package ps.reso.instaeclipse.mods.ghost;

import android.app.Activity;
import android.content.Context;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

public class InboxFoldersHook {

    private static WeakReference<Object> lastVmGeneratorRef = new WeakReference<>(null);
    private static Method diffUpdateMethod = null;

    private static Object generalFolderSingleton = null;
    private static Object chatsFolderSingleton = null;

    public void install(DexKitBridge bridge, ClassLoader loader) {
        installFolderDescriptorHooks(bridge, loader);
        installVmGeneratorHooks(bridge, loader);
    }

    public static void refreshActiveInbox(Activity activity) {
        try {
            Object vmGen = lastVmGeneratorRef.get();
            if (vmGen != null && diffUpdateMethod != null) {
                diffUpdateMethod.invoke(null, vmGen);
            } else if (activity != null) {
                HideChatsHook.refreshAccount(ps.reso.instaeclipse.utils.ghost.CurrentConversation.accountId(activity));
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|InboxFolders) error refreshing inbox: " + t.getMessage());
        }
    }

    private void installFolderDescriptorHooks(DexKitBridge bridge, ClassLoader loader) {
        try {
            List<ClassData> descriptors = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create().usingStrings("primary", "general", "requests_hidden_words", "school_channels")));
            if (descriptors.isEmpty()) return;

            Class<?> folderDescriptorClass = descriptors.get(0).getInstance(loader);

