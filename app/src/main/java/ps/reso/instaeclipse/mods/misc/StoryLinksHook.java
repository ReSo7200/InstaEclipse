package ps.reso.instaeclipse.mods.misc;

import android.app.AndroidAppHelper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Story link stickers: when the open story has link stickers, the story ⋯ menu gets
 * "Copy Link" (one link) or "Story Links (n)" (several, listed in a dialog).
 *
 * Links come from Media's paramCount-0 List getters guarded by the "story_link_stickers" /
 * "story_cta" JSON keys (same idea as StoryMentionHook's "reel_mentions"), and the URLs are
 * collected by walking the sticker objects for http(s) strings, so no obfuscated names are used.
 */
public class StoryLinksHook {

    private static final String[] KEYS = {"story_link_stickers", "story_cta"};
    private static final List<Method> getters = new ArrayList<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void install(DexKitBridge bridge, ClassLoader classLoader) {
        resolveGetters(bridge, classLoader);
        if (getters.isEmpty()) {
            ModuleLog.line("(IE|StoryLinks) ❌ link getters not found");
            return;
        }
        installButtonHook(bridge, classLoader);
        installClickHook(bridge, classLoader);
        FeatureStatusTracker.setHooked("StoryLinks");
    }

    // ── DexKit ────────────────────────────────────────────────────────────────

    private static void resolveGetters(DexKitBridge bridge, ClassLoader classLoader) {
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods("StoryLinkGetters", classLoader);
            if (cached != null && !cached.isEmpty()) {
                for (Method m : cached) m.setAccessible(true);
                getters.addAll(cached);
                return;
            }
        }
        for (String key : KEYS) {
            try {
                List<MethodData> found = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .declaredClass("com.instagram.feed.media.Media")
                                .paramCount(0)
                                .usingEqStrings(List.of(key))));
                for (MethodData md : found) {
                    if (md.getName().equals("<clinit>")) continue;
                    Method m = md.getMethodInstance(classLoader);
                    if (!List.class.isAssignableFrom(m.getReturnType())) continue;
                    m.setAccessible(true);
                    getters.add(m);
                    break;
                }
            } catch (Throwable t) {
                ModuleLog.line("(IE|StoryLinks) getter " + key + ": " + t);
            }
        }
        if (!getters.isEmpty()) DexKitCache.saveMethods("StoryLinkGetters", getters);
    }

    // ── Hook 1: add the menu entry only when the story has links ─────────────

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
                ModuleLog.line("(IE|StoryLinks) ❌ builder DexKit: " + t);
            }
        }
        if (builders == null || builders.isEmpty()) {
            ModuleLog.line("(IE|StoryLinks) ❌ builder not found");
            return;
        }

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                try {
                    if (!FeatureFlags.storyLinks) return;
                    CharSequence[] original = (CharSequence[]) param.getResult();
                    if (original == null) return;
                    Object media = findMedia(param);
                    List<String> links = media != null ? linksOf(media) : Collections.emptyList();
                    if (links.isEmpty()) return;
                    String label = label(links.size());
                    for (CharSequence cs : original) if (label.contentEquals(cs)) return;
                    CharSequence[] extended = new CharSequence[original.length + 1];
                    System.arraycopy(original, 0, extended, 0, original.length);
                    extended[original.length] = label;
                    param.setResult(extended);
                } catch (Throwable t) {
                    ModuleLog.line("(IE|StoryLinks) builder: " + t);
                }
            }
        };
        int hooked = 0;
        for (Method m : builders) {
            try { XposedBridge.hookMethod(m, hook); hooked++; } catch (Throwable ignored) {}
        }
        ModuleLog.line("(IE|StoryLinks) ✅ button hook on " + hooked + " builder(s)");
    }

    // ── Hook 2: handle the tap ───────────────────────────────────────────────

    private void installClickHook(DexKitBridge bridge, ClassLoader classLoader) {
        List<Method> dispatchers = ps.reso.instaeclipse.mods.media.StoryDownloadHook.storyClickDispatchers(bridge, classLoader);
        if (dispatchers.isEmpty()) {
            ModuleLog.line("(IE|StoryLinks) ❌ click handler not found");
            return;
        }
        try {
            XC_MethodHook clickHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    try {
                        if (!FeatureFlags.storyLinks) return;
                        CharSequence tapped = null;
                        for (Object a : param.args) if (a instanceof CharSequence cs) { tapped = cs; break; }
                        if (tapped == null) return;
                        String t = tapped.toString();
                        Context app = AndroidAppHelper.currentApplication();
                        if (!t.equals(I18n.t(app, R.string.ig_btn_copy_story_link))
                                && !t.startsWith(I18n.t(app, R.string.ig_story_links_title))) return;

                        Object media = findMedia(param);
                        Context ctx = StoryMentionHook.findContext(param.thisObject);
                        for (Object a : param.args) if (ctx == null) ctx = StoryMentionHook.findContext(a);
                        if (ctx == null) ctx = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
                        if (media == null || ctx == null) return; // let IG handle it; nothing we can do
                        List<String> links = linksOf(media);
                        if (links.isEmpty()) return;
                        if (!t.equals(label(links.size()))) return;

                        param.setResult(null);
                        Context c = ctx;
                        mainHandler.post(() -> {
                            if (links.size() == 1) copy(c, links.get(0), I18n.t(c, R.string.ig_toast_link_copied));
                            else showLinks(c, links);
                        });
                    } catch (Throwable t) {
                        ModuleLog.line("(IE|StoryLinks) click: " + t);
                    }
                }
            };
            for (Method m : dispatchers) XposedBridge.hookMethod(m, clickHook);
            ModuleLog.line("(IE|StoryLinks) ✅ click hook installed");
        } catch (Throwable t) {
            ModuleLog.line("(IE|StoryLinks) ❌ click hook: " + t);
        }
    }

    // ── Link extraction ──────────────────────────────────────────────────────

    private static Object findMedia(XC_MethodHook.MethodHookParam param) {
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Object media = param.thisObject != null ? StoryMentionHook.findMediaInGraph(param.thisObject, 0, visited) : null;
        for (Object a : param.args) {
            if (media != null) break;
            if (a != null) media = StoryMentionHook.findMediaInGraph(a, 0, visited);
        }
        return media;
    }

    static List<String> linksOf(Object media) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (Method g : getters) {
            try {
                Object r = g.invoke(media);
                if (r instanceof List<?> list)
                    for (Object sticker : list)
                        collectUrls(sticker, 0, out, Collections.newSetFromMap(new IdentityHashMap<>()));
            } catch (Throwable ignored) {}
        }
        return new ArrayList<>(out);
    }

    private static void collectUrls(Object o, int depth, Set<String> out, Set<Object> seen) {
        if (o == null || depth > 4) return;
        if (o instanceof CharSequence cs) {
            String s = unwrap(cs.toString().trim());
            if (s != null) out.add(s);
            return;
        }
        if (o instanceof Number || o instanceof Boolean || o.getClass().isPrimitive() || o.getClass().isEnum()) return;
        if (!seen.add(o)) return;
        if (o instanceof Map<?, ?> m) {
            for (Object v : m.values()) collectUrls(v, depth + 1, out, seen);
            return;
        }
        if (o instanceof Collection<?> c) {
            for (Object v : c) collectUrls(v, depth + 1, out, seen);
            return;
        }
        String n = o.getClass().getName();
        if (!n.startsWith("com.instagram.") && !n.startsWith("X.") && !n.startsWith("com.facebook.")) return;
        for (Class<?> cls = o.getClass(); cls != null && cls != Object.class; cls = cls.getSuperclass()) {
            for (Field f : cls.getDeclaredFields()) {
                if (f.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    collectUrls(f.get(o), depth + 1, out, seen);
                } catch (Throwable ignored) {}
            }
        }
    }

    /** The real target of an http(s) string; l.instagram.com redirects are unwrapped. */
    private static String unwrap(String s) {
        if (!s.startsWith("http://") && !s.startsWith("https://")) return null;
        try {
            Uri u = Uri.parse(s);
            String host = u.getHost();
            if (host != null && host.endsWith("l.instagram.com")) {
                String target = u.getQueryParameter("u");
                if (target != null && target.startsWith("http")) return stripFbclid(target);
            }
            // image/CDN urls inside the sticker model are not links the user put on the story
            if (host != null && (host.contains("cdninstagram.com") || host.contains("fbcdn.net"))) return null;
        } catch (Throwable ignored) {}
        return stripFbclid(s);
    }

    /** IG appends its own fbclid tracking tag to sticker links; the user's link is everything else. */
    private static String stripFbclid(String url) {
        try {
            Uri u = Uri.parse(url);
            if (u.getQueryParameter("fbclid") == null) return url;
            Uri.Builder b = u.buildUpon().clearQuery();
            for (String name : u.getQueryParameterNames()) {
                if (name.equals("fbclid")) continue;
                for (String v : u.getQueryParameters(name)) b.appendQueryParameter(name, v);
            }
            return b.build().toString();
        } catch (Throwable ignored) {
            return url;
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    private static String label(int count) {
        Context app = AndroidAppHelper.currentApplication();
        return count == 1 ? I18n.t(app, R.string.ig_btn_copy_story_link)
                : I18n.t(app, R.string.ig_story_links_title) + " (" + count + ")";
    }

    private static void copy(Context ctx, String text, String toast) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("link", text));
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, toast, Toast.LENGTH_SHORT).show();
    }

    private static void showLinks(Context ctx, List<String> links) {
        try {
            IeDialog dlg = new IeDialog(ctx)
                    .title(I18n.t(ctx, R.string.ig_story_links_title))
                    .message(I18n.t(ctx, R.string.ig_story_links_hint));
            for (String url : links) {
                TextView row = new TextView(ctx);
                row.setText(url);
                row.setTextColor(dlg.text);
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                row.setMaxLines(3);
                row.setEllipsize(android.text.TextUtils.TruncateAt.END);
                row.setPadding(dlg.dp(16), dlg.dp(13), dlg.dp(14), dlg.dp(13));
                row.setBackground(dlg.cardBg());
                row.setOnClickListener(v -> copy(ctx, url, I18n.t(ctx, R.string.ig_toast_link_copied)));
                row.setOnLongClickListener(v -> {
                    try {
                        ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Throwable ignored) {}
                    return true;
                });
                dlg.view(row);
            }
            dlg.primary(I18n.t(ctx, R.string.ig_mention_copy_all),
                    () -> copy(ctx, String.join("\n", links), I18n.t(ctx, R.string.ig_toast_links_copied)));
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.line("(IE|StoryLinks) dialog: " + t);
        }
    }
}
