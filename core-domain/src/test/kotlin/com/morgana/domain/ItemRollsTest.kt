package com.morgana.domain

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.NpcDropEntry
import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.CompiledGame
import com.morgana.domain.game.GameDefinitionValidator
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.item.AffixDefinition
import com.morgana.domain.item.AffixPosition
import com.morgana.domain.item.AffixStat
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemRollText
import com.morgana.domain.item.ItemRoller
import com.morgana.domain.item.ItemRolls
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.LootRules
import com.morgana.domain.item.RarityDefinition
import com.morgana.domain.item.RolledAffix
import com.morgana.domain.map.FloorGenerator
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.WeightedLoot
import com.morgana.domain.script.ScriptEffect
import com.morgana.domain.sim.InstanceSettings
import com.morgana.domain.sim.SeededRandomSource
import com.morgana.domain.sim.ShopSession
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.sim.WorldSimulationEvent
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Diablo-style loot: rarities and affixes rolled on dropped gear, carried by the item, and counted while it is worn. */
class ItemRollsTest {

    private val rules = LootRules(
        rarities = listOf(
            RarityDefinition("common", "Common", "#e5e7eb", weight = 60),
            RarityDefinition("magic", "Magic", "#60a5fa", weight = 30, affixesMin = 1, affixesMax = 2, priceMultiplierPercent = 200),
            RarityDefinition("rare", "Rare", "#facc15", weight = 10, minItemLevel = 5, affixesMin = 3, affixesMax = 4, priceMultiplierPercent = 500)
        ),
        affixes = listOf(
            AffixDefinition("strong", "Strong", AffixPosition.PREFIX, AffixStat.ATTRIBUTE, "str", 1, 3),
            AffixDefinition("mighty", "Mighty", AffixPosition.PREFIX, AffixStat.ATTRIBUTE, "str", 4, 6, minItemLevel = 5),
            AffixDefinition("cruel", "Cruel", AffixPosition.PREFIX, AffixStat.ATTACK_DAMAGE, min = 2, max = 5, slots = setOf(EquipmentSlot.WEAPON)),
            AffixDefinition("sturdy", "Sturdy", AffixPosition.PREFIX, AffixStat.ARMOR_DEFENSE, min = 1, max = 4),
            AffixDefinition("of_the_bear", "of the Bear", AffixPosition.SUFFIX, AffixStat.VITAL_MAX, "hp", 5, 15),
            AffixDefinition("of_wisdom", "of Wisdom", AffixPosition.SUFFIX, AffixStat.ATTRIBUTE, "int", 1, 3),
            AffixDefinition("of_speed", "of Speed", AffixPosition.SUFFIX, AffixStat.ATTRIBUTE, "agi", 1, 3)
        )
    )

    private val sword = ItemDefinition(id = 2, name = "Sword", type = ItemType.WEAPON, attackDamage = 5, price = 40)
    private val helm = ItemDefinition(id = 3, name = "Helm", type = ItemType.HELMET, armorDefense = 2, price = 30)
    private val potion = ItemDefinition(id = 4, name = "Potion", type = ItemType.CONSUMABLE)
    private val arrows = ItemDefinition(id = 5, name = "Arrows", type = ItemType.AMMO)
    private val relic = ItemDefinition(id = 6, name = "Quest Relic", type = ItemType.AMULET, rollAffixes = false)
    private val gold = ItemDefinition(id = 1, name = "Gold", type = ItemType.CURRENCY)

    private val lootGame = CompiledGame.compile(MorganaClassic.definition.copy(loot = rules))

    // ---- the roller ----------------------------------------------------------------------------------------

    @Test
    fun `only gear rolls, and only in a game with rarities`() {
        val rng = SeededRandomSource(1L)
        assertNotNull(ItemRoller.roll(sword, 1, rules, rng))
        assertNull(ItemRoller.roll(potion, 1, rules, rng), "consumables never roll")
        assertNull(ItemRoller.roll(arrows, 1, rules, rng), "ammunition stacks, so it never rolls")
        assertNull(ItemRoller.roll(relic, 1, rules, rng), "items can opt out")
        assertNull(ItemRoller.roll(sword, 1, null, rng), "a game without loot rules hands out plain items")
        assertNull(ItemRoller.roll(sword, 1, LootRules(), rng), "no rarities, no rolls")
    }

