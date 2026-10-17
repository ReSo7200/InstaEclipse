package ps.reso.instaeclipse.ui.theme;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;
import java.util.List;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.theme.IgThemePalette;
import ps.reso.instaeclipse.mods.ui.theme.ThemePreset;
import ps.reso.instaeclipse.mods.ui.theme.ThemePresets;
import ps.reso.instaeclipse.mods.ui.theme.ThemeSettingsHelper;

public class ThemeCustomizerActivity extends AppCompatActivity implements AdvancedColorPickerDialog.Listener {

    private static final String CACHE_NAME = "instaeclipse_cache";
    private static final String KEY_ENABLED = "customThemeEnabled";
    private static final String KEY_PRESET_ID = "themePresetId";
    private static final String KEY_PALETTE_JSON = "themePaletteJson";
    private static final String KEY_LIGHT_PRESET_ID = "themeLightPresetId";

    private static final int[] SLOT_LABELS = {
            R.string.theme_slot_background, R.string.theme_slot_surface, R.string.theme_slot_primary_text,
            R.string.theme_slot_secondary_text, R.string.theme_slot_accent, R.string.theme_slot_button,
            R.string.theme_slot_icon, R.string.theme_slot_glyph, R.string.theme_slot_divider,
            R.string.theme_slot_border, R.string.theme_slot_status_bar, R.string.theme_slot_navigation,
            R.string.theme_slot_link, R.string.theme_slot_error, R.string.theme_slot_destructive
    };
    private static final String STATE_PRESETS_EXPANDED = "presets_expanded";
    private static final String STATE_CUSTOM_EXPANDED = "custom_expanded";

