package com.morgana.tilegen

import kotlin.math.abs
import kotlin.math.sqrt

enum class TilesetTheme {
    CLASSIC,
    FARM,
    DUNGEON,
    SPACESHIP
}

/**
 * A single generated 32x32 pixel-art tile with a filesystem-safe [name].
 */
data class NamedTile(val name: String, val image: TileImage)

/**
 * Generates themed 32x32 pixel-art tiles (farm, dungeon, spaceship) and bundles any list of 32x32 tiles
 * into a standard 16-column, 32-pixel-aligned tileset [TileImage].
 */
class ThemedTilesetGenerator(val seed: Long = 1337L) {

    private val base = ProceduralTilesetGenerator(seed)
    val tileSize: Int = base.tileSize

    private fun rgba(r: Int, g: Int, b: Int, a: Int = 255): Int =
        ((r and 0xFF) shl 24) or ((g and 0xFF) shl 16) or ((b and 0xFF) shl 8) or (a and 0xFF)

    private fun hash(x: Int, y: Int, s: Long): Float {
        var h = s xor (x.toLong() * 374761393L) xor (y.toLong() * 668265263L)
        h = (h xor (h ushr 16)) * 0x85ebca6bL
        h = (h xor (h ushr 13)) * 0xc2b2ae35L
        return ((h xor (h ushr 16)) and 0x7FFFFFFFL).toFloat() / 0x7FFFFFFFL.toFloat()
    }

    /**
     * Generates all 32x32 tiles for [theme].
     * For [TilesetTheme.CLASSIC], slices the default 16x3 sheet into 48 individual 32x32 tiles.
     */
    fun generateTiles(theme: TilesetTheme): List<NamedTile> = when (theme) {
        TilesetTheme.CLASSIC -> sliceSheet(base.generateTileset(columns = 16, rows = 3), "classic")
        TilesetTheme.FARM -> generateFarmTiles()
        TilesetTheme.DUNGEON -> generateDungeonTiles()
        TilesetTheme.SPACESHIP -> generateSpaceshipTiles()
    }

    /**
     * Generates a bundled tileset [TileImage] for [theme] with [columns] tiles per row.
     */
    fun generateTileset(theme: TilesetTheme, columns: Int = 16): TileImage {
        if (theme == TilesetTheme.CLASSIC) {
            return base.generateTileset(columns = columns, rows = 3)
        }
        return bundleTiles(generateTiles(theme).map { it.image }, columns = columns)
    }

    /**
     * Bundles a list of 32x32 [tiles] left-to-right, top-to-bottom into a single [TileImage]
     * whose width is `columns * 32` and height is `rows * 32`.
     */
    fun bundleTiles(tiles: List<TileImage>, columns: Int = 16): TileImage {
        require(columns > 0) { "columns must be positive" }
        val rows = ((tiles.size + columns - 1) / columns).coerceAtLeast(1)
        val sheet = TileImage(columns * tileSize, rows * tileSize)
        tiles.forEachIndexed { index, tile ->
            val col = index % columns
            val row = index / columns
            sheet.drawImage(tile, 0, 0, tileSize, tileSize, col * tileSize, row * tileSize)
        }
        return sheet
    }

