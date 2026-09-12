package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.core.SettingsManager;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Custom UI font: replace Instagram's text typeface with a user-supplied .ttf/.otf.
 *
 * Framework-level (version-proof, no obfuscated names): every IG typeface load ultimately goes
 * through android.graphics.Typeface.createFromAsset / createFromFile (IG's TypefaceRepository uses
 * createFromAsset for bundled fonts and createFromFile for downloaded ones). We hook those and
 * return the user's font when the feature is on. This build ships no icon-glyph font (icons are
 * vector drawables), so replacing the text typeface doesn't break icons; we still skip any asset
 * whose name looks like an icon/glyph font as a safeguard.
 *
 * The font is picked via the system file picker (ACTION_GET_CONTENT) and copied into the module's
 * filesDir; applied on the next process start (Typeface results are cached by IG).
 */
public class CustomFontHook {

    public static final int FONT_PICK_REQUEST = 0x1F09;
    public static final int EMOJI_PICK_REQUEST = 0x1F0A;
    private static final String FONT_FILE = "ie_custom_font.ttf";
    private static final String EMOJI_FILE = "ie_custom_emoji.ttf";

    private static volatile Typeface userFont;
    private static volatile String loadedFrom;
    // Guards our own createFromFile(userPath) call from re-entering the hook (infinite recursion).
    private static final ThreadLocal<Boolean> loading = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public void install(ClassLoader cl) {
        installFontHooks(cl);
        installEmojiHook(cl);
    }

