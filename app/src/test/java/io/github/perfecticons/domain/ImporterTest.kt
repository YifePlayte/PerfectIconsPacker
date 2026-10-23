package io.github.perfecticons.domain

import io.github.perfecticons.data.custom.CustomizationStore
import io.github.perfecticons.domain.importer.DynamicIconImporter
import io.github.perfecticons.domain.importer.ImportStatus
import io.github.perfecticons.domain.importer.StaticIconImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImporterTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun png(width: Int = 8, height: Int = 8): ByteArray {
        val header = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(header)
        header[16] = (width shr 24).toByte()
        header[17] = (width shr 16).toByte()
        header[18] = (width shr 8).toByte()
        header[19] = width.toByte()
        header[20] = (height shr 24).toByte()
        header[21] = (height shr 16).toByte()
        header[22] = (height shr 8).toByte()
        header[23] = height.toByte()
        return header
    }

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val file = temp.newFile(name)
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file
    }

    private fun configuration(quietOnly: Set<Int> = setOf(0)): ByteArray {
        val variables = buildString {
            append("<Config><Variables>")
            append("<Variable name=\"layer0\" value=\"0\" type=\"string\"/>")
            append("<Variable name=\"layer1\" value=\"1\" type=\"string\"/>")
            quietOnly.forEach {
                append("<Variable name=\"layer_only_quiet$it\" value=\"true\" type=\"string\"/>")
            }
            append("</Variables></Config>")
        }
        return variables.toByteArray()
    }

    private fun validDynamicEntries(id: String): Map<String, ByteArray> = mapOf(
        "layer_animating_icons/$id/config.xml" to configuration(),
        "layer_animating_icons/$id/0/quiet/quietImage.png" to png(),
        "layer_animating_icons/$id/1/fancy/manifest.xml" to
            "<Icon><Image src=\"a.png\"/></Icon>".toByteArray(),
        "layer_animating_icons/$id/1/fancy/a.png" to png(),
        "layer_animating_icons/$id/1/quiet/quietImage.png" to png(),
    )

    @Test
    fun `static import keeps only valid layered icons`() {
        val store = CustomizationStore(temp.newFolder("custom"))
        val archive = zip(
            "icons.zip",
            mapOf(
                "res/drawable-xxhdpi/com.good/0.png" to png(),
                "res/drawable-xxhdpi/com.good/1.png" to png(),
                "res/drawable-xxhdpi/com.good/10.png" to png(),
                "res/drawable-xxhdpi/com.partial/0.png" to png(),
                "res/drawable-xxhdpi/com.badratio/0.png" to png(width = 8, height = 4),
                "res/drawable-xxhdpi/com.badratio/1.png" to png(),
                "miui_mod_icons/ignored.png" to png(),
            ),
        )

        val summary = StaticIconImporter(store, temp.newFolder("work")).importZip(archive)

        // com.partial only carries 0.png, which is a legal partial override now.
        assertEquals(2, summary.added)
        assertEquals(1, summary.skipped)
        assertTrue(File(store.staticDir, "com.good/0.png").isFile)
        assertTrue(!File(store.staticDir, "com.good/10.png").exists())
        assertTrue(File(store.staticDir, "com.partial/0.png").isFile)
        assertTrue(!File(store.staticDir, "com.partial/1.png").exists())
        assertTrue(!File(store.staticDir, "com.badratio").exists())
        assertTrue(
            summary.items.single { it.id == "com.badratio" }.status.let {
                it is ImportStatus.Skipped && it.reason.contains("正方形")
            },
        )
    }

    @Test
    fun `static import requires res drawable directory`() {
        val store = CustomizationStore(temp.newFolder("custom"))
        val archive = zip(
            "icons.zip",
            mapOf("drawable-xxhdpi/com.good/0.png" to png(), "drawable-xxhdpi/com.good/1.png" to png()),
        )
        val summary = StaticIconImporter(store, temp.newFolder("work")).importZip(archive)
        assertEquals(0, summary.added)
        assertEquals(1, summary.skipped)
        assertTrue(summary.items.single().status is ImportStatus.Skipped)
    }

    @Test
    fun `static import merges layers into existing entries`() {
        val store = CustomizationStore(temp.newFolder("custom"))
        store.saveStatic("com.example", png(), png()).getOrThrow()
        val archive = zip(
            "icons.zip",
            mapOf("res/drawable-xxhdpi/com.example/0.png" to png()),
        )
        val importer = StaticIconImporter(store, temp.newFolder("work"))

        val first = importer.importZip(archive)

        assertEquals(0, first.skipped)
        assertEquals(1, first.replaced)
        // The archive only carries 0.png; the entry's own 1.png survives.
        assertTrue(File(store.staticDir, "com.example/0.png").isFile)
        assertTrue(File(store.staticDir, "com.example/1.png").isFile)

        // Re-importing updates the entry in place instead of being skipped.
        assertEquals(1, importer.importZip(archive).replaced)
    }

    @Test
    fun `dynamic import validates every layer`() {
        val store = CustomizationStore(temp.newFolder("custom"))
        val valid = validDynamicEntries("com.good")
        val missingQuiet = validDynamicEntries("com.noquiet").toMutableMap().apply {
            remove("layer_animating_icons/com.noquiet/1/quiet/quietImage.png")
        }
        val missingManifest = validDynamicEntries("com.nomanifest").toMutableMap().apply {
            remove("layer_animating_icons/com.nomanifest/1/fancy/manifest.xml")
        }
        val quietOnlyUndeclared = mapOf(
            "layer_animating_icons/com.undeclared/config.xml" to
                "<Config><Variables><Variable name=\"layer0\" value=\"0\"/></Variables></Config>".toByteArray(),
            "layer_animating_icons/com.undeclared/0/quiet/quietImage.png" to png(),
        )

        val archive = zip("dynamic.zip", valid + missingQuiet + missingManifest + quietOnlyUndeclared)
        val summary = DynamicIconImporter(store, temp.newFolder("work")).importZip(archive, overwrite = false)

        assertEquals(1, summary.added)
        assertEquals(3, summary.skipped)
        assertTrue(File(store.dynamicDir, "com.good/config.xml").isFile)

        val skippedReasons = summary.items.filter { it.status is ImportStatus.Skipped }
            .joinToString(" | ") { (it.status as ImportStatus.Skipped).reason }
        assertTrue(skippedReasons.contains("quiet/quietImage.png"))
        assertTrue(skippedReasons.contains("manifest.xml"))
        assertTrue(skippedReasons.contains("layer_only_quiet"))
    }

    @Test
    fun `dynamic single import tolerates one wrapper directory`() {
        val store = CustomizationStore(temp.newFolder("custom"))
        val wrapped = validDynamicEntries("com.wrapped").mapKeys { (path, _) ->
            path.removePrefix("layer_animating_icons/com.wrapped/")
        }
        val archive = zip(
            "single.zip",
            wrapped.mapKeys { (path, _) -> "com.wrapped/$path" },
        )

        val summary = DynamicIconImporter(store, temp.newFolder("work"))
            .importSingle(archive, "com.wrapped", overwrite = false)

        assertEquals(1, summary.added)
        assertTrue(File(store.dynamicDir, "com.wrapped/config.xml").isFile)
    }

    @Test
    fun `dynamic single import rejects missing config`() {
        val store = CustomizationStore(temp.newFolder("custom"))
        val archive = zip("bad.zip", mapOf("0/quiet/quietImage.png" to png()))
        val summary = DynamicIconImporter(store, temp.newFolder("work"))
            .importSingle(archive, "com.bad", overwrite = false)
        assertEquals(0, summary.added)
        assertTrue((summary.items.single().status as ImportStatus.Skipped).reason.contains("config.xml"))
    }
}
