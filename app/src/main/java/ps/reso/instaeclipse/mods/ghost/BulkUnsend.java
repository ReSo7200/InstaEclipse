package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Bulk unsend (#245): select several of your own messages right in the chat and unsend them at once.
 *
 * <p>Entry: an "Unsend multiple" row in the long-press menu of your own message (added by
 * {@link ps.reso.instaeclipse.mods.media.VoiceMessageDownloadHook}, next to IG's own Unsend).
 * It turns the chat into selection mode: a check circle sits beside each of your messages, a tap
 * on a message toggles it (scrolling still works), and a bar at the bottom unsends the selection.
 * The long-pressed message starts selected.
 *
 * <p>Unsending uses Instagram's own path, resolved by shape (no obfuscated names hardcoded):
 * <ul>
 *   <li>the unsend interactor is the class using "DirectUnsendMessageInteractor"; its static
 *       confirm handler invokes the sender's {@code (MessageRef, DirectThreadKey) -> ListenableFuture}
 *       unsend method (X.02Yh.A0F on 451);</li>
 *   <li>the MessageRef is built with the subclass constructor {@code (Message, DirectThreadKey)}
 *       (X.0Vpr on 451);</li>
 *   <li>the sender comes from the per-thread message cache passed to the message lookup
 *       ({@code ": null_direct_message"}, X.0F55 on 451).</li>
 * </ul>
 * Rows of the chat list ({@code id/message_list}) are mapped to their message through each row's
 * RecyclerView layout params (its view holder's bound model).
 */
public final class BulkUnsend {

    private static final String TAG = "(IE|BulkUnsend) ";
    private static final String DTK = "com.instagram.model.direct.DirectThreadKey";
    private static final String SESSION = "com.instagram.common.session.UserSession";

    private static Class<?> messageClass;
    private static Method unsendMethod;      // sender.(MessageRef, DirectThreadKey) -> ListenableFuture
    private static Constructor<?> refCtor;   // MessageRef(Message, DirectThreadKey)

    /** The message cache passed to the last message lookup (the open thread's). */
    private static volatile Object lastCache;

    /** Ids of messages we unsent, so the unsent-messages log does not keep our own. */
    private static final Set<String> OWN_IDS = Collections.synchronizedSet(new LinkedHashSet<>());

    private static Selection active;

    private BulkUnsend() {}

    public static boolean ready() {
        return unsendMethod != null && refCtor != null && messageClass != null;
    }

    public static void onLookup(Object cache) {
        if (cache != null) lastCache = cache;
    }

    public static boolean isOwnUnsend(String id) {
        return id != null && OWN_IDS.contains(id);
    }

    public static void install(DexKitBridge bridge, ClassLoader cl, Class<?> message) {
        messageClass = message;
        if (message == null) { ModuleLog.line(TAG + "❌ no message class"); return; }
        try {
            Class<?> dtk = cl.loadClass(DTK);
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("DirectUnsendMessageInteractor")))) {
                String owner = md.getClassName();
                for (MethodData m : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(owner)))) {
                    for (MethodData inv : m.getInvokes()) {
                        if (!"com.google.common.util.concurrent.ListenableFuture".equals(inv.getReturnTypeName())) continue;
                        List<String> p = inv.getParamTypeNames();
                        if (p.size() != 2 || !DTK.equals(p.get(1))) continue;
                        try {
                            Method um = inv.getMethodInstance(cl);
                            if (Modifier.isStatic(um.getModifiers())) continue;
                            unsendMethod = um;
                        } catch (Throwable ignored) {}
                        if (unsendMethod != null) break;
                    }
                    if (unsendMethod != null) break;
                }
                if (unsendMethod != null) break;
            }
            if (unsendMethod == null) { ModuleLog.line(TAG + "❌ unsend method not found"); return; }
            unsendMethod.setAccessible(true);
            Class<?> refBase = unsendMethod.getParameterTypes()[0];
            for (ClassData cd : bridge.findClass(FindClass.create().matcher(ClassMatcher.create()
                    .superClass(refBase.getName())))) {
                Class<?> c = cd.getInstance(cl);
                for (Constructor<?> k : c.getDeclaredConstructors()) {
                    Class<?>[] p = k.getParameterTypes();
                    if (p.length == 2 && p[0] == message && p[1] == dtk) { refCtor = k; break; }
                }
                if (refCtor != null) break;
            }
            if (refCtor == null) { ModuleLog.line(TAG + "❌ message ref not found"); return; }
            refCtor.setAccessible(true);
            FeatureStatusTracker.setHooked("BulkUnsend");
            ModuleLog.line(TAG + "✅ unsend=" + unsendMethod.getDeclaringClass().getName() + "." + unsendMethod.getName()
                    + " ref=" + refCtor.getDeclaringClass().getName());
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ install: " + t);
        }
    }

    // ── Thread state ─────────────────────────────────────────────────────────────

    private static final class Ctx {
        Object sender;
        Object session;
        Object threadKey;
    }

    /** Walks the message cache to the thread container (the object holding the sender, X.03CE on
     *  451), then reads the session and the open thread's key from there. */
    private static Ctx resolve(Object cache) {
        Ctx c = new Ctx();
        Class<?> senderClass = unsendMethod.getDeclaringClass();
        Object[] container = new Object[1];
        walk(cache, 4, (o, v) -> {
            if (senderClass.isInstance(v)) { container[0] = o; c.sender = v; return true; }
            return false;
        });
        if (container[0] == null) return c;
        walk(container[0], 3, (o, v) -> {
            if (c.session == null && SESSION.equals(v.getClass().getName())) c.session = v;
            if (c.threadKey == null && DTK.equals(v.getClass().getName())) c.threadKey = v;
            return c.session != null && c.threadKey != null;
        });
        if (c.session == null) {
            for (Field f : c.sender.getClass().getDeclaredFields()) {
                if (!SESSION.equals(f.getType().getName())) continue;
                try { f.setAccessible(true); c.session = f.get(c.sender); } catch (Throwable ignored) {}
            }
        }
        return c;
    }

    private interface Visit { boolean on(Object owner, Object value); }

    /** Breadth-first over IG objects' fields (and lazy holders' get()), until {@code v} says stop. */
    private static void walk(Object start, int maxDepth, Visit v) {
        walk(start, maxDepth, 3000, v);
    }

    private static void walk(Object start, int maxDepth, int maxNodes, Visit v) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Object[]> q = new ArrayDeque<>();
        q.add(new Object[]{start, 0});
        while (!q.isEmpty() && seen.size() < maxNodes) {
            Object[] e = q.poll();
            Object o = e[0];
            int d = (int) e[1];
            if (o == null || !seen.add(o) || d >= maxDepth) continue;
            Class<?> k = o.getClass();
            String n = k.getName();
            if (n.startsWith("java.") || n.startsWith("android.") || SESSION.equals(n)) continue;
            // lazy holders (kotlin Lazy / provider): a no-arg get()/getValue() returning Object
            if (!(o instanceof View)) {
                for (String g : new String[]{"get", "getValue"}) {
                    try {
                        Method m = k.getMethod(g);
                        if (m.getReturnType() != Object.class) continue;
                        Object val = m.invoke(o);
                        if (val == null) continue;
                        if (v.on(o, val)) return;
                        q.add(new Object[]{val, d + 1});
                    } catch (Throwable ignored) {}
                }
            }
            if (!n.startsWith("X.") && !n.startsWith("com.instagram.") && !n.startsWith("androidx.recyclerview.")) continue;
            for (Class<?> x = k; x != null && x != Object.class; x = x.getSuperclass()) {
                if (x.getName().startsWith("android.")) break;
                for (Field f : x.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                    try {
                        f.setAccessible(true);
                        Object val = f.get(o);
                        if (val == null || val instanceof View) continue;
                        if (v.on(o, val)) return;
                        q.add(new Object[]{val, d + 1});
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    private static String userId(Object session) {
        try { return (String) session.getClass().getMethod("getUserId").invoke(session); }
        catch (Throwable t) { return null; }
    }

    /** True when one of the message's own String fields is our user id (its sender). */
    private static boolean isMine(Object msg, String me) {
        if (me == null) return false;
        for (Class<?> k = msg.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
                try { f.setAccessible(true); if (me.equals(f.get(msg))) return true; } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static Object threadKeyOf(Object msg) {
        for (Class<?> k = msg.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !DTK.equals(f.getType().getName())) continue;
                try { f.setAccessible(true); Object v = f.get(msg); if (v != null) return v; } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** The message bound to a chat-list row: the first Message in its layout params' graph. */
    private static Object messageOf(View row) {
        ViewGroup.LayoutParams lp = row.getLayoutParams();
        if (lp == null) return null;
        Object[] found = new Object[1];
        walk(lp, 5, 300, (o, v) -> {
            if (messageClass.isInstance(v)) { found[0] = v; return true; }
            return false;
        });
        return found[0];
    }

    // ── Selection mode ───────────────────────────────────────────────────────────

    /** Turns the open chat into selection mode with {@code pressed} (the long-pressed message) selected. */
    public static void show(Activity act, Object pressed) {
        if (act == null) return;
        if (active != null) active.exit();
        if (!ready() || lastCache == null) { toast(act, I18n.t(act, R.string.ig_bulk_unsend_unavailable)); return; }
        Ctx c;
        try { c = resolve(lastCache); } catch (Throwable t) { c = null; ModuleLog.line(TAG + "⚠️ resolve: " + t); }
        @SuppressLint("DiscouragedApi")
        int listId = act.getResources().getIdentifier("message_list", "id", act.getPackageName());
        View list = listId != 0 ? act.getWindow().getDecorView().findViewById(listId) : null;
        if (c == null || c.sender == null || !(list instanceof ViewGroup rv) || !list.isShown()) {
            ModuleLog.line(TAG + "⚠️ resolve: sender=" + (c != null && c.sender != null) + " list=" + (list != null));
            toast(act, I18n.t(act, R.string.ig_bulk_unsend_unavailable));
            return;
        }
        String me = c.session != null ? userId(c.session) : null;
        active = new Selection(act, rv, c, me, pressed);
        ModuleLog.line(TAG + "selection mode: me=" + (me != null) + " key=" + (c.threadKey != null));
    }

    private static final class Selection {
        final Activity act;
        final ViewGroup rv;
        final Ctx ctx;
        final String me;
        final List<Object> selected = new ArrayList<>();
        final IdentityHashMap<Object, Boolean> mineCache = new IdentityHashMap<>();
        final Overlay overlay;
        final LinearLayout bar;
        final TextView go;
        final ViewTreeObserver.OnPreDrawListener preDraw;
        long lastLayout;

        Selection(Activity act, ViewGroup rv, Ctx ctx, String me, Object pressed) {
            this.act = act;
            this.rv = rv;
            this.ctx = ctx;
            this.me = me;
            if (pressed != null) selected.add(pressed);
            float d = act.getResources().getDisplayMetrics().density;
            int accent = ps.reso.instaeclipse.mods.ui.theme.IgThemeEngine.accentOr(0xFF0095F6);

            overlay = new Overlay(act, this, accent, d);
            bar = new LinearLayout(act);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            bar.setPadding((int) (16 * d), (int) (10 * d), (int) (12 * d), (int) (10 * d));
            boolean dark = (act.getResources().getConfiguration().uiMode
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(dark ? 0xFF1C1C1E : 0xFFFFFFFF);
            bg.setCornerRadius(22 * d);
            bg.setStroke(Math.max(1, (int) d), dark ? 0x33FFFFFF : 0x1F000000);
            bar.setBackground(bg);
            bar.setElevation(8 * d);
            TextView cancel = new TextView(act);
            cancel.setText(I18n.t(act, R.string.ig_dialog_cancel));
            cancel.setTextSize(15);
            cancel.setTextColor(dark ? 0xFFF5F5F5 : 0xFF111111);
            cancel.setPadding((int) (6 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
            cancel.setOnClickListener(v -> exit());
            bar.addView(cancel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            go = new TextView(act);
            go.setTextSize(15);
            go.setTypeface(Typeface.DEFAULT_BOLD);
            go.setTextColor(Color.WHITE);
            go.setGravity(Gravity.CENTER);
            go.setPadding((int) (18 * d), (int) (10 * d), (int) (18 * d), (int) (10 * d));
            android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
            pill.setColor(accent);
            pill.setCornerRadius(20 * d);
            go.setBackground(pill);
            go.setOnClickListener(v -> unsendSelected());
            bar.addView(go);
            updateBar();

            ViewGroup content = act.findViewById(android.R.id.content);
            content.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
            // over the composer (hidden while selecting), so the newest message stays visible
            int m = (int) (10 * d);
            blp.setMargins(m, 0, m, m);
            content.addView(bar, blp);

            preDraw = () -> {
                if (!rv.isShown() || !rv.isAttachedToWindow()) { exit(); return true; }
                long sig = rv.getChildCount();
                for (int i = 0; i < rv.getChildCount(); i++) sig = sig * 31 + rv.getChildAt(i).getTop() + rv.getChildAt(i).hashCode();
                if (sig != lastLayout) { lastLayout = sig; overlay.invalidate(); }
                return true;
            };
            rv.getViewTreeObserver().addOnPreDrawListener(preDraw);
        }

        boolean isMine(Object msg) {
            Boolean b = mineCache.get(msg);
            if (b == null) {
                b = selected.contains(msg) || BulkUnsend.isMine(msg, me);
                mineCache.put(msg, b);
            }
            return b;
        }

        /** The row under (x, y) in rv coordinates and its message, if it's one of ours. */
        Object messageAt(float x, float y) {
            for (int i = rv.getChildCount() - 1; i >= 0; i--) {
                View ch = rv.getChildAt(i);
                if (y < ch.getTop() || y > ch.getBottom()) continue;
                Object msg = messageOf(ch);
                return msg != null && isMine(msg) ? msg : null;
            }
            return null;
        }

        void toggle(Object msg) {
            if (!selected.remove(msg)) selected.add(msg);
            updateBar();
            overlay.invalidate();
        }

        void updateBar() {
            int n = selected.size();
            go.setText(I18n.t(act, R.string.ig_bulk_unsend_go) + (n > 0 ? " (" + n + ")" : ""));
            go.setAlpha(n > 0 ? 1f : 0.5f);
        }

        void unsendSelected() {
            if (selected.isEmpty()) return;
            List<Object> picked = new ArrayList<>(selected);
            exit();
            unsendAll(act, ctx, picked);
        }

        void exit() {
            if (active == this) active = null;
            try { rv.getViewTreeObserver().removeOnPreDrawListener(preDraw); } catch (Throwable ignored) {}
            for (View v : new View[]{overlay, bar}) {
                if (v.getParent() instanceof ViewGroup p) p.removeView(v);
            }
        }
    }

    /** Draws the check circles and turns taps on our messages into selection toggles; drags still
     *  scroll the chat (the gesture is handed to the list once it moves). */
    @SuppressLint("ViewConstructor")
    private static final class Overlay extends View {
        final Selection s;
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint tint = new Paint();
        final float d;
        final int slop;
        final int[] rvLoc = new int[2], myLoc = new int[2];
        MotionEvent down;
        boolean forwarding;

        Overlay(Context c, Selection s, int accent, float d) {
            super(c);
            this.s = s;
            this.d = d;
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(2 * d);
            ring.setColor(0xCCFFFFFF);
            fill.setColor(accent);
            tick.setColor(Color.WHITE);
            tick.setStyle(Paint.Style.STROKE);
            tick.setStrokeWidth(2.2f * d);
            tick.setStrokeCap(Paint.Cap.ROUND);
            tint.setColor((accent & 0x00FFFFFF) | 0x33000000);
        }

        private float dx() { s.rv.getLocationInWindow(rvLoc); getLocationInWindow(myLoc); return rvLoc[0] - myLoc[0]; }
        private float dy() { s.rv.getLocationInWindow(rvLoc); getLocationInWindow(myLoc); return rvLoc[1] - myLoc[1]; }

        @Override
        protected void onDraw(Canvas canvas) {
            float ox = dx(), oy = dy();
            canvas.save();
            canvas.clipRect(ox, oy, ox + s.rv.getWidth(), oy + s.rv.getHeight());
            float r = 11 * d, cx = ox + 22 * d;
            for (int i = 0; i < s.rv.getChildCount(); i++) {
                View ch = s.rv.getChildAt(i);
                Object msg = messageOf(ch);
                if (msg == null || !s.isMine(msg)) continue;
                float top = oy + ch.getTop() + ch.getTranslationY(), bottom = oy + ch.getBottom() + ch.getTranslationY();
                float cy = (top + bottom) / 2f;
                boolean on = s.selected.contains(msg);
                if (on) {
                    canvas.drawRect(ox, top, ox + s.rv.getWidth(), bottom, tint);
                    canvas.drawCircle(cx, cy, r, fill);
                    canvas.drawLine(cx - 5 * d, cy, cx - 1.5f * d, cy + 4 * d, tick);
                    canvas.drawLine(cx - 1.5f * d, cy + 4 * d, cx + 5.5f * d, cy - 4 * d, tick);
                } else {
                    canvas.drawCircle(cx, cy, r, ring);
                }
            }
            canvas.restore();
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float ox = dx(), oy = dy();
            float x = e.getX() - ox, y = e.getY() - oy;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (x < 0 || y < 0 || x > s.rv.getWidth() || y > s.rv.getHeight()) return false;
                    forwarding = false;
                    if (down != null) down.recycle();
                    down = MotionEvent.obtain(e);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!forwarding && down != null && Math.hypot(e.getX() - down.getX(), e.getY() - down.getY()) > slop) {
                        forwarding = true;
                        forward(down, ox, oy);
                    }
                    if (forwarding) forward(e, ox, oy);
                    return true;
                case MotionEvent.ACTION_UP:
                    if (forwarding) { forward(e, ox, oy); forwarding = false; return true; }
                    Object msg = s.messageAt(x, y);
                    if (msg != null) s.toggle(msg);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (forwarding) forward(e, ox, oy);
                    forwarding = false;
                    return true;
                default:
                    if (forwarding) forward(e, ox, oy);
                    return true;
            }
        }

        private void forward(MotionEvent e, float ox, float oy) {
            MotionEvent c = MotionEvent.obtain(e);
            c.offsetLocation(-ox, -oy);
            try { s.rv.dispatchTouchEvent(c); } catch (Throwable ignored) {}
            c.recycle();
        }
    }

    // ── Unsend ───────────────────────────────────────────────────────────────────

    /** Unsends {@code msgs} one after another on a background thread. */
    private static void unsendAll(Activity act, Ctx c, List<Object> msgs) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            int ok = 0;
            for (Object m : msgs) {
                try {
                    Object key = threadKeyOf(m);
                    if (key == null) key = c.threadKey;
                    if (key == null) { ModuleLog.line(TAG + "⚠️ no thread key"); continue; }
                    rememberIds(m, key);
                    Object ref = refCtor.newInstance(m, key);
                    Object future = unsendMethod.invoke(c.sender, ref, key);
                    Object r = future instanceof java.util.concurrent.Future<?> f ? f.get(10, java.util.concurrent.TimeUnit.SECONDS) : future;
                    ModuleLog.line(TAG + "unsend → " + r + " (key from " + (threadKeyOf(m) != null ? "message" : "thread") + ")");
                    ok++;
                    Thread.sleep(250);
                } catch (Throwable t) {
                    ModuleLog.line(TAG + "⚠️ unsend: " + t + " / " + t.getCause());
                }
            }
            final int done = ok;
            ModuleLog.line(TAG + "✅ unsent " + done + "/" + msgs.size());
            main.post(() -> toast(act, I18n.t(act, R.string.ig_bulk_unsend_done, done)));
        }, "IE-BulkUnsend").start();
    }

    /** The message's id-like Strings ("mid.…", long digit or hex ids), not its thread's id. */
    private static void rememberIds(Object msg, Object key) {
        String thread = ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker.firstStringField(key);
        for (Class<?> k = msg.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(msg);
                    if (!(v instanceof String s) || s.equals(thread)) continue;
                    if (s.startsWith("mid.") || s.matches("[0-9]{15,}") || s.matches("[0-9a-fA-F-]{20,}")) OWN_IDS.add(s);
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void toast(Context c, String s) {
        try { Toast.makeText(c, s, Toast.LENGTH_SHORT).show(); } catch (Throwable ignored) {}
    }
}
