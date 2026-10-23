package io.github.perfecticons.domain.model

/**
 * A static layered icon: `0.png` (background) and `1.png` (foreground), each
 * square when present.
 *
 * Layers are nullable because a custom entry may override just one layer of an
 * upstream icon; upstream entries always carry both. Dirty files occasionally
 * present upstream (`10.png`, `0.jpg`, `background.png`) are never part of this
 * model.
 */
data class StaticIcon(
    val id: String,
    val background: String?,
    val foreground: String?,
)

/**
 * A dynamic layered icon directory. Layer count is unbounded; the directory is
 * copied verbatim because every file may be referenced by manifests.
 */
data class DynamicIcon(
    val id: String,
    val directory: String,
)

/**
 * What the customization merge did, for the build log. The upstream counts are
 * the synced resources alone (base plus the selected themes), matching the
 * home stat tiles: custom entries are reported separately.
 */
data class MergeStats(
    val upstreamStatic: Int = 0,
    val upstreamDynamic: Int = 0,
    val disabledStatic: Int = 0,
    val disabledDynamic: Int = 0,
    val overriddenStatic: Int = 0,
    val overriddenDynamic: Int = 0,
)

data class AssembledTheme(
    val staticIcons: List<StaticIcon>,
    val flatIcons: Map<String, String>,
    val dynamicIcons: List<DynamicIcon>,
    val addons: Map<String, String>,
    val addonDirectories: List<String>,
    val transformConfig: String?,
    val stats: MergeStats = MergeStats(),
) {
    val staticCount: Int get() = staticIcons.size + flatIcons.size
    val dynamicCount: Int get() = dynamicIcons.size
    val addonCount: Int get() = addons.size

    /** Files the static write emits: one per set layer, plus the mask config. */
    val staticWriteCount: Int
        get() = staticIcons.sumOf { icon ->
            (if (icon.background != null) 1 else 0) + (if (icon.foreground != null) 1 else 0)
        } + (if (transformConfig != null) 1 else 0)
}

enum class BuildTarget {
    Module,
    Mtz,
    ;

    /**
     * Type handed to the document picker so it keeps the artifact's extension.
     *
     * The module is an ordinary zip. MTZ has no registered type, so a generic
     * one would let the system rewrite the name onto whatever extension it
     * associates with that type; the vendor-specific type is unmapped, which
     * leaves the suggested `.mtz` file name untouched.
     */
    val mimeType: String
        get() = when (this) {
            Module -> "application/zip"
            Mtz -> "application/vnd.miui.mtz"
        }
}
