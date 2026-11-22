package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.NpcScale
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.InstanceScaling
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.script.ScriptEffect
import com.morgana.domain.sim.InstanceSettings
import com.morgana.domain.sim.MapInstances
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Scaling dungeons: an instanced map's monsters grow tougher for the players who walk into it. */
class DungeonScalingTest {
    private val town = 1
    private val dungeon = 2
    private val wolf = NpcDefinition(id = 5, name = "Wolf", behavior = NpcBehavior.ATTACK_ON_SIGHT, level = 10, vitals = mapOf("hp" to 100), attackDamage = 20, xpReward = 40L)

    @Test
    fun `the scale grows with each member up to the cap and follows the party level when asked`() {
        val s = InstanceScaling(hpPercentPerMember = 50, attackPercentPerMember = 10, xpPercentPerMember = 20, maxMembers = 3)
        assertEquals(NpcScale(100, 100, 100, 0), s.scaleFor(1, 10, 10))
        assertEquals(NpcScale(150, 110, 120, 0), s.scaleFor(2, 10, 10))
        assertEquals(NpcScale(200, 120, 140, 0), s.scaleFor(3, 10, 10))
        assertEquals(NpcScale(200, 120, 140, 0), s.scaleFor(9, 10, 10), "players beyond the cap add nothing")
        val follow = InstanceScaling(hpPercentPerMember = 0, attackPercentPerMember = 0, levelFollowsMembers = true, percentPerLevel = 5, maxLevelShift = 6)
        assertEquals(NpcScale(125, 125, 100, 5), follow.scaleFor(1, 15, 10), "five levels above the monster: 25% tougher")
        assertEquals(NpcScale(70, 70, 100, -6), follow.scaleFor(1, 1, 10), "the shift is capped at six levels either way")
    }

