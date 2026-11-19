package com.quirogaj.vistio.data

import java.net.Proxy
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
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

internal data class CameraCertificateOffer(val subject: String, val fingerprint: String)

/** Check Android trust without sending credentials. An offer never authorizes the probe connection. */
internal fun inspectCameraCertificate(origin: URL): CameraCertificateOffer? {
    val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
        init(null as KeyStore?)
        trustManagers.filterIsInstance<X509TrustManager>().single()
    }
    var presented: X509Certificate? = null
    var rejectedByTrustManager = false
    val observer = object : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            presented = chain.firstOrNull()
            try {
                trust.checkServerTrusted(chain, authType)
            } catch (failure: CertificateException) {
                rejectedByTrustManager = true
                throw failure
            }
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
            trust.checkClientTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = trust.acceptedIssuers
    }
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(observer), null) }
    val connection = origin.openConnection(Proxy.NO_PROXY) as HttpsURLConnection
    return try {
        connection.sslSocketFactory = ssl.socketFactory
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 6_000
        connection.readTimeout = 6_000
        connection.connect()
        null
    } catch (failure: SSLException) {
        val cert = presented ?: throw failure
        if (!rejectedByTrustManager && failure !is SSLPeerUnverifiedException) throw failure
        if (cert.issuerX500Principal != cert.subjectX500Principal) throw failure
        try {
            cert.checkValidity()
            cert.verify(cert.publicKey)
        } catch (_: java.security.GeneralSecurityException) {
            throw failure
        }
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        CameraCertificateOffer(cert.subjectX500Principal.name, fingerprint)
    } finally {
        connection.disconnect()
    }
}
