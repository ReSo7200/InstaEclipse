package ps.reso.instaeclipse.mods.misc;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.IeDialog;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;
import ps.reso.instaeclipse.utils.users.WatchlistStore;

/**
 * Profile analyzer: watch another profile's Followers and Following lists for changes.
 *
 * <p>"Analyze profile" in a profile's ⋮ menu (or the "Analyze" pill on a profile's Followers /
 * Following tab) starts an analysis of that profile. From the menu, the profile header's followers
 * count is tapped to open the follow list screen. A driver then waits for the profile's Followers tab
 * to be on screen and loads it to the end with IG's own pager (FollowListExportHook.loadAll, the same
 * as Export CSV), switches to the Following tab (the UnifiedFollowFragment's ViewPager2) and loads it
 * too. Only when both lists loaded to the end are they compared with the last snapshot
 * (WatchlistStore) and the result shown. A private account you don't follow has no list to open, so
 * the analysis says so instead.
 */
public final class ProfileAnalyzer {

    private static final String TAG = "(IE|ProfileAnalyzer) ";
    private static final String PILL_TAG = "ie_profile_analyze";
    private static final int GREEN = 0xFF34C759, RED = 0xFFFF453A;
    private static final Handler H = new Handler(Looper.getMainLooper());

    /** One analysis in progress. */
    private static final class Session {
        final String id;
        String username, self;
        final boolean fromMenu;
        final long started = SystemClock.uptimeMillis();
        long lastSeen, warmSince, nextTabAt;
        boolean seen, busy, hinted;
        int tabTries;
        Map<String, String[]> followers, following;
        Context ctx;

        Session(String id, String username, boolean fromMenu) {
            this.id = id;
            this.username = username;
            this.fromMenu = fromMenu;
        }

        String name() { return username != null ? username : id; }
    }

    private static volatile Session session;

    private ProfileAnalyzer() {}

    // ---- entry points ----

    /** "Analyze profile" in the ⋮ menu of {@code username}'s profile ({@code user}: its User model). */
    public static void start(Activity a, Object user, String username) {
        if (!FeatureFlags.profileAnalyzer || a == null) return;
        String id = user != null ? FollowListExportHook.idOf(user) : null;
        if (id == null) {
            IeToast.makeText(a, I18n.t(a, R.string.ig_pa_no_id), Toast.LENGTH_SHORT).show();
            return;
        }
        Context app = a.getApplicationContext();
        String self = WatchlistStore.self(app);
        if (self != null) new Thread(() -> WatchlistStore.add(app, self, id, username)).start();
        Session s = begin(a, id, username, true);
        if (!openFollowers(a)) {
            ModuleLog.line(TAG + "followers count not found / not tappable on @" + username);
            end(s);
            cantOpen(a, s.name());
        }
    }

    private static Session begin(Context ctx, String id, String username, boolean fromMenu) {
        Session s = new Session(id, username, fromMenu);
        s.ctx = ctx;
        session = s;
        ModuleLog.line(TAG + "analyze " + id + " (@" + username + ")" + (fromMenu ? " from menu" : " from list"));
        H.postDelayed(() -> drive(s), fromMenu ? 900 : 0);
        return s;
    }

    private static void end(Session s) {
        if (session == s) session = null;
    }

