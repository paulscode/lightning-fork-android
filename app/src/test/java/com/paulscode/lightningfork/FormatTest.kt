package com.paulscode.lightningfork

import com.paulscode.lightningfork.data.AmountUnit
import com.paulscode.lightningfork.util.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
    // Written as in the US unless a test says otherwise; words are English
    // without the app's resources.
    @org.junit.Before fun us() {
        Format.localeOverride = java.util.Locale.US
    }

    @org.junit.After fun reset() {
        Format.localeOverride = null
    }

    @Test fun numbers_follow_the_phones_language() {
        Format.localeOverride = java.util.Locale.GERMANY
        assertEquals("1.234.567", Format.sats(1_234_567))
        assertEquals("0,00001234", Format.btc(1234))
        assertEquals("1,00000000 BTC", Format.amountWithUnit(100_000_000, AmountUnit.Btc))
        // Typed the way the phone writes them, or with a point in BTC.
        assertEquals(2500L, Format.parseAmount("2.500", AmountUnit.Sats))
        assertEquals(150_000_000L, Format.parseAmount("1,5", AmountUnit.Btc))
        assertEquals(150_000_000L, Format.parseAmount("1.5", AmountUnit.Btc))
        assertEquals(1234L, Format.parseAmount(Format.editable(1234, AmountUnit.Btc), AmountUnit.Btc))
        assertNull(Format.parseAmount("1,5", AmountUnit.Sats))
        assertEquals("204,08", Format.inverseRate(0.0049))
        assertEquals("0,0049", Format.rate(0.0049))
        assertEquals("≈ 46,00\u00a0€", Format.fiat(100_000, 46_000.0, "EUR"))
        Format.localeOverride = java.util.Locale.FRANCE
        // French groups with a narrow no-break space; typed with a space.
        assertEquals(2500L, Format.parseAmount("2 500", AmountUnit.Sats))
        assertEquals(2500L, Format.parseAmount(Format.sats(2500), AmountUnit.Sats))
    }

    @Test fun editable_amounts_read_back() {
        for (sats in listOf(0L, 1L, 1234L, 150_000_000L)) {
            assertEquals(sats, Format.parseAmount(Format.editable(sats, AmountUnit.Sats), AmountUnit.Sats))
            assertEquals(sats, Format.parseAmount(Format.editable(sats, AmountUnit.Btc), AmountUnit.Btc))
        }
        assertEquals("≈ €50.00", Format.fiat(100_000, 50_000.0, "EUR"))
        assertEquals("6.38%", Format.percent(0.0638))
        assertEquals("0.0049", Format.rate(0.0049))
    }
    @Test fun sats_are_grouped() {
        assertEquals("0", Format.sats(0))
        assertEquals("1,234,567", Format.sats(1_234_567))
    }

    @Test fun btc_has_eight_places() {
        assertEquals("0.00001234", Format.btc(1234))
        assertEquals("21000000.00000000", Format.btc(2_100_000_000_000_000))
        assertEquals("1.00000000 BTC", Format.amountWithUnit(100_000_000, AmountUnit.Btc))
        assertEquals("1 sat", Format.amountWithUnit(1, AmountUnit.Sats))
        assertEquals("2 sats", Format.amountWithUnit(2, AmountUnit.Sats))
    }

    @Test fun typed_amounts_parse_exactly() {
        assertEquals(2500L, Format.parseAmount("2,500", AmountUnit.Sats))
        assertEquals(2500L, Format.parseAmount(" 2500 ", AmountUnit.Sats))
        assertEquals(1L, Format.parseAmount("0.00000001", AmountUnit.Btc))
        assertEquals(150_000_000L, Format.parseAmount("1.5", AmountUnit.Btc))
        assertNull("more than eight places", Format.parseAmount("0.000000001", AmountUnit.Btc))
        assertNull(Format.parseAmount("-5", AmountUnit.Sats))
        assertNull(Format.parseAmount("1.5", AmountUnit.Sats))
        assertNull(Format.parseAmount("", AmountUnit.Sats))
        assertNull(Format.parseAmount("abc", AmountUnit.Btc))
        // In BTC a comma is never grouping: "0,5" must not read as 5 BTC.
        assertNull(Format.parseAmount("0,5", AmountUnit.Btc))
        assertNull(Format.parseAmount("1.2.3", AmountUnit.Btc))
    }

    @Test fun fiat_estimates() {
        assertEquals("≈ $50.00", Format.fiat(100_000, 50_000.0))
        assertEquals("≈ $1,500", Format.fiat(3_000_000, 50_000.0))
        assertNull(Format.fiat(100, null))
        assertNull(Format.fiat(100, 0.0))
    }

    @Test fun countdown_and_ago() {
        assertEquals("0:05", Format.countdown(5))
        assertEquals("59:58", Format.countdown(3598))
        assertEquals("1:00:00", Format.countdown(3600))
        assertEquals("0:00", Format.countdown(-3))
        val now = 1_800_000_000_000L
        assertEquals("just now", Format.ago(now / 1000 - 10, now))
        assertEquals("5 min ago", Format.ago(now / 1000 - 300, now))
        assertEquals("3 h ago", Format.ago(now / 1000 - 3 * 3600, now))
        assertEquals("2 d ago", Format.ago(now / 1000 - 2 * 86400, now))
    }

    @Test fun long_strings_shorten_and_group() {
        assertEquals("bc1qw508d6…f3t4", Format.middle("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4", 10, 4))
        assertEquals("short", Format.middle("short"))
        assertEquals("bc1q w508 d6", Format.grouped("bc1qw508d6"))
    }
}
