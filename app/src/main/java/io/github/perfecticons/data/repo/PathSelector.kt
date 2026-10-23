package io.github.perfecticons.data.repo

/**
 * Decides which upstream repository paths the packer needs.
 *
 * Static layered icons are strictly `0.png` + `1.png`; every other file inside a
 * static icon directory is treated as a dirty file and ignored. Dynamic layered
 * icons keep every file because they may contain any number of layers.
 */
object PathSelector {

    fun isSelected(path: String, styles: Set<String>): Boolean {
        if (path.isEmpty() || path.endsWith(".nomedia")) return false

        val parts = path.split('/')
        // Every segment must be a plain name; a `..` anywhere would escape the
        // snapshot directory once the tree is written to disk.
        if (parts.any { !isSafeId(it) }) return false
        return when (parts.firstOrNull()) {
            "icons" -> isStaticLayer(parts)
            "themes" -> isThemePath(parts, styles)
            else -> false
        }
    }

    private fun isStaticLayer(parts: List<String>): Boolean {
        if (parts.size != 3) return false
        val file = parts[2]
        if (file != "0.png" && file != "1.png") return false
        return isSafeId(parts[1])
    }

    private fun isThemePath(parts: List<String>, styles: Set<String>): Boolean {
        if (parts.size < 3) return false
        val style = parts[1]
        val category = parts[2]

        if (style == COMMON_STYLE) {
            return category == DYNAMIC_DIR && parts.size >= 4
        }
        if (style !in styles) return false

        return when (category) {
            STATIC_DIR -> when {
                parts.size == 4 -> parts[3].endsWith(".png") && !parts[3].startsWith(".")
                parts.size == 5 -> (parts[4] == "0.png" || parts[4] == "1.png") && isSafeId(parts[3])
                else -> false
            }
            DYNAMIC_DIR -> parts.size >= 4 && isSafeId(parts[3])
            else -> false
        }
    }

    /** Rejects identifiers that could escape the snapshot directory. */
    fun isSafeId(id: String): Boolean =
        id.isNotEmpty() &&
            id != "." &&
            id != ".." &&
            !id.contains('/') &&
            !id.contains('\\') &&
            !id.contains('\u0000')

    const val COMMON_STYLE = "common"
    const val STATIC_DIR = "icons"
    const val DYNAMIC_DIR = "layer_animating_icons"
}
