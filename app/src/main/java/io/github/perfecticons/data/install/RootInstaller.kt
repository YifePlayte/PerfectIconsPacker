package io.github.perfecticons.data.install

import android.util.Log
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Which root implementation is currently active. */
enum class RootKind {
    Magisk,
    KernelSu,
    Other,
    None,
}

data class RootStatus(
    val granted: Boolean,
    val kind: RootKind,
    val version: String?,
    /** Name of the active mount metamodule, if any. KernelSU needs one. */
    val mountMetamodule: String? = null,
) {
    val canInstall: Boolean get() = granted && kind != RootKind.None

    /**
     * KernelSU delegates `/system` mounting to a metamodule. Any active
     * metamodule that implements mounting counts.
     */
    val hasMountProvider: Boolean?
        get() = when (kind) {
            RootKind.KernelSu -> mountMetamodule != null
            RootKind.Magisk -> null
            RootKind.Other, RootKind.None -> null
        }
}

sealed interface InstallResult {
    data class Success(val output: List<String>) : InstallResult
    data class Failure(val message: String, val output: List<String> = emptyList()) : InstallResult
}

/**
 * Installs the single generated module zip with whichever root implementation is
 * present. The archive is identical for both; only the manager command differs.
 */
class RootInstaller {

    private companion object {
        const val TAG = "RootInstaller"
        const val EXIT_MARKER = "__PIC_EXIT__:"

        /** libsu 缓存字段名，见 [dropCachedVerdict]。 */
        const val LIBSU_UTILS_CLASS = "com.topjohnwu.superuser.internal.Utils"
        const val LIBSU_ROOT_STATE_FIELD = "currentRootState"
        const val LIBSU_ROOT_STATE_UNKNOWN = -1
    }

    /**
     * 重新检测时清掉 libsu 的进程级缓存，否则运行期间新授予的 root 永远不会生效。
     *
     * libsu 缓存两层状态：
     * - `Shell.isAppGrantedRoot()` 把结论记在私有的 `Utils.currentRootState` 里
     *   （-1 未知、0 无 su、1 有 su 未验证、2 已确认）。首次判定后不再重算。
     * - 建好的 shell 会作为主 shell 缓存；一次失败的非 root shell 会被后续所有
     *   命令复用，于是 `Shell.cmd("id")` 永远不是 root。
     *
     * KernelSU 只有对已授权的应用才让 `su` 可见，所以未授权时这里判定为“无 su”
     * 并一直缓存；授予之后必须重置才能重新读取。重置不做任何前提判断，即使当前
     * 是 root shell 也一并丢弃，保证重新检测拿到的永远是现场状态。
     */
    private fun dropCachedVerdict() {
        runCatching { Shell.getCachedShell()?.close() }
            .onFailure { Log.w(TAG, "关闭缓存的 shell 失败", it) }
        runCatching {
            val field = Class.forName(LIBSU_UTILS_CLASS)
                .getDeclaredField(LIBSU_ROOT_STATE_FIELD)
                .apply { isAccessible = true }
            field.setInt(null, LIBSU_ROOT_STATE_UNKNOWN)
        }.onFailure { Log.w(TAG, "重置 libsu root 缓存失败", it) }
    }

    /**
     * @param recheck 用户主动“重新检测”时为 true：无条件丢弃 libsu 缓存后重新探测，
     *   不复用任何旧结论。自动检测沿用现有缓存，避免每次进入首页都重建 root shell。
     */
    suspend fun detect(recheck: Boolean = false): RootStatus = withContext(Dispatchers.IO) {
        if (recheck) dropCachedVerdict()

        // libsu returns three distinct states, not a boolean:
        //   true  -> uid 0, or the app was already granted
        //   false -> no su binary on PATH, root is impossible
        //   null  -> su exists but the grant is unverified
        // Treating null as "denied" is wrong.
        val grantedFlag = runCatching { Shell.isAppGrantedRoot() }.getOrNull()
        val granted = when {
            // 重新检测不复用任何旧结论，直接执行 su 拿现场结果。
            recheck -> verifyRootByExecution()
            grantedFlag == true -> true
            grantedFlag == null -> verifyRootByExecution()
            else -> false
        }
        Log.i(TAG, "isAppGrantedRoot=$grantedFlag rootUsable=$granted")
        if (!granted) return@withContext RootStatus(false, RootKind.None, null)

        val probe = runCatching {
            Shell.cmd(
                "command -v magisk >/dev/null 2>&1 && echo magisk",
                "command -v ksud >/dev/null 2>&1 && echo ksud",
                "test -d /data/adb/ksu && echo ksu-dir",
                "test -d /data/adb/magisk && echo magisk-dir",
                "magisk -V 2>/dev/null | head -n1",
                "ksud -V 2>/dev/null | head -n1",
            ).exec()
        }.getOrNull()

        val lines = probe?.out.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        val kind = when {
            lines.any { it == "magisk" || it == "magisk-dir" } -> RootKind.Magisk
            lines.any { it == "ksud" || it == "ksu-dir" } -> RootKind.KernelSu
            else -> RootKind.Other
        }
        val version = lines.firstOrNull { it.firstOrNull()?.isDigit() == true }

        // KernelSU delegates mounting to a metamodule. Read its module.prop
        // instead of hardcoding a specific implementation, because any
        // metamodule that implements mounting is equally valid.
        val metamodule = if (kind == RootKind.KernelSu) {
            runCatching {
                Shell.cmd(
                    "grep -m1 '^id=' /data/adb/metamodule/module.prop 2>/dev/null | cut -d= -f2-",
                ).exec()
            }.getOrNull()
                ?.out
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }

        RootStatus(
            granted = true,
            kind = kind,
            version = version,
            mountMetamodule = metamodule,
        )
    }

