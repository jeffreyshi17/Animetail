package eu.kanade.tachiyomi.ui.player.cast

import eu.kanade.tachiyomi.ui.player.cast.CastNotificationState.Playback
import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol.MediaStatus
import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol.PlayerState
import eu.kanade.tachiyomi.ui.player.cast.direct.DirectCastDevice
import eu.kanade.tachiyomi.ui.player.cast.direct.DirectCastState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class CastNotificationStateTest {

    data class VisibilityCase(
        val description: String,
        val state: DirectCastState?,
        val shown: Boolean,
    ) {
        override fun toString() = description
    }

    @ParameterizedTest
    @MethodSource("visibilityCases")
    fun `the notification exists only while a session is connected`(case: VisibilityCase) {
        val content = CastNotificationState.from(case.state, device, nowMs = 0)

        assertEquals(case.shown, content != null, case.description)
    }

    data class PlaybackCase(
        val description: String,
        val media: MediaStatus?,
        val expected: Playback,
        val canToggle: Boolean,
    ) {
        override fun toString() = description
    }

    @ParameterizedTest
    @MethodSource("playbackCases")
    fun `the receiver's player state picks the controls`(case: PlaybackCase) {
        val content = CastNotificationState.from(connected.copy(media = case.media), device, nowMs = 0)!!

        assertEquals(case.expected, content.playback, case.description)
        assertEquals(case.canToggle, content.canTogglePlayback, case.description)
    }

    @Test
    fun `shows the receiver's metadata and the device it casts to`() {
        val content = CastNotificationState.from(
            connected.copy(media = media(title = "Frieren", subtitle = "Episode 3"), volumeLevel = 0.4),
            device,
            nowMs = 0,
        )!!

        assertEquals("Frieren", content.title)
        assertEquals("Episode 3", content.subtitle)
        assertEquals("Living Room TV", content.deviceName)
        assertEquals("https://img/cover.jpg", content.imageUrl)
        assertEquals(1440.0, content.durationSeconds)
        assertEquals(0.4, content.volumeLevel)
    }

    @Test
    fun `blank metadata falls back instead of showing an empty line`() {
        val content = CastNotificationState.from(
            connected.copy(media = media(title = " ", subtitle = "", imageUrl = "")),
            device.copy(name = ""),
            nowMs = 0,
        )!!

        assertEquals(null, content.title)
        assertEquals(null, content.subtitle)
        assertEquals(null, content.imageUrl)
        assertEquals(null, content.deviceName)
    }

    data class DetailCase(
        val description: String,
        val subtitle: String?,
        val expected: String,
    ) {
        override fun toString() = description
    }

    @ParameterizedTest
    @MethodSource("detailCases")
    fun `the text line names the device, then the episode`(case: DetailCase) {
        val content = CastNotificationState.from(connected.copy(media = media(subtitle = case.subtitle)), device, 0)!!

        assertEquals(case.expected, content.detailLine("Casting to Living Room TV"), case.description)
    }

    @Test
    fun `the position follows the receiver while playing`() {
        val state = connected.copy(media = media(currentTime = 100.0), mediaUpdatedAtMs = 1_000)

        assertEquals(105.0, CastNotificationState.from(state, device, nowMs = 6_000)!!.positionSeconds)
    }

    @Test
    fun `position and volume ticks do not repost the notification`() {
        val first = CastNotificationState.from(
            connected.copy(media = media(currentTime = 10.0), volumeLevel = 0.2),
            device,
            nowMs = 0,
        )!!
        val ticked = CastNotificationState.from(
            connected.copy(media = media(currentTime = 12.0), volumeLevel = 0.3),
            device,
            nowMs = 0,
        )!!
        val paused = CastNotificationState.from(
            connected.copy(media = media(currentTime = 12.0, playerState = PlayerState.PAUSED)),
            device,
            nowMs = 0,
        )!!

        assertEquals(first.notificationKey, ticked.notificationKey)
        assertNotEquals(first.notificationKey, paused.notificationKey)
    }

    companion object {
        private val device = DirectCastDevice(id = "tv", name = "Living Room TV", host = "192.168.0.20")

        private val connected = DirectCastState(
            phase = DirectCastState.Phase.CONNECTED,
            appSessionId = "app",
            transportId = "transport",
        )

        private fun media(
            playerState: PlayerState = PlayerState.PLAYING,
            currentTime: Double = 0.0,
            title: String? = "Frieren",
            subtitle: String? = "Episode 3",
            imageUrl: String? = "https://img/cover.jpg",
        ) = MediaStatus(
            mediaSessionId = 1,
            playerState = playerState,
            currentTimeSeconds = currentTime,
            durationSeconds = 1440.0,
            idleReason = null,
            activeTrackIds = emptyList(),
            title = title,
            subtitle = subtitle,
            imageUrl = imageUrl,
        )

        @JvmStatic
        fun detailCases() = listOf(
            DetailCase("with an episode name", "Episode 3", "Casting to Living Room TV - Episode 3"),
            DetailCase("without one there is no dangling separator", null, "Casting to Living Room TV"),
        )

        @JvmStatic
        fun visibilityCases() = listOf(
            VisibilityCase("no session at all, e.g. after the receiver connection dropped", null, shown = false),
            VisibilityCase(
                "still launching the receiver app: nothing to control yet",
                DirectCastState(phase = DirectCastState.Phase.CONNECTING),
                shown = false,
            ),
            VisibilityCase(
                "the session ended (stop button, TV remote) but the controller has not cleared it yet",
                connected.ended("The receiver closed the cast session"),
                shown = false,
            ),
            VisibilityCase("connected with nothing loaded yet", connected, shown = true),
        )

        @JvmStatic
        fun playbackCases() = listOf(
            PlaybackCase("playing shows pause", media(PlayerState.PLAYING), Playback.PLAYING, canToggle = true),
            PlaybackCase("paused shows play", media(PlayerState.PAUSED), Playback.PAUSED, canToggle = true),
            PlaybackCase(
                "buffering keeps play/pause so the user can pause a stalled stream",
                media(PlayerState.BUFFERING),
                Playback.BUFFERING,
                canToggle = true,
            ),
            PlaybackCase(
                "loading counts as buffering",
                media(PlayerState.LOADING),
                Playback.BUFFERING,
                canToggle = true,
            ),
            PlaybackCase(
                "an idle receiver (finished or failed episode) rejects play, so only stop is offered",
                media(PlayerState.IDLE),
                Playback.IDLE,
                canToggle = false,
            ),
            PlaybackCase("nothing loaded offers only stop", null, Playback.IDLE, canToggle = false),
        )
    }
}
