package com.morgana.persistence

import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.content.ShopDefinition
import com.morgana.domain.content.ShopEntry
import com.morgana.domain.validation.ContentCollection
import com.morgana.domain.validation.ContentValidator
import com.morgana.domain.validation.IssueSeverity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class Phase11ContentPipelineTest {

    private fun seededWorld(dir: Path): Path {
        val store = MorganaDataStore(dir)
        GameScaffolder.seedIfEmpty(store.world)
        return dir.resolve("world")
    }

    @Test
    fun `the seeded world loads from files and passes full validation with no errors`(@TempDir dir: Path) {
        val result = ContentLoader.load(seededWorld(dir))
        assertEquals(1, result.catalog.maps.size)
        assertEquals(1, result.catalog.items.size)
        val report = result.fullReport()
        assertTrue(report.isValid, "The seeded world must be valid but had:\n" + report.errors.joinToString("\n"))
        assertTrue(result.fileIssues.isEmpty(), result.fileIssues.joinToString("\n"))
    }

    @Test
    fun `validator reports broken references with collection, id, and JSON pointer`(@TempDir dir: Path) {
        val catalog = ContentLoader.load(seededWorld(dir)).catalog
        val shop = ShopDefinition(id = 1, name = "General Store", entries = listOf(ShopEntry(itemId = 1)))
        val broken = shop.copy(entries = listOf(ShopEntry(itemId = 9999)) + shop.entries)
        val report = ContentValidator.validate(catalog.copy(shops = catalog.shops + (shop.id to broken)), MorganaClassic.definition)

        val issue = report.errors.single { it.collection == ContentCollection.SHOPS && it.id == shop.id }
        assertEquals("/entries/0/itemId", issue.pointer)
        assertEquals("missing_reference", issue.code)
        assertEquals(IssueSeverity.ERROR, issue.severity)
        assertEquals(1, report.forDocument(ContentCollection.SHOPS, shop.id).errors.size)
    }

    @Test
    fun `validator enforces behavior requirements, ranges, and schema version`(@TempDir dir: Path) {
        val catalog = ContentLoader.load(seededWorld(dir)).catalog
        val npc = catalog.npcs.values.first()
        val bad = npc.copy(behavior = NpcBehavior.SHOPKEEPER, shopId = 0, vitals = mapOf("hp" to 0), schemaVersion = 99)
        val report = ContentValidator.validate(catalog.copy(npcs = catalog.npcs + (npc.id to bad)), MorganaClassic.definition)
            .forDocument(ContentCollection.NPCS, npc.id)
        val codes = report.errors.map { it.code to it.pointer }.toSet()
        assertTrue("missing_required" to "/shopId" in codes, codes.toString())
        assertTrue("out_of_range" to "/vitals/hp" in codes, codes.toString())
        assertTrue("newer_schema" to "/schemaVersion" in codes, codes.toString())
    }

    @Test
    fun `loader reports malformed files, typo fields, and filename mismatches without throwing`(@TempDir dir: Path) {
        val world = seededWorld(dir)
        MorganaDataStore(dir).world.items.save(2, com.morgana.domain.item.ItemDefinition(id = 2, name = "Pebble"))
        Files.writeString(world.resolve("items/item_900.json"), "{ not json")
        val itemFile = world.resolve("items").resolve("item_001.json")
        Files.writeString(itemFile, Files.readString(itemFile).replaceFirst("\"name\"", "\"nmae_typo\": 1,\n  \"name\""))
        Files.copy(world.resolve("items/item_002.json"), world.resolve("items/renamed.json"))

        val issues = ContentLoader.load(world).fileIssues
        val codes = issues.map { it.code to it.file }.toSet()
        assertTrue("parse_error" to "items/item_900.json" in codes, codes.toString())
        assertTrue("unknown_field" to "items/item_001.json" in codes, codes.toString())
        assertTrue(issues.any { it.code == "duplicate_id" && it.file == "items/renamed.json" }, codes.toString())
        assertEquals(IssueSeverity.ERROR, issues.first { it.code == "parse_error" }.severity)
        assertEquals(IssueSeverity.WARNING, issues.first { it.code == "unknown_field" }.severity)
    }

    @Test
    fun `generated JSON schemas cover every collection, include polymorphic discriminators, and handle recursion`() {
        val schemas = ContentSchemaExporter.allSchemas()
        assertEquals(ContentCollection.entries.toSet(), schemas.keys)

        val item = schemas.getValue(ContentCollection.ITEMS)
        val itemDefs = item["\$defs"]!!.jsonObject
        val itemDef = itemDefs["com.morgana.domain.item.ItemDefinition"]!!.jsonObject
        val required = (itemDef["required"] as JsonArray).map { (it as JsonPrimitive).content }
        assertTrue("id" in required && "name" in required)
        assertTrue("schemaVersion" !in required, "defaulted fields are optional")
        assertTrue("maxStack" in itemDef["properties"]!!.jsonObject)

        val map = schemas.getValue(ContentCollection.MAPS)["\$defs"]!!.jsonObject
        val attribute = map["com.morgana.domain.map.TileAttribute"]!!.jsonObject["oneOf"] as JsonArray
        val warp = attribute.map { it.jsonObject }.first {
            it["properties"]!!.jsonObject["_type"]!!.jsonObject["const"] == JsonPrimitive("warp")
        }
        assertNotNull(warp["properties"]!!.jsonObject["targetMapId"])

        // EventCommand -> DialogueChoice -> EventCommand is recursive and must terminate.
        val events = schemas.getValue(ContentCollection.EVENTS)["\$defs"]!!.jsonObject
        assertTrue(events.containsKey("com.morgana.domain.event.EventCommand"))
        assertTrue(events.containsKey("com.morgana.domain.event.DialogueChoice"))
    }

    @Test
    fun `schema output is deterministic`() {
        val first = ContentSchemaExporter.schemaFor(ContentCollection.EVENTS).toString()
        val second = ContentSchemaExporter.schemaFor(ContentCollection.EVENTS).toString()
        assertEquals(first, second)
        assertTrue(JsonObject::class.isInstance(ContentSchemaExporter.schemaFor(ContentCollection.MAPS)))
    }

    @Test
    fun `committed docs schemas match the Kotlin models`() {
        val dir = java.nio.file.Paths.get("..", "docs", "schemas")
        for (col in ContentCollection.entries) {
            val file = dir.resolve("${col.directoryName}.schema.json")
            assertTrue(
                Files.exists(file),
                "Missing $file. Regenerate with: gradlew :server-app:exportContentSchemas"
            )
            val committed = kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(file))
            assertEquals(
                ContentSchemaExporter.schemaFor(col), committed,
                "${col.directoryName}.schema.json is stale. Regenerate with: gradlew :server-app:exportContentSchemas"
            )
        }
    }

    @Test
    fun `common events and server world switches or numbers persist through repository and content loader`(@TempDir dir: Path) {
        val store = MorganaDataStore(dir)
        val common = com.morgana.domain.event.CommonEventDefinition(
            id = 12,
            name = "Shared Blessing",
            description = "Heals player and increments a world counter",
            commands = listOf(
                com.morgana.domain.event.EventCommand.HealPlayerFull,
                com.morgana.domain.event.EventCommand.AddVariable("blessings", 1, com.morgana.domain.event.VariableScope.WORLD)
            )
        )
        store.world.commonEvents.save(12, common)

        val loaded = ContentLoader.load(dir.resolve("world"))
        assertEquals(common, loaded.catalog.commonEvents[12])
        assertTrue(loaded.fullReport().isValid, loaded.fullReport().errors.joinToString("\n"))

        ServerState.save(dir, ServerState(worldEpochTicks = 77L, worldSwitches = mapOf("festival" to true), worldVariables = mapOf("blessings" to 4), worldStringVariables = mapOf("motto" to "Dawn")))
        val state = ServerState.load(dir)
        assertEquals(mapOf("festival" to true), state.worldSwitches)
        assertEquals(mapOf("blessings" to 4), state.worldVariables)
        assertEquals(mapOf("motto" to "Dawn"), state.worldStringVariables)
    }
}
