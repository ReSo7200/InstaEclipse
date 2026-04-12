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

public class StoryMentionHook {



    // Resolved once: MediaExtKt.A1s(Media) → List<User>
    // DexKit anchor: only static (Media)→List method on MediaExtKt that accesses a field
    // declared on com.instagram.reels.interactive.Interactive (field A1I of type User).
    private static volatile Method mentionGetterMethod = null;
    private static final List<Method> mentionGetterCandidates = new ArrayList<>();

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Entry point ──────────────────────────────────────────────────────────

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        resolveMentionGetter(bridge, classLoader);
        installButtonHook(bridge, classLoader);
        installClickHook(bridge, classLoader);
        FeatureStatusTracker.setHooked("StoryMentions");
    }

    // ── DexKit: resolve the mention getter ───────────────────────────────────
    //
    // Targets the only static (Media)→List method on MediaExtKt that reads a field
    // declared on com.instagram.reels.interactive.Interactive. Found via usingFields
    // on Interactive — cleaner than depending on a specific call-site string.

    private static void resolveMentionGetter(DexKitBridge bridge, ClassLoader classLoader) {
        // Cache hit
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("MentionGetter", classLoader);
            if (cached != null && !cached.isEmpty()) {
                mentionGetterCandidates.addAll(cached);
                mentionGetterMethod = mentionGetterCandidates.get(0);
                XposedBridge.log("(IE|Mention) ✅ " + cached.size() + " candidate(s) from cache");
                return;
            }
        }

        try {
            List<MethodData> results = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass("com.instagram.feed.media.MediaExtKt")
                            .returnType("java.util.List")
                            .paramCount(1)
                            .usingStrings("Required value was null.")
                    ));

            for (MethodData md : results) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    m.setAccessible(true);
                    mentionGetterCandidates.add(m);
                } catch (Throwable ignored) {}
            }

            if (!mentionGetterCandidates.isEmpty()) {
                mentionGetterMethod = mentionGetterCandidates.get(0);
                DexKitCache.saveMethods("MentionGetter", mentionGetterCandidates);
                XposedBridge.log("(IE|Mention) ✅ " + mentionGetterCandidates.size() + " candidate(s) loaded");
            } else {
                XposedBridge.log("(IE|Mention) ❌ mentionGetter not found");
            }
        } catch (Throwable t) {
            XposedBridge.log("(IE|Mention) ❌ resolveMentionGetter: " + t);
        }
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
                XposedBridge.log("(IE|Mention) ❌ button hook DexKit: " + t);
            }
        }

        if (method == null) {
            XposedBridge.log("(IE|Mention) ❌ button builder not found");
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
            XposedBridge.log("(IE|Mention) ✅ button hook installed");
        } catch (Throwable t) {
            XposedBridge.log("(IE|Mention) ❌ button hook: " + t);
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
                    XposedBridge.log("(IE|Mention) ❌ click handler not found");
                    return;
                }
                method = methods.get(0).getMethodInstance(classLoader);
                DexKitCache.saveMethod("MentionClick", method);
            } catch (Throwable t) {
                XposedBridge.log("(IE|Mention) ❌ click hook DexKit: " + t);
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

                        if (ctx == null) { XposedBridge.log("(IE|Mention) ❌ context not found"); return; }
                        if (media == null) { XposedBridge.log("(IE|Mention) ❌ Media not found"); return; }

                        showMentionsDialog(ctx, resolveMentions(media));
                    } catch (Throwable t) {
                        XposedBridge.log("(IE|Mention) ❌ click handler: " + t);
                    }
                }
            });
            XposedBridge.log("(IE|Mention) ✅ click hook installed");
        } catch (Throwable t) {
            XposedBridge.log("(IE|Mention) ❌ click hook: " + t);
        }
    }

    // ── Mention extraction ────────────────────────────────────────────────────

    // media is already resolved by the caller — passed in directly
    private static List<String> resolveMentions(Object media) {
        List<String> usernames = new ArrayList<>();
        try {
            if (mentionGetterCandidates.isEmpty()) {
                XposedBridge.log("(IE|Mention) ❌ no mentionGetter candidates");
                return usernames;
            }

            // On first real call, probe all candidates and pin the one returning User objects.
            // A1n returns List<String> user IDs; A1o returns List<User> with usernames.
            if (mentionGetterMethod == null || mentionGetterMethod == mentionGetterCandidates.get(0)) {
                for (Method candidate : mentionGetterCandidates) {
                    try {
                        Object probe = candidate.invoke(null, media);
                        if (!(probe instanceof List<?> list) || list.isEmpty()) continue;
                        Object first = list.get(0);
                        if (first != null && !(first instanceof String)) {
                            // Found the User-returning method — pin it
                            mentionGetterMethod = candidate;
                            XposedBridge.log("(IE|Mention) ✅ pinned to " + candidate.getName() + " (returns User objects)");
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }

            Object result = mentionGetterMethod.invoke(null, media);
            if (!(result instanceof List<?> list)) return usernames;

            for (Object item : list) {
                if (item == null) continue;
                String username = (item instanceof String s) ? null : UserUtils.callUsernameGetter(item);
                if (username != null && !username.isEmpty()) usernames.add(username);
            }
        } catch (Throwable t) {
            XposedBridge.log("(IE|Mention) resolveMentions exception: " + t);
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
                XposedBridge.log("(IE|Mention) ❌ showMentionsDialog: " + t);
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
