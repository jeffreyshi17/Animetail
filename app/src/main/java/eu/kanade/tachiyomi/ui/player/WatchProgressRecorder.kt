package eu.kanade.tachiyomi.ui.player

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.source.anime.interactor.GetAnimeIncognitoState
import eu.kanade.domain.track.anime.interactor.TrackEpisode
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.database.models.anime.Episode
import eu.kanade.tachiyomi.data.database.models.anime.isRecognizedNumber
import eu.kanade.tachiyomi.data.database.models.anime.toDomainEpisode
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.history.anime.interactor.UpsertAnimeHistory
import tachiyomi.domain.history.anime.model.AnimeHistoryUpdate
import tachiyomi.domain.items.episode.interactor.UpdateEpisode
import tachiyomi.domain.items.episode.model.EpisodeUpdate
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import java.util.Date

/**
 * Writes watch progress: last second seen, history, and what follows marking an episode seen
 * (trackers, deleting after watching, duplicate episodes). The player uses it while it plays and
 * the cast tracker while the TV plays with the player closed, so both follow the same rules.
 */
@Inject
class WatchProgressRecorder(
    private val context: Context,
    private val updateEpisode: UpdateEpisode,
    private val upsertHistory: UpsertAnimeHistory,
    private val trackEpisode: TrackEpisode,
    private val trackPreferences: TrackPreferences,
    private val basePreferences: BasePreferences,
    private val libraryPreferences: LibraryPreferences,
    private val playerPreferences: PlayerPreferences,
    private val downloadPreferences: DownloadPreferences,
    private val downloadManager: AnimeDownloadManager,
    private val getIncognitoState: GetAnimeIncognitoState,
    private val getTracks: GetAnimeTracks,
) {

    /** What incognito mode lets us record: trackers still need progress, history stays hidden. */
    data class Privacy(val incognito: Boolean, val hasTrackers: Boolean) {
        val savesProgress: Boolean get() = !incognito || hasTrackers
        val savesHistory: Boolean get() = !incognito
    }

    suspend fun privacyFor(anime: Anime): Privacy = Privacy(
        incognito = getIncognitoState.await(anime.source),
        hasTrackers = getTracks.await(anime.id).isNotEmpty(),
    )

    /** Whether [positionMs] is far enough into the episode to count it as seen. */
    fun reachedSeen(positionMs: Long, durationMs: Long): Boolean =
        isSeen(positionMs, durationMs, playerPreferences.progressPreference().get())

    /** Saves [episode]'s last second seen and seen flag. */
    suspend fun saveProgress(episode: Episode, privacy: Privacy) {
        if (!privacy.savesProgress) return
        updateEpisode.await(
            EpisodeUpdate(
                id = episode.id!!,
                seen = episode.seen,
                bookmark = episode.bookmark,
                fillermark = episode.fillermark,
                lastSecondSeen = episode.last_second_seen,
                totalSeconds = episode.total_seconds,
            ),
        )
    }

    /** Records that [episodeId] was watched just now. */
    suspend fun saveHistory(episodeId: Long, privacy: Privacy) {
        if (!privacy.savesHistory) return
        upsertHistory.await(AnimeHistoryUpdate(episodeId, Date()))
    }

    /**
     * Marks [episode] seen in memory and does what follows: updates trackers, deletes the episode
     * that many slots back when "delete after watching" is on, and marks duplicates seen. The seen
     * flag itself is written by the next [saveProgress].
     *
     * @param playlist the episodes the player steps through, in order.
     */
    suspend fun onEpisodeSeen(anime: Anime, episode: Episode, playlist: List<Episode>, privacy: Privacy) {
        if (!privacy.savesProgress) return
        episode.seen = true
        deleteAfterSeen(anime, episode, playlist)
        markDuplicatesSeen(episode, playlist)
        updateTrackers(anime, episode, privacy)
    }

    private suspend fun deleteAfterSeen(anime: Anime, episode: Episode, playlist: List<Episode>) {
        val removeAfterSeenSlots = downloadPreferences.removeAfterReadSlots.get()
        val episodeToDelete = playlist.getOrNull(playlist.indexOf(episode) - removeAfterSeenSlots)
        // The download manager persists the queue of episodes to delete across process deaths.
        if (removeAfterSeenSlots != -1 && episodeToDelete != null && episodeToDelete.seen) {
            downloadManager.enqueueEpisodesToDelete(listOf(episodeToDelete.toDomainEpisode()!!), anime)
        }
    }

    private suspend fun markDuplicatesSeen(episode: Episode, playlist: List<Episode>) {
        val markDuplicateAsSeen = libraryPreferences.markDuplicateSeenEpisodeAsSeen.get()
            .contains(LibraryPreferences.MARK_DUPLICATE_EPISODE_SEEN_EXISTING)
        if (!markDuplicateAsSeen) return

        val duplicateUnseenEpisodes = playlist.mapNotNull { other ->
            if (!other.seen && other.isRecognizedNumber && other.episode_number == episode.episode_number) {
                EpisodeUpdate(id = other.id!!, seen = true)
            } else {
                null
            }
        }
        updateEpisode.awaitAll(duplicateUnseenEpisodes)
    }

    private suspend fun updateTrackers(anime: Anime, episode: Episode, privacy: Privacy) {
        if (basePreferences.incognitoMode.get() || !privacy.hasTrackers) return
        if (!trackPreferences.autoUpdateTrack.get()) return
        trackEpisode.await(context, anime.id, episode.episode_number.toDouble())
    }

    companion object {
        /**
         * Where an episode starts: where it was left, except that a seen episode starts over unless
         * the user keeps the position of watched episodes.
         */
        fun resumePositionMs(episode: Episode, preserveWatchingPosition: Boolean): Long =
            if (episode.seen && !preserveWatchingPosition) 0L else episode.last_second_seen

        /** The "mark as seen at" rule: [threshold] is the fraction of the episode, e.g. 0.85. */
        fun isSeen(positionMs: Long, durationMs: Long, threshold: Float): Boolean =
            durationMs > 0 && positionMs >= durationMs * threshold
    }
}
