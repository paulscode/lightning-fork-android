package com.paulscode.lightningfork.ui.activity

import com.paulscode.lightningfork.net.ActivityItem

/** How a status reads: a payment on its way, or one that did not go through. */
enum class StatusTone { Waiting, Failed }

/** What an activity item says in the list, apart from the screen. */
object ActivityLabels {
    fun isBitcoinInvoice(item: ActivityItem): Boolean = item.bitcoinInvoice != null

    fun title(item: ActivityItem): String {
        val incoming = item.direction == "in"
        // A Bitcoin invoice reads as one; what it was for is in its details.
        if (item.bitcoinInvoice != null) return "SHA256 invoice"
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
                "returned" -> "Returned" to StatusTone.Failed
                else -> null
            }
        }
        return when (item.status) {
            "pending" -> (if (item.kind == "onchain") "Confirming" else "Pending") to StatusTone.Waiting
            "failed" -> "Failed" to StatusTone.Failed
            else -> null
        }
    }

    /** Nothing left this node: a failed payment, or a returned one. */
    fun didNotMove(item: ActivityItem): Boolean =
        item.status == "failed" || item.bitcoinInvoice?.state == "returned"
}
