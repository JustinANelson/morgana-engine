package com.morgana.server.web

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
import java.nio.file.Files
import java.nio.file.Path

/**
 * Answers plain HTTP on the server's WebSocket port. With a [root] (a built `client-web` page: `index.html`, `app.js`, its
 * assets) it serves the browser client, so a phone or another computer plays by opening
 * `http://<server>:<websocket port>/` with no separate web server; the page then connects back to the same address and
 * port. Every other plain request gets a 404 (or 405), instead of hanging inside the WebSocket handler as it used to.
 *
 * WebSocket upgrades pass on untouched. `/` is `index.html`. A request can never leave [root] (`..`, encoded dots,
 * drive letters and backslashes are refused).
 */
@ChannelHandler.Sharable
class WebClientHttpHandler(root: Path?, private val wiki: (() -> Map<String, ByteArray>)? = null) : ChannelInboundHandlerAdapter() {
    private val root: Path? = root?.toAbsolutePath()?.normalize()

    override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is FullHttpRequest || isUpgrade(msg)) {
            ctx.fireChannelRead(msg)
            return
        }
        try {
            if (msg.method() != HttpMethod.GET && msg.method() != HttpMethod.HEAD) {
                return send(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "Method not allowed".toByteArray(), "text/plain")
            }
            wiki?.let { source -> wikiResponse(msg.uri(), source)?.let { (status, body, type, location) ->
                return send(ctx, status, if (msg.method() == HttpMethod.HEAD) ByteArray(0) else body, type, body.size, location)
            } }
            val file = resolve(msg.uri()) ?: return send(ctx, HttpResponseStatus.NOT_FOUND, "Not found".toByteArray(), "text/plain")
            val bytes = try { Files.readAllBytes(file) } catch (_: Exception) {
                return send(ctx, HttpResponseStatus.NOT_FOUND, "Not found".toByteArray(), "text/plain")
            }
            send(ctx, HttpResponseStatus.OK, if (msg.method() == HttpMethod.HEAD) ByteArray(0) else bytes, contentType(file.fileName.toString()), bytes.size)
        } finally {
            ReferenceCountUtil.release(msg)
        }
    }

    private data class WikiReply(val status: HttpResponseStatus, val body: ByteArray, val type: String, val location: String? = null)

    /** The answer for a `/wiki...` request, or null when the path is not the wiki's (the client files then answer). */
    private fun wikiResponse(uri: String, source: () -> Map<String, ByteArray>): WikiReply? {
        val path = uri.substringBefore('?').substringBefore('#')
        if (path == "/wiki") return WikiReply(HttpResponseStatus.MOVED_PERMANENTLY, ByteArray(0), "text/plain", "/wiki/")
        if (!path.startsWith("/wiki/")) return null
        val name = path.removePrefix("/wiki/").ifEmpty { "index.html" }
        val bytes = try { source()[name] } catch (_: Exception) { null }
            ?: return WikiReply(HttpResponseStatus.NOT_FOUND, "Not found".toByteArray(), "text/plain")
        return WikiReply(HttpResponseStatus.OK, bytes, contentType(name))
    }

    private fun send(ctx: ChannelHandlerContext, status: HttpResponseStatus, body: ByteArray, type: String, length: Int = body.size, location: String? = null) {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body))
        response.headers()
            .set(HttpHeaderNames.CONTENT_TYPE, type)
            .set(HttpHeaderNames.CONTENT_LENGTH, length)
            .set(HttpHeaderNames.CONNECTION, "close")
        // The page is rebuilt under the same names, so let browsers keep it but always check for a newer one.
        if (status == HttpResponseStatus.OK) response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache")
        if (location != null) response.headers().set(HttpHeaderNames.LOCATION, location)
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
    }

    /** The file a request path names inside [root], or null when there is none or the path tries to leave it. */
    fun resolve(uri: String): Path? {
        val root = root ?: return null
        val raw = uri.substringBefore('?').substringBefore('#')
        val decoded = try { java.net.URLDecoder.decode(raw.replace("+", "%2B"), Charsets.UTF_8) } catch (_: Exception) { return null }
        if ('\\' in decoded || '\u0000' in decoded) return null
        val rel = decoded.trimStart('/').ifEmpty { "index.html" }
        if (rel.split('/').any { it == ".." || it.contains(':') }) return null
        val file = root.resolve(rel).normalize()
        if (!file.startsWith(root)) return null
        val target = if (Files.isDirectory(file)) file.resolve("index.html") else file
        return target.takeIf { Files.isRegularFile(it) }
    }

    private fun isUpgrade(request: FullHttpRequest): Boolean =
        request.headers().get(HttpHeaderNames.UPGRADE)?.equals("websocket", ignoreCase = true) == true

    companion object {
        fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "js", "mjs" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "json", "map" -> "application/json"
            "webmanifest" -> "application/manifest+json"
            "wasm" -> "application/wasm"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "svg" -> "image/svg+xml"
            "ico" -> "image/x-icon"
            "ttf" -> "font/ttf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ogg" -> "audio/ogg"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "txt", "fnt", "atlas" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }

        /**
         * Where a built web client is, for `--web` with no folder: `web/` next to the server (the SDK), else this
         * repository's release build, else its development build. Null when none is built.
         */
        fun findBuilt(workingDir: Path = Path.of("")): Path? =
            listOf("web", "client-web/build/dist/js/release/webapp", "client-web/build/dist/js/webapp", "../client-web/build/dist/js/release/webapp")
                .map { workingDir.resolve(it) }
                .firstOrNull { Files.isRegularFile(it.resolve("index.html")) }
    }
}
