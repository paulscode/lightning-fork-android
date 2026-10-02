package com.paulscode.lightningfork.net

import android.util.Log
import com.paulscode.lightningfork.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Where the node can be reached, and the root certificate it is pinned to. */
data class ServerEndpoints(
    val onionUrl: String? = null,
    val lanUrl: String? = null,
    val lanIp: String? = null,
    val caPem: String? = null,
    val caSha256: String? = null,
)

/** Which way a request went, for the connection indicator. */
enum class Route { Lan, Tor }

/**
 * The node answered, but not with success. [message] is the node's own
 * sentence for the user when it gave one.
 */
class ApiException(val status: Int, override val message: String) : Exception(message)

/** No way to the node worked. */
class UnreachableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The device key could not be read from the Keystore; nothing was sent. */
class KeyUnavailableException : IOException("This phone's key could not be read. Unlock the phone and try again.")

val ApiJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

/**
 * HTTP to the node's mobile API over whichever way works: its LAN name, its
 * LAN IP, then its onion through the embedded Tor. LAN connections are pinned
 * to the node's root certificate; an http onion needs no certificate (the
 * address authenticates the node), an https one is pinned too. The way that
 * last worked is tried first.
 *
 * Any HTTP answer is the node's verdict and is not retried elsewhere; only a
 * failure to connect moves on. Calls that move money carry a request id, so a
 * request that reached the node but whose answer was lost is safe to resend
 * on the next route: the node replies with the first outcome.
 */
