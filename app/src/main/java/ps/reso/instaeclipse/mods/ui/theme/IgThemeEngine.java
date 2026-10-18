package ps.reso.instaeclipse.mods.ui.theme;

import android.content.Context;
import android.content.res.Resources;
import android.util.SparseIntArray;
import android.util.TypedValue;

import java.lang.reflect.Field;

import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Maps Instagram's theme attrs and color resources to one of {@link IgThemePalette}'s 15 slots
 * by matching on the resource's own NAME (e.g. "igds_color_primary_background"), not its
 * numeric ID or declaring class — Instagram's design-system resource names are stable across
 * versions even though the obfuscated classes referencing them are not.
 */
public final class IgThemeEngine {

    private static volatile IgThemePalette activePalette;
    private static volatile SparseIntArray attrToSlot;
    private static volatile SparseIntArray colorResToSlot;
    private static volatile ClassLoader hostClassLoader;
    private static volatile boolean initialized;

    private IgThemeEngine() {}

    // Night state the cached palette was resolved for (-1 = none yet). Only consulted when the
    // user picked a separate light-mode behaviour (FeatureFlags.themeLightPresetId != 0, #251).
    private static volatile int paletteNight = -1;

    public static boolean isActive() {
        return themeEnabled() && !IgColorRemapEngine.isBypassing();
    }

    /** The custom theme's accent when a theme is active, else {@code fallback}. For our own UI
     *  (menus, dialogs, toggles) so highlights match the chosen theme. */
    public static int accentOr(int fallback) {
        try {
            if (!themeEnabled()) return fallback;
            IgThemePalette p = getActivePalette();
            return p != null ? (p.get(IgThemePalette.SLOT_ACCENT) | 0xFF000000) : fallback;
        } catch (Throwable t) {
            return fallback;
        }
    }

    /**
     * Custom theme enabled for the CURRENT day/night mode. With themeLightPresetId == -1 the theme
     * applies only in dark mode, so light mode shows stock Instagram (dark presets made light mode
     * illegible — #251).
     */
    public static boolean themeEnabled() {
        if (!FeatureFlags.customThemeEnabled) return false;
        return FeatureFlags.themeLightPresetId != -1 || isNightNow();
    }

