package com.paulscode.lightningfork

import com.paulscode.lightningfork.net.ActivityResponse
import com.paulscode.lightningfork.net.ApiJson
import com.paulscode.lightningfork.net.FeesResponse
import com.paulscode.lightningfork.net.PairResponse
import com.paulscode.lightningfork.net.PaymentTarget
import com.paulscode.lightningfork.net.WalletResponse
import com.paulscode.lightningfork.pairing.PairingCodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The node's answers, as the dashboard's mobile API sends them. */
class WireTest {
    @Test fun pairing_code_from_the_dashboard() {
        val qr = """{"lf":1,"code":"e_AlEdpyMgb38OH6tytGA2-i65T5tHIQy1","exp":1790913022312,"lan":"https://umbrel.local:7157","ip":"https://192.168.1.20:7157","onion":"https://${"a".repeat(56)}.onion","ca":"FA:F5:0F"}"""
        val p = PairingCodes.parse(qr)!!
        assertEquals("https://umbrel.local:7157", p.lan)
        assertEquals("FA:F5:0F", p.ca)
        assertFalse(PairingCodes.isExpired(p, 1790913000000))
        assertTrue(PairingCodes.isExpired(p, 1790913100000))
    }

    @Test fun other_codes_are_not_pairing_codes() {
        // Key Value Copy's pairing code, a payment request, junk.
        assertNull(PairingCodes.parse("""{"v":1,"enroll_code":"e_abc","onion_url":"http://x.onion"}"""))
        assertNull(PairingCodes.parse("lnbc1pvjluezpp5qqqsyq"))
        assertNull(PairingCodes.parse("""{"lf":1,"code":"nope"}"""))
        assertNull(PairingCodes.parse(""))
    }

    @Test fun pair_response() {
        val r = ApiJson.decodeFromString(
            PairResponse.serializer(),
            """{"apiKey":"lf_32be4596da4b_x","deviceId":"32be4596da4b","label":"E2E phone","serverId":"101f","apiVersion":1,"caPem":"-----BEGIN CERTIFICATE-----\nMII\n-----END CERTIFICATE-----\n","caSha256":"FA:F5","node":{"alias":"mob-a","pubkey":"031d","network":"regtest","syncedToChain":true,"blockHeight":8122,"activeChannels":1,"peers":1,"future":"ignored"}}""",
        )
        assertEquals("mob-a", r.node!!.alias)
        assertEquals(8122L, r.node!!.blockHeight)
    }

    @Test fun wallet_and_fees() {
        val w = ApiJson.decodeFromString(
            WalletResponse.serializer(),
            """{"onchain":{"confirmedSat":4999671,"unconfirmedSat":0,"lockedSat":0,"reservedSat":0},"lightning":{"outboundSat":1499056,"inboundSat":500000,"pendingOutboundSat":0},"syncedToChain":true,"blockHeight":8122,"updatedAt":1790912722}""",
        )
        assertEquals(4_999_671L, w.onchain.confirmedSat)
        assertEquals(500_000L, w.lightning.inboundSat)
        val f = ApiJson.decodeFromString(
            FeesResponse.serializer(),
            """{"source":{"kind":"mempool","name":"Mempool"},"warning":null,"minimumSatPerVbyte":1,"low":{"satPerVbyte":4,"label":"Low","eta":"About an hour"},"medium":{"satPerVbyte":8,"label":"Medium","eta":"About 30 minutes"},"high":{"satPerVbyte":14,"label":"High","eta":"About 10 minutes"}}""",
        )
        assertEquals(14L, f.high.satPerVbyte)
        assertNull(f.warning)
    }

    @Test fun unified_request_with_its_onchain_fallback() {
        val t = ApiJson.decodeFromString(
            PaymentTarget.serializer(),
            """{"kind":"bolt11","request":"lnbc25u1p","amountSat":2500,"amountEditable":false,"description":"coffee","destination":"03aa","paymentHash":"aa","createdAt":1,"expiresAt":3601,"expired":false,"ours":false,"fallback":{"kind":"onchain","request":"bc1qw5","address":"bc1qw5","addressType":"p2wpkh","amountSat":2500,"amountEditable":false,"description":"","label":""}}""",
        )
        assertTrue(t.isLightning)
        assertNotNull(t.fallback)
        assertEquals("onchain", t.fallback!!.kind)
        assertFalse(t.fallback!!.isLightning)
    }

    @Test fun unsupported_carries_a_message() {
        val t = ApiJson.decodeFromString(
            PaymentTarget.serializer(),
            """{"kind":"unsupported","message":"Lightning addresses are not supported yet."}""",
        )
        assertEquals("unsupported", t.kind)
        assertTrue(t.message!!.startsWith("Lightning addresses"))
    }

    @Test fun a_pending_send_round_trips() {
        val p = com.paulscode.lightningfork.net.PendingSend(
            pay = com.paulscode.lightningfork.net.PayRequest(request = "lnbc1", amountSat = null, requestId = "req-12345678"),
            amountSat = 2500,
            startedAtMs = 5,
        )
        val back = ApiJson.decodeFromString(
            com.paulscode.lightningfork.net.PendingSend.serializer(),
            ApiJson.encodeToString(com.paulscode.lightningfork.net.PendingSend.serializer(), p),
        )
        assertEquals(p, back)
        assertTrue(back.lightning)
        // The request goes out exactly as it was first sent: same id.
        assertEquals("req-12345678", back.pay!!.requestId)
    }

    @Test fun activity() {
        val a = ApiJson.decodeFromString(
            ActivityResponse.serializer(),
            """{"items":[{"id":"tx:t1","kind":"onchain","direction":"out","amountSat":25000,"feeSat":2170,"timestamp":1790912800,"status":"pending","confirmations":0,"description":"","reference":"f3f9"},{"id":"inv:01","kind":"lightning","direction":"in","amountSat":2500,"feeSat":0,"timestamp":1790912700,"status":"complete","description":"Coffee","reference":"01"}]}""",
        )
        assertEquals(2, a.items.size)
        assertEquals("Coffee", a.items[1].description)
    }
}
