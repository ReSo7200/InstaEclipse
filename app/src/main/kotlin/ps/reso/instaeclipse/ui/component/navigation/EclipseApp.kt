package ps.reso.instaeclipse.ui.component.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import ps.reso.instaeclipse.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ps.reso.instaeclipse.ui.component.common.EmptySearchState
import ps.reso.instaeclipse.ui.component.common.QualityDropdownRow
import ps.reso.instaeclipse.ui.component.common.SwitchRow
import ps.reso.instaeclipse.ui.component.dashboard.AppQuickControls
import ps.reso.instaeclipse.ui.component.dashboard.HomeDashboard
import ps.reso.instaeclipse.ui.component.dashboard.TimestampFormatDialog
import ps.reso.instaeclipse.ui.component.faq.FaqScreen
import ps.reso.instaeclipse.ui.component.features.FeaturesScreen
import ps.reso.instaeclipse.ui.component.logs.LogsScreen
import ps.reso.instaeclipse.ui.component.settings.SettingsScreen
import androidx.compose.material.icons.filled.Schedule
import ps.reso.instaeclipse.utils.text.TimestampFormat
import java.util.Date
import java.util.Locale
import ps.reso.instaeclipse.ui.theme.AppSpacing
import ps.reso.instaeclipse.ui.theme.horizontalSwipeNavigator
import ps.reso.instaeclipse.ui.theme.ksuScreenTransition
import ps.reso.instaeclipse.ui.theme.ksuTabTransition
import ps.reso.instaeclipse.ui.theme.m3ItemPlacementSpringSpec
import ps.reso.instaeclipse.ui.viewmodel.AppNavDestination
import ps.reso.instaeclipse.ui.viewmodel.DashboardUiEffect
import ps.reso.instaeclipse.ui.viewmodel.DashboardUiState
import ps.reso.instaeclipse.ui.viewmodel.DashboardViewModel

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class, ExperimentalLayoutApi::class)
@Composable
fun EclipseApp(
    viewModel: DashboardViewModel,
    uiState: DashboardUiState,
    darkTheme: Boolean,
    onPickFolder: () -> Unit,
    onExportConfig: () -> Unit,
    onImportConfig: () -> Unit,
    onOpenLocationPicker: () -> Unit,
    onOpenThemeCustomizer: () -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val windowSizeClass = calculateWindowSizeClass(context as ComponentActivity)
    val isExpanded = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Medium ||
        windowSizeClass.widthSizeClass == WindowWidthSizeClass.Expanded

    val isImeVisible = WindowInsets.isImeVisible
    val inFeatureSubpage = uiState.currentDestination == AppNavDestination.FEATURES && uiState.selectedFeatureCategory != null

    BackHandler(enabled = uiState.isSearchActive || uiState.showSettings || inFeatureSubpage || uiState.currentDestination != AppNavDestination.HOME) {
        viewModel.handleBack()
    }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is DashboardUiEffect.ShowSnackbar -> {
                    snackbarHostState.showSnackbar(effect.message)
                }
                is DashboardUiEffect.ShowToast -> {
                    android.widget.Toast.makeText(context, effect.message, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AppTopBar(
                currentDestination = uiState.currentDestination,
                showSettings = uiState.showSettings,
                settingsSubpage = uiState.settingsSubpage,
                selectedFeatureCategory = uiState.selectedFeatureCategory,
                searchActive = uiState.isSearchActive,
                query = uiState.searchQuery,
                onQueryChange = { viewModel.setSearchQuery(it) },
                onSearchActiveChange = { viewModel.setSearchActive(it) },
                onBack = { viewModel.handleBack() },
                onNavigateToSettings = { viewModel.showSettingsScreen() }
            )
        },
        bottomBar = {
            AnimatedVisibility(
                visible = !uiState.showSettings && !inFeatureSubpage && !isImeVisible && (uiState.showAppControls || !isExpanded),
                enter = slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = tween(300)
                ) + fadeIn(
                    animationSpec = tween(300)
                ),
                exit = slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = tween(300)
                ) + fadeOut(
                    animationSpec = tween(300)
                )
            ) {
                Column {
                    if (uiState.showAppControls) {
                        AppQuickControls(
                            onForceStop = { viewModel.forceStopInstagram() },
                            onOpenApp = { viewModel.openInstagram() }
                        )
                    }
                    if (!isExpanded) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ) {
                            appNavEntries.forEach { entry ->
                                val selected = entry.destination == uiState.currentDestination
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { viewModel.selectDestination(entry.destination) },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) entry.selectedIcon else entry.icon,
                                            contentDescription = stringResource(entry.labelRes)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = stringResource(entry.shortLabelRes),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                        )
                                    },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                        indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                )
                            }
                        }
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) { padding ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isExpanded && !uiState.showSettings) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Spacer(Modifier.height(AppSpacing.md))
                    appNavEntries.forEach { entry ->
                        val selected = entry.destination == uiState.currentDestination
                        NavigationRailItem(
                            selected = selected,
                            onClick = { viewModel.selectDestination(entry.destination) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) entry.selectedIcon else entry.icon,
                                    contentDescription = stringResource(entry.labelRes)
                                )
                            },
                            label = { Text(stringResource(entry.shortLabelRes)) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }

            AnimatedContent(
                targetState = when {
                    uiState.showSettings -> "settings"
                    uiState.isSearchActive -> "search"
                    else -> "main"
                },
                transitionSpec = {
                    val forward = targetState != "main"
                    ksuScreenTransition(forward = forward, isRtl = isRtl)
                },
                label = "ScreenTransition",
                modifier = Modifier.weight(1f)
            ) { screen ->
                when (screen) {
                    "settings" -> {
                        SettingsScreen(
                            currentSubpage = uiState.settingsSubpage,
                            onNavigateToSubpage = { viewModel.selectSettingsSubpage(it) },
                            themeMode = uiState.themeMode,
                            onThemeModeChange = { viewModel.setThemeMode(it) },
                            amoledEnabled = uiState.isAppAmoledEnabled,
                            onAmoledToggle = { viewModel.setAppAmoled(it) },
                            onExportConfig = onExportConfig,
                            onImportConfig = onImportConfig
                        )
                    }
                    "search" -> {
                        LazyColumn(
                            contentPadding = PaddingValues(
                                start = AppSpacing.md,
                                end = AppSpacing.md,
                                top = AppSpacing.xs,
                                bottom = AppSpacing.md
                            ),
                            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            if (uiState.searchQuery.isNotBlank()) {
                                if (uiState.searchResults.isNotEmpty()) {
                                    item(key = "search_results_card") {
                                        Card(
                                            shape = MaterialTheme.shapes.extraLarge,
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                                contentColor = MaterialTheme.colorScheme.onSurface
                                            ),
                                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .animateItem(placementSpec = m3ItemPlacementSpringSpec)
                                        ) {
                                            Column(modifier = Modifier.padding(vertical = AppSpacing.xs)) {
                                                uiState.searchResults.forEachIndexed { index, hook ->
                                                    if (hook.id == "forceReelQuality") {
                                                        QualityDropdownRow(
                                                            currentQuality = uiState.forceReelQuality,
                                                            onQualitySelected = { viewModel.setForceReelQuality(it) },
                                                            showDivider = index < uiState.searchResults.lastIndex,
                                                            categoryBadge = hook.category.title
                                                        )
                                                    } else {
                                                        val checked = uiState.isHookEnabled(hook.id)
                                                        val isCustomThemeHook = hook.id == "customThemeEnabled"
                                                        val isExactTimestampsHook = hook.id == "exactTimestamps"
                                                        SwitchRow(
                                                            title = hook.name,
                                                            checked = checked,
                                                            onCheckedChange = { viewModel.setHookEnabled(hook.id, it) },
                                                            showDivider = index < uiState.searchResults.lastIndex,
                                                            categoryBadge = hook.category.title,
                                                            content = if (isCustomThemeHook) {
                                                                {
                                                                    AnimatedVisibility(
                                                                        visible = checked,
                                                                        enter = expandVertically() + fadeIn(),
                                                                        exit = shrinkVertically() + fadeOut()
                                                                    ) {
                                                                        Box(
                                                                            modifier = Modifier
                                                                                .fillMaxWidth()
                                                                                .padding(
                                                                                    start = AppSpacing.md,
                                                                                    end = AppSpacing.md,
                                                                                    top = 2.dp,
                                                                                    bottom = AppSpacing.sm
                                                                                )
                                                                        ) {
                                                                            FilledTonalButton(
                                                                                onClick = onOpenThemeCustomizer,
                                                                                shape = MaterialTheme.shapes.medium,
                                                                                modifier = Modifier.fillMaxWidth()
                                                                            ) {
                                                                                Icon(Icons.Filled.Palette, contentDescription = null, modifier = Modifier.size(18.dp))
                                                                                Spacer(Modifier.width(AppSpacing.xs))
                                                                                Text("Customize Theme…", fontWeight = FontWeight.SemiBold)
                                                                                Spacer(Modifier.weight(1f))
                                                                                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            } else if (isExactTimestampsHook) {
                                                                {
                                                                    AnimatedVisibility(
                                                                        visible = checked,
                                                                        enter = expandVertically() + fadeIn(),
                                                                        exit = shrinkVertically() + fadeOut()
                                                                    ) {
                                                                        var showFormatDialog by remember { mutableStateOf(false) }

                                                                        if (showFormatDialog) {
                                                                            TimestampFormatDialog(
                                                                                initialFormat = uiState.exactTimestampFormat,
                                                                                onDismissRequest = { showFormatDialog = false },
                                                                                onSaveFormat = { newFormat ->
                                                                                    showFormatDialog = false
                                                                                    viewModel.setExactTimestampFormat(newFormat)
                                                                                }
                                                                            )
                                                                        }

                                                                        val now = remember { Date() }
                                                                        val locale = remember { Locale.getDefault() }
                                                                        val currentSample = remember(uiState.exactTimestampFormat) {
                                                                            val sample = TimestampFormat.format(uiState.exactTimestampFormat, now, locale)
                                                                            if (uiState.exactTimestampFormat.isEmpty()) {
                                                                                sample
                                                                            } else {
                                                                                "${uiState.exactTimestampFormat}  ·  $sample"
                                                                            }
                                                                        }

                                                                        Box(
                                                                            modifier = Modifier
                                                                                .fillMaxWidth()
                                                                                .padding(
                                                                                    start = AppSpacing.md,
                                                                                    end = AppSpacing.md,
                                                                                    top = 2.dp,
                                                                                    bottom = AppSpacing.sm
                                                                                )
                                                                        ) {
                                                                            FilledTonalButton(
                                                                                onClick = { showFormatDialog = true },
                                                                                shape = MaterialTheme.shapes.medium,
                                                                                modifier = Modifier.fillMaxWidth()
                                                                            ) {
                                                                                Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(18.dp))
                                                                                Spacer(Modifier.width(AppSpacing.xs))
                                                                                Column(modifier = Modifier.weight(1f)) {
                                                                                    Text(
                                                                                        text = stringResource(R.string.ig_timestamp_format),
                                                                                        fontWeight = FontWeight.SemiBold,
                                                                                        style = MaterialTheme.typography.labelLarge
                                                                                    )
                                                                                    Text(
                                                                                        text = currentSample,
                                                                                        style = MaterialTheme.typography.bodySmall,
                                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                                        maxLines = 1,
                                                                                        overflow = TextOverflow.Ellipsis
                                                                                    )
                                                                                }
                                                                                Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            } else null
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    item(key = "empty_search") {
                                        Box(modifier = Modifier.animateItem(placementSpec = m3ItemPlacementSpringSpec)) {
                                            EmptySearchState(query = uiState.searchQuery)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> {
                        AnimatedContent(
                            targetState = uiState.currentDestination,
                            transitionSpec = {
                                val targetIndex = appNavEntries.indexOfFirst { it.destination == targetState }
                                val initialIndex = appNavEntries.indexOfFirst { it.destination == initialState }
                                ksuTabTransition(initialIndex = initialIndex, targetIndex = targetIndex, isRtl = isRtl)
                            },
                            label = "BottomNavDestinationTransition",
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalSwipeNavigator(
                                    currentDestination = uiState.currentDestination,
                                    destinations = appNavEntries.map { it.destination },
                                    isRtl = isRtl,
                                    onNavigate = { destination ->
                                        try {
                                            haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        } catch (_: Throwable) {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        viewModel.selectDestination(destination)
                                    }
                                )
                        ) { destination ->
                            when (destination) {
                                AppNavDestination.HOME -> {
                                    LazyColumn(
                                        contentPadding = PaddingValues(
                                            start = AppSpacing.md,
                                            end = AppSpacing.md,
                                            top = AppSpacing.xs,
                                            bottom = AppSpacing.md
                                        ),
                                        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                                        modifier = Modifier.fillMaxSize()
                                    ) {
                                        item(key = "home_dashboard") {
                                            Box(modifier = Modifier.animateItem(placementSpec = m3ItemPlacementSpringSpec)) {
                                                HomeDashboard(
                                                    uiState = uiState,
                                                    darkTheme = darkTheme,
                                                    onToggleHook = { id, enabled -> viewModel.setHookEnabled(id, enabled) },
                                                    onToggleAllGhost = { ids, enabled -> viewModel.setAllHooksEnabled(ids, enabled) },
                                                    onToggleShortcutSelection = { viewModel.toggleCustomShortcut(it) },
                                                    onToggleGhostChipSelection = { viewModel.toggleGhostMasterChip(it) },
                                                    onToggleAppControls = { viewModel.setAppControlsEnabled(it) },
                                                    onPickFolder = onPickFolder,
                                                    onLaunchInstagram = { viewModel.openInstagram() },
                                                    onOpenInstagramAppInfo = { viewModel.openInstagramAppInfo() },
                                                    onSelectVariant = { viewModel.setActiveInstagramPackage(it) },
                                                    onShowVariantDialog = { viewModel.setShowVariantDialog(it) }
                                                )
                                            }
                                        }
                                        item(key = "bottom_spacer") { Spacer(Modifier.height(AppSpacing.xs)) }
                                    }
                                }
                                AppNavDestination.FEATURES -> {
                                    FeaturesScreen(
                                        uiState = uiState,
                                        selectedCategory = uiState.selectedFeatureCategory,
                                        onSelectCategory = { viewModel.selectFeatureCategory(it) },
                                        onToggleHook = { id, enabled -> viewModel.setHookEnabled(id, enabled) },
                                        onToggleAllGhost = { ids, enabled -> viewModel.setAllHooksEnabled(ids, enabled) },
                                        onToggleGhostChipSelection = { viewModel.toggleGhostMasterChip(it) },
                                        onOpenLocationPicker = onOpenLocationPicker,
                                        onOpenThemeCustomizer = onOpenThemeCustomizer,
                                        onPickFolder = onPickFolder,
                                        onQualitySelected = { viewModel.setForceReelQuality(it) },
                                        onSaveNavTabOrder = { viewModel.setNavTabOrder(it) },
                                        onSaveExactTimestampFormat = { viewModel.setExactTimestampFormat(it) },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                AppNavDestination.LOGS -> {
                                    LogsScreen(
                                        logEntries = uiState.filteredLogEntries,
                                        allCount = uiState.logCountAll,
                                        hookedCount = uiState.logCountHooked,
                                        warningsCount = uiState.logCountWarnings,
                                        errorsCount = uiState.logCountErrors,
                                        selectedFilter = uiState.selectedLogFilter,
                                        searchQuery = uiState.logSearchQuery,
                                        isLoading = uiState.isLogLoading,
                                        onFilterSelected = { viewModel.setLogFilter(it) },
                                        onSearchQueryChange = { viewModel.setLogSearchQuery(it) },
                                        onRefresh = { viewModel.refreshLogs() },
                                        onCopy = { viewModel.copyLogs(context) },
                                        onShare = { viewModel.shareLogs(context) },
                                        onClear = { viewModel.clearLogs() },
                                        onCopyEntry = { viewModel.copyLogEntry(context, it) },
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs)
                                    )
                                }
                                AppNavDestination.HELP -> {
                                    FaqScreen(
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
