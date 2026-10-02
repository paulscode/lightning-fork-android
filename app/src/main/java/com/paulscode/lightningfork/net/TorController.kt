package com.paulscode.lightningfork.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Keeps all of Tor behind one seam, so the engine (embedded Arti, or an
 * external SOCKS proxy for development) can change without touching the rest.
 */
interface TorController {
    val status: StateFlow<TorStatus>

    /** Bootstrap progress 0..100 while [status] is Bootstrapping. */
    val progress: StateFlow<Int>

    /** Bootstrap a working Tor client; returns when Ready or Failed. */
    suspend fun start()

    fun stop()

    /** After a failed request: notice a client that died after starting. */
    fun checkHealth() {}

    /** SOCKS5 address once [status] is [TorStatus.Ready], else null. */
    fun socksAddress(): InetSocketAddress?

    /** A proxy for `.onion` requests once ready, else null. */
    fun proxy(): Proxy? = socksAddress()?.let { Proxy(Proxy.Type.SOCKS, it) }
}

enum class TorStatus { Stopped, Bootstrapping, Ready, Failed }

/** An already-running SOCKS proxy (Orbot, a desktop Tor), for development. */
class ExternalSocksTorController(
    private val host: String = "127.0.0.1",
    private val port: Int = 9050,
) : TorController {
    private val _status = MutableStateFlow(TorStatus.Stopped)
    override val status: StateFlow<TorStatus> = _status
    override val progress: StateFlow<Int> = MutableStateFlow(100)

    override suspend fun start() { _status.value = TorStatus.Ready }

    override fun stop() { _status.value = TorStatus.Stopped }

    override fun socksAddress(): InetSocketAddress? =
        if (_status.value == TorStatus.Ready) InetSocketAddress(host, port) else null
}
