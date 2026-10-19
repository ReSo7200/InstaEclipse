package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class CommentCopyHook {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Activity currentActivity = null;

    private static final String CACHE_KEY = "CommentCopy_ShowMenu";
    private static final String CACHE_RESOLVER_KEY = "CommentCopy_Resolver";

    // ── Install ───────────────────────────────────────────────────────────────
    //
    // Instagram rewrote comments on top of a modern MVVM/reducer architecture in newer builds;
    // the classic GestureDetector.onLongPress handlers this hook originally targeted (found via
    // the "fb_comment_long_press" string) become dead code once a build migrates — their
    // GestureDetector gets constructed but never actually receives touch events (confirmed by
    // live tracing on a build that had already migrated).
    // So the new mechanism is tried FIRST, and the old one kept as a fallback for any
    // Instagram version that hasn't migrated yet — never assume the newer architecture is
    // universal, always have a path back to what worked before.
    //
    // New mechanism: the trigger is Dcq.FmJ(String commentId, String mediaId, float, boolean)
    // — the concrete implementation of the shared LX/mxm interface. Dcq itself is obfuscated
    // and renames every build, but it's found without DexKit at all: it's the field on the
    // stable, non-obfuscated effect-handler class whose type has a method matching that exact
    // (String,String,float,boolean)V shape.
    // To get the actual comment MODEL (not just its id), the same repository lookup chain
    // Dcq.FmJ itself uses is replicated:
    //   1) Dcq's MediaCommentListRepository-typed field (stable class name, found by reflection)
    //   2) a field on that repository whose type exposes a public no-arg getValue() (a
    //      Kotlin State/Lazy-style holder — the method name itself is preserved since it's a
    //      real SDK interface, so this survives Dcq's own field/class renaming)
    //   3) the static resolver method (LX/DjK;->A01 in this build) that turns
    //      (thatValue, commentId, mediaId) into the comment model (LX/EGL;) — found via
    //      DexKit's addCaller from the already-resolved Dcq.FmJ, so renames of DjK/EGL don't
    //      break discovery either.
    // From the resolved model, the comment text is the longest non-trivial String field —
    // the same heuristic the old mechanism (and other features in this codebase) also use.
    private static final String EFFECT_HANDLER_CLASS =
            "com.instagram.comments.mvvm.view.fragment.CommentViewUiEffectHandler$handleCommentUiEffects$1";
    private static final String REPO_CLASS =
            "com.instagram.comments.mvvm.data.MediaCommentListRepository";

    private static java.lang.reflect.Field repoField;   // Dcq -> MediaCommentListRepository
    private static java.lang.reflect.Method resolverMethod; // static (Object,String,String) -> EGL

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        ps.reso.instaeclipse.mods.media.CommentMedia.trackCommentSheet(bridge, classLoader);
        // Track current Activity for showing dialogs
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                    currentActivity = (Activity) p.thisObject;
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                    if (currentActivity == p.thisObject) currentActivity = null;
                }
            });
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | CopyComment): ⚠️ Activity tracker – " + t);
        }

        // Cache path — try the new mechanism's cache, then the old mechanism's
        if (DexKitCache.isCacheValid()) {
            java.lang.reflect.Method cachedFmj = DexKitCache.loadMethod(CACHE_KEY, classLoader);
            java.lang.reflect.Method cachedResolver = DexKitCache.loadMethod(CACHE_RESOLVER_KEY, classLoader);
            if (cachedFmj != null && cachedResolver != null
                    && resolveRepoField(cachedFmj.getDeclaringClass())) {
                resolverMethod = cachedResolver;
                resolverMethod.setAccessible(true);
                XposedBridge.hookMethod(cachedFmj, SHOW_MENU_HOOK);
                ModuleLog.line("(InstaEclipse | CopyComment): ✅ Hooked (cached, new) "
                        + cachedFmj.getDeclaringClass().getName() + "." + cachedFmj.getName());
                FeatureStatusTracker.setHooked("CopyComment");
                return;
            }

            List<java.lang.reflect.Method> cachedLongPress =
                    DexKitCache.loadMethods(CACHE_KEY_OLD, classLoader);
            if (cachedLongPress != null && !cachedLongPress.isEmpty()) {
                for (java.lang.reflect.Method m : cachedLongPress) {
                    XposedBridge.hookMethod(m, LONG_PRESS_HOOK);
                }
                ModuleLog.line("(InstaEclipse | CopyComment): ✅ Hooked (cached, legacy) – "
                        + cachedLongPress.size() + " method(s)");
                FeatureStatusTracker.setHooked("CopyComment");
                return;
            }
        }

        try {
            if (findAndHookNew(bridge, classLoader)) return;
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | CopyComment): ⚠️ new-architecture path failed, "
                    + "falling back to legacy – " + t);
        }

        try {
            findAndHookOld(bridge, classLoader);
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | CopyComment): ❌ install – " + t.getMessage());
        }
    }

    private boolean findAndHookNew(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            Class<?> effectHandlerClass = classLoader.loadClass(EFFECT_HANDLER_CLASS);

            java.lang.reflect.Method fmj = null;
            for (java.lang.reflect.Field f : effectHandlerClass.getDeclaredFields()) {
                Class<?> t = f.getType();
                for (java.lang.reflect.Method cand : t.getDeclaredMethods()) {
                    Class<?>[] p = cand.getParameterTypes();
                    if (cand.getReturnType() == void.class && p.length == 4
                            && p[0] == String.class && p[1] == String.class
                            && p[2] == float.class && p[3] == boolean.class) {
                        fmj = cand;
                        break;
                    }
                }
                if (fmj != null) break;
            }

            if (fmj == null) {
                ModuleLog.line("(InstaEclipse | CopyComment): ❌ Dcq.FmJ-equivalent not found "
                        + "(older Instagram version? falling back to legacy)");
                return false;
            }
            fmj.setAccessible(true);

            if (!resolveRepoField(fmj.getDeclaringClass())) {
                ModuleLog.line("(InstaEclipse | CopyComment): ❌ repository field not found on "
                        + fmj.getDeclaringClass().getName());
                return false;
            }

            List<MethodData> callees = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramCount(3)
                            .addCaller(MethodMatcher.create(fmj))));

            for (MethodData md : callees) {
                try {
                    java.lang.reflect.Method cm = md.getMethodInstance(classLoader);
                    if (!java.lang.reflect.Modifier.isStatic(cm.getModifiers())) continue;
                    Class<?>[] p = cm.getParameterTypes();
                    if (p[1] != String.class || p[2] != String.class) continue;
                    if (cm.getReturnType() == void.class || cm.getReturnType().isPrimitive()) continue;
                    resolverMethod = cm;
                    resolverMethod.setAccessible(true);
                    break;
                } catch (Throwable ignored) {}
            }

            if (resolverMethod == null) {
                ModuleLog.line("(InstaEclipse | CopyComment): ❌ resolver method not found among FmJ's callees");
                return false;
            }

            XposedBridge.hookMethod(fmj, SHOW_MENU_HOOK);
            DexKitCache.saveMethod(CACHE_KEY, fmj);
            DexKitCache.saveMethod(CACHE_RESOLVER_KEY, resolverMethod);
            FeatureStatusTracker.setHooked("CopyComment");
            ModuleLog.line("(InstaEclipse | CopyComment): ✅ Hooked " + fmj.getDeclaringClass().getName()
                    + "." + fmj.getName() + " (resolver=" + resolverMethod.getDeclaringClass().getName()
                    + "." + resolverMethod.getName() + ")");
            return true;
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | CopyComment): ❌ findAndHookNew – " + t);
            return false;
        }
    }

    // ── Legacy fallback (pre-MVVM Instagram versions) ───────────────────────────

    private static final String CACHE_KEY_OLD = "CommentCopy_LongPress";

    private void findAndHookOld(DexKitBridge bridge, ClassLoader classLoader) {
        List<MethodData> found = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                        .name("onLongPress")
                        .usingStrings("fb_comment_long_press")
                )
        );

        if (found.isEmpty()) {
            found = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .name("onLongPress")
                            .usingStrings("comment_row_component")
                    )
            );
        }

        if (found.isEmpty()) {
            List<ClassData> classes = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                            .usingStrings("fb_comment_long_press")
                    )
            );
            for (ClassData cd : classes) {
                found.addAll(bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass(cd.getName())
                                .name("onLongPress")
                        )
                ));
            }
        }

        if (found.isEmpty()) {
            ModuleLog.line("(InstaEclipse | CopyComment): ❌ legacy onLongPress not found via DexKit either");
            return;
        }

        List<java.lang.reflect.Method> hooked = new ArrayList<>();
        for (MethodData md : found) {
            try {
                java.lang.reflect.Method m = md.getMethodInstance(classLoader);
                XposedBridge.hookMethod(m, LONG_PRESS_HOOK);
                hooked.add(m);
                ModuleLog.line("(InstaEclipse | CopyComment): ✅ Hooked (legacy) "
                        + md.getClassName() + ".onLongPress");
            } catch (Throwable t) {
                ModuleLog.line("(InstaEclipse | CopyComment): ❌ legacy hook – " + t.getMessage());
            }
        }

        if (!hooked.isEmpty()) {
            DexKitCache.saveMethods(CACHE_KEY_OLD, hooked);
            FeatureStatusTracker.setHooked("CopyComment");
        }
    }

    private static final XC_MethodHook LONG_PRESS_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
            if (!FeatureFlags.enableCopyComment) return;
            try {
                String text = extractCommentTextLegacy(param.thisObject);
                if (text == null || text.trim().isEmpty()) return;

                Context ctx = currentActivity;
                if (ctx == null) return;

                showCopyPopup(ctx, text.trim());
            } catch (Throwable t) {
                ModuleLog.line("(InstaEclipse | CopyComment): ❌ legacy hook body – " + t);
            }
        }
    };

    private static final String CACHE_ITEM_CLASS = "CommentCopy_ItemClass";
    private static final String CACHE_TEXT_FIELD = "CommentCopy_TextField";

    private static String extractCommentTextLegacy(Object gestureListener) {
        if (DexKitCache.isCacheValid()) {
            String itemClass = DexKitCache.loadString(CACHE_ITEM_CLASS);
            String textField = DexKitCache.loadString(CACHE_TEXT_FIELD);
            if (itemClass != null && textField != null) {
                try {
                    Object item = findItemByClass(gestureListener, itemClass);
                    if (item == null) return null;
                    Field tf = item.getClass().getDeclaredField(textField);
                    tf.setAccessible(true);
                    String val = (String) tf.get(item);
                    return (val != null && !val.isEmpty()) ? val : null;
                } catch (Throwable ignored) {
                }
            }
        }

        return discoverAndCacheLegacy(gestureListener);
    }

    private static Object findItemByClass(Object gestureListener, String targetClassName) {
        try {
            for (Field f1 : gestureListener.getClass().getDeclaredFields()) {
                if (!isObfuscatedObject(f1)) continue;
                f1.setAccessible(true);
                Object holder = f1.get(gestureListener);
                if (holder == null) continue;
                for (Field f2 : holder.getClass().getDeclaredFields()) {
                    if (!isObfuscatedObject(f2)) continue;
                    f2.setAccessible(true);
                    Object item = f2.get(holder);
                    if (item != null && item.getClass().getName().equals(targetClassName))
                        return item;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static String discoverAndCacheLegacy(Object gestureListener) {
        try {
            ClassLoader cl = gestureListener.getClass().getClassLoader();
            Class<?> userClass = Class.forName(
                    "com.instagram.user.model.User", false, cl);

            for (Field f1 : gestureListener.getClass().getDeclaredFields()) {
                if (!isObfuscatedObject(f1)) continue;
                f1.setAccessible(true);
                Object holder = f1.get(gestureListener);
                if (holder == null) continue;

                for (Field f2 : holder.getClass().getDeclaredFields()) {
                    if (!isObfuscatedObject(f2)) continue;
                    f2.setAccessible(true);
                    Object item = f2.get(holder);
                    if (item == null) continue;
                    if (!hasFieldOfType(item.getClass(), userClass)) continue;

                    String[] found = findTextFieldLegacy(item);
                    if (found != null) {
                        DexKitCache.saveString(CACHE_ITEM_CLASS, item.getClass().getName());
                        DexKitCache.saveString(CACHE_TEXT_FIELD, found[0]);
                        ModuleLog.line("(InstaEclipse | CopyComment): cached (legacy) "
                                + item.getClass().getName() + "." + found[0]);
                        return found[1];
                    }
                    return null;
                }
            }
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | CopyComment): ❌ legacy discover – " + t);
        }
        return null;
    }

    private static String[] findTextFieldLegacy(Object item) {
        String bestName = null;
        String bestVal  = null;
        for (Field f : item.getClass().getDeclaredFields()) {
            if (f.getType() != String.class) continue;
            try {
                f.setAccessible(true);
                String val = (String) f.get(item);
                if (val == null || val.isEmpty()) continue;
                if (val.matches("\\d+")) continue;
                if (val.matches("\\d+_\\d+")) continue;
                if (val.startsWith("http")) continue;
                if (bestVal == null || val.length() > bestVal.length()) {
                    bestName = f.getName();
                    bestVal  = val;
                }
            } catch (Throwable ignored) {}
        }
        return (bestName != null) ? new String[]{bestName, bestVal} : null;
    }

    private static boolean isObfuscatedObject(Field f) {
        Class<?> t = f.getType();
        if (t.isPrimitive() || t == String.class) return false;
        String n = t.getName();
        return !n.startsWith("android.") && !n.startsWith("java.")
                && !n.startsWith("kotlin.") && !n.startsWith("androidx.");
    }

    private static boolean hasFieldOfType(Class<?> clazz, Class<?> target) {
        for (Field f : clazz.getDeclaredFields()) {
            if (target.isAssignableFrom(f.getType())) return true;
        }
        return false;
    }

    private static boolean resolveRepoField(Class<?> dcqClass) {
        try {
            Class<?> repoClass = dcqClass.getClassLoader().loadClass(REPO_CLASS);
            for (java.lang.reflect.Field f : dcqClass.getDeclaredFields()) {
                if (f.getType() == repoClass) {
                    f.setAccessible(true);
                    repoField = f;
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // Finds a field on `repo` whose type has a public no-arg "getValue" method (a Kotlin
    // State/Lazy-style holder) that yields an instance of targetType — the repository has
    // several such holders (loading state, ui state, etc.), so the resolver's own expected
    // parameter type disambiguates which one is the actual comment cache.
    private static Object resolveHeldValue(Object repo, Class<?> targetType) {
        for (java.lang.reflect.Field f : repo.getClass().getDeclaredFields()) {
            try {
                java.lang.reflect.Method getValue = f.getType().getMethod("getValue");
                f.setAccessible(true);
                Object holder = f.get(repo);
                if (holder == null) continue;
                Object value = getValue.invoke(holder);
                if (targetType.isInstance(value)) return value;
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static final XC_MethodHook SHOW_MENU_HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
            if (!FeatureFlags.enableCopyComment) return;
            try {
                Activity ctx = currentActivity;
                if (ctx == null) return;

                String commentId = (String) param.args[0];
                String mediaId = (String) param.args[1];

                Object repo = repoField.get(param.thisObject);
                if (repo == null) return;
                Object heldValue = resolveHeldValue(repo, resolverMethod.getParameterTypes()[0]);
                if (heldValue == null) return;

                Object comment = resolverMethod.invoke(null, heldValue, commentId, mediaId);
                if (comment == null) return;

                String text = findLongestTextField(comment);
                // #242: image / GIF comments get a Download button (GIF-only comments have no text)
                java.util.List<String> media = ps.reso.instaeclipse.mods.media.CommentMedia.urls(ctx, comment);
                if ((text == null || text.trim().isEmpty()) && media.isEmpty()) return;

                String[] names = media.isEmpty() ? new String[2] // {commenter, post author} for the file name
                        : ps.reso.instaeclipse.mods.media.CommentMedia.names(comment, mediaId, heldValue, repo, param.thisObject);
                showCopyPopup(ctx, text == null ? "" : text.trim(), media, commentId, names);
            } catch (Throwable t) {
                ModuleLog.line("(InstaEclipse | CopyComment): ❌ hook body – " + t);
            }
        }
    };

    // ── Comment text extraction from the resolved model ─────────────────────────

    private static String findLongestTextField(Object model) {
        String best = null;
        for (java.lang.reflect.Field f : model.getClass().getDeclaredFields()) {
            if (f.getType() != String.class) continue;
            try {
                f.setAccessible(true);
                String val = (String) f.get(model);
                if (val == null || val.isEmpty()) continue;
                if (val.matches("\\d+")) continue;
                if (val.matches("\\d+_\\d+")) continue;
                if (val.startsWith("http")) continue;
                if (best == null || val.length() > best.length()) best = val;
            } catch (Throwable ignored) {}
        }
        return best;
    }

    // ── Copy popup ────────────────────────────────────────────────────────────

    private static void showCopyPopup(final Context ctx, final String text) {
        showCopyPopup(ctx, text, java.util.Collections.emptyList(), null, new String[2]);
    }

    private static void showCopyPopup(final Context ctx, final String text,
                                      final java.util.List<String> media, final String commentId,
                                      final String[] names) {
        MAIN.post(() -> {
            try {
                IeDialog dlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_comment_copy_title));
                if (!text.isEmpty()) dlg.quote(text, 5);

                java.util.List<CharSequence> labels = new java.util.ArrayList<>();
                java.util.List<Runnable> actions = new java.util.ArrayList<>();
                if (!media.isEmpty()) {
                    labels.add(I18n.t(ctx, R.string.ig_dl_title));
                    actions.add(() -> ps.reso.instaeclipse.mods.media.CommentMedia.download(ctx, media, commentId, names[0], names[1]));
                    // GIF / photo comments: copy the media link (Android can't put an animated GIF on the clipboard)
                    labels.add(I18n.t(ctx, R.string.ig_comment_copy_media_link));
                    actions.add(() -> copyToClipboard(ctx, media.get(0)));
                }
                if (!text.isEmpty()) {
                    labels.add(I18n.t(ctx, R.string.ig_comment_copy_full));
                    actions.add(() -> copyToClipboard(ctx, text));
                    labels.add(I18n.t(ctx, R.string.ig_comment_select_part));
                    actions.add(() -> showSelectDialog(ctx, text));
                    // Translate (#233): hand the text to the user's translation app.
                    labels.add(I18n.t(ctx, R.string.ig_translate_text));
                    actions.add(() -> ps.reso.instaeclipse.utils.core.TextTranslator.translate(ctx, text));
                }
                dlg.choices(labels.toArray(new CharSequence[0]), -1, which -> actions.get(which).run());
                dlg.show();
            } catch (Throwable t) {
                ModuleLog.line("(InstaEclipse | CopyComment): ❌ Popup – " + t.getMessage());
            }
        });
    }

    // ── Select dialog ─────────────────────────────────────────────────────────

    private static void showSelectDialog(final Context ctx, final String text) {
        MAIN.post(() -> {
            try {
                IeDialog dlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_comment_select_title));
                final EditText et = dlg.selectableText(text, 8);
                dlg.secondary(I18n.t(ctx, R.string.ig_mention_copy_all), () -> copyToClipboard(ctx, text));
                dlg.primary(I18n.t(ctx, R.string.ig_comment_copy_selected), () -> {
                    int s = et.getSelectionStart(), e = et.getSelectionEnd();
                    copyToClipboard(ctx, (s >= 0 && e > s) ? text.substring(s, e) : text);
                });
                dlg.show();
                et.requestFocus();
            } catch (Throwable t) {
                ModuleLog.line("(InstaEclipse | CopyComment): ❌ SelectDialog – " + t.getMessage());
            }
        });
    }

    // ── Clipboard ─────────────────────────────────────────────────────────────

    private static void copyToClipboard(final Context ctx, final String text) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("comment", text));
                MAIN.post(() ->
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_comment_copied),
                                Toast.LENGTH_SHORT).show());
            }
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | CopyComment): ❌ Copy – " + t.getMessage());
        }
    }
}
