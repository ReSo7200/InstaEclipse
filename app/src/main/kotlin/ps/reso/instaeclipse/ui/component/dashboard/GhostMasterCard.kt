package ps.reso.instaeclipse.ui.component.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.reso.instaeclipse.R
import ps.reso.instaeclipse.core.Hook
import ps.reso.instaeclipse.ui.theme.AppSpacing
import ps.reso.instaeclipse.ui.theme.MotionDuration
import ps.reso.instaeclipse.ui.theme.MotionEasing
import ps.reso.instaeclipse.ui.theme.m3ContentSizeSpringSpec

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GhostMasterShortcutCard(
    allGhostHooks: List<Hook>,
    selectedChipIds: List<String>,
    isHookEnabled: (String) -> Boolean,
    onToggleHook: (String, Boolean) -> Unit,
    onToggleAll: (List<String>, Boolean) -> Unit,
    onToggleChipSelection: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showChipsDialog by remember { mutableStateOf(false) }

    val knownIds = remember(allGhostHooks) { allGhostHooks.map { it.id }.toSet() }
    val visibleChipIds = remember(selectedChipIds, knownIds) { selectedChipIds.filter { it in knownIds } }
    val allChipsEnabled = visibleChipIds.isNotEmpty() && visibleChipIds.all { isHookEnabled(it) }

    val targetContainerColor = if (allChipsEnabled) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val containerColor by animateColorAsState(
        targetValue = targetContainerColor,
        animationSpec = tween(MotionDuration.Medium2, easing = MotionEasing.Standard),
        label = "GhostMasterContainerColor"
    )

    val targetIconTint = if (allChipsEnabled) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val iconTint by animateColorAsState(
        targetValue = targetIconTint,
        animationSpec = tween(MotionDuration.Short4, easing = MotionEasing.Standard),
        label = "GhostMasterIconTint"
    )

    val iconScale by animateFloatAsState(
        targetValue = if (allChipsEnabled) 1.12f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "GhostMasterIconScale"
    )

    if (showChipsDialog) {
        GhostChipsDialog(
            allGhostHooks = allGhostHooks,
            selectedChipIds = selectedChipIds,
            onDismiss = { showChipsDialog = false },
            onToggleChip = onToggleChipSelection
        )
    }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = m3ContentSizeSpringSpec)
                .padding(AppSpacing.md),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Filled.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier
                            .size(22.dp)
                            .graphicsLayer(scaleX = iconScale, scaleY = iconScale),
                        tint = iconTint
                    )
                    Text(
                        text = "Ghost Mode Master",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xxs)
                ) {
                    IconButton(
                        onClick = { showChipsDialog = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = stringResource(R.string.cd_customize_ghost_chips),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Switch(
                        checked = allChipsEnabled,
                        onCheckedChange = { onToggleAll(visibleChipIds, it) },
                        thumbContent = if (allChipsEnabled) {
                            {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(SwitchDefaults.IconSize)
                                )
                            }
                        } else null,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            checkedIconColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        )
                    )
                }
            }

            if (visibleChipIds.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val hookMap = remember(allGhostHooks) { allGhostHooks.associateBy { it.id } }
                    visibleChipIds.forEach { id ->
                        val hook = hookMap[id] ?: return@forEach
                        val enabled = isHookEnabled(id)
                        val targetChipBg = if (enabled) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        }
                        val chipBg by animateColorAsState(
                            targetValue = targetChipBg,
                            animationSpec = tween(MotionDuration.Short4, easing = MotionEasing.Standard),
                            label = "GhostChipBg"
                        )
                        val targetChipFg = if (enabled) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                        val chipFg by animateColorAsState(
                            targetValue = targetChipFg,
                            animationSpec = tween(MotionDuration.Short4, easing = MotionEasing.Standard),
                            label = "GhostChipFg"
                        )
                        Surface(
                            shape = CircleShape,
                            color = chipBg,
                            contentColor = chipFg,
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onToggleHook(id, !enabled) }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                AnimatedVisibility(
                                    visible = enabled,
                                    enter = scaleIn() + fadeIn(),
                                    exit = scaleOut() + fadeOut()
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Text(
                                    text = hook.name.replace("Ghost ", ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GhostChipsDialog(
    allGhostHooks: List<Hook>,
    selectedChipIds: List<String>,
    onDismiss: () -> Unit,
    onToggleChip: (String) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.md)
                .padding(bottom = AppSpacing.lg)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AppSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.dialog_ghost_chips_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Text(
                text = stringResource(R.string.dialog_ghost_chips_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = AppSpacing.sm)
            )

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                modifier = Modifier.padding(bottom = AppSpacing.xs)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                items(allGhostHooks.size) { idx ->
                    val hook = allGhostHooks[idx]
                    val isSelected = selectedChipIds.contains(hook.id)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleChip(hook.id) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleChip(hook.id) },
                            colors = CheckboxDefaults.colors(
                                checkedColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = hook.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(AppSpacing.md))
            FilledTonalButton(
                onClick = onDismiss,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Done", fontWeight = FontWeight.Bold)
            }
        }
    }
}
