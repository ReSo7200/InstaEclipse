package ps.reso.instaeclipse.mods.ui.theme;

import android.content.Context;
import android.content.res.Resources;
import android.util.SparseIntArray;
import android.view.View;

import androidx.core.view.ViewCompat;

import java.lang.reflect.Field;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Two-tier color substitution: an exact/RGB lookup table built from every color the palette
 * actually touches, plus a luminance/hue "fuzzy" classifier for any color that slips through
 * (so arbitrary colors baked into Instagram's own drawables/bitmaps still land on a roughly
 * plausible slot instead of staying unthemed).
 */
public final class IgColorRemapEngine {

    private static final ThreadLocal<Integer> BYPASS_DEPTH = ThreadLocal.withInitial(() -> 0);
    private static final int CACHE_MISS = Integer.MIN_VALUE;
    private static final int FUZZY_CACHE_LIMIT = 1024;

    private static volatile boolean built;
    private static volatile SparseIntArray exactTable;
    private static volatile SparseIntArray rgbTable;
    private static volatile SparseIntArray fuzzyCache;
    // Light theme in light mode: resource id -> corrected color for name-mapped resources whose slot
    // would flip ink/paper (see keepLightPolarity). Null when not in that mode.
    private static volatile SparseIntArray resPolarityFix;
    private static volatile int moduleUiDepth;

    private static volatile int fuzzyBg, fuzzySurface, fuzzyPrimary, fuzzySecondary, fuzzyButton, fuzzyLink, fuzzyDestructive;
    private static volatile boolean fuzzyDarkTheme;

    private IgColorRemapEngine() {}

    public static boolean isBypassing() {
        return BYPASS_DEPTH.get() > 0 || moduleUiDepth > 0;
    }

    public static int getModuleUiDepth() {
        return moduleUiDepth;
    }

    public static boolean shouldSkipRemap(Object hookTarget) {
        return isBypassing() || isModuleUiTarget(hookTarget);
    }

    public static boolean isModuleUiTarget(Object target) {
        return target instanceof View && isModuleUiView((View) target);
    }

    public static boolean isModuleUiView(View view) {
        for (View current = view; current != null; current = parentView(current)) {
            if (Boolean.TRUE.equals(current.getTag(R.id.tag_module_dialog_root))) return true;
        }
        return false;
    }

    public static void markModuleDialogView(View view) {
        if (view != null) view.setTag(R.id.tag_module_dialog_root, Boolean.TRUE);
    }

    private static View parentView(View view) {
        Object parent = view.getParent();
        return parent instanceof View ? (View) parent : null;
    }

    public static void enterModuleUi() {
        moduleUiDepth++;
    }

    public static void leaveModuleUi() {
        if (moduleUiDepth > 0) moduleUiDepth--;
    }

    public static void withBypass(Runnable action) {
        int depth = BYPASS_DEPTH.get() + 1;
        BYPASS_DEPTH.set(depth);
        try {
            action.run();
        } finally {
            BYPASS_DEPTH.set(depth - 1);
        }
    }

    private static void enterBypass() {
        BYPASS_DEPTH.set(BYPASS_DEPTH.get() + 1);
    }

    private static void exitBypass() {
        BYPASS_DEPTH.set(BYPASS_DEPTH.get() - 1);
    }

    public static int sampleColor(Resources res, int resId) {
        if (res == null || resId == 0) return 0;
        enterBypass();
        try {
            return res.getColor(resId, null);
        } catch (Throwable th) {
            return 0;
        } finally {
            exitBypass();
        }
    }

    // Bumped whenever the table is dropped, so caches derived from it (themed ColorStateLists) refresh.
    private static volatile int generation;

    public static int generation() {
        return generation;
    }

    public static void invalidate() {
        generation++;
        built = false;
        exactTable = null;
        rgbTable = null;
        fuzzyCache = null;
        resPolarityFix = null;
    }

