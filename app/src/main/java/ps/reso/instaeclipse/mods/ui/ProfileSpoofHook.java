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
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

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
        hookCountTaps();
        hookListArguments(cl);
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
            if (!found.isEmpty()) getters[which] = found.get(0);
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

    private static final Method[] getters = new Method[4];
    private static volatile long redirectUntil;
    private static volatile long infoUntil;

    /** True while your own profile is in front (kept alive by taps on it). */
    public static boolean infoRedirectArmed() { return System.currentTimeMillis() < infoUntil; }

    public static boolean ownProfileOnScreen(android.view.View v) { return ownProfileShown(v); }

    /** The profile in front is yours or the copied one. */
    public static boolean spoofedProfileInFront(android.view.View v) {
        if (!FeatureFlags.profileSpoofer) return false;
        return ownProfileShown(v) || (!FeatureFlags.spoofTargetId.isEmpty() && infoRedirectArmed());
    }

    /** True for a short while after you tap Followers or Following on your own profile. */
    public static boolean listRedirectArmed() { return System.currentTimeMillis() < redirectUntil; }

    private static void hookCountTaps() {
        XposedBridge.hookAllMethods(android.view.View.class, "performClick", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.profileSpoofer || FeatureFlags.spoofTargetId.isEmpty()) return;
                android.view.View v = (android.view.View) param.thisObject;
                if (followersId == 0) resolveIds(v);
                if (profileTabId > 0 && v.getId() == profileTabId) infoUntil = System.currentTimeMillis() + 30_000;
                else if (infoRedirectArmed() || ownProfileShown(v)) infoUntil = System.currentTimeMillis() + 30_000;
                if (followersId <= 0 || !isCountView(v)) return;
                if (ownProfileShown(v)) redirectUntil = System.currentTimeMillis() + 60_000;
            }
        });
    }

    /** The list opened from your counts is told to load the copied account instead of you. */
    private static void hookListArguments(ClassLoader cl) {
        try {
            Class<?> fragment = Class.forName("androidx.fragment.app.Fragment", false, cl);
            XposedBridge.hookMethod(fragment.getMethod("setArguments", android.os.Bundle.class), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!listRedirectArmed() || !(param.args[0] instanceof android.os.Bundle b)) return;
                    String self = selfId, target = FeatureFlags.spoofTargetId;
                    if (self == null || target.isEmpty()) return;
                    boolean moved = false;
                    for (String key : b.keySet()) {
                        Object v = b.get(key);
                        if ((key.endsWith("FOLLOWER_COUNT") || key.endsWith("FollowerCount")) && FeatureFlags.spoofFollowers >= 0) {
                            b.putInt(key, FeatureFlags.spoofFollowers);
                        } else if ((key.endsWith("FOLLOWING_COUNT") || key.endsWith("FollowingCount")) && FeatureFlags.spoofFollowing >= 0) {
                            b.putInt(key, FeatureFlags.spoofFollowing);
                        } else if (key.endsWith("PROFILE_USER_NAME") && !FeatureFlags.spoofUsername.isEmpty()) {
                            b.putString(key, FeatureFlags.spoofUsername);
                            moved = true;
                        } else if (v != null && !(v instanceof String) && !(v instanceof Number) && !(v instanceof Boolean)
                                && !(v instanceof android.os.Bundle) && v.getClass().getName().startsWith("com.instagram")) {
                            moved |= swapId(v, self, target, 0);
                        }
                        if (self.equals(v)) {
                            b.putString(key, target);
                            moved = true;
                        }
                    }
                }
            });
        } catch (Throwable t) {
            ModuleLog.line(TAG + "list arguments hook failed: " + t);
        }
    }

    private static boolean swapId(Object o, String self, String target, int depth) {
        boolean moved = false;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(o);
                    if (self.equals(v)) {
                        f.set(o, target);
                        moved = true;
                    } else if (v instanceof Long l && l == Long.parseLong(self) && f.getType() == long.class) {
                        f.setLong(o, Long.parseLong(target));
                        moved = true;
                    } else if (depth < 2 && v != null && v.getClass().getName().startsWith("com.instagram")) {
                        moved |= swapId(v, self, target, depth + 1);
                    }
                } catch (Throwable ignored) {}
            }
        }
        return moved;
    }

    /** The tapped view is a count or sits next to one inside the same header cell. */
    private static boolean isCountView(android.view.View v) {
        android.view.View node = v;
        for (int i = 0; i < 2 && node != null; i++) {
            if (hasId(node, 2)) return true;
            android.view.ViewParent p = node.getParent();
            node = p instanceof android.view.View pv ? pv : null;
        }
        return false;
    }

    private static boolean hasId(android.view.View v, int depth) {
        int id = v.getId();
        if (id != android.view.View.NO_ID && (id == followersId || id == followingId
                || id == stackedFollowersId || id == stackedFollowingId)) return true;
        if (depth > 0 && v instanceof android.view.ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) if (hasId(g.getChildAt(i), depth - 1)) return true;
        }
        return false;
    }

    public static boolean isOwn(Object user) { return isSelf(user); }

    /** True for your own account and for the profile copied into the spoofer. */
    public static boolean isOwnOrCopied(Object user) {
        if (user == null) return false;
        String id = userId(user);
        return id != null && (id.equals(selfId) || (FeatureFlags.profileSpoofer && id.equals(FeatureFlags.spoofTargetId)));
    }

    /** Copies the profile on screen (its User model plus the header views) into the spoofer. */
    public static boolean copyProfile(android.app.Activity a, Object user, String username, String bio) {
        try {
            String id = userId(user);
            if (id == null || username == null || user == null) return false;
            android.view.View root = a.getWindow().getDecorView();
            if (followersId == 0) resolveIds(root);
            int posts = number(user, 2), followers = number(user, 0), following = number(user, 1);
            Boolean verified = null;
            if (getters[3] != null) verified = (Boolean) getters[3].invoke(user);
            String name = "";
            for (int vid : new int[]{nameId, nameAltId}) {
                if (vid == 0) continue;
                android.view.View v = root.findViewById(vid);
                if (v instanceof android.widget.TextView t && t.isShown() && t.getText() != null && t.getText().length() > 0) {
                    name = t.getText().toString();
                    break;
                }
            }
            String avatar = "";
            android.view.View av = avatarId != 0 ? root.findViewById(avatarId) : null;
            if (av instanceof android.widget.ImageView iv && iv.getDrawable() != null) {
                android.graphics.drawable.Drawable d = iv.getDrawable();
                int w = Math.max(1, d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : iv.getWidth());
                int h = Math.max(1, d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : iv.getHeight());
                android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
                d.setBounds(0, 0, w, h);
                d.draw(new android.graphics.Canvas(bm));
                String b64 = ps.reso.instaeclipse.utils.profile.SpoofAvatar.encode(bm);
                if (b64 != null) avatar = b64;
            }
            FeatureFlags.spoofTargetId = id;
            FeatureFlags.spoofUsername = username;
            FeatureFlags.spoofName = withoutFollowLabel(a, name);
            FeatureFlags.spoofBio = bio == null ? "" : bio.trim();
            FeatureFlags.spoofAvatar = avatar;
            FeatureFlags.spoofPosts = posts;
            FeatureFlags.spoofFollowers = followers;
            FeatureFlags.spoofFollowing = following;
            FeatureFlags.spoofVerified = Boolean.TRUE.equals(verified);
            FeatureFlags.profileSpoofer = true;
            ps.reso.instaeclipse.utils.core.SettingsManager.saveAllFlags();
            refreshNow();
            return true;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "copy failed: " + t);
            return false;
        }
    }

    /** The "Follows you" label is drawn into the name view: never copy it with the name. */
    private static String withoutFollowLabel(android.content.Context c, String name) {
        for (int res : new int[]{ps.reso.instaeclipse.R.string.ig_profile_follows_you, ps.reso.instaeclipse.R.string.ig_profile_not_following_you}) {
            String tail = " · " + ps.reso.instaeclipse.utils.i18n.I18n.t(c, res);
            if (name.endsWith(tail)) return name.substring(0, name.length() - tail.length());
        }
        return name;
    }

    private static int number(Object user, int which) {
        try {
            Method m = getters[which];
            if (m == null) return -1;
            Object v = m.invoke(user);
            return v instanceof Number n ? (int) Math.min(n.longValue(), Integer.MAX_VALUE) : -1;
        } catch (Throwable t) {
            return -1;
        }
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

    private static int followButtonId, followContextTextId, similarTitleId;
    private static final int[] hideIds = new int[4];
    private static int stackedFollowersId, stackedFollowingId, backId, profileTabId;
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
                boolean extra = id != 0 && (id == followButtonId || id == followContextTextId || id == similarTitleId);
                if (id != followersId && id != followingId && id != titleId
                        && id != nameId && id != nameAltId && id != bioTextId && !extra) return;
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
        stackedFollowersId = r.getIdentifier("profile_header_followers_stacked_familiar", "id", pkg);
        stackedFollowingId = r.getIdentifier("profile_header_following_stacked_familiar", "id", pkg);
        backId = r.getIdentifier("action_bar_button_back", "id", pkg);
        followButtonId = r.getIdentifier("profile_header_follow_button", "id", pkg);
        followContextTextId = r.getIdentifier("profile_header_follow_context_text", "id", pkg);
        similarTitleId = r.getIdentifier("similar_accounts_carousel_title", "id", pkg);
        hideIds[0] = r.getIdentifier("profile_header_actions_top_row", "id", pkg);
        hideIds[1] = r.getIdentifier("row_profile_header_button_chaining", "id", pkg);
        hideIds[2] = r.getIdentifier("similar_accounts_container", "id", pkg);
        hideIds[3] = r.getIdentifier("profile_header_follow_context", "id", pkg);
        profileTabId = r.getIdentifier("profile_tab", "id", pkg);
        titleId = r.getIdentifier("action_bar_title", "id", pkg);
        chevronId = r.getIdentifier("action_bar_title_chevron", "id", pkg);
        nameId = r.getIdentifier("profile_header_full_name_above_vanity", "id", pkg);
        nameAltId = r.getIdentifier("profile_header_full_name", "id", pkg);
        bioTextId = r.getIdentifier("profile_header_bio_text", "id", pkg);
        avatarId = r.getIdentifier("row_profile_header_imageview", "id", pkg);
        tabAvatarId = r.getIdentifier("tab_avatar", "id", pkg); // the bottom bar's profile tab picture
        // 0 never matches a real id; followersId last, it marks the lookup as done.
        int f = r.getIdentifier("profile_header_familiar_followers_value", "id", pkg);
        followersId = f != 0 ? f : -1;
    }

    /** Your own profile is on screen: the account switcher chevron is shown. */
    /** The copied account's profile is a normal profile: hide what makes it look like someone else's. */
    private static void hideCopiedExtras(android.view.View root) {
        for (int hid : hideIds) {
            if (hid == 0) continue;
            android.view.View x = root.findViewById(hid);
            if (x != null && x.getVisibility() != android.view.View.GONE) x.setVisibility(android.view.View.GONE);
        }
    }

    private static boolean ownProfileShown(android.view.View v) {
        if (followersId == 0) resolveIds(v);
        if (chevronShown(v)) return true;
        // One account has no switcher chevron: the profile tab is selected and no back arrow is shown.
        if (profileTabId == 0) return false;
        android.view.View root = v.getRootView();
        android.view.View tab = root.findViewById(profileTabId);
        if (tab == null || !tab.isSelected()) return false;
        java.util.ArrayDeque<android.view.View> st = new java.util.ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            android.view.View x = st.pop();
            if (!x.isShown()) continue;
            if (x.getId() == backId && backId != 0 && onScreen(x)) return false;
            if (x instanceof android.view.ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        return true;
    }

    private static boolean chevronShown(android.view.View v) {
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
        if (!chevronShown(title)) return onScreen(title);
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
            if (id != 0 && (id == followButtonId || id == followContextTextId || id == similarTitleId)) {
                if (!FeatureFlags.spoofTargetId.isEmpty()) hideCopiedExtras(tv.getRootView());
                return;
            }
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
                badge(tv, FeatureFlags.spoofVerified && FeatureFlags.spoofTargetId.isEmpty());
                // The header may have bound before your profile was on screen: catch up.
                applyHeader(tv.getRootView());
                return;
            }
            if (id == nameId || id == nameAltId) {
                String name = withoutFollowLabel(tv.getContext(), FeatureFlags.spoofName);
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
            boolean want = FeatureFlags.profileSpoofer && value != null && !value.isEmpty();
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
