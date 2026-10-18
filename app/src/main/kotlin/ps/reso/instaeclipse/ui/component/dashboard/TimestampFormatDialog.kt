package ps.reso.instaeclipse.ui.component.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.reso.instaeclipse.R
import ps.reso.instaeclipse.utils.text.TimestampFormat
import java.util.Date
import java.util.Locale

@Composable
fun TimestampFormatDialog(
    initialFormat: String,
    onDismissRequest: () -> Unit,
    onSaveFormat: (String) -> Unit
) {
    val presets = remember { TimestampFormat.PRESETS }
    val isCustomInitial = remember(initialFormat) {
        initialFormat.isNotEmpty() && !presets.contains(initialFormat)
    }

    var selectedIndex by remember {
        mutableIntStateOf(
            if (isCustomInitial) {
                presets.size
            } else {
                val idx = presets.indexOf(initialFormat)
                if (idx >= 0) idx else 0
            }
        )
    }

    var customPattern by remember {
        mutableStateOf(
            if (isCustomInitial) initialFormat else "yyyy-MM-dd HH:mm:ss"
        )
    }

    val now = remember { Date() }
    val locale = remember { Locale.getDefault() }

    val isCustomSelected = selectedIndex == presets.size
    val isCustomValid = remember(customPattern) {
        TimestampFormat.isValid(customPattern.trim()) && customPattern.trim().isNotEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(
                text = stringResource(R.string.ig_timestamp_format),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                presets.forEachIndexed { index, preset ->
                    val sample = remember(preset) {
                        TimestampFormat.format(preset, now, locale)
                    }
                    val label = if (preset.isEmpty()) {
                        "${stringResource(R.string.ig_timestamp_format_default)}  ·  $sample"
                    } else {
                        "$preset  ·  $sample"
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedIndex = index }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedIndex == index,
                            onClick = { selectedIndex = index }
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (selectedIndex == index) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedIndex = presets.size }
                        .padding(vertical = 6.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isCustomSelected,
                        onClick = { selectedIndex = presets.size }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.ig_timestamp_format_custom),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isCustomSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isCustomSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                AnimatedVisibility(
                    visible = isCustomSelected,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp, bottom = 8.dp)
                    ) {
                        OutlinedTextField(
                            value = customPattern,
                            onValueChange = { customPattern = it },
                            label = { Text(stringResource(R.string.ig_timestamp_format)) },
                            placeholder = { Text("yyyy-MM-dd HH:mm:ss") },
                            isError = !isCustomValid,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(6.dp))

                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isCustomValid) {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                } else {
                                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                                }
                            ),
                            shape = MaterialTheme.shapes.small,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = if (isCustomValid) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline,
                                    contentDescription = null,
                                    tint = if (isCustomValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 1.dp)
                                )
                                Text(
                                    text = if (isCustomValid) {
                                        TimestampFormat.format(customPattern.trim(), now, locale)
                                    } else {
                                        stringResource(R.string.ig_timestamp_format_invalid)
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isCustomValid) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))

                        Text(
                            text = stringResource(R.string.ig_timestamp_format_custom_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalFormat = if (isCustomSelected) {
                        customPattern.trim()
                    } else {
                        presets[selectedIndex]
                    }
                    onSaveFormat(finalFormat)
                },
                enabled = !isCustomSelected || isCustomValid
            ) {
                Text(stringResource(android.R.string.ok), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(stringResource(R.string.ig_dialog_cancel))
            }
        }
    )
}
