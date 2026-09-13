package ps.reso.instaeclipse.utils.toast;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import de.robv.android.xposed.XposedBridge;
import ps.reso.instaeclipse.utils.log.ModuleLog;

public class CustomToast {

    public static boolean toastShown = false;

    public static void showCustomToast(Context context, String message) {
        if (context == null) {
            ModuleLog.line("❌ CustomToast: Context is null!");
            return;
        }

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Context safeContext = new android.view.ContextThemeWrapper(context, android.R.style.Theme_Material_Light);
                TextView toastText = new TextView(safeContext);
                toastText.setText(message);
                toastText.setTextColor(Color.WHITE);
                toastText.setBackgroundColor(Color.parseColor("#CC000000")); // semi-transparent black
                toastText.setPadding(40, 25, 40, 25);
                toastText.setTextSize(16);
                toastText.setGravity(Gravity.CENTER);

                android.widget.Toast toast = new android.widget.Toast(context);
                toast.setView(toastText);
                toast.setDuration(android.widget.Toast.LENGTH_SHORT);
                toast.setGravity(Gravity.BOTTOM, 0, 150);
                toast.show();
                new Handler(Looper.getMainLooper()).postDelayed(toast::cancel, 1500);

            } catch (Throwable t) {
                ModuleLog.line("❌ Failed to show custom toast: " + Log.getStackTraceString(t));
            }
        });
    }

    // Category order + accent (mirrors the in-IG dialog's 4 groups).
    private static final String[] CAT_ORDER = {"Appearance", "Privacy", "Media", "Tools"};
    private static int catAccent(String cat) {
        switch (cat) {
            case "Appearance": return Color.parseColor("#FF375F");
            case "Privacy":    return Color.parseColor("#5E5CE6");
            case "Media":      return Color.parseColor("#FF9F0A");
            default:           return Color.parseColor("#8E8E93");
        }
    }

    /** Map a FeatureStatusTracker key to one of the 4 categories (matches the dialog grouping). */
    private static String categoryOf(String key) {
        switch (key) {
            case "CustomTheme": case "CustomFont": case "CustomEmoji": case "ForceReelQuality":
            case "HideSuggestionsInFeed": case "HideThreadsSuggestions":
                return "Appearance";
            case "GhostSeen": case "GhostTyping": case "GhostStories": case "GhostLive":
            case "GhostViewOnce": case "GhostScreenshot": case "AllowScreenshots":
            case "KeepEphemeralMessages": case "KeepUnsentMessages": case "PermanentViewMode":
            case "LockDirectMessages": case "HideSpecificChats": case "AdBlocker":
            case "DisableTrackingLinks": case "RemoveMetaAI": case "SpoofLastSeen":
            case "DisableDiscoverPeople":
                return "Privacy";
            case "PostDownload": case "ReelDownload": case "StoryDownload": case "ProfileDownload":
            case "CopyMediaLink": case "SaveInstants": case "UploadInstants": case "CacheStories":
            case "SpoofLocation": case "StoryMentions": case "CaptionCopy": case "CopyComment":
            case "PhotoZoom":
                return "Media";
            default:
                return "Tools"; // DevOptions, RemoveBuildExpiredPopup, FollowerToast, DisableDoubleTapLike…
        }
    }

    /**
     * Feature-status toast: a rounded card grouping active features by category (Appearance /
     * Privacy / Media / Tools), each section under an accent header, laid out in a dense 3-column
     * checklist grid — green ✓ = hook confirmed installed, muted ○ = enabled-but-pending.
     * {@code status} maps FeatureStatusTracker KEY -> hooked. Labels are resolved per key.
     */
    public static void showFeatureGrid(Context context, String title,
                                       java.util.LinkedHashMap<String, Boolean> status) {
        if (context == null || status == null || status.isEmpty()) return;

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                final float d = context.getResources().getDisplayMetrics().density;
                final int on  = Color.parseColor("#30D158");
                final int off = Color.parseColor("#8E8E93");
                final int txt = Color.parseColor("#F2F2F7");

                // Bucket keys by category (preserving the 4-group order).
                java.util.LinkedHashMap<String, java.util.List<String>> byCat = new java.util.LinkedHashMap<>();
                for (String c : CAT_ORDER) byCat.put(c, new java.util.ArrayList<>());
                int active = 0;
                for (java.util.Map.Entry<String, Boolean> e : status.entrySet()) {
                    byCat.get(categoryOf(e.getKey())).add(e.getKey());
                    if (Boolean.TRUE.equals(e.getValue())) active++;
                }

                android.widget.LinearLayout card = new android.widget.LinearLayout(context);
                card.setOrientation(android.widget.LinearLayout.VERTICAL);
                android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
                bg.setColor(Color.parseColor("#F214141A"));
                bg.setCornerRadius(26 * d);
                bg.setStroke(Math.max(1, (int) d), Color.parseColor("#33FFFFFF"));
                card.setBackground(bg);
                int p = (int) (18 * d);
                card.setPadding(p, (int) (15 * d), p, (int) (17 * d));

                android.widget.TextView header = new android.widget.TextView(context);
                header.setText(title + "   " + active + "/" + status.size());
                header.setTextColor(Color.WHITE);
                header.setTextSize(15);
                header.setTypeface(null, android.graphics.Typeface.BOLD);
                header.setLetterSpacing(0.01f);
                header.setPadding(0, 0, 0, (int) (6 * d));
                card.addView(header);

                for (String cat : CAT_ORDER) {
                    java.util.List<String> keys = byCat.get(cat);
                    if (keys == null || keys.isEmpty()) continue;

                    android.widget.TextView catHeader = new android.widget.TextView(context);
                    catHeader.setText(cat.toUpperCase(java.util.Locale.getDefault()));
                    catHeader.setTextColor(catAccent(cat));
                    catHeader.setTextSize(11);
                    catHeader.setTypeface(null, android.graphics.Typeface.BOLD);
                    catHeader.setLetterSpacing(0.08f);
                    catHeader.setPadding(0, (int) (10 * d), 0, (int) (5 * d));
                    card.addView(catHeader);

                    android.widget.GridLayout grid = new android.widget.GridLayout(context);
                    grid.setColumnCount(2); // 2 cols so full feature names fit without truncation
                    for (String key : keys) {
                        boolean hooked = Boolean.TRUE.equals(status.get(key));
                        boolean brokenState = !hooked
                                && ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.isBroken(key);

                        android.widget.LinearLayout cell = new android.widget.LinearLayout(context);
                        cell.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                        cell.setGravity(Gravity.CENTER_VERTICAL);
                        int cp = (int) (4 * d);
                        cell.setPadding(0, cp, (int) (10 * d), cp);

                        android.widget.TextView mark = new android.widget.TextView(context);
                        // ✓ hook confirmed, ✗ enabled but hook failed (broken), ○ enabled/pending.
                        mark.setText(hooked ? "✓" : brokenState ? "✗" : "○");
                        mark.setTextColor(hooked ? on : brokenState ? Color.parseColor("#FF453A") : off);
                        mark.setTextSize(13);
                        mark.setTypeface(null, android.graphics.Typeface.BOLD);
                        mark.setPadding(0, 0, (int) (6 * d), 0);

                        android.widget.TextView lbl = new android.widget.TextView(context);
                        lbl.setText(ps.reso.instaeclipse.utils.feature.FeatureStatusTracker.getLabel(context, key));
                        lbl.setTextColor(hooked ? txt : brokenState ? Color.parseColor("#FF453A") : off);
                        lbl.setTextSize(12);
                        lbl.setMaxLines(1);
                        lbl.setEllipsize(android.text.TextUtils.TruncateAt.END);

                        cell.addView(mark);
                        cell.addView(lbl);

                        android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
                        lp.width = 0;
                        lp.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f);
                        lp.setGravity(Gravity.FILL_HORIZONTAL);
                        cell.setLayoutParams(lp);
                        grid.addView(cell);
                    }
                    card.addView(grid);
                }

                android.widget.FrameLayout wrap = new android.widget.FrameLayout(context);
                int m = (int) (14 * d);
                wrap.setPadding(m, 0, m, 0);
                // Pin the card to (screen width - margins) so the weighted grid columns get real
                // width — otherwise the Toast wraps to content and every label ellipsizes to ~6 chars.
                int screenW = context.getResources().getDisplayMetrics().widthPixels;
                card.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                        screenW - 2 * m, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT));
                wrap.addView(card);

                android.widget.Toast toast = new android.widget.Toast(context);
                toast.setView(wrap);
                toast.setDuration(android.widget.Toast.LENGTH_LONG);
                toast.setGravity(Gravity.BOTTOM, 0, (int) (80 * d));
                toast.show();
                // Re-show so a full grid stays readable ~6s (a single LENGTH_LONG is ~3.5s).
                new Handler(Looper.getMainLooper()).postDelayed(toast::show, 3200);
                new Handler(Looper.getMainLooper()).postDelayed(toast::cancel, 6500);
            } catch (Throwable t) {
                ModuleLog.line("❌ showFeatureGrid failed: " + Log.getStackTraceString(t));
            }
        });
    }

}
