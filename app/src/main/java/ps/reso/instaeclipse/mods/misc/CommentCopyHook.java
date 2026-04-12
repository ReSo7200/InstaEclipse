package ps.reso.instaeclipse.mods.misc;

import android.app.Activity;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;

/**
 * CommentCopyHook
 *
 * Detects long-press on a comment row (identified by the view tag
 * "row_comment_section_container_*") and shows a copy dialog.
 *
 * Text extraction uses a two-pass approach:
 *   1. contentDescription suffix parsing (language-aware)
 *   2. Recursive TextView / reflection getText() fallback
 * The reflection fallback handles Instagram custom text-view subclasses
 * that do not inherit android.widget.TextView, which caused the
 * "Container found but text empty" failure on newer builds.
 */
public class CommentCopyHook {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Activity currentActivity = null;

    private static Runnable      pendingRunnable = null;
    private static float         downRawX        = 0f;
    private static float         downRawY        = 0f;
    private static boolean       longPressFired  = false;
    private static volatile View  pendingRoot    = null;
    private static volatile Context pendingCtx   = null;

    private static final long  LONG_PRESS_MS = 500L;
    private static final float SLOP_DP       = 30f;

    private static final String COMMENT_ROW_TAG = "row_comment_section_container_";

    private static final String[] COMMENT_SUFFIXES = {
        " yorumunu yaptı",
        " yorum yaptı",
        " commented",
        " hat kommentiert",
        " a commenté",
        " comentó",
        " comentou",
        " прокомментировал",
        " прокомментировала",
        " ha commentato",
        " comentou",
        " コメントしました",
        " 댓글을 달았습니다",
        " تعليق",
    };

    // ─────────────────────────────────────────────────────────────────────────

