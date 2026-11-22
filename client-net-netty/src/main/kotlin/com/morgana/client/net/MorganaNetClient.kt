package com.morgana.client.net

import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PacketCodec
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.tls.IdentityTrustManager
import com.morgana.protocol.tls.InviteMismatchException
import com.morgana.protocol.tls.ServerIdentityChangedException
import com.morgana.protocol.tls.UnknownServerException
import io.netty.bootstrap.Bootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.SslProvider
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Non-blocking Netty TCP client used by the LibGDX client to communicate with the authoritative server.
 */
class MorganaNetClient : ClientTransport {
    private var group: NioEventLoopGroup? = null
    @Volatile
    private var channel: Channel? = null

    val incomingPackets: ConcurrentLinkedQueue<ServerPacket> = ConcurrentLinkedQueue()

    override val isConnected: Boolean
        get() = channel?.isActive == true

    /** True when the current connection runs over TLS. */
    override val isEncrypted: Boolean
        get() = channel?.pipeline()?.get(SslHandler::class.java) != null

    /** Plain-text connection (loopback play, LAN, legacy servers). */
    fun connect(host: String = "127.0.0.1", port: Int = 7001): Boolean =
        connectSecure(host, port, ClientSecurity.PLAIN) is ConnectResult.Connected

    override fun connectAsync(host: String, port: Int, security: ClientSecurity, onResult: (ConnectResult) -> Unit) {
        Thread({ onResult(connectSecure(host, port, security)) }, "morgana-connect").apply { isDaemon = true }.start()
    }

    /** Blocking connect (tests and tools); the game uses [connectAsync]. */
    fun connectSecure(host: String, port: Int, security: ClientSecurity): ConnectResult {
        close()
        val worker = NioEventLoopGroup(1)
        group = worker
        val trust = if (security.tls) {
            IdentityTrustManager(host, port, security.pinnedFingerprint, security.knownHosts)
        } else {
            null
        }
        return try {
            val bootstrap = Bootstrap()
                .group(worker)
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 4000)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        if (trust != null) {
                            val context = SslContextBuilder.forClient()
                                .sslProvider(SslProvider.JDK)
                                .protocols("TLSv1.3", "TLSv1.2")
                                .trustManager(trust)
                                .build()
                            val ssl = context.newHandler(ch.alloc(), host, port)
                            // Ask the JDK to also check the host name when a certificate authority vouches for the server.
                            val params = ssl.engine().sslParameters
                            params.endpointIdentificationAlgorithm = "HTTPS"
                            ssl.engine().sslParameters = params
                            ssl.setHandshakeTimeoutMillis(HANDSHAKE_TIMEOUT_MS)
                            ch.pipeline().addLast("tls", ssl)
                        }
                        ch.pipeline().addLast(
                            LengthFieldBasedFrameDecoder(2 * 1024 * 1024, 0, 4, 0, 4),
                            StringDecoder(StandardCharsets.UTF_8),
                            LengthFieldPrepender(4),
                            StringEncoder(StandardCharsets.UTF_8),
                            object : SimpleChannelInboundHandler<String>() {
                                override fun channelRead0(ctx: ChannelHandlerContext, msg: String) {
                                    incomingPackets.offer(PacketCodec.decodeServerJson(msg))
                                }
                            }
                        )
                    }
                })
            val connected = bootstrap.connect(host, port).sync().channel()
            if (trust != null) {
                val ssl = connected.pipeline().get(SslHandler::class.java)
                val handshake = ssl.handshakeFuture()
                if (!handshake.await(HANDSHAKE_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS)) {
                    connected.close()
                    close()
                    return ConnectResult.Failed(
                        "No secure answer from $host:$port. The server may not have encryption (TLS) turned on."
                    )
                }
                if (!handshake.isSuccess) {
                    val failure = classify(handshake.cause(), host, port, trust)
                    connected.close()
                    close()
                    return failure
                }
            }
            channel = connected
            ConnectResult.Connected(encrypted = trust != null, fingerprint = trust?.observedFingerprint)
        } catch (e: Exception) {
            close()
            ConnectResult.Failed("Could not connect to $host:$port (${e.message ?: e::class.simpleName})")
        }
    }

    private fun classify(cause: Throwable?, host: String, port: Int, trust: IdentityTrustManager): ConnectResult {
        var t: Throwable? = cause
        while (t != null) {
            when (t) {
                is UnknownServerException -> return ConnectResult.NeedsTrust(t.host, t.port, t.fingerprint)
                is ServerIdentityChangedException -> return ConnectResult.IdentityChanged(t.host, t.port, t.expected, t.actual)
                is InviteMismatchException -> return ConnectResult.InviteMismatch(t.host, t.port, t.expected, t.actual)
            }
            t = t.cause?.takeIf { it !== t }
        }
        val detail = cause?.message?.lineSequence()?.firstOrNull().orEmpty()
        return ConnectResult.Failed(
            "Secure connection to $host:$port failed" + (if (detail.isNotBlank()) " ($detail)" else "") +
                ". The server may not support encryption."
        )
    }

    override fun send(packet: ClientPacket) {
        val ch = channel ?: return
        if (ch.isActive) {
            ch.writeAndFlush(PacketCodec.encodeClientJson(packet))
        }
    }

    override fun drainPackets(maxPerFrame: Int, consumer: (ServerPacket) -> Unit) {
        var processed = 0
        while (processed < maxPerFrame) {
            val pkt = incomingPackets.poll() ?: break
            consumer(pkt)
            processed++
        }
    }

    override fun close() {
        channel?.close()?.syncUninterruptibly()
        channel = null
        group?.shutdownGracefully(0, 5, TimeUnit.SECONDS)?.syncUninterruptibly()
        group = null
    }

    private companion object {
        const val HANDSHAKE_TIMEOUT_MS = 6000L
    }
}
