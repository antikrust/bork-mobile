package online.borkbrowser.mobile

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/** A search engine: what it is called and how a query becomes a URL. */
data class SearchEngine(val id: String, val name: String, val template: String) {
    fun urlFor(query: String): String = template.replace("%s", Uri.encode(query))
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("bork", Context.MODE_PRIVATE)

    var searchEngineId: String
        get() = prefs.getString("search", ENGINES.first().id) ?: ENGINES.first().id
        set(value) = prefs.edit { putString("search", value) }

    val searchEngine: SearchEngine
        get() = ENGINES.firstOrNull { it.id == searchEngineId } ?: ENGINES.first()

    var javascript: Boolean
        get() = prefs.getBoolean("javascript", true)
        set(value) = prefs.edit { putBoolean("javascript", value) }

    var remoteDebugging: Boolean
        get() = prefs.getBoolean("remoteDebugging", false)
        set(value) = prefs.edit { putBoolean("remoteDebugging", value) }

    var forceZoom: Boolean
        get() = prefs.getBoolean("forceZoom", true)
        set(value) = prefs.edit { putBoolean("forceZoom", value) }

    var desktopByDefault: Boolean
        get() = prefs.getBoolean("desktopByDefault", false)
        set(value) = prefs.edit { putBoolean("desktopByDefault", value) }

    /** Keep the address bar at the bottom, where a thumb reaches it. */
    var toolbarAtBottom: Boolean
        get() = prefs.getBoolean("toolbarAtBottom", false)
        set(value) = prefs.edit { putBoolean("toolbarAtBottom", value) }

    var restoreTabs: Boolean
        get() = prefs.getBoolean("restoreTabs", true)
        set(value) = prefs.edit { putBoolean("restoreTabs", value) }

    var zen: Boolean
        get() = prefs.getBoolean("zen", false)
        set(value) = prefs.edit { putBoolean("zen", value) }

    /** Bumped when the bundled add-on set changes, so it is reinstalled once. */
    var addonsInstalledVersion: Int
        get() = prefs.getInt("addonsInstalledVersion", 0)
        set(value) = prefs.edit { putInt("addonsInstalledVersion", value) }

    var seeded: Boolean
        get() = prefs.getBoolean("seeded", false)
        set(value) = prefs.edit { putBoolean("seeded", value) }

    companion object {
        val ENGINES = listOf(
            SearchEngine("ddg", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
            SearchEngine("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s"),
            SearchEngine("brave", "Brave Search", "https://search.brave.com/search?q=%s"),
            SearchEngine("google", "Google", "https://www.google.com/search?q=%s"),
            SearchEngine("bing", "Bing", "https://www.bing.com/search?q=%s"),
            SearchEngine("kagi", "Kagi", "https://kagi.com/search?q=%s"),
        )
    }
}
