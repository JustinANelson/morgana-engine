package com.morgana.client.editor.forms

import com.morgana.client.editor.FieldRef
import com.morgana.protocol.WorldContentCatalogDto

/** What a field holds, which decides the widget the form panel shows for it. */
enum class FormFieldType {
    TEXT,
    INT,
    LONG,
    BOOL,
    /** One of [FormField.choices]. */
    CHOICE,
    /** A comma-separated list of whole numbers (class ids, spell ids). */
    ID_LIST,
    /** An icon id, chosen with the icon picker. */
    ICON,
    /** A character sprite id, chosen with the sprite picker. */
    SPRITE,
    /** An animation id, chosen with the animation picker. */
    ANIMATION,
    /** Several lines of text, one entry per line (see the form for the line format). */
    LINES
}

/** One typed input of a content form. Values are kept as text so the panel can hold a half-typed number. */
data class FormField(
    val key: String,
    val label: String,
    val type: FormFieldType,
    val default: String = "",
    val choices: List<String> = emptyList(),
    /** For INT and LONG fields: smallest and largest allowed value. */
    val min: Long = Long.MIN_VALUE,
    val max: Long = Long.MAX_VALUE,
    /** For INT fields that name other content: the panel shows the name beside the number. */
    val ref: FieldRef = FieldRef.NONE,
    /** For TEXT fields: an empty value is a problem. */
    val required: Boolean = false,
    /** For CHOICE fields: an empty value (shown as "none") is allowed. */
    val optional: Boolean = false,
    /** A short explanation shown under the field. */
    val hint: String = ""
)

/** A titled group of fields, shown under one heading. */
data class FormSection(val title: String, val fields: List<FormField>)

/** What a form needs to know about the game to offer the right per-attribute and per-vital fields. */
data class FormContext(
    val attributeIds: List<String> = DEFAULT_ATTRIBUTES,
    val vitalIds: List<String> = DEFAULT_VITALS,
    /** The vital whose reaching zero kills (health). Spells may not cost it, and new NPCs start with it. */
    val lifeVitalId: String = DEFAULT_LIFE_VITAL,
    val catalog: WorldContentCatalogDto = WorldContentCatalogDto(),
    /** The ids of the game's equipment slots (items name one with `equipSlot`). */
    val slotIds: List<String> = com.morgana.domain.item.EquipmentSlot.CLASSIC.map { it.id }
) {
    /** The vitals a spell may cost: every vital except the life vital. */
    val costVitalIds: List<String> get() = vitalIds.filter { it != lifeVitalId }

    companion object {
        val DEFAULT_ATTRIBUTES = listOf("str", "int", "agi")
        val DEFAULT_VITALS = listOf("hp", "mp")
        const val DEFAULT_LIFE_VITAL = "hp"
    }
}

/**
 * A content form as plain data: the fields of one kind of definition (items, NPCs, spells), how a definition becomes form values
 * and back, and what is wrong with typed values. No graphics, so every rule is unit-tested; the generic form panel draws any
 * [ContentForm].
 *
 * Values are text keyed by [FormField.key]. [apply] is forgiving (a bad or missing value keeps the definition's current
 * setting) while [problems] says exactly what is wrong, so the panel can explain instead of silently changing the user's input.
 */
interface ContentForm<T> {
    /** Plural name for panel titles, e.g. "Items". */
    val title: String

    /** Singular name for buttons, e.g. "item". */
    val noun: String

    fun sections(context: FormContext): List<FormSection>

    fun idOf(definition: T): Int

    fun nameOf(definition: T): String

    /** The first id to use when the catalog is empty. */
    val firstId: Int get() = 1

    /** A new definition with [id] and sensible defaults for the game described by [context]. */
    fun create(id: Int, context: FormContext): T

    /** The definitions of this kind in [catalog]. */
    fun all(catalog: WorldContentCatalogDto): List<T>

    fun valuesOf(definition: T, context: FormContext): Map<String, String>

    fun apply(definition: T, values: Map<String, String>, context: FormContext): T

