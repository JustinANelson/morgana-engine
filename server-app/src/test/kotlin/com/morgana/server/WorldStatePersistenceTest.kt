package com.morgana.server

import com.morgana.domain.entity.WorldObject
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.WorldObjectKind
import com.morgana.domain.game.WorldObjectsDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.systems.WorldObjectResult
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.WorldObjectRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** Things placed on maps survive a restart: one file per map, written off the tick thread and on shutdown. */
class WorldStatePersistenceTest {
    private fun obj(id: String, map: Int, x: Int, y: Int) = WorldObject(id, "wall", map, x, y, "p", "g", 40, 123L, mapOf("tier" to "2"))

    @Test
    fun `objects are saved one file per map and read back exactly`(@TempDir dir: Path) {
        val repo = WorldObjectRepository(dir.resolve("world-objects"))
        assertEquals(emptyList<WorldObject>(), repo.loadAll(), "nothing saved yet")
        val a = listOf(obj("obj-1", 1, 3, 4), obj("obj-2", 1, 5, 6))
        val b = listOf(obj("obj-3", 7, 0, 0))
        repo.save(1, a)
        repo.save(7, b)
        assertTrue(Files.exists(dir.resolve("world-objects/map_1.json")) && Files.exists(dir.resolve("world-objects/map_7.json")))
        assertEquals(a + b, repo.loadAll(), "map by map, in id order, every field")

        repo.save(1, emptyList())
        assertFalse(Files.exists(dir.resolve("world-objects/map_1.json")), "a map with nothing left has no file")
        assertEquals(b, repo.loadAll())
    }

    @Test
    fun `a file that cannot be read is reported and skipped, and left as it was`(@TempDir dir: Path) {
        val repo = WorldObjectRepository(dir.resolve("world-objects"))
        repo.save(1, listOf(obj("obj-1", 1, 1, 1)))
        Files.writeString(dir.resolve("world-objects/map_2.json"), "{ this is not json")
        val problems = mutableListOf<String>()
        assertEquals(listOf(obj("obj-1", 1, 1, 1)), repo.loadAll { problems += it }, "the good map still loads")
        assertEquals(1, problems.size)
        assertTrue(problems.single().contains("map_2.json"), problems.single())
        assertEquals("{ this is not json", Files.readString(dir.resolve("world-objects/map_2.json")), "the bad file is not touched")
    }

    private val game = CompiledGame.compile(
        MorganaClassic.definition.copy(worldObjects = WorldObjectsDefinition(listOf(WorldObjectKind("wall", "Wall", maxHp = 40))))
    )

    private fun boot(dir: Path): MorganaWorldCoordinator {
        val sim = WorldSimulation(game = game)
        sim.registerMap(MapDefinition(id = 1, name = "Yard", width = 20, height = 20))
        return MorganaWorldCoordinator(dataStore = MorganaDataStore(rootDir = dir), simulation = sim)
    }

    @Test
    fun `the server saves changed maps on its autosave tick and everything on shutdown, then restores them`(@TempDir dir: Path) {
        val first = boot(dir)
        val placed = (first.simulation.placeWorldObject("wall", 1, 4, 4, "p") as WorldObjectResult.Placed).obj
        assertTrue(first.simulation.dirtyWorldObjectMaps.contains(1))
        repeat(200) { first.stepServerTick(50) } // the autosave tick
        first.flushPersistence()
        assertTrue(Files.exists(dir.resolve("world-objects/map_1.json")), "written by the autosave, not only at shutdown")
        assertTrue(first.simulation.dirtyWorldObjectMaps.isEmpty())

        // A change after the autosave is still saved when the server shuts down.
        val second = (first.simulation.placeWorldObject("wall", 1, 5, 4, "p") as WorldObjectResult.Placed).obj
        first.close()

        val restarted = boot(dir)
        assertEquals(setOf(placed.id, second.id), restarted.simulation.worldObjectsOn(1).map { it.id }.toSet(), "both came back")
        assertEquals(placed, restarted.simulation.worldObjectAt(1, 4, 4))
        assertTrue(restarted.simulation.dirtyWorldObjectMaps.isEmpty(), "loading is not a change to save")
        val next = (restarted.simulation.placeWorldObject("wall", 1, 6, 4, "p") as WorldObjectResult.Placed).obj
        assertNotEquals(placed.id, next.id)
        assertNotEquals(second.id, next.id)

        // Taking everything down removes the file at the next save.
        for (o in restarted.simulation.worldObjectsOn(1).toList()) restarted.simulation.removeWorldObject(o.id)
        restarted.close()
        assertFalse(Files.exists(dir.resolve("world-objects/map_1.json")))
    }
}
