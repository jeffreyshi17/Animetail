package eu.kanade.tachiyomi.ui.player.cast

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The episode the cast receiver is playing, kept app-wide so the cast banner can reopen the player
 * on it after the user leaves the player to browse the app.
 */
@Inject
@SingleIn(AppScope::class)
class CastNowPlaying {

    /**
     * @param subtitleName the name of the subtitle track the receiver shows, so the next episode
     * the tracker loads keeps the same language.
     */
    data class Episode(val animeId: Long, val episodeId: Long, val subtitleName: String? = null)

    private val _episode = MutableStateFlow<Episode?>(null)
    val episode: StateFlow<Episode?> = _episode.asStateFlow()

    fun set(episode: Episode?) {
        _episode.value = episode
    }
}
