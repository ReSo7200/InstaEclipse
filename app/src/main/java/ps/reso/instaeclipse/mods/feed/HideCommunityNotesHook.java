package ps.reso.instaeclipse.mods.feed;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Hide community notes on posts and reels (#259).
 *
 * The note isn't a separate media field: it is media_overlay_info.notes_bloks_data, read through
 * one getter of {@code com.instagram.api.schemas.MediaOverlayPayloadSchemaIntf} (CuQ() on 452)
 * by the feed, reels and options-sheet code — all of which already handle null. Return null from
 * that getter on both concrete schema classes. media_overlay_info itself is left alone: it also
 * carries the sensitive-content / misinformation overlays.
 *
 * Anchor: in ImmutablePandoMediaOverlayPayloadSchema the getter reads Pando field hash 0xc2d475a
 * ("notes_bloks_data", same on 451/452); the method name is looked up at runtime.
 */
public class HideCommunityNotesHook {

    private static final String TAG = "(InstaEclipse | CommunityNotes): ";
    private static final String CACHE_KEY = "CommunityNotesGetter";
    private static final String PANDO = "com.instagram.api.schemas.ImmutablePandoMediaOverlayPayloadSchema";
    private static final String MUTABLE = "com.instagram.api.schemas.MediaOverlayPayloadSchema";
    private static final int NOTES_BLOKS_DATA = 0xc2d475a;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        String name = DexKitCache.isCacheValid() ? DexKitCache.loadString(CACHE_KEY) : null;
        if (name == null) {
            try {
                List<MethodData> found = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(PANDO).paramCount(0).usingNumbers(NOTES_BLOKS_DATA)));
                if (found.size() != 1) {
                    ModuleLog.line(TAG + "❌ notes getter not found (" + found.size() + ")");
                    return;
                }
                name = found.get(0).getName();
                DexKitCache.saveString(CACHE_KEY, name);
            } catch (Throwable t) {
                ModuleLog.line(TAG + "❌ " + t);
                return;
            }
        }
        XC_MethodHook hide = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (FeatureFlags.hideCommunityNotes) param.setResult(null);
            }
        };
        int n = 0;
        for (String cls : new String[]{PANDO, MUTABLE}) {
            try {
                Method m = cl.loadClass(cls).getDeclaredMethod(name);
                XposedBridge.hookMethod(m, hide);
                n++;
            } catch (Throwable t) {
                ModuleLog.line(TAG + "⚠️ " + cls + "." + name + ": " + t);
            }
        }
        ModuleLog.line(TAG + "✅ notes getter " + name + " hooked on " + n + " class(es)");
        if (n > 0) FeatureStatusTracker.setHooked("HideCommunityNotes");
    }
}
