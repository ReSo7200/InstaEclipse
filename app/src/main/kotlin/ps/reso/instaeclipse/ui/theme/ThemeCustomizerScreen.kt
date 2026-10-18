package ps.reso.instaeclipse.ui.theme

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ps.reso.instaeclipse.R
import ps.reso.instaeclipse.mods.ui.theme.IgThemePalette
import ps.reso.instaeclipse.mods.ui.theme.ThemePreset
import ps.reso.instaeclipse.mods.ui.theme.ThemePresets

data class ColorSlotInfo(
    val key: String,
    val labelRes: Int,
    val description: String
)

data class ColorSlotGroup(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val slots: List<ColorSlotInfo>
)

private val COLOR_SLOT_GROUPS = listOf(
    ColorSlotGroup(
        title = "Surfaces & Backgrounds",
        icon = Icons.Filled.Layers,
        slots = listOf(
            ColorSlotInfo(IgThemePalette.SLOT_BACKGROUND, R.string.theme_slot_background, "Main screen & timeline background"),
            ColorSlotInfo(IgThemePalette.SLOT_SURFACE, R.string.theme_slot_surface, "Cards, sheets, dialogs & headers"),
            ColorSlotInfo(IgThemePalette.SLOT_STATUS_BAR, R.string.theme_slot_status_bar, "System status bar tint"),
            ColorSlotInfo(IgThemePalette.SLOT_NAVIGATION, R.string.theme_slot_navigation, "Bottom navigation bar background")
        )
    ),
    ColorSlotGroup(
        title = "Text & Content",
        icon = Icons.Filled.TextFields,
        slots = listOf(
            ColorSlotInfo(IgThemePalette.SLOT_PRIMARY_TEXT, R.string.theme_slot_primary_text, "Usernames, headings & primary text"),
            ColorSlotInfo(IgThemePalette.SLOT_SECONDARY_TEXT, R.string.theme_slot_secondary_text, "Subtitles, timestamps & hints"),
            ColorSlotInfo(IgThemePalette.SLOT_LINK, R.string.theme_slot_link, "Web URLs, hashtags & @mentions")
        )
    ),
    ColorSlotGroup(
        title = "Brand & Accents",
        icon = Icons.Filled.AutoAwesome,
        slots = listOf(
            ColorSlotInfo(IgThemePalette.SLOT_ACCENT, R.string.theme_slot_accent, "Primary brand accent & highlights"),
            ColorSlotInfo(IgThemePalette.SLOT_BUTTON, R.string.theme_slot_button, "Action, follow & confirm buttons"),
            ColorSlotInfo(IgThemePalette.SLOT_ICON, R.string.theme_slot_icon, "Primary navigation & action icons"),
            ColorSlotInfo(IgThemePalette.SLOT_GLYPH, R.string.theme_slot_glyph, "Indicator glyphs & secondary icons")
        )
    ),
    ColorSlotGroup(
        title = "Dividers & System States",
        icon = Icons.Filled.Warning,
        slots = listOf(
            ColorSlotInfo(IgThemePalette.SLOT_DIVIDER, R.string.theme_slot_divider, "Dividers & separator lines"),
            ColorSlotInfo(IgThemePalette.SLOT_BORDER, R.string.theme_slot_border, "Card outlines & input borders"),
            ColorSlotInfo(IgThemePalette.SLOT_ERROR, R.string.theme_slot_error, "Error warnings & alert banners"),
            ColorSlotInfo(IgThemePalette.SLOT_DESTRUCTIVE, R.string.theme_slot_destructive, "Delete, unsend & block actions")
        )
    )
)

fun formatColorHex(color: Int): String {
    return if (android.graphics.Color.alpha(color) == 255) {
        String.format("#%06X", 0xFFFFFF and color)
    } else {
        String.format("#%08X", color)
    }
}

data class ActiveSlotInfo(
    val key: String,
    val label: String,
    val color: Int
)

