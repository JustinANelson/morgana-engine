package com.morgana.tilegen.spec

import com.morgana.tilegen.autotile.AutotileMode
import com.morgana.tilegen.autotile.AutotileStyle
import com.morgana.tilegen.autotile.TerrainPair
import com.morgana.tilegen.material.DecalKind
import com.morgana.tilegen.material.MaterialPattern
import com.morgana.tilegen.material.PalettePresets
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.structure.PropKind

/**
 * Registry of built-in named [PaletteRamp] presets addressable from LLM tileset specs.
 */
object SpecPalettes {
    val presets: Map<String, PaletteRamp> = mapOf(
        "meadow_grass" to PalettePresets.MEADOW_GRASS,
        "autumn_grass" to PalettePresets.AUTUMN_GRASS,
        "earth_dirt" to PalettePresets.EARTH_DIRT,
        "slate_stone" to PalettePresets.SLATE_STONE,
        "ocean_water" to PalettePresets.OCEAN_WATER,
        "calm_water" to PalettePresets.CALM_WATER,
        "desert_sand" to PalettePresets.DESERT_SAND,
        "timber_wood" to PalettePresets.TIMBER_WOOD,
        "frost_snow" to PalettePresets.FROST_SNOW,
        "lava_magma" to PalettePresets.LAVA_MAGMA,
        "void_metal" to PalettePresets.VOID_METAL,
        "terracotta_brick" to PalettePresets.TERRACOTTA_BRICK,
        "gameboy_dmg" to PalettePresets.GAMEBOY_DMG,
        "nes_overworld" to PalettePresets.NES_OVERWORLD,
        "nes_dungeon" to PalettePresets.NES_DUNGEON,
        "pico8_forest" to PalettePresets.PICO8_FOREST,
        "pico8_water" to PalettePresets.PICO8_WATER,
        "classic_rpg_grass" to PalettePresets.CLASSIC_RPG_GRASS,
        "classic_rpg_earth" to PalettePresets.CLASSIC_RPG_EARTH,
        "classic_rpg_stone" to PalettePresets.CLASSIC_RPG_STONE,
        "ruins_marble" to PalettePresets.RUINS_MARBLE,
        "ruins_slate" to PalettePresets.RUINS_SLATE,
        "ruins_gold" to PalettePresets.RUINS_GOLD,
        "ruins_terra" to PalettePresets.RUINS_TERRA,
        "ruins_blue" to PalettePresets.RUINS_BLUE,
        "jade_green" to PalettePresets.JADE_GREEN,
        "lacquer_red" to PalettePresets.LACQUER_RED,
        "celestial_gold" to PalettePresets.CELESTIAL_GOLD,
        "mystic_silk" to PalettePresets.MYSTIC_SILK,
        "dragon_bronze" to PalettePresets.DRAGON_BRONZE,
        "abyssal_obsidian" to PalettePresets.ABYSSAL_OBSIDIAN,
        "rift_crimson" to PalettePresets.RIFT_CRIMSON,
        "dark_iron" to PalettePresets.DARK_IRON,
        "ancient_leaf" to PalettePresets.ANCIENT_LEAF,
        "relic_amber" to PalettePresets.RELIC_AMBER,
        "highland_grass" to PalettePresets.HIGHLAND_GRASS,
        "warm_timber" to PalettePresets.WARM_TIMBER,
        "pale_limestone" to PalettePresets.PALE_LIMESTONE,
        "scarlet_roof" to PalettePresets.SCARLET_ROOF,
        "rune_gold" to PalettePresets.RUNE_GOLD,
        "cream_marble" to PalettePresets.CREAM_MARBLE,
        "forest_jade" to PalettePresets.FOREST_JADE,
        "arcane_violet" to PalettePresets.ARCANE_VIOLET,
        "dune_sandstone" to PalettePresets.DUNE_SANDSTONE,
        "pastel_pink" to PalettePresets.PASTEL_PINK,
        "gunmetal_steel" to PalettePresets.GUNMETAL_STEEL,
        "urban_asphalt" to PalettePresets.URBAN_ASPHALT,
        "crt_green" to PalettePresets.CRT_GREEN,
        "siren_blue" to PalettePresets.SIREN_BLUE,
        "hazard_amber" to PalettePresets.HAZARD_AMBER,
        "neon_crimson" to PalettePresets.NEON_CRIMSON,
        "neon_violet" to PalettePresets.NEON_VIOLET,
        "toxic_sludge" to PalettePresets.TOXIC_SLUDGE,
        "dungeon_flagstone" to PalettePresets.DUNGEON_FLAGSTONE,
        "dungeon_cobble_wall" to PalettePresets.DUNGEON_COBBLE_WALL,
        "dungeon_teal_water" to PalettePresets.DUNGEON_TEAL_WATER,
        "dungeon_sculpted_slate" to PalettePresets.DUNGEON_SCULPTED_SLATE,
        "dungeon_treasure_gold" to PalettePresets.DUNGEON_TREASURE_GOLD,
        "dungeon_bone" to PalettePresets.DUNGEON_BONE,
        "dungeon_spectral_flame" to PalettePresets.DUNGEON_SPECTRAL_FLAME,
        "dungeon_chest_wood" to PalettePresets.DUNGEON_CHEST_WOOD
    )

