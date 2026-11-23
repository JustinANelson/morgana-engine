package com.morgana.tilegen.spec

import com.morgana.tilegen.autotile.AutotileEdge
import com.morgana.tilegen.autotile.AutotileMode
import com.morgana.tilegen.autotile.TerrainPair
import com.morgana.tilegen.material.DecalKind
import com.morgana.tilegen.material.MaterialPattern
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.structure.PropKind

/**
 * Pure-Kotlin JSON serializer, parser, and JSON Schema provider for [TilesetSpec].
 */
object TilesetSpecJson {

    /**
     * Parses a JSON string into a validated [TilesetSpec].
     * Throws [SpecException] with descriptive context on malformed JSON or schema violations.
     */
    fun parse(json: String): TilesetSpec {
        val root = JsonParser(json).parse() as? JsonValue.Obj
            ?: throw SpecException("Top-level tileset spec must be a JSON object")

        val name = root.reqString("name")
        val seed = root.optLong("seed") ?: 1337L
        val tileSize = root.optInt("tileSize") ?: 32
        val columns = root.optInt("columns") ?: 8
        val style = root.optString("style")?.let { parseEnum<PixelStyle>(it, "root.style") } ?: PixelStyle.STANDARD

        val palettes = root.optObj("palettes")?.entries?.mapValues { (pName, pVal) ->
            val pObj = pVal as? JsonValue.Obj
                ?: throw SpecException("Palette '$pName' must be a JSON object")
            parsePalette(pObj, "palettes.$pName")
        } ?: emptyMap()

        val materials = root.optArr("materials")?.items?.mapIndexed { idx, item ->
            val mObj = item as? JsonValue.Obj
                ?: throw SpecException("materials[$idx] must be a JSON object")
            parseMaterial(mObj, idx)
        } ?: emptyList()

        val autotiles = root.optArr("autotiles")?.items?.mapIndexed { idx, item ->
            val aObj = item as? JsonValue.Obj
                ?: throw SpecException("autotiles[$idx] must be a JSON object")
            parseAutotile(aObj, idx)
        } ?: emptyList()

        val cliffs = root.optArr("cliffs")?.items?.mapIndexed { idx, item ->
            val cObj = item as? JsonValue.Obj
                ?: throw SpecException("cliffs[$idx] must be a JSON object")
            parseCliff(cObj, idx)
        } ?: emptyList()

        val props = root.optArr("props")?.items?.mapIndexed { idx, item ->
            val pObj = item as? JsonValue.Obj
                ?: throw SpecException("props[$idx] must be a JSON object")
            parseProp(pObj, idx)
        } ?: emptyList()

        return TilesetSpec(
            name = name,
            seed = seed,
            tileSize = tileSize,
            columns = columns,
            style = style,
            palettes = palettes,
            materials = materials,
            autotiles = autotiles,
            cliffs = cliffs,
            props = props
        )
    }

    /**
     * Serializes a [TilesetSpec] to formatted JSON.
     */
    fun toJson(spec: TilesetSpec): String = buildString {
        append("{\n")
        append("  \"name\": ${quote(spec.name)},\n")
        append("  \"seed\": ${spec.seed},\n")
        append("  \"tileSize\": ${spec.tileSize},\n")
        append("  \"columns\": ${spec.columns}")
        if (spec.style != PixelStyle.STANDARD) {
            append(",\n  \"style\": ${quote(spec.style.name)}")
        }

        if (spec.palettes.isNotEmpty()) {
            append(",\n  \"palettes\": {\n")
            spec.palettes.entries.forEachIndexed { idx, (pName, pSpec) ->
                val comma = if (idx < spec.palettes.size - 1) "," else ""
                append("    ${quote(pName)}: ${paletteToJson(pSpec)}$comma\n")
            }
            append("  }")
        }

        if (spec.materials.isNotEmpty()) {
            append(",\n  \"materials\": [\n")
            spec.materials.forEachIndexed { idx, m ->
                val comma = if (idx < spec.materials.lastIndex) "," else ""
                append("    ${materialToJson(m)}$comma\n")
            }
            append("  ]")
        }

        if (spec.autotiles.isNotEmpty()) {
            append(",\n  \"autotiles\": [\n")
            spec.autotiles.forEachIndexed { idx, a ->
                val comma = if (idx < spec.autotiles.lastIndex) "," else ""
                append("    ${autotileToJson(a)}$comma\n")
            }
            append("  ]")
        }

        if (spec.cliffs.isNotEmpty()) {
            append(",\n  \"cliffs\": [\n")
            spec.cliffs.forEachIndexed { idx, c ->
                val comma = if (idx < spec.cliffs.lastIndex) "," else ""
                append("    ${cliffToJson(c)}$comma\n")
            }
            append("  ]")
        }

        if (spec.props.isNotEmpty()) {
            append(",\n  \"props\": [\n")
            spec.props.forEachIndexed { idx, p ->
                val comma = if (idx < spec.props.lastIndex) "," else ""
                append("    ${propToJson(p)}$comma\n")
            }
            append("  ]")
        }

        append("\n}")
    }

