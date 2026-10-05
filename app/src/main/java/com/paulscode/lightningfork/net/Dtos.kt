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

/**
 * [claimNonce]: random, kept for the code. If the answer is lost, the same
 * code with the same nonce can be claimed again briefly; nobody else who saw
 * the QR code has the nonce.
 */
@Serializable
data class PairRequest(val enrollCode: String, val label: String, val claimNonce: String? = null)

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
    /**
     * What the dashboard can do beyond the first calls; null from one too
     * old to say (which also can't pay SHA256 invoices).
     */
    val features: List<String>? = null,
    val bitcoinInvoices: BitcoinInvoicesSummary? = null,
) {
    val paysSha256Invoices: Boolean get() = features?.contains(FEATURE_SHA256_INVOICES) == true

    companion object {
        const val FEATURE_SHA256_INVOICES = "bitcoin-invoice"
    }
}

/** Whether a service for SHA256 invoices is set up, its name and the premium allowed. */
@Serializable
data class BitcoinInvoicesSummary(
    val configured: Boolean = false,
    val label: String = "",
    val onion: Boolean = false,
    val premium: Double = 0.0,
)

/** The service's terms now, as /bitcoin-invoices gives them. */
@Serializable
data class BitcoinInvoiceTerms(
    val open: Boolean = false,
    val refusal: String? = null,
    val rate: Double = 0.0,
    val spread: Double = 0.0,
    val minSat: Long = 0,
    val maxSat: Long = 0,
)

