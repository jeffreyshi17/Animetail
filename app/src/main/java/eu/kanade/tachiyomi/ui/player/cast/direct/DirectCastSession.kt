package eu.kanade.tachiyomi.ui.player.cast.direct

import eu.kanade.tachiyomi.ui.player.cast.CastMediaRequest
import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol.NS_CONNECTION
import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol.NS_MEDIA
import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol.NS_RECEIVER
import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol.RECEIVER_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.net.InetAddress

/**
 * A Google-Play-services-free cast session: launches (or joins) the Default Media Receiver on a
 * Chromecast / Google TV over the open CastV2 protocol and drives its media channel.
 */
class DirectCastSession private constructor(
    private val channel: CastV2Channel,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
) {
    private val _state = MutableStateFlow(DirectCastState())
    val state: StateFlow<DirectCastState> = _state.asStateFlow()

    /** The phone's address as seen from the receiver, for URLs the receiver must fetch from us. */
    val localAddress: InetAddress get() = channel.localAddress

    private var statusPollJob: Job? = null

    private val transportId: String
        get() = _state.value.transportId ?: throw IllegalStateException("Cast receiver app not launched")

    private fun mediaSessionId(): Int =
        _state.value.media?.mediaSessionId ?: throw IllegalStateException("Nothing is loaded on the cast receiver")

    private fun observe() {
        channel.incoming.onEach { message ->
            val payload = message.payloadUtf8 ?: return@onEach
            val envelope = runCatching { CastV2Protocol.envelope(payload) }.getOrNull() ?: return@onEach
            when (message.namespace) {
                NS_RECEIVER -> if (envelope.type == "RECEIVER_STATUS") {
                    _state.update { it.onReceiverStatus(CastV2Protocol.receiverStatus(envelope.body)) }
                }

                NS_MEDIA -> if (envelope.type == "MEDIA_STATUS") {
                    _state.update { it.onMediaStatus(CastV2Protocol.mediaStatuses(envelope.body), clock()) }
                }

                NS_CONNECTION -> if (envelope.type == "CLOSE" && message.sourceId == _state.value.transportId) {
                    _state.update { it.ended("The receiver closed the cast session") }
                }
            }
        }.launchIn(scope)

        channel.isOpen.filter { !it }.onEach {
            _state.update { it.ended(channel.closeCause?.message ?: "Connection to the receiver lost") }
        }.launchIn(scope)
    }

    private suspend fun start() {
        observe()
        channel.send(RECEIVER_ID, NS_CONNECTION, CastV2Protocol.connect())

        val status = channel.request(RECEIVER_ID, NS_RECEIVER) { CastV2Protocol.getReceiverStatus(it) }
        val running = CastV2Protocol.receiverStatus(status.body)
        _state.update { it.copy(volumeLevel = running.volumeLevel) }

        val app = running.app(CastV2Protocol.DEFAULT_MEDIA_RECEIVER_APP_ID) ?: launchApp()
        channel.send(app.transportId, NS_CONNECTION, CastV2Protocol.connect())
        _state.update { it.onAppJoined(app) }

        // Pick up media already playing when joining an existing session.
        val media = channel.request(app.transportId, NS_MEDIA) { CastV2Protocol.getMediaStatus(it) }
        if (media.type == "MEDIA_STATUS") {
            _state.update { it.onMediaStatus(CastV2Protocol.mediaStatuses(media.body), clock()) }
        }
        startStatusPolling()
    }

    private suspend fun launchApp(): CastV2Protocol.RunningApp {
        val reply = channel.request(RECEIVER_ID, NS_RECEIVER, LAUNCH_TIMEOUT_MS) { CastV2Protocol.launch(it) }
        if (reply.type == "LAUNCH_ERROR") {
            throw IOException("Receiver refused to launch the media app: ${reply.body["reason"]}")
        }
        CastV2Protocol.receiverStatus(reply.body).app(CastV2Protocol.DEFAULT_MEDIA_RECEIVER_APP_ID)?.let { return it }

        // Some firmwares answer LAUNCH before the app is up; wait for the status that lists it.
        return withTimeout(LAUNCH_TIMEOUT_MS) {
            var app: CastV2Protocol.RunningApp? = null
            while (app == null) {
                delay(500)
                val status = channel.request(RECEIVER_ID, NS_RECEIVER) { CastV2Protocol.getReceiverStatus(it) }
                app = CastV2Protocol.receiverStatus(status.body).app(CastV2Protocol.DEFAULT_MEDIA_RECEIVER_APP_ID)
            }
            app
        }
    }

    /**
     * The receiver only pushes statuses on changes; polling keeps the position estimate honest and
     * notices a dropped session within a few seconds.
     */
    private fun startStatusPolling() {
        statusPollJob?.cancel()
        statusPollJob = scope.launch {
            while (_state.value.phase != DirectCastState.Phase.ENDED) {
                delay(STATUS_POLL_INTERVAL_MS)
                val transport = _state.value.transportId ?: continue
                runCatching { channel.request(transport, NS_MEDIA) { CastV2Protocol.getMediaStatus(it) } }
            }
        }
    }

    /** Loads [request], replacing whatever plays. Throws with the receiver's reason when it refuses. */
    suspend fun load(request: CastMediaRequest) {
        val transport = transportId
        val sessionId = _state.value.appSessionId!!
        val reply = channel.request(transport, NS_MEDIA, LOAD_TIMEOUT_MS) {
            CastV2Protocol.load(it, sessionId, request)
        }
        when (reply.type) {
            "MEDIA_STATUS" -> _state.update { it.onMediaStatus(CastV2Protocol.mediaStatuses(reply.body), clock()) }

            else -> throw IOException(
                "Cast receiver rejected the video (${reply.type}${reply.body["reason"]?.let { ": $it" } ?: ""})",
            )
        }
    }

    suspend fun play() = mediaRequest { CastV2Protocol.play(it, mediaSessionId()) }

    suspend fun pause() = mediaRequest { CastV2Protocol.pause(it, mediaSessionId()) }

    suspend fun seek(positionSeconds: Double) = mediaRequest {
        CastV2Protocol.seek(it, mediaSessionId(), positionSeconds)
    }

    suspend fun stopMedia() = mediaRequest { CastV2Protocol.stopMedia(it, mediaSessionId()) }

    suspend fun setTextTracks(style: CastMediaRequest.TextStyle, activeTrackIds: List<Int>) =
        mediaRequest { CastV2Protocol.editTracks(it, mediaSessionId(), style, activeTrackIds) }

    suspend fun setVolume(level: Double) {
        val reply = channel.request(RECEIVER_ID, NS_RECEIVER) { CastV2Protocol.setVolume(it, level) }
        if (reply.type == "RECEIVER_STATUS") {
            _state.update { it.onReceiverStatus(CastV2Protocol.receiverStatus(reply.body)) }
        }
    }

    private suspend fun mediaRequest(build: (Int) -> String) {
        val reply = channel.request(transportId, NS_MEDIA, build = build)
        if (reply.type == "MEDIA_STATUS") {
            _state.update { it.onMediaStatus(CastV2Protocol.mediaStatuses(reply.body), clock()) }
        } else if (reply.type == "INVALID_REQUEST") {
            throw IOException("Cast receiver rejected the command: ${reply.body["reason"]}")
        }
    }

    /** Ends the session: stops the receiver app (the TV returns to its idle screen) and disconnects. */
    suspend fun stop() {
        val sessionId = _state.value.appSessionId
        if (sessionId != null && channel.isOpen.value) {
            runCatching {
                channel.request(RECEIVER_ID, NS_RECEIVER) { CastV2Protocol.stopApp(it, sessionId) }
            }
        }
        disconnect("Stopped casting")
    }

    /** Leaves the receiver app running and closes our connection. */
    suspend fun disconnect(reason: String = "Disconnected") {
        _state.update { it.ended(reason) }
        // Say goodbye even when the caller is being cancelled, so the receiver drops our connection now.
        withContext(NonCancellable) {
            _state.value.transportId?.let { runCatching { channel.send(it, NS_CONNECTION, CastV2Protocol.close()) } }
            runCatching { channel.send(RECEIVER_ID, NS_CONNECTION, CastV2Protocol.close()) }
        }
        channel.close()
        scope.cancel()
    }

    companion object {
        private const val LAUNCH_TIMEOUT_MS = 20_000L
        private const val LOAD_TIMEOUT_MS = 30_000L
        private const val STATUS_POLL_INTERVAL_MS = 2_000L

        /** Connects to the receiver at [host] and launches or joins the Default Media Receiver. */
        suspend fun connect(
            host: String,
            port: Int = CastV2Channel.DEFAULT_PORT,
            clock: () -> Long = System::currentTimeMillis,
        ): DirectCastSession {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val channel = try {
                CastV2Channel.open(host, port, scope)
            } catch (e: Throwable) {
                scope.cancel()
                throw e
            }
            val session = DirectCastSession(channel, scope, clock)
            try {
                session.start()
            } catch (e: Throwable) {
                session.disconnect("Connection failed")
                throw e
            }
            return session
        }
    }

    /** Suspends until the session ends, returning the final state. */
    suspend fun awaitEnd(): DirectCastState = state.first { it.phase == DirectCastState.Phase.ENDED }
}
