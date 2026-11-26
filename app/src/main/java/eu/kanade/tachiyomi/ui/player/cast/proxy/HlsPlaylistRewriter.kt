package eu.kanade.tachiyomi.ui.player.cast.proxy

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Rewrites every URI in an HLS playlist (variant streams, media playlists, segments, keys, init
 * maps, alternate renditions) to go through the cast proxy, so the receiver's follow-up requests also
 * get the source's headers and CORS.
 */
object HlsPlaylistRewriter {

    private val uriAttribute = Regex("""URI="([^"]*)"""")

    fun isPlaylist(contentType: String?, url: String, head: String?): Boolean {
        val type = contentType?.lowercase().orEmpty()
        return "mpegurl" in type ||
            url.substringBefore('?').lowercase().endsWith(".m3u8") ||
            head?.trimStart()?.startsWith("#EXTM3U") == true
    }

    /**
     * @param playlistUrl the URL the playlist was finally served from (after redirects); relative
     *   URIs resolve against it.
     * @param proxy maps an absolute upstream URL to the proxied URL the receiver should fetch.
     */
    fun rewrite(playlist: String, playlistUrl: HttpUrl, proxy: (String) -> String): String {
        fun resolve(uri: String): String? {
            if (uri.isEmpty() || uri.startsWith("data:") || uri.startsWith("skd:")) return null
            return (playlistUrl.resolve(uri) ?: uri.toHttpUrlOrNull())?.toString()
        }

        return playlist.lineSequence().joinToString("\n") { rawLine ->
            val line = rawLine.trimEnd('\r')
            when {
                line.isBlank() -> line

                line.startsWith("#") -> uriAttribute.replace(line) { match ->
                    val resolved = resolve(match.groupValues[1]) ?: return@replace match.value
                    "URI=\"${proxy(resolved)}\""
                }

                else -> resolve(line.trim())?.let(proxy) ?: line
            }
        }
    }
}
