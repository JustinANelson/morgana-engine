package com.morgana.tilegen.spec

import com.morgana.tilegen.material.PaletteRamp
import com.morgana.tilegen.material.PixelStyle
import com.morgana.tilegen.sprite.AttachmentKind
import com.morgana.tilegen.sprite.AttachmentSocket
import com.morgana.tilegen.sprite.BodyAncestry
import com.morgana.tilegen.sprite.CustomPixelAttachment
import com.morgana.tilegen.sprite.Direction8
import com.morgana.tilegen.sprite.FeetStyle
import com.morgana.tilegen.sprite.GloveStyle
import com.morgana.tilegen.sprite.HairStyle
import com.morgana.tilegen.sprite.HeadgearKind
import com.morgana.tilegen.sprite.LegsStyle
import com.morgana.tilegen.sprite.RobeHem
import com.morgana.tilegen.sprite.PaperDollAction
import com.morgana.tilegen.sprite.PaperDollJob
import com.morgana.tilegen.sprite.PaperDollLoadout
import com.morgana.tilegen.sprite.PaperDollProportions
import com.morgana.tilegen.sprite.PaperDollTheme
import com.morgana.tilegen.sprite.PaperDollThemes
import com.morgana.tilegen.sprite.ShieldKind
import com.morgana.tilegen.sprite.TorsoStyle
import com.morgana.tilegen.sprite.WeaponKind

/**
 * Palette reference in a [PaperDollSpec]: either a named preset in [SpecPalettes.presets]
 * or an inline [PaletteSpec] (hex array or baseColor ramp).
 */
sealed interface PaperDollPaletteRef {
    fun resolve(): PaletteRamp

    data class Named(val name: String) : PaperDollPaletteRef {
        override fun resolve(): PaletteRamp =
            SpecPalettes.presets[name.lowercase().trim()]
                ?: throw SpecException("Unknown palette preset '$name'")
    }

    data class Inline(val spec: PaletteSpec) : PaperDollPaletteRef {
        override fun resolve(): PaletteRamp = spec.toPaletteRamp()
    }
}

/**
 * Custom theme specification for a [PaperDollSpec], supporting preset resolution
 * plus per-channel palette overrides (preset name or [PaletteSpec]).
 */
data class PaperDollThemeSpec(
    val preset: String? = null,
    val ancestry: BodyAncestry? = null,
    val skinPalette: PaperDollPaletteRef? = null,
    val hairPalette: PaperDollPaletteRef? = null,
    val outfitPalette: PaperDollPaletteRef? = null,
    val accentPalette: PaperDollPaletteRef? = null,
    val secondaryPalette: PaperDollPaletteRef? = null,
    val auraPalette: PaperDollPaletteRef? = null,
    val eyeColor: String? = null,
    val style: PixelStyle? = null,
    val proportions: PaperDollProportions? = null
) {
    fun resolve(job: PaperDollJob): PaperDollTheme {
        val base = preset?.let {
            PaperDollThemes.resolve(it)
                ?: throw SpecException("Unknown paper-doll theme preset '$it'")
        } ?: PaperDollTheme.fromJob(job)

        val resolvedAncestry = ancestry ?: base.ancestry
        val resolvedSkin = skinPalette?.resolve() ?: if (ancestry != null && preset == null) {
            PaperDollTheme.defaultSkinFor(resolvedAncestry)
        } else {
            base.skinPalette
        }
        val resolvedEye = eyeColor?.let { parseHexRgba(it) } ?: if (ancestry != null && preset == null) {
            PaperDollTheme.defaultEyeFor(resolvedAncestry)
        } else {
            base.eyeColor
        }
        return base.copy(
            name = preset ?: base.name,
            ancestry = resolvedAncestry,
            skinPalette = resolvedSkin,
            eyeColor = resolvedEye,
            hairPalette = hairPalette?.resolve() ?: base.hairPalette,
            outfitPalette = outfitPalette?.resolve() ?: base.outfitPalette,
            accentPalette = accentPalette?.resolve() ?: base.accentPalette,
            secondaryPalette = secondaryPalette?.resolve() ?: base.secondaryPalette,
            auraPalette = auraPalette?.resolve() ?: base.auraPalette,
            style = style ?: base.style,
            proportions = proportions ?: base.proportions
        )
    }

    companion object {
        internal fun parseHexRgba(hex: String): Int {
            val clean = hex.trim().removePrefix("#")
            require(clean.length == 6 || clean.length == 8) {
                "Invalid hex color '$hex'; expected #RRGGBB or #RRGGBBAA"
            }
            val r = clean.substring(0, 2).toInt(16)
            val g = clean.substring(2, 4).toInt(16)
            val b = clean.substring(4, 6).toInt(16)
            val a = if (clean.length == 8) clean.substring(6, 8).toInt(16) else 255
            return PaletteRamp.rgba(r, g, b, a)
        }
    }
}

