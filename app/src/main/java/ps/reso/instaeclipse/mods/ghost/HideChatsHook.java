package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

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
    private static final String DIFF_UPDATE_ANCHOR = "InboxViewModelGenerator.calculateAndApplyDiffUpdate";
    private static final String HEADER_ANCHOR = "PrebindThreadHeader";
    private static final String THREAD_OPEN_ANCHOR = "igThreadIgid";

    private static int threadHeaderId, backButtonId, tagKeyId;
    public static volatile String currentOpenThreadId = null;

    private static final WeakHashMap<Activity, Boolean> registeredActivities = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> backTouchListeners = new WeakHashMap<>();
    private static final WeakHashMap<View, int[]> savedBarParams = new WeakHashMap<>();

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        installInboxFilter(bridge, classLoader);
        installDiffUpdateHook(bridge, classLoader);
        installThreadTracker(bridge, classLoader);
        installHeaderButton(classLoader);
    }

    // ── 1. Inbox Filter & Empty General Folder (Store Level) ──────────────────
    private void installInboxFilter(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook filter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                FeatureFlags.hideSpecificChats = (FeatureFlags.hideChatsMode != 0);
                Object r = param.getResult();
                if (!(r instanceof List<?> originalList)) return;

                if (FeatureFlags.emptyGeneralFolder && isGeneralFolderQuery(param.args)) {
                    param.setResult(new ArrayList<>());
                    return;
                }

                if (FeatureFlags.hideChatsMode == 0 || HiddenThreads.isEmpty()) return;

                try {
                    List<Object> filtered = new ArrayList<>(originalList.size());
                    for (Object item : originalList) {
                        String id = threadIdOfRow(item);
                        if (id != null && HiddenThreads.isHidden(id)) continue;
                        filtered.add(item);
                    }
                    param.setResult(filtered);
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

    // ── 1b. ViewModel DiffUpdate Hook (UI State Level for Empty General) ──────
    private void installDiffUpdateHook(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook diffHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.emptyGeneralFolder) return;
                try {
                    Object thisObj = param.thisObject;
                    if (thisObj == null) return;
                    Field cField = findFieldByType(thisObj.getClass(), "X.05Iq");
                    if (cField == null) return;
                    cField.setAccessible(true);
                    Object state = cField.get(thisObj);
                    if (state == null) return;
                    
                    Field folderField = findFieldByType(state.getClass(), "X.09ZC");
                    if (folderField == null) return;
                    folderField.setAccessible(true);
                    Object folder = folderField.get(state);
                    if (folder != null && isGeneralDescriptor(folder)) {
                        Field threadsField = findFieldByType(state.getClass(), List.class);
                        if (threadsField != null) {
                            threadsField.setAccessible(true);
                            threadsField.set(state, new ArrayList<>());
                        }
                    }
                } catch (Throwable ignored) {}
            }
        };

        try {
            int n = 0;
            for (MethodData md : bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(DIFF_UPDATE_ANCHOR)))) {
                try {
                    XposedBridge.hookMethod(md.getMethodInstance(classLoader), diffHook);
                    n++;
                } catch (Throwable ignored) {}
            }
            ModuleLog.line("(IE|HideChats) diff update hooked " + n + " method(s)");
        } catch (Throwable ignored) {}
    }

    private static Field findFieldByType(Class<?> clazz, String typeName) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().getName().contains(typeName)) return f;
            }
        }
        return null;
    }

    private static Field findFieldByType(Class<?> clazz, Class<?> targetType) {
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType().isAssignableFrom(targetType)) return f;
            }
        }
        return null;
    }

    private static boolean isGeneralDescriptor(Object obj) {
        if (obj == null) return false;
        String name = obj.getClass().getName();
        if (name.contains("0Gmm") || name.contains("C425100Gmm")) return true;
        try {
            for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        Object val = f.get(obj);
                        if ("general".equalsIgnoreCase((String) val)) return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isGeneralFolderQuery(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (arg == null) continue;
            if (isGeneralDescriptor(arg)) return true;
            if (arg instanceof List<?> list) {
                for (Object elem : list) {
                    if (isGeneralDescriptor(elem)) return true;
                }
            }
        }
        return false;
    }

    // ── 1c. Foreground Thread Tracker ──────────────────────────────────────────
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
                        }
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
                            if (arg.getClass().getName().startsWith("X.")) {
                                String id = extractThreadIdDeep(arg, 4);
                                if (id != null) {
                                    currentOpenThreadId = id;
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

    // ── 2. Live Refresh & Folders Bar Management ───────────────────────────────
    public static void refreshActiveInbox(Activity activity) {
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            try {
                applyFoldersBarVisibility(activity);
                Method getSfm = activity.getClass().getMethod("getSupportFragmentManager");
                Object fm = getSfm.invoke(activity);
                if (fm != null) {
                    refreshFragmentsRecursive(fm);
                }
                walkDecorViewForRecyclerViews(activity.getWindow().getDecorView());
            } catch (Throwable ignored) {}
        });
    }

    private static void refreshFragmentsRecursive(Object fm) {
        try {
            Method getFrags = fm.getClass().getMethod("getFragments");
            List<?> frags = (List<?>) getFrags.invoke(fm);
            if (frags == null) return;
            for (Object frag : frags) {
                if (frag == null || frag.getClass().getName().contains("Dialog")) continue;
                try {
                    Method isVis = frag.getClass().getMethod("isVisible");
                    if (Boolean.TRUE.equals(isVis.invoke(frag))) {
                        Method onP = frag.getClass().getMethod("onPause");
                        Method onR = frag.getClass().getMethod("onResume");
                        onP.invoke(frag);
                        onR.invoke(frag);
                    }
                } catch (Throwable ignored) {}
                try {
                    Method getChildFm = frag.getClass().getMethod("getChildFragmentManager");
                    Object childFm = getChildFm.invoke(frag);
                    if (childFm != null) refreshFragmentsRecursive(childFm);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private static void walkDecorViewForRecyclerViews(View v) {
        if (v instanceof ViewGroup vg) {
            if (v.getClass().getName().contains("RecyclerView")) {
                try {
                    Method getAdapter = v.getClass().getMethod("getAdapter");
                    Object adapter = getAdapter.invoke(v);
                    if (adapter != null) {
                        Method notifyDS = adapter.getClass().getMethod("notifyDataSetChanged");
                        notifyDS.invoke(adapter);
                    }
                } catch (Throwable ignored) {}
            }
            for (int i = 0; i < vg.getChildCount(); i++) {
                walkDecorViewForRecyclerViews(vg.getChildAt(i));
            }
        }
    }

    private static void applyFoldersBarVisibility(Activity activity) {
        try {
            View bar = findFoldersBar(activity.getWindow().getDecorView());
            if (bar == null) return;
            ViewGroup.LayoutParams lp = bar.getLayoutParams();
            if (!savedBarParams.containsKey(bar)) {
                int topM = (lp instanceof ViewGroup.MarginLayoutParams mlp) ? mlp.topMargin : 0;
                int botM = (lp instanceof ViewGroup.MarginLayoutParams mlp) ? mlp.bottomMargin : 0;
                savedBarParams.put(bar, new int[]{lp.height, bar.getPaddingTop(), bar.getPaddingBottom(), topM, botM});
            }
            int[] orig = savedBarParams.get(bar);
            if (FeatureFlags.hideDirectFoldersBar) {
                bar.setVisibility(View.GONE);
                lp.height = 0;
                bar.setPadding(bar.getPaddingLeft(), 0, bar.getPaddingRight(), 0);
                if (lp instanceof ViewGroup.MarginLayoutParams mlp) {
                    mlp.topMargin = 0;
                    mlp.bottomMargin = 0;
                }
                bar.setLayoutParams(lp);
            } else {
                if (orig != null) {
                    lp.height = orig[0];
                    bar.setPadding(bar.getPaddingLeft(), orig[1], bar.getPaddingRight(), orig[2]);
                    if (lp instanceof ViewGroup.MarginLayoutParams mlp) {
                        mlp.topMargin = orig[3];
                        mlp.bottomMargin = orig[4];
                    }
                    bar.setLayoutParams(lp);
                }
                bar.setVisibility(View.VISIBLE);
                bar.requestLayout();
            }
        } catch (Throwable ignored) {}
    }

    private static View findFoldersBar(View root) {
        if (!(root instanceof ViewGroup vg)) return null;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View child = vg.getChildAt(i);
            if (child.getId() != View.NO_ID) {
                try {
                    String entry = root.getResources().getResourceEntryName(child.getId());
                    if (entry != null && (entry.contains("direct_inbox_folder") || entry.contains("filter_pill") || entry.contains("cf_hub_pills") || (entry.contains("inbox") && (entry.contains("pill") || entry.contains("folder"))))) {
                        return child;
                    }
                } catch (Throwable ignored) {}
            }
            // Search specifically for horizontal container holding folder pills (avoiding action bar)
            if (child instanceof ViewGroup subVg) {
                String cn = subVg.getClass().getName();
                if (!cn.contains("ActionBar") && !cn.contains("Toolbar") && subVg.getChildCount() >= 2 && subVg.getHeight() > 0 && subVg.getHeight() < dpApprox(subVg, 70)) {
                    return subVg;
                }
                View found = findFoldersBar(subVg);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int dpApprox(View v, int dp) {
        return Math.round(dp * v.getResources().getDisplayMetrics().density);
    }

    // ── 3. Header Integration (Eye Button vs. Back Button Long-Press) ──────────
    private void installHeaderButton(ClassLoader classLoader) {
        XC_MethodHook resume = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                FeatureFlags.hideSpecificChats = (FeatureFlags.hideChatsMode != 0);
                final Activity a = (Activity) param.thisObject;
                a.runOnUiThread(() -> registerListener(a));
            }
        };
        for (String act : new String[]{"com.instagram.modal.ModalActivity", "com.instagram.mainactivity.InstagramMainActivity"}) {
            try { XposedHelpers.findAndHookMethod(act, classLoader, "onResume", resume); }
            catch (Throwable ignored) {}
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
            applyFoldersBarVisibility(activity);
            tryInject(activity);
            if (registeredActivities.containsKey(activity)) return;
            registeredActivities.put(activity, true);
            final View decor = activity.getWindow().getDecorView();
            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    applyFoldersBarVisibility(activity);
                    tryInject(activity);
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

            if (back != null && !backTouchListeners.containsKey(back)) {
                backTouchListeners.put(back, true);
                back.setOnTouchListener(new View.OnTouchListener() {
                    private final Handler handler = new Handler(Looper.getMainLooper());
                    private final boolean[] triggered = {false};
                    private float startX, startY;
                    private final Runnable longPressRunnable = () -> {
                        triggered[0] = true;
                        back.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                        String threadId = resolveThreadId(activity, header, currentOpenThreadId);
                        if (threadId == null) {
                            Toast.makeText(activity, I18n(activity, R.string.ig_hide_chat_no_thread), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        boolean nowHidden = HiddenThreads.toggle(threadId, threadTitle(activity));
                        Toast.makeText(activity, I18n(activity, nowHidden ? R.string.ig_hide_chat_hidden : R.string.ig_hide_chat_unhidden), Toast.LENGTH_SHORT).show();
                        refreshActiveInbox(activity);
                    };

                    @SuppressLint("ClickableViewAccessibility")
                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        if (FeatureFlags.hideChatsMode != 2) {
                            handler.removeCallbacks(longPressRunnable);
                            return false;
                        }
                        int action = event.getAction();
                        int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                        switch (action) {
                            case MotionEvent.ACTION_DOWN:
                                triggered[0] = false;
                                startX = event.getX();
                                startY = event.getY();
                                long sec = Math.max(1, FeatureFlags.hideChatsBackLongPressSeconds);
                                handler.postDelayed(longPressRunnable, sec * 1000L);
                                return false;
                            case MotionEvent.ACTION_MOVE:
                                if (Math.abs(event.getX() - startX) > slop || Math.abs(event.getY() - startY) > slop) {
                                    handler.removeCallbacks(longPressRunnable);
                                }
                                break;
                            case MotionEvent.ACTION_UP:
                                handler.removeCallbacks(longPressRunnable);
                                if (triggered[0]) {
                                    v.setPressed(false);
                                    return true;
                                }
                                break;
                            case MotionEvent.ACTION_CANCEL:
                                handler.removeCallbacks(longPressRunnable);
                                triggered[0] = false;
                                break;
                        }
                        return false;
                    }
                });
            }

            if (back != null && back.getParent() instanceof ViewGroup) {
                target = (ViewGroup) back.getParent();
                int bi = target.indexOfChild(back);
                boolean rtl = target.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                insertAt = rtl ? bi : bi + 1;
            } else if (header instanceof ViewGroup) {
                target = (ViewGroup) header;
                insertAt = 0;
            } else return false;

            View existingEye = target.findViewWithTag(TAG);
            if (FeatureFlags.hideChatsMode == 1) {
                if (existingEye == null) {
                    android.widget.ImageView btn = new android.widget.ImageView(activity);
                    btn.setTag(TAG);
                    android.graphics.drawable.Drawable icon = ps.reso.instaeclipse.utils.dialog.DialogUtils.moduleIcon(R.drawable.ic_eye_off, Color.WHITE);
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
                    final String boundAtInject = currentOpenThreadId != null ? currentOpenThreadId : KeepUnsentMessagesHook.currentThreadId;

                    btn.setOnClickListener(v -> {
                        String threadId = resolveThreadId(activity, headerRoot, boundAtInject);
                        if (threadId == null) {
                            Toast.makeText(activity, I18n(activity, R.string.ig_hide_chat_no_thread), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        boolean nowHidden = HiddenThreads.toggle(threadId, threadTitle(activity));
                        Toast.makeText(activity, I18n(activity, nowHidden ? R.string.ig_hide_chat_hidden : R.string.ig_hide_chat_unhidden), Toast.LENGTH_SHORT).show();
                        refreshActiveInbox(activity);
                    });
                    try { target.addView(btn, Math.min(insertAt, target.getChildCount())); }
                    catch (Throwable t) { target.addView(btn); }
                } else {
                    existingEye.setVisibility(View.VISIBLE);
                }
            } else {
                if (existingEye != null) existingEye.setVisibility(View.GONE);
            }
            return true;
        } catch (Throwable t) {
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
            List<String> texts = new ArrayList<>();
            collectTexts((ViewGroup) header, texts);
            for (String t : texts) if (t.matches("[a-zA-Z0-9._]{2,30}")) return t;
            return texts.isEmpty() ? "" : texts.get(0);
        } catch (Throwable t) { return ""; }
    }

    private void collectTexts(ViewGroup vg, List<String> out) {
        for (int i = 0; i < vg.getChildCount() && out.size() < 12; i++) {
            View c = vg.getChildAt(i);
            if (c instanceof android.widget.TextView tv) {
                CharSequence cs = tv.getText();
                if (cs != null && !cs.toString().trim().isEmpty()) out.add(cs.toString().trim());
            } else if (c instanceof ViewGroup) {
                collectTexts((ViewGroup) c, out);
            }
        }
    }

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
                    List<?> frags = (List<?>) getFrags.invoke(fm);
                    if (frags != null) {
                        for (Object frag : frags) {
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
