---
type: Playbook
title: "Make Your Game's Art"
description: "A creator's guide to giving a game its own tilesets, characters, icons, worn gear, projectiles and effects: file layout, sizes, the export, the art check and the F1 Art panel."
tags: [playbook, assets, creators, art]
code: ["server-app/src/main/kotlin/com/morgana/server/assets/GameAssets.kt", "server-app/src/main/kotlin/com/morgana/server/assets/ArtCheck.kt", "client-desktop/src/main/kotlin/com/morgana/client/desktop/GameArtExporter.kt", "client-core/src/main/kotlin/com/morgana/client/ui/editor/ArtPanel.kt", "client-core/src/main/kotlin/com/morgana/client/editor/ArtGallery.kt"]
symbols: ["ArtCheck", "ArtReport", "GameAssets", "GameArtExporter", "ArtPanel", "ArtGallery", "ArtKind"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-12T12:00:00Z }
stale_after: 2027-05-11T00:00:00Z
---

# Make Your Game's Art

A game gets its own look by putting PNG files in its `assets/` folder. Nothing needs compiling. Anything you do not draw keeps the built-in art, so you can replace one picture at a time. The rules are in [game-owned art](/server/game_assets.md); this page is the order to work in.

## 1. Start from the built-in art
```
.\gradlew.bat :client-desktop:exportGameArt "-PartArgs=--game games/my-game"
```
This writes the tileset and the pictures your content actually uses (the characters your NPCs use, the icons your items use, and so on) as normal PNG files you can open in any paint program. They look exactly like the defaults until you change them. Run it from PowerShell. Files that already exist are kept unless you add `--force`.

## 2. What goes where
| Folder | One file is | Size |
| :--- | :--- | :--- |
| `assets/tilesets/<id>.png` | a sheet of 32 x 32 tiles | width and height multiples of 32 |
| `assets/sprites/<id>.png` | a character: 3 walking frames across, 4 directions down (down, left, right, up) | width divisible by 3, height by 4 |
| `assets/icons/<id>.png` | an item picture | square, 8 to 256 pixels |
| `assets/paperdolls/<id>.png` | a layer drawn over a character for worn gear | square (8..256 px) or directional sheet matching `sprites` |
| `assets/projectiles/<id>.png` | an arrow, bolt or the like | square, 8 to 256 pixels |
| `assets/animations/<id>.png` | a spell or combat effect strip | single square (8..256 px) or horizontal strip of 1..64 square frames (e.g. `160 x 32`) |

The number in the file name is the id the content uses: an item with `iconId` 12 shows `icons/12.png`. Limits: 4 MB per file on disk (1 MB per file when uploaded live in-game) and 32 MB per game. Single pictures are drawn at tile size, so a bigger square is just sharper.

## 3. Check it
```
.\gradlew.bat :server-app:validateArt "-PcontentArgs=--game games/my-game"
```
(leave the argument off to check every game). It lists how many files of each kind you ship, every file the server would refuse and why (wrong size, bad name, not a PNG), and warns about files nothing uses, such as `icons/9.png` when no item has icon 9. Errors fail the command; warnings do not. It also runs as part of `.\gradlew.bat check`.

## 4. Import or preview it in the game
Join, press `F1`, then Panels (or `P`) and open **Art**. It shows every tileset, icon, character, worn-gear layer, projectile and effect picture, with a gold corner on the ones your game supplied, and says which ids your items and NPCs use that have no picture at all (the game draws a stand-in for those).

To replace or add art without restarting the server, select a category tab, enter or click a target id (for example `12`), and click **Import PNG...** (desktop uses the native file dialog; web uses the browser file picker). The client validates dimensions first (`ArtImportValidator`), sends `c_admin_import_art` (`EDIT_CONTENT`), and the server writes the file atomically and pushes `s_admin_art_updated` live to all connected players (disk edits made directly still require a server restart unless imported through the editor).

## Paperdoll characters from TilesetGen
For layered characters (body, hair, armour, weapons as separate overlays) use the [`:tilegen`](/architecture/tilegen.md) module, which now lives in this repository; a game's own sprite kit and exporter live in [`:game-art`](/architecture/game_art.md). `TrevanaGameExporter` is the worked example: `.\gradlew.bat :game-art:run "--args=trevana export --game games/trevana-realms"` draws the five class bodies (from `game-art/specs/trevana_<class>.json`), the NPCs, the monsters and the worn-gear overlays straight into the game's `sprites` layout (23 columns x 4 rows of 32 x 48 cells) and writes `sprites/1..5, 10..21, 101..122` and `paperdolls/1..21` only (`--only bodies,gear,npcs,enemies` limits it). Everything is drawn in the `MORGANA` house style so characters match the soft map art. It prints the `sprites.uiHeights` for what it wrote: copy any that changed into `game.json`. Set `"paperdollingV2": true` in `sprites` and give items `paperdollSpriteId`. A `sprites` profile applies to every sprite sheet in the game, so older sheets must be re-laid too. Re-running replaces the owned ids, so do not hand-edit those files; draw hand-made art in other ids.

## Good to know
- Clients that predate the extra kinds are sent only tilesets and sprites, so they keep working ([protocol notes](/server/game_assets.md)).
- Audio is still built in. Texture atlas packing is on the [roadmap](/reference/roadmap_status.md).
