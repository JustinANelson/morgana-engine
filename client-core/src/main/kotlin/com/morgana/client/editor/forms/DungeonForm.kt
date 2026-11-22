package com.morgana.client.editor.forms

import com.morgana.client.editor.FieldRef
import com.morgana.domain.map.DifficultyTier
import com.morgana.domain.map.FloorAlgorithm
import com.morgana.domain.map.FloorGeneration
import com.morgana.domain.map.FloorGenerator
import com.morgana.domain.map.InstanceScaling
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.TilePlacement
import com.morgana.domain.map.WeightedLoot
import com.morgana.domain.map.WeightedSpawn
import com.morgana.domain.map.WeightedTile

/**
 * The dungeon half of a map as plain form data: the floor recipe (`generator`) and the party scaling with its difficulty tiers
 * (`scaling`). Values are text keyed by [FormField.key], like every [ContentForm], so the generic field view draws them and
 * every rule is unit-tested without graphics.
 *
 * Line formats (one entry per line):
 * - tiles: `tileset x y` (the tile's place on its tileset sheet); decorations add a weight: `tileset x y weight`
 * - monsters: `npc weight`
 * - loot: `item min-max weight` (`item amount weight` for a fixed amount)
 * - tiers: `hp% attack% xp% drops% minLevel name`
 */
object DungeonForm {

