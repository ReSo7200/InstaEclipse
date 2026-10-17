package ps.reso.instaeclipse.mods.media;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.Xposed.Module;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * "Media info" (#231): resolution, duration, codec, audio, the available video qualities (from the
 * DASH manifest) and the file size of the post / visible carousel slide.
 *
 * Values come from com.instagram.feed.media.Media getters found by their JSON key
 * (declaredClass Media + no args + usingEqStrings(key) + return type — the same pattern the
 * downloader uses for video_dash_manifest), resolved lazily and cached. Size = HEAD Content-Length
 * of the URL the downloader would save.
 */
final class MediaInfo {

    private static final String MEDIA = "com.instagram.feed.media.Media";
    private static final Map<String, Method> GETTERS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> MISSING = new ConcurrentHashMap<>();

    private MediaInfo() {}

    static void show(Context ctx, Object media, Object clickHandler) {
        ModuleLog.line("(IE|MediaInfo) requested for " + (media == null ? "null" : media.getClass().getName()));
        FeedVideoDownloadHook.executor.submit(() -> {
            String text;
            String[] mediaUrl = new String[1];
            try {
                text = build(ctx, media, clickHandler, mediaUrl);
            } catch (Throwable t) {
                text = String.valueOf(t);
                ModuleLog.line("(IE|MediaInfo) ❌ " + t);
            }
            final String body = text;
            FeedVideoDownloadHook.mainHandler.post(() -> {
                try {
                    // The menu's context may belong to a sheet that has closed by now; show on the
                    // Activity under it (a post opened from a profile has its own, above the main one).
                    Context host = ps.reso.instaeclipse.mods.ui.UIHookManager.dialogHost(ctx);
                    ModuleLog.line("(IE|MediaInfo) showing (" + body.length() + " chars) on " + host.getClass().getSimpleName());
                    ps.reso.instaeclipse.utils.dialog.IeDialog dlg = new ps.reso.instaeclipse.utils.dialog.IeDialog(host)
                            .title(I18n.t(ctx, R.string.ig_media_info_title));
                    // one rounded "label · value" card per line
                    LinearLayout rows = new LinearLayout(dlg.ctx);
                    rows.setOrientation(LinearLayout.VERTICAL);
                    rows.setBackground(dlg.cardBg());
                    rows.setPadding(dlg.dp(14), dlg.dp(6), dlg.dp(14), dlg.dp(6));
                    for (String ln : body.split("\n")) {
                        if (ln.trim().isEmpty()) continue;
                        int c = ln.indexOf(':');
                        LinearLayout r = new LinearLayout(dlg.ctx);
                        r.setPadding(0, dlg.dp(7), 0, dlg.dp(7));
                        TextView k = new TextView(dlg.ctx);
                        k.setText(c > 0 ? ln.substring(0, c).trim() : ln.trim());
                        k.setTextColor(dlg.sub);
                        k.setTextSize(14);
                        r.addView(k, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                        if (c > 0) {
                            TextView v = new TextView(dlg.ctx);
                            v.setText(ln.substring(c + 1).trim());
                            v.setTextColor(dlg.text);
                            v.setTextSize(14);
                            v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                            v.setGravity(android.view.Gravity.END);
                            v.setTextIsSelectable(true);
                            r.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f));
                        }
                        rows.addView(r);
                    }
                    dlg.view(rows);
                    // Song + Spotify / YouTube Music / Apple Music, like Story Info.
                    ps.reso.instaeclipse.mods.misc.StoryInfoHook.addMusic(dlg, host, media);
                    dlg.secondary(I18n.t(ctx, R.string.ig_media_info_copy), () -> {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager) host.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("media info", body));
                    });
                    if (mediaUrl[0] != null) dlg.secondary(I18n.t(ctx, R.string.ig_media_open_with), () -> openWith(host, mediaUrl[0]));
                    dlg.primary(I18n.t(ctx, R.string.ig_dialog_ok), null);
                    dlg.show();
                } catch (Throwable t) {
                    ModuleLog.line("(IE|MediaInfo) dialog: " + t);
                }
            });
        });
    }

    /** Hands the media's direct CDN link to any app that can open it (player, browser, editor). */
    private static void openWith(Context host, String url) {
        try {
            android.content.Intent view = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            String path = android.net.Uri.parse(url).getPath();
            String type = path == null ? null : path.toLowerCase(Locale.ROOT).endsWith(".mp4") ? "video/*"
                    : path.toLowerCase(Locale.ROOT).matches(".*\\.(jpe?g|png|webp|heic|gif)$") ? "image/*" : null;
            if (type != null) view.setDataAndType(android.net.Uri.parse(url), type);
            android.content.Intent chooser = android.content.Intent.createChooser(view, I18n.t(host, R.string.ig_media_open_with));
            if (!(host instanceof android.app.Activity)) chooser.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            host.startActivity(chooser);
        } catch (Throwable t) {
            ModuleLog.line("(IE|MediaInfo) open with: " + t);
        }
    }

    private static String build(Context ctx, Object media, Object clickHandler, String[] urlOut) {
        Object target = media;
        String slide = "";
        List<?> children = FeedVideoDownloadHook.carouselChildren(media);
        if (children != null && children.size() > 1) {
            int idx = ReelDownloadHook.findCarouselIndexFromView(ctx, children.size());
            if (idx < 0) idx = PostDownloadContextMenuHook.findCarouselIndex(clickHandler, children.size());
            if (idx < 0 || idx >= children.size()) idx = 0;
            target = children.get(idx);
            slide = " (" + (idx + 1) + "/" + children.size() + ")";
        }

        StringBuilder sb = new StringBuilder();
        Integer type = (Integer) get(target, "media_type", Integer.class);
        String kind = type == null ? "?" : type == 2 ? "Video" : type == 8 ? "Carousel" : "Photo";
        sb.append(kind).append(slide).append('\n');
        Integer w = (Integer) get(target, "original_width", Integer.class);
        Integer h = (Integer) get(target, "original_height", Integer.class);
        if (w != null && h != null) sb.append("Original: ").append(w).append(" × ").append(h).append('\n');
        Double dur = (Double) get(target, "video_duration", Double.class);
        if (dur != null && dur > 0) sb.append(String.format(Locale.US, "Duration: %.1f s", dur)).append('\n');
        String codec = (String) get(target, "video_codec", String.class);
        if (codec != null && !codec.isEmpty()) sb.append("Codec: ").append(codec).append('\n');
        Boolean audio = (Boolean) get(target, "has_audio", Boolean.class);
        if (audio != null) sb.append("Audio: ").append(audio ? "yes" : "no").append('\n');

        String manifest = FeedVideoDownloadHook.dashManifest(target);
        List<String> reps = representations(manifest);
        if (!reps.isEmpty()) {
            sb.append("\nQualities:\n");
            for (String r : reps) sb.append("• ").append(r).append('\n');
        }

        List<String> urls = FeedVideoDownloadHook.extractAllUrlsFromMedia(ctx, target);
        if (urls != null && !urls.isEmpty()) {
            urlOut[0] = urls.get(0);
            long size = headSize(urls.get(0));
            if (size > 0) sb.append(String.format(Locale.US, "\nFile size: %.2f MB", size / 1048576.0));
        }
        return sb.toString().trim();
    }

    /** "1080×1920 · 3.21 Mbps · avc1.64001F" for each video Representation, best first. */
    static List<String> representations(String manifest) {
        List<String> out = new ArrayList<>();
        if (manifest == null) return out;
        Matcher m = Pattern.compile("<Representation\\b([^>]*)>").matcher(manifest);
        List<long[]> order = new ArrayList<>();
        while (m.find()) {
            String a = m.group(1);
            String mime = attr(a, "mimeType");
            String wid = attr(a, "width"), hei = attr(a, "height");
            if (wid == null || hei == null || (mime != null && !mime.startsWith("video"))) continue;
            String bw = attr(a, "bandwidth"), codecs = attr(a, "codecs");
            long bps = bw != null ? Long.parseLong(bw) : 0;
            String line = wid + "×" + hei + (bps > 0 ? String.format(Locale.US, " · %.2f Mbps", bps / 1e6) : "")
                    + (codecs != null ? " · " + codecs : "");
            order.add(new long[]{bps, out.size()});
            out.add(line);
        }
        order.sort((x, y) -> Long.compare(y[0], x[0]));
        List<String> sorted = new ArrayList<>();
        for (long[] o : order) sorted.add(out.get((int) o[1]));
        return sorted;
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("\\b" + name + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static long headSize(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            return c.getContentLengthLong();
        } catch (Throwable t) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Object get(Object media, String key, Class<?> rt) {
        if (media == null || MISSING.containsKey(key)) return null;
        try {
            Method m = GETTERS.get(key);
            if (m == null) {
                m = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("MediaInfo_" + key, Module.hostClassLoader) : null;
                if (m == null) {
                    DexKitBridge bridge = Module.dexKitBridge;
                    List<MethodData> found = bridge == null ? List.of() : bridge.findMethod(FindMethod.create()
                            .matcher(MethodMatcher.create().declaredClass(MEDIA).paramCount(0)
                                    .usingEqStrings(List.of(key)).returnType(rt.getName())));
                    if (found.size() != 1) {
                        MISSING.put(key, true);
                        return null;
                    }
                    m = found.get(0).getMethodInstance(Module.hostClassLoader);
                    DexKitCache.saveMethod("MediaInfo_" + key, m);
                }
                m.setAccessible(true);
                GETTERS.put(key, m);
            }
            return m.getDeclaringClass().isInstance(media) ? m.invoke(media) : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
