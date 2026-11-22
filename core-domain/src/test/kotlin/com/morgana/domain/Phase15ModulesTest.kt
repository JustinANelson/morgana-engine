package com.morgana.domain

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.PartyState
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.WeaponStyle
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.replay.StateHasher
import com.morgana.domain.sim.ModuleFlags
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.sim.awardPartyOrSoloExperience
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase15ModulesTest {

    private fun world(modules: ModuleFlags = ModuleFlags.ALL_ENABLED): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(11L), game = MorganaClassic.COMPILED)
        sim.modules = modules
        sim.items[2] = ItemDefinition(
            id = 2, name = "Bow", type = ItemType.WEAPON, weaponStyle = WeaponStyle.RANGED_BOW, attackDamage = 20,
            attackSpeedTicks = 1, rangeTiles = 5, requiredAmmoItemId = 3, projectileSpriteId = 1
        )
        sim.items[3] = ItemDefinition(id = 3, name = "Arrow", type = ItemType.AMMO, stackable = true, attackDamage = 5)
        sim.spells[1] = SpellDefinition(
            id = 1, name = "Bolt", targetType = SpellTargetType.DIRECTIONAL_PROJECTILE, costs = mapOf("mp" to 5), cooldownTicks = 3,
            basePower = 20, rangeTiles = 4
        )
        sim.registerMap(MapDefinition(id = 1, name = "Field", width = 12, height = 12))
        return sim
    }

    private fun player(id: String, x: Int = 3, partyId: String? = null) = CharacterState(
        id = id, accountId = "a-$id", name = id, attributes = mapOf("str" to 12, "def" to 12, "int" to 12, "agi" to 12, "vit" to 12),
        position = WorldPosition.fromTile(1, x, 5, Direction.RIGHT),
        equipment = EquipmentLoadout(mapOf(EquipmentSlot.WEAPON to ItemStack(2, 1), EquipmentSlot.AMMO to ItemStack(3, 10))),
        knownSpellIds = listOf(1),
        partyId = partyId
    )

    @Test
    fun `module flags map config keys and default to everything enabled`() {
        assertTrue(OptionalModule.entries.all { ModuleFlags.ALL_ENABLED.isEnabled(it) })
        assertEquals(OptionalModule.TRADING, OptionalModule.fromConfigKey("trading"))
        assertNull(OptionalModule.fromConfigKey("Trading"), "keys are case-sensitive")
        assertEquals(OptionalModule.entries.size, OptionalModule.entries.map { it.configKey }.toSet().size)
        val flags = ModuleFlags.fromDisabledKeys(listOf("trading", "nonsense", "guilds"))
        assertEquals(listOf("guilds", "trading"), flags.disabledKeys())
        assertFalse(flags.isEnabled(OptionalModule.TRADING))
        assertTrue(flags.isEnabled(OptionalModule.PARTIES))
    }

    @Test
    fun `trading and crafting do nothing when switched off`() {
        for (enabled in listOf(true, false)) {
            val sim = world(if (enabled) ModuleFlags.ALL_ENABLED else ModuleFlags(setOf(OptionalModule.TRADING, OptionalModule.CRAFTING)))
            sim.spawnOrUpdatePlayer(player("a"))
            sim.spawnOrUpdatePlayer(player("b", x = 4))
            val trade = sim.initiateTrade("a", "b")
            assertEquals(enabled, trade.any { it is WorldSimulationEvent.TradeUpdated }, "trade enabled=$enabled")
            assertEquals(enabled, sim.tradeSessions.isNotEmpty())
            if (!enabled) {
                assertTrue(sim.addTradeOfferItem("a", 0, 1).isEmpty())
                assertTrue(sim.lockAndConfirmTrade("a", false).isEmpty())
                assertTrue(sim.craftRecipe("a", 1).isEmpty())
            }
        }
    }

    @Test
    fun `a bow and a bolt spell are refused with a notice when projectiles are off, and work when on`() {
        val on = world()
        on.spawnOrUpdatePlayer(player("a"))
        on.stepTick()
        assertTrue(on.playerAttack("a").any { it is WorldSimulationEvent.ProjectilesChanged })
        assertTrue(on.mapProjectiles.values.any { it.isNotEmpty() })

        val off = world(ModuleFlags(setOf(OptionalModule.PROJECTILES)))
        off.spawnOrUpdatePlayer(player("a"))
        off.stepTick()
        val shot = off.playerAttack("a")
        assertTrue(shot.none { it is WorldSimulationEvent.ProjectilesChanged })
        assertTrue(shot.any { it is WorldSimulationEvent.FloatingTextEmitted && it.text.contains("disabled") }, shot.toString())
        assertEquals(10, off.players.getValue("a").equipment.getEquipped(EquipmentSlot.AMMO)?.amount, "no ammo spent")

        val mpBefore = off.players.getValue("a").vitals["mp"]?.cur
        val cast = off.playerCastSpell("a", 1)
        assertTrue(cast.none { it is WorldSimulationEvent.SpellCast || it is WorldSimulationEvent.ProjectilesChanged })
        assertEquals(mpBefore, off.players.getValue("a").vitals["mp"]?.cur, "no mana spent")
        assertTrue(off.mapProjectiles.values.all { it.isEmpty() })
    }

    @Test
    fun `free pixel movement degrades to grid steps when switched off`() {
        fun posAfterSubTileMove(modules: ModuleFlags): WorldPosition {
            val sim = world(modules)
            sim.spawnOrUpdatePlayer(player("a"))
            sim.stepTick()
            sim.movePlayer("a", Direction.RIGHT, MovementMode.SUB_TILE, deltaPixelsX = 8f)
            return sim.players.getValue("a").position
        }
        val free = posAfterSubTileMove(ModuleFlags.ALL_ENABLED)
        val grid = posAfterSubTileMove(ModuleFlags(setOf(OptionalModule.SUB_TILE_MOVEMENT)))
        assertEquals(3, free.tileX)
        assertNotEquals(free.pixelX, grid.pixelX, "free move shifts by 8px, grid move by a whole tile")
        assertEquals(4, grid.tileX)
        assertEquals(grid.tileX * WorldPosition.DEFAULT_TILE_SIZE, grid.pixelX.toInt())
    }

    private fun worldWithWalls(vararg walls: Pair<Int, Int>): WorldSimulation {
        val sim = world()
        var map = MapDefinition(id = 1, name = "Field", width = 12, height = 12)
        for ((x, y) in walls) map = map.withCell(x, y, MapCell(attribute = TileAttribute.Blocked))
        sim.registerMap(map)
        sim.spawnOrUpdatePlayer(player("a"))
        sim.stepTick()
        return sim
    }

    @Test
    fun `free pixel movement moves diagonally`() {
        val sim = worldWithWalls()
        val before = sim.players.getValue("a").position
        sim.movePlayer("a", Direction.DOWN_RIGHT, MovementMode.SUB_TILE, deltaPixelsX = 6f, deltaPixelsY = 6f)
        val after = sim.players.getValue("a").position
        assertEquals(before.pixelX + 6f, after.pixelX)
        assertEquals(before.pixelY + 6f, after.pixelY)
    }

    @Test
    fun `a blocked diagonal slides along the open axis`() {
        // Moving 16px down-right from tile (3,5) lands in (4,6), which is a wall; (4,5) is open.
        val sim = worldWithWalls(4 to 6)
        val before = sim.players.getValue("a").position
        sim.movePlayer("a", Direction.DOWN_RIGHT, MovementMode.SUB_TILE, deltaPixelsX = 16f, deltaPixelsY = 16f)
        val after = sim.players.getValue("a").position
        assertEquals(before.pixelX + 16f, after.pixelX, "slid sideways")
        assertEquals(before.pixelY, after.pixelY, "did not move down into the wall")
    }

    @Test
    fun `a diagonal into a corner with both axes walled does not move`() {
        val sim = worldWithWalls(4 to 5, 3 to 6, 4 to 6)
        val before = sim.players.getValue("a").position
        sim.movePlayer("a", Direction.DOWN_RIGHT, MovementMode.SUB_TILE, deltaPixelsX = 16f, deltaPixelsY = 16f)
        val after = sim.players.getValue("a").position
        assertEquals(before.pixelX, after.pixelX)
        assertEquals(before.pixelY, after.pixelY)
    }

    @Test
    fun `party experience sharing is ignored when parties are off`() {
        fun xpOfMemberB(modules: ModuleFlags): Long {
            val sim = world(modules)
            sim.spawnOrUpdatePlayer(player("a", partyId = "party-1"))
            sim.spawnOrUpdatePlayer(player("b", x = 4, partyId = "party-1"))
            sim.parties["party-1"] = PartyState("party-1", "a", listOf("a", "b"))
            val out = mutableListOf<WorldSimulationEvent>()
            sim.awardPartyOrSoloExperience(sim.players.getValue("a"), 50L, out)
            return sim.players.getValue("b").xp
        }
        assertTrue(xpOfMemberB(ModuleFlags.ALL_ENABLED) > 0L, "party member shares XP")
        assertEquals(0L, xpOfMemberB(ModuleFlags(setOf(OptionalModule.PARTIES))), "no sharing with parties off")
    }

    @Test
    fun `the replay fingerprint only changes when a module is disabled`() {
        val base = StateHasher.contentFingerprint(world())
        assertEquals(base, StateHasher.contentFingerprint(world(ModuleFlags())))
        assertNotEquals(base, StateHasher.contentFingerprint(world(ModuleFlags(setOf(OptionalModule.GUILDS)))))
    }
}
