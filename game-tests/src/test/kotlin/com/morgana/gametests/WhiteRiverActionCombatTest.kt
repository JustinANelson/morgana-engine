package com.morgana.gametests

import com.morgana.server.*

import com.morgana.domain.combat.LuaCombatModule
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.MapNpcInstance
import com.morgana.domain.replay.InputRecorder
import com.morgana.domain.replay.ReplayLog
import com.morgana.domain.replay.ReplayPlayer
import com.morgana.domain.replay.SimInput
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.VitalState
import com.morgana.persistence.GameDefinitionLoader
import com.morgana.persistence.MorganaDataStore
import com.morgana.script.luak.LuakScriptRuntime
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Phase A2 of ADR 0017: the Lua prototype in `games/white-river/scripts/action_combat.lua` steps the Ford Sword and
 * Shield combo graph against a training dummy. The exit gate is that a ten-skill combo replays to the same hash twice.
 * Each test works on a temp copy of the game and builds the simulation with `lua_combat` selected, because the game
 * itself still ships on `realtime`.
 */
class WhiteRiverActionCombatTest {

    private val game: Path = listOf(Path.of("games/white-river"), Path.of("../games/white-river")).first { it.exists() }

    private val hero = "hero-1"
    private val dummyId = "dummy-1"
    private val dummyNpcId = 9001

    private fun copyGame(to: Path): Path {
        Files.walk(game).use { paths ->
            paths.forEach { src ->
                val rel = game.relativize(src).toString().replace('\\', '/')
                if (rel.startsWith("tools")) return@forEach
                val dst = to.resolve(rel)
                if (Files.isDirectory(src)) Files.createDirectories(dst) else Files.copy(src, dst)
            }
        }
        return to
    }

    /** White River's content and scripts in a simulation that uses the Lua combat module, with a hero and a dummy. */
    private fun world(dir: Path, dummyTileX: Int = 21, sp: Int = 100): WorldSimulation {
        val store = MorganaDataStore(copyGame(dir))
        val sim = WorldSimulation(
            game = GameDefinitionLoader.loadFor(store.gameDir, store.rootDir),
            scripts = LuakScriptRuntime(),
            combat = LuaCombatModule()
        )
        MorganaWorldCoordinator(
            store, simulation = sim, ownerPolicy = OwnerPolicy(dir) { null }, packetLimits = PacketLimitSettings(enabled = false)
        )
        assertTrue(sim.scripts.hasModule("action_combat.lua"), "the game's action combat script is loaded")
        sim.npcs[dummyNpcId] = NpcDefinition(
            id = dummyNpcId, name = "Training Dummy", behavior = NpcBehavior.SHOPKEEPER,
            vitals = mapOf("hp" to 100_000), attributes = mapOf("str" to 1, "agi" to 1, "int" to 1, "vit" to 1)
        )
        sim.mapNpcs.getOrPut(1) { mutableListOf() }.clear()
        sim.mapNpcs.getValue(1).add(
            MapNpcInstance(
                instanceId = dummyId, spawnSlotIndex = 0, npcId = dummyNpcId, mapId = 1,
                tileX = dummyTileX, tileY = 20, homeX = dummyTileX, homeY = 20,
                vitals = mapOf("hp" to VitalState(100_000, 100_000))
            )
        )
        sim.spawnOrUpdatePlayer(
            CharacterState(
                id = hero, accountId = "acc-1", name = "Hero", classId = 1, level = 20,
                position = WorldPosition.fromTile(1, 20, 20, Direction.RIGHT),
                attributes = mapOf("str" to 14, "agi" to 12, "int" to 8, "vit" to 10),
                vitals = mapOf("hp" to VitalState(500, 500), "mp" to VitalState(50, 50), "sp" to VitalState(sp, 100))
            )
        )
        return sim
    }

    private fun InputRecorder.input(token: String) { apply(SimInput.CustomAction(hero, "input", mapOf("token" to token))) }
    private fun InputRecorder.ticks(n: Int) { repeat(n) { apply(SimInput.Tick()) } }

    private fun WorldSimulation.state() = scriptState["ac:$hero"]
    private fun WorldSimulation.skill() = state()?.get("skill")?.toInt() ?: 0
    private fun WorldSimulation.dummyHp() = mapNpcs.getValue(1).first { it.instanceId == dummyId }.vitals.getValue("hp").cur
    private fun WorldSimulation.sp() = players.getValue(hero).vitals.getValue("sp").cur

    private val seed = WorldSimulation.DEFAULT_SEED

    // ---- the ten-skill gate ------------------------------------------------------------------------------------

