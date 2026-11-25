package com.morgana.server.admin

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.Channel
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.util.concurrent.DefaultEventExecutorGroup
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

/**
 * The operator's web panel and REST API on its own port (see [com.morgana.persistence.AdminConfig]). It only hands requests
 * to [AdminApi]. Requests run on a small separate thread pool, so a call that waits for the world lock (a content reload,
 * say) never stalls the socket threads. Plain HTTP: keep it on this machine or behind a tunnel or a TLS proxy.
 */
class AdminHttpServer(
    private val api: AdminApi,
    private val bindAddress: String,
    private val port: Int
) : AutoCloseable {
    private val log = LoggerFactory.getLogger("AdminPanel")
    private val boss = NioEventLoopGroup(1)
    private val workers = NioEventLoopGroup(1)
    private val handlers = DefaultEventExecutorGroup(2)
    private var channel: Channel? = null

    /** The TCP port actually bound (useful when started with port 0). */
    val boundPort: Int get() = (channel?.localAddress() as? InetSocketAddress)?.port ?: port

    fun start() {
        val bootstrap = ServerBootstrap()
            .group(boss, workers)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.pipeline()
                        .addLast(ReadTimeoutHandler(READ_TIMEOUT_SECONDS))
                        .addLast(HttpServerCodec())
                        .addLast(HttpObjectAggregator(AdminApi.MAX_CONTENT_BODY + 2048))
                        .addLast(handlers, RequestHandler())
                }
            })
        channel = bootstrap.bind(InetAddress.getByName(bindAddress), port).syncUninterruptibly().channel()
        log.info("Admin panel on http://{}:{}/ (token in {})", bindAddress, boundPort, AdminToken.FILE_NAME)
    }

    override fun close() {
        channel?.close()?.syncUninterruptibly()
        boss.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS)
        workers.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS)
        handlers.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS)
    }

    private inner class RequestHandler : SimpleChannelInboundHandler<FullHttpRequest>() {
        override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
            val remote = (ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress ?: "unknown"
            val response = try {
                api.handle(
                    request.method().name(), request.uri(), request.content().toString(StandardCharsets.UTF_8),
                    request.headers().get(HttpHeaderNames.AUTHORIZATION), remote
                )
            } catch (e: Exception) {
                log.warn("Admin request failed", e)
                AdminResponse(500, "application/json; charset=utf-8", "{\"error\":\"Internal error\"}".toByteArray())
            }
            val http = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(response.status), Unpooled.wrappedBuffer(response.body))
            http.headers().set(HttpHeaderNames.CONTENT_TYPE, response.contentType).set(HttpHeaderNames.CONTENT_LENGTH, response.body.size).set(HttpHeaderNames.CONNECTION, "close")
            response.headers.forEach { (k, v) -> http.headers().set(k, v) }
            ctx.writeAndFlush(http).addListener(ChannelFutureListener.CLOSE)
        }

        @Deprecated("Netty's signature")
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            ctx.close()
        }
    }

    companion object {
        private const val READ_TIMEOUT_SECONDS = 15
    }
}
