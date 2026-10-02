package com.paulscode.lightningfork

import com.paulscode.lightningfork.data.AmountUnit
import com.paulscode.lightningfork.util.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormatTest {
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
