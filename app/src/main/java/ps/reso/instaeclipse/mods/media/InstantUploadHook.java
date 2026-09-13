package ps.reso.instaeclipse.mods.media;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.drawable.GradientDrawable;
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
    private static TextView chip;              // the injected gallery chip (main thread only)
    private static ImageView preview;          // full-screen preview of the picked image
    private static Activity chipHost;          // the Activity we injected them on
    private static boolean lifecycleRegistered = false;

    public void install(ClassLoader cl) {
        hookSwap(cl);
        hookCameraOpen(cl);
        hookPickerResult();
    }

    // ── 4. The bitmap swap (verified: A02 arg1 = captured Bitmap) ──────────────
    private void hookSwap(ClassLoader cl) {
        try {
            Class<?> vm = XposedHelpers.findClass(VM_CLASS, cl);
            Method a02 = null;
            for (Method m : vm.getDeclaredMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                int bmp = 0;
                for (Class<?> t : m.getParameterTypes()) if (t == Bitmap.class) bmp++;
                if (bmp >= 2) { a02 = m; break; }
            }
            if (a02 == null) { ModuleLog.line("(IE|InstantUpload) ⚠️ A02 not found"); return; }
            XposedBridge.hookMethod(a02, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    try {
                        if (!(p.args[1] instanceof Bitmap)) return; // suspend re-entry passes nulls
                        // Drop a stale pick so it can't hijack a much-later real capture.
                        if (pendingBitmap != null
                                && System.currentTimeMillis() - pendingSetAt > PENDING_TTL_MS) {
                            pendingBitmap = null;
                        }
                        if (FeatureFlags.uploadInstants && pendingBitmap != null) {
                            p.args[1] = pendingBitmap;
                            if (p.args[2] != null)
                                p.args[2] = pendingBitmap.copy(pendingBitmap.getConfig(), false);
                            pendingBitmap = null;
                            FeedVideoDownloadHook.mainHandler.post(InstantUploadHook::removeChip);
                            ModuleLog.line("(IE|InstantUpload) ✅ swapped in gallery bitmap");
                        }
                    } catch (Throwable t) {
                        ModuleLog.line("(IE|InstantUpload) ❌ swap: " + t);
                    }
                }
            });
            FeatureStatusTracker.setHooked("UploadInstants");
            ModuleLog.line("(IE|InstantUpload) ✅ A02 hooked");
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
                @Override protected void afterHookedMethod(MethodHookParam p) {
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
        if (lifecycleRegistered) return;
        try {
            android.app.Application app = (android.app.Application)
                    android.app.AndroidAppHelper.currentApplication();
            if (app == null) return;
            app.registerActivityLifecycleCallbacks(new android.app.Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityPaused(Activity a) { if (a == chipHost) removeChip(); }
                @Override public void onActivityStopped(Activity a) { if (a == chipHost) removeChip(); }
                @Override public void onActivityDestroyed(Activity a) { if (a == chipHost) removeChip(); }
                // If focus moves to any other screen, the chip must not follow — kill it. It is
                // re-injected only when the quicksnap camera signals open again (A15).
                @Override public void onActivityResumed(Activity a) { if (chip != null && a != chipHost) removeChip(); }
                @Override public void onActivityCreated(Activity a, android.os.Bundle b) {}
                @Override public void onActivityStarted(Activity a) {}
                @Override public void onActivitySaveInstanceState(Activity a, android.os.Bundle b) {}
            });
            lifecycleRegistered = true;
        } catch (Throwable ignored) {}
    }

    /**
     * Show a SMALL thumbnail of the picked image just above the chip (so the shutter stays fully
     * visible and tappable — a full-screen preview would cover IG's shutter and block sending),
     * and turn the chip green with a ✓ prompting the shutter tap.
     */
    private static void markChipReady(Activity act, Bitmap bmp) {
        try {
            View decor = act.getWindow().getDecorView();
            if (decor instanceof ViewGroup && preview == null) {
                ImageView iv = new ImageView(act);
                iv.setImageBitmap(bmp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                int side = dp(act, 84);
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(side, side);
                lp.gravity = Gravity.BOTTOM | Gravity.START;
                lp.leftMargin = dp(act, 20);
                lp.bottomMargin = dp(act, 172); // sits above the chip, clear of the centre shutter
                iv.setLayoutParams(lp);
                GradientDrawable frame = new GradientDrawable();
                frame.setColor(Color.BLACK);
                frame.setCornerRadius(dp(act, 12));
                frame.setStroke(dp(act, 2), Color.parseColor("#30D158"));
                iv.setBackground(frame);
                iv.setClipToOutline(true);
                ((ViewGroup) decor).addView(iv);
                preview = iv;
            }
            if (chip != null) {
                chip.setText(I18n.t(act, R.string.ig_instant_upload_chip_ready));
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.parseColor("#CC30D158"));
                bg.setCornerRadius(dp(act, 22));
                chip.setBackground(bg);
                chip.bringToFront();
            }
        } catch (Throwable ignored) {}
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

            TextView c = new TextView(act);
            c.setText(I18n.t(act, R.string.ig_instant_upload_chip));
            c.setTextColor(Color.WHITE);
            c.setTextSize(14);
            int padH = dp(act, 16), padV = dp(act, 10);
            c.setPadding(padH, padV, padH, padV);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#CC0A84FF"));
            bg.setCornerRadius(dp(act, 22));
            c.setBackground(bg);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.START;
            lp.leftMargin = dp(act, 20);
            lp.bottomMargin = dp(act, 120);
            c.setLayoutParams(lp);
            c.setOnClickListener(v -> launchPicker(act));

            ((ViewGroup) decor).addView(c);
            chip = c;
            chipHost = act;
            ModuleLog.line("(IE|InstantUpload) ✅ gallery chip injected");
        } catch (Throwable t) {
            ModuleLog.line("(IE|InstantUpload) ❌ chip: " + t);
        }
    }

    private static void launchPicker(Activity act) {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            act.startActivityForResult(Intent.createChooser(i,
                    I18n.t(act, R.string.ig_instant_upload_chip)), PICK_REQUEST);
        } catch (Throwable t) {
            Toast.makeText(act, I18n.t(act, R.string.ig_instant_upload_fail), Toast.LENGTH_SHORT).show();
        }
    }

    // ── 3. Receive the pick (hook base Activity.onActivityResult) ──────────────
    private void hookPickerResult() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActivityResult",
                    int.class, int.class, Intent.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam p) {
                            try {
                                int req = (int) p.args[0];
                                int res = (int) p.args[1];
                                if (req != PICK_REQUEST) return;
                                if (res != Activity.RESULT_OK || p.args[2] == null) return;
                                Uri uri = ((Intent) p.args[2]).getData();
                                if (uri == null) return;
                                Activity act = (Activity) p.thisObject;
                                Bitmap bmp = decode(act, uri);
                                if (bmp == null) {
                                    Toast.makeText(act, I18n.t(act, R.string.ig_instant_upload_fail),
                                            Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                pendingBitmap = bmp;
                                pendingSetAt = System.currentTimeMillis();
                                markChipReady(act, bmp);
                                Toast.makeText(act, I18n.t(act, R.string.ig_instant_upload_ready),
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
