package io.github.perfecticons.domain.assemble

import io.github.perfecticons.core.log.LogLine
import io.github.perfecticons.domain.build.IconArchives
import io.github.perfecticons.domain.build.ModuleBuilder
import io.github.perfecticons.domain.build.MtzBuilder
import io.github.perfecticons.domain.log.BuildLog
import io.github.perfecticons.domain.model.BuildTarget
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class BuildRequest(
    val target: BuildTarget,
    val styles: Set<String>,
    val outputName: String,
    val author: String,
    val versionName: String,
    /** Launcher app icon ids, already ordered by display name. */
    val previewPriority: List<String> = emptyList(),
)

data class BuildResult(
    val output: File,
    val target: BuildTarget,
    val staticCount: Int,
    val dynamicCount: Int,
    val addonCount: Int,
    val elapsedMs: Long,
)

/**
 * Turns the current snapshot plus customizations into a deliverable archive.
 *
 * Each build uses a fresh temporary directory and writes to a single output file,
 * so a failed or cancelled build never leaves a partial artifact behind.
 */
class ThemeBuildService(
    private val snapshotDir: File,
    private val customDir: File,
    private val buildsRoot: File,
    private val defaultTransformConfig: File,
) {
    /**
     * Builds the archive, reporting progress through [onLine] as it happens.
     *
     * The callback fires on the calling thread, so callers that own UI state must
     * dispatch it themselves.
     */
    fun build(
        request: BuildRequest,
        onLine: (LogLine) -> Unit = {},
    ): BuildResult {
        val log = BuildLog(onLine)
        val started = System.currentTimeMillis()
        val buildId = LocalDateTime.now().format(TIMESTAMP)
        val workDir = File(buildsRoot, "work-$buildId")
        val outputDir = File(buildsRoot, "output")
        // Builds are serialized, so every other work directory is stale
        // (crashes skip the finally cleanup and would otherwise pile up).
        buildsRoot.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("work-") && it.name != workDir.name }
            .forEach { it.deleteRecursively() }
        workDir.deleteRecursively()
        workDir.mkdirs()
        outputDir.mkdirs()
        val targetName = if (request.target == BuildTarget.Module) "模块" else "MTZ"
        log.step("开始构建 $targetName")
        log.info("工作目录：${workDir.absolutePath}")
        try {
            log.step("合并图标资源")
            log.info("主题：${request.styles.joinToString(", ")}")
            log.info("自定义目录：${customDir.absolutePath}")
            val theme = ThemeAssembler(
                snapshotDir = snapshotDir,
                customDir = customDir,
                defaultTransformConfig = defaultTransformConfig,
            ).assemble(request.styles)
            // Resource counts, same scope as the home stat tiles; the custom
            // overlay is reported by the 覆盖 lines below.
            log.info("静态分层：${theme.stats.upstreamStatic}")
            log.info("动态分层：${theme.stats.upstreamDynamic}")
            log.info("单张图标：${theme.flatIcons.size}")
            log.info("禁用静态：${theme.stats.disabledStatic}")
            log.info("禁用动态：${theme.stats.disabledDynamic}")
            log.info("覆盖静态分层：${theme.stats.overriddenStatic}")
            log.info("覆盖动态分层：${theme.stats.overriddenDynamic}")
            log.info("遮罩：${theme.transformConfig ?: "未提供"}")
            log.info("高级附加：${theme.addons.size}")
            if (theme.addons.isNotEmpty()) {
                theme.addons.keys.sorted().forEach { log.info("  附加 $it") }
            }
            check(theme.staticCount > 0 || theme.dynamicCount > 0) {
                "没有可打包的图标，请先同步资源。"
            }

            val safeName = sanitizeFileName(request.outputName)
            log.step("写入压缩包")
            val totalStatic = theme.staticWriteCount
            var lastReported = -1
            val progress: (Int, Int) -> Unit = { written, total ->
                // Report every 20% so the sheet moves without flooding the log
                // with twenty near-identical lines.
                if (total > 0) {
                    val percent = written * 100 / total
                    val bucket = percent / 20
                    if (bucket != lastReported / 20) {
                        lastReported = percent
                        log.info("写入静态图标 $written/$total（$percent%）")
                    }
                }
            }
            val output = when (request.target) {
                BuildTarget.Module -> ModuleBuilder(theme, workDir, progress).build(
                    File(outputDir, "$safeName.zip"),
                    ModuleBuilder.ModuleInfo(
                        id = MODULE_ID,
                        name = MODULE_NAME,
                        version = request.versionName,
                        versionCode = versionCode(),
                        author = request.author,
                        description = "HyperOS 4 完美图标补全 · ${request.styles.joinToString(", ")}",
                        style = request.styles.joinToString(","),
                    ),
                )

                BuildTarget.Mtz -> MtzBuilder(
                    theme = theme,
                    workDir = workDir,
                    previewPriority = request.previewPriority,
                    onProgress = progress,
                ).build(
                    File(outputDir, "$safeName.mtz"),
                    MtzBuilder.MtzInfo(
                        title = MTZ_TITLE,
                        description = "HyperOS 4 完美图标补全\n主题：${request.styles.joinToString(", ")}",
                        author = request.author,
                        // The old packer's MTZ description pins 1.0.0 here; the
                        // timestamped version belongs to the module only.
                        version = MTZ_VERSION,
                    ),
                )
            }

            log.step("校验产物")
            val elapsed = System.currentTimeMillis() - started
            log.info("输出文件：${output.name}")
            log.info("文件大小：${formatSize(output.length())}")
            log.info("耗时：${elapsed} ms")
            log.done("构建完成")
            return BuildResult(
                output = output,
                target = request.target,
                staticCount = theme.staticCount,
                dynamicCount = theme.dynamicCount,
                addonCount = theme.addonCount,
                elapsedMs = elapsed,
            )
        } finally {
            workDir.deleteRecursively()
            // A failed archive never becomes a result; sweep half-written temps.
            outputDir.listFiles().orEmpty()
                .filter { it.name.endsWith(".tmp") }
                .forEach { it.delete() }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1_024 -> String.format("%.0f KB", bytes / 1_024.0)
        else -> "$bytes B"
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            // Direction overrides and line separators enable display spoofing.
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f\\u0080-\\u009f\\u2028\\u2029\\u202a-\\u202e]"), "_")
            .trim()
            .take(100)
            .trimEnd('.')
        return cleaned.ifBlank { "完美图标补全" }
    }

    private fun versionCode(): Int =
        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyMMddHH")).toIntOrNull() ?: 1

    private companion object {
        // Identity carried over from the old packer so installs land as an
        // update of the existing module instead of a second one.
        const val MODULE_ID = "MIUIiconsplus"
        const val MODULE_NAME = "MIUI 完美图标补全"
        const val MTZ_TITLE = "完美图标补全计划"
        const val MTZ_VERSION = "1.0.0"
        // Milliseconds keep parallel-entry builds from sharing a work dir.
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    }
}
