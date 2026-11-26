package eu.kanade.tachiyomi.ui.player.cast.direct

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.ui.player.cast.proxy.CastProxyHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat

/**
 * Owns the direct (Play-services-free) cast session for the whole app, so casting survives leaving
 * the player, like a Google Cast session does.
 */
@Inject
@SingleIn(AppScope::class)
class DirectCastController(
    context: Context,
    private val proxyHolder: CastProxyHolder,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val discovery = CastDeviceDiscovery(context.applicationContext)

    val devices: StateFlow<List<DirectCastDevice>> = discovery.devices
    val isScanning: StateFlow<Boolean> = discovery.isScanning

    private val _session = MutableStateFlow<DirectCastSession?>(null)
    val session: StateFlow<DirectCastSession?> = _session.asStateFlow()

    private val _device = MutableStateFlow<DirectCastDevice?>(null)

    /** The device of the current or connecting session. */
    val device: StateFlow<DirectCastDevice?> = _device.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<DirectCastState?> = _session
        .flatMapLatest { it?.state ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun startDiscovery() = discovery.start(scope)

    fun stopDiscovery() = discovery.stop()

    suspend fun addDeviceByIp(host: String): DirectCastDevice? = discovery.addManual(host.trim())

    /** Connects to [device], replacing any current session. Throws when the receiver cannot be reached. */
    suspend fun connect(device: DirectCastDevice): DirectCastSession {
        _session.value?.disconnect("Switched device")
        _session.value = null
        _device.value = device
        return try {
            DirectCastSession.connect(device.host, device.port).also { session ->
                _session.value = session
                discovery.stop()
                scope.launch {
                    val ended = session.awaitEnd()
                    logcat(LogPriority.INFO) { "Direct cast session ended: ${ended.endReason}" }
                    if (_session.value === session) {
                        _session.value = null
                        _device.value = null
                        // Proxy URLs handed to this receiver stop working with the session.
                        proxyHolder.proxy.clear()
                    }
                }
            }
        } catch (e: Throwable) {
            _device.value = null
            throw e
        }
    }

    /** Stops playback on the receiver and closes its media app. */
    suspend fun stop() {
        _session.value?.stop()
    }
}
