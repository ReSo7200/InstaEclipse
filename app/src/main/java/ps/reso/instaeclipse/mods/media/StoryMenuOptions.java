package ps.reso.instaeclipse.mods.media;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

/** Native Story menus contain labels; retain their order and runtime container type. */
final class StoryMenuOptions {
    private StoryMenuOptions() { }

    static Object appendDownload(Object original, String label) {
        if (original == null || label == null || label.isEmpty()) return original;
        if (original instanceof CharSequence[] labels) {
            Class<?> component = original.getClass().getComponentType();
            if (!component.isInstance(label)) return original;
            for (CharSequence item : labels) {
                if (item != null && label.contentEquals(item)) return original;
            }
            CharSequence[] extended = (CharSequence[]) Array.newInstance(component, labels.length + 1);
            System.arraycopy(labels, 0, extended, 0, labels.length);
            extended[labels.length] = label;
            return extended;
        }
        if (original instanceof ArrayList<?> labels) {
            for (Object item : labels) {
                if (item != null && !(item instanceof CharSequence)) return original;
                if (item instanceof CharSequence text && label.contentEquals(text)) return original;
            }
            ArrayList<Object> extended = new ArrayList<>(labels);
            extended.add(label);
            return extended;
        }
        return original;
    }

    static boolean isOwnedListBuilder(Method method, Class<?> itemType) {
        Class<?> owner = method.getDeclaringClass();
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != ArrayList.class
                || method.getParameterCount() == 0 || method.getParameterTypes()[0] != owner) return false;
        int fields = 0;
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getType() == itemType) fields++;
        }
        return fields == 1;
    }
}
