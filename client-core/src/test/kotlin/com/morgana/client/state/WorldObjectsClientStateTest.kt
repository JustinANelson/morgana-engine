package com.morgana.client.state

import com.morgana.domain.entity.WorldObject
import com.morgana.domain.map.MapDefinition
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The client keeps the world objects of the active map from the snapshot and the changes after it. */
class WorldObjectsClientStateTest {
    private fun wall(id: String, x: Int, hp: Int = 50) = WorldObject(id = id, kind = "wall", mapId = 1, tileX = x, tileY = 2, hp = hp)

    @Test
    fun `the snapshot replaces the objects and changes add, update and remove them`() {
        val state = ClientWorldState()
        val map = MapDefinition(id = 1, name = "Yard", width = 10, height = 10)
        state.applyServerPacket(ServerPacket.MapStateSnapshot(activeMap = map, worldObjects = listOf(wall("o1", 3), wall("o2", 4))))
        assertEquals(setOf("o1", "o2"), state.worldObjectsOnMap.keys)

        state.applyServerPacket(ServerPacket.WorldObjectsUpdated(1, upserted = listOf(wall("o1", 3, hp = 10), wall("o3", 5)), removedIds = listOf("o2")))
        assertEquals(setOf("o1", "o3"), state.worldObjectsOnMap.keys)
        assertEquals(10, state.worldObjectsOnMap.getValue("o1").hp)

        state.applyServerPacket(ServerPacket.WorldObjectsUpdated(9, upserted = listOf(wall("zz", 1))))
        assertTrue("zz" !in state.worldObjectsOnMap, "a change on another map is not ours")

        state.applyServerPacket(ServerPacket.MapStateSnapshot(activeMap = map, worldObjects = emptyList()))
        assertTrue(state.worldObjectsOnMap.isEmpty(), "a new snapshot starts from what the server says")
    }
}
