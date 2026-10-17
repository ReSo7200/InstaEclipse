package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Hide the "Create group" entry on the DM share sheet (#240).
 *
 * Two shapes, both anchored on stable names verified on IG 436 / 447 / 451:
 *  - the plain button, view id {@code direct_private_share_create_group_button}, inside
 *    instagram.features.direct.fragment.sharesheet.DirectShareSheetFragment (constraint child). We
 *    collapse it after onViewCreated and keep it collapsed on later layouts (IG re-shows it when the
 *    selection changes) via a coalesced global-layout check scoped to the sheet's view.
 *  - the multi-recipient "create group" facepile variants, inflated from layouts
 *    {@code direct_private_share_create_group_facepile_button/_view}: collapsed as they inflate.
 * Gated on FeatureFlags.hideShareCreateGroup; nothing changes when it is off.
 */
public class ShareSheetCleanupHook {

    private static final String FRAGMENT =
            "instagram.features.direct.fragment.sharesheet.DirectShareSheetFragment";
    private static final String[] LAYOUTS = {
            "direct_private_share_create_group_facepile_button",
            "direct_private_share_create_group_facepile_view",
    };
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile boolean idsResolved;
    private static int buttonId;
    private static final Set<Integer> layoutIds = new HashSet<>();

    public void install(ClassLoader classLoader) {
        int hooked = 0;
        try {
            XposedHelpers.findAndHookMethod(FRAGMENT, classLoader, "onViewCreated",
                    View.class, Bundle.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (param.args[0] instanceof View root) watch(root);
                        }
                    });
            hooked++;
        } catch (Throwable t) {
            ModuleLog.line("(IE|ShareSheet) ⚠️ onViewCreated: " + t);
        }

        XC_MethodHook inflate = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                if (!FeatureFlags.hideShareCreateGroup) return;
                try {
                    if (!(param.args[0] instanceof Integer res) || !(param.getResult() instanceof View result)) return;
                    ensureIds(result.getResources(), result.getContext().getPackageName());
                    if (!layoutIds.contains(res)) return;
                    View target = result;
                    boolean attached = param.args.length >= 3
                            ? Boolean.TRUE.equals(param.args[2]) : param.args[1] != null;
                    if (attached && param.args[1] != null && result instanceof ViewGroup root
                            && root.getChildCount() > 0) {
                        target = root.getChildAt(root.getChildCount() - 1);
                    }
                    collapse(target);
                } catch (Throwable ignored) {
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(LayoutInflater.class, "inflate",
                    int.class, ViewGroup.class, boolean.class, inflate);
            hooked++;
        } catch (Throwable t) {
            ModuleLog.line("(IE|ShareSheet) ⚠️ inflate: " + t);
        }

        if (hooked > 0) FeatureStatusTracker.setHooked("HideShareCreateGroup");
        ModuleLog.line("(IE|ShareSheet) ✅ installed (" + hooked + " hooks)");
    }

    private static void watch(View root) {
        if (!FeatureFlags.hideShareCreateGroup) return;
        try {
            ensureIds(root.getResources(), root.getContext().getPackageName());
            if (buttonId == 0) return;
            sweep(root);
            final boolean[] pending = {false};
            final android.view.ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
                if (!FeatureFlags.hideShareCreateGroup || pending[0]) return;
                pending[0] = true;
                MAIN.post(() -> {
                    pending[0] = false;
                    sweep(root);
                });
            };
            // Attach/detach with the sheet so listeners never pile up on the activity window.
            root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {
                    v.getViewTreeObserver().addOnGlobalLayoutListener(listener);
                    sweep(v);
                }
                @Override public void onViewDetachedFromWindow(View v) {
                    v.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
                }
            });
            if (root.isAttachedToWindow()) root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        } catch (Throwable ignored) {
        }
    }

    private static void sweep(View root) {
        View b = root.findViewById(buttonId);
        // ConstraintLayout child: GONE only (width/height 0 would mean "match constraints").
        if (b != null && b.getVisibility() != View.GONE) b.setVisibility(View.GONE);
    }

    @SuppressLint("DiscouragedApi")
    private static void ensureIds(Resources res, String pkg) {
        if (idsResolved) return;
        buttonId = res.getIdentifier("direct_private_share_create_group_button", "id", pkg);
        for (String name : LAYOUTS) {
            int id = res.getIdentifier(name, "layout", pkg);
            if (id != 0) layoutIds.add(id);
        }
        idsResolved = true;
    }

    private static void collapse(View v) {
        v.setVisibility(View.GONE);
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) {
            lp.width = 0;
            lp.height = 0;
            v.setLayoutParams(lp);
        }
    }
}
