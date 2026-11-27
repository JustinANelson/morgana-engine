package com.morgana.server

import com.morgana.domain.sim.OptionalModule
import com.morgana.persistence.GameDefinitionLoader
import com.morgana.persistence.GameScaffolder
import com.morgana.persistence.MorganaDataStore
import com.morgana.persistence.ServerConfig
import com.morgana.server.tools.newWorld
import com.morgana.server.worlds.RealmBlueprint
import com.morgana.server.worlds.RealmTemplate
import com.morgana.server.worlds.WorldLibrary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The New Realm Wizard's back end: every template yields a realm that boots, with the chosen modules switched off. */
class NewRealmWizardTest {

    private fun lib(tmp: Path, games: Map<RealmTemplate, Path> = emptyMap()) =
        WorldLibrary(tmp.resolve("worlds"), tmp.resolve("server-data"), clock = { 1L }, gameDirs = games)

    private fun boot(dataDir: Path) = MorganaWorldCoordinator(MorganaDataStore(rootDir = dataDir, gameDir = dataDir))

    /** A throwaway `games/` folder holding one scaffolded game, "Cozy Island", with a script and a description. */
    private fun cozyGames(tmp: Path): Pair<RealmTemplate, Map<RealmTemplate, Path>> {
        val root = tmp.resolve("games")
        val dir = GameScaffolder.scaffold(root.resolve("cozy-island"), "Cozy Island", "cozy-island")
        val def = GameDefinitionLoader.load(dir).game.definition.copy(description = "A sunny island template discovered automatically.")
        Files.writeString(GameDefinitionLoader.fileIn(dir), GameDefinitionLoader.encode(def))
        val found = WorldLibrary.findGameDirs(root)
        return found.keys.single { it.id == "cozy-island" } to found
    }

    @Test
    fun `blueprint problems are reported without touching the disk`() {
        assertEquals(listOf("Give the realm a name."), RealmBlueprint("  ").problems())
        assertTrue(RealmBlueprint("x".repeat(41)).problems().single().contains("at most"))
        assertTrue(RealmBlueprint("Pack realm", RealmTemplate.PACK).problems().single().contains("world pack"))
        assertTrue(RealmBlueprint("Fine").problems().isEmpty())
    }

    @Test
    fun `the default template is the blank game and keeps modules on`(@TempDir tmp: Path) {
        val info = lib(tmp).create(RealmBlueprint("Camp"))
        assertEquals("blank", info.origin)
        assertFalse(Files.exists(info.dataDir.resolve("server.json")), "no server.json when nothing is switched off")
        assertEquals(1, boot(info.dataDir).simulation.maps.size)
    }

    @Test
    fun `a template carries the systems it switches off and the wizard adds to them`(@TempDir tmp: Path) {
        val (cozy, games) = cozyGames(tmp)
        // The template declares its systems in the game folder's own server.json; operator settings in it are not copied.
        Files.writeString(
            games.getValue(cozy).resolve("server.json"),
            """{ "schemaVersion": 1, "modules": { "guilds": false, "market": false, "parties": true } }"""
        )
        val plain = lib(tmp, games).create(RealmBlueprint("Plain Camp", cozy))
        assertEquals(setOf("guilds", "market"), ServerConfig.load(plain.dataDir).modules.filterValues { !it }.keys)
        val simPlain = boot(plain.dataDir).simulation
        assertFalse(simPlain.modules.isEnabled(OptionalModule.GUILDS))
        assertTrue(simPlain.modules.isEnabled(OptionalModule.PARTIES))

        val extra = lib(tmp, games).create(RealmBlueprint("Extra Camp", cozy, setOf(OptionalModule.TRADING)))
        assertEquals(setOf("guilds", "market", "trading"), ServerConfig.load(extra.dataDir).modules.filterValues { !it }.keys)
        // The template folder is untouched.
        assertEquals(setOf(OptionalModule.GUILDS, OptionalModule.MARKET), WorldLibrary.templateDisabledModules(games.getValue(cozy)))
    }

    @Test
    fun `a template with no server json or one that switches nothing off writes no server json`(@TempDir tmp: Path) {
        val (cozy, games) = cozyGames(tmp)
        assertTrue(WorldLibrary.templateDisabledModules(games.getValue(cozy)).isEmpty())
        assertFalse(Files.exists(lib(tmp, games).create(RealmBlueprint("Quiet Camp", cozy)).dataDir.resolve("server.json")))
        Files.writeString(games.getValue(cozy).resolve("server.json"), """{ "schemaVersion": 1, "modules": { "guilds": true } }""")
        assertFalse(Files.exists(lib(tmp, games).create(RealmBlueprint("Still Quiet", cozy)).dataDir.resolve("server.json")))
    }

