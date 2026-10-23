package io.github.perfecticons.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.perfecticons.data.settings.SettingsRepository
import io.github.perfecticons.data.settings.ThemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun PerfectIconsApp() {
    val context = LocalContext.current
    val settings = remember(context) { SettingsRepository(context) }
    var themeMode by remember { mutableStateOf(settings.themeMode) }

    val controller = remember(themeMode) {
        ThemeController(colorSchemeMode = themeMode.colorSchemeMode)
    }

    MiuixTheme(controller = controller) {
        PerfectIconsScaffold(
            themeMode = themeMode,
            onThemeModeChange = { mode ->
                themeMode = mode
                settings.themeMode = mode
            },
        )
    }
}
