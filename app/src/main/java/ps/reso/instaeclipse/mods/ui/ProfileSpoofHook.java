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
 *
 * <p>Name, username, bio and picture are changed only on your own profile screen, and only in
 * what is drawn, never in the User model, so DMs, comments, Edit profile and anything sent to
 * Instagram keep the real values:
 * <ul>
 *   <li>username: the action bar title next to the chevron (action_bar_title);</li>
 *   <li>name: profile_header_full_name_above_vanity / profile_header_full_name (TextViews);</li>
 *   <li>picture: row_profile_header_imageview (a CircularImageView). Instagram loads the real
 *       picture asynchronously, so ImageView.setImageDrawable/setImageBitmap on that view put the
 *       spoofed one back right away;</li>
 *   <li>bio: drawn by Compose (profile_user_info_compose_view, ProfileBioComponent), so there is
 *       no TextView. Its text comes from the header's bio UI state, a small immutable class
 *       (452: X.01N1) built by the header binder from the profile's GraphQL model; it is the type
 *       of a ProfileBioComponent composable parameter. Its constructor gets (…, id, username,
 *       bio, …, mention/hashtag entities, …): when the id is your own account, the bio argument
 *       is replaced and the entities (offsets into the real bio) are dropped. That object only
 *       feeds the profile header's bio composable; Edit profile reads its own model, so saving
 *       it never sends the fake bio. A legacy bio TextView (profile_header_bio_text) is handled
 *       like the name.</li>
 * </ul>
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
        hookAvatarView();
        hookBioState(bridge, cl, log);
        if (hooked > 0 && session) FeatureStatusTracker.setHooked("ProfileSpoofer");
        ModuleLog.line(TAG + (hooked > 0 && session ? "✅" : "❌") + " getters hooked=" + hooked + log
                + ", account tracking=" + session);
    }

    // ---- Bio (Compose): the header's bio UI state ----------------------------------------

    private static final String BIO_COMPOSABLE = "ProfileBioComponent (ProfileBioComponent.kt";
    private static final String BIO_STATE_KEY = "ProfileSpoofBioState";

    /** Finds the bio UI state class (see the class doc) and hooks its constructors. */
    private static void hookBioState(DexKitBridge bridge, ClassLoader cl, StringBuilder log) {
        try {
            Class<?> state = null;
            String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(BIO_STATE_KEY) : null;
            if (cached != null && !cached.isEmpty()) {
                try { state = Class.forName(cached, false, cl); } catch (Throwable ignored) {}
            }
            if (state == null) {
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .usingStrings(java.util.List.of(BIO_COMPOSABLE),
                                org.luckypray.dexkit.query.enums.StringMatchType.Contains, false)))) {
                    Method m;
                    try { m = md.getMethodInstance(cl); } catch (Throwable t) { continue; }
                    for (Class<?> p : m.getParameterTypes()) {
                        if (bioConstructor(p) != null) { state = p; break; }
                    }
                    if (state != null) break;
                }
                if (state != null) DexKitCache.saveString(BIO_STATE_KEY, state.getName());
            }
            java.lang.reflect.Constructor<?> ctor = state != null ? bioConstructor(state) : null;
            if (ctor == null) {
                log.append(", bio=not found");
                return;
            }
            Class<?>[] types = ctor.getParameterTypes();
            int[] strings = new int[types.length];
            int n = 0, lastString = -1;
            for (int i = 0; i < types.length; i++) if (types[i] == String.class) { strings[n++] = i; lastString = i; }
            final int usernameArg = strings[1];
            final int bioArg = strings[2];
            int entities = -1;
            for (int i = lastString + 1; i < types.length; i++)
                if (!types[i].isPrimitive() && types[i] != Integer.class) { entities = i; break; }
            final int entitiesArg = entities;
            final int[] stringArgs = java.util.Arrays.copyOf(strings, n);
            XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!FeatureFlags.profileSpoofer) return;
                    String bio = FeatureFlags.spoofBio;
                    String self = selfId;
                    if (bio == null || bio.isEmpty() || self == null) return;
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    boolean own = false;
                    for (int i : stringArgs) if (self.equals(param.args[i])) { own = true; break; }
                    // Fallback when the header model's id is not the account id: your username,
                    // as the action bar shows it, while your own profile is in front.
                    if (!own) {
                        String real = realUsername;
                        own = real != null && real.equals(param.args[usernameArg])
                                && android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
                                && ownProfileInFront();
                    }
                    if (!own) return;
                    param.args[bioArg] = bio;
                    // Mention / hashtag ranges point into the real bio; null = "none" (Instagram
                    // passes null itself for a bio without entities).
                    if (entitiesArg >= 0) param.args[entitiesArg] = null;
                }
            });
            log.append(", bio=").append(state.getName()).append(" arg ").append(bioArg);
        } catch (Throwable t) {
            log.append(", bio=").append(t);
        }
    }

    /** The bio UI state's constructor: at least 4 String and 5 boolean parameters. */
    private static java.lang.reflect.Constructor<?> bioConstructor(Class<?> c) {
        if (c.isPrimitive() || c.isInterface() || c.getName().startsWith("java") || c.getName().startsWith("android")) return null;
        for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
            int strings = 0, booleans = 0;
            for (Class<?> p : k.getParameterTypes()) {
                if (p == String.class) strings++;
                else if (p == boolean.class) booleans++;
            }
            if (strings >= 4 && booleans >= 5) return k;
        }
        return null;
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

    // ---- View level: counts, username, name, bio TextView, picture ------------------------

    private static int followersId, followingId, titleId, chevronId, nameId, nameAltId, bioTextId, avatarId, tabAvatarId;
    private static final ThreadLocal<Boolean> SETTING = new ThreadLocal<>();

    private static void hookHeaderViews() {
        XposedBridge.hookAllMethods(android.widget.TextView.class, "setText", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.profileSpoofer || SETTING.get() == Boolean.TRUE) return;
                android.widget.TextView tv = (android.widget.TextView) param.thisObject;
                int id = tv.getId();
                if (id == android.view.View.NO_ID) return;
                if (followersId == 0) resolveIds(tv);
                if (id != followersId && id != followingId && id != titleId
                        && id != nameId && id != nameAltId && id != bioTextId) return;
                ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                tv.post(() -> apply(tv));
            }
        });
    }

    /** The real picture arrives asynchronously (and again on every rebind): put ours back. */
    private static void hookAvatarView() {
        XC_MethodHook hook = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.profileSpoofer || SETTING.get() == Boolean.TRUE) return;
                String value = FeatureFlags.spoofAvatar;
                if (value == null || value.isEmpty()) return;
                android.widget.ImageView iv = (android.widget.ImageView) param.thisObject;
                int id = iv.getId();
                if (id == android.view.View.NO_ID) return;
                if (followersId == 0) resolveIds(iv);
                if (id == tabAvatarId && tabAvatarId != 0) { watchTabAvatar(iv); return; }
                if (id != avatarId) return;
                ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                // Not on screen yet during the first bind: try again once it is laid out.
                if (!applyAvatar(iv)) iv.post(() -> applyAvatar(iv));
            }
        };
        XposedBridge.hookAllMethods(android.widget.ImageView.class, "setImageDrawable", hook);
        XposedBridge.hookAllMethods(android.widget.ImageView.class, "setImageBitmap", hook);
    }

    @android.annotation.SuppressLint("DiscouragedApi")
    private static void resolveIds(android.view.View v) {
        android.content.res.Resources r = v.getResources();
        String pkg = v.getContext().getPackageName();
        followingId = r.getIdentifier("profile_header_familiar_following_value", "id", pkg);
        titleId = r.getIdentifier("action_bar_title", "id", pkg);
        chevronId = r.getIdentifier("action_bar_title_chevron", "id", pkg);
        nameId = r.getIdentifier("profile_header_full_name_above_vanity", "id", pkg);
        nameAltId = r.getIdentifier("profile_header_full_name", "id", pkg);
        bioTextId = r.getIdentifier("profile_header_bio_text", "id", pkg);
        avatarId = r.getIdentifier("row_profile_header_imageview", "id", pkg);
        tabAvatarId = r.getIdentifier("profile_tab_icon_view", "id", pkg);
        // 0 never matches a real id; followersId last, it marks the lookup as done.
        int f = r.getIdentifier("profile_header_familiar_followers_value", "id", pkg);
        followersId = f != 0 ? f : -1;
    }

    /** Your own profile is on screen: the account switcher chevron is shown. */
    private static boolean ownProfileShown(android.view.View v) {
        if (chevronId == 0) return false;
        // Your profile stays loaded behind other screens (isShown still true): look for a chevron
        // that is really on screen.
        java.util.ArrayDeque<android.view.View> st = new java.util.ArrayDeque<>();
        st.push(v.getRootView());
        while (!st.isEmpty()) {
            android.view.View x = st.pop();
            if (!x.isShown()) continue;
            if (x.getId() == chevronId && onScreen(x)) return true;
            if (x instanceof android.view.ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        return false;
    }

    private static boolean onScreen(android.view.View x) {
        android.graphics.Rect r = new android.graphics.Rect();
        return x.isShown() && x.getGlobalVisibleRect(r) && r.width() > 0 && r.height() > 0;
    }

    /** The title is the one next to the shown chevron (not another screen's action bar title). */
    private static boolean besideChevron(android.view.View title) {
        android.view.ViewParent p = title.getParent();
        for (int i = 0; i < 5 && p instanceof android.view.ViewGroup g; i++, p = g.getParent()) {
            android.view.View c = g.findViewById(chevronId);
            if (c != null) return onScreen(c) && onScreen(title);
        }
        return false;
    }

    /** Your username as Instagram shows it (the action bar title before we change it). */
    private static volatile String realUsername, appliedUsername;

    /** Your real username once your profile has been shown (for the editor's preview), or null. */
    public static String knownUsername() { return realUsername; }

    private static void apply(android.widget.TextView tv) {
        try {
            if (!tv.isAttachedToWindow() || !tv.isShown() || !ownProfileShown(tv)) return;
            int id = tv.getId();
            if (id == titleId) {
                // The username only in the title next to the chevron (other screens' action bars
                // reuse this id); the badge as before.
                // Other screens' action bars reuse this id: only the title beside the chevron.
                if (!besideChevron(tv)) { badge(tv, false); return; }
                {
                    String user = FeatureFlags.spoofUsername;
                    CharSequence cur = tv.getText();
                    if (cur != null && cur.length() > 0 && !cur.toString().equals(user)
                            && !cur.toString().equals(appliedUsername)) realUsername = cur.toString();
                    if (user != null && !user.isEmpty()) {
                        appliedUsername = user;
                        setText(tv, user);
                    }
                }
                badge(tv, FeatureFlags.spoofVerified);
                // The header may have bound before your profile was on screen: catch up.
                applyHeader(tv.getRootView());
                return;
            }
            if (id == nameId || id == nameAltId) {
                String name = FeatureFlags.spoofName;
                // An empty name view is hidden by Instagram when you have no name; only fill a shown one.
                if (name != null && !name.isEmpty()) setText(tv, name);
                return;
            }
            if (id == bioTextId) {
                String bio = FeatureFlags.spoofBio;
                if (bio != null && !bio.isEmpty()) setText(tv, bio);
                return;
            }
            int v = id == followersId ? FeatureFlags.spoofFollowers : FeatureFlags.spoofFollowing;
            if (v < 0) return;
            setText(tv, shortCount(v));
        } catch (Throwable ignored) { }
    }

    private static void setText(android.widget.TextView tv, String text) {
        if (text.contentEquals(tv.getText())) return;
        SETTING.set(Boolean.TRUE);
        try { tv.setText(text); } finally { SETTING.remove(); }
    }

    /** Name, legacy bio and picture views already bound in the header. */
    private static void applyHeader(android.view.View root) {
        for (int id : new int[]{nameId, nameAltId, bioTextId}) {
            if (id == 0) continue;
            android.view.View v = root.findViewById(id);
            if (v instanceof android.widget.TextView t && t.getText() != null && t.getText().length() > 0) apply(t);
        }
        String value = FeatureFlags.spoofAvatar;
        if (avatarId != 0 && value != null && !value.isEmpty()
                && root.findViewById(avatarId) instanceof android.widget.ImageView iv) applyAvatar(iv);
    }

    /**
     * The profile tab's avatar (bottom right) shows the spoofed picture while your profile is on
     * screen, and Instagram's own picture again on other screens. Checked on each layout of the
     * window (switching screens lays it out), not every frame.
     */
    private static final java.util.WeakHashMap<android.widget.ImageView, android.graphics.drawable.Drawable> TAB_REAL = new java.util.WeakHashMap<>();
    private static final java.util.Set<android.widget.ImageView> TAB_WATCHED =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    private static void watchTabAvatar(android.widget.ImageView iv) {
        if (!(iv.getDrawable() instanceof ps.reso.instaeclipse.utils.profile.SpoofAvatar.CircleDrawable))
            TAB_REAL.put(iv, iv.getDrawable()); // Instagram set its own picture: remember it
        if (TAB_WATCHED.add(iv))
            iv.getViewTreeObserver().addOnGlobalLayoutListener(() -> syncTabAvatar(iv));
        iv.post(() -> syncTabAvatar(iv));
    }

    private static void syncTabAvatar(android.widget.ImageView iv) {
        try {
            if (!iv.isAttachedToWindow()) return;
            String value = FeatureFlags.spoofAvatar;
            boolean want = FeatureFlags.profileSpoofer && value != null && !value.isEmpty() && ownProfileShown(iv);
            boolean ours = iv.getDrawable() instanceof ps.reso.instaeclipse.utils.profile.SpoofAvatar.CircleDrawable;
            if (want && !ours) {
                android.graphics.Bitmap bitmap = ps.reso.instaeclipse.utils.profile.SpoofAvatar.bitmap(value);
                if (bitmap == null) return;
                TAB_REAL.put(iv, iv.getDrawable());
                SETTING.set(Boolean.TRUE);
                try { iv.setImageDrawable(new ps.reso.instaeclipse.utils.profile.SpoofAvatar.CircleDrawable(bitmap)); }
                finally { SETTING.remove(); }
            } else if (!want && ours) {
                SETTING.set(Boolean.TRUE);
                try { iv.setImageDrawable(TAB_REAL.get(iv)); } finally { SETTING.remove(); }
            }
        } catch (Throwable ignored) { }
    }

    /** Shows the spoofed picture on the header avatar; false when your profile isn't on screen. */
    private static boolean applyAvatar(android.widget.ImageView iv) {
        try {
            if (!iv.isAttachedToWindow() || !iv.isShown() || !ownProfileShown(iv)) return false;
            android.graphics.Bitmap bitmap = ps.reso.instaeclipse.utils.profile.SpoofAvatar.bitmap(FeatureFlags.spoofAvatar);
            if (bitmap == null) return true;
            if (iv.getDrawable() instanceof ps.reso.instaeclipse.utils.profile.SpoofAvatar.CircleDrawable d
                    && d.bitmap == bitmap) return true;
            SETTING.set(Boolean.TRUE);
            try { iv.setImageDrawable(new ps.reso.instaeclipse.utils.profile.SpoofAvatar.CircleDrawable(bitmap)); }
            finally { SETTING.remove(); }
            return true;
        } catch (Throwable t) {
            return true;
        }
    }

    /** After a change in the editor: apply it to your profile if it is on screen now. */
    public static void refreshNow() {
        try {
            android.app.Activity a = UIHookManager.getCurrentActivity();
            android.view.View root = a != null && a.getWindow() != null ? a.getWindow().peekDecorView() : null;
            if (root == null || !FeatureFlags.profileSpoofer) return;
            root.post(() -> {
                if (followersId == 0) resolveIds(root);
                android.view.View chevron = chevronId != 0 ? root.findViewById(chevronId) : null;
                if (chevron != null && chevron.getParent() instanceof android.view.ViewGroup bar
                        && bar.findViewById(titleId) instanceof android.widget.TextView title) apply(title);
                for (int id : new int[]{followersId, followingId}) {
                    if (id == 0 || id == -1) continue;
                    if (root.findViewById(id) instanceof android.widget.TextView t) apply(t);
                }
            });
        } catch (Throwable ignored) {}
    }

    /** For the bio state hook: your own profile is in front (current activity's window). */
    private static boolean ownProfileInFront() {
        try {
            android.app.Activity a = UIHookManager.getCurrentActivity();
            android.view.View root = a != null && a.getWindow() != null ? a.getWindow().peekDecorView() : null;
            if (root == null) return false;
            if (followersId == 0) resolveIds(root);
            return ownProfileShown(root);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Instagram's style: 1,234 · 12.3K · 1.2M. */
    public static String shortCount(int n) {
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
    public static final class VerifiedBadge extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint check = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        public VerifiedBadge() {
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
