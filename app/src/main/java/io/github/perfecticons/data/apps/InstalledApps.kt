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
)

data class InstalledApp(
    val packageName: String,
    val label: String,
    val entries: List<LaunchEntry>,
    val isSystem: Boolean = false,
    val firstInstallTime: Long = 0L,
) {
    val suggestedIds: List<String>
        get() = buildList {
            add(packageName)
            entries.forEach { entry ->
                val resolved = IconId.resolve(entry.packageName, entry.className)
                if (resolved != packageName && resolved !in this) add(resolved)
            }
        }

    val hasMultipleEntries: Boolean get() = suggestedIds.size > 1
}

/**
 * Lists launchable apps together with every activity that can appear on the home
 * screen. Activity aliases are included because launchers treat them as separate
 * entries with their own icon identifiers.
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
                val label = infos.firstOrNull()?.loadLabel(manager)?.toString() ?: packageName
                val entries = infos
                    .map { info ->
                        LaunchEntry(
                            packageName = packageName,
                            className = info.activityInfo.name,
                            label = info.loadLabel(manager)?.toString() ?: packageName,
                        )
                    }
                    .distinctBy { it.className }
                    .sortedBy { it.className }
                val applicationInfo = infos.firstOrNull()?.activityInfo?.applicationInfo
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