    @Test
    fun `a game template is copied into the realm and boots with its scripts`(@TempDir tmp: Path) {
        val (cozy, games) = cozyGames(tmp)
        val info = lib(tmp, games).create(RealmBlueprint("Island Camp", cozy, setOf(OptionalModule.TRADING)))
        assertEquals("game:cozy-island", info.origin)
        for (f in listOf("game.json", "data/maps/map_001.json", "scripts/main.lua")) {
            assertTrue(Files.exists(info.dataDir.resolve(f)), "$f copied")
        }
        val sim = boot(info.dataDir).simulation
        assertEquals("Cozy Island", sim.game.definition.name)
        assertTrue(sim.scripts.hasModule("main.lua"))
        assertFalse(sim.modules.isEnabled(OptionalModule.TRADING))
        // It is a copy: editing it does not touch the template.
        Files.delete(info.dataDir.resolve("data/maps/map_001.json"))
        assertTrue(Files.exists(games.getValue(cozy).resolve("data/maps/map_001.json")))
    }

    @Test
    fun `a blank game realm boots with its own rules file`(@TempDir tmp: Path) {
        val info = lib(tmp).create(RealmBlueprint("My Blank Game", RealmTemplate.BLANK))
        assertEquals("blank", info.origin)
        assertTrue(Files.exists(info.dataDir.resolve("game.json")))
        assertEquals("My Blank Game", boot(info.dataDir).simulation.game.definition.name)
    }

    @Test
    fun `switched-off features are written to server json and enforced`(@TempDir tmp: Path) {
        val off = setOf(OptionalModule.GUILDS, OptionalModule.TRADING)
        val info = lib(tmp).create(RealmBlueprint("Quiet Camp", RealmTemplate.BLANK, off))
        val config = ServerConfig.load(info.dataDir)
        assertEquals(off, config.moduleFlags().disabled)
        val coordinator = boot(info.dataDir)
        assertFalse(coordinator.simulation.modules.isEnabled(OptionalModule.TRADING))
        assertTrue(coordinator.simulation.modules.isEnabled(OptionalModule.PARTIES))
    }

    @Test
    fun `a failed creation leaves nothing behind`(@TempDir tmp: Path) {
        val library = lib(tmp, games = emptyMap())
        val missing = RealmTemplate("ghost", "Ghost Town", "A game whose files are not installed.", gameFolder = "ghost-town")
        assertFalse(missing in library.availableTemplates(), "a game template is hidden when its files are missing")
        assertThrows(IllegalArgumentException::class.java) { library.create(RealmBlueprint("Nope", missing)) }
        assertThrows(IllegalArgumentException::class.java) { library.create(RealmBlueprint("")) }
        val worlds = tmp.resolve("worlds")
        assertTrue(!Files.exists(worlds) || Files.list(worlds).use { it.count() } == 0L, "no half-made realm folders")
    }

    @Test
    fun `the command line exposes the same choices as the wizard`(@TempDir tmp: Path) {
        val root = tmp.resolve("worlds")
        assertEquals(0, newWorld(root, "Cli Realm", null, "blank", "parties,guilds"))
        val dir = root.resolve("cli-realm")
        assertTrue(Files.exists(dir.resolve("game.json")))
        assertEquals(setOf(OptionalModule.PARTIES, OptionalModule.GUILDS), ServerConfig.load(dir).moduleFlags().disabled)
        assertEquals(2, newWorld(root, "Bad Template", null, "castle", ""))
        assertEquals(2, newWorld(root, "Bad Module", null, "starter", "teleporting"))
        assertEquals(2, newWorld(root, "Needs Pack", null, "pack", ""))
        assertNotNull(WorldLibrary(root).find("cli-realm"))
    }

    @Test
    fun `the creator can switch a template's own switches back on and the wizard can read them`(@TempDir tmp: Path) {
        val (cozy, games) = cozyGames(tmp)
        Files.writeString(games.getValue(cozy).resolve("server.json"), """{ "schemaVersion": 1, "modules": { "guilds": false, "market": false } }""")
        val library = lib(tmp, games)
        assertEquals(setOf(OptionalModule.GUILDS, OptionalModule.MARKET), library.templateDefaults(cozy))
        assertTrue(library.templateDefaults(RealmTemplate.BLANK).isEmpty(), "a built-in template switches nothing off")

        val info = library.create(RealmBlueprint("Guild Camp", cozy, enabledModules = setOf(OptionalModule.GUILDS)))
        assertEquals(setOf("market"), ServerConfig.load(info.dataDir).modules.filterValues { !it }.keys, "guilds are back on, the market stays off")
        // switched back on and off again by the same blueprint: the creator's own switch-off wins
        val both = library.create(RealmBlueprint("Both Ways", cozy, setOf(OptionalModule.GUILDS), enabledModules = setOf(OptionalModule.GUILDS)))
        assertTrue(OptionalModule.GUILDS.configKey in ServerConfig.load(both.dataDir).modules.filterValues { !it }.keys)
    }

