package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.GearUpgradeKind
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.GearUpgradeDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.QualityStep
import com.morgana.domain.game.QualityUpgradeDefinition
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.LootRules
import com.morgana.domain.item.RarityDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Quality raises under `gearUpgrade.quality`: a chance per step, pity after failures, and a small chance of a socket. */
class QualityUpgradeTest {
    private val pearl = ItemDefinition(id = 10, name = "Wyrm Pearl", type = ItemType.MATERIAL, maxStack = 99)
    private val sword = ItemDefinition(id = 2, name = "Sword", type = ItemType.WEAPON, attackDamage = 10)

    private val loot = LootRules(
        rarities = listOf(
            RarityDefinition("normal", "Common", weight = 10, unlabeled = true),
            RarityDefinition("refined", "Fine", weight = 10, affixesMin = 0, affixesMax = 0),
            RarityDefinition("super", "Mythic", weight = 10, affixesMin = 0, affixesMax = 0)
        )
    )

    private fun rules(chance: Int = 100, socketBp: Int = 0, bonus: Int = 5_000, maxStacks: Int = 2) = QualityUpgradeDefinition(
        steps = listOf(QualityStep("refined", chance, 2), QualityStep("super", chance, 3)),
        pityBonusBasisPointsPerStack = bonus, maxPityStacks = maxStacks, socketChanceBasisPoints = socketBp
    )

    private fun sim(rules: QualityUpgradeDefinition? = rules(), pearls: Int = 20, sockets: List<Int> = emptyList(), maxSockets: Int = 2, stacks: Int = 0): WorldSimulation {
        val game = CompiledGame.compile(MorganaClassic.definition.copy(loot = loot, gearUpgrade = GearUpgradeDefinition(maxSockets = maxSockets, quality = rules)))
        val s = WorldSimulation(SeededRandomSource(5L), game = game)
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        listOf(pearl, sword).forEach { s.items[it.id] = it }
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p1", accountId = "a", name = "P", level = 20, upgradePityStacks = stacks,
                inventory = Inventory().addItem(pearl, pearls).updatedInventory,
                equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(2, sockets = sockets)))
            ).recalculateVitals(game, s.items, refill = true)
        )
        return s
    }

    private fun WorldSimulation.raise() = mutableListOf<com.morgana.domain.sim.WorldSimulationEvent>().also {
        executeEventCommands("p1", 1, listOf(EventCommand.UpgradeGear(GearUpgradeKind.UPGRADE_QUALITY, EquipmentSlot.WEAPON, 10)), it)
    }

    private fun WorldSimulation.weapon() = players.getValue("p1").equipment.getEquipped(EquipmentSlot.WEAPON)!!
    private fun WorldSimulation.rarity() = weapon().rolls?.rarity
    private fun WorldSimulation.carried() = players.getValue("p1").inventory.totalCountOf(10)
    private fun WorldSimulation.pity() = players.getValue("p1").upgradePityStacks

    @Test
    fun `a failed raise uses the pearls and builds pity, and pity then makes it certain`() {
        val s = sim()
        s.raise()  // a 1% try that fails on this seed
        assertEquals(null, s.rarity(), "still the starting quality")
        assertEquals(18, s.carried(), "the step costs 2 pearls even when it fails")
        assertEquals(1, s.pity())
        s.raise()  // 1% + 50% (may fail)
        if (s.rarity() == null) {
            assertEquals(2, s.pity(), "pity is capped at the maximum")
            s.raise()  // 1% + 100% is certain
        }
        assertEquals("refined", s.rarity())
        assertEquals(0, s.pity(), "a chancy success spends the stacks")
    }

    @Test
    fun `a full pity bar guarantees the step and each step has its own price`() {
        val s = sim(stacks = 2)
        s.raise()
        assertEquals("refined", s.rarity())
        assertEquals(18, s.carried())
        s.players["p1"] = s.players.getValue("p1").copy(upgradePityStacks = 2)
        s.raise()
        assertEquals("super", s.rarity())
        assertEquals(15, s.carried(), "the second step costs 3 pearls")
        val before = s.carried()
        s.raise()
        assertEquals(before, s.carried(), "the best quality is refused and nothing is taken")
    }

    @Test
    fun `a success can open a socket only while there is room, and a refusal takes nothing`() {
        val sure = sim(rules = rules(chance = 10_000, socketBp = 10_000))
        sure.raise()
        assertEquals(listOf(0), sure.weapon().sockets, "a sure socket chance adds one")
        val full = sim(rules = rules(chance = 10_000, socketBp = 10_000), sockets = listOf(0, 0))
        full.raise()
        assertEquals(listOf(0, 0), full.weapon().sockets, "no room, no new socket")
        val never = sim(rules = rules(chance = 10_000, socketBp = 0))
        never.raise()
        assertEquals(emptyList<Int>(), never.weapon().sockets)
        val poor = sim(rules = rules(chance = 10_000), pearls = 1)
        poor.raise()
        assertEquals(1, poor.carried(), "not enough pearls: nothing taken")
        assertEquals(null, poor.rarity())
    }

    @Test
    fun `without a quality section the event's own success percent still applies`() {
        val s = sim(rules = null)
        mutableListOf<com.morgana.domain.sim.WorldSimulationEvent>().also {
            s.executeEventCommands("p1", 1, listOf(EventCommand.UpgradeGear(GearUpgradeKind.UPGRADE_QUALITY, EquipmentSlot.WEAPON, 10, 1, 100)), it)
        }
        assertEquals("refined", s.rarity())
        assertEquals(19, s.carried())
    }

    @Test
    fun `the validator rejects unknown rarities and bad chances`() {
        val bad = MorganaClassic.definition.copy(
            loot = loot,
            gearUpgrade = GearUpgradeDefinition(
                quality = QualityUpgradeDefinition(steps = listOf(QualityStep("nope", 0, 0), QualityStep("refined"), QualityStep("refined")), socketChanceBasisPoints = 20_000)
            )
        )
        val problems = GameDefinitionValidator.validate(bad).map { it.toString() }
        assertTrue(problems.any { "rarityId" in it }, "unknown rarity: $problems")
        assertTrue(problems.any { "chanceBasisPoints" in it }, "zero chance: $problems")
        assertTrue(problems.any { "once" in it }, "duplicate rarity: $problems")
        assertTrue(problems.any { "socketChanceBasisPoints" in it }, "socket chance: $problems")
    }
}
