package com.morgana.client.ui.bag

import com.morgana.client.editor.forms.ChoiceText
import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.ShopEntry
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.item.AffixStat
import com.morgana.domain.item.Inventory
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemRoller
import com.morgana.domain.item.ItemRollText
import com.morgana.domain.item.ItemRolls
import com.morgana.domain.item.LootRules
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.GearRules
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.WeaponStyle

/*
 * The pure side of the inventory and shop windows: what to list, what to say about an item, and the buy and sell
 * arithmetic. No graphics, so every rule here is unit-tested; the Scene2D windows only draw it.
 */

/** The tabs above the bag: which kinds of item are listed. */
enum class BagFilter(val label: String, private val types: Set<ItemType>?) {
    ALL("All", null),
    GEAR("Gear", setOf(ItemType.WEAPON, ItemType.SHIELD, ItemType.HELMET, ItemType.ARMOR, ItemType.BOOTS, ItemType.RING, ItemType.AMULET, ItemType.TOOL)),
    USABLE("Use", setOf(ItemType.CONSUMABLE, ItemType.SPELL_SCROLL, ItemType.AMMO)),
    MATERIALS("Materials", setOf(ItemType.MATERIAL)),
    OTHER("Other", setOf(ItemType.KEY, ItemType.CURRENCY, ItemType.QUEST));

    fun matches(type: ItemType): Boolean = types == null || type in types
}

/** One bag slot as the grid shows it; [stack] is null for an empty slot. */
data class BagEntry(val slot: Int, val stack: ItemStack?, val def: ItemDefinition?)

object BagView {
    /**
     * What the grid lists. With the All tab and no search, every slot (empty ones too, so the bag's size is visible and an
     * item can be moved to an empty slot); otherwise only the matching items, in slot order.
     */
    fun entries(inventory: Inventory, items: Map<Int, ItemDefinition>, filter: BagFilter, query: String): List<BagEntry> {
        val all = inventory.slots.mapIndexed { i, s -> BagEntry(i, s, s?.let { items[it.itemId] }) }
        val q = query.trim().lowercase()
        if (filter == BagFilter.ALL && q.isEmpty()) return all
        return all.filter { e ->
            val stack = e.stack ?: return@filter false
            val def = e.def
            val typeOk = if (def == null) filter == BagFilter.ALL else filter.matches(def.type)
            typeOk && (q.isEmpty() || (def?.name ?: "Item #${stack.itemId}").lowercase().contains(q))
        }
    }

    fun usedSlots(inventory: Inventory): Int = inventory.slots.count { it != null }

    /** "17 / 40 slots". */
    fun capacityText(inventory: Inventory): String = "${usedSlots(inventory)} / ${inventory.maxSlots} slots"

    /** How much of [currencyItemId] the bag holds. */
    fun gold(inventory: Inventory, currencyItemId: Int): Int = inventory.totalCountOf(currencyItemId)
}

/** How a line of item text should look: green for better, red for worse or unmet, grey for background detail. */
enum class Tone { NEUTRAL, GOOD, BAD, MUTED }

data class InfoLine(val text: String, val tone: Tone = Tone.NEUTRAL)

/** What an item hover tooltip says; [titleColor] (`#rrggbb`) is the rarity colour of a rolled item, null for the usual colour. */
data class ItemTip(val title: String, val kind: String, val description: String, val lines: List<InfoLine>, val titleColor: String? = null)

/** Display names for the game's own stats and vitals (`STR`, `HP`...). Unknown ids fall back to the id in capitals. */
class StatNames(private val attributes: Map<String, String> = emptyMap(), private val vitals: Map<String, String> = emptyMap()) {
    fun attribute(id: String): String = attributes[id]?.takeIf { it.isNotBlank() } ?: id.uppercase()
    fun vital(id: String): String = vitals[id]?.takeIf { it.isNotBlank() } ?: id.uppercase()
}

/** What the player is, for the requirement lines: level, stats, class, and weapon proficiency. */
data class WearerInfo(
    val level: Int,
    val attributes: Map<String, Int>,
    val classId: Int,
    val equippedWeaponType: String = "",
    val weaponProficiencyLevels: Map<String, Int> = emptyMap()
)

