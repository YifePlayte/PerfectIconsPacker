package io.github.perfecticons.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class StatusTone {
    Ready,
    Warning,
    Error,
}

/**
 * Prominent status card in the module-app family style (MaxFreeForm / WOMMO /
 * KernelSU): the healthy and warning variants are tinted cards with an
 * oversized icon bleeding out of the bottom-end corner, the neutral variant is
 * a plain card row with a leading icon.
 */
@Composable
fun StatusCard(
    tone: StatusTone,
    title: String,
    detail: String,
    footer: String,
    modifier: Modifier = Modifier,
) {
    val dynamic = MiuixTheme.isDynamicColor
    // Follow the applied theme, not the system setting: the app can force
    // light or dark regardless of the device.
    val dark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    when (tone) {
        StatusTone.Ready -> BleedingStatusCard(
            modifier = modifier,
            container = when {
                dynamic -> MiuixTheme.colorScheme.secondaryContainer
                dark -> Color(0xFF1A3825)
                else -> Color(0xFFDFFAE4)
            },
            icon = Icons.Rounded.CheckCircleOutline,
            iconTint = when {
                dynamic -> MiuixTheme.colorScheme.primary.copy(alpha = 0.8f)
                else -> Color(0xFF36D167)
            },
            title = title,
            detail = detail,
            footer = footer,
        )

        StatusTone.Warning -> BleedingStatusCard(
            modifier = modifier,
            container = when {
                dynamic -> MiuixTheme.colorScheme.tertiaryContainer
                dark -> Color(0xFF3E2F1B)
                else -> Color(0xFFFFF0DB)
            },
            icon = Icons.Rounded.RemoveCircleOutline,
            iconTint = when {
                dynamic -> MiuixTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
                else -> Color(0xFFF5A623)
            },
            title = title,
            detail = detail,
            footer = footer,
        )

        StatusTone.Error -> BleedingStatusCard(
            modifier = modifier,
            container = when {
                dynamic -> MiuixTheme.colorScheme.errorContainer
                dark -> Color(0xFF3B1F1F)
                else -> Color(0xFFFCE8E6)
            },
            icon = Icons.Rounded.ErrorOutline,
            iconTint = when {
                dynamic -> MiuixTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                else -> Color(0xFFF72727)
            },
            title = title,
            detail = detail,
            footer = footer,
        )
    }
}

@Composable
private fun BleedingStatusCard(
    modifier: Modifier,
    container: Color,
    icon: ImageVector,
    iconTint: Color,
    title: String,
    detail: String,
    footer: String,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = container),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 110.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 27.dp, y = 31.dp)
                    .size(110.dp),
            )
            Column(
                modifier = Modifier.padding(
                    start = 16.dp,
                    top = 14.dp,
                    end = 120.dp,
                    bottom = 38.dp,
                ),
            ) {
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = detail,
                    fontSize = 15.sp,
                )
            }
            Text(
                text = footer,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 10.dp),
            )
        }
    }
}
