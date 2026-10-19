package online.borkbrowser.mobile

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.WebExtension

/**
 * The installed add-ons: switch them off, open their settings, remove them.
 *
 * Shipped add-ons can be removed like any other, and stay removed — see
 * Addons. bork's own two are built in and come back only by reinstalling the
 * app, which is the same promise the desktop makes.
 */
class AddonsActivity : Screen() {
    override val screenTitle = "Add-ons"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        body.removeAllViews()
        val all = app.addons.all.sortedWith(compareBy({ !it.isBuiltIn }, { it.metaData.name ?: it.id }))
        body.addView(note(
            "Firefox add-ons, the full Manifest V2 kind — uBlock Origin here is the real one, not the " +
                "Lite version Chrome's rules forced on everyone else.",
        ))
        if (all.isEmpty()) body.addView(note("Add-ons are still loading…"))
        for (ext in all) {
            val name = ext.metaData.name ?: ext.id
            val state = buildList {
                add("v${ext.metaData.version}")
                if (ext.isBuiltIn) add("built into bork")
                if (!ext.metaData.enabled) add("off")
            }.joinToString(" · ")
            val row = Ui.row(this, name, R.drawable.ic_extension, detail = state) { manage(ext) }
            body.addView(row)
            ext.metaData.icon?.getBitmap(Ui.dp(this, 22))?.accept { bmp ->
                if (bmp != null) (row.getChildAt(0) as? ImageView)?.apply { clearColorFilter(); setImageBitmap(bmp) }
            }
        }
        body.addView(Ui.divider(this))
        body.addView(Ui.row(this, "Find more add-ons", R.drawable.ic_search, detail = "addons.mozilla.org, opened in bork") {
            startActivity(
                Intent(this, BrowserActivity::class.java)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse("https://addons.mozilla.org/android/")),
            )
            finish()
        })
        body.addView(Ui.row(this, "Check for updates", R.drawable.ic_reload) {
            Toast.makeText(this, "Checking…", Toast.LENGTH_SHORT).show()
            app.addons.checkForUpdates { n ->
                Toast.makeText(this, if (n == 0) "Everything is up to date" else "$n updated", Toast.LENGTH_SHORT).show()
                render()
            }
        })
    }

    private fun manage(ext: WebExtension) {
        val name = ext.metaData.name ?: ext.id
        val actions = mutableListOf<Pair<String, () -> Unit>>()
        if (ext.metaData.optionsPageUrl != null) {
            actions += "Settings" to {
                startActivity(
                    Intent(this, BrowserActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse(ext.metaData.optionsPageUrl)),
                )
                finish()
            }
        }
        actions += (if (ext.metaData.enabled) "Turn off" else "Turn on") to {
            app.addons.setEnabled(ext, !ext.metaData.enabled).accept({ render() }, { render() })
        }
        if (!ext.isBuiltIn) {
            actions += "Remove" to {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Remove $name?")
                    .setMessage("Its settings go with it.")
                    .setPositiveButton("Remove") { _, _ ->
                        app.addons.uninstall(ext).accept({ render() }, { render() })
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(name)
            .setMessage(ext.metaData.description)
            .setItems(actions.map { it.first }.toTypedArray()) { _, which -> actions[which].second() }
            .show()
    }
}
