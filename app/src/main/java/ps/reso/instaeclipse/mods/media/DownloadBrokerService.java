package ps.reso.instaeclipse.mods.media;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import ps.reso.instaeclipse.utils.ipc.BrokerPayload;
import ps.reso.instaeclipse.utils.ipc.CompanionBridge;

/** Only this broker is exported. Tokens target private components and cannot be filled in. */
public final class DownloadBrokerService extends Service {
    @Override public void onCreate() {
        super.onCreate();
        FrameSessionCache.cleanupOrphans(getCacheDir());
    }
    private final Handler expiry = new Handler(Looper.getMainLooper());
    private final IBinder broker = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { reply.writeString(CompanionBridge.DESCRIPTOR); return true; }
            if (code < CompanionBridge.DOWNLOAD || code > CompanionBridge.ACTIVITY) return super.onTransact(code, data, reply, flags);
            // onBind cannot authenticate its eventual callers. Check the actual transaction UID.
            if (!CompanionBridge.isTrustedCaller(DownloadBrokerService.this, Binder.getCallingUid()))
                throw new SecurityException("Companion request denied");
            if (reply == null || data.dataSize() > BrokerPayload.MAX_PARCEL_BYTES || data.hasFileDescriptors())
                throw new IllegalArgumentException("Invalid companion request");
            data.enforceInterface(CompanionBridge.DESCRIPTOR);
            Intent target;
            if (code == CompanionBridge.DOWNLOAD) {
                String json = BrokerPayload.request(data.readString()).toJson();
                target = new Intent(DownloadBrokerService.this, DownloadSaveService.class)
                        .putExtra(DownloadSaveService.EXTRA_REQUEST, json);
            } else if (code == CompanionBridge.LEGACY) {
                android.os.Bundle values = ps.reso.instaeclipse.utils.ipc.LegacyBrokerPayload.validate(
                        data.readBundle(android.os.Bundle.class.getClassLoader()));
                target = new Intent(DownloadBrokerService.this, DownloadSaveService.class).putExtras(values);
            } else {
                String name = data.readString();
                int count = data.readInt();
                if (count < 0 || count > 4) throw new IllegalArgumentException("Invalid companion request");
                Map<String, String> extras = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    String key = data.readString(), value = data.readString();
                    if (key == null || value == null || extras.put(key, value) != null)
                        throw new IllegalArgumentException("Invalid companion request");
                }
                extras = BrokerPayload.activity(name, extras);
                target = new Intent().setClassName(DownloadBrokerService.this, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                for (Map.Entry<String, String> item : extras.entrySet()) {
                    if (BrokerPayload.LOCATION.equals(name)) target.putExtra(item.getKey(),
                            BrokerPayload.coordinate(item.getValue(), item.getKey().equals("lat") ? 90 : 180));
                    else target.putExtra(item.getKey(), item.getValue());
                }
            }
            if (data.dataAvail() != 0) throw new IllegalArgumentException("Invalid companion request");
            target.setData(new Uri.Builder().scheme("instaeclipse-broker").authority("job")
                    .appendPath(UUID.randomUUID().toString()).build());
            long identity = Binder.clearCallingIdentity();
            try {
                int tokenFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_ONE_SHOT;
                PendingIntent token;
                if (code == CompanionBridge.ACTIVITY) {
                    ActivityOptions options = ActivityOptions.makeBasic();
                    // API 35 creator opt-in, while keeping compilation/API 28 compatibility.
                    if (Build.VERSION.SDK_INT >= 35) {
                        try { ActivityOptions.class.getMethod("setPendingIntentCreatorBackgroundActivityStartMode", int.class)
                                .invoke(options, 1); } catch (ReflectiveOperationException error) {
                            throw new IllegalStateException("Companion launch unavailable");
                        }
                    }
                    token = PendingIntent.getActivity(DownloadBrokerService.this, 0, target, tokenFlags, options.toBundle());
                } else token = PendingIntent.getForegroundService(DownloadBrokerService.this, 0, target, tokenFlags);
                // Never retain signed URLs in a persistent queue; unused capabilities expire.
                expiry.postDelayed(token::cancel, 30_000);
                reply.writeNoException();
                token.writeToParcel(reply, Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
                return true;
            } finally { Binder.restoreCallingIdentity(identity); }
        }
    };
    @Override public IBinder onBind(Intent intent) { return broker; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        stopSelfResult(startId);
        return START_NOT_STICKY;
    }
}
