---
type: Concept
title: "Game Definition (game.json) and the Formula Language"
description: "Data-defined attributes, vitals, progression and combat formulas in game.json, written in an integer-only expression language; the rules every game runs on."
tags: [domain, game-definition, formulas, scripting]
code: ["core-domain/src/main/kotlin/com/morgana/domain/game/GameDefinition.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/WorldWeather.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/Formula.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/GameDefinitionValidator.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/CompiledGame.kt", "core-domain/src/testFixtures/kotlin/com/morgana/domain/game/MorganaClassic.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/BlankGame.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/GameDefinitionLoader.kt"]
symbols: ["GameDefinition", "WorldWeather", "WeatherZone", "Formula", "GameDefinitionValidator", "CompiledGame", "MorganaClassic", "BlankGame", "GameDefinitionLoader"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-08T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Game Definition (`game.json`) and the Formula Language

**Status: in use.** `WorldSimulation` runs entirely from the `GameDefinition` of the running game: attributes, vitals, progression, combat formulas and the combat rule constants all come from `game.json`. Without a `game.json` the engine runs `BlankGame`. The direction is [ADR 0013](/decisions/0013_engine_game_split_and_lua_scripting.md); the plan is in `docs/GAME_DEFINITION.md`.

## What it is
A `GameDefinition` declares what the engine used to hard-wire:
- **Bag size** (`inventorySlots`, default 24, 8..200): how many slots a character's bag has; existing characters grow to it at login ([inventory](/systems/inventory_interaction.md)).
- **Metadata** (`id`, `name`, optional `description` up to 400 chars, optional `tags` list up to 16 tags used by auto-discovered realm templates and directory entries).
- **UI profile** (`ui`, optional `UiProfile` with booleans `combat`, `spells`, `hotbar`, `minimap`, `chat`, `party`, all `true` by default): sent to the client inside `AuthResult.gameDefinition` so peaceful games (`"ui": { "combat": false, "spells": false }`) hide combat/spell buttons and empty hotbar slots automatically.
- **Paperdoll slots for gear** (Paperdolling v2): an item draws in the `PaperdollSlot` it names, or its equipment slot's default: `WEAPON`/`TOOL` main hand, `SHIELD` off-hand, `HELMET` head, `ARMOR` torso, `BOOTS` feet, `AMMO` and `CLOAK` back, **`GLOVES` the `HANDS` slot** (drawn over the torso and its sleeves, under hair, headgear and held weapons, in every facing), anything else an accessory. Generate the glove art with the `gloves` style of [tilegen](/architecture/tilegen.md). Tests: `PaperdollV2DomainTest`.
- **Sprite geometry & animation states** (`sprites`, optional `SpriteProfile` with `cellWidth`, `cellHeight`, `stateFrames`, `anchor`, `paperdollingV2`): defaults to classic `32 x 32`, `3 x 4` sheets when omitted (`null`). When configured (e.g. `32 x 64` modern tall sprites with `idle`, `walk`, `attack`, `cast`, `harvest`; sheet columns follow the order idle, walk, attack, cast, harvest, jump, hurt. `jump` plays across a leap, `hurt` flinches when damage text lands on a character; a state with 0 frames plays walk, and `hurt` plays idle. A walk of 4 or more frames cycles on the clock; `scales` maps a sprite id to a draw scale of 0.25 to 8, default 1, so a small-drawn character can be shown larger without redrawing its sheet; `uiHeights` maps a sprite id to its visible height in cell pixels, so a short sprite in a tall cell gets its name plate, bars, bubbles and damage text on its head rather than above the empty cell). Items can also carry `paperdollOffHandSpriteId`, the picture used instead of `paperdollSpriteId` when the item is worn in the off-hand slot (a dual-wielded blade drawn in the off-hand pose), `GameDefinitionValidator` validates cell bounds (`8..256`), `walk >= 1`, per-state frame counts (`0..16`), and sheet size limits (`<= 4096`), `GameAssets.scan` enforces `cellWidth * totalFrames` x `cellHeight * 4`, and the renderer slices by the declared cell size, draws anchored bottom-center at the feet, Y-sorts entities/seams by foot Y, and raises world-space UI (HP bars, cast bars, nameplates, chat bubbles, floating text) above `cellHeight`. Setting `"paperdollingV2": true` opts into slot-based, direction-ordered, tinted, and composited paperdolls (`PaperdollSlot`, `PaperdollComposer`).
- **Attributes** (id, name, base, floor, optional cap, allocatable), **vitals** (id, a `max` formula, optional regeneration, and exactly one with `role: "life"`), **progression** (`maxLevel` plus an `xpTable` or an `xpExpr`) and **combat** (`module`: `"morgana_classic"`, `"lua"`, or `"noop"`, plus formulas for melee, ranged, spell, critical chance, dodge chance). Setting `"module": "noop"` selects `NoOpCombatModule` for non-combat realms without writing code. Every formula is a plain string. Attribute and vital ids share one namespace, because formulas use them as variable names.

`GameDefinitionLoader` (`:server-persistence`) reads `<data dir>/game.json` strictly (unknown fields are errors, like `server.json`), validates it, and falls back to the
engine default `BlankGame` when the file is absent. Tool: `.\gradlew.bat :server-app:gameDefinition` validates and summarizes; with `-PcontentArgs="--write"` it creates an
editable `game.json` from the built-in definition. Schema: `docs/schemas/game.schema.json` (drift-tested).

## New-player tips (`onboarding`, optional)
`"onboarding": { "enabled": true, "defaults": true, "tips": [ ... ] }`: rewrite, remove or add the short hints shown once per account (welcome, equip, stat points, skills, repairs, ghosts, low health, level milestones). Without the section the built-in tips apply. Format, triggers and delivery: [new-player tips](/systems/onboarding.md).

## The clock (`clock`, optional)
`"clock": { "dayLengthTicks": 1200, "daysPerSeason": 7, "seasons": ["Spring","Summer","Autumn","Winter"], "startTimePermille": 350, "dayNight": false, "dawnStartPermille": 230, "dayStartPermille": 300, "duskStartPermille": 700, "nightStartPermille": 770, "nightLight": 150 }` (the last five are optional; the values shown are the defaults).
A game with a `clock` section has a calendar; without it there is none (Morgana Classic has none, so it looks exactly as before). Time is the
simulation tick: `WorldClock.at(tick)` gives day, day of season, season, year, time of day (thousandths of a day, 0 = midnight), a phase
(night, dawn, day, dusk) and a light level (150 at night to 1000 by day), all in integers so replays agree. Defaults: 1200 ticks per day
(one minute), 7 days per season, 4 seasons, tick 0 at 08:24. `dayNight: true` opts in to the visual cycle: the client darkens outdoor maps
after dusk. Scripts read it as `world.clock` ([scripting](/systems/scripting.md)); the client computes the same clock from the `serverTick`
in `AuthResult`, `MapStateSnapshot` and `Pong`, so no clock packets are sent.
**Day and night lengths are data.** A game picks how long a day is (`dayLengthTicks`) and where each part of it starts, in thousandths of a day: the world is dark from `nightStartPermille` through midnight to `dawnStartPermille`, brightens through dawn to `dayStartPermille`, is fully lit until `duskStartPermille`, and darkens through dusk to `nightStartPermille`; `nightLight` (0..1000) is the darkest it gets. A 60-minute day (`dayLengthTicks` 72000) with `dawn 100, day 150, dusk 850, night 900` is 12 minutes of night and 48 of day. The boundaries must be in order, each 0..1000. The client reads the same definition, so it needs no code change; a script reads the result as `world.clock.phase`/`light` (the schedule itself is data, not script state, because the client draws it without asking the server). Validation: `dayLengthTicks` 200..1,000,000, 1..365 days per
season, 1..12 unique season names, start time 0..999. Plan for crops and homes that build on it: [ADR 0015](/decisions/0015_plots_crops_and_housing_plan.md).


## Territory (`territory`, optional)
`"territory": { "noBuildRadiusTiles": 6, "minClaimSpacingTiles": 0, "claimKinds": [ { "kind": "claim_totem", "levels": [ { "radiusTiles": 10, "maxPieces": 150 } ], "maxPerOwner": 1, "safeZone": true, "guild": false } ] }`.
Claiming land, on top of world objects: a claim kind names a `worldObjects` kind; each level sets a radius and a piece limit; `guild` makes it the placer's guild's; `safeZone` stops fighting inside it except in an attack window; `maxPerOwner` limits claims per character or guild per map. `noBuildRadiusTiles` keeps placements away from resource nodes and monster spawn tiles; `minClaimSpacingTiles` adds space between claims beyond their radii. Rules and limits: [territory](/systems/territory.md). Validation: kinds exist and are listed once, 1..20 levels, radius 1..200, pieces 1..100,000, `maxPerOwner` 1..1,000.

## World objects (`worldObjects`, optional)
`"worldObjects": { "maxPerMap": 20000, "kinds": [ { "id": "wall", "name": "Wall", "blocksMovement": true, "maxHp": 100, "tags": ["wall"] } ] }`.
The kinds of thing that can be placed in the world and stay there (walls, chests, totems, signs), used by the `worldState` system: [world state](/systems/world_state.md). Per kind: `id` (`a-z 0-9 _`, unique), `name`, `blocksMovement` (default true), `maxHp` (default 100), `tags`, and `spriteTilesetId`/`spriteTileX`/`spriteTileY` for the future renderer. An item places one when its `placesObject` names the kind. Validation: ids, duplicates, `maxHp` 1..10,000,000, `maxPerMap` 1..500,000.

## Class passives (`classPassives`, optional)
`"classPassives": { "1": { "vitalMaxPercent": { "hp": 5 }, "armorPercent": 5 }, "5": { "damagePercent": 5, "moveSpeedPercent": 5 } }`.
A small standing bonus per class id, so a soft class template (anyone may use any gear and skill) still has a flavour. Every field is a percentage (-90 to 500) and defaults to no change; a class with no entry has none. `vitalMaxPercent` (by vital id) raises maximum vitals; `damagePercent` is applied to all damage a character deals to monsters and players (melee, ranged, spells, action skills and the dual-wield off-hand strike); `armorPercent` to the armour their gear gives; `healingPercent` to the healing they cast; `moveSpeedPercent` to movement speed (stacks with mounts and sprint). Rounded half up; an empty or missing passive changes nothing. Validation: percent range, vital ids must exist, class ids 1 or more (a class id that does not exist is not checked). Not covered by an end-to-end test: movement speed (the scaling and the hook exist). Tests: `ClassPassiveTest`.

## Tuning (`tuning`, optional)
`"tuning": { "harvestCooldownTicks": 10, "shopRangeTiles": 6, "pickupRangeTiles": 2, "unarmedDamage": 2, "plusWeaponDamage": 3, "plusArmorDefense": 2, "plusRingHp": 15, "plusPercentPerLevel": 0, "maxGuildMembers": 50, "maxPartySize": 0, "actionTargetRadiusPixels": 12 }`.
Gameplay numbers a game sets without engine code (mandate 12 in `AGENTS.md`). Every field is optional and its default is the value the engine always used, so a game without the section, or leaving a field out, plays as before; the values below are the defaults.
| Field | Meaning |
| :--- | :--- |
| `harvestCooldownTicks` (0..1200) | Ticks between resource-node swings (20 = 1 s). |
| `shopRangeTiles`, `pickupRangeTiles` (1..50) | How far a player may walk from an open shop, and how near a ground item must be to pick it up. Applied on the server and in the client's auto-loot, loot pose and shop window. |
| `unarmedDamage` (0..100000) | Damage of a bare-handed swing. |
| `plusWeaponDamage`, `plusArmorDefense`, `plusRingHp` | What each `+` level adds, flat (weapons, armour/shields/helmets/boots, rings/amulets). |
| `plusPercentPerLevel` (0..100) | Percent of a weapon's own damage or armour's own defence each `+` level adds on top of the flat amounts (`3` is +3% per level; `0` is off). Integer maths, rounded down. Set the flat values to 0 for a purely percentage system. |
| `maxGuildMembers` (1..1000) | Guild size. |
| `maxPartySize` (0..1000) | Party size; **0 means no limit** (the engine used to have none). Enforced when inviting and when accepting, so a stale invitation cannot overfill a party. |
| `actionTargetRadiusPixels` (0..200) | How big a body counts for action-combat hitboxes. |

The gear values reach `EquipmentLoadout` through one parameter object, `GearRules` (`GameDefinition.gearRules`, which also carries `durability.brokenStatPercent`), so the stat totals, the combat code and the bag tooltips all agree. Not tunable yet: the action-combat combo grace (its limit is checked when action skills are validated; see [hard-coded gameplay constants](/issues/hardcoded_gameplay_constants.md)). Tests: `TuningTest`, `PartySizeTest`.

## Schedules (`schedules`, optional)
`"schedules": [ { "id": "cleansing", "kind": "WEEKLY", "timezone": "America/New_York", "dayOfWeek": "SATURDAY", "time": "10:00", "warningsSeconds": [3600, 900, 60] } ]`.
Real-world-time events run by the `scheduler` system: a weekly or daily time in a time zone, an interval, or a one-off date, each with advance warnings announced in chat and passed to Lua (`register.schedule`) and other systems. Full field list, rules and the determinism design are in [scheduler](/systems/scheduler.md). Validation: at most 64, unique ids, a real time zone, `HH:mm` times, 1 to 8 distinct warnings of 1 to 2,592,000 seconds.

## Gear wear (`durability`, optional)
`"durability": { "enabled": true, "weaponWearChancePercent": 10, "armorWearChancePercent": 10, "wearPerHit": 1, "repairPricePercent": 20, "brokenStatPercent": 0 }`.
Gear loses durability (100 down to 0) as it is used and is repaired by an event `repair_gear` step ([optional modules](/server/optional_modules.md), `durability`). A broken piece stays equipped and counts for `brokenStatPercent` of its bonuses: **0 (the default) means it adds nothing until repaired**; a game that wants a harsh-but-playable penalty sets, for example, 10, and a broken weapon then still swings at 10% of its damage. The percentage applies to attribute, vital, armour, damage, crit, leech and gem bonuses, rounded half up per piece; weapon range, speed and style are unchanged. Validation: 0..100. Tests: `DurabilityTest`.

## Weather zones (`weather`, optional)
`"weather": { "zones": [ { "id": 0, "name": "Valley", "periodTicks": 2400, "seed": 11, "states": [ { "type": "NONE", "weight": 5 }, { "type": "RAIN", "intensity": 0.5, "weight": 2 } ] } ] }`.
Weather is a **world or zone feature**, not something each map stores. A *zone* is a named region; a map joins one through its `zoneId` (0 = the default world zone) and says whether it is an **interior** (`isIndoors`) or an exterior. Every `periodTicks` the zone picks its next weather from `states` by weight: `WorldWeather.inZone(zone, tick)` is a pure function of the zone's `seed`, its id and the tick, so the server, every client and a replay agree and **nothing is sent over the wire** (the client reads the same `serverTick` it uses for the clock). `WorldWeather.effective(map, definition, tick)` is what a map shows: an interior is always clear, an exterior shows its zone's weather (clear if the zone is not defined).
**A game with no `weather` section keeps the older behaviour exactly**: each map's own fixed `weather` / `weatherIntensity` (kept in the map files, no longer edited in the F1 editor) applies, interior or not, so Morgana Classic is unchanged. Validation: at most 64 zones, unique non-negative ids, 1 to 40 character names, `periodTicks` 200..1,000,000, 1 to 16 states with weight 1..1000 and intensity 0..1; content validation rejects a map whose `zoneId` is not a defined zone (when the game defines zones). Not yet built: an admin command to force weather, and an in-editor zone editor (zones are authored in `game.json`).

## Loot rules (`loot`, optional)
Rarities and affixes that dropped gear rolls (Diablo-style loot): `"loot": { "rarities": [...], "affixes": [...] }`. Without the section items never roll. Format, rolling rules, validation and where rolls show are on [loot rarities and affixes](/systems/loot_and_affixes.md).

## Map settings: interior and instanced
`MapDefinition.isIndoors` is the **interior / exterior** flag: interiors ignore weather and the day and night cycle. `MapDefinition.instanced` marks a map that is copied per party, guild, or solo player (`INSTANCED_DUNGEON` ruleset; see [instanced maps](/systems/instancing.md)). It is stored, validated (an instanced map may not have neighbouring maps, because instances are always entered through a warp), and editable in the map properties panel. Per-owner persisted home instances are still planned; see [farming and housing](../../docs/FARMING_AND_HOUSING.md).

## The formula language (`Formula`)
Pure integer functions of named inputs: no floating point, loops, strings, randomness or time.
* Operators (high to low): unary `-` `!`; `*` `/` `%`; `+` `-`; `<` `<=` `>` `>=`; `==` `!=`; `&&`; `||`; `c ? x : y`. `/` and `%` truncate toward zero like Kotlin.
* Comparisons and logic give 0 or 1; any non-zero value is true; `&&`, `||` and `?:` evaluate only what they need.
* Functions: `min`, `max`, `clamp`, `abs`, `mulDiv` (exact intermediate product), `pow` (exponent 0 to 16), `isqrt`.
* Steps: `name = expression;` before the final expression (at most 16).
* Overflow, division by zero and out-of-range arguments throw `FormulaEvaluationException`; arithmetic never wraps. Limits: 1000 characters, 200 parts, 16 steps, 40 levels of nesting.

Variables by slot: vital `max` has `level`, every attribute and `bonus`; regeneration has `level` and every attribute; `xpExpr` has `level`; combat formulas have `a.<id>` and
`d.<id>` plus `weapon`, `armor`, `ammo` (ranged), `crit`, `basePower`, `scales`, `heals` as each slot provides.

## Validation (`GameDefinitionValidator`)
Pure and deterministic; reports every problem with a JSON pointer. Besides structure, names and limits, each formula is **trial-evaluated across the extremes of its inputs**
(attribute floor and cap, level 1 to `maxLevel`, item values up to 100,000), so an overflow or a division by zero stops the load instead of surfacing in a fight.
`CompiledGame.compile` validates and parses once and offers typed calls (`vitalMax`, `regenAmount`, `xpToNext`, `meleeDamage`, `rangedDamage`, `spellOutput`,
`critChanceBp`, `dodgeChanceBp`).

## The Morgana Classic fixture and the oracle
`MorganaClassic.definition` (a `:core-domain` test fixture, `testFixtures`; no shipped code may use it) reproduces the original rules exactly, using integers: the XP curve is a 99-entry table generated from `round(100 * level^1.65)`, and critical
damage is `(3*base + 1)/2`, which equals `round(base * 1.5)` for whole numbers. `MorganaClassicOracleTest` compares it with a copy of the original formulas
(`ClassicOracle`) over tens of thousands of generated inputs, every XP level and every small critical-damage case.

Tests: `FormulaTest`, `GameDefinitionValidatorTest`, `MorganaClassicOracleTest` (`:core-domain`), `GameDefinitionLoaderTest` (`:server-persistence`), `GameCommandTest` (`:server-app`).
