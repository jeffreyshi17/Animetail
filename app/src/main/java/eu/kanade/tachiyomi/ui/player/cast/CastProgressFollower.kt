package eu.kanade.tachiyomi.ui.player.cast

import eu.kanade.tachiyomi.ui.player.WatchProgressRecorder
import kotlin.math.abs

/**
 * What the receiver reports about the media it plays, the same for both backends.
 *
 * @param mediaSessionId identifies one load on the receiver; loading again gives a new one. Google
 *   Cast reports its queue item id instead.
 * @param queuedItems how many items the receiver's queue holds (Google Cast only, 0 or 1 otherwise).
 */
data class CastReceiverStatus(
    val mediaSessionId: Long,
    val state: State,
    val idleReason: IdleReason?,
    val positionSeconds: Double,
    val durationSeconds: Double?,
    val queuedItems: Int = 0,
) {
    enum class State { PLAYING, PAUSED, BUFFERING, IDLE, UNKNOWN }

    enum class IdleReason { FINISHED, CANCELLED, INTERRUPTED, ERROR }
}

/**
 * Decides, status by status, when the receiver's progress on one episode is written and when the
 * episode ended. Pure, so the rules are unit tested without a receiver.
 *
 * One follower lives for the whole cast session: [follow] switches it to another episode but keeps
 * the media sessions it already handled, so the finished status of an episode still reported after
 * loading the next one does not end the next one too.
 */
class CastProgressFollower(
    private val saveIntervalMs: Long = SAVE_INTERVAL_MS,
) {

    /**
     * @param saveAtSeconds the position to write as the episode's progress, or null to write nothing.
     * @param markSeen the episode just reached the seen threshold or its end.
     * @param finished the receiver played the episode to its end.
     */
    data class Decision(
        val saveAtSeconds: Double? = null,
        val durationSeconds: Double? = null,
        val markSeen: Boolean = false,
        val finished: Boolean = false,
    ) {
        companion object {
            val NONE = Decision()
        }
    }

    private var episodeId: Long? = null
    private var seenMarked = false
    private var lastSavedAtMs: Long? = null
    private var lastSavedSeconds = -1.0
    private var lastState: CastReceiverStatus.State? = null
    private var lastDurationSeconds: Double? = null
    private var durationSessionId: Long? = null

    /** Media sessions seen playing: only those can finish, not one joined already finished. */
    private val playedSessions = mutableSetOf<Long>()
    private val finishedSessions = mutableSetOf<Long>()

    /**
     * Follows [episodeId] from now on, as it is stored now: [alreadySeen] keeps an episode watched
     * before from being marked again. Calling it again for the same episode starts over from there.
     */
    fun follow(episodeId: Long, alreadySeen: Boolean) {
        this.episodeId = episodeId
        seenMarked = alreadySeen
        lastSavedAtMs = null
        lastSavedSeconds = -1.0
        lastState = null
        lastDurationSeconds = null
    }

    /**
     * @param seenThreshold the fraction of the episode that counts it as seen, e.g. 0.85.
     */
    fun onStatus(status: CastReceiverStatus, nowMs: Long, seenThreshold: Float): Decision {
        if (episodeId == null) return Decision.NONE
        // A Google Cast queue moves on by itself, and we cannot tell which episode each item is.
        if (status.queuedItems > 1) return Decision.NONE

        val previousState = lastState
        lastState = status.state
        // Later statuses may leave the duration out; keep the last one of the same media session.
        if (status.mediaSessionId != durationSessionId) lastDurationSeconds = null
        durationSessionId = status.mediaSessionId
        status.durationSeconds?.takeIf { it > 0 }?.let { lastDurationSeconds = it }
        val duration = lastDurationSeconds

        if (status.state == CastReceiverStatus.State.IDLE) {
            val endedHere = status.idleReason == CastReceiverStatus.IdleReason.FINISHED &&
                status.mediaSessionId in playedSessions &&
                finishedSessions.add(status.mediaSessionId)
            if (!endedHere) return Decision.NONE
            val markSeen = !seenMarked
            seenMarked = true
            return Decision(
                saveAtSeconds = duration,
                durationSeconds = duration,
                markSeen = markSeen,
                finished = true,
            )
        }

        if (status.state == CastReceiverStatus.State.PLAYING) playedSessions += status.mediaSessionId
        if (status.mediaSessionId in finishedSessions) return Decision.NONE
        val position = status.positionSeconds
        if (duration == null || position <= 0) return Decision.NONE

        val markSeen = !seenMarked && isSeen(position, duration, seenThreshold)
        if (markSeen) seenMarked = true
        val paused = status.state == CastReceiverStatus.State.PAUSED &&
            previousState != CastReceiverStatus.State.PAUSED
        val due = lastSavedAtMs?.let { nowMs - it >= saveIntervalMs } ?: true
        val moved = abs(position - lastSavedSeconds) >= 1
        if (!markSeen && !(moved && (paused || due))) return Decision.NONE

        lastSavedAtMs = nowMs
        lastSavedSeconds = position
        return Decision(saveAtSeconds = position, durationSeconds = duration, markSeen = markSeen)
    }

    private fun isSeen(positionSeconds: Double, durationSeconds: Double, threshold: Float): Boolean =
        WatchProgressRecorder.isSeen(
            positionMs = (positionSeconds * 1000).toLong(),
            durationMs = (durationSeconds * 1000).toLong(),
            threshold = threshold,
        )

    companion object {
        /** How often progress is written while the receiver plays. */
        const val SAVE_INTERVAL_MS = 10_000L
    }
}
