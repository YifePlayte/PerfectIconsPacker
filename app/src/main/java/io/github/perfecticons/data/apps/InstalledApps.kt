package io.github.perfecticons.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import io.github.perfecticons.domain.model.IconId

/** One launcher entry that can own an icon. */
data class LaunchEntry(
    val packageName: String,
    val className: String?,
    val label: String,
    /**
     * Whether this entry declares its own icon, i.e. its launcher icon differs
     * from the application icon. Apps that switch their home-screen icon do it by
     * enabling an alias that carries a distinct icon, so these are the entries a
     * user is most likely looking for.
     */
    val hasOwnIcon: Boolean = false,
)

data class InstalledApp(
    val packageName: String,
    val label: String,
    val entries: List<LaunchEntry>,
    val isSystem: Boolean = false,
    val firstInstallTime: Long = 0L,
) {
    /** One selectable id plus the component it stands for. */
    data class SuggestedId(
        val id: String,
        val packageName: String,
        val className: String?,
    )

    val suggestedIds: List<SuggestedId>
        get() = buildList {
            add(SuggestedId(packageName, packageName, null))
            entries.forEach { entry ->
                val resolved = IconId.resolve(entry.packageName, entry.className)
                if (resolved != packageName && none { it.id == resolved }) {
                    add(SuggestedId(resolved, entry.packageName, entry.className))
                }
            }
        }

    val hasMultipleEntries: Boolean get() = suggestedIds.size > 1
}

/**
 * Lists launchable apps together with every activity that can appear on the home
 * screen. Activity aliases are included because launchers treat them as separate
 * entries with their own icon identifiers.
 *
 * Disabled components are collected too. Apps switch their launcher icon by
 * flipping an alias between enabled and disabled (`setComponentEnabledSetting`),
 * so a currently disabled alias is still a launcher entry: Duolingo ships 211 of
 * them, one per seasonal or streak-saver icon, and any of them can be on the
 * home screen after the app flips it.
 */
class InstalledApps(private val context: Context) {

    fun load(): List<InstalledApp> {
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = manager.queryIntentActivities(
            intent,
            PackageManager.MATCH_ALL or PackageManager.MATCH_DISABLED_COMPONENTS,
        )

        return resolved
            .filter { it.activityInfo != null && it.activityInfo.packageName != context.packageName }
            .groupBy { it.activityInfo.packageName }
            .map { (packageName, infos) ->
                val applicationInfo = runCatching {
                    manager.getApplicationInfo(packageName, 0)
                }.getOrNull()
                // ResolveInfo.loadLabel() prefers the entry's own android:label, so
                // one leftover activity can rename the whole app: mijia ships
                // BlockCanary's DisplayActivity with a launcher filter and its own
                // "Blocks" label, which then wins over the <application> label.
                // Read the application label instead; well-behaved entries leave
                // android:label unset and resolve to it anyway.
                val label = applicationInfo
                    ?.let { manager.getApplicationLabel(it).toString() }
                    ?.takeIf { it.isNotBlank() }
                    ?: infos.firstOrNull()?.loadLabel(manager)?.toString()
                    ?: packageName
                val appIconRes = applicationInfo?.icon ?: 0
                val entries = infos
                    .map { info ->
                        LaunchEntry(
                            packageName = packageName,
                            className = info.activityInfo.name,
                            label = info.loadLabel(manager)?.toString() ?: label,
                            // An alias that names a different icon resource than
                            // the application is one the app enables to switch the
                            // home-screen icon; a shared or inherited resource is
                            // just the app icon restated.
                            hasOwnIcon = info.activityInfo.icon != 0 &&
                                info.activityInfo.icon != appIconRes,
                        )
                    }
                    .distinctBy { it.className }
                    // Entries with their own icon first: those are the ones an
                    // icon-switching app can actually put on the home screen.
                    .sortedWith(compareByDescending<LaunchEntry> { it.hasOwnIcon }
                        .thenBy { it.className })
                InstalledApp(
                    packageName = packageName,
                    label = label,
                    entries = entries,
                    isSystem = applicationInfo != null &&
                        applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    firstInstallTime = runCatching {
                        manager.getPackageInfo(packageName, 0).firstInstallTime
                    }.getOrDefault(0L),
                )
            }
            .sortedBy { it.label.lowercase() }
    }
}