    public void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    currentActivity = (Activity) p.thisObject;
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onDestroy", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (currentActivity == p.thisObject) currentActivity = null;
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | CopyComment): ⚠️ Activity tracker – " + t);
        }

        hookWindow(Activity.class);
        hookWindow(Dialog.class);

        FeatureStatusTracker.setHooked("CopyComment");
    }

    // ── Window hook ───────────────────────────────────────────────────────────

    private static void hookWindow(Class<?> cls) {
        try {
            XposedHelpers.findAndHookMethod(cls, "dispatchTouchEvent", MotionEvent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!FeatureFlags.enableCopyComment) return;

                            MotionEvent ev      = (MotionEvent) param.args[0];
                            Object      thisObj  = param.thisObject;
                            int         action   = ev.getActionMasked();

                            if (action == MotionEvent.ACTION_DOWN) {
                                View    root;
                                Context ctx;
                                try {
                                    if (thisObj instanceof Activity) {
                                        Activity a = (Activity) thisObj;
                                        root = a.getWindow().getDecorView();
                                        ctx  = a;
                                    } else {
                                        Dialog d = (Dialog) thisObj;
                                        Window w = d.getWindow();
                                        if (w == null) return;
                                        root = w.getDecorView();
                                        ctx  = activityCtx(d.getContext());
                                    }
                                } catch (Throwable t) { return; }

                                cancelTimer();
                                longPressFired = false;
                                downRawX       = ev.getRawX();
                                downRawY       = ev.getRawY();
                                pendingRoot    = root;
                                pendingCtx     = ctx;

                                final float   fx    = ev.getRawX();
                                final float   fy    = ev.getRawY();
                                final View    fRoot  = root;
                                final Context fCtx   = ctx;

                                pendingRunnable = () -> {
                                    pendingRunnable = null;
                                    longPressFired  = true;

                                    View commentContainer = findCommentContainer(fRoot, (int)fx, (int)fy);

                                    String text = null;
                                    if (commentContainer != null) {
                                        text = extractFromContainer(commentContainer);
                                    }

                                    // Fallback for IG versions where Litho renders text directly
                                    // onto the ComponentHost canvas — no tagged container exists,
                                    // but the ViewGroup itself carries a contentDescription.
                                    if (text == null || text.trim().length() < 2) {
                                        text = findCommentTextAtPoint(fRoot, (int)fx, (int)fy);
                                    }

                                    if (text == null || text.trim().length() < 2) {
                                        return;
                                    }

                                    text = text.trim();
                                    XposedBridge.log("(InstaEclipse | CopyComment): ✅ Comment → ["
                                        + text.substring(0, Math.min(60, text.length())) + "]");

                                    showCopyPopup(fCtx, text);
                                };
                                MAIN.postDelayed(pendingRunnable, LONG_PRESS_MS);

                            } else if (action == MotionEvent.ACTION_MOVE) {
                                float density = pendingCtx != null
                                        ? pendingCtx.getResources().getDisplayMetrics().density
                                        : 3.0f;
                                float slop = SLOP_DP * density;
                                if (Math.abs(ev.getRawX() - downRawX) > slop
                                        || Math.abs(ev.getRawY() - downRawY) > slop) {
                                    cancelTimer();
                                }

                            } else if (action == MotionEvent.ACTION_UP
                                    || action == MotionEvent.ACTION_CANCEL) {
                                if (!longPressFired) cancelTimer();
                                longPressFired = false;
                            }
                        }
                    });

            XposedBridge.log("(InstaEclipse | CopyComment): ✅ Hooked "
                    + cls.getSimpleName() + ".dispatchTouchEvent");
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | CopyComment): ❌ Hook ["
                    + cls.getSimpleName() + "] – " + t.getMessage());
        }
    }

    // ── Timer ─────────────────────────────────────────────────────────────────

    private static void cancelTimer() {
        if (pendingRunnable != null) {
            MAIN.removeCallbacks(pendingRunnable);
            pendingRunnable = null;
        }
        pendingRoot = null;
        pendingCtx  = null;
    }

    // ── Context helper ────────────────────────────────────────────────────────

    private static Context activityCtx(Context c) {
        if (c instanceof Activity) return c;
        Activity a = currentActivity;
        return (a != null) ? a : c;
    }

    // ── Comment container detection ───────────────────────────────────────────

    private static View findCommentContainer(View v, int sx, int sy) {
        if (v == null || v.getVisibility() != View.VISIBLE) return null;

        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int l = loc[0], t = loc[1];
        int r = l + v.getWidth(), b = t + v.getHeight();
        if (sx < l || sx > r || sy < t || sy > b) return null;

        Object tag = v.getTag();
        if (tag instanceof String && ((String) tag).startsWith(COMMENT_ROW_TAG)) {
            return v;
        }

        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                View found = findCommentContainer(vg.getChildAt(i), sx, sy);
                if (found != null) return found;
            }
        }

        return null;
    }

    /**
     * Fallback: walks every view that contains the touch point and checks if its
     * contentDescription parses as a comment. Used on IG versions where Litho
     * renders the text directly (no child TextView, no row tag).
     */
    private static String findCommentTextAtPoint(View v, int sx, int sy) {
        if (v == null || v.getVisibility() != View.VISIBLE) return null;

        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int l = loc[0], t = loc[1];
        int r = l + v.getWidth(), b = t + v.getHeight();
        if (sx < l || sx > r || sy < t || sy > b) return null;

        CharSequence cd = v.getContentDescription();
        if (cd != null && cd.length() > 0) {
            String parsed = parseCommentCd(cd.toString());
            if (parsed != null) return parsed;
        }

        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                String found = findCommentTextAtPoint(vg.getChildAt(i), sx, sy);
                if (found != null) return found;
            }
        }

        return null;
    }

    // ── Text extraction ───────────────────────────────────────────────────────

    private static String extractFromContainer(View container) {
        return collectBest(container, null);
    }

    private static String collectBest(View v, String best) {
        if (v == null) return best;

        // ── Litho ComponentHost: ask Litho's own text-content API ────────────
        // Litho renders text as drawables (TextDrawable) directly onto the
        // ComponentHost canvas — no child TextViews are created. The only way
        // to retrieve that text is via ComponentHost.getTextContent().getTextList().
        String componentClassName = v.getClass().getName();
        if (componentClassName.equals("com.facebook.litho.ComponentHost")) {
            String lithoText = extractLithoComponentHostText(v);
            if (lithoText != null && lithoText.length() > 3
                    && (best == null || lithoText.length() > best.length())) {
                best = lithoText;
            }
        }

        // ── contentDescription (older IG builds / Litho accessibility shim) ──
        CharSequence cd = v.getContentDescription();
        if (cd != null && cd.length() > 0) {
            String parsed = parseCommentCd(cd.toString());
            if (parsed != null && (best == null || parsed.length() > best.length())) {
                best = parsed;
            }
        }

        // ── Standard TextView ─────────────────────────────────────────────────
        if (v instanceof TextView && !(v instanceof EditText)) {
            CharSequence cs = ((TextView) v).getText();
            if (cs != null) {
                String s = cs.toString().trim();
                if (s.length() > 3 && (best == null || s.length() > best.length())) {
                    best = s;
                }
            }
        } else if (!(v instanceof EditText)) {
            // Reflection fallback for non-standard text view subclasses
            try {
                java.lang.reflect.Method getTextMethod = v.getClass().getMethod("getText");
                Object result = getTextMethod.invoke(v);
                if (result instanceof CharSequence) {
                    String s = result.toString().trim();
                    if (s.length() > 3 && (best == null || s.length() > best.length())) {
                        best = s;
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                best = collectBest(vg.getChildAt(i), best);
            }
        }

        return best;
    }

    /**
     * Uses Litho's own accessibility API to retrieve all text rendered by a
     * ComponentHost. Litho exposes ComponentHost.getTextContent() which returns
     * a TextContent object whose getTextList() yields every CharSequence that
     * Litho drew as a TextDrawable — invisible to the normal View hierarchy.
     *
     * Returns the longest non-trivial string found, or null.
     */
    private static String extractLithoComponentHostText(View componentHost) {
        try {
            // ComponentHost.getTextContent() → TextContent
            java.lang.reflect.Method getTextContent =
                    componentHost.getClass().getMethod("getTextContent");
            Object textContent = getTextContent.invoke(componentHost);
            if (textContent == null) return null;
            // Instagram's bundled Litho returns the List<CharSequence> directly.
            // Upstream Litho wraps it in a TextContent object with getTextList().
            java.util.List<?> texts;
            if (textContent instanceof java.util.List) {
                texts = (java.util.List<?>) textContent;
            } else {
                java.lang.reflect.Method getTextList =
                        textContent.getClass().getMethod("getTextList");
                Object list = getTextList.invoke(textContent);
                if (list == null) return null;
                texts = (java.util.List<?>) list;
            }
            // Litho mounts text in component-tree order: username → timestamp → comment.
            // Build an ordered candidate list, then return the last item (comment).
            // Single-item lists are UI labels (e.g. "Reply") — ignore them.
            java.util.List<String> candidates = new java.util.ArrayList<>();
            for (Object item : texts) {
                if (item == null) continue;
                String s;
                if (item instanceof CharSequence) {
                    s = charSequenceToString((CharSequence) item).trim();
                } else {
                    s = extractLongestCharSequenceField(item);
                }
                if (s == null || s.isEmpty() || s.startsWith("<cls>") || isTimestamp(s)) continue;
                candidates.add(s);
            }
            // Need at least username + comment; a lone string is a button label, not a comment.
            if (candidates.size() >= 2) return candidates.get(candidates.size() - 1);
            return null;

        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | CopyComment): ❌ Litho text extraction – " + t);
            return null;
        }
    }

    /** Returns true for short relative-time strings like "14h", "2d", "1w", "30m". */
    private static boolean isTimestamp(String s) {
        return s.matches("\\d+[smhdw]");
    }

    /**
     * For obfuscated wrapper objects that hold text in a CharSequence field rather
     * than implementing CharSequence themselves. Reflects over all declared
     * CharSequence fields and returns the longest value found.
     */
    private static String extractLongestCharSequenceField(Object obj) {
        String longest = null;
        for (java.lang.reflect.Field f : obj.getClass().getDeclaredFields()) {
            if (!CharSequence.class.isAssignableFrom(f.getType())) continue;
            try {
                f.setAccessible(true);
                Object val = f.get(obj);
                if (val == null) continue;
                String s = val instanceof String
                        ? (String) val
                        : charSequenceToString((CharSequence) val);
                s = s.trim();
                if (s.length() > 3 && !s.startsWith("<cls>")
                        && (longest == null || s.length() > longest.length())) {
                    longest = s;
                }
            } catch (Throwable ignored) {}
        }
        return longest;
    }

    /** Reads a CharSequence char-by-char — safe for obfuscated implementations
     *  that don't override toString(). */
    private static String charSequenceToString(CharSequence cs) {
        if (cs == null) return "";
        // String.valueOf() on a real String subclass works fine; for custom
        // implementations we use StringBuilder via the CharSequence interface.
        if (cs instanceof String) return (String) cs;
        StringBuilder sb = new StringBuilder(cs.length());
        for (int i = 0; i < cs.length(); i++) sb.append(cs.charAt(i));
        return sb.toString();
    }

    private static String parseCommentCd(String cd) {
        // Format used by newer Instagram (Litho-based):
        // "username said comment_text"
        // Username can't contain spaces, so the first " said " split is safe.
        int saidIdx = cd.indexOf(" said ");
        if (saidIdx > 0 && saidIdx < 60) {
            String body = cd.substring(saidIdx + 6).trim();
            if (body.length() >= 2) return body;
        }

        // Legacy format: "username, comment_text <localized_suffix>"
        // e.g. "john, nice photo commented" / "john, super foto hat kommentiert"
        String suffix = null;
        for (String s : COMMENT_SUFFIXES) {
            if (cd.endsWith(s)) { suffix = s; break; }
        }
        if (suffix != null) {
            String body = cd.substring(0, cd.length() - suffix.length()).trim();
            int commaSpace = body.indexOf(", ");
            if (commaSpace > 0 && commaSpace < 50 && !body.substring(0, commaSpace).contains("\n")) {
                body = body.substring(commaSpace + 2).trim();
            }
            if (body.length() >= 2) return body;
        }

        return null;
    }

    // ── Theme helpers ─────────────────────────────────────────────────────────

    private static boolean isDarkTheme(Context ctx) {
        return (ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Rounded rectangle drawable. */
    private static GradientDrawable roundRect(int color, float radiusDp, Context ctx) {
        float r = radiusDp * ctx.getResources().getDisplayMetrics().density;
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(r);
        return d;
    }

    /** Creates a styled pill action button. */
    private static Button makeButton(Context ctx, String label,
                                     int bgColor, int textColor, float dp) {
        Button btn = new Button(ctx);
        btn.setText(label);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setBackground(roundRect(bgColor, 14, ctx));
        btn.setAllCaps(false);
        btn.setPadding((int)(20 * dp), (int)(14 * dp), (int)(20 * dp), (int)(14 * dp));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int)(10 * dp);
        btn.setLayoutParams(lp);
        return btn;
    }

    // ── Main bottom-sheet popup ───────────────────────────────────────────────

    private static void showCopyPopup(final Context ctx, final String text) {
        MAIN.post(() -> {
            try {
                float dp = ctx.getResources().getDisplayMetrics().density;
                boolean dark = isDarkTheme(ctx);

                // Colors
                int sheetBg    = dark ? Color.parseColor("#1C1C1E") : Color.parseColor("#F2F2F7");
                int cardBg     = dark ? Color.parseColor("#2C2C2E") : Color.parseColor("#FFFFFF");
                int textPrim   = dark ? Color.WHITE                 : Color.parseColor("#1C1C1E");
                int textSec    = dark ? Color.parseColor("#AEAEB2") : Color.parseColor("#6C6C70");
                int accentBg   = Color.parseColor("#0A84FF");
                int secondBg   = dark ? Color.parseColor("#3A3A3C") : Color.parseColor("#E5E5EA");
                int secondText = dark ? Color.WHITE                 : Color.parseColor("#1C1C1E");
                int handleClr  = dark ? Color.parseColor("#48484A") : Color.parseColor("#C7C7CC");

                // Root sheet
                LinearLayout sheet = new LinearLayout(ctx);
                sheet.setOrientation(LinearLayout.VERTICAL);
                sheet.setBackground(roundRect(sheetBg, 20, ctx));
                int hPad = (int)(20 * dp);
                sheet.setPadding(hPad, (int)(12 * dp), hPad, (int)(28 * dp));

                // Drag handle
                View handle = new View(ctx);
                LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                        (int)(40 * dp), (int)(4 * dp));
                handleLp.gravity = Gravity.CENTER_HORIZONTAL;
                handleLp.bottomMargin = (int)(16 * dp);
                handle.setLayoutParams(handleLp);
                handle.setBackground(roundRect(handleClr, 2, ctx));
                sheet.addView(handle);

                // Title
                TextView titleTv = new TextView(ctx);
                titleTv.setText(I18n.t(ctx, R.string.ig_comment_copy_title));
                titleTv.setTextColor(textPrim);
                titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                titleTv.setTypeface(null, Typeface.BOLD);
                LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                titleLp.bottomMargin = (int)(14 * dp);
                titleTv.setLayoutParams(titleLp);
                sheet.addView(titleTv);

                // Comment preview card
                TextView commentTv = new TextView(ctx);
                commentTv.setText(text);
                commentTv.setTextColor(textPrim);
                commentTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                commentTv.setMaxLines(5);
                commentTv.setEllipsize(TextUtils.TruncateAt.END);
                int cardPad = (int)(14 * dp);
                commentTv.setPadding(cardPad, cardPad, cardPad, cardPad);
                commentTv.setBackground(roundRect(cardBg, 12, ctx));
                LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                cardLp.bottomMargin = (int)(6 * dp);
                commentTv.setLayoutParams(cardLp);
                sheet.addView(commentTv);

                // Buttons
                Dialog dialog = new Dialog(ctx);
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

                Button btnCopy = makeButton(ctx, I18n.t(ctx, R.string.ig_comment_copy_full), accentBg, Color.WHITE, dp);
                btnCopy.setOnClickListener(v -> {
                    dialog.dismiss();
                    copyToClipboard(ctx, text);
                });
                sheet.addView(btnCopy);

                Button btnSelect = makeButton(ctx, I18n.t(ctx, R.string.ig_comment_select_part), secondBg, secondText, dp);
                btnSelect.setOnClickListener(v -> {
                    dialog.dismiss();
                    showSelectDialog(ctx, text);
                });
                sheet.addView(btnSelect);

                // Wire dialog
                dialog.setContentView(sheet);
                Window w = dialog.getWindow();
                if (w != null) {
                    w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    w.setGravity(Gravity.BOTTOM);
                    w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.WRAP_CONTENT);
                    // Small margin from screen edges
                    WindowManager.LayoutParams wlp = w.getAttributes();
                    int margin = (int)(12 * dp);
                    wlp.x = margin;
                    wlp.y = margin;
                    w.setAttributes(wlp);
                }
                dialog.show();

            } catch (Throwable t) {
                XposedBridge.log("(InstaEclipse | CopyComment): ❌ Popup – " + t.getMessage());
            }
        });
    }

    // ── Select dialog ─────────────────────────────────────────────────────────

    private static void showSelectDialog(final Context ctx, final String text) {
        MAIN.post(() -> {
            try {
                float dp = ctx.getResources().getDisplayMetrics().density;
                boolean dark = isDarkTheme(ctx);

                int sheetBg  = dark ? Color.parseColor("#1C1C1E") : Color.parseColor("#F2F2F7");
                int cardBg   = dark ? Color.parseColor("#2C2C2E") : Color.parseColor("#FFFFFF");
                int textPrim = dark ? Color.WHITE                 : Color.parseColor("#1C1C1E");
                int accentBg = Color.parseColor("#0A84FF");
                int secondBg = dark ? Color.parseColor("#3A3A3C") : Color.parseColor("#E5E5EA");
                int handleClr= dark ? Color.parseColor("#48484A") : Color.parseColor("#C7C7CC");

                LinearLayout sheet = new LinearLayout(ctx);
                sheet.setOrientation(LinearLayout.VERTICAL);
                sheet.setBackground(roundRect(sheetBg, 20, ctx));
                int hPad = (int)(20 * dp);
                sheet.setPadding(hPad, (int)(12 * dp), hPad, (int)(28 * dp));

                // Drag handle
                View handle = new View(ctx);
                LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(
                        (int)(40 * dp), (int)(4 * dp));
                handleLp.gravity = Gravity.CENTER_HORIZONTAL;
                handleLp.bottomMargin = (int)(16 * dp);
                handle.setLayoutParams(handleLp);
                handle.setBackground(roundRect(handleClr, 2, ctx));
                sheet.addView(handle);

                // Title
                TextView titleTv = new TextView(ctx);
                titleTv.setText(I18n.t(ctx, R.string.ig_comment_select_title));
                titleTv.setTextColor(textPrim);
                titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
                titleTv.setTypeface(null, Typeface.BOLD);
                LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                titleLp.bottomMargin = (int)(14 * dp);
                titleTv.setLayoutParams(titleLp);
                sheet.addView(titleTv);

                // Selectable EditText in a rounded card
                EditText et = new EditText(ctx);
                et.setText(text);
                et.setTextColor(textPrim);
                et.setTextIsSelectable(true);
                et.setFocusableInTouchMode(true);
                et.setInputType(android.text.InputType.TYPE_NULL);
                et.setKeyListener(null);
                et.setHorizontallyScrolling(false);
                et.setMaxLines(8);
                et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                int cardPad = (int)(14 * dp);
                et.setPadding(cardPad, cardPad, cardPad, cardPad);
                et.setBackground(roundRect(cardBg, 12, ctx));
                et.setSelection(0, text.length());
                LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                etLp.bottomMargin = (int)(6 * dp);
                et.setLayoutParams(etLp);
                sheet.addView(et);

                Dialog dialog = new Dialog(ctx);
                dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

                Button btnCopySel = makeButton(ctx, I18n.t(ctx, R.string.ig_comment_copy_selected), accentBg, Color.WHITE, dp);
                btnCopySel.setOnClickListener(v -> {
                    int s = et.getSelectionStart(), e = et.getSelectionEnd();
                    String sel = (s >= 0 && e > s) ? text.substring(s, e) : text;
                    dialog.dismiss();
                    copyToClipboard(ctx, sel);
                });
                sheet.addView(btnCopySel);

                Button btnCopyAll = makeButton(ctx, I18n.t(ctx, R.string.ig_mention_copy_all), secondBg,
                        dark ? Color.WHITE : Color.parseColor("#1C1C1E"), dp);
                btnCopyAll.setOnClickListener(v -> {
                    dialog.dismiss();
                    copyToClipboard(ctx, text);
                });
                sheet.addView(btnCopyAll);

                dialog.setContentView(sheet);
                Window w = dialog.getWindow();
                if (w != null) {
                    w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                    w.setGravity(Gravity.BOTTOM);
                    w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                                WindowManager.LayoutParams.WRAP_CONTENT);
                    WindowManager.LayoutParams wlp = w.getAttributes();
                    int margin = (int)(12 * dp);
                    wlp.x = margin;
                    wlp.y = margin;
                    w.setAttributes(wlp);
                }
                dialog.show();
                et.requestFocus();

            } catch (Throwable t) {
                XposedBridge.log("(InstaEclipse | CopyComment): ❌ SelectDialog – " + t.getMessage());
            }
        });
    }

    // ── Clipboard ─────────────────────────────────────────────────────────────

    private static void copyToClipboard(final Context ctx, final String text) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("comment", text));
                MAIN.post(() ->
                    Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_comment_copied), Toast.LENGTH_SHORT).show());
            }
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse | CopyComment): ❌ Copy – " + t.getMessage());
        }
    }
}
