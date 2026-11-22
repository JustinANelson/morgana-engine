package com.morgana.protocol.tls

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/**
 * Server identity for self-signed worlds: a server is recognised by the SHA-256 fingerprint of its certificate,
 * not by a certificate authority. JDK-only code, shared by the server, desktop and Android clients.
 */
object Fingerprint {
    /** Lower-case hex SHA-256 of the DER-encoded certificate (64 characters, no separators). */
    fun of(certificate: X509Certificate): String = ofDer(certificate.encoded)

    fun ofDer(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    private val HEX64 = Regex("[0-9a-f]{64}")

    /** Accepts hex with optional `:`/space separators in any case; returns the canonical form or null. */
    fun normalize(text: String): String? {
        val clean = text.lowercase().replace(Regex("[\\s:]"), "")
        return clean.takeIf { HEX64.matches(it) }
    }

    /** `AB:CD:...` in groups of two, for showing to humans. */
    fun display(fingerprint: String): String = fingerprint.chunked(2).joinToString(":").uppercase()
}

/**
 * A join code a world host can share: `morgana://host:port#fp=<sha-256 hex>`. The fingerprint lets a client verify a
 * self-signed server without any certificate authority or file. A bare `host`, `host:port` or `[v6]:port` also parses
 * (with no fingerprint), so players can type an address.
 */
data class InviteCode(val host: String, val port: Int, val fingerprint: String? = null) {
    fun format(): String {
        val shownHost = if (':' in host) "[$host]" else host
        return "$SCHEME$shownHost:$port" + (fingerprint?.let { "#fp=$it" } ?: "")
    }

    companion object {
        const val SCHEME = "morgana://"
        const val DEFAULT_PORT = 7001

        /** True if [text] names a port itself (`host:1234`, `[v6]:1234`, or an invite code with one). */
        fun hasExplicitPort(text: String): Boolean {
            var rest = text.trim()
            if (rest.startsWith(SCHEME, ignoreCase = true)) rest = rest.substring(SCHEME.length)
            rest = rest.substringBefore('#').trimEnd('/')
            return if (rest.startsWith("[")) rest.contains("]:") else rest.count { it == ':' } == 1
        }

        /** Null when [text] is not a usable address or code (blank, bad port, malformed fingerprint). */
        fun parse(text: String): InviteCode? {
            var rest = text.trim()
            if (rest.isEmpty()) return null
            if (rest.startsWith(SCHEME, ignoreCase = true)) rest = rest.substring(SCHEME.length)
            var fingerprint: String? = null
            val hash = rest.indexOf('#')
            if (hash >= 0) {
                val fragment = rest.substring(hash + 1)
                rest = rest.substring(0, hash)
                val fp = fragment.split('&').firstOrNull { it.startsWith("fp=") }?.removePrefix("fp=")
                    ?: return null
                fingerprint = Fingerprint.normalize(URLDecoder.decode(fp, StandardCharsets.UTF_8)) ?: return null
            }
            rest = rest.trimEnd('/')
            val host: String
            val port: Int
            if (rest.startsWith("[")) {
                val close = rest.indexOf(']')
                if (close < 0) return null
                host = rest.substring(1, close)
                val after = rest.substring(close + 1)
                port = if (after.isEmpty()) DEFAULT_PORT else after.removePrefix(":").toIntOrNull() ?: return null
            } else {
                val colon = rest.lastIndexOf(':')
                if (colon >= 0 && rest.count { it == ':' } == 1) {
                    host = rest.substring(0, colon)
                    port = rest.substring(colon + 1).toIntOrNull() ?: return null
                } else {
                    host = rest
                    port = DEFAULT_PORT
                }
            }
            if (host.isBlank() || host.any { it.isWhitespace() || it == '/' } || port !in 1..65535) return null
            return InviteCode(host, port, fingerprint)
        }
    }
}

/** Remembers which fingerprint a `host:port` presented the first time it was trusted (like SSH's known_hosts). */
interface KnownHostStore {
    fun fingerprintFor(host: String, port: Int): String?
    fun remember(host: String, port: Int, fingerprint: String)
    fun forget(host: String, port: Int)
}

/** Known hosts kept in memory only (used when no storage location is available). */
class InMemoryKnownHosts : KnownHostStore {
    private val map = LinkedHashMap<String, String>()
    @Synchronized override fun fingerprintFor(host: String, port: Int): String? = map[key(host, port)]
    @Synchronized override fun remember(host: String, port: Int, fingerprint: String) { map[key(host, port)] = fingerprint }
    @Synchronized override fun forget(host: String, port: Int) { map.remove(key(host, port)) }
    companion object { fun key(host: String, port: Int) = "${host.lowercase()}:$port" }
}

/** Known hosts persisted as lines of `host:port <fingerprint>`; unreadable lines are ignored. */
class FileKnownHosts(private val file: Path) : KnownHostStore {
    private val map = LinkedHashMap<String, String>()

    init {
        if (Files.isRegularFile(file)) {
            for (line in Files.readAllLines(file)) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size == 2 && Fingerprint.normalize(parts[1]) != null) map[parts[0].lowercase()] = Fingerprint.normalize(parts[1])!!
            }
        }
    }

    @Synchronized override fun fingerprintFor(host: String, port: Int): String? = map[InMemoryKnownHosts.key(host, port)]

    @Synchronized override fun remember(host: String, port: Int, fingerprint: String) {
        map[InMemoryKnownHosts.key(host, port)] = fingerprint
        save()
    }

    @Synchronized override fun forget(host: String, port: Int) {
        if (map.remove(InMemoryKnownHosts.key(host, port)) != null) save()
    }

    private fun save() {
        file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.write(tmp, map.map { "${it.key} ${it.value}" })
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }
}

/** The server presented a certificate we have no reason to trust yet; ask the player, then [KnownHostStore.remember]. */
class UnknownServerException(val host: String, val port: Int, val fingerprint: String) :
    CertificateException("Unknown server $host:$port (certificate ${Fingerprint.display(fingerprint)})")

/** A server we trusted before now shows a different certificate: possible attack, or the host recreated their world. */
class ServerIdentityChangedException(val host: String, val port: Int, val expected: String, val actual: String) :
    CertificateException("The identity of $host:$port changed")

/** The server does not match the fingerprint in the invite code that was used. */
class InviteMismatchException(val host: String, val port: Int, val expected: String, val actual: String) :
    CertificateException("Server $host:$port does not match the invite code")
