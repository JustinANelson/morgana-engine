package com.morgana.tilegen.structure

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelDither
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.material.SpriteFinish
import com.morgana.tilegen.material.TileNoise
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Supported multi-tile and single-tile 3/4-perspective RPG props.
 */
enum class PropKind(val widthTiles: Int, val heightTiles: Int) {
    /** 3x2 lush 3/4-perspective oak tree with layered canopy, centered trunk, and ground shadow. */
    OAK_TREE(3, 2),

    /** 1x2 tiered evergreen pine tree with trunk and shadow. */
    PINE_TREE(1, 2),

    /** 3x1 modular wooden fence (left post, center rail, right post). */
    WOOD_FENCE(3, 1),

    /** 3x1 modular wooden bridge with side railings and planks. */
    WOOD_BRIDGE(3, 1),

    /** 3x2 stone dungeon/castle archway with centered 1-tile-wide opening. */
    STONE_ARCHWAY(3, 2),

    /** 3x2 3/4-perspective cottage with pitched roof, eaves, door, and window. */
    COTTAGE(3, 2),

    /** 1x1 stone-ring campfire with warm flame. */
    CAMPFIRE(1, 1),

    /** 1x1 lush grass tuft cluster for ground dressing. */
    GRASS_TUFT(1, 1),

    /** 1x1 wildflower & clover patch for ground dressing. */
    WILDFLOWER_PATCH(1, 1),

    /** 1x1 rounded leafy bush with ground shadow. */
    BUSH(1, 1),

    /** 1x1 cut tree stump with bark rings and ground shadow. */
    TREE_STUMP(1, 1),

    /** 1x1 reinforced dungeon wooden door with iron bands. */
    WOOD_DOOR_CLOSED(1, 1),

    /** 1x1 open dungeon doorway. */
    WOOD_DOOR_OPEN(1, 1),

    /** 1x1 iron prison cell bars. */
    IRON_BARS(1, 1),

    /** 1x1 wall-mounted flaming torch sconce. */
    TORCH_SCONCE(1, 1),

    /** 1x1 destructible wooden barrel. */
    BARREL(1, 1),

    /** 1x1 iron-bound treasure chest (closed). */
    CHEST_CLOSED(1, 1),

    /** 1x1 looted treasure chest (open). */
    CHEST_OPEN(1, 1),

    /** 1x2 tall 3/4-perspective stone column. */
    PILLAR(1, 2),

    /** 1x2 glowing gothic stone shrine/obelisk. */
    SHRINE(1, 2),

    /** 1x1 descending stone dungeon staircase. */
    STAIRS_DOWN(1, 1),

    /** 1x1 ascending stone dungeon staircase. */
    STAIRS_UP(1, 1),

    /** 1x2 carved stone tomb / sarcophagus. */
    SARCOPHAGUS(1, 2),

    /** 1x2 glowing blue town portal / waypoint. */
    PORTAL(1, 2),

    /** 1x2 tattered gothic wall banner. */
    WALL_BANNER(1, 2),

    /** 1x1 hanging iron wall chains/shackles. */
    WALL_CHAINS(1, 1),

    /** 1x1 stone wall alcove shelf with potions/tome. */
    WALL_SHELF(1, 1),

    /** 1x1 carved stone demon/gargoyle wall relief. */
    GARGOYLE_PLAQUE(1, 1),

    /** 1x1 gleaming pile of gold coins. */
    GOLD_PILE(1, 1),

    /** 1x1 red health potion flask. */
    HEALTH_POTION(1, 1),

    /** 1x1 blue mana potion flask. */
    MANA_POTION(1, 1),

    /** 1x1 tied parchment scroll. */
    SCROLL(1, 1),

    /** 1x1 iron/gold skeleton key. */
    KEY_ITEM(1, 1),

    /** 1x1 cluster of skulls and bones. */
    SKULL_PILE(1, 1),

    /** 1x1 wooden dungeon crate. */
    CRATE(1, 1),

    /** 1x1 breakable clay/stone urn. */
    URN(1, 1),

    /** 1x1 weapon rack with sword and shield. */
    WEAPON_RACK(1, 1),

    /** 1x1 iron floor spike trap. */
    SPIKE_TRAP(1, 1),

    /** 1x1 raised stone floor pressure plate. */
    PRESSURE_PLATE(1, 1),

    // ---- Imperial East Asian props ----

    /** 3x2 upturned pagoda pavilion with crimson lacquer columns, gold eaves, and glazed roof. */
    PAGODA_ROOF(3, 2),

    /** 3x2 ornate Oriental Paifang archway gate with upturned roof tiers and inscribed lintel. */
    PAIFANG_GATE(3, 2),

    /** 1x2 imperial bronze and gold dragon incense brazier with jade flame smoke. */
    DRAGON_BRAZIER(1, 2),

    /** 1x2 carved imperial guardian lion (foo dog) statue on a lotus pedestal. */
    JADE_LION_STATUE(1, 2),

    /** 1x2 segmented jade bamboo stalks and layered leaves with ground shadow. */
    BAMBOO_GROVE(1, 2),

    /** 1x1 hanging crimson silk and gold tassel festival paper lantern. */
    PAPER_LANTERN(1, 1),

    // ---- Dark medieval high-fantasy props ----

    /** 1x2 swirling obsidian and crimson dark-energy awakening altar. */
    DARK_RIFT_ALTAR(1, 2),

    /** 3x2 medieval military command pavilion tent with heraldic crest and iron stakes. */
    MEDIEVAL_TENT(3, 2),

    /** 1x1 iron-bound siege war ballista with tension arms and steel bolt. */
    SIEGE_BALLISTA(1, 1),

    /** 3x2 merchant canvas-covered wooden trade pack wagon with spoked iron-rimmed wheels. */
    TRADE_WAGON(3, 2),

    /** 1x2 gothic dark-iron spire topped with a horned gargoyle. */
    GARGOYLE_SPIRE(1, 2),

    /** 1x2 ancient stone relic mechanism with glowing amber core. */
    ANCIENT_EXCAVATION(1, 2),

    // ---- JRPG and 2.5D town props ----

    /** 3x2 half-timbered JRPG town house with scalloped shingle roof, chimney, cross-braced plaster, and warm glowing paned windows. */
    HALF_TIMBER_HOUSE(3, 2),

    /** 3x2 festive striped market stall with wooden counter, display crates, and merchant wares. */
    MARKET_STALL(3, 2),

    /** 3x2 octagonal town square stone fountain with cascading water basin and glowing crystal centerpiece. */
    RUNE_CRYSTAL_FOUNTAIN(3, 2),

    /** 3x2 cluster-shaded cherry blossom (sakura) tree with gnarled dark trunk, layered petal crowns, and fallen ground petals. */
    CHERRY_BLOSSOM_TREE(3, 2),

    /** 1x2 JRPG wing-crested save-point obelisk with floating mana ring and warm lantern. */
    WAYPOINT_PILLAR(1, 2),

    /** 1x2 gold-crossbar chivalric/guild emblem banner with embroidered crest and swallowtail hem. */
    GUILD_EMBLEM_FLAG(1, 2),

    /** 3x2 Police tactical cruiser with red-blue roof lightbar and headlights. */
    POLICE_CRUISER(3, 2),

    /** 1x2 glowing mainframe CRT terminal and data-spike interface kiosk. */
    DATA_TERMINAL(1, 2),

    /** 1x2 industrial steel toxic-waste vat bubbling with radioactive green sludge. */
    TOXIC_VAT(1, 2),

    /** 3x2 cyberpunk neon street billboard with glowing magenta/cyan signage. */
    NEON_BILLBOARD(3, 2),

    /** 3x1 yellow-and-black striped police sawhorse street barricade. */
    STREET_BARRICADE(3, 1),

    /** 1x2 Urban streetlamp and traffic signal post. */
    TRAFFIC_LIGHT(1, 2),

    // ---- Overworld scenery (WorldProps) ----

    /** 1x2 wooden signpost with two plank boards pointing opposite ways. */
    SIGNPOST(1, 2),

    /** 1x3 tapering stone monolith with glowing rune bands. */
    OBELISK(1, 3),

    /** 1x1 clump of swamp reeds with cattail heads. */
    REED_CLUMP(1, 1),

    /** 1x1 bound sheaf of golden wheat (use a golden foliage palette). */
    WHEAT_SHEAF(1, 1),

    /** 3x1 rope bridge with planks between two sagging ropes. */
    ROPE_BRIDGE(3, 1),

    /** 3x1 wooden pier or quay deck with posts. */
    PIER(3, 1),

    /** 4x3 sailing ship in side view with a square sail. */
    SAILING_SHIP(4, 3),

    /** 2x3 crenellated stone watchtower. */
    WATCH_TOWER(2, 3),

    /** 4x3 gatehouse: two towers, an arched portcullis gate and a banner. */
    GATEHOUSE(4, 3),

    /** 1x1 ore cart on iron wheels. */
    MINE_CART(1, 1),

    /** 2x2 leafless dead tree. */
    DEAD_TREE(2, 2)
}

/**
 * Output of [PropGenerator.generateProp], containing both the full composite image
 * and individual [tileSize]x[tileSize] sliced [NamedTile]s.
 */
data class GeneratedProp(
    val name: String,
    val kind: PropKind,
    val widthTiles: Int,
    val heightTiles: Int,
    val fullImage: TileImage,
    val slicedTiles: List<NamedTile>
)

/**
 * Deterministic generator for multi-tile 3/4-perspective props and structures.
 */
