package io.github.perfecticons.domain.build

import io.github.perfecticons.domain.model.AssembledTheme
import java.io.File
import java.nio.file.Files

/**
 * Writes the inner archives read by the HyperOS theme manager.
 *
 * HyperOS 4 keeps `icons` and `dynamicicons` as separate zip files inside the
 * built-in theme directory. MTZ still uses a single `icons` archive, so the
 * dynamic entries can be appended to the same writer.
 *
 * Static icon counting is reported through [onProgress] because writing several
 * thousand PNGs is the slowest part of a build.
 */
class IconArchives(
    private val theme: AssembledTheme,
    private val workDir: File,
    private val onProgress: (written: Int, total: Int) -> Unit = { _, _ -> },
) {
    fun build(includeDynamicIcons: Boolean): Result {
        workDir.mkdirs()
        val iconsZip = File(workDir, "icons")
        val dynamicZip = File(workDir, "dynamicicons")

        val totalStatic = theme.staticWriteCount
        var written = 0
        val report = {
            written++
            onProgress(written, totalStatic)
        }

        ThemeZipWriter(iconsZip.outputStream()).use { writer ->
            writeStatic(writer, report)
            if (includeDynamicIcons) {
                writeDynamic(writer, report = null)
            }
        }

        if (includeDynamicIcons) {
            dynamicZip.delete()
        } else {
            ThemeZipWriter(dynamicZip.outputStream()).use { writer ->
                writeDynamic(writer, report = null)
            }
        }

        return Result(iconsZip, dynamicZip.takeIf { it.isFile })
    }

    private fun writeStatic(writer: ThemeZipWriter, report: () -> Unit) {
        theme.staticIcons.forEach { icon ->
            icon.background?.let {
                writer.addFile("res/drawable-xxhdpi/${icon.id}/0.png", File(it))
                report()
            }
            icon.foreground?.let {
                writer.addFile("res/drawable-xxhdpi/${icon.id}/1.png", File(it))
                report()
            }
        }
        theme.transformConfig?.let {
            writer.addFile("transform_config.xml", File(it))
            report()
        }
    }

    private fun writeDynamic(writer: ThemeZipWriter, report: (() -> Unit)?) {
        theme.dynamicIcons.forEach { icon ->
            val root = File(icon.directory)
            if (!root.isDirectory) return@forEach
            root.walkTopDown()
                .onEnter { !Files.isSymbolicLink(it.toPath()) }
                .forEach { file ->
                if (Files.isSymbolicLink(file.toPath())) return@forEach
                val relative = file.relativeTo(root).invariantSeparatorsPath
                val entry = "layer_animating_icons/${icon.id}/$relative"
                if (file.isDirectory) {
                    writer.addDirectory(entry)
                } else {
                    writer.addFile(entry, file)
                }
                report?.invoke()
            }
        }
    }

    data class Result(
        val icons: File,
        val dynamicIcons: File?,
    )
}
