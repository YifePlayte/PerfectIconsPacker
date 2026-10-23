package io.github.perfecticons.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.perfecticons.R
import io.github.perfecticons.data.apps.InstalledApp
import io.github.perfecticons.domain.model.IconId
import io.github.perfecticons.ui.component.ActionButton
import io.github.perfecticons.ui.component.BackIcon
import io.github.perfecticons.ui.component.SectionLabel
import io.github.perfecticons.ui.component.BlurredBar
import io.github.perfecticons.ui.component.rememberAppIconThumb
import io.github.perfecticons.ui.component.rememberBlurBackdrop
import io.github.perfecticons.ui.viewmodel.CustomizationViewModel
import io.github.ranlee1.jpinyin.PinyinFormat.WITHOUT_TONE
import io.github.ranlee1.jpinyin.PinyinHelper.convertToPinyinString
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.FloatingActionButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.ListPopupDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.ArrowUpDown
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowListPopup

private enum class AppOrderType {
    Label,
    PackageName,
    FirstInstallTime,
}

/**
 * Static custom icon step 1: pick the installed app to create entries for.
 *
 * Row style follows KernelSU's superuser list (icon, title, identifier,
 * drill-in chevron); page affordances (search, sort, filters, back-to-top)
 * follow maxfreeform's app list. Tapping a row opens the icon-id picker; ids
 * that belong to no installed app are added from the manual field here.
 */
