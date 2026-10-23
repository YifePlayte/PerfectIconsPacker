package io.github.perfecticons.data.settings

import android.content.Context
import io.github.perfecticons.data.repo.PathSelector
import io.github.perfecticons.data.net.ProxyConfig
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

enum class ThemeMode(val labelRes: Int, val colorSchemeMode: ColorSchemeMode) {
    System(io.github.perfecticons.R.string.theme_mode_system, ColorSchemeMode.System),
    Light(io.github.perfecticons.R.string.theme_mode_light, ColorSchemeMode.Light),
    Dark(io.github.perfecticons.R.string.theme_mode_dark, ColorSchemeMode.Dark),
}

class SettingsRepository(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var iconStyles: Set<String>
        get() = preferences.getStringSet(KEY_ICON_STYLES, null)
            .orEmpty()
            .filter { PathSelector.isSafeId(it) }
            .toSet()
            .ifEmpty { DEFAULT_ICON_STYLES }
        set(value) {
            val safe = value.filter { PathSelector.isSafeId(it) }.toSet()
            preferences.edit()
                .putStringSet(KEY_ICON_STYLES, safe.ifEmpty { DEFAULT_ICON_STYLES })
                .apply()
        }

    var themeMode: ThemeMode
        get() = runCatching {
            ThemeMode.valueOf(preferences.getString(KEY_THEME_MODE, null) ?: ThemeMode.System.name)
        }.getOrDefault(ThemeMode.System)
        set(value) {
            preferences.edit().putString(KEY_THEME_MODE, value.name).apply()
        }

    var author: String
        get() = preferences.getString(KEY_AUTHOR, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_AUTHOR
        set(value) {
            preferences.edit().putString(KEY_AUTHOR, value.trim()).apply()
        }

    /**
     * Optional user-provided acceleration endpoint. Empty means direct access.
     * No acceleration service is bundled with the app.
     */
    var accelerator: String
        get() = preferences.getString(KEY_ACCELERATOR, null).orEmpty()
        set(value) {
            preferences.edit().putString(KEY_ACCELERATOR, value.trim()).apply()
        }

    var repoUrl: String
        get() = preferences.getString(KEY_REPO_URL, null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_REPO
        set(value) {
            preferences.edit().putString(KEY_REPO_URL, value.trim()).apply()
        }

    var proxy: ProxyConfig.Settings?
        get() {
            val host = preferences.getString(KEY_PROXY_HOST, null)?.takeIf { it.isNotBlank() }
                ?: return null
            val port = preferences.getInt(KEY_PROXY_PORT, 0)
            return ProxyConfig.Settings(host, port).takeIf { it.isValid }
        }
        set(value) {
            preferences.edit().apply {
                if (value == null) {
                    remove(KEY_PROXY_HOST)
                    remove(KEY_PROXY_PORT)
                } else {
                    putString(KEY_PROXY_HOST, value.host)
                    putInt(KEY_PROXY_PORT, value.port)
                }
            }.apply()
            ProxyConfig.apply(value)
        }

    companion object {
        const val PREFS_NAME = "settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_ICON_STYLES = "icon_styles"
        val DEFAULT_ICON_STYLES = setOf("Hyper")
        const val KEY_AUTHOR = "author"
        const val KEY_ACCELERATOR = "accelerator"
        const val KEY_REPO_URL = "repo_url"
        const val KEY_PROXY_HOST = "proxy_host"
        const val KEY_PROXY_PORT = "proxy_port"
        const val DEFAULT_AUTHOR = "PerfectIconsPacker"
        const val DEFAULT_REPO =
            "https://github.com/pzcn/Perfect-Icons-Completion-Project.git"
    }
}