    @Test
    fun `rolls are deterministic and respect rarity counts, item levels, slots and value ranges`() {
        fun rollMany(seed: Long, level: Int, def: ItemDefinition) = SeededRandomSource(seed).let { rng -> List(400) { ItemRoller.roll(def, level, rules, rng)!! } }
        assertEquals(rollMany(9L, 3, sword), rollMany(9L, 3, sword), "same seed, same loot")
        assertNotEquals(rollMany(9L, 3, sword), rollMany(10L, 3, sword))

        val low = rollMany(9L, 1, helm)
        assertTrue(low.none { it.rarity == "rare" }, "rares need item level 5")
        assertTrue(low.flatMap { it.affixes }.none { it.id == "mighty" }, "tiered affixes need their item level")
        assertTrue(low.flatMap { it.affixes }.none { it.id == "cruel" }, "a weapon-only affix never lands on a helm")
        assertTrue(low.all { it.itemLevel == 1 })

        val high = rollMany(9L, 8, sword)
        assertTrue(high.any { it.rarity == "rare" } && high.any { it.rarity == "magic" } && high.any { it.rarity == "common" })
        for (r in high) {
            val rarity = rules.rarity(r.rarity)!!
            assertTrue(r.affixes.size in rarity.affixesMin..rarity.affixesMax, "affix count follows the rarity: $r")
            assertEquals(r.affixes.size, r.affixes.map { it.stat to it.statId }.toSet().size, "never two affixes on one stat: $r")
            for (a in r.affixes) {
                val def = rules.affix(a.id)!!
                assertTrue(a.value in def.min..def.max, "value within the affix's range: $a")
                assertEquals(def.stat, a.stat)
                assertEquals(def.statId, a.statId)
            }
        }
        assertTrue(high.flatMap { it.affixes }.any { it.id == "cruel" }, "weapon affixes do roll on weapons")
    }

    @Test
    fun `a rarity with weight 0 exists and validates but never drops`() {
        val ladder = LootRules(rarities = listOf(
            RarityDefinition("common", "Common", weight = 90),
            RarityDefinition("fine", "Fine", weight = 10),
            RarityDefinition("masterwork", "Masterwork", weight = 0, priceMultiplierPercent = 120)
        ))
        assertEquals(emptyList<Any>(), GameDefinitionValidator.validate(MorganaClassic.definition.copy(loot = ladder)), "weight 0 is valid")
        assertNotNull(ladder.rarity("masterwork"), "it is still a rarity a crafter can reach")
        val rng = SeededRandomSource(5L)
        val seen = List(2000) { ItemRoller.roll(sword, 10, ladder, rng)!!.rarity }.toSet()
        assertEquals(setOf("common", "fine"), seen, "2000 drops never include the weight-0 rarity")
    }

    @Test
    fun `a rolled item is named by its first prefix and suffix and priced by its rarity`() {
        val rolls = ItemRolls("magic", 3, listOf(RolledAffix("cruel", AffixStat.ATTACK_DAMAGE, value = 3), RolledAffix("of_the_bear", AffixStat.VITAL_MAX, "hp", 10)))
        assertEquals("Cruel Sword of the Bear", ItemRollText.displayName(sword, rolls, rules))
        assertEquals("Sword", ItemRollText.displayName(sword, null, rules))
        assertEquals("#60a5fa", ItemRollText.color(rolls, rules))
        assertEquals(80, ItemRollText.price(40, rolls, rules))
        assertEquals(40, ItemRollText.price(40, null, rules))
        assertEquals(listOf("+3 damage", "+10 max HP"), ItemRollText.affixLines(rolls) { _, id -> id.uppercase() })
    }

    // ---- worn bonuses --------------------------------------------------------------------------------------

