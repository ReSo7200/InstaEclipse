package ps.reso.instaeclipse.utils.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Lives in the companion app. Instagram -> companion settings sync: the hooked Instagram process
 * calls {@link #METHOD_PUSH} with its settings (and their {@code __igts_} / {@code __applied_}
 * stamps) at startup and after every change made in its own menu, and the companion's
 * {@code instaeclipse_cache} is updated per {@link PrefsMerge} (newer change wins).
 *
 * <p>Unlike the {@code ACTION_REQUEST_PREFS} round trip, this works when the companion is not
 * running (the call starts it) and doesn't depend on Instagram being alive and unfrozen when the
 * companion opens. Only supported Instagram packages may call it (uid checked via the binder).
 */
public class PrefsSyncProvider extends ContentProvider {

    public static final String AUTHORITY = CommonUtils.MY_PACKAGE_NAME + ".prefsync";
    public static final Uri URI = Uri.parse("content://" + AUTHORITY);
    public static final String METHOD_PUSH = "push";
    public static final String RESULT_UPDATED = "updated";
    private static final int MAX_ENTRIES = 2000;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Bundle call(@NonNull String method, @Nullable String arg, @Nullable Bundle extras) {
        if (!METHOD_PUSH.equals(method) || extras == null) return null;
        Context ctx = getContext();
        if (IpcSecurity.supportedCallerPackage(ctx, Binder.getCallingUid()) == null) {
            throw new SecurityException("caller is not a supported Instagram package");
        }
        if (extras.size() > MAX_ENTRIES) return null;

        Map<String, Object> incoming = new HashMap<>();
        for (String key : extras.keySet()) {
            Object v = extras.get(key);
            if (PrefsMerge.isSharedSetting(key, v) || PrefsMerge.isSharedStamp(key, v)) incoming.put(key, v);
        }

        int updated;
        synchronized (PrefsSyncProvider.class) {
            SharedPreferences cache = CommonUtils.companionCache(ctx);
            Map<String, Object> changes = PrefsMerge.select(incoming, cache.getAll());
            updated = changes.size();
            if (updated > 0) {
                SharedPreferences.Editor e = cache.edit();
                for (Map.Entry<String, Object> c : changes.entrySet()) {
                    Object v = c.getValue();
                    if (v instanceof Boolean) e.putBoolean(c.getKey(), (Boolean) v);
                    else if (v instanceof Integer) e.putInt(c.getKey(), (Integer) v);
                    else if (v instanceof String) e.putString(c.getKey(), (String) v);
                }
                e.commit();
            }
        }
        Bundle result = new Bundle();
        result.putInt(RESULT_UPDATED, updated);
        return result;
    }

    @Nullable
    @Override
    public Cursor query(@NonNull Uri uri, @Nullable String[] projection, @Nullable String selection,
                        @Nullable String[] selectionArgs, @Nullable String sortOrder) {
        return null;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return null;
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection,
                      @Nullable String[] selectionArgs) {
        return 0;
    }
}
