package ps.reso.instaeclipse.utils.ipc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.ResultReceiver;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import ps.reso.instaeclipse.BuildConfig;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

/** Protected commands and request-specific Binder replies; no public reply broadcasts. */
public final class ModuleReplies {
    public static final String CONTROL_PERMISSION = ps.reso.instaeclipse.utils.core.IpcSecurity.PERMISSION;
    private static final String EXTRA_REPLY = BuildConfig.APPLICATION_ID + ".ipc.REPLY";
    private static final int RESULT = 1;
    private static final long REPLY_LIFETIME_MS = 30_000;
    public static final int MAX_CONFIG_CHARS = 200_000;
    static final int MAX_PREFERENCE_STRING_CHARS = 16 * 1024;
    private static final int MAX_REPLY_BYTES = 512 * 1024;
    private static final Map<String, Class<?>> PREFERENCE_TYPES = preferenceTypes();

    private ModuleReplies() { }

    public interface Callback { void onReply(Bundle data); }

    /** The permission applies to the broadcaster, not to the hooked host's UID. */
    public static void registerControlReceiver(Context context, BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, CONTROL_PERMISSION, null, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter, CONTROL_PERMISSION, null);
        }
    }

    /** Sends a capability only to an explicitly selected, certificate-verified host. */
    public static Pending request(Context context, Intent request, Callback callback) {
        String target = request.getPackage();
        if (target == null || !CompanionBridge.isTrustedHost(context, target)) return null;
        Pending pending = new Pending(callback);
        try {
            Intent addressed = new Intent(request);
            pending.attachTo(addressed);
            context.sendBroadcast(addressed);
            return pending;
        } catch (RuntimeException unavailable) {
            pending.cancel();
            return null;
        }
    }

    /** Called only from a permission-protected host receiver. Never falls back to broadcasting. */
    public static boolean reply(Intent request, Bundle data) {
        try {
            ResultReceiver receiver = request.getParcelableExtra(EXTRA_REPLY);
            if (receiver == null) return false;
            Bundle outgoing = data == null ? new Bundle() : new Bundle(data);
            Parcel parcel = Parcel.obtain();
            try {
                outgoing.writeToParcel(parcel, 0);
                if (parcel.dataSize() > MAX_REPLY_BYTES) {
                    String nonce = outgoing.getString(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE);
                    outgoing = new Bundle();
                    if (nonce != null && nonce.matches("[0-9a-f]{32}"))
                        outgoing.putString(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE, nonce);
                    outgoing.putString("error", "Module reply exceeds the supported size");
                }
            } finally { parcel.recycle(); }
            receiver.send(RESULT, outgoing);
            return true;
        } catch (RuntimeException malformedOrUnavailable) {
            return false;
        }
    }

    /** Cancellation/reply delivery run on the main thread; canceled capabilities retain no UI callback. */
    public static final class Pending {
        private final Handler main = new Handler(Looper.getMainLooper());
        private Callback callback;
        private final Runnable expiry = this::cancel;
        private final ResultReceiver receiver = new ResultReceiver(main) {
            @Override protected void onReceiveResult(int code, Bundle data) {
                if (code != RESULT || data == null) return;
                Callback accepted;
                synchronized (Pending.this) {
                    accepted = callback;
                    callback = null;
                }
                main.removeCallbacks(expiry);
                if (accepted != null) accepted.onReply(data);
            }
        };

        Pending(Callback callback) {
            this.callback = callback;
            main.postDelayed(expiry, REPLY_LIFETIME_MS);
        }
        void attachTo(Intent request) {
            // Bundles record the concrete Parcelable class name. Send a framework instance so
            // the host can unmarshal this capability without our anonymous subclass's loader.
            Parcel parcel = Parcel.obtain();
            try {
                receiver.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                request.putExtra(EXTRA_REPLY, ResultReceiver.CREATOR.createFromParcel(parcel));
            } finally { parcel.recycle(); }
        }
        public synchronized void cancel() {
            callback = null;
            main.removeCallbacks(expiry);
        }
    }

    /** Reflect the existing settings schema instead of maintaining a second list of feature keys. */
    private static Map<String, Class<?>> preferenceTypes() {
        Map<String, Class<?>> types = new HashMap<>();
        for (Field field : FeatureFlags.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) continue;
            Class<?> type = field.getType();
            if (type == boolean.class) types.put(field.getName(), Boolean.class);
            else if (type == int.class) types.put(field.getName(), Integer.class);
            else if (type == String.class) types.put(field.getName(), String.class);
        }
        for (String flag : ps.reso.instaeclipse.utils.feature.ExtrasFeatures.flags()) types.put(flag, Boolean.class);
        // SettingsManager persists these double-valued flags as strings.
        types.put("spoofLat", String.class);
        types.put("spoofLng", String.class);
        return Collections.unmodifiableMap(types);
    }

    /** Reject mismatched actions before typed Intent getters can supply a default of the wrong type. */
    public static boolean isPreferenceUpdate(String action, String key, Object value) {
        String prefix = BuildConfig.APPLICATION_ID;
        boolean actionType = (prefix + ".ACTION_UPDATE_PREF").equals(action) && value instanceof Boolean
                || (prefix + ".ACTION_UPDATE_PREF_STRING").equals(action) && value instanceof String
                || (prefix + ".ACTION_UPDATE_PREF_INT").equals(action) && value instanceof Integer;
        return actionType && isPreferenceValue(key, value);
    }

    public static boolean isPreferenceValue(String key, Object value) {
        Class<?> type = PREFERENCE_TYPES.get(key);
        return type != null && type.isInstance(value)
                && (!(value instanceof String) || ((String) value).length() <= MAX_PREFERENCE_STRING_CHARS);
    }
}
