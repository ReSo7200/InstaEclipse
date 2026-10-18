package ps.reso.instaeclipse.ui.component.features

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.NotificationsPaused
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WebStories
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ps.reso.instaeclipse.R
import ps.reso.instaeclipse.core.HookCategory
import ps.reso.instaeclipse.core.HookGroup
import ps.reso.instaeclipse.ui.component.dashboard.DownloadFolderCard
import ps.reso.instaeclipse.ui.component.dashboard.GhostMasterShortcutCard
import ps.reso.instaeclipse.ui.component.dashboard.SectionCategoryCard
import ps.reso.instaeclipse.ui.theme.AppSpacing
import ps.reso.instaeclipse.ui.theme.ksuScreenTransition
import ps.reso.instaeclipse.ui.theme.m3ItemPlacementSpringSpec
import ps.reso.instaeclipse.ui.viewmodel.DashboardUiState

@Composable
fun FeaturesScreen(
    uiState: DashboardUiState,
    selectedCategory: HookCategory?,
    onSelectCategory: (HookCategory?) -> Unit,
    onToggleHook: (String, Boolean) -> Unit,
    onToggleAllGhost: (List<String>, Boolean) -> Unit,
    onToggleGhostChipSelection: (String) -> Unit,
    onOpenLocationPicker: () -> Unit,
    onOpenThemeCustomizer: () -> Unit,
    onPickFolder: () -> Unit,
    onQualitySelected: (Int) -> Unit = {},
    onSaveNavTabOrder: ((String) -> Unit)? = null,
    onSaveExactTimestampFormat: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    AnimatedContent(
        targetState = selectedCategory,
        transitionSpec = {
            ksuScreenTransition(forward = targetState != null, isRtl = isRtl)
        },
        label = "FeaturesSubpageTransition",
        modifier = modifier.fillMaxSize()
    ) { category ->
        if (category == null) {
            FeaturesIndexPage(
                uiState = uiState,
                onSelectCategory = { onSelectCategory(it) }
            )
        } else {
            FeatureSubpage(
                category = category,
                uiState = uiState,
                onToggleHook = onToggleHook,
                onToggleAllGhost = onToggleAllGhost,
                onToggleGhostChipSelection = onToggleGhostChipSelection,
                onOpenLocationPicker = onOpenLocationPicker,
                onOpenThemeCustomizer = onOpenThemeCustomizer,
                onPickFolder = onPickFolder,
                onQualitySelected = onQualitySelected,
                onSaveNavTabOrder = onSaveNavTabOrder,
                onSaveExactTimestampFormat = onSaveExactTimestampFormat
            )
        }
    }
}

