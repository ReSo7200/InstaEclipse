package ps.reso.instaeclipse.fragments;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import ps.reso.instaeclipse.utils.Preferences;
import ps.reso.instaeclipse.R;

public class DistractionFreeFragment extends Fragment {
    private static final String ENABLE_ALL_KEY = "enableAllDistractionFree";
    private static final String STORIES_KEY = "disableStories";
    private static final String FEED_KEY = "disableFeed";
    private static final String REELS_KEY = "disableReels";
    private static final String EXPLORE_KEY = "disableExplore";

    private Switch enableAllToggle;
    private Switch storiesToggle;
    private Switch feedToggle;
    private Switch reelsToggle;
    private Switch exploreToggle;

    private SharedPreferences preferences;
    private SharedPreferences.Editor editor;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.distraction_free_fragment, container, false);

        preferences = Preferences.getPrefs();
        editor = preferences.edit();

        // Initialize toggles
        enableAllToggle = view.findViewById(R.id.toggle_all);
        storiesToggle = view.findViewById(R.id.stories_toggle);
        feedToggle = view.findViewById(R.id.feed_toggle);
        reelsToggle = view.findViewById(R.id.reels_toggle);
        exploreToggle = view.findViewById(R.id.explore_toggle);

        // Load saved states
        loadToggleStates();

        // Handle enable/disable all toggle
        enableAllToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveToggleState(ENABLE_ALL_KEY, isChecked);
            storiesToggle.setChecked(isChecked);
            feedToggle.setChecked(isChecked);
            reelsToggle.setChecked(isChecked);
            exploreToggle.setChecked(isChecked);
        });

        // Individual toggles
        storiesToggle.setOnCheckedChangeListener((buttonView, isChecked) -> saveToggleState(STORIES_KEY, isChecked));
        feedToggle.setOnCheckedChangeListener((buttonView, isChecked) -> saveToggleState(FEED_KEY, isChecked));
        reelsToggle.setOnCheckedChangeListener((buttonView, isChecked) -> saveToggleState(REELS_KEY, isChecked));
        exploreToggle.setOnCheckedChangeListener((buttonView, isChecked) -> saveToggleState(EXPLORE_KEY, isChecked));

        return view;
    }

    private void loadToggleStates() {
        enableAllToggle.setChecked(preferences.getBoolean(ENABLE_ALL_KEY, false));
        storiesToggle.setChecked(preferences.getBoolean(STORIES_KEY, false));
        feedToggle.setChecked(preferences.getBoolean(FEED_KEY, false));
        reelsToggle.setChecked(preferences.getBoolean(REELS_KEY, false));
        exploreToggle.setChecked(preferences.getBoolean(EXPLORE_KEY, false));
    }

    private void saveToggleState(String key, boolean value) {
        editor.putBoolean(key, value);
        editor.apply();
    }
}