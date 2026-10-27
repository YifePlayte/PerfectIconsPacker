package io.github.perfecticons.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.perfecticons.core.util.runCatchingCancellable
import io.github.perfecticons.data.net.RepoEndpoint
import io.github.perfecticons.data.repo.IconRepository
import io.github.perfecticons.data.repo.RepoPaths
import io.github.perfecticons.data.repo.RepoSnapshot
import io.github.perfecticons.data.repo.SyncProgress
import io.github.perfecticons.data.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ResourcesUiState(
    val snapshot: RepoSnapshot? = null,
    val isSyncing: Boolean = false,
    val progress: SyncProgress? = null,
    val error: String? = null,
    val availableStyles: List<String> = emptyList(),
    val selectedStyles: Set<String> = emptySet(),
    /** Repository label and accelerator as currently configured, shown read-only. */
    val repositoryName: String = "",
    val accelerator: String = "",
    /** Configured repository URL, compared against the snapshot's source. */
    val repoUrl: String = "",
) {
    val isSynced: Boolean get() = snapshot != null

    /** The selected styles are not materialized yet; a re-sync is needed. */
    val stylesOutOfSync: Boolean
        get() = snapshot != null && snapshot.styles.toSet() != selectedStyles

    /**
     * The snapshot came from a different repository than the configured one.
     * Snapshots from before the field existed are trusted, since re-syncing
     * then flags nothing and a mismatch would otherwise be permanent.
     */
    val snapshotFromOtherRepo: Boolean
        get() {
            val source = snapshot?.sourceRepo ?: return false
            return source != repoUrl
        }
}

class ResourcesViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsRepository(application)
    private val repository = IconRepository(
        root = RepoPaths.root(application),
        remoteUrls = { RepoEndpoint.candidates(settings.repoUrl, settings.accelerator) },
    )

    private val _state = MutableStateFlow(
        ResourcesUiState(selectedStyles = settings.iconStyles).withCurrentRepository(),
    )
    val state: StateFlow<ResourcesUiState> = _state.asStateFlow()

    private var syncJob: Job? = null

    /** Copies the configured repository settings into the UI state. */
    private fun ResourcesUiState.withCurrentRepository(): ResourcesUiState = copy(
        repoUrl = RepoEndpoint.normalizeBase(settings.repoUrl),
        repositoryName = RepoEndpoint.displayName(settings.repoUrl),
        accelerator = settings.accelerator.trim(),
    )

    init {
        // The snapshot read and the jgit style listing stay off the main thread;
        // the picker fills in as soon as both are available.
        viewModelScope.launch {
            val (snapshot, styles) = withContext(Dispatchers.IO) {
                repository.status().snapshot to repository.availableStyles()
            }
            _state.value = _state.value.copy(snapshot = snapshot, availableStyles = styles)
                .withCurrentRepository()
        }
    }

    /** Selects the icon style used by the next sync and every build. */
    fun setStyle(style: String) {
        settings.iconStyles = setOf(style)
        _state.value = _state.value.copy(selectedStyles = setOf(style))
    }

    fun sync(styles: Set<String> = settings.iconStyles) {
        if (syncJob?.isActive == true) return
        syncJob = viewModelScope.launch {
            // The endpoints are re-read from settings here, so a changed
            // repository URL or accelerator applies to the very next sync.
            _state.value = _state.value.copy(
                isSyncing = true,
                error = null,
                progress = null,
            ).withCurrentRepository()
            runCatchingCancellable {
                repository.sync(styles) { progress ->
                    _state.value = _state.value.copy(progress = progress)
                }
            }.onSuccess { snapshot ->
                // snapshot.styles is only what this sync materialized; the
                // picker keeps the full upstream list.
                _state.value = ResourcesUiState(
                    snapshot = snapshot,
                    availableStyles = repository.availableStyles()
                        .ifEmpty { _state.value.availableStyles },
                    selectedStyles = settings.iconStyles,
                ).withCurrentRepository()
            }.onFailure { throwable ->
                _state.value = _state.value.copy(
                    isSyncing = false,
                    error = throwable.message ?: throwable::class.java.simpleName,
                )
            }
        }
    }

    fun clear() {
        viewModelScope.launch {
            val styles = withContext(Dispatchers.IO) {
                repository.clear()
                repository.availableStyles()
            }
            _state.value = ResourcesUiState(
                availableStyles = styles,
                selectedStyles = settings.iconStyles,
            ).withCurrentRepository()
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    /**
     * Picks up repository settings edited on another tab. Cheap (prefs read
     * only), so it runs whenever the resources tab becomes active.
     */
    fun refreshSettings() {
        _state.value = _state.value.withCurrentRepository()
    }

    companion object {
        val DEFAULT_STYLES = SettingsRepository.DEFAULT_ICON_STYLES
    }
}
