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
) {
    val isSynced: Boolean get() = snapshot != null

    /** The selected styles are not materialized yet; a re-sync is needed. */
    val stylesOutOfSync: Boolean
        get() = snapshot != null && snapshot.styles.toSet() != selectedStyles
}

class ResourcesViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsRepository(application)
    private val repository = IconRepository(
        root = RepoPaths.root(application),
        remoteUrls = RepoEndpoint.candidates(settings.repoUrl, settings.accelerator),
    )

    private val _state = MutableStateFlow(ResourcesUiState(selectedStyles = settings.iconStyles))
    val state: StateFlow<ResourcesUiState> = _state.asStateFlow()

    private var syncJob: Job? = null

    init {
        // The snapshot read and the jgit style listing stay off the main thread;
        // the picker fills in as soon as both are available.
        viewModelScope.launch {
            val (snapshot, styles) = withContext(Dispatchers.IO) {
                repository.status().snapshot to repository.availableStyles()
            }
            _state.value = _state.value.copy(snapshot = snapshot, availableStyles = styles)
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
            _state.value = _state.value.copy(isSyncing = true, error = null, progress = null)
            runCatchingCancellable {
                IconRepository(
                    root = RepoPaths.root(getApplication()),
                    remoteUrls = RepoEndpoint.candidates(settings.repoUrl, settings.accelerator),
                ).sync(styles) { progress ->
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
                )
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
            )
        }
    }

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    companion object {
        val DEFAULT_STYLES = setOf("Hyper")
    }
}
