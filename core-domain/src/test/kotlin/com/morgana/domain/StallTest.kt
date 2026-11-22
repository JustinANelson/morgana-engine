package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.StallDefinition
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.systems.isStalled
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StallTest {
    private val silver = ItemDefinition(id = 1, name = "Silver", type = ItemType.MATERIAL, maxStack = 1_000_000_000)
    private val potion = ItemDefinition(id = 2, name = "Potion", type = ItemType.MATERIAL, maxStack = 99)
    private val charm = ItemDefinition(id = 3, name = "Charm", type = ItemType.MATERIAL, maxStack = 1, boundOnPickup = true)

    private fun sim(rules: StallDefinition? = StallDefinition()): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(4L), game = CompiledGame.compile(MorganaClassic.definition.copy(stall = rules)))
        s.registerMap(MapDefinition(id = 1, name = "Market", width = 20, height = 20))
        s.registerMap(MapDefinition(id = 2, name = "Far", width = 20, height = 20))
        for (d in listOf(silver, potion, charm)) s.items[d.id] = d
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "seller", accountId = "a", name = "Sam", position = WorldPosition.fromTile(1, 5, 5, Direction.DOWN),
                inventory = Inventory().addItem(potion, 10).updatedInventory.addItem(charm, 1).updatedInventory
            )
        )
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "buyer", accountId = "b", name = "Bea", position = WorldPosition.fromTile(1, 7, 5, Direction.DOWN),
                inventory = Inventory().addItem(silver, 500).updatedInventory
            )
        )
        return s
    }

    private fun WorldSimulation.say(id: String, line: String) = executeAdminSlashCommand(id, line)
    private fun WorldSimulation.count(id: String, itemId: Int) = players.getValue(id).inventory.totalCountOf(itemId)

    private fun WorldSimulation.openPotionStall() {
        say("seller", "/stall add 1 20 6")
        say("seller", "/stall open Sam's Potions")
    }

    @Test
    fun `a buyer purchases goods from an open stall and both bags change`() {
        val s = sim()
        s.openPotionStall()
        assertTrue(s.isStalled("seller"))
        s.say("buyer", "/stall buy sam 1 3")
        assertEquals(3, s.count("buyer", 2))
        assertEquals(440, s.count("buyer", 1), "3 x 20 paid")
        assertEquals(7, s.count("seller", 2))
        assertEquals(60, s.count("seller", 1), "the seller is paid")
        s.say("buyer", "/stall buy sam 1 10")
        assertEquals(3, s.count("buyer", 2), "only 3 of the 6 offered remain, so 10 is refused")
        s.say("buyer", "/stall buy sam 1 3")
        assertEquals(6, s.count("buyer", 2))
        s.say("buyer", "/stall buy sam 1 1")
        assertEquals(6, s.count("buyer", 2), "the listing sold out and went away")
    }

    @Test
    fun `an open stall pins its owner and a closed one does not exist for buyers`() {
        val s = sim()
        s.say("buyer", "/stall buy sam 1")
        assertEquals(0, s.count("buyer", 2), "no stall yet")
        s.openPotionStall()
        val x = s.players.getValue("seller").position.tileX
        s.movePlayer("seller", Direction.RIGHT)
        assertEquals(x, s.players.getValue("seller").position.tileX, "a stall owner cannot move")
        assertTrue(s.playerAttack("seller").isEmpty())
        assertTrue(s.initiateTrade("buyer", "seller").isEmpty())
        s.say("seller", "/stall close")
        assertFalse(s.isStalled("seller"))
        s.say("buyer", "/stall buy sam 1")
        assertEquals(0, s.count("buyer", 2))
        s.movePlayer("seller", Direction.RIGHT)
        assertTrue(s.players.getValue("seller").position.tileX != x, "free to move again")
    }

    @Test
    fun `listing rules refuse bound items bad prices and a missing slot`() {
        val s = sim()
        s.say("seller", "/stall add 2 20")
        s.say("seller", "/stall add 1 0")
        s.say("seller", "/stall add 9 20")
        s.say("seller", "/stall add 1 -4")
        s.say("seller", "/stall open")
        assertFalse(s.isStalled("seller"), "nothing legal was listed, so nothing opens")
        s.say("seller", "/stall add 1 20")
        s.say("seller", "/stall add 1 30")
        assertEquals(1, s.stalls.getValue("seller").listings.size, "a slot is listed once")
    }

    @Test
    fun `goods that left the bag drop off the stall and buyers need money and reach`() {
        val s = sim()
        s.openPotionStall()
        s.players["buyer"] = s.players.getValue("buyer").copy(position = WorldPosition.fromTile(1, 18, 18, Direction.DOWN))
        s.say("buyer", "/stall buy sam 1")
        assertEquals(0, s.count("buyer", 2), "out of reach")
        s.players["buyer"] = s.players.getValue("buyer").copy(position = WorldPosition.fromTile(1, 7, 5, Direction.DOWN), inventory = Inventory())
        s.say("buyer", "/stall buy sam 1")
        assertEquals(0, s.count("buyer", 2), "no silver")
        s.players["seller"] = s.players.getValue("seller").copy(inventory = Inventory())
        s.players["buyer"] = s.players.getValue("buyer").copy(inventory = Inventory().addItem(silver, 500).updatedInventory)
        s.say("buyer", "/stall buy sam 1")
        assertEquals(500, s.count("buyer", 1), "the potions were gone, so nothing was charged")
        assertTrue(s.stalls.getValue("seller").listings.isEmpty())
    }

    @Test
    fun `a stall closes when its owner leaves or dies and maps can be restricted`() {
        val s = sim()
        s.openPotionStall()
        s.removePlayer("seller")
        assertTrue(s.stalls.isEmpty())

        val restricted = sim(StallDefinition(mapIds = setOf(2)))
        restricted.say("seller", "/stall add 1 20")
        restricted.say("seller", "/stall open")
        assertFalse(restricted.isStalled("seller"), "stalls are only allowed on map 2")

        val off = sim(null)
        off.say("seller", "/stall add 1 20")
        assertTrue(off.stalls.isEmpty())
    }

    @Test
    fun `the stall section is validated`() {
        val classic = MorganaClassic.definition
        assertTrue(GameDefinitionValidator.validate(classic.copy(stall = StallDefinition())).isEmpty())
        assertFalse(GameDefinitionValidator.validate(classic.copy(stall = StallDefinition(maxListings = 0))).isEmpty())
        assertFalse(GameDefinitionValidator.validate(classic.copy(stall = StallDefinition(rangeTiles = 0))).isEmpty())
    }

    private fun List<WorldSimulationEvent>.views(viewer: String) =
        filterIsInstance<WorldSimulationEvent.StallViewed>().filter { it.viewerId == viewer }.map { it.view }

    @Test
    fun `the goods of a stall are sent to whoever looks at it, and refreshed after a purchase`() {
        val s = sim()
        val own = s.say("seller", "/stall add 1 20 6").views("seller").single()
        assertTrue(own.own)
        assertFalse(own.open)
        assertEquals(listOf(1), own.goods.map { it.number })
        s.say("seller", "/stall open Sam's Potions")
        val seen = s.say("buyer", "/stall view sam").views("buyer").single()
        assertFalse(seen.own)
        assertTrue(seen.open)
        assertEquals("Sam's Potions", seen.title)
        assertEquals("Sam", seen.ownerName)
        assertEquals(listOf(2 to 6), seen.goods.map { it.itemId to it.amount })
        assertEquals(20, seen.goods.single().unitPrice)
        val after = s.say("buyer", "/stall buy sam 1 4")
        assertEquals(2, after.views("buyer").single().goods.single().amount, "the buyer sees what is left")
        assertEquals(2, after.views("seller").single().goods.single().amount, "so does the seller")
        assertTrue(s.say("seller", "/stall close").views("seller").single().goods.isEmpty(), "closing sends an empty stall so the window clears")
    }
}
