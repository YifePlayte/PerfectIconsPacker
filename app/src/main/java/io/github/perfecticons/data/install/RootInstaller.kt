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
    Apatch,
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
            RootKind.KernelSu, RootKind.Apatch -> mountMetamodule != null
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
     * 重新检测前清掉 libsu 的两层陈旧状态，否则运行期间新授予的 root 不会生效。
     *
     * 第一层是结论缓存：`Utils.currentRootState`（-1 未知 / 0 无 su / 1 有 su 未验证
     * / 2 已确认），首次判定后不再重算。
     *
     * 第二层是主 shell 本身。`BuilderImpl.start()` 在拿不到 root 时会退化成
     * `exec("sh")`，而这个非 root shell 会被 `MainShell` 一直接着用（`getCached()`
     * 只看 status 是否有效），用户后来授权也不会换掉它，于是 `id` 永远不是 root。
     * 因此仅在**当前主 shell 不是 root** 时才关闭它，逼下一次访问重建；
     * 已经是 root 的 shell 不动，避免无谓地打断正常工作的会话。
     */
    private fun dropCachedVerdict() {
        runCatching { Shell.getCachedShell() }
            .getOrNull()
            ?.takeIf { !it.isRoot }
            ?.let { stale ->
                Log.i(TAG, "丢弃非 root 主 shell，下次访问重建")
                runCatching { stale.close() }
                    .onFailure { Log.w(TAG, "关闭缓存的 shell 失败", it) }
            }

        runCatching {
            val field = Class.forName(LIBSU_UTILS_CLASS)
                .getDeclaredField(LIBSU_ROOT_STATE_FIELD)
                .apply { isAccessible = true }
            field.setInt(null, LIBSU_ROOT_STATE_UNKNOWN)
        }.onFailure { Log.w(TAG, "重置 libsu root 缓存失败", it) }
    }

    /**
     * @param recheck 用户主动“重新检测”时为 true：先清掉 libsu 的陈旧状态，
     *   再用一次现场命令重新判定。自动检测沿用现有 shell，避免每次进入首页
     *   都重建会话、反复触发授权弹窗。
     */
    suspend fun detect(recheck: Boolean = false): RootStatus = withContext(Dispatchers.IO) {
        if (recheck) dropCachedVerdict()

        // 一次 shell 往返同时拿到 uid、方案品牌与元模块，避免多次 exec() 逐个
        // 争用 MainShell 的创建锁；任何一次卡住都会让 detect() 永不返回。
        val probe = runCatching {
            Shell.cmd(
                "id",
                "echo META:\$(readlink /data/adb/metamodule 2>/dev/null | sed 's#.*/##')",
                "su -v 2>/dev/null | head -n1",
            ).exec()
        }.getOrNull()

        val lines = probe?.out.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        // 是否真的拿到 root 只看 id 的输出；libsu 的三态缓存在重新检测时不可信。
        val isRoot = lines.any { it.contains("uid=0") }
        Log.i(TAG, "probe recheck=$recheck root=$isRoot out=$lines")
        if (!isRoot) return@withContext RootStatus(false, RootKind.None, null)

        // 品牌后缀是“谁在提供 su”的唯一可靠证据：换装 root 方案后
        // /data/adb/magisk 之类的目录仍会残留，目录探测会误判成旧方案。
        val banner = lines.firstOrNull { line ->
            line.endsWith(":MAGISKSU", true) ||
                line.endsWith(":KernelSU", true) ||
                line.endsWith(":APatch", true)
        }.orEmpty()

        val kind = when {
            banner.endsWith(":MAGISKSU", true) -> RootKind.Magisk
            banner.endsWith(":KernelSU", true) -> RootKind.KernelSu
            banner.endsWith(":APatch", true) -> RootKind.Apatch
            else -> RootKind.Other
        }
        // 横幅是 <version>:<brand>，品牌之前的部分才是版本号。
        val version = banner.substringBeforeLast(':', "").trim()
            .takeIf { it.isNotEmpty() && it.firstOrNull()?.isDigit() == true }
        Log.i(TAG, "banner=$banner kind=$kind version=$version")

        // KernelSU 与 APatch 都把 /system 挂载委托给元模块：
        // 没有元模块时，模块里的 system/ 目录不会生效。
        val metamodule = if (kind == RootKind.KernelSu || kind == RootKind.Apatch) {
            lines.firstOrNull { it.startsWith("META:") }
                ?.removePrefix("META:")
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        Log.i(TAG, "metamodule=$metamodule")

        RootStatus(
            granted = true,
            kind = kind,
            version = version,
            mountMetamodule = metamodule,
        )
    }

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
            // APatch 的守护进程在 /data/adb/apd，不在 PATH 上，须写绝对路径。
            RootKind.Apatch -> "/data/adb/apd module install '$path'"
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
