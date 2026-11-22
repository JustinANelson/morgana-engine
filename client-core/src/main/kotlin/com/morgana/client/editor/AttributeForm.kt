package com.morgana.client.editor

import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.VitalType
import com.morgana.domain.spatial.Direction

/** What a field holds, which decides the widget the attribute panel shows for it. */
enum class FieldType { INT, FLOAT, BOOL, TEXT, DIRECTION, OPTIONAL_DIRECTION, VITAL }

/** Which kind of game content an integer field names, so the panel can show the name next to the number. */
enum class FieldRef { NONE, ITEM, SHOP, RESOURCE, MAP, SPELL, NPC, EVENT, COMMON_EVENT, ANIMATION, CLASS, QUEST }

/** Looks up the name of the content an id points at, for the "= Iron Key" hints beside id fields. */
object ContentNames {
    /** The name of the [ref] content with [id]; null when there is no such content (or [ref] is [FieldRef.NONE] or [FieldRef.MAP]). */
    fun nameOf(catalog: com.morgana.protocol.WorldContentCatalogDto, ref: FieldRef, id: Int): String? = when (ref) {
        FieldRef.ITEM -> catalog.items.firstOrNull { it.id == id }?.name
        FieldRef.SHOP -> catalog.shops.firstOrNull { it.id == id }?.name
        FieldRef.RESOURCE -> catalog.resources.firstOrNull { it.id == id }?.name
        FieldRef.SPELL -> catalog.spells.firstOrNull { it.id == id }?.name
        FieldRef.NPC -> catalog.npcs.firstOrNull { it.id == id }?.name
        FieldRef.EVENT -> catalog.events.firstOrNull { it.id == id }?.name
        FieldRef.COMMON_EVENT -> catalog.commonEvents.firstOrNull { it.id == id }?.name
        FieldRef.ANIMATION -> catalog.animations.firstOrNull { it.id == id }?.name
        FieldRef.CLASS -> catalog.classes.firstOrNull { it.id == id }?.name
        FieldRef.MAP -> catalog.maps.firstOrNull { it.id == id }?.name
        FieldRef.QUEST -> catalog.quests.firstOrNull { it.id == id }?.name
        FieldRef.NONE -> null
    }

    /** "= Name" for a known id, "(no such item)" for an unknown one, "" when 0 (none) or [ref] is [FieldRef.NONE]. */
    fun hint(catalog: com.morgana.protocol.WorldContentCatalogDto, ref: FieldRef, id: Int): String {
        if (ref == FieldRef.NONE) return ""
        val name = nameOf(catalog, ref, id)
        return if (name != null) "= $name" else "(no such ${ref.name.lowercase()})"
    }
}

/** One typed input of an attribute. Values are kept as text so the panel can hold a half-typed number. */
data class AttributeField(
    val key: String,
    val label: String,
    val type: FieldType,
    val default: String,
    val ref: FieldRef = FieldRef.NONE,
    /** Smallest allowed number for INT and FLOAT fields. */
    val min: Double = Double.NEGATIVE_INFINITY
)

