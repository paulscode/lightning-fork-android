package com.paulscode.lightningfork

import com.paulscode.lightningfork.net.ActivityResponse
import com.paulscode.lightningfork.net.ApiException
import com.paulscode.lightningfork.net.ApiJson
import com.paulscode.lightningfork.net.BitcoinInvoicePayRequest
import com.paulscode.lightningfork.net.ErrorBody
import com.paulscode.lightningfork.net.PayResponse
import com.paulscode.lightningfork.net.PaymentTarget
import com.paulscode.lightningfork.net.PendingSend
import com.paulscode.lightningfork.ui.activity.ActivityLabels
import com.paulscode.lightningfork.ui.activity.StatusTone
import com.paulscode.lightningfork.ui.send.SendRules
import com.paulscode.lightningfork.ui.send.SendUi
import com.paulscode.lightningfork.util.Format
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Paying a Bitcoin invoice through the service: the node's answers, and what the app makes of them. */
class BitcoinInvoiceTest {
    private val payable = ApiJson.decodeFromString(
        PaymentTarget.serializer(),
        """{"kind":"bitcoin-invoice","request":"lnbc1500n1pexample","amountSat":150,"amountEditable":false,"description":"A sticker","destination":"02bb","paymentHash":"9f","createdAt":1790912000,"expiresAt":1790915600,"expired":false,"ours":false,"payable":true,"message":null,"estimate":{"incomingSat":30919,"feeSat":307,"maxIncomingSat":31074,"routingFeeLimitSat":310,"rate":0.0049,"spread":0.01,"minSat":100,"maxSat":500000,"serviceLabel":"Example service","open":true},"reference":{"rate":0.004875,"premiumAllowed":0.0638,"premium":0.0048,"withinLimit":true,"source":"Neoxa"}}""",
    )

    private val notSetUp = ApiJson.decodeFromString(
        PaymentTarget.serializer(),
        """{"kind":"bitcoin-invoice","request":"lnbc1500n1pexample","amountSat":150,"amountEditable":false,"description":"","paymentHash":"9f","createdAt":1790912000,"expiresAt":1790915600,"expired":false,"ours":false,"estimate":null,"message":"Paying Bitcoin invoices is not set up. Set it up in the dashboard's settings.","payable":false}""",
    )

    // The wire.

    @Test fun a_bitcoin_invoice_as_decode_describes_it() {
        assertTrue(payable.isBitcoinInvoice)
        assertFalse(payable.isLightning)
        assertTrue(payable.payable)
        val est = payable.estimate!!
        assertEquals(31_074L, est.maxIncomingSat)
        assertEquals(30_919L, est.incomingSat)
        assertEquals(307L, est.feeSat)
        assertEquals(310L, est.routingFeeLimitSat)
        assertEquals(0.0049, est.rate, 0.0)
        assertEquals("Example service", est.serviceLabel)
        assertEquals(0.0638, payable.reference!!.premiumAllowed, 0.0)
        assertEquals("Neoxa", payable.reference!!.source)
        assertNull(payable.referenceError)
    }

    @Test fun one_that_cant_be_paid_says_why() {
        assertFalse(notSetUp.payable)
        assertNull(notSetUp.estimate)
        assertTrue(notSetUp.message!!.startsWith("Paying Bitcoin invoices is not set up"))
        val noRate = ApiJson.decodeFromString(
            PaymentTarget.serializer(),
            """{"kind":"bitcoin-invoice","request":"lnbc1","amountSat":150,"amountEditable":false,"payable":false,"message":"No market rate.","estimate":{"incomingSat":30919,"feeSat":307,"maxIncomingSat":31074,"routingFeeLimitSat":310,"rate":0.0049,"spread":0.01,"serviceLabel":"","open":true},"referenceError":"No market rate."}""",
        )
        assertNull(noRate.reference)
        assertEquals("No market rate.", noRate.referenceError)
    }

    @Test fun other_kinds_stay_payable() {
        val t = ApiJson.decodeFromString(PaymentTarget.serializer(), """{"kind":"bolt11","request":"lnbc25u1p","amountSat":2500,"amountEditable":false}""")
        assertTrue(t.payable)
        assertFalse(t.isBitcoinInvoice)
        assertNull(SendUi(target = t).bitcoinInvoiceBlocker)
    }

