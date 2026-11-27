package eu.kanade.tachiyomi.ui.player.cast

import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol.PlayerState
import eu.kanade.tachiyomi.ui.player.cast.direct.DirectCastDevice
import eu.kanade.tachiyomi.ui.player.cast.direct.DirectCastState

/**
 * What the casting notification and its media session show for a direct cast session. Pure so the
 * mapping from the receiver's state is unit tested without a device.
 */
data class CastNotificationState(
    val title: String?,
    val subtitle: String?,
    val deviceName: String?,
    val playback: Playback,
    val positionSeconds: Double,
    val durationSeconds: Double?,
    val imageUrl: String?,
    /** The receiver's volume, 0 to 1, once it reported one. */
    val volumeLevel: Double?,
) {
    enum class Playback { PLAYING, PAUSED, BUFFERING, IDLE }

    /** Play/pause needs loaded media; an idle receiver rejects both. */
    val canTogglePlayback: Boolean get() = playback != Playback.IDLE

    /** The notification's text line: where it casts, then the episode, e.g. "Casting to TV - Episode 3". */
    fun detailLine(castingLabel: String): String = listOfNotNull(castingLabel, subtitle).joinToString(" - ")

    /**
     * The fields the notification itself shows. The position and volume change with every status
     * poll but only feed the media session, so they must not repost the notification.
     */
    val notificationKey: CastNotificationState get() = copy(positionSeconds = 0.0, volumeLevel = null)

    companion object {
        /** Null while no session is connected, which is when the notification must go away. */
        fun from(state: DirectCastState?, device: DirectCastDevice?, nowMs: Long): CastNotificationState? {
            if (state?.phase != DirectCastState.Phase.CONNECTED) return null
            val media = state.media
            return CastNotificationState(
                title = media?.title?.takeIf { it.isNotBlank() },
                subtitle = media?.subtitle?.takeIf { it.isNotBlank() },
                deviceName = device?.name?.takeIf { it.isNotBlank() },
                playback = when (media?.playerState) {
                    PlayerState.PLAYING -> Playback.PLAYING
                    PlayerState.PAUSED -> Playback.PAUSED
                    PlayerState.BUFFERING, PlayerState.LOADING -> Playback.BUFFERING
                    PlayerState.IDLE, PlayerState.UNKNOWN, null -> Playback.IDLE
                },
                positionSeconds = state.estimatedPositionSeconds(nowMs),
                durationSeconds = media?.durationSeconds,
                imageUrl = media?.imageUrl?.takeIf { it.isNotBlank() },
                volumeLevel = state.volumeLevel,
            )
        }
    }
}
