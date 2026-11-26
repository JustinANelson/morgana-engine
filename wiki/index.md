---
okf_version: "0.2"
---
# Morgana ORPG Wiki

Maintenance knowledge base for Morgana ORPG, written for human and AI contributors. Pages are OKF concepts; see [the wiki guide](../docs/WIKI_GUIDE.md) for conventions and workflows.

## Start Here

* [Glossary](/glossary.md) - Definitions of the terms used across the Morgana codebase and documentation.
* [Morgana ORPG Overview](/overview.md) - What Morgana ORPG is, its design pillars, the module map, and where to start reading.

## Architecture

* [Build and Toolchain Versions](/architecture/build_toolchain.md) - Pinned versions of Gradle, Kotlin, JDK, and every major library.
* [Module: client-android](/architecture/client_android.md) - The Android launcher with LibGDX natives packaging and emulator loopback defaults.
* [Module: client-core](/architecture/client_core.md) - The platform-independent LibGDX client: state, renderer, HUD, F1 editor, networking, assets, audio.
* [Module: client-desktop](/architecture/client_desktop.md) - The LWJGL3 desktop launcher, including the embedded local server.
* [Module: client-web](/architecture/client_web.md) - The browser client: client-core compiled to JavaScript by gdx-teavm, talking to the server over WebSocket.
* [Module: core-domain](/architecture/core_domain.md) - The pure Kotlin rules engine: models, the 20 TPS simulation, and content validation.
* [Module: dev-tools](/architecture/dev_tools.md) - Repository tooling that keeps the wiki healthy: lint, index generation, and change-impact lookup.
* [Module: game-art](/architecture/game_art.md) - Tooling, not engine: the shared 32 x 48 sprite kit, the public games' art exporters and the command line, on top of the generic :tilegen generators.
* [Module: map-builder](/architecture/map_builder.md) - Tooling, not engine: compiles map recipes (JSON terrain, paths, walls, props, warps and enemy camps) into map documents using the tile index a tileset export wrote.
* [Module Graph and Dependency Rules](/architecture/module_graph.md) - The Gradle module layout, who may depend on whom, and why the boundaries exist.
* [Module: server-app](/architecture/server_app.md) - The dedicated authoritative server: Netty transport, coordinator, tick loop, owner policy, hot reload, and CLI tools.
* [Module: server-persistence](/architecture/server_persistence.md) - JSON document collections, repositories, content loader, schema exporter, Tiled converter, and the game scaffolder.
* [Module: shared-protocol](/architecture/shared_protocol.md) - Client/server packet definitions and the JSON/CBOR codec.
* [Module: tilegen](/architecture/tilegen.md) - Creator tool: engine-free pixel-art generator (themed tiles, autotiles, props, structures, paper-doll characters and monsters, JSON tileset specs) with a command-line tool; only the starter tileset is used at runtime.

## Domain Concepts

* [Access Levels and Permissions](/domain/access_levels.md) - The five access tiers and the permission flags each tier grants.
* [Action-Combat Content: Skills and Weapon Sets](/domain/action_combat_content.md) - The action_skills and weapon_sets collections: skills timed in ticks with hitboxes, armor and movement windows, and combo graphs of input-token edges, with the rules that reject a bad timeline or graph.
* [Character State](/domain/character_state.md) - What a persisted player character contains and which parts are derived.
* [Determinism and Seeded Randomness](/domain/determinism_and_rng.md) - How the simulation stays reproducible: no wall clock, injected RNG, ordered collections.
* [Game Definition (game.json) and the Formula Language](/domain/game_definition.md) - Data-defined attributes, vitals, progression and combat formulas in game.json, written in an integer-only expression language; the rules every game runs on.
* [Maps, Layers, Tile Attributes, and Morals](/domain/maps_and_tiles.md) - The map data model: size limits, six layers, neighbor links, tile attribute types, and map rulesets.
* [Simulation Events](/domain/simulation_events.md) - How the pure simulation reports what happened, and how the server maps events to packets.
* [Stats, Vitals, and Combat Formulas](/domain/stats_and_formulas.md) - Data-defined attributes, vital pools, and the exact integer combat and progression formulas compiled by the engine.

## Simulation Systems

