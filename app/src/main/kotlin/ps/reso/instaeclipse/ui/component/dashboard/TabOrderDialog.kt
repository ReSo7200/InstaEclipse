package ps.reso.instaeclipse.ui.component.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Reorder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import ps.reso.instaeclipse.R
import ps.reso.instaeclipse.mods.ui.DistractionFreeUIHook
import ps.reso.instaeclipse.ui.theme.m3ItemPlacementSpringSpec
import java.util.Collections

@Composable
fun TabOrderDialog(
    initialOrder: String,
    onDismissRequest: () -> Unit,
    onSaveOrder: (String) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    val tabLabels = mapOf(
        "feed_tab" to stringResource(R.string.ig_nav_name_home),
        "clips_tab" to stringResource(R.string.ig_nav_name_reels),
        "search_tab" to stringResource(R.string.ig_nav_name_search),
        "creation_tab" to stringResource(R.string.ig_nav_name_create),
        "direct_tab" to stringResource(R.string.ig_nav_name_messages),
        "news_tab" to stringResource(R.string.ig_nav_name_activity),
        "profile_tab" to stringResource(R.string.ig_nav_name_profile)
    )

    val tabIcons = mapOf(
        "feed_tab" to Icons.Filled.Home,
        "clips_tab" to Icons.Filled.PlayCircle,
        "search_tab" to Icons.Filled.Search,
        "creation_tab" to Icons.Filled.AddBox,
        "direct_tab" to Icons.AutoMirrored.Filled.Send,
        "news_tab" to Icons.Filled.Favorite,
        "profile_tab" to Icons.Filled.AccountCircle
    )

    val currentList = remember(initialOrder) {
        val list = mutableListOf<String>()
        val defaultTabs = DistractionFreeUIHook.ORDERABLE_TABS.toList()
        if (initialOrder.isNotBlank()) {
            for (tab in initialOrder.split(",")) {
                val trimmed = tab.trim()
                if (defaultTabs.contains(trimmed) && !list.contains(trimmed)) {
                    list.add(trimmed)
                }
            }
        }
        for (tab in defaultTabs) {
            if (!list.contains(tab)) list.add(tab)
        }
        mutableStateListOf<String>().apply { addAll(list) }
    }

    var draggingKey by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var itemHeightPx by remember { mutableFloatStateOf(0f) }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = {
            Icon(
                imageVector = Icons.Filled.Reorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.ig_nav_tab_order),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.ig_nav_tab_order_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(4.dp))

                LazyColumn(
                    userScrollEnabled = draggingKey == null,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 440.dp)
                ) {
                    items(
                        items = currentList,
                        key = { it }
                    ) { tabId ->
                        val index = currentList.indexOf(tabId)
                        val name = tabLabels[tabId] ?: tabId
                        val icon = tabIcons[tabId] ?: Icons.Filled.Home
                        val isDragging = tabId == draggingKey

                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isDragging) {
                                    MaterialTheme.colorScheme.surfaceContainerHighest
                                } else {
                                    MaterialTheme.colorScheme.surfaceContainerLow
                                }
                            ),
                            border = if (isDragging) {
                                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                            } else {
                                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                            },
                            elevation = CardDefaults.cardElevation(
                                defaultElevation = if (isDragging) 8.dp else 0.dp
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(placementSpec = m3ItemPlacementSpringSpec)
                                .zIndex(if (isDragging) 10f else 1f)
                                .graphicsLayer {
                                    translationY = if (isDragging) dragOffsetY else 0f
                                    scaleX = if (isDragging) 1.03f else 1f
                                    scaleY = if (isDragging) 1.03f else 1f
                                }
                                .onGloballyPositioned { coordinates ->
                                    if (!isDragging && coordinates.size.height > 0) {
                                        itemHeightPx = coordinates.size.height.toFloat() + with(density) { 8.dp.toPx() }
                                    }
                                }
                                .pointerInput(tabId) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggingKey = tabId
                                            dragOffsetY = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragOffsetY += dragAmount.y
                                            val h = if (itemHeightPx > 0f) itemHeightPx else 160f
                                            val threshold = h * 0.5f
                                            var currentIndex = currentList.indexOf(tabId)
                                            while (currentIndex != -1 && currentIndex < currentList.lastIndex && dragOffsetY > threshold) {
                                                Collections.swap(currentList, currentIndex, currentIndex + 1)
                                                dragOffsetY -= h
                                                currentIndex++
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                            while (currentIndex != -1 && currentIndex > 0 && dragOffsetY < -threshold) {
                                                Collections.swap(currentList, currentIndex, currentIndex - 1)
                                                dragOffsetY += h
                                                currentIndex--
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        },
                                        onDragEnd = {
                                            draggingKey = null
                                            dragOffsetY = 0f
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        },
                                        onDragCancel = {
                                            draggingKey = null
                                            dragOffsetY = 0f
                                        }
                                    )
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isDragging) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.surfaceContainerHighest
                                    },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isDragging) {
                                                MaterialTheme.colorScheme.onPrimary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }

                                Spacer(Modifier.width(10.dp))

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                    modifier = Modifier.size(34.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }

                                Spacer(Modifier.width(12.dp))

                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isDragging) FontWeight.Bold else FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )

                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilledTonalIconButton(
                                        onClick = {
                                            if (index > 0) {
                                                Collections.swap(currentList, index, index - 1)
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        },
                                        enabled = index > 0,
                                        modifier = Modifier.size(32.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.ArrowUpward,
                                            contentDescription = "Move Up",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }

                                    FilledTonalIconButton(
                                        onClick = {
                                            if (index < currentList.size - 1) {
                                                Collections.swap(currentList, index, index + 1)
                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            }
                                        },
                                        enabled = index < currentList.size - 1,
                                        modifier = Modifier.size(32.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest
                                        )
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.ArrowDownward,
                                            contentDescription = "Move Down",
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Spacer(Modifier.width(6.dp))

                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            if (isDragging) {
                                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)
                                            } else {
                                                MaterialTheme.colorScheme.surfaceContainerHigh
                                            }
                                        )
                                        .pointerInput(tabId) {
                                            detectDragGestures(
                                                onDragStart = {
                                                    draggingKey = tabId
                                                    dragOffsetY = 0f
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                },
                                                onDrag = { change, dragAmount ->
                                                    change.consume()
                                                    dragOffsetY += dragAmount.y
                                                    val h = if (itemHeightPx > 0f) itemHeightPx else 160f
                                                    val threshold = h * 0.5f
                                                    var currentIndex = currentList.indexOf(tabId)
                                                    while (currentIndex != -1 && currentIndex < currentList.lastIndex && dragOffsetY > threshold) {
                                                        Collections.swap(currentList, currentIndex, currentIndex + 1)
                                                        dragOffsetY -= h
                                                        currentIndex++
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                    while (currentIndex != -1 && currentIndex > 0 && dragOffsetY < -threshold) {
                                                        Collections.swap(currentList, currentIndex, currentIndex - 1)
                                                        dragOffsetY += h
                                                        currentIndex--
                                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                    }
                                                },
                                                onDragEnd = {
                                                    draggingKey = null
                                                    dragOffsetY = 0f
                                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                },
                                                onDragCancel = {
                                                    draggingKey = null
                                                    dragOffsetY = 0f
                                                }
                                            )
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.DragHandle,
                                        contentDescription = "Drag to reorder",
                                        tint = if (isDragging) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val saved = currentList.joinToString(",")
                    onSaveOrder(saved)
                }
            ) {
                Text(stringResource(android.R.string.ok), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        onSaveOrder("")
                    }
                ) {
                    Text(
                        stringResource(R.string.ig_nav_tab_order_reset),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismissRequest) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        }
    )
}
