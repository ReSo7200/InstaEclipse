package ps.reso.instaeclipse.mods.misc;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;

public class FollowStatusFinder {

    public String type;

    public FollowMethodResult findFollowerStatusMethod(DexKitBridge bridge) {
        try {
            // Step 1: Semantic anchor via "other_user_follows_viewer".
            try {
                List<MethodData> ovCallers = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings("other_user_follows_viewer")));

                for (MethodData caller : ovCallers) {
                    String boolMethodName = null;
                    for (MethodData invoked : caller.getInvokes()) {
                        if (!String.valueOf(invoked.getReturnType()).contains("boolean")) continue;
                        if (!invoked.getParamTypes().isEmpty()) continue;
                        boolMethodName = invoked.getName();
                        break;
                    }

                    if (boolMethodName == null) continue;

                    final String bmn = boolMethodName;
                    List<MethodData> candidates = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .name(bmn)
                                    .returnType("boolean")
                                    .paramCount(0)));

                    for (MethodData cand : candidates) {
                        List<MethodData> idCheck = bridge.findMethod(FindMethod.create()
                                .matcher(MethodMatcher.create()
                                        .declaredClass(cand.getClassName())
                                        .name("getId")
                                        .returnType("java.lang.String")
                                        .paramCount(0)));
                        if (!idCheck.isEmpty()) {
                            type = "other_user_follows_viewer";
                            return new FollowMethodResult(bmn, cand.getClassName());
                        }
                    }
                }
            } catch (Throwable ignore) {}

            try {
                // Step 2: Try method detection (obfuscated User class)
                String obfUserClass = null;
                List<MethodData> errMethods = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings("ERROR_INSERT_EXPIRED_URL")));
                if (!errMethods.isEmpty()) {
                    obfUserClass = errMethods.get(0).getClassName();
                }

                if (obfUserClass != null) {
                    List<MethodData> methods = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings("", "", "").paramTypes("com.instagram.common.session.UserSession", obfUserClass)));
                    for (MethodData method : methods) {
                        for (MethodData invoked : method.getInvokes()) {
                            if (invoked.getClassName().contains(obfUserClass) && String.valueOf(invoked.getReturnType()).contains("boolean")) {
                                type = "fallback - 1";
                                return new FollowMethodResult(invoked.getName(), obfUserClass);
                            }
                        }
                    }
                }
            } catch (Throwable ignore) {}

        } catch (Throwable e) {
            XposedBridge.log("❌ Error in findFollowerStatusMethod: " + e.getMessage());
        }
        return null;
    }

    public String findUserIdClassIfNeeded(DexKitBridge bridge, String userClassName) {
        try {
            if (!"com.instagram.user.model.FriendshipStatus".equals(userClassName)) {
                return userClassName;
            } else {
                try {
                    List<MethodData> methods = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().usingStrings("username_missing_during_update")));
                    if (!methods.isEmpty()) {
                        String userClass = methods.get(0).getClassName();
                        List<MethodData> toStringMethods = bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create().declaredClass(userClass).name("toString").returnType("java.lang.String")));
                        if (!toStringMethods.isEmpty()) {
                            for (MethodData invoked : toStringMethods.get(0).getInvokes()) {
                                return invoked.getClassName();
                            }
                        }
                    }
                } catch (Throwable e) {
                    XposedBridge.log("❌ Error finding user class: " + e.getMessage());
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    public void checkFollow(ClassLoader classLoader, String followerStatusMethod, String userClassName, String userIdClassName) {
        try {
            final String[] userId = {null};
            boolean isOvPath = "other_user_follows_viewer".equals(type);

            if (!isOvPath && userClassName.equals("com.instagram.user.model.FriendshipStatus")) {
                userClassName = "com.instagram.user.model.FriendshipStatusImpl";
                try {
                    if (userIdClassName != null) {
                        XposedHelpers.findAndHookMethod(userIdClassName, classLoader, "getId", new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                userId[0] = (String) param.getResult();
                            }
                        });
                    }
                } catch (Throwable t) {
                    XposedBridge.log("❌ Failed to hook getId() fallback: " + t.getMessage());
                }
            }

            final String finalUserClassName = userClassName;
            XposedHelpers.findAndHookMethod(userClassName, classLoader, followerStatusMethod, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // We only hook here to ensure the tracker has the most recent ID
                    // if the interceptor needs it.

                    Object user = param.thisObject;
                    if (!finalUserClassName.equals("com.instagram.user.model.FriendshipStatusImpl")) {
                        try {
                            userId[0] = (String) XposedHelpers.callMethod(param.thisObject, "getId");
                        } catch (Throwable ignored) {}
                    }

                    // Note: No more toast logic here.
                    // No more observedResults.put() here.
                }
            });

            XposedBridge.log("(InstaEclipse | FollowerStatus): ✅ Hooked (" + type + ") - Cache disabled.");
            FeatureStatusTracker.setHooked("ShowFollowerToast");

        } catch (Exception e) {
            XposedBridge.log("❌ Error hooking follower status: " + e.getMessage());
        }
    }

    public static class FollowMethodResult {
        public final String methodName;
        public final String userClassName;
        public FollowMethodResult(String methodName, String userClassName) {
            this.methodName = methodName;
            this.userClassName = userClassName;
        }
    }
}