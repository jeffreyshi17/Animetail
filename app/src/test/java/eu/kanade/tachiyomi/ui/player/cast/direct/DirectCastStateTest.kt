package eu.kanade.tachiyomi.ui.player.cast.direct

import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol.MediaStatus
import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol.PlayerState
import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol.ReceiverStatus
import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol.RunningApp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class DirectCastStateTest {

    private val app = RunningApp(appId = "CC1AD845", sessionId = "sess-1", transportId = "web-5", displayName = null)
    private val joined = DirectCastState().onAppJoined(app)

    @Test
    fun `later media statuses that omit the media block keep duration and title`() {
        val first = joined.onMediaStatus(listOf(status(currentTime = 0.0, duration = 600.0, title = "Ep 1")), nowMs = 0)
        val next = first.onMediaStatus(listOf(status(currentTime = 30.0, duration = null, title = null)), nowMs = 1_000)

        assertEquals(600.0, next.media?.durationSeconds)
        assertEquals("Ep 1", next.media?.title)
        assertEquals(30.0, next.media?.currentTimeSeconds)
    }

    @Test
    fun `a new media session does not inherit the previous media's duration`() {
        val first = joined.onMediaStatus(listOf(status(sessionId = 1, duration = 600.0)), nowMs = 0)
        val next = first.onMediaStatus(listOf(status(sessionId = 2, duration = null)), nowMs = 0)

        assertNull(next.media?.durationSeconds)
    }

    @Test
    fun `an empty media status list means nothing is loaded any more`() {
        val playing = joined.onMediaStatus(listOf(status()), nowMs = 0)

        assertNull(playing.onMediaStatus(emptyList(), nowMs = 0).media)
    }

    data class PositionCase(
        val description: String,
        val playerState: PlayerState,
        val currentTime: Double,
        val duration: Double?,
        val elapsedMs: Long,
        val expected: Double,
    ) {
        override fun toString() = description
    }

    @ParameterizedTest
    @MethodSource("positionCases")
    fun `position is extrapolated only while playing`(case: PositionCase) {
        val state = joined.onMediaStatus(
            listOf(status(playerState = case.playerState, currentTime = case.currentTime, duration = case.duration)),
            nowMs = 10_000,
        )

        assertEquals(case.expected, state.estimatedPositionSeconds(nowMs = 10_000 + case.elapsedMs), 1e-9)
    }

    @Test
    fun `the session ends when the receiver no longer runs our app`() {
        val stoppedFromTv = joined.onReceiverStatus(
            ReceiverStatus(applications = emptyList(), volumeLevel = 0.5, muted = false),
        )
        val otherApp = joined.onReceiverStatus(
            ReceiverStatus(listOf(app.copy(appId = "YouTube", sessionId = "sess-2")), volumeLevel = null, muted = null),
        )

        assertEquals(DirectCastState.Phase.ENDED, stoppedFromTv.phase)
        assertEquals(0.5, stoppedFromTv.volumeLevel)
        assertEquals(DirectCastState.Phase.ENDED, otherApp.phase)
    }

    @Test
    fun `receiver statuses before we joined an app do not end the session`() {
        val state = DirectCastState().onReceiverStatus(ReceiverStatus(emptyList(), volumeLevel = 1.0, muted = false))

        assertEquals(DirectCastState.Phase.CONNECTING, state.phase)
    }

    @Test
    fun `the first end reason sticks`() {
        val ended = joined.ended("Stopped casting").ended("Connection to the receiver lost")

        assertEquals("Stopped casting", ended.endReason)
    }

    private fun status(
        sessionId: Int = 1,
        playerState: PlayerState = PlayerState.PLAYING,
        currentTime: Double = 0.0,
        duration: Double? = 600.0,
        title: String? = "title",
    ) = MediaStatus(
        mediaSessionId = sessionId,
        playerState = playerState,
        currentTimeSeconds = currentTime,
        durationSeconds = duration,
        idleReason = null,
        activeTrackIds = emptyList(),
        title = title,
        subtitle = null,
        imageUrl = null,
    )

    companion object {
        @JvmStatic
        fun positionCases() = listOf(
            PositionCase("playing advances with wall clock", PlayerState.PLAYING, 100.0, 600.0, 2_500, 102.5),
            PositionCase("paused stays put", PlayerState.PAUSED, 100.0, 600.0, 60_000, 100.0),
            PositionCase("buffering stays put", PlayerState.BUFFERING, 100.0, 600.0, 5_000, 100.0),
            PositionCase("never runs past the end", PlayerState.PLAYING, 599.0, 600.0, 10_000, 600.0),
            PositionCase("unknown duration is not clamped", PlayerState.PLAYING, 10.0, null, 1_000, 11.0),
        )
    }
}
