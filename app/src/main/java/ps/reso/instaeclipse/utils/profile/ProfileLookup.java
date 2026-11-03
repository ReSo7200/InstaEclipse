package ps.reso.instaeclipse.utils.profile;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Reads a public profile by username, to fill the profile spoofer from it. */
public final class ProfileLookup {

    public static final class Result {
        public String id = "", username = "", name = "", bio = "", avatar = "";
        public int posts = -1, followers = -1, following = -1;
        public boolean verified;
    }

    private static final String APP_ID = "936619743392459";
    private static final String AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    private ProfileLookup() {}

    /** Blocking, call off the main thread. Returns null when the profile can't be read. */
    public static Result fetch(String username) {
        if (username == null) return null;
        String clean = username.trim().replace("@", "");
        if (!clean.matches("[A-Za-z0-9._]{1,30}")) return null;
        for (String host : new String[]{"https://i.instagram.com", "https://www.instagram.com"}) {
            try {
                String body = get(host + "/api/v1/users/web_profile_info/?username=" + clean);
                if (body == null) continue;
                JSONObject user = new JSONObject(body).getJSONObject("data").getJSONObject("user");
                Result r = new Result();
                r.id = user.optString("id");
                r.username = user.optString("username", clean);
                r.name = user.optString("full_name");
                r.bio = user.optString("biography");
                r.verified = user.optBoolean("is_verified");
                r.followers = count(user, "edge_followed_by");
                r.following = count(user, "edge_follow");
                r.posts = count(user, "edge_owner_to_timeline_media");
                String pic = user.optString("profile_pic_url_hd", user.optString("profile_pic_url"));
                if (!pic.isEmpty()) {
                    byte[] img = getBytes(pic);
                    if (img != null) {
                        String b64 = SpoofAvatar.encode(img);
                        if (b64 != null) r.avatar = b64;
                    }
                }
                return r.id.isEmpty() ? null : r;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static int count(JSONObject user, String key) {
        JSONObject o = user.optJSONObject(key);
        return o == null ? -1 : (int) Math.min(o.optLong("count", -1), Integer.MAX_VALUE);
    }

    private static String get(String url) {
        byte[] b = getBytes(url);
        return b == null ? null : new String(b, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] getBytes(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", AGENT);
            c.setRequestProperty("X-IG-App-ID", APP_ID);
            if (c.getResponseCode() / 100 != 2) return null;
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toByteArray();
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
