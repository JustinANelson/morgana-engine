package com.morgana.client.net

import com.morgana.protocol.tls.InviteCode
import com.morgana.protocol.tls.KnownHostStore

/** What the login window will do for what the player typed. [error] is set (and the rest unusable) if it cannot connect. */
data class ConnectionPlan(
    val host: String,
    val port: Int,
    val security: ClientSecurity,
    val error: String? = null
)

/**
 * Turns the login window's fields into a connection, without any UI. The host field accepts a plain address, `host:port`,
 * or a full invite code (`morgana://host:port#fp=...`) pasted from the world's host.
 */
object ConnectionPlanner {

    fun plan(
        hostText: String,
        portText: String,
        tlsRequested: Boolean,
        knownHosts: KnownHostStore,
        defaultPort: Int = InviteCode.DEFAULT_PORT
    ): ConnectionPlan {
        val text = hostText.trim().ifBlank { "127.0.0.1" }
        val invite = InviteCode.parse(text)
            ?: return ConnectionPlan(text, defaultPort, ClientSecurity.PLAIN, "That is not a valid address or invite code.")
        val port = if (InviteCode.hasExplicitPort(text)) invite.port else portText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: defaultPort
        // An invite code always means encrypted + verified; otherwise the player's checkbox decides.
        val tls = invite.fingerprint != null || tlsRequested
        return ConnectionPlan(invite.host, port, ClientSecurity(tls, invite.fingerprint, knownHosts))
    }

    /** True for addresses that never leave this machine; TLS is off by default for them. */
    fun isLoopbackHost(hostText: String): Boolean {
        val host = (InviteCode.parse(hostText)?.host ?: hostText).trim().lowercase()
        return host == "localhost" || host == "::1" || host == "127.0.0.1" || host.startsWith("127.")
    }
}
