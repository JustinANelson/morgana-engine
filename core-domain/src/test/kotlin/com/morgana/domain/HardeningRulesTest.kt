package com.morgana.domain

import com.morgana.domain.content.CraftingStationType
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.content.RecipeIngredient
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.EventPage
import com.morgana.domain.event.EventTriggerType
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.sim.MovementRules
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.validation.ContentCatalog
import com.morgana.domain.validation.ContentCollection
import com.morgana.domain.validation.ContentValidator
import com.morgana.domain.validation.IssueSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Covers the rules added to close the recorded known issues (movement rate limit, stations, autorun, hooks, map links). */
class HardeningRulesTest {

    private fun player(x: Int = 3, y: Int = 3, mapId: Int = 1) = CharacterState(
        id = "p1", accountId = "a1", name = "Tester",
        position = WorldPosition.fromTile(mapId, x, y, Direction.DOWN)
    )

    private fun sim(vararg maps: MapDefinition): WorldSimulation {
        val s = WorldSimulation(game = MorganaClassic.COMPILED)
        maps.forEach { s.registerMap(it) }
        return s
    }

    private fun open(id: Int) = MapDefinition(id = id, name = "Map $id", width = 10, height = 10)

    // ---- Movement rate limit ----------------------------------------------------------------------

    @Test
    fun `grid moves faster than the minimum step interval are dropped and resynced`() {
        val s = sim(open(1))
        s.spawnOrUpdatePlayer(player())
        repeat(5) { s.stepTick() }

        val first = s.movePlayer("p1", Direction.RIGHT)
        assertEquals(4, s.players.getValue("p1").position.tileX)
        assertTrue(first.any { it is WorldSimulationEvent.PlayerMoved })

        // Same tick: rejected, position unchanged, authoritative CharacterUpdated returned for client resync.
        val spam = s.movePlayer("p1", Direction.RIGHT)
        assertEquals(4, s.players.getValue("p1").position.tileX)
        assertTrue(spam.single() is WorldSimulationEvent.CharacterUpdated)

        // One tick later is still too fast for grid mode.
        s.stepTick()
        s.movePlayer("p1", Direction.RIGHT)
        assertEquals(4, s.players.getValue("p1").position.tileX)

        repeat(MovementRules.GRID_MIN_STEP_TICKS - 1) { s.stepTick() }
        s.movePlayer("p1", Direction.RIGHT)
        assertEquals(5, s.players.getValue("p1").position.tileX)
    }

    @Test
    fun `a speed-hacking client cannot out-run the limit over many ticks`() {
        val s = sim(MapDefinition(id = 1, name = "Long", width = 64, height = 5))
        s.spawnOrUpdatePlayer(player(x = 1, y = 2))
        repeat(3) { s.stepTick() }
        val startX = s.players.getValue("p1").position.tileX
        repeat(40) {
            repeat(10) { s.movePlayer("p1", Direction.RIGHT) } // 10 attempts per tick
            s.stepTick()
        }
        val moved = s.players.getValue("p1").position.tileX - startX
        assertTrue(moved <= 40 / MovementRules.GRID_MIN_STEP_TICKS + 1, "moved $moved tiles in 40 ticks")
    }

    @Test
    fun `sub-tile moves are limited to one step per tick and clamped per step`() {
        val s = sim(open(1))
        s.spawnOrUpdatePlayer(player())
        repeat(3) { s.stepTick() }
        s.movePlayer("p1", Direction.RIGHT, MovementMode.SUB_TILE, deltaPixelsX = 100f)
        val afterFirst = s.players.getValue("p1").position.pixelX
        val startPx = 3 * 32f
        assertTrue(afterFirst - startPx <= MovementRules.SUB_TILE_MAX_STEP_PIXELS + 0.01f, "clamped step, got ${afterFirst - startPx}")

        s.movePlayer("p1", Direction.RIGHT, MovementMode.SUB_TILE, deltaPixelsX = 8f)
        assertEquals(afterFirst, s.players.getValue("p1").position.pixelX, "second step in same tick is rejected")
        s.stepTick()
        s.movePlayer("p1", Direction.RIGHT, MovementMode.SUB_TILE, deltaPixelsX = 8f)
        assertEquals(afterFirst + 8f, s.players.getValue("p1").position.pixelX, 0.01f)
    }

    // ---- Crafting stations --------------------------------------------------------------------------

    private fun craftingSim(station: CraftingStationType): WorldSimulation {
        val s = sim(open(1))
        val ore = ItemDefinition(id = 1, name = "Ore", type = ItemType.MATERIAL, stackable = true, maxStack = 99)
        val sword = ItemDefinition(id = 2, name = "Sword", type = ItemType.WEAPON)
        s.items[1] = ore
        s.items[2] = sword
        s.recipes[1] = RecipeDefinition(
            id = 1, name = "Forge Sword", stationType = station,
            ingredients = listOf(RecipeIngredient(1, 2)), outputItemId = 2
        )
        val p = player().let { it.copy(inventory = it.inventory.addItem(ore, 5).updatedInventory) }
        s.spawnOrUpdatePlayer(p)
        return s
    }

    private fun openStation(s: WorldSimulation, station: CraftingStationType) {
        s.executeEventCommands("p1", 1, listOf(EventCommand.OpenCrafting(station)), mutableListOf())
    }

