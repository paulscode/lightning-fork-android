package com.paulscode.lightningfork

import android.content.Context
import android.util.Log
import com.paulscode.lightningfork.crypto.SecretStore
import com.paulscode.lightningfork.data.SettingsStore
import com.paulscode.lightningfork.lock.LockController
import com.paulscode.lightningfork.net.ArtiTorController
import com.paulscode.lightningfork.net.NodeApi
import com.paulscode.lightningfork.net.TorController
import com.paulscode.lightningfork.net.Transport
import com.paulscode.lightningfork.pairing.PairingCoordinator
import com.paulscode.lightningfork.wallet.WalletRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Builds the app's long-lived pieces once. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val secrets = SecretStore(appContext)
    val settings = SettingsStore(appContext)
    val tor: TorController = ArtiTorController(appContext)
    val transport = Transport(settings.endpoints, tor) { secrets.readApiKey() }
    val api = NodeApi(transport)
    val pairing = PairingCoordinator(transport, api, tor, secrets, settings)

    /** Outlives screens; a failure in one job never takes the others down. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, t -> if (BuildConfig.DEBUG) Log.e("LfApp", "uncaught", t) },
    )

    val wallet = WalletRepository(api, transport, tor, settings, appScope)
    val lock = LockController()

    /** A pairing code that arrived by link, for the pairing screen to pick up. */
    val pendingPairing = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    val isPaired: Boolean get() = settings.isPaired && secrets.hasApiKey()

    /**
     * Unpair at the user's word: forget the node at once, and tell the node
     * to remove this phone, in the background, with the key taken before it
     * was forgotten (a pairing started meanwhile is not affected).
     */
    fun unpairAndRemove() {
        val key = secrets.getApiKey()
        val endpoints = settings.endpoints
        unpair()
        if (key != null) {
            appScope.launch(Dispatchers.IO) {
                val t = Transport(endpoints, tor) { com.paulscode.lightningfork.crypto.SecretStore.Read.None }
                runCatching { kotlinx.coroutines.withTimeoutOrNull(60_000) { NodeApi(t).unpair(key) } }
            }
        }
    }

    /** Forget the node: the key, the addresses, the cached numbers. */
    fun unpair() {
        wallet.reset()
        secrets.clear()
        settings.clear()
        transport.endpoints = settings.endpoints
    }
}
