package com.morgana.domain

import com.morgana.domain.content.BossAbility
import com.morgana.domain.content.BossEncounter
import com.morgana.domain.content.BossPhase
import com.morgana.domain.content.BossZoneShape
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.adjustedVariant
import com.morgana.domain.sim.handleNpcKilled
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Boss fights: telegraphed ground attacks, phases with adds and harder hits, and adds that go with the fight. */
class BossEncounterTest {
    private val slam = SpellDefinition(id = 50, name = "Slam", targetType = SpellTargetType.SINGLE_TARGET, basePower = 40, scales = false)
    private val bossDef = NpcDefinition(
        id = 20, name = "Warden", behavior = NpcBehavior.ATTACK_ON_SIGHT, level = 30, aggroRangeTiles = 10, leashRangeTiles = 30,
        vitals = mapOf("hp" to 1000), attributes = mapOf("str" to 5, "def" to 5, "agi" to 5, "vit" to 10), attackDamage = 1, xpReward = 500L,
        encounter = BossEncounter(
            phases = listOf(
                BossPhase(fromLifePercent = 100, abilities = listOf(BossAbility(spellId = 50, shape = BossZoneShape.AROUND_TARGET, radiusTiles = 1, windUpTicks = 20, cooldownTicks = 400, warning = "Slam!"))),
                BossPhase(fromLifePercent = 50, announce = "The Warden roars!", attackPercent = 200, summonNpcIds = listOf(21))
            )
        )
    )
    private val minion = NpcDefinition(id = 21, name = "Shade", behavior = NpcBehavior.ATTACK_ON_SIGHT, level = 5, vitals = mapOf("hp" to 30), xpReward = 5L)