object ItemInfo {
    /** Contextual display name for an equipment slot. */
    fun slotDisplayName(slot: com.morgana.domain.item.EquipmentSlot, slots: List<com.morgana.domain.game.EquipmentSlotDefinition> = com.morgana.domain.game.EquipmentSlotDefinition.CLASSIC): String =
        slots.firstOrNull { it.id == slot.id }?.name ?: slot.id.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** "Weapon", "Consumable"...: the item's kind in plain words. */
    fun kind(def: ItemDefinition): String = when (def.type) {
        ItemType.WEAPON -> {
            val base = when (def.weaponStyle) {
                WeaponStyle.MELEE -> "Melee weapon"
                WeaponStyle.RANGED_BOW -> "Ranged weapon"
                WeaponStyle.MAGIC_STAFF -> "Magic weapon"
            }
            if (def.twoHanded) "Two-handed ${base.lowercase()}" else base
        }
        ItemType.AMMO -> "Ammunition"
        ItemType.QUEST -> "Quest item"
        else -> ChoiceText.label(def.type.name)
    }

    private fun signed(n: Int) = if (n > 0) "+$n" else "$n"

    private fun tone(delta: Int) = if (delta > 0) Tone.GOOD else if (delta < 0) Tone.BAD else Tone.MUTED

    /**
     * The lines under an item's name: what it does, compared with what the player already wears in that slot
     * ([equipped]), what it needs, and what it is worth. [items] names ammunition.
     */
    fun describe(
        def: ItemDefinition,
        equipped: ItemDefinition?,
        items: Map<Int, ItemDefinition>,
        names: StatNames,
        wearer: WearerInfo?,
        rolls: ItemRolls? = null,
        plus: Int = 0,
        spells: Map<Int, SpellDefinition> = emptyMap(),
        rules: GearRules = GearRules.DEFAULT
    ): List<InfoLine> {
        val out = ArrayList<InfoLine>()
        val worn = equipped?.takeIf { it.id != def.id && it.type == def.type }
        val compare = worn != null
        if (def.attackDamage > 0 || def.type == ItemType.WEAPON) {
            val delta = if (worn != null) def.attackDamage - worn.attackDamage else 0
            out += InfoLine("Damage ${def.attackDamage}" + if (compare && delta != 0) " (${signed(delta)} vs worn)" else "", if (compare) tone(delta) else Tone.NEUTRAL)
            if (def.type == ItemType.WEAPON) {
                val tenths = def.attackSpeedTicks * 10 / 20 // 20 ticks per second, shown to a tenth without String.format (browser build)
                val range = if (def.weaponStyle != WeaponStyle.MELEE || def.rangeTiles > 1) ", range ${def.rangeTiles}" else ""
                val profType = if (def.weaponType.isNotBlank()) " · ${ChoiceText.label(def.weaponType)}" else ""
                out += InfoLine("One hit every ${tenths / 10}.${tenths % 10} s$range$profType", Tone.MUTED)
            }
        }
        if (def.armorDefense > 0 || def.type in setOf(ItemType.ARMOR, ItemType.SHIELD, ItemType.HELMET, ItemType.BOOTS)) {
            val delta = if (worn != null) def.armorDefense - worn.armorDefense else 0
            out += InfoLine("Defense ${def.armorDefense}" + if (compare && delta != 0) " (${signed(delta)} vs worn)" else "", if (compare) tone(delta) else Tone.NEUTRAL)
        }
        for ((id, n) in def.attributeBonuses.entries.sortedBy { it.key }) if (n != 0) {
            val was = if (worn != null) worn.attributeBonuses[id] ?: 0 else n
            out += InfoLine("${signed(n)} ${names.attribute(id)}", if (compare && n != was) tone(n - was) else if (n > 0) Tone.GOOD else Tone.BAD)
        }
        for ((id, n) in def.vitalMaxBonuses.entries.sortedBy { it.key }) if (n != 0) {
            out += InfoLine("${signed(n)} max ${names.vital(id)}", if (n > 0) Tone.GOOD else Tone.BAD)
        }
        // Rolled affixes (Diablo-style loot): the item's own extra bonuses, after the ones every copy of it has.
        val affixLines = ItemRollText.affixLines(rolls) { stat, id -> if (stat == AffixStat.VITAL_MAX) names.vital(id) else names.attribute(id) }
        rolls?.affixes.orEmpty().zip(affixLines).forEach { (r, text) -> out += InfoLine(text, if (r.value >= 0) Tone.GOOD else Tone.BAD) }
        if (plus > 0) {
            when {
                def.type == ItemType.WEAPON || def.attackDamage > 0 ->
                    out += InfoLine("+${rules.plusDamage(def.attackDamage, plus)} damage (+$plus)", Tone.GOOD)
                def.type in setOf(ItemType.ARMOR, ItemType.SHIELD, ItemType.HELMET, ItemType.BOOTS) ->
                    out += InfoLine("+${rules.plusDefense(def.armorDefense, plus)} defense (+$plus)", Tone.GOOD)
                def.type in setOf(ItemType.RING, ItemType.AMULET) ->
                    out += InfoLine("+${rules.plusHp(plus)} max ${names.vital("hp")} (+$plus)", Tone.GOOD)
            }
        }
        if (rolls != null) out += InfoLine("Item level ${rolls.itemLevel}", Tone.MUTED)
        for ((id, n) in def.restore.entries.sortedBy { it.key }) if (n != 0) {
            out += InfoLine((if (n > 0) "Restores $n " else "Drains ${-n} ") + names.vital(id), if (n > 0) Tone.GOOD else Tone.BAD)
        }
        if (def.requiredAmmoItemId > 0) out += InfoLine("Uses ${items[def.requiredAmmoItemId]?.name ?: "ammunition"}", Tone.MUTED)
        if (def.toolType != com.morgana.domain.item.ToolType.NONE) out += InfoLine("Tool: ${ChoiceText.label(def.toolType.name)}", Tone.MUTED)
        if (def.teachSpellId > 0) out += InfoLine("Teaches a spell when used", Tone.GOOD)
        if (def.warpMapId > 0) out += InfoLine("Teleports you when used", Tone.NEUTRAL)
        def.statusEffectToApply?.let { out += InfoLine("Applies ${ChoiceText.label(it.name)}" + if (def.statusEffectDurationTicks > 0) " for ${def.statusEffectDurationTicks / 20} s" else "", Tone.NEUTRAL) }

        val taughtSpell = if (def.type == ItemType.SPELL_SCROLL && def.teachSpellId > 0) spells[def.teachSpellId] else null
        val requiredLevel = maxOf(def.levelReq, taughtSpell?.levelReq ?: 1)
        val requiredClasses = if (def.classReqIds.isNotEmpty()) def.classReqIds else taughtSpell?.classReqIds.orEmpty()
        if (requiredLevel > 1) out += InfoLine("Needs level $requiredLevel", if (wearer != null && wearer.level < requiredLevel) Tone.BAD else Tone.MUTED)
        for ((id, n) in def.attributeReq.entries.sortedBy { it.key }) {
            out += InfoLine("Needs ${names.attribute(id)} $n", if (wearer != null && (wearer.attributes[id] ?: 0) < n) Tone.BAD else Tone.MUTED)
        }
        if (requiredClasses.isNotEmpty()) out += InfoLine("Only some classes can use this", if (wearer != null && wearer.classId !in requiredClasses) Tone.BAD else Tone.MUTED)
        if (def.boundOnPickup) out += InfoLine("Bound to you: cannot be sold or traded", Tone.MUTED)
        return out
    }

