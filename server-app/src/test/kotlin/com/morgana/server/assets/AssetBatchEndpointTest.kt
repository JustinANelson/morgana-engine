package com.morgana.server.assets

import com.morgana.protocol.realms.AssetBundle
import com.morgana.protocol.realms.AssetKind
import io.netty.buffer.ByteBufUtil
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.FullHttpResponse
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** `GET /assets/_batch` returns several manifest files in one [AssetBundle], and never anything outside the manifest. */
class AssetBatchEndpointTest {

    private fun png(w: Int, h: Int, rgb: Int): ByteArray = ByteArrayOutputStream().also {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, rgb)
        ImageIO.write(img, "png", it)
    }.toByteArray()

    private fun get(assets: GameAssets, uri: String): FullHttpResponse {
        val channel = EmbeddedChannel(AssetHttpHandler(assets))
        channel.writeInbound(DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri))
        return channel.readOutbound()
    }

    private fun gameWithIcons(dir: Path): GameAssets {
        GameAssets.importPng(dir, "g", AssetKind.ICON, "1", png(32, 32, 0x11223344))
        GameAssets.importPng(dir, "g", AssetKind.ICON, "2", png(32, 32, 0x55667788))
        Files.writeString(dir.resolve("secret.txt"), "not art")
        return GameAssets.scan(dir, "g")
    }

    @Test
    fun `a batch returns the named files in order and leaves out anything not in the manifest`(@TempDir dir: Path) {
        val assets = gameWithIcons(dir)
        val response = get(assets, "/assets/_batch?p=icons/2.png&p=../secret.txt&p=icons/1.png&p=icons/9.png")
        assertEquals(HttpResponseStatus.OK, response.status())
        assertEquals("no-store", response.headers().get(HttpHeaderNames.CACHE_CONTROL))
        assertEquals("*", response.headers().get(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN))
        val files = AssetBundle.decode(ByteBufUtil.getBytes(response.content()), 4)
        assertArrayEquals(Files.readAllBytes(dir.resolve("assets/icons/2.png")), files[0])
        assertNull(files[1], "a path outside the manifest is never served")
        assertArrayEquals(Files.readAllBytes(dir.resolve("assets/icons/1.png")), files[2])
        assertNull(files[3])
    }

    @Test
    fun `a batch with no files is refused and single files still work`(@TempDir dir: Path) {
        val assets = gameWithIcons(dir)
        assertEquals(HttpResponseStatus.BAD_REQUEST, get(assets, "/assets/_batch").status())
        val single = get(assets, "/assets/icons/1.png")
        assertEquals(HttpResponseStatus.OK, single.status())
        assertEquals("no-cache", single.headers().get(HttpHeaderNames.CACHE_CONTROL))
    }
}
