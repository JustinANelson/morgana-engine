package com.morgana.domain.validation

import com.morgana.domain.content.ActionSkillDefinition
import com.morgana.domain.content.AnimationDefinition
import com.morgana.domain.content.WeaponSetDefinition
import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.content.ContentSchema
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.event.CommonEventDefinition
import com.morgana.domain.event.CommonEventTrigger
import com.morgana.domain.event.EventCommand
import com.morgana.domain.event.GearUpgradeKind
import com.morgana.domain.event.EventCondition
import com.morgana.domain.event.EventConditionType
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.game.GameDefinition
import com.morgana.domain.game.VitalRole
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.map.TileAnimation
import com.morgana.domain.map.FloorGeneration
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.spatial.Direction
import kotlinx.serialization.Serializable

/** Every authorable content collection (one JSON document per entry, one directory per collection). */
enum class ContentCollection(val directoryName: String, val filePrefix: String) {
    MAPS("maps", "map"),
    CLASSES("classes", "class"),
    ITEMS("items", "item"),
    NPCS("npcs", "npc"),
    SPELLS("spells", "spell"),
    SHOPS("shops", "shop"),
    RESOURCES("resources", "resource"),
    RECIPES("recipes", "recipe"),
    QUESTS("quests", "quest"),
    EVENTS("events", "event"),
    COMMON_EVENTS("common_events", "common_event"),
    ANIMATIONS("animations", "anim"),
    ACTION_SKILLS("action_skills", "action_skill"),
    WEAPON_SETS("weapon_sets", "weapon_set");

    /** File stem for a document id, e.g. `item_007`. */
    fun fileStem(id: Int): String = "%s_%03d".format(filePrefix, id)
}

enum class IssueSeverity { ERROR, WARNING }

/**
 * Machine-readable content problem. [pointer] is an RFC 6901 JSON pointer into the document
 * (empty string = whole document), so tools and agents can jump straight to the offending field.
 */
@Serializable
data class ContentIssue(
    val severity: IssueSeverity,
    val collection: ContentCollection,
    val id: Int,
    val pointer: String,
    val code: String,
    val message: String,
    /** Source file relative to the world directory, when known (e.g. `items/item_007.json`). */
    val file: String = ""
) {
    override fun toString(): String {
        val where = file.ifBlank { "${collection.directoryName}/${collection.fileStem(id)}.json" }
        return "${severity.name} $where$pointer [$code] $message"
    }
}

@Serializable
data class ContentReport(val issues: List<ContentIssue>) {
    val errors: List<ContentIssue> get() = issues.filter { it.severity == IssueSeverity.ERROR }
    val warnings: List<ContentIssue> get() = issues.filter { it.severity == IssueSeverity.WARNING }
    val isValid: Boolean get() = errors.isEmpty()

    fun forDocument(collection: ContentCollection, id: Int): ContentReport =
        ContentReport(issues.filter { it.collection == collection && it.id == id })
}

/** Immutable bundle of every content document, keyed by id. Pure data: no I/O. */
data class ContentCatalog(
    val maps: Map<Int, MapDefinition> = emptyMap(),
    val classes: Map<Int, ClassDefinition> = emptyMap(),
    val items: Map<Int, ItemDefinition> = emptyMap(),
    val npcs: Map<Int, NpcDefinition> = emptyMap(),
    val spells: Map<Int, SpellDefinition> = emptyMap(),
    val shops: Map<Int, ShopDefinition> = emptyMap(),
    val resources: Map<Int, ResourceDefinition> = emptyMap(),
    val recipes: Map<Int, RecipeDefinition> = emptyMap(),
    val quests: Map<Int, QuestDefinition> = emptyMap(),
    val events: Map<Int, EventDefinition> = emptyMap(),
    val commonEvents: Map<Int, CommonEventDefinition> = emptyMap(),
    val animations: Map<Int, AnimationDefinition> = emptyMap(),
    /** Action-combat skills (ADR 0017); empty in a game that uses another combat module. */
    val actionSkills: Map<Int, ActionSkillDefinition> = emptyMap(),
    /** Weapon sets: the combo graphs over [actionSkills]. */
    val weaponSets: Map<Int, WeaponSetDefinition> = emptyMap()
)

/**
 * Pure, deterministic validator for schema versions, field ranges, and cross-document references.
 * Shared by the F1 editor save path, the file hot-reloader, and the `validateContent` CLI so every
 * authoring route enforces identical rules.
 */
/** Deepest nesting of event steps (answers and if branches inside one another) the content rules accept. */
private const val MAX_EVENT_NESTING = 8

object ContentValidator {

    fun validate(catalog: ContentCatalog, game: GameDefinition): ContentReport {
        val c = Collector(catalog, game)
        catalog.maps.values.sortedBy { it.id }.forEach { c.map(it) }
        catalog.classes.values.sortedBy { it.id }.forEach { c.cls(it) }
        catalog.items.values.sortedBy { it.id }.forEach { c.item(it) }
        catalog.npcs.values.sortedBy { it.id }.forEach { c.npc(it) }
        catalog.spells.values.sortedBy { it.id }.forEach { c.spell(it) }
        catalog.shops.values.sortedBy { it.id }.forEach { c.shop(it) }
        catalog.resources.values.sortedBy { it.id }.forEach { c.resource(it) }
        catalog.recipes.values.sortedBy { it.id }.forEach { c.recipe(it) }
        catalog.quests.values.sortedBy { it.id }.forEach { c.quest(it) }
        catalog.events.values.sortedBy { it.id }.forEach { c.event(it) }
        catalog.commonEvents.values.sortedBy { it.id }.forEach { c.commonEvent(it) }
        catalog.animations.values.sortedBy { it.id }.forEach { c.animation(it) }
        catalog.actionSkills.values.sortedBy { it.id }.forEach { c.actionSkill(it) }
        catalog.weaponSets.values.sortedBy { it.id }.forEach { c.weaponSet(it) }
        game.gearUpgrade?.enhancement?.tiers?.forEach { t ->
            if (t.materialItemId !in catalog.items) c.error(ContentCollection.ITEMS, t.materialItemId, "/gearUpgrade/enhancement/tiers/${t.plus - 1}/materialItemId", "missing_reference", "Enhancement to +${t.plus} uses item ${t.materialItemId} which does not exist")
        }
        game.promotion?.tiers?.forEachIndexed { i, t ->
            val at = "/promotion/tiers/$i"
            t.classNames.forEach { (classId, name) ->
                if (classId !in catalog.classes) c.error(ContentCollection.CLASSES, classId, "$at/classNames", "missing_reference", "Promotion tier ${t.name} names class $classId which does not exist")
                if (name.isBlank()) c.error(ContentCollection.CLASSES, classId, "$at/classNames", "invalid_value", "Promotion tier ${t.name} gives class $classId a blank title")
            }
            t.classGrantSpellIds.forEach { (classId, spellIds) ->
                if (classId !in catalog.classes) c.error(ContentCollection.CLASSES, classId, "$at/classGrantSpellIds", "missing_reference", "Promotion tier ${t.name} grants spells to class $classId which does not exist")
                spellIds.filter { it !in catalog.spells }.forEach { c.error(ContentCollection.SPELLS, it, "$at/classGrantSpellIds", "missing_reference", "Promotion tier ${t.name} grants spell $it which does not exist") }
            }
            t.grantSpellIds.filter { it !in catalog.spells }.forEach { c.error(ContentCollection.SPELLS, it, "$at/grantSpellIds", "missing_reference", "Promotion tier ${t.name} grants spell $it which does not exist") }
        }
        return ContentReport(c.issues)
    }

