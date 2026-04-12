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

    public void install(ClassLoader classLoader) {
        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View view = (View) param.thisObject;
                    Context context = view.getContext();

                    if (!FeatureFlags.isGhostSeen) return;

                    // Target the specific seen state text ID used in communities/channels
                    @SuppressLint("DiscouragedApi")
                    int seenStateId = context.getResources().getIdentifier(
                            "seen_state_text", "id", context.getPackageName());

                    if (view.getId() == seenStateId && view instanceof TextView seenTextView) {

                        // Get the ID for the buttons container
                        @SuppressLint("DiscouragedApi")
                        int composerContainerId = context.getResources().getIdentifier(
                                "header_right_buttons", "id", context.getPackageName());

                        if (composerContainerId != 0) {
                            View container = view.getRootView().findViewById(composerContainerId);

                            if (container instanceof ViewGroup viewGroup) {
                                for (int i = 0; i < viewGroup.getChildCount(); i++) {
                                    View child = viewGroup.getChildAt(i);
                                    CharSequence description = child.getContentDescription();

                                    if (description != null) {
                                        String descStr = description.toString().toLowerCase();

                                        // If it's a DM-specific button (Call or Blend), exit the hook
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