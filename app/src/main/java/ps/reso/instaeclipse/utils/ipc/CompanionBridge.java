package ps.reso.instaeclipse.utils.ipc;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Process;
import android.view.View;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import ps.reso.instaeclipse.BuildConfig;
import ps.reso.instaeclipse.utils.download.DownloadRequest;

/** Authenticated capability client. No host URL, configuration or exception text is logged. */
public final class CompanionBridge {
    public static final String DESCRIPTOR = BuildConfig.APPLICATION_ID + ".CompanionBroker.v1";
    public static final int DOWNLOAD = IBinder.FIRST_CALL_TRANSACTION;
    public static final int LEGACY = DOWNLOAD + 1;
    public static final int ACTIVITY = DOWNLOAD + 2;
    public static final String SERVICE = "ps.reso.instaeclipse.mods.media.DownloadBrokerService";
    // Official Instagram signing identity; never enroll a host by package name alone.
    // hasSigningCertificate also validates its signing-history descendants (APK v3 rotation).
    private static final String HOST_SIGNER_SHA256 = "5f3e50f435583c9ae626302a71f7340044087a7e2c60adacfc254205a993e305";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ThreadPoolExecutor IPC = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), new ThreadPoolExecutor.AbortPolicy());
    private CompanionBridge() { }

    public interface Callback { void onResult(String error); }
    private interface Writer { void write(Parcel parcel); }

    /** Name alone never enrolls a host. This intentionally excludes companion and clone packages. */
    public static boolean isTrustedHost(Context context, String packageName) {
        if (context == null || !BrokerPayload.HOST.equals(packageName)) return false;
        try {
            byte[] expected = new byte[32];
            if (HOST_SIGNER_SHA256.length() != 64) return false;
            for (int i = 0; i < expected.length; i++)
                expected[i] = (byte) Integer.parseInt(HOST_SIGNER_SHA256.substring(i * 2, i * 2 + 2), 16);
            if (context.getPackageManager().hasSigningCertificate(packageName, expected, PackageManager.CERT_INPUT_SHA256))
                return true;
            return lspatchOriginalSigner(context, packageName, expected);
        } catch (RuntimeException ignored) { return false; }
    }

    /** Patched Instagram (sourceDir + install time) -> whether its recorded signer matched. */
    private static volatile String lspatchCheckedFor;
    private static volatile boolean lspatchTrusted;

    /**
     * LSPatch re-signs the app it patches with its own key and records the original signer in
     * assets/lspatch/config.json ("originalSignature", the certificate in hex). Without root that
     * is how Instagram arrives, so its recorded signer is checked against the same pinned hash.
     */
    private static boolean lspatchOriginalSigner(Context context, String packageName, byte[] expected) {
        try {
            android.content.pm.PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            String key = info.applicationInfo.sourceDir + "@" + info.lastUpdateTime;
            if (key.equals(lspatchCheckedFor)) return lspatchTrusted;
            boolean trusted = false;
            try (java.util.zip.ZipFile apk = new java.util.zip.ZipFile(info.applicationInfo.sourceDir)) {
                java.util.zip.ZipEntry entry = apk.getEntry("assets/lspatch/config.json");
                if (entry != null && entry.getSize() > 0 && entry.getSize() < 64 * 1024) {
                    byte[] raw;
                    try (java.io.InputStream in = apk.getInputStream(entry)) { raw = in.readAllBytes(); }
                    String cert = new org.json.JSONObject(new String(raw, java.nio.charset.StandardCharsets.UTF_8))
                            .optString("originalSignature", "");
                    if (!cert.isEmpty() && cert.length() % 2 == 0 && cert.matches("[0-9a-fA-F]+")) {
                        byte[] der = new byte[cert.length() / 2];
                        for (int i = 0; i < der.length; i++)
                            der[i] = (byte) Integer.parseInt(cert.substring(i * 2, i * 2 + 2), 16);
                        trusted = java.security.MessageDigest.isEqual(expected,
                                java.security.MessageDigest.getInstance("SHA-256").digest(der));
                    }
                }
            }
            lspatchTrusted = trusted;
            lspatchCheckedFor = key;
            return trusted;
        } catch (Exception ignored) { return false; }
    }

    /** Called only inside a Binder transaction with Binder.getCallingUid(), never a claimed extra. */
    public static boolean isTrustedCaller(Context context, int uid) {
        if (uid == Process.myUid()) return true;
        try {
            PackageManager pm = context.getPackageManager();
            String[] packages = pm.getPackagesForUid(uid);
            return packages != null && packages.length == 1 && BrokerPayload.HOST.equals(packages[0])
                    && pm.getApplicationInfo(BrokerPayload.HOST, 0).uid == uid
                    && isTrustedHost(context, BrokerPayload.HOST);
        } catch (Exception ignored) { return false; }
    }

    public static void download(Context context, DownloadRequest request, Callback callback) {
        try {
            String json = BrokerPayload.request(request.toJson()).toJson();
            begin(context, DOWNLOAD, parcel -> parcel.writeString(json), callback);
        } catch (RuntimeException invalid) { report(callback, "Invalid download request"); }
    }

    public static void saveFrame(Context context, DownloadRequest request, Callback callback) {
        try {
            Map<String, String> values = new LinkedHashMap<>();
            values.put(BrokerPayload.FRAME_REQUEST, request.toJson());
            openActivity(context, BrokerPayload.FRAME, values, callback);
        } catch (RuntimeException invalid) { report(callback, "Invalid frame request"); }
    }

    public static void downloadLegacy(Context context, String url, String audioUrl, String filename,
            String mime, String owner, Callback callback) {
        Intent legacy = new Intent().putExtra("url", url).putExtra("audioUrl", audioUrl)
                .putExtra("filename", filename).putExtra("mimeType", mime).putExtra("username", owner);
        downloadLegacy(context, legacy, callback);
    }

    /** Captures all upstream download/export options before crossing the process boundary. */
    public static void downloadLegacy(Context context, Intent intent, Callback callback) {
        try {
            Bundle extras = LegacyBrokerPayload.validate(intent == null ? null : intent.getExtras());
            begin(context, LEGACY, parcel -> parcel.writeBundle(extras), callback);
        } catch (RuntimeException invalid) { report(callback, "Invalid or oversized download request"); }
    }

    /** True means scheduled, not launched. Completion/failure is reported asynchronously. */
    public static boolean launchActivity(Context context, Intent intent) {
        try {
            ComponentName component = intent.getComponent();
            if (component == null || !BuildConfig.APPLICATION_ID.equals(component.getPackageName()))
                throw new IllegalArgumentException();
            String name = component.getClassName();
            Map<String, String> extras = new LinkedHashMap<>();
            Bundle values = intent.getExtras();
            if (values != null) {
                if (values.size() > 4) throw new IllegalArgumentException();
                for (String key : values.keySet()) {
                    Object value = values.get(key);
                    if (BrokerPayload.LOCATION.equals(name) && (key.equals("lat") || key.equals("lng"))
                            && value instanceof Double) extras.put(key, Double.toString((Double) value));
                    else if (value instanceof String) extras.put(key, (String) value);
                    else throw new IllegalArgumentException();
                }
            }
            openActivity(context, name, extras, error -> { if (error != null) errorToast(context, error); });
            return true;
        } catch (RuntimeException invalid) { errorToast(context, "Invalid companion activity request"); return false; }
    }

    public static void openActivity(Context context, String name, Map<String, String> values, Callback callback) {
        try {
            Map<String, String> extras = BrokerPayload.activity(name, values);
            begin(context, ACTIVITY, parcel -> {
                parcel.writeString(name); parcel.writeInt(extras.size());
                for (Map.Entry<String, String> extra : extras.entrySet()) {
                    parcel.writeString(extra.getKey()); parcel.writeString(extra.getValue());
                }
            }, callback);
        } catch (RuntimeException invalid) { report(callback, "Invalid companion activity request"); }
    }

    private static void begin(Context context, int code, Writer writer, Callback callback) {
        if (context == null) { report(callback, "Companion unavailable"); return; }
        MAIN.post(() -> new Operation(context, code, writer, callback).start());
    }
    private static void report(Callback callback, String error) {
        MAIN.post(() -> { if (callback != null) callback.onResult(error); });
    }
    private static void errorToast(Context context, String error) {
        if (context == null) return;
        MAIN.post(() -> Toast.makeText(context, error, Toast.LENGTH_LONG).show());
    }

    private static Activity activity(Context context) {
        for (int depth = 0; context != null && depth < 12; depth++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) break;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }
    private static boolean visible(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed()
                && activity.getWindow() != null
                && activity.getWindow().getDecorView().getWindowVisibility() == View.VISIBLE;
    }

    private static final class Operation implements ServiceConnection {
        final Context caller, app;
        final int code;
        final Writer writer;
        final Callback callback;
        final AtomicBoolean complete = new AtomicBoolean();
        final Runnable timeout = () -> finish(null, "Companion connection timed out");
        boolean bound;
        int expectedUid;
        Operation(Context context, int code, Writer writer, Callback callback) {
            caller = context; app = context.getApplicationContext() == null ? context : context.getApplicationContext();
            this.code = code; this.writer = writer; this.callback = callback;
        }
        void start() {
            if (code == ACTIVITY && !visible(activity(caller))) { finish(null, "Open this action from the current screen"); return; }
            MAIN.postDelayed(timeout, 10_000);
            try {
                expectedUid = app.getPackageManager().getApplicationInfo(BuildConfig.APPLICATION_ID, 0).uid;
                bound = app.bindService(new Intent().setClassName(BuildConfig.APPLICATION_ID, SERVICE), this, Context.BIND_AUTO_CREATE);
                if (!bound) finish(null, "Companion connection unavailable");
            } catch (Exception error) { finish(null, "Companion connection unavailable"); }
        }
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (complete.get()) return;
            try { IPC.execute(() -> transact(binder)); }
            catch (RuntimeException busy) { finish(null, "Companion is busy; try again"); }
        }
        void transact(IBinder binder) {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            PendingIntent token = null;
            try {
                if (complete.get()) return;
                data.writeInterfaceToken(DESCRIPTOR); writer.write(data);
                if (data.dataSize() > BrokerPayload.MAX_PARCEL_BYTES) throw new IllegalArgumentException();
                if (!binder.transact(code, data, reply, 0)) throw new IllegalStateException();
                reply.readException();
                token = PendingIntent.CREATOR.createFromParcel(reply);
                if (reply.dataAvail() != 0 || token == null || !BuildConfig.APPLICATION_ID.equals(token.getCreatorPackage())
                        || token.getCreatorUid() != expectedUid
                        || (Build.VERSION.SDK_INT >= 31 && (!token.isImmutable()
                            || (code == ACTIVITY ? !token.isActivity() : !token.isForegroundService())))) throw new SecurityException();
                finish(token, null);
            } catch (SecurityException denied) {
                if (token != null) token.cancel();
                finish(null, "Companion request denied");
            } catch (Exception failure) {
                if (token != null) token.cancel();
                finish(null, "Companion request failed");
            } finally { data.recycle(); reply.recycle(); }
        }
        void finish(PendingIntent token, String error) {
            MAIN.post(() -> {
                if (!complete.compareAndSet(false, true)) { if (token != null) token.cancel(); return; }
                MAIN.removeCallbacks(timeout);
                if (bound) { try { app.unbindService(this); } catch (RuntimeException ignored) { } bound = false; }
                String result = error;
                if (token != null) {
                    try {
                        if (code == ACTIVITY && !visible(activity(caller))) throw new IllegalStateException();
                        Bundle options = null;
                        if (code == ACTIVITY && Build.VERSION.SDK_INT >= 34) {
                            ActivityOptions launch = ActivityOptions.makeBasic();
                            launch.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                            options = launch.toBundle();
                        }
                        token.send(caller, 0, null, null, null, null, options);
                    } catch (Exception failure) { token.cancel(); result = "Companion launch failed; try again from the current screen"; }
                }
                if (callback != null) callback.onResult(result);
            });
        }
        @Override public void onServiceDisconnected(ComponentName name) { finish(null, "Companion disconnected"); }
        @Override public void onBindingDied(ComponentName name) { finish(null, "Companion connection closed"); }
        @Override public void onNullBinding(ComponentName name) { finish(null, "Companion connection unavailable"); }
    }
}