/**
 * JSON-serializable custom pixel-grid attachment anchored to an [AttachmentSocket].
 */
data class CustomAttachmentSpec(
    val name: String,
    val socket: AttachmentSocket,
    val rows: List<String>,
    val palette: PaperDollPaletteRef? = null,
    val offsetX: Int = 0,
    val offsetY: Int = 0,
    val hideWhenFacingBack: Boolean = false,
    val hideWhenFacingFront: Boolean = false
) {
    fun toCustomPixelAttachment(): CustomPixelAttachment = CustomPixelAttachment(
        name = name,
        socket = socket,
        rows = rows,
        palette = palette?.resolve(),
        offsetX = offsetX,
        offsetY = offsetY,
        hideWhenFacingBack = hideWhenFacingBack,
        hideWhenFacingFront = hideWhenFacingFront
    )
}

/**
 * Declarative specification for generating a Universal LPC-style paper-doll character
 * and its compositable layer sheets.
 */
data class PaperDollSpec(
    val name: String,
    val job: PaperDollJob = PaperDollJob.HERO,
    val tileSize: Int = 32,
    val tileHeight: Int = tileSize,
    val seed: Long = 1337L,
    val hairStyle: HairStyle? = null,
    val headgear: HeadgearKind? = null,
    val weapon: WeaponKind? = null,
    val shield: ShieldKind? = null,
    val legsStyle: LegsStyle? = null,
    val feetStyle: FeetStyle? = null,
    val gloves: GloveStyle? = null,
    val torsoStyle: TorsoStyle? = null,
    val robeHem: RobeHem? = null,
    val includeCape: Boolean? = null,
    val theme: PaperDollThemeSpec? = null,
    val attachments: List<AttachmentKind> = emptyList(),
    val customAttachments: List<CustomAttachmentSpec> = emptyList(),
    val actions: List<PaperDollAction> = PaperDollAction.ALL,
    val directions8: Boolean = true,
    val exportLayers: Boolean = true,
    val includeShadow: Boolean = true
) {
    init {
        require(name.isNotBlank()) { "PaperDollSpec name must not be blank" }
        require(tileSize >= 16 && tileSize % 2 == 0) { "tileSize must be an even integer >= 16, got $tileSize" }
        require(tileHeight >= 16 && tileHeight % 2 == 0) { "tileHeight must be an even integer >= 16, got $tileHeight" }
        require(actions.isNotEmpty()) { "PaperDollSpec '$name' must have at least one action" }
    }

    fun resolvedDirections(): List<Direction8> = if (directions8) {
        Direction8.entries
    } else {
        listOf(Direction8.S, Direction8.W, Direction8.E, Direction8.N)
    }

    fun toLoadout(): PaperDollLoadout {
        val resolvedTheme = theme?.resolve(job) ?: PaperDollTheme.fromJob(job)
        return PaperDollLoadout(
            job = job,
            hairStyle = hairStyle ?: PaperDollLoadout.defaultHairFor(job),
            headgear = headgear ?: PaperDollLoadout.defaultHeadgearFor(job),
            weapon = weapon ?: PaperDollLoadout.defaultWeaponFor(job),
            shield = shield ?: PaperDollLoadout.defaultShieldFor(job),
            theme = resolvedTheme,
            attachments = attachments.ifEmpty { PaperDollLoadout.defaultAttachmentsFor(job) },
            customAttachments = customAttachments.map { it.toCustomPixelAttachment() },
            includeCape = includeCape ?: PaperDollLoadout.defaultCapeFor(job),
            legsStyle = legsStyle ?: PaperDollLoadout.defaultLegsFor(job),
            feetStyle = feetStyle ?: PaperDollLoadout.defaultFeetFor(job),
            gloves = gloves ?: GloveStyle.NONE,
            torsoStyle = torsoStyle ?: PaperDollLoadout.defaultTorsoFor(job),
            robeHem = robeHem ?: RobeHem.STRAIGHT
        )
    }
}