    private val HEX_COLOR = Regex("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$")

    private class Collector(val cat: ContentCatalog, val game: GameDefinition) {
        val issues = mutableListOf<ContentIssue>()

        fun error(col: ContentCollection, id: Int, pointer: String, code: String, msg: String) {
            issues += ContentIssue(IssueSeverity.ERROR, col, id, pointer, code, msg)
        }

        fun warn(col: ContentCollection, id: Int, pointer: String, code: String, msg: String) {
            issues += ContentIssue(IssueSeverity.WARNING, col, id, pointer, code, msg)
        }

        /** Reference check; when [optional], an id of 0 means "none". */
        fun ref(
            col: ContentCollection, id: Int, pointer: String,
            target: ContentCollection, targetId: Int, exists: Boolean, optional: Boolean = true
        ) {
            if (optional && targetId == 0) return
            if (!exists) error(col, id, pointer, "missing_reference", "References ${target.directoryName} id $targetId which does not exist")
        }

        /**
         * Map-to-map links (warps, neighbors, warp commands/spells). A missing destination is a WARNING, not an error:
         * the simulation safely ignores such links, and maps legitimately reference each other in cycles, so creators
         * must be able to save map A before map B exists.
         */
        fun mapLink(col: ContentCollection, id: Int, pointer: String, targetMapId: Int, what: String) {
            if (targetMapId !in cat.maps) {
                warn(col, id, pointer, "missing_map_link", "$what targets maps id $targetMapId which does not exist yet (the link is ignored until it does)")
            }
        }

        fun itemRef(col: ContentCollection, id: Int, pointer: String, itemId: Int, optional: Boolean = true) =
            ref(col, id, pointer, ContentCollection.ITEMS, itemId, itemId in cat.items, optional)

        fun common(col: ContentCollection, id: Int, name: String, schemaVersion: Int) {
            if (id <= 0) error(col, id, "/id", "invalid_id", "id must be a positive integer (got $id)")
            if (name.isBlank()) error(col, id, "/name", "blank_name", "name must not be blank")
            if (schemaVersion > ContentSchema.CURRENT_VERSION) {
                error(col, id, "/schemaVersion", "newer_schema", "Document schemaVersion $schemaVersion is newer than supported ${ContentSchema.CURRENT_VERSION}")
            }
        }

