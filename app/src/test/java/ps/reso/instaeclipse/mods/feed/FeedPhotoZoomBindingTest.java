package ps.reso.instaeclipse.mods.feed;

import android.widget.FrameLayout;
import android.widget.ImageView;
import org.junit.Test;
import static org.junit.Assert.*;

public class FeedPhotoZoomBindingTest {
    public static class Current extends FrameLayout {
        public Current() { super(null); }
        @Override public void onAttachedToWindow() {}
        public ImageView getIgImageView() { return null; }
    }
    public static class Legacy extends Current {
        @Override public void onAttachedToWindow() {}
    }
    public static class WrongChild extends FrameLayout {
        public WrongChild() { super(null); }
        @Override public void onAttachedToWindow() {}
        public Object getIgImageView() { return null; }
    }
    public static class InheritedAttach extends Current {}

    @Test public void prefersCurrentTypedWrapper() {
        FeedPhotoZoomHook.ViewBinding binding = resolve(Current.class.getName(), Legacy.class.getName());
        assertNotNull(binding);
        assertEquals(Current.class, binding.attach.getDeclaringClass());
        assertEquals(ImageView.class, binding.image.getReturnType());
    }

    @Test public void missingOrIncompatibleCurrentWrapperFallsBackToLegacy() {
        FeedPhotoZoomHook.ViewBinding binding = resolve("missing.host.Wrapper", WrongChild.class.getName(), Legacy.class.getName());
        assertNotNull(binding);
        assertEquals(Legacy.class, binding.attach.getDeclaringClass());
    }

    @Test public void rejectsUnprovenChildAndInheritedPlatformLifecycle() {
        assertNull(resolve(WrongChild.class.getName(), InheritedAttach.class.getName(), String.class.getName()));
    }

    private static FeedPhotoZoomHook.ViewBinding resolve(String... names) {
        return FeedPhotoZoomHook.ViewBinding.resolve(FeedPhotoZoomBindingTest.class.getClassLoader(), names);
    }
}
