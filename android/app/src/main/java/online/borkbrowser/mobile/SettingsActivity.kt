package online.borkbrowser.mobile

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.StorageController

class SettingsActivity : Screen() {
    override val screenTitle = "Settings"
    private val settings get() = app.settings
    private var cleared = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun render() {
        body.removeAllViews()
        body.addView(Ui.header(this, "Browsing"))
        body.addView(Ui.row(this, "Search engine", R.drawable.ic_search, detail = settings.searchEngine.name) {
            val engines = Settings.ENGINES
            MaterialAlertDialogBuilder(this)
                .setTitle("Search engine")
                .setSingleChoiceItems(engines.map { it.name }.toTypedArray(), engines.indexOf(settings.searchEngine)) { d, which ->
                    settings.searchEngineId = engines[which].id
                    d.dismiss()
                    render()
                }
                .show()
        })
        body.addView(Ui.switchRow(this, "Address bar at the bottom", "Where a thumb reaches it", settings.toolbarAtBottom) {
            settings.toolbarAtBottom = it
        })
        body.addView(Ui.switchRow(this, "Desktop site for new tabs", null, settings.desktopByDefault) {
            settings.desktopByDefault = it
        })
        body.addView(Ui.switchRow(this, "Reopen tabs on start", "Private tabs never are", settings.restoreTabs) {
            settings.restoreTabs = it
        })
        body.addView(Ui.switchRow(this, "Zoom on every site", "Even ones that try to stop pinch-zoom", settings.forceZoom) {
            settings.forceZoom = it
            app.runtime.settings.forceUserScalableEnabled = it
        })
        body.addView(Ui.switchRow(this, "JavaScript", "Applies to new tabs", settings.javascript) {
            settings.javascript = it
            app.runtime.settings.javaScriptEnabled = it
        })

        body.addView(Ui.header(this, "Privacy"))
        body.addView(note(
            "No telemetry, no crash reports, no accounts — nothing is sent anywhere. Tracking protection " +
                "and Global Privacy Control are always on, and uBlock Origin ships installed.",
        ))
        body.addView(Ui.row(this, "Clear history", R.drawable.ic_history) {
            confirm("Clear history?", "Every visited page is forgotten. Pinned sites stay.") {
                app.store.clearHistory()
                Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
            }
        })
        body.addView(Ui.row(this, "Clear cookies and site data", R.drawable.ic_close, detail = "Signs you out everywhere") {
            confirm("Clear cookies and site data?", "Every site forgets you, and open tabs close.") {
                app.runtime.storageController.clearData(StorageController.ClearFlags.ALL).accept {
                    Toast.makeText(this, "Site data cleared", Toast.LENGTH_SHORT).show()
                }
                cleared = true
                setResult(RESULT_OK, Intent().putExtra(EXTRA_CLEARED, true))
            }
        })

        body.addView(Ui.header(this, "Advanced"))
        body.addView(Ui.switchRow(this, "Remote debugging", "about:debugging over USB, for add-on work", settings.remoteDebugging) {
            settings.remoteDebugging = it
            app.runtime.settings.remoteDebuggingEnabled = it
        })

        body.addView(Ui.header(this, "About"))
        body.addView(Ui.row(this, "bork mobile ${BuildConfig.VERSION_NAME}", detail = "Gecko via GeckoView ${org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION}"))
        body.addView(note(
            "bork is based on Floorp, which is based on Mozilla Firefox. It is not affiliated with, " +
                "endorsed by, or connected to Mozilla, Ablaze or the Floorp project. MPL 2.0.",
        ))
        if (cleared) setResult(RESULT_OK, Intent().putExtra(EXTRA_CLEARED, true))
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Clear") { _, _ -> action() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_CLEARED = "cleared"
    }
}