    @Test
    fun `worn rolled affixes add to attributes, vitals, armour and weapon damage`() {
        val items = mapOf(sword.id to sword, helm.id to helm)
        val rolledHelm = ItemStack(helm.id, rolls = ItemRolls("rare", 6, listOf(
            RolledAffix("mighty", AffixStat.ATTRIBUTE, "str", 5),
            RolledAffix("sturdy", AffixStat.ARMOR_DEFENSE, value = 3),
            RolledAffix("of_the_bear", AffixStat.VITAL_MAX, "hp", 12)
        )))
        val rolledSword = ItemStack(sword.id, rolls = ItemRolls("magic", 2, listOf(RolledAffix("cruel", AffixStat.ATTACK_DAMAGE, value = 4))))
        val loadout = EquipmentLoadout(mapOf(EquipmentSlot.HELMET to rolledHelm, EquipmentSlot.WEAPON to rolledSword))
        assertEquals(5, loadout.totalAttributeBonuses(items)["str"])
        assertEquals(2 + 3, loadout.totalArmorDefense(items))
        assertEquals(12L, loadout.totalVitalMaxBonuses("hp", items))
        assertEquals(4, loadout.rolledAttackDamage(items))

        val plain = CharacterState(id = "p", accountId = "a", name = "P").recalculateVitals(MorganaClassic.COMPILED, items, refill = true)
        val geared = plain.copy(equipment = loadout).recalculateVitals(MorganaClassic.COMPILED, items, refill = true)
        assertTrue(geared.vitals.getValue("hp").max > plain.vitals.getValue("hp").max, "rolled max-HP (and STR) raise the pool")
    }

    // ---- items keep their rolls when they move -------------------------------------------------------------

    @Test
    fun `addStack keeps what a non-stackable item carries and merges stackables as before`() {
        val rolled = ItemStack(sword.id, durability = 61, rolls = ItemRolls("magic", 2, listOf(RolledAffix("strong", AffixStat.ATTRIBUTE, "str", 2))))
        val res = Inventory(maxSlots = 4).addStack(sword, rolled)
        assertEquals(1, res.addedAmount)
        assertEquals(rolled, res.updatedInventory.slots[0])

        val two = Inventory(maxSlots = 4).addStack(sword, rolled.copy(amount = 2)).updatedInventory
        assertEquals(listOf(rolled, rolled, null, null), two.slots, "each unit is its own copy")

        val full = Inventory(maxSlots = 1, slots = listOf(ItemStack(helm.id))).addStack(sword, rolled)
        assertEquals(1, full.overflowAmount)

        val pots = Inventory(maxSlots = 2, slots = listOf(ItemStack(potion.id, 3), null)).addStack(potion, ItemStack(potion.id, 2))
        assertEquals(ItemStack(potion.id, 5), pots.updatedInventory.slots[0])
    }

    private fun lootWorld(game: CompiledGame = lootGame, seed: Long = 5L): WorldSimulation {
        val sim = WorldSimulation(SeededRandomSource(seed), game = game)
        listOf(gold, sword, helm, potion).forEach { sim.items[it.id] = it }
        sim.npcs[50] = NpcDefinition(
            id = 50, name = "Treasure Imp", behavior = NpcBehavior.PASSIVE, level = 7, vitals = mapOf("hp" to 1),
            drops = listOf(NpcDropEntry(sword.id, 3, 3, 10_000), NpcDropEntry(potion.id, 2, 2, 10_000))
        )
        sim.shops[1] = ShopDefinition(id = 1, name = "Smith", sellRatePercent = 50)
        sim.registerMap(MapDefinition(id = 1, name = "Den", width = 10, height = 10, npcSpawns = listOf(MapNpcSpawnSlot(0, 50, 3, 3, 100))))
        val hero = CharacterState(
            id = "hero", accountId = "a", name = "Hero",
            attributes = mapOf("str" to 50, "def" to 10, "int" to 10, "agi" to 10, "vit" to 10),
            position = WorldPosition.fromTile(1, 3, 2, Direction.DOWN), lastAttackTick = -20L
        ).recalculateVitals(sim.game, sim.items, refill = true)
        sim.spawnOrUpdatePlayer(hero)
        sim.stepTick()
        return sim
    }

