package ps.reso.instaeclipse.ui.component.logs

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ps.reso.instaeclipse.R
import ps.reso.instaeclipse.ui.theme.AppSpacing
import ps.reso.instaeclipse.ui.theme.MotionDuration
import ps.reso.instaeclipse.ui.theme.MotionEasing
import ps.reso.instaeclipse.ui.theme.m3ContentSizeSpringSpec
import ps.reso.instaeclipse.ui.theme.m3ItemPlacementSpringSpec
import ps.reso.instaeclipse.ui.viewmodel.LogEntry
import ps.reso.instaeclipse.ui.viewmodel.LogFilter
import ps.reso.instaeclipse.ui.viewmodel.LogSeverity
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LogsScreen(
    logEntries: List<LogEntry>,
    allCount: Int,
    hookedCount: Int,
    warningsCount: Int,
    errorsCount: Int,
    selectedFilter: LogFilter,
    searchQuery: String,
    isLoading: Boolean,
    onFilterSelected: (LogFilter) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onRefresh: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onCopyEntry: (LogEntry) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(bottom = AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
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
                    .animateContentSize(animationSpec = m3ContentSizeSpringSpec)
                    .padding(AppSpacing.md),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    IconButton(onClick = onRefresh, enabled = !isLoading) {
                        if (isLoading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_logs_refresh),
                                contentDescription = stringResource(R.string.logs_refresh),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    IconButton(onClick = onShare, enabled = allCount > 0) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_logs_share),
                            contentDescription = stringResource(R.string.logs_share),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onCopy, enabled = allCount > 0) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.logs_copy),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onClear, enabled = allCount > 0) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = stringResource(R.string.logs_clear),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                ) {
                    FilterChip(
                        selected = selectedFilter == LogFilter.ALL,
                        onClick = { onFilterSelected(LogFilter.ALL) },
                        label = {
                            Text(stringResource(R.string.logs_filter_count, stringResource(R.string.logs_filter_all), allCount))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )
                    FilterChip(
                        selected = selectedFilter == LogFilter.HOOKED,
                        onClick = { onFilterSelected(LogFilter.HOOKED) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFF81C784)
                            )
                        },
                        label = {
                            Text(stringResource(R.string.logs_filter_count, stringResource(R.string.logs_filter_hooked), hookedCount))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF2E7D32).copy(alpha = 0.2f),
                            selectedLabelColor = Color(0xFF81C784)
                        )
                    )
                    FilterChip(
                        selected = selectedFilter == LogFilter.WARNINGS,
                        onClick = { onFilterSelected(LogFilter.WARNINGS) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = Color(0xFFFFB74D)
                            )
                        },
                        label = {
                            Text(stringResource(R.string.logs_filter_count, stringResource(R.string.logs_filter_warnings), warningsCount))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFFB74D).copy(alpha = 0.2f),
                            selectedLabelColor = Color(0xFFFFB74D)
                        )
                    )
                    FilterChip(
                        selected = selectedFilter == LogFilter.ERRORS,
                        onClick = { onFilterSelected(LogFilter.ERRORS) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Error,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                        },
                        label = {
                            Text(stringResource(R.string.logs_filter_count, stringResource(R.string.logs_filter_errors), errorsCount))
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    )
                }

                TextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Filter logs…") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { onSearchQueryChange("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.logs_count_format, logEntries.size, allCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                contentColor = MaterialTheme.colorScheme.onSurface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            if (logEntries.isEmpty()) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(AppSpacing.xl)
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppSpacing.sm)
                    ) {
                        val isFiltered = allCount > 0
                        Icon(
                            imageVector = if (isFiltered) Icons.Outlined.Search else Icons.Outlined.Terminal,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Text(
                            text = stringResource(if (isFiltered) R.string.logs_empty_filter_title else R.string.logs_empty_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (isFiltered) {
                            Text(
                                text = stringResource(R.string.logs_empty_filter_body),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (isFiltered) {
                            FilledTonalButton(
                                onClick = {
                                    onFilterSelected(LogFilter.ALL)
                                    onSearchQueryChange("")
                                }
                            ) {
                                Text("Show All Entries")
                            }
                        } else {
                            FilledTonalButton(onClick = onRefresh) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.logs_refresh))
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
                ) {
                    items(
                        items = logEntries,
                        key = { it.id }
                    ) { entry ->
                        val entryColor = when (entry.severity) {
                            LogSeverity.ERROR -> MaterialTheme.colorScheme.error
                            LogSeverity.WARNING -> Color(0xFFFFB74D)
                            LogSeverity.HOOKED -> Color(0xFF81C784)
                            LogSeverity.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                        }

                        SelectionContainer(
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(MotionDuration.Medium1, easing = MotionEasing.EmphasizedDecelerate),
                                fadeOutSpec = tween(MotionDuration.Short3, easing = MotionEasing.EmphasizedAccelerate),
                                placementSpec = m3ItemPlacementSpringSpec
                            )
                        ) {
                            Text(
                                text = entry.display,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                lineHeight = 16.sp,
                                color = entryColor,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {},
                                        onLongClick = { onCopyEntry(entry) }
                                    )
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
