package io.github.perfecticons.domain.assemble

import io.github.perfecticons.data.repo.Paths
import io.github.perfecticons.domain.build.SafeZipPath
import io.github.perfecticons.domain.model.AssembledTheme
import io.github.perfecticons.domain.model.MergeStats
import io.github.perfecticons.domain.model.DynamicIcon
import io.github.perfecticons.domain.model.IconId
import io.github.perfecticons.domain.model.StaticIcon
import java.io.File

/**
 * Merges the upstream snapshot with user customizations.
 *
 * Order matters and is fixed:
 *  1. upstream static + selected style + shared dynamic icons
 *  2. static disabled markers
 *  3. dynamic disabled markers
 *  4. custom static icons
 *  5. custom dynamic icons
 *  6. transform_config override, falling back to the built-in HyperOS 4 mask
 *  7. advanced addons
 *
 * Disabling runs before customization so a user can re-provide a disabled icon.
 */
class ThemeAssembler(
    private val snapshotDir: File,
    private val customDir: File,
    private val defaultTransformConfig: File,
) {
    fun assemble(styles: Set<String>): AssembledTheme {
        val staticIcons = linkedMapOf<String, StaticIcon>()
        val flatIcons = linkedMapOf<String, String>()
        val dynamicIcons = linkedMapOf<String, DynamicIcon>()
        val addons = linkedMapOf<String, String>()
        val addonDirs = sortedSetOf<String>()

        collectUpstream(styles, staticIcons, flatIcons, dynamicIcons)
        val upstreamStatic = staticIcons.size
        val upstreamDynamic = dynamicIcons.size
        val (disabledStatic, disabledDynamic) = applyDisabled(staticIcons, flatIcons, dynamicIcons)
        val overriddenStatic = collectCustomStatic(staticIcons)
        val overriddenDynamic = collectCustomDynamic(dynamicIcons)
        val transform = customTransform()
        collectAddons(addons, addonDirs)

        return AssembledTheme(
            staticIcons = staticIcons.values.toList(),
            flatIcons = flatIcons,
            dynamicIcons = dynamicIcons.values.toList(),
            addons = addons,
            addonDirectories = addonDirs.toList(),
            transformConfig = transform.absolutePath,
            stats = MergeStats(
                upstreamStatic = upstreamStatic,
                upstreamDynamic = upstreamDynamic,
                disabledStatic = disabledStatic,
                disabledDynamic = disabledDynamic,
                overriddenStatic = overriddenStatic,
                overriddenDynamic = overriddenDynamic,
            ),
        )
    }

    /**
     * Layered icon counts of the synced resources alone (base plus the selected
     * themes), before custom entries are laid over: `static to dynamic`.
     */
    fun upstreamIconCounts(styles: Set<String>): Pair<Int, Int> {
        val staticIcons = linkedMapOf<String, StaticIcon>()
        val flatIcons = linkedMapOf<String, String>()
        val dynamicIcons = linkedMapOf<String, DynamicIcon>()
        collectUpstream(styles, staticIcons, flatIcons, dynamicIcons)
        return staticIcons.size to dynamicIcons.size
    }

    private fun collectUpstream(
        styles: Set<String>,
        staticIcons: MutableMap<String, StaticIcon>,
        flatIcons: MutableMap<String, String>,
        dynamicIcons: MutableMap<String, DynamicIcon>,
    ) {
        val base = File(snapshotDir, Paths.BASE_STATIC_DIR)
        if (base.isDirectory) {
            base.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
                readStatic(dir)?.let { staticIcons[dir.name] = it }
            }
        }

        styles.forEach { style ->
            val themeRoot = File(snapshotDir, "${Paths.THEMES_DIR}/$style")
            val themeStatic = File(themeRoot, Paths.STATIC_DIR)
            if (themeStatic.isDirectory) {
                themeStatic.listFiles().orEmpty().forEach { entry ->
                    when {
                        entry.isDirectory -> readStatic(entry)?.let { staticIcons[entry.name] = it }
                        entry.isFile && entry.extension.equals("png", ignoreCase = true) -> {
                            val id = entry.nameWithoutExtension
                            if (IconId.isValid(id)) flatIcons[id] = entry.path
                        }
                    }
                }
            }

            val themeDynamic = File(themeRoot, Paths.DYNAMIC_DIR)
            if (themeDynamic.isDirectory) {
                themeDynamic.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
                    if (IconId.isValid(dir.name) && File(dir, "config.xml").isFile) {
                        dynamicIcons[dir.name] = DynamicIcon(dir.name, dir.path)
                    }
                }
            }
        }

        val commonDynamic = File(
            snapshotDir,
            "${Paths.THEMES_DIR}/${Paths.COMMON_STYLE}/${Paths.DYNAMIC_DIR}",
        )
        if (commonDynamic.isDirectory) {
            commonDynamic.listFiles().orEmpty().filter { it.isDirectory }.forEach { dir ->
                if (IconId.isValid(dir.name) && File(dir, "config.xml").isFile) {
                    dynamicIcons.putIfAbsent(dir.name, DynamicIcon(dir.name, dir.path))
                }
            }
        }
    }

    private fun readStatic(dir: File): StaticIcon? {
        if (!IconId.isValid(dir.name)) return null
        val background = File(dir, "0.png")
        val foreground = File(dir, "1.png")
        if (!background.isFile || !foreground.isFile) return null
        return StaticIcon(dir.name, background.path, foreground.path)
    }

    /** Returns how many static and dynamic icons were effectively removed. */
    private fun applyDisabled(
        staticIcons: MutableMap<String, StaticIcon>,
        flatIcons: MutableMap<String, String>,
        dynamicIcons: MutableMap<String, DynamicIcon>,
    ): Pair<Int, Int> {
        var static = 0
        disabledIds("static").forEach {
            // Both forms must go: a short-circuiting || would leave the flat
            // icon packaged when the id exists in both maps.
            val removedLayered = staticIcons.remove(it) != null
            val removedFlat = flatIcons.remove(it) != null
            if (removedLayered || removedFlat) static++
        }
        var dynamic = 0
        disabledIds("dynamic").forEach {
            if (dynamicIcons.remove(it) != null) dynamic++
        }
        return static to dynamic
    }

    private fun disabledIds(kind: String): Set<String> {
        val dir = File(customDir, "disabled/$kind")
        if (!dir.isDirectory) return emptySet()
        return dir.listFiles().orEmpty()
            .map { it.name }
            .filter(IconId::isValid)
            .toSet()
    }

    /** Returns the number of custom static entry folders laid over the theme. */
    private fun collectCustomStatic(
        staticIcons: MutableMap<String, StaticIcon>,
    ): Int {
        val dir = File(customDir, "static")
        if (!dir.isDirectory) return 0
        var overridden = 0
        dir.listFiles().orEmpty()
            .filter { it.isDirectory && IconId.isValid(it.name) }
            .forEach { iconDir ->
                val custom = readCustomStatic(iconDir) ?: return@forEach
                overridden++
                // A custom entry may override just one layer; the missing
                // layer falls back to the upstream icon it overrides.
                val existing = staticIcons[iconDir.name]
                // The launcher resolves layered icons before flat ones, so a
                // layered custom entry and a flat icon of the same id coexist
                // legally. The flat entry must survive: once the layered
                // override is removed the flat icon becomes visible again.
                staticIcons[iconDir.name] = StaticIcon(
                    id = iconDir.name,
                    background = custom.background ?: existing?.background,
                    foreground = custom.foreground ?: existing?.foreground,
                )
            }
        return overridden
    }

    /** Custom static entries need at least one layer; both is the common case. */
    private fun readCustomStatic(dir: File): StaticIcon? {
        if (!IconId.isValid(dir.name)) return null
        val background = File(dir, "0.png").takeIf { it.isFile }
        val foreground = File(dir, "1.png").takeIf { it.isFile }
        if (background == null && foreground == null) return null
        return StaticIcon(dir.name, background?.path, foreground?.path)
    }

    /** Returns the number of custom dynamic entry folders laid over the theme. */
    private fun collectCustomDynamic(dynamicIcons: MutableMap<String, DynamicIcon>): Int {
        val dir = File(customDir, "dynamic")
        if (!dir.isDirectory) return 0
        var overridden = 0
        dir.listFiles().orEmpty()
            .filter { it.isDirectory && IconId.isValid(it.name) }
            .forEach { iconDir ->
                if (File(iconDir, "config.xml").isFile) {
                    overridden++
                    dynamicIcons[iconDir.name] = DynamicIcon(iconDir.name, iconDir.path)
                }
            }
        return overridden
    }

    private fun customTransform(): File =
        File(customDir, "transform_config.xml").takeIf { it.isFile }
            ?: defaultTransformConfig

    private fun collectAddons(
        addons: MutableMap<String, String>,
        addonDirs: MutableSet<String>,
    ) {
        val dir = File(customDir, "addons")
        if (!dir.isDirectory) return
        dir.walkTopDown().forEach { file ->
            if (!file.isFile) return@forEach
            val relative = file.relativeTo(dir).invariantSeparatorsPath
            if (!SafeZipPath.isSafe(relative)) return@forEach
            addons[relative] = file.absolutePath
            val parent = relative.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) addonDirs += parent
        }
    }
}
