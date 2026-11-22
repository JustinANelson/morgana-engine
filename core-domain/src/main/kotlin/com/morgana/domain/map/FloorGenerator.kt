package com.morgana.domain.map

import com.morgana.domain.item.LootRules
import com.morgana.domain.sim.SeededRandomSource
import kotlinx.serialization.Serializable

/**
 * A recipe for procedurally generated dungeon floors (roguelike runs). Set on an `instanced` map, it makes the map a
 * **floor template**: every private copy the map opens is built by [FloorGeneration.generate] from this recipe and a
 * seed, instead of copying the map's own tiles. The template's name, rules, music and lighting carry over.
 *
 * A floor is rooms joined by corridors. The player arrives in the first room; the room farthest from it holds the
 * **stairs**, a warp to [nextMapId] (the next floor's template, so the party moves on to a fresh, deeper floor), or on
 * the last floor ([nextMapId] = 0) the **exit**, a warp back to [exitMapId] at ([exitX], [exitY]). A [bossNpcId] stands
 * beside the stairs or the exit, so a middle floor can end in a mini-boss as well as the last one. Monsters placed by the
 * generator never respawn; loot lies on the floor.
 *
 * Authorable as JSON on the map (`"generator": { ... }`) and validated with the map ([FloorGeneration.problems]).
 */
/** Layout algorithm used by [FloorGeneration.generate]. */
@Serializable
enum class FloorAlgorithm {
    /** Classic rectangular rooms connected by L-shaped corridors (default). */
    ROOMS_AND_CORRIDORS,
    /** Organic caverns carved via seeded cellular automata with guaranteed flood-fill connectivity. */
    CELLULAR_CAVES,
    /** Binary-space-partitioned dungeon rooms connected by structured corridors. */
    BSP_DUNGEON,
    /** Open courtyard or wilderness arena with perimeter walls and seeded obstacle pillars. */
    OPEN_ARENA
}

@Serializable
data class FloorGenerator(
    val width: Int = 40,
    val height: Int = 30,
    /** Layout algorithm for this floor (default `ROOMS_AND_CORRIDORS` keeps legacy seed layouts identical). */
    val algorithm: FloorAlgorithm = FloorAlgorithm.ROOMS_AND_CORRIDORS,
    /** Corridor width in tiles (1..3); wider corridors prevent body-blocking in 2-4 player co-op. */
    val corridorWidth: Int = 1,
    /** Initial wall density percent (25..65) for `CELLULAR_CAVES`. */
    val caveFillPercent: Int = 45,
    val roomsMin: Int = 5,
    val roomsMax: Int = 8,
    /** Smallest and largest side of a room, in tiles (walls not included). */
    val roomSizeMin: Int = 4,
    val roomSizeMax: Int = 8,
    /** GROUND tile of rooms and corridors. */
    val floorTile: TilePlacement = TilePlacement(),
    /** GROUND tile of everything else; those cells are also blocked. */
    val wallTile: TilePlacement = TilePlacement(srcTileX = 1),
    /**
     * Optional GROUND tile for the visible face of a wall: a wall cell with floor directly below it (south) gets this
     * tile instead of [wallTile], so tilesets with a brick face and a dark wall top read as walls seen from the front.
     * Blocked like any wall. `null` (default): every wall cell uses [wallTile].
     */
    val wallFaceTile: TilePlacement? = null,
    /** MASK_1 tiles scattered over the floor (passable): rubble, bones, moss. */
    val decorations: List<WeightedTile> = emptyList(),
    /** Chance in basis points (10000 = every tile) that a floor tile gets a decoration. */
    val decorationChanceBp: Int = 500,
    /** MASK_1 tile drawn on the stairs or exit. */
    val stairsTile: TilePlacement = TilePlacement(srcTileX = 2),
    val monsters: List<WeightedSpawn> = emptyList(),
    /** Monsters in each room except the first (where the party arrives). */
    val monstersPerRoomMin: Int = 0,
    val monstersPerRoomMax: Int = 2,
    val loot: List<WeightedLoot> = emptyList(),
    /** Loot piles on the floor. */
    val lootMin: Int = 1,
    val lootMax: Int = 3,
    /** Item level of the gear lying on this floor, for games with `loot` rules (deeper floors, better rolls). */
    val itemLevel: Int = 1,
    /**
     * Room features: NPCs with a dialogue event (a healing shrine, a merchant, a cursed altar) placed once each in the rooms
     * after the arrival room, `featuresMin..featuresMax` of them per floor, weighted. They never respawn. Draws random numbers
     * only when this list is not empty, so recipes without features generate exactly the floors they always did.
     */
    val features: List<WeightedSpawn> = emptyList(),
    val featuresMin: Int = 0,
    val featuresMax: Int = 1,
    /** NPC placed beside the stairs (or the exit, on the last floor): a mini-boss or the final boss (0 = none). */
    val bossNpcId: Int = 0,
    /** The next floor's template map (instanced, with a generator), or 0 when this is the last floor. */
    val nextMapId: Int = 0,
    /** Where the last floor's exit leads (a normal map, usually the hub). */
    val exitMapId: Int = 0,
    val exitX: Int = 0,
    val exitY: Int = 0,
    /** 0: a new floor every run. Anything else: always the same floor (for testing, or a shared "daily" layout). */
    val fixedSeed: Long = 0L
)

