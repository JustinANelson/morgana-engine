package com.morgana.persistence

import com.morgana.domain.content.ClassDefinition
import com.morgana.domain.content.NpcBehavior
import com.morgana.domain.content.NpcDefinition
import com.morgana.domain.game.BlankGame
import com.morgana.domain.game.EngineApiVersion
import com.morgana.domain.item.ItemDefinition
import com.morgana.domain.item.ItemStack
import com.morgana.domain.item.ItemType
import com.morgana.domain.item.WeaponStyle
import com.morgana.domain.map.MapCell
import com.morgana.domain.map.MapDefinition
import com.morgana.domain.map.MapLayer
import com.morgana.domain.map.MapMoral
import com.morgana.domain.map.MapNpcSpawnSlot
import com.morgana.domain.map.TileAttribute
import com.morgana.domain.map.TilePlacement
import java.nio.file.Files
import java.nio.file.Path

/**
 * Scaffolds an out-of-the-box playable, fully decoupled game project for the Morgana ORPG engine.
 * Generates `game.json`, starter world content (`data/`), Lua scripts (`scripts/`), and a `README.md`.
 */
object GameScaffolder {

    fun scaffold(
        gameDir: Path,
        name: String,
        id: String = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "custom_game" }
    ): Path {
        if (Files.exists(gameDir.resolve(GameDefinitionLoader.FILE_NAME))) {
            throw IllegalArgumentException("Game already exists at $gameDir (game.json is present)")
        }
        Files.createDirectories(gameDir)

        // 1. game.json
        val gameDefinition = BlankGame.definition.copy(id = id, name = name, engineApi = EngineApiVersion.CURRENT.toString())
        Files.writeString(GameDefinitionLoader.fileIn(gameDir), GameDefinitionLoader.encode(gameDefinition))

        // 2. data/ content
        seedContent(WorldContentRepository(gameDir.resolve("data")), name)

        // 3. scripts/main.lua
        val scriptsDir = gameDir.resolve("scripts")
        Files.createDirectories(scriptsDir)
        Files.writeString(scriptsDir.resolve("main.lua"), mainLua(name))

        // 4. README.md
        Files.writeString(gameDir.resolve("README.md"), readme(gameDir, name))

        return gameDir
    }

    /**
     * Writes the blank game's content into [world] when it has none: one class, one item, one NPC and one 20 x 20 map.
     * This is also what a server data directory without a game starts with. Does nothing if [world] already has content.
     */
    fun seedIfEmpty(world: WorldContentRepository, name: String = DEFAULT_NAME) {
        if (world.isEmpty()) seedContent(world, name)
    }

    /** Display name used for content seeded into a data directory that has no game of its own. */
    const val DEFAULT_NAME = "Blank Game"

    private fun seedContent(world: WorldContentRepository, name: String) {
        // Class 1: Adventurer
        world.classes.save(1, ClassDefinition(
            id = 1,
            name = "Adventurer",
            description = "A versatile wanderer equipped to explore the realm of $name.",
            spriteIds = listOf(1),
            baseAttributes = mapOf("str" to 10, "agi" to 10, "int" to 10, "vit" to 10),
            statPointsPerLevel = 3,
            startMapId = 1,
            startX = 5,
            startY = 5,
            startingItems = listOf(ItemStack(itemId = 1, amount = 1))
        ))

        // Item 1: Starter Sword
        world.items.save(1, ItemDefinition(
            id = 1,
            name = "Bronze Sword",
            description = "A well-balanced bronze blade standard issue for new adventurers.",
            type = ItemType.WEAPON,
            weaponStyle = WeaponStyle.MELEE,
            attackDamage = 5,
            attackSpeedTicks = 10,
            rangeTiles = 1,
            iconId = 1,
            price = 10
        ))

        // NPC 1: Guide
        world.npcs.save(1, NpcDefinition(
            id = 1,
            name = "Town Guide",
            title = "Elder of $name",
            behavior = NpcBehavior.FRIENDLY_ROAM,
            spriteId = 2,
            level = 1,
            chatGreeting = "Welcome to $name, adventurer! The realm awaits.",
            vitals = mapOf("hp" to 100, "mp" to 50),
            attributes = mapOf("str" to 10, "agi" to 10, "int" to 10, "vit" to 10)
        ))

        // Map 1: Town Square (20x20)
        val width = 20
        val height = 20
        val cells = (0 until (width * height)).map { i ->
            val x = i % width
            val y = i / width
            val isBorder = x == 0 || x == width - 1 || y == 0 || y == height - 1
            MapCell(
                layers = mapOf(MapLayer.GROUND to TilePlacement(tilesetId = 1, srcTileX = 0, srcTileY = 0)),
                attribute = if (isBorder) TileAttribute.Blocked else TileAttribute.None
            )
        }
        world.maps.save(1, MapDefinition(
            id = 1,
            name = "$name Town Square",
            revision = 1,
            width = width,
            height = height,
            moral = MapMoral.SAFE_ZONE,
            cells = cells,
            npcSpawns = listOf(
                MapNpcSpawnSlot(slotIndex = 0, npcId = 1, spawnX = 10, spawnY = 10, respawnTicks = 60)
            )
        ))

    }

    private fun mainLua(name: String): String = """
-- $name Main Script
-- Morgana ORPG Engine API Version: ${EngineApiVersion.CURRENT}

register.command("hello", "Say hello to the realm", 0, function(player, args, world)
    local name = player and player.name or "Adventurer"
    world.broadcast("[" .. name .. "] greets the realm of $name!", nil, "#38BDF8")
    return true
end)

register.action("scout", function(char_id, params, world)
    local player = world.get_player(char_id)
    if player then
        world.floating_text(player.map_id, player.x, player.y, "*scouting*", "action", "#FACC15")
    end
    world.send_message(char_id, "You scout your surroundings in $name.", "#94A3B8")
end)
""".trimIndent() + "\n"

    private fun readme(gameDir: Path, name: String): String = """
# $name

Created with the Morgana ORPG Engine (API Version ${EngineApiVersion.CURRENT}).

## Running This Game

With the Morgana SDK (from the SDK folder; on Windows use the `.bat` launchers):

```
bin/morgana-server --game ${gameDir.toString().replace('\\', '/')} --data server-data/${gameDir.fileName}
bin/morgana-client
```

Check the content with `bin/morgana validate --game ${gameDir.toString().replace('\\', '/')}`.

From the engine source instead:

```powershell
.\gradlew.bat :server-app:run --args="--game ${gameDir.toString().replace('\\', '/')}"
.\gradlew.bat :client-desktop:run
```

## Project Structure

- `game.json` - Defines engine API compatibility, attributes, vitals, leveling progression, and combat formulas.
- `data/` - World content (classes, items, maps, npcs, spells, shops, resources, recipes, quests, events).
- `scripts/` - Lua gameplay scripts, custom slash commands, custom actions, and combat routines.

## Editing Content

You can edit world content directly as JSON files in `data/`, or log in as an administrator in the client and press **F1** to open the live in-game GUI editor.
""".trimIndent() + "\n"
}

