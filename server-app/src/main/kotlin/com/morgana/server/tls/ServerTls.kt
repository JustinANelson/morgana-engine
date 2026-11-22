package com.morgana.server.tls

import com.morgana.persistence.TlsConfig
import com.morgana.persistence.TlsMode
import com.morgana.protocol.tls.Fingerprint
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import javax.net.ssl.KeyManagerFactory

/** The TLS setup could not be completed; the message says what to fix. The server must not silently start unencrypted. */
class TlsSetupException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A certificate with its private key and the fingerprint clients pin. */
class TlsIdentity(val keyStore: KeyStore, val password: CharArray, val certificate: X509Certificate) {
    val fingerprint: String get() = Fingerprint.of(certificate)
}

/**
 * Creates and stores the self-signed certificate of a world (valid for `localhost`, loopback addresses and this
 * machine's host name). Players do not rely on the names: they verify the SHA-256 [Fingerprint] from the invite code.
 */
object SelfSignedCertificates {
    /** Protects the auto-generated keystore only against accidental reading; the real protection is the file system. */
    private val AUTO_PASSWORD = "morgana-world".toCharArray()
    const val VALID_YEARS = 10L

    fun generate(commonName: String = "Morgana World"): TlsIdentity {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()
        val now = Instant.now()
        val notBefore = Date.from(now.minusSeconds(24 * 3600)) // tolerate clock skew
        val notAfter = Date.from(now.plusSeconds(VALID_YEARS * 365 * 24 * 3600))
        val subject = X500Name("CN=$commonName")
        val names = linkedSetOf(GeneralName(GeneralName.dNSName, "localhost"),
            GeneralName(GeneralName.iPAddress, "127.0.0.1"), GeneralName(GeneralName.iPAddress, "::1"))
        runCatching { InetAddress.getLocalHost().hostName }.getOrNull()?.takeIf { it.isNotBlank() && it != "localhost" }
            ?.let { names += GeneralName(GeneralName.dNSName, it) }

        val builder = JcaX509v3CertificateBuilder(subject, BigInteger(64, SecureRandom()).abs().add(BigInteger.ONE), notBefore, notAfter, subject, keyPair.public)
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment))
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.toTypedArray()))
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))
        return TlsIdentity(keyStoreOf(keyPair.private, cert, AUTO_PASSWORD), AUTO_PASSWORD, cert)
    }

    /** Loads `<dir>/server.p12`, creating a new certificate the first time. */
    fun loadOrCreate(dir: Path): TlsIdentity {
        val file = dir.resolve("server.p12")
        if (Files.isRegularFile(file)) return load(file, AUTO_PASSWORD)
        Files.createDirectories(dir)
        val identity = generate()
        val tmp = dir.resolve("server.p12.tmp")
        Files.newOutputStream(tmp).use { identity.keyStore.store(it, identity.password) }
        runCatching { // best effort: owner-only access where the file system supports it
            Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        return identity
    }

    fun load(file: Path, password: CharArray): TlsIdentity {
        val ks = try {
            KeyStore.getInstance("PKCS12").also { k -> Files.newInputStream(file).use { k.load(it, password) } }
        } catch (e: Exception) {
            throw TlsSetupException("Cannot read keystore $file (wrong password or not a PKCS12 file): ${e.message}", e)
        }
        val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
            ?: throw TlsSetupException("Keystore $file contains no private key entry")
        val cert = ks.getCertificate(alias) as? X509Certificate
            ?: throw TlsSetupException("Keystore $file entry '$alias' has no X.509 certificate")
        return TlsIdentity(ks, password, cert)
    }

    private fun keyStoreOf(key: PrivateKey, cert: X509Certificate, password: CharArray): KeyStore =
        KeyStore.getInstance("PKCS12").also {
            it.load(null, null)
            it.setKeyEntry("morgana", key, password, arrayOf(cert))
        }
}

/** A ready-to-use server TLS configuration. */
class ServerTls(val mode: TlsMode, val sslContext: SslContext, val fingerprint: String, val certificate: X509Certificate) {
    companion object {
        /**
         * Builds the TLS configuration for [dataDir]: null when the effective mode is `off`. Throws [TlsSetupException]
         * rather than ever falling back to plain text.
         */
        fun load(
            dataDir: Path,
            config: TlsConfig?,
            defaultMode: TlsMode = TlsMode.OFF,
            env: (String) -> String? = System::getenv
        ): ServerTls? {
            val mode = config?.let { TlsMode.fromConfigKey(it.mode) } ?: defaultMode
            if (mode == TlsMode.OFF) return null

            val identity = if (config != null && config.keystore.isNotBlank()) {
                val file = dataDir.resolve(config.keystore)
                if (!Files.isRegularFile(file)) throw TlsSetupException("tls.keystore '$file' does not exist")
                val password = env(config.passwordEnv)
                    ?: throw TlsSetupException("Environment variable ${config.passwordEnv} (the keystore password) is not set")
                SelfSignedCertificates.load(file, password.toCharArray())
            } else {
                SelfSignedCertificates.loadOrCreate(dataDir.resolve("tls"))
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(identity.keyStore, identity.password)
            val context = try {
                SslContextBuilder.forServer(kmf)
                    .sslProvider(SslProvider.JDK)
                    .protocols("TLSv1.3", "TLSv1.2")
                    .build()
            } catch (e: Exception) {
                throw TlsSetupException("Cannot create the TLS context: ${e.message}", e)
            }
            return ServerTls(mode, context, identity.fingerprint, identity.certificate)
        }
    }
}