@Serializable
data class WeightedTile(val tile: TilePlacement, val weight: Int = 1)

@Serializable
data class WeightedSpawn(val npcId: Int, val weight: Int = 1)

@Serializable
data class WeightedLoot(val itemId: Int, val minAmount: Int = 1, val maxAmount: Int = 1, val weight: Int = 1)

/** A loot pile placed by the generator. */
data class PlacedLoot(val x: Int, val y: Int, val itemId: Int, val amount: Int)

/** A rectangle of floor tiles (walls not included). */
data class FloorRoom(val x: Int, val y: Int, val width: Int, val height: Int) {
    val centerX: Int get() = x + width / 2
    val centerY: Int get() = y + height / 2

    fun contains(px: Int, py: Int): Boolean = px in x until x + width && py in y until y + height

    /** True when the two rooms, each grown by [margin] tiles, overlap. */
    fun overlaps(other: FloorRoom, margin: Int = 1): Boolean =
        x - margin < other.x + other.width && other.x - margin < x + width &&
            y - margin < other.y + other.height && other.y - margin < y + height
}

/**
 * One generated floor: the map (with id [mapId] as asked), where arriving players stand, where the stairs or exit are,
 * the rooms (first = arrival, [exitRoom] = stairs), and the loot to lay on the floor.
 */
data class GeneratedFloor(
    val map: MapDefinition,
    val entryX: Int,
    val entryY: Int,
    val stairsX: Int,
    val stairsY: Int,
    val rooms: List<FloorRoom>,
    val exitRoom: Int,
    val loot: List<PlacedLoot>
)

/**
 * The pure, seeded floor generator. The same recipe, template and seed always give the same floor, on any machine, so
 * runs are reproducible and a seed can be shared. Uses its own [SeededRandomSource], never the world's.
 */
object FloorGeneration {
    const val MAX_ROOMS = 40
    const val MIN_ROOM = 3
    const val MAX_ROOM = 30
    const val MAX_PER_ROOM = 20
    const val MAX_LOOT = 100

