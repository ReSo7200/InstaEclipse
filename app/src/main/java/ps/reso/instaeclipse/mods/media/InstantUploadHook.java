package ps.reso.instaeclipse.mods.media;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.WindowInsets;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.log.ModuleLog;

/**
 * Upload an Instant from gallery (#199). The quicksnap camera is Jetpack Compose with a suspend,
 * obfuscated send pipeline, so we don't build the payload. Instead:
 *   1. Detect the camera is open (a QuickSnapCameraViewModel instance method fires) and inject a
 *      floating "Gallery" chip onto the hosting Activity's decor (the module's decor-overlay
 *      pattern — works regardless of the Compose content).
 *   2. The chip launches the system image picker.
 *   3. We receive the pick by hooking base Activity.onActivityResult (filtered by a unique request
 *      code, so IG's Activity subclassing doesn't matter), decode it to a Bitmap, and mark it
 *      pending.
 *   4. When the user taps the shutter, QuickSnapCameraViewModel.A02(Context,Bitmap,Bitmap,…) fires;
 *      we swap the captured Bitmap (arg1) for the gallery bitmap and let IG build+send the payload.
 */
public class InstantUploadHook {

    private static final String VM_CLASS =
            "com.instagram.quicksnap.camera.domain.QuickSnapCameraViewModel";
    private static final int PICK_REQUEST = 0x1E5A; // unique request code for our picker

    static volatile Bitmap pendingBitmap;
    private static volatile long pendingSetAt = 0;   // when pendingBitmap was armed (staleness guard)
    private static final long PENDING_TTL_MS = 5 * 60 * 1000;
    private static FrameLayout chip;           // the injected gallery button (main thread only)
    private static View preview;               // "tap shutter" hint shown once a photo is picked
    private static FrameLayout chipBtn;        // the round button inside chip
    private static View chipIcon;              // gallery glyph (idle state)
    private static ImageView chipThumb;        // picked photo, circle-cropped (ready state)
    private static View chipBadge;             // green check badge (ready state)
    private static Activity chipHost;          // the Activity we injected them on
    private static boolean lifecycleRegistered = false;
    // True while OUR gallery picker is on top of the camera. The camera screen pauses/stops while
    // the picker is open; the chip must survive that, otherwise backing out of the picker without
    // choosing anything leaves the camera with no Gallery chip until it is reopened.
    private static volatile boolean pickerOpen = false;

    public void install(ClassLoader cl) {
        hookSwap(cl);
        hookCameraOpen(cl);
        hookPickerResult();
    }

