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
                        protected void afterHookedMethod(MethodHookParam param) {
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
            int factories = 0;
            for (Method m : vm.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != vm) continue;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!FeatureFlags.enableVoiceDownload || ctor == null) return;
                        Object msg = recentMessage();
                        if (msg == null || audioUrl(msg) == null) return;
                        for (int i = 0; i < param.args.length; i++) {
                            if (!(param.args[i] instanceof List<?> list) || list.isEmpty() || !data.isInstance(list.get(0))) continue;
                            Object row = buildRow(ctor, list.get(0), param.args[0] instanceof Context c ? c : null);
                            if (row == null) return;
                            List<Object> copy = new ArrayList<>(list);
                            copy.add(row);
                            param.args[i] = copy;
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
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!FeatureFlags.enableVoiceDownload) return;
                        Object action = null, msg = null;
                        Context ctx = null;
                        for (Object a : param.args) {
                            if (a instanceof Enum<?> e && "SAVE_MEDIA".equals(e.name())) action = a;
                            else if (a != null && messageClass != null && messageClass.isInstance(a)) msg = a;
                            else if (a instanceof Activity act) ctx = act;
                        }
                        if (action == null) return;
                        if (msg == null) msg = recentMessage();
                        String url = msg != null ? audioUrl(msg) : null;
                        if (url == null) return;
                        param.setResult(null);
                        if (ctx == null) ctx = ps.reso.instaeclipse.mods.ui.UIHookManager.getCurrentActivity();
                        if (ctx == null) return;
                        String name = "voice_message_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".m4a";
                        FeedVideoDownloadHook.downloadAudio(ctx, url, name, "voice");
                    }
                });
                dispatchers++;
            }
            ModuleLog.line(TAG + "✅ lookups=" + lookups + " factories=" + factories + " dispatchers=" + dispatchers
                    + " mapper=" + (audioMapper != null) + " message=" + (messageClass != null ? messageClass.getName() : "?"));
            if (factories > 0 && dispatchers > 0) FeatureStatusTracker.setHooked("VoiceDownload");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    private static Object recentMessage() {
        return System.currentTimeMillis() - lastMessageAt < 15000 ? lastMessage : null;
    }

    /** Our "Download" row, styled like the first existing row. */
    private static Object buildRow(Constructor<?> ctor, Object template, Context ctx) {
        try {
            Class<?>[] p = ctor.getParameterTypes();
            if (saveMediaAction == null) {
                for (Object v : p[2].getEnumConstants()) if ("SAVE_MEDIA".equals(((Enum<?>) v).name())) saveMediaAction = v;
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
                int id = c.getResources().getIdentifier("instagram_download_outline_24", "drawable", c.getPackageName());
                if (id != 0) icon = id;
            }
            String label = c != null ? I18n.t(c, R.string.ig_dl_title) : "Download";
            ctor.setAccessible(true);
            return ctor.newInstance(null, style, saveMediaAction, null, null, icon, null, label, null, null, null);
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