* [Action Combat Module](/systems/action_combat.md) - The Kotlin action combat module (combat.module action): combo graphs of timed skills, aimed hitboxes, dodge frames and guards, with client input tokens.
* [Admin Commands and Live Content Edits](/systems/admin_live_edit.md) - Slash commands, permission checks, and the applyLive*Edit hot-swap methods.
* [Arena](/systems/arena.md) - Instanced team fights: the /arena queue, rating-based pairing, countdown, no loss on death, Elo rating changes and rewards.
* [Boss Encounters](/systems/boss_encounters.md) - Scripted boss fights: phases by life percent, adds, telegraphed ground attacks, dungeon difficulty tiers and weekly lockouts.
* [Combat, Status Effects, Death, and Loot](/systems/combat.md) - Pluggable CombatModule port, RealtimeCombatModule, LuaCombatModule, NoOpCombatModule, generic actions, melee, ranged, spells, projectiles, PvP, status effects, regeneration, death penalties, loot, and XP sharing.
* [Combat stance (SAFE / PvE / PvP)](/systems/combat_stance.md) - A stance each character chooses: only two PvP-stance characters can hurt each other. Per-map permission, arming and disarming times, forced PvP in a siege window, and a shared 'in combat' record.
* [Deterministic Replay](/systems/deterministic_replay.md) - Input-log recorder, replay player, state hashing, JSON codec, and the golden-master test workflow.
* [Shops, Bank, and Crafting](/systems/economy.md) - Buy/sell rate rules, bank storage, and recipe crafting including crafting-station enforcement.
* [Page Events, Dialogue, and Quests](/systems/events_quests.md) - How event pages are chosen, which commands exist, and how quest objectives progress.
* [Exploration: Fog of War and the World Map](/systems/exploration.md) - Per-character explored chunks, fog on the minimap, the World Map window and its layout: Exploration, WorldMapLayout, the exploration module and packets.
* [Friends List](/systems/friends.md) - A per-character friends list with online and offline notices: FriendRules, the friends module, packets and the Friends window.
* [Guilds](/systems/guilds.md) - Guild ranks, invitations, kicks and succession: GuildRules, the coordinator flow, the roster packet and the Guild window.
* [Ignore List](/systems/ignore.md) - A per-character ignore list that hides chat, whispers, mail and invitations from chosen players: IgnoreRules, the ignore module, commands and the window.
* [Instanced Maps](/systems/instancing.md) - How maps marked instanced become private per-party copies: entering, sharing, rejoining, closing, limits, and what counts as content.
* [Interaction, Inventory, and Equipment](/systems/inventory_interaction.md) - Interact-facing, harvesting, pickup, item use, equipment, dropping, and stat allocation.
* [Loot Rarities and Affixes](/systems/loot_and_affixes.md) - Diablo-style random item properties: the loot section of game.json, how dropped gear rolls a rarity and affixes, how rolls travel with the item, count while worn, change names, colours and prices.
* [Mail](/systems/mail.md) - Player-to-player letters with an optional item attachment: the MailRules domain rules, the mail module, packets, storage and the Mail window.
* [Player Market](/systems/market.md) - Fixed-price player listings for the world currency: MarketRules, escrow, the sale tax, payment by mail, expiry and the Market window.
* [Movement](/systems/movement.md) - Rules for player movement: modes, validation order, border crossing, and tile attribute effects.
* [NPC AI](/systems/npc_ai.md) - How NPCs respawn, acquire targets, attack, leash, and wander.
* [New-Player Tips (Onboarding)](/systems/onboarding.md) - Short hints shown once per account as they become relevant: a welcome, equipping, stat points, skills, gear repair, ghosts, low health and level milestones; built in, rewritable per game in game.json.
* [Pets](/systems/pets.md) - Companions a player adopts with an item, summons with /pet, who follow across maps, fight for the owner and grow with their XP.
* [Population scaling](/systems/population_scaling.md) - How fully a map is supplied by the number of players on it: quiet maps refill only part of their monsters and regrow resource nodes more slowly. Opt-in, deterministic, configured in game.json.
* [Procedural Dungeon Floors](/systems/procedural_floors.md) - Seeded rooms-and-corridors floors for roguelike runs: the FloorGenerator recipe on an instanced map, how each party copy is generated, stairs, the boss and exit, validation.
* [Role-Play Chat](/systems/roleplay_chat.md) - The rolePlayChat module: /me actions, out-of-character talk, shouts, quiet speech and staff narration, speech range, and how older clients see it.
* [Scheduler](/systems/scheduler.md) - Real-world-time events (a weekly reset, a daily bonus, a booked window) with advance warnings, announced in chat and passed to Lua and other systems, deterministic through a recorded wall-clock input.
* [Script Host and Sandbox](/systems/scripting.md) - Sandboxed Lua scripting runtime powered by Luak with deterministic instruction budgets, memory ceilings, world queries, and declarative effects.
* [System Seams and Installed Systems](/systems/system_seams.md) - How the kernel reaches the systems built on it: twelve seam interfaces, WorldSimulation.install, the installed systems, the boundary test and what stays in the kernel.
* [Territory (claims)](/systems/territory.md) - Claiming land: only owners build inside a claim, claims keep apart and hold limited pieces, claims can be safe zones opened to attack by timed windows. Built on world state and the scheduler.
* [Tick Loop and System Layout](/systems/tick_loop.md) - The exact order of work in one simulation tick and how WorldSimulation delegates to system files.
* [Player Trading](/systems/trading.md) - The two-stage lock-and-confirm trade protocol and its safeguards.
* [World State (placed objects)](/systems/world_state.md) - Things placed on a map that stay there (walls, chests, totems): deterministic, saved per map, and open to Kotlin systems and Lua through a placement gate and an observer.

