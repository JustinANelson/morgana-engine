package com.morgana.gameart.export

import com.morgana.tilegen.*
import com.morgana.tilegen.material.*
import com.morgana.tilegen.spec.*
import com.morgana.tilegen.sprite.*
import com.morgana.tilegen.structure.*
import com.morgana.tilegen.export.*
import com.morgana.tilegen.autotile.*
import com.morgana.gameart.sprite.*
import com.morgana.gameart.whiteriver.*

import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.spec.PaperDollSpecJson
import com.morgana.tilegen.sprite.AttachmentKind
import com.morgana.tilegen.sprite.Direction8
import com.morgana.tilegen.sprite.FeetStyle
import com.morgana.tilegen.sprite.HairStyle
import com.morgana.gameart.sprite.TrevanaEnemyKind
import com.morgana.gameart.sprite.TrevanaNpcKind
import com.morgana.gameart.sprite.TrevanaSpriteGenerator
import com.morgana.tilegen.sprite.HeadgearKind
import com.morgana.tilegen.sprite.PaperDollAction
import com.morgana.tilegen.sprite.PaperDollJob
import com.morgana.tilegen.sprite.PaperDollLoadout
import com.morgana.tilegen.sprite.PaperDollProportions
import com.morgana.tilegen.sprite.PaperDollSlot
import com.morgana.tilegen.sprite.PaperDollSpriteGenerator
import com.morgana.tilegen.sprite.PaperDollThemes
import com.morgana.tilegen.sprite.ShieldKind
import com.morgana.tilegen.sprite.TorsoStyle
import com.morgana.tilegen.sprite.WeaponKind
import java.nio.file.Files
import java.nio.file.Path

/**
 * Draws Trevana Realms's characters and worn gear straight into a game's `assets/` folder in the engine's sprite layout.
 *
 * It replaces the old Python converter that re-laid TilesetGen's sheets: everything is drawn in memory in the game's own
 * cell grid, so there is no intermediate sheet, no PIL dependency and no second copy of the layout rules.
 *
 * **Layout** (`game.json` `sprites`): 32 x 48 cells, four direction rows (down, left, right, up) and the state columns
 * idle 2, walk 4, attack 4, cast 4, harvest 4, jump 3, hurt 2 = 23 columns, 736 x 192 px. `attack` plays the doll's
 * `SLASH`, or `SHOOT` for the Ranger and the bows; a worn piece uses the same action as the body under it.
 *
 * **What it owns** (and so overwrites): `sprites/1..5` (class bodies, every non-gear layer baked in), `sprites/10..21`
 * (NPCs), `sprites/101..122` (monsters) and `paperdolls/1..21` (worn gear, see [GEAR]). It touches nothing else - no item
 * data, no `game.json`, no other sprite - so hand-drawn art in other ids is safe. Output is deterministic.
 */
object TrevanaGameExporter {
    const val CELL_W = 32
    const val CELL_H = 48

    /** The engine's sprite states in column order: name, doll action, frames. `attack` is replaced per sheet (see [Sheet.attack]). */
    private data class State(val name: String, val action: PaperDollAction, val frames: Int)

    private val STATES = listOf(
        State("idle", PaperDollAction.IDLE, 2),
        State("walk", PaperDollAction.WALK, 4),
        State("attack", PaperDollAction.SLASH, 4),
        State("cast", PaperDollAction.CAST, 4),
        State("harvest", PaperDollAction.HARVEST, 4),
        State("jump", PaperDollAction.JUMP, 3),
        State("hurt", PaperDollAction.HURT, 2)
    )
    val COLUMNS: Int = STATES.sumOf { it.frames }

