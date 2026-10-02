package com.paulscode.lightningfork.wallet

import com.paulscode.lightningfork.data.SettingsStore
import com.paulscode.lightningfork.net.ApiException
import com.paulscode.lightningfork.net.NodeApi
import com.paulscode.lightningfork.net.NodeInfo
import com.paulscode.lightningfork.net.Route
import com.paulscode.lightningfork.net.TorController
import com.paulscode.lightningfork.net.TorStatus
import com.paulscode.lightningfork.net.Transport
import com.paulscode.lightningfork.net.WalletResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How the app is talking to the node. */
enum class Connection { Connecting, Lan, Tor, Offline }

data class WalletState(
    /** The last balances the node gave, kept on screen while a refresh runs. */
    val wallet: WalletResponse? = null,
    /** When [wallet] was fetched (epoch ms), 0 if never. */
    val updatedAtMs: Long = 0,
    val node: NodeInfo? = null,
    val connection: Connection = Connection.Connecting,
    val refreshing: Boolean = false,
    /** Why the last refresh failed, if it did. Shown without hiding the numbers. */
    val error: String? = null,
    /** The node no longer knows this phone's key. */
    val revoked: Boolean = false,
    /** USD per BTC, when known and wanted. */
    val usdPrice: Double? = null,
)

/**
 * The balances on the home screen, refreshed every few seconds while the app is
 * in front and not at all behind. A refresh replaces the numbers only when new
 * ones arrive, so nothing blanks or flickers; a failure keeps the last numbers
 * and says how old they are.
 */
class WalletRepository(
    private val api: NodeApi,
    private val transport: Transport,
    private val tor: TorController,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(
        WalletState(
            wallet = settings.lastWallet,
            updatedAtMs = settings.lastWalletAtMs,
            node = settings.node,
        )
    )
    val state: StateFlow<WalletState> = _state

    private var poller: Job? = null
    private val refreshing = Mutex()
    private var lastPriceMs = 0L
    private var lastNodeMs = 0L

    /** Poll while the app is in the foreground. */
    fun setForeground(foreground: Boolean) {
        if (foreground) {
            if (poller?.isActive == true) return
            poller = scope.launch {
                while (isActive) {
                    val ok = refresh()
                    // Back off while the node can't be reached; Tor is slower.
                    val wait = when {
                        !ok -> 20_000L
                        transport.route.value == Route.Tor -> 20_000L
                        else -> 10_000L
                    }
                    delay(wait)
                }
            }
        } else {
            poller?.cancel()
            poller = null
        }
    }

    /** Refresh now (pull to refresh, after a payment). Returns whether it worked. */
    suspend fun refresh(): Boolean {
        if (_state.value.revoked) return false
        return refreshing.withLock {
            _state.update {
                it.copy(
                    refreshing = true,
                    connection = if (it.wallet == null || it.connection == Connection.Offline) Connection.Connecting else it.connection,
                )
            }
            try {
                val wallet = api.wallet()
                val now = System.currentTimeMillis()
                settings.lastWallet = wallet
                settings.lastWalletAtMs = now
                _state.update {
                    it.copy(
                        wallet = wallet,
                        updatedAtMs = now,
                        refreshing = false,
                        error = null,
                        connection = if (transport.route.value == Route.Tor) Connection.Tor else Connection.Lan,
                    )
                }
                if (now - lastNodeMs > 5 * 60_000) refreshNode()
                if (settings.showFiat && now - lastPriceMs > 5 * 60_000) refreshPrice()
                true
            } catch (e: ApiException) {
                _state.update {
                    it.copy(
                        refreshing = false,
                        error = e.message,
                        revoked = e.status == 401,
                        connection = if (transport.route.value == Route.Tor) Connection.Tor else Connection.Lan,
                    )
                }
                false
            } catch (e: Exception) {
                val torFailed = tor.status.value == TorStatus.Failed
                _state.update {
                    it.copy(
                        refreshing = false,
                        error = if (torFailed) "Can't reach your node, and Tor could not start." else "Can't reach your node right now.",
                        connection = Connection.Offline,
                    )
                }
                false
            }
        }
    }

    private suspend fun refreshNode() {
        runCatching { api.bootstrap() }.onSuccess { boot ->
            lastNodeMs = System.currentTimeMillis()
            settings.node = boot.node
            _state.update { it.copy(node = boot.node) }
        }
        // Learn the node's addresses afresh: a phone paired over Tor learns its
        // LAN address, and the other way round.
        runCatching { api.endpoints() }.onSuccess { fresh ->
            val current = settings.endpoints
            val merged = current.copy(
                onionUrl = fresh.onionUrl ?: current.onionUrl,
                lanUrl = fresh.lanUrl ?: current.lanUrl,
                lanIp = fresh.lanIp ?: current.lanIp,
                // The root only changes with a new fingerprint the user saw; a
                // node that hands over a different one is not trusted here.
                caPem = current.caPem ?: fresh.caPem?.takeIf { fresh.caSha256 == current.caSha256 },
            )
            if (merged != current) {
                settings.endpoints = merged
                transport.endpoints = merged
            }
        }
    }

    private suspend fun refreshPrice() {
        runCatching { api.price("USD") }.onSuccess { p ->
            lastPriceMs = System.currentTimeMillis()
            if (p.price != null && p.price > 0) _state.update { it.copy(usdPrice = p.price) }
        }
    }

    fun setShowFiat(show: Boolean) {
        settings.showFiat = show
        if (!show) _state.update { it.copy(usdPrice = null) } else lastPriceMs = 0
    }

    fun reset() {
        poller?.cancel()
        poller = null
        _state.value = WalletState()
    }
}
