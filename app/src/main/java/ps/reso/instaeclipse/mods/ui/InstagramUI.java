package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.dialog.DialogUtils;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.GhostModeUtils;

public class InstagramUI {
    @SuppressLint("StaticFieldLeak")
    private static Activity currentActivity;
    @SuppressLint("StaticFieldLeak")
    private static TextView ghostEmojiView;


    public static Activity getCurrentActivity() {
        return currentActivity;
    }

    public static void addGhostEmojiNextToInbox(Activity activity, boolean showGhost) {
        try {
            int inboxButtonId = activity.getResources().getIdentifier("action_bar_inbox_button", "id", activity.getPackageName());
            View inboxButton = activity.findViewById(inboxButtonId);
            int translationY = -65; // default for action_bar_inbox_button

            // If "action_bar_inbox_button" wasn't found, try "direct_tab"
            if (inboxButton == null) {
                inboxButtonId = activity.getResources().getIdentifier("direct_tab", "id", activity.getPackageName());
                inboxButton = activity.findViewById(inboxButtonId);
                translationY = 35;
            }

            if (inboxButton != null) {
                ViewGroup parent = (ViewGroup) inboxButton.getParent();

                if (showGhost) {
                    if (ghostEmojiView == null || ghostEmojiView.getParent() == null) {
                        ghostEmojiView = new TextView(activity);
                        ghostEmojiView.setText("👻");
                        ghostEmojiView.setTextSize(18);
                        ghostEmojiView.setTextColor(android.graphics.Color.WHITE);
                        ghostEmojiView.setPadding(8, 0, 0, 0);
                        ghostEmojiView.setTranslationY(translationY);

                        int index = parent.indexOfChild(inboxButton);
                        parent.addView(ghostEmojiView, index + 1);
                    }
                    ghostEmojiView.setVisibility(View.VISIBLE);
                } else {
                    if (ghostEmojiView != null) {
                        ghostEmojiView.setVisibility(View.GONE);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }


    private static void setupHooks(Activity activity) {
        // Hook Search Tab (open InstaEclipse Settings)
        hookLongPress(activity, "search_tab", v -> {
            DialogUtils.showEclipseOptionsDialog(activity);
            vibrate(activity);
            return true;
        }, "search_tab");

        // Hook Inbox Button (toggle Ghost Quick Options)
        String[] possibleIds = {"action_bar_inbox_button", "direct_tab"};

        for (String id : possibleIds) {
            int viewId = activity.getResources().getIdentifier(id, "id", activity.getPackageName());
            View view = activity.findViewById(viewId);
            if (view != null) {
                hookLongPress(activity, id, v -> {
                    GhostModeUtils.toggleSelectedGhostOptions(activity);
                    vibrate(activity);
                    return true;
                }, id);
                break;
            }
        }

        addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());

    }

    private static void hookLongPress(Activity activity, String viewName, View.OnLongClickListener listener, String logName) {
        try {
            @SuppressLint("DiscouragedApi") int viewId = activity.getResources().getIdentifier(viewName, "id", activity.getPackageName());
            View view = activity.findViewById(viewId);

            if (view != null) {
                view.setOnLongClickListener(listener);
            }
        } catch (Exception ignored) {
        }
    }


    // Importing meta config from clipboard
    public static void importConfigFromClipboard(Context context) {

        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return;
            }

            ClipData clipData = clipboard.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() == 0) {
                return;
            }

            CharSequence clipText = clipData.getItemAt(0).getText();
            if (clipText == null || clipText.length() == 0) {
                return;
            }

            String json = clipText.toString().trim();
            if (!json.startsWith("{") || !json.endsWith("}")) {
                return;
            }

            File dest = new File(context.getFilesDir(), "mobileconfig/mc_overrides.json");
            if (!dest.getParentFile().exists()) dest.getParentFile().mkdirs();

            try (FileOutputStream fos = new FileOutputStream(dest, false)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }

            Toast.makeText(context, "✅ Imported into mc_overrides.json", Toast.LENGTH_LONG).show();
            XposedBridge.log("InstaEclipse | ✅ JSON imported from clipboard into mc_overrides.json");

        } catch (Exception e) {
            XposedBridge.log("InstaEclipse | ❌ Clipboard import failed: " + e.getMessage());
        }
    }

    public static void exportCurrentDevConfig(Context context) {
        if (!FeatureFlags.isExportingConfig) {
            return;
        }
        try {
            File source = new File(context.getFilesDir(), "mobileconfig/mc_overrides.json");
            if (!source.exists()) {
                XposedBridge.log("InstaEclipse | ❌ mc_overrides.json not found.");
                return;
            }

            StringBuilder jsonBuilder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(source))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonBuilder.append(line).append("\n");
                }
            }

            String jsonContent = jsonBuilder.toString().trim();

            if (!jsonContent.startsWith("{") || !jsonContent.endsWith("}")) {
                XposedBridge.log("InstaEclipse | ❌ mc_overrides.json does not contain valid JSON.");
                return;
            }

            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("json", jsonContent);
                clipboard.setPrimaryClip(clip);
                XposedBridge.log("InstaEclipse | ✅ Copied mc_overrides.json to clipboard.");
            }

        } catch (Exception e) {
            XposedBridge.log("InstaEclipse | ❌ Failed to export config: " + e.getMessage());
        } finally {
            FeatureFlags.isExportingConfig = false;
        }

    }


    @SuppressLint("ObsoleteSdkInt")
    private static void vibrate(Context context) {
        try {
            Vibrator v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    v.vibrate(50); // For older Android versions
                }
            }
        } catch (Exception e) {
            XposedBridge.log("InstaEclipse (vibrate error): " + e.getMessage());
        }
    }


    private static boolean isAnyGhostOptionEnabled() {
        return GhostModeUtils.isGhostModeActive();
    }

    public void mainActivity(ClassLoader classLoader) {
        // Hook onCreate of InstagramMainActivity
        XposedHelpers.findAndHookMethod("com.instagram.mainactivity.InstagramMainActivity", classLoader, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                final Activity activity = (Activity) param.thisObject;
                currentActivity = activity; // Save the activity
                activity.runOnUiThread(() -> {
                    try {
                        setupHooks(activity);
                        addGhostEmojiNextToInbox(activity, isAnyGhostOptionEnabled());
                    } catch (Exception ignored) {

                    }
                });
            }
        });

        // Hook onResume
        XposedHelpers.findAndHookMethod("com.instagram.mainactivity.InstagramMainActivity", classLoader, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                final Activity activity = (Activity) param.thisObject;
                currentActivity = activity;
                activity.runOnUiThread(() -> {
                    try {
                        setupHooks(activity);
                        addGhostEmojiNextToInbox(activity, isAnyGhostOptionEnabled());
                        if (FeatureFlags.isImportingConfig) {
                            FeatureFlags.isImportingConfig = false;
                            importConfigFromClipboard(activity);
                        }

                    } catch (Exception ignored) {
                    }
                });
            }
        });

        XposedHelpers.findAndHookMethod("com.instagram.mainactivity.InstagramMainActivity", classLoader, "getBottomSheetNavigator", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                final Activity activity = (Activity) InstagramUI.getCurrentActivity();
                if (activity != null) {
                    activity.runOnUiThread(() -> {
                        try {
                            InstagramUI.setupHooks(activity);
                            InstagramUI.addGhostEmojiNextToInbox(activity, GhostModeUtils.isGhostModeActive());
                        } catch (Exception ignored) {
                        }
                    });
                }
            }
        });

    }
}
