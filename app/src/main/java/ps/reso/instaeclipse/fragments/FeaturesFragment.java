package ps.reso.instaeclipse.fragments;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.ShapeAppearanceModel;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import ps.reso.instaeclipse.R;

public class FeaturesFragment extends Fragment {

    private SharedPreferences localCache;
    private View fragmentView;
    private RecyclerView recyclerFeatures;
    private FeatureAdapter adapter;
    private TextView tvHeaderTitle;
    private ImageButton btnBack;
    private ExtendedFloatingActionButton fabSave;
    private ActivityResultLauncher<Uri> dirPickerLauncher;
    private ActivityResultLauncher<String[]> restoreFileLauncher;
    private ActivityResultLauncher<String> notifPermLauncher;

    private String currentMenu = "main";

    // STAGING SYSTEM: Holds changes before applying
    private final Map<String, Boolean> stagedChanges = new HashMap<>();

    private final BroadcastReceiver prefsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("ps.reso.instaeclipse.ACTION_SEND_PREFS".equals(intent.getAction())) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    // If we just set the path locally (via dirPickerLauncher), the incoming
                    // reply from Instagram may still carry the old value — skip overwriting.
                    boolean suppressPath = localCache.getBoolean("pathJustSetLocally", false);
                    SharedPreferences.Editor editor = localCache.edit();
                    if (suppressPath) editor.remove("pathJustSetLocally");
                    for (String key : bundle.keySet()) {
                        Object value = bundle.get(key);
                        if (value instanceof Boolean) {
                            editor.putBoolean(key, (Boolean) value);
                        } else if (value instanceof String) {
                            if (suppressPath && ("downloaderCustomPath".equals(key) || "downloaderCustomUri".equals(key))) {
                                continue;
                            }
                            editor.putString(key, (String) value);
                        }
                    }
                    editor.apply();
                    // Rebuild downloader menu so the folder title reflects the latest value
                    if ("downloader".equals(currentMenu)) {
                        loadDownloaderMenu();
                    } else if (adapter != null) {
                        adapter.notifyDataSetChanged();
                    }
                }
            }
        }
    };

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        restoreFileLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri == null) return;
            try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
                 Scanner scanner = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                String json = scanner.hasNext() ? scanner.next() : "";
                JSONObject root = new JSONObject(json);
                JSONObject s = root.has("settings") ? root.getJSONObject("settings") : root;
                SharedPreferences.Editor editor = localCache.edit();
                Iterator<String> keys = s.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    Object val = s.get(key);
                    if (val instanceof Boolean) {
                        editor.putBoolean(key, (Boolean) val);
                        Intent intent = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF");
                        intent.putExtra("key", key);
                        intent.putExtra("value", (boolean) (Boolean) val);
                        requireContext().sendBroadcast(intent);
                    }
                }
                editor.apply();
                if (adapter != null) adapter.notifyDataSetChanged();
                Toast.makeText(requireContext(), getString(R.string.ig_toast_settings_restored), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        notifPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {});

        dirPickerLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
            if (uri != null) {
                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                requireContext().getContentResolver().takePersistableUriPermission(uri, takeFlags);

                String uriString = uri.toString();
                String path = uri.getPath();

                if (path != null) {
                    path = path.replace("/tree/primary:", "/storage/emulated/0/");
                    path = path.replace("/document/primary:", "/storage/emulated/0/");
                } else {
                    path = uriString;
                }

                // Set flag + save new path atomically so the incoming ACTION_SEND_PREFS
                // reply (triggered by onResume's ACTION_REQUEST_PREFS) doesn't overwrite us.
                // commit() (not apply()) ensures the XML is flushed to disk before we make it
                // world-readable so the module's XSharedPreferences can pick it up on cold start.
                SharedPreferences.Editor editor = localCache.edit();
                editor.putBoolean("pathJustSetLocally", true);
                editor.putString("downloaderCustomUri", uriString);
                editor.putString("downloaderCustomPath", path);
                editor.commit();
                makeLocalCacheWorldReadable();

                Intent intentUri = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
                intentUri.putExtra("key", "downloaderCustomUri");
                intentUri.putExtra("value", uriString);
                requireContext().sendBroadcast(intentUri);

                Intent intentPath = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
                intentPath.putExtra("key", "downloaderCustomPath");
                intentPath.putExtra("value", path);
                requireContext().sendBroadcast(intentPath);

                Toast.makeText(requireContext(), getString(R.string.ig_toast_download_folder_updated), Toast.LENGTH_SHORT).show();

                if ("downloader".equals(currentMenu)) {
                    loadDownloaderMenu();
                }
            }
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        fragmentView = inflater.inflate(R.layout.fragment_features, container, false);
        localCache = requireContext().getSharedPreferences("instaeclipse_cache", Context.MODE_PRIVATE);

        // Request POST_NOTIFICATIONS permission (required API 33+ for download progress notifications)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        recyclerFeatures = fragmentView.findViewById(R.id.recycler_features);
        recyclerFeatures.setLayoutManager(new LinearLayoutManager(requireContext()));
        // Disable change animations so conditional enabled/alpha updates are instant, not crossfaded
        recyclerFeatures.setItemAnimator(null);
        adapter = new FeatureAdapter();
        recyclerFeatures.setAdapter(adapter);

        tvHeaderTitle = fragmentView.findViewById(R.id.tv_header_title);
        btnBack = fragmentView.findViewById(R.id.btn_back);
        fabSave = fragmentView.findViewById(R.id.fab_save);

        btnBack.setOnClickListener(v -> loadMainMenu());

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!"main".equals(currentMenu)) {
                    loadMainMenu();
                } else {
                    setEnabled(false);
                    requireActivity().getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        fabSave.setOnClickListener(v -> commitStagedChanges());

        loadMainMenu();

        return fragmentView;
    }

    // =========================================================
    // MENU DATA MODEL & ADAPTER
    // =========================================================

    public static class FeatureItem {
        public static final int TYPE_HEADER = 0;
        public static final int TYPE_SWITCH = 1;
        public static final int TYPE_CLICKABLE = 2;
        public static final int TYPE_MASTER_SWITCH = 3;
        public static final int TYPE_SPACER = 4;

        public int type;
        public String title;
        public String prefKey;
        public Runnable onClick;
        public int textColor;
        public boolean isExtreme;
        public List<String> childKeys;
        /** When this switch is turned ON, also stage this key as true (parent dependency). */
        public String dependsOn;
        /** When this switch is turned OFF, also stage this key as false (child cascade). */
        public String cascadeOffKey;
        /** Switch is only enabled when at least one key in this list is currently true. */
        public List<String> requiresAnyOf;
        /** Switch is locked (disabled) when this key is currently true. */
        public String disabledWhenTrue;

        public int segmentPosition;
        public int segmentSize;
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView tvHeader;
        HeaderViewHolder(View v) {
            super(v);
            tvHeader = v.findViewById(R.id.tv_header);
        }
    }

    static class ItemViewHolder extends RecyclerView.ViewHolder {
        TextView tvTitle;
        MaterialSwitch swToggle;
        MaterialCardView cardView;
        ItemViewHolder(View v) {
            super(v);
            tvTitle = v.findViewById(R.id.tv_title);
            swToggle = v.findViewById(R.id.sw_toggle);
            cardView = (MaterialCardView) v;
        }
    }

    private class FeatureAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        List<FeatureItem> items = new ArrayList<>();

        public void setItems(List<FeatureItem> items) {
            this.items = items;
            notifyDataSetChanged();
        }

        @Override
        public int getItemViewType(int position) {
            int type = items.get(position).type;
            if (type == FeatureItem.TYPE_HEADER) return 0;
            if (type == FeatureItem.TYPE_SPACER)  return 2;
            return 1;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == 0) {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_feature_header, parent, false);
                return new HeaderViewHolder(v);
            } else if (viewType == 2) {
                // Spacer: a plain transparent view with fixed height
                View v = new View(parent.getContext());
                v.setLayoutParams(new RecyclerView.LayoutParams(
                        RecyclerView.LayoutParams.MATCH_PARENT,
                        (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, parent.getResources().getDisplayMetrics())
                ));
                return new RecyclerView.ViewHolder(v) {};
            } else {
                View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_feature, parent, false);
                return new ItemViewHolder(v);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            FeatureItem item = items.get(position);

            if (item.type == FeatureItem.TYPE_SPACER) return;

            if (holder instanceof HeaderViewHolder) {
                ((HeaderViewHolder) holder).tvHeader.setText(item.title);
            } else if (holder instanceof ItemViewHolder) {
                ItemViewHolder itemHolder = (ItemViewHolder) holder;

                itemHolder.tvTitle.setText(item.title);

                // Master switches get a tinted card + bold text to stand out from regular items
                boolean isMaster = item.type == FeatureItem.TYPE_MASTER_SWITCH;
                if (isMaster) {
                    int bg = MaterialColors.getColor(itemHolder.itemView, com.google.android.material.R.attr.colorSecondaryContainer);
                    int fg = MaterialColors.getColor(itemHolder.itemView, com.google.android.material.R.attr.colorOnSecondaryContainer);
                    itemHolder.cardView.setCardBackgroundColor(bg);
                    itemHolder.tvTitle.setTextColor(fg);
                    itemHolder.tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                } else {
                    int bg = MaterialColors.getColor(itemHolder.itemView, com.google.android.material.R.attr.colorSurfaceContainerLow);
                    int defaultTextColor = MaterialColors.getColor(itemHolder.itemView, com.google.android.material.R.attr.colorOnSurface);
                    itemHolder.cardView.setCardBackgroundColor(bg);
                    itemHolder.tvTitle.setTextColor(item.textColor != 0 ? item.textColor : defaultTextColor);
                    itemHolder.tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL);
                }

                float largeRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, getResources().getDisplayMetrics());
                float smallRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, getResources().getDisplayMetrics());

                ShapeAppearanceModel.Builder shapeBuilder = itemHolder.cardView.getShapeAppearanceModel().toBuilder();

                if (item.segmentSize == 1) {
                    shapeBuilder.setAllCornerSizes(largeRadius);
                } else if (item.segmentPosition == 0) {
                    shapeBuilder.setTopLeftCornerSize(largeRadius).setTopRightCornerSize(largeRadius)
                            .setBottomLeftCornerSize(smallRadius).setBottomRightCornerSize(smallRadius);
                } else if (item.segmentPosition == item.segmentSize - 1) {
                    shapeBuilder.setTopLeftCornerSize(smallRadius).setTopRightCornerSize(smallRadius)
                            .setBottomLeftCornerSize(largeRadius).setBottomRightCornerSize(largeRadius);
                } else {
                    shapeBuilder.setAllCornerSizes(smallRadius);
                }
                itemHolder.cardView.setShapeAppearanceModel(shapeBuilder.build());

                // Compute enabled state from conditions
                boolean switchEnabled = true;
                if (item.requiresAnyOf != null) {
                    switchEnabled = false;
                    for (String k : item.requiresAnyOf) {
                        if (getCurrentState(k)) { switchEnabled = true; break; }
                    }
                }
                if (item.disabledWhenTrue != null && getCurrentState(item.disabledWhenTrue)) {
                    switchEnabled = false;
                }
                itemHolder.itemView.setEnabled(switchEnabled);
                itemHolder.swToggle.setEnabled(switchEnabled);
                itemHolder.itemView.setAlpha(switchEnabled ? 1f : 0.4f);

                itemHolder.swToggle.setOnCheckedChangeListener(null);
                itemHolder.itemView.setOnClickListener(null);

                if (item.type == FeatureItem.TYPE_CLICKABLE) {
                    itemHolder.swToggle.setVisibility(View.GONE);
                    itemHolder.itemView.setOnClickListener(v -> {
                        if (item.onClick != null) item.onClick.run();
                    });
                } else {
                    itemHolder.swToggle.setVisibility(View.VISIBLE);
                    itemHolder.itemView.setOnClickListener(v -> itemHolder.swToggle.toggle());

                    boolean isChecked = false;
                    if (item.type == FeatureItem.TYPE_MASTER_SWITCH) {
                        isChecked = true;
                        for (String key : item.childKeys) {
                            if (!getCurrentState(key)) {
                                isChecked = false;
                                break;
                            }
                        }
                    } else if (item.prefKey != null) {
                        isChecked = getCurrentState(item.prefKey);
                    }
                    itemHolder.swToggle.setChecked(isChecked);

                    itemHolder.swToggle.setOnCheckedChangeListener((btn, checked) -> {
                        if (item.isExtreme && checked) {
                            new AlertDialog.Builder(itemHolder.itemView.getContext())
                                    .setTitle(getString(R.string.ig_dialog_distraction_extreme_title))
                                    .setMessage(getString(R.string.ig_dialog_distraction_extreme_message))
                                    .setPositiveButton(getString(R.string.ig_dialog_yes), (dialog, which) -> {
                                        stageChange(item.prefKey, true);
                                        stageChange("isDistractionFree", true);
                                        updateMasterSwitches();
                                        refreshConditionalItems();
                                    })
                                    .setNegativeButton(getString(R.string.ig_dialog_cancel), (dialog, which) -> {
                                        itemHolder.swToggle.setChecked(false);
                                    })
                                    .show();
                            return;
                        }

                        if (item.type == FeatureItem.TYPE_MASTER_SWITCH) {
                            for (String key : item.childKeys) {
                                stageChange(key, checked);
                            }
                            for (int i = 0; i < items.size(); i++) {
                                if (items.get(i).type == FeatureItem.TYPE_SWITCH) {
                                    notifyItemChanged(i);
                                }
                            }
                            refreshConditionalItems();
                        } else if (item.prefKey != null) {
                            stageChange(item.prefKey, checked);
                            // Auto-enable parent dependency (e.g. disableReels when disableReelsExceptDM is on)
                            if (checked && item.dependsOn != null) {
                                stageChange(item.dependsOn, true);
                                refreshRowByKey(item.dependsOn);
                            }
                            // Auto-disable child when parent is turned off (e.g. disableReelsExceptDM when disableReels is off)
                            if (!checked && item.cascadeOffKey != null) {
                                stageChange(item.cascadeOffKey, false);
                                refreshRowByKey(item.cascadeOffKey);
                            }
                            updateMasterSwitches();
                            refreshConditionalItems();
                        }
                    });
                }
            }
        }

        private void refreshRowByKey(String prefKey) {
            for (int i = 0; i < items.size(); i++) {
                if (prefKey.equals(items.get(i).prefKey)) {
                    notifyItemChanged(i);
                    break;
                }
            }
        }

        /** Refreshes any item whose enabled state depends on runtime conditions. */
        void refreshConditionalItems() {
            for (int i = 0; i < items.size(); i++) {
                FeatureItem fi = items.get(i);
                if (fi.requiresAnyOf != null || fi.disabledWhenTrue != null) {
                    notifyItemChanged(i);
                }
            }
        }

        private void updateMasterSwitches() {
            for (int i = 0; i < items.size(); i++) {
                if (items.get(i).type == FeatureItem.TYPE_MASTER_SWITCH) {
                    notifyItemChanged(i);
                }
            }
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    // =========================================================
    // MENU BUILDING HELPERS
    // =========================================================

    private void showMenu(String title, List<Object> definitions) {
        ViewGroup headerLayout = fragmentView.findViewById(R.id.header_layout);
        TransitionManager.beginDelayedTransition(headerLayout, new AutoTransition().setDuration(200));

        tvHeaderTitle.setText(title);
        btnBack.setVisibility(title.equals(getString(R.string.features)) ? View.GONE : View.VISIBLE);

        List<FeatureItem> displayList = new ArrayList<>();
        for (int di = 0; di < definitions.size(); di++) {
            Object def = definitions.get(di);
            if (def instanceof String) {
                FeatureItem header = new FeatureItem();
                header.type = FeatureItem.TYPE_HEADER;
                header.title = (String) def;
                displayList.add(header);
            } else if (def instanceof List) {
                @SuppressWarnings("unchecked")
                List<FeatureItem> group = (List<FeatureItem>) def;
                for (int i = 0; i < group.size(); i++) {
                    FeatureItem item = group.get(i);
                    item.segmentPosition = i;
                    item.segmentSize = group.size();
                    displayList.add(item);
                }
                // Add a spacer after each group except the last definition
                if (di < definitions.size() - 1) {
                    FeatureItem spacer = new FeatureItem();
                    spacer.type = FeatureItem.TYPE_SPACER;
                    displayList.add(spacer);
                }
            }
        }

        recyclerFeatures.setAlpha(0f);
        recyclerFeatures.setTranslationY(60f);

        adapter.setItems(displayList);
        recyclerFeatures.scrollToPosition(0);

        recyclerFeatures.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(250)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private FeatureItem createNav(String title, Runnable navAction) {
        FeatureItem item = new FeatureItem();
        item.type = FeatureItem.TYPE_CLICKABLE;
        item.title = title;
        item.onClick = navAction;
        return item;
    }

    private FeatureItem createClickable(String title, int color, Runnable onClick) {
        FeatureItem item = new FeatureItem();
        item.type = FeatureItem.TYPE_CLICKABLE;
        item.title = title;
        item.textColor = color;
        item.onClick = onClick;
        return item;
    }

    private FeatureItem createSwitch(String title, String prefKey) {
        FeatureItem item = new FeatureItem();
        item.type = FeatureItem.TYPE_SWITCH;
        item.title = title;
        item.prefKey = prefKey;
        return item;
    }

    private FeatureItem createSwitchWithDependency(String title, String prefKey, String dependsOn) {
        FeatureItem item = createSwitch(title, prefKey);
        item.dependsOn = dependsOn;
        return item;
    }

    private FeatureItem createSwitchWithCascadeOff(String title, String prefKey, String cascadeOffKey) {
        FeatureItem item = createSwitch(title, prefKey);
        item.cascadeOffKey = cascadeOffKey;
        return item;
    }

    private FeatureItem createMasterSwitch(String title, List<String> childKeys) {
        FeatureItem item = new FeatureItem();
        item.type = FeatureItem.TYPE_MASTER_SWITCH;
        item.title = title;
        item.childKeys = childKeys;
        return item;
    }

    // =========================================================
    // MENUS
    // =========================================================

    private void loadMainMenu() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_categories));
        defs.add(Arrays.asList(
                createNav(getString(R.string.ig_dialog_menu_dev_options), this::loadDevMenu),
                createNav(getString(R.string.ig_dialog_menu_ghost_settings), this::loadGhostMenu),
                createNav(getString(R.string.ig_dialog_menu_ad_analytics), this::loadAdsMenu),
                createNav(getString(R.string.ig_dialog_menu_clean_feed), this::loadCleanFeedMenu),
                createNav(getString(R.string.ig_dialog_menu_distraction_free), this::loadDistractionMenu),
                createNav(getString(R.string.ig_dialog_menu_misc), this::loadMiscMenu),
                createNav(getString(R.string.ig_dialog_menu_downloader), this::loadDownloaderMenu)
        ));

        defs.add(getString(R.string.feat_tools));
        defs.add(Arrays.asList(
                createClickable(getString(R.string.ig_dialog_backup_settings), 0, this::backupSettings),
                createClickable(getString(R.string.ig_dialog_restore_settings), 0, this::restoreSettings),
                createClickable(getString(R.string.ig_dialog_menu_about), 0, this::showAboutDialog),
                createClickable(getString(R.string.ig_dialog_menu_restart), 0xFFFF453A, this::restartInstagram)
        ));

        showMenu(getString(R.string.features), defs);
        currentMenu = "main";
    }

    private void loadDevMenu() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(createSwitch(getString(R.string.ig_dialog_dev_enable), "isDevEnabled")));

        defs.add(getString(R.string.feat_config));
        defs.add(Arrays.asList(
                createClickable(getString(R.string.ig_dialog_dev_import), 0xFF30D158, this::importDevConfig),
                createClickable(getString(R.string.ig_dialog_dev_export), 0xFF0A84FF, this::exportDevConfig)
        ));

        defs.add(getString(R.string.feat_options));
        defs.add(Arrays.asList(createSwitch(getString(R.string.ig_dialog_dev_remove_build_expired), "removeBuildExpiredPopup")));

        showMenu(getString(R.string.ig_dialog_section_dev_options), defs);
        currentMenu = "dev";
    }

    private void loadGhostMenu() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_quick_toggle));
        defs.add(Arrays.asList(createNav(getString(R.string.ig_dialog_customize_quick_toggle), this::loadQuickTogglesMenu)));

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createMasterSwitch(getString(R.string.ig_dialog_enable_disable_all), Arrays.asList(
                        "isGhostSeen", "isGhostTyping", "isGhostStory", "isGhostLive",
                        "allowScreenshots", "isGhostScreenshot", "isGhostViewOnce",
                        "enableUnlimitedReplays", "permanentViewMode", "keepEphemeralMessages"
                )),
                createSwitch(getString(R.string.ig_dialog_ghost_hide_dm_seen), "isGhostSeen"),
                createSwitch(getString(R.string.ig_dialog_ghost_hide_typing), "isGhostTyping"),
                createSwitch(getString(R.string.ig_dialog_ghost_hide_story_views), "isGhostStory"),
                createSwitch(getString(R.string.ig_dialog_ghost_hide_live_presence), "isGhostLive"),
                createSwitch(getString(R.string.ig_dialog_ghost_allow_screenshots_dms), "allowScreenshots"),
                createSwitch(getString(R.string.ig_dialog_ghost_bypass_screenshot), "isGhostScreenshot"),
                createSwitch(getString(R.string.ig_dialog_ghost_hide_view_once), "isGhostViewOnce"),
                createSwitch(getString(R.string.ig_dialog_ghost_unlimited_replays), "enableUnlimitedReplays"),
                createSwitch(getString(R.string.ig_dialog_ghost_permanent_view_once), "permanentViewMode"),
                createSwitch(getString(R.string.ig_dialog_ghost_keep_disappearing), "keepEphemeralMessages")
        ));

        showMenu(getString(R.string.ig_dialog_section_ghost_mode), defs);
        currentMenu = "ghost";
    }

    private void loadQuickTogglesMenu() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createMasterSwitch(getString(R.string.ig_dialog_enable_disable_all), Arrays.asList(
                        "quickToggleSeen", "quickToggleTyping", "quickToggleScreenshot",
                        "quickToggleViewOnce", "quickToggleStory", "quickToggleLive",
                        "quickToggleEphemeral", "quickToggleReplays", "quickTogglePermanentView",
                        "quickToggleAllowScreenshots"
                )),
                createSwitch(getString(R.string.ig_dialog_quick_hide_seen), "quickToggleSeen"),
                createSwitch(getString(R.string.ig_dialog_quick_hide_typing), "quickToggleTyping"),
                createSwitch(getString(R.string.ig_dialog_quick_disable_screenshot), "quickToggleScreenshot"),
                createSwitch(getString(R.string.ig_dialog_quick_hide_view_once), "quickToggleViewOnce"),
                createSwitch(getString(R.string.ig_dialog_quick_hide_story_seen), "quickToggleStory"),
                createSwitch(getString(R.string.ig_dialog_quick_hide_live_seen), "quickToggleLive"),
                createSwitch(getString(R.string.ig_dialog_quick_keep_ephemeral), "quickToggleEphemeral"),
                createSwitch(getString(R.string.ig_dialog_quick_unlimited_replays), "quickToggleReplays"),
                createSwitch(getString(R.string.ig_dialog_quick_permanent_view), "quickTogglePermanentView"),
                createSwitch(getString(R.string.ig_dialog_quick_allow_screenshots), "quickToggleAllowScreenshots")
        ));

        showMenu(getString(R.string.ig_dialog_section_quick_toggle), defs);
        currentMenu = "qt";
    }

    private void loadAdsMenu() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createMasterSwitch(getString(R.string.ig_dialog_enable_disable_all), Arrays.asList("isAdBlockEnabled", "isAnalyticsBlocked", "disableTrackingLinks")),
                createSwitch(getString(R.string.ig_dialog_ad_block_ads), "isAdBlockEnabled"),
                createSwitch(getString(R.string.ig_dialog_ad_block_analytics), "isAnalyticsBlocked"),
                createSwitch(getString(R.string.ig_dialog_ad_disable_tracking), "disableTrackingLinks")
        ));

        showMenu(getString(R.string.ig_dialog_section_ad_analytics), defs);
        currentMenu = "ads";
    }

    private void loadCleanFeedMenu() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createSwitch(getString(R.string.ig_dialog_clean_feed_hide_suggested), "hideSuggestionsInFeed")
        ));

        showMenu(getString(R.string.ig_dialog_section_clean_feed), defs);
        currentMenu = "cleanfeed";
    }

    private void loadDistractionMenu() {
        List<Object> defs = new ArrayList<>();

        List<String> distractionKeys = Arrays.asList(
                "disableStories", "disableFeed", "disableReels",
                "disableReelsExceptDM", "disableExplore", "disableComments"
        );

        // Extreme Mode: only enabled once the user has selected at least one feature
        FeatureItem extreme = createSwitch(getString(R.string.ig_dialog_distraction_extreme_mode), "isExtremeMode");
        extreme.textColor = 0xFFFF453A;
        extreme.isExtreme = true;
        extreme.requiresAnyOf = distractionKeys;

        defs.add(getString(R.string.feat_danger_zone));
        defs.add(Arrays.asList(extreme));

        // Master switch and all feature toggles: locked once extreme mode is active
        FeatureItem masterSwitch = createMasterSwitch(getString(R.string.ig_dialog_enable_disable_all), distractionKeys);
        masterSwitch.disabledWhenTrue = "isExtremeMode";

        FeatureItem disableReels = createSwitchWithCascadeOff(getString(R.string.ig_dialog_distraction_disable_reels), "disableReels", "disableReelsExceptDM");
        disableReels.disabledWhenTrue = "isExtremeMode";

        FeatureItem disableReelsExceptDM = createSwitchWithDependency(getString(R.string.ig_dialog_distraction_disable_reels_except_dm), "disableReelsExceptDM", "disableReels");
        disableReelsExceptDM.disabledWhenTrue = "isExtremeMode";

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                masterSwitch,
                createSwitchLockedByExtreme(getString(R.string.ig_dialog_distraction_disable_stories), "disableStories"),
                createSwitchLockedByExtreme(getString(R.string.ig_dialog_distraction_disable_feed), "disableFeed"),
                disableReels,
                disableReelsExceptDM,
                createSwitchLockedByExtreme(getString(R.string.ig_dialog_distraction_disable_explore), "disableExplore"),
                createSwitchLockedByExtreme(getString(R.string.ig_dialog_distraction_disable_comments), "disableComments")
        ));

        showMenu(getString(R.string.ig_dialog_section_distraction_free), defs);
        currentMenu = "distract";
    }

    private FeatureItem createSwitchLockedByExtreme(String title, String prefKey) {
        FeatureItem item = createSwitch(title, prefKey);
        item.disabledWhenTrue = "isExtremeMode";
        return item;
    }

    private void loadMiscMenu() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createMasterSwitch(getString(R.string.ig_dialog_enable_disable_all), Arrays.asList(
                        "disableStoryFlipping", "disableVideoAutoPlay", "disableRepost", "showFollowerToast",
                        "showFeatureToasts", "enableStoryMentions", "disableDiscoverPeople", "enableCopyComment"
                )),
                createSwitch(getString(R.string.ig_dialog_misc_disable_story_autoswipe), "disableStoryFlipping"),
                createSwitch(getString(R.string.ig_dialog_misc_disable_video_autoplay), "disableVideoAutoPlay"),
                createSwitch(getString(R.string.ig_dialog_misc_disable_repost), "disableRepost"),
                createSwitch(getString(R.string.ig_dialog_misc_show_follower_toast), "showFollowerToast"),
                createSwitch(getString(R.string.ig_dialog_misc_show_feature_toasts), "showFeatureToasts"),
                createSwitch(getString(R.string.ig_dialog_misc_view_story_mentions), "enableStoryMentions"),
                createSwitch(getString(R.string.ig_dialog_misc_disable_discover_people), "disableDiscoverPeople"),
                createSwitch(getString(R.string.ig_dialog_misc_copy_comment), "enableCopyComment")
        ));

        showMenu(getString(R.string.ig_dialog_section_misc), defs);
        currentMenu = "misc";
    }

    private void loadDownloaderMenu() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createMasterSwitch(getString(R.string.ig_dialog_enable_disable_all), Arrays.asList(
                        "enablePostDownload", "enableStoryDownload", "enableReelDownload", "enableProfileDownload"
                )),
                createSwitch(getString(R.string.ig_dialog_downloader_posts), "enablePostDownload"),
                createSwitch(getString(R.string.ig_dialog_downloader_stories), "enableStoryDownload"),
                createSwitch(getString(R.string.ig_dialog_downloader_reels), "enableReelDownload"),
                createSwitch(getString(R.string.ig_dialog_downloader_profiles), "enableProfileDownload")
        ));

        defs.add(getString(R.string.feat_options));
        defs.add(Arrays.asList(
                createSwitch(getString(R.string.ig_dialog_downloader_username_subfolder), "downloaderUsernameFolder"),
                createSwitch(getString(R.string.ig_dialog_downloader_add_timestamp), "downloaderAddTimestamp")
        ));

        String customPath = localCache.getString("downloaderCustomPath", "");
        String folderTitle = customPath.isEmpty()
                ? getString(R.string.feat_downloader_set_folder)
                : getString(R.string.feat_downloader_selected, customPath);

        defs.add(getString(R.string.feat_download_folder));
        defs.add(Arrays.asList(
                createClickable(folderTitle, 0, this::pickDownloadFolder),
                createClickable(getString(R.string.feat_downloader_reset_folder), 0xFFFF453A, this::resetDownloadFolder)
        ));

        showMenu(getString(R.string.ig_dialog_section_downloader), defs);
        currentMenu = "downloader";
    }

    // =========================================================
    // TOOLS ACTIONS & HANDLERS
    // =========================================================

    /**
     * Makes the localCache SharedPreferences file world-readable so the module can
     * access it via XSharedPreferences on a cold Instagram start (when the sync
     * broadcast was never delivered because Instagram wasn't running at the time).
     * Apps are allowed to change permissions on their own files.
     */
    private void makeLocalCacheWorldReadable() {
        try {
            java.io.File prefsFile = new java.io.File(
                    requireContext().getApplicationInfo().dataDir + "/shared_prefs/instaeclipse_cache.xml");
            prefsFile.setReadable(true, false);
        } catch (Throwable ignored) {}
    }

    private void pickDownloadFolder() {
        dirPickerLauncher.launch(null);
    }

    private void resetDownloadFolder() {
        SharedPreferences.Editor editor = localCache.edit();
        editor.remove("pathJustSetLocally");
        editor.putString("downloaderCustomUri", "");
        editor.putString("downloaderCustomPath", "");
        editor.commit();
        makeLocalCacheWorldReadable();

        Intent intentUri = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
        intentUri.putExtra("key", "downloaderCustomUri");
        intentUri.putExtra("value", "");
        requireContext().sendBroadcast(intentUri);

        Intent intentPath = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
        intentPath.putExtra("key", "downloaderCustomPath");
        intentPath.putExtra("value", "");
        requireContext().sendBroadcast(intentPath);

        Toast.makeText(requireContext(), getString(R.string.ig_toast_download_folder_reset), Toast.LENGTH_SHORT).show();

        if ("downloader".equals(currentMenu)) {
            loadDownloaderMenu();
        }
    }

    private void backupSettings() {
        try {
            JSONObject settings = new JSONObject();
            Map<String, ?> all = localCache.getAll();
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                if (entry.getValue() instanceof Boolean) {
                    settings.put(entry.getKey(), entry.getValue());
                }
            }
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("settings", settings);
            Intent exportIntent = new Intent();
            exportIntent.setComponent(new ComponentName(requireContext(), "ps.reso.instaeclipse.mods.devops.config.JsonExportActivity"));
            exportIntent.putExtra("json_content", root.toString(2));
            exportIntent.putExtra("file_name", "instaeclipse_settings.json");
            startActivity(exportIntent);
        } catch (JSONException e) {
            Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreSettings() {
        restoreFileLauncher.launch(new String[]{"application/json"});
    }

    private void restartInstagram() {
        Toast.makeText(requireContext(), getString(R.string.ig_toast_restart_manual), Toast.LENGTH_LONG).show();
    }

    private void importDevConfig() {
        Intent importIntent = new Intent();
        importIntent.setComponent(new ComponentName(requireContext(), "ps.reso.instaeclipse.mods.devops.config.JsonImportActivity"));
        importIntent.putExtra("target_package", "com.instagram.android");
        startActivity(importIntent);
    }

    private void exportDevConfig() {
        BroadcastReceiver configReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                context.unregisterReceiver(this);
                Activity activity = getActivity();
                if (activity == null || activity.isFinishing()) return;
                String error = intent.getStringExtra("error");
                if (error != null) {
                    Toast.makeText(activity, error, Toast.LENGTH_SHORT).show();
                    return;
                }
                String json = intent.getStringExtra("json_content");
                if (json == null || json.isEmpty()) {
                    Toast.makeText(activity, getString(R.string.export_no_config_data), Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent exportIntent = new Intent();
                exportIntent.setComponent(new ComponentName(activity, "ps.reso.instaeclipse.mods.devops.config.JsonExportActivity"));
                exportIntent.putExtra("json_content", json);
                startActivity(exportIntent);
            }
        };
        IntentFilter filter = new IntentFilter("ps.reso.instaeclipse.ACTION_SEND_CONFIG");
        if (Build.VERSION.SDK_INT >= 33) {
            requireContext().registerReceiver(configReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            ContextCompat.registerReceiver(requireContext(), configReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        }
        Intent request = new Intent("ps.reso.instaeclipse.ACTION_EXPORT_CONFIG");
        request.setPackage("com.instagram.android");
        requireContext().sendBroadcast(request);
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(requireContext())
                .setTitle("InstaEclipse 🌘")
                .setMessage("Created by @reso7200\n\nGitHub: https://github.com/ReSo7200/InstaEclipse\nTelegram: https://t.me/InstaEclipse")
                .setPositiveButton("Close", null)
                .show();
    }

    // =========================================================
    // STAGING LOGIC
    // =========================================================

    private void stageChange(String prefKey, boolean isChecked) {
        if (localCache.getBoolean(prefKey, false) == isChecked) {
            stagedChanges.remove(prefKey);
        } else {
            stagedChanges.put(prefKey, isChecked);
        }

        if (stagedChanges.isEmpty()) {
            fabSave.hide();
        } else {
            fabSave.show();
        }
    }

    private void commitStagedChanges() {
        if (stagedChanges.isEmpty()) return;

        SharedPreferences.Editor editor = localCache.edit();
        for (Map.Entry<String, Boolean> entry : stagedChanges.entrySet()) {
            String key = entry.getKey();
            boolean value = entry.getValue();

            editor.putBoolean(key, value);

            Intent intent = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF");
            intent.putExtra("key", key);
            intent.putExtra("value", value);
            requireContext().sendBroadcast(intent);
        }
        editor.apply();
        stagedChanges.clear();
        fabSave.hide();
        Toast.makeText(requireContext(), getString(R.string.ig_toast_settings_applied), Toast.LENGTH_SHORT).show();
    }

    private boolean getCurrentState(String prefKey) {
        if (prefKey == null) return false;
        if (stagedChanges.containsKey(prefKey)) return stagedChanges.get(prefKey);
        return localCache.getBoolean(prefKey, false);
    }

    // =========================================================
    // LIFECYCLE BROADCAST REGISTRATION
    // =========================================================

    @Override
    public void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter("ps.reso.instaeclipse.ACTION_SEND_PREFS");
        if (Build.VERSION.SDK_INT >= 33) {
            requireContext().registerReceiver(prefsReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            ContextCompat.registerReceiver(requireContext(), prefsReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
        }
        requireContext().sendBroadcast(new Intent("ps.reso.instaeclipse.ACTION_REQUEST_PREFS"));
    }

    @Override
    public void onPause() {
        super.onPause();
        requireContext().unregisterReceiver(prefsReceiver);
    }
}
