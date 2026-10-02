package com.paulscode.lightningfork.net

import kotlinx.serialization.Serializable

// Wire shapes of the dashboard's mobile API (/api/v1). Amounts are whole
// satoshis, times Unix seconds. Unknown fields are ignored, so the server can
// add to a response without breaking an older app.

/** The pairing QR code: the one-time code and how to reach and recognise the node. */
@Serializable
data class PairingPayload(
    val lf: Int = 0,
    val code: String = "",
    val exp: Long? = null,
    val onion: String? = null,
    val lan: String? = null,
    val ip: String? = null,
    val ca: String? = null,
)

@Serializable
data class PairRequest(val enrollCode: String, val label: String)

@Serializable
data class NodeInfo(
    val alias: String = "",
    val pubkey: String = "",
    val color: String = "",
    val version: String = "",
    val network: String = "mainnet",
    val syncedToChain: Boolean = false,
    val syncedToGraph: Boolean = false,
    val blockHeight: Long = 0,
    val activeChannels: Int = 0,
    val peers: Int = 0,
)

@Serializable
data class PairResponse(
    val apiKey: String,
    val deviceId: String,
    val label: String = "",
    val serverId: String,
    val apiVersion: Int = 1,
    val caPem: String? = null,
    val caSha256: String? = null,
    val node: NodeInfo? = null,
)

@Serializable
data class BootstrapResponse(
    val serverId: String,
    val apiVersion: Int = 1,
    val deviceId: String = "",
    val label: String = "",
    val node: NodeInfo,
)

@Serializable
data class EndpointsResponse(
    val onionUrl: String? = null,
    val lanUrl: String? = null,
    val lanIp: String? = null,
    val caPem: String? = null,
    val caSha256: String? = null,
)

@Serializable
data class OnchainBalance(
    val confirmedSat: Long = 0,
    val unconfirmedSat: Long = 0,
    val lockedSat: Long = 0,
    val reservedSat: Long = 0,
)

@Serializable
data class LightningBalance(
    val outboundSat: Long = 0,
    val inboundSat: Long = 0,
    val pendingOutboundSat: Long = 0,
)

@Serializable
data class WalletResponse(
    val onchain: OnchainBalance = OnchainBalance(),
    val lightning: LightningBalance = LightningBalance(),
    val syncedToChain: Boolean = true,
    val blockHeight: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class FeeSource(val kind: String = "", val name: String = "")

@Serializable
data class FeeRate(val satPerVbyte: Long, val label: String = "", val eta: String = "")

@Serializable
data class FeesResponse(
    val source: FeeSource? = null,
    val warning: String? = null,
    val minimumSatPerVbyte: Long = 1,
    val low: FeeRate,
    val medium: FeeRate,
    val high: FeeRate,
)

/** What a pasted or scanned text turned out to be, and how to pay it. */
@Serializable
data class PaymentTarget(
    /** onchain, bolt11, offer, bolt12-invoice or unsupported. */
    val kind: String,
    val request: String = "",
    val address: String? = null,
    val addressType: String? = null,
    val amountSat: Long? = null,
    val amountEditable: Boolean = true,
    val description: String = "",
    val label: String? = null,
    val issuer: String? = null,
    val destination: String? = null,
    val paymentHash: String? = null,
    val createdAt: Long? = null,
    val expiresAt: Long? = null,
    val expired: Boolean = false,
    val ours: Boolean = false,
    val message: String? = null,
    /** The on-chain way to pay a unified BIP 21 request. */
    val fallback: PaymentTarget? = null,
) {
    val isLightning: Boolean get() = kind == "bolt11" || kind == "offer" || kind == "bolt12-invoice"
}

@Serializable
data class DecodeRequest(val input: String)

@Serializable
data class OnchainEstimateRequest(
    val address: String,
    val amountSat: Long? = null,
    val sendAll: Boolean = false,
    val satPerVbyte: Long,
)

@Serializable
data class OnchainEstimate(
    val amountSat: Long,
    val feeSat: Long,
    val satPerVbyte: Long,
    val totalSat: Long,
    val sendAll: Boolean = false,
)

@Serializable
data class OnchainSendRequest(
    val address: String,
    val amountSat: Long? = null,
    val sendAll: Boolean = false,
    val satPerVbyte: Long,
    val label: String? = null,
    val requestId: String,
)

@Serializable
data class OnchainSendResponse(val txid: String, val satPerVbyte: Long = 0)

@Serializable
data class PayRequest(
    val request: String,
    val amountSat: Long? = null,
    val payerNote: String? = null,
    val requestId: String,
)

@Serializable
data class PayResponse(
    val status: String,
    val paymentHash: String = "",
    val preimage: String = "",
    val amountSat: Long = 0,
    val feeSat: Long = 0,
)

@Serializable
data class AddressRequest(val fresh: Boolean = false)

@Serializable
data class AddressResponse(val address: String, val type: String = "", val uri: String = "")

@Serializable
data class InvoiceRequest(
    val amountSat: Long? = null,
    val memo: String? = null,
    val expirySeconds: Long? = null,
)

@Serializable
data class InvoiceResponse(
    val paymentRequest: String,
    val paymentHash: String,
    val amountSat: Long? = null,
    val memo: String = "",
    val createdAt: Long = 0,
    val expiresAt: Long = 0,
    val uri: String = "",
)

@Serializable
data class InvoiceStatus(
    val paymentHash: String,
    /** open, settled, canceled, accepted or expired. */
    val state: String,
    val amountSat: Long? = null,
    val amountPaidSat: Long = 0,
    val settledAt: Long? = null,
    val expiresAt: Long = 0,
)

@Serializable
data class ActivityItem(
    val id: String,
    /** onchain or lightning. */
    val kind: String,
    /** in or out. */
    val direction: String,
    val amountSat: Long,
    val feeSat: Long = 0,
    val timestamp: Long = 0,
    /** confirmed, pending, complete or failed. */
    val status: String = "",
    val confirmations: Long? = null,
    val description: String = "",
    val reference: String = "",
)

@Serializable
data class ActivityResponse(val items: List<ActivityItem> = emptyList())

@Serializable
data class PriceResponse(val currency: String = "USD", val price: Double? = null)

@Serializable
data class ErrorBody(val error: String = "")
