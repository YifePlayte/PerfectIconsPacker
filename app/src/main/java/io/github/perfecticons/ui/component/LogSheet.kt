package io.github.perfecticons.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.perfecticons.R
import io.github.perfecticons.core.log.LogLine
import io.github.perfecticons.core.log.LogTone
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet

/**
 * Log viewer styled after a terminal: monospace, dark panel, colour coded tones,
 * selectable text and auto-scroll to the newest line (ghostlock-app pattern).
 *
 * It lives in an [OverlayBottomSheet] so a running build or install can be
 * watched without leaving the page, and can be copied out for bug reports.
 */
@Composable
fun LogSheet(
    show: Boolean,
    title: String,
    lines: List<LogLine>,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    OverlayBottomSheet(
        show = show,
        title = title,
        onDismissRequest = onDismiss,
        startAction = {
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = MiuixIcons.Copy,
                    contentDescription = stringResource(R.string.log_copy),
                )
            }
        },
        endAction = {
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = MiuixIcons.Close,
                    contentDescription = stringResource(R.string.log_close),
                )
            }
        },
        content = {
            LogBody(
                lines = lines,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp, max = 520.dp)
                    .navigationBarsPadding(),
            )
        },
    )
}

@Composable
fun LogBody(
    lines: List<LogLine>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0B1220))
            .padding(12.dp),
    ) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 8.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line.text.trimEnd('\r', '\n'),
                        color = line.tone.color(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

private fun LogTone.color(): Color = when (this) {
    LogTone.Default -> Color(0xFFD1D5DB)
    LogTone.Step -> Color(0xFF60A5FA)
    LogTone.Success -> Color(0xFF5FD68A)
    LogTone.Warning -> Color(0xFFFFC94D)
    LogTone.Error -> Color(0xFFFF6B6B)
    LogTone.Dim -> Color(0xFF8B95A5)
}
