package ps.reso.instaeclipse.fragments;

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

    private AutoScroller contributorsScroller;
    private AutoScroller specialThanksScroller;


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
        if (contributorsScroller != null) contributorsScroller.start();
        if (specialThanksScroller != null) specialThanksScroller.start();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (contributorsScroller != null) contributorsScroller.stop();
        if (specialThanksScroller != null) specialThanksScroller.stop();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (contributorsScroller != null) contributorsScroller.stop();
        if (specialThanksScroller != null) specialThanksScroller.stop();
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
                new Contributor("particle-box", "https://github.com/particle-box", null, null),
                new Contributor("rsr", null, null, "https://t.me/rsr1337"),
                // Merged-PR authors that were missing from this list.
                new Contributor("akifakif32", "https://github.com/akifakif32", null, null),
                new Contributor("HackZy01", "https://github.com/HackZy01", null, null),
                new Contributor("Xiddoc", "https://github.com/Xiddoc", null, null),
                new Contributor("GlitchDaBest", "https://github.com/GlitchDaBest", null, null),
                new Contributor("NicKz101", "https://github.com/NicKz101", null, null),
                new Contributor("HcNguyen111", "https://github.com/HcNguyen111", null, null),
                new Contributor("Lxchoooo", "https://github.com/Lxchoooo", null, null),
                new Contributor("EscapeA", "https://github.com/EscapeA", null, null),
                new Contributor("Figim", "https://github.com/Figim", null, null),
                new Contributor("oka1da", "https://github.com/oka1da", null, null),
                new Contributor("uurcan7", "https://github.com/uurcan7", null, null),
                new Contributor("zarzet", "https://github.com/zarzet", null, null),
                new Contributor("xxOrdulu52xx", "https://github.com/xxOrdulu52xx", null, null),
                new Contributor("dpwbusr", "https://github.com/dpwbusr", null, null),
                new Contributor("d9k6s", "https://github.com/d9k6s", null, null)
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
                        contributorsScroller = new AutoScroller(contributorsScroll, halfWidth);
                        contributorsScroller.attachTouchPause();
                        if (isResumed()) contributorsScroller.start();
                    }
                });

        specialThanksContainer.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        specialThanksContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        int halfWidth = specialThanksContainer.getWidth() / 2;
                        specialThanksScroller = new AutoScroller(specialThanksScroll, halfWidth);
                        specialThanksScroller.attachTouchPause();
                        if (isResumed()) specialThanksScroller.start();
                    }
                });
    }

    /**
     * Continuous, seamless loop scroller for a duplicated-content HorizontalScrollView. It advances
     * the scroll by a fixed speed every animation frame, reading the LIVE scroll position and
     * wrapping it modulo one copy's width — so after a user drag it simply continues from wherever
     * the finger left off (the content is duplicated, so the wrap is invisible). Momentum fling is
     * disabled on the view (LoopScrollView), which is what removes the old snap-back / random jump.
     */
    private final class AutoScroller implements Runnable {
        private final HorizontalScrollView sv;
        private final int halfWidth;
        private final float stepPxPerMs;
        private boolean running;
        private boolean paused;
        private long lastFrame;

        AutoScroller(HorizontalScrollView sv, int halfWidth) {
            this.sv = sv;
            this.halfWidth = halfWidth;
            this.stepPxPerMs = SCROLL_SPEED_DP_PER_SEC * getResources().getDisplayMetrics().density / 1000f;
        }

        void start() {
            if (running || halfWidth <= 0) return;
            running = true;
            lastFrame = 0;
            sv.postOnAnimation(this);
        }

        void stop() {
            running = false;
            sv.removeCallbacks(this);
        }

        @Override
        public void run() {
            if (!running) return;
            long now = android.view.animation.AnimationUtils.currentAnimationTimeMillis();
            if (lastFrame == 0) lastFrame = now;
            long dt = now - lastFrame;
            lastFrame = now;
            if (!paused && halfWidth > 0) {
                int x = sv.getScrollX() + Math.round(stepPxPerMs * dt);
                x %= halfWidth;
                if (x < 0) x += halfWidth;
                sv.scrollTo(x, 0);
            }
            sv.postOnAnimation(this);
        }

        @SuppressLint("ClickableViewAccessibility")
        void attachTouchPause() {
            sv.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                    case MotionEvent.ACTION_MOVE:
                        paused = true;      // let the user drag freely
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        paused = false;     // resume from the live position (no fling to fight)
                        lastFrame = 0;
                        break;
                }
                return false; // keep normal drag handling
            });
        }
    }

    private void inflateCards(List<Contributor> list, LinearLayout container) {
        for (Contributor c : list) {
            View v = LayoutInflater.from(getContext()).inflate(R.layout.contributor_card, container, false);
            setupContributorCard(v, c);
            container.addView(v);
        }
    }

    // A small palette of pleasant, saturated hues for the monogram avatars — each contributor gets
    // a stable colour derived from their name so the row reads as a set of distinct people.
    private static final int[] AVATAR_COLORS = {
            0xFF5E5CE6, 0xFFFF375F, 0xFFFF9F0A, 0xFF30D158, 0xFF64D2FF,
            0xFFBF5AF2, 0xFFFF9500, 0xFF32D74B, 0xFF0A84FF, 0xFFFFD60A
    };

    private void setupContributorCard(View view, Contributor contributor) {
        TextView nameTextView = view.findViewById(R.id.contributor_name);
        nameTextView.setText(contributor.name());

        // Monogram avatar: first letter on a per-name coloured circle.
        TextView avatar = view.findViewById(R.id.contributor_avatar);
        if (avatar != null) {
            String name = contributor.name() == null ? "" : contributor.name().trim();
            String initial = "?";
            for (int i = 0; i < name.length(); i++) {
                char ch = name.charAt(i);
                if (Character.isLetterOrDigit(ch)) { initial = String.valueOf(Character.toUpperCase(ch)); break; }
            }
            int color = AVATAR_COLORS[Math.abs(name.hashCode()) % AVATAR_COLORS.length];
            android.graphics.drawable.GradientDrawable circle = new android.graphics.drawable.GradientDrawable();
            circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            circle.setColor(color);
            avatar.setText(initial);
            avatar.setBackground(circle);
        }

        // Overlay the GitHub profile photo when the contributor has a GitHub link — github.com/<user>.png
        // resolves to their avatar. On no-link/failure the monogram above stays visible.
        ImageView avatarImg = view.findViewById(R.id.contributor_avatar_img);
        if (avatarImg != null) {
            avatarImg.setVisibility(View.GONE); // reset (fresh inflate, but be safe)
            String gh = contributor.githubUrl();
            if (gh != null && !gh.trim().isEmpty()) {
                String u = gh.trim();
                if (u.endsWith("/")) u = u.substring(0, u.length() - 1);
                ps.reso.instaeclipse.utils.ui.AvatarLoader.loadCircular(u + ".png?size=144", avatarImg);
            }
        }

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
