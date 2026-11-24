package com.morgana.domain.item

import com.morgana.domain.sim.RandomSource
import kotlinx.serialization.Serializable

/**
 * Diablo-style random item properties. A game opts in with a `loot` section in `game.json` ([LootRules]); every piece of
 * gear that then drops (from a monster or as loot on a generated floor) rolls a rarity and that rarity's number of affixes,
 * and carries the result on its [ItemStack] as [ItemRolls]. Shops, quest rewards, crafting and admin commands hand out
 * plain items. Everything here is pure and draws only from the [RandomSource] it is given, so rolls replay exactly.
 */
@Serializable
data class LootRules(
    /** Rarity tiers in the order they are listed (usually common first). Empty = items never roll. */
    val rarities: List<RarityDefinition> = emptyList(),
    /** Every affix an item can roll. */
    val affixes: List<AffixDefinition> = emptyList()
) {
    fun rarity(id: String): RarityDefinition? = rarities.firstOrNull { it.id == id }

    /**
     * The best rarity: the last one listed (tiers are listed lowest first). Null when the game has fewer than two tiers, since
     * with a single tier nothing is "the best" and every drop would count as it.
     */
    val topRarityId: String? get() = rarities.takeIf { it.size >= 2 }?.last()?.id
    fun affix(id: String): AffixDefinition? = affixes.firstOrNull { it.id == id }

    companion object {
        const val MAX_RARITIES = 12
        const val MAX_AFFIXES = 400
        const val MAX_AFFIXES_PER_ITEM = 8
        const val MAX_ITEM_LEVEL = 1000
        const val MAX_WEIGHT = 1_000_000
        const val MAX_AFFIX_VALUE = 100_000
        const val MAX_PRICE_MULTIPLIER_PERCENT = 100_000
    }
}

/** One rarity tier, such as Magic: how often it drops and how many affixes it rolls. */
@Serializable
data class RarityDefinition(
    val id: String,
    val name: String,
    /** `#rrggbb`: the item's name is drawn in this colour in tooltips and on the ground. */
    val color: String = "#ffffff",
    /**
     * Relative chance among the rarities an item may roll. **0 means this rarity never drops** but still exists, for example as
     * a quality only a crafter's upgrade reaches (the order of the list is the quality ladder).
     */
    val weight: Int,
    /** This rarity only rolls on items of at least this level (for example no rares on the first floor). */
    val minItemLevel: Int = 1,
    val affixesMin: Int = 0,
    val affixesMax: Int = 0,
    /** Sell value of an item of this rarity, in percent of the item's own price (100 = unchanged). */
    val priceMultiplierPercent: Int = 100,
    /** When true, the rarity label is omitted in item kind descriptions (e.g. "Melee weapon" instead of "Normal melee weapon"). */
    val unlabeled: Boolean = false,
    /**
     * Chance in basis points (10000 = always) that a piece of gear of this rarity drops with one empty socket, when the game's
     * `gearUpgrade.maxSockets` allows any. Only drops roll this; 0 means this rarity never drops with a socket.
     */
    val socketChanceBasisPoints: Int = 0
)

/** Whether an affix's name goes before the item's name ("Cruel Sword") or after it ("Sword of the Bear"). */
@Serializable
enum class AffixPosition { PREFIX, SUFFIX }

/** What an affix adds to while the item is worn. */
@Serializable
enum class AffixStat {
    /** `+value` to the attribute named by `statId` (for example `str`). */
    ATTRIBUTE,
    /** `+value` to the maximum of the vital named by `statId` (for example `hp`). */
    VITAL_MAX,
    /** `+value` weapon damage (from any slot). */
    ATTACK_DAMAGE,
    /** `+value` armour. */
    ARMOR_DEFENSE,
    /** `+value` basis points (100 = 1%) to critical strike chance. */
    CRIT_CHANCE_BP,
    /** `+value` basis points (100 = 1%) to dodge chance. */
    DODGE_CHANCE_BP,
    /** `+value%` of landed melee/ranged/action damage restored as life. */
    LIFE_LEECH_PERCENT,
    /** `+value%` bonus drop / magic-find rate on monster kills. */
    MAGIC_FIND_PERCENT
}

