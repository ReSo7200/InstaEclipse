package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Lock DMs (GitHub #182): gate the Direct inbox behind the module passcode. When the inbox becomes
 * visible and the session isn't unlocked yet, a full-screen overlay covers the content until the
 * correct passcode is entered. Locks the WHOLE inbox (not individual chats). The passcode is stored
 * as a SHA-256 hash (FeatureFlags.lockDirectPasscode). Gated on FeatureFlags.lockDirectMessages.
 *
 * Inbox is detected by the stable view id `direct_search_bar_container` (fallback
 * `direct_inbox_null_state`); the thread screen is excluded via `direct_thread_header`.
 */
public class LockDirectMessagesHook {

    private static final String OVERLAY_TAG = "ie_dm_lock";
    private static boolean unlockedThisSession = false;
    private static boolean appUnlockedThisSession = false;
    private static boolean appLockLifecycleRegistered = false;
    private static int appStartedActivities = 0;
    private static int searchBarId, nullStateId, threadHeaderId;
    private static long lastGateLog = 0;

    public void install(ClassLoader classLoader) {
        // ModalActivity (a thread/inbox opened full-screen) fires onResume. The DM inbox TAB lives
        // inside InstagramMainActivity, whose onCreate is obfuscated/inherited (a literal hook
        // fails) — so main is covered from UIHookManager.setupHooks via watchActivity() instead.
        XC_MethodHook start = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                watchActivity((Activity) param.thisObject);
            }
        };
        try {
            XposedHelpers.findAndHookMethod("com.instagram.modal.ModalActivity",
                    classLoader, "onResume", start);
        } catch (Throwable t) { ModuleLog.line("(IE|LockDMs) ⚠️ modal onResume: " + t.getMessage()); }
        // Reflect status at startup (the gate only runs when the inbox opens, so mark it hooked
        // now when armed — otherwise the load toast shows a ❌ even though it works).
        if (FeatureFlags.lockDirectMessages) FeatureStatusTracker.setHooked("LockDirectMessages");
        ModuleLog.line("(IE|LockDMs) ✅ installed");
    }

    /** Entry point usable from any activity hook (e.g. UIHookManager.setupHooks for the main tab). */
    public static void watchActivity(final Activity a) {
        if (a == null) return;
        try { a.runOnUiThread(() -> watch(a)); } catch (Throwable ignored) {}
    }

    private static final java.util.Set<View> watchedDecors =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    @SuppressLint("DiscouragedApi")
    private static void ensureIds(Activity a) {
        if (searchBarId != 0 || nullStateId != 0) return;
        String pkg = a.getPackageName();
        android.content.res.Resources r = a.getResources();
        // The DM inbox (a tab in InstagramMainActivity) is identified by these ids (confirmed via
        // live view dump); the old direct_search_bar_container/null_state don't exist here.
        searchBarId    = r.getIdentifier("direct_inbox_action_bar", "id", pkg);
        nullStateId    = r.getIdentifier("inbox_refreshable_thread_list_recyclerview", "id", pkg);
        threadHeaderId = r.getIdentifier("direct_thread_header", "id", pkg);
        ModuleLog.probe("(IE|LockDMs|PROBE) ids searchBar=" + searchBarId + " nullState=" + nullStateId
                + " threadHeader=" + threadHeaderId);
    }

    private static void watch(final Activity a) {
        try {
            registerAppLockLifecycle(a);
            // Whole-app lock (#182 extension): gate the entire app the moment any screen shows while
            // locked — takes priority over the DM-only gate. Same passcode as Lock DMs.
            if (FeatureFlags.lockWholeApp && !appUnlockedThisSession
                    && FeatureFlags.lockDirectPasscode != null && !FeatureFlags.lockDirectPasscode.isEmpty()) {
                showOverlay(a, true);
            }
            ensureIds(a);
            ModuleLog.probe("(IE|LockDMs|PROBE) armed flag=" + FeatureFlags.lockDirectMessages
                    + " passLen=" + (FeatureFlags.lockDirectPasscode == null ? -1 : FeatureFlags.lockDirectPasscode.length())
                    + " unlocked=" + unlockedThisSession);
            final View decor = a.getWindow().getDecorView();
            if (!watchedDecors.add(decor)) { gateIfInbox(a); return; } // already watching this window
            // Persistent listener: the inbox tab appears/disappears without an activity resume, so
            // we re-check on every layout while the feature is armed.
            decor.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (!FeatureFlags.lockDirectMessages
                        || FeatureFlags.lockDirectPasscode == null || FeatureFlags.lockDirectPasscode.isEmpty()) return;
                // "Always ask": once unlocked, re-lock as soon as the inbox is no longer on screen,
                // so returning to DMs prompts again (without needing to close Instagram).
                if (unlockedThisSession) {
                    if (FeatureFlags.lockDirectAlways && !isInbox(a)) unlockedThisSession = false;
                    return;
                }
                gateIfInbox(a);
            });
            gateIfInbox(a);
        } catch (Throwable t) {
            ModuleLog.line("(IE|LockDMs) ⚠️ watch: " + t.getMessage());
        }
    }

    /**
     * Whole-app lock lifecycle: re-lock whenever the app returns from the background, and gate any
     * screen that resumes while locked. Registered once per process. Uses the same passcode as DMs.
     */
    private static void registerAppLockLifecycle(Activity a) {
        if (appLockLifecycleRegistered) return;
        try {
            android.app.Application app = a.getApplication();
            if (app == null) return;
            app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityResumed(Activity act) {
                    if (!FeatureFlags.lockWholeApp || appUnlockedThisSession) return;
                    if (FeatureFlags.lockDirectPasscode == null || FeatureFlags.lockDirectPasscode.isEmpty()) return;
                    try { act.runOnUiThread(() -> showOverlay(act, true)); } catch (Throwable ignored) {}
                }
                @Override public void onActivityStarted(Activity act) { appStartedActivities++; }
                @Override public void onActivityStopped(Activity act) {
                    appStartedActivities--;
                    if (appStartedActivities <= 0) { appStartedActivities = 0; appUnlockedThisSession = false; }
                }
                @Override public void onActivityCreated(Activity act, android.os.Bundle b) {}
                @Override public void onActivityPaused(Activity act) {}
                @Override public void onActivitySaveInstanceState(Activity act, android.os.Bundle b) {}
                @Override public void onActivityDestroyed(Activity act) {}
            });
            appLockLifecycleRegistered = true;
        } catch (Throwable ignored) {}
    }

    /** True when the DM inbox is on screen (and not a thread). */
    private static boolean isInbox(Activity a) {
        if (threadHeaderId != 0 && a.findViewById(threadHeaderId) != null) return false;
        return (searchBarId != 0 && a.findViewById(searchBarId) != null)
                || (nullStateId != 0 && a.findViewById(nullStateId) != null);
    }

    /** If the DM inbox is on screen (and not a thread), show the passcode overlay. Returns true if
     *  the inbox was detected (so the layout listener can detach). */
    private static boolean gateIfInbox(Activity a) {
        if (!FeatureFlags.lockDirectMessages || unlockedThisSession
                || FeatureFlags.lockDirectPasscode == null || FeatureFlags.lockDirectPasscode.isEmpty()) return false;
        boolean onThread = threadHeaderId != 0 && a.findViewById(threadHeaderId) != null;
        if (onThread) return false;
        boolean sb = searchBarId != 0 && a.findViewById(searchBarId) != null;
        boolean ns = nullStateId != 0 && a.findViewById(nullStateId) != null;
        boolean onInbox = sb || ns;
        long now = System.currentTimeMillis();
        if (now - lastGateLog > 1500) {
            lastGateLog = now;
            ModuleLog.probe("(IE|LockDMs|PROBE) gate act=" + a.getClass().getSimpleName()
                    + " onThread=" + onThread + " searchBar=" + sb + " nullState=" + ns);
        }
        if (!onInbox) return false;
        showOverlay(a);
        return true;
    }

    private static void showOverlay(Activity a) { showOverlay(a, false); }

    /**
     * @param wholeApp when true this gates the entire app (title + which session flag it clears);
     *                 when false it gates just the DM inbox.
     */
    private static void showOverlay(Activity a, boolean wholeApp) {
        final ViewGroup content = a.findViewById(android.R.id.content);
        if (content == null || content.findViewWithTag(OVERLAY_TAG) != null) return;

        // Let the window resize when the keyboard shows so the centered card floats ABOVE it
        // (previously the Unlock button was hidden behind the keyboard).
        try {
            a.getWindow().setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        } catch (Throwable ignored) {}

        FrameLayout overlay = new FrameLayout(a);
        overlay.setTag(OVERLAY_TAG);
        overlay.setClickable(true);
        overlay.setBackgroundColor(Color.parseColor("#FA121214"));
        overlay.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));

        // Scrollable so the card is always reachable even on short screens with the keyboard up.
        android.widget.ScrollView scroller = new android.widget.ScrollView(a);
        FrameLayout.LayoutParams svLp = new FrameLayout.LayoutParams(-1, -1);
        scroller.setLayoutParams(svLp);
        scroller.setFillViewport(true);

        // Rounded card holding the prompt.
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(a, 28);
        col.setPadding(pad, pad, pad, pad);
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(Color.parseColor("#1E1E20"));
        cardBg.setCornerRadius(dp(a, 22));
        col.setBackground(cardBg);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(dp(a, 300), -2);
        clp.gravity = Gravity.CENTER;
        clp.topMargin = dp(a, 40);
        clp.bottomMargin = dp(a, 40);
        col.setLayoutParams(clp);

        TextView lock = new TextView(a);
        lock.setText("🔒");
        lock.setTextSize(34);
        lock.setGravity(Gravity.CENTER);
        lock.setPadding(0, 0, 0, dp(a, 8));

        TextView title = new TextView(a);
        title.setText(wholeApp ? "Enter passcode to open Instagram" : "Enter passcode to open DMs");
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, dp(a, 18));

        final EditText code = new EditText(a);
        code.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        code.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        code.setSingleLine(true);
        code.setHint("Passcode");
        code.setTextColor(Color.WHITE);
        code.setHintTextColor(Color.parseColor("#8E8E93"));
        code.setGravity(Gravity.CENTER);
        code.setTextSize(20);
        code.setLetterSpacing(0.15f);
        // Size the box to a few digits and centre it — no wide empty passcode bar.
        code.setEms(6);
        code.setMinWidth(0);
        code.setMinimumWidth(0);
        android.graphics.drawable.GradientDrawable inBg = new android.graphics.drawable.GradientDrawable();
        inBg.setColor(Color.parseColor("#2C2C2E"));
        inBg.setCornerRadius(dp(a, 12));
        code.setBackground(inBg);
        int ip = dp(a, 12);
        code.setPadding(ip, dp(a, 10), ip, dp(a, 10));
        LinearLayout.LayoutParams codeLp = new LinearLayout.LayoutParams(-2, -2); // wrap: hugs the digits
        codeLp.gravity = Gravity.CENTER_HORIZONTAL;
        code.setLayoutParams(codeLp);

        final TextView error = new TextView(a);
        error.setTextColor(Color.parseColor("#FF6B6B"));
        error.setTextSize(13);
        error.setGravity(Gravity.CENTER);
        error.setPadding(0, dp(a, 8), 0, 0);
        error.setVisibility(View.GONE);

        Button unlock = new Button(a);
        unlock.setText("Unlock");
        unlock.setAllCaps(false);
        unlock.setTextColor(Color.WHITE);
        unlock.setTextSize(16);
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setColor(Color.parseColor("#0A84FF"));
        btnBg.setCornerRadius(dp(a, 12));
        unlock.setBackground(btnBg);
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(-1, dp(a, 46));
        btnLp.topMargin = dp(a, 16);
        unlock.setLayoutParams(btnLp);

        final Runnable attempt = () -> {
            if (hashPass(code.getText().toString()).equals(FeatureFlags.lockDirectPasscode)) {
                if (wholeApp) appUnlockedThisSession = true; else unlockedThisSession = true;
                hideKeyboard(a, code);
                content.removeView(overlay);
            } else {
                code.setText("");
                error.setText("Wrong passcode");
                error.setVisibility(View.VISIBLE);
            }
        };
        unlock.setOnClickListener(v -> attempt.run());
        // Keyboard "Done" also submits — so the unlock never depends on a button the keyboard hides.
        code.setOnEditorActionListener((v, actionId, ev) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { attempt.run(); return true; }
            return false;
        });

        col.addView(lock);
        col.addView(title);
        col.addView(code);
        col.addView(error);
        col.addView(unlock);
        // Wrapper fills the viewport (via fillViewport) and centers the card — so the card itself
        // stays at its content height instead of the ScrollView stretching it to full screen.
        FrameLayout inner = new FrameLayout(a);
        inner.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        inner.addView(col);
        scroller.addView(inner);
        overlay.addView(scroller);
        content.addView(overlay);
        FeatureStatusTracker.setHooked(wholeApp ? "LockWholeApp" : "LockDirectMessages");

        // Biometric unlock: if the device has an enrolled fingerprint/face and the user hasn't
        // disabled it, prompt automatically. Success unlocks exactly like a correct passcode; the
        // passcode field stays as the fallback (the prompt's negative button just dismisses it).
        if (FeatureFlags.lockUseFingerprint && canAuthenticate(a)) {
            promptBiometric(a, () -> {
                if (wholeApp) appUnlockedThisSession = true; else unlockedThisSession = true;
                hideKeyboard(a, code);
                try { content.removeView(overlay); } catch (Throwable ignored) {}
            });
        }
    }

    /** True when the device has biometric hardware with something enrolled. */
    private static boolean canAuthenticate(Activity a) {
        try {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return false;
            android.hardware.biometrics.BiometricManager bm =
                    (android.hardware.biometrics.BiometricManager) a.getSystemService(Activity.BIOMETRIC_SERVICE);
            if (bm == null) return false;
            int res;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                // No-arg canAuthenticate() is deprecated on R+ and can misreport; pass authenticators.
                res = bm.canAuthenticate(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK);
            } else {
                res = bm.canAuthenticate();
            }
            return res == android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Shows the system biometric prompt; runs onSuccess on the UI thread when auth succeeds. */
    private static void promptBiometric(Activity a, Runnable onSuccess) {
        try {
            // Use plain literals — our module's R.string ids aren't in Instagram's resource table,
            // so a.getString(R.string...) throws Resources.NotFoundException in IG's process.
            android.hardware.biometrics.BiometricPrompt prompt =
                    new android.hardware.biometrics.BiometricPrompt.Builder(a)
                            .setTitle("Unlock Instagram")
                            .setDescription("Unlock with your fingerprint")
                            .setNegativeButton("Use passcode", a.getMainExecutor(), (d, w) -> {})
                            .build();
            prompt.authenticate(new android.os.CancellationSignal(), a.getMainExecutor(),
                    new android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                        @Override public void onAuthenticationSucceeded(
                                android.hardware.biometrics.BiometricPrompt.AuthenticationResult result) {
                            a.runOnUiThread(onSuccess);
                        }
                    });
        } catch (Throwable t) {
            ModuleLog.line("(IE|LockDMs) ⚠️ biometric: " + t.getMessage());
        }
    }

    private static int dp(Activity a, int v) { return Math.round(v * a.getResources().getDisplayMetrics().density); }

    /** Dismiss the soft keyboard after a successful unlock so it doesn't linger over the content. */
    private static void hideKeyboard(Activity a, View focus) {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) a.getSystemService(Activity.INPUT_METHOD_SERVICE);
            if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    /** SHA-256 hex of the input. */
    public static String sha256(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return s; }
    }

    /** Salted hash of a passcode: sha256(salt + passcode). Legacy passcodes have salt="" so
     *  sha256(""+pass) == sha256(pass) and keep verifying without a forced reset. */
    public static String hashPass(String passcode) {
        String salt = FeatureFlags.lockDirectSalt == null ? "" : FeatureFlags.lockDirectSalt;
        return sha256(salt + passcode);
    }

    /** Generate + store a fresh random salt (call right before hashing a NEW passcode). */
    public static void newSalt() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        FeatureFlags.lockDirectSalt = sb.toString();
    }
}
