package ps.reso.instaeclipse.mods.location;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.events.MapListener;
import org.osmdroid.events.ScrollEvent;
import org.osmdroid.events.ZoomEvent;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import ps.reso.instaeclipse.R;

/**
 * Full-screen OpenStreetMap picker for the coordinates the GPS spoof reports to Instagram.
 *
 * <ul>
 *   <li>Search (Nominatim) lists up to six matches; pasted "lat, lng" coordinates jump
 *   straight to the point.</li>
 *   <li>When the map settles, the place under the pin is named via reverse geocoding (debounced
 *   to respect Nominatim's one-request-per-second policy).</li>
 *   <li>Recently used places show as chips; the confirmed place is saved with its name and
 *   pushed to Instagram through {@link LocationPresets#applyFromCompanion}.</li>
 * </ul>
 */
public class LocationPickerActivity extends AppCompatActivity {

    public static final String EXTRA_LAT = "lat";
    public static final String EXTRA_LNG = "lng";
    public static final String RESULT_LAT = "result_lat";
    public static final String RESULT_LNG = "result_lng";

    private static final long REVERSE_DELAY_MS = 900;

    private MapView map;
    private TextView placeName;
    private TextView coordText;
    private TextInputEditText searchInput;
    private CircularProgressIndicator searchProgress;
    private MaterialCardView resultsCard;
    private LinearLayout resultsList;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /** Name of the place under the pin; empty until reverse geocoding answers. */
    private String currentLabel = "";
    /** Set when the pin was placed from a named source (search hit / recent chip). */
    private boolean labelLocked;
    private final Runnable reverseTask = this::reverseGeocodeCenter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            SharedPreferences prefs = getApplicationContext()
                    .getSharedPreferences(getApplicationContext().getPackageName() + "_preferences", MODE_PRIVATE);
            Configuration.getInstance().load(getApplicationContext(), prefs);
            Configuration.getInstance().setUserAgentValue(getPackageName());
        } catch (Throwable ignored) {}

        setContentView(R.layout.activity_location_picker);

        map = findViewById(R.id.map);
        placeName = findViewById(R.id.place_name);
        coordText = findViewById(R.id.coord_text);
        searchInput = findViewById(R.id.search_input);
        searchProgress = findViewById(R.id.search_progress);
        resultsCard = findViewById(R.id.results_card);
        resultsList = findViewById(R.id.results_list);
        ImageButton back = findViewById(R.id.btn_back);
        ImageButton clearBtn = findViewById(R.id.search_clear);
        MaterialButton useBtn = findViewById(R.id.btn_use);

        back.setOnClickListener(v -> finish());

        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            String q = v.getText() != null ? v.getText().toString().trim() : "";
            if (!q.isEmpty()) onSearch(q);
            return true;
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean empty = s == null || s.length() == 0;
                clearBtn.setVisibility(empty ? View.GONE : View.VISIBLE);
                if (empty) resultsCard.setVisibility(View.GONE);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        clearBtn.setOnClickListener(v -> {
            searchInput.setText("");
            searchInput.requestFocus();
        });

        map.setTileSource(TileSourceFactory.MAPNIK);
        map.setMultiTouchControls(true);
        map.getController().setZoom(15.0);

        double startLat = getIntent().getDoubleExtra(EXTRA_LAT, 0.0);
        double startLng = getIntent().getDoubleExtra(EXTRA_LNG, 0.0);
        if (!LocationPresets.valid(startLat, startLng)) {
            startLat = 41.0082; // Istanbul as a neutral first view
            startLng = 28.9784;
            map.getController().setZoom(11.0);
        }
        map.getController().setCenter(new GeoPoint(startLat, startLng));
        onCenterChanged();

        map.addMapListener(new MapListener() {
            @Override public boolean onScroll(ScrollEvent e) { onCenterChanged(); return false; }
            @Override public boolean onZoom(ZoomEvent e) { onCenterChanged(); return false; }
        });

        // Only a finger on the map releases a name that came from search or a recent chip;
        // programmatic animateTo() also fires scroll events.
        map.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                labelLocked = false;
                resultsCard.setVisibility(View.GONE);
            }
            return false;
        });

        buildRecentChips();

        useBtn.setOnClickListener(v -> {
            GeoPoint c = (GeoPoint) map.getMapCenter();
            LocationPresets.Preset picked = new LocationPresets.Preset(c.getLatitude(), c.getLongitude(), currentLabel);
            LocationPresets.applyFromCompanion(this, picked);
            Intent data = new Intent();
            data.putExtra(RESULT_LAT, picked.lat);
            data.putExtra(RESULT_LNG, picked.lng);
            setResult(RESULT_OK, data);
            finish();
        });
    }

    // ---- map ------------------------------------------------------------------------------

    private void onCenterChanged() {
        GeoPoint c = (GeoPoint) map.getMapCenter();
        coordText.setText(LocationPresets.coords(c.getLatitude(), c.getLongitude()));
        if (labelLocked) return;
        currentLabel = "";
        placeName.setText(R.string.loc_picker_locating);
        main.removeCallbacks(reverseTask);
        main.postDelayed(reverseTask, REVERSE_DELAY_MS);
    }

    private void moveTo(double lat, double lng, String label) {
        labelLocked = label != null && !label.isEmpty();
        currentLabel = labelLocked ? label : "";
        if (labelLocked) placeName.setText(label);
        map.getController().setZoom(16.0);
        map.getController().animateTo(new GeoPoint(lat, lng));
        coordText.setText(LocationPresets.coords(lat, lng));
        if (!labelLocked) onCenterChanged();
    }

    // ---- recent places ---------------------------------------------------------------------

    private void buildRecentChips() {
        ChipGroup group = findViewById(R.id.recent_chips);
        HorizontalScrollView scroll = findViewById(R.id.recent_scroll);
        List<LocationPresets.Preset> recent = LocationPresets.parse(
                getSharedPreferences("instaeclipse_cache", MODE_PRIVATE).getString("spoofRecent", ""));
        group.removeAllViews();
        for (LocationPresets.Preset p : recent) {
            Chip chip = new Chip(this, null, com.google.android.material.R.attr.chipStyle);
            chip.setText(shorten(p.title()));
            chip.setChipIconResource(R.drawable.ic_timer);
            chip.setChipIconVisible(true);
            chip.setOnClickListener(v -> moveTo(p.lat, p.lng, p.label));
            group.addView(chip);
        }
        scroll.setVisibility(recent.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private static String shorten(String s) {
        int comma = s.indexOf(',');
        String first = comma > 0 ? s.substring(0, comma) : s;
        return first.length() > 28 ? first.substring(0, 27) + "…" : first;
    }

    // ---- search ----------------------------------------------------------------------------

    private void onSearch(String query) {
        hideKeyboard();
        double[] coords = LocationPresets.parseCoordinates(query);
        if (coords != null) {
            resultsCard.setVisibility(View.GONE);
            moveTo(coords[0], coords[1], null);
            return;
        }
        searchProgress.setVisibility(View.VISIBLE);
        io.execute(() -> {
            try {
                JSONArray arr = new JSONArray(fetch("https://nominatim.openstreetmap.org/search?format=json&limit=6&q="
                        + URLEncoder.encode(query, "UTF-8")));
                main.post(() -> showResults(arr));
            } catch (Throwable t) {
                main.post(() -> {
                    searchProgress.setVisibility(View.GONE);
                    Toast.makeText(this, getString(R.string.loc_picker_search_error, String.valueOf(t.getMessage())),
                            Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void showResults(JSONArray arr) {
        searchProgress.setVisibility(View.GONE);
        resultsList.removeAllViews();
        if (arr.length() == 0) {
            resultsCard.setVisibility(View.GONE);
            Toast.makeText(this, R.string.loc_picker_search_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        LayoutInflater inflater = LayoutInflater.from(this);
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            double lat = o.optDouble("lat"), lng = o.optDouble("lon");
            String name = o.optString("display_name", "");
            if (!LocationPresets.valid(lat, lng)) continue;

            View row = inflater.inflate(android.R.layout.simple_list_item_2, resultsList, false);
            TextView t1 = row.findViewById(android.R.id.text1);
            TextView t2 = row.findViewById(android.R.id.text2);
            int comma = name.indexOf(',');
            t1.setText(comma > 0 ? name.substring(0, comma) : name);
            t2.setText(comma > 0 ? name.substring(comma + 1).trim() : LocationPresets.coords(lat, lng));
            t2.setMaxLines(1);
            t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
            int pad = Math.round(getResources().getDisplayMetrics().density * 20);
            row.setPadding(pad, row.getPaddingTop(), pad, row.getPaddingBottom());
            row.setBackgroundResource(resolveSelectable());
            row.setOnClickListener(v -> {
                resultsCard.setVisibility(View.GONE);
                moveTo(lat, lng, name);
            });
            resultsList.addView(row);
        }
        resultsCard.setVisibility(View.VISIBLE);
    }

    private int resolveSelectable() {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    // ---- reverse geocoding -------------------------------------------------------------------

    private void reverseGeocodeCenter() {
        GeoPoint c = (GeoPoint) map.getMapCenter();
        double lat = c.getLatitude(), lng = c.getLongitude();
        io.execute(() -> {
            String name = "";
            try {
                JSONObject o = new JSONObject(fetch(String.format(Locale.US,
                        "https://nominatim.openstreetmap.org/reverse?format=json&zoom=16&lat=%.6f&lon=%.6f", lat, lng)));
                name = o.optString("display_name", "");
            } catch (Throwable ignored) {
                // offline or rate-limited: coordinates are shown instead
            }
            String result = name;
            main.post(() -> {
                GeoPoint now = (GeoPoint) map.getMapCenter();
                if (labelLocked || Math.abs(now.getLatitude() - lat) > 1e-6 || Math.abs(now.getLongitude() - lng) > 1e-6) {
                    return; // the map moved on since this request
                }
                currentLabel = result;
                placeName.setText(result.isEmpty() ? getString(R.string.loc_picker_unnamed) : result);
            });
        });
    }

    private String fetch(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", getPackageName() + "/1.0 (LocationPicker)");
            conn.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag() + ",en;q=0.5");
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } finally {
            conn.disconnect();
        }
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && searchInput != null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        } catch (Throwable ignored) {}
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (map != null) map.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (map != null) map.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        try { io.shutdownNow(); } catch (Throwable ignored) {}
    }
}
