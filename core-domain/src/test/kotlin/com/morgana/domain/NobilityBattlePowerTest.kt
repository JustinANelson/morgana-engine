package com.morgana.domain

import com.morgana.domain.entity.CharacterState
import com.morgana.domain.game.BattlePowerDefinition
import com.morgana.domain.game.MorganaClassic
import com.morgana.domain.game.NobilityDefinition
import com.morgana.domain.game.NobilityRank
import com.morgana.domain.item.EquipmentLoadout
import com.morgana.domain.item.EquipmentSlot
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemRolls
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class NobilityBattlePowerTest {
    private val nobility = NobilityDefinition(
        ranks = listOf(NobilityRank("baron", "Baron", 1000, 20), NobilityRank("knight", "Knight", 100, 5))
    )
    private val rules = BattlePowerDefinition(perLevel = 2, perItemLevel = 1, perPlus = 3, rarityPoints = mapOf("magic" to 7))
    private val game = MorganaClassic.COMPILED.definition.copy(nobility = nobility, battlePower = rules)
    private val sword = ItemDefinition(id = 1, name = "Sword", type = ItemType.WEAPON, levelReq = 10)

    private fun hero(donated: Int? = null, equipped: Map<EquipmentSlot, ItemStack> = emptyMap()) = CharacterState(
        id = "h", accountId = "a", name = "H", level = 5,
        variables = if (donated == null) emptyMap() else mapOf("nobility_donated" to donated),
        equipment = EquipmentLoadout(equipped)
    )

    @Test
    fun `rank is the highest threshold reached regardless of list order`() {
        assertNull(nobility.rankOf(hero(99)))
        assertEquals("knight", nobility.rankOf(hero(100))?.id)
        assertEquals("knight", nobility.rankOf(hero(999))?.id)
        assertEquals("baron", nobility.rankOf(hero(5000))?.id)
        assertEquals("baron", nobility.nextRank(100)?.id)
        assertNull(nobility.nextRank(1000))
    }

    @Test
    fun `battle power sums level gear plus level rarity and nobility`() {
        val items = mapOf(1 to sword)
        assertEquals(10, rules.of(hero(), game, items))
        val gear = mapOf(EquipmentSlot.WEAPON to ItemStack(1, plus = 2, rolls = ItemRolls("magic")))
        // level 5*2 + item level 10 + plus 2*3 + magic 7 + knight 5
        assertEquals(10 + 10 + 6 + 7 + 5, rules.of(hero(150, gear), game, items))
    }

    @Test
    fun `disabled battle power is zero and disabled nobility gives no rank`() {
        assertEquals(0, rules.copy(enabled = false).of(hero(), game, emptyMap()))
        assertNull(nobility.copy(enabled = false).rankOf(hero(5000)))
    }
}
