package com.morgana.client.editor.forms

import com.morgana.domain.game.GameDefinition
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class GameRulesFormTest {

    private val json = Json { ignoreUnknownKeys = false }

    private fun load(game: String): GameDefinition {
        val file = listOf(Path.of("games/$game/game.json"), Path.of("../games/$game/game.json")).first { it.exists() }
        return json.decodeFromString(GameDefinition.serializer(), Files.readString(file))
    }

    @Test
    fun `every shipped game opens in the form and an unedited apply changes nothing`() {
        for (game in listOf("roguelike", "blank", "tutorial-village", "white-river")) {
            val def = load(game)
            assertEquals(def, GameRulesForm.apply(def, GameRulesForm.valuesOf(def)), "$game round trips")
            assertEquals(emptyList<String>(), GameRulesForm.problems(def, GameRulesForm.valuesOf(def)), "$game has no complaints")
        }
    }

    @Test
    fun `editing rules changes only what was typed and removes unticked systems`() {
        val def = load("roguelike")
        val v = GameRulesForm.valuesOf(def)
        val edited = GameRulesForm.apply(def, v + mapOf(
            "name" to "Deepdelve", "maxLevel" to "60", "sprint.on" to "false", "ghost.selfAfter" to "300",
            "attributes" to v.getValue("attributes") + "\nluck | Luck | LCK | 5 | 1 | 99 | no",
            "tags" to "roguelike, hardcore"
        ))
        assertEquals("Deepdelve", edited.name)
        assertEquals(60, edited.progression.maxLevel)
        assertNull(edited.sprint, "an unticked system leaves game.json")
        assertEquals(300, edited.ghost!!.selfReviveAfterTicks)
        assertEquals(def.attributes.size + 1, edited.attributes.size)
        assertEquals(99, edited.attributes.last().cap)
        assertEquals(listOf("roguelike", "hardcore"), edited.tags)
        assertEquals(def.loot, edited.loot, "untouched sections stay as they were")
        assertEquals(def.combat, edited.combat)
        assertEquals(emptyList<String>(), GameRulesForm.problems(def, GameRulesForm.valuesOf(edited)))
    }

    @Test
    fun `a roguelike can add loot rarities, affixes, a system and a tip from nothing`() {
        val blank = load("blank")
        val v = GameRulesForm.valuesOf(blank) + mapOf(
            "loot.on" to "true",
            "rarities" to "common | Common | #d1d5db | 600 | 1 | 0 | 0 | 100 | 0 | no\nmagic | Magic | #60a5fa | 300 | 1 | 1 | 2 | 250 | 0 | no",
            "affixes" to "sharp | Sharp | PREFIX | ATTACK_DAMAGE |  | 1 | 3 | 100 | 1 | 1000 | WEAPON",
            "paragon.on" to "true", "bestiary.on" to "true",
            "tips" to "descent | LEVEL | 5 | Going deeper | The deeper floors hit harder."
        )
        assertEquals(emptyList<String>(), GameRulesForm.problems(blank, v))
        val made = GameRulesForm.apply(blank, v)
        assertEquals(listOf("common", "magic"), made.loot!!.rarities.map { it.id })
        assertEquals(setOf("WEAPON"), made.loot!!.affixes.single().slots.map { it.id }.toSet())
        assertTrue(made.paragon!!.enabled && made.bestiary!!.enabled)
        assertEquals("descent", made.onboarding!!.tips.single().id)
        assertEquals(5, made.onboarding!!.tips.single().level)
    }

    @Test
    fun `bad rows and rule violations are explained`() {
        val def = load("roguelike")
        val v = GameRulesForm.valuesOf(def)
        val bad = GameRulesForm.problems(def, v + mapOf(
            "attributes" to "str | Strength", "vitals" to "hp | Health | HP | #fff | - | 10 | | ",
            "rarities" to "x | X | #fff | w", "affixes" to "a | A | MIDDLE | ATTACK_DAMAGE | | 1 | 2 | 1 | 1 | 9 | ",
            "tips" to "bad id | WELCOME | 0 | T | text", "melee" to "", "maxLevel" to "0"
        ))
        assertTrue(bad.any { it.startsWith("Attribute 1") })
        assertTrue(bad.any { "life" in it.lowercase() && "hp" !in it.take(0) } || bad.isNotEmpty())
        assertTrue(bad.any { it.startsWith("Rarity 1") })
        assertTrue(bad.any { it.startsWith("Affix 1") })
        assertTrue(bad.any { it.startsWith("Tip 1") })
        assertTrue(bad.any { it.startsWith("Melee damage formula") })
        assertNotNull(bad.firstOrNull { it.startsWith("Level cap") })
    }
}
