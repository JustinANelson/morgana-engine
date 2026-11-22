package com.morgana.client

import com.morgana.client.state.ClientWorldState
import com.morgana.client.state.ClientWorldState.FacingTarget
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.WorldContentCatalogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The touch buttons say what they will do: Harvest, Talk, Shop, Bank, or the plain Use / Atk. */
class ContextActionLabelTest {

    private val width = 10

    private fun stateFacing(tile: Pair<Int, Int>, attribute: TileAttribute = TileAttribute.None, npc: NpcDefinition? = null): ClientWorldState {
        val cells = List(width * 10) { i ->
            if (i % width == tile.first && i / width == tile.second) MapCell(attribute = attribute) else MapCell()
        }
        val map = MapDefinition(id = 1, name = "T", width = width, height = 10, cells = cells)
        val hero = CharacterState(id = "h", accountId = "a", name = "H", position = WorldPosition.fromTile(1, 4, 4, Direction.RIGHT))
        val state = ClientWorldState()
        state.applyServerPacket(
            ServerPacket.AuthResult(true, "ok", character = hero, catalog = WorldContentCatalogDto(npcs = listOfNotNull(npc)))
        )
        val instances = if (npc != null) {
            listOf(
                MapNpcInstance(
                    instanceId = "n1", spawnSlotIndex = 0, npcId = npc.id, mapId = 1, tileX = tile.first, tileY = tile.second,
                    homeX = tile.first, homeY = tile.second, vitals = mapOf("hp" to VitalState(10, 10))
                )
            )
        } else {
            emptyList()
        }
        state.applyServerPacket(ServerPacket.MapStateSnapshot(activeMap = map, npcs = instances))
        return state
    }

    @Test
    fun `facing nothing gives the plain labels`() {
        val s = stateFacing(0 to 0)
        assertEquals(FacingTarget.NOTHING, s.facingTarget())
        assertEquals("Atk", s.attackButtonLabel())
        assertEquals("Use", s.interactButtonLabel())
    }

    @Test
    fun `facing a resource node is working and harvesting, not attacking`() {
        val s = stateFacing(5 to 4, TileAttribute.ResourceNode(resourceId = 1))
        assertEquals("Work", s.attackButtonLabel())
        assertEquals("Harvest", s.interactButtonLabel())
    }

    @Test
    fun `facing shops banks and friendly villagers says so`() {
        assertEquals("Shop", stateFacing(5 to 4, TileAttribute.ShopTile(shopId = 1)).interactButtonLabel())
        assertEquals("Bank", stateFacing(5 to 4, TileAttribute.BankTile).interactButtonLabel())
        val villager = NpcDefinition(id = 7, name = "Villager", behavior = NpcBehavior.QUEST_GIVER)
        assertEquals("Talk", stateFacing(5 to 4, npc = villager).interactButtonLabel())
    }

    @Test
    fun `a hostile monster is still something to attack`() {
        val wolf = NpcDefinition(id = 8, name = "Wolf", behavior = NpcBehavior.ATTACK_ON_SIGHT)
        val s = stateFacing(5 to 4, npc = wolf)
        assertEquals(FacingTarget.HOSTILE_NPC, s.facingTarget())
        assertEquals("Atk", s.attackButtonLabel())
        assertEquals("Use", s.interactButtonLabel())
    }
}
