package com.paulscode.lightningfork.ui.activity

import com.paulscode.lightningfork.R
import com.paulscode.lightningfork.net.ActivityItem
import com.paulscode.lightningfork.ui.text.UiText

/** How a status reads: on its way, did not go through, or came back (nothing lost). */
enum class StatusTone { Waiting, Failed, Returned }

/** What an activity item says in the list, apart from the screen. */
object ActivityLabels {
    fun isBitcoinInvoice(item: ActivityItem): Boolean = item.bitcoinInvoice != null

    fun title(item: ActivityItem): UiText {
        val incoming = item.direction == "in"
        // A SHA256 invoice reads as what it was for, when it says.
        item.bitcoinInvoice?.let { inv ->
            val about = inv.description.ifBlank { item.description }
            return if (about.isNotBlank()) UiText.raw(about) else UiText.of(R.string.activity_sha256_invoice)
        }
        if (item.description.isNotBlank()) return UiText.raw(item.description)
        return UiText.of(
            when {
                incoming && item.kind == "onchain" -> R.string.activity_received_onchain
                incoming -> R.string.activity_received
                item.kind == "onchain" -> R.string.activity_sent_onchain
                else -> R.string.activity_sent
            },
        )
    }

    /** The status pill, or null for one that is done. */
    fun status(item: ActivityItem): Pair<UiText, StatusTone>? {
        item.bitcoinInvoice?.let {
            return when (it.state) {
                "pending" -> UiText.of(R.string.activity_status_on_its_way) to StatusTone.Waiting
                "returned" -> UiText.of(R.string.activity_status_returned) to StatusTone.Returned
                else -> null
            }
        }
        return when (item.status) {
            "pending" -> UiText.of(if (item.kind == "onchain") R.string.activity_status_confirming else R.string.activity_status_pending) to StatusTone.Waiting
            "failed" -> UiText.of(R.string.activity_status_failed) to StatusTone.Failed
            else -> null
        }
    }

    /** The line under a SHA256 invoice's title: what it is, and through whom. */
    fun subtitle(item: ActivityItem): UiText? {
        val inv = item.bitcoinInvoice ?: return null
        return if (inv.serviceLabel.isNotBlank()) UiText.of(R.string.activity_sha256_invoice_via, inv.serviceLabel) else UiText.of(R.string.activity_sha256_invoice)
    }

    /** Whether the list should look again on its own: a SHA256 invoice still on its way. */
    fun anyOnItsWay(items: List<ActivityItem>): Boolean = items.any { it.bitcoinInvoice?.state == "pending" }

    /** Nothing left this node: a failed payment, or a returned one. */
    fun didNotMove(item: ActivityItem): Boolean =
        item.status == "failed" || item.bitcoinInvoice?.state == "returned"
}
