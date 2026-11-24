package com.morgana.domain

import com.morgana.domain.combat.ActionCombatRules
import com.morgana.domain.content.HitboxShape
import com.morgana.domain.content.SkillHitbox
import com.morgana.domain.content.NpcDropEntry
import com.morgana.domain.content.ResourceCategory
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.entity.GuildDocument
import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.GameplayTuning
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.GearRules
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.ToolType
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.sim.GROUND_PICKUP_RANGE_TILES
import com.morgana.domain.sim.HARVEST_COOLDOWN_TICKS
import com.morgana.domain.sim.SHOP_RANGE_TILES
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.ShopSession
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.social.GuildRules
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `game.json` `tuning`: gameplay numbers a game sets without engine code. Every setting is checked twice: left alone it is the
 * engine's old value, and changed it changes what the simulation does.
 */
class TuningTest {
    private fun game(tuning: GameplayTuning?) = CompiledGame.compile(MorganaClassic.definition.copy(tuning = tuning))

    @Test
    fun `a game with no tuning plays by the engine's old numbers`() {
        val t = MorganaClassic.definition.gameplayTuning
        assertEquals(HARVEST_COOLDOWN_TICKS.toInt(), t.harvestCooldownTicks)
        assertEquals(10, t.harvestCooldownTicks)
        assertEquals(SHOP_RANGE_TILES, t.shopRangeTiles)
        assertEquals(6, t.shopRangeTiles)
        assertEquals(GROUND_PICKUP_RANGE_TILES, t.pickupRangeTiles)
        assertEquals(2, t.pickupRangeTiles)
        assertEquals(GuildRules.MAX_MEMBERS, t.maxGuildMembers)
        assertEquals(50, t.maxGuildMembers)
        assertEquals(0, t.maxPartySize, "no party limit unless a game sets one")
        assertEquals(ActionCombatRules.TARGET_RADIUS, t.actionTargetRadiusPixels)
        assertEquals(12, t.actionTargetRadiusPixels)
        val rules = MorganaClassic.definition.gearRules
        assertEquals(GearRules.DEFAULT, rules)
        assertEquals(ItemStack.PLUS_WEAPON_DAMAGE, rules.plusWeaponDamage)
        assertEquals(ItemStack.PLUS_ARMOR_DEFENSE, rules.plusArmorDefense)
        assertEquals(ItemStack.PLUS_RING_HP, rules.plusRingHp)
        assertEquals(2, rules.unarmedDamage)
        assertEquals(0, rules.plusPercentPerLevel)
    }

    @Test
    fun `tuning is validated with a pointer for each field`() {
        fun pointers(t: GameplayTuning) = GameDefinitionValidator.validate(MorganaClassic.definition.copy(tuning = t)).map { it.pointer }
        assertTrue(GameDefinitionValidator.validate(MorganaClassic.definition.copy(tuning = GameplayTuning())).isEmpty(), "the defaults are valid")
        assertTrue(GameDefinitionValidator.validate(MorganaClassic.definition.copy(tuning = GameplayTuning(maxPartySize = 4, plusPercentPerLevel = 3, harvestCooldownTicks = 0))).isEmpty())
        assertTrue("/tuning/harvestCooldownTicks" in pointers(GameplayTuning(harvestCooldownTicks = -1)))
        assertTrue("/tuning/shopRangeTiles" in pointers(GameplayTuning(shopRangeTiles = 0)))
        assertTrue("/tuning/pickupRangeTiles" in pointers(GameplayTuning(pickupRangeTiles = 51)))
        assertTrue("/tuning/unarmedDamage" in pointers(GameplayTuning(unarmedDamage = -1)))
        assertTrue("/tuning/plusWeaponDamage" in pointers(GameplayTuning(plusWeaponDamage = -1)))
        assertTrue("/tuning/plusPercentPerLevel" in pointers(GameplayTuning(plusPercentPerLevel = 101)))
        assertTrue("/tuning/maxGuildMembers" in pointers(GameplayTuning(maxGuildMembers = 0)))
        assertTrue("/tuning/maxPartySize" in pointers(GameplayTuning(maxPartySize = -2)))
        assertTrue("/tuning/actionTargetRadiusPixels" in pointers(GameplayTuning(actionTargetRadiusPixels = 201)))
    }

    // ---- harvesting ------------------------------------------------------------------------------------------------------------

    private val pickaxe = ItemDefinition(id = 10, name = "Pickaxe", type = ItemType.TOOL, toolType = ToolType.PICKAXE)
    private val ore = ItemDefinition(id = 11, name = "Ore", type = ItemType.MATERIAL, maxStack = 99)

