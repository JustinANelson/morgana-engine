package com.morgana.domain.item

import com.morgana.domain.content.ContentSchema
import com.morgana.domain.spatial.Direction
import com.morgana.domain.stats.StatusEffectType
import kotlinx.serialization.Serializable
import kotlin.math.min

/**
 * Categories of items supported by Morgana ORPG.
 */
@Serializable
enum class ItemType {
    WEAPON,
    SHIELD,
    HELMET,
    ARMOR,
    BOOTS,
    RING,
    AMULET,
    TOOL,
    AMMO,
    CONSUMABLE,
    SPELL_SCROLL,
    KEY,
    CURRENCY,
    MATERIAL,
    QUEST
}

/**
 * An equipment slot, identified by an upper-case id such as `WEAPON` or `GARMENT`. The nine classic slots are constants
 * here; a game adds or drops slots in the `equipmentSlots` section of `game.json` ([com.morgana.domain.game.EquipmentSlotDefinition])
 * and an item picks its slot with [ItemDefinition.equipSlot] (or, when that is blank, from its [ItemType]). Serialized as the bare
 * id string, exactly as the earlier enum was.
 */
@Serializable
@JvmInline
value class EquipmentSlot(val id: String) {
    /** The slot id (kept as `name` for the enum-era call sites). */
    val name: String get() = id

    /** Default Paperdolling v2 visual slot when an item does not override [ItemDefinition.paperdollSlot]; custom slots draw as accessories. */
    fun defaultPaperdollSlot(): PaperdollSlot = when (id) {
        "WEAPON", "TOOL" -> PaperdollSlot.MAIN_HAND
        "SHIELD" -> PaperdollSlot.OFF_HAND
        "HELMET" -> PaperdollSlot.HEAD
        "ARMOR" -> PaperdollSlot.TORSO
        "BOOTS" -> PaperdollSlot.FEET
        "AMMO" -> PaperdollSlot.BACK
        else -> PaperdollSlot.ACCESSORY
    }

    override fun toString(): String = id

    companion object {
        val WEAPON = EquipmentSlot("WEAPON")
        val SHIELD = EquipmentSlot("SHIELD")
        val HELMET = EquipmentSlot("HELMET")
        val ARMOR = EquipmentSlot("ARMOR")
        val BOOTS = EquipmentSlot("BOOTS")
        val RING = EquipmentSlot("RING")
        val AMULET = EquipmentSlot("AMULET")
        val TOOL = EquipmentSlot("TOOL")
        val AMMO = EquipmentSlot("AMMO")

        /** The slots a game has when `game.json` has no `equipmentSlots` section, in display order. */
        val CLASSIC: List<EquipmentSlot> = listOf(WEAPON, SHIELD, HELMET, ARMOR, BOOTS, RING, AMULET, TOOL, AMMO)

        /** Valid slot ids: upper-case letters, digits and underscores, starting with a letter. */
        val ID_PATTERN = Regex("[A-Z][A-Z0-9_]{0,23}")
    }
}

/**
 * Visual slots for Paperdolling v2, ordered independently of gameplay [EquipmentSlot]s and direction-sorted at render time.
 */
@Serializable
enum class PaperdollSlot {
    BODY,
    HAIR,
    FEET,
    LEGS,
    TORSO,
    BACK,
    HEAD,
    MAIN_HAND,
    OFF_HAND,
    ACCESSORY
}

/**
 * One resolved visual layer drawn onto a character in Paperdolling v2.
 */
@Serializable
data class PaperdollLayer(
    val slot: PaperdollSlot,
    val spriteId: Int,
    val tintHex: String? = null,
    val hidesSlots: Set<PaperdollSlot> = emptySet(),
    /** True when the item is of the game's highest rarity: the client outlines it with a 1 pixel yellow glow. */
    val glow: Boolean = false
)

/**
 * Pure slot-ordering and hide-rule resolver for Paperdolling v2.
 */
object PaperdollComposer {

    // Facing the viewer (DOWN): capes, quivers and packs (BACK) hang behind the body; held gear is drawn over it.
    private val DRAW_ORDER_DOWN: Map<PaperdollSlot, Int> = mapOf(
        PaperdollSlot.BACK to 0,
        PaperdollSlot.BODY to 1,
        PaperdollSlot.FEET to 2,
        PaperdollSlot.LEGS to 3,
        PaperdollSlot.TORSO to 4,
        PaperdollSlot.HAIR to 5,
        PaperdollSlot.ACCESSORY to 6,
        PaperdollSlot.HEAD to 7,
        PaperdollSlot.OFF_HAND to 8,
        PaperdollSlot.MAIN_HAND to 9
    )

