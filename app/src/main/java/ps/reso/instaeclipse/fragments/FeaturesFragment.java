package ps.reso.instaeclipse.fragments;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.ImageButton;
import android.widget.ImageView;
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.core.ExportBrand;
import ps.reso.instaeclipse.mods.location.LocationPickerActivity;
import ps.reso.instaeclipse.ui.theme.ThemeCustomizerActivity;

public class FeaturesFragment extends Fragment {

    private SharedPreferences localCache;
    private View fragmentView;
    private RecyclerView recyclerFeatures;
    private FeatureAdapter adapter;
    private TextView tvHeaderTitle;
    private ImageButton btnBack;
    private ExtendedFloatingActionButton fabSave;
    private TextInputLayout tilSearch;
    private TextInputEditText etSearch;
    /** True while the search text is being changed programmatically (skip re-filtering). */
    private boolean suppressSearchWatcher = false;
    private ActivityResultLauncher<Uri> dirPickerLauncher;
    private ActivityResultLauncher<String[]> restoreFileLauncher;
    private ActivityResultLauncher<String> notifPermLauncher;
    private ActivityResultLauncher<Intent> locationPickerLauncher;
    private ActivityResultLauncher<Intent> themeCustomizerLauncher;

    private String currentMenu = "main";

    // STAGING SYSTEM: Holds changes before applying
    private final Map<String, Boolean> stagedChanges = new HashMap<>();

    /** Nonce of the last ACTION_REQUEST_PREFS; replies without it are ignored (IpcSecurity). */
    private String prefsNonce;

