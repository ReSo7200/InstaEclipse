package ps.reso.instaeclipse.mods.ui;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.DexKitCache;
import ps.reso.instaeclipse.utils.core.DexKitQueryCache;
import ps.reso.instaeclipse.utils.core.SafeReflect;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.feature.FeatureStatusTracker;
import ps.reso.instaeclipse.utils.i18n.I18n;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.xposedcompat.XC_MethodHook;
import ps.reso.instaeclipse.xposedcompat.XposedBridge;

/**
 * Profile ⋮ menu extras: Copy username, Copy bio, Download all posts (profileMenuTools) and
 * Analyze profile (profileAnalyzer, see ProfileAnalyzer).
 *
 * <p>The menu is a plain PopupWindow whose content holds Instagram's context menu
 * (context_menu_start_items). When one opens over a profile, three rows are added, cloned from
 * Instagram's own row so they match it. The username comes from the profile header; the bio from
 * the profile's User model (its "biography" getter, found by the field name's hash like the
 * module's other User getters).
 */
public final class ProfileMenuHook {

    private static final String TAG = "(IE|ProfileMenu) ";
    private static final String USER = "com.instagram.user.model.User";
    private static final String ROW_TAG = "ie_profile_menu_row";
    private static Method bioGetter;

    public void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            bioGetter = DexKitCache.isCacheValid() ? DexKitCache.loadMethod("ProfileBioGetter", cl) : null;
            if (bioGetter == null) {
                for (MethodData md : DexKitQueryCache.findMethod(bridge, FindMethod.create().matcher(MethodMatcher.create()
                        .declaredClass(USER).paramCount(0).returnType("java.lang.String")
                        .usingNumbers("biography".hashCode())))) {
                    bioGetter = md.getMethodInstance(cl);
                    bioGetter.setAccessible(true);
                    DexKitCache.saveMethod("ProfileBioGetter", bioGetter);
                    break;
                }
            }
            XC_MethodHook onShow = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) { ps.reso.instaeclipse.utils.core.JitGuard.tick(param.method);
                    if ((FeatureFlags.profileMenuTools || FeatureFlags.profileAnalyzer) && param.thisObject instanceof PopupWindow pw) inject(pw);
                }
            };
            XposedBridge.hookAllMethods(PopupWindow.class, "showAsDropDown", onShow);
            XposedBridge.hookAllMethods(PopupWindow.class, "showAtLocation", onShow);
            FeatureStatusTracker.setHooked("ProfileMenuTools");
            ModuleLog.line(TAG + "✅ installed, bio getter=" + (bioGetter != null ? bioGetter.getName() : "none"));
        } catch (Throwable t) {
            ModuleLog.line(TAG + "❌ " + t);
        }
    }

    @SuppressLint("DiscouragedApi")
    private static void inject(PopupWindow pw) {
        View content = pw.getContentView();
        if (content == null) return;
        Activity a = activityOf(content.getContext());
        String username = profileUsername(a);
        if (username == null) return; // not a profile's menu
        inject(pw, content, a, username, 0);
    }

    /**
     * The menu is a RecyclerView (context_menu_options_list) inside context_menu; its rows exist
     * only after its first layout, so ours go in, above it, once it has laid out.
     */
    @SuppressLint("DiscouragedApi")
    private static void inject(PopupWindow pw, View content, Activity a, String username, int attempt) {
        try {
            if (!pw.isShowing() && attempt > 0) return;
            Context ctx = content.getContext();
            String pkg = ctx.getPackageName();
            android.content.res.Resources res = ctx.getResources();
            View options = content.findViewById(res.getIdentifier("context_menu_options_list", "id", pkg));
            if (!(options instanceof ViewGroup scroller) || !(scroller.getParent() instanceof LinearLayout list)) return;
            if (list.getOrientation() != LinearLayout.VERTICAL) {
                // context_menu lays its parts side by side; here the list is its only shown part
                for (int i = 0; i < list.getChildCount(); i++)
                    if (list.getChildAt(i) != scroller && list.getChildAt(i).getVisibility() == View.VISIBLE) return;
                list.setOrientation(LinearLayout.VERTICAL);
            }
            View template = scroller.getChildCount() > 0 && scroller.getChildAt(0).getHeight() > 0 ? scroller.getChildAt(0) : null;
            if (list.findViewWithTag(ROW_TAG) != null) return;
            if (template == null) {
                if (attempt < 20) content.postDelayed(() -> inject(pw, content, a, username, attempt + 1), 50);
                return;
            }

            // Our rows and Instagram's list go into one ScrollView, so they scroll together.
            LinearLayout column = new LinearLayout(ctx);
            column.setOrientation(LinearLayout.VERTICAL);
            if (FeatureFlags.profileMenuTools) {
                column.addView(row(template, R.drawable.ic_content_copy, I18n.t(ctx, R.string.ig_profile_copy_username), () -> {
                    pw.dismiss();
                    copy(a, username);
                }));
                column.addView(row(template, R.drawable.ic_content_copy, I18n.t(ctx, R.string.ig_profile_copy_bio), () -> {
                    pw.dismiss();
                    String bio = bioOf(a, username);
                    if (bio == null || bio.trim().isEmpty())
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(a, I18n.t(a, R.string.ig_profile_no_bio), Toast.LENGTH_SHORT).show();
                    else copy(a, bio.trim());
                }));
                column.addView(row(template, R.drawable.ic_download, I18n.t(ctx, R.string.ig_profile_download_all), () -> {
                    pw.dismiss();
                    ps.reso.instaeclipse.mods.media.ProfileDownloadAll.start(a, username);
                }));
            }
            if (FeatureFlags.profileAnalyzer) {
                column.addView(row(template, R.drawable.ic_search, I18n.t(ctx, R.string.ig_pa_analyze), () -> {
                    pw.dismiss();
                    ps.reso.instaeclipse.mods.misc.ProfileAnalyzer.start(a, findUser(a, username), username);
                }));
            }
            int listHeight = scroller.getHeight();
            int at = list.indexOfChild(scroller);
            ViewGroup.LayoutParams old = scroller.getLayoutParams();
            list.removeView(scroller);
            scroller.setNestedScrollingEnabled(false);
            // Instagram's list keeps a fixed size, so it is laid out once tall enough for every row,
            // then trimmed to its last row: the ScrollView does all the scrolling.
            column.addView(scroller, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10_000));
            scroller.post(() -> {
                View last = scroller.getChildCount() > 0 ? scroller.getChildAt(scroller.getChildCount() - 1) : null;
                if (last == null) return;
                ViewGroup.LayoutParams rl = scroller.getLayoutParams();
                rl.height = last.getBottom() + scroller.getPaddingBottom();
                scroller.setLayoutParams(rl);
            });
            android.widget.ScrollView sv = new android.widget.ScrollView(ctx);
            sv.setVerticalScrollBarEnabled(false);
            sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            sv.addView(column);
            LinearLayout.LayoutParams lp = old instanceof LinearLayout.LayoutParams l ? new LinearLayout.LayoutParams(l)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.height = fitHeight(content, listHeight, column.getChildCount() * template.getHeight());
            list.addView(sv, at, lp);
            pw.update(); // the popup measures its new height
        } catch (Throwable t) {
            ModuleLog.line(TAG + "⚠️ inject: " + t);
        }
    }

    /** Height for the rows + list: all of it when it fits on screen, else what is left above the
     *  screen's bottom (the ScrollView scrolls the rest). */
    private static int fitHeight(View content, int listHeight, int extra) {
        int[] loc = new int[2];
        content.getLocationOnScreen(loc);
        android.graphics.Rect frame = new android.graphics.Rect();
        content.getRootView().getWindowVisibleDisplayFrame(frame);
        int margin = (int) (16 * content.getResources().getDisplayMetrics().density);
        int over = loc[1] + content.getHeight() + extra - frame.bottom + margin;
        return over <= 0 ? ViewGroup.LayoutParams.WRAP_CONTENT : Math.max(listHeight + extra - over, extra);
    }

    /** x of {@code v} inside {@code ancestor} (layout positions, so the popup's open animation doesn't skew it). */
    private static int leftIn(View v, View ancestor) {
        int x = 0;
        for (View c = v; c != null && c != ancestor; c = c.getParent() instanceof View p ? p : null) x += c.getLeft();
        return x;
    }

    private static Activity activityOf(Context c) {
        while (c instanceof android.content.ContextWrapper w) {
            if (c instanceof Activity act) return act;
            c = w.getBaseContext();
        }
        return UIHookManager.getCurrentActivity();
    }

    /** A row built like Instagram's template row: same padding, background, icon size and text style. */
    private static View row(View template, int iconRes, String label, Runnable action) {
        Context ctx = template.getContext();
        LinearLayout row = new LinearLayout(ctx);
        row.setTag(ROW_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(template.getHeight() > 0 ? template.getHeight() : template.getMinimumHeight());
        Drawable bg = template.getBackground();
        if (bg != null && bg.getConstantState() != null) row.setBackground(bg.getConstantState().newDrawable().mutate());
        row.setClickable(true);
        row.setFocusable(true);
        TextView tplText = firstText(template);
        ImageView tplIcon = firstImage(template);
        int color = tplText != null ? tplText.getCurrentTextColor() : 0xFFFFFFFF;
        // Instagram's row nests icon and label a few layouts deep; ours places them at the same
        // on-screen offsets instead of copying each layer's padding.
        int iconX = tplIcon != null ? leftIn(tplIcon, template) : 0;
        int textX = tplText != null ? leftIn(tplText, template) : 0;
        int left = tplIcon != null ? iconX : tplText != null ? textX : template.getPaddingLeft();
        row.setPadding(Math.max(left, 0), 0, template.getPaddingRight(), 0);
        if (tplIcon != null) {
            ImageView icon = new ImageView(ctx);
            Drawable d = ps.reso.instaeclipse.utils.dialog.DialogUtils.moduleIcon(iconRes, color);
            if (d != null) icon.setImageDrawable(d);
            ViewGroup.LayoutParams ilp = tplIcon.getLayoutParams();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ilp != null && ilp.width > 0 ? ilp.width : tplIcon.getWidth(),
                    ilp != null && ilp.height > 0 ? ilp.height : tplIcon.getHeight());
            if (tplText != null) lp.rightMargin = Math.max(textX - iconX - tplIcon.getWidth(), 0);
            icon.setPadding(tplIcon.getPaddingLeft(), tplIcon.getPaddingTop(), tplIcon.getPaddingRight(), tplIcon.getPaddingBottom());
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            row.addView(icon, lp);
        }
        TextView text = new TextView(ctx);
        text.setText(label);
        if (tplText != null) {
            text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tplText.getTextSize());
            text.setTextColor(tplText.getTextColors());
            text.setTypeface(tplText.getTypeface());
            row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        } else row.addView(text);
        row.setOnClickListener(v -> action.run());
        ViewGroup.LayoutParams rlp = template.getLayoutParams();
        row.setLayoutParams(rlp instanceof LinearLayout.LayoutParams l ? new LinearLayout.LayoutParams(l)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** The open profile's username (its header title), or null when no profile is on screen. */
    @SuppressLint("DiscouragedApi")
    public static String profileUsername(Activity a) {
        if (a == null) return null;
        android.content.res.Resources r = a.getResources();
        String pkg = a.getPackageName();
        int header = r.getIdentifier("profile_header_container", "id", pkg);
        int title = r.getIdentifier("action_bar_title", "id", pkg);
        int bar = r.getIdentifier("profile_action_bar", "id", pkg);
        if (header == 0 || title == 0) return null;
        // Profiles stay in the view tree behind other tabs (isShown still true), so both the
        // header and the profile action bar's title must really be on screen.
        View root = a.getWindow().getDecorView();
        if (onScreen(root, header, 0) == null) return null;
        View t = onScreen(root, title, bar);
        if (!(t instanceof TextView tv) || tv.getText() == null) return null;
        String s = tv.getText().toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** A view with {@code id} visible on screen, inside a view with {@code ancestorId} if set. */
    private static View onScreen(View root, int id, int ancestorId) {
        java.util.ArrayDeque<View> st = new java.util.ArrayDeque<>();
        st.push(root);
        android.graphics.Rect rect = new android.graphics.Rect();
        while (!st.isEmpty()) {
            View v = st.pop();
            if (!v.isShown()) continue;
            if (v.getId() == id && v.getGlobalVisibleRect(rect) && rect.width() > 0 && rect.height() > 0
                    && (ancestorId == 0 || hasAncestor(v, ancestorId))) return v;
            if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) st.push(g.getChildAt(i));
        }
        return null;
    }

    private static boolean hasAncestor(View v, int id) {
        for (Object p = v.getParent(); p instanceof View pv; p = pv.getParent()) if (pv.getId() == id) return true;
        return false;
    }

    /** The bio from the profile's User model: a User reachable from the shown fragments whose
     *  username matches the header. */
    private static String bioOf(Activity a, String username) {
        if (bioGetter == null) return null;
        try {
            Object user = findUser(a, username);
            return user != null ? (String) bioGetter.invoke(user) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Breadth-first from the shown lists' adapters (and the activity) through the Instagram objects
     * they hold (lists, maps, Kotlin flows, ViewModels), nearest first, for a User with this
     * username. Instagram's androidx is obfuscated, so fragments can't be listed by name.
     */
    public static Object findUser(Activity a, String username) {
        Method name = ps.reso.instaeclipse.utils.users.UserUtils.userUsernameGetter;
        if (name == null || a == null) return null;
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        java.util.ArrayDeque<Object[]> queue = new java.util.ArrayDeque<>();
        // Roots: the adapters of the shown lists (the profile header and grid are RecyclerViews),
        // then the activity itself.
        java.util.ArrayDeque<View> views = new java.util.ArrayDeque<>();
        views.push(a.getWindow().getDecorView());
        while (!views.isEmpty()) {
            View v = views.pop();
            if (!v.isShown()) continue;
            if (isA(v.getClass(), "androidx.recyclerview.widget.RecyclerView")) queue.add(new Object[]{call(v, "getAdapter"), 0});
            if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) views.push(g.getChildAt(i));
        }
        queue.add(new Object[]{a, 0});
        int visited = 0;
        while (!queue.isEmpty() && visited++ < 40_000) {
            Object[] e = queue.poll();
            Object o = e[0];
            int depth = (int) e[1];
            if (o == null || !seen.add(o)) continue;
            String cn = o.getClass().getName();
            if (cn.equals(USER)) {
                try { if (username.equals(name.invoke(o))) return o; } catch (Throwable ignored) { }
                continue;
            }
            if (depth >= 8) continue;
            if (o instanceof java.util.Map<?, ?> m) {
                try { for (Object v : m.values()) queue.add(new Object[]{v, depth + 1}); } catch (Throwable ignored) { }
                continue;
            }
            if (o instanceof java.util.Collection<?> col) {
                try { int n = 0; for (Object v : col) { if (n++ > 200) break; queue.add(new Object[]{v, depth + 1}); } } catch (Throwable ignored) { }
                continue;
            }
            if (o instanceof Object[] arr) {
                for (int i = 0; i < Math.min(arr.length, 200); i++) queue.add(new Object[]{arr[i], depth + 1});
                continue;
            }
            if (!(cn.startsWith("X.") || cn.startsWith("com.instagram.") || o == a || cn.startsWith("kotlinx.coroutines.flow.")
                    || isA(o.getClass(), "androidx.lifecycle.ViewModel"))) continue;
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                String n = c.getName();
                if (n.startsWith("android.") || n.startsWith("java.") || (n.startsWith("androidx.") && !n.startsWith("androidx.lifecycle.ViewModel")
                        && !n.startsWith("androidx.recyclerview.") && !n.startsWith("androidx.fragment.app.FragmentActivity"))) break;
                for (Field f : SafeReflect.fields(c)) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive() || f.getType() == String.class) continue;
                    try {
                        f.setAccessible(true);
                        queue.add(new Object[]{f.get(o), depth + 1});
                    } catch (Throwable ignored) { }
                }
            }
        }
        return null;
    }

    // Instagram's androidx classes come from its own class loader, so they are matched by name.
    private static Object call(Object o, String method) {
        try { return o == null ? null : o.getClass().getMethod(method).invoke(o); } catch (Throwable t) { return null; }
    }

    private static boolean isA(Class<?> c, String name) {
        for (; c != null; c = c.getSuperclass()) if (c.getName().equals(name)) return true;
        return false;
    }

    private static void copy(Context ctx, String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("InstaEclipse", text));
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_comment_copied), Toast.LENGTH_SHORT).show();
    }

    private static TextView firstText(View v) {
        if (v instanceof TextView t) return t;
        if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) {
            TextView t = firstText(g.getChildAt(i));
            if (t != null) return t;
        }
        return null;
    }

    private static ImageView firstImage(View v) {
        if (v instanceof ImageView i) return i;
        if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) {
            ImageView im = firstImage(g.getChildAt(i));
            if (im != null) return im;
        }
        return null;
    }
}
