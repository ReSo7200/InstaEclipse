package ps.reso.instaeclipse.mods.media;

import org.junit.Test;

import java.io.File;

import static org.junit.Assert.*;

public class InstantUploadBindingTest {
    @Test public void resolves447PhotoHandoffAndExcludesVideoAndArchivePaths() {
        InstantUploadHook.SwapBinding binding = resolve(LegacyVm.class);
        assertNotNull(binding);
        assertEquals("photo", binding.method.getName());
        assertEquals(2, binding.secondaryBitmapIndex);
    }

    @Test public void resolves450SingleBitmapPhotoHandoff() {
        InstantUploadHook.SwapBinding binding = resolve(ModernVm.class);
        assertNotNull(binding);
        assertEquals("photo", binding.method.getName());
        assertEquals(-1, binding.secondaryBitmapIndex);
    }

    @Test public void preservesNewerSingleBitmapFamilyWithoutFixedArgumentCount() {
        InstantUploadHook.SwapBinding binding = resolve(NewerVm.class);
        assertNotNull(binding);
        assertEquals("photo", binding.method.getName());
        assertEquals(-1, binding.secondaryBitmapIndex);
        Object[] args = {new ContextValue(), new BitmapValue(), new Metadata(), new Continuation(),
                1L, new NewerVm(), "caption", false, new Callback(), new Metadata(), 2};
        Object[] original = args.clone();
        BitmapValue selected = new BitmapValue();
        assertTrue(binding.replace(args, selected, null));
        assertSame(selected, args[1]);
        for (int i = 0; i < args.length; i++) if (i != 1) assertSame(original[i], args[i]);
    }

    @Test public void modernSwapPreservesCaptureMetadataAndEveryOtherArgument() {
        InstantUploadHook.SwapBinding binding = resolve(ModernVm.class);
        Object[] args = modernArgs(new ModernVm());
        Object[] before = args.clone();
        BitmapValue selected = new BitmapValue();
        assertFalse(binding.needsSecondary(args));
        assertTrue(binding.replace(args, selected, null));
        assertSame(selected, args[1]);
        for (int i = 0; i < args.length; i++) if (i != 1) assertSame(before[i], args[i]);
    }

    @Test public void legacySwapReplacesOnlyTheTwoBitmapSlots() {
        InstantUploadHook.SwapBinding binding = resolve(LegacyVm.class);
        Object[] args = legacyArgs();
        Object[] before = args.clone();
        BitmapValue primary = new BitmapValue(), secondary = new BitmapValue();
        assertTrue(binding.needsSecondary(args));
        assertTrue(binding.replace(args, primary, secondary));
        assertSame(primary, args[1]);
        assertSame(secondary, args[2]);
        for (int i = 0; i < args.length; i++) if (i != 1 && i != 2) assertSame(before[i], args[i]);
    }

    @Test public void legacyNullSecondaryRemainsNull() {
        InstantUploadHook.SwapBinding binding = resolve(LegacyVm.class);
        Object[] args = legacyArgs();
        args[2] = null;
        assertFalse(binding.needsSecondary(args));
        assertTrue(binding.replace(args, new BitmapValue(), null));
        assertNull(args[2]);
    }

    @Test public void failedLegacyCopyLeavesArgumentsUnchanged() {
        InstantUploadHook.SwapBinding binding = resolve(LegacyVm.class);
        Object[] args = legacyArgs();
        Object[] before = args.clone();
        assertFalse(binding.replace(args, new BitmapValue(), null));
        assertArrayEquals(before, args);
    }

    @Test public void suspendReentryAndWrongVmCannotConsumeSelectedBitmap() {
        InstantUploadHook.SwapBinding binding = resolve(ModernVm.class);
        Object[] args = modernArgs(new ModernVm());
        args[1] = null;
        Object[] before = args.clone();
        assertFalse(binding.replace(args, new BitmapValue(), null));
        assertArrayEquals(before, args);
        args[1] = new BitmapValue();
        args[3] = new LegacyVm();
        assertFalse(binding.accepts(args));
        assertFalse(binding.accepts(null));
        assertFalse(binding.accepts(new Object[2]));
    }

