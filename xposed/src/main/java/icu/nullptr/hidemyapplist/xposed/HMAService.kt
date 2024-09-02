package icu.nullptr.hidemyapplist.xposed

import android.content.pm.ApplicationInfo
import android.content.pm.IPackageManager
import android.os.Build
import icu.nullptr.hidemyapplist.common.*
import icu.nullptr.hidemyapplist.xposed.hook.*
import java.io.File

class HMAService(val pms: IPackageManager) : IHMAService.Stub() {

    companion object {
        private const val TAG = "HMAL-S"
        var instance: HMAService? = null
    }

    @Volatile

    private lateinit var dataDir: String
    private lateinit var configFile: File

    private val configLock = Any()
    private val systemApps = mutableSetOf<String>()
    private val frameworkHooks = mutableSetOf<IFrameworkHook>()

    var config = JsonConfig().apply { forceMountData = false }
        private set

    init {
        searchDataDir()
        instance = this
        loadConfig()
        installHooks()
    }

    private fun searchDataDir() {
        File("/data/misc/ifwnc").deleteRecursively()
        File("/data/system").list()?.forEach {
            if (it.startsWith("ifwnc")) {
                if (this::dataDir.isInitialized) File("/data/system/$it").deleteRecursively()
                else dataDir = "/data/system/$it"
            }
        }
        if (!this::dataDir.isInitialized) {
            dataDir = "/data/system/ifwnc_" + Utils.generateRandomString(6)
        }

        File("$dataDir/log").mkdirs()
        configFile = File("$dataDir/config.json")
    }

    private fun loadConfig() {
        if (!configFile.exists()) {
            return
        }
        val loading = runCatching {
            val json = configFile.readText()
            JsonConfig.parse(json)
        }.getOrElse {
            return
        }
        if (loading.configVersion != BuildConfig.CONFIG_VERSION) {
            return
        }
        config = loading
    }

    private fun installHooks() {
        Utils.getInstalledApplicationsCompat(pms, 0, 0).mapNotNullTo(systemApps) {
            if (it.flags and ApplicationInfo.FLAG_SYSTEM != 0) it.packageName else null
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            frameworkHooks.add(PmsHookTarget33(this))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            frameworkHooks.add(PmsHookTarget30(this))
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            frameworkHooks.add(PmsHookTarget28(this))
        } else {
            frameworkHooks.add(PmsHookLegacy(this))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            frameworkHooks.add(ZygoteArgsHook(this))
        }

        frameworkHooks.forEach(IFrameworkHook::load)
    }

    fun isHookEnabled(packageName: String) = config.scope.containsKey(packageName)

    fun shouldHide(caller: String?, query: String?): Boolean {
        if (caller == null || query == null) return false
        if (caller in Constants.packagesShouldNotHide || query in Constants.packagesShouldNotHide) return false
        if ((caller == Constants.GMS_PACKAGE_NAME || caller == Constants.GSF_PACKAGE_NAME) && query == Constants.APP_PACKAGE_NAME) return false // If apply hide on gms, HMAL app will crash 😓
        if (caller in query) return false
        val appConfig = config.scope[caller] ?: return false
        if (appConfig.useWhitelist && appConfig.excludeSystemApps && query in systemApps) return false

        if (query in appConfig.extraAppList) return !appConfig.useWhitelist
        for (tplName in appConfig.applyTemplates) {
            val tpl = config.templates[tplName]!!
            if (query in tpl.appList) return !appConfig.useWhitelist
        }

        return appConfig.useWhitelist
    }

    override fun stopService(cleanEnv: Boolean) {
        synchronized(configLock) {
            frameworkHooks.forEach(IFrameworkHook::unload)
            frameworkHooks.clear()
            if (cleanEnv) {
                File(dataDir).deleteRecursively()
                return
            }
        }
        instance = null
    }

    override fun syncConfig(json: String) {
        synchronized(configLock) {
            configFile.writeText(json)
            val newConfig = JsonConfig.parse(json)
            if (newConfig.configVersion != BuildConfig.CONFIG_VERSION) {
                return
            }
            config = newConfig
            frameworkHooks.forEach(IFrameworkHook::onConfigChanged)
        }
    }

    override fun getServiceVersion() = BuildConfig.SERVICE_VERSION
}
