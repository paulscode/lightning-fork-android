package com.paulscode.lightningfork.net

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.cert.CertificateFactory
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Pinning to the node's root certificate. The root the node hands over at
 * pairing is the *sole* trust anchor for its HTTPS addresses; the public trust
 * store is not consulted. The root is checked against the fingerprint in the
 * pairing QR code (the whole certificate's SHA-256, as `openssl x509
 * -fingerprint -sha256` prints it; not an OkHttp SPKI pin).
 */
object CaPinning {

    class PinMismatchException(msg: String) : CertificateException(msg)

    /** Parse a PEM root CA into an X509Certificate. */
    fun parsePem(pem: String): X509Certificate {
        val cf = CertificateFactory.getInstance("X.509")
        return ByteArrayInputStream(pem.toByteArray(Charsets.US_ASCII)).use {
            cf.generateCertificate(it) as X509Certificate
        }
    }

    /** Whole-certificate SHA-256, colon-separated uppercase hex (== `openssl -fingerprint -sha256`). */
    fun fingerprint(cert: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString(":") { "%02X".format(it) }
    }

    /** True if `ca_sha256` matches the cert (tolerant of case and `:` presence). */
    fun matchesFingerprint(cert: X509Certificate, caSha256: String): Boolean {
        val norm = { s: String -> s.replace(":", "").uppercase() }
        return norm(fingerprint(cert)) == norm(caSha256)
    }

    /**
     * Build a trust manager anchored solely on [pem]. If [expectedSha256] is
     * non-null, the parsed cert must match it or a [PinMismatchException] is thrown.
     */
    fun trustManagerFor(pem: String, expectedSha256: String? = null): X509TrustManager {
        val cert = parsePem(pem)
        if (expectedSha256 != null && !matchesFingerprint(cert, expectedSha256)) {
            throw PinMismatchException(
                "ca_pem fingerprint ${fingerprint(cert)} != expected $expectedSha256"
            )
        }
        return trustManagerForCert(cert)
    }

    /** SSLSocketFactory pinned to [tm] (TLS 1.2+). */
    fun sslContextFor(tm: X509TrustManager): SSLContext =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }

    /** Serialize a cert back to PEM (for persisting a root captured over LAN). */
    fun toPem(cert: X509Certificate): String {
        val b64 = java.util.Base64.getEncoder().encodeToString(cert.encoded)
        return buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            b64.chunked(64).forEach { append(it).append('\n') }
            append("-----END CERTIFICATE-----\n")
        }
    }

    /**
     * Trust-on-first-use-by-fingerprint: accept a presented chain only if its
     * self-signed **root** matches [expectedSha256] (the QR pin) AND the chain
     * validly links to it. Used once during LAN pairing to capture `ca_pem` when
     * we don't have it yet; the QR fingerprint is the real security boundary, so
     * this is exactly as strong as pinning. The captured root is handed to
     * [onRoot] for persisting.
     */
    fun tofuByFingerprint(
        expectedSha256: String,
        onRoot: (X509Certificate) -> Unit,
    ): X509TrustManager = object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certs = chain?.toList().orEmpty()
            if (certs.isEmpty()) throw CertificateException("empty certificate chain")
            val root = certs.lastOrNull { it.subjectX500Principal == it.issuerX500Principal }
                ?: throw PinMismatchException("no self-signed root in presented chain")
            if (!matchesFingerprint(root, expectedSha256)) {
                throw PinMismatchException(
                    "root fingerprint ${fingerprint(root)} != expected $expectedSha256"
                )
            }
            // Verify the leaf actually chains to that pinned root.
            trustManagerForCert(root).checkServerTrusted(certs.toTypedArray(), authType ?: authTypeOf(certs[0]))
            onRoot(root)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun trustManagerForCert(cert: X509Certificate): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("node-root", cert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * Hostname verifier for LAN: the CA pin is the real security boundary, so accept
     * when the peer chains to our pinned anchor. Prefer the default SAN check first
     * (covers the `.local` name); fall back to trusting the pinned chain when
     * connecting by IP, which the leaf's SAN may not list.
     */
    fun hostnameVerifier(tm: X509TrustManager): HostnameVerifier =
        HostnameVerifier { host, session ->
            if (DEFAULT.verify(host, session)) return@HostnameVerifier true
            try {
                val chain = session.peerCertificates.filterIsInstance<X509Certificate>()
                if (chain.isEmpty()) return@HostnameVerifier false
                tm.checkServerTrusted(chain.toTypedArray(), authTypeOf(chain[0]))
                true
            } catch (_: Exception) {
                false
            }
        }

    /** The key exchange a leaf's key implies, for checks made outside a handshake. */
    private fun authTypeOf(leaf: X509Certificate): String =
        if (leaf.publicKey.algorithm == "EC") "ECDHE_ECDSA" else "ECDHE_RSA"

    private val DEFAULT: HostnameVerifier =
        javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
}
