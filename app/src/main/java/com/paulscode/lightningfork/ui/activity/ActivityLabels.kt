package com.paulscode.lightningfork.ui.activity

import com.paulscode.lightningfork.net.ActivityItem

/** How a status reads: on its way, did not go through, or came back (nothing lost). */
enum class StatusTone { Waiting, Failed, Returned }

/** What an activity item says in the list, apart from the screen. */
object ActivityLabels {
    fun isBitcoinInvoice(item: ActivityItem): Boolean = item.bitcoinInvoice != null

    fun title(item: ActivityItem): String {
        val incoming = item.direction == "in"
        // A SHA256 invoice reads as what it was for, when it says.
        item.bitcoinInvoice?.let { inv ->
            return inv.description.ifBlank { item.description }.ifBlank { "SHA256 invoice" }
        }
        return item.description.ifBlank {
            when {
                incoming && item.kind == "onchain" -> "Received on-chain"
                incoming -> "Received"
                item.kind == "onchain" -> "Sent on-chain"
                else -> "Sent"
            }
        }
    }

    /** The status pill, or null for one that is done. */
    fun status(item: ActivityItem): Pair<String, StatusTone>? {
        item.bitcoinInvoice?.let {
            return when (it.state) {
                "pending" -> "On its way" to StatusTone.Waiting
                "returned" -> "Returned" to StatusTone.Returned
                else -> null
            }
        }
        return when (item.status) {
            "pending" -> (if (item.kind == "onchain") "Confirming" else "Pending") to StatusTone.Waiting
            "failed" -> "Failed" to StatusTone.Failed
            else -> null
        }
    }

    /** The line under a SHA256 invoice's title: what it is, and through whom. */
    fun subtitle(item: ActivityItem): String? {
        val inv = item.bitcoinInvoice ?: return null
        return if (inv.serviceLabel.isNotBlank()) "SHA256 invoice via ${inv.serviceLabel}" else "SHA256 invoice"
    }

    /** Whether the list should look again on its own: a SHA256 invoice still on its way. */
    fun anyOnItsWay(items: List<ActivityItem>): Boolean = items.any { it.bitcoinInvoice?.state == "pending" }

    /** Nothing left this node: a failed payment, or a returned one. */
    fun didNotMove(item: ActivityItem): Boolean =
        item.status == "failed" || item.bitcoinInvoice?.state == "returned"
}