    private fun world(): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(2L), game = MorganaClassic.COMPILED)
        sim.npcs[20] = bossDef
        sim.npcs[21] = minion
        sim.spells[50] = slam
        sim.registerMap(MapDefinition(id = 1, name = "Hall", width = 30, height = 30, npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 20, spawnX = 10, spawnY = 10))))
        return sim
    }

    private fun WorldSimulation.hero(id: String, x: Int, y: Int) =
        spawnOrUpdatePlayer(
            CharacterState(id = id, accountId = "a-$id", name = id, attributes = mapOf("str" to 10, "def" to 10, "agi" to 10, "vit" to 10), position = WorldPosition.fromTile(1, x, y, Direction.DOWN))
                .recalculateVitals(game, items, refill = true)
        )

    private fun WorldSimulation.life(id: String) = players.getValue(id).vitals.getValue("hp").cur
    private fun WorldSimulation.boss() = mapNpcs.getValue(1).first { it.npcId == 20 }
    private fun WorldSimulation.setBossLife(cur: Int) {
        val i = mapNpcs.getValue(1).indexOfFirst { it.npcId == 20 }
        val b = mapNpcs.getValue(1)[i]
        mapNpcs.getValue(1)[i] = b.copy(vitals = b.vitals + ("hp" to b.vitals.getValue("hp").copy(cur = cur)))
    }

    /** Steps until [pick] finds something in a tick's events, up to [limit] ticks; returns it. */
    private fun <T> WorldSimulation.until(limit: Int = 200, pick: (WorldSimulationEvent) -> T?): T? {
        repeat(limit) { for (e in stepTick()) pick(e)?.let { return it } }
        return null
    }

    @Test
    fun `the boss marks a danger zone, and only those who stay in it are hit`() {
        val sim = world()
        sim.hero("stay", 10, 14)
        sim.hero("dodge", 11, 14)
        val warning = sim.until { it as? WorldSimulationEvent.BossTelegraph }
        assertNotNull(warning, "the boss warns before it strikes")
        assertEquals(1, warning!!.radius)
        assertEquals("Slam!", warning.label)
        // the zone is centred on whoever the boss fought; one hero steps clear, the other does not
        val target = sim.boss().targetEntityId!!
        val other = if (target == "stay") "dodge" else "stay"
        sim.spawnOrUpdatePlayer(sim.players.getValue(other).copy(position = WorldPosition.fromTile(1, 25, 25, Direction.DOWN)))
        val otherLife = sim.life(other)
        val targetBefore = sim.life(target)
        sim.until(limit = 40) { (it as? WorldSimulationEvent.FloatingTextEmitted)?.takeIf { f -> f.text.startsWith("-") && f.tileX == warning.centerX && f.tileY == warning.centerY } }
        assertTrue(targetBefore - sim.life(target) >= 20, "the target who stayed took the slam")
        assertEquals(otherLife, sim.life(other), "the one who stepped out was not")
    }

    @Test
    fun `a boss in a later phase announces it, calls adds and hits harder, and the adds go with the fight`() {
        val sim = world()
        sim.hero("tank", 10, 14)
        sim.until(limit = 5) { null }
        sim.setBossLife(400)
        val events = (1..3).flatMap { sim.stepTick() }
        assertEquals(1, sim.boss().phaseIndex)
        assertTrue(events.filterIsInstance<WorldSimulationEvent.ChatEmitted>().any { "roars" in it.message }, "the phase is announced to the map")
        val adds = sim.mapNpcs.getValue(1).filter { it.spawnSlotIndex < 0 }
        assertEquals(1, adds.size, "one add is called")
        assertEquals(21, adds.single().npcId)
        assertEquals(200, sim.adjustedVariant(sim.boss(), sim.npcVariant(sim.boss()))?.attackPercent, "phase two hits twice as hard")
        // the boss dies: its adds are cleared at the next tick
        val i = sim.mapNpcs.getValue(1).indexOfFirst { it.npcId == 20 }
        sim.mapNpcs.getValue(1)[i] = sim.boss().copy(vitals = mapOf("hp" to sim.boss().vitals.getValue("hp").copy(cur = 0)), respawnAtTick = sim.currentTick + 9999, targetEntityId = null)
        sim.stepTick()
        assertTrue(sim.mapNpcs.getValue(1).none { it.spawnSlotIndex < 0 }, "no adds are left once the boss is gone")
    }

    @Test
    fun `a boss with a lockout rewards a player once, then saves them to it`() {
        val sim = world()
        val prize = com.morgana.domain.item.ItemDefinition(id = 90, name = "Warden Crest", type = com.morgana.domain.item.ItemType.MATERIAL, maxStack = 1)
        sim.items[90] = prize
        sim.npcs[20] = bossDef.copy(
            drops = listOf(com.morgana.domain.content.NpcDropEntry(itemId = 90, minAmount = 1, maxAmount = 1, chanceBasisPoints = 10_000)),
            encounter = bossDef.encounter!!.copy(lockoutTicks = 5_000L)
        )
        sim.hero("ann", 10, 14)
        sim.until(limit = 3) { null }
        val first = sim.boss()
        val out = mutableListOf<WorldSimulationEvent>()
        sim.handleNpcKilled(first, sim.npcs.getValue(20), sim.players.getValue("ann"), out)
        assertEquals(1, sim.mapGroundItems[1].orEmpty().count { it.stack.itemId == 90 }, "the first kill drops the crest")
        assertTrue(sim.players.getValue("ann").encounterLockouts.getValue(20) > sim.effectiveWorldTick(), "and saves the player to the boss")
        assertTrue(out.filterIsInstance<WorldSimulationEvent.ChatEmitted>().any { "saved to Warden" in it.message })

        // the same player kills it again: nothing drops and no XP is given
        sim.mapGroundItems[1]?.clear()
        val xpBefore = sim.players.getValue("ann").xp
        sim.handleNpcKilled(first, sim.npcs.getValue(20), sim.players.getValue("ann"), mutableListOf())
        assertEquals(0, sim.mapGroundItems[1].orEmpty().count { it.stack.itemId == 90 }, "a saved player gets no loot")
        assertEquals(xpBefore, sim.players.getValue("ann").xp, "and no XP")

        // a newcomer in the room is not saved, so the kill rewards them
        sim.hero("bob", 11, 14)
        sim.handleNpcKilled(first, sim.npcs.getValue(20), sim.players.getValue("ann"), mutableListOf())
        assertEquals(1, sim.mapGroundItems[1].orEmpty().count { it.stack.itemId == 90 && it.ownerCharacterId == "bob" }, "the unsaved player gets the drop")
    }
}
