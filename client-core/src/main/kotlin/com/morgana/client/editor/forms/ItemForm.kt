package com.morgana.client.editor.forms

import com.morgana.client.editor.FieldRef
import com.morgana.domain.game.GameDefinition
import com.morgana.domain.item.GemEffect
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.PaperdollSlot
import com.morgana.domain.item.ToolType
import com.morgana.domain.item.WeaponStyle
import com.morgana.domain.stats.StatusEffectType
import com.morgana.protocol.WorldContentCatalogDto

/** The item form: every field of an [ItemDefinition], grouped, with per-attribute and per-vital fields from the game. */
object ItemForm : ContentForm<ItemDefinition> {
    override val title = "Items"
    override val noun = "item"
    override val firstId = 1

    private fun choices(entries: Iterable<Enum<*>>) = entries.map { it.name }

    override fun sections(context: FormContext): List<FormSection> = listOf(
        FormSection("Basics", listOf(
            FormField("name", "Name", FormFieldType.TEXT, required = true),
            FormField("description", "Description", FormFieldType.TEXT),
            FormField("icon", "Icon", FormFieldType.ICON, "1", min = 1),
            FormField("type", "Type", FormFieldType.CHOICE, "MATERIAL", choices(ItemType.entries)),
            FormField("price", "Base price", FormFieldType.INT, "10", min = 0),
            FormField("stackable", "Stackable", FormFieldType.BOOL, "false"),
            FormField("maxStack", "Largest stack", FormFieldType.INT, "1", min = 1, max = 99_999_999),
            FormField("bound", "Bound when picked up", FormFieldType.BOOL, "false"),
            FormField("rollAffixes", "Rolls a rarity and affixes when dropped", FormFieldType.BOOL, "true", hint = "Needs loot rarities in Game rules. Untick for fixed unique items.")
        )),
        FormSection("Requirements", listOf(
            FormField("levelReq", "Level needed", FormFieldType.INT, "1", min = 1),
            FormField("equipSlot", "Worn in slot (id)", FormFieldType.TEXT, "", hint = "Blank wears it where its type goes. Otherwise one of: ${context.slotIds.joinToString()}."),
            FormField("promotionReq", "Promotion tier needed", FormFieldType.INT, "0", min = 0),
            FormField("classReq", "Classes allowed (ids)", FormFieldType.ID_LIST, hint = "Empty means every class.")
        ) + context.attributeIds.map { FormField("req.$it", "Needs $it", FormFieldType.INT, "0", min = 0) }),
        FormSection("Weapon and armour", listOf(
            FormField("attackDamage", "Attack damage", FormFieldType.INT, "0", min = 0),
            FormField("armorDefense", "Armour defence", FormFieldType.INT, "0", min = 0),
            FormField("attackSpeed", "Attack delay (ticks)", FormFieldType.INT, "12", min = 1, hint = "Time between attacks. The game runs 20 ticks a second, so 20 is one second."),
            FormField("range", "Range (tiles)", FormFieldType.INT, "1", min = 1, max = 30, hint = "How far away the weapon can hit. 1 means the next tile."),
            FormField("weaponStyle", "Weapon style", FormFieldType.CHOICE, "MELEE", choices(WeaponStyle.entries)),
            FormField("projectile", "Projectile sprite", FormFieldType.INT, "0", min = 0),
            FormField("ammo", "Ammunition item", FormFieldType.INT, "0", min = 0, ref = FieldRef.ITEM, hint = "0 means none."),
            FormField("paperdoll", "Paperdoll sprite", FormFieldType.INT, "0", min = 0, hint = "0 means none."),
            FormField("paperdollOffHand", "Paperdoll sprite (off-hand)", FormFieldType.INT, "0", min = 0, hint = "Used when worn in the off-hand slot; 0 reuses the paperdoll sprite."),
            FormField("paperdollSlot", "Paperdoll slot", FormFieldType.CHOICE, "", choices(PaperdollSlot.entries), optional = true, hint = "Blank derives from item type."),
            FormField("paperdollTintHex", "Paperdoll tint (#rrggbb)", FormFieldType.TEXT, "", hint = "Optional dye colour when Paperdolling v2 is enabled."),
            FormField("paperdollClassTints", "Paperdoll tint by class", FormFieldType.TEXT, "", hint = "Optional colour per wearer class: classId=#rrggbb, comma-separated (e.g. 1=#d9453a, 3=#5fae3f)."),
            FormField("paperdollHidesSlots", "Paperdoll hides slots", FormFieldType.TEXT, "", hint = "Comma-separated PaperdollSlot names (e.g. HAIR).")
        )),
        FormSection("Upgrades and gems", listOf(
            FormField("nextTier", "Next tier item", FormFieldType.INT, "0", min = 0, ref = FieldRef.ITEM, hint = "Gear this turns into with a level upgrade. 0 means none."),
            FormField("gemAttack", "Gem: +damage", FormFieldType.INT, "0", min = 0, hint = "Any gem value above 0 makes this item a gem."),
            FormField("gemArmor", "Gem: +armour", FormFieldType.INT, "0", min = 0),
            FormField("gemXp", "Gem: +kill XP (%)", FormFieldType.INT, "0", min = 0, max = 500)
        ) + context.attributeIds.map { FormField("gem.$it", "Gem: +$it", FormFieldType.INT, "0", min = 0) }),
        FormSection("Bonuses when worn", context.attributeIds.map { FormField("bonus.$it", "+$it", FormFieldType.INT, "0") } +
            context.vitalIds.map { FormField("vmax.$it", "+max $it", FormFieldType.INT, "0") }),
        FormSection("When used", context.vitalIds.map { FormField("restore.$it", "Restores $it", FormFieldType.INT, "0", min = 0) } + listOf(
            FormField("tool", "Gathering tool", FormFieldType.CHOICE, "NONE", choices(ToolType.entries)),
            FormField("teach", "Teaches spell", FormFieldType.INT, "0", min = 0, ref = FieldRef.SPELL, hint = "0 means none."),
            FormField("warpMapId", "Warp map", FormFieldType.INT, "0", min = 0, ref = FieldRef.MAP, hint = "0 means none."),
            FormField("warpX", "Warp tile X", FormFieldType.INT, "0", min = 0),
            FormField("warpY", "Warp tile Y", FormFieldType.INT, "0", min = 0),
            FormField("status", "Applies status", FormFieldType.CHOICE, "", choices(StatusEffectType.entries), optional = true),
            FormField("statusMagnitude", "Status strength", FormFieldType.INT, "0", min = 0),
            FormField("statusDuration", "Status duration (ticks)", FormFieldType.INT, "0", min = 0, hint = "20 ticks is one second."),
            FormField("animation", "Animation", FormFieldType.ANIMATION, "0", min = 0)
        ))
    )