    // Facing LEFT the main hand is the far hand, so it sits behind the body; RIGHT mirrors it with the off-hand.
    private val DRAW_ORDER_LEFT: Map<PaperdollSlot, Int> = mapOf(
        PaperdollSlot.BACK to 0,
        PaperdollSlot.MAIN_HAND to 1,
        PaperdollSlot.BODY to 2,
        PaperdollSlot.FEET to 3,
        PaperdollSlot.LEGS to 4,
        PaperdollSlot.TORSO to 5,
        PaperdollSlot.HAIR to 6,
        PaperdollSlot.ACCESSORY to 7,
        PaperdollSlot.HEAD to 8,
        PaperdollSlot.OFF_HAND to 9
    )

    private val DRAW_ORDER_RIGHT: Map<PaperdollSlot, Int> = mapOf(
        PaperdollSlot.BACK to 0,
        PaperdollSlot.OFF_HAND to 1,
        PaperdollSlot.BODY to 2,
        PaperdollSlot.FEET to 3,
        PaperdollSlot.LEGS to 4,
        PaperdollSlot.TORSO to 5,
        PaperdollSlot.HAIR to 6,
        PaperdollSlot.ACCESSORY to 7,
        PaperdollSlot.HEAD to 8,
        PaperdollSlot.MAIN_HAND to 9
    )

    // When facing UP, held gear draws behind the body/torso while capes/quivers (BACK) draw over the back.
    private val DRAW_ORDER_UP: Map<PaperdollSlot, Int> = mapOf(
        PaperdollSlot.MAIN_HAND to 0,
        PaperdollSlot.OFF_HAND to 1,
        PaperdollSlot.BODY to 2,
        PaperdollSlot.FEET to 3,
        PaperdollSlot.LEGS to 4,
        PaperdollSlot.TORSO to 5,
        PaperdollSlot.HAIR to 6,
        PaperdollSlot.ACCESSORY to 7,
        PaperdollSlot.HEAD to 8,
        PaperdollSlot.BACK to 9
    )

    /** Returns draw order rank (`0` drawn first / furthest back) for [slot] when facing [direction]. */
    fun drawOrderRank(slot: PaperdollSlot, direction: Direction): Int =
        when (direction.primaryCardinal()) {
            Direction.UP -> DRAW_ORDER_UP
            Direction.LEFT -> DRAW_ORDER_LEFT
            Direction.RIGHT -> DRAW_ORDER_RIGHT
            else -> DRAW_ORDER_DOWN
        }.getValue(slot)

    /**
     * Filters out slots hidden by other layers (`hidesSlots`) and returns visible layers sorted back-to-front
     * for [direction]. Layers cannot hide their own slot.
     */
    fun composeLayers(rawLayers: List<PaperdollLayer>, direction: Direction): List<PaperdollLayer> {
        if (rawLayers.isEmpty()) return emptyList()
        val hiddenSlots = buildSet {
            for (layer in rawLayers) {
                for (hidden in layer.hidesSlots) {
                    if (hidden != layer.slot) add(hidden)
                }
            }
        }
        return rawLayers
            .filter { it.spriteId > 0 && it.slot !in hiddenSlots }
            .sortedBy { drawOrderRank(it.slot, direction) }
    }

    /** Combines base character layers (body/hair) with equipped items in canonical composition order. */
    fun resolveCharacterLayers(
        baseLayers: List<PaperdollLayer>,
        equipment: EquipmentLoadout,
        itemCatalog: Map<Int, ItemDefinition>,
        direction: Direction = Direction.DOWN,
        classId: Int = 0,
        topRarityId: String? = null
    ): List<PaperdollLayer> {
        val eqLayers = equipment.resolvedPaperdollLayers(itemCatalog, classId, topRarityId)
        return composeLayers(baseLayers + eqLayers, direction)
    }
}

/**
 * Gathering tools for Morgana resource nodes.
 */
@Serializable
enum class ToolType {
    NONE,
    HATCHET,
    PICKAXE,
    FISHING_ROD
}

/**
 * Combat style for weapons.
 */
@Serializable
enum class WeaponStyle {
    MELEE,
    RANGED_BOW,
    MAGIC_STAFF
}

/**
 * What a gem adds to the piece of gear it is socketed into (see `ItemStack.sockets`). Everything is summed over the
 * worn, unbroken gear, so a gem in a boot works like a gem in a sword.
 */
@Serializable
data class GemEffect(
    /** `+value` to each named attribute. */
    val attributeBonuses: Map<String, Int> = emptyMap(),
    /** `+value` weapon damage. */
    val attackDamage: Int = 0,
    /** `+value` armour. */
    val armorDefense: Int = 0,
    /** Percent extra experience from kills, summed over all socketed gems. */
    val xpPercent: Int = 0
) {
    val isEmpty: Boolean get() = attributeBonuses.isEmpty() && attackDamage == 0 && armorDefense == 0 && xpPercent == 0
}