    private fun miner(tuning: GameplayTuning?): WorldSimulation {
        val s = WorldSimulation(SeededRandomSource(8L), game = game(tuning))
        s.resources[1] = ResourceDefinition(
            id = 1, name = "Vein", category = ResourceCategory.ORE_VEIN, requiredTool = ToolType.PICKAXE, rewardItemId = 11,
            minYield = 1, maxYield = 1, maxHealth = 50, respawnTicks = 100, bonusDrops = emptyList<NpcDropEntry>()
        )
        s.registerMap(MapDefinition(id = 1, name = "Mine", width = 10, height = 10).withCell(6, 5, MapCell(attribute = TileAttribute.ResourceNode(1))))
        for (d in listOf(pickaxe, ore)) s.items[d.id] = d
        s.spawnOrUpdatePlayer(
            CharacterState(
                id = "p1", accountId = "a", name = "Miner", position = WorldPosition.fromTile(1, 5, 5, Direction.RIGHT),
                inventory = Inventory().addItem(pickaxe, 1).updatedInventory
            )
        )
        return s
    }

    @Test
    fun `the harvest cooldown is the game's, so gathering can be fast or slow`() {
        val normal = miner(null)
        assertTrue(normal.playerInteractFacing("p1").isNotEmpty(), "the first swing lands")
        assertTrue(normal.playerInteractFacing("p1").isEmpty(), "and the next one, straight away, is too soon")

        val instant = miner(GameplayTuning(harvestCooldownTicks = 0))
        instant.playerInteractFacing("p1")
        assertTrue(instant.playerInteractFacing("p1").isNotEmpty(), "with no cooldown swings repeat at once")

        val slow = miner(GameplayTuning(harvestCooldownTicks = 40))
        slow.playerInteractFacing("p1")
        repeat(HARVEST_COOLDOWN_TICKS.toInt() + 5) { slow.stepTick() }
        assertTrue(slow.playerInteractFacing("p1").isEmpty(), "the default cooldown has passed but this game's has not")
        repeat(40) { slow.stepTick() }
        assertTrue(slow.playerInteractFacing("p1").isNotEmpty())
    }

