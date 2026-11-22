---
type: Concept
title: "Action-Combat Content: Skills and Weapon Sets"
description: "The action_skills and weapon_sets collections: skills timed in ticks with hitboxes, armor and movement windows, and combo graphs of input-token edges, with the rules that reject a bad timeline or graph."
tags: [combat, content, action-combat, white-river, validation]
status: stable
code: ["core-domain/src/main/kotlin/com/morgana/domain/content/ActionCombatModels.kt", "games/white-river/scripts/action_combat.lua", "game-tests/src/test/kotlin/com/morgana/gametests/WhiteRiverActionCombatTest.kt", "core-domain/src/main/kotlin/com/morgana/domain/validation/ActionCombatValidation.kt", "core-domain/src/test/kotlin/com/morgana/domain/combat/ActionCombatValidationTest.kt", "server-persistence/src/test/kotlin/com/morgana/persistence/ActionCombatContentFilesTest.kt", "docs/schemas/action_skills.schema.json", "docs/schemas/weapon_sets.schema.json"]
symbols: ["ActionSkillDefinition", "WeaponSetDefinition", "SkillHitbox", "SkillHit", "ComboEdge", "ComboEntry", "InputTokens", "ActionCombatLimits", "ActionCombatValidation", "free_cycle", "unreachable_skill", "outside_active_window"]
generated: { by: claude-code/claude-fable-5-2, at: 2026-11-15T22:00:00Z }
stale_after: 2027-05-14T00:00:00Z
---

# Action-Combat Content: Skills and Weapon Sets

Added 2026-11-15 as phase A1 of [ADR 0017](/decisions/0017_white_river_online.md). Two [content collections](/content/content_collections.md) describe action combat as data. The `realtime` [combat module](/systems/combat.md) ignores them. Since phase A2 a **Lua prototype** steps them (see below); the Kotlin module that replaces it is A5 in `games/white-river/ROADMAP.md`. Today they load, validate, export schemas, hot-reload into `WorldSimulation.actionSkills` / `weaponSets`, and count in the replay content fingerprint (only when a game has some, so other games keep theirs).

Units are integers: **ticks** counted from the tick a skill starts (tick 0; a tick is 50 ms), and **pixels** along the character's facing (`forward`) and to its right (`side`); a tile is 32.

