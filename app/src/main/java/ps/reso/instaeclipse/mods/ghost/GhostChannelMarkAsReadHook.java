package ps.reso.instaeclipse.mods.ghost;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.feature.FeatureFlags;
import ps.reso.instaeclipse.utils.i18n.I18n;

public class GhostChannelMarkAsReadHook {

    private static final String CHANNEL_TAG = "ie_channel_seen";

    // Cached once on first use — resource IDs are constant for a given app install.
    private static volatile int sCachedSeenStateId = 0;
    private static volatile int sCachedHeaderButtonsId = 0;

    public void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // Bail immediately if the feature is off — this hook fires for every
                    // view attachment in the entire app, so the fast path must be trivial.
                    if (!FeatureFlags.isGhostSeen) return;

                    View view = (View) param.thisObject;
                    Context context = view.getContext();

                    if (sCachedSeenStateId == 0) {
                        @SuppressLint("DiscouragedApi")
                        int id = context.getResources().getIdentifier(
                                "seen_state_text", "id", context.getPackageName());
                        sCachedSeenStateId = id;
                    }

                    if (sCachedSeenStateId == 0 || view.getId() != sCachedSeenStateId) return;
                    if (!(view instanceof TextView seenTextView)) return;

                    if (sCachedHeaderButtonsId == 0) {
                        @SuppressLint("DiscouragedApi")
                        int id = context.getResources().getIdentifier(
                                "header_right_buttons", "id", context.getPackageName());
                        sCachedHeaderButtonsId = id;
                    }

                    if (sCachedHeaderButtonsId != 0) {
                        View container = view.getRootView().findViewById(sCachedHeaderButtonsId);
                        if (container instanceof ViewGroup viewGroup) {
                            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                                CharSequence description = viewGroup.getChildAt(i).getContentDescription();
                                if (description != null) {
                                    String descStr = description.toString().toLowerCase();
                                    if (descStr.contains("audio call") ||
                                            descStr.contains("video call") ||
                                            descStr.contains("blend")) {
                                        return;
                                    }
                                }
                            }
                        }
                    }
                    updateChannelSeen(seenTextView);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse): Channel seen hook failed: " + t.getMessage());
        }
    }

    private void updateChannelSeen(TextView textView) {
        // Prevent multiple listeners/updates
        if (textView.getTag() != null && textView.getTag().equals(CHANNEL_TAG)) return;
        textView.setTag(CHANNEL_TAG);

        // Make it look interactive
        textView.setTextColor(Color.CYAN); // Distinguish it as a "modded" element

        textView.setOnClickListener(v -> {
            triggerChannelSeen(textView);
        });

        // Optional: Append a ghost emoji to indicate it's modded
        String currentText = textView.getText().toString();
        if (!currentText.contains("👻")) {
            textView.setText(currentText + " 👻");
        }
    }

    private void triggerChannelSeen(View view) {
        try {
            Context ctx = view.getContext();
            @SuppressLint("DiscouragedApi")
            int messageListId = ctx.getResources().getIdentifier("message_list", "id", ctx.getPackageName());

            View root = view.getRootView();
            View messageList = root.findViewById(messageListId);

            if (messageList instanceof ViewGroup group) {
                group.scrollBy(0, 100_000);

                FeatureFlags.isGhostSeen = false;
                group.scrollBy(0, -300);

                view.postDelayed(() -> {
                    group.scrollBy(0, 300);
                    FeatureFlags.isGhostSeen = true;
                    Toast.makeText(ctx, I18n.t(ctx, R.string.ig_toast_channel_seen_sent), Toast.LENGTH_SHORT).show();
                }, 400);
            }
        } catch (Exception e) {
            FeatureFlags.isGhostSeen = true;
        }
    }
}