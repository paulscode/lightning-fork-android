package com.paulscode.lightningfork.net

import android.content.res.Resources
import android.util.Log
import androidx.annotation.StringRes
import com.paulscode.lightningfork.BuildConfig
import com.paulscode.lightningfork.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
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
 * sentence for the user when it gave one; [code], a refusal the app can
 * branch on, when it gave one; [uncertain], its word that the money may
 * have moved.
 */
class ApiException(
    val status: Int,
    override val message: String,
    val code: String? = null,
    val uncertain: Boolean = false,
    val details: ErrorDetails? = null,
) : Exception(message)

/**
 * The app's own words for the user, in the phone's language: a string
 * resource with its arguments, looked up when the text is made. [of] reads
 * the app's resources; [English], the default, serves the plain-JVM tests,
 * which have none.
 */
fun interface AppStrings {
    fun get(@StringRes id: Int, vararg args: Any): String

    companion object {
        fun of(res: Resources): AppStrings = AppStrings { id, args -> res.getString(id, *args) }

        /** The English of the strings in strings_app.xml that plain classes make. */
        val English: AppStrings = AppStrings { id, args ->
            val text = when (id) {
                R.string.app_key_lost -> "This phone's key can no longer be read. Pair the phone again."
                R.string.app_key_unavailable -> "This phone's key could not be read. Unlock the phone and try again."
                R.string.app_certificate_changed -> "Your node's certificate does not match the one this phone was paired with."
                R.string.app_no_address -> "No address for the node is known."
                R.string.app_unreachable -> "Can't reach your node right now."
                R.string.app_error_not_paired -> "This phone is no longer paired with your node."
                R.string.app_error_too_many_attempts -> "Too many attempts. Try again in a few minutes."
                R.string.app_error_not_answering -> "Your node is not answering. It may be starting up."
                R.string.app_error_status -> "Your node answered with an error (%1\$d)."
                R.string.app_pair_no_address -> "This code doesn't say how to reach the node."
                R.string.app_pair_not_on_lan -> "Can't reach your node from this network. Connect to the same Wi-Fi as your node and try again."
                R.string.app_pair_tor_failed -> "Tor could not start. Check the connection and try again."
                R.string.app_pair_tor_unreachable -> "Can't reach your node over Tor right now. Try again, or pair on the same Wi-Fi as your node."
                R.string.app_pair_certificate_mismatch -> "The node's certificate does not match the code. Pairing was stopped."
                R.string.app_pair_code_used -> "This code has expired or was already used. Make a new one in the dashboard."
                R.string.app_pair_too_many_attempts -> "Too many attempts. Wait a few minutes and try again."
                R.string.app_pair_unreachable -> "Can't reach your node. Check that this phone is online, then try again."
                R.string.app_wallet_tor_failed -> "Can't reach your node, and Tor could not start."
                R.string.app_wallet_connecting_tor -> "Connecting to your node over Tor…"
                else -> error("no English for string $id")
            }
            if (args.isEmpty()) text else String.format(Locale.US, text, *args)
        }
    }
}

/** No way to the node worked. */
class UnreachableException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The device key could not be read; nothing was sent. [lost]: the Keystore
 * key is gone for good (the phone must pair again), not just locked.
 */
class KeyUnavailableException(val lost: Boolean, strings: AppStrings = AppStrings.English) : IOException(
    if (lost) strings.get(R.string.app_key_lost) else strings.get(R.string.app_key_unavailable),
)

/**
 * The node was reached but its certificate no longer chains to the root the
 * phone pinned at pairing: the node's certificate authority changed, or
 * something is in the way. Nothing was sent.
 */
class CertificateChangedException(cause: Throwable?, strings: AppStrings = AppStrings.English) :
    IOException(strings.get(R.string.app_certificate_changed), cause)

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
 *
 * [strings]: the app's own words in its errors (a node's own sentence is
 * passed on as it came).
 */
