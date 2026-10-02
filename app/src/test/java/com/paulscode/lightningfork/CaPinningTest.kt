package com.paulscode.lightningfork

import com.paulscode.lightningfork.net.CaPinning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CA-pinning behavior against a real self-signed root and its independently
 * computed `openssl x509 -fingerprint -sha256` (API.md §7). Proves the app both
 * accepts the correct CA and rejects a mismatched fingerprint.
 */
class CaPinningTest {

    // Generated with openssl; fingerprint captured from openssl, not from our code.
    private val pem = """
        -----BEGIN CERTIFICATE-----
        MIIDETCCAfmgAwIBAgIULXMNIr4LMBsNdTfviARFx25o660wDQYJKoZIhvcNAQEL
        BQAwGDEWMBQGA1UEAwwNa3ZjLXRlc3Qtcm9vdDAeFw0yNjA3MTgwMzE1NTRaFw0z
        NjA3MTUwMzE1NTRaMBgxFjAUBgNVBAMMDWt2Yy10ZXN0LXJvb3QwggEiMA0GCSqG
        SIb3DQEBAQUAA4IBDwAwggEKAoIBAQC8sGb0b1G07lNTQIIfXQlJYNa8i46/96b4
        fOURnxmUyPFI/Y1coBp/wTz6w5rLd9rRe7QT59UAHkEC2WBCSYtRTLV4M0yG8sKy
        zNrh7WGQzv8CDaPFMCERkLC3fTKpYuKQf/vGiHs1FzWMZHnp4LnRh3y7vwqF1qGc
        4zfQbOtJe+nnU0MdGhys6wZZIohuFcfRPyTKC0lbC377yon8f6ZVZPMoa+3DEhRZ
        7DlBIdQh/fgL7dQXVGQrYD+B+pa/BofkTV4gMNyLaSTNgzd1I3SGvcUdZxKhNvnP
        uCvwHemD4RQv54QHELZ1T3w7OKvWp6LGRir9fD2CPOASQkedBNj7AgMBAAGjUzBR
        MB0GA1UdDgQWBBRVVLkfs4jRMix/LAcdSOIHXcLpFTAfBgNVHSMEGDAWgBRVVLkf
        s4jRMix/LAcdSOIHXcLpFTAPBgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUA
        A4IBAQCMzl/0wUMCkY005xtOb3BKCOicrTmbFVZAUZToVmw1/EBw7Fs2gxNKhLLf
        z4PLkvPb8yUNY1FUqCBraiy4qXv9oCRZYpRelmbfVmrUKkmwhPTbJpMSykU4uJrp
        D4GEwNOIE23J3gkK0zT+JIsN5OVQj2aEr9tjLR365IWbTWKcEifFA77KVhiOa3kE
        m05Hu0xRs6DV0BsxnUS3AoFbMuFqAZehia7bloSp+ZyglAZ2DlbXyjy+S0ppIAdp
        qkaUxXRYDdu2Qkn2vnx/eDrapmjWeuqCKHSzmE90hhGsJfdz3ke4yLX4KCxYqH3Q
        fXxh/q2u6A9TrfLZE+UZoFCK1MuS
        -----END CERTIFICATE-----
    """.trimIndent().replace("        ", "")

    private val fpr = "5F:73:EC:B7:77:99:0D:A1:ED:AF:12:7F:8A:E7:1D:B1:8E:64:91:C2:CB:37:94:C5:58:28:F5:10:2B:DA:78:BB"

    @Test
    fun fingerprint_matches_openssl() {
        val cert = CaPinning.parsePem(pem)
        assertEquals(fpr, CaPinning.fingerprint(cert))
    }

    @Test
    fun matches_tolerates_case_and_colons() {
        val cert = CaPinning.parsePem(pem)
        assertTrue(CaPinning.matchesFingerprint(cert, fpr.lowercase()))
        assertTrue(CaPinning.matchesFingerprint(cert, fpr.replace(":", "")))
        assertFalse(CaPinning.matchesFingerprint(cert, "AA:BB:CC"))
    }

    @Test
    fun trust_manager_accepts_matching_pin() {
        val tm = CaPinning.trustManagerFor(pem, fpr)
        assertTrue(tm.acceptedIssuers.isNotEmpty())
    }

    @Test
    fun trust_manager_rejects_wrong_pin() {
        assertThrows(CaPinning.PinMismatchException::class.java) {
            CaPinning.trustManagerFor(pem, "00:11:22:33")
        }
    }

    @Test
    fun tofu_captures_root_when_fingerprint_matches() {
        val cert = CaPinning.parsePem(pem) // self-signed: acts as leaf + root
        var captured: java.security.cert.X509Certificate? = null
        val tm = CaPinning.tofuByFingerprint(fpr) { captured = it }
        tm.checkServerTrusted(arrayOf(cert), "RSA")
        assertEquals(fpr, CaPinning.fingerprint(captured!!))
    }

    @Test
    fun tofu_rejects_chain_with_wrong_root() {
        val cert = CaPinning.parsePem(pem)
        val tm = CaPinning.tofuByFingerprint("00:11:22:33") { }
        assertThrows(CaPinning.PinMismatchException::class.java) {
            tm.checkServerTrusted(arrayOf(cert), "RSA")
        }
    }

    @Test
    fun pem_round_trips() {
        val cert = CaPinning.parsePem(pem)
        val reparsed = CaPinning.parsePem(CaPinning.toPem(cert))
        assertEquals(CaPinning.fingerprint(cert), CaPinning.fingerprint(reparsed))
    }
}
