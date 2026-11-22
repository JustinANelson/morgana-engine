---
type: Reference
title: "Roadmap Archive"
description: "Finished roadmap items and backlog entries, moved out of the roadmap status page to keep it short."
tags: [roadmap, archive]
code: ["docs/DESIGN.md"]
symbols: ["Phased Roadmap"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-14T12:00:00Z }
stale_after: 2027-05-13T00:00:00Z
---

# Roadmap Archive

Finished items moved from the [roadmap](/reference/roadmap_status.md) on 2026-11-14, under their original headings. Nothing here is open. Entries are kept as written, so they may name games and files that were later deleted (for example the Hearthvale and Morgana Classic games removed on 2026-11-13); `wiki/log.md` has the dated history.

## Phases 0 to 16 and the plugin architecture phases

**Done**: Phases 0-9 (scaffold, domain, deterministic simulation, persistence, Netty server, client renderer/starter assets, HUD and F1 editors, events/quests, Android client, procedural audio/minimap/Tiled), **Phase 10** (simulation decomposition, [ADR 0006](/decisions/0006_system_decomposition.md)), **Phase 11** (content pipeline, [content pipeline](/content/content_pipeline.md)), **Phase 12** (deterministic replay, [replay](/systems/deterministic_replay.md)), **Phase 16** (SQLite for player data, [persistence layout](/server/persistence_layout.md)), **Phase 15** ([optional modules](/server/optional_modules.md)), **Phase 14** ([typed scripting API](/server/hook_api.md)), **Phase 13** ([world packs](/content/world_packs.md), [world library and embedded host](/server/world_library.md)), **Plugin Architecture Phases 0-5** (data-defined stats, vitals, game definitions, sandboxed Lua script host via Luak, ScriptRuntime port, deterministic ScriptEffects, registries, pluggable CombatModule port, MorganaClassicCombatModule, LuaCombatModule, NoOpCombatModule, generic c_action packets, differential testing, reference game as a project in `games/morgana-classic/` with a classic Mirage Source-style content set, engine API versioning contract via `EngineApiVersion`, game project template generator via `GameScaffolder` and `newGame` CLI, script capabilities and permissions model via `ScriptCapability`, comprehensive API reference and creator guides, and open-engine verification via `Phase5OpenEngineTest`, [scripting host](/systems/scripting.md), [combat](/systems/combat.md), [ADR 0013](/decisions/0013_engine_game_split_and_lua_scripting.md)).

## Backlog (realm-toggle audit, 2026-11-08)

- [x] **Soft seams as an optional module** (`softSeams`): the server skips `SeamView` and the client ignores it when off; a missing key stays on. Today it is sent for every realm with neighbours.
- [x] **`session` section in `server.json`**: resume on/off, grace minutes, idle seconds, validated, defaults equal to today (30 min, 75 s). Today these are constants.
- [x] **Auto-discovered realm templates**: each game folder carries a manifest (title, description) read by the New Realm Wizard and the realm directory, replacing the `RealmTemplate` enum entries and the bundled Hearthvale directory entry.
- [x] **New-system checklist** in the wiki (module entry, guard, config, wizard, off-test) so plots and homes are born toggleable.
- [x] Combat module selectable from `game.json` (a no-combat game by configuration).
- [ ] ~~**More content**~~ *Dropped 2026-11-13: Hearthvale and Morgana Classic were deleted.* (2026-11-13; content is data, so each item is a content slice with a test like `HearthvaleLakesideContentTest`): Hearthvale orchard and beekeeping quest lines, seasonal festival events (clock-driven, pairs with *World events* below), more NPCs with schedules once those exist, and a refresh of Morgana Classic; further template games for the New Realm Wizard.

