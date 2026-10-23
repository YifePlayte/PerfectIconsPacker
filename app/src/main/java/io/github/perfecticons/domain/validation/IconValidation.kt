package io.github.perfecticons.domain.validation

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Result of validating one custom icon entry. */
sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(val reasons: List<String>) : ValidationResult

    val isValid: Boolean get() = this is Valid
}

object PngValidator {

    const val MAX_DIMENSION = 4096
    const val MAX_FILE_BYTES = 32L * 1024 * 1024

    data class Info(val width: Int, val height: Int, val bytes: Long) {
        val isSquare: Boolean get() = width == height
    }

    fun read(file: File): Info? {
        if (!file.isFile) return null
        if (file.length() <= 0 || file.length() > MAX_FILE_BYTES) return null
        val header = ByteArray(24)
        val read = file.inputStream().use { it.read(header) }
        if (read < 24) return null
        if (!header.copyOfRange(0, 8).contentEquals(PNG_MAGIC)) return null
        // IHDR width/height are big-endian 32-bit values at offsets 16 and 20.
        val width = header.beInt(16)
        val height = header.beInt(20)
        if (width <= 0 || height <= 0) return null
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) return null
        return Info(width, height, file.length())
    }

    fun validate(file: File, label: String): List<String> {
        val info = read(file)
            ?: return listOf("$label 不是有效的 PNG 或超出大小限制")
        if (!info.isSquare) return listOf("$label 不是正方形（${info.width}×${info.height}）")
        return emptyList()
    }

    private fun ByteArray.beInt(offset: Int): Int =
        ((this[offset].toInt() and 0xFF) shl 24) or
            ((this[offset + 1].toInt() and 0xFF) shl 16) or
            ((this[offset + 2].toInt() and 0xFF) shl 8) or
            (this[offset + 3].toInt() and 0xFF)

    private val PNG_MAGIC = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
}

object XmlSupport {

    /** Config files are tiny; anything huge or DTD-carrying is refused outright. */
    private const val MAX_CONFIG_BYTES = 4L * 1024 * 1024

    fun parse(file: File): Element? {
        if (!file.isFile || file.length() > MAX_CONFIG_BYTES) return null
        // Android's Expat builder ignores the Xerces hardening flags below, so
        // DTDs are rejected up front instead: none of the accepted configs use
        // entities, and this closes XXE and entity expansion on every platform.
        if (file.readText().contains("<!DOCTYPE", ignoreCase = true)) return null
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                isExpandEntityReferences = false
                harden("http://apache.org/xml/features/disallow-doctype-decl", true)
                harden("http://xml.org/sax/features/external-general-entities", false)
                harden("http://xml.org/sax/features/external-parameter-entities", false)
            }
            val document = factory.newDocumentBuilder().parse(file)
            document.documentElement
        }.getOrNull()
    }

    /**
     * Enables a parser hardening feature when the platform's builder knows it.
     * The Xerces-only features make Android's Expat builder throw, which used
     * to fail every parse (and mark every dynamic icon invalid) on device.
     */
    private fun DocumentBuilderFactory.harden(feature: String, value: Boolean) {
        runCatching { setFeature(feature, value) }
    }
}

/**
 * Dynamic icons may use any number of layers, but every layer must be complete:
 *
 *  - `config.xml` at the icon root, parseable with a `Config` root element
 *  - numeric layer directories starting at 0 and contiguous
 *  - every layer has `quiet/quietImage.png`
 *  - every layer with a `fancy` directory has `fancy/manifest.xml`
 *  - layers without fancy must declare `layer_only_quiet<N>=true`
 *  - at least one layer uses fancy, otherwise it is not an animated icon
 */
object DynamicIconValidator {

    fun validate(directory: File): ValidationResult {
        val reasons = mutableListOf<String>()
        // An empty directory is a placeholder created by the add flow; its
        // archive is imported per entry later.
        if (directory.walkTopDown().none { it.isFile }) return ValidationResult.Valid
        val config = File(directory, "config.xml")
        if (!config.isFile) {
            return ValidationResult.Invalid(listOf("缺少 config.xml"))
        }

        val configRoot = XmlSupport.parse(config)
        if (configRoot == null || configRoot.tagName != "Config") {
            reasons += "config.xml 无法解析或根节点不是 Config"
        }

        val declaredQuietOnly = readVariables(configRoot)
            .filterKeys { it.startsWith("layer_only_quiet") }
            .filterValues { it == "true" }
            .keys
            .mapNotNull { it.removePrefix("layer_only_quiet").toIntOrNull() }
            .toSet()

        val layers = directory.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.toIntOrNull() != null }
            .map { it.name.toInt() to it }
            .sortedBy { it.first }

        if (layers.isEmpty()) {
            reasons += "没有数字层目录"
            return ValidationResult.Invalid(reasons)
        }

        val numbers = layers.map { it.first }
        if (numbers != numbers.indices.toList()) {
            reasons += "层编号不连续：${numbers.joinToString(",")}"
        }

        var fancyLayers = 0
        layers.forEach { (number, layerDir) ->
            val quietImage = File(layerDir, "quiet/quietImage.png")
            if (!quietImage.isFile) {
                reasons += "第 $number 层缺少 quiet/quietImage.png"
            } else if (PngValidator.read(quietImage) == null) {
                reasons += "第 $number 层的 quietImage.png 无效"
            }

            val fancyDir = File(layerDir, "fancy")
            val hasFancy = fancyDir.isDirectory && fancyDir.listFiles().orEmpty().any { it.isFile }
            if (hasFancy) {
                fancyLayers++
                val manifest = File(fancyDir, "manifest.xml")
                if (!manifest.isFile) {
                    reasons += "第 $number 层缺少 fancy/manifest.xml"
                } else if (XmlSupport.parse(manifest)?.tagName != "Icon") {
                    reasons += "第 $number 层的 fancy/manifest.xml 无效"
                }
            } else if (number !in declaredQuietOnly) {
                reasons += "第 $number 层没有 fancy，且未声明 layer_only_quiet$number=true"
            }
        }

        if (fancyLayers == 0) {
            reasons += "至少需要一个 fancy 层"
        }

        return if (reasons.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(reasons)
    }

    private fun readVariables(root: Element?): Map<String, String> {
        if (root == null) return emptyMap()
        val variables = root.getElementsByTagName("Variable")
        return buildMap {
            for (index in 0 until variables.length) {
                val element = variables.item(index) as? Element ?: continue
                val name = element.getAttribute("name")
                if (name.isNotEmpty()) put(name, element.getAttribute("value"))
            }
        }
    }
}

object TransformConfigValidator {

    fun validate(file: File): ValidationResult {
        val root = XmlSupport.parse(file)
            ?: return ValidationResult.Invalid(listOf("XML 无法解析"))
        if (root.tagName != "IconTransform") {
            return ValidationResult.Invalid(listOf("根节点必须是 IconTransform"))
        }
        val configs = root.getElementsByTagName("Config")
        for (index in 0 until configs.length) {
            val element = configs.item(index) as? Element ?: continue
            if (element.getAttribute("name") == "ConfigIconMask" &&
                element.getAttribute("value").isNotBlank()
            ) {
                return ValidationResult.Valid
            }
        }
        return ValidationResult.Invalid(listOf("缺少非空的 ConfigIconMask"))
    }
}
