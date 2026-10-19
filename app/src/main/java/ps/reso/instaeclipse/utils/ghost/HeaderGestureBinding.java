package ps.reso.instaeclipse.utils.ghost;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Owns only the longClickable flag that this module adds to an existing native tap target.
 * No click or long-click listener is installed or replaced. The Android adapter must treat an
 * uninspectable native listener as present. Calls run on the UI thread. */
public final class HeaderGestureBinding<V> {
    public interface Access<V> {
        V parent(V view);
        boolean clickable(V view);
        boolean longClickable(V view);
        boolean nativeLongClick(V view);
        boolean usable(V view);
        void setLongClickable(V view, boolean value);
    }
    public interface Observer<V> {
        void acquired(V view);
        void released(V view);
    }
    private final Access<V> access;
    private final Observer<V> observer;
    private WeakReference<V> target = new WeakReference<>(null);
    public HeaderGestureBinding(Access<V> access, Observer<V> observer) {
        this.access = access; this.observer = observer;
    }
    public V current() { return target.get(); }

    /** Reconcile a recreated header or feature change; repeated layouts do not acquire twice. */
    public V update(V title, V header, boolean enabled) {
        V candidate = enabled ? nativeTapTarget(title, header) : null;
        V current = target.get();
        if (current != null && (current != candidate || !access.usable(current)
                || !access.clickable(current) || access.nativeLongClick(current))) release();
        if (target.get() == candidate && candidate != null) {
            // A native rebind may reset flags on the same View without recreating it. We still
            // own this claim and have rechecked native conflicts above; re-arm without a duplicate.
            if (!access.longClickable(candidate)) access.setLongClickable(candidate, true);
            return candidate;
        }
        if (candidate == null || !access.usable(candidate) || access.longClickable(candidate)
                || access.nativeLongClick(candidate)) return null;
        // Acquisition is allowed only from false, so release can restore the exact old flag.
        access.setLongClickable(candidate, true);
        target = new WeakReference<>(candidate);
        observer.acquired(candidate);
        return candidate;
    }

    /** Called immediately before intercepting a long press; a native listener added later wins. */
    public boolean accepts(V view) {
        if (view == null || target.get() != view) return false;
        if (!access.usable(view) || !access.clickable(view) || !access.longClickable(view)
                || access.nativeLongClick(view)) { release(); return false; }
        return true;
    }

    public void release() {
        V previous = target.get();
        target.clear();
        if (previous == null) return;
        // Instagram may have acquired a native long-press listener since our last layout.
        if (!access.nativeLongClick(previous)) access.setLongClickable(previous, false);
        observer.released(previous);
    }

    private V nativeTapTarget(V title, V header) {
        if (title == null || header == null || title == header) return null;
        Set<V> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        V candidate = null;
        for (V view = title; view != null && visited.add(view); view = access.parent(view)) {
            if (view == header) return candidate;
            if (candidate == null && access.clickable(view)) candidate = view;
        }
        return null; // A clickable view outside this header, or an invalid/cyclic tree.
    }
}
