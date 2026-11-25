package com.morgana.tilegen.spec

import com.morgana.tilegen.NamedTile
import com.morgana.tilegen.TileImage
import com.morgana.tilegen.autotile.AutotileGenerator
import com.morgana.tilegen.autotile.AutotileSheet
import com.morgana.tilegen.autotile.AutotileStyle
import com.morgana.tilegen.material.MaterialSpec
import com.morgana.tilegen.material.MaterialTileGenerator
import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.TileDecals
import com.morgana.tilegen.structure.BuildingTheme
import com.morgana.tilegen.structure.GeneratedProp
import com.morgana.tilegen.structure.PropGenerator
import com.morgana.tilegen.structure.StructureGenerator

/**
 * Output of rendering a [TilesetSpec]: named material/cliff/prop tiles, optional bundled sheet,
 * named autotile transition sheets, and full multi-tile [GeneratedProp] composites.
 */
data class RenderedTileset(
    val spec: TilesetSpec,
    val tiles: List<NamedTile>,
    val sheet: TileImage?,
    val autotiles: Map<String, AutotileSheet>,
    val props: Map<String, GeneratedProp> = emptyMap()
)

/**
 * Deterministic renderer that turns a [TilesetSpec] (or JSON string) into pixel-art tiles
 * and autotile sheets without I/O or external dependencies.
 */
object TilesetSpecRenderer {

    /**
     * Parses [json] into a [TilesetSpec] and renders it deterministically.
     */
    fun renderJson(json: String): RenderedTileset = render(TilesetSpecJson.parse(json))

    /**
     * Renders [spec] into a [RenderedTileset].
     */
    fun render(spec: TilesetSpec): RenderedTileset {
        val resolvedPalettes = resolvePalettes(spec)
        val materialGen = MaterialTileGenerator(seed = spec.seed, tileSize = spec.tileSize)
        val autotileGen = AutotileGenerator(seed = spec.seed, tileSize = spec.tileSize)
        val structureGen = StructureGenerator(tileSize = spec.tileSize)
        val propGen = PropGenerator(seed = spec.seed, tileSize = spec.tileSize)

        val tiles = mutableListOf<NamedTile>()
        val baseTilesByName = mutableMapOf<String, TileImage>()

        for (mat in spec.materials) {
            val palette = mat.inlinePalette?.toPaletteRamp()
                ?: resolvedPalettes[mat.palette!!.lowercase()]
                ?: throw SpecException("Unknown palette '${mat.palette}' for material '${mat.name}'")

            val effStyle = mat.style ?: spec.style
            val mSpec = MaterialSpec(
                name = mat.name,
                pattern = mat.pattern,
                palette = palette,
                accentColor = mat.accentColor?.let { SpecPalettes.parseHexRgba(it) },
                scale = mat.scale,
                detail = mat.detail,
                style = effStyle
            )

            fun maybeDecal(img: TileImage, variantSeed: Long): TileImage =
                if (mat.decal != null) TileDecals.applyDecal(img, mat.decal, variantSeed, effStyle, palette) else img

            if (mat.frames > 1) {
                val frames = materialGen.generateAnimatedFrames(mSpec, mat.frames)
                frames.forEachIndexed { idx, frameImg ->
                    val withDecal = maybeDecal(frameImg, spec.seed + idx * 31L)
                    tiles += NamedTile("${mat.name}_f$idx", withDecal)
                    if (idx == 0) {
                        baseTilesByName[mat.name] = withDecal
                    }
                }
            } else if (mat.variants > 1) {
                for (v in 0 until mat.variants) {
                    val vSeed = spec.seed + v * 7919L
                    val vGen = if (v == 0) materialGen else MaterialTileGenerator(seed = vSeed, tileSize = spec.tileSize)
                    val img = maybeDecal(vGen.generateTile(mSpec), vSeed)
                    val tileName = if (v == 0) mat.name else "${mat.name}_v$v"
                    tiles += NamedTile(tileName, img)
                    if (v == 0) {
                        baseTilesByName[mat.name] = img
                    }
                }
            } else {
                val img = maybeDecal(materialGen.generateTile(mSpec), spec.seed)
                tiles += NamedTile(mat.name, img)
                baseTilesByName[mat.name] = img
            }
        }

        for (cliff in spec.cliffs) {
            val top = baseTilesByName[cliff.topMaterial]
                ?: throw SpecException("Unknown topMaterial '${cliff.topMaterial}'")
            val face = baseTilesByName[cliff.faceMaterial]
                ?: throw SpecException("Unknown faceMaterial '${cliff.faceMaterial}'")
            val trimRgba = cliff.trimColor?.let { SpecPalettes.parseHexRgba(it) }
            val cliffStyle = cliff.style ?: spec.style
            tiles += when {
                cliff.townFacade -> structureGen.generateTownFacadeSet(
                    name = cliff.name,
                    roofMaterial = top,
                    wallMaterial = face,
                    style = cliffStyle,
                    windowGlow = cliff.castShadow,
                    timberColor = trimRgba ?: PaletteRamp.rgba(104, 58, 38)
                )
                cliff.a4Layout -> structureGen.generateA4WallSet(
                    name = cliff.name,
                    topMaterial = top,
                    faceMaterial = face,
                    style = cliffStyle,
                    castShadow = cliff.castShadow,
                    trimColor = trimRgba
                )
                else -> structureGen.generateCliffSet(
                    name = cliff.name,
                    topMaterial = top,
                    faceMaterial = face,
                    style = cliffStyle,
                    castShadow = cliff.castShadow,
                    trimColor = trimRgba,
                    lush = cliff.lush
                )
            }
        }

        val unitTiles = mutableListOf<NamedTile>()
        unitTiles += tiles
        val multiProps = mutableListOf<GeneratedProp>()

        val renderedProps = linkedMapOf<String, GeneratedProp>()
        for (prop in spec.props) {
            val primary = prop.primaryPalette?.let {
                resolvedPalettes[it.lowercase()] ?: throw SpecException("Unknown primaryPalette '$it'")
            }
            val secondary = prop.secondaryPalette?.let {
                resolvedPalettes[it.lowercase()] ?: throw SpecException("Unknown secondaryPalette '$it'")
            }
            val generated = propGen.generateProp(
                name = prop.name,
                kind = prop.kind,
                style = prop.style ?: spec.style,
                primaryPalette = primary,
                secondaryPalette = secondary,
                buildingRoof = prop.roof,
                buildingTheme = prop.buildingTheme ?: BuildingTheme.DEFAULT
            )
            renderedProps[prop.name] = generated
            tiles += generated.slicedTiles
            if (generated.widthTiles == 1 && generated.heightTiles == 1) {
                unitTiles += generated.slicedTiles
            } else {
                multiProps += generated
            }
        }

        val sheet = if (tiles.isNotEmpty()) {
            bundleWith2DProps(unitTiles, multiProps, spec.tileSize, spec.columns)
        } else {
            null
        }

        val autotiles = linkedMapOf<String, AutotileSheet>()
        for (auto in spec.autotiles) {
            val cols = auto.columns ?: AutotileGenerator.defaultColumns(auto.mode)
            val renderedAuto = if (auto.terrainPair != null) {
                autotileGen.generateTerrainAutotile(auto.terrainPair, auto.mode, cols)
            } else {
                val fg = baseTilesByName[auto.foregroundMaterial]
                    ?: throw SpecException("Unknown foregroundMaterial '${auto.foregroundMaterial}'")
                val bg = baseTilesByName[auto.backgroundMaterial]
                    ?: throw SpecException("Unknown backgroundMaterial '${auto.backgroundMaterial}'")
                val style = auto.style?.toAutotileStyle(spec.style) ?: AutotileStyle(pixelStyle = spec.style)
                autotileGen.generateAutotile(fg, bg, auto.mode, style, cols)
            }
            autotiles[auto.name] = renderedAuto
        }

        return RenderedTileset(
            spec = spec,
            tiles = tiles,
            sheet = sheet,
            autotiles = autotiles,
            props = renderedProps
        )
    }

