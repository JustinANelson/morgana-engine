package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.NpcDropEntry
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.ToolType
import com.morgana.domain.item.WeaponStyle
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.replay.InputRecorder
import com.morgana.domain.replay.ReplayLog
import com.morgana.domain.replay.ReplayPlayer
import com.morgana.domain.replay.SimInput
import com.morgana.domain.replay.StateHasher
import com.morgana.domain.sim.MovementRules
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase12ReplayTest {

    private val seed = 1234L

    private fun world(seed: Long = this.seed, slimeHp: Int = 35): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(seed), game = MorganaClassic.COMPILED)
        sim.items[1] = ItemDefinition(id = 1, name = "Gold", type = ItemType.CURRENCY, stackable = true, maxStack = 9999)
        sim.items[2] = ItemDefinition(
            id = 2, name = "Sword", type = ItemType.WEAPON, weaponStyle = WeaponStyle.MELEE, attackDamage = 12,
            attackSpeedTicks = 1, rangeTiles = 1, toolType = ToolType.NONE
        )
        sim.npcs[1] = NpcDefinition(
            id = 1, name = "Slime", behavior = NpcBehavior.PASSIVE, vitals = mapOf("hp" to slimeHp), attributes = mapOf("str" to 2, "def" to 2, "int" to 2, "agi" to 2, "vit" to 2),
            xpReward = 50L, drops = listOf(NpcDropEntry(itemId = 1, minAmount = 1, maxAmount = 20, chanceBasisPoints = 10_000))
        )
        sim.registerMap(
            MapDefinition(
                id = 1, name = "Field", width = 12, height = 12,
                npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 1, spawnX = 5, spawnY = 5, respawnTicks = 40))
            )
        )
        return sim
    }

    private val fighter = CharacterState(
        id = "p1", accountId = "a1", name = "Fighter", attributes = mapOf("str" to 12, "def" to 12, "int" to 12, "agi" to 12, "vit" to 12),
        position = WorldPosition.fromTile(1, 3, 5, Direction.RIGHT),
        equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(2, 1)))
    )

    /** A scripted session that involves movement, combat, RNG-driven loot, respawn and pickup. */
    private fun play(rec: InputRecorder) {
        rec.apply(SimInput.SpawnPlayer(fighter))
        rec.apply(SimInput.Tick())
        repeat(1) {
            rec.apply(SimInput.Move("p1", Direction.RIGHT))
            repeat(MovementRules.GRID_MIN_STEP_TICKS) { rec.apply(SimInput.Tick()) }
        }
        repeat(6) {
            rec.apply(SimInput.Attack("p1"))
            repeat(3) { rec.apply(SimInput.Tick()) }
        }
        rec.apply(SimInput.Move("p1", Direction.RIGHT))
        repeat(MovementRules.GRID_MIN_STEP_TICKS) { rec.apply(SimInput.Tick()) }
        rec.apply(SimInput.PickupGroundItem("p1"))
        repeat(60) { rec.apply(SimInput.Tick()) }
    }

    private fun record(): ReplayLog {
        val rec = InputRecorder(world(), seed, checkpointEveryTicks = 10)
        play(rec)
        return rec.finish()
    }

    @Test
    fun `the scripted session actually exercises combat so the replay is meaningful`() {
        val rec = InputRecorder(world(), seed)
        play(rec)
        val p = rec.sim.players["p1"]!!
        assertTrue(p.level > 1 || p.inventory != fighter.inventory, "fighter earned XP from the kill")
        assertTrue(rec.finish().checkpoints.isNotEmpty())
    }

    @Test
    fun `replaying a recorded log on a fresh world reproduces every checkpoint and the final state`() {
        val log = record()
        val result = ReplayPlayer.replay(world(), log)
        assertTrue(result.ok, result.toString())
        assertEquals(log.finalTick, result.finalTick)
        assertEquals(log.finalHash, result.finalHash)
    }

    @Test
    fun `two independent recordings of the same inputs are identical`() {
        assertEquals(record(), record())
    }

    @Test
    fun `a different seed diverges at the first checkpoint that depends on rng`() {
        val log = record()
        val result = ReplayPlayer.replay(world(seed = seed + 1), log)
        assertFalse(result.ok)
        assertTrue(result.divergences.isNotEmpty())
        assertTrue(result.divergences.first().tick <= log.finalTick)
    }

    @Test
    fun `changed content is flagged and also breaks the replay`() {
        val log = record()
        val result = ReplayPlayer.replay(world(slimeHp = 500), log)
        assertFalse(result.contentMatches)
        assertFalse(result.ok)
    }

    @Test
    fun `a tampered input list is detected`() {
        val log = record()
        val tampered = log.copy(inputs = log.inputs.filterNot { it is SimInput.PickupGroundItem })
        assertFalse(ReplayPlayer.replay(world(), tampered).ok)
    }

    @Test
    fun `state hash changes when state changes and is stable otherwise`() {
        val sim = world()
        sim.spawnOrUpdatePlayer(fighter)
        val before = StateHasher.hash(sim)
        assertEquals(before, StateHasher.hash(sim))
        sim.stepTick()
        assertNotEquals(before, StateHasher.hash(sim))
    }

    @Test
    fun `script effects are deterministic inputs that reproduce identical replay hashes`() {
        val recorderA = InputRecorder(world(), seed, checkpointEveryTicks = 2)
        recorderA.apply(SimInput.SpawnPlayer(fighter))
        recorderA.apply(SimInput.ExecuteScriptEffect(com.morgana.domain.script.ScriptEffect.DamagePlayer("p1", 5, "hp")))
        recorderA.apply(SimInput.ExecuteScriptEffect(com.morgana.domain.script.ScriptEffect.GiveItem("p1", 1, 10)))
        repeat(5) { recorderA.apply(SimInput.Tick(50)) }
        val log = recorderA.finish()

        val result = ReplayPlayer.replay(world(), log)
        assertTrue(result.ok, "Replay with script effects must succeed without divergence: ${result.divergences}")
        assertEquals(log.finalHash, result.finalHash)
    }
}
