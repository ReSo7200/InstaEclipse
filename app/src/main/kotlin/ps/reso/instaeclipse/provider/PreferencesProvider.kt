package ps.reso.instaeclipse.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import ps.reso.instaeclipse.core.PreferencesManager

class PreferencesProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        android.util.Log.d("InstaEclipse.Sync", "PreferencesProvider.call: method=$method, extrasKeys=${extras?.keySet()?.size}")
        when (method) {
            "saveAll", "putAll" -> {
                if (extras != null) {
                    val editor = PreferencesManager.getPrefs(ctx).edit()
                    for (key in extras.keySet()) {
                        when (val value = extras.get(key)) {
                            is Boolean -> {
                                editor.putBoolean(key, value)
                                PreferencesManager.updateFieldDirectly(key, value)
                            }
                            is Int -> editor.putInt(key, value)
                            is Long -> editor.putLong(key, value)
                            is Float -> editor.putFloat(key, value)
                            is Double -> editor.putString(key, value.toString())
                            is String -> {
                                editor.putString(key, value)
                                if (key == PreferencesManager.KEY_DOWNLOAD_SAF_URI) {
                                    PreferencesManager.downloadSafUri = value
                                    ps.reso.instaeclipse.utils.feature.FeatureFlags.downloaderCustomUri = value
                                } else if (key == PreferencesManager.KEY_DOWNLOAD_SAF_PATH) {
                                    ps.reso.instaeclipse.utils.feature.FeatureFlags.downloaderCustomPath = value
                                }
                            }
                        }
                    }
                    val committed = editor.commit()
                    android.util.Log.d("InstaEclipse.Sync", "PreferencesProvider saveAll committed=$committed")
                    PreferencesManager.loadAll(ctx)
                    PreferencesManager.onPreferenceChangedListener?.invoke()
                }
                return Bundle.EMPTY
            }
            "getAll" -> {
                val bundle = Bundle()
                val prefs = PreferencesManager.getPrefs(ctx)
                for ((k, v) in prefs.all) {
                    when (v) {
                        is Boolean -> bundle.putBoolean(k, v)
                        is Int -> bundle.putInt(k, v)
                        is Long -> bundle.putLong(k, v)
                        is Float -> bundle.putFloat(k, v)
                        is String -> bundle.putString(k, v)
                    }
                }
                return bundle
            }
            "setBoolean" -> {
                if (arg != null && extras != null) {
                    val value = extras.getBoolean("value", false)
                    PreferencesManager.setBoolean(ctx, arg, value, notifyIpc = false)
                    PreferencesManager.onPreferenceChangedListener?.invoke()
                }
            }
            "setBooleans" -> {
                if (extras != null) {
                    for (key in extras.keySet()) {
                        val value = extras.getBoolean(key, false)
                        PreferencesManager.setBoolean(ctx, key, value, notifyIpc = false)
                    }
                    PreferencesManager.onPreferenceChangedListener?.invoke()
                }
            }
            "setString" -> {
                if (arg != null && extras != null) {
                    val value = extras.getString("value", "")
                    PreferencesManager.setString(ctx, arg, value, notifyIpc = false)
                    PreferencesManager.onPreferenceChangedListener?.invoke()
                }
            }
            "setInt" -> {
                if (arg != null && extras != null) {
                    val value = extras.getInt("value", 0)
                    PreferencesManager.setInt(ctx, arg, value, notifyIpc = false)
                    PreferencesManager.onPreferenceChangedListener?.invoke()
                }
            }
        }
        return Bundle.EMPTY
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