    /**
     * Builds a floor from [template]'s recipe. [mapId] is the id the floor gets; [seed] picks the layout (ignored when
     * the recipe has a [FloorGenerator.fixedSeed]). Requires [template] to have a generator.
     */
    fun generate(template: MapDefinition, mapId: Int, seed: Long): GeneratedFloor {
        val g = requireNotNull(template.generator) { "Map ${template.id} has no generator" }
        val rng = SeededRandomSource(mix(if (g.fixedSeed != 0L) g.fixedSeed else seed))
        val w = g.width
        val h = g.height
        val floor = BooleanArray(w * h)
        fun idx(x: Int, y: Int) = y * w + x

        // 1-2. Carve layout according to the chosen algorithm.
        val rooms = ArrayList<FloorRoom>()
        when (g.algorithm) {
            FloorAlgorithm.ROOMS_AND_CORRIDORS -> {
                val wanted = rng.nextIntInclusive(g.roomsMin, g.roomsMax)
                var attempts = 0
                while (rooms.size < wanted && attempts < wanted * 40) {
                    attempts++
                    val rw = rng.nextIntInclusive(g.roomSizeMin, g.roomSizeMax).coerceAtMost(w - 2)
                    val rh = rng.nextIntInclusive(g.roomSizeMin, g.roomSizeMax).coerceAtMost(h - 2)
                    val room = FloorRoom(rng.nextIntInclusive(1, w - rw - 1), rng.nextIntInclusive(1, h - rh - 1), rw, rh)
                    if (rooms.none { it.overlaps(room) }) rooms += room
                }
                if (rooms.isEmpty()) rooms += FloorRoom(1, 1, (w - 2).coerceAtMost(g.roomSizeMax), (h - 2).coerceAtMost(g.roomSizeMax))
                for (r in rooms) for (y in r.y until r.y + r.height) for (x in r.x until r.x + r.width) floor[idx(x, y)] = true

                for (i in 1 until rooms.size) {
                    val a = rooms[i - 1]
                    val b = rooms[i]
                    val horizontalFirst = rng.nextInt(2) == 0
                    val cornerX = if (horizontalFirst) b.centerX else a.centerX
                    val cornerY = if (horizontalFirst) a.centerY else b.centerY
                    carveLine(floor, w, h, a.centerX, a.centerY, cornerX, cornerY, g.corridorWidth)
                    carveLine(floor, w, h, cornerX, cornerY, b.centerX, b.centerY, g.corridorWidth)
                }
            }
            FloorAlgorithm.CELLULAR_CAVES -> {
                carveCellularCaves(floor, w, h, g.caveFillPercent, rng)
                rooms += findOpenZones(floor, w, h, g.roomsMin, g.roomsMax, rng)
            }
            FloorAlgorithm.BSP_DUNGEON -> {
                carveBspDungeon(floor, w, h, g, rng, rooms)
            }
            FloorAlgorithm.OPEN_ARENA -> {
                carveOpenArena(floor, w, h, g, rng, rooms)
            }
        }

        // 3. Arrival in the first room; stairs in the room farthest from it by walking distance.
        val start = rooms[0]
        val entryX = start.centerX
        val entryY = start.centerY
        val distance = walkDistances(floor, w, h, entryX, entryY)
        val exitRoom = if (rooms.size == 1) 0 else (1 until rooms.size).maxByOrNull { distance[idx(rooms[it].centerX, rooms[it].centerY)] } ?: 0
        val exitR = rooms[exitRoom]
        val stairsX = exitR.centerX
        val stairsY = if (exitRoom == 0 && exitR.height > 1) (exitR.centerY + 1).coerceAtMost(exitR.y + exitR.height - 1) else exitR.centerY

        val taken = HashSet<Int>()
        taken += idx(entryX, entryY)
        taken += idx(stairsX, stairsY)

        // 4. Monsters, then the boss beside the stairs or exit. None of them respawn.
        val spawns = ArrayList<MapNpcSpawnSlot>()
        fun freeTileIn(room: FloorRoom): Pair<Int, Int>? {
            repeat(12) {
                val x = rng.nextIntInclusive(room.x, room.x + room.width - 1)
                val y = rng.nextIntInclusive(room.y, room.y + room.height - 1)
                if (floor[idx(x, y)] && taken.add(idx(x, y))) return x to y
            }
            return null
        }
        if (g.monsters.isNotEmpty()) {
            for ((i, room) in rooms.withIndex()) {
                if (i == 0) continue
                repeat(rng.nextIntInclusive(g.monstersPerRoomMin, g.monstersPerRoomMax)) {
                    val (x, y) = freeTileIn(room) ?: return@repeat
                    spawns += MapNpcSpawnSlot(spawns.size, pick(rng, g.monsters) { it.weight }.npcId, x, y, respawnTicks = 0)
                }
            }
        }
        if (g.bossNpcId != 0) {
            val beside = listOf(stairsX to stairsY - 1, stairsX - 1 to stairsY, stairsX + 1 to stairsY, stairsX to stairsY + 1)
                .firstOrNull { (x, y) -> x in 0 until w && y in 0 until h && floor[idx(x, y)] && idx(x, y) !in taken }
                ?: freeTileIn(exitR)
            if (beside != null) {
                taken += idx(beside.first, beside.second)
                spawns += MapNpcSpawnSlot(spawns.size, g.bossNpcId, beside.first, beside.second, respawnTicks = 0)
            }
        }

        // 5. Loot on the floor, anywhere but the arrival tile and the stairs.
        val loot = ArrayList<PlacedLoot>()
        if (g.loot.isNotEmpty()) {
            repeat(rng.nextIntInclusive(g.lootMin, g.lootMax)) {
                val room = rooms[rng.nextInt(rooms.size)]
                val (x, y) = freeTileIn(room) ?: return@repeat
                val entry = pick(rng, g.loot) { it.weight }
                loot += PlacedLoot(x, y, entry.itemId, rng.nextIntInclusive(entry.minAmount, entry.maxAmount))
            }
        }

        // 5b. Room features (shrines and the like): one NPC each, in a room that is not the arrival room.
        if (g.features.isNotEmpty() && rooms.size > 1) {
            repeat(rng.nextIntInclusive(g.featuresMin, g.featuresMax)) {
                val room = rooms[1 + rng.nextInt(rooms.size - 1)]
                val (x, y) = freeTileIn(room) ?: return@repeat
                spawns += MapNpcSpawnSlot(spawns.size, pick(rng, g.features) { it.weight }.npcId, x, y, respawnTicks = 0)
            }
        }

        // 6. Cells: walls are blocked, floor tiles may get a decoration, the stairs warp on.
        val stairsWarp = if (g.nextMapId != 0) TileAttribute.Warp(g.nextMapId, 0, 0) else TileAttribute.Warp(g.exitMapId, g.exitX, g.exitY)
        val cells = List(w * h) { i ->
            val x = i % w
            val y = i / w
            when {
                !floor[i] -> {
                    val face = g.wallFaceTile?.takeIf { y + 1 < h && floor[i + w] }
                    MapCell(layers = mapOf(MapLayer.GROUND to (face ?: g.wallTile)), attribute = TileAttribute.Blocked)
                }
                x == stairsX && y == stairsY -> MapCell(layers = mapOf(MapLayer.GROUND to g.floorTile, MapLayer.MASK_1 to g.stairsTile), attribute = stairsWarp)
                i !in taken && g.decorations.isNotEmpty() && rng.rollBasisPoints(g.decorationChanceBp) ->
                    MapCell(layers = mapOf(MapLayer.GROUND to g.floorTile, MapLayer.MASK_1 to pick(rng, g.decorations) { it.weight }.tile))
                else -> MapCell(layers = mapOf(MapLayer.GROUND to g.floorTile))
            }
        }
        val map = template.copy(
            id = mapId, width = w, height = h, cells = cells, npcSpawns = spawns, generator = null,
            neighbors = MapNeighborLinks(), isIndoors = true
        )
        return GeneratedFloor(map, entryX, entryY, stairsX, stairsY, rooms, exitRoom, loot)
    }