    /**
     * Returns a JSON Schema (Draft 2020-12) describing the [TilesetSpec] format,
     * including all valid enum values and built-in palette preset names for LLM prompting.
     */
    fun jsonSchema(): String {
        val patternEnums = MaterialPattern.entries.joinToString(", ") { quote(it.name) }
        val modeEnums = AutotileMode.entries.joinToString(", ") { quote(it.name) }
        val pairEnums = TerrainPair.entries.joinToString(", ") { quote(it.name) }
        val edgeEnums = AutotileEdge.entries.joinToString(", ") { quote(it.name) }
        val styleEnums = PixelStyle.entries.joinToString(", ") { quote(it.name) }
        val propEnums = PropKind.entries.joinToString(", ") { quote(it.name) }
        val presetNames = SpecPalettes.presets.keys.sorted().joinToString(", ")

        return """
{
  "${"$"}schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "TilesetSpec",
  "description": "Deterministic 2D pixel-art tileset specification for TilesetGen.",
  "type": "object",
  "required": ["name"],
  "properties": {
    "name": { "type": "string", "minLength": 1 },
    "seed": { "type": "integer", "default": 1337 },
    "tileSize": { "type": "integer", "minimum": 8, "multipleOf": 2, "default": 32 },
    "columns": { "type": "integer", "minimum": 1, "default": 8 },
    "style": { "type": "string", "enum": [$styleEnums], "default": "STANDARD" },
    "palettes": {
      "type": "object",
      "description": "Custom named palettes. Built-in presets: [$presetNames]",
      "additionalProperties": { "${"$"}ref": "#/${"$"}defs/PaletteSpec" }
    },
    "materials": {
      "type": "array",
      "items": { "${"$"}ref": "#/${"$"}defs/MaterialTileSpec" }
    },
    "autotiles": {
      "type": "array",
      "items": { "${"$"}ref": "#/${"$"}defs/AutotileLayerSpec" }
    },
    "cliffs": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["name", "topMaterial", "faceMaterial"],
        "properties": {
          "name": { "type": "string" },
          "topMaterial": { "type": "string" },
          "faceMaterial": { "type": "string" },
          "castShadow": { "type": "boolean", "default": true },
          "style": { "type": "string", "enum": [$styleEnums] }
        }
      }
    },
    "props": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["name", "kind"],
        "properties": {
          "name": { "type": "string" },
          "kind": { "type": "string", "enum": [$propEnums] },
          "primaryPalette": { "type": "string" },
          "secondaryPalette": { "type": "string" },
          "style": { "type": "string", "enum": [$styleEnums] }
        }
      }
    }
  },
  "${"$"}defs": {
    "PaletteSpec": {
      "type": "object",
      "description": "Specify either 'colors' (array of #RRGGBB/#RRGGBBAA) or 'baseColor'.",
      "properties": {
        "colors": { "type": "array", "minItems": 2, "items": { "type": "string" } },
        "baseColor": { "type": "string" },
        "steps": { "type": "integer", "minimum": 2, "default": 5 },
        "hueShift": { "type": "number", "default": 0.06 },
        "valueSpread": { "type": "number", "default": 0.35 }
      }
    },
    "MaterialTileSpec": {
      "type": "object",
      "required": ["name", "pattern"],
      "properties": {
        "name": { "type": "string", "minLength": 1 },
        "pattern": { "type": "string", "enum": [$patternEnums] },
        "palette": { "type": "string", "description": "Preset name or key from 'palettes'." },
        "inlinePalette": { "${"$"}ref": "#/${"$"}defs/PaletteSpec" },
        "accentColor": { "type": "string", "description": "Optional #RRGGBB or #RRGGBBAA accent." },
        "scale": { "type": "number", "exclusiveMinimum": 0, "default": 1.0 },
        "detail": { "type": "number", "minimum": 0, "maximum": 1, "default": 0.5 },
        "frames": { "type": "integer", "minimum": 1, "default": 1 }
      }
    },
    "AutotileLayerSpec": {
      "type": "object",
      "required": ["name"],
      "properties": {
        "name": { "type": "string", "minLength": 1 },
        "mode": { "type": "string", "enum": [$modeEnums], "default": "BLOB_47" },
        "columns": { "type": "integer", "minimum": 1 },
        "terrainPair": { "type": "string", "enum": [$pairEnums] },
        "foregroundMaterial": { "type": "string" },
        "backgroundMaterial": { "type": "string" },
        "style": {
          "type": "object",
          "properties": {
            "margin": { "type": "number", "minimum": 0, "default": 5.0 },
            "rimWidth": { "type": "number", "minimum": 0, "default": 2.0 },
            "wobbleAmplitude": { "type": "number", "minimum": 0, "default": 3.5 },
            "rimColor": { "type": "string" },
            "edge": { "type": "string", "enum": [$edgeEnums], "default": "SOFT", "description": "OUTLINED = hard silhouette with a dark outline, lit and shaded inner bands and a background shadow; LUSH = the same plus grass-like tufts jutting over the edge." },
            "shadow": { "type": "number", "minimum": 0, "maximum": 1, "default": 0.25 }
          }
        }
      }
    }
  }
}
        """.trimIndent()
    }

