package online.borkbrowser.mobile

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoSession
import java.io.File
import java.util.UUID

/**
 * A workspace: a named set of tabs, the way the desktop's Vivaldi-style
 * workspaces are. Every tab belongs to exactly one; the tab tray shows one
 * workspace at a time and switching workspace switches the set.
 */
class Workspace(val id: String, var name: String) {
    /** The tab to come back to when this workspace is picked again. */
    var lastTabId: String? = null
}

class Tab(
    val id: String = UUID.randomUUID().toString(),
    var workspaceId: String,
    val private: Boolean = false,
) {
    /** The page the tab is on. Empty means the start page and nothing else. */
    var url: String = ""
    var title: String = ""

    /** Showing the start page over a session that still has its history. */
    var home: Boolean = true

    var desktop: Boolean = false
    var sessionState: String? = null
    var lastUsed: Long = System.currentTimeMillis()

    /** The tab that opened this one — back from a fresh tab returns there. */
    var parentId: String? = null

    /** Created when the tab is first shown, so a restored session costs nothing until used. */
    var session: GeckoSession? = null

    var canGoBack = false
    var canGoForward = false
    var progress = 100
    var loading = false
    var secure = false
    var thumbnail: Bitmap? = null

    val displayTitle: String
        get() = when {
            home && (url.isEmpty() || session == null) -> "Start"
            title.isNotBlank() -> title
            url.isNotBlank() -> url
            else -> "Start"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("workspace", workspaceId)
        put("url", url)
        put("title", title)
        put("home", home)
        put("desktop", desktop)
        put("lastUsed", lastUsed)
        parentId?.let { put("parent", it) }
        sessionState?.let { put("state", it) }
    }

    companion object {
        fun fromJson(o: JSONObject): Tab = Tab(
            id = o.getString("id"),
            workspaceId = o.getString("workspace"),
        ).apply {
            url = o.optString("url")
            title = o.optString("title")
            home = o.optBoolean("home", url.isEmpty())
            desktop = o.optBoolean("desktop")
            lastUsed = o.optLong("lastUsed", System.currentTimeMillis())
            parentId = o.optString("parent").ifEmpty { null }
            sessionState = o.optString("state").ifEmpty { null }
        }
    }
}

/**
 * Tabs and workspaces on disk. Private tabs are never written down.
 *
 * One small JSON file, rewritten whole: the state is a few kilobytes per tab
 * at most (GeckoView's session state is the bulk of it), and a whole-file
 * write through a temp file cannot leave half a session behind.
 */
class TabStore(context: Context) {
    private val file = File(context.filesDir, "tabs.json")

    data class Snapshot(
        val workspaces: List<Workspace>,
        val tabs: List<Tab>,
        val currentWorkspace: String?,
    )

    fun load(): Snapshot? {
        if (!file.exists()) return null
        return try {
            val o = JSONObject(file.readText())
            val workspaces = o.getJSONArray("workspaces").let { a ->
                (0 until a.length()).map { i ->
                    val w = a.getJSONObject(i)
                    Workspace(w.getString("id"), w.getString("name")).apply {
                        lastTabId = w.optString("lastTab").ifEmpty { null }
                    }
                }
            }
            val tabs = o.getJSONArray("tabs").let { a ->
                (0 until a.length()).map { Tab.fromJson(a.getJSONObject(it)) }
            }
            Snapshot(workspaces, tabs, o.optString("currentWorkspace").ifEmpty { null })
        } catch (e: Exception) {
            Log.e("BorkTabs", "tabs.json unreadable, starting fresh", e)
            null
        }
    }

    fun save(workspaces: List<Workspace>, tabs: List<Tab>, currentWorkspace: String?) {
        val o = JSONObject().apply {
            put("version", 1)
            put("currentWorkspace", currentWorkspace)
            put("workspaces", JSONArray().apply {
                workspaces.forEach { w ->
                    put(JSONObject().apply {
                        put("id", w.id)
                        put("name", w.name)
                        w.lastTabId?.let { put("lastTab", it) }
                    })
                }
            })
            put("tabs", JSONArray().apply { tabs.filter { !it.private }.forEach { put(it.toJson()) } })
        }
        try {
            val tmp = File(file.parentFile, "tabs.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.e("BorkTabs", "could not save tabs", e)
        }
    }

    fun clear() {
        file.delete()
    }
}
