package com.paulscode.lightningfork

import com.paulscode.lightningfork.data.AmountUnit
import com.paulscode.lightningfork.net.FeeRate
import com.paulscode.lightningfork.net.FeesResponse
import com.paulscode.lightningfork.net.OnchainEstimate
import com.paulscode.lightningfork.net.AddressResponse
import com.paulscode.lightningfork.net.PaymentTarget
import com.paulscode.lightningfork.ui.receive.ReceiveUi
import com.paulscode.lightningfork.ui.send.FeeLevel
import com.paulscode.lightningfork.ui.send.SendUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowStateTest {
    private val invoice = PaymentTarget(kind = "bolt11", request = "lnbc", amountSat = 2500, amountEditable = false)
    private val address = PaymentTarget(kind = "onchain", request = "bc1q", address = "bc1q", amountEditable = true)
    private val fees = FeesResponse(low = FeeRate(4), medium = FeeRate(8), high = FeeRate(14))

    @Test fun a_fixed_amount_is_the_requests() {
        assertEquals(2500L, SendUi(target = invoice).amountSat)
    }

    @Test fun a_typed_amount_follows_the_unit() {
        assertEquals(1500L, SendUi(target = address, amountText = "1500").amountSat)
        assertEquals(150_000_000L, SendUi(target = address, unit = AmountUnit.Btc, amountText = "1.5").amountSat)
        assertNull(SendUi(target = address, amountText = "0").amountSat)
        assertNull(SendUi(target = address, amountText = "").amountSat)
    }

    @Test fun send_all_takes_the_estimate() {
        val s = SendUi(target = address, sendAll = true, estimate = OnchainEstimate(amountSat = 99_000, feeSat = 1000, satPerVbyte = 4, totalSat = 100_000, sendAll = true))
        assertEquals(99_000L, s.amountSat)
        assertNull(SendUi(target = address, sendAll = true).amountSat)
    }

    @Test fun the_fee_level_picks_the_rate() {
        assertEquals(8L, SendUi(target = address, fees = fees).satPerVbyte)
        assertEquals(4L, SendUi(target = address, fees = fees, feeLevel = FeeLevel.Low).satPerVbyte)
        assertEquals(14L, SendUi(target = address, fees = fees, feeLevel = FeeLevel.High).satPerVbyte)
        assertNull(SendUi(target = address).satPerVbyte)
    }

    @Test fun a_unified_request_can_be_paid_onchain() {
        val unified = invoice.copy(fallback = address.copy(amountSat = 2500, amountEditable = false))
        assertTrue(SendUi(target = unified).active!!.isLightning)
        val onchain = SendUi(target = unified, payOnchain = true)
        assertEquals("onchain", onchain.active!!.kind)
        assertTrue(onchain.onchain)
        assertEquals(2500L, onchain.amountSat)
    }

    @Test fun the_receive_qr_carries_an_amount_only_when_asked() {
        val a = AddressResponse(address = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")
        assertEquals("bitcoin:BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4", ReceiveUi(address = a).onchainUri)
        assertEquals(
            "bitcoin:bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4?amount=0.0005",
            ReceiveUi(address = a, onchainAmountText = "50000").onchainUri,
        )
        assertEquals(
            "bitcoin:bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4?amount=1",
            ReceiveUi(address = a, unit = AmountUnit.Btc, onchainAmountText = "1").onchainUri,
        )
        assertNull(ReceiveUi().onchainUri)
    }
}
