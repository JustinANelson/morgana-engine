package com.morgana.client.ui

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute

/**
 * Pure presentation model for high-visibility minimap points of interest (POIs):
 * boss/guardian NPCs, ground loot, dungeon warps/stairs, and shop/bank services.
 */
object MinimapPoiModel {

    enum class PoiKind {
        WARP,
        SERVICE,
        GROUND_LOOT,
        BOSS
    }

    data class MinimapPoi(
        val tileX: Int,
        val tileY: Int,
        val kind: PoiKind
    )

    fun collectPois(
        map: MapDefinition,
        npcsOnMap: Collection<MapNpcInstance>,
        npcDefsById: Map<Int, NpcDefinition>,
        groundItems: Collection<MapGroundItem>,
        isTileExplored: (Int, Int) -> Boolean
    ): List<MinimapPoi> {
        val out = mutableListOf<MinimapPoi>()

        for (ty in 0 until map.height) {
            for (tx in 0 until map.width) {
                val attr = map.getCell(tx, ty)?.attribute ?: continue
                if (!isTileExplored(tx, ty)) continue
                when (attr) {
                    is TileAttribute.Warp -> out += MinimapPoi(tx, ty, PoiKind.WARP)
                    is TileAttribute.ShopTile,
                    is TileAttribute.BankTile -> out += MinimapPoi(tx, ty, PoiKind.SERVICE)
                    else -> Unit
                }
            }
        }

        val lootTiles = HashSet<Long>()
        for (item in groundItems) {
            if (item.mapId != map.id) continue
            if (!isTileExplored(item.tileX, item.tileY)) continue
            val key = (item.tileX.toLong() shl 32) or (item.tileY.toLong() and 0xffffffffL)
            if (lootTiles.add(key)) {
                out += MinimapPoi(item.tileX, item.tileY, PoiKind.GROUND_LOOT)
            }
        }

        val floorBossNpcId = map.generator?.bossNpcId ?: 0
        for (npc in npcsOnMap) {
            if (!npc.isAlive) continue
            if (!isTileExplored(npc.tileX, npc.tileY)) continue
            val def = npcDefsById[npc.npcId] ?: continue
            if (!DungeonHudModel.isBossNpc(npc.npcId, def, floorBossNpcId)) continue
            out += MinimapPoi(npc.tileX, npc.tileY, PoiKind.BOSS)
        }

        return out
    }
}