    private MaterialSwitch enableSwitch;
    private View presetsContent;
    private View customContent;
    private ImageView presetsExpandIcon;
    private ImageView customExpandIcon;
    private PresetAdapter presetAdapter;
    private ColorSlotAdapter slotAdapter;
    private IgThemePalette workingPalette;
    private String pendingSlotKey;
    private boolean customMode;
    private boolean presetsExpanded = true;
    private boolean customExpanded = true;
    private int selectedPresetId = 1;
    // The gallery follows the phone's mode: in dark mode it lists and saves the dark theme
    // (themePresetId), in light mode the light theme (themeLightPresetId). The activity is
    // recreated when the mode changes, so this is re-read every time.
    private boolean lightMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_theme_customizer);

        MaterialToolbar toolbar = findViewById(R.id.theme_toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        lightMode = (getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                != android.content.res.Configuration.UI_MODE_NIGHT_YES;
        toolbar.setSubtitle(lightMode ? R.string.theme_set_light : R.string.theme_set_dark);

        enableSwitch = findViewById(R.id.theme_enable_switch);
        presetsContent = findViewById(R.id.theme_presets_content);
        customContent = findViewById(R.id.theme_custom_content);
        presetsExpandIcon = findViewById(R.id.theme_presets_expand_icon);
        customExpandIcon = findViewById(R.id.theme_custom_expand_icon);
        RecyclerView presetList = findViewById(R.id.theme_preset_list);
        RecyclerView colorSlots = findViewById(R.id.theme_color_slots);
        MaterialButton resetButton = findViewById(R.id.theme_reset_custom);

        if (savedInstanceState != null) {
            presetsExpanded = savedInstanceState.getBoolean(STATE_PRESETS_EXPANDED, true);
            customExpanded = savedInstanceState.getBoolean(STATE_CUSTOM_EXPANDED, true);
        }
        setupCollapsibleSection(findViewById(R.id.theme_presets_header), presetsContent, presetsExpandIcon, presetsExpanded);
        setupCollapsibleSection(findViewById(R.id.theme_custom_header), customContent, customExpandIcon, customExpanded);

        reloadPaletteState();
        boolean enabled = cache().getBoolean(KEY_ENABLED, false);
        enableSwitch.setChecked(enabled);
        enableSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> persist());

        List<ThemePreset> modePresets = new java.util.ArrayList<>();
        for (ThemePreset p : ThemePresets.all())
            if (ThemePresets.isLightPreset(p.id) == lightMode) modePresets.add(p);
        presetAdapter = new PresetAdapter(modePresets);
        presetList.setLayoutManager(new GridLayoutManager(this, 2));
        presetList.setAdapter(presetAdapter);

        slotAdapter = new ColorSlotAdapter();
        colorSlots.setLayoutManager(new LinearLayoutManager(this));
        colorSlots.setAdapter(slotAdapter);

        if (lightMode) {
            // Custom colors edit the dark theme's palette; the light theme is chosen from the set.
            findViewById(R.id.theme_custom_header).setVisibility(View.GONE);
            customContent.setVisibility(View.GONE);
        }

        resetButton.setOnClickListener(v -> {
            workingPalette = ThemePresets.getById(1).palette.copy();
            customMode = true;
            selectedPresetId = 0;
            presetAdapter.notifyDataSetChanged();
            slotAdapter.notifyDataSetChanged();
            persist();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadPaletteState();
        if (presetAdapter != null) presetAdapter.notifyDataSetChanged();
        if (slotAdapter != null) slotAdapter.notifyDataSetChanged();
    }

    private SharedPreferences cache() {
        return ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(this);
    }

    private void reloadPaletteState() {
        if (lightMode) {
            selectedPresetId = cache().getInt(KEY_LIGHT_PRESET_ID, -1);
            customMode = false;
            int shown = selectedPresetId > 0 ? selectedPresetId : firstLightPresetId();
            workingPalette = ThemePresets.getById(shown).palette.copy();
            return;
        }
        selectedPresetId = cache().getInt(KEY_PRESET_ID, 1);
        String paletteJson = cache().getString(KEY_PALETTE_JSON, "");
        customMode = ThemeSettingsHelper.isCustomMode(selectedPresetId);
        workingPalette = ThemeSettingsHelper.resolveEffectivePalette(selectedPresetId, paletteJson);
    }

    private static int firstLightPresetId() {
        for (ThemePreset p : ThemePresets.all()) if (ThemePresets.isLightPreset(p.id)) return p.id;
        return 1;
    }

    private IgThemePalette activePalette() {
        if (!customMode && selectedPresetId > 0) return ThemePresets.getById(selectedPresetId).palette;
        return workingPalette;
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_PRESETS_EXPANDED, presetsExpanded);
        outState.putBoolean(STATE_CUSTOM_EXPANDED, customExpanded);
    }

    private void setupCollapsibleSection(View header, View content, ImageView icon, boolean expanded) {
        setSectionExpanded(content, icon, expanded);
        header.setOnClickListener(v -> {
            if (content == presetsContent) {
                presetsExpanded = !presetsExpanded;
                setSectionExpanded(content, icon, presetsExpanded);
            } else {
                customExpanded = !customExpanded;
                setSectionExpanded(content, icon, customExpanded);
            }
        });
    }

    private void setSectionExpanded(View content, ImageView icon, boolean expanded) {
        content.setVisibility(expanded ? View.VISIBLE : View.GONE);
        icon.setImageResource(expanded ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        icon.setContentDescription(getString(expanded ? R.string.theme_collapse_section : R.string.theme_expand_section));
    }

    private static String formatColorHex(int color) {
        if (Color.alpha(color) == 255) return String.format("#%06X", 0xFFFFFF & color);
        return String.format("#%08X", color);
    }

    /** Persists to the companion app's local cache and syncs to Instagram, matching every
     *  other setting's broadcast convention in FeaturesFragment. */
    private void persist() {
        boolean enabled = enableSwitch.isChecked();
        if (lightMode) {
            persistLight(enabled);
            return;
        }
        int presetId = customMode ? 0 : selectedPresetId;
        String paletteJson = workingPalette.copy().toJson();

        SharedPreferences.Editor editor = cache().edit();
        editor.putBoolean(KEY_ENABLED, enabled);
        editor.putInt(KEY_PRESET_ID, presetId);
        editor.putString(KEY_PALETTE_JSON, paletteJson);
        editor.commit();
        makeCacheWorldReadable();

        Intent enabledIntent = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF");
        enabledIntent.putExtra("key", KEY_ENABLED);
        enabledIntent.putExtra("value", enabled);
        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(this, enabledIntent);

        Intent presetIntent = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT");
        presetIntent.putExtra("key", KEY_PRESET_ID);
        presetIntent.putExtra("value", presetId);
        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(this, presetIntent);

        Intent paletteIntent = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
        paletteIntent.putExtra("key", KEY_PALETTE_JSON);
        paletteIntent.putExtra("value", paletteJson);
        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(this, paletteIntent);

        ps.reso.instaeclipse.utils.toast.IeToast.makeText(this, R.string.theme_saved, Toast.LENGTH_SHORT).show();
    }

    /** Light mode: only the enable switch and the light theme id; the dark theme is left as is. */
    private void persistLight(boolean enabled) {
        SharedPreferences.Editor editor = cache().edit();
        editor.putBoolean(KEY_ENABLED, enabled);
        if (selectedPresetId > 0) editor.putInt(KEY_LIGHT_PRESET_ID, selectedPresetId);
        editor.commit();
        makeCacheWorldReadable();

        Intent enabledIntent = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF");
        enabledIntent.putExtra("key", KEY_ENABLED);
        enabledIntent.putExtra("value", enabled);
        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(this, enabledIntent);

        if (selectedPresetId > 0) {
            Intent presetIntent = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT");
            presetIntent.putExtra("key", KEY_LIGHT_PRESET_ID);
            presetIntent.putExtra("value", selectedPresetId);
            ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(this, presetIntent);
        }
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(this, R.string.theme_saved, Toast.LENGTH_SHORT).show();
    }

    private void makeCacheWorldReadable() {
        try {
            File file = new File(getApplicationInfo().dataDir + "/shared_prefs/" + CACHE_NAME + ".xml");
            file.setReadable(true, false);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onColorPicked(int color) {
        if (pendingSlotKey == null) return;
        workingPalette.set(pendingSlotKey, color);
        customMode = true;
        selectedPresetId = 0;
        if (!enableSwitch.isChecked()) enableSwitch.setChecked(true);
        presetAdapter.notifyDataSetChanged();
        slotAdapter.notifyDataSetChanged();
        persist();
        pendingSlotKey = null;
    }

    private void selectPreset(ThemePreset preset) {
        selectedPresetId = preset.id;
        customMode = false;
        workingPalette = preset.palette.copy();
        if (!enableSwitch.isChecked()) enableSwitch.setChecked(true);
        presetAdapter.notifyDataSetChanged();
        slotAdapter.notifyDataSetChanged();
        persist();
    }

    private void openPicker(String slotKey, int color, String label) {
        pendingSlotKey = slotKey;
        AdvancedColorPickerDialog.newInstance(label, color).show(getSupportFragmentManager(), "colorPicker");
    }

    private String slotLabel(int position) {
        if (position < 0 || position >= SLOT_LABELS.length) return "";
        return getString(SLOT_LABELS[position]);
    }

    private void bindPreview(LinearLayout container, int[] colors) {
        IgThemePalette.bindCardPreview(this, container, colors);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class PresetAdapter extends RecyclerView.Adapter<PresetAdapter.Holder> {
        private final List<ThemePreset> presets;

        PresetAdapter(List<ThemePreset> presets) {
            this.presets = presets;
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_theme_preset, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            ThemePreset preset = presets.get(position);
            holder.name.setText(ThemePresets.getDisplayName(holder.itemView.getContext(), preset.id));
            bindPreview(holder.preview, preset.palette.previewColors());
            boolean selected = !customMode && preset.id == selectedPresetId;
            int stroke = selected
                    ? MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorPrimary)
                    : MaterialColors.getColor(holder.card, com.google.android.material.R.attr.colorOutline);
            holder.card.setStrokeColor(stroke);
            holder.card.setStrokeWidth(selected ? dp(2) : dp(1));
            holder.card.setOnClickListener(v -> selectPreset(preset));
        }

        @Override
        public int getItemCount() {
            return presets.size();
        }

        class Holder extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final LinearLayout preview;
            final TextView name;

            Holder(View itemView) {
                super(itemView);
                card = itemView.findViewById(R.id.theme_preset_card);
                preview = itemView.findViewById(R.id.theme_preset_preview);
                name = itemView.findViewById(R.id.theme_preset_name);
            }
        }
    }

    private class ColorSlotAdapter extends RecyclerView.Adapter<ColorSlotAdapter.Holder> {

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_theme_color_slot, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            String key = IgThemePalette.SLOT_KEYS[position];
            int color = activePalette().get(key);
            holder.label.setText(slotLabel(position));
            holder.hex.setText(formatColorHex(color));
            GradientDrawable swatch = new GradientDrawable();
            swatch.setCornerRadius(dp(10));
            swatch.setColor(color);
            holder.swatch.setBackground(swatch);
            holder.itemView.setOnClickListener(v -> {
                if (!customMode && selectedPresetId > 0) {
                    workingPalette = ThemePresets.getById(selectedPresetId).palette.copy();
                }
                openPicker(key, color, slotLabel(position));
            });
        }

        @Override
        public int getItemCount() {
            return IgThemePalette.SLOT_KEYS.length;
        }

        class Holder extends RecyclerView.ViewHolder {
            final View swatch;
            final TextView label;
            final TextView hex;

            Holder(View itemView) {
                super(itemView);
                swatch = itemView.findViewById(R.id.theme_slot_swatch);
                label = itemView.findViewById(R.id.theme_slot_label);
                hex = itemView.findViewById(R.id.theme_slot_hex);
            }
        }
    }
}
