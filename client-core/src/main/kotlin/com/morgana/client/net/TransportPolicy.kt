package com.morgana.client.net

/**
 * Decides whether a plaintext connection is acceptable. Passwords cross the wire in cleartext over `ws://`, so the
 * browser client refuses it for any host that is not loopback, a private-network address, or a Tailscale address
 * (Tailscale already encrypts everything between its devices with WireGuard).
 */
object TransportPolicy {

    /**
     * True when [host] is this machine, a private-network name or address, or a Tailscale device, where cleartext over the
     * game's own connection is tolerable. Tailscale: addresses in 100.64.0.0/10 and fd7a:115c:a1e0::/48, MagicDNS names
     * under `.ts.net`, and short single-word names (which only a local or VPN resolver can answer).
     */
    fun isLocalHost(host: String): Boolean {
        val h = host.trim().lowercase().removePrefix("[").removeSuffix("]").removeSuffix(".")
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h == "::1") return true
        if (h.endsWith(".ts.net")) return true
        if (h.startsWith("fd7a:115c:a1e0:")) return true
        val parts = h.split('.')
        // A bare machine name such as "gaming-pc" (Tailscale MagicDNS or a home router) can never be a public host.
        if (parts.size == 1) return ':' !in h && h.all { it.isLetterOrDigit() || it == '-' } && !h.all { it.isDigit() }
        if (parts.size != 4) return false
        val n = parts.map { it.toIntOrNull() ?: return false }
        if (n.any { it !in 0..255 }) return false
        return n[0] == 127 || n[0] == 10 ||
            (n[0] == 192 && n[1] == 168) ||
            (n[0] == 172 && n[1] in 16..31) ||
            (n[0] == 100 && n[1] in 64..127) // Tailscale (carrier-grade NAT range)
    }

    /** Null when the connection may proceed; otherwise the message to show instead of connecting. */
    fun plaintextRefusal(host: String, tls: Boolean): String? =
        if (tls || isLocalHost(host)) null
        else "Refusing an unencrypted connection to $host: passwords would be sent in cleartext. Tick the secure (TLS) option to use wss://."
}
