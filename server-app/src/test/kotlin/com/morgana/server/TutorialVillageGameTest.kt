package com.morgana.server

import com.morgana.domain.event.QuestStatus
import com.morgana.domain.spatial.Direction
import com.morgana.domain.spatial.WorldPosition
import com.morgana.persistence.ContentLoader
import com.morgana.persistence.GameDefinitionLoader
import com.morgana.persistence.MorganaDataStore
import com.morgana.protocol.ChatChannel
import com.morgana.protocol.ClientPacket
import com.morgana.protocol.PROTOCOL_VERSION
import com.morgana.protocol.ProtocolCapabilities
import com.morgana.protocol.ServerPacket
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

/**
 * `games/tutorial-village` is the finished game of the first-game guide ([docs/GETTING_STARTED.md]): it must stay valid and
 * stay playable from start to finish, or the guide promises something that is not there. Runs on a copy in a temp folder.
 */
class TutorialVillageGameTest {
    private val game: Path = listOf(Path.of("games/tutorial-village"), Path.of("../games/tutorial-village")).first { it.exists() }

    private class FakeSession(override val sessionId: String) : ClientSession {
        override var characterId: String? = null
        val sent = mutableListOf<ServerPacket>()
        override fun send(packet: ServerPacket) { sent += packet }
        override fun close(reason: String) {}
        fun dialogues() = sent.filterIsInstance<ServerPacket.OpenUiWindow>().filter { it.dialogueText.isNotEmpty() }
        fun system() = sent.filterIsInstance<ServerPacket.ChatBroadcast>().filter { it.channel == ChatChannel.SYSTEM }.map { it.message }
    }

    private fun copyGame(to: Path): Path {
        Files.walk(game).use { paths ->
            paths.forEach { src ->
                val dst = to.resolve(game.relativize(src).toString())
                if (Files.isDirectory(src)) Files.createDirectories(dst) else Files.copy(src, dst)
            }
        }
        return to
    }

    private fun coordinator(dir: Path) = MorganaWorldCoordinator(MorganaDataStore(copyGame(dir)), ownerPolicy = OwnerPolicy(dir) { null })

    private fun join(c: MorganaWorldCoordinator, name: String): FakeSession {
        val s = FakeSession("s-$name")
        c.onSessionConnected(s)
        c.handleClientPacket(s, ClientPacket.Authenticate(name, "pw", true, 1, protocolVersion = PROTOCOL_VERSION,
            capabilities = ProtocolCapabilities.DEFAULT_CLIENT_CAPABILITIES + ProtocolCapabilities.DIALOGUE_FLOW))
        return s
    }

    private fun MorganaWorldCoordinator.me(s: FakeSession) = simulation.players.getValue(s.characterId!!)

    private fun MorganaWorldCoordinator.stand(s: FakeSession, x: Int, y: Int, facing: Direction) {
        simulation.players[me(s).id] = me(s).copy(position = WorldPosition.fromTile(1, x, y, facing))
    }

    /** Talks to Mara from the tile south of her, and answers the open question with [choice] when there is one. */
    private fun MorganaWorldCoordinator.talkToMara(s: FakeSession, choice: Int? = null) {
        val mara = simulation.mapNpcs.getValue(1).first { it.npcId == 1 }
        stand(s, mara.tileX, mara.tileY + 1, Direction.UP)
        handleClientPacket(s, ClientPacket.InteractFacing)
        if (choice != null) handleClientPacket(s, ClientPacket.SelectDialogueChoice(1, choice))
    }