/**
 * Pure-Kotlin JSON parser & serializer for [PaperDollSpec].
 */
object PaperDollSpecJson {

    fun parse(json: String): PaperDollSpec {
        val root = JsonParser(json).parse() as? JsonValue.Obj
            ?: throw SpecException("PaperDollSpec root must be a JSON object")
        return parseObj(root, "paperdoll")
    }

    internal fun parseObj(obj: JsonValue.Obj, path: String): PaperDollSpec {
        val name = obj.reqString("name", path)
        val job = obj.optEnum("job", PaperDollJob.HERO, path)
        val tileSize = obj.optInt("tileSize", 32, path)
        val tileHeight = obj.optInt("tileHeight", tileSize, path)
        val seed = obj.optLong("seed", 1337L, path)
        val hairStyle = obj.optNullableEnum<HairStyle>("hairStyle", path)
        val headgear = obj.optNullableEnum<HeadgearKind>("headgear", path)
        val weapon = obj.optNullableEnum<WeaponKind>("weapon", path)
        val shield = obj.optNullableEnum<ShieldKind>("shield", path)
        val legsStyle = obj.optNullableEnum<LegsStyle>("legsStyle", path)
        val feetStyle = obj.optNullableEnum<FeetStyle>("feetStyle", path)
        val gloves = obj.optNullableEnum<GloveStyle>("gloves", path)
        val torsoStyle = obj.optNullableEnum<TorsoStyle>("torsoStyle", path)
        val robeHem = obj.optNullableEnum<RobeHem>("robeHem", path)
        val includeCape = obj.optNullableBool("includeCape", path)
        val theme = obj.optObj("theme", path)?.let { parseTheme(it, "$path.theme") }
        val attachments = obj.optStringList("attachments", path).map { raw ->
            parseEnumValue<AttachmentKind>(raw, "$path.attachments")
        }
        val customAttachments = obj.optObjList("customAttachments", path).mapIndexed { i, it ->
            parseCustomAttachment(it, "$path.customAttachments[$i]")
        }
        val actionsRaw = obj.optStringList("actions", path)
        val actions = if (actionsRaw.isEmpty()) {
            PaperDollAction.ALL
        } else {
            actionsRaw.flatMap { raw ->
                when (raw.lowercase().trim()) {
                    "all" -> PaperDollAction.ALL
                    "lpc", "lpc_standard" -> PaperDollAction.LPC_STANDARD
                    "extended" -> PaperDollAction.EXTENDED
                    else -> listOf(parseEnumValue<PaperDollAction>(raw, "$path.actions"))
                }
            }.distinct()
        }
        val directions8 = obj.optBool("directions8", true, path)
        val exportLayers = obj.optBool("exportLayers", true, path)
        val includeShadow = obj.optBool("includeShadow", true, path)

        return PaperDollSpec(
            name = name,
            job = job,
            tileSize = tileSize,
            tileHeight = tileHeight,
            seed = seed,
            hairStyle = hairStyle,
            headgear = headgear,
            weapon = weapon,
            shield = shield,
            legsStyle = legsStyle,
            feetStyle = feetStyle,
            gloves = gloves,
            torsoStyle = torsoStyle,
            robeHem = robeHem,
            includeCape = includeCape,
            theme = theme,
            attachments = attachments,
            customAttachments = customAttachments,
            actions = actions,
            directions8 = directions8,
            exportLayers = exportLayers,
            includeShadow = includeShadow
        )
    }