    @Test fun the_payment_and_its_proof() {
        val r = ApiJson.decodeFromString(
            PayResponse.serializer(),
            """{"status":"succeeded","paymentHash":"9f","preimage":"ab12","amountSat":30950,"feeSat":12,"bitcoinInvoice":{"amountSat":150,"description":"A sticker","paymentHash":"9f"}}""",
        )
        assertEquals(30_950L, r.amountSat)
        assertEquals(150L, r.bitcoinInvoice!!.amountSat)
        assertEquals("ab12", r.preimage)
    }

    @Test fun the_request_as_sent() {
        val json = ApiJson.encodeToString(
            BitcoinInvoicePayRequest.serializer(),
            BitcoinInvoicePayRequest(request = "lnbc1", maxIncomingSat = 31_074, requestId = "req-12345678"),
        )
        // No resume on a first attempt: it is left out, not sent as null.
        assertEquals("""{"request":"lnbc1","maxIncomingSat":31074,"requestId":"req-12345678"}""", json)
    }

    @Test fun refusals_carry_a_code() {
        val e = ApiJson.decodeFromString(ErrorBody.serializer(), """{"error":"The service now asks more.","code":"price_changed"}""")
        assertEquals("price_changed", e.code)
        assertFalse(e.uncertain)
        val u = ApiJson.decodeFromString(ErrorBody.serializer(), """{"error":"Your payment is on its way.","uncertain":true}""")
        assertTrue(u.uncertain)
        assertNull(u.code)
    }

    // The review.

    @Test fun the_cost_is_the_ceiling_shown() {
        val s = SendUi(target = payable)
        assertTrue(s.bitcoinInvoice)
        assertFalse(s.onchain)
        assertEquals(31_074L, s.amountSat)
        assertNull(s.bitcoinInvoiceBlocker)
    }

    @Test fun not_payable_cant_be_sent() {
        assertEquals("Can't pay this now", SendUi(target = notSetUp).bitcoinInvoiceBlocker)
        assertNull(SendUi(target = notSetUp).amountSat)
        // The node's word decides, even with a price.
        assertNotNull(SendUi(target = payable.copy(payable = false, message = "The service is closed.")).bitcoinInvoiceBlocker)
        assertNull(SendRules.pendingFor(SendUi(target = notSetUp), "req-12345678", 5))
    }

    @Test fun the_agreed_ceiling_goes_to_the_node() {
        val p = SendRules.pendingFor(SendUi(target = payable), "req-12345678", 5)!!
        val req = p.bitcoinInvoice!!
        assertEquals("lnbc1500n1pexample", req.request)
        assertEquals(31_074L, req.maxIncomingSat)
        assertEquals("req-12345678", req.requestId)
        assertNull(req.resume)
        assertNull(p.pay)
        assertNull(p.onchain)
        assertTrue(p.lightning)
        assertEquals(31_074L, p.amountSat)
        assertEquals(150L, p.bitcoinAmountSat)
    }

    // An uncertain payment, and asking again.

    @Test fun a_pending_bitcoin_invoice_round_trips_and_resumes_unchanged() {
        val p = SendRules.pendingFor(SendUi(target = payable), "req-12345678", 5)!!
        val back = ApiJson.decodeFromString(PendingSend.serializer(), ApiJson.encodeToString(PendingSend.serializer(), p))
        assertEquals(p, back)
        val again = SendRules.bitcoinInvoiceRequest(back, again = true)!!
        assertEquals(true, again.resume)
        // The same id and the same ceiling: the node finds the same payment.
        assertEquals("req-12345678", again.requestId)
        assertEquals(31_074L, again.maxIncomingSat)
        assertNull(SendRules.bitcoinInvoiceRequest(back, again = false)!!.resume)
        // An older pending send, kept before this kind existed, still reads.
        val old = ApiJson.decodeFromString(PendingSend.serializer(), """{"pay":{"request":"lnbc1","requestId":"req-1"},"amountSat":2500,"startedAtMs":5}""")
        assertNull(old.bitcoinInvoice)
        assertNull(SendRules.bitcoinInvoiceRequest(old, again = true))
    }

    @Test fun on_its_way_is_asked_about_again() {
        val waiting = ApiException(504, "Your payment is on its way and the Bitcoin invoice is being paid.", uncertain = true)
        assertFalse(SendRules.settles(waiting, again = false, bitcoinInvoice = true))
        assertFalse(SendRules.settles(waiting, again = true, bitcoinInvoice = true))
        assertTrue(SendRules.onItsWay(waiting, bitcoinInvoice = true))
        assertFalse(SendRules.onItsWay(waiting, bitcoinInvoice = false))
    }

