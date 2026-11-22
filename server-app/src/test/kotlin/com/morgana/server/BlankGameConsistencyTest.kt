package com.morgana.server

import com.morgana.domain.game.BlankGame
import com.morgana.persistence.GameDefinitionLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.nio.file.Paths

/** `BlankGame` (the engine default) and `games/blank/game.json` (the template) describe the same ruleset. */
class BlankGameConsistencyTest {

    @Test
    fun `the blank game folder holds the engine default rules`() {
        val shipped = GameDefinitionLoader.load(Paths.get("games/blank")).game.definition
        assertEquals(BlankGame.definition.copy(engineApi = shipped.engineApi), shipped)
    }

    @Test
    fun `a data directory without game json runs the engine default`(@TempDir dir: Path) {
        val loaded = GameDefinitionLoader.load(dir)
        assertNull(loaded.source)
        assertEquals(BlankGame.ID, loaded.game.definition.id)
    }
}
