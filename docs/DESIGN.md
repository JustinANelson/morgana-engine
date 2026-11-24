# Morgana ORPG — Design Pillars, Feature Specification & Roadmap

**Mission:** Morgana ORPG — A cross-platform 2D game engine built on Kotlin and LibGDX (desktop, Android and browser from one client core). It is geared toward online RPGs in the spirit of Mirage Source and Player Worlds, and also supports other genres. The engine is genre-neutral and deterministic; a game is data (`game.json` plus JSON content) and sandboxed Lua scripts.

> **Status key:** features below are implemented unless marked *(partial)* or *(planned)*. Statements about what is not built are deliberate.

---

## Core Design Pillars
1. **Deterministic Core**: All simulation ticks, combat formulas, movement validations, and world mutations live in a pure Kotlin domain module (`:core-domain`) testable without LibGDX rendering, network I/O, or wall-clock flakiness.
2. **Clear Architectural Boundaries**: Core domain logic never imports from transport (Netty), persistence (JSON/SQLite), or client UI/rendering (LibGDX/Scene2D/VisUI) layers.
3. **Live In-Game Creation (The Mirage Hallmark)**: Press `F1` with appropriate admin rank to open live editors for Maps, Items, NPCs, Spells, Shops, Resources, Recipes, and Quests, modifying the live world with instant broadcast to connected players. Events, common events and animations have their own `F1` editors too (see `wiki/client/f1_editor.md`).
4. **Verifiable Increments**: Every roadmap phase is self-contained and verified by automated unit and integration tests before advancing.
5. **Data First, GUI Optional**: Every piece of world content (maps, items, NPCs, spells, shops, resources, events, animations, quests, recipes, classes) is a plain, schema-versioned, Git-friendly JSON document on disk. The `F1` editor, hand-editing, scripts, and AI coding agents are all *equal clients of the same content pipeline* (validate → persist → hot-reload → broadcast). Nothing that can be authored in the GUI may be impossible to author as a file, and vice versa.
6. **Mirage Simplicity**: A new creator must be able to go from clone to a playable, editable world in minutes. Heavy or niche systems are *optional modules* (see Feature Tiers) and never complicate the core path.

---

## Defining Features (What Sets Morgana Apart)

