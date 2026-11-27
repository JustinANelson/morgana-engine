package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.entity.WorldObject
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.WorldObjectKind
import com.morgana.domain.game.WorldObjectsDefinition
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.replay.InputRecorder
import com.morgana.domain.replay.ReplayPlayer
import com.morgana.domain.replay.SimInput
import com.morgana.domain.replay.StateHasher
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.MovementRules
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldObjectChange
import com.morgana.domain.sim.WorldObjectGate
import com.morgana.domain.sim.WorldObjectInteraction
import com.morgana.domain.sim.WorldObjectObserver
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.canNpcStepTo
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.TileCoord
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import com.morgana.domain.systems.WorldObjectResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** World state: things placed on a map that stay there. */
class WorldStateTest {
    private val wall = WorldObjectKind("wall", "Wall", blocksMovement = true, maxHp = 50)
    private val sign = WorldObjectKind("sign", "Sign", blocksMovement = false, maxHp = 5)
    private val piece = ItemDefinition(id = 20, name = "Wall piece", type = ItemType.CONSUMABLE, stackable = true, maxStack = 99, placesObject = "wall")

    private fun game(maxPerMap: Int = 100) = CompiledGame.compile(
        MorganaClassic.definition.copy(worldObjects = WorldObjectsDefinition(listOf(wall, sign), maxPerMap))
    )

