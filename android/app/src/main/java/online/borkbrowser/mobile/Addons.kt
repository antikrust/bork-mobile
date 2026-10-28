package online.borkbrowser.mobile

import android.util.Log
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import java.io.File

/**
 * The add-ons bork-mobile ships, and the plumbing that lets any add-on show
 * its popup, open its settings and open tabs.
 *
 * Two kinds ship:
 *
 *  - bork's own (YoutubeDaddy, Cyberswine) are BUILT-IN: loaded straight
 *    out of the APK's assets with ensureBuiltIn, unsigned, updated with the
 *    app. Same as the desktop, where they are distribution add-ons.
 *  - uBlock Origin and Dark Reader are the AMO-signed .xpi files the desktop
 *    build ships, installed once from the APK as ordinary add-ons. They are
 *    signed, so a release GeckoView accepts them, and they are removable.
 *
 * Removing a shipped add-on sticks, as it does on the desktop: an id that has
 * been installed once is remembered and never pushed back.
 */
class Addons(private val app: BorkApplication) {

    enum class Kind { BUILT_IN, XPI }

    data class Bundled(val id: String, val kind: Kind, val source: String)

    /** What the browser UI has to provide for add-ons to work. */
    interface Host {
        /** A popup wants showing. Return an OPEN session to display it in. */
        fun openPopup(extension: WebExtension, title: String): GeckoSession?
        /** An add-on opened a tab. Return an UNOPENED session; GeckoView opens it. */
        fun newTabFromAddon(url: String?, active: Boolean): GeckoSession?
        fun openUrlInNewTab(url: String)
        /** Something not shipped wants installing; the user decides. */
        fun confirmInstall(extension: WebExtension, permissions: List<String>): GeckoResult<Boolean>
        fun addonsChanged()
    }

    var host: Host? = null

    private val extensions = LinkedHashMap<String, WebExtension>()
    private val actions = HashMap<String, WebExtension.Action>()
    private val quietInstalls = HashSet<String>()

    val all: List<WebExtension> get() = extensions.values.toList()

    fun get(id: String): WebExtension? = extensions[id]

    fun action(id: String): WebExtension.Action? = actions[id]

    /** Add-ons with a toolbar button (a popup or a click) — what the menu lists. */
    val withActions: List<WebExtension>
        get() = extensions.values
            .filter { actions.containsKey(it.id) && it.metaData.enabled }
            .sortedBy { (it.metaData.name ?: it.id).lowercase() }

    fun start(runtime: GeckoRuntime) {
        val controller = runtime.webExtensionController
        controller.promptDelegate = object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>,
            ): GeckoResult<WebExtension.PermissionPromptResponse> {
                if (extension.id in quietInstalls || extension.isBuiltIn) {
                    return GeckoResult.fromValue(
                        WebExtension.PermissionPromptResponse(true, true, false),
                    )
                }
                val asked = host?.confirmInstall(extension, permissions.toList() + origins.toList())
                    ?: return GeckoResult.fromValue(WebExtension.PermissionPromptResponse(false, false, false))
                return asked.map { ok -> WebExtension.PermissionPromptResponse(ok, false, false) }
            }

            override fun onUpdatePrompt(
                extension: WebExtension,
                newPermissions: Array<String>,
                newOrigins: Array<String>,
                newDataCollectionPermissions: Array<String>,
            ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)

