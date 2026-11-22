package com.morgana.server

import com.morgana.persistence.TlsMode
import com.morgana.protocol.ClientPacket
import com.morgana.server.tls.ServerTls
import com.morgana.protocol.PacketCodec
import com.morgana.protocol.ServerPacket
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
import io.netty.handler.codec.MessageToMessageCodec
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
import io.netty.handler.ssl.OptionalSslHandler
import io.netty.handler.ssl.SslHandler
import io.netty.handler.timeout.IdleStateEvent
import io.netty.handler.timeout.IdleStateHandler
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Authoritative Netty TCP Server running the 20 TPS (50ms) deterministic simulation loop.
 */
class MorganaNettyServer(
    val port: Int = DEFAULT_PORT,
    val coordinator: MorganaWorldCoordinator,
    /** TLS configuration; null serves plain TCP only. */
    val tls: ServerTls? = null,
    /** Per-address connection limits and bans; null admits every connection. */
    val gate: ConnectionGate? = null,
    /**
     * Extra port serving the same protocol over WebSocket text frames (one JSON packet per frame) for browser clients,
     * which cannot open raw TCP sockets. Null disables it; 0 binds an ephemeral port. The same [tls] policy applies.
     */
    val webSocketPort: Int? = null,
    /** Seconds of total silence after which a connection is closed (from `session.idleSeconds` in `server.json`, default 75). */
    val idleTimeoutSeconds: Int = IDLE_TIMEOUT_SECONDS,
    /** A built browser client to serve on the WebSocket port (`http://<server>:<port>/`); null serves none. */
    val webClientDir: java.nio.file.Path? = null,
    /** The game wiki's files (name to bytes) served at `/wiki/` on the WebSocket port; null serves none. */
    val wikiSource: (() -> Map<String, ByteArray>)? = null
) : AutoCloseable {
    private val webClientHandler = com.morgana.server.web.WebClientHttpHandler(webClientDir, wikiSource)
    private val logger = LoggerFactory.getLogger(MorganaNettyServer::class.java)
    private var bossGroup: NioEventLoopGroup? = null
    private var workerGroup: NioEventLoopGroup? = null
    private var serverChannel: Channel? = null
    private var webSocketChannel: Channel? = null
    private var tickExecutor: ScheduledExecutorService? = null

    var boundPort: Int = port
        private set

    /** The WebSocket port actually bound, or null when WebSocket support is off. */
    var boundWebSocketPort: Int? = null
        private set

    fun start() {
        val boss = NioEventLoopGroup(1)
        val worker = NioEventLoopGroup()
        bossGroup = boss
        workerGroup = worker

        val bootstrap = ServerBootstrap()
        bootstrap.group(boss, worker)
            .channel(NioServerSocketChannel::class.java)
            .childOption(ChannelOption.TCP_NODELAY, true)
            .childOption(ChannelOption.SO_KEEPALIVE, true)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) = initConnection(ch, webSocket = false)
            })

        val channelFuture = bootstrap.bind(bindAddress(port)).sync()
        val channel = channelFuture.channel()
        serverChannel = channel
        boundPort = (channel.localAddress() as? java.net.InetSocketAddress)?.port ?: port

        webSocketPort?.let { wsPort ->
            val wsBootstrap = ServerBootstrap()
            wsBootstrap.group(boss, worker)
                .channel(NioServerSocketChannel::class.java)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) = initConnection(ch, webSocket = true)
                })
            val wsChannel = wsBootstrap.bind(bindAddress(wsPort)).sync().channel()
            webSocketChannel = wsChannel
            boundWebSocketPort = (wsChannel.localAddress() as? java.net.InetSocketAddress)?.port ?: wsPort
        }

        val exec = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "morgana-20tps-sim-loop").apply { isDaemon = true }
        }
        tickExecutor = exec
        exec.scheduleAtFixedRate(
            {
                try {
                    coordinator.stepServerTick(50)
                } catch (t: Throwable) {
                    logger.error("Error in 20 TPS simulation tick", t)
                }
            },
            50L,
            50L,
            TimeUnit.MILLISECONDS
        )

        logger.info("Morgana ORPG Authoritative Server listening on port {} (WebSocket: {}, 20 TPS, TLS: {})", boundPort, boundWebSocketPort ?: "off", tls?.mode?.configKey ?: "off")
        val wsPort = boundWebSocketPort
        if (webClientDir != null && wsPort != null) {
            val scheme = if (tls != null && tls.mode != TlsMode.OFF) "https" else "http"
            logger.info("Browser client served from {}. Open it in a browser (phones too):", webClientDir.toAbsolutePath().normalize())
            for ((address, note) in browserAddresses()) logger.info("  {}://{}:{}/{}", scheme, address, wsPort, note)
        }
        if (tls != null) logger.info("Server certificate fingerprint (SHA-256): {}", com.morgana.protocol.tls.Fingerprint.display(tls.fingerprint))
    }

    /** Builds the pipeline for one accepted connection: admission gate, TLS, then raw framing or WebSocket, then the session. */
    private fun initConnection(ch: SocketChannel, webSocket: Boolean) {
        // Admission control first, before TLS or any bytes are read: refused peers cost almost nothing.
        val peer = ch.remoteAddress()?.address
        var admitted = false
        if (webSocket && gate != null && peer != null) {
            // The browser port also serves the page and its files, dozens of short HTTP requests per page load, which
            // must not count as connection attempts (they once got a phone banned for "connection flood"). Here only bans
            // are refused; the full gate runs when the connection asks to become a game session (WebSocketUpgradeGate).
            if (gate.isBanned(peer)) {
                ch.close()
                return
            }
        } else if (gate != null && peer != null) {
            val verdict = gate.admit(peer)
            if (verdict != GateVerdict.ALLOW) {
                logger.debug("Refused connection from {}: {}", peer.hostAddress, verdict)
                ch.close()
                return
            }
            admitted = true
        }
        val pipeline = ch.pipeline()
        // TLS must be the first handler so everything after it (framing, JSON) sees decrypted bytes.
        tls?.let { t ->
            val fromThisMachine = peer?.isLoopbackAddress == true
            when (t.mode) {
                TlsMode.REQUIRED -> pipeline.addLast("tls", t.sslContext.newHandler(ch.alloc()))
                TlsMode.OPTIONAL -> pipeline.addLast("tls", OptionalSslHandler(t.sslContext))
                TlsMode.REMOTE -> pipeline.addLast(
                    "tls",
                    if (fromThisMachine) OptionalSslHandler(t.sslContext) else t.sslContext.newHandler(ch.alloc())
                )
                TlsMode.OFF -> {}
            }
        }
        val sessionHandler = NettySessionHandler(coordinator, if (admitted) gate else null, peer, gate?.preLoginSeconds ?: 0, startOnHandshake = webSocket)
        if (webSocket) {
            pipeline.addLast("http", HttpServerCodec())
            pipeline.addLast("httpAggregator", HttpObjectAggregator(WEBSOCKET_MAX_BYTES))
            pipeline.addLast("upgradeGate", WebSocketUpgradeGate(gate, peer, sessionHandler))
            // Plain GET /assets/... requests (the game's own art) are answered here; everything else continues to the WebSocket upgrade.
            pipeline.addLast("assets", com.morgana.server.assets.AssetHttpHandler { coordinator.gameAssets })
            // The browser client's own page and scripts when this server hosts it, and a 404 for any other plain request
            // (the WebSocket upgrade passes through).
            pipeline.addLast("webClient", webClientHandler)
            pipeline.addLast("websocket", WebSocketServerProtocolHandler("/", null, true, WEBSOCKET_MAX_BYTES))
            pipeline.addLast("textFrames", TextFrameCodec())
        } else {
            pipeline.addLast("frameDecoder", LengthFieldBasedFrameDecoder(2 * 1024 * 1024, 0, 4, 0, 4))
            pipeline.addLast("stringDecoder", StringDecoder(StandardCharsets.UTF_8))
            pipeline.addLast("frameEncoder", LengthFieldPrepender(4))
            pipeline.addLast("stringEncoder", StringEncoder(StandardCharsets.UTF_8))
        }
        // Clients ping every few seconds, so a connection silent for this long is dead (phone asleep, Wi-Fi gone): drop it so
        // the character is saved and removed, and the player's resume token starts its grace period.
        pipeline.addLast("idle", IdleStateHandler(idleTimeoutSeconds, 0, 0))
        pipeline.addLast("handler", sessionHandler)
    }

    /**
     * On the browser port, runs the connection gate (rate limit, per-address cap, strikes) when a request asks to become
     * a WebSocket game session; plain page and file requests pass without counting. A refused upgrade closes the
     * connection; an admitted one is released by the session handler when it closes.
     */
    private class WebSocketUpgradeGate(
        private val gate: ConnectionGate?,
        private val peer: java.net.InetAddress?,
        private val session: NettySessionHandler
    ) : io.netty.channel.ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (gate != null && peer != null && msg is io.netty.handler.codec.http.FullHttpRequest &&
                msg.headers().get(io.netty.handler.codec.http.HttpHeaderNames.UPGRADE)?.equals("websocket", ignoreCase = true) == true
            ) {
                val verdict = gate.admit(peer)
                if (verdict != GateVerdict.ALLOW) {
                    LoggerFactory.getLogger(MorganaNettyServer::class.java).debug("Refused WebSocket session from {}: {}", peer.hostAddress, verdict)
                    io.netty.util.ReferenceCountUtil.release(msg)
                    ctx.close()
                    return
                }
                session.admittedBy(gate)
            }
            ctx.fireChannelRead(msg)
        }
    }

    /** Maps WebSocket text frames to/from the plain JSON strings the session handler works with. */
    private class TextFrameCodec : MessageToMessageCodec<TextWebSocketFrame, String>() {
        override fun encode(ctx: ChannelHandlerContext, msg: String, out: MutableList<Any>) {
            out.add(TextWebSocketFrame(msg))
        }

        override fun decode(ctx: ChannelHandlerContext, msg: TextWebSocketFrame, out: MutableList<Any>) {
            out.add(msg.text())
        }
    }

    override fun close() {
        tickExecutor?.shutdownNow()
        webSocketChannel?.close()?.syncUninterruptibly()
        serverChannel?.close()?.syncUninterruptibly()
        // No quiet period (Netty's default is 2s per group, which made every start/stop cycle slow); both groups stop in parallel.
        val workerDone = workerGroup?.shutdownGracefully(0, 5, TimeUnit.SECONDS)
        val bossDone = bossGroup?.shutdownGracefully(0, 5, TimeUnit.SECONDS)
        workerDone?.syncUninterruptibly()
        bossDone?.syncUninterruptibly()
    }

    private class NettySessionHandler(
        private val coordinator: MorganaWorldCoordinator,
        /** Non-null when this connection was admitted by the gate and must be released when it closes. */
        private var gate: ConnectionGate?,
        private val peer: java.net.InetAddress?,
        private val preLoginSeconds: Int,
        /**
         * Start the game session when the WebSocket handshake completes rather than when the socket opens: on the browser
         * port most connections are page and file downloads that never become sessions.
         */
        private val startOnHandshake: Boolean = false
    ) : SimpleChannelInboundHandler<String>() {
        private var session: NettyClientSession? = null

        /** Records that the gate admitted this connection late (at the WebSocket upgrade), so closing it releases the slot. */
        fun admittedBy(gate: ConnectionGate) { this.gate = gate }

        override fun channelActive(ctx: ChannelHandlerContext) {
            if (!startOnHandshake) startSession(ctx)
            super.channelActive(ctx)
        }

        private fun startSession(ctx: ChannelHandlerContext) {
            if (session != null) return
            val nettySession = NettyClientSession(ctx.channel())
            session = nettySession
            coordinator.onSessionConnected(nettySession)
            if (preLoginSeconds > 0) {
                // Silent connections hold a slot; anyone who has not logged in by now is dropped.
                ctx.executor().schedule({
                    if (nettySession.characterId == null && ctx.channel().isActive) {
                        peer?.let { gate?.strike(it, "connection idle before login") }
                        ctx.close()
                    }
                }, preLoginSeconds.toLong(), java.util.concurrent.TimeUnit.SECONDS)
            }
        }

        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (startOnHandshake && evt is WebSocketServerProtocolHandler.HandshakeComplete) {
                startSession(ctx)
            } else if (evt is IdleStateEvent) {
                ctx.close()
            } else {
                super.userEventTriggered(ctx, evt)
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            val admittedBy = gate
            if (admittedBy != null && peer != null) admittedBy.release(peer)
            session?.let { coordinator.onSessionDisconnected(it) }
            session = null
        }

        private val rawGuard = RawFloodGuard()

        override fun channelRead0(ctx: ChannelHandlerContext, msg: String) {
            val activeSession = session ?: return
            // Hard ceilings checked before the (costly) JSON decode; the coordinator applies the fine-grained budgets.
            if (activeSession.characterId == null && msg.length > RawFloodGuard.MAX_PRE_AUTH_CHARS) {
                closeFlooder(ctx, activeSession, "oversized message before login (${msg.length} chars)")
                return
            }
            if (!rawGuard.allow(System.nanoTime())) {
                closeFlooder(ctx, activeSession, "more than ${RawFloodGuard.MAX_PER_SECOND} messages in one second")
                return
            }
            val packet: ClientPacket = PacketCodec.decodeClientJson(msg)
            coordinator.handleClientPacketOffLock(activeSession, packet)
        }

        private fun closeFlooder(ctx: ChannelHandlerContext, session: NettyClientSession, reason: String) {
            org.slf4j.LoggerFactory.getLogger("Security").warn("Closing {}: {}", ctx.channel().remoteAddress(), reason)
            peer?.let { gate?.strike(it, "message flood") }
            session.close("flood")
        }

        @Deprecated("Deprecated in Netty ChannelHandler")
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            org.slf4j.LoggerFactory.getLogger(MorganaNettyServer::class.java)
                .debug("Closing connection {}: {}", ctx.channel().remoteAddress(), cause.toString())
            ctx.close()
        }
    }

    private class NettyClientSession(
        private val channel: Channel
    ) : ClientSession {
        override val sessionId: String = channel.id().asShortText()
        override var characterId: String? = null
        override val remoteInetAddress: java.net.InetAddress? = (channel.remoteAddress() as? java.net.InetSocketAddress)?.address

        override val isLoopback: Boolean =
            (channel.remoteAddress() as? java.net.InetSocketAddress)?.address?.isLoopbackAddress == true

        /** Evaluated on demand: an optional-TLS connection only gains its SslHandler once the first bytes arrive. */
        override val isEncrypted: Boolean get() = channel.pipeline().get(SslHandler::class.java) != null

        override fun send(packet: ServerPacket) = write(PacketCodec.encodeServerJson(packet))

        override fun sendEncoded(packet: ServerPacket, encoded: () -> String) = write(encoded())

        private val flushQueued = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * Queues [text] and flushes once for the whole burst: a tick sends dozens of packets to a busy player, and a flush
         * per packet was a system call per packet. The flush runs after the writes already queued on the connection's loop.
         */
        private fun write(text: String) {
            if (!channel.isActive) return
            channel.write(text)
            if (flushQueued.compareAndSet(false, true)) {
                try {
                    channel.eventLoop().execute {
                        flushQueued.set(false)
                        channel.flush()
                    }
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    flushQueued.set(false) // the loop is shutting down: nothing more can be sent anyway
                }
            }
        }

        override fun close(reason: String) {
            channel.close()
        }
    }

    companion object {
        const val DEFAULT_PORT: Int = 7001

        /** Environment variable naming the WebSocket port for browser clients (unset: WebSocket off). */
        const val WEBSOCKET_PORT_ENV = "MORGANA_WS_PORT"

        /** Environment variable naming the local address to listen on (e.g. a Tailscale IP); unset listens on all interfaces. */
        const val BIND_HOST_ENV = "MORGANA_BIND_HOST"

        /**
         * Addresses a browser on another device can use to reach this machine: every non-loopback IPv4 address, with
         * Tailscale's (100.64.0.0/10) marked, or only the bind address when `MORGANA_BIND_HOST` is set.
         */
        fun browserAddresses(): List<Pair<String, String>> {
            System.getenv(BIND_HOST_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let { return listOf(it to "") }
            val found = try {
                java.net.NetworkInterface.getNetworkInterfaces().toList()
                    .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                    .flatMap { it.inetAddresses.toList() }
                    .filterIsInstance<java.net.Inet4Address>()
                    .map { it.hostAddress }
                    .distinct()
                    .sorted()
            } catch (_: Exception) { emptyList() }
            fun tailscale(ip: String) = ip.split('.').let { p -> p.size == 4 && p[0] == "100" && (p[1].toIntOrNull() ?: 0) in 64..127 }
            return listOf("localhost" to "  (this computer)") +
                found.sortedBy { if (tailscale(it)) 0 else 1 }.map { it to if (tailscale(it)) "  (Tailscale: use this, or this machine's MagicDNS name, from your phone)" else "" }
        }

        private fun bindAddress(port: Int): java.net.InetSocketAddress {
            val host = System.getenv(BIND_HOST_ENV)?.trim().orEmpty()
            return if (host.isEmpty()) java.net.InetSocketAddress(port) else java.net.InetSocketAddress(host, port)
        }

        private const val WEBSOCKET_MAX_BYTES = 2 * 1024 * 1024

        /** Seconds of total silence after which a connection is closed (clients ping every ~10 s). */
        private const val IDLE_TIMEOUT_SECONDS = 75
    }
}


/**
 * Last-resort ceiling on raw messages per connection, applied before JSON decoding. Far above anything a real client
 * sends (the busiest, free pixel movement, is about 22 per second); exceeding it closes the connection.
 */
internal class RawFloodGuard {
    private var windowStartNanos = 0L
    private var count = 0

    /** False once more than [MAX_PER_SECOND] messages arrive within one second. */
    fun allow(nowNanos: Long): Boolean {
        if (nowNanos - windowStartNanos >= 1_000_000_000L) {
            windowStartNanos = nowNanos
            count = 0
        }
        return ++count <= MAX_PER_SECOND
    }

    companion object {
        const val MAX_PER_SECOND = 400

        /** Largest message accepted from a connection that has not logged in (a login packet is well under 1 KB). */
        const val MAX_PRE_AUTH_CHARS = 8192
    }
}
