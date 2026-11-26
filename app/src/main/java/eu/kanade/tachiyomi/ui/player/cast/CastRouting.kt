package eu.kanade.tachiyomi.ui.player.cast

import eu.kanade.tachiyomi.ui.player.cast.proxy.CastStreamProxy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Decides which URLs the receiver can fetch itself and which must go through the cast proxy. */
object CastRouting {

    fun proxyStream(url: String, headers: Map<String, String>, contentType: String): Boolean {
        if (CastStreamProxy.isLocal(url)) return true
        val host = url.toHttpUrlOrNull()?.host ?: return false
        // Extension and torrent servers on the phone itself.
        if (host == "localhost" || host.startsWith("127.")) return true
        return when (contentType) {
            // MPD manifests are not rewritten, so their relative segment URLs would miss the proxy.
            CastMediaRequest.CONTENT_TYPE_DASH -> false

            // The receiver fetches HLS with XHR, which needs CORS headers most video CDNs do not send.
            CastMediaRequest.CONTENT_TYPE_HLS -> true

            // Receivers run old firmware whose TLS trust store can lag behind current certificate
            // chains (e.g. Let's Encrypt's YE hierarchy), so HTTPS is relayed over the LAN. Plain
            // HTTP plays directly unless it needs headers the receiver cannot send.
            else -> url.startsWith("https:", ignoreCase = true) ||
                headers.keys.any { !it.equals("User-Agent", ignoreCase = true) }
        }
    }
}
