package ps.reso.instaeclipse.mods.misc;

import android.view.View;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.function.Supplier;
import org.junit.Test;
import static org.junit.Assert.*;

public class DisableDoubleTapLikeBindingTest {
    interface Action { Object invoke(Object item, Object state, Object location, Object doubleTap); }
    static class MergedAction implements Action {
        public Object invoke(Object item, Object state, Object location, Object doubleTap) { return null; }
        @Override public boolean equals(Object other) { return other instanceof MergedAction; }
        @Override public int hashCode() { return 1; }
    }
    static class Handler {
        void setLike(Action action) {}
        void doubleTap(View view, Object item, Object state, Object location, boolean skip, boolean override) {}
        void singleTap(View view) {}
    }
    static class UnrelatedHandler { void setLike(Action action) {} }
    static class WrongAction { public Object invoke(Object a, Object b, Object c, Object d) { return null; } }

    private static Method dispatcher() throws Exception {
        return Handler.class.getDeclaredMethod("doubleTap", View.class, Object.class, Object.class,
                Object.class, boolean.class, boolean.class);
    }
    private static Method invoke(Class<?> type) throws Exception {
        return type.getDeclaredMethod("invoke", Object.class, Object.class, Object.class, Object.class);
    }
    private static Supplier<StackTraceElement[]> stack(Method method) {
        return () -> new StackTraceElement[]{new StackTraceElement(method.getDeclaringClass().getName(), method.getName(), "Fixture", 1)};
    }
    private static Object[] args(boolean doubleTap) { return new Object[]{new Object(), new Object(), null, doubleTap}; }

    @Test public void requiresSameOwnerActualFunctionContractAndInvocationProof() throws Exception {
        Method setter = Handler.class.getDeclaredMethod("setLike", Action.class);
        Method action = invoke(MergedAction.class);
        assertNotNull(DisableDoubleTapLikeHook.LikeActionBinding.create(setter, dispatcher(), action,
                Collections.singletonList(invoke(Action.class))));
        assertNull(DisableDoubleTapLikeHook.LikeActionBinding.create(setter, dispatcher(), action, Collections.emptyList()));
        assertNull(DisableDoubleTapLikeHook.LikeActionBinding.create(
                UnrelatedHandler.class.getDeclaredMethod("setLike", Action.class), dispatcher(), action,
                Collections.singletonList(invoke(Action.class))));
        assertNull(DisableDoubleTapLikeHook.LikeActionBinding.create(setter, dispatcher(), invoke(WrongAction.class),
                Collections.singletonList(invoke(Action.class))));
    }

    @Test public void blocksOnlyBoundIdentityInExactDoubleTapDispatcher() throws Exception {
        DisableDoubleTapLikeHook.BoundLikeCallbacks callbacks = new DisableDoubleTapLikeHook.BoundLikeCallbacks(dispatcher());
        Object bound = new MergedAction();
        Object otherCaseOfSameMergedClass = new MergedAction();
        callbacks.bind(bound);
        assertTrue(bound.equals(otherCaseOfSameMergedClass)); // equality is insufficient for merged callbacks
        assertTrue(callbacks.shouldBlock(true, bound, args(true), stack(dispatcher())));
        assertFalse(callbacks.shouldBlock(true, otherCaseOfSameMergedClass, args(true), stack(dispatcher())));
        assertFalse(callbacks.shouldBlock(true, bound, args(true), stack(Handler.class.getDeclaredMethod("singleTap", View.class))));
        assertFalse(callbacks.shouldBlock(true, bound, args(true), () -> new StackTraceElement[]{
                new StackTraceElement("unrelated.Handler", "doubleTap", "Fixture", 1)}));
    }

    @Test public void disabledSettingAndOrdinaryButtonLikesPassThrough() throws Exception {
        DisableDoubleTapLikeHook.BoundLikeCallbacks callbacks = new DisableDoubleTapLikeHook.BoundLikeCallbacks(dispatcher());
        Object bound = new MergedAction();
        callbacks.bind(bound);
        assertFalse(callbacks.shouldBlock(false, bound, args(true), stack(dispatcher())));
        assertFalse(callbacks.shouldBlock(true, bound, args(false), stack(dispatcher())));
        assertFalse(callbacks.shouldBlock(true, bound, new Object[]{true}, stack(dispatcher())));
        assertFalse(callbacks.shouldBlock(true, bound, args(true), null));
    }

    @Test public void unboundFunctionsAndNullAssignmentsNeverBlock() throws Exception {
        DisableDoubleTapLikeHook.BoundLikeCallbacks callbacks = new DisableDoubleTapLikeHook.BoundLikeCallbacks(dispatcher());
        Object callback = new MergedAction();
        callbacks.bind(null);
        assertFalse(callbacks.shouldBlock(true, callback, args(true), stack(dispatcher())));
        assertFalse(callbacks.shouldBlock(true, null, args(true), stack(dispatcher())));
    }

    @Test public void unrelatedMergedActionsNeverCollectAStackTrace() throws Exception {
        DisableDoubleTapLikeHook.BoundLikeCallbacks callbacks = new DisableDoubleTapLikeHook.BoundLikeCallbacks(dispatcher());
        Object bound = new MergedAction();
        callbacks.bind(bound);
        Supplier<StackTraceElement[]> expensive = () -> { throw new AssertionError("unnecessary stack collection"); };
        assertFalse(callbacks.shouldBlock(false, bound, args(true), expensive));
        assertFalse(callbacks.shouldBlock(true, new MergedAction(), args(true), expensive));
        assertFalse(callbacks.shouldBlock(true, bound, args(false), expensive));
    }

    @Test public void boundedRegistryFailsOpenForEvictedOldCallback() throws Exception {
        DisableDoubleTapLikeHook.BoundLikeCallbacks callbacks = new DisableDoubleTapLikeHook.BoundLikeCallbacks(dispatcher());
        Object[] retained = new Object[129];
        for (int i = 0; i < retained.length; i++) {
            retained[i] = new MergedAction();
            callbacks.bind(retained[i]);
        }
        assertFalse(callbacks.shouldBlock(true, retained[0], args(true), stack(dispatcher())));
        assertTrue(callbacks.shouldBlock(true, retained[128], args(true), stack(dispatcher())));
    }
}