    @Test
    fun `a slain monster drops each piece of gear with its own roll at the monster's level`() {
        val sim = lootWorld()
        assertTrue(sim.playerAttack("hero").any { it is WorldSimulationEvent.NpcKilled })
        val ground = sim.mapGroundItems.getValue(1)
        val swords = ground.filter { it.stack.itemId == sword.id }
        assertEquals(3, swords.size, "three swords lie as three items, each rolled")
        assertTrue(swords.all { it.stack.amount == 1 && it.stack.rolls?.itemLevel == 7 })
        val pots = ground.single { it.stack.itemId == potion.id }
        assertEquals(2, pots.stack.amount)
        assertNull(pots.stack.rolls, "consumables drop plain")
    }

    @Test
    fun `a game without loot rules drops plain stacks exactly as before`() {
        val sim = lootWorld(game = MorganaClassic.COMPILED)
        sim.playerAttack("hero")
        val swords = sim.mapGroundItems.getValue(1).filter { it.stack.itemId == sword.id }
        assertEquals(listOf(ItemStack(sword.id, 3)), swords.map { it.stack })
    }

    @Test
    fun `gear can drop with a socket by its rarity's chance, only in a game that allows sockets`() {
        fun game(chance: Int, upgrade: com.morgana.domain.game.GearUpgradeDefinition?): CompiledGame {
            val socketed = rules.copy(rarities = rules.rarities.map { it.copy(socketChanceBasisPoints = chance) })
            return CompiledGame.compile(MorganaClassic.definition.copy(loot = socketed, gearUpgrade = upgrade))
        }
        fun socketsOf(g: CompiledGame): List<List<Int>> {
            val sim = lootWorld(game = g)
            sim.playerAttack("hero")
            return sim.mapGroundItems.getValue(1).filter { it.stack.itemId == sword.id }.map { it.stack.sockets }
        }
        val allowed = com.morgana.domain.game.GearUpgradeDefinition(maxSockets = 2)
        assertEquals(List(3) { listOf(0) }, socketsOf(game(10_000, allowed)), "a sure chance gives every dropped sword an empty socket")
        assertEquals(List(3) { emptyList<Int>() }, socketsOf(game(0, allowed)), "a rarity with no chance never drops socketed")
        assertEquals(List(3) { emptyList<Int>() }, socketsOf(game(10_000, null)), "a game with no gearUpgrade allows no sockets")
    }

    @Test
    fun `rolls survive pickup, equipping, unequipping, the bank and dropping, and raise the sell price`() {
        val sim = lootWorld()
        sim.playerAttack("hero")
        // Pick the loot up where it fell.
        sim.players["hero"] = sim.players.getValue("hero").let { it.copy(position = WorldPosition.fromTile(1, 3, 3, Direction.DOWN)) }
        val onGround = sim.mapGroundItems.getValue(1).filter { it.stack.itemId == sword.id }.map { it.stack }
        repeat(4) { sim.pickupGroundItem("hero") }
        fun bag() = sim.players.getValue("hero").inventory
        val carried = bag().slots.filterNotNull().filter { it.itemId == sword.id }
        assertEquals(onGround.sortedBy { it.hashCode() }, carried.sortedBy { it.hashCode() }, "picked up with their rolls")

        val slot = bag().slots.indexOfFirst { it?.itemId == sword.id }
        val stack = bag().slots[slot]!!
        sim.useInventorySlot("hero", slot)
        assertEquals(stack, sim.players.getValue("hero").equipment.getEquipped(EquipmentSlot.WEAPON))
        sim.unequipSlot("hero", EquipmentSlot.WEAPON)
        assertTrue(stack in bag().slots, "unequipped with its roll")

        val at = bag().slots.indexOf(stack)
        sim.bankDeposit("hero", at)
        assertEquals(stack, sim.players.getValue("hero").bank.slots.filterNotNull().single())
        sim.bankWithdraw("hero", 0)
        assertTrue(stack in bag().slots, "withdrawn with its roll")

        // Selling: the rarity multiplies the price.
        sim.shopSessions["hero"] = ShopSession(1, 1, 3, 3)
        val sellAt = bag().slots.indexOf(stack)
        val goldBefore = bag().totalCountOf(gold.id)
        sim.shopSell("hero", 1, sellAt)
        val expected = maxOf(1, ItemRollText.price(sword.price, stack.rolls, rules) * 50 / 100)
        assertEquals(goldBefore + expected, bag().totalCountOf(gold.id))
    }

