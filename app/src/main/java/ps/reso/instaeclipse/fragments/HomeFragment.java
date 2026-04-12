package ps.reso.instaeclipse.fragments;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.core.Contributor;

public class HomeFragment extends Fragment {

    // px/s scroll speed — feels like a gentle conveyor belt
    private static final float SCROLL_SPEED_DP_PER_SEC = 48f;

    private MaterialButton launchInstagramButton;
    private MaterialCardView instagramStatusCard;
    private TextView instagramStatusText;
    private TextView instagramVariantText;
    private MaterialButton instagramMultiButton;
    private ImageView instagramLogo, instagramInfoIcon;

    private String activePackage;
    private List<String> installedPackages;

    private ValueAnimator contributorsAnimator;
    private ValueAnimator specialThanksAnimator;


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);

        launchInstagramButton = view.findViewById(R.id.launch_instagram_button);
        MaterialButton downloadButton = view.findViewById(R.id.download_instagram_button);

        instagramStatusCard = view.findViewById(R.id.instagram_status_card);
        instagramStatusText = view.findViewById(R.id.instagram_status_text);
        instagramVariantText = view.findViewById(R.id.instagram_variant_text);
        instagramMultiButton = view.findViewById(R.id.instagram_multi_button);
        instagramLogo = view.findViewById(R.id.instagram_logo);
        instagramInfoIcon = view.findViewById(R.id.instagram_info_icon);

        checkInstagramStatus();

        downloadButton.setOnClickListener(v -> {
            String url = "https://www.apkmirror.com/uploads/?appcategory=instagram-instagram";
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        });

        setupContributorsAndSpecialThanks(view);

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        resumeAnimators();
    }

    @Override
    public void onPause() {
        super.onPause();
        pauseAnimators();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (contributorsAnimator != null) contributorsAnimator.cancel();
        if (specialThanksAnimator != null) specialThanksAnimator.cancel();
    }

    @SuppressLint("SetTextI18n")
    private void checkInstagramStatus() {
        PackageManager pm = requireContext().getPackageManager();

        installedPackages = new ArrayList<>();
        for (String pkg : CommonUtils.SUPPORTED_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                installedPackages.add(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }

        if (installedPackages.isEmpty()) {
            instagramStatusText.setText(getString(R.string.not_installed_instagram));
            instagramStatusText.setTypeface(null, android.graphics.Typeface.BOLD);
            instagramStatusCard.setCardBackgroundColor(getResources().getColor(R.color.dark_red));
            instagramLogo.setImageResource(R.drawable.ic_cancel);
            launchInstagramButton.setEnabled(false);
            return;
        }

        // Prefer the official package; fall back to first found mod
        activePackage = installedPackages.contains(CommonUtils.IG_PACKAGE_NAME)
                ? CommonUtils.IG_PACKAGE_NAME
                : installedPackages.get(0);

        instagramStatusCard.setCardBackgroundColor(getResources().getColor(R.color.green));
        instagramLogo.setImageResource(R.drawable.ic_instagram_logo);
        instagramVariantText.setVisibility(View.VISIBLE);

        if (installedPackages.size() > 1) {
            instagramMultiButton.setVisibility(View.VISIBLE);
            instagramMultiButton.setOnClickListener(v -> showDetectedVersionsDialog(pm));
        } else {
            instagramMultiButton.setVisibility(View.GONE);
        }

        bindPackageActions(pm, activePackage);
    }

    @SuppressLint("SetTextI18n")
    private void bindPackageActions(PackageManager pm, String pkg) {
        activePackage = pkg;

        try {
            String versionName = pm.getPackageInfo(pkg, 0).versionName;
            String installedText = getString(R.string.installed_instagram_version);
            String versionText = getString(R.string.instagram_version) + ": " + versionName;
            String fullText = installedText + "\n" + versionText;

            SpannableString sp = new SpannableString(fullText);
            sp.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, installedText.length(), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sp.setSpan(new android.text.style.RelativeSizeSpan(0.85f), installedText.length() + 1, fullText.length(), 0);
            instagramStatusText.setText(sp);
        } catch (PackageManager.NameNotFoundException e) {
            instagramStatusText.setText(getString(R.string.installed_instagram_version));
        }

        instagramVariantText.setText(CommonUtils.getVariantLabel(pkg));

        instagramInfoIcon.setOnClickListener(v -> {
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + pkg));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        });

        launchInstagramButton.setOnClickListener(v -> {
            Intent launchIntent = pm.getLaunchIntentForPackage(pkg);
            if (launchIntent != null) {
                startActivity(launchIntent);
            } else {
                Toast.makeText(getActivity(), getString(R.string.not_installed_instagram), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupContributorsAndSpecialThanks(View rootView) {
        HorizontalScrollView contributorsScroll = rootView.findViewById(R.id.contributors_scroll);
        HorizontalScrollView specialThanksScroll = rootView.findViewById(R.id.special_thanks_scroll);
        LinearLayout contributorsContainer = rootView.findViewById(R.id.contributors_container);
        LinearLayout specialThanksContainer = rootView.findViewById(R.id.special_thanks_container);

        List<Contributor> contributors = Arrays.asList(
                new Contributor("ReSo7200", "https://github.com/ReSo7200", "https://linkedin.com/in/abdalhaleem-altamimi", null),
                new Contributor("swakwork", "https://github.com/swakwork", null, null),
                new Contributor("isma3iloiso", "https://github.com/isma3iloiso", null, null),
                new Contributor("Placeholder6", "https://github.com/Placeholder6", null, null),
                new Contributor("frknkrc44", "https://github.com/frknkrc44", null, null),
                new Contributor("BrianML", "https://github.com/brianml31", null, "https://t.me/instamoon_channel"),
                new Contributor("silvzr", "https://github.com/silvzr", null, null),
                new Contributor("oct", "https://github.com/oct888", null, null),
                new Contributor("HalfManBear", "https://github.com/halfmanbear", null, null),
                new Contributor("ar5to", "https://github.com/ar5to", null, "https://t.me/ar5to"),
                new Contributor("particle-box", "https://github.com/particle-box", null, null)
        );

        List<Contributor> specialThanks = Arrays.asList(
                new Contributor("xHookman", "https://github.com/xHookman", null, null),
                new Contributor("Bluepapilte", null, null, "https://t.me/instasmashrepo"),
                new Contributor("BdrcnAYYDIN", null, null, "https://t.me/BdrcnAYYDIN"),
                new Contributor("Amàzing World", null, null, null)
        );

        // Inflate each list twice so the loop restarts seamlessly
        inflateCards(contributors, contributorsContainer);
        inflateCards(contributors, contributorsContainer);
        inflateCards(specialThanks, specialThanksContainer);
        inflateCards(specialThanks, specialThanksContainer);

        // Start infinite scroll once layout is complete
        contributorsContainer.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        contributorsContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        int halfWidth = contributorsContainer.getWidth() / 2;
                        contributorsAnimator = buildAnimator(contributorsScroll, halfWidth);
                        if (isResumed()) contributorsAnimator.start();
                        hookTouchPause(contributorsScroll, contributorsAnimator, halfWidth);
                    }
                });

        specialThanksContainer.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        specialThanksContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        int halfWidth = specialThanksContainer.getWidth() / 2;
                        specialThanksAnimator = buildAnimator(specialThanksScroll, halfWidth);
                        if (isResumed()) specialThanksAnimator.start();
                        hookTouchPause(specialThanksScroll, specialThanksAnimator, halfWidth);
                    }
                });
    }

    private void inflateCards(List<Contributor> list, LinearLayout container) {
        for (Contributor c : list) {
            View v = LayoutInflater.from(getContext()).inflate(R.layout.contributor_card, container, false);
            setupContributorCard(v, c);
            container.addView(v);
        }
    }

    /**
     * Builds a ValueAnimator that scrolls from 0 to halfWidth (one full copy of the items)
     * at a constant speed, restarting instantly — creating a seamless infinite loop.
     */
    private ValueAnimator buildAnimator(HorizontalScrollView scrollView, int halfWidth) {
        float density = getResources().getDisplayMetrics().density;
        long durationMs = (long) (halfWidth / (SCROLL_SPEED_DP_PER_SEC * density) * 1000f);

        ValueAnimator anim = ValueAnimator.ofInt(0, halfWidth);
        anim.setDuration(Math.max(durationMs, 1000));
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setRepeatMode(ValueAnimator.RESTART);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(a -> scrollView.scrollTo((int) a.getAnimatedValue(), 0));
        return anim;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void hookTouchPause(HorizontalScrollView scrollView, ValueAnimator animator, int halfWidth) {
        scrollView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                case MotionEvent.ACTION_MOVE:
                    if (animator != null && animator.isRunning()) animator.pause();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (animator != null && halfWidth > 0) {
                        // Sync animator position to where the user left the scroll,
                        // then resume — so it continues from the dropped position.
                        int currentX = scrollView.getScrollX() % halfWidth;
                        animator.setCurrentFraction(currentX / (float) halfWidth);
                        if (animator.isPaused()) animator.resume();
                        else if (!animator.isRunning()) animator.start();
                    }
                    break;
            }
            return false;
        });
    }

    private void resumeAnimators() {
        resumeAnimator(contributorsAnimator);
        resumeAnimator(specialThanksAnimator);
    }

    private void resumeAnimator(ValueAnimator anim) {
        if (anim == null) return;
        if (anim.isPaused()) anim.resume();
        else if (!anim.isRunning()) anim.start();
    }

    private void pauseAnimators() {
        if (contributorsAnimator != null && contributorsAnimator.isRunning()) contributorsAnimator.pause();
        if (specialThanksAnimator != null && specialThanksAnimator.isRunning()) specialThanksAnimator.pause();
    }

    private void setupContributorCard(View view, Contributor contributor) {
        TextView nameTextView = view.findViewById(R.id.contributor_name);
        nameTextView.setText(contributor.name());

        ImageButton githubButton = view.findViewById(R.id.github_button);
        if (contributor.githubUrl() != null) {
            githubButton.setVisibility(View.VISIBLE);
            githubButton.setOnClickListener(v -> openLink(contributor.githubUrl()));
        } else {
            githubButton.setVisibility(View.GONE);
        }

        ImageButton linkedinButton = view.findViewById(R.id.linkedin_button);
        if (contributor.linkedinUrl() != null) {
            linkedinButton.setVisibility(View.VISIBLE);
            linkedinButton.setOnClickListener(v -> openLink(contributor.linkedinUrl()));
        } else {
            linkedinButton.setVisibility(View.GONE);
        }

        ImageButton telegramButton = view.findViewById(R.id.telegram_button);
        if (contributor.telegramUrl() != null) {
            telegramButton.setVisibility(View.VISIBLE);
            telegramButton.setOnClickListener(v -> openLink(contributor.telegramUrl()));
        } else {
            telegramButton.setVisibility(View.GONE);
        }
    }

    private void showDetectedVersionsDialog(PackageManager pm) {
        String[] labels = new String[installedPackages.size()];
        for (int i = 0; i < installedPackages.size(); i++) {
            String pkg = installedPackages.get(i);
            String version = "?";
            try { version = pm.getPackageInfo(pkg, 0).versionName; }
            catch (PackageManager.NameNotFoundException ignored) { }
            labels[i] = CommonUtils.getVariantLabel(pkg) + "  —  v" + version;
        }

        int currentIndex = installedPackages.indexOf(activePackage);
        final int[] selectedIndex = {currentIndex};

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("Detected versions")
                .setSingleChoiceItems(labels, currentIndex, (dialog, which) -> selectedIndex[0] = which)
                .setPositiveButton("Use this", (dialog, which) -> {
                    String chosen = installedPackages.get(selectedIndex[0]);
                    if (!chosen.equals(activePackage)) {
                        bindPackageActions(pm, chosen);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openLink(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }
}
