package online.borkbrowser.mobile

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import mozilla.components.concept.sync.OAuthAccount
import mozilla.components.service.fxa.manager.SCOPE_SYNC
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.WebExtension
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/**
 * Add-ons from your other borks: the desktop's add-ons, installed here.
 *
 * A desktop bork signed in to the bork account keeps an "addons" collection in
 * its sync storage — one record per add-on it installed from
 * addons.mozilla.org, by id. Mozilla's Android libraries have no engine for
 * that collection (Firefox for Android never syncs add-ons), so this reads it
 * the way the desktop writes it:
 *
 *   1. the account's sync token and its scoped key (kSync),
 *   2. the token server, for a storage endpoint and a Hawk id/key,
 *   3. crypto/keys, decrypted with kSync, for the collection's key bundle,
 *   4. the addons collection, decrypted with that bundle.
 *
 * Every add-on the desktop has enabled that this phone lacks is looked up on
 * addons.mozilla.org and offered through the usual "Add …?" prompt — nothing
 * installs without a yes. A declined add-on is remembered and not offered
 * again. Only reads: nothing here ever writes to the account.
 */
class AddonSync(private val app: BorkApplication) {

    private val prefs = app.getSharedPreferences("addon-sync", 0)
    private var running = false

    /** After a sync: offer what the desktop has and this phone does not. */
    suspend fun run(account: OAuthAccount) {
        if (running || !app.settings.addonSync || app.addons.host == null) return
        running = true
        try {
            val wanted = withContext(Dispatchers.IO) { desktopAddons(account) }
            val have = app.addons.all.map { it.id }.toSet()
            val declined = prefs.getStringSet(DECLINED, emptySet()).orEmpty()
            for (id in wanted) {
                if (id in have || id in declined) continue
                if (app.addons.host == null) break // the browser went away mid-run
                val url = withContext(Dispatchers.IO) { amoDownloadUrl(id) } ?: continue
                when (install(url)) {
                    Outcome.DECLINED -> prefs.edit().putStringSet(DECLINED, declined + id).apply()
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[AddonSync] failed", e)
        } finally {
            running = false
        }
    }

    /** Forget the declines, so the next sync offers those add-ons again. */
    fun resetDeclined() {
        prefs.edit().remove(DECLINED).apply()
    }

    /* ----------------------------------------------------- reading the account */

    private suspend fun desktopAddons(account: OAuthAccount): List<String> {
        val token = account.getAccessToken(SCOPE_SYNC) ?: error("no sync token")
        val scoped = token.key ?: error("no sync key")
        val tokenServer = account.getTokenServerEndpointURL() ?: error("no token server")
        val tokenUrl = if (tokenServer.trimEnd('/').endsWith("/1.0/sync/1.5")) {
            tokenServer.trimEnd('/')
        } else {
            tokenServer.trimEnd('/') + "/1.0/sync/1.5"
        }

        val grant = JSONObject(
            get(URL(tokenUrl), mapOf("Authorization" to "Bearer ${token.token}", "X-KeyID" to scoped.kid)),
        )
        val endpoint = grant.getString("api_endpoint").trimEnd('/')
        val hawkId = grant.getString("id")
        val hawkKey = grant.getString("key")
        fun storage(path: String) = get(URL("$endpoint/$path"), mapOf("Authorization" to hawk("GET", URL("$endpoint/$path"), hawkId, hawkKey)))

        val kSync = Base64.decode(scoped.k, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        require(kSync.size == 64) { "unexpected sync key length ${kSync.size}" }
        val root = Bundle(kSync.copyOfRange(0, 32), kSync.copyOfRange(32, 64))

        val keys = JSONObject(decrypt(JSONObject(storage("storage/crypto/keys")), root))
        val pair = keys.optJSONObject("collections")?.optJSONArray("addons") ?: keys.getJSONArray("default")
        val bundle = Bundle(b64(pair.getString(0)), b64(pair.getString(1)))

        val records = JSONArray(storage("storage/addons?full=1"))
        val out = ArrayList<String>()
        for (i in 0 until records.length()) {
            val record = try {
                JSONObject(decrypt(records.getJSONObject(i), bundle))
            } catch (e: Exception) {
                Log.w(TAG, "[AddonSync] unreadable record", e)
                continue
            }
            if (record.optBoolean("deleted")) continue
            if (!record.optBoolean("enabled", true)) continue
            if (record.optString("source", "amo") != "amo") continue
            val id = record.optString("addonID").ifBlank { continue }
            out += id
        }
        return out
    }

    private class Bundle(val enc: ByteArray, val hmac: ByteArray)

    /** A sync record's payload: AES-256-CBC, authenticated by HMAC-SHA256 over the base64 ciphertext. */
    private fun decrypt(bso: JSONObject, bundle: Bundle): String {
        val payload = JSONObject(bso.getString("payload"))
        val ciphertext = payload.getString("ciphertext")
        val expected = payload.getString("hmac")
        val actual = hex(hmacSha256(bundle.hmac, ciphertext.toByteArray(Charsets.US_ASCII)))
        require(MessageDigest.isEqual(actual.toByteArray(), expected.lowercase().toByteArray())) { "record HMAC mismatch" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(bundle.enc, "AES"), IvParameterSpec(b64(payload.getString("IV"))))
        return String(cipher.doFinal(b64(ciphertext)), Charsets.UTF_8)
    }

    /** The storage server's request signature (Hawk, header form, no payload hash). */
    private fun hawk(method: String, url: URL, id: String, key: String): String {
        val ts = System.currentTimeMillis() / 1000
        val nonce = Base64.encodeToString(ByteArray(8).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP)
        val resource = url.path + (url.query?.let { "?$it" } ?: "")
        val port = if (url.port == -1) url.defaultPort else url.port
        val normalized = "hawk.1.header\n$ts\n$nonce\n$method\n$resource\n${url.host.lowercase()}\n$port\n\n\n"
        val mac = Base64.encodeToString(hmacSha256(key.toByteArray(Charsets.UTF_8), normalized.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        return "Hawk id=\"$id\", ts=\"$ts\", nonce=\"$nonce\", mac=\"$mac\""
    }

    /* ------------------------------------------------------------- installing */

    /** The current release's .xpi on addons.mozilla.org; null if AMO does not have it. */
    private fun amoDownloadUrl(id: String): String? = try {
        val encoded = URLEncoder.encode(id, "UTF-8")
        val json = JSONObject(get(URL("https://addons.mozilla.org/api/v5/addons/addon/$encoded/"), emptyMap()))
        json.getJSONObject("current_version").getJSONObject("file").getString("url")
    } catch (e: Exception) {
        Log.w(TAG, "[AddonSync] $id is not on addons.mozilla.org", e)
        null
    }

    private enum class Outcome { INSTALLED, DECLINED, FAILED }

    private suspend fun install(url: String): Outcome = suspendCancellableCoroutine { cont ->
        app.addons.install(url).accept(
            { cont.resume(Outcome.INSTALLED) },
            { e ->
                val canceled = (e as? WebExtension.InstallException)?.code ==
                    WebExtension.InstallException.ErrorCodes.ERROR_USER_CANCELED
                if (!canceled) Log.e(TAG, "[AddonSync] installing $url failed", e)
                cont.resume(if (canceled) Outcome.DECLINED else Outcome.FAILED)
            },
        )
    }

    /* ---------------------------------------------------------------- plumbing */

    private fun get(url: URL, headers: Map<String, String>): String {
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Accept", "application/json")
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val code = conn.responseCode
            if (code !in 200..299) error("${url.host}${url.path}: HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    private fun b64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "bork"
        private const val DECLINED = "declined"
    }
}