    @Test
    fun `trading hands over the rolled item itself`() {
        val sim = lootWorld()
        val rolled = ItemStack(sword.id, rolls = ItemRolls("magic", 4, listOf(RolledAffix("strong", AffixStat.ATTRIBUTE, "str", 3))))
        val other = CharacterState(id = "pal", accountId = "b", name = "Pal", position = WorldPosition.fromTile(1, 4, 2, Direction.LEFT))
        sim.spawnOrUpdatePlayer(other)
        sim.players["hero"] = sim.players.getValue("hero").let { it.copy(inventory = it.inventory.addStack(sword, rolled).updatedInventory) }
        sim.initiateTrade("hero", "pal")
        sim.addTradeOfferItem("hero", sim.players.getValue("hero").inventory.slots.indexOf(rolled), 1)
        sim.lockAndConfirmTrade("hero", false); sim.lockAndConfirmTrade("pal", false)
        sim.lockAndConfirmTrade("hero", true); sim.lockAndConfirmTrade("pal", true)
        assertTrue(rolled in sim.players.getValue("pal").inventory.slots, "the partner gets the same roll")
    }

    @Test
    fun `generated floor loot rolls at the floor's item level, the same way for the same seed`() {
        fun floorLoot(): List<ItemStack> {
            val sim = WorldSimulation(SeededRandomSource(7L), game = lootGame)
            sim.instanceSettings = InstanceSettings(emptyTicks = 10)
            sim.items[sword.id] = sword
            sim.registerMap(MapDefinition(id = 1, name = "Hub", width = 12, height = 12))
            val recipe = FloorGenerator(loot = listOf(WeightedLoot(sword.id, 2, 2)), lootMin = 3, lootMax = 3, itemLevel = 6, exitMapId = 1, fixedSeed = 99L)
            sim.registerMap(MapDefinition(id = 2, name = "Crypt", width = 5, height = 5, instanced = true, generator = recipe))
            sim.spawnOrUpdatePlayer(CharacterState(id = "p", accountId = "a", name = "P", position = WorldPosition.fromTile(1, 3, 3, Direction.DOWN)))
            sim.applyScriptEffect(ScriptEffect.WarpPlayer("p", 2, 0, 0))
            return sim.mapGroundItems.getValue(sim.players.getValue("p").position.mapId).map { it.stack }
        }
        val loot = floorLoot()
        assertEquals(6, loot.size, "three piles of two swords, one item each")
        assertTrue(loot.all { it.amount == 1 && it.rolls?.itemLevel == 6 })
        assertEquals(loot, floorLoot(), "a fixed-seed floor always lays the same loot")
    }

    // ---- validation and storage ----------------------------------------------------------------------------

