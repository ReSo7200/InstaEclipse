package ps.reso.instaeclipse.ui.component.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ps.reso.instaeclipse.R
import ps.reso.instaeclipse.ui.theme.AppSpacing
import ps.reso.instaeclipse.ui.theme.MotionDuration
import ps.reso.instaeclipse.ui.theme.MotionEasing

data class QualityOption(
    val value: Int,
    val labelRes: Int
)

val QUALITY_OPTIONS = listOf(
    QualityOption(0, R.string.ig_dialog_quality_auto),
    QualityOption(360, R.string.ig_dialog_quality_360),
    QualityOption(480, R.string.ig_dialog_quality_480),
    QualityOption(720, R.string.ig_dialog_quality_720),
    QualityOption(1080, R.string.ig_dialog_quality_1080),
    QualityOption(Int.MAX_VALUE, R.string.ig_dialog_quality_max)
)

@Composable
fun QualityDropdownRow(
    currentQuality: Int,
    onQualitySelected: (Int) -> Unit,
    showDivider: Boolean,
    title: String = stringResource(R.string.ig_dialog_quality_force_reels),
    categoryBadge: String? = null,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(MotionDuration.Short4, easing = MotionEasing.Standard),
        label = "QualityDropdownArrowRotation"
    )

    val selectedOption = QUALITY_OPTIONS.firstOrNull { it.value == currentQuality }
        ?: QUALITY_OPTIONS.first()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
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
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
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
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { expanded = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(selectedOption.labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = "Select quality",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer(rotationZ = arrowRotation)
                        )
                    }
                }

                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    shape = MaterialTheme.shapes.medium
                ) {
                    QUALITY_OPTIONS.forEach { option ->
                        val isSelected = option.value == currentQuality
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(option.labelRes),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            trailingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            } else null,
                            onClick = {
                                expanded = false
                                onQualitySelected(option.value)
                            }
                        )
                    }
                }
            }
        }

        if (showDivider) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                modifier = Modifier.padding(horizontal = AppSpacing.md)
            )
        }
    }
}
