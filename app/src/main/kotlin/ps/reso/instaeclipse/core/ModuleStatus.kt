package ps.reso.instaeclipse.core

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import java.io.File

object ModuleStatus {

    private val _isModuleActive = mutableStateOf(false)
    val isModuleActive: State<Boolean> = _isModuleActive

    private val _frameworkName = mutableStateOf<String?>(null)
    val frameworkName: State<String?> = _frameworkName

    @JvmStatic
    fun isModuleActiveInternal(): Boolean = false

    @JvmStatic
    fun getFrameworkInternal(): String? = null

    fun checkStatus() {
        val active = isModuleActiveInternal()
        _isModuleActive.value = active
        if (active) {
            val hookedFramework = getFrameworkInternal()
            _frameworkName.value = if (!hookedFramework.isNullOrBlank()) {
                hookedFramework
            } else {
                detectFramework()
            }
        } else {
            _frameworkName.value = null
        }
    }

    fun detectFramework(): String {
        val knownClasses = listOf(
            "LSPatch" to listOf(
                "org.lsposed.lspatch.loader.XposedContext",
                "org.lsposed.lspatch.metaloader.Metaloader",
                "org.lsposed.lspatch.loader.LSPClassLoader"
            ),
            "LSPosed" to listOf(
                "org.lsposed.lspd.service.ILSPManager",
                "org.lsposed.lspd.models.Module",
                "org.lsposed.lsposed.service.ILSPManager"
            ),
            "EdXposed" to listOf(
                "com.elderdrivers.riru.edxp.config.EdXpConfig",
                "org.meowcat.edxposed.manager.BuildConfig"
            ),
            "SandHook" to listOf(
                "com.swift.sandhook.SandHook"
            ),
            "TaiChi" to listOf(
                "me.weishu.exp.TaiChi"
            ),
            "VirtualXposed" to listOf(
                "io.va.exposed.XposedHelper"
            )
        )

        for ((name, classNames) in knownClasses) {
            for (className in classNames) {
                try {
                    Class.forName(className)
                    return name
                } catch (_: Throwable) {}
            }
        }

        try {
            File("/proc/self/maps").useLines { lines ->
                for (line in lines) {
                    val lower = line.lowercase()
                    when {
                        "lspatch" in lower -> return "LSPatch"
                        "lsposed" in lower -> return "LSPosed"
                        "edxp" in lower || "edxposed" in lower -> return "EdXposed"
                        "sandhook" in lower -> return "SandHook"
                        "taichi" in lower -> return "TaiChi"
                    }
                }
            }
        } catch (_: Throwable) {}

        if (System.getProperty("vxp") != null) {
            return "VirtualXposed"
        }

        return "LSPosed"
    }
}

