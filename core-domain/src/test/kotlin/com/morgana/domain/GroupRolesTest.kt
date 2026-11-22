package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.ActiveStatusEffect
import com.morgana.domain.stats.StatusEffectType
import com.morgana.domain.stats.VitalState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Taunt, cleanse and group heals: the three support and tank roles. */
class GroupRolesTest {
    private val taunt = SpellDefinition(
        id = 1, name = "Roar", targetType = SpellTargetType.AOE_SELF_CENTERED, aoeRadiusTiles = 8, basePower = 1,
        statusEffectType = StatusEffectType.TAUNT, statusMagnitude = 1, statusDurationTicks = 100, cooldownTicks = 10, animationId = 0
    )
    private val groupHeal = SpellDefinition(
        id = 2, name = "Tide", targetType = SpellTargetType.SELF, groupTarget = true, isHealing = true, basePower = 60, rangeTiles = 5,
        cooldownTicks = 10, animationId = 0
    )
    private val cleanse = SpellDefinition(
        id = 3, name = "Purify", targetType = SpellTargetType.SELF, allyTargetable = true, cleanses = true, rangeTiles = 6,
        cooldownTicks = 10, animationId = 0
    )
    private val wolf = NpcDefinition(
        id = 1, name = "Wolf", behavior = NpcBehavior.ATTACK_ON_SIGHT, vitals = mapOf("hp" to 100_000), attributes = mapOf("str" to 1, "agi" to 1, "vit" to 1),
        aggroRangeTiles = 20, attackDamage = 1
    )

    private fun sim(): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(2L), game = CompiledGame.compile(MorganaClassic.definition))
        s.registerMap(MapDefinition(id = 1, name = "Field", width = 60, height = 10))
        for (sp in listOf(taunt, groupHeal, cleanse)) s.spells[sp.id] = sp
        s.npcs[1] = wolf
        return s
    }

    private fun WorldSimulation.hero(id: String, x: Int, party: String? = "pt", hp: Int? = null, effects: List<ActiveStatusEffect> = emptyList(), alive: Boolean = true): String {
        val base = CharacterState(
            id = id, accountId = id, name = id, position = WorldPosition.fromTile(1, x, 4, Direction.RIGHT), partyId = party,
            knownSpellIds = listOf(1, 2, 3), statusEffects = effects
        ).recalculateVitals(game, items, refill = true)
        val max = base.vitals.getValue("hp").max
        val life = if (!alive) 0 else hp ?: max
        spawnOrUpdatePlayer(base.copy(vitals = base.vitals + ("hp" to VitalState(life, max))))
        return id
    }

    private fun WorldSimulation.hp(id: String) = players.getValue(id).vitals.getValue("hp").cur
    private fun WorldSimulation.cast(id: String, spell: Int, target: String? = null) {
        players[id] = players.getValue(id).copy(spellCooldowns = emptyMap())
        playerCastSpell(id, spell, target, 0, 0)
    }

    private fun effect(type: StatusEffectType) = ActiveStatusEffect("e-${type.name}", type, "x", magnitude = 1, remainingTicks = 1000)

    @Test
    fun `a taunt pulls a monster off the nearer hero onto the taunter`() {
        val s = sim()
        s.hero("near", 11, party = null)
        s.hero("tank", 17, party = null)
        s.mapNpcs[1] = mutableListOf(MapNpcInstance("wolf", 0, 1, 1, 10, 4, 10, 4, vitals = mapOf("hp" to VitalState(100_000, 100_000))))
        s.stepTick()
        assertEquals("near", s.mapNpcs.getValue(1).first().targetEntityId, "without a taunt the wolf picks the first hero it finds")
        s.cast("tank", 1)
        s.stepTick()
        val wolfNow = s.mapNpcs.getValue(1).first()
        assertTrue(wolfNow.statusEffects.any { it.type == StatusEffectType.TAUNT }, "the roar marks the wolf")
        assertEquals("tank", wolfNow.targetEntityId, "the taunted wolf now fights the tank")
    }

    @Test
    fun `a taunter who has left the map holds nothing`() {
        val s = sim()
        s.hero("near", 11, party = null)
        s.hero("tank", 17, party = null)
        s.mapNpcs[1] = mutableListOf(MapNpcInstance("wolf", 0, 1, 1, 10, 4, 10, 4, vitals = mapOf("hp" to VitalState(100_000, 100_000))))
        s.cast("tank", 1)
        s.registerMap(MapDefinition(id = 2, name = "Elsewhere", width = 20, height = 10))
        s.players["tank"] = s.players.getValue("tank").copy(position = WorldPosition.fromTile(2, 5, 4, Direction.RIGHT))
        repeat(3) { s.stepTick() }
        assertEquals("near", s.mapNpcs.getValue(1).first().targetEntityId, "a taunter who has left the map no longer holds the wolf")
    }

    @Test
    fun `a group heal reaches the caster and allies in range only`() {
        val s = sim()
        s.hero("healer", 10, hp = 10)
        s.hero("ally", 13, hp = 10)
        s.hero("far", 30, hp = 10)
        s.hero("stranger", 12, party = null, hp = 10)
        s.hero("fallen", 11, hp = 0, alive = false)
        s.cast("healer", 2)
        assertTrue(s.hp("healer") > 10, "the caster is healed")
        assertTrue(s.hp("ally") > 10, "an ally within 5 tiles is healed")
        assertEquals(10, s.hp("far"), "an ally out of range is not")
        assertEquals(10, s.hp("stranger"), "someone outside the party is not")
        assertEquals(0, s.hp("fallen"), "a fallen ally is not healed back from zero")
    }

    @Test
    fun `a cleanse strips harmful statuses, keeps good ones, and finds the worst afflicted ally`() {
        val s = sim()
        s.hero("healer", 10)
        s.hero("mild", 11, effects = listOf(effect(StatusEffectType.DOT_POISON), effect(StatusEffectType.HASTE)))
        s.hero("worst", 12, effects = listOf(effect(StatusEffectType.DOT_BURN), effect(StatusEffectType.SLOW), effect(StatusEffectType.DOT_BLEED)))
        s.cast("healer", 3)
        assertTrue(s.players.getValue("worst").statusEffects.isEmpty(), "with no named target the cleanse goes to the worst afflicted ally")
        assertEquals(2, s.players.getValue("mild").statusEffects.size, "the others are untouched")
        s.cast("healer", 3, "mild")
        assertEquals(listOf(StatusEffectType.HASTE), s.players.getValue("mild").statusEffects.map { it.type }, "a named target loses only the harmful ones")
        assertFalse(s.players.getValue("healer").statusEffects.any { it.type.isNegative })
    }
}
