package ps.reso.instaeclipse.mods.media;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Forces reel/video playback to a specific quality by hooking LiveTreeMediaDict's
 * video_versions getter and collapsing the list of available qualities down to whichever
 * one best matches the desired height — Instagram's player has no other choice left once
 * only one option remains.
 *
 * An earlier attempt at this feature rewrote raw Tigon network response bytes mid-stream,
 * but never actually fed the rewritten bytes back into what Instagram parses (the mutated
 * JSON was built and then only logged, never applied) — so it silently did nothing. This
 * version instead manipulates Instagram's own already-parsed model object, the same
 * approach HideSuggestedFeedItemsHook already uses successfully elsewhere in this codebase.
 *
 * The per-item height field is found without depending on any obfuscated name at all: the
 * concrete video-version class's height accessor calls Pando's getOptionalIntValueByHashCode
 * with a hardcoded hash constant that is simply Java's standard String.hashCode() of the
 * literal string "height" — a value fixed by the JDK spec, not by Instagram's build, so it's
 * computed locally and matched via DexKit rather than guessed from a stale reference.
 */
public class ForceReelQualityHook {

    // IG 442+ renamed both model classes. Central media class: LiveTreeMediaDict -> Media.
    // Per-version class: model.mediasize.ImmutablePandoVideoVersion -> api.schemas.ImmutablePandoVideoVersion.
    // Try the new name first, fall back to the old one so 436 and 447 both resolve.
    private static final String[] DICT_CLASS_CANDIDATES = {
            "com.instagram.feed.media.Media",             // IG 442+ (447)
            "com.instagram.feed.media.LiveTreeMediaDict"  // IG <=441 (436)
    };
    private static final String[] VIDEO_VERSION_CLASS_CANDIDATES = {
            "com.instagram.api.schemas.ImmutablePandoVideoVersion",      // IG 442+ (447)
            "com.instagram.model.mediasize.ImmutablePandoVideoVersion"   // IG <=441 (436)
    };
    // The height getter embeds getOptional...ByHashCode / A2E(hash, idx) where hash is the
    // JDK-fixed String.hashCode("height") (== -0x48c76ed9). Identical const in 436 and 447,
    // so it is matched via DexKit rather than by the per-version obfuscated method name or
    // the volatile Pando index.
    private static final int HEIGHT_HASH = "height".hashCode();

    private static final String CACHE_GETTER_KEY = "ForceReelQuality_VideoVersionsGetter";
    private static final String CACHE_HEIGHT_KEY = "ForceReelQuality_HeightGetterName";

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        try {
            Method videoVersionsGetter;
            String heightGetterName;

            if (DexKitCache.isCacheValid()) {
                videoVersionsGetter = DexKitCache.loadMethod(CACHE_GETTER_KEY, classLoader);
                heightGetterName = DexKitCache.loadString(CACHE_HEIGHT_KEY);
            } else {
                videoVersionsGetter = null;
                heightGetterName = null;
            }

            if (videoVersionsGetter == null || heightGetterName == null) {
                videoVersionsGetter = resolveVideoVersionsGetter(bridge, classLoader);
                heightGetterName = resolveHeightGetterName(bridge, classLoader);

                if (videoVersionsGetter == null || heightGetterName == null) {
                    ModuleLog.line("(InstaEclipse | ForceReelQuality): ❌ discovery failed");
                    return;
                }

                DexKitCache.saveMethod(CACHE_GETTER_KEY, videoVersionsGetter);
                DexKitCache.saveString(CACHE_HEIGHT_KEY, heightGetterName);
            }

            videoVersionsGetter.setAccessible(true);
            String finalHeightGetterName = heightGetterName;

            XposedBridge.hookMethod(videoVersionsGetter, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (FeatureFlags.forceReelQuality <= 0) return;
                    try {
                        Object result = param.getResult();
                        if (!(result instanceof List)) return;
                        List<?> versions = (List<?>) result;
                        if (versions.size() <= 1) return;

                        Object chosen = pickBestQuality(versions, FeatureFlags.forceReelQuality, finalHeightGetterName);
                        if (chosen != null) {
                            param.setResult(Collections.singletonList(chosen));
                        }
                    } catch (Throwable t) {
                        ModuleLog.line("(InstaEclipse | ForceReelQuality): ❌ hook body – " + t);
                    }
                }
            });

            // Mark hooked as soon as the hook is successfully attached, not only once a reel
            // with multiple qualities is actually intercepted — the status toast is built
            // ~1.5s after launch, before any video is guaranteed to have loaded yet.
            FeatureStatusTracker.setHooked("ForceReelQuality");
            ModuleLog.line("(InstaEclipse | ForceReelQuality): ✅ Hooked "
                    + videoVersionsGetter.getDeclaringClass().getName()
                    + "#" + videoVersionsGetter.getName()
                    + " (height=" + heightGetterName + ")");
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | ForceReelQuality): ❌ install – " + t);
        }
    }

    private static Method resolveVideoVersionsGetter(DexKitBridge bridge, ClassLoader classLoader) {
        for (String dictClass : DICT_CLASS_CANDIDATES) {
            try {
                List<MethodData> results = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass(dictClass)
                                .paramCount(0)
                                .usingEqStrings(List.of("video_versions"))));

                for (MethodData md : results) {
                    try {
                        Method m = md.getMethodInstance(classLoader);
                        if (m.getReturnType() != List.class) continue;
                        return m;
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                ModuleLog.line("(InstaEclipse | ForceReelQuality): ❌ resolveVideoVersionsGetter[" + dictClass + "] – " + t);
            }
        }
        return null;
    }

    private static String resolveHeightGetterName(DexKitBridge bridge, ClassLoader classLoader) {
        for (String versionClass : VIDEO_VERSION_CLASS_CANDIDATES) {
            try {
                List<MethodData> results = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass(versionClass)
                                .paramCount(0)
                                .returnType("java.lang.Integer")
                                .usingNumbers(List.of(HEIGHT_HASH))));

                if (!results.isEmpty()) return results.get(0).getName();
            } catch (Throwable t) {
                ModuleLog.line("(InstaEclipse | ForceReelQuality): ❌ resolveHeightGetterName[" + versionClass + "] – " + t);
            }
        }
        return null;
    }

    private static Object pickBestQuality(List<?> items, int desired, String heightGetterName) {
        Object best = null;
        int bestDelta = Integer.MAX_VALUE;
        int bestHeight = -1;
        for (Object item : items) {
            if (item == null) continue;
            try {
                Method m = item.getClass().getMethod(heightGetterName);
                Object hObj = m.invoke(item);
                if (!(hObj instanceof Integer)) continue;
                int h = (Integer) hObj;
                if (h <= 0) continue;

                if (desired == Integer.MAX_VALUE) {
                    if (h > bestHeight) { bestHeight = h; best = item; }
                } else {
                    int delta = Math.abs(h - desired);
                    if (delta < bestDelta) { bestDelta = delta; best = item; }
                }
            } catch (Throwable ignored) {}
        }
        return best;
    }
}