    override fun idOf(definition: ItemDefinition) = definition.id
    override fun nameOf(definition: ItemDefinition) = definition.name
    override fun create(id: Int, context: FormContext) = ItemDefinition(id = id, name = "New item $id", iconId = (id % 16).coerceAtLeast(1))
    override fun all(catalog: WorldContentCatalogDto) = catalog.items.sortedBy { it.id }

    override fun valuesOf(definition: ItemDefinition, context: FormContext): Map<String, String> {
        val d = definition
        return mapOf(
            "name" to d.name, "description" to d.description, "icon" to d.iconId.toString(), "type" to d.type.name,
            "price" to d.price.toString(), "rollAffixes" to d.rollAffixes.toString(), "stackable" to d.stackable.toString(), "maxStack" to d.maxStack.toString(),
            "bound" to d.boundOnPickup.toString(), "levelReq" to d.levelReq.toString(), "promotionReq" to d.promotionReq.toString(), "equipSlot" to d.equipSlot,
            "classReq" to d.classReqIds.sorted().joinToString(", "),
            "attackDamage" to d.attackDamage.toString(), "armorDefense" to d.armorDefense.toString(),
            "attackSpeed" to d.attackSpeedTicks.toString(), "range" to d.rangeTiles.toString(),
            "weaponStyle" to d.weaponStyle.name, "projectile" to d.projectileSpriteId.toString(),
            "ammo" to d.requiredAmmoItemId.toString(), "paperdoll" to d.paperdollSpriteId.toString(), "paperdollOffHand" to d.paperdollOffHandSpriteId.toString(),
            "paperdollSlot" to (d.paperdollSlot?.name ?: ""),
            "paperdollTintHex" to (d.paperdollTintHex ?: ""),
            "paperdollClassTints" to d.paperdollClassTints.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" },
            "paperdollHidesSlots" to d.paperdollHidesSlots.map { it.name }.sorted().joinToString(", "),
            "tool" to d.toolType.name, "teach" to d.teachSpellId.toString(),
            "warpMapId" to d.warpMapId.toString(), "warpX" to d.warpX.toString(), "warpY" to d.warpY.toString(),
            "status" to (d.statusEffectToApply?.name ?: ""),
            "statusMagnitude" to d.statusEffectMagnitude.toString(), "statusDuration" to d.statusEffectDurationTicks.toString(),
            "animation" to d.animationId.toString(),
            "nextTier" to d.nextTierItemId.toString(),
            "gemAttack" to (d.gem?.attackDamage ?: 0).toString(), "gemArmor" to (d.gem?.armorDefense ?: 0).toString(),
            "gemXp" to (d.gem?.xpPercent ?: 0).toString()
        ) + FormRules.mapValues(d.gem?.attributeBonuses.orEmpty(), "gem.", context.attributeIds) + FormRules.mapValues(d.attributeReq, "req.", context.attributeIds) +
            FormRules.mapValues(d.attributeBonuses, "bonus.", context.attributeIds) +
            FormRules.mapValues(d.vitalMaxBonuses, "vmax.", context.vitalIds) +
            FormRules.mapValues(d.restore, "restore.", context.vitalIds)
    }

