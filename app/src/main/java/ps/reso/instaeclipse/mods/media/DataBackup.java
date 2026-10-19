package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.download.DownloadEngine;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

/**
 * Back up / restore the module's own data kept in Instagram's private files, so it survives a
 * reinstall: deleted (unsent) messages, analyzed profiles (watchlist), followers history, hidden
 * chats and chat names.
 *
 * <p>Backup: one zip saved from Instagram's process to Download/InstaEclipse (no size limit).
 * Restore: the companion's picker (DataImportActivity) reads the zip and sends it here in Base64
 * pieces, since one broadcast can't carry more than about a megabyte. Deleted messages are merged
 * with what's on the phone; the other files are put back as they were. Instagram then restarts so
 * every store reloads from disk.
 */
public final class DataBackup {

    private static final String TAG = "(IE|DataBackup) ";
    public static final String ACTION_RESTORE = "ps.reso.instaeclipse.ACTION_RESTORE_DATA";
    private static final String MANIFEST = "instaeclipse_data.json";
    private static final String UNSENT = "ie_unsent_log.json";
    private static final int MAX_UNSENT = 1000;
    private static final long MAX_BYTES = 64L << 20;

    private DataBackup() {}

    /** Files that belong in a backup: our stores only (not caches or logs). */
    private static boolean included(String name) {
        return name.equals(UNSENT) || name.equals("ie_hidden_threads.json") || name.equals("ie_thread_names.json") || name.equals("ie_post_counts.json")
                || name.startsWith("ie_watch") || name.startsWith("ie_followers_history_");
    }

    // ----- backup -----

