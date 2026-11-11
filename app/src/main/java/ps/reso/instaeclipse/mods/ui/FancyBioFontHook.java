package ps.reso.instaeclipse.mods.ui;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.text.FancyText;

/**
 * Edit profile: the bio's "Change font" button (an Instagram Plus upsell) opens the module's
 * fancy-text picker instead. The bio is rewritten with Unicode look-alike letters
 * ({@link FancyText}), so it is plain text that shows the same for everyone. Views are found by
 * their resource names, nothing obfuscated.
 */
public final class FancyBioFontHook {

    private static final Map<Activity, Boolean> WATCHED = Collections.synchronizedMap(new WeakHashMap<>());
    private static int editorId, bioId, cardId, labelId;
    private static boolean idsResolved;

    private FancyBioFontHook() {}

    public static void watchActivity(Activity a) {
        // watched even while off, so switching it on works without leaving the screen
        if (WATCHED.containsKey(a)) return;
        if (!idsResolved) {
            String pkg = a.getPackageName();
            // IG 451+: the bio editor is edit_bio_layout (the old "bio" id now only tags the
            // Edit-profile list row, so anchor the editor container first, fall back to "bio").
            editorId = a.getResources().getIdentifier("edit_bio_layout", "id", pkg);
            bioId = a.getResources().getIdentifier("bio", "id", pkg);
            cardId = a.getResources().getIdentifier("form_field_second_right_add_on_button_card", "id", pkg);
            labelId = a.getResources().getIdentifier("form_field_second_right_add_on_button_text", "id", pkg);
            idsResolved = true;
            if ((editorId == 0 && bioId == 0) || cardId == 0) return;
        } else if ((editorId == 0 && bioId == 0) || cardId == 0) {
            return;
        }
        WATCHED.put(a, true);
        View decor = a.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (!FeatureFlags.fancyBioFont) return;
            View editor = editorId != 0 ? decor.findViewById(editorId) : null;
            if (editor == null && bioId != 0) editor = decor.findViewById(bioId);
            if (editor != null) wire(a, editor);
        });
        FeatureStatusTracker.setHooked("FancyBioFont");
    }

    private static void wire(Activity a, View editor) {
        View card = editor.findViewById(cardId);
        if (card == null) return;
        EditText input = input(editor, card);
        if (input == null) return;
        String label = I18n.t(a, R.string.ig_fancy_font_button);
        TextView text = labelId != 0 ? card.findViewById(labelId) : null;
        if (text != null && text.getText().toString().startsWith(label) && card.getTag(R.id.ie_fancy_font) != null) return;
        if (text != null) text.setText(label);
        hideIcons(card);
        TextView labelView = text;
        View.OnClickListener open = v -> pick(a, input, labelView);
        card.setOnClickListener(open);
        if (text != null) text.setOnClickListener(open);
        card.setTag(R.id.ie_fancy_font, Boolean.TRUE);
    }

    /**
     * The bio's editable text field. The prism form field around it also reports itself as an
     * EditText, so we take the deepest EditText under the editor (the real input) and never the
     * "Change font" button's own subtree.
     */
    private static EditText input(View editor, View card) {
        EditText[] best = new EditText[1];
        int[] bestDepth = {-1};
        collectInput(editor, card, 0, best, bestDepth);
        return best[0];
    }

    private static void collectInput(View v, View card, int depth, EditText[] best, int[] bestDepth) {
        if (v == card) return; // skip the change-font button's own views
        if (v instanceof EditText e && depth > bestDepth[0]) {
            best[0] = e;
            bestDepth[0] = depth;
        }
        if (v instanceof ViewGroup g)
            for (int i = 0; i < g.getChildCount(); i++)
                collectInput(g.getChildAt(i), card, depth + 1, best, bestDepth);
    }

    /** The upsell's gem icon: it's no longer a paid feature. */
    private static void hideIcons(View v) {
        if (v instanceof ImageView) v.setVisibility(View.GONE);
        else if (v instanceof TextView t) {
            t.setCompoundDrawablesRelative(null, null, null, null);
            t.setCompoundDrawables(null, null, null, null);
        }
        else if (v instanceof ViewGroup g)
            for (int i = 0; i < g.getChildCount(); i++) hideIcons(g.getChildAt(i));
    }

    /** The style new typing gets, per bio field (0 = normal). */
    private static final Map<EditText, Integer> STYLE = Collections.synchronizedMap(new WeakHashMap<>());

    private static void pick(Activity a, EditText input, TextView label) {
        CharSequence[] rows = new CharSequence[FancyText.NAMES.length];
        for (int i = 0; i < rows.length; i++)
            rows[i] = FancyText.convert("Abc 123", i) + "   ·  " + FancyText.NAMES[i];
        Integer current = STYLE.get(input);
        IeDialog dlg = new IeDialog(a).title(I18n.t(a, R.string.ig_fancy_font_title));
        dlg.message(I18n.t(a, R.string.ig_fancy_font_note));
        dlg.choices(rows, current != null ? current : 0, i -> {
            if (!STYLE.containsKey(input)) input.addTextChangedListener(new Typing(input));
            STYLE.put(input, i);
            if (label != null) label.setText(i == 0 ? I18n.t(a, R.string.ig_fancy_font_button)
                    : I18n.t(a, R.string.ig_fancy_font_button) + ": " + FancyText.NAMES[i]);
            input.requestFocus();
        });
        dlg.show();
    }

    /**
     * Styles what's typed from now on; text already there keeps its look. Keyboards rewrite the
     * word being typed (the "composing" region) on every key, so that region is re-styled each
     * time and its composing span is put back over the styled text, or the keyboard would lose
     * track of it and repeat the word.
     */
    private static final class Typing implements android.text.TextWatcher {
        private final EditText input;
        private int start, count;
        private boolean busy;

        Typing(EditText input) { this.input = input; }

        @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

        @Override
        public void onTextChanged(CharSequence s, int st, int before, int c) {
            start = st;
            count = c;
        }

        @Override
        public void afterTextChanged(android.text.Editable e) {
            Integer style = STYLE.get(input);
            if (busy || style == null || style == 0 || count <= 0 || start + count > e.length()) return;
            String typed = e.subSequence(start, start + count).toString();
            String styled = FancyText.convert(typed, style);
            if (styled.equals(typed)) return;
            Object[] spans = e.getSpans(start, start + count, Object.class);
            int[][] where = new int[spans.length][];
            for (int i = 0; i < spans.length; i++)
                where[i] = new int[]{e.getSpanStart(spans[i]), e.getSpanEnd(spans[i]), e.getSpanFlags(spans[i])};
            int delta = styled.length() - typed.length();
            busy = true;
            try {
                e.replace(start, start + count, styled);
                for (int i = 0; i < spans.length; i++) {
                    if ((where[i][2] & android.text.Spanned.SPAN_COMPOSING) == 0) continue;
                    int s0 = where[i][0], s1 = where[i][1] + (where[i][1] >= start + count ? delta : 0);
                    if (s0 >= 0 && s1 >= s0 && s1 <= e.length()) e.setSpan(spans[i], s0, s1, where[i][2]);
                }
            } finally {
                busy = false;
            }
        }
    }
}
