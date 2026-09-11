package ps.reso.instaeclipse.mods.misc;

import android.app.AndroidAppHelper;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.users.UserUtils;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class StoryMentionHook {



    // Story @mentions live behind a two-step pipeline, not a single Media-level getter:
    //   Media -> LiveTreeMediaDict (found by type) -> "reel_mentions" field (raw tree entries)
    //   -> a converter method that turns each raw entry into an Interactive sticker object,
    //      with the actual mentioned User stored in one of Interactive's own fields.
    // Anchoring on the "reel_mentions" JSON key and the converter's own debug string is far
    // more stable across IG versions than matching a Media-level method's signature — those
    // getters get refactored/renamed/re-typed constantly (see git history of this file), but
    // literal JSON field names and hardcoded log/QPL strings don't change on a version bump.
    private static volatile Method rawMentionsGetter;   // LiveTreeMediaDict -> List (raw entries)
    private static volatile Method mentionsConverter;   // List(raw) -> List<Interactive w/ User field>

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Entry point ──────────────────────────────────────────────────────────

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        resolveMentionPipeline(bridge, classLoader);
        installButtonHook(bridge, classLoader);
        installClickHook(bridge, classLoader);
        FeatureStatusTracker.setHooked("StoryMentions");
    }

    // ── DexKit: resolve the two-step mention pipeline ────────────────────────

    private static void resolveMentionPipeline(DexKitBridge bridge, ClassLoader classLoader) {
        if (DexKitCache.isCacheValid()) {
            Method g = DexKitCache.loadMethod("MentionsRawGetter", classLoader);
            Method c = DexKitCache.loadMethod("MentionsConverter", classLoader);
            if (g != null && c != null) {
                rawMentionsGetter = g;
                mentionsConverter = c;
                ModuleLog.line("(IE|Mention) ✅ pipeline resolved from cache");
                return;
            }
        }

        try {
            // The paramCount-0 List getter guarded by the "reel_mentions" JSON key used to live
            // on LiveTreeMediaDict (<=436), but IG 442+ folded the live-tree model into
            // com.instagram.feed.media.Media, so the same getter now sits directly on Media.
            // Try the current home first, then the legacy class. Only stable
            // com.instagram.feed.media.* class names + the JSON key are used — never an X.* name.
            String[] mediaClasses = {
                    "com.instagram.feed.media.Media",             // 442+ (447: Media reel_mentions getter)
                    "com.instagram.feed.media.LiveTreeMediaDict"  // 436 and earlier
            };
            outer:
            for (String cls : mediaClasses) {
                List<MethodData> getters;
                try {
                    getters = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .declaredClass(cls)
                                    .paramCount(0)
                                    .usingEqStrings(List.of("reel_mentions"))));
                } catch (Throwable ignored) {
                    continue; // class absent on this build
                }
                for (MethodData md : getters) {
                    if (md.getName().equals("<clinit>")) continue;
                    try {
                        Method m = md.getMethodInstance(classLoader);
                        if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                        m.setAccessible(true);
                        rawMentionsGetter = m;
                        DexKitCache.saveMethod("MentionsRawGetter", m);
                        break outer;
                    } catch (Throwable ignored) {}
                }
            }
            if (rawMentionsGetter == null) ModuleLog.line("(IE|Mention) ❌ rawMentionsGetter not found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Mention) ❌ rawMentionsGetter query failed: " + t);
        }

        try {
            List<MethodData> converters = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramCount(1)
                            .usingEqStrings(List.of("MentionTappableObject.user is null; dropping mention sticker"))));
            for (MethodData md : converters) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                    m.setAccessible(true);
                    mentionsConverter = m;
                    DexKitCache.saveMethod("MentionsConverter", m);
                    break;
                } catch (Throwable ignored) {}
            }
            if (mentionsConverter == null) ModuleLog.line("(IE|Mention) ❌ mentionsConverter not found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Mention) ❌ mentionsConverter query failed: " + t);
        }

        if (rawMentionsGetter != null && mentionsConverter != null) {
            ModuleLog.line("(IE|Mention) ✅ pipeline resolved: " + rawMentionsGetter.getName() + " -> " + mentionsConverter.getName());
        }
    }

    // Walks an object's declared fields (and superclasses) for the first one whose exact
    // declared type matches typeName. Used to find LiveTreeMediaDict on Media, and the
    // mentioned User inside an Interactive sticker, without depending on obfuscated field names.
    private static Object findFieldByType(Object obj, String typeName) {
        if (obj == null) return null;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType().getName().equals(typeName)) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(obj);
                        if (v != null) return v;
                    } catch (Throwable ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // ── Hook 1: append "View Mentions" to the story options list ─────────────
    //
    // Same anchor as StoryDownloadHook — CharSequence[] builder with "[INTERNAL] Pause Playback".
    // Xposed stacks hooks, so both run independently on the same method.

    private void installButtonHook(DexKitBridge bridge, ClassLoader classLoader) {
        Method method = null;

        if (DexKitCache.isCacheValid()) {
            method = DexKitCache.loadMethod("MentionButton", classLoader);
        }

        if (method == null) {
            try {
                List<MethodData> methods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .usingStrings("[INTERNAL] Pause Playback")
                                .paramCount(1)));

                for (MethodData md : methods) {
                    try {
                        Method m = md.getMethodInstance(classLoader);
                        if (m.getReturnType().isArray() &&
                                CharSequence.class.isAssignableFrom(m.getReturnType().getComponentType())) {
                            method = m;
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                ModuleLog.line("(IE|Mention) ❌ button hook DexKit: " + t);
            }
        }

        if (method == null) {
            ModuleLog.line("(IE|Mention) ❌ button builder not found");
            return;
        }
        DexKitCache.saveMethod("MentionButton", method);

        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.enableStoryMentions) return;
                    CharSequence[] original = (CharSequence[]) param.getResult();
                    if (original == null) return;
                    String mentionLabel = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_btn_view_mentions);
                    for (CharSequence cs : original) {
                        if (mentionLabel.contentEquals(cs)) return;
                    }
                    CharSequence[] extended = new CharSequence[original.length + 1];
                    System.arraycopy(original, 0, extended, 0, original.length);
                    extended[original.length] = mentionLabel;
                    param.setResult(extended);
                }
            });
            ModuleLog.line("(IE|Mention) ✅ button hook installed");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Mention) ❌ button hook: " + t);
        }
    }

    // ── Hook 2: handle "View Mentions" tap ───────────────────────────────────
    //
    // Same anchor as StoryDownloadHook click handler. We intercept only our label;
    // all other taps pass through to Instagram and to the StoryDownloadHook.

    private void installClickHook(DexKitBridge bridge, ClassLoader classLoader) {
        Method method = null;

        if (DexKitCache.isCacheValid()) {
            method = DexKitCache.loadMethod("MentionClick", classLoader);
        }

        if (method == null) {
            try {
                List<MethodData> methods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .returnType("void")
                                .usingStrings("explore_viewer",
                                        "friendships/mute_friend_reel/%s/",
                                        "[INTERNAL] Pause Playback")));
                if (methods.isEmpty()) {
                    ModuleLog.line("(IE|Mention) ❌ click handler not found");
                    return;
                }
                method = methods.get(0).getMethodInstance(classLoader);
                DexKitCache.saveMethod("MentionClick", method);
            } catch (Throwable t) {
                ModuleLog.line("(IE|Mention) ❌ click hook DexKit: " + t);
                return;
            }
        }

        try {
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!FeatureFlags.enableStoryMentions) return;

                        CharSequence tapped = null;
                        for (Object a : param.args) {
                            if (a instanceof CharSequence cs && tapped == null) tapped = cs;
                        }
                        String mentionLabel = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_btn_view_mentions);
                        if (tapped == null || !mentionLabel.contentEquals(tapped)) return;

                        param.setResult(null); // consume event

                        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
                        Object media = null;
                        Context ctx = null;

                        if (param.thisObject != null) {
                            media = findMediaInGraph(param.thisObject, 0, visited);
                            ctx = findContext(param.thisObject);
                        }
                        for (Object a : param.args) {
                            if (a == null) continue;
                            if (media == null) media = findMediaInGraph(a, 0, visited);
                            if (ctx == null) ctx = findContext(a);
                        }

                        if (ctx == null) { ModuleLog.line("(IE|Mention) ❌ context not found"); return; }
                        if (media == null) { ModuleLog.line("(IE|Mention) ❌ Media not found"); return; }

                        showMentionsDialog(ctx, resolveMentions(media));
                    } catch (Throwable t) {
                        ModuleLog.line("(IE|Mention) ❌ click handler: " + t);
                    }
                }
            });
            ModuleLog.line("(IE|Mention) ✅ click hook installed");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Mention) ❌ click hook: " + t);
        }
    }

    // ── Mention extraction ────────────────────────────────────────────────────

    // media is already resolved by the caller — passed in directly
    private static List<String> resolveMentions(Object media) {
        List<String> usernames = new ArrayList<>();
        try {
            if (rawMentionsGetter == null || mentionsConverter == null) {
                ModuleLog.line("(IE|Mention) ❌ mention pipeline not resolved");
                return usernames;
            }

            // The getter may be declared directly on Media (442+) or on a sub-dict field of
            // Media (<=436). Derive the receiver from the resolved getter's own declaring class
            // instead of hardcoding a class name.
            Class<?> owner = rawMentionsGetter.getDeclaringClass();
            Object receiver = owner.isInstance(media) ? media : findFieldByType(media, owner.getName());
            if (receiver == null) {
                ModuleLog.line("(IE|Mention) ❌ mention receiver (" + owner.getName() + ") not found on media");
                return usernames;
            }

            Object rawResult = rawMentionsGetter.invoke(receiver);
            if (!(rawResult instanceof List<?> raw) || raw.isEmpty()) return usernames;

            Object convertedResult = mentionsConverter.invoke(null, raw);
            if (!(convertedResult instanceof List<?> list)) return usernames;

            for (Object item : list) {
                if (item == null) continue;
                // The converter returns Interactive stickers, not raw Users — the mentioned
                // User lives in one of Interactive's own fields (unless a future version
                // returns Users directly, which this also handles).
                Object user = item.getClass().getName().equals("com.instagram.user.model.User")
                        ? item : findFieldByType(item, "com.instagram.user.model.User");
                if (user == null) continue;
                String username = UserUtils.callUsernameGetter(user);
                if (username != null && !username.isEmpty()) usernames.add(username);
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|Mention) resolveMentions exception: " + t);
        }
        return usernames;
    }

    // Recursively walk fields (including Object-typed ones) to find a Media instance.
    // Checks runtime class name, not declared field type, so it works through Object fields.
    private static final int GRAPH_MAX_DEPTH = 6;

    private static Object findMediaInGraph(Object obj, int depth, Set<Object> visited) {
        if (obj == null || depth > GRAPH_MAX_DEPTH) return null;
        if (!visited.add(obj)) return null;

        String className = obj.getClass().getName();
        if (!className.startsWith("com.instagram.") &&
                !className.startsWith("com.facebook.") &&
                !className.startsWith("X.")) return null;

        if (className.equals("com.instagram.feed.media.Media")) return obj;

        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft.isArray()) continue;
                f.setAccessible(true);
                Object val;
                try { val = f.get(obj); } catch (Throwable ignored) { continue; }
                if (val == null) continue;

                String vn = val.getClass().getName();
                if (vn.equals("com.instagram.feed.media.Media")) return val;
                if (vn.startsWith("com.instagram.") || vn.startsWith("com.facebook.") || vn.startsWith("X.")) {
                    Object found = findMediaInGraph(val, depth + 1, visited);
                    if (found != null) return found;
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // ── Bottom sheet dialog ───────────────────────────────────────────────────

    private static void showMentionsDialog(Context ctx, List<String> usernames) {
        mainHandler.post(() -> {
            try {
                float dp   = ctx.getResources().getDisplayMetrics().density;
                boolean dk = (ctx.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

                int sheetBg    = dk ? Color.parseColor("#1C1C1E") : Color.parseColor("#F2F2F7");
                int cardBg     = dk ? Color.parseColor("#2C2C2E") : Color.parseColor("#FFFFFF");
                int textPrim   = dk ? Color.WHITE                 : Color.parseColor("#1C1C1E");
                int textSec    = dk ? Color.parseColor("#AEAEB2") : Color.parseColor("#6C6C70");
                int accentBg   = Color.parseColor("#0A84FF");
                int handleClr  = dk ? Color.parseColor("#48484A") : Color.parseColor("#C7C7CC");

                LinearLayout sheet = new LinearLayout(ctx);
                sheet.setOrientation(LinearLayout.VERTICAL);
                sheet.setBackground(roundRect(sheetBg, 20, ctx, dp));
                int hPad = (int)(20 * dp);
                sheet.setPadding(hPad, (int)(12 * dp), hPad, (int)(28 * dp));

                // Drag handle
                View handle = new View(ctx);
                LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                        (int)(40 * dp), (int)(4 * dp));
                handleLp.gravity = Gravity.CENTER_HORIZONTAL;
                handleLp.bottomMargin = (int)(16 * dp);
                handle.setLayoutParams(handleLp);
                handle.setBackground(roundRect(handleClr, 2, ctx, dp));
                sheet.addView(handle);

                // Title
                TextView title = new TextView(ctx);
                title.setText(I18n.t(ctx, R.string.ig_mention_dialog_title));
                title.setTextColor(textPrim);
                title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                title.setTypeface(null, Typeface.BOLD);
                LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                titleLp.bottomMargin = (int)(4 * dp);
                title.setLayoutParams(titleLp);
                sheet.addView(title);

                // Subtitle
                TextView subtitle = new TextView(ctx);
                subtitle.setText(usernames.isEmpty()
                        ? I18n.t(ctx, R.string.ig_mention_no_mentions)
                        : I18n.t(ctx, R.string.ig_mention_subtitle, usernames.size()));
                subtitle.setTextColor(textSec);
                subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                subLp.bottomMargin = (int)(14 * dp);
                subtitle.setLayoutParams(subLp);
                sheet.addView(subtitle);

                Dialog dialog = new Dialog(ctx);
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

                if (!usernames.isEmpty()) {
                    // Scrollable username list
                    ScrollView scroll = new ScrollView(ctx);
                    scroll.setLayoutParams(new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT));

                    LinearLayout list = new LinearLayout(ctx);
                    list.setOrientation(LinearLayout.VERTICAL);

                    for (String username : usernames) {
                        TextView row = new TextView(ctx);
                        row.setText("@" + username);
                        row.setTextColor(textPrim);
                        row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                        row.setTypeface(null, Typeface.BOLD);
                        int rowPad = (int)(14 * dp);
                        row.setPadding(rowPad, rowPad, rowPad, rowPad);
                        row.setBackground(roundRect(cardBg, 12, ctx, dp));
                        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                                LinearLayout.LayoutParams.MATCH_PARENT,
                                LinearLayout.LayoutParams.WRAP_CONTENT);
                        rowLp.bottomMargin = (int)(8 * dp);
                        row.setLayoutParams(rowLp);
                        row.setOnClickListener(v -> {
                            ClipboardManager cm = (ClipboardManager)
                                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("username", username));
                                Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_mention_copied, username), Toast.LENGTH_SHORT).show();
                            }
                        });
                        list.addView(row);
                    }
                    scroll.addView(list);
                    sheet.addView(scroll);

                    // Copy all button (only shown when more than one mention)
                    if (usernames.size() > 1) {
                        Button btnAll = makePillButton(ctx, I18n.t(ctx, R.string.ig_mention_copy_all), accentBg, Color.WHITE, dp);
                        btnAll.setOnClickListener(v -> {
                            dialog.dismiss();
                            StringBuilder sb = new StringBuilder();
                            for (String u : usernames) sb.append("@").append(u).append("\n");
                            ClipboardManager cm = (ClipboardManager)
                                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(ClipData.newPlainText("mentions", sb.toString().trim()));
                                Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_all_mentions_copied), Toast.LENGTH_SHORT).show();
                            }
                        });
                        sheet.addView(btnAll);
                    }
                }

                dialog.setContentView(sheet);
                Window w = dialog.getWindow();
                if (w != null) {
                    w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    w.setGravity(Gravity.BOTTOM);
                    w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.WRAP_CONTENT);
                    WindowManager.LayoutParams wlp = w.getAttributes();
                    int margin = (int)(12 * dp);
                    wlp.x = margin;
                    wlp.y = margin;
                    w.setAttributes(wlp);
                }
                dialog.show();

            } catch (Throwable t) {
                ModuleLog.line("(IE|Mention) ❌ showMentionsDialog: " + t);
            }
        });
    }

    // ── UI helpers ────────────────────────────────────────────────────────────

    private static GradientDrawable roundRect(int color, float radiusDp, Context ctx, float dp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * dp);
        return d;
    }

    private static Button makePillButton(Context ctx, String label,
                                          int bgColor, int textColor, float dp) {
        Button btn = new Button(ctx);
        btn.setText(label);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setBackground(roundRect(bgColor, 14, ctx, dp));
        btn.setAllCaps(false);
        btn.setPadding((int)(20 * dp), (int)(14 * dp), (int)(20 * dp), (int)(14 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int)(10 * dp);
        btn.setLayoutParams(lp);
        return btn;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Context findContext(Object obj) {
        if (obj == null) return null;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (Context.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    try {
                        Object v = f.get(obj);
                        if (v instanceof Context c) return c;
                    } catch (Throwable ignored) {}
                }
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

}
