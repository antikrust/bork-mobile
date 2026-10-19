package online.borkbrowser.mobile

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * Saving what a page hands over as a download.
 *
 * GeckoView gives the app the response itself — headers and an open body
 * stream, already carrying the page's cookies — so the file is written from
 * that stream rather than fetched a second time by DownloadManager, which
 * would arrive without the session and get a login page instead.
 *
 * Android 10 and later: into the shared Downloads collection, no permission
 * needed. Older: the app's own Download folder, also permission-free.
 */
class Downloads(private val context: Context) {

    data class Done(val name: String, val uri: Uri, val mime: String)

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun fileName(response: WebResponse): String {
        val disposition = response.headers.entries
            .firstOrNull { it.key.equals("content-disposition", true) }?.value
        val mime = mimeOf(response)
        return URLUtil.guessFileName(response.uri, disposition, mime)
    }

    fun mimeOf(response: WebResponse): String =
        response.headers.entries.firstOrNull { it.key.equals("content-type", true) }
            ?.value?.substringBefore(';')?.trim()?.ifEmpty { null }
            ?: "application/octet-stream"

    fun save(response: WebResponse, onDone: (Done?) -> Unit) {
        val body = response.body ?: return onDone(null)
        val name = fileName(response)
        val mime = mimeOf(response).let {
            if (it == "application/octet-stream") {
                MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: it
            } else {
                it
            }
        }
        response.setReadTimeoutMillis(0)
        io.execute {
            val done = try {
                write(body, name, mime)
            } catch (e: Exception) {
                Log.e("BorkDownloads", "download of $name failed", e)
                null
            } finally {
                runCatching { body.close() }
            }
            main.post { onDone(done) }
        }
    }

    private fun write(body: InputStream, name: String, mime: String): Done {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("MediaStore refused $name")
            try {
                resolver.openOutputStream(uri)!!.use { out -> body.copyTo(out) }
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return Done(name, uri, mime)
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "Download")
        dir.mkdirs()
        var file = File(dir, name)
        var n = 1
        while (file.exists()) {
            file = File(dir, "${name.substringBeforeLast('.')} ($n)${name.substring(name.substringBeforeLast('.').length)}")
            n++
        }
        file.outputStream().use { out -> body.copyTo(out) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Done(file.name, uri, mime)
    }
}
