package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.JitGuard;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

/**
 * Free app-icon picker. Long-pressing the Instagram logo on the home feed opens Instagram Plus's
 * paid "Change your app icon" sheet. With the switch on, the same long press opens our own picker
 * instead, offering every icon Instagram ships in its manifest (the launcher aliases
 * com.instagram.android.activity.MainTabActivity[.name]) without a subscription.
 *
 * Android can only switch to icons an app declares itself, so the choice is Instagram's own 13
 * alternative icons plus the default. Instagram resets the icon for non-subscribers
 * ("subscription_rollback" / ig_app_icon_reset_job in AuraAppIconSwitchManager); instead of hooking
 * that obfuscated code, setComponentEnabledSetting is guarded: while the switch is on, only our
 * picker may enable or disable the icon aliases. Version-independent, Vector and LSPatch alike.
 *
 * The long press sits on the title view (id action_bar_title_view) inside main_feed_action_bar;
 * Instagram's own listener is kept and used again when the switch is off.
 */
public final class AppIconPickerHook {
    private static final String TAG = "(IE|AppIcon) ";
    private static final String PKG = "com.instagram.android";
    private static final String BASE = "com.instagram.android.activity.MainTabActivity";
    /** alias suffix (empty = the default icon), mipmap name, label */
    private static final String[][] ICONS = {
            {"", null, "Instagram"},
            {"throwback", "aura_throwback_icon", "Throwback"},
            {"haruko", "aura_haruko_icon", "Haruko"},
            {"felipe", "aura_felipe_icon", "Felipe"},
            {"humberto", "aura_humberto_icon", "Humberto"},
            {"zipeng", "aura_zipeng_icon", "Zipeng"},
            {"uzo", "aura_uzo_icon", "Uzo"},
            {"ricky", "aura_ricky_icon", "Ricky"},
            {"kpop", "cannes_kpop_icon", "K-pop"},
            {"floral", "cannes_floral_icon", "Floral"},
            {"flame", "cannes_flame_icon", "Flame"},
            {"slime", "cannes_slime_icon", "Slime"},
            {"neon", "cannes_neon_icon", "Neon"},
            {"metal", "cannes_metal_icon", "Metal"},
    };

    private static final Set<View> watched = Collections.newSetFromMap(new WeakHashMap<>());
    private static final ThreadLocal<Boolean> OURS = new ThreadLocal<>();
    private static int titleId, feedBarId;

