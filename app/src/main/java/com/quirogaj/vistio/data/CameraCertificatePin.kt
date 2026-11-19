package com.quirogaj.vistio.data

import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.X509TrustManager

internal class CameraCertificateMismatch : CertificateException("Camera certificate fingerprint mismatch")

internal class CameraCertificatePin(private val origin: URL, fingerprint: String) {
    private val expected = requireNotNull(normalize(fingerprint))
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    val socketFactory = SSLContext.getInstance("TLS").run {
        init(null, arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                verify(chain.firstOrNull() ?: throw CertificateException("Camera certificate missing"))
            }

            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                throw CertificateException("Client certificate authentication is not enabled")
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }), null)
        socketFactory
    }

    fun configure(connection: HttpsURLConnection) {
        val url = connection.url
        require(url.protocol == "https" && url.host.equals(origin.host, ignoreCase = true) &&
            effectivePort(url) == effectivePort(origin)
        )
        connection.sslSocketFactory = socketFactory
        // The manually verified certificate identifies this configured origin even if its
        // self-signed certificate was issued for localhost.
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { host, session ->
            if (!host.equals(origin.host, ignoreCase = true)) {
                false
            } else {
                try {
                    val certificate = session.peerCertificates.firstOrNull() as? X509Certificate
                        ?: throw CertificateException("Camera certificate missing")
                    verify(certificate)
                    true
                } catch (_: CertificateException) {
                    false
                } catch (_: SSLPeerUnverifiedException) {
                    false
                }
            }
        }
    }

    private fun verify(certificate: X509Certificate) {
        val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        if (!MessageDigest.isEqual(actual, expected)) throw CameraCertificateMismatch()
        certificate.checkValidity()
    }

    private fun effectivePort(url: URL) = if (url.port == -1) url.defaultPort else url.port

    companion object {
        private val sha256Hex = Regex("[0-9a-f]{64}")

        fun normalize(value: String): String? =
            value.filterNot { it == ':' || it.isWhitespace() }.lowercase().takeIf(sha256Hex::matches)
    }
}
