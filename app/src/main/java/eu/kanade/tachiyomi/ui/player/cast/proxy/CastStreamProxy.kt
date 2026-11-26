package eu.kanade.tachiyomi.ui.player.cast.proxy

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Serves what a cast receiver cannot fetch by itself, through the phone's local HTTP server:
 *
 * - streams that need the source's request headers (Referer, Origin, cookies), which a Chromecast
 *   cannot send; HLS playlists are rewritten so every nested request is proxied too;
 * - CORS headers, which the Default Media Receiver requires for HLS/DASH and subtitles;
 * - local files (downloads: `content://`, `file:` and absolute paths), with Range support for seeking;
 * - subtitles converted to WebVTT, the only side-loaded format receivers render.
 *
 * Every URL carries a random token registered for one cast load, so the server does not proxy
 * arbitrary URLs for other devices on the network. Local files are served only when they were
 * registered with the token, so a token seen on the network does not open other files.
 */
class CastStreamProxy(
    private val client: OkHttpClient,
    private val localFiles: LocalFileSource,
) {
    fun interface LocalFileSource {
        /** Opens a `content://` or `file://` URI, or returns null when it cannot be read. */
        fun open(uri: String): LocalFile?
    }

    class LocalFile(
        /** Size in bytes, or -1 when the provider does not know it. */
        val length: Long,
        val mimeType: String?,
        /** Opens the file positioned at the given byte offset. */
        val openAt: (offset: Long) -> InputStream,
    )

    private class Grant(val headers: Map<String, String>, val localUris: Set<String>)

    private val grants = ConcurrentHashMap<String, Grant>()
    private val random = SecureRandom()

    enum class Kind(val pathSegment: String) { STREAM("stream"), SUBTITLE("sub") }

    /**
     * Registers the headers upstream requests need and the local files ([isLocal]) the receiver may
     * read, and returns the token that unlocks them.
     */
    fun register(headers: Map<String, String>, localUris: Set<String> = emptySet()): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        grants[token] = Grant(headers, localUris)
        return token
    }

    /** Forgets every token: called when a cast session ends. */
    fun clear() = grants.clear()

    /**
     * The URL the receiver fetches for [upstream]. The upstream's file name is mirrored in the path
     * because receivers guess HLS segment formats from the extension.
     */
    fun url(baseUrl: String, token: String, kind: Kind, upstream: String): String {
        val name = upstream.substringBefore('?').substringBefore('#').substringAfterLast('/')
            .filter { it.isLetterOrDigit() || it in "._-" }
            .ifEmpty { "media" }
        return "${baseUrl.trimEnd('/')}/cast/${kind.pathSegment}/$token/$name?u=${URLEncoder.encode(upstream, "UTF-8")}"
    }

    fun handles(uri: String): Boolean = uri.startsWith("/cast/")

    fun serve(session: IHTTPSession): Response {
        if (session.method == NanoHTTPD.Method.OPTIONS) return withCors(text(Response.Status.OK, ""))

        val segments = session.uri.removePrefix("/cast/").split('/')
        val kind = Kind.entries.firstOrNull { it.pathSegment == segments.getOrNull(0) }
        val token = segments.getOrNull(1)
        val grant = token?.let { grants[it] }
        val upstream = session.parameters["u"]?.firstOrNull()
        if (kind == null || grant == null || upstream.isNullOrBlank() ||
            (isLocal(upstream) && upstream !in grant.localUris)
        ) {
            return withCors(text(Response.Status.NOT_FOUND, "Unknown cast resource"))
        }
        val headers = grant.headers
        val baseUrl = "http://${session.headers["host"] ?: "127.0.0.1"}"

        return withCors(
            try {
                when (kind) {
                    Kind.STREAM -> stream(upstream, headers, session.headers["range"]) { nested ->
                        url(baseUrl, token, Kind.STREAM, nested)
                    }

                    Kind.SUBTITLE -> subtitle(upstream, headers)
                }
            } catch (e: Exception) {
                text(Response.Status.INTERNAL_ERROR, "Cast proxy error: ${e.message}")
            },
        )
    }

    private fun stream(
        upstream: String,
        headers: Map<String, String>,
        range: String?,
        proxied: (String) -> String,
    ): Response {
        if (isLocal(upstream)) return local(upstream, range)

        val request = Request.Builder().url(upstream).apply {
            headers.forEach { (name, value) -> header(name, value) }
            range?.let { header("Range", it) }
        }.build()
        val response = client.newCall(request).execute()
        val body = response.body
        val contentType = response.header("Content-Type")
        if (!response.isSuccessful) {
            response.close()
            return text(status(response.code, response.message), "Upstream HTTP ${response.code}")
        }

        // Some hosts serve playlists as text/plain or octet-stream from extension-less URLs.
        val head = if (contentType == null || contentType.startsWith("text/") || "octet-stream" in contentType) {
            runCatching {
                val source = body.source()
                source.request(PLAYLIST_SNIFF_BYTES)
                source.buffer.snapshot(minOf(PLAYLIST_SNIFF_BYTES, source.buffer.size).toInt()).utf8()
            }.getOrNull()
        } else {
            null
        }
        if (HlsPlaylistRewriter.isPlaylist(contentType, upstream, head)) {
            val playlist = response.use { it.body.string() }
            val rewritten = HlsPlaylistRewriter.rewrite(playlist, response.request.url, proxied)
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, MIME_HLS, rewritten)
        }

        val length = body.contentLength()
        val mime = contentType ?: "application/octet-stream"
        val stream = body.byteStream()
        val out = if (length >= 0) {
            NanoHTTPD.newFixedLengthResponse(status(response.code, response.message), mime, stream, length)
        } else {
            NanoHTTPD.newChunkedResponse(status(response.code, response.message), mime, stream)
        }
        listOf("Content-Range", "Accept-Ranges").forEach { name ->
            response.header(name)?.let { out.addHeader(name, it) }
        }
        return out
    }

    private fun local(uri: String, range: String?): Response {
        val file = localFiles.open(uri) ?: return text(Response.Status.NOT_FOUND, "File not found")
        val mime = file.mimeType ?: guessMime(uri)
        if (file.length < 0) {
            return NanoHTTPD.newChunkedResponse(Response.Status.OK, mime, file.openAt(0))
        }
        val byteRange = range?.let { parseRange(it, file.length) }
        if (byteRange == null) {
            return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, mime, file.openAt(0), file.length).apply {
                addHeader("Accept-Ranges", "bytes")
            }
        }
        val (start, end) = byteRange
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.PARTIAL_CONTENT,
            mime,
            file.openAt(start),
            end - start + 1,
        ).apply {
            addHeader("Accept-Ranges", "bytes")
            addHeader("Content-Range", "bytes $start-$end/${file.length}")
        }
    }

    private fun subtitle(upstream: String, headers: Map<String, String>): Response {
        val text = if (isLocal(upstream)) {
            val file = localFiles.open(upstream) ?: return text(Response.Status.NOT_FOUND, "File not found")
            file.openAt(0).use { it.readBytes().decodeToString() }
        } else {
            val request = Request.Builder().url(upstream).apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return text(status(response.code, response.message), "Upstream HTTP ${response.code}")
                }
                response.body.string()
            }
        }
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            "text/vtt; charset=utf-8",
            WebVttConverter.toWebVtt(text, upstream),
        )
    }

    private fun withCors(response: Response): Response = response.apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "*")
        addHeader("Access-Control-Expose-Headers", "Content-Length, Content-Range, Accept-Ranges")
    }

    private fun text(status: Response.IStatus, message: String) =
        NanoHTTPD.newFixedLengthResponse(status, NanoHTTPD.MIME_PLAINTEXT, message)

    private fun status(code: Int, message: String): Response.IStatus =
        Response.Status.lookup(code) ?: object : Response.IStatus {
            override fun getDescription() = "$code ${message.ifEmpty { "Upstream" }}"
            override fun getRequestStatus() = code
        }

    companion object {
        const val MIME_HLS = "application/vnd.apple.mpegurl"
        private const val PLAYLIST_SNIFF_BYTES = 16L

        fun isLocal(uri: String) = uri.startsWith("content://") || uri.startsWith("file:") || uri.startsWith("/")

        /** Parses `bytes=a-b`, `bytes=a-` and `bytes=-n`; returns inclusive bounds, or null if unsatisfiable. */
        fun parseRange(header: String, length: Long): Pair<Long, Long>? {
            if (length <= 0) return null
            val spec = header.trim().removePrefix("bytes=").substringBefore(',').trim()
            val startText = spec.substringBefore('-').trim()
            val endText = spec.substringAfter('-', "").trim()
            val (start, end) = if (startText.isEmpty()) {
                val suffix = endText.toLongOrNull() ?: return null
                (length - suffix).coerceAtLeast(0) to length - 1
            } else {
                val start = startText.toLongOrNull() ?: return null
                start to (endText.toLongOrNull()?.coerceAtMost(length - 1) ?: (length - 1))
            }
            return if (start in 0..end) start to end else null
        }

        fun guessMime(uri: String): String {
            val name = URLDecoder.decode(uri, "UTF-8").substringBefore('?').lowercase()
            return when {
                name.endsWith(".mkv") -> "video/x-matroska"
                name.endsWith(".mp4") || name.endsWith(".m4v") -> "video/mp4"
                name.endsWith(".webm") -> "video/webm"
                name.endsWith(".ts") -> "video/mp2t"
                name.endsWith(".vtt") -> "text/vtt"
                else -> "application/octet-stream"
            }
        }

        /** Local source for plain files: `file:` URIs and absolute paths. */
        fun openFile(uri: String): LocalFile? {
            val file = if (uri.startsWith("file:")) File(URI(uri).path) else File(uri)
            if (!file.isFile) return null
            return LocalFile(file.length(), null) { offset ->
                file.inputStream().also { it.skipFully(offset) }
            }
        }

        fun InputStream.skipFully(count: Long) {
            var remaining = count
            while (remaining > 0) {
                val skipped = skip(remaining)
                if (skipped <= 0) {
                    if (read() == -1) return
                    remaining--
                } else {
                    remaining -= skipped
                }
            }
        }
    }
}