    private fun parsePalette(obj: JsonValue.Obj, path: String): PaletteSpec {
        val colors = obj.optArr("colors")?.items?.mapIndexed { idx, v ->
            (v as? JsonValue.Str)?.value
                ?: throw SpecException("$path.colors[$idx] must be a hex string")
        }
        val baseColor = obj.optString("baseColor")
        val steps = obj.optInt("steps") ?: 5
        val hueShift = obj.optFloat("hueShift") ?: 0.06f
        val valueSpread = obj.optFloat("valueSpread") ?: 0.35f
        return PaletteSpec(colors, baseColor, steps, hueShift, valueSpread)
    }

    private fun parseMaterial(obj: JsonValue.Obj, idx: Int): MaterialTileSpec {
        val path = "materials[$idx]"
        val name = obj.reqString("name", path)
        val patternStr = obj.reqString("pattern", path)
        val pattern = parseEnum<MaterialPattern>(patternStr, "$path.pattern")
        val palette = obj.optString("palette")
        val inlinePalette = obj.optObj("inlinePalette")?.let { parsePalette(it, "$path.inlinePalette") }
        val accentColor = obj.optString("accentColor")
        val scale = obj.optFloat("scale") ?: 1f
        val detail = obj.optFloat("detail") ?: 0.5f
        val frames = obj.optInt("frames") ?: 1
        val variants = obj.optInt("variants") ?: 1
        val decal = obj.optString("decal")?.let { parseEnum<DecalKind>(it, "$path.decal") }
        val style = obj.optString("style")?.let { parseEnum<PixelStyle>(it, "$path.style") }
        return MaterialTileSpec(name, pattern, palette, inlinePalette, accentColor, scale, detail, frames, variants, decal, style)
    }

    private fun parseAutotile(obj: JsonValue.Obj, idx: Int): AutotileLayerSpec {
        val path = "autotiles[$idx]"
        val name = obj.reqString("name", path)
        val mode = obj.optString("mode")?.let { parseEnum<AutotileMode>(it, "$path.mode") } ?: AutotileMode.BLOB_47
        val columns = obj.optInt("columns")
        val terrainPair = obj.optString("terrainPair")?.let { parseEnum<TerrainPair>(it, "$path.terrainPair") }
        val fg = obj.optString("foregroundMaterial")
        val bg = obj.optString("backgroundMaterial")
        val style = obj.optObj("style")?.let { s ->
            AutotileStyleSpec(
                margin = s.optFloat("margin") ?: 5f,
                rimWidth = s.optFloat("rimWidth") ?: 2f,
                wobbleAmplitude = s.optFloat("wobbleAmplitude") ?: 3.5f,
                rimColor = s.optString("rimColor"),
                pixelStyle = s.optString("pixelStyle")?.let { parseEnum<PixelStyle>(it, "$path.style.pixelStyle") },
                edge = s.optString("edge")?.let { parseEnum<AutotileEdge>(it, "$path.style.edge") } ?: AutotileEdge.SOFT,
                shadow = s.optFloat("shadow") ?: 0.25f
            )
        }
        return AutotileLayerSpec(name, mode, columns, terrainPair, fg, bg, style)
    }

