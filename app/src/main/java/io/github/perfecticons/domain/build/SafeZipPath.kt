package io.github.perfecticons.domain.build

import java.io.IOException

/**
 * Writes zip entries while rejecting anything that could escape the extraction
 * directory. Every path is normalized and validated before it reaches the archive.
 */
object SafeZipPath {

    private const val MAX_PATH_LENGTH = 512

    fun requireSafe(path: String): String {
        // Raw checks run before normalization so a converted path can never
        // sneak past them (normalize folds backslashes into separators).
        if (path.contains('\u0000')) throw IOException("非法字符：$path")
        if (path.contains('\\')) throw IOException("反斜杠路径：$path")
        if (path.startsWith("/")) throw IOException("绝对路径：$path")
        val normalized = normalize(path)
        if (normalized.isEmpty()) throw IOException("空路径")
        if (normalized.length > MAX_PATH_LENGTH) throw IOException("路径过长：$path")
        if (normalized.split('/').any { it == ".." }) throw IOException("路径穿越：$path")
        return normalized
    }

    fun normalize(path: String): String = path
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotEmpty() && it != "." }
        .joinToString("/")

    fun isSafe(path: String): Boolean = runCatching { requireSafe(path) }.isSuccess

    /** Reserved top-level names that customization must not overwrite. */
    val RESERVED_MODULE_NAMES = setOf("icons", "dynamicicons", "transform_config.xml")
    val RESERVED_MTZ_ROOT_NAMES = setOf(
        "icons",
        "description.xml",
        "miui_mod_icons",
        "preview",
        "wallpaper",
        "module.prop",
        "META-INF",
        "system",
    )

    fun isReservedFor(target: Target, relativePath: String): Boolean {
        // Case-insensitive: a `COM.MIUI.HOME` addon would otherwise slip past
        // the check and leave the theme without its required `com.miui.home`.
        val top = normalize(relativePath).substringBefore('/').lowercase()
        return when (target) {
            Target.Module -> top in RESERVED_MODULE_NAMES
            Target.Mtz -> top in RESERVED_MTZ_ROOT_NAMES
        }
    }

    enum class Target { Module, Mtz }
}