class Transport(
    @Volatile var endpoints: ServerEndpoints,
    private val tor: TorController,
    private val localNetwork: LocalNetwork = LocalNetwork { true },
    private val strings: AppStrings = AppStrings.English,
    private val apiKeyProvider: () -> com.paulscode.lightningfork.crypto.SecretStore.Read,
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
                .hostnameVerifier(CaPinning.hostnameVerifier(tm) { knownHosts() })
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
        // Stay on what worked, but while that is Tor, look for the LAN again
        // now and then (back home, the phone should use it), by a quick probe.
        val now = android.os.SystemClock.elapsedRealtime()
        if (_route.value == Route.Tor && canPin && now - lastLanProbeMs > LAN_RECHECK_MS) {
            lastLanProbeMs = now
            // In the background: the next call goes by what it finds.
            probeScope.launch { lanAnswers() }
        }
        val lg = lastGoodUrl
        // Away from Wi-Fi the home addresses would only spend seconds
        // failing before the onion is tried: the onion goes first there, and
        // they stay as a fallback (a VPN can reach home; the probe above then
        // moves calls back to the LAN).
        val torFirst = !localNetwork.likely()
        return list.sortedWith(
            compareByDescending<Attempt> { torFirst && it.route == Route.Tor }
                .thenByDescending { it.url == lg },
        )
    }

    /**
     * Starts Tor ahead of the first call that will need it: away from Wi-Fi,
     * or when the last call went over Tor. Bootstrapping then overlaps the
     * app's start instead of following the failed home addresses.
     */
    fun warmUp() {
        if (endpoints.onionUrl == null) return
        if (tor.status.value == TorStatus.Ready || tor.status.value == TorStatus.Bootstrapping) return
        if (!localNetwork.likely() || _route.value == Route.Tor) startTorInBackground()
    }

    private fun startTorInBackground() {
        probeScope.launch { runCatching { tor.start() } }
    }

    @Volatile private var onionWarmedMs = 0L

    /**
     * Keeps the way to the onion ready while the phone is at home, so that
     * leaving Wi-Fi does not start from nothing. Reaching an onion takes Tor
     * a lookup of the service's descriptor, one directory relay at a time,
     * and a slow relay costs it 15 s or more (measured: 5 to 44 s for the
     * first connection, 2 s once the descriptor is known). Tor keeps the
     * descriptor for hours while the app runs, so after a call at home this
     * starts Tor if needed and opens one connection to the onion, then
     * closes it: no request reaches the node. At most every half hour.
     */
    private fun warmOnion() {
        val onion = endpoints.onionUrl?.toHttpUrlOrNull() ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - onionWarmedMs < ONION_REWARM_MS && onionWarmedMs != 0L) return
        onionWarmedMs = now
        probeScope.launch {
            runCatching {
                if (tor.status.value != TorStatus.Ready) tor.start()
                val proxy = tor.proxy() ?: return@runCatching
                java.net.Socket(proxy).use {
                    it.connect(java.net.InetSocketAddress.createUnresolved(onion.host, onion.port), 90_000)
                }
                logi("onion warmed")
            }.onFailure {
                // Tried again on a later call rather than in a half hour.
                onionWarmedMs = 0L
                logw("onion warm-up failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
    }

    @Volatile private var lastLanProbeMs = 0L
    private val probeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    /** The node's own host names, the only ones the pinned root vouches for here. */
    private fun knownHosts(): Set<String> {
        val e = endpoints
        return listOfNotNull(e.lanUrl, e.lanIp, e.onionUrl)
            .mapNotNull { runCatching { it.toHttpUrl().host.lowercase() }.getOrNull() }
            .toSet()
    }

    /**
     * Whether the LAN answers, by a quick unauthenticated request (any HTTP
     * answer will do), so that looking for it never holds up a real call.
     */
    private fun lanAnswers(): Boolean {
        val e = endpoints
        val lan = pinnedClients()?.first ?: return false
        val quick = lan.newBuilder().callTimeout(4, TimeUnit.SECONDS).build()
        for (url in listOfNotNull(e.lanUrl, e.lanIp)) {
            val ok = runCatching {
                quick.newCall(Request.Builder().url((url.trimEnd('/') + "/api/v1/bootstrap").toHttpUrl()).get().build())
                    .execute().use { true }
            }.getOrDefault(false)
            if (ok) {
                lastGoodUrl = url
                return true
            }
        }
        return false
    }

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

    suspend fun post(
        path: String,
        body: String,
        timeoutSeconds: Long = 30,
        auth: Boolean = true,
        key: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
    ): String =
        execute(path, auth, timeoutSeconds, key) {
            val b = Request.Builder().post(body.toRequestBody(JSON_MEDIA))
            extraHeaders.forEach { (name, value) -> b.header(name, value) }
            b
        }

    private suspend fun execute(
        path: String,
        auth: Boolean,
        timeoutSeconds: Long,
        keyOverride: String? = null,
        build: () -> Request.Builder,
    ): String = withContext(Dispatchers.IO) {
        val list = attempts()
        if (list.isEmpty()) throw UnreachableException(strings.get(R.string.app_no_address))
        val key = if (!auth) null else keyOverride ?: when (val read = apiKeyProvider()) {
            is com.paulscode.lightningfork.crypto.SecretStore.Read.Key -> read.value
            com.paulscode.lightningfork.crypto.SecretStore.Read.Lost,
            com.paulscode.lightningfork.crypto.SecretStore.Read.None -> throw KeyUnavailableException(lost = true, strings)
            com.paulscode.lightningfork.crypto.SecretStore.Read.Unavailable -> throw KeyUnavailableException(lost = false, strings)
        }
        var last: Exception? = null
        var certFailure: Exception? = null
        var torDown = false
        for (a in list) {
            try {
                if (a.route == Route.Tor) {
                    // One failed start is enough for this call: the next onion
                    // attempt would only wait out the same bootstrap again.
                    if (torDown) continue
                    if (tor.status.value != TorStatus.Ready) {
                        tor.start()
                        if (tor.status.value != TorStatus.Ready) {
                            torDown = true
                            throw UnreachableException("Tor could not start.")
                        }
                    }
                }
                val url = (a.url.trimEnd('/') + path).toHttpUrl()
                val b = build().url(url).header("Accept", "application/json")
                if (key != null) b.header("Authorization", "Bearer $key")
                logi("${a.kind} $path")
                val startedMs = android.os.SystemClock.elapsedRealtime()
                clientFor(a.kind, timeoutSeconds).newCall(b.build()).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    logi("${a.kind} $path done in ${android.os.SystemClock.elapsedRealtime() - startedMs} ms")
                    lastGoodUrl = a.url
                    _route.value = a.route
                    if (a.route == Route.Lan) warmOnion()
                    if (!resp.isSuccessful) {
                        throw apiError(resp.code, text)
                    }
                    return@withContext text
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                logw("${a.kind} $path failed: ${e.javaClass.simpleName}: ${e.message}")
                if (a.route == Route.Tor) tor.checkHealth()
                // A home address failed: if the onion is next, have Tor
                // starting while any other home address is tried.
                if (a.route == Route.Lan && tor.status.value == TorStatus.Stopped &&
                    list.any { it.route == Route.Tor }
                ) {
                    startTorInBackground()
                }
                // Only the onion authenticates the far end on its own: a
                // certificate it does not chain to the pinned root is the
                // node's. A LAN address that answers with another certificate
                // may be some other device on a foreign network.
                if (a.kind == Kind.TOR_TLS && isCertificateFailure(e)) certFailure = e
                last = e
            }
        }
        // Reached, but not the certificate pinned at pairing.
        if (certFailure != null) throw CertificateChangedException(certFailure, strings)
        throw UnreachableException(strings.get(R.string.app_unreachable), last)
    }

    private fun isCertificateFailure(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is java.security.cert.CertificateException || t is javax.net.ssl.SSLPeerUnverifiedException) return true
            t = t.cause
        }
        return false
    }

    private fun apiError(status: Int, body: String): ApiException {
        val parsed = runCatching { ApiJson.decodeFromString(ErrorBody.serializer(), body) }.getOrNull()
        return ApiException(
            status,
            errorMessage(status, parsed?.error),
            code = parsed?.code?.takeIf { it.isNotBlank() },
            uncertain = parsed?.uncertain == true,
            details = parsed?.details,
        )
    }

    private fun errorMessage(code: Int, error: String?): String {
        val fromNode = error?.takeIf { it.isNotBlank() }
        return fromNode ?: when (code) {
            401 -> strings.get(R.string.app_error_not_paired)
            429 -> strings.get(R.string.app_error_too_many_attempts)
            502, 503, 504 -> strings.get(R.string.app_error_not_answering)
            else -> strings.get(R.string.app_error_status, code)
        }
    }

    // URLs name the node's addresses; log them in debug builds only.
    private fun logi(msg: String) { if (BuildConfig.DEBUG) Log.i(TAG, msg) }
    private fun logw(msg: String) { if (BuildConfig.DEBUG) Log.w(TAG, msg) }

    private companion object {
        const val TAG = "LfNet"
        const val LAN_RECHECK_MS = 60_000L
        const val ONION_REWARM_MS = 30 * 60_000L
    }
}
