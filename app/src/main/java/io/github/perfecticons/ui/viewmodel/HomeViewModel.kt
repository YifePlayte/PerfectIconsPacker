package io.github.perfecticons.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.perfecticons.R
import io.github.perfecticons.data.apps.InstalledApps
import io.github.perfecticons.data.apps.PreviewPriority
import io.github.perfecticons.data.net.RepoEndpoint
import io.github.perfecticons.domain.assemble.ThemeAssembler
import io.github.perfecticons.data.repo.IconRepository
import io.github.perfecticons.data.repo.RepoPaths
import io.github.perfecticons.data.repo.RepoSnapshot
import io.github.perfecticons.data.settings.SettingsRepository
import io.github.perfecticons.domain.assemble.BuildRequest
import io.github.perfecticons.core.util.runCatchingCancellable
import io.github.perfecticons.data.repo.RepoLock
import io.github.perfecticons.domain.assemble.BuildResult
import io.github.perfecticons.domain.assemble.ThemeBuildService
import io.github.perfecticons.domain.model.BuildTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Result of checking the upstream icon repository for a newer snapshot. */
sealed interface UpstreamStatus {
    data object Checking : UpstreamStatus
    data object UpToDate : UpstreamStatus
    data class UpdateAvailable(val commit: String) : UpstreamStatus
    data object Unavailable : UpstreamStatus
}

