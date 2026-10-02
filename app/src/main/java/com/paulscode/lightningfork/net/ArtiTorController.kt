package com.paulscode.lightningfork.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.InetSocketAddress

/**
 * [TorController] backed by the embedded Arti client. Starts the native SOCKS
 * proxy, waits for bootstrap, and exposes 127.0.0.1:<port>. A failed start can
 * be retried; concurrent callers share one start.
 */
class ArtiTorController(
    context: Context,
    private val bootstrapTimeoutMs: Long = 120_000,
) : TorController {
    private val appContext = context.applicationContext
    private val _status = MutableStateFlow(TorStatus.Stopped)
    override val status: StateFlow<TorStatus> = _status

    private val _progress = MutableStateFlow(0)
    override val progress: StateFlow<Int> = _progress

    @Volatile private var port: Int = -1
    private val starting = Mutex()

    override suspend fun start() {
        if (_status.value == TorStatus.Ready) return
        starting.withLock {
            if (_status.value == TorStatus.Ready) return
            _status.value = TorStatus.Bootstrapping
            // Any failure to load or bootstrap Arti degrades to Failed; a native
            // error must never escape and crash the app.
            try {
                if (!ArtiNative.ensureLoaded()) {
                    _status.value = TorStatus.Failed
                    return
                }
                val stateDir = File(appContext.filesDir, "arti/state").apply { mkdirs() }.absolutePath
                val cacheDir = File(appContext.cacheDir, "arti/cache").apply { mkdirs() }.absolutePath
                val p = withContext(Dispatchers.IO) { ArtiNative.nativeStart(stateDir, cacheDir) }
                if (p <= 0) {
                    _status.value = TorStatus.Failed
                    return
                }
                port = p
                val ready = withTimeoutOrNull(bootstrapTimeoutMs) {
                    while (true) {
                        val pct = ArtiNative.nativeBootstrapPercent()
                        if (pct < 0) return@withTimeoutOrNull false
                        _progress.value = pct
                        if (pct >= 100) return@withTimeoutOrNull true
                        delay(400)
                    }
                    @Suppress("UNREACHABLE_CODE") false
                }
                _status.value = if (ready == true) TorStatus.Ready else TorStatus.Failed
            } catch (t: Throwable) {
                _status.value = TorStatus.Failed
            }
        }
    }

    // Arti runs for the life of the process; stopping only withholds the proxy.
    override fun stop() { _status.value = TorStatus.Stopped }

    override fun socksAddress(): InetSocketAddress? =
        if (_status.value == TorStatus.Ready && port > 0) InetSocketAddress("127.0.0.1", port) else null
}
