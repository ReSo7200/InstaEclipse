package ps.reso.instaeclipse.utils.ghost;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;

/** Never edits the backing store or guesses how to construct an app-specific collection. */
public final class InboxListFilter {
    private InboxListFilter() {}
    public static Object filtered(List<?> source, Class<?> declaredReturn, Predicate<Object> hidden) {
        ArrayList<Object> copy = new ArrayList<>(source.size());
        boolean changed = false;
        for (Object row : source) {
            if (hidden.test(row)) changed = true; else copy.add(row);
        }
        if (!changed) return source;
        // Only ordinary list contracts are verified here. A concrete immutable/obfuscated return
        // class needs a tested adapter; returning ArrayList into it would cause ClassCastException.
        if (declaredReturn == List.class || declaredReturn == Collection.class
                || declaredReturn == Iterable.class || declaredReturn == ArrayList.class) return copy;
        if (declaredReturn == LinkedList.class) return new LinkedList<>(copy);
        return source;
    }
}
