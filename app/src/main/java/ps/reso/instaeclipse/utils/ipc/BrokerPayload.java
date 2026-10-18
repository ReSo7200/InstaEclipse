package ps.reso.instaeclipse.utils.ipc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.Arrays;
import java.util.HashSet;
import java.io.StringReader;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import ps.reso.instaeclipse.BuildConfig;
import ps.reso.instaeclipse.utils.download.DownloadEngine;
import ps.reso.instaeclipse.utils.download.DownloadRequest;

/** Pure bounded validation shared by the broker and its client; never logs payloads. */
public final class BrokerPayload {
    public static final String HOST = "com.instagram.android";
    public static final String IMPORT = "ps.reso.instaeclipse.mods.devops.config.JsonImportActivity";
    public static final String EXPORT = "ps.reso.instaeclipse.mods.devops.config.JsonExportActivity";
    public static final String LOCATION = "ps.reso.instaeclipse.mods.location.LocationPickerActivity";
    public static final String THEME = "ps.reso.instaeclipse.ui.theme.ThemeCustomizerActivity";
    public static final String FRAME = "ps.reso.instaeclipse.mods.media.VideoFramePickerActivity";
    public static final String FRAME_REQUEST = "selectedDownloadRequest";
    public static final int MAX_PARCEL_BYTES = 600 * 1024;
    public static final int MAX_CONFIG_CHARS = 200_000;
    private BrokerPayload() { }

    /** Same UTF-16 character limit on disk imports and exports; strict UTF-8, bounded reads. */
    public static String readConfig(java.io.InputStream input) throws java.io.IOException {
        if (input == null) throw new java.io.IOException("No configuration stream");
        java.io.Reader reader = new java.io.InputStreamReader(input,
                java.nio.charset.StandardCharsets.UTF_8.newDecoder());
        StringBuilder text = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if (text.length() + count > MAX_CONFIG_CHARS) throw new java.io.IOException("Configuration too large");
            text.append(buffer, 0, count);
        }
        String json = text.toString().trim();
        jsonObject(json, MAX_CONFIG_CHARS);
        return json;
    }

    public static void validateConfig(String json) { jsonObject(json, MAX_CONFIG_CHARS); }

    public static DownloadRequest request(String json) {
        jsonObject(json, DownloadRequest.MAX_JSON_CHARS);
        DownloadRequest request = DownloadRequest.fromJson(json);
        filename(request.filename);
        if (request.manifest.isEmpty() && request.direct.isEmpty()) invalid();
        for (DownloadRequest.Rendition rendition : request.direct) url(rendition.url);
        return request;
    }

    public static void legacy(String url, String audioUrl, String name, String mime, String owner) {
        url(url);
        if (audioUrl != null && !audioUrl.isEmpty()) {
            url(audioUrl);
            if (!"video/mp4".equals(mime)) invalid();
        }
        filename(name);
        if (!set("image/jpeg", "image/png", "image/webp", "video/mp4", "audio/mp4", "audio/mpeg", "audio/webm").contains(mime)) invalid();
        bounded(owner, 128, false);
    }

    public static Map<String, String> activity(String target, Map<String, String> input) {
        if (input == null || input.size() > 4) invalid();
        Set<String> allowed;
        if (IMPORT.equals(target)) allowed = set("target_package", "broadcast_action");
        else if (EXPORT.equals(target)) allowed = set("json_content", "file_name");
        else if (LOCATION.equals(target)) allowed = set("lat", "lng");
        else if (THEME.equals(target)) allowed = Collections.emptySet();
        else if (FRAME.equals(target)) allowed = set(FRAME_REQUEST);
        else { invalid(); return Collections.emptyMap(); }
        Map<String, String> copy = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            if (!allowed.contains(entry.getKey()) || entry.getValue() == null) invalid();
            copy.put(entry.getKey(), entry.getValue());
        }
        if (IMPORT.equals(target)) {
            String pkg = copy.get("target_package");
            if (pkg != null && !pkg.isEmpty() && !HOST.equals(pkg)) invalid();
            copy.put("target_package", HOST);
            String action = copy.get("broadcast_action");
            String importAction = BuildConfig.APPLICATION_ID + ".ACTION_IMPORT_CONFIG";
            String restoreAction = BuildConfig.APPLICATION_ID + ".ACTION_RESTORE_SETTINGS";
            if (action != null && !action.isEmpty() && !action.equals(importAction) && !action.equals(restoreAction)) invalid();
            copy.put("broadcast_action", action == null || action.isEmpty() ? importAction : action);
        } else if (EXPORT.equals(target)) {
            String json = copy.get("json_content");
            jsonObject(json, MAX_CONFIG_CHARS);
            if (copy.containsKey("file_name")) filename(copy.get("file_name"));
        } else if (LOCATION.equals(target)) {
            if (copy.containsKey("lat")) coordinate(copy.get("lat"), 90);
            if (copy.containsKey("lng")) coordinate(copy.get("lng"), 180);
        } else if (FRAME.equals(target)) {
            DownloadRequest original = request(copy.get(FRAME_REQUEST));
            // This explicit action always uses the best exposed video. Existing video-download
            // preferences and their request snapshots remain unchanged.
            copy.put(FRAME_REQUEST, new DownloadRequest(original.mediaId, original.owner,
                    original.filename, original.manifest, original.direct,
                    new DownloadRequest.Options(0, false, false), original.audio).toJson());
        }
        return Collections.unmodifiableMap(copy);
    }

    public static double coordinate(String value, double bound) {
        bounded(value, 32, true);
        try {
            double number = Double.parseDouble(value);
            if (!Double.isFinite(number) || Math.abs(number) > bound) invalid();
            return number;
        } catch (RuntimeException error) { throw new IllegalArgumentException("Invalid companion request"); }
    }

    private static void url(String value) {
        if (!DownloadEngine.isAllowedMediaUrl(value)) invalid();
    }
    private static void filename(String value) {
        bounded(value, 255, true);
        if (value.equals(".") || value.equals("..") || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0) invalid();
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) invalid();
    }
    private static void bounded(String value, int maximum, boolean required) {
        if ((required && (value == null || value.isEmpty())) || (value != null && value.length() > maximum)) invalid();
    }
    private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static void jsonObject(String value, int maximum) {
        bounded(value, maximum, true);
        // Streaming depth/token bounds before Gson materializes download arrays. No DASH parsing.
        try (JsonReader reader = new JsonReader(new StringReader(value))) {
            reader.setLenient(false);
            if (reader.peek() != JsonToken.BEGIN_OBJECT) invalid();
            int depth = 0, tokens = 0;
            while (reader.peek() != JsonToken.END_DOCUMENT) {
                if (++tokens > 16_384) invalid();
                switch (reader.peek()) {
                    case BEGIN_OBJECT: reader.beginObject(); depth++; break;
                    case BEGIN_ARRAY: reader.beginArray(); depth++; break;
                    case END_OBJECT: reader.endObject(); depth--; break;
                    case END_ARRAY: reader.endArray(); depth--; break;
                    case NAME: reader.nextName(); break;
                    case STRING: case NUMBER: reader.nextString(); break;
                    case BOOLEAN: reader.nextBoolean(); break;
                    case NULL: reader.nextNull(); break;
                    default: invalid();
                }
                if (depth > 32 || depth < 0) invalid();
            }
            if (depth != 0) invalid();
        } catch (Exception invalid) { throw new IllegalArgumentException("Invalid companion request"); }
    }
    private static void invalid() { throw new IllegalArgumentException("Invalid companion request"); }
}
