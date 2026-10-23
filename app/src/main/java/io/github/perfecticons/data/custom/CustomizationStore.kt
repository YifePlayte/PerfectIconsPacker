package io.github.perfecticons.data.custom

import io.github.perfecticons.domain.model.IconId
import io.github.perfecticons.domain.validation.DynamicIconValidator
import io.github.perfecticons.domain.validation.PngValidator
import io.github.perfecticons.domain.validation.TransformConfigValidator
import io.github.perfecticons.domain.validation.ValidationResult
import java.io.File

/**
 * Owns the on-disk customization tree. The directory is the single source of
 * truth: the in-app UI and an external SAF editor both mutate the same files, so
 * nothing here caches state between calls.
 */
class CustomizationStore(
    val root: File,
) {
    val staticDir: File get() = File(root, "static")
    val dynamicDir: File get() = File(root, "dynamic")
    val addonsDir: File get() = File(root, "addons")
    val disabledStaticDir: File get() = File(root, "disabled/static")
    val disabledDynamicDir: File get() = File(root, "disabled/dynamic")
    val transformConfig: File get() = File(root, "transform_config.xml")

    fun ensureLayout() {
        listOf(
            staticDir,
            dynamicDir,
            addonsDir,
            disabledStaticDir,
            disabledDynamicDir,
        ).forEach { it.mkdirs() }
    }

    /**
     * Identity of a file's current content for UI cache invalidation: existence,
     * size and modification time. Cheap to compute and changes on replace/clear.
     */
    private fun File.stamp(): String =
        if (isFile) "${lastModified()}:${length()}" else "absent"

    fun staticIcons(): List<StaticEntry> = staticDir.listFiles().orEmpty()
        .filter { it.isDirectory && IconId.isValid(it.name) }
        .sortedBy { it.name }
        .map { dir ->
            val background = File(dir, "0.png")
            val foreground = File(dir, "1.png")
            StaticEntry(
                id = dir.name,
                directory = dir,
                hasBackground = background.isFile,
                hasForeground = foreground.isFile,
                // A layer can be replaced in place, so the entry carries each
                // file's stamp: state comparison then notices the new bytes and
                // the UI re-decodes instead of keeping the previous thumbnail.
                backgroundStamp = background.stamp(),
                foregroundStamp = foreground.stamp(),
                ignoredFiles = dir.listFiles().orEmpty()
                    .filter { it.isFile && it.name != "0.png" && it.name != "1.png" }
                    .map { it.name },
            )
        }

    fun dynamicIcons(): List<DynamicEntry> = dynamicDir.listFiles().orEmpty()
        .filter { it.isDirectory && IconId.isValid(it.name) }
        .sortedBy { it.name }
        .map { dir ->
            DynamicEntry(
                id = dir.name,
                directory = dir,
                fileCount = dir.walkTopDown().count { it.isFile },
                previews = layerQuietImages(dir),
                validation = DynamicIconValidator.validate(dir),
            )
        }

    fun addonFiles(): List<AddonEntry> = addonsDir.walkTopDown()
        .filter { it.isFile }
        .sortedBy { it.path }
        .map { file ->
            AddonEntry(
                relativePath = file.relativeTo(addonsDir).invariantSeparatorsPath,
                file = file,
                bytes = file.length(),
            )
        }
        .toList()

    /**
     * Saves layers under every given id. Provided layers replace the entry's
     * same-name layer, omitted layers stay untouched, and ids without an entry
     * are created as placeholders whose layers are filled in from the custom
     * list later. Overriding just one layer of an upstream icon is the point.
     */
    fun saveStatic(
        id: String,
        backgroundBytes: ByteArray?,
        foregroundBytes: ByteArray?,
    ): Result<Unit> =
        runCatching {
            requireValidId(id)
            val bg = backgroundBytes?.takeIf { it.isNotEmpty() }
            val fg = foregroundBytes?.takeIf { it.isNotEmpty() }
            val target = File(staticDir, id)
            val temp = File(staticDir, ".tmp-$id")
            temp.deleteRecursively()
            if (target.isDirectory) target.copyRecursively(temp, overwrite = true) else temp.mkdirs()
            if (bg != null) File(temp, "0.png").writeBytes(bg)
            if (fg != null) File(temp, "1.png").writeBytes(fg)
            val problems = buildList {
                File(temp, "0.png").takeIf { it.isFile }?.let { addAll(PngValidator.validate(it, "0.png")) }
                File(temp, "1.png").takeIf { it.isFile }?.let { addAll(PngValidator.validate(it, "1.png")) }
            }
            if (problems.isNotEmpty()) {
                temp.deleteRecursively()
                error(problems.joinToString("；"))
            }
            target.deleteRecursively()
            if (!temp.renameTo(target)) {
                temp.copyRecursively(target, overwrite = true)
                temp.deleteRecursively()
            }
        }

    /** Removes one layer from an entry; the entry itself is kept. */
    fun clearStaticLayer(id: String, layer: String): Result<Unit> = runCatching {
        requireValidId(id)
        require(layer == "0.png" || layer == "1.png") { "非法图层：$layer" }
        File(File(staticDir, id), layer).delete()
    }

    /** Creates an empty dynamic entry; the icon archive is imported per entry later. */
    fun createDynamicEntry(id: String): Result<Unit> = runCatching {
        File(dynamicDir, requireValidId(id)).mkdirs()
    }

    fun deleteStatic(id: String): Boolean = File(staticDir, requireValidId(id)).deleteRecursively()

    fun importDynamicFromDirectory(id: String, source: File): Result<Unit> = runCatching {
        requireValidId(id)
        val validation = DynamicIconValidator.validate(source)
        if (validation is ValidationResult.Invalid) {
            error(validation.reasons.joinToString("；"))
        }
        val target = File(dynamicDir, id)
        val temp = File(dynamicDir, ".tmp-$id")
        temp.deleteRecursively()
        source.copyRecursively(temp, overwrite = true)
        target.deleteRecursively()
        if (!temp.renameTo(target)) {
            temp.copyRecursively(target, overwrite = true)
            temp.deleteRecursively()
        }
    }

    fun deleteDynamic(id: String): Boolean = File(dynamicDir, requireValidId(id)).deleteRecursively()

    fun importAddon(source: File, relativePath: String): Result<Unit> = runCatching {
        val safe = relativePath.replace('\\', '/').trimStart('/')
        require(safe.isNotEmpty() && safe.split('/').none { it == ".." }) { "非法附加路径" }
        val target = File(addonsDir, safe)
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
    }

    fun deleteAddon(relativePath: String): Boolean {
        // Same path rules as importAddon; deletion must not reach outside.
        val safe = relativePath.replace('\\', '/').trimStart('/')
        if (safe.isEmpty() || safe.split('/').any { it == ".." }) return false
        return File(addonsDir, safe).deleteRecursively()
    }

    fun readTransformConfig(): String? =
        transformConfig.takeIf { it.isFile }?.readText()

    fun saveTransformConfig(bytes: ByteArray): Result<Unit> = runCatching {
        val temp = File(root, ".tmp-transform.xml")
        temp.writeBytes(bytes)
        val validation = TransformConfigValidator.validate(temp)
        if (validation is ValidationResult.Invalid) {
            temp.delete()
            error(validation.reasons.joinToString("；"))
        }
        if (!temp.renameTo(transformConfig)) {
            temp.copyTo(transformConfig, overwrite = true)
            temp.delete()
        }
    }

    fun deleteTransformConfig(): Boolean = transformConfig.delete()

    fun isStaticDisabled(id: String): Boolean = File(disabledStaticDir, id).exists()

    fun isDynamicDisabled(id: String): Boolean = File(disabledDynamicDir, id).exists()

    fun setStaticDisabled(id: String, disabled: Boolean) {
        requireValidId(id)
        val marker = File(disabledStaticDir, id)
        if (disabled) {
            marker.parentFile?.mkdirs()
            marker.writeText("")
        } else {
            marker.delete()
        }
    }

    fun setDynamicDisabled(id: String, disabled: Boolean) {
        requireValidId(id)
        val marker = File(disabledDynamicDir, id)
        if (disabled) {
            marker.parentFile?.mkdirs()
            marker.writeText("")
        } else {
            marker.delete()
        }
    }

    fun disabledStaticIds(): Set<String> = markerIds(disabledStaticDir)
    fun disabledDynamicIds(): Set<String> = markerIds(disabledDynamicDir)

    fun clearStaleDisabled(): Int {
        var removed = 0
        listOf(disabledStaticDir, disabledDynamicDir).forEach { dir ->
            dir.listFiles().orEmpty().forEach { marker ->
                if (marker.isFile && marker.length() > 0L) {
                    marker.delete()
                    removed++
                }
            }
        }
        return removed
    }

    private fun markerIds(dir: File): Set<String> = dir.listFiles().orEmpty()
        .map { it.name }
        .filter(IconId::isValid)
        .toSet()

    private fun requireValidId(id: String): String {
        require(IconId.isValid(id)) { "非法应用标识：$id" }
        return id
    }

    /**
     * Per-layer quiet images in layer order, so previews can stack them the way
     * the theme composites the icon. A root-level quiet image (seen in some
     * upstream icons) is used when no layer provides one.
     */
    private fun layerQuietImages(directory: File): List<File> {
        val layered = directory.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.toIntOrNull() != null }
            .sortedBy { it.name.toInt() }
            .map { File(it, "quiet/quietImage.png") }
            .filter { it.isFile }
        if (layered.isNotEmpty()) return layered
        return File(directory, "quiet/quietImage.png").takeIf { it.isFile }?.let { listOf(it) }.orEmpty()
    }
}

data class StaticEntry(
    val id: String,
    val directory: File,
    val hasBackground: Boolean,
    val hasForeground: Boolean,
    /** mtime+size of `0.png`; changes when the layer is replaced or cleared. */
    val backgroundStamp: String,
    /** mtime+size of `1.png`; changes when the layer is replaced or cleared. */
    val foregroundStamp: String,
    val ignoredFiles: List<String>,
) {
    val backgroundFile: File? get() = File(directory, "0.png").takeIf { hasBackground }
    val foregroundFile: File? get() = File(directory, "1.png").takeIf { hasForeground }
}

data class DynamicEntry(
    val id: String,
    val directory: File,
    val fileCount: Int,
    val previews: List<File>,
    val validation: ValidationResult,
)

data class AddonEntry(
    val relativePath: String,
    val file: File,
    val bytes: Long,
)