    /** What the player wears in the slot [def] would take: [stack] is null when the slot is empty. */
    data class WornGear(val stack: ItemStack?, val def: ItemDefinition?)

    /** The worn gear to compare [def] against, or null when [def] is not worn gear (so no comparison is shown). */
    fun wornFor(def: ItemDefinition, equipment: com.morgana.domain.item.EquipmentLoadout, items: Map<Int, ItemDefinition>): WornGear? {
        val slot = def.equipmentSlotOrNull() ?: return null
        if (slot == com.morgana.domain.item.EquipmentSlot.AMMO) return null
        val stack = equipment.getEquipped(slot)
        return WornGear(stack, stack?.let { items[it.itemId] })
    }

    /** The stats a piece of gear adds, in display order, with rolled affixes and plus levels counted (the base-only view hides them). */
    private fun gearStats(def: ItemDefinition, rolls: ItemRolls?, plus: Int, names: StatNames, rules: GearRules = GearRules.DEFAULT): LinkedHashMap<String, Int> {
        val out = LinkedHashMap<String, Int>()
        var damage = def.attackDamage
        var defense = def.armorDefense
        val attrs = sortedMapOf<String, Int>()
        val vitals = sortedMapOf<String, Int>()
        def.attributeBonuses.forEach { (k, v) -> attrs[k] = (attrs[k] ?: 0) + v }
        def.vitalMaxBonuses.forEach { (k, v) -> vitals[k] = (vitals[k] ?: 0) + v }
        when {
            def.type == ItemType.WEAPON || def.attackDamage > 0 -> damage += rules.plusDamage(def.attackDamage, plus)
            def.type in setOf(ItemType.ARMOR, ItemType.SHIELD, ItemType.HELMET, ItemType.BOOTS) -> defense += rules.plusDefense(def.armorDefense, plus)
            def.type in setOf(ItemType.RING, ItemType.AMULET) -> vitals["hp"] = (vitals["hp"] ?: 0) + rules.plusHp(plus).toInt()
        }
        for (a in rolls?.affixes.orEmpty()) when (a.stat) {
            AffixStat.ATTRIBUTE -> attrs[a.statId] = (attrs[a.statId] ?: 0) + a.value
            AffixStat.VITAL_MAX -> vitals[a.statId] = (vitals[a.statId] ?: 0) + a.value
            AffixStat.ATTACK_DAMAGE -> damage += a.value
            AffixStat.ARMOR_DEFENSE -> defense += a.value
            AffixStat.CRIT_CHANCE_BP,
            AffixStat.DODGE_CHANCE_BP,
            AffixStat.LIFE_LEECH_PERCENT,
            AffixStat.MAGIC_FIND_PERCENT -> {}
        }
        out["Damage"] = damage
        out["Defense"] = defense
        attrs.forEach { (k, v) -> out[names.attribute(k)] = v }
        vitals.forEach { (k, v) -> out["max " + names.vital(k)] = v }
        return out
    }

