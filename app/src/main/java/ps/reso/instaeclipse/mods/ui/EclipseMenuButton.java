package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.XModuleResources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.utils.VibrationUtil;
import ps.reso.instaeclipse.utils.dialog.DialogUtils;

/**
 * Injects an eye-icon button into Instagram's main action bar that opens the
 * InstaEclipse menu on tap. Uses the same ic_eye drawable and
 * onAttachedToWindow hook pattern as GhostDMMarkAsReadHook.
 *
 * Targets the search tab (or its end-action-buttons container) so the button
 * appears alongside the existing navigation icons.
 */
public class EclipseMenuButton {

    private static final String BTN_TAG = "ie_menu_btn";
    private final String moduleSourceDir;

    public EclipseMenuButton(String moduleSourceDir) {
        this.moduleSourceDir = moduleSourceDir;
    }

    @SuppressLint("DiscouragedApi")
    public void install(XC_LoadPackage.LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                View view = (View) param.thisObject;
                Context ctx = view.getContext();
                String pkgName = ctx.getPackageName();

                // List of potential IDs to anchor onto
                String[] targetIds = {
                        "action_bar_title_view"
                };

                for (String idName : targetIds) {
                    int resId = ctx.getResources().getIdentifier(idName, "id", pkgName);

                    // If the ID exists in this version and matches the current view
                    if (resId != 0 && view.getId() == resId) {
                        if (view.getParent() instanceof ViewGroup parent) {
                            // Check if we already injected to prevent duplicates
                            if (parent.findViewWithTag("injected_btn_tag") == null) {
                                injectButton(ctx, parent, parent.indexOfChild(view) + 1);
                            }
                        }
                        // Break the loop once we've matched and handled the view
                        break;
                    }
                }
            }
        });
    }

    private void injectButton(Context ctx, ViewGroup parent, int insertIndex) {
        if (parent.findViewWithTag(BTN_TAG) != null) return;

        ImageButton btn = new ImageButton(ctx);
        btn.setTag(BTN_TAG);

        try {
            @SuppressLint("UseCompatLoadingForDrawables") Drawable icon = XModuleResources.createInstance(moduleSourceDir, null)
                    .getDrawable(R.drawable.ic_settings_gear, null);
            btn.setImageDrawable(icon);
            btn.setColorFilter(Color.WHITE);
        } catch (Exception e) {
            btn.setImageResource(android.R.drawable.ic_menu_manage);
            btn.setColorFilter(Color.WHITE);
        }
        btn.setBackground(null);

        ViewGroup.MarginLayoutParams lp =
                new ViewGroup.MarginLayoutParams(dp(ctx, 32), dp(ctx, 56));
        lp.topMargin  = dp(ctx, 0);
        lp.leftMargin = dp(ctx, 0);
        btn.setLayoutParams(lp);
        btn.setPadding(0, 0, 0, 0);
        btn.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);

        btn.setOnClickListener(v -> {
            Activity activity = unwrapActivity(v.getContext());
            if (activity != null) {
                DialogUtils.showEclipseOptionsDialog(activity);
                VibrationUtil.vibrate(activity);
            }
        });

        parent.post(() -> {
            if (parent.findViewWithTag(BTN_TAG) != null) return;
            int idx = Math.min(insertIndex, parent.getChildCount());
            parent.addView(btn, idx);
        });
    }

    private static Activity unwrapActivity(Context ctx) {
        while (ctx instanceof ContextWrapper wrapper) {
            if (wrapper instanceof Activity activity) return activity;
            ctx = wrapper.getBaseContext();
        }
        return null;
    }

    private int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}