    /**
     * Parses `#RRGGBB` or `#RRGGBBAA` hex strings into packed RGBA8888 integers.
     */
    fun parseHexRgba(hex: String): Int {
        val trimmed = hex.trim()
        val raw = if (trimmed.startsWith("#")) trimmed.substring(1) else trimmed
        return when (raw.length) {
            6 -> {
                val rgb = raw.toLongOrNull(16)
                    ?: throw SpecException("Invalid hex color '$hex'")
                ((rgb.toInt() shl 8) or 0xFF)
            }
            8 -> {
                val rgba = raw.toLongOrNull(16)
                    ?: throw SpecException("Invalid hex color '$hex'")
                rgba.toInt()
            }
            else -> throw SpecException("Hex color '$hex' must be #RRGGBB or #RRGGBBAA")
        }
    }

    /**
     * Formats a packed RGBA8888 integer as `#RRGGBBAA` (or `#RRGGBB` when opaque).
     */
    fun formatHexRgba(rgba: Int): String {
        val r = (rgba ushr 24) and 0xFF
        val g = (rgba ushr 16) and 0xFF
        val b = (rgba ushr 8) and 0xFF
        val a = rgba and 0xFF
        return if (a == 255) {
            "#%02X%02X%02X".format(r, g, b)
        } else {
            "#%02X%02X%02X%02X".format(r, g, b, a)
        }
    }
}

/**
 * Palette definition in a [TilesetSpec]: either an explicit list of hex color stops
 * or a generated hue-shifted ramp from a single [baseColor].
 */
data class PaletteSpec(
    val colors: List<String>? = null,
    val baseColor: String? = null,
    val steps: Int = 5,
    val hueShift: Float = 0.06f,
    val valueSpread: Float = 0.35f
) {
    init {
        val hasColors = !colors.isNullOrEmpty()
        val hasBase = !baseColor.isNullOrBlank()
        if (hasColors == hasBase) {
            throw SpecException("PaletteSpec must specify either 'colors' or 'baseColor' (not both or neither)")
        }
        if (hasColors && colors.size < 2) {
            throw SpecException("PaletteSpec 'colors' requires at least 2 color stops")
        }
        if (steps < 2) {
            throw SpecException("PaletteSpec 'steps' must be >= 2, got $steps")
        }
    }

    /**
     * Resolves this spec into a concrete [PaletteRamp].
     */
    fun toPaletteRamp(): PaletteRamp {
        return if (!colors.isNullOrEmpty()) {
            PaletteRamp(colors.map { SpecPalettes.parseHexRgba(it) }.toIntArray())
        } else {
            PaletteRamp.fromBaseColor(
                baseRgba = SpecPalettes.parseHexRgba(baseColor!!),
                steps = steps,
                hueShift = hueShift,
                valueSpread = valueSpread
            )
        }
    }
}

/**
 * Specification for a single procedural material tile (or animated frame sequence) in a [TilesetSpec].
 */
