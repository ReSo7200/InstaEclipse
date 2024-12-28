package ps.reso.instaeclipse.fragments;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;

import java.io.DataOutputStream;
import java.util.Arrays;
import java.util.List;

import ps.reso.instaeclipse.utils.Contributor;
import ps.reso.instaeclipse.MainActivity;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.Utils;

public class HomeFragment extends Fragment {

    private TextView moduleStatus;
    private TextView moduleSubtext;
    private MaterialButton restartInstagramButton;

    private final boolean hasRootAccess = MainActivity.hasRootAccess;

    private boolean isWaitingForForceStop = false;

    private TextView instagramStatusText;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_home, container, false);


        // Initialize views
        moduleStatus = view.findViewById(R.id.module_status);
        moduleSubtext = view.findViewById(R.id.module_subtext);
        restartInstagramButton = view.findViewById(R.id.restart_instagram_button);

        // Check module and root status
        checkModuleStatus();

        // Find the TextView to display Instagram status
        instagramStatusText = view.findViewById(R.id.instagram_status_text);

        // Check Instagram installation and version
        checkInstagramStatus();

        // Restart Instagram Button Logic
        restartInstagramButton.setOnClickListener(v -> {
            if (hasRootAccess) {
                restartInstagramWithRoot();
            } else {
                restartInstagramNonRoot();
            }
        });

        // Setup Contributors and Special Thanks
        setupContributorsAndSpecialThanks(view);

        return view;
    }

    @SuppressLint("SetTextI18n")
    private void checkModuleStatus() {
        // Replace these checks with actual logic
        boolean isModuleEnabled = MainActivity.isModuleActive();

        if (!isModuleEnabled) {
            moduleStatus.setText("Module Status: Disabled");
            moduleStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
            moduleSubtext.setText("Please enable the module in Xposed Installer.");
            restartInstagramButton.setEnabled(false);
        } else if (!hasRootAccess) {
            moduleStatus.setText("Module Status: Enabled (No Root Access)");
            moduleStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_light));
            moduleSubtext.setText("Root access is required for auto-restart. Manual restart required.");
            restartInstagramButton.setEnabled(true);
        } else {
            moduleStatus.setText("Module Status: Enabled");
            moduleStatus.setTextColor(getResources().getColor(android.R.color.holo_green_light));
            moduleSubtext.setText("The module is active and working.");
            restartInstagramButton.setEnabled(true);
        }
    }

    private void restartInstagramWithRoot() {
        try {
            Process su = Runtime.getRuntime().exec("su");
            DataOutputStream os = new DataOutputStream(su.getOutputStream());
            os.writeBytes("am force-stop " + Utils.IG_PACKAGE_NAME + "\n");
            os.flush();
            os.writeBytes("am start -n " + Utils.IG_PACKAGE_NAME + "/com.instagram.mainactivity.InstagramMainActivity\n");
            os.flush();
            Toast.makeText(getActivity(), "Restarting Instagram...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(getActivity(), "Failed to restart Instagram", Toast.LENGTH_SHORT).show();
        }
    }

    private void restartInstagramNonRoot() {
        String instagramPackage = "com.instagram.android";

        if (!isWaitingForForceStop) {
            // Step 1: Redirect the user to the app settings
            Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + instagramPackage));
            startActivity(intent);
            isWaitingForForceStop = true;

            Toast.makeText(requireContext(), "Please force stop Instagram and open it.", Toast.LENGTH_SHORT).show();
        } else {
            // Step 2: Restart Instagram only if it's stopped
            Intent launchIntent = requireContext().getPackageManager().getLaunchIntentForPackage(instagramPackage);
            if (launchIntent != null) {
                startActivity(launchIntent);
                Toast.makeText(requireContext(), "Instagram restarted.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(requireContext(), "Instagram not found.", Toast.LENGTH_SHORT).show();
            }

            isWaitingForForceStop = false;
        }
    }

    @SuppressLint("SetTextI18n")
    private void checkInstagramStatus() {
        String instagramPackage = Utils.IG_PACKAGE_NAME; // IG package name
        PackageManager pm = requireContext().getPackageManager(); // Get PackageManager

        try {
            PackageInfo packageInfo = pm.getPackageInfo(instagramPackage, 0);
            String versionName = packageInfo.versionName;

            instagramStatusText.setText("Instagram Installed - Version: " + versionName);
            instagramStatusText.setTextColor(getResources().getColor(R.color.green));
        } catch (PackageManager.NameNotFoundException e) {
            instagramStatusText.setText("Instagram is not installed.");
            instagramStatusText.setTextColor(getResources().getColor(R.color.red));
        } catch (Exception e) {
            instagramStatusText.setText("Error checking Instagram status.");
            instagramStatusText.setTextColor(getResources().getColor(R.color.red));
        }
    }


    private void setupContributorsAndSpecialThanks(View rootView) {
        LinearLayout contributorsContainer = rootView.findViewById(R.id.contributors_container);
        LinearLayout specialThanksContainer = rootView.findViewById(R.id.special_thanks_container);

        List<Contributor> contributors = Arrays.asList(
                new Contributor("ReSo7200", "https://github.com/ReSo7200", "https://linkedin.com/in/abdalhaleem-altamimi", null)
        );

        List<Contributor> specialThanks = Arrays.asList(
                new Contributor("Amàzing World", null, null, null),
                new Contributor("Bluepapilte", null, null, "https://t.me/instasmashrepo")
        );

        for (Contributor contributor : contributors) {
            View contributorView = LayoutInflater.from(getContext()).inflate(R.layout.contributor_card, contributorsContainer, false);
            setupContributorCard(contributorView, contributor);
            contributorsContainer.addView(contributorView);
        }

        for (Contributor thanks : specialThanks) {
            View thanksView = LayoutInflater.from(getContext()).inflate(R.layout.contributor_card, specialThanksContainer, false);
            setupContributorCard(thanksView, thanks);
            specialThanksContainer.addView(thanksView);
        }
    }

    private void setupContributorCard(View view, Contributor contributor) {
        TextView nameTextView = view.findViewById(R.id.contributor_name);
        nameTextView.setText(contributor.getName());

        ImageButton githubButton = view.findViewById(R.id.github_button);
        if (contributor.getGithubUrl() != null) {
            githubButton.setVisibility(View.VISIBLE);
            githubButton.setOnClickListener(v -> openLink(contributor.getGithubUrl()));
        } else {
            githubButton.setVisibility(View.GONE);
        }

        ImageButton linkedinButton = view.findViewById(R.id.linkedin_button);
        if (contributor.getLinkedinUrl() != null) {
            linkedinButton.setVisibility(View.VISIBLE);
            linkedinButton.setOnClickListener(v -> openLink(contributor.getLinkedinUrl()));
        } else {
            linkedinButton.setVisibility(View.GONE);
        }

        ImageButton telegramButton = view.findViewById(R.id.telegram_button);
        if (contributor.getTelegramUrl() != null) {
            telegramButton.setVisibility(View.VISIBLE);
            telegramButton.setOnClickListener(v -> openLink(contributor.getTelegramUrl()));
        } else {
            telegramButton.setVisibility(View.GONE);
        }
    }

    private void openLink(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }


}
