package io.github.perfecticons.domain.build

import io.github.perfecticons.domain.model.AssembledTheme
import java.io.File

/**
 * Produces an MTZ theme.
 *
 * Unlike the module, MTZ keeps the animated icons inside the single `icons`
 * archive. Advanced addons live under `addons/` and keep their relative paths.
 */
class MtzBuilder(
    private val theme: AssembledTheme,
    private val workDir: File,
    /** Injectable so JVM tests can skip Android graphics entirely. */
    private val artworkFactory: (() -> ArtworkProvider)? = null,
    /** Preferred icon ids for the generated preview, most important first. */
    private val previewPriority: List<String> = emptyList(),
    /** Reports static icon writing progress to the caller. */
    private val onProgress: (written: Int, total: Int) -> Unit = { _, _ -> },
) {
    interface ArtworkProvider {
        /** Preview artwork as (zip entry path, file) pairs. */
        fun renderPreviews(): List<Pair<String, File>>
    }

    fun build(output: File, info: MtzInfo): File {
        workDir.mkdirs()
        val archives = IconArchives(theme, workDir, onProgress).build(includeDynamicIcons = true)
        val icons = archives.icons

        output.parentFile?.mkdirs()
        val temp = File(output.parentFile, "${output.name}.tmp")
        temp.delete()

        ThemeZipWriter(temp.outputStream()).use { writer ->
            writer.addBytes("description.xml", info.toDescriptionXml().toByteArray(), deflated = true)
            writer.addFile("icons", icons)

            theme.flatIcons.forEach { (id, source) ->
                writer.addFile("miui_mod_icons/$id.png", File(source))
            }

            // `com.miui.home` must exist as an empty zip for the theme to be
            // recognized as a home-screen theme; a same-name addon replaces it.
            if (!theme.addons.containsKey("com.miui.home")) {
                writer.addBytes("com.miui.home", EMPTY_ZIP)
            }

            // Advanced addons keep their relative path at the archive root.
            theme.addons.forEach { (relative, source) ->
                if (SafeZipPath.isSafe(relative) &&
                    !SafeZipPath.isReservedFor(SafeZipPath.Target.Mtz, relative)
                ) {
                    writer.addFile(relative, File(source))
                }
            }

            val artwork = artworkFactory?.invoke()
                ?: ThemeArtwork(theme, workDir, previewPriority)
            artwork.renderPreviews().forEach { (entry, file) ->
                writer.addFile(entry, file)
            }
        }

        if (!temp.renameTo(output)) {
            temp.copyTo(output, overwrite = true)
            temp.delete()
        }
        return output
    }

    data class MtzInfo(
        val title: String,
        val description: String,
        val author: String,
        val version: String,
    ) {
        /**
         * MTZ root description, byte-shaped like the old packer's: the XML
         * declaration must be the very first bytes and lines carry no indent,
         * or the theme manager fails to parse it (theme not recognised, title
         * shown as null).
         */
        fun toDescriptionXml(): String = listOf(
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>",
            "<theme>",
            "<version>${cdata(version)}</version>",
            "<uiVersion>10</uiVersion>",
            "<author>${cdata(author)}</author>",
            "<designer>${cdata(author)}</designer>",
            "<title>${cdata(title)}</title>",
            "<description>${cdata(description)}</description>",
            "<authors>",
            "<author locale=\"zh_CN\">${cdata(author)}</author>",
            "</authors>",
            "<designers>",
            "<designer locale=\"zh_CN\">${cdata(author)}</designer>",
            "</designers>",
            "<titles>",
            "<title locale=\"zh_CN\">${cdata(title)}</title>",
            "</titles>",
            "<descriptions>",
            "<description locale=\"zh_CN\">${cdata(description)}</description>",
            "</descriptions>",
            "</theme>",
        ).joinToString("\n")

        /**
         * Wraps free text in CDATA, splitting the section when the text itself
         * contains a terminator; author and title are user-supplied and the
         * theme manager parses this file in a privileged context.
         */
        private fun cdata(value: String): String =
            "<![CDATA[" + value.replace("]]>", "]]]]><![CDATA[>") + "]]>"
    }
}

/** A minimal valid empty zip: just the end-of-central-directory record. */
private val EMPTY_ZIP = byteArrayOf(
    0x50, 0x4B, 0x05, 0x06,
    0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00,
    0x00, 0x00,
)
