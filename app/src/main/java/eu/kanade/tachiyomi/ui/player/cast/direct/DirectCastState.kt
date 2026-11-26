package eu.kanade.tachiyomi.ui.player.cast.direct

import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol.MediaStatus
import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol.PlayerState
import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol.ReceiverStatus

/**
 * What the phone knows about one direct cast session, folded from the receiver's status messages.
 * Immutable and pure so the state handling is unit tested without a device.
 */
data class DirectCastState(
    val phase: Phase = Phase.CONNECTING,
    /** The Default Media Receiver session we launched or joined. */
    val appSessionId: String? = null,
    val transportId: String? = null,
    val media: MediaStatus? = null,
    /** Wall-clock time [media] was last updated, to extrapolate the position while playing. */
    val mediaUpdatedAtMs: Long = 0,
    val volumeLevel: Double? = null,
    val endReason: String? = null,
) {
    enum class Phase { CONNECTING, CONNECTED, ENDED }

    val isPlaying: Boolean get() = media?.playerState == PlayerState.PLAYING

    /** The receiver's position now, assuming it kept playing at normal speed since its last status. */
    fun estimatedPositionSeconds(nowMs: Long): Double {
        val media = media ?: return 0.0
        val elapsed = if (media.playerState == PlayerState.PLAYING) {
            (nowMs - mediaUpdatedAtMs).coerceAtLeast(0) / 1000.0
        } else {
            0.0
        }
        val position = media.currentTimeSeconds + elapsed
        return media.durationSeconds?.let { position.coerceAtMost(it) } ?: position
    }

    fun onAppJoined(app: CastV2Protocol.RunningApp): DirectCastState =
        copy(phase = Phase.CONNECTED, appSessionId = app.sessionId, transportId = app.transportId)

    fun onReceiverStatus(status: ReceiverStatus): DirectCastState {
        val withVolume = copy(volumeLevel = status.volumeLevel ?: volumeLevel)
        val sessionId = appSessionId ?: return withVolume
        // Our app is gone: stopped from the TV remote, the Google Home app, or another sender took over.
        return if (status.applications.none { it.sessionId == sessionId }) {
            withVolume.ended("The receiver closed the cast session")
        } else {
            withVolume
        }
    }

    /**
     * Folds a `MEDIA_STATUS`. Later statuses omit the unchanged `media` block, so duration and
     * metadata carry over while the media session stays the same.
     */
    fun onMediaStatus(statuses: List<MediaStatus>, nowMs: Long): DirectCastState {
        val next = statuses.firstOrNull { it.mediaSessionId == media?.mediaSessionId }
            ?: statuses.lastOrNull()
            ?: return copy(media = null, mediaUpdatedAtMs = nowMs)
        val previous = media?.takeIf { it.mediaSessionId == next.mediaSessionId }
        val merged = if (previous == null) {
            next
        } else {
            next.copy(
                durationSeconds = next.durationSeconds ?: previous.durationSeconds,
                title = next.title ?: previous.title,
                subtitle = next.subtitle ?: previous.subtitle,
                imageUrl = next.imageUrl ?: previous.imageUrl,
            )
        }
        return copy(media = merged, mediaUpdatedAtMs = nowMs)
    }

    fun ended(reason: String): DirectCastState =
        if (phase == Phase.ENDED) this else copy(phase = Phase.ENDED, endReason = reason)
}
