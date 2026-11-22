---
type: Concept
title: "Game-Owned Art"
description: "A game ships its own tileset and sprite PNGs in its assets/ folder; the server validates, advertises and serves them."
tags: [server, assets, content, pipeline]
code: ["server-app/src/main/kotlin/com/morgana/server/assets/GameAssets.kt", "server-app/src/main/kotlin/com/morgana/server/assets/AssetHttpHandler.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaServerRuntime.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ServerConfig.kt", "client-desktop/src/main/kotlin/com/morgana/client/desktop/GameArtExporter.kt", "client-core/src/main/kotlin/com/morgana/client/assets/ProceduralRetroAssets.kt", "tilegen/src/main/kotlin/com/morgana/tilegen/ThemedTilesetGenerator.kt", "server-app/src/main/kotlin/com/morgana/server/assets/ArtCheck.kt"]
symbols: ["GameAssets", "AssetHttpHandler", "AssetsConfig", "resolveAssetBaseUrl", "assetManifest", "assetBaseUrl", "GameArtExporter", "buildStarterTilesetPixmap", "buildStarterSpritePixmaps", "ThemedTilesetGenerator", "TilesetTheme", "ArtCheck"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-09T12:00:00Z }
stale_after: 2027-05-08T00:00:00Z
---

# Game-Owned Art

A game replaces the client's procedural art by putting PNGs in its own project folder, so a new game never edits Kotlin. The client side (manifest sync, SHA-256 check, content-addressed cache, `ProceduralRetroAssets.applyManifest`) is described in [assets and audio](/client/assets_and_audio.md).

## Layout

```
games/<game>/assets/
  tilesets/<tilesetId>.png   width and height multiples of 32
  sprites/<spriteId>.png     default: 3 frame columns x 4 direction rows (DOWN, LEFT, RIGHT, UP; width % 3 == 0, height % 4 == 0); or exact cellWidth * totalFrames x cellHeight * 4 when game.json defines sprites (SpriteProfile)
  icons/<iconId>.png         one square image: an item's inventory icon (`iconId`)
  paperdolls/<id>.png        square overlay (8..256 px) or directional sheet matching `sprites` geometry (for an item's `paperdollSpriteId`)
  projectiles/<id>.png       one square image (an item's `projectileSpriteId`)
  animations/<id>.png        single square frame or horizontal strip of square frames (1..64) for a spell or combat effect (`spriteSheetId`)
  music/<trackId>.ogg        looping background music (OGG Vorbis, 4 MB max; <trackId> is lower-case letters, digits, "_" and is what a map's `bgmTrackId` names; overrides a built-in theme of the same id)
  pictures/<name>.png        any valid PNG (`AssetKind.MISC`) for event `show_picture` popups (arbitrary name)
```

Icons and projectiles take a single square PNG from 8 to 256 pixels a side (drawn at tile size). Paperdolls (`AssetKind.PAPERDOLL`) accept either a legacy single square PNG (`8..256` px) or a full directional multi-frame sheet matching `game.json`'s `sprites` geometry (`cellWidth * totalFrames` x `cellHeight * 4`) when `sprites` is set, sliced and composited per character when `paperdollingV2` is enabled. Animations (`AssetKind.ANIMATION`) accept either a legacy single square PNG or a horizontal strip of 1 to 64 square frames (`height` in `8..256`, `width % height == 0`, e.g., `160 x 32` for five `32 x 32` frames); `AnimationStripLayout` slices the strip and steps frames via `AnimationDefinition.spriteSheetId`, `frameCount`, `frameDurationMs`, and `renderAboveEntities`.

The file name is the positive integer id the content already uses (`tilesets/1.png` replaces tileset 1; `sprites/7.png` replaces sprite 7). Whatever a game does not ship keeps the procedural art, so partial replacement works. Tiles stay 32 x 32 ([roadmap](/reference/roadmap_status.md)).

## Starting from the built-in art

`.\gradlew.bat :client-desktop:exportGameArt "-PartArgs=--game games/<game>"` (run it from PowerShell; add `--force` to overwrite) writes the client's procedural tileset as `assets/tilesets/1.png`, the sprites the game's `data/` actually uses (NPC `spriteId`, class `spriteSheetId`) as `assets/sprites/<id>.png`, and the icons, paperdolls, projectiles and animations its items and animations use, then validates them with the server's rules. The PNGs are pixel-identical to what the client draws by default (`ProceduralRetroAssets.buildStarterTilesetPixmap` / `buildStarterSpritePixmaps` feed both), so exporting changes nothing visible until you edit a file. Existing files are kept unless forced. Tested by `GameArtExporterTest`.

