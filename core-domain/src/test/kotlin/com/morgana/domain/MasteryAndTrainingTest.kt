package com.morgana.domain

import com.morgana.domain.content.ActionSkillDefinition
import com.morgana.domain.content.HitReaction
import com.morgana.domain.content.SkillDamageKind
import com.morgana.domain.content.SkillHit
import com.morgana.domain.content.SkillHitbox
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.SkillMasteryConfig
import com.morgana.domain.game.TrainingConfig
import com.morgana.domain.game.TrainingFocusKind
import com.morgana.domain.game.WeaponProficiencyConfig
import com.morgana.domain.game.WeaponTypeDefinition
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.clearOfflineTrainingFuel
import com.morgana.domain.sim.fuelOfflineTraining
import com.morgana.domain.sim.onActionCombatHitLanded
import com.morgana.domain.sim.setTrainingFocus
import com.morgana.domain.sim.skillMasteryDamageMultiplierPercent
import com.morgana.domain.sim.toggleAfkTraining
import com.morgana.domain.sim.weaponProficiencyDamageMultiplierPercent
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MasteryAndTrainingTest {

    private fun testSim(): WorldSimulation {
        val def = MorganaClassic.definition.copy(
            weaponProficiency = WeaponProficiencyConfig(
                enabled = true,
                maxLevel = 20,
                hitsPerLevel = 5,
                damageBonusPercentPerLevel = 3,
                weaponTypes = listOf(WeaponTypeDefinition("sword", "Sword"))
            ),
            skillMastery = SkillMasteryConfig(
                enabled = true,
                maxLevel = 20,
                usesPerLevel = 4,
                damagePercentPerLevel = 2
            ),
            training = TrainingConfig(
                enabled = true,
                offlineHitsPerMinute = 2,
                onlineAfkDailyLimitMinutes = 30,
                onlineAfkHitsPerMinute = 12,
                onlineAfkIntervalTicks = 100
            )
        )
        val sim = WorldSimulation(game = CompiledGame.compile(def))
        sim.maps[1] = MapDefinition(id = 1, name = "Yard", width = 10, height = 10)
        sim.items[1] = ItemDefinition(id = 1, name = "Iron Sword", type = ItemType.WEAPON, weaponType = "sword", attackDamage = 10)
        sim.items[10] = ItemDefinition(id = 10, name = "Apple", type = ItemType.CONSUMABLE, stackable = true, maxStack = 99, trainingTier = 1, trainingMinutes = 6, trainingCapMinutes = 120)
        sim.items[11] = ItemDefinition(id = 11, name = "Masterworked Meal", type = ItemType.CONSUMABLE, stackable = true, maxStack = 99, trainingTier = 3, trainingMinutes = 120, trainingCapMinutes = 600)
        sim.actionSkills[1] = ActionSkillDefinition(
            id = 1,
            name = "Slash",
            startupTicks = 2,
            activeTicks = 3,
            recoveryTicks = 5,
            hitboxes = listOf(SkillHitbox(fromTick = 2, toTick = 4, hit = SkillHit(damagePercent = 100, damageKind = SkillDamageKind.MELEE, reaction = HitReaction.NONE)))
        )
        return sim
    }

    private fun testHero(): CharacterState = CharacterState(
        id = "hero",
        accountId = "acc",
        name = "Hero",
        classId = 1,
        position = WorldPosition(1, 2, 2),
        equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(1, 1))),
        inventory = Inventory(
            maxSlots = 4,
            slots = listOf(
                ItemStack(10, 30), // 30 Apples (T1: 6m each, 120m cap)
                ItemStack(11, 6),  // 6 Masterworked Meals (T3: 120m each, 600m cap)
                null, null
            )
        )
    )

    @Test
    fun `action combat hits advance both weapon proficiency and skill mastery with flat scaling`() {
        val sim = testSim()
        sim.spawnOrUpdatePlayer(testHero())
        val out = mutableListOf<WorldSimulationEvent>()
        repeat(5) { sim.onActionCombatHitLanded("hero", 1, out) }
        val p = sim.players.getValue("hero")
        assertEquals(2, p.weaponProficiencyLevel("sword"))
        assertEquals(103, sim.weaponProficiencyDamageMultiplierPercent(p, "sword"))
        assertEquals(2, p.skillMasteryLevel(1))
        assertEquals(102L, sim.skillMasteryDamageMultiplierPercent(p, 1))
    }

    @Test
    fun `single-tier food lock prevents mixing cheap food with high-tier meals until cleared`() {
        val sim = testSim()
        sim.spawnOrUpdatePlayer(testHero())

        // Load 4 Masterworked Meals (T3: 4 * 120m = 480m / 600m)
        sim.fuelOfflineTraining("hero", slotIndex = 1, amount = 4)
        var p = sim.players.getValue("hero")
        assertEquals(3, p.trainingState.foodTier)
        assertEquals(480 * 60, p.trainingState.fueledSeconds)
        assertEquals(600 * 60, p.trainingState.capSeconds)
        assertEquals(2, p.inventory.slots[1]?.amount)

        // Attempting to top off with T1 Apples must be refused!
        val refused = sim.fuelOfflineTraining("hero", slotIndex = 0, amount = 20)
        assertTrue(refused.any { it is WorldSimulationEvent.ChatEmitted && it.message.contains("locked to Tier 3") })
        p = sim.players.getValue("hero")
        assertEquals(30, p.inventory.slots[0]?.amount, "apples must not be consumed")
        assertEquals(480 * 60, p.trainingState.fueledSeconds)

        // Clearing rations unlocks tier switching so T1 Apples can be loaded up to their 2h cap (20 apples)
        sim.clearOfflineTrainingFuel("hero")
        sim.fuelOfflineTraining("hero", slotIndex = 0, amount = 25)
        p = sim.players.getValue("hero")
        assertEquals(1, p.trainingState.foodTier)
        assertEquals(120 * 60, p.trainingState.fueledSeconds)
        assertEquals(120 * 60, p.trainingState.capSeconds)
        assertEquals(10, p.inventory.slots[0]?.amount, "only 20 apples consumed to hit the 120m cap")
    }

    @Test
    fun `offline training consumes food only while offline and emits report on login`() {
        val sim = testSim()
        val t0 = 1_700_000_000_000L
        sim.advanceWallClock(t0)
        sim.spawnOrUpdatePlayer(testHero())
        sim.setTrainingFocus("hero", TrainingFocusKind.SKILL, "1")
        sim.fuelOfflineTraining("hero", slotIndex = 0, amount = 5) // 5 Apples = 30 min

        // Log out -> stamps lastOnlineEpochMs
        val logoutEvents = sim.removePlayer("hero")
        val saved = logoutEvents.filterIsInstance<WorldSimulationEvent.CharacterUpdated>().last().character
        assertEquals(t0, saved.trainingState.lastOnlineEpochMs)

        // Advance wall clock by 20 minutes and log back in
        sim.advanceWallClock(t0 + 20L * 60_000L)
        val loginEvents = sim.spawnOrUpdatePlayer(saved)
        val report = loginEvents.filterIsInstance<WorldSimulationEvent.OfflineTrainingCompleted>().single()
        assertEquals(20, report.minutesTrained)
        assertEquals(40, report.hitsAwarded)
        assertEquals(10 * 60, report.remainingFuelSeconds)
        assertTrue(report.endLevel > report.startLevel)

        val live = sim.players.getValue("hero")
        assertEquals(10 * 60, live.trainingState.fueledSeconds)
        assertEquals(1, live.trainingState.foodTier)
    }

    @Test
    fun `online AFK training grants mastery without consuming offline food and respects daily limit`() {
        val sim = testSim()
        val day1 = 20_000L * 86_400_000L + 1_000L
        sim.advanceWallClock(day1)
        sim.spawnOrUpdatePlayer(testHero())
        sim.setTrainingFocus("hero", TrainingFocusKind.WEAPON, "sword")
        sim.fuelOfflineTraining("hero", slotIndex = 0, amount = 2) // 12m offline rations

        sim.toggleAfkTraining("hero", true)
        assertTrue(sim.players.getValue("hero").trainingState.afkActive)

        // Step 100 ticks (5s = 1 AFK grant)
        repeat(100) { sim.stepTick() }
        var p = sim.players.getValue("hero")
        assertEquals(12 * 60, p.trainingState.fueledSeconds, "online AFK must NOT consume offline food")
        assertEquals(5, p.trainingState.afkUsedSecondsToday)
        assertTrue(p.weaponProficiencyHitsFor("sword") > 0 || p.weaponProficiencyLevel("sword") > 1)

        // Exhaust the 30-minute daily budget
        sim.players["hero"] = p.copy(trainingState = p.trainingState.copy(afkUsedSecondsToday = 30 * 60 - 5))
        repeat(100) { sim.stepTick() }
        p = sim.players.getValue("hero")
        assertFalse(p.trainingState.afkActive, "AFK mode stops automatically at daily cap")
        assertEquals(30 * 60, p.trainingState.afkUsedSecondsToday)

        // Re-enabling same day is refused, next UTC day resets budget
        sim.toggleAfkTraining("hero", true)
        assertFalse(sim.players.getValue("hero").trainingState.afkActive)
        sim.advanceWallClock(day1 + 86_400_000L)
        sim.toggleAfkTraining("hero", true)
        assertTrue(sim.players.getValue("hero").trainingState.afkActive)
    }
}
