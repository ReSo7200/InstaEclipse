with open("app/src/main/java/ps/reso/instaeclipse/mods/ghost/HideChatsHook.java", "r") as f:
    content = f.read()

replacement = """package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
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
import android.view.HapticFeedbackConstants;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.WeakHashMap;
import java.util.Collection;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
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

    private static final String GENERAL_LABEL_REGEX = "^(?i)(general|generale|général|allgemein|geral|genel|общие|عام)(\\\\s.*|\\\\u00a0.*|•.*|\\\\d+.*)?$";
    private static final String OTHER_FOLDER_LABEL_REGEX = "^(?i)(primary|principale|principal|haupt|primario|requests|richieste|anfragen|demandes|solicitudes|channels|canali|kanäle|canaux|canales|unread|non letti)(\\\\s.*|\\\\u00a0.*|•.*|\\\\d+.*)?$";

    private static int threadHeaderId, backButtonId, tagKeyId;
    public static volatile String currentOpenThreadId = null;

    private static volatile boolean isGeneralTabSelected = false;
    private static final WeakHashMap<View, Boolean> backButtonTouchAttached = new WeakHashMap<>();
    private static final WeakHashMap<View, int[]> originalPillsBarLayout = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Boolean> registeredActivities = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> hookedPills = new WeakHashMap<>();

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
                // A. Check for emptyGeneralFolder intent from args
                if (FeatureFlags.emptyGeneralFolder) {
                    boolean isGeneralCall = false;
                    for (Object arg : param.args) {
                        if (arg instanceof Collection<?>) {
                            for (Object el : (Collection<?>) arg) {
                                if (el == null) continue;
                                try {
                                    for (Field f : el.getClass().getDeclaredFields()) {
                                        if (Modifier.isStatic(f.getModifiers())) continue;
                                        f.setAccessible(true);
                                        Object fv = f.get(el);
                                        if (isGeneralEnum(fv)) {
                                            isGeneralCall = true; break;
                                        }
                                    }
                                } catch (Throwable ignored) {}
                                if (isGeneralCall) break;
                            }
                        } else if (isGeneralEnum(arg)) {
                            isGeneralCall = true;
                        }
                        if (isGeneralCall) break;
                    }
                    if (isGeneralCall || isGeneralTabSelected) {
                        param.setResult(new ArrayList<>());
                        return;
                    }
                }

                // B. Filter hidden chats via defensive copy (no it.remove)
                if (FeatureFlags.hideChatsMode == 0 || HiddenThreads.isEmpty()) return;

                Object r = param.getResult();
                if (!(r instanceof List<?> list) || list.isEmpty()) return;

                try {
                    ArrayList<Object> filteredCopy = new ArrayList<>(list.size());
                    for (Object item : list) {
                        String id = threadIdOfRow(item);
                        if (id == null || !HiddenThreads.isHidden(id)) {
                            filteredCopy.add(item);
                        }
                    }
                    param.setResult(filteredCopy);
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

    private static boolean isGeneralEnum(Object obj) {
        if (obj instanceof Enum<?>) {
            Enum<?> e = (Enum<?>) obj;
            if (e.name().toUpperCase().contains("GENERAL") || e.toString().toLowerCase().contains("general")) return true;
            try {
                for (Field f : e.getClass().getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        String s = (String) f.get(e);
                        if (s != null && (s.equalsIgnoreCase("general") || s.equals("1"))) return true;
                    }
                }
            } catch (Throwable ignored) {}
        } else if (obj instanceof String s && s.equalsIgnoreCase("general")) {
            return true;
        }
        return false;
    }

    // ── 1b. Active Thread Tracker (PrebindThreadHeader + igThreadIgid) ─────────
    private void installThreadTracker(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            XC_MethodHook headerHook = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object r2 = param.args[1];
                        if (r2 == null) return;
                        Field[] r2f = r2.getClass().getDeclaredFields();
                        for (Field f : r2f) {
                            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                            f.setAccessible(true);
                            Object presenter = f.get(r2);
                            if (presenter != null && presenter.getClass().getName().startsWith("X.")) {
                                for (Field pf : presenter.getClass().getDeclaredFields()) {
                                    if (Modifier.isStatic(pf.getModifiers())) continue;
                                    pf.setAccessible(true);
                                    Object row = pf.get(presenter);
                                    if (row != null && row.getClass().getName().startsWith("X.")) {
                                        String tid = threadIdOfRow(row);
                                        if (tid != null) {
                                            currentOpenThreadId = tid;
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            };
            int hn = 0;
            for (MethodData md : bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(HEADER_ANCHOR)))) {
                try {
                    XposedBridge.hookMethod(md.getMethodInstance(classLoader), headerHook);
                    hn++;
                } catch (Throwable ignored) {}
            }
            ModuleLog.line("(IE|HideChats) tracker[header] hooked " + hn + " method(s)");

            XC_MethodHook fgHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object dtk = param.args[0];
                        if (dtk != null) {
                            String tid = extractThreadIdDeep(dtk, 3);
                            if (tid != null) {
                                currentOpenThreadId = tid;
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            };
            int fn = 0;
            for (MethodData md : bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(THREAD_OPEN_ANCHOR)))) {
                try {
                    XposedBridge.hookMethod(md.getMethodInstance(classLoader), fgHook);
                    fn++;
                } catch (Throwable ignored) {}
            }
            ModuleLog.line("(IE|HideChats) tracker[fg] hooked " + fn + " method(s)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|HideChats) ⚠️ tracker: " + t.getMessage());
        }
    }

    private static String threadIdOfRow(Object row) {
        if (row == null) return null;
        try {
            for (Field f : row.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object val = f.get(row);
                if (val != null) {
                    String cn = val.getClass().getName();
                    if (cn.contains("DirectThreadKey")) {
                        String id = extractThreadIdDeep(val, 2);
                        if (id != null) return id;
                    } else if (cn.startsWith("X.")) {
                        for (Field sub : val.getClass().getDeclaredFields()) {
                            if (sub.getType() == String.class) {
                                sub.setAccessible(true);
                                String s = (String) sub.get(val);
                                if (s != null && s.matches("\\\\d{15,}")) return s;
                            }
                        }
                    }
                }
            }
            try {
                Method m = row.getClass().getMethod("CTG");
                Object dtk = m.invoke(row);
                if (dtk != null) {
                    String id = extractThreadIdDeep(dtk, 2);
                    if (id != null) return id;
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return null;
    }

    private static String extractThreadIdDeep(Object obj, int maxDepth) {
        Object found = scan(obj, 0, Collections.newSetFromMap(new IdentityHashMap<>()), maxDepth);
        if (found instanceof String s) return s;
        return firstStringField(found);
    }

    // ── 2. Hide/unhide button in the thread header ─────────────────────────────
    private void installHeaderButton(ClassLoader classLoader) {
        XC_MethodHook resume = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // We always want to register the listener so it can apply the folders bar hiding
                // or track the active tab, even if Hide Specific Chats is off.
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

            View decor = activity.getWindow().getDecorView();
            View foldersBar = findFoldersBar(decor);

            scanAndHookInboxPills(activity, foldersBar);
            applyFoldersBarVisibility(activity, foldersBar);
            tryInject(activity);
            applyEmptyGeneralUiFallback(activity, foldersBar);

            if (registeredActivities.containsKey(activity)) return;
            registeredActivities.put(activity, true);

            decor.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
                @Override
                public void onGlobalLayout() {
                    View fb = findFoldersBar(decor);
                    scanAndHookInboxPills(activity, fb);
                    applyFoldersBarVisibility(activity, fb);
                    tryInject(activity);
                    applyEmptyGeneralUiFallback(activity, fb);
                }
            });
        } catch (Throwable ignored) {}
    }

    private boolean tryInject(Activity activity) {
        try {
            View header = threadHeaderId != 0 ? activity.findViewById(threadHeaderId) : null;
            View back = backButtonId != 0 ? activity.findViewById(backButtonId) : null;

            // Setup Back Button Hold Trigger if mode is 2
            if (back != null && !backButtonTouchAttached.containsKey(back)) {
                backButtonTouchAttached.put(back, true);
                back.setOnTouchListener(new View.OnTouchListener() {
                    private final boolean[] triggered = {false};
                    private final Handler handler = new Handler(Looper.getMainLooper());
                    private final Runnable longPressAction = new Runnable() {
                        @Override
                        public void run() {
                            triggered[0] = true;
                            back.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                            String threadId = resolveThreadId(activity, header, currentOpenThreadId);
                            if (threadId != null) {
                                boolean nowHidden = HiddenThreads.toggle(threadId, threadTitle(activity));
                                Toast.makeText(activity,
                                        I18n(activity, nowHidden ? R.string.ig_hide_chat_hidden : R.string.ig_hide_chat_unhidden),
                                        Toast.LENGTH_SHORT).show();
                            }
                        }
                    };

                    @Override
                    public boolean onTouch(View v, MotionEvent event) {
                        if (FeatureFlags.hideChatsMode != 2) {
                            handler.removeCallbacks(longPressAction);
                            return false;
                        }

                        switch (event.getAction()) {
                            case MotionEvent.ACTION_DOWN:
                                triggered[0] = false;
                                handler.postDelayed(longPressAction, Math.max(1, FeatureFlags.hideChatsBackLongPressSeconds) * 1000L);
                                break;
                            case MotionEvent.ACTION_MOVE:
                                int slop = ViewConfiguration.get(v.getContext()).getScaledTouchSlop();
                                if (event.getX() < -slop || event.getY() < -slop ||
                                    event.getX() > v.getWidth() + slop || event.getY() > v.getHeight() + slop) {
                                    handler.removeCallbacks(longPressAction);
                                }
                                break;
                            case MotionEvent.ACTION_UP:
                                handler.removeCallbacks(longPressAction);
                                if (triggered[0]) {
                                    v.setPressed(false);
                                    return true; // Consume event so normal back doesn't fire
                                }
                                break;
                            case MotionEvent.ACTION_CANCEL:
                                handler.removeCallbacks(longPressAction);
                                triggered[0] = false;
                                break;
                        }
                        return false;
                    }
                });
            }

            if (header == null) return false;

            ViewGroup target;
            int insertAt;
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

            if (FeatureFlags.hideChatsMode != 1) {
                if (existingEye != null) existingEye.setVisibility(View.GONE);
                return true;
            }

            if (existingEye != null) {
                existingEye.setVisibility(View.VISIBLE);
                return true;
            }

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

    // ── Structural Finder for Folders Bar ──────────────────────────────────────
    private static View findFoldersBar(View v) {
        if (v == null) return null;

        List<android.widget.TextView> textViews = new ArrayList<>();
        collectFolderTextViews(v, textViews);

        for (android.widget.TextView tv : textViews) {
            String text = tv.getText().toString();
            if (text.matches(GENERAL_LABEL_REGEX)) {
                View bar = tv;
                int levels = 0;
                while (bar != null && bar.getParent() instanceof ViewGroup && levels < 4) {
                    bar = (View) bar.getParent();
                    if (bar instanceof ViewGroup) {
                        ViewGroup vg = (ViewGroup) bar;
                        if (vg.getHeight() < dp(v.getContext(), 90) || vg.getHeight() == 0) {
                            if (vg.getClass().getName().contains("RecyclerView") ||
                                vg.getClass().getName().contains("HorizontalScrollView") ||
                                (vg instanceof LinearLayout && ((LinearLayout) vg).getOrientation() == LinearLayout.HORIZONTAL)) {

                                int childPillsCount = 0;
                                for (int i = 0; i < vg.getChildCount(); i++) {
                                    if (hasFolderText(vg.getChildAt(i))) {
                                        childPillsCount++;
                                    }
                                }
                                if (childPillsCount >= 2) {
                                    return vg;
                                }
                            }
                        }
                    }
                    levels++;
                }
            }
        }

        // Fallback to resource ID
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            String resName = "";
            try { resName = v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) {}

            if (resName != null && (resName.contains("direct_inbox_folder") ||
                resName.contains("direct_inbox_filter_pill") ||
                resName.contains("inbox_folder_pills") ||
                resName.contains("direct_inbox_pills") ||
                resName.contains("cf_hub_pills") ||
                (resName.contains("inbox") && (resName.contains("pill") || resName.contains("folder") || resName.contains("filter"))))) {
                return v;
            }

            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findFoldersBar(vg.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void collectFolderTextViews(View v, List<android.widget.TextView> list) {
        if (v == null) return;

        // Exclude bottom nav and header
        String resName = "";
        try { resName = v.getResources().getResourceEntryName(v.getId()); } catch (Throwable ignored) {}
        if (resName != null && (resName.contains("direct_thread_header") || resName.contains("tab_bar") || resName.contains("bottom_navigation"))) return;

        if (v instanceof android.widget.TextView) {
            String text = ((android.widget.TextView) v).getText().toString();
            if (text.matches(GENERAL_LABEL_REGEX) || text.matches(OTHER_FOLDER_LABEL_REGEX)) {
                list.add((android.widget.TextView) v);
            }
        } else if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                collectFolderTextViews(vg.getChildAt(i), list);
            }
        }
    }

    private static boolean hasFolderText(View v) {
        if (v instanceof android.widget.TextView) {
            String text = ((android.widget.TextView) v).getText().toString();
            return text.matches(GENERAL_LABEL_REGEX) || text.matches(OTHER_FOLDER_LABEL_REGEX);
        } else if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (hasFolderText(vg.getChildAt(i))) return true;
            }
        }
        return false;
    }

    // ── Apply visibility of direct folders bar ──────────────────────────────────
    private static void applyFoldersBarVisibility(Activity activity, View bar) {
        try {
            if (bar == null) return;

            ViewGroup.LayoutParams lp = bar.getLayoutParams();
            if (bar.getVisibility() == View.VISIBLE && lp.height != 0) {
                int tm = 0, bm = 0;
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    tm = ((ViewGroup.MarginLayoutParams) lp).topMargin;
                    bm = ((ViewGroup.MarginLayoutParams) lp).bottomMargin;
                }
                originalPillsBarLayout.put(bar, new int[]{
                    lp.height, bar.getPaddingTop(), bar.getPaddingBottom(), tm, bm, bar.getMinimumHeight()
                });
            }

            if (FeatureFlags.hideDirectFoldersBar) {
                bar.setVisibility(View.GONE);
                bar.setMinimumHeight(0);
                lp.height = 0;
                bar.setPadding(bar.getPaddingLeft(), 0, bar.getPaddingRight(), 0);
                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ((ViewGroup.MarginLayoutParams) lp).topMargin = 0;
                    ((ViewGroup.MarginLayoutParams) lp).bottomMargin = 0;
                }
                if (bar instanceof ViewGroup) {
                    ViewGroup vg = (ViewGroup) bar;
                    for (int i = 0; i < vg.getChildCount(); i++) vg.getChildAt(i).setVisibility(View.GONE);
                }
                bar.setLayoutParams(lp);
                bar.requestLayout();
            } else {
                int[] orig = originalPillsBarLayout.get(bar);
                if (orig != null) {
                    lp.height = orig[0];
                    bar.setMinimumHeight(orig[5]);
                    bar.setPadding(bar.getPaddingLeft(), orig[1], bar.getPaddingRight(), orig[2]);
                    if (lp instanceof ViewGroup.MarginLayoutParams) {
                        ((ViewGroup.MarginLayoutParams) lp).topMargin = orig[3];
                        ((ViewGroup.MarginLayoutParams) lp).bottomMargin = orig[4];
                    }
                    if (bar instanceof ViewGroup) {
                        ViewGroup vg = (ViewGroup) bar;
                        for (int i = 0; i < vg.getChildCount(); i++) vg.getChildAt(i).setVisibility(View.VISIBLE);
                    }
                    bar.setLayoutParams(lp);
                    bar.setVisibility(View.VISIBLE);
                    bar.requestLayout();
                }
            }
        } catch (Throwable ignored) {}
    }

    // ── Track General Folder Pill ──────────────────────────────────────────────
    private static void scanAndHookInboxPills(Activity activity, View bar) {
        if (!FeatureFlags.emptyGeneralFolder || bar == null || !(bar instanceof ViewGroup)) return;

        try {
            boolean wasGeneral = isGeneralTabSelected;
            boolean foundGeneralSelected = false;

            ViewGroup vg = (ViewGroup) bar;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View pill = vg.getChildAt(i);

                boolean isGen = isGeneralPill(pill);

                if (isGen) {
                    if (pill.isSelected() || pill.isActivated() || isAnyChildCheckedOrSelected(pill)) {
                        foundGeneralSelected = true;
                    }
                }

                if (!hookedPills.containsKey(pill)) {
                    hookedPills.put(pill, true);
                    pill.setOnTouchListener((v, event) -> {
                        if (event.getAction() == MotionEvent.ACTION_UP) {
                            boolean wasGen = isGeneralTabSelected;
                            isGeneralTabSelected = isGen;
                            if (wasGen != isGen) {
                                Handler h = new Handler(Looper.getMainLooper());
                                h.postDelayed(() -> refreshActiveInbox(activity), 50);
                                h.postDelayed(() -> refreshActiveInbox(activity), 200);
                            }
                        }
                        return false; // let normal click run
                    });
                }
            }

            isGeneralTabSelected = foundGeneralSelected;
            if (wasGeneral != isGeneralTabSelected) {
                refreshActiveInbox(activity);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean isGeneralPill(View v) {
        if (v instanceof android.widget.TextView) {
            String text = ((android.widget.TextView) v).getText().toString();
            return text.matches(GENERAL_LABEL_REGEX);
        }
        if (v.getContentDescription() != null) {
            String desc = v.getContentDescription().toString();
            if (desc.matches(GENERAL_LABEL_REGEX)) return true;
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (isGeneralPill(vg.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private static boolean isAnyChildCheckedOrSelected(View v) {
        if (v.isSelected() || v.isActivated()) return true;
        if (v instanceof android.widget.Checkable && ((android.widget.Checkable) v).isChecked()) return true;
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                if (isAnyChildCheckedOrSelected(vg.getChildAt(i))) return true;
            }
        }
        return false;
    }

    // ── Apply UI Fallback for Empty General ────────────────────────────────────
    private static void applyEmptyGeneralUiFallback(Activity activity, View foldersBar) {
        if (foldersBar == null) return;
        try {
            View parent = (View) foldersBar.getParent();
            while (parent != null && !parent.getClass().getName().contains("RecyclerView")) {
                parent = (View) parent.getParent();
            }
            if (parent != null) {
                // Look for the threads recyclerview which is usually below the bar
                View decor = activity.getWindow().getDecorView();
                List<View> recyclerViews = new ArrayList<>();
                findRecyclerViews(decor, recyclerViews);

                for (View rv : recyclerViews) {
                    if (rv != parent && rv.getHeight() > dp(activity, 200)) { // Main threads list
                        if (FeatureFlags.emptyGeneralFolder && isGeneralTabSelected) {
                            rv.setVisibility(View.INVISIBLE);
                        } else {
                            if (rv.getVisibility() == View.INVISIBLE) rv.setVisibility(View.VISIBLE);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void findRecyclerViews(View v, List<View> list) {
        if (v == null) return;
        if (v.getClass().getName().contains("RecyclerView")) list.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) findRecyclerViews(vg.getChildAt(i), list);
        }
    }

    // ── Live Inbox Refresh ─────────────────────────────────────────────────────
    public static void refreshActiveInbox(Activity activity) {
        if (activity == null || activity.isFinishing()) return;

        activity.runOnUiThread(() -> {
            try {
                // Ensure UI state (eye button, bar visibility) is updated immediately
                HideChatsHook inst = new HideChatsHook();
                inst.ensureIds(activity);
                inst.tryInject(activity);

                View decor = activity.getWindow().getDecorView();
                View fb = findFoldersBar(decor);
                applyFoldersBarVisibility(activity, fb);
                applyEmptyGeneralUiFallback(activity, fb);

                // Force IG to re-query the thread store by cycling visible fragments
                Method getSfm = activity.getClass().getMethod("getSupportFragmentManager");
                Object fm = getSfm.invoke(activity);
                if (fm != null) {
                    Method getFrags = fm.getClass().getMethod("getFragments");
                    List<?> frags = (List<?>) getFrags.invoke(fm);
                    if (frags != null) cycleVisibleFragments(frags);
                }

                // Also poke RecyclerView adapters to redraw immediately
                notifyRecyclerViews(decor);
            } catch (Throwable t) {
                ModuleLog.line("(IE|HideChats) refresh error: " + t.getMessage());
            }
        });
    }

    private static void cycleVisibleFragments(List<?> frags) {
        for (Object frag : frags) {
            if (frag == null) continue;
            try {
                Method isVisible = frag.getClass().getMethod("isVisible");
                if (Boolean.TRUE.equals(isVisible.invoke(frag))) {
                    // Skip dialog fragments to avoid glitching overlays
                    if (frag.getClass().getName().contains("Dialog")) continue;

                    try {
                        Method onPause = frag.getClass().getMethod("onPause");
                        onPause.invoke(frag);
                    } catch (Throwable ignored) {}
                    try {
                        Method onResume = frag.getClass().getMethod("onResume");
                        onResume.invoke(frag);
                    } catch (Throwable ignored) {}

                    // Recurse into child fragment managers
                    try {
                        Method getCfm = frag.getClass().getMethod("getChildFragmentManager");
                        Object cfm = getCfm.invoke(frag);
                        if (cfm != null) {
                            Method getChildFrags = cfm.getClass().getMethod("getFragments");
                            List<?> childFrags = (List<?>) getChildFrags.invoke(cfm);
                            if (childFrags != null) cycleVisibleFragments(childFrags);
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void notifyRecyclerViews(View v) {
        if (v == null) return;
        if (v.getClass().getName().contains("RecyclerView")) {
            try {
                Method getAdapter = v.getClass().getMethod("getAdapter");
                Object adapter = getAdapter.invoke(v);
                if (adapter != null) {
                    Method notifyData = adapter.getClass().getMethod("notifyDataSetChanged");
                    notifyData.invoke(adapter);
                }
            } catch (Throwable ignored) {}
        }
        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                notifyRecyclerViews(vg.getChildAt(i));
            }
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

    private static int dp(Context a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }
}
"""

with open("app/src/main/java/ps/reso/instaeclipse/mods/ghost/HideChatsHook.java", "w") as f:
    f.write(replacement)