    /**
     * "What changes if I wear this": the net change in every stat against what is worn now, rolled affixes and plus levels
     * included. Empty when [def] is not gear.
     */
    fun compareLines(
        def: ItemDefinition,
        rolls: ItemRolls?,
        plus: Int,
        worn: WornGear,
        names: StatNames,
        rules: GearRules = GearRules.DEFAULT
    ): List<InfoLine> {
        val mine = gearStats(def, rolls, plus, names, rules)
        val wornStack = worn.stack
        val wornDef = worn.def
        if (wornStack == null || wornDef == null) {
            return listOf(InfoLine("Nothing worn in this slot: wearing this adds all of the above.", Tone.GOOD))
        }
        val theirs = gearStats(wornDef, wornStack.rolls, wornStack.plus, names, rules)
        val out = ArrayList<InfoLine>()
        for (key in (mine.keys + theirs.keys).distinct()) {
            val delta = (mine[key] ?: 0) - (theirs[key] ?: 0)
            if (delta != 0) out += InfoLine("${signed(delta)} $key", tone(delta))
        }
        val head = InfoLine("Compared with your ${wornDef.name}:", Tone.MUTED)
        return if (out.isEmpty()) listOf(head, InfoLine("No difference in stats.", Tone.MUTED)) else listOf(head) + out
    }

    /** The hover tooltip for [def]: name, kind (with the stack size), description and the same lines the detail card shows. */
    fun tip(
        def: ItemDefinition,
        amount: Int,
        equipped: ItemDefinition?,
        items: Map<Int, ItemDefinition>,
        names: StatNames,
        wearer: WearerInfo?,
        rolls: ItemRolls? = null,
        loot: LootRules? = null,
        plus: Int = 0,
        spells: Map<Int, SpellDefinition> = emptyMap(),
        durability: Int = 100,
        sockets: List<Int> = emptyList(),
        worn: WornGear? = null,
        rules: GearRules = GearRules.DEFAULT
    ): ItemTip =
        ItemTip(
            title = ItemRollText.displayName(def, rolls, loot, plus),
            kind = rolledKind(def, rolls, loot) + if (amount > 1) "  x$amount" else "",
            description = def.description,
            lines = describe(def, equipped, items, names, wearer, rolls, plus, spells, rules) +
                (worn?.let { compareLines(def, rolls, plus, it, names, rules) } ?: emptyList()) +
                durabilityLines(durability, rules.brokenPercent) +
                socketLines(sockets, items, names) +
                gemLines(def, names) +
                InfoLine("Value ${ItemRollText.price(def.price, rolls, loot, plus)}" + if (amount > 1) " each" else "", Tone.MUTED),
            titleColor = ItemRollText.color(rolls, loot)
        )

