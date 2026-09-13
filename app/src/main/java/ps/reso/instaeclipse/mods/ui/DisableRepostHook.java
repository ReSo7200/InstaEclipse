package ps.reso.instaeclipse.mods.ui;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Disable Repost (feed + reels/clips + profile grid).
 *
 * Repost is created optimistically/client-side, so dropping the network request does NOT stop
 * it — IG applies the repost locally regardless. We neutralise IG's OWN repost code paths.
 * There are two independent paths on 447.0.0.21.81, so this installs two hooks:
 *
 * 1) REELS + PROFILE-GRID — the shared RepostActionHandler. We swallow its void entry points,
 *    anchored on IG's stable {@code android_purge_26_q3_*} trace-scope markers (never obfuscated
 *    X.* names), so the tap does nothing:
 *      • RepostActionHandler.handleRepostButtonTapped(...)                         (tap entry)
 *      • OnBoardingExperiencesRepostActionHandlerProxy.handleRepostButtonTapped(...) (proxy)
 *      • RepostActionHandler.performRepostAction(...)                              (executor)
 *    All void → {@code setResult(null)} is a clean skip.
 *
 * 2) HOME FEED (timeline dense-UFI) — a SEPARATE path that does NOT route through
 *    RepostActionHandler (proven on-device: the handler hook fires yet the feed repost still
 *    commits). Empirical instrumentation showed the feed tap drives
 *    {@code RepostsRepository.postRepost}, whose coroutine {@code RepostsRepository$postRepost$2}
 *    performs BOTH the optimistic "Reposted"/Undo UI apply (OptimisticPostOperation) AND the
 *    network commit (OptimisticNetworkOperation — the {@code xdt_update_media_note_repost_text}
 *    mutation). We skip the repository's VOID launcher that builds+launches that coroutine, so
 *    nothing runs: no optimistic apply, no network. Being VOID, {@code setResult(null)} is a
 *    clean, sentinel-safe skip — unlike the coroutine body itself, whose {@code invokeSuspend}
 *    must return the exact Kotlin COROUTINE_SUSPENDED / Unit sentinel or the machinery crashes.
 *
 *    The launcher is resolved WITHOUT obfuscated names: load the stably-named coroutine class
 *    {@code com.instagram.reposts.data.RepostsRepository$postRepost$2}, take its owning
 *    RepostsRepository class (enclosing class, else its RepostsRepository-typed constructor
 *    param), and hook that class's unique {@code void(<params>, kotlin.jvm.functions.Function1)}
 *    launcher. likeRepost launches from other classes, so this can't touch it; removeRepost is a
 *    different method, so undo still works.
 *
 * Everything is gated behind {@link FeatureFlags#disableRepost} and wrapped in try/catch: a miss
 * logs and no-ops, it never crashes IG.
 */
public class DisableRepostHook {

    // Reels + profile-grid repost: shared RepostActionHandler tap/exec entry points (all void).
    private static final String[] NEUTRALIZE_ANCHORS = {
            "android_purge_26_q3_RepostActionHandler_handleRepostButtonTapped",
            "android_purge_26_q3_OnBoardingExperiencesRepostActionHandlerProxy_handleRepostButtonTapped",
            "android_purge_26_q3_RepostActionHandler_performRepostAction",
    };

    // Stably-named Kotlin coroutine that RepostsRepository.postRepost launches.
    private static final String POST_REPOST_COROUTINE =
            "com.instagram.reposts.data.RepostsRepository$postRepost$2";

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        int total = installActionHandler(bridge, classLoader);
        total += installFeedCommit(classLoader);

        if (total > 0) FeatureStatusTracker.setHooked("DisableRepost");
        ModuleLog.line("(IE|Repost) ✅ installed: " + total + " method(s)");
    }

    // Reels + profile path — swallow the shared handler's void entry points entirely.
    private int installActionHandler(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook skip = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.disableRepost) {
                    param.setResult(null); // void method → skip original entirely
                    ModuleLog.line("(IE|Repost) neutralized repost action");
                }
            }
        };

        int total = 0;
        Set<String> hooked = new HashSet<>();
        for (String anchor : NEUTRALIZE_ANCHORS) {
            try {
                for (MethodData md : bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings(anchor)))) {
                    String key = md.getDescriptor();
                    if (!hooked.add(key)) continue; // avoid double-hooking a shared method
                    try {
                        XposedBridge.hookMethod(md.getMethodInstance(classLoader), skip);
                        total++;
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                ModuleLog.line("(IE|Repost) ⚠️ anchor " + anchor + ": " + t.getMessage());
            }
        }
        return total;
    }

    // Home-feed path — skip the VOID RepostsRepository.postRepost launcher (sentinel-safe).
    private int installFeedCommit(ClassLoader classLoader) {
        XC_MethodHook skipPostRepost = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.disableRepost) {
                    param.setResult(null); // void launcher → coroutine never built/launched
                    ModuleLog.line("(IE|Repost) neutralized FEED repost (postRepost)");
                }
            }
        };

        int total = 0;
        try {
            Class<?> lambda = classLoader.loadClass(POST_REPOST_COROUTINE);

            // Owning RepostsRepository class: prefer the enclosing class; if R8 stripped that
            // attribute, fall back to the constructor param whose class declares the launcher.
            Class<?> owner = null;
            Class<?> enclosing = lambda.getEnclosingClass();
            if (hasPostRepostLauncher(enclosing)) owner = enclosing;
            if (owner == null) {
                outer:
                for (Constructor<?> c : lambda.getDeclaredConstructors()) {
                    for (Class<?> pt : c.getParameterTypes()) {
                        if (hasPostRepostLauncher(pt)) { owner = pt; break outer; }
                    }
                }
            }

            if (owner == null) {
                ModuleLog.line("(IE|Repost) ⚠️ RepostsRepository owner not resolved");
                return 0;
            }

            for (Method m : owner.getDeclaredMethods()) {
                if (!isPostRepostLauncher(m)) continue;
                try {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, skipPostRepost);
                    total++;
                } catch (Throwable ignored) {}
            }
            if (total == 0) ModuleLog.line("(IE|Repost) ⚠️ postRepost launcher not found on "
                    + owner.getName());
        } catch (Throwable t) {
            ModuleLog.line("(IE|Repost) ⚠️ feed postRepost hook: " + t.getMessage());
        }
        ModuleLog.line("(IE|Repost) feed postRepost launcher: " + total + " method(s)");
        return total;
    }

    /**
     * A RepostsRepository.postRepost launcher: {@code void} return, exactly two params, the second
     * being the Kotlin completion callback ({@code kotlin.jvm.functions.Function1} — kept
     * un-obfuscated in signatures). On RepostsRepository this uniquely identifies the postRepost
     * launcher (likeRepost is built from other classes; the 10-arg logging overloads don't match).
     */
    private static boolean isPostRepostLauncher(Method m) {
        if (m.getReturnType() != void.class) return false;
        Class<?>[] p = m.getParameterTypes();
        return p.length == 2 && "kotlin.jvm.functions.Function1".equals(p[1].getName());
    }

    private static boolean hasPostRepostLauncher(Class<?> c) {
        if (c == null || c.isPrimitive() || c.isInterface() || c.isArray()) return false;
        String n = c.getName();
        if (n.startsWith("java.") || n.startsWith("android") || n.startsWith("kotlin.")) return false;
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (isPostRepostLauncher(m)) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
