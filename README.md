# Morgana ORPG

**Morgana ORPG** is a cross-platform 2D game engine built on Kotlin and LibGDX (desktop, Android, and browser from one client core). Geared toward online RPGs and co-op dungeon crawlers in the spirit of Mirage Source and Player Worlds, the engine is genre-neutral and deterministic: a game is plain JSON data (`game.json` + `data/`), game-owned art/audio (`assets/`), and sandboxed Lua scripts (`scripts/`).

## Highlights
- **Engine and game are separate**: the engine knows no particular game. Attributes, vitals, formulas, combat rules, loot tables, run rules, boons, skill trees, and content live in a game folder under `games/` (`games/blank`, `games/tutorial-village`, `games/roguelike`, `games/halcyon-reach`, `games/white-river`), customized with sandboxed Lua scripts. `EngineBoundaryTest` enforces the split.
- **No-Code `F1` Live Editors (`:client-core`)**: Press `F1` as Mapper/Developer/Owner to edit Maps, Dungeon floor recipes, Items, NPCs & Bosses, Spells, Shops, Resources, Recipes, Quests, Events, Animations, Classes, Action Skills, Weapon Sets, Systems, Art, and Game Rules live in-game.
- **Deterministic 20 TPS Core (`:core-domain`)**: Pure Kotlin simulation covering grid & sub-tile movement, seamless neighbor stitching, realtime & action combat (combos, hitboxes, dodge i-frames, piercing projectiles, ground-targeted AoEs), procedural dungeon floors (`ROOMS_AND_CORRIDORS`, `CELLULAR_CAVES`, `BSP_DUNGEON`, `OPEN_ARENA`), instanced co-op runs (persistent/extraction/ephemeral, configurable permadeath), Diablo-style random loot affixes, boons, skill trees, parties, guilds, trading, and page-based quests/events.
- **Data First, Git-Friendly (`:server-persistence`)**: Every content definition is a schema-validated JSON file on disk (optional SQLite for player data), editable equally via `F1`, a text editor, Tiled, or scripts through one validation & hot-reload pipeline.

## Quickstart (Java 17+)

> On macOS/Linux replace `.\gradlew.bat` with `./gradlew`.

### Option A: Start with the GUI (No Code)
```powershell
# Launch the Desktop Client, open Local Worlds -> New Realm Wizard -> Play, then press F1
.\gradlew.bat :client-desktop:run
```
The first account created on the machine running the server automatically becomes **Owner (rank 4)** so you can press `F1` and build immediately.

### Option B: Dedicated Server & Game Folder (CLI / Git Workflow)
```powershell
# 1. Scaffold a new game folder under games/
.\gradlew.bat :server-app:newGame -PcontentArgs="--name 'My Game' --dir games/my-game"

# 2. Terminal 1: Start the dedicated server (TCP 7001; add --web to also serve the browser client)
.\gradlew.bat :server-app:run --args="--game games/my-game"

# 3. Terminal 2: Launch the Desktop Client and connect to localhost:7001
.\gradlew.bat :client-desktop:run
```

For remote/shared servers, grant Owner explicitly:
```powershell
.\gradlew.bat :server-app:grantOwner -PcontentArgs="--user Alice"
```

### Verification & Packaging
```powershell
# Full verification gate (all unit/integration tests, schema drift, boundary checks, wikiLint)
.\gradlew.bat check

# Validate a game's content, scripts, and art
.\gradlew.bat :server-app:validateContent "-PcontentArgs=--game games/my-game"
.\gradlew.bat :server-app:validateScripts
.\gradlew.bat :server-app:validateArt

# Export JSON Schemas for all content types
.\gradlew.bat :server-app:exportContentSchemas

# Build Android Debug APK
.\gradlew.bat :client-android:assembleDebug

# Build all distributables (Morgana SDK zip + Android APK -> build/distributables/)
.\gradlew.bat distributables
```

## Documentation
- **[docs/GETTING_STARTED.md](./docs/GETTING_STARTED.md)** — Make your first game in 15 minutes using only the `F1` editors
- **[docs/BUILD_A_ROGUELIKE_IN_F1.md](./docs/BUILD_A_ROGUELIKE_IN_F1.md)** — Build a co-op roguelike dungeon crawler in `F1` (floors, bosses, loot, runs, permadeath, boons, skill trees, action combat)
- **[docs/CREATOR_GUIDE.md](./docs/CREATOR_GUIDE.md)** — CLI, JSON, and Lua workflow for building and distributing independent games
- **[docs/API_REFERENCE.md](./docs/API_REFERENCE.md)** — Sandboxed Lua scripting & plugin seam reference
- **[docs/GENRES_AND_MODULES.md](./docs/GENRES_AND_MODULES.md)** — Core, Systems, Templates, and Plugins overview
- **[CONTRIBUTING.md](./CONTRIBUTING.md)** — Collaborator & contributor workflow, architecture rules, and PR checklist
- **[docs/DESIGN.md](./docs/DESIGN.md)** & **[docs/ARCHITECTURE.md](./docs/ARCHITECTURE.md)** — Design pillars, module graph, and roadmap
- **[wiki/index.md](./wiki/index.md)** — Full maintenance knowledge base (architecture, systems, playbooks, ADRs)
- **[docs/schemas/](./docs/schemas/)** — JSON Schemas for every content file type
