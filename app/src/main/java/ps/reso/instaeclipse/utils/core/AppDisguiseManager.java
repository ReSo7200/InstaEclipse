package ps.reso.instaeclipse.utils.core;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import ps.reso.instaeclipse.BuildConfig;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

public class AppDisguiseManager {

    public static final String DEFAULT = "default";
    public static final String CALCULATOR = "calculator";
    public static final String NOTES = "notes";
    public static final String FILE_MANAGER = "file_manager";
    public static final String CALENDAR = "calendar";
    public static final String WEATHER = "weather";

    public static void applyDisguise(Context context, String disguiseType) {
        if (disguiseType == null) disguiseType = DEFAULT;
        FeatureFlags.appDisguise = disguiseType;
        SettingsManager.saveAllFlags();

        PackageManager pm = context.getPackageManager();
        String pkg = BuildConfig.APPLICATION_ID;

        setAliasState(pm, pkg, ".MainActivityDefault", DEFAULT.equals(disguiseType));
        setAliasState(pm, pkg, ".MainActivityCalculator", CALCULATOR.equals(disguiseType));
        setAliasState(pm, pkg, ".MainActivityNotes", NOTES.equals(disguiseType));
        setAliasState(pm, pkg, ".MainActivityFileManager", FILE_MANAGER.equals(disguiseType));
        setAliasState(pm, pkg, ".MainActivityCalendar", CALENDAR.equals(disguiseType));
        setAliasState(pm, pkg, ".MainActivityWeather", WEATHER.equals(disguiseType));
    }

    private static void setAliasState(PackageManager pm, String pkg, String aliasName, boolean enable) {
        ComponentName componentName = new ComponentName(pkg, pkg + aliasName);
        int state = enable ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP);
    }
}