        // ---- Maps ----------------------------------------------------------------------------
        fun map(m: MapDefinition) {
            if (com.morgana.domain.sim.MapInstances.isInstanceId(m.id)) {
                error(ContentCollection.MAPS, m.id, "/id", "reserved_id", "Map ids from ${com.morgana.domain.sim.MapInstances.FIRST_ID} up are reserved for instances; pick a smaller id")
            }
            val col = ContentCollection.MAPS
            common(col, m.id, m.name, m.schemaVersion)
            if (m.tileSize != 32) error(col, m.id, "/tileSize", "invalid_tile_size", "tileSize must be 32 (got ${m.tileSize})")
            if (!HEX_COLOR.matches(m.ambientLightHex)) error(col, m.id, "/ambientLightHex", "invalid_color", "Expected #RRGGBB or #RRGGBBAA, got '${m.ambientLightHex}'")
            val links = listOf(
                Triple("northMapId", m.neighbors.northMapId, Direction.UP),
                Triple("southMapId", m.neighbors.southMapId, Direction.DOWN),
                Triple("eastMapId", m.neighbors.eastMapId, Direction.RIGHT),
                Triple("westMapId", m.neighbors.westMapId, Direction.LEFT)
            )
            for ((field, target, dir) in links) {
                if (target == 0) continue
                val other = cat.maps[target]
                if (other == null) {
                    mapLink(col, m.id, "/neighbors/$field", target, "Neighbor link")
                } else if (other.neighbors.neighborFor(dir.opposite()) != m.id) {
                    warn(col, m.id, "/neighbors/$field", "non_reciprocal_neighbor", "Map $target does not link back to map ${m.id}")
                }
            }
            val zones = game.weather?.zones
            if (m.zoneId < 0) {
                error(col, m.id, "/zoneId", "invalid_weather_zone", "zoneId must be 0 or more")
            } else if (zones != null && m.zoneId != 0 && zones.none { it.id == m.zoneId }) {
                error(col, m.id, "/zoneId", "unknown_weather_zone", "Weather zone ${m.zoneId} is not defined in the game's weather zones")
            }
            if (m.instanceOwner == com.morgana.domain.map.InstanceOwner.GUILD && !m.instanced) {
                error(col, m.id, "/instanceOwner", "guild_owner_not_instanced", "instanceOwner GUILD only means something on an instanced map")
            }
            if (game.arena?.mapId == m.id && !m.instanced) {
                error(col, m.id, "/instanced", "arena_not_instanced", "The arena map must be instanced so every match gets its own copy")
            }
            m.scaling?.let { s ->
                if (!m.instanced) error(col, m.id, "/scaling", "scaling_not_instanced", "scaling only means something on an instanced map")
                if (s.maxMembers !in 1..50) error(col, m.id, "/scaling/maxMembers", "out_of_range", "maxMembers must be 1 to 50")
                if (s.hpPercentPerMember !in 0..1000) error(col, m.id, "/scaling/hpPercentPerMember", "out_of_range", "hpPercentPerMember must be 0 to 1000")
                if (s.attackPercentPerMember !in 0..1000) error(col, m.id, "/scaling/attackPercentPerMember", "out_of_range", "attackPercentPerMember must be 0 to 1000")
                if (s.xpPercentPerMember !in 0..1000) error(col, m.id, "/scaling/xpPercentPerMember", "out_of_range", "xpPercentPerMember must be 0 to 1000")
                if (s.percentPerLevel !in 0..50) error(col, m.id, "/scaling/percentPerLevel", "out_of_range", "percentPerLevel must be 0 to 50")
                if (s.maxLevelShift !in 0..100) error(col, m.id, "/scaling/maxLevelShift", "out_of_range", "maxLevelShift must be 0 to 100")
                if (s.tiers.size > 8) error(col, m.id, "/scaling/tiers", "out_of_range", "A dungeon offers at most 8 difficulty tiers")
                if (s.tiers.isNotEmpty() && s.tierVariable.isBlank()) error(col, m.id, "/scaling/tierVariable", "missing_required", "Difficulty tiers need a tierVariable")
                s.tiers.forEachIndexed { ti, t ->
                    val tp = "/scaling/tiers/$ti"
                    if (t.name.isBlank()) error(col, m.id, "$tp/name", "missing_required", "A difficulty tier needs a name")
                    for ((field, v) in listOf("hpPercent" to t.hpPercent, "attackPercent" to t.attackPercent, "xpPercent" to t.xpPercent, "dropRatePercent" to t.dropRatePercent)) {
                        if (v !in 10..1000) error(col, m.id, "$tp/$field", "out_of_range", "$field must be 10 to 1000")
                    }
                    if (t.minLevel < 1) error(col, m.id, "$tp/minLevel", "out_of_range", "minLevel must be at least 1")
                }
            }
            if (m.instanced && links.any { it.second != 0 }) {
                error(col, m.id, "/instanced", "instanced_with_neighbors", "An instanced map cannot have neighbouring maps (instances are always entered through a warp)")
            }
            val slots = mutableSetOf<Int>()
            m.npcSpawns.forEachIndexed { i, s ->
                val p = "/npcSpawns/$i"
                ref(col, m.id, "$p/npcId", ContentCollection.NPCS, s.npcId, s.npcId in cat.npcs, optional = false)
                if (!slots.add(s.slotIndex)) error(col, m.id, "$p/slotIndex", "duplicate_slot", "Duplicate spawn slotIndex ${s.slotIndex}")
                if (!m.isInBounds(s.spawnX, s.spawnY)) error(col, m.id, p, "out_of_bounds", "Spawn (${s.spawnX},${s.spawnY}) is outside the ${m.width}x${m.height} map")
            }
            m.eventIds.forEachIndexed { i, e ->
                ref(col, m.id, "/eventIds/$i", ContentCollection.EVENTS, e, e in cat.events, optional = false)
            }
            m.generator?.let { g ->
                for ((pointer, message) in FloorGeneration.problems(g)) error(col, m.id, pointer, "invalid_floor_generator", message)
                if (!m.instanced) error(col, m.id, "/generator", "generator_not_instanced", "A map with a floor generator must be instanced (each party gets its own generated floor)")
                g.monsters.forEachIndexed { i, s -> ref(col, m.id, "/generator/monsters/$i/npcId", ContentCollection.NPCS, s.npcId, s.npcId in cat.npcs, optional = false) }
                g.features.forEachIndexed { i, s -> ref(col, m.id, "/generator/features/$i/npcId", ContentCollection.NPCS, s.npcId, s.npcId in cat.npcs, optional = false) }
                if (g.bossNpcId != 0) ref(col, m.id, "/generator/bossNpcId", ContentCollection.NPCS, g.bossNpcId, g.bossNpcId in cat.npcs, optional = false)
                g.loot.forEachIndexed { i, l -> itemRef(col, m.id, "/generator/loot/$i/itemId", l.itemId, optional = false) }
                for ((placement, field) in listOf(g.floorTile to "floorTile", g.wallTile to "wallTile", g.stairsTile to "stairsTile") + g.decorations.mapIndexed { i, d -> d.tile to "decorations/$i/tile" }) {
                    for ((f, message) in TileAnimation.problems(placement)) error(col, m.id, "/generator/$field/$f", "invalid_tile_animation", message)
                }
                if (g.nextMapId != 0) {
                    val next = cat.maps[g.nextMapId]
                    when {
                        next == null -> mapLink(col, m.id, "/generator/nextMapId", g.nextMapId, "Next floor")
                        next.generator == null || !next.instanced -> error(col, m.id, "/generator/nextMapId", "next_floor_not_generated", "Map ${next.id} is not an instanced map with a floor generator")
                        next.id == m.id -> error(col, m.id, "/generator/nextMapId", "floor_loops_to_itself", "A floor cannot lead to itself")
                    }
                } else {
                    val exit = cat.maps[g.exitMapId]
                    if (exit == null) mapLink(col, m.id, "/generator/exitMapId", g.exitMapId, "Exit")
                    else if (!exit.isInBounds(g.exitX, g.exitY)) error(col, m.id, "/generator/exitX", "out_of_bounds", "Exit (${g.exitX},${g.exitY}) is outside map ${exit.id}")
                }
            }
            m.cells.forEachIndexed { index, cell ->
                for ((layer, placement) in cell.layers) {
                    for ((field, message) in TileAnimation.problems(placement)) {
                        error(col, m.id, "/cells/$index/layers/${layer.name}/$field", "invalid_tile_animation", message)
                    }
                }
                val p = "/cells/$index/attribute"
                when (val a = cell.attribute) {
                    is TileAttribute.Warp -> {
                        val dest = cat.maps[a.targetMapId]
                        if (dest == null) mapLink(col, m.id, p, a.targetMapId, "Warp")
                        else if (!dest.isInBounds(a.targetX, a.targetY)) error(col, m.id, p, "out_of_bounds", "Warp target (${a.targetX},${a.targetY}) is outside map ${dest.id}")
                    }
                    is TileAttribute.ItemSpawn -> itemRef(col, m.id, "$p/itemId", a.itemId, optional = false)
                    is TileAttribute.KeyDoor -> itemRef(col, m.id, "$p/keyItemId", a.keyItemId, optional = false)
                    is TileAttribute.ResourceNode ->
                        ref(col, m.id, "$p/resourceId", ContentCollection.RESOURCES, a.resourceId, a.resourceId in cat.resources, optional = false)
                    is TileAttribute.ShopTile ->
                        ref(col, m.id, "$p/shopId", ContentCollection.SHOPS, a.shopId, a.shopId in cat.shops, optional = false)
                    is TileAttribute.LightSource ->
                        if (!HEX_COLOR.matches(a.colorHex)) error(col, m.id, "$p/colorHex", "invalid_color", "Expected #RRGGBB, got '${a.colorHex}'")
                    else -> {}
                }
            }
        }

