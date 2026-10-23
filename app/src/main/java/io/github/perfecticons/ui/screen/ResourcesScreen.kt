package io.github.perfecticons.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.perfecticons.R
import io.github.perfecticons.core.util.formatBytes
import io.github.perfecticons.data.repo.SyncProgress
import io.github.perfecticons.ui.component.ActionButton
import io.github.perfecticons.ui.component.ActionRow
import io.github.perfecticons.ui.component.PageScaffold
import io.github.perfecticons.ui.component.SectionCard
import io.github.perfecticons.ui.viewmodel.ResourcesUiState
import io.github.perfecticons.ui.viewmodel.ResourcesViewModel
import java.text.DateFormat
import java.util.Date
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ResourcesScreen(
    padding: PaddingValues,
    viewModel: ResourcesViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PageScaffold(
        title = stringResource(R.string.resources_title),
        padding = padding,
    ) {
        item {
            SectionCard(modifier = Modifier.animateItem(), padded = false) {
                val styles = state.availableStyles.ifEmpty { state.selectedStyles.toList() }
                OverlayDropdownPreference(
                    items = styles,
                    selectedIndex = styles.indexOfFirst { it in state.selectedStyles }.coerceAtLeast(0),
                    title = stringResource(R.string.resources_style_title),
                    summary = if (state.stylesOutOfSync) {
                        stringResource(R.string.resources_style_resync)
                    } else {
                        stringResource(R.string.resources_style_summary)
                    },
                    onSelectedIndexChange = { viewModel.setStyle(styles[it]) },
                )
            }
        }
        item {
            SectionCard(modifier = Modifier.animateItem(), title = stringResource(R.string.resources_section_repository)) {
                Text(
                    text = stringResource(R.string.resources_repo_value),
                    style = MiuixTheme.textStyles.body2,
                )
                Text(
                    text = stringResource(R.string.resources_repo_hint),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        item {
            SectionCard(modifier = Modifier.animateItem(), title = stringResource(R.string.resources_section_snapshot)) {
                SyncStateBody(state)
            }
        }
        item {
            SectionCard(modifier = Modifier.animateItem()) {
                ActionRow {
                    ActionButton(
                        text = stringResource(R.string.resources_sync),
                        onClick = { viewModel.sync() },
                        primary = true,
                        enabled = !state.isSyncing,
                        modifier = Modifier.weight(1f),
                    )
                    ActionButton(
                        text = stringResource(R.string.resources_clear),
                        onClick = viewModel::clear,
                        enabled = !state.isSyncing && state.isSynced,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        state.error?.let { message ->
            item {
                SectionCard(modifier = Modifier.animateItem()) {
                    Text(
                        text = stringResource(R.string.resources_error, message),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.SyncStateBody(state: ResourcesUiState) {
    val snapshot = state.snapshot
    when {
        state.isSyncing -> {
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column {
                    Text(
                        text = progressText(state.progress),
                        style = MiuixTheme.textStyles.body2,
                    )
                    LinearProgressIndicator(
                        progress = progressFraction(state.progress),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        snapshot == null -> {
            Text(
                text = stringResource(R.string.resources_empty),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }

        else -> {
            Text(
                text = stringResource(R.string.resources_commit, snapshot.commit.take(8)),
                style = MiuixTheme.textStyles.body2,
            )
            Text(
                text = stringResource(
                    R.string.resources_stats,
                    snapshot.files.size,
                    formatBytes(snapshot.totalBytes),
                ),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = stringResource(
                    R.string.resources_styles,
                    snapshot.styles.joinToString(", "),
                ),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Text(
                text = stringResource(
                    R.string.resources_synced_at,
                    DateFormat.getDateTimeInstance().format(Date(snapshot.syncedAt)),
                ),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun progressText(progress: SyncProgress?): String = when (progress) {
    is SyncProgress.FetchingTrees -> stringResource(R.string.resources_progress_trees)
    is SyncProgress.Resolving -> stringResource(R.string.resources_progress_resolving)
    is SyncProgress.FetchingBlobs -> stringResource(
        R.string.resources_progress_blobs,
        progress.fetched,
        progress.total,
        progress.batch,
        progress.batches,
    )
    is SyncProgress.Materializing -> stringResource(
        R.string.resources_progress_materializing,
        progress.written,
        progress.total,
    )
    is SyncProgress.Done -> stringResource(R.string.resources_progress_done)
    is SyncProgress.Failed -> progress.message
    null -> stringResource(R.string.resources_progress_starting)
}

private fun progressFraction(progress: SyncProgress?): Float = when (progress) {
    is SyncProgress.FetchingBlobs -> progress.fetched.toFloat() / progress.total.coerceAtLeast(1)
    is SyncProgress.Materializing -> progress.written.toFloat() / progress.total.coerceAtLeast(1)
    is SyncProgress.Done -> 1f
    else -> 0f
}
