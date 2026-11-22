package com.morgana.client.ui.bag

import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.ShopEntry
import com.morgana.domain.item.AffixDefinition
import com.morgana.domain.item.AffixPosition
import com.morgana.domain.item.AffixStat
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemRolls
import com.morgana.domain.item.LootRules
import com.morgana.domain.item.RarityDefinition
import com.morgana.domain.item.RolledAffix
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.WeaponStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BagModelsTest {

    private val gold = ItemDefinition(id = 1, name = "Gold Coin", type = ItemType.CURRENCY, stackable = true, maxStack = 9999, price = 1)
    private val potion = ItemDefinition(id = 2, name = "Healing Draught", type = ItemType.CONSUMABLE, stackable = true, price = 8, restore = mapOf("hp" to 40))
    private val blade = ItemDefinition(id = 3, name = "Rusty Blade", type = ItemType.WEAPON, price = 5, attackDamage = 5, attackSpeedTicks = 10)
    private val longsword = ItemDefinition(id = 4, name = "Longsword", type = ItemType.WEAPON, price = 60, attackDamage = 11, attackSpeedTicks = 10, levelReq = 3, attributeBonuses = mapOf("str" to 2))
    private val tunic = ItemDefinition(id = 5, name = "Tunic", type = ItemType.ARMOR, price = 40, armorDefense = 4, vitalMaxBonuses = mapOf("hp" to 20))
    private val bone = ItemDefinition(id = 6, name = "Bone Shard", type = ItemType.MATERIAL, stackable = true, price = 3)
    private val bow = ItemDefinition(id = 7, name = "Bow", type = ItemType.WEAPON, price = 50, attackDamage = 8, attackSpeedTicks = 14, weaponStyle = WeaponStyle.RANGED_BOW, rangeTiles = 6, requiredAmmoItemId = 8)
    private val arrow = ItemDefinition(id = 8, name = "Arrow", type = ItemType.AMMO, price = 1)
    private val relic = ItemDefinition(id = 9, name = "Relic", type = ItemType.QUEST, boundOnPickup = true, price = 99)
    private val items = listOf(gold, potion, blade, longsword, tunic, bone, bow, arrow, relic).associateBy { it.id }
    private val names = StatNames(mapOf("str" to "STR"), mapOf("hp" to "HP"))

    private fun bag(vararg stacks: ItemStack?, size: Int = 8) = Inventory(maxSlots = size, slots = List(size) { stacks.getOrNull(it) })

    @Test
    fun `the all tab lists every slot, empty ones too, and a filter lists only matching items`() {
        val inv = bag(ItemStack(3), null, ItemStack(2, 5), ItemStack(6, 9), null, ItemStack(1, 40))
        val all = BagView.entries(inv, items, BagFilter.ALL, "")
        assertEquals(8, all.size)
        assertEquals(listOf(0, 2, 3, 5), all.filter { it.stack != null }.map { it.slot })
        assertEquals(listOf(0), BagView.entries(inv, items, BagFilter.GEAR, "").map { it.slot })
        assertEquals(listOf(2), BagView.entries(inv, items, BagFilter.USABLE, "").map { it.slot })
        assertEquals(listOf(3), BagView.entries(inv, items, BagFilter.MATERIALS, "").map { it.slot })
        assertEquals(listOf(5), BagView.entries(inv, items, BagFilter.OTHER, "").map { it.slot })
        assertEquals("4 / 8 slots", BagView.capacityText(inv))
        assertEquals(40, BagView.gold(inv, 1))
    }

    @Test
    fun `searching matches any part of a name regardless of case and hides empty slots`() {
        val inv = bag(ItemStack(3), null, ItemStack(2, 5), ItemStack(6, 9))
        assertEquals(listOf(2), BagView.entries(inv, items, BagFilter.ALL, "  DRAUGHT ").map { it.slot })
        assertEquals(listOf(0, 3), BagView.entries(inv, items, BagFilter.ALL, "s").map { it.slot }, "Rusty Blade and Bone Shard contain an s")
        assertTrue(BagView.entries(inv, items, BagFilter.GEAR, "draught").isEmpty(), "the filter and the search both apply")
        val unknown = bag(ItemStack(77))
        assertEquals(1, BagView.entries(unknown, items, BagFilter.ALL, "#77").size, "an item the catalogue lacks is still listed by number")
        assertTrue(BagView.entries(unknown, items, BagFilter.GEAR, "").isEmpty())
    }

    @Test
    fun `a weapon is compared with the one worn`() {
        val lines = ItemInfo.describe(longsword, blade, items, names, WearerInfo(level = 5, attributes = mapOf("str" to 10), classId = 1))
        assertEquals(InfoLine("Damage 11 (+6 vs worn)", Tone.GOOD), lines[0])
        assertEquals(InfoLine("One hit every 0.5 s", Tone.MUTED), lines[1])
        assertEquals(InfoLine("+2 STR", Tone.GOOD), lines[2])
        assertEquals(InfoLine("Needs level 3", Tone.MUTED), lines[3], "met requirements are quiet")
        val weaker = ItemInfo.describe(blade, longsword, items, names, null)
        assertEquals(InfoLine("Damage 5 (-6 vs worn)", Tone.BAD), weaker[0])
        val alone = ItemInfo.describe(blade, null, items, names, null)
        assertEquals(InfoLine("Damage 5", Tone.NEUTRAL), alone[0])
        val same = ItemInfo.describe(blade, blade, items, names, null)
        assertEquals("Damage 5", same[0].text, "no comparison with itself")
    }

    @Test
    fun `unmet requirements are red and a ranged weapon names its ammunition`() {
        val weak = WearerInfo(level = 1, attributes = mapOf("str" to 5), classId = 2)
        val needy = longsword.copy(attributeReq = mapOf("str" to 12), classReqIds = setOf(1))
        val lines = ItemInfo.describe(needy, null, items, names, weak)
        assertTrue(InfoLine("Needs level 3", Tone.BAD) in lines)
        assertTrue(InfoLine("Needs STR 12", Tone.BAD) in lines)
        assertTrue(InfoLine("Only some classes can use this", Tone.BAD) in lines)
        assertTrue(ItemInfo.unmet(needy, weak))
        assertFalse(ItemInfo.unmet(blade, weak))
        val bowLines = ItemInfo.describe(bow, null, items, names, null)
        assertTrue(InfoLine("One hit every 0.7 s, range 6", Tone.MUTED) in bowLines)
        assertTrue(InfoLine("Uses Arrow", Tone.MUTED) in bowLines)
        assertEquals("Ranged weapon", ItemInfo.kind(bow))
        assertEquals("Melee weapon", ItemInfo.kind(blade))
        assertEquals("Quest item", ItemInfo.kind(relic))
        assertEquals("Consumable", ItemInfo.kind(potion))
    }

    @Test
    fun `consumables, armour and bound items describe themselves`() {
        assertEquals(listOf(InfoLine("Restores 40 HP", Tone.GOOD)), ItemInfo.describe(potion, null, items, names, null))
        val armour = ItemInfo.describe(tunic, null, items, names, null)
        assertEquals(listOf(InfoLine("Defense 4"), InfoLine("+20 max HP", Tone.GOOD)), armour)
        assertTrue(InfoLine("Bound to you: cannot be sold or traded", Tone.MUTED) in ItemInfo.describe(relic, null, items, names, null))
        assertEquals("XYZ", StatNames().attribute("xyz"), "an unknown stat falls back to its id in capitals")
    }

    // ---- trade arithmetic ----

    private val shop = ShopDefinition(id = 1, name = "Camp", currencyItemId = 1, buyRatePercent = 100, sellRatePercent = 50, entries = emptyList())

    @Test
    fun `prices on screen match what the server charges`() {
        assertEquals(8, TradeMath.buyPrice(shop, ShopEntry(2), potion))
        assertEquals(1, TradeMath.buyPrice(shop.copy(buyRatePercent = 10), ShopEntry(6), bone), "never below 1")
        assertEquals(25, TradeMath.buyPrice(shop, ShopEntry(2, priceOverride = 25), potion))
        assertEquals(30, TradeMath.sellValue(shop, longsword))
        assertEquals(1, TradeMath.sellValue(shop, bone), "3 * 50% rounds down to 1")
        assertEquals(1, TradeMath.sellValue(shop.copy(sellRatePercent = 1), longsword).coerceAtLeast(1))
        assertEquals(1, TradeMath.currencyOf(shop, ShopEntry(2)))
        assertEquals(6, TradeMath.currencyOf(shop, ShopEntry(2, currencyItemId = 6)))
    }

    @Test
    fun `selling is allowed except for bound items and the shop's currency`() {
        assertTrue(TradeMath.canSell(shop, potion))
        assertFalse(TradeMath.canSell(shop, relic))
        assertFalse(TradeMath.canSell(shop, gold))
    }

    @Test
    fun `the most you can buy is limited by gold, room and the request cap`() {
        val bundle = ShopEntry(6, amount = 10)
        assertEquals(5, TradeMath.maxBuy(gold = 100, unitPrice = 20, entry = ShopEntry(2), roomUnits = 50))
        assertEquals(2, TradeMath.maxBuy(gold = 100, unitPrice = 1, entry = bundle, roomUnits = 25), "room for 25 bones is two bundles of ten")
        assertEquals(0, TradeMath.maxBuy(gold = 5, unitPrice = 20, entry = ShopEntry(2), roomUnits = 50))
        assertEquals(0, TradeMath.maxBuy(gold = 100, unitPrice = 5, entry = ShopEntry(3), roomUnits = 0), "a full bag")
        assertEquals(TradeMath.MAX_QUANTITY, TradeMath.maxBuy(gold = 5_000_000, unitPrice = 1, entry = ShopEntry(2), roomUnits = 100_000))
        assertEquals(0, TradeMath.maxBuy(gold = 100, unitPrice = 0, entry = ShopEntry(2), roomUnits = 50))
    }

    @Test
    fun `quantity buttons are 1, 5, 10 and Max without repeats`() {
        assertEquals(emptyList<Int>(), TradeMath.quantityChoices(0))
        assertEquals(listOf(1), TradeMath.quantityChoices(1))
        assertEquals(listOf(1, 3), TradeMath.quantityChoices(3))
        assertEquals(listOf(1, 5), TradeMath.quantityChoices(5))
        assertEquals(listOf(1, 5, 7), TradeMath.quantityChoices(7))
        assertEquals(listOf(1, 5, 10, 11), TradeMath.quantityChoices(11))
        assertEquals(listOf(1, 5, 10, 999), TradeMath.sellChoices(5000))
    }

    @Test
    fun `room for an item counts partial stacks and empty slots`() {
        val inv = bag(ItemStack(2, 990), ItemStack(3), null, null, size = 4)
        assertEquals(9 + 2 * 999, inv.roomFor(potion), "9 more fit in the open stack, plus two empty slots of 999")
        assertEquals(2, inv.roomFor(blade), "a weapon needs a slot each")
        assertEquals(0, bag(ItemStack(3), size = 1).roomFor(blade))
    }

    @Test
    fun `an item tooltip names the item, its kind and stack, and ends with its value`() {
        val tip = ItemInfo.tip(potion, 3, null, items, names, null)
        assertEquals("Healing Draught", tip.title)
        assertEquals("Consumable  x3", tip.kind)
        assertTrue(tip.lines.any { it.text == "Restores 40 HP" })
        assertEquals(InfoLine("Value 8 each", Tone.MUTED), tip.lines.last())
        assertEquals(InfoLine("Value 5", Tone.MUTED), ItemInfo.tip(blade, 1, null, items, names, null).lines.last())
    }

    @Test
    fun `a rolled item's tip shows its rarity name, colour, affixes, item level and rarity price`() {
        val loot = LootRules(
            rarities = listOf(RarityDefinition("magic", "Magic", "#60a5fa", weight = 1, affixesMax = 2, priceMultiplierPercent = 300)),
            affixes = listOf(
                AffixDefinition("cruel", "Cruel", AffixPosition.PREFIX, AffixStat.ATTACK_DAMAGE, min = 1, max = 5),
                AffixDefinition("of_life", "of Life", AffixPosition.SUFFIX, AffixStat.VITAL_MAX, "hp", 1, 20)
            )
        )
        val rolls = ItemRolls("magic", 4, listOf(RolledAffix("cruel", AffixStat.ATTACK_DAMAGE, value = 3), RolledAffix("of_life", AffixStat.VITAL_MAX, "hp", 12)))
        val tip = ItemInfo.tip(blade, 1, null, items, names, null, rolls, loot)
        assertEquals("Cruel Rusty Blade of Life", tip.title)
        assertEquals("#60a5fa", tip.titleColor)
        assertEquals("Magic melee weapon", tip.kind)
        assertTrue(InfoLine("+3 damage", Tone.GOOD) in tip.lines, "${tip.lines}")
        assertTrue(InfoLine("+12 max HP", Tone.GOOD) in tip.lines, "${tip.lines}")
        assertTrue(InfoLine("Item level 4", Tone.MUTED) in tip.lines)
        assertEquals(InfoLine("Value 15", Tone.MUTED), tip.lines.last())
        val shop = ShopDefinition(id = 1, name = "Smith", sellRatePercent = 50)
        assertEquals(7, TradeMath.sellValue(shop, blade, rolls, loot), "the rarity multiplies what the shop pays, as on the server")
        assertEquals(null, ItemInfo.tip(blade, 1, null, items, names, null).titleColor, "plain items keep the usual colour")
    }

    @Test
    fun `unlabeled rarity omits its prefix and plus items show enhancement`() {
        val loot = LootRules(
            rarities = listOf(RarityDefinition("normal", "Normal", "#e5e7eb", weight = 1, unlabeled = true))
        )
        val rolls = ItemRolls("normal", 1)
        val tip = ItemInfo.tip(blade, 1, null, items, names, null, rolls, loot, plus = 2)
        assertEquals("Rusty Blade +2", tip.title)
        assertEquals("Melee weapon", tip.kind)
        assertTrue(InfoLine("+${2 * com.morgana.domain.item.ItemStack.PLUS_WEAPON_DAMAGE} damage (+2)", Tone.GOOD) in tip.lines)
    }

    @Test
    fun `two-handed weapons and hand slots use contextual names`() {
        assertEquals("Main Hand", ItemInfo.slotDisplayName(com.morgana.domain.item.EquipmentSlot.WEAPON))
        assertEquals("Off-Hand", ItemInfo.slotDisplayName(com.morgana.domain.item.EquipmentSlot.SHIELD))
        assertEquals("Helmet", ItemInfo.slotDisplayName(com.morgana.domain.item.EquipmentSlot.HELMET))

        val twoHandBow = bow.copy(twoHanded = true)
        assertEquals("Two-handed ranged weapon", ItemInfo.kind(twoHandBow))
    }

    @Test
    fun `slotBorderHex returns quality colour for rolled items and base colour for unrolled gear`() {
        val loot = LootRules(
            rarities = listOf(
                RarityDefinition("normal", "Normal", "#e5e7eb", weight = 620, unlabeled = true),
                RarityDefinition("refined", "Refined", "#34d399", weight = 240),
                RarityDefinition("magic", "Magic", "#60a5fa", weight = 95),
                RarityDefinition("elite", "Elite", "#f87171", weight = 35),
                RarityDefinition("super", "Super", "#fbbf24", weight = 10)
            )
        )
        assertEquals(null, ItemInfo.slotBorderHex(null, null, loot))
        assertEquals(null, ItemInfo.slotBorderHex(potion, ItemStack(potion.id, 1), loot))
        assertEquals("#e5e7eb", ItemInfo.slotBorderHex(blade, ItemStack(blade.id, 1), loot))
        assertEquals("#34d399", ItemInfo.slotBorderHex(blade, ItemStack(blade.id, 1, rolls = ItemRolls("refined", 1)), loot))
        assertEquals("#60a5fa", ItemInfo.slotBorderHex(blade, ItemStack(blade.id, 1, rolls = ItemRolls("magic", 3)), loot))
        assertEquals("#f87171", ItemInfo.slotBorderHex(blade, ItemStack(blade.id, 1, rolls = ItemRolls("elite", 6)), loot))
        assertEquals("#fbbf24", ItemInfo.slotBorderHex(blade, ItemStack(blade.id, 1, rolls = ItemRolls("super", 9)), loot))
    }

    @Test
    fun `bulkSellGroups groups unenhanced gear by rarity tier and includes materials while protecting plus gear and consumables`() {
        val loot = LootRules(
            rarities = listOf(
                RarityDefinition("normal", "Normal", "#e5e7eb", weight = 620, unlabeled = true, priceMultiplierPercent = 100),
                RarityDefinition("refined", "Refined", "#34d399", weight = 240, priceMultiplierPercent = 200),
                RarityDefinition("magic", "Magic", "#60a5fa", weight = 95, priceMultiplierPercent = 450),
                RarityDefinition("elite", "Elite", "#f87171", weight = 35, priceMultiplierPercent = 900),
                RarityDefinition("super", "Super", "#fbbf24", weight = 10, priceMultiplierPercent = 1800)
            )
        )
        val inv = bag(
            ItemStack(blade.id, 1), // slot 0: unrolled gear -> counts as Normal (sell 2)
            ItemStack(tunic.id, 1, rolls = ItemRolls("normal", 1)), // slot 1: Normal gear (sell 20)
            ItemStack(longsword.id, 1, rolls = ItemRolls("refined", 2)), // slot 2: Refined gear (60 * 200% * 50% = 60)
            ItemStack(longsword.id, 1, rolls = ItemRolls("magic", 3)), // slot 3: Magic gear (60 * 450% * 50% = 135)
            ItemStack(blade.id, 1, rolls = ItemRolls("normal", 1), plus = 1), // slot 4: +1 gear -> protected!
            ItemStack(potion.id, 5), // slot 5: consumable -> not gear or material!
            ItemStack(bone.id, 4), // slot 6: material (4 * 1 = 4)
            ItemStack(relic.id, 1), // slot 7: bound quest item -> unsellable!
            size = 8
        )
        val groups = TradeMath.bulkSellGroups(inv, shop, items, loot)
        assertEquals(listOf("normal", "refined", "magic", "materials"), groups.map { it.id })

        val normalGroup = groups[0]
        assertEquals("Sell all Normal (22)", normalGroup.label)
        assertEquals("#e5e7eb", normalGroup.color)
        assertEquals(listOf(0, 1), normalGroup.slots)
        assertEquals(2, normalGroup.count)
        assertEquals(22, normalGroup.worth)

        val refinedGroup = groups[1]
        assertEquals("Sell all Refined (60)", refinedGroup.label)
        assertEquals("#34d399", refinedGroup.color)
        assertEquals(listOf(2), refinedGroup.slots)

        val magicGroup = groups[2]
        assertEquals("Sell all Magic (135)", magicGroup.label)
        assertEquals("#60a5fa", magicGroup.color)
        assertEquals(listOf(3), magicGroup.slots)

        val materialsGroup = groups[3]
        assertEquals("Sell all materials (4)", materialsGroup.label)
        assertEquals(listOf(6), materialsGroup.slots)
    }

    @Test
    fun `gear compare counts plus levels and affixes and lists every stat that changes`() {
        val rolled = ItemRolls("rare", 10, listOf(RolledAffix("of_might", AffixStat.ATTRIBUTE, "str", 3)))
        val worn = ItemInfo.WornGear(ItemStack(3, plus = 1), blade) // 5 + 3 damage
        val lines = ItemInfo.compareLines(longsword, rolled, 0, worn, names).map { it.text to it.tone }
        assertEquals("Compared with your Rusty Blade:", lines[0].first)
        assertTrue(("+3 Damage" to Tone.GOOD) in lines, "11 against 5 + 3 plus: $lines")
        assertTrue(("+5 STR" to Tone.GOOD) in lines, "2 base + 3 rolled: $lines")
    }

    @Test
    fun `gear compare shows losses and says so when nothing is worn or nothing differs`() {
        val lines = ItemInfo.compareLines(blade, null, 0, ItemInfo.WornGear(ItemStack(4), longsword), names)
        assertTrue(lines.any { it.text == "-6 Damage" && it.tone == Tone.BAD }, lines.toString())
        assertTrue(lines.any { it.text == "-2 STR" && it.tone == Tone.BAD }, lines.toString())
        assertTrue(ItemInfo.compareLines(blade, null, 0, ItemInfo.WornGear(null, null), names)[0].text.startsWith("Nothing worn"))
        assertTrue(ItemInfo.compareLines(blade, null, 0, ItemInfo.WornGear(ItemStack(3), blade), names).any { it.text == "No difference in stats." })
    }

    @Test
    fun `worn gear is only offered for equippable items`() {
        val empty = com.morgana.domain.item.EquipmentLoadout()
        assertTrue(ItemInfo.wornFor(potion, empty, items) == null)
        assertTrue(ItemInfo.wornFor(arrow, empty, items) == null, "ammunition is not compared")
        assertTrue(ItemInfo.wornFor(blade, empty, items)!!.stack == null)
    }
}
