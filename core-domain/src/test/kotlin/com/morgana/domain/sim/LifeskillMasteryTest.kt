package com.morgana.domain.sim

import com.morgana.domain.content.CraftingStationType
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.content.RecipeIngredient
import com.morgana.domain.content.ResourceCategory
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.LifeskillBranchDefinition
import com.morgana.domain.game.LifeskillDefinition
import com.morgana.domain.game.LifeskillMasteryConfig
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.ToolType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LifeskillMasteryTest {

    private fun testSim(): WorldSimulation {
        val lifeskillCfg = LifeskillMasteryConfig(
            enabled = true,
            apprenticeMaxLevel = 5,
            journeymanMaxLevel = 12,
            grandmasterMaxLevel = 20,
            maxJourneymanBranches = 3,
            maxGrandmasterBranches = 1,
            actionsPerLevel = 1,
            apprenticeBonusPercentPerLevel = 2,
            lifeskills = listOf(
                LifeskillDefinition(
                    id = "mining",
                    name = "Mining",
                    kind = "GATHERING",
                    resourceCategories = listOf("ORE_VEIN"),
                    branches = listOf(
                        LifeskillBranchDefinition("prospector", "Prospector", bonusPercentPerLevel = 4),
                        LifeskillBranchDefinition("deep_miner", "Deep Miner", bonusPercentPerLevel = 2, extraHarvestHitAtLevel = 6)
                    )
                ),
                LifeskillDefinition(
                    id = "tailoring",
                    name = "Tailoring",
                    kind = "CRAFTING",
                    stationTypes = listOf("WORKBENCH"),
                    branches = listOf(
                        LifeskillBranchDefinition("outfitter", "Outfitter", bonusPercentPerLevel = 3),
                        LifeskillBranchDefinition("saddler_bagmaker", "Saddler & Bagmaker", bonusPercentPerLevel = 3)
                    )
                ),
                LifeskillDefinition(
                    id = "smithing",
                    name = "Smithing",
                    kind = "CRAFTING",
                    stationTypes = listOf("ANVIL"),
                    branches = listOf(
                        LifeskillBranchDefinition("weaponsmith", "Weaponsmith", bonusPercentPerLevel = 3),
                        LifeskillBranchDefinition("armorsmith", "Armorsmith", bonusPercentPerLevel = 3)
                    )
                )
            )
        )
        val def = MorganaClassic.definition.copy(
            lifeskillMastery = lifeskillCfg,
            maxInventorySlots = 36,
            maxBankSlots = 64
        )
        val sim = WorldSimulation(game = CompiledGame.compile(def))
        sim.maps[1] = MapDefinition(id = 1, name = "Test", width = 10, height = 10)
        sim.items[1] = ItemDefinition(id = 1, name = "Copper Ore", type = ItemType.MATERIAL)
        sim.items[2] = ItemDefinition(id = 2, name = "Leather Satchel", type = ItemType.CONSUMABLE, expandInventorySlots = 4)
        sim.items[3] = ItemDefinition(id = 3, name = "Vault Chest", type = ItemType.CONSUMABLE, expandBankSlots = 8)
        sim.resources[1] = ResourceDefinition(
            id = 1, name = "Copper Vein", category = ResourceCategory.ORE_VEIN,
            requiredTool = ToolType.NONE, rewardItemId = 1, minYield = 1, maxYield = 1, maxHealth = 2, respawnTicks = 10, xpReward = 5
        )
        sim.recipes[1] = RecipeDefinition(
            id = 1, name = "Basic Satchel", stationType = CraftingStationType.ANYWHERE,
            ingredients = listOf(RecipeIngredient(1, 1)), outputItemId = 2, outputAmount = 1,
            xpReward = 10, professionId = "tailoring"
        )
        sim.recipes[2] = RecipeDefinition(
            id = 2, name = "Specialist Satchel", stationType = CraftingStationType.ANYWHERE,
            ingredients = listOf(RecipeIngredient(1, 1)), outputItemId = 2, outputAmount = 1,
            xpReward = 20, professionId = "tailoring", branchId = "saddler_bagmaker", requiredProfessionLevel = 6
        )
        sim.players["p1"] = CharacterState(
            id = "p1", accountId = "a1", name = "Crafter", classId = 1,
            position = WorldPosition(1, 1, 1),
            inventory = Inventory(maxSlots = 24),
            bank = Inventory(maxSlots = 32)
        )
        return sim
    }

    private fun Inventory.countOfId(itemId: Int): Int =
        slots.filterNotNull().filter { it.itemId == itemId }.sumOf { it.amount }

    @Test
    fun `three-tier specialization funnel enforces Apprentice cap, Journeyman 3-slot cap, Grandmaster 1-slot cap, and step-down`() {
        val sim = testSim()
        val cfg = sim.lifeskillConfig()!!
        val out = mutableListOf<WorldSimulationEvent>()

        for (ls in cfg.lifeskills) {
            repeat(12) { sim.onLifeskillActionCompleted("p1", ls, "", out) }
            assertEquals(5, sim.players["p1"]!!.lifeskillState.levelOf(ls.id))
        }

        sim.unlockLifeskillBranch("p1", "prospector")
        sim.unlockLifeskillBranch("p1", "deep_miner")
        sim.unlockLifeskillBranch("p1", "saddler_bagmaker")
        assertEquals(setOf("prospector", "deep_miner", "saddler_bagmaker"), sim.players["p1"]!!.lifeskillState.journeymanBranches)

        sim.unlockLifeskillBranch("p1", "armorsmith")
        assertFalse("armorsmith" in sim.players["p1"]!!.lifeskillState.journeymanBranches)

        assertEquals(2, sim.lifeskillHarvestPower(sim.players["p1"]!!, sim.resources[1]!!))

        val tailoring = cfg.findLifeskill("tailoring")!!
        repeat(80) { sim.onLifeskillActionCompleted("p1", tailoring, "saddler_bagmaker", out) }
        assertEquals(12, sim.players["p1"]!!.lifeskillState.branchLevelOf("saddler_bagmaker"))

        sim.promoteLifeskillGrandmaster("p1", "saddler_bagmaker")
        assertTrue("saddler_bagmaker" in sim.players["p1"]!!.lifeskillState.grandmasterBranches)
        assertEquals(13, sim.players["p1"]!!.lifeskillState.branchLevelOf("saddler_bagmaker"))

        sim.stepDownLifeskillBranch("p1", "saddler_bagmaker")
        assertFalse("saddler_bagmaker" in sim.players["p1"]!!.lifeskillState.grandmasterBranches)
        assertEquals(12, sim.players["p1"]!!.lifeskillState.branchLevelOf("saddler_bagmaker"))

        sim.stepDownLifeskillBranch("p1", "saddler_bagmaker")
        assertFalse("saddler_bagmaker" in sim.players["p1"]!!.lifeskillState.journeymanBranches)

        sim.unlockLifeskillBranch("p1", "armorsmith")
        assertTrue("armorsmith" in sim.players["p1"]!!.lifeskillState.journeymanBranches)
    }

    @Test
    fun `recipe branch gating and bag or vault expansion consumables work end to end`() {
        val sim = testSim()
        val p = sim.players["p1"]!!
        val ore = sim.items[1]!!
        sim.players["p1"] = p.copy(inventory = p.inventory.addItem(ore, 20).updatedInventory)

        sim.craftRecipe("p1", 2)
        assertEquals(0, sim.players["p1"]!!.inventory.countOfId(2))

        repeat(10) { sim.craftRecipe("p1", 1) }
        assertEquals(5, sim.players["p1"]!!.lifeskillState.levelOf("tailoring"))
        sim.unlockLifeskillBranch("p1", "saddler_bagmaker")

        val before = sim.players["p1"]!!.inventory.countOfId(2)
        sim.craftRecipe("p1", 2)
        assertTrue(sim.players["p1"]!!.inventory.countOfId(2) > before)

        val satchelSlot = sim.players["p1"]!!.inventory.slots.indexOfFirst { it?.itemId == 2 }
        sim.useInventorySlot("p1", satchelSlot)
        assertEquals(28, sim.players["p1"]!!.inventory.maxSlots)

        val withChest = sim.players["p1"]!!.let {
            it.copy(inventory = it.inventory.addItem(sim.items[3]!!, 1).updatedInventory)
        }
        sim.players["p1"] = withChest
        val chestSlot = withChest.inventory.slots.indexOfFirst { it?.itemId == 3 }
        sim.useInventorySlot("p1", chestSlot)
        assertEquals(40, sim.players["p1"]!!.bank.maxSlots)
    }
}
