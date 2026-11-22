package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GuildWarDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.ActiveStatusEffect
import com.morgana.domain.stats.StatusEffectType
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FlyReflectTest {
    private val brute = NpcDefinition(
        id = 1, name = "Brute", behavior = NpcBehavior.ATTACK_ON_SIGHT, vitals = mapOf("hp" to 400), attackDamage = 8,
        attributes = mapOf("str" to 8, "agi" to 5, "vit" to 5), attackSpeedTicks = 8, aggroRangeTiles = 4, xpReward = 0
    )

    private fun effect(type: StatusEffectType, magnitude: Int = 0) =
        ActiveStatusEffect(id = "t-${type.name}", type = type, sourceEntityId = "p1", magnitude = magnitude, remainingTicks = 100_000)

    private fun sim(effects: List<ActiveStatusEffect>, moral: MapMoral = MapMoral.SAFE_ZONE, warMapId: Int = 0): WorldSimulation {
        val definition = MorganaClassic.definition.copy(guildWar = if (warMapId > 0) GuildWarDefinition(mapId = warMapId, intervalTicks = 0) else null)
        val s = WorldSimulation(SeededRandomSource(9L), game = CompiledGame.compile(definition))
        var map = MapDefinition(id = 1, name = "Field", width = 12, height = 12, moral = moral)
        map = map.withCell(6, 5, MapCell(attribute = TileAttribute.Blocked))
        s.registerMap(map)
        s.npcs[1] = brute
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p1", accountId = "a", name = "P", position = WorldPosition.fromTile(1, 5, 5, Direction.RIGHT),
                statusEffects = effects
            )
        )
        s.mapNpcs[1] = mutableListOf()
        return s
    }

    private fun WorldSimulation.hp() = players.getValue("p1").vitals.getValue("hp").cur
    private fun WorldSimulation.addBrute(x: Int, y: Int) {
        mapNpcs.getValue(1).add(MapNpcInstance("b1", 0, 1, 1, x, y, x, y, vitals = mapOf("hp" to VitalState(400, 400))))
    }

    @Test
    fun `a flier walks over a blocked tile and a walker does not`() {
        val walker = sim(emptyList())
        walker.movePlayer("p1", Direction.RIGHT)
        assertEquals(5, walker.players.getValue("p1").position.tileX, "the wall stops a walker")

        val flier = sim(listOf(effect(StatusEffectType.FLY)))
        flier.movePlayer("p1", Direction.RIGHT)
        assertEquals(6, flier.players.getValue("p1").position.tileX, "a flier crosses it")
    }

    @Test
    fun `flight cannot cross the wall of the guild war map`() {
        val flier = sim(listOf(effect(StatusEffectType.FLY)), warMapId = 1)
        flier.movePlayer("p1", Direction.RIGHT)
        assertEquals(5, flier.players.getValue("p1").position.tileX)
    }

    @Test
    fun `a monster cannot strike a flier in melee`() {
        val walker = sim(emptyList())
        walker.addBrute(4, 5)
        val flier = sim(listOf(effect(StatusEffectType.FLY)))
        flier.addBrute(4, 5)
        val before = flier.hp()
        repeat(60) { walker.stepTick(); flier.stepTick() }
        assertTrue(walker.hp() < before, "the monster hurts a walker")
        assertEquals(before, flier.hp(), "but cannot reach a flier")
    }

    @Test
    fun `a flier cannot be struck by a player's melee blow`() {
        val s = sim(emptyList(), moral = MapMoral.OPEN_PVP)
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p2", accountId = "b", name = "Q", position = WorldPosition.fromTile(1, 4, 5, Direction.RIGHT),
                statusEffects = listOf(effect(StatusEffectType.FLY))
            )
        )
        s.players["p1"] = s.players.getValue("p1").copy(position = WorldPosition.fromTile(1, 5, 5, Direction.LEFT))
        repeat(40) { s.stepTick() }
        val before = s.players.getValue("p2").vitals.getValue("hp").cur
        s.playerAttack("p1")
        assertEquals(before, s.players.getValue("p2").vitals.getValue("hp").cur)
    }

    @Test
    fun `reflect sends a share of melee damage back at the monster`() {
        val plain = sim(emptyList())
        plain.addBrute(4, 5)
        val mirror = sim(listOf(effect(StatusEffectType.REFLECT, 50)))
        mirror.addBrute(4, 5)
        repeat(60) { plain.stepTick(); mirror.stepTick() }
        fun brute(s: WorldSimulation) = s.mapNpcs.getValue(1).first().vitals.getValue("hp").cur
        assertEquals(400, brute(plain), "no reflection, no scratch")
        assertTrue(brute(mirror) < 400, "the monster is hurt by its own blows (${brute(mirror)})")
        val taken = 400 - brute(mirror)
        val dealt = mirror.players.getValue("p1").vitals.getValue("hp").max - mirror.hp()
        assertTrue(taken in (dealt * 40 / 100)..(dealt * 60 / 100 + 2), "about half comes back (took $taken of $dealt)")
    }
}
