package eu.kanade.tachiyomi.ui.player.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.ui.player.CastManager
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.cast.components.CastPlayerDialog
import tachiyomi.i18n.tail.TLMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Shown across the app while casting, so the user can browse and still get back to the TV's
 * controls (tap) or stop casting in one step.
 */
@Composable
fun CastNowPlayingBanner(
    castManager: CastManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val media by castManager.currentMedia.collectAsState()
    val isPlaying by castManager.isPlaying.collectAsState()
    val nowPlaying by castManager.nowPlayingEpisode.collectAsState()
    val devices by castManager.availableDevices.collectAsState()
    val deviceName = devices.firstOrNull { it.isConnected }?.name
    // Without a known episode (e.g. a session joined from elsewhere) the controls open as a dialog.
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) CastPlayerDialog(castManager = castManager, onDismiss = { showDialog = false })

    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .then(modifier)
                .fillMaxWidth()
                .height(56.dp)
                .clickable(onClickLabel = stringResource(TLMR.strings.cast_now_playing_open)) {
                    val episode = nowPlaying
                    if (episode == null) {
                        showDialog = true
                    } else {
                        context.startActivity(PlayerActivity.newIntent(context, episode.animeId, episode.episodeId))
                    }
                }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (media?.thumbnail.isNullOrEmpty()) {
                Icon(Icons.Default.CastConnected, contentDescription = null, modifier = Modifier.size(32.dp))
            } else {
                AsyncImage(
                    model = media?.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    text = media?.title.orEmpty(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(
                        deviceName?.let { stringResource(TLMR.strings.cast_casting_to, it) }
                            ?: stringResource(TLMR.strings.cast_casting),
                        media?.subtitle?.takeIf { it.isNotBlank() },
                    ).joinToString(" - "),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = castManager::togglePlayPause) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = stringResource(
                        if (isPlaying) TLMR.strings.cast_pause else TLMR.strings.cast_play,
                    ),
                )
            }
            IconButton(onClick = castManager::endSession) {
                Icon(Icons.Default.StopCircle, contentDescription = stringResource(TLMR.strings.cast_stop_casting))
            }
        }
    }
}
