package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.Resources;
import android.view.View;
import android.view.ViewGroup;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Distraction Free — view-level removal of the story tray and the Reels tab.
 *
 * The network interceptor already drops the story/reels metadata requests, but IG 447.0.0.39+ falls
 * back to its locally-cached stories/reels (media streams from the CDN, which distraction-free does
 * not block), so they stay visible. To make them actually disappear we collapse the real Views by
 * their stable public resource names (verified from the live view tree, never obfuscated X.* ids):
 *   - story tray  -> com.instagram.android:id/reels_tray_container   (disableStories or hideStoriesTray)
 *   - Reels tab   -> com.instagram.android:id/clips_tab              (gated on disableReels)
 *
 * Armed from {@link UIHookManager#setupHooks} on every resume; a one-time global-layout listener per
 * window re-applies the collapse because IG rebuilds these views as you navigate. Everything is
 * gated per-flag and wrapped in try/catch, so nothing changes unless the toggle is on and a miss can
 * never crash Instagram.
 */
public class DistractionFreeUIHook {

    private static volatile boolean idsResolved = false;
    private static int trayId = 0;      // reels_tray_container (story tray)
    private static int clipsTabId = 0;  // clips_tab (Reels bottom-nav tab)
    // IG 447+ hosts the main tabs in a swipeable ViewPager2 (swipeable_tab_view_pager): swiping
    // left from Home (or right from Messages) opened Reels even with the tab hidden (#220).
    private static int tabPagerId = 0;

    // Customize navigation bar (#238): bottom-nav tab ids (stable names, present 436 → 451). The
    // search tab is deliberately not offered — long-pressing it opens the InstaEclipse settings.
    private static final String[] NAV_TAB_NAMES =
            {"feed_tab", "clips_tab", "creation_tab", "direct_tab", "news_tab", "profile_tab"};
    private static final int[] navTabIds = new int[NAV_TAB_NAMES.length];
    // Instagram Plus ("aura") upsell button, badges and lock icons (same ids 451/452).
    private static final String[] PLUS_UPSELL_NAMES =
            {"aura_upsell_button", "aura_list_badge", "aura_list_badge_stub", "aura_badge_lock_icon", "aura_badge_chevron"};
    private static final int[] plusUpsellIds = new int[PLUS_UPSELL_NAMES.length];

    private static boolean navTabHidden(int index) {
        switch (index) {
            case 0: return FeatureFlags.hideTabHome;
            case 1: return FeatureFlags.hideTabReels;
            case 2: return FeatureFlags.hideTabCreate;
            case 3: return FeatureFlags.hideTabMessages;
            case 4: return FeatureFlags.hideTabActivity;
            case 5: return FeatureFlags.hideTabProfile;
            default: return false;
        }
    }

    private static boolean anyNavTabHidden() {
        for (int i = 0; i < NAV_TAB_NAMES.length; i++) if (navTabHidden(i)) return true;
        return false;
    }

    private static final java.util.Set<View> watchedDecors =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    // Global layout fires many times per second on the main feed (video, animations). Walking the
    // whole decor tree on every pass was a measurable battery drain (#257), so sweeps triggered by
    // layout are coalesced: at most one pending sweep per window, run SWEEP_DEBOUNCE_MS later.
    private static final long SWEEP_DEBOUNCE_MS = 400;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final java.util.Set<View> pendingSweeps =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private static boolean anyFlag() {
        return FeatureFlags.disableStories || FeatureFlags.hideStoriesTray || FeatureFlags.disableReels || anyNavTabHidden() || FeatureFlags.hidePlusUpsells
                || !FeatureFlags.navTabOrder.isEmpty();
    }

    @SuppressLint("DiscouragedApi")
    private static void ensureIds(Activity a) {
        if (idsResolved) return;
        try {
            Resources res = a.getResources();
            String pkg = a.getPackageName();
            trayId = res.getIdentifier("reels_tray_container", "id", pkg);
            clipsTabId = res.getIdentifier("clips_tab", "id", pkg);
            tabPagerId = res.getIdentifier("swipeable_tab_view_pager", "id", pkg);
            for (int i = 0; i < NAV_TAB_NAMES.length; i++)
                navTabIds[i] = res.getIdentifier(NAV_TAB_NAMES[i], "id", pkg);
            for (int i = 0; i < PLUS_UPSELL_NAMES.length; i++)
                plusUpsellIds[i] = res.getIdentifier(PLUS_UPSELL_NAMES[i], "id", pkg);
            idsResolved = true;
            ModuleLog.line("(IE|DistractUI) resolved: tray=" + trayId + " clipsTab=" + clipsTabId);
        } catch (Throwable ignored) {}
    }

    /** Collapse the enabled targets in this activity, and keep them collapsed via a one-time
     *  global-layout listener on the window decor. Called on every main/modal resume. */
    public static void watchActivity(final Activity a) {
        if (a == null || !anyFlag()) return;
        try {
            ensureIds(a);
            sweep(a);
            final View decor = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            if (decor == null || !watchedDecors.add(decor)) return;
            decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (!anyFlag() || !pendingSweeps.add(decor)) return;
                MAIN.postDelayed(() -> {
                    pendingSweeps.remove(decor);
                    if (anyFlag()) sweep(a);
                }, SWEEP_DEBOUNCE_MS);
            });
        } catch (Throwable ignored) {}
    }

    private static void sweep(Activity a) {
        try {
            View root = a.getWindow() != null ? a.getWindow().getDecorView() : null;
            if (!(root instanceof ViewGroup)) return;
            if ((FeatureFlags.disableStories || FeatureFlags.hideStoriesTray) && trayId != 0) collapseAllById((ViewGroup) root, trayId);
            if (FeatureFlags.disableReels && clipsTabId != 0) collapseAllById((ViewGroup) root, clipsTabId);
            if (FeatureFlags.disableReels && tabPagerId != 0) disableTabSwipe((ViewGroup) root);
            for (int i = 0; i < NAV_TAB_NAMES.length; i++)
                if (navTabIds[i] != 0 && navTabHidden(i)) collapseAllById((ViewGroup) root, navTabIds[i]);
            if (FeatureFlags.hidePlusUpsells)
                for (int id : plusUpsellIds) if (id != 0) collapseAllById((ViewGroup) root, id);
            if (!FeatureFlags.navTabOrder.isEmpty()) reorderTabs(a, (ViewGroup) root);
        } catch (Throwable ignored) {}
    }

    /** Every bottom-nav tab that can be reordered, in Instagram's default order. */
    public static final String[] ORDERABLE_TABS =
            {"feed_tab", "clips_tab", "search_tab", "creation_tab", "direct_tab", "news_tab", "profile_tab"};
    private static boolean orderLogged;

    /** True when a saved tab order actually differs from Instagram's default order. */
    public static boolean isCustomTabOrder(String saved) {
        if (saved == null || saved.trim().isEmpty()) return false;
        java.util.List<String> order = new java.util.ArrayList<>();
        java.util.List<String> all = java.util.Arrays.asList(ORDERABLE_TABS);
        for (String n : saved.split(",")) {
            String t = n.trim();
            if (all.contains(t) && !order.contains(t)) order.add(t);
        }
        for (String n : ORDERABLE_TABS) if (!order.contains(n)) order.add(n);
        return !order.equals(all);
    }

    /**
     * Customize navigation bar (#238): puts the tab views in the user's order
     * (FeatureFlags.navTabOrder, comma-separated tab id names; unlisted tabs keep their relative
     * order after the listed ones). Only the slots the tabs already occupy are reused, so other
     * children of the bar stay where they are. Tabs are found by id, so their click handling is
     * unaffected.
     */
    @SuppressLint("DiscouragedApi")
    private static void reorderTabs(Activity a, ViewGroup root) {
        int feedId = a.getResources().getIdentifier("feed_tab", "id", a.getPackageName());
        View feed = feedId != 0 ? root.findViewById(feedId) : null;
        if (feed == null || !(feed.getParent() instanceof ViewGroup bar)) return;
        java.util.Map<String, View> tabs = new java.util.LinkedHashMap<>();
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        for (int i = 0; i < bar.getChildCount(); i++) {
            View c = bar.getChildAt(i);
            if (c.getId() == View.NO_ID) continue;
            String name;
            try { name = a.getResources().getResourceEntryName(c.getId()); } catch (Throwable t) { continue; }
            if (java.util.Arrays.asList(ORDERABLE_TABS).contains(name)) { tabs.put(name, c); slots.add(i); }
        }
        if (tabs.size() < 2) return;
        java.util.List<View> wanted = new java.util.ArrayList<>();
        for (String n : FeatureFlags.navTabOrder.split(","))
            if (tabs.containsKey(n.trim()) && !wanted.contains(tabs.get(n.trim()))) wanted.add(tabs.get(n.trim()));
        for (View v : tabs.values()) if (!wanted.contains(v)) wanted.add(v);
        boolean same = true;
        for (int k = 0; k < slots.size(); k++) if (bar.getChildAt(slots.get(k)) != wanted.get(k)) { same = false; break; }
        if (same) return;
        for (View v : wanted) bar.removeView(v);
        for (int k = 0; k < wanted.size(); k++) bar.addView(wanted.get(k), Math.min(slots.get(k), bar.getChildCount()));
        if (!orderLogged) {
            orderLogged = true;
            ModuleLog.line("(IE|DistractUI) nav tabs reordered: " + FeatureFlags.navTabOrder);
        }
    }

    private static boolean pagerTouchHooked;

    /**
     * Instagram re-enables the pager's user-input flag when you return to Home, so clearing it
     * once isn't enough. Hook the pager's inner RecyclerView touch handlers (where ViewPager2
     * checks that flag) and report "not handled" for the main tab pager while Reels are off.
     * Page content still gets every touch (onInterceptTouchEvent=false lets children handle it).
     */
    private static void hookPagerTouch(View pager) {
        if (pagerTouchHooked || !(pager instanceof ViewGroup g)) return;
        Class<?> inner = null;
        for (int i = 0; i < g.getChildCount() && inner == null; i++) {
            View c = g.getChildAt(i);
            if (c instanceof ViewGroup) inner = c.getClass();
        }
        if (inner == null) return;
        pagerTouchHooked = true;
        de.robv.android.xposed.XC_MethodHook gate = new de.robv.android.xposed.XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.disableReels) return;
                if (param.thisObject instanceof View self
                        && self.getParent() instanceof View p && p.getId() == tabPagerId) {
                    if (param.method.getName().equals("onInterceptTouchEvent")
                            && param.args[0] instanceof android.view.MotionEvent ev) trackSwipe(self, ev);
                    param.setResult(false);
                }
            }
        };
        int n = 0;
        for (String name : new String[]{"onInterceptTouchEvent", "onTouchEvent"}) {
            try {
                java.lang.reflect.Method m = inner.getDeclaredMethod(name, android.view.MotionEvent.class);
                de.robv.android.xposed.XposedBridge.hookMethod(m, gate);
                n++;
            } catch (Throwable ignored) {}
        }
        ModuleLog.line("(IE|DistractUI) main tab pager touch gate on " + inner.getName() + ": " + n);
    }

    // Swipe between Home and Messages while Reels is off (Reels sits between them in IG's pager):
    // the pager itself no longer scrolls, so a clear horizontal swipe taps the real tab button.
    private static float swipeDownX, swipeDownY;
    private static boolean swipeDone;

    private static void trackSwipe(View pagerChild, android.view.MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                swipeDownX = ev.getRawX();
                swipeDownY = ev.getRawY();
                swipeDone = false;
                break;
            case android.view.MotionEvent.ACTION_MOVE:
            case android.view.MotionEvent.ACTION_UP:
                if (swipeDone) return;
                float dx = ev.getRawX() - swipeDownX, dy = ev.getRawY() - swipeDownY;
                float min = 90 * pagerChild.getResources().getDisplayMetrics().density;
                if (Math.abs(dx) < min || Math.abs(dx) < 2.5f * Math.abs(dy)) return;
                swipeDone = true;
                View root = pagerChild.getRootView();
                View home = findTab(root, "feed_tab"), dm = findTab(root, "direct_tab");
                if (home == null || dm == null) return;
                // finger moving left = next page (Home → Reels → Messages); right = back.
                if (dx < 0 && home.isSelected()) dm.post(dm::performClick);
                else if (dx > 0 && dm.isSelected()) home.post(home::performClick);
                break;
            default:
        }
    }

    @SuppressLint("DiscouragedApi")
    private static View findTab(View root, String name) {
        int id = root.getResources().getIdentifier(name, "id", root.getContext().getPackageName());
        return id != 0 ? root.findViewById(id) : null;
    }

    private static boolean swipeLogged;

    /** Turns off swipe navigation between main tabs (tab buttons keep working). */
    private static void disableTabSwipe(ViewGroup root) {
        java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            if (v.getId() == tabPagerId) {
                hookPagerTouch(v);
                try {
                    java.lang.reflect.Field f = ps.reso.instaeclipse.utils.ui.ViewPager2Input.field(v);
                    if (f != null && f.getBoolean(v)) {
                        f.setBoolean(v, false);
                        if (!swipeLogged) { // IG turns it back on every few seconds; we keep switching it off
                            swipeLogged = true;
                            ModuleLog.line("(IE|DistractUI) main tab swipe disabled (Reels off) via " + f.getName());
                        }
                    }
                } catch (Throwable ignored) {}
                continue;
            }
            if (v instanceof ViewGroup vg)
                for (int i = 0; i < vg.getChildCount(); i++) stack.push(vg.getChildAt(i));
        }
    }

    private static void collapseAllById(ViewGroup root, int id) {
        java.util.ArrayDeque<View> stack = new java.util.ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            View v = stack.pop();
            if (v.getId() == id && v.getVisibility() != View.GONE) collapse(v);
            if (v instanceof ViewGroup vg)
                for (int i = 0; i < vg.getChildCount(); i++) stack.push(vg.getChildAt(i));
        }
    }

    private static void collapse(View v) {
        if (v == null) return;
        v.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) { lp.height = 0; lp.width = 0; v.setLayoutParams(lp); }
    }
}
