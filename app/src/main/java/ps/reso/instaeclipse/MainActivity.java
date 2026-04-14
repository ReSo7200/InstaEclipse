package ps.reso.instaeclipse;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.color.DynamicColors;

import ps.reso.instaeclipse.fragments.FeaturesFragment;
import ps.reso.instaeclipse.fragments.HelpFragment;
import ps.reso.instaeclipse.fragments.HomeFragment;
import ps.reso.instaeclipse.utils.version.VersionCheckUtility;

public class MainActivity extends AppCompatActivity {

    @SuppressLint("NonConstantResourceId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);

        super.onCreate(savedInstanceState);
        VersionCheckUtility.checkForUpdates(this);

        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.top_app_bar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayShowTitleEnabled(false);
        }

        BottomNavigationView bottomNavigation = findViewById(R.id.bottom_navigation);
        FrameLayout fragmentContainer = findViewById(R.id.fragment_container);

        // On targetSdk 35+, edge-to-edge is enforced. The BottomNavigationView absorbs the
        // system gesture inset via fitsSystemWindows, making its actual height larger than the
        // fixed 82dp we had in XML. Sync the fragment container's bottom padding to match the
        // nav bar's real height after each layout pass.
        bottomNavigation.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int navHeight = v.getHeight();
            if (fragmentContainer.getPaddingBottom() != navHeight) {
                fragmentContainer.setPadding(0, 0, 0, navHeight);
            }
        });

        // Load the HomeFragment by default
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, new HomeFragment())
                    .commit();
        }

        // Select Home by default in the navbar
        bottomNavigation.setSelectedItemId(R.id.nav_home);

        // Handle bottom navigation item clicks
        bottomNavigation.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;

            if (item.getItemId() == R.id.nav_home) {
                selectedFragment = new HomeFragment();
            } else if (item.getItemId() == R.id.nav_features) {
                selectedFragment = new FeaturesFragment();
            } else if (item.getItemId() == R.id.nav_help) {
                selectedFragment = new HelpFragment();
            }

            if (selectedFragment != null) {
                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragment_container, selectedFragment)
                        .commit();
            }
            return true;
        });
    }

    @Override
    public void onBackPressed() {
        if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            getSupportFragmentManager().popBackStack();
        } else {
            super.onBackPressed();
        }
    }
}