    @Test fun a_refusal_with_a_code_settles_it_even_on_a_reask() {
        for ((status, code) in listOf(409 to "price_changed", 409 to "in_progress", 409 to "already_paid", 429 to "limit", 503 to "unreachable", 503 to "reference_unavailable", 400 to "returned")) {
            val e = ApiException(status, "Nothing was paid.", code = code)
            assertTrue(code, SendRules.settles(e, again = false, bitcoinInvoice = true))
            assertTrue(code, SendRules.settles(e, again = true, bitcoinInvoice = true))
            assertFalse(SendRules.onItsWay(e, bitcoinInvoice = true))
        }
    }

    @Test fun without_a_code_the_usual_rules_hold() {
        // The node not answering, on a re-ask: still unknown.
        assertFalse(SendRules.settles(ApiException(503, "Your node is not answering."), again = true, bitcoinInvoice = true))
        assertTrue(SendRules.settles(ApiException(503, "Your node is not answering."), again = false, bitcoinInvoice = true))
        // A reused id, a rate limit: not the node's word on the payment.
        assertFalse(SendRules.settles(ApiException(422, "Reused id"), again = true, bitcoinInvoice = true))
        assertFalse(SendRules.settles(ApiException(429, "Too many"), again = false, bitcoinInvoice = true))
        // A Lightning payment is judged as before: a code changes nothing.
        assertFalse(SendRules.settles(ApiException(503, "x", code = "unreachable"), again = true))
        assertFalse(SendRules.settles(ApiException(504, "x"), again = false))
        assertTrue(SendRules.settles(ApiException(400, "x"), again = false))
    }

    // Activity.

    private val activity = ApiJson.decodeFromString(
        ActivityResponse.serializer(),
        """{"items":[
            {"id":"pay:9f","kind":"lightning","direction":"out","amountSat":30950,"feeSat":12,"timestamp":1790912800,"status":"complete","description":"A sticker","reference":"9f","bitcoinInvoice":{"amountSat":150,"description":"A sticker","state":"paid"},"preimage":"ab12"},
            {"id":"pay:8e","kind":"lightning","direction":"out","amountSat":30950,"feeSat":0,"timestamp":1790912700,"status":"pending","description":"","reference":"8e","bitcoinInvoice":{"amountSat":150,"description":"","state":"pending"}},
            {"id":"pay:7d","kind":"lightning","direction":"out","amountSat":30950,"feeSat":0,"timestamp":1790912600,"status":"failed","description":"","reference":"7d","bitcoinInvoice":{"amountSat":150,"description":"","state":"returned"}},
            {"id":"pay:6c","kind":"lightning","direction":"out","amountSat":2500,"feeSat":1,"timestamp":1790912500,"status":"pending","description":"Coffee","reference":"6c"}
        ]}""",
    )

    @Test fun activity_shows_bitcoin_invoices_as_such() {
        val (paid, pending, returned, plain) = activity.items
        assertEquals("Bitcoin invoice", ActivityLabels.title(paid))
        assertTrue(ActivityLabels.isBitcoinInvoice(paid))
        assertEquals(150L, paid.bitcoinInvoice!!.amountSat)
        assertEquals(30_950L, paid.amountSat)
        assertEquals("ab12", paid.preimage)
        assertNull(ActivityLabels.status(paid))
        assertFalse(ActivityLabels.didNotMove(paid))

        assertEquals("On its way" to StatusTone.Waiting, ActivityLabels.status(pending))
        assertNull(pending.preimage)

        assertEquals("Returned" to StatusTone.Failed, ActivityLabels.status(returned))
        assertTrue(ActivityLabels.didNotMove(returned))

        assertFalse(ActivityLabels.isBitcoinInvoice(plain))
        assertEquals("Coffee", ActivityLabels.title(plain))
        assertEquals("Pending" to StatusTone.Waiting, ActivityLabels.status(plain))
    }

    @Test fun rates_and_premiums_read_plainly() {
        assertEquals("0.0049", Format.rate(0.0049))
        assertEquals("0.004875", Format.rate(0.004875))
        assertEquals("6.38%", Format.percent(0.0638))
        assertEquals("0.48%", Format.percent(0.0048))
        assertEquals("5%", Format.percent(0.05))
    }
}