    /** Eleven skills through every kind of edge: chains, a dodge cancel, a requires-hit edge, a guard and a riposte. */
    private fun playCombo(rec: InputRecorder): List<Int> {
        val started = mutableListOf<Int>()
        fun press(token: String, thenWait: Int) {
            val before = rec.sim.state()?.get("start")
            rec.input(token)
            val s = rec.sim.state()
            if (s != null && s["start"] != before) started += rec.sim.skill()
            rec.ticks(thenWait)
        }
        press("LMB", 6)        // 1 Ford Cut
        press("LMB", 6)        // 2 Backhand
        press("LMB", 22)       // 3 Riverbreak, then let it finish
        press("LMB", 6)        // 1 again, from neutral
        press("SPACE", 8)      // 6 Sidestep cancels the cut
        press("LMB", 6)        // 1 Ford Cut out of the dodge
        press("LMB", 6)        // 2 Backhand
        press("SHIFT+RMB", 9)  // 4 Undertow
        press("LMB", 22)       // 3 Riverbreak, which needs Undertow to have connected
        press("Q", 2)          // 8 Wardstance
        press("LMB", 25)       // 9 Riposte out of the guard
        return started
    }

    private val expectedChain = listOf(1, 2, 3, 1, 6, 1, 2, 4, 3, 8, 9)

    private fun recordCombo(dir: Path): Triple<ReplayLog, List<Int>, WorldSimulation> {
        val sim = world(dir)
        val rec = InputRecorder(sim, seed)
        rec.ticks(1)
        val started = playCombo(rec)
        return Triple(rec.finish(), started, sim)
    }

    @Test
    fun `a ten-skill combo plays through the combo graph and replays to the same hash twice`(@TempDir tmp: Path) {
        val (logA, startedA, simA) = recordCombo(Files.createDirectory(tmp.resolve("a")))
        val (logB, startedB, _) = recordCombo(Files.createDirectory(tmp.resolve("b")))

        assertEquals(expectedChain, startedA, "every press started the skill the combo graph names")
        assertTrue(startedA.size >= 10)
        assertEquals(startedA, startedB)
        assertTrue(simA.dummyHp() < 100_000 - 100, "the chain hurt the dummy: ${simA.dummyHp()}")
        assertEquals(100 - 10 - 20 - 15 - 10 - 10, simA.sp(), "stamina paid for Riverbreak x2, Sidestep, Undertow and Riposte")

        assertEquals(logA.finalHash, logB.finalHash, "two live runs of the same inputs end in the same state")
        assertEquals(logA.checkpoints, logB.checkpoints)
        assertTrue(logA.gaps.isEmpty())

        // A third, fresh simulation replays the recorded log and reproduces every checkpoint and the final hash.
        val replayed = ReplayPlayer.replay(world(Files.createDirectory(tmp.resolve("c"))), logA)
        assertTrue(replayed.ok, "replay diverged: ${replayed.divergences}")
        assertEquals(logA.finalHash, replayed.finalHash)
    }

    @Test
    fun `different input gives a different hash`(@TempDir tmp: Path) {
        val (log, _, _) = recordCombo(Files.createDirectory(tmp.resolve("a")))
        val sim = world(Files.createDirectory(tmp.resolve("b")))
        val rec = InputRecorder(sim, seed)
        rec.ticks(1)
        rec.input("LMB")
        rec.ticks(40)
        assertNotEquals(log.finalHash, rec.finish().finalHash)
    }

    // ---- the combo graph --------------------------------------------------------------------------------------

    @Test
    fun `a press inside the window chains, a press after it starts a fresh cut`(@TempDir tmp: Path) {
        val sim = world(tmp)
        val rec = InputRecorder(sim, seed)
        rec.ticks(1)
        rec.input("LMB")
        assertEquals(1, sim.skill())
        rec.ticks(6)
        rec.input("LMB")
        assertEquals(2, sim.skill(), "Backhand follows Ford Cut inside its window")

        // Let the chain die, then a press starts from neutral instead of continuing.
        rec.ticks(60)
        rec.input("LMB")
        assertEquals(1, sim.skill())
    }

    @Test
    fun `an early press is buffered and fires at the cancel point`(@TempDir tmp: Path) {
        val sim = world(tmp)
        val rec = InputRecorder(sim, seed)
        rec.ticks(1)
        rec.input("LMB")
        rec.ticks(1)
        rec.input("LMB") // Ford Cut cancels from tick 4; this is tick ~1, inside the 6-tick buffer
        assertEquals(1, sim.skill(), "not yet: the cut has not reached its cancel point")
        assertEquals("LMB", sim.state()?.get("bin"))
        rec.ticks(6)
        assertEquals(2, sim.skill(), "the buffered press became Backhand")
        assertEquals(null, sim.state()?.get("bin"))
    }

    @Test
    fun `a press that is too early for the buffer is dropped`(@TempDir tmp: Path) {
        val sim = world(tmp)
        val rec = InputRecorder(sim, seed)
        rec.ticks(1)
        rec.input("LMB")
        rec.ticks(1)
        rec.input("SHIFT+RMB") // no edge for this out of Ford Cut
        assertEquals(null, sim.state()?.get("bin"))
        rec.ticks(20)
        assertEquals(1, sim.skill())
    }

