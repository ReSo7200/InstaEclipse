package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.HiddenThreads;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class HideChatsHook {

    private static final String TAG = "ie_hidechat_btn";
    private static final String INBOX_ANCHOR = "DirectThreadStoreImpl.getSortedCopyOfThreadSummaries";
    private static final String HEADER_ANCHOR = "PrebindThreadHeader";
    private static final String THREAD_OPEN_ANCHOR = "igThreadIgid";

    private static int threadHeaderId, backButtonId, tagKeyId;
    public static volatile String currentOpenThreadId = null;

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        installInboxFilter(bridge, classLoader);
        installThreadTracker(bridge, classLoader);
        installHeaderButton(classLoader);
    }

    // ── 1. Inbox thread-list filter ────────────────────────────────────────────
    private void installInboxFilter(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook filter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.hideSpecificChats || HiddenThreads.isEmpty()) return;
                Object r = param.getResult();
                if (!(r instanceof List<?> list) || list.isEmpty()) return;
                try {
                    java.util.Iterator<?> it = list.iterator();
                    while (it.hasNext()) {
                        String id = threadIdOfRow(it.next());
                        if (id != null && HiddenThreads.isHidden(id)) {
                            it.remove();
                        }
                    }
                } catch (Throwable ignored) {}
            }
        };
        try {
            int n = 0;
            for (MethodData md : bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(INBOX_ANCHOR)))) {
                try {
                    XposedBridge.hookMethod(md.getMethodInstance(classLoader), filter);
                    n++;
                } catch (Throwable ignored) {}
            }
            if (n > 0) FeatureStatusTracker.setHooked("HideSpecificChats");
            ModuleLog.line("(IE|HideChats) inbox filter hooked " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|HideChats) ⚠️ inbox filter: " + t.getMessage());
        }
    }

    // ── 1b. Active Thread Tracker (PrebindThreadHeader + igThreadIgid) ─────────
    private void installThreadTracker(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            List<MethodData> openMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings(THREAD_OPEN_ANCHOR)
                            .paramTypes("com.instagram.model.direct.DirectThreadKey", "boolean")));
            XC_MethodHook openHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && param.args[0] != null) {
                        String id = firstStringField(param.args[0]);
                        Object flag = param.args.length > 1 ? param.args[1] : Boolean.TRUE;
                        if (id != null && Boolean.TRUE.equals(flag)) {
                            currentOpenThreadId = id;
                            ModuleLog.line("(IE|HideChats) tracked open thread (igThreadIgid)=" + id);
                        }
                    }
                    if (currentOpenThreadId == null && param.thisObject != null) {
                        String id = extractThreadIdDeep(param.thisObject, 3);
                        if (id != null) currentOpenThreadId = id;
                    }
                }
            };
            for (MethodData md : openMethods) {
                try { XposedBridge.hookMethod(md.getMethodInstance(classLoader), openHook); }
                catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        try {
            List<MethodData> headerMethods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(HEADER_ANCHOR)));
            XC_MethodHook headerHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args != null) {
                        for (Object arg : param.args) {
                            if (arg == null) continue;
                            String cn = arg.getClass().getName();
                            if (cn.startsWith("X.")) {
                                String id = extractThreadIdDeep(arg, 4);
                                if (id != null) {
                                    currentOpenThreadId = id;
                                    ModuleLog.line("(IE|HideChats) tracked open thread (PrebindThreadHeader)=" + id);
                                    break;
                                }
                            }
                        }
                    }
                }
            };
            for (MethodData md : headerMethods) {
                try { XposedBridge.hookMethod(md.getMethodInstance(classLoader), headerHook); }
                catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Finds the DirectThreadKey on an inbox row object (e.g. X.C00WH) and returns its thread id.
     * In IG 447+, C00WH.A00 (DirectThreadKey) is lazily initialized by calling CTG().
     */
    private static String threadIdOfRow(Object row) {
        if (row == null) return null;
        try {
            if (row.getClass().getName().contains("DirectThreadKey")) {
                return firstStringField(row);
            }
            Class<?> c = row.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (!f.getType().getName().contains("DirectThreadKey")) continue;
                    f.setAccessible(true);
                    Object key = f.get(row);
                    String id = firstStringField(key);
                    if (id != null) return id;
                }
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterCount() == 0 && m.getReturnType().getName().contains("DirectThreadKey")) {
                        m.setAccessible(true);
                        Object key = m.invoke(row);
                        String id = firstStringField(key);
                        if (id != null) return id;
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String extractThreadIdDeep(Object root, int maxDepth) {
        Object found = scan(root, 0, Collections.newSetFromMap(new IdentityHashMap<>()), maxDepth);
        if (found == null) return null;
        if (found instanceof String s) return s;
        return firstStringField(found);
    }

    // ── 2. Hide/unhide button in the thread header ─────────────────────────────
    private void installHeaderButton(ClassLoader classLoader) {
        XC_MethodHook resume = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.hideSpecificChats) return;
                final Activity a = (Activity) param.thisObject;
                a.runOnUiThread(() -> registerListener(a));
            }
        };
        for (String act : new String[]{"com.instagram.modal.ModalActivity",
                "com.instagram.mainactivity.InstagramMainActivity"}) {
            try {
                XposedHelpers.findAndHookMethod(act, classLoader, "onResume", resume);
            } catch (Throwable t) {
                ModuleLog.line("(IE|HideChats) ⚠️ hook " + act + ": " + t.getMessage());
            }
        }
        ModuleLog.line("(IE|HideChats) ✅ installed");
    }

    @SuppressLint("DiscouragedApi")
    private void ensureIds(Activity a) {
        if (threadHeaderId != 0) return;
        String pkg = a.getPackageName();
        android.content.res.Resources r = a.getResources();
        threadHeaderId = r.getIdentifier("direct_thread_header", "id", pkg);
        backButtonId   = r.getIdentifier("action_bar_button_back", "id", pkg);
        tagKeyId       = r.getIdentifier("direct_thread_view_layout_tag_key", "id", pkg);
    }

    private void registerListener(final Activity activity) {
        try {
            ensureIds(activity);
            if (tryInject(activity)) return;
            final View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    if (tryInject(activity)) {
                        try { decor.getViewTreeObserver().removeOnGlobalLayoutListener(this); }
                        catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    private boolean tryInject(Activity activity) {
        try {
            View header = threadHeaderId != 0 ? activity.findViewById(threadHeaderId) : null;
            if (header == null) return false;

            ViewGroup target;
            int insertAt;
            View back = backButtonId != 0 ? activity.findViewById(backButtonId) : null;
            if (back != null && back.getParent() instanceof ViewGroup) {
                target = (ViewGroup) back.getParent();
                int bi = target.indexOfChild(back);
                boolean rtl = target.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                insertAt = rtl ? bi : bi + 1;
            } else if (header instanceof ViewGroup) {
                target = (ViewGroup) header;
                insertAt = 0;
            } else return false;

            if (target.findViewWithTag(TAG) != null) return true;

            android.widget.ImageView btn = new android.widget.ImageView(activity);
            btn.setTag(TAG);
            android.graphics.drawable.Drawable icon =
                    ps.reso.instaeclipse.utils.dialog.DialogUtils.moduleIcon(R.drawable.ic_eye_off, Color.WHITE);
            if (icon != null) btn.setImageDrawable(icon);
            btn.setScaleType(android.widget.ImageView.ScaleType.CENTER_INSIDE);
            int pad = dp(activity, 9);
            btn.setPadding(pad, pad, pad, pad);
            btn.setClickable(true);
            btn.setFocusable(true);
            btn.setContentDescription("Hide chat");
            int sz = dp(activity, 40);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sz, sz);
            lp.gravity = Gravity.CENTER_VERTICAL;
            btn.setLayoutParams(lp);

            final View headerRoot = header;
            final String boundAtInject = currentOpenThreadId != null
                    ? currentOpenThreadId
                    : KeepUnsentMessagesHook.currentThreadId;

            btn.setOnClickListener(v -> {
                String threadId = resolveThreadId(activity, headerRoot, boundAtInject);
                if (threadId == null) {
                    Toast.makeText(activity, I18n(activity, R.string.ig_hide_chat_no_thread), Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean nowHidden = HiddenThreads.toggle(threadId, threadTitle(activity));
                Toast.makeText(activity,
                        I18n(activity, nowHidden ? R.string.ig_hide_chat_hidden : R.string.ig_hide_chat_unhidden),
                        Toast.LENGTH_SHORT).show();
                ModuleLog.line("(IE|HideChats) toggled thread=" + threadId + " hidden=" + nowHidden);
            });
            try { target.addView(btn, Math.min(insertAt, target.getChildCount())); }
            catch (Throwable t) { target.addView(btn); }
            return true;
        } catch (Throwable t) {
            ModuleLog.line("(IE|HideChats) ⚠️ inject: " + t.getMessage());
            return false;
        }
    }

    private static String I18n(Activity a, int res) {
        return ps.reso.instaeclipse.utils.i18n.I18n.t(a, res);
    }

    private String threadTitle(Activity a) {
        try {
            View header = threadHeaderId != 0 ? a.findViewById(threadHeaderId) : null;
            if (!(header instanceof ViewGroup)) return "";
            java.util.List<String> texts = new java.util.ArrayList<>();
            collectTexts((ViewGroup) header, texts);
            for (String t : texts) if (t.matches("[a-zA-Z0-9._]{2,30}")) return t;
            return texts.isEmpty() ? "" : texts.get(0);
        } catch (Throwable t) { return ""; }
    }

    private void collectTexts(ViewGroup vg, java.util.List<String> out) {
        for (int i = 0; i < vg.getChildCount() && out.size() < 12; i++) {
            View c = vg.getChildAt(i);
            if (c instanceof android.widget.TextView) {
                CharSequence cs = ((android.widget.TextView) c).getText();
                if (cs != null && !cs.toString().trim().isEmpty()) out.add(cs.toString().trim());
            } else if (c instanceof ViewGroup) {
                collectTexts((ViewGroup) c, out);
            }
        }
    }

    // ── Multi-layer Thread ID resolution ───────────────────────────────────────
    private String resolveThreadId(Activity activity, View headerRoot, String boundAtInject) {
        if (currentOpenThreadId != null && !currentOpenThreadId.isEmpty()) return currentOpenThreadId;
        if (KeepUnsentMessagesHook.currentThreadId != null && !KeepUnsentMessagesHook.currentThreadId.isEmpty()) {
            return KeepUnsentMessagesHook.currentThreadId;
        }
        if (boundAtInject != null && !boundAtInject.isEmpty()) return boundAtInject;

        try {
            Bundle ex = activity.getIntent() != null ? activity.getIntent().getExtras() : null;
            if (ex != null) {
                Object dtk = scan(ex, 0, Collections.newSetFromMap(new IdentityHashMap<>()), 5);
                if (dtk != null) {
                    String id = (dtk instanceof String s) ? s : firstStringField(dtk);
                    if (id != null) return id;
                }
            }

            View v = headerRoot;
            int up = 0;
            while (v != null && up++ < 8) {
                if (tagKeyId != 0) {
                    Object t = v.getTag(tagKeyId);
                    if (t != null) {
                        String id = extractThreadIdDeep(t, 3);
                        if (id != null) return id;
                    }
                }
                Object plain = v.getTag();
                if (plain != null) {
                    String id = extractThreadIdDeep(plain, 3);
                    if (id != null) return id;
                }
                v = (v.getParent() instanceof View) ? (View) v.getParent() : null;
            }

            try {
                Method getSfm = activity.getClass().getMethod("getSupportFragmentManager");
                Object fm = getSfm.invoke(activity);
                if (fm != null) {
                    Method getFrags = fm.getClass().getMethod("getFragments");
                    Object frags = getFrags.invoke(fm);
                    if (frags instanceof List<?> list) {
                        for (Object frag : list) {
                            if (frag == null) continue;
                            String id = extractThreadIdDeep(frag, 5);
                            if (id != null) return id;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            Object dtk = scan(activity, 0, Collections.newSetFromMap(new IdentityHashMap<>()), 6);
            if (dtk != null) {
                return (dtk instanceof String s) ? s : firstStringField(dtk);
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object scan(Object obj, int depth, Set<Object> seen, int maxDepth) {
        if (obj == null || depth > maxDepth || !seen.add(obj)) return null;
        String cn = obj.getClass().getName();
        if (cn.contains("DirectThreadKey") || cn.contains("ThreadIdParcelable") || cn.contains("UnifiedThreadKeyParcelable")) {
            return obj;
        }

        if (cn.startsWith("X.")) {
            String rowId = threadIdOfRow(obj);
            if (rowId != null) return rowId;
        }

        if (obj instanceof Bundle b) {
            try {
                for (String k : b.keySet()) {
                    Object val = b.get(k);
                    if (val instanceof String s && (k.toLowerCase().contains("thread_id") || k.toLowerCase().contains("threadid"))) {
                        if (!s.isEmpty()) return s;
                    }
                    Object r = scan(val, depth + 1, seen, maxDepth);
                    if (r != null) return r;
                }
            } catch (Throwable ignored) {}
            return null;
        }

        boolean descendable = cn.startsWith("X.") || cn.startsWith("com.instagram.") || cn.startsWith("androidx.fragment.");
        if (!descendable) return null;

        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v;
                    try { v = f.get(obj); } catch (Throwable e) { continue; }
                    if (v == null) continue;
                    String vcn = v.getClass().getName();
                    if (vcn.contains("DirectThreadKey") || vcn.contains("ThreadIdParcelable")) return v;
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
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class || Modifier.isStatic(f.getModifiers())) continue;
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (v instanceof String s && !s.isEmpty()) return s;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
