package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.GearUpgradeKind
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.EnhancementDefinition
import com.morgana.domain.game.EnhancementTier
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.GearUpgradeDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The `+` enhancement ladder: guaranteed tiers, chancy tiers with pity, and what a failure costs. */
class EnhancementTest {
    private val stone = ItemDefinition(id = 20, name = "Honing Stone", type = ItemType.MATERIAL, maxStack = 99)
    private val crystal = ItemDefinition(id = 21, name = "Crystal", type = ItemType.MATERIAL, maxStack = 99)
    private val sword = ItemDefinition(id = 2, name = "Sword", type = ItemType.WEAPON, attackDamage = 10)

    /** +1 and +2 never fail; +3 is a 1% try; +4 is a 1% try that also drops a level when it fails. */
    private fun ladder(pityBonus: Int = 5_000, maxStacks: Int = 2, dropFrom: Int = 4) = EnhancementDefinition(
        tiers = listOf(
            EnhancementTier(1, 10_000, 20, 1),
            EnhancementTier(2, 10_000, 20, 2),
            EnhancementTier(3, 100, 21, 1),
            EnhancementTier(4, 100, 21, 1)
        ),
        pityBonusBasisPointsPerStack = pityBonus, maxPityStacks = maxStacks, failDurabilityLoss = 10, dropPlusOnFailFrom = dropFrom
    )

