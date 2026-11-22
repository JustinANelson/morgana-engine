package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.GuildDocument
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GuildBankDefinition
import com.morgana.domain.game.GuildBankUpgrade
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.social.GuildBankLog
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The guild vault: members deposit at the Quartermaster, the founder sets who may withdraw, and every move is logged. */
class GuildBankTest {
    private val salve = ItemDefinition(id = 1, name = "Salve", type = ItemType.CONSUMABLE, maxStack = 99)
    private val charm = ItemDefinition(id = 2, name = "Soulbound Charm", type = ItemType.CONSUMABLE, maxStack = 1, boundOnPickup = true)
    private val keeper = NpcDefinition(id = 7, name = "Quartermaster", behavior = NpcBehavior.GUILD_BANKER, vitals = mapOf("hp" to 100))

    private fun sim(config: GuildBankDefinition? = GuildBankDefinition(logEntries = 3)): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(5L), game = CompiledGame.compile(MorganaClassic.definition.copy(guildBank = config)))
        s.registerMap(MapDefinition(id = 1, name = "Hall", width = 20, height = 20))
        s.items[1] = salve
        s.items[2] = charm
        s.npcs[7] = keeper
        s.mapNpcs[1] = mutableListOf(MapNpcInstance("q", 0, 7, 1, 10, 10, 10, 10, vitals = mapOf("hp" to VitalState(100, 100))))
        s.guilds["g1"] = GuildDocument("g1", "Hallkeepers", "HK", "boss", mapOf("boss" to "Founder", "off" to "Officer", "mem" to "Member"))
        for (id in listOf("boss", "off", "mem")) s.hero(id, "g1")
        return s
    }

    private fun WorldSimulation.hero(id: String, guild: String?, x: Int = 11) {
        val base = CharacterState(
            id = id, accountId = id, name = id, position = WorldPosition.fromTile(1, x, 10, Direction.LEFT), guildId = guild,
            guildRank = guilds[guild ?: ""]?.memberRanks?.get(id) ?: ""
        ).recalculateVitals(game, items, refill = true)
        spawnOrUpdatePlayer(base.copy(inventory = base.inventory.addItem(salve, 20).updatedInventory.addItem(charm, 1).updatedInventory))
    }

    private fun WorldSimulation.bag(id: String, itemId: Int) = players.getValue(id).inventory.totalCountOf(itemId)
    private fun WorldSimulation.vault(itemId: Int) = guilds.getValue("g1").bank.totalCountOf(itemId)
    private fun WorldSimulation.slotOf(id: String, itemId: Int) = players.getValue(id).inventory.slots.indexOfFirst { it?.itemId == itemId }
    private fun List<WorldSimulationEvent>.said(text: String) = filterIsInstance<WorldSimulationEvent.ChatEmitted>().any { text in it.message }

    @Test
    fun `a member deposits at the Quartermaster and the vault and log record it`() {
        val s = sim()
        val ev = s.guildBankDeposit("mem", s.slotOf("mem", 1), 5)
        assertEquals(15, s.bag("mem", 1))
        assertEquals(5, s.vault(1))
        assertEquals(listOf("mem deposited 5 x Salve"), s.guilds.getValue("g1").bankLog.map { GuildBankLog.parse(it).second })
        assertTrue(ev.any { it is WorldSimulationEvent.GuildBankChanged }, "the server is told to save and show the vault")
    }

    @Test
    fun `only the allowed rank withdraws, and the founder can change who that is`() {
        val s = sim()
        s.guildBankDeposit("mem", s.slotOf("mem", 1), 10)
        val slot = s.guilds.getValue("g1").bank.slots.indexOfFirst { it?.itemId == 1 }
        val refused = s.guildBankWithdraw("mem", slot, 4)
        assertTrue(refused.said("Officer rank and above"), "a member is told who may withdraw")
        assertEquals(10, s.vault(1))
        s.guildBankWithdraw("off", slot, 4)
        assertEquals(6, s.vault(1), "an officer may, by default")
        assertEquals(24, s.bag("off", 1))
        assertTrue(s.setGuildBankAccess("off", "Member").said("Only the founder"))
        s.setGuildBankAccess("boss", "Member")
        s.guildBankWithdraw("mem", slot, 2)
        assertEquals(4, s.vault(1), "now members may too")
        s.setGuildBankAccess("boss", "Founder")
        assertTrue(s.guildBankWithdraw("off", slot, 1).said("the founder may withdraw"))
    }

    @Test
    fun `the log keeps only the newest entries`() {
        val s = sim()
        repeat(5) { s.guildBankDeposit("mem", s.slotOf("mem", 1), 1) }
        assertEquals(3, s.guilds.getValue("g1").bankLog.size)
    }

    @Test
    fun `bound items, strangers, distance, a full bank and a missing config all stop the move`() {
        val s = sim()
        assertTrue(s.guildBankDeposit("mem", s.slotOf("mem", 2), 1).said("bound to you"))
        assertEquals(0, s.vault(2))
        s.hero("stranger", null)
        assertTrue(s.guildBankDeposit("stranger", s.slotOf("stranger", 1), 1).said("guild members only"))
        s.hero("far", "g1", x = 18)
        s.guilds["g1"] = s.guilds.getValue("g1").copy(memberRanks = s.guilds.getValue("g1").memberRanks + ("far" to "Member"))
        assertTrue(s.guildBankDeposit("far", s.slotOf("far", 1), 1).said("Quartermaster"))
        assertEquals(0, s.vault(1))
        // a vault with no free slot refuses a new kind of item
        val bread = ItemDefinition(id = 3, name = "Bread", type = ItemType.CONSUMABLE, maxStack = 99)
        s.items[3] = bread
        s.spawnOrUpdatePlayer(s.players.getValue("mem").let { it.copy(inventory = it.inventory.addItem(bread, 1).updatedInventory) })
        val packed = (0 until s.guilds.getValue("g1").bank.maxSlots).fold(s.guilds.getValue("g1").bank) { bank, i ->
            bank.addItem(ItemDefinition(id = 100 + i, name = "Junk$i", type = ItemType.CONSUMABLE), 1).updatedInventory
        }
        s.guilds["g1"] = s.guilds.getValue("g1").copy(bank = packed)
        assertTrue(s.guildBankDeposit("mem", s.slotOf("mem", 3), 1).said("vault is full"))
        assertEquals(1, s.bag("mem", 3), "the bread stays in the bag")
        val off = sim(config = null)
        assertTrue(off.guildBankDeposit("mem", off.slotOf("mem", 1), 1).isEmpty(), "without a guildBank section the vault does nothing")
    }

    private val marks = ItemDefinition(id = 9, name = "Marks", type = ItemType.CURRENCY, maxStack = 9999)

    @Test
    fun `log lines carry the world tick and show a time`() {
        val s = sim()
        s.currentTick = 36_000L // half a day at 20 ticks a second
        s.guildBankDeposit("mem", s.slotOf("mem", 1), 5)
        val line = s.guilds.getValue("g1").bankLog.single()
        assertEquals(36_000L, GuildBankLog.parse(line).first)
        assertEquals("Day 1 00:30  mem deposited 5 x Salve", GuildBankLog.display(line))
        assertEquals("an old line", GuildBankLog.display("an old line"), "lines saved before times existed still show")
    }

    @Test
    fun `a daily limit caps what a rank takes out and resets the next day`() {
        val s = sim(GuildBankDefinition(logEntries = 20, withdrawLimits = mapOf("Member" to 5), limitDayTicks = 1000L, defaultWithdrawRank = "Member"))
        s.guildBankDeposit("boss", s.slotOf("boss", 1), 20)
        val slot = s.guilds.getValue("g1").bank.slots.indexOfFirst { it?.itemId == 1 }
        s.guildBankWithdraw("mem", slot, 4)
        s.guildBankWithdraw("mem", slot, 4)
        assertEquals(5, s.bag("mem", 1) - 20, "the second take is cut to the one item left of the day's 5")
        assertTrue(s.guildBankWithdraw("mem", slot, 1).said("limit of 5"))
        s.guildBankWithdraw("boss", slot, 10)
        assertEquals(5, s.vault(1), "the founder took 10 in one go: no limit")
        s.currentTick = 1000L
        s.guildBankWithdraw("mem", slot, 3)
        assertEquals(8, s.bag("mem", 1) - 20, "a new day, a new allowance")
    }

    @Test
    fun `a deposit into a chosen tab lands only in that tab`() {
        val s = sim(GuildBankDefinition(tabSlots = 8))
        s.guildBankDeposit("mem", s.slotOf("mem", 1), 3, tab = 1)
        val slot = s.guilds.getValue("g1").bank.slots.indexOfFirst { it?.itemId == 1 }
        assertTrue(slot in 8..15, "tab 1 is slots 8 to 15, got $slot")
        assertTrue(s.guildBankDeposit("mem", s.slotOf("mem", 1), 1, tab = 99).said("tab is full"), "a tab that does not exist refuses")
    }

    @Test
    fun `the founder buys vault upgrades from the vault's own Marks`() {
        val config = GuildBankDefinition(currencyItemId = 9, upgrades = listOf(GuildBankUpgrade(cost = 50, extraSlots = 24), GuildBankUpgrade(cost = 100, extraSlots = 24)))
        val s = sim(config)
        s.items[9] = marks
        s.spawnOrUpdatePlayer(s.players.getValue("boss").let { it.copy(inventory = it.inventory.addItem(marks, 120).updatedInventory) })
        assertTrue(s.upgradeGuildBank("off").said("Only the founder"))
        assertTrue(s.upgradeGuildBank("boss").said("costs 50 Marks"), "an empty vault cannot pay")
        s.guildBankDeposit("boss", s.slotOf("boss", 9), 120)
        assertEquals(120, s.vault(9), "the vault's Marks are the guild balance")
        s.upgradeGuildBank("boss")
        val g = s.guilds.getValue("g1")
        assertEquals(72, g.bank.maxSlots)
        assertEquals(1, g.bankLevel)
        assertEquals(70, s.vault(9))
        assertTrue(s.upgradeGuildBank("boss").said("costs 100 Marks"), "the second upgrade needs more than is left")
        assertEquals(1, s.guilds.getValue("g1").bankLevel, "a refused upgrade changes nothing")
    }
}