        // ---- Classes -------------------------------------------------------------------------
        fun cls(c: ClassDefinition) {
            val col = ContentCollection.CLASSES
            common(col, c.id, c.name, c.schemaVersion)
            val start = cat.maps[c.startMapId]
            if (start == null) error(col, c.id, "/startMapId", "missing_reference", "Start map ${c.startMapId} does not exist")
            else if (!start.isInBounds(c.startX, c.startY)) error(col, c.id, "/startX", "out_of_bounds", "Start (${c.startX},${c.startY}) is outside map ${start.id}")
            if (c.spriteIds.isEmpty()) error(col, c.id, "/spriteIds", "empty_list", "A class needs at least one sprite id")
            c.startingItems.forEachIndexed { i, s -> itemRef(col, c.id, "/startingItems/$i/itemId", s.itemId, optional = false) }
            c.startingSpellIds.forEachIndexed { i, s ->
                ref(col, c.id, "/startingSpellIds/$i", ContentCollection.SPELLS, s, s in cat.spells, optional = false)
            }
            c.baseAttributes.keys.forEach { attrId ->
                if (game.attributes.none { it.id == attrId }) {
                    error(col, c.id, "/baseAttributes/$attrId", "unknown_attribute", "Unknown attribute '$attrId'")
                }
            }
        }

        // ---- Items ---------------------------------------------------------------------------
        fun item(d: ItemDefinition) {
            val col = ContentCollection.ITEMS
            common(col, d.id, d.name, d.schemaVersion)
            if (d.maxStack < 1) error(col, d.id, "/maxStack", "out_of_range", "maxStack must be >= 1")
            if (d.stackable && d.maxStack < 2) warn(col, d.id, "/maxStack", "suspicious_stack", "stackable item has maxStack ${d.maxStack}")
            if (d.price < 0) error(col, d.id, "/price", "out_of_range", "price must be >= 0")
            if (d.mountSpeedPercent != 0 && d.mountSpeedPercent !in 101..200) error(col, d.id, "/mountSpeedPercent", "out_of_range", "mountSpeedPercent must be 0 (not a mount) or 101 to 200")
            if (d.levelReq < 1) error(col, d.id, "/levelReq", "out_of_range", "levelReq must be >= 1")
            d.equipmentSlotOrNull()?.let { slot ->
                if (d.equipSlot.isNotBlank() && !com.morgana.domain.item.EquipmentSlot.ID_PATTERN.matches(d.equipSlot)) {
                    error(col, d.id, "/equipSlot", "invalid_slot", "equipSlot must be upper-case letters, digits and underscores (got \"${d.equipSlot}\")")
                } else if (!game.hasSlot(slot)) {
                    error(col, d.id, "/equipSlot", "unknown_slot", "this game has no equipment slot \"${slot.id}\" (slots: ${game.slots.joinToString { it.id }})")
                }
            }
            ref(col, d.id, "/teachSpellId", ContentCollection.SPELLS, d.teachSpellId, d.teachSpellId in cat.spells)
            if (d.adoptPetNpcId > 0) {
                ref(col, d.id, "/adoptPetNpcId", ContentCollection.NPCS, d.adoptPetNpcId, d.adoptPetNpcId in cat.npcs)
                if (d.adoptPetNpcId in cat.npcs && cat.npcs.getValue(d.adoptPetNpcId).pet == null) error(col, d.id, "/adoptPetNpcId", "not_a_pet", "NPC ${d.adoptPetNpcId} has no pet section, so it cannot be adopted")
            }
            if (d.warpMapId > 0) {
                val dest = cat.maps[d.warpMapId]
                if (dest == null) mapLink(col, d.id, "/warpMapId", d.warpMapId, "Warp item")
                else if (!dest.isInBounds(d.warpX, d.warpY)) error(col, d.id, "/warpX", "out_of_bounds", "Warp target outside map ${dest.id}")
            }
            itemRef(col, d.id, "/requiredAmmoItemId", d.requiredAmmoItemId)
            if (d.nextTierItemId > 0) {
                val next = cat.items[d.nextTierItemId]
                when {
                    next == null -> itemRef(col, d.id, "/nextTierItemId", d.nextTierItemId, optional = false)
                    next.equipmentSlotOrNull() == null || next.equipmentSlotOrNull() != d.equipmentSlotOrNull() ->
                        error(col, d.id, "/nextTierItemId", "wrong_slot", "nextTierItemId must be gear for the same equipment slot")
                    next.id == d.id -> error(col, d.id, "/nextTierItemId", "self_reference", "an item cannot be its own next tier")
                }
            }
            d.gem?.let { gem ->
                gem.attributeBonuses.keys.forEach { attrId ->
                    if (game.attributes.none { it.id == attrId }) error(col, d.id, "/gem/attributeBonuses/$attrId", "unknown_attribute", "Unknown attribute '$attrId'")
                }
                if (gem.isEmpty) warn(col, d.id, "/gem", "empty_gem", "gem adds nothing")
                if (!d.isEffectivelyStackable) warn(col, d.id, "/gem", "gem_not_stackable", "a gem should be a stackable MATERIAL so it can sit in the bag")
            }
            ref(col, d.id, "/animationId", ContentCollection.ANIMATIONS, d.animationId, d.animationId in cat.animations)
            if (d.paperdollTintHex != null && !GameDefinition.COLOR_PATTERN.matches(d.paperdollTintHex)) {
                error(col, d.id, "/paperdollTintHex", "invalid_color", "paperdollTintHex must look like #rrggbb")
            }
            d.paperdollClassTints.forEach { (classId, hex) ->
                ref(col, d.id, "/paperdollClassTints", ContentCollection.CLASSES, classId, classId in cat.classes, optional = false)
                if (!GameDefinition.COLOR_PATTERN.matches(hex)) error(col, d.id, "/paperdollClassTints/$classId", "invalid_color", "paperdollClassTints colours must look like #rrggbb")
            }
            d.classReqIds.forEach { ref(col, d.id, "/classReqIds", ContentCollection.CLASSES, it, it in cat.classes, optional = false) }
            d.attributeReq.keys.forEach { attrId ->
                if (game.attributes.none { it.id == attrId }) error(col, d.id, "/attributeReq/$attrId", "unknown_attribute", "Unknown attribute '$attrId'")
            }
            d.attributeBonuses.keys.forEach { attrId ->
                if (game.attributes.none { it.id == attrId }) error(col, d.id, "/attributeBonuses/$attrId", "unknown_attribute", "Unknown attribute '$attrId'")
            }
            d.vitalMaxBonuses.keys.forEach { vitalId ->
                if (game.vitals.none { it.id == vitalId }) error(col, d.id, "/vitalMaxBonuses/$vitalId", "unknown_vital", "Unknown vital '$vitalId'")
            }
            d.restore.keys.forEach { vitalId ->
                if (game.vitals.none { it.id == vitalId }) error(col, d.id, "/restore/$vitalId", "unknown_vital", "Unknown vital '$vitalId'")
            }
        }

