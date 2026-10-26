package io.github.perfecticons.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.perfecticons.R
import io.github.perfecticons.data.settings.ThemeMode
import io.github.perfecticons.ui.component.BlurredBar
import io.github.perfecticons.ui.component.LogSheet
import io.github.perfecticons.ui.component.rememberBlurBackdrop
import io.github.perfecticons.ui.navigation.Route
import io.github.perfecticons.ui.screen.CustomScreen
import io.github.perfecticons.ui.screen.HomeScreen
import io.github.perfecticons.ui.screen.ResourcesScreen
import io.github.perfecticons.ui.screen.SettingsScreen
import io.github.perfecticons.ui.screen.StaticAddAppScreen
import io.github.perfecticons.ui.screen.StaticAddPickScreen
import io.github.perfecticons.ui.viewmodel.CustomizationViewModel
import io.github.perfecticons.ui.viewmodel.HomeViewModel
import io.github.perfecticons.ui.viewmodel.ModuleInstallViewModel
import io.github.perfecticons.ui.viewmodel.ResourcesViewModel
import io.github.perfecticons.ui.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.springAnimateToPage

private data class Destination(
    val labelRes: Int,
    val icon: ImageVector,
)

private val destinations = listOf(
    Destination(R.string.nav_home, MiuixIcons.Home),
    Destination(R.string.nav_resources, MiuixIcons.Folder),
    Destination(R.string.nav_custom, MiuixIcons.Tune),
    Destination(R.string.nav_settings, MiuixIcons.Settings),
)

/** Pops sub-screens until the main shell is back on top. */
private fun NavBackStack.popToMain() {
    while (size > 1) removeAt(lastIndex)
}

/**
 * Distinguishes back-stack entries that would otherwise share a route value, so
 * NavDisplay's per-entry content key stays unique. Strictly increasing, so two
 * pushes in the same frame still differ.
 */
private val routeToken = java.util.concurrent.atomic.AtomicLong()

private fun nextToken(): Long = routeToken.incrementAndGet()

@Composable
fun PerfectIconsScaffold(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
) {
    val backStack = rememberNavBackStack<Route>(Route.Main)
    // Tabs are pages of one pager (the miuix Example's shell): the bottom bar
    // drives animateToPage, so switching tabs slides in tab order.
    val pagerState = rememberPagerState(pageCount = { destinations.size })
    val pagerScope = rememberCoroutineScope()
    // ViewModels are created at the shell level: NavDisplay entries own their
    // ViewModel stores, so entry-scoped instances would not match the log sheets
    // hosted here and would reset when switching tabs.
    val homeViewModel: HomeViewModel = viewModel()
    val installViewModel: ModuleInstallViewModel = viewModel()
    val resourcesViewModel: ResourcesViewModel = viewModel()
    val customizationViewModel: CustomizationViewModel = viewModel()
    val settingsViewModel: SettingsViewModel = viewModel()
    val homeState by homeViewModel.state.collectAsStateWithLifecycle()
    val installState by installViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val backdrop = rememberBlurBackdrop()

    Scaffold(
        bottomBar = {
            BlurredBar(backdrop = backdrop) {
                NavigationBar(
                    color = if (backdrop != null) {
                        Color.Transparent
                    } else {
                        MiuixTheme.colorScheme.surface
                    },
                ) {
                    destinations.forEachIndexed { index, destination ->
                        NavigationBarItem(
                            selected = pagerState.currentPage == index,
                            onClick = {
                                pagerScope.launch {
                                    backStack.popToMain()
                                    pagerState.springAnimateToPage(index)
                                }
                            },
                            icon = destination.icon,
                            label = stringResource(destination.labelRes),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(
            modifier = if (backdrop != null) {
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            } else {
                Modifier.fillMaxSize()
            },
        ) {
            NavDisplay(
                backStack = backStack,
                transition = NavTransitions.MiuixDefault,
            ) {
                entry<Route.Main>(swipeDismiss = NavSwipeDirection.None) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                    ) { page ->
                        when (page) {
                            0 -> HomeScreen(
                                padding = padding,
                                viewModel = homeViewModel,
                                installViewModel = installViewModel,
                                isActive = pagerState.currentPage == 0,
                            )

                            1 -> ResourcesScreen(
                                padding = padding,
                                viewModel = resourcesViewModel,
                                isActive = pagerState.currentPage == 1,
                            )

                            2 -> CustomScreen(
                                padding = padding,
                                viewModel = customizationViewModel,
                                onAddStatic = {
                                    backStack.add(
                                        Route.CustomStaticAdd(dynamic = false, token = nextToken()),
                                    )
                                },
                                onAddDynamic = {
                                    backStack.add(
                                        Route.CustomStaticAdd(dynamic = true, token = nextToken()),
                                    )
                                },
                            )

                            else -> SettingsScreen(
                                padding = padding,
                                themeMode = themeMode,
                                onThemeModeChange = onThemeModeChange,
                                viewModel = settingsViewModel,
                            )
                        }
                    }
                }
                entry<Route.CustomStaticAdd>(
                    transition = NavTransitions.MiuixDefault,
                    swipeDismiss = NavSwipeDirection.LeftToRight,
                ) { route ->
                    StaticAddAppScreen(
                        padding = padding,
                        viewModel = customizationViewModel,
                        dynamic = route.dynamic,
                        onOpenApp = { packageName ->
                            backStack.add(
                                Route.CustomStaticPick(
                                    packageName = packageName,
                                    dynamic = route.dynamic,
                                    token = nextToken(),
                                ),
                            )
                        },
                        onCreate = { ids ->
                            customizationViewModel.createEntries(ids, route.dynamic)
                            backStack.popToMain()
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
                entry<Route.CustomStaticPick>(
                    transition = NavTransitions.MiuixDefault,
                    swipeDismiss = NavSwipeDirection.LeftToRight,
                ) { route ->
                    StaticAddPickScreen(
                        padding = padding,
                        packageName = route.packageName,
                        viewModel = customizationViewModel,
                        onConfirm = { ids ->
                            customizationViewModel.createEntries(ids, route.dynamic)
                            backStack.popToMain()
                        },
                        onBack = { backStack.removeLastOrNull() },
                    )
                }
            }

            // Log sheets are hosted at the shell level so a running build or install
            // stays visible while switching tabs (ghostlock pattern).
            LogSheet(
                show = homeState.showLogSheet,
                title = stringResource(R.string.home_section_build_log),
                lines = homeState.buildLog,
                onDismiss = homeViewModel::dismissLogSheet,
                onCopy = {
                    copyToClipboard(context, homeState.buildLog.joinToString("\n") { it.text })
                },
            )
            LogSheet(
                show = installState.showLogSheet,
                title = stringResource(R.string.home_install_log),
                lines = installState.output,
                onDismiss = installViewModel::dismissLogSheet,
                onCopy = {
                    copyToClipboard(context, installState.output.joinToString("\n") { it.text })
                },
            )
        }
    }
}

private fun copyToClipboard(context: android.content.Context, text: String) {
    val manager = context.getSystemService(android.content.ClipboardManager::class.java)
    manager?.setPrimaryClip(android.content.ClipData.newPlainText("log", text))
}