## Server

* [Admin Web Panel and REST API](/server/admin_panel.md) - The operator's web panel and JSON API: how to turn it on, the token, every endpoint, what it can do, the security rules and how it is built.
* [Agent Control (admin API for LLMs and scripts)](/server/agent_control.md) - How an LLM or script makes accounts and characters, logs in without a password, and changes the live world through the admin API, the morgana-ctl CLI and its MCP server.
* [Load testing with bots](/server/load_testing.md) - A bot load test (morgana-ctl loadtest.mjs) that reports tick time and per-client bandwidth by packet type, and the numbers before and after the movement and projectile packets were slimmed.
* [Characters per Account](/server/characters.md) - How one account holds several characters: the list after login, creating, choosing and deleting, the rules, and what older clients see.
* [Connection Limits and IP Bans](/server/connection_limits.md) - Per-address connection caps, connection-rate limits, strikes and temporary bans, the pre-login timeout, and operator commands.
* [World Coordinator](/server/coordinator.md) - The single synchronized authority: sessions, authentication, packet handling, persistence triggers, and event dispatch.
* [Dev Login Tickets](/server/dev_login.md) - One-time login tickets so automation (an LLM driving a client, a test script) can enter the world without typing a password; opt-in per server.
* [Game-Owned Art](/server/game_assets.md) - A game ships its own tileset and sprite PNGs in its assets/ folder; the server validates, advertises and serves them.
* [Game Wiki: a Browsable Wiki Generated from the Data Files](/server/game_wiki.md) - A cross-linked wiki of every item, monster, spell, quest, recipe, shop, resource, map and class, with a Balance report, built from game.json and the content JSON; static files or served live at /wiki/.
* [Kotlin Hook API](/server/hook_api.md) - Typed server plugin callbacks (login, items, spells, kills, deaths, chat, custom events) and where game rules now live.
* [Moderation Tools](/server/moderation.md) - Bans, mutes, kicks with reasons, a player report queue and an audit log: commands, who may use them, what is stored and what is not done yet.
* [New Realm Wizard](/server/new_realm_wizard.md) - The guided flow (and matching CLI) for creating a realm: pick a starting template and which optional systems are on.
* [Optional Modules and server.json](/server/optional_modules.md) - Which systems an operator can switch off, how server.json configures them, and where each switch is enforced.
* [Owner Policy](/server/owner_policy.md) - Who becomes Owner (access rank 4): configured owner names or the first local account, never a magic username.
* [Packet Flood Limits](/server/packet_limits.md) - Per-connection rate budgets, field-length caps, kick rules, and the raw message ceiling that protect the server from floods.
* [Persistence Layout on Disk](/server/persistence_layout.md) - The server-data directory tree, file naming, JSON settings, and atomic write behavior.
* [Server Runtime](/server/server_runtime.md) - The single assembly point for a running server, shared by the dedicated server and the desktop embedded server.
* [TLS and Invite Codes](/server/tls.md) - Encrypted connections: modes, automatic certificates, fingerprint pinning via invite codes, and how clients decide whom to trust.
* [World Library and Embedded Host](/server/world_library.md) - Independent per-world data folders under worlds/, and the desktop client's one-click New World / Play flow.

