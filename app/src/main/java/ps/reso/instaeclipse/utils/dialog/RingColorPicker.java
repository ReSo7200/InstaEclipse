package ps.reso.instaeclipse.utils.dialog;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.StoryTrayHook;
import ps.reso.instaeclipse.utils.i18n.I18n;

/**
 * Story ring color picker (#283): a live ring preview, then Instagram's colors, one color, or a
 * gradient of 2 to 4 colors. Presets fill it in one tap; the selected color is tuned with hue,
 * saturation and brightness sliders or typed as hex. Built from plain views so the same picker
 * runs inside Instagram and in the companion app. The value it reports is the storyRingColor
 * setting: "" (Instagram's), "#RRGGBB" or "#RRGGBB,#RRGGBB,..." .
 */
public final class RingColorPicker {

    public static final String[] SOLID_PRESETS = {"#3897F0", "#0A84FF", "#5E5CE6", "#A855F7", "#FF2D92",
            "#FF3B30", "#FF9500", "#FFD60A", "#34C759", "#00C7BE", "#FFFFFF", "#8E8E93"};
    public static final String[] GRADIENT_PRESETS = {"#FF0066,#7638FA", "#00C6FF,#0072FF",
            "#F9D423,#FF4E50", "#43E97B,#38F9D7", "#FA709A,#FEE140", "#8E2DE2,#4A00E0",
            "#FF9A9E,#FAD0C4,#A18CD1", "#12C2E9,#C471ED,#F64F59", "#FEDA75,#FA7E1E,#D62976,#962FBF"};
    /** Instagram's own unseen ring, for the preview of the default. */
    private static final int[] INSTAGRAM = {0xFFFEDA75, 0xFFFA7E1E, 0xFFD62976, 0xFF962FBF, 0xFF4F5BD5};
    private static final int MAX_STOPS = 4;
    private static final int DEFAULT = 0, SOLID = 1, GRADIENT = 2;

    /** Colors of the surrounding dialog, so the picker matches either menu. */
    public static final class Palette {
        final int text, sub, field, accent;
        public Palette(int text, int sub, int field, int accent) {
            this.text = text; this.sub = sub; this.field = field; this.accent = accent;
        }
    }

    private final Context ctx;
    private final Palette p;
    private final Consumer<String> onChange;
    private final float density;
    private final List<Integer> stops = new ArrayList<>();
    private int mode, selected;
    private boolean syncing;

    private final LinearLayout root;
    private final RingView preview;
    private final TextView[] segments = new TextView[3];
    private final LinearLayout presets, stopsRow, editor;
    private final View stopsBlock, presetsBlock;
    private final TextView stopsHint;
    private final SeekBar hue, sat, val;
    private final EditText hex;

    public static View build(Context ctx, String current, Palette palette, Consumer<String> onChange) {
        return new RingColorPicker(ctx, current, palette, onChange).root;
    }

    private RingColorPicker(Context ctx, String current, Palette palette, Consumer<String> onChange) {
        this.ctx = ctx;
        this.p = palette;
        this.onChange = onChange;
        density = ctx.getResources().getDisplayMetrics().density;

        int[] parsed = StoryTrayHook.parseColors(current);
        if (parsed == null) {
            mode = DEFAULT;
            stops.add(0xFFFF0066);
            stops.add(0xFF7638FA);
        } else {
            mode = parsed.length == 1 ? SOLID : GRADIENT;
            for (int c : parsed) stops.add(c);
        }

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        preview = new RingView(ctx);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(112), dp(112));
        plp.gravity = Gravity.CENTER_HORIZONTAL;
        plp.bottomMargin = dp(14);
        root.addView(preview, plp);

        LinearLayout seg = new LinearLayout(ctx);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        seg.setBackground(round(p.field, 14));
        int[] labels = {R.string.ig_story_ring_mode_default, R.string.ig_story_ring_solid, R.string.ig_story_ring_gradient};
        for (int i = 0; i < 3; i++) {
            final int m = i;
            TextView t = new TextView(ctx);
            t.setText(I18n.t(ctx, labels[i]));
            t.setGravity(Gravity.CENTER);
            t.setTextSize(13.5f);
            t.setSingleLine(true);
            t.setPadding(dp(6), dp(9), dp(6), dp(9));
            t.setOnClickListener(v -> setMode(m));
            seg.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            segments[i] = t;
        }
        root.addView(seg, matchWrap(0));