            for (Method m : folderDescriptorClass.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class && m.getReturnType() == folderDescriptorClass) {
                    m.setAccessible(true);
                    generalFolderSingleton = m.invoke(null, "general");
                    chatsFolderSingleton = m.invoke(null, "chats");
                    break;
                }
            }

            for (Method m : folderDescriptorClass.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 2 && m.getReturnType() == boolean.class) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (FeatureFlags.emptyGeneralFolder && isGeneralDescriptor(param.thisObject)) {
                                param.setResult(false);
                            }
                        }
                    });
                }
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|InboxFolders) Error in folder descriptor hooks: " + t.getMessage());
        }
    }

    public static boolean isGeneralDescriptor(Object obj) {
        if (obj == null) return false;
        if (generalFolderSingleton != null && obj == generalFolderSingleton) return true;

        String name = obj.getClass().getName();
        if (name.endsWith(".0Gmm") || name.contains("C425100Gmm")) return true;

        for (Class<?> c = obj.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    if ("general".equalsIgnoreCase((String) val)) return true;
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    public static boolean isStateInGeneralFolder(Object state) {
        if (state == null) return false;
        try {
            for (Field f : state.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                Object val = f.get(state);
                if (isGeneralDescriptor(val)) return true;
            }
        } catch (Throwable t) {}
        return false;
    }

    private void installVmGeneratorHooks(DexKitBridge bridge, ClassLoader loader) {
        try {
            List<MethodData> diffUpdates = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings("InboxViewModelGenerator.calculateAndApplyDiffUpdate")));

            if (diffUpdates.isEmpty()) return;

            Method diffUpdateMethodResolved = diffUpdates.get(0).getMethodInstance(loader);
            diffUpdateMethod = diffUpdateMethodResolved;
            Class<?> vmGenClass = diffUpdateMethodResolved.getParameterTypes()[0];

            Field vmStateField = null;
            for (Field f : vmGenClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getType().getName().startsWith("X.")) continue;

                int listCount = 0;
                for (Field sf : f.getType().getDeclaredFields()) {
                    if (sf.getType() == java.util.List.class) listCount++;
                }
                if (listCount >= 3) {
                    vmStateField = f;
                    break;
                }
            }

            Field holdersField = null;
            for (Field f : vmGenClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getType().getName().startsWith("X.")) continue;

                if (f.getType().getDeclaredFields().length >= 20) {
                    holdersField = f;
                    break;
                }
            }

            if (vmStateField == null || holdersField == null) return;
            vmStateField.setAccessible(true);
            holdersField.setAccessible(true);
            Class<?> stateClass = vmStateField.getType();
            Class<?> holdersClass = holdersField.getType();

            for (Method m : vmGenClass.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1 && m.getParameterTypes()[0] == vmGenClass && m.getReturnType() == void.class) {
                    final Field finalVmStateField = vmStateField;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Object vmGen = param.thisObject != null ? param.thisObject : (param.args != null && param.args.length > 0 ? param.args[0] : null);
                            if (vmGen != null) {
                                lastVmGeneratorRef = new WeakReference<>(vmGen);
                                Object state = finalVmStateField.get(vmGen);

                                if (FeatureFlags.emptyGeneralFolder && isStateInGeneralFolder(state)) {
                                    Map<Field, Object> backup = new HashMap<>();
                                    for (Field f : stateClass.getDeclaredFields()) {
                                        if (f.getType() == java.util.List.class && !Modifier.isStatic(f.getModifiers())) {
                                            f.setAccessible(true);
                                            backup.put(f, f.get(state));
                                            f.set(state, Collections.emptyList());
                                        }
                                    }
                                    backupMap.put(param, backup);
                                    stateMap.put(param, state);
                                }
                            }
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object state = stateMap.remove(param);
                            Map<Field, Object> backup = backupMap.remove(param);
                            if (state != null && backup != null) {
                                for (Map.Entry<Field, Object> entry : backup.entrySet()) {
                                    entry.getKey().set(state, entry.getValue());
                                }
                            }
                        }

                        private final Map<MethodHookParam, Map<Field, Object>> backupMap = new HashMap<>();
                        private final Map<MethodHookParam, Object> stateMap = new HashMap<>();
                    });
                }
            }

            Field sectionEnumField = null;
            Method singleItemGenMethod = null;

            for (Field f : holdersClass.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || !f.getType().getName().startsWith("X.")) continue;

                Class<?> genType = f.getType();
                for (Class<?> c = genType; c != null && c != Object.class; c = c.getSuperclass()) {
                    if (sectionEnumField == null) {
                        for (Field sf : c.getDeclaredFields()) {
                            if (sf.getType().isEnum() && sf.getType().getName().startsWith("X.")) {
                                sectionEnumField = sf;
                                sectionEnumField.setAccessible(true);
                                break;
                            }
                        }
                    }
                    if (singleItemGenMethod == null) {
                        for (Method sm : c.getDeclaredMethods()) {
                            if (sm.getParameterCount() == 1 && sm.getParameterTypes()[0] == stateClass && sm.getReturnType() != void.class) {
                                singleItemGenMethod = sm;
                                singleItemGenMethod.setAccessible(true);
                                break;
                            }
                        }
                    }
                }
                if (sectionEnumField != null && singleItemGenMethod != null) break;
            }

            if (sectionEnumField != null && singleItemGenMethod != null) {
                final Field finalSectionEnumField = sectionEnumField;
                XposedBridge.hookMethod(singleItemGenMethod, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        if (!FeatureFlags.hideDirectFoldersBar) return;
                        Object gen = param.thisObject;
                        Enum<?> secEnum = (Enum<?>) finalSectionEnumField.get(gen);
                        if (secEnum == null) return;

                        String secName = secEnum.name();
                        if ("FOLDER".equals(secName) || "FILTER_ROW".equals(secName)) {
                            param.setResult(null);
                        } else if ("INBOX_HEADER".equals(secName) && param.getResult() == null) {
                            Field headerItemField = null;
                            Field userSessionField = null;
                            for (Field f : gen.getClass().getDeclaredFields()) {
                                if (f.getType().getName().endsWith(".UserSession")) userSessionField = f;
                                else if (f.getType().getName().startsWith("X.")) headerItemField = f;
                            }

                            if (headerItemField != null) {
                                headerItemField.setAccessible(true);
                                Object existingHeader = headerItemField.get(gen);
                                if (existingHeader == null) {
                                    Object state = param.args[0];
                                    Object c0iafInstance = null;
                                    for (Field sf : state.getClass().getDeclaredFields()) {
                                        if (sf.getType().isEnum() && sf.getType().getName().startsWith("X.")) {
                                            sf.setAccessible(true);
                                            c0iafInstance = sf.get(state);
                                            break;
                                        }
                                    }
                                    if (c0iafInstance == null) {
                                        Class<?> enumClass = null;
                                        for (Constructor<?> constructor : headerItemField.getType().getDeclaredConstructors()) {
                                            for (Class<?> pType : constructor.getParameterTypes()) {
                                                if (pType.isEnum()) { enumClass = pType; break; }
                                            }
                                        }
                                        if (enumClass != null) c0iafInstance = enumClass.getEnumConstants()[0];
                                    }

                                    if (c0iafInstance != null) {
                                        Class<?> headerClass = headerItemField.getType();
                                        Constructor<?> headerConstructor = null;
                                        for (Constructor<?> c : headerClass.getDeclaredConstructors()) {
                                            if (c.getParameterCount() == 5) {
                                                headerConstructor = c;
                                                break;
                                            }
                                        }
                                        if (headerConstructor != null) {
                                            headerConstructor.setAccessible(true);
                                            Context context = null;
                                            try {
                                                Class<?> appThreadClass = Class.forName("android.app.ActivityThread");
                                                Method currentAppThreadMethod = appThreadClass.getDeclaredMethod("currentApplication");
                                                context = (Context) currentAppThreadMethod.invoke(null);
                                            } catch (Throwable ignored) {}
                                            String msgTitle = context != null ? context.getString(context.getResources().getIdentifier("direct_header_title", "string", context.getPackageName())) : "Messages";
                                            if (msgTitle == null || msgTitle.isEmpty()) msgTitle = "Messages";
                                            String reqTitle = context != null ? context.getString(context.getResources().getIdentifier("direct_message_requests", "string", context.getPackageName())) : "Requests";
                                            if (reqTitle == null || reqTitle.isEmpty()) reqTitle = "Requests";

                                            Object newHeader = headerConstructor.newInstance(c0iafInstance, msgTitle, reqTitle, 0, true);
                                            headerItemField.set(gen, newHeader);
                                            param.setResult(newHeader);
                                        }
                                    }
                                }
                            }
                        }
                    }
                });
            }

        } catch (Throwable t) {
            ModuleLog.line("(IE|InboxFolders) Error in VM generator hooks: " + t.getMessage());
        }
    }
}
