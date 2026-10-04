package com.paulscode.lightningfork.data

import android.content.Context
import com.paulscode.lightningfork.net.ApiJson
import com.paulscode.lightningfork.net.NodeInfo
import com.paulscode.lightningfork.net.ServerEndpoints
import com.paulscode.lightningfork.net.WalletResponse

/** How amounts are shown. */
enum class AmountUnit { Sats, Btc }

/**
 * What the app keeps that is not secret: how to reach the node, what it was
 * last seen to say (so the home screen opens with numbers, marked by age, and
 * not a spinner), and the user's display choices. The device key lives in
 * [com.paulscode.lightningfork.crypto.SecretStore].
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("lf_settings", Context.MODE_PRIVATE)

    var serverId: String?
        get() = prefs.getString("server_id", null)
        // commit: this is what marks the phone paired.
        set(v) { prefs.edit().putString("server_id", v).commit() }

    var deviceId: String?
        get() = prefs.getString("device_id", null)
        set(v) { prefs.edit().putString("device_id", v).apply() }

    var deviceLabel: String?
        get() = prefs.getString("device_label", null)
        set(v) { prefs.edit().putString("device_label", v).apply() }

    var node: NodeInfo?
        get() = prefs.getString("node", null)?.let {
            runCatching { ApiJson.decodeFromString(NodeInfo.serializer(), it) }.getOrNull()
        }
        set(v) {
            prefs.edit().putString("node", v?.let { ApiJson.encodeToString(NodeInfo.serializer(), it) }).apply()
        }

    var lastWallet: WalletResponse?
        get() = prefs.getString("last_wallet", null)?.let {
            runCatching { ApiJson.decodeFromString(WalletResponse.serializer(), it) }.getOrNull()
        }
        set(v) {
            prefs.edit().putString("last_wallet", v?.let { ApiJson.encodeToString(WalletResponse.serializer(), it) }).apply()
        }

    var lastWalletAtMs: Long
        get() = prefs.getLong("last_wallet_at", 0)
        set(v) { prefs.edit().putLong("last_wallet_at", v).apply() }

    /** The unit amounts are shown in, everywhere at once. */
    val unitFlow = kotlinx.coroutines.flow.MutableStateFlow(
        runCatching { AmountUnit.valueOf(prefs.getString("unit", AmountUnit.Sats.name)!!) }
            .getOrDefault(AmountUnit.Sats)
    )

    /** A send whose outcome the phone has not yet heard; see [PendingSend]. */
    var pendingSend: com.paulscode.lightningfork.net.PendingSend?
        get() = prefs.getString("pending_send", null)?.let {
            runCatching { ApiJson.decodeFromString(com.paulscode.lightningfork.net.PendingSend.serializer(), it) }.getOrNull()
        }
        set(v) {
            prefs.edit().putString(
                "pending_send",
                v?.let { ApiJson.encodeToString(com.paulscode.lightningfork.net.PendingSend.serializer(), it) },
            ).commit()
        }

    var unit: AmountUnit
        get() = unitFlow.value
        set(v) {
            unitFlow.value = v
            prefs.edit().putString("unit", v.name).apply()
        }

    var showFiat: Boolean
        get() = prefs.getBoolean("show_fiat", true)
        set(v) { prefs.edit().putBoolean("show_fiat", v).apply() }

    /** The currency estimates are shown in; null for the phone's own (when the node quotes it). */
    var fiatCurrency: String?
        get() = prefs.getString("fiat_currency", null)
        set(v) { prefs.edit().putString("fiat_currency", v).apply() }

    var appLock: Boolean
        get() = prefs.getBoolean("app_lock", true)
        set(v) { prefs.edit().putBoolean("app_lock", v).apply() }

    /** Which receive tab was used last: "lightning" or "onchain". */
    var receiveTab: String
        get() = prefs.getString("receive_tab", "lightning") ?: "lightning"
        set(v) { prefs.edit().putString("receive_tab", v).apply() }

    var endpoints: ServerEndpoints
        get() = ServerEndpoints(
            onionUrl = prefs.getString("onion_url", null),
            lanUrl = prefs.getString("lan_url", null),
            lanIp = prefs.getString("lan_ip", null),
            caPem = prefs.getString("ca_pem", null),
            caSha256 = prefs.getString("ca_sha256", null),
        )
        set(v) {
            prefs.edit()
                .putString("onion_url", v.onionUrl)
                .putString("lan_url", v.lanUrl)
                .putString("lan_ip", v.lanIp)
                .putString("ca_pem", v.caPem)
                .putString("ca_sha256", v.caSha256)
                .apply()
        }

    val isPaired: Boolean get() = prefs.contains("server_id")

    fun clear() = prefs.edit().clear().apply()
}