/** One affix an item can roll, with the range its value is drawn from. */
@Serializable
data class AffixDefinition(
    val id: String,
    /** Shown in the item's name: "Cruel" for a prefix, "of the Bear" for a suffix. */
    val name: String,
    val position: AffixPosition = AffixPosition.SUFFIX,
    val stat: AffixStat,
    /** The attribute or vital id for [AffixStat.ATTRIBUTE] and [AffixStat.VITAL_MAX]; empty otherwise. */
    val statId: String = "",
    val min: Int,
    val max: Int,
    /** Relative chance among the affixes an item may roll (1 or more). */
    val weight: Int = 100,
    /** Item levels this affix can roll on (inclusive); use it to make stronger tiers of the same bonus. */
    val minItemLevel: Int = 1,
    val maxItemLevel: Int = LootRules.MAX_ITEM_LEVEL,
    /** Equipment slots this affix may appear on; empty = any gear except ammunition. */
    val slots: Set<EquipmentSlot> = emptySet()
)

/** What an item rolled when it dropped. Stored on its [ItemStack], so it survives the bag, bank, trades, mail and the market. */
@Serializable
data class ItemRolls(
    val rarity: String,
    val itemLevel: Int = 1,
    val affixes: List<RolledAffix> = emptyList()
)

/**
 * One rolled affix. The stat it adds to is copied from its [AffixDefinition] so the item keeps working (and the server can
 * total its bonuses) without the loot table; the name is looked up from the game's [LootRules] when the item is shown.
 */
@Serializable
data class RolledAffix(
    val id: String,
    val stat: AffixStat,
    val statId: String = "",
    val value: Int
)

/** Rolls rarities and affixes. Pure: the same rules, item, level and random sequence always give the same result. */
object ItemRoller {

    /** Whether [def] is gear that can roll: equippable (but not ammunition), not stackable, and not opted out. */
    fun canRoll(def: ItemDefinition): Boolean {
        val slot = def.equipmentSlotOrNull() ?: return false
        return def.rollAffixes && slot != EquipmentSlot.AMMO && !def.isEffectivelyStackable
    }

    /**
     * Rolls [def] at [itemLevel] under [rules], or returns null when the game has no rarities, the item cannot roll, or no
     * rarity is allowed at that level. Draws from [rng] only when it returns a roll.
     */
    fun roll(def: ItemDefinition, itemLevel: Int, rules: LootRules?, rng: RandomSource): ItemRolls? {
        if (rules == null || !canRoll(def)) return null
        val level = itemLevel.coerceIn(1, LootRules.MAX_ITEM_LEVEL)
        val rarities = rules.rarities.filter { it.weight > 0 && level >= it.minItemLevel }
        if (rarities.isEmpty()) return null
        val rarity = pickWeighted(rarities, rng) { it.weight }
        return rollAtRarity(def, level, rules, rarity, rng)
    }

    /**
     * The sockets a freshly dropped piece of gear carries: one empty socket with the chance of its rarity
     * ([RarityDefinition.socketChanceBasisPoints]), none when the game allows no sockets. Draws no random number when the
     * chance is 0, so games without socket loot keep their replays.
     */
    fun rollDropSockets(rolls: ItemRolls?, rules: LootRules?, maxSockets: Int, rng: RandomSource): List<Int> {
        if (rolls == null || rules == null || maxSockets < 1) return emptyList()
        val chance = rules.rarities.firstOrNull { it.id == rolls.rarity }?.socketChanceBasisPoints ?: 0
        return if (chance > 0 && rng.rollBasisPoints(chance)) listOf(0) else emptyList()
    }