/** Every tile attribute the engine knows, with the typed fields that describe it. */
enum class AttributeKind(val label: String, val hint: String, val fields: List<AttributeField>) {
    NONE("None", "No special behaviour.", emptyList()),
    BLOCKED("Blocked", "Nothing can walk onto this tile.", emptyList()),
    DIRECTIONAL_BLOCK(
        "One-way block", "Blocks movement across the chosen edges.",
        listOf(
            AttributeField("up", "Block up", FieldType.BOOL, "true"),
            AttributeField("down", "Block down", FieldType.BOOL, "false"),
            AttributeField("left", "Block left", FieldType.BOOL, "false"),
            AttributeField("right", "Block right", FieldType.BOOL, "false")
        )
    ),
    WARP(
        "Warp", "Moves the player to another map.",
        listOf(
            AttributeField("map", "Target map", FieldType.INT, "1", FieldRef.MAP, min = 1.0),
            AttributeField("x", "Target X", FieldType.INT, "0", min = 0.0),
            AttributeField("y", "Target Y", FieldType.INT, "0", min = 0.0),
            AttributeField("dir", "Arrive facing", FieldType.OPTIONAL_DIRECTION, "")
        )
    ),
    ITEM_SPAWN(
        "Item spawn", "Drops an item on this tile again and again.",
        listOf(
            AttributeField("item", "Item", FieldType.INT, "1", FieldRef.ITEM, min = 1.0),
            AttributeField("amount", "Amount", FieldType.INT, "1", min = 1.0),
            AttributeField("respawn", "Respawn (ticks)", FieldType.INT, "200", min = 1.0)
        )
    ),
    NPC_AVOID("NPC avoid", "Wandering NPCs will not step here.", emptyList()),
    KEY_DOOR(
        "Key door", "Opens for players who carry the key.",
        listOf(
            AttributeField("key", "Key item", FieldType.INT, "1", FieldRef.ITEM, min = 1.0),
            AttributeField("consume", "Use up the key", FieldType.BOOL, "false"),
            AttributeField("open", "Stays open (ticks)", FieldType.INT, "100", min = 1.0)
        )
    ),
    RESOURCE_NODE(
        "Resource node", "A tree, rock or other gatherable.",
        listOf(AttributeField("resource", "Resource", FieldType.INT, "1", FieldRef.RESOURCE, min = 1.0))
    ),
    SHOP("Shop", "Opens a shop when stepped on.", listOf(AttributeField("shop", "Shop", FieldType.INT, "1", FieldRef.SHOP, min = 1.0))),
    BANK("Bank", "Opens the player's bank.", emptyList()),
    HEAL_TRAP(
        "Heal / trap", "Restores or drains a vital while standing here.",
        listOf(
            AttributeField("vital", "Vital", FieldType.VITAL, "HP"),
            AttributeField("amount", "Amount", FieldType.INT, "10", min = 1.0),
            AttributeField("trap", "Damages (trap)", FieldType.BOOL, "false"),
            AttributeField("interval", "Every (ticks)", FieldType.INT, "20", min = 1.0),
            AttributeField("animation", "Animation id", FieldType.INT, "0", min = 0.0)
        )
    ),
    SLIDE("Slide", "Pushes the player along until they leave the ice.", listOf(AttributeField("dir", "Direction", FieldType.DIRECTION, "DOWN"))),
    LIGHT_SOURCE(
        "Light source", "A glowing point in the dark.",
        listOf(
            AttributeField("radius", "Radius (pixels)", FieldType.FLOAT, "128", min = 16.0),
            AttributeField("color", "Colour (hex)", FieldType.TEXT, "#FFAA44"),
            AttributeField("intensity", "Strength (0-1)", FieldType.FLOAT, "0.85", min = 0.0),
            AttributeField("flicker", "Flickers", FieldType.BOOL, "true")
        )
    ),
    SOUND_EMITTER(
        "Sound emitter", "Plays a sound for players nearby.",
        listOf(
            AttributeField("sound", "Sound id", FieldType.TEXT, "ambient"),
            AttributeField("radius", "Radius (tiles)", FieldType.INT, "8", min = 1.0),
            AttributeField("interval", "Every (ticks)", FieldType.INT, "60", min = 1.0)
        )
    )
}

/**
 * Turns attributes into typed form values and back, with no graphics, so the rules are unit-tested. Values are text keyed by
 * [AttributeField.key]. [build] is forgiving (a missing or bad value falls back to the field's default, numbers are clamped),
 * while [problems] says what is wrong so the panel can tell the user instead of silently changing their input.
 */
object AttributeForm {

    fun kindOf(attribute: TileAttribute): AttributeKind = when (attribute) {
        TileAttribute.None -> AttributeKind.NONE
        TileAttribute.Blocked -> AttributeKind.BLOCKED
        is TileAttribute.DirectionalBlock -> AttributeKind.DIRECTIONAL_BLOCK
        is TileAttribute.Warp -> AttributeKind.WARP
        is TileAttribute.ItemSpawn -> AttributeKind.ITEM_SPAWN
        TileAttribute.NpcAvoid -> AttributeKind.NPC_AVOID
        is TileAttribute.KeyDoor -> AttributeKind.KEY_DOOR
        is TileAttribute.ResourceNode -> AttributeKind.RESOURCE_NODE
        is TileAttribute.ShopTile -> AttributeKind.SHOP
        TileAttribute.BankTile -> AttributeKind.BANK
        is TileAttribute.HealTrap -> AttributeKind.HEAL_TRAP
        is TileAttribute.Slide -> AttributeKind.SLIDE
        is TileAttribute.LightSource -> AttributeKind.LIGHT_SOURCE
        is TileAttribute.SoundEmitter -> AttributeKind.SOUND_EMITTER
    }