    /**
     * Confirms root by actually running a command. Only called when libsu reports
     * `null`, because on a device without root this would raise a pointless
     * superuser prompt.
     */
    private fun verifyRootByExecution(): Boolean = runCatching {
        Shell.cmd("id").exec().out.any { it.contains("uid=0") }
    }.getOrDefault(false)

    /**
     * Installs and reports output line by line.
     *
     * The root manager writes progress through stdout/stderr, and installation can
     * take tens of seconds for a large module, so blocking until completion would
     * look like a freeze. [onLine] is invoked from a background thread.
     */
    suspend fun install(
        moduleZip: File,
        onLine: (String) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        if (!moduleZip.isFile) {
            return@withContext InstallResult.Failure("模块文件不存在")
        }
        val path = moduleZip.absolutePath.replace("'", "'\\''")
        val status = detect()
        if (!status.granted) {
            return@withContext InstallResult.Failure("未获得 root 权限")
        }

        val command = when (status.kind) {
            RootKind.Magisk -> "magisk --install-module '$path'"
            RootKind.KernelSu -> "ksud module install '$path'"
            RootKind.Other, RootKind.None ->
                return@withContext InstallResult.Failure("未检测到受支持的 root 方案")
        }

        onLine("$ $command")
        val collected = java.util.Collections.synchronizedList(mutableListOf<String>())
        val exitCode = java.util.concurrent.atomic.AtomicInteger(Int.MIN_VALUE)

        runCatching {
            val shell = Shell.getShell()
            shell.execTask(object : Shell.Task {
                override fun run(
                    stdin: java.io.OutputStream,
                    stdout: java.io.InputStream,
                    stderr: java.io.InputStream,
                ) {
                    // Start both pumps before writing so neither pipe can fill up
                    // and block the root process mid-install.
                    val errThread = Thread {
                        runCatching {
                            stderr.bufferedReader().useLines { lines ->
                                lines.forEach { line -> publish(line, onLine, collected) }
                            }
                        }
                    }.apply { start() }

                    val outThread = Thread {
                        runCatching {
                            stdout.bufferedReader().useLines { lines ->
                                lines.forEach { line ->
                                    if (line.startsWith(EXIT_MARKER)) {
                                        exitCode.set(
                                            line.removePrefix(EXIT_MARKER).trim().toIntOrNull() ?: 0,
                                        )
                                    } else {
                                        publish(line, onLine, collected)
                                    }
                                }
                            }
                        }
                    }.apply { start() }

                    // Do not close stdin before the marker arrives: closing early
                    // makes the shell drop the queued command line.
                    stdin.bufferedWriter().apply {
                        write(command)
                        newLine()
                        write("echo $EXIT_MARKER$?")
                        newLine()
                        flush()
                    }

                    outThread.join()
                    errThread.join()
                }
            })
        }.getOrElse {
            return@withContext InstallResult.Failure(it.message ?: "执行失败", collected.toList())
        }

        val code = exitCode.get()
        return@withContext if (code == 0 || code == Int.MIN_VALUE) {
            InstallResult.Success(collected.toList())
        } else {
            InstallResult.Failure("安装失败（退出码 $code）", collected.toList())
        }
    }

    private fun publish(
        line: String,
        onLine: (String) -> Unit,
        collected: MutableList<String>,
    ) {
        val text = line.trimEnd('\r')
        if (text.isBlank()) return
        collected += text
        onLine(text)
    }

    /** Reboots the device; only called after explicit user confirmation. */
    suspend fun reboot(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            Shell.cmd("svc power reboot || reboot").exec().isSuccess
        }.getOrDefault(false)
    }
}
