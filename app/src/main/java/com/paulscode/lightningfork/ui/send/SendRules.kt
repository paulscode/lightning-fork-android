package com.paulscode.lightningfork.ui.send

import com.paulscode.lightningfork.net.ApiException
import com.paulscode.lightningfork.net.BitcoinInvoicePayRequest
import com.paulscode.lightningfork.net.OnchainSendRequest
import com.paulscode.lightningfork.net.PayRequest
import com.paulscode.lightningfork.net.PendingSend

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
            // The ceiling the user was shown is the one the node holds the
            // payment to.
            t.isBitcoinInvoice -> {
                val est = t.estimate ?: return null
                PendingSend(
                    bitcoinInvoice = BitcoinInvoicePayRequest(
                        request = t.request,
                        maxIncomingSat = est.maxIncomingSat,
                        requestId = requestId,
                    ),
                    amountSat = est.maxIncomingSat,
                    bitcoinAmountSat = t.amountSat,
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
        bitcoinInvoice && e.uncertain && e.status == 504

    /**
     * The node's own refusal, as opposed to an error on the way. 503 is the
     * node saying LND is not answering, before anything was sent; 502 and 504
     * are a cut-off.
     */
    fun definite(status: Int) =
        (status in 400..499 && status != 408 && status != 425 && status != 429) || status == 503

    fun definiteOnRecheck(status: Int) =
        status in 400..499 && status !in setOf(401, 403, 408, 422, 425, 429)

    /** The service asks more than the user agreed to: show the new price. */
    const val PRICE_CHANGED = "price_changed"

    /** Paid already, by this node or not: nothing to try again. */
    const val ALREADY_PAID = "already_paid"
}
