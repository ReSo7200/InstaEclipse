package ps.reso.instaeclipse.utils.core;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;

import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Hands a piece of text to the user's translation app (#233) — no network or API keys of our own.
 *
 * Tries, in order: the system translate intent (handled by Google Translate and others), a
 * PROCESS_TEXT chooser (DeepL, Translate, …), then a plain share sheet. Each step is a straight
 * startActivity in try/catch rather than a resolveActivity check, because Instagram's own
 * package-visibility (<queries>) would hide most handlers from a lookup.
 */
public final class TextTranslator {

    private TextTranslator() {}

    public interface TranslateCallback {
        void onSuccess(String translatedText);
        void onError(Throwable error);
    }

    public static void translate(Context ctx, String text) {
        if (ctx == null || text == null || text.trim().isEmpty()) return;
        boolean newTask = !(ctx instanceof Activity);

        Intent translate = new Intent("android.intent.action.TRANSLATE")
                .putExtra(Intent.EXTRA_TEXT, text);
        if (start(ctx, translate, newTask)) return;

        Intent process = new Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
        if (start(ctx, Intent.createChooser(process, "Translate"), newTask)) return;

        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text);
        start(ctx, Intent.createChooser(send, "Translate"), newTask);
    }

    public static void translateOnline(String text, String targetLang, TranslateCallback callback) {
        if (text == null || text.trim().isEmpty()) {
            if (callback != null) callback.onError(new IllegalArgumentException("Empty text"));
            return;
        }
        final String lang = (targetLang == null || targetLang.isEmpty()) ? "en" : targetLang;
        new Thread(() -> {
            // 1. Primary: Google Translate gtx single endpoint
            try {
                String query = java.net.URLEncoder.encode(text, "UTF-8");
                String gUrl = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" + lang + "&dt=t&q=" + query;
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(gUrl).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
                conn.setConnectTimeout(4500);
                conn.setReadTimeout(4500);
                if (conn.getResponseCode() == 200) {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        org.json.JSONArray root = new org.json.JSONArray(sb.toString());
                        org.json.JSONArray sentences = root.getJSONArray(0);
                        StringBuilder translated = new StringBuilder();
                        for (int i = 0; i < sentences.length(); i++) {
                            org.json.JSONArray s = sentences.getJSONArray(i);
                            translated.append(s.getString(0));
                        }
                        if (translated.length() > 0) {
                            if (callback != null) callback.onSuccess(translated.toString());
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 2. Secondary: MyMemory Translation API
            try {
                String query = java.net.URLEncoder.encode(text, "UTF-8");
                String mUrl = "https://api.mymemory.translated.net/get?q=" + query + "&langpair=auto|" + lang;
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(mUrl).openConnection();
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36");
                conn.setConnectTimeout(4500);
                conn.setReadTimeout(4500);
                if (conn.getResponseCode() == 200) {
                    try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) sb.append(line);
                        org.json.JSONObject obj = new org.json.JSONObject(sb.toString());
                        if (obj.has("responseData")) {
                            String trans = obj.getJSONObject("responseData").optString("translatedText");
                            if (trans != null && !trans.isEmpty() && !trans.equalsIgnoreCase("NO QUERY SPECIFIED")) {
                                if (callback != null) callback.onSuccess(trans);
                                return;
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                if (callback != null) callback.onError(t);
                return;
            }

            if (callback != null) callback.onError(new Exception("Translation service unavailable"));
        }, "IE-TranslateOnline").start();
    }

    private static boolean start(Context ctx, Intent intent, boolean newTask) {
        try {
            if (newTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        } catch (Throwable t) {
            ModuleLog.line("(IE|Translate) ⚠️ " + t);
            return false;
        }
    }
}
