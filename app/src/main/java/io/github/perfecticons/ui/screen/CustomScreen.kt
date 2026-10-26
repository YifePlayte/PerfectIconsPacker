package io.github.perfecticons.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.perfecticons.R
import io.github.perfecticons.core.util.formatBytes
import io.github.perfecticons.data.custom.AddonEntry
import io.github.perfecticons.data.custom.DynamicEntry
import io.github.perfecticons.data.custom.StaticEntry
import io.github.perfecticons.data.apps.InstalledApp
import io.github.perfecticons.domain.model.IconId
import io.github.perfecticons.ui.component.ActionButton
import io.github.perfecticons.ui.component.BlurredBar
import io.github.perfecticons.ui.component.SectionCard
import io.github.perfecticons.ui.component.pageContentPadding
import io.github.perfecticons.ui.component.pageScrollModifiers
import io.github.perfecticons.ui.component.rememberBlurBackdrop
import io.github.perfecticons.ui.component.rememberFileThumb
import io.github.perfecticons.ui.viewmodel.CustomizationUiState
import io.github.perfecticons.ui.viewmodel.CustomizationViewModel
import java.io.File
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberPullToRefreshState
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val tabs = listOf("静态", "动态", "附加", "遮罩", "禁用")

/** Layer tile size; also the thumbnail decode target. */
private val LayerTileSize = 48.dp

/** Drawn diameter of the clear badge; its tap target stays larger. */
private val LayerClearBadgeSize = 16.dp

/** Tap target of the clear badge. */
private val LayerClearHitSize = 20.dp

/** Corner radius of a layer tile; the badge is clipped to the same shape. */
private val LayerTileCorner = 8.dp

/**
 * Drop shadow geometry. Compose's `shadow` only casts an even halo around the
 * shape, so the tile draws its own: a copy of the shape shifted toward the
 * bottom-right at 45° and blurred.
 */
private val LayerShadowOffset = 2.dp
private val LayerShadowBlur = 4.dp
private const val LayerShadowAlpha = 0.20f

/** Badge shadow: same direction, scaled down with the badge. */
private val BadgeShadowOffset = 1.dp
private val BadgeShadowBlur = 2.dp
private const val BadgeShadowAlpha = 0.22f

@Composable
fun CustomScreen(
    padding: PaddingValues,
    viewModel: CustomizationViewModel = viewModel(),
    onAddStatic: () -> Unit = {},
    onAddDynamic: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val scrollBehavior = MiuixScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val backdrop = rememberBlurBackdrop()
    val pullToRefreshState = rememberPullToRefreshState()

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearMessage()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BlurredBar(backdrop = backdrop) {
                TopAppBar(
                    title = stringResource(R.string.custom_title),
                    color = if (backdrop != null) {
                        Color.Transparent
                    } else {
                        MiuixTheme.colorScheme.surface
                    },
                    scrollBehavior = scrollBehavior,
                    bottomContent = {
                        TabRow(
                            tabs = tabs,
                            selectedTabIndex = selectedTab,
                            onTabSelected = { selectedTab = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 5.dp),
                            // Transparent strip so the blurred bar backdrop
                            // shows through; the selected pill stays opaque.
                            colors = TabRowDefaults.tabRowColors(
                                backgroundColor = Color.Transparent,
                            ),
                            minWidth = 56.dp,
                            maxWidth = 68.dp,
                            itemSpacing = 4.dp,
                        )
                    },
                )
            }
        },
        snackbarHost = {
            SnackbarHost(
                state = snackbarHostState,
                // The page sits above the shell's navigation bar; lift the
                // snackbar with it so it is never hidden behind it.
                modifier = Modifier.padding(bottom = padding.calculateBottomPadding()),
            )
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
            val modifier = Modifier
                .fillMaxSize()
                .pageScrollModifiers(scrollBehavior)
            val contentPadding = pageContentPadding(innerPadding, padding)

            // The customization tree is also edited outside the app through SAF,
            // so a pull gesture is the manual way to rescan it. Miuix expects the
            // refresh container to wrap the scrollable and the list to opt out of
            // its own overscroll, which the page modifier already provides.
            PullToRefresh(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refresh(fromPull = true) },
                pullToRefreshState = pullToRefreshState,
                contentPadding = contentPadding,
                topAppBarScrollBehavior = scrollBehavior,
                refreshTexts = listOf(
                    stringResource(R.string.custom_refresh_pulling),
                    stringResource(R.string.custom_refresh_release),
                    stringResource(R.string.custom_refresh_refreshing),
                    stringResource(R.string.custom_refresh_done),
                ),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            ) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "customTab",
                ) { tab ->
                    when (tab) {
                        0 -> StaticTab(state, viewModel, onAddStatic, modifier, contentPadding)
                        1 -> DynamicTab(state, viewModel, onAddDynamic, modifier, contentPadding)
                        2 -> AddonsTab(state, viewModel, modifier, contentPadding)
                        3 -> TransformTab(state, viewModel, modifier, contentPadding)
                        else -> DisabledTab(state, viewModel, modifier, contentPadding)
                    }
                }
            }
        }
    }
}