    fun toJson(spec: PaperDollSpec): String = buildString {
        append("{\n")
        append("  \"name\": \"${escapeJson(spec.name)}\",\n")
        append("  \"job\": \"${spec.job.name.lowercase()}\",\n")
        append("  \"tileSize\": ${spec.tileSize},\n")
        if (spec.tileHeight != spec.tileSize) {
            append("  \"tileHeight\": ${spec.tileHeight},\n")
        }
        append("  \"seed\": ${spec.seed},\n")
        spec.hairStyle?.let { append("  \"hairStyle\": \"${it.name.lowercase()}\",\n") }
        spec.headgear?.let { append("  \"headgear\": \"${it.name.lowercase()}\",\n") }
        spec.weapon?.let { append("  \"weapon\": \"${it.name.lowercase()}\",\n") }
        spec.shield?.let { append("  \"shield\": \"${it.name.lowercase()}\",\n") }
        spec.legsStyle?.let { append("  \"legsStyle\": \"${it.name.lowercase()}\",\n") }
        spec.robeHem?.let { append("  \"robeHem\": \"${it.name.lowercase()}\",\n") }
        spec.feetStyle?.let { append("  \"feetStyle\": \"${it.name.lowercase()}\",\n") }
        spec.gloves?.let { append("  \"gloves\": \"${it.name.lowercase()}\",\n") }
        spec.includeCape?.let { append("  \"includeCape\": $it,\n") }
        spec.theme?.let { t ->
            append("  \"theme\": ")
            appendThemeJson(t, "  ")
            append(",\n")
        }
        if (spec.attachments.isNotEmpty()) {
            val attStr = spec.attachments.joinToString(", ") { "\"${it.name.lowercase()}\"" }
            append("  \"attachments\": [$attStr],\n")
        }
        if (spec.customAttachments.isNotEmpty()) {
            append("  \"customAttachments\": [\n")
            spec.customAttachments.forEachIndexed { idx, ca ->
                val comma = if (idx < spec.customAttachments.lastIndex) "," else ""
                append("    {\n")
                append("      \"name\": \"${escapeJson(ca.name)}\",\n")
                append("      \"socket\": \"${ca.socket.name.lowercase()}\",\n")
                val rowsStr = ca.rows.joinToString(", ") { "\"${escapeJson(it)}\"" }
                append("      \"rows\": [$rowsStr],\n")
                append("      \"offsetX\": ${ca.offsetX},\n")
                append("      \"offsetY\": ${ca.offsetY},\n")
                append("      \"hideWhenFacingBack\": ${ca.hideWhenFacingBack},\n")
                append("      \"hideWhenFacingFront\": ${ca.hideWhenFacingFront}\n")
                append("    }$comma\n")
            }
            append("  ],\n")
        }
        val actStr = spec.actions.joinToString(", ") { "\"${it.name.lowercase()}\"" }
        append("  \"actions\": [$actStr],\n")
        append("  \"directions8\": ${spec.directions8},\n")
        append("  \"exportLayers\": ${spec.exportLayers},\n")
        append("  \"includeShadow\": ${spec.includeShadow}\n")
        append("}")
    }

