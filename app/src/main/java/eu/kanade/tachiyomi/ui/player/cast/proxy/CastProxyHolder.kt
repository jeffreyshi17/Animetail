package eu.kanade.tachiyomi.ui.player.cast.proxy

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.ui.player.cast.CastRouting
import eu.kanade.tachiyomi.ui.player.cast.proxy.CastStreamProxy.Companion.skipFully
import eu.kanade.tachiyomi.util.LocalHttpServerHolder
import eu.kanade.tachiyomi.util.LocalHttpServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * The app's [CastStreamProxy], served by the existing cast HTTP server ([LocalHttpServerService]).
 * Uses the app's HTTP client, so source cookies (e.g. Cloudflare clearance) apply to proxied requests.
 */
@Inject
@SingleIn(AppScope::class)
class CastProxyHolder(
    private val context: Context,
    networkHelper: NetworkHelper,
    private val serverHolder: LocalHttpServerHolder,
) {
    val proxy = CastStreamProxy(
        client = networkHelper.client.newBuilder()
            // A receiver may stream a whole episode in one request; the app client gives up after 2 minutes.
            .callTimeout(0, TimeUnit.MILLISECONDS)
            // Video bytes must not fill the HTTP cache.
            .cache(null)
            .build(),
        localFiles = { uri -> openLocal(uri) },
    )

    val port: Int get() = serverHolder.port().get().toInt()

    /** A stream URL and its subtitles as the receiver must fetch them. */
    data class Routed(val streamUrl: String, val subtitleUrls: List<String>)

    /**
     * Routes [streamUrl] and [subtitleUrls] through the proxy where [CastRouting] says the receiver
     * cannot fetch them itself, starting the cast server when anything is proxied.
     *
     * @param lanHost the phone's address on the network the receiver is on.
     */
    suspend fun route(
        streamUrl: String,
        headers: Map<String, String>,
        contentType: String,
        subtitleUrls: List<String>,
        lanHost: String,
    ): Routed {
        val proxyStream = CastRouting.proxyStream(streamUrl, headers, contentType)
        if (proxyStream || subtitleUrls.isNotEmpty()) ensureServerRunning()
        val baseUrl = "http://$lanHost:$port"
        val token = proxy.register(
            headers = headers,
            localUris = (subtitleUrls + streamUrl).filterTo(mutableSetOf(), CastStreamProxy::isLocal),
        )
        return Routed(
            streamUrl = if (proxyStream) {
                proxy.url(
                    baseUrl,
                    token,
                    CastStreamProxy.Kind.STREAM,
                    streamUrl,
                )
            } else {
                streamUrl
            },
            subtitleUrls = subtitleUrls.map { proxy.url(baseUrl, token, CastStreamProxy.Kind.SUBTITLE, it) },
        )
    }

    /** Starts the cast HTTP server if needed and waits until it accepts connections. */
    suspend fun ensureServerRunning() {
        context.startService(Intent(context, LocalHttpServerService::class.java))
        val listening = withTimeoutOrNull(SERVER_START_TIMEOUT_MS) {
            while (!isListening()) delay(100)
            true
        }
        if (listening != true) throw IOException("The cast server did not start on port $port")
    }

    private suspend fun isListening(): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isSuccess
    }

    private fun openLocal(uri: String): CastStreamProxy.LocalFile? {
        if (!uri.startsWith("content://")) return CastStreamProxy.openFile(uri)
        val resolver = context.contentResolver
        val parsed = uri.toUri()
        val length = runCatching { resolver.openAssetFileDescriptor(parsed, "r")?.use { it.length } }.getOrNull()
            ?: return null
        return CastStreamProxy.LocalFile(length = length, mimeType = resolver.getType(parsed)) { offset ->
            (resolver.openInputStream(parsed) ?: throw IOException("Cannot open $uri")).also { it.skipFully(offset) }
        }
    }

    private companion object {
        const val SERVER_START_TIMEOUT_MS = 5_000L
    }
}