Procedural tilesets can also be directly generated and exported with custom parameters via:
`.\gradlew.bat :client-desktop:generateTileset "-PtilesetArgs=--game games/<game> [--id 1] [--theme classic|farm|dungeon|spaceship] [--rows 3] [--seed 1337] [--export-tiles <dir>] [--bundle <dir>] [--force]"`
`GenerateTilesetTool` uses `ProceduralTilesetGenerator` and `ThemedTilesetGenerator` from the [tilegen module](/architecture/tilegen.md) (for `farm`, `dungeon` and `spaceship` 32x32 tile sets, optionally exporting individual 32x32 tile PNGs with `--export-tiles` or bundling a folder of 32x32 PNGs with `--bundle`) and validates the output with `GameAssets.scan`. Tested by `GenerateTilesetToolTest` (and the generators by `ThemedTilesetGeneratorTest` in `:tilegen`).

`games/blank` ships no art and draws with the procedural fallback; `exportGameArt` gives a game editable copies to start from. [Lanternfall](/content/roguelike_game.md) ships item icons, and Emberdeep (deleted 2026-11-19) ships its own tilesets (2 to 6), monster sprites (101 to 120) and icons, converted from 16 px Time Fantasy sheets by its `tools/art.py`.

## Checking and seeing the art
`.\gradlew.bat :server-app:validateArt "-PcontentArgs=--game games/<game>"` runs `ArtCheck`: the same validation the server applies plus warnings for files no content uses (also part of `check`). In the F1 editor the **Art** panel is a read-only gallery of every icon, character, worn-gear, projectile and effect picture, marking the game's own and listing ids the content uses that have no picture. The step-by-step creator guide is [make your game's art](/playbooks/make_game_art.md). Tests: `ArtCheckTest`, `ArtGalleryTest`.

## Server

- `GameAssets.scan(gameDir, gameId)` is pure: it validates every file (PNG header, name, sheet shape, 4 MB per file, 32 MB per game, 4096 px edge) and builds the `AssetManifest` with SHA-256 hashes. It reports **all** problems at once; invalid files are logged as warnings by the coordinator and left out of the manifest.
- `GameAssets.importPng(kind, id, bytes)` validates live F1 uploads (`MAX_UPLOAD_BYTES` = 1 MB, PNG header + IHDR + full `ImageIO.read` decode, `shapeProblem`, and the 32 MB game total cap), writes atomically via a temp file in `<game>/assets/<kind>/`, and re-scans so `coordinator.gameAssets` swaps live without a restart.
- `AssetHttpHandler` receives `{ coordinator.gameAssets }` and is attached on the WebSocket port even when `assets/` was empty at server boot, so newly imported art is served immediately. Each response carries `ETag` (the file's SHA-256) and `Cache-Control: no-cache`, so browsers keep the file but revalidate it (`304` when unchanged); `Access-Control-Allow-Origin: *` lets browser pages fetch it. Only manifest paths are reachable (exact lookup, no file-system path is built from the request). Since 2026-11-13 an `/assets/` path the manifest does not list is passed on rather than answered: the hosted browser client serves its own `/assets/com/...` files from there, and anything else (including `..` and encoded variants) gets a 404 from `WebClientHttpHandler` ([play on a phone](/playbooks/play_on_a_phone.md)).
- `AuthResult` (login and resume) carries `assetManifest` and `assetBaseUrl` only when the game has art *and* a download URL exists. Otherwise clients keep their built-in art. Connected `ART_V2` clients (and `TILESET`/`SPRITE` clients for those two kinds) receive `ServerPacket.GameArtUpdated` (`s_admin_art_updated`) with inline Base64 PNG bytes whenever art is imported live, and cache/apply the texture immediately.
- Realms created from a template copy `assets/` with `game.json`, `data/` and `scripts/` ([world library](/server/world_library.md)).

## Where clients download from

`MorganaServerRuntime.resolveAssetBaseUrl`:

1. `server.json` `"assets": { "baseUrl": "https://cdn.example.com/hearthvale" }` when set (any static host or CDN holding the same `assets/` tree; validated to start with `http://` or `https://`).
2. Otherwise `http://{host}:<webSocketPort>/assets` (`https` only when `tls.mode` is `required`). The server cannot know the name players use, so it sends `{host}` and the client substitutes the host it connected to.
3. With no WebSocket port and no `baseUrl`, nothing is advertised.

## Known limits

- The browser keeps downloaded art in memory only, so each page load revalidates every file (a cheap `304` for unchanged art; `BrowserAssetDownloader` fetches with `cache: 'no-cache'`). A persistent IndexedDB cache is a possible later step.
- A page served over `https` cannot fetch art from an `http` URL (mixed content); with `tls.mode` `required` the default URL is `https`, otherwise set `assets.baseUrl` to an `https` host.
- Audio is still procedural. Texture atlas packing is a separate roadmap item.
- **Older clients**: the icon, projectile and animation kinds (and paperdolls) need the `art_v2` capability, which current clients advertise at login. `GameAssets.manifestFor(artV2)` leaves those entries out for a client without it, so an older client still gets its tilesets and sprites and never sees a kind it cannot decode.
- Tests: `GameAssetsTest`, `GameAssetsServingTest`, `GameArtExporterTest` (on a scaffolded test game).