    /** One line per socket: the gem set into it with what it adds, or "Empty socket". */
    fun socketLines(sockets: List<Int>, items: Map<Int, ItemDefinition>, names: StatNames): List<InfoLine> =
        sockets.map { id ->
            val gem = if (id > 0) items[id] else null
            if (gem == null) InfoLine("Socket: empty", Tone.MUTED)
            else InfoLine("Socket: ${gem.name} (${gemSummary(gem, names)})", Tone.GOOD)
        }

    /** The effect lines of a gem item itself, so its tooltip says what slotting it does. */
    fun gemLines(def: ItemDefinition, names: StatNames): List<InfoLine> =
        def.gem?.let { listOf(InfoLine("Gem: ${gemSummary(def, names)}", Tone.GOOD)) }.orEmpty()

    private fun gemSummary(def: ItemDefinition, names: StatNames): String {
        val gem = def.gem ?: return ""
        val parts = ArrayList<String>()
        gem.attackDamage.takeIf { it != 0 }?.let { parts += "+$it damage" }
        gem.armorDefense.takeIf { it != 0 }?.let { parts += "+$it armour" }
        gem.xpPercent.takeIf { it != 0 }?.let { parts += "+$it% kill XP" }
        gem.attributeBonuses.forEach { (attr, v) -> parts += "${if (v >= 0) "+" else ""}$v ${names.attribute(attr)}" }
        return parts.joinToString(", ")
    }

    /**
     * A tooltip line for worn gear: a broken piece gives no bonuses (or only [brokenPercent] of them, in a game that sets
     * `durability.brokenStatPercent`), so say so. Nothing for gear in perfect shape.
     */
    fun durabilityLines(durability: Int, brokenPercent: Int = 0): List<InfoLine> = when {
        durability <= 0 && brokenPercent > 0 -> listOf(InfoLine("Broken: only $brokenPercent% of its bonuses until repaired", Tone.BAD))
        durability <= 0 -> listOf(InfoLine("Broken: gives no bonuses until repaired", Tone.BAD))
        durability < 100 -> listOf(InfoLine("Durability $durability%", if (durability <= 10) Tone.BAD else Tone.MUTED))
        else -> emptyList()
    }

    /** [kind] with the rarity's name in front for a rolled item: "Rare Melee weapon". */
    fun rolledKind(def: ItemDefinition, rolls: ItemRolls?, loot: LootRules?): String {
        val rarity = rolls?.let { r -> loot?.rarity(r.rarity) } ?: return kind(def)
        if (rarity.unlabeled) return kind(def)
        return "${rarity.name} ${kind(def).replaceFirstChar { it.lowercase() }}"
    }

    /** True when [def] cannot be used by [wearer] yet (level, stat or class). */
    fun unmet(def: ItemDefinition, wearer: WearerInfo, spells: Map<Int, SpellDefinition> = emptyMap()): Boolean {
        val taughtSpell = if (def.type == ItemType.SPELL_SCROLL && def.teachSpellId > 0) spells[def.teachSpellId] else null
        val requiredLevel = maxOf(def.levelReq, taughtSpell?.levelReq ?: 1)
        val requiredClasses = if (def.classReqIds.isNotEmpty()) def.classReqIds else taughtSpell?.classReqIds.orEmpty()
        return wearer.level < requiredLevel ||
            def.attributeReq.any { (id, n) -> (wearer.attributes[id] ?: 0) < n } ||
            (requiredClasses.isNotEmpty() && wearer.classId !in requiredClasses)
    }

    /** `#rrggbb` outline colour for a bag slot: the item's rolled rarity colour, or the base rarity colour for unrolled gear. */
    fun slotBorderHex(def: ItemDefinition?, stack: ItemStack?, loot: LootRules?): String? {
        if (stack == null) return null
        return ItemRollText.color(stack.rolls, loot)
            ?: if (def != null && def.equipmentSlotOrNull() != null) loot?.rarities?.firstOrNull()?.color else null
    }
}

/**
 * One bulk-sell shortcut in the shop's Sell tab (for example "Sell all Normal (85)" or "Sell all materials (15)").
 * [slots] are the bag slot indices to sell, [count] is the total item count across those slots, and [worth]
 * is the total currency the shop pays for all of them.
 */
data class BulkSellGroup(
    val id: String,
    val label: String,
    val color: String? = null,
    val slots: List<Int>,
    val count: Int,
    val worth: Int
)

/** The shop arithmetic as the server does it (`EconomySystem`), so prices on screen are the prices charged. */
object TradeMath {
    const val MAX_QUANTITY = 999

