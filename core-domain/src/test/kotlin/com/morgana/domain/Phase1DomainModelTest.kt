package com.morgana.domain

import com.morgana.domain.access.AccessLevel
import com.morgana.domain.access.PermissionFlag
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.TilePlacement
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.TileCoord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase1DomainModelTest {

    @Test
    fun `5-tier access levels enforce hierarchical permissions and custom flags`() {
        assertFalse(AccessLevel.PLAYER.canOpenAdminPanel())
        assertFalse(AccessLevel.MONITOR.canOpenAdminPanel())
        assertTrue(AccessLevel.MAPPER.canOpenAdminPanel())
        assertTrue(AccessLevel.DEVELOPER.canOpenAdminPanel())
        assertTrue(AccessLevel.OWNER.canOpenAdminPanel())

        assertTrue(AccessLevel.MONITOR.hasPermission(PermissionFlag.KICK_MUTE))
        assertFalse(AccessLevel.MONITOR.hasPermission(PermissionFlag.EDIT_MAP))
        assertTrue(AccessLevel.MAPPER.hasPermission(PermissionFlag.EDIT_MAP))
        assertFalse(AccessLevel.MAPPER.hasPermission(PermissionFlag.EDIT_CONTENT))
        assertTrue(AccessLevel.DEVELOPER.hasPermission(PermissionFlag.EDIT_CONTENT))
        assertTrue(AccessLevel.OWNER.hasPermission(PermissionFlag.SET_ACCESS))

        // Custom permission grant on a player
        assertTrue(
            AccessLevel.PLAYER.hasPermission(
                PermissionFlag.EDIT_MAP,
                extraPermissions = setOf(PermissionFlag.EDIT_MAP)
            )
        )
    }

    @Test
    fun `map cell traversal respects blocked, directional block, key door, and resource attributes`() {
        var map = MapDefinition(id = 1, name = "Havenwood", width = 10, height = 10)
        map = map.withCell(
            x = 2,
            y = 2,
            newCell = MapCell(
                layers = mapOf(MapLayer.GROUND to TilePlacement(tilesetId = 1, srcTileX = 1, srcTileY = 0)),
                attribute = TileAttribute.Blocked
            )
        )
        map = map.withCell(
            x = 3,
            y = 2,
            newCell = MapCell(attribute = TileAttribute.DirectionalBlock(blockLeft = true))
        )
        map = map.withCell(
            x = 4,
            y = 4,
            newCell = MapCell(attribute = TileAttribute.KeyDoor(keyItemId = 99))
        )
        map = map.withCell(
            x = 5,
            y = 5,
            newCell = MapCell(attribute = TileAttribute.NpcAvoid)
        )

        // Blocked tile cannot be traversed
        assertFalse(map.canTraverse(TileCoord(2, 1), TileCoord(2, 2), Direction.DOWN))

        // Directional block on (3,2) blocks entry from the left (moving RIGHT from (2,2)->(3,2))
        assertFalse(map.canTraverse(TileCoord(2, 2), TileCoord(3, 2), Direction.RIGHT))
        // Entry from above (moving DOWN from (3,1)->(3,2)) is allowed
        assertTrue(map.canTraverse(TileCoord(3, 1), TileCoord(3, 2), Direction.DOWN))

        // Locked door blocks until unlocked
        assertFalse(map.canTraverse(TileCoord(4, 3), TileCoord(4, 4), Direction.DOWN))
        assertTrue(
            map.canTraverse(
                from = TileCoord(4, 3),
                to = TileCoord(4, 4),
                direction = Direction.DOWN,
                unlockedDoors = setOf(TileCoord(4, 4))
            )
        )

        // NPC Avoid blocks NPCs but allows players
        assertFalse(map.canTraverse(TileCoord(5, 4), TileCoord(5, 5), Direction.DOWN, isNpc = true))
        assertTrue(map.canTraverse(TileCoord(5, 4), TileCoord(5, 5), Direction.DOWN, isNpc = false))
    }

    @Test
    fun `inventory stacking, slot limits, removal, and swapping are deterministic`() {
        val goldDef = ItemDefinition(id = 1, name = "Gold Coin", type = ItemType.CURRENCY, stackable = true, maxStack = 1000)
        val swordDef = ItemDefinition(id = 2, name = "Iron Longsword", type = ItemType.WEAPON, stackable = false)

        val inv0 = Inventory(maxSlots = 4)
        val res1 = inv0.addItem(goldDef, 400)
        assertEquals(400, res1.addedAmount)
        assertEquals(0, res1.overflowAmount)

        val res2 = res1.updatedInventory.addItem(goldDef, 750)
        assertEquals(750, res2.addedAmount)
        assertEquals(1150, res2.updatedInventory.totalCountOf(1))
        assertEquals(1000, res2.updatedInventory.getSlot(0)?.amount)
        assertEquals(150, res2.updatedInventory.getSlot(1)?.amount)

        // Add non-stackable swords into remaining 2 slots + 1 overflow
        val res3 = res2.updatedInventory.addItem(swordDef, 3)
        assertEquals(2, res3.addedAmount)
        assertEquals(1, res3.overflowAmount)

        val afterRemove = res3.updatedInventory.removeByItemId(1, 500)
        assertNotNull(afterRemove)
        assertEquals(650, afterRemove!!.totalCountOf(1))

        assertNull(afterRemove.removeByItemId(1, 9999))
    }

    @Test
    fun `character experience gain, level up, equipment stats, and paperdoll layers work deterministically`() {
        val sword = ItemDefinition(
            id = 10,
            name = "Knight Blade",
            type = ItemType.WEAPON,
            paperdollSpriteId = 101,
            attributeBonuses = mapOf("str" to 5, "agi" to 2),
            attackDamage = 12
        )
        val armor = ItemDefinition(
            id = 11,
            name = "Chainmail",
            type = ItemType.ARMOR,
            paperdollSpriteId = 202,
            attributeBonuses = mapOf("def" to 6, "vit" to 4),
            armorDefense = 15,
            vitalMaxBonuses = mapOf("hp" to 40)
        )
        val catalog = mapOf(sword.id to sword, armor.id to armor)

        val equipped = EquipmentLoadout(
            mapOf(
                EquipmentSlot.WEAPON to ItemStack(10, 1),
                EquipmentSlot.ARMOR to ItemStack(11, 1)
            )
        )

        val baseChar = CharacterState(
            id = "char-1",
            accountId = "acc-1",
            name = "Ayla",
            attributes = mapOf("str" to 8, "def" to 8, "vit" to 8),
            equipment = equipped
        ).recalculateVitals(MorganaClassic.COMPILED, catalog, refill = true)

        val effective = baseChar.effectiveAttributes(MorganaClassic.COMPILED, catalog)
        assertEquals(13, effective["str"])
        assertEquals(14, effective["def"])
        assertEquals(12, effective["vit"])
        assertEquals(listOf(202, 101), equipped.paperdollLayers(catalog))

        val xpForLv2 = MorganaClassic.COMPILED.xpToNext(1)!!
        val (leveledChar, gained) = baseChar.gainExperience(MorganaClassic.COMPILED, xpForLv2 + 25L, statPointsPerLevel = 3, itemCatalog = catalog)
        assertEquals(1, gained)
        assertEquals(2, leveledChar.level)
        assertEquals(25L, leveledChar.xp)
        assertEquals(3, leveledChar.unspentStatPoints)
        assertTrue(leveledChar.vitals["hp"]!!.max > baseChar.vitals["hp"]!!.max)
    }
}

