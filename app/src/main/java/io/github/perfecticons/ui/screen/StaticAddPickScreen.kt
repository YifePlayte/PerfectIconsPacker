package io.github.perfecticons.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.perfecticons.R
import io.github.perfecticons.ui.component.ActionButton
import io.github.perfecticons.ui.component.BackIcon
import io.github.perfecticons.ui.component.SectionLabel
import io.github.perfecticons.ui.component.BlurredBar
import io.github.perfecticons.ui.component.pageContentPadding
import io.github.perfecticons.ui.component.pageScrollModifiers
import io.github.perfecticons.ui.component.rememberBlurBackdrop
import io.github.perfecticons.ui.component.rememberEntryIconThumb
import io.github.perfecticons.ui.viewmodel.CustomizationViewModel
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Static custom icon step 2: choose which icon ids of this app to create and
 * confirm. Layer images (0.png / 1.png) are managed later from the custom list;
 * this page only adds ids. The confirm button is pinned to the page bottom.
 */
@Composable
fun StaticAddPickScreen(
    padding: PaddingValues,
    packageName: String,
    viewModel: CustomizationViewModel = viewModel(),
    onConfirm: (ids: List<String>) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val app = state.apps.firstOrNull { it.packageName == packageName }
    var selectedIds by rememberSaveable(
        stateSaver = listSaver(save = { it.toList() }, restore = { it.toSet() }),
    ) { mutableStateOf(setOf<String>()) }

    fun toggle(id: String) {
        selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
    }

    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BlurredBar(backdrop = backdrop) {
                TopAppBar(
                    title = app?.label ?: packageName,
                    color = if (backdrop != null) {
                        Color.Transparent
                    } else {
                        MiuixTheme.colorScheme.surface
                    },
                    scrollBehavior = scrollBehavior,
                    navigationIcon = { BackIcon(onClick = onBack) },
                )
            }
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 16.dp + padding.calculateBottomPadding(),
                    ),
            ) {
                ActionButton(
                    text = stringResource(R.string.common_ok),
                    onClick = { onConfirm(selectedIds.toList()) },
                    primary = true,
                    enabled = selectedIds.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
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
            ) {
                item(key = "picker_label") {
                    SectionLabel(text = stringResource(R.string.custom_add_choose_id))
                }
                // One item per id so only the rows on screen are composed. An app
                // can expose hundreds (Duolingo ships over 200 launcher aliases),
                // and composing them all also started an icon decode for each.
                val suggestedIds = app?.suggestedIds.orEmpty()
                items(
                    items = suggestedIds,
                    key = { it.id },
                ) { suggested ->
                    val id = suggested.id
                    EntryRow(
                        id = id,
                        packageName = suggested.packageName,
                        className = suggested.className,
                        typeLabel = if (id == app?.packageName) {
                            stringResource(R.string.custom_id_package)
                        } else if (id.contains('#')) {
                            stringResource(R.string.custom_id_package_class)
                        } else {
                            stringResource(R.string.custom_id_class)
                        },
                        checked = id in selectedIds,
                        onClick = { toggle(id) },
                    )
                }
            }
        }
    }
}

/**
 * One id row: leading icon, the id and its kind stacked, and the checkbox at the
 * end. Laid out by hand instead of through BasicComponent's `startAction`
 * slot, which separates the icon from the text with a fixed gap that leaves the
 * icon off-centre against a two-line label.
 */
@Composable
private fun EntryRow(
    id: String,
    packageName: String,
    className: String?,
    typeLabel: String,
    checked: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryIcon(id = id, packageName = packageName, className = className)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        ) {
            Text(
                text = id,
                style = MiuixTheme.textStyles.body1,
            )
            Text(
                text = typeLabel,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = onClick,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

/**
 * Leading icon of one id row: an activity's own icon when the id names one, the
 * application icon otherwise. The placeholder keeps the row height stable while
 * a decode is in flight or when an id belongs to no installed component.
 */
@Composable
private fun EntryIcon(id: String, packageName: String, className: String?) {
    val icon = rememberEntryIconThumb(id, packageName, className, 40.dp)
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = null,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .size(40.dp),
        )
    } else {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .size(40.dp)
                .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        )
    }
}
