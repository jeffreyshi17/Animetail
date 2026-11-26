package eu.kanade.tachiyomi.ui.player.cast

import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.core.net.toUri
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.cast.components.BorderStyle
import eu.kanade.tachiyomi.ui.player.cast.components.SubtitleSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.system.logcat

/**
 * Turns the player's current [Video] into a [CastMediaRequest] the receiver can play: torrents become
 * the local torrent server's stream, and local files, header-gated streams, HLS and subtitles go
 * through the cast proxy (see [CastRouting]).
 */
class CastMediaBuilder(
    private val viewModel: PlayerViewModel,
    private val activity: PlayerActivity,
) {
    private val appGraph by lazy { activity.applicationContext.appGraph }
    private val proxyHolder get() = appGraph.castProxyHolder
    private val torrentServerApi get() = appGraph.torrentServerApi
    private val torrentServerUtils get() = appGraph.torrentServerUtils

    /**
     * @param lanHost the phone's address on the network the receiver is on.
     */
    suspend fun build(
        video: Video,
        lanHost: String,
        startPositionSeconds: Double,
        autoplay: Boolean,
        textStyle: CastMediaRequest.TextStyle?,
    ): CastMediaRequest = withContext(Dispatchers.IO) {
        val streamUrl = when {
            video.videoUrl.startsWith("magnet") || video.videoUrl.endsWith(".torrent") ->
                torrentLinkHandler(video.videoUrl, video.videoTitle)

            else -> video.videoUrl
        }
        val headers = sourceHeaders(video)
        val contentType = contentType(streamUrl)
        val routed = proxyHolder.route(
            streamUrl = streamUrl,
            headers = headers,
            contentType = contentType,
            subtitleUrls = video.subtitleTracks.map { it.url },
            lanHost = lanHost,
        )
        logcat(LogPriority.DEBUG) { "Cast: $contentType, proxied=${routed.streamUrl != streamUrl}" }

        CastMediaRequest(
            url = routed.streamUrl,
            contentType = contentType,
            title = viewModel.currentAnime.value?.title.orEmpty(),
            subtitle = viewModel.currentEpisode.value?.name.orEmpty(),
            imageUrl = viewModel.currentAnime.value?.thumbnailUrl,
            durationSeconds = viewModel.mpv.getPropertyDouble("duration")?.takeIf { it > 0 },
            startPositionSeconds = startPositionSeconds,
            autoplay = autoplay,
            textTracks = video.subtitleTracks.zip(routed.subtitleUrls) { track, url ->
                CastMediaRequest.TextTrack(url = url, name = track.lang, language = null)
            },
            activeTextTrackIndex = activeSubtitleIndex(video),
            textTrackStyle = textStyle,
        )
    }

    /** The headers mpv plays the video with (see `PlayerActivity.setHttpOptions`). */
    private fun sourceHeaders(video: Video): Map<String, String> {
        val headers = video.headers
            ?: (viewModel.currentSource.value as? AnimeHttpSource)
                ?.takeIf { viewModel.isEpisodeOnline() == true }
                ?.headers
            ?: return emptyMap()
        return headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun contentType(url: String): String {
        if (url.startsWith("content://")) {
            activity.contentResolver.getType(url.toUri())
                ?.takeIf { it.startsWith("video/") }
                ?.let { return it }
        }
        return CastMediaRequest.contentTypeFor(Uri.decode(url))
    }

    /** The external subtitle mpv shows now: mpv names `sub-add`ed tracks after [Video.subtitleTracks] langs. */
    private fun activeSubtitleIndex(video: Video): Int? {
        val selectedId = viewModel.selectedSubtitles.value.first.takeIf { it > 0 } ?: return null
        val selected = viewModel.subtitleTracks.value.firstOrNull { it.id == selectedId } ?: return null
        return video.subtitleTracks.indexOfFirst { it.lang == selected.name }.takeIf { it >= 0 }
    }

    private suspend fun torrentLinkHandler(videoUrl: String, quality: String): String {
        var index = 0

        if (videoUrl.startsWith("content://")) {
            val videoInputStream = activity.applicationContext.contentResolver.openInputStream(Uri.parse(videoUrl))
                ?: throw IllegalStateException("Unable to open InputStream for content: $videoUrl")
            val torrent = torrentServerApi.uploadTorrent(videoInputStream, quality, false)
            return torrentServerUtils.getTorrentPlayLink(torrent, 0)
        }

        if (videoUrl.startsWith("magnet") && videoUrl.contains("index=")) {
            index = try {
                videoUrl.substringAfter("index=").substringBefore("&").toInt()
            } catch (e: NumberFormatException) {
                0
            }
        }

        val currentTorrent = torrentServerApi.addTorrent(videoUrl, quality, "", "", false)
        logcat(LogPriority.DEBUG) { "Torrent URL: $videoUrl" }
        return torrentServerUtils.getTorrentPlayLink(currentTorrent, index)
    }
}

/** Maps the cast subtitle settings to the receiver's text track style. */
fun SubtitleSettings.toCastTextStyle(): CastMediaRequest.TextStyle {
    val edgeType = when (borderStyle) {
        BorderStyle.OUTLINE -> CastMediaRequest.TextStyle.EdgeType.OUTLINE

        BorderStyle.DROP_SHADOW -> CastMediaRequest.TextStyle.EdgeType.DROP_SHADOW

        BorderStyle.RAISED -> CastMediaRequest.TextStyle.EdgeType.RAISED

        BorderStyle.DEPRESSED -> CastMediaRequest.TextStyle.EdgeType.DEPRESSED

        BorderStyle.NONE -> when {
            shadowRadius.value <= 0 -> CastMediaRequest.TextStyle.EdgeType.NONE
            shadowRadius.value <= 3 -> CastMediaRequest.TextStyle.EdgeType.DEPRESSED
            shadowRadius.value <= 6 -> CastMediaRequest.TextStyle.EdgeType.DROP_SHADOW
            else -> CastMediaRequest.TextStyle.EdgeType.OUTLINE
        }
    }
    return CastMediaRequest.TextStyle(
        fontScale = fontSize.value / 20f,
        foregroundColor = textColor.toArgb(),
        backgroundColor = backgroundColor.toArgb(),
        edgeType = edgeType,
        edgeColor = android.graphics.Color.BLACK,
        fontGenericFamily = when (fontFamily) {
            FontFamily.Serif -> CastMediaRequest.TextStyle.FontFamily.SERIF
            FontFamily.Monospace -> CastMediaRequest.TextStyle.FontFamily.MONOSPACED_SANS_SERIF
            FontFamily.Cursive -> CastMediaRequest.TextStyle.FontFamily.CURSIVE
            else -> CastMediaRequest.TextStyle.FontFamily.SANS_SERIF
        },
    )
}
