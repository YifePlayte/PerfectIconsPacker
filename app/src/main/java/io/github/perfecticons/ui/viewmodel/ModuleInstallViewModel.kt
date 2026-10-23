package io.github.perfecticons.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.perfecticons.data.install.InstallResult
import io.github.perfecticons.data.install.RootInstaller
import io.github.perfecticons.data.install.RootStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface InstallOutcome {
    data object Success : InstallOutcome
    data class Failure(val message: String) : InstallOutcome
}

data class InstallUiState(
    val rootStatus: RootStatus? = null,
    val isDetecting: Boolean = false,
    val isInstalling: Boolean = false,
    val outcome: InstallOutcome? = null,
    val output: List<io.github.perfecticons.core.log.LogLine> = emptyList(),
    val showLogSheet: Boolean = false,
)

class ModuleInstallViewModel(application: Application) : AndroidViewModel(application) {

    private val installer = RootInstaller()

    private val _state = MutableStateFlow(InstallUiState())
    val state: StateFlow<InstallUiState> = _state.asStateFlow()

    fun detect(recheck: Boolean = false) {
        if (_state.value.isDetecting) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isDetecting = true)
            val status = installer.detect(recheck)
            _state.value = _state.value.copy(isDetecting = false, rootStatus = status)
        }
    }

    fun install(moduleZip: File) {
        if (_state.value.isInstalling) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isInstalling = true,
                outcome = null,
                output = emptyList(),
            )
            _state.value = _state.value.copy(showLogSheet = true)
            val result = installer.install(moduleZip) { line ->
                // RootInstaller streams from a background thread.
                launch(Dispatchers.Main.immediate) {
                    _state.value = _state.value.copy(
                        output = _state.value.output + toLogLine(line),
                    )
                }
            }
            // The streamed lines are already in state; only the verdict changes.
            _state.value = when (result) {
                is InstallResult.Success -> _state.value.copy(
                    isInstalling = false,
                    outcome = InstallOutcome.Success,
                )
                is InstallResult.Failure -> _state.value.copy(
                    isInstalling = false,
                    outcome = InstallOutcome.Failure(result.message),
                )
            }
        }
    }

    fun reboot() {
        viewModelScope.launch { installer.reboot() }
    }

    fun showLog() {
        _state.value = _state.value.copy(showLogSheet = true)
    }

    fun dismissLogSheet() {
        _state.value = _state.value.copy(showLogSheet = false)
    }

    /**
     * Root manager output is unstructured, so tone is inferred from common
     * keywords rather than by parsing a format that does not exist.
     */
    private fun toLogLine(line: String): io.github.perfecticons.core.log.LogLine {
        val lower = line.lowercase()
        val tone = when {
            listOf("error", "fail", "abort", "denied", "cannot", "unable").any(lower::contains) ->
                io.github.perfecticons.core.log.LogTone.Error
            line.startsWith("==>") -> io.github.perfecticons.core.log.LogTone.Step
            listOf("success", "complete", "installed", "done").any(lower::contains) ->
                io.github.perfecticons.core.log.LogTone.Success
            listOf("warn", "skip", "ignore").any(lower::contains) ->
                io.github.perfecticons.core.log.LogTone.Warning
            line.startsWith("- ") || line.startsWith("  ") ->
                io.github.perfecticons.core.log.LogTone.Dim
            else -> io.github.perfecticons.core.log.LogTone.Default
        }
        return io.github.perfecticons.core.log.LogLine(line, tone)
    }
}
