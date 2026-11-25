package online.borkbrowser.mobile

import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mozilla.appservices.fxaclient.FxaConfig
import mozilla.appservices.fxaclient.FxaServer
import mozilla.components.concept.sync.AccountObserver
import mozilla.components.concept.sync.AuthType
import mozilla.components.concept.sync.DeviceConfig
import mozilla.components.concept.sync.DeviceType
import mozilla.components.concept.sync.FxAEntryPoint
import mozilla.components.concept.sync.OAuthAccount
import mozilla.components.concept.sync.PeriodicSyncConfig
import mozilla.components.concept.sync.Profile
import mozilla.components.concept.sync.SyncConfig
import mozilla.components.concept.sync.SyncEngine
import mozilla.components.service.fxa.FxaAuthData
import mozilla.components.service.fxa.manager.FxaAccountManager
import mozilla.components.service.fxa.sync.GlobalSyncableStoreProvider
import mozilla.components.service.fxa.sync.SyncReason
import mozilla.components.service.fxa.sync.SyncStatusObserver

/**
 * The bork account: Firefox's own account and sync machinery (Mozilla's
 * android-components and application-services), pointed at bork's server on
 * borkbrowser.online instead of Mozilla's — the same server the desktop
 * build signs in to (bork-browser tools/accounts-server).
 *
 * Off until switched on in Settings. Signing in opens the server's own pages
 * in a tab; when they finish, the server sends that tab to [REDIRECT], which
 * BrowserActivity catches and hands to [finish] instead of loading.
 *
 * What syncs: history and bookmarks, straight out of Store's Places database.
 * After each sync, AddonSync offers the desktop's add-ons.
 */
class Account(private val app: BorkApplication) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Built on first use, and only once the switch is on. */
    private var manager: FxaAccountManager? = null

    /** Settings redraws on these. */
    var onChange: (() -> Unit)? = null

    var syncing = false
        private set
    var lastError: String? = null
        private set

    val enabled get() = app.settings.accountEnabled
    val signedIn get() = manager?.authenticatedAccount() != null
    val needsReauth get() = manager?.accountNeedsReauth() == true
    val email: String? get() = manager?.accountProfile()?.email

    init {
        GlobalSyncableStoreProvider.configureStore(SyncEngine.History to lazy { app.store.syncable })
        GlobalSyncableStoreProvider.configureStore(SyncEngine.Bookmarks to lazy { app.store.syncable })
    }

    /** At app start: picks up a signed-in account and its sync schedule. */
    fun start() {
        if (enabled) launch { require().start() }
    }

    fun setEnabled(on: Boolean) {
        app.settings.accountEnabled = on
        if (on) {
            start()
        } else {
            val m = manager ?: return changed()
            manager = null
            launch {
                m.logout()
                m.close()
            }
        }
        changed()
    }

    /** The URL of the sign-in pages, for a tab to open; null if the server is unreachable. */
    fun beginSignIn(done: (String?) -> Unit) {
        launch { done(require().beginAuthentication(entrypoint = ENTRY, service = "")) }
    }

    fun isRedirect(url: String) = url.startsWith(REDIRECT)

    /** The sign-in pages are done: [url] carries the code to trade for the account's keys. */
    fun finish(url: String) {
        val uri = Uri.parse(url)
        val code = uri.getQueryParameter("code") ?: return
        val state = uri.getQueryParameter("state") ?: return
        val authType = when (val action = uri.getQueryParameter("action")) {
            "signin" -> AuthType.Signin
            "signup" -> AuthType.Signup
            "pairing" -> AuthType.Pairing
            else -> AuthType.OtherExternal(action)
        }
        launch { require().finishAuthentication(FxaAuthData(authType, code, state)) }
    }

    fun syncNow() {
        val m = manager ?: return
        launch { m.syncNow(SyncReason.User) }
    }

    fun signOut() {
        val m = manager ?: return
        launch { m.logout() }
    }

    private fun require(): FxaAccountManager = manager ?: FxaAccountManager(
        app,
        FxaConfig(FxaServer.Custom(SERVER), CLIENT_ID, REDIRECT),
        DeviceConfig(name = "bork on ${Build.MODEL}", type = DeviceType.MOBILE, capabilities = emptySet()),
        SyncConfig(
            supportedEngines = setOf(SyncEngine.History, SyncEngine.Bookmarks),
            periodicSyncConfig = PeriodicSyncConfig(periodMinutes = 60, initialDelayMinutes = 1),
        ),
    ).also { m ->
        m.register(observer)
        m.registerForSyncEvents(syncObserver, ProcessLifecycleOwner.get(), autoPause = false)
        manager = m
    }

    private val observer = object : AccountObserver {
        override fun onReady(authenticatedAccount: OAuthAccount?) = changed()
        override fun onAuthenticated(account: OAuthAccount, authType: AuthType) = changed()
        override fun onProfileUpdated(profile: Profile) = changed()
        override fun onLoggedOut() = changed()
        override fun onAuthenticationProblems() = changed()
    }

    private val syncObserver = object : SyncStatusObserver {
        override fun onStarted() {
            syncing = true
            changed()
        }

        override fun onIdle() {
            syncing = false
            lastError = null
            changed()
            // The desktop's add-ons ride along with every finished sync.
            manager?.authenticatedAccount()?.let { account ->
                scope.launch { app.addonSync.run(account) }
            }
        }

        override fun onError(error: Exception?) {
            syncing = false
            lastError = error?.message ?: "sync failed"
            changed()
        }
    }

    /** Observers are called on the manager's own thread; the screen is redrawn on the main one. */
    private fun changed() {
        scope.launch { onChange?.invoke() }
    }

    private fun launch(job: suspend () -> Unit) {
        scope.launch {
            try {
                job()
            } catch (e: Exception) {
                Log.e("bork", "[Account]", e)
                lastError = e.message
                changed()
            }
        }
    }

    companion object {
        const val SERVER = "https://accounts.borkbrowser.online"

        /**
         * The server's Android client (Fenix's id, which the self-hosted stack
         * registers for exactly this) and the page it sends a finished sign-in
         * to. That page is never loaded: the app takes the code out of its URL.
         */
        const val CLIENT_ID = "a2270f727f45f648"
        const val REDIRECT = "$SERVER/oauth/success/$CLIENT_ID"

        private val ENTRY = object : FxAEntryPoint {
            override val entryName = "bork-settings"
        }
    }
}