    /** Corrected color for a name-mapped resource when a light theme runs in light mode, else null. */
    public static Integer polarityFix(int resId) {
        SparseIntArray fix = resPolarityFix;
        if (fix == null) return null;
        int v = fix.get(resId, CACHE_MISS);
        return v == CACHE_MISS ? null : v;
    }

    public static boolean isReady() {
        return built && rgbTable != null;
    }

    public static void ensureBuilt(Context context) {
        if (built || context == null || !IgThemeEngine.isActive()) return;
        synchronized (IgColorRemapEngine.class) {
            if (built) return;
            buildTable(context);
            built = true;
            int size = (rgbTable != null ? rgbTable.size() : 0) + (exactTable != null ? exactTable.size() : 0);
            ModuleLog.line("(InstaEclipse | Theme): color remap table size=" + size);
        }
    }

    public static int remap(int color) {
        if (!IgThemeEngine.isActive() || isBypassing() || color == 0) return color;
        SparseIntArray exact = exactTable;
        SparseIntArray rgb = rgbTable;
        if (rgb == null) return color;
        if (exact != null) {
            int exactHit = exact.get(color, CACHE_MISS);
            if (exactHit != CACHE_MISS) return exactHit;
        }
        int mappedRgb = rgb.get(color & 0x00FFFFFF, CACHE_MISS);
        if (mappedRgb != CACHE_MISS) {
            return (0xFF000000 & color) | (0x00FFFFFF & mappedRgb);
        }
        SparseIntArray fuzzy = fuzzyCache;
        if (fuzzy != null) {
            int cached = fuzzy.get(color, CACHE_MISS);
            if (cached != CACHE_MISS) return cached;
        }
        int result = remapFuzzy(color);
        if (fuzzy != null && fuzzy.size() < FUZZY_CACHE_LIMIT) fuzzy.put(color, result);
        return result;
    }

    public static int remapIfChanged(int color) {
        int remapped = remap(color);
        return remapped == color ? color : remapped;
    }

    public static void applyRemapArg(Object[] args, int index) {
        if (!IgThemeEngine.isActive() || isBypassing()) return;
        int original = (Integer) args[index];
        int remapped = remap(original);
        if (remapped != original) args[index] = remapped;
    }

    public static int remapResourceColor(Resources res, int resId, int resolved) {
        if (!IgThemeEngine.isActive() || isBypassing()) return resolved;
        Integer override = IgThemeEngine.colorForResource(resId);
        return override != null ? override : remap(resolved);
    }

    private static int remapFuzzy(int color) {
        int alpha = (color >>> 24) & 0xFF;
        if (alpha == 0) return color;
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        double lum = ((r * 0.299d) + (g * 0.587d) + (b * 0.114d)) / 255.0d;
        int target;
        if (isAccentBlue(r, g, b)) target = fuzzyButton;
        else if (isDestructiveRed(r, g, b)) target = fuzzyDestructive;
        else if (isLinkBlue(r, g, b)) target = fuzzyLink;
        else if (fuzzyDarkTheme) {
            if (lum < 0.08d) target = fuzzyBg;
            else if (lum < 0.22d) target = fuzzySurface;
            else if (lum > 0.65d) target = fuzzyPrimary;
            else target = fuzzySecondary;
        } else if (lum > 0.92d) target = fuzzyBg;
        else if (lum > 0.75d) target = fuzzySurface;
        else if (lum < 0.25d) target = fuzzyPrimary;
        else target = fuzzySecondary;
        return (alpha << 24) | (0x00FFFFFF & target);
    }

    private static void cacheFuzzyPalette(IgThemePalette palette) {
        fuzzyBg = palette.background;
        fuzzySurface = palette.surface;
        fuzzyPrimary = palette.primaryText;
        fuzzySecondary = palette.secondaryText;
        fuzzyButton = palette.button;
        fuzzyLink = palette.link;
        fuzzyDestructive = palette.destructive;
        int bg = palette.background;
        int r = (bg >> 16) & 0xFF, g = (bg >> 8) & 0xFF, b = bg & 0xFF;
        fuzzyDarkTheme = ((r * 0.299d) + (g * 0.587d) + (b * 0.114d)) / 255.0d < 0.2d;
    }

