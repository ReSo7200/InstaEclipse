package ps.reso.instaeclipse.utils.dialog;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.ProfileSpoofHook;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.profile.SpoofAvatar;

/**
 * The profile spoofer's editor, shared by Instagram's InstaEclipse menu (in an IeDialog) and the
 * companion app (in a Material dialog): a live preview styled like Instagram's profile header
 * (picture, username with the badge, posts / followers / following, name, bio) above grouped
 * inputs (Identity, Stats, Badge, Picture) and "Reset all". An empty field keeps the real value.
 *
 * <p>Circles (picture, placeholder, badge) are painted drawables, not shape drawables, so
 * Instagram's theme engine can't recolor them.
 */
public final class ProfileSpoofEditor {

    /** Colors of the surrounding dialog. */
    public static final class Palette {
        final int text, sub, field, accent, border;
        public Palette(int text, int sub, int field, int accent, int border) {
            this.text = text; this.sub = sub; this.field = field; this.accent = accent; this.border = border;
        }
    }

    /** The spoofer's settings; "" / -1 = keep the real value. */
    public static final class Values {
        public String username = "", name = "", bio = "", avatar = "";
        public int posts = -1, followers = -1, following = -1;
        public boolean verified;
    }

    public static final int MAX_BIO = 150;

    private final Context ctx;
    private final Palette p;
    private final float d;
    private final String knownUsername;
    private final LinearLayout root;
    private final EditText username, name, bio, posts, followers, following;
    private final android.widget.Switch verified;
    private final ImageView previewAvatar, pictureThumb;
    private final TextView previewUsername, previewName, previewBio, previewPosts, previewFollowers, previewFollowing;
    private final TextView removePhoto;
    private String avatar;

    /**
     * @param onChoosePhoto opens a picker; its result goes to {@link #setAvatar}
     * @param photoNote     shown under the picture buttons (e.g. "opens the InstaEclipse app"), or null
     * @param knownUsername your real username for the preview, or null
     */
    public ProfileSpoofEditor(Context ctx, Values v, Palette p, Runnable onChoosePhoto, String photoNote, String knownUsername) {
        this.ctx = ctx;
        this.p = p;
        this.d = ctx.getResources().getDisplayMetrics().density;
        this.knownUsername = knownUsername;
        this.avatar = v.avatar == null ? "" : v.avatar;

        root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);

