package ps.reso.instaeclipse.mods.misc;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class DisableStoryFlippingHook {

    private static final XC_MethodHook HOOK = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
            if (FeatureFlags.disableStoryFlipping) param.setResult(null);
        }
    };

    public void handleStoryFlippingDisable(DexKitBridge bridge) {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("StoryFlipping", Module.hostClassLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, HOOK);
                ModuleLog.line("(InstaEclipse | StoryFlipping): ✅ Hooked (dynamic check): " + cached.getDeclaringClass().getName() + "." + cached.getName());
                return;
            }
        }
        try {
            findAndHookMethod(bridge);
        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse | StoryFlipping): ❌ Error handling Story Flipping hook: " + e.getMessage());
        }
    }

    private void findAndHookMethod(DexKitBridge bridge) {
        try {
            // Step 1: the "story item finished → advance" callback on ReelViewerFragment.
            // IG 452+: typed A1L(ReelItem)V holding the "end_scene" check; the (Object)V
            // interface method became a bare bridge to it, and the progress callback also calls
            // it at 100%, so hooking it covers both paths.
            String rvf = "instagram.features.stories.fragment.ReelViewerFragment";
            List<MethodData> methods = bridge.findMethod(
                    FindMethod.create().matcher(
                            MethodMatcher.create()
                                    .declaredClass(rvf)
                                    .paramTypes("com.instagram.model.reels.ReelItem")
                                    .returnType("void")
                                    .usingStrings("end_scene")
                    )
            );
            // IG <=451: the (Object)V interface method itself held the body.
            if (methods.isEmpty()) {
                methods = bridge.findMethod(
                        FindMethod.create().matcher(
                                MethodMatcher.create()
                                        .declaredClass(rvf)
                                        .paramTypes("java.lang.Object")
                                        .returnType("void")
                                        .usingStrings("userSession")
                        )
                );
            }

            if (methods.isEmpty()) {
                ModuleLog.line("(InstaEclipse | StoryFlipping): ❌ story-advance method not found (end_scene / userSession).");
                return;
            }

            // Step 2: Hook the correct method
            for (MethodData method : methods) {
                try {
                    Method targetMethod = method.getMethodInstance(Module.hostClassLoader);
                    DexKitCache.saveMethod("StoryFlipping", targetMethod);
                    XposedBridge.hookMethod(targetMethod, HOOK);

                    ModuleLog.line("(InstaEclipse | StoryFlipping): ✅ Hooked (dynamic check): " +
                            method.getClassName() + "." + method.getName());
                    return;

                } catch (Exception e) {
                    ModuleLog.line("(InstaEclipse | StoryFlipping): ❌ Error hooking method: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            ModuleLog.line("(InstaEclipse | StoryFlipping): ❌ Error during dynamic method discovery: " + e.getMessage());
        }
    }
}
