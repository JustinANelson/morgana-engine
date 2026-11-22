package com.morgana.domain

import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.EventPage
import com.morgana.domain.event.EventTriggerType
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.replay.StateHasher
import com.morgana.domain.script.ScriptEffect
import com.morgana.domain.sim.InstanceSettings
import com.morgana.domain.sim.MapInstances
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.contentMapId
import com.morgana.domain.sim.contentMaps
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.validation.ContentValidator
import com.morgana.domain.validation.toContentCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InstanceSystemTest {

    private val hub = 1
    private val dungeon = 2

    private fun world(modules: ModuleFlags = ModuleFlags.ALL_ENABLED, settings: InstanceSettings = InstanceSettings(emptyTicks = 10)): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(7L), game = MorganaClassic.COMPILED)
        sim.modules = modules
        sim.instanceSettings = settings
        sim.npcs[1] = NpcDefinition(id = 1, name = "Rat")
        // The hub has a warp tile at (4, 5) leading into the dungeon.
        sim.registerMap(
            MapDefinition(id = hub, name = "Town", width = 12, height = 12)
                .withCell(4, 5, MapCell(attribute = TileAttribute.Warp(dungeon, 2, 2)))
        )
        sim.registerMap(
            MapDefinition(
                id = dungeon, name = "Crypt", width = 10, height = 10, instanced = true,
                npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 1, spawnX = 6, spawnY = 6))
            )
        )
        return sim
    }

    private fun player(id: String, partyId: String? = null, x: Int = 3) = CharacterState(
        id = id, accountId = "a-$id", name = id,
        position = WorldPosition.fromTile(hub, x, 5, Direction.RIGHT),
        partyId = partyId
    )

    private fun WorldSimulation.warp(id: String, mapId: Int, x: Int = 2, y: Int = 2) =
        applyScriptEffect(ScriptEffect.WarpPlayer(id, mapId, x, y))

    private fun WorldSimulation.mapOf(id: String) = players.getValue(id).position.mapId

    @Test
    fun `entering an instanced map puts the player in a private copy with its own monsters`() {
        val sim = world()
        sim.spawnOrUpdatePlayer(player("ann"))
        sim.warp("ann", dungeon)

        val inside = sim.mapOf("ann")
        assertTrue(MapInstances.isInstanceId(inside))
        assertEquals(dungeon, sim.contentMapId(inside))
        assertEquals("Crypt", sim.maps.getValue(inside).name)
        assertEquals(1, sim.mapNpcs.getValue(inside).size, "the copy spawns the template's monsters")
        assertEquals(WorldPosition.fromTile(hub, 3, 5, Direction.RIGHT), sim.players.getValue("ann").instanceReturn)
        assertTrue(sim.players.values.none { it.position.mapId == dungeon })
    }

    @Test
    fun `a warp tile into an instanced map also uses the copy`() {
        val sim = world()
        sim.spawnOrUpdatePlayer(player("ann"))
        repeat(20) { sim.stepTick() }
        sim.movePlayer("ann", Direction.RIGHT, MovementMode.GRID_LOCKED)
        assertTrue(MapInstances.isInstanceId(sim.mapOf("ann")))
    }

    @Test
    fun `a party shares one instance, solo players and other parties get their own`() {
        val sim = world()
        listOf(player("ann", "p1"), player("ben", "p1"), player("cat"), player("dan", "p2")).forEach { sim.spawnOrUpdatePlayer(it) }
        listOf("ann", "ben", "cat", "dan").forEach { sim.warp(it, dungeon) }

        assertEquals(sim.mapOf("ann"), sim.mapOf("ben"))
        assertNotEquals(sim.mapOf("ann"), sim.mapOf("cat"))
        assertNotEquals(sim.mapOf("ann"), sim.mapOf("dan"))
        assertNotEquals(sim.mapOf("cat"), sim.mapOf("dan"))
        assertEquals(3, sim.instances.size)
        assertEquals(setOf("ann", "ben"), sim.instances.getValue(sim.mapOf("ann")).memberIds)
    }

    @Test
    fun `instances are not content`() {
        val sim = world()
        sim.spawnOrUpdatePlayer(player("ann"))
        sim.warp("ann", dungeon)
        assertEquals(setOf(hub, dungeon), sim.contentMaps().keys)
        assertEquals(setOf(hub, dungeon), sim.toContentCatalog().maps.keys)

        val catalog = sim.toContentCatalog().let { it.copy(maps = it.maps + (MapInstances.FIRST_ID to MapDefinition(id = MapInstances.FIRST_ID, name = "Sneaky"))) }
        val report = ContentValidator.validate(catalog, sim.game.definition)
        assertTrue(report.errors.any { it.code == "reserved_id" }, "content may not claim an instance id: ${report.errors}")
    }

    @Test
    fun `events and visit objectives on the template work inside the copy`() {
        val sim = world()
        sim.events[1] = EventDefinition(
            id = 1, name = "Welcome", mapId = dungeon,
            pages = listOf(EventPage(trigger = EventTriggerType.AUTORUN_ON_ENTER, commands = listOf(EventCommand.SetSwitch("crypt_seen", true))))
        )
        sim.spawnOrUpdatePlayer(player("ann"))
        sim.warp("ann", dungeon)
        assertEquals(true, sim.players.getValue("ann").switches["crypt_seen"])
    }

    @Test
    fun `an empty instance closes after the delay and someone coming back keeps it open`() {
        val sim = world(settings = InstanceSettings(emptyTicks = 10))
        sim.spawnOrUpdatePlayer(player("ann"))
        sim.warp("ann", dungeon)
        val id = sim.mapOf("ann")

        sim.warp("ann", hub, 3, 5)
        assertNull(sim.players.getValue("ann").instanceReturn, "leaving clears the return point")
        repeat(5) { sim.stepTick() }
        sim.warp("ann", dungeon)
        assertEquals(id, sim.mapOf("ann"), "came back before it closed")

        sim.warp("ann", hub, 3, 5)
        val closed = (1..12).flatMap { sim.stepTick() }.filterIsInstance<WorldSimulationEvent.InstanceClosed>()
        assertEquals(listOf(WorldSimulationEvent.InstanceClosed(id, dungeon)), closed)
        assertFalse(id in sim.maps)
        assertFalse(id in sim.mapNpcs)
        assertTrue(sim.instances.isEmpty())

        sim.warp("ann", dungeon)
        assertTrue(sim.mapOf("ann") > id, "ids are never reused")
    }

    @Test
    fun `logging in inside an open instance rejoins it, a closed or foreign one sends the player back`() {
        val sim = world(settings = InstanceSettings(emptyTicks = 10))
        sim.spawnOrUpdatePlayer(player("ann"))
        sim.warp("ann", dungeon)
        val id = sim.mapOf("ann")
        val saved = sim.players.getValue("ann")

        sim.removePlayer("ann")
        sim.spawnOrUpdatePlayer(saved)
        assertEquals(id, sim.mapOf("ann"), "rejoined while it was still open")

        val stranger = saved.copy(id = "eve", name = "eve", instanceReturn = WorldPosition.fromTile(hub, 7, 7, Direction.DOWN))
        sim.spawnOrUpdatePlayer(stranger)
        assertEquals(WorldPosition.fromTile(hub, 7, 7, Direction.DOWN), sim.players.getValue("eve").position, "not a member: sent to their return point")

        sim.removePlayer("ann")
        sim.removePlayer("eve")
        repeat(25) { sim.stepTick() }
        sim.spawnOrUpdatePlayer(saved)
        assertEquals(WorldPosition.fromTile(hub, 3, 5, Direction.RIGHT), sim.players.getValue("ann").position, "closed: back where they entered")
        assertNull(sim.players.getValue("ann").instanceReturn)
    }

    @Test
    fun `the instance limit turns players away with a message`() {
        val sim = world(settings = InstanceSettings(maxInstances = 1))
        sim.spawnOrUpdatePlayer(player("ann"))
        sim.spawnOrUpdatePlayer(player("ben"))
        sim.warp("ann", dungeon)
        val events = sim.warp("ben", dungeon)
        assertEquals(hub, sim.mapOf("ben"))
        assertTrue(events.any { it is WorldSimulationEvent.ChatEmitted && "full" in it.message })
    }

    @Test
    fun `with the module off instanced maps are shared as before`() {
        val sim = world(ModuleFlags(setOf(OptionalModule.INSTANCING)))
        sim.spawnOrUpdatePlayer(player("ann"))
        sim.spawnOrUpdatePlayer(player("ben"))
        sim.warp("ann", dungeon)
        sim.warp("ben", dungeon)
        assertEquals(dungeon, sim.mapOf("ann"))
        assertEquals(dungeon, sim.mapOf("ben"))
        assertTrue(sim.instances.isEmpty())
    }

    @Test
    fun `instance runs are deterministic`() {
        fun run(): String {
            val sim = world()
            listOf(player("ann", "p1"), player("ben", "p1"), player("cat")).forEach { sim.spawnOrUpdatePlayer(it) }
            listOf("ann", "ben", "cat").forEach { sim.warp(it, dungeon) }
            repeat(30) { sim.stepTick() }
            sim.warp("cat", hub, 3, 5)
            repeat(30) { sim.stepTick() }
            return StateHasher.hash(sim)
        }
        assertEquals(run(), run())
    }
}
