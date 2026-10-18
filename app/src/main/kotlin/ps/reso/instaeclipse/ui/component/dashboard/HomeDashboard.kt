package ps.reso.instaeclipse.ui.component.dashboard

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.reso.instaeclipse.R
import ps.reso.instaeclipse.core.HookCategory
import ps.reso.instaeclipse.ui.theme.AppSpacing
import ps.reso.instaeclipse.ui.theme.MotionDuration
import ps.reso.instaeclipse.ui.theme.MotionEasing
import ps.reso.instaeclipse.ui.theme.m3ContentSizeSpringSpec
import ps.reso.instaeclipse.ui.viewmodel.DashboardUiState
import ps.reso.instaeclipse.ui.viewmodel.GHOST_MASTER_SHORTCUT_ID
import ps.reso.instaeclipse.ui.viewmodel.InstalledInstagramInfo

@Composable
fun HomeDashboard(
    uiState: DashboardUiState,
    darkTheme: Boolean,
    onToggleHook: (String, Boolean) -> Unit,
    onToggleAllGhost: (List<String>, Boolean) -> Unit,
    onToggleShortcutSelection: (String) -> Unit,
    onToggleGhostChipSelection: (String) -> Unit,
    onToggleAppControls: (Boolean) -> Unit,
    onPickFolder: () -> Unit,
    onLaunchInstagram: () -> Unit,
    onOpenInstagramAppInfo: () -> Unit,
    onSelectVariant: (String) -> Unit,
    onShowVariantDialog: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var showAddShortcutDialog by remember { mutableStateOf(false) }

    val hooksById = remember(uiState.allHooks) { uiState.allHooks.associateBy { it.id } }
    val ghostHooks = remember(uiState.allHooks) {
        uiState.allHooks.filter { it.category == HookCategory.GHOST }
    }
    val visibleShortcutIds = remember(uiState.customShortcutIds, hooksById) {
        uiState.customShortcutIds.filter { it == GHOST_MASTER_SHORTCUT_ID || it in hooksById }
    }

    if (showAddShortcutDialog) {
        AddShortcutsDialog(
            allHooks = uiState.allHooks,
            selectedShortcutIds = uiState.customShortcutIds,
            onDismiss = { showAddShortcutDialog = false },
            onToggleShortcut = onToggleShortcutSelection,
            showAppControls = uiState.showAppControls,
            onToggleAppControls = onToggleAppControls
        )
    }

    if (uiState.showVariantDialog && uiState.installedInstagramVariants.size > 1) {
        VariantPickerDialog(
            variants = uiState.installedInstagramVariants,
            selectedPkg = uiState.activeInstagram?.packageName ?: "",
            onSelect = onSelectVariant,
            onDismiss = { onShowVariantDialog(false) }
        )
    }

    val statusBg by animateColorAsState(
        targetValue = if (uiState.isModuleActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer,
        animationSpec = tween(MotionDuration.Medium2, easing = MotionEasing.Standard),
        label = "HomeStatusBg"
    )
    val statusFg by animateColorAsState(
        targetValue = if (uiState.isModuleActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer,
        animationSpec = tween(MotionDuration.Medium2, easing = MotionEasing.Standard),
        label = "HomeStatusFg"
    )
    val logoScale by animateFloatAsState(
        targetValue = if (uiState.isModuleActive) 1f else 0.95f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "HomeLogoScale"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = m3ContentSizeSpringSpec),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md)
    ) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.md)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = statusBg,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_logo),
                                contentDescription = "InstaEclipse Logo",
                                tint = statusFg,
                                modifier = Modifier
                                    .size(32.dp)
                                    .graphicsLayer(scaleX = logoScale, scaleY = logoScale)
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(if (uiState.isModuleActive) R.string.home_hero_status else R.string.home_hero_status_inactive),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (uiState.isModuleActive) {
                                val framework = uiState.detectedFramework ?: "LSPosed"
                                stringResource(R.string.home_hero_connected_desc, framework)
                            } else {
                                stringResource(R.string.home_hero_inactive_desc)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    val context = LocalContext.current
                    val totalActiveFeatures = remember(uiState.enabledHookIds, uiState.forceReelQuality) {
                        uiState.enabledHookIds.size + if (uiState.forceReelQuality != 0) 1 else 0
                    }
                    val featuresText = remember(totalActiveFeatures, context) {
                        context.resources.getQuantityString(
                            R.plurals.home_features_enabled,
                            totalActiveFeatures,
                            totalActiveFeatures
                        )
                    }

                    Text(
                        text = featuresText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        InstagramStatusCard(
            active = uiState.activeInstagram,
            allVariants = uiState.installedInstagramVariants,
            showAppControls = uiState.showAppControls,
            onLaunch = onLaunchInstagram,
            onOpenInfo = onOpenInstagramAppInfo,
            onOpenVariantDialog = { onShowVariantDialog(true) }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Quick Shortcuts",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            IconButton(
                onClick = { showAddShortcutDialog = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Customize Shortcuts",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        if (visibleShortcutIds.isEmpty()) {
            Text(
                text = "No shortcuts pinned. Tap the edit icon above to pin quick toggles.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xs)
            )
        } else {
            visibleShortcutIds.forEach { id ->
                if (id == GHOST_MASTER_SHORTCUT_ID) {
                    GhostMasterShortcutCard(
                        allGhostHooks = ghostHooks,
                        selectedChipIds = uiState.ghostMasterChipIds,
                        isHookEnabled = { uiState.isHookEnabled(it) },
                        onToggleHook = onToggleHook,
                        onToggleAll = onToggleAllGhost,
                        onToggleChipSelection = onToggleGhostChipSelection
                    )
                } else {
                    hooksById[id]?.let { hook ->
                        CustomShortcutCard(
                            hook = hook,
                            isEnabled = uiState.isHookEnabled(hook.id),
                            onToggle = { enabled -> onToggleHook(hook.id, enabled) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InstagramStatusCard(
    active: InstalledInstagramInfo?,
    allVariants: List<InstalledInstagramInfo>,
    showAppControls: Boolean,
    onLaunch: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenVariantDialog: () -> Unit
) {
    val context = LocalContext.current

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (active != null) Modifier.clickable(onClick = onLaunch)
                else Modifier
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                modifier = Modifier.fillMaxWidth()
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_instagram_logo),
                            contentDescription = "Instagram",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    if (active != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Instagram",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            ) {
                                Text(
                                    text = active.variantLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "Version ${active.versionName}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Instagram Not Detected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "Install an official or supported Instagram APK",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (active != null) {
                    IconButton(
                        onClick = onOpenInfo,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "App Info",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (active != null) {
                if (allVariants.size > 1 || !showAppControls) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        if (!showAppControls) {
                            FilledTonalButton(
                                onClick = onLaunch,
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Launch,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Launch Instagram", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        if (allVariants.size > 1) {
                            FilledTonalButton(
                                onClick = onOpenVariantDialog,
                                shape = MaterialTheme.shapes.large,
                                modifier = if (!showAppControls) Modifier.weight(1f) else Modifier.fillMaxWidth()
                            ) {
                                Text("Switch Variant (${allVariants.size})", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            } else {
                FilledTonalButton(
                    onClick = {
                        val url = "https://www.apkmirror.com/uploads/?appcategory=instagram-instagram"
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    },
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Download from APKMirror", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun VariantPickerDialog(
    variants: List<InstalledInstagramInfo>,
    selectedPkg: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Active Instagram Variant", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                variants.forEach { variant ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(variant.packageName)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = variant.packageName == selectedPkg,
                            onClick = {
                                onSelect(variant.packageName)
                                onDismiss()
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = "${variant.variantLabel} (${variant.versionName})",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = variant.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
