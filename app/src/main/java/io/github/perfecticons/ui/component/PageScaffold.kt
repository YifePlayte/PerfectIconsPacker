package io.github.perfecticons.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * Standard page shell: a collapsing [TopAppBar] over a scrolling [LazyColumn],
 * composed the way the miuix Example builds its pages.
 *
 * [padding] is the outer scaffold's [PaddingValues] (the navigation bar); each
 * page owns its own top bar, so the outer shell only hosts navigation chrome.
 */
@Composable
fun PageScaffold(
    title: String,
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            BlurredBar(backdrop = backdrop) {
                TopAppBar(
                    title = title,
                    color = if (backdrop != null) {
                        Color.Transparent
                    } else {
                        MiuixTheme.colorScheme.surface
                    },
                    scrollBehavior = scrollBehavior,
                    navigationIcon = navigationIcon,
                    actions = actions,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = if (backdrop != null) {
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            } else {
                Modifier.fillMaxSize()
            },
        ) {
            LazyColumn(
                modifier = Modifier.pageScrollModifiers(scrollBehavior),
                contentPadding = pageContentPadding(innerPadding, padding),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
    }
}

/** Overscroll glow, scroll-end haptics and top-app-bar collapse wired together. */
fun Modifier.pageScrollModifiers(scrollBehavior: ScrollBehavior): Modifier = this
    .overScrollVertical()
    .scrollEndHaptic()
    .nestedScroll(scrollBehavior.nestedScrollConnection)
    .fillMaxSize()

/** Horizontal margin every list applies to its items. */
@Composable
fun pageHorizontalMargin(): Dp {
    val windowWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    return ((windowWidth - 800.dp) / 2).coerceAtLeast(12.dp)
}

/**
 * Content margins for a page: below the top bar, above whichever bottom bar is
 * taller (the page's own or the shell's navigation bar), and horizontally
 * centered with a 800.dp cap on wide screens (ghostlock pattern).
 */
@Composable
fun pageContentPadding(
    innerPadding: PaddingValues,
    outerPadding: PaddingValues,
    extraTop: Dp = 8.dp,
): PaddingValues {
    val horizontal = pageHorizontalMargin()
    // A page that owns a bottomBar (e.g. the pinned confirm button) draws it over
    // its content, so the list has to clear the bar and not just the shell's
    // navigation bar. Whichever is taller wins; pages without a bar keep the
    // shell padding.
    val bottom = maxOf(
        innerPadding.calculateBottomPadding(),
        outerPadding.calculateBottomPadding(),
    )
    return PaddingValues(
        start = horizontal,
        end = horizontal,
        top = innerPadding.calculateTopPadding() + extraTop,
        bottom = bottom + 12.dp,
    )
}
