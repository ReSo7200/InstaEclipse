package ps.reso.instaeclipse.fragments;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.AttrRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;

import com.google.android.material.color.MaterialColors;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.ExportBrand;

public class HelpFragment extends Fragment {

    private static final String VECTOR_URL = "https://github.com/JingMatrix/Vector/releases/latest";
    private static final String LSPATCH_URL = "https://github.com/JingMatrix/LSPatch/releases/latest";
    private static final String LSPATCH_GUIDE_URL = "https://t.me/instaEclipse_discussion/158/5149";

    private static final int[] FAQ_STRINGS = {
            R.string.faq_1, R.string.faq_2, R.string.faq_3,
            R.string.faq_4, R.string.faq_5, R.string.faq_6,
            R.string.faq_7, R.string.faq_8, R.string.faq_9
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_help, container, false);

        buildFaq(view.findViewById(R.id.faq_container));

        // Troubleshooting: JingMatrix's Vector (rooted) / LSPatch (not rooted) + the LSPatch guide.
        view.findViewById(R.id.vector_row).setOnClickListener(v -> openUrl(VECTOR_URL));
        view.findViewById(R.id.lspatch_row).setOnClickListener(v -> openUrl(LSPATCH_URL));
        view.findViewById(R.id.lspatch_guide_button).setOnClickListener(v -> openUrl(LSPATCH_GUIDE_URL));
        for (int id : new int[]{R.id.vector_logo, R.id.lspatch_logo}) {
            View logo = view.findViewById(id);
            logo.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View v, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12));
                }
            });
            logo.setClipToOutline(true);
        }

        view.findViewById(R.id.github_button).setOnClickListener(v -> openUrl(ExportBrand.GITHUB + "/issues"));
        view.findViewById(R.id.telegram_button).setOnClickListener(v -> openUrl(ExportBrand.TELEGRAM));
        view.findViewById(R.id.source_button).setOnClickListener(v -> openUrl(ExportBrand.GITHUB));

        TextView version = view.findViewById(R.id.about_version);
        version.setText(getString(R.string.help_version, readVersionName(requireContext())));

        return view;
    }

    // ---- FAQ ------------------------------------------------------------------------------

    private void buildFaq(LinearLayout container) {
        Context ctx = container.getContext();
        for (int i = 0; i < FAQ_STRINGS.length; i++) {
            if (i > 0) container.addView(hairline(ctx));
            CharSequence[] qa = splitFaq(getText(FAQ_STRINGS[i]));
            container.addView(faqRow(container, qa[0], qa[1]));
        }
    }

    private View faqRow(LinearLayout parentCard, CharSequence question, CharSequence answer) {
        Context ctx = parentCard.getContext();
        int pad = dp(16);
        int gutter = dp(20);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(ctx);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(56));
        header.setPadding(gutter, pad, dp(12), pad);
        header.setBackgroundResource(resolveResId(ctx, android.R.attr.selectableItemBackground));
        header.setClickable(true);
        header.setFocusable(true);

        TextView q = new TextView(ctx);
        q.setTextAppearance(resolveResId(ctx, com.google.android.material.R.attr.textAppearanceTitleMedium));
        q.setTextColor(MaterialColors.getColor(parentCard, com.google.android.material.R.attr.colorOnSurface));
        q.setText(question.toString());
        header.addView(q, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView chevron = new ImageView(ctx);
        chevron.setImageResource(R.drawable.ic_expand_more);
        chevron.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(parentCard,
                com.google.android.material.R.attr.colorOnSurfaceVariant)));
        chevron.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams chevronLp = new LinearLayout.LayoutParams(dp(24), dp(24));
        chevronLp.setMarginStart(dp(12));
        header.addView(chevron, chevronLp);

        TextView a = new TextView(ctx);
        a.setTextAppearance(resolveResId(ctx, com.google.android.material.R.attr.textAppearanceBodyMedium));
        a.setTextColor(MaterialColors.getColor(parentCard, com.google.android.material.R.attr.colorOnSurfaceVariant));
        a.setLineSpacing(0f, 1.15f);
        a.setText(answer);
        a.setPadding(gutter, 0, gutter, pad);
        a.setVisibility(View.GONE);

        row.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(a, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean hasAnswer = answer.length() > 0;
        if (!hasAnswer) chevron.setVisibility(View.INVISIBLE);
        header.setContentDescription(question + ". " + getString(R.string.help_faq_expand));

        header.setOnClickListener(v -> {
            if (!hasAnswer) return;
            boolean expand = a.getVisibility() != View.VISIBLE;
            // Animate the whole card so rows below slide smoothly.
            ViewGroup root = (ViewGroup) parentCard.getParent().getParent();
            TransitionManager.beginDelayedTransition(root != null ? root : parentCard,
                    new AutoTransition().setDuration(220));
            a.setVisibility(expand ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(expand ? 180f : 0f).setDuration(220).start();
            header.setContentDescription(question + ". " + getString(
                    expand ? R.string.help_faq_collapse : R.string.help_faq_expand));
        });
        return row;
    }

    /**
     * FAQ strings bundle question + answer ("<b>1. Q?</b>\n- A"). Locales vary: some use
     * &lt;br/&gt; in CDATA, some have no separator at all. Split robustly; fall back to the
     * whole text as the question.
     */
    static CharSequence[] splitFaq(CharSequence raw) {
        String text = raw.toString();
        if (text.contains("<")) {
            text = Html.fromHtml(text.replace("\n", "<br/>"), Html.FROM_HTML_MODE_LEGACY).toString();
        }
        text = text.trim();
        int cut = text.indexOf('\n');
        int answerStart = cut + 1;
        if (cut < 0) {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '?' || c == '؟' || c == '？') {
                    cut = i + 1;
                    answerStart = cut;
                    break;
                }
            }
        }
        if (cut <= 0 || cut >= text.length()) return new CharSequence[]{text, ""};
        String q = text.substring(0, cut).trim();
        String a = text.substring(answerStart).trim();
        if (a.startsWith("-")) a = a.substring(1).trim();
        return new CharSequence[]{q, a};
    }

    private View hairline(Context ctx) {
        View v = new View(ctx);
        v.setBackgroundColor(MaterialColors.getColor(ctx,
                com.google.android.material.R.attr.colorOutlineVariant, 0x22FFFFFF));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.setMarginStart(dp(20));
        lp.setMarginEnd(dp(20));
        v.setLayoutParams(lp);
        return v;
    }

    // ---- helpers --------------------------------------------------------------------------

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
        }
    }

    private static String readVersionName(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return info.versionName != null ? info.versionName : "?";
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private static int resolveResId(Context ctx, @AttrRes int attr) {
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(attr, tv, true);
        return tv.resourceId;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