    fun sections(): List<FormSection> = listOf(
        FormSection("Dungeon floor", listOf(
            FormField("floor", "This map is a generated floor template", FormFieldType.BOOL, "false", hint = "Needs the Instanced box ticked in Map properties. The map's own tiles are ignored; each party gets a fresh floor."),
            FormField("width", "Floor width", FormFieldType.INT, "40", min = 10, max = 256),
            FormField("height", "Floor height", FormFieldType.INT, "30", min = 10, max = 256),
            FormField("algorithm", "Layout algorithm", FormFieldType.CHOICE, FloorAlgorithm.ROOMS_AND_CORRIDORS.name, FloorAlgorithm.entries.map { it.name }),
            FormField("corridorWidth", "Corridor width (tiles)", FormFieldType.INT, "1", min = 1, max = 3, hint = "2 or 3 gives co-op parties room to pass."),
            FormField("caveFillPercent", "Cave wall density (%)", FormFieldType.INT, "45", min = 25, max = 65, hint = "Used only by Cellular caves."),
            FormField("roomsMin", "Fewest rooms", FormFieldType.INT, "5", min = 1, max = FloorGeneration.MAX_ROOMS.toLong()),
            FormField("roomsMax", "Most rooms", FormFieldType.INT, "8", min = 1, max = FloorGeneration.MAX_ROOMS.toLong()),
            FormField("roomSizeMin", "Smallest room side", FormFieldType.INT, "4", min = 3, max = 30),
            FormField("roomSizeMax", "Largest room side", FormFieldType.INT, "8", min = 3, max = 30),
            FormField("fixedSeed", "Fixed seed", FormFieldType.LONG, "0", hint = "0 gives a new layout every run; any other number always gives the same floor.")
        )),
        FormSection("Floor look", listOf(
            FormField("floorTile", "Floor tile", FormFieldType.TEXT, "1 0 0", hint = "tileset x y: the tile's column and row on its tileset sheet."),
            FormField("wallTile", "Wall tile", FormFieldType.TEXT, "1 1 0"),
            FormField("wallFaceTile", "Wall face tile (optional)", FormFieldType.TEXT, hint = "Drawn on a wall with floor below it. Leave empty to use the wall tile everywhere."),
            FormField("stairsTile", "Stairs tile", FormFieldType.TEXT, "1 2 0"),
            FormField("decorations", "Decorations", FormFieldType.LINES, hint = "One per line: tileset x y weight. Scattered on the floor, passable."),
            FormField("decorationChanceBp", "Decoration chance (out of 10000)", FormFieldType.INT, "500", min = 0, max = 10_000)
        )),
        FormSection("Monsters and loot", listOf(
            FormField("monsters", "Monsters", FormFieldType.LINES, hint = "One per line: npc weight. Example: 11 3"),
            FormField("perRoomMin", "Fewest monsters per room", FormFieldType.INT, "0", min = 0, max = 20),
            FormField("perRoomMax", "Most monsters per room", FormFieldType.INT, "2", min = 0, max = 20),
            FormField("loot", "Loot piles", FormFieldType.LINES, hint = "One per line: item min-max weight. Example: 1 5-12 6"),
            FormField("lootMin", "Fewest piles on the floor", FormFieldType.INT, "1", min = 0, max = 100),
            FormField("lootMax", "Most piles on the floor", FormFieldType.INT, "3", min = 0, max = 100),
            FormField("itemLevel", "Item level of gear lying here", FormFieldType.INT, "1", min = 1, max = 1000),
            FormField("features", "Room features (shrines, merchants)", FormFieldType.LINES, hint = "One per line: npc weight. An NPC with a dialogue event placed once in a room after the first, such as a healing shrine."),
            FormField("featuresMin", "Fewest features on the floor", FormFieldType.INT, "0", min = 0, max = 20),
            FormField("featuresMax", "Most features on the floor", FormFieldType.INT, "1", min = 0, max = 20)
        )),
        FormSection("Boss and way out", listOf(
            FormField("boss", "Boss beside the stairs", FormFieldType.INT, "0", min = 0, ref = FieldRef.NPC, hint = "0 means none."),
            FormField("next", "Next floor (template map)", FormFieldType.INT, "0", min = 0, ref = FieldRef.MAP, hint = "0 means this is the last floor, which needs an exit."),
            FormField("exitMap", "Exit map (last floor)", FormFieldType.INT, "0", min = 0, ref = FieldRef.MAP),
            FormField("exitX", "Exit tile X", FormFieldType.INT, "0", min = 0),
            FormField("exitY", "Exit tile Y", FormFieldType.INT, "0", min = 0)
        )),
        FormSection("Party scaling and difficulty", listOf(
            FormField("scaling", "Scale monsters to the party", FormFieldType.BOOL, "false"),
            FormField("hpPerMember", "Life per extra member (%)", FormFieldType.INT, "60", min = 0, max = 1000),
            FormField("attackPerMember", "Damage per extra member (%)", FormFieldType.INT, "15", min = 0, max = 1000),
            FormField("xpPerMember", "XP per extra member (%)", FormFieldType.INT, "0", min = 0, max = 1000),
            FormField("maxMembers", "Members counted at most", FormFieldType.INT, "5", min = 1, max = 50),
            FormField("levelFollows", "Follow the party's average level", FormFieldType.BOOL, "false"),
            FormField("percentPerLevel", "Life and damage per level above the monster (%)", FormFieldType.INT, "4", min = 0, max = 100),
            FormField("maxLevelShift", "Level difference counted at most", FormFieldType.INT, "20", min = 0, max = 200),
            FormField("tierVariable", "Player variable that picks the tier", FormFieldType.TEXT, "dungeon_tier", hint = "An NPC's dialogue sets it (Set Variable, player scope) before the warp."),
            FormField("tiers", "Difficulty tiers", FormFieldType.LINES, hint = "Easiest first, one per line: life% damage% xp% drops% minLevel name. Example: 150 125 150 130 6 Hard")
        ))
    )

