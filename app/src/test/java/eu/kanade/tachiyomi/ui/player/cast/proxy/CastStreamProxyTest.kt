package eu.kanade.tachiyomi.ui.player.cast.proxy

import fi.iki.elonen.NanoHTTPD
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File
import java.nio.file.Files

/**
 * Runs the proxy behind a real NanoHTTPD in front of an upstream that, like many anime CDNs, refuses
 * requests without the right Referer and sends no CORS headers.
 */
class CastStreamProxyTest {

    private val client = OkHttpClient()

    private val upstream = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response {
            if (session.headers["referer"] != REFERER) {
                return newFixedLengthResponse(Response.Status.FORBIDDEN, MIME_PLAINTEXT, "no referer")
            }
            return when (session.uri) {
                "/show/master.m3u8" -> newFixedLengthResponse(
                    Response.Status.OK,
                    "application/vnd.apple.mpegurl",
                    "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nlow/index.m3u8\n",
                )

                "/show/low/seg0.ts" -> newFixedLengthResponse(Response.Status.OK, "video/mp2t", "SEGMENT")

                "/subs/en.srt" -> newFixedLengthResponse(
                    Response.Status.OK,
                    "application/x-subrip",
                    "1\n00:00:01,000 --> 00:00:02,000\nHi\n",
                )

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "nope")
            }
        }
    }.apply { start() }

    private val localFile: File = Files.createTempFile("cast-proxy", ".mkv").toFile().apply {
        writeBytes(ByteArray(100) { it.toByte() })
    }

    private val proxy = CastStreamProxy(client) { uri -> CastStreamProxy.openFile(uri) }

    private val server = object : NanoHTTPD("127.0.0.1", 0) {
        override fun serve(session: IHTTPSession): Response = proxy.serve(session)
    }.apply { start() }

    private val baseUrl = "http://127.0.0.1:${server.listeningPort}"
    private val upstreamUrl = "http://127.0.0.1:${upstream.listeningPort}"

    @AfterEach
    fun tearDown() {
        server.stop()
        upstream.stop()
        localFile.delete()
    }

    @Test
    fun `hls playlists are rewritten so nested requests also carry the source headers`() {
        val token = proxy.register(mapOf("Referer" to REFERER))
        val master = get(proxy.url(baseUrl, token, CastStreamProxy.Kind.STREAM, "$upstreamUrl/show/master.m3u8"))

        assertEquals(200, master.code)
        assertEquals("*", master.headers["Access-Control-Allow-Origin"])
        val variantUrl = master.body.lines()[2]
        assertEquals(
            proxy.url(baseUrl, token, CastStreamProxy.Kind.STREAM, "$upstreamUrl/show/low/index.m3u8"),
            variantUrl,
        )
        assertTrue(variantUrl.contains("/index.m3u8?u="), "the file name is mirrored for format sniffing")

        val segment = get(proxy.url(baseUrl, token, CastStreamProxy.Kind.STREAM, "$upstreamUrl/show/low/seg0.ts"))
        assertEquals("SEGMENT", segment.body)
        assertEquals("video/mp2t", segment.headers["Content-Type"])
    }

    @Test
    fun `upstream errors pass through with their status`() {
        val token = proxy.register(emptyMap())

        assertEquals(
            403,
            get(proxy.url(baseUrl, token, CastStreamProxy.Kind.STREAM, "$upstreamUrl/show/master.m3u8")).code,
        )
    }

    @Test
    fun `unknown tokens are refused so the server is not an open proxy`() {
        val response = get(proxy.url(baseUrl, "0123", CastStreamProxy.Kind.STREAM, "$upstreamUrl/show/master.m3u8"))

        assertEquals(404, response.code)
    }

    @Test
    fun `subtitles are converted to webvtt`() {
        val token = proxy.register(mapOf("Referer" to REFERER))
        val response = get(proxy.url(baseUrl, token, CastStreamProxy.Kind.SUBTITLE, "$upstreamUrl/subs/en.srt"))

        assertEquals("text/vtt; charset=utf-8", response.headers["Content-Type"])
        assertEquals("WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n\n", response.body)
    }

    @Test
    fun `local files are served with range support for seeking`() {
        val fileUri = localFile.toURI().toString()
        val token = proxy.register(emptyMap(), localUris = setOf(fileUri))
        val url = proxy.url(baseUrl, token, CastStreamProxy.Kind.STREAM, fileUri)

        val full = get(url)
        val partial = get(url, range = "bytes=10-19")

        assertEquals(200, full.code)
        assertEquals("100", full.headers["Content-Length"])
        assertEquals("video/x-matroska", full.headers["Content-Type"])
        assertEquals(206, partial.code)
        assertEquals("bytes 10-19/100", partial.headers["Content-Range"])
        assertEquals((10..19).map { it.toChar() }.joinToString(""), partial.body)
    }

    @Test
    fun `a token only opens the local files registered with it`() {
        val token = proxy.register(emptyMap(), localUris = setOf("file:/other/episode.mkv"))

        assertEquals(
            404,
            get(proxy.url(baseUrl, token, CastStreamProxy.Kind.STREAM, localFile.toURI().toString())).code,
        )
        assertEquals(404, get(proxy.url(baseUrl, token, CastStreamProxy.Kind.SUBTITLE, localFile.absolutePath)).code)
    }

    @ParameterizedTest(name = "{0} of 100 bytes")
    @CsvSource(
        "bytes=0-, 0, 99",
        "bytes=90-200, 90, 99",
        "bytes=-10, 90, 99",
        "bytes=5-5, 5, 5",
    )
    fun `byte ranges`(header: String, start: Long, end: Long) {
        assertEquals(start to end, CastStreamProxy.parseRange(header, 100))
    }

    @Test
    fun `unsatisfiable ranges are ignored`() {
        assertNull(CastStreamProxy.parseRange("bytes=150-", 100))
    }

    private data class Fetched(val code: Int, val headers: okhttp3.Headers, val body: String)

    private fun get(url: String, range: String? = null): Fetched {
        val request = Request.Builder().url(url).apply { range?.let { header("Range", it) } }.build()
        return client.newCall(request).execute().use {
            Fetched(it.code, it.headers, it.body.bytes().toString(Charsets.ISO_8859_1))
        }
    }

    private companion object {
        const val REFERER = "https://anime.example/"
    }
}