    @Test
    fun `the game is valid and has what the guide says it has`() {
        val loaded = ContentLoader.load(game.resolve("data"))
        val definition = GameDefinitionLoader.load(game).game.definition
        val report = loaded.fullReport(definition)
        assertTrue(report.isValid, report.errors.joinToString("\n"))
        assertEquals("tutorial_village", definition.id)
        val c = loaded.catalog
        assertEquals("Greenhollow", c.maps.getValue(1).name)
        assertEquals(listOf("Mara", "Garden Pest"), c.npcs.values.sortedBy { it.id }.map { it.name })
        assertEquals("Weed the Garden", c.quests.getValue(1).name)
        assertEquals("Gardener's Charm", c.items.getValue(2).name)
        assertEquals(3, c.quests.getValue(1).objectives.single().requiredCount)
        assertEquals(1, c.npcs.getValue(1).eventId, "Mara's conversation is her event")
        assertEquals(3, c.events.getValue(1).pages.size, "one page for each stage of the quest")
        assertTrue(c.maps.getValue(1).npcSpawns.count { it.npcId == 2 } >= 3, "at least as many pests as the quest asks for")
    }

    @Test
    fun `the quest can be taken, done and handed in, and the reward arrives`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = join(c, "Hero")
        val heroId = s.characterId!!

        // 1. Mara asks for help and offers the choice; "I'll help" starts the quest.
        c.talkToMara(s)
        val ask = s.dialogues().last()
        assertTrue(ask.dialogueText.contains("Garden Pests"), ask.dialogueText)
        assertEquals(listOf("I'll help", "Not right now"), ask.choiceLabels)
        c.handleClientPacket(s, ClientPacket.SelectDialogueChoice(1, 0))
        assertEquals(QuestStatus.IN_PROGRESS, c.me(s).quests.getValue(1).status)

        // 2. Handing in too early: she says some pests are left and the quest stays open.
        c.talkToMara(s)
        assertTrue(s.dialogues().last().dialogueText.contains("Still some pests left"), s.dialogues().last().dialogueText)
        assertEquals(QuestStatus.IN_PROGRESS, c.me(s).quests.getValue(1).status)

        // 3. Fight three pests with the starting sword.
        c.handleClientPacket(s, ClientPacket.UseInventorySlot(0))
        var ticks = 0
        while ((c.me(s).quests.getValue(1).objectiveCounts.firstOrNull() ?: 0) < 3 && ticks < 2000) {
            val pest = c.simulation.mapNpcs.getValue(1).firstOrNull { it.npcId == 2 && it.isAlive }
            if (pest != null) {
                c.stand(s, pest.tileX - 1, pest.tileY, Direction.RIGHT)
                c.handleClientPacket(s, ClientPacket.Attack)
            }
            repeat(5) { c.stepServerTick() }
            ticks += 5
            // keep the hero alive: the pests nibble
            val hero = c.me(s)
            c.simulation.players[heroId] = hero.copy(vitals = hero.vitals.mapValues { it.value.copy(cur = it.value.max) })
        }
        assertEquals(3, c.me(s).quests.getValue(1).objectiveCounts.first(), "three pests chased away after $ticks ticks")
        assertFalse(c.me(s).inventory.hasItem(2), "no charm before handing in")

        // 4. Handing in: the quest completes and the charm is the reward.
        val xpBefore = c.me(s).xp
        c.talkToMara(s)
        assertTrue(s.dialogues().last().dialogueText.contains("You did it"), s.dialogues().last().dialogueText)
        assertEquals(QuestStatus.COMPLETED, c.me(s).quests.getValue(1).status)
        assertEquals(1, c.me(s).inventory.totalCountOf(2), "the Gardener's Charm")
        assertTrue(c.me(s).xp >= xpBefore + 100 || c.me(s).level > 1, "the quest's experience was paid")

        // 5. Afterwards she only thanks you.
        c.talkToMara(s)
        assertTrue(s.dialogues().last().dialogueText.contains("never looked better"), s.dialogues().last().dialogueText)
    }

    @Test
    fun `saying not right now leaves the quest unstarted`(@TempDir dir: Path) {
        val c = coordinator(dir)
        val s = join(c, "Hero")
        c.talkToMara(s, choice = 1)
        assertTrue(c.me(s).quests.isEmpty())
        assertTrue(s.dialogues().last().dialogueText.contains("Come back if you change your mind"))
    }
}
