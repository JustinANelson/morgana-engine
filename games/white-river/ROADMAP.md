# White River Online: Roadmap

The design is the owner's plan "Black Desert-Style ORPG on Morgana Engine" (2026-11-15) and
[ADR 0017](../../wiki/decisions/0017_white_river_online.md). Combat is the product, so the action-combat phases come
first; each phase ends with tests and a wiki update.

| Phase | Deliverable | Exit gate | State |
| :--- | :--- | :--- | :--- |
| A0 | Game folder, design ADR, tick-rate decision | ADR accepted; `validateContent` passes | **Done** (2026-11-15) |
| A1 | Skill and combo content schemas, validator, JSON Schemas | Invalid graphs rejected with pointers | **Done** (2026-11-15) |
| A2 | `action` combat in Lua: one weapon set, a dummy, hitboxes | A 10-skill combo replay hashes identically twice | **Done** (2026-11-15) |
| A3 | Client input buffer, prediction, attack animation, hit-stop, F1 skill editor | Chain plays in browser and desktop | Next |
| A4 | Dodge, guard, knockdown, airborne, super armor, immunity timers | No infinite chain; i-frames verified per tick | |
| A5 | Port to Kotlin `ActionCombatModule`; all three classes on combo graphs | Lua and Kotlin agree on shared input logs | |
| A6 | Enhancement ladder with failstacks; durability | Seeded roll distribution test; replay identical | |
| A7 | Life-skill levels, tiered tools, fishing and cooking play | Every recipe reachable; no money loop | Content half done |
| A8 | Marketplace bidding, workers, mounts, guild ranks and bank | Invariants hold under concurrent clients | |
| A9 | Grinding-zone density, bosses with telegraphs, instanced dungeons | Boss fight replay passes | |
| A10 | Territory siege, karma and open-PvP rules; load test | Soak test at target concurrency | |

## What A0 delivered
The valley in the README: 5 seamless maps, 3 classes with 5 skills each, 17 creatures (2 bosses), 76 items in four gear
tiers, 7 gathering resources, 25 recipes across four stations, 12 quests, 6 shops, a day and night clock, loot rarities
and enemy variants. It plays today on the engine's `realtime` combat. `WhiteRiverGameTest` already holds part of the A7
gate: every item can be obtained, every recipe can be made, and no shop-bought recipe sells for more than it costs.

## What A1 delivered
Two engine content collections, `action_skills` and `weapon_sets` (format and rules:
`wiki/domain/action_combat_content.md`, schemas in `docs/schemas/`). A skill is a timeline in ticks (startup, active,
recovery, a cancel point) with hitboxes (arc, rectangle, circle, projectile), what each hit does (damage, stagger,
knockdown, airborne, grab, knockback, hit-stop, status), super-armor and invulnerability windows, movement and guard.
A weapon set is the combo graph: entries from neutral and edges with an input token, a window and an optional
"only if it hit". The validator rejects bad timelines and graphs with a pointer to the field, including skills nothing
leads to and loops that could be chained forever for free.

The Bladewarden's **Ford Sword and Shield** is the first set (`data/action_skills`, `data/weapon_sets`, written by
the former `tools/build.py`): Ford Cut, Backhand and Riverbreak on left click, Undertow off the second cut with Shift + right
click, Shieldbreaker on F, Sidestep and Backstep on Space, Wardstance on Q and a Riposte out of it.

## What A2 delivered
`scripts/action_combat.lua` steps the Ford Sword and Shield against a training dummy under `lua_combat` (the game still
ships on `realtime`): entries and edges with windows, early-press buffering, `requiresHit`, costs, cooldowns, level gates,
hit-stop, arc, rectangle and circle hitboxes, one hit per hit group. It needed three small engine additions, all generic:
script state (`world.set_state`, capability `state`, hashed for replays), content as Lua tables, and `px` / `py` pixel
positions. `WhiteRiverActionCombatTest` holds the gate: an eleven-skill chain replays to the same hash twice and a fresh
simulation replays the log checkpoint for checkpoint. Not done: movement during skills, guard, armor and invulnerability
(A4), projectiles (A5), the game's damage formula (A5). Whether this feels right on a 20 TPS server is still unanswered;
A3 is where that is found out.

## Stand-ins until their phase lands
- **Combat** is `realtime` (target a creature, press a skill). The skills are written so they map onto combo graphs in
  A2 to A5: each class has an opener, a defensive skill, an area skill, a crowd-control skill and a finisher.
- **Stamina** (`sp`) pays for Bladewarden and Windrunner skills now; it will also pay for dodge and guard (A4).
- **Palestone** drops rarely everywhere and has no use yet: it is the enhancement material for A6.
- **PvE zones** use the `SAFE_ZONE` ruleset, so the client labels the fields and the marsh "Safe Zone" although
  creatures fight there. **Contested zones** use `OPEN_PVP`, which drops the whole bag on death. A10 replaces both
  with karma rules (a small penalty, no full loot).
- **Life skills** have no levels of their own yet: gathering and crafting give character experience (A7).
- **Chalk Hollow's redoubt** is a camp of reavers; it is where the node war of A10 will be fought.
- **Art** is the owner's Time Fantasy set, shared with the Conquer demake and recoloured. Attack animations per skill
  (the plan's largest content cost) are not drawn.
- Balance is by formula (the deleted `build.py`, `creature`) and has not been play-tested end to end.
