package com.paulscode.lightningfork.wallet

import com.paulscode.lightningfork.R
import com.paulscode.lightningfork.data.SettingsStore
import com.paulscode.lightningfork.net.ApiException
import com.paulscode.lightningfork.net.AppStrings
import com.paulscode.lightningfork.net.CertificateChangedException
import com.paulscode.lightningfork.net.KeyUnavailableException
import kotlinx.coroutines.CancellationException
import com.paulscode.lightningfork.net.CaPinning
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why a paired phone can no longer use its node. */
enum class Repair {
    /** The node no longer accepts the key: removed in the dashboard. */
    Removed,
    /** The Keystore key that protects the device key is gone. */
    KeyLost,
    /** The node's certificate no longer chains to the root pinned at pairing. */
    CertificateChanged,
}

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
    /** Why this phone must pair again, if it must. The user is asked first. */
    val repair: Repair? = null,
    /** The price of one BTCB2 in [fiatCurrency], when known and wanted. */
    val fiatPrice: Double? = null,
    /** The currency [fiatPrice] is in. */
    val fiatCurrency: String = "USD",
    /** The dashboard's /bootstrap: what it can do and its SHA256 invoice service; null until read. */
    val dashboard: com.paulscode.lightningfork.net.BootstrapResponse? = null,
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
    /** The app's own words for [WalletState.error]; the node's own are passed on. */
    private val strings: AppStrings = AppStrings.English,
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
    @Volatile private var generation = 0
    private var certFailures = 0
    private val refreshing = Mutex()
    private var lastPriceMs = 0L
    private var failuresInRow = 0
    private var lastNodeMs = 0L

    private val _foreground = MutableStateFlow(false)
    /** Whether the app is in front; screens that poll wait while it is not. */
    val foreground: StateFlow<Boolean> = _foreground

    /** Poll while the app is in the foreground. */
    fun setForeground(foreground: Boolean) {
        _foreground.value = foreground
        if (foreground) {
            if (poller?.isActive == true) return
            transport.warmUp()
            poller = scope.launch {
                while (isActive) {
                    val ok = refresh()
                    failuresInRow = if (ok) 0 else failuresInRow + 1
                    when {
                        // Tor is still starting: try again the moment it is
                        // up, rather than a fixed while later.
                        !ok && tor.status.value == TorStatus.Bootstrapping ->
                            withTimeoutOrNull(120_000) {
                                tor.status.first { it != TorStatus.Bootstrapping }
                            }
                        // A first failure is often the first onion
                        // connection taking its time: try again soon.
                        !ok && failuresInRow == 1 -> delay(3_000)
                        // Back off while the node can't be reached; Tor is slower.
                        !ok -> delay(20_000)
                        transport.route.value == Route.Tor -> delay(20_000)
                        else -> delay(10_000)
                    }
                }
            }
        } else {
            poller?.cancel()
            poller = null
        }
    }

    /** Refresh now (pull to refresh, after a payment). Returns whether it worked. */
    suspend fun refresh(): Boolean {
        if (_state.value.repair != null) return false
        val gen = generation
        return refreshing.withLock {
            if (gen != generation) return@withLock false
            _state.update {
                it.copy(
                    refreshing = true,
                    connection = if (it.wallet == null || it.connection == Connection.Offline) Connection.Connecting else it.connection,
                )
            }
            try {
                val wallet = api.wallet()
                // Unpaired while this was on its way: drop it.
                if (gen != generation) return@withLock false
                val now = System.currentTimeMillis()
                certFailures = 0
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
            } catch (e: CancellationException) {
                // Left the foreground mid-refresh: not a failure to show.
                _state.update { it.copy(refreshing = false) }
                throw e
            } catch (e: ApiException) {
                if (gen != generation) return@withLock false
                // A 401 is checked once more before the phone is called
                // removed: the user is then asked, never unpaired silently.
                val removed = e.status == 401 && confirmRevoked()
                _state.update {
                    it.copy(
                        refreshing = false,
                        error = e.message,
                        repair = if (removed) Repair.Removed else null,
                        connection = if (transport.route.value == Route.Tor) Connection.Tor else Connection.Lan,
                    )
                }
                false
            } catch (e: KeyUnavailableException) {
                _state.update {
                    it.copy(refreshing = false, error = e.message, repair = if (e.lost) Repair.KeyLost else null)
                }
                false
            } catch (e: CertificateChangedException) {
                // One failure could be a network in the way; three in a row
                // across a minute or more is the node's certificate.
                certFailures++
                _state.update {
                    it.copy(
                        refreshing = false,
                        error = e.message,
                        connection = Connection.Offline,
                        repair = if (certFailures >= 3) Repair.CertificateChanged else null,
                    )
                }
                false
            } catch (e: Exception) {
                if (gen != generation) return@withLock false
                // Tor starting, or its first connection to the node still
                // being made, is connecting, not failing: said as such, for
                // the first tries.
                val stillConnecting = tor.status.value == TorStatus.Bootstrapping ||
                    (tor.status.value == TorStatus.Ready && failuresInRow < 2 && transport.route.value != Route.Lan)
                val message = when {
                    tor.status.value == TorStatus.Failed -> strings.get(R.string.app_wallet_tor_failed)
                    stillConnecting -> strings.get(R.string.app_wallet_connecting_tor)
                    else -> strings.get(R.string.app_unreachable)
                }
                _state.update {
                    it.copy(
                        refreshing = false,
                        error = message,
                        connection = if (stillConnecting) Connection.Connecting else Connection.Offline,
                    )
                }
                false
            }
        }
    }

    private suspend fun confirmRevoked(): Boolean {
        kotlinx.coroutines.delay(2_000)
        return try {
            api.wallet()
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            e.status == 401
        } catch (e: Exception) {
            false
        }
    }

    /** Back to normal after the user chose to try again. */
    fun clearRepair() {
        certFailures = 0
        _state.update { it.copy(repair = null, error = null) }
    }

    private suspend fun refreshNode() {
        val genAtStart = generation
        runCatching { api.bootstrap() }.onSuccess { boot ->
            if (generation != genAtStart) return@onSuccess
            lastNodeMs = System.currentTimeMillis()
            settings.node = boot.node
            _state.update { it.copy(node = boot.node, dashboard = boot) }
        }
        // Learn the node's addresses afresh: a phone paired over Tor learns its
        // LAN address, and the other way round.
        val gen = generation
        runCatching { api.endpoints() }.onSuccess { fresh ->
            if (gen != generation) return@onSuccess
            val current = settings.endpoints
            // Over the onion (which authenticates the node) the node names a
            // root other than the pinned one: its certificate authority
            // changed, as after a restore to another server. The LAN would
            // only fail; say so rather than stay on Tor without a word.
            val pinned = current.caSha256
            if (transport.route.value == Route.Tor && pinned != null && fresh.caSha256 != null &&
                pinned.replace(":", "").uppercase() != fresh.caSha256.replace(":", "").uppercase()
            ) {
                _state.update { it.copy(repair = Repair.CertificateChanged) }
                return@onSuccess
            }
            // A root is adopted only if it hashes to the fingerprint the user
            // paired with; one paired over an http onion without a fingerprint
            // takes the root the node hands over that authenticated channel.
            val adopt = fresh.caPem?.takeIf { pem ->
                current.caPem == null && runCatching {
                    val cert = CaPinning.parsePem(pem)
                    val expected = current.caSha256 ?: fresh.caSha256
                    expected != null && CaPinning.matchesFingerprint(cert, expected)
                }.getOrDefault(false)
            }
            val merged = current.copy(
                onionUrl = fresh.onionUrl ?: current.onionUrl,
                lanUrl = fresh.lanUrl ?: current.lanUrl,
                lanIp = fresh.lanIp ?: current.lanIp,
                caPem = current.caPem ?: adopt,
                caSha256 = current.caSha256 ?: adopt?.let { fresh.caSha256 },
            )
            if (merged != current) {
                settings.endpoints = merged
                transport.endpoints = merged
            }
        }
    }

    /**
     * The price in the chosen currency, or the phone's own; in dollars when
     * the node has no quote in that one now (dollars need no conversion), so
     * an estimate never wears a symbol its number isn't in.
     */
    private suspend fun refreshPrice() {
        val wanted = wantedCurrency()
        for (code in listOf(wanted, "USD").distinct()) {
            val p = runCatching { api.price(code) }.getOrNull() ?: continue
            lastPriceMs = System.currentTimeMillis()
            if (p.price != null && p.price > 0) {
                _state.update { it.copy(fiatPrice = p.price, fiatCurrency = p.currency.ifBlank { code }) }
                return
            }
        }
    }

    private fun wantedCurrency(): String =
        settings.fiatCurrency ?: runCatching { java.util.Currency.getInstance(java.util.Locale.getDefault()).currencyCode }.getOrNull() ?: "USD"

    fun setShowFiat(show: Boolean) {
        settings.showFiat = show
        if (!show) _state.update { it.copy(fiatPrice = null) } else lastPriceMs = 0
    }

    /** A currency chosen in Settings, or null for the phone's own; asked anew at once. */
    fun setFiatCurrency(code: String?) {
        settings.fiatCurrency = code
        lastPriceMs = 0
        _state.update { it.copy(fiatPrice = null) }
    }

    fun reset() {
        generation++
        poller?.cancel()
        poller = null
        lastNodeMs = 0
        lastPriceMs = 0
        certFailures = 0
        _state.value = WalletState()
    }
}
