package com.morgana.persistence

import com.morgana.domain.content.AnimationDefinition
import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.RecipeDefinition
import com.morgana.domain.content.ResourceDefinition
import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.event.CommonEventDefinition
import com.morgana.domain.event.EventDefinition
import com.morgana.domain.game.GameDefinition
import com.morgana.domain.event.QuestDefinition
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.validation.ContentCollection
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Generates JSON Schema (draft 2020-12) documents for every content collection directly from the
 * kotlinx.serialization descriptors, so the schemas can never drift from the Kotlin domain models.
 *
 * Polymorphic types (tile attributes, event commands) use the `_type` discriminator that content files use.
 * Hand-editors, scripts, and AI agents can use these schemas for validation and autocomplete.
 */
@OptIn(ExperimentalSerializationApi::class)
object ContentSchemaExporter {
    const val DISCRIMINATOR = "_type"

    fun serializerFor(col: ContentCollection): KSerializer<*> = when (col) {
        ContentCollection.MAPS -> MapDefinition.serializer()
        ContentCollection.CLASSES -> ClassDefinition.serializer()
        ContentCollection.ITEMS -> ItemDefinition.serializer()
        ContentCollection.NPCS -> NpcDefinition.serializer()
        ContentCollection.SPELLS -> SpellDefinition.serializer()
        ContentCollection.SHOPS -> ShopDefinition.serializer()
        ContentCollection.RESOURCES -> ResourceDefinition.serializer()
        ContentCollection.RECIPES -> RecipeDefinition.serializer()
        ContentCollection.QUESTS -> QuestDefinition.serializer()
        ContentCollection.EVENTS -> EventDefinition.serializer()
        ContentCollection.COMMON_EVENTS -> CommonEventDefinition.serializer()
        ContentCollection.ANIMATIONS -> AnimationDefinition.serializer()
        ContentCollection.ACTION_SKILLS -> com.morgana.domain.content.ActionSkillDefinition.serializer()
        ContentCollection.WEAPON_SETS -> com.morgana.domain.content.WeaponSetDefinition.serializer()
    }

    fun schemaFor(col: ContentCollection): JsonObject = SchemaBuilder().build("Morgana ${col.directoryName} document", serializerFor(col).descriptor)

    /** Schema for `game.json` (the game definition), written to `docs/schemas/game.schema.json`. */
    fun gameDefinitionSchema(): JsonObject = SchemaBuilder().build("Morgana game definition (game.json)", GameDefinition.serializer().descriptor)

    fun allSchemas(): Map<ContentCollection, JsonObject> = ContentCollection.entries.associateWith { schemaFor(it) }

    private class SchemaBuilder {
        private val defs = linkedMapOf<String, JsonElement>()

        fun build(title: String, root: SerialDescriptor): JsonObject {
            val rootRef = ref(root)
            return buildJsonObject {
                put("\$schema", "https://json-schema.org/draft/2020-12/schema")
                put("title", title)
                put("\$ref", rootRef)
                put("\$defs", JsonObject(defs.toMap()))
            }
        }

        private fun refName(d: SerialDescriptor): String = d.serialName.removeSuffix("?")

        private fun ref(d: SerialDescriptor): String {
            val name = refName(d)
            if (name !in defs) {
                defs[name] = JsonObject(emptyMap()) // placeholder so recursive types terminate
                defs[name] = definition(d)
            }
            return "#/\$defs/$name"
        }

        private fun schema(d: SerialDescriptor): JsonElement {
            val base = nonNull(d)
            return if (d.isNullable) {
                buildJsonObject {
                    put("anyOf", JsonArray(listOf(base, buildJsonObject { put("type", "null") })))
                }
            } else {
                base
            }
        }

        private fun nonNull(d: SerialDescriptor): JsonElement = when (d.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> typed("string")
            PrimitiveKind.BOOLEAN -> typed("boolean")
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> typed("integer")
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> typed("number")
            SerialKind.ENUM -> buildJsonObject {
                put("type", "string")
                put("enum", JsonArray((0 until d.elementsCount).map { JsonPrimitive(d.getElementName(it)) }))
            }
            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", schema(d.getElementDescriptor(0)))
            }
            StructureKind.MAP -> buildJsonObject {
                put("type", "object")
                put("additionalProperties", schema(d.getElementDescriptor(1)))
            }
            StructureKind.CLASS, StructureKind.OBJECT, PolymorphicKind.SEALED -> buildJsonObject {
                put("\$ref", ref(d))
            }
            else -> JsonObject(emptyMap())
        }

        private fun typed(type: String) = buildJsonObject { put("type", type) }

        private fun definition(d: SerialDescriptor): JsonElement = when (d.kind) {
            PolymorphicKind.SEALED -> {
                val subclasses = d.getElementDescriptor(1).elementDescriptors
                buildJsonObject {
                    put("oneOf", JsonArray(subclasses.map { objectSchema(it, discriminator = it.serialName) }))
                }
            }
            else -> objectSchema(d, discriminator = null)
        }

        private fun objectSchema(d: SerialDescriptor, discriminator: String?): JsonObject {
            val properties = linkedMapOf<String, JsonElement>()
            val required = mutableListOf<String>()
            if (discriminator != null) {
                properties[DISCRIMINATOR] = buildJsonObject { put("const", discriminator) }
                required += DISCRIMINATOR
            }
            for (i in 0 until d.elementsCount) {
                val name = d.getElementName(i)
                properties[name] = schema(d.getElementDescriptor(i))
                if (!d.isElementOptional(i)) required += name
            }
            return buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(properties))
                put("required", JsonArray(required.map { JsonPrimitive(it) }))
                put("additionalProperties", false)
            }
        }
    }
}
