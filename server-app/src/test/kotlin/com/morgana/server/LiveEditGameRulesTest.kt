package com.morgana.server

import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Live edits are validated against the game being served, not the built-in Classic rules. A game with its own attribute ids
 * (the scaffolded test game has no "def") once had every NPC and item save rejected because it was judged as Classic content.
 */
class LiveEditGameRulesTest {

    private class Session(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent.add(packet) }
        override fun close(reason: String) {}
        fun messages() = sent.filterIsInstance<ServerPacket.ChatBroadcast>().map { it.message }
    }

    /** A throwaway game in the temp dir: live edits are saved into the game's own data folder. */
    private fun coordinator(dir: Path): MorganaWorldCoordinator {
        val gameDir = TestGames.blank(dir)
        return MorganaWorldCoordinator(MorganaDataStore(rootDir = dir, gameDir = gameDir), ownerPolicy = OwnerPolicy(dir) { "Owner" })
    }

    /** The account named Owner is configured as the realm owner, who may edit content. */
    private fun owner(c: MorganaWorldCoordinator): Session {
        val s = Session("Owner")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate("Owner", "Password123!", requestedClassId = 1, protocolVersion = PROTOCOL_VERSION))
        return s
    }

    @Test
    fun `an NPC in a game with its own attributes can be saved`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = owner(c)
        val npc = c.simulation.npcs.getValue(1)
        c.handleClientPacket(s, ClientPacket.SaveNpcDefinition(npc.copy(title = npc.title + "!")))

        assertTrue(s.messages().none { it.startsWith("Save rejected") }, s.messages().toString())
        assertEquals(npc.title + "!", c.simulation.npcs.getValue(1).title)
        val file = dir.resolve("game/data/npcs/npc_001.json")
        assertTrue(Files.readString(file).contains(npc.title + "!"), "the edit is written to the game's data folder")
    }

    @Test
    fun `an item in a game with its own attributes can be saved`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = owner(c)
        val item = c.simulation.items.values.first()
        c.handleClientPacket(s, ClientPacket.SaveItemDefinition(item.copy(price = item.price + 1)))

        assertTrue(s.messages().none { it.startsWith("Save rejected") }, s.messages().toString())
        assertEquals(item.price + 1, c.simulation.items.getValue(item.id).price)
    }

    @Test
    fun `a class can be saved live and a class with an unknown attribute is rejected`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = owner(c)
        val cls = c.simulation.classes.getValue(1)
        c.handleClientPacket(s, ClientPacket.SaveClassDefinition(cls.copy(description = "Edited in F1")))
        assertTrue(s.messages().none { it.startsWith("Save rejected") }, s.messages().toString())
        assertEquals("Edited in F1", c.simulation.classes.getValue(1).description)
        assertTrue(Files.readString(dir.resolve("game/data/classes/class_001.json")).contains("Edited in F1"))

        c.handleClientPacket(s, ClientPacket.SaveClassDefinition(cls.copy(baseAttributes = mapOf("nonsense" to 3))))
        assertTrue(s.messages().any { it.startsWith("Save rejected for classes #1") }, s.messages().toString())
        assertEquals("Edited in F1", c.simulation.classes.getValue(1).description)
    }

    @Test
    fun `game rules save to game json for the owner, and a broken or unauthorised save is refused`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = owner(c)
        val def = c.simulation.game.definition
        val file = dir.resolve("game/game.json")

        c.handleClientPacket(s, ClientPacket.SaveGameDefinition(def.copy(name = "Renamed in F1", inventorySlots = 50)))
        assertTrue(s.messages().any { it.startsWith("Game rules saved") }, s.messages().toString())
        val written = com.morgana.persistence.GameDefinitionLoader.load(dir.resolve("game")).game.definition
        assertEquals("Renamed in F1", written.name)
        assertEquals(50, written.inventorySlots)
        assertEquals(def.name, c.simulation.game.definition.name, "the running server keeps its rules until it restarts")
        assertTrue(Files.exists(file))

        c.handleClientPacket(s, ClientPacket.SaveGameDefinition(def.copy(vitals = emptyList())))
        assertTrue(s.messages().any { it.startsWith("Save rejected for game rules") }, s.messages().toString())
        assertEquals("Renamed in F1", com.morgana.persistence.GameDefinitionLoader.load(dir.resolve("game")).game.definition.name)

        val other = Session("Pat")
        c.onSessionConnected(other)
        c.handleClientPacket(other, ClientPacket.Authenticate("Pat", "Password123!", requestedClassId = 1, protocolVersion = PROTOCOL_VERSION))
        c.handleClientPacket(other, ClientPacket.SaveGameDefinition(def.copy(name = "Hijacked")))
        assertTrue(other.messages().any { it.contains("needs server maintenance rights") }, other.messages().toString())
        assertEquals("Renamed in F1", com.morgana.persistence.GameDefinitionLoader.load(dir.resolve("game")).game.definition.name)
    }

    @Test
    fun `action skills and weapon sets save live, and a weapon set naming a missing skill is rejected`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = owner(c)
        val skill = com.morgana.domain.content.ActionSkillDefinition(id = 1, name = "Slash", hitboxes = listOf(com.morgana.domain.content.SkillHitbox(2, 3, com.morgana.domain.content.HitboxShape.CIRCLE, radiusPixels = 32)))
        c.handleClientPacket(s, ClientPacket.SaveActionSkillDefinition(skill))
        assertTrue(s.messages().none { it.startsWith("Save rejected") }, s.messages().toString())
        assertEquals("Slash", c.simulation.actionSkills.getValue(1).name)
        assertTrue(Files.exists(dir.resolve("game/data/action_skills/action_skill_001.json")))

        val set = com.morgana.domain.content.WeaponSetDefinition(id = 1, name = "Blade", skillIds = listOf(1), entries = listOf(com.morgana.domain.content.ComboEntry("LMB", 1)))
        c.handleClientPacket(s, ClientPacket.SaveWeaponSetDefinition(set))
        assertEquals("Blade", c.simulation.weaponSets.getValue(1).name)
        assertTrue(c.buildCatalogDto().let { it.actionSkills.size == 1 && it.weaponSets.size == 1 }, "the editors receive both collections")

        c.handleClientPacket(s, ClientPacket.SaveWeaponSetDefinition(set.copy(id = 2, skillIds = listOf(9), entries = listOf(com.morgana.domain.content.ComboEntry("LMB", 9)))))
        assertTrue(s.messages().any { it.startsWith("Save rejected for weapon_sets #2") }, s.messages().toString())
        assertTrue(2 !in c.simulation.weaponSets)
    }

    @Test
    fun `optional systems switch live and are saved to server json, for the owner only`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = owner(c)
        assertTrue(c.simulation.modules.isEnabled(com.morgana.domain.sim.OptionalModule.PARTIES))
        c.handleClientPacket(s, ClientPacket.SetModules(listOf("parties", "projectiles")))
        assertTrue(s.messages().any { it.startsWith("Systems saved") }, s.messages().toString())
        assertTrue(!c.simulation.modules.isEnabled(com.morgana.domain.sim.OptionalModule.PARTIES))
        assertTrue(c.simulation.modules.isEnabled(com.morgana.domain.sim.OptionalModule.INSTANCING))
        val saved = com.morgana.persistence.ServerConfig.load(dir)
        assertEquals(false, saved.modules["parties"])
        assertEquals(true, saved.modules["instancing"])

        c.handleClientPacket(s, ClientPacket.SetModules(listOf("nonsense")))
        assertTrue(s.messages().any { it.contains("unknown system") }, s.messages().toString())
        assertTrue(!c.simulation.modules.isEnabled(com.morgana.domain.sim.OptionalModule.PARTIES), "a rejected change leaves the last one")

        val other = Session("Pat")
        c.onSessionConnected(other)
        c.handleClientPacket(other, ClientPacket.Authenticate("Pat", "Password123!", requestedClassId = 1, protocolVersion = PROTOCOL_VERSION))
        c.handleClientPacket(other, ClientPacket.SetModules(emptyList()))
        assertTrue(other.messages().any { it.contains("needs server maintenance rights") }, other.messages().toString())
        assertTrue(!c.simulation.modules.isEnabled(com.morgana.domain.sim.OptionalModule.PARTIES))
    }

    @Test
    fun `a genuinely invalid edit is still rejected with the reason`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = owner(c)
        val npc = c.simulation.npcs.getValue(1)
        c.handleClientPacket(s, ClientPacket.SaveNpcDefinition(npc.copy(attributes = mapOf("nonsense" to 3))))

        assertTrue(s.messages().any { it.startsWith("Save rejected for npcs #1") && it.contains("nonsense") }, s.messages().toString())
        assertTrue(c.simulation.npcs.getValue(1).attributes.containsKey("nonsense").not())
    }

    @Test
    fun `testing an event runs its steps without saving them and rejects bad references`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = owner(c)
        val unsavedId = 999
        val steps = listOf(
            com.morgana.domain.event.EventCommand.ShowDialogue(
                "Tester",
                "Unsaved preview?",
                choices = listOf(com.morgana.domain.event.DialogueChoice("Run", listOf(com.morgana.domain.event.EventCommand.SetSwitch("preview_ok", true))))
            ),
            com.morgana.domain.event.EventCommand.ShowMessage("Preview complete")
        )
        c.handleClientPacket(s, ClientPacket.TestEventCommands(unsavedId, steps))
        assertTrue(c.simulation.events[unsavedId] == null, "testing does not save the event")
        assertTrue(s.sent.any { it is ServerPacket.OpenUiWindow && it.eventId == unsavedId && it.dialogueText == "Unsaved preview?" })

        c.handleClientPacket(s, ClientPacket.SelectDialogueChoice(unsavedId, 0))
        val charId = s.characterId!!
        assertEquals(true, c.simulation.players.getValue(charId).switches["preview_ok"])
        assertTrue(s.messages().any { it == "Preview complete" })

        s.sent.clear()
        c.handleClientPacket(
            s,
            ClientPacket.TestEventCommands(unsavedId, listOf(com.morgana.domain.event.EventCommand.GiveItem(9999, 1)))
        )
        assertTrue(s.messages().any { it.startsWith("Test rejected for events #$unsavedId") }, s.messages().toString())
    }

    @Test
    fun `common events save live, appear in catalog, and reject missing references`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = owner(c)
        val ce = com.morgana.domain.event.CommonEventDefinition(
            id = 1,
            name = "Town Bell",
            description = "Flips a world switch",
            commands = listOf(com.morgana.domain.event.EventCommand.SetSwitch("bell_rung", true, com.morgana.domain.event.VariableScope.WORLD))
        )
        c.handleClientPacket(s, ClientPacket.SaveCommonEventDefinition(ce))
        assertEquals(ce, c.simulation.commonEvents[1])
        assertTrue(s.sent.any { it is ServerPacket.ContentCatalogUpdated && ce in it.catalog.commonEvents })

        s.sent.clear()
        val bad = com.morgana.domain.event.CommonEventDefinition(
            id = 2,
            name = "Broken",
            commands = listOf(com.morgana.domain.event.EventCommand.GiveItem(9999, 1))
        )
        c.handleClientPacket(s, ClientPacket.SaveCommonEventDefinition(bad))
        assertTrue(c.simulation.commonEvents[2] == null)
        assertTrue(s.messages().any { it.startsWith("Save rejected for common_events #2") }, s.messages().toString())
    }
}


