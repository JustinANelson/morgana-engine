package com.morgana.server

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.ExplorationDocument
import com.morgana.domain.explore.ExploredMap
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerState
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class AsyncPersistenceWriterTest {

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        override fun send(packet: ServerPacket) {}
        override fun close(reason: String) {}
    }

    @Test
    fun `newer synchronous save wins over an older queued autosave`(@TempDir dir: Path) {
        MorganaDataStore(dir).use { store ->
            AsyncPersistenceWriter(store).use { writer ->
                val older = CharacterState(id = "char-1", accountId = "acc-1", name = "Hero", level = 1)
                val newer = older.copy(level = 42)

                writer.enqueueAutosave(listOf(older), ServerState(worldEpochTicks = 200L))
                writer.saveCharacterNow(newer)
                writer.flush()

                assertEquals(42, store.characters.findById("char-1")?.level)
            }
        }
    }

    @Test
    fun `invalidated character is not resurrected by an older queued autosave`(@TempDir dir: Path) {
        MorganaDataStore(dir).use { store ->
            AsyncPersistenceWriter(store).use { writer ->
                val ch = CharacterState(id = "char-del", accountId = "acc-1", name = "Ghost", level = 5)
                writer.enqueueAutosave(listOf(ch), ServerState())
                writer.invalidateCharacter(ch.id)
                writer.flush()

                assertNull(store.characters.findById(ch.id))
            }
        }
    }

    @Test
    fun `newer synchronous server state and exploration writes win over older queued writes`(@TempDir dir: Path) {
        MorganaDataStore(dir).use { store ->
            AsyncPersistenceWriter(store).use { writer ->
                writer.enqueueAutosave(emptyList(), ServerState(worldEpochTicks = 200L))
                writer.saveServerStateNow(ServerState(worldEpochTicks = 999L))

                val oldExp = ExplorationDocument(id = "char-1", maps = mapOf(1 to ExploredMap(listOf(1L))))
                val newExp = ExplorationDocument(id = "char-1", maps = mapOf(1 to ExploredMap(listOf(0xffL)), 2 to ExploredMap(listOf(0xaaL))))
                writer.enqueueExploration(oldExp)
                writer.saveExplorationNow(newExp)
                writer.flush()

                assertEquals(999L, store.loadServerState().worldEpochTicks)
                assertEquals(newExp, store.social.exploration.findById("char-1"))
            }
        }
    }

    @Test
    fun `coordinator autosave at tick 200 persists characters and world epoch off-lock`(@TempDir dir: Path) {
        val store = MorganaDataStore(dir)
        val coordinator = MorganaWorldCoordinator(store, ownerPolicy = OwnerPolicy(dir) { null })
        val session = FakeSession("s1")
        coordinator.onSessionConnected(session)
        coordinator.handleClientPacket(
            session,
            ClientPacket.Authenticate("AutosaveHero", "pw", registerIfNew = true, requestedClassId = 1, protocolVersion = PROTOCOL_VERSION)
        )
        val charId = session.characterId!!
        val leveled = coordinator.simulation.players.getValue(charId).copy(level = 7)
        coordinator.simulation.players[charId] = leveled

        repeat(200) { coordinator.stepServerTick(50) }
        coordinator.flushPersistence()

        assertEquals(7, store.characters.findById(charId)?.level)
        assertEquals(200L, store.loadServerState().worldEpochTicks)

        coordinator.close()
        store.close()
    }
}
