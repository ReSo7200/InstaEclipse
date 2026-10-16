package ps.reso.instaeclipse.utils.feature;

import java.lang.reflect.Field;

import ps.reso.instaeclipse.R;

/**
 * The "Extras" section: on/off features listed in one place so both settings UIs, settings
 * save/load, backup and the feature status tracker pick them up without per-feature wiring.
 * Each entry's flag is a {@code boolean} field of {@link FeatureFlags} with the same name, which
 * is also its pref key, backup key and {@link FeatureStatusTracker} key.
 */
public final class ExtrasFeatures {

    public static final class Entry {
        public final String flag;
        public final int label;
        public final int icon;

        Entry(String flag, int label, int icon) {
            this.flag = flag;
            this.label = label;
            this.icon = icon;
        }
    }

    public static final Entry[] ALL = {
            new Entry("disableSwipeToCamera", R.string.ig_dialog_extras_no_swipe_camera, R.drawable.ic_camera),
            new Entry("reelsAutoScroll", R.string.ig_dialog_extras_reels_auto_scroll, R.drawable.ic_movie),
            new Entry("reelsLockScroll", R.string.ig_dialog_extras_reels_lock_scroll, R.drawable.ic_movie),
            new Entry("unlimitedAccounts", R.string.ig_dialog_extras_unlimited_accounts, R.drawable.ic_settings_gear),
            new Entry("airplaneMode", R.string.ig_dialog_extras_airplane_mode, R.drawable.ic_eye_off),
            new Entry("highResolutionImages", R.string.ig_dialog_extras_high_res_images, R.drawable.ic_search),
            new Entry("hideOnboardingPrompts", R.string.ig_dialog_extras_hide_onboarding, R.drawable.ic_block),
            new Entry("fixNotificationRegistration", R.string.ig_dialog_extras_fix_notifications, R.drawable.ic_notification),
            new Entry("hideStoriesTray", R.string.ig_dialog_extras_hide_stories_tray, R.drawable.ic_story_ring),
    };

    private ExtrasFeatures() {}

    public static String[] flags() {
        String[] out = new String[ALL.length];
        for (int i = 0; i < ALL.length; i++) out[i] = ALL[i].flag;
        return out;
    }

    /** The entry for {@code flag}, or null if it isn't an Extras feature. */
    public static Entry entry(String flag) {
        for (Entry e : ALL) if (e.flag.equals(flag)) return e;
        return null;
    }

    public static boolean get(String flag) {
        try {
            return field(flag).getBoolean(null);
        } catch (Throwable t) {
            return false;
        }
    }

    public static void set(String flag, boolean value) {
        try {
            field(flag).setBoolean(null, value);
        } catch (Throwable ignored) {}
    }

    private static Field field(String flag) throws NoSuchFieldException {
        return FeatureFlags.class.getField(flag);
    }
}