    /** What is wrong with a recipe on map [mapId], as (JSON pointer below the map, message) pairs. */
    fun problems(g: FloorGenerator): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun bad(field: String, msg: String) { out += "/generator/$field" to msg }
        if (g.width !in 10..256) bad("width", "width must be 10..256 (got ${g.width})")
        if (g.height !in 10..256) bad("height", "height must be 10..256 (got ${g.height})")
        if (g.corridorWidth !in 1..3) bad("corridorWidth", "corridorWidth must be 1..3 (got ${g.corridorWidth})")
        if (g.caveFillPercent !in 25..65) bad("caveFillPercent", "caveFillPercent must be 25..65 (got ${g.caveFillPercent})")
        if (g.roomsMin !in 1..MAX_ROOMS || g.roomsMax !in g.roomsMin..MAX_ROOMS) bad("roomsMin", "need 1 <= roomsMin <= roomsMax <= $MAX_ROOMS (got ${g.roomsMin}..${g.roomsMax})")
        if (g.roomSizeMin !in MIN_ROOM..MAX_ROOM || g.roomSizeMax !in g.roomSizeMin..MAX_ROOM) bad("roomSizeMin", "need $MIN_ROOM <= roomSizeMin <= roomSizeMax <= $MAX_ROOM (got ${g.roomSizeMin}..${g.roomSizeMax})")
        if (g.roomSizeMax > g.width - 2 || g.roomSizeMax > g.height - 2) bad("roomSizeMax", "rooms up to ${g.roomSizeMax} tiles do not fit a ${g.width}x${g.height} floor")
        if (g.decorationChanceBp !in 0..10_000) bad("decorationChanceBp", "decorationChanceBp must be 0..10000")
        if (g.monstersPerRoomMin < 0 || g.monstersPerRoomMax !in g.monstersPerRoomMin..MAX_PER_ROOM) bad("monstersPerRoomMin", "need 0 <= monstersPerRoomMin <= monstersPerRoomMax <= $MAX_PER_ROOM")
        if (g.lootMin < 0 || g.lootMax !in g.lootMin..MAX_LOOT) bad("lootMin", "need 0 <= lootMin <= lootMax <= $MAX_LOOT")
        if (g.itemLevel !in 1..LootRules.MAX_ITEM_LEVEL) bad("itemLevel", "itemLevel must be 1..${LootRules.MAX_ITEM_LEVEL} (got ${g.itemLevel})")
        g.decorations.forEachIndexed { i, d -> if (d.weight < 1) bad("decorations/$i/weight", "weight must be at least 1") }
        g.monsters.forEachIndexed { i, m -> if (m.weight < 1) bad("monsters/$i/weight", "weight must be at least 1") }
        g.features.forEachIndexed { i, f -> if (f.weight < 1) bad("features/$i/weight", "weight must be at least 1") }
        if (g.featuresMin < 0 || g.featuresMax !in g.featuresMin..MAX_PER_ROOM) bad("featuresMin", "need 0 <= featuresMin <= featuresMax <= $MAX_PER_ROOM")
        g.loot.forEachIndexed { i, l ->
            if (l.weight < 1) bad("loot/$i/weight", "weight must be at least 1")
            if (l.minAmount < 1 || l.maxAmount < l.minAmount) bad("loot/$i/minAmount", "need 1 <= minAmount <= maxAmount")
        }
        if (g.nextMapId == 0 && g.exitMapId == 0) bad("exitMapId", "the last floor (nextMapId 0) needs an exitMapId to send the party back to")
        return out
    }

    /** Spreads a seed's bits (SplitMix64 finaliser), so nearby seeds give unrelated floors. */
    fun mix(seed: Long): Long {
        var z = seed + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private fun carveLine(floor: BooleanArray, w: Int, h: Int, x0: Int, y0: Int, x1: Int, y1: Int, width: Int = 1) {
        val half = (width - 1) / 2
        val extra = width - 1 - half
        fun stamp(cx: Int, cy: Int) {
            for (dy in -half..extra) for (dx in -half..extra) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx in 1 until w - 1 && ny in 1 until h - 1) floor[ny * w + nx] = true
            }
        }
        var x = x0
        var y = y0
        if (width <= 1) floor[y * w + x] = true else stamp(x, y)
        while (x != x1 || y != y1) {
            if (x != x1) {
                x += if (x1 > x) 1 else -1
            } else {
                y += if (y1 > y) 1 else -1
            }
            if (width <= 1) floor[y * w + x] = true else stamp(x, y)
        }
    }

    /** Organic cave layout via cellular automata + largest connected component retention. */
    private fun carveCellularCaves(floor: BooleanArray, w: Int, h: Int, fillPercent: Int, rng: SeededRandomSource) {
        var grid = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            grid[y * w + x] = rng.nextInt(100) >= fillPercent
        }
        repeat(4) {
            val next = BooleanArray(w * h)
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                var walls = 0
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    if (!grid[(y + dy) * w + (x + dx)]) walls++
                }
                next[y * w + x] = when {
                    walls >= 5 -> false
                    walls <= 3 -> true
                    else -> grid[y * w + x]
                }
            }
            grid = next
        }
        // Keep only the largest 4-way connected component so every floor tile is mutually reachable.
        val visited = BooleanArray(w * h)
        var best = emptyList<Int>()
        val queue = IntArray(w * h)
        for (i in 0 until w * h) {
            if (!grid[i] || visited[i]) continue
            val comp = ArrayList<Int>()
            var head = 0
            var tail = 0
            visited[i] = true
            queue[tail++] = i
            while (head < tail) {
                val cur = queue[head++]
                comp += cur
                val cx = cur % w
                val cy = cur / w
                for ((dx, dy) in NEIGHBOURS) {
                    val nx = cx + dx
                    val ny = cy + dy
                    if (nx !in 1 until w - 1 || ny !in 1 until h - 1) continue
                    val n = ny * w + nx
                    if (grid[n] && !visited[n]) {
                        visited[n] = true
                        queue[tail++] = n
                    }
                }
            }
            if (comp.size > best.size) best = comp
        }
        if (best.size < 9) {
            // Fallback open chamber if parameters produced an overly dense cave
            for (y in 2 until h - 2) for (x in 2 until w - 2) floor[y * w + x] = true
        } else {
            for (i in best) floor[i] = true
        }
    }

    /** Picks well-spaced walkable zones on an open cave/arena floor so entry, stairs, monsters and loot work uniformly. */
    private fun findOpenZones(floor: BooleanArray, w: Int, h: Int, minZones: Int, maxZones: Int, rng: SeededRandomSource): List<FloorRoom> {
        val walkable = ArrayList<Int>()
        for (y in 1 until h - 1) for (x in 1 until w - 1) if (floor[y * w + x]) walkable += y * w + x
        val target = rng.nextIntInclusive(minZones, maxZones).coerceAtLeast(2)
        val zones = ArrayList<FloorRoom>()
        var attempts = 0
        while (zones.size < target && attempts < target * 40 && walkable.isNotEmpty()) {
            attempts++
            val pickIdx = walkable[rng.nextInt(walkable.size)]
            val cx = pickIdx % w
            val cy = pickIdx / w
            val cand = FloorRoom((cx - 1).coerceAtLeast(1), (cy - 1).coerceAtLeast(1), 3.coerceAtMost(w - 2), 3.coerceAtMost(h - 2))
            if (floor[cand.centerY * w + cand.centerX] && zones.none { it.overlaps(cand, margin = 2) }) {
                zones += cand
            }
        }
        if (zones.isEmpty() && walkable.isNotEmpty()) {
            val first = walkable.first()
            zones += FloorRoom(first % w, first / w, 1, 1)
        }
        return zones
    }

    /** Binary space partition dungeon: clean non-overlapping rooms inside split sectors connected by corridors. */
    private fun carveBspDungeon(floor: BooleanArray, w: Int, h: Int, g: FloorGenerator, rng: SeededRandomSource, rooms: MutableList<FloorRoom>) {
        data class Sector(val x: Int, val y: Int, val w: Int, val h: Int)
        val sectors = ArrayList<Sector>()
        sectors += Sector(1, 1, w - 2, h - 2)
        val wanted = rng.nextIntInclusive(g.roomsMin, g.roomsMax)
        val minLeaf = (g.roomSizeMin + 2).coerceAtLeast(5)
        var splits = 0
        while (sectors.size < wanted && splits < wanted * 4) {
            splits++
            val idx = sectors.indices.maxByOrNull { sectors[it].w * sectors[it].h } ?: break
            val s = sectors[idx]
            val canSplitH = s.h >= minLeaf * 2
            val canSplitV = s.w >= minLeaf * 2
            if (!canSplitH && !canSplitV) break
            val splitV = when {
                canSplitV && !canSplitH -> true
                canSplitH && !canSplitV -> false
                else -> s.w >= s.h
            }
            sectors.removeAt(idx)
            if (splitV) {
                val cut = rng.nextIntInclusive(minLeaf, s.w - minLeaf)
                sectors += Sector(s.x, s.y, cut, s.h)
                sectors += Sector(s.x + cut, s.y, s.w - cut, s.h)
            } else {
                val cut = rng.nextIntInclusive(minLeaf, s.h - minLeaf)
                sectors += Sector(s.x, s.y, s.w, cut)
                sectors += Sector(s.x, s.y + cut, s.w, s.h - cut)
            }
        }
        for (s in sectors) {
            val maxW = (s.w - 1).coerceIn(1, g.roomSizeMax)
            val maxH = (s.h - 1).coerceIn(1, g.roomSizeMax)
            val rw = rng.nextIntInclusive(g.roomSizeMin.coerceAtMost(maxW), maxW)
            val rh = rng.nextIntInclusive(g.roomSizeMin.coerceAtMost(maxH), maxH)
            val rx = s.x + rng.nextInt((s.w - rw).coerceAtLeast(1))
            val ry = s.y + rng.nextInt((s.h - rh).coerceAtLeast(1))
            val room = FloorRoom(rx.coerceIn(1, w - rw - 1), ry.coerceIn(1, h - rh - 1), rw, rh)
            rooms += room
            for (y in room.y until room.y + room.height) for (x in room.x until room.x + room.width) floor[y * w + x] = true
        }
        for (i in 1 until rooms.size) {
            val a = rooms[i - 1]
            val b = rooms[i]
            carveLine(floor, w, h, a.centerX, a.centerY, b.centerX, a.centerY, g.corridorWidth)
            carveLine(floor, w, h, b.centerX, a.centerY, b.centerX, b.centerY, g.corridorWidth)
        }
    }

    /** Open courtyard/arena with perimeter walls and scattered 2x2 pillar clusters leaving full walkability. */
    private fun carveOpenArena(floor: BooleanArray, w: Int, h: Int, g: FloorGenerator, rng: SeededRandomSource, rooms: MutableList<FloorRoom>) {
        for (y in 1 until h - 1) for (x in 1 until w - 1) floor[y * w + x] = true
        val pillars = ((w * h) / 140).coerceIn(2, 16)
        repeat(pillars) {
            val px = rng.nextIntInclusive(3, (w - 5).coerceAtLeast(3))
            val py = rng.nextIntInclusive(3, (h - 5).coerceAtLeast(3))
            floor[py * w + px] = false
            floor[py * w + px + 1] = false
            floor[(py + 1) * w + px] = false
            floor[(py + 1) * w + px + 1] = false
        }
        rooms += findOpenZones(floor, w, h, g.roomsMin, g.roomsMax, rng)
    }

    /** Steps from ([sx], [sy]) to every floor tile (4-way), or Int.MAX_VALUE where unreachable. */
    private fun walkDistances(floor: BooleanArray, w: Int, h: Int, sx: Int, sy: Int): IntArray {
        val dist = IntArray(w * h) { Int.MAX_VALUE }
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        dist[sy * w + sx] = 0
        queue[tail++] = sy * w + sx
        while (head < tail) {
            val cur = queue[head++]
            val cx = cur % w
            val cy = cur / w
            for ((dx, dy) in NEIGHBOURS) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx !in 0 until w || ny !in 0 until h) continue
                val n = ny * w + nx
                if (!floor[n] || dist[n] != Int.MAX_VALUE) continue
                dist[n] = dist[cur] + 1
                queue[tail++] = n
            }
        }
        return dist
    }

    private val NEIGHBOURS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private fun <T> pick(rng: SeededRandomSource, options: List<T>, weight: (T) -> Int): T {
        val total = options.sumOf { weight(it).coerceAtLeast(1) }
        var roll = rng.nextInt(total)
        for (o in options) {
            roll -= weight(o).coerceAtLeast(1)
            if (roll < 0) return o
        }
        return options.last()
    }
}