    // Both need real saturation: a near-white with a faint blue tint (e.g. a light theme's own
    // background) is a surface, not a link or a button.
    private static boolean isAccentBlue(int r, int g, int b) {
        return b > 160 && b > r + 30 && b > g + 10 && b - Math.min(r, g) > 60;
    }

    private static boolean isLinkBlue(int r, int g, int b) {
        return b > 120 && b >= r && g < b && b - Math.min(r, g) > 60;
    }

    private static boolean isDestructiveRed(int r, int g, int b) {
        return r > 180 && r > g + 60 && r > b + 60;
    }

    private static void buildTable(Context context) {
        SparseIntArray exact = new SparseIntArray(128);
        SparseIntArray rgb = new SparseIntArray(512);
        IgThemePalette palette = IgThemeEngine.getActivePalette();
        cacheFuzzyPalette(palette);
        Resources res = context.getResources();
        String pkg = res.getResourcePackageName(android.R.color.black);
        mapCanonical(exact, rgb, palette);
        mapResourceNames(exact, rgb, res, pkg, palette);
        mapFromSlots(exact, rgb, res, pkg, palette);
        mapAllResourceColors(exact, rgb, res, context.getClassLoader(), palette);
        // The canonical table assumes Instagram's dark resources (black = background, white = text).
        // When a light theme runs while Instagram is in light mode, IG draws its text and icons in
        // black, so that assumption would paint them in the light background color and hide them.
        if (!fuzzyDarkTheme && !IgThemeEngine.isNightNow()) {
            keepLightPolarity(exact, palette);
            keepLightPolarity(rgb, palette);
            resPolarityFix = buildResourcePolarityFix(res, palette);
        }
        // The palette's own colors are already final: never remap them a second time (a view that
        // gets our background and is then re-colored would otherwise go through the classifier).
        for (String key : IgThemePalette.SLOT_KEYS) {
            int v = palette.get(key) & 0x00FFFFFF;
            if (rgb.get(v, CACHE_MISS) == CACHE_MISS) rgb.put(v, v);
        }
        exactTable = exact;
        rgbTable = rgb;
        fuzzyCache = new SparseIntArray(256);
    }

