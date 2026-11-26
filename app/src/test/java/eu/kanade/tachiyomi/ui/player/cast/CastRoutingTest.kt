package eu.kanade.tachiyomi.ui.player.cast

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class CastRoutingTest {

    data class Case(
        val description: String,
        val url: String,
        val headers: Map<String, String>,
        val proxied: Boolean,
    ) {
        override fun toString() = description
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `streams the receiver cannot fetch itself go through the proxy`(case: Case) {
        val contentType = CastMediaRequest.contentTypeFor(case.url)

        assertEquals(case.proxied, CastRouting.proxyStream(case.url, case.headers, contentType), case.description)
    }

    @Test
    fun `the receiver content type follows the stream format`() {
        assertEquals(CastMediaRequest.CONTENT_TYPE_HLS, CastMediaRequest.contentTypeFor("https://h/a/master.m3u8?t=1"))
        assertEquals(
            CastMediaRequest.CONTENT_TYPE_HLS,
            CastMediaRequest.contentTypeFor("http://localhost:1/m3u8?url=x"),
        )
        assertEquals(CastMediaRequest.CONTENT_TYPE_MKV, CastMediaRequest.contentTypeFor("content://x/Episode 1.mkv"))
        assertEquals(CastMediaRequest.CONTENT_TYPE_MP4, CastMediaRequest.contentTypeFor("https://h/video"))
    }

    data class BackendCase(
        val preference: CastBackend,
        val playServices: Boolean,
        val expected: CastBackend,
    )

    @ParameterizedTest
    @MethodSource("backendCases")
    fun `automatic backend uses google cast only with play services`(case: BackendCase) {
        assertEquals(case.expected, CastBackend.resolve(case.preference, case.playServices))
    }

    @Test
    fun `the lan address skips vpn and mobile interfaces`() {
        val picked = LanAddress.pick(
            listOf(
                LanAddress.Candidate("tailscale0", "100.111.180.122", 32),
                LanAddress.Candidate("rmnet_data0", "10.45.0.7", 30),
                LanAddress.Candidate("wlan0", "192.168.0.132", 24),
            ),
        )

        assertEquals("192.168.0.132", picked?.address)
    }

    companion object {
        private val ua = mapOf("User-Agent" to "Mozilla/5.0")

        @JvmStatic
        fun cases() = listOf(
            Case("plain http mp4 with only a user agent plays directly", "http://cdn/ep.mp4", ua, proxied = false),
            Case(
                "https mp4 is relayed, since receivers may not trust the host's certificate chain",
                "https://cdn/ep.mp4",
                ua,
                proxied = true,
            ),
            Case(
                "referer-gated mp4 needs the injected header",
                "http://cdn/ep.mp4",
                ua + ("Referer" to "https://s/"),
                true,
            ),
            Case("hls always needs CORS", "https://cdn/master.m3u8", emptyMap(), proxied = true),
            Case("dash is not rewritten, so it cannot be proxied", "https://cdn/x.mpd", mapOf("Referer" to "r"), false),
            Case("downloaded episode", "content://com.android.externalstorage/ep.mkv", emptyMap(), proxied = true),
            Case("extension server on the phone", "http://localhost:8080/video.mp4", emptyMap(), proxied = true),
            Case("torrent server on the phone", "http://127.0.0.1:8090/stream/x?play", emptyMap(), proxied = true),
        )

        @JvmStatic
        fun backendCases() = listOf(
            BackendCase(CastBackend.AUTO, playServices = true, expected = CastBackend.GOOGLE_CAST),
            BackendCase(CastBackend.AUTO, playServices = false, expected = CastBackend.DIRECT),
            BackendCase(CastBackend.GOOGLE_CAST, playServices = false, expected = CastBackend.GOOGLE_CAST),
            BackendCase(CastBackend.DIRECT, playServices = true, expected = CastBackend.DIRECT),
        )
    }
}
