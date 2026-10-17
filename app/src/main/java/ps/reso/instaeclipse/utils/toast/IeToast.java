package ps.reso.instaeclipse.utils.toast;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * The module's toast: a rounded card in the IeDialog palette (dark / light) with the InstaEclipse
 * mark on the left, faded and slid up as it appears. Drop-in for Toast.makeText(…).show(), safe
 * to call from any thread and from both Instagram's process and the companion app.
 */
public final class IeToast {

    private final Context ctx;
    private final CharSequence text;
    private final int duration;

    private IeToast(Context ctx, CharSequence text, int duration) {
        this.ctx = ctx;
        this.text = text;
        this.duration = duration;
    }

    public static IeToast makeText(Context ctx, CharSequence text, int duration) {
        return new IeToast(ctx, text, duration);
    }

    public static IeToast makeText(Context ctx, int resId, int duration) {
        CharSequence t;
        try { t = ctx.getText(resId); } catch (Throwable e) { t = ""; }
        return new IeToast(ctx, t, duration);
    }

    public void show() {
        if (ctx == null || text == null || text.length() == 0) return;
        Runnable r = () -> {
            try {
                showNow();
            } catch (Throwable t) {
                ModuleLog.line("(IE|Toast) fallback: " + t);
                try { Toast.makeText(ctx, text, duration).show(); } catch (Throwable ignored) {}
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else new Handler(Looper.getMainLooper()).post(r);
    }

    private void showNow() {
        Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        float d = app.getResources().getDisplayMetrics().density;
        boolean dark = (app.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int bg = dark ? 0xF21C1C1E : 0xF7FFFFFF;
        int fg = dark ? 0xFFF5F5F5 : 0xFF111111;
        int stroke = dark ? 0x1FFFFFFF : 0x1A000000;
        int accent = ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine.accentOr(0xFF0095F6);

        LinearLayout card = new LinearLayout(app);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding((int) (12 * d), (int) (10 * d), (int) (18 * d), (int) (10 * d));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(bg);
        shape.setCornerRadius(22 * d);
        shape.setStroke(Math.max(1, (int) d), stroke);
        card.setBackground(shape);
        card.setElevation(6 * d);

        // Accent badge with the InstaEclipse mark.
        ImageView mark = new ImageView(app);
        GradientDrawable badge = new GradientDrawable();
        badge.setShape(GradientDrawable.OVAL);
        badge.setColor(accent);
        mark.setBackground(badge);
        Drawable logo = moduleDrawable(app, R.drawable.ic_logo);
        if (logo != null) mark.setImageDrawable(logo);
        int pad = (int) (5 * d);
        mark.setPadding(pad, pad, pad, pad);
        int size = (int) (28 * d);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(size, size);
        mlp.setMarginEnd((int) (10 * d));
        card.addView(mark, mlp);

        TextView tv = new TextView(app);
        tv.setText(text);
        tv.setTextColor(fg);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setMaxLines(4);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tv.setMaxWidth((int) (app.getResources().getDisplayMetrics().widthPixels * 0.78f));
        card.addView(tv);

        // A little room so the elevation shadow isn't clipped by the toast window.
        LinearLayout root = new LinearLayout(app);
        int m = (int) (8 * d);
        root.setPadding(m, m, m, m);
        root.addView(card);

        Toast toast = new Toast(app);
        toast.setView(root);
        toast.setDuration(duration == Toast.LENGTH_LONG ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, (int) (96 * d));
        card.setAlpha(0f);
        card.setTranslationY(14 * d);
        card.animate().alpha(1f).translationY(0f).setDuration(220).start();
        toast.show();
    }

    /** Module drawable: from our own APK inside Instagram, from resources in the companion app. */
    private static Drawable moduleDrawable(Context c, int res) {
        try {
            if ("ps.reso.instaeclipse".equals(c.getPackageName())) return c.getDrawable(res);
            if (Module.moduleSourceDir == null) return null;
            return android.content.res.XModuleResources.createInstance(Module.moduleSourceDir, null)
                    .getDrawable(res, null);
        } catch (Throwable t) {
            return null;
        }
    }
}
