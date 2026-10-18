package ps.reso.instaeclipse.ui.component.dashboard

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ps.reso.instaeclipse.core.Hook
import ps.reso.instaeclipse.ui.theme.AppSpacing
import ps.reso.instaeclipse.ui.viewmodel.GHOST_MASTER_SHORTCUT_ID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddShortcutsDialog(
    allHooks: List<Hook>,
    selectedShortcutIds: List<String>,
    onDismiss: () -> Unit,
    onToggleShortcut: (String) -> Unit,
    showAppControls: Boolean,
    onToggleAppControls: (Boolean) -> Unit
) {
    var dialogSearchQuery by remember { mutableStateOf("") }

    val filteredHooks = remember(allHooks, dialogSearchQuery) {
        val selectable = allHooks.filter { it.id != "forceReelQuality" }
        if (dialogSearchQuery.isBlank()) {
            selectable
        } else {
            selectable.filter {
                it.name.contains(dialogSearchQuery, ignoreCase = true) ||
                    it.category.title.contains(dialogSearchQuery, ignoreCase = true)
            }
        }
    }

    val showGhostMasterRow = dialogSearchQuery.isBlank() ||
        "Ghost Mode Master".contains(dialogSearchQuery, ignoreCase = true) ||
        "ghost".contains(dialogSearchQuery, ignoreCase = true)

    val showAppControlsRow = dialogSearchQuery.isBlank() ||
        "Quick App Controls".contains(dialogSearchQuery, ignoreCase = true) ||
        "force stop".contains(dialogSearchQuery, ignoreCase = true) ||
        "open instagram".contains(dialogSearchQuery, ignoreCase = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
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
                    text = "Customize Quick Shortcuts",
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
                text = "Select which features appear on the quick shortcuts bar.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = AppSpacing.sm)
            )

            TextField(
                value = dialogSearchQuery,
                onValueChange = { dialogSearchQuery = it },
                placeholder = { Text("Search shortcuts…") },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (dialogSearchQuery.isNotEmpty()) {
                        IconButton(onClick = { dialogSearchQuery = "" }) {
                            Icon(imageVector = Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = AppSpacing.sm)
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
                if (showGhostMasterRow) {
                    item {
                        val isSelected = selectedShortcutIds.contains(GHOST_MASTER_SHORTCUT_ID)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleShortcut(GHOST_MASTER_SHORTCUT_ID) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onToggleShortcut(GHOST_MASTER_SHORTCUT_ID) },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Ghost Mode Master",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                if (showAppControlsRow) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onToggleAppControls(!showAppControls) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = showAppControls,
                                onCheckedChange = { onToggleAppControls(it) },
                                colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Force Stop / Open Instagram Bar",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (showAppControls) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                items(filteredHooks.size) { idx ->
                    val hook = filteredHooks[idx]
                    val isSelected = selectedShortcutIds.contains(hook.id)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleShortcut(hook.id) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onToggleShortcut(hook.id) },
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary)
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
