package com.morgana.client

import com.morgana.client.ui.BestiaryModel
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.content.NpcDropEntry
import com.morgana.domain.game.BestiaryDefinition
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BestiaryModelTest {
    private val rules = BestiaryDefinition(statsAfterKills = 5, dropsAfterKills = 10)
    private val wolf = NpcDefinition(id = 1, name = "Wolf", level = 4, vitals = mapOf("hp" to 80), attackDamage = 9, attributes = mapOf("str" to 7),
        drops = listOf(NpcDropEntry(itemId = 3, minAmount = 1, maxAmount = 1, chanceBasisPoints = 2500)))
    private val bat = NpcDefinition(id = 2, name = "Bat", level = 1)
    private val items = mapOf(3 to ItemDefinition(id = 3, name = "Pelt", type = ItemType.MATERIAL))

    @Test
    fun `rows list only killed kinds the catalog knows, by name, at the right stage`() {
        val rows = BestiaryModel.rows(mapOf(1 to 12, 2 to 1, 3 to 4, 9 to 0), listOf(wolf, bat), rules)
        assertEquals(listOf("Bat", "Wolf"), rows.map { it.name }, "unknown and zero-kill kinds are left out")
        assertEquals(BestiaryModel.Stage.SEEN, rows[0].stage)
        assertEquals(BestiaryModel.Stage.DROPS, rows[1].stage)
        assertEquals(BestiaryModel.Stage.STATS, BestiaryModel.stageFor(5, rules))
        assertEquals(BestiaryModel.Stage.SEEN, BestiaryModel.stageFor(4, rules))
    }

    @Test
    fun `more kills reveal more, and the hints say how many are left`() {
        val seen = BestiaryModel.detail(BestiaryModel.Row(1, "Wolf", 4, 2, BestiaryModel.Stage.SEEN), wolf, items, emptyMap(), rules)
        assertTrue(seen.any { "3 more to learn how it fights" in it }, seen.toString())
        assertTrue(seen.none { "Drops" in it })
        val stats = BestiaryModel.detail(BestiaryModel.Row(1, "Wolf", 4, 6, BestiaryModel.Stage.STATS), wolf, items, mapOf("str" to "STR"), rules)
        assertTrue("Life 80, damage 9" in stats)
        assertTrue("STR 7" in stats)
        assertTrue(stats.any { "4 more to learn what it drops" in it })
        val all = BestiaryModel.detail(BestiaryModel.Row(1, "Wolf", 4, 10, BestiaryModel.Stage.DROPS), wolf, items, emptyMap(), rules)
        assertTrue("Drops Pelt (25%)" in all)
        assertEquals("Defeated 10 times", all[1])
    }
}