@Composable
private fun StaticTab(
    state: CustomizationUiState,
    viewModel: CustomizationViewModel,
    onAddStatic: () -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {


    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.importStaticZip(uri)
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard(modifier = Modifier.animateItem()) {
                Text(
                    text = stringResource(R.string.custom_static_help),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Button(onClick = onAddStatic, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.custom_static_add))
                }
                Button(
                    onClick = { zipPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.custom_static_bulk))
                }
            }
        }

        if (state.staticIcons.isEmpty()) {
            item { EmptyHint(stringResource(R.string.custom_static_empty), Modifier.animateItem()) }
        }

        items(state.staticIcons, key = { it.id }) { entry ->
            StaticRow(entry, viewModel, Modifier.animateItem())
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun StaticRow(
    entry: StaticEntry,
    viewModel: CustomizationViewModel,
    modifier: Modifier = Modifier,
) {

    val backgroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.saveStaticLayer(listOf(entry.id), uri, background = true)
    }
    val foregroundPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.saveStaticLayer(listOf(entry.id), uri, background = false)
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StaticLayerThumb(
                file = entry.backgroundFile,
                stamp = entry.backgroundStamp,
                onPick = { backgroundPicker.launch(arrayOf("image/png")) },
                onClear = { viewModel.clearStaticLayer(entry.id, "0.png") },
            )
            StaticLayerThumb(
                file = entry.foregroundFile,
                stamp = entry.foregroundStamp,
                onPick = { foregroundPicker.launch(arrayOf("image/png")) },
                onClear = { viewModel.clearStaticLayer(entry.id, "1.png") },
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.id, style = MiuixTheme.textStyles.body2)
                AnimatedVisibility(
                    visible = entry.ignoredFiles.isNotEmpty(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    Text(
                        text = stringResource(R.string.custom_static_ignored, entry.ignoredFiles.joinToString(", ")),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Button(onClick = { viewModel.deleteStatic(entry.id) }) {
                Text(stringResource(R.string.common_delete))
            }
        }
    }
}

/**
 * One layer slot of a custom entry: tap to add or replace the image, use the
 * badge to clear it. Unset layers show a dim placeholder tile.
 *
 * The badge overflows the slot instead of reserving space for itself: the tile
 * keeps its exact 48.dp footprint in the row, and the badge is drawn on top of
 * the neighbouring area with its centre on the tile's top-end corner.
 *
 * The tile needs to stay distinguishable from the card behind it in both themes.
 * The card is `surfaceContainer`, which is #FFFFFF in light and #242424 in dark;
 * `surfaceContainerHigh` is #E8E8E8 in light but also #242424 in dark, so the
 * tile used to disappear in dark mode. `surfaceContainerHighest` is #E8E8E8 /
 * #2D2D2D, one step apart from the card in both themes, so the fill alone
 * separates the tile; the 45° bottom-right shadow adds depth on light themes.
 *
 * [stamp] identifies the file's current content; passing it through makes the
 * slot recompose (and re-decode) when the same path gets new bytes.
 */
@Composable
private fun StaticLayerThumb(
    file: File?,
    stamp: String,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val bitmap = rememberFileThumb(file, LayerTileSize)
    val shape = RoundedCornerShape(LayerTileCorner)

    Box(modifier = Modifier.size(LayerTileSize)) {
        // 45° bottom-right drop shadow, drawn behind the tile.
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = LayerShadowOffset, y = LayerShadowOffset)
                .blur(LayerShadowBlur, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                .background(
                    color = Color.Black.copy(alpha = LayerShadowAlpha),
                    shape = shape,
                ),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(shape)
                .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                .clickable(onClick = onPick),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                // Keyed by the stamp so a replaced file yields a fresh decode
                // rather than the previous bitmap from the shared LRU cache.
                key(stamp) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = stringResource(R.string.custom_layer_pick, file!!.name),
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else {
                Text(
                    text = file?.name.orEmpty(),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
        }
        if (bitmap != null) {
            val name = file?.name.orEmpty()
            // Half of the hit box sits past the tile's edge, so the badge centre
            // lands on the corner while the row layout stays untouched.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = LayerClearHitSize / 2, y = -LayerClearHitSize / 2)
                    .size(LayerClearHitSize)
                    .clickable(onClick = onClear),
                contentAlignment = Alignment.Center,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Same 45° bottom-right direction as the tile, scaled down.
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .offset(x = BadgeShadowOffset, y = BadgeShadowOffset)
                            .blur(BadgeShadowBlur, edgeTreatment = BlurredEdgeTreatment.Unbounded)
                            .background(
                                color = Color.Black.copy(alpha = BadgeShadowAlpha),
                                shape = CircleShape,
                            ),
                    )
                    Box(
                        modifier = Modifier
                            .size(LayerClearBadgeSize)
                            .clip(CircleShape)
                            .background(MiuixTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Close,
                            contentDescription = stringResource(R.string.custom_layer_clear, name),
                            modifier = Modifier.size(10.dp),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DynamicTab(
    state: CustomizationUiState,
    viewModel: CustomizationViewModel,
    onAddDynamic: () -> Unit,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {

    var overwrite by rememberSaveable { mutableStateOf(true) }

    val bulkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.importDynamicZip(uri, overwrite)
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard(modifier = Modifier.animateItem()) {
                Text(
                    text = stringResource(R.string.custom_dynamic_help),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                SwitchPreference(
                    checked = overwrite,
                    onCheckedChange = { overwrite = it },
                    title = stringResource(R.string.custom_overwrite),
                )
                Button(onClick = onAddDynamic, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.custom_dynamic_add))
                }
                Button(
                    onClick = { bulkPicker.launch(arrayOf("application/zip", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.custom_dynamic_bulk))
                }
            }
        }

        if (state.dynamicIcons.isEmpty()) {
            item { EmptyHint(stringResource(R.string.custom_dynamic_empty), Modifier.animateItem()) }
        }

        items(state.dynamicIcons, key = { it.id }) { entry ->
            DynamicRow(entry, overwrite, viewModel, Modifier.animateItem())
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun DynamicRow(
    entry: DynamicEntry,
    overwrite: Boolean,
    viewModel: CustomizationViewModel,
    modifier: Modifier = Modifier,
) {

    val singlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.importDynamicSingle(uri, entry.id, overwrite)
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AnimatedVisibility(
                visible = entry.previews.isNotEmpty(),
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp)),
                ) {
                    // Layers stack in order: 0 at the back, higher layers on top,
                    // mirroring how the theme composites the animated icon.
                    entry.previews.forEach { preview ->
                        val bitmap = rememberFileThumb(File(preview.path), 48.dp)
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                modifier = Modifier.matchParentSize(),
                                contentScale = ContentScale.Fit,
                            )
                        }
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.id, style = MiuixTheme.textStyles.body2)
                val placeholder = entry.fileCount == 0
                val status = when {
                    placeholder -> stringResource(R.string.custom_dynamic_placeholder)
                    entry.validation.isValid ->
                        stringResource(R.string.custom_dynamic_files, entry.fileCount)
                    else -> (entry.validation as io.github.perfecticons.domain.validation.ValidationResult.Invalid)
                        .reasons.joinToString("；")
                }
                Text(
                    text = status,
                    style = MiuixTheme.textStyles.footnote2,
                    color = if (placeholder || entry.validation.isValid) {
                        MiuixTheme.colorScheme.onSurfaceVariantSummary
                    } else {
                        MiuixTheme.colorScheme.error
                    },
                )
            }
            Button(
                onClick = {
                    singlePicker.launch(arrayOf("application/zip", "application/octet-stream"))
                },
            ) {
                Text(stringResource(R.string.custom_dynamic_import))
            }
            Button(onClick = { viewModel.deleteDynamic(entry.id) }) {
                Text(stringResource(R.string.common_delete))
            }
        }
    }
}

@Composable
private fun AddonsTab(
    state: CustomizationUiState,
    viewModel: CustomizationViewModel,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {

    var pendingName by rememberSaveable { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = pendingName.trim().ifEmpty {
            uri.lastPathSegment?.substringAfterLast('/') ?: "addon"
        }
        viewModel.importAddon(uri, name)
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard(modifier = Modifier.animateItem()) {
                Text(
                    text = stringResource(R.string.custom_addons_help),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                TextField(
                    value = pendingName,
                    onValueChange = { pendingName = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.custom_addons_name),
                    useLabelAsPlaceholder = true,
                    singleLine = true,
                )
                Button(
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.custom_addons_add))
                }
            }
        }

        if (state.addons.isEmpty()) {
            item { EmptyHint(stringResource(R.string.custom_addons_empty), Modifier.animateItem()) }
        }

        items(state.addons, key = { it.relativePath }) { entry ->
            AddonRow(entry, viewModel, Modifier.animateItem())
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun AddonRow(
    entry: AddonEntry,
    viewModel: CustomizationViewModel,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(entry.relativePath, style = MiuixTheme.textStyles.body2)
                Text(
                    text = formatBytes(entry.bytes),
                    style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Button(onClick = { viewModel.deleteAddon(entry.relativePath) }) {
                Text(stringResource(R.string.common_delete))
            }
        }
    }
}

@Composable
private fun TransformTab(
    state: CustomizationUiState,
    viewModel: CustomizationViewModel,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.saveTransformConfig(uri)
    }

    LazyColumn(modifier = modifier, contentPadding = contentPadding) {
        item {
            SectionCard(modifier = Modifier.animateItem()) {
            Text(
                text = if (state.transformConfig == null) {
                    stringResource(R.string.custom_transform_default)
                } else {
                    stringResource(R.string.custom_transform_custom)
                },
                style = MiuixTheme.textStyles.body2,
            )
            Text(
                text = stringResource(R.string.custom_transform_help),
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Button(
                onClick = { picker.launch(arrayOf("text/xml", "application/xml", "*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.custom_transform_import))
            }
            Button(
                onClick = viewModel::resetTransformConfig,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.transformConfig != null,
            ) {
                Text(stringResource(R.string.custom_transform_reset))
            }
            }
        }
    }
}

@Composable
private fun DisabledTab(
    state: CustomizationUiState,
    viewModel: CustomizationViewModel,
    modifier: Modifier,
    contentPadding: PaddingValues,
) {
    var selectedGroup by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    val group = if (selectedGroup == 0) state.availableStatic else state.availableDynamic
    val disabled = if (selectedGroup == 0) state.disabledStatic else state.disabledDynamic
    val filtered = remember(group, query) {
        group.filter { query.isBlank() || it.contains(query, ignoreCase = true) }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            TabRow(
                tabs = listOf(
                    stringResource(R.string.custom_disable_static),
                    stringResource(R.string.custom_disable_dynamic),
                ),
                selectedTabIndex = selectedGroup,
                onTabSelected = { selectedGroup = it },
                modifier = Modifier
                    .animateItem()
                    .fillMaxWidth(),
                minWidth = 56.dp,
                maxWidth = 68.dp,
                itemSpacing = 4.dp,
            )
        }

        item {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.animateItem().fillMaxWidth(),
                label = stringResource(R.string.custom_disable_search),
                useLabelAsPlaceholder = true,
                singleLine = true,
            )
        }

        if (filtered.isEmpty()) {
            item { EmptyHint(stringResource(R.string.custom_disable_empty), Modifier.animateItem()) }
        }

        items(filtered, key = { it }) { id ->
            Card(modifier = Modifier.animateItem().fillMaxWidth()) {
                SwitchPreference(
                    checked = id in disabled,
                    onCheckedChange = { checked ->
                        if (selectedGroup == 0) {
                            viewModel.setStaticDisabled(id, checked)
                        } else {
                            viewModel.setDynamicDisabled(id, checked)
                        }
                    },
                    title = id,
                    summary = stringResource(R.string.custom_disable_hint),
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    SectionCard(modifier = modifier) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}
