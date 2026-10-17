import re

with open("app/src/main/java/ps/reso/instaeclipse/mods/ghost/HideChatsHook.java", "r") as f:
    content = f.read()

old_filter = """    private void installInboxFilter(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook filter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // A. Check for emptyGeneralFolder intent from args
                if (FeatureFlags.emptyGeneralFolder) {
                    boolean isGeneralCall = false;
                    for (Object arg : param.args) {
                        if (arg instanceof Collection<?>) {
                            for (Object el : (Collection<?>) arg) {
                                if (el == null) continue;
                                try {
                                    for (Field f : el.getClass().getDeclaredFields()) {
                                        if (Modifier.isStatic(f.getModifiers())) continue;
                                        f.setAccessible(true);
                                        Object fv = f.get(el);
                                        if (isGeneralEnum(fv)) {
                                            isGeneralCall = true; break;
                                        }
                                    }
                                } catch (Throwable ignored) {}
                                if (isGeneralCall) break;
                            }
                        } else if (isGeneralEnum(arg)) {
                            isGeneralCall = true;
                        }
                        if (isGeneralCall) break;
                    }
                    if (isGeneralCall || isGeneralTabSelected) {
                        param.setResult(new ArrayList<>());
                        return;
                    }
                }

                // B. Filter hidden chats via defensive copy (no it.remove)
                if (FeatureFlags.hideChatsMode == 0 || HiddenThreads.isEmpty()) return;

                Object r = param.getResult();
                if (!(r instanceof List<?> list) || list.isEmpty()) return;

                try {
                    ArrayList<Object> filteredCopy = new ArrayList<>(list.size());
                    for (Object item : list) {
                        String id = threadIdOfRow(item);
                        if (id == null || !HiddenThreads.isHidden(id)) {
                            filteredCopy.add(item);
                        }
                    }
                    param.setResult(filteredCopy);
                } catch (Throwable ignored) {}
            }
        };
        try {
            int n = 0;
            for (MethodData md : bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(INBOX_ANCHOR)))) {
                try {
                    XposedBridge.hookMethod(md.getMethodInstance(classLoader), filter);
                    n++;
                } catch (Throwable ignored) {}
            }
            if (n > 0) FeatureStatusTracker.setHooked("HideSpecificChats");
            ModuleLog.line("(IE|HideChats) inbox filter hooked " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|HideChats) ⚠️ inbox filter: " + t.getMessage());
        }
    }

    private static boolean isGeneralEnum(Object obj) {
        if (obj instanceof Enum<?>) {
            Enum<?> e = (Enum<?>) obj;
            if (e.name().toUpperCase().contains("GENERAL") || e.toString().toLowerCase().contains("general")) return true;
            try {
                for (Field f : e.getClass().getDeclaredFields()) {
                    if (f.getType() == String.class) {
                        f.setAccessible(true);
                        String s = (String) f.get(e);
                        if (s != null && (s.equalsIgnoreCase("general") || s.equals("1"))) return true;
                    }
                }
            } catch (Throwable ignored) {}
        } else if (obj instanceof String s && s.equalsIgnoreCase("general")) {
            return true;
        }
        return false;
    }"""

new_filter = """    private void installInboxFilter(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook filter = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                FeatureFlags.hideSpecificChats = (FeatureFlags.hideChatsMode != 0);
                Object r = param.getResult();
                if (!(r instanceof List<?>)) return;
                List<?> originalList = (List<?>) r;

                // 1. Empty General Folder check (Target: C425100Gmm / General folder query)
                if (FeatureFlags.emptyGeneralFolder && (isGeneralFolderQuery(param.args) || isGeneralTabSelected)) {
                    param.setResult(new ArrayList<>());
                    return;
                }

                // 2. Non-destructive filtering for Hide Specific Chats
                if (FeatureFlags.hideChatsMode == 0 || HiddenThreads.isEmpty()) return;

                try {
                    List<Object> filtered = new ArrayList<>(originalList.size());
                    for (Object item : originalList) {
                        String id = threadIdOfRow(item);
                        if (id != null && HiddenThreads.isHidden(id)) continue;
                        filtered.add(item);
                    }
                    param.setResult(filtered);
                } catch (Throwable ignored) {}
            }
        };

        try {
            int n = 0;
            for (MethodData md : bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create().usingStrings(INBOX_ANCHOR)))) {
                try {
                    XposedBridge.hookMethod(md.getMethodInstance(classLoader), filter);
                    n++;
                } catch (Throwable ignored) {}
            }
            if (n > 0) FeatureStatusTracker.setHooked("HideSpecificChats");
            ModuleLog.line("(IE|HideChats) inbox filter hooked " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|HideChats) ⚠️ inbox filter: " + t.getMessage());
        }
    }

    private static boolean isGeneralFolderQuery(Object[] args) {
        if (args == null) return false;
        for (Object arg : args) {
            if (arg == null) continue;
            String cn = arg.getClass().getName();
            if (cn.contains("C425100Gmm") || cn.contains("Gmm") || "general".equalsIgnoreCase(arg.toString())) {
                return true;
            }
            if (arg instanceof Collection<?>) {
                Collection<?> col = (Collection<?>) arg;
                for (Object elem : col) {
                    if (elem != null) {
                        String ecn = elem.getClass().getName();
                        if (ecn.contains("C425100Gmm") || ecn.contains("Gmm") || "general".equalsIgnoreCase(elem.toString())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }"""

content = content.replace(old_filter, new_filter)

with open("app/src/main/java/ps/reso/instaeclipse/mods/ghost/HideChatsHook.java", "w") as f:
    f.write(content)
