package com.paulscode.lightningfork.util

import com.paulscode.lightningfork.data.AmountUnit
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object Format {
    private val symbols = DecimalFormatSymbols(Locale.US)
    private val group = DecimalFormat("#,##0", symbols)

    fun sats(sats: Long): String = group.format(sats)

    fun btc(sats: Long): String =
        BigDecimal(sats).movePointLeft(8).setScale(8, RoundingMode.UNNECESSARY).toPlainString()

    /** The number alone, in [unit]. */
    fun amount(sats: Long, unit: AmountUnit): String = when (unit) {
        AmountUnit.Sats -> sats(sats)
        AmountUnit.Btc -> btc(sats)
    }

    fun unitLabel(unit: AmountUnit, sats: Long = 2): String = when (unit) {
        AmountUnit.Sats -> if (sats == 1L) "sat" else "sats"
        AmountUnit.Btc -> "BTC"
    }

    /** "1,234 sats" or "0.00001234 BTC". */
    fun amountWithUnit(sats: Long, unit: AmountUnit): String = "${amount(sats, unit)} ${unitLabel(unit, sats)}"

    fun fiat(sats: Long, usdPerBtc: Double?): String? {
        if (usdPerBtc == null || usdPerBtc <= 0) return null
        val usd = sats / 1e8 * usdPerBtc
        val f = if (usd >= 1000) DecimalFormat("#,##0", symbols) else DecimalFormat("#,##0.00", symbols)
        return "≈ $" + f.format(usd)
    }

    /** A rate to a few significant digits, plainly: "0.0049". */
    fun rate(value: Double): String =
        BigDecimal(value.toString()).round(java.math.MathContext(6)).stripTrailingZeros().toPlainString()

    /** A fraction as a percentage, to two places at most: 0.0638 is "6.38%". */
    fun percent(fraction: Double): String =
        BigDecimal(fraction * 100).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%"

    /** Parse what the user typed in [unit] into sats; null when it isn't an amount. */
    fun parseAmount(text: String, unit: AmountUnit): Long? {
        // Commas are grouping only in sats; in BTC a comma is not an amount
        // (the field types it as the decimal point).
        val t = text.trim().replace("_", "").replace(" ", "").let { if (unit == AmountUnit.Sats) it.replace(",", "") else it }
        if (t.isEmpty()) return null
        return when (unit) {
            AmountUnit.Sats -> t.toLongOrNull()?.takeIf { it >= 0 }
            AmountUnit.Btc -> runCatching {
                val bd = BigDecimal(t)
                if (bd.signum() < 0 || bd.scale() > 8) null else bd.movePointRight(8).longValueExact()
            }.getOrNull()
        }
    }

    /** How long ago, briefly: "just now", "5 min ago", "3 h ago", "2 d ago". */
    fun ago(epochSeconds: Long, nowMs: Long = System.currentTimeMillis()): String {
        val s = (nowMs / 1000 - epochSeconds).coerceAtLeast(0)
        return when {
            s < 45 -> "just now"
            s < 3600 -> "${(s + 30) / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            s < 86400 * 30 -> "${s / 86400} d ago"
            else -> java.text.SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(java.util.Date(epochSeconds * 1000))
        }
    }

    /** "12:34" or "1:02:03" for a countdown in seconds. */
    fun countdown(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, sec) else "%d:%02d".format(Locale.US, m, sec)
    }

    /** The start and end of a long string, for a glance: "bc1qw5…7kv8f3t4". */
    fun middle(text: String, head: Int = 10, tail: Int = 8): String =
        if (text.length <= head + tail + 1) text else text.take(head) + "…" + text.takeLast(tail)

    /** An address in groups of four, as wallets show them to be checked by eye. */
    fun grouped(text: String): String = text.chunked(4).joinToString(" ")
}
