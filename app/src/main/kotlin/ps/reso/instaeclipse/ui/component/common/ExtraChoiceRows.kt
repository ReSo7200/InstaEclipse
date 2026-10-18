package ps.reso.instaeclipse.ui.component.common

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ps.reso.instaeclipse.R
import ps.reso.instaeclipse.core.HookCategory
import ps.reso.instaeclipse.core.PreferencesManager
import ps.reso.instaeclipse.mods.misc.DeviceSpoofHook
import ps.reso.instaeclipse.mods.ui.StoryTrayHook
import ps.reso.instaeclipse.ui.theme.AppSpacing
import ps.reso.instaeclipse.utils.dialog.DialogUtils
import ps.reso.instaeclipse.utils.feature.FeatureFlags

/**
 * Settings that are a choice rather than a switch, shown at the bottom of their category card:
 * the download quality cap, the story tray size and ring color (#283) and the device spoof.
 * Saved through PreferencesManager, which also tells a running Instagram.
 */
@Composable
fun ExtraChoiceRows(category: HookCategory) {
    when (category) {
        HookCategory.DOWNLOADER -> {
            val sizes = listOf(0, 2160, 1440, 1080, 720, 480)
            IntChoiceRow("downloaderQualityMaxShortSide", stringResource(R.string.ie_download_quality), 0, sizes) {
                if (it == 0) stringResource(R.string.ie_download_best) else stringResource(R.string.ie_download_ceiling, it)
            }
        }
        HookCategory.STORIES -> {
            IntChoiceRow("storyTrayScale", stringResource(R.string.ig_story_tray_size), 100,
                DialogUtils.STORY_TRAY_SIZES.toList()) { "$it%" }
            StoryRingColorRow()
        }
        HookCategory.DEV_OPTIONS -> {
            val ids = DeviceSpoofHook.DEVICES.toList()
            StringChoiceRow("spoofDevice", stringResource(R.string.ig_spoof_device), ids,
                note = stringResource(R.string.ig_spoof_device_note)) {
                DeviceSpoofHook.deviceName(it) ?: stringResource(R.string.ig_dialog_extras_share_domain_off)
            }
        }
        else -> Unit
    }
}

@Composable
private fun ChoiceRowShell(title: String, value: @Composable () -> Unit, onClick: () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        value()
    }
}

@Composable
private fun ValueText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun IntChoiceRow(key: String, title: String, default: Int, values: List<Int>, label: @Composable (Int) -> String) {
    val ctx = LocalContext.current
    var current by remember { mutableIntStateOf(readInt(ctx, key, default)) }
    var open by remember { mutableStateOf(false) }
    ChoiceRowShell(title, { ValueText(label(current)) }) { open = true }
    if (open) {
        ChoiceDialog(title, values.map { v -> v to label(v) }, current, onDismiss = { open = false }) { v ->
            open = false
            current = v
            PreferencesManager.setInt(ctx, key, v)
            when (key) {
                "downloaderQualityMaxShortSide" -> FeatureFlags.downloaderQualityMaxShortSide = v
                "storyTrayScale" -> FeatureFlags.storyTrayScale = v
            }
        }
    }
}

@Composable
private fun StringChoiceRow(key: String, title: String, values: List<String>, note: String? = null,
                            label: @Composable (String) -> String) {
    val ctx = LocalContext.current
    var current by remember { mutableStateOf(PreferencesManager.getString(ctx, key, "")) }
    var open by remember { mutableStateOf(false) }
    ChoiceRowShell(title, { ValueText(label(current)) }) { open = true }
    if (open) {
        ChoiceDialog(title, values.map { v -> v to label(v) }, current, note = note, onDismiss = { open = false }) { v ->
            open = false
            current = v
            PreferencesManager.setString(ctx, key, v)
            if (key == "spoofDevice") FeatureFlags.spoofDevice = v
            Toast.makeText(ctx, R.string.ig_toast_restart_manual, Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
private fun <T> ChoiceDialog(title: String, options: List<Pair<T, String>>, selected: T, note: String? = null,
                             swatch: (@Composable (T) -> Unit)? = null,
                             onDismiss: () -> Unit, onPick: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (note != null) {
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(AppSpacing.sm))
                }
                options.forEach { (value, text) ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(value) }
                            .padding(vertical = 2.dp)
                    ) {
                        RadioButton(selected = value == selected, onClick = { onPick(value) })
                        if (swatch != null) { swatch(value); Spacer(Modifier.width(AppSpacing.xs)) }
                        Text(text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

@Composable
private fun Swatches(value: String) {
    val colors = StoryTrayHook.parseColors(value) ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        colors.forEach { c -> Box(Modifier.size(16.dp).background(Color(c), CircleShape)) }
    }
}

/** Unseen story ring color (#283): Instagram's, a preset, or a custom color / two-color gradient. */
@Composable
private fun StoryRingColorRow() {
    val ctx = LocalContext.current
    val title = stringResource(R.string.ig_story_ring_color)
    val defaultLabel = stringResource(R.string.ig_story_ring_default)
    val customLabel = stringResource(R.string.ig_story_ring_custom)
    var current by remember { mutableStateOf(PreferencesManager.getString(ctx, "storyRingColor", "")) }
    var open by remember { mutableStateOf(false) }
    var custom by remember { mutableStateOf(false) }
    fun save(v: String) {
        current = v
        PreferencesManager.setString(ctx, "storyRingColor", v)
        FeatureFlags.storyRingColor = v
        Toast.makeText(ctx, R.string.ig_toast_restart_manual, Toast.LENGTH_SHORT).show()
    }
    ChoiceRowShell(title, {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (current.isEmpty()) ValueText(defaultLabel)
            else { Swatches(current); Spacer(Modifier.width(AppSpacing.xs)); ValueText(current.replace(",", " → ")) }
        }
    }) { open = true }
    if (open) {
        val presets = DialogUtils.STORY_RING_PRESETS.toList()
        val options = listOf("" to defaultLabel) + presets.map { it to it.replace(",", " → ") } + listOf(CUSTOM to customLabel)
        val selected = when {
            current.isEmpty() -> ""
            presets.any { it.equals(current, ignoreCase = true) } -> presets.first { it.equals(current, ignoreCase = true) }
            else -> CUSTOM
        }
        ChoiceDialog(title, options, selected, swatch = { v -> if (v != CUSTOM) Swatches(v) },
            onDismiss = { open = false }) { v ->
            open = false
            if (v == CUSTOM) custom = true else save(v)
        }
    }
    if (custom) {
        var text by remember { mutableStateOf(current) }
        val invalid = stringResource(R.string.ig_story_ring_invalid)
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text(customLabel) },
            text = {
                Column {
                    Text(stringResource(R.string.ig_story_ring_custom_hint), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.size(AppSpacing.sm))
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                        placeholder = { Text("#3897F0") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = text.trim().replace(" ", "")
                    if (StoryTrayHook.parseColors(v) == null) {
                        Toast.makeText(ctx, invalid, Toast.LENGTH_SHORT).show()
                    } else {
                        custom = false
                        save(v.split(",").joinToString(",") { if (it.startsWith("#")) it else "#$it" }.uppercase())
                    }
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { custom = false }) { Text(stringResource(android.R.string.cancel)) } }
        )
    }
}

private const val CUSTOM = "custom"

private fun readInt(ctx: Context, key: String, default: Int): Int =
    try { PreferencesManager.getPrefs(ctx).getInt(key, default) } catch (t: Throwable) { default }
