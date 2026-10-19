package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.utils.LifecycleHookUtil;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.ghost.HiddenThreads;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/** Account-scoped inbox filtering; discreet controls are owned by ConversationMenuHook.
 * The upstream read-receipt resolver below stays available to ReadReceiptChatHook. */
public class HideChatsHook {
    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        new HiddenInboxFilter().install(bridge, classLoader);
        ConversationMenuHook.install();
    }

    public static boolean refreshAccount(String account) {
        return HiddenInboxFilter.refreshAccount(account);
    }

    public static int refreshKnownAccounts() {
        return HiddenInboxFilter.refreshKnownAccounts();
    }
    // ── thread-id resolution ──
    /** The open DM thread's id for {@code activity} (shared with ReadReceiptChatHook), or null.
     *  Only sources tied to the open chat; no scan of the whole activity graph, which in the main
     *  activity can return another chat's key (inbox rows, the previous chat). */
    static String resolveThreadId(Activity activity) {
        try {
            // 0. The resumed thread fragment's DirectThreadKey (works for threads hosted in
            //    InstagramMainActivity as well as ModalActivity — issue #254).
            String tracked = CurrentThreadTracker.currentThreadId();
            if (tracked != null) return tracked;
            // 1. Intent extras (thread key usually passed here); depth 4.
            android.os.Bundle ex = activity.getIntent() != null ? activity.getIntent().getExtras() : null;
            if (ex != null) {
                for (String k : ex.keySet()) {
                    Object dtk = scan(ex.get(k), 0,
                            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()), 4);
                    if (dtk != null) { String id = firstStringField(dtk); if (id != null) return id; }
                }
            }
            return null;
        } catch (Throwable t) { return null; }
    }

    /** Find a DirectThreadKey in obj's field graph (depth-limited; descends IG/obfuscated + Bundles). */
    private static Object scan(Object obj, int depth, java.util.Set<Object> seen, int maxDepth) {
        if (obj == null || depth > maxDepth || !seen.add(obj)) return null;
        String cn = obj.getClass().getName();
        if (cn.contains("DirectThreadKey")) return obj;
        boolean descendable = cn.startsWith("X.") || cn.startsWith("com.instagram.")
                || obj instanceof android.os.Bundle;
        if (!descendable) return null;
        if (obj instanceof android.os.Bundle) {
            try {
                android.os.Bundle b = (android.os.Bundle) obj;
                for (String k : b.keySet()) {
                    Object r = scan(b.get(k), depth + 1, seen, maxDepth);
                    if (r != null) return r;
                }
            } catch (Throwable ignored) {}
            return null;
        }
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v;
                    try { v = f.get(obj); } catch (Throwable e) { continue; }
                    if (v == null) continue;
                    if (v.getClass().getName().contains("DirectThreadKey")) return v;
                    Object r = scan(v, depth + 1, seen, maxDepth);
                    if (r != null) return r;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String firstStringField(Object o) {
        if (o == null) return null;
        try {
            for (Field f : o.getClass().getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                f.setAccessible(true);
                Object v = f.get(o);
                if (v instanceof String s && !s.isEmpty()) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int dp(Activity a, int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }
}
