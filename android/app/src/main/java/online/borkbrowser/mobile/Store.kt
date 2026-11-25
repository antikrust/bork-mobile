package online.borkbrowser.mobile

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import mozilla.appservices.places.BookmarkRoot
import mozilla.appservices.places.PlacesApi
import mozilla.appservices.places.uniffi.BookmarkItem
import mozilla.appservices.places.uniffi.VisitObservation
import mozilla.appservices.places.uniffi.VisitType
import mozilla.components.concept.sync.SyncableStore

data class Site(val url: String, val title: String, val time: Long = 0)

/**
 * History and bookmarks, in Mozilla's Places: the same database, written by
 * the same library, that Firefox for Android keeps them in, so a bork account
 * syncs them as they are (Account.kt).
 *
 * GeckoView keeps no history of its own — HistoryDelegate hands every visit to
 * the app and asks the app what has been visited — so this is the whole of it.
 * The start page's tiles are the Mobile Bookmarks folder, in its own order;
 * bookmarks from other devices live in the other folders and only light the
 * star.
 *
 * Calls are synchronous, as they were on the SQLite store this replaces.
 * Reads have their own connection, so a sync in progress never holds them up;
 * visits and titles are written off the main thread, where a sync holding the
 * writer can only delay them. Bookmark edits stay synchronous because the
 * screen redraws from them straight away.
 */
class Store(private val context: Context) {

    private val places = PlacesApi(File(context.filesDir, "places.sqlite").canonicalPath)
    private val reader = places.openReader()
    private val writer get() = places.getWriter()
    private val background = Executors.newSingleThreadExecutor { Thread(it, "bork-places") }

    /** What the sync manager needs for both the history and the bookmarks engine. */
    val syncable = object : SyncableStore {
        override fun registerWithSyncManager() = places.registerWithSyncManager()
    }

    init {
        importOldStore()
    }

    /* ---------------------------------------------------------------- history */

    fun recordVisit(url: String) {
        if (!isRecordable(url)) return
        later { writer.noteObservation(VisitObservation(url = url, visitType = VisitType.LINK)) }
    }

    fun setTitle(url: String, title: String) {
        if (!isRecordable(url) || title.isBlank()) return
        later { writer.noteObservation(VisitObservation(url = url, title = title, visitType = null)) }
    }

    fun visited(urls: Array<String>): BooleanArray {
        if (urls.isEmpty()) return BooleanArray(0)
        return safely(BooleanArray(urls.size)) { reader.getVisited(urls.toList()).toBooleanArray() }
    }

    /** Newest first, one row per page (Places keeps every visit). */
    fun history(limit: Int = 500, query: String = ""): List<Site> = safely(emptyList()) {
        val out = LinkedHashMap<String, Site>()
        var offset = 0L
        // Searching reads further back: a match can be anywhere.
        val window = if (query.isBlank()) limit * 4L else 5000L
        while (out.size < limit && offset < window) {
            val page = reader.getVisitPage(offset, PAGE, HIDDEN_VISITS)
            if (page.isEmpty()) break
            offset += page.size
            for (visit in page) {
                if (visit.url in out) continue
                val title = visit.title.orEmpty()
                if (query.isNotBlank() && !visit.url.contains(query, true) && !title.contains(query, true)) continue
                out[visit.url] = Site(visit.url, title, visit.timestamp)
                if (out.size >= limit) break
            }
        }
        out.values.toList()
    }

    fun deleteHistory(url: String) {
        safely(Unit) { writer.deleteVisitsFor(url) }
    }

    fun clearHistory() {
        safely(Unit) { writer.deleteEverything() }
    }

    /** What the address bar offers while typing: bookmarks first, then the most visited. */
    fun suggest(query: String, limit: Int = 8): List<Site> {
        if (query.isBlank()) return emptyList()
        val out = LinkedHashMap<String, Site>()
        safely(Unit) {
            for (item in reader.searchBookmarks(query, limit)) {
                if (item is BookmarkItem.Bookmark) out.putIfAbsent(item.b.url, Site(item.b.url, item.b.title.orEmpty()))
            }
            for (hit in reader.queryAutocomplete(query, limit)) {
                if (out.size >= limit) break
                out.putIfAbsent(hit.url, Site(hit.url, hit.title))
            }
        }
        return out.values.take(limit)
    }

