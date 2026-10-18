package ps.reso.instaeclipse.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ps.reso.instaeclipse.utils.core.CommonUtils

object AppUtils {
    private const val TAG = "InstaEclipse"

    private inline fun <T> attempt(operation: String, silent: Boolean = false, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        if (!silent) Log.w(TAG, "Failed to $operation", e)
        null
    }

    suspend fun forceStopPackage(
        context: Context,
        packageName: String,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        processExecutor: (Array<String>) -> Process = { Runtime.getRuntime().exec(it) }
    ): Boolean {
        val rooted = withContext(ioDispatcher) {
            var process: Process? = null
            try {
                process = processExecutor(arrayOf("su", "-c", "am force-stop $packageName"))
                if (process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.exitValue() == 0
                } else {
                    process.destroy()
                    false
                }
            } catch (e: Throwable) {
                false
            } finally {
                process?.destroy()
            }
        }

        if (rooted) {
            ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, context.getString(ps.reso.instaeclipse.R.string.home_restart_done), android.widget.Toast.LENGTH_SHORT).show()
            kotlinx.coroutines.delay(400)
            openInstagram(context, packageName)
            return true
        }

        ps.reso.instaeclipse.utils.toast.IeToast.makeText(context, context.getString(ps.reso.instaeclipse.R.string.ig_toast_restart_manual), android.widget.Toast.LENGTH_LONG).show()
        attempt("open $packageName app settings") {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
        return false
    }

    suspend fun forceStopInstagram(
        context: Context,
        packageName: String = CommonUtils.IG_PACKAGE_NAME,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        processExecutor: (Array<String>) -> Process = { Runtime.getRuntime().exec(it) }
    ): Boolean = forceStopPackage(context, packageName, ioDispatcher, processExecutor)

    suspend fun restartInstagram(
        context: Context,
        packageName: String = CommonUtils.IG_PACKAGE_NAME,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        processExecutor: (Array<String>) -> Process = { Runtime.getRuntime().exec(it) }
    ): Boolean = forceStopPackage(context, packageName, ioDispatcher, processExecutor)

    fun openInstagram(
        context: Context,
        packageName: String = CommonUtils.IG_PACKAGE_NAME
    ): Boolean {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
            ?: pm.getLeanbackLaunchIntentForPackage(packageName)
            ?: Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(packageName)
            }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return attempt("launch $packageName") {
            context.startActivity(launchIntent)
            true
        } ?: false
    }
}
