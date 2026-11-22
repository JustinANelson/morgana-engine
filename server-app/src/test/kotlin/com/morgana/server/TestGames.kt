package com.morgana.server

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.MapNeighborLinks
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.TilePlacement
import com.morgana.persistence.GameScaffolder
import com.morgana.persistence.WorldContentRepository
import java.nio.file.Path

/**
 * Throwaway game projects for tests, built in a temp directory so no test depends on (or writes into) a game shipped in
 * `games/`. [blank] is the scaffolder's one-map game; [withNeighbours] adds a small ring of linked maps.
 */
object TestGames {
    /** The `newGame` scaffold (one 20 x 20 map, one class starting at (5, 5), one item, one NPC) under `<dir>/game`. */
    fun blank(dir: Path): Path = GameScaffolder.scaffold(dir.resolve("game"), "Test Game", "test_game")

    /**
     * [blank] with map 1 linked east to map 2 and west to map 3, map 2 linked on east to map 4, and a stand-alone map 5.
     * Map 2 has a monster two tiles from its west edge and map 3 one two tiles from its east edge, so a player at the start
     * (5, 5) on map 1 sees both across the seams. All maps are open 20 x 20 grass with no blocked tiles.
     */
    fun withNeighbours(dir: Path): Path {
        val game = blank(dir)
        val world = WorldContentRepository(game.resolve("data"))
        fun grass(id: Int, name: String, links: MapNeighborLinks = MapNeighborLinks(), spawns: List<MapNpcSpawnSlot> = emptyList()) =
            MapDefinition(
                id = id, name = name, width = SIZE, height = SIZE, neighbors = links, npcSpawns = spawns,
                cells = List(SIZE * SIZE) { MapCell(layers = mapOf(MapLayer.GROUND to TilePlacement(tilesetId = 1, srcTileX = 0, srcTileY = 0))) }
            )
        world.maps.save(1, grass(1, "Middle", MapNeighborLinks(eastMapId = 2, westMapId = 3)))
        world.maps.save(2, grass(2, "East", MapNeighborLinks(westMapId = 1, eastMapId = 4), listOf(MapNpcSpawnSlot(0, 1, spawnX = 2, spawnY = 5))))
        world.maps.save(3, grass(3, "West", MapNeighborLinks(eastMapId = 1), listOf(MapNpcSpawnSlot(0, 1, spawnX = SIZE - 3, spawnY = 5))))
        world.maps.save(4, grass(4, "Far East", MapNeighborLinks(westMapId = 2)))
        world.maps.save(5, grass(5, "Island"))
        return game
    }

    /**
     * Adds what combat and plugin tests need to a running world, in memory only: a healing draught (item 2, three in
     * every new character's bag), a self-heal spell (spell 1, known by new characters) and a hostile rat (NPC 2) spawned
     * at (8, 8) on map 1. Call it before players log in.
     */
    fun addCombatKit(sim: WorldSimulation) {
        sim.items[2] = ItemDefinition(id = 2, name = "Healing Draught", type = ItemType.CONSUMABLE, stackable = true, restore = mapOf("hp" to 20))
        sim.spells[1] = SpellDefinition(id = 1, name = "Mend", targetType = SpellTargetType.SELF, isHealing = true, basePower = 5, cooldownTicks = 2)
        sim.npcs[2] = NpcDefinition(
            id = 2, name = "Rat", behavior = NpcBehavior.ATTACK_ON_SIGHT, spriteId = 2, level = 1,
            vitals = mapOf("hp" to 30), attributes = mapOf("str" to 5, "agi" to 5, "int" to 5, "vit" to 5)
        )
        val cls = sim.classes.getValue(1)
        sim.classes[1] = cls.copy(startingItems = cls.startingItems + ItemStack(2, 3), startingSpellIds = listOf(1))
        val map = sim.maps.getValue(1)
        sim.registerMap(map.copy(npcSpawns = map.npcSpawns + MapNpcSpawnSlot(slotIndex = map.npcSpawns.size, npcId = 2, spawnX = 8, spawnY = 8)))
    }

    const val SIZE = 20
}
