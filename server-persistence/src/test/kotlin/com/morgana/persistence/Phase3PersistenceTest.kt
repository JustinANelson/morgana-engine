package com.morgana.persistence

import com.morgana.domain.access.AccessLevel
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.entity.AccountDocument
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.sim.WorldSimulation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class Phase3PersistenceTest {

    @Test
    fun `the blank seed populates JSON document collections and loads into WorldSimulation`(@TempDir tempDir: Path) {
        val store = MorganaDataStore(tempDir)
        assertTrue(store.world.isEmpty())

        GameScaffolder.seedIfEmpty(store.world)
        assertFalse(store.world.isEmpty())
        assertEquals(1, store.world.maps.count())
        assertEquals(1, store.world.classes.count())
        assertEquals(1, store.world.items.count())
        assertEquals(1, store.world.npcs.count())
        GameScaffolder.seedIfEmpty(store.world)
        assertEquals(1, store.world.items.count(), "seeding again changes nothing")

        val sim = WorldSimulation(game = MorganaClassic.COMPILED)
        store.world.loadIntoSimulation(sim)

        assertEquals("Blank Game Town Square", sim.maps[1]?.name)
        assertEquals(1, sim.mapNpcs[1]?.size)

        // Verify Account & Character JSON persistence round-trip
        val account = AccountDocument(
            id = "acc-1",
            username = "AdminUser",
            passwordHash = AccountRepository.hashPassword("secret"),
            accessLevel = AccessLevel.OWNER,
            characterIds = listOf("char-1")
        )
        store.accounts.save(account)
        val loadedAcc = store.accounts.findByUsername("adminuser")
        assertNotNull(loadedAcc)
        assertEquals(AccessLevel.OWNER, loadedAcc!!.accessLevel)

        val charState = CharacterState(
            id = "char-1",
            accountId = "acc-1",
            name = "AdminHero",
            accessLevel = AccessLevel.OWNER
        )
        store.characters.save(charState)
        val loadedChar = store.characters.findByName("adminhero")
        assertNotNull(loadedChar)
        assertEquals("char-1", loadedChar!!.id)
    }
}