    private fun world(scaling: InstanceScaling?): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(3L), game = MorganaClassic.COMPILED)
        sim.modules = ModuleFlags.ALL_ENABLED
        sim.instanceSettings = InstanceSettings(emptyTicks = 1000)
        sim.npcs[5] = wolf
        sim.registerMap(MapDefinition(id = town, name = "Town", width = 12, height = 12))
        sim.registerMap(
            MapDefinition(
                id = dungeon, name = "Crypt", width = 12, height = 12, instanced = true, scaling = scaling,
                npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 5, spawnX = 8, spawnY = 8))
            )
        )
        return sim
    }

    private fun WorldSimulation.hero(id: String, level: Int = 10) =
        spawnOrUpdatePlayer(CharacterState(id = id, accountId = "a-$id", name = id, level = level, position = WorldPosition.fromTile(town, 3, 5, Direction.RIGHT), partyId = "p1"))

    private fun WorldSimulation.enter(id: String) = applyScriptEffect(ScriptEffect.WarpPlayer(id, dungeon, 2, 2))
    private fun WorldSimulation.wolfIn(id: String) = mapNpcs.getValue(players.getValue(id).position.mapId).single()

    @Test
    fun `a party that walks in finds tougher monsters, and a lone player finds them as written`() {
        val sim = world(InstanceScaling(hpPercentPerMember = 50, attackPercentPerMember = 20))
        sim.hero("ann"); sim.hero("bob")
        sim.enter("ann")
        assertTrue(MapInstances.isInstanceId(sim.players.getValue("ann").position.mapId))
        assertEquals(100, sim.wolfIn("ann").vitals.getValue("hp").max, "alone: the monster is as written")
        assertEquals(NpcScale(), sim.wolfIn("ann").scale)
        sim.enter("bob")
        val w = sim.wolfIn("bob")
        assertEquals(150, w.vitals.getValue("hp").max, "a second player adds 50% life")
        assertEquals(150, w.vitals.getValue("hp").cur)
        assertEquals(24, sim.npcVariantAttack(w), "and 20% damage (20 -> 24)")
    }

    private fun WorldSimulation.npcVariantAttack(npc: com.morgana.domain.entity.MapNpcInstance): Int =
        (npcs.getValue(npc.npcId).attackDamage * (npcVariant(npc)?.attackPercent ?: 100)) / 100

    @Test
    fun `a monster that is already hurt keeps its life when more players arrive`() {
        val sim = world(InstanceScaling(hpPercentPerMember = 100))
        sim.hero("ann"); sim.hero("bob")
        sim.enter("ann")
        val id = sim.players.getValue("ann").position.mapId
        val hurt = sim.wolfIn("ann").let { it.copy(vitals = it.vitals + ("hp" to it.vitals.getValue("hp").copy(cur = 40))) }
        sim.mapNpcs.getValue(id)[0] = hurt
        sim.enter("bob")
        assertEquals(100, sim.wolfIn("bob").vitals.getValue("hp").max, "left as it was")
        assertEquals(40, sim.wolfIn("bob").vitals.getValue("hp").cur)
    }

    @Test
    fun `a map without scaling is never touched`() {
        val sim = world(null)
        sim.hero("ann"); sim.hero("bob")
        sim.enter("ann"); sim.enter("bob")
        assertNull(sim.wolfIn("bob").scale)
        assertEquals(100, sim.wolfIn("bob").vitals.getValue("hp").max)
    }

    @Test
    fun `a monster that comes back to life keeps the dungeon's scale`() {
        val sim = world(InstanceScaling(hpPercentPerMember = 100))
        sim.hero("ann"); sim.hero("bob")
        sim.enter("ann"); sim.enter("bob")
        val id = sim.players.getValue("ann").position.mapId
        val dead = sim.wolfIn("ann").let { it.copy(vitals = it.vitals + ("hp" to it.vitals.getValue("hp").copy(cur = 0)), respawnAtTick = 1L) }
        sim.mapNpcs.getValue(id)[0] = dead
        sim.currentTick = 5L
        sim.stepTick(50)
        assertEquals(200, sim.wolfIn("ann").vitals.getValue("hp").max, "back with the doubled life")
    }

    @Test
    fun `a difficulty tier multiplies the scale, and a player below its level gets a lower one`() {
        val tiers = listOf(
            com.morgana.domain.map.DifficultyTier("Normal"),
            com.morgana.domain.map.DifficultyTier("Heroic", hpPercent = 200, attackPercent = 150, xpPercent = 150, dropRatePercent = 130, minLevel = 20)
        )
        val s = InstanceScaling(hpPercentPerMember = 50, attackPercentPerMember = 0, tiers = tiers)
        assertEquals(1, s.tierFor(1, 25))
        assertEquals(0, s.tierFor(1, 12), "too low a level for Heroic: Normal")
        assertEquals(1, s.tierFor(7, 25), "a request past the last tier gets the last one")
        assertEquals(NpcScale(hpPercent = 300, attackPercent = 150, xpPercent = 150, levelBonus = 0, dropRatePercent = 130), s.scaleFor(2, 25, 10, tier = 1))
        assertEquals(NpcScale(hpPercent = 150), s.scaleFor(2, 25, 10, tier = 0))
    }

    @Test
    fun `the tier the opener asks for is fixed for the copy`() {
        val sim = world(InstanceScaling(hpPercentPerMember = 0, attackPercentPerMember = 0, tiers = listOf(
            com.morgana.domain.map.DifficultyTier("Normal"), com.morgana.domain.map.DifficultyTier("Heroic", hpPercent = 300))))
        sim.hero("ann", level = 30); sim.hero("bob")
        sim.spawnOrUpdatePlayer(sim.players.getValue("ann").copy(variables = mapOf("dungeon_tier" to 1)))
        sim.enter("ann")
        assertEquals(300, sim.wolfIn("ann").vitals.getValue("hp").max, "Heroic: triple life")
        sim.enter("bob")
        assertEquals(1, sim.instances.getValue(sim.players.getValue("bob").position.mapId).tier, "a later arrival shares the opener's tier")
        assertEquals(300, sim.wolfIn("bob").vitals.getValue("hp").max)
    }
}
