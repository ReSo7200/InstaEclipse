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
import ps.reso.instaeclipse.utils.feature.FeatureFlags;

public class GhostCommunityMarkAsReadButton {

    private static final String COMMUNITY_TAG = "ie_community_seen";

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
                        // Only handle community context — skip if the regular DM composer
                        // buttons container is present (GhostSeenButtonHook covers that case)
                        @SuppressLint("DiscouragedApi")
                        int composerContainerId = context.getResources().getIdentifier(
                                "row_thread_composer_buttons_container", "id", context.getPackageName());
                        if (composerContainerId != 0 && view.getRootView().findViewById(composerContainerId) != null) return;

                        updateCommunitySeen(seenTextView);
                    }
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("(InstaEclipse): Community seen hook failed: " + t.getMessage());
        }
    }

    private void updateCommunitySeen(TextView textView) {
        // Prevent multiple listeners/updates
        if (textView.getTag() != null && textView.getTag().equals(COMMUNITY_TAG)) return;
        textView.setTag(COMMUNITY_TAG);

        // Make it look interactive
        textView.setTextColor(Color.CYAN); // Distinguish it as a "modded" element

        textView.setOnClickListener(v -> {
            triggerCommunitySeen(textView);
        });

        // Optional: Append a ghost emoji to indicate it's modded
        String currentText = textView.getText().toString();
        if (!currentText.contains("👻")) {
            textView.setText(currentText + " 👻");
        }
    }

    private void triggerCommunitySeen(View view) {
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
                    Toast.makeText(ctx, "✅ Community Seen Sent", Toast.LENGTH_SHORT).show();
                }, 400);
            }
        } catch (Exception e) {
            FeatureFlags.isGhostSeen = true;
        }
    }
}