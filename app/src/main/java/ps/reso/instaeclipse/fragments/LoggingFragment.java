package ps.reso.instaeclipse.fragments;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.CommonUtils;
import ps.reso.instaeclipse.utils.log.LogMirrorProvider;
import ps.reso.instaeclipse.utils.log.Logging;

/**
 * Logs tab. Instagram's module log is read from a file in the companion's own storage that the
 * hooked Instagram process keeps appending to (see {@link LogMirrorProvider}), so it works even
 * when Instagram is backgrounded or dead. While Instagram is running we additionally ask it (via
 * broadcast) to re-sync that file; its inline reply is only used as a fallback when the file is
 * missing (e.g. an Instagram process started before this companion version was installed).
 */
public class LoggingFragment extends Fragment {

    private static final long INSTAGRAM_REPLY_TIMEOUT_MS = 3000;
    private static final int MAX_ENTRIES = 6000;
    private static final long MAX_READ_BYTES = 3L * 1024 * 1024;
    private static final int MAX_EXPORT_CHARS = 250_000;

    private static final int KIND_INFO = 0;
    private static final int KIND_OK = 1;
    private static final int KIND_WARN = 2;
    private static final int KIND_ERROR = 3;

    private static final int FILTER_ALL = 0;

    private static final Pattern TS_PREFIX = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} .*", Pattern.DOTALL);
    private static final int TS_LEN = 23; // "yyyy-MM-dd HH:mm:ss.SSS"

    private static final class Entry {
        final String ts;
        final String full;
        final String display;
        final int kind;

        Entry(String ts, String full, String display, int kind) {
            this.ts = ts;
            this.full = full;
            this.display = display;
            this.kind = kind;
        }
    }

    private RecyclerView listView;
    private View emptyView;
    private TextView emptyTitle;
    private TextView emptyBody;
    private View emptyRefresh;
    private TextView countView;
    private TextView statusView;
    private View progressView;
    private Chip chipAll, chipHooked, chipErrors, chipWarnings;

    private final LogAdapter adapter = new LogAdapter();
    private List<Entry> allEntries = new ArrayList<>();
    private int filterKind = FILTER_ALL;
    private boolean loadedOnce;

    private final Map<String, String> liveReplies = new HashMap<>();
    private boolean waitingForInstagram;
    private boolean noTargetInstalled;
    private Runnable pendingTimeout;

    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger loadGeneration = new AtomicInteger(0);

    /** Nonce of the last ACTION_REQUEST_LOGS; replies without it are ignored (IpcSecurity). */
    private String logsNonce;
    private final java.util.List<ps.reso.instaeclipse.utils.ipc.ModuleReplies.Pending> logReplies = new java.util.ArrayList<>();

    private final BroadcastReceiver logReplyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!CommonUtils.ACTION_LOGS_REPLY.equals(intent.getAction()) || listView == null) return;
            if (!ps.reso.instaeclipse.utils.core.IpcSecurity.nonceMatches(logsNonce, intent)) return;
            String src = intent.getStringExtra(CommonUtils.EXTRA_LOG_SOURCE);
            if (src == null) src = CommonUtils.IG_PACKAGE_NAME;
            String body = intent.getStringExtra(CommonUtils.EXTRA_LOG_TEXT);
            liveReplies.put(src, body == null ? "" : body);
            cancelInstagramTimeout();
            waitingForInstagram = false;
            reload();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_logging, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        listView = view.findViewById(R.id.logs_list);
        emptyView = view.findViewById(R.id.logs_empty);
        emptyTitle = view.findViewById(R.id.logs_empty_title);
        emptyBody = view.findViewById(R.id.logs_empty_body);
        emptyRefresh = view.findViewById(R.id.logs_empty_refresh);
        countView = view.findViewById(R.id.logs_count);
        statusView = view.findViewById(R.id.logs_status);
        progressView = view.findViewById(R.id.logs_progress);
        chipAll = view.findViewById(R.id.logs_chip_all);
        chipHooked = view.findViewById(R.id.logs_chip_hooked);
        chipErrors = view.findViewById(R.id.logs_chip_errors);
        chipWarnings = view.findViewById(R.id.logs_chip_warnings);

        adapter.colorInfo = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurfaceVariant);
        adapter.colorError = MaterialColors.getColor(view, androidx.appcompat.R.attr.colorError);
        adapter.colorWarn = ContextCompat.getColor(requireContext(), R.color.warning_yellow);
        adapter.colorOk = Color.parseColor("#81C784");

        LinearLayoutManager lm = new LinearLayoutManager(requireContext());
        listView.setLayoutManager(lm);
        listView.setAdapter(adapter);
        listView.setItemAnimator(null);

        ChipGroup group = view.findViewById(R.id.logs_filter_group);
        group.setOnCheckedStateChangeListener((g, ids) -> {
            int id = ids.isEmpty() ? R.id.logs_chip_all : ids.get(0);
            if (id == R.id.logs_chip_hooked) filterKind = KIND_OK;
            else if (id == R.id.logs_chip_errors) filterKind = KIND_ERROR;
            else if (id == R.id.logs_chip_warnings) filterKind = KIND_WARN;
            else filterKind = FILTER_ALL;
            applyFilter();
        });

        view.findViewById(R.id.logs_refresh).setOnClickListener(v -> loadLogs());
        emptyRefresh.setOnClickListener(v -> loadLogs());
        view.findViewById(R.id.logs_copy).setOnClickListener(v -> copyLogs());
        view.findViewById(R.id.logs_share).setOnClickListener(v -> shareLogs());
        view.findViewById(R.id.logs_clear).setOnClickListener(v -> clearLogs());
    }

    @Override
    public void onStart() {
        super.onStart();

    }

    @Override
    public void onResume() {
        super.onResume();
        loadLogs();
    }

    @Override
    public void onStop() {
        super.onStop();
        cancelInstagramTimeout();
        waitingForInstagram = false;
        cancelLogReplies();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        cancelInstagramTimeout();
        listView = null;
        emptyView = null;
        countView = null;
        statusView = null;
        progressView = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        loadExecutor.shutdownNow();
    }

    // ---------------------------------------------------------------- loading

    /** Shows the saved log right away, then asks running Instagram processes to re-sync it. */
    private void loadLogs() {
        Context ctx = getContext();
        if (ctx == null || listView == null) return;
        liveReplies.clear();
        reload();

        List<String> targets = findInstalledTargets(ctx);
        noTargetInstalled = targets.isEmpty();
        if (noTargetInstalled) return;
        cancelLogReplies();
        logsNonce = ps.reso.instaeclipse.utils.core.IpcSecurity.newNonce();
        for (String pkg : targets) {
            Intent request = new Intent(CommonUtils.ACTION_REQUEST_LOGS);
            request.setPackage(pkg);
            request.putExtra(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE, logsNonce);
            ps.reso.instaeclipse.utils.ipc.ModuleReplies.Pending pending =
                    ps.reso.instaeclipse.utils.core.IpcSecurity.requestReply(ctx, request, CommonUtils.ACTION_LOGS_REPLY, logReplyReceiver);
            if (pending != null) logReplies.add(pending);
        }
        waitingForInstagram = true;
        setProgress(true);
        cancelInstagramTimeout();
        pendingTimeout = () -> {
            pendingTimeout = null;
            waitingForInstagram = false;
            setProgress(false);
            updateStatus();
        };
        mainHandler.postDelayed(pendingTimeout, INSTAGRAM_REPLY_TIMEOUT_MS);
    }

    private void cancelLogReplies() {
        for (ps.reso.instaeclipse.utils.ipc.ModuleReplies.Pending reply : logReplies) reply.cancel();
        logReplies.clear();
    }

    private void reload() {
        Context ctx = getContext();
        if (ctx == null) return;
        Context app = ctx.getApplicationContext();
        int gen = loadGeneration.incrementAndGet();
        Map<String, String> replies = new HashMap<>(liveReplies);
        String companionTag = getString(R.string.logs_source_companion);
        if (!loadedOnce) setProgress(true);
        try {
            loadExecutor.execute(() -> {
                List<Entry> entries = readAllEntries(app, replies, companionTag);
                mainHandler.post(() -> {
                    if (listView == null || gen != loadGeneration.get()) return;
                    loadedOnce = true;
                    allEntries = entries;
                    if (!waitingForInstagram) setProgress(false);
                    applyFilter();
                    updateStatus();
                });
            });
        } catch (Throwable ignored) {
            // executor shut down (fragment destroyed)
        }
    }

    private static List<Entry> readAllEntries(Context ctx, Map<String, String> replies, String companionTag) {
        List<Entry> out = new ArrayList<>();
        Map<String, Boolean> seen = new HashMap<>();

        File[] files = LogMirrorProvider.mirrorDir(ctx).listFiles((d, n) -> n.endsWith(".log"));
        if (files != null) {
            for (File f : files) {
                String pkg = f.getName().substring(0, f.getName().length() - 4);
                List<String> lines = readTail(f);
                if (lines.isEmpty()) {
                    String reply = replies.get(pkg);
                    if (reply != null) lines = splitLines(reply);
                }
                if (!lines.isEmpty()) seen.put(pkg, true);
                parseInto(out, lines, sourcePrefix(pkg));
            }
        }
        // Fallback: a running Instagram that couldn't open the mirror still answers inline.
        for (Map.Entry<String, String> r : replies.entrySet()) {
            if (seen.containsKey(r.getKey())) continue;
            parseInto(out, splitLines(r.getValue()), sourcePrefix(r.getKey()));
        }
        parseInto(out, splitLines(Logging.getSnapshot()), companionTag + " ");

        // Stable merge by timestamp so app + Instagram entries interleave correctly.
        Collections.sort(out, (a, b) -> a.ts.compareTo(b.ts));
        if (out.size() > MAX_ENTRIES) out = new ArrayList<>(out.subList(out.size() - MAX_ENTRIES, out.size()));
        return out;
    }

    private static String sourcePrefix(String pkg) {
        return CommonUtils.IG_PACKAGE_NAME.equals(pkg) ? "" : "[" + pkg + "] ";
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) return lines;
        for (String l : text.split("\n")) if (!l.isEmpty()) lines.add(l);
        return lines;
    }

    private static List<String> readTail(File f) {
        ArrayDeque<String> lines = new ArrayDeque<>();
        if (!f.isFile() || f.length() == 0) return new ArrayList<>();
        try (FileInputStream in = new FileInputStream(f)) {
            long len = f.length();
            boolean skipFirst = false;
            if (len > MAX_READ_BYTES) {
                long skip = len - MAX_READ_BYTES;
                while (skip > 0) {
                    long s = in.skip(skip);
                    if (s <= 0) break;
                    skip -= s;
                }
                skipFirst = true; // likely a partial line
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                if (skipFirst) {
                    skipFirst = false;
                    continue;
                }
                if (line.isEmpty()) continue;
                lines.addLast(line);
                if (lines.size() > MAX_ENTRIES * 4) lines.pollFirst();
            }
        } catch (Throwable ignored) {}
        return new ArrayList<>(lines);
    }

    /** Groups raw lines into entries: a timestamped line starts one, others (stack traces) continue it. */
    private static void parseInto(List<Entry> out, List<String> lines, String prefix) {
        String curTs = null;
        StringBuilder cur = null;
        String lastTs = "";
        for (String line : lines) {
            boolean starts = line.length() > TS_LEN && TS_PREFIX.matcher(line).matches();
            if (starts || cur == null) {
                if (cur != null) out.add(makeEntry(curTs, cur.toString(), prefix));
                curTs = starts ? line.substring(0, TS_LEN) : lastTs;
                lastTs = curTs;
                cur = new StringBuilder(line);
            } else {
                cur.append('\n').append(line);
            }
        }
        if (cur != null) out.add(makeEntry(curTs, cur.toString(), prefix));
    }

    private static Entry makeEntry(String ts, String full, String prefix) {
        String display;
        if (full.length() > TS_LEN && full.startsWith(ts) && !ts.isEmpty()) {
            // Drop the date for display; keep HH:mm:ss.SSS.
            display = full.substring(11, TS_LEN) + "  " + prefix + full.substring(TS_LEN + 1);
        } else {
            display = prefix + full;
        }
        return new Entry(ts == null ? "" : ts, prefix.isEmpty() ? full : insertPrefix(full, ts, prefix), display, classify(full));
    }

    private static String insertPrefix(String full, String ts, String prefix) {
        if (ts != null && !ts.isEmpty() && full.startsWith(ts) && full.length() > TS_LEN) {
            return ts + " " + prefix + full.substring(TS_LEN + 1);
        }
        return prefix + full;
    }

    static int classify(String s) {
        String l = s.toLowerCase(Locale.ROOT);
        if (s.contains("❌") || l.contains("exception") || l.contains("error") || l.contains("failed")
                || l.contains("fatal") || l.contains("crash")) return KIND_ERROR;
        if (s.contains("⚠") || l.contains("warn") || l.contains("not found") || l.contains("timeout")
                || l.contains("timed out") || l.contains("skipp") || l.contains("fallback")) return KIND_WARN;
        if (s.contains("✅") || s.contains("✔") || l.contains("hooked") || l.contains("success")
                || l.contains("installed") || l.contains(" ok ") || l.endsWith(" ok")) return KIND_OK;
        return KIND_INFO;
    }

    // ---------------------------------------------------------------- rendering

    private void applyFilter() {
        if (listView == null) return;
        List<Entry> shown;
        int ok = 0, warn = 0, err = 0;
        for (Entry e : allEntries) {
            if (e.kind == KIND_OK) ok++;
            else if (e.kind == KIND_WARN) warn++;
            else if (e.kind == KIND_ERROR) err++;
        }
        if (filterKind == FILTER_ALL) {
            shown = allEntries;
        } else {
            shown = new ArrayList<>();
            for (Entry e : allEntries) if (e.kind == filterKind) shown.add(e);
        }
        chipAll.setText(getString(R.string.logs_filter_count, getString(R.string.logs_filter_all), allEntries.size()));
        chipHooked.setText(getString(R.string.logs_filter_count, getString(R.string.logs_filter_hooked), ok));
        chipErrors.setText(getString(R.string.logs_filter_count, getString(R.string.logs_filter_errors), err));
        chipWarnings.setText(getString(R.string.logs_filter_count, getString(R.string.logs_filter_warnings), warn));

        adapter.setItems(shown);
        countView.setText(getString(R.string.logs_count_format, shown.size(), allEntries.size()));

        boolean empty = shown.isEmpty();
        emptyView.setVisibility(empty && loadedOnce ? View.VISIBLE : View.GONE);
        listView.setVisibility(empty ? View.INVISIBLE : View.VISIBLE);
        if (empty) {
            boolean filtered = !allEntries.isEmpty();
            emptyTitle.setText(filtered ? R.string.logs_empty_filter_title : R.string.logs_empty_title);
            emptyBody.setText(filtered ? R.string.logs_empty_filter_body : R.string.logs_empty_body);
            emptyRefresh.setVisibility(filtered ? View.GONE : View.VISIBLE);
        } else {
            listView.scrollToPosition(shown.size() - 1);
        }
    }

    private void updateStatus() {
        if (statusView == null) return;
        int res;
        if (noTargetInstalled) res = R.string.logs_status_none;
        else if (!liveReplies.isEmpty()) res = R.string.logs_status_live;
        else if (!waitingForInstagram) res = R.string.logs_status_saved;
        else {
            statusView.setVisibility(View.GONE);
            return;
        }
        statusView.setText(res);
        statusView.setVisibility(View.VISIBLE);
    }

    private void setProgress(boolean on) {
        if (progressView != null) progressView.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void cancelInstagramTimeout() {
        if (pendingTimeout != null) {
            mainHandler.removeCallbacks(pendingTimeout);
            pendingTimeout = null;
        }
    }

    private static List<String> findInstalledTargets(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<String> out = new ArrayList<>();
        for (String pkg : CommonUtils.SUPPORTED_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                out.add(pkg);
            } catch (PackageManager.NameNotFoundException ignored) {}
        }
        return out;
    }

    // ---------------------------------------------------------------- actions

    /** Text of the currently filtered entries (full timestamps), capped to the newest part. */
    private String exportText() {
        List<Entry> items = adapter.items;
        StringBuilder sb = new StringBuilder();
        for (int i = items.size() - 1; i >= 0; i--) {
            String s = items.get(i).full;
            if (sb.length() + s.length() + 1 > MAX_EXPORT_CHARS) break;
            sb.insert(0, s + "\n");
        }
        return sb.toString();
    }

    private void copyLogs() {
        Context ctx = getContext();
        if (ctx == null) return;
        String text = exportText();
        if (text.isEmpty()) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, R.string.logs_nothing_to_copy, Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("InstaEclipse logs", text));
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, R.string.logs_copied, Toast.LENGTH_SHORT).show();
    }

    private void shareLogs() {
        Context ctx = getContext();
        if (ctx == null) return;
        String text = exportText();
        if (text.isEmpty()) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, R.string.logs_nothing_to_copy, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.logs_share_subject));
        send.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.logs_share_title)));
        } catch (Throwable t) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, String.valueOf(t.getMessage()), Toast.LENGTH_SHORT).show();
        }
    }

    private void clearLogs() {
        Context ctx = getContext();
        if (ctx == null) return;
        for (String pkg : findInstalledTargets(ctx)) {
            Intent clear = new Intent(CommonUtils.ACTION_CLEAR_LOGS);
            clear.setPackage(pkg);
            ctx.sendBroadcast(clear);
        }
        Logging.clear();
        liveReplies.clear();
        Context app = ctx.getApplicationContext();
        try {
            loadExecutor.execute(() -> {
                // Truncate (not delete): a running Instagram keeps an O_APPEND fd on these files.
                File[] files = LogMirrorProvider.mirrorDir(app).listFiles();
                if (files != null) {
                    for (File f : files) {
                        try (FileOutputStream fos = new FileOutputStream(f, false)) {
                            fos.getFD();
                        } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable ignored) {}
        reload();
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, R.string.logs_cleared, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- adapter

    private final class LogAdapter extends RecyclerView.Adapter<LogAdapter.VH> {
        List<Entry> items = new ArrayList<>();
        int colorInfo, colorOk, colorWarn, colorError;

        @SuppressWarnings("NotifyDataSetChanged")
        void setItems(List<Entry> list) {
            items = list;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setTypeface(Typeface.MONOSPACE);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3, parent.getResources().getDisplayMetrics()));
            tv.setPadding(0, pad, 0, pad);
            tv.setLineSpacing(0, 1.1f);
            return new VH(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            Entry e = items.get(position);
            holder.text.setText(e.display);
            int c;
            switch (e.kind) {
                case KIND_ERROR: c = colorError; break;
                case KIND_WARN: c = colorWarn; break;
                case KIND_OK: c = colorOk; break;
                default: c = colorInfo;
            }
            holder.text.setTextColor(c);
            holder.text.setOnLongClickListener(v -> {
                ClipboardManager cm = (ClipboardManager) v.getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("InstaEclipse log entry", e.full));
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(v.getContext(), R.string.logs_entry_copied, Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class VH extends RecyclerView.ViewHolder {
            final TextView text;

            VH(TextView tv) {
                super(tv);
                text = tv;
            }
        }
    }
}
