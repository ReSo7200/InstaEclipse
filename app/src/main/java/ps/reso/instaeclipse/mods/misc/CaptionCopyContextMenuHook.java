package ps.reso.instaeclipse.mods.misc;

import android.app.AndroidAppHelper;
import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Injects a "Copy Caption" entry into the feed-post three-dots (⋮) menu, reusing the same
 * row-injection mechanism as the Download feature (Instagram has no native "copy caption"
 * MediaOption$Option, so we repurpose one of its employee/internal-only debug options as the
 * carrier — those are permanently filtered out for regular users by Instagram's own code, so
 * there's no risk of colliding with real native behavior).
 *
 * Caption text extraction: Media -> LiveTreeMediaDict (found by type) -> caption object
 * (LiveTreeMediaDict's zero-arg method guarded by the "caption" string) -> longest plausible
 * String returned by any of the caption object's own zero-arg getters (same heuristic
 * CommentCopyHook uses for comment text, adapted to methods since the caption object is an
 * interface-typed reference with no directly-scannable fields).
 */
public class CaptionCopyContextMenuHook {

    // ── Resolved at install time ──────────────────────────────────────────────

    private static Class<?> mediaOptionEnumClass;
    private static Object   copyCaptionOptionValue; // repurposed employee-only-gated option

    private static Class<?> menuCreatorClass;
    private static Method   addButtonMethod;
    private static Object   enumNormalValue;

    private static int idxEnum   = 0;
    private static int idxOption = 1;
    private static int idxSelf   = 2;
    private static int idxText   = 3;
    private static int idxList   = 4;

    private static Method captionGetter; // LiveTreeMediaDict -> caption object

    // ── Guards ────────────────────────────────────────────────────────────────

    private static final ThreadLocal<Boolean> sAddingCaptionRow =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final Set<Object> processedCreators =
            Collections.newSetFromMap(new WeakHashMap<>());

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Activity currentActivity = null;

    // Candidates that are gated behind Instagram's own employee/internal-build check
    // (LX/EpO;->A00 denylist, discovered while fixing the post-download allowlist) — never
    // shown to regular users natively, so safe to repurpose as our carrier.
    private static final String[] CARRIER_CANDIDATES = {
            "DEBUG_MEDIA", "CONTENT_DEEP_DIVE", "DEBUG_BASEL_MEDIA_INFO",
            "DEBUG_MEDIA_ON_DEVICE_CBR", "PROMOTE_DEBUG"
    };

    // ── Entry point ──────────────────────────────────────────────────────────

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        // Track current Activity for showing the copy dialog
        try {
            installActivityTracker();
        } catch (Throwable ignored) {}

        loadMediaOptionEnum(classLoader);
        resolveCaptionGetter(bridge, classLoader);
        findCreatorClassAndAddButtonMethod(bridge, classLoader);
        installAddButtonHook();
        installClickHandlerHook(bridge, classLoader);
        installAllowlistPatchHook(bridge, classLoader);
        installReelOptionsListPatch(bridge, classLoader);
        try {
            installReelLabelOverrideHook(bridge, classLoader);
        } catch (Throwable t) {
            ModuleLog.line("(IE|Caption) ❌ installReelLabelOverrideHook: " + t);
        }
    }

    private static void installActivityTracker() {
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                currentActivity = (Activity) p.thisObject;
            }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (currentActivity == p.thisObject) currentActivity = null;
            }
        });
    }

    // ── Step 1: MediaOption$Option carrier value ────────────────────────────

    private static void loadMediaOptionEnum(ClassLoader cl) {
        try {
            mediaOptionEnumClass = cl.loadClass(
                    "com.instagram.feed.media.mediaoption.MediaOption$Option");
            Object[] values = (Object[]) mediaOptionEnumClass.getMethod("values").invoke(null);
            for (String candidate : CARRIER_CANDIDATES) {
                for (Object v : values) {
                    if (v.toString().equals(candidate)) {
                        copyCaptionOptionValue = v;
                        break;
                    }
                }
                if (copyCaptionOptionValue != null) break;
            }
            if (copyCaptionOptionValue == null)
                ModuleLog.line("(IE|Caption) ❌ No usable carrier MediaOption$Option found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Caption) ❌ loadMediaOptionEnum: " + t);
        }
    }

    // ── Step 2: caption getter (LiveTreeMediaDict -> caption object) ────────

    private static void resolveCaptionGetter(DexKitBridge bridge, ClassLoader classLoader) {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("CaptionGetter", classLoader);
            if (cached != null) {
                captionGetter = cached;
                return;
            }
        }

        try {
            // The caption getter (0-arg, object-returning, guarded by the "caption" string)
            // moved from LiveTreeMediaDict (<=436) onto com.instagram.feed.media.Media itself
            // in the 442+ media-model refactor. Try the current home first, then the legacy
            // class. Only stable com.instagram.feed.media.* class names are used — never an
            // obfuscated X.* name (those change every Instagram version).
            String[] declClassCandidates = {
                    "com.instagram.feed.media.Media",             // 442+ (447: Media caption getter)
                    "com.instagram.feed.media.LiveTreeMediaDict"  // 436 and earlier
            };

            for (String declClass : declClassCandidates) {
                List<MethodData> results;
                try {
                    results = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .declaredClass(declClass)
                                    .paramCount(0)
                                    .usingEqStrings(List.of("caption"))));
                } catch (Throwable ignored) {
                    continue; // class absent on this build
                }

                for (MethodData md : results) {
                    if (md.getName().equals("<clinit>")) continue;
                    try {
                        Method m = md.getMethodInstance(classLoader);
                        if (m.getParameterCount() != 0) continue;   // excludes the setter
                        if (m.getReturnType() == void.class || m.getReturnType().isPrimitive()) continue;
                        m.setAccessible(true);
                        captionGetter = m;
                        DexKitCache.saveMethod("CaptionGetter", m);
                        ModuleLog.line("(IE|Caption) ✅ captionGetter=" + declClass + "." + m.getName());
                        return;
                    } catch (Throwable ignored) {}
                }
            }
            ModuleLog.line("(IE|Caption) ❌ captionGetter not found");
        } catch (Throwable t) {
            ModuleLog.line("(IE|Caption) ❌ resolveCaptionGetter: " + t);
        }
    }

    private static String extractCaptionText(Object media) {
        if (media == null || captionGetter == null) return null;
        try {
            // The receiver depends on where the getter is declared:
            //   442+ : declared on Media itself      -> invoke on `media` directly
            //   <=436: declared on LiveTreeMediaDict -> find that dict field on `media`
            Class<?> owner = captionGetter.getDeclaringClass();
            Object receiver;
            if (owner.isInstance(media)) {
                receiver = media;
            } else {
                receiver = null;
                Class<?> cls = media.getClass();
                outer:
                while (cls != null && cls != Object.class) {
                    for (Field f : cls.getDeclaredFields()) {
                        if (owner.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            Object v = f.get(media);
                            if (v != null) { receiver = v; break outer; }
                        }
                    }
                    cls = cls.getSuperclass();
                }
                if (receiver == null) return null;
            }

            Object captionObj = captionGetter.invoke(receiver);
            if (captionObj == null) return null;

            // Longest-plausible-string heuristic over the caption wrapper. Scan zero-arg
            // String getters AND String fields so it works whether the text is exposed as a
            // getter or a bare field (the 442+ caption object shape is not name-stable).
            String bestVal = null;
            for (Method m : captionObj.getClass().getMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() != String.class) continue;
                try { bestVal = pickCaption(bestVal, (String) m.invoke(captionObj)); }
                catch (Throwable ignored) {}
            }
            Class<?> c = captionObj.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType() != String.class) continue;
                    try { f.setAccessible(true); bestVal = pickCaption(bestVal, (String) f.get(captionObj)); }
                    catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
            return bestVal;
        } catch (Throwable t) {
            ModuleLog.line("(IE|Caption) ❌ extractCaptionText: " + t);
            return null;
        }
    }

    /** Longest-plausible caption picker: rejects numeric ids and URLs, keeps the longest. */
    private static String pickCaption(String best, String val) {
        if (val == null || val.isEmpty()) return best;
        if (val.matches("\\d+") || val.matches("\\d+_\\d+") || val.startsWith("http")) return best;
        return (best == null || val.length() > best.length()) ? val : best;
    }

    // ── Step 3: find MediaOptionsOverflowMenuCreator + its add-button method ─
    // (same discovery strategy as PostDownloadContextMenuHook — kept independent so this
    // feature works regardless of whether the download feature is enabled)

    private static void findCreatorClassAndAddButtonMethod(DexKitBridge bridge,
                                                             ClassLoader classLoader) {
        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("CaptionCopy_addButton", classLoader);
            if (cached != null) {
                addButtonMethod = cached;
                addButtonMethod.setAccessible(true);
                menuCreatorClass = cached.getDeclaringClass();
                String idxStr = DexKitCache.loadString("CaptionCopy_addButtonIdx");
                if (idxStr != null) {
                    String[] parts = idxStr.split(",");
                    if (parts.length == 5) {
                        try {
                            idxEnum   = Integer.parseInt(parts[0]);
                            idxOption = Integer.parseInt(parts[1]);
                            idxSelf   = Integer.parseInt(parts[2]);
                            idxText   = Integer.parseInt(parts[3]);
                            idxList   = Integer.parseInt(parts[4]);
                        } catch (NumberFormatException ignored) {}
                    }
                }
                resolveEnumNormalValue(cached);
                return;
            }
        }

        try {
            List<ClassData> pass1 = bridge.findClass(FindClass.create()
                    .matcher(ClassMatcher.create()
                            .usingStrings("MediaOptionsOverflowMenuCreator")));

            if (pass1.isEmpty()) {
                ModuleLog.line("(IE|Caption) ❌ MediaOptionsOverflowMenuCreator class not found");
                return;
            }

            String creatorClassName = pass1.get(0).getName();
            menuCreatorClass = classLoader.loadClass(creatorClassName);

            List<MethodData> pass2 = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .declaredClass(creatorClassName)
                            .returnType("void")));

            for (MethodData md : pass2) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (!Modifier.isStatic(m.getModifiers())) continue;

                    Class<?>[] p = m.getParameterTypes();
                    if (p.length < 4) continue;

                    int eIdx = -1, oIdx = -1, sIdx = -1, tIdx = -1, lIdx = -1;
                    for (int i = 0; i < p.length; i++) {
                        if (mediaOptionEnumClass != null && p[i] == mediaOptionEnumClass) {
                            oIdx = i;
                        } else if (ArrayList.class.isAssignableFrom(p[i])) {
                            lIdx = i;
                        } else if (p[i] == menuCreatorClass) {
                            sIdx = i;
                        } else if (CharSequence.class.isAssignableFrom(p[i])) {
                            tIdx = i;
                        } else if (p[i].isEnum() && eIdx < 0 && oIdx < 0) {
                            eIdx = i;
                        }
                    }

                    if (oIdx < 0 || lIdx < 0) continue;

                    addButtonMethod = m;
                    addButtonMethod.setAccessible(true);
                    idxEnum   = eIdx >= 0 ? eIdx : 0;
                    idxOption = oIdx;
                    idxSelf   = sIdx >= 0 ? sIdx : 2;
                    idxText   = tIdx >= 0 ? tIdx : 3;
                    idxList   = lIdx;
                    break;
                } catch (Throwable ignored) {}
            }

            if (addButtonMethod == null) {
                ModuleLog.line("(IE|Caption) ❌ addButtonMethod not found in " + creatorClassName);
                return;
            }
            DexKitCache.saveMethod("CaptionCopy_addButton", addButtonMethod);
            DexKitCache.saveString("CaptionCopy_addButtonIdx",
                    idxEnum + "," + idxOption + "," + idxSelf + "," + idxText + "," + idxList);

            resolveEnumNormalValue(addButtonMethod);

        } catch (Throwable t) {
            ModuleLog.line("(IE|Caption) ❌ findCreatorClassAndAddButtonMethod: " + t);
        }
    }

    private static void resolveEnumNormalValue(Method method) {
        try {
            Class<?> btnTypeEnumClass = method.getParameterTypes()[idxEnum];
            Object[] btnVals = (Object[]) btnTypeEnumClass.getMethod("values").invoke(null);
            Object firstVal = null;
            for (Object v : btnVals) {
                if (firstVal == null) firstVal = v;
                if (enumNormalValue == null && v.toString().equalsIgnoreCase("normal")) {
                    enumNormalValue = v;
                }
            }
            if (enumNormalValue == null) {
                for (Object v : btnVals) {
                    if (v.toString().equalsIgnoreCase("action")) { enumNormalValue = v; break; }
                }
            }
            if (enumNormalValue == null) enumNormalValue = firstVal;
        } catch (Throwable ignored) {}
    }

    // ── Hook A: intercept every addButton call, inject Copy Caption once per menu ─

    private static void installAddButtonHook() {
        if (addButtonMethod == null || copyCaptionOptionValue == null || enumNormalValue == null) {
            ModuleLog.line("(IE|Caption) ❌ Cannot install addButton hook — prerequisites missing");
            return;
        }

        XposedBridge.hookMethod(addButtonMethod, new XC_MethodHook() {

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.enableCaptionCopy) return;
                if (Boolean.TRUE.equals(sAddingCaptionRow.get())) return;
                if (param.args[idxOption] == copyCaptionOptionValue) return;

                Object self = param.args[idxSelf];
                boolean alreadyProcessed;
                synchronized (processedCreators) {
                    alreadyProcessed = processedCreators.contains(self);
                    if (!alreadyProcessed) processedCreators.add(self);
                }
                if (alreadyProcessed) return;

                Object[] callArgs = new Object[addButtonMethod.getParameterCount()];
                System.arraycopy(param.args, 0, callArgs, 0, callArgs.length);
                callArgs[idxEnum]   = enumNormalValue;
                callArgs[idxOption] = copyCaptionOptionValue;
                callArgs[idxText]   = I18n.t(AndroidAppHelper.currentApplication(), R.string.ig_caption_copy_menu_item);

                sAddingCaptionRow.set(true);
                try {
                    addButtonMethod.invoke(null, callArgs);
                } catch (Throwable t) {
                    ModuleLog.line("(IE|Caption) ❌ addButton invoke failed: " + t);
                } finally {
                    sAddingCaptionRow.set(false);
                }
            }
        });

        FeatureStatusTracker.setHooked("CaptionCopy");
        ModuleLog.line("(IE|Caption) ✅ Caption copy menu hook installed");
    }

    // ── Hook B: click handler ─────────────────────────────────────────────────

    private static void installClickHandlerHook(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook clickHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.enableCaptionCopy) return;
                onOptionClicked(param);
            }
        };

        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("CaptionCopy_click", classLoader);
            if (cached != null && !cached.isEmpty()) {
                for (Method m : cached) XposedBridge.hookMethod(m, clickHook);
                return;
            }
        }

        try {
            String optionClassName = "com.instagram.feed.media.mediaoption.MediaOption$Option";

            List<MethodData> results = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .returnType("void")
                            .paramTypes(optionClassName)));

            List<Method> candidates = new ArrayList<>();
            for (MethodData md : results) {
                try {
                    Method m = md.getMethodInstance(classLoader);
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    candidates.add(m);
                } catch (Throwable ignored) {}
            }

            boolean hasPublicCandidate = candidates.stream().anyMatch(m -> Modifier.isPublic(m.getModifiers()));

            List<Method> hooked = new ArrayList<>();
            for (Method m : candidates) {
                try {
                    if (hasPublicCandidate && Modifier.isPrivate(m.getModifiers())) continue;
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, clickHook);
                    hooked.add(m);
                } catch (Throwable t) {
                    ModuleLog.line("(IE|Caption) ❌ Failed to hook click candidate: " + t);
                }
            }

            if (hooked.isEmpty()) {
                ModuleLog.line("(IE|Caption) ❌ No click handler methods could be hooked");
            } else {
                DexKitCache.saveMethods("CaptionCopy_click", hooked);
            }

        } catch (Throwable t) {
            ModuleLog.line("(IE|Caption) ❌ installClickHandlerHook: " + t);
        }
    }

    // ── Hook C: allowlist patch ─────────────────────────────────────────────────
    // Same "SimplifiedMediaOverflowBottomSheet" allowlist patched for Download — also needs
    // to include our carrier option so it isn't filtered out on newer builds.

    private static void installAllowlistPatchHook(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook allowlistHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (copyCaptionOptionValue == null) return;
                try {
                    Object result = param.getResult();
                    if (!(result instanceof List<?> original)) return;
                    if (original.contains(copyCaptionOptionValue)) return;
                    List<Object> patched = new ArrayList<>(original);
                    patched.add(copyCaptionOptionValue);
                    param.setResult(patched);
                } catch (Throwable t) {
                    ModuleLog.line("(IE|Caption) ❌ allowlist patch failed: " + t);
                }
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("PostDownload_allowlist", classLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, allowlistHook);
                return;
            }
        }

        try {
            String optionClassName = "com.instagram.feed.media.mediaoption.MediaOption$Option";
            String typeDesc = "L" + optionClassName.replace('.', '/') + ";";
            String prefix = typeDesc + "->";

            List<MethodData> results = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .paramTypes("boolean")
                            .returnType("java.util.List")
                            .addUsingField(prefix + "REPORT:" + typeDesc)
                            .addUsingField(prefix + "HIDE_OPTIONS:" + typeDesc)
                            .addUsingField(prefix + "GEN_AI_INFO:" + typeDesc)));

            if (results.isEmpty()) return; // menu may not be filtered on this build — fine

            Method target = results.get(0).getMethodInstance(classLoader);
            target.setAccessible(true);
            XposedBridge.hookMethod(target, allowlistHook);

        } catch (Throwable t) {
            ModuleLog.line("(IE|Caption) ❌ installAllowlistPatchHook: " + t);
        }
    }

    // ── Reel options-list patch ─────────────────────────────────────────────────
    //
    // Reels use a completely separate, simplified overflow menu whose option list is
    // built by a different method entirely (found in ReelDownloadHook via field-usage
    // matching on PLAYBACK_CONTROLS + UNSAVE). Append our carrier there too so "Copy
    // Caption" also shows up on reels — same shared row renderer, so the click-handler
    // hook above already covers whatever dispatches the click.
    private static void installReelOptionsListPatch(DexKitBridge bridge, ClassLoader classLoader) {
        if (copyCaptionOptionValue == null) return;

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.enableCaptionCopy) return;
                try {
                    Object result = param.getResult();
                    if (result instanceof List<?> list && !list.contains(copyCaptionOptionValue)) {
                        @SuppressWarnings("unchecked")
                        List<Object> mutable = (List<Object>) list;
                        mutable.add(copyCaptionOptionValue);
                    }
                } catch (Throwable t) {
                    ModuleLog.line("(IE|Caption) ❌ reel options-list patch failed: " + t);
                }
            }
        };

        if (DexKitCache.isCacheValid()) {
            Method cached = DexKitCache.loadMethod("ReelOptionsListBuilder", classLoader);
            if (cached != null) {
                XposedBridge.hookMethod(cached, hook);
                return;
            }
        }

        try {
            String optionDesc = "Lcom/instagram/feed/media/mediaoption/MediaOption$Option;";
            List<MethodData> methods = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .returnType("java.util.ArrayList")
                            .addUsingField(optionDesc + "->PLAYBACK_CONTROLS:" + optionDesc)
                            .addUsingField(optionDesc + "->UNSAVE:" + optionDesc)));

            if (methods.isEmpty()) return; // reel menu may not be on this newer layout — fine

            Method target = methods.get(0).getMethodInstance(classLoader);
            target.setAccessible(true);
            XposedBridge.hookMethod(target, hook);
            DexKitCache.saveMethod("ReelOptionsListBuilder", target);

        } catch (Throwable t) {
            ModuleLog.line("(IE|Caption) ❌ installReelOptionsListPatch: " + t);
        }
    }

    // ── Step 8: reel row label override ─────────────────────────────────────
    // Reels resolve each menu row's label natively (no text parameter like the post menu
    // has), so our injected carrier row shows Instagram's own internal/debug label instead
    // of "Copy Caption". Three earlier attempts were reverted or dead-ended: hooking
    // Resources.getString(int) globally crashed unrelated layout inflation; a
    // param-shape-only DexKit query once matched the wrong method after Instagram
    // auto-updated mid-session; and the row constructor this now also hooks (QnK's 3-arg
    // ctor, reached from one row-adder variant, X.5RK.A0O) turned out not to be on the code
    // path the actual on-device reel viewer uses — live stack traces showed the real path
    // goes through a sibling variant, X.5RK.A0P, which builds its row via a different
    // native call (LX/QIa;->A04(Context,OnClickListener,String,String,F,I,I,Z,Z,Z,Z)V)
    // instead of constructing a QnK row object directly.
    // Rather than bet on a single variant again, every row-adder method found (there are
    // several — one per menu-surface variant) is probed for BOTH known row-building shapes,
    // and any match found is hooked. Discovery is call-graph anchored, not shape-only:
    //   1) label resolver   — found via the unique string it references
    //   2) row-adder methods — methods that INVOKE the label resolver (call-graph, not shape)
    //   3) row-building call — the QnK-style ctor OR the QIa.A04-style method CALLED BY #2
    // Every hook installed only fires when the row's click-listener wraps our exact carrier
    // enum singleton (extracted by scanning its fields by type), so each is a no-op for
    // every other menu row, native or otherwise — misidentifying a target here means the
    // fix silently doesn't apply, not that behavior changes for anything else.
    private static void installReelLabelOverrideHook(DexKitBridge bridge, ClassLoader classLoader) {
        if (mediaOptionEnumClass == null || copyCaptionOptionValue == null) return;

        XC_MethodHook labelHook = makeBuilderLabelHook();

        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("ReelRowBuilders", classLoader);
            if (cached != null && !cached.isEmpty()) {
                for (Method m : cached) {
                    try { m.setAccessible(true); XposedBridge.hookMethod(m, labelHook); }
                    catch (Throwable ignored) {}
                }
                ModuleLog.line("(IE|Caption) ✅ reel label override (cached), "
                        + cached.size() + " builder(s)");
                return;
            }
        }

        try {
            // 1) Label resolver (X.0AOs.A02) — anchored on its unique string.
            List<MethodData> resolverResults = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingEqStrings(List.of(
                                    "Unsupported text row for Clips Viewer Overflow menu."))));
            if (resolverResults.isEmpty()) {
                ModuleLog.line("(IE|Caption) ❌ reel label resolver not found");
                return;
            }
            Method labelResolver = resolverResults.get(0).getMethodInstance(classLoader);

            // 2) Row-adder methods (X.0AOr.A0P) — invoke the resolver AND take a MediaOption$Option.
            List<MethodData> adderResults = bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .returnType("void")
                            .addInvoke(MethodMatcher.create(labelResolver))));

            List<Method> hooked = new ArrayList<>();
            Set<Method> seen = new HashSet<>();
            for (MethodData md : adderResults) {
                Method rowAdder;
                try { rowAdder = md.getMethodInstance(classLoader); }
                catch (Throwable ignored) { continue; }

                boolean takesOption = false;
                for (Class<?> p : rowAdder.getParameterTypes())
                    if (p == mediaOptionEnumClass) { takesOption = true; break; }
                if (!takesOption) continue;

                // 3) Row builders (LX/0458.A00 / .A01) — the void methods this adder CALLS.
                List<MethodData> builderResults;
                try {
                    builderResults = bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create()
                                    .returnType("void")
                                    .addCaller(MethodMatcher.create(rowAdder))));
                } catch (Throwable ignored) { continue; }

                for (MethodData bmd : builderResults) {
                    Method bm;
                    try { bm = bmd.getMethodInstance(classLoader); }
                    catch (Throwable ignored) { continue; }
                    if (!isRowBuilderShape(bm)) continue;
                    if (!seen.add(bm)) continue;
                    try {
                        bm.setAccessible(true);
                        XposedBridge.hookMethod(bm, labelHook);
                        hooked.add(bm);
                        ModuleLog.line("(IE|Caption) ✅ reel label override on "
                                + bm.getDeclaringClass().getName() + "." + bm.getName());
                    } catch (Throwable ignored) {}
                }
            }

            if (hooked.isEmpty()) {
                ModuleLog.line("(IE|Caption) ❌ no reel row-builder methods found");
            } else {
                DexKitCache.saveMethods("ReelRowBuilders", hooked);
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|Caption) ❌ installReelLabelOverrideHook discovery: " + t);
        }
    }

    // Builder shape: void; first param Context; second param View.OnClickListener; >=1 String
    // param (the title). Matches LX/0458.A00 (carrier's path) and .A01 on 447; harmless on any
    // other match because the hook is a no-op unless our carrier listener is present.
    private static boolean isRowBuilderShape(Method m) {
        if (m.getReturnType() != void.class) return false;
        Class<?>[] p = m.getParameterTypes();
        if (p.length < 4) return false;
        if (!Context.class.isAssignableFrom(p[0])) return false;
        if (!View.OnClickListener.class.isAssignableFrom(p[1])) return false;
        for (Class<?> c : p) if (c == String.class) return true;
        return false;
    }

    private static Object findCarrierOptionOnListener(Object listener) {
        if (listener == null) return null;
        Class<?> cls = listener.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                // Wrapper classes (e.g. CV4) often store the option in a generically-typed
                // Object field, so match by runtime value type, not declared field type.
                if (!mediaOptionEnumClass.isAssignableFrom(f.getType()) && f.getType() != Object.class)
                    continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(listener);
                    if (mediaOptionEnumClass.isInstance(v)) return v;
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // Fires for every reel-menu row builder; no-op unless one of the args is the click-listener
    // wrapping our exact carrier enum. Then it replaces the row title (the first String arg after
    // the last OnClickListener arg — index 3 for both LX/0458.A00 and .A01 on 447) with "Copy Caption".
    private static XC_MethodHook makeBuilderLabelHook() {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object[] args = param.args;
                    if (args == null) return;

                    boolean ours = false;
                    int lastListenerIdx = -1;
                    for (int i = 0; i < args.length; i++) {
                        Object a = args[i];
                        if (a instanceof View.OnClickListener) {
                            lastListenerIdx = i;
                            if (!ours && findCarrierOptionOnListener(a) == copyCaptionOptionValue)
                                ours = true;
                        }
                    }
                    if (!ours || lastListenerIdx < 0) return;

                    int titleIdx = -1;
                    for (int i = lastListenerIdx + 1; i < args.length; i++) {
                        if (args[i] instanceof CharSequence) { titleIdx = i; break; }
                    }
                    if (titleIdx < 0) return;

                    Context ctx = (args[0] instanceof Context c) ? c : currentActivity;
                    if (ctx == null) return;

                    args[titleIdx] = I18n.t(ctx, R.string.ig_caption_copy_menu_item);
                } catch (Throwable t) {
                    ModuleLog.line("(IE|Caption) ❌ reel label override (builder): " + t);
                }
            }
        };
    }

    // ── Click dispatch ────────────────────────────────────────────────────────

    private static void onOptionClicked(XC_MethodHook.MethodHookParam param) {
        try {
            if (Boolean.TRUE.equals(sAddingCaptionRow.get())) return;

            Object clicked = null;
            for (Object a : param.args) {
                if (a != null && mediaOptionEnumClass != null && mediaOptionEnumClass.isInstance(a)) {
                    clicked = a; break;
                }
            }
            if (clicked == null || copyCaptionOptionValue == null || clicked != copyCaptionOptionValue) return;

            param.setResult(null); // consume the event

            Object thisObj = param.thisObject;
            Context ctx = findContext(thisObj);
            if (ctx == null) ctx = currentActivity;
            if (ctx == null) {
                ModuleLog.line("(IE|Caption) ❌ Context not found in click handler");
                return;
            }

            Object media = findMediaViaMenuCreator(thisObj);
            if (media == null) media = findMedia(thisObj);
            if (media == null) {
                ModuleLog.line("(IE|Caption) ❌ Media not found in click handler");
                return;
            }

            String caption = extractCaptionText(media);
            if (caption == null || caption.trim().isEmpty()) {
                Toast.makeText(ctx, I18n.t(ctx, R.string.ig_caption_copy_none), Toast.LENGTH_SHORT).show();
                return;
            }

            showCopyPopup(ctx, caption.trim());
        } catch (Throwable t) {
            ModuleLog.line("(IE|Caption) ❌ onOptionClicked: " + t);
        }
    }

    // ── Media resolution (mirrors PostDownloadContextMenuHook's fix for stale profile data) ─

    private static Object findMediaViaMenuCreator(Object clickHandler) {
        if (clickHandler == null || menuCreatorClass == null) return null;
        try {
            Object creator = null;
            Class<?> cls = clickHandler.getClass();
            outer:
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getType() != menuCreatorClass) continue;
                    f.setAccessible(true);
                    Object v = f.get(clickHandler);
                    if (v != null) { creator = v; break outer; }
                }
                cls = cls.getSuperclass();
            }
            if (creator == null) return null;

            Class<?> cCls = creator.getClass();
            while (cCls != null && cCls != Object.class) {
                for (Field f : cCls.getDeclaredFields()) {
                    if (f.getType().getName().equals("com.instagram.feed.media.Media")) {
                        f.setAccessible(true);
                        return f.get(creator);
                    }
                }
                cCls = cCls.getSuperclass();
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static Object findMedia(Object obj) {
        return findMediaDepth(obj, 0);
    }

    private static Object findMediaDepth(Object obj, int depth) {
        if (obj == null || depth > 2) return null;
        Class<?> cls = obj.getClass();
        if (cls.isPrimitive() || cls.getName().startsWith("java.") || cls.getName().startsWith("android."))
            return null;

        List<Object> nextLevel = depth < 2 ? new ArrayList<>() : null;

        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType().isPrimitive()) continue;
                f.setAccessible(true);
                try {
                    Object v = f.get(obj);
                    if (v == null) continue;
                    String name = v.getClass().getName();
                    if (name.equals("com.instagram.feed.media.Media")) return v;
                    if (nextLevel != null && !name.startsWith("java.") && !name.startsWith("android."))
                        nextLevel.add(v);
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }

        if (nextLevel != null) {
            for (Object child : nextLevel) {
                Object found = findMediaDepth(child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Context findContext(Object obj) {
        if (obj == null) return null;
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (!Context.class.isAssignableFrom(f.getType())) continue;
                f.setAccessible(true);
                try {
                    Object v = f.get(obj);
                    if (v instanceof Context c) return c;
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    // ── Copy popup (same look as CommentCopyHook) ───────────────────────────────

    private static boolean isDarkTheme(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private static GradientDrawable roundRect(int color, float radiusDp, Context ctx) {
        float r = radiusDp * ctx.getResources().getDisplayMetrics().density;
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(r);
        return d;
    }

    private static Button makeButton(Context ctx, String label,
                                      int bgColor, int textColor, float dp) {
        Button btn = new Button(ctx);
        btn.setText(label);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setBackground(roundRect(bgColor, 14, ctx));
        btn.setAllCaps(false);
        btn.setPadding((int) (20 * dp), (int) (14 * dp), (int) (20 * dp), (int) (14 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * dp);
        btn.setLayoutParams(lp);
        return btn;
    }

    private static void showCopyPopup(final Context ctx, final String text) {
        MAIN.post(() -> {
            try {
                float dp = ctx.getResources().getDisplayMetrics().density;
                boolean dark = isDarkTheme(ctx);

                int sheetBg    = dark ? Color.parseColor("#1C1C1E") : Color.parseColor("#F2F2F7");
                int cardBg     = dark ? Color.parseColor("#2C2C2E") : Color.parseColor("#FFFFFF");
                int textPrim   = dark ? Color.WHITE                 : Color.parseColor("#1C1C1E");
                int accentBg   = Color.parseColor("#0A84FF");
                int secondBg   = dark ? Color.parseColor("#3A3A3C") : Color.parseColor("#E5E5EA");
                int secondText = dark ? Color.WHITE                 : Color.parseColor("#1C1C1E");
                int handleClr  = dark ? Color.parseColor("#48484A") : Color.parseColor("#C7C7CC");

                LinearLayout sheet = new LinearLayout(ctx);
                sheet.setOrientation(LinearLayout.VERTICAL);
                sheet.setBackground(roundRect(sheetBg, 20, ctx));
                int hPad = (int) (20 * dp);
                sheet.setPadding(hPad, (int) (12 * dp), hPad, (int) (28 * dp));

                View handle = new View(ctx);
                LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                        (int) (40 * dp), (int) (4 * dp));
                handleLp.gravity = Gravity.CENTER_HORIZONTAL;
                handleLp.bottomMargin = (int) (16 * dp);
                handle.setLayoutParams(handleLp);
                handle.setBackground(roundRect(handleClr, 2, ctx));
                sheet.addView(handle);

                TextView titleTv = new TextView(ctx);
                titleTv.setText(I18n.t(ctx, R.string.ig_caption_copy_title));
                titleTv.setTextColor(textPrim);
                titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                titleTv.setTypeface(null, Typeface.BOLD);
                LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                titleLp.bottomMargin = (int) (14 * dp);
                titleTv.setLayoutParams(titleLp);
                sheet.addView(titleTv);

                TextView captionTv = new TextView(ctx);
                captionTv.setText(text);
                captionTv.setTextColor(textPrim);
                captionTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                captionTv.setMaxLines(6);
                captionTv.setEllipsize(TextUtils.TruncateAt.END);
                int cardPad = (int) (14 * dp);
                captionTv.setPadding(cardPad, cardPad, cardPad, cardPad);
                captionTv.setBackground(roundRect(cardBg, 12, ctx));
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                cardLp.bottomMargin = (int) (6 * dp);
                captionTv.setLayoutParams(cardLp);
                sheet.addView(captionTv);

                Dialog dialog = new Dialog(ctx);
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

                Button btnCopy = makeButton(ctx,
                        I18n.t(ctx, R.string.ig_caption_copy_full), accentBg, Color.WHITE, dp);
                btnCopy.setOnClickListener(v -> {
                    dialog.dismiss();
                    copyToClipboard(ctx, text);
                });
                sheet.addView(btnCopy);

                Button btnSelect = makeButton(ctx,
                        I18n.t(ctx, R.string.ig_comment_select_part), secondBg, secondText, dp);
                btnSelect.setOnClickListener(v -> {
                    dialog.dismiss();
                    showSelectDialog(ctx, text);
                });
                sheet.addView(btnSelect);

                dialog.setContentView(sheet);
                Window w = dialog.getWindow();
                if (w != null) {
                    w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    w.setGravity(Gravity.BOTTOM);
                    w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.WRAP_CONTENT);
                    WindowManager.LayoutParams wlp = w.getAttributes();
                    int margin = (int) (12 * dp);
                    wlp.x = margin;
                    wlp.y = margin;
                    w.setAttributes(wlp);
                }
                dialog.show();

            } catch (Throwable t) {
                ModuleLog.line("(IE|Caption) ❌ Popup: " + t);
            }
        });
    }

    private static void showSelectDialog(final Context ctx, final String text) {
        MAIN.post(() -> {
            try {
                float dp = ctx.getResources().getDisplayMetrics().density;
                boolean dark = isDarkTheme(ctx);

                int sheetBg  = dark ? Color.parseColor("#1C1C1E") : Color.parseColor("#F2F2F7");
                int cardBg   = dark ? Color.parseColor("#2C2C2E") : Color.parseColor("#FFFFFF");
                int textPrim = dark ? Color.WHITE                 : Color.parseColor("#1C1C1E");
                int accentBg = Color.parseColor("#0A84FF");
                int secondBg = dark ? Color.parseColor("#3A3A3C") : Color.parseColor("#E5E5EA");
                int handleClr= dark ? Color.parseColor("#48484A") : Color.parseColor("#C7C7CC");

                LinearLayout sheet = new LinearLayout(ctx);
                sheet.setOrientation(LinearLayout.VERTICAL);
                sheet.setBackground(roundRect(sheetBg, 20, ctx));
                int hPad = (int) (20 * dp);
                sheet.setPadding(hPad, (int) (12 * dp), hPad, (int) (28 * dp));

                View handle = new View(ctx);
                LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                        (int) (40 * dp), (int) (4 * dp));
                handleLp.gravity = Gravity.CENTER_HORIZONTAL;
                handleLp.bottomMargin = (int) (16 * dp);
                handle.setLayoutParams(handleLp);
                handle.setBackground(roundRect(handleClr, 2, ctx));
                sheet.addView(handle);

                TextView titleTv = new TextView(ctx);
                titleTv.setText(I18n.t(ctx, R.string.ig_comment_select_title));
                titleTv.setTextColor(textPrim);
                titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                titleTv.setTypeface(null, Typeface.BOLD);
                LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                titleLp.bottomMargin = (int) (14 * dp);
                titleTv.setLayoutParams(titleLp);
                sheet.addView(titleTv);

                EditText et = new EditText(ctx);
                et.setText(text);
                et.setTextColor(textPrim);
                et.setTextIsSelectable(true);
                et.setFocusableInTouchMode(true);
                et.setInputType(android.text.InputType.TYPE_NULL);
                et.setKeyListener(null);
                et.setHorizontallyScrolling(false);
                et.setMaxLines(8);
                et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                int cardPad = (int) (14 * dp);
                et.setPadding(cardPad, cardPad, cardPad, cardPad);
                et.setBackground(roundRect(cardBg, 12, ctx));
                et.setSelection(0, text.length());
                LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                etLp.bottomMargin = (int) (6 * dp);
                et.setLayoutParams(etLp);
                sheet.addView(et);

                Dialog dialog = new Dialog(ctx);
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

                Button btnCopySel = makeButton(ctx,
                        I18n.t(ctx, R.string.ig_comment_copy_selected), accentBg, Color.WHITE, dp);
                btnCopySel.setOnClickListener(v -> {
                    int s = et.getSelectionStart(), e = et.getSelectionEnd();
                    String sel = (s >= 0 && e > s) ? text.substring(s, e) : text;
                    dialog.dismiss();
                    copyToClipboard(ctx, sel);
                });
                sheet.addView(btnCopySel);

                Button btnCopyAll = makeButton(ctx,
                        I18n.t(ctx, R.string.ig_mention_copy_all), secondBg,
                        dark ? Color.WHITE : Color.parseColor("#1C1C1E"), dp);
                btnCopyAll.setOnClickListener(v -> {
                    dialog.dismiss();
                    copyToClipboard(ctx, text);
                });
                sheet.addView(btnCopyAll);

                dialog.setContentView(sheet);
                Window w = dialog.getWindow();
                if (w != null) {
                    w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    w.setGravity(Gravity.BOTTOM);
                    w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.WRAP_CONTENT);
                    WindowManager.LayoutParams wlp = w.getAttributes();
                    int margin = (int) (12 * dp);
                    wlp.x = margin;
                    wlp.y = margin;
                    w.setAttributes(wlp);
                }
                dialog.show();
                et.requestFocus();

            } catch (Throwable t) {
                ModuleLog.line("(IE|Caption) ❌ SelectDialog: " + t);
            }
        });
    }

    private static void copyToClipboard(final Context ctx, final String text) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("caption", text));
                MAIN.post(() ->
                        Toast.makeText(ctx, I18n.t(ctx, R.string.ig_caption_copied),
                                Toast.LENGTH_SHORT).show());
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|Caption) ❌ Copy: " + t);
        }
    }
}
