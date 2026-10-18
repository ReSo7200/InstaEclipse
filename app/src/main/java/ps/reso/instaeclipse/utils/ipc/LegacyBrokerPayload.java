package ps.reso.instaeclipse.utils.ipc;

import android.os.Bundle;
import android.os.Parcel;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Primitive-only bounded snapshot of upstream single-file, audio and HTML-export requests. */
public final class LegacyBrokerPayload {
    private LegacyBrokerPayload() { }
    private static final Set<String> MEDIA = keys("url", "audioUrl", "fallbackUrl", "filename", "mimeType",
            "username", "extractAudio", "trimStartMs", "trimDurationMs");
    private static final Set<String> EXPORT = keys("chatExportSubdir", "htmlFilename", "htmlB64",
            "saveUri", "messageCount", "mediaUrls", "mediaNames", "mediaMimes");

    public static Bundle validate(Bundle source) {
        if (source == null || source.hasFileDescriptors() || source.size() > 12) invalid();
        boolean export = source.containsKey("chatExportSubdir");
        Set<String> allowed = export ? EXPORT : MEDIA;
        Bundle out = new Bundle();
        for (String key : source.keySet()) {
            if (!allowed.contains(key)) invalid();
            Object value = source.get(key);
            if (value == null) continue;
            if (value instanceof String) out.putString(key, (String)value);
            else if (value instanceof String[]) out.putStringArray(key, ((String[])value).clone());
            else if (value instanceof Boolean) out.putBoolean(key, (Boolean)value);
            else if (value instanceof Long) out.putLong(key, (Long)value);
            else if (value instanceof Integer) out.putInt(key, (Integer)value);
            else invalid();
        }
        if (export) {
            String subdir = string(out, "chatExportSubdir", true);
            if (subdir.length() > 160 || !subdir.matches("(?:chats|comments)/[A-Za-z0-9._-]+")
                    || subdir.endsWith("/.") || subdir.endsWith("/..")) invalid();
            filename(string(out, "htmlFilename", true));
            String encoded = string(out, "htmlB64", true);
            if (encoded.length() > 240_000 || !encoded.matches("[A-Za-z0-9+/=]*")) invalid();
            // Only the folder already granted to the companion can be used.
            out.remove("saveUri");
            if (out.containsKey("messageCount") && !(out.get("messageCount") instanceof Integer)) invalid();
            if (out.getInt("messageCount", 0) < 0) invalid();
            String[] urls = array(out, "mediaUrls"), names = array(out, "mediaNames"), mimes = array(out, "mediaMimes");
            if (urls.length > 500 || urls.length != names.length || urls.length != mimes.length) invalid();
            for (int i = 0; i < urls.length; i++) {
                filename(names[i]);
                if (!ps.reso.instaeclipse.utils.download.DownloadEngine.isAllowedMediaUrl(urls[i])) invalid();
                if (!keys("image/jpeg", "image/png", "image/webp", "image/gif", "video/mp4", "audio/mp4", "audio/mpeg", "audio/webm").contains(mimes[i])) invalid();
            }
        } else {
            String mime = string(out, "mimeType", true);
            BrokerPayload.legacy(string(out, "url", true), string(out, "audioUrl", false),
                    string(out, "filename", true), mime, string(out, "username", false));
            String fallback = string(out, "fallbackUrl", false);
            if (fallback != null && !ps.reso.instaeclipse.utils.download.DownloadEngine.isAllowedMediaUrl(fallback)) invalid();
            if (out.containsKey("extractAudio") && !(out.get("extractAudio") instanceof Boolean)) invalid();
            if (out.getBoolean("extractAudio", false) && !"audio/mp4".equals(mime)) invalid();
            long start = number(out, "trimStartMs", 0), duration = number(out, "trimDurationMs", -1);
            if (start < 0 || start > 86_400_000 || duration < -1 || duration > 86_400_000) invalid();
        }
        Parcel parcel = Parcel.obtain();
        try { out.writeToParcel(parcel, 0); if (parcel.dataSize() > BrokerPayload.MAX_PARCEL_BYTES - 4096) invalid(); }
        finally { parcel.recycle(); }
        return out;
    }
    private static String string(Bundle b, String key, boolean required) {
        Object value = b.get(key);
        if (value != null && !(value instanceof String)) invalid();
        String text = (String)value;
        if (required && (text == null || text.isEmpty())) invalid();
        return text;
    }
    private static String[] array(Bundle b, String key) {
        Object value = b.get(key);
        if (value == null) return new String[0];
        if (!(value instanceof String[])) invalid();
        return (String[])value;
    }
    private static long number(Bundle b, String key, long fallback) {
        Object value = b.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Long)) invalid();
        return (Long)value;
    }
    private static void filename(String value) {
        if (value == null || value.isEmpty() || value.length() > 255 || value.equals(".") || value.equals("..")) invalid();
        for (int i=0; i<value.length(); i++) if (value.charAt(i)=='/' || value.charAt(i)==92 || Character.isISOControl(value.charAt(i))) invalid();
    }
    private static Set<String> keys(String... values) { return new HashSet<>(Arrays.asList(values)); }
    private static void invalid() { throw new IllegalArgumentException("Invalid companion request"); }
}