    /** Dark mode as Instagram sees it: the foreground activity's configuration (IG's own in-app
     *  dark-mode setting overrides the system one per activity), else the app's, else the system's. */
    public static boolean isNightNow() {
        try {
            android.content.Context c = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
            if (c == null) c = ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.currentApplication();
            android.content.res.Configuration cfg = c != null
                    ? c.getResources().getConfiguration()
                    : Resources.getSystem().getConfiguration();
            return (cfg.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static IgThemePalette getActivePalette() {
        if (FeatureFlags.themeLightPresetId > 0) {
            // A different preset is used in light mode: re-resolve when the mode flips.
            int night = isNightNow() ? 1 : 0;
            if (night != paletteNight) {
                synchronized (IgThemeEngine.class) {
                    if (night != paletteNight) {
                        boolean flipped = paletteNight != -1;
                        activePalette = night == 1
                                ? resolvePalette()
                                : ThemePresets.getById(FeatureFlags.themeLightPresetId).palette.copy();
                        paletteNight = night;
                        if (flipped) IgColorRemapEngine.invalidate();
                    }
                }
            }
            return activePalette;
        }
        if (activePalette == null) {
            synchronized (IgThemeEngine.class) {
                if (activePalette == null) activePalette = resolvePalette();
            }
        }
        return activePalette;
    }

    public static void invalidate() {
        activePalette = null;
        paletteNight = -1;
        initialized = false;
        attrToSlot = null;
        colorResToSlot = null;
        IgColorRemapEngine.invalidate();
    }

    public static SparseIntArray getColorResToSlot() {
        return colorResToSlot;
    }

    public static IgThemePalette resolvePalette() {
        return ThemeSettingsHelper.resolveEffectivePalette();
    }

    public static void ensureInitialized(Context context) {
        if (context == null) return;
        ensureInitialized(context.getResources(), context.getClassLoader());
    }

    public static void ensureInitialized(Resources res) {
        if (res == null) return;
        ensureInitialized(res, hostClassLoader);
    }

    public static void ensureInitialized(Resources res, ClassLoader cl) {
        if (initialized) return;
        synchronized (IgThemeEngine.class) {
            if (initialized) return;
            if (cl != null) hostClassLoader = cl;
            String pkg = res.getResourcePackageName(android.R.color.black);
            initMappings(res, pkg, cl);
            initialized = true;
            ModuleLog.line("(InstaEclipse | Theme): mapped " + attrToSlot.size() + " attrs, " + colorResToSlot.size() + " colors");
        }
    }

    public static Integer colorForAttr(int attrId) {
        if (!themeEnabled() || attrId == 0) return null;
        SparseIntArray map = attrToSlot;
        if (map == null) return null;
        int slotIndex = map.get(attrId, -1);
        if (slotIndex < 0) return null;
        return getActivePalette().get(IgThemePalette.SLOT_KEYS[slotIndex]);
    }

    public static Integer colorForResource(int resId) {
        if (!themeEnabled() || resId == 0) return null;
        SparseIntArray map = colorResToSlot;
        if (map == null) return null;
        int slotIndex = map.get(resId, -1);
        if (slotIndex < 0) return null;
        Integer fix = IgColorRemapEngine.polarityFix(resId);
        if (fix != null) return fix;
        return getActivePalette().get(IgThemePalette.SLOT_KEYS[slotIndex]);
    }

    public static boolean looksLikeResourceId(int value) {
        if (value == 0) return false;
        int pkg = value >>> 24;
        return pkg == 127 || pkg == 1;
    }

    public static boolean looksLikeDirectColor(int value) {
        if (value == 0 || looksLikeResourceId(value)) return false;
        int alpha = value >>> 24;
        return alpha == 255 || alpha == 0 || alpha < 127;
    }

    public static void applyAttrOverride(int attrId, TypedValue out) {
        Integer override = colorForAttr(attrId);
        if (override == null || out == null) return;
        out.type = TypedValue.TYPE_INT_COLOR_ARGB8;
        out.data = override;
        out.resourceId = 0;
        out.assetCookie = 0;
        out.string = null;
    }

    private static void initMappings(Resources res, String pkg, ClassLoader cl) {
        SparseIntArray attrs = new SparseIntArray();
        SparseIntArray colors = new SparseIntArray();
        mapCoreAttrs(attrs, res, pkg);
        scanAttrClasses(attrs, cl);
        scanColorClasses(colors, cl);
        mapCoreColors(colors, res, pkg);
        attrToSlot = attrs;
        colorResToSlot = colors;
    }

    private static void mapCoreAttrs(SparseIntArray map, Resources res, String pkg) {
        String[] names = {"igds_color_primary_background", "igds_color_media_background", "igds_color_clips_tab_bar_background",
                "actionBarBackgroundColor", "tabBarBackgroundColor", "modalActionBarBackground", "directThreadActionBarBackgroundColor",
                "statusBarBackgroundColor", "sc_card_background_flat", "fbpay_background_color", "permissionBannerBackground",
                "igdsPrimaryBackground", "igds_color_cta_banner_background", "status_bar_background", "android:colorBackground",
                "android:windowBackground", "android:navigationBarColor", "igds_color_elevated_background", "igds_color_highlight_background",
                "igds_color_elevated_highlight_background", "callout_background", "igds_color_banner_background", "creationTertiaryBackground",
                "igds_color_form_field_background_default_color", "igds_color_generic_xma_background_color", "igds_color_secondary_background",
                "igds_color_primary_text", "igdsPrimaryText", "glyphColorPrimary", "glyphColorSecondaryActive", "fbpay_primary_text_color",
                "tabSelectedTextColor", "android:textColorPrimary", "snackbar_text_color", "igds_color_secondary_text", "android:textColorSecondary",
                "reportSubtitleTextColor", "igds_color_primary_button", "igds_color_primary_button_indigo", "igds_color_data_visualization_primary",
                "igds_color_gradient_blue", "colorControlActivated", "igds_color_creation_tools_blue", "fbpay_focus_color", "igds_color_primary_icon",
                "igds_color_actionbar_drawable_primary", "igds_color_clips_tab_bar_icon", "colorControlNormal", "igds_color_divider",
                "igds_color_elevated_separator", "igds_color_border_secondary", "igds_color_border_tertiary", "android:statusBarColor",
                "igds_color_text_link", "android:textColorLink", "igds_color_action_cell_emphasized_text", "fbpay_link_text_color",
                "igds_color_link", "igds_color_error_or_destructive", "igds_color_icon_badge"};
        for (String name : names) mapAttrByName(map, res, pkg, name);
    }

    private static void mapCoreColors(SparseIntArray map, Resources res, String pkg) {
        String[] names = {"bds_black", "igds_prism_black", "bds_white", "igds_prism_gray_00", "bds_grey_0", "bds_grey_1",
                "bds_grey_2", "bds_grey_3", "bds_grey_4", "bds_grey_6", "bds_grey_7", "bds_grey_8", "bds_grey_9", "bds_grey_10",
                "bds_grey_11", "bds_grey_12", "bds_grey_16", "bds_grey_18", "bds_grey_21", "bds_grey_22", "bds_grey_24",
                "igds_prism_gray_08", "igds_prism_gray_10", "emphasized_action_color", "badge_color", "igds_prism_indigo_1000",
                "bds_blue_1", "bds_blue_2", "bds_red_5", "bds_red_6", "igds_primary_background", "bottom_sheet_undo_redo_color"};
        for (String name : names) mapColorByName(map, res, pkg, name);
        String[] packages = {pkg, CommonUtils.IG_PACKAGE_NAME};
        for (String p : packages) {
            if (p == null || p.isEmpty()) continue;
            for (String name : names) {
                int id = res.getIdentifier(name, "color", p);
                if (id != 0) {
                    int slot = slotForColorName(name);
                    if (slot >= 0) map.put(id, slot);
                }
            }
        }
    }

    private static void scanAttrClasses(SparseIntArray map, ClassLoader cl) {
        if (cl == null) return;
        String[] candidates = {"com.instagram.android.R$attr"};
        for (String className : candidates) scanFields(map, cl, className, true);
    }

    private static void scanColorClasses(SparseIntArray map, ClassLoader cl) {
        if (cl == null) return;
        String[] candidates = {"com.instagram.android.R$color"};
        for (String className : candidates) scanFields(map, cl, className, false);
    }

    private static void scanFields(SparseIntArray map, ClassLoader cl, String className, boolean attrs) {
        try {
            Class<?> cls = cl.loadClass(className);
            for (Field field : cls.getDeclaredFields()) {
                if ((field.getModifiers() & java.lang.reflect.Modifier.STATIC) != 0 && field.getType() == int.class) {
                    String name = field.getName();
                    int slot = attrs ? slotForAttrName(name) : slotForColorName(name);
                    if (slot >= 0) {
                        try { map.put(field.getInt(null), slot); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    static int slotForAttrName(String name) {
        if (name == null) return -1;
        if (name.contains("primary_background") || name.contains("media_background") || name.contains("tab_bar_background")) return 0;
        if ((name.contains("clips_tab") && name.contains(IgThemePalette.SLOT_BACKGROUND)) || name.equals("status_bar_background")
                || name.contains("cta_banner") || name.equals("igdsPrimaryBackground") || name.contains("actionBarBackground")
                || name.contains("tabBarBackground") || name.contains("colorBackground") || name.contains("windowBackground")) return 0;
        if (name.contains("elevated") || name.contains("highlight_background") || name.contains("secondary_background")
                || name.contains("callout_background") || name.contains("form_field_background") || name.contains("banner_background")
                || (name.contains("creation") && name.contains(IgThemePalette.SLOT_BACKGROUND))) return 1;
        if (name.contains("primary_text") || name.equals("igdsPrimaryText") || name.contains("textColorPrimary")
                || name.contains("tabSelectedText") || name.contains("snackbar_text")) return 2;
        if (name.contains("secondary_text") || name.contains("textColorSecondary")) return 3;
        if (name.contains("glyphColor")) return 7;
        if (name.contains("primary_button") || name.contains("gradient_blue") || name.contains("colorControlActivated")
                || name.contains("data_visualization_primary") || name.contains("fbpay_focus") || name.contains("creation_tools_blue")) return 5;
        if (name.contains("emphasized") || name.contains(IgThemePalette.SLOT_ACCENT) || name.contains("cta")) return 4;
        if (name.contains("primary_icon") || name.contains("actionbar_drawable") || name.contains("clips_tab_bar_icon")
                || name.contains("colorControlNormal") || name.contains("tab_bar_icon")) return 6;
        if (name.contains("nav3_") && name.contains(IgThemePalette.SLOT_ICON)) return 6;
        if (name.contains(IgThemePalette.SLOT_DIVIDER) || name.contains("separator")) return 8;
        if (name.contains(IgThemePalette.SLOT_BORDER)) return 9;
        if (name.contains("stroke") && !name.contains(IgThemePalette.SLOT_DESTRUCTIVE)) return 9;
        if (name.contains("statusBarColor") || name.contains("status_bar")) return 10;
        if (name.contains("navigationBar") || name.contains("nav3_")) return 11;
        if (name.contains("text_link") || name.contains("link_text") || name.equals("igds_color_link") || name.contains("action_cell_emphasized")) return 12;
        if (name.contains(IgThemePalette.SLOT_DESTRUCTIVE)) return 14;
        if (name.contains("badge") && name.contains(IgThemePalette.SLOT_ICON)) return 14;
        if (name.contains("error") || name.contains("icon_badge")) return 13;
        return name.startsWith("igds_color_") ? 1 : -1;
    }

    static int slotForColorName(String name) {
        if (name == null) return -1;
        if (name.contains("primary_text") || name.contains("text_on_color") || name.contains("text_on_white")) return 2;
        if (name.contains("secondary_text") || name.contains("text_subtitle")) return 3;
        if (name.contains(IgThemePalette.SLOT_GLYPH)) return 7;
        if (name.contains("primary_icon") || name.contains("secondary_icon")) return 6;
        if (name.contains(IgThemePalette.SLOT_DESTRUCTIVE) || (name.contains("badge") && name.contains(IgThemePalette.SLOT_ICON))) return 14;
        if (name.contains("error")) return 13;
        if (name.contains(IgThemePalette.SLOT_LINK)) return 12;
        if (name.contains("primary_button") || name.contains("bds_blue") || name.equals("emphasized_action_color")) return 5;
        if (name.contains("indigo") || name.contains("blue") || name.contains("emphasized") || name.contains("gradient")
                || name.contains(IgThemePalette.SLOT_ACCENT) || name.contains("cta")) return 4;
        if (name.contains(IgThemePalette.SLOT_DIVIDER) || name.contains("separator")) return 8;
        if (name.contains(IgThemePalette.SLOT_BORDER) || name.contains("stroke")) return 9;
        if (name.contains("status_bar")) return 10;
        if (name.contains("nav")) return 11;
        if (name.contains("black") || name.equals("igds_prism_black") || name.contains("grey_9") || name.contains("gray_10")
                || name.contains("grey_10") || name.contains("media_background")) return 0;
        if (name.contains("grey_8") || name.contains("gray_08") || name.contains("grey_7") || name.contains("elevated")
                || name.contains("highlight") || name.contains(IgThemePalette.SLOT_SURFACE)) return 1;
        if (name.contains("grey_0") || name.contains("gray_00") || name.contains("white")) return 2;
        if (name.contains("grey_1") || name.contains("secondary") || name.contains("grey_2") || name.contains("grey_3")
                || name.contains("grey_4") || name.contains("grey_6")) return 3;
        if (name.contains(IgThemePalette.SLOT_ICON)) return 6;
        if (name.contains("red") && (name.contains("5") || name.contains("6") || name.contains(IgThemePalette.SLOT_DESTRUCTIVE))) return 14;
        return (name.startsWith("bds_") || name.startsWith("igds_prism_") || name.startsWith("igds_")) ? 1 : -1;
    }

    private static void mapAttrByName(SparseIntArray map, Resources res, String pkg, String name) {
        String attrName = name.startsWith("android:") ? name.substring(8) : name;
        String attrPkg = name.startsWith("android:") ? "android" : pkg;
        int id = res.getIdentifier(attrName, "attr", attrPkg);
        if (id != 0) {
            int slot = slotForAttrName(attrName);
            if (slot >= 0) map.put(id, slot);
        }
    }

    private static void mapColorByName(SparseIntArray map, Resources res, String pkg, String name) {
        int id = res.getIdentifier(name, "color", pkg);
        if (id != 0) {
            int slot = slotForColorName(name);
            if (slot >= 0) map.put(id, slot);
        }
    }
}