        // ---- NPCs ----------------------------------------------------------------------------
        fun npc(n: NpcDefinition) {
            val col = ContentCollection.NPCS
            common(col, n.id, n.name, n.schemaVersion)
            val lifeVital = game.vitals.firstOrNull { it.role == VitalRole.LIFE }
            val lifeHp = lifeVital?.let { n.vitals[it.id] } ?: 0
            if (lifeHp < 1) error(col, n.id, "/vitals/${lifeVital?.id ?: "hp"}", "out_of_range", "Life vital must be >= 1")
            if (n.level < 1) error(col, n.id, "/level", "out_of_range", "level must be >= 1")
            ref(col, n.id, "/shopId", ContentCollection.SHOPS, n.shopId, n.shopId in cat.shops)
            ref(col, n.id, "/eventId", ContentCollection.EVENTS, n.eventId, n.eventId in cat.events)
            ref(col, n.id, "/animationId", ContentCollection.ANIMATIONS, n.animationId, n.animationId in cat.animations)
            if (n.behavior == NpcBehavior.SHOPKEEPER && n.shopId == 0) error(col, n.id, "/shopId", "missing_required", "SHOPKEEPER NPC needs a shopId")
            if (n.behavior == NpcBehavior.QUEST_GIVER && n.eventId == 0) warn(col, n.id, "/eventId", "missing_required", "QUEST_GIVER NPC has no eventId")
            if (n.guildWarRole.isNotEmpty() && n.guildWarRole !in com.morgana.domain.game.GuildWarDefinition.ROLES) error(col, n.id, "/guildWarRole", "invalid_value", "guildWarRole must be blank, POLE or GATE")
            n.spellIds.forEachIndexed { i, s -> ref(col, n.id, "/spellIds/$i", ContentCollection.SPELLS, s, s in cat.spells, optional = false) }
            n.pet?.let { p ->
                if (n.behavior != com.morgana.domain.content.NpcBehavior.FRIENDLY_ROAM) error(col, n.id, "/behavior", "pet_not_friendly", "A pet NPC must have behavior FRIENDLY_ROAM so nothing attacks it")
                if (p.maxLevel !in 1..99) error(col, n.id, "/pet/maxLevel", "out_of_range", "maxLevel must be 1 to 99")
                if (p.xpPerLevel !in 1..1_000_000) error(col, n.id, "/pet/xpPerLevel", "out_of_range", "xpPerLevel must be 1 to 1000000")
                if (p.xpSharePercent !in 0..1000) error(col, n.id, "/pet/xpSharePercent", "out_of_range", "xpSharePercent must be 0 to 1000")
                if (p.damagePerLevelPercent !in 0..1000) error(col, n.id, "/pet/damagePerLevelPercent", "out_of_range", "damagePerLevelPercent must be 0 to 1000")
                if (p.followDistance !in 1..10) error(col, n.id, "/pet/followDistance", "out_of_range", "followDistance must be 1 to 10")
            }
            n.encounter?.let { enc ->
                if (enc.phases.isEmpty()) error(col, n.id, "/encounter/phases", "missing_required", "A boss encounter needs at least one phase")
                if (enc.lockoutTicks < 0) error(col, n.id, "/encounter/lockoutTicks", "out_of_range", "lockoutTicks must be 0 or more")
                var previous = Int.MAX_VALUE
                enc.phases.forEachIndexed { pi, ph ->
                    val p = "/encounter/phases/$pi"
                    if (ph.fromLifePercent !in 1..100) error(col, n.id, "$p/fromLifePercent", "out_of_range", "fromLifePercent must be 1 to 100")
                    if (ph.fromLifePercent >= previous) error(col, n.id, "$p/fromLifePercent", "phase_order", "Phases must go down in life percent (each lower than the one before)")
                    previous = ph.fromLifePercent
                    if (ph.attackPercent !in 10..1000) error(col, n.id, "$p/attackPercent", "out_of_range", "attackPercent must be 10 to 1000")
                    if (ph.moveSpeedPercent !in 10..1000) error(col, n.id, "$p/moveSpeedPercent", "out_of_range", "moveSpeedPercent must be 10 to 1000")
                    ph.summonNpcIds.forEachIndexed { si, id -> ref(col, n.id, "$p/summonNpcIds/$si", ContentCollection.NPCS, id, id in cat.npcs, optional = false) }
                    ph.abilities.forEachIndexed { ai, ab ->
                        val ap = "$p/abilities/$ai"
                        ref(col, n.id, "$ap/spellId", ContentCollection.SPELLS, ab.spellId, ab.spellId in cat.spells, optional = false)
                        if (ab.radiusTiles !in 0..20) error(col, n.id, "$ap/radiusTiles", "out_of_range", "radiusTiles must be 0 to 20")
                        if (ab.windUpTicks !in 1..2000) error(col, n.id, "$ap/windUpTicks", "out_of_range", "windUpTicks must be 1 to 2000")
                        if (ab.cooldownTicks !in 1..100000) error(col, n.id, "$ap/cooldownTicks", "out_of_range", "cooldownTicks must be 1 to 100000")
                    }
                }
            }
            n.drops.forEachIndexed { i, d ->
                itemRef(col, n.id, "/drops/$i/itemId", d.itemId, optional = false)
                if (d.chanceBasisPoints !in 0..10000) error(col, n.id, "/drops/$i/chanceBasisPoints", "out_of_range", "chanceBasisPoints must be 0..10000")
                if (d.minAmount < 1 || d.maxAmount < d.minAmount) error(col, n.id, "/drops/$i", "out_of_range", "Require 1 <= minAmount <= maxAmount")
            }
            n.attributes.keys.forEach { attrId ->
                if (game.attributes.none { it.id == attrId }) error(col, n.id, "/attributes/$attrId", "unknown_attribute", "Unknown attribute '$attrId'")
            }
            n.vitals.keys.forEach { vitalId ->
                if (game.vitals.none { it.id == vitalId }) error(col, n.id, "/vitals/$vitalId", "unknown_vital", "Unknown vital '$vitalId'")
            }
        }

