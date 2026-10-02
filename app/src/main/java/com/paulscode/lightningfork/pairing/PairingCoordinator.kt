package com.paulscode.lightningfork.pairing

import com.paulscode.lightningfork.crypto.SecretStore
import com.paulscode.lightningfork.data.SettingsStore
import com.paulscode.lightningfork.net.ApiException
import com.paulscode.lightningfork.net.ApiJson
import com.paulscode.lightningfork.net.CaPinning
import com.paulscode.lightningfork.net.NodeApi
import com.paulscode.lightningfork.net.PairRequest
import com.paulscode.lightningfork.net.PairingPayload
import com.paulscode.lightningfork.net.ServerEndpoints
import com.paulscode.lightningfork.net.TorController
import com.paulscode.lightningfork.net.TorStatus
import com.paulscode.lightningfork.net.Transport

/** Reading the dashboard's pairing code. */
object PairingCodes {
    /** The pairing payload in a scanned or typed text, or null. */
    fun parse(text: String): PairingPayload? =
        runCatching { ApiJson.decodeFromString(PairingPayload.serializer(), text.trim()) }
            .getOrNull()
            ?.takeIf { it.lf == 1 && it.code.startsWith("e_") && it.code.length in 10..80 }

    /** Whether the code has run out, by the phone's clock. */
    fun isExpired(payload: PairingPayload, nowMs: Long): Boolean =
        payload.exp != null && payload.exp < nowMs
}

/** Coarse progress for the pairing screen. */
enum class PairPhase { Reaching, StartingTor, Claiming, Finishing, Done }

sealed interface PairResult {
    data class Success(val alias: String) : PairResult
    data class Failure(val message: String, val recoverable: Boolean) : PairResult
}

/**
 * First launch: read the dashboard's QR code, reach the node, pin its root
 * certificate by the code's fingerprint, claim the one-time code for a device
 * key, and keep what is needed to reach the node again.
 *
 * On the LAN the root is captured from the TLS handshake and accepted only if
 * it matches the fingerprint, so pairing at home needs no Tor. Away from it,
 * pairing goes over the onion, which authenticates the node by its address.
 */
class PairingCoordinator(
    private val transport: Transport,
    private val api: NodeApi,
    private val tor: TorController,
    private val secrets: SecretStore,
    private val settings: SettingsStore,
) {
    // One nonce per code, reused if pairing with it is tried again.
    private val nonces = mutableMapOf<String, String>()

    fun parsePayload(text: String): PairingPayload? = PairingCodes.parse(text)

    fun isExpired(payload: PairingPayload, nowMs: Long = System.currentTimeMillis()): Boolean =
        PairingCodes.isExpired(payload, nowMs)

    suspend fun pair(
        payload: PairingPayload,
        label: String,
        onProgress: (PairPhase) -> Unit = {},
    ): PairResult {
        if (payload.onion == null && payload.lan == null && payload.ip == null) {
            return PairResult.Failure("This code doesn't say how to reach the node.", recoverable = false)
        }
        transport.endpoints = ServerEndpoints(
            onionUrl = payload.onion,
            lanUrl = payload.lan,
            lanIp = payload.ip,
            caSha256 = payload.ca,
            caPem = null,
        )
        try {
            onProgress(PairPhase.Reaching)
            if (payload.ca != null) {
                for (url in listOfNotNull(payload.lan, payload.ip)) {
                    val pem = transport.acquireRootPem(url, payload.ca) ?: continue
                    transport.endpoints = transport.endpoints.copy(caPem = pem)
                    break
                }
            }
            val onLan = transport.endpoints.caPem != null
            if (!onLan && payload.onion == null) {
                return PairResult.Failure(
                    "Can't reach your node from this network. Connect to the same Wi-Fi as your node and try again.",
                    recoverable = true,
                )
            }
            if (!onLan && tor.status.value != TorStatus.Ready) {
                onProgress(PairPhase.StartingTor)
                tor.start()
                if (tor.status.value != TorStatus.Ready) {
                    return PairResult.Failure("Tor could not start. Check the connection and try again.", recoverable = true)
                }
            }
            // An https onion is pinned too: capture its root over Tor, by the
            // same fingerprint.
            if (!onLan && payload.onion!!.startsWith("https://")) {
                val pem = payload.ca?.let { transport.acquireRootPem(payload.onion, it, viaTor = true) }
                    ?: return PairResult.Failure(
                        "Can't reach your node over Tor right now. Try again, or pair on the same Wi-Fi as your node.",
                        recoverable = true,
                    )
                transport.endpoints = transport.endpoints.copy(caPem = pem)
            }

            onProgress(PairPhase.Claiming)
            val nonce = synchronized(nonces) {
                nonces.getOrPut(payload.code) {
                    val bytes = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
                    android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                }
            }
            val paired = api.pair(PairRequest(payload.code, label, nonce))

            // The root from the answer, or the one captured over LAN; either
            // must match the QR code before it is trusted.
            val caPem = paired.caPem ?: transport.endpoints.caPem
            if (caPem != null && payload.ca != null &&
                !CaPinning.matchesFingerprint(CaPinning.parsePem(caPem), payload.ca)
            ) {
                return PairResult.Failure(
                    "The node's certificate does not match the code. Pairing was stopped.",
                    recoverable = false,
                )
            }

            onProgress(PairPhase.Finishing)
            secrets.putApiKey(paired.apiKey)
            val endpoints = ServerEndpoints(
                onionUrl = payload.onion,
                lanUrl = payload.lan,
                lanIp = payload.ip,
                caSha256 = payload.ca ?: paired.caSha256,
                caPem = caPem,
            )
            settings.endpoints = endpoints
            transport.endpoints = endpoints
            settings.deviceLabel = paired.label.ifBlank { label }
            settings.deviceId = paired.deviceId
            settings.node = paired.node
            // Last, so a half-finished pairing never reads as paired.
            settings.serverId = paired.serverId
            onProgress(PairPhase.Done)
            return PairResult.Success(paired.node?.alias.orEmpty())
        } catch (e: ApiException) {
            val msg = when (e.status) {
                401 -> "This code has expired or was already used. Make a new one in the dashboard."
                429 -> "Too many attempts. Wait a few minutes and try again."
                else -> e.message
            }
            return PairResult.Failure(msg, recoverable = e.status != 401)
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return PairResult.Failure(
                "Can't reach your node. Check that this phone is online, then try again.",
                recoverable = true,
            )
        }
    }
}
