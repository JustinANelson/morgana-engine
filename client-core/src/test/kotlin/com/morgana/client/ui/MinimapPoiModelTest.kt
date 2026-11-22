package com.morgana.client.ui

import com.morgana.client.ui.MinimapPoiModel.MinimapPoi
import com.morgana.client.ui.MinimapPoiModel.PoiKind
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.item.ItemStack
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MinimapPoiModelTest {

    @Test
    fun `collectPois includes warps, services, deduplicated loot, and alive bosses while respecting fog`() {
        val cells = MutableList(100) { MapCell() }
        // (1, 1) warp (explored), (8, 8) warp (unexplored)
        cells[1 * 10 + 1] = MapCell(attribute = TileAttribute.Warp(targetMapId = 2, targetX = 1, targetY = 1))
        cells[8 * 10 + 8] = MapCell(attribute = TileAttribute.Warp(targetMapId = 3, targetX = 1, targetY = 1))
        // (2, 2) shop
        cells[2 * 10 + 2] = MapCell(attribute = TileAttribute.ShopTile(shopId = 1))

        val map = MapDefinition(id = 1, name = "Crypt (Floor 3)", width = 10, height = 10, cells = cells)

        val npcDefs = mapOf(
            10 to NpcDefinition(id = 10, name = "Bone Sovereign", title = "Floor Boss", behavior = NpcBehavior.ATTACK_ON_SIGHT),
            11 to NpcDefinition(id = 11, name = "Skeleton", behavior = NpcBehavior.ATTACK_ON_SIGHT),
            12 to NpcDefinition(id = 12, name = "Merchant", title = "Boss Supplies", behavior = NpcBehavior.SHOPKEEPER)
        )
        val npcs = listOf(
            MapNpcInstance(
                instanceId = "b1",
                spawnSlotIndex = 0,
                npcId = 10,
                mapId = 1,
                tileX = 4,
                tileY = 4,
                homeX = 4,
                homeY = 4,
                vitals = mapOf("hp" to VitalState(50, 100))
            ),
            MapNpcInstance(
                instanceId = "b_dead",
                spawnSlotIndex = 1,
                npcId = 10,
                mapId = 1,
                tileX = 3,
                tileY = 3,
                homeX = 3,
                homeY = 3,
                vitals = mapOf("hp" to VitalState(0, 100))
            ),
            MapNpcInstance(
                instanceId = "m1",
                spawnSlotIndex = 2,
                npcId = 11,
                mapId = 1,
                tileX = 2,
                tileY = 4,
                homeX = 2,
                homeY = 4,
                vitals = mapOf("hp" to VitalState(10, 10))
            ),
            MapNpcInstance(
                instanceId = "v1",
                spawnSlotIndex = 3,
                npcId = 12,
                mapId = 1,
                tileX = 1,
                tileY = 2,
                homeX = 1,
                homeY = 2,
                vitals = mapOf("hp" to VitalState(10, 10))
            )
        )

        val loot = listOf(
            MapGroundItem("g1", mapId = 1, tileX = 5, tileY = 5, stack = ItemStack(1, 1)),
            MapGroundItem("g2", mapId = 1, tileX = 5, tileY = 5, stack = ItemStack(2, 1)), // duplicate tile
            MapGroundItem("g3", mapId = 1, tileX = 9, tileY = 9, stack = ItemStack(3, 1)), // unexplored
            MapGroundItem("g4", mapId = 2, tileX = 1, tileY = 1, stack = ItemStack(4, 1))  // other map
        )

        val pois = MinimapPoiModel.collectPois(
            map = map,
            npcsOnMap = npcs,
            npcDefsById = npcDefs,
            groundItems = loot,
            isTileExplored = { x, y -> x <= 5 && y <= 5 }
        )

        assertEquals(
            listOf(
                MinimapPoi(1, 1, PoiKind.WARP),
                MinimapPoi(2, 2, PoiKind.SERVICE),
                MinimapPoi(5, 5, PoiKind.GROUND_LOOT),
                MinimapPoi(4, 4, PoiKind.BOSS)
            ),
            pois
        )
        assertTrue(pois.none { it.tileX > 5 || it.tileY > 5 })
    }
}
