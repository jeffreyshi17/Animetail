package eu.kanade.tachiyomi.ui.player.cast.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.ui.player.CastManager
import eu.kanade.tachiyomi.ui.player.PlayerViewModel
import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol
import `is`.xyz.mpv.Utils
import tachiyomi.i18n.tail.TLMR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The player while casting: the local video is paused, so instead of its controls this shows what
 * the TV plays and controls the TV. Seeking, play/pause and episode changes go through the
 * [PlayerViewModel], which forwards them to the receiver while casting.
 */
@Composable
fun CastControlsScreen(
    viewModel: PlayerViewModel,
    castManager: CastManager,
    onBack: () -> Unit,
    onOpenCastSheet: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val anime by viewModel.currentAnime.collectAsState()
    val episode by viewModel.currentEpisode.collectAsState()
    val position by viewModel.pos.collectAsState()
    val duration by viewModel.duration.collectAsState()
    val paused by viewModel.paused.collectAsState()
    val speed by viewModel.playbackSpeed.collectAsState()
    val volume by castManager.volume.collectAsState()
    val devices by castManager.availableDevices.collectAsState()
    val deviceName = devices.firstOrNull { it.isConnected }?.name
    var seekTarget by remember { mutableStateOf<Float?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // Swallow taps so they do not reach the hidden player controls and gestures beneath.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = Color.White)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = anime?.title.orEmpty(),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = episode?.name.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onOpenCastSheet) {
                Icon(Icons.Default.CastConnected, contentDescription = null, tint = Color.White)
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 72.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AsyncImage(
                model = anime?.thumbnailUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .height(168.dp)
                    .width(120.dp)
                    .clip(RoundedCornerShape(8.dp)),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Default.Cast, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = deviceName?.let { stringResource(TLMR.strings.cast_casting_to, it) }
                        ?: stringResource(TLMR.strings.cast_casting),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                Slider(
                    value = (seekTarget ?: position).coerceIn(0f, duration.coerceAtLeast(1f)),
                    onValueChange = { seekTarget = it },
                    onValueChangeFinished = {
                        seekTarget?.let { viewModel.seekTo(it.toInt()) }
                        seekTarget = null
                    },
                    valueRange = 0f..duration.coerceAtLeast(1f),
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = Utils.prettyTime((seekTarget ?: position).toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = Utils.prettyTime(duration.toInt()),
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.changeEpisode(previous = true) }) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        stringResource(TLMR.strings.cast_previous_video),
                        tint = Color.White,
                    )
                }
                IconButton(onClick = { viewModel.seekBy(-10) }) {
                    Icon(Icons.Default.Replay10, contentDescription = null, tint = Color.White)
                }
                FilledIconButton(onClick = viewModel::pauseUnpause, modifier = Modifier.size(72.dp)) {
                    Icon(
                        if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = stringResource(
                            if (paused) TLMR.strings.cast_play else TLMR.strings.cast_pause,
                        ),
                        modifier = Modifier.size(40.dp),
                    )
                }
                IconButton(onClick = { viewModel.seekBy(10) }) {
                    Icon(Icons.Default.Forward10, contentDescription = null, tint = Color.White)
                }
                IconButton(onClick = { viewModel.changeEpisode(previous = false) }) {
                    Icon(Icons.Default.SkipNext, stringResource(TLMR.strings.cast_next_video), tint = Color.White)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = stringResource(TLMR.strings.cast_volume),
                    tint = Color.White,
                )
                Slider(
                    value = volume,
                    onValueChange = castManager::setVolume,
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${(volume * 100).toInt()}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CastSpeedButton(speed = speed, onSpeedChange = viewModel::changeSpeed)
                OutlinedButton(onClick = castManager::endSession) {
                    Icon(Icons.Default.StopCircle, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(TLMR.strings.cast_stop_casting))
                }
            }
        }
    }
}

@Composable
private fun CastSpeedButton(speed: Float, onSpeedChange: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Speed, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("${speed.coerceAtMost(CastV2Protocol.MAX_PLAYBACK_RATE.toFloat())}x")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CAST_SPEEDS.forEach { option ->
                DropdownMenuItem(
                    text = { Text("${option}x") },
                    onClick = {
                        expanded = false
                        onSpeedChange(option)
                    },
                )
            }
        }
    }
}

// The Default Media Receiver plays between 0.5x and 2x.
private val CAST_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
