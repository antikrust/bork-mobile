package online.borkbrowser.mobile

import android.net.Uri
import android.text.Html
import android.util.Base64
import org.mozilla.geckoview.WebRequestError

object UrlInput {
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val HOSTISH = Regex("^[^\\s/?#]+\\.[^\\s/?#.]{2,}([/?#].*)?$")
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?([/?#].*)?$")
    private val LOCALHOST = Regex("^localhost(:\\d+)?([/?#].*)?$", RegexOption.IGNORE_CASE)

    /** What the address bar should load for what was typed into it. */
    fun toUrl(input: String, engine: SearchEngine): String {
        val text = input.trim()
        if (text.isEmpty()) return ""
        if (text.contains(' ')) return engine.urlFor(text)
        if (SCHEME.containsMatchIn(text) && !Regex("^[^:]+:\\d").containsMatchIn(text)) {
            val scheme = text.substringBefore(':').lowercase()
            if (scheme in KNOWN) return text
        }
        if (HOSTISH.matches(text) || IPV4.matches(text) || LOCALHOST.matches(text)) {
            val http = IPV4.matches(text) || LOCALHOST.matches(text)
            return (if (http) "http://" else "https://") + text
        }
        return engine.urlFor(text)
    }

    /** The URL as the address bar shows it while not being edited. */
    fun display(url: String): String {
        if (url.isEmpty()) return ""
        val uri = Uri.parse(url)
        return when (uri.scheme) {
            "http", "https" -> url.substringAfter("://").removePrefix("www.").removeSuffix("/")
            else -> url
        }
    }

    fun host(url: String): String =
        runCatching { Uri.parse(url).host?.removePrefix("www.") }.getOrNull().orEmpty()

    private val KNOWN = setOf(
        "http", "https", "about", "data", "file", "view-source", "moz-extension",
        "resource", "blob", "javascript",
    )

    /** Schemes Gecko loads itself; anything else is handed to Android. */
    val GECKO_SCHEMES = setOf(
        "http", "https", "about", "data", "file", "view-source", "moz-extension",
        "resource", "blob", "javascript", "chrome", "jar", "wss", "ws",
    )
}

object ErrorPage {
    fun dataUri(uri: String, error: WebRequestError): String {
        val (title, detail) = describe(error)
        val safeUri = Html.escapeHtml(uri)
        val html = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>${Html.escapeHtml(title)}</title>
            <style>
              :root { color-scheme: dark light; }
              body { margin: 0; padding: 48px 24px; font: 16px/1.5 system-ui, sans-serif;
                     background: #050506; color: #ececef; }
              @media (prefers-color-scheme: light) { body { background: #fafafa; color: #15151a; } }
              .eyes { font-size: 56px; letter-spacing: -6px; margin-bottom: 12px; }
              h1 { font-size: 22px; margin: 0 0 8px; }
              p { margin: 0 0 12px; opacity: .8; }
              code { word-break: break-all; opacity: .7; font-size: 13px; }
              button { margin-top: 20px; padding: 12px 22px; border: 0; border-radius: 10px;
                       background: #0a84ff; color: #fff; font: 600 15px system-ui, sans-serif; }
            </style></head><body>
            <div class="eyes">◉◉</div>
            <h1>${Html.escapeHtml(title)}</h1>
            <p>${Html.escapeHtml(detail)}</p>
            <code>$safeUri</code><br>
            <button onclick="location.reload()">Try again</button>
            </body></html>
        """.trimIndent()
        return "data:text/html;charset=utf-8;base64," +
            Base64.encodeToString(html.toByteArray(), Base64.NO_WRAP)
    }

    private fun describe(e: WebRequestError): Pair<String, String> = when (e.code) {
        WebRequestError.ERROR_UNKNOWN_HOST ->
            "Can't find that site" to "The address may be mistyped, or you may be offline."
        WebRequestError.ERROR_CONNECTION_REFUSED ->
            "Connection refused" to "The site is up but would not take the connection."
        WebRequestError.ERROR_NET_TIMEOUT ->
            "The site took too long" to "It may be busy or unreachable from here."
        WebRequestError.ERROR_OFFLINE ->
            "You're offline" to "Check the connection and try again."
        WebRequestError.ERROR_SECURITY_BAD_CERT,
        WebRequestError.ERROR_SECURITY_SSL ->
            "Not a secure connection" to "The site's certificate could not be trusted, so bork stopped before sending anything."
        WebRequestError.ERROR_MALFORMED_URI ->
            "That isn't an address" to "The URL could not be understood."
        WebRequestError.ERROR_UNKNOWN_PROTOCOL ->
            "Nothing can open this" to "No app on this phone handles that kind of link."
        WebRequestError.ERROR_SAFEBROWSING_MALWARE_URI,
        WebRequestError.ERROR_SAFEBROWSING_PHISHING_URI,
        WebRequestError.ERROR_SAFEBROWSING_UNWANTED_URI,
        WebRequestError.ERROR_SAFEBROWSING_HARMFUL_URI ->
            "Deceptive or harmful site" to "This page is on a list of sites known to steal information or install malware."
        WebRequestError.ERROR_CONTENT_CRASHED ->
            "This tab crashed" to "Reloading usually brings it back."
        else -> "This page could not be loaded" to "Something went wrong between here and the site."
    }
}