        // ---- Preview: Instagram's profile header ----
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(16));
        card.setBackground(rounded(p.field, 18, true));

        previewUsername = text(17, p.text, true);
        previewUsername.setSingleLine(true);
        previewUsername.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(previewUsername, wrap());

        LinearLayout row = new LinearLayout(ctx);
        row.setGravity(Gravity.CENTER_VERTICAL);
        previewAvatar = new ImageView(ctx);
        row.addView(previewAvatar, new LinearLayout.LayoutParams(dp(76), dp(76)));
        previewPosts = stat(row, R.string.ig_profile_spoofer_posts);
        previewFollowers = stat(row, R.string.ig_profile_spoofer_followers);
        previewFollowing = stat(row, R.string.ig_profile_spoofer_following);
        LinearLayout.LayoutParams rowLp = matchWrap();
        rowLp.topMargin = dp(12);
        card.addView(row, rowLp);

        previewName = text(14, p.text, true);
        LinearLayout.LayoutParams nameLp = matchWrap();
        nameLp.topMargin = dp(10);
        card.addView(previewName, nameLp);
        previewBio = text(14, p.text, false);
        previewBio.setMaxLines(5);
        previewBio.setEllipsize(TextUtils.TruncateAt.END);
        previewBio.setLineSpacing(2 * d, 1f);
        LinearLayout.LayoutParams bioLp = matchWrap();
        bioLp.topMargin = dp(2);
        card.addView(previewBio, bioLp);
        root.addView(card, matchWrap());

        TextView note = text(13, p.sub, false);
        note.setText(I18n.t(ctx, R.string.ig_profile_spoofer_note));
        note.setLineSpacing(2 * d, 1f);
        LinearLayout.LayoutParams noteLp = matchWrap();
        noteLp.topMargin = dp(10);
        root.addView(note, noteLp);

        // ---- Identity ----
        LinearLayout identity = section(R.string.ig_profile_spoofer_section_identity);
        username = input(identity, R.string.ig_profile_spoofer_username, v.username, false, 30);
        name = input(identity, R.string.ig_profile_spoofer_name, v.name, false, 64);
        bio = input(identity, R.string.ig_profile_spoofer_bio, v.bio, true, MAX_BIO);

        // ---- Stats ----
        LinearLayout stats = section(R.string.ig_profile_spoofer_section_stats);
        LinearLayout statRow = new LinearLayout(ctx);
        stats.addView(statRow, matchWrap());
        posts = countInput(statRow, R.string.ig_profile_spoofer_posts, v.posts, false);
        followers = countInput(statRow, R.string.ig_profile_spoofer_followers, v.followers, true);
        following = countInput(statRow, R.string.ig_profile_spoofer_following, v.following, true);

        // ---- Badge ----
        LinearLayout badge = section(R.string.ig_profile_spoofer_section_badge);
        LinearLayout badgeRow = new LinearLayout(ctx);
        badgeRow.setGravity(Gravity.CENTER_VERTICAL);
        badgeRow.setPadding(dp(14), dp(6), dp(8), dp(6));
        badgeRow.setBackground(rounded(p.field, 14, false));
        TextView badgeLabel = text(15, p.text, false);
        badgeLabel.setText(I18n.t(ctx, R.string.ig_profile_spoofer_verified));
        ImageView seal = new ImageView(ctx);
        seal.setImageDrawable(new ProfileSpoofHook.VerifiedBadge());
        LinearLayout.LayoutParams sealLp = new LinearLayout.LayoutParams(dp(18), dp(18));
        sealLp.setMarginEnd(dp(10));
        badgeRow.addView(seal, sealLp);
        badgeRow.addView(badgeLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        verified = new android.widget.Switch(ctx);
        verified.setChecked(v.verified);
        int[][] states = {{android.R.attr.state_checked}, {}};
        verified.setThumbTintList(new ColorStateList(states, new int[]{p.accent, 0xFFF2F2F2}));
        verified.setTrackTintList(new ColorStateList(states, new int[]{(p.accent & 0x00FFFFFF) | 0x80000000, 0x40808080}));
        badgeRow.addView(verified);
        badgeRow.setOnClickListener(x -> verified.toggle());
        badge.addView(badgeRow, matchWrap());
        verified.setOnCheckedChangeListener((b, c) -> refresh());

        // ---- Picture ----
        LinearLayout picture = section(R.string.ig_profile_spoofer_section_picture);
        LinearLayout picRow = new LinearLayout(ctx);
        picRow.setGravity(Gravity.CENTER_VERTICAL);
        picRow.setPadding(dp(12), dp(10), dp(12), dp(10));
        picRow.setBackground(rounded(p.field, 14, false));
        pictureThumb = new ImageView(ctx);
        LinearLayout.LayoutParams thumbLp = new LinearLayout.LayoutParams(dp(44), dp(44));
        thumbLp.setMarginEnd(dp(12));
        picRow.addView(pictureThumb, thumbLp);
        TextView choose = pill(I18n.t(ctx, R.string.ig_profile_spoofer_choose_photo), true);
        choose.setOnClickListener(x -> { if (onChoosePhoto != null) onChoosePhoto.run(); });
        picRow.addView(choose, wrap());
        removePhoto = pill(I18n.t(ctx, R.string.ig_profile_spoofer_remove_photo), false);
        removePhoto.setOnClickListener(x -> setAvatar(""));
        LinearLayout.LayoutParams removeLp = wrap();
        removeLp.setMarginStart(dp(6));
        picRow.addView(removePhoto, removeLp);
        picture.addView(picRow, matchWrap());
        if (photoNote != null && !photoNote.isEmpty()) {
            TextView pn = text(12, p.sub, false);
            pn.setText(photoNote);
            LinearLayout.LayoutParams pnLp = matchWrap();
            pnLp.topMargin = dp(6);
            pnLp.setMarginStart(dp(4));
            picture.addView(pn, pnLp);
        }

        // ---- Reset all ----
        TextView reset = text(14, p.accent, true);
        reset.setText(I18n.t(ctx, R.string.ig_profile_spoofer_reset_all));
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(dp(12), dp(12), dp(12), dp(4));
        reset.setOnClickListener(x -> reset());
        LinearLayout.LayoutParams resetLp = matchWrap();
        resetLp.topMargin = dp(8);
        root.addView(reset, resetLp);

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refresh(); }
        };
        for (EditText e : new EditText[]{username, name, bio, posts, followers, following}) e.addTextChangedListener(watcher);
        refresh();
    }

    public View view() { return root; }

    /** The edited values (avatar included). */
    public Values values() {
        Values v = new Values();
        v.username = username.getText().toString().trim();
        v.name = name.getText().toString().trim();
        v.bio = bio.getText().toString().trim();
        v.posts = DialogUtils.parseSpoofCount(posts.getText().toString());
        v.followers = DialogUtils.parseSpoofCount(followers.getText().toString());
        v.following = DialogUtils.parseSpoofCount(following.getText().toString());
        v.verified = verified.isChecked();
        v.avatar = avatar;
        return v;
    }

    /** The chosen picture (Base64 JPEG), or "" for none. */
    public void setAvatar(String b64) {
        avatar = b64 == null ? "" : b64;
        refresh();
    }

    public void reset() {
        for (EditText e : new EditText[]{username, name, bio, posts, followers, following}) e.setText("");
        verified.setChecked(false);
        setAvatar("");
    }

    private void refresh() {
        Values v = values();
        String user = !v.username.isEmpty() ? v.username : knownUsername;
        previewUsername.setText(user != null && !user.isEmpty() ? user : I18n.t(ctx, R.string.ig_profile_spoofer_username));
        previewUsername.setTextColor(user != null && !user.isEmpty() ? p.text : p.sub);
        if (v.verified) {
            ProfileSpoofHook.VerifiedBadge b = new ProfileSpoofHook.VerifiedBadge();
            int size = Math.round(previewUsername.getTextSize() * 0.85f);
            b.setBounds(0, 0, size, size);
            previewUsername.setCompoundDrawablePadding(dp(5));
            previewUsername.setCompoundDrawablesRelative(null, null, b, null);
        } else {
            previewUsername.setCompoundDrawablesRelative(null, null, null, null);
        }
        previewName.setText(!v.name.isEmpty() ? v.name : I18n.t(ctx, R.string.ig_profile_spoofer_name));
        previewName.setTextColor(!v.name.isEmpty() ? p.text : p.sub);
        previewBio.setText(!v.bio.isEmpty() ? v.bio : I18n.t(ctx, R.string.ig_profile_spoofer_bio));
        previewBio.setTextColor(!v.bio.isEmpty() ? p.text : p.sub);
        previewPosts.setText(count(v.posts));
        previewFollowers.setText(count(v.followers));
        previewFollowing.setText(count(v.following));

        android.graphics.Bitmap bm = SpoofAvatar.bitmap(avatar);
        int placeholderBg = (p.sub & 0x00FFFFFF) | 0x40000000;
        previewAvatar.setImageDrawable(bm != null ? new SpoofAvatar.CircleDrawable(bm)
                : new SpoofAvatar.PlaceholderDrawable(placeholderBg, p.sub));
        pictureThumb.setImageDrawable(bm != null ? new SpoofAvatar.CircleDrawable(bm)
                : new SpoofAvatar.PlaceholderDrawable(placeholderBg, p.sub));
        removePhoto.setVisibility(bm != null ? View.VISIBLE : View.GONE);
    }

    private static String count(int n) { return n < 0 ? "–" : ProfileSpoofHook.shortCount(n); }

    // ---- building blocks ----

    private TextView stat(LinearLayout row, int label) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView number = text(16, p.text, true);
        number.setGravity(Gravity.CENTER);
        number.setSingleLine(true);
        col.addView(number, wrap());
        TextView l = text(13, p.text, false);
        l.setText(I18n.t(ctx, label));
        l.setGravity(Gravity.CENTER);
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(l, wrap());
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return number;
    }

    /** A titled group; its inputs go into the returned layout. */
    private LinearLayout section(int title) {
        TextView t = text(12, p.sub, true);
        t.setText(I18n.t(ctx, title).toUpperCase(java.util.Locale.getDefault()));
        t.setLetterSpacing(0.06f);
        LinearLayout.LayoutParams tlp = matchWrap();
        tlp.topMargin = dp(18);
        tlp.setMarginStart(dp(4));
        root.addView(t, tlp);
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams blp = matchWrap();
        blp.topMargin = dp(6);
        root.addView(box, blp);
        return box;
    }

    private EditText input(LinearLayout box, int label, String value, boolean multiLine, int max) {
        TextView l = text(12, p.sub, false);
        l.setText(I18n.t(ctx, label));
        LinearLayout.LayoutParams llp = matchWrap();
        llp.topMargin = box.getChildCount() > 0 ? dp(10) : 0;
        llp.setMarginStart(dp(4));
        box.addView(l, llp);
        EditText e = field(I18n.t(ctx, R.string.ig_profile_spoofer_keep_real));
        if (multiLine) {
            e.setSingleLine(false);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            e.setMinLines(2);
            e.setMaxLines(5);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        }
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        if (value != null) e.setText(value);
        LinearLayout.LayoutParams elp = matchWrap();
        elp.topMargin = dp(4);
        box.addView(e, elp);
        return e;
    }

    private EditText countInput(LinearLayout row, int label, int value, boolean gap) {
        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(12, p.sub, false);
        l.setText(I18n.t(ctx, label));
        l.setSingleLine(true);
        l.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams llp = matchWrap();
        llp.setMarginStart(dp(4));
        col.addView(l, llp);
        EditText e = field(I18n.t(ctx, R.string.ig_profile_spoofer_keep_real));
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
        e.setTextSize(15);
        e.setHint(I18n.t(ctx, R.string.ig_profile_spoofer_keep_real));
        e.setPadding(dp(12), dp(11), dp(12), dp(11));
        if (value >= 0) e.setText(String.valueOf(value));
        LinearLayout.LayoutParams elp = matchWrap();
        elp.topMargin = dp(4);
        col.addView(e, elp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        if (gap) clp.setMarginStart(dp(8));
        row.addView(col, clp);
        return e;
    }

    private EditText field(String hint) {
        EditText e = new EditText(ctx);
        e.setHint(hint);
        e.setTextColor(p.text);
        e.setHintTextColor((p.sub & 0x00FFFFFF) | 0xA0000000);
        e.setTextSize(16);
        e.setBackground(rounded(p.field, 14, false));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        return e;
    }

    private TextView pill(String label, boolean filled) {
        TextView b = text(14, filled ? 0xFFFFFFFF : p.text, true);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable g = rounded(filled ? p.accent : 0x00000000, 18, false);
        if (!filled) g.setStroke(Math.max(1, dp(1)), p.border);
        b.setBackground(g);
        return b;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(ctx);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable rounded(int color, float radiusDp, boolean border) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * d);
        if (border) g.setStroke(Math.max(1, dp(1)), p.border);
        return g;
    }

    private int dp(float v) { return Math.round(v * d); }
    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