    // ── 4. The bitmap swap (verified: A02 arg1 = captured Bitmap) ──────────────
    private void hookSwap(ClassLoader cl) {
        try {
            Class<?> vm = XposedHelpers.findClass(VM_CLASS, cl);
            java.util.List<Method> targets = new java.util.ArrayList<>();
            // Legacy (<= 447): static (Context, Bitmap, Bitmap, VM, ...) capture handler.
            for (Method m : vm.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                int bmp = 0;
                for (Class<?> t : m.getParameterTypes()) if (t == Bitmap.class) bmp++;
                if (bmp >= 2) { targets.add(m); break; }
            }
            // IG 451: the second Bitmap became a holder object; the capture handlers are static
            // (Context, Bitmap, ..., QuickSnapCameraViewModel, ...) -> Object (suspend), called from
            // QuickSnapCameraViewModel$onCaptured$*. Hook every such handler; the first one to
            // run consumes pendingBitmap, so a capture is swapped exactly once.
            if (targets.isEmpty()) {
                for (Method m : vm.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != Object.class) continue;
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length < 4 || pt[0] != android.content.Context.class || pt[1] != Bitmap.class) continue;
                    boolean hasVm = false;
                    for (Class<?> t : pt) if (t == vm) hasVm = true;
                    if (hasVm) targets.add(m);
                }
            }
            if (targets.isEmpty()) { ModuleLog.line("(IE|InstantUpload) ⚠️ A02 not found"); return; }
            XC_MethodHook swap = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                    try {
                        if (!(p.args[1] instanceof Bitmap)) return; // suspend re-entry passes nulls
                        // Drop a stale pick so it can't hijack a much-later real capture.
                        if (pendingBitmap != null
                                && System.currentTimeMillis() - pendingSetAt > PENDING_TTL_MS) {
                            pendingBitmap = null;
                        }
                        if (FeatureFlags.uploadInstants && pendingBitmap != null) {
                            p.args[1] = pendingBitmap;
                            // Only on builds where arg2 is the second capture Bitmap (<= 447);
                            // on 451 it is a non-Bitmap holder and must be left untouched.
                            if (p.args.length > 2 && p.args[2] instanceof Bitmap)
                                p.args[2] = pendingBitmap.copy(pendingBitmap.getConfig(), false);
                            pendingBitmap = null;
                            FeedVideoDownloadHook.mainHandler.post(InstantUploadHook::removeChip);
                            ModuleLog.line("(IE|InstantUpload) ✅ swapped in gallery bitmap");
                        }
                    } catch (Throwable t) {
                        ModuleLog.line("(IE|InstantUpload) ❌ swap: " + t);
                    }
                }
            };
            for (Method m : targets) XposedBridge.hookMethod(m, swap);
            FeatureStatusTracker.setHooked("UploadInstants");
            ModuleLog.line("(IE|InstantUpload) ✅ capture handler hooked: " + targets.size());
        } catch (Throwable t) {
            ModuleLog.line("(IE|InstantUpload) ⚠️ swap install: " + t.getMessage());
        }
    }

    // ── 1. Camera-open detection → inject the Gallery chip ─────────────────────
    private void hookCameraOpen(ClassLoader cl) {
        registerLifecycle(cl);
        try {
            Class<?> vm = XposedHelpers.findClass(VM_CLASS, cl);
            // Hook the VM's no-arg void instance methods structurally (not a hardcoded letter like
            // "A15") — any one firing means the quicksnap camera VM is live, i.e. the camera is
            // on screen. Injection is idempotent + gated to the modal, so hooking several is safe.
            XC_MethodHook onActive = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                    if (!FeatureFlags.uploadInstants) return;
                    Activity act = currentActivity();
                    if (act != null) FeedVideoDownloadHook.mainHandler.post(() -> injectChip(act));
                }
            };
            int n = 0;
            for (Method m : vm.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 0) continue;
                if (m.getReturnType() != void.class) continue;
                if (m.isSynthetic() || m.isBridge()) continue;
                try { XposedBridge.hookMethod(m, onActive); n++; } catch (Throwable ignored) {}
            }
            ModuleLog.line("(IE|InstantUpload) ✅ camera-open hooked (" + n + " VM methods)");
        } catch (Throwable t) {
            ModuleLog.line("(IE|InstantUpload) ⚠️ camera-open: " + t.getMessage());
        }
    }

    /**
     * Remove the chip the moment its host screen is paused, so it never lingers on other menus
     * (the modal window/decor is reused across surfaces, so a left-over chip would show app-wide).
     */
    private void registerLifecycle(ClassLoader cl) {
        registerLifecycle(0);
    }

    /** Hooks may install before the Application exists; retry for up to ~30 s until it does. */
    private static void registerLifecycle(int attempt) {
        if (lifecycleRegistered) return;
        try {
            android.app.Application app = (android.app.Application)
                    android.app.AndroidAppHelper.currentApplication();
            if (app == null) {
                if (attempt < 30) FeedVideoDownloadHook.mainHandler.postDelayed(() -> registerLifecycle(attempt + 1), 1000);
                else ModuleLog.line("(IE|InstantUpload) ⚠️ no Application; chip lifecycle not registered");
                return;
            }
            app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityPaused(Activity a) {
                    if (a == watchHost && !pickerOpen) stopWatch();
                    if (a == chipHost && !pickerOpen) removeChip();
                }
                @Override public void onActivityStopped(Activity a) { if (a == chipHost && !pickerOpen) removeChip(); }
                @Override public void onActivityDestroyed(Activity a) {
                    if (a == watchHost) stopWatch();
                    if (a == chipHost) { pickerOpen = false; removeChip(); }
                }
                // If focus moves to any other screen, the chip must not follow — kill it. It is
                // re-injected only when the quicksnap camera signals open again (A15).
                @Override public void onActivityResumed(Activity a) {
                    if (chip != null && a != chipHost) removeChip();
                    // The quicksnap screens live in a modal; watch it so the chip appears whenever
                    // the camera page is on screen, however the user got there.
                    if (FeatureFlags.uploadInstants && a.getClass().getName().contains("ModalActivity")) startWatch(a);
                }
                @Override public void onActivityCreated(Activity a, android.os.Bundle b) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) {}
            });
            lifecycleRegistered = true;
            ModuleLog.line("(IE|InstantUpload) ✅ lifecycle registered");
        } catch (Throwable t) {
            ModuleLog.line("(IE|InstantUpload) ❌ lifecycle: " + t);
        }
    }

    /**
     * Ready state: the button shows the picked photo (circle-cropped, white ring, green check
     * badge) and a small "tap shutter" hint sits just above the shutter, never covering it.
     */
    private static void markChipReady(Activity act, Bitmap bmp) {
        try {
            View decor = act.getWindow().getDecorView();
            removePreview();
            if (decor instanceof ViewGroup) {
                TextView hint = new TextView(act);
                hint.setText(cleanLabel(I18n.t(act, R.string.ig_instant_upload_chip_ready)));
                hint.setTextColor(Color.WHITE);
                hint.setTextSize(13);
                hint.setSingleLine(true);
                hint.setGravity(Gravity.CENTER_VERTICAL);
                int padH = dp(act, 12), padV = dp(act, 6);
                hint.setPaddingRelative(dp(act, 8), padV, padH, padV);
                Drawable check = moduleDrawable(act, R.drawable.ic_check_circle);
                if (check != null) {
                    check = check.mutate();
                    check.setTint(GREEN);
                    int s = dp(act, 16);
                    check.setBounds(0, 0, s, s);
                    hint.setCompoundDrawablesRelative(check, null, null, null);
                    hint.setCompoundDrawablePadding(dp(act, 6));
                }
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.parseColor("#A6000000"));
                bg.setStroke(dp(act, 1), Color.parseColor("#26FFFFFF"));
                bg.setCornerRadius(dp(act, 16));
                hint.setBackground(bg);
                hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                // The shutter ring's top edge is ~188dp above the navigation bar; float just above it.
                lp.bottomMargin = navInset(act, decor) + dp(act, 198);
                hint.setLayoutParams(lp);
                hint.setVisibility(chip != null ? chip.getVisibility() : View.VISIBLE);
                hint.setAlpha(0f);
                ((ViewGroup) decor).addView(hint);
                hint.animate().alpha(1f).setDuration(180).start();
                preview = hint;
            }
            applyChipState(act, bmp);
            if (chip != null) chip.bringToFront();
        } catch (Throwable ignored) {}
    }

    private static final int GREEN = Color.parseColor("#30D158");

    /** Idle (bmp == null): gallery glyph on a dark disc. Ready: the picked photo + check badge. */
    private static void applyChipState(Activity act, Bitmap bmp) {
        if (chip == null || chipBtn == null) return;
        boolean ready = bmp != null;
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(Color.parseColor("#33FFFFFF"));       // same grey as IG's flash/flip discs
        disc.setStroke(dp(act, ready ? 2 : 1), ready ? Color.WHITE : Color.parseColor("#26FFFFFF"));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        chipBtn.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#40FFFFFF")), disc, mask));
        if (chipThumb != null) {
            chipThumb.setImageBitmap(ready ? bmp : null);
            chipThumb.setVisibility(ready ? View.VISIBLE : View.GONE);
        }
        if (chipIcon != null) chipIcon.setVisibility(ready ? View.GONE : View.VISIBLE);
        if (chipBadge != null) {
            chipBadge.setVisibility(ready ? View.VISIBLE : View.GONE);
            if (ready) {
                chipBadge.setScaleX(0.4f);
                chipBadge.setScaleY(0.4f);
                chipBadge.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(new android.view.animation.OvershootInterpolator()).start();
            }
        }
        chipBtn.setContentDescription(cleanLabel(I18n.t(act,
                ready ? R.string.ig_instant_upload_chip_ready : R.string.ig_instant_upload_chip)));
    }

    private static void removePreview() {
        try {
            if (preview != null && preview.getParent() instanceof ViewGroup)
                ((ViewGroup) preview.getParent()).removeView(preview);
        } catch (Throwable ignored) {}
        preview = null;
    }

    /** Strip the leading emoji/✓ the chip strings carry — the button draws real icons instead. */
    static String cleanLabel(String s) {
        if (s == null) return "";
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) break;
            i += Character.charCount(cp);
        }
        return i < s.length() ? s.substring(i).trim() : s.trim();
    }

    /** A drawable from the module APK (R.drawable ids only resolve against the module's Resources). */
    private static Drawable moduleDrawable(Activity act, int id) {
        try {
            return I18n.moduleResources(act).getDrawable(id, null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Height of the navigation bar overlapping the bottom of the (edge-to-edge) decor. */
    @SuppressLint({"DiscouragedApi", "InternalInsetResource"})
    private static int navInset(Activity act, View decor) {
        try {
            WindowInsets wi = decor.getRootWindowInsets();
            if (wi != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    return wi.getInsets(WindowInsets.Type.navigationBars()).bottom;
                return wi.getSystemWindowInsetBottom();
            }
        } catch (Throwable ignored) {}
        try {
            int id = act.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
            if (id != 0) return act.getResources().getDimensionPixelSize(id);
        } catch (Throwable ignored) {}
        return dp(act, 48);
    }

    private static void removeChip() {
        try {
            if (chip != null && chip.getParent() instanceof ViewGroup)
                ((ViewGroup) chip.getParent()).removeView(chip);
        } catch (Throwable ignored) {}
        try {
            if (preview != null && preview.getParent() instanceof ViewGroup)
                ((ViewGroup) preview.getParent()).removeView(preview);
        } catch (Throwable ignored) {}
        chip = null;
        preview = null;
        chipBtn = null;
        chipIcon = null;
        chipThumb = null;
        chipBadge = null;
        chipHost = null;
    }

    private static void injectChip(Activity act) {
        try {
            // Only inject on the camera's own modal window — never the long-lived MainTabActivity
            // (which would glue the chip app-wide). The quicksnap camera runs in a modal activity.
            if (!act.getClass().getName().contains("ModalActivity")) return;
            if (chip != null && chipHost == act) return; // already showing on this screen
            removeChip();                                 // clear any stale chip from a prior screen

            View decor = act.getWindow().getDecorView();
            if (!(decor instanceof ViewGroup)) return;

            // A round dark button in IG's own control style (like the flash/flip discs), sitting in
            // the "Friends" row directly under the flash button — clear of shutter, flash and pill.
            int btnSize = dp(act, 48), badgeSize = dp(act, 18), box = btnSize + dp(act, 6);
            FrameLayout c = new FrameLayout(act);
            c.setClipChildren(false);
            c.setClipToPadding(false);

            FrameLayout btn = new FrameLayout(act);
            btn.setLayoutParams(new FrameLayout.LayoutParams(btnSize, btnSize, Gravity.CENTER));
            btn.setClickable(true);
            btn.setFocusable(true);
            btn.setOnClickListener(v -> launchPicker(act));
            btn.setOnTouchListener((v, e) -> {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN)
                    v.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start();
                else if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
                return false; // let the click + ripple run
            });

            ImageView thumb = new ImageView(act);
            thumb.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int ring = dp(act, 2);
            thumb.setPadding(ring, ring, ring, ring);
            thumb.setCropToPadding(true);
            thumb.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override public void getOutline(View v, android.graphics.Outline o) {
                    int r = dp(act, 2);
                    o.setOval(r, r, v.getWidth() - r, v.getHeight() - r);
                }
            });
            thumb.setClipToOutline(true);
            thumb.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            thumb.setVisibility(View.GONE);
            btn.addView(thumb);

            View icon;
            Drawable glyph = moduleDrawable(act, R.drawable.ic_gallery);
            if (glyph != null) {
                ImageView iv = new ImageView(act);
                iv.setImageDrawable(glyph);
                iv.setImageTintList(ColorStateList.valueOf(Color.WHITE));
                int s = dp(act, 24);
                iv.setLayoutParams(new FrameLayout.LayoutParams(s, s, Gravity.CENTER));
                icon = iv;
            } else {                                    // module resources unavailable: emoji glyph
                TextView tv = new TextView(act);
                tv.setText("🖼");
                tv.setTextSize(18);
                tv.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
                icon = tv;
            }
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            btn.addView(icon);
            c.addView(btn);

            ImageView badge = new ImageView(act);
            Drawable check = moduleDrawable(act, R.drawable.ic_check_circle);
            if (check != null) badge.setImageDrawable(check);
            badge.setImageTintList(ColorStateList.valueOf(GREEN));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setShape(GradientDrawable.OVAL);
            badgeBg.setColor(Color.WHITE);                // shows through the check cut-out
            badgeBg.setStroke(dp(act, 1), Color.BLACK);
            badge.setBackground(badgeBg);
            badge.setLayoutParams(new FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP | Gravity.END));
            badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            badge.setVisibility(View.GONE);
            c.addView(badge);

            // IG centres its row on the screen: flash disc at centre - 106dp, "Friends" pill row
            // ~40dp above the navigation bar.
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(box, box);
            lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
            int width = decor.getWidth() > 0 ? decor.getWidth() : act.getResources().getDisplayMetrics().widthPixels;
            lp.leftMargin = Math.max(dp(act, 8), width / 2 - dp(act, 106) - box / 2);
            lp.bottomMargin = navInset(act, decor) + dp(act, 40) - box / 2;
            c.setLayoutParams(lp);

            c.setAlpha(0f);
            ((ViewGroup) decor).addView(c);
            c.animate().alpha(1f).setDuration(160).start();
            chip = c;
            chipBtn = btn;
            chipIcon = icon;
            chipThumb = thumb;
            chipBadge = badge;
            chipHost = act;
            applyChipState(act, null);
            watchCamera();
            ModuleLog.line("(IE|InstantUpload) ✅ gallery chip injected");
        } catch (Throwable t) {
            ModuleLog.line("(IE|InstantUpload) ❌ chip: " + t);
        }
    }

    // The camera and "Your instants" are pages of one horizontal pager in the same modal, so the
    // chip follows the camera preview (app:id/camera_view): shown only while that page is on screen.
    private static final long WATCH_MS = 300;
    private static int cameraViewId;
    private static Activity watchHost;          // the resumed modal being watched (main thread only)
    private static final Runnable WATCH = new Runnable() {
        @Override public void run() {
            Activity host = watchHost;
            if (host == null || !FeatureFlags.uploadInstants) return;
            boolean hasCam = hasCameraView(host);
            boolean on = hasCam && cameraOnScreen(host);
            if (on && (chip == null || chipHost != host)) injectChip(host);
            if (chip != null && chipHost == host) {
                int vis = on || pickerOpen ? View.VISIBLE : View.GONE;
                if (chip.getVisibility() != vis) chip.setVisibility(vis);
                if (preview != null && preview.getVisibility() != vis) preview.setVisibility(vis);
            }
            // Modals without a camera (comments, profiles…) are checked rarely.
            FeedVideoDownloadHook.mainHandler.postDelayed(this, hasCam ? WATCH_MS : 1000);
        }
    };

    private static void startWatch(Activity act) {
        watchHost = act;
        FeedVideoDownloadHook.mainHandler.removeCallbacks(WATCH);
        FeedVideoDownloadHook.mainHandler.post(WATCH);
    }

    private static void stopWatch() {
        watchHost = null;
        FeedVideoDownloadHook.mainHandler.removeCallbacks(WATCH);
    }

    private static void watchCamera() {
        if (chipHost != null && watchHost != chipHost) startWatch(chipHost);
    }

    @SuppressLint("DiscouragedApi")
    private static boolean cameraOnScreen(Activity act) {
        try {
            if (cameraViewId == 0) cameraViewId = act.getResources().getIdentifier("camera_view", "id", act.getPackageName());
            View cam = cameraViewId == 0 ? null : act.getWindow().getDecorView().findViewById(cameraViewId);
            if (cam == null) return true; // no preview to follow on this build: keep the chip
            if (!cam.isShown() || cam.getWidth() == 0) return false;
            int[] at = new int[2];
            cam.getLocationOnScreen(at);
            return Math.abs(at[0]) < cam.getWidth() / 2;
        } catch (Throwable t) {
            return true;
        }
    }

    @SuppressLint("DiscouragedApi")
    private static boolean hasCameraView(Activity act) {
        try {
            if (cameraViewId == 0) cameraViewId = act.getResources().getIdentifier("camera_view", "id", act.getPackageName());
            return cameraViewId != 0 && act.getWindow().getDecorView().findViewById(cameraViewId) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void launchPicker(Activity act) {
        try {
            pickerOpen = true;
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            act.startActivityForResult(Intent.createChooser(i,
                    I18n.t(act, R.string.ig_instant_upload_chip)), PICK_REQUEST);
        } catch (Throwable t) {
            pickerOpen = false;
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(act, I18n.t(act, R.string.ig_instant_upload_fail), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * Picker closed without a usable image (cancelled, nothing chosen, or undecodable): drop any
     * half-armed pick so the next shutter press is a normal capture, and put the Gallery chip back
     * in its default state on the camera screen.
     */
    private static void restoreChipAfterCancel(Activity act) {
        pendingBitmap = null;
        removePreview();
        if (chip == null || chipHost != act || chip.getParent() == null) {
            removeChip();
            injectChip(act);
            return;
        }
        applyChipState(act, null);
        chip.bringToFront();
    }

    // ── 3. Receive the pick (hook base Activity.onActivityResult) ──────────────
    private void hookPickerResult() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActivityResult",
                    int.class, int.class, Intent.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) { ps.reso.instaeclipse.utils.core.JitGuard.tick(p.method);
                            try {
                                int req = (int) p.args[0];
                                int res = (int) p.args[1];
                                if (req != PICK_REQUEST) return;
                                pickerOpen = false;
                                Activity act = (Activity) p.thisObject;
                                Uri uri = res == Activity.RESULT_OK && p.args[2] != null
                                        ? ((Intent) p.args[2]).getData() : null;
                                if (uri == null) {          // backed out / picked nothing
                                    restoreChipAfterCancel(act);
                                    return;
                                }
                                Bitmap bmp = decode(act, uri);
                                if (bmp == null) {
                                    restoreChipAfterCancel(act);
                                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(act, I18n.t(act, R.string.ig_instant_upload_fail),
                                            Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                pendingBitmap = bmp;
                                pendingSetAt = System.currentTimeMillis();
                                markChipReady(act, bmp);
                                ps.reso.instaeclipse.utils.toast.IeToast.makeText(act, I18n.t(act, R.string.ig_instant_upload_ready),
                                        Toast.LENGTH_LONG).show();
                            } catch (Throwable t) {
                                ModuleLog.line("(IE|InstantUpload) ❌ result: " + t);
                            }
                        }
                    });
            ModuleLog.line("(IE|InstantUpload) ✅ picker-result hooked");
        } catch (Throwable t) {
            ModuleLog.line("(IE|InstantUpload) ⚠️ picker-result: " + t.getMessage());
        }
    }

    // Cap the picked image's long edge. Full-resolution gallery photos (e.g. 4000×3000) can
    // intermittently choke IG's send/transcoder; a capture-sized bitmap is what the pipeline
    // expects, so downscaling makes the upload reliable.
    private static final int MAX_EDGE = 1440;

    /** Decodes a picked image Uri to a downscaled SOFTWARE bitmap (hardware bitmaps can't be copied). */
    private static Bitmap decode(Activity act, Uri uri) {
        try {
            Bitmap bmp;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                ImageDecoder.Source src = ImageDecoder.createSource(act.getContentResolver(), uri);
                bmp = ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setMutableRequired(false);
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    int longEdge = Math.max(w, h);
                    if (longEdge > MAX_EDGE) {
                        float scale = (float) MAX_EDGE / longEdge;
                        decoder.setTargetSize(Math.round(w * scale), Math.round(h * scale));
                    }
                });
            } else {
                bmp = MediaStore.Images.Media.getBitmap(act.getContentResolver(), uri);
                bmp = downscale(bmp);
            }
            return bmp;
        } catch (Throwable t) {
            ModuleLog.line("(IE|InstantUpload) ❌ decode: " + t);
            return null;
        }
    }

    private static Bitmap downscale(Bitmap in) {
        if (in == null) return null;
        int longEdge = Math.max(in.getWidth(), in.getHeight());
        if (longEdge <= MAX_EDGE) return in;
        float scale = (float) MAX_EDGE / longEdge;
        return Bitmap.createScaledBitmap(in,
                Math.round(in.getWidth() * scale), Math.round(in.getHeight() * scale), true);
    }

    private static int dp(Activity a, int v) {
        return Math.round(v * a.getResources().getDisplayMetrics().density);
    }

    /** Best-effort current resumed Activity via ActivityThread (A02/VM carry no Activity). */
    @SuppressWarnings("unchecked")
    static Activity currentActivity() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Object at = atClass.getMethod("currentActivityThread").invoke(null);
            java.lang.reflect.Field f = atClass.getDeclaredField("mActivities");
            f.setAccessible(true);
            java.util.Map<Object, Object> acts = (java.util.Map<Object, Object>) f.get(at);
            if (acts == null) return null;
            for (Object record : acts.values()) {
                Class<?> rc = record.getClass();
                java.lang.reflect.Field paused = rc.getDeclaredField("paused");
                paused.setAccessible(true);
                if (Boolean.FALSE.equals(paused.get(record))) {
                    java.lang.reflect.Field af = rc.getDeclaredField("activity");
                    af.setAccessible(true);
                    return (Activity) af.get(record);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
