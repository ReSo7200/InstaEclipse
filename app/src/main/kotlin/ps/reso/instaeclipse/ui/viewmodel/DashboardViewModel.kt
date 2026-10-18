package ps.reso.instaeclipse.ui.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ps.reso.instaeclipse.R
import ps.reso.instaeclipse.core.HookCategory
import ps.reso.instaeclipse.core.HookRegistry
import ps.reso.instaeclipse.core.ModuleStatus
import ps.reso.instaeclipse.core.PreferencesManager
import ps.reso.instaeclipse.utils.AppUtils
import ps.reso.instaeclipse.utils.core.CommonUtils
import ps.reso.instaeclipse.utils.feature.FeatureFlags
import ps.reso.instaeclipse.utils.log.LogMirrorProvider
import ps.reso.instaeclipse.utils.log.Logging
import ps.reso.instaeclipse.utils.toast.IeToast
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private val TS_PREFIX = Regex("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} .*", RegexOption.DOT_MATCHES_ALL)
private const val TS_LEN = 23
private const val MAX_ENTRIES = 6000
private const val MAX_READ_BYTES = 3L * 1024 * 1024
private const val MAX_EXPORT_CHARS = 250_000

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _effects = Channel<DashboardUiEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    private val liveReplies = ConcurrentHashMap<String, String>()

    private val logReplyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if ("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT" == action) {
                val key = intent.getStringExtra("key")
                val value = intent.getIntExtra("value", 0)
                if (key != null) {
                    PreferencesManager.setInt(context, key, value, notifyIpc = false)
                    if (key == "forceReelQuality") {
                        _uiState.update { it.copy(forceReelQuality = value) }
                    }
                }
                refreshState()
            } else if ("ps.reso.instaeclipse.ACTION_UPDATE_PREF" == action) {
                val key = intent.getStringExtra("key")
                if (key != null) {
                    val value = intent.getBooleanExtra("value", false)
                    PreferencesManager.setBoolean(context, key, value, notifyIpc = false)
                }
                refreshState()
            } else if ("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING" == action) {
                val key = intent.getStringExtra("key")
                val value = intent.getStringExtra("value")
                if (key != null && value != null) {
                    PreferencesManager.setString(context, key, value, notifyIpc = false)
                }
                refreshState()
            } else if ("ps.reso.instaeclipse.ACTION_SEND_PREFS" == action) {
                // Instagram answered: it is running with the module. Its settings already reach
                // this app through the signed sync (PrefsSyncProvider, merged per key by time),
                // so they are only re-read here, never written over newer local changes.
                PreferencesManager.loadAll(context)
                refreshState()
            } else if (CommonUtils.ACTION_LOGS_REPLY == action) {
                val logText = intent.getStringExtra(CommonUtils.EXTRA_LOG_TEXT) ?: ""
                var source = intent.getStringExtra(CommonUtils.EXTRA_LOG_SOURCE)
                if (source.isNullOrEmpty()) source = CommonUtils.IG_PACKAGE_NAME
                liveReplies[source] = logText
                reloadLogs()
            }
        }
    }

    init {
        PreferencesManager.init(application)
        PreferencesManager.loadAll(application)
        ModuleStatus.checkStatus()

        registerLogReceiver()
        detectInstagramStatus()
        refreshState()
        requestPrefsFromInstagram()

        PreferencesManager.onPreferenceChangedListener = {
            viewModelScope.launch {
                PreferencesManager.loadAll(getApplication())
                refreshState()
            }
        }
    }

    private fun registerLogReceiver() {
        val filter = IntentFilter().apply {
            addAction(CommonUtils.ACTION_LOGS_REPLY)
            addAction("ps.reso.instaeclipse.ACTION_UPDATE_PREF")
            addAction("ps.reso.instaeclipse.ACTION_UPDATE_PREF_INT")
            addAction("ps.reso.instaeclipse.ACTION_UPDATE_PREF_STRING")
            addAction("ps.reso.instaeclipse.ACTION_SEND_PREFS")
        }
        val app = getApplication<Application>()
        // Not exported: replies from Instagram come through IpcSecurity.requestReply (a private
        // callback), so no other app can feed settings or logs into this receiver.
        ContextCompat.registerReceiver(app, logReplyReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private val pendingReplies = mutableListOf<ps.reso.instaeclipse.utils.ipc.ModuleReplies.Pending>()

    /** Asks every installed Instagram for a reply, through the private, nonce-checked callback. */
    private fun requestFromInstagram(action: String, replyAction: String) {
        val app = getApplication<Application>()
        val targets = _uiState.value.installedInstagramVariants.map { it.packageName }.ifEmpty {
            listOf(CommonUtils.IG_PACKAGE_NAME)
        }
        for (pkg in targets) {
            try {
                val nonce = java.util.UUID.randomUUID().toString().replace("-", "")
                val req = Intent(action).setPackage(pkg)
                    .putExtra(ps.reso.instaeclipse.utils.core.IpcSecurity.EXTRA_NONCE, nonce)
                ps.reso.instaeclipse.utils.core.IpcSecurity.requestReply(app, req, replyAction, logReplyReceiver)
                    ?.let { synchronized(pendingReplies) { pendingReplies.add(it) } }
            } catch (ignored: Throwable) {}
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            getApplication<Application>().unregisterReceiver(logReplyReceiver)
        } catch (ignored: Throwable) {}
        synchronized(pendingReplies) {
            pendingReplies.forEach { it.cancel() }
            pendingReplies.clear()
        }
    }

    fun requestPrefsFromInstagram() {
        requestFromInstagram("ps.reso.instaeclipse.ACTION_REQUEST_PREFS", "ps.reso.instaeclipse.ACTION_SEND_PREFS")
    }

    fun refreshStatus() {
        ModuleStatus.checkStatus()
        PreferencesManager.loadAll(getApplication())
        detectInstagramStatus()
        refreshState()
        requestPrefsFromInstagram()
    }

    fun refreshState() {
        val app = getApplication<Application>()
        val allHooks = HookRegistry.getAllHooks()
        val enabledHooks = allHooks.filter { PreferencesManager.isHookEnabled(app, it.id) }.map { it.id }.toSet()
        val currentForceReelQuality = PreferencesManager.getForceReelQuality(app)

        _uiState.update { current ->
            current.copy(
                isModuleActive = ModuleStatus.isModuleActive.value,
                detectedFramework = ModuleStatus.frameworkName.value,
                themeMode = PreferencesManager.appThemeMode,
                isAppAmoledEnabled = PreferencesManager.isAppAmoledEnabled,
                showAppControls = PreferencesManager.showAppControls,
                downloadSafUri = PreferencesManager.downloadSafUri,
                enabledHookIds = enabledHooks,
                customShortcutIds = PreferencesManager.getCustomShortcuts(app),
                ghostMasterChipIds = PreferencesManager.getGhostMasterChips(app),
                allHooks = allHooks,
                forceReelQuality = currentForceReelQuality,
                navTabOrder = FeatureFlags.navTabOrder,
                exactTimestampFormat = PreferencesManager.getString(app, "exactTimestampFormat", FeatureFlags.exactTimestampFormat)
            )
        }
    }

    fun detectInstagramStatus() {
        val pm = getApplication<Application>().packageManager
        val installed = mutableListOf<InstalledInstagramInfo>()

        for (pkg in CommonUtils.SUPPORTED_PACKAGES) {
            try {
                val info = pm.getPackageInfo(pkg, 0)
                val ver = info.versionName ?: "Unknown"
                val label = CommonUtils.getVariantLabel(pkg)
                installed.add(InstalledInstagramInfo(pkg, ver, label))
            } catch (ignored: PackageManager.NameNotFoundException) {}
        }

        val active = installed.firstOrNull { it.packageName == CommonUtils.IG_PACKAGE_NAME }
            ?: installed.firstOrNull()

        _uiState.update {
            it.copy(
                installedInstagramVariants = installed,
                activeInstagram = active
            )
        }
    }

    fun setActiveInstagramPackage(pkg: String) {
        val target = _uiState.value.installedInstagramVariants.firstOrNull { it.packageName == pkg }
        if (target != null) {
            _uiState.update { it.copy(activeInstagram = target, showVariantDialog = false) }
        }
    }

    fun setShowVariantDialog(show: Boolean) {
        _uiState.update { it.copy(showVariantDialog = show) }
    }

    private var isStoppingInstagram = false

    fun openInstagram() {
        val pkg = _uiState.value.activeInstagram?.packageName
        val app = getApplication<Application>()
        if (pkg == null || !AppUtils.openInstagram(app, pkg)) {
            IeToast.makeText(app, app.getString(R.string.not_installed_instagram), Toast.LENGTH_SHORT).show()
        }
    }

    fun openInstagramAppInfo() {
        val pkg = _uiState.value.activeInstagram?.packageName
        val app = getApplication<Application>()
        if (pkg == null) {
            IeToast.makeText(app, app.getString(R.string.not_installed_instagram), Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$pkg")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        app.startActivity(intent)
    }

    fun forceStopInstagram() {
        if (isStoppingInstagram) return
        val pkg = _uiState.value.activeInstagram?.packageName
        val app = getApplication<Application>()
        if (pkg == null) {
            IeToast.makeText(app, app.getString(R.string.not_installed_instagram), Toast.LENGTH_SHORT).show()
            return
        }
        isStoppingInstagram = true
        viewModelScope.launch {
            try {
                val restarted = AppUtils.forceStopInstagram(app, pkg)
                if (restarted) {
                    _uiState.update { it.copy(isModuleActive = false) }
                }
            } finally {
                isStoppingInstagram = false
            }
        }
    }

    fun setHookEnabled(id: String, value: Boolean) {
        if (id == "forceReelQuality") return
        val app = getApplication<Application>()
        PreferencesManager.setBoolean(app, id, value)

        if (id == "isExtremeMode" && value) {
            PreferencesManager.setBoolean(app, "isDistractionFree", true)
        } else if (id == "disableReels" && !value) {
            PreferencesManager.setBoolean(app, "disableReelsExceptDM", false)
        } else if (id == "disableReelsExceptDM" && value) {
            PreferencesManager.setBoolean(app, "disableReels", true)
        }

        refreshState()
    }

    fun setForceReelQuality(value: Int) {
        val app = getApplication<Application>()
        PreferencesManager.setInt(app, "forceReelQuality", value)
        _uiState.update { it.copy(forceReelQuality = value) }
    }

    fun setExactTimestampFormat(format: String) {
        val app = getApplication<Application>()
        PreferencesManager.setString(app, "exactTimestampFormat", format)
        FeatureFlags.exactTimestampFormat = format
        _uiState.update { it.copy(exactTimestampFormat = format) }
    }

    fun setAllHooksEnabled(ids: List<String>, value: Boolean) {
        val app = getApplication<Application>()
        ids.forEach { id ->
            PreferencesManager.setBoolean(app, id, value, notifyIpc = false)
        }
        PreferencesManager.setBoolean(app, ids.firstOrNull() ?: "", value, notifyIpc = true)

        _uiState.update { current ->
            val updated = if (value) current.enabledHookIds + ids else current.enabledHookIds - ids.toSet()
            current.copy(enabledHookIds = updated)
        }
    }

    fun toggleCustomShortcut(id: String) {
        val app = getApplication<Application>()
        val current = _uiState.value.customShortcutIds.toMutableList()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        PreferencesManager.setCustomShortcuts(app, current)
        _uiState.update { it.copy(customShortcutIds = current) }
    }

    fun toggleGhostMasterChip(id: String) {
        val app = getApplication<Application>()
        val current = _uiState.value.ghostMasterChipIds.toMutableList()
        if (current.contains(id)) {
            current.remove(id)
        } else {
            current.add(id)
        }
        PreferencesManager.setGhostMasterChips(app, current)
        _uiState.update { it.copy(ghostMasterChipIds = current) }
    }

    fun setDownloadSafUri(uri: String) {
        val app = getApplication<Application>()
        PreferencesManager.setString(app, PreferencesManager.KEY_DOWNLOAD_SAF_URI, uri)
        _uiState.update { it.copy(downloadSafUri = uri) }
        notifyEffect(DashboardUiEffect.ShowToast("Download folder updated"))
    }

    fun setThemeMode(mode: PreferencesManager.ThemeMode) {
        val app = getApplication<Application>()
        PreferencesManager.setString(app, PreferencesManager.KEY_APP_THEME_MODE, mode.name)
        PreferencesManager.appThemeMode = mode
        _uiState.update { it.copy(themeMode = mode) }
    }

    fun setAppAmoled(enabled: Boolean) {
        val app = getApplication<Application>()
        PreferencesManager.setBoolean(app, PreferencesManager.KEY_APP_AMOLED_THEME, enabled)
        PreferencesManager.isAppAmoledEnabled = enabled
        _uiState.update { it.copy(isAppAmoledEnabled = enabled) }
    }

    fun setAppControlsEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        PreferencesManager.setBoolean(app, PreferencesManager.KEY_SHOW_APP_CONTROLS, enabled)
        PreferencesManager.showAppControls = enabled
        _uiState.update { it.copy(showAppControls = enabled) }
    }

    fun selectDestination(destination: AppNavDestination) {
        _uiState.update {
            it.copy(
                currentDestination = destination,
                showSettings = false,
                settingsSubpage = null,
                selectedFeatureCategory = null
            )
        }
        if (destination == AppNavDestination.LOGS) {
            refreshLogs()
        }
    }

    fun showSettingsScreen() {
        _uiState.update { it.copy(showSettings = true, settingsSubpage = null) }
    }

    fun selectSettingsSubpage(subpage: SettingsSubpage?) {
        _uiState.update { it.copy(settingsSubpage = subpage) }
    }

    fun selectFeatureCategory(category: HookCategory?) {
        _uiState.update { it.copy(selectedFeatureCategory = category) }
    }

    fun handleBack(): Boolean {
        val current = _uiState.value
        if (current.isSearchActive) {
            _uiState.update { it.copy(isSearchActive = false, searchQuery = "") }
            return true
        }
        if (current.selectedFeatureCategory != null) {
            _uiState.update { it.copy(selectedFeatureCategory = null) }
            return true
        }
        if (current.settingsSubpage != null) {
            _uiState.update { it.copy(settingsSubpage = null) }
            return true
        }
        if (current.showSettings) {
            _uiState.update { it.copy(showSettings = false) }
            return true
        }
        if (current.currentDestination != AppNavDestination.HOME) {
            _uiState.update { it.copy(currentDestination = AppNavDestination.HOME) }
            return true
        }
        return false
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSearchActive(active: Boolean) {
        _uiState.update { it.copy(isSearchActive = active, searchQuery = if (!active) "" else it.searchQuery) }
    }

    fun refreshLogs() {
        _uiState.update { it.copy(isLogLoading = true) }
        requestFromInstagram(CommonUtils.ACTION_REQUEST_LOGS, CommonUtils.ACTION_LOGS_REPLY)
        reloadLogs()
    }

    private fun reloadLogs() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val entries = readAllLogEntries(app)
            val combinedText = entries.joinToString("\n") { it.full }
            val hasTargets = _uiState.value.installedInstagramVariants.isNotEmpty()
            val status = if (!hasTargets) {
                LogSourceStatus.NONE
            } else if (liveReplies.isNotEmpty()) {
                LogSourceStatus.LIVE
            } else {
                LogSourceStatus.SAVED
            }

            withContext(Dispatchers.Main) {
                _uiState.update {
                    it.copy(
                        logEntries = entries,
                        logContent = combinedText,
                        logLineCount = entries.size,
                        logSourceStatus = status,
                        isLogLoading = false
                    )
                }
            }
        }
    }

    private fun classifyLog(s: String): LogSeverity {
        val l = s.lowercase(Locale.ROOT)
        if (s.contains("❌") || l.contains("exception") || l.contains("error") || l.contains("failed") || l.contains("fatal") || l.contains("crash")) {
            return LogSeverity.ERROR
        }
        if (s.contains("⚠") || s.contains("⚠️") || l.contains("warn") || l.contains("not found") || l.contains("timeout") || l.contains("timed out") || l.contains("skipp") || l.contains("fallback")) {
            return LogSeverity.WARNING
        }
        if (s.contains("✅") || s.contains("✔") || l.contains("hooked") || l.contains("success") || l.contains("installed") || l.contains(" ok ") || l.endsWith(" ok")) {
            return LogSeverity.HOOKED
        }
        return LogSeverity.INFO
    }

    private fun parseInto(
        out: MutableList<LogEntry>,
        lines: List<String>,
        prefix: String,
        idGen: AtomicLong
    ) {
        var curTs: String? = null
        var cur: StringBuilder? = null
        var lastTs = ""
        for (line in lines) {
            val starts = line.length > TS_LEN && TS_PREFIX.matches(line)
            if (starts || cur == null) {
                if (cur != null) {
                    out.add(makeEntry(curTs, cur.toString(), prefix, idGen.incrementAndGet()))
                }
                curTs = if (starts) line.substring(0, TS_LEN) else lastTs
                lastTs = curTs
                cur = StringBuilder(line)
            } else {
                cur.append('\n').append(line)
            }
        }
        if (cur != null) {
            out.add(makeEntry(curTs, cur.toString(), prefix, idGen.incrementAndGet()))
        }
    }

    private fun makeEntry(ts: String?, full: String, prefix: String, id: Long): LogEntry {
        val safeTs = ts ?: ""
        val display = if (full.length > TS_LEN && full.startsWith(safeTs) && safeTs.isNotEmpty()) {
            full.substring(11, TS_LEN) + "  " + prefix + full.substring(TS_LEN + 1)
        } else {
            prefix + full
        }
        val fullWithPrefix = if (prefix.isEmpty()) {
            full
        } else if (safeTs.isNotEmpty() && full.startsWith(safeTs) && full.length > TS_LEN) {
            safeTs + " " + prefix + full.substring(TS_LEN + 1)
        } else {
            prefix + full
        }
        return LogEntry(
            id = id,
            timestamp = safeTs,
            full = fullWithPrefix,
            display = display,
            severity = classifyLog(full)
        )
    }

    private fun sourcePrefix(pkg: String): String {
        return if (pkg == CommonUtils.IG_PACKAGE_NAME) "" else "[$pkg] "
    }

    private fun splitLines(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        return text.lines().filter { it.isNotEmpty() }
    }

    private fun readTail(f: File): List<String> {
        val lines = ArrayDeque<String>()
        if (!f.isFile || f.length() == 0L) return emptyList()
        try {
            FileInputStream(f).use { inStream ->
                val len = f.length()
                var skipFirst = false
                if (len > MAX_READ_BYTES) {
                    var skip = len - MAX_READ_BYTES
                    while (skip > 0) {
                        val s = inStream.skip(skip)
                        if (s <= 0) break
                        skip -= s
                    }
                    skipFirst = true
                }
                BufferedReader(InputStreamReader(inStream, StandardCharsets.UTF_8)).use { br ->
                    var line: String?
                    while (br.readLine().also { line = it } != null) {
                        if (skipFirst) {
                            skipFirst = false
                            continue
                        }
                        val l = line ?: continue
                        if (l.isEmpty()) continue
                        lines.addLast(l)
                        if (lines.size > MAX_ENTRIES * 4) {
                            lines.pollFirst()
                        }
                    }
                }
            }
        } catch (ignored: Throwable) {}
        return lines.toList()
    }

    private fun readAllLogEntries(context: Context): List<LogEntry> {
        val out = mutableListOf<LogEntry>()
        val seen = mutableSetOf<String>()
        val idGen = AtomicLong(0)

        val files = LogMirrorProvider.mirrorDir(context).listFiles { _, n -> n.endsWith(".log") }
        if (files != null) {
            for (f in files) {
                val pkg = f.name.removeSuffix(".log")
                var lines = readTail(f)
                if (lines.isEmpty()) {
                    val reply = liveReplies[pkg]
                    if (reply != null) lines = splitLines(reply)
                }
                if (lines.isNotEmpty()) seen.add(pkg)
                parseInto(out, lines, sourcePrefix(pkg), idGen)
            }
        }

        for ((pkg, reply) in liveReplies) {
            if (seen.contains(pkg)) continue
            parseInto(out, splitLines(reply), sourcePrefix(pkg), idGen)
        }

        val companion = Logging.getSnapshot()
        val companionTag = context.getString(R.string.logs_source_companion) + " "
        parseInto(out, splitLines(companion), companionTag, idGen)

        out.sortWith { a, b -> a.timestamp.compareTo(b.timestamp) }
        return if (out.size > MAX_ENTRIES) {
            out.subList(out.size - MAX_ENTRIES, out.size)
        } else {
            out
        }
    }


    fun clearLogs() {
        val app = getApplication<Application>()
        liveReplies.clear()
        Logging.clear()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val files = LogMirrorProvider.mirrorDir(app).listFiles()
                if (files != null) {
                    for (f in files) {
                        try {
                            FileOutputStream(f, false).close()
                        } catch (ignored: Throwable) {}
                    }
                }
            } catch (ignored: Throwable) {}
        }
        for (pkg in CommonUtils.SUPPORTED_PACKAGES) {
            try {
                val clear = Intent(CommonUtils.ACTION_CLEAR_LOGS).apply {
                    `package` = pkg
                }
                CommonUtils.broadcastToInstagram(app, clear)
            } catch (ignored: Throwable) {}
        }
        _uiState.update {
            it.copy(
                logEntries = emptyList(),
                logContent = "",
                logLineCount = 0,
                logSourceStatus = if (it.installedInstagramVariants.isEmpty()) LogSourceStatus.NONE else LogSourceStatus.SAVED
            )
        }
        notifyEffect(DashboardUiEffect.ShowToast(app.getString(R.string.logs_cleared)))
    }

    fun copyLogs(context: Context) {
        val items = _uiState.value.filteredLogEntries
        if (items.isEmpty()) {
            notifyEffect(DashboardUiEffect.ShowToast(context.getString(R.string.logs_nothing_to_copy)))
            return
        }
        val sb = StringBuilder()
        for (i in items.indices.reversed()) {
            val s = items[i].full
            if (sb.length + s.length + 1 > MAX_EXPORT_CHARS) break
            sb.insert(0, s + "\n")
        }
        val text = sb.toString()
        if (text.isBlank()) {
            notifyEffect(DashboardUiEffect.ShowToast(context.getString(R.string.logs_nothing_to_copy)))
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("InstaEclipse logs", text))
        notifyEffect(DashboardUiEffect.ShowToast(context.getString(R.string.logs_copied)))
    }

    fun shareLogs(context: Context) {
        val items = _uiState.value.filteredLogEntries
        if (items.isEmpty()) {
            notifyEffect(DashboardUiEffect.ShowToast(context.getString(R.string.logs_nothing_to_copy)))
            return
        }
        val sb = StringBuilder()
        for (i in items.indices.reversed()) {
            val s = items[i].full
            if (sb.length + s.length + 1 > MAX_EXPORT_CHARS) break
            sb.insert(0, s + "\n")
        }
        val text = sb.toString()
        if (text.isBlank()) {
            notifyEffect(DashboardUiEffect.ShowToast(context.getString(R.string.logs_nothing_to_copy)))
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.logs_share_subject))
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            val chooser = Intent.createChooser(send, context.getString(R.string.logs_share_title)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (t: Throwable) {
            notifyEffect(DashboardUiEffect.ShowToast(t.message ?: "Failed to share logs"))
        }
    }

    fun copyLogEntry(context: Context, entry: LogEntry) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("InstaEclipse log entry", entry.full))
        notifyEffect(DashboardUiEffect.ShowToast(context.getString(R.string.logs_entry_copied)))
    }

    fun setLogFilter(filter: LogFilter) {
        _uiState.update { it.copy(selectedLogFilter = filter) }
    }

    fun setLogSearchQuery(query: String) {
        _uiState.update { it.copy(logSearchQuery = query) }
    }

    fun setNavTabOrder(order: String) {
        val app = getApplication<Application>()
        PreferencesManager.setString(app, "navTabOrder", order)
        FeatureFlags.navTabOrder = order
        _uiState.update { it.copy(navTabOrder = order) }
        notifyEffect(DashboardUiEffect.ShowToast(app.getString(R.string.ig_toast_saved)))
    }

    fun exportConfigJson(): String = PreferencesManager.exportConfigJson(getApplication())

    fun importConfigJson(json: String) {
        try {
            val count = PreferencesManager.importConfigJson(getApplication(), json)
            refreshState()
            notifyEffect(DashboardUiEffect.ShowToast("Successfully imported $count settings"))
        } catch (t: Throwable) {
            notifyEffect(DashboardUiEffect.ShowToast("Import failed: ${t.message}"))
        }
    }

    fun notifyEffect(effect: DashboardUiEffect) {
        viewModelScope.launch {
            _effects.send(effect)
        }
    }
}

