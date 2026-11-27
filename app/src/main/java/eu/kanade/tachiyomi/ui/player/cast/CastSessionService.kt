package eu.kanade.tachiyomi.ui.player.cast

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media.VolumeProviderCompat
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.ui.player.PlayerActivity
import eu.kanade.tachiyomi.ui.player.cast.direct.DirectCastController
import eu.kanade.tachiyomi.ui.player.cast.direct.DirectCastSession
import eu.kanade.tachiyomi.ui.player.cast.direct.DirectCastState
import eu.kanade.tachiyomi.util.system.getBitmapOrNull
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.tail.TLMR
import kotlin.math.roundToInt

/**
 * Shows a notification and lock-screen controls while a direct (Play-services-free) cast session
 * is connected. The session lives in the app-scoped [DirectCastController], not in the
 * player, so this service follows the controller and keeps working after the player is closed;
 * being a foreground service it also keeps the process, and with it the receiver connection, alive
 * while the app is in the background. It stops itself as soon as the session ends, whichever way
 * it ended. Google Cast sessions get their notification from the Cast framework instead.
 *
 * The notification is a plain one with its own action buttons, not a MediaStyle one: Android
 * containers that do not bridge media sessions to the system UI (HarmonyOS NEXT's) never show a
 * MediaStyle notification. The media session is kept only for lock-screen and system controls
 * where the platform supports them, and nothing here depends on it.
 */
class CastSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controller by lazy { appGraph.directCastController }
    private val nowPlaying by lazy { appGraph.castNowPlaying }

    private var mediaSession: MediaSessionCompat? = null
    private var volumeProvider: VolumeProviderCompat? = null

    private var content: CastNotificationState? = null
    private var episode: CastNowPlaying.Episode? = null
    private var shownKey: Pair<CastNotificationState, Any?>? = null

    private var coverKey: Any? = null
    private var cover: Bitmap? = null
    private var coverJob: Job? = null

    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = try {
            MediaSessionCompat(this, "CastSessionService").apply {
                setCallback(SessionCallback())
                isActive = true
            }
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Cast media session unavailable, showing the notification only" }
            null
        }
        content = CastNotificationState.from(
            controller.state.value,
            controller.device.value,
            System.currentTimeMillis(),
        )
        episode = nowPlaying.episode.value
        startForegroundCompat(buildNotification())
        logcat(LogPriority.INFO) { "Cast notification shown (media session: ${mediaSession != null})" }

        combine(controller.state, controller.device, nowPlaying.episode) { state, device, episode ->
            CastNotificationState.from(state, device, System.currentTimeMillis()) to episode
        }
            .onEach { (content, episode) -> if (content == null) stop() else render(content, episode) }
            .launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_PLAYBACK -> togglePlayback()
            ACTION_STOP_CASTING -> stopCasting()
        }
        // Restarted without a session there is nothing to show; the next connect starts it again.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.let {
            it.isActive = false
            it.release()
        }
        super.onDestroy()
    }

    private fun render(content: CastNotificationState, episode: CastNowPlaying.Episode?) {
        this.content = content
        this.episode = episode
        updateCover(content, episode)
        // A failing media session must not keep the notification from updating.
        runCatching { updateMediaSession(content) }
            .onFailure { logcat(LogPriority.WARN, it) { "Cast media session update failed" } }
        val key = content.notificationKey to coverKey.takeIf { cover != null }
        if (key != shownKey) {
            shownKey = key
            postNotification()
        }
    }

    private fun stop() {
        if (stopped) return
        stopped = true
        coverJob?.cancel()
        mediaSession?.isActive = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // region Commands

    private fun command(block: suspend (DirectCastSession) -> Unit) {
        val session = controller.session.value ?: return
        scope.launch {
            try {
                block(session)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Cast notification command failed" }
            }
        }
    }

    private fun togglePlayback() {
        if (controller.state.value?.isPlaying == true) command { it.pause() } else command { it.play() }
    }

    /** The same as the cast banner's stop: closes the receiver app and ends the session. */
    private fun stopCasting() {
        scope.launch { controller.stop() }
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() = command { it.play() }

        override fun onPause() = command { it.pause() }

        override fun onStop() = stopCasting()

        override fun onSeekTo(pos: Long) = command { it.seek(pos / 1000.0) }

        override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
            if (action == ACTION_STOP_CASTING) stopCasting()
        }
    }

    // endregion

    // region Media session

    private fun updateMediaSession(content: CastNotificationState) {
        val mediaSession = mediaSession ?: return
        mediaSession.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, content.title ?: castingLabel(content))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, content.subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, castingLabel(content))
                .putLong(
                    MediaMetadataCompat.METADATA_KEY_DURATION,
                    content.durationSeconds?.let { (it * 1000).toLong() } ?: -1L,
                )
                .apply { cover?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
                .build(),
        )

        val state = when (content.playback) {
            CastNotificationState.Playback.PLAYING -> PlaybackStateCompat.STATE_PLAYING
            CastNotificationState.Playback.PAUSED -> PlaybackStateCompat.STATE_PAUSED
            CastNotificationState.Playback.BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            CastNotificationState.Playback.IDLE -> PlaybackStateCompat.STATE_STOPPED
        }
        var actions = PlaybackStateCompat.ACTION_STOP
        if (content.canTogglePlayback) {
            actions = actions or PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO
        }
        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(
                    state,
                    (content.positionSeconds * 1000).toLong(),
                    if (content.playback == CastNotificationState.Playback.PLAYING) 1f else 0f,
                    SystemClock.elapsedRealtime(),
                )
                .setActions(actions)
                // Android 13+ builds the media controls from the session, not the notification actions.
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(
                        ACTION_STOP_CASTING,
                        stringResource(TLMR.strings.cast_stop_casting),
                        R.drawable.ic_close_24dp,
                    ).build(),
                )
                .build(),
        )
        updateVolume(content.volumeLevel)
    }

    /** Hands the volume keys to the TV, as a Google Cast session does, once the receiver reported its volume. */
    private fun updateVolume(level: Double?) {
        level ?: return
        val mediaSession = mediaSession ?: return
        val steps = (level * VOLUME_STEPS).roundToInt().coerceIn(0, VOLUME_STEPS)
        volumeProvider?.let {
            if (it.currentVolume != steps) it.currentVolume = steps
            return
        }
        volumeProvider = object : VolumeProviderCompat(VOLUME_CONTROL_ABSOLUTE, VOLUME_STEPS, steps) {
            override fun onSetVolumeTo(volume: Int) = setVolume(volume)

            override fun onAdjustVolume(direction: Int) = setVolume(currentVolume + direction)

            private fun setVolume(volume: Int) {
                val target = volume.coerceIn(0, VOLUME_STEPS)
                currentVolume = target
                command { it.setVolume(target.toDouble() / VOLUME_STEPS) }
            }
        }.also(mediaSession::setPlaybackToRemote)
    }

    // endregion

    // region Notification

    private fun castingLabel(content: CastNotificationState): String =
        content.deviceName?.let { stringResource(TLMR.strings.cast_casting_to, it) }
            ?: stringResource(TLMR.strings.cast_casting)

    private fun buildNotification(): Notification {
        val content = content
        val playing = content?.playback == CastNotificationState.Playback.PLAYING
        return notificationBuilder(Notifications.CHANNEL_CAST) {
            setSmallIcon(R.drawable.ic_cast_20dp)
            setContentTitle(content?.title ?: stringResource(TLMR.strings.cast_casting))
            setContentText(content?.let { it.detailLine(castingLabel(it)) })
            setLargeIcon(cover)
            setContentIntent(openPlayerIntent())
            setOngoing(true)
            setOnlyAlertOnce(true)
            setShowWhen(false)
            // Not CATEGORY_TRANSPORT: containers without media session support may hide those.
            setCategory(NotificationCompat.CATEGORY_SERVICE)
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

            if (content?.canTogglePlayback == true) {
                addAction(
                    if (playing) R.drawable.ic_pause_24dp else R.drawable.ic_play_arrow_24dp,
                    stringResource(if (playing) TLMR.strings.cast_pause else TLMR.strings.cast_play),
                    serviceIntent(ACTION_TOGGLE_PLAYBACK),
                )
            }
            addAction(
                R.drawable.ic_close_24dp,
                stringResource(TLMR.strings.cast_stop_casting),
                serviceIntent(ACTION_STOP_CASTING),
            )
        }.build()
    }

    // Without POST_NOTIFICATIONS the system drops the update; the foreground service keeps running.
    @SuppressLint("MissingPermission")
    private fun postNotification() {
        if (stopped) return
        notificationManager.notify(Notifications.ID_CAST, buildNotification())
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifications.ID_CAST, notification, type)
    }

    /** Opens the player on the cast episode, like tapping the cast banner. */
    private fun openPlayerIntent(): PendingIntent? {
        val episode = episode
        val intent = if (episode != null) {
            PlayerActivity.newIntent(this, episode.animeId, episode.episodeId)
        } else {
            packageManager.getLaunchIntentForPackage(packageName) ?: return null
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun serviceIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, CastSessionService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Loads the anime's cover (through the cover cache when the episode is known) once per anime. */
    private fun updateCover(content: CastNotificationState, episode: CastNowPlaying.Episode?) {
        val key: Any = episode?.animeId ?: content.imageUrl ?: return
        if (key == coverKey) return
        coverKey = key
        cover = null
        coverJob?.cancel()
        coverJob = scope.launch {
            val data: Any = episode?.let { appGraph.getAnime.await(it.animeId) } ?: content.imageUrl ?: return@launch
            val request = ImageRequest.Builder(this@CastSessionService)
                .data(data)
                .size(COVER_SIZE_PX)
                .build()
            val bitmap = runCatching {
                imageLoader.execute(request).image?.asDrawable(resources)?.getBitmapOrNull()
            }.getOrNull() ?: return@launch
            if (coverKey != key) return@launch
            cover = bitmap
            this@CastSessionService.content?.let { render(it, this@CastSessionService.episode) }
        }
    }

    // endregion

    companion object {
        private const val ACTION_TOGGLE_PLAYBACK = "eu.kanade.tachiyomi.cast.TOGGLE_PLAYBACK"
        private const val ACTION_STOP_CASTING = "eu.kanade.tachiyomi.cast.STOP_CASTING"
        private const val VOLUME_STEPS = 20
        private const val COVER_SIZE_PX = 512

        /**
         * Starts the service whenever [controller] connects a session. Sessions are connected from
         * the player in the foreground, which is when a foreground service may start.
         */
        fun followSessions(context: Context, controller: DirectCastController, scope: CoroutineScope) {
            val appContext = context.applicationContext
            controller.state
                .map { it?.phase == DirectCastState.Phase.CONNECTED }
                .distinctUntilChanged()
                .filter { it }
                .onEach {
                    try {
                        ContextCompat.startForegroundService(
                            appContext,
                            Intent(appContext, CastSessionService::class.java),
                        )
                    } catch (e: IllegalStateException) {
                        // ForegroundServiceStartNotAllowedException: connected while in the background.
                        logcat(LogPriority.WARN, e) { "Could not start the cast notification" }
                    }
                }
                .launchIn(scope)
        }
    }
}
