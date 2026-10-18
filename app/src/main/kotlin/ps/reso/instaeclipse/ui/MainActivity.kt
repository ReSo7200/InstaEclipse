package ps.reso.instaeclipse.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ps.reso.instaeclipse.R
import ps.reso.instaeclipse.mods.location.LocationPickerActivity
import ps.reso.instaeclipse.ui.component.navigation.EclipseApp
import ps.reso.instaeclipse.ui.theme.AppTheme
import ps.reso.instaeclipse.ui.theme.ThemeCustomizerActivity
import ps.reso.instaeclipse.ui.viewmodel.DashboardUiEffect
import ps.reso.instaeclipse.ui.viewmodel.DashboardViewModel
import ps.reso.instaeclipse.utils.feature.FeatureFlags
import ps.reso.instaeclipse.utils.log.Logging

class MainActivity : ComponentActivity() {

    private val viewModel: DashboardViewModel by viewModels()

    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(uri, takeFlags)
            viewModel.setDownloadSafUri(uri.toString())
        }
    }

    private val exportConfigLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
            if (uri != null) {
                try {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(viewModel.exportConfigJson().toByteArray())
                    }
                    viewModel.notifyEffect(DashboardUiEffect.ShowToast(getString(R.string.config_exported)))
                } catch (t: Throwable) {
                    viewModel.notifyEffect(DashboardUiEffect.ShowToast(getString(R.string.config_export_failed)))
                }
            }
        }

    private val importConfigLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) {
                try {
                    val json = contentResolver.openInputStream(uri)?.use { input ->
                        input.bufferedReader().readText()
                    } ?: ""
                    viewModel.importConfigJson(json)
                } catch (t: Throwable) {
                    viewModel.notifyEffect(DashboardUiEffect.ShowToast(getString(R.string.config_import_failed)))
                }
            }
        }

    private val locationPickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val lat = result.data!!.getDoubleExtra(LocationPickerActivity.RESULT_LAT, 0.0)
                val lng = result.data!!.getDoubleExtra(LocationPickerActivity.RESULT_LNG, 0.0)
                viewModel.notifyEffect(DashboardUiEffect.ShowToast("Coordinates set to: $lat, $lng"))
            }
        }

    private val themeCustomizerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.notifyEffect(DashboardUiEffect.ShowToast("Theme settings updated"))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        Logging.init(this, "instaeclipse_companion.log")

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = uiState.isDarkTheme(systemDark)
            val snackbarHostState = remember { SnackbarHostState() }

            AppTheme(
                darkTheme = darkTheme,
                dynamicColor = true,
                amoled = uiState.isAppAmoledEnabled
            ) {
                EclipseApp(
                    viewModel = viewModel,
                    uiState = uiState,
                    darkTheme = darkTheme,
                    onPickFolder = { folderPicker.launch(null) },
                    onExportConfig = { exportConfigLauncher.launch("instaeclipse_settings.json") },
                    onImportConfig = { importConfigLauncher.launch(arrayOf("application/json")) },
                    onOpenLocationPicker = {
                        val intent = Intent(this@MainActivity, LocationPickerActivity::class.java).apply {
                            putExtra(LocationPickerActivity.EXTRA_LAT, FeatureFlags.spoofLat)
                            putExtra(LocationPickerActivity.EXTRA_LNG, FeatureFlags.spoofLng)
                        }
                        locationPickerLauncher.launch(intent)
                    },
                    onOpenThemeCustomizer = {
                        val intent = Intent(this@MainActivity, ThemeCustomizerActivity::class.java)
                        themeCustomizerLauncher.launch(intent)
                    },
                    snackbarHostState = snackbarHostState
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStatus()
    }
}
