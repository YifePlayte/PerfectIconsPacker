package io.github.perfecticons.ui.component

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Backdrop of the scrolling content beneath a bar (miuix Example's blur
 * pattern). `null` when RuntimeShader blur is unavailable; callers then fall
 * back to an opaque bar.
 */
@Composable
fun rememberBlurBackdrop(): LayerBackdrop? {
    if (!isRuntimeShaderSupported()) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/**
 * Bar area with a blurred translucent backdrop over the content recorded by
 * [rememberBlurBackdrop]. Bars drawing inside it should use a transparent
 * background so the blur shows through.
 *
 * The bar also swallows taps across its whole area. The backdrop covers the
 * content below it but the bar itself is just a drawing surface: gaps between
 * its children (beside the title, right of a short row) were left transparent
 * to touches, so a tap there reached the list item scrolled underneath and
 * opened it. Only taps are taken; drags still reach the list so the bar keeps
 * following the scroll.
 */
@Composable
fun BlurredBar(
    backdrop: LayerBackdrop?,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = (if (backdrop != null) {
            Modifier.textureBlur(
                backdrop = backdrop,
                shape = RectangleShape,
                blurRadius = 25f,
                colors = BlurDefaults.blurColors(
                    blendColors = listOf(
                        BlendColorEntry(color = MiuixTheme.colorScheme.surface.copy(alpha = 0.8f)),
                    ),
                ),
            )
        } else {
            Modifier
        }).pointerInput(Unit) { detectTapGestures(onTap = {}) },
    ) {
        content()
    }
}