## Client

* [Accessibility](/client/accessibility.md) - Rebindable keys, colour-blind palettes, reduce motion and a larger interface: the settings, how they are stored and applied, how the palettes were checked, and what is not covered.
* [Assets, Audio, and Realm Asset Pipeline](/client/assets_and_audio.md) - Procedural art, retro audio, content-addressed asset caching, and remote realm asset synchronization.
* [Client State and Prediction](/client/client_state_and_prediction.md) - How ClientWorldState consumes packets, smooths motion, and predicts local movement.
* [F1 Live Editor](/client/f1_editor.md) - The in-game editor panel: tabs, visual asset pickers, map tools, event/animation studios, and how saves reach the server.
* [Input and HUD](/client/input_and_hud.md) - Keyboard/mouse bindings, HUD windows, hotbar, minimap, and touch controls.
* [Local Worlds (Host Your Own Realm)](/client/local_worlds.md) - The desktop hub's Local Worlds tab: the worlds on this machine, what is being hosted, and play, share, stop, rename, delete and open-folder for each.
* [Realm Browser (Server Select)](/client/realm_browser.md) - The server select screen: a two-pane realm browser with search, tag filters, favorites and recents pinned on top, a details pane, and what it still lacks.
* [Reconnect and Session Resume](/client/reconnect.md) - How a client whose connection dropped (backgrounded app, locked phone, network loss) returns to its character: heartbeat, resume tokens, backoff, takeover.
* [World Rendering](/client/rendering.md) - How MorganaWorldRenderer draws maps, neighbors, entities, lighting, and overlays.

## Protocol

* [Client Packets (c_*)](/protocol/client_packets.md) - Every client-to-server packet and its purpose.
* [Server Packets (s_*)](/protocol/server_packets.md) - Every server-to-client packet and when it is sent.
* [Session Lifecycle](/protocol/session_lifecycle.md) - What happens from connect to disconnect, including first-account rules and saves.
* [Wire Format and Transport](/protocol/wire_format.md) - TCP framing, text encoding, the unused CBOR codec, size limits, and the tick scheduler.

## Content & Data Pipeline

* [Blank Game and the Seeded World](/content/blank_game.md) - The games/blank scaffold, which is also what an empty server data directory is seeded with (the repository also ships the reference roguelike).
* [Content Collections](/content/content_collections.md) - All fourteen content types: directory, file name, model class, JSON Schema, and reference rules.
* [Content Pipeline](/content/content_pipeline.md) - The single validate, persist, hot-reload, broadcast path that GUI edits, file edits, and agents all share.
* [Hot Reload](/content/hot_reload.md) - How file edits and /reload update a running server, and what is deliberately not applied.
* [Ashen Keep: the Reference Co-op Dungeon Crawler & Roguelike](/content/roguelike_game.md) - games/roguelike, the reference dungeon crawler and roguelike realm: Ashen Keep multiplayer hub and Floor 1 (The Sunken Crypt) built with the Trevana Realms tileset and paperdoll workflow.
* [Tiled Map Conversion](/content/tiled_conversion.md) - Round-tripping maps through Tiled .tmj with full fidelity for attributes, NPC spawns, and event ids.
* [Tutorial Village: the Getting-Started Game](/content/tutorial_village.md) - games/tutorial-village, the finished game of docs/GETTING_STARTED.md (a village, a gardener, a kill quest and its reward), kept playable by TutorialVillageGameTest.
* [Validation Rules and Issue Codes](/content/validation_rules.md) - Every check the content validator performs and the issue codes it emits.
* [White River Online](/content/white_river_game.md) - games/white-river, an original sandbox ORPG: a town and four zones joined by open seams, 3 classes, life skills at four stations, and the first action-combat weapon set.
* [World Packs](/content/world_packs.md) - Shareable, validated folder or zip exports of a world's content, and the safe import that accepts them.

## Decisions