    override fun apply(definition: ItemDefinition, values: Map<String, String>, context: FormContext): ItemDefinition {
        val d = definition
        fun i(key: String, fallback: Int, min: Int = Int.MIN_VALUE) = FormRules.int(values, key, fallback).coerceAtLeast(min)
        return d.copy(
            name = FormRules.text(values, "name", d.name).ifBlank { d.name },
            description = FormRules.text(values, "description", d.description),
            iconId = i("icon", d.iconId, 1),
            type = ItemType.entries.firstOrNull { it.name == values["type"] } ?: d.type,
            price = i("price", d.price, 0),
            rollAffixes = FormRules.bool(values, "rollAffixes", d.rollAffixes),
            stackable = FormRules.bool(values, "stackable", d.stackable),
            maxStack = i("maxStack", d.maxStack, 1),
            boundOnPickup = FormRules.bool(values, "bound", d.boundOnPickup),
            levelReq = i("levelReq", d.levelReq, 1),
            promotionReq = i("promotionReq", d.promotionReq, 0),
            equipSlot = if (values.containsKey("equipSlot")) FormRules.text(values, "equipSlot", d.equipSlot).trim().uppercase() else d.equipSlot,
            classReqIds = values["classReq"]?.let { FormRules.parseIds(it)?.toSet() } ?: d.classReqIds,
            attributeReq = FormRules.mergeMap(d.attributeReq, "req.", context.attributeIds, values),
            attributeBonuses = FormRules.mergeMap(d.attributeBonuses, "bonus.", context.attributeIds, values),
            vitalMaxBonuses = FormRules.mergeMap(d.vitalMaxBonuses, "vmax.", context.vitalIds, values),
            restore = FormRules.mergeMap(d.restore, "restore.", context.vitalIds, values),
            attackDamage = i("attackDamage", d.attackDamage, 0),
            armorDefense = i("armorDefense", d.armorDefense, 0),
            attackSpeedTicks = i("attackSpeed", d.attackSpeedTicks, 1),
            rangeTiles = i("range", d.rangeTiles, 1),
            weaponStyle = WeaponStyle.entries.firstOrNull { it.name == values["weaponStyle"] } ?: d.weaponStyle,
            projectileSpriteId = i("projectile", d.projectileSpriteId, 0),
            requiredAmmoItemId = i("ammo", d.requiredAmmoItemId, 0),
            paperdollSpriteId = i("paperdoll", d.paperdollSpriteId, 0),
            paperdollOffHandSpriteId = i("paperdollOffHand", d.paperdollOffHandSpriteId, 0),
            paperdollSlot = if (values.containsKey("paperdollSlot")) PaperdollSlot.entries.firstOrNull { it.name == values["paperdollSlot"] } else d.paperdollSlot,
            paperdollTintHex = if (values.containsKey("paperdollTintHex")) values["paperdollTintHex"]?.trim()?.takeIf { it.isNotEmpty() } else d.paperdollTintHex,
            paperdollClassTints = if (values.containsKey("paperdollClassTints")) parseClassTints(values["paperdollClassTints"]) ?: d.paperdollClassTints else d.paperdollClassTints,
            paperdollHidesSlots = if (values.containsKey("paperdollHidesSlots")) parseSlots(values["paperdollHidesSlots"]) ?: d.paperdollHidesSlots else d.paperdollHidesSlots,
            toolType = ToolType.entries.firstOrNull { it.name == values["tool"] } ?: d.toolType,
            teachSpellId = i("teach", d.teachSpellId, 0),
            warpMapId = i("warpMapId", d.warpMapId, 0),
            warpX = i("warpX", d.warpX, 0),
            warpY = i("warpY", d.warpY, 0),
            statusEffectToApply = if (values.containsKey("status")) StatusEffectType.entries.firstOrNull { it.name == values["status"] } else d.statusEffectToApply,
            statusEffectMagnitude = i("statusMagnitude", d.statusEffectMagnitude, 0),
            statusEffectDurationTicks = i("statusDuration", d.statusEffectDurationTicks, 0),
            animationId = i("animation", d.animationId, 0),
            nextTierItemId = i("nextTier", d.nextTierItemId, 0),
            gem = GemEffect(
                attributeBonuses = FormRules.mergeMap(d.gem?.attributeBonuses.orEmpty(), "gem.", context.attributeIds, values),
                attackDamage = i("gemAttack", d.gem?.attackDamage ?: 0, 0),
                armorDefense = i("gemArmor", d.gem?.armorDefense ?: 0, 0),
                xpPercent = i("gemXp", d.gem?.xpPercent ?: 0, 0)
            ).takeIf { !it.isEmpty }
        )
    }

