package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.mods.ui.UIHookManager;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Marks the rows of messages that the other side unsent but "Keep received unsent messages in
 * chat" kept (#222): a small "Unsent" pill at the end of the row and the row content faded.
 *
 * <p>Rows are found through AndroidX RecyclerView's final bindViewHolder (the method holding the
 * "RV onBindViewHolder type=0x%X" trace string, X.04Sl.A0R on 452.0.0.33.64). Only holders owned
 * by the DM thread list ({@code id/message_list}) are looked at. The bound message is the first
 * DM message in the holder's object graph, the same mapping Bulk Unsend uses for its rows; it is
 * matched by id against the retained set. The decoration is removed before every bind, so a
 * recycled row never keeps it, and visible rows are refreshed when a message is retained or the
 * settings change.
 *
 * <p>Fail safe: when the bind method or the holder's fields cannot be resolved, nothing is hooked.
 * Every callback swallows its errors.
 */
final class UnsentMarkerHook {

    private static final String TAG = "(IE|UnsentMarker) ";
    private static final String BIND_ANCHOR = "RV onBindViewHolder type=0x%X";
    private static final String CACHE_KEY = "UnsentMarker_bind";
    private static final String SESSION = "com.instagram.common.session.UserSession";
    private static final float FADE = 0.55f;

    interface Matcher {
        /** True when there is at least one retained message (cheap). */
        boolean any();
        /** True when {@code message} is a retained (unsent but kept) message. */
        boolean retained(Object message);
    }

    private static Class<?> messageClass, holderBase, adapterBase, recyclerView;
    private static Field itemViewField, ownerField;
    private static Matcher matcher;
    private static int listId = -1;
    private static boolean installed;

    /** Decorated rows; touched on the main thread only. */
    private static final Map<View, Mark> MARKS = new WeakHashMap<>();
    /** Adapter classes seen binding rows of the thread list without any DM message: skipped. */
    private static final Map<Class<?>, int[]> ADAPTERS = Collections.synchronizedMap(new WeakHashMap<>());

    private UnsentMarkerHook() {}

