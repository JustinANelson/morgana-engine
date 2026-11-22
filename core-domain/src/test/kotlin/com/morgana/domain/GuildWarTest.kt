package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.GuildDocument
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.GuildWarDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.systems.guildAreaHolderId
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import com.morgana.domain.access.AccessLevel
import com.morgana.domain.validation.ContentCatalog
import com.morgana.domain.validation.ContentValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GuildWarTest {
    private val pole = NpcDefinition(id = 9, name = "Guild Pole", behavior = NpcBehavior.PASSIVE, vitals = mapOf("hp" to 100_000), attributes = mapOf("str" to 1, "agi" to 1, "vit" to 1), guildWarRole = "POLE")
    private val gate = NpcDefinition(id = 8, name = "Guild Gate", behavior = NpcBehavior.PASSIVE, vitals = mapOf("hp" to 20), attributes = mapOf("str" to 1, "agi" to 1, "vit" to 1), guildWarRole = "GATE")
    private val prize = ItemDefinition(id = 50, name = "War Prize", type = ItemType.MATERIAL, maxStack = 99)

    private fun guild(id: String) = GuildDocument(id = id, name = "Guild $id", tag = id.uppercase(), founderCharacterId = "p$id", memberRanks = mapOf("p$id" to "Founder"))

    private fun sim(war: GuildWarDefinition? = GuildWarDefinition(mapId = 1, intervalTicks = 0, durationTicks = 100_000, rewardItems = mapOf(50 to 2))): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(3L), game = CompiledGame.compile(MorganaClassic.definition.copy(guildWar = war)))
        s.registerMap(MapDefinition(id = 1, name = "Guild Area", width = 20, height = 20))
        s.items[50] = prize
        s.npcs[9] = pole
        s.npcs[8] = gate
        for (id in listOf("a", "b")) s.guilds[id] = guild(id)
        fun hero(id: String, y: Int, guildId: String?) = s.spawnOrUpdatePlayer(
            CharacterState(
                id = id, accountId = id, name = id, position = WorldPosition.fromTile(1, 5, y, Direction.RIGHT),
                guildId = guildId, accessLevel = if (id == "staff") AccessLevel.OWNER else AccessLevel.PLAYER
            )
        )
        hero("pa", 2, "a"); hero("pb", 4, "b"); hero("px", 6, null); hero("staff", 8, null)
        s.mapNpcs[1] = mutableListOf(
            MapNpcInstance("pole", 0, 9, 1, 6, 2, 6, 2, vitals = mapOf("hp" to VitalState(100_000, 100_000))),
            MapNpcInstance("gate", 1, 8, 1, 6, 8, 6, 8, vitals = mapOf("hp" to VitalState(20, 20)))
        )
        s.mapNpcs.getValue(1).add(MapNpcInstance("pole2", 2, 9, 1, 6, 4, 6, 4, vitals = mapOf("hp" to VitalState(100_000, 100_000))))
        s.mapNpcs.getValue(1).add(MapNpcInstance("pole3", 3, 9, 1, 6, 6, 6, 6, vitals = mapOf("hp" to VitalState(100_000, 100_000))))
        return s
    }

    private fun WorldSimulation.hit(id: String): List<WorldSimulationEvent> {
        repeat(120) { stepTick() }
        return playerAttack(id)
    }

    private fun WorldSimulation.poleHp() = mapNpcs.getValue(1).first { it.instanceId == "pole" }.vitals.getValue("hp").cur

    @Test
    fun `the pole cannot be harmed outside a war and never falls during one`() {
        val s = sim()
        s.hit("pa")
        assertEquals(100_000, s.poleHp())
        assertTrue(s.guildWar.damage.isEmpty(), "no war, no score")

        s.executeAdminSlashCommand("staff", "/guildwar start")
        assertTrue(s.guildWar.active)
        s.hit("pa")
        assertEquals(100_000, s.poleHp(), "the pole keeps its health")
        assertTrue((s.guildWar.damage["a"] ?: 0L) > 0L, "the blow is counted for the guild")
        assertEquals(setOf("a"), s.guildWar.damage.keys)
    }

    @Test
    fun `players without a guild and ordinary players cannot start a war`() {
        val s = sim()
        s.executeAdminSlashCommand("pa", "/guildwar start")
        assertFalse(s.guildWar.active, "only staff may start a war")
        s.executeAdminSlashCommand("staff", "/guildwar start")
        s.executeAdminSlashCommand("staff", "/guildwar start")
        assertTrue(s.guildWar.active)

        // px has no guild: tries pole3 at y=6
        s.hit("px")
        assertTrue(s.guildWar.damage.isEmpty())
    }

    @Test
    fun `the guild with the most pole damage wins takes the area and is rewarded`() {
        val s = sim()
        s.executeAdminSlashCommand("staff", "/guildwar start")
        s.hit("pa")
        repeat(3) { s.hit("pb") }
        val a = s.guildWar.damage.getValue("a")
        val b = s.guildWar.damage.getValue("b")
        assertTrue(b > a, "b hit three times to a's one ($b vs $a)")

        val out = mutableListOf<WorldSimulationEvent>()
        out += s.executeAdminSlashCommand("staff", "/guildwar end")
        assertEquals("b", s.guildAreaHolderId())
        assertTrue(out.any { it is WorldSimulationEvent.GuildWarResult })
        assertEquals(2, s.players.getValue("pb").inventory.totalCountOf(50), "the winners are rewarded")
        assertEquals(0, s.players.getValue("pa").inventory.totalCountOf(50))
        assertFalse(s.guildWar.active)

        // The holder cannot attack its own pole in the next war.
        s.executeAdminSlashCommand("staff", "/guildwar start")
        s.hit("pb")
        assertNull(s.guildWar.damage["b"])
        s.hit("pa")
        assertTrue(s.guildWar.damage.containsKey("a"))
    }

    @Test
    fun `a challenger who out-damages the holder takes the area from it`() {
        val s = sim()
        s.guilds["a"] = s.guilds.getValue("a").copy(holdsGuildArea = true)
        s.executeAdminSlashCommand("staff", "/guildwar start")
        s.hit("pb")
        s.executeAdminSlashCommand("staff", "/guildwar end")
        assertEquals("b", s.guildAreaHolderId())
        assertFalse(s.guilds.getValue("a").holdsGuildArea)
        assertEquals(1, s.guilds.values.count { it.holdsGuildArea })
    }

    @Test
    fun `a war nobody scores in changes nothing`() {
        val s = sim()
        s.guilds["a"] = s.guilds.getValue("a").copy(holdsGuildArea = true)
        s.executeAdminSlashCommand("staff", "/guildwar start")
        s.executeAdminSlashCommand("staff", "/guildwar end")
        assertEquals("a", s.guildAreaHolderId())
    }

    @Test
    fun `gates fall to attackers during a war and stand again when it starts and ends`() {
        val s = sim()
        // The gate only takes blows from a guild member while a war runs.
        s.players["pa"] = s.players.getValue("pa").copy(position = WorldPosition.fromTile(1, 5, 8, Direction.RIGHT))
        s.hit("pa")
        assertEquals(20, s.mapNpcs.getValue(1).first { it.instanceId == "gate" }.vitals.getValue("hp").cur, "untouchable in peacetime")
        s.executeAdminSlashCommand("staff", "/guildwar start")
        repeat(6) { s.hit("pa") }
        assertFalse(s.mapNpcs.getValue(1).first { it.instanceId == "gate" }.isAlive, "the gate is broken")
        s.executeAdminSlashCommand("staff", "/guildwar end")
        assertTrue(s.mapNpcs.getValue(1).first { it.instanceId == "gate" }.isAlive, "the gate is rebuilt")
    }

    @Test
    fun `a scheduled war starts and ends on its own`() {
        val s = sim(GuildWarDefinition(mapId = 1, intervalTicks = 100, durationTicks = 40))
        repeat(99) { s.stepTick() }
        assertFalse(s.guildWar.active)
        s.stepTick()
        assertTrue(s.guildWar.active)
        repeat(39) { s.stepTick() }
        assertTrue(s.guildWar.active)
        s.stepTick()
        assertFalse(s.guildWar.active)
    }

    @Test
    fun `a game without the section has no war`() {
        val s = sim(null)
        s.executeAdminSlashCommand("staff", "/guildwar start")
        assertFalse(s.guildWar.active)
    }

    @Test
    fun `the guildWar section and the npc role are validated`() {
        val classic = MorganaClassic.definition
        assertTrue(GameDefinitionValidator.validate(classic.copy(guildWar = GuildWarDefinition())).isEmpty())
        assertFalse(GameDefinitionValidator.validate(classic.copy(guildWar = GuildWarDefinition(durationTicks = 0))).isEmpty())
        assertFalse(GameDefinitionValidator.validate(classic.copy(guildWar = GuildWarDefinition(intervalTicks = 100, durationTicks = 200))).isEmpty())
        assertFalse(GameDefinitionValidator.validate(classic.copy(guildWar = GuildWarDefinition(minDamage = 0))).isEmpty())
        val bad = ContentValidator.validate(ContentCatalog(npcs = mapOf(9 to pole.copy(guildWarRole = "TOWER"))), classic)
        assertTrue(bad.errors.any { it.pointer == "/guildWarRole" }, "got ${bad.issues}")
        assertTrue(ContentValidator.validate(ContentCatalog(npcs = mapOf(9 to pole)), classic).errors.none { it.pointer == "/guildWarRole" })
    }
}
