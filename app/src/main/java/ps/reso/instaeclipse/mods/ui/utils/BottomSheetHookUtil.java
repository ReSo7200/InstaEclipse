package ps.reso.instaeclipse.mods.ui.utils;

import static ps.reso.instaeclipse.mods.ghost.ui.GhostEmojiManager.addGhostEmojiNextToInbox;
import static ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity;
import static ps.reso.instaeclipse.mods.ui.UIHookManager.setupHooks;

import android.app.Activity;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;

public class BottomSheetHookUtil {

    private static final String CACHE_KEY = "BottomSheet";

    public static void hookBottomSheetNavigator(DexKitBridge bridge) {
        // Try cache first
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod(CACHE_KEY, Module.hostClassLoader);
            if (cached != null) {
                hookMethod(cached);
                return;
            }
        }

        try {
            List<MethodData> methods = bridge.findMethod(
                    FindMethod.create()
                            .matcher(MethodMatcher.create().usingStrings("BottomSheetConstants"))
            );

            for (MethodData method : methods) {
                if (!method.getClassName().equals("com.instagram.mainactivity.InstagramMainActivity")) continue;

                Method reflectMethod;
                try {
                    reflectMethod = method.getMethodInstance(Module.hostClassLoader);
                } catch (Throwable e) {
                    continue;
                }

                int modifiers = reflectMethod.getModifiers();
                String returnType = String.valueOf(method.getReturnType());
                ClassDataList paramTypes = method.getParamTypes();

                if (!Modifier.isStatic(modifiers)
                        && Modifier.isFinal(modifiers)
                        && !returnType.contains("void")
                        && paramTypes.size() == 0) {
                    DexKitCache.saveMethod(CACHE_KEY, reflectMethod);
                    hookMethod(reflectMethod);
                    return;
                }
            }

        } catch (Throwable e) {
            XposedBridge.log("(InstaEclipse | BottomSheet): ❌ DexKit exception: " + e.getMessage());
        }
    }

    private static void hookMethod(Method reflectMethod) {
        XposedBridge.hookMethod(reflectMethod, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final Activity activity = getCurrentActivity();
                if (activity != null) {
                    activity.runOnUiThread(() -> {
                        try {
                            setupHooks(activity);
                            addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        });
        XposedBridge.log("(InstaEclipse | BottomSheet): ✅ Hooked: " + reflectMethod.getDeclaringClass().getName() + "." + reflectMethod.getName());
    }
}

