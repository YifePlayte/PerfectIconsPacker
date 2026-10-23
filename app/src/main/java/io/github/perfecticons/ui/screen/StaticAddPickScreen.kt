package io.github.perfecticons.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import io.github.perfecticons.ui.viewmodel.CustomizationViewModel
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
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
            item {
                Column {
                    SectionLabel(text = stringResource(R.string.custom_add_choose_id))
                    app?.suggestedIds?.forEach { id ->
                        val checked = id in selectedIds
                        BasicComponent(
                            title = id,
                            summary = if (id == app.packageName) {
                                stringResource(R.string.custom_id_package)
                            } else if (id.contains('#')) {
                                stringResource(R.string.custom_id_package_class)
                            } else {
                                stringResource(R.string.custom_id_class)
                            },
                            onClick = { toggle(id) },
                            endActions = {
                                Checkbox(
                                    state = if (checked) ToggleableState.On else ToggleableState.Off,
                                    onClick = { toggle(id) },
                                )
                            },
                        )
                    }
                }
            }
        }
        }
    }
}
