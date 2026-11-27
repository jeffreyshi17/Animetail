package eu.kanade.tachiyomi.ui.player

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.compose.ui.text.font.FontFamily
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaQueueItem
import com.google.android.gms.cast.MediaSeekOptions
import com.google.android.gms.cast.TextTrackStyle
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.ui.player.cast.CastBackend
import eu.kanade.tachiyomi.ui.player.cast.CastMediaBuilder
import eu.kanade.tachiyomi.ui.player.cast.CastMediaRequest
import eu.kanade.tachiyomi.ui.player.cast.CastNowPlaying
import eu.kanade.tachiyomi.ui.player.cast.CastPlaybackTracker
import eu.kanade.tachiyomi.ui.player.cast.CastSessionListener
import eu.kanade.tachiyomi.ui.player.cast.LanAddress
import eu.kanade.tachiyomi.ui.player.cast.components.SubtitleSettings
import eu.kanade.tachiyomi.ui.player.cast.direct.CastV2Protocol
import eu.kanade.tachiyomi.ui.player.cast.direct.DirectCastController
import eu.kanade.tachiyomi.ui.player.cast.direct.DirectCastSession
import eu.kanade.tachiyomi.ui.player.cast.direct.DirectCastState
import eu.kanade.tachiyomi.ui.player.cast.isPlayServicesAvailable
import eu.kanade.tachiyomi.ui.player.cast.toCastTextStyle
import eu.kanade.tachiyomi.ui.player.cast.toGmsLoadRequest
import eu.kanade.tachiyomi.ui.player.cast.toGmsTextTrackStyle
import eu.kanade.tachiyomi.ui.player.controls.components.sheets.HosterState
import eu.kanade.tachiyomi.ui.player.settings.CastSubtitlePreferences
import eu.kanade.tachiyomi.ui.player.settings.PlayerPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.tail.TLMR
import java.util.LinkedList
import kotlin.coroutines.resume

