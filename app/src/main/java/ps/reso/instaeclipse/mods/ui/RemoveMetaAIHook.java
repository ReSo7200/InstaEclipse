package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Removes Meta AI entry points (GitHub #179) by collapsing the Meta AI XML layouts as they inflate.
 * Anchored on STABLE public resource names (never obfuscated X.* ids, no version gate); new names
 * first, legacy last; any name that doesn't resolve on the running build is skipped. Covers the DM
 * composer Meta AI buttons and the "Ask Meta AI" search-results plugin. The network side (stopping
 * Meta AI from replying) is handled in IGNetworkInterceptor under the same flag.
 */
public class RemoveMetaAIHook {

    private static final String[] TARGET_LAYOUTS = {
            // DM composer Meta AI buttons
            "direct_composer_meta_ai_invocation_button",
            "direct_composer_meta_ai_discovery_button",
            "direct_composer_bar_meta_ai_v2",
            "direct_composer_overflow_button_meta_ai_v2",
            "direct_composer_meta_ai_voice_button_v2",
            "direct_composer_meta_ai_voice_button",
            "direct_composer_meta_ai_composer_upsell",
            "direct_composer_meta_ai_nux_disclaimer",
            // In-thread AI summary pill + prompt pills / NUX banners
            "direct_in_thread_ai_summary_pill",
            "layout_meta_ai_large_ask_pill",
            "layout_meta_ai_prompt_pill",
            "layout_meta_ai_suggested_prompts",
            "layout_meta_ai_scrollable_prompts",
            "layout_meta_ai_scrollable_prompts_h_scroll",
            "layout_meta_ai_chat_history_nux_banner",
            "layout_meta_ai_in_thread_blocking_nux",
            // Search SERP "Ask Meta AI" HCM card (447 real names; the *_search_plugin_* names are stale)
            "layout_meta_ai_hcm",
            "layout_meta_ai_hcm_shimmer",
    };

    private static volatile boolean idsResolved = false;
    private static final Set<Integer> targetLayoutIds = new HashSet<>();
    private static long lastMenuLog = 0;

    @SuppressLint("DiscouragedApi")
    private static void ensureIds(View anyView) {
        if (idsResolved) return;
        try {
            Resources res = anyView.getResources();
            String pkg = anyView.getContext().getPackageName();
            for (String name : TARGET_LAYOUTS) {
                int id = res.getIdentifier(name, "layout", pkg);
                if (id != 0) targetLayoutIds.add(id);
            }
            idsResolved = true;
            ModuleLog.line("(IE|RemoveMetaAI) resolved " + targetLayoutIds.size() + " Meta AI layouts");
        } catch (Throwable ignored) {}
    }

    public void install(ClassLoader classLoader) {
        XC_MethodHook inflateHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.removeMetaAI) return;
                try {
                    if (!(param.args[0] instanceof Integer)) return;
                    int resource = (int) param.args[0];
                    View result = (View) param.getResult();
                    if (result == null) return;
                    // PROBE: surface any inflated layout whose name mentions meta/ai, to confirm the
                    // Meta AI surfaces are XML-inflated (vs Litho/Compose, which this hook can't catch).
                    try {
                        String rn = result.getResources().getResourceEntryName(resource);
                        if (rn != null && (rn.contains("meta") || rn.contains("_ai_") || rn.endsWith("_ai")))
                            ModuleLog.line("(IE|RemoveMetaAI|PROBE) inflated: " + rn);
                    } catch (Throwable ignored) {}
                    ensureIds(result);
                    if (targetLayoutIds.isEmpty() || !targetLayoutIds.contains(resource)) return;

                    // inflate(int, ViewGroup, boolean): when attachToRoot, the inflated layout is the
                    // root's last child; otherwise the returned view IS the inflated layout.
                    View target = result;
                    boolean attached = param.args.length >= 3
                            ? Boolean.TRUE.equals(param.args[2]) : param.args[1] != null;
                    if (attached && param.args[1] != null && result instanceof ViewGroup) {
                        ViewGroup root = (ViewGroup) result;
                        if (root.getChildCount() > 0) target = root.getChildAt(root.getChildCount() - 1);
                    }
                    collapse(target);
                    FeatureStatusTracker.setHooked("RemoveMetaAI");
                } catch (Throwable ignored) {}
            }
        };

        try {
            XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate",
                    int.class, ViewGroup.class, boolean.class, inflateHook);
        } catch (Throwable t) {
            ModuleLog.line("(IE|RemoveMetaAI) ⚠️ inflate(3) hook: " + t.getMessage());
        }
        try {
            XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate",
                    int.class, ViewGroup.class, inflateHook);
        } catch (Throwable t) {
            ModuleLog.line("(IE|RemoveMetaAI) ⚠️ inflate(2) hook: " + t.getMessage());
        }
        FeatureStatusTracker.setHooked("RemoveMetaAI");
        ModuleLog.line("(IE|RemoveMetaAI) ✅ installed");
    }

    /**
     * Removes the Meta AI surfaces in Reels and on video posts. Anchored on stable marker
     * strings verified against the exact installed build (447.0.0.21.81) — the earlier
     * "..._render"→null anchors sat on trace-scope companion stubs that the live Litho path
     * never invokes, so nothing fired. These target the data/eligibility layer instead, which
     * runs on the normal path and carries stable strings. Needs the DexKit bridge.
     */
    public void installReels(DexKitBridge bridge, ClassLoader classLoader) {
        installReelsCardEligibility(bridge, classLoader); // in-feed reels Meta AI card
        installVideoAttribution(bridge, classLoader);     // Meta AI attribution subtitle (video posts)
        installReelsOverflowGate(bridge, classLoader);    // reel ⋮ "Ask Meta AI" entrypoint
        installMenuOptionFilter(bridge, classLoader);     // GEN_AI MediaOption rows in overflow menus
        installSearchSerpMetaAiHcm(bridge, classLoader);  // "Ask Meta AI" card in search results (446+)
        installReelContentDeepDive(bridge, classLoader);  // reel "Ask Meta AI about this" (Content Deep Dive)
    }

    /**
     * Reel "Ask Meta AI about this" — the Content Deep Dive prompt/entry (446+, Litho; appears in the
     * reel more-options sheet and as a pill). It is NOT a MediaOption row, so the menu-option filter
     * can't reach it. Kill it at the data layer instead: force its eligibility gate
     * (ClipsMediaInfoComponent.shouldShowContentDeepDivePrompt) to false and no-op its prompt-data
     * fetcher — with no eligibility and no prompt data, neither the pill nor the menu entry is built.
     * Anchored on stable trace-marker strings (classes are obfuscated). Both are gated on the flag.
     */
    private void installReelContentDeepDive(DexKitBridge bridge, ClassLoader cl) {
        // Gate: shouldShowContentDeepDivePrompt(...) -> force false. Return type may be boolean OR
        // boxed Boolean, so decide at runtime from the hooked method's actual return type (never
        // clobber a non-boolean return).
        XC_MethodHook forceFalse = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!FeatureFlags.removeMetaAI) return;
                try {
                    Class<?> rt = ((java.lang.reflect.Method) p.method).getReturnType();
                    if (rt == boolean.class || rt == Boolean.class) p.setResult(false);
                } catch (Throwable ignored) {}
            }
        };
        hookByMarker(bridge, cl, forceFalse,
                "android_purge_26_q3_ClipsMediaInfoComponent_shouldShowContentDeepDivePrompt",
                "cdd-should-show", null);
        // Also blank the Content Deep Dive UI-state builder (getUiState) — belt and braces if the
        // gate lives elsewhere: with an empty/blanked ui-state the pill/entry has nothing to show.
        XC_MethodHook blankUiState = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!FeatureFlags.removeMetaAI) return;
                Object r = p.getResult();
                if (r == null) return;
                try {
                    for (java.lang.reflect.Field f : r.getClass().getDeclaredFields()) {
                        if (f.getType() != String.class || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        if (f.get(r) != null) f.set(r, "");
                    }
                } catch (Throwable ignored) {}
            }
        };
        hookByMarker(bridge, cl, blankUiState,
                "android_purge_26_q3_ContentDeepDiveUseCase_getUiState", "cdd-uistate", null);
        // Fetcher: skip fetching prompt data so there is nothing to render.
        XC_MethodHook skip = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (FeatureFlags.removeMetaAI) p.setResult(null);
            }
        };
        hookByMarker(bridge, cl, skip,
                "android_purge_26_q3_ClipsContentDeepDivePromptFetcher_fetchContentDeepDivePromptDataForMediaIds",
                "cdd-fetch", null);

        // The reel ⋮ more-options menu adds the Meta AI ("GenAI info") row via
        // ClipsOrganicMediaItemViewMoreOptionsController.maybeAddGenAIInfoRow(...). Skip that method
        // so the row is never appended. Only skip if it's void (a side-effecting row-adder) — never
        // clobber a method that returns a value the caller uses.
        XC_MethodHook skipIfVoid = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!FeatureFlags.removeMetaAI) return;
                try {
                    if (((java.lang.reflect.Method) p.method).getReturnType() == void.class) p.setResult(null);
                } catch (Throwable ignored) {}
            }
        };
        hookByMarker(bridge, cl, skipIfVoid,
                "android_purge_26_q3_ClipsOrganicMediaItemViewMoreOptionsController_maybeAddGenAIInfoRow",
                "reel-menu-genai-row", null);
    }

    /** Hook every method carrying a trace-marker string; if returnTypeFilter is non-null, only hook
     *  methods with that return type (guards the force-false against non-boolean overloads). */
    private void hookByMarker(DexKitBridge bridge, ClassLoader cl, XC_MethodHook hook,
                              String marker, String label, String returnTypeFilter) {
        int n = 0;
        try {
            MethodMatcher mm = MethodMatcher.create().usingStrings(marker);
            if (returnTypeFilter != null) mm = mm.returnType(returnTypeFilter);
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(mm))) {
                try { XposedBridge.hookMethod(md.getMethodInstance(cl), hook); n++; } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|RemoveMetaAI) ⚠️ " + label + ": " + t.getMessage());
        }
        ModuleLog.line("(IE|RemoveMetaAI) " + label + ": " + n + " method(s)");
    }

    /**
     * Search-results "Ask Meta AI" HCM card (IG 446+, Litho-rendered — not XML-inflatable). The
     * card's data comes from ClipsTopSerpDataSource.createDefaultMetaAIHcmFetchResults(...); if that
     * produces no results, no card is shown. We anchor on the method's stable trace-marker string
     * (the class itself is obfuscated) and empty its result at runtime. Only collection/map results
     * are cleared; anything else is left untouched (and its type logged) so we never break the SERP.
     */
    private void installSearchSerpMetaAiHcm(DexKitBridge bridge, ClassLoader cl) {
        XC_MethodHook neuter = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!FeatureFlags.removeMetaAI) return;
                Object r = p.getResult();
                if (r == null) return;
                try {
                    if (r instanceof java.util.Collection) { ((java.util.Collection<?>) r).clear(); }
                    else if (r instanceof java.util.Map) { ((java.util.Map<?, ?>) r).clear(); }
                    else if (r instanceof Object[]) { p.setResult(java.util.Arrays.copyOf((Object[]) r, 0)); }
                    else { ModuleLog.probe("(IE|RemoveMetaAI) serp-hcm result type=" + r.getClass().getName()); }
                } catch (Throwable ignored) {}
            }
        };
        int n = 0;
        try {
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("android_purge_26_q2_ClipsTopSerpDataSource_createDefaultMetaAIHcmFetchResults")))) {
                try { XposedBridge.hookMethod(md.getMethodInstance(cl), neuter); n++; } catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|RemoveMetaAI) ⚠️ search-serp-hcm: " + t.getMessage());
        }
        ModuleLog.line("(IE|RemoveMetaAI) search-serp-hcm: " + n + " method(s)");
    }

    /**
     * In-feed Reels Meta AI card. The card is injected by MetaAiClipsEligibilityFetcher: its
     * fetch method builds the eligible-candidate list and hands it to the generic feed injector.
     * Skipping that method (return void) means no candidates → the unit is never injected.
     * Anchored on the fetcher's stable trace markers; the method only reads+clears sets, so a
     * no-op is side-effect-safe.
     */
    private void installReelsCardEligibility(DexKitBridge bridge, ClassLoader cl) {
        XC_MethodHook skip = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (FeatureFlags.removeMetaAI) p.setResult(null); // void → skip candidate build/inject
            }
        };
        String[] anchors = {
                "android_purge_26_q3_MetaAiClipsEligibilityFetcher_fetchEligibility",
                "android_purge_26_q3_MetaAiClipsEligibilityFetcher_onClipsItemsRequestFinished",
                "android_purge_26_q3_MetaAiClipsEligibilityFetcher_onClipsItemsRequestSuccess", // IG 446+
        };
        int n = 0;
        java.util.Set<String> hooked = new HashSet<>();
        for (String a : anchors) {
            try {
                for (MethodData md : bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings(a)))) {
                    String key = md.getDescriptor();
                    if (!hooked.add(key)) continue; // both markers live on the same method
                    try { XposedBridge.hookMethod(md.getMethodInstance(cl), skip); n++; }
                    catch (Throwable ignored) {}
                }
            } catch (Throwable t) {
                ModuleLog.line("(IE|RemoveMetaAI) ⚠️ reels-card anchor " + a + ": " + t.getMessage());
            }
        }
        ModuleLog.line("(IE|RemoveMetaAI) reels-card eligibility: " + n + " method(s)");
    }

    /**
     * Meta AI attribution subtitle on video posts (the "… Meta AI" row on the media). It is
     * built by MetaAIVideoAttributionSubtitleUseCase.getUiState(Media)->uiState. We can't null
     * the result (a downstream final field would NPE), so we blank the UI-state's String fields
     * — the row then renders no Meta AI text. Anchored on the use-case's stable marker string.
     */
    private void installVideoAttribution(DexKitBridge bridge, ClassLoader cl) {
        XC_MethodHook blank = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!FeatureFlags.removeMetaAI) return;
                Object r = p.getResult();
                if (r == null) return;
                try {
                    for (java.lang.reflect.Field f : r.getClass().getDeclaredFields()) {
                        if (f.getType() != String.class) continue;
                        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        if (f.get(r) != null) f.set(r, "");
                    }
                } catch (Throwable ignored) {}
            }
        };
        int n = 0;
        try {
            for (MethodData md : bridge.findMethod(FindMethod.create()
                    .matcher(MethodMatcher.create()
                            .usingStrings("android_purge_26_q3_MetaAIVideoAttributionSubtitleUseCase_getUiState")))) {
                try { XposedBridge.hookMethod(md.getMethodInstance(cl), blank); n++; }
                catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|RemoveMetaAI) ⚠️ video-attribution: " + t.getMessage());
        }
        ModuleLog.line("(IE|RemoveMetaAI) video-attribution: " + n + " method(s)");
    }

    // Meta-AI-reels-entrypoint eligibility gate. The reel more-options "blue circle" Meta AI
    // entrypoint (and its sibling contextual entrypoints) is built only when this boolean returns
    // true; the sheet builder checks it and skips the whole entrypoint block otherwise, and the
    // header binder re-checks it at render. It is a boolean(UserSession) that reads exactly two
    // MobileConfig ids — anchored on those (both present + co-located in this build), never an
    // obfuscated name. The standard rows (Download / Copy Link / Save / Copy Caption) are built in
    // a different method that doesn't consult this gate, so they're unaffected.
    private static final long META_AI_GATE_ID_1 = 0x81111100005b47L;
    private static final long META_AI_GATE_ID_2 = 0x81106e000057c2L;

    private void installReelsOverflowGate(DexKitBridge bridge, ClassLoader classLoader) {
        XC_MethodHook forceFalse = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (FeatureFlags.removeMetaAI) param.setResult(false);
            }
        };
        int n = hookGate(bridge, classLoader, forceFalse, new Number[]{META_AI_GATE_ID_1, META_AI_GATE_ID_2});
        if (n == 0) n = hookGate(bridge, classLoader, forceFalse, new Number[]{META_AI_GATE_ID_1});
        if (n == 0) n = hookGate(bridge, classLoader, forceFalse, new Number[]{META_AI_GATE_ID_2});
        ModuleLog.line("(IE|RemoveMetaAI) reels-overflow gate: " + n + " method(s)");
    }

    private int hookGate(DexKitBridge bridge, ClassLoader cl, XC_MethodHook hook, Number[] ids) {
        int n = 0;
        try {
            MethodMatcher m = MethodMatcher.create()
                    .returnType("boolean")
                    .paramTypes("com.instagram.common.session.UserSession")
                    .usingNumbers(ids);
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(m))) {
                try { XposedBridge.hookMethod(md.getMethodInstance(cl), hook); n++; }
                catch (Throwable ignored) {}
            }
        } catch (Throwable t) {
            ModuleLog.line("(IE|RemoveMetaAI) ⚠️ overflow gate: " + t.getMessage());
        }
        return n;
    }

    /**
     * Drops the Meta AI item ("Ask Meta AI" / GenAI info) from the reel/post overflow (⋮) menus.
     * Those menus are built from lists of com.instagram.feed.media.mediaoption.MediaOption$Option;
     * we hook the list builders (anchored by their referenced enum fields) and remove any option
     * whose enum name is a Meta-AI one. Stable-field anchored — no obfuscated X.* names.
     */
    private void installMenuOptionFilter(DexKitBridge bridge, ClassLoader cl) {
        final String od = "Lcom/instagram/feed/media/mediaoption/MediaOption$Option;";
        XC_MethodHook filter = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!FeatureFlags.removeMetaAI) return;
                Object r = param.getResult();
                if (!(r instanceof List)) return;
                try {
                    List<?> src = (List<?>) r;
                    long now = System.currentTimeMillis();
                    if (now - lastMenuLog > 1500) {
                        lastMenuLog = now;
                        StringBuilder sb = new StringBuilder();
                        for (Object o : src) sb.append(o).append(",");
                        ModuleLog.line("(IE|RemoveMetaAI|PROBE) menu opts=[" + sb + "]");
                    }
                    List<Object> keep = new java.util.ArrayList<>(src.size());
                    boolean changed = false;
                    for (Object o : src) {
                        if (o != null && isMetaAiOption(o.toString())) { changed = true; continue; }
                        keep.add(o);
                    }
                    if (!changed) return;
                    try { ((List<Object>) src).clear(); ((List<Object>) src).addAll(keep); } // mutate in place if possible
                    catch (Throwable mutateFail) { param.setResult(keep); }               // else replace
                } catch (Throwable ignored) {}
            }
        };
        // Any option-list builder that references the GEN_AI_INFO enum field is, by construction, a
        // menu that can contain the Meta AI item — post overflow, reel ⋮, or otherwise. Anchoring on
        // that single stable field (rather than a specific neighbour-field combo) catches every
        // surface across builds; IG 446 moved the reel ⋮ menu to a different builder than the old
        // PLAYBACK_CONTROLS+UNSAVE ArrayList, which is why the narrow reel matcher missed it.
        // Anchor on every Meta-AI menu enum constant: GEN_AI_INFO (post overflow), GEN_AI, and
        // CONTENT_DEEP_DIVE — the reel ⋮ "Ask Meta AI about this" entry (its prompt string is
        // meta_ai_content_deep_dive_prompt_v2), which neither GEN_AI anchor caught.
        for (String constName : new String[]{"GEN_AI_INFO", "GEN_AI", "CONTENT_DEEP_DIVE"}) {
            String field = od + "->" + constName + ":" + od;
            hookBuilder(bridge, cl, filter, FindMethod.create().matcher(MethodMatcher.create()
                    .returnType("java.util.List").addUsingField(field)), "list-" + constName);
            hookBuilder(bridge, cl, filter, FindMethod.create().matcher(MethodMatcher.create()
                    .returnType("java.util.ArrayList").addUsingField(field)), "arraylist-" + constName);
        }
        // Reel options list (ArrayList referencing PLAYBACK_CONTROLS + UNSAVE) — kept for builds
        // where the reel menu builder does not statically reference GEN_AI_INFO.
        hookBuilder(bridge, cl, filter, FindMethod.create().matcher(MethodMatcher.create()
                .returnType("java.util.ArrayList")
                .addUsingField(od + "->PLAYBACK_CONTROLS:" + od)
                .addUsingField(od + "->UNSAVE:" + od)), "reel-options");
    }

    private void hookBuilder(DexKitBridge bridge, ClassLoader cl, XC_MethodHook hook, FindMethod q, String label) {
        try {
            int n = 0;
            for (MethodData md : bridge.findMethod(q)) {
                try { XposedBridge.hookMethod(md.getMethodInstance(cl), hook); n++; } catch (Throwable ignored) {}
            }
            ModuleLog.line("(IE|RemoveMetaAI) " + label + " filter: " + n + " method(s)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|RemoveMetaAI) ⚠️ " + label + ": " + t.getMessage());
        }
    }

    private static boolean isMetaAiOption(String name) {
        String n = name.toUpperCase();
        return n.contains("GEN_AI") || n.contains("GENAI") || n.contains("META_AI")
                || n.contains("METAAI") || n.contains("ASK_META")
                || n.contains("CONTENT_DEEP_DIVE"); // reel ⋮ "Ask Meta AI about this" (446+)
    }

    private static void collapse(View v) {
        if (v == null) return;
        v.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) { lp.height = 0; lp.width = 0; v.setLayoutParams(lp); }
    }
}