data class MaterialTileSpec(
    val name: String,
    val pattern: MaterialPattern,
    val palette: String? = null,
    val inlinePalette: PaletteSpec? = null,
    val accentColor: String? = null,
    val scale: Float = 1f,
    val detail: Float = 0.5f,
    val frames: Int = 1,
    val variants: Int = 1,
    val decal: DecalKind? = null,
    val style: PixelStyle? = null
) {
    init {
        if (name.isBlank()) throw SpecException("MaterialTileSpec 'name' must not be blank")
        if ((palette == null) == (inlinePalette == null)) {
            throw SpecException("Material '$name' must specify either 'palette' name or 'inlinePalette'")
        }
        if (scale <= 0f) throw SpecException("Material '$name' scale must be positive, got $scale")
        if (detail !in 0f..1f) throw SpecException("Material '$name' detail must be in 0..1, got $detail")
        if (frames < 1) throw SpecException("Material '$name' frames must be >= 1, got $frames")
        if (variants < 1) throw SpecException("Material '$name' variants must be >= 1, got $variants")
    }
}

/**
 * Optional transition style overrides for an [AutotileLayerSpec].
 */
data class AutotileStyleSpec(
    val margin: Float = 5f,
    val rimWidth: Float = 2f,
    val wobbleAmplitude: Float = 3.5f,
    val rimColor: String? = null,
    val pixelStyle: PixelStyle? = null
) {
    init {
        if (margin < 0f) throw SpecException("AutotileStyleSpec margin must be non-negative, got $margin")
        if (rimWidth < 0f) throw SpecException("AutotileStyleSpec rimWidth must be non-negative, got $rimWidth")
        if (wobbleAmplitude < 0f) throw SpecException("AutotileStyleSpec wobbleAmplitude must be non-negative, got $wobbleAmplitude")
    }

    fun toAutotileStyle(inheritedStyle: PixelStyle = PixelStyle.STANDARD): AutotileStyle = AutotileStyle(
        margin = margin,
        rimWidth = rimWidth,
        wobbleAmplitude = wobbleAmplitude,
        rimColor = rimColor?.let { SpecPalettes.parseHexRgba(it) },
        pixelStyle = pixelStyle ?: inheritedStyle
    )
}

/**
 * Specification for generating an autotile transition sheet in a [TilesetSpec].
 * Supports either a built-in [terrainPair] or a custom [foregroundMaterial] over [backgroundMaterial].
 */
data class AutotileLayerSpec(
    val name: String,
    val mode: AutotileMode = AutotileMode.BLOB_47,
    val columns: Int? = null,
    val terrainPair: TerrainPair? = null,
    val foregroundMaterial: String? = null,
    val backgroundMaterial: String? = null,
    val style: AutotileStyleSpec? = null
) {
    init {
        if (name.isBlank()) throw SpecException("AutotileLayerSpec 'name' must not be blank")
        if (columns != null && columns <= 0) {
            throw SpecException("Autotile '$name' columns must be positive, got $columns")
        }
        val hasPair = terrainPair != null
        val hasCustom = foregroundMaterial != null && backgroundMaterial != null
        if (!hasPair && !hasCustom) {
            throw SpecException(
                "Autotile '$name' must specify either 'terrainPair' or both 'foregroundMaterial' and 'backgroundMaterial'"
            )
        }
        if (hasPair && (foregroundMaterial != null || backgroundMaterial != null)) {
            throw SpecException(
                "Autotile '$name' cannot specify both 'terrainPair' and custom foreground/background materials"
            )
        }
    }
}

/**
 * Specification for a modular 3/4-perspective cliff or wall set in a [TilesetSpec].
 */
data class CliffLayerSpec(
    val name: String,
    val topMaterial: String,
    val faceMaterial: String,
    val castShadow: Boolean = true,
    val trimColor: String? = null,
    val style: PixelStyle? = null,
    val a4Layout: Boolean = false,
    val townFacade: Boolean = false
) {
    init {
        if (name.isBlank()) throw SpecException("CliffLayerSpec 'name' must not be blank")
        if (topMaterial.isBlank()) throw SpecException("Cliff '$name' topMaterial must not be blank")
        if (faceMaterial.isBlank()) throw SpecException("Cliff '$name' faceMaterial must not be blank")
    }
}

/**
 * Specification for a multi-tile or single-tile 3/4-perspective prop in a [TilesetSpec].
 */
data class PropLayerSpec(
    val name: String,
    val kind: PropKind,
    val primaryPalette: String? = null,
    val secondaryPalette: String? = null,
    val style: PixelStyle? = null
) {
    init {
        if (name.isBlank()) throw SpecException("PropLayerSpec 'name' must not be blank")
    }
}

