package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.systems.respecAttributes
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Taking back spent attribute points. */
class RespecTest {
    private val marks = ItemDefinition(id = 1, name = "Marks", type = ItemType.CURRENCY, maxStack = 9999)

    private fun world(spent: Map<String, Int>, coins: Int): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(1L), game = MorganaClassic.COMPILED)
        sim.items[1] = marks
        sim.registerMap(MapDefinition(id = 1, name = "Town", width = 10, height = 10))
        val base = MorganaClassic.definition.attributes.associate { it.id to it.base }
        val hero = CharacterState(
            id = "h", accountId = "a", name = "h", attributes = base.mapValues { (k, v) -> v + (spent[k] ?: 0) }, unspentStatPoints = 2,
            position = WorldPosition.fromTile(1, 3, 3, Direction.DOWN)
        ).recalculateVitals(sim.game, sim.items, refill = true)
        sim.spawnOrUpdatePlayer(hero.copy(inventory = if (coins > 0) hero.inventory.addItem(marks, coins).updatedInventory else hero.inventory))
        return sim
    }

    private fun told(events: List<WorldSimulationEvent>, text: String) = events.filterIsInstance<WorldSimulationEvent.ChatEmitted>().any { text in it.message }

    @Test
    fun `spent points come back as unspent points, for a price per point`() {
        val firstAttr = MorganaClassic.definition.attributes.first().id
        val base = MorganaClassic.definition.attributes.first().base
        val sim = world(mapOf(firstAttr to 6), coins = 1000)
        val events = sim.respecAttributes("h", currencyItemId = 1, pricePerPoint = 25)
        val p = sim.players.getValue("h")
        assertEquals(base, p.attributes.getValue(firstAttr), "the attribute is back at its starting value")
        assertEquals(8, p.unspentStatPoints, "the 2 unspent points plus the 6 returned")
        assertEquals(1000 - 6 * 25, p.inventory.totalCountOf(1), "six points at 25 each")
        assertTrue(told(events, "paid 150"))
    }

    @Test
    fun `without the money or without anything spent, nothing changes`() {
        val firstAttr = MorganaClassic.definition.attributes.first().id
        val poor = world(mapOf(firstAttr to 6), coins = 100)
        assertTrue(told(poor.respecAttributes("h", 1, 25), "costs 150"))
        assertEquals(2, poor.players.getValue("h").unspentStatPoints)
        val fresh = world(emptyMap(), coins = 0)
        assertTrue(told(fresh.respecAttributes("h", 1, 25), "no spent attribute points"))
    }

    @Test
    fun `a free respec costs nothing`() {
        val firstAttr = MorganaClassic.definition.attributes.first().id
        val sim = world(mapOf(firstAttr to 3), coins = 0)
        sim.respecAttributes("h", 1, 0)
        assertEquals(5, sim.players.getValue("h").unspentStatPoints)
    }
}