    fun currencyOf(shop: ShopDefinition, entry: ShopEntry): Int = if (entry.currencyItemId > 0) entry.currencyItemId else shop.currencyItemId

    /** Price of one purchase of [entry] (which hands over [ShopEntry.amount] items). */
    fun buyPrice(shop: ShopDefinition, entry: ShopEntry, item: ItemDefinition): Int =
        if (entry.priceOverride > 0) entry.priceOverride else maxOf(1, item.price * shop.buyRatePercent / 100)

    /** What the shop pays for one [item]. */
    fun sellValue(shop: ShopDefinition, item: ItemDefinition, rolls: ItemRolls? = null, loot: LootRules? = null, plus: Int = 0): Int =
        maxOf(1, ItemRollText.price(item.price, rolls, loot, plus) * shop.sellRatePercent / 100)

    /** False for items bound to the player and for the shop's own currency. */
    fun canSell(shop: ShopDefinition, item: ItemDefinition): Boolean = !item.boundOnPickup && item.id != shop.currencyItemId

    /** The most purchases of [entry] the player can make with [gold] and room for [roomUnits] more of the item. */
    fun maxBuy(gold: Int, unitPrice: Int, entry: ShopEntry, roomUnits: Int): Int {
        if (unitPrice <= 0) return 0
        return minOf(gold / unitPrice, roomUnits / maxOf(1, entry.amount), MAX_QUANTITY).coerceAtLeast(0)
    }

    /** The quantity buttons: 1, 5, 10 and "Max", without repeats and never above [max]. */
    fun quantityChoices(max: Int): List<Int> = listOf(1, 5, 10).filter { it < max } + listOfNotNull(max.takeIf { it >= 1 })

    /** Quantity steps for a stack of [held] being sold: 1, 5, 10 and the whole stack. */
    fun sellChoices(held: Int): List<Int> = quantityChoices(held.coerceAtMost(MAX_QUANTITY))

    /**
     * Effective rarity id of [stack] for bulk gear selling, or null when the item should not be bulk-sold as gear
     * (not rollable gear, or enhanced with `+1` or higher). Unrolled gear in a game with [loot] rarities counts as
     * the base (first) rarity tier.
     */
    fun gearRarityId(def: ItemDefinition, stack: ItemStack, loot: LootRules?): String? {
        if (stack.plus > 0 || !ItemRoller.canRoll(def)) return null
        return stack.rolls?.rarity ?: loot?.rarities?.firstOrNull()?.id
    }

    /**
     * Quick-sell groups for the shop's Sell tab: one per rarity tier in [loot] that has sellable unenhanced gear in
     * [inventory] (e.g. Normal, Refined, Magic, Elite, Super), followed by crafting materials if any are held.
     */
    fun bulkSellGroups(
        inventory: Inventory,
        shop: ShopDefinition,
        items: Map<Int, ItemDefinition>,
        loot: LootRules? = null
    ): List<BulkSellGroup> {
        val out = ArrayList<BulkSellGroup>()
        val sellable = inventory.slots.mapIndexedNotNull { slot, stack ->
            val def = stack?.let { items[it.itemId] }
            if (stack != null && def != null && canSell(shop, def)) Triple(slot, stack, def) else null
        }
        if (sellable.isEmpty()) return out

        for (rarity in loot?.rarities.orEmpty()) {
            val matching = sellable.filter { (_, stack, def) -> gearRarityId(def, stack, loot) == rarity.id }
            if (matching.isNotEmpty()) {
                val worth = matching.sumOf { (_, s, d) -> sellValue(shop, d, s.rolls, loot, s.plus) * s.amount }
                val count = matching.sumOf { (_, s, _) -> s.amount }
                out += BulkSellGroup(
                    id = rarity.id,
                    label = "Sell all ${rarity.name} ($worth)",
                    color = rarity.color,
                    slots = matching.map { it.first },
                    count = count,
                    worth = worth
                )
            }
        }

        val materials = sellable.filter { (_, _, def) -> def.type == ItemType.MATERIAL }
        if (materials.isNotEmpty()) {
            val worth = materials.sumOf { (_, s, d) -> sellValue(shop, d, s.rolls, loot, s.plus) * s.amount }
            val count = materials.sumOf { (_, s, _) -> s.amount }
            out += BulkSellGroup(
                id = "materials",
                label = "Sell all materials ($worth)",
                color = null,
                slots = materials.map { it.first },
                count = count,
                worth = worth
            )
        }
        return out
    }
}