    override fun extraProblems(values: Map<String, String>, context: FormContext): List<String> {
        val out = ArrayList<String>()
        val ammo = values["ammo"]?.trim()?.toIntOrNull() ?: 0
        if (ammo != 0 && context.catalog.items.none { it.id == ammo } && context.catalog.items.isNotEmpty()) out += "Ammunition item $ammo does not exist"
        val teach = values["teach"]?.trim()?.toIntOrNull() ?: 0
        if (teach != 0 && context.catalog.spells.none { it.id == teach } && context.catalog.spells.isNotEmpty()) out += "Spell $teach does not exist"
        val warpMap = values["warpMapId"]?.trim()?.toIntOrNull() ?: 0
        if (warpMap != 0 && context.catalog.maps.none { it.id == warpMap } && context.catalog.maps.isNotEmpty()) out += "Warp map $warpMap does not exist"
        val stack = values["maxStack"]?.trim()?.toIntOrNull() ?: 1
        if (values["stackable"] != "true" && stack > 1 && values["type"] !in STACKING_TYPES) out += "Largest stack is above 1 but the item is not stackable"
        val status = values["status"].orEmpty()
        if (status.isEmpty() && ((values["statusMagnitude"]?.trim()?.toIntOrNull() ?: 0) != 0 || (values["statusDuration"]?.trim()?.toIntOrNull() ?: 0) != 0)) {
            out += "Status strength and duration are set but no status is chosen"
        }
        val tint = values["paperdollTintHex"]?.trim().orEmpty()
        if (tint.isNotEmpty() && !GameDefinition.COLOR_PATTERN.matches(tint)) {
            out += "Paperdoll tint must look like #rrggbb"
        }
        val rawClassTints = values["paperdollClassTints"]?.trim().orEmpty()
        if (rawClassTints.isNotEmpty() && parseClassTints(rawClassTints) == null) {
            out += "Paperdoll tint by class must look like 1=#rrggbb, 2=#rrggbb"
        }
        val rawHideSlots = values["paperdollHidesSlots"]?.trim().orEmpty()
        if (rawHideSlots.isNotEmpty() && parseSlots(rawHideSlots) == null) {
            out += "Paperdoll hides slots must be PaperdollSlot names (${PaperdollSlot.entries.joinToString { it.name }})"
        }
        return out
    }

    /** "1=#d9453a, 3=#5fae3f" as classId to colour; null when an entry is malformed. */
    private fun parseClassTints(text: String?): Map<Int, String>? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return emptyMap()
        val out = linkedMapOf<Int, String>()
        for (part in raw.split(',')) {
            val token = part.trim()
            if (token.isEmpty()) continue
            val id = token.substringBefore('=').trim().toIntOrNull() ?: return null
            val hex = token.substringAfter('=', "").trim()
            if (!GameDefinition.COLOR_PATTERN.matches(hex)) return null
            out[id] = hex
        }
        return out
    }

    private fun parseSlots(text: String?): Set<PaperdollSlot>? {
        val raw = text?.trim() ?: return emptySet()
        if (raw.isEmpty()) return emptySet()
        val out = linkedSetOf<PaperdollSlot>()
        for (part in raw.split(',')) {
            val token = part.trim()
            if (token.isEmpty()) continue
            val slot = PaperdollSlot.entries.firstOrNull { it.name.equals(token, ignoreCase = true) } ?: return null
            out += slot
        }
        return out
    }

    /** Item types that always stack whatever the Stackable tick says (see [ItemDefinition.isEffectivelyStackable]). */
    private val STACKING_TYPES = setOf(ItemType.CURRENCY.name, ItemType.AMMO.name, ItemType.CONSUMABLE.name, ItemType.MATERIAL.name)
}