        // ---- Spells --------------------------------------------------------------------------
        fun spell(s: SpellDefinition) {
            val col = ContentCollection.SPELLS
            common(col, s.id, s.name, s.schemaVersion)
            val lifeVital = game.vitals.firstOrNull { it.role == VitalRole.LIFE }
            s.costs.forEach { (vitalId, cost) ->
                if (game.vitals.none { it.id == vitalId }) error(col, s.id, "/costs/$vitalId", "unknown_vital", "Unknown vital '$vitalId'")
                if (cost < 0) error(col, s.id, "/costs/$vitalId", "out_of_range", "Cost must be >= 0")
                if (lifeVital != null && vitalId == lifeVital.id) error(col, s.id, "/costs/$vitalId", "invalid_cost", "Spell costs may not name the life vital")
            }
            if (s.cooldownTicks < 0 || s.castTimeTicks < 0) error(col, s.id, "/cooldownTicks", "out_of_range", "Timings must be >= 0")
            if (s.targetType == SpellTargetType.WARP) {
                val dest = cat.maps[s.warpMapId]
                if (dest == null) mapLink(col, s.id, "/warpMapId", s.warpMapId, "Warp spell")
                else if (!dest.isInBounds(s.warpX, s.warpY)) error(col, s.id, "/warpX", "out_of_bounds", "Warp target outside map ${dest.id}")
            }
            if ((s.targetType == SpellTargetType.AOE_SELF_CENTERED || s.targetType == SpellTargetType.AOE_TARGET_CENTERED || s.targetType == SpellTargetType.GROUND_AOE) && s.aoeRadiusTiles < 1) {
                warn(col, s.id, "/aoeRadiusTiles", "suspicious_value", "AoE spell has radius ${s.aoeRadiusTiles}")
            }
            if (s.groupTarget && s.targetType != SpellTargetType.SELF) error(col, s.id, "/groupTarget", "invalid_value", "groupTarget only works on SELF spells")
            if (s.cleanses && s.targetType != SpellTargetType.SELF) error(col, s.id, "/cleanses", "invalid_value", "cleanses only works on SELF spells")
            ref(col, s.id, "/animationId", ContentCollection.ANIMATIONS, s.animationId, s.animationId in cat.animations)
            s.classReqIds.forEach { ref(col, s.id, "/classReqIds", ContentCollection.CLASSES, it, it in cat.classes, optional = false) }
        }

        // ---- Shops ---------------------------------------------------------------------------
        fun shop(s: ShopDefinition) {
            val col = ContentCollection.SHOPS
            common(col, s.id, s.name, s.schemaVersion)
            if (s.buyRatePercent < 0 || s.sellRatePercent < 0) error(col, s.id, "/buyRatePercent", "out_of_range", "Rates must be >= 0")
            itemRef(col, s.id, "/currencyItemId", s.currencyItemId, optional = false)
            s.entries.forEachIndexed { i, e ->
                itemRef(col, s.id, "/entries/$i/itemId", e.itemId, optional = false)
                itemRef(col, s.id, "/entries/$i/currencyItemId", e.currencyItemId, optional = false)
                if (e.amount < 1) error(col, s.id, "/entries/$i/amount", "out_of_range", "amount must be >= 1")
            }
        }

        // ---- Resources -----------------------------------------------------------------------
        fun resource(r: ResourceDefinition) {
            val col = ContentCollection.RESOURCES
            common(col, r.id, r.name, r.schemaVersion)
            itemRef(col, r.id, "/rewardItemId", r.rewardItemId, optional = false)
            if (r.minYield < 1 || r.maxYield < r.minYield) error(col, r.id, "/maxYield", "out_of_range", "Require 1 <= minYield <= maxYield")
            if (r.maxHealth < 1) error(col, r.id, "/maxHealth", "out_of_range", "maxHealth must be >= 1")
            ref(col, r.id, "/animationId", ContentCollection.ANIMATIONS, r.animationId, r.animationId in cat.animations)
        }

        // ---- Recipes -------------------------------------------------------------------------
        fun recipe(r: RecipeDefinition) {
            val col = ContentCollection.RECIPES
            common(col, r.id, r.name, r.schemaVersion)
            itemRef(col, r.id, "/outputItemId", r.outputItemId, optional = false)
            if (r.outputAmount < 1) error(col, r.id, "/outputAmount", "out_of_range", "outputAmount must be >= 1")
            if (r.ingredients.isEmpty()) warn(col, r.id, "/ingredients", "empty_list", "Recipe has no ingredients")
            r.ingredients.forEachIndexed { i, ing ->
                itemRef(col, r.id, "/ingredients/$i/itemId", ing.itemId, optional = false)
                if (ing.amount < 1) error(col, r.id, "/ingredients/$i/amount", "out_of_range", "amount must be >= 1")
            }
        }