            override fun onOptionalPrompt(
                extension: WebExtension,
                permissions: Array<String>,
                origins: Array<String>,
                dataCollectionPermissions: Array<String>,
            ): GeckoResult<AllowOrDeny> = GeckoResult.fromValue(AllowOrDeny.ALLOW)
        }

        controller.setAddonManagerDelegate(object : WebExtensionController.AddonManagerDelegate {
            override fun onInstalled(extension: WebExtension) = register(extension)
            override fun onEnabled(extension: WebExtension) = register(extension)
            override fun onDisabled(extension: WebExtension) = register(extension)
            override fun onReady(extension: WebExtension) = register(extension)
            override fun onUninstalled(extension: WebExtension) {
                extensions.remove(extension.id)
                actions.remove(extension.id)
                host?.addonsChanged()
            }
        })

        controller.list().accept({ list ->
            list.orEmpty().forEach(::register)
            installBundled(controller, list.orEmpty().map { it.id }.toSet())
        }, { error -> Log.e(TAG, "listing add-ons failed", error) })
    }

    private fun installBundled(controller: WebExtensionController, present: Set<String>) {
        val remembered = app.getSharedPreferences("addons", 0)
        for (bundled in BUNDLED) {
            when (bundled.kind) {
                // Every launch: it is cheap, and it is how an app update
                // carrying a newer copy reaches an existing install.
                Kind.BUILT_IN ->
                    controller.ensureBuiltIn(bundled.source, bundled.id).accept(
                        { ext -> ext?.let(::register) },
                        { e -> Log.e(TAG, "built-in ${bundled.id} failed", e) },
                    )
                Kind.XPI -> {
                    if (bundled.id in present) {
                        remembered.edit().putBoolean(bundled.id, true).apply()
                        continue
                    }
                    if (remembered.getBoolean(bundled.id, false)) continue // removed by the user
                    installXpi(controller, bundled) {
                        remembered.edit().putBoolean(bundled.id, true).apply()
                    }
                }
            }
        }
    }

    private fun installXpi(controller: WebExtensionController, bundled: Bundled, done: () -> Unit) {
        val file = File(app.cacheDir, "xpi/${bundled.source.substringAfterLast('/')}")
        try {
            file.parentFile?.mkdirs()
            app.assets.open(bundled.source).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not unpack ${bundled.source}", e)
            return
        }
        quietInstalls += bundled.id
        controller.install(file.toURI().toString(), WebExtensionController.INSTALLATION_METHOD_FROM_FILE)
            .accept({ ext ->
                file.delete()
                ext ?: return@accept
                register(ext)
                controller.setAllowedInPrivateBrowsing(ext, true).accept { it?.let(::register) }
                done()
            }, { e ->
                file.delete()
                Log.e(TAG, "installing ${bundled.id} failed", e)
            })
    }

    fun register(extension: WebExtension) {
        extensions[extension.id] = extension
        extension.setActionDelegate(actionDelegate)
        extension.setTabDelegate(tabDelegate)
        sessions.forEach { it.get()?.webExtensionController?.setActionDelegate(extension, actionDelegate) }
        host?.addonsChanged()
    }

    private val sessions = ArrayList<java.lang.ref.WeakReference<GeckoSession>>()

    /**
     * Per-tab action state (uBlock's blocked-request badge, a disabled
     * button on some sites) arrives through each session; without a
     * delegate there it is dropped.
     */
    fun attachTo(session: GeckoSession) {
        sessions.removeAll { it.get() == null }
        sessions += java.lang.ref.WeakReference(session)
        extensions.values.forEach { session.webExtensionController.setActionDelegate(it, actionDelegate) }
    }

    /** Remove an add-on. A shipped one stays removed (see the class comment). */
    fun uninstall(extension: WebExtension): GeckoResult<Void> =
        app.runtime.webExtensionController.uninstall(extension)

    fun setEnabled(extension: WebExtension, enabled: Boolean): GeckoResult<WebExtension> {
        val controller = app.runtime.webExtensionController
        val result = if (enabled) {
            controller.enable(extension, WebExtensionController.EnableSource.USER)
        } else {
            controller.disable(extension, WebExtensionController.EnableSource.USER)
        }
        return result.map { ext -> ext?.also(::register) }
    }

    fun install(url: String): GeckoResult<WebExtension> =
        app.runtime.webExtensionController.install(url)

    fun checkForUpdates(done: (Int) -> Unit) {
        val candidates = extensions.values.filter { !it.isBuiltIn }
        if (candidates.isEmpty()) return done(0)
        var pending = candidates.size
        var updated = 0
        for (ext in candidates) {
            app.runtime.webExtensionController.update(ext).accept({ next ->
                if (next != null && next.metaData.version != ext.metaData.version) {
                    updated++
                    register(next)
                }
                if (--pending == 0) done(updated)
            }, {
                if (--pending == 0) done(updated)
            })
        }
    }

    fun click(extension: WebExtension) {
        val action = actions[extension.id]
        action?.click()
    }

    private val actionDelegate = object : WebExtension.ActionDelegate {
        override fun onBrowserAction(
            extension: WebExtension,
            session: GeckoSession?,
            action: WebExtension.Action,
        ) {
            // Per-tab overrides (badge counts) arrive with a session; the menu
            // shows the add-on's default.
            if (session == null) {
                actions[extension.id] = action
                host?.addonsChanged()
            }
        }

        override fun onTogglePopup(
            extension: WebExtension,
            action: WebExtension.Action,
        ): GeckoResult<GeckoSession>? = popup(extension, action)

        override fun onOpenPopup(
            extension: WebExtension,
            action: WebExtension.Action,
        ): GeckoResult<GeckoSession>? = popup(extension, action)

        private fun popup(extension: WebExtension, action: WebExtension.Action): GeckoResult<GeckoSession>? {
            val title = action.title ?: extension.metaData.name ?: extension.id
            val session = host?.openPopup(extension, title) ?: return null
            return GeckoResult.fromValue(session)
        }
    }

    private val tabDelegate = object : WebExtension.TabDelegate {
        override fun onNewTab(
            source: WebExtension,
            createDetails: WebExtension.CreateTabDetails,
        ): GeckoResult<GeckoSession>? {
            val session = host?.newTabFromAddon(createDetails.url, createDetails.active != false)
                ?: return null
            return GeckoResult.fromValue(session)
        }

        override fun onOpenOptionsPage(source: WebExtension) {
            val url = source.metaData.optionsPageUrl ?: return
            host?.openUrlInNewTab(url)
        }
    }

    companion object {
        private const val TAG = "BorkAddons"

        val BUNDLED = listOf(
            Bundled("uBlock0@raymondhill.net", Kind.XPI, "extensions/ublock-origin.xpi"),
            Bundled("youtubedaddy@bork", Kind.BUILT_IN, "resource://android/assets/extensions/youtubedaddy/"),
            Bundled("cyberswine@bork", Kind.BUILT_IN, "resource://android/assets/extensions/cyberswine/"),
            Bundled("addon@darkreader.org", Kind.XPI, "extensions/dark-reader.xpi"),
        )
    }
}