class CastManager(
    val activity: ComponentActivity,
    val preferenceStore: PreferenceStore,
) : PlayerViewModel.CastRemote {
    enum class CastState {
        CONNECTED,
        DISCONNECTED,
        CONNECTING,
    }

    data class CastDevice(
        val id: String,
        val name: String,
        val isConnected: Boolean = false,
        val isSelected: Boolean = false,
    )

    data class CastMedia(
        val title: String,
        val subtitle: String,
        val thumbnail: String?,
    )

    private val context = activity.applicationContext
    private val viewModel by lazy {
        when (activity) {
            is PlayerActivity -> {
                activity.viewModels<PlayerViewModel> { activity.appGraph.viewModelFactory }.value
            }

            else -> null
        }
    }
    private val player by lazy { (activity as? PlayerActivity)?.player }
    private val mpv by lazy { viewModel?.mpv }
    private val playerPreferences: PlayerPreferences by lazy {
        viewModel?.playerPreferences ?: PlayerPreferences(preferenceStore)
    }
    private val autoplayEnabled = playerPreferences.autoplayEnabled().get()
    private val subtitlePreferences = CastSubtitlePreferences(preferenceStore)
    private val mediaBuilder by lazy { CastMediaBuilder(context) }
    private val mediaRouter by lazy {
        androidx.mediarouter.media.MediaRouter.getInstance(context)
    }

    private val _castState = MutableStateFlow(CastState.DISCONNECTED)
    val castState: StateFlow<CastState> = _castState.asStateFlow()

    private var castContext: CastContext? = null
    var castSession: CastSession? = null
    private var sessionListener: CastSessionListener? = null
    private var castProgressJob: Job? = null
    private var discoveryRetryJob: Job? = null
    private var mediaRouterCallback: androidx.mediarouter.media.MediaRouter.Callback? = null

    private val isCastApiAvailable: Boolean
        get() = try {
            val availability = com.google.android.gms.common.GoogleApiAvailability.getInstance()
            val resultCode = availability.isGooglePlayServicesAvailable(context)
            resultCode == com.google.android.gms.common.ConnectionResult.SUCCESS ||
                resultCode == com.google.android.gms.common.ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED
        } catch (_: Throwable) {
            true
        }

    private val mediaQueue = LinkedList<MediaQueueItem>()
    private var isLoadingMedia = false

    private val _queueItems = MutableStateFlow<List<MediaQueueItem>>(emptyList())
    val queueItems: StateFlow<List<MediaQueueItem>> = _queueItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<CastDevice>>(emptyList())
    val availableDevices: StateFlow<List<CastDevice>> = _availableDevices.asStateFlow()

    private val _currentMedia = MutableStateFlow<CastMedia?>(null)
    val currentMedia: StateFlow<CastMedia?> = _currentMedia.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _volume = MutableStateFlow(1f)
    override val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _positionSeconds = MutableStateFlow(0.0)

    /** The receiver's playback position, refreshed while a session is connected. */
    val positionSeconds: StateFlow<Double> = _positionSeconds.asStateFlow()

    private val _durationSeconds = MutableStateFlow(0.0)
    val durationSeconds: StateFlow<Double> = _durationSeconds.asStateFlow()

    private val preferredBackend = playerPreferences.castBackend().get()

    /**
     * The backend this screen casts with. Google Cast needs working Play services; without them
     * `CastContext.getSharedInstance` throws and no cast route is ever discovered, so the automatic
     * choice falls back to the direct CastV2 client.
     */
    val backend: CastBackend

    private val nowPlaying by lazy { context.appGraph.castNowPlaying }

    /** The episode on the receiver, for the cast banner to reopen the player on. */
    val nowPlayingEpisode: StateFlow<CastNowPlaying.Episode?> get() = nowPlaying.episode

    /** Whether the receiver already plays [episodeId], so reopening the player must not reload it. */
    fun isCastingEpisode(episodeId: Long?): Boolean =
        episodeId != null && nowPlaying.episode.value?.episodeId == episodeId

    private fun recordNowPlaying(request: CastMediaRequest) {
        val animeId = viewModel?.currentAnime?.value?.id ?: return
        val episodeId = viewModel?.currentEpisode?.value?.id ?: return
        val subtitleName = request.activeTextTrackIndex?.let { request.textTracks.getOrNull(it)?.name }
        nowPlaying.set(CastNowPlaying.Episode(animeId, episodeId, subtitleName))
    }

    /** The receiver's request for [video] as the player shows it now: position, subtitle, metadata. */
    private suspend fun buildPlayerRequest(
        video: Video,
        lanHost: String,
        autoplay: Boolean,
        textStyle: CastMediaRequest.TextStyle?,
    ): CastMediaRequest {
        val vm = viewModel!!
        return mediaBuilder.build(
            video = video,
            headers = CastMediaBuilder.sourceHeaders(video, vm.currentSource.value, vm.isEpisodeOnline() == true),
            metadata = CastMediaBuilder.Metadata(
                title = vm.currentAnime.value?.title.orEmpty(),
                subtitle = vm.currentEpisode.value?.name.orEmpty(),
                imageUrl = vm.currentAnime.value?.thumbnailUrl,
                durationSeconds = vm.mpv.getPropertyDouble("duration")?.takeIf { it > 0 },
            ),
            activeSubtitleIndex = activeSubtitleIndex(vm, video),
            lanHost = lanHost,
            startPositionSeconds = mpv?.getPropertyDouble("time-pos") ?: 0.0,
            autoplay = autoplay,
            textStyle = textStyle,
        )
    }

    /** The external subtitle mpv shows now: mpv names `sub-add`ed tracks after [Video.subtitleTracks] langs. */
    private fun activeSubtitleIndex(vm: PlayerViewModel, video: Video): Int? {
        val selectedId = vm.selectedSubtitles.value.first.takeIf { it > 0 } ?: return null
        val selected = vm.subtitleTracks.value.firstOrNull { it.id == selectedId } ?: return null
        return CastMediaBuilder.subtitleIndexNamed(video, selected.name)
    }

    private val playbackTracker by lazy { context.appGraph.castPlaybackTracker }

    /** Lets the tracker hand the end of the cast episode to this player, which loads the next itself. */
    private val attachedPlayer = object : CastPlaybackTracker.AttachedPlayer {
        override val castingEpisodeId: Long?
            get() = viewModel?.takeIf { it.isCasting.value }?.currentEpisode?.value?.id

        override fun onReceiverFinished(advance: Boolean) {
            viewModel?.onCastFinished(advance)
        }
    }

    private val directController: DirectCastController? by lazy {
        if (backend == CastBackend.DIRECT) context.appGraph.directCastController else null
    }

    /** True while the direct backend looks for devices. */
    val isScanning: StateFlow<Boolean> by lazy {
        directController?.isScanning ?: MutableStateFlow(false)
    }

    init {
        var resolved = CastBackend.resolve(
            preference = preferredBackend,
            playServicesAvailable = preferredBackend != CastBackend.DIRECT && isPlayServicesAvailable(context),
        )
        if (resolved == CastBackend.GOOGLE_CAST && !initializeCast() && preferredBackend == CastBackend.AUTO) {
            resolved = CastBackend.DIRECT
        }
        backend = resolved
        logcat(LogPriority.INFO) { "Cast backend: $backend (preference $preferredBackend)" }
        if (backend == CastBackend.DIRECT) observeDirectSession()
        playbackTracker.follow(backend)
        viewModel?.let { vm ->
            vm.castRemote = this
            playbackTracker.attach(attachedPlayer)
            activity.lifecycleScope.launch { volume.collect(vm::onCastVolumeChanged) }
            // Every path that changes the cast state goes through _castState, so mirror it here.
            activity.lifecycleScope.launch {
                castState.collect {
                    val casting = it == CastState.CONNECTED
                    if (vm.isCasting.value && !casting) vm.onCastEnded()
                    vm.isCasting.value = casting
                    if (casting) vm.onCastVolumeChanged(_volume.value)
                }
            }
        }
    }

    private fun initializeCast(): Boolean {
        return try {
            castContext = CastContext.getSharedInstance(context.applicationContext)
            sessionListener = CastSessionListener(this)
            registerSessionListener()
            true
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Google Cast is unavailable" }
            false
        }
    }

    // Session Management
    fun registerSessionListener() {
        sessionListener?.let { listener ->
            castContext?.sessionManager?.addSessionManagerListener(listener, CastSession::class.java)
        }
    }

    fun unregisterSessionListener() {
        sessionListener?.let { listener ->
            castContext?.sessionManager?.removeSessionManagerListener(listener, CastSession::class.java)
        }
    }

    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    private fun acquireMulticastLock() {
        try {
            val wifiManager = context.getSystemService(
                android.content.Context.WIFI_SERVICE,
            ) as? android.net.wifi.WifiManager
            if (multicastLock == null) {
                multicastLock = wifiManager?.createMulticastLock("animetail_cast_multicast_lock")?.apply {
                    setReferenceCounted(false)
                }
            }
            if (multicastLock?.isHeld == false) {
                multicastLock?.acquire()
                android.util.Log.d("CastDebug", "MulticastLock acquired successfully")
            }
        } catch (e: Exception) {
            android.util.Log.e("CastDebug", "Failed to acquire MulticastLock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
                android.util.Log.d("CastDebug", "MulticastLock released")
            }
            multicastLock = null
        } catch (e: Exception) {
            android.util.Log.e("CastDebug", "Failed to release MulticastLock", e)
        }
    }

    fun cleanup() {
        if (viewModel?.castRemote === this) viewModel?.castRemote = null
        playbackTracker.detach(attachedPlayer)
        directController?.stopDiscovery()
        discoveryRetryJob?.cancel()
        mediaRouterCallback?.let { mediaRouter.removeCallback(it) }
        mediaRouterCallback = null
        releaseMulticastLock()
        unregisterSessionListener()
        castSession = null
    }

    // Session Callbacks
    fun onSessionConnected(session: CastSession) {
        castSession = session
        updateCastState(CastState.CONNECTED)
        startTrackingCastProgress()
        updateCurrentMedia()
        updateQueueItems()

        session.remoteMediaClient?.registerCallback(object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                updateCurrentMedia()
                updateQueueItems()
            }

            override fun onMetadataUpdated() {
                updateCurrentMedia()
                updateQueueItems()
            }

            override fun onQueueStatusUpdated() {
                updateQueueItems()
            }

            override fun onPreloadStatusUpdated() {
                updateQueueItems()
            }
        })
    }

    fun onSessionEnded() {
        nowPlaying.set(null)
        castProgressJob?.cancel()
        val lastPosition = getCurrentCastPosition()
        if (lastPosition > 0) viewModel?.updateCastProgress(lastPosition.toFloat() / 1000)
        castSession = null
        updateCastState(CastState.DISCONNECTED)
        viewModel?.resumeFromCast()
    }

    fun onSessionStartFailed() {
        castProgressJob?.cancel()
        castSession = null
        updateCastState(CastState.DISCONNECTED)
        _availableDevices.value = _availableDevices.value.map { device ->
            device.copy(isSelected = false, isConnected = false)
        }
        if (preferredBackend == CastBackend.AUTO) {
            showToast(context.stringResource(TLMR.strings.cast_google_failed_try_direct))
        } else {
            showConnectionErrorToast()
        }
    }

    fun updateCastState(state: CastState) {
        _castState.value = state
        if (state == CastState.CONNECTED) mpv?.setPropertyBoolean("pause", true)
        activity.invalidateOptionsMenu()
    }

    // Quality Selection
    fun handleQualitySelection() {
        viewModel?.hosterList?.filter { it.isNotEmpty() }
            ?.onEach { videos ->
                val hasQueueItems = (castSession?.remoteMediaClient?.mediaQueue?.itemCount ?: 0) > 0
                val hasMultipleQualities = videos.size > 1

                if (!hasQueueItems && !hasMultipleQualities) {
                    loadRemoteMedia()
                }
            }
            ?.launchIn(viewModel!!.viewModelScope)
    }

    fun removeQueueItem(itemId: Int) {
        castSession?.remoteMediaClient?.queueRemoveItem(itemId, null)?.setResultCallback { result ->
            if (result.status.isSuccess) {
                updateQueueItems()
            }
        }
    }

    fun moveQueueItem(itemId: Int, newIndex: Int) {
        castSession?.remoteMediaClient?.queueMoveItemToNewIndex(itemId, newIndex, null)?.setResultCallback { result ->
            if (result.status.isSuccess) {
                updateQueueItems()
            }
        }
    }

    // Media Loading & Progress Tracking
    @SuppressLint("SuspiciousIndentation")
    fun loadRemoteMedia() {
        if (backend == CastBackend.DIRECT) return loadDirectMedia()
        if (!isCastApiAvailable || isLoadingMedia) return
        val remoteMediaClient = castSession?.remoteMediaClient ?: return
        if (viewModel == null) return

        activity.lifecycleScope.launch {
            try {
                isLoadingMedia = true
                _isLoading.value = true
                val selectedHosterIndex = viewModel!!.selectedHosterVideoIndex.value.first
                val selectedVideoIndex = viewModel!!.selectedHosterVideoIndex.value.second

                val hosterState = viewModel!!.hosterState.value.getOrNull(selectedHosterIndex) as? HosterState.Ready
                val video = hosterState?.videoList?.getOrNull(selectedVideoIndex) ?: return@launch

                val request = buildPlayerRequest(
                    video = video,
                    lanHost = LanAddress.current()?.address ?: "127.0.0.1",
                    autoplay = autoplayEnabled,
                    textStyle = null,
                )
                val loadRequest = request.toGmsLoadRequest()
                val mediaInfo = loadRequest.mediaInfo!!

                updateQueueItems()
                if (remoteMediaClient.mediaQueue.itemCount > 0) {
                    val queueItem = MediaQueueItem.Builder(mediaInfo)
                        .setAutoplay(autoplayEnabled)
                        .build()

                    mediaQueue.add(queueItem)
                    remoteMediaClient.queueAppendItem(queueItem, null)
                    updateQueueItems()
                    showAddedToQueueToast()
                } else {
                    remoteMediaClient.load(loadRequest)
                    recordNowPlaying(request)
                    updateQueueItems()
                }
                _castState.value = CastState.CONNECTED
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Error loading media: ${e.message}" }
                showLoadErrorToast()
            } finally {
                isLoadingMedia = false
                _isLoading.value = false
            }
        }
    }

    private fun showAddedToQueueToast() {
        activity.runOnUiThread {
            Toast.makeText(
                context,
                context.stringResource(TLMR.strings.cast_video_added_to_queue),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun showLoadErrorToast() {
        activity.runOnUiThread {
            Toast.makeText(
                context,
                context.stringResource(TLMR.strings.cast_error_loading),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun startTrackingCastProgress() {
        castProgressJob?.cancel()
        castProgressJob = activity.lifecycleScope.launch {
            while (isSessionConnected()) {
                val currentPosition = getCurrentCastPosition()
                _positionSeconds.value = currentPosition / 1000.0
                _durationSeconds.value = getCastDuration() / 1000.0
                if (currentPosition > 0) viewModel?.updateCastProgress(currentPosition.toFloat() / 1000)
                viewModel?.onCastPlayingChanged(isRemotePlaying())
                delay(500)
            }
        }
    }

    private fun isRemotePlaying(): Boolean = when (backend) {
        CastBackend.DIRECT -> directController?.state?.value?.isPlaying == true
        else -> castSession?.remoteMediaClient?.isPlaying == true
    }

    // region CastRemote: the player's own controls while casting

    override fun play() {
        if (backend == CastBackend.DIRECT) return directCommand { it.play() }
        castSession?.remoteMediaClient?.play()
    }

    override fun pause() {
        if (backend == CastBackend.DIRECT) return directCommand { it.pause() }
        castSession?.remoteMediaClient?.pause()
    }

    override fun seekBy(offsetSeconds: Int) = seekRelative(offsetSeconds)

    override fun stepVolume(delta: Float) = setVolume(_volume.value + delta)

    override fun setSpeed(speed: Float) {
        val rate = speed.toDouble().coerceIn(CastV2Protocol.MIN_PLAYBACK_RATE, CastV2Protocol.MAX_PLAYBACK_RATE)
        if (rate != speed.toDouble() && !warnedAboutSpeedLimit) {
            warnedAboutSpeedLimit = true
            showToast(context.stringResource(TLMR.strings.cast_speed_limited, rate.toString()))
        }
        if (backend == CastBackend.DIRECT) return directCommand { it.setPlaybackRate(rate) }
        castSession?.remoteMediaClient?.setPlaybackRate(rate)
    }

    private var warnedAboutSpeedLimit = false

    // endregion

    private fun isSessionConnected(): Boolean = when (backend) {
        CastBackend.DIRECT -> directController?.state?.value?.phase == DirectCastState.Phase.CONNECTED
        else -> castSession?.isConnected == true
    }

    private fun getCurrentCastPosition(): Long = when (backend) {
        CastBackend.DIRECT ->
            directController?.state?.value
                ?.estimatedPositionSeconds(System.currentTimeMillis())
                ?.let { (it * 1000).toLong() }
                ?: 0

        else -> castSession?.remoteMediaClient?.approximateStreamPosition ?: 0
    }

    private fun getCastDuration(): Long = when (backend) {
        CastBackend.DIRECT -> directController?.state?.value?.media?.durationSeconds?.let { (it * 1000).toLong() } ?: 0
        else -> castSession?.remoteMediaClient?.mediaInfo?.streamDuration ?: 0
    }

    fun updateQueueItems(items: List<MediaQueueItem>? = null) {
        _queueItems.value = items ?: castSession?.remoteMediaClient?.mediaQueue?.let { queue ->
            (0 until queue.itemCount).mapNotNull { index ->
                queue.getItemAtIndex(index)
            }
        } ?: emptyList()
    }

    fun reset() {
        mediaQueue.clear()
        _queueItems.value = emptyList()
        _isLoading.value = false
        _castState.value = CastState.DISCONNECTED
        castProgressJob?.cancel()
        castSession = null
    }

    fun reconnect() {
        if (backend == CastBackend.DIRECT) {
            if (isSessionConnected() && _castState.value != CastState.CONNECTED) onDirectConnected()
            return
        }
        if (!isCastApiAvailable) return
        try {
            castContext = CastContext.getSharedInstance(context.applicationContext)
            castSession = castContext?.sessionManager?.currentCastSession
            if (castSession?.isConnected == true) {
                updateCastState(CastState.CONNECTED)
                startTrackingCastProgress()
                updateQueueItems()
                updateCurrentMedia()

                castSession?.remoteMediaClient?.registerCallback(
                    object : RemoteMediaClient.Callback() {
                        override fun onStatusUpdated() {
                            updateCurrentMedia()
                            updateQueueItems()
                        }

                        override fun onQueueStatusUpdated() {
                            updateQueueItems()
                        }

                        override fun onPreloadStatusUpdated() {
                            updateQueueItems()
                        }
                    },
                )
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    fun startDeviceDiscovery() {
        if (backend == CastBackend.DIRECT) {
            directController?.startDiscovery()
            return
        }
        acquireMulticastLock()
        discoveryRetryJob?.cancel()

        try {
            if (castContext == null) {
                android.util.Log.d("CastDebug", "castContext is null, calling initializeCast()")
                initializeCast()
            }
            val currentCastContext = castContext ?: CastContext.getSharedInstance(context.applicationContext)
            castContext = currentCastContext
            android.util.Log.d("CastDebug", "castContext ready: $currentCastContext")

            val currentSession = currentCastContext.sessionManager.currentCastSession
            android.util.Log.d("CastDebug", "currentSession: $currentSession")
            val selector = androidx.mediarouter.media.MediaRouteSelector.Builder()
                .addControlCategory(
                    com.google.android.gms.cast.CastMediaControlIntent.categoryForCast(
                        com.google.android.gms.cast.CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
                    ),
                )
                .build()
            android.util.Log.d("CastDebug", "selector built: $selector")

            mediaRouterCallback?.let {
                mediaRouter.removeCallback(it)
                mediaRouterCallback = null
            }

            mediaRouterCallback = object : androidx.mediarouter.media.MediaRouter.Callback() {
                override fun onRouteAdded(
                    router: androidx.mediarouter.media.MediaRouter,
                    route: androidx.mediarouter.media.MediaRouter.RouteInfo,
                ) {
                    android.util.Log.d("CastDebug", "onRouteAdded: ${route.name} id=${route.id}")
                    updateDevicesList(currentCastContext.sessionManager.currentCastSession)
                }

                override fun onRouteRemoved(
                    router: androidx.mediarouter.media.MediaRouter,
                    route: androidx.mediarouter.media.MediaRouter.RouteInfo,
                ) {
                    android.util.Log.d("CastDebug", "onRouteRemoved: ${route.name}")
                    updateDevicesList(currentCastContext.sessionManager.currentCastSession)
                }

                override fun onRouteChanged(
                    router: androidx.mediarouter.media.MediaRouter,
                    route: androidx.mediarouter.media.MediaRouter.RouteInfo,
                ) {
                    android.util.Log.d("CastDebug", "onRouteChanged: ${route.name}")
                    updateDevicesList(currentCastContext.sessionManager.currentCastSession)
                }

                override fun onRouteSelected(
                    router: androidx.mediarouter.media.MediaRouter,
                    route: androidx.mediarouter.media.MediaRouter.RouteInfo,
                    reason: Int,
                ) {
                    android.util.Log.d("CastDebug", "onRouteSelected: ${route.name}")
                    updateDevicesList(currentCastContext.sessionManager.currentCastSession)
                }

                override fun onRouteUnselected(
                    router: androidx.mediarouter.media.MediaRouter,
                    route: androidx.mediarouter.media.MediaRouter.RouteInfo,
                    reason: Int,
                ) {
                    android.util.Log.d("CastDebug", "onRouteUnselected: ${route.name}")
                    updateDevicesList(currentCastContext.sessionManager.currentCastSession)
                }
            }.also { callback ->
                mediaRouter.addCallback(
                    selector,
                    callback,
                    androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN or
                        androidx.mediarouter.media.MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY,
                )
            }
            android.util.Log.d("CastDebug", "MediaRouter callback registered, routes: ${mediaRouter.routes.size}")
            mediaRouter.routes.forEach { route ->
                android.util.Log.d(
                    "CastDebug",
                    "  route: name=${route.name}, id=${route.id}, isDefault=${route.isDefault}",
                )
            }

            updateDevicesList(currentSession)

            // Periodically check for routes while discovery is active (e.g. 10 times every 1.5s)
            discoveryRetryJob = activity.lifecycleScope.launch {
                repeat(15) {
                    delay(1500)
                    android.util.Log.d(
                        "CastDebug",
                        "Periodic discovery check (#$it), total routes=${mediaRouter.routes.size}",
                    )
                    updateDevicesList(castContext?.sessionManager?.currentCastSession)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("CastDebug", "startDeviceDiscovery FAILED", e)
            logcat(LogPriority.ERROR) { "Error in startDeviceDiscovery: ${e.message}" }
            if (_castState.value != CastState.CONNECTED) {
                _castState.value = CastState.DISCONNECTED
            }
        }
    }

    private fun updateDevicesList(currentSession: CastSession?) {
        val connectedDeviceId = currentSession?.castDevice?.deviceId

        val newDevices = mediaRouter.routes
            .filter { route ->
                !route.isDefault && !route.isDefaultOrBluetooth
            }
            .map { route ->
                val isConnected = (connectedDeviceId != null && route.id == connectedDeviceId) ||
                    (currentSession?.isConnected == true && route.isSelected)
                CastDevice(
                    id = route.id,
                    name = route.name,
                    isConnected = isConnected,
                    isSelected = isConnected || route.isSelected,
                )
            }
            .distinctBy { it.id }

        android.util.Log.d(
            "CastDebug",
            "updateDevicesList: found ${newDevices.size} devices (${newDevices.map {
                it.name
            }})",
        )

        if (_availableDevices.value != newDevices) {
            _availableDevices.value = newDevices

            when {
                newDevices.any { it.isConnected } -> _castState.value = CastState.CONNECTED

                _castState.value == CastState.CONNECTING -> { /* keep waiting */ }

                newDevices.isEmpty() && _castState.value != CastState.DISCONNECTED ->
                    _castState.value = CastState.DISCONNECTED
            }
        }
    }

    private var connectionJob: Job? = null

    fun connectToDevice(deviceId: String) {
        if (backend == CastBackend.DIRECT) return connectDirect(deviceId)
        try {
            val route = mediaRouter.routes.find { it.id == deviceId } ?: return
            if (route.id == castSession?.castDevice?.deviceId && castSession?.isConnected == true) return

            _availableDevices.value = _availableDevices.value.map { device ->
                device.copy(isSelected = device.id == deviceId)
            }

            connectionJob?.cancel()
            connectionJob = activity.lifecycleScope.launch {
                try {
                    _castState.value = CastState.CONNECTING
                    mediaRouter.selectRoute(route)

                    var attempts = 0
                    while (attempts < 20) {
                        delay(500)
                        if (castSession?.isConnected == true) {
                            _castState.value = CastState.CONNECTED
                            return@launch
                        }
                        if (_castState.value == CastState.DISCONNECTED) {
                            return@launch
                        }
                        attempts++
                    }

                    if (castSession?.isConnected != true) {
                        _castState.value = CastState.DISCONNECTED
                        showConnectionErrorToast()
                    }
                } catch (e: Exception) {
                    _availableDevices.value = _availableDevices.value.map { device ->
                        device.copy(isSelected = device.isConnected)
                    }
                    logcat(LogPriority.ERROR) { "Error connecting to device: ${e.message}" }
                    _castState.value = CastState.DISCONNECTED
                    showConnectionErrorToast()
                }
            }
        } catch (e: Exception) {
            _availableDevices.value = _availableDevices.value.map { device ->
                device.copy(isSelected = device.isConnected)
            }
            logcat(LogPriority.ERROR) { "Error in connectToDevice: ${e.message}" }
            _castState.value = CastState.DISCONNECTED
            showConnectionErrorToast()
        }
    }

    private fun showConnectionErrorToast() {
        activity.runOnUiThread {
            Toast.makeText(
                context,
                context.stringResource(TLMR.strings.cast_connection_error),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun stop() {
        if (backend == CastBackend.DIRECT) {
            directCommand { it.stopMedia() }
        } else {
            castSession?.remoteMediaClient?.stop()
        }
        _isPlaying.value = false
    }

    fun togglePlayPause() {
        if (backend == CastBackend.DIRECT) {
            val playing = directController?.state?.value?.isPlaying == true
            directCommand { if (playing) it.pause() else it.play() }
            return
        }
        castSession?.remoteMediaClient?.let { client -> if (client.isPlaying) client.pause() else client.play() }
    }

    override fun seekTo(positionSeconds: Double) {
        if (backend == CastBackend.DIRECT) {
            directCommand { it.seek(positionSeconds) }
            return
        }
        castSession?.remoteMediaClient?.seek(
            MediaSeekOptions.Builder().setPosition((positionSeconds * 1000).toLong()).build(),
        )
    }

    override fun setVolume(volume: Float) {
        if (backend == CastBackend.DIRECT) {
            _volume.value = volume.coerceIn(0f, 1f)
            directCommand { it.setVolume(volume.toDouble()) }
            return
        }
        try {
            castSession?.let { session ->
                val newVolume = volume.coerceIn(0f, 1f)
                session.volume = newVolume.toDouble()
                _volume.value = newVolume
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
        }
    }

    private fun updateCurrentMedia() {
        castSession?.remoteMediaClient?.let { client ->
            val mediaInfo = client.mediaInfo
            val metadata = mediaInfo?.metadata

            _currentMedia.value = CastMedia(
                title = metadata?.getString(MediaMetadata.KEY_TITLE)
                    ?: viewModel?.mediaTitle?.value ?: "",
                subtitle = metadata?.getString(MediaMetadata.KEY_SUBTITLE)
                    ?: viewModel?.currentEpisode?.value?.name ?: "",
                thumbnail = metadata?.images?.firstOrNull()?.url?.toString()
                    ?: viewModel?.currentAnime?.value?.thumbnailUrl,
            )
            _isPlaying.value = !client.isPaused
            _volume.value = castSession?.volume?.toFloat() ?: 1f
        }
    }

    fun seekRelative(offset: Int) {
        if (backend == CastBackend.DIRECT) {
            val position = getCurrentCastPosition() / 1000.0
            return seekTo((position + offset).coerceAtLeast(0.0))
        }
        castSession?.remoteMediaClient?.let { client ->
            val newPosition = client.approximateStreamPosition + (offset * 1000)
            client.seek(
                MediaSeekOptions.Builder()
                    .setPosition(newPosition)
                    .build(),
            )
        }
    }

    fun nextVideo() {
        castSession?.remoteMediaClient?.let { client ->
            val queue = client.mediaQueue
            val currentItemId = client.currentItem?.itemId ?: return@let
            val currentIndex = (0 until queue.itemCount).find {
                queue.getItemAtIndex(it)?.itemId == currentItemId
            } ?: return@let

            if (currentIndex < queue.itemCount - 1) {
                client.queueJumpToItem(queue.getItemAtIndex(currentIndex + 1)?.itemId ?: return@let, null)
            }
        }
    }

    fun previousVideo() {
        castSession?.remoteMediaClient?.let { client ->
            val queue = client.mediaQueue
            val currentItemId = client.currentItem?.itemId ?: return@let
            val currentIndex = (0 until queue.itemCount).find {
                queue.getItemAtIndex(it)?.itemId == currentItemId
            } ?: return@let

            if (currentIndex > 0) {
                client.queueJumpToItem(queue.getItemAtIndex(currentIndex - 1)?.itemId ?: return@let, null)
            }
        }
    }

    fun endSession() {
        if (backend == CastBackend.DIRECT) {
            activity.lifecycleScope.launch { directController?.stop() }
            return
        }
        val mSessionManager = castContext!!.sessionManager
        mSessionManager.endCurrentSession(true)
        reset()
        _castState.value = CastState.DISCONNECTED
    }

    private var lastAppliedSettings: SubtitleSettings? = null
    private var subtitleSettingsJob: Job? = null

    fun applySubtitleSettings(settings: SubtitleSettings) {
        subtitleSettingsJob?.cancel()
        if (lastAppliedSettings == settings) {
            logcat(LogPriority.INFO) { "Subtitle settings unchanged, skipping update" }
            return
        }
        lastAppliedSettings = settings

        if (backend == CastBackend.DIRECT) {
            subtitlePreferences.saveTextTrackStyle(settings)
            val activeTracks = directController?.state?.value?.media?.activeTrackIds.orEmpty()
            directCommand { it.setTextTracks(settings.toCastTextStyle(), activeTracks) }
            return
        }

        subtitleSettingsJob = activity.lifecycleScope.launch {
            try {
                val session = castSession ?: run {
                    castContext?.sessionManager?.currentCastSession?.also {
                        castSession = it
                    } ?: run {
                        logcat(LogPriority.ERROR) { "No active cast session found" }
                        null
                    }
                } ?: run {
                    logcat(LogPriority.ERROR) { "Failed to get cast session" }
                    return@launch
                }

                val client = session.remoteMediaClient ?: run {
                    logcat(LogPriority.ERROR) { "No remote media client available" }
                    return@launch
                }
                if (!client.hasMediaSession()) {
                    logcat(LogPriority.ERROR) { "No active media session found" }
                    return@launch
                }

                subtitlePreferences.saveTextTrackStyle(settings)

                val textTrackStyle = settings.toCastTextStyle().toGmsTextTrackStyle().apply {
                    fontStyle = TextTrackStyle.FONT_STYLE_NORMAL
                    setFontFamily(
                        when (settings.fontFamily) {
                            FontFamily.Serif -> "SERIF"
                            FontFamily.Monospace -> "MONOSPACE"
                            FontFamily.Cursive -> "CURSIVE"
                            else -> "SANS_SERIF"
                        },
                    )
                }

                val wasPlaying = client.isPlaying
                val activeTrackIds = client.mediaStatus?.activeTrackIds ?: longArrayOf()

                val styleApplied = suspendCancellableCoroutine<Boolean> { continuation ->
                    val task = client.setTextTrackStyle(textTrackStyle)
                    task.setResultCallback { result ->
                        if (continuation.isActive) {
                            val success = result.status.isSuccess
                            continuation.resume(success)
                        }
                    }
                    continuation.invokeOnCancellation {
                        task.cancel()
                    }
                }

                if (!styleApplied) {
                    logcat(LogPriority.ERROR) { "Failed to apply text track style" }
                    return@launch
                }

                if (activeTrackIds.isNotEmpty()) {
                    val tracksDisabled = suspendCancellableCoroutine<Boolean> { continuation ->
                        val task = client.setActiveMediaTracks(longArrayOf())
                        task.setResultCallback { result ->
                            if (continuation.isActive) {
                                val success = result.status.isSuccess
                                continuation.resume(success)
                            }
                        }
                        continuation.invokeOnCancellation {
                            task.cancel()
                        }
                    }

                    if (!tracksDisabled) {
                        logcat(LogPriority.ERROR) { "Failed to disable tracks" }
                    } else {
                        delay(500)
                        val tracksEnabled = suspendCancellableCoroutine<Boolean> { continuation ->
                            val task = client.setActiveMediaTracks(activeTrackIds)
                            task.setResultCallback { result ->
                                if (continuation.isActive) {
                                    val success = result.status.isSuccess
                                    continuation.resume(success)
                                }
                            }
                            continuation.invokeOnCancellation {
                                task.cancel()
                            }
                        }

                        if (!tracksEnabled) {
                            logcat(LogPriority.ERROR) { "Failed to re-enable tracks" }
                        } else if (wasPlaying) {
                            delay(100)
                            client.play()
                            logcat(LogPriority.INFO) { "Playback resumed" }
                        }
                    }
                } else {
                    logcat(LogPriority.INFO) { "No active tracks found, skipping refresh" }
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Error applying subtitle settings: ${e.message}" }
                e.printStackTrace()
            } finally {
                subtitleSettingsJob = null
            }
        }
    }

    // region Direct (Play-services-free) backend

    private var reportedReceiverError: Int? = null

    private fun observeDirectSession() {
        val controller = directController ?: return
        combine(controller.devices, controller.device, controller.state) { devices, current, state ->
            val connectedId = current?.id?.takeIf { state?.phase == DirectCastState.Phase.CONNECTED }
            val all = if (current != null && devices.none { it.id == current.id }) devices + current else devices
            all.map { device ->
                CastDevice(
                    id = device.id,
                    name = device.name,
                    isConnected = device.id == connectedId,
                    isSelected = device.id == current?.id,
                )
            }
        }.onEach { _availableDevices.value = it }.launchIn(activity.lifecycleScope)

        controller.state.onEach { state ->
            val connected = state?.phase == DirectCastState.Phase.CONNECTED
            when {
                connected && _castState.value != CastState.CONNECTED -> onDirectConnected()
                !connected && _castState.value == CastState.CONNECTED -> onSessionEnded()
            }
            if (state != null) onDirectStatus(state)
        }.launchIn(activity.lifecycleScope)
    }

    private fun onDirectConnected() {
        updateCastState(CastState.CONNECTED)
        startTrackingCastProgress()
        // A session we just started has nothing loaded: send what the player shows now.
        if (directController?.state?.value?.media == null) loadRemoteMedia()
    }

    private fun onDirectStatus(state: DirectCastState) {
        val media = state.media
        _isPlaying.value = state.isPlaying
        state.volumeLevel?.let { _volume.value = it.toFloat() }
        _currentMedia.value = CastMedia(
            title = media?.title ?: viewModel?.currentAnime?.value?.title.orEmpty(),
            subtitle = media?.subtitle ?: viewModel?.currentEpisode?.value?.name.orEmpty(),
            thumbnail = media?.imageUrl ?: viewModel?.currentAnime?.value?.thumbnailUrl,
        )
        if (media?.idleReason == "ERROR" && reportedReceiverError != media.mediaSessionId) {
            reportedReceiverError = media.mediaSessionId
            showLoadErrorToast()
        }
    }

    private fun connectDirect(deviceId: String) {
        val controller = directController ?: return
        val device = controller.devices.value.firstOrNull { it.id == deviceId } ?: return
        if (controller.device.value?.id == deviceId && isSessionConnected()) return
        connectionJob?.cancel()
        connectionJob = activity.lifecycleScope.launch {
            _castState.value = CastState.CONNECTING
            try {
                controller.connect(device)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Direct cast: could not connect to ${device.name} (${device.host})" }
                _castState.value = CastState.DISCONNECTED
                showConnectionErrorToast()
            }
        }
    }

    /** Adds a receiver by IP for networks where discovery finds nothing. Returns false if none answered. */
    suspend fun addDeviceByIp(host: String): Boolean = directController?.addDeviceByIp(host) != null

    private fun loadDirectMedia() {
        val session = directController?.session?.value ?: return
        val viewModel = viewModel ?: return
        if (isLoadingMedia) return
        isLoadingMedia = true
        _isLoading.value = true
        activity.lifecycleScope.launch {
            try {
                val (hosterIndex, videoIndex) = viewModel.selectedHosterVideoIndex.value
                val hoster = viewModel.hosterState.value.getOrNull(hosterIndex) as? HosterState.Ready
                val video = hoster?.videoList?.getOrNull(videoIndex) ?: return@launch
                val request = buildPlayerRequest(
                    video = video,
                    lanHost = session.localAddress.hostAddress ?: return@launch,
                    autoplay = true,
                    textStyle = getDefaultSubtitleSettings().toCastTextStyle(),
                )
                session.load(request)
                recordNowPlaying(request)
                // Speed is not part of LOAD; carry over the one the player is set to.
                mpv?.getPropertyDouble("speed")?.takeIf { it != 1.0 }?.let { setSpeed(it.toFloat()) }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Direct cast: load failed" }
                showToast(context.stringResource(TLMR.strings.cast_receiver_error, e.message.orEmpty()))
            } finally {
                isLoadingMedia = false
                _isLoading.value = false
            }
        }
    }

    private fun directCommand(command: suspend (DirectCastSession) -> Unit) {
        val session = directController?.session?.value ?: return
        activity.lifecycleScope.launch {
            try {
                command(session)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e) { "Direct cast command failed" }
            }
        }
    }

    // endregion

    private fun showToast(message: String) {
        activity.runOnUiThread { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    }

    fun getDefaultSubtitleSettings(): SubtitleSettings = subtitlePreferences.subtitleSettings()
}