class Transport(
    @Volatile var endpoints: ServerEndpoints,
    private val tor: TorController,
    private val apiKeyProvider: () -> String?,
) {
    @Volatile private var lastGoodUrl: String? = null

    private val _route = MutableStateFlow<Route?>(null)
    /** The way the last successful request went. */
    val route: StateFlow<Route?> = _route

    private enum class Kind { LAN, TOR_PLAIN, TOR_TLS }
    private data class Attempt(val route: Route, val url: String, val kind: Kind)

    @Volatile private var pinnedPem: String? = null
    @Volatile private var pinnedLan: OkHttpClient? = null
    @Volatile private var pinnedTor: OkHttpClient? = null

    private val base: OkHttpClient by lazy {
        // No redirects: the key goes to the node's own address and no other,
        // and a POST is never replayed somewhere else.
        OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    @Synchronized
    private fun pinnedClients(): Pair<OkHttpClient, OkHttpClient>? {
        val pem = endpoints.caPem ?: return null
        if (pem != pinnedPem || pinnedLan == null) {
            val tm = CaPinning.trustManagerFor(pem, endpoints.caSha256)
            val pinned = base.newBuilder()
                .sslSocketFactory(CaPinning.sslContextFor(tm).socketFactory, tm)
                .hostnameVerifier(CaPinning.hostnameVerifier(tm))
                .build()
            pinnedLan = pinned.newBuilder().connectTimeout(4, TimeUnit.SECONDS).build()
            pinnedTor = pinned.newBuilder().connectTimeout(45, TimeUnit.SECONDS).build()
            pinnedPem = pem
        }
        return pinnedLan!! to pinnedTor!!
    }

    private fun attempts(): List<Attempt> {
        val e = endpoints
        val canPin = e.caPem != null
        val list = buildList {
            if (canPin) {
                e.lanUrl?.let { add(Attempt(Route.Lan, it, Kind.LAN)) }
                e.lanIp?.let { add(Attempt(Route.Lan, it, Kind.LAN)) }
            }
            e.onionUrl?.let { onion ->
                if (onion.startsWith("https://")) {
                    if (canPin) add(Attempt(Route.Tor, onion, Kind.TOR_TLS))
                } else {
                    add(Attempt(Route.Tor, onion, Kind.TOR_PLAIN))
                    // A StartOS onion may serve only HTTPS.
                    if (canPin) add(Attempt(Route.Tor, onion.replaceFirst("http://", "https://"), Kind.TOR_TLS))
                }
            }
        }
        val lg = lastGoodUrl ?: return list
        // Stay on what worked, but while that is Tor, look for the LAN again
        // now and then: back home, the phone should use it.
        val now = android.os.SystemClock.elapsedRealtime()
        if (_route.value == Route.Tor && now - lastLanProbeMs > LAN_RECHECK_MS) {
            lastLanProbeMs = now
            return list
        }
        return list.sortedByDescending { it.url == lg }
    }

    @Volatile private var lastLanProbeMs = 0L

    private fun clientFor(kind: Kind, timeoutSeconds: Long): OkHttpClient {
        val c = when (kind) {
            Kind.LAN -> pinnedClients()!!.first
            Kind.TOR_PLAIN -> base.newBuilder()
                .connectTimeout(45, TimeUnit.SECONDS)
                .proxy(tor.proxy() ?: throw UnreachableException("Tor is not ready"))
                .build()
            Kind.TOR_TLS -> pinnedClients()!!.second.newBuilder()
                .proxy(tor.proxy() ?: throw UnreachableException("Tor is not ready"))
                .build()
        }
        val extra = if (kind == Kind.LAN) 0L else 30L
        return c.newBuilder()
            .readTimeout(timeoutSeconds + extra, TimeUnit.SECONDS)
            .callTimeout(timeoutSeconds + extra + 20, TimeUnit.SECONDS)
            .build()
    }

    /** Whether any way to the node is configured at all. */
    val hasRoute: Boolean get() = attempts().isNotEmpty()

    /** Whether the only ways configured are over Tor. */
    val torOnly: Boolean get() = attempts().all { it.route == Route.Tor }

    /**
     * Capture the node's root over LAN, accepted only if it matches the QR
     * code's fingerprint. Any answer (401, 404) completes the handshake this
     * needs. Returns the root's PEM, or null when the LAN address doesn't work.
     */
    suspend fun acquireRootPem(url: String, expectedSha256: String, viaTor: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            var pem: String? = null
            val tm = CaPinning.tofuByFingerprint(expectedSha256) { pem = CaPinning.toPem(it) }
            val builder = base.newBuilder()
                .sslSocketFactory(CaPinning.sslContextFor(tm).socketFactory, tm)
                .hostnameVerifier { _, _ -> true }
            if (viaTor) {
                val proxy = tor.proxy() ?: return@withContext null
                builder.proxy(proxy).connectTimeout(45, TimeUnit.SECONDS).callTimeout(90, TimeUnit.SECONDS)
            } else {
                builder.connectTimeout(5, TimeUnit.SECONDS).callTimeout(12, TimeUnit.SECONDS)
            }
            val client = builder.build()
            runCatching {
                val u = (url.trimEnd('/') + "/api/v1/bootstrap").toHttpUrl()
                client.newCall(Request.Builder().url(u).get().build()).execute().use { }
            }
            pem
        }

    suspend fun get(path: String, timeoutSeconds: Long = 30, auth: Boolean = true): String =
        execute(path, auth, timeoutSeconds) { Request.Builder().get() }

    suspend fun post(path: String, body: String, timeoutSeconds: Long = 30, auth: Boolean = true): String =
        execute(path, auth, timeoutSeconds) { Request.Builder().post(body.toRequestBody(JSON_MEDIA)) }

    private suspend fun execute(
        path: String,
        auth: Boolean,
        timeoutSeconds: Long,
        build: () -> Request.Builder,
    ): String = withContext(Dispatchers.IO) {
        val list = attempts()
        if (list.isEmpty()) throw UnreachableException("No address for the node is known.")
        val key = if (auth) apiKeyProvider() ?: throw KeyUnavailableException() else null
        var last: Exception? = null
        for (a in list) {
            try {
                if (a.route == Route.Tor && tor.status.value != TorStatus.Ready) {
                    tor.start()
                    if (tor.status.value != TorStatus.Ready) {
                        throw UnreachableException("Tor could not start.")
                    }
                }
                val url = (a.url.trimEnd('/') + path).toHttpUrl()
                val b = build().url(url).header("Accept", "application/json")
                if (key != null) b.header("Authorization", "Bearer $key")
                logi("${a.kind} $path")
                clientFor(a.kind, timeoutSeconds).newCall(b.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    lastGoodUrl = a.url
                    _route.value = a.route
                    if (!resp.isSuccessful) {
                        throw ApiException(resp.code, errorMessage(resp.code, text))
                    }
                    return@withContext text
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                logw("${a.kind} $path failed: ${e.javaClass.simpleName}: ${e.message}")
                if (a.route == Route.Tor) tor.checkHealth()
                last = e
            }
        }
        throw UnreachableException("Can't reach your node right now.", last)
    }

    private fun errorMessage(code: Int, body: String): String {
        val fromNode = runCatching { ApiJson.decodeFromString(ErrorBody.serializer(), body).error }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        return fromNode ?: when (code) {
            401 -> "This phone is no longer paired with your node."
            429 -> "Too many attempts. Try again in a few minutes."
            502, 503, 504 -> "Your node is not answering. It may be starting up."
            else -> "Your node answered with an error ($code)."
        }
    }

    // URLs name the node's addresses; log them in debug builds only.
    private fun logi(msg: String) { if (BuildConfig.DEBUG) Log.i(TAG, msg) }
    private fun logw(msg: String) { if (BuildConfig.DEBUG) Log.w(TAG, msg) }

    private companion object {
        const val TAG = "LfNet"
        const val LAN_RECHECK_MS = 60_000L
    }
}
