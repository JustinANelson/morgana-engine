package com.morgana.protocol

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemStack
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Equipment slots are string ids now (not an enum): the wire formats must still carry them, as map keys and as values. */
class EquipmentSlotCodecTest {
    private val garment = EquipmentSlot("GARMENT")
    private val character = CharacterState(
        id = "c1", accountId = "a1", name = "Hero",
        position = WorldPosition.fromTile(1, 2, 2, Direction.DOWN),
        equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(3), garment to ItemStack(9)))
    )

    @Test
    fun `a character wearing a game-defined slot survives json and cbor`() {
        val sync = ServerPacket.CharacterSync(character)
        assertEquals(sync, PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(sync)))
        assertEquals(sync, PacketCodec.decodeServerCbor(PacketCodec.encodeServerCbor(sync)))
    }

    @Test
    fun `slots keep their bare id on the wire exactly like the old enum`() {
        val json = PacketCodec.encodeServerJson(ServerPacket.CharacterSync(character))
        assertTrue("\"WEAPON\":" in json && "\"GARMENT\":" in json, "slot ids are plain map keys: $json")
        val unequip = ClientPacket.UnequipSlot(garment)
        assertTrue("\"slot\":\"GARMENT\"" in PacketCodec.encodeClientJson(unequip))
        assertEquals(unequip, PacketCodec.decodeClientJson("{\"pkt\":\"${PacketCodec.encodeClientJson(unequip).substringAfter("\"pkt\":\"").substringBefore("\"")}\",\"slot\":\"GARMENT\"}"))
        assertEquals(unequip, PacketCodec.decodeClientCbor(PacketCodec.encodeClientCbor(unequip)))
    }
}
