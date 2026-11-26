package eu.kanade.tachiyomi.ui.player.cast

import android.content.Context
import android.net.Uri
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaTrack
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.images.WebImage
import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol

/** Whether Google Play services is installed, enabled and up to date: what the Google Cast SDK needs. */
fun isPlayServicesAvailable(context: Context): Boolean = try {
    GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
} catch (_: Throwable) {
    false
}

/** The Google Cast SDK form of a [CastMediaRequest]; track ids match the direct backend's. */
fun CastMediaRequest.toGmsMediaInfo(): MediaInfo {
    val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
        putString(MediaMetadata.KEY_TITLE, title)
        putString(MediaMetadata.KEY_SUBTITLE, subtitle)
        imageUrl?.let { addImage(WebImage(Uri.parse(it))) }
    }
    val tracks = textTracks.mapIndexed { index, track ->
        MediaTrack.Builder(CastV2Protocol.trackId(index).toLong(), MediaTrack.TYPE_TEXT)
            .setContentId(track.url)
            .setContentType("text/vtt")
            .setSubtype(MediaTrack.SUBTYPE_SUBTITLES)
            .setName(track.name)
            .apply { track.language?.let(::setLanguage) }
            .build()
    }
    return MediaInfo.Builder(url)
        .setContentType(contentType)
        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
        .setMetadata(metadata)
        .setMediaTracks(tracks)
        .apply { durationSeconds?.let { setStreamDuration((it * 1000).toLong()) } }
        .apply { textTrackStyle?.let { setTextTrackStyle(it.toGmsTextTrackStyle()) } }
        .build()
}

fun CastMediaRequest.toGmsLoadRequest(): MediaLoadRequestData = MediaLoadRequestData.Builder()
    .setMediaInfo(toGmsMediaInfo())
    .setAutoplay(autoplay)
    .setCurrentTime((startPositionSeconds * 1000).toLong())
    .apply {
        activeTextTrackIndex?.takeIf { it in textTracks.indices }?.let { index ->
            setActiveTrackIds(longArrayOf(CastV2Protocol.trackId(index).toLong()))
        }
    }
    .build()

fun CastMediaRequest.TextStyle.toGmsTextTrackStyle(): TextTrackStyle = TextTrackStyle().also { style ->
    style.fontScale = fontScale
    style.foregroundColor = foregroundColor
    style.backgroundColor = backgroundColor
    style.windowColor = android.graphics.Color.TRANSPARENT
    style.edgeColor = edgeColor
    style.edgeType = when (edgeType) {
        CastMediaRequest.TextStyle.EdgeType.NONE -> TextTrackStyle.EDGE_TYPE_NONE
        CastMediaRequest.TextStyle.EdgeType.OUTLINE -> TextTrackStyle.EDGE_TYPE_OUTLINE
        CastMediaRequest.TextStyle.EdgeType.DROP_SHADOW -> TextTrackStyle.EDGE_TYPE_DROP_SHADOW
        CastMediaRequest.TextStyle.EdgeType.RAISED -> TextTrackStyle.EDGE_TYPE_RAISED
        CastMediaRequest.TextStyle.EdgeType.DEPRESSED -> TextTrackStyle.EDGE_TYPE_DEPRESSED
    }
    style.fontGenericFamily = when (fontGenericFamily) {
        CastMediaRequest.TextStyle.FontFamily.SANS_SERIF -> TextTrackStyle.FONT_FAMILY_SANS_SERIF
        CastMediaRequest.TextStyle.FontFamily.SERIF -> TextTrackStyle.FONT_FAMILY_SERIF
        CastMediaRequest.TextStyle.FontFamily.MONOSPACED_SANS_SERIF -> TextTrackStyle.FONT_FAMILY_MONOSPACED_SANS_SERIF
        CastMediaRequest.TextStyle.FontFamily.CURSIVE -> TextTrackStyle.FONT_FAMILY_CURSIVE
    }
}