        presets = new LinearLayout(ctx);
        presets.setOrientation(LinearLayout.HORIZONTAL);
        presets.setPadding(dp(2), dp(4), dp(2), dp(4));
        HorizontalScrollView presetScroll = new HorizontalScrollView(ctx);
        presetScroll.setHorizontalScrollBarEnabled(false);
        presetScroll.addView(presets);
        presetsBlock = presetScroll;
        root.addView(presetScroll, matchWrap(14));

        LinearLayout stopsWrap = new LinearLayout(ctx);
        stopsWrap.setOrientation(LinearLayout.VERTICAL);
        stopsRow = new LinearLayout(ctx);
        stopsRow.setOrientation(LinearLayout.HORIZONTAL);
        stopsRow.setGravity(Gravity.CENTER_VERTICAL);
        stopsWrap.addView(stopsRow, matchWrap(0));
        stopsHint = new TextView(ctx);
        stopsHint.setText(I18n.t(ctx, R.string.ig_story_ring_stops_hint));
        stopsHint.setTextColor(p.sub);
        stopsHint.setTextSize(12);
        stopsWrap.addView(stopsHint, matchWrap(6));
        stopsBlock = stopsWrap;
        root.addView(stopsWrap, matchWrap(12));

        editor = new LinearLayout(ctx);
        editor.setOrientation(LinearLayout.VERTICAL);
        hue = slider(360);
        sat = slider(100);
        val = slider(100);
        editor.addView(hue, matchWrap(0));
        editor.addView(sat, matchWrap(10));
        editor.addView(val, matchWrap(10));
        hex = new EditText(ctx);
        hex.setSingleLine(true);
        hex.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        hex.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        hex.setTextColor(p.text);
        hex.setHintTextColor(p.sub);
        hex.setHint("#RRGGBB");
        hex.setTypeface(android.graphics.Typeface.MONOSPACE);
        hex.setGravity(Gravity.CENTER);
        hex.setBackground(round(p.field, 12));
        hex.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT);
        hlp.gravity = Gravity.CENTER_HORIZONTAL;
        hlp.topMargin = dp(14);
        editor.addView(hex, hlp);
        root.addView(editor, matchWrap(14));

        SeekBar.OnSeekBarChangeListener fromSliders = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser || syncing) return;
                setSelectedColor(Color.HSVToColor(new float[]{hue.getProgress(), sat.getProgress() / 100f,
                        val.getProgress() / 100f}), true);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        };
        hue.setOnSeekBarChangeListener(fromSliders);
        sat.setOnSeekBarChangeListener(fromSliders);
        val.setOnSeekBarChangeListener(fromSliders);
        hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable e) {
                if (syncing) return;
                String v = e.toString().trim();
                if (!v.matches("#?[0-9A-Fa-f]{6}")) return;
                setSelectedColor(0xFF000000 | Integer.parseInt(v.startsWith("#") ? v.substring(1) : v, 16), false);
            }
        });

        refresh();
    }

    // ---- state ----

    private String value() {
        if (mode == DEFAULT) return "";
        StringBuilder sb = new StringBuilder();
        int n = mode == SOLID ? 1 : stops.size();
        for (int i = 0; i < n; i++) sb.append(i > 0 ? "," : "").append(hexOf(stops.get(i)));
        return sb.toString();
    }

    private void setMode(int m) {
        if (m == mode) return;
        mode = m;
        if (mode == GRADIENT && stops.size() < 2) stops.add(shiftHue(stops.get(0), 50));
        if (selected >= (mode == SOLID ? 1 : stops.size())) selected = 0;
        changed();
    }

    private void setSelectedColor(int color, boolean updateHex) {
        stops.set(selected, color);
        syncing = true;
        try {
            if (updateHex) hex.setText(hexOf(color));
            else syncSliders();
        } finally { syncing = false; }
        updateTracks();
        rebuildStops();
        rebuildPresets();
        preview.invalidate();
        onChange.accept(value());
    }

    private void applyPreset(String value) {
        int[] colors = StoryTrayHook.parseColors(value);
        if (colors == null) return;
        stops.clear();
        for (int c : colors) stops.add(c);
        mode = colors.length == 1 ? SOLID : GRADIENT;
        selected = 0;
        changed();
    }

    private void changed() {
        refresh();
        onChange.accept(value());
    }

    private void refresh() {
        for (int i = 0; i < 3; i++) {
            boolean on = i == mode;
            segments[i].setBackground(on ? round(p.accent, 11) : null);
            segments[i].setTextColor(on ? 0xFFFFFFFF : p.text);
            segments[i].setTypeface(null, on ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        presetsBlock.setVisibility(mode == DEFAULT ? View.GONE : View.VISIBLE);
        stopsBlock.setVisibility(mode == GRADIENT ? View.VISIBLE : View.GONE);
        editor.setVisibility(mode == DEFAULT ? View.GONE : View.VISIBLE);
        rebuildPresets();
        rebuildStops();
        syncing = true;
        try {
            syncSliders();
            hex.setText(hexOf(stops.get(selected)));
        } finally { syncing = false; }
        updateTracks();
        preview.invalidate();
    }

    private void syncSliders() {
        float[] hsv = new float[3];
        Color.colorToHSV(stops.get(selected), hsv);
        hue.setProgress(Math.round(hsv[0]));
        sat.setProgress(Math.round(hsv[1] * 100));
        val.setProgress(Math.round(hsv[2] * 100));
    }

    /** Saturation and brightness tracks follow the hue, so each slider shows where it leads. */
    private void updateTracks() {
        float h = hue.getProgress(), s = sat.getProgress() / 100f, v = val.getProgress() / 100f;
        int[] rainbow = new int[7];
        for (int i = 0; i < rainbow.length; i++) rainbow[i] = Color.HSVToColor(new float[]{i * 60f, 1f, 1f});
        track(hue, rainbow);
        track(sat, new int[]{Color.HSVToColor(new float[]{h, 0f, v}), Color.HSVToColor(new float[]{h, 1f, v})});
        track(val, new int[]{0xFF000000, Color.HSVToColor(new float[]{h, s, 1f})});
    }

    // ---- rows ----

    private void rebuildPresets() {
        presets.removeAllViews();
        String current = value();
        for (String preset : mode == GRADIENT ? GRADIENT_PRESETS : SOLID_PRESETS) {
            int[] colors = StoryTrayHook.parseColors(preset);
            boolean on = preset.equalsIgnoreCase(current);
            View v = new View(ctx);
            v.setBackground(new Swatch(colors, true, 0, on ? p.accent : 0x33888888, dp(on ? 3 : 1)));
            v.setOnClickListener(x -> applyPreset(preset));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(38), dp(38));
            lp.setMarginEnd(dp(10));
            presets.addView(v, lp);
        }
    }

    private void rebuildStops() {
        stopsRow.removeAllViews();
        if (mode != GRADIENT) return;
        for (int i = 0; i < stops.size(); i++) {
            final int idx = i;
            View v = new View(ctx);
            v.setBackground(new Swatch(new int[]{stops.get(i)}, true, 0,
                    idx == selected ? p.accent : 0x33888888, dp(idx == selected ? 3 : 1)));
            v.setOnClickListener(x -> { selected = idx; refresh(); });
            v.setOnLongClickListener(x -> {
                if (stops.size() <= 2) return false;
                stops.remove(idx);
                if (selected >= stops.size()) selected = stops.size() - 1;
                changed();
                return true;
            });
            stopsRow.addView(v, chipParams(34));
            if (i < stops.size() - 1) {
                View link = new View(ctx);
                link.setBackground(new Swatch(new int[]{stops.get(i), stops.get(i + 1)}, false, dp(2), 0, 0));
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(dp(14), dp(4));
                llp.setMarginEnd(dp(6));
                stopsRow.addView(link, llp);
            }
        }
        if (stops.size() < MAX_STOPS) stopsRow.addView(textChip("+", () -> {
            stops.add(selected + 1, shiftHue(stops.get(selected), 40));
            selected++;
            changed();
        }), chipParams(34));
        stopsRow.addView(textChip("⇄", () -> {
            java.util.Collections.reverse(stops);
            selected = stops.size() - 1 - selected;
            changed();
        }), chipParams(34));
    }

    private TextView textChip(String label, Runnable action) {
        TextView t = new TextView(ctx);
        t.setText(label);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(17);
        t.setTextColor(p.text);
        GradientDrawable bg = oval(p.field);
        bg.setStroke(dp(1), 0x33888888);
        t.setBackground(bg);
        t.setOnClickListener(v -> action.run());
        return t;
    }

    private LinearLayout.LayoutParams chipParams(int size) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(size), dp(size));
        lp.setMarginEnd(dp(6));
        return lp;
    }

    // ---- views ----

    private SeekBar slider(int max) {
        SeekBar s = new SeekBar(ctx);
        s.setMax(max);
        s.setSplitTrack(false);
        s.setPadding(dp(14), dp(8), dp(14), dp(8));
        Swatch thumb = new Swatch(new int[]{0xFFFFFFFF}, true, 0, 0x40000000, dp(2));
        thumb.size = dp(24);
        s.setThumb(thumb);
        s.setThumbOffset(dp(12));
        return s;
    }

    private void track(SeekBar s, int[] colors) {
        Swatch g = new Swatch(colors, false, dp(6), 0, 0);
        g.height = dp(12);
        s.setProgressDrawable(g);
        s.setMinimumHeight(dp(12));
    }

    /** The ring the way Instagram draws it: a diagonal gradient around the avatar, with a gap. */
    private final class RingView extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();

        RingView(Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
        }

        @Override protected void onDraw(Canvas canvas) {
            float size = Math.min(getWidth(), getHeight());
            float cx = getWidth() / 2f, cy = getHeight() / 2f, r = size / 2f;
            float stroke = size * 0.055f;
            int[] colors = mode == DEFAULT ? INSTAGRAM : mode == SOLID ? new int[]{stops.get(0), stops.get(0)}
                    : toArray(stops);
            ring.setStrokeWidth(stroke);
            ring.setShader(new LinearGradient(cx + r, cy - r, cx - r, cy + r, colors, null, Shader.TileMode.CLAMP));
            float rr = r - stroke / 2f;
            box.set(cx - rr, cy - rr, cx + rr, cy + rr);
            canvas.drawOval(box, ring);
            float avatar = r - stroke - size * 0.045f;
            fill.setColor(p.field);
            canvas.drawCircle(cx, cy, avatar, fill);
            // A plain person: head and shoulders, clipped to the avatar.
            fill.setColor(p.sub);
            canvas.save();
            android.graphics.Path clip = new android.graphics.Path();
            clip.addCircle(cx, cy, avatar, android.graphics.Path.Direction.CW);
            canvas.clipPath(clip);
            canvas.drawCircle(cx, cy - avatar * 0.18f, avatar * 0.32f, fill);
            canvas.drawOval(cx - avatar * 0.62f, cy + avatar * 0.28f, cx + avatar * 0.62f, cy + avatar * 1.25f, fill);
            canvas.restore();
        }
    }

    /**
     * Color content painted directly. Instagram's theme engine recolors GradientDrawable and
     * ColorDrawable fills, which would turn every swatch into the theme's colors; a plain Paint
     * keeps the real ones. Several colors run diagonally (a dot, like the ring) or left to right.
     */
    private static final class Swatch extends android.graphics.drawable.Drawable {
        private final int[] colors;
        private final boolean oval;
        private final float radius;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        int size = -1, height = -1;

        Swatch(int[] colors, boolean oval, float radius, int strokeColor, float strokeWidth) {
            this.colors = colors;
            this.oval = oval;
            this.radius = radius;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(strokeColor);
            stroke.setStrokeWidth(strokeWidth);
        }

        @Override public void draw(Canvas canvas) {
            android.graphics.Rect b = getBounds();
            float inset = stroke.getStrokeWidth() / 2f;
            box.set(b.left + inset, b.top + inset, b.right - inset, b.bottom - inset);
            if (colors.length == 1) {
                fill.setShader(null);
                fill.setColor(colors[0]);
            } else {
                fill.setShader(oval
                        ? new LinearGradient(box.right, box.top, box.left, box.bottom, colors, null, Shader.TileMode.CLAMP)
                        : new LinearGradient(box.left, 0, box.right, 0, colors, null, Shader.TileMode.CLAMP));
            }
            if (oval) canvas.drawOval(box, fill); else canvas.drawRoundRect(box, radius, radius, fill);
            if (stroke.getStrokeWidth() > 0) {
                if (oval) canvas.drawOval(box, stroke); else canvas.drawRoundRect(box, radius, radius, stroke);
            }
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return height > 0 ? height : size; }
        @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    // ---- helpers ----

    private static int[] toArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private static int shiftHue(int color, float degrees) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[0] = (hsv[0] + degrees) % 360f;
        if (hsv[1] < 0.35f) hsv[1] = 0.75f;
        if (hsv[2] < 0.35f) hsv[2] = 0.9f;
        return Color.HSVToColor(hsv);
    }

    private static String hexOf(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    private GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private LinearLayout.LayoutParams matchWrap(int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topMarginDp);
        return lp;
    }

    private int dp(float v) { return Math.round(v * density); }
}
