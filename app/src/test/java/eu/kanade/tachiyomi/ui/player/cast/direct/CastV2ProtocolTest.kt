package eu.kanade.tachiyomi.ui.player.cast.direct

import eu.kanade.tachiyomi.ui.player.cast.CastMediaRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class CastV2ProtocolTest {

    @Test
    fun `cast message encodes the required proto2 enums even though they are zero`() {
        val message = CastV2Message(
            sourceId = "s",
            destinationId = "d",
            namespace = "n",
            payloadUtf8 = "{}",
        )

        // Hand-encoded cast_channel.proto: receivers reject frames missing the required fields 1 and 5.
        val expected = byteArrayOf(
            0x08, 0x00, // protocol_version = CASTV2_1_0
            0x12, 0x01, 's'.code.toByte(),
            0x1A, 0x01, 'd'.code.toByte(),
            0x22, 0x01, 'n'.code.toByte(),
            0x28, 0x00, // payload_type = STRING
            0x32, 0x02, '{'.code.toByte(), '}'.code.toByte(),
        )
        assertArrayEquals(expected, CastV2Message.encode(message))
    }

    @Test
    fun `frames round trip with a big-endian length prefix`() {
        val message = CastV2Message(
            sourceId = "sender-1",
            destinationId = CastV2Protocol.RECEIVER_ID,
            namespace = CastV2Protocol.NS_RECEIVER,
            payloadUtf8 = CastV2Protocol.launch(requestId = 3),
        )
        val out = ByteArrayOutputStream()
        CastV2Message.writeFrame(out, message)
        val bytes = out.toByteArray()

        val bodyLength = CastV2Message.encode(message).size
        assertEquals(bodyLength, ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF))
        assertEquals(message, CastV2Message.readFrame(DataInputStream(bytes.inputStream())))
    }

    @Test
    fun `load request carries subtitles as WebVTT text tracks and activates the chosen one`() {
        val request = CastMediaRequest(
            url = "http://192.168.0.10:8181/cast/stream?t=abc",
            contentType = CastMediaRequest.CONTENT_TYPE_HLS,
            title = "Show",
            subtitle = "Episode 3",
            imageUrl = "https://img/cover.jpg",
            durationSeconds = 1440.0,
            startPositionSeconds = 61.5,
            autoplay = true,
            textTracks = listOf(
                CastMediaRequest.TextTrack(
                    url = "http://192.168.0.10:8181/cast/sub?t=1",
                    name = "English",
                    language = "en",
                ),
                CastMediaRequest.TextTrack(
                    url = "http://192.168.0.10:8181/cast/sub?t=2",
                    name = "Spanish",
                    language = null,
                ),
            ),
            activeTextTrackIndex = 1,
        )

        val load = parse(CastV2Protocol.load(requestId = 7, sessionId = "app-session", request = request))
        val media = load["media"]!!.jsonObject
        val tracks = media["tracks"]!!.jsonArray.map { it.jsonObject }

        assertEquals("LOAD", load.str("type"))
        assertEquals("app-session", load.str("sessionId"))
        assertEquals(61.5, load["currentTime"]!!.jsonPrimitive.content.toDouble())
        assertEquals(request.url, media.str("contentId"))
        assertEquals("BUFFERED", media.str("streamType"))
        assertEquals("Episode 3", media["metadata"]!!.jsonObject.str("subtitle"))
        assertEquals(listOf("text/vtt", "text/vtt"), tracks.map { it.str("trackContentType") })
        assertEquals(listOf(1, 2), tracks.map { it["trackId"]!!.jsonPrimitive.int })
        assertNull(tracks[1]["language"], "an unknown language must be omitted, not sent as null")
        assertEquals(listOf(2), load["activeTrackIds"]!!.jsonArray.map { it.jsonPrimitive.int })
    }

    @Test
    fun `load request without subtitles sends no tracks and no active track ids`() {
        val request = CastMediaRequest(
            url = "https://cdn/video.mp4",
            contentType = CastMediaRequest.CONTENT_TYPE_MP4,
            title = "t",
            subtitle = "s",
            imageUrl = null,
            durationSeconds = null,
            startPositionSeconds = 0.0,
            autoplay = true,
            activeTextTrackIndex = 0,
        )
        val load = parse(CastV2Protocol.load(1, "x", request))

        assertFalse("tracks" in load["media"]!!.jsonObject)
        assertFalse("activeTrackIds" in load)
        assertFalse("duration" in load["media"]!!.jsonObject)
    }

    @Test
    fun `android ARGB colors become cast RRGGBBAA`() {
        assertEquals("#FFFFFFFF", CastV2Protocol.cssColor(0xFFFFFFFF.toInt()))
        assertEquals("#00000000", CastV2Protocol.cssColor(0x00000000))
        assertEquals("#11223380", CastV2Protocol.cssColor(0x80112233.toInt()))
    }

    @Test
    fun `receiver status lists running apps and volume`() {
        val status = CastV2Protocol.receiverStatus(
            parse(
                """
                {"requestId":2,"status":{"applications":[{"appId":"CC1AD845","displayName":"Default Media Receiver",
                "sessionId":"sess-1","transportId":"web-5","namespaces":[{"name":"urn:x-cast:com.google.cast.media"}]}],
                "volume":{"level":0.4,"muted":false}},"type":"RECEIVER_STATUS"}
                """,
            ),
        )

        assertEquals("web-5", status.app(CastV2Protocol.DEFAULT_MEDIA_RECEIVER_APP_ID)?.transportId)
        assertEquals(0.4, status.volumeLevel)
    }

    @Test
    fun `media status parses player state, position and metadata`() {
        val statuses = CastV2Protocol.mediaStatuses(
            parse(
                """
                {"type":"MEDIA_STATUS","requestId":0,"status":[{"mediaSessionId":4,"playerState":"PLAYING",
                "currentTime":12.5,"activeTrackIds":[1],"media":{"duration":596.4,
                "metadata":{"title":"Big Buck Bunny","images":[{"url":"https://img"}]}}}]}
                """,
            ),
        )

        val status = statuses.single()
        assertEquals(4, status.mediaSessionId)
        assertEquals(CastV2Protocol.PlayerState.PLAYING, status.playerState)
        assertEquals(12.5, status.currentTimeSeconds)
        assertEquals(596.4, status.durationSeconds)
        assertEquals(listOf(1), status.activeTrackIds)
        assertEquals("https://img", status.imageUrl)
    }

    private fun parse(payload: String): JsonObject = Json.parseToJsonElement(payload.trimIndent()).jsonObject

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
}
