package online.borkbrowser.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Site(val url: String, val title: String, val time: Long = 0)

/**
 * History and bookmarks, kept on the phone and nowhere else.
 *
 * GeckoView keeps no history of its own — HistoryDelegate hands every visit to
 * the app and asks the app what has been visited — so this is the whole of it.
 * Bookmarks double as the start page's tiles, in their own order.
 */
class Store(context: Context) : SQLiteOpenHelper(context, "bork.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history (url TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', " +
                "visits INTEGER NOT NULL DEFAULT 0, last_visit INTEGER NOT NULL)",
        )
        db.execSQL("CREATE INDEX history_last ON history(last_visit)")
        db.execSQL(
            "CREATE TABLE bookmarks (url TEXT PRIMARY KEY, title TEXT NOT NULL DEFAULT '', " +
                "position INTEGER NOT NULL, created INTEGER NOT NULL)",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    /* ---------------------------------------------------------------- history */

    fun recordVisit(url: String) {
        if (!isRecordable(url)) return
        val db = writableDatabase
        val now = System.currentTimeMillis()
        val updated = db.compileStatement(
            "UPDATE history SET visits = visits + 1, last_visit = ? WHERE url = ?",
        ).apply {
            bindLong(1, now)
            bindString(2, url)
        }.executeUpdateDelete()
        if (updated == 0) {
            db.insert(
                "history",
                null,
                ContentValues().apply {
                    put("url", url)
                    put("visits", 1)
                    put("last_visit", now)
                },
            )
        }
    }

    fun setTitle(url: String, title: String) {
        if (!isRecordable(url) || title.isBlank()) return
        writableDatabase.update(
            "history",
            ContentValues().apply { put("title", title) },
            "url = ?",
            arrayOf(url),
        )
    }

    fun visited(urls: Array<String>): BooleanArray {
        if (urls.isEmpty()) return BooleanArray(0)
        val found = HashSet<String>()
        urls.toList().chunked(500).forEach { chunk ->
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.rawQuery(
                "SELECT url FROM history WHERE url IN ($marks)",
                chunk.toTypedArray(),
            ).use { c -> while (c.moveToNext()) found.add(c.getString(0)) }
        }
        return BooleanArray(urls.size) { urls[it] in found }
    }

    fun history(limit: Int = 500, query: String = ""): List<Site> {
        val args = if (query.isBlank()) emptyArray() else arrayOf("%$query%", "%$query%")
        val where = if (query.isBlank()) "" else "WHERE url LIKE ? OR title LIKE ?"
        return readableDatabase.rawQuery(
            "SELECT url, title, last_visit FROM history $where ORDER BY last_visit DESC LIMIT $limit",
            args,
        ).use { c ->
            buildList { while (c.moveToNext()) add(Site(c.getString(0), c.getString(1), c.getLong(2))) }
        }
    }

    fun deleteHistory(url: String) {
        writableDatabase.delete("history", "url = ?", arrayOf(url))
    }

    fun clearHistory() {
        writableDatabase.delete("history", null, null)
    }

    /** What the address bar offers while typing: bookmarks first, then the most visited. */
    fun suggest(query: String, limit: Int = 8): List<Site> {
        if (query.isBlank()) return emptyList()
        val like = "%$query%"
        val out = LinkedHashMap<String, Site>()
        readableDatabase.rawQuery(
            "SELECT url, title FROM bookmarks WHERE url LIKE ? OR title LIKE ? ORDER BY position LIMIT ?",
            arrayOf(like, like, limit.toString()),
        ).use { c -> while (c.moveToNext()) out[c.getString(0)] = Site(c.getString(0), c.getString(1)) }
        readableDatabase.rawQuery(
            "SELECT url, title FROM history WHERE url LIKE ? OR title LIKE ? " +
                "ORDER BY visits DESC, last_visit DESC LIMIT ?",
            arrayOf(like, like, limit.toString()),
        ).use { c ->
            while (c.moveToNext() && out.size < limit) {
                out.putIfAbsent(c.getString(0), Site(c.getString(0), c.getString(1)))
            }
        }
        return out.values.toList()
    }

    /* -------------------------------------------------------------- bookmarks */

    fun bookmarks(): List<Site> =
        readableDatabase.rawQuery(
            "SELECT url, title, created FROM bookmarks ORDER BY position, created",
            null,
        ).use { c ->
            buildList { while (c.moveToNext()) add(Site(c.getString(0), c.getString(1), c.getLong(2))) }
        }

    fun isBookmarked(url: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM bookmarks WHERE url = ?", arrayOf(url))
            .use { it.moveToFirst() }

    fun addBookmark(url: String, title: String) {
        val next = readableDatabase.rawQuery("SELECT COALESCE(MAX(position), -1) + 1 FROM bookmarks", null)
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }
        writableDatabase.insertWithOnConflict(
            "bookmarks",
            null,
            ContentValues().apply {
                put("url", url)
                put("title", title)
                put("position", next)
                put("created", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun renameBookmark(url: String, title: String) {
        writableDatabase.update("bookmarks", ContentValues().apply { put("title", title) }, "url = ?", arrayOf(url))
    }

    fun removeBookmark(url: String) {
        writableDatabase.delete("bookmarks", "url = ?", arrayOf(url))
    }

    fun moveBookmark(url: String, delta: Int) {
        val list = bookmarks().toMutableList()
        val i = list.indexOfFirst { it.url == url }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        list.add(j, list.removeAt(i))
        val db = writableDatabase
        db.beginTransaction()
        try {
            list.forEachIndexed { index, site ->
                db.update("bookmarks", ContentValues().apply { put("position", index) }, "url = ?", arrayOf(site.url))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        fun isRecordable(url: String): Boolean =
            url.startsWith("http://") || url.startsWith("https://")
    }
}
