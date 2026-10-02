package com.paulscode.lightningfork.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle

/**
 * Clipboard copies. Sensitive values (a payment's preimage) are marked so they
 * stay out of clipboard previews and history.
 */
object Clipboard {
    private fun manager(context: Context): ClipboardManager =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun copySensitive(context: Context, text: String, label: String = "value") {
        val clip = ClipData.newPlainText(label, text).apply {
            // Keep secrets out of clipboard previews / history where supported (13+).
            description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        manager(context).setPrimaryClip(clip)
    }

    /** Copy something meant to be shared, such as an invoice or an address. */
    fun copyPlain(context: Context, text: String, label: String = "text") {
        manager(context).setPrimaryClip(ClipData.newPlainText(label, text))
    }

}
