package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.content.Intent;

import java.lang.reflect.Method;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Opens Instagram on the chosen tab. The main activity picks its first tab from intent extras
 * holding a tab enum name (FEED, CLIPS, DIRECT, SEARCH, PROFILE, NEWS): the entrance-module extra
 * on a normal launch, {@code MainActivityAccountHelper.STARTUP_TAB} after an account switch. Both
 * are set on a fresh launch unless IG set one itself; IG falls back to its default tab when the
 * chosen one is not on the bottom bar.
 */
public class StartupTabHook {

    private static final String TAG = "(InstaEclipse | StartupTab): ";

    /** Tab enum names offered in the settings, in bottom-bar order ("" = Instagram's default). */
    public static final String[] TABS = {"", "FEED", "CLIPS", "DIRECT", "SEARCH", "PROFILE", "NEWS"};

    /** Read only on the account-switch path. */
    static final String EXTRA = "MainActivityAccountHelper.STARTUP_TAB";
    /** Read on a normal launch when no cold-start flag picks a tab. */
    static final String ENTRANCE_EXTRA = "INSTAGRAM_MAIN_ACTIVITY_INTENT_ENTRANCE_MODULE_EXTRA_FIELD";
    /** Marks a launch intent this hook rewrote. */
    static final String MARK = "ie_startup_tab";
    private static final String APPLIED = "ie_startup_tab_applied";

    /** Launcher alias; the activity behind it is {@link #MAIN_CLASS}. */
    private static final String MAIN = "com.instagram.android.activity.MainTabActivity";
    private static final String MAIN_CLASS = "com.instagram.mainactivity.InstagramMainActivity";

    private static boolean isMainActivity(Activity activity) {
        String cls = activity.getClass().getName();
        if (MAIN_CLASS.equals(cls) || MAIN.equals(cls)) return true;
        android.content.ComponentName cn = activity.getComponentName();
        return cn != null && MAIN.equals(cn.getClassName());
    }

    public void install(ClassLoader cl) {
        // The main activity lives in a secondary dex that isn't loaded yet when hooks install,
        // so hook the framework's activity-creation entry point and match it by name.
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.startupTab.isEmpty() || !(param.args[0] instanceof Activity)) return;
                if (param.args.length > 1 && param.args[1] != null) return; // recreated: keep the user's tab
                Activity activity = (Activity) param.args[0];
                if (!isMainActivity(activity)) return;
                Intent intent = activity.getIntent();
                if (intent == null || intent.hasExtra(EXTRA) || intent.hasExtra(ENTRANCE_EXTRA)) return;
                if (!Intent.ACTION_MAIN.equals(intent.getAction())) return; // deep links pick their own tab
                intent.putExtra(EXTRA, FeatureFlags.startupTab);
                intent.putExtra(ENTRANCE_EXTRA, FeatureFlags.startupTab);
                intent.putExtra(MARK, true);
            }
        };
        int hooked = 0;
        for (Method m : android.app.Instrumentation.class.getDeclaredMethods()) {
            if (m.getName().equals("callActivityOnCreate")) {
                XposedBridge.hookMethod(m, hook);
                hooked++;
            }
        }
        if (hooked == 0) {
            ModuleLog.line(TAG + "❌ callActivityOnCreate not found");
            return;
        }
        // During onCreate IG puts "is_cold_start_feed"/"is_cold_start_reel_tab" on the launch
        // intent, and those win over the entrance extra; hide them on intents we rewrote.
        try {
            java.util.Set<String> coldStart = new java.util.HashSet<>(
                    java.util.Arrays.asList("is_cold_start_feed", "is_cold_start_reel_tab"));
            XposedBridge.hookMethod(Intent.class.getDeclaredMethod("getBooleanExtra", String.class, boolean.class),
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (FeatureFlags.startupTab.isEmpty()) return;
                            if (coldStart.contains(param.args[0]) && ((Intent) param.thisObject).hasExtra(MARK)) {
                                param.setResult(false);
                            }
                        }
                    });
        } catch (NoSuchMethodException e) {
            ModuleLog.line(TAG + "Intent.getBooleanExtra missing");
        }
        // The extras can be read while the tab controller is still null, so the pick is dropped;
        // switch tabs through the controller once the activity first resumes.
        for (Method m : android.app.Instrumentation.class.getDeclaredMethods()) {
            if (!m.getName().equals("callActivityOnResume")) continue;
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (FeatureFlags.startupTab.isEmpty() || !(param.args[0] instanceof Activity)) return;
                    Activity activity = (Activity) param.args[0];
                    if (!isMainActivity(activity)) return;
                    Intent intent = activity.getIntent();
                    if (intent == null || !intent.hasExtra(MARK) || intent.hasExtra(APPLIED)) return;
                    intent.putExtra(APPLIED, true);
                    String tab = FeatureFlags.startupTab;
                    activity.getWindow().getDecorView().post(() -> switchTab(activity, tab));
                }
            });
        }
        FeatureStatusTracker.setHooked("StartupTab");
        ModuleLog.line(TAG + "✅ hooked Instrumentation.callActivityOnCreate x" + hooked);
    }

    /**
     * Calls the main activity's tab controller: a field whose type declares
     * {@code void (TabEnum, String, boolean)}, with the tab enum resolved from that parameter type
     * by name.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void switchTab(Activity activity, String tab) {
        for (Class<?> c = activity.getClass(); c != null && c != Activity.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                Class<?> type = f.getType();
                if (type.isPrimitive() || type.getName().startsWith("android.") || type.getName().startsWith("java.")) continue;
                for (Method m : type.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getReturnType() != void.class || p.length != 3 || !p[0].isEnum()
                            || p[1] != String.class || p[2] != boolean.class) continue;
                    try {
                        Object value = Enum.valueOf((Class<? extends Enum>) p[0], tab);
                        f.setAccessible(true);
                        Object controller = f.get(activity);
                        if (controller == null) continue;
                        m.setAccessible(true);
                        m.invoke(controller, value, null, false);
                        ModuleLog.line(TAG + "switched to " + tab);
                        return;
                    } catch (IllegalArgumentException notThisEnum) {
                        // enum without that constant: not the tab enum
                    } catch (Throwable t) {
                        ModuleLog.line(TAG + "switch failed: " + t);
                        return;
                    }
                }
            }
        }
        ModuleLog.line(TAG + "tab controller not found");
    }
}