class PropGenerator(
    val seed: Long = 1337L,
    val tileSize: Int = 32
) {
    init {
        require(tileSize >= 8 && tileSize % 2 == 0) {
            "tileSize must be an even integer >= 8, got $tileSize"
        }
    }

    /**
     * Generates a [PropKind] composite and slices it into [NamedTile]s (`<name>_<col>_<row>`).
     */
    fun generateProp(
        name: String,
        kind: PropKind,
        style: PixelStyle = PixelStyle.STANDARD,
        primaryPalette: PaletteRamp? = null,
        secondaryPalette: PaletteRamp? = null
    ): GeneratedProp {
        require(name.isNotBlank()) { "Prop name must not be blank" }
        val w = kind.widthTiles * tileSize
        val h = kind.heightTiles * tileSize
        val canvas = TileImage(w, h)
        val s = seed + name.hashCode().toLong() * 43L

        val foliage = primaryPalette ?: if (style == PixelStyle.CLASSIC_RPG) PalettePresets.CLASSIC_RPG_GRASS else PalettePresets.MEADOW_GRASS
        val wood = secondaryPalette ?: PalettePresets.TIMBER_WOOD
        val stone = primaryPalette ?: if (style == PixelStyle.CLASSIC_RPG) PalettePresets.CLASSIC_RPG_STONE else PalettePresets.SLATE_STONE
        val roof = primaryPalette ?: PalettePresets.TERRACOTTA_BRICK

        val handledByClassic = style == PixelStyle.CLASSIC_RPG && renderClassicRuinsProp(
            canvas, w, h, kind, stone, secondaryPalette, roof
        )
        if (!handledByClassic) {
            when (kind) {
            PropKind.OAK_TREE -> drawOakTree(canvas, w, h, foliage, wood, style, s)
            PropKind.PINE_TREE -> drawPineTree(canvas, w, h, foliage, wood, style, s)
            PropKind.WOOD_FENCE -> drawWoodFence(canvas, w, h, wood, style)
            PropKind.WOOD_BRIDGE -> drawWoodBridge(canvas, w, h, wood, style)
            PropKind.STONE_ARCHWAY -> drawStoneArchway(canvas, w, h, stone, style, s)
            PropKind.COTTAGE -> drawCottage(canvas, w, h, roof, wood, stone, style)
            PropKind.CAMPFIRE -> drawCampfire(canvas, w, h, stone, style, s)
            PropKind.GRASS_TUFT -> drawGrassTuft(canvas, w, h, foliage, s)
            PropKind.WILDFLOWER_PATCH -> drawWildflowerPatch(canvas, w, h, foliage, s)
            PropKind.BUSH -> drawBush(canvas, w, h, foliage, style, s)
            PropKind.TREE_STUMP -> drawTreeStump(canvas, w, h, wood, foliage)
            PropKind.WOOD_DOOR_CLOSED -> drawWoodDoor(canvas, w, h, wood, stone, open = false)
            PropKind.WOOD_DOOR_OPEN -> drawWoodDoor(canvas, w, h, wood, stone, open = true)
            PropKind.IRON_BARS -> drawIronBars(canvas, w, h, stone)
            PropKind.TORCH_SCONCE -> drawTorchSconce(canvas, w, h, stone)
            PropKind.BARREL -> drawBarrel(canvas, w, h, wood, stone)
            PropKind.CHEST_CLOSED -> drawChest(canvas, w, h, wood, stone, open = false)
            PropKind.CHEST_OPEN -> drawChest(canvas, w, h, wood, stone, open = true)
            PropKind.PILLAR -> drawPillar(canvas, w, h, stone, style)
            PropKind.SHRINE -> drawShrine(canvas, w, h, stone)
            PropKind.STAIRS_DOWN -> drawStairs(canvas, w, h, stone, down = true)
            PropKind.STAIRS_UP -> drawStairs(canvas, w, h, stone, down = false)
            PropKind.SARCOPHAGUS -> drawSarcophagus(canvas, w, h, stone)
            PropKind.PORTAL -> drawPortal(canvas, w, h, stone)
            PropKind.WALL_BANNER -> drawWallBanner(canvas, w, h, roof, stone)
            PropKind.WALL_CHAINS -> drawWallChains(canvas, w, h, stone)
            PropKind.WALL_SHELF -> drawWallShelf(canvas, w, h, wood, stone)
            PropKind.GARGOYLE_PLAQUE -> drawGargoylePlaque(canvas, w, h, stone)
            PropKind.GOLD_PILE -> drawGoldPile(canvas, w, h)
            PropKind.HEALTH_POTION -> drawPotion(canvas, w, h, PalettePresets.LAVA_MAGMA, stone)
            PropKind.MANA_POTION -> drawPotion(canvas, w, h, PalettePresets.OCEAN_WATER, stone)
            PropKind.SCROLL -> drawScroll(canvas, w, h, PalettePresets.DESERT_SAND, roof)
            PropKind.KEY_ITEM -> drawKey(canvas, w, h)
            PropKind.SKULL_PILE -> drawSkullPile(canvas, w, h, stone)
            PropKind.CRATE -> drawCrate(canvas, w, h, wood)
            PropKind.URN -> drawUrn(canvas, w, h, roof, stone)
            PropKind.WEAPON_RACK -> drawWeaponRack(canvas, w, h, wood, stone)
            PropKind.SPIKE_TRAP -> drawSpikeTrap(canvas, w, h, stone)
            PropKind.PRESSURE_PLATE -> drawPressurePlate(canvas, w, h, stone)
            PropKind.PAGODA_ROOF -> drawPagodaRoof(canvas, w, h, roof, wood, stone, style)
            PropKind.PAIFANG_GATE -> drawPaifangGate(canvas, w, h, roof, stone, style)
            PropKind.DRAGON_BRAZIER -> drawDragonBrazier(canvas, w, h, stone, foliage)
            PropKind.JADE_LION_STATUE -> drawJadeLionStatue(canvas, w, h, stone, style)
            PropKind.BAMBOO_GROVE -> drawBambooGrove(canvas, w, h, foliage, style, s)
            PropKind.PAPER_LANTERN -> drawPaperLantern(canvas, w, h, roof)
            PropKind.DARK_RIFT_ALTAR -> drawBlackSpiritRift(canvas, w, h, stone, secondaryPalette ?: PalettePresets.RIFT_CRIMSON)
            PropKind.MEDIEVAL_TENT -> drawMedievalTent(canvas, w, h, roof, wood, stone, style)
            PropKind.SIEGE_BALLISTA -> drawSiegeBallista(canvas, w, h, wood, stone)
            PropKind.TRADE_WAGON -> drawTradeWagon(canvas, w, h, roof, wood, stone, style)
            PropKind.GARGOYLE_SPIRE -> drawGargoyleSpire(canvas, w, h, stone, style)
            PropKind.ANCIENT_EXCAVATION -> drawAncientExcavation(canvas, w, h, stone, secondaryPalette ?: PalettePresets.RELIC_AMBER)
            PropKind.HALF_TIMBER_HOUSE -> drawHalfTimberHouse(
                canvas,
                w,
                h,
                primaryPalette ?: PalettePresets.SCARLET_ROOF,
                secondaryPalette ?: PalettePresets.WARM_TIMBER,
                PalettePresets.PALE_LIMESTONE
            )
            PropKind.MARKET_STALL -> drawMarketStall(
                canvas,
                w,
                h,
                primaryPalette ?: PalettePresets.SCARLET_ROOF,
                secondaryPalette ?: PalettePresets.WARM_TIMBER
            )
            PropKind.RUNE_CRYSTAL_FOUNTAIN -> drawRuneCrystalFountain(
                canvas,
                w,
                h,
                primaryPalette ?: PalettePresets.CREAM_MARBLE,
                secondaryPalette ?: PalettePresets.OCEAN_WATER
            )
            PropKind.CHERRY_BLOSSOM_TREE -> drawCherryBlossomTree(
                canvas,
                w,
                h,
                primaryPalette ?: PalettePresets.PASTEL_PINK,
                secondaryPalette ?: PalettePresets.WARM_TIMBER,
                s
            )
            PropKind.WAYPOINT_PILLAR -> drawWaypointPillar(
                canvas,
                w,
                h,
                primaryPalette ?: PalettePresets.CREAM_MARBLE,
                secondaryPalette ?: PalettePresets.RUNE_GOLD
            )
            PropKind.GUILD_EMBLEM_FLAG -> drawGuildEmblemFlag(
                canvas,
                w,
                h,
                primaryPalette ?: PalettePresets.SCARLET_ROOF,
                secondaryPalette ?: PalettePresets.RUNE_GOLD
            )
            PropKind.POLICE_CRUISER -> drawPoliceCruiser(
                canvas,
                w,
                h,
                primaryPalette ?: PalettePresets.GUNMETAL_STEEL,
                secondaryPalette ?: PalettePresets.SIREN_BLUE
            )
            PropKind.DATA_TERMINAL -> drawDataTerminal(
                canvas,
                w,
                h,
                primaryPalette ?: PalettePresets.GUNMETAL_STEEL,
                secondaryPalette ?: PalettePresets.CRT_GREEN
            )
            PropKind.TOXIC_VAT -> drawToxicVat(
                canvas,
                w,
                h,
                primaryPalette ?: PalettePresets.URBAN_ASPHALT,
                secondaryPalette ?: PalettePresets.TOXIC_SLUDGE
            )
            PropKind.NEON_BILLBOARD -> drawNeonBillboard(
                canvas,
                w,
                h,
                primaryPalette ?: PalettePresets.NEON_VIOLET,
                secondaryPalette ?: PalettePresets.SIREN_BLUE
            )
            PropKind.STREET_BARRICADE -> drawStreetBarricade(
                canvas,
                w,
                h,
                primaryPalette ?: PalettePresets.HAZARD_AMBER,
                secondaryPalette ?: PalettePresets.URBAN_ASPHALT
            )
            PropKind.TRAFFIC_LIGHT -> drawTrafficLight(
                canvas,
                w,
                h,
                primaryPalette ?: PalettePresets.URBAN_ASPHALT,
                secondaryPalette ?: PalettePresets.HAZARD_AMBER
            )
            PropKind.SIGNPOST -> WorldProps.signpost(canvas, w, h, wood)
            PropKind.OBELISK -> WorldProps.obelisk(canvas, w, h, stone, secondaryPalette ?: PalettePresets.OCEAN_WATER)
            PropKind.REED_CLUMP -> WorldProps.reeds(canvas, w, h, foliage, wood, s)
            PropKind.WHEAT_SHEAF -> WorldProps.wheatSheaf(canvas, w, h, primaryPalette ?: PalettePresets.DESERT_SAND, wood)
            PropKind.ROPE_BRIDGE -> WorldProps.ropeBridge(canvas, w, h, wood)
            PropKind.PIER -> WorldProps.pier(canvas, w, h, wood)
            PropKind.SAILING_SHIP -> WorldProps.sailingShip(canvas, w, h, primaryPalette ?: PalettePresets.CREAM_MARBLE, wood)
            PropKind.WATCH_TOWER -> WorldProps.watchTower(canvas, w, h, stone, wood)
            PropKind.GATEHOUSE -> WorldProps.gatehouse(canvas, w, h, stone, wood, PalettePresets.SCARLET_ROOF)
            PropKind.MINE_CART -> WorldProps.mineCart(canvas, w, h, stone, wood)
            PropKind.DEAD_TREE -> WorldProps.deadTree(canvas, w, h, wood, s)
            }
        }

        val styled = when (style) {
            PixelStyle.RETRO_8BIT -> {
                val capPalette = (primaryPalette ?: foliage).limitColors(4)
                PixelDither.applyStyle(canvas, style, capPalette)
            }
            PixelStyle.MORGANA -> SpriteFinish.finish(canvas)
            PixelStyle.JRPG_32BIT, PixelStyle.PAINTED_25D -> {
                val selOut = PixelDither.applySelectiveOutline(canvas)
                PixelDither.applyStyle(selOut, style, primaryPalette ?: foliage)
            }
            else -> canvas
        }

        val slices = mutableListOf<NamedTile>()
        for (row in 0 until kind.heightTiles) {
            for (col in 0 until kind.widthTiles) {
                val tileImg = styled.crop(col * tileSize, row * tileSize, tileSize, tileSize)
                slices += NamedTile("${name}_${col}_${row}", tileImg)
            }
        }

        return GeneratedProp(
            name = name,
            kind = kind,
            widthTiles = kind.widthTiles,
            heightTiles = kind.heightTiles,
            fullImage = styled,
            slicedTiles = slices
        )
    }

    private fun drawOakTree(
        img: TileImage,
        w: Int,
        h: Int,
        foliage: PaletteRamp,
        wood: PaletteRamp,
        style: PixelStyle,
        s: Long
    ) {
        val shadowRgba = PaletteRamp.rgba(10, 18, 12, 102)
        val outline = foliage.shadow

        // Soft grass-tinted ground shadow ellipse
        drawEllipse(img, w * 0.51f, h * 0.87f, w * 0.35f, h * 0.085f, shadowRgba)

        // Textured trunk with root flare and canopy ambient occlusion
        val trunkW = (w * 0.19f).toInt().coerceAtLeast(3)
        val trunkX0 = (w - trunkW) / 2
        val trunkY0 = (h * 0.52f).toInt()
        val trunkY1 = (h * 0.88f).toInt()
        for (y in trunkY0..trunkY1) {
            val flare = if (y >= trunkY1 - 3) (y - (trunkY1 - 4)) / 2 else 0
            val xStart = (trunkX0 - flare).coerceAtLeast(0)
            val xEnd = (trunkX0 + trunkW + flare).coerceAtMost(w)
            for (x in xStart until xEnd) {
                val nx = (x - xStart).toFloat() / (xEnd - xStart).coerceAtLeast(1)
                val barkGrain = if ((x + (y / 3)) % 3 == 0) -0.10f else 0.05f
                val ao = if (y < trunkY0 + 6) -0.25f else 0f
                val col = when {
                    x == xStart || x == xEnd - 1 -> wood.shadow
                    else -> wood.sample((0.72f - nx * 0.42f + barkGrain + ao).coerceIn(0.05f, 0.92f))
                }
                img.setPixel(x, y, col)
            }
        }

        // Layered 3/4 canopy sub-clusters shaded individually for pillowy pixel-art depth
        val clusters = listOf(
            Triple(0.50f, 0.44f, 0.33f), // base
            Triple(0.30f, 0.46f, 0.23f), // left lobe
            Triple(0.70f, 0.46f, 0.23f), // right lobe
            Triple(0.38f, 0.30f, 0.24f), // upper-left sunlit crown
            Triple(0.62f, 0.32f, 0.22f), // upper-right crown
            Triple(0.50f, 0.22f, 0.23f)  // top crown
        )
        val inside = Array(h) { BooleanArray(w) }
        val shadeGrid = Array(h) { FloatArray(w) }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var bestDist = -1f
                var bestShade = 0f
                for ((cxN, cyN, rN) in clusters) {
                    val dx = (x + 0.5f - cxN * w) / (rN * w)
                    val dy = (y + 0.5f - cyN * h) / (rN * h)
                    val wobble = (TileNoise.hash(x / 2, y / 2, s) - 0.5f) * 0.16f
                    val dist = 1f - hypot(dx, dy) + wobble
                    if (dist > bestDist) {
                        bestDist = dist
                        // Directional upper-left light per sub-cluster + global height gradient
                        val localLight = (-dx * 0.28f - dy * 0.38f + 0.55f)
                        val globalLight = (1f - y.toFloat() / (h * 0.65f)) * 0.45f
                        val clusterVar = (TileNoise.hash(x / 2, y / 2, s + 17L) - 0.5f) * 0.14f
                        bestShade = (localLight * 0.55f + globalLight + clusterVar).coerceIn(0.08f, 0.96f)
                    }
                }
                if (bestDist > 0f) {
                    inside[y][x] = true
                    shadeGrid[y][x] = bestShade
                }
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (!inside[y][x]) continue
                val isOutline = x == 0 || x == w - 1 || y == 0 || y == h - 1 ||
                    !inside[y - 1][x] || !inside[y + 1][x] || !inside[y][x - 1] || !inside[y][x + 1]
                val color = if (isOutline) {
                    outline
                } else if (style == PixelStyle.CLASSIC_RPG) {
                    PixelDither.sampleDithered(foliage, shadeGrid[y][x], x, y, 4, 0.12f)
                } else {
                    foliage.sample(shadeGrid[y][x])
                }
                img.setPixel(x, y, color)
            }
        }
    }

    private fun drawPineTree(
        img: TileImage,
        w: Int,
        h: Int,
        foliage: PaletteRamp,
        wood: PaletteRamp,
        style: PixelStyle,
        s: Long
    ) {
        drawEllipse(img, w * 0.5f, h * 0.90f, w * 0.34f, h * 0.06f, PaletteRamp.rgba(10, 18, 12, 102))
        val trunkW = (w * 0.24f).toInt().coerceAtLeast(3)
        val tx0 = (w - trunkW) / 2
        for (y in (h * 0.72f).toInt()..(h * 0.90f).toInt()) {
            for (x in tx0 until tx0 + trunkW) {
                img.setPixel(x, y, if (x == tx0 || x == tx0 + trunkW - 1) wood.shadow else wood.mid)
            }
        }
        // Three stacked evergreen tiers with scalloped bough tips
        val tiers = listOf(
            Triple(0.06f, 0.36f, 0.30f),
            Triple(0.24f, 0.58f, 0.40f),
            Triple(0.44f, 0.78f, 0.47f)
        )
        for ((topN, botN, halfWN) in tiers) {
            val y0 = (topN * h).toInt()
            val y1 = (botN * h).toInt()
            for (y in y0..y1) {
                val frac = (y - y0).toFloat() / (y1 - y0).coerceAtLeast(1)
                val halfW = (frac * halfWN * w).toInt()
                for (x in (w / 2 - halfW)..(w / 2 + halfW)) {
                    if (x !in 0 until w) continue
                    // Scallop the bottom 2 px of each tier for bough silhouette
                    val scallop = if (y >= y1 - 1 && (abs(x - w / 2) % 4 == 2)) true else false
                    if (scallop && y == y1) continue
                    val edge = abs(x - w / 2) >= halfW || y == y1 || scallop
                    val shade = (1f - frac * 0.38f) * (if (x < w / 2) 1.0f else 0.74f) +
                        TileNoise.hash(x / 2, y / 2, s) * 0.10f
                    val col = when {
                        edge -> foliage.shadow
                        style == PixelStyle.CLASSIC_RPG -> PixelDither.sampleDithered(foliage, shade, x, y, 4, 0.12f)
                        else -> foliage.sample(shade.coerceIn(0.08f, 0.95f))
                    }
                    img.setPixel(x, y, col)
                }
            }
        }
    }

    private fun drawWoodFence(img: TileImage, w: Int, h: Int, wood: PaletteRamp, style: PixelStyle) {
        val railY1 = (h * 0.38f).toInt()
        val railY2 = (h * 0.64f).toInt()
        val railH = (h * 0.12f).toInt().coerceAtLeast(2)
        // Horizontal rails across full 3x1 width
        for (ry in listOf(railY1, railY2)) {
            for (y in ry until (ry + railH).coerceAtMost(h)) {
                for (x in 0 until w) {
                    val col = when (y) {
                        ry -> wood.highlight
                        ry + railH - 1 -> wood.shadow
                        else -> wood.mid
                    }
                    img.setPixel(x, y, col)
                }
            }
        }
        // Fence posts per tile
        val postW = (tileSize * 0.25f).toInt().coerceAtLeast(3)
        for (tileCol in 0 until 3) {
            val px0 = tileCol * tileSize + (tileSize - postW) / 2
            val py0 = (h * 0.18f).toInt()
            val py1 = (h * 0.88f).toInt()
            for (y in py0..py1) {
                for (x in px0 until px0 + postW) {
                    val col = when {
                        y == py0 -> wood.highlight
                        y == py1 || x == px0 || x == px0 + postW - 1 -> wood.shadow
                        style == PixelStyle.CLASSIC_RPG -> PixelDither.sampleDithered(wood, 0.65f, x, y, 4, 0.12f)
                        else -> wood.sample(0.65f)
                    }
                    img.setPixel(x, y, col)
                }
            }
        }
    }

    private fun drawWoodBridge(img: TileImage, w: Int, h: Int, wood: PaletteRamp, style: PixelStyle) {
        val plankStep = (tileSize / 8).coerceAtLeast(2)
        val topRail = (h * 0.14f).toInt()
        val botRail = (h * 0.86f).toInt()
        for (y in topRail..botRail) {
            for (x in 0 until w) {
                val isBorder = y == topRail || y == topRail + 1 || y == botRail || y == botRail - 1
                val isPlankSeam = x % plankStep == 0
                val t = when {
                    isBorder -> 0.85f
                    isPlankSeam -> 0.15f
                    else -> 0.55f + 0.1f * ((x / plankStep) % 2)
                }
                val col = if (style == PixelStyle.CLASSIC_RPG) {
                    PixelDither.sampleDithered(wood, t, x, y, 4, 0.14f)
                } else {
                    wood.sample(t)
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawStoneArchway(img: TileImage, w: Int, h: Int, stone: PaletteRamp, style: PixelStyle, s: Long) {
        val voidColor = PaletteRamp.rgba(14, 14, 22)
        val archCx = w * 0.5f
        val archCy = h * 0.54f
        val archRx = tileSize * 0.45f
        val archRy = h * 0.28f

        for (y in (h * 0.10f).toInt() until h) {
            for (x in (w * 0.10f).toInt() until (w * 0.90f).toInt()) {
                val inOpening = (y >= archCy && abs(x - archCx) <= archRx) ||
                    (y < archCy && hypot((x - archCx) / archRx, (y - archCy) / archRy) <= 1f)
                if (inOpening) {
                    img.setPixel(x, y, voidColor)
                } else {
                    val isOuterEdge = x <= (w * 0.10f).toInt() + 1 || x >= (w * 0.90f).toInt() - 2 || y <= (h * 0.10f).toInt() + 1
                    val brickLine = y % (tileSize / 4).coerceAtLeast(3) == 0
                    val t = when {
                        isOuterEdge || brickLine -> 0.15f
                        else -> 0.45f + TileNoise.hash(x / 2, y / 2, s) * 0.35f
                    }
                    val col = if (style == PixelStyle.CLASSIC_RPG) {
                        PixelDither.sampleDithered(stone, t, x, y, 4, 0.15f)
                    } else {
                        stone.sample(t)
                    }
                    img.setPixel(x, y, col)
                }
            }
        }
    }

    private fun drawCottage(
        img: TileImage,
        w: Int,
        h: Int,
        roof: PaletteRamp,
        wood: PaletteRamp,
        stone: PaletteRamp,
        style: PixelStyle
    ) {
        val wallColor = PaletteRamp.rgba(218, 206, 184)
        val windowColor = PaletteRamp.rgba(116, 178, 224)
        val x0 = (w * 0.08f).toInt()
        val x1 = (w * 0.92f).toInt()
        val roofTop = (h * 0.08f).toInt()
        val roofBot = (h * 0.54f).toInt()
        val wallBot = (h * 0.92f).toInt()

        // Pitched roof
        val shingleH = (tileSize / 6).coerceAtLeast(2)
        for (y in roofTop..roofBot) {
            val inset = ((roofBot - y).toFloat() / (roofBot - roofTop).coerceAtLeast(1) * (w * 0.14f)).toInt()
            for (x in (x0 + inset)..(x1 - inset)) {
                val isSeam = y % shingleH == 0 || y == roofBot || x == x0 + inset || x == x1 - inset
                val t = if (isSeam) 0.15f else 0.55f + 0.25f * (1f - (y - roofTop).toFloat() / (roofBot - roofTop))
                val col = if (style == PixelStyle.CLASSIC_RPG) {
                    PixelDither.sampleDithered(roof, t, x, y, 4, 0.14f)
                } else {
                    roof.sample(t)
                }
                img.setPixel(x, y, col)
            }
        }

        // Front wall
        for (y in (roofBot + 1)..wallBot) {
            for (x in (x0 + 2)..(x1 - 2)) {
                val isShadow = y <= roofBot + (tileSize / 10).coerceAtLeast(2)
                val isOutline = x == x0 + 2 || x == x1 - 2 || y == wallBot
                val col = when {
                    isOutline -> stone.shadow
                    isShadow -> stone.mid
                    else -> wallColor
                }
                img.setPixel(x, y, col)
            }
        }

        // Door (center)
        val doorW = (w * 0.16f).toInt().coerceAtLeast(4)
        val doorX0 = (w - doorW) / 2
        val doorY0 = (h * 0.66f).toInt()
        for (y in doorY0..wallBot) {
            for (x in doorX0 until doorX0 + doorW) {
                val edge = x == doorX0 || x == doorX0 + doorW - 1 || y == doorY0
                img.setPixel(x, y, if (edge) wood.shadow else wood.mid)
            }
        }

        // Two windows
        val winSize = (tileSize * 0.30f).toInt().coerceAtLeast(3)
        for (winCx in listOf((w * 0.27f).toInt(), (w * 0.73f).toInt())) {
            val wx0 = winCx - winSize / 2
            val wy0 = (h * 0.66f).toInt()
            for (y in wy0 until wy0 + winSize) {
                for (x in wx0 until wx0 + winSize) {
                    val frame = x == wx0 || x == wx0 + winSize - 1 || y == wy0 || y == wy0 + winSize - 1
                    img.setPixel(x, y, if (frame) wood.shadow else windowColor)
                }
            }
        }
    }

    private fun drawCampfire(img: TileImage, w: Int, h: Int, stone: PaletteRamp, style: PixelStyle, s: Long) {
        val fire = PalettePresets.LAVA_MAGMA
        val cx = w * 0.5f
        val cy = h * 0.66f
        val ringR = w * 0.30f

        // Stone ring
        for (y in 0 until h) {
            for (x in 0 until w) {
                val d = hypot(x + 0.5f - cx, (y + 0.5f - cy) * 1.3f)
                if (d in (ringR * 0.65f)..ringR) {
                    img.setPixel(x, y, stone.sample(0.45f + TileNoise.hash(x / 2, y / 2, s) * 0.3f))
                }
            }
        }
        // Flame
        for (y in (h * 0.18f).toInt()..(h * 0.68f).toInt()) {
            val frac = (y - h * 0.18f) / (h * 0.50f)
            val halfW = (frac * (1f - frac * 0.45f) * w * 0.24f).toInt().coerceAtLeast(1)
            for (x in (w / 2 - halfW)..(w / 2 + halfW)) {
                val t = (1f - abs(x - w / 2).toFloat() / halfW.coerceAtLeast(1)) * 0.6f + frac * 0.4f
                val col = if (style == PixelStyle.CLASSIC_RPG) {
                    PixelDither.sampleDithered(fire, t, x, y, 4, 0.15f)
                } else {
                    fire.sample(t)
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawGrassTuft(img: TileImage, w: Int, h: Int, foliage: PaletteRamp, s: Long) {
        val baseY = (h * 0.78f).toInt()
        val blades = listOf(-6 to 9, -3 to 12, 0 to 13, 3 to 11, 6 to 8)
        for ((dx, bh) in blades) {
            val bx = w / 2 + dx
            val tilt = if (dx < 0) -1 else if (dx > 0) 1 else 0
            for (i in 0..bh) {
                val py = baseY - i
                val px = bx + (tilt * i / 5)
                if (px !in 0 until w || py !in 0 until h) continue
                val t = i.toFloat() / bh
                val col = when {
                    i == 0 -> foliage.shadow
                    t > 0.72f -> foliage.highlight
                    else -> foliage.sample(0.42f + t * 0.45f)
                }
                img.setPixel(px, py, col)
                if (i < bh / 2 && px + 1 < w && (s + dx).toInt() % 2 == 0) {
                    img.setPixel(px + 1, py, foliage.shadow)
                }
            }
        }
    }

    private fun drawWildflowerPatch(img: TileImage, w: Int, h: Int, foliage: PaletteRamp, s: Long) {
        drawGrassTuft(img, w, h, foliage, s)
        val petals = listOf(
            Triple(w / 2 - 6, h / 2 - 2, PaletteRamp.rgba(250, 214, 72)),
            Triple(w / 2 + 5, h / 2 + 1, PaletteRamp.rgba(244, 114, 182)),
            Triple(w / 2 - 1, h / 2 - 6, PaletteRamp.rgba(255, 250, 220))
        )
        val center = PaletteRamp.rgba(217, 119, 6)
        for ((fx, fy, petalCol) in petals) {
            for ((ox, oy) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
                if (fx + ox in 0 until w && fy + oy in 0 until h) img.setPixel(fx + ox, fy + oy, petalCol)
            }
            if (fx in 0 until w && fy in 0 until h) img.setPixel(fx, fy, center)
        }
    }

    private fun drawBush(img: TileImage, w: Int, h: Int, foliage: PaletteRamp, style: PixelStyle, s: Long) {
        drawEllipse(img, w * 0.5f, h * 0.80f, w * 0.34f, h * 0.11f, PaletteRamp.rgba(10, 18, 12, 102))
        val lobes = listOf(
            Triple(0.50f, 0.56f, 0.30f),
            Triple(0.32f, 0.62f, 0.21f),
            Triple(0.68f, 0.62f, 0.21f)
        )
        val inside = Array(h) { BooleanArray(w) }
        val shade = Array(h) { FloatArray(w) }
        for (y in 0 until h) for (x in 0 until w) {
            var best = -1f
            var bestS = 0f
            for ((cx, cy, r) in lobes) {
                val dx = (x + 0.5f - cx * w) / (r * w)
                val dy = (y + 0.5f - cy * h) / (r * h)
                val d = 1f - hypot(dx, dy) + (TileNoise.hash(x / 2, y / 2, s) - 0.5f) * 0.15f
                if (d > best) {
                    best = d
                    bestS = (-dx * 0.3f - dy * 0.4f + 0.55f).coerceIn(0.1f, 0.95f)
                }
            }
            if (best > 0f) { inside[y][x] = true; shade[y][x] = bestS }
        }
        for (y in 0 until h) for (x in 0 until w) {
            if (!inside[y][x]) continue
            val outline = x == 0 || x == w - 1 || y == 0 || y == h - 1 ||
                !inside[y - 1][x] || !inside[y + 1][x] || !inside[y][x - 1] || !inside[y][x + 1]
            val col = when {
                outline -> foliage.shadow
                style == PixelStyle.CLASSIC_RPG -> PixelDither.sampleDithered(foliage, shade[y][x], x, y, 4, 0.12f)
                else -> foliage.sample(shade[y][x])
            }
            img.setPixel(x, y, col)
        }
    }

    private fun drawTreeStump(img: TileImage, w: Int, h: Int, wood: PaletteRamp, foliage: PaletteRamp) {
        drawEllipse(img, w * 0.5f, h * 0.80f, w * 0.30f, h * 0.10f, PaletteRamp.rgba(10, 18, 12, 102))
        val topY = (h * 0.44f).toInt()
        val botY = (h * 0.78f).toInt()
        val halfW = (w * 0.24f).toInt().coerceAtLeast(4)
        for (y in topY..botY) {
            val flare = if (y >= botY - 2) (y - (botY - 3)) else 0
            for (x in (w / 2 - halfW - flare)..(w / 2 + halfW + flare)) {
                if (x !in 0 until w) continue
                val edge = x == w / 2 - halfW - flare || x == w / 2 + halfW + flare || y == botY
                val nx = (x - (w / 2 - halfW)).toFloat() / (halfW * 2).coerceAtLeast(1)
                val col = if (edge) wood.shadow else wood.sample((0.72f - nx * 0.42f).coerceIn(0.15f, 0.85f))
                img.setPixel(x, y, col)
            }
        }
        drawEllipse(img, w * 0.5f, topY.toFloat(), halfW.toFloat(), 3.2f, wood.highlight)
        drawEllipse(img, w * 0.5f, topY.toFloat(), (halfW * 0.55f), 1.8f, wood.mid)
        img.setPixel(w / 2 - halfW, botY - 2, foliage.mid)
        img.setPixel(w / 2 - halfW + 1, botY - 1, foliage.highlight)
    }

    private fun drawEllipse(img: TileImage, cx: Float, cy: Float, rx: Float, ry: Float, rgba: Int) {
        val x0 = (cx - rx).toInt().coerceAtLeast(0)
        val x1 = (cx + rx).toInt().coerceAtMost(img.width - 1)
        val y0 = (cy - ry).toInt().coerceAtLeast(0)
        val y1 = (cy + ry).toInt().coerceAtMost(img.height - 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val d = hypot((x + 0.5f - cx) / rx, (y + 0.5f - cy) / ry)
                if (d <= 1f) {
                    img.drawPixel(x, y, rgba)
                }
            }
        }
    }

    private fun drawWoodDoor(img: TileImage, w: Int, h: Int, wood: PaletteRamp, stone: PaletteRamp, open: Boolean) {
        val voidColor = PaletteRamp.rgba(12, 12, 18)
        val x0 = (w * 0.12f).toInt()
        val x1 = (w * 0.88f).toInt()
        val y0 = (h * 0.08f).toInt()
        val y1 = h - 1
        for (y in y0..y1) {
            for (x in x0..x1) {
                val isFrame = x == x0 || x == x1 || y == y0
                if (isFrame) {
                    img.setPixel(x, y, stone.shadow)
                } else if (open) {
                    val isDoorLeaf = x <= x0 + (w * 0.18f).toInt()
                    img.setPixel(x, y, if (isDoorLeaf) wood.mid else voidColor)
                } else {
                    val isBand = y == (h * 0.35f).toInt() || y == (h * 0.72f).toInt()
                    val isPlank = x % (w / 5).coerceAtLeast(2) == 0
                    val col = when {
                        isBand -> stone.mid
                        isPlank -> wood.shadow
                        else -> wood.mid
                    }
                    img.setPixel(x, y, col)
                }
            }
        }
    }

    private fun drawIronBars(img: TileImage, w: Int, h: Int, stone: PaletteRamp) {
        val barStep = (w / 4).coerceAtLeast(3)
        val crossY1 = (h * 0.25f).toInt()
        val crossY2 = (h * 0.75f).toInt()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val isVBar = x % barStep == 1
                val isHBar = y == crossY1 || y == crossY2
                if (isVBar || isHBar) {
                    img.setPixel(x, y, if (y == crossY1 || x % barStep == 1 && y % 2 == 0) stone.highlight else stone.mid)
                }
            }
        }
    }

    private fun drawTorchSconce(img: TileImage, w: Int, h: Int, stone: PaletteRamp) {
        val fire = PalettePresets.LAVA_MAGMA
        val cx = w / 2
        // Bracket & handle
        for (y in (h * 0.45f).toInt()..(h * 0.85f).toInt()) {
            img.setPixel(cx, y, stone.mid)
            if (cx + 1 < w) img.setPixel(cx + 1, y, stone.shadow)
        }
        // Flame
        for (y in (h * 0.15f).toInt()..(h * 0.46f).toInt()) {
            val frac = (y - h * 0.15f) / (h * 0.31f)
            val halfW = ((1f - abs(frac - 0.5f) * 1.4f) * w * 0.18f).toInt().coerceAtLeast(1)
            for (x in (cx - halfW)..(cx + halfW)) {
                img.setPixel(x, y, fire.sample(0.4f + frac * 0.55f))
            }
        }
    }

    private fun drawBarrel(img: TileImage, w: Int, h: Int, wood: PaletteRamp, stone: PaletteRamp) {
        val cx = w * 0.5f
        val cy = h * 0.54f
        val rx = w * 0.32f
        val ry = h * 0.38f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                val d = hypot(dx * 0.85f, dy)
                if (d <= 1f) {
                    val isOutline = d > 0.86f
                    val isHoop = y == (h * 0.36f).toInt() || y == (h * 0.70f).toInt()
                    val isTop = y < (h * 0.30f).toInt()
                    val col = when {
                        isOutline -> wood.shadow
                        isHoop -> stone.mid
                        isTop -> wood.highlight
                        else -> wood.sample(0.65f - dx * 0.25f)
                    }
                    img.setPixel(x, y, col)
                }
            }
        }
    }

    private fun drawChest(img: TileImage, w: Int, h: Int, wood: PaletteRamp, stone: PaletteRamp, open: Boolean) {
        val gold = PaletteRamp.rgba(235, 192, 52)
        val x0 = (w * 0.16f).toInt()
        val x1 = (w * 0.84f).toInt()
        val y0 = (h * (if (open) 0.22f else 0.32f)).toInt()
        val y1 = (h * 0.84f).toInt()
        val lidY = (h * 0.52f).toInt()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val border = x == x0 || x == x1 || y == y0 || y == y1 || y == lidY
                val isLock = abs(x - w / 2) <= 1 && abs(y - lidY) <= 1
                val col = when {
                    isLock -> gold
                    border -> stone.shadow
                    open && y in (y0 + 2) until lidY -> PaletteRamp.rgba(16, 12, 18)
                    y < lidY -> wood.highlight
                    else -> wood.mid
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawPillar(img: TileImage, w: Int, h: Int, stone: PaletteRamp, style: PixelStyle) {
        val x0 = (w * 0.22f).toInt()
        val x1 = (w * 0.78f).toInt()
        val capH = (h * 0.14f).toInt()
        for (y in (h * 0.06f).toInt()..(h * 0.92f).toInt()) {
            val isCap = y < (h * 0.06f).toInt() + capH || y > (h * 0.92f).toInt() - capH
            val rowX0 = if (isCap) (x0 - 2).coerceAtLeast(1) else x0
            val rowX1 = if (isCap) (x1 + 2).coerceAtMost(w - 2) else x1
            for (x in rowX0..rowX1) {
                val nx = (x - rowX0).toFloat() / (rowX1 - rowX0).coerceAtLeast(1)
                val outline = x == rowX0 || x == rowX1 || y == (h * 0.06f).toInt() || y == (h * 0.92f).toInt()
                val col = if (outline) {
                    stone.shadow
                } else if (style == PixelStyle.CLASSIC_RPG) {
                    PixelDither.sampleDithered(stone, 0.85f - nx * 0.5f, x, y, 4, 0.14f)
                } else {
                    stone.sample(0.85f - nx * 0.5f)
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawShrine(img: TileImage, w: Int, h: Int, stone: PaletteRamp) {
        val glow = PaletteRamp.rgba(110, 200, 255)
        val x0 = (w * 0.20f).toInt()
        val x1 = (w * 0.80f).toInt()
        for (y in (h * 0.10f).toInt()..(h * 0.92f).toInt()) {
            val taper = if (y < h * 0.24f) ((h * 0.24f - y) / (h * 0.14f) * (w * 0.14f)).toInt() else 0
            for (x in (x0 + taper)..(x1 - taper)) {
                val outline = x == x0 + taper || x == x1 - taper || y == (h * 0.92f).toInt()
                val isRune = abs(x - w / 2) <= 1 && y in (h * 0.34f).toInt()..(h * 0.56f).toInt()
                val col = when {
                    outline -> stone.shadow
                    isRune -> glow
                    else -> stone.sample(0.65f - (x - x0).toFloat() / (x1 - x0).coerceAtLeast(1) * 0.35f)
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawStairs(img: TileImage, w: Int, h: Int, stone: PaletteRamp, down: Boolean) {
        val steps = 4
        val stepH = (h / steps).coerceAtLeast(2)
        for (y in 0 until h) {
            val stepIdx = (y / stepH).coerceIn(0, steps - 1)
            val norm = if (down) 1f - stepIdx.toFloat() / (steps - 1) else stepIdx.toFloat() / (steps - 1)
            val isEdge = y % stepH == 0
            for (x in 1 until w - 1) {
                val isWall = x == 1 || x == w - 2
                val col = when {
                    isWall || isEdge -> stone.shadow
                    else -> stone.sample(0.15f + norm * 0.7f)
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawSarcophagus(img: TileImage, w: Int, h: Int, stone: PaletteRamp) {
        val x0 = (w * 0.14f).toInt()
        val x1 = (w * 0.86f).toInt()
        val y0 = (h * 0.08f).toInt()
        val y1 = (h * 0.92f).toInt()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val border = x == x0 || x == x1 || y == y0 || y == y1
                val lid = y in (y0 + 2)..(y1 - 3) && x in (x0 + 2)..(x1 - 2)
                val cross = (abs(x - w / 2) <= 0 && y in (y0 + 4)..(y1 - 5)) ||
                    (abs(y - (y0 + (y1 - y0) / 3)) <= 0 && x in (x0 + 3)..(x1 - 3))
                val col = when {
                    border -> stone.shadow
                    lid && cross -> stone.highlight
                    lid -> stone.mid
                    else -> stone.shadow
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawPortal(img: TileImage, w: Int, h: Int, stone: PaletteRamp) {
        val water = PalettePresets.OCEAN_WATER
        val cx = w * 0.5f
        val cy = h * 0.50f
        val rx = w * 0.36f
        val ry = h * 0.40f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val d = hypot((x + 0.5f - cx) / rx, (y + 0.5f - cy) / ry)
                if (d <= 1f) {
                    val col = when {
                        d > 0.85f -> stone.highlight
                        d < 0.35f -> water.highlight
                        else -> water.sample(1f - d)
                    }
                    img.setPixel(x, y, col)
                }
            }
        }
    }

    private fun drawWallBanner(img: TileImage, w: Int, h: Int, banner: PaletteRamp, stone: PaletteRamp) {
        val gold = PaletteRamp.rgba(232, 188, 52)
        val x0 = (w * 0.22f).toInt()
        val x1 = (w * 0.78f).toInt()
        val barY = (h * 0.12f).toInt()
        for (x in (x0 - 1)..(x1 + 1)) {
            if (x in 0 until w) img.setPixel(x, barY, stone.highlight)
        }
        val botY = (h * 0.86f).toInt()
        for (y in (barY + 1)..botY) {
            val notch = if (y > h * 0.72f) ((y - h * 0.72f) / (h * 0.14f) * (w * 0.2f)).toInt() else 0
            for (x in x0..x1) {
                if (abs(x - w / 2) < notch) continue
                val edge = x == x0 || x == x1 || y == botY
                val trim = abs(x - w / 2) <= 0 && y in (h * 0.28f).toInt()..(h * 0.54f).toInt()
                img.setPixel(x, y, when {
                    edge -> banner.shadow
                    trim -> gold
                    else -> banner.mid
                })
            }
        }
    }

    private fun drawWallChains(img: TileImage, w: Int, h: Int, stone: PaletteRamp) {
        val ringY = (h * 0.22f).toInt()
        val cx = w / 2
        for (dy in -1..1) for (dx in -1..1) {
            if (dx != 0 || dy != 0) img.setPixel((cx + dx).coerceIn(0, w - 1), (ringY + dy).coerceIn(0, h - 1), stone.highlight)
        }
        for (offset in listOf(-2, 2)) {
            val lx = (cx + offset).coerceIn(0, w - 1)
            for (y in (ringY + 2)..(h * 0.80f).toInt()) {
                img.setPixel(lx, y, if (y % 2 == 0) stone.highlight else stone.shadow)
            }
        }
    }

    private fun drawWallShelf(img: TileImage, w: Int, h: Int, wood: PaletteRamp, stone: PaletteRamp) {
        val shelfY = (h * 0.62f).toInt()
        for (y in shelfY..(shelfY + 1).coerceAtMost(h - 1)) {
            for (x in (w * 0.14f).toInt()..(w * 0.86f).toInt()) {
                img.setPixel(x, y, if (y == shelfY) wood.highlight else wood.shadow)
            }
        }
        // Small red bottle & blue bottle on shelf
        val red = PaletteRamp.rgba(210, 42, 42)
        val blue = PaletteRamp.rgba(52, 130, 232)
        for (y in (shelfY - 4).coerceAtLeast(0) until shelfY) {
            img.setPixel((w * 0.30f).toInt(), y, red)
            img.setPixel((w * 0.30f).toInt() + 1, y, red)
            img.setPixel((w * 0.62f).toInt(), y, blue)
            img.setPixel((w * 0.62f).toInt() + 1, y, blue)
        }
        img.setPixel((w * 0.30f).toInt(), (shelfY - 5).coerceAtLeast(0), stone.highlight)
        img.setPixel((w * 0.62f).toInt(), (shelfY - 5).coerceAtLeast(0), stone.highlight)
    }

    private fun drawGargoylePlaque(img: TileImage, w: Int, h: Int, stone: PaletteRamp) {
        val eye = PaletteRamp.rgba(220, 48, 36)
        val x0 = (w * 0.18f).toInt()
        val x1 = (w * 0.82f).toInt()
        val y0 = (h * 0.18f).toInt()
        val y1 = (h * 0.82f).toInt()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val border = x == x0 || x == x1 || y == y0 || y == y1
                img.setPixel(x, y, if (border) stone.shadow else stone.mid)
            }
        }
        img.setPixel((w * 0.36f).toInt(), (h * 0.42f).toInt(), eye)
        img.setPixel((w * 0.64f).toInt(), (h * 0.42f).toInt(), eye)
        for (x in (w * 0.38f).toInt()..(w * 0.62f).toInt()) {
            img.setPixel(x, (h * 0.64f).toInt(), stone.shadow)
        }
    }

    private fun drawGoldPile(img: TileImage, w: Int, h: Int) {
        val goldDark = PaletteRamp.rgba(156, 102, 18)
        val goldMid = PaletteRamp.rgba(228, 178, 42)
        val goldBright = PaletteRamp.rgba(255, 240, 128)
        val cx = w * 0.5f
        val cy = h * 0.66f
        for (y in (h * 0.38f).toInt()..(h * 0.84f).toInt()) {
            for (x in (w * 0.16f).toInt()..(w * 0.84f).toInt()) {
                val d = hypot((x + 0.5f - cx) / (w * 0.34f), (y + 0.5f - cy) / (h * 0.24f))
                if (d <= 1f) {
                    val col = when {
                        d > 0.82f -> goldDark
                        (x + y) % 3 == 0 && y < cy -> goldBright
                        else -> goldMid
                    }
                    img.setPixel(x, y, col)
                }
            }
        }
    }

    private fun drawPotion(img: TileImage, w: Int, h: Int, liquid: PaletteRamp, stone: PaletteRamp) {
        val cx = w * 0.5f
        val cy = h * 0.62f
        val r = w * 0.24f
        // Neck & cork
        for (y in (h * 0.22f).toInt()..(h * 0.42f).toInt()) {
            img.setPixel(w / 2 - 1, y, stone.highlight)
            img.setPixel(w / 2, y, if (y < h * 0.30f) PaletteRamp.rgba(164, 112, 62) else stone.mid)
        }
        // Bulb
        for (y in 0 until h) {
            for (x in 0 until w) {
                val d = hypot(x + 0.5f - cx, y + 0.5f - cy)
                if (d <= r) {
                    val col = when {
                        d > r * 0.78f -> liquid.shadow
                        x < cx && y < cy -> liquid.highlight
                        else -> liquid.mid
                    }
                    img.setPixel(x, y, col)
                }
            }
        }
    }

    private fun drawScroll(img: TileImage, w: Int, h: Int, paper: PaletteRamp, ribbon: PaletteRamp) {
        val x0 = (w * 0.20f).toInt()
        val x1 = (w * 0.80f).toInt()
        val y0 = (h * 0.28f).toInt()
        val y1 = (h * 0.72f).toInt()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val border = x == x0 || x == x1 || y == y0 || y == y1
                val isRibbon = abs(x - w / 2) <= 0
                img.setPixel(x, y, when {
                    border -> paper.shadow
                    isRibbon -> ribbon.mid
                    else -> paper.highlight
                })
            }
        }
    }

    private fun drawKey(img: TileImage, w: Int, h: Int) {
        val gold = PaletteRamp.rgba(235, 192, 52)
        val dark = PaletteRamp.rgba(128, 88, 22)
        val cy = h / 2
        // Bow
        for (y in (cy - 2)..(cy + 2)) {
            for (x in (w * 0.20f).toInt()..(w * 0.38f).toInt()) {
                if (x == (w * 0.20f).toInt() || x == (w * 0.38f).toInt() || y == cy - 2 || y == cy + 2) {
                    img.setPixel(x, y, gold)
                }
            }
        }
        // Shaft & teeth
        for (x in (w * 0.38f).toInt()..(w * 0.80f).toInt()) {
            img.setPixel(x, cy, gold)
            img.setPixel(x, cy + 1, dark)
        }
        img.setPixel((w * 0.70f).toInt(), cy + 2, gold)
        img.setPixel((w * 0.80f).toInt(), cy + 2, gold)
    }

    private fun drawSkullPile(img: TileImage, w: Int, h: Int, stone: PaletteRamp) {
        val bone = PaletteRamp.rgba(222, 214, 194)
        val shade = PaletteRamp.rgba(116, 108, 94)
        val eye = PaletteRamp.rgba(20, 16, 20)
        val cx = w / 2
        val cy = (h * 0.56f).toInt()
        for (dy in -3..3) {
            for (dx in -3..3) {
                if (dx * dx + dy * dy <= 10) {
                    val x = (cx + dx).coerceIn(0, w - 1)
                    val y = (cy + dy).coerceIn(0, h - 1)
                    val isEye = dy == 0 && (dx == -1 || dx == 1)
                    img.setPixel(x, y, when {
                        isEye -> eye
                        dy > 1 -> shade
                        else -> bone
                    })
                }
            }
        }
        // Crossed bone underneath
        for (i in -4..4) {
            val bx = (cx + i).coerceIn(0, w - 1)
            val by = (cy + 3 + (i % 2)).coerceIn(0, h - 1)
            img.setPixel(bx, by, if (i % 2 == 0) bone else stone.shadow)
        }
    }

    private fun drawCrate(img: TileImage, w: Int, h: Int, wood: PaletteRamp) {
        val x0 = (w * 0.16f).toInt()
        val x1 = (w * 0.84f).toInt()
        val y0 = (h * 0.16f).toInt()
        val y1 = (h * 0.84f).toInt()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val border = x == x0 || x == x1 || y == y0 || y == y1
                val diag = abs((x - x0) - (y - y0)) <= 0
                img.setPixel(x, y, when {
                    border -> wood.shadow
                    diag -> wood.highlight
                    else -> wood.mid
                })
            }
        }
    }

    private fun drawUrn(img: TileImage, w: Int, h: Int, clay: PaletteRamp, stone: PaletteRamp) {
        val cx = w * 0.5f
        val cy = h * 0.58f
        for (y in (h * 0.22f).toInt()..(h * 0.84f).toInt()) {
            val isNeck = y < h * 0.34f
            val halfW = if (isNeck) (w * 0.14f).toInt() else (w * 0.28f * (1f - abs(y - cy) / (h * 0.42f))).toInt().coerceAtLeast(2)
            for (x in (w / 2 - halfW)..(w / 2 + halfW)) {
                val edge = x == w / 2 - halfW || x == w / 2 + halfW || y == (h * 0.84f).toInt()
                img.setPixel(x, y, when {
                    edge -> stone.shadow
                    x < w / 2 -> clay.highlight
                    else -> clay.mid
                })
            }
        }
    }

    private fun drawWeaponRack(img: TileImage, w: Int, h: Int, wood: PaletteRamp, stone: PaletteRamp) {
        val x0 = (w * 0.20f).toInt()
        val x1 = (w * 0.80f).toInt()
        val barY = (h * 0.28f).toInt()
        for (y in barY..(h * 0.86f).toInt()) {
            img.setPixel(x0, y, wood.shadow)
            img.setPixel(x1, y, wood.shadow)
        }
        for (x in x0..x1) img.setPixel(x, barY, wood.highlight)
        // Sword blade hanging on left, shield on right
        val sx = (w * 0.36f).toInt()
        for (y in (barY + 1)..(h * 0.78f).toInt()) img.setPixel(sx, y, stone.highlight)
        val scx = (w * 0.64f).toInt()
        val scy = (h * 0.54f).toInt()
        for (dy in -2..2) for (dx in -2..2) {
            if (dx * dx + dy * dy <= 5) img.setPixel(scx + dx, scy + dy, stone.mid)
        }
    }

    private fun drawSpikeTrap(img: TileImage, w: Int, h: Int, stone: PaletteRamp) {
        for (py in listOf((h * 0.32f).toInt(), (h * 0.72f).toInt())) {
            for (px in listOf((w * 0.28f).toInt(), (w * 0.72f).toInt())) {
                img.setPixel(px, py - 2, stone.highlight)
                img.setPixel(px, py - 1, stone.highlight)
                img.setPixel(px - 1, py, stone.mid)
                img.setPixel(px, py, stone.shadow)
                img.setPixel(px + 1, py, stone.mid)
            }
        }
    }

    private fun drawPressurePlate(img: TileImage, w: Int, h: Int, stone: PaletteRamp) {
        val x0 = (w * 0.18f).toInt()
        val x1 = (w * 0.82f).toInt()
        val y0 = (h * 0.22f).toInt()
        val y1 = (h * 0.78f).toInt()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val edge = x == x0 || x == x1 || y == y0 || y == y1
                val top = y == y0 + 1 || x == x0 + 1
                img.setPixel(x, y, when {
                    edge -> stone.shadow
                    top -> stone.highlight
                    else -> stone.mid
                })
            }
        }
    }

    private fun drawPagodaRoof(
        img: TileImage,
        w: Int,
        h: Int,
        roof: PaletteRamp,
        wood: PaletteRamp,
        stone: PaletteRamp,
        style: PixelStyle
    ) {
        val gold = PalettePresets.CELESTIAL_GOLD
        val lacquer = PalettePresets.LACQUER_RED
        val jade = PalettePresets.JADE_GREEN
        val roofPal = if (roof == PalettePresets.TERRACOTTA_BRICK) jade else roof

        // Golden spire finial at apex
        val cx = w / 2
        for (y in (h * 0.03f).toInt()..(h * 0.12f).toInt()) {
            img.setPixel(cx, y, gold.highlight)
            if (y > h * 0.07f) {
                img.setPixel((cx - 1).coerceAtLeast(0), y, gold.mid)
                img.setPixel((cx + 1).coerceAtMost(w - 1), y, gold.shadow)
            }
        }

        // Two-tiered curved pagoda roof with upturned flying eaves
        val tiers = listOf(
            Triple((h * 0.12f).toInt(), (h * 0.30f).toInt(), 0.34f),
            Triple((h * 0.31f).toInt(), (h * 0.54f).toInt(), 0.46f)
        )
        for ((y0, y1, maxHalfSpan) in tiers) {
            for (y in y0..y1) {
                val frac = (y - y0).toFloat() / (y1 - y0).coerceAtLeast(1)
                // Swooping concave curve + upturned tip flare near bottom of tier
                val curve = frac * frac * 0.65f + frac * 0.35f
                val halfW = ((0.12f + curve * (maxHalfSpan - 0.12f)) * w).toInt()
                for (x in (cx - halfW)..(cx + halfW)) {
                    if (x !in 0 until w) continue
                    val distFromCenter = abs(x - cx).toFloat() / halfW.coerceAtLeast(1)
                    // Upturned eave lift at outer 20% of roof
                    val eaveLift = if (distFromCenter > 0.78f) ((distFromCenter - 0.78f) * 14f).toInt() else 0
                    val ry = (y - eaveLift).coerceIn(0, h - 1)
                    val isGoldEave = y >= y1 - 1 || distFromCenter > 0.90f
                    val isFlute = (x - cx) % 4 == 0
                    val col = when {
                        isGoldEave -> if (y == y1) gold.shadow else gold.highlight
                        isFlute -> roofPal.shadow
                        style == PixelStyle.CLASSIC_RPG -> PixelDither.sampleDithered(roofPal, 0.80f - frac * 0.35f, x, ry, 4, 0.14f)
                        else -> roofPal.sample(0.80f - frac * 0.35f)
                    }
                    img.setPixel(x, ry, col)
                }
            }
        }

        // Carved marble terrace base & steps
        val baseTop = (h * 0.82f).toInt()
        val baseBot = (h * 0.94f).toInt()
        for (y in baseTop..baseBot) {
            for (x in (w * 0.10f).toInt()..(w * 0.90f).toInt()) {
                val inStairs = abs(x - cx) <= (w * 0.12f).toInt()
                val col = when {
                    y == baseTop || y == baseBot -> stone.shadow
                    inStairs && y % 2 == 0 -> stone.highlight
                    else -> stone.mid
                }
                img.setPixel(x, y, col)
            }
        }

        // Crimson lacquer colonnade & lattice screen wall
        val wallTop = (h * 0.54f).toInt()
        for (y in wallTop until baseTop) {
            for (x in (w * 0.16f).toInt()..(w * 0.84f).toInt()) {
                val isPillar = abs(x - (w * 0.20f).toInt()) <= 2 ||
                    abs(x - (w * 0.38f).toInt()) <= 1 ||
                    abs(x - (w * 0.62f).toInt()) <= 1 ||
                    abs(x - (w * 0.80f).toInt()) <= 2
                val isDoorway = abs(x - cx) <= (w * 0.08f).toInt() && y > (h * 0.62f).toInt()
                val isLattice = (x + y) % 3 == 0 && y < (h * 0.72f).toInt()
                val col = when {
                    isPillar -> if (y == wallTop + 1) gold.mid else lacquer.mid
                    isDoorway -> PaletteRamp.rgba(22, 14, 18)
                    isLattice -> gold.shadow
                    else -> wood.shadow
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawPaifangGate(
        img: TileImage,
        w: Int,
        h: Int,
        roof: PaletteRamp,
        stone: PaletteRamp,
        style: PixelStyle
    ) {
        val gold = PalettePresets.CELESTIAL_GOLD
        val lacquer = PalettePresets.LACQUER_RED
        val jade = if (roof == PalettePresets.TERRACOTTA_BRICK) PalettePresets.JADE_GREEN else roof
        val cx = w / 2

        // Central upper roof tier (spans middle 60% of 3x2)
        val topY0 = (h * 0.08f).toInt()
        val topY1 = (h * 0.26f).toInt()
        for (y in topY0..topY1) {
            val frac = (y - topY0).toFloat() / (topY1 - topY0).coerceAtLeast(1)
            val halfW = ((0.22f + frac * 0.14f) * w).toInt()
            for (x in (cx - halfW)..(cx + halfW)) {
                val dist = abs(x - cx).toFloat() / halfW.coerceAtLeast(1)
                val lift = if (dist > 0.75f) ((dist - 0.75f) * 12f).toInt() else 0
                val ry = (y - lift).coerceIn(0, h - 1)
                val col = when {
                    y == topY1 || dist > 0.90f -> gold.highlight
                    (x - cx) % 3 == 0 -> jade.shadow
                    style == PixelStyle.CLASSIC_RPG -> PixelDither.sampleDithered(jade, 0.78f - frac * 0.3f, x, ry, 4, 0.12f)
                    else -> jade.sample(0.78f - frac * 0.3f)
                }
                img.setPixel(x, ry, col)
            }
        }

        // Inscribed gold lintel plaque & crossbeam
        val beamY0 = (h * 0.27f).toInt()
        val beamY1 = (h * 0.38f).toInt()
        for (y in beamY0..beamY1) {
            for (x in (w * 0.10f).toInt()..(w * 0.90f).toInt()) {
                val inPlaque = abs(x - cx) <= (w * 0.12f).toInt() && y in (beamY0 + 1) until beamY1
                val col = when {
                    inPlaque -> if ((x + y) % 2 == 0) gold.highlight else lacquer.shadow
                    y == beamY0 || y == beamY1 -> gold.mid
                    else -> lacquer.mid
                }
                img.setPixel(x, y, col)
            }
        }

        // Four crimson lacquer & stone pedestal pillars (outer two + inner arch pillars)
        val pillarCenters = listOf((w * 0.15f).toInt(), (w * 0.34f).toInt(), (w * 0.66f).toInt(), (w * 0.85f).toInt())
        for (px in pillarCenters) {
            for (y in (beamY1 + 1)..(h * 0.92f).toInt()) {
                val isBase = y >= (h * 0.80f).toInt()
                val halfP = if (isBase) 3 else 2
                for (dx in -halfP..halfP) {
                    val x = (px + dx).coerceIn(0, w - 1)
                    val col = when {
                        dx == -halfP || dx == halfP -> if (isBase) stone.shadow else lacquer.shadow
                        isBase -> if (dx < 0) stone.highlight else stone.mid
                        else -> if (dx < 0) lacquer.highlight else lacquer.mid
                    }
                    img.setPixel(x, y, col)
                }
            }
        }
    }

    private fun drawDragonBrazier(img: TileImage, w: Int, h: Int, stone: PaletteRamp, foliage: PaletteRamp) {
        val bronze = PalettePresets.DRAGON_BRONZE
        val gold = PalettePresets.CELESTIAL_GOLD
        val jade = if (foliage == PalettePresets.MEADOW_GRASS) PalettePresets.JADE_GREEN else foliage
        val cx = w / 2

        // Rising jade incense smoke & mystic flame in upper tile
        for (y in (h * 0.08f).toInt()..(h * 0.42f).toInt()) {
            val frac = (y - h * 0.08f) / (h * 0.34f)
            val sway = kotlin.math.sin(frac * 6.0).toInt()
            val halfW = ((1f - abs(frac - 0.55f) * 1.4f) * w * 0.22f).toInt().coerceAtLeast(1)
            for (x in (cx + sway - halfW)..(cx + sway + halfW)) {
                if (x in 0 until w) {
                    val col = if (abs(x - (cx + sway)) <= 1 && frac > 0.4f) gold.highlight else jade.sample(0.45f + frac * 0.45f)
                    img.setPixel(x, y, col)
                }
            }
        }

        // Ornate bronze cauldron bowl with dragon-ear handles
        val bowlY0 = (h * 0.42f).toInt()
        val bowlY1 = (h * 0.68f).toInt()
        for (y in bowlY0..bowlY1) {
            val ny = (y - bowlY0).toFloat() / (bowlY1 - bowlY0).coerceAtLeast(1)
            val belly = (w * (0.30f - abs(ny - 0.45f) * 0.22f)).toInt().coerceAtLeast(3)
            for (x in (cx - belly)..(cx + belly)) {
                if (x !in 0 until w) continue
                val isRim = y <= bowlY0 + 2
                val isDragonMotif = ny in 0.35f..0.65f && (x + y) % 3 == 0
                val col = when {
                    isRim -> gold.highlight
                    x == cx - belly || x == cx + belly -> bronze.shadow
                    isDragonMotif -> gold.mid
                    x < cx -> bronze.highlight
                    else -> bronze.mid
                }
                img.setPixel(x, y, col)
            }
        }

        // Three tripod legs & stone base
        for (y in (bowlY1 + 1)..(h * 0.90f).toInt()) {
            for (offset in listOf(-4, 0, 4)) {
                val lx = (cx + offset).coerceIn(0, w - 1)
                img.setPixel(lx, y, if (offset <= 0) bronze.highlight else bronze.shadow)
            }
        }
        drawEllipse(img, cx.toFloat(), h * 0.90f, w * 0.34f, h * 0.04f, stone.shadow)
    }

    private fun drawJadeLionStatue(img: TileImage, w: Int, h: Int, stone: PaletteRamp, style: PixelStyle) {
        val jade = if (stone == PalettePresets.SLATE_STONE || stone == PalettePresets.CLASSIC_RPG_STONE) {
            PalettePresets.JADE_GREEN
        } else {
            stone
        }
        val gold = PalettePresets.CELESTIAL_GOLD
        val cx = w / 2

        // Carved lotus pedestal (bottom 35% of 1x2)
        val pedTop = (h * 0.62f).toInt()
        val pedBot = (h * 0.92f).toInt()
        for (y in pedTop..pedBot) {
            val isWaist = y in (pedTop + 3)..(pedBot - 4)
            val halfW = if (isWaist) (w * 0.26f).toInt() else (w * 0.34f).toInt()
            for (x in (cx - halfW)..(cx + halfW)) {
                val edge = x == cx - halfW || x == cx + halfW || y == pedTop || y == pedBot
                img.setPixel(x, y, when {
                    edge -> stone.shadow
                    y == pedTop + 1 -> gold.mid
                    x < cx -> stone.highlight
                    else -> stone.mid
                })
            }
        }

        // Guardian lion (Foo Dog) seated body, curled mane, and golden pearl under paw
        val bodyTop = (h * 0.16f).toInt()
        for (y in bodyTop until pedTop) {
            val ny = (y - bodyTop).toFloat() / (pedTop - bodyTop).coerceAtLeast(1)
            val halfW = when {
                ny < 0.38f -> (w * 0.28f).toInt() // Curled mane & head
                ny < 0.65f -> (w * 0.22f).toInt() // Chest
                else -> (w * 0.26f).toInt()       // Seated haunches & paws
            }
            for (x in (cx - halfW)..(cx + halfW)) {
                val isOutline = x == cx - halfW || x == cx + halfW || y == bodyTop
                val isEye = ny in 0.18f..0.24f && abs(x - cx) == 3
                val isManeCurl = ny < 0.38f && (x + y) % 3 == 0
                val isPearl = ny > 0.80f && x in (cx + 1)..(cx + 4)
                val shade = if (x < cx) 0.78f else 0.46f
                val col = when {
                    isEye || isPearl -> gold.highlight
                    isOutline -> jade.shadow
                    isManeCurl -> jade.highlight
                    style == PixelStyle.CLASSIC_RPG -> PixelDither.sampleDithered(jade, shade, x, y, 4, 0.14f)
                    else -> jade.sample(shade)
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawBambooGrove(img: TileImage, w: Int, h: Int, foliage: PaletteRamp, style: PixelStyle, s: Long) {
        val bamboo = if (foliage == PalettePresets.MEADOW_GRASS || foliage == PalettePresets.CLASSIC_RPG_GRASS) {
            PalettePresets.JADE_GREEN
        } else {
            foliage
        }
        drawEllipse(img, w * 0.5f, h * 0.90f, w * 0.34f, h * 0.05f, PaletteRamp.rgba(12, 18, 20, 128))

        // Three vertical segmented bamboo culms
        val stalks = listOf(
            Triple((w * 0.32f).toInt(), (h * 0.14f).toInt(), (h * 0.89f).toInt()),
            Triple((w * 0.52f).toInt(), (h * 0.06f).toInt(), (h * 0.91f).toInt()),
            Triple((w * 0.68f).toInt(), (h * 0.20f).toInt(), (h * 0.88f).toInt())
        )
        val nodeStep = (h / 7).coerceAtLeast(4)
        for ((sx, y0, y1) in stalks) {
            for (y in y0..y1) {
                val isNodeRing = y % nodeStep == 0
                for (dx in -1..1) {
                    val x = (sx + dx).coerceIn(0, w - 1)
                    val col = when {
                        isNodeRing -> bamboo.highlight
                        dx == -1 -> bamboo.sample(0.82f)
                        dx == 0 -> bamboo.mid
                        else -> bamboo.shadow
                    }
                    img.setPixel(x, y, col)
                }
            }
        }

        // Sharp lance-shaped bamboo leaf sprays near upper half
        for (y in (h * 0.04f).toInt()..(h * 0.58f).toInt()) {
            for (x in (w * 0.12f).toInt()..(w * 0.88f).toInt()) {
                val n = TileNoise.hash(x / 2, y / 2, s + 31L)
                val distCenter = hypot((x - w * 0.5f) / (w * 0.38f), (y - h * 0.28f) / (h * 0.26f))
                if (distCenter <= 1f && n > 0.46f && (x + y * 2) % 3 != 0) {
                    val col = if (style == PixelStyle.CLASSIC_RPG) {
                        PixelDither.sampleDithered(bamboo, 0.85f - distCenter * 0.45f, x, y, 4, 0.14f)
                    } else {
                        bamboo.sample(0.85f - distCenter * 0.45f)
                    }
                    img.setPixel(x, y, col)
                }
            }
        }
    }

    private fun drawPaperLantern(img: TileImage, w: Int, h: Int, roof: PaletteRamp) {
        val lacquer = if (roof == PalettePresets.TERRACOTTA_BRICK) PalettePresets.LACQUER_RED else roof
        val gold = PalettePresets.CELESTIAL_GOLD
        val cx = w / 2

        // Hanging cord & top gold cap
        for (y in 1..(h * 0.20f).toInt()) {
            img.setPixel(cx, y, gold.shadow)
        }
        // Lantern oval body with horizontal bamboo ribs and warm inner glow
        val cy = h * 0.48f
        val rx = w * 0.30f
        val ry = h * 0.26f
        for (y in (h * 0.20f).toInt()..(h * 0.76f).toInt()) {
            for (x in 0 until w) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                val d = hypot(dx, dy)
                if (d <= 1f) {
                    val isCap = abs(dy) > 0.78f
                    val isRib = y % 4 == 0
                    val col = when {
                        isCap -> gold.highlight
                        d > 0.86f || isRib -> lacquer.shadow
                        d < 0.38f -> gold.highlight
                        else -> lacquer.sample(0.82f - d * 0.45f)
                    }
                    img.setPixel(x, y, col)
                }
            }
        }
        // Flowing golden silk tassel
        for (y in (h * 0.77f).toInt()..(h * 0.94f).toInt()) {
            img.setPixel(cx, y, gold.highlight)
            if (y > h * 0.84f) {
                img.setPixel((cx - 1).coerceAtLeast(0), y, lacquer.mid)
                img.setPixel((cx + 1).coerceAtMost(w - 1), y, lacquer.mid)
            }
        }
    }

    private fun drawBlackSpiritRift(img: TileImage, w: Int, h: Int, stone: PaletteRamp, spirit: PaletteRamp) {
        val obsidian = PalettePresets.ABYSSAL_OBSIDIAN
        val cx = w * 0.5f
        val cy = h * 0.44f

        // Jagged obsidian monolith shards around base
        for (y in (h * 0.52f).toInt()..(h * 0.92f).toInt()) {
            val ny = (y - h * 0.52f) / (h * 0.40f)
            val halfW = ((0.18f + ny * 0.22f) * w).toInt()
            for (x in (cx.toInt() - halfW)..(cx.toInt() + halfW)) {
                if (x in 0 until w) {
                    val isVein = abs(x - cx.toInt()) <= 1 || (x + y) % 7 == 0
                    img.setPixel(x, y, if (isVein) spirit.mid else obsidian.sample(0.25f + (1f - ny) * 0.35f))
                }
            }
        }

        // Swirling abyssal vortex + glowing red Black Spirit eyes & jagged claws
        val rx = w * 0.38f
        val ry = h * 0.32f
        for (y in (h * 0.08f).toInt()..(h * 0.76f).toInt()) {
            for (x in 0 until w) {
                val dx = (x + 0.5f - cx) / rx
                val dy = (y + 0.5f - cy) / ry
                val d = hypot(dx, dy)
                val angle = kotlin.math.atan2(dy.toDouble(), dx.toDouble()).toFloat()
                val tendril = kotlin.math.sin((angle * 5f + d * 6f).toDouble()).toFloat() * 0.16f
                if (d + tendril <= 0.95f) {
                    val isEye = abs(dy + 0.12f) < 0.09f && abs(abs(dx) - 0.28f) < 0.11f
                    val isSmile = dy in 0.12f..0.24f && abs(dx) < 0.36f && (x + y) % 2 == 0
                    val col = when {
                        isEye || isSmile -> spirit.highlight
                        d > 0.70f -> spirit.mid
                        d > 0.42f -> obsidian.shadow
                        else -> spirit.shadow
                    }
                    img.setPixel(x, y, col)
                }
            }
        }
    }

    private fun drawMedievalTent(
        img: TileImage,
        w: Int,
        h: Int,
        roof: PaletteRamp,
        wood: PaletteRamp,
        stone: PaletteRamp,
        style: PixelStyle
    ) {
        val iron = PalettePresets.DARK_IRON
        val banner = if (roof == PalettePresets.TERRACOTTA_BRICK) PalettePresets.RIFT_CRIMSON else roof
        val cx = w / 2

        // Iron halberd/spear poles & guy ropes
        for (px in listOf((w * 0.18f).toInt(), cx, (w * 0.82f).toInt())) {
            val topY = if (px == cx) (h * 0.06f).toInt() else (h * 0.22f).toInt()
            for (y in topY..(h * 0.90f).toInt()) {
                img.setPixel(px, y, if (y < topY + 4) iron.highlight else wood.shadow)
            }
        }

        // Sloped medieval pavilion canopy with vertical heraldic stripes & scalloped valance
        val canopyTop = (h * 0.12f).toInt()
        val canopyMid = (h * 0.50f).toInt()
        for (y in canopyTop..canopyMid) {
            val frac = (y - canopyTop).toFloat() / (canopyMid - canopyTop).coerceAtLeast(1)
            val sag = frac * (0.38f * w)
            val halfW = (w * 0.06f + sag).toInt()
            for (x in (cx - halfW)..(cx + halfW)) {
                if (x !in 0 until w) continue
                val stripe = ((x - cx + w) / 6) % 2 == 0
                val isValance = y >= canopyMid - 3
                val pal = if (stripe) banner else stone
                val shade = (0.82f - frac * 0.35f).coerceIn(0.15f, 0.90f)
                val col = when {
                    isValance && (x / 3) % 2 == 0 -> iron.highlight
                    style == PixelStyle.CLASSIC_RPG -> PixelDither.sampleDithered(pal, shade, x, y, 4, 0.14f)
                    else -> pal.sample(shade)
                }
                img.setPixel(x, y, col)
            }
        }

        // Tent side walls, dark open flap entrance, and iron ground stakes
        val wallBot = (h * 0.88f).toInt()
        for (y in (canopyMid + 1)..wallBot) {
            val ny = (y - canopyMid).toFloat() / (wallBot - canopyMid).coerceAtLeast(1)
            val flapGap = (ny * w * 0.14f).toInt()
            for (x in (w * 0.14f).toInt()..(w * 0.86f).toInt()) {
                val distC = abs(x - cx)
                val col = when {
                    distC < flapGap -> PaletteRamp.rgba(14, 12, 18)
                    distC == flapGap || distC == flapGap + 1 -> banner.highlight
                    x == (w * 0.14f).toInt() || x == (w * 0.86f).toInt() || y == wallBot -> iron.shadow
                    else -> stone.sample(0.55f - ny * 0.25f)
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawSiegeBallista(img: TileImage, w: Int, h: Int, wood: PaletteRamp, stone: PaletteRamp) {
        val iron = if (stone == PalettePresets.SLATE_STONE) PalettePresets.DARK_IRON else stone
        val cx = w / 2
        val cy = h / 2

        // Heavy timber tripod base & iron braces
        for (i in -8..8) {
            val y = (cy + 2 + abs(i) / 2).coerceIn(0, h - 1)
            img.setPixel((cx + i).coerceIn(0, w - 1), y, wood.shadow)
        }
        // Bowed torsion arms & bowstring
        val bowY = (h * 0.30f).toInt()
        for (dx in -(w * 0.38f).toInt()..(w * 0.38f).toInt()) {
            val curveY = bowY + (dx * dx) / (w.coerceAtLeast(8))
            if (curveY in 0 until h) {
                img.setPixel((cx + dx).coerceIn(0, w - 1), curveY, wood.highlight)
                if (curveY + 1 < h) img.setPixel((cx + dx).coerceIn(0, w - 1), curveY + 1, iron.mid)
            }
            // Bowstring to stock rear
            val stringY = bowY + ((h * 0.72f - bowY) * (1f - abs(dx).toFloat() / (w * 0.38f))).toInt()
            if (stringY in 0 until h && abs(dx) > 1) {
                img.setPixel((cx + dx).coerceIn(0, w - 1), stringY, iron.highlight)
            }
        }
        // Central stock rail & steel siege bolt tip
        for (y in (h * 0.12f).toInt()..(h * 0.84f).toInt()) {
            for (dx in -1..1) {
                val x = (cx + dx).coerceIn(0, w - 1)
                val col = when {
                    y < (h * 0.26f).toInt() -> iron.highlight
                    dx == 0 -> iron.mid
                    else -> wood.mid
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawTradeWagon(
        img: TileImage,
        w: Int,
        h: Int,
        roof: PaletteRamp,
        wood: PaletteRamp,
        stone: PaletteRamp,
        style: PixelStyle
    ) {
        val iron = PalettePresets.DARK_IRON
        val canvasPal = if (roof == PalettePresets.TERRACOTTA_BRICK) PalettePresets.RELIC_AMBER else roof

        // Ground shadow under wagon
        drawEllipse(img, w * 0.50f, h * 0.88f, w * 0.38f, h * 0.06f, PaletteRamp.rgba(12, 14, 18, 128))

        // Ribbed canvas cover (upper wagon body across 3x2)
        val x0 = (w * 0.14f).toInt()
        val x1 = (w * 0.78f).toInt()
        val topY = (h * 0.16f).toInt()
        val bedY = (h * 0.56f).toInt()
        for (y in topY..bedY) {
            val ny = (y - topY).toFloat() / (bedY - topY).coerceAtLeast(1)
            val archInset = ((1f - kotlin.math.sin(ny * 1.57f)) * w * 0.06f).toInt()
            for (x in (x0 + archInset)..(x1 - archInset)) {
                val isRib = (x - x0) % (w / 7).coerceAtLeast(4) == 0
                val shade = (0.85f - ny * 0.38f).coerceIn(0.20f, 0.92f)
                val col = when {
                    isRib -> wood.shadow
                    style == PixelStyle.CLASSIC_RPG -> PixelDither.sampleDithered(canvasPal, shade, x, y, 4, 0.12f)
                    else -> canvasPal.sample(shade)
                }
                img.setPixel(x, y, col)
            }
        }

        // Reinforced timber wagon bed & front hitch shafts
        val bedBot = (h * 0.72f).toInt()
        for (y in (bedY + 1)..bedBot) {
            for (x in x0..(w * 0.88f).toInt()) {
                if (x > x1 && y !in (bedBot - 3)..(bedBot - 1)) continue
                val isIronBrace = (x - x0) % 8 == 0 || y == bedBot
                img.setPixel(x, y, if (isIronBrace) iron.mid else wood.sample(0.65f))
            }
        }

        // Two spoked wooden wheels with iron tires
        val wheelR = (h * 0.15f).toInt().coerceAtLeast(4)
        val wheelCy = (h * 0.74f).toInt()
        for (wheelCx in listOf((w * 0.28f).toInt(), (w * 0.64f).toInt())) {
            for (dy in -wheelR..wheelR) {
                for (dx in -wheelR..wheelR) {
                    val d = hypot(dx.toFloat(), dy.toFloat())
                    if (d <= wheelR) {
                        val isTire = d >= wheelR - 1.4f
                        val isHub = d <= 2.0f
                        val isSpoke = dx == 0 || dy == 0 || abs(dx) == abs(dy)
                        if (isTire || isHub || isSpoke) {
                            img.setPixel(
                                (wheelCx + dx).coerceIn(0, w - 1),
                                (wheelCy + dy).coerceIn(0, h - 1),
                                if (isTire || isHub) iron.highlight else wood.highlight
                            )
                        }
                    }
                }
            }
        }
    }

    private fun drawGargoyleSpire(img: TileImage, w: Int, h: Int, stone: PaletteRamp, style: PixelStyle) {
        val iron = if (stone == PalettePresets.SLATE_STONE || stone == PalettePresets.CLASSIC_RPG_STONE) {
            PalettePresets.DARK_IRON
        } else {
            stone
        }
        val spirit = PalettePresets.RIFT_CRIMSON
        val cx = w / 2

        // Horned winged gargoyle perched on top of gothic spire (top 38% of 1x2)
        val gargBot = (h * 0.40f).toInt()
        for (y in (h * 0.08f).toInt()..gargBot) {
            val ny = (y - h * 0.08f) / (h * 0.32f)
            // Bat-like flared wings in upper-mid rows
            val wingSpan = if (ny in 0.18f..0.62f) ((1f - abs(ny - 0.38f) * 2.5f) * w * 0.42f).toInt() else (w * 0.18f).toInt()
            for (x in (cx - wingSpan)..(cx + wingSpan)) {
                if (x !in 0 until w) continue
                val isEye = ny in 0.22f..0.28f && abs(x - cx) == 2
                val isEdge = abs(x - cx) == wingSpan || y == (h * 0.08f).toInt()
                val col = when {
                    isEye -> spirit.highlight
                    isEdge -> iron.shadow
                    else -> iron.sample(0.68f - abs(x - cx).toFloat() / w)
                }
                img.setPixel(x, y, col)
            }
        }

        // Fluted dark-iron & stone gothic buttress pillar below
        for (y in (gargBot + 1)..(h * 0.92f).toInt()) {
            val isCollar = y <= gargBot + 3 || y >= (h * 0.84f).toInt()
            val halfW = if (isCollar) (w * 0.28f).toInt() else (w * 0.20f).toInt()
            for (x in (cx - halfW)..(cx + halfW)) {
                val nx = (x - (cx - halfW)).toFloat() / (halfW * 2).coerceAtLeast(1)
                val isFlute = (x - cx) % 3 == 0 && !isCollar
                val shade = if (isFlute) 0.22f else 0.80f - nx * 0.50f
                val col = if (x == cx - halfW || x == cx + halfW) {
                    iron.shadow
                } else if (style == PixelStyle.CLASSIC_RPG) {
                    PixelDither.sampleDithered(iron, shade, x, y, 4, 0.14f)
                } else {
                    iron.sample(shade)
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawAncientExcavation(img: TileImage, w: Int, h: Int, stone: PaletteRamp, amber: PaletteRamp) {
        val relicStone = if (stone == PalettePresets.SLATE_STONE) PalettePresets.ABYSSAL_OBSIDIAN else stone
        val cx = w * 0.5f
        val cy = h * 0.36f

        // Stepped ancient sandstone/obsidian ziggurat pedestal (bottom half)
        for (y in (h * 0.56f).toInt()..(h * 0.92f).toInt()) {
            val step = ((y - h * 0.56f) / (h * 0.12f)).toInt()
            val halfW = ((0.20f + step * 0.06f) * w).toInt()
            for (x in (cx.toInt() - halfW)..(cx.toInt() + halfW)) {
                if (x in 0 until w) {
                    val isCircuit = (x == cx.toInt() || y % 6 == 0) && abs(x - cx.toInt()) < halfW - 2
                    img.setPixel(x, y, if (isCircuit) amber.mid else relicStone.sample(0.45f))
                }
            }
        }

        // Levitating gyroscopic stone rings & glowing amber power core
        val outerR = w * 0.36f
        for (y in (h * 0.08f).toInt()..(h * 0.64f).toInt()) {
            for (x in 0 until w) {
                val d = hypot(x + 0.5f - cx, y + 0.5f - cy)
                when {
                    d <= outerR * 0.32f -> img.setPixel(x, y, if (d < outerR * 0.16f) amber.highlight else amber.mid)
                    d in (outerR * 0.52f)..(outerR * 0.66f) -> img.setPixel(x, y, amber.sample(0.75f))
                    d in (outerR * 0.78f)..outerR -> {
                        val isRune = (x + y) % 4 == 0
                        img.setPixel(x, y, if (isRune) amber.highlight else relicStone.highlight)
                    }
                }
            }
        }
    }

    /**
     * Renders 16x16-native classic pixel-grid templates with selective/hue-shifted outlines
     * and nearest-neighbor scaling to [w]x[h].
     */
    private fun renderClassicRuinsProp(
        img: TileImage,
        w: Int,
        h: Int,
        kind: PropKind,
        stone: PaletteRamp,
        secondary: PaletteRamp?,
        roof: PaletteRamp
    ): Boolean {
        val grid: List<String> = when (kind) {
            PropKind.PILLAR -> listOf(
                "..dddddddddddd..",
                ".dhhhhhhhhhhhho.",
                ".dhhllllllllmmo.",
                ".dmmbbbbbbbbmmo.",
                "..ddggggggggdo..",
                "...dhhmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dhlmlmlmdo...",
                "...dmmggggmmo...",
                "..dhhllllllmdo..",
                ".dhhllllllllmmo.",
                ".dmmddddddddmmo.",
                ".dooooooooooooo.",
                "................",
                "................"
            )
            PropKind.SHRINE -> listOf(
                ".....dddddd.....",
                "....dhhhhhho....",
                "....dhlbbblo....",
                "...dhlbccbblo...",
                "...dhlbccbmlo...",
                "...dhlbBBbmlo...",
                "...dhhggggmlo...",
                "...dhlmlmlmlo...",
                "...dhlmbbmmlo...",
                "...dhlmbBmmlo...",
                "...dhlmlmlmlo...",
                "...dhlmlmlmlo...",
                "...dhlmlmlmlo...",
                "..dhhllllllmdo..",
                "..dmmggggggmmo..",
                "..dhlmbbbbmlmo..",
                "..dhlmbccbmlmo..",
                "..dhlmbbbbmlmo..",
                "..dhlmlmlmlmlo..",
                "..dhlmlmlmlmlo..",
                "..dhlmlmlmlmlo..",
                "..dhlmlmlmlmlo..",
                "..dhlmlmlmlmlo..",
                "..dhlmlmlmlmlo..",
                "..dhlmlmlmlmlo..",
                ".dhhllllllllmdo.",
                ".dmmggggggggmmo.",
                ".dhlllllllllmmo.",
                "dhhmmmmmmmmmmmdo",
                "dmmmmmmmmmmmmmmo",
                ".oooooooooooooo.",
                "................"
            )
            PropKind.URN -> listOf(
                "................",
                ".....dddddd.....",
                "....dhhhhhho....",
                ".....dggggo.....",
                "....dhlmlmdo....",
                "...dhhllllmdo...",
                "..dhhllbbllmdo..",
                "..dhllbccblmdo..",
                "..dhllbbbbmmdo..",
                "..dmlgggggmmdo..",
                "..dmmlmlmlmmdo..",
                "...dmmmmmmmmo...",
                "...dmmddddmmo...",
                "....dhhhhhho....",
                "....oooooooo....",
                "................"
            )
            PropKind.STAIRS_DOWN -> listOf(
                ".dddddddddddddd.",
                ".dhllllllllllmo.",
                ".dmdhhhhhhhhdmo.",
                ".dmdllllllllmdo.",
                ".dmdmmmmmmmmdmo.",
                ".dmdhhhhhhhhdmo.",
                ".dmdllllllllmdo.",
                ".dmdmmmmmmmmdmo.",
                ".dmdhhhhhhhddmo.",
                ".dmdllllllmdvvo.",
                ".dmdmmmmmmmvvvo.",
                ".dmdhhhhddvvvvo.",
                ".dmdllmdvvvvvvo.",
                ".dmdmdvvvvvvvvo.",
                ".dooooooooooooo.",
                "................"
            )
            PropKind.STAIRS_UP -> listOf(
                ".dddddddddddddd.",
                ".dhhhhhhhhhhhho.",
                ".dmdhhhhhhhhdmo.",
                ".dmdllllllllmdo.",
                ".dmdhhhhhhhhdmo.",
                ".dmdllllllllmdo.",
                ".dmdmmmmmmmmdmo.",
                ".dmdhhhhhhhhdmo.",
                ".dmdllllllllmdo.",
                ".dmdmmmmmmmmdmo.",
                ".dmdhhhhhhhhdmo.",
                ".dmdllllllllmdo.",
                ".dmdmmmmmmmmdmo.",
                ".dhllllllllllmo.",
                ".dooooooooooooo.",
                "................"
            )
            PropKind.GARGOYLE_PLAQUE -> listOf(
                ".dddddddddddddd.",
                ".dhhhhhhhhhhhho.",
                ".dhlllllllllmmo.",
                ".dhlmddmmddmlmo.",
                ".dhlmhhlmhhmlmo.",
                ".dhlmhcmmchmlmo.",
                ".dhlmmmmmmmmlmo.",
                ".dhlmdhhhhdmlmo.",
                ".dhlmdmsmsdmlmo.",
                ".dhlmdmddmdmlmo.",
                ".dhlmmmmmmmmlmo.",
                ".dhmddddddddmmo.",
                ".dhlllllllllmmo.",
                ".dmmmmmmmmmmmmo.",
                ".dooooooooooooo.",
                "................"
            )
            PropKind.WALL_BANNER -> listOf(
                "................",
                ".gddddddddddddg.",
                ".GhhhhhhhhhhhhG.",
                "..dbbbbbbbbbbo..",
                "..dbbbbbbbbbbo..",
                "..dbbbggggbbbo..",
                "..dbbgccccgbbo..",
                "..dbbgccccgbbo..",
                "..dbbbggggbbbo..",
                "..dbbbbbbbbbbo..",
                "..dbbbbbBBBBbo..",
                "..dbbBBBBBBBbo..",
                "..dbBbo..obBbo..",
                "..dbo......obo..",
                "...o........o...",
                "................"
            )
            PropKind.SKULL_PILE -> listOf(
                "................",
                "................",
                ".....dddddd.....",
                "....dsssssso....",
                "...dssssssssso..",
                "...dssovvosso...",
                "...dssssssssso..",
                "..dddssovssddd..",
                ".dsssoSssSossso.",
                "dssssSssssSsssso",
                "dssovvosssovvsso",
                "dssssossssosssso",
                ".dsSsssssssSSso.",
                "..oooooooooooo..",
                "................",
                "................"
            )
            else -> return false
        }
        drawPixelGrid(img, w, h, grid, stone, secondary ?: PalettePresets.RUINS_BLUE, roof)
        return true
    }

    private fun drawPixelGrid(
        img: TileImage,
        w: Int,
        h: Int,
        grid: List<String>,
        stone: PaletteRamp,
        accent: PaletteRamp,
        warm: PaletteRamp
    ) {
        val gridH = grid.size
        val gridW = grid[0].length
        val voidOutline = PaletteRamp.rgba(0x2B, 0x26, 0x32)
        val stoneLit = stone.sample(0.78f)
        val goldMid = PaletteRamp.rgba(0xD8, 0xA8, 0x38)
        val goldDark = PaletteRamp.rgba(0x9E, 0x6C, 0x1E)
        val boneLit = PaletteRamp.rgba(0xEC, 0xE6, 0xD8)
        val boneDark = PaletteRamp.rgba(0xB2, 0xA8, 0x98)

        for (y in 0 until h) {
            val gy = (y * gridH) / h
            val row = grid[gy]
            for (x in 0 until w) {
                val gx = (x * gridW) / w
                val ch = row[gx]
                val color = when (ch) {
                    '.' -> 0
                    'o', 'v' -> voidOutline
                    'd' -> stone.shadow
                    'm' -> stone.mid
                    'l' -> stoneLit
                    'h' -> stone.highlight
                    'b' -> accent.mid
                    'B' -> accent.shadow
                    'c' -> accent.highlight
                    'g' -> goldMid
                    'G' -> goldDark
                    's' -> boneLit
                    'S' -> boneDark
                    'r' -> warm.mid
                    else -> 0
                }
                if (color != 0) {
                    img.setPixel(x, y, color)
                }
            }
        }
    }

    private fun drawHalfTimberHouse(
        img: TileImage,
        w: Int,
        h: Int,
        roof: PaletteRamp,
        timber: PaletteRamp,
        plaster: PaletteRamp
    ) {
        drawEllipse(img, w * 0.5f, h * 0.92f, w * 0.44f, h * 0.06f, PaletteRamp.rgba(16, 14, 24, 115))
        val x0 = (w * 0.06f).toInt()
        val x1 = (w * 0.94f).toInt()
        val roofTop = (h * 0.08f).toInt()
        val roofBot = (h * 0.52f).toInt()
        val wallBot = (h * 0.92f).toInt()

        // Stone chimney on right roof slope
        val chimX0 = (w * 0.72f).toInt()
        val chimX1 = (w * 0.83f).toInt()
        for (y in (h * 0.03f).toInt()..roofBot) {
            for (x in chimX0..chimX1) {
                val isBorder = x == chimX0 || x == chimX1 || y == (h * 0.03f).toInt()
                img.setPixel(x, y, if (isBorder) plaster.shadow else plaster.sample(0.45f))
            }
        }

        // Scalloped pitched shingle roof with gable timber bargeboards
        val shingleH = (tileSize / 6).coerceAtLeast(3)
        val shingleW = (tileSize / 5).coerceAtLeast(3)
        for (y in roofTop..roofBot) {
            val frac = (y - roofTop).toFloat() / (roofBot - roofTop).coerceAtLeast(1)
            val inset = ((1f - frac) * (w * 0.14f)).toInt()
            val rx0 = x0 + inset
            val rx1 = x1 - inset
            for (x in rx0..rx1) {
                val isBargeboard = x <= rx0 + 1 || x >= rx1 - 1 || y == roofTop
                val isEaveLip = y == roofBot - 1
                val isEaveBottom = y == roofBot
                val rowIdx = (y - roofTop) / shingleH
                val sx = x + (if (rowIdx % 2 == 1) shingleW / 2 else 0)
                val isShingleSeam = (y - roofTop) % shingleH == 0 || sx % shingleW == 0
                val col = when {
                    isEaveBottom -> timber.shadow
                    isBargeboard -> timber.mid
                    isEaveLip -> roof.highlight
                    isShingleSeam -> roof.shadow
                    else -> roof.sample((0.82f - frac * 0.32f).coerceIn(0.25f, 0.92f))
                }
                img.setPixel(x, y, col)
            }
        }

        // Half-timbered plaster walls with eave overhang shadow and stone plinth
        val wx0 = x0 + (w * 0.04f).toInt()
        val wx1 = x1 - (w * 0.04f).toInt()
        val plinthY = wallBot - (tileSize / 5).coerceAtLeast(3)
        val midBeamY = (roofBot + plinthY) / 2
        val post1X = wx0 + (wx1 - wx0) / 3
        val post2X = wx0 + ((wx1 - wx0) * 2) / 3

        for (y in (roofBot + 1)..wallBot) {
            for (x in wx0..wx1) {
                val isCornerPost = x in wx0..(wx0 + 1) || x in (wx1 - 1)..wx1
                val isInnerPost = abs(x - post1X) <= 0 || abs(x - post2X) <= 0
                val isHorizBeam = abs(y - midBeamY) <= 0 || y == plinthY
                val isDiagonalBrace = (x in (wx0 + 2) until post1X && abs((x - wx0) - (y - roofBot)) <= 1) ||
                    (x in (post2X + 1) until (wx1 - 1) && abs((wx1 - x) - (y - roofBot)) <= 1)
                val isPlinth = y > plinthY
                val inEaveShadow = y <= roofBot + (tileSize / 8).coerceAtLeast(2)

                val col = when {
                    y == wallBot || x == wx0 || x == wx1 -> timber.shadow
                    isCornerPost || isInnerPost || isHorizBeam || isDiagonalBrace ->
                        if (inEaveShadow) timber.shadow else timber.mid
                    isPlinth -> if ((x + y) % 4 == 0) plaster.shadow else plaster.sample(0.42f)
                    inEaveShadow -> plaster.sample(0.55f)
                    else -> plaster.sample(0.86f)
                }
                img.setPixel(x, y, col)
            }
        }

        // Warm arched wooden door in center bay
        val doorW = (w * 0.14f).toInt().coerceAtLeast(5)
        val doorX0 = (w - doorW) / 2
        val doorY0 = midBeamY + 1
        for (y in doorY0 until wallBot) {
            for (x in doorX0 until doorX0 + doorW) {
                val isFrame = x == doorX0 || x == doorX0 + doorW - 1 || y == doorY0
                val isLatch = x == doorX0 + doorW - 2 && y == (doorY0 + wallBot) / 2
                img.setPixel(
                    x,
                    y,
                    when {
                        isLatch -> PaletteRamp.rgba(248, 214, 96)
                        isFrame -> timber.shadow
                        else -> timber.sample(0.55f)
                    }
                )
            }
        }

        // Warm amber glowing paned windows in left and right bays
        val winW = (tileSize * 0.34f).toInt().coerceAtLeast(4)
        val winH = (tileSize * 0.28f).toInt().coerceAtLeast(4)
        val glowLit = PaletteRamp.rgba(255, 232, 136)
        val glowWarm = PaletteRamp.rgba(242, 172, 68)
        for (winCx in listOf((wx0 + post1X) / 2, (post2X + wx1) / 2)) {
            val lx0 = winCx - winW / 2
            val ly0 = roofBot + (tileSize / 6).coerceAtLeast(3)
            for (y in ly0 until (ly0 + winH).coerceAtMost(wallBot - 2)) {
                for (x in lx0 until (lx0 + winW).coerceAtMost(wx1 - 1)) {
                    val isFrame = x == lx0 || x == lx0 + winW - 1 || y == ly0 || y == ly0 + winH - 1 ||
                        x == lx0 + winW / 2 || y == ly0 + winH / 2
                    img.setPixel(x, y, if (isFrame) timber.shadow else if ((x + y) % 2 == 0) glowLit else glowWarm)
                }
            }
        }
    }

    private fun drawMarketStall(
        img: TileImage,
        w: Int,
        h: Int,
        awning: PaletteRamp,
        wood: PaletteRamp
    ) {
        drawEllipse(img, w * 0.5f, h * 0.90f, w * 0.44f, h * 0.07f, PaletteRamp.rgba(16, 14, 24, 115))
        val x0 = (w * 0.08f).toInt()
        val x1 = (w * 0.92f).toInt()
        val awningTop = (h * 0.12f).toInt()
        val awningBot = (h * 0.46f).toInt()
        val counterTop = (h * 0.58f).toInt()
        val counterBot = (h * 0.86f).toInt()
        val cream = PalettePresets.CREAM_MARBLE

        // Support poles
        val poleW = (tileSize / 10).coerceAtLeast(2)
        for (px in listOf(x0 + 2, x1 - 2 - poleW)) {
            for (y in awningBot..counterBot) {
                for (x in px until px + poleW) {
                    img.setPixel(x, y, if (x == px) wood.highlight else wood.shadow)
                }
            }
        }

        // Wooden merchant counter & colorful display crates/fruits
        for (y in counterTop..counterBot) {
            for (x in (x0 + 2)..(x1 - 2)) {
                val isEdge = y == counterTop || y == counterBot || x == x0 + 2 || x == x1 - 2
                val isPlank = (x - x0) % (tileSize / 4).coerceAtLeast(3) == 0
                img.setPixel(
                    x,
                    y,
                    when {
                        y == counterTop + 1 -> wood.highlight
                        isEdge || isPlank -> wood.shadow
                        else -> wood.mid
                    }
                )
            }
        }
        // Wares on counter (emerald, gold, and ruby goods)
        val wareColors = listOf(
            PaletteRamp.rgba(232, 72, 64),
            PaletteRamp.rgba(248, 204, 68),
            PaletteRamp.rgba(76, 196, 112),
            PaletteRamp.rgba(96, 178, 248)
        )
        for (i in 0 until 4) {
            val bx = x0 + (w * (0.18f + i * 0.18f)).toInt()
            fillEllipseSolid(img, bx.toFloat(), (counterTop - 1).toFloat(), (tileSize * 0.12f).coerceAtLeast(2f), (tileSize * 0.09f).coerceAtLeast(1.5f), wareColors[i])
        }

        // Striped sloped canvas awning with scalloped valance
        val stripeW = (w / 10).coerceAtLeast(3)
        for (y in awningTop..awningBot) {
            val frac = (y - awningTop).toFloat() / (awningBot - awningTop).coerceAtLeast(1)
            val inset = ((1f - frac) * (w * 0.08f)).toInt()
            for (x in (x0 + inset)..(x1 - inset)) {
                val stripeIdx = (x - x0) / stripeW
                val pal = if (stripeIdx % 2 == 0) awning else cream
                val isScallopDrop = y == awningBot && ((x / 2) % 2 == 0)
                if (y == awningBot && !isScallopDrop) continue
                val t = (0.85f - frac * 0.35f).coerceIn(0.2f, 0.95f)
                val isBorder = y == awningTop || y >= awningBot - 1 || x == x0 + inset || x == x1 - inset
                img.setPixel(x, y, if (isBorder) pal.shadow else pal.sample(t))
            }
        }
    }

    private fun drawRuneCrystalFountain(
        img: TileImage,
        w: Int,
        h: Int,
        marble: PaletteRamp,
        water: PaletteRamp
    ) {
        val cx = w * 0.5f
        val cy = h * 0.64f
        drawEllipse(img, cx, cy + h * 0.10f, w * 0.42f, h * 0.22f, PaletteRamp.rgba(16, 18, 32, 115))

        // Outer marble basin & water pool
        val rxOuter = w * 0.40f
        val ryOuter = h * 0.24f
        val rxInner = w * 0.31f
        val ryInner = h * 0.17f
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dOuter = hypot((x + 0.5f - cx) / rxOuter, (y + 0.5f - cy) / ryOuter)
                val dInner = hypot((x + 0.5f - cx) / rxInner, (y + 0.5f - (cy - h * 0.02f)) / ryInner)
                when {
                    dInner <= 1f -> {
                        val ripple = kotlin.math.sin(dInner * 10f) * 0.18f
                        img.setPixel(x, y, water.sample((0.62f + ripple).coerceIn(0.25f, 0.98f)))
                    }
                    dOuter <= 1f -> {
                        val isRimBorder = dOuter > 0.90f || dInner in 1.0f..1.12f
                        val light = 0.72f - (x - cx) / w * 0.35f - (y - cy) / h * 0.35f
                        img.setPixel(x, y, if (isRimBorder) marble.shadow else marble.sample(light.coerceIn(0.25f, 0.95f)))
                    }
                }
            }
        }

        // Center stone pedestal & levitating Rune Crystal spire
        val pedW = (w * 0.16f).toInt().coerceAtLeast(4)
        val pedX0 = (w - pedW) / 2
        val pedY0 = (h * 0.34f).toInt()
        val pedY1 = (cy).toInt()
        for (y in pedY0..pedY1) {
            for (x in pedX0 until pedX0 + pedW) {
                val isEdge = x == pedX0 || x == pedX0 + pedW - 1
                img.setPixel(x, y, if (isEdge) marble.shadow else marble.highlight)
            }
        }

        // Glowing cyan-white diamond crystal + cascading water arcs
        val cryTop = (h * 0.10f).toInt()
        val cryBot = (h * 0.36f).toInt()
        val cryMid = (cryTop + cryBot) / 2
        for (y in cryTop..cryBot) {
            val distMid = abs(y - cryMid).toFloat() / ((cryBot - cryTop) / 2f).coerceAtLeast(1f)
            val halfW = ((1f - distMid) * (w * 0.11f)).toInt().coerceAtLeast(1)
            for (x in (w / 2 - halfW)..(w / 2 + halfW)) {
                val isCore = abs(x - w / 2) <= 1 && distMid < 0.5f
                img.setPixel(x, y, if (isCore) PaletteRamp.rgba(255, 255, 255) else water.highlight)
            }
        }
    }

    private fun drawCherryBlossomTree(
        img: TileImage,
        w: Int,
        h: Int,
        blossom: PaletteRamp,
        wood: PaletteRamp,
        s: Long
    ) {
        drawEllipse(img, w * 0.5f, h * 0.88f, w * 0.36f, h * 0.08f, PaletteRamp.rgba(22, 16, 28, 120))

        // Scattered fallen pink petals around trunk base
        for (py in (h * 0.80f).toInt()..(h * 0.93f).toInt()) {
            for (px in (w * 0.20f).toInt()..(w * 0.80f).toInt()) {
                if (TileNoise.hash(px, py, s + 31L) > 0.88f) {
                    img.setPixel(px, py, blossom.highlight)
                }
            }
        }

        // Gnarled curved dark-bark trunk & spreading boughs
        val trunkW = (w * 0.16f).toInt().coerceAtLeast(3)
        for (y in (h * 0.46f).toInt()..(h * 0.88f).toInt()) {
            val bend = (kotlin.math.sin((y - h * 0.46f) * 0.18f) * 2f).toInt()
            val tx0 = (w - trunkW) / 2 + bend
            for (x in tx0 until tx0 + trunkW) {
                if (x !in 0 until w) continue
                val nx = (x - tx0).toFloat() / trunkW
                val col = when {
                    x == tx0 || x == tx0 + trunkW - 1 -> wood.shadow
                    nx < 0.45f -> wood.sample(0.68f)
                    else -> wood.sample(0.32f)
                }
                img.setPixel(x, y, col)
            }
        }

        // Layered sakura blossom cloud clusters
        val clusters = listOf(
            Triple(0.50f, 0.34f, 0.34f),
            Triple(0.28f, 0.42f, 0.24f),
            Triple(0.72f, 0.42f, 0.24f),
            Triple(0.38f, 0.22f, 0.22f),
            Triple(0.62f, 0.22f, 0.22f)
        )
        for (y in 0 until h) {
            for (x in 0 until w) {
                var best = -1f
                var crownLight = 0f
                for ((cxN, cyN, rN) in clusters) {
                    val dx = (x + 0.5f - cxN * w) / (rN * w)
                    val dy = (y + 0.5f - cyN * h) / (rN * h)
                    val scallop = (TileNoise.hash(x / 2, y / 2, s + 73L) - 0.5f) * 0.22f
                    val inside = 1f - hypot(dx, dy) + scallop
                    if (inside > best) {
                        best = inside
                        crownLight = (0.68f - dx * 0.24f - dy * 0.32f).coerceIn(0.12f, 0.98f)
                    }
                }
                if (best > 0f) {
                    val col = if (best < 0.08f) {
                        blossom.shadow
                    } else {
                        blossom.sample(crownLight)
                    }
                    img.setPixel(x, y, col)
                }
            }
        }
    }

    private fun drawWaypointPillar(
        img: TileImage,
        w: Int,
        h: Int,
        marble: PaletteRamp,
        gold: PaletteRamp
    ) {
        drawEllipse(img, w * 0.5f, h * 0.92f, w * 0.36f, h * 0.06f, PaletteRamp.rgba(16, 18, 32, 120))
        val cx = w / 2
        val x0 = (w * 0.26f).toInt()
        val x1 = (w * 0.74f).toInt()
        val y0 = (h * 0.16f).toInt()
        val y1 = (h * 0.90f).toInt()

        // Winged top crest (winged crest)
        val wingY = (h * 0.24f).toInt()
        for (dx in 1..(w * 0.42f).toInt()) {
            val wyTop = (wingY - dx / 2).coerceAtLeast(2)
            val wyBot = (wingY + dx / 3).coerceAtMost(h - 2)
            for (y in wyTop..wyBot) {
                val col = if (y == wyTop) marble.highlight else gold.mid
                if (cx - dx >= 1) img.setPixel(cx - dx, y, col)
                if (cx + dx < w - 1) img.setPixel(cx + dx, y, col)
            }
        }

        // Fluted marble obelisk shaft with gold capital and base
        for (y in y0..y1) {
            val isCapOrBase = y < y0 + (h * 0.08f).toInt() || y > y1 - (h * 0.10f).toInt()
            val rx0 = if (isCapOrBase) (x0 - 2).coerceAtLeast(1) else x0
            val rx1 = if (isCapOrBase) (x1 + 2).coerceAtMost(w - 2) else x1
            for (x in rx0..rx1) {
                val isBorder = x == rx0 || x == rx1 || y == y0 || y == y1
                val isRuneCore = abs(x - cx) <= 1 && y in (h * 0.38f).toInt()..(h * 0.62f).toInt()
                val col = when {
                    isRuneCore -> PaletteRamp.rgba(120, 232, 255)
                    isBorder -> marble.shadow
                    isCapOrBase -> gold.sample(0.72f)
                    else -> marble.sample(0.84f - (x - rx0).toFloat() / (rx1 - rx0).coerceAtLeast(1) * 0.42f)
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawGuildEmblemFlag(
        img: TileImage,
        w: Int,
        h: Int,
        banner: PaletteRamp,
        gold: PaletteRamp
    ) {
        drawEllipse(img, w * 0.5f, h * 0.92f, w * 0.30f, h * 0.05f, PaletteRamp.rgba(16, 16, 26, 115))
        val cx = w / 2
        val poleTop = (h * 0.06f).toInt()
        val poleBot = (h * 0.92f).toInt()

        // Vertical pole & finial spearhead
        for (y in poleTop..poleBot) {
            img.setPixel(cx, y, gold.highlight)
            if (cx + 1 < w) img.setPixel(cx + 1, y, gold.shadow)
        }
        val crossbarY = (h * 0.14f).toInt()
        val bx0 = (w * 0.16f).toInt()
        val bx1 = (w * 0.84f).toInt()
        for (x in bx0..bx1) {
            img.setPixel(x, crossbarY, gold.highlight)
            if (crossbarY + 1 < h) img.setPixel(x, crossbarY + 1, gold.mid)
        }

        // Embroidered swallowtail banner cloth
        val clothTop = crossbarY + 2
        val clothBot = (h * 0.78f).toInt()
        val crestCy = (clothTop + clothBot) / 2
        for (y in clothTop..clothBot) {
            val notch = if (y > (h * 0.64f).toInt()) ((y - h * 0.64f) / (h * 0.14f) * (w * 0.18f)).toInt() else 0
            for (x in (bx0 + 2)..(bx1 - 2)) {
                if (abs(x - cx) < notch) continue
                val isGoldTrim = x == bx0 + 2 || x == bx1 - 2 || y == clothBot
                val crestDist = abs(x - cx) + abs(y - crestCy)
                val isEmblem = crestDist <= (w * 0.16f).toInt().coerceAtLeast(2)
                val col = when {
                    isGoldTrim || isEmblem -> gold.highlight
                    else -> banner.sample(0.75f - (y - clothTop).toFloat() / (clothBot - clothTop).coerceAtLeast(1) * 0.35f)
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawPoliceCruiser(
        img: TileImage,
        w: Int,
        h: Int,
        chassis: PaletteRamp,
        siren: PaletteRamp
    ) {
        val shadow = PaletteRamp.rgba(12, 12, 18, 110)
        val redSiren = PalettePresets.NEON_CRIMSON.highlight
        val darkBody = PaletteRamp.rgba(22, 24, 34)
        val windshield = PalettePresets.SIREN_BLUE.highlight
        val headlight = PalettePresets.HAZARD_AMBER.highlight

        fillEllipseSolid(img, w * 0.5f, h * 0.84f, w * 0.44f, h * 0.13f, shadow)

        val x0 = (w * 0.08f).toInt()
        val x1 = (w * 0.92f).toInt()
        val yBody0 = (h * 0.42f).toInt()
        val yBody1 = (h * 0.78f).toInt()

        // Lower body (black-and-white / gunmetal cruiser)
        for (y in yBody0..yBody1) {
            for (x in x0..x1) {
                val isEdge = x == x0 || x == x1 || y == yBody0 || y == yBody1
                val isDoorPanel = x in (w * 0.30f).toInt()..(w * 0.68f).toInt() && y in (yBody0 + 2)..(yBody1 - 2)
                val col = when {
                    isEdge -> darkBody
                    isDoorPanel -> chassis.highlight
                    else -> darkBody
                }
                img.setPixel(x, y, col)
            }
        }
        // Cabin roof
        val cabX0 = (w * 0.24f).toInt()
        val cabX1 = (w * 0.74f).toInt()
        val cabY0 = (h * 0.24f).toInt()
        for (y in cabY0 until yBody0) {
            for (x in cabX0..cabX1) {
                val isWin = y in (cabY0 + 2) until (yBody0 - 1) && x in (cabX0 + 2)..(cabX1 - 2)
                img.setPixel(x, y, if (isWin) windshield else chassis.mid)
            }
        }
        // Red & blue roof lightbar
        val barY0 = (cabY0 - 2).coerceAtLeast(1)
        val midX = w / 2
        for (y in barY0 until cabY0) {
            for (x in (midX - 5)..(midX + 5)) {
                img.setPixel(x, y, if (x < midX) redSiren else siren.highlight)
            }
        }
        // Wheels & headlights
        val wheelY0 = yBody1 - 2
        val wheelY1 = (yBody1 + 3).coerceAtMost(h - 2)
        for (wy in wheelY0..wheelY1) {
            for (wx in (x0 + 4)..(x0 + 9)) img.setPixel(wx, wy, darkBody)
            for (wx in (x1 - 9)..(x1 - 4)) img.setPixel(wx, wy, darkBody)
        }
        img.setPixel(x0 + 1, yBody0 + 3, headlight)
        img.setPixel(x1 - 1, yBody0 + 3, redSiren)
    }

    private fun drawDataTerminal(
        img: TileImage,
        w: Int,
        h: Int,
        chassis: PaletteRamp,
        crt: PaletteRamp
    ) {
        val shadow = PaletteRamp.rgba(12, 14, 20, 100)
        fillEllipseSolid(img, w * 0.5f, h * 0.90f, w * 0.36f, h * 0.08f, shadow)
        val x0 = (w * 0.18f).toInt()
        val x1 = (w * 0.82f).toInt()
        val y0 = (h * 0.14f).toInt()
        val y1 = (h * 0.88f).toInt()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val isBorder = x == x0 || x == x1 || y == y0 || y == y1
                val isScreen = x in (x0 + 2)..(x1 - 2) && y in (y0 + 2)..(h * 0.46f).toInt()
                val isScanline = isScreen && y % 2 == 0
                val col = when {
                    isBorder -> chassis.shadow
                    isScanline -> crt.highlight
                    isScreen -> crt.mid
                    y == (h * 0.54f).toInt() && x in (x0 + 2)..(x1 - 2) -> chassis.highlight
                    else -> chassis.mid
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawToxicVat(
        img: TileImage,
        w: Int,
        h: Int,
        metal: PaletteRamp,
        sludge: PaletteRamp
    ) {
        val shadow = PaletteRamp.rgba(12, 16, 12, 110)
        val cx = w * 0.5f
        val cy = h * 0.80f
        fillEllipseSolid(img, cx, cy, w * 0.42f, h * 0.14f, shadow)

        val x0 = (w * 0.16f).toInt()
        val x1 = (w * 0.84f).toInt()
        val yTop = (h * 0.24f).toInt()
        val yBot = (h * 0.80f).toInt()
        for (y in yTop..yBot) {
            for (x in x0..x1) {
                val isRim = x == x0 || x == x1 || y == yBot || y == (yTop + yBot) / 2
                val t = 1f - (x - x0).toFloat() / (x1 - x0).coerceAtLeast(1) * 0.5f
                img.setPixel(x, y, if (isRim) metal.shadow else metal.sample(0.32f + t * 0.38f))
            }
        }
        // Bubbling green toxic pool at top rim
        fillEllipseSolid(img, cx, yTop.toFloat(), w * 0.34f, h * 0.11f, metal.highlight)
        fillEllipseSolid(img, cx, yTop.toFloat(), w * 0.28f, h * 0.08f, sludge.mid)
        fillEllipseSolid(img, cx - 2f, yTop - 1f, w * 0.10f, h * 0.04f, sludge.highlight)
    }

    private fun drawNeonBillboard(
        img: TileImage,
        w: Int,
        h: Int,
        neon: PaletteRamp,
        cyan: PaletteRamp
    ) {
        val darkFrame = PaletteRamp.rgba(22, 20, 32)
        val postX0 = (w * 0.24f).toInt()
        val postX1 = (w * 0.76f).toInt()
        for (y in (h * 0.58f).toInt()..(h * 0.90f).toInt()) {
            img.setPixel(postX0, y, darkFrame)
            img.setPixel(postX0 + 1, y, darkFrame)
            img.setPixel(postX1, y, darkFrame)
            img.setPixel(postX1 - 1, y, darkFrame)
        }
        val x0 = (w * 0.10f).toInt()
        val x1 = (w * 0.90f).toInt()
        val y0 = (h * 0.12f).toInt()
        val y1 = (h * 0.60f).toInt()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val isFrame = x == x0 || x == x1 || y == y0 || y == y1
                val isTube = x == x0 + 2 || x == x1 - 2 || y == y0 + 2 || y == y1 - 2
                val isTextBar = y in (y0 + 5)..(y1 - 5) && x in (x0 + 5)..(x1 - 5) && (x + y) % 3 != 0
                val col = when {
                    isFrame -> darkFrame
                    isTube -> neon.highlight
                    isTextBar -> cyan.highlight
                    else -> neon.shadow
                }
                img.setPixel(x, y, col)
            }
        }
    }

    private fun drawStreetBarricade(
        img: TileImage,
        w: Int,
        h: Int,
        amber: PaletteRamp,
        dark: PaletteRamp
    ) {
        val y0 = (h * 0.28f).toInt()
        val y1 = (h * 0.60f).toInt()
        val x0 = (w * 0.08f).toInt()
        val x1 = (w * 0.92f).toInt()
        for (y in y0..y1) {
            for (x in x0..x1) {
                val stripe = ((x + y) / 3) % 2 == 0
                val isEdge = y == y0 || y == y1 || x == x0 || x == x1
                img.setPixel(x, y, when {
                    isEdge -> dark.shadow
                    stripe -> amber.highlight
                    else -> dark.shadow
                })
            }
        }
        // Legs
        for (y in (y1 + 1)..(h * 0.88f).toInt()) {
            img.setPixel(x0 + 2, y, dark.mid)
            img.setPixel(x0 + 3, y, dark.shadow)
            img.setPixel(x1 - 2, y, dark.mid)
            img.setPixel(x1 - 3, y, dark.shadow)
        }
    }

    private fun drawTrafficLight(
        img: TileImage,
        w: Int,
        h: Int,
        metal: PaletteRamp,
        amber: PaletteRamp
    ) {
        val cx = w / 2
        for (y in (h * 0.36f).toInt()..(h * 0.90f).toInt()) {
            img.setPixel(cx - 1, y, metal.mid)
            img.setPixel(cx, y, metal.shadow)
        }
        val boxX0 = (cx - 2).coerceAtLeast(1)
        val boxX1 = (cx + 2).coerceAtMost(w - 2)
        val boxY0 = (h * 0.08f).toInt()
        val boxY1 = (h * 0.38f).toInt()
        for (y in boxY0..boxY1) {
            for (x in boxX0..boxX1) {
                img.setPixel(x, y, metal.shadow)
            }
        }
        img.setPixel(cx, boxY0 + 2, PalettePresets.NEON_CRIMSON.highlight)
        img.setPixel(cx, (boxY0 + boxY1) / 2, amber.highlight)
        img.setPixel(cx, boxY1 - 2, PalettePresets.CRT_GREEN.highlight)
    }

    private fun fillEllipseSolid(img: TileImage, cx: Float, cy: Float, rx: Float, ry: Float, rgba: Int) {
        val x0 = (cx - rx).toInt().coerceAtLeast(0)
        val x1 = (cx + rx).toInt().coerceAtMost(img.width - 1)
        val y0 = (cy - ry).toInt().coerceAtLeast(0)
        val y1 = (cy + ry).toInt().coerceAtMost(img.height - 1)
        for (y in y0..y1) {
            for (x in x0..x1) {
                if (hypot((x + 0.5f - cx) / rx, (y + 0.5f - cy) / ry) <= 1f) {
                    img.setPixel(x, y, rgba)
                }
            }
        }
    }
}