* [ADR 0001: Deterministic, Pure Domain Core](/decisions/0001_deterministic_domain_core.md) - All game rules live in a pure Kotlin module with seeded randomness and tick-based time.
* [ADR 0002: JSON Document Persistence](/decisions/0002_json_document_persistence.md) - World and player data are one human-readable JSON file per document behind a storage port.
* [ADR 0003: Kotlin + LibGDX with a Shared Client Core](/decisions/0003_kotlin_libgdx_shared_client.md) - One Kotlin codebase for server and clients, with LibGDX giving desktop and Android from a single client-core.
* [ADR 0004: One Synchronized Coordinator](/decisions/0004_single_synchronized_coordinator.md) - All server state changes serialize through one lock instead of fine-grained concurrency.
* [ADR 0005: Data First, GUI Optional](/decisions/0005_data_first_content.md) - Content is plain JSON files authored equally by humans, scripts, agents, and the F1 GUI through one pipeline.
* [ADR 0006: Decompose WorldSimulation into Extension-Function Systems](/decisions/0006_system_decomposition.md) - Why the 2,466-line simulation class became a facade plus per-domain system files.
* [ADR 0007: Validate Before Apply (Strict F1 Saves, All-or-Nothing Reload)](/decisions/0007_validate_before_apply.md) - Invalid content never reaches the live world, with one deliberate relaxation: links between maps may dangle.
* [ADR 0008: Maintain the Wiki as an OKF Bundle with Lint](/decisions/0008_okf_wiki.md) - Why the project knowledge base uses Open Knowledge Format v0.2 and a code-drift linter.
* [ADR 0009: JSON Text Frames on the Wire (CBOR Deferred)](/decisions/0009_json_wire_format.md) - The current wire format is JSON over length-prefixed TCP; CBOR exists but is unused.
* [ADR 0010: Rename FADE to Morgana ORPG](/decisions/0010_morgana_rename.md) - The product and package rename, what changed, and what was intentionally left alone.
* [ADR 0011: Security Baseline](/decisions/0011_security_baseline.md) - Salted password hashing, explicit owner bootstrap, protocol versioning, server-side rate limits, and login throttling.
* [ADR 0012: TLS with Fingerprint Pinning](/decisions/0012_tls_fingerprint_pinning.md) - Why player-hosted worlds use automatic self-signed certificates verified by invite-code fingerprints instead of certificate authorities.
* [ADR 0013: Engine/Game Split with Data-Defined Stats and Lua Scripting (Luak)](/decisions/0013_engine_game_split_and_lua_scripting.md) - Direction for opening the engine to other developers: games are directories of data and sandboxed Lua scripts, stats are data-defined, combat is replaceable, and Luak is the Lua runtime.
* [ADR 0014: Morgana Realms Universal Client Architecture](/decisions/0014_morgana_realms_universal_client_architecture.md) - Decoupled PlayerWorlds-style multi-realm discovery, content-addressed asset caching, per-realm isolated storage, and universal client hub.
* [ADR 0015: Plots, Crops, Owned Homes and the World Clock](/decisions/0015_plots_crops_and_housing_plan.md) - Plan to give the engine plantable plots, owned instanced homes and a deterministic world clock, driven by what a content-only farming village could not express.
* [ADR 0016: Map Instancing for Co-op Roguelike Runs](/decisions/0016_map_instancing_and_coop_roguelikes.md) - Instanced maps become private per-party copies with reserved map ids, as the first step toward hub-and-dungeon co-op roguelikes.
* [ADR 0017: White River Online and Its Action-Combat Path](/decisions/0017_white_river_online.md) - White River Online is an original sandbox ORPG built as a game folder; action combat arrives as a new CombatModule, prototyped in Lua, and the tick rate stays at 20 TPS.
* [ADR 0018: Core, Systems, Templates and Plugins](/decisions/0018_core_systems_plugins_templates_terminology.md) - The engine's four layers: Core (every game), Systems (switchable features, grouped by genre tags), Templates (starting games that switch systems) and Plugins (sandboxed Lua that overrides how systems work in one game).

## Playbooks