    public static void export(Activity a) {
        Context app = a.getApplicationContext();
        new Thread(() -> {
            File zip = null;
            try {
                File[] files = app.getFilesDir().listFiles();
                int count = 0;
                zip = File.createTempFile("ie_data_", ".zip", app.getCacheDir());
                try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
                    if (files != null) for (File f : files) {
                        if (!f.isFile() || !included(f.getName())) continue;
                        out.putNextEntry(new ZipEntry(f.getName()));
                        out.write(java.nio.file.Files.readAllBytes(f.toPath()));
                        out.closeEntry();
                        count++;
                    }
                    JSONObject m = new JSONObject();
                    m.put("app", "InstaEclipse");
                    m.put("type", "data");
                    m.put("version", 1);
                    m.put("created", System.currentTimeMillis());
                    out.putNextEntry(new ZipEntry(MANIFEST));
                    out.write(m.toString().getBytes(StandardCharsets.UTF_8));
                    out.closeEntry();
                }
                String name = "instaeclipse_data_" + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date()) + ".zip";
                DownloadPublisher.save(app, zip, name, "application/zip", "", "", false, new DownloadEngine.Cancellation());
                ModuleLog.line(TAG + "backup saved: " + count + " files, " + zip.length() + " bytes");
                toast(app, I18n.t(app, R.string.ig_data_backup_saved, "Download/InstaEclipse/" + name), true);
            } catch (Throwable t) {
                ModuleLog.line(TAG + "backup failed: " + t);
                toast(app, I18n.t(app, R.string.ig_data_backup_failed), true);
            } finally {
                if (zip != null) zip.delete();
            }
        }, "IE-DataBackup").start();
    }

    /** Opens the companion's file picker, which sends the chosen backup back to Instagram. */
    public static void pickAndRestore(Activity a) {
        Intent i = new Intent();
        i.setComponent(new ComponentName("ps.reso.instaeclipse", "ps.reso.instaeclipse.mods.devops.config.DataImportActivity"));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        i.putExtra("target_package", a.getPackageName());
        ps.reso.instaeclipse.utils.ipc.CompanionBridge.launchActivity(a, i);
    }

    // ----- restore -----

    private static final Map<String, String[]> PENDING = new HashMap<>();

    public static void registerRestoreReceiver(Context context) {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                String id = intent.getStringExtra("id");
                String piece = intent.getStringExtra("piece");
                int index = intent.getIntExtra("index", -1), total = intent.getIntExtra("total", 0);
                if (id == null || piece == null || total <= 0 || total > 2000 || index < 0 || index >= total) return;
                String[] parts;
                synchronized (PENDING) {
                    parts = PENDING.get(id);
                    if (parts == null) {
                        PENDING.clear(); // one restore at a time
                        parts = new String[total];
                        PENDING.put(id, parts);
                    }
                    if (parts.length != total) return;
                    parts[index] = piece;
                    for (String p : parts) if (p == null) return;
                    PENDING.remove(id);
                }
                final String[] all = parts;
                Context app = ctx.getApplicationContext();
                new Thread(() -> restore(app, all), "IE-DataRestore").start();
            }
        };
        try {
            ps.reso.instaeclipse.utils.core.IpcSecurity.registerCompanionOnlyReceiver(context, receiver, new IntentFilter(ACTION_RESTORE));
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ receiver: " + t.getMessage());
        }
    }

    private static void restore(Context app, String[] parts) {
        try {
            StringBuilder b64 = new StringBuilder();
            for (String p : parts) b64.append(p);
            byte[] zip = Base64.decode(b64.toString(), Base64.NO_WRAP);
            Map<String, byte[]> files = unzip(zip);
            if (!files.containsKey(MANIFEST) || !"data".equals(new JSONObject(new String(files.get(MANIFEST), StandardCharsets.UTF_8)).optString("type")))
                throw new IllegalArgumentException("not an InstaEclipse data backup");
            File dir = app.getFilesDir();
            int restored = 0;
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                String name = e.getKey();
                if (!included(name) || name.contains("/") || name.contains("\\")) continue;
                byte[] data = name.equals(UNSENT) ? mergeUnsent(new File(dir, UNSENT), e.getValue()) : e.getValue();
                write(new File(dir, name), data);
                restored++;
            }
            ModuleLog.line(TAG + "restored " + restored + " files");
            toast(app, I18n.t(app, R.string.ig_data_restore_done, restored), false);
            // Every store keeps a copy in memory: restart so they all read the restored files.
            new Handler(Looper.getMainLooper()).postDelayed(() -> restart(app), 1800);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "restore failed: " + t);
            toast(app, I18n.t(app, R.string.ig_data_restore_failed), true);
        }
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new IllegalArgumentException("backup too large");
                    bytes.write(buf, 0, n);
                }
                out.put(e.getName(), bytes.toByteArray());
            }
        }
        return out;
    }

    /** Deleted messages from the backup and the phone, each once, oldest first, newest kept. */
    static byte[] mergeUnsent(File current, byte[] backup) throws Exception {
        Map<String, JSONObject> merged = new LinkedHashMap<>();
        List<JSONArray> sources = new ArrayList<>();
        sources.add(new JSONArray(new String(backup, StandardCharsets.UTF_8)));
        if (current.exists()) {
            try { sources.add(new JSONArray(new String(new android.util.AtomicFile(current).readFully(), StandardCharsets.UTF_8))); }
            catch (Throwable ignored) { }
        }
        for (JSONArray arr : sources)
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                merged.put(o.optLong("t") + "|" + o.optString("th") + "|" + o.optString("m"), o);
            }
        List<JSONObject> list = new ArrayList<>(merged.values());
        list.sort((x, y) -> Long.compare(x.optLong("t"), y.optLong("t")));
        if (list.size() > MAX_UNSENT) list = list.subList(list.size() - MAX_UNSENT, list.size());
        JSONArray out = new JSONArray();
        for (JSONObject o : list) out.put(o);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void write(File f, byte[] data) throws Exception {
        android.util.AtomicFile atomic = new android.util.AtomicFile(f);
        FileOutputStream out = atomic.startWrite();
        try {
            out.write(data);
            atomic.finishWrite(out);
        } catch (Exception e) {
            atomic.failWrite(out);
            throw e;
        }
    }

    private static void restart(Context app) {
        Intent launch = app.getPackageManager().getLaunchIntentForPackage(app.getPackageName());
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(app, 0x1EB, launch,
                    android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_CANCEL_CURRENT);
            android.app.AlarmManager am = (android.app.AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            if (am != null) am.set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 400, pi);
        }
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private static void toast(Context ctx, String text, boolean longer) {
        new Handler(Looper.getMainLooper()).post(() ->
                IeToast.makeText(ctx, text, longer ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show());
    }

    /** For the companion: the zip's Base64 split into broadcast-sized pieces. */
    public static List<String> pieces(InputStream in) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        long total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES) throw new IllegalArgumentException("backup too large");
            bytes.write(buf, 0, n);
        }
        String b64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP);
        List<String> out = new ArrayList<>();
        int size = 120_000;
        for (int i = 0; i < b64.length(); i += size) out.add(b64.substring(i, Math.min(b64.length(), i + size)));
        return out;
    }
}
