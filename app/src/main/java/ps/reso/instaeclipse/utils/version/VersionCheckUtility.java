package ps.reso.instaeclipse.utils.version;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class VersionCheckUtility {

    private static final String CURRENT_VERSION = "0.5.1"; // Current version
    private static final String VERSION_CHECK_URL = "https://raw.githubusercontent.com/ReSo7200/InstaEclipse/refs/heads/main/version.json"; // JSON URL

    public static void checkForUpdates(Context context) {
        new AsyncTask<Void, Void, VersionCheck>() {
            @Override
            protected VersionCheck doInBackground(Void... voids) {
                try {
                    URL url = new URL(VERSION_CHECK_URL);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");

                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    return new Gson().fromJson(response.toString(), VersionCheck.class);
                } catch (Exception e) {
                    e.printStackTrace();
                    return null;
                }
            }

            @Override
            protected void onPostExecute(VersionCheck versionCheck) {
                if (versionCheck != null) {
                    handleVersionCheckResult(context, versionCheck);
                } else {
                    showErrorDialog(context);
                }
            }
        }.execute();
    }

    private static void handleVersionCheckResult(Context context, VersionCheck versionCheck) {
        String latestVersion = versionCheck.getLatestVersion();
        if (!CURRENT_VERSION.equals(latestVersion)) {
            showUpdateDialog(context, versionCheck.getUpdateUrl(), latestVersion);
        }
    }

    private static void showUpdateDialog(Context context, String updateUrl, String newVersion) {
        new MaterialAlertDialogBuilder(context)
                .setTitle("Update Available")
                .setMessage("A new version (" + newVersion + ") is available. Would you like to update now?")
                .setPositiveButton("Update", (dialogInterface, which) -> {
                    // Open the update URL in the browser
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl));
                    context.startActivity(browserIntent);
                })
                .setNegativeButton("Later", (dialogInterface, which) -> {
                    // Dismiss the dialog
                    dialogInterface.dismiss();
                })
                .show();
    }

    private static void showErrorDialog(Context context) {
        new MaterialAlertDialogBuilder(context)
                .setTitle("Error")
                .setMessage("Failed to check for updates. Please try again later.")
                .setPositiveButton("OK", (dialogInterface, which) -> dialogInterface.dismiss())
                .show();
    }
}