    public void install(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                    View decor = ((Activity) param.thisObject).getWindow().getDecorView();
                    if (!watched.add(decor)) return;
                    decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> scan(decor));
                    scan(decor);
                }
            });
            // Guard: Instagram may not switch the icon aliases itself while the picker is on.
            XposedHelpers.findAndHookMethod("android.app.ApplicationPackageManager", cl, "setComponentEnabledSetting",
                    ComponentName.class, int.class, int.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                            if (!FeatureFlags.freeAppIcons || Boolean.TRUE.equals(OURS.get())) return;
                            ComponentName c = (ComponentName) param.args[0];
                            if (c != null && PKG.equals(c.getPackageName()) && c.getClassName().startsWith(BASE)) {
                                ModuleLog.line(TAG + "kept the picked icon: Instagram tried to set " + c.getClassName()
                                        + " to " + param.args[1]);
                                param.setResult(null);
                            }
                        }
                    });
            installLifecycle();
            FeatureStatusTracker.setHooked("FreeAppIcons");
            ModuleLog.line(TAG + "✅ installed");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    @SuppressLint("DiscouragedApi")
    private static void scan(View decor) {
        try {
            if (titleId == 0) {
                titleId = decor.getResources().getIdentifier("action_bar_title_view", "id", decor.getContext().getPackageName());
                feedBarId = decor.getResources().getIdentifier("main_feed_action_bar", "id", decor.getContext().getPackageName());
            }
            if (titleId == 0 || feedBarId == 0) return;
            View bar = decor.findViewById(feedBarId);
            if (bar == null) return;
            View title = bar.findViewById(titleId);
            if (title == null) return;
            View.OnLongClickListener cur = currentLongClick(title);
            if (cur instanceof Ours) return;
            title.setOnLongClickListener(new Ours(cur));
        } catch (Throwable ignored) {}
    }

    /** Our long press; Instagram's own listener is kept for when the switch is off. */
    private static final class Ours implements View.OnLongClickListener {
        final View.OnLongClickListener original;
        Ours(View.OnLongClickListener original) { this.original = original; }

        @Override
        public boolean onLongClick(View v) {
            if (!FeatureFlags.freeAppIcons) return original != null && original.onLongClick(v);
            try {
                showPicker(v.getContext());
            } catch (Throwable t) {
                ModuleLog.line(TAG + "picker failed: " + t);
                return original != null && original.onLongClick(v);
            }
            return true;
        }
    }

    private static View.OnLongClickListener currentLongClick(View v) {
        try {
            Field f = View.class.getDeclaredField("mListenerInfo");
            f.setAccessible(true);
            Object info = f.get(v);
            if (info == null) return null;
            Field l = info.getClass().getDeclaredField("mOnLongClickListener");
            l.setAccessible(true);
            return (View.OnLongClickListener) l.get(info);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Index in ICONS of the icon that is on now (the enabled alias; none enabled = default). */
    private static int current(PackageManager pm) {
        for (int i = 1; i < ICONS.length; i++) {
            try {
                if (pm.getComponentEnabledSetting(new ComponentName(PKG, BASE + "." + ICONS[i][0]))
                        == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return i;
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    @SuppressLint("DiscouragedApi")
    private static void showPicker(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        int now = pending >= 0 ? pending : current(pm);   // a pick waiting for the background counts
        IeDialog dlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_app_icon_title));
        GridLayout grid = new GridLayout(ctx);
        grid.setColumnCount(3);
        int cell = dlg.dp(92), icon = dlg.dp(56);
        final android.app.Dialog[] shown = new android.app.Dialog[1];
        for (int i = 0; i < ICONS.length; i++) {
            Drawable d = null;
            try {
                if (ICONS[i][1] == null) d = ctx.getResources().getDrawable(ctx.getApplicationInfo().icon, ctx.getTheme());
                else {
                    int id = ctx.getResources().getIdentifier(ICONS[i][1], "mipmap", ctx.getPackageName());
                    if (id != 0) d = ctx.getResources().getDrawable(id, ctx.getTheme());
                }
            } catch (Throwable ignored) {}
            if (d == null) continue;                    // not in this Instagram build
            LinearLayout box = new LinearLayout(ctx);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(dlg.dp(4), dlg.dp(8), dlg.dp(4), dlg.dp(8));
            if (i == now) box.setBackground(dlg.round(dlg.field, 14));
            ImageView iv = new ImageView(ctx);
            iv.setImageDrawable(d);
            box.addView(iv, new LinearLayout.LayoutParams(icon, icon));
            TextView tv = new TextView(ctx);
            tv.setText(ICONS[i][2]);
            tv.setTextColor(i == now ? dlg.accent : dlg.text);
            tv.setTextSize(12);
            tv.setGravity(Gravity.CENTER);
            tv.setSingleLine(true);
            tv.setPadding(0, dlg.dp(4), 0, 0);
            box.addView(tv);
            final int pick = i;
            box.setOnClickListener(v -> {
                if (pick != now) schedule(ctx, pick);
                if (shown[0] != null) try { shown[0].dismiss(); } catch (Throwable ignored) {}
            });
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = cell;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            grid.addView(box, lp);
        }
        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView note = new TextView(ctx);
        note.setText(I18n.t(ctx, R.string.ig_app_icon_note));
        note.setTextColor(dlg.sub);
        note.setTextSize(13);
        note.setPadding(0, 0, 0, dlg.dp(8));
        wrap.addView(note);
        wrap.addView(grid);
        android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
        sv.addView(wrap);
        dlg.view(sv);
        shown[0] = dlg.show();
    }

    /**
     * Switching the alias at once ends the task that was started through the old alias, so
     * Instagram closed under the user (found in the agent test, 9 Nov). The pick is kept and applied
     * when the last Instagram activity stops, i.e. when the user leaves the app.
     */
    private static void schedule(Context ctx, int pick) {
        pending = pick;
        pendingPm = ctx.getApplicationContext().getPackageManager();
        ModuleLog.line(TAG + "icon " + ICONS[pick][2] + " will be set when Instagram goes to the background");
        IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_app_icon_applied, ICONS[pick][2]), Toast.LENGTH_LONG).show();
    }

    private static volatile int pending = -1, started = 0;
    private static volatile PackageManager pendingPm;

    private static void installLifecycle() {
        XposedHelpers.findAndHookMethod(Activity.class, "onStart", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { JitGuard.tick(param.method); started++; }
        });
        XposedHelpers.findAndHookMethod(Activity.class, "onStop", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { JitGuard.tick(param.method);
                started = Math.max(0, started - 1);
                Activity a = (Activity) param.thisObject;
                if (started == 0 && pending >= 0 && pendingPm != null && !a.isChangingConfigurations()) {
                    int p = pending;
                    pending = -1;
                    apply(pendingPm, p);
                }
            }
        });
    }

    /** Turn the picked alias on first (so a launcher entry always exists), then the others off. */
    private static void apply(PackageManager pm, int pick) {
        OURS.set(true);
        try {
            String target = pick == 0 ? BASE : BASE + "." + ICONS[pick][0];
            pm.setComponentEnabledSetting(new ComponentName(PKG, target),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);
            for (int i = 0; i < ICONS.length; i++) {
                if (i == pick) continue;
                String other = i == 0 ? BASE : BASE + "." + ICONS[i][0];
                pm.setComponentEnabledSetting(new ComponentName(PKG, other),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);
            }
            ModuleLog.line(TAG + "icon set to " + target);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "switch failed: " + t);
        } finally {
            OURS.remove();
        }
    }
}
