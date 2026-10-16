package ps.reso.instaeclipse.utils.ui;

import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * ViewPager2's user-input ("swipe enabled") flag, resolved without hardcoded obfuscated names.
 * IG's R8 build keeps the androidx.viewpager2.widget.ViewPager2 class name, but some builds drop
 * setUserInputEnabled() and the backing {@code mUserInputEnabled} field is renamed (A09 on 452).
 * Shared by DistractionFreeUIHook (main tab pager) and ReelsControlsHook (Reels pager).
 */
public final class ViewPager2Input {

    private ViewPager2Input() {}

    private static final String VIEWPAGER2 = "androidx.viewpager2.widget.ViewPager2";
    private static final String CACHE_PAGER_INPUT = "ViewPager2_userInputEnabled";

    private static Field userInputField;
    private static boolean userInputFieldTried;
    private static Method setter;
    private static boolean setterTried;

    /**
     * ViewPager2's {@code mUserInputEnabled}. Its inner RecyclerView reads it in
     * onInterceptTouchEvent, so DexKit gives the exact boolean field that method reads.
     * Needs a live pager (the inner RecyclerView class is taken from its children).
     */
    public static synchronized Field field(View pager) {
        if (userInputFieldTried) return userInputField;
        userInputFieldTried = true;
        try {
            Class<?> vp = viewPager2Class(pager);
            if (vp == null) return null;
            try {
                Field f = vp.getDeclaredField("mUserInputEnabled"); // unminified builds
                f.setAccessible(true);
                return userInputField = f;
            } catch (NoSuchFieldException ignored) {}
            String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(CACHE_PAGER_INPUT) : null;
            if (cached != null) {
                try {
                    Field f = vp.getDeclaredField(cached);
                    f.setAccessible(true);
                    return userInputField = f;
                } catch (NoSuchFieldException ignored) {} // stale cache: rediscover below
            }
            Class<?> inner = null;
            if (pager instanceof ViewGroup g) {
                for (int i = 0; i < g.getChildCount() && inner == null; i++) {
                    Class<?> c = g.getChildAt(i).getClass();
                    for (Field f : c.getDeclaredFields()) if (f.getType() == vp) { inner = c; break; }
                }
            }
            org.luckypray.dexkit.DexKitBridge bridge = ps.reso.instaeclipse.Xposed.Module.dexKitBridge;
            if (inner == null || bridge == null) return null;
            for (org.luckypray.dexkit.result.MethodData md : bridge.findMethod(org.luckypray.dexkit.query.FindMethod.create()
                    .matcher(org.luckypray.dexkit.query.matchers.MethodMatcher.create()
                            .declaredClass(inner.getName()).name("onInterceptTouchEvent")))) {
                for (org.luckypray.dexkit.result.UsingFieldData uf : md.getUsingFields()) {
                    org.luckypray.dexkit.result.FieldData fd = uf.getField();
                    if (fd.getDeclaredClassName().equals(vp.getName()) && fd.getTypeName().equals("boolean")) {
                        Field f = vp.getDeclaredField(fd.getFieldName());
                        f.setAccessible(true);
                        DexKitCache.saveString(CACHE_PAGER_INPUT, f.getName());
                        ModuleLog.line("(IE|ViewPager2) user-input field = " + f.getName());
                        return userInputField = f;
                    }
                }
            }
            ModuleLog.line("(IE|ViewPager2) user-input field not found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|ViewPager2) user-input field: " + t);
        }
        return null;
    }

    /**
     * Sets the pager's user-input flag. Prefers the public setUserInputEnabled() when the build
     * kept it (it also refreshes accessibility state); otherwise writes the resolved field.
     * Returns false when neither could be found.
     */
    public static boolean setEnabled(View pager, boolean enabled) {
        try {
            Method m = setter(pager);
            if (m != null) {
                m.invoke(pager, enabled);
                return true;
            }
            Field f = field(pager);
            if (f == null) return false;
            if (f.getBoolean(pager) != enabled) f.setBoolean(pager, enabled);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static synchronized Method setter(View pager) {
        if (setterTried) return setter;
        setterTried = true;
        Class<?> vp = viewPager2Class(pager);
        if (vp == null) return null;
        try {
            Method m = vp.getDeclaredMethod("setUserInputEnabled", boolean.class);
            m.setAccessible(true);
            return setter = m;
        } catch (NoSuchMethodException e) {
            ModuleLog.line("(IE|ViewPager2) setUserInputEnabled missing — using the field");
            return null;
        }
    }

    private static Class<?> viewPager2Class(View pager) {
        Class<?> vp = pager == null ? null : pager.getClass();
        while (vp != null && !vp.getName().equals(VIEWPAGER2)) vp = vp.getSuperclass();
        return vp;
    }
}
