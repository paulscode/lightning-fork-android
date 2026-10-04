package com.paulscode.lightningfork.util

import android.content.res.Resources
import com.paulscode.lightningfork.R
import com.paulscode.lightningfork.data.AmountUnit
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DateFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Which chain an amount is on, where both are shown. */
enum class Coin { Btcb2, Sha256 }

/**
 * Numbers, amounts and times as the phone's language writes them: its digits
 * grouping and decimal mark for numbers, its words (from the app's resources)
 * for units and times. Typed amounts are read the same way.
 *
 * The words come from [res], which the app sets as it starts; without it (the
 * JVM tests) they are English.
 */
object Format {
    /** Where the words come from; null in JVM tests, which get English. */
    @Volatile
    var res: Resources? = null

    /** The locale numbers are written in: the phone's, unless a test sets one. */
    @Volatile
    var localeOverride: Locale? = null

    private val locale: Locale get() = localeOverride ?: Locale.getDefault()
    private fun symbols(): DecimalFormatSymbols = DecimalFormatSymbols.getInstance(locale)

    /** A whole number of sats, grouped as the phone writes numbers: "1,234,567". */
    fun sats(sats: Long): String = NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = true }.format(sats)

    /** Sats as BTC, always eight places, ungrouped: "0.00001234" (or "0,00001234"). */
    fun btc(sats: Long): String =
        DecimalFormat("0.00000000", symbols()).format(BigDecimal(sats).movePointLeft(8))

    /** The number alone, in [unit]. */
    fun amount(sats: Long, unit: AmountUnit): String = when (unit) {
        AmountUnit.Sats -> sats(sats)
        AmountUnit.Btc -> btc(sats)
    }

    /**
     * An amount as it goes back into an amount field: no grouping, the
     * phone's decimal mark. Read back by [parseAmount] to the same sats.
     */
    fun editable(sats: Long, unit: AmountUnit): String = when (unit) {
        AmountUnit.Sats -> sats.toString()
        // ASCII digits and a point, as the amount field types them in every
        // language (it turns any decimal mark into one), and parseAmount reads.
        AmountUnit.Btc -> BigDecimal(sats).movePointLeft(8).setScale(8).toPlainString()
    }

    /** A plural's category for a count of any size: its last digits decide. */
    private fun quantity(n: Long): Int = if (n in 0..Int.MAX_VALUE) n.toInt() else (n % 1_000_000).toInt() + 1_000_000

    /**
     * The unit's name. With [coin], named for its chain, where amounts of
     * both are on one screen (paying a SHA256 invoice): "sats (BTCB2)" or
     * "BTCB2", "sats (SHA256)" or "BTC (SHA256)".
     */
    fun unitLabel(unit: AmountUnit, sats: Long = 2, coin: Coin? = null): String {
        if (unit == AmountUnit.Btc) {
            return when (coin) {
                null -> "BTC"
                Coin.Btcb2 -> "BTCB2"
                Coin.Sha256 -> "BTC (SHA256)"
            }
        }
        val r = res
        val q = quantity(sats)
        if (r != null) {
            val id = when (coin) {
                null -> R.plurals.unit_sats
                Coin.Btcb2 -> R.plurals.unit_sats_btcb2
                Coin.Sha256 -> R.plurals.unit_sats_sha256
            }
            return r.getQuantityString(id, q)
        }
        val plain = if (sats == 1L) "sat" else "sats"
        return when (coin) {
            null -> plain
            Coin.Btcb2 -> "$plain (BTCB2)"
            Coin.Sha256 -> "$plain (SHA256)"
        }
    }

    /** "1,234 sats" or "0.00001234 BTC"; with [coin], named for its chain. */
    fun amountWithUnit(sats: Long, unit: AmountUnit, coin: Coin? = null): String =
        "${amount(sats, unit)} ${unitLabel(unit, sats, coin)}"

    /**
     * BTCB2 for one BTC (SHA256), from a rate in BTC (SHA256) per BTCB2:
     * "204.08". Easier to read than the rate itself.
     */
    fun inverseRate(rate: Double): String =
        if (rate <= 0) "—" else DecimalFormat("#,##0.00", symbols()).format(BigDecimal(1 / rate).setScale(2, RoundingMode.HALF_UP))

    /** A SHA256 amount in the chosen currency, from [rate] (BTC (SHA256) per BTCB2) and the BTCB2 price. */
    fun sha256Fiat(sha256Sats: Long, rate: Double, pricePerBtcb2: Double?, currency: String = "USD"): String? =
        if (rate <= 0) null else fiat(kotlin.math.ceil(sha256Sats / rate).toLong(), pricePerBtcb2, currency)

    /** A longest time, roughly and never under it: "about 30 hours", "about 3 days" (for 56 h). */
    fun hoursRoughly(hours: Long): String {
        val r = res
        val days = (hours + 23) / 24
        return when {
            hours <= 1 -> r?.getString(R.string.time_about_an_hour) ?: "about an hour"
            hours < 48 -> r?.getQuantityString(R.plurals.time_about_hours, quantity(hours), hours) ?: "about $hours hours"
            else -> r?.getQuantityString(R.plurals.time_about_days, quantity(days), days) ?: "about $days days"
        }
    }

    /**
     * An estimate in [currency] of [sats] at [price] per coin: "≈ $50.00",
     * "≈ 46,00 €". Whole units from a thousand up.
     */
    fun fiat(sats: Long, price: Double?, currency: String = "USD"): String? {
        if (price == null || price <= 0) return null
        val value = sats / 1e8 * price
        val f = NumberFormat.getCurrencyInstance(locale)
        val cents = runCatching { Currency.getInstance(currency) }.getOrNull()?.also { f.currency = it }?.defaultFractionDigits ?: 2
        // Whole units from a thousand up; below, the currency's own
        // places (none for yen or won).
        val places = if (value >= 1000) 0 else cents.coerceAtLeast(0)
        f.maximumFractionDigits = places
        f.minimumFractionDigits = places
        val written = f.format(value)
        return res?.getString(R.string.format_approx, written) ?: "≈ $written"
    }

    /** A rate to a few significant digits, plainly: "0.0049". */
    fun rate(value: Double): String {
        val bd = BigDecimal(value.toString()).round(MathContext(6)).stripTrailingZeros()
        val f = DecimalFormat("0", symbols())
        f.maximumFractionDigits = maxOf(0, bd.scale())
        return f.format(bd)
    }

    /** A fraction as a percentage, to two places at most: 0.0638 is "6.38%" (or "6,38 %"). */
    fun percent(fraction: Double): String {
        val f = NumberFormat.getPercentInstance(locale)
        f.maximumFractionDigits = 2
        f.minimumFractionDigits = 0
        return f.format(BigDecimal(fraction).setScale(4, RoundingMode.HALF_UP))
    }

    /**
     * Parse what the user typed in [unit] into sats; null when it isn't an
     * amount. The phone's grouping and decimal marks are read as such; a
     * point is a decimal mark in BTC whatever the language (people type it),
     * and a comma is never grouping in BTC where the phone's decimal mark is
     * the point, so "0,5" can't be read as 5 BTC.
     */
    fun parseAmount(text: String, unit: AmountUnit): Long? {
        val sym = symbols()
        val decimal = sym.decimalSeparator
        val grouping = sym.groupingSeparator
        var t = text.trim().filterNot { it == '_' || it == ' ' || it == ' ' || it == ' ' || it == ' ' }
        if (t.isEmpty()) return null
        return when (unit) {
            AmountUnit.Sats -> {
                t = t.filterNot { it == grouping || (grouping == ' ' && it == ' ') }
                // Where the phone groups with a point, a comma is the common
                // typed alternative, and the other way round: neither is a
                // decimal mark in sats.
                t = t.filterNot { it == ',' && decimal != ',' }
                t.toLongOrNull()?.takeIf { it >= 0 && t.all(Char::isDigit) }
            }
            AmountUnit.Btc -> {
                if (decimal != '.' && t.contains(decimal)) {
                    t = t.filterNot { it == grouping }.replace(decimal, '.')
                }
                runCatching {
                    val bd = BigDecimal(t)
                    if (bd.signum() < 0 || bd.scale() > 8) null else bd.movePointRight(8).longValueExact()
                }.getOrNull()
            }
        }
    }

    /** How long ago, briefly: "just now", "5 min ago", "3 h ago", "2 d ago", then the date. */
    fun ago(epochSeconds: Long, nowMs: Long = System.currentTimeMillis()): String {
        val s = (nowMs / 1000 - epochSeconds).coerceAtLeast(0)
        val r = res
        return when {
            s < 45 -> r?.getString(R.string.time_just_now) ?: "just now"
            s < 3600 -> ((s + 30) / 60).let { m -> r?.getQuantityString(R.plurals.time_min_ago, quantity(m), m) ?: "$m min ago" }
            s < 86400 -> (s / 3600).let { h -> r?.getQuantityString(R.plurals.time_h_ago, quantity(h), h) ?: "$h h ago" }
            s < 86400 * 30 -> (s / 86400).let { d -> r?.getQuantityString(R.plurals.time_d_ago, quantity(d), d) ?: "$d d ago" }
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(java.util.Date(epochSeconds * 1000))
        }
    }

    /** "12:34" or "1:02:03" for a countdown in seconds. */
    fun countdown(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(locale, h, m, sec) else "%d:%02d".format(locale, m, sec)
    }

    /** The start and end of a long string, for a glance: "bc1qw5…7kv8f3t4". */
    fun middle(text: String, head: Int = 10, tail: Int = 8): String =
        if (text.length <= head + tail + 1) text else text.take(head) + "…" + text.takeLast(tail)

    /** An address in groups of four, as wallets show them to be checked by eye. */
    fun grouped(text: String): String = text.chunked(4).joinToString(" ")
}