    private static void mapAllResourceColors(SparseIntArray exact, SparseIntArray rgb, Resources res, ClassLoader cl, IgThemePalette palette) {
        if (cl == null) return;
        try {
            Class<?> cls = cl.loadClass("com.instagram.android.R$color");
            for (Field field : cls.getDeclaredFields()) {
                if (field.getType() != int.class || (field.getModifiers() & java.lang.reflect.Modifier.STATIC) == 0) continue;
                int slot = IgThemeEngine.slotForColorName(field.getName());
                if (slot < 0) continue;
                try {
                    int resId = field.getInt(null);
                    int original = sampleColor(res, resId);
                    if (original != 0) put(exact, rgb, original, palette.get(IgThemePalette.SLOT_KEYS[slot]));
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void mapCanonical(SparseIntArray exact, SparseIntArray rgb, IgThemePalette palette) {
        put(exact, rgb, ViewCompat.MEASURED_STATE_MASK, palette.background);
        put(exact, rgb, -15986668, palette.background);
        put(exact, rgb, -15066598, palette.surface);
        put(exact, rgb, -14803426, palette.surface);
        put(exact, rgb, -14277082, palette.surface);
        put(exact, rgb, -13224394, palette.divider);
        put(exact, rgb, -15592942, palette.background);
        put(exact, rgb, -15461356, palette.surface);
        put(exact, rgb, -14605528, palette.surface);
        put(exact, rgb, -13486786, palette.surface);
        put(exact, rgb, -657931, palette.primaryText);
        put(exact, rgb, -1052689, palette.secondaryText);
        put(exact, rgb, -2368549, palette.secondaryText);
        put(exact, rgb, -3684409, palette.secondaryText);
        put(exact, rgb, -5723992, palette.secondaryText);
        put(exact, rgb, -9211021, palette.secondaryText);
        put(exact, rgb, -11184811, palette.secondaryText);
        put(exact, rgb, -4407100, palette.secondaryText);
        put(exact, rgb, -1, palette.primaryText);
        put(exact, rgb, -16738826, palette.button);
        put(exact, rgb, -15173646, palette.button);
        put(exact, rgb, -11903495, palette.button);
        put(exact, rgb, -1226410, palette.destructive);
        put(exact, rgb, -53184, palette.destructive);
        put(exact, rgb, -217321, palette.accent);
        put(exact, rgb, -14934750, palette.surface);
        put(exact, rgb, -14471112, palette.surface);
        put(exact, rgb, -1312770, palette.surface);
        put(exact, rgb, -789001, palette.background);
        put(exact, rgb, -328966, palette.primaryText);
        put(exact, rgb, -2039584, palette.secondaryText);
        put(exact, rgb, -9079435, palette.secondaryText);
        put(exact, rgb, -6381922, palette.secondaryText);
        put(exact, rgb, -12434878, palette.border);
        put(exact, rgb, -13882324, palette.border);
        put(exact, rgb, -13092808, palette.border);
        put(exact, rgb, -11219201, palette.link);
        put(exact, rgb, -16763029, palette.link);
        put(exact, rgb, -10960094, palette.accent);
        put(exact, rgb, -9360, palette.accent);
        put(exact, rgb, -2130706433, IgThemePalette.withAlpha(palette.primaryText, 0.5f));
        put(exact, rgb, -2131364363, IgThemePalette.withAlpha(palette.primaryText, 0.5f));
        put(exact, rgb, Integer.MIN_VALUE, IgThemePalette.withAlpha(palette.background, 0.5f));
        put(exact, rgb, -872415232, IgThemePalette.withAlpha(palette.background, 0.8f));
        put(exact, rgb, 855638016, IgThemePalette.withAlpha(palette.background, 0.2f));
        put(exact, rgb, 872415231, IgThemePalette.withAlpha(palette.primaryText, 0.2f));
        put(exact, rgb, -7434610, palette.secondaryText);
        put(exact, rgb, -6710887, palette.secondaryText);
        put(exact, rgb, -13882324, palette.secondaryText);
    }

    private static void mapResourceNames(SparseIntArray exact, SparseIntArray rgb, Resources res, String pkg, IgThemePalette palette) {
        String[] colorNames = {"bds_black", "igds_prism_black", "bds_white", "igds_prism_gray_00", "bds_grey_0", "bds_grey_1",
                "bds_grey_2", "bds_grey_3", "bds_grey_4", "bds_grey_6", "bds_grey_7", "bds_grey_8", "bds_grey_9", "bds_grey_10",
                "bds_grey_11", "bds_grey_12", "bds_grey_16", "bds_grey_18", "bds_grey_21", "bds_grey_22", "bds_grey_24",
                "igds_prism_gray_08", "igds_prism_gray_10", "emphasized_action_color", "badge_color", "igds_prism_indigo_1000",
                "bds_blue_1", "bds_blue_2", "bds_red_5", "bds_red_6", "igds_primary_background", "bottom_sheet_undo_redo_color"};
        for (String name : colorNames) {
            int id = res.getIdentifier(name, "color", pkg);
            if (id == 0) continue;
            int slot = IgThemeEngine.slotForColorName(name);
            if (slot < 0) continue;
            int original = sampleColor(res, id);
            if (original != 0) put(exact, rgb, original, palette.get(IgThemePalette.SLOT_KEYS[slot]));
        }
    }

    private static void mapFromSlots(SparseIntArray exact, SparseIntArray rgb, Resources res, String pkg, IgThemePalette palette) {
        SparseIntArray colorResToSlot = IgThemeEngine.getColorResToSlot();
        if (colorResToSlot == null) return;
        for (int i = 0; i < colorResToSlot.size(); i++) {
            int resId = colorResToSlot.keyAt(i);
            int slot = colorResToSlot.valueAt(i);
            int original = sampleColor(res, resId);
            if (original != 0) put(exact, rgb, original, palette.get(IgThemePalette.SLOT_KEYS[slot]));
        }
    }

    /**
     * Light theme in light mode: an original and its replacement must stay on the same side of the
     * light/dark line — dark ink stays ink, light paper stays paper. Entries that flip (dark text
     * mapped to a light surface, or a white surface mapped to dark text) are re-pointed at the
     * matching slot of the light palette. Accent/link/destructive colors aren't gray, so they are
     * left alone.
     */
    private static void keepLightPolarity(SparseIntArray table, IgThemePalette palette) {
        for (int i = 0; i < table.size(); i++) {
            int to = table.valueAt(i);
            int fixed = lightPolarity(table.keyAt(i), to, palette);
            if (fixed != to) table.put(table.keyAt(i), (to & 0xFF000000) == 0 ? (0x00FFFFFF & fixed)
                    : ((to & 0xFF000000) | (0x00FFFFFF & fixed)));
        }
    }

    /** Same rule for colors assigned by resource name (IgThemeEngine.colorForResource). */
    private static SparseIntArray buildResourcePolarityFix(Resources res, IgThemePalette palette) {
        SparseIntArray out = new SparseIntArray(64);
        SparseIntArray colorResToSlot = IgThemeEngine.getColorResToSlot();
        if (colorResToSlot == null) return out;
        for (int i = 0; i < colorResToSlot.size(); i++) {
            int resId = colorResToSlot.keyAt(i);
            int original = sampleColor(res, resId);
            if (original == 0) continue;
            int to = palette.get(IgThemePalette.SLOT_KEYS[colorResToSlot.valueAt(i)]);
            int fixed = lightPolarity(original, to, palette);
            if (fixed != to) out.put(resId, (to & 0xFF000000) | (0x00FFFFFF & fixed));
        }
        return out;
    }

    /** {@code to}, or the light-palette slot on the same ink/paper side as the gray {@code from}. */
    private static int lightPolarity(int from, int to, IgThemePalette palette) {
        if (!isNeutral(from)) return to;
        double lf = lum(from), lt = lum(to);
        // A light gray (a surface) never becomes a saturated accent: a button-named resource whose
        // light-mode value is a gray surface would otherwise flood trays/bars with the accent color.
        // Dark ink turning into a dark accent (themed icons) is intended and left alone.
        boolean flips = (isStrongColor(to) && lf > 0.6d)
                || (lf < 0.6d && lt > 0.75d)
                || (lf > 0.8d && lt < 0.4d);
        if (!flips) return to;
        if (lf > 0.8d) return lf > 0.97d ? palette.background : palette.surface;
        return lf < 0.25d ? palette.primaryText : palette.secondaryText;
    }

    /** A clearly colored (accent-like) value, as opposed to a gray or a lightly tinted gray. */
    private static boolean isStrongColor(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 70;
    }

    private static boolean isNeutral(int c) {
        int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) < 28;
    }

    private static double lum(int c) {
        return (((c >> 16) & 0xFF) * 0.299d + ((c >> 8) & 0xFF) * 0.587d + (c & 0xFF) * 0.114d) / 255.0d;
    }

    private static void put(SparseIntArray exact, SparseIntArray rgb, int from, int to) {
        if (from == 0 || to == 0) return;
        int fromAlpha = (from >>> 24) & 0xFF;
        int toAlpha = (to >>> 24) & 0xFF;
        if (fromAlpha != 255 || toAlpha != 255) exact.put(from, to);
        rgb.put(from & 0x00FFFFFF, 0x00FFFFFF & to);
    }
}
