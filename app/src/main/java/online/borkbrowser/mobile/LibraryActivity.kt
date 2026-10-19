package online.borkbrowser.mobile

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.EditText
import android.widget.LinearLayout
import androidx.core.widget.addTextChangedListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout

/** Pinned sites (bookmarks) and history. Picking one hands its URL back to the browser. */
class LibraryActivity : Screen() {
    override val screenTitle = "Bookmarks & history"
    private var showingHistory = false
    private var query = ""
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tabs = TabLayout(this).apply {
            addTab(newTab().setText("Pinned"))
            addTab(newTab().setText("History"))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    showingHistory = tab.position == 1
                    render()
                }
                override fun onTabUnselected(tab: TabLayout.Tab) = Unit
                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        body.addView(tabs)
        body.addView(EditText(this).apply {
            hint = "Search"
            setTextColor(Ui.fg(context))
            setHintTextColor(Ui.dim(context))
            setBackgroundResource(R.drawable.bg_field)
            setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), 0)
            isSingleLine = true
            addTextChangedListener { query = it?.toString().orEmpty(); render() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 44)).apply {
                setMargins(Ui.dp(context, 16), Ui.dp(context, 10), Ui.dp(context, 16), Ui.dp(context, 6))
            }
        })
        list = Ui.column(this)
        body.addView(list)
        render()
    }

    private fun open(url: String) {
        setResult(RESULT_OK, Intent().putExtra(EXTRA_URL, url))
        finish()
    }

    private fun render() {
        list.removeAllViews()
        val store = app.store
        if (showingHistory) {
            val sites = store.history(limit = 400, query = query)
            if (sites.isEmpty()) list.addView(note("Nothing here yet."))
            for (site in sites) {
                val ago = DateUtils.getRelativeTimeSpanString(site.time).toString()
                list.addView(Ui.row(this, site.title.ifBlank { UrlInput.display(site.url) }, detail = "${UrlInput.display(site.url)} · $ago") {
                    open(site.url)
                }.apply {
                    setOnLongClickListener {
                        MaterialAlertDialogBuilder(context)
                            .setTitle(site.title.ifBlank { site.url })
                            .setItems(arrayOf("Pin to start page", "Delete from history")) { _, which ->
                                if (which == 0) store.addBookmark(site.url, site.title) else store.deleteHistory(site.url)
                                render()
                            }
                            .show()
                        true
                    }
                })
            }
            if (sites.isNotEmpty() && query.isEmpty()) {
                list.addView(Ui.divider(this))
                list.addView(Ui.row(this, "Clear all history", R.drawable.ic_close) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Clear all history?")
                        .setPositiveButton("Clear") { _, _ -> store.clearHistory(); render() }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                })
            }
        } else {
            val sites = store.bookmarks().filter {
                query.isBlank() || it.url.contains(query, true) || it.title.contains(query, true)
            }
            if (sites.isEmpty()) list.addView(note("Pin a page from the menu's star and it lands here and on the start page."))
            for (site in sites) {
                list.addView(Ui.row(this, site.title.ifBlank { UrlInput.host(site.url) }, R.drawable.ic_star_filled, detail = UrlInput.display(site.url)) {
                    open(site.url)
                }.apply {
                    setOnLongClickListener {
                        MaterialAlertDialogBuilder(context)
                            .setTitle(site.title.ifBlank { site.url })
                            .setItems(arrayOf("Unpin")) { _, _ -> store.removeBookmark(site.url); render() }
                            .show()
                        true
                    }
                })
            }
        }
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