    @Test
    fun `the command line starts from a genre and can switch systems back on`(@TempDir tmp: Path) {
        val root = tmp.resolve("worlds")
        assertEquals(0, newWorld(root, "Delve", null, "blank", genre = "roguelike"))
        val off = ServerConfig.load(root.resolve("delve")).modules.filterValues { !it }.keys
        assertEquals(com.morgana.domain.sim.SystemGroups.disabledKeysFor("roguelike"), off)
        assertTrue("guilds" in off && "parties" !in off && "bossEncounters" !in off)
        assertEquals(1, boot(root.resolve("delve")).simulation.maps.size, "the realm boots with its genre switches")

        assertEquals(0, newWorld(root, "Delve Arena", null, "blank", genre = "roguelike", enable = "arena,guilds", disable = "pets"))
        val second = ServerConfig.load(root.resolve("delve-arena")).modules.filterValues { !it }.keys
        assertTrue("arena" !in second && "guilds" !in second, "enable beats the genre")
        assertTrue("pets" in second && "trading" in second)

        assertEquals(2, newWorld(root, "Bad Genre", null, "blank", genre = "western"))
        assertEquals(2, newWorld(root, "Bad Enable", null, "blank", enable = "teleporting"))
        assertEquals(2, newWorld(root, "Genre And Typo", null, "blank", genre = "rpg", disable = "typo"))
    }

    @Test
    fun `auto-discovers custom game templates under games directory and supports peaceful no-combat preset`(@TempDir tmp: Path) {
        val (template, games) = cozyGames(tmp)
        assertEquals("Cozy Island", template.title)
        assertEquals("A sunny island template discovered automatically.", template.description)
        assertEquals("cozy-island", template.gameFolder)

        val library = lib(tmp, games)
        val peaceful = library.create(RealmBlueprint("Peaceful Town", RealmTemplate.BLANK, noCombat = true))
        val coordinator = boot(peaceful.dataDir)
        assertEquals("noop", coordinator.simulation.game.definition.combat.module)
        assertFalse(coordinator.simulation.game.definition.ui!!.combat)
        assertFalse(coordinator.simulation.game.definition.ui!!.spells)
        assertEquals(1, library.classesFor(peaceful.slug).size)

        val modern = library.create(RealmBlueprint("Modern Village", template, modernSprites = true))
        val modernCoordinator = boot(modern.dataDir)
        assertEquals(com.morgana.domain.game.SpriteProfile.MODERN_TALL, modernCoordinator.simulation.game.definition.sprites)
        val artReport = com.morgana.server.assets.ArtCheck.check(modern.dataDir, modern.slug)
        assertTrue(artReport.isClean, artReport.errors.toString())
        assertTrue(modernCoordinator.gameAssets.errors.isEmpty(), modernCoordinator.gameAssets.errors.toString())
    }

    @Test
    fun `pre-login RequestRealmInfo returns the realm classes sorted by id`(@TempDir tmp: Path) {
        val (cozy, games) = cozyGames(tmp)
        val info = lib(tmp, games).create(RealmBlueprint("Island", cozy))
        val c = boot(info.dataDir)
        val sent = mutableListOf<com.morgana.protocol.ServerPacket>()
        val session = object : ClientSession {
            override val sessionId: String = "pre-auth"
            override var characterId: String? = null
            override fun send(packet: com.morgana.protocol.ServerPacket) { sent += packet }
            override fun close(reason: String) {}
        }
        c.onSessionConnected(session)
        c.handleClientPacket(session, com.morgana.protocol.ClientPacket.RequestRealmInfo)
        val realmInfo = sent.filterIsInstance<com.morgana.protocol.ServerPacket.RealmInfo>().single()
        assertEquals("cozy-island", realmInfo.gameId)
        assertEquals(listOf("Adventurer"), realmInfo.classes.map { it.name })
    }

    @Test
    fun `creating a realm with uiLayout ring writes ring layout into game json`(@TempDir tmp: Path) {
        val library = lib(tmp)
        val info = library.create(RealmBlueprint("Dungeon Realm", RealmTemplate.BLANK, uiLayout = com.morgana.domain.game.UiLayout.RING))
        val c = boot(info.dataDir)
        assertEquals(com.morgana.domain.game.UiLayout.RING, c.simulation.game.definition.ui?.layout)
    }
}
