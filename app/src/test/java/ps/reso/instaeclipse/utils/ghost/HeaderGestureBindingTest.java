package ps.reso.instaeclipse.utils.ghost;

import org.junit.Test;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import static org.junit.Assert.*;

/** Tests the production ownership/selection policy, not Android's touch dispatcher. */
public class HeaderGestureBindingTest {
    private static final class Node {
        final String name;
        Node parent;
        boolean clickable, longClickable, nativeLongClick, usable = true;
        int longClickableWrites, taps;
        final Runnable nativeTap = () -> taps++;
        Node(String name, Node parent, boolean clickable) {
            this.name = name; this.parent = parent; this.clickable = clickable;
        }
    }
    private static final class Harness implements HeaderGestureBinding.Access<Node>, HeaderGestureBinding.Observer<Node> {
        final Set<Node> owned = Collections.newSetFromMap(new IdentityHashMap<>());
        int acquisitions, releases;
        final HeaderGestureBinding<Node> binding = new HeaderGestureBinding<>(this, this);
        public Node parent(Node view) { return view.parent; }
        public boolean clickable(Node view) { return view.clickable; }
        public boolean longClickable(Node view) { return view.longClickable; }
        public boolean nativeLongClick(Node view) { return view.nativeLongClick; }
        public boolean usable(Node view) { return view.usable; }
        public void setLongClickable(Node view, boolean value) { view.longClickable = value; view.longClickableWrites++; }
        public void acquired(Node view) { assertTrue(owned.add(view)); acquisitions++; }
        public void released(Node view) { assertTrue(owned.remove(view)); releases++; }
    }
    @Test public void passiveTitleUsesNativeClickableContainerWithoutChangingTap() {
        Harness harness = new Harness();
        Node header = new Node("direct_thread_header", null, true);
        Node container = new Node("header_title_subtitle_container", header, true);
        Node title = new Node("header_title", container, false);
        Runnable original = container.nativeTap;
        assertSame(container, harness.binding.update(title, header, true));
        assertFalse(title.longClickable);
        assertEquals(0, title.longClickableWrites);
        assertSame(original, container.nativeTap);
        container.nativeTap.run();
        assertEquals(1, container.taps);
        assertTrue(harness.binding.accepts(container));
        assertFalse(harness.binding.accepts(title));
    }
    @Test public void repeatedLayoutsAndRecreationHaveExactlyOneOwner() {
        Harness harness = new Harness();
        Node firstHeader = new Node("header", null, false);
        Node first = new Node("title_container", firstHeader, true);
        for (int i = 0; i < 50; i++) assertSame(first, harness.binding.update(first, firstHeader, true));
        assertEquals(1, harness.acquisitions);
        assertEquals(1, first.longClickableWrites);
        Node secondHeader = new Node("replacement_header", null, false);
        Node second = new Node("replacement_title", secondHeader, true);
        assertSame(second, harness.binding.update(second, secondHeader, true));
        assertFalse(first.longClickable);
        assertFalse(harness.binding.accepts(first));
        assertEquals(Collections.singleton(second), harness.owned);
        harness.binding.release();
        harness.binding.release();
        assertFalse(second.longClickable);
        assertTrue(harness.owned.isEmpty());
        assertEquals(2, harness.releases);
    }
    @Test public void sameViewNativeRebindRearmsFlagWithoutDuplicatingOwnership() {
        Harness harness = new Harness();
        Node header = new Node("header", null, false);
        Node target = new Node("title_container", header, true);
        harness.binding.update(target, header, true);
        target.longClickable = false; // Instagram rebinds the same existing title container.
        assertSame(target, harness.binding.update(target, header, true));
        assertTrue(target.longClickable);
        assertTrue(harness.binding.accepts(target));
        assertEquals(1, harness.acquisitions);
        assertEquals(0, harness.releases);
        assertEquals(2, target.longClickableWrites);
        assertEquals(Collections.singleton(target), harness.owned);
    }
    @Test public void nativeLongPressAndUninspectableTargetsRemainNative() {
        Harness harness = new Harness();
        Node header = new Node("header", null, false);
        Node target = new Node("native_title", header, true);
        target.nativeLongClick = true;
        assertNull(harness.binding.update(target, header, true));
        assertEquals(0, target.longClickableWrites);
        target.nativeLongClick = false;
        target.longClickable = true; // e.g. an overridden native long-press implementation
        assertNull(harness.binding.update(target, header, true));
        assertEquals(0, target.longClickableWrites);
    }
    @Test public void nativeListenerAddedAfterClaimIsNeverCleared() {
        Harness harness = new Harness();
        Node header = new Node("header", null, false);
        Node target = new Node("title", header, true);
        harness.binding.update(target, header, true);
        target.nativeLongClick = true;
        assertFalse(harness.binding.accepts(target));
        assertTrue(target.longClickable);
        assertTrue(target.nativeLongClick);
        assertEquals(1, target.longClickableWrites);
        assertTrue(harness.owned.isEmpty());
    }
    @Test public void disabledFeatureAndDetachedTitleReleaseOwnedState() {
        Harness harness = new Harness();
        Node header = new Node("header", null, false);
        Node target = new Node("title", header, true);
        harness.binding.update(target, header, true);
        assertNull(harness.binding.update(target, header, false));
        assertFalse(target.longClickable);
        harness.binding.update(target, header, true);
        target.usable = false;
        assertFalse(harness.binding.accepts(target));
        assertFalse(target.longClickable);
        assertTrue(harness.owned.isEmpty());
    }
    @Test public void cannotBindHeaderOutsideTreeOrCyclicParents() {
        Harness harness = new Harness();
        Node header = new Node("header", null, true);
        Node passive = new Node("title", header, false);
        assertNull(harness.binding.update(passive, header, true));
        assertNull(harness.binding.update(new Node("foreign", null, true), header, true));
        Node cycle = new Node("cycle", null, false);
        cycle.parent = cycle;
        assertNull(harness.binding.update(cycle, header, true));
        assertTrue(harness.owned.isEmpty());
    }
    @Test public void reparentedTitleLosesOldHeaderClaimBeforeBindingNewHeader() {
        Harness harness = new Harness();
        Node oldHeader = new Node("old_header", null, false);
        Node newHeader = new Node("new_header", null, false);
        Node target = new Node("title_container", oldHeader, true);
        assertSame(target, harness.binding.update(target, oldHeader, true));
        target.parent = newHeader;
        assertNull(harness.binding.update(target, oldHeader, true));
        assertFalse(harness.binding.accepts(target));
        assertFalse(target.longClickable);
        assertTrue(harness.owned.isEmpty());
        assertSame(target, harness.binding.update(target, newHeader, true));
        assertTrue(harness.binding.accepts(target));
        assertEquals(Collections.singleton(target), harness.owned);
        assertEquals(2, harness.acquisitions);
        assertEquals(1, harness.releases);
    }
    @Test public void layoutDetectsNativeTakeoverAndRecreationDoesNotClearNativeGesture() {
        Harness harness = new Harness();
        Node header = new Node("header", null, false);
        Node target = new Node("title_container", header, true);
        harness.binding.update(target, header, true);
        target.nativeLongClick = true;
        assertNull(harness.binding.update(target, header, true));
        assertFalse(harness.binding.accepts(target));
        assertTrue(target.longClickable);
        assertEquals(1, target.longClickableWrites);
        Node replacement = new Node("replacement_title", header, true);
        assertSame(replacement, harness.binding.update(replacement, header, true));
        harness.binding.release();
        assertFalse(replacement.longClickable);
        assertTrue(target.longClickable);
        assertTrue(target.nativeLongClick);
        assertTrue(harness.owned.isEmpty());
        assertEquals(2, harness.acquisitions);
        assertEquals(2, harness.releases);
    }
}
