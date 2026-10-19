package ps.reso.instaeclipse.mods.feed;

import android.content.Context;
import android.text.InputType;
import android.widget.EditText;

import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Edit a post's likes / comments / reposts / shares, on this phone only (post ⋮ menu → "Edit
 * counts"). Nothing is sent to Instagram: the counts are kept per post id in
 * ie_post_counts.json (part of the data backup) and returned by the Media model's count getters,
 * which are found by their JSON field names' hash like the module's other model getters.
 */
public final class PostCountSpoof {

    private static final String TAG = "(IE|PostCounts) ";
    private static final String MEDIA = "com.instagram.feed.media.Media";
    public static final String FILE = "ie_post_counts.json";
    static final String[] FIELDS = {"likes", "comments", "reposts", "shares"};
    private static final String[][] KEYS = {
            {"like_count"},
            {"comment_count"},
            {"media_repost_count", "repost_count", "reposts_count"},
            {"reshare_count", "share_count", "shares_count"},
    };
    private static final ConcurrentHashMap<String, int[]> COUNTS = new ConcurrentHashMap<>();
    private static volatile boolean loaded;
    private static Method getId;

    private PostCountSpoof() {}

    public static void install(DexKitBridge bridge, ClassLoader cl) {
        StringBuilder log = new StringBuilder();
        int hooked = 0;
        try { getId = cl.loadClass(MEDIA).getMethod("getId"); } catch (Throwable t) { log.append(" no getId"); }
        for (int i = 0; i < KEYS.length; i++) {
            final int which = i;
            List<Method> found = new ArrayList<>();
            String cacheKey = "PostCountV3_" + FIELDS[i];
            List<Method> cached = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(cacheKey, cl) : null;
            if (cached != null && !cached.isEmpty()) found.addAll(cached);
            else {
                for (String key : KEYS[i]) {
                    for (String type : new String[]{"java.lang.Integer", "int", "java.lang.Long", "long"}) {
                        try {
                            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                                    .declaredClass(MEDIA).paramCount(0).returnType(type).usingNumbers(key.hashCode())))) {
                                if (!md.isMethod()) continue;
                                Method m = md.getMethodInstance(cl);
                                if (!Modifier.isStatic(m.getModifiers())) found.add(m);
                            }
                        } catch (Throwable ignored) { }
                    }
                    if (!found.isEmpty()) { log.append(" ").append(key); break; }
                }
                if (!found.isEmpty()) DexKitCache.saveMethods(cacheKey, found);
            }
            // The feed also reads counts through Media's int methods, which read the field by its
            // hash through a small static helper (likes add Instagram's optimistic like/unlike
            // state): find the helper by the hash, then Media's int method that calls it.
            if (!found.isEmpty() && cached == null) {
                try {
                    for (String key : KEYS[i])
                    for (MethodData helper : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                            .usingNumbers(key.hashCode()).returnType("int").paramCount(1)))) {
                        if (!helper.isMethod() || helper.getClassName().equals(MEDIA)) continue;
                        Method h = helper.getMethodInstance(cl);
                        if (Modifier.isStatic(h.getModifiers()) && !found.contains(h)) found.add(h);
                        for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                                .declaredClass(MEDIA).paramCount(0).returnType("int")
                                .addInvoke(MethodMatcher.create().declaredClass(helper.getClassName()).name(helper.getName()))))) {
                            if (!md.isMethod()) continue;
                            Method m = md.getMethodInstance(cl);
                            if (!Modifier.isStatic(m.getModifiers()) && !found.contains(m)) found.add(m);
                        }
                    }
                    DexKitCache.saveMethods(cacheKey, found);
                } catch (Throwable ignored) { }
            }
            for (Method m : found) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!FeatureFlags.postCountSpoof) return;
                        ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        Object media = param.thisObject != null ? param.thisObject : mediaIn(param.args);
                        int[] c = countsFor(media);
                        if (c == null || c[which] < 0) return;
                        if (param.getResult() instanceof Number real && param.thisObject != null) remember(media, which, real.intValue());
                        Class<?> t = ((Method) param.method).getReturnType();
                        param.setResult(t == long.class || t == Long.class ? (Object) (long) c[which] : (Object) c[which]);
                    }
                });
                log.append("=").append(m.getName());
                hooked++;
            }
            if (found.isEmpty()) log.append(" ").append(FIELDS[i]).append("=none");
        }
        // A post with edited counts shows them even when its counts are set to hidden.
        for (String key : new String[]{"like_and_view_counts_disabled", "is_like_and_view_counts_disabled",
                "share_count_disabled", "is_share_count_disabled", "hide_view_count"}) {
            try {
                for (String type : new String[]{"java.lang.Boolean", "boolean"})
                    for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                            .declaredClass(MEDIA).paramCount(0).returnType(type).usingNumbers(key.hashCode())))) {
                        if (!md.isMethod()) continue;
                        Method m = md.getMethodInstance(cl);
                        if (Modifier.isStatic(m.getModifiers())) continue;
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                if (!FeatureFlags.postCountSpoof || !Boolean.TRUE.equals(param.getResult())) return;
                                if (countsFor(param.thisObject) != null) param.setResult(Boolean.FALSE);
                            }
                        });
                        log.append(" ").append(key).append("=").append(m.getName());
                    }
            } catch (Throwable ignored) { }
        }
        hookCountViews();
        if (hooked > 0) FeatureStatusTracker.setHooked("PostCountSpoof");
        ModuleLog.line(TAG + (hooked > 0 ? "✅" : "❌") + " getters=" + hooked + log);
    }

    private static volatile Class<?> mediaClass;

    /** The post a static count helper is reading: a Media among its arguments or their fields. */
    private static Object mediaIn(Object[] args) {
        Class<?> mc = mediaClass;
        if (mc == null && getId != null) mediaClass = mc = getId.getDeclaringClass();
        if (mc == null || args == null) return null;
        for (Object a : args) {
            if (a == null) continue;
            if (mc.isInstance(a)) return a;
            for (Class<?> k = a.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                for (java.lang.reflect.Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(a);
                        if (mc.isInstance(v)) return v;
                    } catch (Throwable ignored) { }
                }
        }
        return null;
    }

    private static int[] countsFor(Object media) {
        ensureLoaded();
        if (getId == null || COUNTS.isEmpty()) return null;
        if (media == null) return null;
        try {
            Object id = getId.invoke(media);
            return id == null ? null : COUNTS.get(key(id.toString()));
        } catch (Throwable t) {
            return null;
        }
    }

    /** "pk_ownerid" and "pk" refer to the same post. */
    private static String key(String id) {
        int cut = id.indexOf('_');
        return cut > 0 ? id.substring(0, cut) : id;
    }

    // ----- the counts row under a post -----
    //
    // The row's numbers come from a view state that doesn't always re-read the model (a post you
    // liked, a count Instagram hides at 0). So when a post with edited counts is bound, the row's
    // count labels (the label right after the heart / comment / repost / send button) are set too.

    private static final String[] BUTTONS = {"row_feed_button_like", "row_feed_button_comment",
            "row_feed_button_repost", "row_feed_button_share"};
    private static final int[] BUTTON_IDS = new int[BUTTONS.length];
    private static final ThreadLocal<Boolean> SETTING = new ThreadLocal<>();

    private static void hookCountViews() {
        try {
            XC_MethodHook rebind = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.postCountSpoof || SETTING.get() == Boolean.TRUE) return;
                    ensureLoaded();
                    if (COUNTS.isEmpty()) return;
                    android.view.View v = (android.view.View) param.thisObject;
                    if (BUTTON_IDS[0] == 0) resolveIds(v);
                    // Bound before it is attached: look for its row once it is laid out.
                    if (v.getId() != BUTTON_IDS[0] && !(v instanceof android.widget.TextView)) return;
                    v.post(() -> {
                        android.view.ViewGroup row = rowOf(v);
                        if (row != null) applyRow(row);
                    });
                }
            };
            XposedBridge.hookAllMethods(android.view.View.class, "setContentDescription", rebind);
            XposedBridge.hookAllMethods(android.widget.TextView.class, "setText", rebind);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "row: " + t);
        }
    }

    private static void resolveIds(android.view.View v) {
        android.content.res.Resources r = v.getResources();
        for (int i = 0; i < BUTTONS.length; i++) {
            int id = r.getIdentifier(BUTTONS[i], "id", v.getContext().getPackageName());
            BUTTON_IDS[i] = id != 0 ? id : -1;
        }
    }

    /** The buttons row a view belongs to: the parent of the group holding the heart, or null. */
    private static android.view.ViewGroup rowOf(android.view.View v) {
        if (BUTTON_IDS[0] <= 0) return null;
        android.view.View heartGroup = null;
        if (v.getId() == BUTTON_IDS[0] && v.getParent() instanceof android.view.View g) heartGroup = g;
        else if (v instanceof android.widget.TextView && v.getParent() instanceof android.view.ViewGroup p) {
            for (int i = 0; i < p.getChildCount() && heartGroup == null; i++) {
                android.view.View c = p.getChildAt(i);
                if (c instanceof android.view.ViewGroup g) {
                    android.view.View heart = g.findViewById(BUTTON_IDS[0]);
                    if (heart != null && heart.getParent() == g) heartGroup = g;
                }
            }
        }
        return heartGroup != null && heartGroup.getParent() instanceof android.view.ViewGroup row ? row : null;
    }

    private static void applyRow(android.view.ViewGroup row) {
        try {
            int[] c = countsFor(mediaForView(row));
            if (c == null) c = byContent(row);
            if (c == null) return;
            for (int i = 0; i < row.getChildCount() - 1; i++) {
                android.view.View child = row.getChildAt(i);
                for (int k = 0; k < BUTTON_IDS.length; k++) {
                    if (BUTTON_IDS[k] <= 0 || c[k] < 0 || child.findViewById(BUTTON_IDS[k]) == null) continue;
                    if (!(row.getChildAt(i + 1) instanceof android.widget.TextView label)) continue;
                    String text = ps.reso.instaeclipse.mods.ui.ProfileSpoofHook.shortCount(c[k]);
                    if (label.getVisibility() != android.view.View.VISIBLE) label.setVisibility(android.view.View.VISIBLE);
                    fitWidth(row, label, text);
                    if (!text.contentEquals(label.getText())) {
                        SETTING.set(Boolean.TRUE);
                        try { label.setText(text); } finally { SETTING.remove(); }
                        fitWidth(row, label, text);
                    }
                }
            }
        } catch (Throwable ignored) { }
    }

    /**
     * The row is laid out by Litho for the real numbers, so a longer number would be cut: widen
     * the label to fit and move the buttons after it along.
     */
    private static void fitWidth(android.view.ViewGroup row, android.widget.TextView label, String text) {
        int need = (int) Math.ceil(label.getPaint().measureText(text)) + label.getPaddingLeft() + label.getPaddingRight();
        int have = label.getRight() - label.getLeft();
        if (need == have) return;
        int idx = row.indexOfChild(label);
        int left = label.getLeft(), top = label.getTop(), bottom = label.getBottom();
        if (have <= 0 && idx > 0) { // a count Instagram hid (0): place it after its button
            android.view.View button = row.getChildAt(idx - 1);
            left = button.getRight();
            top = button.getTop();
            bottom = button.getBottom();
            have = 0;
        }
        int delta = need - have;
        label.layout(left, top, left + need, bottom);
        int saveId = row.getResources().getIdentifier("row_feed_button_save", "id", row.getContext().getPackageName());
        for (int i = idx + 1; i < row.getChildCount(); i++) {
            android.view.View v = row.getChildAt(i);
            if (v.getId() == saveId && saveId != 0) continue; // the save button stays on the right
            v.offsetLeftAndRight(delta);
        }
    }

    /** Real counts of edited posts, as Instagram's models report them: key -> {likes, comments,
     *  reposts, shares (-1 unknown), last seen}. Used to recognize an edited post's row. */
    private static final ConcurrentHashMap<String, long[]> REAL = new ConcurrentHashMap<>();

    private static void remember(Object media, int which, int real) {
        try {
            String k = key(String.valueOf(getId.invoke(media)));
            long[] v = REAL.computeIfAbsent(k, x -> new long[]{-1, -1, -1, -1, 0});
            v[which] = real;
            v[4] = System.currentTimeMillis();
        } catch (Throwable ignored) { }
    }

    /**
     * The edited post a row shows, by what it shows: the post's own author is you and its shown
     * counts are that post's real counts. When several match, the one bound last.
     */
    private static int[] byContent(android.view.ViewGroup row) {
        String self = ps.reso.instaeclipse.mods.ui.ProfileSpoofHook.knownUsername();
        android.view.View item = row;
        for (int i = 0; i < 8 && item.getParent() instanceof android.view.View p; i++) item = p;
        int nameId = row.getResources().getIdentifier("row_feed_photo_profile_name", "id", row.getContext().getPackageName());
        android.view.View name = nameId != 0 ? item.findViewById(nameId) : null;
        if (self != null && name instanceof android.widget.TextView tv && tv.getText() != null
                && !tv.getText().toString().trim().equalsIgnoreCase(self)) return null;
        String[] shown = new String[BUTTON_IDS.length];
        for (int i = 0; i < row.getChildCount() - 1; i++)
            for (int k = 0; k < BUTTON_IDS.length; k++)
                if (BUTTON_IDS[k] > 0 && row.getChildAt(i).findViewById(BUTTON_IDS[k]) != null
                        && row.getChildAt(i + 1) instanceof android.widget.TextView label)
                    shown[k] = label.getVisibility() == android.view.View.VISIBLE && label.getText() != null ? label.getText().toString() : "";
        String best = null;
        long bestTime = -1;
        for (java.util.Map.Entry<String, long[]> e : REAL.entrySet()) {
            if (!COUNTS.containsKey(e.getKey())) continue;
            long[] v = e.getValue();
            boolean ok = true;
            for (int k = 0; k < 4 && ok; k++) {
                if (shown[k] == null || v[k] < 0) continue;
                String real = v[k] == 0 ? "" : ps.reso.instaeclipse.mods.ui.ProfileSpoofHook.shortCount((int) v[k]);
                String ours = COUNTS.get(e.getKey())[k] < 0 ? null : ps.reso.instaeclipse.mods.ui.ProfileSpoofHook.shortCount(COUNTS.get(e.getKey())[k]);
                ok = shown[k].equals(real) || shown[k].equals(ours) || (v[k] == 0 && shown[k].equals("0"));
            }
            if (ok && v[4] > bestTime) { best = e.getKey(); bestTime = v[4]; }
        }
        return best == null ? null : COUNTS.get(best);
    }

    /** The post a feed row shows: a Media held by the row's views (their tags or fields). */
    private static Object mediaForView(android.view.View v) {
        Class<?> mc = mediaClass;
        if (mc == null && getId != null) mediaClass = mc = getId.getDeclaringClass();
        if (mc == null) return null;
        for (android.view.View x = v; x != null; x = x.getParent() instanceof android.view.View p ? p : null) {
            Object m = mediaInObject(x.getTag(), mc, 2);
            if (m != null) return m;
            if (!x.getClass().getName().startsWith("android.")) {
                m = mediaInObject(x, mc, 2);
                if (m != null) return m;
            }
            // The list item: its ViewHolder holds the bound post.
            if (x.getParent() instanceof android.view.ViewGroup list && isRecyclerView(list.getClass())) {
                try {
                    Object holder = list.getClass().getMethod("getChildViewHolder", android.view.View.class).invoke(list, x);
                    return mediaInObject(holder, mc, 3);
                } catch (Throwable ignored) { return null; }
            }
        }
        return null;
    }

    private static boolean isRecyclerView(Class<?> c) {
        for (; c != null; c = c.getSuperclass()) if (c.getName().equals("androidx.recyclerview.widget.RecyclerView")) return true;
        return false;
    }

    private static Object mediaInObject(Object o, Class<?> mc, int depth) {
        if (o == null) return null;
        if (mc.isInstance(o)) return o;
        if (depth == 0) return null;
        String cn = o.getClass().getName();
        if (!(cn.startsWith("X.") || cn.startsWith("com.instagram.") || cn.startsWith("androidx.recyclerview."))) return null;
        for (Class<?> k = o.getClass(); k != null && k != Object.class && !k.getName().startsWith("android."); k = k.getSuperclass())
            for (java.lang.reflect.Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(k)) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                try {
                    f.setAccessible(true);
                    Object m = mediaInObject(f.get(o), mc, depth - 1);
                    if (m != null) return m;
                } catch (Throwable ignored) { }
            }
        return null;
    }

    // ----- storage -----

    private static File file() {
        Context c = ps.reso.instaeclipse.xposedcompat.AndroidAppHelper.currentApplication();
        return c == null ? null : new File(c.getFilesDir(), FILE);
    }

    private static volatile long checkedAt, fileTime = -1;

    /** Reads the saved counts, again whenever the file changed (a restore, another process). */
    private static void ensureLoaded() {
        long now = System.currentTimeMillis();
        if (loaded && now - checkedAt < 1000) return;
        synchronized (PostCountSpoof.class) {
            checkedAt = now;
            File f = file();
            if (f == null) return;
            long t = f.exists() ? f.lastModified() : 0;
            if (loaded && t == fileTime) return;
            loaded = true;
            fileTime = t;
            COUNTS.clear();
            if (t == 0) return;
            try {
                JSONObject o = new JSONObject(new String(new android.util.AtomicFile(f).readFully(), StandardCharsets.UTF_8));
                for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) {
                    String k = it.next();
                    JSONObject v = o.getJSONObject(k);
                    int[] c = new int[FIELDS.length];
                    for (int i = 0; i < FIELDS.length; i++) c[i] = v.optInt(FIELDS[i], -1);
                    COUNTS.put(k, c);
                }
            } catch (Throwable e) {
                ModuleLog.line(TAG + "could not read saved counts");
            }
        }
    }

    private static synchronized void save() {
        File f = file();
        if (f == null) return;
        android.util.AtomicFile atomic = new android.util.AtomicFile(f);
        java.io.FileOutputStream out = null;
        try {
            JSONObject o = new JSONObject();
            for (java.util.Map.Entry<String, int[]> e : COUNTS.entrySet()) {
                JSONObject v = new JSONObject();
                for (int i = 0; i < FIELDS.length; i++) if (e.getValue()[i] >= 0) v.put(FIELDS[i], e.getValue()[i]);
                o.put(e.getKey(), v);
            }
            out = atomic.startWrite();
            out.write(o.toString().getBytes(StandardCharsets.UTF_8));
            atomic.finishWrite(out);
            fileTime = f.lastModified();
        } catch (Throwable t) {
            atomic.failWrite(out);
            ModuleLog.line(TAG + "could not save counts");
        }
    }

    // ----- editor (post ⋮ menu) -----

    public static void edit(Context ctx, Object media) {
        String id;
        try { id = key(String.valueOf(getId.invoke(media))); } catch (Throwable t) { return; }
        ensureLoaded();
        int[] cur = COUNTS.get(id);
        IeDialog dlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_post_counts_title))
                .message(I18n.t(ctx, R.string.ig_post_counts_hint));
        int[] labels = {R.string.ig_post_counts_likes, R.string.ig_post_counts_comments,
                R.string.ig_post_counts_reposts, R.string.ig_post_counts_shares};
        EditText[] inputs = new EditText[FIELDS.length];
        for (int i = 0; i < FIELDS.length; i++) {
            android.widget.TextView label = new android.widget.TextView(dlg.ctx);
            label.setText(I18n.t(ctx, labels[i]));
            label.setTextColor(dlg.sub);
            label.setTextSize(13);
            dlg.view(label);
            inputs[i] = dlg.input(I18n.t(ctx, R.string.ig_post_counts_keep));
            inputs[i].setInputType(InputType.TYPE_CLASS_NUMBER);
            inputs[i].setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10)});
            if (cur != null && cur[i] >= 0) inputs[i].setText(String.valueOf(cur[i]));
        }
        dlg.secondary(I18n.t(ctx, R.string.ig_post_counts_reset), () -> { COUNTS.remove(id); save(); });
        dlg.primary(ctx.getString(android.R.string.ok), () -> {
            int[] c = new int[FIELDS.length];
            boolean any = false;
            for (int i = 0; i < FIELDS.length; i++) {
                c[i] = ps.reso.instaeclipse.utils.dialog.DialogUtils.parseSpoofCount(inputs[i].getText().toString());
                any |= c[i] >= 0;
            }
            if (any) COUNTS.put(id, c); else COUNTS.remove(id);
            save();
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_post_counts_saved),
                    android.widget.Toast.LENGTH_SHORT).show();
        });
        dlg.show();
    }
}