### Context-aware classes and UI (requested 2026-11-08)
Today the login screen lists Classic's class names (Knight, Ranger...) whatever the realm, and the HUD always shows combat controls (touch Spell and Atk buttons, hotbar slots, Spells window) even in a no-combat realm like Hearthvale. Both should follow the realm.
  1. **A `ui` section in `game.json`, shipped by the template** (recommended): strict JSON with a schema, validated like the rest, exportable, editable in F1, and a template or wizard preset can pre-fill it (e.g. "no combat" turns off the combat controls). Matches how `clock` and `server.json` work.
  2. **Lua** (`register.ui(...)` hook): for realms that need logic (show the Spell button only after learning a spell); needs a sandboxed, deterministic, read-only-config style API and a client-visible result, so it layers on option 1 rather than replacing it.
  3. **An `.ini` file**: familiar to hand editors but a second format beside the strict-JSON, schema-validated pipeline (no schema, no F1 editing, no migration path); only worth it if hand-editing by non-technical creators is the priority. If chosen it should compile to the same internal profile and be validated the same way.

- [x] **Realm-driven class list**: the login screen shows the connected realm's own classes (names, descriptions, sprites), delivered by the server before login (a small `realm info` response on connect) or from the directory entry; falls back to Classic's list for older servers.
- [x] **Realm-driven UI profile**: a set of switches the client obeys, for example `combat` (Atk and Spell buttons, Spells window, hotbar), `hotbar`, `minimap`, `chat`, `partyUi`, `touchHotkeys` (which side hotkeys appear and what they map to), plus labels. The server sends it with `AuthResult`; the HUD builds from it. Default equals today's UI, so nothing changes unless a realm opts out.
- [x] **Where the configuration lives** (owner to choose; recommendation first):
- [x] Hide the touch **Spell** button, **Atk** button and empty hotbar slots when the realm has no spells or no combat (first consumer of the UI profile).
- [x] Wizard: a "no combat" feature preset that sets the UI profile and the combat switches together.

### Small issues found while play-testing Hearthvale (2026-11-08)

