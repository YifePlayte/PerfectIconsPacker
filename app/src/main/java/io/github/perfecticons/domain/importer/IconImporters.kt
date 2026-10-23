package io.github.perfecticons.domain.importer

import io.github.perfecticons.data.custom.CustomizationStore
import io.github.perfecticons.data.custom.SafeUnzip
import io.github.perfecticons.domain.model.IconId
import io.github.perfecticons.domain.validation.DynamicIconValidator
import io.github.perfecticons.domain.validation.PngValidator
import java.io.File

/**
 * Outcome of one entry inside a bulk import. Importers never abort the whole
 * archive because a single icon is malformed; they report it and continue.
 */
sealed interface ImportStatus {
    data object Added : ImportStatus
    data object Replaced : ImportStatus
    data class Skipped(val reason: String) : ImportStatus
}

data class ImportItem(
    val id: String,
    val status: ImportStatus,
)

data class ImportSummary(
    val items: List<ImportItem>,
) {
    val added: Int get() = items.count { it.status is ImportStatus.Added }
    val replaced: Int get() = items.count { it.status is ImportStatus.Replaced }
    val skipped: Int get() = items.count { it.status is ImportStatus.Skipped }
    val total: Int get() = items.size
}

/**
 * Imports archives shaped like the theme manager's `icons` file, keeping only
 * static layered icons. Dirty files such as `10.png` or `0.jpg` are ignored.
 */
class StaticIconImporter(
    private val store: CustomizationStore,
    private val workDir: File,
) {
    /**
     * Imports layered icons by merging layers into entries: whatever layers the
     * archive provides are written, existing layers of an entry stay. Entries
     * are never skipped, so re-importing an archive updates entries in place.
     */
    fun importZip(zip: File): ImportSummary {
        val extracted = File(workDir, "static-import")
        extracted.deleteRecursively()
        extracted.mkdirs()
        try {
            SafeUnzip.extract(zip, extracted)
            val root = findLayerRoot(extracted)
                ?: return ImportSummary(listOf(ImportItem("-", ImportStatus.Skipped("压缩包内没有 res/drawable-xxhdpi/"))))

            val items = root.listFiles().orEmpty()
                .filter { it.isDirectory }
                .sortedBy { it.name }
                .map { dir ->
                    val id = dir.name
                    val background = File(dir, "0.png").takeIf { it.isFile }
                    val foreground = File(dir, "1.png").takeIf { it.isFile }
                    when {
                        !IconId.isValid(id) ->
                            ImportItem(id, ImportStatus.Skipped("非法应用标识"))
                        background == null && foreground == null ->
                            ImportItem(id, ImportStatus.Skipped("缺少 0.png 与 1.png"))
                        // Header check first: reading happens only for files that
                        // already passed the PNG and size limits.
                        listOfNotNull(background, foreground).any { PngValidator.read(it) == null } ->
                            ImportItem(id, ImportStatus.Skipped("0.png / 1.png 不是有效的 PNG 或超出大小限制"))
                        else -> {
                            val entry = File(store.staticDir, id)
                            val existed = entry.isDirectory &&
                                entry.listFiles().orEmpty().any { it.isFile }
                            val result = store.saveStatic(
                                id,
                                background?.readBytes(),
                                foreground?.readBytes(),
                            )
                            result.fold(
                                onSuccess = {
                                    ImportItem(
                                        id,
                                        if (existed) ImportStatus.Replaced else ImportStatus.Added,
                                    )
                                },
                                onFailure = { ImportItem(id, ImportStatus.Skipped(it.message ?: "校验失败")) },
                            )
                        }
                    }
                }
            return ImportSummary(items)
        } finally {
            extracted.deleteRecursively()
        }
    }

    /**
     * Requires `<...>/res/drawable-xxhdpi/` exactly. A single wrapper directory
     * produced by "compress folder" tools is tolerated because the `res` segment
     * still has to be present.
     */
    private fun findLayerRoot(root: File): File? = root.walkTopDown()
        .firstOrNull { dir ->
            dir.isDirectory &&
                dir.name == LAYER_DIR &&
                dir.parentFile?.name == "res"
        }

    private companion object {
        const val LAYER_DIR = "drawable-xxhdpi"
    }
}

/**
 * Imports archives shaped like the theme manager's `dynamicicons` file. Every
 * directory under `layer_animating_icons` must pass full dynamic validation.
 */