    private void installFontHooks(ClassLoader cl) {
        XC_MethodHook replace = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!(FeatureFlags.customFontEnabled || FeatureFlags.customEmojiEnabled)) return;
                if (Boolean.TRUE.equals(loading.get())) return; // our own load
                // Safeguard: don't replace an icon/glyph font.
                for (Object a : p.args) {
                    if (a instanceof String s) {
                        String low = s.toLowerCase();
                        if (low.contains("icon") || low.contains("glyph")) return;
                    } else if (a instanceof File f) {
                        String low = f.getName().toLowerCase();
                        if (low.contains("icon") || low.contains("glyph")) return;
                    }
                }
                Typeface tf = getActiveTypeface();
                if (tf != null) p.setResult(tf);
            }
        };
        try {
            XposedHelpers.findAndHookMethod(Typeface.class, "createFromAsset",
                    android.content.res.AssetManager.class, String.class, replace);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(Typeface.class, "createFromFile", File.class, replace);
        } catch (Throwable ignored) {}
        try {
            XposedHelpers.findAndHookMethod(Typeface.class, "createFromFile", String.class, replace);
        } catch (Throwable ignored) {}

        // Catch-all: many IG text views get their typeface via Typeface.create(...) or cached
        // repository typefaces, which the createFrom* hooks miss. Hook TextView.setTypeface to force
        // our font on EVERY text view (this build renders icons as vector drawables, not an icon
        // font, so replacing text typefaces app-wide is safe). beforeHook swaps the arg.
        XC_MethodHook setTf1 = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!(FeatureFlags.customFontEnabled || FeatureFlags.customEmojiEnabled) || Boolean.TRUE.equals(loading.get())) return;
                Typeface tf = getActiveTypeface();
                if (tf != null && p.args[0] != tf) p.args[0] = tf;
            }
        };
        try {
            XposedHelpers.findAndHookMethod(android.widget.TextView.class, "setTypeface", Typeface.class, setTf1);
        } catch (Throwable ignored) {}
        // setTypeface(Typeface, int) applies a style over a base family; force our font as the base
        // and let the style (bold/italic) be derived from it.
        XC_MethodHook setTf2 = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!(FeatureFlags.customFontEnabled || FeatureFlags.customEmojiEnabled) || Boolean.TRUE.equals(loading.get())) return;
                Typeface tf = getActiveTypeface();
                if (tf != null && p.args[0] != tf) p.args[0] = tf;
            }
        };
        try {
            XposedHelpers.findAndHookMethod(android.widget.TextView.class, "setTypeface", Typeface.class, int.class, setTf2);
        } catch (Throwable ignored) {}

        // Deepest catch-all: DM chats and post captions are Litho/Compose text, drawn via a Paint
        // (not a TextView), so the TextView hooks miss them. Force our font at Paint.setTypeface —
        // this reaches every text-drawing path in the app. (Icons are vector drawables on this
        // build, so this doesn't affect glyph rendering.)
        XC_MethodHook paintTf = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!(FeatureFlags.customFontEnabled || FeatureFlags.customEmojiEnabled) || Boolean.TRUE.equals(loading.get())) return;
                Typeface tf = getActiveTypeface();
                if (tf != null && p.args[0] != tf) p.args[0] = tf;
            }
        };
        try {
            XposedHelpers.findAndHookMethod(android.graphics.Paint.class, "setTypeface", Typeface.class, paintTf);
        } catch (Throwable ignored) {}

        // Receive the picked font file (launched from the module's "Pick font file" action).
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActivityResult",
                    int.class, int.class, Intent.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                int req = (int) p.args[0];
                                if (req != FONT_PICK_REQUEST && req != EMOJI_PICK_REQUEST) return;
                                if ((int) p.args[1] != Activity.RESULT_OK || p.args[2] == null) return;
                                android.net.Uri uri = ((Intent) p.args[2]).getData();
                                if (uri == null) return;
                                Activity act = (Activity) p.thisObject;
                                if (req == EMOJI_PICK_REQUEST) {
                                    if (saveEmoji(act, uri)) {
                                        FeatureFlags.customEmojiEnabled = true;
                                        SettingsManager.saveAllFlags();
                                        android.widget.Toast.makeText(act,
                                                "Emoji font saved — restart Instagram to apply", android.widget.Toast.LENGTH_LONG).show();
                                    } else {
                                        android.widget.Toast.makeText(act,
                                                "Couldn't load that font file", android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                    return;
                                }
                                if (saveFont(act, uri)) {
                                    FeatureFlags.customFontEnabled = true;
                                    SettingsManager.saveAllFlags();
                                    android.widget.Toast.makeText(act,
                                            "Font saved — restart Instagram to apply", android.widget.Toast.LENGTH_LONG).show();
                                } else {
                                    android.widget.Toast.makeText(act,
                                            "Couldn't load that font file", android.widget.Toast.LENGTH_SHORT).show();
                                }
                            } catch (Throwable t) { ModuleLog.line("(IE|Font) result: " + t); }
                        }
                    });
        } catch (Throwable ignored) {}

        if (FeatureFlags.customFontEnabled) FeatureStatusTracker.setHooked("CustomFont");
        ModuleLog.line("(IE|Font) ✅ installed");
    }

    private static Typeface getUserFont() {
        String path = FeatureFlags.customFontPath;
        if (path == null || path.isEmpty()) return null;
        if (userFont != null && path.equals(loadedFrom)) return userFont;
        try {
            loading.set(Boolean.TRUE);
            userFont = Typeface.createFromFile(path);
            loadedFrom = path;
        } catch (Throwable t) {
            ModuleLog.line("(IE|Font) load failed: " + t);
            userFont = null;
        } finally {
            loading.set(Boolean.FALSE);
        }
        return userFont;
    }

    // ── Combined text+emoji typeface (font-fallback method) ────────────────────
    private static Typeface combo;
    private static String comboKey;

    /**
     * The typeface to force on text. When custom emoji is on, this is a CustomFallbackBuilder
     * typeface: primary = the custom text font (or a system font) for normal text, with the emoji
     * font added as a fallback family so emoji codepoints render from it. When only custom font is
     * on, it's just the text font.
     */
    private static Typeface getActiveTypeface() {
        if (FeatureFlags.customEmojiEnabled && FeatureFlags.customEmojiPath != null
                && !FeatureFlags.customEmojiPath.isEmpty()) {
            return getCombo();
        }
        if (FeatureFlags.customFontEnabled) return getUserFont();
        return null;
    }

    private static Typeface getCombo() {
        String primaryPath = (FeatureFlags.customFontEnabled && FeatureFlags.customFontPath != null
                && !FeatureFlags.customFontPath.isEmpty()) ? FeatureFlags.customFontPath : systemFontPath();
        String key = primaryPath + "|" + FeatureFlags.customEmojiPath;
        if (combo != null && key.equals(comboKey)) return combo;
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                // Pre-29: no CustomFallbackBuilder — fall back to just the text font.
                combo = getUserFont();
            } else {
                android.graphics.fonts.Font primary = new android.graphics.fonts.Font.Builder(
                        new File(primaryPath)).build();
                android.graphics.fonts.Font emoji = new android.graphics.fonts.Font.Builder(
                        new File(FeatureFlags.customEmojiPath)).build();
                android.graphics.fonts.FontFamily primFam = new android.graphics.fonts.FontFamily.Builder(primary).build();
                android.graphics.fonts.FontFamily emojiFam = new android.graphics.fonts.FontFamily.Builder(emoji).build();
                combo = new Typeface.CustomFallbackBuilder(primFam)
                        .addCustomFallback(emojiFam)
                        .build();
            }
            comboKey = key;
        } catch (Throwable t) {
            ModuleLog.line("(IE|Emoji) combo build failed: " + t);
            combo = getUserFont();
        }
        return combo;
    }

    /** First available regular system font, used as the text base when no custom font is set. */
    private static String systemFontPath() {
        for (String p : new String[]{"/system/fonts/Roboto-Regular.ttf", "/system/fonts/NotoSans-Regular.ttf",
                "/system/fonts/DroidSans.ttf"}) {
            if (new File(p).exists()) return p;
        }
        return "/system/fonts/Roboto-Regular.ttf";
    }

    /** Copies the picked font Uri into the module's filesDir and records the path. */
    private static boolean saveFont(Activity act, android.net.Uri uri) {
        try {
            File out = new File(act.getFilesDir(), FONT_FILE);
            try (InputStream in = act.getContentResolver().openInputStream(uri);
                 FileOutputStream fo = new FileOutputStream(out)) {
                if (in == null) return false;
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
            // Validate it's a usable font before committing.
            Typeface test;
            try { loading.set(Boolean.TRUE); test = Typeface.createFromFile(out); }
            finally { loading.set(Boolean.FALSE); }
            if (test == null) return false;
            FeatureFlags.customFontPath = out.getAbsolutePath();
            userFont = test;
            loadedFrom = FeatureFlags.customFontPath;
            return true;
        } catch (Throwable t) {
            ModuleLog.line("(IE|Font) saveFont: " + t);
            return false;
        }
    }

    /** Launches the system font-file picker from the given Activity. */
    public static void launchPicker(Activity act) {
        launchPickerFor(act, FONT_PICK_REQUEST, "Pick a font (.ttf / .otf)");
    }

    /** Launches the picker for the emoji font. */
    public static void launchEmojiPicker(Activity act) {
        launchPickerFor(act, EMOJI_PICK_REQUEST, "Pick an EmojiCompat emoji font (.ttf)");
    }

    private static void launchPickerFor(Activity act, int req, String title) {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            act.startActivityForResult(Intent.createChooser(i, title), req);
        } catch (Throwable t) {
            android.widget.Toast.makeText(act, "Can't open file picker here", android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ── Custom emoji (EmojiCompat) ─────────────────────────────────────────────
    //
    // IG uses androidx.emoji2 (obfuscated). The emoji font is loaded lazily ~500ms after start by
    // the metadata loader X/00aC.EXz(callback). We replace that: build a MetadataRepo (X/00ae) from
    // the user's EmojiCompat-format font and hand it to the callback, bypassing the Google-Play font
    // download. The font MUST contain the EmojiCompat 'meta' table (a NotoColorEmojiCompat-style
    // build) — a plain emoji .ttf has no metadata and is rejected at pick time.

    /**
     * Font-fallback method: rather than feeding EmojiCompat a special metadata font (which requires
     * a NotoColorEmojiCompat-format file), we DISABLE EmojiCompat so it stops converting emoji into
     * its-own-font spans. Emoji then render through the normal text pipeline, where the emoji font
     * is added as a fallback family on the active typeface (see getCombo). This works with any plain
     * color-emoji .ttf (CBDT/CBLC + cmap), e.g. a raw Apple emoji font.
     *
     * Disable = force the metadata loader (X/00aC.EXz) to report failure (callback.A01), leaving
     * EmojiCompat in the FAILED state where process() is a no-op.
     */
    private void installEmojiHook(final ClassLoader cl) {
        try {
            Class<?> c00aC = XposedHelpers.findClass("X.00aC", cl);       // metadata loader
            final Class<?> c00Zv = XposedHelpers.findClass("X.00Zv", cl); // callback
            XposedHelpers.findAndHookMethod(c00aC, "EXz", c00Zv, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!FeatureFlags.customEmojiEnabled) return;
                    try {
                        XposedHelpers.callMethod(p.args[0], "A01", new Exception("ie: emoji via fallback")); // onFailed
                        ModuleLog.line("(IE|Emoji) EmojiCompat disabled — emoji via font fallback");
                    } catch (Throwable ignored) {}
                    p.setResult(null);
                }
            });
            if (FeatureFlags.customEmojiEnabled) FeatureStatusTracker.setHooked("CustomEmoji");
            ModuleLog.line("(IE|Emoji) ✅ hook installed");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Emoji) ⚠️ install: " + t.getMessage());
        }
    }

    /** Saves the picked emoji font (any loadable .ttf/.otf; rendered via the fallback typeface). */
    private static boolean saveEmoji(Activity act, android.net.Uri uri) {
        try {
            File out = new File(act.getFilesDir(), EMOJI_FILE);
            try (InputStream in = act.getContentResolver().openInputStream(uri);
                 FileOutputStream fo = new FileOutputStream(out)) {
                if (in == null) return false;
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) > 0) fo.write(buf, 0, n);
            }
            Typeface test;
            try { loading.set(Boolean.TRUE); test = Typeface.createFromFile(out); }
            finally { loading.set(Boolean.FALSE); }
            if (test == null) return false;
            FeatureFlags.customEmojiPath = out.getAbsolutePath();
            combo = null; // force rebuild with the new emoji font
            return true;
        } catch (Throwable t) {
            ModuleLog.line("(IE|Emoji) saveEmoji: " + t);
            return false;
        }
    }
}
