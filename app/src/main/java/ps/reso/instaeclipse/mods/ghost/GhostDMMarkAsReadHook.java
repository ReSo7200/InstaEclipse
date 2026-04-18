package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.XModuleResources;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;

public class GhostDMMarkAsReadHook {

    private static final String GHOST_BTN_TAG = "ie_ghost_seen_btn";
    private final String moduleSourceDir;

    public GhostDMMarkAsReadHook(String moduleSourceDir) {
        this.moduleSourceDir = moduleSourceDir;
    }

    // Cached once on first use — resource IDs are constant for a given app install.
    private static volatile int sCachedContainerId = 0;

    public void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // Bail immediately if the feature is off — this hook fires for every
                    // view attachment in the entire app, so the fast path must be trivial.
                    if (!FeatureFlags.isGhostSeen) return;

                    View view = (View) param.thisObject;

                    if (sCachedContainerId == 0) {
                        @SuppressLint("DiscouragedApi")
                        int id = view.getContext().getResources().getIdentifier(
                                "row_thread_composer_buttons_container", "id",
                                view.getContext().getPackageName());
                        sCachedContainerId = id;
                    }

                    if (sCachedContainerId == 0 || view.getId() != sCachedContainerId) return;
                    if (!(view.getParent() instanceof ViewGroup parent)) return;
                    injectIndependentButton(parent, view);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse): Ghost hook failed: " + t.getMessage());
        }
    }

    private void injectIndependentButton(ViewGroup parent, View originalContainer) {
        if (parent.findViewWithTag(GHOST_BTN_TAG) != null) return;

        Context ctx = parent.getContext();
        ImageButton ghostBtn = new ImageButton(ctx);
        ghostBtn.setTag(GHOST_BTN_TAG);

        try {
            @SuppressLint("UseCompatLoadingForDrawables") Drawable icon = XModuleResources.createInstance(moduleSourceDir, null)
                    .getDrawable(R.drawable.ic_eye, null);
            ghostBtn.setImageDrawable(icon);
        } catch (Exception e) {
            ghostBtn.setImageResource(android.R.drawable.ic_menu_view);
            ghostBtn.setColorFilter(Color.WHITE);
        }
        ghostBtn.setBackground(null);

        int size = dp(ctx, 35);

        // We use FrameLayout params because most Instagram composer parents are FrameLayouts
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);

        // POSITION: Left side, slightly elevated so it doesn't block the text input or mic
        lp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        lp.setMargins(dp(ctx, 5), 25, 0, 0);

        ghostBtn.setLayoutParams(lp);
        ghostBtn.setOnClickListener(v -> triggerSeenLogic(parent));

        parent.post(() -> {
            parent.addView(ghostBtn, 3);
        });
    }

    private void triggerSeenLogic(View view) {
        try {
            Context ctx = view.getContext();
            @SuppressLint("DiscouragedApi")
            int messageListId = ctx.getResources().getIdentifier("message_list", "id", ctx.getPackageName());

            View root = view.getRootView();
            View messageList = root.findViewById(messageListId);

            if (messageList instanceof ViewGroup group) {
                // scrollBy with a large value is capped synchronously by RecyclerView's
                // LayoutManager to the actual bottom
                group.scrollBy(0, 100_000);

                FeatureFlags.isGhostSeen = false;
                group.scrollBy(0, -200);

                view.postDelayed(() -> {
                    group.scrollBy(0, 200);
                    FeatureFlags.isGhostSeen = true;
                    Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_seen_sent), Toast.LENGTH_SHORT).show();
                }, 300);
            }
        } catch (Exception e) {
            FeatureFlags.isGhostSeen = true;
        }
    }

    private int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density);
    }
}