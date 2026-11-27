package com.morgana.domain

import com.morgana.domain.combat.ActionCombatModule
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.ActiveStatusEffect
import com.morgana.domain.stats.StatusEffectType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Telegraphed monster strikes: a warning first, then damage only to whoever is still in the zone. */
class TelegraphTest {
    private fun monster(kind: String, range: Int = 1, length: Int = 0) = NpcDefinition(
        id = 9, name = "Brute", behavior = NpcBehavior.ATTACK_ON_SIGHT, level = 1, vitals = mapOf("hp" to 100_000), attackDamage = 20, attackSpeedTicks = 8,
        aggroRangeTiles = 20, leashRangeTiles = 40, moveSpeedTicks = 10, telegraphTicks = 12, telegraphKind = kind, telegraphRadiusPixels = 44,
        telegraphLengthPixels = length, telegraphRangeTiles = range, telegraphDamagePercent = 100
    )

    private val seen = ArrayList<WorldSimulationEvent>()

    private fun world(def: NpcDefinition, playerTile: Pair<Int, Int> = 7 to 5): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(3L), game = MorganaClassic.COMPILED)
        sim.modules = ModuleFlags.ALL_ENABLED
        sim.combat = ActionCombatModule()
        sim.npcs[9] = def
        sim.registerMap(MapDefinition(id = 1, name = "Arena", width = 24, height = 16, npcSpawns = listOf(MapNpcSpawnSlot(0, 9, 6, 5))))
        sim.spawnOrUpdatePlayer(CharacterState(id = "ann", accountId = "a", name = "Ann", level = 10, position = WorldPosition.fromTile(1, playerTile.first, playerTile.second, Direction.LEFT)))
        seen.clear()
        return sim
    }

    private fun WorldSimulation.hp() = players.getValue("ann").vitals.getValue("hp").cur
    private fun WorldSimulation.run(n: Int) = repeat(n) { seen += stepTick() }
    private fun telegraphStarted() = seen.any { it is WorldSimulationEvent.SkillEffect && (it.effect == "telegraph_circle" || it.effect == "telegraph_line") }

    @Test
    fun `a slam warns first and hits a player who stays, not one who has moved away`() {
        val stay = world(monster("slam"))
        val full = stay.hp()
        var warnedWithoutDamage = false
        repeat(40) {
            stay.run(1)
            if (telegraphStarted() && stay.hp() == full) warnedWithoutDamage = true
        }
        assertTrue(warnedWithoutDamage, "the warning came before any damage")
        assertTrue(stay.hp() < full, "standing in the zone got hit")

        val dodge = world(monster("slam"))
        val full2 = dodge.hp()
        var moved = false
        repeat(40) {
            dodge.run(1)
            if (!moved && telegraphStarted()) {
                // step out of the zone (11 tiles away) as the warning shows
                dodge.spawnOrUpdatePlayer(dodge.players.getValue("ann").copy(position = WorldPosition.fromTile(1, 17, 10, Direction.LEFT)))
                moved = true
            }
        }
        assertTrue(moved)
        assertEquals(full2, dodge.hp(), "leaving the zone avoided the hit")
    }

    @Test
    fun `a lunge dashes along its line and hits a player in the line but not one beside it`() {
        val inLine = world(monster("lunge", range = 4, length = 150), playerTile = 9 to 5)
        val full = inLine.hp()
        inLine.run(60)
        assertTrue(telegraphStarted())
        assertTrue(inLine.hp() < full, "in the line: hit")
        val beside = world(monster("lunge", range = 4, length = 150), playerTile = 9 to 5)
        var moved = false
        var sinceMoved = 0
        while (sinceMoved < 14 && seen.size < 10_000) {
            beside.run(1)
            if (moved) sinceMoved++
            if (!moved && telegraphStarted()) {
                beside.spawnOrUpdatePlayer(beside.players.getValue("ann").copy(position = WorldPosition.fromTile(1, 9, 9, Direction.LEFT)))
                moved = true
            }
        }
        // 14 ticks after the player stepped aside is just past the strike (12 ticks of wind-up): it landed on nobody.
        assertEquals(beside.players.getValue("ann").vitals.getValue("hp").max, beside.hp(), "stepping sideways out of the line avoided it")
    }

    @Test
    fun `a stun interrupts a wind-up and cancels its warning`() {
        val sim = world(monster("slam"))
        var stunned = false
        repeat(40) {
            sim.run(1)
            if (!stunned && telegraphStarted()) {
                val npcs = sim.mapNpcs.getValue(1)
                npcs[0] = npcs[0].copy(statusEffects = listOf(ActiveStatusEffect("x", StatusEffectType.STUN, "ann", 0, emptyMap(), 100)))
                stunned = true
            }
        }
        assertTrue(seen.any { it is WorldSimulationEvent.SkillEffect && it.effect == "telegraph_cancel" })
        assertEquals(sim.players.getValue("ann").vitals.getValue("hp").max, sim.hp(), "a stunned monster never landed it")
    }
}
