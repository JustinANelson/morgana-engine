---
type: Reference
title: "Roadmap and Status"
description: "Open roadmap items and backlogs; the authoritative plan is docs/DESIGN.md. Finished items are in the roadmap archive."
tags: [roadmap]
code: ["docs/DESIGN.md"]
symbols: ["Phased Roadmap", "Phase 13"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Roadmap and Status

Authoritative source: `docs/DESIGN.md` (update it when a phase completes).

**Done**: phases 0 to 16, the plugin architecture phases 0 to 5 and the known-issue burn-down. The list is in the [roadmap archive](/reference/roadmap_archive.md).
**Planned**: Android UI for invite codes. Completed (connection limits: [connection limits](/server/connection_limits.md); packet limits: [packet limits](/server/packet_limits.md); TLS: [TLS and invite codes](/server/tls.md)): the known-issue burn-down ([ADR 0011](/decisions/0011_security_baseline.md)).
The wiki itself is part of the project's tooling ([ADR 0008](/decisions/0008_okf_wiki.md)).

## Backlog (realm-toggle audit, 2026-11-08)
Found by auditing whether new systems are per-realm options or hardcoded for every realm. Rule for new systems: an `OptionalModule` entry, a guard in the domain facade or coordinator, config in `server.json` or `game.json`, wizard exposure, and a test with it off ([optional modules](/server/optional_modules.md)).
- [ ] Plots and crops (ADR 0015 step 2), then owned homes (step 3).
- [ ] Other recommended: replay viewer; `WorldTopology` and `realm.json` profile.

## Editor suite redesign (requested 2026-11-09)
Goal: make the in-game `F1` editors look professional and feel familiar to old-school developers of Mirage Source and similar editors. **Decisions**: panels start **docked** in the classic arrangement (toolbar on top, tileset palette and layer/attribute panels on the right, map in the centre, status bar below) and can be torn off to **float**; **modern dark** look (custom skin, icons, tooltips); **map editor first**, then the Items / NPCs / Spells forms, then the Events and Animation studios. Data stays authorable as JSON files; the editors still go through the same validation pipeline ([F1 editor](/client/f1_editor.md)).
- [ ] **Map editor panels**: *done so far: the tile palette (every tileset stacked in a narrow scrolling column, real image, no captions, drag-to-select stamps, jump-to and collapse), and a toolbar (save, undo, redo, tool buttons, grid, zoom), status bar, layers panel with show/hide, grid, stamp hover preview and the rectangle tool.* Attributes panel with typed fields, map properties and the map list are done. Weather moved to zones (maps have interior/exterior, a zone and an instanced flag). Dim-other-layers (D), keyboard shortcuts, the map list and the status bar are done.
- [ ] **Weather zoning, remaining work** (weather zones themselves are built, see [game definition](/domain/game_definition.md)): an in-editor **zone editor** (create, rename and delete zones and edit each zone's weather states, period and seed instead of hand-editing `game.json`), an **admin command** to force a zone's weather for a while (for example `/weather <zone> rain`), and a "now playing" weather readout per zone in the editor. Backlog only; not scheduled.

## Better than Intersect and classic Mirage (owner direction 2026-11-13)
Ranked by what most separates Morgana from Intersect Engine and the Mirage Source family. Morgana already leads on reach (browser and Android clients, in-game editors on every platform), on content as diffable JSON files, on Lua and plugins instead of editing the engine, and on operations (TLS, limits, moderation, replay, world packs, host your own realm). The gap is a non-programmer making a good-looking game with quests and getting it online.
- [ ] **3. One-click publish** (new): from the Local Worlds tab or the wizard, export a realm as a ready-to-run server bundle (server, game folder, `server.json` with TLS notes, start scripts) plus the web client folder and the link players open, with a checklist that tests the port from outside. Builds on world packs and the web client; neither rival has an equivalent.
- [ ] **4. Layered content** (item 8 of the design review): a realm is a base game plus overrides, so engine and content upgrades reach existing realms.
- [ ] **6. Real instancing** for dungeons and homes (section D and ADR 0015). *Dungeons done 2026-11-13 ([instanced maps](/systems/instancing.md)); owned homes still need per-owner persisted copies.*
- [ ] **8. Live co-editing** (new): two builders on one map at once with presence (who is editing where), per-map or per-region locks, and edits streamed to the other editor; Mirage's draw was mapping together in-game. Today a second editor's save simply replaces the first.
- [ ] **9. Importers** (new): Intersect and RPG Maker tilesets and character sheets into the game-owned art layout (slicing, renumbering, a preview), and where practical their maps; Tiled import already exists.
- [ ] **10. Creator onboarding** (new): a "make your first game in 15 minutes" guide that follows the wizard and the editors, a gallery of sample games in the realm templates, and the wiki published as a browsable docs site.
- [ ] **Smaller, also new**: *a REST admin API and web panel done 2026-11-14 ([admin panel](/server/admin_panel.md));* a load-test bot with a published players-per-server figure (the single coordinator lock in item 12 of the design review is the known limit).

## Diablo-style demo game (owner direction 2026-11-14)
The demo is a co-op roguelike / Diablo-style dungeon crawler with an MMO-style hub and instanced dungeons, grown from [Lanternfall](/content/roguelike_game.md). Owner instruction: work the items in this order.
- [ ] **2. Run lifecycle** (co-op roguelike step 3 below).
- [ ] **3. Co-op death rules** (step 5 below): downed state and revive, spectate, a wipe ends the run, optional hardcore.
- [ ] **4. Difficulty scaling** by party size and depth (monster health, damage and loot), optional difficulty tiers.
- [ ] **5. Boss mechanics**: telegraphed ground attacks, phases, enrage timers, adds; scriptable in Lua.
- [ ] **6. Progress between runs** (step 6 below); run-only state (step 4) only if roguelite perks are wanted.
- [ ] **7. Art and audio**: directional, multi-frame sprites; attack and spell effects; game-owned music and sound effects.
- [ ] **8. Combat feel**: mouse targeting, hold to attack, ground-targeted spells, a cast bar.
- [ ] **9. Floor variety**: layouts beyond rooms and L-corridors, room events (shrines, chests, champion packs), an editor form for the floor recipe.
- [ ] **10. Elite monsters**: champion and elite packs with their own affixes; status effects on monsters.
- [ ] **11. Going public**: one-click publish, password reset and email verification, a load test.

## Co-op roguelikes (owner direction 2026-11-13)
Target shape: **hub plus dungeon runs** (a persistent town; parties enter generated, private dungeon runs and return with permanent progress). A "whole realm is one run" game is a realm whose hub is a small lobby. Plan and reasoning: [ADR 0016](/decisions/0016_map_instancing_and_coop_roguelikes.md). Each step follows the new-system checklist in [optional modules](/server/optional_modules.md).
- [ ] **3. Run lifecycle**: a run document (seed, party, depth, start tick), the end of a run (success or wipe), what happens when everyone disconnects, a run timer. *Done with step 2:* stairs between floors, a boss beside the last floor's exit, the exit back to the hub, a fresh floor when a party starts again.
- [ ] **4. Run-only state**: items, levels or perks gained during a run, kept apart from the character and wiped (or partly banked) when it ends.
- [ ] **5. Co-op death rules**: downed state with a revive window, spectating after death, a wipe ends the run, optional permadeath per realm (also the "death penalty presets" item in section D).
- [ ] **6. Progress between runs**: unlocks, a meta currency spent in the hub, run history and leaderboards; shareable seeds and verifiable runs through [deterministic replay](/systems/deterministic_replay.md).
- [ ] **7. A sample roguelike realm**: a small game (hub, two floor themes, a boss) that exercises all of the above, offered as a New Realm Wizard template. *First version 2026-11-13:* [Lanternfall](/content/roguelike_game.md) (`games/roguelike`: Lantern Camp hub, two crypt floors and the Frost Caverns with the Frost Warden), in the wizard and the SDK; it grows as steps 3 to 6 land. *2026-11-14:* a second, bigger demo, Emberdeep (deleted 2026-11-19) (`games/emberdeep`: Time Fantasy art, the 64 x 48 town of Brightwater, twelve generated floors in four bands with four guardians).

## Role-play module backlog (owner direction 2026-11-13)
Support for social role-play realms (the Player Worlds / Furcadia style: players write their characters into shared scenes, staff run stories live). Each piece is part of the RP module family and follows the new-system checklist in [optional modules](/server/optional_modules.md). Parked 2026-11-13 in favour of co-op roguelikes (above); order is rough priority.
- [ ] **2. Character identity**: a free-text profile (appearance, personality, history) read with `/look <name>` or by clicking a character; display names separate from login names; titles; a pronouns field. Needs optional `CharacterState` fields, a profile window, and moderation (report a profile, staff can clear one).
- [ ] **3. Status and presence**: "Looking for RP", AFK and do-not-disturb flags on the nameplate; an idle timer that sets AFK automatically.
- [ ] **4. Poses**: sit, sleep, kneel and similar idle states (extra sprite states in `SpriteProfile`, a `c_pose` packet, sit on chair tiles).
- [ ] **5. Dice and checks**: a built-in deterministic `/roll 2d6+3` on the seeded RNG, heard by those nearby and kept for audit; stat checks (`/check STR`) through the `game.json` formula language. (The Lua `/roll` that lived in `morgana-classic` was deleted with that game.)
- [ ] **6. Game-master tools**: possess or puppet an NPC (speak and move as it), spawn and remove props and NPCs live for a scene, scene controls (weather, lighting, music cue, lock a room), and a GM-only "scene" view of who is present. The event commands `Spawn/Despawn NPC` and `Set Move Route` overlap.
- [ ] **7. Spaces players own and write in**: owned homes with guest permissions and decorating (ADR 0015 step 3), and writable items: books, letters and notes whose text travels with the item.
- [ ] **8. Community structure**: *party and guild windows, real guild ranks and a friends list done 2026-11-14 ([guilds](/systems/guilds.md), [friends](/systems/friends.md)); a trade-request window is still missing.* Remaining: realm-browser tags such as "Heavy RP".

## Feature backlog: what modern MMORPG players ask for (2026-11-08)
Decision key: **P** = permanent engine feature (usability, safety), configurable but on; **M** = optional module (changes how a realm plays: needs an `OptionalModule` entry, a guard, config, wizard switch and an off-test per the new-system checklist above); **P/M** = permanent core with optional extras. Order within each group is rough priority. Monetization-style features (battle passes, loot boxes) are deliberately **not** on this list.

**Shortlist (best payoff for this engine):** 1 tap-to-move and quest tracker and world map; 2 friends, mail and player market (storage exists); 3 real instancing (dungeons and homes); 4 accessibility, rebinding, localization; 5 achievements, collections, titles; 6 moderation tools and account security (before any public realm); 7 professions; 8 controller support.

### A. Playability and accessibility (permanent)
- [ ] **P** Quest tracker with waypoints, and a world map with fog of war (discovered tiles stored per character). *Quest tracker with waypoints and the world map with fog of war done 2026-11-14 ([input and HUD](/client/input_and_hud.md), [exploration](/systems/exploration.md)).*
- [ ] **P** Controller or gamepad support (the touch overlay layout maps directly).
- [ ] **P** Rebindable keys; text size; colour-blind palettes; reduce-motion option; screen-reader-friendly chat. *Rebindable keys, the larger interface, colour-blind palettes and reduce motion done 2026-11-14 ([accessibility](/client/accessibility.md)); screen-reader-friendly chat remains.*
- [ ] **P** Localization: move UI and content strings to translation files; per-realm language list.
- [ ] **P** Inventory QoL: *done: sort (merges stacks), filter tabs, comparison tooltips, auto-loot, and the shop buy-back tab (2026-11-20).* Remaining: a search box in the bag (the model exists, the box was removed for room) and loot filters.
- [ ] **P** Chat QoL: emotes; tabs and filters; `@` mentions. *Ignore (block) done 2026-11-14 ([ignore list](/systems/ignore.md)); mute and report exist as staff commands ([moderation](/server/moderation.md)).*

### B. Social
- [ ] **M** Guild extras: guild bank, guild halls (shares tech with homes). *Ranks (founder, officer, member), invitations, kicks and the Guild window done 2026-11-14 ([guilds](/systems/guilds.md)).*
- [ ] **M** Emotes, titles, and a cosmetics or transmog layer (pairs with paperdolling v2).
- [ ] **M** Duels, and a looking-for-party board.

### C. Progression
- [ ] **M** Achievements, collections (bestiary, item log) and titles as rewards; derived from existing quest, kill, craft and harvest events.
- [ ] **M** Professions (farming, fishing, cooking, crafting): levels, proficiency, quality tiers, unlocks. First consumer: a farming game (Hearthvale, the original candidate, was deleted 2026-11-13).
- [ ] **M** Skill or talent trees, class change, optional prestige.
- [ ] **M** Reputation and factions.
- [ ] **M** Daily and weekly resets (needs the world clock, which exists).

### D. Combat and challenge
- [ ] **M** Boss mechanics: telegraphed ground attacks, phases, enrage timers, scriptable in Lua.
- [ ] **M** Targeting and aiming options: ground-targeted area spells, lock-on; cooldown and cast UI.
- [ ] **M** PvP modes: duels, arena ladders, battlegrounds, faction war (the `ARENA` map moral exists).
- [ ] **M** Death penalty presets: respawn only, item loss, durability loss, permadeath or hardcore realms (a wizard preset).

### E. World and economy
- [ ] **M** World events: seasonal festivals, invasions, world bosses (clock-driven).
- [ ] **M** NPC schedules: shops open and close, villagers move by time of day (clock-driven).
- [ ] **M** Mounts and fast travel (waypoints); pets and companions.
- [ ] **M** Durability and repair, enchanting or sockets, crafting quality, multiple currencies, taxes and fees.

### F. Operations and trust
- [ ] **P** Moderation tools: *done 2026-11-12 as chat commands (see [moderation](/server/moderation.md)): kick, timed mute and ban with reasons, a player report queue, an audit log.* Remaining: a web admin panel or an in-game report window, report context (recent chat), appeals, automatic sanctions and word filters.
- [ ] **P** Accounts: email verification, password reset, optional two-factor, OAuth sign-in (before public realms; configurable per realm).
- [ ] **P** Metrics and health: population graphs, tick-time alerts. *Population and tick-time graph, status and tick timing done 2026-11-14 in the [admin panel](/server/admin_panel.md); alerts that notify someone are not built.*
- [ ] **P/M** Spectating and a replay viewer (the deterministic replay system exists; viewer is permanent, spectate-live is a realm switch).
- [ ] **P** Cross-realm and cross-play basics: web, mobile and desktop on one account (resume tokens already work across a dropped connection).

## Design review backlog (2026-11-08)
From a review of the major design decisions. Priority order; each item states the recommendation. Items 1 and 2 are prerequisites for ADR 0015 steps 2 and 3 (see its revision).
- [ ] **3. Game-owned art.** *In progress: the server half is done (validated `assets/` folder, manifest in `AuthResult`, HTTP serving; see [game-owned art](/server/game_assets.md)). Hearthvale now ships its own PNGs, made with the `exportGameArt` tool. Realm-switch revert and browser downloads done. Icons, paperdolls, projectiles and animations are game-owned too (2026-11-12, behind the `art_v2` capability). Remaining: a persistent browser cache (low value while the `304` revalidation is cheap), directional paperdolls and multi-frame animations (see the sprite items above), art tooling done 2026-11-12: `validateArt`, the F1 Art panel and the [creator guide](/playbooks/make_game_art.md) (still wanted: an in-editor way to import a PNG).* Hearthvale's tiles and sprite palettes live in `ProceduralRetroAssets.kt`, so each new game edits Kotlin. Each game ships its own tileset and sprite PNGs through the existing asset-sync pipeline and manifest; procedural art stays as the fallback and for Classic. *Recommend: high priority; the biggest barrier for outside creators.*
- [ ] **5. Soft seams as an optional module, then interest management.** Currently hardcoded for every realm. Module first (already above); later replace per-feature streaming with general interest management (the server sends what is near you regardless of map). *Recommend: module now, interest management with continuous worlds.*
- [ ] **7. Module registry instead of a closed enum.** *In progress (2026-11-12): `ModuleRegistry` exists and plugins register namespaced `plugin:module` switches that `server.json` and `ModuleFlags` understand; built-ins are described through it. Remaining: the engine's own checks still use the `OptionalModule` enum; guard hooks, a config schema and a client flag per module; the two open questions below.* `OptionalModule` is an enum, so third parties cannot add modules without editing the core. Replace with a registry of string-id descriptors (name, description, guard hooks, config schema, client flag), with built-ins registered the same way. *Recommend: when the optional-module backlog passes a handful; this is the real plugin story.* *Open design questions folded in from the 2026-11-11 outside review: (a) **generic action intents**: the client sends specific packets (`c_attack`, `c_cast`, `c_interact`); a generic "use action slot" / "interact with tile and tool" intent would let a new genre add actions without new packets; (b) **schema-driven editor forms**: the F1 forms are typed Kotlin (`ItemForm`, `NpcForm`...), so a collection or field added by a closed realm needs client code; the JSON schemas are already exported, so a generic fallback form built from a server-sent schema for collections the client does not know is possible (typed forms stay for the built-in ones). Decide both while designing the registry.*
- [ ] **8. Layered content instead of copied templates.** Wizard realms copy a whole game folder, so engine or content upgrades never reach them. Make a realm a base game plus local overrides. *Recommend: design together with auto-discovered template manifests.*
- [ ] **9. Revise ADR 0015.** Done in the ADR's revision (crops as growing resource nodes; in-world home lots first, instanced interiors later); owner to confirm before code for steps 2 and 3.
- [ ] **12. Server scale limit noted.** One coordinator lock serialises everything; fine for a showcase and a few dozen players. Later: per-map workers or actors. *Recommend: document as the known limit rather than fix now.*

## Open core and closed realms (outside review 2026-11-11)
From evaluating an outside design conversation against the code (what was already built is recorded in [ADR 0014](/decisions/0014_morgana_realms_universal_client_architecture.md) and the [extensibility map](/reference/extensibility_map.md)). Goal: a closed commercial realm consumes the open engine as a dependency instead of forking it, and other genres (farming and life sims) can add UI and actions without client changes. Items 7 and 8 above are the other half of this story.
- [ ] **Plugin loading for Kotlin hooks.** *In progress (2026-11-12): done are the plugins folder, manifest, class loader per jar, lifecycle (enable, disable, reload), API version check and dependencies (see the [plugin guide](/playbooks/write_a_server_plugin.md)). Veto hooks (`onBeforeAction`) and `messagePlayer` done the same day. The `/plugins` command and a private per-plugin key-value store are done too. Remaining: new event command types (`kotlin_hook` is the route today), per-character plugin data, a permission model.* Before this a "plugin" was a class compiled into the server. Hooks are notifications only: they cannot veto an action, message a player, open UI or register event commands. Design: a plugin manifest and loader, a lifecycle, a compatibility check against the engine version, and hooks that can veto and reply. Lua already covers much of this on the server, so scope it against the scripting system first. *Recommend: design together with the module registry (#7).*
- [ ] **A generic plugin message and server-described UI.** *Done 2026-11-12 as a first slice: capability `plugin_ui`, plugin messages both ways and `PluginWindowDto` windows (text lines, label/value rows, buttons) drawn by the standard client; additive, so no protocol version bump (see the [plugin guide](/playbooks/write_a_server_plugin.md)). Text inputs and a scrolling table were added the same day, and a plugin's windows now close when it is disabled. Remaining: images and item slots, windows that follow map changes, and trying it with a real second genre (farming or a market).* `ServerUiWindowType` has five fixed windows (shop, bank, crafting, dialogue, F1 panel) and there is no generic plugin message. Add a custom-message packet (namespaced id plus a small payload, ignorable by clients that do not know it) and a data-described window (title, grid or list, buttons bound to server events) so another genre can show a stock market or crop-breeding screen with the same client. Needs a capability flag and a protocol version bump.
- [ ] **Realm browser and login follow-ups still open** (from the 2026-11-12 slices; see [realm browser](/client/realm_browser.md)): per-realm latency and online status (a refresh button and background directory fetch are done), region and language fields and filters, realm banners, favourites that follow the account, other tabs of the hub (Direct Join layout), and a gamepad or touch pass over the hub.
- [ ] **Federated realm registries.** The client talks to one directory. Let the user add further registry URLs (like package registries), keeping the default list as the front page, plus a direct-connect tab (already there). Governance of the default list (who runs it, delisting policy) is an owner decision, not code.
- [ ] **Closed-server workflow guide.** A short page for realm builders: consume the open engine as a dependency, the supported extension points, what not to edit, and the rule "if you need to edit the engine, add a generic hook upstream first". Cheap, and prevents drift. Write it before the first closed realm starts.

### Plugin backlog (parked 2026-11-12, not scheduled)
The plugin system works end to end (loader, lifecycle, veto and reply hooks, storage, `/plugins`, data-described windows; see the [plugin guide](/playbooks/write_a_server_plugin.md)). What is left is parked until a real second genre needs it:
- [ ] Images and item slots in plugin windows; windows that follow the player across map changes.
- [ ] Per-character plugin data that travels with the character (`CharacterState` only has `switches` and `variables`).
- [ ] A permission model for plugins (today they are fully trusted code).
- [ ] Engine checks through the module registry instead of the `OptionalModule` enum; a config schema, guard hooks and a client flag per module (item 7 above).
- [ ] Try the design with a real farming or market plugin and fix what it shows.
