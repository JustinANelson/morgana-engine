package com.morgana.persistence

import com.morgana.domain.content.HitReaction
import com.morgana.domain.content.HitboxShape
import com.morgana.domain.content.WeaponSetSlot
import com.morgana.domain.game.BlankGame
import com.morgana.domain.sim.WorldSimulation
import com.morgana.domain.validation.ContentCollection
import com.morgana.domain.validation.IssueSeverity
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Action skills and weapon sets as files (White River roadmap phase A1): hand-written JSON loads with its defaults,
 * reaches the simulation, and a bad combo graph is reported with the file and a pointer into it.
 */
class ActionCombatContentFilesTest {

    private fun put(dir: Path, rel: String, json: String) {
        val file = dir.resolve(rel)
        Files.createDirectories(file.parent)
        Files.writeString(file, json)
    }

    private fun cut(id: Int, extra: String = "") = """
        { "id": $id, "name": "Cut $id", "startupTicks": 2, "activeTicks": 2, "recoveryTicks": 5,$extra
          "hitboxes": [ { "fromTick": 2, "toTick": 3, "shape": "ARC", "radiusPixels": 44, "arcDegrees": 110,
                          "hit": { "damagePercent": 90, "reaction": "STAGGER", "reactionTicks": 6 } } ] }
    """.trimIndent()

    @Test
    fun `hand-written skills and a weapon set load, validate and reach the simulation`(@TempDir dir: Path) {
        put(dir, "action_skills/action_skill_001.json", cut(1))
        put(dir, "action_skills/action_skill_002.json", cut(2, """ "cooldownTicks": 40, "costs": { "mp": 5 },"""))
        put(dir, "weapon_sets/weapon_set_001.json", """
            { "id": 1, "name": "Sword", "skillIds": [1, 2],
              "entries": [ { "input": "LMB", "skillId": 1 } ],
              "edges": [ { "fromSkillId": 1, "input": "LMB", "toSkillId": 2 },
                         { "fromSkillId": 2, "input": "SHIFT+RMB", "toSkillId": 1, "windowTicks": 10, "requiresHit": true } ] }
        """.trimIndent())

        val loaded = ContentLoader.load(dir)
        val report = loaded.fullReport(BlankGame.definition)
        assertTrue(report.issues.isEmpty(), report.issues.toString())

        val skill = loaded.catalog.actionSkills.getValue(1)
        assertEquals(9, skill.totalTicks)
        assertEquals(true, skill.aimLock, "defaults fill what the file leaves out")
        val box = skill.hitboxes.single()
        assertEquals(HitboxShape.ARC, box.shape)
        assertEquals(HitReaction.STAGGER, box.hit.reaction)
        val set = loaded.catalog.weaponSets.getValue(1)
        assertEquals(WeaponSetSlot.MAIN, set.slot)
        assertEquals(6, set.inputBufferTicks)
        assertEquals(8, set.edges[0].windowTicks)
        assertTrue(set.edges[1].requiresHit)

        val sim = WorldSimulation(game = BlankGame.COMPILED)
        WorldContentRepository(dir).loadIntoSimulation(sim)
        assertEquals(setOf(1, 2), sim.actionSkills.keys)
        assertEquals(loaded.catalog.weaponSets, sim.weaponSets.toMap())
    }

    @Test
    fun `a bad graph in a file is rejected with the file and a pointer`(@TempDir dir: Path) {
        put(dir, "action_skills/action_skill_001.json", cut(1))
        put(dir, "action_skills/action_skill_002.json", cut(2))
        put(dir, "action_skills/action_skill_003.json", cut(3, """ "startupTick": 4,""")) // a typo: unknown field
        put(dir, "weapon_sets/weapon_set_001.json", """
            { "id": 1, "name": "Endless", "skillIds": [1, 2, 3, 4],
              "entries": [ { "input": "lmb", "skillId": 1 } ],
              "edges": [ { "fromSkillId": 1, "input": "LMB", "toSkillId": 2 },
                         { "fromSkillId": 2, "input": "LMB", "toSkillId": 1 } ] }
        """.trimIndent())

        val report = ContentLoader.load(dir).fullReport(BlankGame.definition)
        val lines = report.issues.map { it.toString() }
        fun has(text: String) = assertTrue(lines.any { text in it }, "expected '$text' in:\n" + lines.joinToString("\n"))

        has("ERROR weapon_sets/weapon_set_001.json/edges/0 [free_cycle]")
        has("ERROR weapon_sets/weapon_set_001.json/skillIds/3 [missing_reference]")
        has("ERROR weapon_sets/weapon_set_001.json/skillIds/2 [unreachable_skill]")
        has("ERROR weapon_sets/weapon_set_001.json/entries/0/input [invalid_input]")
        val typo = report.issues.single { it.code == "unknown_field" }
        assertEquals(IssueSeverity.WARNING, typo.severity)
        assertEquals("action_skills/action_skill_003.json", typo.file)
        assertTrue(!report.isValid)
    }

    @Test
    fun `the schemas describe the timeline and the graph`() {
        val skill = ContentSchemaExporter.schemaFor(ContentCollection.ACTION_SKILLS)["\$defs"]!!.jsonObject
        val def = skill["com.morgana.domain.content.ActionSkillDefinition"]!!.jsonObject
        val props = def["properties"]!!.jsonObject
        for (field in listOf("startupTicks", "activeTicks", "recoveryTicks", "cancelFromTick", "hitboxes", "armor", "moves", "guard", "costs")) {
            assertTrue(field in props, field)
        }
        assertEquals(listOf("id", "name"), (def["required"] as JsonArray).map { (it as JsonPrimitive).content })
        val shapes = skill["com.morgana.domain.content.SkillHitbox"]!!.jsonObject["properties"]!!.jsonObject["shape"]!!.jsonObject["enum"] as JsonArray
        assertEquals(listOf("CIRCLE", "RECT", "ARC"), shapes.map { (it as JsonPrimitive).content })

        val set = ContentSchemaExporter.schemaFor(ContentCollection.WEAPON_SETS)["\$defs"]!!.jsonObject
        assertTrue("edges" in set["com.morgana.domain.content.WeaponSetDefinition"]!!.jsonObject["properties"]!!.jsonObject)
        val edge = set["com.morgana.domain.content.ComboEdge"]!!.jsonObject
        assertEquals(listOf("fromSkillId", "input", "toSkillId"), (edge["required"] as JsonArray).map { (it as JsonPrimitive).content })
    }
}