    /** The default values for every field of [kind]. */
    fun defaults(kind: AttributeKind): Map<String, String> = kind.fields.associate { it.key to it.default }

    /** The form values that describe [attribute] (used to fill the panel after the eyedropper picks a tile). */
    fun valuesOf(attribute: TileAttribute): Map<String, String> = when (attribute) {
        is TileAttribute.DirectionalBlock -> mapOf(
            "up" to attribute.blockUp.toString(), "down" to attribute.blockDown.toString(),
            "left" to attribute.blockLeft.toString(), "right" to attribute.blockRight.toString()
        )
        is TileAttribute.Warp -> mapOf(
            "map" to attribute.targetMapId.toString(), "x" to attribute.targetX.toString(),
            "y" to attribute.targetY.toString(), "dir" to (attribute.targetDirection?.name ?: "")
        )
        is TileAttribute.ItemSpawn -> mapOf("item" to attribute.itemId.toString(), "amount" to attribute.amount.toString(), "respawn" to attribute.respawnTicks.toString())
        is TileAttribute.KeyDoor -> mapOf("key" to attribute.keyItemId.toString(), "consume" to attribute.consumeKey.toString(), "open" to attribute.unlockDurationTicks.toString())
        is TileAttribute.ResourceNode -> mapOf("resource" to attribute.resourceId.toString())
        is TileAttribute.ShopTile -> mapOf("shop" to attribute.shopId.toString())
        is TileAttribute.HealTrap -> mapOf(
            "vital" to attribute.vitalType.name, "amount" to attribute.amount.toString(), "trap" to attribute.isTrapDamage.toString(),
            "interval" to attribute.intervalTicks.toString(), "animation" to attribute.animationId.toString()
        )
        is TileAttribute.Slide -> mapOf("dir" to attribute.direction.name)
        is TileAttribute.LightSource -> mapOf(
            "radius" to formatNumber(attribute.radiusPixels.toDouble()), "color" to attribute.colorHex,
            "intensity" to formatNumber(attribute.intensity.toDouble()), "flicker" to attribute.flicker.toString()
        )
        is TileAttribute.SoundEmitter -> mapOf("sound" to attribute.soundId, "radius" to attribute.radiusTiles.toString(), "interval" to attribute.intervalTicks.toString())
        else -> emptyMap()
    }

    /** What is wrong with [values] for [kind], one message per bad field; empty when everything is acceptable. */
    fun problems(kind: AttributeKind, values: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        for (f in kind.fields) {
            val raw = (values[f.key] ?: f.default).trim()
            when (f.type) {
                FieldType.INT -> {
                    val n = raw.toIntOrNull()
                    if (n == null) out += "${f.label} must be a whole number"
                    else if (n < f.min) out += "${f.label} must be at least ${formatNumber(f.min)}"
                }
                FieldType.FLOAT -> {
                    val n = raw.toDoubleOrNull()
                    if (n == null) out += "${f.label} must be a number"
                    else if (n < f.min) out += "${f.label} must be at least ${formatNumber(f.min)}"
                }
                FieldType.TEXT -> {
                    if (raw.isEmpty()) out += "${f.label} cannot be empty"
                    else if (f.key == "color" && !HEX_COLOR.matches(raw)) out += "${f.label} must look like #RRGGBB"
                }
                else -> Unit
            }
        }
        if (kind == AttributeKind.DIRECTIONAL_BLOCK && listOf("up", "down", "left", "right").none { values[it] == "true" }) {
            out += "Block at least one edge, or use Blocked / None"
        }
        return out
    }