@Serializable
data class BitcoinInvoicesStatus(
    val configured: Boolean = false,
    val label: String = "",
    val onion: Boolean = false,
    val premium: Double = 0.0,
    val terms: BitcoinInvoiceTerms? = null,
    val error: String? = null,
    val reference: BitcoinInvoiceReference? = null,
    val referenceError: String? = null,
    /** Set when the node runs its own bridge, which pays before any service. */
    val ownBridge: OwnBridge? = null,
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

/**
 * What paying a Bitcoin invoice through the service would cost now, in sats
 * of this chain. [maxIncomingSat] is the ceiling the payment is held to, and
 * the figure to show; [incomingSat] what is expected, [feeSat] the service's
 * part of it. [rate] is bitcoin paid out per coin of this chain paid in.
 */
@Serializable
data class BitcoinInvoiceEstimate(
    val incomingSat: Long,
    val feeSat: Long = 0,
    val maxIncomingSat: Long,
    val routingFeeLimitSat: Long = 0,
    val rate: Double = 0.0,
    val spread: Double = 0.0,
    val minSat: Long? = null,
    val maxSat: Long? = null,
    val serviceLabel: String = "",
    val open: Boolean = true,
    val refusal: String? = null,
    /** The service's own code for [refusal]. */
    val refusalCode: String? = null,
    /**
     * "own_bridge" when the node pays it from its own bridge's SHA256 node:
     * nothing of this chain is spent (the amounts above are 0), and the
     * sha256 fields say what that node pays.
     */
    val source: String = "",
    val sha256AmountSat: Long = 0,
    val sha256RoutingFeeLimitSat: Long = 0,
    val sha256AvailableSat: Long = 0,
) {
    val fromOwnBridge: Boolean get() = source == OWN_BRIDGE
}

/** Paid from the node's own bridge, as the dashboard names it. */
const val OWN_BRIDGE = "own_bridge"

/** The node's own bridge, which pays SHA256 invoices from its SHA256 node. */
@Serializable
data class OwnBridge(
    val ready: Boolean = false,
    val availableSat: Long = 0,
)

/**
 * The market rate a Bitcoin invoice's price is checked against: [premium] is
 * how far the price is from it, [premiumAllowed] how far it may be, both as
 * fractions (0.05 is 5%).
 */
@Serializable
data class BitcoinInvoiceReference(
    val rate: Double = 0.0,
    val premiumAllowed: Double = 0.0,
    val premium: Double = 0.0,
    val withinLimit: Boolean = true,
    val source: String = "",
)

/** What a pasted or scanned text turned out to be, and how to pay it. */
@Serializable
data class PaymentTarget(
    /** onchain, bolt11, offer, bolt12-invoice, bitcoin-invoice or unsupported. */
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
    /** Names the reason in [message]: no_service, no_amount, rate, bridge_code, ... */
    val messageCode: String? = null,
    /** The on-chain way to pay a unified BIP 21 request. */
    val fallback: PaymentTarget? = null,
    /** A Bitcoin invoice: false whenever [message] says why it can't be paid now. */
    val payable: Boolean = true,
    /** A Bitcoin invoice: its price, or null without a service to ask. */
    val estimate: BitcoinInvoiceEstimate? = null,
    val reference: BitcoinInvoiceReference? = null,
    /** Why there is no market rate to check the price against. */
    val referenceError: String? = null,
) {
    val isLightning: Boolean get() = kind == "bolt11" || kind == "offer" || kind == "bolt12-invoice"

    /** An invoice of the original Bitcoin chain, paid through the service. */
    val isBitcoinInvoice: Boolean get() = kind == "bitcoin-invoice"
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
    /** Asking again about this very send (see PendingSend). */
    val resume: Boolean? = null,
)

@Serializable
data class OnchainSendResponse(val txid: String, val satPerVbyte: Long = 0)

@Serializable
data class PayRequest(
    val request: String,
    val amountSat: Long? = null,
    val payerNote: String? = null,
    val requestId: String,
    /** Asking again about this very payment (see PendingSend). */
    val resume: Boolean? = null,
)

/**
 * [maxIncomingSat]: the estimate's ceiling the user agreed to; the node pays
 * no more.
 */
@Serializable
data class BitcoinInvoicePayRequest(
    val request: String,
    val maxIncomingSat: Long,
    val requestId: String,
    /** Asking again about this very payment (see PendingSend). */
    val resume: Boolean? = null,
)

/** The Bitcoin invoice a payment paid: [amountSat] in Bitcoin. */
@Serializable
data class PaidBitcoinInvoice(
    val amountSat: Long = 0,
    val description: String = "",
    val paymentHash: String = "",
    /** "own_bridge" when paid from the node's own bridge. */
    val source: String = "",
    /** Paid from the node's own bridge: its SHA256 node's routing fee. */
    val sha256FeeSat: Long = 0,
)

/**
 * [amountSat] and [feeSat] are what it cost here. Paying a Bitcoin invoice,
 * [bitcoinInvoice] is what was paid there.
 */
@Serializable
data class PayResponse(
    val status: String,
    val paymentHash: String = "",
    val preimage: String = "",
    val amountSat: Long = 0,
    val feeSat: Long = 0,
    val bitcoinInvoice: PaidBitcoinInvoice? = null,
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
    /** A payment that paid a Bitcoin invoice: what it paid there. */
    val bitcoinInvoice: ActivityBitcoinInvoice? = null,
    /** The proof of a paid Bitcoin invoice. */
    val preimage: String? = null,
)

/** [amountSat] on the SHA256 chain; [state] is paid, pending or returned. */
@Serializable
data class ActivityBitcoinInvoice(
    val amountSat: Long = 0,
    val description: String = "",
    val serviceLabel: String = "",
    val state: String = "",
    /** "own_bridge": paid from the node's own bridge, nothing spent here. */
    val source: String = "",
    /** Paid from the node's own bridge: its SHA256 node's routing fee. */
    val sha256FeeSat: Long = 0,
)

@Serializable
data class ActivityResponse(val items: List<ActivityItem> = emptyList())

@Serializable
data class CurrenciesResponse(val currencies: List<String> = listOf("USD"))

@Serializable
data class PriceResponse(val currency: String = "USD", val price: Double? = null)

/** [code]: a refusal's stable name; [uncertain]: the money may have moved. */
@Serializable
data class ErrorBody(
    val error: String = "",
    val code: String? = null,
    val uncertain: Boolean = false,
    val details: ErrorDetails? = null,
)

/**
 * What a refusal carries beside its code: the proof of a payment made
 * already, how long a held payment can stay held, how long to wait before
 * trying again, a price that rose and the ceiling agreed.
 */
@Serializable
data class ErrorDetails(
    val preimage: String? = null,
    val maxHoldHours: Long? = null,
    val retryAfterSeconds: Long? = null,
    val incomingSat: Long? = null,
    val maxIncomingSat: Long? = null,
)

/**
 * A send as it went to the node, kept on the phone until its outcome is known,
 * so that a send cut off (or an app killed mid-send) can be asked about again
 * with the very same request and id.
 */
@Serializable
data class PendingSend(
    val onchain: OnchainSendRequest? = null,
    val pay: PayRequest? = null,
    val bitcoinInvoice: BitcoinInvoicePayRequest? = null,
    /**
     * At most what it costs here; for a SHA256 invoice, the ceiling shown
     * with the review (the service's, and the routing to it).
     */
    val amountSat: Long,
    val feeSat: Long = 0,
    val startedAtMs: Long = 0,
    /** A Bitcoin invoice's own amount, in Bitcoin. */
    val bitcoinAmountSat: Long? = null,
    /** A SHA256 invoice the node's own bridge pays: nothing spent here. */
    val fromOwnBridge: Boolean = false,
) {
    val lightning: Boolean get() = pay != null || bitcoinInvoice != null
}
