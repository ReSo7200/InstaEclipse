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
            String cacheKey = "PostCountV2_" + FIELDS[i];
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
                        int[] c = countsFor(param.thisObject);
                        if (c == null || c[which] < 0) return;
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
        if (hooked > 0) FeatureStatusTracker.setHooked("PostCountSpoof");
        ModuleLog.line(TAG + (hooked > 0 ? "✅" : "❌") + " getters=" + hooked + log);
    }

    private static int[] countsFor(Object media) {
        ensureLoaded();
        if (getId == null || COUNTS.isEmpty()) return null;
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