    @Test public void ambiguityAndUnrelatedBitmapMethodsFailClosed() {
        assertNull(resolve(AmbiguousVm.class));
        assertNull(resolve(UnrelatedVm.class));
        assertNull(resolve(AmbiguousModernVm.class));
    }

    private static InstantUploadHook.SwapBinding resolve(Class<?> vm) {
        return InstantUploadHook.SwapBinding.resolve(vm, ContextValue.class, BitmapValue.class);
    }

    private static Object[] modernArgs(ModernVm vm) {
        return new Object[]{new ContextValue(), new BitmapValue(), new Metadata(), vm, new Metadata(),
                5L, "caption", "source", new Continuation(), new Metadata(), new Callback(), 10L, true};
    }

    private static Object[] legacyArgs() {
        return new Object[]{new ContextValue(), new BitmapValue(), new BitmapValue(), new LegacyVm(),
                new Metadata(), "caption", "source", new Continuation(), 10L};
    }

    static class ContextValue { }
    static class BitmapValue { }
    static class Metadata { }
    static class Continuation { }
    static class Callback { }

    static class LegacyVm {
        static Object photo(ContextValue c, BitmapValue b, BitmapValue second, LegacyVm vm,
                Metadata data, String text, String source, Continuation continuation, long time) { return null; }
        static Object video(ContextValue c, BitmapValue b, LegacyVm vm, File file, String text,
                Continuation continuation, int width, long time) { return null; }
        static void archive(ContextValue c, BitmapValue b, LegacyVm vm) { }
    }

    static class ModernVm {
        static Object photo(ContextValue c, BitmapValue b, Metadata capture, ModernVm vm, Metadata data,
                Long time, String text, String source, Continuation continuation, Metadata state,
                Callback callback, long timestamp, boolean flag) { return null; }
        static Object video(ContextValue c, BitmapValue b, Metadata capture, ModernVm vm, File file,
                Float rate, String text, Continuation continuation, int width, int height, long time) { return null; }
        static void archive(ContextValue c, BitmapValue b, ModernVm vm) { }
    }

    static class AmbiguousVm {
        static Object first(ContextValue c, BitmapValue b, BitmapValue second, AmbiguousVm vm,
                Metadata data, String text, String source, Continuation continuation, long time) { return null; }
        static Object second(ContextValue c, BitmapValue b, BitmapValue second, AmbiguousVm vm,
                Metadata data, String text, String source, Continuation continuation, long time) { return null; }
    }

    static class NewerVm {
        static Object photo(ContextValue c, BitmapValue b, Metadata capture, Continuation continuation,
                long time, NewerVm vm, String caption, boolean flag, Callback callback,
                Metadata data, int mode) { return null; }
        static Object video(ContextValue c, BitmapValue b, Metadata capture, NewerVm vm,
                File file, Continuation continuation) { return null; }
    }

    static class AmbiguousModernVm {
        static Object first(ContextValue c, BitmapValue b, Metadata capture, AmbiguousModernVm vm,
                Continuation continuation) { return null; }
        static Object second(ContextValue c, BitmapValue b, Metadata capture, AmbiguousModernVm vm,
                Continuation continuation, boolean flag) { return null; }
    }

    static class UnrelatedVm {
        Object instance(ContextValue c, BitmapValue b, BitmapValue second, UnrelatedVm vm,
                Metadata data, String text, String source, Continuation continuation, long time) { return null; }
        static void thumbnail(ContextValue c, BitmapValue b, BitmapValue second, UnrelatedVm vm,
                Metadata data, String text, String source, Continuation continuation, long time) { }
        static Object unrelated(ContextValue c, BitmapValue b, BitmapValue second) { return null; }
    }
}
