package ps.reso.instaeclipse.mods.location;

import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import ps.reso.instaeclipse.utils.core.CommonUtils;

/**
 * The spoofed location and its recently used history, shared by the map picker (companion app),
 * the companion's Location menu and the in-Instagram sheet.
 *
 * <p>History is stored as a small JSON array ({@code spoofRecent}) next to {@code spoofLat},
 * {@code spoofLng} and {@code spoofLabel}, so it rides the existing string-pref sync to
 * Instagram's process.
 */
public final class LocationPresets {

    public static final int MAX_RECENT = 6;
    /** Picks closer than this to an existing entry replace it instead of adding a new one. */
    static final double SAME_PLACE_METERS = 75;

    public static final class Preset {
        public final double lat;
        public final double lng;
        public final String label;

        public Preset(double lat, double lng, String label) {
            this.lat = lat;
            this.lng = lng;
            this.label = label == null ? "" : label;
        }

        /** Label when known, otherwise the rounded coordinates. */
        public String title() {
            return label.isEmpty() ? coords(lat, lng) : label;
        }
    }

    private LocationPresets() {}

    public static String coords(double lat, double lng) {
        return String.format(Locale.US, "%.5f, %.5f", lat, lng);
    }

    public static List<Preset> parse(String json) {
        List<Preset> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length() && out.size() < MAX_RECENT; i++) {
                JSONObject o = arr.getJSONObject(i);
                double lat = o.getDouble("lat"), lng = o.getDouble("lng");
                if (valid(lat, lng)) out.add(new Preset(lat, lng, o.optString("label", "")));
            }
        } catch (Throwable ignored) {
            // corrupt history: start over rather than break the picker
        }
        return out;
    }

    public static String serialize(List<Preset> presets) {
        JSONArray arr = new JSONArray();
        try {
            for (Preset p : presets) {
                arr.put(new JSONObject().put("lat", p.lat).put("lng", p.lng).put("label", p.label));
            }
        } catch (Throwable ignored) {
            // JSONObject.put only throws for NaN/inf, which valid() excludes
        }
        return arr.toString();
    }

    /** History with {@code picked} moved to the front, near-duplicates dropped, capped. */
    public static List<Preset> remember(List<Preset> recent, Preset picked) {
        List<Preset> out = new ArrayList<>();
        out.add(picked);
        for (Preset p : recent) {
            if (out.size() >= MAX_RECENT) break;
            if (distanceMeters(p.lat, p.lng, picked.lat, picked.lng) > SAME_PLACE_METERS) out.add(p);
        }
        return out;
    }

    public static boolean valid(double lat, double lng) {
        return !Double.isNaN(lat) && !Double.isNaN(lng) && lat >= -90 && lat <= 90 && lng >= -180 && lng <= 180
                && !(lat == 0.0 && lng == 0.0);
    }

    /** "41.0082, 28.9784" (also ";" or space separated) → {lat, lng}; null when not coordinates. */
    public static double[] parseCoordinates(String text) {
        if (text == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\s*(-?\\d{1,2}(?:\\.\\d+)?)\\s*[,; ]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$")
                .matcher(text);
        if (!m.matches()) return null;
        double lat = Double.parseDouble(m.group(1)), lng = Double.parseDouble(m.group(2));
        return valid(lat, lng) ? new double[]{lat, lng} : null;
    }

    /** Haversine distance. */
    public static double distanceMeters(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1), dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    /**
     * Companion-side save: writes the location and updated history to both pref stores and
     * pushes them to Instagram's process, where the spoof picks them up without a restart.
     */
    public static void applyFromCompanion(Context context, Preset picked) {
        android.content.SharedPreferences cache = CommonUtils.companionCache(context);
        String recent = serialize(remember(parse(cache.getString("spoofRecent", "")), picked));
        String[][] values = {
                {"spoofLat", String.valueOf(picked.lat)},
                {"spoofLng", String.valueOf(picked.lng)},
                {"spoofLabel", picked.label},
                {"spoofRecent", recent},
        };
        for (String store : new String[]{"instaeclipse_prefs", "instaeclipse_cache"}) {
            android.content.SharedPreferences.Editor e = ("instaeclipse_cache".equals(store)
                    ? CommonUtils.companionCache(context) : context.getSharedPreferences(store, Context.MODE_PRIVATE)).edit();
            for (String[] kv : values) e.putString(kv[0], kv[1]);
            e.apply();
        }
        for (String[] kv : values) {
            Intent i = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
            i.putExtra("key", kv[0]);
            i.putExtra("value", kv[1]);
            CommonUtils.broadcastToInstagram(context, i);
        }
    }

    /** Max distance of a spoofed fix from the picked point. */
    static final double JITTER_METERS = 4.0;

    /** Uniform point within {@link #JITTER_METERS} of (lat, lng); u1/u2 are uniform [0, 1). */
    static double[] jitter(double lat, double lng, double u1, double u2) {
        double r = JITTER_METERS * Math.sqrt(u1);
        double theta = 2 * Math.PI * u2;
        double dLat = (r * Math.cos(theta)) / 111_320.0;
        double dLng = (r * Math.sin(theta)) / (111_320.0 * Math.max(0.01, Math.cos(Math.toRadians(lat))));
        return new double[]{lat + dLat, lng + dLng};
    }
}