    /** The "Analyze" pill on a profile's Followers / Following tab. */
    static void addPill(View root, boolean historyShown) {
        View host = root;
        while (host != null && !(host instanceof FrameLayout)) host = host.getParent() instanceof View p ? p : null;
        if (!(host instanceof FrameLayout g) || g.findViewWithTag(PILL_TAG) != null) return;
        float d = root.getResources().getDisplayMetrics().density;
        TextView pill = new TextView(root.getContext());
        pill.setTag(PILL_TAG);
        pill.setText("🔍 " + I18n.t(root.getContext(), R.string.ig_pa_analyze));
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(13);
        int ph = (int) (14 * d), pv = (int) (8 * d);
        pill.setPadding(ph, pv, ph, pv);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEEFF9F0A);
        bg.setCornerRadius(18 * d);
        pill.setBackground(bg);
        pill.setElevation(20 * d);
        pill.setOnClickListener(v -> {
            if (FollowListExportHook.isLoading()) { FollowListExportHook.stopLoading(); return; }
            Object[] vis = FollowListExportHook.visibleList();
            FollowListExportHook.ListInfo info = vis != null ? FollowListExportHook.listInfo(vis[0]) : null;
            if (info == null || info.type == null) {
                IeToast.makeText(v.getContext(), I18n.t(v.getContext(), R.string.ig_pa_wrong_tab), Toast.LENGTH_SHORT).show();
                return;
            }
            Session cur = session;
            if (cur != null && cur.id.equals(info.owner)) return; // already analyzing this profile
            String name = info.username != null ? info.username : titleOf(v);
            if (name == null && info.self != null) {
                WatchlistStore.Entry e = WatchlistStore.find(WatchlistStore.loadIndex(v.getContext().getApplicationContext(), info.self), info.owner);
                if (e != null) name = e.username;
            }
            begin(v.getContext(), info.owner, name, false);
        });
        // above the other pills (Export CSV at the bottom, Followers history above it)
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        lp.bottomMargin = (int) ((24 + (FeatureFlags.followListExport ? 48 : 0) + (historyShown ? 48 : 0)) * d);
        lp.rightMargin = (int) (16 * d);
        g.addView(pill, lp);
        pill.bringToFront();
    }

    // ---- the driver: wait for the profile's lists, load each to the end ----

    private static void drive(Session s) {
        if (session != s || s.busy) return;
        long now = SystemClock.uptimeMillis();
        if (now - s.started > 15 * 60_000L) { end(s); return; }
        Object[] vis = FollowListExportHook.visibleList();
        FollowListExportHook.ListInfo info = vis != null ? FollowListExportHook.listInfo(vis[0]) : null;
        if (info == null || !s.id.equals(info.owner)) {
            if (!s.seen && now - s.started > 9000) {
                // the list never opened: a private profile you don't follow, or the tap missed
                end(s);
                if (s.fromMenu) cantOpen(s.ctx, s.name());
                return;
            }
            if (s.seen && now - s.lastSeen > 20000) {
                end(s);
                toast(s.ctx, I18n.t(s.ctx, R.string.ig_pa_left));
                return;
            }
            H.postDelayed(() -> drive(s), 600);
            return;
        }
        View root = (View) vis[1];
        s.ctx = root.getContext();
        s.seen = true;
        s.lastSeen = now;
        if (s.self == null) s.self = info.self;
        if (s.username == null) s.username = info.username != null ? info.username : titleOf(root);

        String type = info.type != null && missing(s, info.type) ? info.type : null;
        if (type == null) {
            // a tab that isn't needed (mutual, suggested, or the list already loaded): go to the missing one
            String want = s.followers == null ? FollowListExportHook.FOLLOWERS : FollowListExportHook.FOLLOWING;
            if (now >= s.nextTabAt) {
                if (s.tabTries < 3 && FollowListExportHook.showTab(vis[0], want)) {
                    s.tabTries++;
                    s.nextTabAt = now + 1500;
                } else if (!s.hinted) {
                    s.hinted = true;
                    toast(s.ctx, I18n.t(s.ctx, R.string.ig_pa_switch_tab, label(s.ctx, want)));
                }
            }
            H.postDelayed(() -> drive(s), 600);
            return;
        }

        // the tab may still be fetching its first page: wait until rows are in (or a short while)
        Object frag = vis[0];
        if (FollowListExportHook.listUsers(root).isEmpty()) {
            if (s.warmSince == 0) s.warmSince = now;
            if (now - s.warmSince < 12000) { H.postDelayed(() -> drive(s), 600); return; }
        }
        s.warmSince = 0;
        s.busy = true;
        String title = I18n.t(s.ctx, R.string.ig_pa_loading_title, s.name(), label(s.ctx, type));
        H.postDelayed(() -> FollowListExportHook.loadAll(root, frag, title, complete -> loaded(s, root, type, complete)), 300);
    }

    private static boolean missing(Session s, String type) {
        return FollowListExportHook.FOLLOWERS.equals(type) ? s.followers == null : s.following == null;
    }

    private static void loaded(Session s, View root, String type, boolean complete) {
        s.busy = false;
        if (session != s) return;
        Map<String, String[]> users = new LinkedHashMap<>();
        if (complete) {
            for (String[] u : FollowListExportHook.listUsers(root).values())
                if (u[2] != null && !u[2].equals(s.id)) users.put(u[2], new String[]{u[0], u[1]});
        }
        ModuleLog.line(TAG + type + ": " + users.size() + (complete ? "" : " (incomplete)"));
        if (!complete || users.isEmpty()) {
            end(s);
            new IeDialog(s.ctx).title("@" + s.name()).message(I18n.t(s.ctx, R.string.ig_pa_partial)).show();
            return;
        }
        if (FollowListExportHook.FOLLOWERS.equals(type)) s.followers = users; else s.following = users;
        s.tabTries = 0;
        s.hinted = false;
        s.nextTabAt = 0;
        if (s.followers != null && s.following != null) {
            end(s);
            finish(s);
            return;
        }
        String next = s.followers == null ? FollowListExportHook.FOLLOWERS : FollowListExportHook.FOLLOWING;
        toast(s.ctx, I18n.t(s.ctx, R.string.ig_pa_next, users.size(), label(s.ctx, type), label(s.ctx, next)));
        s.lastSeen = SystemClock.uptimeMillis();
        H.postDelayed(() -> drive(s), 600);
    }

    private static void finish(Session s) {
        Context ctx = s.ctx;
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String self = s.self != null ? s.self : WatchlistStore.self(app);
            if (self == null) self = "0";
            WatchlistStore.Profile p = WatchlistStore.loadProfile(app, self, s.id);
            if (s.username != null) p.username = s.username;
            long now = System.currentTimeMillis();
            WatchlistStore.Result r = WatchlistStore.apply(p, s.followers, s.following, now);
            String error = null;
            try {
                WatchlistStore.saveProfile(app, self, p);
                List<WatchlistStore.Entry> index = WatchlistStore.loadIndex(app, self);
                WatchlistStore.touch(index, p, now);
                WatchlistStore.saveIndex(app, self, index);
            } catch (Throwable t) {
                error = t.toString();
                ModuleLog.line(TAG + "❌ save: " + t);
            }
            ModuleLog.line(TAG + "@" + s.name() + ": " + r.followers + " followers, " + r.following + " following, "
                    + r.added.size() + " changes" + (r.first ? " (first)" : ""));
            final String err = error, me = self;
            H.post(() -> showResult(ctx, me, p, r, err));
        }).start();
    }

    // ---- opening the profile's followers list from its header ----

    /** Taps the profile header's followers count (Instagram opens the follow list screen). */
    @SuppressLint("DiscouragedApi")
    private static boolean openFollowers(Activity a) {
        try {
            android.content.res.Resources res = a.getResources();
            String pkg = a.getPackageName();
            View decor = a.getWindow().getDecorView();
            for (String n : new String[]{"profile_header_followers_stacked_familiar", "profile_header_familiar_followers_value",
                    "profile_header_familiar_followers_label", "row_profile_header_followers_container",
                    "row_profile_header_textview_followers_count"}) {
                int id = res.getIdentifier(n, "id", pkg);
                View v = id != 0 ? decor.findViewById(id) : null;
                if (v != null && v.isShown() && tapUp(v)) { ModuleLog.line(TAG + "opened followers via " + n); return true; }
            }
            // any shown view whose id names the followers count
            ArrayDeque<View> st = new ArrayDeque<>();
            st.push(decor);
            while (!st.isEmpty()) {
                View v = st.pop();
                if (!v.isShown()) continue;
                if (v.getId() != View.NO_ID && v.getId() > 0x7f000000) {
                    try {
                        String n = res.getResourceEntryName(v.getId());
                        if (n.contains("followers") && !n.contains("follow_context") && tapUp(v)) {
                            ModuleLog.line(TAG + "opened followers via " + n);
                            return true;
                        }
                    } catch (Throwable ignored) {}
                }
                if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ open followers: " + t);
        }
        return false;
    }

    /** Clicks the nearest view (itself or a parent, a few levels up) that has a click listener. */
    private static boolean tapUp(View v) {
        View c = v;
        for (int i = 0; i < 4 && c != null; i++, c = c.getParent() instanceof View p ? p : null)
            if (c.isClickable() && c.hasOnClickListeners() && c.isEnabled()) return c.performClick();
        return false;
    }

    private static void cantOpen(Context ctx, String name) {
        try {
            new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_pa_analyze))
                    .message(I18n.t(ctx, R.string.ig_pa_cant_open, name)).show();
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ dialog: " + t);
        }
    }

    // ---- result, change log, watchlist ----

    private static void showResult(Context ctx, String self, WatchlistStore.Profile p, WatchlistStore.Result r, String error) {
        try {
            DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
            String name = p.username != null ? p.username : p.id;
            IeDialog dlg = new IeDialog(ctx).title("@" + name);
            String head;
            if (r.first) head = I18n.t(ctx, R.string.ig_pa_first, r.followers, r.following);
            else {
                head = I18n.t(ctx, R.string.ig_pa_since, df.format(new Date(r.previousCheck)))
                        + "\n" + I18n.t(ctx, R.string.ig_pa_counts, r.followers, r.following);
                if (r.added.isEmpty()) head += "\n\n" + I18n.t(ctx, R.string.ig_pa_no_changes);
            }
            if (error != null) head += "\n⚠️ " + error;
            dlg.message(head);
            if (!r.first && !r.added.isEmpty()) {
                section(dlg, ctx, I18n.t(ctx, R.string.ig_pa_sec_b_followed, name, r.of(WatchlistStore.B_FOLLOWED).size()), r.of(WatchlistStore.B_FOLLOWED), GREEN);
                section(dlg, ctx, I18n.t(ctx, R.string.ig_pa_sec_b_unfollowed, name, r.of(WatchlistStore.B_UNFOLLOWED).size()), r.of(WatchlistStore.B_UNFOLLOWED), RED);
                section(dlg, ctx, I18n.t(ctx, R.string.ig_pa_sec_gained, name, r.of(WatchlistStore.GAINED_FOLLOWER).size()), r.of(WatchlistStore.GAINED_FOLLOWER), GREEN);
                section(dlg, ctx, I18n.t(ctx, R.string.ig_pa_sec_lost, name, r.of(WatchlistStore.LOST_FOLLOWER).size()), r.of(WatchlistStore.LOST_FOLLOWER), RED);
                hint(dlg, ctx);
            }
            if (!p.events.isEmpty()) dlg.secondary(I18n.t(ctx, R.string.ig_pa_log), () -> showLog(ctx, self, p.id));
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ result dialog: " + t);
        }
    }

    private static void section(IeDialog dlg, Context ctx, String title, List<WatchlistStore.Event> events, int color) {
        if (events.isEmpty()) return;
        TextView h = new TextView(dlg.ctx);
        h.setText(title);
        h.setTextColor(color);
        h.setTextSize(14);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(0, dlg.dp(8), 0, dlg.dp(2));
        dlg.view(h);
        LinearLayout rows = new LinearLayout(dlg.ctx);
        rows.setOrientation(LinearLayout.VERTICAL);
        int shown = 0;
        for (WatchlistStore.Event e : events) {
            if (shown++ >= 300) break;
            SpannableStringBuilder sb = new SpannableStringBuilder("@").append(e.username != null ? e.username : e.id);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), 0);
            if (e.fullName != null && !e.fullName.isEmpty()) {
                int st = sb.length();
                sb.append("  ").append(e.fullName);
                sb.setSpan(new ForegroundColorSpan(dlg.sub), st, sb.length(), 0);
            }
            rows.addView(userRow(dlg, ctx, sb, e.username));
        }
        dlg.view(rows);
    }

    /** A user row: tap opens the profile in Instagram, hold copies the username. */
    private static TextView userRow(IeDialog dlg, Context ctx, CharSequence text, String username) {
        TextView row = new TextView(dlg.ctx);
        row.setText(text);
        row.setTextColor(dlg.text);
        row.setTextSize(14);
        row.setPadding(0, dlg.dp(5), 0, dlg.dp(5));
        if (username != null) {
            row.setOnClickListener(v -> openProfile(ctx, username));
            row.setOnLongClickListener(v -> { copy(ctx, username); return true; });
        }
        return row;
    }

    private static void hint(IeDialog dlg, Context ctx) {
        TextView note = new TextView(dlg.ctx);
        note.setText(I18n.t(ctx, R.string.ig_pa_tap_hint) + "\n" + I18n.t(ctx, R.string.ig_followers_history_note));
        note.setTextColor(dlg.sub);
        note.setTextSize(12);
        dlg.view(note);
    }

    /** A watched profile's change log, newest analysis first. */
    public static void showLog(Context ctx, String self, String id) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            WatchlistStore.Profile p = WatchlistStore.loadProfile(app, self, id);
            H.post(() -> showLog(ctx, self, p));
        }).start();
    }

    private static void showLog(Context ctx, String self, WatchlistStore.Profile p) {
        try {
            DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
            String name = p.username != null ? p.username : p.id;
            IeDialog dlg = new IeDialog(ctx).title("@" + name + " · " + I18n.t(ctx, R.string.ig_pa_log));
            dlg.message(p.lastCheck > 0
                    ? I18n.t(ctx, R.string.ig_pa_counts, p.followers.size(), p.following.size()) + " · " + df.format(new Date(p.lastCheck))
                    : I18n.t(ctx, R.string.ig_pa_never));
            if (p.events.isEmpty()) {
                TextView empty = new TextView(dlg.ctx);
                empty.setText(I18n.t(ctx, R.string.ig_pa_log_empty));
                empty.setTextColor(dlg.sub);
                empty.setTextSize(14);
                dlg.view(empty);
            } else {
                LinearLayout rows = new LinearLayout(dlg.ctx);
                rows.setOrientation(LinearLayout.VERTICAL);
                long group = -1;
                int shown = 0;
                for (WatchlistStore.Event e : p.events) {
                    if (shown++ >= 400) break;
                    if (e.ts != group) {
                        group = e.ts;
                        TextView h = new TextView(dlg.ctx);
                        h.setText(I18n.t(ctx, R.string.ig_pa_analysis_of, df.format(new Date(e.ts))));
                        h.setTextColor(dlg.sub);
                        h.setTextSize(13);
                        h.setTypeface(Typeface.DEFAULT_BOLD);
                        h.setPadding(0, dlg.dp(10), 0, dlg.dp(2));
                        rows.addView(h);
                    }
                    boolean plus = WatchlistStore.B_FOLLOWED.equals(e.type) || WatchlistStore.GAINED_FOLLOWER.equals(e.type);
                    int labelRes = switch (e.type) {
                        case WatchlistStore.B_FOLLOWED -> R.string.ig_pa_ev_b_followed;
                        case WatchlistStore.B_UNFOLLOWED -> R.string.ig_pa_ev_b_unfollowed;
                        case WatchlistStore.GAINED_FOLLOWER -> R.string.ig_pa_ev_gained;
                        default -> R.string.ig_pa_ev_lost;
                    };
                    SpannableStringBuilder sb = new SpannableStringBuilder(plus ? "+ " : "− ");
                    sb.append("@").append(e.username != null ? e.username : e.id);
                    sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), 0);
                    if (e.fullName != null && !e.fullName.isEmpty()) sb.append("  ").append(e.fullName);
                    int st = sb.length();
                    sb.append("\n").append(I18n.t(ctx, labelRes, name));
                    sb.setSpan(new ForegroundColorSpan(plus ? GREEN : RED), st, sb.length(), 0);
                    sb.setSpan(new RelativeSizeSpan(0.85f), st, sb.length(), 0);
                    rows.addView(userRow(dlg, ctx, sb, e.username));
                }
                dlg.view(rows);
                hint(dlg, ctx);
            }
            Context app = ctx.getApplicationContext();
            dlg.secondary(I18n.t(ctx, R.string.ig_pa_remove), () -> new Thread(() -> {
                WatchlistStore.remove(app, self, p.id);
                H.post(() -> toast(ctx, I18n.t(ctx, R.string.ig_pa_removed, name)));
            }).start());
            if (p.username != null) dlg.primary(I18n.t(ctx, R.string.ig_pa_open_profile), () -> openProfile(ctx, p.username));
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ log dialog: " + t);
        }
    }

    /** The in-app menu's Watchlist: watched profiles with their last analysis; tap opens the log. */
    public static void showWatchlist(Context ctx) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            String self = WatchlistStore.self(app);
            List<WatchlistStore.Entry> index = self != null ? WatchlistStore.loadIndex(app, self) : new java.util.ArrayList<>();
            H.post(() -> showWatchlist(ctx, self, index));
        }).start();
    }

    private static void showWatchlist(Context ctx, String self, List<WatchlistStore.Entry> index) {
        try {
            DateFormat df = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
            IeDialog dlg = new IeDialog(ctx).title(I18n.t(ctx, R.string.ig_pa_watchlist));
            if (index.isEmpty()) {
                dlg.message(I18n.t(ctx, R.string.ig_pa_watch_empty));
                dlg.show();
                return;
            }
            dlg.message(I18n.t(ctx, R.string.ig_pa_watch_hint));
            for (WatchlistStore.Entry e : index) {
                String name = e.username != null ? e.username : e.id;
                SpannableStringBuilder sb = new SpannableStringBuilder("@" + name);
                sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), 0);
                int st = sb.length();
                sb.append("\n").append(e.lastCheck > 0
                        ? I18n.t(ctx, R.string.ig_pa_watch_row, e.followers, e.following, df.format(new Date(e.lastCheck)))
                        : I18n.t(ctx, R.string.ig_pa_never));
                if (e.events > 0) sb.append(" · ").append(I18n.t(ctx, R.string.ig_pa_watch_changes, e.events));
                sb.setSpan(new ForegroundColorSpan(dlg.sub), st, sb.length(), 0);
                sb.setSpan(new RelativeSizeSpan(0.85f), st, sb.length(), 0);
                TextView row = new TextView(dlg.ctx);
                row.setText(sb);
                row.setTextColor(dlg.text);
                row.setTextSize(15);
                row.setPadding(dlg.dp(14), dlg.dp(10), dlg.dp(14), dlg.dp(10));
                row.setBackground(dlg.cardBg());
                row.setOnClickListener(v -> showLog(ctx, self, e.id));
                dlg.view(row);
            }
            dlg.show();
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ watchlist dialog: " + t);
        }
    }

    // ---- helpers ----

    private static String label(Context ctx, String type) {
        return I18n.t(ctx, FollowListExportHook.FOLLOWERS.equals(type) ? R.string.ig_pa_followers : R.string.ig_pa_following);
    }

    /** The follow list screen's title (the profile's username). */
    @SuppressLint("DiscouragedApi")
    private static String titleOf(View v) {
        try {
            int id = v.getResources().getIdentifier("action_bar_title", "id", v.getContext().getPackageName());
            View t = id != 0 ? v.getRootView().findViewById(id) : null;
            if (t instanceof TextView tv && tv.getText() != null) {
                String s = tv.getText().toString().trim();
                if (s.matches("[A-Za-z0-9._]{1,30}")) return s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void openProfile(Context ctx, String username) {
        try {
            ctx.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/" + username + "/"))
                    .setPackage(ctx.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            ModuleLog.line(TAG + "open profile: " + t);
        }
    }

    private static void copy(Context ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("InstaEclipse", text));
        toast(ctx, I18n.t(ctx, R.string.ig_toast_comment_copied));
    }

    private static void toast(Context ctx, String text) {
        try { IeToast.makeText(ctx, text, Toast.LENGTH_LONG).show(); } catch (Throwable ignored) {}
    }
}
