package ps.reso.instaeclipse.utils.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The module's in-Instagram dialog look (same as Search comments): rounded card that follows
 * dark/light mode, bold title with a close ✕, message / custom view / choice rows, and pill
 * buttons (filled primary, plain secondary).
 *
 * <pre>
 * new IeDialog(activity).title("Media info").message(text).primary("Copy", () -> ...).show();
 * </pre>
 */
public class IeDialog {

    public final Context ctx;
    public final float d;
    public final boolean dark;
    public final int bg, field, text, sub, line, accent, border;

    private final Dialog dialog;
    private final LinearLayout card, content, buttons;
    private final TextView titleView;
    private TextView messageView;
    private boolean hasButtons;
    private Runnable onDismiss;
    private Runnable onClose;

    public IeDialog(Context c) {
        Context a = c;
        while (!(a instanceof Activity) && a instanceof android.content.ContextWrapper w && w.getBaseContext() != null)
            a = w.getBaseContext();
        ctx = a instanceof Activity ? a : c;
        d = ctx.getResources().getDisplayMetrics().density;
        dark = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg = dark ? 0xFF1C1C1E : 0xFFFFFFFF;
        field = dark ? 0xFF2C2C2E : 0xFFEFEFF2;
        text = dark ? 0xFFF5F5F5 : 0xFF111111;
        sub = dark ? 0xFF9A9AA0 : 0xFF6B6B70;
        line = dark ? 0x22FFFFFF : 0x14000000;
        accent = ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine.accentOr(0xFF0095F6);
        border = dark ? 0x33FFFFFF : 0x1F000000;

        dialog = new Dialog(ctx);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(18);
        card.setPadding(pad, pad, pad, dp(14));
        card.setBackground(round(bg, 22));

        LinearLayout header = new LinearLayout(ctx);
        header.setGravity(Gravity.CENTER_VERTICAL);
        titleView = new TextView(ctx);
        titleView.setTextColor(text);
        titleView.setTextSize(19);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(titleView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = new TextView(ctx);
        close.setText("✕");
        close.setTextSize(18);
        close.setTextColor(sub);
        close.setPadding(dp(10), dp(4), dp(4), dp(4));
        close.setOnClickListener(v -> { dismiss(); if (onClose != null) onClose.run(); });
        header.addView(close);
        card.addView(header);

        content = new LinearLayout(ctx);
        content.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(ctx);
        scroll.addView(content);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        slp.topMargin = dp(12);
        card.addView(scroll, slp);

        buttons = new LinearLayout(ctx);
        buttons.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(16);
        card.addView(buttons, blp);
        buttons.setVisibility(View.GONE);
    }

    public int dp(float v) { return (int) (v * d); }

    public GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * d);
        return g;
    }

    /** Rounded row/card background with a hairline border, so rows stay distinct even when a
     *  custom theme maps the dialog and row fills to nearly the same colour. */
    public GradientDrawable cardBg() {
        GradientDrawable g = round(field, 14);
        g.setStroke(Math.max(1, dp(1)), border);
        return g;
    }

    public IeDialog title(CharSequence t) { titleView.setText(t); return this; }

    public IeDialog message(CharSequence m) {
        if (messageView == null) {
            messageView = new TextView(ctx);
            messageView.setTextColor(text);
            messageView.setTextSize(15);
            messageView.setLineSpacing(3 * d, 1f);
            content.addView(messageView, 0);
        }
        messageView.setText(m);
        return this;
    }

    /** Message the user can select / copy (e.g. technical info). */
    public IeDialog selectable() {
        if (messageView != null) messageView.setTextIsSelectable(true);
        return this;
    }

    public IeDialog view(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = content.getChildCount() > 0 ? dp(10) : 0;
        content.addView(v, lp);
        return this;
    }

    /** One tappable row per label; the row at {@code checked} (or -1) is marked. Tapping runs the
     *  listener with the index and closes the dialog. */
    public IeDialog choices(CharSequence[] labels, int checked, java.util.function.IntConsumer onPick) {
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            boolean on = i == checked;
            LinearLayout row = new LinearLayout(ctx);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(13), dp(14), dp(13));
            GradientDrawable g = cardBg();
            if (on) g.setStroke(Math.max(1, dp(1.5f)), accent);
            row.setBackground(g);
            TextView t = new TextView(ctx);
            t.setText(labels[i]);
            t.setTextColor(on ? accent : text);
            t.setTextSize(15);
            if (on) t.setTypeface(Typeface.DEFAULT_BOLD);
            row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            if (on) {
                TextView tick = new TextView(ctx);
                tick.setText("✓");
                tick.setTextColor(accent);
                tick.setTextSize(16);
                tick.setTypeface(Typeface.DEFAULT_BOLD);
                row.addView(tick);
            }
            row.setOnClickListener(v -> { dismiss(); onPick.accept(idx); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = content.getChildCount() > 0 ? dp(8) : 0;
            content.addView(row, lp);
        }
        return this;
    }

    /** A rounded card quoting {@code t} (e.g. the comment being copied), cut at {@code maxLines}. */
    public TextView quote(CharSequence t, int maxLines) {
        TextView q = new TextView(ctx);
        q.setText(t);
        q.setTextColor(text);
        q.setTextSize(14);
        q.setMaxLines(maxLines);
        q.setEllipsize(android.text.TextUtils.TruncateAt.END);
        q.setPadding(dp(14), dp(12), dp(14), dp(12));
        q.setBackground(round(field, 14));
        view(q);
        return q;
    }

    /** A read-only, selectable text box (all text pre-selected) for "copy part of it" dialogs. */
    public EditText selectableText(CharSequence t, int maxLines) {
        EditText et = new EditText(ctx);
        et.setText(t);
        et.setTextColor(text);
        et.setTextIsSelectable(true);
        et.setFocusableInTouchMode(true);
        et.setInputType(android.text.InputType.TYPE_NULL);
        et.setKeyListener(null);
        et.setHorizontallyScrolling(false);
        et.setMaxLines(maxLines);
        et.setTextSize(14);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        et.setBackground(round(field, 14));
        et.setSelection(0, et.getText().length());
        view(et);
        return et;
    }

    /** A rounded input in the dialog's style. */
    public EditText input(CharSequence hint) {
        EditText et = new EditText(ctx);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setTextColor(text);
        et.setHintTextColor(sub);
        et.setTextSize(16);
        et.setBackground(round(field, 14));
        et.setPadding(dp(16), dp(13), dp(16), dp(13));
        view(et);
        return et;
    }

    /** A check box row in the dialog's style. */
    public android.widget.CheckBox checkBox(CharSequence label, boolean checked) {
        android.widget.CheckBox cb = new android.widget.CheckBox(ctx);
        cb.setText(label);
        cb.setChecked(checked);
        cb.setTextColor(text);
        cb.setTextSize(15);
        cb.setButtonTintList(ColorStateList.valueOf(accent));
        cb.setPadding(dp(6), dp(6), 0, dp(6));
        view(cb);
        return cb;
    }

    /** Filled accent pill; closes the dialog after {@code action} unless {@code keepOpen}. */
    public TextView primary(CharSequence label, Runnable action) { return button(label, action, true, false); }

    public TextView primaryKeepOpen(CharSequence label, Runnable action) { return button(label, action, true, true); }

    /** Plain secondary button (e.g. Cancel); {@code action} may be null. */
    public TextView secondary(CharSequence label, Runnable action) { return button(label, action, false, false); }

    private TextView button(CharSequence label, Runnable action, boolean filled, boolean keepOpen) {
        TextView b = new TextView(ctx);
        b.setText(label);
        b.setTextSize(14);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setTextColor(filled ? Color.WHITE : sub);
        if (filled) b.setBackground(round(accent, 20));
        b.setOnClickListener(v -> {
            if (!keepOpen) dismiss();
            if (action != null) action.run();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (hasButtons) lp.setMarginStart(dp(8));
        buttons.addView(b, lp);
        buttons.setVisibility(View.VISIBLE);
        hasButtons = true;
        return b;
    }

    public IeDialog cancelable(boolean c) {
        dialog.setCancelable(c);
        dialog.setCanceledOnTouchOutside(c);
        return this;
    }

    public IeDialog onDismiss(Runnable r) { onDismiss = r; return this; }

    /** What the ✕, Back and a tap outside do besides closing (e.g. the same as Cancel). */
    public IeDialog onClose(Runnable r) { onClose = r; return this; }

    public Dialog show() {
        dialog.setContentView(card);
        dialog.setOnCancelListener(x -> { if (onClose != null) onClose.run(); });
        dialog.setOnDismissListener(x -> { if (onDismiss != null) onDismiss.run(); });
        dialog.show();
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout((int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.92f), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        return dialog;
    }

    public void dismiss() {
        try { if (dialog.isShowing()) dialog.dismiss(); } catch (Throwable ignored) {}
    }

    public boolean isShowing() { return dialog.isShowing(); }

    public Dialog dialog() { return dialog; }
}
