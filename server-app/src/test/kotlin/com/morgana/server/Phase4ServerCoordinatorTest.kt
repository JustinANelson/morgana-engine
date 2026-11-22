package com.morgana.server

import com.morgana.domain.access.AccessLevel
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.PacketCodec
import com.morgana.protocol.ServerPacket
import io.netty.bootstrap.Bootstrap
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.LengthFieldBasedFrameDecoder
import io.netty.handler.codec.LengthFieldPrepender
import io.netty.handler.codec.string.StringDecoder
import io.netty.handler.codec.string.StringEncoder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class Phase4ServerCoordinatorTest {

    private class FakeClientSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        override val isLoopback: Boolean = true
        val outbox: MutableList<ServerPacket> = mutableListOf()
        var closed: Boolean = false

        override fun send(packet: ServerPacket) {
            outbox.add(packet)
        }

        override fun close(reason: String) {
            closed = true
        }
    }

    @Test
    fun `first registered account receives OWNER access, seamless neighbor snapshots, and broadcasts F1 live edits`(@TempDir tempDir: Path) {
        val store = MorganaDataStore(tempDir)
        // The seeded blank world has one map; give it an east neighbour so the snapshot has something across the seam.
        com.morgana.persistence.GameScaffolder.seedIfEmpty(store.world)
        val home = store.world.maps.findById(1)!!
        store.world.maps.save(1, home.copy(neighbors = com.morgana.domain.map.MapNeighborLinks(eastMapId = 2)))
        store.world.maps.save(2, com.morgana.domain.map.MapDefinition(id = 2, name = "East Field", width = home.width, height = home.height,
            neighbors = com.morgana.domain.map.MapNeighborLinks(westMapId = 1)))
        val coordinator = MorganaWorldCoordinator(store)

        val s1 = FakeClientSession("s1")
        val s2 = FakeClientSession("s2")
        coordinator.onSessionConnected(s1)
        coordinator.onSessionConnected(s2)

        // First player logs in -> receives OWNER access
        coordinator.handleClientPacket(
            s1,
            ClientPacket.Authenticate(username = "Creator", password = "pw1", registerIfNew = true, requestedClassId = 1, protocolVersion = PROTOCOL_VERSION)
        )
        val auth1 = s1.outbox.filterIsInstance<ServerPacket.AuthResult>().firstOrNull()
        assertNotNull(auth1)
        assertTrue(auth1!!.success)
        assertEquals(AccessLevel.OWNER, auth1.character?.accessLevel)

        val snap1 = s1.outbox.filterIsInstance<ServerPacket.MapStateSnapshot>().firstOrNull()
        assertNotNull(snap1)
        assertEquals(1, snap1!!.activeMap!!.id)
        // Verify East neighbor Map 2 is included in snapshot for seamless map stitching!
        assertTrue(snap1.neighborMaps.containsKey(2))

        // Second player logs in -> receives PLAYER access
        coordinator.handleClientPacket(
            s2,
            ClientPacket.Authenticate(username = "Explorer", password = "pw2", registerIfNew = true, requestedClassId = 2, protocolVersion = PROTOCOL_VERSION)
        )
        val auth2 = s2.outbox.filterIsInstance<ServerPacket.AuthResult>().firstOrNull()
        assertNotNull(auth2)
        assertEquals(AccessLevel.PLAYER, auth2!!.character?.accessLevel)

        s1.outbox.clear()
        s2.outbox.clear()

        // Creator (Access 4) edits Item #99 live via F1 Item Editor -> saved to disk & broadcast to both players!
        val mythicBlade = ItemDefinition(id = 99, name = "Mythic Sunblade", type = ItemType.WEAPON, attackDamage = 45)
        coordinator.handleClientPacket(s1, ClientPacket.SaveItemDefinition(mythicBlade))

        assertTrue(s1.outbox.any { it is ServerPacket.ContentCatalogUpdated })
        assertTrue(s2.outbox.any { it is ServerPacket.ContentCatalogUpdated })
        assertEquals("Mythic Sunblade", store.world.items.findById(99)?.name)
    }

    @Test
    fun `netty TCP server binds, authenticates over length-framed JSON sockets, and shuts down cleanly`(@TempDir tempDir: Path) {
        val store = MorganaDataStore(tempDir)
        val coordinator = MorganaWorldCoordinator(store)
        val server = MorganaNettyServer(port = 0, coordinator = coordinator)
        server.start()

        val received = LinkedBlockingQueue<ServerPacket>()
        val clientGroup = NioEventLoopGroup(1)

        try {
            val bootstrap = Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel::class.java)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().addLast(
                            LengthFieldBasedFrameDecoder(2 * 1024 * 1024, 0, 4, 0, 4),
                            StringDecoder(StandardCharsets.UTF_8),
                            LengthFieldPrepender(4),
                            StringEncoder(StandardCharsets.UTF_8),
                            object : SimpleChannelInboundHandler<String>() {
                                override fun channelRead0(ctx: ChannelHandlerContext, msg: String) {
                                    received.offer(PacketCodec.decodeServerJson(msg))
                                }
                            }
                        )
                    }
                })

            val ch = bootstrap.connect("127.0.0.1", server.boundPort).sync().channel()
            ch.writeAndFlush(PacketCodec.encodeClientJson(ClientPacket.Authenticate("NettyHero", "pass", true, 3, protocolVersion = PROTOCOL_VERSION)))

            val pkt1 = received.poll(5, TimeUnit.SECONDS)
            assertNotNull(pkt1)
            assertTrue(pkt1 is ServerPacket.AuthResult)
            assertTrue((pkt1 as ServerPacket.AuthResult).success)

            ch.close().sync()
        } finally {
            clientGroup.shutdownGracefully().syncUninterruptibly()
            server.close()
        }
    }
}

