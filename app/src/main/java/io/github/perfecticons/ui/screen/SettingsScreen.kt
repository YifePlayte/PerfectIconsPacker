package io.github.perfecticons.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.perfecticons.R
import io.github.perfecticons.data.settings.ThemeMode
import io.github.perfecticons.ui.component.ActionButton
import io.github.perfecticons.ui.component.ActionRow
import io.github.perfecticons.ui.component.PageScaffold
import io.github.perfecticons.ui.component.SectionCard
import io.github.perfecticons.ui.viewmodel.SettingsViewModel
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(
    padding: PaddingValues,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    viewModel: SettingsViewModel = viewModel(),
) {
    PageScaffold(
        title = stringResource(R.string.settings_title),
        padding = padding,
    ) {
        item {
            SectionCard(
                modifier = Modifier.animateItem(),
                title = stringResource(R.string.settings_section_appearance),
                padded = false,
            ) {
                OverlayDropdownPreference(
                    items = ThemeMode.entries.map { stringResource(it.labelRes) },
                    selectedIndex = themeMode.ordinal,
                    title = stringResource(R.string.settings_dark_mode),
                    summary = stringResource(R.string.settings_dark_mode_summary),
                    onSelectedIndexChange = { onThemeModeChange(ThemeMode.entries[it]) },
                )
            }
        }
        item {
            SectionCard(
                modifier = Modifier.animateItem(),
                title = stringResource(R.string.settings_section_repo),
                contentSpacing = 8.dp,
            ) {
                RepoForm(viewModel)
            }
        }
        item {
            SectionCard(
                modifier = Modifier.animateItem(),
                title = stringResource(R.string.settings_section_network),
                contentSpacing = 8.dp,
            ) {
                ProxyForm(viewModel)
            }
        }
    }
}

@Composable
private fun RepoForm(viewModel: SettingsViewModel) {
    val repo by viewModel.repo.collectAsStateWithLifecycle()
    var repoUrl by remember(repo) { mutableStateOf(repo.repoUrl) }
    var accelerator by remember(repo) { mutableStateOf(repo.accelerator) }
    var invalid by remember { mutableStateOf(false) }

    TextField(
        value = repoUrl,
        onValueChange = { repoUrl = it; invalid = false },
        modifier = Modifier.fillMaxWidth(),
        label = stringResource(R.string.settings_repo_url),
        useLabelAsPlaceholder = true,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    TextField(
        value = accelerator,
        onValueChange = { accelerator = it; invalid = false },
        modifier = Modifier.fillMaxWidth(),
        label = stringResource(R.string.settings_repo_accelerator),
        useLabelAsPlaceholder = true,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    )
    Text(
        text = stringResource(R.string.settings_repo_accelerator_hint),
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    AnimatedVisibility(
        visible = invalid,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Text(
            text = stringResource(R.string.settings_repo_invalid),
            style = MiuixTheme.textStyles.footnote2,
            color = MiuixTheme.colorScheme.error,
        )
    }
    Text(
        text = stringResource(
            R.string.settings_repo_current,
            accelerator.trim().ifEmpty { stringResource(R.string.settings_repo_direct) },
        ),
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    ActionRow {
        ActionButton(
            text = stringResource(R.string.settings_repo_save),
            onClick = { invalid = !viewModel.updateRepo(repoUrl, accelerator) },
            primary = true,
            modifier = Modifier.weight(1f),
        )
        ActionButton(
            text = stringResource(R.string.settings_repo_reset),
            onClick = { viewModel.resetRepo(); invalid = false },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ProxyForm(viewModel: SettingsViewModel) {
    val proxy by viewModel.proxy.collectAsStateWithLifecycle()
    var host by remember(proxy) { mutableStateOf(proxy?.host ?: "") }
    var port by remember(proxy) { mutableStateOf(proxy?.port?.toString() ?: "") }

    Text(
        text = stringResource(R.string.settings_proxy_summary),
        style = MiuixTheme.textStyles.footnote2,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
    )
    TextField(
        value = host,
        onValueChange = { host = it },
        modifier = Modifier.fillMaxWidth(),
        label = stringResource(R.string.settings_proxy_host),
        useLabelAsPlaceholder = true,
        singleLine = true,
    )
    TextField(
        value = port,
        onValueChange = { port = it.filter(Char::isDigit) },
        modifier = Modifier.fillMaxWidth(),
        label = stringResource(R.string.settings_proxy_port),
        useLabelAsPlaceholder = true,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    ActionRow {
        ActionButton(
            text = stringResource(R.string.settings_proxy_save),
            onClick = { viewModel.updateProxy(host, port) },
            primary = true,
            enabled = host.isNotBlank() && port.toIntOrNull()?.let { it in 1..65535 } == true,
            modifier = Modifier.weight(1f),
        )
        ActionButton(
            text = stringResource(R.string.settings_proxy_clear),
            onClick = viewModel::clearProxy,
            enabled = proxy != null,
            modifier = Modifier.weight(1f),
        )
    }
}