    private fun sim(maxPerMap: Int = 100, modules: ModuleFlags = ModuleFlags()): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(3L), game = game(maxPerMap))
        s.modules = modules
        s.items[20] = piece
        s.registerMap(
            MapDefinition(id = 1, name = "Yard", width = 10, height = 10)
                .withCell(8, 8, MapCell(attribute = TileAttribute.Blocked))
                .withCell(7, 8, MapCell(attribute = TileAttribute.LightSource()))
        )
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p", accountId = "a", name = "Pat", position = WorldPosition.fromTile(1, 2, 2, Direction.RIGHT),
                inventory = Inventory().addItem(piece, 3).updatedInventory
            )
        )
        return s
    }

    private fun WorldSimulation.place(kind: String = "wall", x: Int = 5, y: Int = 5, owner: String? = null) = placeWorldObject(kind, 1, x, y, owner)

    // ---- the definition -------------------------------------------------------------------------------------------------------

    @Test
    fun `kinds are validated with a pointer for each field`() {
        fun pointers(w: WorldObjectsDefinition) = GameDefinitionValidator.validate(MorganaClassic.definition.copy(worldObjects = w)).map { it.pointer }
        assertTrue(GameDefinitionValidator.validate(MorganaClassic.definition.copy(worldObjects = WorldObjectsDefinition(listOf(wall, sign)))).isEmpty())
        assertTrue("/worldObjects/kinds/0/id" in pointers(WorldObjectsDefinition(listOf(WorldObjectKind("Bad Id")))))
        assertTrue("/worldObjects/kinds/1/id" in pointers(WorldObjectsDefinition(listOf(wall, wall))), "duplicate ids")
        assertTrue("/worldObjects/kinds/0/maxHp" in pointers(WorldObjectsDefinition(listOf(WorldObjectKind("w", maxHp = 0)))))
        assertTrue("/worldObjects/maxPerMap" in pointers(WorldObjectsDefinition(listOf(wall), maxPerMap = 0)))
    }

    // ---- placing --------------------------------------------------------------------------------------------------------------

    @Test
    fun `an object is placed with its kind's hit points, indexed by tile, and announced`() {
        val s = sim()
        val events = mutableListOf<WorldSimulationEvent>()
        val result = s.placeWorldObject("wall", 1, 5, 5, ownerCharacterId = "p", ownerGuildId = "g1", data = mapOf("tier" to "1"), out = events)
        val obj = (result as WorldObjectResult.Placed).obj
        assertEquals(WorldObject(obj.id, "wall", 1, 5, 5, "p", "g1", 50, 0L, mapOf("tier" to "1")), obj)
        assertEquals(obj, s.worldObjectAt(1, 5, 5))
        assertEquals(obj, s.worldObject(obj.id))
        assertEquals(listOf(obj), s.worldObjectsOn(1).toList())
        assertEquals(WorldSimulationEvent.WorldObjectsChanged(1, listOf(obj), emptyList()), events.single())
        assertTrue(s.dirtyWorldObjectMaps.contains(1), "marked to be saved")
        assertTrue(s.isTileBlockedByObject(1, 5, 5))
        assertFalse(s.isTileBlockedByObject(1, 6, 5))
    }

    @Test
    fun `placement is refused with a reason a player can read`() {
        val s = sim(maxPerMap = 3)
        fun refusal(r: WorldObjectResult) = (r as WorldObjectResult.Refused).reason
        assertEquals("Nothing is known as 'castle'.", refusal(s.place("castle")))
        assertEquals("That is outside the map.", refusal(s.place(x = 10)))
        assertEquals("You cannot build on that.", refusal(s.place(x = 8, y = 8)), "a blocked tile")
        assertTrue(s.place(x = 7, y = 8) is WorldObjectResult.Placed, "a light source is no obstacle")
        assertTrue(s.place() is WorldObjectResult.Placed)
        assertEquals("Something is already built there.", refusal(s.place()))
        assertEquals("Someone is standing there.", refusal(s.place(x = 2, y = 2)), "a blocking object cannot go under a player")
        assertTrue(s.place("sign", 2, 2) is WorldObjectResult.Placed, "a sign does not block, so it may go under a player")
        assertEquals("This place cannot hold anything more.", refusal(s.place(x = 4, y = 4)), "the map is full at 3")
        assertEquals("There is no such place.", refusal(s.placeWorldObject("wall", 99, 1, 1)))
    }

    @Test
    fun `a game with no world objects, or the system switched off, places nothing`() {
        val plain = WorldSimulation(SeededRandomSource(1L), game = CompiledGame.compile(MorganaClassic.definition))
        plain.registerMap(MapDefinition(id = 1, name = "Yard", width = 10, height = 10))
        assertEquals("Nothing can be built here.", (plain.placeWorldObject("wall", 1, 1, 1) as WorldObjectResult.Refused).reason)
        val off = sim(modules = ModuleFlags(disabled = setOf(OptionalModule.WORLD_STATE)))
        assertEquals("Building is switched off.", (off.place() as WorldObjectResult.Refused).reason)
        assertTrue(off.worldObjects.isEmpty())
    }

    @Test
    fun `a gate can refuse a placement and an observer hears every change`() {
        val s = sim()
        s.install(object : WorldObjectGate {
            override fun refusal(sim: WorldSimulation, kind: WorldObjectKind, mapId: Int, tileX: Int, tileY: Int, ownerCharacterId: String?) =
                if (tileX < 3) "Too close to the gate." else null
        })
        val heard = mutableListOf<Triple<String, WorldObjectChange, String>>()
        s.install(object : WorldObjectObserver {
            override fun onWorldObject(sim: WorldSimulation, obj: WorldObject, change: WorldObjectChange, reason: String, out: MutableList<WorldSimulationEvent>) {
                heard += Triple(obj.id, change, reason)
            }
        })
        assertEquals("Too close to the gate.", (s.place(x = 1) as WorldObjectResult.Refused).reason)
        val id = (s.place(x = 5) as WorldObjectResult.Placed).obj.id
        s.damageWorldObject(id, 10)
        s.setWorldObjectData(id, mapOf("paid" to "yes"))
        s.removeWorldObject(id, "decayed")
        assertEquals(
            listOf(Triple(id, WorldObjectChange.PLACED, "placed"), Triple(id, WorldObjectChange.CHANGED, "damaged"), Triple(id, WorldObjectChange.CHANGED, "data"), Triple(id, WorldObjectChange.REMOVED, "decayed")),
            heard
        )
    }

    // ---- damage, data, removal ------------------------------------------------------------------------------------------------

    @Test
    fun `an object is hurt until it is destroyed, and what a system keeps on it is replaced`() {
        val s = sim()
        val id = (s.place() as WorldObjectResult.Placed).obj.id
        assertTrue(s.damageWorldObject(id, 20))
        assertEquals(30, s.worldObject(id)!!.hp)
        assertFalse(s.damageWorldObject(id, 0), "no damage, no change")
        s.setWorldObjectData(id, mapOf("b" to "2", "a" to "1"))
        assertEquals(listOf("a", "b"), s.worldObject(id)!!.data.keys.toList(), "data is kept in key order")

        val events = mutableListOf<WorldSimulationEvent>()
        assertTrue(s.damageWorldObject(id, 100, events))
        assertNull(s.worldObject(id), "destroyed at 0 hit points")
        assertNull(s.worldObjectAt(1, 5, 5))
        assertEquals(WorldSimulationEvent.WorldObjectsChanged(1, emptyList(), listOf(id)), events.last())
        assertFalse(s.removeWorldObject(id), "already gone")
        assertTrue(s.worldObjects.isEmpty(), "an empty map leaves no trace")
    }

    // ---- walking --------------------------------------------------------------------------------------------------------------

    @Test
    fun `a blocking object stops a player walking onto it, and a sign does not`() {
        val s = sim()
        s.place(x = 3, y = 2)
        s.movePlayer("p", Direction.RIGHT)
        assertEquals(2, s.players.getValue("p").position.tileX, "the wall holds")
        s.removeWorldObject(s.worldObjectAt(1, 3, 2)!!.id)
        repeat(MovementRules.GRID_MIN_STEP_TICKS + 1) { s.stepTick() }
        s.movePlayer("p", Direction.RIGHT)
        assertEquals(3, s.players.getValue("p").position.tileX, "and once it is gone the way is open")

        val t = sim()
        t.place("sign", 3, 2)
        t.movePlayer("p", Direction.RIGHT)
        assertEquals(3, t.players.getValue("p").position.tileX, "a sign is walked over")
    }

    @Test
    fun `monsters walk around blocking objects too`() {
        val s = sim()
        val map = s.maps.getValue(1)
        assertTrue(s.canNpcStepTo(map, TileCoord(4, 5), TileCoord(5, 5), Direction.RIGHT))
        s.place(x = 5, y = 5)
        assertFalse(s.canNpcStepTo(map, TileCoord(4, 5), TileCoord(5, 5), Direction.RIGHT))
    }

    // ---- placing from an item -------------------------------------------------------------------------------------------------

    @Test
    fun `using an item places its object on the tile in front and uses the item up`() {
        val s = sim()
        s.useInventorySlot("p", 0)
        val built = s.worldObjectAt(1, 3, 2)!!
        assertEquals("wall", built.kind)
        assertEquals("p", built.ownerCharacterId)
        assertEquals(2, s.players.getValue("p").inventory.totalCountOf(20))

        s.useInventorySlot("p", 0) // the tile in front is now taken
        assertEquals(2, s.players.getValue("p").inventory.totalCountOf(20), "a refused placement keeps the item")
        assertEquals(1, s.worldObjectsOn(1).size)
    }

    // ---- /object --------------------------------------------------------------------------------------------------------------

    @Test
    fun `slash object looks at and takes down what you built, and nothing of anyone else's`() {
        val s = sim()
        s.place(x = 3, y = 2, owner = "p")
        fun said(line: String) = s.executeAdminSlashCommand("p", line).filterIsInstance<WorldSimulationEvent.ChatEmitted>().map { it.message }
        assertTrue(said("/object").single().startsWith("Wall: 50/50 HP"), "looking")
        s.removeWorldObject(s.worldObjectAt(1, 3, 2)!!.id)
        s.place(x = 3, y = 2, owner = "someone-else")
        assertEquals(listOf("That is not yours to take down."), said("/object remove"))
        assertTrue(s.worldObjectAt(1, 3, 2) != null)
        s.removeWorldObject(s.worldObjectAt(1, 3, 2)!!.id)
        s.place(x = 3, y = 2, owner = "p")
        assertEquals(listOf("Taken down."), said("/object remove"))
        assertNull(s.worldObjectAt(1, 3, 2))
    }

    // ---- state, saving and replay ---------------------------------------------------------------------------------------------

    @Test
    fun `objects are part of the hashed state, and loading keeps new ids ahead of saved ones`() {
        val a = sim()
        val b = sim()
        assertEquals(StateHasher.hash(a), StateHasher.hash(b))
        a.place()
        assertNotEquals(StateHasher.hash(a), StateHasher.hash(b), "the hash sees a placed object")
        assertFalse(StateHasher.canonicalState(b).contains("worldObjects"), "a world with none hashes as before")

        val saved = a.worldObjectsOn(1).toList()
        val restarted = sim()
        restarted.loadWorldObjects(saved)
        assertEquals(StateHasher.hash(a), StateHasher.hash(restarted), "loading puts the same state back")
        assertTrue(restarted.dirtyWorldObjectMaps.isEmpty(), "loading is not a change to save")
        val next = (restarted.place(x = 6, y = 6) as WorldObjectResult.Placed).obj
        assertNotEquals(saved.single().id, next.id, "a new object never reuses a saved id")
    }

    @Test
    fun `a recording that starts with objects already in the world replays exactly`() {
        fun fresh() = sim()
        val live = fresh()
        live.place(x = 6, y = 6, owner = "p") // already there when recording starts, as after a restart
        val recorder = InputRecorder(live, seed = 3L)
        recorder.apply(SimInput.PlaceWorldObject("wall", 1, 4, 4, "p"))
        repeat(25) { recorder.apply(SimInput.Tick(50)) }
        recorder.apply(SimInput.PlaceWorldObject("sign", 1, 5, 4, "p", data = mapOf("text" to "hello")))
        recorder.apply(SimInput.RemoveWorldObject(live.worldObjectAt(1, 6, 6)!!.id, "decayed"))
        repeat(25) { recorder.apply(SimInput.Tick(50)) }
        val log = recorder.finish()
        assertEquals(1, log.startingWorldObjects.size, "the objects as recording began")

        val result = ReplayPlayer.replay(fresh(), log)
        assertTrue(result.ok, "the replay agrees with the live run: $result")
        assertEquals(StateHasher.hash(live), result.finalHash)
    }

    @Test
    fun `facing a placed object, a system can refuse the use or carry it out, and an unruled object falls through`() {
        val s = sim()
        val placed = s.placeWorldObject("wall", 1, 3, 2) as WorldObjectResult.Placed // Pat at (2,2) faces right
        val calls = mutableListOf<String>()
        s.install(object : WorldObjectInteraction {
            override fun interactionRefusal(sim: WorldSimulation, player: CharacterState, obj: WorldObject, kind: WorldObjectKind): String? =
                if (obj.data["damaged"] == "yes") "It is too damaged to use." else null

            override fun interact(sim: WorldSimulation, player: CharacterState, obj: WorldObject, kind: WorldObjectKind, out: MutableList<WorldSimulationEvent>): Boolean {
                calls += "${player.name}:${obj.id}"
                return true
            }
        })
        s.playerInteractFacing("p")
        assertEquals(listOf("Pat:${placed.obj.id}"), calls)

        s.setWorldObjectData(placed.obj.id, mapOf("damaged" to "yes"))
        val events = s.playerInteractFacing("p")
        assertEquals(1, calls.size, "a refusal ends the use before any handler runs")
        assertTrue(events.any { it is WorldSimulationEvent.ChatEmitted && it.message == "It is too damaged to use." })
    }

    @Test
    fun `an object with no interaction rule does nothing, and switching worldState off disables the seam`() {
        val s = sim()
        s.placeWorldObject("sign", 1, 3, 2)
        assertTrue(s.playerInteractFacing("p").isEmpty(), "nothing handles it, so the facing interaction carries on as before")
        var used = 0
        s.install(object : WorldObjectInteraction {
            override fun interact(sim: WorldSimulation, player: CharacterState, obj: WorldObject, kind: WorldObjectKind, out: MutableList<WorldSimulationEvent>): Boolean { used++; return true }
        })
        val off = sim(modules = ModuleFlags(disabled = setOf(OptionalModule.WORLD_STATE)))
        off.install(object : WorldObjectInteraction {
            override fun interact(sim: WorldSimulation, player: CharacterState, obj: WorldObject, kind: WorldObjectKind, out: MutableList<WorldSimulationEvent>): Boolean { used += 100; return true }
        })
        off.playerInteractFacing("p")
        assertEquals(0, used)
        s.playerInteractFacing("p")
        assertEquals(1, used)
    }

    @Test
    fun `healing a damaged object restores hp up to its kind maxHp and notifies observers`() {
        val s = sim()
        val placed = (s.placeWorldObject("wall", 1, 3, 2) as WorldObjectResult.Placed).obj
        val maxHp = placed.hp
        assertTrue(s.damageWorldObject(placed.id, maxHp - 10))
        assertEquals(10, s.worldObject(placed.id)!!.hp)

        val events = mutableListOf<WorldSimulationEvent>()
        assertTrue(s.healWorldObject(placed.id, 15, events))
        assertEquals(25.coerceAtMost(maxHp), s.worldObject(placed.id)!!.hp)
        assertTrue(events.any { it is WorldSimulationEvent.WorldObjectsChanged })

        assertTrue(s.healWorldObject(placed.id, maxHp * 5, events))
        assertEquals(maxHp, s.worldObject(placed.id)!!.hp)
        assertFalse(s.healWorldObject(placed.id, 0))
        assertFalse(s.healWorldObject("missing-id", 10))
    }
}
