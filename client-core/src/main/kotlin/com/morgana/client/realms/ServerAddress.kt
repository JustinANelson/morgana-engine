package com.morgana.client.realms

/**
 * Reads what a player types or pastes into the "server" box of Direct Join: `host`, `host:port`, `[ipv6]:port`, a bare IPv6
 * address, or a web-style address such as `https://play.example.com:7002/`. Pure, so the odd cases are tested.
 */
object ServerAddress {
    data class Parsed(val host: String, /** Null when the text gave no port. */ val port: Int?)

    private val schemes = listOf("https://", "http://", "wss://", "ws://", "tcp://")

    /** The host and optional port in [text], or null when there is no usable host. A port that is present but invalid also gives null. */
    fun parse(text: String): Parsed? {
        var t = text.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        schemes.firstOrNull { t.startsWith(it, ignoreCase = true) }?.let { t = t.substring(it.length) }
        t = t.substringBefore('/').substringBefore('?').substringBefore('#')
        if (t.isEmpty()) return null

        if (t.startsWith("[")) { // [::1] or [::1]:7001
            val close = t.indexOf(']')
            if (close < 2) return null
            val host = t.substring(1, close)
            val rest = t.substring(close + 1)
            if (rest.isEmpty()) return Parsed(host, null)
            if (!rest.startsWith(":")) return null
            val port = parsePort(rest.substring(1)) ?: return null
            return Parsed(host, port)
        }
        val colons = t.count { it == ':' }
        if (colons == 0) return Parsed(t, null).takeIf { validHost(t) }
        if (colons > 1) return Parsed(t, null).takeIf { t.all { c -> c.isLetterOrDigit() || c == ':' || c == '.' } } // bare IPv6
        val host = t.substringBefore(':')
        val port = parsePort(t.substringAfter(':')) ?: return null
        return Parsed(host, port).takeIf { validHost(host) }
    }

    private fun validHost(host: String): Boolean =
        host.isNotEmpty() && host.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' } && !host.startsWith(".") && !host.endsWith(".")

    private fun parsePort(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** A plain-language reason the port field is unusable, or null when it is fine. */
    fun portError(text: String): String? =
        if (parsePort(text) == null) "The port must be a number from 1 to 65535." else null
}
