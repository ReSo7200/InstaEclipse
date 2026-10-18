package ps.reso.instaeclipse.ui.component.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.reso.instaeclipse.R
import ps.reso.instaeclipse.core.Hook
import ps.reso.instaeclipse.core.HookCategory
import androidx.compose.material.icons.filled.Reorder
import ps.reso.instaeclipse.ui.component.common.QualityDropdownRow
import ps.reso.instaeclipse.ui.component.common.SwitchRow
import ps.reso.instaeclipse.ui.theme.AppSpacing
import ps.reso.instaeclipse.ui.theme.MotionDuration
import ps.reso.instaeclipse.ui.theme.MotionEasing
import ps.reso.instaeclipse.ui.theme.m3ContentSizeSpringSpec

import androidx.compose.material.icons.filled.Schedule
import androidx.compose.ui.text.style.TextOverflow
import ps.reso.instaeclipse.utils.text.TimestampFormat
import java.util.Date
import java.util.Locale

@Composable
fun SectionCategoryCard(
    category: HookCategory,
    icon: ImageVector,
    hooks: List<Hook>,
    isHookEnabled: (String) -> Boolean,
    onToggle: (String, Boolean) -> Unit,
    onOpenLocationPicker: (() -> Unit)? = null,
    onOpenThemeCustomizer: (() -> Unit)? = null,
    forceReelQuality: Int = 0,
    onQualitySelected: ((Int) -> Unit)? = null,
    currentNavTabOrder: String = "",
    onSaveNavTabOrder: ((String) -> Unit)? = null,
    exactTimestampFormat: String = "",
    onSaveExactTimestampFormat: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (hooks.isEmpty()) return

    val isDistractionSection = category == HookCategory.DISTRACTION_FREE
    val isExtremeActive = isHookEnabled("isExtremeMode")
    val otherDistractionHooks = remember(hooks) { hooks.filter { it.id != "isExtremeMode" } }
    val anyDistractionOptionEnabled = otherDistractionHooks.any { isHookEnabled(it.id) }

    val activeCount = remember(hooks, isHookEnabled) { hooks.count { isHookEnabled(it.id) } }
    val (badgeText, isBadgeActive) = when {
        hooks.isNotEmpty() -> {
            val text = if (activeCount > 0) {
                stringResource(R.string.feat_count_on, activeCount)
            } else {
                stringResource(R.string.feat_count_off)
            }
            text to (activeCount > 0)
        }
        else -> null to false
    }

    var showExtremeConfirmDialog by remember { mutableStateOf(false) }

    if (showExtremeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExtremeConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.ig_dialog_distraction_extreme_title),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.ig_dialog_distraction_extreme_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExtremeConfirmDialog = false
                        onToggle("isExtremeMode", true)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text(stringResource(R.string.ig_dialog_yes), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExtremeConfirmDialog = false }) {
                    Text(stringResource(R.string.ig_dialog_cancel))
                }
            }
        )
    }

    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = m3ContentSizeSpringSpec)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.md, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    }
                    Text(
                        text = category.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (badgeText != null) {
                    val badgeBg by animateColorAsState(
                        targetValue = if (isBadgeActive) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        animationSpec = tween(MotionDuration.Short4, easing = MotionEasing.Standard),
                        label = "CategoryBadgeBg"
                    )
                    val badgeFg by animateColorAsState(
                        targetValue = if (isBadgeActive) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(MotionDuration.Short4, easing = MotionEasing.Standard),
                        label = "CategoryBadgeFg"
                    )

                    Surface(
                        shape = CircleShape,
                        color = badgeBg,
                        contentColor = badgeFg
                    ) {
                        Text(
                            text = badgeText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                modifier = Modifier.padding(horizontal = AppSpacing.md)
            )

            if (category == HookCategory.LOCATION && onOpenLocationPicker != null) {
                FilledTonalButton(
                    onClick = onOpenLocationPicker,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs)
                ) {
                    Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(AppSpacing.xs))
                    Text("Pick Location on Map", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            if (category == HookCategory.NAVIGATION) {
                var showTabOrderDialog by remember { mutableStateOf(false) }

                if (showTabOrderDialog) {
                    TabOrderDialog(
                        initialOrder = currentNavTabOrder.ifEmpty { ps.reso.instaeclipse.utils.feature.FeatureFlags.navTabOrder },
                        onDismissRequest = { showTabOrderDialog = false },
                        onSaveOrder = { order ->
                            showTabOrderDialog = false
                            onSaveNavTabOrder?.invoke(order)
                        }
                    )
                }

                FilledTonalButton(
                    onClick = { showTabOrderDialog = true },
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs)
                ) {
                    Icon(Icons.Filled.Reorder, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(AppSpacing.xs))
                    Text(stringResource(R.string.ig_nav_tab_order), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Filled.Launch, contentDescription = null, modifier = Modifier.size(16.dp))
                }
            }

            Column(modifier = Modifier.padding(vertical = AppSpacing.xxs)) {
                if (category == HookCategory.REELS && onQualitySelected != null) {
                    QualityDropdownRow(
                        currentQuality = forceReelQuality,
                        onQualitySelected = { onQualitySelected.invoke(it) },
                        showDivider = hooks.isNotEmpty()
                    )
                }
                hooks.forEachIndexed { index, hook ->
                        val checked = isHookEnabled(hook.id)
                        val isCustomThemeHook = hook.id == "customThemeEnabled" && onOpenThemeCustomizer != null
                        val isExactTimestampsHook = hook.id == "exactTimestamps"

                        val isEnabled = if (isDistractionSection) {
                            if (hook.id == "isExtremeMode") {
                                !isExtremeActive && anyDistractionOptionEnabled
                            } else if (isExtremeActive) {
                                false
                            } else if (hook.id == "disableReelsExceptDM") {
                                isHookEnabled("disableReels")
                            } else {
                                true
                            }
                        } else {
                            true
                        }

                        SwitchRow(
                            title = hook.name,
                            checked = checked,
                            enabled = isEnabled,
                            onCheckedChange = { newValue ->
                                if (hook.id == "isExtremeMode") {
                                    if (newValue) {
                                        showExtremeConfirmDialog = true
                                    }
                                } else {
                                    onToggle(hook.id, newValue)
                                }
                            },
                            showDivider = index < hooks.size - 1,
                            content = if (isCustomThemeHook) {
                                {
                                    AnimatedVisibility(
                                        visible = checked,
                                        enter = expandVertically(
                                            animationSpec = tween(MotionDuration.Medium2, easing = MotionEasing.EmphasizedDecelerate)
                                        ) + fadeIn(
                                            animationSpec = tween(MotionDuration.Medium1, delayMillis = MotionDuration.Short1, easing = MotionEasing.Linear)
                                        ),
                                        exit = shrinkVertically(
                                            animationSpec = tween(MotionDuration.Short4, easing = MotionEasing.EmphasizedAccelerate)
                                        ) + fadeOut(
                                            animationSpec = tween(MotionDuration.Short2, easing = MotionEasing.Linear)
                                        )
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
                                        enter = expandVertically(
                                            animationSpec = tween(MotionDuration.Medium2, easing = MotionEasing.EmphasizedDecelerate)
                                        ) + fadeIn(
                                            animationSpec = tween(MotionDuration.Medium1, delayMillis = MotionDuration.Short1, easing = MotionEasing.Linear)
                                        ),
                                        exit = shrinkVertically(
                                            animationSpec = tween(MotionDuration.Short4, easing = MotionEasing.EmphasizedAccelerate)
                                        ) + fadeOut(
                                            animationSpec = tween(MotionDuration.Short2, easing = MotionEasing.Linear)
                                        )
                                    ) {
                                        var showFormatDialog by remember { mutableStateOf(false) }

                                        if (showFormatDialog) {
                                            TimestampFormatDialog(
                                                initialFormat = exactTimestampFormat,
                                                onDismissRequest = { showFormatDialog = false },
                                                onSaveFormat = { newFormat ->
                                                    showFormatDialog = false
                                                    onSaveExactTimestampFormat?.invoke(newFormat)
                                                }
                                            )
                                        }

                                        val now = remember { Date() }
                                        val locale = remember { Locale.getDefault() }
                                        val currentSample = remember(exactTimestampFormat) {
                                            val sample = TimestampFormat.format(exactTimestampFormat, now, locale)
                                            if (exactTimestampFormat.isEmpty()) {
                                                sample
                                            } else {
                                                "$exactTimestampFormat  ·  $sample"
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