/**
 * Authoritative item definition editable live via the F1 Item Editor.
 */
@Serializable
data class ItemDefinition(
    val id: Int,
    val name: String,
    val description: String = "",
    val type: ItemType = ItemType.MATERIAL,
    val iconId: Int = 1,
    val paperdollSpriteId: Int = 0,
    /** Paperdolling v2: the picture used instead of [paperdollSpriteId] when this item is worn in the off-hand slot (a second blade drawn in the off-hand pose); 0 reuses [paperdollSpriteId]. */
    val paperdollOffHandSpriteId: Int = 0,
    val stackable: Boolean = false,
    val maxStack: Int = 1,
    val price: Int = 10,
    val boundOnPickup: Boolean = false,
    val levelReq: Int = 1,
    val classReqIds: Set<Int> = emptySet(),
    /** Promotion tier (see `PromotionDefinition`) a character needs before they can use or wear this item; 0 = none. */
    val promotionReq: Int = 0,
    val attributeReq: Map<String, Int> = emptyMap(),
    val attributeBonuses: Map<String, Int> = emptyMap(),
    val vitalMaxBonuses: Map<String, Int> = emptyMap(),
    val restore: Map<String, Int> = emptyMap(),
    val attackDamage: Int = 0,
    val armorDefense: Int = 0,
    val attackSpeedTicks: Int = 12,
    val rangeTiles: Int = 1,
    val weaponStyle: WeaponStyle = WeaponStyle.MELEE,
    /** Optional weapon proficiency type id (e.g. `"blade"`, `"sword"`, `"club"`, `"bow"`, `"katana"`). */
    val weaponType: String = "",
    val twoHanded: Boolean = false,
    val projectileSpriteId: Int = 0,
    val requiredAmmoItemId: Int = 0,
    val toolType: ToolType = ToolType.NONE,
    val teachSpellId: Int = 0,
    val warpMapId: Int = 0,
    val warpX: Int = 0,
    val warpY: Int = 0,
    /** A Town Portal: warps to the nearest town of the game's `townGate` list instead of a fixed spot. */
    val townGate: Boolean = false,
    /** A consumable that adopts the pet NPC with this id when used (see `PetTraits`); 0 for none. */
    val adoptPetNpcId: Int = 0,
    /** A mount: while this is worn, free-movement steps are this percent of a normal step (0 = not a mount, 101 to 200 = faster). */
    val mountSpeedPercent: Int = 0,
    val statusEffectToApply: StatusEffectType? = null,
    val statusEffectMagnitude: Int = 0,
    val statusEffectDurationTicks: Int = 0,
    val animationId: Int = 0,
    /** Optional sound id played to the killer when this item drops from an enemy. */
    val dropSoundId: String = "",
    /** When true, announces this item's drop in global chat with the finder's name and location. */
    val broadcastOnDrop: Boolean = false,
    /** When true, renders this item larger on the ground with a pulsing highlight and overhead name label. */
    val highlightOnGround: Boolean = false,
    /** Optional `#rrggbb` accent colour used for ground highlight glow and overhead label when [highlightOnGround] is true. */
    val dropHighlightColorHex: String? = null,
    /**
     * The equipment slot id this item is worn in (one of the game's `equipmentSlots`); blank derives it from [type]
     * (a weapon goes in `WEAPON`, a ring in `RING`, ...). Lets any gear type fill a game-defined slot such as `GARMENT`.
     */
    val equipSlot: String = "",
    /** Opt-in Paperdolling v2 slot override; null derives from [equipmentSlotOrNull]. */
    val paperdollSlot: PaperdollSlot? = null,
    /** Opt-in Paperdolling v2 `#rrggbb` tint color applied when drawing this layer. */
    val paperdollTintHex: String? = null,
    /**
     * Paperdolling v2 `#rrggbb` tint by the wearer's class id; a class listed here wears the item in that colour instead of
     * [paperdollTintHex]. One item then looks different on each class (a Duelist's red and a Ranger's green armour) without
     * a copy of the item per class.
     */
    val paperdollClassTints: Map<Int, String> = emptyMap(),
    /** Opt-in Paperdolling v2 slots hidden while this item is worn (for example a helmet hiding `HAIR`). */
    val paperdollHidesSlots: Set<PaperdollSlot> = emptySet(),
    /**
     * Whether this item rolls a rarity and affixes when it drops, in a game with `loot` rules (see [ItemRoller.canRoll]).
     * Set false for fixed gear such as a quest reward or a named unique.
     */
    val rollAffixes: Boolean = true,
    /** When set, this item is a gem that can be socketed into gear (see `upgrade_gear`). */
    val gem: GemEffect? = null,
    /** The next tier of this piece of gear (same slot) that a level upgrade turns it into; 0 = none. */
    val nextTierItemId: Int = 0,
    /** On-disk document schema version; see [ContentSchema]. */
    val schemaVersion: Int = ContentSchema.CURRENT_VERSION
) {
    fun equipmentSlotOrNull(): EquipmentSlot? = if (equipSlot.isNotBlank()) EquipmentSlot(equipSlot) else when (type) {
        ItemType.WEAPON -> EquipmentSlot.WEAPON
        ItemType.SHIELD -> EquipmentSlot.SHIELD
        ItemType.HELMET -> EquipmentSlot.HELMET
        ItemType.ARMOR -> EquipmentSlot.ARMOR
        ItemType.BOOTS -> EquipmentSlot.BOOTS
        ItemType.RING -> EquipmentSlot.RING
        ItemType.AMULET -> EquipmentSlot.AMULET
        ItemType.TOOL -> EquipmentSlot.TOOL
        ItemType.AMMO -> EquipmentSlot.AMMO
        else -> null
    }

    /** A one-handed melee weapon: the only kind that may be worn in the off-hand slot when dual-wielding. */
    val canDualWield: Boolean
        get() = type == ItemType.WEAPON && !twoHanded && weaponStyle == WeaponStyle.MELEE && equipSlot.isBlank()

    val isEffectivelyStackable: Boolean
        get() = stackable || type in setOf(
            ItemType.CURRENCY,
            ItemType.AMMO,
            ItemType.CONSUMABLE,
            ItemType.MATERIAL
        )

    val effectiveMaxStack: Int
        get() = if (isEffectivelyStackable) maxOf(maxStack, 999) else 1
}

