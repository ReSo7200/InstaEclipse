package ps.reso.instaeclipse.ui.component.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ps.reso.instaeclipse.ui.theme.AppSpacing
import ps.reso.instaeclipse.ui.theme.MotionDuration
import ps.reso.instaeclipse.ui.theme.MotionEasing
import ps.reso.instaeclipse.ui.theme.m3ContentSizeSpringSpec

@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    categoryBadge: String? = null,
    content: (@Composable () -> Unit)? = null
) {
    val contentAlpha = if (enabled) 1f else 0.38f

    val targetTitleColor = (if (checked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = contentAlpha)
    val animatedTitleColor by animateColorAsState(
        targetValue = targetTitleColor,
        animationSpec = tween(MotionDuration.Short4, easing = MotionEasing.Standard),
        label = "SwitchRowTitleColor"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = m3ContentSizeSpringSpec)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange
                )
                .padding(horizontal = AppSpacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = AppSpacing.md)
            ) {
                if (categoryBadge != null) {
                    Surface(
                        shape = MaterialTheme.shapes.extraSmall,
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = contentAlpha),
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = contentAlpha)
                    ) {
                        Text(
                            text = categoryBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = animatedTitleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = null,
                thumbContent = if (checked) {
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
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    disabledCheckedThumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    disabledCheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.38f),
                    disabledCheckedIconColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.38f),
                    disabledUncheckedThumbColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
                    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.38f)
                )
            )
        }

        if (content != null) {
            content()
        }

        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                modifier = Modifier.padding(horizontal = AppSpacing.md)
            )
        }
    }
}
