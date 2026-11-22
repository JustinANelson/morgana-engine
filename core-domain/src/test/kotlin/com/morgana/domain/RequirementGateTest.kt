package com.morgana.domain

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.sim.RequirementGate
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.systems.PromotionSystem
import com.morgana.domain.systems.RebirthSystem
import com.morgana.domain.systems.ScriptedSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/** The kernel asks requirement gates before a spell or item is used; promotion and rebirth are two such systems (ADR 0018). */
class RequirementGateTest {
    private val helm = ItemDefinition(id = 5, name = "Moonlit Helm", type = ItemType.HELMET, armorDefense = 3)
    private val smite = SpellDefinition(id = 1, name = "Smite", targetType = SpellTargetType.SELF, cooldownTicks = 0)

    private fun sim(): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(5L), game = CompiledGame.compile(MorganaClassic.definition))
        s.registerMap(MapDefinition(id = 1, name = "Room", width = 8, height = 8))
        s.items[5] = helm
        s.spells[1] = smite
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p1", accountId = "a", name = "P", knownSpellIds = listOf(1),
                position = WorldPosition.fromTile(1, 2, 2, Direction.DOWN),
                inventory = Inventory().addItem(helm, 1).updatedInventory
            )
        )
        return s
    }

    private val noMoon = object : RequirementGate {
        override fun spellRefusal(sim: WorldSimulation, player: CharacterState, spell: SpellDefinition) =
            if (spell.id == 1) "The moon is not out." else null

        override fun itemRefusal(sim: WorldSimulation, player: CharacterState, item: ItemDefinition) =
            if (item.id == 5) "The helm sleeps by day." else null
    }

    @Test
    fun `promotion and rebirth are the built in gates`() {
        assertEquals(listOf(RebirthSystem, PromotionSystem, ScriptedSystem), sim().requirementGates, "the Lua plugins come after the engine's own systems")
    }

    @Test
    fun `a gate added to a game refuses a spell and says why`() {
        val s = sim()
        assertTrue(s.playerCastSpell("p1", 1, null, 0, 0).any { it is WorldSimulationEvent.SpellCast }, "allowed with no extra gate")

        val gated = sim().also { it.requirementGates += noMoon }
        val events = gated.playerCastSpell("p1", 1, null, 0, 0)
        assertTrue(events.none { it is WorldSimulationEvent.SpellCast })
        assertTrue(events.any { it is WorldSimulationEvent.ChatEmitted && "The moon is not out." in it.message })
    }

    @Test
    fun `a gate added to a game keeps an item in the bag`() {
        val gated = sim().also { it.requirementGates += noMoon }
        val events = gated.useInventorySlot("p1", 0)
        assertEquals(1, gated.players.getValue("p1").inventory.totalCountOf(5), "the helm stays in the bag")
        assertTrue(events.any { it is WorldSimulationEvent.ChatEmitted && "The helm sleeps by day." in it.message })

        val open = sim()
        open.useInventorySlot("p1", 0)
        assertEquals(0, open.players.getValue("p1").inventory.totalCountOf(5), "worn when nothing objects")
    }

    @Test
    fun `the first gate to object wins and the built ins can be removed`() {
        val s = sim()
        s.requirementGates.clear()
        s.requirementGates += object : RequirementGate {
            override fun spellRefusal(sim: WorldSimulation, player: CharacterState, spell: SpellDefinition) = "first"
        }
        s.requirementGates += noMoon
        assertTrue(s.playerCastSpell("p1", 1, null, 0, 0).any { it is WorldSimulationEvent.ChatEmitted && it.message.contains("first") })
        // A spell that asks for a rank is allowed once the promotion system is no longer a gate.
        val bare = sim().also { it.requirementGates.clear() }
        bare.spells[1] = smite.copy(promotionReq = 3)
        assertTrue(bare.playerCastSpell("p1", 1, null, 0, 0).any { it is WorldSimulationEvent.SpellCast })
    }

    /**
     * The kernel (`sim`, `combat` and the rest) reaches systems only through seams like [RequirementGate]. Files that still import
     * the `systems` package directly are listed here and may only be removed, never added (compare `EngineBoundaryTest`).
     */
    @Test
    fun `the kernel does not reach into the systems package except where listed`() {
        val root = Path.of("src/main/kotlin/com/morgana/domain")
        val known = setOf(
            "sim/WorldSimulation.kt" // installs the built-in systems and holds the state they keep (arena queue, guild war)
        )
        fun relative(file: Path) = root.relativize(file).joinToString("/")
        val importers = Files.walk(root).use { stream ->
            stream.filter { it.toString().endsWith(".kt") }
                .filter { !relative(it).startsWith("systems/") }
                .filter { Files.readAllLines(it).any { line -> line.startsWith("import com.morgana.domain.systems.") } }
                .map { relative(it) }
                .toList().toSet()
        }
        assertEquals(emptySet<String>(), importers - known, "new kernel imports of systems/ need a seam instead")
    }
}
