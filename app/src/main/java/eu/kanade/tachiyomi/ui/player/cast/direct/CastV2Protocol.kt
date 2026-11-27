package eu.kanade.tachiyomi.ui.player.cast.direct

import eu.kanade.tachiyomi.ui.player.cast.CastMediaRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * JSON payloads of the CastV2 namespaces we speak, and parsing of the statuses the receiver sends back.
 * Pure functions, so the message shapes are unit tested without a device.
 */
object CastV2Protocol {

    const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
    const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    const val NS_MEDIA = "urn:x-cast:com.google.cast.media"

    const val RECEIVER_ID = "receiver-0"

    /** Google's Default Media Receiver, the same app the Google Cast path launches. */
    const val DEFAULT_MEDIA_RECEIVER_APP_ID = "CC1AD845"

    val json = Json { ignoreUnknownKeys = true }

    fun connect(): String = buildJsonObject {
        put("type", "CONNECT")
        // Identifies us to the receiver like a generic sender; it shows no UI for it.
        putJsonObject("origin") {}
    }.toString()

    fun close(): String = buildJsonObject { put("type", "CLOSE") }.toString()

    fun ping(): String = buildJsonObject { put("type", "PING") }.toString()

    fun pong(): String = buildJsonObject { put("type", "PONG") }.toString()

    fun getReceiverStatus(requestId: Int): String = buildJsonObject {
        put("type", "GET_STATUS")
        put("requestId", requestId)
    }.toString()

    fun launch(requestId: Int, appId: String = DEFAULT_MEDIA_RECEIVER_APP_ID): String = buildJsonObject {
        put("type", "LAUNCH")
        put("requestId", requestId)
        put("appId", appId)
    }.toString()

    fun stopApp(requestId: Int, sessionId: String): String = buildJsonObject {
        put("type", "STOP")
        put("requestId", requestId)
        put("sessionId", sessionId)
    }.toString()

    fun setVolume(requestId: Int, level: Double): String = buildJsonObject {
        put("type", "SET_VOLUME")
        put("requestId", requestId)
        putJsonObject("volume") { put("level", level.coerceIn(0.0, 1.0)) }
    }.toString()

    fun getMediaStatus(requestId: Int): String = buildJsonObject {
        put("type", "GET_STATUS")
        put("requestId", requestId)
    }.toString()

    fun load(requestId: Int, sessionId: String, request: CastMediaRequest): String = buildJsonObject {
        put("type", "LOAD")
        put("requestId", requestId)
        put("sessionId", sessionId)
        put("autoplay", request.autoplay)
        put("currentTime", request.startPositionSeconds.coerceAtLeast(0.0))
        putJsonObject("media") {
            put("contentId", request.url)
            put("contentUrl", request.url)
            put("contentType", request.contentType)
            put("streamType", "BUFFERED")
            request.durationSeconds?.takeIf { it > 0 }?.let { put("duration", it) }
            putJsonObject("metadata") {
                // GenericMediaMetadata; MovieMediaMetadata (1) has no subtitle field.
                put("metadataType", 0)
                put("title", request.title)
                put("subtitle", request.subtitle)
                request.imageUrl?.let { url ->
                    putJsonArray("images") { add(buildJsonObject { put("url", url) }) }
                }
            }
            if (request.textTracks.isNotEmpty()) {
                putJsonArray("tracks") {
                    request.textTracks.forEachIndexed { index, track ->
                        add(
                            buildJsonObject {
                                put("trackId", trackId(index))
                                put("type", "TEXT")
                                put("subtype", "SUBTITLES")
                                put("trackContentId", track.url)
                                put("trackContentType", "text/vtt")
                                put("name", track.name)
                                track.language?.let { put("language", it) }
                            },
                        )
                    }
                }
            }
            request.textTrackStyle?.let { put("textTrackStyle", textTrackStyle(it)) }
        }
        request.activeTextTrackIndex
            ?.takeIf { it in request.textTracks.indices }
            ?.let { index -> putJsonArray("activeTrackIds") { add(JsonPrimitive(trackId(index))) } }
    }.toString()

    fun play(requestId: Int, mediaSessionId: Int): String = mediaCommand("PLAY", requestId, mediaSessionId)

    fun pause(requestId: Int, mediaSessionId: Int): String = mediaCommand("PAUSE", requestId, mediaSessionId)

    /** The Default Media Receiver plays between 0.5x and 2x. */
    fun setPlaybackRate(requestId: Int, mediaSessionId: Int, rate: Double): String = buildJsonObject {
        put("type", "SET_PLAYBACK_RATE")
        put("requestId", requestId)
        put("mediaSessionId", mediaSessionId)
        put("playbackRate", rate.coerceIn(MIN_PLAYBACK_RATE, MAX_PLAYBACK_RATE))
    }.toString()

    const val MIN_PLAYBACK_RATE = 0.5
    const val MAX_PLAYBACK_RATE = 2.0

    fun stopMedia(requestId: Int, mediaSessionId: Int): String = mediaCommand("STOP", requestId, mediaSessionId)

    fun seek(requestId: Int, mediaSessionId: Int, positionSeconds: Double): String = buildJsonObject {
        put("type", "SEEK")
        put("requestId", requestId)
        put("mediaSessionId", mediaSessionId)
        put("currentTime", positionSeconds.coerceAtLeast(0.0))
    }.toString()

