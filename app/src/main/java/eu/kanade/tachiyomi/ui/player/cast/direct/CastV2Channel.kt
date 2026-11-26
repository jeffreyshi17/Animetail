package eu.kanade.tachiyomi.ui.player.cast.direct

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * One TLS connection to a cast receiver: framing, heartbeat, and request/response matching by
 * `requestId`. Higher-level session logic lives in [DirectCastSession].
 */
class CastV2Channel private constructor(
    private val socket: Socket,
    private val scope: CoroutineScope,
    val senderId: String,
) : Closeable {

    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = BufferedOutputStream(socket.getOutputStream())
    private val writeLock = Any()
    private val nextRequestId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<CastV2Protocol.Envelope>>()

    private val _incoming = MutableSharedFlow<CastV2Message>(extraBufferCapacity = 64)

    /** Every message the receiver sends, after heartbeats and matched responses are handled. */
    val incoming: SharedFlow<CastV2Message> = _incoming.asSharedFlow()

    private val _isOpen = MutableStateFlow(true)

    /** False once the socket is gone; [closeCause] says why. */
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    @Volatile
    var closeCause: Throwable? = null
        private set

    /** The phone's address on the interface that reaches the receiver: what the receiver can fetch from. */
    val localAddress: InetAddress get() = socket.localAddress

    @Volatile
    private var lastReceivedAt = System.currentTimeMillis()
    private var readerJob: Job? = null
    private var heartbeatJob: Job? = null

    private fun start() {
        readerJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val message = CastV2Message.readFrame(input)
                    lastReceivedAt = System.currentTimeMillis()
                    handle(message)
                }
            } catch (e: Throwable) {
                shutdown(e)
            }
        }
        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive && _isOpen.value) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (System.currentTimeMillis() - lastReceivedAt > HEARTBEAT_TIMEOUT_MS) {
                    shutdown(IOException("Cast receiver stopped answering heartbeats"))
                    break
                }
                runCatching { send(CastV2Protocol.RECEIVER_ID, CastV2Protocol.NS_HEARTBEAT, CastV2Protocol.ping()) }
            }
        }
    }

    private suspend fun handle(message: CastV2Message) {
        val payload = message.payloadUtf8 ?: return
        if (message.namespace == CastV2Protocol.NS_HEARTBEAT) {
            if (payload.contains("\"PING\"")) {
                send(message.sourceId, CastV2Protocol.NS_HEARTBEAT, CastV2Protocol.pong())
            }
            return
        }
        val envelope = runCatching { CastV2Protocol.envelope(payload) }.getOrNull()
        envelope?.requestId?.takeIf { it != 0 }?.let { requestId ->
            pending.remove(requestId)?.complete(envelope)
        }
        _incoming.emit(message)
    }

    /** Writes one message. Socket writes block, so they always run on [Dispatchers.IO], never the caller's thread. */
    suspend fun send(destinationId: String, namespace: String, payload: String) = withContext(Dispatchers.IO) {
        val message = CastV2Message(
            sourceId = senderId,
            destinationId = destinationId,
            namespace = namespace,
            payloadUtf8 = payload,
        )
        try {
            synchronized(writeLock) { CastV2Message.writeFrame(output, message) }
        } catch (e: IOException) {
            shutdown(e)
            throw e
        }
    }

    /**
     * Sends a request built from a fresh `requestId` and waits for the reply carrying the same id.
     * The caller interprets the reply type (e.g. `LOAD_FAILED` vs `MEDIA_STATUS`).
     */
    suspend fun request(
        destinationId: String,
        namespace: String,
        timeoutMs: Long = REQUEST_TIMEOUT_MS,
        build: (requestId: Int) -> String,
    ): CastV2Protocol.Envelope {
        val requestId = nextRequestId.getAndIncrement()
        val reply = CompletableDeferred<CastV2Protocol.Envelope>()
        pending[requestId] = reply
        try {
            send(destinationId, namespace, build(requestId))
            return withTimeout(timeoutMs) { reply.await() }
        } finally {
            pending.remove(requestId)
        }
    }

    private fun shutdown(cause: Throwable) {
        if (!_isOpen.compareAndSet(true, false)) return
        closeCause = cause
        pending.values.forEach { it.completeExceptionally(cause) }
        pending.clear()
        heartbeatJob?.cancel()
        runCatching { socket.close() }
    }

    override fun close() {
        shutdown(IOException("Closed by sender"))
        readerJob?.cancel()
    }

    companion object {
        const val DEFAULT_PORT = 8009
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val REQUEST_TIMEOUT_MS = 10_000L
        private const val HEARTBEAT_INTERVAL_MS = 5_000L
        private const val HEARTBEAT_TIMEOUT_MS = 20_000L

        /**
         * Opens the TLS socket. Cast receivers present self-signed certificates and the Default Media
         * Receiver does not require sender-side device authentication, so the certificate is not
         * verified; nothing secret travels over this channel.
         */
        suspend fun open(
            host: String,
            port: Int,
            scope: CoroutineScope,
            senderId: String = "sender-${SecureRandom().nextInt(1_000_000)}",
        ): CastV2Channel = withContext(Dispatchers.IO) {
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, arrayOf(AcceptAnyCertificate), SecureRandom())
            }
            val plain = Socket().apply {
                tcpNoDelay = true
                connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            }
            val tls = sslContext.socketFactory.createSocket(plain, host, port, true) as SSLSocket
            tls.soTimeout = 0
            tls.startHandshake()
            CastV2Channel(tls, scope, senderId).also { it.start() }
        }
    }

    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object AcceptAnyCertificate : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