    private fun StringBuilder.appendThemeJson(t: PaperDollThemeSpec, indent: String) {
        append("{\n")
        val parts = mutableListOf<String>()
        t.preset?.let { parts += "$indent  \"preset\": \"${escapeJson(it)}\"" }
        t.ancestry?.let { parts += "$indent  \"ancestry\": \"${it.name.lowercase()}\"" }
        t.eyeColor?.let { parts += "$indent  \"eyeColor\": \"${escapeJson(it)}\"" }
        t.style?.let { parts += "$indent  \"style\": \"${it.name.lowercase()}\"" }
        t.proportions?.let { parts += "$indent  \"proportions\": \"${it.name.lowercase()}\"" }
        t.skinPalette?.let { parts += "$indent  \"skinPalette\": ${paletteJson(it)}" }
        t.hairPalette?.let { parts += "$indent  \"hairPalette\": ${paletteJson(it)}" }
        t.outfitPalette?.let { parts += "$indent  \"outfitPalette\": ${paletteJson(it)}" }
        t.accentPalette?.let { parts += "$indent  \"accentPalette\": ${paletteJson(it)}" }
        t.secondaryPalette?.let { parts += "$indent  \"secondaryPalette\": ${paletteJson(it)}" }
        t.auraPalette?.let { parts += "$indent  \"auraPalette\": ${paletteJson(it)}" }
        append(parts.joinToString(",\n"))
        append("\n$indent}")
    }

    private fun paletteJson(p: PaperDollPaletteRef): String = when (p) {
        is PaperDollPaletteRef.Named -> "\"${escapeJson(p.name)}\""
        is PaperDollPaletteRef.Inline -> {
            val cols = p.spec.colors
            if (!cols.isNullOrEmpty()) {
                cols.joinToString(", ", prefix = "[", postfix = "]") { "\"${escapeJson(it)}\"" }
            } else {
                "{\"baseColor\": \"${escapeJson(p.spec.baseColor ?: "#ffffff")}\", \"steps\": ${p.spec.steps}}"
            }
        }
    }

    private fun parseTheme(obj: JsonValue.Obj, path: String): PaperDollThemeSpec = PaperDollThemeSpec(
        preset = obj.optNullableString("preset", path),
        ancestry = obj.optNullableEnum<BodyAncestry>("ancestry", path),
        skinPalette = obj.optPalette("skinPalette", "$path.skinPalette"),
        hairPalette = obj.optPalette("hairPalette", "$path.hairPalette"),
        outfitPalette = obj.optPalette("outfitPalette", "$path.outfitPalette"),
        accentPalette = obj.optPalette("accentPalette", "$path.accentPalette"),
        secondaryPalette = obj.optPalette("secondaryPalette", "$path.secondaryPalette"),
        auraPalette = obj.optPalette("auraPalette", "$path.auraPalette"),
        eyeColor = obj.optNullableString("eyeColor", path),
        style = obj.optNullableEnum<PixelStyle>("style", path),
        proportions = obj.optNullableEnum<PaperDollProportions>("proportions", path)
    )

    private fun parseCustomAttachment(obj: JsonValue.Obj, path: String): CustomAttachmentSpec = CustomAttachmentSpec(
        name = obj.reqString("name", path),
        socket = obj.optEnum("socket", AttachmentSocket.BACK, path),
        rows = obj.optStringList("rows", path),
        palette = obj.optPalette("palette", "$path.palette"),
        offsetX = obj.optInt("offsetX", 0, path),
        offsetY = obj.optInt("offsetY", 0, path),
        hideWhenFacingBack = obj.optBool("hideWhenFacingBack", false, path),
        hideWhenFacingFront = obj.optBool("hideWhenFacingFront", false, path)
    )

    private fun JsonValue.Obj.reqString(key: String, path: String): String =
        (entries[key] as? JsonValue.Str)?.value
            ?: throw SpecException("Missing or non-string '$path.$key'")

    private fun JsonValue.Obj.optNullableString(key: String, path: String): String? =
        when (val v = entries[key]) {
            null, JsonValue.Null -> null
            is JsonValue.Str -> v.value
            else -> throw SpecException("Expected string for '$path.$key'")
        }

    private fun JsonValue.Obj.optInt(key: String, default: Int, path: String): Int =
        when (val v = entries[key]) {
            null, JsonValue.Null -> default
            is JsonValue.Num -> v.value.toInt()
            else -> throw SpecException("Expected integer for '$path.$key'")
        }

