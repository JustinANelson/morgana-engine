package com.morgana.gameart.export

import com.morgana.tilegen.export.GameTilesetExporter
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** The open-world RPG's biome tileset specs render, and the index names everything the map builders rely on. */
class TrevanaWorldSpecsTest {

    private val specs = Path.of("specs/trevana-world")

    @Test
    fun `every biome spec renders into a temp game with its ground, autotiles, cliffs and props indexed`(@TempDir dir: Path) {
        val files = Files.list(specs).use { s -> s.filter { it.toString().endsWith(".json") }.sorted().toList() }
        assertTrue(files.size >= 7, "one spec per biome plus the hub: ${files.size}")
        val r = GameTilesetExporter.export(dir, files, firstId = 100)
        val tiles = r.tiles.keys
        val needs = listOf(
            "gelli_gold", "cel_litter", "din_rock", "cam_salt", "mere_mud", "high_ash", "high_lava_f0", "hub_paver",
            "hub_sea_f0"
        )
        // Cliff naming is the generator's; only require that each biome produced wall pieces.
        for (prefix in listOf("citadel_wall", "terrace_cliff", "mesa_wall", "mere_bank", "volcano_cliff")) {
            assertTrue(tiles.any { it.startsWith(prefix) }, "$prefix wall tiles")
        }
        for (n in needs.filterNot { it.contains("_wall_") }) assertTrue(n in tiles, "tile $n")
        for (p in listOf("hub_gatehouse", "hub_ship", "din_bridge", "cam_obelisk", "mere_reeds", "gelli_wheat", "high_gate", "cel_dead")) {
            assertTrue(p in r.props, "prop $p")
        }
        for (a in listOf("sea_on_sand", "pond_on_gold", "water_on_mud", "lava_on_ash", "salt_on_sand", "dirt_on_litter", "dirt_on_rock")) {
            assertTrue(a in r.autotiles, "autotile $a")
        }
        assertTrue(r.autotiles.values.all { it.cells.size == 47 })
    }
}
