package com.morgana.persistence

import java.net.InetAddress

/**
 * An IP address or CIDR block (`203.0.113.7`, `10.0.0.0/8`, `2001:db8::/32`) used for allow-lists.
 * Parsing accepts only numeric literals, so it never performs a DNS lookup.
 */
class AddressRange private constructor(private val network: ByteArray, private val prefixBits: Int) {

    fun contains(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != network.size) return false // IPv4 and IPv6 are separate spaces
        var remaining = prefixBits
        var i = 0
        while (remaining > 0) {
            val mask = if (remaining >= 8) 0xFF else (0xFF shl (8 - remaining)) and 0xFF
            if ((bytes[i].toInt() and mask) != (network[i].toInt() and mask)) return false
            remaining -= 8
            i++
        }
        return true
    }

    companion object {
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
        private val IPV6 = Regex("""[0-9a-fA-F:.]+""")

        /** Parses an address or CIDR block; null if it is neither. */
        fun parse(text: String): AddressRange? {
            val trimmed = text.trim()
            val addrPart = trimmed.substringBefore('/')
            val address = literal(addrPart) ?: return null
            val max = address.address.size * 8
            val prefix = if ('/' in trimmed) trimmed.substringAfter('/').toIntOrNull() ?: return null else max
            if (prefix !in 0..max) return null
            return AddressRange(address.address, prefix)
        }

        /** The numeric address in [text], or null (also for host names, which are never resolved). */
        fun literal(text: String): InetAddress? {
            val t = text.trim().removePrefix("[").removeSuffix("]")
            val numeric = when {
                IPV4.matches(t) -> t.split('.').all { it.toInt() in 0..255 }
                ':' in t -> IPV6.matches(t)
                else -> false
            }
            if (!numeric) return null
            return try { InetAddress.getByName(t) } catch (e: Exception) { null }
        }
    }
}