    static void install(DexKitBridge bridge, ClassLoader loader, Class<?> message, Matcher match) {
        if (installed || message == null || match == null) return;
        try {
            recyclerView = loader.loadClass("androidx.recyclerview.widget.RecyclerView");
            Method bind = findBind(bridge, loader);
            if (bind == null) { ModuleLog.line(TAG + "❌ bind method not found"); return; }
            holderBase = bind.getParameterTypes()[0];
            adapterBase = bind.getDeclaringClass();
            for (Class<?> c = holderBase; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() == View.class && Modifier.isFinal(f.getModifiers()) && itemViewField == null) itemViewField = f;
                    if (f.getType() == recyclerView && ownerField == null) ownerField = f;
                }
            }
            if (itemViewField == null) { ModuleLog.line(TAG + "❌ holder view field not found"); return; }
            itemViewField.setAccessible(true);
            if (ownerField != null) ownerField.setAccessible(true);
            messageClass = message;
            matcher = match;
            XposedBridge.hookMethod(bind, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (MARKS.isEmpty() || param.args.length == 0) return;
                    try { clear(itemView(param.args[0])); } catch (Throwable ignored) {}
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (param.hasThrowable() || param.args.length == 0 || !enabled()) return;
                    try { apply(param.thisObject, param.args[0]); } catch (Throwable ignored) {}
                }
            });
            installed = true;
            ModuleLog.line(TAG + "✅ bind hooked → " + adapterBase.getName() + "." + bind.getName()
                    + " owner=" + (ownerField != null));
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ install: " + t.getClass().getSimpleName());
        }
    }

    /** RecyclerView.Adapter.bindViewHolder(ViewHolder, int): final, void, holds the trace string. */
    private static Method findBind(DexKitBridge bridge, ClassLoader loader) {
        if (DexKitCache.isCacheValid()) {
            List<Method> cached = DexKitCache.loadMethods(CACHE_KEY, loader);
            if (cached != null && cached.size() == 1) return cached.get(0);
        }
        Method found = null;
        for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create()
                .matcher(MethodMatcher.create().usingStrings(BIND_ANCHOR)))) {
            try {
                Method m = md.getMethodInstance(loader);
                Class<?>[] p = m.getParameterTypes();
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class
                        || p.length != 2 || p[1] != int.class || p[0].isPrimitive()) continue;
                if (found != null) return null; // ambiguous: do nothing
                found = m;
            } catch (Throwable ignored) {}
        }
        if (found != null) {
            found.setAccessible(true);
            List<Method> save = new ArrayList<>();
            save.add(found);
            DexKitCache.saveMethods(CACHE_KEY, save);
        }
        return found;
    }

    static boolean enabled() {
        return installed && FeatureFlags.keepUnsentMessages && FeatureFlags.keepUnsentInChat
                && !FeatureFlags.hideUnsentLabel;
    }

    private static View itemView(Object holder) throws IllegalAccessException {
        return holder != null && holderBase.isInstance(holder) ? (View) itemViewField.get(holder) : null;
    }

    /** Decorates the holder's row when it shows a retained message. Main thread. */
    private static void apply(Object adapter, Object holder) throws IllegalAccessException {
        if (Looper.myLooper() != Looper.getMainLooper() || matcher == null || !matcher.any()) return;
        View row = itemView(holder);
        if (row == null || !inThreadList(holder, row)) return;
        int[] stats = adapter != null ? ADAPTERS.get(adapter.getClass()) : null;
        if (stats != null && stats[1] == 0 && stats[0] >= 40) return; // not a message adapter
        Object message = messageOf(holder);
        if (adapter != null) {
            if (stats == null) { stats = new int[2]; ADAPTERS.put(adapter.getClass(), stats); }
            stats[0]++;
            if (message != null) stats[1]++;
        }
        if (message != null && matcher.retained(message)) mark(row);
    }

    private static boolean inThreadList(Object holder, View row) {
        Object owner = null;
        try { if (ownerField != null) owner = ownerField.get(holder); } catch (Throwable ignored) {}
        if (!(owner instanceof View) && row.getParent() instanceof View p) owner = p;
        if (!(owner instanceof View list)) return true; // unknown owner: the adapter filter decides
        if (listId == -1) {
            try {
                listId = list.getResources().getIdentifier("message_list", "id", list.getContext().getPackageName());
            } catch (Throwable t) { listId = 0; }
        }
        return listId == 0 || list.getId() == listId;
    }

    /** The first DM message in the holder's object graph (breadth first, bounded). */
    private static Object messageOf(Object holder) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{holder, 0});
        while (!queue.isEmpty() && seen.size() < 300) {
            Object[] e = queue.poll();
            Object o = e[0];
            int depth = (int) e[1];
            if (o == null || !seen.add(o) || depth >= 4) continue;
            if (o != holder && (holderBase.isInstance(o) || adapterBase.isInstance(o))) continue;
            Class<?> k = o.getClass();
            String n = k.getName();
            if (n.startsWith("java.") || n.startsWith("android.") || SESSION.equals(n)) continue;
            if (!n.startsWith("X.") && !n.startsWith("com.instagram.") && !n.startsWith("androidx.recyclerview.")) continue;
            for (Class<?> x = k; x != null && x != Object.class; x = x.getSuperclass()) {
                if (x.getName().startsWith("android.")) break;
                for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(x)) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v == null || v instanceof View) continue;
                        if (messageClass.isInstance(v)) return v;
                        queue.add(new Object[]{v, depth + 1});
                    } catch (Throwable ignored) {}
                }
            }
        }
        return null;
    }

    // ── Decoration ──────────────────────────────────────────────────────────────

    private static final class Mark {
        final Badge badge;
        final View.OnLayoutChangeListener layout;
        final List<View> faded = new ArrayList<>();
        final List<Float> alphas = new ArrayList<>();

        Mark(Badge badge, View.OnLayoutChangeListener layout) {
            this.badge = badge;
            this.layout = layout;
        }
    }

    private static void mark(View row) {
        if (MARKS.containsKey(row)) return;
        Badge badge = new Badge(row);
        View.OnLayoutChangeListener layout = (v, l, t, r, b, ol, ot, or, ob) ->
                badge.setBounds(0, 0, r - l, b - t);
        Mark m = new Mark(badge, layout);
        badge.setBounds(0, 0, row.getWidth(), row.getHeight());
        row.getOverlay().add(badge);
        row.addOnLayoutChangeListener(layout);
        if (row instanceof ViewGroup g) {
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                m.faded.add(c);
                m.alphas.add(c.getAlpha());
                c.setAlpha(c.getAlpha() * FADE);
            }
        }
        MARKS.put(row, m);
        row.invalidate();
    }

    private static void clear(View row) {
        if (row == null) return;
        Mark m = MARKS.remove(row);
        if (m == null) return;
        try { row.getOverlay().remove(m.badge); } catch (Throwable ignored) {}
        row.removeOnLayoutChangeListener(m.layout);
        for (int i = 0; i < m.faded.size(); i++) {
            View c = m.faded.get(i);
            float original = m.alphas.get(i);
            // Leave alone a child whose alpha Instagram changed since.
            if (Math.abs(c.getAlpha() - original * FADE) < 0.001f) c.setAlpha(original);
        }
        row.invalidate();
    }

    /** Re-evaluates the rows on screen: after a message is kept, or the settings change. */
    static void refreshVisible() {
        if (!installed) return;
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Activity a = UIHookManager.getCurrentActivity();
                if (a == null || a.getWindow() == null) return;
                @SuppressLint("DiscouragedApi")
                int id = a.getResources().getIdentifier("message_list", "id", a.getPackageName());
                View list = id != 0 ? a.getWindow().getDecorView().findViewById(id) : null;
                if (!recyclerView.isInstance(list)) {
                    // No thread on screen: drop every decoration still around.
                    for (View row : new ArrayList<>(MARKS.keySet())) clear(row);
                    return;
                }
                ViewGroup rv = (ViewGroup) list;
                for (int i = 0; i < rv.getChildCount(); i++) {
                    View row = rv.getChildAt(i);
                    clear(row);
                    if (!enabled()) continue;
                    Object holder = holderOf(row);
                    if (holder != null && itemView(holder) == row) apply(null, holder);
                }
            } catch (Throwable ignored) {}
        });
    }

    /** RecyclerView.LayoutParams keeps the row's view holder. */
    private static Object holderOf(View row) {
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp == null) return null;
        for (Class<?> c = lp.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            if (c.getName().startsWith("android.")) break;
            for (Field f : ps.reso.instaeclipse.utils.core.SafeReflect.fields(c)) {
                if (Modifier.isStatic(f.getModifiers()) || !holderBase.isAssignableFrom(f.getType())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(lp);
                    if (v != null) return v;
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** "⊘ Unsent" pill drawn at the end of the row, vertically centred. */
    private static final class Badge extends Drawable {
        private final View row;
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint icon = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final String label;
        private final float d;
        private final RectF pill = new RectF();

        Badge(View row) {
            this.row = row;
            Context c = row.getContext();
            d = c.getResources().getDisplayMetrics().density;
            String l;
            try { l = I18n.t(c, R.string.ie_unsent_label); } catch (Throwable t) { l = "Unsent"; }
            label = l;
            boolean dark = (c.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            int fg = dark ? 0xFFD8D8D8 : 0xFF5A5A5A;
            text.setColor(fg);
            text.setTextSize(11 * c.getResources().getDisplayMetrics().scaledDensity);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            fill.setColor(dark ? 0xE0303033 : 0xE0EFEFF2);
            icon.setColor(fg);
            icon.setStyle(Paint.Style.STROKE);
            icon.setStrokeWidth(1.4f * d);
            icon.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            int w = row.getWidth(), h = row.getHeight();
            if (w <= 0 || h <= 0) return;
            float r = 4.5f * d, padH = 7 * d, gap = 4 * d, margin = 10 * d;
            float tw = text.measureText(label);
            Paint.FontMetrics fm = text.getFontMetrics();
            float ph = Math.max(fm.descent - fm.ascent, 2 * r) + 6 * d;
            float pw = padH + 2 * r + gap + tw + padH;
            boolean rtl = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float left = rtl ? margin : w - margin - pw;
            float top = (h - ph) / 2f;
            pill.set(left, top, left + pw, top + ph);
            canvas.drawRoundRect(pill, ph / 2f, ph / 2f, fill);
            float cx = left + padH + r, cy = top + ph / 2f;
            canvas.drawCircle(cx, cy, r, icon);
            float k = r * 0.7071f;
            canvas.drawLine(cx - k, cy - k, cx + k, cy + k, icon);
            canvas.drawText(label, cx + r + gap, cy - (fm.ascent + fm.descent) / 2f, text);
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(ColorFilter cf) {}
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
