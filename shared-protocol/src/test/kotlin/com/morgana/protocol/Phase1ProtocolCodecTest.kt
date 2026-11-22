package com.morgana.protocol

import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.spatial.Direction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class Phase1ProtocolCodecTest {

    @Test
    fun `client and server packets round-trip cleanly through both JSON and CBOR codecs`() {
        val map = MapDefinition(id = 3, name = "Crypt Level 1", width = 12, height = 10).withCell(
            x = 5,
            y = 5,
            newCell = MapCell(attribute = TileAttribute.Warp(targetMapId = 1, targetX = 10, targetY = 8, targetDirection = Direction.UP))
        )

        val saveMapPkt: ClientPacket = ClientPacket.SaveMapDefinition(map)
        val jsonStr = PacketCodec.encodeClientJson(saveMapPkt)
        val decodedJson = PacketCodec.decodeClientJson(jsonStr)
        assertEquals(saveMapPkt, decodedJson)

        val cborBytes = PacketCodec.encodeClientCbor(saveMapPkt)
        val decodedCbor = PacketCodec.decodeClientCbor(cborBytes)
        assertEquals(saveMapPkt, decodedCbor)

        val serverPkt: ServerPacket = ServerPacket.ContentCatalogUpdated(
            catalog = WorldContentCatalogDto(
                items = listOf(ItemDefinition(id = 1, name = "Gold", type = ItemType.CURRENCY, stackable = true))
            ),
            summaryMessage = "Updated Item #1"
        )
        val serverJson = PacketCodec.encodeServerJson(serverPkt)
        assertEquals(serverPkt, PacketCodec.decodeServerJson(serverJson))

        val serverCbor = PacketCodec.encodeServerCbor(serverPkt)
        assertEquals(serverPkt, PacketCodec.decodeServerCbor(serverCbor))
    }

    @Test
    fun `protocol version is carried by auth packets and a missing version decodes as unknown 0`() {
        val auth = ClientPacket.Authenticate("Hero", "pw", protocolVersion = PROTOCOL_VERSION)
        assertEquals(auth, PacketCodec.decodeClientJson(PacketCodec.encodeClientJson(auth)))
        assertEquals(PROTOCOL_VERSION, (PacketCodec.decodeClientJson(PacketCodec.encodeClientJson(auth)) as ClientPacket.Authenticate).protocolVersion)

        // A client built before versioning sends no field at all: it must decode as 0 so the server can reject it.
        val legacyJson = """{"pkt":"c_auth","username":"Old","password":"pw"}"""
        assertEquals(0, (PacketCodec.decodeClientJson(legacyJson) as ClientPacket.Authenticate).protocolVersion)

        val result = ServerPacket.AuthResult(success = false, message = "no")
        assertEquals(PROTOCOL_VERSION, (PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(result)) as ServerPacket.AuthResult).serverProtocolVersion)
    }

    @Test
    fun `unknown server and client packets decode into ignorable Unknown packets for forward compatibility`() {
        val futureServerJson = """{"pkt":"s_quantum_leap","universeId":42,"coords":[1,2,3]}"""
        val decodedServer = PacketCodec.decodeServerJson(futureServerJson)
        org.junit.jupiter.api.Assertions.assertTrue(decodedServer is ServerPacket.Unknown)
        val unknownServer = decodedServer as ServerPacket.Unknown
        assertEquals("s_quantum_leap", unknownServer.rawType)
        org.junit.jupiter.api.Assertions.assertTrue(unknownServer.rawPayload.contains("universeId"))

        val futureClientJson = """{"pkt":"c_hologram_emote","emoteId":"dance"}"""
        val decodedClient = PacketCodec.decodeClientJson(futureClientJson)
        org.junit.jupiter.api.Assertions.assertTrue(decodedClient is ClientPacket.Unknown)
        val unknownClient = decodedClient as ClientPacket.Unknown
        assertEquals("c_hologram_emote", unknownClient.rawType)
    }

    @Test
    fun `protocol capabilities are exchanged in auth and auth result`() {
        val auth = ClientPacket.Authenticate("Hero", "pw", protocolVersion = PROTOCOL_VERSION, capabilities = listOf(ProtocolCapabilities.SOFT_SEAMS, ProtocolCapabilities.SESSION_RESUME))
        val authJson = PacketCodec.encodeClientJson(auth)
        val decodedAuth = PacketCodec.decodeClientJson(authJson) as ClientPacket.Authenticate
        assertEquals(listOf(ProtocolCapabilities.SOFT_SEAMS, ProtocolCapabilities.SESSION_RESUME), decodedAuth.capabilities)

        val result = ServerPacket.AuthResult(
            success = true,
            message = "ok",
            capabilities = listOf(ProtocolCapabilities.SOFT_SEAMS, ProtocolCapabilities.SESSION_RESUME, ProtocolCapabilities.REALM_INFO)
        )
        val resultJson = PacketCodec.encodeServerJson(result)
        val decodedResult = PacketCodec.decodeServerJson(resultJson) as ServerPacket.AuthResult
        assertEquals(listOf(ProtocolCapabilities.SOFT_SEAMS, ProtocolCapabilities.SESSION_RESUME, ProtocolCapabilities.REALM_INFO), decodedResult.capabilities)

        val testCmd = ClientPacket.TestEventCommands(
            eventId = 42,
            commands = listOf(com.morgana.domain.event.EventCommand.ShowMessage("Preview"), com.morgana.domain.event.EventCommand.Stop)
        )
        val decodedTest = PacketCodec.decodeClientJson(PacketCodec.encodeClientJson(testCmd)) as ClientPacket.TestEventCommands
        assertEquals(testCmd, decodedTest)

        val ce = com.morgana.domain.event.CommonEventDefinition(
            id = 5,
            name = "Town Bell",
            description = "Rings across the realm",
            commands = listOf(
                com.morgana.domain.event.EventCommand.SetSwitch("bell_rung", true, com.morgana.domain.event.VariableScope.WORLD),
                com.morgana.domain.event.EventCommand.AddVariable("bells", 1, com.morgana.domain.event.VariableScope.WORLD),
                com.morgana.domain.event.EventCommand.Wait(30),
                com.morgana.domain.event.EventCommand.PlaySound("bell"),
                com.morgana.domain.event.EventCommand.ShowPicture("banner", "Festival", 100),
                com.morgana.domain.event.EventCommand.MoveNpc(2, com.morgana.domain.spatial.Direction.LEFT, steps = 2, faceOnly = false),
                com.morgana.domain.event.EventCommand.TextInput("Code?", "secret_code", com.morgana.domain.event.VariableScope.WORLD, false, "0000", 16)
            )
        )
        val saveCe = ClientPacket.SaveCommonEventDefinition(ce)
        val decodedSaveCe = PacketCodec.decodeClientJson(PacketCodec.encodeClientJson(saveCe)) as ClientPacket.SaveCommonEventDefinition
        assertEquals(saveCe, decodedSaveCe)

        val playSound = ServerPacket.PlaySound("level_up")
        assertEquals(playSound, PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(playSound)))

        val showPicture = ServerPacket.ShowPicture("portraits/mayor", "Mayor Elwin", durationTicks = 80)
        assertEquals(showPicture, PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(showPicture)))

        val cooldown = ServerPacket.SpellCooldown(spellId = 7, durationTicks = 40)
        assertEquals(cooldown, PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(cooldown)))

        val notice = ServerPacket.ChatBroadcast(ChatChannel.SYSTEM, senderName = "System", message = "Level up!", notice = NoticeKind.LEVEL)
        assertEquals(notice, PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(notice)))
        val oldServerChat = """{"pkt":"s_chat","channel":"SYSTEM","senderName":"Server","message":"hi"}"""
        assertEquals(null, (PacketCodec.decodeServerJson(oldServerChat) as ServerPacket.ChatBroadcast).notice, "a message from an older server has no notice")

        val textPrompt = ServerPacket.TextInputPrompt(eventId = 5, prompt = "Code?", isNumeric = true, defaultValue = "42", maxLength = 8)
        assertEquals(textPrompt, PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(textPrompt)))

        val submitText = ClientPacket.SubmitTextInput(eventId = 5, value = "42", cancelled = false)
        assertEquals(submitText, PacketCodec.decodeClientJson(PacketCodec.encodeClientJson(submitText)))

        for (size in listOf(0, 1, 2, 3, 4, 17, 256)) {
            val sample = ByteArray(size) { ((it * 31) and 0xFF).toByte() }
            val encoded = Base64Codec.encode(sample)
            val decoded = Base64Codec.decodeOrNull(encoded)
            assertEquals(sample.toList(), decoded?.toList())
        }
        assertEquals(null, Base64Codec.decodeOrNull("abc"))
        assertEquals(null, Base64Codec.decodeOrNull("A=AA"))
        assertEquals(null, Base64Codec.decodeOrNull("!@#\$"))

        val artEntry = com.morgana.protocol.realms.AssetManifestEntry("icons/12.png", "deadbeef", 4, com.morgana.protocol.realms.AssetKind.ICON)
        val importArt = ClientPacket.ImportGameArt(com.morgana.protocol.realms.AssetKind.ICON, "12", Base64Codec.encode(byteArrayOf(1, 2, 3, 4)))
        assertEquals(importArt, PacketCodec.decodeClientJson(PacketCodec.encodeClientJson(importArt)))
        val artUpdated = ServerPacket.GameArtUpdated(
            entry = artEntry,
            manifest = com.morgana.protocol.realms.AssetManifest(gameId = "g", assets = mapOf(artEntry.path to artEntry)),
            pngBase64 = Base64Codec.encode(byteArrayOf(1, 2, 3, 4))
        )
        assertEquals(artUpdated, PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(artUpdated)))
    }

    @Test
    fun `gameplay packets leave out default fields and still round trip, definition packets keep every field`() {
        val pong = ServerPacket.Pong(clientTimestampMs = 5L, serverTick = 9L)
        val update = ServerPacket.PlayerLeftMap(characterId = "c1", mapId = 3)
        val lean = PacketCodec.encodeServerJson(ServerPacket.SessionEnded(reason = "bye"))
        assertEquals(ServerPacket.SessionEnded(reason = "bye"), PacketCodec.decodeServerJson(lean))
        assertEquals(false, lean.contains("resumable"), "a default value is not written")
        assertEquals(true, lean.contains("\"pkt\":\"s_session_end\""), "the packet type is always written")
        assertEquals(pong, PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(pong)))
        assertEquals(update, PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(update)))

        val login = PacketCodec.encodeServerJson(ServerPacket.AuthResult(success = false, message = "no"))
        assertEquals(true, login.contains("serverProtocolVersion"), "the login result names the server's protocol version even though it is the default")
        val catalog = PacketCodec.encodeServerJson(ServerPacket.ContentCatalogUpdated(WorldContentCatalogDto()))
        assertEquals(true, catalog.contains("summaryMessage"))
    }
}
