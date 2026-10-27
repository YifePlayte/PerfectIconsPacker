package io.github.perfecticons.domain

import io.github.perfecticons.domain.assemble.ThemeAssembler
import io.github.perfecticons.domain.build.IconArchives
import io.github.perfecticons.domain.build.ModuleBuilder
import io.github.perfecticons.domain.build.MtzBuilder
import io.github.perfecticons.domain.model.BuildTarget
import io.github.perfecticons.domain.model.IconId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ThemeBuildTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var mask: File

    private fun png(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(TINY_PNG)
    }

    private fun fixture(): Pair<File, File> {
        val snapshot = temp.newFolder("snapshot")
        val custom = temp.newFolder("custom")
        mask = temp.newFile("default_transform.xml").apply {
            writeText("<IconTransform><Config name=\"ConfigIconMask\" value=\"M0 0\" /></IconTransform>")
        }

        png(File(snapshot, "icons/com.example/0.png"))
        png(File(snapshot, "icons/com.example/1.png"))
        png(File(snapshot, "icons/com.dirty/0.png"))
        png(File(snapshot, "icons/com.dirty/10.png"))
        png(File(snapshot, "themes/Hyper/icons/com.example/0.png"))
        png(File(snapshot, "themes/Hyper/icons/com.example/1.png"))
        png(File(snapshot, "themes/Hyper/icons/com.flat.png"))
        File(snapshot, "themes/Hyper/layer_animating_icons/com.anim/config.xml").apply {
            parentFile?.mkdirs()
            writeText("<Config/>")
        }
        png(File(snapshot, "themes/Hyper/layer_animating_icons/com.anim/0/quiet/quietImage.png"))
        png(File(snapshot, "themes/common/layer_animating_icons/com.shared/0/quiet/quietImage.png"))
        File(snapshot, "themes/common/layer_animating_icons/com.shared/config.xml").writeText("<Config/>")

        return snapshot to custom
    }

    @Test
    fun `static icons require both layers`() {
        val (snapshot, custom) = fixture()
        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        assertEquals(listOf("com.example"), theme.staticIcons.map { it.id })
        assertEquals("com.flat", theme.flatIcons.keys.single())
    }

    @Test
    fun `dynamic icons merge style and common`() {
        val (snapshot, custom) = fixture()
        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        assertEquals(setOf("com.anim", "com.shared"), theme.dynamicIcons.map { it.id }.toSet())
    }

    @Test
    fun `disable runs before customization`() {
        val (snapshot, custom) = fixture()
        File(custom, "disabled/static/com.example").apply { parentFile?.mkdirs(); writeText("") }
        File(custom, "disabled/dynamic/com.anim").apply { parentFile?.mkdirs(); writeText("") }
        png(File(custom, "static/com.example/0.png"))
        png(File(custom, "static/com.example/1.png"))

        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        assertEquals("com.example", theme.staticIcons.single().id)
        assertEquals(listOf("com.shared"), theme.dynamicIcons.map { it.id })
    }

    @Test
    fun `module splits icons and dynamicicons`() {
        val (snapshot, custom) = fixture()
        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        val work = temp.newFolder("build")
        val output = ModuleBuilder(theme, work).build(
            File(work, "module.zip"),
            ModuleBuilder.ModuleInfo("id", "name", "1", 1, "author", "desc", "Hyper"),
        )

        ZipFile(output).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(names.contains("theme_staging/theme/default/icons"))
            assertTrue(names.contains("theme_staging/theme/default/dynamicicons"))
            assertTrue(names.contains("module.prop"))
            assertTrue(names.contains("META-INF/com/google/android/updater-script"))

            val icons = unzip(zip, "theme_staging/theme/default/icons")
            assertTrue(icons.any { it.startsWith("res/drawable-xxhdpi/com.example/") })
            assertTrue(icons.none { it.contains("layer_animating_icons") })
            assertTrue(icons.none { it.contains("10.png") })

            val dynamic = unzip(zip, "theme_staging/theme/default/dynamicicons")
            assertTrue(dynamic.any { it.startsWith("layer_animating_icons/com.anim/") })
            assertTrue(dynamic.any { it.startsWith("layer_animating_icons/com.shared/") })
        }
    }

    @Test
    fun `mtz keeps animated icons inside icons`() {
        val (snapshot, custom) = fixture()
        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        val work = temp.newFolder("build")
        val output = MtzBuilder(theme, work, ::fakeArtwork).build(
            File(work, "theme.mtz"),
            MtzBuilder.MtzInfo("title", "desc", "author", "1"),
        )

        ZipFile(output).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(names.contains("icons"))
            assertTrue(names.contains("description.xml"))
            assertFalse(names.contains("dynamicicons"))

            val icons = unzip(zip, "icons")
            assertTrue(icons.any { it.startsWith("res/drawable-xxhdpi/com.example/") })
            assertTrue(icons.any { it.startsWith("layer_animating_icons/com.anim/") })
            assertTrue(icons.any { it.startsWith("layer_animating_icons/com.shared/") })
        }
    }

    @Test
    fun `mtz tiles flat icons inside icons instead of a miui_mod_icons directory`() {
        val (snapshot, custom) = fixture()
        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        val work = temp.newFolder("build")
        val output = MtzBuilder(theme, work, ::fakeArtwork).build(
            File(work, "theme.mtz"),
            MtzBuilder.MtzInfo("title", "desc", "author", "1"),
        )

        ZipFile(output).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertFalse(names.any { it.startsWith("miui_mod_icons/") })

            val icons = unzip(zip, "icons")
            assertTrue(icons.contains("res/drawable-xxhdpi/com.flat.png"))
            // Tiled next to the layered directory of the same id, not inside it.
            assertTrue(icons.contains("res/drawable-xxhdpi/com.example/0.png"))
            assertFalse(icons.any { it.startsWith("res/drawable-xxhdpi/com.flat/") })
        }
    }

    @Test
    fun `module keeps flat icons in their own miui_mod_icons directory`() {
        val (snapshot, custom) = fixture()
        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        val work = temp.newFolder("build")
        val output = ModuleBuilder(theme, work).build(
            File(work, "module.zip"),
            ModuleBuilder.ModuleInfo("id", "name", "1", 1, "author", "desc", "Hyper"),
        )

        ZipFile(output).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(names.contains("theme_staging/theme/miui_mod_icons/com.flat.png"))
            // The module keeps flat icons out of the `icons` archive.
            val icons = unzip(zip, "theme_staging/theme/default/icons")
            assertFalse(icons.any { it.endsWith("com.flat.png") })
        }
    }

    @Test
    fun `advanced addons keep their name and relative path`() {
        val (snapshot, custom) = fixture()
        File(custom, "addons/com.android.systemui").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }
        File(custom, "addons/nested/component").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(4)) }

        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        val work = temp.newFolder("build")
        val module = ModuleBuilder(theme, work).build(
            File(work, "module.zip"),
            ModuleBuilder.ModuleInfo("id", "name", "1", 1, "author", "desc", "Hyper"),
        )
        val mtz = MtzBuilder(theme, work, ::fakeArtwork).build(
            File(work, "theme.mtz"),
            MtzBuilder.MtzInfo("title", "desc", "author", "1"),
        )

        ZipFile(module).use { zip ->
            assertTrue(
                zip.entries().toList().any {
                    it.name == "theme_staging/theme/default/com.android.systemui"
                },
            )
        }
        ZipFile(mtz).use { zip ->
            assertTrue(zip.entries().toList().any { it.name == "com.android.systemui" })
            assertTrue(zip.entries().toList().any { it.name == "nested/component" })
        }
    }

    @Test
    fun `mtz ships an empty com miui home zip that addons can replace`() {
        val (snapshot, custom) = fixture()
        val work = temp.newFolder("home")

        val plain = MtzBuilder(
            ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper")),
            work,
            ::fakeArtwork,
        ).build(
            File(work, "plain.mtz"),
            MtzBuilder.MtzInfo("t", "d", "a", "1"),
        )
        ZipFile(plain).use { zip ->
            val entry = zip.entries().toList().single { it.name == "com.miui.home" }
            assertTrue(entry.size == 22L)
            // A bare end-of-central-directory record: a valid empty zip.
            val bytes = zip.getInputStream(zip.getEntry("com.miui.home")).use { it.readBytes() }
            assertTrue(bytes.size == 22)
        }

        File(custom, "addons/com.miui.home").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9, 9, 9))
        }
        val overridden = MtzBuilder(
            ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper")),
            work,
            ::fakeArtwork,
        ).build(
            File(work, "overridden.mtz"),
            MtzBuilder.MtzInfo("t", "d", "a", "1"),
        )
        ZipFile(overridden).use { zip ->
            val home = zip.entries().toList().filter { it.name == "com.miui.home" }
            assertTrue(home.size == 1)
            val bytes = zip.getInputStream(zip.getEntry("com.miui.home")).use { it.readBytes() }
            assertTrue(bytes.toList() == byteArrayOf(9, 9, 9).toList())
        }
    }

    @Test
    fun `transform config falls back to the built-in mask`() {
        val (snapshot, custom) = fixture()
        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        assertEquals(mask.path, theme.transformConfig)

        File(custom, "transform_config.xml").writeText(
            "<IconTransform><Config name=\"ConfigIconMask\" value=\"M1 1\" /></IconTransform>",
        )
        val overridden = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        assertEquals(File(custom, "transform_config.xml").path, overridden.transformConfig)
    }

    @Test
    fun `mtz always ships the icon previews and never a wallpaper`() {
        val (snapshot, custom) = fixture()
        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        val work = temp.newFolder("art")

        val output = MtzBuilder(theme, work, ::fakeArtwork).build(
            File(work, "art.mtz"),
            MtzBuilder.MtzInfo("t", "d", "a", "1"),
        )
        val entries = ZipFile(output).use { zip -> zip.entries().toList().map { it.name } }

        assertTrue(entries.any { it == "preview/preview_icons_2x3_0.jpg" })
        assertTrue(entries.any { it == "preview/preview_icons_4x3_0.jpg" })
        assertTrue(entries.any { it == "preview/preview_lockscreen_0.jpg" })
        assertTrue(entries.none { it.startsWith("wallpaper/") })
    }

    @Test
    fun `icon id resolution follows launcher priority`() {
        assertEquals("com.example", IconId.resolve("com.example", null))
        assertEquals(
            "com.example.MainActivity",
            IconId.resolve("com.example", "com.example.MainActivity"),
        )
        assertEquals(
            "com.example#com.other.Launcher",
            IconId.resolve("com.example", "com.other.Launcher"),
        )
        assertFalse(IconId.isValid("../escape"))
        assertFalse(IconId.isValid("..."))
        assertTrue(IconId.isValid("com.example#com.other.Launcher"))
    }

    /** Deterministic stand-in for the Android bitmap renderer. */
    private fun fakeArtwork(): MtzBuilder.ArtworkProvider = object : MtzBuilder.ArtworkProvider {
        override fun renderPreviews(): List<Pair<String, File>> = listOf(
            "preview/preview_icons_2x3_0.jpg",
            "preview/preview_icons_4x3_0.jpg",
            "preview/preview_lockscreen_0.jpg",
        ).map { entry ->
            entry to temp.newFile("preview-${System.nanoTime()}.jpg").apply { writeBytes(PNG_STUB) }
        }
    }

    private fun unzip(zip: ZipFile, entry: String): List<String> {
        val bytes = zip.getInputStream(zip.getEntry(entry)).use { it.readBytes() }
        val inner = temp.newFile().apply { writeBytes(bytes) }
        return ZipFile(inner).use { innerZip ->
            innerZip.entries().toList().map { it.name }
        }
    }

    private companion object {
        val PNG_STUB = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )

        val TINY_PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