    private fun parseCliff(obj: JsonValue.Obj, idx: Int): CliffLayerSpec {
        val path = "cliffs[$idx]"
        val name = obj.reqString("name", path)
        val top = obj.reqString("topMaterial", path)
        val face = obj.reqString("faceMaterial", path)
        val castShadow = obj.optBool("castShadow") ?: true
        val trimColor = obj.optString("trimColor")
        val style = obj.optString("style")?.let { parseEnum<PixelStyle>(it, "$path.style") }
        val a4Layout = obj.optBool("a4Layout") ?: false
        val townFacade = obj.optBool("townFacade") ?: false
        val lush = obj.optBool("lush") ?: false
        return CliffLayerSpec(name, top, face, castShadow, trimColor, style, a4Layout, townFacade, lush)
    }

    private fun parseProp(obj: JsonValue.Obj, idx: Int): PropLayerSpec {
        val path = "props[$idx]"
        val name = obj.reqString("name", path)
        val kind = parseEnum<PropKind>(obj.reqString("kind", path), "$path.kind")
        val primary = obj.optString("primaryPalette")
        val secondary = obj.optString("secondaryPalette")
        val style = obj.optString("style")?.let { parseEnum<PixelStyle>(it, "$path.style") }
        return PropLayerSpec(name, kind, primary, secondary, style)
    }

    private fun paletteToJson(p: PaletteSpec): String = buildString {
        append("{")
        if (p.colors != null) {
            append("\"colors\": [${p.colors.joinToString(", ") { quote(it) }}]")
        } else {
            append("\"baseColor\": ${quote(p.baseColor!!)}, \"steps\": ${p.steps}, \"hueShift\": ${p.hueShift}, \"valueSpread\": ${p.valueSpread}")
        }
        append("}")
    }

    private fun materialToJson(m: MaterialTileSpec): String = buildString {
        val parts = mutableListOf(
            "\"name\": ${quote(m.name)}",
            "\"pattern\": ${quote(m.pattern.name)}"
        )
        if (m.palette != null) parts += "\"palette\": ${quote(m.palette)}"
        if (m.inlinePalette != null) parts += "\"inlinePalette\": ${paletteToJson(m.inlinePalette)}"
        if (m.accentColor != null) parts += "\"accentColor\": ${quote(m.accentColor)}"
        if (m.scale != 1f) parts += "\"scale\": ${m.scale}"
        if (m.detail != 0.5f) parts += "\"detail\": ${m.detail}"
        if (m.frames != 1) parts += "\"frames\": ${m.frames}"
        if (m.variants != 1) parts += "\"variants\": ${m.variants}"
        if (m.decal != null) parts += "\"decal\": ${quote(m.decal.name)}"
        if (m.style != null) parts += "\"style\": ${quote(m.style.name)}"
        append("{${parts.joinToString(", ")}}")
    }

    private fun autotileToJson(a: AutotileLayerSpec): String = buildString {
        val parts = mutableListOf(
            "\"name\": ${quote(a.name)}",
            "\"mode\": ${quote(a.mode.name)}"
        )
        if (a.columns != null) parts += "\"columns\": ${a.columns}"
        if (a.terrainPair != null) parts += "\"terrainPair\": ${quote(a.terrainPair.name)}"
        if (a.foregroundMaterial != null) parts += "\"foregroundMaterial\": ${quote(a.foregroundMaterial)}"
        if (a.backgroundMaterial != null) parts += "\"backgroundMaterial\": ${quote(a.backgroundMaterial)}"
        if (a.style != null) {
            val sParts = mutableListOf(
                "\"margin\": ${a.style.margin}",
                "\"rimWidth\": ${a.style.rimWidth}",
                "\"wobbleAmplitude\": ${a.style.wobbleAmplitude}"
            )
            if (a.style.rimColor != null) sParts += "\"rimColor\": ${quote(a.style.rimColor)}"
            if (a.style.pixelStyle != null) sParts += "\"pixelStyle\": ${quote(a.style.pixelStyle.name)}"
            if (a.style.edge != AutotileEdge.SOFT) sParts += "\"edge\": ${quote(a.style.edge.name)}"
            if (a.style.shadow != 0.25f) sParts += "\"shadow\": ${a.style.shadow}"
            parts += "\"style\": {${sParts.joinToString(", ")}}"
        }
        append("{${parts.joinToString(", ")}}")
    }

