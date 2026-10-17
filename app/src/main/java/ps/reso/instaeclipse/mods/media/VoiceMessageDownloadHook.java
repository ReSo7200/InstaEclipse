package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.content.Context;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Download voice messages from the DM long-press menu (#223).
 *
 * - The menu is built by the static factory of the named class MessageActionsViewModel; its
 *   main action list is a List<LongPressActionData> argument. We append a row built like IG's own
 *   helper (new LongPressActionData(null, style, action, null, null, icon, null, label, ...)),
 *   carried by the action enum value named SAVE_MEDIA (not used in the DM long-press list).
 * - The long-pressed message comes from the message lookup in the class using
 *   ": null_direct_message" (X.0BVY on 452); we keep the last one returned.
 * - Taps of every DM menu renderer end in the dispatcher using
 *   "DirectReportDailyPromptInteractor.reportDailyPrompt" (X.0RAg.A06), which receives the
 *   action and the message; SAVE_MEDIA on a voice message is consumed and downloaded.
 * - Audio URL: the message's AudioInfoIntf (found through its fields / Media) mapped by IG's
 *   serializer (static AudioInfoIntf -> Map using "audio_src_expiration_timestamp_us" +
 *   "waveform_sampling_frequency_hz") → "audio_src".
 */
public class VoiceMessageDownloadHook {

    private static final String TAG = "(IE|VoiceDL) ";
    private static final String VIEW_MODEL = "com.instagram.direct.messagethread.messageactions.model.MessageActionsViewModel";
    private static final String ACTION_DATA = "com.instagram.direct.messagethread.interaction.longpressaction.LongPressActionData";
    private static final String AUDIO_INFO = "com.instagram.api.schemas.AudioInfoIntf";
    private static final String MEDIA = "com.instagram.feed.media.Media";
    // Row carrier: an action IG fully wires (click → dispatch) but never offers on voice messages.
    private static final String CARRIER = "COPY_TEXT";
    // Translate row carrier: a staff-only action every menu renderer dispatches but that never
    // appears for normal users, so the action alone identifies our tap (COPY_TEXT would be
    // indistinguishable from IG's own Copy row on text messages).
    private static final String TRANSLATE_CARRIER = "DEBUG";

    private static volatile Object lastMessage;
    private static volatile long lastMessageAt;
    private static Class<?> messageClass;
    private static Class<?> audioInfoClass;
    private static Method audioMapper;
    private static Object saveMediaAction;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            audioInfoClass = cl.loadClass(AUDIO_INFO);
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("audio_src_expiration_timestamp_us", "waveform_sampling_frequency_hz")
                    .returnType("java.util.Map")))) {
                Method m = md.getMethodInstance(cl);
                if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == audioInfoClass) { audioMapper = m; break; }
            }

            // message lookup → remember the long-pressed message
            int lookups = 0;
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings(": null_direct_message")))) {
                Class<?> owner = md.getMethodInstance(cl).getDeclaringClass();
                messageClass = md.getMethodInstance(cl).getReturnType();
                for (Method m : owner.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != messageClass) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (param.getResult() != null) {
                                lastMessage = param.getResult();
                                lastMessageAt = System.currentTimeMillis();
                            }
                        }
                    });
                    lookups++;
                }
                break;
            }

            // menu factory → add our row for voice messages
            Class<?> vm = cl.loadClass(VIEW_MODEL);
            Class<?> data = cl.loadClass(ACTION_DATA);
            Constructor<?> dataCtor = null;
            for (Constructor<?> c : data.getDeclaredConstructors()) if (c.getParameterCount() == 11) dataCtor = c;
            final Constructor<?> ctor = dataCtor;
            if (ctor != null) {
                for (Object v : ctor.getParameterTypes()[2].getEnumConstants())
                    if (CARRIER.equals(((Enum<?>) v).name())) saveMediaAction = v;
            }
            int factories = 0;
            for (Method m : vm.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != vm) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if ((!FeatureFlags.enableVoiceDownload && !FeatureFlags.translateMessages) || ctor == null) return;
                        Object msg = recentMessage();
                        String url = FeatureFlags.enableVoiceDownload && msg != null ? audioUrl(msg) : null;
                        // Translate (like comments): hand a text message to the user's translator app.
                        String text = FeatureFlags.translateMessages && url == null && msg != null ? messageText(msg) : null;
                        ModuleLog.line(TAG + "menu: message=" + (msg != null) + " voice=" + (url != null) + " text=" + (text != null));
                        if (url == null && text == null) {
                            if (msg != null && FeatureFlags.enableVoiceDownload) dumpTypes(msg);
                            return;
                        }
                        for (int i = 0; i < param.args.length; i++) {
                            if (!(param.args[i] instanceof List<?> list) || list.isEmpty() || !data.isInstance(list.get(0))) continue;
                            // style template: a regular action row (the first item is the
                            // non-clickable timestamp header)
                            Object template = list.get(0);
                            for (Object r : list) {
                                String an = actionName(r);
                                if ("FORWARD".equals(an) || "REPLY".equals(an) || "PIN".equals(an) || "UNPIN".equals(an)) { template = r; break; }
                            }
                            Context c = param.args[0] instanceof Context cc ? cc : null;
                            Object row = url != null
                                    ? buildRow(ctor, template, c, downloadLabel(), "instagram_download_outline_24", CARRIER)
                                    : buildRow(ctor, template, c, translateLabel(), "instagram_translate_pano_outline_24", TRANSLATE_CARRIER);
                            if (row == null) return;
                            List<Object> copy = new ArrayList<>(list);
                            copy.add(row);
                            param.args[i] = copy;
                            if (url != null) {
                                wireRowWhenShown(downloadLabel(), act -> startDownload(act, url));
                            } else {
                                pendingText = text;
                                wireRowWhenShown(translateLabel(), act -> translate(act));
                            }
                            return;
                        }
                    }
                });
                factories++;
            }

            // tap dispatcher
            int dispatchers = 0;
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .usingStrings("DirectReportDailyPromptInteractor.reportDailyPrompt")))) {
                Method m = md.getMethodInstance(cl);
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (FeatureFlags.translateMessages && hasAction(param.args, TRANSLATE_CARRIER)) {
                            Context tctx = null;
                            for (Object a : param.args) if (a instanceof Activity act) tctx = act;
                            ModuleLog.line(TAG + "dispatch: translate");
                            param.setResult(null);
                            translate(tctx);
                            return;
                        }
                        if (!FeatureFlags.enableVoiceDownload) return;
                        Object action = null, msg = null;
                        Context ctx = null;
                        for (Object a : param.args) {
                            if (a instanceof Enum<?> e && CARRIER.equals(e.name())) action = a;
                            else if (a != null && messageClass != null && messageClass.isInstance(a)) msg = a;
                            else if (a instanceof Activity act) ctx = act;
                        }
                        if (action == null) return;
                        if (msg == null) msg = recentMessage();
                        String url = msg != null ? audioUrl(msg) : null;
                        if (url == null) return;
                        param.setResult(null);
                        startDownload(ctx, url);
                    }
                });
                dispatchers++;
            }
            // Row taps of the long-press menu go through the static method of the menu controller
            // that takes a LongPressActionData (MessageActionsController.A02 on 452).
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                    .paramTypes(ACTION_DATA, null, "java.lang.String")))) {
                Method m = md.getMethodInstance(cl);
                if (!Modifier.isStatic(m.getModifiers())) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                        if (!FeatureFlags.enableVoiceDownload && !FeatureFlags.translateMessages) return;
                        Object row = null;
                        for (Object a : param.args) if (data.isInstance(a)) row = a;
                        if (row == null) return;
                        if (isOurRow(row, translateLabel(), TRANSLATE_CARRIER)) {
                            ModuleLog.line(TAG + "row tap: translate");
                            param.setResult(null);
                            translate(null);
                            return;
                        }
                        if (!FeatureFlags.enableVoiceDownload || !isSaveMedia(row)) return;
                        Object msg = recentMessage();
                        String url = msg != null ? audioUrl(msg) : null;
                        ModuleLog.line(TAG + "row tap: voice=" + (url != null));
                        if (url == null) return;
                        startDownload(null, url);
                    }
                });
                dispatchers++;
            }
            // "Action selected" listeners: (ActionEnum, String) -> void, e.g. X.0lMj.E5t on 452.
            if (ctor != null) {
                String actionEnum = ctor.getParameterTypes()[2].getName();
                for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                        .paramTypes(actionEnum, "java.lang.String").returnType("void")))) {
                    Method m;
                    try { m = md.getMethodInstance(cl); } catch (Throwable t) { continue; }
                    if (Modifier.isStatic(m.getModifiers()) || Modifier.isAbstract(m.getModifiers())) continue;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                            if (FeatureFlags.translateMessages && param.args[0] instanceof Enum<?> te && TRANSLATE_CARRIER.equals(te.name())) {
                                ModuleLog.line(TAG + "action selected: translate");
                                param.setResult(null);
                                translate(null);
                                return;
                            }
                            if (!FeatureFlags.enableVoiceDownload) return;
                            if (!(param.args[0] instanceof Enum<?> e) || !CARRIER.equals(e.name())) return;
                            Object msg = recentMessage();
                            String url = msg != null ? audioUrl(msg) : null;
                            ModuleLog.line(TAG + "action selected via " + param.method.getDeclaringClass().getName() + ": voice=" + (url != null));
                            if (url == null) return;
                            param.setResult(null);
                            startDownload(null, url);
                        }
                    });
                    dispatchers++;
                }
            }
            // Compose menu rows: every method that reads the row label field and takes a Kotlin
            // Function1 click callback (X.0Ssj.A00 on 452). Wrap the callback so a tap on our row
            // downloads; other rows are passed through untouched.
            int composeRows = 0;
            try {
                java.lang.reflect.Field labelField = null;
                for (Field f : data.getDeclaredFields()) {
                    if (f.getType() == String.class && !Modifier.isStatic(f.getModifiers())) {
                        // label = constructor param 8 (index 7); pick the field set from it
                        Class<?>[] cp = ctor.getParameterTypes();
                        Object style = cp[1].isEnum() ? cp[1].getEnumConstants()[0] : null;
                        Object act = cp[2].getEnumConstants()[0];
                        ctor.setAccessible(true);
                        Object probe = ctor.newInstance(null, style, act, null, null, null, null, "ie", null, null, null);
                        f.setAccessible(true);
                        if ("ie".equals(f.get(probe))) { labelField = f; break; }
                    }
                }
                if (labelField != null) {
                    labelFieldRef = labelField;
                    final Field lf = labelField;
                    String desc = "L" + ACTION_DATA.replace('.', '/') + ";->" + lf.getName() + ":Ljava/lang/String;";
                    Class<?> fn1 = cl.loadClass("kotlin.jvm.functions.Function1");
                    for (MethodData md : bridge.findMethod(FindMethod.create().matcher(MethodMatcher.create()
                            .addUsingField(desc)))) {
                        if (!md.isMethod()) continue;
                        Method m;
                        try { m = md.getMethodInstance(cl); } catch (Throwable t) { continue; }
                        boolean takesFn = false;
                        for (Class<?> pt : m.getParameterTypes()) if (pt == fn1) takesFn = true;
                        if (!takesFn) continue;
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                                if (!FeatureFlags.enableVoiceDownload && !FeatureFlags.translateMessages) return;
                                for (int i = 0; i < param.args.length; i++) {
                                    Object orig = param.args[i];
                                    if (!fn1.isInstance(orig) || java.lang.reflect.Proxy.isProxyClass(orig.getClass())) continue;
                                    param.args[i] = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{fn1}, (proxy, method, margs) -> {
                                        if (method.getName().equals("invoke") && margs != null && margs.length == 1
                                                && data.isInstance(margs[0]) && isOurRow(margs[0], translateLabel(), TRANSLATE_CARRIER)) {
                                            ModuleLog.line(TAG + "compose row tap: translate");
                                            dismissMenu();
                                            translate(null);
                                            return null;
                                        }
                                        if (method.getName().equals("invoke") && margs != null && margs.length == 1
                                                && data.isInstance(margs[0]) && isOurRow(margs[0], downloadLabel(), CARRIER)) {
                                            Object msg = recentMessage();
                                            String url = msg != null ? audioUrl(msg) : null;
                                            ModuleLog.line(TAG + "compose row tap: voice=" + (url != null));
                                            if (url != null) {
                                                startDownload(null, url);
                                                dismissMenu();
                                                return null;
                                            }
                                        }
                                        if (method.getDeclaringClass() == Object.class) {
                                            if (method.getName().equals("equals")) return proxy == margs[0];
                                            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                                            if (method.getName().equals("toString")) return "IEWrappedClick";
                                        }
                                        return method.invoke(orig, margs);
                                    });
                                }
                            }
                        });
                        composeRows++;
                    }
                }
            } catch (Throwable t) {
                ModuleLog.line(TAG + "⚠️ compose rows: " + t + " / " + t.getCause());
            }
            ModuleLog.line(TAG + "compose row hooks=" + composeRows + " (label field " + (composeRows >= 0 ? "ok" : "") + ")");
            ModuleLog.line(TAG + "✅ lookups=" + lookups + " factories=" + factories + " dispatchers=" + dispatchers
                    + " mapper=" + (audioMapper != null) + " message=" + (messageClass != null ? messageClass.getName() : "?"));
            if (factories > 0 && dispatchers > 0) FeatureStatusTracker.setHooked("VoiceDownload");
            if (factories > 0 && labelFieldRef != null) FeatureStatusTracker.setHooked("ChatTranslate");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    /**
     * The menu renders our row but routes unknown actions to a dismiss-only branch, so attach our
     * own click handler to the row once it is on screen (found by its label), then close the menu.
     */
    private static void wireRowWhenShown(String label, java.util.function.Consumer<Activity> onTap) {
        android.app.Activity act = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        if (act == null) return;
        android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final int[] tries = {0};
        h.post(new Runnable() {
            @Override
            public void run() {
                android.view.View row = findRow(act.getWindow().getDecorView(), label);
                if (row == null) {
                    if (++tries[0] < 25) h.postDelayed(this, 80);
                    return;
                }
                row.setOnClickListener(v -> {
                    onTap.accept(act);
                    act.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));
                    act.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
                });
                ModuleLog.line(TAG + "row wired (" + row.getClass().getSimpleName() + ")");
            }
        });
    }

    /** The clickable row containing a TextView whose text is exactly {@code label}. */
    private static android.view.View findRow(android.view.View root, String label) {
        java.util.ArrayDeque<android.view.View> st = new java.util.ArrayDeque<>();
        st.push(root);
        while (!st.isEmpty()) {
            android.view.View v = st.pop();
            if (v instanceof android.widget.TextView tv && tv.isShown() && label.contentEquals(tv.getText())) {
                android.view.View r = tv;
                for (int i = 0; i < 4 && r.getParent() instanceof android.view.View p; i++) {
                    r = p;
                    if (r.isClickable()) return r;
                }
                return tv.getParent() instanceof android.view.View p ? p : tv;
            }
            if (v instanceof android.view.ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        return null;
    }

    private static volatile String voiceMediaId;

    /** Sender of the voice clip (its Media's owner, like post downloads), else the chat name. */
    private static String voiceUsername(Object message) {
        try {
            if (message != null) {
                Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
                ArrayDeque<Object[]> q = new ArrayDeque<>();
                q.add(new Object[]{message, 0});
                while (!q.isEmpty() && seen.size() < 200) {
                    Object[] e = q.poll();
                    Object o = e[0];
                    int d = (int) e[1];
                    if (o == null || !seen.add(o)) continue;
                    if (o.getClass().getName().equals(MEDIA)) {
                        try {
                            Object id = o.getClass().getMethod("getId").invoke(o);
                            if (id instanceof String sid && !sid.isEmpty()) voiceMediaId = sid.split("_")[0];
                        } catch (Throwable ignored) {}
                        String u = FeedVideoDownloadHook.extractUsernameFromMediaObject(o);
                        if (u != null && !u.isEmpty()) return u;
                        continue;
                    }
                    if (d >= 3) continue;
                    String n = o.getClass().getName();
                    if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
                    for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                        for (Field f : k.getDeclaredFields()) {
                            if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                            try { f.setAccessible(true); q.add(new Object[]{f.get(o), d + 1}); } catch (Throwable ignored) {}
                        }
                }
            }
            String chat = ps.reso.instaeclipse.utils.ghost.ThreadNames.resolve(
                    ps.reso.instaeclipse.utils.ghost.CurrentThreadTracker.currentThreadId());
            if (chat != null && !chat.isEmpty()) return chat.replaceAll("[^A-Za-z0-9._-]", "_");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ username: " + t);
        }
        return "voice";
    }

    /** Our injected row with this label (carried by the CARRIER action, so IG's own rows with the
     *  same text, e.g. its native "Translate", never match). */
    private static boolean isOurRow(Object row, String label, String carrier) {
        try {
            Field lf = labelFieldRef;
            return lf != null && label.equals(lf.get(row)) && carrier.equals(actionName(row));
        } catch (Throwable t) {
            return false;
        }
    }

    private static volatile Field labelFieldRef;
    private static volatile String pendingText;

    private static String downloadLabel() {
        Context c = android.app.AndroidAppHelper.currentApplication();
        return c != null ? I18n.t(c, R.string.ig_dl_title) : "Download";
    }

    private static String translateLabel() {
        Context c = android.app.AndroidAppHelper.currentApplication();
        return c != null ? I18n.t(c, R.string.ig_chat_translate_row) : "Translate with app";
    }

    private static volatile long lastTranslateAt;

    /** Hands the long-pressed message's text to the user's translator app (same as comments). */
    private static void translate(Context ctx) {
        if (System.currentTimeMillis() - lastTranslateAt < 1500) return; // several tap paths fire
        lastTranslateAt = System.currentTimeMillis();
        String text = pendingText;
        if (text == null) {
            Object msg = recentMessage();
            text = msg != null ? messageText(msg) : null;
        }
        if (text == null) return;
        Context c = ctx != null ? ctx : ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        if (c == null) c = android.app.AndroidAppHelper.currentApplication();
        final Context host = c;
        final String t = text;
        new android.os.Handler(android.os.Looper.getMainLooper()).post(
                () -> ps.reso.instaeclipse.utils.core.TextTranslator.translate(host, t));
    }

    /**
     * The message's own text, found by shape rather than by (obfuscated) name: a String field
     * declared on the message object whose value is repeated inside its inner message model
     * (wrapper + model both carry the text). Ids ("mid.…", digits) are skipped. Falls back to the
     * single non-id String field when nothing repeats.
     */
    static String messageText(Object msg) {
        try {
            List<String> own = new ArrayList<>();
            List<Object> children = new ArrayList<>();
            boolean textType = false; // the message type enum (TEXT on 451); skips media/stickers
            for (Class<?> k = msg.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v = f.get(msg);
                    if (v instanceof Enum<?> e && ("TEXT".equals(e.name()) || "LINK".equals(e.name()))) textType = true;
                    if (v instanceof String s) { if (isTextLike(s)) own.add(s); }
                    else if (v != null && (v.getClass().getName().startsWith("X.") || v.getClass().getName().startsWith("com.instagram."))) children.add(v);
                }
            }
            if (own.isEmpty() || !textType) return null;
            Set<String> nested = new java.util.HashSet<>();
            for (Object c : children)
                for (Class<?> k = c.getClass(); k != null && k != Object.class; k = k.getSuperclass())
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
                        try { f.setAccessible(true); Object v = f.get(c); if (v instanceof String s) nested.add(s); } catch (Throwable ignored) {}
                    }
            // The text is stored twice on 451 (a field of the message class and one of its base
            // class); count both that and copies in a nested model.
            java.util.Map<String, Integer> seenCount = new java.util.HashMap<>();
            for (String s : own) seenCount.merge(s, 1, Integer::sum);
            String best = null;
            for (String s : own) {
                boolean repeated = seenCount.get(s) > 1 || nested.contains(s);
                if (repeated && (best == null || s.length() > best.length())) best = s;
            }
            if (best == null && own.size() == 1) best = own.get(0);
            return best;
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ text: " + t);
            return null;
        }
    }

    private static boolean isTextLike(String s) {
        if (s == null || s.trim().isEmpty()) return false;
        if (s.startsWith("mid.") || s.matches("[0-9]+")) return false;
        return true;
    }

    private static void dismissMenu() {
        android.app.Activity act = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        if (act == null) return;
        act.runOnUiThread(() -> {
            act.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK));
            act.dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
        });
    }

    private static volatile long lastDownloadAt;

    private static void startDownload(Context ctx, String url) {
        if (System.currentTimeMillis() - lastDownloadAt < 1500) return; // both tap paths fired
        lastDownloadAt = System.currentTimeMillis();
        if (ctx == null) ctx = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
        if (ctx == null) return;
        voiceMediaId = null;
        String user = voiceUsername(recentMessage());
        String id = voiceMediaId != null ? voiceMediaId : String.valueOf(System.currentTimeMillis());
        String name = FeedVideoDownloadHook.buildFilename(user, "voice", id, true).replace(".mp4", ".m4a");
        FeedVideoDownloadHook.downloadAudio(ctx, url, name, user);
    }

    private static String actionName(Object row) {
        try {
            Field f = row.getClass().getDeclaredFields()[0];
            for (Field g : row.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(g.getModifiers()) || !g.getType().isEnum()) continue;
                g.setAccessible(true);
                Object v = g.get(row);
                // the action enum is the constructor's 3rd param type; its values have names like REPLY
                if (v instanceof Enum<?> e && e.getDeclaringClass() == (saveMediaAction != null ? saveMediaAction.getClass() : e.getDeclaringClass())) {
                    String n = e.name();
                    if (n.equals(n.toUpperCase()) && n.length() > 2) return n;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static boolean isSaveMedia(Object row) {
        for (Field f : row.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || !f.getType().isEnum()) continue;
            try {
                f.setAccessible(true);
                Object v = f.get(row);
                if (v instanceof Enum<?> e && CARRIER.equals(e.name())) return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /** DEBUG: types reachable from a message, to find where the voice clip lives. */
    private static void dumpTypes(Object msg) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Class<?> k = msg.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                for (Field f : k.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                    f.setAccessible(true);
                    Object v = f.get(msg);
                    if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) continue;
                    sb.append(f.getName()).append('=').append(v.getClass().getName()).append(' ');
                }
            }
            ModuleLog.line(TAG + "message fields: " + sb);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "dump: " + t);
        }
    }

    private static boolean hasAction(Object[] args, String name) {
        for (Object a : args) if (a instanceof Enum<?> e && name.equals(e.name())) return true;
        return false;
    }

    private static Object recentMessage() {
        return System.currentTimeMillis() - lastMessageAt < 15000 ? lastMessage : null;
    }

    /** Our injected row ("Download" / "Translate with app"), styled like an existing row. */
    private static Object buildRow(Constructor<?> ctor, Object template, Context ctx, String label, String iconName, String carrier) {
        try {
            Class<?>[] p = ctor.getParameterTypes();
            if (saveMediaAction == null) {
                for (Object v : p[2].getEnumConstants()) if (CARRIER.equals(((Enum<?>) v).name())) saveMediaAction = v;
            }
            if (saveMediaAction == null) return null;
            Object style = null;
            Integer icon = null;
            for (Field f : template.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (f.getType() == p[1] && style == null) style = f.get(template);
                if (f.getType() == Integer.class && icon == null) icon = (Integer) f.get(template);
            }
            Context c = ctx != null ? ctx : ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
            if (c != null) {
                @SuppressWarnings("DiscouragedApi")
                int id = c.getResources().getIdentifier(iconName, "drawable", c.getPackageName());
                if (id != 0) icon = id;
            }
            ctor.setAccessible(true);
            Object action = saveMediaAction;
            for (Object v : p[2].getEnumConstants()) if (carrier.equals(((Enum<?>) v).name())) action = v;
            return ctor.newInstance(null, style, action, null, null, icon, null, label, null, null, null);
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ row: " + t);
            return null;
        }
    }

    /** audio_src of the message's voice clip, or null when it isn't a voice message. */
    static String audioUrl(Object message) {
        if (audioMapper == null || audioInfoClass == null) return null;
        try {
            Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            ArrayDeque<Object[]> q = new ArrayDeque<>();
            q.add(new Object[]{message, 0});
            while (!q.isEmpty() && seen.size() < 300) {
                Object[] e = q.poll();
                Object o = e[0];
                int d = (int) e[1];
                if (o == null || !seen.add(o)) continue;
                if (audioInfoClass.isInstance(o)) {
                    Object map = audioMapper.invoke(null, o);
                    Object src = map instanceof Map<?, ?> m ? m.get("audio_src") : null;
                    if (src instanceof String s && s.startsWith("http")) return s;
                    continue;
                }
                if (o.getClass().getName().equals(MEDIA)) {
                    for (Method m : o.getClass().getMethods()) {
                        if (m.getParameterCount() == 0 && m.getReturnType() == audioInfoClass) {
                            try { q.add(new Object[]{m.invoke(o), d + 1}); } catch (Throwable ignored) {}
                        }
                    }
                    continue;
                }
                if (d >= 3) continue;
                String n = o.getClass().getName();
                if (!n.startsWith("X.") && !n.startsWith("com.instagram.")) continue;
                for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                        try { f.setAccessible(true); q.add(new Object[]{f.get(o), d + 1}); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ audio url: " + t);
        }
        return null;
    }
}
