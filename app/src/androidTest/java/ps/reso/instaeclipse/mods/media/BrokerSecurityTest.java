package ps.reso.instaeclipse.mods.media;

import static org.junit.Assert.*;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import ps.reso.instaeclipse.BuildConfig;
import ps.reso.instaeclipse.utils.ipc.BrokerPayload;
import ps.reso.instaeclipse.utils.ipc.CompanionBridge;
import ps.reso.instaeclipse.utils.ipc.ModuleReplies;

/** Synthetic capabilities only: never starts a download, reads media, or changes a setting. */
@RunWith(AndroidJUnit4.class)
public class BrokerSecurityTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private final Context context = instrumentation.getTargetContext();
    private final CountDownLatch connected = new CountDownLatch(1);
    private IBinder broker;
    private boolean bound;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) { broker = binder; connected.countDown(); }
        @Override public void onServiceDisconnected(ComponentName name) { broker = null; }
    };
    @Before public void connect() throws Exception {
        bound = context.bindService(new Intent(context, DownloadBrokerService.class), connection, Context.BIND_AUTO_CREATE);
        assertTrue(bound);
        assertTrue("Broker bind timed out", connected.await(10, TimeUnit.SECONDS));
    }
    @After public void cleanup() { if (bound) context.unbindService(connection); }

    @Test public void privilegedComponentsArePrivateAndControlPermissionIsSignature() throws Exception {
        PackageManager pm = context.getPackageManager();
        assertFalse(pm.getServiceInfo(new ComponentName(context, DownloadSaveService.class), 0).exported);
        for (String name : new String[]{BrokerPayload.IMPORT, BrokerPayload.EXPORT, BrokerPayload.LOCATION,
                BrokerPayload.THEME, BrokerPayload.FRAME})
            assertFalse(pm.getActivityInfo(new ComponentName(context, name), 0).exported);
        assertTrue(pm.getServiceInfo(new ComponentName(context, DownloadBrokerService.class), 0).exported);
        PermissionInfo permission = pm.getPermissionInfo(ModuleReplies.CONTROL_PERMISSION, 0);
        assertEquals(PermissionInfo.PROTECTION_SIGNATURE, permission.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE);
        assertEquals(PackageManager.PERMISSION_GRANTED, pm.checkPermission(ModuleReplies.CONTROL_PERMISSION, context.getPackageName()));
    }

    @Test public void installedHostCertificateIsTrustedButPackageClaimsAreNot() throws Exception {
        assertTrue(CompanionBridge.isTrustedHost(context, "com.instagram.android"));
        assertFalse(CompanionBridge.isTrustedHost(context, "com.instagramclone.android"));
        assertFalse(CompanionBridge.isTrustedHost(context, context.getPackageName()));
        assertFalse(CompanionBridge.isTrustedCaller(context, 2000)); // shell cannot impersonate Instagram
        int hostUid = context.getPackageManager().getApplicationInfo("com.instagram.android", 0).uid;
        assertTrue(CompanionBridge.isTrustedCaller(context, hostUid));
    }

    @Test public void authorizedCompanionGetsTypedPreferencesFromRunningHost() throws Exception {
        CountDownLatch answered = new CountDownLatch(1);
        AtomicReference<Bundle> received = new AtomicReference<>();
        Intent request = new Intent(BuildConfig.APPLICATION_ID + ".ACTION_REQUEST_PREFS")
                .setPackage("com.instagram.android");
        ModuleReplies.Pending pending = ModuleReplies.request(context, request, data -> {
            received.set(data);
            answered.countDown();
        });
        assertNotNull("Verified host unavailable", pending);
        try {
            assertTrue("Running host did not answer protected request", answered.await(10, TimeUnit.SECONDS));
            Bundle data = received.get();
            assertFalse("Host returned no preferences", data.isEmpty());
            for (String key : data.keySet())
                assertTrue("Unexpected preference schema", ModuleReplies.isPreferenceValue(key, data.get(key)));
        } finally { pending.cancel(); received.set(null); }
    }

    @Test public void malformedRequestIsRejectedBeforeAnyDownload() throws Exception {
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(CompanionBridge.DESCRIPTOR);
            request.writeString("{}");
            try {
                assertTrue(broker.transact(CompanionBridge.DOWNLOAD, request, reply, 0));
                reply.readException();
                fail("Empty download request accepted");
            }
            catch (IllegalArgumentException expected) { }
        } finally { request.recycle(); reply.recycle(); }
    }

    private PendingIntent themeToken() throws Exception {
        Parcel request = Parcel.obtain(), reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(CompanionBridge.DESCRIPTOR);
            request.writeString(BrokerPayload.THEME);
            request.writeInt(0);
            assertTrue(broker.transact(CompanionBridge.ACTIVITY, request, reply, 0));
            reply.readException();
            return PendingIntent.CREATOR.createFromParcel(reply);
        } finally { request.recycle(); reply.recycle(); }
    }

    @Test public void authorizedCapabilityIgnoresTamperingAndRejectsReplay() throws Exception {
        PendingIntent token = themeToken();
        assertEquals(BuildConfig.APPLICATION_ID, token.getCreatorPackage());
        if (Build.VERSION.SDK_INT >= 31) assertTrue(token.isImmutable());
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(BrokerPayload.THEME, null, false);
        Activity opened = null;
        try {
            Intent injected = new Intent().setClassName(context, BrokerPayload.LOCATION)
                    .putExtra("security_probe_marker_v24", "must_not_arrive");
            ActivityOptions options = ActivityOptions.makeBasic();
            if (Build.VERSION.SDK_INT >= 34) options.setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            token.send(context, 0, injected, null, null, null, options.toBundle());
            opened = monitor.waitForActivityWithTimeout(8000);
            assertNotNull("Authorized theme capability did not open", opened);
            assertEquals(BrokerPayload.THEME, opened.getClass().getName());
            assertFalse(opened.getIntent().hasExtra("security_probe_marker_v24"));
            try { token.send(); fail("One-shot capability replayed"); }
            catch (PendingIntent.CanceledException expected) { }
        } finally {
            Activity activity = opened;
            if (activity != null) instrumentation.runOnMainSync(activity::finish);
            instrumentation.removeMonitor(monitor);
            token.cancel();
        }
    }
}
