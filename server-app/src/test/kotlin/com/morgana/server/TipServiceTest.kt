package com.morgana.server

import com.morgana.domain.entity.AccountDocument
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.DurabilityDefinition
import com.morgana.domain.game.GhostDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.VitalRole
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemStack
import com.morgana.domain.onboarding.OnboardingConfig
import com.morgana.domain.onboarding.TipDefinition
import com.morgana.domain.onboarding.TipTrigger
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.stats.VitalState
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ServerPacket
import com.morgana.protocol.TipsAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class TipServiceTest {
    private class Rig(dir: Path, onboarding: OnboardingConfig? = null) {
        val definition = MorganaClassic.definition.copy(
            durability = DurabilityDefinition(),
            ghost = GhostDefinition(),
            onboarding = onboarding
        )
        val store = MorganaDataStore(dir)
        val sim = WorldSimulation(game = CompiledGame.compile(definition))
        val sent = mutableListOf<Pair<String, ServerPacket.Tip>>()
        var connected = true
        val service = TipService(store.accounts, sim) { id, p ->
            if (connected && p is ServerPacket.Tip) { sent += id to p; true } else false
        }
        val lifeId = definition.vitals.first { it.role == VitalRole.LIFE }.id

        init {
            store.accounts.save(AccountDocument(id = "acct", username = "Alpha", passwordHash = "x", characterIds = listOf("c1", "c2")))
        }

        fun hero(id: String = "c1", hp: Int = 100, equipment: EquipmentLoadout = EquipmentLoadout(), points: Int = 0): CharacterState {
            val c = CharacterState(
                id = id, accountId = "acct", name = id, unspentStatPoints = points,
                vitals = mapOf(lifeId to VitalState(hp, 100)), equipment = equipment
            )
            sim.players[id] = c
            return c
        }

        fun tick(n: Int) = repeat(n) { sim.stepTick() }
        fun ids() = sent.map { it.second.id }
    }

    @Test
    fun `the welcome tip comes at login, once per account, even for a second character`(@TempDir dir: Path) {
        val r = Rig(dir)
        r.hero("c1")
        r.service.onLogin("c1")
        assertEquals(listOf("welcome"), r.ids())

        r.service.onLogin("c1")
        assertEquals(listOf("welcome"), r.ids(), "logging in again does not repeat it")

        r.hero("c2")
        r.service.onLogin("c2")
        assertEquals(listOf("welcome"), r.ids(), "a second character on the account does not get it either")
    }

    @Test
    fun `an account signed in with a dev ticket gets no tips and keeps its history`(@TempDir dir: Path) {
        val r = Rig(dir)
        r.hero("c1")
        r.service.quietAccount("acct")
        r.service.onLogin("c1")
        r.service.onDeath("c1")
        assertTrue(r.sent.isEmpty(), "automation is not shown tip windows")
        assertTrue(r.store.accounts.findById("acct")!!.tipsSeen.isEmpty(), "nothing was marked as seen, so a normal login still shows them")
    }

    @Test
    fun `what an account has seen survives a restart`(@TempDir dir: Path) {
        val r = Rig(dir)
        r.hero("c1")
        r.service.onLogin("c1")
        assertEquals(setOf("welcome"), r.store.accounts.findById("acct")!!.tipsSeen)

        val again = TipService(r.store.accounts, r.sim) { _, _ -> true }
        assertEquals(setOf("welcome"), again.seen("c1"), "a fresh service reads it from the account")
    }

    @Test
    fun `ordinary tips wait their turn and urgent ones do not`(@TempDir dir: Path) {
        val r = Rig(dir)
        r.hero("c1")
        r.service.onLogin("c1")
        r.hero("c1", points = 3)
        r.tick(30)
        r.service.onCharacterUpdated("c1")
        assertEquals(listOf("welcome"), r.ids(), "the stat tip is held back right after another tip")

        r.tick(TipService.TIP_GAP_TICKS.toInt())
        r.service.onCharacterUpdated("c1")
        assertEquals(listOf("welcome", "stat_points"), r.ids())

        r.hero("c1", hp = 10)
        r.tick(TipService.URGENT_GAP_TICKS.toInt() + 1)
        r.service.onCharacterUpdated("c1")
        assertEquals("low_health", r.ids().last(), "urgent tips need only a short gap")
    }

    @Test
    fun `wearing gear down and breaking it each get a repair tip`(@TempDir dir: Path) {
        val r = Rig(dir)
        val gear = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(1, 1, durability = 90)))
        r.hero("c1", equipment = gear)
        r.service.onLogin("c1")
        assertTrue("gear_worn" !in r.ids(), "the welcome tip comes first")
        r.tick(TipService.TIP_GAP_TICKS.toInt() + 1)
        r.service.onCharacterUpdated("c1")
        assertEquals(listOf("welcome", "gear_worn"), r.ids())
        assertTrue("repair" in r.sent.last().second.text.lowercase())

        r.hero("c1", equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(1, 1, durability = 0))))
        r.tick(TipService.URGENT_GAP_TICKS.toInt() + 1)
        r.service.onCharacterUpdated("c1")
        assertEquals("gear_broken", r.ids().last())
    }

    @Test
    fun `dying explains ghosts and how to get back, at once`(@TempDir dir: Path) {
        val r = Rig(dir)
        r.hero("c1")
        r.service.onLogin("c1")
        r.service.onDeath("c1")
        val death = r.sent.last().second
        assertEquals("death", death.id)
        assertTrue("ghost" in death.text.lowercase() && "/release" in death.text, death.text)
        r.service.onDeath("c1")
        assertEquals(listOf("welcome", "death"), r.ids(), "only the first death is explained")
    }

    @Test
    fun `a client that cannot show tips loses nothing, they stay unseen`(@TempDir dir: Path) {
        val r = Rig(dir)
        r.hero("c1")
        r.connected = false
        r.service.onLogin("c1")
        assertTrue(r.ids().isEmpty())
        assertEquals(emptySet<String>(), r.service.seen("c1"))
        r.connected = true
        r.service.onLogin("c1")
        assertEquals(listOf("welcome"), r.ids())
    }

    @Test
    fun `tips can be turned off, on and reset`(@TempDir dir: Path) {
        val r = Rig(dir)
        r.hero("c1")
        r.service.control("c1", TipsAction.OFF)
        r.service.onLogin("c1")
        assertTrue(r.ids().isEmpty(), "off means off")
        assertTrue(r.store.accounts.findById("acct")!!.tipsOff)

        r.service.control("c1", TipsAction.ON)
        r.service.onLogin("c1")
        assertEquals(listOf("welcome"), r.ids())

        r.service.control("c1", TipsAction.RESET)
        assertEquals(emptySet<String>(), r.store.accounts.findById("acct")!!.tipsSeen)
        r.service.onLogin("c1")
        assertEquals(listOf("welcome", "welcome"), r.ids(), "reset shows them all again")
    }

    @Test
    fun `a game can switch tips off or write its own`(@TempDir dir: Path) {
        val off = Rig(dir.resolve("off").also { java.nio.file.Files.createDirectories(it) }, OnboardingConfig(enabled = false))
        off.hero("c1")
        off.service.onLogin("c1")
        assertTrue(off.ids().isEmpty())

        val own = Rig(
            dir.resolve("own").also { java.nio.file.Files.createDirectories(it) },
            OnboardingConfig(tips = listOf(TipDefinition("welcome", TipTrigger.WELCOME, "Hello", "Custom welcome.")))
        )
        own.hero("c1")
        own.service.onLogin("c1")
        assertEquals("Custom welcome.", own.sent.single().second.text)
    }
}
