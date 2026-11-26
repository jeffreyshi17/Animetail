package eu.kanade.tachiyomi.ui.player

import dev.zacsweers.metro.Inject
import eu.kanade.domain.base.BasePreferences
import eu.kanade.domain.items.episode.model.toDbEpisode
import eu.kanade.tachiyomi.data.database.models.anime.Episode
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadCache
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadManager
import eu.kanade.tachiyomi.util.episode.filterDownloadedEpisodes
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.items.episode.service.getEpisodeSort

/**
 * The episodes the player steps through for an anime, in order: the anime's sort and episode
 * filters, and downloaded-only mode. Previous, next and autoplay move along this list.
 */
@Inject
class PlayerPlaylist(
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId,
    private val basePreferences: BasePreferences,
    private val downloadCache: AnimeDownloadCache,
    private val downloadManager: AnimeDownloadManager,
) {

    /** The playlist [episodeId] plays in. It is always on it, even when the filters would hide it. */
    suspend fun episodes(anime: Anime, episodeId: Long): List<Episode> {
        val episodes = getEpisodesByAnimeId.await(anime.id)
            .sortedWith(getEpisodeSort(anime, sortDescending = false))
            .run {
                if (basePreferences.downloadedOnly.get()) {
                    filterDownloadedEpisodes(anime, downloadCache)
                } else {
                    this
                }
            }
            .map { it.toDbEpisode() }
        return filter(anime, episodes, episodeId)
    }

    private fun filter(anime: Anime, episodes: List<Episode>, episodeId: Long): List<Episode> {
        val selectedEpisode = episodes.find { it.id == episodeId }
            ?: error("Requested episode of id $episodeId not found in episode list")

        val episodesForPlayer = episodes.filterNot {
            (
                anime.unseenFilterRaw == Anime.EPISODE_SHOW_SEEN &&
                    !it.seen
                ) ||
                (
                    anime.unseenFilterRaw == Anime.EPISODE_SHOW_UNSEEN &&
                        it.seen
                    ) ||
                (
                    anime.downloadedFilterRaw == Anime.EPISODE_SHOW_DOWNLOADED &&
                        !downloadManager.isEpisodeDownloaded(
                            it.name,
                            it.scanlator,
                            it.url,
                            anime.title,
                            anime.source,
                        )
                    ) ||
                (
                    anime.downloadedFilterRaw == Anime.EPISODE_SHOW_NOT_DOWNLOADED &&
                        downloadManager.isEpisodeDownloaded(
                            it.name,
                            it.scanlator,
                            it.url,
                            anime.title,
                            anime.source,
                        )
                    ) ||
                (
                    anime.bookmarkedFilterRaw == Anime.EPISODE_SHOW_BOOKMARKED &&
                        !it.bookmark
                    ) ||
                (
                    anime.bookmarkedFilterRaw == Anime.EPISODE_SHOW_NOT_BOOKMARKED &&
                        it.bookmark
                    ) ||
                (
                    anime.fillermarkedFilterRaw == Anime.EPISODE_SHOW_FILLERMARKED &&
                        !it.fillermark
                    ) ||
                (
                    anime.fillermarkedFilterRaw == Anime.EPISODE_SHOW_NOT_FILLERMARKED &&
                        it.fillermark
                    )
        }.toMutableList()

        if (episodesForPlayer.all { it.id != episodeId }) {
            episodesForPlayer += listOf(selectedEpisode)
        }

        return episodesForPlayer
    }

    companion object {
        /** The episode after [episodeId] in [playlist], or null at the end. */
        fun next(playlist: List<Episode>, episodeId: Long): Episode? {
            val index = playlist.indexOfFirst { it.id == episodeId }
            return if (index == -1) null else playlist.getOrNull(index + 1)
        }
    }
}
