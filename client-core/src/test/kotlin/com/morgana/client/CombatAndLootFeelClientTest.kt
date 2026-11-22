package com.morgana.client

import com.morgana.client.editor.forms.FormContext
import com.morgana.client.editor.forms.SpellForm
import com.morgana.client.input.HoldAttackRepeat
import com.morgana.client.ui.GroundLootLabels
import com.morgana.client.ui.bag.SpellInfo
import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import com.morgana.domain.entity.MapGroundItem
import com.morgana.domain.item.AffixDefinition
import com.morgana.domain.item.AffixPosition
import com.morgana.domain.item.AffixStat
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemRolls
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.LootRules
import com.morgana.domain.item.RarityDefinition
import com.morgana.domain.item.RolledAffix
import com.morgana.protocol.WorldContentCatalogDto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CombatAndLootFeelClientTest {

    @Test
    fun `hold attack repeat waits full interval after initial press and stops on release`() {
        val repeat = HoldAttackRepeat(0.15f)
        repeat.markFired()
        assertFalse(repeat.update(held = true, deltaSeconds = 0.05f))
        assertFalse(repeat.update(held = true, deltaSeconds = 0.05f))
        assertTrue(repeat.update(held = true, deltaSeconds = 0.06f), "fires once interval elapsed")
        assertFalse(repeat.update(held = true, deltaSeconds = 0.05f), "waits for next interval")
        assertFalse(repeat.update(held = false, deltaSeconds = 0.50f), "never fires when released")
    }

    @Test
    fun `ground loot labels format rolled rarities, stack vertically on same tile, and hit test cleanly`() {
        val lootRules = LootRules(
            rarities = listOf(
                RarityDefinition("common", "Common", "#d1d5db", weight = 100),
                RarityDefinition("rare", "Rare", "#facc15", weight = 20, affixesMin = 1, affixesMax = 2)
            ),
            affixes = listOf(
                AffixDefinition("cruel", "Cruel", AffixPosition.PREFIX, AffixStat.ATTACK_DAMAGE, min = 3, max = 5)
            )
        )
        val sword = ItemDefinition(id = 1, name = "Sword", type = ItemType.WEAPON)
        val gold = ItemDefinition(id = 2, name = "Gold", type = ItemType.CURRENCY, stackable = true)
        val itemsById = mapOf(1 to sword, 2 to gold)
        val drop1 = MapGroundItem(
            "g1", 1, 4, 5,
            ItemStack(1, 1, rolls = ItemRolls("rare", 3, listOf(RolledAffix("cruel", AffixStat.ATTACK_DAMAGE, value = 4))))
        )
        val drop2 = MapGroundItem("g2", 1, 4, 5, ItemStack(2, 25))

        // Without Alt (showAll = false), plain/unhighlighted drops are hidden
        val hidden = GroundLootLabels.layout(listOf(drop1, drop2), itemsById, lootRules, 10, 32, showAll = false, isHighlighted = { false })
        assertTrue(hidden.isEmpty())

        // With Alt (showAll = true), both drops appear stacked vertically with rarity/gold colors
        val shown = GroundLootLabels.layout(listOf(drop1, drop2), itemsById, lootRules, 10, 32, showAll = true, isHighlighted = { false })
        assertEquals(2, shown.size)
        assertEquals("Cruel Sword", shown[0].text)
        assertEquals("#facc15", shown[0].colorHex)
        assertEquals("25 Gold", shown[1].text)
        assertEquals("#FACC15", shown[1].colorHex)
        assertTrue(shown[1].baselineY > shown[0].baselineY, "second item on same tile stacks upward")

        // Hit-testing the second label's box returns drop2
        val hit = GroundLootLabels.hitTest(shown[1].centerX, shown[1].boxY + 2f, 0, 0, shown, listOf(drop1, drop2))
        assertEquals("g2", hit?.instanceId)

        // Hit-testing an empty spot misses
        assertNull(GroundLootLabels.hitTest(0f, 0f, 0, 0, shown, listOf(drop1, drop2)))
    }

    @Test
    fun `spell form and info support GROUND_AOE and pierce`() {
        val spell = SpellDefinition(
            id = 5, name = "Meteor", targetType = SpellTargetType.GROUND_AOE,
            rangeTiles = 6, aoeRadiusTiles = 2, basePower = 50, pierce = true
        )
        val ctx = FormContext(catalog = WorldContentCatalogDto())
        val values = SpellForm.valuesOf(spell, ctx)
        assertEquals("GROUND_AOE", values["target"])
        assertEquals("true", values["pierce"])
        val roundTripped = SpellForm.apply(SpellForm.create(5, ctx), values, ctx)
        assertEquals(SpellTargetType.GROUND_AOE, roundTripped.targetType)
        assertTrue(roundTripped.pierce)
        assertTrue(SpellForm.extraProblems(values, ctx).isEmpty())

        assertTrue(SpellInfo.kind(spell).contains("Ground area"))
        assertNotNull(SpellInfo.kind(spell.copy(isHealing = true)))
    }
}
