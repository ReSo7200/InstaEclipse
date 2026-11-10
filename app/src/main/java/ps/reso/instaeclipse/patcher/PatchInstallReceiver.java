package ps.reso.instaeclipse.patcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

import ps.reso.instaeclipse.R;
import ps.reso.instaeclipse.utils.log.ModuleLog;
import ps.reso.instaeclipse.utils.toast.IeToast;

/** Handles {@link PackageInstaller} session status for the patched Instagram install. */
public class PatchInstallReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                Intent confirm = getConfirmIntent(intent);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirm);
                    } catch (Throwable t) {
                        ModuleLog.line("(IE|Patch) could not open the installer: " + t);
                    }
                }
                break;
            case PackageInstaller.STATUS_SUCCESS:
                ModuleLog.line("(IE|Patch) install succeeded");
                IeToast.makeText(context, context.getString(R.string.patch_install_success), Toast.LENGTH_LONG).show();
                break;
            default:
                ModuleLog.line("(IE|Patch) install failed: " + status + " " + message);
                IeToast.makeText(context,
                        context.getString(R.string.patch_install_failed, message == null ? "" : message),
                        Toast.LENGTH_LONG).show();
                break;
        }
    }

    @SuppressWarnings("deprecation")
    private static Intent getConfirmIntent(Intent intent) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }
}
