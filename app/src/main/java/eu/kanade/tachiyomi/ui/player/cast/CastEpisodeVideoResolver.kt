package eu.kanade.tachiyomi.ui.player.cast

import android.content.Context
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.animesource.AnimeSource
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.database.models.anime.Episode
import eu.kanade.tachiyomi.data.database.models.anime.toDomainEpisode
import eu.kanade.tachiyomi.ui.player.loader.EpisodeLoader
import eu.kanade.tachiyomi.ui.player.loader.HosterLoader
import kotlinx.coroutines.flow.first
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.source.anime.service.AnimeSourceManager

/**
 * Finds a playable video for an episode without the player: the episode's hosters in priority
 * order and the first video that resolves ([HosterLoader.getBestVideo]), as downloads pick theirs.
 */
@Inject
class CastEpisodeVideoResolver(
    private val context: Context,
    private val sourceManager: AnimeSourceManager,
) {

    data class Resolved(val source: AnimeSource, val video: Video, val isEpisodeOnline: Boolean)

    /** Returns null when no hoster gives a playable video. */
    suspend fun resolve(anime: Anime, episode: Episode): Resolved? = withIOContext {
        sourceManager.isInitialized.first { it }
        val source = sourceManager.getOrStub(anime.source)
        val domainEpisode = episode.toDomainEpisode()!!
        val hosters = EpisodeLoader.getHosters(context, domainEpisode, anime, source)
        val isEpisodeOnline = source is AnimeHttpSource && !EpisodeLoader.isDownload(context, domainEpisode, anime)
        HosterLoader.getBestVideo(context, source, hosters)?.let { Resolved(source, it, isEpisodeOnline) }
    }
}
