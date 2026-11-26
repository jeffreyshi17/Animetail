package eu.kanade.tachiyomi.ui.player.cast.direct

import eu.kanade.tachiyomi.ui.player.cast.CastMediaRequest
import eu.kanade.tachiyomi.ui.player.cast.proxy.CastStreamProxy
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Drives a real Chromecast on the LAN: `CAST_E2E_HOST=192.168.0.151 ./gradlew :app:testDebugUnitTest
 * --tests '*DirectCastDeviceTest*'`. Casts a short public test clip for a few seconds and stops it.
 */
@EnabledIfEnvironmentVariable(named = "CAST_E2E_HOST", matches = ".+")
class DirectCastDeviceTest {

    @Test
    fun `launches the default media receiver, plays, pauses, seeks and stops`() = runBlocking {
        val session = DirectCastSession.connect(System.getenv("CAST_E2E_HOST"))
        try {
            assertEquals(DirectCastState.Phase.CONNECTED, session.state.value.phase)

            session.load(
                CastMediaRequest(
                    // Mux's public HLS test stream (Big Buck Bunny); Google's old sample MP4 now answers 403.
                    url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                    contentType = CastMediaRequest.CONTENT_TYPE_HLS,
                    title = "Big Buck Bunny",
                    subtitle = "Animetail direct cast test",
                    imageUrl = null,
                    durationSeconds = null,
                    startPositionSeconds = 30.0,
                    autoplay = true,
                ),
            )
            withTimeout(15_000) { session.state.first { it.isPlaying } }
            println(
                "playing: ${session.state.value.media}",
            )

            session.pause()
            withTimeout(5_000) { session.state.first { it.media?.playerState == CastV2Protocol.PlayerState.PAUSED } }

            session.seek(120.0)
            withTimeout(5_000) { session.state.first { (it.media?.currentTimeSeconds ?: 0.0) >= 119.0 } }
            assertTrue((session.state.value.media?.durationSeconds ?: 0.0) > 500, "duration comes from the receiver")
        } finally {
            session.stop()
        }
        assertEquals(DirectCastState.Phase.ENDED, session.state.value.phase)
    }

    @Test
    fun `plays a stream and a converted subtitle served through the cast proxy`() = runBlocking {
        val proxy = CastStreamProxy(OkHttpClient()) { null }
        val receiverRequests = java.util.concurrent.ConcurrentLinkedQueue<String>()
        val subtitleHost = object : NanoHTTPD(0) {
            override fun serve(session: IHTTPSession): Response = if (session.uri == "/en.srt") {
                newFixedLengthResponse(Response.Status.OK, "application/x-subrip", SRT)
            } else {
                receiverRequests += session.uri
                proxy.serve(session)
            }
        }.apply { start() }
        val session = DirectCastSession.connect(System.getenv("CAST_E2E_HOST"))
        try {
            // The address the Chromecast reaches us on, exactly as the app picks it.
            val base = "http://${session.localAddress.hostAddress}:${subtitleHost.listeningPort}"
            val token = proxy.register(mapOf("Referer" to "https://example.org/"))
            session.load(
                CastMediaRequest(
                    url = proxy.url(
                        base,
                        token,
                        CastStreamProxy.Kind.STREAM,
                        "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                    ),
                    contentType = CastMediaRequest.CONTENT_TYPE_HLS,
                    title = "Big Buck Bunny",
                    subtitle = "Animetail direct cast proxy test",
                    imageUrl = null,
                    durationSeconds = null,
                    startPositionSeconds = 0.0,
                    autoplay = true,
                    textTracks = listOf(
                        CastMediaRequest.TextTrack(
                            url = proxy.url(base, token, CastStreamProxy.Kind.SUBTITLE, "$base/en.srt"),
                            name = "English",
                            language = "en",
                        ),
                    ),
                    activeTextTrackIndex = 0,
                ),
            )
            withTimeout(20_000) { session.state.first { it.isPlaying && (it.media?.currentTimeSeconds ?: 0.0) > 1.0 } }
            assertEquals(listOf(CastV2Protocol.trackId(0)), session.state.value.media?.activeTrackIds)
            println(
                "receiver fetched through the proxy: ${receiverRequests.map {
                    it.substringAfterLast('/')
                }.distinct().take(8)}",
            )
            assertTrue(receiverRequests.any { it.startsWith("/cast/sub/") }, "the receiver fetched the subtitle")
            assertTrue(receiverRequests.any { it.endsWith(".ts") }, "segments went through the proxy")
        } finally {
            session.stop()
            subtitleHost.stop()
        }
    }

    /** A downloaded episode: a local MKV served from the phone with Range support. Set CAST_E2E_MKV to a file. */
    @Test
    @EnabledIfEnvironmentVariable(named = "CAST_E2E_MKV", matches = ".+")
    fun `plays a local mkv file served by the cast proxy`() = runBlocking {
        val proxy = CastStreamProxy(OkHttpClient()) { uri -> CastStreamProxy.openFile(uri) }
        val server = object : NanoHTTPD(0) {
            override fun serve(session: IHTTPSession): Response = proxy.serve(session)
        }.apply { start() }
        val session = DirectCastSession.connect(System.getenv("CAST_E2E_HOST"))
        try {
            val base = "http://${session.localAddress.hostAddress}:${server.listeningPort}"
            val file = java.io.File(System.getenv("CAST_E2E_MKV"))
            val fileUri = file.toURI().toString()
            session.load(
                CastMediaRequest(
                    url = proxy.url(
                        base,
                        proxy.register(emptyMap(), localUris = setOf(fileUri)),
                        CastStreamProxy.Kind.STREAM,
                        fileUri,
                    ),
                    contentType = CastMediaRequest.contentTypeFor(file.name),
                    title = "Local MKV",
                    subtitle = "Animetail direct cast download test",
                    imageUrl = null,
                    durationSeconds = null,
                    startPositionSeconds = 2.0,
                    autoplay = true,
                ),
            )
            withTimeout(15_000) {
                session.state.first {
                    it.isPlaying || it.media?.idleReason == "ERROR"
                }
            }
            assertEquals(CastV2Protocol.PlayerState.PLAYING, session.state.value.media?.playerState)
        } finally {
            session.stop()
            server.stop()
        }
    }

    private companion object {
        const val SRT = "1\n00:00:00,000 --> 00:00:30,000\nAnimetail cast proxy subtitle\n"
    }
}