    fun editTracks(
        requestId: Int,
        mediaSessionId: Int,
        style: CastMediaRequest.TextStyle,
        activeTrackIds: List<Int>,
    ): String = buildJsonObject {
        put("type", "EDIT_TRACKS_INFO")
        put("requestId", requestId)
        put("mediaSessionId", mediaSessionId)
        put("textTrackStyle", textTrackStyle(style))
        put("activeTrackIds", JsonArray(activeTrackIds.map(::JsonPrimitive)))
    }.toString()

    /** Track ids start at 1: some receivers treat 0 as "no track". */
    fun trackId(index: Int): Int = index + 1

    private fun mediaCommand(type: String, requestId: Int, mediaSessionId: Int): String = buildJsonObject {
        put("type", type)
        put("requestId", requestId)
        put("mediaSessionId", mediaSessionId)
    }.toString()

    fun textTrackStyle(style: CastMediaRequest.TextStyle): JsonObject = buildJsonObject {
        put("fontScale", style.fontScale)
        put("foregroundColor", cssColor(style.foregroundColor))
        put("backgroundColor", cssColor(style.backgroundColor))
        put("edgeType", style.edgeType.name)
        put("edgeColor", cssColor(style.edgeColor))
        put("fontGenericFamily", style.fontGenericFamily.name)
        put("windowType", "NONE")
    }

    /** Cast colors are `#RRGGBBAA`; Android colors are ARGB. */
    fun cssColor(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val rgb = argb and 0xFFFFFF
        return "#%06X%02X".format(rgb, a)
    }

    // region Incoming messages

    /** The `type` and `requestId` of any namespace payload. */
    data class Envelope(val type: String?, val requestId: Int?, val body: JsonObject)

    fun envelope(payload: String): Envelope {
        val body = json.parseToJsonElement(payload).jsonObject
        return Envelope(
            type = body["type"]?.jsonPrimitive?.contentOrNullSafe(),
            requestId = body["requestId"]?.jsonPrimitive?.intOrNull,
            body = body,
        )
    }

    data class RunningApp(
        val appId: String,
        val sessionId: String,
        val transportId: String,
        val displayName: String?,
    )

    data class ReceiverStatus(
        val applications: List<RunningApp>,
        val volumeLevel: Double?,
        val muted: Boolean?,
    ) {
        fun app(appId: String): RunningApp? = applications.firstOrNull { it.appId == appId }
    }

    /** Parses a `RECEIVER_STATUS` payload body. */
    fun receiverStatus(body: JsonObject): ReceiverStatus {
        val status = body["status"]?.jsonObject ?: return ReceiverStatus(emptyList(), null, null)
        val apps = status["applications"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
            val app = element as? JsonObject ?: return@mapNotNull null
            RunningApp(
                appId = app.string("appId") ?: return@mapNotNull null,
                sessionId = app.string("sessionId") ?: return@mapNotNull null,
                transportId = app.string("transportId") ?: return@mapNotNull null,
                displayName = app.string("displayName"),
            )
        }
        val volume = status["volume"] as? JsonObject
        return ReceiverStatus(
            applications = apps,
            volumeLevel = volume?.get("level")?.jsonPrimitive?.doubleOrNull,
            muted = volume?.get("muted")?.jsonPrimitive?.booleanOrNull,
        )
    }

    enum class PlayerState { IDLE, PLAYING, PAUSED, BUFFERING, LOADING, UNKNOWN }

    data class MediaStatus(
        val mediaSessionId: Int,
        val playerState: PlayerState,
        val currentTimeSeconds: Double,
        val durationSeconds: Double?,
        /** Set when [playerState] is IDLE: FINISHED, CANCELLED, INTERRUPTED or ERROR. */
        val idleReason: String?,
        val activeTrackIds: List<Int>,
        val title: String?,
        val subtitle: String?,
        val imageUrl: String?,
    )

    /**
     * Parses a `MEDIA_STATUS` payload body. It carries a list; an empty list means no media session
     * (nothing loaded, or the receiver dropped it).
     */
    fun mediaStatuses(body: JsonObject): List<MediaStatus> {
        return body["status"]?.jsonArrayOrNull().orEmpty().mapNotNull { element ->
            val status = element as? JsonObject ?: return@mapNotNull null
            val media = status["media"] as? JsonObject
            val metadata = media?.get("metadata") as? JsonObject
            MediaStatus(
                mediaSessionId = status["mediaSessionId"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null,
                playerState = status.string("playerState")
                    ?.let { state -> PlayerState.entries.firstOrNull { it.name == state } }
                    ?: PlayerState.UNKNOWN,
                currentTimeSeconds = status["currentTime"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                durationSeconds = media?.get("duration")?.jsonPrimitive?.doubleOrNull,
                idleReason = status.string("idleReason"),
                activeTrackIds = status["activeTrackIds"]?.jsonArrayOrNull().orEmpty()
                    .mapNotNull { it.jsonPrimitive.longOrNull?.toInt() },
                title = metadata?.string("title"),
                subtitle = metadata?.string("subtitle"),
                imageUrl = (metadata?.get("images")?.jsonArrayOrNull()?.firstOrNull() as? JsonObject)?.string("url"),
            )
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNullSafe()

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is JsonNull) null else content

    private fun JsonElement.jsonArrayOrNull(): JsonArray? = this as? JsonArray

    // endregion
}