    @Test
    fun `station recipes need an opened station and ANYWHERE recipes never do`() {
        val s = craftingSim(CraftingStationType.ANVIL)
        val denied = s.craftRecipe("p1", 1)
        assertEquals(0, s.players.getValue("p1").inventory.totalCountOf(2), "no sword without a station")
        val msg = denied.filterIsInstance<WorldSimulationEvent.ChatEmitted>().single()
        assertTrue(msg.message.contains("anvil"), msg.message)

        openStation(s, CraftingStationType.WORKBENCH) // wrong kind of station
        s.craftRecipe("p1", 1)
        assertEquals(0, s.players.getValue("p1").inventory.totalCountOf(2))

        openStation(s, CraftingStationType.ANVIL)
        s.craftRecipe("p1", 1)
        assertEquals(1, s.players.getValue("p1").inventory.totalCountOf(2))

        val anywhere = craftingSim(CraftingStationType.ANYWHERE)
        anywhere.craftRecipe("p1", 1)
        assertEquals(1, anywhere.players.getValue("p1").inventory.totalCountOf(2))
    }

    @Test
    fun `walking away from the station or logging out invalidates it`() {
        val s = craftingSim(CraftingStationType.ANVIL)
        openStation(s, CraftingStationType.ANVIL)
        repeat(3) { s.stepTick() }
        s.movePlayer("p1", Direction.RIGHT)
        s.craftRecipe("p1", 1)
        assertEquals(0, s.players.getValue("p1").inventory.totalCountOf(2), "moved off the station tile")

        openStation(s, CraftingStationType.ANVIL)
        s.removePlayer("p1")
        assertFalse(s.craftingAnchors.containsKey("p1"))
    }

    // ---- Autorun events -------------------------------------------------------------------------------

    private fun autorunEvent(id: Int, mapId: Int, vararg commands: EventCommand) = EventDefinition(
        id = id, name = "Autorun $id", mapId = mapId,
        pages = listOf(EventPage(trigger = EventTriggerType.AUTORUN_ON_ENTER, commands = commands.toList()))
    )

    @Test
    fun `autorun pages fire on login, warp tiles, and border crossings but not on plain moves`() {
        val gold = ItemDefinition(id = 1, name = "Gold", type = ItemType.CURRENCY, stackable = true, maxStack = 999)
        val start = open(1)
        val warpCells = start.cells.toMutableList().also {
            it[start.cellIndex(4, 3)] = MapCell(attribute = TileAttribute.Warp(targetMapId = 2, targetX = 1, targetY = 1))
        }
        val s = sim(start.copy(cells = warpCells), open(2))
        s.items[1] = gold
        s.events[1] = autorunEvent(1, 1, EventCommand.GiveItem(1, 1))
        s.events[2] = autorunEvent(2, 2, EventCommand.GiveItem(1, 10))

        s.spawnOrUpdatePlayer(player())
        assertEquals(1, s.players.getValue("p1").inventory.totalCountOf(1), "login entry on map 1")

        s.spawnOrUpdatePlayer(s.players.getValue("p1")) // re-sync of an existing player is not an entry
        assertEquals(1, s.players.getValue("p1").inventory.totalCountOf(1))

        repeat(3) { s.stepTick() }
        s.movePlayer("p1", Direction.RIGHT) // steps onto the warp tile at (4,3)
        assertEquals(2, s.players.getValue("p1").position.mapId)
        assertEquals(11, s.players.getValue("p1").inventory.totalCountOf(1), "warp entry on map 2")
    }

    @Test
    fun `autorun warp loops terminate`() {
        val s = sim(open(1), open(2))
        s.events[1] = autorunEvent(1, 1, EventCommand.WarpPlayer(2, 1, 1))
        s.events[2] = autorunEvent(2, 2, EventCommand.WarpPlayer(1, 1, 1))
        val out = s.spawnOrUpdatePlayer(player())
        assertTrue(out.isNotEmpty())
        assertTrue(s.players.getValue("p1").position.mapId in setOf(1, 2))
    }

    // ---- Custom hook events ---------------------------------------------------------------------------

    @Test
    fun `kotlin_hook commands emit a CustomHookRequested event with their payload`() {
        val s = sim(open(1))
        s.spawnOrUpdatePlayer(player())
        val out = mutableListOf<WorldSimulationEvent>()
        s.executeEventCommands("p1", 7, listOf(EventCommand.InvokeKotlinHook("quest.bonus", mapOf("tier" to "gold"))), out)
        val hook = out.filterIsInstance<WorldSimulationEvent.CustomHookRequested>().single()
        assertEquals("quest.bonus", hook.hookId)
        assertEquals(7, hook.eventId)
        assertEquals(mapOf("tier" to "gold"), hook.payload)
    }

    // ---- Validator: map links are warnings --------------------------------------------------------------

    @Test
    fun `links to maps that do not exist yet are warnings, other broken references stay errors`() {
        val base = open(1)
        val cells = base.cells.toMutableList().also {
            it[base.cellIndex(2, 2)] = MapCell(attribute = TileAttribute.Warp(targetMapId = 99, targetX = 1, targetY = 1))
        }
        val map = base.copy(cells = cells, neighbors = com.morgana.domain.map.MapNeighborLinks(eastMapId = 98))
        val report = ContentValidator.validate(ContentCatalog(maps = mapOf(1 to map)), MorganaClassic.definition)
        assertTrue(report.isValid, report.errors.toString())
        val codes = report.warnings.filter { it.collection == ContentCollection.MAPS }.map { it.code }
        assertEquals(2, codes.count { it == "missing_map_link" }, report.warnings.toString())
        assertTrue(report.warnings.all { it.severity == IssueSeverity.WARNING })

        val withItemWarp = map.copy(
            cells = map.cells.toMutableList().also {
                it[map.cellIndex(5, 5)] = MapCell(attribute = TileAttribute.ItemSpawn(itemId = 4242))
            }
        )
        val bad = ContentValidator.validate(ContentCatalog(maps = mapOf(1 to withItemWarp)), MorganaClassic.definition)
        assertFalse(bad.isValid)
        assertEquals("missing_reference", bad.errors.single().code)
    }
}