## A skill (`action_skills/action_skill_NNN.json`, `ActionSkillDefinition`)
```json
{ "id": 3, "name": "Riverbreak", "startupTicks": 4, "activeTicks": 3, "recoveryTicks": 9,
  "costs": { "sp": 10 },
  "moves": [ { "fromTick": 2, "toTick": 4, "forwardPixels": 20 } ],
  "armor": [ { "fromTick": 0, "toTick": 6, "type": "SUPER_ARMOR" } ],
  "hitboxes": [ { "fromTick": 4, "toTick": 6, "shape": "RECT", "lengthPixels": 64, "widthPixels": 40,
                  "hit": { "damagePercent": 160, "reaction": "KNOCKDOWN", "reactionTicks": 30, "knockbackPixels": 24, "hitStopTicks": 3 } } ] }
```
- **Timeline**: `startupTicks`, then `activeTicks`, then `recoveryTicks` (each 0..200, 1..400 in total). `cancelFromTick` is the first tick another skill may interrupt it; absent means when recovery starts. `costs` are paid at the start from any vital but the life vital; `cooldownTicks`; `aimLock` (facing fixed at the start, or following the aim).
- **Hitboxes** (at most 16) are live `fromTick..toTick` inclusive and must lie inside the active window. Shapes: `CIRCLE` (`radiusPixels`), `ARC` (`radiusPixels`, `arcDegrees`, centred on the facing) and `RECT` (`lengthPixels` forward, `widthPixels`), offset by `forwardPixels` / `sidePixels`. A target is hit at most once per `hitGroup`; `maxTargets` 0 means no limit. A `projectile` (speed, range, sprite, `pierce`) makes a `CIRCLE` hitbox fly forward from its `fromTick`.
- **A hit** (`SkillHit`): `damageKind` (`MELEE`, `RANGED`, `SPELL`: which `game.json` formula) times `damagePercent`; a `reaction` (`STAGGER`, `KNOCKDOWN`, `AIRBORNE`, `GRAB`) held for `reactionTicks`; `knockbackPixels`; `hitStopTicks`; an optional [status effect](/systems/combat.md).
- **Armor windows**: `SUPER_ARMOR` (hits land but do not interrupt) or `INVULNERABLE` (a dodge's frames). **Moves**: a displacement spread evenly over a window (lunges, dashes, a backstep with a negative `forwardPixels`). Windows of one list may not overlap.
- **Guard** (`SkillGuard`): makes the skill a held block with an arc, a cost per blocked hit and a damage reduction.

## A weapon set (`weapon_sets/weapon_set_NNN.json`, `WeaponSetDefinition`)
The combo graph. Nodes are `skillIds`; `entries` start a skill from neutral; `edges` chain or cancel from one skill into another.
```json
{ "id": 1, "name": "Ford Sword and Shield", "slot": "MAIN", "classIds": [1], "inputBufferTicks": 6,
  "skillIds": [1, 2, 3, 4],
  "entries": [ { "input": "LMB", "skillId": 1 } ],
  "edges": [ { "fromSkillId": 1, "input": "LMB", "toSkillId": 2 },
             { "fromSkillId": 2, "input": "LMB", "toSkillId": 3 },
             { "fromSkillId": 2, "input": "SHIFT+RMB", "toSkillId": 4 },
             { "fromSkillId": 4, "input": "LMB", "toSkillId": 3, "windowTicks": 10, "requiresHit": true } ] }
```
- An edge's input is accepted for `windowTicks` (default 8) counted from the from-skill's cancel point. The window may run past the end of the skill by up to 20 ticks (`MAX_COMBO_GRACE_TICKS`): the character is idle but the chain is still alive.
- `requiresHit` edges are taken only when the from-skill connected (flow skills, finishers). The same input may lead two ways from one skill when exactly one of the edges requires the hit.
- `slot` is `MAIN`, `SUB` or `AWAKENING`; `classIds` (empty = any) and `levelReq` say who may use the set; `inputBufferTicks` (0..20) is how long an early press is remembered.

## Input tokens (`InputTokens`)
One key (`LMB`, `RMB`, `SPACE`, `Q`, `E`, `F`, `TAB`), optionally after modifiers (`SHIFT`, `CTRL`) and one movement direction relative to the facing (`FORWARD`, `BACK`, `LEFT`, `RIGHT`), joined by `+` in that order: `SHIFT+RMB`, `BACK+SPACE`. Each chord has exactly one spelling; `RMB+SHIFT` or `lmb` is an `invalid_input` error whose message gives the accepted form. A sequence ("Q then F") is not a token: it is two edges.

## What the validator rejects (`ActionCombatValidation`, called by `ContentValidator`)
Every issue carries a JSON pointer, for example `ERROR weapon_sets/weapon_set_001.json/edges/0 [free_cycle]`.

| Code | Where | Meaning |
| :--- | :--- | :--- |
| `outside_active_window` | `/hitboxes/i/fromTick` | A hitbox is live outside the active ticks (or the skill has none) |
| `inverted_window`, `outside_skill`, `overlapping_windows` | `/armor/i/...`, `/moves/i/...` | A window ends before it starts, leaves the skill, or overlaps another of the same list |
| `empty_skill` | `/activeTicks` | The three phases add up to no ticks |
| `invalid_projectile` | `/hitboxes/i/shape` | A projectile hitbox that is not a `CIRCLE` |
| `invalid_cost`, `unknown_vital` | `/costs/<vital>`, `/guard/blockCosts/<vital>` | A cost on the life vital or on a vital the game does not have |
| `out_of_range`, `too_many` | the field | A value outside its limit (`ActionCombatLimits`) |
| `unused_field`, `no_effect` (warnings) | the field | A size the shape ignores; a hit or a skill that does nothing |
| `empty_set`, `duplicate_skill`, `missing_reference` | `/skillIds`, `/skillIds/i` | No skills, a skill listed twice, or one that does not exist |
| `not_in_set` | `/entries/i/skillId`, `/edges/i/fromSkillId`, `/edges/i/toSkillId` | An entry or edge names a skill outside `skillIds` |
| `no_entry` | `/entries` | Nothing starts a skill from neutral |
| `invalid_input` | `/entries/i/input`, `/edges/i/input` | Not a token, or not its one spelling |
| `ambiguous_input` | `/entries/i/input`, `/edges/i/input` | The same input twice from neutral, or twice from one skill with the same `requiresHit` |
| `window_too_long` | `/edges/i/windowTicks` | Longer than what is left of the from-skill after its cancel point plus the 20-tick grace |
| `never_hits` | `/edges/i/requiresHit` | The edge requires a hit from a skill with no hitbox |
| `unreachable_skill` | `/skillIds/i` | No entry or chain of edges leads to the skill |
| `free_cycle` | `/edges/i` (first edge of the loop) | Skills that chain into each other forever with no cooldown and no cost anywhere in the loop (strongly connected components over the free skills); one report per loop |

Going back through neutral is always allowed, so a straight chain of free cuts is fine; only a loop of *edges* needs a cooldown or a cost on one of its skills.

## Running it
The [action combat module](/systems/action_combat.md) (`combat.module` `action`) runs these collections in Kotlin, with client input, dodge frames, guard and super armour (added 2026-11-21 for Lanternfall). The F1 **Action skills** and **Weapon sets** panels edit them and the editor catalog carries them. Not built: client prediction, a weapon set tied to an item, and White River itself still ships on `realtime`.

## Example and tests
`games/white-river` ships the Bladewarden's *Ford Sword and Shield*: nine skills (a three-cut chain, a launcher, a stun, two dodges, a guard and a riposte) and eleven edges ([White River Online](/content/white_river_game.md)). Tests: `ActionCombatValidationTest` (core-domain: every rule above with its pointer, token spelling, the replay fingerprint), `ActionCombatContentFilesTest` (server-persistence: hand-written files load with defaults and reach the simulation, a bad graph is reported with file and pointer, schema shape), `Phase11ContentPipelineTest` (committed schemas match the models) and `WhiteRiverGameTest` (the shipped set).

## The Lua prototype (phase A2)
`games/white-river/scripts/action_combat.lua` runs the combo graph under `combat.module: "lua_combat"`; White River still ships on `realtime`, so nothing in normal play reaches it. Tests (`WhiteRiverActionCombatTest`) build the simulation with `LuaCombatModule` on a temp copy of the game.
- **Input**: `c_action` with `actionId: "input"` and `params.token` (an input token, see above); the plain attack packet counts as `LMB`. The set is the first `MAIN` weapon set whose `classIds` and `levelReq` fit.
- **State** (`ac:<character>`, in [script state](/systems/scripting.md)): skill in progress, start tick, whether it hit, targets already hit per `hitGroup`, a buffered press, cooldowns. `react:<npc>` records the last reaction a hit left on a creature.
- **Rules**: an edge is taken when the press lands in `[cancel, cancel + windowTicks]` of the skill's own time; an earlier press within `inputBufferTicks` of the cancel point is buffered and fires at it; `requiresHit` edges need the skill to have connected; an entry starts a skill from neutral once the previous one has ended; costs are paid at the start (a short vital refuses the skill) and `cooldownTicks` is honoured; a hit's `hitStopTicks` freezes the skill's timeline (the start tick moves). Skills are gated by `levelReq`. The chain dies 20 ticks after a skill ends.
- **Geometry**: integer pixels from the character's centre, facing locked when the skill starts; creatures are discs of 12 px. Arcs compare against a cosine computed with a Taylor series so the bits do not depend on `math.cos`.
- **Not yet**: movement during skills, guard, super armor and invulnerability (A4), projectile hitboxes, reactions that change creature behaviour, a client that sends tokens (A3), and the game's own damage formula (the script uses a stand-in so the chain can be measured). Natural regeneration does not run while the Lua module owns `on_tick_players`.
- **The gate**: an eleven-skill chain through every kind of edge replays to the same hash twice live, and a third fresh simulation replays the recorded log checkpoint for checkpoint.