/**
 * Single slot in an inventory, bank, or equipment loadout.
 */
@Serializable
data class ItemStack(
    val itemId: Int,
    val amount: Int = 1,
    val durability: Int = 100,
    /** Rarity and affixes rolled when this item dropped; null for plain items (see [ItemRoller]). */
    val rolls: ItemRolls? = null,
    /** Plus enhancement level (+1..+12) gained by merging gear. */
    val plus: Int = 0,
    /** One entry per socket: 0 for an empty socket, otherwise the id of the gem item socketed into it. */
    val sockets: List<Int> = emptyList()
) {
    init {
        require(itemId > 0) { "ItemStack itemId must be positive (got $itemId)" }
        require(amount > 0) { "ItemStack amount must be positive (got $amount)" }
        require(plus in 0..MAX_PLUS) { "ItemStack plus must be between 0 and $MAX_PLUS (got $plus)" }
        require(sockets.size <= MAX_SOCKETS && sockets.none { it < 0 }) { "ItemStack sockets must be at most $MAX_SOCKETS ids that are 0 or more" }
    }

    companion object {
        const val MAX_PLUS = 12
        const val MAX_SOCKETS = 6
        const val PLUS_WEAPON_DAMAGE = 3
        const val PLUS_ARMOR_DEFENSE = 2
        const val PLUS_RING_HP = 15
    }
}

/**
 * Result of adding an item to an [Inventory] or [BankStorage].
 */
@Serializable
data class InventoryMutationResult(
    val updatedInventory: Inventory,
    val addedAmount: Int,
    val overflowAmount: Int
)

/**
 * Pure deterministic fixed-slot inventory container (default 24 slots, Morgana style).
 */
