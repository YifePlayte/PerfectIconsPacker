package io.github.perfecticons.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.Text

/** Pause before a long line starts moving, and again once it reaches the end. */
private const val StartDelayMs = 1200L

/** Pause at the trailing edge before returning. */
private const val EndPauseMs = 900L

/** Scroll speed; slow enough to read an identifier while it passes. */
private const val PixelsPerSecond = 55f

/**
 * Single-line text that scrolls when it does not fit its row and stays still
 * when it does.
 *
 * The app list shows identifiers that can be wider than any phone, and a plain
 * `maxLines = 1` cut them off. The package name is what identifies the app, so
 * hiding its tail is worse than letting it move. Text that already fits is not
 * animated at all, so ordinary labels keep their static look.
 */
@Composable
fun ScrollingText(
    text: String,
    style: TextStyle,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    // Apply the weight to both measurement and drawing: a bold line is wider,
    // and measuring with a different style would under-scroll it.
    val resolvedStyle = remember(style, fontWeight) {
        if (fontWeight != null) style.copy(fontWeight = fontWeight) else style
    }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val availablePx = with(density) { maxWidth.roundToPx() }
        val measuredPx = remember(text, resolvedStyle) {
            measurer.measure(
                text = text,
                style = resolvedStyle,
                constraints = Constraints(),
            ).size.width
        }
        val overflowPx = (measuredPx - availablePx).coerceAtLeast(0)

        // Reset on every content change so a recycled row never inherits the
        // previous entry's offset.
        LaunchedEffect(text, overflowPx) {
            scrollState.scrollTo(0)
            if (overflowPx == 0) return@LaunchedEffect
            val durationMs = (overflowPx / PixelsPerSecond * 1000f).toInt().coerceAtLeast(300)
            val spec = tween<Float>(durationMillis = durationMs, easing = LinearEasing)
            while (true) {
                delay(StartDelayMs)
                scrollState.animateScrollTo(overflowPx, spec)
                delay(EndPauseMs)
                scrollState.animateScrollTo(0, spec)
            }
        }

        Box(modifier = Modifier.clipToBounds()) {
            Text(
                text = text,
                style = resolvedStyle,
                color = color,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.horizontalScroll(scrollState, enabled = false),
            )
        }
    }
}
