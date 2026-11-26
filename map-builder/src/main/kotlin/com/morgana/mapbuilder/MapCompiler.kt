package com.morgana.mapbuilder

import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.TilePlacement
import com.morgana.tilegen.autotile.AutotileBitmask
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/** Thrown when a recipe cannot be compiled (an unknown terrain or tile name, a prop that does not fit, a bad coordinate). */
class RecipeException(message: String) : IllegalArgumentException(message)

/**
 * Compiles a [MapRecipe] into a [MapDefinition]. Pure and deterministic: no files, no clock, seeded random only. Operations run in
 * order, so a later one paints over an earlier one; props and camps see the terrain as painted so far.
 */
object MapCompiler {

    internal class Cell(var terrain: Int) {
        val layers = LinkedHashMap<MapLayer, TilePlacement>()
        var groundOverride: TilePlacement? = null
        var blocked = false
        var open = false
        var occupied = false
        var attribute: TileAttribute? = null
    }

    fun compile(recipe: MapRecipe, index: TileIndex): MapDefinition = build(recipe, index).map

    /** The compiled map together with what the compiler knew while making it, for tools that read a map (see [MapAnalysis]). */
    fun build(recipe: MapRecipe, index: TileIndex): Built = buildInternal(recipe, index)

    class Built(val map: MapDefinition, val analysis: MapAnalysis)