/**
 * Top-level structured tileset specification that an LLM can emit as JSON and
 * [TilesetSpecRenderer] can deterministically render into tiles and autotile sheets.
 */
data class TilesetSpec(
    val name: String,
    val seed: Long = 1337L,
    val tileSize: Int = 32,
    val columns: Int = 8,
    val style: PixelStyle = PixelStyle.STANDARD,
    val palettes: Map<String, PaletteSpec> = emptyMap(),
    val materials: List<MaterialTileSpec> = emptyList(),
    val autotiles: List<AutotileLayerSpec> = emptyList(),
    val cliffs: List<CliffLayerSpec> = emptyList(),
    val props: List<PropLayerSpec> = emptyList()
) {
    init {
        validate()
    }

    private fun isKnownPalette(paletteName: String): Boolean {
        val key = paletteName.lowercase()
        return key in SpecPalettes.presets ||
            paletteName in palettes ||
            key in palettes.keys.map { it.lowercase() }
    }

    private fun validate() {
        if (name.isBlank()) throw SpecException("TilesetSpec 'name' must not be blank")
        if (tileSize < 8 || tileSize % 2 != 0) {
            throw SpecException("TilesetSpec 'tileSize' must be an even integer >= 8, got $tileSize")
        }
        if (columns <= 0) {
            throw SpecException("TilesetSpec 'columns' must be positive, got $columns")
        }
        if (materials.isEmpty() && autotiles.isEmpty() && cliffs.isEmpty() && props.isEmpty()) {
            throw SpecException("TilesetSpec '$name' must define at least one material, autotile, cliff, or prop")
        }

        val materialNames = mutableSetOf<String>()
        for (mat in materials) {
            if (!materialNames.add(mat.name)) {
                throw SpecException("Duplicate material name '${mat.name}' in TilesetSpec '$name'")
            }
            if (mat.palette != null && !isKnownPalette(mat.palette)) {
                throw SpecException(
                    "Material '${mat.name}' references unknown palette '${mat.palette}'"
                )
            }
            mat.accentColor?.let { SpecPalettes.parseHexRgba(it) }
        }

        val autotileNames = mutableSetOf<String>()
        for (auto in autotiles) {
            if (!autotileNames.add(auto.name)) {
                throw SpecException("Duplicate autotile name '${auto.name}' in TilesetSpec '$name'")
            }
            if (auto.foregroundMaterial != null && auto.foregroundMaterial !in materialNames) {
                throw SpecException(
                    "Autotile '${auto.name}' references unknown foregroundMaterial '${auto.foregroundMaterial}'"
                )
            }
            if (auto.backgroundMaterial != null && auto.backgroundMaterial !in materialNames) {
                throw SpecException(
                    "Autotile '${auto.name}' references unknown backgroundMaterial '${auto.backgroundMaterial}'"
                )
            }
            auto.style?.rimColor?.let { SpecPalettes.parseHexRgba(it) }
        }

        val cliffNames = mutableSetOf<String>()
        for (cliff in cliffs) {
            if (!cliffNames.add(cliff.name)) {
                throw SpecException("Duplicate cliff name '${cliff.name}' in TilesetSpec '$name'")
            }
            if (cliff.topMaterial !in materialNames) {
                throw SpecException("Cliff '${cliff.name}' references unknown topMaterial '${cliff.topMaterial}'")
            }
            if (cliff.faceMaterial !in materialNames) {
                throw SpecException("Cliff '${cliff.name}' references unknown faceMaterial '${cliff.faceMaterial}'")
            }
            cliff.trimColor?.let { SpecPalettes.parseHexRgba(it) }
        }

        val propNames = mutableSetOf<String>()
        for (prop in props) {
            if (!propNames.add(prop.name)) {
                throw SpecException("Duplicate prop name '${prop.name}' in TilesetSpec '$name'")
            }
            if (prop.primaryPalette != null && !isKnownPalette(prop.primaryPalette)) {
                throw SpecException("Prop '${prop.name}' references unknown primaryPalette '${prop.primaryPalette}'")
            }
            if (prop.secondaryPalette != null && !isKnownPalette(prop.secondaryPalette)) {
                throw SpecException("Prop '${prop.name}' references unknown secondaryPalette '${prop.secondaryPalette}'")
            }
        }

        palettes.values.forEach { it.toPaletteRamp() }
    }
}
