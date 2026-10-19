package ps.reso.instaeclipse.utils.ipc;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.os.ResultReceiver;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import ps.reso.instaeclipse.BuildConfig;
import static org.junit.Assert.*;

/** Synthetic in-process Binder callbacks only; does not request real preferences, logs, or media. */
@RunWith(AndroidJUnit4.class)
public class ModuleRepliesTest {
    private static Intent parcelled(Intent request) {
        Parcel parcel = Parcel.obtain();
        try {
            request.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return Intent.CREATOR.createFromParcel(parcel);
        } finally { parcel.recycle(); }
    }
    private static Bundle marker(String marker) {
        Bundle data = new Bundle();
        data.putString("marker", marker);
        return data;
    }
    private static void drain() { InstrumentationRegistry.getInstrumentation().waitForIdleSync(); }

    @Test public void capabilityCanBeUnmarshalledWithOnlyTheFrameworkClassLoader() {
        AtomicInteger calls = new AtomicInteger();
        ModuleReplies.Pending pending = new ModuleReplies.Pending(data -> calls.incrementAndGet());
        try {
            Intent request = new Intent(); pending.attachTo(request);
            Intent remote = parcelled(request);
            remote.setExtrasClassLoader(ResultReceiver.class.getClassLoader());
            ResultReceiver receiver = remote.getParcelableExtra(BuildConfig.APPLICATION_ID + ".ipc.REPLY");
            assertNotNull(receiver);
            assertEquals(ResultReceiver.class, receiver.getClass());
            assertTrue(ModuleReplies.reply(remote, marker("framework")));
            drain();
            assertEquals(1, calls.get());
        } finally { pending.cancel(); }
    }

    @Test public void parcelledCapabilityDeliversOnlyOnceToItsOwnRequest() {
        List<String> values = new ArrayList<>();
        ModuleReplies.Pending first = new ModuleReplies.Pending(data -> values.add("first:" + data.getString("marker")));
        ModuleReplies.Pending second = new ModuleReplies.Pending(data -> values.add("second:" + data.getString("marker")));
        try {
            Intent a = new Intent(), b = new Intent(); first.attachTo(a); second.attachTo(b);
            Intent remoteA = parcelled(a), remoteB = parcelled(b);
            assertTrue(ModuleReplies.reply(remoteB, marker("B")));
            assertTrue(ModuleReplies.reply(remoteA, marker("A")));
            assertTrue(ModuleReplies.reply(remoteA, marker("duplicate")));
            drain();
            assertEquals(List.of("second:B", "first:A"), values);
        } finally { first.cancel(); second.cancel(); }
    }

    @Test public void cancelledRequestCannotCompleteItsReplacement() {
        AtomicInteger obsoleteCalls = new AtomicInteger();
        AtomicInteger replacementCalls = new AtomicInteger();
        ModuleReplies.Pending old = new ModuleReplies.Pending(data -> obsoleteCalls.incrementAndGet());
        ModuleReplies.Pending current = new ModuleReplies.Pending(data -> replacementCalls.incrementAndGet());
        try {
            Intent oldIntent = new Intent(), currentIntent = new Intent(); old.attachTo(oldIntent); current.attachTo(currentIntent);
            Intent oldRemote = parcelled(oldIntent), currentRemote = parcelled(currentIntent);
            old.cancel();
            assertTrue(ModuleReplies.reply(oldRemote, marker("old")));
            assertTrue(ModuleReplies.reply(currentRemote, marker("current")));
            drain();
            assertEquals(0, obsoleteCalls.get());
            assertEquals(1, replacementCalls.get());
        } finally { old.cancel(); current.cancel(); }
    }

    @Test public void oversizedReplyProducesSmallErrorInsteadOfLargeTransaction() {
        AtomicReference<Bundle> received = new AtomicReference<>();
        ModuleReplies.Pending pending = new ModuleReplies.Pending(received::set);
        try {
            Intent request = new Intent(); pending.attachTo(request);
            Bundle oversized = marker("x".repeat(400_000));
            String nonce = "0123456789abcdef0123456789abcdef";
            oversized.putString(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE, nonce);
            assertTrue(ModuleReplies.reply(parcelled(request), oversized));
            drain();
            assertNotNull(received.get());
            assertNotNull(received.get().getString("error"));
            assertFalse(received.get().containsKey("marker"));
            assertEquals(nonce, received.get().getString(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE));
        } finally { pending.cancel(); }
    }

    @Test public void missingCapabilityAndUntrustedOrImplicitDestinationAreRejected() {
        assertFalse(ModuleReplies.reply(new Intent("synthetic"), marker("no capability")));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertNull(ModuleReplies.request(context, new Intent("synthetic"), data -> fail("Implicit request sent")));
        assertNull(ModuleReplies.request(context, new Intent("synthetic").setPackage(context.getPackageName()),
                data -> fail("Companion is not a trusted host")));
    }
}
