package ps.reso.instaeclipse.mods.ghost;

import android.app.Activity;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.dialog.DialogUtils;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.ghost.CurrentConversation;
import ps.reso.instaeclipse.utils.ghost.HiddenThreads;
import ps.reso.instaeclipse.utils.i18n.I18n;

public class BatchHideChatsHook implements ViewTreeObserver.OnGlobalLayoutListener {

    private static final String BTN_TAG = "ie_batch_hide_btn";
    private static final WeakHashMap<Activity, BatchHideChatsHook> instances = new WeakHashMap<>();

    private final Activity activity;
    private boolean attached = false;

    public static void attach(Activity a, View root) {
        if (!FeatureFlags.hideSpecificChats || !FeatureFlags.enableBatchHideChats) return;
        BatchHideChatsHook hook = instances.get(a);
        if (hook == null) {
            hook = new BatchHideChatsHook(a);
            instances.put(a, hook);
        }
        if (!hook.attached && root != null) {
            root.getViewTreeObserver().addOnGlobalLayoutListener(hook);
            hook.attached = true;
        }
    }

    private BatchHideChatsHook(Activity activity) {
        this.activity = activity;
    }

    @Override
    public void onGlobalLayout() {
        if (activity.isDestroyed() || activity.isFinishing()) return;
        View root = activity.findViewById(android.R.id.content);
        if (root == null) return;

        List<String> selectedThreads = resolveSelectedThreadIds(activity);
        boolean isMultiSelectActive = !selectedThreads.isEmpty();

        View existingBtn = root.findViewWithTag(BTN_TAG);

        if (isMultiSelectActive) {
            if (existingBtn == null) {
                injectHideButton(root, selectedThreads);
            } else {
                existingBtn.setOnClickListener(v -> hideThreads(selectedThreads));
            }
        } else if (existingBtn != null) {
            if (existingBtn.getParent() instanceof ViewGroup) {
                ((ViewGroup) existingBtn.getParent()).removeView(existingBtn);
            }
        }
    }

    private void injectHideButton(View root, List<String> selectedThreads) {
        ViewGroup target = findMultiSelectBar(root);
        if (target == null) return;

        ImageView btn = new ImageView(activity);
        btn.setTag(BTN_TAG);
        btn.setImageDrawable(DialogUtils.moduleIcon(R.drawable.ic_eye_off, Color.WHITE));
        int pad = (int) (12 * activity.getResources().getDisplayMetrics().density);
        btn.setPadding(pad, pad, pad, pad);
        btn.setOnClickListener(v -> hideThreads(selectedThreads));

        target.addView(btn, 0);
    }

    private ViewGroup findMultiSelectBar(View root) {
        ArrayList<View> out = new ArrayList<>();
        root.findViewsWithText(out, "Mute", View.FIND_VIEWS_WITH_TEXT);
        root.findViewsWithText(out, "Delete", View.FIND_VIEWS_WITH_TEXT);
        for (View v : out) {
            if (v.getParent() instanceof LinearLayout) {
                LinearLayout p = (LinearLayout) v.getParent();
                if (p.getOrientation() == LinearLayout.HORIZONTAL) {
                    return p;
                }
            }
        }
        return null;
    }

    private void hideThreads(List<String> threadIds) {
        String acc = CurrentConversation.accountId(activity);
        for (String id : threadIds) {
            HiddenThreads.setHidden(acc, id, "Thread " + id, true);
        }
        Toast.makeText(activity, "Hidden " + threadIds.size() + " chats", Toast.LENGTH_SHORT).show();
        activity.onBackPressed();
        HideChatsHook.refreshActiveInbox(activity);
    }

    private List<String> resolveSelectedThreadIds(Activity activity) {
        Set<String> ids = new HashSet<>();
        if (activity instanceof FragmentActivity) {
            scanFragmentsForSelectedThreads(((FragmentActivity) activity).getSupportFragmentManager().getFragments(), ids, 0);
        }

        if (ids.isEmpty()) {
            View root = activity.findViewById(android.R.id.content);
            if (root instanceof ViewGroup) {
                scanViewForSelectedThreads((ViewGroup) root, ids);
            }
        }

        return new ArrayList<>(ids);
    }

    private void scanFragmentsForSelectedThreads(List<Fragment> fragments, Set<String> out, int depth) {
        if (depth > 5 || fragments == null) return;
        for (Fragment f : fragments) {
            if (f == null || f.getView() == null || !f.getUserVisibleHint()) continue;
            String name = f.getClass().getName();
            if (!name.startsWith("X.") && !name.startsWith("com.instagram.")) continue;

            scanObjectForThreadKeys(f, out, new HashSet<>(), 0);
            scanFragmentsForSelectedThreads(f.getChildFragmentManager().getFragments(), out, depth + 1);
        }
    }

    private void scanObjectForThreadKeys(Object obj, Set<String> out, Set<Object> seen, int depth) {
        if (depth > 2 || obj == null || !seen.add(obj)) return;

        if (obj instanceof Set) {
            for (Object item : (Set<?>) obj) extractThreadIdFrom(item, out);
        } else if (obj instanceof Map) {
            for (Object item : ((Map<?, ?>) obj).keySet()) extractThreadIdFrom(item, out);
            for (Object item : ((Map<?, ?>) obj).values()) extractThreadIdFrom(item, out);
        } else {
            Class<?> c = obj.getClass();
            if (!c.getName().startsWith("X.") && !c.getName().startsWith("com.instagram.")) return;
            for (Field field : c.getDeclaredFields()) {
                if (field.getType() == Set.class || field.getType() == Map.class) {
                    try {
                        field.setAccessible(true);
                        scanObjectForThreadKeys(field.get(obj), out, seen, depth + 1);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void extractThreadIdFrom(Object obj, Set<String> out) {
        if (obj == null) return;
        String cn = obj.getClass().getName();
        if (cn.contains("DirectThreadKey")) {
            for (Field f : obj.getClass().getDeclaredFields()) {
                if (f.getType() == String.class) {
                    try {
                        f.setAccessible(true);
                        String val = (String) f.get(obj);
                        if (val != null && !val.isEmpty()) {
                            out.add(val);
                            return;
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    private void scanViewForSelectedThreads(ViewGroup group, Set<String> out) {
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (child instanceof ViewGroup) {
                scanViewForSelectedThreads((ViewGroup) child, out);
            } else if (child instanceof CompoundButton && ((CompoundButton) child).isChecked()) {
                 Object tag = child.getTag();
                 if (tag != null) extractThreadIdFrom(tag, out);
                 if (child.getParent() instanceof View) {
                     extractThreadIdFrom(((View) child.getParent()).getTag(), out);
                 }
            } else if (child.isSelected()) {
                 Object tag = child.getTag();
                 if (tag != null) extractThreadIdFrom(tag, out);
                 if (child.getParent() instanceof View) {
                     extractThreadIdFrom(((View) child.getParent()).getTag(), out);
                 }
            }
        }
    }
}