    private fun sim(plus: Int = 0, stones: Int = 10, crystals: Int = 10, durability: Int = 100, ladder: EnhancementDefinition = ladder()): WorldSimulation {
        val game = CompiledGame.compile(MorganaClassic.definition.copy(gearUpgrade = GearUpgradeDefinition(enhancement = ladder)))
        val s = WorldSimulation(SeededRandomSource(5L), game = game)
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        listOf(stone, crystal, sword).forEach { s.items[it.id] = it }
        val inv = Inventory().let { if (stones > 0) it.addItem(stone, stones).updatedInventory else it }.let { if (crystals > 0) it.addItem(crystal, crystals).updatedInventory else it }
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p1", accountId = "a", name = "P", level = 20, inventory = inv,
                equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(2, plus = plus, durability = durability)))
            ).recalculateVitals(game, s.items, refill = true)
        )
        return s
    }

    private fun WorldSimulation.enhance() = mutableListOf<com.morgana.domain.sim.WorldSimulationEvent>().also {
        executeEventCommands("p1", 1, listOf(EventCommand.UpgradeGear(GearUpgradeKind.ENHANCE, EquipmentSlot.WEAPON)), it)
    }

    private fun WorldSimulation.weapon() = players.getValue("p1").equipment.getEquipped(EquipmentSlot.WEAPON)!!
    private fun WorldSimulation.carried(id: Int) = players.getValue("p1").inventory.totalCountOf(id)
    private fun WorldSimulation.pity() = players.getValue("p1").upgradePityStacks

    @Test
    fun `guaranteed tiers always succeed, cost their stones, and build no pity`() {
        val s = sim()
        s.enhance(); s.enhance()
        assertEquals(2, s.weapon().plus)
        assertEquals(10 - 1 - 2, s.carried(20))
        assertEquals(0, s.pity())
        assertEquals(100, s.weapon().durability)
    }

    @Test
    fun `a failed chancy try uses the material, wears the gear and adds pity`() {
        val s = sim(plus = 2)
        // Pity bonus 5000 per stack: the 1% first try fails on this seed, and each stack then adds 50%.
        var tries = 0
        while (s.weapon().plus == 2 && tries < 10) { s.enhance(); tries++ }
        assertEquals(3, s.weapon().plus, "pity finishes the step within a few tries")
        assertTrue(tries in 2..3, "1% then 51% then 100% (maximum 2 stacks), got $tries tries")
        assertEquals(10 - tries, s.carried(21), "each try used one crystal")
        assertEquals(100 - 10 * (tries - 1), s.weapon().durability, "each failure took 10 durability")
        assertEquals(0, s.pity(), "a chancy success spends the pity")
    }

    @Test
    fun `pity at its cap makes the step certain`() {
        val s = sim(plus = 2, ladder = ladder(pityBonus = 5_000, maxStacks = 2))
        val p = s.players.getValue("p1")
        s.players["p1"] = p.copy(upgradePityStacks = 2)
        s.enhance()
        assertEquals(3, s.weapon().plus, "1% + 2 x 50% is 101%, a sure thing")
        assertEquals(0, s.pity())
    }

    @Test
    fun `a failure from the drop level knocks one plus off`() {
        val s = sim(plus = 3, ladder = ladder(pityBonus = 0, maxStacks = 5, dropFrom = 4))
        s.enhance()  // a 1% try for +4 that fails on this seed
        assertEquals(2, s.weapon().plus, "the failed +4 try dropped the +3 weapon to +2")
        assertEquals(1, s.pity())
    }

    @Test
    fun `a failure below the drop level keeps the plus`() {
        val s = sim(plus = 2, ladder = ladder(pityBonus = 0, maxStacks = 5, dropFrom = 4))
        s.enhance()  // a failed 1% try for +3
        assertEquals(2, s.weapon().plus)
        assertEquals(1, s.pity())
    }

    @Test
    fun `broken gear and missing material are refused and nothing is taken`() {
        val game = CompiledGame.compile(
            MorganaClassic.definition.copy(
                gearUpgrade = GearUpgradeDefinition(enhancement = ladder()),
                durability = com.morgana.domain.game.DurabilityDefinition(enabled = true)
            )
        )
        val s = WorldSimulation(SeededRandomSource(5L), game = game)
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        listOf(stone, crystal, sword).forEach { s.items[it.id] = it }
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p1", accountId = "a", name = "P", level = 20,
                inventory = Inventory().addItem(stone, 5).updatedInventory,
                equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(2, durability = 0)))
            ).recalculateVitals(game, s.items, refill = true)
        )
        s.enhance()
        assertEquals(0, s.weapon().plus, "broken gear is refused")
        assertEquals(5, s.carried(20), "nothing was taken")

        val noStones = sim(stones = 0)
        noStones.enhance()
        assertEquals(0, noStones.weapon().plus)
        assertEquals(10, noStones.carried(21))
    }

    @Test
    fun `the top step is refused and merging gear is closed when a ladder exists`() {
        val s = sim(plus = 4)
        s.enhance()
        assertEquals(4, s.weapon().plus, "the ladder ends at +4")
        assertEquals(10, s.carried(21), "nothing taken")
        val inv = s.players.getValue("p1").inventory.addItem(sword, 1).updatedInventory.addItem(sword, 1).updatedInventory
        s.players["p1"] = s.players.getValue("p1").copy(inventory = inv)
        val slots = inv.slots.mapIndexedNotNull { i, st -> if (st?.itemId == 2) i else null }
        val events = s.mergeGear("p1", slots[0], slots[1])
        assertTrue(events.any { it.toString().contains("Gear Master") }, "merging is replaced by enhancing")
    }

    @Test
    fun `the validator rejects a broken ladder`() {
        val bad = MorganaClassic.definition.copy(
            gearUpgrade = GearUpgradeDefinition(
                enhancement = EnhancementDefinition(tiers = listOf(EnhancementTier(1, 10_000, 20), EnhancementTier(3, 0, 0, 0)))
            )
        )
        val problems = GameDefinitionValidator.validate(bad).map { it.toString() }
        assertTrue(problems.any { "tiers/1/plus" in it }, "gaps in the ladder: $problems")
        assertTrue(problems.any { "chanceBasisPoints" in it }, "a zero chance: $problems")
        assertTrue(problems.any { "materialItemId" in it }, "a missing material: $problems")
    }
}
