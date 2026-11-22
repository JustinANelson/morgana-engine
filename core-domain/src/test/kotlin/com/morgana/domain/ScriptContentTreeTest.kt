package com.morgana.domain

import com.morgana.domain.content.ActionSkillDefinition
import com.morgana.domain.content.HitboxShape
import com.morgana.domain.content.SkillHit
import com.morgana.domain.content.SkillHitbox
import com.morgana.domain.script.ScriptContentTree
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ScriptContentTreeTest {
    @Test
    fun `a definition becomes maps and lists with defaults written out and nulls left out`() {
        val skill = ActionSkillDefinition(
            id = 7, name = "Cut", startupTicks = 2, activeTicks = 2, recoveryTicks = 4, costs = mapOf("sp" to 5),
            hitboxes = listOf(SkillHitbox(fromTick = 2, toTick = 3, shape = HitboxShape.ARC, radiusPixels = 40, arcDegrees = 90, hit = SkillHit(damagePercent = 80)))
        )
        @Suppress("UNCHECKED_CAST")
        val tree = ScriptContentTree.of(ActionSkillDefinition.serializer(), skill) as Map<String, Any?>
        assertEquals(7, tree["id"])
        assertEquals("Cut", tree["name"])
        assertEquals(mapOf("sp" to 5), tree["costs"])
        assertFalse(tree.containsKey("cancelFromTick"), "an absent optional field is left out")
        @Suppress("UNCHECKED_CAST")
        val box = (tree["hitboxes"] as List<Map<String, Any?>>).single()
        assertEquals("ARC", box["shape"], "enums are their names")
        assertEquals(0, box["hitGroup"], "defaults are written out")
        assertTrue((box["hit"] as Map<*, *>)["damagePercent"] == 80)
    }
}
