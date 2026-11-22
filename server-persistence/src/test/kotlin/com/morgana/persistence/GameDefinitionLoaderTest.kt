package com.morgana.persistence

import com.morgana.domain.game.BlankGame
import com.morgana.domain.game.MorganaClassic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class GameDefinitionLoaderTest {

    /** A game that is nothing like Morgana Classic: two attributes, a different life vital, a stamina-less world, an expression XP curve. */
    private val otherGame = """
        {
          "schemaVersion": 1,
          "id": "iron-hearts",
          "name": "Iron Hearts",
          "attributes": [
            { "id": "might", "name": "Might", "short": "MGT", "base": 3, "floor": 1, "allocatable": true },
            { "id": "wits",  "name": "Wits",  "base": 3, "floor": 1 }
          ],
          "vitals": [
            { "id": "life",  "name": "Life",  "color": "#ff0000", "role": "life",
              "max": "50 + level*10 + might*8", "regen": { "everyTicks": 60, "amount": "1 + might/4" } },
            { "id": "focus", "name": "Focus", "color": "#3366ff", "max": "wits*10" }
          ],
          "progression": { "maxLevel": 30, "xpExpr": "50 * level * level" },
          "combat": {
            "melee": "max(1, a.might*3 + weapon - d.might)",
            "ranged": "max(1, a.wits*2 + weapon + ammo - armor/2)",
            "spell": "heals ? basePower + a.wits : max(1, basePower + a.wits - d.wits)",
            "critChanceBp": "200 + a.wits*20",
            "dodgeChanceBp": "100"
          }
        }
    """.trimIndent()

    @Test
    fun `a data directory without game json runs the engine default blank game`(@TempDir dir: Path) {
        val loaded = GameDefinitionLoader.load(dir)
        assertNull(loaded.source)
        assertEquals(BlankGame.definition, loaded.game.definition)
    }

    @Test
    fun `the built-in definition can be written out, edited and read back`(@TempDir dir: Path) {
        assertTrue(GameDefinitionLoader.writeBuiltInIfMissing(dir))
        val file = GameDefinitionLoader.fileIn(dir)
        assertTrue(Files.isRegularFile(file))
        assertFalse(Files.exists(file.resolveSibling("game.json.tmp")), "no temporary file is left behind")

        val loaded = GameDefinitionLoader.load(dir)
        assertEquals(file, loaded.source)
        assertEquals(BlankGame.definition, loaded.game.definition, "what was written reads back identically")

        val before = Files.readString(file)
        Files.writeString(file, before.replace("\"Strength\"", "\"Might\""))
        assertFalse(GameDefinitionLoader.writeBuiltInIfMissing(dir), "an existing game.json is never overwritten")
        assertEquals("Might", GameDefinitionLoader.load(dir).game.attribute("str").name)
    }

    @Test
    fun `encoding and decoding a definition is lossless`() {
        val text = GameDefinitionLoader.encode(MorganaClassic.definition)
        assertTrue(text.endsWith("\n"))
        val back = GameDefinitionLoader.compile(Path.of("game.json"), text).definition
        assertEquals(MorganaClassic.definition, back)
    }

    @Test
    fun `a hand-written game with other attributes and vitals loads and calculates`(@TempDir dir: Path) {
        Files.writeString(GameDefinitionLoader.fileIn(dir), otherGame)
        val game = GameDefinitionLoader.load(dir).game

        assertEquals("life", game.lifeVitalId)
        assertEquals(listOf("might", "wits"), game.attributeIds)
        assertEquals(listOf("life", "focus"), game.vitalIds)
        val hero = mapOf("might" to 10, "wits" to 4)
        assertEquals(50 + 5 * 10 + 10 * 8, game.vitalMax("life", 5, hero).toInt())
        assertEquals(40, game.vitalMax("focus", 1, hero).toInt())
        assertEquals(3L, game.regenAmount("life", 1, hero), "1 + 10/4")
        assertEquals(null, game.regenAmount("focus", 1, hero), "focus does not regenerate")
        assertEquals(800L, game.xpToNext(4), "50 * 4 * 4")
        assertNull(game.xpToNext(30))
        assertEquals(10L * 3 + 12 - 6, game.meleeDamage(hero, mapOf("might" to 6, "wits" to 1), 12, 0, crit = false))
        assertEquals(200L + 4 * 20, game.critChanceBp(hero))
    }

    @Test
    fun `an unknown field is an error naming the file`(@TempDir dir: Path) {
        Files.writeString(GameDefinitionLoader.fileIn(dir), otherGame.replace("\"name\": \"Iron Hearts\",", "\"name\": \"Iron Hearts\", \"luck\": 1,"))
        val e = assertThrows(GameDefinitionFileException::class.java) { GameDefinitionLoader.load(dir) }
        assertTrue("game.json" in e.message!!, e.message)
        assertTrue("cannot be read" in e.message!! && "luck" in e.message!!, e.message)
    }

    @Test
    fun `malformed JSON and a missing required field are errors`(@TempDir dir: Path) {
        Files.writeString(GameDefinitionLoader.fileIn(dir), "{ not json")
        assertTrue("cannot be read" in assertThrows(GameDefinitionFileException::class.java) { GameDefinitionLoader.load(dir) }.message!!)
        Files.writeString(GameDefinitionLoader.fileIn(dir), otherGame.replace("\"id\": \"iron-hearts\",", ""))
        assertTrue("cannot be read" in assertThrows(GameDefinitionFileException::class.java) { GameDefinitionLoader.load(dir) }.message!!)
    }

    @Test
    fun `validation problems are listed with their pointers`(@TempDir dir: Path) {
        val broken = otherGame
            .replace("\"role\": \"life\",", "")                // no life vital
            .replace("max(1, a.might*3 + weapon - d.might)", "max(1, a.luck + weapon)") // unknown variable
        Files.writeString(GameDefinitionLoader.fileIn(dir), broken)
        val e = assertThrows(GameDefinitionFileException::class.java) { GameDefinitionLoader.load(dir) }
        assertTrue(e.message!!.startsWith("Invalid "), e.message)
        assertTrue("/vitals: exactly one vital must have role" in e.message!!, e.message)
        assertTrue("/combat/melee" in e.message!! && "luck" in e.message!!, e.message)
    }

    @Test
    fun `an old or future schema version is rejected clearly`(@TempDir dir: Path) {
        Files.writeString(GameDefinitionLoader.fileIn(dir), otherGame.replace("\"schemaVersion\": 1", "\"schemaVersion\": 7"))
        val e = assertThrows(GameDefinitionFileException::class.java) { GameDefinitionLoader.load(dir) }
        assertTrue("/schemaVersion" in e.message!! && "reads 1" in e.message!!, e.message)
    }

    @Test
    fun `formulas that would overflow are rejected at load, not in play`(@TempDir dir: Path) {
        Files.writeString(GameDefinitionLoader.fileIn(dir), otherGame.replace("50 + level*10 + might*8", "pow(might, 6)"))
        val e = assertThrows(GameDefinitionFileException::class.java) { GameDefinitionLoader.load(dir) }
        assertTrue("/vitals/0/max" in e.message!! && "overflow" in e.message!!, e.message)
    }

    @Test
    fun `the game definition schema covers the model and the committed copy is current`() {
        val schema = ContentSchemaExporter.gameDefinitionSchema()
        val defs = schema["\$defs"] as kotlinx.serialization.json.JsonObject
        assertTrue(defs.containsKey("com.morgana.domain.game.GameDefinition"))
        assertTrue(defs.containsKey("com.morgana.domain.game.VitalDefinition"))
        assertEquals(schema.toString(), ContentSchemaExporter.gameDefinitionSchema().toString(), "schema output is deterministic")

        val file = Path.of("..", "docs", "schemas", "game.schema.json")
        assertTrue(Files.exists(file), "Missing $file. Regenerate with: gradlew :server-app:exportContentSchemas")
        assertEquals(
            schema, kotlinx.serialization.json.Json.parseToJsonElement(Files.readString(file)),
            "game.schema.json is stale. Regenerate with: gradlew :server-app:exportContentSchemas"
        )
    }
}
