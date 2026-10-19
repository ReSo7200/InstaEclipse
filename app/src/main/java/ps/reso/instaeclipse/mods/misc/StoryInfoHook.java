package ps.reso.instaeclipse.mods.misc;

import ps.reso.instaeclipse.xposedcompat.AndroidAppHelper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Story ⋯ menu extras: "Story Info" (exact times, music, location, hashtags, reshare source, app,
 * alt text, size, countdown, question, highlights) plus "Quiz Answer" / "Poll Results" on stories
 * that carry those stickers.
 *
 * Each story field is read through Media's paramCount-0 getter guarded by its JSON key (found by
 * DexKit). The returned models are turned into plain maps with their JSON keys through the
 * models' own 0-arg Map serializer, so every value below is looked up by its API key name,
 * never by an obfuscated field name.
 */
public class StoryInfoHook {

    private static final String[] KEYS = {
            "taken_at", "expiring_at", "story_edited_at", "is_story_edited",
            "music_metadata", "story_music_stickers", "story_music_lyric_stickers", "clips_metadata",
            "story_locations", "story_hashtags",
            "reshared_story_media_author", "story_feed_media", "story_app_attribution",
            "accessibility_caption", "original_width", "original_height", "has_audio",
            "story_countdowns", "story_questions", "story_mention_request_info",
            "story_quizs", "story_polls", "story_sliders", "story_prompts", "sponsor_tags", "is_paid_partnership", "coauthor_producers",
    };
    private static final Map<String, Method> getters = new LinkedHashMap<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        resolveGetters(bridge, classLoader);
        if (getters.isEmpty()) {
            ModuleLog.line("(IE|StoryInfo) ❌ no Media getters found");
            return;
        }
        ModuleLog.line("(IE|StoryInfo) getters: " + getters.keySet());
        installButtonHook(bridge, classLoader);
        installClickHook(bridge, classLoader);
        FeatureStatusTracker.setHooked("StoryInfo");
    }

    // ── DexKit ────────────────────────────────────────────────────────────────

    private static void resolveGetters(DexKitBridge bridge, ClassLoader classLoader) {
        String queried = String.join(",", KEYS);
        if (DexKitCache.isCacheValid() && queried.equals(DexKitCache.loadString("StoryInfoQueried"))) {
            String found = DexKitCache.loadString("StoryInfoKeys");
            if (found != null) {
                for (String key : found.split(",")) {
                    if (key.isEmpty()) continue;
                    Method m = DexKitCache.loadMethod("StoryInfo_" + key, classLoader);
                    if (m != null) { m.setAccessible(true); getters.put(key, m); }
                }
                if (!getters.isEmpty()) return;
            }
        }
        StringBuilder keys = new StringBuilder();
        for (String key : KEYS) {
            try {
                List<MethodData> found = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass("com.instagram.feed.media.Media")
                                .paramCount(0)
                                .usingEqStrings(List.of(key))));
                for (MethodData md : found) {
                    if (md.getName().startsWith("<")) continue;
                    Method m = md.getMethodInstance(classLoader);
                    if (m.getReturnType() == void.class || Modifier.isStatic(m.getModifiers())) continue;
                    m.setAccessible(true);
                    getters.put(key, m);
                    DexKitCache.saveMethod("StoryInfo_" + key, m);
                    keys.append(key).append(',');
                    break;
                }
            } catch (Throwable t) {
                ModuleLog.line("(IE|StoryInfo) getter " + key + ": " + t);
            }
        }
        DexKitCache.saveString("StoryInfoKeys", keys.toString());
        DexKitCache.saveString("StoryInfoQueried", queried);
    }

    // ── Menu entries ─────────────────────────────────────────────────────────

    private void installButtonHook(DexKitBridge bridge, ClassLoader classLoader) {
        List<Method> builders = null;
        if (DexKitCache.isCacheValid()) builders = DexKitCache.loadMethods("MentionButtons", classLoader);
        if (builders == null || builders.isEmpty()) {
            try {
                List<MethodData> methods = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create().usingStrings("[INTERNAL] Pause Playback")));
                builders = new ArrayList<>(
                        ps.reso.instaeclipse.mods.media.StoryDownloadHook.storyOptionBuilders(methods, classLoader));
            } catch (Throwable t) {
                ModuleLog.line("(IE|StoryInfo) ❌ builder DexKit: " + t);
            }
        }
        if (builders == null || builders.isEmpty()) return;

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                try {
                    if (!FeatureFlags.storyInfo) return;
                    CharSequence[] original = (CharSequence[]) param.getResult();
                    if (original == null) return;
                    Context app = AndroidAppHelper.currentApplication();
                    String info = I18n.t(app, R.string.ig_btn_story_info);
                    for (CharSequence cs : original) if (info.contentEquals(cs)) return;

                    List<CharSequence> add = new ArrayList<>();
                    add.add(info);
                    Object media = findMedia(param);
                    if (media != null) {
                        if (!list(media, "story_quizs").isEmpty()) add.add(I18n.t(app, R.string.ig_btn_quiz_answer));
                        if (!list(media, "story_polls").isEmpty()) add.add(I18n.t(app, R.string.ig_btn_poll_results));
                        if (!list(media, "story_sliders").isEmpty()) add.add(I18n.t(app, R.string.ig_btn_slider_results));
                    }
                    CharSequence[] extended = new CharSequence[original.length + add.size()];
                    System.arraycopy(original, 0, extended, 0, original.length);
                    for (int i = 0; i < add.size(); i++) extended[original.length + i] = add.get(i);
                    param.setResult(extended);
                } catch (Throwable t) {
                    ModuleLog.line("(IE|StoryInfo) builder: " + t);
                }
            }
        };
        int hooked = 0;
        for (Method m : builders) {
            try { XposedBridge.hookMethod(m, hook); hooked++; } catch (Throwable ignored) {}
        }
        ModuleLog.line("(IE|StoryInfo) ✅ button hook on " + hooked + " builder(s)");
    }

    private void installClickHook(DexKitBridge bridge, ClassLoader classLoader) {
        List<Method> dispatchers = ps.reso.instaeclipse.mods.media.StoryDownloadHook.storyClickDispatchers(bridge, classLoader);
        if (dispatchers.isEmpty()) {
            ModuleLog.line("(IE|StoryInfo) ❌ click handler not found");
            return;
        }
        try {
            XC_MethodHook clickHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    try {
                        if (!FeatureFlags.storyInfo) return;
                        CharSequence tapped = null;
                        for (Object a : param.args) if (a instanceof CharSequence cs) { tapped = cs; break; }
                        if (tapped == null) return;
                        Context app = AndroidAppHelper.currentApplication();
                        String t = tapped.toString();
                        int which;
                        if (t.equals(I18n.t(app, R.string.ig_btn_story_info))) which = 0;
                        else if (t.equals(I18n.t(app, R.string.ig_btn_quiz_answer))) which = 1;
                        else if (t.equals(I18n.t(app, R.string.ig_btn_poll_results))) which = 2;
                        else if (t.equals(I18n.t(app, R.string.ig_btn_slider_results))) which = 3;
                        else return;

                        param.setResult(null);
                        Object media = findMedia(param);
                        Context ctx = StoryMentionHook.findContext(param.thisObject);
                        for (Object a : param.args) if (ctx == null) ctx = StoryMentionHook.findContext(a);
                        if (ctx == null) ctx = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
                        if (media == null || ctx == null) {
                            ModuleLog.line("(IE|StoryInfo) ❌ media/context not found");
                            return;
                        }
                        Context c = ctx;
                        mainHandler.post(() -> {
                            try {
                                if (which == 0) showInfo(c, media);
                                else if (which == 1) showQuiz(c, media);
                                else if (which == 2) showPolls(c, media);
                                else showSliders(c, media);
                            } catch (Throwable e) {
                                ModuleLog.line("(IE|StoryInfo) dialog: " + e);
                            }
                        });
                    } catch (Throwable e) {
                        ModuleLog.line("(IE|StoryInfo) click: " + e);
                    }
                }
            };
            for (Method m : dispatchers) XposedBridge.hookMethod(m, clickHook);
            ModuleLog.line("(IE|StoryInfo) ✅ click hook installed");
        } catch (Throwable t) {
            ModuleLog.line("(IE|StoryInfo) ❌ click hook: " + t);
        }
    }

    private static Object findMedia(XC_MethodHook.MethodHookParam param) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Object media = param.thisObject != null ? StoryMentionHook.findMediaInGraph(param.thisObject, 0, visited) : null;
        for (Object a : param.args) {
            if (media != null) break;
            if (a != null) media = StoryMentionHook.findMediaInGraph(a, 0, visited);
        }
        return media;
    }

    // ── Reading values ───────────────────────────────────────────────────────

    private static Object raw(Object media, String key) {
        Method g = getters.get(key);
        if (g == null) return null;
        try { return g.invoke(media); } catch (Throwable t) { return null; }
    }

    /** The field as a JSON-like tree: Map (JSON keys), List, String, Number, Boolean. */
    static Object value(Object media, String key) {
        return tree(raw(media, key), 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    static List<Object> list(Object media, String key) {
        Object v = value(media, key);
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object o : l) if (o != null) out.add(o);
            return out;
        }
        return v == null ? Collections.emptyList() : Collections.singletonList(v);
    }

    private static final Map<Class<?>, Method> mapMethods = new ConcurrentHashMap<>();
    private static final Method NONE;
    static { try { NONE = Object.class.getMethod("toString"); } catch (NoSuchMethodException e) { throw new RuntimeException(e); } }

    private static final Map<Class<?>, Object> serializerArgs = new ConcurrentHashMap<>();

    /**
     * The model's own serializer to a JSON-keyed Map: the synthetic {@code Map x(Options)} (full map,
     * its options object has a no-arg constructor) or the synthetic {@code Map x()}.
     */
    private static Method mapMethod(Class<?> c) {
        Method m = mapMethods.get(c);
        if (m == null) {
            Method zero = null, one = null;
            for (Method cand : c.getDeclaredMethods()) {
                if (cand.getReturnType() != Map.class || Modifier.isStatic(cand.getModifiers()) || !cand.isSynthetic()) continue;
                if (cand.getParameterCount() == 0 && zero == null) zero = cand;
                else if (cand.getParameterCount() == 1 && one == null
                        && cand.getParameterTypes()[0].getName().startsWith("X.") && serializerArg(cand.getParameterTypes()[0]) != null) one = cand;
            }
            m = one != null ? one : zero != null ? zero : NONE;
            if (m != NONE) m.setAccessible(true);
            mapMethods.put(c, m);
        }
        return m == NONE ? null : m;
    }

    private static Object serializerArg(Class<?> type) {
        Object a = serializerArgs.get(type);
        if (a == null) {
            try {
                java.lang.reflect.Constructor<?> k = type.getDeclaredConstructor();
                k.setAccessible(true);
                a = k.newInstance();
            } catch (Throwable t) {
                a = NONE;
            }
            serializerArgs.put(type, a);
        }
        return a == NONE ? null : a;
    }

    private static Object tree(Object o, int depth, Set<Object> seen) {
        if (o == null || depth > 7) return null;
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean) return o instanceof CharSequence ? o.toString() : o;
        if (o instanceof Enum<?> e) return e.toString();
        if (!seen.add(o)) return null;
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (key.equals("dash_manifest")) continue;
                Object v = tree(e.getValue(), depth + 1, seen);
                if (v != null) out.put(key, v);
            }
            return out;
        }
        if (o instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object v : c) { Object t = tree(v, depth + 1, seen); if (t != null) out.add(t); }
            return out;
        }
        String n = o.getClass().getName();
        if (!n.startsWith("com.instagram.") && !n.startsWith("X.") && !n.startsWith("com.facebook.")) return o.toString();
        Method mm = mapMethod(o.getClass());
        if (mm != null) {
            try {
                Object r = mm.getParameterCount() == 0 ? mm.invoke(o)
                        : mm.invoke(o, serializerArg(mm.getParameterTypes()[0]));
                if (r instanceof Map) return tree(r, depth, seen);
            } catch (Throwable ignored) {}
        }
        // No serializer (e.g. sticker wrappers): keep its non-null fields under their raw names,
        // so the named models inside them are still reachable by find().
        Map<String, Object> out = new LinkedHashMap<>();
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = tree(f.get(o), depth + 1, seen);
                    if (v != null) out.put("_" + f.getName(), v);
                } catch (Throwable ignored) {}
            }
        }
        return out;
    }

    /** First value under {@code key} anywhere in the tree (depth-first). */
    static Object find(Object tree, String key) {
        if (tree instanceof Map<?, ?> m) {
            Object v = m.get(key);
            if (v != null) return v;
            for (Object c : m.values()) { Object r = find(c, key); if (r != null) return r; }
        } else if (tree instanceof List<?> l) {
            for (Object c : l) { Object r = find(c, key); if (r != null) return r; }
        }
        return null;
    }

    static String str(Object tree, String key) {
        Object v = find(tree, key);
        if (v == null || v instanceof Map || v instanceof List) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static String plain(Object v) {
        if (v == null || v instanceof Map || v instanceof List) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Long num(Object v) {
        if (v instanceof Number n) return n.longValue();
        if (v instanceof String s) try { return (long) Double.parseDouble(s); } catch (Throwable ignored) {}
        return null;
    }

    private static String time(Object v) {
        Long secs = num(v);
        if (secs == null || secs <= 0) return null;
        if (secs > 100_000_000_000L) secs /= 1000; // millis
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM).format(new Date(secs * 1000));
    }

    // ── Story Info ───────────────────────────────────────────────────────────

    private static void showInfo(Context ctx, Object media) {
        IeDialog dlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_btn_story_info))
                .message(I18n.t(ctx, R.string.ig_story_info_hint));
        int rows = 0;

        rows += row(dlg, ctx, R.string.ig_si_posted, time(value(media, "taken_at")), null);
        rows += row(dlg, ctx, R.string.ig_si_expires, time(value(media, "expiring_at")), null);
        String edited = time(value(media, "story_edited_at"));
        if (edited == null && Boolean.TRUE.equals(value(media, "is_story_edited"))) edited = I18n.t(ctx, R.string.ig_si_yes);
        rows += row(dlg, ctx, R.string.ig_si_edited, edited, null);

        if (addMusic(dlg, ctx, media)) rows++;

        List<String> places = new ArrayList<>();
        for (Object loc : list(media, "story_locations")) {
            String name = str(find(loc, "location"), "name");
            if (name == null) name = str(loc, "name");
            if (name != null && !places.contains(name)) places.add(name);
        }
        rows += row(dlg, ctx, R.string.ig_si_location, join(places, "\n"), null);

        List<String> tags = new ArrayList<>();
        for (Object h : list(media, "story_hashtags")) {
            String name = str(find(h, "hashtag"), "name");
            if (name == null) name = str(h, "name");
            if (name != null && !tags.contains("#" + name)) tags.add("#" + name);
        }
        rows += row(dlg, ctx, R.string.ig_si_hashtags, join(tags, " "), null);

        Object authorUser = raw(media, "reshared_story_media_author");
        String author = authorUser == null ? null : ps.reso.instaeclipse.utils.users.UserUtils.callUsernameGetter(authorUser);
        List<String> posts = new ArrayList<>();
        for (Object fm : list(media, "story_feed_media")) {
            String code = str(fm, "media_code");
            if (code == null) code = str(fm, "code");
            boolean reel = "clips".equals(str(fm, "product_type"));
            if (code != null) posts.add("https://www.instagram.com/" + (reel ? "reel/" : "p/") + code + "/");
            if (author == null) author = str(find(fm, "user"), "username");
        }
        rows += row(dlg, ctx, R.string.ig_si_shared_from, author == null ? null : "@" + author, null);
        for (String p : posts) rows += row(dlg, ctx, R.string.ig_si_original_post, p, p);

        // Paid partnership / collab partners are not stickers, so Mentions never lists them.
        List<String> sponsors = usernames(raw(media, "sponsor_tags"));
        for (String u : sponsors)
            rows += row(dlg, ctx, R.string.ig_si_paid_partnership, "@" + u, "https://www.instagram.com/" + u + "/");
        if (sponsors.isEmpty() && Boolean.TRUE.equals(value(media, "is_paid_partnership")))
            rows += row(dlg, ctx, R.string.ig_si_paid_partnership, I18n.t(ctx, R.string.ig_si_yes), null);
        for (String u : usernames(raw(media, "coauthor_producers")))
            if (!sponsors.contains(u))
                rows += row(dlg, ctx, R.string.ig_si_collaborators, "@" + u, "https://www.instagram.com/" + u + "/");

        Object app = value(media, "story_app_attribution");
        String appName = str(app, "name");
        if (appName == null) appName = str(app, "app_action_text");
        String appLink = str(app, "link");
        if (appLink == null) appLink = str(app, "content_url");
        rows += row(dlg, ctx, R.string.ig_si_posted_with,
                appName == null ? appLink : (appLink == null ? appName : appName + "\n" + appLink), appLink);

        rows += row(dlg, ctx, R.string.ig_si_alt_text, plain(value(media, "accessibility_caption")), null);

        Long w = num(value(media, "original_width")), h = num(value(media, "original_height"));
        String size = w != null && h != null && w > 0 && h > 0 ? w + " × " + h : null;
        Object audio = value(media, "has_audio");
        if (audio instanceof Boolean b)
            size = (size == null ? "" : size + " · ") + I18n.t(ctx, b ? R.string.ig_si_sound_on : R.string.ig_si_sound_off);
        rows += row(dlg, ctx, R.string.ig_si_size, size, null);

        for (Object cd : list(media, "story_countdowns")) {
            Object c = find(cd, "countdown_sticker");
            if (c == null) c = cd;
            String text = str(c, "text");
            String end = time(find(c, "end_ts"));
            rows += row(dlg, ctx, R.string.ig_si_countdown,
                    text == null ? end : (end == null ? text : text + "\n" + end), null);
        }
        for (Object q : list(media, "story_questions")) {
            rows += row(dlg, ctx, R.string.ig_si_question, str(q, "question"), null);
        }
        for (Object pr : list(media, "story_prompts")) {
            Object sticker = find(pr, "prompt_sticker");
            if (sticker == null) sticker = pr;
            StringBuilder sb = new StringBuilder();
            String text = str(sticker, "text");
            if (text != null) sb.append(text).append('\n');
            Long count = num(find(sticker, "participant_count"));
            if (count != null) sb.append(java.text.NumberFormat.getIntegerInstance().format(count))
                    .append(' ').append(I18n.t(ctx, R.string.ig_si_joined));
            List<String> faces = new ArrayList<>();
            Object top = find(sticker, "facepile_top_participants");
            if (top instanceof List<?> users)
                for (Object u : users) { String n = str(u, "username"); if (n != null && !faces.contains("@" + n)) faces.add("@" + n); }
            if (!faces.isEmpty()) sb.append(sb.length() > 0 ? "\n" : "").append(String.join(", ", faces));
            rows += row(dlg, ctx, R.string.ig_si_add_yours, sb.toString().trim(), null);
        }
        Object req = value(media, "story_mention_request_info");
        if (req != null && !(req instanceof List<?> l && l.isEmpty()))
            rows += row(dlg, ctx, R.string.ig_si_mention_request, I18n.t(ctx, R.string.ig_si_yes), null);

        if (rows == 0) dlg.message(I18n.t(ctx, R.string.ig_si_none));
        dlg.show();
    }

    /** Adds the Music row and, for licensed songs, the streaming buttons; for posts and reels
     *  (Media Info) as well as stories. Returns false when the media has no music. */
    public static boolean addMusic(IeDialog dlg, Context ctx, Object media) {
        String[] song;
        try { song = music(media); } catch (Throwable t) { return false; }
        if (song == null) return false;
        row(dlg, ctx, R.string.ig_si_music, song[0], null);
        if (song[1] != null) dlg.view(musicLinks(dlg, ctx, song[1]));
        return true;
    }

    /** {display line, search query (licensed songs only) or null}. */
    private static String[] music(Object media) {
        String[] keys = {"story_music_stickers", "story_music_lyric_stickers", "music_metadata", "clips_metadata"};
        for (String k : keys) {
            Object v = value(media, k);
            if (v == null) continue;
            Object licensed = find(v, "music_asset_info");
            if (licensed != null) {
                String title = str(licensed, "title");
                String artist = str(licensed, "display_artist");
                if (title != null) {
                    String line = artist == null ? title : title + " — " + artist;
                    return new String[]{line, artist == null ? title : title + " " + artist};
                }
            }
            Object original = find(v, "original_sound_info");
            if (original != null) {
                String title = str(original, "original_audio_title");
                String artist = str(find(original, "ig_artist"), "username");
                if (title != null) return new String[]{artist == null ? title : title + " — @" + artist, null};
            }
        }
        return null;
    }

    /** Spotify / YouTube Music / Apple Music search buttons for a song. */
    private static LinearLayout musicLinks(IeDialog dlg, Context ctx, String query) {
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        String q = Uri.encode(query);
        Object[][] apps = {
                {"Spotify", "https://open.spotify.com/search/" + q, R.drawable.ic_logo_spotify},
                {"YouTube Music", "https://music.youtube.com/search?q=" + q, R.drawable.ic_logo_ytmusic},
                {"Apple Music", "https://music.apple.com/search?term=" + q, R.drawable.ic_logo_applemusic},
        };
        for (int i = 0; i < apps.length; i++) {
            Object[] a = apps[i];
            String url = (String) a[1];
            android.widget.ImageView b = new android.widget.ImageView(ctx);
            android.graphics.drawable.Drawable logo = moduleDrawable((Integer) a[2]);
            if (logo != null) b.setImageDrawable(logo);
            b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
            b.setContentDescription((String) a[0]);
            b.setPadding(dlg.dp(6), dlg.dp(9), dlg.dp(6), dlg.dp(9));
            b.setBackground(dlg.cardBg());
            b.setOnClickListener(v -> open(ctx, url));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dlg.dp(50), 1f);
            if (i > 0) lp.setMarginStart(dlg.dp(8));
            bar.addView(b, lp);
        }
        return bar;
    }

    /** A drawable from the module's own resources, in its own colours (no tint). */
    private static android.graphics.drawable.Drawable moduleDrawable(int res) {
        try {
            return ps.reso.instaeclipse.xposedcompat.ModuleResources.get().getDrawable(res, null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String join(List<String> parts, String sep) {
        return parts.isEmpty() ? null : String.join(sep, parts);
    }

    /** One "label / value" row; tap copies the value, long-press opens {@code link}. */
    private static int row(IeDialog dlg, Context ctx, int labelRes, String value, String link) {
        if (value == null || value.isEmpty()) return 0;
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dlg.dp(16), dlg.dp(10), dlg.dp(14), dlg.dp(11));
        box.setBackground(dlg.cardBg());
        TextView label = new TextView(ctx);
        label.setText(I18n.t(ctx, labelRes));
        label.setTextColor(dlg.accent);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setPadding(0, 0, 0, dlg.dp(2));
        TextView val = new TextView(ctx);
        val.setText(value);
        val.setTextColor(dlg.text);
        val.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        val.setMaxLines(8);
        box.addView(label);
        box.addView(val);
        box.setOnClickListener(v -> copy(ctx, value));
        if (link != null) box.setOnLongClickListener(v -> { open(ctx, link); return true; });
        dlg.view(box);
        return 1;
    }

    // ── Quiz / polls ─────────────────────────────────────────────────────────

    private static void showQuiz(Context ctx, Object media) {
        IeDialog dlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_btn_quiz_answer));
        for (Object quizItem : list(media, "story_quizs")) {
            Object quiz = find(quizItem, "quiz_sticker");
            if (quiz == null) quiz = quizItem;
            String question = str(quiz, "question");
            if (question != null) dlg.view(heading(dlg, ctx, question));
            Long correct = num(find(quiz, "correct_answer"));
            Long mine = num(find(quiz, "viewer_answer"));
            Object tallies = find(quiz, "tallies");
            if (!(tallies instanceof List<?> opts)) continue;
            long total = total(opts);
            for (int i = 0; i < opts.size(); i++) {
                Object o = opts.get(i);
                String text = str(o, "text");
                if (text == null) text = "#" + (i + 1);
                Long count = num(find(o, "count"));
                boolean ok = correct != null && correct == i;
                boolean picked = mine != null && mine == i;
                int fill = ok ? 0x8C30D158 : picked ? withAlpha(dlg.accent, 0x8C) : withAlpha(dlg.text, 0x22);
                dlg.view(bar(dlg, ctx, (ok ? "✓  " : "") + text + (picked ? "  •" : ""),
                        votes(count, total), fraction(count, total), fill, ok, text));
            }
            if (mine != null) dlg.view(note(dlg, ctx, "•  " + I18n.t(ctx, R.string.ig_si_your_answer)));
        }
        dlg.show();
    }

    private static void showPolls(Context ctx, Object media) {
        IeDialog dlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_btn_poll_results));
        for (Object pollItem : list(media, "story_polls")) {
            Object poll = find(pollItem, "poll_sticker");
            if (poll == null) poll = pollItem;
            String question = str(poll, "question");
            if (question != null) dlg.view(heading(dlg, ctx, question));
            Long mine = num(find(poll, "viewer_vote"));
            Object tallies = find(poll, "tallies");
            if (!(tallies instanceof List<?> opts)) continue;
            long total = total(opts);
            long best = 0;
            for (Object o : opts) { Long c = num(find(o, "count")); if (c != null && c > best) best = c; }
            for (int i = 0; i < opts.size(); i++) {
                Object o = opts.get(i);
                String text = str(o, "text");
                if (text == null) text = "#" + (i + 1);
                Long count = num(find(o, "count"));
                boolean picked = mine != null && mine == i;
                boolean leading = count != null && best > 0 && count == best;
                int fill = picked ? withAlpha(dlg.accent, 0x8C) : withAlpha(dlg.text, leading ? 0x33 : 0x1C);
                dlg.view(bar(dlg, ctx, (picked ? "✓  " : "") + text, votes(count, total),
                        fraction(count, total), fill, leading, text));
            }
            if (total > 0) dlg.view(note(dlg, ctx, votesLine(ctx, total)));
        }
        dlg.show();
    }

    private static void showSliders(Context ctx, Object media) {
        IeDialog dlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_btn_slider_results));
        for (Object sliderItem : list(media, "story_sliders")) {
            Object slider = find(sliderItem, "slider_sticker");
            if (slider == null) slider = sliderItem;
            String question = str(slider, "question");
            String emoji = str(slider, "emoji");
            dlg.view(heading(dlg, ctx, (emoji == null ? "" : emoji + "  ") + (question == null ? "" : question)));
            Object avg = find(slider, "slider_vote_average");
            Long count = num(find(slider, "slider_vote_count"));
            Object mine = find(slider, "viewer_vote");
            String e = emoji == null ? "" : emoji + "  ";
            if (avg instanceof Number a && (count == null || count > 0)) {
                int p = (int) Math.round(a.doubleValue() * 100);
                dlg.view(bar(dlg, ctx, e + I18n.t(ctx, R.string.ig_si_average), p + "%",
                        a.doubleValue(), withAlpha(dlg.text, 0x33), true, p + "%"));
            }
            if (mine instanceof Number m && m.doubleValue() >= 0) {
                int p = (int) Math.round(m.doubleValue() * 100);
                dlg.view(bar(dlg, ctx, e + I18n.t(ctx, R.string.ig_si_your_answer), p + "%",
                        m.doubleValue(), withAlpha(dlg.accent, 0x8C), false, p + "%"));
            }
            if (count != null) dlg.view(note(dlg, ctx, votesLine(ctx, count)));
        }
        dlg.show();
    }

    private static long total(List<?> opts) {
        long total = 0;
        for (Object o : opts) { Long c = num(find(o, "count")); if (c != null) total += c; }
        return total;
    }

    private static double fraction(Long count, long total) {
        return count == null || total <= 0 ? 0 : (double) count / total;
    }

    private static String votes(Long count, long total) {
        if (count == null) return "";
        return total > 0 ? Math.round(count * 100.0 / total) + "%  ·  " + count : String.valueOf(count);
    }

    private static String votesLine(Context ctx, long n) {
        String w = I18n.t(ctx, R.string.ig_si_votes);
        return (w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1)) + ": " + n;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (alpha << 24);
    }

    /** A result row whose background fills to {@code frac} (0..1), like Instagram's own poll sticker. */
    private static android.view.View bar(IeDialog dlg, Context ctx, String left, String right,
                                         double frac, int fill, boolean bold, String copyText) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dlg.dp(16), dlg.dp(13), dlg.dp(16), dlg.dp(13));
        android.graphics.drawable.ClipDrawable filled = new android.graphics.drawable.ClipDrawable(
                dlg.round(fill, 14), Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL);
        filled.setLevel((int) Math.round(Math.max(0, Math.min(1, frac)) * 10000));
        row.setBackground(new android.graphics.drawable.LayerDrawable(
                new android.graphics.drawable.Drawable[]{dlg.round(dlg.field, 14), filled}));

        TextView l = new TextView(ctx);
        l.setText(left);
        l.setTextColor(dlg.text);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        if (bold) l.setTypeface(null, Typeface.BOLD);
        row.addView(l, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView r = new TextView(ctx);
        r.setText(right);
        r.setTextColor(dlg.text);
        r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        r.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.setMarginStart(dlg.dp(10));
        row.addView(r, rp);

        row.setOnClickListener(v -> copy(ctx, copyText));
        return row;
    }

    private static TextView note(IeDialog dlg, Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(dlg.sub);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(dlg.dp(4), 0, dlg.dp(4), dlg.dp(4));
        return t;
    }

    private static TextView heading(IeDialog dlg, Context ctx, String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(dlg.text);
        t.setTypeface(null, Typeface.BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(dlg.dp(4), dlg.dp(6), dlg.dp(4), dlg.dp(2));
        return t;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void copy(Context ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("story", text));
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_si_copied), Toast.LENGTH_SHORT).show();
    }

    private static void open(Context ctx, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (url.startsWith("https://www.instagram.com/")) i.setPackage(ctx.getPackageName());
            ctx.startActivity(i);
        } catch (Throwable ignored) {}
    }

    /** Usernames of every User found in a raw model graph (User objects or JSON-keyed user maps). */
    private static List<String> usernames(Object root) {
        List<String> out = new ArrayList<>();
        collectUsers(root, 0, out, Collections.newSetFromMap(new IdentityHashMap<>()));
        if (out.isEmpty()) {
            Object t = tree(root, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
            collectUsernameKeys(t, out);
        }
        return out;
    }

    private static void collectUsers(Object o, int depth, List<String> out, Set<Object> seen) {
        if (o == null || depth > 5 || o instanceof CharSequence || o instanceof Number || o instanceof Boolean) return;
        if (!seen.add(o)) return;
        if (o.getClass().getName().equals("com.instagram.user.model.User")) {
            String u = ps.reso.instaeclipse.utils.users.UserUtils.callUsernameGetter(o);
            if (u != null && !u.isEmpty() && !out.contains(u)) out.add(u);
            return;
        }
        if (o instanceof Collection<?> c) { for (Object v : c) collectUsers(v, depth + 1, out, seen); return; }
        if (o instanceof Map<?, ?> m) { for (Object v : m.values()) collectUsers(v, depth + 1, out, seen); return; }
        String n = o.getClass().getName();
        if (!n.startsWith("com.instagram.") && !n.startsWith("X.")) return;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try { f.setAccessible(true); collectUsers(f.get(o), depth + 1, out, seen); } catch (Throwable ignored) {}
            }
    }

    private static void collectUsernameKeys(Object t, List<String> out) {
        if (t instanceof Map<?, ?> m) {
            Object u = m.get("username");
            if (u instanceof String s && !s.isEmpty() && !out.contains(s)) out.add(s);
            for (Object v : m.values()) collectUsernameKeys(v, out);
        } else if (t instanceof List<?> l) {
            for (Object v : l) collectUsernameKeys(v, out);
        }
    }
}
