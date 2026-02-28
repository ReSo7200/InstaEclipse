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

import ps.reso.instaeclipse.R;

public class VersionCheckUtility {

    private static final String CURRENT_VERSION = "0.4.5"; // Current version
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
                .setTitle(context.getString(R.string.ig_update_available))
                .setMessage(context.getString(R.string.ig_update_message, newVersion))
                .setPositiveButton(R.string.ig_update_action_update, (dialogInterface, which) -> {
                    // Open the update URL in the browser
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(updateUrl));
                    context.startActivity(browserIntent);
                })
                .setNegativeButton(R.string.ig_update_action_later, (dialogInterface, which) -> {
                    // Dismiss the dialog
                    dialogInterface.dismiss();
                })
                .show();
    }

    private static void showErrorDialog(Context context) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(context.getString(R.string.ig_update_error_title))
                .setMessage(context.getString(R.string.ig_update_error_message))
                .setPositiveButton(android.R.string.ok, (dialogInterface, which) -> dialogInterface.dismiss())
                .show();
    }
}
