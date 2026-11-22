package com.morgana.client.net

import com.morgana.protocol.ClientPacket
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.tls.InMemoryKnownHosts
import com.morgana.protocol.tls.KnownHostStore

/**
 * How [ClientTransport] should secure a connection.
 *
 * @property tls encrypt the connection; never silently downgraded to plain text if it fails
 * @property pinnedFingerprint SHA-256 fingerprint from an invite code; the server must present exactly this certificate
 * @property knownHosts fingerprints of self-signed servers the player already trusted
 */
data class ClientSecurity(
    val tls: Boolean = false,
    val pinnedFingerprint: String? = null,
    val knownHosts: KnownHostStore = InMemoryKnownHosts()
) {
    companion object {
        val PLAIN = ClientSecurity(tls = false)
    }
}

/** Outcome of [ClientTransport.connectAsync]. */
sealed interface ConnectResult {
    data class Connected(val encrypted: Boolean, val fingerprint: String?) : ConnectResult
    data class Failed(val message: String) : ConnectResult

    /** First contact with a self-signed server: show [fingerprint] to the player and, if accepted, remember it and retry. */
    data class NeedsTrust(val host: String, val port: Int, val fingerprint: String) : ConnectResult

    /** A previously trusted server now presents a different certificate. */
    data class IdentityChanged(val host: String, val port: Int, val expected: String, val actual: String) : ConnectResult

    /** The server does not match the invite code the player used. */
    data class InviteMismatch(val host: String, val port: Int, val expected: String, val actual: String) : ConnectResult
}

/**
 * How the client talks to the authoritative server. `:client-core` only knows this interface so it carries no
 * networking library: desktop and Android plug in Netty over TCP (`:client-net-netty`), the browser client plugs in a
 * WebSocket. Nothing here may block, because a browser has a single thread.
 */
interface ClientTransport : AutoCloseable {
    val isConnected: Boolean

    /** True when the current connection is encrypted. */
    val isEncrypted: Boolean

    /** True when this transport speaks WebSocket/HTTP on the server's web port rather than raw TCP. */
    val isWebSocket: Boolean get() = false

    /**
     * Starts connecting and returns immediately. [onResult] is called exactly once, possibly from another thread, so
     * callers must hand the result over to their own thread rather than touching UI from it.
     */
    fun connectAsync(host: String, port: Int, security: ClientSecurity, onResult: (ConnectResult) -> Unit)

    fun send(packet: ClientPacket)

    /** Bytes handed to [send] that the network has not taken yet. A slow link makes this grow; 0 when the transport cannot tell. */
    val bufferedBytes: Long get() = 0L

    /** Packets received but not yet handed out by [drainPackets] (a backgrounded browser tab keeps receiving while it draws nothing). */
    val pendingPackets: Int get() = 0

    /** Packets that arrived but could not be understood since the last call (a newer server, or damage); they are skipped. */
    fun takeUndecodableCount(): Int = 0

    /** Hands at most [maxPerFrame] received packets to [consumer], oldest first. Call once per frame. */
    fun drainPackets(maxPerFrame: Int = 128, consumer: (ServerPacket) -> Unit)
}
