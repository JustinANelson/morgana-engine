package com.morgana.server

import com.morgana.persistence.GameDefinitionLoader
import com.morgana.server.tools.gameCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The `game` tool (`:server-app:gameDefinition`): validates `game.json`, or writes the built-in one to start from. */
class GameCommandTest {

    @Test
    fun `without a game json the built-in definition is shown and accepted`(@TempDir dir: Path) {
        assertEquals(0, gameCommand(dir, write = false))
        assertFalse(Files.exists(GameDefinitionLoader.fileIn(dir)), "showing must not create a file")
    }

    @Test
    fun `write creates game json once and never overwrites it`(@TempDir dir: Path) {
        assertEquals(0, gameCommand(dir, write = true))
        val file = GameDefinitionLoader.fileIn(dir)
        assertTrue(Files.isRegularFile(file))

        Files.writeString(file, Files.readString(file).replace("Blank Game", "My Edit"))
        assertEquals(0, gameCommand(dir, write = true))
        assertTrue("My Edit" in Files.readString(file), "the edited file is kept")
    }

    @Test
    fun `a broken game json makes the tool fail`(@TempDir dir: Path) {
        Files.writeString(GameDefinitionLoader.fileIn(dir), "{ \"schemaVersion\": 1 }")
        assertEquals(1, gameCommand(dir, write = false))
    }
}
