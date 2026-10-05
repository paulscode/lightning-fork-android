package com.paulscode.lightningfork.ui.send

import com.paulscode.lightningfork.R
import com.paulscode.lightningfork.net.ApiException
import com.paulscode.lightningfork.net.BitcoinInvoicePayRequest
import com.paulscode.lightningfork.net.OnchainSendRequest
import com.paulscode.lightningfork.net.PayRequest
import com.paulscode.lightningfork.net.PaymentTarget
import com.paulscode.lightningfork.net.PendingSend
import com.paulscode.lightningfork.ui.text.UiText

/** What a send goes out as, and how the node's answer is read: the screen's rules, apart from the screen. */
object SendRules {
    /**
     * The send [s] describes, under [requestId], as it is kept on the phone
     * until its outcome is known; null when it can't be sent as it stands.
     */
    fun pendingFor(s: SendUi, requestId: String, nowMs: Long): PendingSend? {
        val t = s.active ?: return null
        val amount = s.amountSat ?: return null
        return when {
            t.kind == "onchain" -> PendingSend(
                onchain = OnchainSendRequest(
                    address = t.address ?: t.request,
                    amountSat = if (s.sendAll) null else amount,
                    sendAll = s.sendAll,
                    satPerVbyte = s.satPerVbyte ?: return null,
                    requestId = requestId,
                ),
                amountSat = amount,
                feeSat = s.estimate?.feeSat ?: 0,
                startedAtMs = nowMs,
            )
            // The node holds the payment to the service's ceiling; kept on
            // the phone is the most it can cost, as the review showed it
            // (that ceiling and the routing to the service).
            t.isBitcoinInvoice -> {
                val est = t.estimate ?: return null
                PendingSend(
                    bitcoinInvoice = BitcoinInvoicePayRequest(
                        request = t.request,
                        maxIncomingSat = est.maxIncomingSat,
                        requestId = requestId,
                    ),
                    amountSat = est.maxIncomingSat + est.routingFeeLimitSat,
                    bitcoinAmountSat = t.amountSat,
                    fromOwnBridge = est.fromOwnBridge,
                    startedAtMs = nowMs,
                )
            }
            else -> PendingSend(
                pay = PayRequest(
                    request = t.request,
                    amountSat = if (t.amountEditable) amount else null,
                    payerNote = s.payerNote.takeIf { it.isNotBlank() && t.kind == "offer" },
                    requestId = requestId,
                ),
                amountSat = amount,
                startedAtMs = nowMs,
            )
        }
    }

    /**
     * Whether [pending] is too old to ask about: an on-chain or Lightning
     * send the node may never have got, which asked about after the node has
     * forgotten the request (a day) would be sent then. A SHA256 invoice is
     * never paid anew by asking, so it can always be asked about.
     */
    fun tooOldToCheck(pending: PendingSend, nowMs: Long): Boolean =
        pending.bitcoinInvoice == null && pending.startedAtMs > 0 && nowMs - pending.startedAtMs > 24 * 3600_000L

    /** [pending]'s Bitcoin invoice payment as it goes out: a re-ask says so. */
    fun bitcoinInvoiceRequest(pending: PendingSend, again: Boolean): BitcoinInvoicePayRequest? =
        pending.bitcoinInvoice?.let { if (again) it.copy(resume = true) else it }

    /**
     * Whether [e] settles the send: the node's refusal, after which nothing
     * moved and a new attempt is a new payment. Otherwise the money may have
     * moved, and the send is asked about again under the same id.
     *
     * Paying a Bitcoin invoice, every refusal with a code is the node's word
     * that nothing was paid, on a re-ask too; an answer marked uncertain
     * never settles it.
     */
    fun settles(e: ApiException, again: Boolean, bitcoinInvoice: Boolean = false): Boolean {
        if (bitcoinInvoice) {
            if (e.uncertain) return false
            if (e.code != null) return true
        }
        return if (again) definiteOnRecheck(e.status) else definite(e.status)
    }

    /**
     * A Bitcoin invoice whose payment the service still holds: it goes on,
     * and is asked about again, calmly.
     */
    fun onItsWay(e: ApiException, bitcoinInvoice: Boolean): Boolean =
        bitcoinInvoice && e.uncertain && e.code == ON_ITS_WAY

    /**
     * The node's own refusal, as opposed to an error on the way. 503 is the
     * node saying LND is not answering, before anything was sent; 502 and 504
     * are a cut-off.
     */
    fun definite(status: Int) =
        (status in 400..499 && status != 408 && status != 425 && status != 429) || status == 503

    fun definiteOnRecheck(status: Int) =
        status in 400..499 && status !in setOf(401, 403, 408, 422, 425, 429)

    /**
     * Whether trying again can help after the refusal [code] paying a SHA256
     * invoice: not when it is paid already, the service is not behaving, or
     * something must change in the dashboard first.
     */
    fun retryable(code: String?): Boolean = code !in NOT_RETRYABLE

    /**
     * Whether [t] is a SHA256 invoice the node's own bridge pays: nothing of
     * this wallet is spent, so no amount or balance here is checked.
     */
    fun paysFromOwnBridge(t: PaymentTarget): Boolean =
        t.isBitcoinInvoice && t.estimate?.fromOwnBridge == true

    /** A sentence on where to fix what [code] says, or null. */
    fun dashboardHint(code: String?): UiText? =
        if (code in FIXED_IN_DASHBOARD) UiText.of(R.string.send_hint_dashboard) else null

    /**
     * The button's word when the node says a SHA256 invoice can't be paid
     * now, by its reason's [code]; the sentence itself is on the screen.
     */
    fun blockerLabel(code: String?): UiText = UiText.of(
        when (code) {
            "no_service" -> R.string.send_blocker_no_service
            "no_amount" -> R.string.send_blocker_no_amount
            "expired" -> R.string.send_blocker_expired
            "too_small" -> R.string.send_blocker_too_small
            "too_large" -> R.string.send_blocker_too_large
            "rate" -> R.string.send_blocker_rate
            "reference_unavailable" -> R.string.send_blocker_no_market_rate
            "own_bridge_not_ready" -> R.string.send_blocker_own_bridge_not_ready
            "own_bridge_no_liquidity" -> R.string.send_blocker_own_bridge_no_liquidity
            "unreachable", "unavailable", "internal", "invalid_response", "tor_required", "cert_mismatch", "not_authorized" ->
                R.string.send_blocker_service_unusable
            null -> R.string.send_blocker_cant_pay_now
            else -> R.string.send_blocker_service_not_paying
        },
    )

    private val NOT_RETRYABLE = setOf(
        "already_paid", "needs_operator", "invalid_hold_invoice", "hold_too_long",
        "no_service", "no_amount", "not_bitcoin_invoice", "tor_required", "cert_mismatch",
        "not_authorized", "wrong_node", "unsupported_version",
    )
    private val FIXED_IN_DASHBOARD = setOf("no_service", "own_bridge_no_liquidity", "tor_required", "cert_mismatch", "not_authorized", "rate", "wrong_node", "unsupported_version")

    /** The service asks more than the user agreed to: show the new price. */
    const val PRICE_CHANGED = "price_changed"

    /** The service paid the SHA256 invoice and did not collect: the operator's to resolve. */
    const val NEEDS_OPERATOR = "needs_operator"

    /** The node knows the payment is under way, not merely unsure. */
    const val ON_ITS_WAY = "on_its_way"

    /** Paid already, by this node or not: nothing to try again. */
    const val ALREADY_PAID = "already_paid"
}