    private fun buildInternal(recipe: MapRecipe, index: TileIndex): Built {
        val w = recipe.width
        val h = recipe.height
        if (w !in 5..256 || h !in 5..256) throw RecipeException("Map ${recipe.id}: size ${w}x$h is outside 5..256")
        val names = recipe.terrains.keys.toList()
        val ids = names.withIndex().associate { (i, n) -> n to i }
        fun terrain(name: String): Int = ids[name] ?: throw RecipeException("Map ${recipe.id}: unknown terrain '$name'")
        val grid = Array(w * h) { Cell(terrain(recipe.base)) }
        fun at(x: Int, y: Int) = grid[y * w + x]
        fun inb(x: Int, y: Int) = x in 0 until w && y in 0 until h
        val spawns = ArrayList<MapNpcSpawnSlot>()
        val campSlots = HashSet<Int>()
        val fields = HashMap<String, FloatArray>()

        fun paint(t: Int, x: Int, y: Int) {
            if (inb(x, y)) at(x, y).terrain = t
        }

        fun placeProp(name: String, px: Int, py: Int, passable: List<Int>, walkable: Boolean) {
            val p = index.props[name] ?: throw RecipeException("Map ${recipe.id}: unknown prop '$name'")
            for (r in 0 until p.h) for (c in 0 until p.w) {
                val x = px + c
                val y = py + r
                if (!inb(x, y)) throw RecipeException("Map ${recipe.id}: prop '$name' at ($px,$py) leaves the map")
                val cell = at(x, y)
                val bottom = r == p.h - 1
                cell.layers[if (bottom) MapLayer.MASK_1 else MapLayer.FRINGE_1] = TilePlacement(p.tilesetId, p.x + c, p.y + r)
                cell.occupied = true
                if (bottom && !walkable) {
                    if (c in passable) cell.open = true else cell.blocked = true
                }
            }
        }

        for (op in recipe.ops) when (op) {
            is RectOp -> {
                val t = terrain(op.terrain)
                for (y in op.y until op.y + op.h) for (x in op.x until op.x + op.w) paint(t, x, y)
            }
            is EllipseOp -> {
                val t = terrain(op.terrain)
                for (y in op.cy - op.ry..op.cy + op.ry) for (x in op.cx - op.rx..op.cx + op.rx) {
                    val nx = (x - op.cx).toFloat() / op.rx.coerceAtLeast(1)
                    val ny = (y - op.cy).toFloat() / op.ry.coerceAtLeast(1)
                    if (nx * nx + ny * ny <= 1f) paint(t, x, y)
                }
            }
            is PathOp -> if (op.meander <= 0f) {
                // The straight path: integer steps along the longer axis, exactly as before (existing maps compile to the same tiles).
                val t = terrain(op.terrain)
                val r = op.width / 2f
                for (i in 0 until op.points.size - 1) {
                    val x0 = op.points[i][0]
                    val y0 = op.points[i][1]
                    val x1 = op.points[i + 1][0]
                    val y1 = op.points[i + 1][1]
                    val steps = maxOf(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1)
                    for (s in 0..steps) {
                        val cx = x0 + (x1 - x0) * s / steps
                        val cy = y0 + (y1 - y0) * s / steps
                        val ri = r.toInt() + 1
                        for (dy in -ri..ri) for (dx in -ri..ri) {
                            if (hypot(dx.toFloat(), dy.toFloat()) <= r + 0.25f) paint(t, cx + dx, cy + dy)
                        }
                    }
                }
            } else {
                val t = terrain(op.terrain)
                val r = op.width / 2f
                // Walk the polyline a tile at a time; a meandering path pushes each sample sideways by smooth noise, fading to nothing
                // at both ends so the road still starts and ends where it was told to.
                val samples = ArrayList<Pair<Float, Float>>()
                var travelled = 0f
                var total = 0f
                for (i in 0 until op.points.size - 1) total += hypot((op.points[i + 1][0] - op.points[i][0]).toFloat(), (op.points[i + 1][1] - op.points[i][1]).toFloat())
                val wave = op.wavelength.coerceAtLeast(1f)
                for (i in 0 until op.points.size - 1) {
                    val x0 = op.points[i][0].toFloat()
                    val y0 = op.points[i][1].toFloat()
                    val x1 = op.points[i + 1][0].toFloat()
                    val y1 = op.points[i + 1][1].toFloat()
                    val len = hypot(x1 - x0, y1 - y0).coerceAtLeast(1f)
                    val nx = -(y1 - y0) / len
                    val ny = (x1 - x0) / len
                    val steps = len.toInt().coerceAtLeast(1)
                    for (st in 0..steps) {
                        val f = st.toFloat() / steps
                        var cx = x0 + (x1 - x0) * f
                        var cy = y0 + (y1 - y0) * f
                        if (op.meander > 0f) {
                            val along = travelled + len * f
                            val taper = minOf(1f, minOf(along, total - along) / wave).coerceAtLeast(0f)
                            val off = (NoiseField.gradient(along / wave, 0.5f, recipe.seed + op.salt) * 1.6f).coerceIn(-1f, 1f) * op.meander * taper
                            cx += nx * off
                            cy += ny * off
                        }
                        samples += cx to cy
                    }
                    travelled += len
                }
                val ri = r.toInt() + 1
                for ((cx, cy) in samples) {
                    val icx = Math.round(cx)
                    val icy = Math.round(cy)
                    for (dy in -ri..ri) for (dx in -ri..ri) {
                        if (hypot(dx.toFloat(), dy.toFloat()) <= r + 0.25f) paint(t, icx + dx, icy + dy)
                    }
                }
            }
            is NoiseOp -> {
                val region = op.region ?: listOf(0, 0, w, h)
                val f = NoiseField.smooth(NoiseField.field(w, h, recipe.seed + op.salt * 131L, op.scale, op.octaves, op.persistence, op.warp), w, h, op.smooth)
                if (op.edge > 0f) NoiseField.applyEdge(f, w, h, op.edge, op.edgeBias)
                op.store?.let { fields[it] = f }
                val before = IntArray(w * h) { grid[it].terrain }
                val bands = op.bands.map { terrain(it.terrain) to it.below }
                val only = op.onlyOn.map { terrain(it) }.toSet()
                if (bands.isNotEmpty()) for (y in maxOf(0, region[1]) until minOf(region[1] + region[3], h)) for (x in maxOf(0, region[0]) until minOf(region[0] + region[2], w)) {
                    val cell = at(x, y)
                    if (only.isNotEmpty() && cell.terrain !in only) continue
                    val v = f[y * w + x]
                    bands.firstOrNull { v < it.second }?.let { cell.terrain = it.first }
                }
                if (op.minArea > 0) {
                    // Undo painted patches of one terrain smaller than minArea (4-connected), restoring what was there before.
                    val seen = BooleanArray(w * h)
                    for (start in 0 until w * h) {
                        val tt = grid[start].terrain
                        if (seen[start] || tt == before[start]) continue
                        val comp = ArrayList<Int>()
                        val stack = ArrayDeque<Int>()
                        stack.add(start); seen[start] = true
                        while (stack.isNotEmpty()) {
                            val i = stack.removeLast(); comp += i
                            val x = i % w; val y = i / w
                            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                                val nx = x + dx; val ny = y + dy
                                if (nx !in 0 until w || ny !in 0 until h) continue
                                val j = ny * w + nx
                                if (!seen[j] && grid[j].terrain == tt && tt != before[j]) { seen[j] = true; stack.add(j) }
                            }
                        }
                        if (comp.size < op.minArea) for (i in comp) grid[i].terrain = before[i]
                    }
                }
            }
            is TerraceOp -> terrace(recipe, op, index, w, h, fields, ::terrain, ::at)
            is BlobOp -> {
                val t = terrain(op.terrain)
                val reach = (op.radius * (1f + op.roughness)).toInt() + 1
                for (y in op.cy - reach..op.cy + reach) for (x in op.cx - reach..op.cx + reach) {
                    val d = hypot((x - op.cx).toFloat(), (y - op.cy).toFloat())
                    val n = valueNoise(x / 4f, y / 4f, recipe.seed + op.salt)
                    if (d <= op.radius * (1f + (n - 0.5f) * 2f * op.roughness)) paint(t, x, y)
                }
            }
            is FrameOp -> {
                val t = terrain(op.terrain)
                fun gapped(edge: String, along: Int) = op.gaps[edge]?.let { along in it[0]..it[1] } ?: false
                for (y in 0 until h) for (x in 0 until w) {
                    val north = y < op.thickness
                    val south = y >= h - op.thickness
                    val west = x < op.thickness
                    val east = x >= w - op.thickness
                    val inBand = (north && !gapped("north", x)) || (south && !gapped("south", x)) ||
                        (west && !gapped("west", y)) || (east && !gapped("east", y))
                    if (inBand) paint(t, x, y)
                }
            }
            is WallOp -> {
                fun tile(part: String) = index.tiles["${op.set}_$part"] ?: throw RecipeException("Map ${recipe.id}: unknown wall tile '${op.set}_$part'")
                if (op.h < 3 || op.w < 1) throw RecipeException("Map ${recipe.id}: a wall needs h >= 3 (top, cornice, plinth)")
                for (r in 0 until op.h) for (c in 0 until op.w) {
                    val x = op.x + c
                    val y = op.y + r
                    if (!inb(x, y)) throw RecipeException("Map ${recipe.id}: wall at (${op.x},${op.y}) leaves the map")
                    val side = if (op.w == 1) "center" else when (c) {
                        0 -> "left"
                        op.w - 1 -> "right"
                        else -> "center"
                    }
                    val row = when (r) {
                        op.h - 1 -> "bot"
                        op.h - 2 -> "top"
                        else -> "void"
                    }
                    val t = tile("${row}_$side")
                    val cell = at(x, y)
                    cell.groundOverride = TilePlacement(t.tilesetId, t.x, t.y)
                    cell.occupied = true
                    if (c in op.passableCols && r >= op.h - 2) cell.open = true else cell.blocked = true
                }
            }
            is ReserveOp -> for (y in op.y until op.y + op.h) for (x in op.x until op.x + op.w) if (inb(x, y)) at(x, y).occupied = true
            is PropOp -> placeProp(op.prop, op.x, op.y, op.passableCols, op.walkable)
            is ScatterOp -> {
                val t = terrain(op.terrain)
                val rnd = Random(recipe.seed * 31 + op.salt)
                val region = op.region ?: listOf(0, 0, w, h)
                val cells = ArrayList<Pair<Int, Int>>()
                for (y in region[1] until minOf(region[1] + region[3], h)) for (x in region[0] until minOf(region[0] + region[2], w)) cells += x to y
                cells.shuffle(rnd)
                for ((x, y) in cells) {
                    if (rnd.nextFloat() >= op.density) continue
                    val name = op.props[rnd.nextInt(op.props.size)]
                    val p = index.props[name] ?: throw RecipeException("Map ${recipe.id}: unknown prop '$name'")
                    val px = x - p.w / 2
                    val py = y - p.h + 1
                    var ok = true
                    scan@ for (yy in py - op.gap..py + p.h - 1 + op.gap) for (xx in px - op.gap..px + p.w - 1 + op.gap) {
                        val inside = xx in px until px + p.w && yy in py until py + p.h
                        if (!inb(xx, yy)) {
                            if (inside) { ok = false; break@scan }
                            continue
                        }
                        val c = at(xx, yy)
                        if (c.occupied || (inside && (c.terrain != t || c.blocked))) { ok = false; break@scan }
                    }
                    if (ok) placeProp(name, px, py, emptyList(), op.walkable)
                }
            }
            is TileOp -> {
                val t = index.tiles[op.tile] ?: throw RecipeException("Map ${recipe.id}: unknown tile '${op.tile}'")
                if (!inb(op.x, op.y)) throw RecipeException("Map ${recipe.id}: tile at (${op.x},${op.y}) leaves the map")
                val c = at(op.x, op.y)
                c.layers[op.layer] = TilePlacement(t.tilesetId, t.x, t.y)
                if (op.blocked) c.blocked = true
            }
            is WarpOp -> for (y in op.y until op.y + op.h) for (x in op.x until op.x + op.w) {
                if (!inb(x, y)) throw RecipeException("Map ${recipe.id}: warp at ($x,$y) leaves the map")
                at(x, y).attribute = TileAttribute.Warp(op.toMap, op.toX, op.toY)
            }
            is SpawnOp -> {
                if (!inb(op.x, op.y)) throw RecipeException("Map ${recipe.id}: spawn at (${op.x},${op.y}) leaves the map")
                spawns += MapNpcSpawnSlot(spawns.size, op.npcId, op.x, op.y, op.respawnTicks)
                at(op.x, op.y).occupied = true
            }
            is CampsOp -> {
                val before = spawns.size
                compileCamps(recipe, op, w, h, ::at, ::inb, ids, recipe.terrains, names, spawns)
                for (i in before until spawns.size) campSlots += i
            }
        }