        // ---- Quests --------------------------------------------------------------------------
        fun quest(q: QuestDefinition) {
            val col = ContentCollection.QUESTS
            common(col, q.id, q.name, q.schemaVersion)
            if (q.objectives.isEmpty()) warn(col, q.id, "/objectives", "empty_list", "Quest has no objectives")
            if (q.cooldownTicks < 0L) error(col, q.id, "/cooldownTicks", "out_of_range", "cooldownTicks must be >= 0")
            if (q.cooldownTicks > 0L && !q.repeatable) error(col, q.id, "/cooldownTicks", "needs_repeatable", "cooldownTicks only applies to a repeatable quest")
            q.prerequisiteQuestIds.forEach {
                if (it == q.id) error(col, q.id, "/prerequisiteQuestIds", "self_reference", "Quest cannot require itself")
                else ref(col, q.id, "/prerequisiteQuestIds", ContentCollection.QUESTS, it, it in cat.quests, optional = false)
            }
            q.objectives.forEachIndexed { i, o ->
                val p = "/objectives/$i/targetId"
                if (o.requiredCount < 1) error(col, q.id, "/objectives/$i/requiredCount", "out_of_range", "requiredCount must be >= 1")
                when (o.type) {
                    QuestObjectiveType.KILL_NPC, QuestObjectiveType.TALK_TO_NPC ->
                        ref(col, q.id, p, ContentCollection.NPCS, o.targetId, o.targetId in cat.npcs, optional = false)
                    QuestObjectiveType.COLLECT_ITEM -> itemRef(col, q.id, p, o.targetId, optional = false)
                    QuestObjectiveType.HARVEST_RESOURCE ->
                        ref(col, q.id, p, ContentCollection.RESOURCES, o.targetId, o.targetId in cat.resources, optional = false)
                    QuestObjectiveType.CRAFT_RECIPE ->
                        ref(col, q.id, p, ContentCollection.RECIPES, o.targetId, o.targetId in cat.recipes, optional = false)
                    QuestObjectiveType.VISIT_MAP ->
                        ref(col, q.id, p, ContentCollection.MAPS, o.targetId, o.targetId in cat.maps, optional = false)
                }
            }
            q.reward.items.forEachIndexed { i, ing -> itemRef(col, q.id, "/reward/items/$i/itemId", ing.itemId, optional = false) }
            ref(col, q.id, "/reward/teachSpellId", ContentCollection.SPELLS, q.reward.teachSpellId, q.reward.teachSpellId in cat.spells)
        }

        // ---- Events --------------------------------------------------------------------------
        fun event(e: EventDefinition) {
            val col = ContentCollection.EVENTS
            common(col, e.id, e.name, e.schemaVersion)
            if (e.mapId != 0) {
                val m = cat.maps[e.mapId]
                if (m == null) error(col, e.id, "/mapId", "missing_reference", "References maps id ${e.mapId} which does not exist")
                else if (!m.isInBounds(e.tileX, e.tileY)) error(col, e.id, "/tileX", "out_of_bounds", "Event tile (${e.tileX},${e.tileY}) is outside map ${m.id}")
            }
            if (e.pages.isEmpty()) error(col, e.id, "/pages", "empty_list", "An event needs at least one page")
            e.pages.forEachIndexed { i, page ->
                val p = "/pages/$i"
                val cond = page.condition
                ref(col, e.id, "$p/condition/requiredQuestId", ContentCollection.QUESTS, cond.requiredQuestId, cond.requiredQuestId in cat.quests)
                itemRef(col, e.id, "$p/condition/requiredItemId", cond.requiredItemId)
                commands(col, e.id, "$p/commands", page.commands)
            }
        }

        fun commonEvent(ce: CommonEventDefinition) {
            val col = ContentCollection.COMMON_EVENTS
            common(col, ce.id, ce.name, ce.schemaVersion)
            if (ce.trigger == CommonEventTrigger.ON_TIMER && ce.intervalTicks !in 1..72000) {
                error(col, ce.id, "/intervalTicks", "out_of_range", "Timer interval must be between 1 and 72000 ticks")
            }
            commands(col, ce.id, "/commands", ce.commands)
        }

        private fun validateEventCondition(col: ContentCollection, id: Int, ptr: String, c: EventCondition) {
            when (c.type) {
                EventConditionType.SWITCH, EventConditionType.VARIABLE ->
                    if (c.key.isBlank()) error(col, id, "$ptr/key", "missing_required", "An if step checking a switch or number needs its name")
                EventConditionType.HAS_ITEM -> itemRef(col, id, "$ptr/targetId", c.targetId, optional = false)
                EventConditionType.QUEST ->
                    ref(col, id, "$ptr/targetId", ContentCollection.QUESTS, c.targetId, c.targetId in cat.quests, optional = false)
                EventConditionType.CLASS ->
                    ref(col, id, "$ptr/targetId", ContentCollection.CLASSES, c.targetId, c.targetId in cat.classes, optional = false)
                EventConditionType.KNOWS_SPELL ->
                    ref(col, id, "$ptr/targetId", ContentCollection.SPELLS, c.targetId, c.targetId in cat.spells, optional = false)
                EventConditionType.CHANCE ->
                    if (c.value !in 0..100) error(col, id, "$ptr/value", "out_of_range", "A chance must be between 0 and 100")
                EventConditionType.LEVEL, EventConditionType.GUILD_HOLDS_AREA -> {}
            }
        }

