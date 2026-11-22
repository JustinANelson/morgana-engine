package com.morgana.client.web

import com.morgana.client.net.ClientSecurity
import com.morgana.client.net.ClientTransport
import com.morgana.client.net.ConnectResult
import com.morgana.client.net.TransportPolicy
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PacketCodec
import com.morgana.protocol.ServerPacket
import org.teavm.jso.JSBody
import org.teavm.jso.websocket.WebSocket

/** The browser's count of bytes passed to `send` that have not left the device yet. */
@JSBody(params = ["ws"], script = "return ws.bufferedAmount || 0;")
private external fun bufferedAmountOf(ws: WebSocket): Double

/**
 * [ClientTransport] over the browser's WebSocket: one JSON packet per text frame, matching the server's WebSocket
 * listener. The browser does all encryption (`wss://`) and certificate checking, so fingerprint pinning and
 * trust-on-first-use do not apply here. Everything runs on the single browser thread, so no locking is needed.
 *
 * Phones close and reopen sockets constantly, so every event handler first checks that its socket is still the current
 * one: a late `close` from a socket that was already replaced must not mark the new connection dead, and its messages
 * must not reach the new session.
 */
class BrowserWebSocketTransport : ClientTransport {
    private var socket: WebSocket? = null
    private var open = false
    private var secure = false
    private val incoming = ArrayDeque<ServerPacket>()
    private var undecodable = 0

    /** Reports the outcome of the connect in flight (once), so closing the socket early still answers its caller. */
    private var reportCurrent: ((ConnectResult) -> Unit)? = null

    override val isConnected: Boolean get() = open
    override val isEncrypted: Boolean get() = open && secure
    override val isWebSocket: Boolean get() = true

    override val bufferedBytes: Long get() = socket?.let { if (open) bufferedAmountOf(it).toLong() else 0L } ?: 0L
    override val pendingPackets: Int get() = incoming.size

    override fun takeUndecodableCount(): Int = undecodable.also { undecodable = 0 }

    override fun connectAsync(host: String, port: Int, security: ClientSecurity, onResult: (ConnectResult) -> Unit) {
        // A connect still in flight is superseded, not failed: its caller must not hear about the new attempt's socket.
        reportCurrent = null
        discardSocket()
        incoming.clear()
        undecodable = 0
        secure = security.tls
        var reported = false
        fun report(result: ConnectResult) {
            if (!reported) {
                reported = true
                onResult(result)
            }
        }
        TransportPolicy.plaintextRefusal(host, secure)?.let {
            report(ConnectResult.Failed(it))
            return
        }
        val url = (if (secure) "wss://" else "ws://") + host + ":" + port + "/"
        val ws = try {
            WebSocket(url)
        } catch (e: Throwable) {
            report(ConnectResult.Failed("Could not open $url (${e.message})"))
            return
        }
        socket = ws
        reportCurrent = ::report
        ws.onOpen { _ ->
            if (socket === ws) {
                open = true
                report(ConnectResult.Connected(encrypted = secure, fingerprint = null))
            }
        }
        ws.onMessage { event ->
            if (socket === ws) receive(event.dataAsString)
        }
        ws.onError { _ ->
            if (socket === ws) report(ConnectResult.Failed("Could not connect to $host:$port. Is the server's WebSocket port enabled?"))
        }
        ws.onClose { _ ->
            if (socket === ws) {
                open = false
                report(ConnectResult.Failed("Connection to $host:$port closed."))
            }
        }
    }

    /** Decodes one frame. A frame that cannot be understood (a newer server, or damage) is counted and skipped, never thrown into the browser's event loop. */
    private fun receive(text: String?) {
        val packet = try {
            if (text == null) null else PacketCodec.decodeServerJson(text)
        } catch (_: Throwable) {
            null
        }
        if (packet == null) {
            undecodable++
            return
        }
        incoming.addLast(packet)
        if (incoming.size > MAX_PENDING) {
            // A tab left in the background for a long time: replaying this much state would take forever and may be stale
            // anyway. Dropping the connection makes the client reconnect, and logging back in sends the world afresh.
            incoming.clear()
            close()
        }
    }

    override fun send(packet: ClientPacket) {
        val ws = socket ?: return
        if (!open) return
        try {
            ws.send(PacketCodec.encodeClientJson(packet))
        } catch (_: Throwable) {
            // The socket is closing under us (CLOSING/CLOSED state); the close event or the reconnect logic takes it from here.
            open = false
        }
    }

    override fun drainPackets(maxPerFrame: Int, consumer: (ServerPacket) -> Unit) {
        var processed = 0
        while (processed < maxPerFrame) {
            val packet = incoming.removeFirstOrNull() ?: break
            consumer(packet)
            processed++
        }
    }

    override fun close() {
        val report = reportCurrent
        reportCurrent = null
        discardSocket()
        report?.invoke(ConnectResult.Failed("Connection closed."))
    }

    /** Forgets the current socket (so its late events are ignored) and closes it. */
    private fun discardSocket() {
        open = false
        val ws = socket
        socket = null
        try { ws?.close() } catch (_: Throwable) {}
    }

    private companion object {
        /** More queued packets than this (a long stay in the background) is not replayed; see [receive]. */
        const val MAX_PENDING = 20_000
    }
}