class DynamicIconImporter(
    private val store: CustomizationStore,
    private val workDir: File,
) {
    fun importZip(zip: File, overwrite: Boolean): ImportSummary {
        val extracted = File(workDir, "dynamic-import")
        extracted.deleteRecursively()
        extracted.mkdirs()
        try {
            SafeUnzip.extract(zip, extracted)
            val root = findDynamicRoot(extracted)
                ?: return ImportSummary(
                    listOf(ImportItem("-", ImportStatus.Skipped("压缩包内没有 layer_animating_icons/"))),
                )

            val items = root.listFiles().orEmpty()
                .filter { it.isDirectory }
                .sortedBy { it.name }
                .map { dir ->
                    val id = dir.name
                    when {
                        !IconId.isValid(id) -> ImportItem(id, ImportStatus.Skipped("非法应用标识"))
                        !File(dir, "config.xml").isFile ->
                            ImportItem(id, ImportStatus.Skipped("缺少 config.xml"))
                        else -> {
                            val validation = DynamicIconValidator.validate(dir)
                            if (!validation.isValid) {
                                ImportItem(
                                    id,
                                    ImportStatus.Skipped(
                                        (validation as io.github.perfecticons.domain.validation.ValidationResult.Invalid)
                                            .reasons.joinToString("；"),
                                    ),
                                )
                            } else {
                                val existed = File(store.dynamicDir, id).isDirectory
                                if (existed && !overwrite) {
                                    ImportItem(id, ImportStatus.Skipped("已存在"))
                                } else {
                                    store.importDynamicFromDirectory(id, dir).fold(
                                        onSuccess = {
                                            ImportItem(
                                                id,
                                                if (existed) ImportStatus.Replaced else ImportStatus.Added,
                                            )
                                        },
                                        onFailure = {
                                            ImportItem(id, ImportStatus.Skipped(it.message ?: "导入失败"))
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            return ImportSummary(items)
        } finally {
            extracted.deleteRecursively()
        }
    }

    /**
     * Accepts either a bare icon directory (config.xml at its root) or the
     * `layer_animating_icons/<id>/` layout, including one redundant wrapper folder.
     */
    fun importSingle(zip: File, id: String, overwrite: Boolean): ImportSummary {
        val extracted = File(workDir, "dynamic-single")
        extracted.deleteRecursively()
        extracted.mkdirs()
        try {
            SafeUnzip.extract(zip, extracted)
            val source = locateSingleRoot(extracted)
                ?: return ImportSummary(listOf(ImportItem(id, ImportStatus.Skipped("缺少 config.xml"))))
            val validation = DynamicIconValidator.validate(source)
            if (!validation.isValid) {
                val reasons =
                    (validation as io.github.perfecticons.domain.validation.ValidationResult.Invalid)
                        .reasons.joinToString("；")
                return ImportSummary(listOf(ImportItem(id, ImportStatus.Skipped(reasons))))
            }
            // An empty placeholder entry is not an existing icon and can always
            // be filled in regardless of the overwrite flag.
            val target = File(store.dynamicDir, id)
            val existed = target.isDirectory && target.walkTopDown().any { it.isFile }
            if (existed && !overwrite) {
                return ImportSummary(listOf(ImportItem(id, ImportStatus.Skipped("已存在"))))
            }
            return store.importDynamicFromDirectory(id, source).fold(
                onSuccess = {
                    ImportSummary(
                        listOf(ImportItem(id, if (existed) ImportStatus.Replaced else ImportStatus.Added)),
                    )
                },
                onFailure = {
                    ImportSummary(listOf(ImportItem(id, ImportStatus.Skipped(it.message ?: "导入失败"))))
                },
            )
        } finally {
            extracted.deleteRecursively()
        }
    }

    private fun locateSingleRoot(root: File): File? {
        if (File(root, "config.xml").isFile) return root
        val directChildren = root.listFiles().orEmpty().filter { it.isDirectory }
        if (directChildren.size == 1) {
            val child = directChildren.single()
            if (File(child, "config.xml").isFile) return child
            val grandChildren = child.listFiles().orEmpty().filter { it.isDirectory }
            if (grandChildren.size == 1 && File(grandChildren.single(), "config.xml").isFile) {
                return grandChildren.single()
            }
        }
        return null
    }

    private fun findDynamicRoot(root: File): File? = root.walkTopDown()
        .firstOrNull { it.isDirectory && it.name == LAYER_DIR }

    private companion object {
        const val LAYER_DIR = "layer_animating_icons"
    }
}
