package eu.kanade.tachiyomi.ui.player.cast

/**
 * What to play on a cast receiver, independent of the backend (Google Cast SDK or the direct CastV2
 * client). URLs are already reachable by the receiver: local files and header-gated streams point at
 * the phone's cast proxy.
 */
data class CastMediaRequest(
    val url: String,
    val contentType: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    /** Stream duration in seconds, or null when unknown. */
    val durationSeconds: Double?,
    val startPositionSeconds: Double,
    val autoplay: Boolean,
    val textTracks: List<TextTrack> = emptyList(),
    /** Index into [textTracks] of the subtitle to show, or null for none. */
    val activeTextTrackIndex: Int? = null,
    val textTrackStyle: TextStyle? = null,
) {
    /** A WebVTT subtitle track the receiver fetches by [url]. */
    data class TextTrack(
        val url: String,
        val name: String,
        val language: String?,
    )

    data class TextStyle(
        val fontScale: Float,
        /** Colors as ARGB ints. */
        val foregroundColor: Int,
        val backgroundColor: Int,
        val edgeType: EdgeType,
        val edgeColor: Int,
        val fontGenericFamily: FontFamily,
    ) {
        enum class EdgeType { NONE, OUTLINE, DROP_SHADOW, RAISED, DEPRESSED }
        enum class FontFamily { SANS_SERIF, SERIF, MONOSPACED_SANS_SERIF, CURSIVE }
    }

    companion object {
        const val CONTENT_TYPE_HLS = "application/x-mpegURL"
        const val CONTENT_TYPE_DASH = "application/dash+xml"
        const val CONTENT_TYPE_MP4 = "video/mp4"
        const val CONTENT_TYPE_MKV = "video/x-matroska"
        const val CONTENT_TYPE_WEBM = "video/webm"

        /** Guesses the receiver content type from a stream URL or file name. */
        fun contentTypeFor(urlOrName: String): String {
            val path = urlOrName.substringBefore('#').lowercase()
            return when {
                ".m3u8" in path || "/m3u8" in path -> CONTENT_TYPE_HLS
                ".mpd" in path -> CONTENT_TYPE_DASH
                ".mkv" in path -> CONTENT_TYPE_MKV
                ".webm" in path -> CONTENT_TYPE_WEBM
                else -> CONTENT_TYPE_MP4
            }
        }
    }
}
