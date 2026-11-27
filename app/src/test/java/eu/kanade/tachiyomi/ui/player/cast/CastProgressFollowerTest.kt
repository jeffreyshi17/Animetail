package eu.kanade.tachiyomi.ui.player.cast

import eu.kanade.tachiyomi.ui.player.cast.CastProgressFollower.Decision
import eu.kanade.tachiyomi.ui.player.cast.CastReceiverStatus.IdleReason
import eu.kanade.tachiyomi.ui.player.cast.CastReceiverStatus.State
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class CastProgressFollowerTest {

    /** A receiver status [atMs] after following started; [episodeId] switches the followed episode first. */
    data class Step(val atMs: Long, val status: CastReceiverStatus, val episodeId: Long? = null)

    data class Case(
        val description: String,
        val steps: List<Step>,
        /** One per step. */
        val expected: List<Decision>,
        val alreadySeen: Boolean = false,
    ) {
        override fun toString() = description
    }

    @ParameterizedTest
    @MethodSource("cases")
    fun `receiver statuses become progress writes, seen marks and episode ends`(case: Case) {
        val follower = CastProgressFollower(saveIntervalMs = 10_000)
        follower.follow(episodeId = 1, alreadySeen = case.alreadySeen)

        val decisions = case.steps.map { step ->
            step.episodeId?.let { follower.follow(it, alreadySeen = false) }
            follower.onStatus(step.status, step.atMs, seenThreshold = 0.85f)
        }

        assertEquals(case.expected, decisions, case.description)
    }

    companion object {
        private const val DURATION = 1440.0

        private fun status(
            state: State,
            position: Double,
            session: Long = 1,
            idleReason: IdleReason? = null,
            duration: Double? = DURATION,
            queued: Int = 1,
        ) = CastReceiverStatus(session, state, idleReason, position, duration, queued)

        private fun playing(position: Double, session: Long = 1, duration: Double? = DURATION) =
            status(State.PLAYING, position, session, duration = duration)

        private fun finished(session: Long = 1) = status(State.IDLE, 0.0, session, IdleReason.FINISHED, duration = null)

        private fun save(position: Double, duration: Double? = DURATION, markSeen: Boolean = false) =
            Decision(saveAtSeconds = position, durationSeconds = duration, markSeen = markSeen)

        private val END =
            Decision(saveAtSeconds = DURATION, durationSeconds = DURATION, markSeen = true, finished = true)

        @JvmStatic
        fun cases() = listOf(
            Case(
                "progress is written every save interval while playing, not on every status",
                steps = listOf(Step(0, playing(60.0)), Step(5_000, playing(65.0)), Step(10_000, playing(70.0))),
                expected = listOf(save(60.0), Decision.NONE, save(70.0)),
            ),
            Case(
                "pausing on the TV writes the position at once, so leaving the app keeps it",
                steps = listOf(Step(0, playing(60.0)), Step(3_000, status(State.PAUSED, 63.0))),
                expected = listOf(save(60.0), save(63.0)),
            ),
            Case(
                "crossing the seen threshold marks the episode seen at once, and only once",
                steps = listOf(Step(0, playing(1200.0)), Step(2_000, playing(1225.0)), Step(4_000, playing(1227.0))),
                expected = listOf(save(1200.0), save(1225.0, markSeen = true), Decision.NONE),
            ),
            Case(
                "an episode already seen is not marked again, but its position is still kept",
                steps = listOf(Step(0, playing(1300.0))),
                expected = listOf(save(1300.0)),
                alreadySeen = true,
            ),
            Case(
                "playing to the end finishes the episode, which marks it seen and saves its full length",
                steps = listOf(Step(0, playing(1430.0)), Step(10_000, finished())),
                expected = listOf(save(1430.0, markSeen = true), END.copy(markSeen = false)),
            ),
            Case(
                "the receiver keeps reporting the finished status; the episode ends once, not on every report",
                steps = listOf(Step(0, playing(100.0)), Step(1_000, finished()), Step(6_000, finished())),
                expected = listOf(save(100.0), END, Decision.NONE),
            ),
            Case(
                "the old episode's finished status, still reported after loading the next one, does not end the next",
                steps = listOf(
                    Step(0, playing(100.0)),
                    Step(1_000, finished()),
                    Step(2_000, finished(), episodeId = 2),
                    Step(3_000, playing(5.0, session = 2)),
                ),
                expected = listOf(save(100.0), END, Decision.NONE, save(5.0)),
            ),
            Case(
                "joining a session that had already finished before we watched it does not end anything",
                steps = listOf(Step(0, finished())),
                expected = listOf(Decision.NONE),
            ),
            Case(
                "stopping the cast (cancelled) is not the end of the episode",
                steps = listOf(
                    Step(0, playing(100.0)),
                    Step(1_000, status(State.IDLE, 0.0, idleReason = IdleReason.CANCELLED)),
                ),
                expected = listOf(save(100.0), Decision.NONE),
            ),
            Case(
                "without a known duration nothing can be judged, so nothing is written",
                steps = listOf(Step(0, playing(100.0, duration = null))),
                expected = listOf(Decision.NONE),
            ),
            Case(
                "later statuses that leave the duration out reuse the one reported earlier",
                steps = listOf(Step(0, playing(100.0)), Step(10_000, playing(110.0, duration = null))),
                expected = listOf(save(100.0), save(110.0)),
            ),
            Case(
                "a google cast queue advances by itself to items we cannot map to episodes, so it is left alone",
                steps = listOf(Step(0, status(State.PLAYING, 100.0, queued = 2))),
                expected = listOf(Decision.NONE),
            ),
        )
    }
}
