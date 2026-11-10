package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.dialog.DialogUtils;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Voice messages of a chat (#307): a "Voice messages" row on the chat details page (and in the
 * module's thread header menu) opens a list of the chat's voice messages with sender and time;
 * tap to play, or download.
 *
 * - Chat details page = the fragment whose code uses "ThreadDetailHomePageFragment" and
 *   "shortcutsContainer" (X.0HEF on 452.0.0.33). After its onViewCreated the row is added right
 *   above the shared media section (resource id shared_section), else at the end of
 *   thread_details_header. Resource names, not obfuscated names.
 * - The chat comes from the page's own arguments (a ThreadIdParcelable wrapping the id), falling
 *   back to the chat that was open right before.
 * - Messages: the thread's live message store and IG's own message JSON, shared with chat export
 *   ({@link ChatExportHook}); voice items are item_type "voice_media" with an audio_src URL. Only
 *   messages the chat has loaded are known.
 * - Playback: Android MediaPlayer streaming the audio URL; released when the list closes.
 */
public class VoiceMessagesHook {

    private static final String TAG = "(IE|VoiceList) ";
    private static final String ROW_TAG = "ie_voice_messages_row";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** The last chat that was open (the details page sits on top of it). */
    private static volatile String lastThreadId;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        CurrentThreadTracker.install(cl);
        CurrentThreadTracker.addListener((activity, threadId) -> {
            lastThreadId = threadId;
            if (!FeatureFlags.voiceMessagesList) return;
            final int[] tries = {0};
            MAIN.postDelayed(new Runnable() {
                @Override public void run() {
                    if (addToHeaderMenu(activity)) return;
                    if (++tries[0] < 15) MAIN.postDelayed(this, 300);
                }
            }, 300);
        });
        try {
            Class<?> fragmentBase = Class.forName("androidx.fragment.app.Fragment", false, cl);
            Class<?> page = null;
            for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("ThreadDetailHomePageFragment")))) {
                try {
                    Class<?> c = cl.loadClass(md.getClassName());
                    if (fragmentBase.isAssignableFrom(c)) { page = c; break; }
                } catch (Throwable ignored) {}
            }
            if (page == null) { ModuleLog.line(TAG + "❌ chat details page not found (header menu only)"); return; }
            Method onViewCreated = null;
            for (Method m : ps.reso.instaeclipse.utils.core.SafeReflect.methods(page)) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getName().equals("onViewCreated") && p.length == 2 && p[0] == View.class && p[1] == Bundle.class) onViewCreated = m;
            }
            if (onViewCreated == null) { ModuleLog.line(TAG + "❌ " + page.getName() + ".onViewCreated not found"); return; }
            XposedBridge.hookMethod(onViewCreated, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.voiceMessagesList) return;
                    try {
                        View root = (View) param.args[0];
                        String id = threadIdOf(param.thisObject);
                        String fallback = CurrentThreadTracker.currentThreadId();
                        if (fallback == null) fallback = lastThreadId;
                        final String f = fallback;
                        // IG fills the sections right after onViewCreated: add the row once laid out.
                        root.post(() -> injectRow(root, id, f));
                    } catch (Throwable t) {
                        ModuleLog.line(TAG + "⚠️ details page: " + t);
                    }
                }
            });
            FeatureStatusTracker.setHooked("VoiceMessagesList");
            ModuleLog.line(TAG + "✅ chat details page " + page.getName());
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    // ── entry points ─────────────────────────────────────────────────────────

    /** "Voice messages" in the module's thread header menu. True once added (or not placeable). */
    private static boolean addToHeaderMenu(Activity a) {
        try {
            if (!FeatureFlags.voiceMessagesList) return true;
            int headerId = a.getResources().getIdentifier("direct_thread_header", "id", a.getPackageName());
            if (headerId == 0 || a.findViewById(headerId) == null) return false;
            if (ThreadHeaderMenu.has(a, "voice")) return true;
            return ThreadHeaderMenu.add(a, "voice", I18n.t(a, R.string.ig_voice_messages), () -> {
                String id = CurrentThreadTracker.currentThreadId();
                if (id == null) try { id = UnsentThreadButtonHook.openThread.get(); } catch (Throwable ignored) {}
                show(a, id, null, ConversationMenuHook.chatTitle(a));
            });
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ header menu: " + t);
            return true;
        }
    }

    @SuppressLint("DiscouragedApi")
    private static void injectRow(View root, String threadId, String fallbackId) {
        try {
            if (!FeatureFlags.voiceMessagesList || !root.isAttachedToWindow() && root.getParent() == null) return;
            if (root.findViewWithTag(ROW_TAG) != null) return;
            Context ctx = root.getContext();
            String pkg = ctx.getPackageName();
            ViewGroup parent = null;
            int index = -1;
            int sharedId = ctx.getResources().getIdentifier("shared_section", "id", pkg);
            View shared = sharedId != 0 ? root.findViewById(sharedId) : null;
            if (shared != null && shared.getParent() instanceof LinearLayout g) {
                parent = g;
                index = g.indexOfChild(shared);
            } else {
                int headerId = ctx.getResources().getIdentifier("thread_details_header", "id", pkg);
                View header = headerId != 0 ? root.findViewById(headerId) : null;
                if (header instanceof LinearLayout g) parent = g;
            }
            if (parent == null) { ModuleLog.line(TAG + "⚠️ no place for the row on the details page"); return; }

            int titleId = ctx.getResources().getIdentifier("thread_title", "id", pkg);
            View title = titleId != 0 ? root.findViewById(titleId) : null;
            boolean dark = (ctx.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            int textColor = title instanceof TextView tv ? tv.getCurrentTextColor() : (dark ? Color.WHITE : Color.BLACK);
            float d = ctx.getResources().getDisplayMetrics().density;

            LinearLayout row = new LinearLayout(ctx);
            row.setTag(ROW_TAG);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (14 * d));
            row.setClickable(true);
            row.setFocusable(true);
            try {
                TypedValue tv = new TypedValue();
                if (ctx.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true) && tv.resourceId != 0)
                    row.setBackgroundResource(tv.resourceId);
            } catch (Throwable ignored) {}

            ImageView icon = new ImageView(ctx);
            android.graphics.drawable.Drawable mic = DialogUtils.moduleIcon(R.drawable.ic_mic, textColor);
            if (mic != null) icon.setImageDrawable(mic);
            int isz = (int) (24 * d);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(isz, isz);
            ilp.setMarginEnd((int) (14 * d));
            row.addView(icon, ilp);

            TextView label = new TextView(ctx);
            label.setText(I18n.t(ctx, R.string.ig_voice_messages));
            label.setTextColor(textColor);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            ImageView chevron = new ImageView(ctx);
            android.graphics.drawable.Drawable chev = DialogUtils.moduleIcon(R.drawable.ic_chevron_right, textColor);
            if (chev != null) chevron.setImageDrawable(chev);
            int csz = (int) (20 * d);
            row.addView(chevron, new LinearLayout.LayoutParams(csz, csz));

            row.setOnClickListener(v -> {
                Activity a = activityOf(v.getContext());
                if (a != null) show(a, threadId, fallbackId, null);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (index >= 0) parent.addView(row, index, lp); else parent.addView(row, lp);
            ModuleLog.line(TAG + "✅ row added (thread=" + threadId + " fallback=" + fallbackId + ")");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ inject row: " + t);
        }
    }

    // ── list ─────────────────────────────────────────────────────────────────

    private record Voice(String url, String sender, long time, long durationMs, String id) {}

    private static void show(Activity a, String threadId, String fallbackId, String chatName) {
        if (a == null || a.isFinishing()) return;
        new Thread(() -> {
            List<Voice> voices = null;
            boolean found = false;
            try {
                Object store = ChatExportHook.findStore(threadId);
                if (store == null && fallbackId != null && !fallbackId.equals(threadId)) store = ChatExportHook.findStore(fallbackId);
                List<JSONObject> msgs = ChatExportHook.loadedMessages(store);
                if (msgs != null) {
                    found = true;
                    Map<String, String> names = ChatExportHook.participants(store);
                    String self = ChatExportHook.selfId;
                    voices = new ArrayList<>();
                    for (JSONObject j : msgs) {
                        if (!"voice_media".equals(j.optString("item_type", ""))) continue;
                        String[] media = ChatExportHook.mediaForMessage(j, "voice_media");
                        if (media == null || !media[0].startsWith("https://")) continue;
                        String uid = j.optString("user_id", "");
                        String sender = uid.equals(self) ? I18n.t(a, R.string.ig_voice_you) : names.getOrDefault(uid, "");
                        long dur = 0;
                        try { dur = (long) Double.parseDouble(find(j.opt("voice_media"), "duration", 0)); } catch (Throwable ignored) {}
                        voices.add(new Voice(media[0], sender, ChatExportHook.timestampMs(j), dur, j.optString("item_id", "")));
                    }
                    Collections.reverse(voices); // newest first
                }
                ModuleLog.line(TAG + "list thread=" + threadId + " fallback=" + fallbackId + " store=" + (store != null)
                        + " voices=" + (voices != null ? voices.size() : -1));
            } catch (Throwable t) {
                ModuleLog.line(TAG + "⚠️ list: " + t);
            }
            final List<Voice> fv = voices;
            final boolean ok = found;
            a.runOnUiThread(() -> {
                if (a.isFinishing() || a.isDestroyed()) return;
                if (!ok) {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_voice_messages_no_chat), Toast.LENGTH_LONG).show();
                    return;
                }
                showList(a, fv, chatName);
            });
        }, "ie-voice-list").start();
    }

    private static void showList(Activity a, List<Voice> voices, String chatName) {
        IeDialog dlg = new IeDialog(a).title(chatName != null && !chatName.isEmpty()
                ? I18n.t(a, R.string.ig_voice_messages) + " · " + chatName : I18n.t(a, R.string.ig_voice_messages));
        Player player = new Player(a);
        TextView note = new TextView(a);
        note.setText(I18n.t(a, voices.isEmpty() ? R.string.ig_voice_messages_empty : R.string.ig_voice_messages_loaded));
        note.setTextColor(dlg.sub);
        note.setTextSize(13);
        dlg.view(note);
        SimpleDateFormat fmt = new SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.getDefault());
        for (Voice v : voices) dlg.view(row(a, dlg, player, v, fmt));
        dlg.onDismiss(player::release);
        try {
            Dialog shown = dlg.show();
            shown.setOwnerActivity(a);
        } catch (Throwable t) {
            player.release();
            ModuleLog.line(TAG + "⚠️ show: " + t);
        }
    }

    private static View row(Activity a, IeDialog dlg, Player player, Voice v, SimpleDateFormat fmt) {
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dlg.dp(12), dlg.dp(10), dlg.dp(12), dlg.dp(10));
        card.setBackground(dlg.cardBg());

        LinearLayout top = new LinearLayout(a);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView play = new TextView(a);
        play.setText("▶");
        play.setTextColor(Color.WHITE);
        play.setTextSize(15);
        play.setGravity(Gravity.CENTER);
        play.setBackground(dlg.round(dlg.accent, 18));
        int psz = dlg.dp(36);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(psz, psz);
        plp.setMarginEnd(dlg.dp(12));
        top.addView(play, plp);

        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        TextView sender = new TextView(a);
        sender.setText(v.sender().isEmpty() ? "?" : v.sender());
        sender.setTextColor(dlg.accent);
        sender.setTextSize(14);
        sender.setTypeface(Typeface.DEFAULT_BOLD);
        sender.setSingleLine(true);
        sender.setEllipsize(android.text.TextUtils.TruncateAt.END);
        info.addView(sender);
        TextView when = new TextView(a);
        String t = v.time() > 0 ? fmt.format(new Date(v.time())) : "";
        if (v.durationMs() > 0) t = (t.isEmpty() ? "" : t + " · ") + clock(v.durationMs());
        when.setText(t);
        when.setTextColor(dlg.sub);
        when.setTextSize(12);
        info.addView(when);
        top.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageView save = new ImageView(a);
        android.graphics.drawable.Drawable dl = DialogUtils.moduleIcon(R.drawable.ic_download, dlg.sub);
        if (dl != null) save.setImageDrawable(dl);
        save.setPadding(dlg.dp(8), dlg.dp(8), dlg.dp(8), dlg.dp(8));
        save.setContentDescription(I18n.t(a, R.string.ig_download_choice_title));
        save.setOnClickListener(x -> ps.reso.instaeclipse.mods.media.VoiceMessageDownloadHook.saveVoice(a, v.url(),
                v.sender().isEmpty() || v.sender().equals(I18n.t(a, R.string.ig_voice_you)) ? null : v.sender(),
                v.id().isEmpty() ? null : v.id()));
        top.addView(save, new LinearLayout.LayoutParams(dlg.dp(40), dlg.dp(40)));
        card.addView(top);

        SeekBar bar = new SeekBar(a);
        bar.setVisibility(View.GONE);
        try {
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(dlg.accent));
            bar.setThumbTintList(android.content.res.ColorStateList.valueOf(dlg.accent));
        } catch (Throwable ignored) {}
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dlg.dp(6);
        card.addView(bar, blp);

        View.OnClickListener toggle = x -> player.toggle(v.url(), play, bar);
        play.setOnClickListener(toggle);
        card.setOnClickListener(toggle);
        return card;
    }

    /** One MediaPlayer for the open list: one clip plays at a time, released when the list closes. */
    private static final class Player {
        private final Activity activity;
        private MediaPlayer mp;
        private String url;
        private TextView button;
        private SeekBar bar;
        private boolean prepared;
        private final Runnable tick = new Runnable() {
            @Override public void run() {
                try {
                    if (mp != null && prepared && bar != null) {
                        bar.setMax(Math.max(1, mp.getDuration()));
                        bar.setProgress(mp.getCurrentPosition());
                        if (mp.isPlaying()) MAIN.postDelayed(this, 200);
                    }
                } catch (Throwable ignored) {}
            }
        };

        Player(Activity a) { activity = a; }

        void toggle(String u, TextView btn, SeekBar sb) {
            try {
                if (mp != null && u.equals(url)) {
                    if (!prepared) return;
                    if (mp.isPlaying()) { mp.pause(); btn.setText("▶"); }
                    else { mp.start(); btn.setText("❚❚"); MAIN.post(tick); }
                    return;
                }
                stop();
                url = u; button = btn; bar = sb;
                btn.setText("…");
                sb.setVisibility(View.VISIBLE);
                sb.setProgress(0);
                sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                        try { if (fromUser && mp != null && prepared && sb == bar) mp.seekTo(p); } catch (Throwable ignored) {}
                    }
                    @Override public void onStartTrackingTouch(SeekBar s) {}
                    @Override public void onStopTrackingTouch(SeekBar s) {}
                });
                MediaPlayer p = new MediaPlayer();
                mp = p;
                p.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                p.setDataSource(u);
                p.setOnPreparedListener(x -> {
                    if (mp != x) return;
                    prepared = true;
                    x.start();
                    btn.setText("❚❚");
                    MAIN.post(tick);
                });
                p.setOnCompletionListener(x -> {
                    if (mp != x) return;
                    btn.setText("▶");
                    sb.setProgress(0);
                    try { x.seekTo(0); } catch (Throwable ignored) {}
                });
                p.setOnErrorListener((x, what, extra) -> {
                    ModuleLog.line(TAG + "⚠️ playback error " + what + "/" + extra);
                    if (mp == x) stop();
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(activity, I18n.t(activity, R.string.ig_voice_play_failed), Toast.LENGTH_SHORT).show();
                    return true;
                });
                p.prepareAsync();
            } catch (Throwable t) {
                ModuleLog.line(TAG + "⚠️ play: " + t);
                stop();
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(activity, I18n.t(activity, R.string.ig_voice_play_failed), Toast.LENGTH_SHORT).show();
            }
        }

        void stop() {
            MAIN.removeCallbacks(tick);
            MediaPlayer p = mp;
            mp = null;
            prepared = false;
            url = null;
            if (button != null) button.setText("▶");
            if (bar != null) { bar.setProgress(0); bar.setVisibility(View.GONE); bar.setOnSeekBarChangeListener(null); }
            button = null;
            bar = null;
            if (p != null) try { p.release(); } catch (Throwable ignored) {}
        }

        void release() { stop(); }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** The chat id in the details page's own arguments (a ThreadIdParcelable / thread key holding
     *  the numeric id), or null. */
    private static String threadIdOf(Object fragment) {
        try {
            Field f = null;
            for (Class<?> c = fragment.getClass(); c != null && f == null; c = c.getSuperclass())
                try { f = c.getDeclaredField("mArguments"); } catch (NoSuchFieldException ignored) {}
            if (f == null) return null;
            f.setAccessible(true);
            if (!(f.get(fragment) instanceof Bundle args)) return null;
            args.setClassLoader(fragment.getClass().getClassLoader());
            String best = null;
            for (String k : args.keySet()) {
                Object v = args.get(k);
                if (v == null) continue;
                String n = v.getClass().getName();
                if (!n.contains("ThreadId") && !n.contains("ThreadKey") && !n.contains("ThreadTarget")
                        && !k.endsWith("THREAD_ID")) continue;
                String id = numericId(v, 0, Collections.newSetFromMap(new IdentityHashMap<>()));
                if (id != null) { if (k.endsWith("THREAD_ID")) return id; if (best == null) best = id; }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String numericId(Object o, int depth, Set<Object> seen) {
        if (o == null || depth > 3 || !seen.add(o)) return null;
        if (o instanceof String s) return s.length() >= 10 && s.chars().allMatch(Character::isDigit) ? s : null;
        String cn = o.getClass().getName();
        if (!cn.startsWith("X.") && !cn.startsWith("com.instagram.")) return null;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    String r = numericId(f.get(o), depth + 1, seen);
                    if (r != null) return r;
                } catch (Throwable ignored) {}
            }
        return null;
    }

    /** First scalar under {@code key} in a JSON subtree, or "". */
    private static String find(Object o, String key, int d) {
        if (o == null || d > 6) return "";
        if (o instanceof JSONObject jo) {
            Object v = jo.opt(key);
            if (v != null && v != JSONObject.NULL && !(v instanceof JSONObject) && !(v instanceof JSONArray)) return String.valueOf(v);
            for (Iterator<String> it = jo.keys(); it.hasNext(); ) {
                String r = find(jo.opt(it.next()), key, d + 1);
                if (!r.isEmpty()) return r;
            }
        } else if (o instanceof JSONArray ja) {
            for (int i = 0; i < ja.length() && i < 20; i++) {
                String r = find(ja.opt(i), key, d + 1);
                if (!r.isEmpty()) return r;
            }
        }
        return "";
    }

    private static String clock(long ms) {
        long s = Math.round(ms / 1000.0);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private static Activity activityOf(Context c) {
        while (c instanceof ContextWrapper) {
            if (c instanceof Activity a) return a;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return null;
    }
}
