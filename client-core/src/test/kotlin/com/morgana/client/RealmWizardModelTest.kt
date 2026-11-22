package com.morgana.client

import com.morgana.client.realms.RealmWizardModel
import com.morgana.domain.sim.OptionalModule
import com.morgana.domain.sim.SystemGroups
import com.morgana.domain.sim.SystemTags
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RealmWizardModelTest {

    private val templates = listOf(
        RealmTemplateInfo("blank", "Blank Game", "small"),
        RealmTemplateInfo("dungeon", "Dungeon Crawl", "big")
    )

    private fun model() = RealmWizardModel(templates)

    @Test
    fun `the first step needs a usable name before moving on`() {
        val m = model()
        assertEquals(RealmWizardModel.Step.BASICS, m.step)
        assertFalse(m.canAdvance)
        assertNotNull(m.nameProblem())
        assertFalse(m.next())

        m.name = "x".repeat(41)
        assertTrue(m.nameProblem()!!.contains("at most"))
        m.name = "  Camp  "
        assertNull(m.nameProblem())
        assertTrue(m.next())
        assertEquals(RealmWizardModel.Step.FEATURES, m.step)
    }

    @Test
    fun `steps move forward and back and stop at the ends`() {
        val m = model().also { it.name = "Camp" }
        assertFalse(m.back())
        assertTrue(m.next())
        assertTrue(m.next())
        assertTrue(m.isLastStep)
        assertFalse(m.next())
        assertTrue(m.back())
        assertEquals(2, m.stepNumber)
        assertEquals(3, m.stepCount)
    }

    @Test
    fun `features start fully on and presets and checkboxes change them`() {
        val m = model()
        assertTrue(OptionalModule.entries.all { m.isEnabled(it) })
        assertEquals("full", m.activePreset()?.id)

        m.applyPreset(RealmWizardModel.PRESETS.first { it.id == "solo" })
        assertFalse(m.isEnabled(OptionalModule.PARTIES))
        assertFalse(m.isEnabled(OptionalModule.GUILDS))
        assertFalse(m.isEnabled(OptionalModule.TRADING))
        assertTrue(m.isEnabled(OptionalModule.CRAFTING))
        assertEquals("solo", m.activePreset()?.id)

        m.setEnabled(OptionalModule.CRAFTING, false)
        assertNull(m.activePreset(), "a custom mix matches no preset")
        m.setEnabled(OptionalModule.CRAFTING, true)
        assertEquals("solo", m.activePreset()?.id)
    }

    @Test
    fun `the spec carries the trimmed name, template and switched-off module keys`() {
        val m = model()
        m.name = "  My Realm "
        m.templateId = "dungeon"
        m.setEnabled(OptionalModule.TRADING, false)
        assertEquals(RealmSpec("My Realm", "dungeon", setOf("trading")), m.toSpec())
    }

    @Test
    fun `the review summary lists what was chosen`() {
        val m = model()
        m.name = "My Realm"
        assertTrue(m.summaryLines().any { it.contains("everything on") })
        m.setEnabled(OptionalModule.GUILDS, false)
        val lines = m.summaryLines()
        assertTrue(lines.any { it.startsWith("Name: My Realm") })
        assertTrue(lines.any { it.startsWith("Starting point: Blank Game") })
        assertTrue(lines.any { it.startsWith("Switched off:") && it.contains("Guilds") })

        m.applyPreset(RealmWizardModel.PRESETS.first { it.id == "modern_sprites" })
        assertTrue(m.modernSprites)
        assertTrue(m.toSpec().modernSprites)
        assertTrue(m.summaryLines().any { it.contains("32 x 64") })
    }

    @Test
    fun `there is one genre starter per genre and applying one keeps its systems and switches the rest off`() {
        assertEquals(SystemTags.TITLES.keys.map { "genre:$it" }, RealmWizardModel.GENRES.map { it.id })
        val roguelike = RealmWizardModel.GENRES.first { it.genre == SystemTags.ROGUELIKE }
        assertEquals("Roguelike and dungeon crawler", roguelike.title)
        assertTrue("Everything else is switched off." in roguelike.description && "Parties" in roguelike.description)

        val m = model()
        m.applyPreset(roguelike)
        assertTrue(m.isEnabled(OptionalModule.PARTIES) && m.isEnabled(OptionalModule.INSTANCING) && m.isEnabled(OptionalModule.BOSS_ENCOUNTERS))
        assertFalse(m.isEnabled(OptionalModule.GUILDS) || m.isEnabled(OptionalModule.TRADING) || m.isEnabled(OptionalModule.MARKET))
        assertEquals(roguelike.id, m.activePreset()?.id)
        assertEquals(SystemGroups.disabledKeysFor(SystemTags.ROGUELIKE), m.toSpec().disabledModuleKeys)
        assertTrue(m.summaryLines().any { it == "Genre: Roguelike and dungeon crawler" })

        m.setEnabled(OptionalModule.ARENA, true)
        assertNull(m.activePreset(), "adjusting one system leaves the genre starter behind")
        assertTrue(m.summaryLines().none { it.startsWith("Genre:") })
    }

    @Test
    fun `systems are grouped by genre and a whole group can be switched at once`() {
        val m = model()
        assertEquals(SystemGroups.grouped(), m.groups)
        val rpg = m.groups.first { it.tag == SystemTags.RPG }
        assertEquals(true, m.groupState(rpg))
        m.setEnabled(rpg.systems.first(), false)
        assertEquals(null, m.groupState(rpg), "a mix")
        m.setGroupEnabled(rpg, false)
        assertEquals(false, m.groupState(rpg))
        assertTrue(rpg.systems.none { m.isEnabled(it) })
        assertTrue(m.groups.filter { it != rpg }.all { g -> g.systems.all { m.isEnabled(it) } }, "other groups are untouched")
        m.setGroupEnabled(rpg, true)
        assertEquals("full", m.activePreset()?.id)
        // a wizard that lists only some systems groups only those
        val few = RealmWizardModel(templates, listOf(OptionalModule.MAIL, OptionalModule.THREAT))
        assertEquals(setOf(OptionalModule.MAIL, OptionalModule.THREAT), few.groups.flatMap { it.systems }.toSet())
    }

    private val withDefaults = listOf(
        RealmTemplateInfo("blank", "Blank Game", "small"),
        RealmTemplateInfo("delve", "Delve", "a roguelike", setOf("arena", "guildWar", "nonsense"))
    )

    @Test
    fun `a template starts the feature list from the systems it switches off and they can be switched back on`() {
        val m = RealmWizardModel(withDefaults)
        assertTrue(m.disabledModuleKeys.isEmpty(), "the blank game switches nothing off")
        m.name = "Delve"
        m.templateId = "delve"
        assertEquals(setOf("arena", "guildWar"), m.disabledModuleKeys, "unknown keys in a template are ignored")
        assertFalse(m.isEnabled(OptionalModule.ARENA))
        assertEquals(RealmSpec("Delve", "delve", setOf("arena", "guildWar")), m.toSpec())

        m.setEnabled(OptionalModule.ARENA, true)
        val spec = m.toSpec()
        assertEquals(setOf("guildWar"), spec.disabledModuleKeys)
        assertEquals(setOf("arena"), spec.enabledModuleKeys, "the spec says which of the template's switches were turned back on")
    }

    @Test
    fun `choosing another template resets the features to that template, and choosing the same one keeps them`() {
        val m = RealmWizardModel(withDefaults)
        m.setEnabled(OptionalModule.GUILDS, false)
        m.templateId = "blank"
        assertEquals(setOf("guilds"), m.disabledModuleKeys, "the same template again changes nothing")
        m.templateId = "delve"
        assertEquals(setOf("arena", "guildWar"), m.disabledModuleKeys)
        m.templateId = "blank"
        assertTrue(m.disabledModuleKeys.isEmpty())
    }

    @Test
    fun `a wizard that starts on a template with defaults shows them straight away`() {
        val m = RealmWizardModel(listOf(RealmTemplateInfo("delve", "Delve", "d", setOf("pets"))))
        assertEquals(setOf("pets"), m.disabledModuleKeys)
        assertEquals(emptySet<String>(), m.toSpec().enabledModuleKeys)
    }

    @Test
    fun `with no templates available the wizard cannot proceed`() {
        val m = RealmWizardModel(emptyList())
        m.name = "Camp"
        assertEquals("Choose a starting point.", m.blockingProblem())
    }
}
