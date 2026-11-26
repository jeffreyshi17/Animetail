package eu.kanade.tachiyomi.ui.player.cast.direct

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ext.SdkExtensions
import eu.kanade.tachiyomi.ui.player.cast.LanAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.coroutines.resume

/** A Chromecast / Google TV / cast-enabled speaker reachable on the LAN. */
data class DirectCastDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = CastV2Channel.DEFAULT_PORT,
    val model: String? = null,
)

/**
 * Finds cast receivers without Google Play services: Android's mDNS ([NsdManager]) for
 * `_googlecast._tcp`, then, if that finds nothing (multicast is filtered on some networks and
 * Android containers), a scan of the local /24 for the CastV2 port.
 */
class CastDeviceDiscovery(private val context: Context) {

    private val nsdManager by lazy { context.getSystemService(Context.NSD_SERVICE) as NsdManager }

    private val _devices = MutableStateFlow<List<DirectCastDevice>>(emptyList())
    val devices: StateFlow<List<DirectCastDevice>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var scanJob: Job? = null
    private val resolveMutex = Mutex()

    fun start(scope: CoroutineScope) {
        stop()
        _isScanning.value = true
        startNsd(scope)
        // On the caller's (main) scope, so the NSD listener is only touched from one thread.
        scanJob = scope.launch {
            delay(NSD_GRACE_MS)
            if (_devices.value.isEmpty()) {
                logcat(LogPriority.INFO) { "Cast discovery: mDNS found nothing, scanning the subnet" }
                scanSubnet()
            }
            delay(SCAN_DURATION_MS)
            // Found devices stay listed; reopening the cast sheet scans again.
            stopNsd()
            _isScanning.value = false
        }
    }

    fun stop() {
        scanJob?.cancel()
        scanJob = null
        stopNsd()
        _isScanning.value = false
    }

    private fun stopNsd() {
        listener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        listener = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    /** Adds a receiver typed in by IP, after checking it answers on the CastV2 port. */
    suspend fun addManual(host: String): DirectCastDevice? = withContext(Dispatchers.IO) {
        if (!probe(host, MANUAL_PROBE_TIMEOUT_MS)) return@withContext null
        val device = DirectCastDevice(id = "ip:$host", name = fetchName(host) ?: host, host = host)
        add(device)
        device
    }

    private fun add(device: DirectCastDevice) {
        _devices.update { current ->
            // Speaker groups share their leader's host on another port, so hosts alone are not unique.
            val others = current.filterNot { it.id == device.id || (it.host == device.host && it.port == device.port) }
            (others + device).sortedBy { it.name }
        }
    }

    private fun startNsd(scope: CoroutineScope) {
        multicastLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createMulticastLock("animetail_direct_cast")
            ?.apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                scope.launch(Dispatchers.IO) { resolve(serviceInfo)?.let(::add) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                logcat(LogPriority.WARN) { "Cast discovery: mDNS failed to start ($errorCode)" }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
            listener = discoveryListener
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Cast discovery: mDNS unavailable" }
        }
    }

    /** Older NsdManagers resolve one service at a time, so resolutions are serialised. */
    @Suppress("DEPRECATION")
    private suspend fun resolve(found: NsdServiceInfo): DirectCastDevice? = resolveMutex.withLock {
        val resolved = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
            suspendCancellableCoroutine<NsdServiceInfo?> { continuation ->
                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        if (continuation.isActive) continuation.resume(serviceInfo)
                    }
                }
                runCatching { nsdManager.resolveService(found, resolveListener) }
                    .onFailure { if (continuation.isActive) continuation.resume(null) }
                continuation.invokeOnCancellation {
                    if (supportsStopResolution()) runCatching { nsdManager.stopServiceResolution(resolveListener) }
                }
            }
        } ?: return@withLock null

        val host = resolved.host?.hostAddress ?: return@withLock null
        val txt = resolved.attributes.mapValues { (_, value) -> value?.decodeToString() }
        if (!hasVideoOutput(txt["ca"])) return@withLock null
        DirectCastDevice(
            id = txt["id"] ?: resolved.serviceName,
            name = txt["fn"] ?: resolved.serviceName,
            host = host,
            port = resolved.port.takeIf { it > 0 } ?: CastV2Channel.DEFAULT_PORT,
            model = txt["md"],
        )
    }

    /** Bit 0 of the `ca` capability mask is video output; Nest/Home speakers lack it. */
    private fun hasVideoOutput(capabilities: String?): Boolean =
        capabilities?.toIntOrNull()?.let { it and 1 == 1 } ?: true

    private fun supportsStopResolution(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
        (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                SdkExtensions.getExtensionVersion(Build.VERSION_CODES.TIRAMISU) >= 7
            )

    private suspend fun scanSubnet(): Unit = withContext(Dispatchers.IO) {
        val own = LanAddress.current() ?: return@withContext
        val prefix = own.address.substringBeforeLast('.')
        val permits = Semaphore(SCAN_PARALLELISM)
        (1..254).map { "$prefix.$it" }
            .filter { it != own.address && _devices.value.none { device -> device.host == it } }
            .map { host ->
                async {
                    permits.withPermit {
                        if (probe(host, SCAN_PROBE_TIMEOUT_MS)) {
                            add(DirectCastDevice(id = "ip:$host", name = fetchName(host) ?: host, host = host))
                        }
                    }
                }
            }
            .awaitAll()
    }

    private fun probe(host: String, timeoutMs: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, CastV2Channel.DEFAULT_PORT), timeoutMs) }
        true
    }.getOrDefault(false)

    /** Cast devices still answer this unauthenticated setup endpoint with their friendly name. */
    private fun fetchName(host: String): String? = runCatching {
        val connection = URL("http://$host:8008/setup/eureka_info?params=name").openConnection() as HttpURLConnection
        connection.connectTimeout = 1_000
        connection.readTimeout = 1_000
        connection.inputStream.use { stream ->
            CastV2Protocol.json.parseToJsonElement(stream.readBytes().decodeToString())
                .jsonObject["name"]?.jsonPrimitive?.content
        }
    }.getOrNull()

    private companion object {
        const val SERVICE_TYPE = "_googlecast._tcp"
        const val NSD_GRACE_MS = 3_000L
        const val SCAN_DURATION_MS = 7_000L
        const val RESOLVE_TIMEOUT_MS = 5_000L
        const val SCAN_PROBE_TIMEOUT_MS = 400
        const val MANUAL_PROBE_TIMEOUT_MS = 2_000
        const val SCAN_PARALLELISM = 48
    }
}
