package io.github.perfecticons.domain

import io.github.perfecticons.domain.assemble.ThemeAssembler
import io.github.perfecticons.domain.build.ModuleBuilder
import io.github.perfecticons.domain.model.IconId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class ThemeAssemblerEdgeCaseTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var mask: File

    private fun png(file: File) {
        file.parentFile?.mkdirs()
        file.writeBytes(PNG)
    }

    private fun setup(snapshot: File): File {
        mask = temp.newFile("mask.xml").apply {
            writeText("<IconTransform><Config name=\"ConfigIconMask\" value=\"M0 0\"/></IconTransform>")
        }
        png(File(snapshot, "icons/com.base/0.png"))
        png(File(snapshot, "icons/com.base/1.png"))
        return temp.newFolder("custom")
    }

    @Test
    fun `disabled id removes both the layered and the flat form`() {
        val snapshot = temp.newFolder("snapshot")
        val custom = setup(snapshot)
        png(File(snapshot, "themes/Hyper/icons/com.base.png"))
        File(custom, "disabled/static/com.base").apply { parentFile?.mkdirs(); writeText("") }

        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        assertTrue(theme.staticIcons.none { it.id == "com.base" })
        assertFalse(theme.flatIcons.containsKey("com.base"))
        assertEquals(1, theme.stats.disabledStatic)
    }

    @Test
    fun `custom layered entry keeps the flat icon of the same id`() {
        val snapshot = temp.newFolder("snapshot")
        val custom = setup(snapshot)
        png(File(snapshot, "themes/Hyper/icons/com.base.png"))
        png(File(custom, "static/com.base/1.png"))

        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        // The launcher resolves layered icons first, so both forms may ship.
        assertEquals(
            File(snapshot, "themes/Hyper/icons/com.base.png").path,
            theme.flatIcons["com.base"],
        )
        assertEquals(1, theme.stats.overriddenStatic)
    }

    @Test
    fun `empty custom folders are not counted as overrides`() {
        val snapshot = temp.newFolder("snapshot")
        val custom = setup(snapshot)
        File(custom, "static/com.empty").mkdirs()
        File(custom, "dynamic/com.empty").mkdirs()

        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        assertEquals(0, theme.stats.overriddenStatic)
        assertEquals(0, theme.stats.overriddenDynamic)
    }

    @Test
    fun `module prop keeps injected newlines out of values`() {
        val prop = ModuleBuilder.ModuleInfo(
            id = "MIUIiconsplus",
            name = "name\nid=evil",
            version = "1",
            versionCode = 1,
            author = "a\r\ntheme=evil",
            description = "d",
            style = "Hyper",
        ).toModuleProp()

        val lines = prop.trim().lines()
        assertEquals(7, lines.size)
        assertTrue(lines.first().startsWith("id=MIUIiconsplus"))
        assertFalse(lines.drop(1).any { it.startsWith("id=") })
        assertFalse(lines.drop(1).any { it.startsWith("theme=evil") })
    }

    @Test
    fun `style overrides base and custom overrides style`() {
        val snapshot = temp.newFolder("snapshot")
        val custom = setup(snapshot)
        png(File(snapshot, "themes/Hyper/icons/com.base/0.png"))
        png(File(snapshot, "themes/Hyper/icons/com.base/1.png"))
        png(File(custom, "static/com.base/0.png"))
        png(File(custom, "static/com.base/1.png"))

        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        val icon = theme.staticIcons.single()
        assertEquals(File(custom, "static/com.base/0.png").path, icon.background)
    }

    @Test
    fun `custom static overrides only the provided layer`() {
        val snapshot = temp.newFolder("snapshot")
        val custom = setup(snapshot)
        png(File(custom, "static/com.base/1.png"))

        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        val icon = theme.staticIcons.single()
        assertEquals(File(snapshot, "icons/com.base/0.png").path, icon.background)
        assertEquals(File(custom, "static/com.base/1.png").path, icon.foreground)
    }

    @Test
    fun `style icons are not included when the style is not selected`() {
        val snapshot = temp.newFolder("snapshot")
        val custom = setup(snapshot)
        png(File(snapshot, "themes/default/icons/com.onlydefault/0.png"))
        png(File(snapshot, "themes/default/icons/com.onlydefault/1.png"))

        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        assertEquals(listOf("com.base"), theme.staticIcons.map { it.id })
    }

    @Test
    fun `style dynamic overrides common dynamic`() {
        val snapshot = temp.newFolder("snapshot")
        val custom = setup(snapshot)
        File(snapshot, "themes/common/layer_animating_icons/com.shared/config.xml").apply {
            parentFile?.mkdirs()
            writeText("<Config/>")
        }
        File(snapshot, "themes/Hyper/layer_animating_icons/com.shared/config.xml").apply {
            parentFile?.mkdirs()
            writeText("<Config/>")
        }

        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        val icon = theme.dynamicIcons.single { it.id == "com.shared" }
        assertTrue(icon.directory.contains("themes/Hyper"))
    }

    @Test
    fun `disabled static also removes the flat icon`() {
        val snapshot = temp.newFolder("snapshot")
        val custom = setup(snapshot)
        png(File(snapshot, "themes/Hyper/icons/com.flat.png"))
        File(custom, "disabled/static/com.flat").apply { parentFile?.mkdirs(); writeText("") }

        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        assertTrue(theme.flatIcons.isEmpty())
    }

    @Test
    fun `dynamic entry without config is skipped`() {
        val snapshot = temp.newFolder("snapshot")
        val custom = setup(snapshot)
        File(custom, "dynamic/com.noconfig/0/quiet/quietImage.png").apply {
            parentFile?.mkdirs()
            writeBytes(PNG)
        }

        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        assertTrue(theme.dynamicIcons.none { it.id == "com.noconfig" })
    }

    @Test
    fun `addon paths that would escape are ignored`() {
        val snapshot = temp.newFolder("snapshot")
        val custom = setup(snapshot)
        File(custom, "addons").mkdirs()
        File(custom, "escape").writeText("x")
        File(custom, "addons/good").mkdirs()
        File(custom, "addons/good/component").writeText("x")

        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        assertEquals(listOf("good/component"), theme.addons.keys.toList())
    }

    @Test
    fun `module output omits addons that collide with reserved names`() {
        val snapshot = temp.newFolder("snapshot")
        val custom = setup(snapshot)
        // "icons" as an addon path would overwrite the theme archive itself.
        File(custom, "addons").mkdirs()
        File(custom, "addons/icons").writeText("x")
        File(custom, "addons/ok").writeText("x")

        val theme = ThemeAssembler(snapshot, custom, mask).assemble(setOf("Hyper"))
        val work = temp.newFolder("work")

        val output = ModuleBuilder(theme, work).build(
            File(work, "m.zip"),
            ModuleBuilder.ModuleInfo("id", "n", "1", 1, "a", "d", "Hyper"),
        )
        ZipFile(output).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(names.contains("theme_staging/theme/default/ok"))
            // The theme archive entry must stay the only one with this name.
            assertEquals(
                1,
                names.count { it == "theme_staging/theme/default/icons" },
            )
        }
    }

    @Test
    fun `icon id validation rejects separators and dot entries`() {
        assertTrue(IconId.isValid("com.example"))
        assertTrue(IconId.isValid("com.example#com.other.Launcher"))
        assertTrue(IconId.isValid("com.example\$Inner"))
        assertFalse(IconId.isValid(""))
        assertFalse(IconId.isValid("."))
        assertFalse(IconId.isValid(".."))
        assertFalse(IconId.isValid(".hidden"))
        assertFalse(IconId.isValid("a/b"))
        assertFalse(IconId.isValid("a\\b"))
        assertFalse(IconId.isValid("a\u0000b"))
        assertEquals("com.example", IconId.packageOf("com.example#com.other.Launcher"))
    }

    private companion object {
        val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
