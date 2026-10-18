package ps.reso.instaeclipse.ui.theme

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ps.reso.instaeclipse.mods.ui.theme.IgThemePalette
import ps.reso.instaeclipse.mods.ui.theme.ThemePreset
import ps.reso.instaeclipse.mods.ui.theme.ThemePresets
import ps.reso.instaeclipse.mods.ui.theme.ThemeSettingsHelper
import java.io.File

class ThemeCustomizerActivity : ComponentActivity() {

    companion object {
        private const val CACHE_NAME = "instaeclipse_cache"
        private const val KEY_ENABLED = "customThemeEnabled"
        private const val KEY_PRESET_ID = "themePresetId"
        private const val KEY_LIGHT_PRESET_ID = "themeLightPresetId"
        private const val KEY_PALETTE_JSON = "themePaletteJson"
    }

    private var customThemeEnabled by mutableStateOf(false)
    private var selectedPresetId by mutableIntStateOf(1)
    private var selectedLightPresetId by mutableIntStateOf(-1)
    private var customMode by mutableStateOf(false)
    private var workingPalette by mutableStateOf(ThemePresets.getById(1).palette.copy())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        reloadPaletteState()

        setContent {
            AppTheme {
                ThemeCustomizerScreen(
                    selectedPresetId = selectedPresetId,
                    customMode = customMode,
                    palette = activePalette(),
                    onBack = { finish() },
                    onReset = { resetToDefault() },
                    onSelectPreset = { preset ->
                        selectPreset(preset)
                    },
                    onColorChanged = { slotKey, color ->
                        onColorPicked(slotKey, color)
                    },
                    onRevertToPreset = { presetId ->
                        revertToPreset(presetId)
                    },
                    selectedLightPresetId = selectedLightPresetId,
                    onSelectLightPreset = { lightId ->
                        selectLightPresetId(lightId)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reloadPaletteState()
    }

    private fun cache(): SharedPreferences {
        return getSharedPreferences(CACHE_NAME, Context.MODE_PRIVATE)
    }

    private fun reloadPaletteState() {
        val sp = cache()
        selectedPresetId = sp.getInt(KEY_PRESET_ID, 1)
        selectedLightPresetId = sp.getInt(KEY_LIGHT_PRESET_ID, -1)
        val paletteJson = sp.getString(KEY_PALETTE_JSON, "") ?: ""
        customThemeEnabled = sp.getBoolean(KEY_ENABLED, false)
        customMode = ThemeSettingsHelper.isCustomMode(selectedPresetId)
        workingPalette = ThemeSettingsHelper.resolveEffectivePalette(selectedPresetId, paletteJson)
    }

    private fun selectLightPresetId(id: Int) {
        selectedLightPresetId = id
        ps.reso.instaeclipse.utils.feature.FeatureFlags.themeLightPresetId = id
        val editor = cache().edit()
        editor.putInt(KEY_LIGHT_PRESET_ID, id)
        editor.commit()
        makeCacheWorldReadable()

        val presetIntent = Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT")
        presetIntent.putExtra("key", KEY_LIGHT_PRESET_ID)
        presetIntent.putExtra("value", id)
        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(this, presetIntent)
        sendBroadcast(presetIntent)
    }

    private fun activePalette(): IgThemePalette {
        return if (!customMode && selectedPresetId > 0) {
            ThemePresets.getById(selectedPresetId).palette
        } else {
            workingPalette
        }
    }

    private fun selectPreset(preset: ThemePreset) {
        selectedPresetId = preset.id
        customMode = false
        workingPalette = preset.palette.copy()
        if (!customThemeEnabled) customThemeEnabled = true
        persist()
    }

    private fun resetToDefault() {
        selectedPresetId = 1
        customMode = false
        workingPalette = ThemePresets.getById(1).palette.copy()
        persist()
    }

    private fun revertToPreset(presetId: Int) {
        val baseId = if (presetId > 0) presetId else 1
        selectPreset(ThemePresets.getById(baseId))
    }

    private fun onColorPicked(key: String, color: Int) {
        if (!customMode && selectedPresetId > 0) {
            workingPalette = ThemePresets.getById(selectedPresetId).palette.copy()
        }
        workingPalette.set(key, color)
        workingPalette = workingPalette.copy()
        customMode = true
        selectedPresetId = 0
        if (!customThemeEnabled) customThemeEnabled = true
        persist()
    }

    private fun persist() {
        val presetId = if (customMode) 0 else selectedPresetId
        val paletteJson = workingPalette.copy().toJson()

        val editor = cache().edit()
        editor.putBoolean(KEY_ENABLED, customThemeEnabled)
        editor.putInt(KEY_PRESET_ID, presetId)
        editor.putString(KEY_PALETTE_JSON, paletteJson)
        editor.commit()
        makeCacheWorldReadable()

        val enabledIntent = Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF")
        enabledIntent.putExtra("key", KEY_ENABLED)
        enabledIntent.putExtra("value", customThemeEnabled)
        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(this, enabledIntent)
        sendBroadcast(enabledIntent)

        val presetIntent = Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT")
        presetIntent.putExtra("key", KEY_PRESET_ID)
        presetIntent.putExtra("value", presetId)
        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(this, presetIntent)
        sendBroadcast(presetIntent)

        val paletteIntent = Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING")
        paletteIntent.putExtra("key", KEY_PALETTE_JSON)
        paletteIntent.putExtra("value", paletteJson)
        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(this, paletteIntent)
        sendBroadcast(paletteIntent)
    }

    private fun makeCacheWorldReadable() {
        try {
            val file = File(applicationInfo.dataDir + "/shared_prefs/" + CACHE_NAME + ".xml")
            file.setReadable(true, false)
        } catch (_: Throwable) {}
    }
}