    private final BroadcastReceiver prefsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("ps.reso.instaeclipse.ACTION_SEND_PREFS".equals(intent.getAction())
                    && ps.reso.instaeclipse.utils.core.IpcSecurity.nonceMatches(prefsNonce, intent)) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    // If we just set the path locally (via dirPickerLauncher), the incoming
                    // reply from Instagram may still carry the old value — skip overwriting.
                    boolean suppressPath = localCache.getBoolean("pathJustSetLocally", false);
                    SharedPreferences.Editor editor = localCache.edit();
                    if (suppressPath) editor.remove("pathJustSetLocally");
                    // Older builds stored the reply's nonce extra as if it were a setting.
                    editor.remove(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE);
                    // Instagram's values, minus the ones this app changed more recently (pending
                    // in Instagram) — see PrefsMerge. Also drops the nonce and stamp extras.
                    Map<String, Object> incoming = new HashMap<>();
                    for (String key : bundle.keySet()) incoming.put(key, bundle.get(key));
                    Map<String, Object> changes =
                            ps.reso.instaeclipse.utils.core.PrefsMerge.select(incoming, localCache.getAll());
                    for (Map.Entry<String, Object> change : changes.entrySet()) {
                        String key = change.getKey();
                        Object value = change.getValue();
                        if (value instanceof Boolean) {
                            editor.putBoolean(key, (Boolean) value);
                        } else if (value instanceof String) {
                            if (suppressPath && ("downloaderCustomPath".equals(key) || "downloaderCustomUri".equals(key))) {
                                continue;
                            }
                            editor.putString(key, (String) value);
                        } else if (value instanceof Integer) {
                            editor.putInt(key, (Integer) value);
                        }
                    }
                    editor.apply();
                    // Rebuild downloader/reels menus so the current value reflects immediately
                    if ("downloader".equals(currentMenu)) {
                        loadDownloaderMenu();
                    } else if ("reels".equals(currentMenu)) {
                        loadReelsMenu();
                    } else {
                        // Also recomputes the main-menu "N on" counts
                        refreshVisibleMenu();
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
                        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), intent);
                    }
                }
                editor.apply();
                refreshVisibleMenu();
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.ig_toast_settings_restored), Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

        notifPermLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {});

        locationPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result == null || result.getData() == null) return;
                    Intent data = result.getData();
                    double lat = data.getDoubleExtra(LocationPickerActivity.RESULT_LAT, 0.0);
                    double lng = data.getDoubleExtra(LocationPickerActivity.RESULT_LNG, 0.0);
                    SharedPreferences.Editor ed = localCache.edit();
                    ed.putString("spoofLat", String.valueOf(lat));
                    ed.putString("spoofLng", String.valueOf(lng));
                    ed.commit();
                    makeLocalCacheWorldReadable();

                    Intent b1 = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
                    b1.putExtra("key", "spoofLat");
                    b1.putExtra("value", String.valueOf(lat));
                    ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), b1);

                    Intent b2 = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
                    b2.putExtra("key", "spoofLng");
                    b2.putExtra("value", String.valueOf(lng));
                    ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), b2);

                    if ("location".equals(currentMenu)) loadLocationMenu();
                });

        themeCustomizerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if ("theme".equals(currentMenu)) loadThemeMenu();
                });

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
                ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), intentUri);

                Intent intentPath = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
                intentPath.putExtra("key", "downloaderCustomPath");
                intentPath.putExtra("value", path);
                ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), intentPath);

                ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.ig_toast_download_folder_updated), Toast.LENGTH_SHORT).show();

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
        localCache = ps.reso.instaeclipse.utils.core.CommonUtils.companionCache(requireContext());

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

        tilSearch = fragmentView.findViewById(R.id.til_search);
        etSearch = fragmentView.findViewById(R.id.et_search);
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (suppressSearchWatcher) return;
                runSearch(s.toString());
            }
        });
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                hideKeyboard();
                etSearch.clearFocus();
                return true;
            }
            return false;
        });
        // Dismiss the keyboard once the user starts scrolling through results
        recyclerFeatures.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING && etSearch.hasFocus()) {
                    hideKeyboard();
                    etSearch.clearFocus();
                }
            }
        });

        btnBack.setOnClickListener(v -> loadMainMenu());

        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(), new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (!"main".equals(currentMenu)) {
                    loadMainMenu();
                } else if (isSearching()) {
                    // Back while searching clears the query (the watcher restores the main menu)
                    etSearch.setText("");
                    etSearch.clearFocus();
                    hideKeyboard();
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
        public int iconRes;
        public int accentColor;
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

        /** Secondary line under the title (e.g. "3 on" on main-menu category rows). */
        public String subtitle;
        /** Subtitle drawn in the accent (primary) colour instead of the muted variant colour. */
        public boolean subtitleActive;
        /** Row navigates into a sub-menu: shows a trailing chevron. */
        public boolean isNav;
        /** Main-menu row: rendered as a standalone rounded card. */
        public boolean isCategory;
        /** Sub-menu this main-menu row opens (used to recompute its count). */
        Category category;
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
        TextView tvSubtitle;
        ImageView ivIcon;
        ImageView ivChevron;
        MaterialSwitch swToggle;
        MaterialCardView cardView;
        ItemViewHolder(View v) {
            super(v);
            tvTitle = v.findViewById(R.id.tv_title);
            tvSubtitle = v.findViewById(R.id.tv_subtitle);
            ivChevron = v.findViewById(R.id.iv_chevron);
            ivIcon = v.findViewById(R.id.iv_icon);
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
                bindIcon(itemHolder.ivIcon, item.iconRes, item.accentColor);

                // Master switches get a tinted card + bold text to stand out from regular items
                boolean isMaster = item.type == FeatureItem.TYPE_MASTER_SWITCH;
                if (isMaster) {
                    int bg = MaterialColors.getColor(itemHolder.itemView, com.google.android.material.R.attr.colorSecondaryContainer);
                    int fg = MaterialColors.getColor(itemHolder.itemView, com.google.android.material.R.attr.colorOnSecondaryContainer);
                    itemHolder.cardView.setCardBackgroundColor(bg);
                    itemHolder.tvTitle.setTextColor(fg);
                    itemHolder.tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);
                } else {
                    int bg = MaterialColors.getColor(itemHolder.itemView, item.isCategory
                            ? com.google.android.material.R.attr.colorSurfaceContainer
                            : com.google.android.material.R.attr.colorSurfaceContainerLow);
                    int defaultTextColor = MaterialColors.getColor(itemHolder.itemView, com.google.android.material.R.attr.colorOnSurface);
                    itemHolder.cardView.setCardBackgroundColor(bg);
                    itemHolder.tvTitle.setTextColor(item.textColor != 0 ? item.textColor : defaultTextColor);
                    itemHolder.tvTitle.setTypeface(null, android.graphics.Typeface.NORMAL);
                }

                float largeRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, getResources().getDisplayMetrics());
                float smallRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, getResources().getDisplayMetrics());

                ShapeAppearanceModel.Builder shapeBuilder = itemHolder.cardView.getShapeAppearanceModel().toBuilder();

                // Subtitle (category counts) + trailing chevron for rows that open a sub-menu
                if (item.subtitle != null) {
                    itemHolder.tvSubtitle.setVisibility(View.VISIBLE);
                    itemHolder.tvSubtitle.setText(item.subtitle);
                    itemHolder.tvSubtitle.setTextColor(MaterialColors.getColor(itemHolder.itemView, item.subtitleActive
                            ? androidx.appcompat.R.attr.colorPrimary
                            : com.google.android.material.R.attr.colorOnSurfaceVariant));
                } else {
                    itemHolder.tvSubtitle.setVisibility(View.GONE);
                }
                itemHolder.ivChevron.setVisibility(item.isNav ? View.VISIBLE : View.GONE);

                // Main-menu rows are standalone rounded cards with a small gap; sub-menu rows stay segmented
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) itemHolder.itemView.getLayoutParams();
                int wantBottom = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                        item.isCategory ? 6f : 2f, getResources().getDisplayMetrics());
                if (lp.bottomMargin != wantBottom) {
                    lp.bottomMargin = wantBottom;
                    itemHolder.itemView.setLayoutParams(lp);
                }

                if (item.isCategory || item.segmentSize == 1) {
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
                            new MaterialAlertDialogBuilder(itemHolder.itemView.getContext())
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
                                    // Back / tap outside = Cancel, so the switch never shows on while it isn't
                                    .setOnCancelListener(dialog -> itemHolder.swToggle.setChecked(false))
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

        private void bindIcon(ImageView iconView, int iconRes, int accentColor) {
            if (iconRes == 0) {
                iconView.setVisibility(View.GONE);
                return;
            }
            iconView.setVisibility(View.VISIBLE);
            android.graphics.drawable.Drawable icon = ContextCompat.getDrawable(iconView.getContext(), iconRes);
            if (icon != null) {
                icon = icon.mutate();
                icon.setColorFilter(new android.graphics.PorterDuffColorFilter(accentColor, android.graphics.PorterDuff.Mode.SRC_IN));
                iconView.setImageDrawable(icon);
            }
            android.graphics.drawable.GradientDrawable chipBg = new android.graphics.drawable.GradientDrawable();
            chipBg.setColor((accentColor & 0x00FFFFFF) | 0x33000000);
            chipBg.setCornerRadius(iconView.getResources().getDisplayMetrics().density * 10);
            iconView.setBackground(chipBg);
        }
    }

    // =========================================================
    // MENU BUILDING HELPERS
    // =========================================================

    private void showMenu(String title, List<Object> definitions) {
        showMenu(title, definitions, true);
    }

    /** @param animate false for live search updates (no header transition / list slide-in per keystroke). */
    private void showMenu(String title, List<Object> definitions, boolean animate) {
        boolean isMain = title.equals(getString(R.string.features));
        if (animate) {
            ViewGroup topBar = fragmentView.findViewById(R.id.top_bar);
            TransitionManager.beginDelayedTransition(topBar, new AutoTransition().setDuration(200));
        }

        tvHeaderTitle.setText(title);
        btnBack.setVisibility(isMain ? View.GONE : View.VISIBLE);
        // Search lives on the main menu only; leaving it (e.g. via a nav row in the results) resets it
        tilSearch.setVisibility(isMain ? View.VISIBLE : View.GONE);
        if (!isMain) clearSearchSilently();

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

        if (!animate) {
            recyclerFeatures.animate().cancel();
            recyclerFeatures.setAlpha(1f);
            recyclerFeatures.setTranslationY(0f);
            adapter.setItems(displayList);
            recyclerFeatures.scrollToPosition(0);
            return;
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
        item.isNav = true;
        return item;
    }

    private FeatureItem createNav(int iconRes, String accentHex, String title, Runnable navAction) {
        FeatureItem item = createNav(title, navAction);
        item.iconRes = iconRes;
        item.accentColor = Color.parseColor(accentHex);
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

    private FeatureItem createClickable(int iconRes, String accentHex, String title, Runnable onClick) {
        FeatureItem item = createClickable(title, Color.parseColor(accentHex), onClick);
        item.iconRes = iconRes;
        item.accentColor = Color.parseColor(accentHex);
        return item;
    }

    /** Tools action row: own accent, normal title colour, a one-line description and an arrow. */
    private FeatureItem createTool(int iconRes, String accentHex, String title, String subtitle, Runnable onClick) {
        FeatureItem item = createNav(iconRes, accentHex, title, onClick);
        item.subtitle = subtitle;
        return item;
    }

    private FeatureItem createSwitch(String title, String prefKey) {
        FeatureItem item = new FeatureItem();
        item.type = FeatureItem.TYPE_SWITCH;
        item.title = title;
        item.prefKey = prefKey;
        return item;
    }

    private FeatureItem createSwitch(int iconRes, String accentHex, String title, String prefKey) {
        FeatureItem item = createSwitch(title, prefKey);
        item.iconRes = iconRes;
        item.accentColor = Color.parseColor(accentHex);
        return item;
    }

    private FeatureItem createSwitchWithDependency(String title, String prefKey, String dependsOn) {
        FeatureItem item = createSwitch(title, prefKey);
        item.dependsOn = dependsOn;
        return item;
    }

    private FeatureItem createSwitchWithDependency(int iconRes, String accentHex, String title, String prefKey, String dependsOn) {
        FeatureItem item = createSwitchWithDependency(title, prefKey, dependsOn);
        item.iconRes = iconRes;
        item.accentColor = Color.parseColor(accentHex);
        return item;
    }

    private FeatureItem createSwitchWithCascadeOff(String title, String prefKey, String cascadeOffKey) {
        FeatureItem item = createSwitch(title, prefKey);
        item.cascadeOffKey = cascadeOffKey;
        return item;
    }

    private FeatureItem createSwitchWithCascadeOff(int iconRes, String accentHex, String title, String prefKey, String cascadeOffKey) {
        FeatureItem item = createSwitchWithCascadeOff(title, prefKey, cascadeOffKey);
        item.iconRes = iconRes;
        item.accentColor = Color.parseColor(accentHex);
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

    private static final String A_APPEARANCE = "#FF375F", A_PRIVACY = "#5E5CE6", A_MEDIA = "#FF9F0A", A_SOCIAL = "#BF5AF2", A_TOOLS = "#8E8E93";
    private static final int GROUP_APPEARANCE = 0, GROUP_PRIVACY = 1, GROUP_MEDIA = 2, GROUP_SOCIAL = 3, GROUP_TOOLS = 4;
    /** Searchable category that is not listed on the main menu (reached from inside another sub-menu). */
    private static final int GROUP_HIDDEN = -1;

    /** Builds a sub-menu's definitions (section header Strings + List of FeatureItem) without showing it. */
    private interface DefsBuilder {
        List<Object> build();
    }

    /** One sub-menu: used for the main-menu nav row, its "N on" count, and global search. */
    static final class Category {
        final String key;
        final int group;
        final int iconRes;
        final String accentHex;
        final String title;
        final DefsBuilder builder;
        final Runnable loader;

        Category(String key, int group, int iconRes, String accentHex, String title, DefsBuilder builder, Runnable loader) {
            this.key = key;
            this.group = group;
            this.iconRes = iconRes;
            this.accentHex = accentHex;
            this.title = title;
            this.builder = builder;
            this.loader = loader;
        }
    }

    /** All sub-menus, in main-menu order. Grouped to match the in-IG dialog: Appearance / Privacy / Media / Social / Tools. */
    private List<Category> getCategories() {
        List<Category> c = new ArrayList<>();
        c.add(new Category("theme", GROUP_APPEARANCE, R.drawable.ic_palette, A_APPEARANCE, getString(R.string.ig_dialog_menu_theme), this::buildThemeDefs, this::loadThemeMenu));
        c.add(new Category("feed", GROUP_APPEARANCE, R.drawable.ic_sparkle, A_APPEARANCE, getString(R.string.menu_feed), this::buildFeedDefs, this::loadFeedMenu));
        c.add(new Category("navigation", GROUP_APPEARANCE, R.drawable.ic_tune, A_APPEARANCE, getString(R.string.menu_navigation), this::buildNavigationDefs, this::loadNavigationMenu));
        c.add(new Category("reels", GROUP_APPEARANCE, R.drawable.ic_movie, A_APPEARANCE, getString(R.string.menu_reels), this::buildReelsDefs, this::loadReelsMenu));
        c.add(new Category("stories", GROUP_APPEARANCE, R.drawable.ic_story_ring, A_APPEARANCE, getString(R.string.menu_stories), this::buildStoriesDefs, this::loadStoriesMenu));

        c.add(new Category("ghost", GROUP_PRIVACY, R.drawable.ic_eye, A_PRIVACY, getString(R.string.ig_dialog_menu_ghost_settings), this::buildGhostDefs, this::loadGhostMenu));
        c.add(new Category("messages", GROUP_PRIVACY, R.drawable.ic_chat, A_PRIVACY, getString(R.string.menu_messages), this::buildMessagesDefs, this::loadMessagesMenu));
        c.add(new Category("hidechats", GROUP_PRIVACY, R.drawable.ic_eye_off, A_PRIVACY, getString(R.string.ig_hide_chats_title), this::buildHideChatsDefs, this::loadHideChatsMenu));
        c.add(new Category("lock", GROUP_PRIVACY, R.drawable.ic_shield, A_PRIVACY, getString(R.string.ig_dialog_misc_lock_section), this::buildLockDefs, this::loadLockMenu));
        c.add(new Category("ads", GROUP_PRIVACY, R.drawable.ic_block, A_PRIVACY, getString(R.string.menu_ads_tracking), this::buildAdsDefs, this::loadAdsMenu));
        c.add(new Category("location", GROUP_PRIVACY, R.drawable.ic_pin, A_PRIVACY, getString(R.string.ig_dialog_menu_location), this::buildLocationDefs, this::loadLocationMenu));
        c.add(new Category("distract", GROUP_PRIVACY, R.drawable.ic_notification, A_PRIVACY, getString(R.string.ig_dialog_menu_distraction_free), this::buildDistractionDefs, this::loadDistractionMenu));

        c.add(new Category("downloader", GROUP_MEDIA, R.drawable.ic_download, A_MEDIA, getString(R.string.ig_dialog_menu_downloader), this::buildDownloaderDefs, this::loadDownloaderMenu));
        c.add(new Category("postmenu", GROUP_MEDIA, R.drawable.ic_save, A_MEDIA, getString(R.string.menu_post_menu), this::buildPostMenuDefs, this::loadPostMenuMenu));

        c.add(new Category("interactions", GROUP_SOCIAL, R.drawable.ic_heart, A_SOCIAL, getString(R.string.menu_likes_comments), this::buildInteractionsDefs, this::loadInteractionsMenu));
        c.add(new Category("profiles", GROUP_SOCIAL, R.drawable.ic_eye, A_SOCIAL, getString(R.string.menu_profiles), this::buildProfilesDefs, this::loadProfilesMenu));

        c.add(new Category("app", GROUP_TOOLS, R.drawable.ic_settings_gear, A_TOOLS, getString(R.string.menu_app), this::buildAppDefs, this::loadAppMenu));
        c.add(new Category("dev", GROUP_TOOLS, R.drawable.ic_tune, A_TOOLS, getString(R.string.ig_dialog_menu_dev_options), this::buildDevDefs, this::loadDevMenu));

        // Quick toggles live inside Ghost Mode; searchable, but not a main-menu row.
        c.add(new Category("qt", GROUP_HIDDEN, R.drawable.ic_tune, A_PRIVACY, getString(R.string.ig_dialog_section_quick_toggle), this::buildQuickTogglesDefs, this::loadQuickTogglesMenu));
        return c;
    }

    private void loadMainMenu() {
        showMenu(getString(R.string.features), buildMainDefs());
        currentMenu = "main";
    }

    private List<Object> buildMainDefs() {
        List<Object> defs = new ArrayList<>();
        int[] groupTitles = {
                R.string.feat_group_appearance, R.string.feat_group_privacy,
                R.string.feat_group_media, R.string.feat_group_social, R.string.feat_group_tools
        };
        List<Category> categories = getCategories();

        for (int g = 0; g < groupTitles.length; g++) {
            List<FeatureItem> rows = new ArrayList<>();
            for (Category cat : categories) {
                if (cat.group != g) continue;
                FeatureItem nav = createNav(cat.iconRes, cat.accentHex, cat.title, cat.loader);
                nav.category = cat;
                applyCategorySubtitle(nav);
                rows.add(nav);
            }
            if (g == GROUP_TOOLS) {
                rows.add(createTool(R.drawable.ic_save, "#30D158", getString(R.string.ig_dialog_backup_settings), getString(R.string.tool_backup_sub), this::backupSettings));
                rows.add(createTool(R.drawable.ic_folder, "#0A84FF", getString(R.string.ig_dialog_restore_settings), getString(R.string.tool_restore_sub), this::restoreSettings));
                rows.add(createTool(R.drawable.ic_restart, "#FF9F0A", getString(R.string.ig_dialog_menu_restart), getString(R.string.tool_restart_sub), this::restartInstagram));
                rows.add(createTool(R.drawable.ic_info, "#BF5AF2", getString(R.string.ig_dialog_menu_about), getString(R.string.tool_about_sub), this::showAboutDialog));
            }
            for (FeatureItem row : rows) row.isCategory = true;
            defs.add(getString(groupTitles[g]));
            defs.add(rows);
        }
        return defs;
    }

    /** Flattens a builder's definitions into its FeatureItems (section headers dropped). */
    private static List<FeatureItem> flattenItems(List<Object> defs) {
        List<FeatureItem> out = new ArrayList<>();
        for (Object def : defs) {
            if (def instanceof List) {
                for (Object o : (List<?>) def) {
                    if (o instanceof FeatureItem) out.add((FeatureItem) o);
                }
            }
        }
        return out;
    }

    /**
     * Sets the "N on" / "Off" subtitle of a main-menu category row: the number of plain boolean
     * switches (master switches excluded, keys de-duplicated) in that sub-menu that are currently on,
     * including staged-but-unapplied changes. Sub-menus without switches get no subtitle.
     */
    private void applyCategorySubtitle(FeatureItem nav) {
        Category cat = nav.category;
        if (cat == null) return;
        Set<String> seen = new HashSet<>();
        int on = 0;
        for (FeatureItem fi : flattenItems(cat.builder.build())) {
            if (fi.type != FeatureItem.TYPE_SWITCH || fi.prefKey == null || !seen.add(fi.prefKey)) continue;
            if (getCurrentState(fi.prefKey)) on++;
        }
        if (seen.isEmpty()) {
            nav.subtitle = null;
            nav.subtitleActive = false;
        } else {
            nav.subtitle = on > 0 ? getString(R.string.feat_count_on, on) : getString(R.string.feat_count_off);
            nav.subtitleActive = on > 0;
        }
        // Navigation: a changed tab order is a choice, not a switch, so it is noted after the count.
        if ("navigation".equals(cat.key) && nav.subtitle != null
                && ps.reso.instaeclipse.mods.ui.DistractionFreeUIHook.isCustomTabOrder(localCache.getString("navTabOrder", ""))) {
            nav.subtitle = nav.subtitle + " · " + getString(R.string.nav_tab_order_changed);
            nav.subtitleActive = true;
        }
    }

    /** Recomputes the category counts when the main menu is showing, then rebinds the visible rows. */
    private void refreshVisibleMenu() {
        if (adapter == null || localCache == null || !isAdded()) return;
        if ("main".equals(currentMenu)) {
            for (FeatureItem fi : adapter.items) {
                if (fi.category != null) applyCategorySubtitle(fi);
            }
        }
        adapter.notifyDataSetChanged();
    }

    // =========================================================
    // SEARCH
    // =========================================================

    private boolean isSearching() {
        return etSearch != null && etSearch.getText() != null && etSearch.getText().toString().trim().length() > 0;
    }

    /** Clears the search field without re-rendering (used when navigating away from the main menu). */
    private void clearSearchSilently() {
        if (etSearch == null) return;
        if (etSearch.getText() != null && etSearch.getText().length() > 0) {
            suppressSearchWatcher = true;
            etSearch.setText("");
            suppressSearchWatcher = false;
        }
        if (etSearch.hasFocus()) {
            etSearch.clearFocus();
            hideKeyboard();
        }
    }

    private void hideKeyboard() {
        if (etSearch == null) return;
        InputMethodManager imm = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }

    /**
     * Searches every switch / clickable row of every sub-menu. Rows come straight from the same
     * builders the sub-menus use, so toggling here goes through the exact same staging, dependsOn,
     * cascade-off and locked-by-extreme logic. A query matching a category name lists all its rows.
     */
    private void runSearch(String raw) {
        String q = raw == null ? "" : raw.trim().toLowerCase(Locale.getDefault());
        if (q.isEmpty()) {
            loadMainMenu();
            return;
        }
        List<Object> defs = new ArrayList<>();
        for (Category cat : getCategories()) {
            boolean categoryMatch = cat.title.toLowerCase(Locale.getDefault()).contains(q);
            List<FeatureItem> hits = new ArrayList<>();
            for (FeatureItem fi : flattenItems(cat.builder.build())) {
                if (fi.type != FeatureItem.TYPE_SWITCH && fi.type != FeatureItem.TYPE_CLICKABLE) continue;
                if (fi.title == null) continue;
                if (categoryMatch || fi.title.toLowerCase(Locale.getDefault()).contains(q)) hits.add(fi);
            }
            if (!hits.isEmpty()) {
                defs.add(cat.title);
                defs.add(hits);
            }
        }
        if (defs.isEmpty()) defs.add(getString(R.string.feat_search_no_results));
        showMenu(getString(R.string.features), defs, false);
        currentMenu = "main";
    }

    private void loadDevMenu() {
        showMenu(getString(R.string.ig_dialog_section_dev_options), buildDevDefs());
        currentMenu = "dev";
    }

    private List<Object> buildDevDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(createSwitch(R.drawable.ic_tune, "#0A84FF", getString(R.string.ig_dialog_dev_enable), "isDevEnabled")));

        defs.add(getString(R.string.feat_config));
        defs.add(Arrays.asList(
                createClickable(R.drawable.ic_download, "#30D158", getString(R.string.ig_dialog_dev_import), this::importDevConfig),
                createClickable(R.drawable.ic_upload, "#0A84FF", getString(R.string.ig_dialog_dev_export), this::exportDevConfig),
                createClickable(R.drawable.ic_restart, "#FF9F0A", getString(R.string.ig_dialog_dev_restore_default_config), this::restoreDefaultConfig)
        ));

        defs.add(getString(R.string.feat_options));
        defs.add(Arrays.asList(createSwitch(R.drawable.ic_block, "#FF453A", getString(R.string.ig_dialog_dev_remove_build_expired), "removeBuildExpiredPopup")));

        return defs;
    }

    private void loadGhostMenu() {
        showMenu(getString(R.string.ig_dialog_section_ghost_mode), buildGhostDefs());
        currentMenu = "ghost";
    }

    private List<Object> buildGhostDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_quick_toggle));
        defs.add(Arrays.asList(createNav(R.drawable.ic_tune, "#5E5CE6", getString(R.string.ig_dialog_customize_quick_toggle), this::loadQuickTogglesMenu)));

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createMasterSwitch(getString(R.string.ig_dialog_enable_disable_all), Arrays.asList(
                        "isGhostSeen", "isGhostTyping", "isGhostStory", "isGhostLive",
                        "isGhostScreenshot", "allowScreenshots", "isGhostViewOnce", "permanentViewMode"
                )),
                createSwitch(R.drawable.ic_eye_off, "#5E5CE6", getString(R.string.ig_dialog_ghost_hide_dm_seen), "isGhostSeen"),
                createSwitch(R.drawable.ic_chat, "#5E5CE6", getString(R.string.ig_dialog_ghost_hide_typing), "isGhostTyping"),
                createSwitch(R.drawable.ic_story_ring, "#5E5CE6", getString(R.string.ig_dialog_ghost_hide_story_views), "isGhostStory"),
                createSwitch(R.drawable.ic_live, "#5E5CE6", getString(R.string.ig_dialog_ghost_hide_live_presence), "isGhostLive"),
                createSwitch(R.drawable.ic_camera, "#5E5CE6", getString(R.string.ig_dialog_ghost_bypass_screenshot), "isGhostScreenshot"),
                createSwitch(R.drawable.ic_camera, "#5E5CE6", getString(R.string.ig_dialog_ghost_allow_screenshots_dms), "allowScreenshots"),
                createSwitch(R.drawable.ic_eye_off, "#5E5CE6", getString(R.string.ig_dialog_ghost_hide_view_once), "isGhostViewOnce"),
                createSwitch(R.drawable.ic_eye, "#5E5CE6", getString(R.string.ig_dialog_ghost_permanent_view_once), "permanentViewMode")
        ));

        // Outside the master switch
        defs.add(getString(R.string.feat_options));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_eye, "#5E5CE6", getString(R.string.ig_dialog_ghost_read_receipt_chats), "readReceiptChats"),
                createSwitch(R.drawable.ic_timer, "#5E5CE6", getString(R.string.ig_dialog_misc_spoof_last_seen), "spoofLastSeen"),
                createExtrasSwitch("#5E5CE6", "airplaneMode")
        ));

        return defs;
    }

    /** A switch for an {@link ps.reso.instaeclipse.utils.feature.ExtrasFeatures} flag, with its label and icon. */
    private FeatureItem createExtrasSwitch(String accentHex, String flag) {
        ps.reso.instaeclipse.utils.feature.ExtrasFeatures.Entry e = ps.reso.instaeclipse.utils.feature.ExtrasFeatures.entry(flag);
        if (e == null) throw new IllegalArgumentException("Not an Extras flag: " + flag);
        return createSwitch(e.icon, accentHex, getString(e.label), flag);
    }

    private void loadMessagesMenu() {
        showMenu(getString(R.string.menu_messages), buildMessagesDefs());
        currentMenu = "messages";
    }

    private List<Object> buildMessagesDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_timer, "#5E5CE6", getString(R.string.ig_dialog_ghost_keep_disappearing), "keepEphemeralMessages"),
                createSwitch(R.drawable.ic_chat, "#5E5CE6", getString(R.string.ig_dialog_ghost_keep_unsent), "keepUnsentMessages"),
                createSwitch(R.drawable.ic_chat, "#5E5CE6", getString(R.string.ig_dialog_ghost_edit_history), "keepEditHistory"),
                createSwitch(R.drawable.ic_jump_first, "#5E5CE6", getString(R.string.ig_dialog_ghost_jump_first), "jumpToFirstMessage"),
                createSwitch(R.drawable.ic_chat, "#5E5CE6", getString(R.string.ig_dialog_translate_messages), "translateMessages"),
                createSwitch(R.drawable.ic_download, "#5E5CE6", getString(R.string.ig_dialog_ghost_chat_export), "chatExport"),
                createSwitch(R.drawable.ic_block, "#5E5CE6", getString(R.string.ig_dialog_distraction_hide_notes_tray), "hideNotesTray"),
                createSwitch(R.drawable.ic_block, "#5E5CE6", getString(R.string.ig_dialog_hide_dm_blend), "hideDmBlend"),
                createSwitch(R.drawable.ic_block, "#5E5CE6", getString(R.string.ig_dialog_misc_hide_share_create_group), "hideShareCreateGroup")
        ));

        return defs;
    }

    private void loadQuickTogglesMenu() {
        showMenu(getString(R.string.ig_dialog_section_quick_toggle), buildQuickTogglesDefs());
        currentMenu = "qt";
    }

    private List<Object> buildQuickTogglesDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createMasterSwitch(getString(R.string.ig_dialog_enable_disable_all), Arrays.asList(
                        "quickToggleSeen", "quickToggleTyping", "quickToggleScreenshot",
                        "quickToggleViewOnce", "quickToggleStory", "quickToggleLive",
                        "quickToggleEphemeral", "quickTogglePermanentView",
                        "quickToggleAllowScreenshots"
                )),
                createSwitch(R.drawable.ic_eye_off, "#5E5CE6", getString(R.string.ig_dialog_quick_hide_seen), "quickToggleSeen"),
                createSwitch(R.drawable.ic_chat, "#5E5CE6", getString(R.string.ig_dialog_quick_hide_typing), "quickToggleTyping"),
                createSwitch(R.drawable.ic_camera, "#5E5CE6", getString(R.string.ig_dialog_quick_disable_screenshot), "quickToggleScreenshot"),
                createSwitch(R.drawable.ic_eye_off, "#5E5CE6", getString(R.string.ig_dialog_quick_hide_view_once), "quickToggleViewOnce"),
                createSwitch(R.drawable.ic_story_ring, "#5E5CE6", getString(R.string.ig_dialog_quick_hide_story_seen), "quickToggleStory"),
                createSwitch(R.drawable.ic_live, "#5E5CE6", getString(R.string.ig_dialog_quick_hide_live_seen), "quickToggleLive"),
                createSwitch(R.drawable.ic_timer, "#5E5CE6", getString(R.string.ig_dialog_quick_keep_ephemeral), "quickToggleEphemeral"),
                createSwitch(R.drawable.ic_eye, "#5E5CE6", getString(R.string.ig_dialog_quick_permanent_view), "quickTogglePermanentView"),
                createSwitch(R.drawable.ic_camera, "#5E5CE6", getString(R.string.ig_dialog_quick_allow_screenshots), "quickToggleAllowScreenshots")
        ));

        return defs;
    }

    private void loadAdsMenu() {
        showMenu(getString(R.string.menu_ads_tracking), buildAdsDefs());
        currentMenu = "ads";
    }

    private List<Object> buildAdsDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createMasterSwitch(getString(R.string.ig_dialog_enable_disable_all), Arrays.asList("isAdBlockEnabled", "isAnalyticsBlocked", "disableTrackingLinks", "openLinksExternally")),
                createSwitch(R.drawable.ic_shield, "#FF453A", getString(R.string.ig_dialog_ad_block_ads), "isAdBlockEnabled"),
                createSwitch(R.drawable.ic_shield, "#FF453A", getString(R.string.ig_dialog_ad_block_analytics), "isAnalyticsBlocked"),
                createSwitch(R.drawable.ic_link, "#FF453A", getString(R.string.ig_dialog_ad_disable_tracking), "disableTrackingLinks"),
                createSwitch(R.drawable.ic_link, "#FF453A", getString(R.string.ig_dialog_open_links_externally), "openLinksExternally"),
                createClickable(R.drawable.ic_link, "#FF453A", shareDomainLabel(), this::showShareDomainDialog)
        ));

        // Outside the master switch
        defs.add(getString(R.string.feat_options));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_sparkle, "#FF453A", getString(R.string.ig_dialog_misc_remove_meta_ai), "removeMetaAI"),
                createExtrasSwitch("#FF453A", "hideOnboardingPrompts")
        ));

        return defs;
    }

    private void loadFeedMenu() {
        showMenu(getString(R.string.menu_feed), buildFeedDefs());
        currentMenu = "feed";
    }

    private List<Object> buildFeedDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.ig_dialog_menu_clean_feed));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_sparkle, "#64D2FF", getString(R.string.ig_dialog_clean_feed_hide_suggested), "hideSuggestionsInFeed"),
                createSwitch(R.drawable.ic_sparkle, "#64D2FF", getString(R.string.ig_dialog_clean_feed_hide_threads), "hideThreadsSuggestions"),
                createSwitch(R.drawable.ic_sparkle, "#64D2FF", getString(R.string.ig_dialog_clean_feed_hide_liked), "hideLikedPosts"),
                createSwitch(R.drawable.ic_sparkle, "#64D2FF", getString(R.string.ig_dialog_clean_feed_hide_community_notes), "hideCommunityNotes"),
                createSwitch(R.drawable.ic_sparkle, "#64D2FF", getString(R.string.ig_dialog_clean_feed_hide_plus), "hidePlusUpsells"),
                createSwitch(R.drawable.ic_sparkle, "#64D2FF", getString(R.string.ig_dialog_clean_feed_following_only), "followingOnlyFeed"),
                createExtrasSwitch("#64D2FF", "hideStoriesTray"),
                createSwitch(R.drawable.ic_block, "#64D2FF", getString(R.string.ig_dialog_misc_disable_discover_people), "disableDiscoverPeople")
        ));

        defs.add(getString(R.string.feat_options));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_sparkle, "#64D2FF", getString(R.string.ig_dialog_clean_feed_expand_captions), "expandCaptions"),
                createSwitch(R.drawable.ic_timer, "#64D2FF", getString(R.string.ig_dialog_misc_exact_timestamps), "exactTimestamps"),
                createExtrasSwitch("#64D2FF", "highResolutionImages"),
                createSwitch(R.drawable.ic_search, "#64D2FF", getString(R.string.ig_dialog_misc_photo_zoom), "enablePhotoZoom"),
                createSwitch(R.drawable.ic_movie, "#64D2FF", getString(R.string.ig_dialog_misc_disable_video_autoplay), "disableVideoAutoPlay")
        ));

        return defs;
    }

    private void loadDistractionMenu() {
        showMenu(getString(R.string.ig_dialog_section_distraction_free), buildDistractionDefs());
        currentMenu = "distract";
    }

    private List<Object> buildDistractionDefs() {
        List<Object> defs = new ArrayList<>();

        List<String> distractionKeys = Arrays.asList(
                "disableStories", "disableFeed", "disableReels",
                "disableReelsExceptDM", "disableExplore", "disableComments"
        );

        // Extreme Mode: only enabled once the user has selected at least one feature
        FeatureItem extreme = createSwitch(R.drawable.ic_block, "#FF453A", getString(R.string.ig_dialog_distraction_extreme_mode), "isExtremeMode");
        extreme.textColor = 0xFFFF453A;
        extreme.isExtreme = true;
        extreme.requiresAnyOf = distractionKeys;

        defs.add(getString(R.string.feat_danger_zone));
        defs.add(Arrays.asList(extreme));

        // Master switch and all feature toggles: locked once extreme mode is active
        FeatureItem masterSwitch = createMasterSwitch(getString(R.string.ig_dialog_enable_disable_all), distractionKeys);
        masterSwitch.disabledWhenTrue = "isExtremeMode";

        FeatureItem disableReels = createSwitchWithCascadeOff(R.drawable.ic_movie, "#30D158", getString(R.string.ig_dialog_distraction_disable_reels), "disableReels", "disableReelsExceptDM");
        disableReels.disabledWhenTrue = "isExtremeMode";

        FeatureItem disableReelsExceptDM = createSwitchWithDependency(R.drawable.ic_movie, "#30D158", getString(R.string.ig_dialog_distraction_disable_reels_except_dm), "disableReelsExceptDM", "disableReels");
        disableReelsExceptDM.disabledWhenTrue = "isExtremeMode";

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                masterSwitch,
                createSwitchLockedByExtreme(R.drawable.ic_story_ring, "#30D158", getString(R.string.ig_dialog_distraction_disable_stories), "disableStories"),
                createSwitchLockedByExtreme(R.drawable.ic_block, "#30D158", getString(R.string.ig_dialog_distraction_disable_feed), "disableFeed"),
                disableReels,
                disableReelsExceptDM,
                createSwitchLockedByExtreme(R.drawable.ic_search, "#30D158", getString(R.string.ig_dialog_distraction_disable_explore), "disableExplore"),
                createSwitchLockedByExtreme(R.drawable.ic_chat, "#30D158", getString(R.string.ig_dialog_distraction_disable_comments), "disableComments")
        ));

        return defs;
    }

    /** Navigation (#238): hide and reorder the bottom-nav tabs, startup tab and gestures. */
    private void loadNavigationMenu() {
        showMenu(getString(R.string.menu_navigation), buildNavigationDefs());
        currentMenu = "navigation";
    }

    private List<Object> buildNavigationDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.menu_nav_tabs));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_block, "#30D158", getString(R.string.ig_nav_tab_home), "hideTabHome"),
                createSwitch(R.drawable.ic_block, "#30D158", getString(R.string.ig_nav_tab_reels), "hideTabReels"),
                createSwitch(R.drawable.ic_block, "#30D158", getString(R.string.ig_nav_tab_create), "hideTabCreate"),
                createSwitch(R.drawable.ic_block, "#30D158", getString(R.string.ig_nav_tab_messages), "hideTabMessages"),
                createSwitch(R.drawable.ic_block, "#30D158", getString(R.string.ig_nav_tab_activity), "hideTabActivity"),
                createSwitch(R.drawable.ic_block, "#30D158", getString(R.string.ig_nav_tab_profile), "hideTabProfile")
        ));

        defs.add(getString(R.string.menu_nav_order));
        defs.add(Arrays.asList(
                createClickable(R.drawable.ic_tune, "#30D158", getString(R.string.ig_nav_tab_order), this::showTabOrderDialog),
                createClickable(R.drawable.ic_tune, "#30D158", startupTabLabel(), this::showStartupTabDialog)
        ));

        defs.add(getString(R.string.menu_nav_gestures));
        defs.add(Arrays.asList(createExtrasSwitch("#30D158", "disableSwipeToCamera")));

        return defs;
    }

    /** #238: reorder the bottom-nav tabs (saved as the "navTabOrder" string pref). */
    private void showTabOrderDialog() {
        final String[] ids = ps.reso.instaeclipse.mods.ui.DistractionFreeUIHook.ORDERABLE_TABS;
        final java.util.Map<String, String> labels = new java.util.HashMap<>();
        labels.put("feed_tab", getString(R.string.ig_nav_name_home));
        labels.put("clips_tab", getString(R.string.ig_nav_name_reels));
        labels.put("search_tab", getString(R.string.ig_nav_name_search));
        labels.put("creation_tab", getString(R.string.ig_nav_name_create));
        labels.put("direct_tab", getString(R.string.ig_nav_name_messages));
        labels.put("news_tab", getString(R.string.ig_nav_name_activity));
        labels.put("profile_tab", getString(R.string.ig_nav_name_profile));
        final java.util.List<String> order = new java.util.ArrayList<>();
        for (String n : localCache.getString("navTabOrder", "").split(","))
            if (java.util.Arrays.asList(ids).contains(n.trim()) && !order.contains(n.trim())) order.add(n.trim());
        for (String n : ids) if (!order.contains(n)) order.add(n);

        final android.widget.LinearLayout list = new android.widget.LinearLayout(requireContext());
        list.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        list.setPadding(pad, pad / 2, pad, 0);
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            list.removeAllViews();
            for (int i = 0; i < order.size(); i++) {
                final int idx = i;
                android.widget.LinearLayout row = new android.widget.LinearLayout(requireContext());
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                android.widget.TextView name = new android.widget.TextView(requireContext());
                name.setText((i + 1) + ".  " + labels.get(order.get(i)));
                name.setTextSize(16);
                row.addView(name, new android.widget.LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                for (final int dir : new int[]{-1, 1}) {
                    android.widget.Button b = new android.widget.Button(requireContext(), null, android.R.attr.borderlessButtonStyle);
                    b.setText(dir < 0 ? "↑" : "↓");
                    b.setEnabled(dir < 0 ? idx > 0 : idx < order.size() - 1);
                    b.setOnClickListener(v -> {
                        java.util.Collections.swap(order, idx, idx + dir);
                        render[0].run();
                    });
                    row.addView(b, new android.widget.LinearLayout.LayoutParams(
                            (int) (48 * getResources().getDisplayMetrics().density), ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                list.addView(row);
            }
        };
        render[0].run();
        android.widget.TextView hint = new android.widget.TextView(requireContext());
        hint.setText(R.string.ig_nav_tab_order_hint);
        hint.setPadding(0, pad / 2, 0, 0);
        android.widget.LinearLayout box = new android.widget.LinearLayout(requireContext());
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.addView(list);
        box.addView(hint);
        box.setPadding(pad, 0, pad, 0);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ig_nav_tab_order)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> saveTabOrder(String.join(",", order)))
                .setNeutralButton(R.string.ig_nav_tab_order_reset, (d, w) -> saveTabOrder(""))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String shareDomainLabel() {
        String d = localCache.getString("customShareDomain", "");
        return getString(R.string.ig_dialog_extras_share_domain) + "  ·  "
                + (d.isEmpty() ? getString(R.string.ig_dialog_extras_share_domain_off) : d);
    }

    /** Custom share domain (saved as the "customShareDomain" string pref). */
    private void showShareDomainDialog() {
        final android.widget.EditText input = new android.widget.EditText(requireContext());
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("ddinstagram.com");
        input.setText(localCache.getString("customShareDomain", ""));
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(requireContext());
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ig_dialog_extras_share_domain)
                .setMessage(R.string.ig_dialog_extras_share_domain_hint)
                .setView(box)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String domain = ps.reso.instaeclipse.mods.ads.ShareDomain.normalize(input.getText().toString());
                    if (domain == null) {
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), R.string.ig_dialog_extras_share_domain_invalid,
                                android.widget.Toast.LENGTH_SHORT).show();
                        return;
                    }
                    localCache.edit().putString("customShareDomain", domain).commit();
                    makeLocalCacheWorldReadable();
                    Intent b = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
                    b.putExtra("key", "customShareDomain");
                    b.putExtra("value", domain);
                    ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), b);
                    loadAdsMenu();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String[] startupTabNames() {
        return new String[]{
                getString(R.string.ig_dialog_extras_share_domain_off),
                getString(R.string.ig_nav_name_home),
                getString(R.string.ig_nav_name_reels),
                getString(R.string.ig_nav_name_messages),
                getString(R.string.ig_nav_name_search),
                getString(R.string.ig_nav_name_profile),
                getString(R.string.ig_nav_name_activity)};
    }

    private String startupTabLabel() {
        int i = Arrays.asList(ps.reso.instaeclipse.mods.ui.StartupTabHook.TABS).indexOf(localCache.getString("startupTab", ""));
        return getString(R.string.ig_dialog_extras_startup_tab) + "  ·  " + startupTabNames()[Math.max(i, 0)];
    }

    /** Which tab Instagram opens on (saved as the "startupTab" string pref). */
    private void showStartupTabDialog() {
        String[] tabs = ps.reso.instaeclipse.mods.ui.StartupTabHook.TABS;
        int current = Math.max(Arrays.asList(tabs).indexOf(localCache.getString("startupTab", "")), 0);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.ig_dialog_extras_startup_tab)
                .setSingleChoiceItems(startupTabNames(), current, (d, which) -> {
                    localCache.edit().putString("startupTab", tabs[which]).commit();
                    makeLocalCacheWorldReadable();
                    Intent b = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
                    b.putExtra("key", "startupTab");
                    b.putExtra("value", tabs[which]);
                    ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), b);
                    d.dismiss();
                    loadNavigationMenu();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void saveTabOrder(String value) {
        localCache.edit().putString("navTabOrder", value).commit();
        makeLocalCacheWorldReadable();
        Intent b = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
        b.putExtra("key", "navTabOrder");
        b.putExtra("value", value);
        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), b);
    }

    private FeatureItem createSwitchLockedByExtreme(String title, String prefKey) {
        FeatureItem item = createSwitch(title, prefKey);
        item.disabledWhenTrue = "isExtremeMode";
        return item;
    }

    private FeatureItem createSwitchLockedByExtreme(int iconRes, String accentHex, String title, String prefKey) {
        FeatureItem item = createSwitchLockedByExtreme(title, prefKey);
        item.iconRes = iconRes;
        item.accentColor = Color.parseColor(accentHex);
        return item;
    }

    private void loadInteractionsMenu() {
        showMenu(getString(R.string.menu_likes_comments), buildInteractionsDefs());
        currentMenu = "interactions";
    }

    private List<Object> buildInteractionsDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_heart, A_SOCIAL, getString(R.string.ig_dialog_misc_disable_double_tap_like), "disableDoubleTapLike"),
                createSwitch(R.drawable.ic_heart, A_SOCIAL, getString(R.string.ig_dialog_misc_like_undo), "likeUndo"),
                createSwitch(R.drawable.ic_content_copy, A_SOCIAL, getString(R.string.ig_dialog_misc_copy_comment), "enableCopyComment"),
                createSwitch(R.drawable.ic_search, A_SOCIAL, getString(R.string.ig_dialog_misc_comment_search), "commentSearch"),
                createSwitch(R.drawable.ic_content_copy, A_SOCIAL, getString(R.string.ig_dialog_misc_copy_caption), "enableCaptionCopy")
        ));

        return defs;
    }

    private void loadProfilesMenu() {
        showMenu(getString(R.string.menu_profiles), buildProfilesDefs());
        currentMenu = "profiles";
    }

    private List<Object> buildProfilesDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_notification, A_SOCIAL, getString(R.string.ig_dialog_misc_profile_follows_label), "profileFollowsYouLabel"),
                createSwitch(R.drawable.ic_block, A_SOCIAL, getString(R.string.ig_dialog_hide_threads_button), "hideThreadsButton"),
                createSwitch(R.drawable.ic_notification, A_SOCIAL, getString(R.string.ig_dialog_misc_show_follower_toast), "showFollowerToast"),
                createSwitch(R.drawable.ic_download, A_SOCIAL, getString(R.string.ig_dialog_misc_follow_export), "followListExport"),
                createSwitch(R.drawable.ic_save, A_SOCIAL, getString(R.string.ig_saved_on_profile), "savedOnProfile")
        ));

        return defs;
    }

    private void loadAppMenu() {
        showMenu(getString(R.string.menu_app), buildAppDefs());
        currentMenu = "app";
    }

    private List<Object> buildAppDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createExtrasSwitch("#0A84FF", "unlimitedAccounts"),
                createExtrasSwitch("#0A84FF", "fixNotificationRegistration"),
                createSwitch(R.drawable.ic_notification, "#0A84FF", getString(R.string.ig_dialog_misc_show_feature_toasts), "showFeatureToasts")
        ));

        // Auto-clear cache (mirrors the in-IG dialog's Tools section).
        defs.add(getString(R.string.ig_dialog_section_auto_clear_cache));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_timer, "#FF9F0A", getString(R.string.ig_dialog_auto_clear_cache), "autoClearCache"),
                createClickable(R.drawable.ic_folder, "#FF9F0A",
                        getString(R.string.ig_dialog_auto_clear_cache_size, localCache.getInt("autoClearCacheSizeMb", 100)),
                        this::pickAutoClearSize)
        ));

        return defs;
    }

    private void openInstagram() {
        Intent launch = requireContext().getPackageManager()
                .getLaunchIntentForPackage(ps.reso.instaeclipse.utils.core.CommonUtils.IG_PACKAGE_NAME);
        if (launch != null) startActivity(launch);
        else ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(),
                getString(R.string.not_installed_instagram), Toast.LENGTH_SHORT).show();
    }

    /** Saves an int setting locally and pushes it to Instagram. */
    private void putIntPref(String key, int value) {
        localCache.edit().putInt(key, value).commit();
        makeLocalCacheWorldReadable();
        Intent b = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT");
        b.putExtra("key", key);
        b.putExtra("value", value);
        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), b);
    }

    private void pickAutoClearSize() {
        // Same reachable presets as the in-IG dialog (IG's real cache is small).
        final int[] values = {50, 100, 200, 500};
        int current = localCache.getInt("autoClearCacheSizeMb", 100);
        String[] labels = new String[values.length];
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = values[i] + " MB";
            if (values[i] == current) sel = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.ig_dialog_section_auto_clear_cache))
                .setSingleChoiceItems(labels, sel, (dialog, which) -> {
                    putIntPref("autoClearCacheSizeMb", values[which]);
                    dialog.dismiss();
                    loadAppMenu();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Lock (Privacy). Companion app only toggles the persisted flags — the passcode itself is set
     *  from inside Instagram (the module has no passcode field here). */
    private void loadLockMenu() {
        showMenu(getString(R.string.ig_dialog_misc_lock_section), buildLockDefs());
        currentMenu = "lock";
    }

    private List<Object> buildLockDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_shield, "#5E5CE6", getString(R.string.ig_dialog_misc_lock_dms), "lockDirectMessages"),
                createSwitch(R.drawable.ic_shield, "#5E5CE6", getString(R.string.ig_dialog_misc_lock_dms_always), "lockDirectAlways"),
                createSwitch(R.drawable.ic_shield, "#5E5CE6", getString(R.string.ig_dialog_misc_lock_whole_app), "lockWholeApp"),
                createSwitch(R.drawable.ic_shield, "#5E5CE6", getString(R.string.ig_locked_coll_setting), "lockSavedCollections"),
                createSwitch(R.drawable.ic_shield, "#5E5CE6", getString(R.string.ig_dialog_misc_lock_fingerprint), "lockUseFingerprint")
        ));

        // The passcode is set from inside Instagram (stored hashed in IG's own prefs), so the app
        // only points there; tapping opens Instagram.
        defs.add(Arrays.asList(
                createTool(R.drawable.ic_shield, "#5E5CE6", getString(R.string.ig_dialog_misc_lock_dms_set),
                        getString(R.string.lock_code_set_in_ig), this::openInstagram)
        ));

        return defs;
    }

    /** Hide Specific Chats (Privacy). The companion app toggles the feature; hiding/unhiding a
     *  specific chat is done from inside Instagram. */
    private void loadHideChatsMenu() {
        showMenu(getString(R.string.ig_hide_chats_title), buildHideChatsDefs());
        currentMenu = "hidechats";
    }

    private List<Object> buildHideChatsDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_eye_off, "#5E5CE6", getString(R.string.ig_dialog_misc_hide_chats), "hideSpecificChats")
        ));

        // Note: hiding/unhiding a specific chat is done from inside Instagram.

        return defs;
    }

    private void loadLocationMenu() {
        showMenu(getString(R.string.ig_dialog_section_location), buildLocationDefs());
        currentMenu = "location";
    }

    private List<Object> buildLocationDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(createSwitch(R.drawable.ic_pin, "#FFD60A", getString(R.string.ig_dialog_location_spoof_enable), "spoofLocation")));

        double lat = 0.0, lng = 0.0;
        try { lat = Double.parseDouble(localCache.getString("spoofLat", "0")); } catch (Throwable ignored) {}
        try { lng = Double.parseDouble(localCache.getString("spoofLng", "0")); } catch (Throwable ignored) {}
        String label = localCache.getString("spoofLabel", "");
        String place = !ps.reso.instaeclipse.mods.location.LocationPresets.valid(lat, lng)
                ? getString(R.string.ig_dialog_location_unset)
                : !label.isEmpty() ? label : ps.reso.instaeclipse.mods.location.LocationPresets.coords(lat, lng);

        final double curLat = lat, curLng = lng;
        defs.add(getString(R.string.ig_dialog_location_current_title));
        defs.add(Arrays.asList(createNav(R.drawable.ic_search, "#FFD60A", place, () -> {
            Intent i = new Intent(requireContext(), LocationPickerActivity.class);
            i.putExtra(LocationPickerActivity.EXTRA_LAT, curLat);
            i.putExtra(LocationPickerActivity.EXTRA_LNG, curLng);
            locationPickerLauncher.launch(i);
        })));

        List<ps.reso.instaeclipse.mods.location.LocationPresets.Preset> recent =
                ps.reso.instaeclipse.mods.location.LocationPresets.parse(localCache.getString("spoofRecent", ""));
        if (!recent.isEmpty()) {
            List<FeatureItem> rows = new ArrayList<>();
            for (ps.reso.instaeclipse.mods.location.LocationPresets.Preset p : recent) {
                rows.add(createNav(R.drawable.ic_timer, "#FFD60A", p.title(), () -> {
                    ps.reso.instaeclipse.mods.location.LocationPresets.applyFromCompanion(requireContext(), p);
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), R.string.ig_dialog_location_applied, Toast.LENGTH_SHORT).show();
                    loadLocationMenu();
                }));
            }
            defs.add(getString(R.string.ig_dialog_location_recent));
            defs.add(rows);
        }

        return defs;
    }

    private void loadThemeMenu() {
        showMenu(getString(R.string.theme_title), buildThemeDefs());
        currentMenu = "theme";
    }

    private List<Object> buildThemeDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.theme_section_colors));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_palette, "#FF375F", getString(R.string.theme_enable), "customThemeEnabled"),
                createClickable(R.drawable.ic_palette, "#FF375F", getString(R.string.theme_customize), () ->
                        themeCustomizerLauncher.launch(new Intent(requireContext(), ThemeCustomizerActivity.class))),
                // Follows the phone's mode, like the customizer and the in-IG dialog.
                isNightMode()
                        ? createClickable(R.drawable.ic_palette, "#FF375F",
                                getString(R.string.theme_dark_mode) + ": " + darkModeLabel(localCache.getInt("themePresetId", 1)),
                                this::pickDarkModeTheme)
                        : createClickable(R.drawable.ic_palette, "#FF375F",
                                getString(R.string.theme_light_mode) + ": " + lightModeLabel(localCache.getInt("themeLightPresetId", -1)),
                                this::pickLightModeTheme)
        ));

        // Fonts & Emoji now live under Custom Theme (mirrors the in-IG dialog). File pickers run
        // inside Instagram; here the companion app only toggles the flags.
        defs.add(getString(R.string.theme_section_fonts));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_palette, "#FF375F", getString(R.string.ig_dialog_misc_custom_font), "customFontEnabled"),
                createSwitch(R.drawable.ic_palette, "#FF375F", getString(R.string.ig_dialog_misc_custom_emoji), "customEmojiEnabled")
        ));
        // Note: font & emoji files are picked from inside Instagram.

        return defs;
    }

    private String lightModeLabel(int value) {
        if (value == 0) return getString(R.string.theme_light_same);
        if (value == -1) return getString(R.string.theme_light_stock);
        return ps.reso.instaeclipse.mods.ui.theme.ThemePresets.getDisplayName(requireContext(), value);
    }

    private boolean isNightMode() {
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private String darkModeLabel(int value) {
        if (value == 0) return getString(R.string.theme_section_custom);
        return ps.reso.instaeclipse.mods.ui.theme.ThemePresets.getDisplayName(requireContext(), value);
    }

    /** Dark-mode theme: one of the dark presets, or the custom palette when that is in use. */
    private void pickDarkModeTheme() {
        int current = localCache.getInt("themePresetId", 1);
        int[] presets = ps.reso.instaeclipse.mods.ui.theme.ThemePresets.darkModeChoices();
        final int[] values;
        if (current == 0) {
            values = new int[presets.length + 1];
            System.arraycopy(presets, 0, values, 1, presets.length);
        } else {
            values = presets;
        }
        String[] labels = new String[values.length];
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = darkModeLabel(values[i]);
            if (values[i] == current) sel = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.theme_dark_mode))
                .setSingleChoiceItems(labels, sel, (dialog, which) -> {
                    putIntPref("themePresetId", values[which]);
                    dialog.dismiss();
                    loadThemeMenu();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Light-mode behaviour of the custom theme (#251). */
    private void pickLightModeTheme() {
        final int[] values = ps.reso.instaeclipse.mods.ui.theme.ThemePresets.lightModeChoices();
        int current = localCache.getInt("themeLightPresetId", -1);
        String[] labels = new String[values.length];
        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            labels[i] = lightModeLabel(values[i]);
            if (values[i] == current) sel = i;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.theme_light_mode))
                .setSingleChoiceItems(labels, sel, (dialog, which) -> {
                    int value = values[which];
                    SharedPreferences.Editor ed = localCache.edit();
                    ed.putInt("themeLightPresetId", value);
                    ed.commit();
                    makeLocalCacheWorldReadable();

                    Intent b = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT");
                    b.putExtra("key", "themeLightPresetId");
                    b.putExtra("value", value);
                    ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), b);

                    dialog.dismiss();
                    loadThemeMenu();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String qualityLabel(int h) {
        if (h == 360) return getString(R.string.ig_dialog_quality_360);
        if (h == 480) return getString(R.string.ig_dialog_quality_480);
        if (h == 720) return getString(R.string.ig_dialog_quality_720);
        if (h == 1080) return getString(R.string.ig_dialog_quality_1080);
        if (h == Integer.MAX_VALUE) return getString(R.string.ig_dialog_quality_max);
        return getString(R.string.ig_dialog_quality_auto);
    }

    private void loadReelsMenu() {
        showMenu(getString(R.string.menu_reels), buildReelsDefs());
        currentMenu = "reels";
    }

    private void loadStoriesMenu() {
        showMenu(getString(R.string.menu_stories), buildStoriesDefs());
        currentMenu = "stories";
    }

    private List<Object> buildReelsDefs() {
        List<Object> defs = new ArrayList<>();
        int current = localCache.getInt("forceReelQuality", 0);

        defs.add(getString(R.string.ig_dialog_section_quality));
        defs.add(Arrays.asList(createClickable(R.drawable.ic_movie, "#32D74B",
                getString(R.string.ig_dialog_quality_force_reels) + ": " + qualityLabel(current), () -> pickQuality(current))));

        defs.add(getString(R.string.ig_nav_name_reels));
        defs.add(Arrays.asList(
                createExtrasSwitch("#32D74B", "reelsAutoScroll"),
                createExtrasSwitch("#32D74B", "reelsLockScroll"),
                createSwitch(R.drawable.ic_movie, "#32D74B", getString(R.string.ig_dialog_downloader_reel_scrubber), "forceReelScrubber"),
                createSwitch(R.drawable.ic_block, "#32D74B", getString(R.string.ig_dialog_misc_disable_repost), "disableRepost")
        ));

        return defs;
    }

    private List<Object> buildStoriesDefs() {
        List<Object> defs = new ArrayList<>();
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_story_ring, "#32D74B", getString(R.string.ig_dialog_misc_disable_story_autoswipe), "disableStoryFlipping"),
                createSwitch(R.drawable.ic_at, "#32D74B", getString(R.string.ig_dialog_misc_view_story_mentions), "enableStoryMentions"),
                createSwitch(R.drawable.ic_link, "#32D74B", getString(R.string.ig_dialog_misc_story_links), "storyLinks"),
                createSwitch(R.drawable.ic_info, "#32D74B", getString(R.string.ig_dialog_misc_story_info), "storyInfo"),
                createSwitch(R.drawable.ic_search, "#32D74B", getString(R.string.ig_dialog_misc_viewer_search), "storyViewerSearch")
        ));

        return defs;
    }

    private void pickQuality(int current) {
        String[] labels = {
                getString(R.string.ig_dialog_quality_auto), getString(R.string.ig_dialog_quality_360),
                getString(R.string.ig_dialog_quality_480), getString(R.string.ig_dialog_quality_720),
                getString(R.string.ig_dialog_quality_1080), getString(R.string.ig_dialog_quality_max)
        };
        int[] values = {0, 360, 480, 720, 1080, Integer.MAX_VALUE};

        int sel = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) { sel = i; break; }
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.ig_dialog_quality_force_reels))
                .setSingleChoiceItems(labels, sel, (dialog, which) -> {
                    int value = values[which];
                    SharedPreferences.Editor ed = localCache.edit();
                    ed.putInt("forceReelQuality", value);
                    ed.commit();
                    makeLocalCacheWorldReadable();

                    Intent b = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT");
                    b.putExtra("key", "forceReelQuality");
                    b.putExtra("value", value);
                    ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), b);

                    dialog.dismiss();
                    loadReelsMenu();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void loadDownloaderMenu() {
        showMenu(getString(R.string.ig_dialog_section_downloader), buildDownloaderDefs());
        currentMenu = "downloader";
    }

    private List<Object> buildDownloaderDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createMasterSwitch(getString(R.string.ig_dialog_enable_disable_all), Arrays.asList(
                        "enablePostDownload", "enableStoryDownload", "enableReelDownload", "enableProfileDownload"
                )),
                createSwitch(R.drawable.ic_download, "#FF9F0A", getString(R.string.ig_dialog_downloader_posts), "enablePostDownload"),
                createSwitch(R.drawable.ic_download, "#FF9F0A", getString(R.string.ig_dialog_downloader_stories), "enableStoryDownload"),
                createSwitch(R.drawable.ic_download, "#FF9F0A", getString(R.string.ig_dialog_downloader_reels), "enableReelDownload"),
                createSwitch(R.drawable.ic_download, "#FF9F0A", getString(R.string.ig_dialog_downloader_profiles), "enableProfileDownload")
        ));

        defs.add(getString(R.string.feat_options));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_folder, "#FF9F0A", getString(R.string.ig_dialog_downloader_username_subfolder), "downloaderUsernameFolder"),
                createSwitch(R.drawable.ic_timer, "#FF9F0A", getString(R.string.ig_dialog_downloader_add_timestamp), "downloaderAddTimestamp"),
                createSwitch(R.drawable.ic_download, "#FF9F0A", getString(R.string.ig_dialog_downloader_voice), "enableVoiceDownload"),
                createSwitch(R.drawable.ic_download, "#FF9F0A", getString(R.string.ig_dialog_downloader_offer_audio), "offerAudioDownload"),
                createSwitch(R.drawable.ic_download, "#FF9F0A", getString(R.string.ig_dialog_misc_cache_stories), "cacheStories")
        ));

        String customPath = localCache.getString("downloaderCustomPath", "");
        String folderTitle = customPath.isEmpty()
                ? getString(R.string.feat_downloader_set_folder)
                : getString(R.string.feat_downloader_selected, customPath);

        defs.add(getString(R.string.feat_download_folder));
        defs.add(Arrays.asList(
                createClickable(R.drawable.ic_folder, "#FF9F0A", folderTitle, this::pickDownloadFolder),
                createClickable(R.drawable.ic_delete, "#FF453A", getString(R.string.feat_downloader_reset_folder), this::resetDownloadFolder)
        ));

        return defs;
    }

    private void loadPostMenuMenu() {
        showMenu(getString(R.string.menu_post_menu), buildPostMenuDefs());
        currentMenu = "postmenu";
    }

    private List<Object> buildPostMenuDefs() {
        List<Object> defs = new ArrayList<>();

        defs.add(getString(R.string.feat_features));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_link, "#FF9F0A", getString(R.string.ig_dialog_downloader_copy_link), "copyMediaLink"),
                createSwitch(R.drawable.ic_content_copy, "#FF9F0A", getString(R.string.ig_dialog_downloader_offer_copy_image), "offerCopyImage"),
                createSwitch(R.drawable.ic_search, "#FF9F0A", getString(R.string.ig_dialog_downloader_offer_media_info), "offerMediaInfo"),
                createSwitch(R.drawable.ic_movie, "#FF9F0A", getString(R.string.ig_dialog_downloader_offer_speed), "offerPlaybackSpeed")
        ));

        defs.add(getString(R.string.feat_options));
        defs.add(Arrays.asList(
                createSwitch(R.drawable.ic_upload, "#FF9F0A", getString(R.string.ig_dialog_max_upload_quality), "maxUploadQuality"),
                createSwitch(R.drawable.ic_download, "#FF9F0A", getString(R.string.ig_dialog_misc_save_instants), "saveInstants"),
                createSwitch(R.drawable.ic_upload, "#FF9F0A", getString(R.string.ig_dialog_misc_upload_instants), "uploadInstants")
        ));

        return defs;
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
        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), intentUri);

        Intent intentPath = new Intent("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING");
        intentPath.putExtra("key", "downloaderCustomPath");
        intentPath.putExtra("value", "");
        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), intentPath);

        ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.ig_toast_download_folder_reset), Toast.LENGTH_SHORT).show();

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
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void restoreSettings() {
        restoreFileLauncher.launch(new String[]{"application/json"});
    }

    private void restartInstagram() {
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.ig_toast_restart_manual), Toast.LENGTH_LONG).show();
    }

    private void importDevConfig() {
        Intent importIntent = new Intent();
        importIntent.setComponent(new ComponentName(requireContext(), "ps.reso.instaeclipse.mods.devops.config.JsonImportActivity"));
        importIntent.putExtra("target_package", "com.instagram.android");
        startActivity(importIntent);
    }

    private void exportDevConfig() {
        final String nonce = ps.reso.instaeclipse.utils.core.IpcSecurity.newNonce();
        BroadcastReceiver configReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ps.reso.instaeclipse.utils.core.IpcSecurity.nonceMatches(nonce, intent)) return;
                context.unregisterReceiver(this);
                Activity activity = getActivity();
                if (activity == null || activity.isFinishing()) return;
                String error = intent.getStringExtra("error");
                if (error != null) {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(activity, error, Toast.LENGTH_SHORT).show();
                    return;
                }
                String json = intent.getStringExtra("json_content");
                if (json == null || json.isEmpty()) {
                    ps.reso.instaeclipse.utils.toast.IeToast.makeText(activity, getString(R.string.export_no_config_data), Toast.LENGTH_SHORT).show();
                    return;
                }
                Intent exportIntent = new Intent();
                exportIntent.setComponent(new ComponentName(activity, "ps.reso.instaeclipse.mods.devops.config.JsonExportActivity"));
                exportIntent.putExtra("json_content", json);
                startActivity(exportIntent);
            }
        };
        IntentFilter filter = new IntentFilter("ps.reso.instaeclipse.ACTION_SEND_CONFIG");
        ps.reso.instaeclipse.utils.core.IpcSecurity.registerReplyReceiver(requireContext(), configReceiver, filter);
        Intent request = new Intent("ps.reso.instaeclipse.ACTION_EXPORT_CONFIG");
        request.putExtra(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE, nonce);
        request.setPackage("com.instagram.android");
        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), request);
    }

    private void restoreDefaultConfig() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.ig_dialog_dev_restore_default_config))
                .setMessage(getString(R.string.ig_dialog_dev_restore_default_config_confirm))
                .setPositiveButton(getString(R.string.ig_dialog_yes), (dialog, which) -> {
                    try (InputStream is = requireContext().getAssets().open("default_mc_overrides.json");
                         Scanner scanner = new Scanner(is, "UTF-8").useDelimiter("\\A")) {
                        String json = scanner.hasNext() ? scanner.next() : "";
                        if (json.isEmpty()) {
                            ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.ig_toast_config_import_failed), Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Intent broadcast = new Intent("ps.reso.instaeclipse.ACTION_IMPORT_CONFIG");
                        broadcast.setPackage("com.instagram.android");
                        broadcast.putExtra("json_content", json);
                        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), broadcast);
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.ig_toast_default_config_restored), Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(getString(R.string.ig_dialog_cancel), null)
                .show();
    }

    private void showAboutDialog() {
        Context ctx = requireContext();
        float d = getResources().getDisplayMetrics().density;
        int onSurface = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
        int onVariant = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY);
        int chipBg = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSecondaryContainer, Color.DKGRAY);
        int chipFg = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.WHITE);
        int primary = MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, Color.BLUE);

        android.widget.LinearLayout root = new android.widget.LinearLayout(ctx);
        root.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        root.setPadding((int) (24 * d), (int) (28 * d), (int) (24 * d), (int) (8 * d));

        // App icon on a soft accent halo; pops in when the dialog opens.
        android.widget.FrameLayout halo = new android.widget.FrameLayout(ctx);
        android.graphics.drawable.GradientDrawable glow = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{(primary & 0x00FFFFFF) | 0x55000000, (0xFFBF5AF2 & 0x00FFFFFF) | 0x33000000});
        glow.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        halo.setBackground(glow);
        int haloSize = (int) (108 * d);
        ImageView icon = new ImageView(ctx);
        icon.setImageResource(R.mipmap.ic_launcher_round);
        android.widget.FrameLayout.LayoutParams ilp = new android.widget.FrameLayout.LayoutParams((int) (80 * d), (int) (80 * d));
        ilp.gravity = android.view.Gravity.CENTER;
        halo.addView(icon, ilp);
        root.addView(halo, new android.widget.LinearLayout.LayoutParams(haloSize, haloSize));

        TextView name = new TextView(ctx);
        name.setText(R.string.app_name);
        name.setTextColor(onSurface);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        android.widget.LinearLayout.LayoutParams nlp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = (int) (14 * d);
        root.addView(name, nlp);

        TextView tagline = new TextView(ctx);
        tagline.setText(R.string.about_tagline);
        tagline.setTextColor(onVariant);
        tagline.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tagline.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams tlp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = (int) (4 * d);
        root.addView(tagline, tlp);

        // Version chips: module + the Instagram build it is running against.
        android.widget.LinearLayout chips = new android.widget.LinearLayout(ctx);
        chips.setGravity(android.view.Gravity.CENTER);
        chips.addView(aboutChip(ctx, "v" + versionOf(ctx, ctx.getPackageName()), chipBg, chipFg, d));
        android.widget.LinearLayout.LayoutParams clp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = (int) (16 * d);
        root.addView(chips, clp);

        // Link buttons.
        android.widget.LinearLayout links = new android.widget.LinearLayout(ctx);
        links.setOrientation(android.widget.LinearLayout.VERTICAL);
        links.addView(aboutButton(ctx, R.drawable.ic_github_logo, getString(R.string.about_source), ExportBrand.GITHUB, d));
        links.addView(aboutButton(ctx, R.drawable.ic_telegram_logo, getString(R.string.about_channel), ExportBrand.TELEGRAM, d));
        links.addView(aboutButton(ctx, R.drawable.ic_github_logo, getString(R.string.about_contributors),
                ExportBrand.GITHUB + "/graphs/contributors", d));
        android.widget.LinearLayout.LayoutParams llp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = (int) (20 * d);
        root.addView(links, llp);

        TextView footer = new TextView(ctx);
        footer.setText(R.string.about_footer);
        footer.setTextColor(onVariant);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        footer.setGravity(android.view.Gravity.CENTER);
        android.widget.LinearLayout.LayoutParams flp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = (int) (14 * d);
        root.addView(footer, flp);

        new MaterialAlertDialogBuilder(ctx)
                .setView(root)
                .setPositiveButton(R.string.ig_dialog_close, null)
                .show();

        // Entrance: icon pops in, the rest rises in just after it.
        halo.setScaleX(0.6f);
        halo.setScaleY(0.6f);
        halo.setAlpha(0f);
        halo.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420)
                .setInterpolator(new android.view.animation.OvershootInterpolator(1.6f)).start();
        icon.setRotation(-25f);
        icon.animate().rotation(0f).setDuration(520).setInterpolator(new DecelerateInterpolator()).start();
        int i = 0;
        for (View v : new View[]{name, tagline, chips, links, footer}) {
            v.setAlpha(0f);
            v.setTranslationY(12 * d);
            v.animate().alpha(1f).translationY(0f).setStartDelay(120 + 60L * i++).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
    }

    private static String versionOf(Context ctx, String pkg) {
        try {
            String v = ctx.getPackageManager().getPackageInfo(pkg, 0).versionName;
            return v == null ? "" : v;
        } catch (Exception e) {
            return "";
        }
    }

    private static TextView aboutChip(Context ctx, String text, int bg, int fg, float d) {
        TextView chip = new TextView(ctx);
        chip.setText(text);
        chip.setTextColor(fg);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        chip.setSingleLine(true);
        chip.setPadding((int) (12 * d), (int) (6 * d), (int) (12 * d), (int) (6 * d));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(bg);
        shape.setCornerRadius(20 * d);
        chip.setBackground(shape);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart((int) (4 * d));
        lp.setMarginEnd((int) (4 * d));
        chip.setLayoutParams(lp);
        return chip;
    }

    private View aboutButton(Context ctx, int iconRes, String label, String url, float d) {
        com.google.android.material.button.MaterialButton b = new com.google.android.material.button.MaterialButton(ctx);
        // Tonal look (same as the Help tab's GitHub / Telegram buttons).
        int bg = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorSecondaryContainer, Color.DKGRAY);
        int fg = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.WHITE);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg));
        b.setTextColor(fg);
        b.setIconTint(android.content.res.ColorStateList.valueOf(fg));
        b.setText(label);
        b.setIconResource(iconRes);
        b.setIconSize((int) (18 * d));
        b.setIconGravity(com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START);
        b.setOnClickListener(v -> openUrl(url));
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (4 * d);
        b.setLayoutParams(lp);
        return b;
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception ignored) {
        }
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
            ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(), intent);
        }
        editor.apply();
        stagedChanges.clear();
        fabSave.hide();
        ps.reso.instaeclipse.utils.toast.IeToast.makeText(requireContext(), getString(R.string.ig_toast_settings_applied), Toast.LENGTH_SHORT).show();
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
        ps.reso.instaeclipse.utils.core.IpcSecurity.registerReplyReceiver(requireContext(), prefsReceiver, filter);
        prefsNonce = ps.reso.instaeclipse.utils.core.IpcSecurity.newNonce();
        ps.reso.instaeclipse.utils.core.CommonUtils.broadcastToInstagram(requireContext(),
                new Intent("ps.reso.instaeclipse.ACTION_REQUEST_PREFS")
                        .putExtra(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE, prefsNonce));
        // Prefs may have changed while away (e.g. toggled from inside Instagram): refresh counts
        refreshVisibleMenu();
    }

    @Override
    public void onPause() {
        super.onPause();
        requireContext().unregisterReceiver(prefsReceiver);
    }
}
