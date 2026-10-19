package ps.reso.instaeclipse.mods.misc;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Spoof device: Instagram sees the phone as a Pixel 11 Pro XL or a Galaxy S26 Ultra. Instagram
 * limits some features to those phones by brand and model, among them downloading HDR (AV1/HLG)
 * video: other phones only get the SDR versions from its servers.
 *
 * <p>Only Instagram's view changes: the {@link Build} fields and the matching system properties
 * (model, brand, manufacturer, device, fingerprint, SoC) in Instagram's own process. The Android
 * version is left alone, so Instagram never calls system APIs the phone doesn't have.
 *
 * <p>While the spoof is on, the developer options that HDR playback needs ({@link #HDR_OVERRIDES})
 * are merged into Instagram's mc_overrides.json; turning the spoof off takes them out again.
 * Both happen at Instagram's start, so a change applies on the next restart.
 */
public final class DeviceSpoofHook {

    public static final String PIXEL = "pixel";
    public static final String S26 = "s26";
    public static final String[] DEVICES = {"", PIXEL, S26};

    /** Developer options for HDR video (MobileConfig overrides, mc_overrides.json format). */
    static final String HDR_OVERRIDES = "{\"66119:\":[\"0: : true\"],\"110796:\":[\"92: : true\"],"
            + "\"74463:\":[\"4: : true\",\"20: : false\"],\"86550:\":[\"9: : true\"],\"77821:\":[\"42: : true\"],"
            + "\"107732:\":[\"12: : true\"],\"124768:\":[\"32: : true\",\"12: : true\"],"
            + "\"102818:\":[\"2: : true\",\"6: : false\",\"1: : true\",\"0: : true\",\"3: : true\"],"
            + "\"50233:\":[\"11: : true\"],\"120903:\":[\"0: : true\"],\"124767:\":[\"31: : true\",\"13: : true\"],"
            + "\"64571:\":[\"12: : true\"],\"78165:\":[\"381: : false\",\"245: : true\",\"167: : false\",\"110: : true\",\"97: : false\"],"
            + "\"68437:\":[\"5: : false\"]}";

    private static final String APPLIED_MARKER = "mobileconfig/ie_spoof_overrides_applied";

    /** One device: Build field values, plus extra system features it reports. */
    private static final class Profile {
        final String manufacturer, brand, model, device, product, fingerprint, buildId, socManufacturer, socModel, platform;
        final Set<String> features;

        Profile(String manufacturer, String brand, String model, String device, String product, String fingerprint,
                String buildId, String socManufacturer, String socModel, String platform, String... features) {
            this.manufacturer = manufacturer;
            this.brand = brand;
            this.model = model;
            this.device = device;
            this.product = product;
            this.fingerprint = fingerprint;
            this.buildId = buildId;
            this.socManufacturer = socManufacturer;
            this.socModel = socModel;
            this.platform = platform;
            this.features = new HashSet<>(Arrays.asList(features));
        }
    }

    // Pixel 11 Pro XL (kodiak), from its official August 2026 firmware.
    private static final Profile PIXEL_11_PRO_XL = new Profile("Google", "google", "Pixel 11 Pro XL", "kodiak", "kodiak",
            "google/kodiak/kodiak:17/CD1A.260714.001.A9/15938155:user/release-keys", "CD1A.260714.001.A9",
            "Google", "Tensor G6", "malibu",
            "com.google.android.feature.PIXEL_EXPERIENCE",
            "com.google.android.feature.PIXEL_2026_EXPERIENCE",
            "com.google.android.feature.GOOGLE_BUILD",
            "com.google.android.feature.GOOGLE_EXPERIENCE");

    // Galaxy S26 Ultra (SM-S948B). Its codename and fingerprint aren't public, so the device,
    // product and fingerprint stay the phone's own (null).
    private static final Profile GALAXY_S26_ULTRA = new Profile("samsung", "samsung", "SM-S948B", null, null,
            null, null, "QTI", "SM8850", null);

    private static final Pattern PRODUCT_PROP = Pattern.compile("ro\\.product\\.(?:[a-z_]+\\.)?(model|brand|manufacturer|device|name)");
    private static final Pattern FINGERPRINT_PROP = Pattern.compile("ro\\.(?:[a-z_]+\\.)?build\\.fingerprint");

    private DeviceSpoofHook() {}

    public static String deviceName(String id) {
        if (PIXEL.equals(id)) return PIXEL_11_PRO_XL.model;
        if (S26.equals(id)) return "Galaxy S26 Ultra";
        return null;
    }

    private static Profile profile() {
        if (PIXEL.equals(FeatureFlags.spoofDevice)) return PIXEL_11_PRO_XL;
        if (S26.equals(FeatureFlags.spoofDevice)) return GALAXY_S26_ULTRA;
        return null;
    }

    /** Called from Application.attach, before Instagram reads anything about the device. */
    public static void apply(ClassLoader classLoader) {
        Profile p = profile();
        if (p == null) return;
        try {
            set(Build.class, "MANUFACTURER", p.manufacturer);
            set(Build.class, "BRAND", p.brand);
            set(Build.class, "MODEL", p.model);
            set(Build.class, "DEVICE", p.device);
            set(Build.class, "PRODUCT", p.product);
            set(Build.class, "HARDWARE", p.device);
            set(Build.class, "BOARD", p.device);
            set(Build.class, "FINGERPRINT", p.fingerprint);
            set(Build.class, "ID", p.buildId);
            set(Build.class, "DISPLAY", p.buildId);
            set(Build.class, "SOC_MANUFACTURER", p.socManufacturer);
            set(Build.class, "SOC_MODEL", p.socModel);

            XC_MethodHook props = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    String v = prop(p, (String) param.args[0]);
                    if (v != null) param.setResult(v);
                }
            };
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", classLoader);
            XposedHelpers.findAndHookMethod(sp, "get", String.class, props);
            XposedHelpers.findAndHookMethod(sp, "get", String.class, String.class, props);

            if (!p.features.isEmpty()) {
                XC_MethodHook feature = new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (p.features.contains(param.args[0])) param.setResult(true);
                    }
                };
                Class<?> pm = XposedHelpers.findClass("android.app.ApplicationPackageManager", classLoader);
                XposedHelpers.findAndHookMethod(pm, "hasSystemFeature", String.class, feature);
                XposedHelpers.findAndHookMethod(pm, "hasSystemFeature", String.class, int.class, feature);
            }
            ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.setHooked("SpoofDevice");
            ModuleLog.line("(InstaEclipse | DeviceSpoof): ✅ " + p.manufacturer + " " + p.model);
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | DeviceSpoof): ❌ " + t);
        }
    }

    private static void set(Class<?> c, String field, String value) {
        if (value == null) return;
        try {
            XposedHelpers.setStaticObjectField(c, field, value);
        } catch (Throwable ignored) {} // SOC_* only exist on Android 12+
    }

    /** The spoofed value of a system property, or null to keep the real one. */
    private static String prop(Profile p, String key) {
        if (key == null) return null;
        java.util.regex.Matcher m = PRODUCT_PROP.matcher(key);
        if (m.matches()) {
            switch (m.group(1)) {
                case "model": return p.model;
                case "brand": return p.brand;
                case "manufacturer": return p.manufacturer;
                case "device": return p.device;
                case "name": return p.product;
            }
        }
        if (FINGERPRINT_PROP.matcher(key).matches()) return p.fingerprint;
        switch (key) {
            case "ro.soc.manufacturer": return p.socManufacturer;
            case "ro.soc.model": return p.socModel;
            case "ro.board.platform": return p.platform;
            case "ro.build.id": return p.buildId;
            default: return null;
        }
    }

    // ---- HDR developer options ----

    /**
     * Brings Instagram's mc_overrides.json in line with the spoof: merges {@link #HDR_OVERRIDES}
     * in when it's on, and takes exactly those entries out again once it's turned off.
     */
    public static void syncOverrides(Context context) {
        try {
            File marker = new File(context.getFilesDir(), APPLIED_MARKER);
            boolean on = profile() != null;
            if (on == marker.exists()) return;
            File file = new File(context.getFilesDir(), "mobileconfig/mc_overrides.json");
            String json = file.exists() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "{}";
            String updated = on ? merge(json, HDR_OVERRIDES) : remove(json, HDR_OVERRIDES);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            ps.reso.instaeclipse.utils.core.AtomicFiles.write(file, updated);
            if (on) ps.reso.instaeclipse.utils.core.AtomicFiles.write(marker, "1");
            else //noinspection ResultOfMethodCallIgnored
                marker.delete();
            ModuleLog.line("(InstaEclipse | DeviceSpoof): HDR developer options " + (on ? "added" : "removed"));
        } catch (Throwable t) {
            ModuleLog.line("(InstaEclipse | DeviceSpoof): ❌ overrides: " + t);
        }
    }

    /** The "index" part of an override entry ("12: : true" → "12"). */
    private static String index(String entry) {
        int i = entry.indexOf(':');
        return i < 0 ? entry : entry.substring(0, i);
    }

    /** {@code add}'s entries merged into {@code json}, replacing entries with the same config and index. */
    static String merge(String json, String add) throws Exception {
        JSONObject out = new JSONObject(json);
        JSONObject in = new JSONObject(add);
        for (Iterator<String> it = in.keys(); it.hasNext(); ) {
            String config = it.next();
            JSONArray adds = in.getJSONArray(config);
            JSONArray old = out.optJSONArray(config);
            JSONArray merged = new JSONArray();
            Set<String> replaced = new HashSet<>();
            for (int i = 0; i < adds.length(); i++) replaced.add(index(adds.getString(i)));
            if (old != null)
                for (int i = 0; i < old.length(); i++)
                    if (!replaced.contains(index(old.getString(i)))) merged.put(old.getString(i));
            for (int i = 0; i < adds.length(); i++) merged.put(adds.getString(i));
            out.put(config, merged);
        }
        return out.toString();
    }

    /** {@code json} without {@code add}'s exact entries (configs left empty are dropped). */
    static String remove(String json, String add) throws Exception {
        JSONObject out = new JSONObject(json);
        JSONObject in = new JSONObject(add);
        for (Iterator<String> it = in.keys(); it.hasNext(); ) {
            String config = it.next();
            JSONArray old = out.optJSONArray(config);
            if (old == null) continue;
            Set<String> drop = new HashSet<>();
            JSONArray adds = in.getJSONArray(config);
            for (int i = 0; i < adds.length(); i++) drop.add(adds.getString(i));
            JSONArray kept = new JSONArray();
            for (int i = 0; i < old.length(); i++) if (!drop.contains(old.getString(i))) kept.put(old.getString(i));
            if (kept.length() == 0) out.remove(config);
            else out.put(config, kept);
        }
        return out.toString();
    }
}
