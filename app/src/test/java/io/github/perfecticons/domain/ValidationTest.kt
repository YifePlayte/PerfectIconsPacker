package io.github.perfecticons.domain

import io.github.perfecticons.domain.validation.DynamicIconValidator
import io.github.perfecticons.domain.validation.PngValidator
import io.github.perfecticons.domain.validation.TransformConfigValidator
import io.github.perfecticons.domain.validation.ValidationResult
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ValidationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun png(width: Int, height: Int): ByteArray {
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

    @Test
    fun `png validation rejects non square and tiny files`() {
        val square = temp.newFile("square.png").apply { writeBytes(png(64, 64)) }
        val tall = temp.newFile("tall.png").apply { writeBytes(png(64, 65)) }
        val empty = temp.newFile("empty.png")

        assertTrue(PngValidator.validate(square, "square").isEmpty())
        assertTrue(PngValidator.validate(tall, "tall").isNotEmpty())
        assertTrue(PngValidator.validate(empty, "empty").isNotEmpty())
        assertNull(PngValidator.read(empty))
    }

    private fun dynamicDir(
        name: String,
        config: String,
        setup: (File) -> Unit,
    ): File {
        val dir = temp.newFolder(name)
        File(dir, "config.xml").writeText(config)
        setup(dir)
        return dir
    }

    private fun layer(dir: File, number: Int, fancy: Boolean, quiet: Boolean = true) {
        val layerDir = File(dir, number.toString())
        if (quiet) {
            val quietFile = File(layerDir, "quiet/quietImage.png")
            quietFile.parentFile?.mkdirs()
            quietFile.writeBytes(png(32, 32))
        }
        if (fancy) {
            val fancyDir = File(layerDir, "fancy")
            fancyDir.mkdirs()
            File(fancyDir, "manifest.xml").writeText("<Icon><Image src=\"a.png\"/></Icon>")
            File(fancyDir, "a.png").writeBytes(png(32, 32))
        }
    }

    private val validConfig = """
        <Config><Variables>
        <Variable name="layer0" value="0"/>
        <Variable name="layer1" value="1"/>
        <Variable name="layer_only_quiet0" value="true"/>
        </Variables></Config>
    """.trimIndent()

    @Test
    fun `dynamic validation accepts a complete icon`() {
        val dir = dynamicDir("complete", validConfig) { root ->
            layer(root, 0, fancy = false)
            layer(root, 1, fancy = true)
        }
        assertTrue(DynamicIconValidator.validate(dir).isValid)
    }

    @Test
    fun `dynamic validation rejects missing quiet image`() {
        val dir = dynamicDir("noquiet", validConfig) { root ->
            layer(root, 0, fancy = false)
            layer(root, 1, fancy = true, quiet = false)
        }
        val result = DynamicIconValidator.validate(dir) as ValidationResult.Invalid
        assertTrue(result.reasons.any { it.contains("quiet/quietImage.png") })
    }

    @Test
    fun `dynamic validation rejects missing manifest`() {
        val dir = dynamicDir("nomanifest", validConfig) { root ->
            layer(root, 0, fancy = false)
            val one = File(root, "1")
            File(one, "quiet/quietImage.png").apply { parentFile?.mkdirs(); writeBytes(png(32, 32)) }
            File(one, "fancy").mkdirs()
            File(one, "fancy/a.png").writeBytes(png(32, 32))
        }
        val result = DynamicIconValidator.validate(dir) as ValidationResult.Invalid
        assertTrue(result.reasons.any { it.contains("manifest.xml") })
    }

    @Test
    fun `dynamic validation rejects undeclared quiet-only layer`() {
        val dir = dynamicDir("undeclared", "<Config><Variables/></Config>") { root ->
            layer(root, 0, fancy = false)
        }
        val result = DynamicIconValidator.validate(dir) as ValidationResult.Invalid
        assertTrue(result.reasons.any { it.contains("layer_only_quiet") })
    }

    @Test
    fun `dynamic validation rejects non contiguous layers`() {
        val dir = dynamicDir("gap", validConfig) { root ->
            layer(root, 0, fancy = false)
            layer(root, 2, fancy = true)
        }
        val result = DynamicIconValidator.validate(dir) as ValidationResult.Invalid
        assertTrue(result.reasons.any { it.contains("不连续") })
    }

    @Test
    fun `dynamic validation rejects quiet-only icon without fancy layer`() {
        val dir = dynamicDir("quietonly", validConfig) { root ->
            layer(root, 0, fancy = false)
        }
        val result = DynamicIconValidator.validate(dir) as ValidationResult.Invalid
        assertTrue(result.reasons.any { it.contains("至少需要一个 fancy") })
    }

    @Test
    fun `transform validation requires a mask`() {
        val good = temp.newFile("good.xml").apply {
            writeText("<IconTransform><Config name=\"ConfigIconMask\" value=\"M0 0\"/></IconTransform>")
        }
        val noMask = temp.newFile("nomask.xml").apply {
            writeText("<IconTransform><Config name=\"SupportLayerIcon\" value=\"true\"/></IconTransform>")
        }
        val wrongRoot = temp.newFile("wrong.xml").apply {
            writeText("<Icon><Config name=\"ConfigIconMask\" value=\"M0 0\"/></Icon>")
        }
        assertTrue(TransformConfigValidator.validate(good).isValid)
        assertTrue(!TransformConfigValidator.validate(noMask).isValid)
        assertTrue(!TransformConfigValidator.validate(wrongRoot).isValid)
    }

    @Test
    fun `xml parser rejects doctype external entities`() {
        val evil = temp.newFile("evil.xml").apply {
            writeText(
                """
                <!DOCTYPE IconTransform [
                  <!ENTITY xxe SYSTEM "file:///etc/passwd">
                ]>
                <IconTransform><Config name="ConfigIconMask" value="&xxe;"/></IconTransform>
                """.trimIndent(),
            )
        }
        // Either parsing fails outright or the entity is not expanded; in both
        // cases the mask must not be accepted with external content.
        assertTrue(!TransformConfigValidator.validate(evil).isValid)
    }

    @Test
    fun `malformed manifest is rejected`() {
        val dir = dynamicDir("badmanifest", validConfig) { root ->
            layer(root, 0, fancy = false)
            val one = File(root, "1")
            File(one, "quiet/quietImage.png").apply { parentFile?.mkdirs(); writeBytes(png(32, 32)) }
            File(one, "fancy").mkdirs()
            File(one, "fancy/manifest.xml").writeText("<Icon")
        }
        val result = DynamicIconValidator.validate(dir) as ValidationResult.Invalid
        assertTrue(result.reasons.any { it.contains("manifest.xml") })
    }
}