    private fun sliceSheet(sheet: TileImage, prefix: String): List<NamedTile> {
        val cols = sheet.width / tileSize
        val rows = sheet.height / tileSize
        val out = ArrayList<NamedTile>(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                out += NamedTile("${prefix}_${r * cols + c}", sheet.crop(c * tileSize, r * tileSize, tileSize, tileSize))
            }
        }
        return out
    }

    private fun tile(name: String, draw: (pm: TileImage) -> Unit): NamedTile {
        val pm = TileImage(tileSize, tileSize)
        draw(pm)
        return NamedTile(name, pm)
    }

    private fun fillRect(pm: TileImage, x0: Int, y0: Int, w: Int, h: Int, color: Int) {
        for (py in y0 until (y0 + h).coerceAtMost(tileSize)) {
            if (py < 0) continue
            for (px in x0 until (x0 + w).coerceAtMost(tileSize)) {
                if (px >= 0) pm.drawPixel(px, py, color)
            }
        }
    }

    private fun fillCircle(pm: TileImage, cx: Int, cy: Int, r: Int, color: Int) {
        val r2 = r * r
        for (py in (cy - r)..(cy + r)) {
            if (py !in 0 until tileSize) continue
            for (px in (cx - r)..(cx + r)) {
                if (px !in 0 until tileSize) continue
                val dx = px - cx
                val dy = py - cy
                if (dx * dx + dy * dy <= r2) pm.drawPixel(px, py, color)
            }
        }
    }

    // =========================================================================
    // FARM THEME (32 tiles: 2 rows x 16 columns)
    // =========================================================================

    private fun generateFarmTiles(): List<NamedTile> = listOf(
        // Row 0: Terrain, soil, fences & barn architecture
        tile("pasture_grass") { pm -> base.drawGrassTile(pm, 0, 0, ProceduralTilesetGenerator.GrassVariant.BASE) },
        tile("clover_meadow") { pm -> base.drawGrassTile(pm, 0, 0, ProceduralTilesetGenerator.GrassVariant.CLOVER) },
        tile("dirt_path") { pm -> base.drawDirtTile(pm, 0, 0, withPebbles = false) },
        tile("pebble_track") { pm -> base.drawDirtTile(pm, 0, 0, withPebbles = true) },
        tile("tilled_soil_dry") { pm -> drawFurrowedSoil(pm, watered = false) },
        tile("tilled_soil_wet") { pm -> drawFurrowedSoil(pm, watered = true) },
        tile("irrigation_canal") { pm -> drawIrrigationCanal(pm) },
        tile("wooden_boardwalk") { pm -> base.drawWoodenPlanksTile(pm, 0, 0) },
        tile("farmyard_cobble") { pm -> base.drawCobbleRoadTile(pm, 0, 0) },
        tile("barn_hay_floor") { pm -> drawBarnHayFloor(pm) },
        tile("wooden_fence_h") { pm -> drawWoodenFence(pm, corner = false) },
        tile("wooden_fence_post") { pm -> drawWoodenFence(pm, corner = true) },
        tile("stone_paddock_wall") { pm -> drawPaddockWall(pm) },
        tile("barn_red_wall") { pm -> drawBarnWall(pm) },
        tile("barn_shingles") { pm -> base.drawRoofShinglesTile(pm, 0, 0) },
        tile("thatched_roof") { pm -> drawThatchedRoof(pm) },

        // Row 1: Crop stages & farmstead props
        tile("crop_seeds") { pm ->
            drawFurrowedSoil(pm, watered = true)
            val seedCol = rgba(217, 178, 114)
            listOf(7 to 9, 14 to 11, 22 to 8, 9 to 21, 18 to 23, 25 to 20).forEach { (sx, sy) ->
                pm.drawPixel(sx, sy, seedCol)
                pm.drawPixel(sx + 1, sy, seedCol)
            }
        },
        tile("crop_sprouts") { pm ->
            drawFurrowedSoil(pm, watered = true)
            val stem = rgba(74, 222, 128)
            val leaf = rgba(34, 197, 94)
            listOf(7 to 10, 16 to 9, 24 to 11, 8 to 22, 17 to 21, 25 to 23).forEach { (sx, sy) ->
                pm.drawPixel(sx, sy, stem)
                pm.drawPixel(sx, sy - 1, stem)
                pm.drawPixel(sx - 1, sy - 2, leaf)
                pm.drawPixel(sx + 1, sy - 2, leaf)
            }
        },
        tile("wheat_young") { pm ->
            drawFurrowedSoil(pm, watered = false)
            val green = rgba(34, 197, 94)
            val tip = rgba(134, 239, 172)
            for (i in 0 until 5) {
                val x = 4 + i * 6
                for (y in 10 until 26) pm.drawPixel(x, y, green)
                pm.drawPixel(x, 8, tip)
                pm.drawPixel(x, 9, tip)
            }
        },
        tile("wheat_ripe") { pm ->
            drawFurrowedSoil(pm, watered = false)
            val stalk = rgba(202, 138, 4)
            val head = rgba(250, 204, 21)
            val gleam = rgba(254, 240, 138)
            for (i in 0 until 5) {
                val x = 4 + i * 6
                for (y in 12 until 28) pm.drawPixel(x, y, stalk)
                fillRect(pm, x - 1, 6, 3, 6, head)
                pm.drawPixel(x, 7, gleam)
            }
        },
        tile("turnip_crop") { pm ->
            drawFurrowedSoil(pm, watered = true)
            val leaf = rgba(34, 197, 94)
            val purple = rgba(168, 85, 247)
            val bulb = rgba(245, 243, 255)
            listOf(10, 22).forEach { cx ->
                for (y in 7 until 14) pm.drawPixel(cx, y, leaf)
                pm.drawPixel(cx - 1, 9, leaf)
                pm.drawPixel(cx + 1, 9, leaf)
                fillCircle(pm, cx, 18, 4, bulb)
                fillRect(pm, cx - 3, 14, 7, 3, purple)
            }
        },
        tile("pumpkin_patch") { pm ->
            base.drawGrassTile(pm, 0, 0, ProceduralTilesetGenerator.GrassVariant.BASE)
            val stem = rgba(21, 128, 61)
            val orange = rgba(234, 88, 12)
            val darkOrange = rgba(194, 65, 12)
            val highlight = rgba(251, 146, 60)
            fillRect(pm, 15, 7, 2, 5, stem)
            fillCircle(pm, 16, 19, 7, orange)
            for (py in 13..25) {
                pm.drawPixel(13, py, darkOrange)
                pm.drawPixel(19, py, darkOrange)
                pm.drawPixel(16, py, highlight)
            }
        },
        tile("berry_bush_plain") { pm ->
            base.drawGrassTile(pm, 0, 0, ProceduralTilesetGenerator.GrassVariant.BASE)
            fillCircle(pm, 16, 17, 10, base.grassShadow)
            fillCircle(pm, 15, 16, 9, base.grassBase)
            fillCircle(pm, 14, 14, 6, base.grassMid)
        },
        tile("berry_bush_ripe") { pm ->
            base.drawGrassTile(pm, 0, 0, ProceduralTilesetGenerator.GrassVariant.BASE)
            fillCircle(pm, 16, 17, 10, base.grassShadow)
            fillCircle(pm, 15, 16, 9, base.grassBase)
            fillCircle(pm, 14, 14, 6, base.grassMid)
            val berry = rgba(219, 39, 119)
            val glint = rgba(251, 207, 232)
            listOf(11 to 13, 19 to 12, 14 to 19, 20 to 20, 10 to 20).forEach { (bx, by) ->
                fillRect(pm, bx, by, 2, 2, berry)
                pm.drawPixel(bx, by, glint)
            }
        },
        tile("apple_tree") { pm ->
            base.drawAncientOakTree(pm, 0, 0)
            val red = rgba(220, 38, 38)
            val shine = rgba(254, 202, 202)
            listOf(11 to 9, 19 to 8, 15 to 14, 9 to 15, 21 to 15).forEach { (ax, ay) ->
                fillRect(pm, ax, ay, 2, 2, red)
                pm.drawPixel(ax, ay, shine)
            }
        },
        tile("scarecrow") { pm ->
            base.drawGrassTile(pm, 0, 0, ProceduralTilesetGenerator.GrassVariant.BASE)
            val straw = rgba(250, 204, 21)
            val shirt = rgba(185, 28, 28)
            val hat = rgba(120, 53, 15)
            fillRect(pm, 15, 10, 2, 18, base.woodDark)
            fillRect(pm, 7, 13, 18, 2, base.woodBase)
            fillRect(pm, 12, 12, 8, 8, shirt)
            fillRect(pm, 13, 7, 6, 5, straw)
            fillRect(pm, 10, 6, 12, 2, hat)
            fillRect(pm, 12, 4, 8, 2, hat)
        },
        tile("hay_bale") { pm ->
            base.drawGrassTile(pm, 0, 0, ProceduralTilesetGenerator.GrassVariant.BASE)
            val hay = rgba(234, 179, 8)
            val hayLight = rgba(253, 224, 71)
            val cord = rgba(161, 98, 7)
            fillRect(pm, 5, 9, 22, 16, hay)
            fillRect(pm, 5, 9, 22, 3, hayLight)
            fillRect(pm, 10, 9, 2, 16, cord)
            fillRect(pm, 20, 9, 2, 16, cord)
        },
        tile("beehive") { pm ->
            base.drawGrassTile(pm, 0, 0, ProceduralTilesetGenerator.GrassVariant.BASE)
            val amber = rgba(245, 158, 11)
            val darkAmber = rgba(180, 83, 9)
            fillRect(pm, 9, 8, 14, 17, amber)
            for (y in 8 until 25 step 4) fillRect(pm, 9, y, 14, 1, darkAmber)
            fillRect(pm, 14, 18, 4, 5, base.woodDark)
        },
        tile("stone_well") { pm ->
            base.drawGrassTile(pm, 0, 0, ProceduralTilesetGenerator.GrassVariant.BASE)
            fillCircle(pm, 16, 19, 9, base.stoneBase)
            fillCircle(pm, 16, 19, 6, base.waterDeep)
            fillRect(pm, 7, 6, 2, 13, base.woodDark)
            fillRect(pm, 23, 6, 2, 13, base.woodDark)
            fillRect(pm, 6, 4, 20, 3, base.woodBase)
        },
        tile("water_trough") { pm ->
            base.drawDirtTile(pm, 0, 0, withPebbles = false)
            fillRect(pm, 4, 11, 24, 12, base.woodDark)
            fillRect(pm, 5, 12, 22, 10, base.woodBase)
            fillRect(pm, 6, 13, 20, 7, base.waterMid)
            fillRect(pm, 7, 14, 12, 2, base.waterCrest)
        },
        tile("shipping_crate") { pm ->
            base.drawWoodenPlanksTile(pm, 0, 0)
            fillRect(pm, 5, 6, 22, 20, base.woodDark)
            fillRect(pm, 7, 8, 18, 16, base.woodLight)
            for (i in 0 until 16) pm.drawPixel(8 + i, 8 + i, base.woodDark)
        },
        tile("hen_coop") { pm ->
            base.drawGrassTile(pm, 0, 0, ProceduralTilesetGenerator.GrassVariant.BASE)
            val straw = rgba(234, 179, 8)
            fillRect(pm, 6, 10, 20, 15, base.woodBase)
            fillRect(pm, 5, 8, 22, 3, base.woodDark)
            fillRect(pm, 13, 17, 6, 8, base.woodDark)
            fillRect(pm, 8, 23, 16, 2, straw)
        }
    )

    private fun drawFurrowedSoil(pm: TileImage, watered: Boolean) {
        val dark = if (watered) rgba(44, 27, 15) else base.dirtDeep
        val mid = if (watered) rgba(68, 43, 24) else base.dirtShade
        val ridge = if (watered) rgba(88, 57, 33) else base.dirtBase
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val n = base.wrappedFbm(px.toFloat(), py.toFloat(), octaves = 2, s = seed + 3100L)
                val furrow = py % 6
                val col = when {
                    furrow == 0 || furrow == 5 -> dark
                    furrow == 2 || furrow == 3 -> if (n > 0.55f) ridge else mid
                    else -> mid
                }
                pm.drawPixel(px, py, col)
            }
        }
    }

    private fun drawIrrigationCanal(pm: TileImage) {
        base.drawDirtTile(pm, 0, 0, withPebbles = false)
        for (py in 0 until tileSize) {
            for (px in 6 until 26) {
                val n = base.wrappedFbm(px.toFloat(), py.toFloat(), octaves = 2, s = seed + 4100L)
                val col = when {
                    px == 6 || px == 25 -> base.dirtDeep
                    n < 0.35f -> base.waterDeep
                    n < 0.72f -> base.waterMid
                    else -> base.waterCrest
                }
                pm.drawPixel(px, py, col)
            }
        }
    }

    private fun drawBarnHayFloor(pm: TileImage) {
        base.drawWoodenPlanksTile(pm, 0, 0)
        val hay = rgba(234, 179, 8)
        val hayShadow = rgba(161, 98, 7)
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val h = hash(px, py, seed + 4200L)
                if (h > 0.84f) pm.drawPixel(px, py, hay)
                else if (h > 0.78f) pm.drawPixel(px, py, hayShadow)
            }
        }
    }

    private fun drawWoodenFence(pm: TileImage, corner: Boolean) {
        base.drawGrassTile(pm, 0, 0, ProceduralTilesetGenerator.GrassVariant.BASE)
        fillRect(pm, 2, 11, 28, 3, base.woodBase)
        fillRect(pm, 2, 19, 28, 3, base.woodBase)
        fillRect(pm, 4, 7, 4, 20, base.woodDark)
        fillRect(pm, 24, 7, 4, 20, base.woodDark)
        if (corner) fillRect(pm, 14, 5, 5, 23, base.woodLight)
    }

    private fun drawPaddockWall(pm: TileImage) {
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val (d1, d2, cellHash) = base.periodicVoronoi(px.toFloat(), py.toFloat(), cells = 4, s = seed + 4300L)
                val col = when {
                    (d2 - d1) < 0.14f -> base.mortarCrevice
                    cellHash < 0.4f -> base.stoneDark
                    cellHash < 0.8f -> base.stoneBase
                    else -> base.stoneLight
                }
                pm.drawPixel(px, py, col)
            }
        }
    }

    private fun drawBarnWall(pm: TileImage) {
        val redDark = rgba(127, 29, 29)
        val redBase = rgba(185, 28, 28)
        val redLight = rgba(220, 38, 38)
        val trim = rgba(245, 245, 244)
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val col = when {
                    py < 2 || py > 29 -> trim
                    px % 8 == 0 -> redDark
                    px % 8 == 1 -> redLight
                    else -> redBase
                }
                pm.drawPixel(px, py, col)
            }
        }
    }

    private fun drawThatchedRoof(pm: TileImage) {
        val strawDark = rgba(161, 98, 7)
        val straw = rgba(202, 138, 4)
        val strawLight = rgba(250, 204, 21)
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val n = hash(px, py / 2, seed + 4400L)
                val col = when {
                    py % 6 == 0 -> strawDark
                    n > 0.72f -> strawLight
                    else -> straw
                }
                pm.drawPixel(px, py, col)
            }
        }
    }

    // =========================================================================
    // DUNGEON THEME (32 tiles: 2 rows x 16 columns)
    // =========================================================================

    private fun generateDungeonTiles(): List<NamedTile> = listOf(
        // Row 0: Stone floors, brick walls, hazards & elevation
        tile("crypt_flagstone") { pm -> base.drawCryptFloorTile(pm, 0, 0) },
        tile("cracked_stone_floor") { pm -> drawCrackedCryptFloor(pm) },
        tile("mossy_stone_floor") { pm -> drawMossyCryptFloor(pm) },
        tile("blood_stained_floor") { pm -> drawBloodStainedFloor(pm) },
        tile("dungeon_brick_wall") { pm -> base.drawDungeonWallTile(pm, 0, 0) },
        tile("mossy_brick_wall") { pm -> drawMossyBrickWall(pm) },
        tile("ruined_brick_wall") { pm -> drawRuinedBrickWall(pm) },
        tile("carved_pillar") { pm -> drawCarvedPillar(pm) },
        tile("iron_grate_floor") { pm -> drawIronGrate(pm) },
        tile("deep_chasm") { pm -> drawChasm(pm) },
        tile("lava_pool") { pm -> drawLavaPool(pm) },
        tile("dark_dungeon_water") { pm -> drawDarkWater(pm) },
        tile("chasm_bridge") { pm -> base.drawWoodenBridgeTile(pm, 0, 0) },
        tile("stairs_down") { pm -> drawStairs(pm, down = true) },
        tile("stairs_up") { pm -> drawStairs(pm, down = false) },
        tile("spike_trap") { pm -> drawSpikeTrap(pm) },

        // Row 1: Doors, loot, traps & atmospheric props
        tile("iron_oak_door") { pm -> drawDungeonDoor(pm) },
        tile("iron_gate") { pm -> drawIronGate(pm) },
        tile("stone_archway") { pm -> drawStoneArchway(pm) },
        tile("lit_brazier") { pm -> drawBrazier(pm, lit = true) },
        tile("unlit_brazier") { pm -> drawBrazier(pm, lit = false) },
        tile("treasure_chest") { pm -> drawChest(pm, open = false) },
        tile("open_chest") { pm -> drawChest(pm, open = true) },
        tile("wooden_barrel") { pm -> drawBarrel(pm) },
        tile("dungeon_crate") { pm -> drawDungeonCrate(pm) },
        tile("bone_pile") { pm -> drawBonePile(pm) },
        tile("cobweb_corner") { pm -> drawCobweb(pm) },
        tile("runic_portal") { pm -> drawRunicPortal(pm) },
        tile("stone_altar") { pm -> drawStoneAltar(pm) },
        tile("archive_bookshelf") { pm -> drawBookshelf(pm) },
        tile("floor_switch") { pm -> drawFloorSwitch(pm) },
        tile("crimson_carpet") { pm -> drawCrimsonCarpet(pm) }
    )

    private fun drawCrackedCryptFloor(pm: TileImage) {
        base.drawCryptFloorTile(pm, 0, 0)
        val crack = rgba(18, 20, 26)
        for (i in 4 until 16) pm.drawPixel(i, i + 2, crack)
        for (i in 15 until 26) pm.drawPixel(i, 30 - i, crack)
    }

    private fun drawMossyCryptFloor(pm: TileImage) {
        base.drawCryptFloorTile(pm, 0, 0)
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val n = base.wrappedFbm(px.toFloat(), py.toFloat(), octaves = 3, s = seed + 5100L)
                if (n > 0.68f) pm.drawPixel(px, py, base.grassShadow)
                else if (n > 0.76f) pm.drawPixel(px, py, base.grassBase)
            }
        }
    }

    private fun drawBloodStainedFloor(pm: TileImage) {
        base.drawCryptFloorTile(pm, 0, 0)
        val crimson = rgba(127, 29, 29)
        val bright = rgba(153, 27, 27)
        fillCircle(pm, 14, 16, 6, crimson)
        fillCircle(pm, 19, 19, 3, bright)
        pm.drawPixel(9, 12, crimson)
        pm.drawPixel(23, 14, crimson)
    }

    private fun drawMossyBrickWall(pm: TileImage) {
        base.drawDungeonWallTile(pm, 0, 0)
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val n = base.wrappedFbm(px.toFloat(), py.toFloat(), octaves = 2, s = seed + 5200L)
                if (n > 0.66f) pm.drawPixel(px, py, base.grassShadow)
            }
        }
    }

    private fun drawRuinedBrickWall(pm: TileImage) {
        base.drawDungeonWallTile(pm, 0, 0)
        val rubble = rgba(45, 48, 56)
        val pit = rgba(16, 18, 24)
        fillRect(pm, 0, 0, 32, 6, pit)
        fillRect(pm, 6, 4, 8, 4, rubble)
        fillRect(pm, 18, 3, 9, 5, rubble)
    }

    private fun drawCarvedPillar(pm: TileImage) {
        base.drawCryptFloorTile(pm, 0, 0)
        fillRect(pm, 8, 4, 16, 24, base.stoneDark)
        fillRect(pm, 10, 6, 12, 20, base.stoneBase)
        fillRect(pm, 11, 7, 4, 18, base.stoneLight)
        fillRect(pm, 7, 3, 18, 3, base.stoneBevel)
        fillRect(pm, 7, 26, 18, 3, base.stoneDark)
    }

    private fun drawIronGrate(pm: TileImage) {
        val abyss = rgba(15, 17, 22)
        val iron = rgba(82, 82, 91)
        val sheen = rgba(161, 161, 170)
        fillRect(pm, 0, 0, 32, 32, abyss)
        for (i in 0 until 32 step 6) {
            fillRect(pm, i, 0, 2, 32, iron)
            fillRect(pm, 0, i, 32, 2, iron)
            pm.drawPixel(i, i, sheen)
        }
    }

    private fun drawChasm(pm: TileImage) {
        val edge = rgba(38, 42, 52)
        val void = rgba(10, 12, 16)
        fillRect(pm, 0, 0, 32, 32, void)
        for (i in 0 until 32) {
            pm.drawPixel(i, 0, edge)
            pm.drawPixel(i, 1, edge)
        }
    }

    private fun drawLavaPool(pm: TileImage) {
        val crust = rgba(127, 29, 29)
        val magma = rgba(220, 38, 38)
        val orange = rgba(249, 115, 22)
        val yellow = rgba(253, 224, 71)
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val n = base.wrappedFbm(px.toFloat(), py.toFloat(), octaves = 3, s = seed + 5300L)
                val col = when {
                    n < 0.30f -> crust
                    n < 0.58f -> magma
                    n < 0.80f -> orange
                    else -> yellow
                }
                pm.drawPixel(px, py, col)
            }
        }
    }

    private fun drawDarkWater(pm: TileImage) {
        val deep = rgba(15, 23, 42)
        val mid = rgba(30, 41, 59)
        val ripple = rgba(51, 65, 85)
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val n = base.wrappedFbm(px.toFloat(), py.toFloat(), octaves = 2, s = seed + 5400L)
                val col = when {
                    n < 0.45f -> deep
                    n < 0.78f -> mid
                    else -> ripple
                }
                pm.drawPixel(px, py, col)
            }
        }
    }

    private fun drawStairs(pm: TileImage, down: Boolean) {
        val border = base.stoneDark
        fillRect(pm, 0, 0, 32, 32, border)
        for (step in 0 until 6) {
            val y = 4 + step * 4
            val shade = if (down) 140 - step * 18 else 50 + step * 18
            val col = rgba(shade, shade + 4, shade + 10)
            fillRect(pm, 4, y, 24, 3, col)
        }
    }

    private fun drawSpikeTrap(pm: TileImage) {
        base.drawCryptFloorTile(pm, 0, 0)
        val hole = rgba(15, 17, 22)
        val steel = rgba(212, 212, 216)
        listOf(8 to 8, 22 to 8, 8 to 22, 22 to 22, 15 to 15).forEach { (sx, sy) ->
            fillRect(pm, sx - 1, sy, 3, 3, hole)
            pm.drawPixel(sx, sy - 2, steel)
            pm.drawPixel(sx, sy - 1, steel)
            pm.drawPixel(sx - 1, sy, steel)
            pm.drawPixel(sx + 1, sy, steel)
        }
    }

    private fun drawDungeonDoor(pm: TileImage) {
        base.drawDungeonWallTile(pm, 0, 0)
        val iron = rgba(82, 82, 91)
        fillRect(pm, 7, 4, 18, 26, base.woodDark)
        fillRect(pm, 9, 6, 14, 24, base.woodBase)
        fillRect(pm, 9, 11, 14, 2, iron)
        fillRect(pm, 9, 21, 14, 2, iron)
        fillRect(pm, 20, 15, 2, 3, rgba(250, 204, 21))
    }

    private fun drawIronGate(pm: TileImage) {
        base.drawCryptFloorTile(pm, 0, 0)
        val iron = rgba(82, 82, 91)
        val highlight = rgba(161, 161, 170)
        fillRect(pm, 2, 4, 28, 3, iron)
        fillRect(pm, 2, 24, 28, 3, iron)
        for (x in 4 until 29 step 5) {
            fillRect(pm, x, 4, 2, 23, iron)
            pm.drawPixel(x, 8, highlight)
        }
    }

    private fun drawStoneArchway(pm: TileImage) {
        base.drawCryptFloorTile(pm, 0, 0)
        fillRect(pm, 2, 2, 28, 5, base.stoneBase)
        fillRect(pm, 2, 2, 5, 28, base.stoneDark)
        fillRect(pm, 25, 2, 5, 28, base.stoneDark)
        fillRect(pm, 4, 3, 24, 2, base.stoneBevel)
    }

    private fun drawBrazier(pm: TileImage, lit: Boolean) {
        base.drawCryptFloorTile(pm, 0, 0)
        val iron = rgba(82, 82, 91)
        fillRect(pm, 10, 16, 12, 10, iron)
        fillRect(pm, 8, 14, 16, 3, iron)
        if (lit) {
            fillCircle(pm, 16, 11, 5, rgba(249, 115, 22))
            fillCircle(pm, 16, 12, 3, rgba(253, 224, 71))
        } else {
            fillRect(pm, 11, 13, 10, 2, rgba(39, 39, 42))
        }
    }

    private fun drawChest(pm: TileImage, open: Boolean) {
        base.drawCryptFloorTile(pm, 0, 0)
        val gold = rgba(250, 204, 21)
        fillRect(pm, 6, 11, 20, 14, base.woodBase)
        fillRect(pm, 6, 11, 20, 2, gold)
        fillRect(pm, 6, 23, 20, 2, gold)
        if (open) {
            fillRect(pm, 6, 7, 20, 5, base.woodDark)
            fillRect(pm, 8, 13, 16, 6, gold)
        } else {
            fillRect(pm, 14, 15, 4, 4, gold)
        }
    }

    private fun drawBarrel(pm: TileImage) {
        base.drawCryptFloorTile(pm, 0, 0)
        val iron = rgba(113, 113, 122)
        fillRect(pm, 9, 7, 14, 19, base.woodBase)
        fillRect(pm, 11, 7, 4, 19, base.woodLight)
        fillRect(pm, 9, 11, 14, 2, iron)
        fillRect(pm, 9, 20, 14, 2, iron)
    }

    private fun drawDungeonCrate(pm: TileImage) {
        base.drawCryptFloorTile(pm, 0, 0)
        fillRect(pm, 6, 6, 20, 20, base.woodDark)
        fillRect(pm, 8, 8, 16, 16, base.woodBase)
        for (i in 0 until 15) pm.drawPixel(8 + i, 8 + i, base.woodDark)
    }

    private fun drawBonePile(pm: TileImage) {
        base.drawCryptFloorTile(pm, 0, 0)
        val bone = rgba(229, 229, 229)
        val shade = rgba(163, 163, 163)
        fillCircle(pm, 15, 16, 4, bone)
        pm.drawPixel(13, 16, shade)
        pm.drawPixel(17, 16, shade)
        fillRect(pm, 8, 21, 12, 2, bone)
        fillRect(pm, 14, 19, 10, 2, shade)
    }

    private fun drawCobweb(pm: TileImage) {
        base.drawCryptFloorTile(pm, 0, 0)
        val web = rgba(212, 212, 216)
        for (i in 0 until 24) {
            pm.drawPixel(i, i / 2, web)
            pm.drawPixel(i / 2, i, web)
            pm.drawPixel(i, i, web)
        }
        for (r in listOf(6, 12, 18)) {
            for (a in 0..r) pm.drawPixel(a, r - a, web)
        }
    }

    private fun drawRunicPortal(pm: TileImage) {
        base.drawCryptFloorTile(pm, 0, 0)
        val outer = rgba(126, 34, 206)
        val ring = rgba(192, 132, 252)
        val core = rgba(243, 232, 255)
        for (py in 0 until tileSize) {
            for (px in 0 until tileSize) {
                val dx = px - 16
                val dy = py - 16
                val d = sqrt((dx * dx + dy * dy).toFloat())
                if (d <= 12f) pm.drawPixel(px, py, outer)
                if (d in 8f..10f) pm.drawPixel(px, py, ring)
                if (d <= 4f) pm.drawPixel(px, py, core)
            }
        }
    }

    private fun drawStoneAltar(pm: TileImage) {
        base.drawCryptFloorTile(pm, 0, 0)
        fillRect(pm, 5, 9, 22, 15, base.stoneDark)
        fillRect(pm, 6, 10, 20, 11, base.stoneBase)
        fillRect(pm, 7, 11, 18, 3, base.stoneBevel)
        fillRect(pm, 12, 14, 8, 4, rgba(185, 28, 28))
    }

    private fun drawBookshelf(pm: TileImage) {
        base.drawDungeonWallTile(pm, 0, 0)
        fillRect(pm, 4, 3, 24, 27, base.woodDark)
        fillRect(pm, 6, 5, 20, 23, base.woodBase)
        val colors = listOf(rgba(185, 28, 28), rgba(29, 78, 216), rgba(21, 128, 61), rgba(180, 83, 9))
        for (shelfY in listOf(6, 14, 22)) {
            fillRect(pm, 6, shelfY + 5, 20, 2, base.woodDark)
            for (bx in 7 until 25 step 3) {
                val col = colors[(bx + shelfY) % colors.size]
                fillRect(pm, bx, shelfY, 2, 5, col)
            }
        }
    }

    private fun drawFloorSwitch(pm: TileImage) {
        base.drawCryptFloorTile(pm, 0, 0)
        fillRect(pm, 9, 9, 14, 14, base.stoneDark)
        fillRect(pm, 11, 11, 10, 10, base.stoneBevel)
        fillRect(pm, 14, 14, 4, 4, rgba(234, 88, 12))
    }

    private fun drawCrimsonCarpet(pm: TileImage) {
        base.drawCryptFloorTile(pm, 0, 0)
        val border = rgba(250, 204, 21)
        val carpet = rgba(153, 27, 27)
        val inner = rgba(185, 28, 28)
        fillRect(pm, 6, 0, 20, 32, border)
        fillRect(pm, 8, 0, 16, 32, carpet)
        fillRect(pm, 10, 0, 12, 32, inner)
    }

    // =========================================================================
    // SPACESHIP THEME (32 tiles: 2 rows x 16 columns)
    // =========================================================================

    // Cool blue-grey hull metals, cyan status lights, amber hazard markings, red alerts.
    private val deckSeam = rgba(22, 26, 36)
    private val deckDark = rgba(44, 51, 66)
    private val deckBase = rgba(72, 82, 100)
    private val deckMid = rgba(88, 99, 119)
    private val deckLight = rgba(118, 131, 154)
    private val deckEdge = rgba(158, 172, 196)
    private val hullDark = rgba(32, 37, 50)
    private val hullBase = rgba(56, 64, 82)
    private val hullLight = rgba(84, 95, 118)
    private val spaceBlack = rgba(5, 6, 16)
    private val starWhite = rgba(240, 246, 255)
    private val starBlue = rgba(147, 170, 220)
    private val cyan = rgba(34, 211, 238)
    private val cyanDim = rgba(14, 116, 144)
    private val cyanGlow = rgba(186, 246, 253)
    private val amber = rgba(250, 204, 21)
    private val alertRed = rgba(239, 68, 68)
    private val redDark = rgba(127, 29, 29)
    private val signalGreen = rgba(74, 222, 128)
    private val screenDark = rgba(8, 30, 44)

    /**
     * Smooth cloud noise in 0..1 that still wraps every 32 px: octaves on 8, 4 and 2 px lattices, so it reads as
     * gas or liquid rather than per-pixel static.
     */
    private fun cloud(px: Int, py: Int, s: Long): Float {
        var total = 0f
        var weight = 0f
        var amp = 1f
        for ((i, cell) in intArrayOf(8, 4, 2).withIndex()) {
            total += base.wrappedNoise2D(px / cell.toFloat(), py / cell.toFloat(), tileSize / cell, s + i * 97L) * amp
            weight += amp
            amp *= 0.5f
        }
        return total / weight
    }

    private fun generateSpaceshipTiles(): List<NamedTile> = listOf(
        // Row 0: Deck floors, hull walls, space and doors
        tile("deck_plating") { pm -> drawDeckPlating(pm) },
        tile("tread_plate") { pm -> drawTreadPlate(pm) },
        tile("grated_catwalk") { pm -> drawCatwalkGrate(pm) },
        tile("lit_deck_strip") { pm -> drawLitDeckStrip(pm) },
        tile("hazard_stripe_floor") { pm -> drawHazardStripes(pm) },
        tile("hull_wall") { pm -> drawHullWall(pm) },
        tile("hull_wall_vent") { pm -> drawHullVent(pm) },
        tile("hull_viewport") { pm -> drawViewport(pm) },
        tile("conduit_wall") { pm -> drawConduitWall(pm) },
        tile("bulkhead_pillar") { pm -> drawBulkheadPillar(pm) },
        tile("open_space") { pm -> drawStarfield(pm, seed + 6100L) },
        tile("nebula_space") { pm -> drawNebula(pm) },
        tile("coolant_pool") { pm -> drawCoolantPool(pm) },
        tile("hull_breach") { pm -> drawHullBreach(pm) },
        tile("airlock_closed") { pm -> drawAirlock(pm, open = false) },
        tile("airlock_open") { pm -> drawAirlock(pm, open = true) },

        // Row 1: Consoles, machinery, crew quarters and props
        tile("control_console") { pm -> drawControlConsole(pm) },
        tile("pilot_seat") { pm -> drawPilotSeat(pm) },
        tile("reactor_core") { pm -> drawReactorCore(pm) },
        tile("server_rack") { pm -> drawServerRack(pm) },
        tile("holo_table") { pm -> drawHoloTable(pm) },
        tile("teleporter_pad") { pm -> drawTeleporterPad(pm) },
        tile("lift_pad") { pm -> drawLiftPad(pm) },
        tile("cryo_pod") { pm -> drawCryoPod(pm) },
        tile("crew_bunk") { pm -> drawCrewBunk(pm) },
        tile("med_bay_bed") { pm -> drawMedBed(pm) },
        tile("cargo_crate") { pm -> drawCargoCrate(pm) },
        tile("fuel_canister") { pm -> drawFuelCanister(pm) },
        tile("hydroponics_tray") { pm -> drawHydroponics(pm) },
        tile("alert_beacon") { pm -> drawAlertBeacon(pm) },
        tile("sparking_wires") { pm -> drawSparkingWires(pm) },
        tile("access_hatch") { pm -> drawAccessHatch(pm) }
    )

    /** Two-by-two bevelled steel panels with corner rivets; seams sit on 0 and 16 so the tile repeats cleanly. */
    private fun drawDeckPlating(pm: TileImage) {
        for (py in 0 until tileSize) for (px in 0 until tileSize) {
            val n = base.wrappedFbm(px.toFloat(), py.toFloat(), octaves = 2, s = seed + 6000L)
            pm.drawPixel(px, py, if (n > 0.62f) deckMid else deckBase)
        }
        for (o in listOf(0, 16)) {
            for (i in 0 until tileSize) {
                pm.drawPixel(o, i, deckSeam)
                pm.drawPixel(i, o, deckSeam)
                pm.drawPixel(o + 1, i, deckLight)
                pm.drawPixel(i, o + 1, deckLight)
                pm.drawPixel(o + 15, i, deckDark)
                pm.drawPixel(i, o + 15, deckDark)
            }
        }
        for (ox in listOf(0, 16)) for (oy in listOf(0, 16)) {
            for ((rx, ry) in listOf(3 to 3, 12 to 3, 3 to 12, 12 to 12)) {
                pm.drawPixel(ox + rx, oy + ry, deckEdge)
                pm.drawPixel(ox + rx + 1, oy + ry + 1, deckDark)
            }
        }
    }

    private fun drawTreadPlate(pm: TileImage) {
        fillRect(pm, 0, 0, 32, 32, deckBase)
        for (cy in 0 until tileSize step 8) for (cx in 0 until tileSize step 8) {
            val diag = ((cx + cy) / 8) % 2 == 0
            for (i in 0 until 4) {
                val x = cx + 2 + i
                val y = if (diag) cy + 2 + i else cy + 5 - i
                pm.drawPixel(x, y, deckEdge)
                pm.drawPixel(x, y + 1, deckDark)
            }
        }
    }

    private fun drawCatwalkGrate(pm: TileImage) {
        fillRect(pm, 0, 0, 32, 32, spaceBlack)
        for (py in 0 until tileSize) for (px in 0 until tileSize) {
            if (cloud(px, py, seed + 6200L) > 0.58f) pm.drawPixel(px, py, cyanDim)
        }
        for (i in 0 until tileSize step 4) {
            fillRect(pm, i, 0, 1, 32, deckMid)
            fillRect(pm, 0, i, 32, 1, deckLight)
        }
        fillRect(pm, 0, 0, 32, 2, deckDark)
        fillRect(pm, 0, 30, 32, 2, deckDark)
    }

    private fun drawLitDeckStrip(pm: TileImage) {
        drawDeckPlating(pm)
        fillRect(pm, 0, 13, 32, 6, deckSeam)
        fillRect(pm, 0, 14, 32, 4, cyanDim)
        fillRect(pm, 0, 15, 32, 2, cyan)
        for (x in 2 until tileSize step 8) fillRect(pm, x, 15, 3, 2, cyanGlow)
    }

    private fun drawHazardStripes(pm: TileImage) {
        // Diagonal period of 16 px divides 32, so the stripes continue across tile edges.
        for (py in 0 until tileSize) for (px in 0 until tileSize) {
            pm.drawPixel(px, py, if (((px + py) / 8) % 2 == 0) amber else deckSeam)
        }
        for (py in 0 until tileSize) for (px in 0 until tileSize) {
            if (hash(px, py, seed + 6300L) > 0.93f) pm.drawPixel(px, py, deckDark)
        }
    }

    /** Vertical hull panels with a horizontal trim band; the base for every wall-mounted tile. */
    private fun drawHullWall(pm: TileImage) {
        fillRect(pm, 0, 0, 32, 32, hullBase)
        for (x in listOf(0, 16)) {
            fillRect(pm, x, 0, 1, 32, hullDark)
            fillRect(pm, x + 1, 0, 1, 32, hullLight)
        }
        fillRect(pm, 0, 22, 32, 4, hullDark)
        fillRect(pm, 0, 22, 32, 1, hullLight)
        for (x in listOf(4, 12, 20, 28)) pm.drawPixel(x, 24, deckEdge)
        fillRect(pm, 0, 30, 32, 2, deckSeam)
    }

    private fun drawHullVent(pm: TileImage) {
        drawHullWall(pm)
        fillRect(pm, 6, 4, 20, 15, hullDark)
        for (y in 6 until 18 step 3) {
            fillRect(pm, 7, y, 18, 1, deckSeam)
            fillRect(pm, 7, y + 1, 18, 1, hullLight)
        }
    }

    private fun drawViewport(pm: TileImage) {
        drawHullWall(pm)
        val window = TileImage(tileSize, tileSize).also { drawStarfield(it, seed + 6400L) }
        fillRect(pm, 3, 3, 26, 17, deckEdge)
        fillRect(pm, 4, 4, 24, 15, hullDark)
        pm.drawImage(window, 5, 5, 22, 13, 5, 5)
        for (i in 0 until 5) pm.drawPixel(7 + i, 6 + i / 2, cyanGlow)
    }

    private fun drawConduitWall(pm: TileImage) {
        drawHullWall(pm)
        for ((y, col) in listOf(5 to cyanDim, 11 to redDark, 16 to deckMid)) {
            fillRect(pm, 0, y, 32, 4, col)
            fillRect(pm, 0, y, 32, 1, deckEdge)
            for (x in listOf(8, 24)) fillRect(pm, x, y - 1, 2, 6, deckDark)
        }
        pm.drawPixel(14, 6, cyanGlow)
    }

    private fun drawBulkheadPillar(pm: TileImage) {
        drawDeckPlating(pm)
        fillRect(pm, 7, 0, 18, 32, hullDark)
        fillRect(pm, 9, 0, 14, 32, hullBase)
        fillRect(pm, 10, 0, 3, 32, hullLight)
        for (y in listOf(6, 25)) {
            fillRect(pm, 7, y, 18, 2, deckEdge)
            fillRect(pm, 7, y + 2, 18, 1, deckSeam)
        }
        fillRect(pm, 15, 12, 2, 8, cyan)
    }

    private fun drawStarfield(pm: TileImage, s: Long) {
        fillRect(pm, 0, 0, 32, 32, spaceBlack)
        for (py in 0 until tileSize) for (px in 0 until tileSize) {
            val h = hash(px, py, s)
            if (h > 0.988f) pm.drawPixel(px, py, starWhite)
            else if (h > 0.972f) pm.drawPixel(px, py, starBlue)
        }
    }

    private fun drawNebula(pm: TileImage) {
        val deepViolet = rgba(30, 14, 60)
        val violet = rgba(76, 29, 149)
        val magenta = rgba(157, 23, 77)
        for (py in 0 until tileSize) for (px in 0 until tileSize) {
            val n = cloud(px, py, seed + 6500L)
            pm.drawPixel(px, py, when {
                n < 0.42f -> spaceBlack
                n < 0.52f -> deepViolet
                n < 0.64f -> violet
                else -> magenta
            })
        }
        for (py in 0 until tileSize) for (px in 0 until tileSize) {
            if (hash(px, py, seed + 6510L) > 0.985f) pm.drawPixel(px, py, starWhite)
        }
    }

    private fun drawCoolantPool(pm: TileImage) {
        val deep = rgba(8, 51, 68)
        for (py in 0 until tileSize) for (px in 0 until tileSize) {
            val n = cloud(px, py, seed + 6600L)
            pm.drawPixel(px, py, when {
                n < 0.38f -> deep
                n < 0.55f -> cyanDim
                n < 0.70f -> cyan
                else -> cyanGlow
            })
        }
    }

    private fun drawHullBreach(pm: TileImage) {
        drawDeckPlating(pm)
        val hole = TileImage(tileSize, tileSize).also { drawStarfield(it, seed + 6700L) }
        for (py in 0 until tileSize) for (px in 0 until tileSize) {
            val dx = px - 16f
            val dy = py - 16f
            val jag = hash(px / 3, py / 3, seed + 6710L) * 3f
            val d = sqrt(dx * dx + dy * dy)
            when {
                d < 8f + jag -> pm.drawPixel(px, py, hole.getPixel(px, py))
                d < 10f + jag -> pm.drawPixel(px, py, if ((px + py) % 2 == 0) amber else alertRed)
                d < 11.5f + jag -> pm.drawPixel(px, py, deckSeam)
            }
        }
    }

    private fun drawAirlock(pm: TileImage, open: Boolean) {
        drawHullWall(pm)
        fillRect(pm, 3, 2, 26, 28, deckSeam)
        if (open) {
            fillRect(pm, 5, 4, 22, 26, deckDark)
            fillRect(pm, 3, 2, 4, 28, hullLight)
            fillRect(pm, 25, 2, 4, 28, hullLight)
            for (x in 7 until 25 step 4) fillRect(pm, x, 28, 2, 2, cyan)
            fillRect(pm, 13, 1, 6, 2, signalGreen)
        } else {
            fillRect(pm, 4, 3, 12, 27, hullLight)
            fillRect(pm, 16, 3, 12, 27, hullLight)
            fillRect(pm, 15, 3, 2, 27, deckSeam)
            for (y in 5 until 28 step 6) {
                fillRect(pm, 4, y, 11, 2, if ((y / 6) % 2 == 0) amber else deckSeam)
                fillRect(pm, 17, y, 11, 2, if ((y / 6) % 2 == 0) amber else deckSeam)
            }
            fillRect(pm, 13, 1, 6, 2, alertRed)
        }
    }

    private fun drawScreen(pm: TileImage, x: Int, y: Int, w: Int, h: Int) {
        fillRect(pm, x, y, w, h, screenDark)
        for (row in y + 1 until y + h - 1 step 2) {
            val len = 2 + (hash(x, row, seed + 6800L) * (w - 4)).toInt()
            fillRect(pm, x + 1, row, len, 1, cyan)
        }
        pm.drawPixel(x + w - 2, y + 1, signalGreen)
    }

    private fun drawControlConsole(pm: TileImage) {
        drawDeckPlating(pm)
        fillRect(pm, 3, 8, 26, 18, hullDark)
        fillRect(pm, 4, 9, 24, 16, hullBase)
        drawScreen(pm, 6, 10, 20, 8)
        val buttons = listOf(alertRed, amber, signalGreen, cyan)
        for (i in 0 until 6) fillRect(pm, 6 + i * 3, 20, 2, 2, buttons[i % buttons.size])
        fillRect(pm, 24, 20, 2, 3, deckEdge)
    }

    private fun drawPilotSeat(pm: TileImage) {
        drawDeckPlating(pm)
        val seat = rgba(51, 65, 85)
        val cushion = rgba(30, 41, 59)
        fillRect(pm, 9, 5, 14, 12, seat)
        fillRect(pm, 11, 7, 10, 8, cushion)
        fillRect(pm, 7, 15, 18, 9, seat)
        fillRect(pm, 9, 17, 14, 5, cushion)
        fillRect(pm, 5, 14, 3, 8, hullLight)
        fillRect(pm, 24, 14, 3, 8, hullLight)
        pm.drawPixel(6, 15, amber)
        pm.drawPixel(25, 15, cyan)
        fillRect(pm, 15, 24, 2, 5, deckDark)
    }

    private fun drawReactorCore(pm: TileImage) {
        drawDeckPlating(pm)
        for (py in 0 until tileSize) for (px in 0 until tileSize) {
            val dx = px - 16
            val dy = py - 16
            val d = sqrt((dx * dx + dy * dy).toFloat())
            when {
                d <= 4f -> pm.drawPixel(px, py, starWhite)
                d <= 7f -> pm.drawPixel(px, py, cyanGlow)
                d <= 10f -> pm.drawPixel(px, py, cyan)
                d <= 12f -> pm.drawPixel(px, py, hullDark)
                d <= 14f -> pm.drawPixel(px, py, if ((abs(dx) + abs(dy)) % 4 < 2) amber else deckSeam)
            }
        }
    }

    private fun drawServerRack(pm: TileImage) {
        drawHullWall(pm)
        fillRect(pm, 5, 1, 22, 30, deckSeam)
        fillRect(pm, 6, 2, 20, 28, hullDark)
        for (y in 4 until 28 step 4) {
            fillRect(pm, 7, y, 18, 3, hullBase)
            fillRect(pm, 7, y, 18, 1, hullLight)
            for (x in 9 until 23 step 3) {
                val h = hash(x, y, seed + 6900L)
                if (h > 0.4f) pm.drawPixel(x, y + 1, if (h > 0.85f) alertRed else if (h > 0.6f) signalGreen else cyan)
            }
        }
    }

    private fun drawHoloTable(pm: TileImage) {
        drawDeckPlating(pm)
        fillRect(pm, 5, 20, 22, 8, hullDark)
        fillRect(pm, 6, 20, 20, 2, hullLight)
        fillRect(pm, 8, 22, 16, 1, cyan)
        // Projected planet (cyan wireframe) above the table.
        for (py in 2 until 20) for (px in 6 until 26) {
            val dx = px - 16
            val dy = py - 10
            val d2 = dx * dx + dy * dy
            if (d2 in 40..56) pm.drawPixel(px, py, cyan)
            else if (d2 < 40 && (px % 3 == 0 || py % 3 == 0)) pm.drawPixel(px, py, cyanDim)
        }
        for (x in 9 until 24) pm.drawPixel(x, 10, cyanGlow)
    }

    private fun drawTeleporterPad(pm: TileImage) {
        drawDeckPlating(pm)
        for (py in 0 until tileSize) for (px in 0 until tileSize) {
            val dx = px - 16
            val dy = py - 16
            val d = sqrt((dx * dx + dy * dy).toFloat())
            when {
                d <= 5f -> pm.drawPixel(px, py, cyanGlow)
                d <= 8f -> pm.drawPixel(px, py, if (((dx + 16) / 2 + (dy + 16) / 2) % 2 == 0) cyan else cyanDim)
                d <= 11f -> pm.drawPixel(px, py, hullLight)
                d <= 13f -> pm.drawPixel(px, py, hullDark)
            }
        }
        for ((x, y) in listOf(16 to 4, 16 to 27, 4 to 16, 27 to 16)) fillRect(pm, x - 1, y - 1, 2, 2, amber)
    }

    private fun drawLiftPad(pm: TileImage) {
        drawDeckPlating(pm)
        fillRect(pm, 3, 3, 26, 26, deckSeam)
        fillRect(pm, 4, 4, 24, 24, hullBase)
        for (i in 0 until 24) {
            pm.drawPixel(4 + i, 4, if ((i / 3) % 2 == 0) amber else deckSeam)
            pm.drawPixel(4 + i, 27, if ((i / 3) % 2 == 0) amber else deckSeam)
            pm.drawPixel(4, 4 + i, if ((i / 3) % 2 == 0) amber else deckSeam)
            pm.drawPixel(27, 4 + i, if ((i / 3) % 2 == 0) amber else deckSeam)
        }
        // Up and down arrows.
        for (i in 0 until 4) {
            fillRect(pm, 16 - i, 8 + i, 1 + 2 * i, 1, signalGreen)
            fillRect(pm, 16 - i, 23 - i, 1 + 2 * i, 1, signalGreen)
        }
        fillRect(pm, 15, 12, 3, 8, signalGreen)
    }

    private fun drawCryoPod(pm: TileImage) {
        drawDeckPlating(pm)
        val frost = rgba(207, 250, 254)
        fillRect(pm, 8, 2, 16, 28, hullLight)
        fillRect(pm, 9, 3, 14, 26, hullDark)
        fillRect(pm, 10, 5, 12, 20, cyanDim)
        for (py in 5 until 25) for (px in 10 until 22) {
            if (hash(px, py, seed + 7000L) > 0.8f) pm.drawPixel(px, py, frost)
        }
        fillRect(pm, 13, 8, 6, 5, rgba(148, 163, 184)) // the sleeper's head behind the glass
        fillRect(pm, 11, 5, 2, 20, cyanGlow)
        fillRect(pm, 12, 26, 8, 2, screenDark)
        pm.drawPixel(13, 26, signalGreen)
        pm.drawPixel(15, 26, cyan)
    }

    private fun drawCrewBunk(pm: TileImage) {
        drawDeckPlating(pm)
        val blanket = rgba(30, 64, 175)
        val blanketLight = rgba(59, 130, 246)
        val pillow = rgba(226, 232, 240)
        fillRect(pm, 6, 3, 20, 27, hullLight)
        fillRect(pm, 7, 4, 18, 25, hullDark)
        fillRect(pm, 8, 5, 16, 6, pillow)
        fillRect(pm, 8, 12, 16, 16, blanket)
        for (y in 14 until 28 step 4) fillRect(pm, 8, y, 16, 1, blanketLight)
        fillRect(pm, 22, 6, 1, 1, cyan)
    }

    private fun drawMedBed(pm: TileImage) {
        drawDeckPlating(pm)
        val sheet = rgba(241, 245, 249)
        fillRect(pm, 6, 6, 20, 22, deckEdge)
        fillRect(pm, 7, 7, 18, 20, sheet)
        fillRect(pm, 7, 7, 18, 5, rgba(203, 213, 225))
        // Red cross and a heartbeat monitor strip.
        fillRect(pm, 15, 15, 2, 8, alertRed)
        fillRect(pm, 12, 18, 8, 2, alertRed)
        fillRect(pm, 6, 1, 20, 4, screenDark)
        val pulse = intArrayOf(3, 3, 3, 2, 1, 3, 3, 2, 3, 3, 3, 3, 2, 1, 3, 3, 2, 3, 3, 3)
        for (i in pulse.indices) pm.drawPixel(6 + i, 1 + pulse[i], signalGreen)
    }

    private fun drawCargoCrate(pm: TileImage) {
        drawDeckPlating(pm)
        val olive = rgba(77, 92, 61)
        val oliveLight = rgba(110, 128, 88)
        fillRect(pm, 4, 5, 24, 23, deckSeam)
        fillRect(pm, 5, 6, 22, 21, olive)
        fillRect(pm, 5, 6, 22, 2, oliveLight)
        fillRect(pm, 5, 15, 22, 2, deckSeam)
        fillRect(pm, 8, 10, 7, 3, amber)
        fillRect(pm, 18, 19, 6, 5, starWhite)
        for (y in 20 until 24) fillRect(pm, 19, y, if (y % 2 == 0) 4 else 2, 1, deckSeam) // barcode label
    }

    private fun drawFuelCanister(pm: TileImage) {
        drawDeckPlating(pm)
        fillRect(pm, 10, 4, 12, 24, redDark)
        fillRect(pm, 11, 4, 3, 24, alertRed)
        fillRect(pm, 10, 9, 12, 2, deckSeam)
        fillRect(pm, 10, 21, 12, 2, deckSeam)
        fillRect(pm, 13, 2, 6, 3, deckEdge)
        fillRect(pm, 13, 13, 6, 6, amber)
        pm.drawPixel(16, 15, deckSeam)
        pm.drawPixel(15, 16, deckSeam)
        pm.drawPixel(17, 16, deckSeam)
    }

    private fun drawHydroponics(pm: TileImage) {
        drawDeckPlating(pm)
        val growLight = rgba(216, 180, 254)
        fillRect(pm, 3, 17, 26, 11, hullDark)
        fillRect(pm, 4, 18, 24, 3, rgba(8, 145, 178))
        for (x in listOf(7, 13, 19, 25)) {
            fillRect(pm, x, 9, 1, 9, rgba(22, 163, 74))
            fillCircle(pm, x, 9, 3, rgba(34, 197, 94))
            pm.drawPixel(x - 1, 8, rgba(134, 239, 172))
        }
        fillRect(pm, 3, 2, 26, 2, hullLight)
        fillRect(pm, 4, 4, 24, 1, growLight)
    }

    private fun drawAlertBeacon(pm: TileImage) {
        drawDeckPlating(pm)
        val glow = rgba(254, 202, 202)
        fillCircle(pm, 16, 16, 9, redDark)
        fillCircle(pm, 16, 16, 6, alertRed)
        fillCircle(pm, 14, 14, 2, glow)
        fillRect(pm, 9, 24, 14, 3, hullDark)
        fillRect(pm, 9, 24, 14, 1, hullLight)
    }

    private fun drawSparkingWires(pm: TileImage) {
        drawDeckPlating(pm)
        fillRect(pm, 6, 6, 20, 20, deckSeam)
        fillRect(pm, 7, 7, 18, 18, spaceBlack)
        val wireCols = listOf(alertRed, amber, cyan, signalGreen)
        for ((i, col) in wireCols.withIndex()) {
            for (x in 7 until 25) {
                val y = 10 + i * 4 + ((x + i * 3) / 4) % 2
                pm.drawPixel(x, y, col)
            }
        }
        for ((x, y) in listOf(20 to 8, 21 to 7, 22 to 9, 19 to 6, 23 to 8, 21 to 10)) pm.drawPixel(x, y, starWhite)
        pm.drawPixel(21, 8, amber)
        fillRect(pm, 4, 4, 5, 2, deckLight) // pried-up panel lip
    }

    private fun drawAccessHatch(pm: TileImage) {
        drawDeckPlating(pm)
        fillCircle(pm, 16, 16, 11, deckSeam)
        fillCircle(pm, 16, 16, 10, hullLight)
        fillCircle(pm, 16, 16, 8, hullBase)
        fillRect(pm, 9, 15, 14, 2, deckEdge)
        fillRect(pm, 15, 9, 2, 14, deckEdge)
        fillCircle(pm, 16, 16, 2, amber)
    }
}
