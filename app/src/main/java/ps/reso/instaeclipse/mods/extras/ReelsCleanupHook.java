package ps.reso.instaeclipse.mods.extras;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.ui.ResIds;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Clean up Reels (#279): hide the Follow button, Meta AI prompts, template / audio pills and the
 * comment bar on the Reels screen. The right-hand action column (ClipsUfiComponent: like, comment,
 * share, save) is never touched.
 *
 * <p>The Reels overlay is built from Litho components. Each part below is its own component whose
 * render method carries an "android_purge_…_&lt;Component&gt;_render" marker; when the switch is on,
 * the render result is replaced by null after the original ran (Litho draws nothing for a null
 * render, and the component's hooks still run in the same order). Nothing is matched by position.
 * <ul>
 *   <li>Follow: ClipsFollowButtonComponent, ClipsFollowButtonComponentLegacy.</li>
 *   <li>Meta AI: ClipsMetaAiPillComponent, ClipsContentDeepDiveComponent, and attribution pills of
 *   type CONTENT_DEEP_DIVE / SEE_MORE_PROMPTS.</li>
 *   <li>Pills: attribution pills of type TEMPLATE / STORIES_TEMPLATE, the Edits ("Basel")
 *   creative-tool pills and the music line (ClipsMusicSubtitleComponent).</li>
 *   <li>Comment bar: the Litho ClipsViewerInViewerCommentBarComponent, and the classic bar of
 *   ClipsViewerCommentBarController (inflated from the clips_comment_composer_stub view stub), set
 *   to GONE after each controller call, the same visibility IG's own hideCommentBar uses.</li>
 * </ul>
 * An attribution pill's type is read from IG's attribution-type enum (located by its constant
 * names); a pill whose type can't be read is left alone.
 */
public class ReelsCleanupHook {

    private static final String TAG = "(InstaEclipse | ReelsCleanup): ";
    private static final String P2 = "android_purge_26_q2_";
    private static final String P3 = "android_purge_26_q3_";

    private static final Set<String> META_AI_TYPES = new HashSet<>(Arrays.asList("CONTENT_DEEP_DIVE", "SEE_MORE_PROMPTS"));
    private static final Set<String> PILL_TYPES = new HashSet<>(Arrays.asList("TEMPLATE", "STORIES_TEMPLATE"));

    /** IG's attribution-type enum class(es). Empty when not found: attribution pills are then kept. */
    private Set<Class<?>> attributionEnums = Collections.emptySet();
    /** Per component class, the field path to its attribution-type value. */
    private final Map<Class<?>, Field[]> typePaths = new ConcurrentHashMap<>();

    public void install(DexKitBridge bridge, ClassLoader cl) {
        hideRender(bridge, cl, "ClipsFollowButtonComponent", P3, () -> FeatureFlags.reelsHideFollow, "reelsHideFollow");
        hideRender(bridge, cl, "ClipsFollowButtonComponentLegacy", P2, () -> FeatureFlags.reelsHideFollow, "reelsHideFollow");

        hideRender(bridge, cl, "ClipsMetaAiPillComponent", P3, () -> FeatureFlags.reelsHideMetaAi, "reelsHideMetaAi");
        hideRender(bridge, cl, "ClipsContentDeepDiveComponent", P2, () -> FeatureFlags.reelsHideMetaAi, "reelsHideMetaAi");

        hideRender(bridge, cl, "ClipsMusicSubtitleComponent", P3, () -> FeatureFlags.reelsHidePills, "reelsHidePills");
        hideRender(bridge, cl, "ClipsBaselAttributionPillComponent", P3, () -> FeatureFlags.reelsHidePills, "reelsHidePills");
        hideRender(bridge, cl, "ClipsBaselCreativeToolAttributionPillComponent", P2, () -> FeatureFlags.reelsHidePills, "reelsHidePills");
        hideRender(bridge, cl, "ClipsBaselPlatformizedCreativeToolAttributionComponent", P2, () -> FeatureFlags.reelsHidePills, "reelsHidePills");

        attributionEnums = findAttributionEnums(bridge, cl);
        if (!attributionEnums.isEmpty()) {
            hideAttribution(bridge, cl, "OrganicSecondaryAttributionComponent");
            hideAttribution(bridge, cl, "OverflowAttributionComponent");
        }

        hideRender(bridge, cl, "ClipsViewerInViewerCommentBarComponent", P3, () -> FeatureFlags.reelsHideCommentBar, "reelsHideCommentBar");
        installCommentBarController(bridge, cl);
    }

    // ── Litho components: render -> null ─────────────────────────────────────────────────────

    /**
     * The component's render method: an instance method with one parameter whose return type is
     * the Litho component base class the declaring component itself extends (so a lambda that
     * merely shares the marker string is never picked).
     */
    private static Method renderMethod(DexKitBridge bridge, ClassLoader cl, String component, String prefix) {
        return ExtrasLookup.method("Extras_ReelsRender_" + component, cl, () -> {
            for (MethodData md : ExtrasLookup.byStrings(bridge, prefix + component + "_render")) {
                if (md.isConstructor() || md.getParamCount() != 1) continue;
                Method m = md.getMethodInstance(cl);
                Class<?> ret = m.getReturnType();
                if (!Modifier.isStatic(m.getModifiers()) && ret != Object.class && !ret.isPrimitive()
                        && ret.isAssignableFrom(m.getDeclaringClass())) return m;
            }
            return null;
        });
    }

    private static void hideRender(DexKitBridge bridge, ClassLoader cl, String component, String prefix,
                                   BooleanSupplier flag, String statusKey) {
        Method render = renderMethod(bridge, cl, component, prefix);
        if (render == null) return;
        try {
            XposedBridge.hookMethod(render, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (flag.getAsBoolean() && param.getThrowable() == null) param.setResult(null);
                }
            });
            FeatureStatusTracker.setHooked(statusKey);
            ModuleLog.line(TAG + "✅ " + component + " " + render.getDeclaringClass().getName() + "." + render.getName());
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + component + ": " + t);
        }
    }

    // ── Attribution pills, filtered by type ──────────────────────────────────────────────────

    private void hideAttribution(DexKitBridge bridge, ClassLoader cl, String component) {
        Method render = renderMethod(bridge, cl, component, P3);
        if (render == null) return;
        try {
            XposedBridge.hookMethod(render, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!(FeatureFlags.reelsHideMetaAi || FeatureFlags.reelsHidePills) || param.getThrowable() != null) return;
                    String type = attributionType(param.thisObject);
                    if (type == null) return;
                    if ((FeatureFlags.reelsHideMetaAi && META_AI_TYPES.contains(type))
                            || (FeatureFlags.reelsHidePills && PILL_TYPES.contains(type))) {
                        param.setResult(null);
                    }
                }
            });
            FeatureStatusTracker.setHooked("reelsHidePills");
            ModuleLog.line(TAG + "✅ " + component + " " + render.getDeclaringClass().getName() + "." + render.getName());
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + component + ": " + t);
        }
    }

    private static Set<Class<?>> findAttributionEnums(DexKitBridge bridge, ClassLoader cl) {
        String key = "Extras_ReelsAttributionEnum";
        String cached = DexKitCache.isCacheValid() ? DexKitCache.loadString(key) : null;
        List<String> names = new ArrayList<>();
        if (cached != null) {
            if (!cached.isEmpty() && !"missing".equals(cached)) names.addAll(Arrays.asList(cached.split(",")));
        } else {
            try {
                for (ClassData cd : DexKitQueryCache.findClass(bridge, FindClass.create().matcher(ClassMatcher.create()
                        .usingStrings("CONTENT_DEEP_DIVE", "SEE_MORE_PROMPTS", "STORIES_TEMPLATE", "TEMPLATE")))) {
                    names.add(cd.getName());
                }
            } catch (Throwable t) {
                ModuleLog.line(TAG + "attribution type lookup failed: " + t);
            }
        }
        Set<Class<?>> out = new HashSet<>();
        for (String n : names) {
            try {
                Class<?> c = cl.loadClass(n);
                if (c.isEnum()) out.add(c);
            } catch (Throwable ignored) {}
        }
        if (cached == null) {
            StringBuilder sb = new StringBuilder();
            for (Class<?> c : out) sb.append(sb.length() == 0 ? "" : ",").append(c.getName());
            DexKitCache.saveString(key, sb.length() == 0 ? "missing" : sb.toString());
        }
        if (out.isEmpty()) ModuleLog.line(TAG + "❌ attribution type enum not found; attribution pills are kept");
        return out;
    }

    /** The attribution type name of {@code component} (one or two fields deep), or null. */
    private String attributionType(Object component) {
        if (component == null) return null;
        try {
            Field[] path = typePaths.get(component.getClass());
            if (path != null) {
                Object v = component;
                for (Field f : path) {
                    v = v == null ? null : f.get(v);
                }
                return v instanceof Enum<?> e ? e.name() : null;
            }
            for (Field f : instanceFields(component.getClass())) {
                Object v = f.get(component);
                if (v == null) continue;
                if (attributionEnums.contains(v.getClass()) || attributionEnums.contains(v.getClass().getSuperclass())) {
                    typePaths.put(component.getClass(), new Field[]{f});
                    return ((Enum<?>) v).name();
                }
                if (!v.getClass().getName().startsWith("X.")) continue; // only IG's own data holders
                for (Field g : instanceFields(v.getClass())) {
                    Object w = g.get(v);
                    if (w != null && (attributionEnums.contains(w.getClass()) || attributionEnums.contains(w.getClass().getSuperclass()))) {
                        typePaths.put(component.getClass(), new Field[]{f, g});
                        return ((Enum<?>) w).name();
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static List<Field> instanceFields(Class<?> c) {
        List<Field> out = new ArrayList<>();
        for (Field f : SafeReflect.fields(c)) {
            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
            try {
                f.setAccessible(true);
                out.add(f);
            } catch (Throwable ignored) {}
        }
        return out;
    }

    // ── Classic comment bar (ClipsViewerCommentBarController) ───────────────────────────────

    /** Controller -> its inflated comment bar. */
    private static final Map<Object, WeakReference<View>> BARS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Object[]> STUB_SLOT = new ThreadLocal<>();

    private static void installCommentBarController(DexKitBridge bridge, ClassLoader cl) {
        Method onViewCreated = ExtrasLookup.method("Extras_ReelsCommentBarOnViewCreated", cl, () -> {
            for (MethodData md : ExtrasLookup.byStrings(bridge, P2 + "ClipsViewerCommentBarController_onViewCreated")) {
                if (!md.isConstructor()) return md.getMethodInstance(cl);
            }
            return null;
        });
        if (onViewCreated == null || onViewCreated.getParameterCount() < 1
                || !View.class.isAssignableFrom(onViewCreated.getParameterTypes()[0])) return;
        Class<?> controller = onViewCreated.getDeclaringClass();
        try {
            XposedBridge.hookMethod(onViewCreated, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    STUB_SLOT.remove();
                    try {
                        if (!(param.args[0] instanceof View root)) return;
                        int stubId = ResIds.id(root.getContext(), "clips_comment_composer_stub");
                        if (stubId == 0) return;
                        View stub = root.findViewById(stubId);
                        if (stub instanceof ViewStub && stub.getParent() instanceof ViewGroup parent) {
                            STUB_SLOT.set(new Object[]{parent, parent.indexOfChild(stub)});
                        }
                    } catch (Throwable ignored) {}
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object[] slot = STUB_SLOT.get();
                    STUB_SLOT.remove();
                    try {
                        if (slot == null) return;
                        ViewGroup parent = (ViewGroup) slot[0];
                        int index = (Integer) slot[1];
                        View bar = index >= 0 && index < parent.getChildCount() ? parent.getChildAt(index) : null;
                        if (bar == null || bar instanceof ViewStub) return;
                        BARS.put(param.thisObject, new WeakReference<>(bar));
                        hideBar(param.thisObject);
                    } catch (Throwable ignored) {}
                }
            });
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ comment bar onViewCreated: " + t);
            return;
        }

        // Every other controller method may show the bar again (page change, resolve visibility,
        // show, overlays cleared): re-apply GONE after each one.
        XC_MethodHook reapply = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.reelsHideCommentBar) return;
                Object owner = controller.isInstance(param.thisObject) ? param.thisObject : null;
                if (owner == null && param.args != null) {
                    for (Object a : param.args) {
                        if (controller.isInstance(a)) {
                            owner = a;
                            break;
                        }
                    }
                }
                if (owner != null) hideBar(owner);
            }
        };
        int n = 0;
        for (Method m : SafeReflect.methods(controller)) {
            if (m.equals(onViewCreated) || Modifier.isAbstract(m.getModifiers()) || m.isSynthetic()) continue;
            try {
                XposedBridge.hookMethod(m, reapply);
                n++;
            } catch (Throwable ignored) {}
        }
        FeatureStatusTracker.setHooked("reelsHideCommentBar");
        ModuleLog.line(TAG + "✅ comment bar controller " + controller.getName() + " (" + n + " methods)");
    }

    private static void hideBar(Object controller) {
        if (!FeatureFlags.reelsHideCommentBar) return;
        WeakReference<View> ref = BARS.get(controller);
        View bar = ref == null ? null : ref.get();
        if (bar == null) return;
        try {
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                bar.post(() -> hideBar(controller));
            } else if (bar.getVisibility() != View.GONE) {
                bar.setVisibility(View.GONE);
            }
        } catch (Throwable ignored) {}
    }
}
