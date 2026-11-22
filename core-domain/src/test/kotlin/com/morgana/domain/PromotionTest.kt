package com.morgana.domain

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.PromotionDefinition
import com.morgana.domain.game.PromotionTier
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromotionTest {
    private val promotion = PromotionDefinition(
        startName = "Intern",
        tiers = listOf(
            PromotionTier("Veteran", minLevel = 10, bonusStatPoints = 5, grantSpellIds = listOf(2)),
            PromotionTier("Tiger", minLevel = 20)
        )
    )
    private val helm = ItemDefinition(id = 5, name = "Veteran Helm", type = ItemType.HELMET, armorDefense = 3, promotionReq = 1)

    private fun sim(level: Int = 1, tier: Int = 0): WorldSimulation {
        val game = CompiledGame.compile(MorganaClassic.definition.copy(promotion = promotion))
        val s = WorldSimulation(SeededRandomSource(5L), game = game)
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        s.items[5] = helm
        s.spells[1] = SpellDefinition(id = 1, name = "Smite", targetType = SpellTargetType.SELF, promotionReq = 1, cooldownTicks = 0)
        s.spells[2] = SpellDefinition(id = 2, name = "Veteran Strike", targetType = SpellTargetType.SELF, promotionReq = 1)
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p1", accountId = "a", name = "P", level = level, knownSpellIds = listOf(1),
                position = WorldPosition.fromTile(1, 2, 2, Direction.DOWN),
                inventory = Inventory().addItem(helm, 1).updatedInventory,
                variables = if (tier > 0) mapOf("promotion" to tier) else emptyMap()
            )
        )
        return s
    }

    private fun promote(s: WorldSimulation): List<WorldSimulationEvent> =
        mutableListOf<WorldSimulationEvent>().also { s.executeEventCommands("p1", 1, listOf(EventCommand.Promote), it) }

    private fun tierOf(s: WorldSimulation) = promotion.tierOf(s.players.getValue("p1"))

    @Test
    fun `promotion needs the tier's level and each step raises one tier`() {
        val s = sim(level = 9)
        promote(s)
        assertEquals(0, tierOf(s), "level 9 is too low for Veteran")

        s.players["p1"] = s.players.getValue("p1").copy(level = 10)
        promote(s)
        val veteran = s.players.getValue("p1")
        assertEquals(1, tierOf(s))
        assertEquals(5, veteran.unspentStatPoints, "the tier's attribute points arrive")
        assertTrue(2 in veteran.knownSpellIds, "the tier's spell is taught")

        promote(s)
        assertEquals(1, tierOf(s), "Tiger needs level 20, not 10")

        s.players["p1"] = s.players.getValue("p1").copy(level = 20)
        promote(s)
        assertEquals(2, tierOf(s))
        promote(s)
        assertEquals(2, tierOf(s), "there is no tier past Tiger")
    }

    @Test
    fun `a spell with a promotion requirement is refused until the rank is reached`() {
        val s = sim()
        val refused = s.playerCastSpell("p1", 1, null, 0, 0)
        assertTrue(refused.filterIsInstance<WorldSimulationEvent.SpellCast>().isEmpty())
        assertTrue(refused.any { it is WorldSimulationEvent.ChatEmitted && "requires the rank Veteran" in it.message }, "the player is told which rank is missing")

        val ranked = sim(tier = 1)
        assertTrue(ranked.playerCastSpell("p1", 1, null, 0, 0).any { it is WorldSimulationEvent.SpellCast })
    }

    @Test
    fun `gear with a promotion requirement is not worn until the rank is reached`() {
        val s = sim()
        s.useInventorySlot("p1", 0)
        assertEquals(1, s.players.getValue("p1").inventory.totalCountOf(5), "the helm stays in the bag")

        val ranked = sim(tier = 1)
        ranked.useInventorySlot("p1", 0)
        assertEquals(0, ranked.players.getValue("p1").inventory.totalCountOf(5), "a Veteran can put it on")
    }

    @Test
    fun `a game without promotions never meets a requirement and the section is validated`() {
        val game = CompiledGame.compile(MorganaClassic.definition)
        val s = WorldSimulation(SeededRandomSource(5L), game = game)
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        s.spells[1] = SpellDefinition(id = 1, name = "Smite", targetType = SpellTargetType.SELF, promotionReq = 1)
        s.spawnOrUpdatePlayer(CharacterState(id = "p1", accountId = "a", name = "P", knownSpellIds = listOf(1), position = WorldPosition.fromTile(1, 2, 2, Direction.DOWN)))
        assertTrue(s.playerCastSpell("p1", 1, null, 0, 0).none { it is WorldSimulationEvent.SpellCast })

        val classic = MorganaClassic.definition
        assertTrue(GameDefinitionValidator.validate(classic.copy(promotion = promotion)).isEmpty())
        val backwards = promotion.copy(tiers = listOf(PromotionTier("A", 20), PromotionTier("B", 10)))
        assertFalse(GameDefinitionValidator.validate(classic.copy(promotion = backwards)).isEmpty(), "tier levels must not go down")
        assertFalse(GameDefinitionValidator.validate(classic.copy(promotion = promotion.copy(tiers = emptyList()))).isEmpty())
    }

    @Test
    fun `a class can have its own title and its own spell for a tier`() {
        val titled = promotion.copy(
            tiers = listOf(
                PromotionTier("Veteran", minLevel = 10, classNames = mapOf(7 to "Swordmaster"), classGrantSpellIds = mapOf(7 to listOf(2), 8 to listOf(3))),
                PromotionTier("Tiger", minLevel = 20)
            )
        )
        assertEquals("Swordmaster", titled.nameOf(1, classId = 7))
        assertEquals("Veteran", titled.nameOf(1, classId = 8), "a class with no title of its own uses the shared name")
        assertEquals("Veteran", titled.nameOf(1))
        assertEquals("Intern", titled.nameOf(0, classId = 7))

        val game = CompiledGame.compile(MorganaClassic.definition.copy(promotion = titled))
        val s = WorldSimulation(SeededRandomSource(5L), game = game)
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        s.spells[2] = SpellDefinition(id = 2, name = "Swordmaster Strike", targetType = SpellTargetType.SELF, classReqIds = setOf(7))
        s.spells[3] = SpellDefinition(id = 3, name = "Other Strike", targetType = SpellTargetType.SELF, classReqIds = setOf(8))
        s.spawnOrUpdatePlayer(CharacterState(id = "p1", accountId = "a", name = "P", level = 10, classId = 7, position = WorldPosition.fromTile(1, 2, 2, Direction.DOWN)))
        val events = mutableListOf<WorldSimulationEvent>().also { s.executeEventCommands("p1", 1, listOf(EventCommand.Promote), it) }
        val known = s.players.getValue("p1").knownSpellIds
        assertTrue(2 in known, "the class's own spell is taught")
        assertFalse(3 in known, "another class's spell is not")
        assertTrue(events.any { it is WorldSimulationEvent.ChatEmitted && "promoted to Swordmaster" in it.message }, "the notice uses the class title")
    }
}
