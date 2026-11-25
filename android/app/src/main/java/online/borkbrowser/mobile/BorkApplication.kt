package online.borkbrowser.mobile

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import mozilla.appservices.RustComponentsInitializer
import mozilla.appservices.viaduct.RustHttpConfig
import mozilla.components.lib.fetch.httpurlconnection.HttpURLConnectionClient
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * One Gecko runtime for the whole process — GeckoView allows no more.
 *
 * What the desktop build takes out, this one never puts in: no crash handler
 * is registered (so nothing is ever reported), Glean is never initialised (so
 * nothing is ever uploaded), and the telemetry pref is switched off on top of
 * that for anything inside Gecko that reads it. The account libraries record
 * into Glean too, which stays uninitialised for them just the same.
 */
class BorkApplication : Application() {

    lateinit var settings: Settings
        private set
    lateinit var store: Store
        private set
    lateinit var addons: Addons
        private set
    lateinit var account: Account
        private set
    lateinit var addonSync: AddonSync
        private set

    val runtime: GeckoRuntime by lazy { createRuntime() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        addons = Addons(this)
        // GeckoView's content processes are this app too and start here as
        // well. History, bookmarks and the account belong to the main one:
        // a second Places or account manager per tab process would fight it.
        if (!isMainProcess()) return
        // The Rust libraries behind Places and the account come first, then
        // the one HTTP client they all make requests through.
        RustComponentsInitializer.init()
        RustHttpConfig.setClient(lazy { HttpURLConnectionClient() })
        store = Store(this)
        addonSync = AddonSync(this)
        account = Account(this)
        account.start()
    }

    private fun isMainProcess(): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) {
            getProcessName()
        } else {
            val pid = Process.myPid()
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses
                ?.firstOrNull { it.pid == pid }?.processName
        }
        return name == null || name == packageName
    }

    private fun createRuntime(): GeckoRuntime {
        val blocking = ContentBlocking.Settings.Builder()
            .antiTracking(
                ContentBlocking.AntiTracking.DEFAULT or ContentBlocking.AntiTracking.STP,
            )
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.DEFAULT)
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .queryParameterStrippingEnabled(true)
            .queryParameterStrippingPrivateBrowsingEnabled(true)
            .build()

        val builder = GeckoRuntimeSettings.Builder()
            .contentBlocking(blocking)
            .consoleOutput(BuildConfig.DEBUG)
            .remoteDebuggingEnabled(settings.remoteDebugging)
            .aboutConfigEnabled(true)
            .javaScriptEnabled(settings.javascript)
            .globalPrivacyControlEnabled(true)
            .extensionsWebAPIEnabled(true)
            .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM)
            .forceUserScalableEnabled(settings.forceZoom)
            .loginAutofillEnabled(false)
            .translationsOfferPopup(false)

        val runtime = GeckoRuntime.create(this, builder.build())
        runtime.notifyTelemetryPrefChanged(false)
        return runtime
    }

    companion object {
        lateinit var instance: BorkApplication
            private set
    }
}
