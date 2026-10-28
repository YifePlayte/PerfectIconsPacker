package io.github.perfecticons.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.perfecticons.R
import io.github.perfecticons.data.install.RootKind

import io.github.perfecticons.domain.model.BuildTarget
import io.github.perfecticons.ui.component.ActionButton
import io.github.perfecticons.ui.component.ActionRow
import io.github.perfecticons.ui.component.CreateDocumentRequest
import io.github.perfecticons.ui.component.CreateDocumentWithMime
import io.github.perfecticons.ui.component.PageScaffold
import io.github.perfecticons.ui.component.SectionCard
import io.github.perfecticons.ui.component.StatCard
import io.github.perfecticons.ui.component.StatusCard
import io.github.perfecticons.ui.component.StatusTone
import io.github.perfecticons.ui.viewmodel.HomeViewModel
import io.github.perfecticons.ui.viewmodel.UpstreamStatus
import io.github.perfecticons.ui.viewmodel.InstallOutcome
import io.github.perfecticons.ui.viewmodel.InstallUiState
import io.github.perfecticons.ui.viewmodel.ModuleInstallViewModel
import java.io.File
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun HomeScreen(
    padding: PaddingValues,
    viewModel: HomeViewModel = viewModel(),
    installViewModel: ModuleInstallViewModel = viewModel(),
    isActive: Boolean = true,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val installState by installViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // The picker may outlive the activity; keep the target path and its kind
    // across recreation so the export does not silently vanish.
    var pendingExportPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportMime by rememberSaveable { mutableStateOf<String?>(null) }

    // The shell keeps every tab composed in its pager: refresh whenever this
    // tab becomes the active one, so theme changes and re-syncs are reflected.
    // Refresh is local; the upstream check follows the snapshot commit and
    // therefore does not fire on every visit.
    LaunchedEffect(isActive) {
        if (isActive) {
            viewModel.refresh()
            installViewModel.detect()
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        CreateDocumentWithMime(),
    ) { uri ->
        val path = pendingExportPath
        if (uri != null && path != null) {
            viewModel.export(File(path)) {
                context.contentResolver.openOutputStream(uri)
            }
        }
        pendingExportPath = null
        pendingExportMime = null
    }

    PageScaffold(
        title = stringResource(R.string.home_title),
        padding = padding,
    ) {
        item {
            val snapshot = state.snapshot
            val upstream = state.upstream
            val theme = (snapshot?.styles ?: state.selectedStyles.toList())
                .joinToString(", ")
            val themeFooter = stringResource(R.string.home_status_detail_theme, theme)
            when {
                snapshot == null -> StatusCard(
                    tone = StatusTone.Error,
                    title = stringResource(R.string.home_status_unsynced),
                    detail = stringResource(R.string.home_status_hint),
                    footer = themeFooter,
                )

                snapshot.styles.toSet() != state.selectedStyles -> StatusCard(
                    tone = StatusTone.Warning,
                    title = stringResource(R.string.home_theme_changed),
                    detail = stringResource(R.string.home_theme_changed_hint),
                    footer = themeFooter,
                )

                upstream is UpstreamStatus.UpdateAvailable -> StatusCard(
                    tone = StatusTone.Warning,
                    title = stringResource(R.string.home_status_update),
                    detail = stringResource(
                        R.string.home_status_update_detail,
                        snapshot.commit.take(8),
                        upstream.commit.take(8),
                    ),
                    footer = themeFooter,
                )

                upstream is UpstreamStatus.UpToDate -> StatusCard(
                    tone = StatusTone.Ready,
                    title = stringResource(R.string.home_status_ready),
                    detail = stringResource(
                        R.string.home_status_latest_detail,
                        snapshot.commit.take(8),
                    ),
                    footer = themeFooter,
                )

                else -> StatusCard(
                    tone = StatusTone.Ready,
                    title = stringResource(R.string.home_status_ready),
                    detail = stringResource(
                        R.string.home_status_commit_detail,
                        snapshot.commit.take(8),
                    ),
                    footer = themeFooter,
                )
            }
        }
        item {
            Row(
                modifier = Modifier
                    .animateItem()
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(
                    label = stringResource(R.string.home_stat_static),
                    value = state.upstreamStatic?.toString() ?: "\u2014",
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = stringResource(R.string.home_stat_dynamic),
                    value = state.upstreamDynamic?.toString() ?: "\u2014",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            SectionCard(modifier = Modifier.animateItem(), 
                title = stringResource(R.string.home_section_build),
                contentSpacing = 8.dp,
            ) {
                val buildEnabled = !state.isBuilding && state.snapshot != null
                ActionRow {
                    ActionButton(
                        text = stringResource(R.string.home_build_module),
                        onClick = { viewModel.build(BuildTarget.Module) },
                        primary = true,
                        enabled = buildEnabled,
                        modifier = Modifier.weight(1f),
                    )
                    ActionButton(
                        text = stringResource(R.string.home_build_mtz),
                        onClick = { viewModel.build(BuildTarget.Mtz) },
                        enabled = buildEnabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                AnimatedVisibility(
                    visible = state.isBuilding,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Column {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = stringResource(R.string.home_building),
                            style = MiuixTheme.textStyles.footnote2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                AnimatedVisibility(
                    visible = !state.isBuilding && state.snapshot == null,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Text(
                        text = stringResource(R.string.home_build_disabled_hint),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        state.result?.let { result ->
            item {
                SectionCard(modifier = Modifier.animateItem(), 
                    title = stringResource(R.string.home_section_result),
                    contentSpacing = 8.dp,
                ) {
                    Text(
                        text = stringResource(R.string.home_result_path, result.output.name),
                        style = MiuixTheme.textStyles.body2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            R.string.home_result_stats,
                            result.staticCount,
                            result.dynamicCount,
                            result.addonCount,
                            result.elapsedMs / 1000,
                        ),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    ActionRow {
                        ActionButton(
                            text = stringResource(R.string.home_result_save),
                            onClick = {
                                pendingExportPath = result.output.absolutePath
                                saveLauncher.launch(
                                    CreateDocumentRequest(
                                        fileName = result.output.name,
                                        mimeType = result.target.mimeType,
                                    ),
                                )
                            },
                            primary = true,
                            modifier = Modifier.weight(1f),
                        )
                        ActionButton(
                            text = stringResource(R.string.home_result_log),
                            onClick = viewModel::showBuildLog,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    AnimatedVisibility(
                        visible = result.target == BuildTarget.Module,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                    ActionButton(
                        text = stringResource(R.string.home_result_install),
                        onClick = { installViewModel.install(result.output) },
                        enabled = !installState.isInstalling &&
                            installState.rootStatus?.canInstall == true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    }
                }
            }
        }
        item {
            SectionCard(modifier = Modifier.animateItem(), title = stringResource(R.string.home_section_root)) {
                InstallStatusBody(installState, installViewModel)
            }
        }
        state.savedMessage?.let { message ->
            item {
                SectionCard {
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        }
        installState.outcome?.let { outcome ->
            item {
                SectionCard(modifier = Modifier.animateItem(), contentSpacing = 8.dp) {
                    when (outcome) {
                        is InstallOutcome.Success -> {
                            Text(
                                text = stringResource(R.string.install_success),
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.primary,
                            )
                            ActionRow {
                                ActionButton(
                                    text = stringResource(R.string.home_result_log),
                                    onClick = installViewModel::showLog,
                                    modifier = Modifier.weight(1f),
                                )
                                ActionButton(
                                    text = stringResource(R.string.home_reboot),
                                    onClick = installViewModel::reboot,
                                    primary = true,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }

                        is InstallOutcome.Failure -> {
                            Text(
                                text = outcome.message,
                                style = MiuixTheme.textStyles.footnote2,
                                color = MiuixTheme.colorScheme.error,
                            )
                            ActionButton(
                                text = stringResource(R.string.home_result_log),
                                onClick = installViewModel::showLog,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
        state.error?.let { message ->
            item {
                SectionCard {
                    Text(
                        text = message,
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun InstallStatusBody(
    state: InstallUiState,
    viewModel: ModuleInstallViewModel,
) {
    val status = state.rootStatus
    if (status == null) {
        Text(
            text = stringResource(
                if (state.isDetecting) R.string.home_root_detecting else R.string.home_root_unknown,
            ),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    } else if (!status.granted) {
        Text(
            text = stringResource(R.string.home_root_not_granted),
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.error,
        )
        Text(
            text = stringResource(R.string.home_root_hint),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    } else {
        val label = when (status.kind) {
            RootKind.Magisk -> "Magisk"
            RootKind.KernelSu -> "KernelSU"
            RootKind.Apatch -> "APatch"
            RootKind.Other -> stringResource(R.string.home_root_other)
            RootKind.None -> stringResource(R.string.home_root_none)
        }
        Text(
            text = stringResource(
                R.string.home_root_active,
                label,
                status.version ?: "",
            ).trim(),
            style = MiuixTheme.textStyles.body2,
        )
        if (status.hasMountProvider == false) {
            Text(
                text = stringResource(R.string.home_root_ksu_overlay_missing),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.error,
            )
        }
    }
    ActionButton(
        text = stringResource(R.string.home_root_refresh),
        onClick = { viewModel.detect(recheck = true) },
        enabled = !state.isDetecting,
        modifier = Modifier.fillMaxWidth(),
    )
}