* [AI-Assisted Mapping](/playbooks/ai_assisted_mapping.md) - Noise for the land, a picture and text summary to read it, then an LLM places roads, camps and landmarks on what exists.
* [Add a New Content Type](/playbooks/add_content_type.md) - Every file to touch when adding a content collection (model to editor), in order.
* [Add an Event Command](/playbooks/add_event_command.md) - Steps to add a new page-event command.
* [Add a Network Packet](/playbooks/add_packet.md) - Checklist for a new client or server packet.
* [Add a Simulation Rule](/playbooks/add_simulation_rule.md) - How to add gameplay behavior in the right system file while preserving determinism.
* [Add a Tile Attribute](/playbooks/add_tile_attribute.md) - Steps to introduce a new TileAttribute subtype end to end.
* [Author Content as Files (Humans and Agents)](/playbooks/author_content_as_files.md) - The file-first content authoring loop with validation and hot reload.
* [Browser Smoke Test](/playbooks/browser_smoke_test.md) - Run and extend the headless-Chrome smoke test of the web client: what it covers, how the client describes itself to it, and how to add a step.
* [Build and Try the SDK](/playbooks/build_the_sdk.md) - Package the engine as a self-contained SDK folder and zip for other developers, what is in it, and how to test it as a newcomer would.
* [Change a Balance Formula](/playbooks/change_formula.md) - Where combat and stat math lives and how to change it safely via GameDefinition.
* [Debug a Determinism Problem](/playbooks/debug_determinism.md) - Checklist when two runs with the same seed and inputs diverge.
* [Make Your Game's Art](/playbooks/make_game_art.md) - A creator's guide to giving a game its own tilesets, characters, icons, worn gear, projectiles and effects: file layout, sizes, the export, the art check and the F1 Art panel.
* [Play on a Phone (Browser, Tailscale)](/playbooks/play_on_a_phone.md) - Serve the browser client from the game server with --web and play from a phone's browser over Tailscale or a home network: what makes it work and what to check when it does not.
* [Large Rename or Package Refactor](/playbooks/refactor_rename.md) - A proven procedure for renaming packages and types across all modules without breaking determinism.
* [Run Locally](/playbooks/run_local_dev.md) - Start the server and clients, seed data, and find the right ports and directories.
* [Update the Wiki](/playbooks/update_wiki.md) - Ingest, query, and lint workflows for keeping the OKF wiki accurate as the code changes.
* [Verification Gate](/playbooks/verification_gate.md) - What .\gradlew.bat check runs, how to force tests to really execute, and how to fix common failures.
* [Write a Server Plugin](/playbooks/write_a_server_plugin.md) - How to build, install, check and manage a Kotlin plugin jar for the dedicated server: manifest, entry class, hooks, modules, lifecycle and limits.

## Reference

* [Competitive Landscape: Intersect, Mirage, Player Worlds](/reference/competitive_landscape.md) - How Morgana positions itself against the engines that inspired it, separating sourced facts from unverified claims.
* [Code and Repository Conventions](/reference/conventions.md) - Naming, style, testing, IDs, units, and commit rules used throughout the project.
* [Extensibility Map: What Can Be Changed Without Editing the Engine](/reference/extensibility_map.md) - Verified inventory of today's extension points (content, events, hooks, formulas, modules) and of the game rules still hard-coded in the engine, with coupling counts.
* [Gradle Tasks](/reference/gradle_tasks.md) - Every project task a contributor or agent needs, with arguments.
* [Roadmap Archive](/reference/roadmap_archive.md) - Finished roadmap items and backlog entries, moved out of the roadmap status page to keep it short.
* [Roadmap and Status](/reference/roadmap_status.md) - Open roadmap items and backlogs; the authoritative plan is docs/DESIGN.md. Finished items are in the roadmap archive.
* [Security Model](/reference/security_model.md) - Authentication, owner bootstrap, password storage, protocol versioning, rate limits, and the remaining risks.
* [Testing Strategy](/reference/testing_strategy.md) - Where tests live, what each suite covers, and how to run them reliably.
* [Wiki Conventions (OKF)](/reference/wiki_conventions.md) - How this OKF v0.2 bundle is structured, the required frontmatter, and what the linter enforces.

## Known Issues

* [F1 Editors Cannot Author Everything a Game Needs](/issues/f1_editor_authoring_gaps.md) - Parts of a game that exist only as JSON today: game.json, classes, floor recipes and scaling, action skills and weapon sets, server.json modules. A full roguelike (or any game) still needs a text editor for these.
* [ServerNoticeAndCooldownTest Fails Intermittently Under Load](/issues/flaky_cooldown_notice_test.md) - client-core's ServerNoticeAndCooldownTest ('the server's exact cooldown replaces the estimate') failed twice in a full gradlew check run alongside heavy tasks and passed on every rerun, so it looks timing-sensitive.
* [Gameplay Numbers Still Hard-Coded in the Engine](/issues/hardcoded_gameplay_constants.md) - Constants in core-domain that a game designer might want to change but that still need a code change: the action-combat combo grace, social list limits and a few internal timings. Most of the first audit is fixed.
