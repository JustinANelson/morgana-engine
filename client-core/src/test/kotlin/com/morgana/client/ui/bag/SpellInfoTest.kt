package com.morgana.client.ui.bag

import com.morgana.domain.content.SpellDefinition
import com.morgana.domain.content.SpellTargetType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpellInfoTest {
    private val names = StatNames(vitals = mapOf("mp" to "MP"))
    private val fireball = SpellDefinition(
        id = 1, name = "Fireball", description = "Hurls flame.", costs = mapOf("mp" to 10),
        castTimeTicks = 30, cooldownTicks = 40, rangeTiles = 6, basePower = 20, levelReq = 3
    )

    @Test
    fun `the quickbar line gives cost, cooldown and range on one line`() {
        assertEquals("10 MP, cooldown 2.0 s, range 6 tiles", SpellInfo.quickLine(fireball, names))
        assertEquals(
            "10 MP, cooldown 2.0 s, range 6 tiles, ready in 1.3 s",
            SpellInfo.quickLine(fireball, names, cooldownLeftSeconds = 1.34f)
        )
        val aura = SpellDefinition(id = 2, name = "Aura", targetType = SpellTargetType.SELF, cooldownTicks = 100)
        assertEquals("free, cooldown 5.0 s", SpellInfo.quickLine(aura, names))
    }

    @Test
    fun `a damage spell lists its effect, cost, timing, reach and requirements`() {
        val tip = SpellInfo.tip(fireball, names, WearerInfo(level = 5, attributes = emptyMap(), classId = 1), vitals = mapOf("mp" to 25))
        assertEquals("Fireball", tip.title)
        assertEquals("Single target spell", tip.kind)
        assertEquals("Hurls flame.", tip.description)
        val lines = tip.lines
        assertEquals(InfoLine("Damage 20, grows with your stats", Tone.NEUTRAL), lines[0])
        assertEquals(InfoLine("Costs 10 MP", Tone.NEUTRAL), lines[1])
        assertEquals(InfoLine("Cast time 1.5 s", Tone.MUTED), lines[2])
        assertEquals(InfoLine("Cooldown 2.0 s", Tone.MUTED), lines[3])
        assertEquals(InfoLine("Range 6 tiles", Tone.MUTED), lines[4])
        assertEquals(InfoLine("Needs level 3", Tone.MUTED), lines[5], "a met requirement is quiet")
    }

    @Test
    fun `unmet cost and level are flagged and a cooling spell says when it is ready`() {
        val tip = SpellInfo.tip(fireball, names, WearerInfo(level = 1, attributes = emptyMap(), classId = 1), vitals = mapOf("mp" to 4), cooldownLeftSeconds = 1.26f)
        assertTrue(tip.lines.contains(InfoLine("Costs 10 MP", Tone.BAD)))
        assertTrue(tip.lines.contains(InfoLine("Needs level 3", Tone.BAD)))
        assertEquals(InfoLine("Ready in 1.2 s", Tone.BAD), tip.lines.last())
    }

    @Test
    fun `heals, self casts and warps read in plain words`() {
        val heal = SpellDefinition(id = 2, name = "Mend", targetType = SpellTargetType.SELF, isHealing = true, basePower = 30, scales = false, castTimeTicks = 0)
        val tip = SpellInfo.tip(heal, names, null)
        assertEquals("Healing spell, self", tip.kind)
        assertEquals(InfoLine("Heals 30", Tone.GOOD), tip.lines[0])
        assertEquals(InfoLine("Costs nothing", Tone.MUTED), tip.lines[1])
        assertEquals(InfoLine("Instant cast", Tone.MUTED), tip.lines[2])
        assertFalse(tip.lines.any { it.text.startsWith("Range") }, "a self cast has no range")

        val warp = SpellDefinition(id = 3, name = "Recall", targetType = SpellTargetType.WARP)
        val warpTip = SpellInfo.tip(warp, names, null)
        assertEquals("Teleport spell", warpTip.kind)
        assertEquals(InfoLine("Teleports you to another place", Tone.NEUTRAL), warpTip.lines[0])
    }

    @Test
    fun `an area spell states its radius`() {
        val nova = SpellDefinition(id = 4, name = "Nova", targetType = SpellTargetType.AOE_SELF_CENTERED, aoeRadiusTiles = 1)
        assertTrue(SpellInfo.tip(nova, names, null).lines.contains(InfoLine("Hits everything within 1 tile", Tone.MUTED)))
    }

    @Test
    fun `skill terminology labels kind and level as skill instead of spell`() {
        val scatter = SpellDefinition(
            id = 7,
            name = "Scatter",
            targetType = SpellTargetType.AOE_TARGET_CENTERED,
            aoeRadiusTiles = 4,
            maxTargets = 5,
            baseDamagePercent = 60,
            baseDamagePercentPerLevel = 10,
            maxSpellLevel = 9
        )
        val skillTip = SpellInfo.tip(scatter, names, null, spellLevel = 2, terminology = com.morgana.domain.content.AbilityTerminology.SKILLS)
        assertEquals("Area skill at a target", skillTip.kind)
        assertTrue(skillTip.lines.contains(InfoLine("Skill Lv 2 / 9", Tone.GOOD)))

        val spellTip = SpellInfo.tip(scatter, names, null, spellLevel = 2, terminology = com.morgana.domain.content.AbilityTerminology.SPELLS)
        assertEquals("Area spell at a target", spellTip.kind)
        assertTrue(spellTip.lines.contains(InfoLine("Spell Lv 2 / 9", Tone.GOOD)))
    }
}