@Composable
fun StaticAddAppScreen(
    padding: PaddingValues,
    viewModel: CustomizationViewModel = viewModel(),
    dynamic: Boolean = false,
    onOpenApp: (String) -> Unit,
    onCreate: (ids: List<String>) -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    var query by rememberSaveable { mutableStateOf("") }
    var orderTypeName by rememberSaveable { mutableStateOf(AppOrderType.Label.name) }
    var orderReversed by rememberSaveable { mutableStateOf(false) }
    var showSystemApps by rememberSaveable { mutableStateOf(true) }
    var manualId by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (state.apps.isEmpty()) viewModel.loadApps()
    }

    val orderType = remember(orderTypeName) {
        runCatching { AppOrderType.valueOf(orderTypeName) }.getOrDefault(AppOrderType.Label)
    }
    val sortKeys = remember(state.apps) {
        state.apps.associate { app ->
            app.packageName to runCatching {
                convertToPinyinString(app.label, "", WITHOUT_TONE).lowercase()
            }.getOrDefault(app.label.lowercase())
        }
    }
    val visibleApps = remember(state.apps, query, showSystemApps, orderType, orderReversed) {
        val filtered = state.apps
            .asSequence()
            .filter { showSystemApps || !it.isSystem }
            .filter {
                query.isBlank() ||
                    it.label.contains(query, true) ||
                    it.packageName.contains(query, true) ||
                    (sortKeys[it.packageName] ?: "").contains(query, true)
            }
        val comparators = mutableListOf<(InstalledApp) -> Comparable<*>?>()
        comparators.add { app ->
            when (orderType) {
                AppOrderType.Label -> sortKeys[app.packageName] ?: app.label.lowercase()
                AppOrderType.PackageName -> app.packageName
                AppOrderType.FirstInstallTime -> app.firstInstallTime
            }
        }
        AppOrderType.entries.filter { it != orderType }.forEach { fallback ->
            comparators.add { app ->
                when (fallback) {
                    AppOrderType.Label -> sortKeys[app.packageName] ?: app.label.lowercase()
                    AppOrderType.PackageName -> app.packageName
                    AppOrderType.FirstInstallTime -> app.firstInstallTime
                }
            }
        }
        var comparator = compareBy(*comparators.toTypedArray())
        if (orderReversed) comparator = comparator.reversed()
        filtered.sortedWith(comparator).toList()
    }

    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val showFloating by remember {
        derivedStateOf {
            lazyListState.firstVisibleItemIndex > 0 ||
                lazyListState.firstVisibleItemScrollOffset > 0
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            BlurredBar(backdrop = backdrop) {
                Column {
                    TopAppBar(
                        title = stringResource(
                            if (dynamic) R.string.custom_dynamic_add else R.string.custom_add_title,
                        ),
                        color = if (backdrop != null) {
                            Color.Transparent
                        } else {
                            MiuixTheme.colorScheme.surface
                        },
                        scrollBehavior = scrollBehavior,
                        navigationIcon = { BackIcon(onClick = onBack) },
                        actions = {
                            MoreMenu(
                                orderReversed = orderReversed,
                                showSystemApps = showSystemApps,
                                onOrderReversedChange = { orderReversed = it },
                                onShowSystemAppsChange = { showSystemApps = it },
                            )
                        },
                    )
                    InputField(
                        query = query,
                        onQueryChange = { query = it },
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        label = stringResource(R.string.custom_add_search_app),
                    )
                    SortDropdown(
                        orderType = orderType,
                        onOrderTypeChange = { orderTypeName = it.name },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(
                visible = showFloating,
                enter = scaleIn(),
                exit = scaleOut(),
                // Lift above the shell's navigation bar so it is not hidden
                // behind it; keep the visual 16dp gap to the content edge.
                modifier = Modifier.padding(
                    bottom = 16.dp + padding.calculateBottomPadding(),
                    end = 16.dp,
                ),
            ) {
                FloatingActionButton(
                    containerColor = MiuixTheme.colorScheme.surface,
                    onClick = {
                        scope.launch { lazyListState.animateScrollToItem(0) }
                    },
                ) {
                    Icon(
                        imageVector = ArrowUpIcon,
                        contentDescription = stringResource(R.string.app_list_back_to_top),
                        tint = MiuixTheme.colorScheme.primary,
                    )
                }
            }
        },
    ) { innerPadding ->
        val loading = state.apps.isEmpty()
        Box(
            modifier = if (backdrop != null) {
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            } else {
                Modifier.fillMaxSize()
            },
        ) {
            AnimatedContent(
                targetState = loading,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "appListLoading",
            ) { isLoading ->
            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        InfiniteProgressIndicator()
                        Text(
                            text = stringResource(R.string.app_list_loading),
                            style = MiuixTheme.textStyles.body2,
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxSize()
                        .scrollEndHaptic()
                        .overScrollVertical()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(
                        top = innerPadding.calculateTopPadding() + 6.dp,
                        bottom = padding.calculateBottomPadding() + 12.dp,
                    ),
                ) {
                item {
                    Column(modifier = Modifier.animateItem()) {
                        SectionLabel(text = stringResource(R.string.custom_add_manual))
                        Card(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                            insideMargin = PaddingValues(16.dp),
                        ) {
                            TextField(
                                value = manualId,
                                onValueChange = { manualId = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.custom_add_manual_id),
                                useLabelAsPlaceholder = true,
                                singleLine = true,
                            )
                            Spacer(Modifier.height(8.dp))
                            ActionButton(
                                text = stringResource(R.string.custom_add_manual_add),
                                onClick = {
                                    val id = manualId.trim()
                                    manualId = ""
                                    onCreate(listOf(id))
                                },
                                primary = true,
                                enabled = IconId.isValid(manualId.trim()),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                if (visibleApps.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .animateItem()
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.app_list_empty),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.body2,
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = visibleApps,
                        key = { _, app -> app.packageName },
                    ) { _, app ->
                        AppRow(
                            app = app,
                            onClick = { onOpenApp(app.packageName) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
            }
        }
    }
}

@Composable
private fun AppRow(
    app: InstalledApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        insideMargin = PaddingValues(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        onClick = onClick,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val iconBitmap = rememberAppIconThumb(app.packageName, 48.dp)
            if (iconBitmap != null) {
                Image(
                    bitmap = iconBitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .size(48.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .padding(end = 10.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .size(48.dp)
                        .background(MiuixTheme.colorScheme.surfaceContainerHigh),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.label,
                    fontWeight = FontWeight(550),
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = if (app.entries.size > 1) {
                        "${app.packageName} · " +
                            stringResource(R.string.app_list_entries, app.entries.size)
                    } else {
                        app.packageName
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight(550),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Image(
                imageVector = MiuixIcons.Basic.ArrowRight,
                contentDescription = null,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .size(width = 10.dp, height = 16.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(
                    MiuixTheme.colorScheme.onSurfaceVariantActions,
                ),
            )
        }
    }
}

@Composable
private fun MoreMenu(
    orderReversed: Boolean,
    showSystemApps: Boolean,
    onOrderReversedChange: (Boolean) -> Unit,
    onShowSystemAppsChange: (Boolean) -> Unit,
) {
    val showMenu = remember { mutableStateOf(false) }
    val options = remember(orderReversed, showSystemApps) {
        listOf(
            R.string.app_list_reverse to orderReversed,
            R.string.app_list_show_system to showSystemApps,
        )
    }
    WindowListPopup(
        show = showMenu.value,
        popupPositionProvider = ListPopupDefaults.ContextMenuPositionProvider,
        alignment = PopupPositionProvider.Align.TopEnd,
        onDismissRequest = { showMenu.value = false },
    ) {
        ListPopupColumn {
            options.forEachIndexed { index, (labelRes, isSelected) ->
                DropdownImpl(
                    text = stringResource(labelRes),
                    optionSize = options.size,
                    isSelected = isSelected,
                    onSelectedIndexChange = {
                        when (index) {
                            0 -> onOrderReversedChange(!orderReversed)
                            1 -> onShowSystemAppsChange(!showSystemApps)
                        }
                        showMenu.value = false
                    },
                    index = index,
                )
            }
        }
    }
    IconButton(onClick = { showMenu.value = true }, holdDownState = showMenu.value) {
        Icon(
            imageVector = MiuixIcons.More,
            tint = MiuixTheme.colorScheme.onBackground,
            contentDescription = stringResource(R.string.custom_add_more),
        )
    }
}

@Composable
private fun SortDropdown(
    orderType: AppOrderType,
    onOrderTypeChange: (AppOrderType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val expanded = remember { mutableStateOf(false) }
    val labels = listOf(
        AppOrderType.Label to stringResource(R.string.app_list_sort_label),
        AppOrderType.PackageName to stringResource(R.string.app_list_sort_package),
        AppOrderType.FirstInstallTime to stringResource(R.string.app_list_sort_install),
    )
    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clickable { expanded.value = true }
                .heightIn(min = 48.dp)
                .padding(horizontal = 28.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = labels.first { it.first == orderType }.second,
                fontSize = MiuixTheme.textStyles.subtitle.fontSize,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onBackgroundVariant,
            )
            Icon(
                imageVector = MiuixIcons.Basic.ArrowUpDown,
                contentDescription = null,
                tint = MiuixTheme.colorScheme.onSurfaceVariantActions,
                modifier = Modifier.size(16.dp),
            )
        }
        WindowListPopup(
            show = expanded.value,
            alignment = PopupPositionProvider.Align.Start,
            popupPositionProvider = ListPopupDefaults.dropdownPositionProvider(12.dp, 8.dp),
            onDismissRequest = { expanded.value = false },
        ) {
            ListPopupColumn {
                labels.forEachIndexed { index, (type, label) ->
                    DropdownImpl(
                        text = label,
                        optionSize = labels.size,
                        isSelected = type == orderType,
                        index = index,
                        onSelectedIndexChange = {
                            onOrderTypeChange(type)
                            expanded.value = false
                        },
                    )
                }
            }
        }
    }
}

private val ArrowUpIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "ArrowUpward",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(fill = SolidColor(Color.Black)) {
            moveTo(4f, 12f)
            lineTo(5.41f, 13.41f)
            lineTo(11f, 7.83f)
            verticalLineTo(20f)
            horizontalLineTo(13f)
            verticalLineTo(7.83f)
            lineTo(18.59f, 13.41f)
            lineTo(20f, 12f)
            lineTo(12f, 4f)
            close()
        }
    }.build()
}
