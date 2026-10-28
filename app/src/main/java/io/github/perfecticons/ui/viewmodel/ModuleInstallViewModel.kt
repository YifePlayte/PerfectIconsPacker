package io.github.perfecticons.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.perfecticons.data.install.InstallResult
import io.github.perfecticons.data.install.RootInstaller
import io.github.perfecticons.data.install.RootStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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

    private companion object {
        const val TAG = "ModuleInstallVM"

        /** root shell 首次往返可能等授权弹窗，需要余量但必须有上限。 */
        const val DETECT_TIMEOUT_MS = 20_000L

        /** 安装日志在界面上只保留最近这么多行，避免逐行复制把主线程拖死。 */
        const val INSTALL_LOG_WINDOW = 500
    }

    private val installer = RootInstaller()

    /** 原子互斥：`isDetecting` 的状态检查不是原子的，并发点击会一起进入。 */
    private val detectInFlight = AtomicBoolean(false)

    private val _state = MutableStateFlow(InstallUiState())
    val state: StateFlow<InstallUiState> = _state.asStateFlow()

    /**
     * 检测必须有终点：`installer.detect()` 要等一次 root shell 往返，
     * 任何意外阻塞都会让 `isDetecting` 永远为 true、按钮永久变灰，
     * 因此这里加互斥、超时和异常兜底。
     */
    fun detect(recheck: Boolean = false) {
        if (!detectInFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(isDetecting = true)
                val status = withTimeoutOrNull(DETECT_TIMEOUT_MS) { installer.detect(recheck) }
                _state.value = _state.value.copy(
                    isDetecting = false,
                    rootStatus = status ?: _state.value.rootStatus,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "root 检测失败", e)
                _state.value = _state.value.copy(isDetecting = false)
            } finally {
                detectInFlight.set(false)
            }
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

            // 安装输出可能有数万行（大模块逐条 inflating），逐行 copy 整个列表
            // 是 O(n²)，会把主线程拖死，看起来就是安装卡住不动。
            // 这里用可变缓冲并只保留最近若干行，界面要的是结论与错误，
            // 不是每一行解压明细。
            //
            // 注意：不能拿 InstallResult.output 来重建。libsu 的 CallbackList
            // 只投递回调、不存储数据，所以传进来的列表作为结果时 size() 恒为 0；
            // 用它会把手上的日志全部抹掉。收尾只保留 window 自身的内容。
            val window = mutableListOf<io.github.perfecticons.core.log.LogLine>()
            val result = installer.install(moduleZip) { line ->
                val entry = toLogLine(line)
                launch(Dispatchers.Main.immediate) {
                    window += entry
                    if (window.size > INSTALL_LOG_WINDOW) {
                        window.subList(0, window.size - INSTALL_LOG_WINDOW).clear()
                    }
                    _state.value = _state.value.copy(output = window.toList())
                }
            }
            // install() 返回时所有回调都已执行完（它内部 awaitTermination 过），
            // window 就是最终内容，直接带上即可。
            _state.value = when (result) {
                is InstallResult.Success -> _state.value.copy(
                    isInstalling = false,
                    outcome = InstallOutcome.Success,
                    output = window.toList(),
                )
                is InstallResult.Failure -> _state.value.copy(
                    isInstalling = false,
                    outcome = InstallOutcome.Failure(result.message),
                    output = window.toList(),
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
