package eu.kanade.tachiyomi.ui.player.cast

import android.content.Context
import android.widget.Toast
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.database.models.anime.Episode
import eu.kanade.tachiyomi.ui.player.PlayerPlaylist
import eu.kanade.tachiyomi.ui.player.WatchProgressRecorder
import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol
import eu.kanade.tachiyomi.ui.player.cast.direct.DirectCastController
import eu.kanade.tachiyomi.ui.player.cast.direct.DirectCastState
import eu.kanade.tachiyomi.ui.player.settings.CastSubtitlePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withNonCancellableContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.i18n.tail.TLMR

/**
 * Follows the cast receiver for as long as the cast session lasts, not just while the player is
 * open: it saves the watch progress of the episode in [CastNowPlaying] and, when the receiver plays
 * it to the end, marks it seen and (with autoplay on) loads the next episode on the receiver.
 *
 * While the player shows the episode being cast, it already saves progress and loads the next
 * episode itself (which also sends it to the receiver), so this only tells it the episode ended.
 */
@Inject
@SingleIn(AppScope::class)
class CastPlaybackTracker(
    private val context: Context,
    private val nowPlaying: CastNowPlaying,
    private val directController: DirectCastController,
    private val recorder: WatchProgressRecorder,
    private val playlist: PlayerPlaylist,
    private val videoResolver: CastEpisodeVideoResolver,
    private val getAnime: GetAnime,
    private val playerPreferences: PlayerPreferences,
    private val preferenceStore: PreferenceStore,
) {

    /** The open player, which handles the episode it shows itself. */
    interface AttachedPlayer {
        /** The episode the player shows while it casts, or null when it is not casting. */
        val castingEpisodeId: Long?

        /** The receiver played the episode to its end; [advance] when autoplay should play the next. */
        fun onReceiverFinished(advance: Boolean)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val backend = MutableStateFlow<CastBackend?>(null)
    private val follower = CastProgressFollower()
    private val mediaBuilder by lazy { CastMediaBuilder(context) }

    /** Main thread only, like everything here. */
    private var attachedPlayer: AttachedPlayer? = null
    private var following: Following? = null

    /** The episode the follower follows on behalf of the open player. */
    private var followedByPlayer: Long? = null

    /** The episode being followed, as stored when following started or was last written. */
    private class Following(
        val nowPlaying: CastNowPlaying.Episode,
        val anime: Anime,
        val episode: Episode,
        val playlist: List<Episode>,
        val privacy: WatchProgressRecorder.Privacy,
    )

    init {
        @OptIn(ExperimentalCoroutinesApi::class)
        scope.launch {
            backend.filterNotNull()
                .flatMapLatest { statuses(it) }
                .collect { status ->
                    try {
                        onStatus(status)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // One bad status (e.g. the episode was deleted) must not stop following the next.
                        logcat(LogPriority.ERROR, e) { "Cast: could not follow the receiver's progress" }
                    }
                }
        }
    }

    /** Starts following receivers of [backend]; every screen that casts calls it with the backend it uses. */
    fun follow(backend: CastBackend) {
        this.backend.value = backend
    }

    fun attach(player: AttachedPlayer) {
        attachedPlayer = player
    }

    fun detach(player: AttachedPlayer) {
        if (attachedPlayer !== player) return
        attachedPlayer = null
        // The player saved its own copy of the episode; read it again before writing over it.
        following = null
    }

    private suspend fun onStatus(status: CastReceiverStatus) {
        val episode = nowPlaying.episode.value ?: return
        val player = attachedPlayer?.takeIf { it.castingEpisodeId == episode.episodeId }
        if (player != null) {
            // The player saves progress itself; only the end of the episode is news to it.
            if (followedByPlayer != episode.episodeId) {
                follower.follow(episode.episodeId, alreadySeen = true)
                followedByPlayer = episode.episodeId
                // Read the episode again once the player closes, since the player writes it until then.
                following = null
            }
            val decision = follower.onStatus(status, System.currentTimeMillis(), seenThreshold())
            if (decision.finished) player.onReceiverFinished(advance = autoplay())
            return
        }
        followedByPlayer = null

        val current = following?.takeIf { it.nowPlaying.episodeId == episode.episodeId }
            ?: load(episode)?.also {
                following = it
                follower.follow(episode.episodeId, alreadySeen = it.episode.seen)
            }
            ?: return

        val decision = follower.onStatus(status, System.currentTimeMillis(), seenThreshold())
        decision.saveAtSeconds?.let { saveProgress(current, it, decision.durationSeconds, decision.markSeen) }
        if (decision.finished && autoplay()) playNext(current)
    }

    private fun seenThreshold(): Float = playerPreferences.progressPreference().get()

    private fun autoplay(): Boolean = playerPreferences.autoplayEnabled().get()

    private suspend fun load(episode: CastNowPlaying.Episode): Following? = withIOContext {
        val anime = getAnime.await(episode.animeId) ?: return@withIOContext null
        val episodes = playlist.episodes(anime, episode.episodeId)
        val current = episodes.firstOrNull { it.id == episode.episodeId } ?: return@withIOContext null
        Following(episode, anime, current, episodes, recorder.privacyFor(anime))
    }

    private suspend fun saveProgress(
        current: Following,
        positionSeconds: Double,
        durationSeconds: Double?,
        markSeen: Boolean,
    ) = withNonCancellableContext {
        val episode = current.episode
        episode.last_second_seen = (positionSeconds * 1000).toLong()
        durationSeconds?.let { episode.total_seconds = (it * 1000).toLong() }
        try {
            withIOContext {
                if (markSeen) recorder.onEpisodeSeen(current.anime, episode, current.playlist, current.privacy)
                recorder.saveProgress(episode, current.privacy)
                recorder.saveHistory(episode.id!!, current.privacy)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Cast: could not save the progress of ${episode.name}" }
        }
    }

    /** Loads the episode after [current] on the receiver, as the player's autoplay would. */
    private suspend fun playNext(current: Following) {
        val next = PlayerPlaylist.next(current.playlist, current.episode.id!!) ?: return
        val backend = backend.value ?: return
        logcat(LogPriority.INFO) { "Cast: ${current.episode.name} finished, loading ${next.name} on the receiver" }
        try {
            val resolved = videoResolver.resolve(current.anime, next)
                ?: return showToast(context.stringResource(TLMR.strings.cast_next_episode_failed))
            val subtitleIndex = CastMediaBuilder.subtitleIndexNamed(resolved.video, current.nowPlaying.subtitleName)
            val textStyle = when (backend) {
                CastBackend.DIRECT -> CastSubtitlePreferences(preferenceStore).subtitleSettings().toCastTextStyle()
                else -> null
            }
            val lanHost = when (backend) {
                CastBackend.DIRECT -> directController.session.value?.localAddress?.hostAddress ?: return
                else -> LanAddress.current()?.address ?: "127.0.0.1"
            }
            val request = mediaBuilder.build(
                video = resolved.video,
                headers = CastMediaBuilder.sourceHeaders(resolved.video, resolved.source, resolved.isEpisodeOnline),
                metadata = CastMediaBuilder.Metadata(
                    title = current.anime.title,
                    subtitle = next.name,
                    imageUrl = current.anime.thumbnailUrl,
                    durationSeconds = next.total_seconds.takeIf { it > 0 }?.let { it / 1000.0 },
                ),
                activeSubtitleIndex = subtitleIndex,
                lanHost = lanHost,
                startPositionSeconds = WatchProgressRecorder.resumePositionMs(
                    episode = next,
                    preserveWatchingPosition = playerPreferences.preserveWatchingPosition().get(),
                ) / 1000.0,
                autoplay = true,
                textStyle = textStyle,
            )
            loadOnReceiver(backend, request)
            nowPlaying.set(
                CastNowPlaying.Episode(
                    animeId = current.anime.id,
                    episodeId = next.id!!,
                    subtitleName = subtitleIndex?.let { request.textTracks.getOrNull(it)?.name },
                ),
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Cast: could not load ${next.name} on the receiver" }
            showToast(context.stringResource(TLMR.strings.cast_next_episode_failed))
        }
    }

    private suspend fun loadOnReceiver(backend: CastBackend, request: CastMediaRequest) {
        when (backend) {
            CastBackend.DIRECT -> {
                val session = directController.session.value ?: error("The direct cast session ended")
                session.load(request)
                // Speed is not part of LOAD; keep the one the player is set to.
                val speed = playerPreferences.playerSpeed().get().toDouble()
                    .coerceIn(CastV2Protocol.MIN_PLAYBACK_RATE, CastV2Protocol.MAX_PLAYBACK_RATE)
                if (speed != 1.0) session.setPlaybackRate(speed)
            }

            else -> {
                val client = CastContext.getSharedInstance(context).sessionManager.currentCastSession
                    ?.remoteMediaClient
                    ?: error("The Google Cast session ended")
                client.load(request.toGmsLoadRequest())
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    // region Receiver statuses

    private fun statuses(backend: CastBackend): Flow<CastReceiverStatus> = when (backend) {
        CastBackend.DIRECT -> directStatuses()
        CastBackend.GOOGLE_CAST -> gmsStatuses()
        CastBackend.AUTO -> flowOf()
    }

    /**
     * The direct session only reports changes, so between them the position is extrapolated and
     * re-emitted every few seconds.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun directStatuses(): Flow<CastReceiverStatus> = directController.state.flatMapLatest { state ->
        val media = state?.takeIf { it.phase == DirectCastState.Phase.CONNECTED }?.media
        if (media == null) {
            flowOf()
        } else {
            flow {
                while (true) {
                    emit(
                        CastReceiverStatus(
                            mediaSessionId = media.mediaSessionId.toLong(),
                            state = when (media.playerState) {
                                CastV2Protocol.PlayerState.PLAYING -> CastReceiverStatus.State.PLAYING
                                CastV2Protocol.PlayerState.PAUSED -> CastReceiverStatus.State.PAUSED
                                CastV2Protocol.PlayerState.BUFFERING,
                                CastV2Protocol.PlayerState.LOADING,
                                -> CastReceiverStatus.State.BUFFERING
                                CastV2Protocol.PlayerState.IDLE -> CastReceiverStatus.State.IDLE
                                CastV2Protocol.PlayerState.UNKNOWN -> CastReceiverStatus.State.UNKNOWN
                            },
                            idleReason = media.idleReason?.let { reason ->
                                CastReceiverStatus.IdleReason.entries.firstOrNull { it.name == reason }
                            },
                            positionSeconds = state.estimatedPositionSeconds(System.currentTimeMillis()),
                            durationSeconds = media.durationSeconds,
                        ),
                    )
                    delay(STATUS_INTERVAL_MS)
                }
            }
        }
    }

    private fun gmsStatuses(): Flow<CastReceiverStatus> = callbackFlow {
        val sessionManager = try {
            CastContext.getSharedInstance(context).sessionManager
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Cast: Google Cast is unavailable, not following its receiver" }
            close()
            return@callbackFlow
        }
        var client: RemoteMediaClient? = null
        // The SDK does not expose the media session id; the queue item id changes with every load.
        // A finished status may clear it, so it then still belongs to the item that played last.
        var lastItemId = 0
        fun emitStatus() {
            val itemId = client?.mediaStatus?.currentItemId?.takeIf { it != 0 } ?: lastItemId
            lastItemId = itemId
            client?.toReceiverStatus(itemId)?.let { trySend(it) }
        }
        val callback = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() = emitStatus()
        }
        val progressListener = RemoteMediaClient.ProgressListener { _, _ -> emitStatus() }
        fun attach(session: CastSession?) {
            client?.unregisterCallback(callback)
            client?.removeProgressListener(progressListener)
            client = session?.remoteMediaClient?.also {
                it.registerCallback(callback)
                it.addProgressListener(progressListener, STATUS_INTERVAL_MS)
            }
            emitStatus()
        }
        val listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, sessionId: String) = attach(session)
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = attach(session)
            override fun onSessionEnded(session: CastSession, error: Int) = attach(null)
            override fun onSessionStarting(session: CastSession) {}
            override fun onSessionStartFailed(session: CastSession, error: Int) {}
            override fun onSessionEnding(session: CastSession) {}
            override fun onSessionResuming(session: CastSession, sessionId: String) {}
            override fun onSessionResumeFailed(session: CastSession, error: Int) {}
            override fun onSessionSuspended(session: CastSession, reason: Int) {}
        }
        sessionManager.addSessionManagerListener(listener, CastSession::class.java)
        attach(sessionManager.currentCastSession)
        awaitClose {
            sessionManager.removeSessionManagerListener(listener, CastSession::class.java)
            attach(null)
        }
    }

    private fun RemoteMediaClient.toReceiverStatus(itemId: Int): CastReceiverStatus? {
        val status = mediaStatus ?: return null
        return CastReceiverStatus(
            mediaSessionId = itemId.toLong(),
            state = when (status.playerState) {
                MediaStatus.PLAYER_STATE_PLAYING -> CastReceiverStatus.State.PLAYING
                MediaStatus.PLAYER_STATE_PAUSED -> CastReceiverStatus.State.PAUSED
                MediaStatus.PLAYER_STATE_BUFFERING,
                MediaStatus.PLAYER_STATE_LOADING,
                -> CastReceiverStatus.State.BUFFERING
                MediaStatus.PLAYER_STATE_IDLE -> CastReceiverStatus.State.IDLE
                else -> CastReceiverStatus.State.UNKNOWN
            },
            idleReason = when (status.idleReason) {
                MediaStatus.IDLE_REASON_FINISHED -> CastReceiverStatus.IdleReason.FINISHED
                MediaStatus.IDLE_REASON_CANCELED -> CastReceiverStatus.IdleReason.CANCELLED
                MediaStatus.IDLE_REASON_INTERRUPTED -> CastReceiverStatus.IdleReason.INTERRUPTED
                MediaStatus.IDLE_REASON_ERROR -> CastReceiverStatus.IdleReason.ERROR
                else -> null
            },
            positionSeconds = approximateStreamPosition / 1000.0,
            durationSeconds = streamDuration.takeIf { it > 0 }?.let { it / 1000.0 },
            queuedItems = status.queueItemCount,
        )
    }

    // endregion

    private companion object {
        const val STATUS_INTERVAL_MS = 5_000L
    }
}