    fun valuesOf(map: MapDefinition): Map<String, String> {
        val g = map.generator ?: FloorGenerator()
        val s = map.scaling ?: InstanceScaling()
        return mapOf(
            "floor" to (map.generator != null).toString(),
            "width" to g.width.toString(), "height" to g.height.toString(),
            "algorithm" to g.algorithm.name, "corridorWidth" to g.corridorWidth.toString(), "caveFillPercent" to g.caveFillPercent.toString(),
            "roomsMin" to g.roomsMin.toString(), "roomsMax" to g.roomsMax.toString(),
            "roomSizeMin" to g.roomSizeMin.toString(), "roomSizeMax" to g.roomSizeMax.toString(),
            "fixedSeed" to g.fixedSeed.toString(),
            "floorTile" to formatTile(g.floorTile), "wallTile" to formatTile(g.wallTile),
            "wallFaceTile" to (g.wallFaceTile?.let { formatTile(it) } ?: ""), "stairsTile" to formatTile(g.stairsTile),
            "decorations" to g.decorations.joinToString("\n") { formatTile(it.tile) + " " + it.weight },
            "decorationChanceBp" to g.decorationChanceBp.toString(),
            "monsters" to g.monsters.joinToString("\n") { "${it.npcId} ${it.weight}" },
            "features" to g.features.joinToString("\n") { "${it.npcId} ${it.weight}" },
            "featuresMin" to g.featuresMin.toString(), "featuresMax" to g.featuresMax.toString(),
            "perRoomMin" to g.monstersPerRoomMin.toString(), "perRoomMax" to g.monstersPerRoomMax.toString(),
            "loot" to g.loot.joinToString("\n") { "${it.itemId} ${if (it.minAmount == it.maxAmount) it.minAmount.toString() else "${it.minAmount}-${it.maxAmount}"} ${it.weight}" },
            "lootMin" to g.lootMin.toString(), "lootMax" to g.lootMax.toString(), "itemLevel" to g.itemLevel.toString(),
            "boss" to g.bossNpcId.toString(), "next" to g.nextMapId.toString(),
            "exitMap" to g.exitMapId.toString(), "exitX" to g.exitX.toString(), "exitY" to g.exitY.toString(),
            "scaling" to (map.scaling != null).toString(),
            "hpPerMember" to s.hpPercentPerMember.toString(), "attackPerMember" to s.attackPercentPerMember.toString(),
            "xpPerMember" to s.xpPercentPerMember.toString(), "maxMembers" to s.maxMembers.toString(),
            "levelFollows" to s.levelFollowsMembers.toString(), "percentPerLevel" to s.percentPerLevel.toString(),
            "maxLevelShift" to s.maxLevelShift.toString(), "tierVariable" to s.tierVariable,
            "tiers" to s.tiers.joinToString("\n") { "${it.hpPercent} ${it.attackPercent} ${it.xpPercent} ${it.dropRatePercent} ${it.minLevel} ${it.name}" }
        )
    }

    /** What is wrong with [values] on map [mapId]. [catalogMapIds], [npcIds] and [itemIds] are empty while the catalog is not loaded. */
    fun problems(values: Map<String, String>, instanced: Boolean, catalogMapIds: Set<Int> = emptySet(), npcIds: Set<Int> = emptySet(), itemIds: Set<Int> = emptySet()): List<String> {
        val out = ArrayList<String>()
        out += FormRules.fieldProblems(sections().flatMap { it.fields }.filter { it.type != FormFieldType.LINES }, values)
        val parsed = parse(values)
        out += parsed.problems
        if (values["floor"] == "true") {
            if (!instanced) out += "A floor template must be Instanced (tick it in Map properties)"
            parsed.generator?.let { g -> out += FloorGeneration.problems(g).map { it.second } }
            for (m in parsed.generator?.monsters.orEmpty()) if (npcIds.isNotEmpty() && m.npcId !in npcIds) out += "Monster ${m.npcId} does not exist"
            for (f in parsed.generator?.features.orEmpty()) if (npcIds.isNotEmpty() && f.npcId !in npcIds) out += "Feature ${f.npcId} does not exist"
            for (l in parsed.generator?.loot.orEmpty()) if (itemIds.isNotEmpty() && l.itemId !in itemIds) out += "Loot item ${l.itemId} does not exist"
            val g = parsed.generator
            if (g != null) {
                if (g.bossNpcId != 0 && npcIds.isNotEmpty() && g.bossNpcId !in npcIds) out += "Boss ${g.bossNpcId} does not exist"
                if (g.nextMapId != 0 && catalogMapIds.isNotEmpty() && g.nextMapId !in catalogMapIds) out += "Next floor map ${g.nextMapId} does not exist"
                if (g.exitMapId != 0 && catalogMapIds.isNotEmpty() && g.exitMapId !in catalogMapIds) out += "Exit map ${g.exitMapId} does not exist"
            }
        }
        return out.distinct()
    }