fun lightModeLabel(context: Context, value: Int): String {
    return when (value) {
        0 -> context.getString(R.string.theme_light_same)
        -1 -> context.getString(R.string.theme_light_stock)
        else -> ThemePresets.getDisplayName(context, value)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeCustomizerScreen(
    selectedPresetId: Int,
    customMode: Boolean,
    palette: IgThemePalette,
    onBack: () -> Unit,
    onReset: () -> Unit,
    onSelectPreset: (ThemePreset) -> Unit,
    onColorChanged: (slotKey: String, newColor: Int) -> Unit,
    onRevertToPreset: (Int) -> Unit,
    selectedLightPresetId: Int = -1,
    onSelectLightPreset: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(if (customMode) 1 else 0) }
    var showResetDialog by remember { mutableStateOf(false) }
    var showLightModeDialog by remember { mutableStateOf(false) }
    var activeSlotPicker by remember { mutableStateOf<ActiveSlotInfo?>(null) }

    val allPresets = remember { ThemePresets.all() }

    activeSlotPicker?.let { target ->
        MaterialColorPickerDialog(
            title = "Color Picker",
            subtitle = target.label,
            initialColor = target.color,
            onDismissRequest = { activeSlotPicker = null },
            onColorConfirmed = { newColor ->
                activeSlotPicker = null
                onColorChanged(target.key, newColor)
            }
        )
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            icon = {
                Icon(
                    Icons.Filled.RestartAlt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Reset to Default Theme?",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = "This will reset all colors back to the default 'Stock Theme' preset.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetDialog = false
                        onReset()
                    }
                ) {
                    Text("Reset", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showLightModeDialog) {
        val choices = remember { ThemePresets.lightModeChoices() }
        AlertDialog(
            onDismissRequest = { showLightModeDialog = false },
            icon = {
                Icon(
                    Icons.Filled.LightMode,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.theme_light_mode),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    for (choice in choices) {
                        val isSelected = choice == selectedLightPresetId
                        val label = lightModeLabel(context, choice)
                        val preset = if (choice > 0) ThemePresets.getById(choice) else null

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    showLightModeDialog = false
                                    onSelectLightPreset(choice)
                                }
                                .padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = isSelected,
                                onClick = {
                                    showLightModeDialog = false
                                    onSelectLightPreset(choice)
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (preset != null) {
                                Spacer(Modifier.width(8.dp))
                                Row(
                                    modifier = Modifier
                                        .width(52.dp)
                                        .height(18.dp),
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    preset.palette.previewColors().forEach { c ->
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxSize()
                                                .clip(RoundedCornerShape(3.dp))
                                                .background(Color(c))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLightModeDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.theme_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showResetDialog = true }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.RestartAlt,
                            contentDescription = stringResource(R.string.theme_reset_custom),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.primary
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 36.dp)
        ) {

            item {
                PrimaryTabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
                    containerColor = MaterialTheme.colorScheme.surface
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Palette, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Presets (${allPresets.size})", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Custom Colors (15)", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                }
            }

            if (selectedTab == 0) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs)
                    ) {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = AppSpacing.sm)
                                .clickable { showLightModeDialog = true }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(AppSpacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Filled.LightMode,
                                            contentDescription = null,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                }

                                Spacer(Modifier.width(AppSpacing.sm))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = stringResource(R.string.theme_light_mode),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = lightModeLabel(context, selectedLightPresetId),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(Modifier.width(AppSpacing.xs))

                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        allPresets.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(AppSpacing.xs)
                            ) {
                                for (preset in pair) {
                                    val isSelected = !customMode && preset.id == selectedPresetId
                                    PresetCard(
                                        preset = preset,
                                        isSelected = isSelected,
                                        onClick = { onSelectPreset(preset) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (pair.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                            Spacer(Modifier.height(AppSpacing.xs))
                        }
                    }
                }
            } else {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs)
                    ) {

                        COLOR_SLOT_GROUPS.forEach { group ->
                            ColorSlotGroupCard(
                                group = group,
                                palette = palette,
                                onPickColorSlot = { slotKey, color, label ->
                                    activeSlotPicker = ActiveSlotInfo(slotKey, label, color)
                                }
                            )
                            Spacer(Modifier.height(AppSpacing.sm))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PresetCard(
    preset: ThemePreset,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val name = remember(preset.id) { ThemePresets.getDisplayName(context, preset.id) }
    val borderColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        animationSpec = tween(200),
        label = "border"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerLow,
        animationSpec = tween(200),
        label = "container"
    )

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(if (isSelected) 2.dp else 1.dp, borderColor),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppSpacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isSelected) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            val colors = remember(preset.id) {
                listOf(
                    Color(preset.palette.background),
                    Color(preset.palette.surface),
                    Color(preset.palette.accent),
                    Color(preset.palette.primaryText)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                colors.forEach { col ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(6.dp))
                            .background(col)
                            .border(0.5.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun ColorSlotGroupCard(
    group: ColorSlotGroup,
    palette: IgThemePalette,
    onPickColorSlot: (String, Int, String) -> Unit
) {
    val context = LocalContext.current

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
            ) {
                Icon(
                    group.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(AppSpacing.xs))
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))

            group.slots.forEachIndexed { index, slot ->
                val color = palette.get(slot.key)
                val label = stringResource(slot.labelRes)
                val hexString = remember(color) { formatColorHex(color) }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPickColorSlot(slot.key, color, label) }
                        .padding(horizontal = AppSpacing.md, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(color))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    )

                    Spacer(Modifier.width(AppSpacing.sm))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = slot.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.width(AppSpacing.xs))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerHighest
                        ) {
                            Text(
                                text = hexString,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                if (index < group.slots.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        modifier = Modifier.padding(start = 58.dp, end = AppSpacing.md)
                    )
                }
            }
        }
    }
}