@Serializable
data class Inventory(
    val maxSlots: Int = DEFAULT_MAX_SLOTS,
    val slots: List<ItemStack?> = List(maxSlots) { null }
) {
    init {
        require(maxSlots in 1..200) { "maxSlots must be between 1 and 200" }
        require(slots.size == maxSlots) { "slots size (${slots.size}) must equal maxSlots ($maxSlots)" }
    }

    fun getSlot(index: Int): ItemStack? = slots.getOrNull(index)

    fun totalCountOf(itemId: Int): Int =
        slots.filterNotNull().filter { it.itemId == itemId }.sumOf { it.amount }

    fun hasItem(itemId: Int, minAmount: Int = 1): Boolean =
        totalCountOf(itemId) >= minAmount

    fun addItem(
        itemDef: ItemDefinition,
        amount: Int
    ): InventoryMutationResult {
        require(amount > 0) { "Amount to add must be > 0" }
        val mutableSlots = slots.toMutableList()
        var remaining = amount
        val stackLimit = itemDef.effectiveMaxStack

        if (itemDef.isEffectivelyStackable) {
            for (i in mutableSlots.indices) {
                if (remaining <= 0) break
                val existing = mutableSlots[i]
                if (existing != null && existing.itemId == itemDef.id && existing.amount < stackLimit) {
                    val space = stackLimit - existing.amount
                    val toTransfer = min(remaining, space)
                    mutableSlots[i] = existing.copy(amount = existing.amount + toTransfer)
                    remaining -= toTransfer
                }
            }
        }

        for (i in mutableSlots.indices) {
            if (remaining <= 0) break
            if (mutableSlots[i] == null) {
                val toPlace = if (itemDef.isEffectivelyStackable) min(remaining, stackLimit) else 1
                mutableSlots[i] = ItemStack(itemId = itemDef.id, amount = toPlace)
                remaining -= toPlace
            }
        }

        return InventoryMutationResult(
            updatedInventory = copy(slots = mutableSlots),
            addedAmount = amount - remaining,
            overflowAmount = remaining
        )
    }

    /**
     * Adds [stack] itself: a stackable item merges like [addItem]; anything else is placed unit by unit into empty slots
     * as a copy of [stack], so what it carries (durability, [ItemStack.rolls]) is kept. Use this whenever an existing item
     * moves (pickup, bank, trade, mail, market, unequip) and [addItem] when a new one is created.
     */
    fun addStack(itemDef: ItemDefinition, stack: ItemStack): InventoryMutationResult {
        if (itemDef.isEffectivelyStackable) return addItem(itemDef, stack.amount)
        val mutableSlots = slots.toMutableList()
        var remaining = stack.amount
        for (i in mutableSlots.indices) {
            if (remaining <= 0) break
            if (mutableSlots[i] == null) {
                mutableSlots[i] = stack.copy(amount = 1)
                remaining--
            }
        }
        return InventoryMutationResult(copy(slots = mutableSlots), stack.amount - remaining, remaining)
    }

    fun removeFromSlot(slotIndex: Int, amount: Int = 1): Pair<Inventory, ItemStack>? {
        if (slotIndex !in slots.indices || amount <= 0) return null
        val current = slots[slotIndex] ?: return null
        if (current.amount < amount) return null

        val mutableSlots = slots.toMutableList()
        val remaining = current.amount - amount
        mutableSlots[slotIndex] = if (remaining > 0) current.copy(amount = remaining) else null
        return copy(slots = mutableSlots) to current.copy(amount = amount)
    }

    fun removeByItemId(itemId: Int, amount: Int = 1): Inventory? {
        if (amount <= 0) return this
        if (!hasItem(itemId, amount)) return null
        val mutableSlots = slots.toMutableList()
        var remainingToRemove = amount
        for (i in mutableSlots.indices) {
            if (remainingToRemove <= 0) break
            val stack = mutableSlots[i] ?: continue
            if (stack.itemId == itemId) {
                val take = min(remainingToRemove, stack.amount)
                val left = stack.amount - take
                mutableSlots[i] = if (left > 0) stack.copy(amount = left) else null
                remainingToRemove -= take
            }
        }
        return copy(slots = mutableSlots)
    }

    fun swapSlots(fromIndex: Int, toIndex: Int): Inventory {
        if (fromIndex !in slots.indices || toIndex !in slots.indices || fromIndex == toIndex) return this
        val mutableSlots = slots.toMutableList()
        val temp = mutableSlots[fromIndex]
        mutableSlots[fromIndex] = mutableSlots[toIndex]
        mutableSlots[toIndex] = temp
        return copy(slots = mutableSlots)
    }

    /** How many units of [itemDef] [addItem] could still place: free room in its partial stacks plus the empty slots. */
    fun roomFor(itemDef: ItemDefinition): Int {
        val limit = itemDef.effectiveMaxStack
        val empty = slots.count { it == null }
        if (!itemDef.isEffectivelyStackable) return empty
        val topUp = slots.sumOf { s -> if (s != null && s.itemId == itemDef.id) (limit - s.amount).coerceAtLeast(0) else 0 }
        return topUp + empty.toLong().times(limit).coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtMost(Int.MAX_VALUE - topUp)
    }

    /** This bag with room for [size] slots: never smaller than it is now, new slots empty. Items keep their places. */
    fun grownTo(size: Int): Inventory {
        val target = size.coerceIn(maxSlots, MAX_SLOTS_LIMIT)
        return if (target == maxSlots) this else copy(maxSlots = target, slots = slots + List(target - maxSlots) { null })
    }

    /**
     * The bag tidied: stacks of the same item merged (up to each item's stack limit), then everything packed to the front
     * in a stable order: by [ItemType] (weapons first, then armour, tools, consumables, materials, ... see the enum), then
     * by name, then by item id; empty slots at the end. Items [items] does not know sort last by id. Nothing is lost or
     * created: the total of every item is unchanged.
     */
    fun sorted(items: Map<Int, ItemDefinition>): Inventory {
        val totals = LinkedHashMap<Int, Int>()
        for (s in slots) if (s != null) totals[s.itemId] = (totals[s.itemId] ?: 0) + s.amount
        // Items that do not stack keep their individual stacks (and their durability); stackable ones are merged.
        val kept = ArrayList<ItemStack>()
        val merged = LinkedHashMap<Int, Int>()
        for (s in slots) {
            if (s == null) continue
            val def = items[s.itemId]
            if (def != null && def.isEffectivelyStackable) merged[s.itemId] = (merged[s.itemId] ?: 0) + s.amount else kept += s
        }
        for ((id, total) in merged) {
            val limit = items.getValue(id).effectiveMaxStack
            var left = total
            while (left > 0) { val n = minOf(left, limit); kept += ItemStack(itemId = id, amount = n); left -= n }
        }
        val ordered = kept.sortedWith(
            compareBy<ItemStack>({ items[it.itemId]?.type?.ordinal ?: Int.MAX_VALUE }, { items[it.itemId]?.name?.lowercase() ?: "" }, { it.itemId }, { -it.amount })
        )
        return copy(slots = ordered + List(maxSlots - ordered.size) { null })
    }

    companion object {
        const val DEFAULT_MAX_SLOTS: Int = 24
        const val DEFAULT_BANK_SLOTS: Int = 48
        /** The most slots a bag can have (the same limit the constructor enforces). */
        const val MAX_SLOTS_LIMIT: Int = 200
    }
}

