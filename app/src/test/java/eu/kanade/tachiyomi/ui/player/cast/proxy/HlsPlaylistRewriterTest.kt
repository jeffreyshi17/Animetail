package eu.kanade.tachiyomi.ui.player.cast.proxy

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class HlsPlaylistRewriterTest {

    private val proxy = { url: String -> "P[$url]" }

    @Test
    fun `master playlist variants and alternate renditions are proxied with absolute urls`() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Japanese",URI="audio/jp.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,AUDIO="aud"
            720p/index.m3u8?token=abc
            #EXT-X-STREAM-INF:BANDWIDTH=5000000
            https://other.cdn/1080p.m3u8
        """.trimIndent()

        val rewritten = HlsPlaylistRewriter.rewrite(
            master,
            "https://cdn.example/show/ep1/master.m3u8".toHttpUrl(),
            proxy,
        )

        assertEquals(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Japanese",URI="P[https://cdn.example/show/ep1/audio/jp.m3u8]"
            #EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720,AUDIO="aud"
            P[https://cdn.example/show/ep1/720p/index.m3u8?token=abc]
            #EXT-X-STREAM-INF:BANDWIDTH=5000000
            P[https://other.cdn/1080p.m3u8]
            """.trimIndent(),
            rewritten,
        )
    }

    @Test
    fun `media playlist segments, keys and init maps are proxied, inline data keys are left alone`() {
        val media = "#EXTM3U\r\n" +
            "#EXT-X-KEY:METHOD=AES-128,URI=\"/keys/k1.bin\",IV=0x01\r\n" +
            "#EXT-X-MAP:URI=\"init.mp4\"\r\n" +
            "#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"data:text/plain;base64,AAAA\"\r\n" +
            "#EXTINF:4.0,\r\n" +
            "seg0.m4s\r\n" +
            "\r\n" +
            "#EXT-X-ENDLIST\r\n"

        val rewritten = HlsPlaylistRewriter.rewrite(media, "https://cdn.example/a/b/index.m3u8".toHttpUrl(), proxy)
            .lines()

        assertEquals("#EXT-X-KEY:METHOD=AES-128,URI=\"P[https://cdn.example/keys/k1.bin]\",IV=0x01", rewritten[1])
        assertEquals("#EXT-X-MAP:URI=\"P[https://cdn.example/a/b/init.mp4]\"", rewritten[2])
        assertEquals("#EXT-X-SESSION-KEY:METHOD=SAMPLE-AES,URI=\"data:text/plain;base64,AAAA\"", rewritten[3])
        assertEquals("P[https://cdn.example/a/b/seg0.m4s]", rewritten[5])
        assertEquals("#EXT-X-ENDLIST", rewritten[7])
    }

    @ParameterizedTest(name = "{3}")
    @CsvSource(
        "application/vnd.apple.mpegurl, https://h/x, , served with the HLS mime type",
        "text/plain, https://h/live.m3u8?sig=1, , detected from the path even with a wrong mime type",
        "application/octet-stream, https://h/playlist, '#EXTM3U\n#EXT', detected from the body",
    )
    fun `playlists are recognised`(
        contentType: String,
        url: String,
        head: String?,
        @Suppress("UNUSED_PARAMETER") why: String,
    ) {
        assertTrue(HlsPlaylistRewriter.isPlaylist(contentType, url, head))
    }

    @Test
    fun `segments are not mistaken for playlists`() {
        assertFalse(HlsPlaylistRewriter.isPlaylist("video/mp2t", "https://h/seg.ts", "G@"))
    }
}
