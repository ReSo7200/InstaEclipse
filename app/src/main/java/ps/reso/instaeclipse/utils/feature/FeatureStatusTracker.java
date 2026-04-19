package ps.reso.instaeclipse.utils.feature;

import android.content.Context;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import ps.reso.instaeclipse.utils.i18n.I18n;

public class FeatureStatusTracker {
    private static final Map<String, Boolean> features = Collections.synchronizedMap(new HashMap<>());
    private static final Map<String, Integer> labels   = Collections.synchronizedMap(new HashMap<>());

    public static void setEnabled(String name, int labelResId) {
        features.put(name, false);
        labels.put(name, labelResId);
    }

    public static void setDisabled(String name) {
        features.remove(name);
        labels.remove(name);
    }

    public static void setHooked(String name) {
        if (features.containsKey(name)) {
            features.put(name, true);
        }
    }

    public static String getLabel(Context ctx, String key) {
        Integer resId = labels.get(key);
        return resId != null ? I18n.t(ctx, resId) : key;
    }

    public static Map<String, Boolean> getStatus() {
        return features;
    }

    public static boolean hasEnabledFeatures() {
        return !features.isEmpty();
    }
}