Morgana keeps the spirit of Mirage Source and Player Worlds (build the world while standing in it, host it yourself, play with friends) and compares itself deliberately against Intersect (C#/MonoGame), whose editor is a separate application and whose logic is tied to server classes.

1. **Live `F1` Editor — Build While You Play**: The Mirage hallmark remains the headline. No separate editor application, no restart.
2. **Dual Authoring: GUI *and* Files**: Content lives in `server-data/world/<collection>/<id>.json`. Creators use `F1`; scripts and AI agents edit or generate files and run `validate`/`reload`. Both paths pass the same validation invariants. (See *Content Pipeline*.)
3. **Deterministic, Replayable Simulation**: Same seed + same input log = same world. Enables golden-master combat tests, reproducible bug reports, and AI-agent verification without a running client.
4. **One-Click Play & Host**: The desktop client can start an embedded server with a single "New World" action (Player Worlds spirit); the same server runs headless for dedicated hosting.
5. **World Packs**: A whole world exports to a folder/zip of JSON and imports into another server. Starter worlds, community content, and agent-generated worlds are shareable and diffable. Exporting a slice of a world (selected maps plus their dependencies) is not built yet, and packs carry content only, not art or sound.
6. **Typed Kotlin Hooks + Page Events**: Non-programmers use Eclipse/RMXP-style event pages; programmers use strongly typed hooks for item use, spell cast, NPC death and more, and sandboxed Lua scripts for commands, actions, formulas and combat. Hooks are notifications (the one exception is `onBeforeAction`); Kotlin plugins load as jars from `plugins/` (see [`PLUGIN_ARCHITECTURE.md`](PLUGIN_ARCHITECTURE.md)).
7. **Headless Clients on the Shared Protocol**: Because protocol and domain are shared and UI-free, scripted clients can drive a real server; the end-to-end tests do exactly that (login, flood, TLS and connection-limit scenarios). There is no standalone bot tool yet.
8. **Desktop, Android and Browser from One Client Core**: 100% shared `:client-core`, with per-platform transports (Netty TCP, browser WebSocket).

### Feature Tiers (Protecting Simplicity)
| Tier | Scope | Rule |
| :--- | :--- | :--- |
| **Core** | Maps & tile attributes, grid movement, melee/spell combat, items/inventory/equipment, NPCs, shops, bank, classes, resources, events/quests, chat, 5-tier access, `F1` editors, JSON persistence, the blank game seed | Must always work out of the box and be fully tested |
| **Optional Module** | Switchable in `server.json`: parties, guilds, trading, crafting, projectiles, sub-tile pixel movement, soft map seams, instanced maps, role-play chat. Also switchable: friends, ignore, exploration (world map and fog of war), mail and the player market. No gameplay or switch yet: a bidding auction, weather/lighting gameplay. The Android touch UI is a client concern | Toggleable via `server.json` module flags; must not be required by Core code paths |
| **Extension** | Alternative persistence backends (SQLite for player data is implemented; MongoDB is not built), custom hook plugins, third-party content packs | Lives behind ports; never leaks into `:core-domain` |

### Content Pipeline (Data-Driven Design)
All content edits, regardless of origin, flow through one pipeline:
1. **Author**: `F1` editor, hand/script/agent file edit, or world-pack import.
2. **Validate**: Schema (`schemaVersion`, required fields, kotlinx.serialization) + domain invariants (e.g. item IDs referenced by shops/NPCs exist, warp targets exist) producing machine-readable errors with the collection, id and JSON pointer. Dangling map-to-map links are warnings.
3. **Persist**: Atomic write of `<id>.json` (Git-friendly, one document per file).
4. **Hot-Reload**: Server hot-swaps the live definition; the file watcher (or the `/reload` admin command) picks up out-of-band edits.
5. **Broadcast**: Affected clients receive the update.

Agent-oriented affordances: generated JSON Schemas for every document type (`docs/schemas/`), a `validate` CLI task (`.\gradlew.bat :server-app:validateContent`), a content-authoring section in `docs/AGENT_GUIDE.md`, and stable, human-readable IDs/filenames.

---

## Feature Specification

### 1. Spatial Model, Maps & Movement
- **Configurable Map Dimensions**: Default `32x32` base tile size (`MapDefinition.tileSize`) with arbitrary map widths/heights and smooth camera tracking.
- **Seamless Neighbor Stitching**: Maps link to `North`, `South`, `East`, and `West` neighbor map IDs. The client renders adjacent maps across boundaries for seamless scrolling and cross-border visibility, alongside classic instant Warp tiles for interiors/dungeons.
- **Hybrid Movement Engine**:
  - **Grid-Locked Mode**: Classic Mirage 4-direction step-by-step tile movement with authoritative server speed validation (`MovementRules`: minimum ticks between steps, with resync of rejected moves). The `Direction` type includes diagonals, but movement and input are 4-direction today *(8-direction is planned)*.
  - **Sub-Tile / Free Pixel Mode**: Smooth pixel-step movement (diagonals included, with wall sliding) validated against tile collision and directional blocking attributes; a realm-wide rule set by the `subTileMovement` module flag (on: pixel movement for every player, off: grid steps).
- **Multi-Layer Rendering Stack**:
  - `Ground`, `Mask 1`, `Mask 2` (rendered below entities)
  - `Entity / Y-Sorted Layer` (Players, NPCs, Map Items, Projectiles, Resource Nodes, Dynamic Lights)
  - `Fringe 1`, `Fringe 2`, `Roof` (rendered above entities; Roof fades when player steps underneath)
  - `Weather & Lighting Overlay` (Rain, Snow, Fog, Ash particles + dynamic 2D ambient tinting and point lights)
  - **Autotiles & Animated Tiles**: RMXP (47-tile) and RMVX (16-tile) autotile bitmasking plus 3-frame animated and waterfall tile modes. Any tile on any layer can animate: `animationFrames`, `animationSpeedMs` and `animationFrameStep` on a placement cycle through the tiles to its right (`TileAnimation`), set in JSON or with the Tileset editor's Anim controls.
- **Classic + Extended Tile Attributes**:
  - `Blocked` (full tile collision) & `Directional Block` (per-edge N/S/E/W passability)
  - `Warp` (target map ID, X, Y, direction)
  - `Item Spawn` (item ID, amount, respawn timer)
  - `NPC Avoid` (blocks NPC movement onto the tile)
  - `Key / Door` (requires key item ID, temporary unlock timer, optional consume)
  - `Resource / Gathering Node` (Trees, Ore veins, Fishing spots, Herb patches: tool requirement, intact/depleted sprites, yield, respawn time, XP reward)
  - `Shop` & `Bank` (open the respective UI panels)
  - `Heal / Trap` (periodic vital restore/damage)
  - `Slide` (forced directional slide)
  - `Light Source` (radius, color, intensity, flicker) and `Sound Emitter` (sound ID, radius, interval: *(partial)* the attribute is defined, but nothing plays it yet)
- **Map Morals / Rulesets** (`MapMoral`): each sets whether PvP is enabled, whether items drop on death, and where a dead player respawns.
  - `Safe Zone` (no PvP, no item drop)
  - `Contested / Open PvP` (PvP on, PK/Rogue status tracking, all non-bound inventory items drop on death)
  - `Arena` (PvP on, no item drop, respawn in place)
  - `Dungeon` (`INSTANCED_DUNGEON`: PvP off, no item drop). Instanced maps (per-party copies) and boss encounters (phases, adds, telegraphed attacks, difficulty tiers, lockouts) are built; see `wiki/systems/instancing.md` and `boss_encounters.md`. There is no XP loss on death anywhere.

### 2. Combat, Progression, Classes & Social Systems
- **Real-Time Combat**:
  - **Melee**: Dedicated attack key (`Space`/`Ctrl`) with weapon swing cooldowns and reach. There is no bump-attack.
  - **Ranged Projectiles**: Bows/crossbows/throwing weapons consuming ammo slots, spawning authoritative server-tracked projectiles with range, speed, and collision (switchable off with the `projectiles` module flag).
  - **Spells & Abilities**: Single-target, directional projectile, self-cast, AoE (self- and target-centered) and warp spells with cooldowns, MP/SP costs, optional status effects and animations. A spell with `castTimeTicks` > 0 is cast over that time (vital costs up front, interrupted by moving) and has an `F1` control.
- **Vitals, Stats & Paperdoll**:
  - **Vitals**: `HP`, `MP`, `Stamina (SP)` with tick-based regeneration (every 2 seconds).
  - **Base Stats**: `STR`, `DEF`, `INT`, `AGI`, `VIT` + allocatable stat points per level.
  - **Classes**: Data-driven starter classes defining base stats, stat points per level, starting items and spells, and starting spawn point (no growth curves; max vitals come from the stat formulas).
  - **Equipment & Visual Paperdoll**: Weapon, Shield/Offhand, Helmet, Armor, Boots, Ring, Amulet, Tool and Ammo slots with layered visual paperdoll rendering over character sprites.
  - **Random Loot (Rarities & Affixes)**: a game with a `loot` section in `game.json` rolls a rarity and affixes (attribute, max-vital, damage and armour bonuses, tiered by item level) on gear dropped by monsters (their level) or lying on generated floors (the recipe's `itemLevel`). Rolls stay on the item through bags, bank, trades, mail and the market, count while worn, and change the item's name, colour and sell price. *(planned)* Uniques and sets, unidentified items, magic find, affix crafting, a `loot` editor in `F1`.
- **Buffs, Debuffs & Status Effects**:
  - Timed status effects attached to players and NPCs: `DoT` (Poison/Burn/Bleed), `HoT` (Regen), `Stun`, `Root`, `Slow`, `Haste`, `Shield`, and flat `Stat Modifiers` (`STAT_BUFF`/`STAT_DEBUFF`; there are no percent modifiers).
- **Social, Economy & Crafting** (server, protocol and client windows are implemented):
  - **Parties**: Invite/accept/leave, shared XP bonus (x1.20) and split with an XP-share toggle, party chat. A Party window and always-visible party frames (name, level and a life bar per member on the map) are built.
  - **Guilds**: Guild creation, invite/leave, message of the day, guild chat, and a guild tag over the character nameplate. Ranks are `Founder`, `Officer` and `Member`; the founder promotes and demotes from the Guild window. Friends and ignore lists are optional modules too.
  - **Player-to-Player Trading**: Secure two-stage lock & confirm trade protocol with a trade window. **Bank storage** has a client window (the Royal Bank Vault).
  - **Mail & Offline Trade / Auction House**: built as the optional `mail` and `market` modules (a bidding auction is not); see `wiki/systems/mail.md` and `market.md`.
  - **Crafting & Recipes**: Station-bound (`CraftingAnchor`) or inventory crafting converting gathered materials into gear/consumables, with a client crafting window.

### 3. Quests, Visual Event System & Kotlin Hook API
- **Page-Based Event Engine (Eclipse/RMXP Inspired)**:
  - Map and NPC events support ordered **Event Pages** evaluated by preconditions: a player switch (on/off), a per-character variable (minimum value), quest status, has-item, and minimum player level. Triggers: action/interact, player touch, and autorun on enter. There is no class precondition and no global (world-wide) variable.
  - **Built-in Commands**: the original set (Show Dialogue with branching choices and a portrait id, Give/Take Item, Give XP, Set Switch, Add Variable, Start Quest, Complete Quest, Warp Player, Heal Player, Play Animation, Open Shop/Bank/Crafting, `kotlin_hook`) plus control flow (`if`, `loop`, labels and jumps, `stop`), variables, `message`, `wait`, `play_sound`, `show_picture`, `move_npc`, `text_input`, common events, spell teaching, gear upgrade, repair and rebirth; the full list is in `wiki/systems/events_quests.md`. Not built: Give/Take Gold (gold is an item), Spawn NPC, a full Move Route.
- **Kotlin Hook API**: Developers can register strongly typed Kotlin hooks on item use, spell cast, NPC death, player death, login/logout, chat, or custom event triggers (`kotlin_hook`) without modifying engine internals. Hooks are notifications after the fact (the one exception is `onBeforeAction`), and plugins load as jars from `plugins/`; damage and crit formulas are data in `game.json`. See [`PLUGIN_ARCHITECTURE.md`](PLUGIN_ARCHITECTURE.md) for the plan.

### 4. Live In-Game Admin Panel (`F1`) & 5-Tier Access System
- **5-Tier Access Hierarchy** (backed by granular permission flags):
  - `0 — Player`: Standard gameplay, chat, party, trade, guild.
  - `1 — Monitor (Moderator)`: `/kick`, `/warpmeto`, `/warptome`, overhead Monitor nameplate color. Moderators also have timed `/mute`, `/ban`, reports and an audit log (see `wiki/server/moderation.md`).
  - `2 — Mapper`: Access to the live **Map Editor** (`F1`): layer painting, autotiles, tile attributes, NPC spawns, map properties, N/S/E/W links, weather/lighting; `/warpto <map>`, `/heal`.
  - `3 — Developer`: Access to the live content editors in `F1`:
    - **Item Editor** (name, description, type, weapon style, gathering tool, attack/defense, range, paperdoll ID, STR/INT/AGI bonuses, HP/MP restore, price, stackable)
    - **NPC Editor** (name, overhead title, sprite, AI behavior: Passive/AttackOnSight/Guard/Shopkeeper/Banker/QuestGiver/FriendlyRoam, level, HP, damage, XP reward, one drop entry, shop and event links, greeting)
    - **Spell Editor** (target type, MP cost, cooldown, base power, range, AoE radius, status effect, magnitude and duration)
    - **Shop Editor** (buy/sell rates, stock item list)
    - **Recipe Editor** (station, level, XP, any number of ingredients, output; the Recipes panel)
    - **Resource Editor** (category, tool, sprites, reward item and yield, node health, respawn, XP, messages; the Resources panel)
    - **Quest Editor** (any number of objectives, prerequisites, texts, XP, currency, item and spell rewards; the Quests panel)
    - Fields that exist in the definitions but have no `F1` control yet include spell SP cost, NPC spell lists, shop currency, and resource sprites, respawn and XP; edit those as JSON. Also `/item`, `/spawnitem`, and the IP ban commands `/ipban`, `/ipunban`, `/ipbans` (`BAN_PLAYER`).
  - `4 — Creator / Owner`: All permissions: grant access levels (`/setaccess`) and `/reload` (re-read content files). A staff broadcast exists (admin panel `POST /api/broadcast`); a world snapshot command is not built.

### 5. Presentation, UI, Audio & Blank Game
- **Scene2D + VisUI Interface**:
  - Retro-styled draggable windows: Player HUD with vitals, Quickbar (`1..0`), Character/Equipment/Inventory sheet, Spellbook, Quest Log & Crafting, Bank, Shop, Dialogue, chat console, Minimap (`M`) and the on-screen Touch Controls. Party, Guild, Trade, Friends, Mail, Market and World map windows are built.
  - Floating VisUI windows for the `F1` Live Admin Editors.
- **Classic Mirage Visual Feedback**:
  - **Overhead Nameplates & HP Bars**: Color-coded by Access Level (`0-4`) and PK/Rogue status, with the guild tag.
  - **Overhead Chat Bubbles**: Timed speech bubbles above speaking players/NPCs + multi-channel chatbox (`Map`, `Global`, `Party`, `Guild`, `Whisper`, `Emote`, `Broadcast`, `System`).
  - **Instanced Maps** (optional module `instancing`): a map marked `instanced` is a template; each party (or solo player) entering it gets a private copy that closes once it has been empty for a while (`instances` in `server.json`). First step toward hub-and-dungeon co-op roguelikes (ADR 0016). Seeded procedural floors, stairs, a boss and a hub exit are built (`wiki/systems/procedural_floors.md`). *(planned)* Run-only state, co-op death rules and progress between runs.
  - **Role-Play Chat** (optional module `rolePlayChat`): `/me` actions, out-of-character talk (`/ooc` or `((...))`, hideable with `/hideooc`), `/shout` (reaches neighbouring maps), `/quiet`, and staff `/narrate`, with an optional speech radius (`roleplay` in `server.json`). Older clients get the style folded into the text. *(planned)* Character profiles, display names, status flags, dice, chat logs and GM tools.
  - **Floating Action Text**: Animated rising numbers and labels for Damage, Critical Hits, Heals, Miss/Dodge, Status Effects, `+XP`, `LEVEL UP!`, and resource gathering.
- **Audio Engine**:
  - Procedural sound effects only (`RetroSoundEngine`: swing, hit, crit, spell, heal, level-up, pickup, warp, click), generated in code and played through a per-platform loader (Howler in the browser). Map BGM with crossfade and realm-shipped music are built; positional/map SFX are not.
- **Out-of-the-Box Blank Game**:
  - Ships procedural retro pixel-art tilesets, character sprites, paperdoll overlays and SFX, and one deliberately minimal game, `games/blank` (one map, one class, one item, one NPC, one script), which empty data directories are also seeded with. The Starter Town, Morgana Classic and Hearthvale content was deleted on 2026-11-13. Anyone cloning the repo can launch the server and client, log in (the first local account becomes Owner, `Access 4`), press `F1`, and start building.

---

## Phased Roadmap

### Done
- **Phase 0 — Project Alignment & Architecture Specification**: Defined full Mirage Source + LibGDX feature specification, module boundaries, and phased roadmap.
- **Phase 1 — Gradle Multi-Module Scaffold & Core Domain Model (`:core-domain`, `:shared-protocol`)**:
  - Set up the Gradle wrapper (originally 8.10.2 with Kotlin 2.1.0 on JDK 17; now 8.14.6 with Kotlin 2.4.10), JUnit 5 and kotlinx.serialization.
  - Implemented 5-tier access levels (`PLAYER`, `MONITOR`, `MAPPER`, `DEVELOPER`, `OWNER`), stats/vitals formulas, inventory/equipment/bank invariants, map/tile layers & attributes, and the packet codecs (JSON on the wire; a CBOR codec exists).
- **Phase 2 — Deterministic World Simulation Engine (`:core-domain`)**:
  - Implemented the 20 TPS (50ms) deterministic `WorldSimulation` engine: hybrid movement (grid & sub-tile), collision & tile attributes (`Warp`, `KeyDoor`, `HealTrap`, `Slide`, `DirectionalBlock`, `ResourceNode`), seamless N/S/E/W map border crossing, real-time melee/ranged/spell combat, buffs/debuffs, NPC AI & loot spawning, two-stage player trading, party/guild/crafting, and map moral rulesets.
- **Phase 3 — Persistence & Document Repository Layer (`:server-persistence`)**:
  - Implemented atomic JSON document collections (`JsonFileDocumentCollection`, `MorganaDataStore`) behind a storage port, plus `StarterWorldSeeder` (*Havenwood Town*, *Havenwood Outskirts*, and *Forgotten Crypt*).
- **Phase 4 — Authoritative Netty Server & Live Admin Sync (`:server-app`, `:shared-protocol`)**:
  - Built `MorganaNettyServer` and `MorganaWorldCoordinator` driving the 20 TPS simulation loop, authentication, chat channels, party/guild/trade/crafting coordination, Kotlin Hook API (`EventHookRegistry`), and live `F1` Admin Editor validation + real-time delta broadcasting.
- **Phase 5 — LibGDX Client Core, Multi-Layer Map Renderer & Starter Assets (`:client-core`, `:client-desktop`)**:
  - Implemented `ProceduralRetroAssets` (32x32 tileset, animated water, character/NPC sprites, paperdoll overlays, radial lighting) and `MorganaWorldRenderer` (6-layer map rendering, seamless N/S/E/W neighbor map stitching, dynamic 2D lighting/weather, visual paperdolls, floating combat text, and overhead chat bubbles/HP bars).
- **Phase 6 — Player HUD, Social Windows & `F1` Live Admin Editors (`:client-core`)**:
  - Built Scene2D + VisUI `PlayerHudAndWindows` and the `F1LiveAdminPanel` (Live Map Editor with layer/autotile/attribute painting, and Item, NPC, Spell, Shop & Recipe, Resource & Quest editors, and Admin Tools).
- **Phase 7 — Visual Event/Quest Engine, Kotlin Hook API & End-to-End Verification**:
  - Wired page-based NPC/Map events, branching dialogues, quest tracking, and full multi-module automated test suites.
- **Phase 8 — Android Client Module (`:client-android`) & Virtual Touch Controls**:
  - Added `:client-android` sharing 100% of `:client-core`, `:shared-protocol`, and `:core-domain` with automated multi-ABI LibGDX native `.so` extraction (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`), immersive landscape configuration, emulator loopback defaults (`10.0.2.2:7001`), and an on-screen Virtual Touch D-Pad + Action Pad in `PlayerHudAndWindows`.
- **Phase 9 — Procedural Audio Engine, 10-Slot Quickbar, Minimap Radar & Tiled Converter**:
  - Implemented zero-dependency `RetroSoundEngine` generating 16-bit PCM retro chiptune audio (swings, impacts, crits, spell casts, heals, level-up fanfares, pickups, warps, UI clicks).
  - Built interactive top-right Minimap / Radar HUD with scaled terrain, walls, warps, NPC blips, and pulsing player blips (`M` toggle).
  - Built 10-slot visual Quickbar (`Keys 1..0`) supporting both spells and usable items with one-click slot assignment from inventory and spellbook.
  - Implemented bi-directional standard Tiled Map (.json / .tmj) converter (`TiledMapConverter`) preserving 6 map layers and tile attributes.
  - Added thread-safe `InMemoryDocumentCollection` to `:server-persistence` for rapid in-memory testing and mock servers.
- **Phase 10 — Simulation Decomposition (`:core-domain`)**:
  - Split the 2,466-line `WorldSimulation` into a ~300-line facade (state, registration, `stepTick`, delegating public API) plus `MovementSystem`, `NpcAiSystem`, `CombatSystem`, `InventorySystem`, `EconomySystem`, `TradeSystem`, `EventSystem`, and `AdminSystem`. Behavior-preserving; all existing tests pass unchanged.
- **Phase 11 — Content Pipeline & Data-Driven Tooling**:
  - `ContentValidator` (`:core-domain`): pure schema/range/cross-reference validation with machine-readable `ContentIssue`s (collection, id, JSON pointer, code, message). All content definitions now carry `schemaVersion`.
  - `ContentLoader` + `ContentSchemaExporter` (`:server-persistence`): per-file error capture (parse errors, unknown fields, duplicate ids, filename mismatches) and JSON Schemas generated from the Kotlin models into `docs/schemas/` (drift-tested).
  - `F1` saves are validated before applying; `MorganaWorldCoordinator.reloadContent()`, `/reload`, and a debounced `ContentWatcher` hot-reload file edits.
  - Gradle tasks `:server-app:validateContent`, `exportContentSchemas`, `seedStarterWorld`; `run` tasks pinned to the repo root so `server-data/` is canonical.
- **Phase 12 — Deterministic Replay**:
  - `:core-domain` `replay` package: serializable `SimInput` (every externally driven simulation call, including `Tick`), `InputRecorder`, `ReplayPlayer`, and `StateHasher` (FNV-1a state and content fingerprints, including RNG state). `:server-persistence` `ReplayLogCodec` stores logs as JSON.
  - Golden-master test replays a committed starter-world session; regenerate with `MORGANA_REGEN_GOLDEN=1`.
  - Live recording: `MORGANA_RECORD_REPLAY=<file>` makes the coordinator record simulation inputs; `:server-app:replayLog` replays a log. Party/guild actions, live `F1` content edits and hot reload are not yet replayable and mark the log with `gaps`.
- **Phase 13 — World Packs & Embedded Host**:
  - `WorldPack` (`:server-persistence`): content-only folder/zip packs with a `pack.json` manifest. Export refuses invalid worlds and zips are byte-reproducible; import allow-lists paths, enforces size limits, validates a staged merge before swapping it in, and keeps replaced content as a backup. Tools `exportWorldPack` / `importWorldPack`.
  - `WorldLibrary` (`:server-app`): independent data folders under `worlds/` (plus the legacy `server-data/` as `default`), `newWorld` / `listWorlds` tools.
  - Desktop: login window gains a Local World picker and **New World & Play**; `EmbeddedWorldHost` runs one embedded server per selected world (`LocalWorldHost` interface in `:client-core`).
  - Not done: exporting a slice of a world (selected maps plus dependencies).
- **Phase 14 — Typed Scripting API**:
  - New simulation events `ItemUsed`, `SpellCast`, `NpcKilled`, `PlayerDied`; `MorganaServerHook` gains `onItemUsed`, `onSpellCast`, `onNpcKilled`, `onPlayerDeath`, `onPlayerChat` (notifications after the fact, exception-isolated).
  - `FormulaOverrides` + `SimFormulas` (plugins replacing damage and crit) were added here and later removed: the formulas became data in `game.json` (engine/game split, step 1).
  - Still open: plugins cannot register new event commands or veto actions; plugin-made simulation changes and overrides are not captured by replay.
- **Phase 15 — Optional Module Flags**:
  - Systems vocabulary (ADR 0018, accepted): Core, Systems (`OptionalModule`, each with genre `tags` and `requires`), Templates (a game folder, whose own `server.json` switches carry into a new realm) and Plugins (sandboxed Lua). Built: eleven kernel seams (requirement gates, event commands, tick participants, combat arbiters and observers, action gates, activity interrupters, chat commands, item use, kill rewards, monster behaviour) and `WorldSimulation.install`; promotion, rebirth, arena, guild war, pets, stalls, durability, gear upgrades, respec, the threat table and boss encounters are systems in `core-domain/.../systems/`, trading and the guild bank are facade services there (see `wiki/systems/system_seams.md`). Lua plugins (`register.plugin`, `register.requirement`, `register.may_fight`, `register.monster`, `register.tick`, `register.item_use`, `register.action_gate`, `register.on_interrupt`, `register.combat_observer`, `register.on_kill`) implement every seam. Every engine rule system is on the one system list (`OptionalModule`, 27 entries), switchable from `server.json`, a template or the F1 Systems panel, and replaceable by a plugin (`register.plugin{ replaces }`). The wizard and the F1 Systems panel list systems under genre headings and offer a starter per genre (`SystemGroups`), the wizard starts from a template's own switches, and `world-new --genre` is the command-line twin. Instancing is a system behind the twelfth seam (`WarpRouter`), and the layers are final (ADR 0018 Accepted). Planned: starter content for more genres.
  - `OptionalModule` / `ModuleFlags` (`:core-domain`) for parties, guilds, trading, crafting, projectiles and free pixel movement, configured per data directory in `server.json` (strict parsing, missing file = all on). Mail and the market were added as modules later; weather/lighting have no switch.
- **Phase 16 — Persistence Extensions (player data)**:
  - Opt-in SQLite backend for player data only (accounts, characters, guilds, mail, market) behind `DocumentCollection`, selected by `storage.playerData` in `server.json` (default `json`), with a verified two-way `storage --migrate` tool. World content, `server.json`, packs and replays remain files. MongoDB remains an unbuilt possibility.
- **Transport Security (TLS)**:
  - Optional TLS below the framing: modes `off`/`optional`/`required`/`remote` in `server.json`; hosted worlds default to `remote` (plain loopback, TLS for everyone else). Automatic self-signed certificate per world, operator PKCS12 keystores, fail-closed startup. Invite codes `morgana://host:port#fp=<sha-256>` pin the certificate; typed addresses use trust-on-first-use. Decision: ADR 0012. Known gaps: Android has no invite/confirmation UI yet; no certificate rotation or revocation.
- **Packet Flood Limits & Per-IP Connection Limits**:
  - `PacketLimiter` (token bucket per connection and packet class, kick after sustained drops), `InputLimits` text caps, `RawFloodGuard`; `ConnectionGate` admits sockets before TLS with per-address and server-wide caps, strikes and escalating persisted temporary bans (`/ipban`, `/ipunban`, `/ipbans`). Not covered: network-level floods, PROXY protocol.
- **Knowledge Base — LLM Wiki (`wiki/`, `:dev-tools`)**:
  - OKF v0.2 bundle (architecture, domain, systems, server, protocol, client, content, ADRs, playbooks, reference) with `WikiLint` wired into `check`; operating manual in `docs/WIKI_GUIDE.md`.
- **Known-Issue Burn-Down (hardening)**:
  - Salted PBKDF2 passwords, `OwnerPolicy` (configured owners or first loopback account), `PROTOCOL_VERSION` handshake, server-side movement rate limit with resync, crafting stations, autorun events, a Tiled round trip that preserves every tile attribute, and a persisted-tick fix so characters saved late in a long run are not frozen after a restart.
- **Browser Client (`:client-web`)**:
  - Optional WebSocket listener on the server (same packets, `MORGANA_WS_PORT`); `:client-core` no longer depends on Netty (`ClientTransport` interface, Netty implementation in `:client-net-netty`); `:client-web` compiles the shared client to JavaScript with gdx-teavm (libGDX 1.14.2). Verified in Chrome: login, keyboard movement, chat, audio and the `F1` item and map editors. A minified release build is available.
- **Engine/Game Split Plan — Step 0 (toolchain and Luak)**:
  - Kotlin 2.4.10 and Gradle 8.14.6; Luak (Lua 5.5.1) vendored and checksum-pinned in `third-party/` with the `:script-luak` module (smoke tests only). Direction recorded in ADR 0013; design in [`PLUGIN_ARCHITECTURE.md`](PLUGIN_ARCHITECTURE.md).

- **Engine/Game Split Plan — Step 1 (data-defined stats and vitals)**:
  - `com.morgana.domain.game` (`:core-domain`): `GameDefinition` (data-defined attributes, vitals, progression, combat formulas), the integer-only `Formula` expression language, `GameDefinitionValidator` (trial-evaluates every formula at its input extremes), `CompiledGame`, and the neutral default `BlankGame` (the former `MorganaClassic` ruleset is now a test fixture); `GameDefinitionLoader` and the `gameDefinition` tool (`:server-persistence`, `:server-app`); `docs/schemas/game.schema.json`.
  - Simulation & System Wiring: Hardcoded `Stats`, `Vitals`, and `StatFormulas` replaced throughout `:core-domain` (`VitalState`, `WorldSimulation`, `CombatSystem`, `InventorySystem`, `MovementSystem`, `NpcAiSystem`, `AdminSystem`, `EventSystem`).
  - Content & Schema: `ContentSchema.CURRENT_VERSION = 2`. Classes, items, spells, and NPCs use dynamic attribute and vital maps validated against `GameDefinition` via `ContentValidator`.
  - Protocol: `PROTOCOL_VERSION = 2`. `PublicPlayerSnapshot` vitals map, `AllocateStatPoint(attributeId: String)`, `AuthResult(gameDefinition)`.
  - Client & Tools: Dynamic HUD vitals bar, character attribute allocation sheet, dynamic spell costs, `F1` live editors, and overhead HP bars. Verified with end-to-end custom game independence test (`CustomGameIndependenceTest`).

- **Engine/Game Split Plan — Step 2 (sandboxed Lua script host)**:
  - `com.morgana.domain.script` (`:core-domain`): `ScriptRuntime` port, `ScriptBudget`, `ScriptRegistry`, `ScriptWorldView`, `ScriptEffect`, and `SimInput.ExecuteScriptEffect`.
  - `:script-luak`: `LuakScriptRuntime`, `LuakSandbox`, `LuakScriptValidator`. Full sandbox hardening (stripped untrusted packages, bounded regex to defeat ReDoS, address-sanitized `tostring` and errors, memory ceiling, deterministic instruction budgets, and circuit breaker).
  - Server & Replay: Coordinator script loading from `scripts/`, hot reload via `/reload` and `reloadContent()`, `:server-app:validateScripts` tool wired into `check`, and deterministic replay verified.

- **Engine/Game Split Plan — Step 3 (systems pipeline and replaceable combat)**:
  - Pluggable `CombatModule` interface in `:core-domain` (`tickProjectiles`, `tickPlayers`, `onPlayerAttack`, `onPlayerCastSpell`, `handleAction`).
  - Default Kotlin `RealtimeCombatModule`, peaceful `NoOpCombatModule`, and script-driven `LuaCombatModule`.
  - Generic action protocol `ClientPacket.CustomAction` (`c_action`) and `SimInput.CustomAction`.
  - Expanded World API & Script Effects (`world.spawn_projectile`, `world.award_xp`, `world.consume_vital`, `register.action`, `register.combat`).
  - Verified differential testing between Lua and Kotlin combat, and confirmed a peaceful custom game runs cleanly on the unmodified engine.

- **Diablo-style demo, item 1 — Random loot (rarities and affixes)**:
  - `LootRules` in `game.json`, `ItemRoller` (seeded, pure), `ItemStack.rolls`, `Inventory.addStack` for every item move, rolled bonuses in `EquipmentLoadout` and weapon damage, floor `itemLevel`, rarity colours and affix lines in the client, rarity sell prices; Lanternfall uses it. See `wiki/systems/loot_and_affixes.md`.

### Next
- **Live content hot-loading for generated content (do this before any map authoring).** An LLM or script writing map, tileset and content files into a running game should be seen in a connected client within about a second, with the same safety as an `F1` save and feedback the author can read. Today `ContentWatcher` reloads `.json` under a game's `data/` (400 ms debounce, `reloadContent()`), which covers most of it; the gaps to close, in order:
  1. ~~**Per-file validation, not all-or-nothing.**~~ *Done 2026-11-24 (`ContentSalvage`): every valid file applies, an invalid one keeps its live version (or is left out if new) and is reported.*
  2. ~~**A feedback file for the author.**~~ *Done 2026-11-24: `<game>/.reload-status.json` after each reload, with per-file errors (JSON pointer, code, message), what changed and warnings.*
  3. ~~**Watch everything a game is made of.**~~ *Done 2026-11-24 for `scripts/*.lua`, `assets/` and `game.json` (a change to `game.json` sets `restartRequired` in the status file; swapping the live-changeable rules, such as tuning, schedules and object kinds, into a running world is still to do).*
  4. ~~**Assets reach connected clients live.**~~ *Done 2026-11-24: `refreshAssets()` rescans and sends each new or changed file as `GameArtUpdated` with the PNG inline.*
  5. ~~**Maps are first-class in the loop.**~~ *Done 2026-11-24: deleted maps are unloaded (players sent to the start), a changed map keeps unchanged monsters and resource nodes, players on newly blocked tiles move to the nearest open tile; the client already redraws a swapped map in place.*
  6. ~~**Partial writes are safe.**~~ *Done 2026-11-24 through item 1: an unreadable half-written file keeps its live version and the next change event reloads it once complete; dot-files are not content.*
  7. **Replay and persistence stay correct.** A reload is a recorded gap in a replay (already so, for content and now art); placed world objects on a reloaded map are kept (a deleted map takes its objects with it). *Mostly done; a replay test across a map swap is still to add.*
  8. **Tests and docs**: tests exist for salvage, the status file, map keep/settle/unload, art push, game-file notice and the watcher; `wiki/content/hot_reload.md` and the "authoring with an LLM" section of `docs/AGENT_GUIDE.md` are written. Still to add: an end-to-end test from a file write through the real watcher to a connected test client.
- **Engine/Game split, steps 4-5** (see [`PLUGIN_ARCHITECTURE.md`](PLUGIN_ARCHITECTURE.md); steps 1-5 are complete): step 4 (the reference game as its own project) was done and then reversed: the owner deleted `games/morgana-classic` and `games/hearthvale` on 2026-11-13 and the only game folder is now `games/blank`, later joined by `games/roguelike`, `games/tutorial-village` and the Time Fantasy demo `games/emberdeep` (deleted 2026-11-19); step 5 (open-engine tooling: API versioning, the scaffolder, script capabilities, the API reference and creator guide) is done.
- **Security hardening (remaining)**: Android UI for invite codes and fingerprint confirmation.
- **Gaps listed above**: a bidding auction house; positional map SFX (the `Sound Emitter` tile does not play yet). Verified 2026-11-19: party, guild and trade windows, cast times, mail, market, map music and event forms are built.
- **MongoDB adapter** (optional, unbuilt): an implementation of `DocumentCollection<ID, T>` for the player-data repositories.
