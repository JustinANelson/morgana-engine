package com.morgana.client.editor

import com.morgana.client.editor.forms.ChoiceText
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.ResourceCategory
import com.morgana.domain.event.QuestObjectiveType
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.ToolType
import com.morgana.domain.stats.StatusEffectType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditorHelpTest {

    @Test
    fun `every shortcut the help lists really does something`() {
        for (s in EditorHelp.shortcuts.filter { it.keys != "Esc" }) {
            assertNotNull(EditorShortcuts.resolve(s.keycode, s.ctrl, s.shift, alt = false, typing = false), "${s.keys} should be a real shortcut")
        }
    }

    @Test
    fun `every map editor shortcut is listed in the help`() {
        val listed = EditorHelp.shortcuts.map { Triple(it.keycode, it.ctrl, it.shift) }.toSet()
        for (key in 0..255) for (ctrl in listOf(false, true)) {
            if (EditorShortcuts.resolve(key, ctrl, shift = false, alt = false, typing = false) != null) {
                assertTrue(Triple(key, ctrl, false) in listed, "key $key (ctrl=$ctrl) works but the help does not list it")
            }
        }
    }

    @Test
    fun `choices read as plain words`() {
        assertEquals("Quest giver", ChoiceText.label("QUEST_GIVER"))
        assertEquals("Herb patch", ChoiceText.label("HERB_PATCH"))
        assertEquals("Aggressive", ChoiceText.label("ATTACK_ON_SIGHT"))
        assertEquals("Quest", ChoiceText.label("QUEST"))
        assertEquals("QUEST_GIVER", ChoiceText.rawFor("Quest giver", NpcBehavior.entries.map { it.name }))
    }

    @Test
    fun `no two choices of one field share a label and none shouts`() {
        val fields = listOf(NpcBehavior.entries, ItemType.entries, ToolType.entries, StatusEffectType.entries, ResourceCategory.entries, QuestObjectiveType.entries)
        for (entries in fields) {
            val labels = entries.map { ChoiceText.label(it.name) }
            assertEquals(labels.size, labels.toSet().size, "labels clash in $entries")
            labels.forEach { assertFalse(it.contains('_') || it == it.uppercase() && it.length > 3, "'$it' still looks like a raw value") }
        }
    }

    @Test
    fun `the engine's choices are all explained`() {
        for (v in NpcBehavior.entries.map { it.name } + ItemType.entries.map { it.name } + ResourceCategory.entries.map { it.name } + QuestObjectiveType.entries.map { it.name }) {
            assertTrue(ChoiceText.explain(v).isNotEmpty(), "$v has no explanation")
        }
        assertEquals("Absorbs some damage.", ChoiceText.explain("SHIELD", "status"))
        assertEquals("Held in the off hand to block damage.", ChoiceText.explain("SHIELD", "type"))
    }
}