    /** Applies [values] to [map]: the recipe and scaling follow their tick boxes, and an unticked box removes that section. */
    fun apply(map: MapDefinition, values: Map<String, String>): MapDefinition {
        val parsed = parse(values)
        val old = map.generator
        fun keep(new: TilePlacement, was: TilePlacement?) = if (was != null && was.tilesetId == new.tilesetId && was.srcTileX == new.srcTileX && was.srcTileY == new.srcTileY) was else new
        val generator = if (values["floor"] == "true") parsed.generator?.let { g ->
            // Tiles typed as before keep their autotile and animation settings.
            g.copy(
                floorTile = keep(g.floorTile, old?.floorTile), wallTile = keep(g.wallTile, old?.wallTile),
                wallFaceTile = g.wallFaceTile?.let { keep(it, old?.wallFaceTile) }, stairsTile = keep(g.stairsTile, old?.stairsTile),
                decorations = g.decorations.mapIndexed { i, d -> d.copy(tile = keep(d.tile, old?.decorations?.getOrNull(i)?.tile)) }
            )
        } ?: map.generator else null
        val scaling = if (values["scaling"] == "true") parsed.scaling ?: map.scaling else null
        return map.copy(generator = generator, scaling = scaling)
    }

    class Parsed(val generator: FloorGenerator?, val scaling: InstanceScaling?, val problems: List<String>)