    private fun resolvePalettes(spec: TilesetSpec): Map<String, PaletteRamp> {
        val merged = linkedMapOf<String, PaletteRamp>()
        merged.putAll(SpecPalettes.presets)
        spec.palettes.forEach { (name, paletteSpec) ->
            merged[name.lowercase()] = paletteSpec.toPaletteRamp()
        }
        return merged
    }

    private fun bundleWith2DProps(
        unitTiles: List<NamedTile>,
        multiProps: List<GeneratedProp>,
        tileSize: Int,
        columns: Int
    ): TileImage {
        val unitRows = if (unitTiles.isEmpty()) 0 else (unitTiles.size + columns - 1) / columns
        var curCol = 0
        var curRow = unitRows
        var bandHeight = 0
        val placements = mutableListOf<Triple<GeneratedProp, Int, Int>>()
        for (prop in multiProps) {
            if (prop.widthTiles <= columns && curCol + prop.widthTiles > columns) {
                curRow += bandHeight
                curCol = 0
                bandHeight = 0
            }
            placements += Triple(prop, curCol, curRow)
            curCol += prop.widthTiles
            bandHeight = maxOf(bandHeight, prop.heightTiles)
        }
        val totalRows = (curRow + bandHeight).coerceAtLeast(1)
        val sheet = TileImage(columns * tileSize, totalRows * tileSize)

        unitTiles.forEachIndexed { idx, named ->
            val col = idx % columns
            val row = idx / columns
            sheet.drawImage(named.image, 0, 0, tileSize, tileSize, col * tileSize, row * tileSize)
        }
        for ((prop, col, row) in placements) {
            sheet.drawImage(
                prop.fullImage,
                0,
                0,
                prop.fullImage.width,
                prop.fullImage.height,
                col * tileSize,
                row * tileSize
            )
        }
        return sheet
    }
}
