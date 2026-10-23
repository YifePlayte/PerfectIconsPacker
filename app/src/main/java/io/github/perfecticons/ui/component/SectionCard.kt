package io.github.perfecticons.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.SmallTitle

/**
 * Section header label. The list's horizontal margin is cancelled so the label
 * keeps only SmallTitle's own indent instead of stacking both margins.
 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    SmallTitle(
        text = text,
        modifier = modifier.offset(x = -pageHorizontalMargin()),
    )
}

private val SectionContentMargin = PaddingValues(16.dp)

/**
 * One section of a page: an optional [SmallTitle] label above a [Card] grouping
 * related content, following the miuix Example's `SmallTitle + Card` pattern.
 *
 * Miuix [Card] has no default inner margin. Free-form content (texts, buttons)
 * should use the default [padded]; preference rows carry their own padding and
 * need `padded = false`.
 */
@Composable
fun SectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    padded: Boolean = true,
    contentSpacing: Dp = 4.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            SectionLabel(text = title)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = if (padded) SectionContentMargin else PaddingValues(0.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(contentSpacing),
                content = content,
            )
        }
    }
}