data class HomeUiState(
    val snapshot: RepoSnapshot? = null,
    val selectedStyles: Set<String> = emptySet(),
    val upstreamStatic: Int? = null,
    val upstreamDynamic: Int? = null,
    val upstream: UpstreamStatus = UpstreamStatus.Checking,
    val isBuilding: Boolean = false,
    val result: BuildResult? = null,
    val error: String? = null,
    val savedMessage: String? = null,
    val buildLog: List<io.github.perfecticons.core.log.LogLine> = emptyList(),
    val showLogSheet: Boolean = false,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsRepository(application)
    private val repository = IconRepository(
        root = RepoPaths.root(application),
        remoteUrls = { RepoEndpoint.candidates(settings.repoUrl, settings.accelerator) },
    )
    private val customDir = RepoPaths.customRoot(application)
    private val buildsRoot = RepoPaths.buildsRoot(application)

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** Snapshot commit the upstream check was last run for. */
    private var checkedCommit: String? = null

    /** Endpoint list the last upstream check used; a settings change re-runs it. */
    private var checkedEndpoint: String? = null

    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { repository.status().snapshot }
            _state.value = _state.value.copy(
                snapshot = snapshot,
                selectedStyles = settings.iconStyles,
            )
            if (snapshot != null) {
                val counts = withContext(Dispatchers.IO) {
                    ThemeAssembler(
                        snapshotDir = repository.snapshotDir,
                        customDir = customDir,
                        defaultTransformConfig = RepoPaths.defaultTransformConfig(getApplication()),
                    ).upstreamIconCounts(settings.iconStyles)
                }
                _state.value = _state.value.copy(
                    upstreamStatic = counts.first,
                    upstreamDynamic = counts.second,
                )
            }
            // The upstream check runs at most once per snapshot commit: on first
            // open, and again only after a sync moves the snapshot. A changed
            // repository or accelerator also re-runs it, since it probes a
            // different remote.
            if (snapshot != null && (snapshot.commit != checkedCommit || endpointKey() != checkedEndpoint)) {
                checkUpstream(snapshot)
            }
        }
    }

    /** Identifies the configured endpoints; a change invalidates the last check. */
    private fun endpointKey(): String =
        RepoEndpoint.candidates(settings.repoUrl, settings.accelerator).joinToString("|")

    /** Asks upstream for its current head and reports whether the snapshot is stale. */
    private fun checkUpstream(snapshot: RepoSnapshot) {
        checkedCommit = snapshot.commit
        checkedEndpoint = endpointKey()
        viewModelScope.launch {
            val head = repository.remoteHead()
            _state.value = _state.value.copy(
                upstream = when {
                    head == null -> UpstreamStatus.Unavailable
                    head == snapshot.commit -> UpstreamStatus.UpToDate
                    else -> UpstreamStatus.UpdateAvailable(head)
                },
            )
        }
    }

    fun build(target: BuildTarget) {
        if (_state.value.isBuilding) return
        val styles = settings.iconStyles
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { repository.status().snapshot }
            if (snapshot == null) {
                _state.value = _state.value.copy(
                    error = getApplication<Application>().getString(R.string.home_status_hint),
                )
                return@launch
            }
            if (snapshot.styles.toSet() != styles) {
                _state.value = _state.value.copy(
                    error = getApplication<Application>().getString(R.string.home_resync_hint),
                )
                return@launch
            }
            _state.value = _state.value.copy(
                isBuilding = true,
                error = null,
                result = null,
                buildLog = emptyList(),
                showLogSheet = true,
            )
            val launcherApps = withContext(Dispatchers.IO) {
                InstalledApps(getApplication()).load()
            }
            val previewPriority = PreviewPriority.order(launcherApps)
            val request = BuildRequest(
                target = target,
                styles = styles,
                outputName = defaultOutputName(styles),
                author = settings.author,
                versionName = buildVersion(),
                previewPriority = previewPriority,
            )
            runCatchingCancellable {
                withContext(Dispatchers.IO) {
                    // Holds the snapshot lock so a sync cannot swap the tree
                    // under the assembler.
                    RepoLock.snapshot.withLock {
                    ThemeBuildService(
                        snapshotDir = repository.snapshotDir,
                        customDir = customDir,
                        buildsRoot = buildsRoot,
                        defaultTransformConfig = RepoPaths.defaultTransformConfig(getApplication()),
                    ).build(request) { line ->
                        // Build runs on IO; append through the main dispatcher so
                        // Compose sees each line while the build is still working.
                        launch(Dispatchers.Main.immediate) {
                            _state.value = _state.value.copy(
                                buildLog = _state.value.buildLog + line,
                            )
                        }
                    }
                    }
                }
            }.onSuccess { result ->
                _state.value = _state.value.copy(
                    isBuilding = false,
                    result = result,
                )
            }.onFailure { throwable ->
                _state.value = _state.value.copy(
                    isBuilding = false,
                    error = throwable.message ?: throwable::class.java.simpleName,
                )
            }
        }
    }

    fun clearResult() {
        _state.value = _state.value.copy(result = null)
    }

    /**
     * Copies the last artifact to a user-selected document. The build directory is
     * private, so exporting is the only way to keep a deliverable.
     */
    /** Copies the artifact on a background thread; [openOutputStream] runs there too. */
    fun export(output: java.io.File, openOutputStream: () -> java.io.OutputStream?) {
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                    val stream = openOutputStream() ?: return@runCatchingCancellable false
                    stream.use { target ->
                        output.inputStream().use { source -> source.copyTo(target) }
                    }
                    true
                }
            }
            outcome.fold(
                onSuccess = { saved ->
                    _state.value = if (saved) {
                        _state.value.copy(
                            savedMessage = getApplication<Application>()
                                .getString(R.string.home_saved, output.name),
                        )
                    } else {
                        _state.value.copy(
                            error = getApplication<Application>()
                                .getString(R.string.common_save_failed),
                        )
                    }
                },
                onFailure = {
                    _state.value = _state.value.copy(
                        error = it.message
                            ?: getApplication<Application>().getString(R.string.common_save_failed),
                    )
                },
            )
        }
    }

    fun clearSavedMessage() {
        _state.value = _state.value.copy(savedMessage = null)
    }

    fun showBuildLog() {
        _state.value = _state.value.copy(showLogSheet = true)
    }

    fun dismissLogSheet() {
        _state.value = _state.value.copy(showLogSheet = false)
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * 产物文件名：`完美图标补全计划-<主题名>-yyyyMMdd-HHmmss`，用设备时区。
     * 主题选择是单选，这里只取唯一值；秒级时间戳让同一分钟内的多次构建
     * 也不会重名（系统选择器会在重名时追加难看的 " (1)"）。
     */
    private fun defaultOutputName(styles: Set<String>): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val theme = styles.firstOrNull().orEmpty().trim()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f\\u0080-\\u009f\\u2028\\u2029\\u202a-\\u202e]"), "_")
            .trim('_')
        val parts = listOfNotNull(
            ARTIFACT_PREFIX,
            theme.takeIf { it.isNotBlank() },
            stamp,
        )
        return parts.joinToString("-")
    }

    /** Module version follows the old packer: `yyyyMMddHHmm` build timestamp. */
    private fun buildVersion(): String =
        SimpleDateFormat("yyyyMMddHHmm", Locale.US).format(Date())

    private companion object {
        /** 产物文件名前缀，与模块/MTZ 的显示名保持一致。 */
        const val ARTIFACT_PREFIX = "完美图标补全计划"
    }
}