    fun parse(values: Map<String, String>): Parsed {
        val problems = ArrayList<String>()
        fun int(key: String, fallback: Int) = values[key]?.trim()?.toIntOrNull() ?: fallback

        val floorTile = parseTile(values["floorTile"].orEmpty(), "Floor tile", problems)
        val wallTile = parseTile(values["wallTile"].orEmpty(), "Wall tile", problems)
        val stairsTile = parseTile(values["stairsTile"].orEmpty(), "Stairs tile", problems)
        val face = values["wallFaceTile"].orEmpty().trim().takeIf { it.isNotEmpty() }?.let { parseTile(it, "Wall face tile", problems) }
        val decorations = lines(values["decorations"]).mapIndexedNotNull { i, line ->
            val parts = line.split(Regex("\\s+"))
            val w = parts.getOrNull(3)?.toIntOrNull()
            val tile = if (parts.size == 4) parseTile(parts.take(3).joinToString(" "), "Decoration ${i + 1}", problems) else null
            if (parts.size != 4 || tile == null || w == null || w < 1) { if (parts.size != 4 || w == null || w < 1) problems += "Decoration ${i + 1}: write 'tileset x y weight' (weight at least 1)"; null } else WeightedTile(tile, w)
        }
        val monsters = lines(values["monsters"]).mapIndexedNotNull { i, line ->
            val parts = line.split(Regex("\\s+"))
            val npc = parts.getOrNull(0)?.toIntOrNull(); val w = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || npc == null || npc < 1 || w == null || w < 1) { problems += "Monster ${i + 1}: write 'npc weight', both positive whole numbers"; null } else WeightedSpawn(npc, w)
        }
        val features = lines(values["features"]).mapIndexedNotNull { i, line ->
            val parts = line.split(Regex("\\s+"))
            val npc = parts.getOrNull(0)?.toIntOrNull(); val w = parts.getOrNull(1)?.toIntOrNull()
            if (parts.size != 2 || npc == null || npc < 1 || w == null || w < 1) { problems += "Feature ${i + 1}: write 'npc weight', both positive whole numbers"; null } else WeightedSpawn(npc, w)
        }
        val loot = lines(values["loot"]).mapIndexedNotNull { i, line ->
            val parts = line.split(Regex("\\s+"))
            val item = parts.getOrNull(0)?.toIntOrNull()
            val range = parts.getOrNull(1)?.split('-').orEmpty()
            val min = range.getOrNull(0)?.toIntOrNull(); val max = if (range.size == 2) range[1].toIntOrNull() else min
            val w = parts.getOrNull(2)?.toIntOrNull()
            if (parts.size != 3 || item == null || item < 1 || range.size > 2 || min == null || max == null || min < 1 || max < min || w == null || w < 1) {
                problems += "Loot ${i + 1}: write 'item min-max weight', for example '1 5-12 6'"; null
            } else WeightedLoot(item, min, max, w)
        }
        val tiers = lines(values["tiers"]).mapIndexedNotNull { i, line ->
            val parts = line.split(Regex("\\s+"), limit = 6)
            val n = (0..4).map { parts.getOrNull(it)?.toIntOrNull() }
            val name = parts.getOrNull(5).orEmpty()
            if (parts.size < 6 || n.any { it == null || it < 1 } || name.isBlank()) { problems += "Tier ${i + 1}: write 'life% damage% xp% drops% minLevel name', for example '150 125 150 130 6 Hard'"; null }
            else DifficultyTier(name, n[0]!!, n[1]!!, n[2]!!, n[3]!!, n[4]!!)
        }
        val generator = if (floorTile != null && wallTile != null && stairsTile != null) FloorGenerator(
            width = int("width", 40), height = int("height", 30),
            algorithm = FloorAlgorithm.entries.firstOrNull { it.name == values["algorithm"] } ?: FloorAlgorithm.ROOMS_AND_CORRIDORS,
            corridorWidth = int("corridorWidth", 1), caveFillPercent = int("caveFillPercent", 45),
            roomsMin = int("roomsMin", 5), roomsMax = int("roomsMax", 8),
            roomSizeMin = int("roomSizeMin", 4), roomSizeMax = int("roomSizeMax", 8), floorTile = floorTile, wallTile = wallTile,
            wallFaceTile = face, decorations = decorations, decorationChanceBp = int("decorationChanceBp", 500), stairsTile = stairsTile,
            monsters = monsters, features = features, featuresMin = int("featuresMin", 0), featuresMax = int("featuresMax", 1), monstersPerRoomMin = int("perRoomMin", 0), monstersPerRoomMax = int("perRoomMax", 2), loot = loot,
            lootMin = int("lootMin", 1), lootMax = int("lootMax", 3), itemLevel = int("itemLevel", 1), bossNpcId = int("boss", 0),
            nextMapId = int("next", 0), exitMapId = int("exitMap", 0), exitX = int("exitX", 0), exitY = int("exitY", 0),
            fixedSeed = values["fixedSeed"]?.trim()?.toLongOrNull() ?: 0L
        ) else null
        val scaling = InstanceScaling(
            hpPercentPerMember = int("hpPerMember", 60), attackPercentPerMember = int("attackPerMember", 15),
            xpPercentPerMember = int("xpPerMember", 0), maxMembers = int("maxMembers", 5),
            levelFollowsMembers = values["levelFollows"] == "true", percentPerLevel = int("percentPerLevel", 4),
            maxLevelShift = int("maxLevelShift", 20), tiers = tiers, tierVariable = values["tierVariable"]?.trim().orEmpty().ifEmpty { "dungeon_tier" }
        )
        return Parsed(generator, scaling, problems)
    }

    private fun lines(text: String?) = text.orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun formatTile(t: TilePlacement) = "${t.tilesetId} ${t.srcTileX} ${t.srcTileY}"

    /** `tileset x y`; keeps nothing else from an older placement (autotile or animation settings are not editable here). */
    private fun parseTile(text: String, label: String, problems: MutableList<String>): TilePlacement? {
        val parts = text.trim().split(Regex("\\s+")).map { it.toIntOrNull() }
        if (parts.size != 3 || parts.any { it == null || it < 0 } || parts[0]!! < 1) {
            problems += "$label: write 'tileset x y' as whole numbers (tileset 1 or more)"
            return null
        }
        return TilePlacement(tilesetId = parts[0]!!, srcTileX = parts[1]!!, srcTileY = parts[2]!!)
    }
}
