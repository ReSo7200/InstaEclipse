package ps.reso.instaeclipse.mods.ghost;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedHelpers;
import ps.reso.instaeclipse.utils.core.TextTranslator;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

/**
 * Long-press on the Direct Message composer's Send button to automatically translate
 * the draft text in-place before sending.
 */
public class ComposerSendTranslateHook {

    private static final String TAG = "(InstaEclipse | SendTranslate): ";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final String PREF_LAST_LANG = "ie_dm_send_translate_lang";

    private static final String[] LANG_CODES = {
            "en", "tr", "de", "es", "fr", "ru", "ar", "it", "pt", "ja", "ko", "zh-CN"
    };
    private static final String[] LANG_NAMES = {
            "English (İngilizce)",
            "Türkçe (Turkish)",
            "Deutsch (Almanca)",
            "Español (İspanyolca)",
            "Français (Fransızca)",
            "Русский (Rusça)",
            "العربية (Arapça)",
            "Italiano (İtalyanca)",
            "Português (Portekizce)",
            "日本語 (Japonca)",
            "한국어 (Korece)",
            "中文 (Çince)"
    };

    public void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if (!FeatureFlags.dmSendTranslate) return;

                    View view = (View) param.thisObject;
                    int id = view.getId();
                    if (id == View.NO_ID) return;

                    if (isSendButton(view)) {
                        attachSendLongPress(view);
                    }
                }
            });

            FeatureStatusTracker.setHooked("DMSendTranslate");
            ModuleLog.line(TAG + "✅ Composer send translate hook installed.");
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ Hook failed: " + t.getMessage());
        }
    }

    private static boolean isSendButton(View view) {
        try {
            int id = view.getId();
            if (id == View.NO_ID) return false;
            String entryName = view.getResources().getResourceEntryName(id);
            if (entryName == null) return false;
            return entryName.equals("row_thread_composer_send_button")
                    || entryName.equals("row_thread_composer_send_button_container")
                    || entryName.equals("direct_text_send_button")
                    || entryName.equals("direct_composer_send_button")
                    || entryName.equals("send_button");
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void attachSendLongPress(View sendButton) {
        sendButton.setOnLongClickListener(v -> {
            if (!FeatureFlags.dmSendTranslate) return false;

            Context ctx = v.getContext();
            EditText editText = findComposerEditText(v);
            if (editText == null) {
                ModuleLog.line(TAG + "❌ Composer EditText not found.");
                return false;
            }

            String currentText = editText.getText() != null ? editText.getText().toString().trim() : "";
            if (currentText.isEmpty()) {
                IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_enter_text_to_translate), Toast.LENGTH_SHORT).show();
                return true;
            }

            showTranslationDialog(ctx, editText, currentText);
            return true;
        });
    }

    private static void showTranslationDialog(Context ctx, EditText editText, String text) {
        SharedPreferences prefs = ctx.getSharedPreferences("instaeclipse_prefs", Context.MODE_PRIVATE);
        String lastLang = prefs.getString(PREF_LAST_LANG, "en");

        int defaultIndex = 0;
        for (int i = 0; i < LANG_CODES.length; i++) {
            if (LANG_CODES[i].equalsIgnoreCase(lastLang)) {
                defaultIndex = i;
                break;
            }
        }

        final int[] selectedIndex = {defaultIndex};

        new AlertDialog.Builder(ctx)
                .setTitle(I18n.t(ctx, R.string.ig_translate_text) + " (" + LANG_NAMES[defaultIndex] + ")")
                .setSingleChoiceItems(LANG_NAMES, defaultIndex, (dialog, which) -> selectedIndex[0] = which)
                .setPositiveButton(I18n.t(ctx, R.string.ig_translate_text), (dialog, which) -> {
                    String targetLang = LANG_CODES[selectedIndex[0]];
                    prefs.edit().putString(PREF_LAST_LANG, targetLang).apply();

                    IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_translating), Toast.LENGTH_SHORT).show();
                    performTranslation(ctx, editText, text, targetLang);
                })
                .setNeutralButton(I18n.t(ctx, R.string.ig_chat_translate_row), (dialog, which) -> {
                    // Fallback to system translate intent
                    TextTranslator.translate(ctx, text);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void performTranslation(Context ctx, EditText editText, String text, String targetLang) {
        TextTranslator.translateOnline(text, targetLang, new TextTranslator.TranslateCallback() {
            @Override
            public void onSuccess(String translatedText) {
                MAIN_HANDLER.post(() -> {
                    try {
                        editText.setText(translatedText);
                        editText.setSelection(translatedText.length());
                        IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_translated), Toast.LENGTH_SHORT).show();
                    } catch (Throwable t) {
                        ModuleLog.line(TAG + "Error setting translated text: " + t.getMessage());
                    }
                });
            }

            @Override
            public void onError(Throwable error) {
                MAIN_HANDLER.post(() -> {
                    ModuleLog.line(TAG + "Online translation failed, opening system app: " + error.getMessage());
                    TextTranslator.translate(ctx, text);
                });
            }
        });
    }

    private static EditText findComposerEditText(View startView) {
        // 1. Walk up to composer container
        View current = startView;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (current instanceof ViewGroup group) {
                EditText found = findEditTextInViewGroup(group);
                if (found != null) return found;
            }
            if (current.getParent() instanceof View parentView) {
                current = parentView;
            } else {
                break;
            }
        }
        return null;
    }

    private static EditText findEditTextInViewGroup(ViewGroup group) {
        int count = group.getChildCount();
        for (int i = 0; i < count; i++) {
            View child = group.getChildAt(i);
            if (child instanceof EditText et) {
                return et;
            }
            if (child instanceof ViewGroup subGroup) {
                EditText found = findEditTextInViewGroup(subGroup);
                if (found != null) return found;
            }
        }
        return null;
    }
}
