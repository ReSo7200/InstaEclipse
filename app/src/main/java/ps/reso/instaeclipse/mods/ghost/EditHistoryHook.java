package ps.reso.instaeclipse.mods.ghost;

import android.os.Parcel;
import android.os.Parcelable;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Message edit history (#243): keep the text a DM had before it was edited and show it in
 * Instagram's own "Edited" history view.
 *
 * - The DM message class is the return type of the lookup using ": null_direct_message"
 *   (X.00zC on 452). An incoming edit is merged into the stored message by its synchronized
 *   (UserSession, Message, x) method (A1B on 452), which replaces the text; we diff the String
 *   fields around it and keep the previous text when exactly one changed.
 * - IG's history UI reads the message's edit-history list getter (A13 on 452: the ()List method
 *   of the message class called by code that reads com.instagram.direct.model.json
 *   .DirectMessageEditHistory fields) and is shown only when that list is non-empty. When the
 *   server sent none, we return our recorded versions as DirectMessageEditHistory objects built
 *   through its Parcelable CREATOR (writeToParcel order: body String, timestamp long µs).
 * In-memory only: history recorded while Instagram is running.
 */
public class EditHistoryHook {

    private static final String TAG = "(IE|EditHistory) ";
    private static final String HISTORY = "com.instagram.direct.model.json.DirectMessageEditHistory";
    private static final Map<Object, List<Object[]>> RECORDED = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Map<Field, String>> BEFORE = new ThreadLocal<>();
    private static Parcelable.Creator<?> creator;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            Class<?> msgClass = null;
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings(": null_direct_message")))) {
                msgClass = md.getMethodInstance(cl).getReturnType();
                break;
            }
            Class<?> history = cl.loadClass(HISTORY);
            Field cf = history.getDeclaredField("CREATOR");
            cf.setAccessible(true);
            creator = (Parcelable.Creator<?>) cf.get(null);
            if (msgClass == null) { ModuleLog.line(TAG + "❌ message class not found"); return; }

            // getter: ()List of the message class invoked by readers of DirectMessageEditHistory fields
            Method getter = null;
            for (Field hf : history.getDeclaredFields()) {
                if (Modifier.isStatic(hf.getModifiers())) continue;
                String desc = "L" + HISTORY.replace('.', '/') + ";->" + hf.getName() + ":"
                        + (hf.getType() == long.class ? "J" : "Ljava/lang/String;");
                for (MethodData reader : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .addUsingField(desc)))) {
                    for (MethodData inv : reader.getInvokes()) {
                        if (!inv.isMethod() || inv.getParamCount() != 0) continue;
                        try {
                            Method m = inv.getMethodInstance(cl);
                            if (m.getReturnType() == List.class && m.getDeclaringClass().isAssignableFrom(msgClass)
                                    && !Modifier.isStatic(m.getModifiers())) { getter = m; break; }
                        } catch (Throwable ignored) {}
                    }
                    if (getter != null) break;
                }
                if (getter != null) break;
            }

            Method merge = null;
            for (Method m : msgClass.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3 && p[1] == msgClass && p[0].getName().equals("com.instagram.common.session.UserSession")
                        && Modifier.isSynchronized(m.getModifiers()) && !Modifier.isStatic(m.getModifiers())) { merge = m; break; }
            }
            if (getter == null || merge == null) {
                ModuleLog.line(TAG + "❌ getter=" + getter + " merge=" + merge);
                return;
            }

            final List<Field> stringFields = new ArrayList<>();
            for (Class<?> k = msgClass; k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields())
                    if (f.getType() == String.class && !Modifier.isStatic(f.getModifiers())) { f.setAccessible(true); stringFields.add(f); }

            XposedBridge.hookMethod(merge, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.keepEditHistory) return;
                    Map<Field, String> snap = new java.util.HashMap<>();
                    for (Field f : stringFields) {
                        try { snap.put(f, (String) f.get(param.thisObject)); } catch (Throwable ignored) {}
                    }
                    BEFORE.set(snap);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    Map<Field, String> snap = BEFORE.get();
                    BEFORE.remove();
                    if (snap == null) return;
                    String old = null;
                    int changed = 0;
                    for (Field f : stringFields) {
                        try {
                            String a = snap.get(f), b = (String) f.get(param.thisObject);
                            if (a != null && b != null && !a.equals(b) && !a.isEmpty() && !b.isEmpty()) { changed++; old = a; }
                        } catch (Throwable ignored) {}
                    }
                    if (changed != 1) return;
                    RECORDED.computeIfAbsent(param.thisObject, k -> new ArrayList<>())
                            .add(new Object[]{old, System.currentTimeMillis() * 1000L});
                    ModuleLog.line(TAG + "recorded a previous version (" + old.length() + " chars)");
                }
            });

            XposedBridge.hookMethod(getter, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.keepEditHistory) return;
                    Object r = param.getResult();
                    if (r instanceof List<?> l && !l.isEmpty()) return; // server-provided history wins
                    List<Object[]> rec = RECORDED.get(param.thisObject);
                    if (rec == null || rec.isEmpty()) return;
                    List<Object> out = new ArrayList<>();
                    synchronized (rec) {
                        for (Object[] e : rec) {
                            Object h = make((String) e[0], (Long) e[1]);
                            if (h != null) out.add(h);
                        }
                    }
                    if (!out.isEmpty()) param.setResult(out);
                }
            });
            ModuleLog.line(TAG + "✅ merge " + merge.getName() + ", history getter " + getter.getName());
            FeatureStatusTracker.setHooked("EditHistory");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    private static Object make(String body, long tsMicros) {
        Parcel p = Parcel.obtain();
        try {
            p.writeString(body);
            p.writeLong(tsMicros);
            p.setDataPosition(0);
            return creator.createFromParcel(p);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ make: " + t);
            return null;
        } finally {
            p.recycle();
        }
    }
}