    /**
     * Rolls [def] at [itemLevel] as exactly [rarity] (used when gear is upgraded to a better quality): the affix count is
     * drawn from the rarity's range and the affixes from the pool allowed at that level. Null when the item cannot roll.
     */
    fun rollAtRarity(def: ItemDefinition, itemLevel: Int, rules: LootRules?, rarity: RarityDefinition, rng: RandomSource): ItemRolls? {
        if (rules == null || !canRoll(def)) return null
        val level = itemLevel.coerceIn(1, LootRules.MAX_ITEM_LEVEL)
        val slot = def.equipmentSlotOrNull()!!
        val pool = rules.affixes.filter { a ->
            a.weight > 0 && level in a.minItemLevel..a.maxItemLevel && (a.slots.isEmpty() || slot in a.slots)
        }
        val wanted = rng.nextIntInclusive(rarity.affixesMin, rarity.affixesMax).coerceAtMost(LootRules.MAX_AFFIXES_PER_ITEM)
        val rolled = ArrayList<RolledAffix>(wanted)
        repeat(wanted) {
            // No affix twice, and no two affixes adding to the same stat (one "+str" per item; tiers are alternatives).
            val open = pool.filter { a -> rolled.none { r -> r.id == a.id || (r.stat == a.stat && r.statId == a.statId) } }
            if (open.isEmpty()) return@repeat
            val affix = pickWeighted(open, rng) { it.weight }
            rolled += RolledAffix(affix.id, affix.stat, affix.statId, rng.nextIntInclusive(affix.min, affix.max))
        }
        return ItemRolls(rarity = rarity.id, itemLevel = level, affixes = rolled)
    }

    private inline fun <T> pickWeighted(options: List<T>, rng: RandomSource, weight: (T) -> Int): T {
        val total = options.sumOf { weight(it).toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        var r = rng.nextInt(total)
        for (o in options) {
            r -= weight(o)
            if (r < 0) return o
        }
        return options.last()
    }
}

/** How a rolled item is named and priced. Shared by the server (shop prices, chat) and the client (tooltips, ground labels). */
object ItemRollText {

    /** "Cruel Sword of the Bear +2": the first prefix, the item's name, the first suffix, and optional +N. Plain name when not rolled. */
    fun displayName(def: ItemDefinition, rolls: ItemRolls?, rules: LootRules?, plus: Int = 0): String {
        val base = if (rolls == null || rules == null) {
            def.name
        } else {
            val named = rolls.affixes.mapNotNull { r -> rules.affix(r.id) }
            val prefix = named.firstOrNull { it.position == AffixPosition.PREFIX }?.name
            val suffix = named.firstOrNull { it.position == AffixPosition.SUFFIX }?.name
            listOfNotNull(prefix, def.name, suffix).filter { it.isNotBlank() }.joinToString(" ")
        }
        return if (plus > 0) "$base +$plus" else base
    }

    /** The rarity's colour, or null when the item has no rarity (draw it in the normal text colour). */
    fun color(rolls: ItemRolls?, rules: LootRules?): String? =
        rolls?.let { r -> rules?.rarity(r.rarity)?.color }

    /**
     * One line per affix, in rolled order, such as `+3 STR` or `+12 max HP`; [statName] turns an attribute or vital id
     * (for [AffixStat.ATTRIBUTE] and [AffixStat.VITAL_MAX]) into the name to show.
     */
    fun affixLines(rolls: ItemRolls?, statName: (AffixStat, String) -> String = { _, id -> id }): List<String> =
        rolls?.affixes.orEmpty().map { r ->
            val sign = if (r.value >= 0) "+" else ""
            when (r.stat) {
                AffixStat.ATTRIBUTE -> "$sign${r.value} ${statName(r.stat, r.statId)}"
                AffixStat.VITAL_MAX -> "$sign${r.value} max ${statName(r.stat, r.statId)}"
                AffixStat.ATTACK_DAMAGE -> "$sign${r.value} damage"
                AffixStat.ARMOR_DEFENSE -> "$sign${r.value} armour"
                AffixStat.CRIT_CHANCE_BP -> "$sign${r.value / 100.0}% crit"
                AffixStat.DODGE_CHANCE_BP -> "$sign${r.value / 100.0}% dodge"
                AffixStat.LIFE_LEECH_PERCENT -> "$sign${r.value}% life leech"
                AffixStat.MAGIC_FIND_PERCENT -> "$sign${r.value}% magic find"
            }
        }

    /** [basePrice] adjusted by the rarity's price multiplier and plus level (never below 1 when [basePrice] is positive). */
    fun price(basePrice: Int, rolls: ItemRolls?, rules: LootRules?, plus: Int = 0): Int {
        if (basePrice <= 0) return basePrice
        val rarityPct = rolls?.let { r -> rules?.rarity(r.rarity)?.priceMultiplierPercent } ?: 100
        if (rolls == null && plus <= 0) return basePrice
        val plusPct = 100L + plus.coerceAtLeast(0) * 25L
        val scaled = basePrice.toLong() * rarityPct / 100L * plusPct / 100L
        return scaled.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
    }
}
