package ps.reso.instaeclipse.mods.ui;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Profile spoofer: your own follower / following / post counts and a verified badge, on this
 * phone only. Nothing is sent to Instagram.
 *
 * <p>Instagram's User model reads each field by the hash of its JSON name (452: A6P() Integer
 * follower_count, A6Q() Integer following_count, A6W() Integer media_count, A61() Boolean
 * is_verified), so the getters are found by {@code fieldName.hashCode()} like the module's other
 * User getters. Their results are replaced only for the logged-in account's own User: its id
 * (the User's one final String field) is compared with the account of the screen in front, the
 * part after ':' of a resumed fragment's IgSessionManager.SESSION_TOKEN_KEY argument.
 *
 * <p>The profile header's follower / following numbers come from a separate header model, so they
 * are also set on the views themselves (profile_header_familiar_*_value) while your own profile is
 * shown (the account switcher's chevron, action_bar_title_chevron, is only there), and the badge
 * is drawn after your username in the action bar.
 */
public final class ProfileSpoofHook {

    private static final String TAG = "(IE|ProfileSpoof) ";
    private static final String USER = "com.instagram.user.model.User";
    private static final String TOKEN = "IgSessionManager.SESSION_TOKEN_KEY";

    private static volatile String selfId;

    /** The logged-in account's user id (null until a screen of it has resumed). */
    public static String selfId() { return selfId; }
    private static volatile Field idField;
    private static volatile boolean idFieldSearched;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        int hooked = 0;
        StringBuilder log = new StringBuilder();
        hooked += hook(bridge, cl, "follower_count", "ProfileSpoofFollowers", log, new String[]{"java.lang.Integer", "int", "java.lang.Long", "long"}, 0);
        hooked += hook(bridge, cl, "following_count", "ProfileSpoofFollowing", log, new String[]{"java.lang.Integer", "int", "java.lang.Long", "long"}, 1);
        hooked += hook(bridge, cl, "media_count", "ProfileSpoofPosts", log, new String[]{"java.lang.Integer", "int", "java.lang.Long", "long"}, 2);
        hooked += hook(bridge, cl, "is_verified", "ProfileSpoofVerified", log, new String[]{"java.lang.Boolean", "boolean"}, 3);
        boolean session = trackAccount(cl);
        hookHeaderViews();
        if (hooked > 0 && session) FeatureStatusTracker.setHooked("ProfileSpoofer");
        ModuleLog.line(TAG + (hooked > 0 && session ? "✅" : "❌") + " getters hooked=" + hooked + log
                + ", account tracking=" + session);
    }

    /** Finds User's zero-arg getter for {@code field} and hooks it; returns how many were hooked. */
    private static int hook(DexKitBridge bridge, ClassLoader cl, String field, String cacheKey,
                            StringBuilder log, String[] types, int which) {
        try {
            java.util.List<Method> found = DexKitCache.isCacheValid() ? DexKitCache.loadMethods(cacheKey, cl) : null;
            if (found == null || found.isEmpty()) {
                found = new java.util.ArrayList<>();
                for (String type : types) {
                    for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                            .declaredClass(USER).paramCount(0).returnType(type).usingNumbers(field.hashCode())))) {
                        Method m = md.getMethodInstance(cl);
                        if (!Modifier.isStatic(m.getModifiers())) found.add(m);
                    }
                }
                if (!found.isEmpty()) DexKitCache.saveMethods(cacheKey, found);
            }
            XC_MethodHook hook = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.profileSpoofer) return;
                    Object value = spoofed(which, ((Method) param.method).getReturnType());
                    if (value != null && isSelf(param.thisObject)) param.setResult(value);
                }
            };
            for (Method m : found) {
                XposedBridge.hookMethod(m, hook);
                log.append(", ").append(field).append("=").append(m.getName()).append("()")
                        .append(m.getReturnType().getSimpleName());
            }
            if (found.isEmpty()) log.append(", ").append(field).append("=not found");
            return found.size();
        } catch (Throwable t) {
            log.append(", ").append(field).append("=").append(t);
            return 0;
        }
    }

    /** The value to show for getter {@code which}, boxed to {@code type}; null = leave it alone. */
    private static Object spoofed(int which, Class<?> type) {
        if (which == 3) return FeatureFlags.spoofVerified ? Boolean.TRUE : null;
        int v = which == 0 ? FeatureFlags.spoofFollowers : which == 1 ? FeatureFlags.spoofFollowing : FeatureFlags.spoofPosts;
        if (v < 0) return null;
        return type == long.class || type == Long.class ? (Object) (long) v : (Object) v;
    }

    private static boolean isSelf(Object user) {
        String self = selfId;
        if (self == null || user == null) return false;
        return self.equals(userId(user));
    }

    /** The User's id: its final String field holding digits (A06 on 452). */
    private static String userId(Object user) {
        Field f = idField;
        if (f == null) {
            if (idFieldSearched) return null;
            synchronized (ProfileSpoofHook.class) {
                if (idField == null && !idFieldSearched) {
                    for (Field c : ps.reso.instaeclipse.utils.core.SafeReflect.fields(user.getClass())) {
                        if (c.getType() != String.class || Modifier.isStatic(c.getModifiers()) || !Modifier.isFinal(c.getModifiers())) continue;
                        try {
                            c.setAccessible(true);
                            if (c.get(user) instanceof String s && s.matches("\\d+")) { idField = c; break; }
                        } catch (Throwable ignored) {}
                    }
                    // A User without an id yet can't tell us; look again on the next one.
                    if (idField != null) ModuleLog.line(TAG + "user id field=" + idField.getName());
                }
                f = idField;
            }
            if (f == null) return null;
        }
        try { return (String) f.get(user); } catch (Throwable t) { return null; }
    }

    /** Keeps {@link #selfId} on the account of the screen in front (account switches included). */
    private static boolean trackAccount(ClassLoader cl) {
        try {
            Class<?> fragment = Class.forName("androidx.fragment.app.Fragment", false, cl);
            Method resume = fragment.getDeclaredMethod("performResume");
            Method args = fragment.getMethod("getArguments");
            XposedBridge.hookMethod(resume, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    // Always kept (cheap, only on resume), so turning the feature on applies at once.
                    try {
                        Object b = args.invoke(param.thisObject);
                        if (!(b instanceof android.os.Bundle bundle) || !(bundle.get(TOKEN) instanceof String t)) return;
                        String id = t.substring(t.lastIndexOf(':') + 1);
                        if (!id.matches("\\d+") || id.equals(selfId)) return;
                        selfId = id;
                        ModuleLog.line(TAG + "own account resolved");
                    } catch (Throwable ignored) {}
                }
            });
            return true;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ account tracking: " + t);
            return false;
        }
    }

    private static int followersId, followingId, titleId, chevronId;
    private static final ThreadLocal<Boolean> SETTING = new ThreadLocal<>();

    private static void hookHeaderViews() {
        XposedBridge.hookAllMethods(android.widget.TextView.class, "setText", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.profileSpoofer || SETTING.get() == Boolean.TRUE) return;
                android.widget.TextView tv = (android.widget.TextView) param.thisObject;
                int id = tv.getId();
                if (id == android.view.View.NO_ID) return;
                if (followersId == 0) resolveIds(tv);
                if (id != followersId && id != followingId && id != titleId) return;
                ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                tv.post(() -> apply(tv));
            }
        });
    }

    private static void resolveIds(android.view.View v) {
        android.content.res.Resources r = v.getResources();
        String pkg = v.getContext().getPackageName();
        followersId = r.getIdentifier("profile_header_familiar_followers_value", "id", pkg);
        followingId = r.getIdentifier("profile_header_familiar_following_value", "id", pkg);
        titleId = r.getIdentifier("action_bar_title", "id", pkg);
        chevronId = r.getIdentifier("action_bar_title_chevron", "id", pkg);
        if (followersId == 0) followersId = -1;
    }

    /** Your own profile is on screen: the account switcher chevron is shown. */
    private static boolean ownProfileShown(android.view.View v) {
        android.view.View c = chevronId != 0 ? v.getRootView().findViewById(chevronId) : null;
        return c != null && c.isShown();
    }

    private static void apply(android.widget.TextView tv) {
        try {
            if (!tv.isAttachedToWindow() || !ownProfileShown(tv)) return;
            int id = tv.getId();
            if (id == titleId) {
                badge(tv, FeatureFlags.spoofVerified);
                return;
            }
            int v = id == followersId ? FeatureFlags.spoofFollowers : FeatureFlags.spoofFollowing;
            if (v < 0) return;
            String text = shortCount(v);
            if (text.contentEquals(tv.getText())) return;
            SETTING.set(Boolean.TRUE);
            try { tv.setText(text); } finally { SETTING.remove(); }
        } catch (Throwable ignored) { }
    }

    /** Instagram's style: 1,234 · 12.3K · 1.2M. */
    static String shortCount(int n) {
        if (n < 10_000) return java.text.NumberFormat.getIntegerInstance().format(n);
        if (n < 1_000_000) return trim(n / 1000.0) + "K";
        return trim(n / 1_000_000.0) + "M";
    }

    private static String trim(double d) {
        String s = d >= 100 ? String.valueOf((long) d) : String.format(java.util.Locale.US, "%.1f", Math.floor(d * 10) / 10);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    private static final String BADGE_TAG = "ie_verified_badge";

    private static void badge(android.widget.TextView tv, boolean on) {
        android.graphics.drawable.Drawable[] d = tv.getCompoundDrawablesRelative();
        boolean has = d[2] instanceof VerifiedBadge;
        if (on == has) return;
        if (on) {
            int size = Math.round(tv.getTextSize() * 0.85f);
            VerifiedBadge b = new VerifiedBadge();
            b.setBounds(0, 0, size, size);
            tv.setCompoundDrawablePadding(Math.round(tv.getTextSize() * 0.2f));
            tv.setCompoundDrawablesRelative(d[0], d[1], b, d[3]);
        } else {
            tv.setCompoundDrawablesRelative(d[0], d[1], null, d[3]);
        }
    }

    /** Instagram's verified seal: a blue scalloped circle with a white check, painted directly so
     *  the theme engine (which recolors shape drawables) leaves it blue. */
    static final class VerifiedBadge extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint check = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        VerifiedBadge() {
            fill.setColor(0xFF0095F6);
            check.setColor(0xFFFFFFFF);
            check.setStyle(android.graphics.Paint.Style.STROKE);
            check.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            check.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }

        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float cx = b.exactCenterX(), cy = b.exactCenterY(), r = Math.min(b.width(), b.height()) / 2f;
            android.graphics.Path seal = new android.graphics.Path();
            int points = 16;
            for (int i = 0; i <= points * 2; i++) {
                double a = Math.PI * i / points;
                float rr = i % 2 == 0 ? r : r * 0.86f;
                float x = cx + (float) (Math.cos(a) * rr), y = cy + (float) (Math.sin(a) * rr);
                if (i == 0) seal.moveTo(x, y); else seal.lineTo(x, y);
            }
            seal.close();
            c.drawPath(seal, fill);
            check.setStrokeWidth(r * 0.2f);
            android.graphics.Path p = new android.graphics.Path();
            p.moveTo(cx - r * 0.38f, cy + r * 0.02f);
            p.lineTo(cx - r * 0.1f, cy + r * 0.3f);
            p.lineTo(cx + r * 0.4f, cy - r * 0.28f);
            c.drawPath(p, check);
        }

        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); check.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