    /** Engine row order. */
    private val DIRECTIONS = listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)

    /** Class body sprite id -> class spec file stem, and whether its attack column is the bow shot. */
    private val CLASS_BODIES = listOf(
        Triple(1, "duelist", false),
        Triple(2, "bulwark", false),
        Triple(3, "ranger", true),
        Triple(4, "tidecaller", false),
        Triple(5, "emberweaver", false)
    )

    /** Layers left out of a baked class body: gear the player swaps (it comes from items) and the ground shadow. */
    private val GEAR_LAYERS = setOf(
        PaperDollSlot.HEADGEAR, PaperDollSlot.MAIN_HAND, PaperDollSlot.OFF_HAND, PaperDollSlot.AURA_FRONT,
        PaperDollSlot.AURA_BACK, PaperDollSlot.SHADOW, PaperDollSlot.BACK_ATTACHMENT, PaperDollSlot.CAPE
    )

    /** One worn-gear overlay: the slot it fills, how the neutral doll is changed to wear it, and the attack action. */
    class Gear(val id: Int, val slot: PaperDollSlot, val shoot: Boolean = false, val wear: (PaperDollLoadout) -> PaperDollLoadout)

    /** `paperdolls/<id>.png`. Item data (`paperdollSpriteId`) already points at these ids; keep them stable. */
    val GEAR: List<Gear> = listOf(
        Gear(1, PaperDollSlot.MAIN_HAND, shoot = true) { it.copy(weapon = WeaponKind.COMPOSITE_BOW) },
        Gear(2, PaperDollSlot.BACK_ATTACHMENT, shoot = true) { it.copy(attachments = listOf(AttachmentKind.QUIVER)) },
        Gear(3, PaperDollSlot.MAIN_HAND) { it.copy(weapon = WeaponKind.SINGLE_BLADE) },
        Gear(4, PaperDollSlot.OFF_HAND) { it.copy(shield = ShieldKind.OFFHAND_BLADE) },
        Gear(5, PaperDollSlot.MAIN_HAND) { it.copy(weapon = WeaponKind.KNIGHT_SWORD) },
        Gear(6, PaperDollSlot.OFF_HAND) { it.copy(shield = ShieldKind.OFFHAND_SWORD) },
        Gear(7, PaperDollSlot.MAIN_HAND) { it.copy(weapon = WeaponKind.KATANA) },
        Gear(8, PaperDollSlot.OFF_HAND) { it.copy(shield = ShieldKind.OFFHAND_KATANA) },
        Gear(9, PaperDollSlot.MAIN_HAND) { it.copy(weapon = WeaponKind.ARCANE_STAFF) },
        Gear(10, PaperDollSlot.MAIN_HAND) { it.copy(weapon = WeaponKind.PICKAXE) },
        Gear(11, PaperDollSlot.OFF_HAND) { it.copy(shield = ShieldKind.ROUND_BUCKLER) },
        Gear(12, PaperDollSlot.OFF_HAND) { it.copy(shield = ShieldKind.TOWER_SHIELD) },
        Gear(13, PaperDollSlot.OFF_HAND) { it.copy(shield = ShieldKind.DAWNBRAND_SHIELD) },
        Gear(14, PaperDollSlot.HEADGEAR) { it.copy(headgear = HeadgearKind.IRON_HELMET) },
        Gear(15, PaperDollSlot.HEADGEAR) { it.copy(headgear = HeadgearKind.STEEL_HELMET) },
        Gear(16, PaperDollSlot.HEADGEAR) { it.copy(headgear = HeadgearKind.SKYRENDER_HELM) },
        Gear(17, PaperDollSlot.TORSO) { it.copy(torsoStyle = TorsoStyle.TRAVELER) },
        Gear(18, PaperDollSlot.TORSO) { it.copy(torsoStyle = TorsoStyle.BRIGANDINE) },
        Gear(19, PaperDollSlot.TORSO) { it.copy(torsoStyle = TorsoStyle.SKYRENDER) },
        Gear(20, PaperDollSlot.FEET) { it.copy(feetStyle = FeetStyle.BOOTS) },
        Gear(21, PaperDollSlot.FEET) { it.copy(feetStyle = FeetStyle.SABATONS) }
    )

    /** Which groups of sprites to draw. */
    enum class Part { BODIES, GEAR, NPCS, ENEMIES }

    class Result(val written: List<Path>, val uiHeights: Map<Int, Int>)

    /**
     * Draws [parts] into `[gameDir]/assets`, reading the class specs from [specDir] (`trevana_<class>.json`).
     * [uiHeights] in the result is the visible height of each written sprite (top edge of its tallest frame to the cell
     * bottom), for `game.json` `sprites.uiHeights`, so name plates sit on heads; compare it with what the file has.
     */
    fun export(gameDir: Path, specDir: Path, parts: Set<Part> = Part.entries.toSet(), seed: Long = 1337L): Result {
        val spriteDir = gameDir.resolve("assets").resolve("sprites")
        val paperdollDir = gameDir.resolve("assets").resolve("paperdolls")
        Files.createDirectories(spriteDir)
        Files.createDirectories(paperdollDir)
        val written = ArrayList<Path>()
        val heights = linkedMapOf<Int, Int>()

        fun writeSprite(id: Int, sheet: TileImage) {
            val path = spriteDir.resolve("$id.png")
            PngEncoder.writeToFile(sheet, path)
            written.add(path)
            visibleHeight(sheet)?.let { heights[id] = it }
        }

        if (Part.BODIES in parts) {
            for ((id, stem, shoot) in CLASS_BODIES) {
                writeSprite(id, classBody(specDir.resolve("trevana_$stem.json"), shoot))
            }
        }
        if (Part.NPCS in parts) {
            val gen = TrevanaSpriteGenerator(seed = seed, cellWidth = CELL_W, cellHeight = CELL_H)
            for (npc in TrevanaNpcKind.entries) {
                writeSprite(npc.spriteId, sheet(shoot = false) { dir, action, f -> gen.generateNpcFrame(npc, dir, action, f) })
            }
        }
        if (Part.ENEMIES in parts) {
            val gen = TrevanaSpriteGenerator(seed = seed, cellWidth = CELL_W, cellHeight = CELL_H)
            for (enemy in TrevanaEnemyKind.entries) {
                writeSprite(enemy.spriteId, sheet(shoot = false) { dir, action, f -> gen.generateEnemyFrame(enemy, dir, action, f) })
            }
        }
        if (Part.GEAR in parts) {
            val doll = PaperDollSpriteGenerator(seed = seed, tileSize = CELL_W, tileHeight = CELL_H)
            for (gear in GEAR) {
                val loadout = gear.wear(neutralLoadout())
                val img = sheet(gear.shoot) { dir, action, f ->
                    val (layers, _) = doll.generateLayeredFrame(loadout, dir, action, f, PixelStyle.MORGANA)
                    layers[gear.slot] ?: TileImage(CELL_W, CELL_H)
                }
                val path = paperdollDir.resolve("${gear.id}.png")
                PngEncoder.writeToFile(img, path)
                written.add(path)
            }
        }
        return Result(written, heights)
    }

    /** The bare doll every gear overlay is drawn on: the Trevana neutral theme at the game's cell size, in the house style. */
    private fun neutralLoadout(): PaperDollLoadout = PaperDollLoadout(
        job = PaperDollJob.HERO,
        hairStyle = HairStyle.SPIKY_HERO,
        headgear = HeadgearKind.NONE,
        weapon = WeaponKind.NONE,
        shield = ShieldKind.NONE,
        theme = PaperDollThemes.NEUTRAL_THEME.copy(style = PixelStyle.MORGANA, proportions = PaperDollProportions.RMXP_32X48),
        attachments = emptyList(),
        includeCape = false
    )

    /** A class body from its spec: every layer except gear, composited in the doll's per-direction draw order. */
    private fun classBody(spec: Path, shoot: Boolean): TileImage {
        val parsed = PaperDollSpecJson.parse(Files.readString(spec))
        val doll = PaperDollSpriteGenerator(seed = parsed.seed, tileSize = CELL_W, tileHeight = CELL_H)
        val loadout = parsed.toLoadout()
        val style = loadout.effectiveTheme().style
        return sheet(shoot) { dir, action, f ->
            val (layers, _) = doll.generateLayeredFrame(loadout, dir, action, f, style)
            doll.compositeLayers(layers.filterKeys { it !in GEAR_LAYERS }, dir, action, includeShadow = false)
        }
    }

    /** Lays [frame] out in the engine's 23 x 4 grid. */
    private fun sheet(shoot: Boolean, frame: (Direction8, PaperDollAction, Int) -> TileImage): TileImage {
        val img = TileImage(CELL_W * COLUMNS, CELL_H * DIRECTIONS.size)
        var col = 0
        for (state in STATES) {
            val action = if (state.name == "attack" && shoot) PaperDollAction.SHOOT else state.action
            for (f in 0 until state.frames) {
                for ((row, dir) in DIRECTIONS.withIndex()) {
                    img.drawImage(frame(dir, action, f), 0, 0, CELL_W, CELL_H, col * CELL_W, row * CELL_H)
                }
                col++
            }
        }
        return img
    }

    /** Height from the top of the tallest idle or walk frame's pixels to the bottom of the cell, or null for an empty sheet. */
    private fun visibleHeight(sheet: TileImage): Int? {
        var top = CELL_H
        for (y in 0 until sheet.height) {
            val inRow = y % CELL_H
            if (inRow >= top) continue
            for (x in 0 until CELL_W * 6) {
                // Idle and walk frames only (columns 0-5): attack arcs and cast sparkles do not move a name plate.
                // Ground shadows are translucent: only solid pixels count as the body.
                if ((sheet.getPixel(x, y) and 0xFF) >= 160) {
                    top = inRow
                    break
                }
            }
        }
        return if (top >= CELL_H) null else CELL_H - top
    }
}