    @Test
    fun `an edge that requires a hit is refused when the skill missed`(@TempDir tmp: Path) {
        val sim = world(tmp, dummyTileX = 30) // out of reach
        val rec = InputRecorder(sim, seed)
        rec.ticks(1)
        rec.input("LMB"); rec.ticks(6)
        rec.input("LMB"); rec.ticks(6)
        rec.input("SHIFT+RMB")
        assertEquals(4, sim.skill())
        rec.ticks(9)
        assertEquals("0", sim.state()?.get("hit"), "Undertow hit nothing")
        rec.input("LMB")
        assertEquals(4, sim.skill(), "Riverbreak needs Undertow to connect")
        assertEquals(100_000, sim.dummyHp())
    }

    @Test
    fun `a skill pays its cost and waits out its cooldown`(@TempDir tmp: Path) {
        val sim = world(tmp)
        val rec = InputRecorder(sim, seed)
        rec.ticks(1)
        rec.input("F") // Shieldbreaker: 25 stamina, 120-tick cooldown
        assertEquals(5, sim.skill())
        assertEquals(75, sim.sp())
        rec.ticks(8)
        assertEquals("STUN", sim.scriptState["react:$dummyId"]?.get("status"), "Shieldbreaker's stun was recorded on the dummy")
        rec.ticks(32)
        assertEquals(0, sim.skill(), "the chain died: nothing leads on from Shieldbreaker")
        rec.input("F")
        assertEquals(0, sim.skill(), "still cooling down")
        assertEquals(75, sim.sp(), "nothing was paid for the refused skill")
        rec.ticks(90)
        rec.input("F")
        assertEquals(5, sim.skill())
        assertEquals(50, sim.sp())
    }

    @Test
    fun `a skill is refused without the stamina`(@TempDir tmp: Path) {
        val sim = world(tmp, sp = 10)
        val rec = InputRecorder(sim, seed)
        rec.ticks(1)
        rec.input("F")
        assertEquals(0, sim.skill())
        assertEquals(10, sim.sp())
    }

    @Test
    fun `hit-stop freezes the timeline for as many ticks as the hit asks`(@TempDir tmp: Path) {
        val sim = world(tmp)
        val rec = InputRecorder(sim, seed)
        rec.ticks(1)
        rec.input("LMB")
        val start = sim.state()!!.getValue("start").toLong()
        rec.ticks(3) // Ford Cut's arc goes live on skill tick 2 and the hit asks for 1 tick of hit-stop
        rec.ticks(2)
        assertEquals(start + 1, sim.state()!!.getValue("start").toLong(), "the start moved by the 1 tick of hit-stop")
    }

    // ---- hitboxes ---------------------------------------------------------------------------------------------

    @Test
    fun `an arc hits what is in front of the character and not what is behind it`(@TempDir tmp: Path) {
        val front = world(Files.createDirectory(tmp.resolve("front")), dummyTileX = 21)
        val recF = InputRecorder(front, seed)
        recF.ticks(1); recF.input("LMB"); recF.ticks(6)
        assertTrue(front.dummyHp() < 100_000, "dummy in front is hit")

        val behind = world(Files.createDirectory(tmp.resolve("behind")), dummyTileX = 19)
        val recB = InputRecorder(behind, seed)
        recB.ticks(1); recB.input("LMB"); recB.ticks(6)
        assertEquals(100_000, behind.dummyHp(), "dummy behind is not hit")
    }

    @Test
    fun `a target is hit once per hit group however many ticks the hitbox is live`(@TempDir tmp: Path) {
        val sim = world(tmp)
        val rec = InputRecorder(sim, seed)
        rec.ticks(1)
        rec.input("LMB") // Ford Cut: arc live on ticks 2 and 3
        rec.ticks(8)
        val one = 100_000 - sim.dummyHp()
        assertTrue(one > 0)
        rec.ticks(30)
        assertEquals(100_000 - one, sim.dummyHp(), "no second hit from the same swing")
    }

    @Test
    fun `the plain attack packet counts as a left click`(@TempDir tmp: Path) {
        val sim = world(tmp)
        val rec = InputRecorder(sim, seed)
        rec.ticks(1)
        rec.apply(SimInput.Attack(hero))
        assertEquals(1, sim.skill())
    }

    @Test
    fun `leaving the game clears the character's combat state`(@TempDir tmp: Path) {
        val sim = world(tmp)
        val rec = InputRecorder(sim, seed)
        rec.ticks(1)
        rec.input("LMB")
        assertTrue(sim.state() != null)
        rec.apply(SimInput.RemovePlayer(hero))
        rec.ticks(2)
        assertEquals(null, sim.state())
    }
}
