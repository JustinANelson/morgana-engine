package com.morgana.protocol

import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.item.AffixStat
import com.morgana.domain.item.ItemRolls
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.RolledAffix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Rolled items (rarity and affixes) travel inside the domain types the packets already carry; older payloads still decode. */
class ItemRollsPacketTest {

    @Test
    fun `a rolled ground item round-trips through the wire format`() {
        val rolled = ItemStack(7, rolls = ItemRolls("rare", 9, listOf(RolledAffix("of_the_bear", AffixStat.VITAL_MAX, "hp", 7))))
        val packet = ServerPacket.GroundItemsUpdated(3, listOf(MapGroundItem("loot-1", 3, 4, 5, rolled)))
        assertEquals(packet, PacketCodec.decodeServerJson(PacketCodec.encodeServerJson(packet)))
    }

    @Test
    fun `a stack written before rolls existed decodes as a plain item`() {
        val old = """{"itemId":2,"amount":1,"durability":100}"""
        assertEquals(ItemStack(2, 1, 100), PacketCodec.json.decodeFromString(ItemStack.serializer(), old))
    }
}