    /** Problems that depend on more than one field or need the catalog (the generic per-field checks are [FormRules.problems]). */
    fun extraProblems(values: Map<String, String>, context: FormContext): List<String> = emptyList()
}

/** The generic per-field checks and small helpers shared by every form. */
object FormRules {

    /** All problems with [values] for [form]: the per-field rules of its fields plus its own cross-field checks. */
    fun <T> problems(form: ContentForm<T>, values: Map<String, String>, context: FormContext): List<String> =
        fieldProblems(form.sections(context).flatMap { it.fields }, values) + form.extraProblems(values, context)

    fun fieldProblems(fields: List<FormField>, values: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        for (f in fields) {
            val raw = (values[f.key] ?: f.default).trim()
            when (f.type) {
                FormFieldType.TEXT -> if (f.required && raw.isEmpty()) out += "${f.label} cannot be empty"
                FormFieldType.INT, FormFieldType.ICON, FormFieldType.SPRITE, FormFieldType.ANIMATION -> {
                    val n = raw.toLongOrNull()
                    when {
                        n == null || n !in Int.MIN_VALUE..Int.MAX_VALUE -> out += "${f.label} must be a whole number"
                        n < f.min -> out += "${f.label} must be at least ${f.min}"
                        n > f.max -> out += "${f.label} must be at most ${f.max}"
                    }
                }
                FormFieldType.LONG -> {
                    val n = raw.toLongOrNull()
                    when {
                        n == null -> out += "${f.label} must be a whole number"
                        n < f.min -> out += "${f.label} must be at least ${f.min}"
                        n > f.max -> out += "${f.label} must be at most ${f.max}"
                    }
                }
                FormFieldType.CHOICE -> if ((raw.isEmpty() && !f.optional) || (raw.isNotEmpty() && raw !in f.choices)) out += "${f.label}: pick one of the choices"
                FormFieldType.ID_LIST -> if (parseIds(raw) == null) out += "${f.label} must be whole numbers separated by commas"
                FormFieldType.BOOL, FormFieldType.LINES -> Unit
            }
        }
        return out
    }

    /** Parses "1, 2, 3" into ids; null if any part is not a positive whole number. An empty text is an empty list. */
    fun parseIds(raw: String): List<Int>? {
        if (raw.isBlank()) return emptyList()
        val parts = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val ids = parts.map { it.toIntOrNull() ?: return null }
        return if (ids.any { it < 1 }) null else ids
    }

    fun int(values: Map<String, String>, key: String, fallback: Int): Int = values[key]?.trim()?.toIntOrNull() ?: fallback

    fun long(values: Map<String, String>, key: String, fallback: Long): Long = values[key]?.trim()?.toLongOrNull() ?: fallback

    fun bool(values: Map<String, String>, key: String, fallback: Boolean): Boolean = values[key]?.let { it == "true" } ?: fallback

    fun text(values: Map<String, String>, key: String, fallback: String): String = values[key]?.trim() ?: fallback

    /**
     * Changes the entries of [existing] for the given [keys] from the values named `<prefix><key>`: a zero removes the entry,
     * anything else sets it, and entries for other keys (set by hand in a data file) are left untouched.
     */
    fun mergeMap(existing: Map<String, Int>, prefix: String, keys: List<String>, values: Map<String, String>): Map<String, Int> {
        val result = existing.toMutableMap()
        for (key in keys) {
            val raw = values["$prefix$key"]?.trim() ?: continue
            val n = raw.toIntOrNull() ?: continue
            if (n == (existing[key] ?: 0)) continue // unchanged: leave the entry exactly as authored (even an explicit 0)
            if (n == 0) result.remove(key) else result[key] = n
        }
        return result
    }

    /** The values `<prefix><key>` for every key, taken from [map] (0 when absent). */
    fun mapValues(map: Map<String, Int>, prefix: String, keys: List<String>): Map<String, String> =
        keys.associate { "$prefix$it" to (map[it] ?: 0).toString() }
}