    @Test
    fun `the game definition validator checks loot rules`() {
        assertEquals(emptyList<Any>(), GameDefinitionValidator.validate(lootGame.definition))
        val bad = MorganaClassic.definition.copy(
            loot = LootRules(
                rarities = listOf(
                    RarityDefinition("magic", "Magic", "blue", weight = -1, minItemLevel = 2, affixesMin = 3, affixesMax = 1),
                    RarityDefinition("magic", "Again", weight = 1, minItemLevel = 2)
                ),
                affixes = listOf(
                    AffixDefinition("x", "X", stat = AffixStat.ATTRIBUTE, statId = "luck", min = 5, max = 1),
                    AffixDefinition("y", "Y", stat = AffixStat.VITAL_MAX, statId = "str", min = 1, max = 2),
                    AffixDefinition("z", "Z", stat = AffixStat.ATTACK_DAMAGE, statId = "str", min = 1, max = 2, slots = setOf(EquipmentSlot.AMMO))
                )
            )
        )
        val pointers = GameDefinitionValidator.validate(bad).map { it.pointer }.toSet()
        for (p in listOf(
            "/loot/rarities/0/color", "/loot/rarities/0/weight", "/loot/rarities/0/affixesMin", "/loot/rarities/1/id", "/loot/rarities",
            "/loot/affixes/0/statId", "/loot/affixes/0/min", "/loot/affixes/1/statId", "/loot/affixes/2/statId", "/loot/affixes/2/slots"
        )) assertTrue(p in pointers, "expected an issue at $p, got $pointers")
    }

    @Test
    fun `merging gear increases plus and adds worn bonuses`() {
        val swordDef = ItemDefinition(id = 10, name = "Blade", type = ItemType.WEAPON, attackDamage = 8)
        val helmDef = ItemDefinition(id = 11, name = "Helm", type = ItemType.HELMET, armorDefense = 4)
        val ringDef = ItemDefinition(id = 12, name = "Ring", type = ItemType.RING)
        val sim = WorldSimulation(SeededRandomSource(7L), game = lootGame)
        sim.registerMap(MapDefinition(id = 1, name = "Town", width = 10, height = 10))
        sim.items[10] = swordDef
        sim.items[11] = helmDef
        sim.items[12] = ringDef
        val inv = Inventory(maxSlots = 10).copy(
            slots = listOf(
                ItemStack(10, plus = 0),
                ItemStack(10, plus = 0),
                ItemStack(10, plus = 0),
                ItemStack(11, plus = 0),
                null, null, null, null, null, null
            )
        )
        sim.spawnOrUpdatePlayer(CharacterState(id = "p", accountId = "a", name = "Hero", inventory = inv))

        // Merge +0 into +0 -> +1
        sim.mergeGear("p", 0, 1)
        val afterFirst = sim.players.getValue("p").inventory
        assertEquals(1, afterFirst.getSlot(0)?.plus)
        assertEquals(null, afterFirst.getSlot(1))
        assertEquals("Blade +1", ItemRollText.displayName(swordDef, null, null, afterFirst.getSlot(0)!!.plus))

        // Cannot merge +0 (slot 2) into +1 (slot 0)
        val warnEvents = sim.mergeGear("p", 0, 2)
        assertTrue(warnEvents.any { it is WorldSimulationEvent.ChatEmitted && it.notice == "WARNING" })
        assertEquals(1, sim.players.getValue("p").inventory.getSlot(0)?.plus)
        assertEquals(0, sim.players.getValue("p").inventory.getSlot(2)?.plus)

        // Cannot merge different slots (helm slot 3 into sword slot 0)
        val slotMismatch = sim.mergeGear("p", 0, 3)
        assertTrue(slotMismatch.any { it is WorldSimulationEvent.ChatEmitted && it.notice == "WARNING" })

        // Worn +N bonuses
        val loadout = EquipmentLoadout(
            mapOf(
                EquipmentSlot.WEAPON to ItemStack(10, plus = 2),
                EquipmentSlot.HELMET to ItemStack(11, plus = 3),
                EquipmentSlot.RING to ItemStack(12, plus = 1)
            )
        )
        val catalog = mapOf(10 to swordDef, 11 to helmDef, 12 to ringDef)
        assertEquals(2 * ItemStack.PLUS_WEAPON_DAMAGE, loadout.rolledAttackDamage(catalog))
        assertEquals(4 + 3 * ItemStack.PLUS_ARMOR_DEFENSE, loadout.totalArmorDefense(catalog))
        assertEquals(1L * ItemStack.PLUS_RING_HP, loadout.totalVitalMaxBonuses("hp", catalog))
    }
}