    private fun cliffToJson(c: CliffLayerSpec): String = buildString {
        val parts = mutableListOf(
            "\"name\": ${quote(c.name)}",
            "\"topMaterial\": ${quote(c.topMaterial)}",
            "\"faceMaterial\": ${quote(c.faceMaterial)}"
        )
        if (!c.castShadow) parts += "\"castShadow\": false"
        if (c.trimColor != null) parts += "\"trimColor\": ${quote(c.trimColor)}"
        if (c.style != null) parts += "\"style\": ${quote(c.style.name)}"
        if (c.a4Layout) parts += "\"a4Layout\": true"
        if (c.townFacade) parts += "\"townFacade\": true"
        if (c.lush) parts += "\"lush\": true"
        append("{${parts.joinToString(", ")}}")
    }

    private fun propToJson(p: PropLayerSpec): String = buildString {
        val parts = mutableListOf(
            "\"name\": ${quote(p.name)}",
            "\"kind\": ${quote(p.kind.name)}"
        )
        if (p.primaryPalette != null) parts += "\"primaryPalette\": ${quote(p.primaryPalette)}"
        if (p.secondaryPalette != null) parts += "\"secondaryPalette\": ${quote(p.secondaryPalette)}"
        if (p.style != null) parts += "\"style\": ${quote(p.style.name)}"
        append("{${parts.joinToString(", ")}}")
    }

    private inline fun <reified T : Enum<T>> parseEnum(raw: String, field: String): T {
        val upper = raw.trim().uppercase()
        return enumValues<T>().firstOrNull { it.name == upper }
            ?: throw SpecException(
                "Invalid value '$raw' for $field. Expected one of: ${enumValues<T>().joinToString { it.name }}"
            )
    }

    private fun JsonValue.Obj.reqString(key: String, path: String = "root"): String {
        val v = entries[key] ?: throw SpecException("Missing required field '$key' in $path")
        return (v as? JsonValue.Str)?.value ?: throw SpecException("Field '$key' in $path must be a string")
    }

    private fun JsonValue.Obj.optString(key: String): String? = when (val v = entries[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Str -> v.value
        else -> throw SpecException("Field '$key' must be a string")
    }

    private fun JsonValue.Obj.optInt(key: String): Int? = when (val v = entries[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Num -> v.value.toInt()
        else -> throw SpecException("Field '$key' must be an integer")
    }

    private fun JsonValue.Obj.optLong(key: String): Long? = when (val v = entries[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Num -> v.value.toLong()
        else -> throw SpecException("Field '$key' must be an integer")
    }

    private fun JsonValue.Obj.optFloat(key: String): Float? = when (val v = entries[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Num -> v.value.toFloat()
        else -> throw SpecException("Field '$key' must be a number")
    }

    private fun JsonValue.Obj.optBool(key: String): Boolean? = when (val v = entries[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Bool -> v.value
        else -> throw SpecException("Field '$key' must be a boolean")
    }

    private fun JsonValue.Obj.optObj(key: String): JsonValue.Obj? = when (val v = entries[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Obj -> v
        else -> throw SpecException("Field '$key' must be a JSON object")
    }

    private fun JsonValue.Obj.optArr(key: String): JsonValue.Arr? = when (val v = entries[key]) {
        null, JsonValue.Null -> null
        is JsonValue.Arr -> v
        else -> throw SpecException("Field '$key' must be a JSON array")
    }

    private fun quote(s: String): String = buildString {
        append('"')
        for (ch in s) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
        append('"')
    }
}