@Composable
private fun FeaturesIndexPage(
    uiState: DashboardUiState,
    onSelectCategory: (HookCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    val groups = remember { HookGroup.entries }

    LazyColumn(
        contentPadding = PaddingValues(
            start = AppSpacing.md,
            end = AppSpacing.md,
            top = AppSpacing.xs,
            bottom = AppSpacing.md
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        modifier = modifier.fillMaxSize()
    ) {
        groups.forEach { group ->
            val categoriesInGroup = HookCategory.entries.filter { it.group == group }

            item(key = "group_header_${group.name}") {
                Text(
                    text = stringResource(group.labelRes).uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = AppSpacing.xs, bottom = AppSpacing.xxs)
                        .animateItem(placementSpec = m3ItemPlacementSpringSpec)
                )
            }

            categoriesInGroup.forEach { category ->
                val hooks = uiState.hooksByCategory[category].orEmpty()
                val activeCount = hooks.count { uiState.isHookEnabled(it.id) }

                item(key = "category_nav_${category.name}") {
                    FeatureCategoryNavCard(
                        category = category,
                        icon = getCategoryIcon(category),
                        activeCount = activeCount,
                        onClick = { onSelectCategory(category) },
                        modifier = Modifier.animateItem(placementSpec = m3ItemPlacementSpringSpec)
                    )
                }
            }
        }

        item(key = "bottom_spacer") { Spacer(Modifier.height(AppSpacing.xs)) }
    }
}

@Composable
private fun FeatureCategoryNavCard(
    category: HookCategory,
    icon: ImageVector,
    activeCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.md, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                shape = CircleShape,
                color = if (activeCount > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                contentColor = if (activeCount > 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(Modifier.width(AppSpacing.sm))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(category.labelRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                val subtitle = if (activeCount > 0) {
                    stringResource(R.string.feat_count_on, activeCount)
                } else {
                    stringResource(R.string.feat_count_off)
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (activeCount > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.width(AppSpacing.xs))

            if (activeCount > 0) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        text = stringResource(R.string.feat_count_on, activeCount),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(Modifier.width(AppSpacing.xs))
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun FeatureSubpage(
    category: HookCategory,
    uiState: DashboardUiState,
    onToggleHook: (String, Boolean) -> Unit,
    onToggleAllGhost: (List<String>, Boolean) -> Unit,
    onToggleGhostChipSelection: (String) -> Unit,
    onOpenLocationPicker: () -> Unit,
    onOpenThemeCustomizer: () -> Unit,
    onPickFolder: () -> Unit,
    onQualitySelected: (Int) -> Unit,
    onSaveNavTabOrder: ((String) -> Unit)?,
    onSaveExactTimestampFormat: ((String) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val hooks = uiState.hooksByCategory[category].orEmpty()

    LazyColumn(
        contentPadding = PaddingValues(
            start = AppSpacing.md,
            end = AppSpacing.md,
            top = AppSpacing.sm,
            bottom = AppSpacing.md
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        modifier = modifier.fillMaxSize()
    ) {
        if (category == HookCategory.DOWNLOADER) {
            item(key = "download_folder_card") {
                DownloadFolderCard(
                    uri = uiState.downloadSafUri,
                    onPickFolder = onPickFolder
                )
            }
        } else if (category == HookCategory.GHOST) {
            val allGhostHooks = uiState.hooksByCategory[HookCategory.GHOST].orEmpty()
            if (allGhostHooks.isNotEmpty()) {
                item(key = "ghost_master_shortcut_card") {
                    GhostMasterShortcutCard(
                        allGhostHooks = allGhostHooks,
                        selectedChipIds = uiState.ghostMasterChipIds,
                        isHookEnabled = { uiState.isHookEnabled(it) },
                        onToggleHook = onToggleHook,
                        onToggleAll = onToggleAllGhost,
                        onToggleChipSelection = onToggleGhostChipSelection
                    )
                }
            }
        }

        item(key = "category_${category.name}") {
            SectionCategoryCard(
                category = category,
                icon = getCategoryIcon(category),
                hooks = hooks,
                isHookEnabled = { uiState.isHookEnabled(it) },
                onToggle = onToggleHook,
                onOpenLocationPicker = if (category == HookCategory.LOCATION) onOpenLocationPicker else null,
                onOpenThemeCustomizer = if (category == HookCategory.THEME) onOpenThemeCustomizer else null,
                forceReelQuality = uiState.forceReelQuality,
                onQualitySelected = onQualitySelected,
                currentNavTabOrder = uiState.navTabOrder,
                onSaveNavTabOrder = onSaveNavTabOrder,
                exactTimestampFormat = uiState.exactTimestampFormat,
                onSaveExactTimestampFormat = onSaveExactTimestampFormat
            )
        }

        item(key = "subpage_bottom_spacer") {
            Spacer(Modifier.height(AppSpacing.md))
        }
    }
}

fun getCategoryIcon(category: HookCategory): ImageVector {
    return when (category) {
        HookCategory.THEME -> Icons.Outlined.Palette
        HookCategory.FEED -> Icons.Outlined.AutoAwesome
        HookCategory.NAVIGATION -> Icons.Outlined.Tune
        HookCategory.REELS -> Icons.Outlined.Movie
        HookCategory.STORIES -> Icons.Outlined.WebStories
        HookCategory.GHOST -> Icons.Outlined.VisibilityOff
        HookCategory.MESSAGES -> Icons.AutoMirrored.Outlined.Chat
        HookCategory.HIDE_CHATS -> Icons.Outlined.VisibilityOff
        HookCategory.LOCK -> Icons.Outlined.Lock
        HookCategory.ADS -> Icons.Outlined.Block
        HookCategory.LOCATION -> Icons.Outlined.Place
        HookCategory.DISTRACTION_FREE -> Icons.Outlined.NotificationsPaused
        HookCategory.DOWNLOADER -> Icons.Outlined.Download
        HookCategory.POST_MENU -> Icons.Outlined.Save
        HookCategory.INTERACTIONS -> Icons.Outlined.FavoriteBorder
        HookCategory.PROFILES -> Icons.Outlined.AccountCircle
        HookCategory.APP -> Icons.Outlined.Settings
        HookCategory.DEV_OPTIONS -> Icons.Outlined.Code
    }
}
