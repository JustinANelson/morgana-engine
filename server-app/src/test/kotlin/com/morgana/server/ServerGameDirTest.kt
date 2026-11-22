package com.morgana.server

import com.morgana.persistence.GameScaffolder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths

/** Which game `morgana-server` serves for each combination of options (tests run from the repository root). */
class ServerGameDirTest {

    @Test
    fun `an explicit game wins, then the environment variable`(@TempDir dir: Path) {
        val realm = GameScaffolder.scaffold(dir.resolve("realm"), "Realm")
        assertEquals(Paths.get("games/x"), resolveServerGameDir(realm, Paths.get("games/x"), "games/env"))
        assertEquals(Paths.get("games/env"), resolveServerGameDir(realm, null, "games/env"))
    }

    @Test
    fun `a realm folder with its own game json serves that game`(@TempDir dir: Path) {
        val realm = GameScaffolder.scaffold(dir.resolve("realm"), "Realm")
        assertEquals(realm, resolveServerGameDir(realm, null, null))
        assertEquals(realm, resolveServerGameDir(realm, null, "  "), "a blank variable is ignored")
    }

    @Test
    fun `a plain data folder serves games blank`(@TempDir dir: Path) {
        assertEquals(Paths.get("games/blank"), resolveServerGameDir(dir.resolve("server-data"), null, null))
    }
}
