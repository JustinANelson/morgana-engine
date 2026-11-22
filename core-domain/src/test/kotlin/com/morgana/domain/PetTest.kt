package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.PetTraits
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.systems.petDamage
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Pets: adopting, following across maps, helping in fights, growing, naming and going home. */
class PetTest {
    private val fox = NpcDefinition(
        id = 30, name = "Fox", behavior = NpcBehavior.FRIENDLY_ROAM, vitals = mapOf("hp" to 50), attackDamage = 10, attackSpeedTicks = 5, moveSpeedTicks = 4,
        pet = PetTraits(maxLevel = 3, xpPerLevel = 10, xpSharePercent = 100, damagePerLevelPercent = 50, followDistance = 2)
    )
    private val rat = NpcDefinition(id = 31, name = "Rat", behavior = NpcBehavior.ATTACK_ON_SIGHT, vitals = mapOf("hp" to 25), attackDamage = 1, xpReward = 12L)
    private val whistle = ItemDefinition(id = 70, name = "Fox Whistle", type = ItemType.CONSUMABLE, maxStack = 5, adoptPetNpcId = 30)

    private fun world(): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(1L), game = MorganaClassic.COMPILED)
        sim.npcs[30] = fox
        sim.npcs[31] = rat
        sim.items[70] = whistle
        sim.registerMap(MapDefinition(id = 1, name = "Field", width = 40, height = 20))
        sim.registerMap(MapDefinition(id = 2, name = "Wood", width = 20, height = 20))
        val hero = CharacterState(
            id = "h", accountId = "a", name = "h", attributes = mapOf("str" to 10, "def" to 10, "agi" to 10, "vit" to 10),
            position = WorldPosition.fromTile(1, 5, 5, Direction.DOWN)
        ).recalculateVitals(sim.game, sim.items, refill = true)
        sim.spawnOrUpdatePlayer(hero.copy(inventory = hero.inventory.addItem(whistle, 2).updatedInventory))
        return sim
    }

    private fun WorldSimulation.slot() = players.getValue("h").inventory.slots.indexOfFirst { it?.itemId == 70 }
    private fun WorldSimulation.say(line: String) = executeAdminSlashCommand("h", line)
    private fun told(events: List<WorldSimulationEvent>, text: String) = events.filterIsInstance<WorldSimulationEvent.ChatEmitted>().any { text in it.message }
    private fun WorldSimulation.foxes() = mapNpcs.values.flatten().filter { it.ownerId == "h" }
    private fun WorldSimulation.adopt() = useInventorySlot("h", slot())

    @Test
    fun `a pet item adopts the pet once and brings it to the owner`() {
        val sim = world()
        val events = sim.adopt()
        assertTrue(told(events, "Fox joins you"))
        assertEquals(1, sim.players.getValue("h").pets.size)
        assertEquals(30, sim.players.getValue("h").activePetNpcId)
        assertEquals(1, sim.players.getValue("h").inventory.totalCountOf(70), "one whistle is used up")
        sim.stepTick()
        val pet = sim.foxes().single()
        assertEquals(1, pet.mapId)
        assertTrue(kotlin.math.abs(pet.tileX - 5) + kotlin.math.abs(pet.tileY - 5) <= 1, "it appears at the owner's side")
        assertTrue(told(sim.adopt(), "already have Fox"), "a second whistle cannot adopt it again")
        assertEquals(1, sim.players.getValue("h").inventory.totalCountOf(70), "and is not used up")
    }

    @Test
    fun `the pet follows the owner and crosses maps with them`() {
        val sim = world()
        sim.adopt(); sim.stepTick()
        sim.spawnOrUpdatePlayer(sim.players.getValue("h").copy(position = WorldPosition.fromTile(1, 14, 5, Direction.DOWN)))
        repeat(60) { sim.stepTick() }
        val near = sim.foxes().single()
        assertTrue(kotlin.math.abs(near.tileX - 14) + kotlin.math.abs(near.tileY - 5) <= 3, "it kept up: ${near.tileX},${near.tileY}")
        sim.spawnOrUpdatePlayer(sim.players.getValue("h").copy(position = WorldPosition.fromTile(2, 8, 8, Direction.DOWN)))
        sim.stepTick()
        assertEquals(2, sim.foxes().single().mapId, "it came along")
        assertTrue(sim.mapNpcs.getValue(1).none { it.ownerId == "h" }, "and left the first map")
    }

    @Test
    fun `it fights what hunts its owner, the kill is the owner's, and the pet grows`() {
        val sim = world()
        sim.adopt(); sim.stepTick()
        val list = sim.mapNpcs.getOrPut(1) { mutableListOf() }
        list.add(MapNpcInstance("rat1", 0, 31, 1, 7, 5, 7, 5, vitals = mapOf("hp" to VitalState(25, 25)), targetEntityId = "h"))
        val xpBefore = sim.players.getValue("h").xp
        repeat(80) { sim.stepTick() }
        val rat = list.first { it.instanceId == "rat1" }
        assertTrue(!rat.isAlive, "the pet and its owner's rat are no longer fighting: the rat is dead")
        assertTrue(sim.players.getValue("h").xp > xpBefore, "the kill's XP goes to the owner")
        val st = sim.players.getValue("h").pets.getValue(30)
        assertEquals(2, st.level, "12 XP at 100% share passes the 10 needed for level 2")
        assertEquals(2, st.xp)
        assertEquals(15, sim.petDamage(fox, 2), "level 2 hits 50% harder")
        assertEquals(20, sim.petDamage(fox, 3))
    }

    @Test
    fun `commands list, summon, dismiss and name the pet`() {
        val sim = world()
        assertTrue(told(sim.say("/pet"), "no pets"))
        sim.adopt()
        sim.say("/pet dismiss")
        sim.stepTick()
        assertTrue(sim.foxes().isEmpty(), "dismissed pets go home")
        assertTrue(told(sim.say("/pet summon fox"), "comes to your side"))
        sim.stepTick()
        assertEquals(1, sim.foxes().size)
        assertTrue(told(sim.say("/pet name Rusty"), "called Rusty"))
        sim.stepTick()
        assertEquals("Rusty", sim.foxes().single().displayName)
        assertTrue(told(sim.say("/pet"), "Rusty the Fox"))
        sim.removePlayer("h")
        sim.stepTick()
        assertNull(sim.mapNpcs.values.flatten().firstOrNull { it.ownerId == "h" }, "a pet does not outlive its owner's visit")
    }
}