/**
 * Character equipment loadout mapping each [EquipmentSlot] to an equipped [ItemStack].
 */
@Serializable
data class EquipmentLoadout(
    val equipped: Map<EquipmentSlot, ItemStack> = emptyMap()
) {
    fun getEquipped(slot: EquipmentSlot): ItemStack? = equipped[slot]

    /** The step length, in percent of a normal step, the worn gear allows: the fastest worn mount, or 100. */
    fun mountSpeedPercent(items: Map<Int, ItemDefinition>): Int =
        active.maxOfOrNull { items[it.itemId]?.mountSpeedPercent ?: 0 }?.takeIf { it > 100 } ?: 100

    /** Worn gear that still counts in full: a broken piece (durability 0) stays equipped but is left out here. */
    val active: Collection<ItemStack> get() = equipped.values.filter { it.durability > 0 }

    /**
     * The percent of its bonuses a worn piece counts for: 100 while it has durability, and [brokenPercent] (0 to 100) once it is
     * broken. The `brokenPercent` parameters below take the game's `durability.brokenStatPercent`; 0, the default, keeps the
     * original rule that a broken piece adds nothing until repaired.
     */
    fun shareOf(stack: ItemStack, brokenPercent: Int = 0): Int = if (stack.durability > 0) 100 else brokenPercent.coerceIn(0, 100)

    /** [value] scaled to what [stack] counts for, rounded half up (so a +5 at 10% still counts for 1). */
    fun scaled(value: Int, stack: ItemStack, brokenPercent: Int): Int {
        val share = shareOf(stack, brokenPercent)
        return if (share >= 100) value else Math.floorDiv(value * share + 50, 100)
    }

    private fun scaledLong(value: Long, stack: ItemStack, brokenPercent: Int): Long {
        val share = shareOf(stack, brokenPercent)
        return if (share >= 100) value else Math.floorDiv(value * share + 50L, 100L)
    }

    /** Worn pieces that count for something: unbroken ones, plus broken ones when [brokenPercent] is above 0. */
    private fun counted(brokenPercent: Int): List<ItemStack> = equipped.values.filter { shareOf(it, brokenPercent) > 0 }

    /** Like [getEquipped] but null when the piece is broken and [brokenPercent] is 0. */
    fun getActive(slot: EquipmentSlot, brokenPercent: Int = 0): ItemStack? = equipped[slot]?.takeIf { shareOf(it, brokenPercent) > 0 }

    /** The base damage of the worn main-hand weapon [stack] (`null` = fists), scaled when it is broken. */
    fun weaponBaseDamage(stack: ItemStack?, itemCatalog: Map<Int, ItemDefinition>, brokenPercent: Int = 0): Int {
        val def = stack?.let { itemCatalog[it.itemId] } ?: return UNARMED_DAMAGE
        return scaled(def.attackDamage, stack, brokenPercent)
    }

    fun totalAttributeBonuses(itemCatalog: Map<Int, ItemDefinition>, brokenPercent: Int = 0): Map<String, Int> {
        val out = mutableMapOf<String, Int>()
        for (stack in counted(brokenPercent)) {
            val def = itemCatalog[stack.itemId] ?: continue
            val piece = mutableMapOf<String, Int>()
            for ((attr, bonus) in def.attributeBonuses) piece[attr] = (piece[attr] ?: 0) + bonus
            for (r in rolledOf(stack, AffixStat.ATTRIBUTE)) piece[r.statId] = (piece[r.statId] ?: 0) + r.value
            for (gem in gemsOf(stack, itemCatalog)) {
                for ((attr, bonus) in gem.attributeBonuses) piece[attr] = (piece[attr] ?: 0) + bonus
            }
            for ((attr, bonus) in piece) out[attr] = (out[attr] ?: 0) + scaled(bonus, stack, brokenPercent)
        }
        return out
    }

    /** The effects of every gem socketed into [stack] (empty sockets and unknown or non-gem items add nothing). */
    private fun gemsOf(stack: ItemStack, itemCatalog: Map<Int, ItemDefinition>): List<GemEffect> =
        stack.sockets.mapNotNull { id -> if (id > 0) itemCatalog[id]?.gem else null }

    /** Percent extra kill experience from every gem socketed into unbroken worn gear. */
    fun totalGemXpPercent(itemCatalog: Map<Int, ItemDefinition>, brokenPercent: Int = 0): Int =
        counted(brokenPercent).sumOf { s -> scaled(gemsOf(s, itemCatalog).sumOf { it.xpPercent }, s, brokenPercent) }

    /** How many gems are socketed into unbroken worn gear. */
    fun socketedGemCount(): Int = active.sumOf { s -> s.sockets.count { it > 0 } }

    fun totalArmorDefense(itemCatalog: Map<Int, ItemDefinition>, brokenPercent: Int = 0): Int =
        counted(brokenPercent).sumOf { stack ->
            val def = itemCatalog[stack.itemId] ?: return@sumOf 0
            val plusBonus = if (def.armorDefense > 0 || def.type in setOf(ItemType.ARMOR, ItemType.SHIELD, ItemType.HELMET, ItemType.BOOTS)) {
                stack.plus * ItemStack.PLUS_ARMOR_DEFENSE
            } else 0
            scaled(
                def.armorDefense + plusBonus + rolledOf(stack, AffixStat.ARMOR_DEFENSE).sumOf { it.value } +
                    gemsOf(stack, itemCatalog).sumOf { it.armorDefense },
                stack, brokenPercent
            )
        }

    fun totalVitalMaxBonuses(vitalId: String, itemCatalog: Map<Int, ItemDefinition>, brokenPercent: Int = 0): Long =
        counted(brokenPercent).sumOf { stack ->
            val def = itemCatalog[stack.itemId] ?: return@sumOf 0L
            val plusBonus = if (vitalId == "hp" && def.type in setOf(ItemType.RING, ItemType.AMULET)) {
                stack.plus.toLong() * ItemStack.PLUS_RING_HP
            } else 0L
            scaledLong(
                (def.vitalMaxBonuses[vitalId] ?: 0).toLong() +
                    plusBonus +
                    rolledOf(stack, AffixStat.VITAL_MAX).filter { it.statId == vitalId }.sumOf { it.value.toLong() },
                stack, brokenPercent
            )
        }

    /** Extra weapon damage rolled on worn gear ([AffixStat.ATTACK_DAMAGE] affixes in any slot) plus weapon +N bonuses. */
    fun rolledAttackDamage(itemCatalog: Map<Int, ItemDefinition>, brokenPercent: Int = 0): Int =
        equipped.entries.filter { (slot, stack) -> shareOf(stack, brokenPercent) > 0 && !isOffHandWeapon(slot, stack, itemCatalog) }
            .sumOf { (_, stack) -> scaled(extraAttackDamageOf(stack, itemCatalog), stack, brokenPercent) }

    /** True for a weapon worn in the off-hand slot (dual-wielding): its damage belongs to the off-hand strike, not the main swing. */
    private fun isOffHandWeapon(slot: EquipmentSlot, stack: ItemStack, itemCatalog: Map<Int, ItemDefinition>): Boolean =
        slot == EquipmentSlot.SHIELD && itemCatalog[stack.itemId]?.type == ItemType.WEAPON

    /** The unbroken weapon worn in the off-hand slot, or null when the slot is empty, broken or holds a shield. */
    fun getActiveOffHandWeapon(itemCatalog: Map<Int, ItemDefinition>, brokenPercent: Int = 0): ItemStack? =
        getActive(EquipmentSlot.SHIELD, brokenPercent)?.takeIf { isOffHandWeapon(EquipmentSlot.SHIELD, it, itemCatalog) }

    /** A weapon stack's own damage: the item's `attackDamage` plus its + level, rolled damage affixes and socketed gems. */
    fun weaponDamageOf(stack: ItemStack, itemCatalog: Map<Int, ItemDefinition>, brokenPercent: Int = 0): Int =
        scaled((itemCatalog[stack.itemId]?.attackDamage ?: 0) + extraAttackDamageOf(stack, itemCatalog), stack, brokenPercent)

    private fun extraAttackDamageOf(stack: ItemStack, itemCatalog: Map<Int, ItemDefinition>): Int {
        val def = itemCatalog[stack.itemId] ?: return 0
        val plusBonus = if (def.type == ItemType.WEAPON || def.attackDamage > 0) {
            stack.plus * ItemStack.PLUS_WEAPON_DAMAGE
        } else 0
        return plusBonus + rolledOf(stack, AffixStat.ATTACK_DAMAGE).sumOf { it.value } + gemsOf(stack, itemCatalog).sumOf { it.attackDamage }
    }

    /** Sum of all worn, unbroken rolled affixes matching [stat]. */
    fun rolledStatTotal(stat: AffixStat, itemCatalog: Map<Int, ItemDefinition>, brokenPercent: Int = 0): Int =
        counted(brokenPercent).sumOf { stack -> scaled(rolledOf(stack, stat).sumOf { it.value }, stack, brokenPercent) }

    fun totalCritBonusBp(itemCatalog: Map<Int, ItemDefinition>, brokenPercent: Int = 0): Int = rolledStatTotal(AffixStat.CRIT_CHANCE_BP, itemCatalog, brokenPercent)
    fun totalDodgeBonusBp(itemCatalog: Map<Int, ItemDefinition>, brokenPercent: Int = 0): Int = rolledStatTotal(AffixStat.DODGE_CHANCE_BP, itemCatalog, brokenPercent)
    fun totalLifeLeechPercent(itemCatalog: Map<Int, ItemDefinition>, brokenPercent: Int = 0): Int = rolledStatTotal(AffixStat.LIFE_LEECH_PERCENT, itemCatalog, brokenPercent).coerceAtLeast(0)
    fun totalMagicFindPercent(itemCatalog: Map<Int, ItemDefinition>, brokenPercent: Int = 0): Int = rolledStatTotal(AffixStat.MAGIC_FIND_PERCENT, itemCatalog, brokenPercent).coerceAtLeast(0)

    companion object {
        /** Damage of a bare-handed swing (no weapon worn): the engine's long-standing default. */
        const val UNARMED_DAMAGE: Int = 2
    }

    private fun rolledOf(stack: ItemStack, stat: AffixStat): List<RolledAffix> =
        stack.rolls?.affixes.orEmpty().filter { it.stat == stat }

    /** Worn slots that are not one of the classic nine (game-defined slots), by id so the order is stable. */
    private fun customSlotsInOrder(): List<EquipmentSlot> =
        equipped.keys.filter { it !in EquipmentSlot.CLASSIC }.sortedBy { it.id }

    fun paperdollLayers(itemCatalog: Map<Int, ItemDefinition>): List<Int> {
        val renderOrder = listOf(
            EquipmentSlot.BOOTS,
            EquipmentSlot.ARMOR,
            EquipmentSlot.HELMET,
            EquipmentSlot.AMULET,
            EquipmentSlot.WEAPON,
            EquipmentSlot.TOOL,
            EquipmentSlot.SHIELD
        ) + customSlotsInOrder()
        return renderOrder.mapNotNull { slot ->
            val stack = equipped[slot] ?: return@mapNotNull null
            val spriteId = itemCatalog[stack.itemId]?.paperdollSpriteId ?: 0
            spriteId.takeIf { it > 0 }
        }
    }

    /**
     * Resolves slot, tint, and hide metadata for every equipped visual item in Paperdolling v2. An item whose rolled rarity is
     * [topRarityId] (the game's highest tier, see [LootRules.topRarityId]) gets [PaperdollLayer.glow]; null means no glow.
     */
    fun resolvedPaperdollLayers(itemCatalog: Map<Int, ItemDefinition>, classId: Int = 0, topRarityId: String? = null): List<PaperdollLayer> {
        val slotScanOrder = listOf(
            EquipmentSlot.BOOTS,
            EquipmentSlot.ARMOR,
            EquipmentSlot.HELMET,
            EquipmentSlot.RING,
            EquipmentSlot.AMULET,
            EquipmentSlot.WEAPON,
            EquipmentSlot.TOOL,
            EquipmentSlot.SHIELD,
            EquipmentSlot.AMMO
        ) + customSlotsInOrder()
        val raw = slotScanOrder.mapNotNull { eqSlot ->
            val stack = equipped[eqSlot] ?: return@mapNotNull null
            val def = itemCatalog[stack.itemId] ?: return@mapNotNull null
            val spriteId = if (eqSlot == EquipmentSlot.SHIELD && def.paperdollOffHandSpriteId > 0) def.paperdollOffHandSpriteId else def.paperdollSpriteId
            if (spriteId <= 0) return@mapNotNull null
            PaperdollLayer(
                slot = def.paperdollSlot ?: eqSlot.defaultPaperdollSlot(),
                spriteId = spriteId,
                tintHex = (def.paperdollClassTints[classId] ?: def.paperdollTintHex)?.trim()?.takeIf { it.isNotEmpty() },
                hidesSlots = def.paperdollHidesSlots,
                glow = topRarityId != null && stack.rolls?.rarity == topRarityId
            )
        }
        return PaperdollComposer.composeLayers(raw, Direction.DOWN)
    }
}