        val terrainIds = grid.map { it.terrain }
        val cells = ArrayList<MapCell>(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val c = at(x, y)
            val def = recipe.terrains.getValue(names[c.terrain])
            val layers = LinkedHashMap<MapLayer, TilePlacement>()
            layers[MapLayer.GROUND] = c.groundOverride ?: ground(recipe, index, def, names, terrainIds, w, h, x, y)
            layers.putAll(c.layers)
            val blocked = (def.blocked && !c.open && c.groundOverride == null) || c.blocked
            val attr = c.attribute ?: if (blocked) TileAttribute.Blocked else TileAttribute.None
            cells += MapCell(layers, attr)
        }
        for (sp in spawns) {
            val a = cells[sp.spawnY * w + sp.spawnX].attribute
            if (a == TileAttribute.Blocked) throw RecipeException("Map ${recipe.id}: npc ${sp.npcId} spawns on a blocked tile (${sp.spawnX},${sp.spawnY})")
        }
        var finalSpawns: List<MapNpcSpawnSlot> = spawns
        recipe.anchor?.let { a ->
            val seen = BooleanArray(w * h)
            val queue = ArrayDeque<Int>()
            if (!inb(a[0], a[1]) || cells[a[1] * w + a[0]].attribute == TileAttribute.Blocked) throw RecipeException("Map ${recipe.id}: anchor (${a[0]},${a[1]}) is not walkable")
            seen[a[1] * w + a[0]] = true
            queue.add(a[1] * w + a[0])
            while (queue.isNotEmpty()) {
                val i = queue.removeFirst()
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = i % w + dx
                    val ny = i / w + dy
                    if (!inb(nx, ny) || seen[ny * w + nx] || cells[ny * w + nx].attribute == TileAttribute.Blocked) continue
                    seen[ny * w + nx] = true
                    queue.add(ny * w + nx)
                }
            }
            for ((i, sp) in spawns.withIndex()) if (i !in campSlots && !seen[sp.spawnY * w + sp.spawnX]) {
                throw RecipeException("Map ${recipe.id}: npc ${sp.npcId} at (${sp.spawnX},${sp.spawnY}) cannot be reached from the anchor")
            }
            finalSpawns = spawns.filterIndexed { i, sp -> i !in campSlots || seen[sp.spawnY * w + sp.spawnX] }
                .mapIndexed { n, sp -> sp.copy(slotIndex = n) }
        }
        val map = MapDefinition(
            id = recipe.id, name = recipe.name, width = w, height = h, moral = recipe.moral, bgmTrackId = recipe.bgm,
            isIndoors = recipe.indoors, zoneId = recipe.zoneId, instanced = recipe.instanced, instanceOwner = recipe.instanceOwner,
            neighbors = recipe.neighbors, cells = cells, npcSpawns = finalSpawns
        )
        val symbols = "abcdefghijklmnopqrstuvwxyz"
        val legend = names.withIndex().associate { (i, n) -> symbols[i % symbols.length].toString() to n }
        val terrainRows = (0 until h).map { y -> (0 until w).joinToString("") { x -> symbols[at(x, y).terrain % symbols.length].toString() } }
        val blockedRows = (0 until h).map { y -> (0 until w).joinToString("") { x -> if (cells[y * w + x].attribute == TileAttribute.Blocked) "#" else "." } }
        // 'o' marks a cell something sits on (a prop, a cliff face, a ramp, a reserved landing): no camp or prop was allowed there.
        val occupiedRows = (0 until h).map { y -> (0 until w).joinToString("") { x -> if (at(x, y).occupied) "o" else "." } }
        return Built(map, MapAnalysis(recipe.id, recipe.name, w, h, legend, terrainRows, blockedRows, occupiedRows, finalSpawns.map { listOf(it.npcId, it.spawnX, it.spawnY) }))
    }


    /** The [TerraceOp] work: levels from a stored field, plateau ground, south-facing cliff faces, and ramps through them. */
    private fun terrace(
        recipe: MapRecipe, op: TerraceOp, index: TileIndex, w: Int, h: Int, fields: Map<String, FloatArray>,
        terrain: (String) -> Int, at: (Int, Int) -> Cell
    ) {
        val f = fields[op.field] ?: throw RecipeException("Map ${recipe.id}: terrace needs a noise op with store \"${op.field}\" before it")
        if (op.terrains.size != op.thresholds.size) throw RecipeException("Map ${recipe.id}: terrace needs one terrain per threshold")
        fun tile(part: String) = index.tiles["${op.set}_$part"] ?: throw RecipeException("Map ${recipe.id}: unknown wall tile '${op.set}_$part'")
        val names = recipe.terrains.keys.toList()
        fun blockedTerrain(c: Cell) = recipe.terrains.getValue(names[c.terrain]).blocked
        val region = op.region ?: listOf(0, 0, w, h)
        fun inRegion(x: Int, y: Int) = x in region[0] until region[0] + region[2] && y in region[1] until region[1] + region[3]
        val level = IntArray(w * h)
        // Sampling the field in bands of rowStep rows puts every plateau's south edge on one of a few rows, so cliff faces run
        // horizontally in long stretches (like stepped terraces) instead of breaking into one-tile stairs along a diagonal.
        val step = op.rowStep.coerceAtLeast(1)
        for (y in 0 until h) for (x in 0 until w) if (inRegion(x, y)) {
            val v = f[minOf(h - 1, (y / step) * step + step / 2) * w + x]
            level[y * w + x] = op.thresholds.count { v >= it }
        }
        fun lv(x: Int, y: Int) = if (x in 0 until w && y in 0 until h) level[y * w + x] else 0
        // Level off slivers: a run of plateau narrower than minWidth or shallower than minDepth drops a level, so every cliff
        // face has two rows of plateau to be drawn on and a plateau is wide enough to walk.
        for (pass in 0 until 4) {
            var changed = false
            for (y in 0 until h) for (x in 0 until w) {
                val l = lv(x, y)
                if (l == 0) continue
                var left = x
                while (lv(left - 1, y) >= l) left--
                var right = x
                while (lv(right + 1, y) >= l) right++
                var up = y
                while (lv(x, up - 1) >= l) up--
                var down = y
                while (lv(x, down + 1) >= l) down++
                if (right - left + 1 < op.minWidth || down - up + 1 < op.minDepth) { level[y * w + x] = l - 1; changed = true }
            }
            if (!changed) break
        }
        for (y in 0 until h) for (x in 0 until w) if (lv(x, y) > 0) at(x, y).terrain = terrain(op.terrains[lv(x, y) - 1])
        // Cliff faces: the plateau cell whose southern neighbour is lower is the plinth (bot), the one above it the cornice (top).
        val face = HashMap<Int, String>()
        for (y in 0 until h - 1) for (x in 0 until w) {
            val l = lv(x, y)
            if (l > 0 && lv(x, y + 1) < l) {
                face[y * w + x] = "bot"
                if (lv(x, y - 1) >= l) face[(y - 1) * w + x] = "top"
            }
        }
        // Ramps: along each run of faces, open a gap every rampEvery tiles (position fixed by a hash, so it is stable).
        val ramp = HashSet<Int>()
        if (op.ramps) {
            for (y in 0 until h - 1) {
                var x = 0
                while (x < w) {
                    if (face[y * w + x] != "bot") { x++; continue }
                    var end = x
                    while (end + 1 < w && face[y * w + end + 1] == "bot" && lv(end + 1, y) == lv(x, y)) end++
                    val run = end - x + 1
                    if (run >= op.rampWidth + 2) {
                        val slots = maxOf(1, run / op.rampEvery)
                        for (k in 0 until slots) {
                            val span = run - op.rampWidth - 1
                            val start = x + 1 + (if (span <= 0) 0 else (hash(x + k * 31, y, recipe.seed + op.salt) % (span + 1)))
                            // Only where the ground below can be walked on.
                            if ((start until start + op.rampWidth).all { cx -> !blockedTerrain(at(cx, y + 1)) }) {
                                for (cx in start until start + op.rampWidth) { ramp += y * w + cx; ramp += (y - 1) * w + cx }
                            }
                        }
                    }
                    x = end + 1
                }
            }
        }
        val rampTerrain = op.rampTerrain?.let(terrain)
        for ((i, kind) in face) {
            val x = i % w
            val y = i / w
            val cell = at(x, y)
            cell.occupied = true
            if (i in ramp) {
                cell.terrain = rampTerrain ?: at(x, y + 1).terrain
                continue
            }
            val l = lv(x, y)
            fun same(nx: Int) = nx in 0 until w && face[y * w + nx] == kind && (y * w + nx) !in ramp && lv(nx, y) == l
            val side = when {
                !same(x - 1) && !same(x + 1) -> "center"
                !same(x - 1) -> "left"
                !same(x + 1) -> "right"
                else -> "center"
            }
            val t = tile("${kind}_$side")
            cell.groundOverride = TilePlacement(t.tilesetId, t.x, t.y)
            cell.blocked = true
        }
        for (i in ramp) at(i % w, i / w).occupied = true
    }

    private fun ground(
        recipe: MapRecipe, index: TileIndex, def: TerrainDef, names: List<String>, terrains: List<Int>, w: Int, h: Int, x: Int, y: Int
    ): TilePlacement {
        val self = terrains[y * w + x]
        fun joined(nx: Int, ny: Int): Boolean {
            if (nx !in 0 until w || ny !in 0 until h) return true
            val t = terrains[ny * w + nx]
            return t == self || names[t] in def.joins
        }
        if (def.autotile != null) {
            val auto = index.autotiles[def.autotile] ?: throw RecipeException("Map ${recipe.id}: unknown autotile '${def.autotile}'")
            val mask = AutotileBitmask.blobMask(
                n = joined(x, y - 1), ne = joined(x + 1, y - 1), e = joined(x + 1, y), se = joined(x + 1, y + 1),
                s = joined(x, y + 1), sw = joined(x - 1, y + 1), w = joined(x - 1, y), nw = joined(x - 1, y - 1)
            )
            if (mask == 255 && def.animatedFrames > 1) animated(index, def)?.let { return it }
            val cell = auto.cells[mask] ?: throw RecipeException("Map ${recipe.id}: autotile '${def.autotile}' has no cell for mask $mask")
            return TilePlacement(auto.tilesetId, cell.first, cell.second)
        }
        if (def.animatedFrames > 1) animated(index, def)?.let { return it }
        val variants = ArrayList<TileIndex.Place>()
        index.tiles[def.tile]?.let { variants += it }
        var v = 1
        while (true) {
            variants += index.tiles["${def.tile}_v$v"] ?: break
            v++
        }
        if (variants.isEmpty()) throw RecipeException("Map ${recipe.id}: unknown tile '${def.tile}'")
        val p = variants[hash(x, y, recipe.seed) % variants.size]
        return TilePlacement(p.tilesetId, p.x, p.y)
    }

    private fun animated(index: TileIndex, def: TerrainDef): TilePlacement? {
        val p = index.tiles["${def.tile}_f0"] ?: return null
        return TilePlacement(p.tilesetId, p.x, p.y, animationFrames = def.animatedFrames, animationSpeedMs = def.animationSpeedMs)
    }

    private fun compileCamps(
        recipe: MapRecipe, op: CampsOp, w: Int, h: Int, at: (Int, Int) -> Cell, inb: (Int, Int) -> Boolean,
        ids: Map<String, Int>, defs: Map<String, TerrainDef>, names: List<String>, spawns: MutableList<MapNpcSpawnSlot>
    ) {
        require(op.npcIds.isNotEmpty()) { "camps needs at least one npc id" }
        val allowed = op.terrains.map { ids[it] ?: throw RecipeException("Map ${recipe.id}: unknown terrain '$it'") }.toSet()
        val region = op.region ?: listOf(0, 0, w, h)
        fun eligible(x: Int, y: Int): Boolean {
            if (!inb(x, y) || x !in region[0] until region[0] + region[2] || y !in region[1] until region[1] + region[3]) return false
            val c = at(x, y)
            return c.terrain in allowed && !c.occupied && !c.blocked && !defs.getValue(names[c.terrain]).blocked && c.attribute == null
        }
        val pool = ArrayList<Pair<Int, Int>>()
        for (y in region[1] until minOf(region[1] + region[3], h)) for (x in region[0] until minOf(region[0] + region[2], w)) if (eligible(x, y)) pool += x to y
        if (pool.isEmpty()) return
        val rnd = Random(recipe.seed * 17 + op.salt)
        val avg = (op.sizeMin + op.sizeMax) / 2f
        val campCount = op.camps ?: (pool.size / (op.tilesPerEnemy * avg)).toInt().coerceAtLeast(1)
        val centres = ArrayList<Pair<Int, Int>>()
        val used = HashSet<Pair<Int, Int>>()
        var attempts = 0
        while (centres.size < campCount && attempts < campCount * 80) {
            attempts++
            val c = pool[rnd.nextInt(pool.size)]
            if (centres.any { abs(it.first - c.first) < op.spacing && abs(it.second - c.second) < op.spacing }) continue
            centres += c
            val npc = op.npcIds[(centres.size - 1) % op.npcIds.size]
            val size = rnd.nextInt(op.sizeMin, op.sizeMax + 1)
            var placed = 0
            var tries = 0
            while (placed < size && tries < size * 20) {
                tries++
                val x = c.first + rnd.nextInt(-op.radius, op.radius + 1)
                val y = c.second + rnd.nextInt(-op.radius, op.radius + 1)
                if (!eligible(x, y) || (x to y) in used) continue
                used += x to y
                spawns += MapNpcSpawnSlot(spawns.size, npc, x, y, op.respawnTicks)
                placed++
            }
        }
    }

    internal fun hash(x: Int, y: Int, seed: Long): Int {
        var v = seed * -7046029254386353131L + x * 1664525L + y * 22695477L
        v = v xor (v ushr 29)
        v *= -4658895280553007687L
        v = v xor (v ushr 32)
        return (v and 0x7fffffffL).toInt()
    }

    private fun valueNoise(x: Float, y: Float, seed: Long): Float {
        val x0 = Math.floor(x.toDouble()).toInt()
        val y0 = Math.floor(y.toDouble()).toInt()
        val fx = x - x0
        val fy = y - y0
        fun v(ix: Int, iy: Int) = (hash(ix, iy, seed) % 10000) / 10000f
        val sx = fx * fx * (3 - 2 * fx)
        val sy = fy * fy * (3 - 2 * fy)
        val a = v(x0, y0) + (v(x0 + 1, y0) - v(x0, y0)) * sx
        val b = v(x0, y0 + 1) + (v(x0 + 1, y0 + 1) - v(x0, y0 + 1)) * sx
        return a + (b - a) * sy
    }
}
