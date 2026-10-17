package ps.reso.instaeclipse.mods.ui;

import android.view.View;
import android.view.ViewGroup;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Hide the Notes tray at the top of the DM inbox (#241).
 *
 * The tray is one RecyclerView item whose view holder is built by a constructor using the string
 * "NotesTrayAdapter" (X.0SWg on 452, X.0E97 on 451) with the inflated tray view as a parameter.
 * Collapse that view (GONE + zero size) and keep it collapsed when the list re-lays it out.
 */
public class HideNotesTrayHook {

    private static final String TAG = "(InstaEclipse | NotesTray): ";

    public void install(DexKitBridge bridge) {
        // IG 451/452: the inbox is Jetpack Compose. The tray is the list-item composable
        // NotesTrayComposeItem.<init>.<anonymous> (X.0D2f.A00(Composer, model, int)V on 452,
        // a generic invoke on 451), found by its compose source-info string (line number left out).
        // Skipping its body renders an empty item; takes effect after an Instagram restart.
        try {
            int n = 0;
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("NotesTrayComposeItem.<init>.<anonymous> (NotesTrayComposeItem.kt")))) {
                if (md.isConstructor() || md.isStaticInitializer()) continue;
                java.lang.reflect.Method m = md.getMethodInstance(ps.reso.instaeclipse.Xposed.Module.hostClassLoader);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (FeatureFlags.hideNotesTray) param.setResult(null);
                    }
                });
                n++;
                ModuleLog.line(TAG + "✅ compose tray item " + md.getClassName() + "." + md.getName());
            }
            if (n > 0) FeatureStatusTracker.setHooked("HideNotesTray");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ compose tray: " + t);
        }
        try {
            Member ctor = null;
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("NotesTrayAdapter")))) {
                if (!md.isConstructor()) continue;
                Constructor<?> c = md.getConstructorInstance(ps.reso.instaeclipse.Xposed.Module.hostClassLoader);
                for (Class<?> p : c.getParameterTypes()) {
                    if (p == View.class) { ctor = c; break; }
                }
                if (ctor != null) break;
            }
            if (ctor == null) {
                ModuleLog.line(TAG + "❌ notes tray holder not found");
                return;
            }
            XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.hideNotesTray) return;
                    for (Object a : param.args) {
                        if (a instanceof View v && !(a instanceof android.view.LayoutInflater)) {
                            keepCollapsed(v);
                            break;
                        }
                    }
                }
            });
            ModuleLog.line(TAG + "✅ Hooked " + ctor.getDeclaringClass().getName() + ".<init>");
            FeatureStatusTracker.setHooked("HideNotesTray");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    private static void keepCollapsed(View v) {
        if (v == null) return;
        collapse(v);
        v.addOnLayoutChangeListener((w, l, t, r, b, ol, ot, or, ob) -> {
            if (FeatureFlags.hideNotesTray && (w.getVisibility() != View.GONE || b - t > 0)) w.post(() -> collapse(w));
        });
    }

    private static void collapse(View v) {
        v.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null && (lp.height != 0 || lp.width != 0)) {
            lp.height = 0;
            v.setLayoutParams(lp);
        }
    }
}
