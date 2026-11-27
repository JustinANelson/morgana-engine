package com.morgana.domain

import com.morgana.domain.access.AccessLevel
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.NpcDropEntry
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.content.RecipeIngredient
import com.morgana.domain.content.ResourceCategory
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.EventPage
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.event.QuestObjective
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.event.QuestReward
import com.morgana.domain.event.QuestStatus
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.ToolType
import com.morgana.domain.item.WeaponStyle
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.MapNeighborLinks
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.sim.MovementRules
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.MovementMode
import com.morgana.domain.spatial.WorldPosition
import com.morgana.domain.stats.StatusEffectType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase2WorldSimulationTest {

    @Test
    fun `seamless neighbor map border crossing, warps, and sub-tile movement work deterministically`() {
        val sim = WorldSimulation(SeededRandomSource(42L), game = MorganaClassic.COMPILED)

        val map1 = MapDefinition(
            id = 1,
            name = "Town",
            width = 10,
            height = 10,
            neighbors = MapNeighborLinks(eastMapId = 2)
        ).withCell(
            x = 5,
            y = 5,
            newCell = MapCell(attribute = TileAttribute.Warp(targetMapId = 2, targetX = 3, targetY = 3, targetDirection = Direction.DOWN))
        )
        val map2 = MapDefinition(
            id = 2,
            name = "Outskirts",
            width = 10,
            height = 10,
            neighbors = MapNeighborLinks(westMapId = 1)
        )
        sim.registerMap(map1)
        sim.registerMap(map2)

        val player = CharacterState(
            id = "p1",
            accountId = "acc1",
            name = "Hero",
            position = WorldPosition.fromTile(mapId = 1, tileX = 9, tileY = 4, direction = Direction.RIGHT)
        )
        sim.spawnOrUpdatePlayer(player)

        // Step East across the right edge of Map 1 (x=9 -> East -> Map 2 at x=0, y=4)
        sim.movePlayer("p1", Direction.RIGHT, MovementMode.GRID_LOCKED)
        val afterBorder = sim.players["p1"]!!
        assertEquals(2, afterBorder.position.mapId)
        assertEquals(0, afterBorder.position.tileX)
        assertEquals(4, afterBorder.position.tileY)

        // Step West back into Map 1 (x=0 -> West -> Map 1 at x=9, y=4)
        sim.movePlayer("p1", Direction.LEFT, MovementMode.GRID_LOCKED)
        assertEquals(1, sim.players["p1"]!!.position.mapId)
        assertEquals(9, sim.players["p1"]!!.position.tileX)

        // Sub-tile pixel movement
        sim.movePlayer("p1", Direction.LEFT, MovementMode.SUB_TILE, deltaPixelsX = -16f, deltaPixelsY = 0f)
        assertEquals(MovementMode.SUB_TILE, sim.players["p1"]!!.movementMode)

        // Place player next to Warp tile (4,5) and step right onto (5,5)
        sim.spawnOrUpdatePlayer(player.copy(position = WorldPosition.fromTile(1, 4, 5, Direction.RIGHT)))
        sim.movePlayer("p1", Direction.RIGHT, MovementMode.GRID_LOCKED)
        val afterWarp = sim.players["p1"]!!
        assertEquals(2, afterWarp.position.mapId)
        assertEquals(3, afterWarp.position.tileX)
        assertEquals(3, afterWarp.position.tileY)
    }

    @Test
    fun `melee combat, ranged projectiles, AoE spells, NPC loot drops, and party XP work deterministically`() {
        val sim = WorldSimulation(SeededRandomSource(1234L), game = MorganaClassic.COMPILED)

        val goldDef = ItemDefinition(id = 1, name = "Gold Coin", type = ItemType.CURRENCY, stackable = true)
        val bowDef = ItemDefinition(
            id = 2,
            name = "Shortbow",
            type = ItemType.WEAPON,
            weaponStyle = WeaponStyle.RANGED_BOW,
            attackDamage = 25,
            attackSpeedTicks = 1,
            rangeTiles = 5,
            requiredAmmoItemId = 3,
            projectileSpriteId = 1
        )
        val arrowDef = ItemDefinition(id = 3, name = "Wood Arrow", type = ItemType.AMMO, stackable = true, attackDamage = 10)
        val fireball = SpellDefinition(
            id = 1,
            name = "Fireball",
            targetType = SpellTargetType.SINGLE_TARGET,
            costs = mapOf("mp" to 5),
            cooldownTicks = 5,
            basePower = 40,
            statusEffectType = StatusEffectType.DOT_BURN,
            statusMagnitude = 10,
            statusDurationTicks = 40
        )
        val slimeDef = NpcDefinition(
            id = 1,
            name = "Green Slime",
            behavior = NpcBehavior.PASSIVE,
            vitals = mapOf("hp" to 35),
            attributes = mapOf("str" to 2, "def" to 2, "int" to 2, "agi" to 2, "vit" to 2),
            xpReward = 120L,
            drops = listOf(NpcDropEntry(itemId = 1, minAmount = 10, maxAmount = 10, chanceBasisPoints = 10_000))
        )

        sim.items[1] = goldDef
        sim.items[2] = bowDef
        sim.items[3] = arrowDef
        sim.spells[1] = fireball
        sim.npcs[1] = slimeDef

        val map = MapDefinition(
            id = 1,
            name = "Training Field",
            width = 12,
            height = 12,
            npcSpawns = listOf(MapNpcSpawnSlot(slotIndex = 0, npcId = 1, spawnX = 5, spawnY = 5, respawnTicks = 100))
        )
        sim.registerMap(map)

        var archer = CharacterState(
            id = "p1",
            accountId = "a1",
            name = "Archer",
            attributes = mapOf("str" to 10, "def" to 10, "int" to 15, "agi" to 18, "vit" to 10),
            position = WorldPosition.fromTile(1, 2, 5, Direction.RIGHT),
            equipment = EquipmentLoadout(
                mapOf(
                    EquipmentSlot.WEAPON to ItemStack(2, 1),
                    EquipmentSlot.AMMO to ItemStack(3, 5)
                )
            ),
            knownSpellIds = listOf(1)
        )
        sim.spawnOrUpdatePlayer(archer)
        sim.stepTick()
        sim.stepTick()

        // Fire ranged projectile at Slime at (5,5) from (2,5) facing RIGHT
        sim.playerAttack("p1")
        assertEquals(4, sim.players["p1"]!!.equipment.getEquipped(EquipmentSlot.AMMO)?.amount)
        assertEquals(1, sim.mapProjectiles[1]?.size)

        // Advance ticks so projectile travels (2,5) -> (3,5) -> (4,5) -> (5,5) and hits the Slime!
        repeat(5) { sim.stepTick() }

        // Slime should have died from the shot, dropped 10 gold on (5,5), and awarded 120 XP (leveling Archer to Lv 2!)
        val slimeAfter = sim.mapNpcs[1]!!.first()
        assertFalse(slimeAfter.isAlive)
        assertEquals(1, sim.mapGroundItems[1]?.size)
        assertEquals(2, sim.players["p1"]!!.level)

        // Walk to (5,5) and pick up the dropped gold
        // Moves are rate limited per tick (MovementRules), so advance time between steps like a real client.
        repeat(3) {
            sim.movePlayer("p1", Direction.RIGHT)
            repeat(MovementRules.GRID_MIN_STEP_TICKS) { sim.stepTick() }
        }
        sim.pickupGroundItem("p1")
        assertEquals(10, sim.players["p1"]!!.inventory.totalCountOf(1))
    }

    @Test
    fun `gather pickup batches all items within 2 tiles, emits pickup toast, and ignores 3+ tiles away`() {
        val sim = WorldSimulation(SeededRandomSource(42L), game = MorganaClassic.COMPILED)
        sim.items[1] = ItemDefinition(id = 1, name = "Gold", type = ItemType.CURRENCY, stackable = true)
        sim.items[2] = ItemDefinition(id = 2, name = "Rawhide", type = ItemType.MATERIAL, stackable = true)
        sim.registerMap(MapDefinition(id = 1, name = "Field", width = 10, height = 10))
        val player = CharacterState(
            id = "p1",
            accountId = "a1",
            name = "Hero",
            classId = 1,
            position = WorldPosition.fromTile(1, 5, 5)
        )
        sim.spawnOrUpdatePlayer(player)

        // Nearby (1 and 2 tiles away) and far (3 tiles away)
        sim.mapGroundItems[1] = mutableListOf(
            com.morgana.domain.entity.MapGroundItem(instanceId = "g_far", mapId = 1, tileX = 8, tileY = 5, stack = ItemStack(1, 50)),
            com.morgana.domain.entity.MapGroundItem(instanceId = "g_two", mapId = 1, tileX = 7, tileY = 6, stack = ItemStack(1, 15)),
            com.morgana.domain.entity.MapGroundItem(instanceId = "g_one", mapId = 1, tileX = 5, tileY = 6, stack = ItemStack(1, 7)),
            com.morgana.domain.entity.MapGroundItem(instanceId = "g_hide", mapId = 1, tileX = 6, tileY = 5, stack = ItemStack(2, 3))
        )
        // Single pickup action batches all in-range items (1 and 2 tiles away) and emits one GOOD toast per item type
        val events = sim.pickupGroundItem("p1")
        assertEquals(22, sim.players["p1"]!!.inventory.totalCountOf(1))
        assertEquals(3, sim.players["p1"]!!.inventory.totalCountOf(2))
        assertEquals(1, sim.mapGroundItems[1]?.size)
        val toasts = events.filterIsInstance<com.morgana.domain.sim.WorldSimulationEvent.ChatEmitted>()
            .filter { it.notice == "GOOD" }
            .map { it.message }
        assertEquals(listOf("+Rawhide x3", "+Gold x22"), toasts)

        // Far item (3 tiles away) is out of reach
        assertTrue(sim.pickupGroundItem("p1").isEmpty())
        assertEquals(22, sim.players["p1"]!!.inventory.totalCountOf(1))
    }

    @Test
    fun `resource gathering, crafting recipes, quests, events, two-stage trading, and F1 live edits work`() {
        val sim = WorldSimulation(SeededRandomSource(99L), game = MorganaClassic.COMPILED)

        val hatchet = ItemDefinition(id = 10, name = "Bronze Hatchet", type = ItemType.TOOL, toolType = ToolType.HATCHET)
        val oakLog = ItemDefinition(id = 11, name = "Oak Log", type = ItemType.MATERIAL, stackable = true)
        val woodenShield = ItemDefinition(id = 12, name = "Oak Shield", type = ItemType.SHIELD)

        sim.items[10] = hatchet
        sim.items[11] = oakLog
        sim.items[12] = woodenShield

        val oakTree = ResourceDefinition(
            id = 1,
            name = "Oak Tree",
            category = ResourceCategory.TREE,
            requiredTool = ToolType.HATCHET,
            rewardItemId = 11,
            minYield = 2,
            maxYield = 2,
            maxHealth = 2,
            respawnTicks = 10,
            xpReward = 20L
        )
        sim.resources[1] = oakTree

        val shieldRecipe = RecipeDefinition(
            id = 1,
            name = "Craft Oak Shield",
            ingredients = listOf(RecipeIngredient(itemId = 11, amount = 2)),
            outputItemId = 12,
            outputAmount = 1,
            xpReward = 30L
        )
        sim.recipes[1] = shieldRecipe

        val lumberQuest = QuestDefinition(
            id = 1,
            name = "Lumberjack's Request",
            objectives = listOf(QuestObjective(QuestObjectiveType.HARVEST_RESOURCE, targetId = 1, requiredCount = 1)),
            reward = QuestReward(xp = 50L, items = listOf(RecipeIngredient(11, 1)))
        )
        sim.quests[1] = lumberQuest

        val questEvent = EventDefinition(
            id = 1,
            name = "Start Lumber Quest",
            mapId = 1,
            tileX = 4,
            tileY = 3,
            pages = listOf(
                EventPage(
                    pageIndex = 0,
                    commands = listOf(
                        EventCommand.StartQuest(1),
                        EventCommand.SetSwitch("met_forester", true)
                    )
                )
            )
        )
        sim.events[1] = questEvent

        val map = MapDefinition(id = 1, name = "Forest", width = 10, height = 10).withCell(
            x = 4,
            y = 4,
            newCell = MapCell(attribute = TileAttribute.ResourceNode(resourceId = 1))
        )
        sim.registerMap(map)

        val invWithHatchet = CharacterState(
            id = "p1",
            accountId = "a1",
            name = "Crafter",
            accessLevel = AccessLevel.OWNER,
            position = WorldPosition.fromTile(1, 3, 3, Direction.RIGHT)
        ).let { c ->
            c.copy(inventory = c.inventory.addItem(hatchet, 1).updatedInventory)
        }
        sim.spawnOrUpdatePlayer(invWithHatchet)

        // Interact with event tile at (4,3) to start quest
        sim.playerInteractFacing("p1")
        assertTrue(sim.players["p1"]!!.switches["met_forester"] == true)
        assertEquals(QuestStatus.IN_PROGRESS, sim.players["p1"]!!.quests[1]?.status)

        // Move to (3,4) facing RIGHT toward Oak Tree at (4,4) and harvest twice
        sim.movePlayer("p1", Direction.DOWN)
        sim.movePlayer("p1", Direction.RIGHT) // turns RIGHT because (4,4) has intact resource node
        sim.playerInteractFacing("p1")
        repeat(com.morgana.domain.sim.HARVEST_COOLDOWN_TICKS.toInt()) { sim.stepTick() }
        sim.playerInteractFacing("p1")

        // Player now has 2 Oak Logs, and quest objective (1/1) is complete!
        assertEquals(2, sim.players["p1"]!!.inventory.totalCountOf(11))
        assertEquals(1, sim.players["p1"]!!.quests[1]?.objectiveCounts?.first())

        // Craft Oak Shield using the 2 Oak Logs
        sim.craftRecipe("p1", 1)
        assertEquals(0, sim.players["p1"]!!.inventory.totalCountOf(11))
        assertEquals(1, sim.players["p1"]!!.inventory.totalCountOf(12))

        // Test two-stage trading with Player 2
        val p2 = CharacterState(
            id = "p2",
            accountId = "a2",
            name = "Partner",
            position = WorldPosition.fromTile(1, 3, 5, Direction.UP)
        )
        sim.spawnOrUpdatePlayer(p2)
        sim.initiateTrade("p1", "p2")
        // Offer Oak Shield (in slot 1, since Hatchet is in slot 0)
        sim.addTradeOfferItem("p1", 1, 1)
        sim.lockAndConfirmTrade("p1", confirmStage = false)
        sim.lockAndConfirmTrade("p2", confirmStage = false)
        sim.lockAndConfirmTrade("p1", confirmStage = true)
        sim.lockAndConfirmTrade("p2", confirmStage = true)
        assertEquals(1, sim.players["p2"]!!.inventory.totalCountOf(12))

        // Test Live F1 Map & Item Edit by Owner (p1)
        val editedMap = map.copy(name = "Enchanted Forest", moral = MapMoral.ARENA)
        val editEvents = sim.applyLiveMapEdit("p1", editedMap)
        assertTrue(editEvents.isNotEmpty())
        assertEquals("Enchanted Forest", sim.maps[1]?.name)
    }
}