        private fun commands(col: ContentCollection, id: Int, pointer: String, list: List<EventCommand>, depth: Int = 0) {
            if (depth > MAX_EVENT_NESTING) {
                if (list.isNotEmpty()) error(col, id, pointer, "too_deep", "Steps are nested more than $MAX_EVENT_NESTING levels deep")
                return
            }
            list.forEachIndexed { i, cmd ->
                val p = "$pointer/$i"
                when (cmd) {
                    is EventCommand.Conditional -> {
                        validateEventCondition(col, id, "$p/condition", cmd.condition)
                        cmd.extraConditions.forEachIndexed { ci, extra ->
                            validateEventCondition(col, id, "$p/extraConditions/$ci", extra)
                        }
                        commands(col, id, "$p/then", cmd.then, depth + 1)
                        commands(col, id, "$p/otherwise", cmd.otherwise, depth + 1)
                    }
                    is EventCommand.SetVariable ->
                        if (cmd.variableId.isBlank()) error(col, id, "$p/variableId", "missing_required", "set_variable needs the number's name")
                    is EventCommand.ShowMessage ->
                        if (cmd.text.isBlank()) error(col, id, "$p/text", "missing_required", "message needs some text")
                    is EventCommand.UpgradeGear -> {
                        if (cmd.amount < 1) error(col, id, "$p/amount", "out_of_range", "upgrade_gear needs an amount of at least 1")
                        if (cmd.successPercent !in 1..100) error(col, id, "$p/successPercent", "out_of_range", "successPercent must be 1 to 100")
                        itemRef(col, id, "$p/materialItemId", cmd.materialItemId, optional = cmd.materialItemId == 0 && (cmd.kind == GearUpgradeKind.SOCKET_GEM || cmd.kind == GearUpgradeKind.ENHANCE))
                    }
                    is EventCommand.Rebirth ->
                        if (cmd.classId != 0) ref(col, id, "$p/classId", ContentCollection.CLASSES, cmd.classId, cmd.classId in cat.classes, optional = false)
                    is EventCommand.RepairGear -> itemRef(col, id, "$p/currencyItemId", cmd.currencyItemId, optional = false)
                    is EventCommand.RespecAttributes -> {
                        if (cmd.pricePerPoint < 0) error(col, id, "$p/pricePerPoint", "out_of_range", "pricePerPoint must be 0 or more")
                        if (cmd.pricePerPoint > 0) itemRef(col, id, "$p/currencyItemId", cmd.currencyItemId, optional = false)
                    }
                    is EventCommand.ArenaQueue ->
                        if (cmd.size < 0) error(col, id, "$p/size", "out_of_range", "size must be 0 or more")
                    is EventCommand.TeachSpell ->
                        ref(col, id, "$p/spellId", ContentCollection.SPELLS, cmd.spellId, cmd.spellId in cat.spells, optional = false)
                    is EventCommand.GiveItem -> itemRef(col, id, "$p/itemId", cmd.itemId, optional = false)
                    is EventCommand.TakeItem -> itemRef(col, id, "$p/itemId", cmd.itemId, optional = false)
                    is EventCommand.StartQuest ->
                        ref(col, id, "$p/questId", ContentCollection.QUESTS, cmd.questId, cmd.questId in cat.quests, optional = false)
                    is EventCommand.CompleteQuest ->
                        ref(col, id, "$p/questId", ContentCollection.QUESTS, cmd.questId, cmd.questId in cat.quests, optional = false)
                    is EventCommand.OpenShop ->
                        ref(col, id, "$p/shopId", ContentCollection.SHOPS, cmd.shopId, cmd.shopId in cat.shops, optional = false)
                    is EventCommand.PlayAnimation ->
                        ref(col, id, "$p/animationId", ContentCollection.ANIMATIONS, cmd.animationId, cmd.animationId in cat.animations, optional = false)
                    is EventCommand.RunCommonEvent ->
                        ref(col, id, "$p/commonEventId", ContentCollection.COMMON_EVENTS, cmd.commonEventId, cmd.commonEventId in cat.commonEvents, optional = false)
                    is EventCommand.Wait ->
                        if (cmd.ticks !in 1..12000) error(col, id, "$p/ticks", "out_of_range", "Wait ticks must be between 1 and 12000")
                    is EventCommand.PlaySound ->
                        if (cmd.soundId.isBlank()) error(col, id, "$p/soundId", "missing_required", "play_sound needs a sound id")
                    is EventCommand.ShowPicture -> {
                        if (cmd.pictureId.isBlank()) error(col, id, "$p/pictureId", "missing_required", "show_picture needs a picture id")
                        if (cmd.durationTicks !in 0..12000) error(col, id, "$p/durationTicks", "out_of_range", "Picture duration ticks must be between 0 and 12000")
                    }
                    is EventCommand.MoveNpc -> {
                        ref(col, id, "$p/npcId", ContentCollection.NPCS, cmd.npcId, cmd.npcId in cat.npcs, optional = false)
                        if (cmd.steps !in 1..20) error(col, id, "$p/steps", "out_of_range", "NPC move steps must be between 1 and 20")
                    }
                    is EventCommand.TextInput -> {
                        if (cmd.prompt.isBlank()) error(col, id, "$p/prompt", "missing_required", "text_input needs a prompt")
                        if (cmd.variableId.isBlank()) error(col, id, "$p/variableId", "missing_required", "text_input needs a variable name")
                        if (cmd.maxLength !in 1..200) error(col, id, "$p/maxLength", "out_of_range", "Text input max length must be between 1 and 200")
                    }
                    is EventCommand.WarpPlayer -> {
                        val dest = cat.maps[cmd.mapId]
                        if (dest == null) mapLink(col, id, "$p/mapId", cmd.mapId, "Warp command")
                        else if (!dest.isInBounds(cmd.tileX, cmd.tileY)) error(col, id, p, "out_of_bounds", "Warp target (${cmd.tileX},${cmd.tileY}) is outside map ${dest.id}")
                    }
                    is EventCommand.ShowDialogue ->
                        cmd.choices.forEachIndexed { ci, choice -> commands(col, id, "$p/choices/$ci/commands", choice.commands, depth + 1) }
                    is EventCommand.Label ->
                        if (cmd.name.isBlank()) error(col, id, "$p/name", "missing_required", "label needs a name")
                    is EventCommand.JumpToLabel ->
                        if (cmd.name.isBlank()) error(col, id, "$p/name", "missing_required", "jump_to_label needs a label name")
                    is EventCommand.Loop ->
                        commands(col, id, "$p/commands", cmd.commands, depth + 1)
                    EventCommand.BreakLoop -> {}
                    else -> {}
                }
            }
        }

        fun animation(a: AnimationDefinition) {
            val col = ContentCollection.ANIMATIONS
            common(col, a.id, a.name, a.schemaVersion)
            if (a.frameCount < 1) error(col, a.id, "/frameCount", "out_of_range", "frameCount must be >= 1")
            if (a.frameDurationMs < 1) error(col, a.id, "/frameDurationMs", "out_of_range", "frameDurationMs must be >= 1")
        }

        // ---- Action combat (rules in ActionCombatValidation) -----------------------------------
        private val actionCombat = ActionCombatValidation(cat, game, ::error, ::warn)

        fun actionSkill(s: ActionSkillDefinition) {
            common(ContentCollection.ACTION_SKILLS, s.id, s.name, s.schemaVersion)
            actionCombat.skill(s)
        }

        fun weaponSet(w: WeaponSetDefinition) {
            common(ContentCollection.WEAPON_SETS, w.id, w.name, w.schemaVersion)
            actionCombat.weaponSet(w)
        }
    }
}