    /** Builds the attribute for [kind] from [values], filling anything missing or invalid from the field defaults. */
    fun build(kind: AttributeKind, values: Map<String, String>): TileAttribute {
        fun int(key: String): Int {
            val f = kind.fields.first { it.key == key }
            return (values[key]?.trim()?.toIntOrNull() ?: f.default.toInt()).coerceAtLeast(f.min.coerceAtLeast(Int.MIN_VALUE.toDouble()).toInt())
        }
        fun float(key: String): Float {
            val f = kind.fields.first { it.key == key }
            return (values[key]?.trim()?.toFloatOrNull() ?: f.default.toFloat()).coerceAtLeast(f.min.toFloat())
        }
        fun bool(key: String): Boolean = (values[key] ?: kind.fields.first { it.key == key }.default) == "true"
        fun text(key: String): String {
            val f = kind.fields.first { it.key == key }
            val raw = values[key]?.trim().orEmpty()
            return if (raw.isEmpty() || (key == "color" && !HEX_COLOR.matches(raw))) f.default else raw
        }
        fun direction(key: String, fallback: Direction?): Direction? =
            values[key]?.let { v -> Direction.entries.firstOrNull { it.name == v } } ?: fallback

        return when (kind) {
            AttributeKind.NONE -> TileAttribute.None
            AttributeKind.BLOCKED -> TileAttribute.Blocked
            AttributeKind.DIRECTIONAL_BLOCK -> TileAttribute.DirectionalBlock(bool("up"), bool("down"), bool("left"), bool("right"))
            AttributeKind.WARP -> TileAttribute.Warp(int("map"), int("x"), int("y"), direction("dir", null))
            AttributeKind.ITEM_SPAWN -> TileAttribute.ItemSpawn(int("item"), int("amount"), int("respawn"))
            AttributeKind.NPC_AVOID -> TileAttribute.NpcAvoid
            AttributeKind.KEY_DOOR -> TileAttribute.KeyDoor(int("key"), bool("consume"), int("open"))
            AttributeKind.RESOURCE_NODE -> TileAttribute.ResourceNode(int("resource"))
            AttributeKind.SHOP -> TileAttribute.ShopTile(int("shop"))
            AttributeKind.BANK -> TileAttribute.BankTile
            AttributeKind.HEAL_TRAP -> TileAttribute.HealTrap(
                VitalType.entries.firstOrNull { it.name == values["vital"] } ?: VitalType.HP,
                int("amount"), bool("trap"), int("interval"), int("animation")
            )
            AttributeKind.SLIDE -> TileAttribute.Slide(direction("dir", Direction.DOWN)!!)
            AttributeKind.LIGHT_SOURCE -> TileAttribute.LightSource(float("radius"), text("color"), float("intensity").coerceAtMost(1f), bool("flicker"))
            AttributeKind.SOUND_EMITTER -> TileAttribute.SoundEmitter(text("sound"), int("radius"), int("interval"))
        }
    }

    /** A short description of an attribute for the status bar and tooltips, e.g. "Warp to map 2 (5, 8)". */
    fun describe(attribute: TileAttribute): String = when (attribute) {
        TileAttribute.None -> "None"
        TileAttribute.Blocked -> "Blocked"
        is TileAttribute.DirectionalBlock -> "One-way block (" + listOfNotNull(
            "up".takeIf { attribute.blockUp }, "down".takeIf { attribute.blockDown },
            "left".takeIf { attribute.blockLeft }, "right".takeIf { attribute.blockRight }
        ).joinToString(", ") + ")"
        is TileAttribute.Warp -> "Warp to map ${attribute.targetMapId} (${attribute.targetX}, ${attribute.targetY})"
        is TileAttribute.ItemSpawn -> "Item spawn: item ${attribute.itemId} x${attribute.amount}"
        TileAttribute.NpcAvoid -> "NPC avoid"
        is TileAttribute.KeyDoor -> "Key door: key item ${attribute.keyItemId}"
        is TileAttribute.ResourceNode -> "Resource node ${attribute.resourceId}"
        is TileAttribute.ShopTile -> "Shop ${attribute.shopId}"
        TileAttribute.BankTile -> "Bank"
        is TileAttribute.HealTrap -> (if (attribute.isTrapDamage) "Trap " else "Heal ") + "${attribute.amount} ${attribute.vitalType}"
        is TileAttribute.Slide -> "Slide ${attribute.direction.name.lowercase()}"
        is TileAttribute.LightSource -> "Light, radius ${formatNumber(attribute.radiusPixels.toDouble())}"
        is TileAttribute.SoundEmitter -> "Sound '${attribute.soundId}'"
    }

    private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}$")

    private fun formatNumber(n: Double): String = if (n == Math.floor(n) && !n.isInfinite()) n.toLong().toString() else n.toString()
}
