package online.borkbrowser.mobile

import android.app.Application
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * One Gecko runtime for the whole process — GeckoView allows no more.
 *
 * What the desktop build takes out, this one never puts in: no crash handler
 * is registered (so nothing is ever reported), Glean is never initialised (so
 * nothing is ever uploaded), and the telemetry pref is switched off on top of
 * that for anything inside Gecko that reads it.
 */
class BorkApplication : Application() {

    lateinit var settings: Settings
        private set
    lateinit var store: Store
        private set
    lateinit var addons: Addons
        private set

    val runtime: GeckoRuntime by lazy { createRuntime() }

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        store = Store(this)
        addons = Addons(this)
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
