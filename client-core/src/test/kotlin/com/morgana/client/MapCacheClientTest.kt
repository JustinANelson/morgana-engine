package com.morgana.client

import com.morgana.client.state.ClientWorldState
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNeighborLinks
import com.morgana.protocol.MapRef
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The client half of the send-once map cache: maps arrive once and later snapshots are rebuilt from references. */
class MapCacheClientTest {

    private val home = MapDefinition(id = 1, name = "Home", revision = 3, neighbors = MapNeighborLinks(eastMapId = 2))
    private val east = MapDefinition(id = 2, name = "East", revision = 7, neighbors = MapNeighborLinks(westMapId = 1))

    private fun full(state: ClientWorldState) {
        state.applyServerPacket(
            ServerPacket.MapStateSnapshot(
                activeMap = home,
                neighborMaps = mapOf(2 to east),
                mapRefs = listOf(MapRef(1, 3), MapRef(2, 7))
            )
        )
    }

    private fun referenceOnly(vararg refs: MapRef) = ServerPacket.MapStateSnapshot(activeMap = null, mapRefs = refs.toList())

    @Test
    fun `discarding editor changes shows the server's version of the open map again`() {
        val state = ClientWorldState()
        full(state)
        state.editor.load(state.activeMap)
        state.editor.updateProperties { it.copy(name = "Edited") }
        assertEquals("Edited", state.activeMap!!.name)
        assertTrue(state.editor.isDirty)

        state.discardEditorChanges()

        assertSame(home, state.activeMap)
        assertSame(home, state.editor.draft)
        assertFalse(state.editor.isDirty)
        assertFalse(state.editor.canUndo)
        assertFalse(state.takeMapResyncRequest())
    }

    @Test
    fun `discarding with no editor map does nothing`() {
        val state = ClientWorldState()
        state.discardEditorChanges()
        assertNull(state.activeMap)
        assertFalse(state.takeMapResyncRequest())
    }

    @Test
    fun `a snapshot that only references held maps is rebuilt from the cache`() {
        val state = ClientWorldState()
        full(state)

        state.applyServerPacket(referenceOnly(MapRef(2, 7), MapRef(1, 3)))

        assertSame(east, state.activeMap, "the destination comes from the cache, not the packet")
        assertEquals(mapOf(1 to home), state.neighborMaps)
        assertFalse(state.takeMapResyncRequest())
    }

    @Test
    fun `an unknown map is not guessed at - the snapshot is ignored and a resync is requested once`() {
        val state = ClientWorldState()
        full(state)

        state.applyServerPacket(referenceOnly(MapRef(9, 1)))

        assertSame(home, state.activeMap, "the previous map stays on screen")
        assertTrue(state.takeMapResyncRequest())
        assertFalse(state.takeMapResyncRequest(), "the request is consumed")
    }

    @Test
    fun `a reference to a different revision than the cached one counts as a miss`() {
        val state = ClientWorldState()
        full(state)

        state.applyServerPacket(referenceOnly(MapRef(1, 4)))

        assertTrue(state.takeMapResyncRequest())
        assertEquals(3, state.activeMap?.revision)
    }

    @Test
    fun `a missing neighbour still shows the map but asks for a resync`() {
        val state = ClientWorldState()
        full(state)

        state.applyServerPacket(referenceOnly(MapRef(1, 3), MapRef(5, 1)))

        assertSame(home, state.activeMap)
        assertTrue(state.neighborMaps.isEmpty())
        assertTrue(state.takeMapResyncRequest())
    }

    @Test
    fun `a map update refreshes the cache even for a map that is not on screen`() {
        val state = ClientWorldState()
        full(state)
        val edited = east.copy(name = "East (edited)", revision = 8)

        state.applyServerPacket(ServerPacket.MapDefinitionUpdated(edited))
        state.applyServerPacket(referenceOnly(MapRef(2, 8)))

        assertSame(edited, state.activeMap)
        assertFalse(state.takeMapResyncRequest())
    }

    @Test
    fun `a map update for a map never seen is cached for later references`() {
        val state = ClientWorldState()
        val far = MapDefinition(id = 40, name = "Far", revision = 2)

        state.applyServerPacket(ServerPacket.MapDefinitionUpdated(far))
        state.applyServerPacket(referenceOnly(MapRef(40, 2)))

        assertSame(far, state.activeMap)
    }

    @Test
    fun `a new login starts with an empty cache because the server's record is per connection`() {
        val state = ClientWorldState()
        full(state)

        state.applyServerPacket(ServerPacket.AuthResult(success = true, message = "welcome back"))
        state.applyServerPacket(referenceOnly(MapRef(1, 3)))

        assertTrue(state.takeMapResyncRequest(), "the cache was cleared at login")
    }

    @Test
    fun `logging out forgets the cache`() {
        val state = ClientWorldState()
        full(state)
        state.resetSession()

        assertNull(state.activeMap)
        state.applyServerPacket(referenceOnly(MapRef(1, 3)))
        assertTrue(state.takeMapResyncRequest())
    }

    @Test
    fun `snapshots from servers without the cache work exactly as before`() {
        val state = ClientWorldState()
        state.applyServerPacket(ServerPacket.MapStateSnapshot(activeMap = home, neighborMaps = mapOf(2 to east)))

        assertSame(home, state.activeMap)
        assertEquals(mapOf(2 to east), state.neighborMaps)
        assertFalse(state.takeMapResyncRequest())
    }
}