    /* -------------------------------------------------------------- bookmarks */

    /** The start page's tiles: Mobile Bookmarks, in order. */
    fun bookmarks(): List<Site> = tiles().map { Site(it.url, it.title.orEmpty(), it.dateAdded) }

    fun isBookmarked(url: String): Boolean = safely(false) { reader.getBookmarksWithURL(url).isNotEmpty() }

    fun addBookmark(url: String, title: String) {
        if (tiles().any { it.url == url }) return
        safely(Unit) { writer.createBookmarkItem(MOBILE, url, title, null) }
    }

    fun renameBookmark(url: String, title: String) {
        safely(Unit) {
            for (b in withUrl(url)) writer.updateBookmark(b.guid, null, null, title, null)
        }
    }

    /** Unstarring a page takes it out of every folder, whichever device put it there. */
    fun removeBookmark(url: String) {
        safely(Unit) {
            for (b in withUrl(url)) writer.deleteBookmarkNode(b.guid)
        }
    }

    fun moveBookmark(url: String, delta: Int) {
        val list = tiles()
        val i = list.indexOfFirst { it.url == url }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        safely(Unit) { writer.updateBookmark(list[i].guid, MOBILE, j.toUInt(), null, null) }
    }

    private fun tiles() = safely(emptyList()) {
        val folder = reader.getBookmarksTree(MOBILE, false) as? BookmarkItem.Folder
        folder?.f?.childNodes.orEmpty().filterIsInstance<BookmarkItem.Bookmark>().map { it.b }
    }

    private fun withUrl(url: String) =
        reader.getBookmarksWithURL(url).filterIsInstance<BookmarkItem.Bookmark>().map { it.b }

    /* ------------------------------------------------------------ plumbing */

    private fun later(job: () -> Unit) {
        background.execute { safely(Unit, job) }
    }

    /**
     * A storage failure costs one history row or one tile, never the browser:
     * Places throws on a corrupt URL or an interrupted read.
     */
    private inline fun <T> safely(fallback: T, job: () -> T): T =
        try {
            job()
        } catch (e: Exception) {
            Log.e("bork", "[Store] places", e)
            fallback
        }

    /**
     * Before Places, history and pinned sites lived in bork.db. Copied over
     * once, in order, then deleted. Each page keeps its last visit only. A
     * copy that fails keeps bork.db for the next start to try again.
     */
    private fun importOldStore() {
        val old = context.getDatabasePath("bork.db")
        if (!old.exists()) return
        val copied = safely(false) {
            SQLiteDatabase.openDatabase(old.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT url, title, last_visit FROM history ORDER BY last_visit", null).use { c ->
                    while (c.moveToNext()) {
                        writer.noteObservation(
                            VisitObservation(
                                url = c.getString(0),
                                title = c.getString(1).ifBlank { null },
                                visitType = VisitType.LINK,
                                at = c.getLong(2),
                            ),
                        )
                    }
                }
                val have = tiles().map { it.url }.toSet()
                db.rawQuery("SELECT url, title FROM bookmarks ORDER BY position, created", null).use { c ->
                    while (c.moveToNext()) {
                        if (c.getString(0) !in have) writer.createBookmarkItem(MOBILE, c.getString(0), c.getString(1), null)
                    }
                }
            }
            true
        }
        if (copied) context.deleteDatabase("bork.db")
    }

    companion object {
        private val MOBILE = BookmarkRoot.Mobile.id
        private const val PAGE = 200L
        /** Hops a page made on the way to another, which history does not list. */
        private val HIDDEN_VISITS = listOf(
            VisitType.REDIRECT_PERMANENT,
            VisitType.REDIRECT_TEMPORARY,
            VisitType.EMBED,
            VisitType.FRAMED_LINK,
            VisitType.DOWNLOAD,
        )

        fun isRecordable(url: String): Boolean =
            url.startsWith("http://") || url.startsWith("https://")
    }
}
