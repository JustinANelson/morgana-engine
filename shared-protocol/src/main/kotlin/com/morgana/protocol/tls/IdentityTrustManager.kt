package com.morgana.protocol.tls

import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager

/**
 * Decides whether to trust the server's certificate, in this order:
 * 1. an invite-code fingerprint ([pinned]) must match exactly, otherwise [InviteMismatchException];
 * 2. a fingerprint remembered for this `host:port` must match, otherwise [ServerIdentityChangedException];
 * 3. a certificate that a normal certificate authority vouches for (system trust + host name check) is accepted;
 * 4. anything else throws [UnknownServerException] carrying the fingerprint, so the UI can ask the player once.
 *
 * The TLS handshake aborts on every exception, and [observedFingerprint] stays available to the caller.
 * Instances are per connection attempt.
 */
class IdentityTrustManager(
    private val host: String,
    private val port: Int,
    private val pinned: String?,
    private val known: KnownHostStore,
    private val systemTrust: X509TrustManager? = defaultSystemTrust()
) : X509ExtendedTrustManager() {

    @Volatile
    var observedFingerprint: String? = null
        private set

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = verify(chain, authType, null)
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) = verify(chain, authType, null)
    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) = verify(chain, authType, engine)

    private fun verify(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) {
        if (chain.isEmpty()) throw CertificateException("Server sent no certificate")
        val actual = Fingerprint.of(chain[0])
        observedFingerprint = actual

        if (pinned != null) {
            if (pinned == actual) return
            throw InviteMismatchException(host, port, pinned, actual)
        }
        known.fingerprintFor(host, port)?.let { expected ->
            if (expected == actual) return
            throw ServerIdentityChangedException(host, port, expected, actual)
        }
        if (systemTrust != null && trustedByAuthority(chain, authType, engine)) return
        throw UnknownServerException(host, port, actual)
    }

    private fun trustedByAuthority(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?): Boolean = try {
        val trust = systemTrust
        if (trust is X509ExtendedTrustManager && engine != null) trust.checkServerTrusted(chain, authType, engine)
        else trust!!.checkServerTrusted(chain, authType)
        true
    } catch (e: CertificateException) {
        false
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
        throw CertificateException("This trust manager only validates servers")
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) =
        throw CertificateException("This trust manager only validates servers")
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) =
        throw CertificateException("This trust manager only validates servers")

    override fun getAcceptedIssuers(): Array<X509Certificate> = systemTrust?.acceptedIssuers ?: emptyArray()

    companion object {
        /** The platform's default trust manager, or null if it cannot be created. */
        fun defaultSystemTrust(): X509TrustManager? = try {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