- [x] Web client takes about 25 s to show the first screen (suspect: music synthesis on the browser's single thread); generate in chunks, lazily, or off the first frame.
- [x] The compact HUD's Menu button overlaps the first side hotkey circle.
- [x] A door or dialogue window can stay open after a teleport or map change; close dialogues when the map changes.

### Tile and sprite sizes (owner direction 2026-11-08)
**Decision:** tiles stay **32 x 32** for every realm (the genre standard). Variation is in *sprites*: the default stays a 32 x 32 cell with 3 walk frames x 4 directions, and modern, taller characters are an **opt-in option**, never the default, so existing games and assets are unchanged.

- [x] Make the 32-pixel tile official: validate `MapDefinition.tileSize == 32` (the field exists but movement and asset slicing assume 32) and document it.
- [x] **Optional taller sprites** (for example a 32 x 64 cell holding a ~24 x 48 character), configured by a `sprites` section (`SpriteProfile`) in `game.json` (same approach as the `ui` section): `cellWidth`, `cellHeight`, `stateFrames` (`idle`, `walk`, `attack`, `cast`, `harvest`), `anchor` (`bottom_center`). Characters still occupy one tile for collision; `MorganaWorldRenderer` + `SpritePlacement` draw at the declared cell size anchored bottom-center at the feet and Y-sort jointly by foot Y (including soft-seam entities); nameplate, HP bar, cast bar, chat bubble, and floating-text offsets follow the sprite height; `SpriteSheetLayout` slices custom and procedural sprite sheets by declared cell geometry; `GameAssets.scan` and `ArtCheck` validate `cellWidth * totalFrames x cellHeight * 4`.
- [x] **Paperdolling v2** (opt-in via `sprites.paperdollingV2`, done 2026-11-13): slot-based layers (`PaperdollSlot`: `BODY`, `HAIR`, `FEET`, `LEGS`, `TORSO`, `BACK`, `HEAD`, `MAIN_HAND`, `OFF_HAND`, `ACCESSORY`), per-direction draw order (`PaperdollComposer`: weapons draw behind body when facing `UP`, capes/quivers draw over back), matching cell geometry & frame sync alongside legacy square overlays, per-layer `#rrggbb` tinting, hide rules (`paperdollHidesSlots` + validation), live equipment snapshot updates (`PublicPlayerSnapshot.paperdollLayers`), and per-character LRU GPU compositing (`PaperdollCompositeCache`).
- [x] **Texture atlas** *(done 2026-11-13)*: `TextureAtlasPacker` packs starter sprites, icons, paperdolls, projectiles, and animation strips — as well as realm manifests downloaded via `applyManifest` — into shared power-of-two GPU atlas pages (`ProceduralRetroAssets`), cutting `SpriteBatch` texture binds and per-asset texture allocations cleanly while falling back to standalone textures for oversized sheets/tilesets.
- [x] **Paperdoll preview and composite cache** *(done 2026-11-13)*: F1 **Art** panel shows proportional thumbnails plus live directional/state previews for both **Sprites** and **Worn gear** (`PAPERDOLLS` composited with `PaperdollComposer` over Sprite #1), New Realm Wizard has the **Modern sprites** (`modern_sprites`) preset (`WorldLibrary`), and `PaperdollCompositeCacheTest` verifies caching, character invalidation, LRU eviction, tinting, and direction occlusion.

## Editor suite redesign (requested 2026-11-09)

- [x] **Editing model**: `MapEditSession` (pure, tested): draft, tools, stamp brush, layers, attribute painting, rectangle fill, undo/redo by stroke, dirty tracking.
- [x] **Dark skin and icon set**: `EditorTheme`, `EditorPalette` (contrast-tested) and 24 pixel icons in `IconArt`, with tooltips; the existing F1 window already uses them ([F1 editor](/client/f1_editor.md)).
- [x] **Panel system**: `EditorWorkspace` and `DockLayout`: panels dock to the top, bottom, left or right (or float), collapse, hide, resize, drag-to-dock with a preview, remembered between runs; the world is centred in the free space. See [F1 editor](/client/f1_editor.md).
- [x] **World view aids**: grid overlay, hover cursor with the whole-stamp preview, dimming of non-active layers (D) and the live rectangle-drag preview.
- [x] **Editor review follow-ups** (2026-11-11, done 2026-11-12, see [F1 editor](/client/f1_editor.md)): friendly choice names with explanations, a Help panel and first-run hint, Animations as a form panel, a tidier Events editor, a clearer Tileset readout, delete-confirmation for saved layouts, F1 ignored before login. *Events became a proper panel on 2026-11-13 (see below).* *Still open:* finder rows below the visible list need scrolling.
- [x] **Tidy workspace** (requested 2026-11-11): minimize to a panel bar, a searchable **Panels** finder (P), built-in and user-saved layouts, and a minimal default (toolbar, status bar, Tileset, Layers). Follow-ups: drag a panel header onto the bar, remember the last-used layout name, per-realm layouts.
- [x] **Shortcuts** (B/R/G/E/I/N tools, Ctrl+Z / Ctrl+Y, Ctrl+S, H grid, D dim) and a "discard changes?" prompt when closing the editor.
- [x] Items / NPCs / Spells forms in the new shell (list on the left, typed form on the right; `ContentEditorPanel`). *Shops, Resources, Recipes, Quests, Animations and Events (2026-11-13) are done too.*

## Better than Intersect and classic Mirage (owner direction 2026-11-13)

- [x] **1. Visual event editor** (2026-11-13, see [F1 editor](/client/f1_editor.md)): the Events panel, with pages, every condition, all 30 kinds of step, dialogue answers with their own steps, undo and plain-language problems. **Event engine parity (2026-11-13, see [events](/systems/events_quests.md))**: If steps with Then and Otherwise (single or multi-condition `ALL`/`ANY` over switch, number with six comparisons, item carried, quest state including "ready to hand in", level, class, known spell, random chance), labels and loops (`Label`, `JumpToLabel`, `Loop`, `BreakLoop`), `Stop`, `SetVariable`, `ShowMessage`, `TeachSpell`, world-wide switches/numbers, `CommonEventDefinition` (`MANUAL`, `ON_TIMER`, `ON_LOGIN`, `ON_LEVEL_UP`) and `RunCommonEvent`, `Wait`, `PlaySound`, `ShowPicture`, `MoveNpc`, `TextInput` with `{...}` text placeholders, **Event tool (`V`)** map placement, **Test from here**, **Copy to another map...**, and automatic disconnect draining of non-interactive trailing steps (`finishPendingEventsOnDisconnect`). Dialogues pause the event at any depth, lines show one at a time, and answers work at every level.
- [x] **2. Modern looks** *(completed 2026-11-13)*: taller sprites (`SpriteProfile`, `SpriteSheetLayout`, `SpritePlacement`), multi-frame spell/combat animations (`AnimationStripLayout`, depth ordering, live `ArtPanel` effect preview), in-editor PNG import (`Base64Codec`, `ImportGameArt`/`GameArtUpdated`, `GameAssets.importPng`, `ArtImportValidator`, desktop/web pickers), Paperdolling v2 (`PaperdollSlot`, `PaperdollComposer`, directional/tinted/hide rules, `PaperdollCompositeCache`, `ArtPanel` worn-gear preview), and texture atlas packing (`TextureAtlasPacker` + `ProceduralRetroAssets` atlas batching).

## Co-op roguelikes (owner direction 2026-11-13)

- [x] **1. Instancing** (2026-11-13, see [instanced maps](/systems/instancing.md)): maps marked `instanced` become private copies per party or solo player, with rejoin after a drop, closing when empty, a cap, and `instanceReturn` for logins after the copy closed. *Follow-ups:* an "enter as a party" confirmation (everyone ready) instead of each member walking in, a GM view of open instances (`/instances`), per-instance difficulty, and updating open copies when a template is edited.
- [x] **2. Procedural floors** (2026-11-13, see [procedural floors](/systems/procedural_floors.md)): a pure, seeded rooms-and-corridors generator in `:core-domain` driven by a `generator` recipe on an instanced map (tiles, decorations, weighted monsters and loot, stairs to the next floor, boss and exit on the last), validated with the map; each party copy is a fresh floor and stairs lead to the next. *Follow-ups:* hand-made room templates stitched by doorways, an editor form for the recipe (it is JSON-only for now, though the editor keeps it), per-depth scaling inside one template.

## Role-play module backlog (owner direction 2026-11-13)

- [x] **1. Role-play chat** (2026-11-13, see [role-play chat](/systems/roleplay_chat.md)): `/me`, `/ooc` and `((...))`, `/shout`, `/quiet`, staff `/narrate`, speech radius in `server.json`, styled client lines, local `/hideooc`. *Follow-ups:* longer posts than the 300-character chat cap (multi-part or a post window), saved chat logs a player can export, a chat-range choice in the New Realm Wizard, chat tabs that separate IC and OOC.

## Feature backlog: what modern MMORPG players ask for (2026-11-08)

### A. Playability and accessibility (permanent)

- [x] **P** Click or tap to move (client pathfinding over blocked tiles, sends normal moves; works with seams and slides).

### D. Combat and challenge

- [x] **M** Real instanced dungeons (per-party map copies), done 2026-11-13 as module `instancing` ([instanced maps](/systems/instancing.md)); owned homes in ADR 0015 step 3 build on it.

## Design review backlog (2026-11-08)

- [x] **1. Persist the world epoch.** `currentTick` restarts at 0 each boot, so the clock resets to Spring, day 1 on every restart. Save elapsed ticks (`worldEpochTicks`) in server state, restore at boot, add to the calendar and growth tick; replays restore it as starting state. *Recommend: fix before plots.* (Verified: `currentTick` is not persisted.)
- [x] **2. Protocol forward compatibility.** Unknown packet types throw on decode, and `SeamView`, `SessionEnded`, `ResumeSession` shipped without a version bump. Decode unknown server packets to an ignorable `Unknown`, bump `PROTOCOL_VERSION` for breaking changes, add a capability list at login. *Recommend: small and high value; do first.*
- [x] **4. Cached, compact maps.** *Done: maps are packed on the wire (`PackedMapSerializer`, about 20x smaller; a 256 x 256 map fits a frame) and sent once per connection, then referenced by id and revision (`map_cache` capability, with a resync safety net); see [wire format](/protocol/wire_format.md). A persistent cross-session cache keyed by content hash is a possible later step.* Every snapshot carries the full neighbour maps (about 200 bytes per cell, roughly 0.5 MB per crossing, 2 MB frame cap, so the 256-tile limit is unreachable). Send maps once, cache by revision or hash (reuse the content-addressed cache), and use a binary or palette-compressed cell format (a CBOR codec already exists in `PacketCodec`; transports use JSON). *Recommend: before larger maps or seamless worlds.*
- [x] **6. Disconnect handling for PvP realms.** *Done 2026-11-12: `session.combatLogSeconds` (default 15, 0 = off); a player who drops within that time of PvP damage stays as a ghost and re-attaches if they return (see [session lifecycle](/protocol/session_lifecycle.md)). Not done: a longer lock for repeat offenders, and showing other players that someone is a ghost.* A dropped player was removed at once (combat-log exploit), then respawned on resume. Add a realm setting: keep the character as a ghost for a few seconds or a combat-lock timer on PvP maps; make resume re-attach instead of respawn. *Recommend: realm setting; harmless in peaceful realms.*
- [x] **10. Tap-to-move before further touch overlay work.** Exploration and farming on a phone want it more than extra buttons (already listed under playability; raise its priority).
- [x] **11. World zoom.** One world pixel is one screen pixel, so tiles are tiny on high-DPI phones. Integer world zoom (1x, 2x, 3x) with pixel-perfect scaling, auto-chosen from screen density. *Recommend: small and high impact for mobile web.*
- [x] **13. Require `wss://` off localhost in the web client.** `ws://` is the default, so passwords cross the network in cleartext unless TLS is configured. Warn visibly or refuse for non-local hosts. *Recommend: quick win.*
- [x] **14. Browser smoke test in CI.** *Done as a runnable test (`:client-web:smokeTest`, see [playbook](/playbooks/browser_smoke_test.md)); wiring it into CI waits for a CI configuration.* Unit tests missed the Menu overlap, slow startup and stale dialogs found by hand. A headless browser test (load, log in, move, harvest) plus screenshots of key screens. *Recommend: before the client grows further.*

## Completed items archived 2026-11-21
- [x] **Friends, mail, and player market** (done 2026-11-14: [friends](/systems/friends.md), [ignore list](/systems/ignore.md), [mail](/systems/mail.md), [market](/systems/market.md); join-friend, bidding auctions, and listing fees remain unbuilt).
- [x] **Quest tracker and world map** (done 2026-11-14: [input and HUD](/client/input_and_hud.md), [exploration](/systems/exploration.md)).
- [x] **Random item properties** (done 2026-11-14: [loot rarities and affixes](/systems/loot_and_affixes.md)).
- [x] **Stale statuses in `docs/DESIGN.md`** (fixed 2026-11-20; resolved issue page removed).

## Open core and closed realms (outside review 2026-11-11)

- [x] **Server select screen for long lists** (2026-11-12, see [realm browser](/client/realm_browser.md)): two panes, search, tag chips, favorites and recents pinned on top, a details pane, a row cap, in-place updates. Keyboard navigation, Enter to sign in and a better Direct Join (recent servers, address parsing, port checks) followed the same day. *Still wanted:* per-realm latency and online status, directory refresh, region and language filters, realm banners, favourites that follow the account.
- [x] **Local Worlds tab / host your own realm** (2026-11-13, see [local worlds](/client/local_worlds.md)): world list with details, Play, invite, stop hosting, rename, delete (confirmed), open folder, live hosting status and the address friends use. *Still wanted:* port and "friends can join" settings, who-is-online and kick from the tab, export as a pack from the tab, a reachability check for the forwarded port.
- [x] **Characters per account** (2026-11-13, see [characters per account](/server/characters.md)): up to `session.maxCharacters` (default 3) per account; after the password the client shows a character list (play, create, delete with the name typed), resume remembers the character, one character per account in the world at a time. Older and scripted clients keep the one-step login. *Still wanted:* switching character without leaving the world, rename, reserved-name list, last map and playtime on the cards, and the first-login form simplified (class choice now lives on the character screen too).