    // ---- ranges ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the shop range is the game's`() {
        fun buyFrom(tuning: GameplayTuning?, distance: Int): Boolean {
            val gold = ItemDefinition(id = 1, name = "Gold", type = ItemType.CURRENCY, stackable = true, maxStack = 9999, price = 1)
            val potion = ItemDefinition(id = 2, name = "Potion", type = ItemType.CONSUMABLE, stackable = true, price = 20)
            val sim = WorldSimulation(SeededRandomSource(3L), game = game(tuning))
            sim.items[1] = gold; sim.items[2] = potion
            sim.shops[1] = com.morgana.domain.content.ShopDefinition(
                id = 1, name = "Shop", currencyItemId = 1, buyRatePercent = 100, sellRatePercent = 50,
                entries = listOf(com.morgana.domain.content.ShopEntry(itemId = 2, amount = 1))
            )
            sim.registerMap(MapDefinition(id = 1, name = "Town", width = 40, height = 40))
            sim.spawnOrUpdatePlayer(CharacterState(id = "p", accountId = "a", name = "P", position = WorldPosition.fromTile(1, 5, 5, Direction.DOWN)))
            sim.players["p"] = sim.players.getValue("p").copy(inventory = Inventory().addItem(gold, 100).updatedInventory)
            sim.shopSessions["p"] = ShopSession(1, 1, 5 + distance, 5)
            return sim.shopBuy("p", 1, 0, 1).any { it is com.morgana.domain.sim.WorldSimulationEvent.CharacterUpdated }
        }
        assertTrue(buyFrom(null, SHOP_RANGE_TILES))
        assertFalse(buyFrom(null, SHOP_RANGE_TILES + 1))
        assertTrue(buyFrom(GameplayTuning(shopRangeTiles = 10), SHOP_RANGE_TILES + 3), "a game with a longer reach")
        assertFalse(buyFrom(GameplayTuning(shopRangeTiles = 2), 3), "and one with a shorter one")
    }

    @Test
    fun `the pickup range is the game's`() {
        val gold = ItemDefinition(id = 1, name = "Gold", type = ItemType.CURRENCY, stackable = true, maxStack = 9999, price = 1)
        fun pickedUp(tuning: GameplayTuning?, distance: Int): Boolean {
            val sim = WorldSimulation(SeededRandomSource(3L), game = game(tuning))
            sim.items[1] = gold
            sim.registerMap(MapDefinition(id = 1, name = "Field", width = 40, height = 40))
            sim.spawnOrUpdatePlayer(CharacterState(id = "p", accountId = "a", name = "P", position = WorldPosition.fromTile(1, 5, 5, Direction.DOWN)))
            sim.mapGroundItems[1] = mutableListOf(MapGroundItem("g", 1, 5 + distance, 5, ItemStack(1, 7)))
            sim.pickupGroundItem("p")
            return sim.players.getValue("p").inventory.totalCountOf(1) == 7
        }
        assertTrue(pickedUp(null, GROUND_PICKUP_RANGE_TILES))
        assertFalse(pickedUp(null, GROUND_PICKUP_RANGE_TILES + 1))
        assertTrue(pickedUp(GameplayTuning(pickupRangeTiles = 5), 5))
        assertFalse(pickedUp(GameplayTuning(pickupRangeTiles = 1), 2))
    }

    // ---- gear ------------------------------------------------------------------------------------------------------------------

    private val blade = ItemDefinition(id = 2, name = "Blade", type = ItemType.WEAPON, attackDamage = 100)
    private val plate = ItemDefinition(id = 3, name = "Plate", type = ItemType.ARMOR, armorDefense = 50)
    private val ring = ItemDefinition(id = 4, name = "Ring", type = ItemType.RING)
    private val catalog = mapOf(2 to blade, 3 to plate, 4 to ring)

    private fun rulesOf(tuning: GameplayTuning?) = MorganaClassic.definition.copy(tuning = tuning).gearRules

    @Test
    fun `plus levels follow the game's values, flat or as a percentage of the item`() {
        val gear = EquipmentLoadout(
            mapOf(EquipmentSlot.WEAPON to ItemStack(2, plus = 5), EquipmentSlot.ARMOR to ItemStack(3, plus = 4), EquipmentSlot.RING to ItemStack(4, plus = 2))
        )
        // The engine's old values: +3 damage, +2 defence and +15 HP per level.
        val old = rulesOf(null)
        assertEquals(15, gear.rolledAttackDamage(catalog, old))
        assertEquals(50 + 8, gear.totalArmorDefense(catalog, old))
        assertEquals(30L, gear.totalVitalMaxBonuses("hp", catalog, old))

        // Percentages only: +3% of 100 damage a level is 15 at +5; +3% of 50 defence a level is 6 at +4. Flat values off.
        val percent = rulesOf(GameplayTuning(plusWeaponDamage = 0, plusArmorDefense = 0, plusRingHp = 0, plusPercentPerLevel = 3))
        assertEquals(15, gear.rolledAttackDamage(catalog, percent))
        assertEquals(50 + 6, gear.totalArmorDefense(catalog, percent))
        assertEquals(0L, gear.totalVitalMaxBonuses("hp", catalog, percent))

        // Both at once add up.
        val both = rulesOf(GameplayTuning(plusWeaponDamage = 1, plusPercentPerLevel = 10))
        assertEquals(5 * 1 + 100 * 5 * 10 / 100, gear.rolledAttackDamage(catalog, both))
        assertEquals(100 + 2 * 3, gear.weaponDamageOf(ItemStack(2, plus = 2), catalog, rulesOf(GameplayTuning(plusWeaponDamage = 3))), "weaponDamageOf follows the same rules")
    }

    @Test
    fun `unarmed damage is the game's`() {
        assertEquals(2, EquipmentLoadout().weaponBaseDamage(null, catalog, rulesOf(null)))
        assertEquals(7, EquipmentLoadout().weaponBaseDamage(null, catalog, rulesOf(GameplayTuning(unarmedDamage = 7))))
    }

    // ---- guilds ----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the guild size is the game's`() {
        val guild = GuildRules.create("g", "Crew", "CRW", "c1").let { it.copy(memberRanks = (1..3).associate { n -> "c$n" to GuildRules.MEMBER }) }
        assertTrue(GuildRules.join(guild, "new") is GuildRules.Change.Updated, "room under the default of 50")
        assertTrue(GuildRules.join(guild, "new", maxMembers = 3) is GuildRules.Change.Refused, "full at 3")
        val refused = GuildRules.join(guild, "new", maxMembers = 3) as GuildRules.Change.Refused
        assertTrue(refused.reason.contains("full (3 members)"), refused.reason)
        assertTrue(GuildRules.join(guild, "new", maxMembers = 4) is GuildRules.Change.Updated)
    }

    // ---- action combat ---------------------------------------------------------------------------------------------------------

    @Test
    fun `the action combat target size is the game's`() {
        val rect = SkillHitbox(0, 1, HitboxShape.RECT, lengthPixels = 64, widthPixels = 32)
        assertFalse(ActionCombatRules.inShape(rect, 0.0, 0.0, 1.0, 0.0, 120.0, 0.0), "a body 120 pixels away is out of a 64 pixel strike")
        assertTrue(ActionCombatRules.inShape(rect, 0.0, 0.0, 1.0, 0.0, 120.0, 0.0, targetRadius = 60), "but a bigger body reaches it")
        assertTrue(ActionCombatRules.inShape(rect, 0.0, 0.0, 1.0, 0.0, 70.0, 0.0), "and a body just past the end still counts at the default size")
        assertFalse(ActionCombatRules.inShape(rect, 0.0, 0.0, 1.0, 0.0, 70.0, 0.0, targetRadius = 0), "while a point-sized body does not")
        assertTrue(ActionCombatRules.reachTiles(rect, 32, targetRadius = 100) > ActionCombatRules.reachTiles(rect, 32))
    }
}