    private fun JsonValue.Obj.optLong(key: String, default: Long, path: String): Long =
        when (val v = entries[key]) {
            null, JsonValue.Null -> default
            is JsonValue.Num -> v.value.toLong()
            else -> throw SpecException("Expected integer for '$path.$key'")
        }

    private fun JsonValue.Obj.optBool(key: String, default: Boolean, path: String): Boolean =
        when (val v = entries[key]) {
            null, JsonValue.Null -> default
            is JsonValue.Bool -> v.value
            else -> throw SpecException("Expected boolean for '$path.$key'")
        }

    private fun JsonValue.Obj.optNullableBool(key: String, path: String): Boolean? =
        when (val v = entries[key]) {
            null, JsonValue.Null -> null
            is JsonValue.Bool -> v.value
            else -> throw SpecException("Expected boolean for '$path.$key'")
        }

    private fun JsonValue.Obj.optObj(key: String, path: String): JsonValue.Obj? =
        when (val v = entries[key]) {
            null, JsonValue.Null -> null
            is JsonValue.Obj -> v
            else -> throw SpecException("Expected object for '$path.$key'")
        }

    private fun JsonValue.Obj.optStringList(key: String, path: String): List<String> {
        val arr = entries[key] ?: return emptyList()
        if (arr is JsonValue.Null) return emptyList()
        val list = (arr as? JsonValue.Arr)
            ?: throw SpecException("Expected array for '$path.$key'")
        return list.items.mapIndexed { idx, item ->
            (item as? JsonValue.Str)?.value
                ?: throw SpecException("Expected string at '$path.$key[$idx]'")
        }
    }

    private fun JsonValue.Obj.optObjList(key: String, path: String): List<JsonValue.Obj> {
        val arr = entries[key] ?: return emptyList()
        if (arr is JsonValue.Null) return emptyList()
        val list = (arr as? JsonValue.Arr)
            ?: throw SpecException("Expected array for '$path.$key'")
        return list.items.mapIndexed { idx, item ->
            (item as? JsonValue.Obj)
                ?: throw SpecException("Expected object at '$path.$key[$idx]'")
        }
    }

    private fun JsonValue.Obj.optPalette(key: String, path: String): PaperDollPaletteRef? =
        when (val v = entries[key]) {
            null, JsonValue.Null -> null
            is JsonValue.Str -> PaperDollPaletteRef.Named(v.value)
            is JsonValue.Arr -> PaperDollPaletteRef.Inline(
                PaletteSpec(colors = v.items.mapIndexed { idx, item ->
                    (item as? JsonValue.Str)?.value ?: throw SpecException("Expected hex string at '$path[$idx]'")
                })
            )
            is JsonValue.Obj -> PaperDollPaletteRef.Inline(
                PaletteSpec(
                    colors = v.optStringList("colors", path).takeIf { it.isNotEmpty() },
                    baseColor = v.optNullableString("baseColor", path),
                    steps = v.optInt("steps", 5, path)
                )
            )
            else -> throw SpecException("Expected palette name, hex array, or object for '$path'")
        }

    private inline fun <reified T : Enum<T>> JsonValue.Obj.optEnum(key: String, default: T, path: String): T =
        when (val v = entries[key]) {
            null, JsonValue.Null -> default
            is JsonValue.Str -> parseEnumValue(v.value, "$path.$key")
            else -> throw SpecException("Expected string enum for '$path.$key'")
        }

    private inline fun <reified T : Enum<T>> JsonValue.Obj.optNullableEnum(key: String, path: String): T? =
        when (val v = entries[key]) {
            null, JsonValue.Null -> null
            is JsonValue.Str -> parseEnumValue(v.value, "$path.$key")
            else -> throw SpecException("Expected string enum for '$path.$key'")
        }

    private inline fun <reified T : Enum<T>> parseEnumValue(raw: String, path: String): T {
        val normalized = raw.trim().uppercase()
        return enumValues<T>().firstOrNull { it.name == normalized }
            ?: throw SpecException("Unknown value '$raw' for $path; expected one of ${enumValues<T>().joinToString { it.name.lowercase() }}")
    }

    private fun escapeJson(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
