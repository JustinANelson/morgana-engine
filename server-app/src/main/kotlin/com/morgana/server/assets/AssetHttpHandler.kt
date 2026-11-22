package com.morgana.server.assets

import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.util.ReferenceCountUtil
import com.morgana.protocol.realms.AssetBundle
import io.netty.handler.codec.http.QueryStringDecoder
import java.nio.file.Files

/**
 * Serves a game's own art (`GET /assets/<manifest path>`) on the server's HTTP-capable (WebSocket) port, ahead of the
 * WebSocket upgrade. Only files listed in the validated [GameAssets] manifest are reachable, looked up by exact manifest
 * path, so a request can never name an arbitrary file on disk. Every other request, including an `/assets/` path the
 * manifest does not list (the browser client's own files, when this server hosts it), passes through untouched.
 *
 * The URL stays the same when a creator edits a file, so responses must never be cached blindly: each carries an `ETag`
 * (the file's SHA-256) and `Cache-Control: no-cache`, which lets a browser keep the file but revalidate it with a cheap
 * `304 Not Modified`. (Marking them `immutable` would pin the old art in a returning player's cache for a year.)
 */
@ChannelHandler.Sharable
class AssetHttpHandler(private val assetsProvider: () -> GameAssets) : ChannelInboundHandlerAdapter() {
    constructor(assets: GameAssets) : this({ assets })

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is FullHttpRequest) { ctx.fireChannelRead(msg); return }
        val path = msg.uri().substringBefore('?')
        if (!path.startsWith(PREFIX)) { ctx.fireChannelRead(msg); return }
        val assets = assetsProvider()
        val rel = path.removePrefix(PREFIX)
        if (rel == AssetBundle.BATCH_PATH) {
            try { respondBatch(ctx, msg, assets) } finally { ReferenceCountUtil.release(msg) }
            return
        }
        val file = assets.fileFor(rel)
        // Not the game's art: let the next handler (the hosted browser client, or a 404) answer.
        if (file == null) { ctx.fireChannelRead(msg); return }
        try {
            respond(ctx, msg, rel, assets, file)
        } finally {
            ReferenceCountUtil.release(msg)
        }
    }

    private fun respond(ctx: ChannelHandlerContext, request: FullHttpRequest, rel: String, assets: GameAssets, file: java.nio.file.Path) {
        if (request.method() != HttpMethod.GET && request.method() != HttpMethod.HEAD) {
            return send(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, ByteArray(0), "text/plain")
        }
        val etag = assets.manifest.assets[rel]?.sha256?.let { "\"$it\"" }
        if (etag != null && request.headers().get(HttpHeaderNames.IF_NONE_MATCH) == etag) {
            return send(ctx, HttpResponseStatus.NOT_MODIFIED, ByteArray(0), "image/png", etag = etag)
        }
        val bytes = try { Files.readAllBytes(file) } catch (_: Exception) {
            return send(ctx, HttpResponseStatus.NOT_FOUND, "Not found".toByteArray(), "text/plain")
        }
        send(ctx, HttpResponseStatus.OK, if (request.method() == HttpMethod.HEAD) ByteArray(0) else bytes, "image/png", bytes.size, etag)
    }

    /**
     * Several manifest files in one response ([AssetBundle]): `?p=<path>` once per file, at most [AssetBundle.MAX_FILES].
     * Only manifest paths are served, exactly as for single files; anything else, and anything past
     * [AssetBundle.MAX_BYTES], is marked as not included and the client fetches it on its own. Never cached: the same
     * request can name different files' current versions.
     */
    private fun respondBatch(ctx: ChannelHandlerContext, request: FullHttpRequest, assets: GameAssets) {
        if (request.method() != HttpMethod.GET) return send(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, ByteArray(0), "text/plain")
        val paths = QueryStringDecoder(request.uri()).parameters()["p"].orEmpty().take(AssetBundle.MAX_FILES)
        if (paths.isEmpty()) return send(ctx, HttpResponseStatus.BAD_REQUEST, "No files named".toByteArray(), "text/plain")
        var total = 0
        val files = paths.map { p ->
            val file = assets.fileFor(p) ?: return@map null
            val bytes = try { Files.readAllBytes(file) } catch (_: Exception) { return@map null }
            if (total + bytes.size > AssetBundle.MAX_BYTES) return@map null
            total += bytes.size
            bytes
        }
        send(ctx, HttpResponseStatus.OK, AssetBundle.encode(files), "application/octet-stream", cache = "no-store")
    }

    private fun send(ctx: ChannelHandlerContext, status: HttpResponseStatus, body: ByteArray, type: String, length: Int = body.size, etag: String? = null, cache: String = "no-cache") {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body))
        response.headers()
            .set(HttpHeaderNames.CONTENT_TYPE, type)
            .set(HttpHeaderNames.CONTENT_LENGTH, length)
            .set(HttpHeaderNames.CONNECTION, "close")
            // Browser clients on another origin (the web client page) must be allowed to fetch the art.
            .set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        if (status == HttpResponseStatus.OK || status == HttpResponseStatus.NOT_MODIFIED) {
            response.headers().set(HttpHeaderNames.CACHE_CONTROL, cache)
            if (etag != null) response.headers().set(HttpHeaderNames.ETAG, etag)
        }
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
    }

    companion object {
        const val PREFIX = "/assets/"
    }
}
