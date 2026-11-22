---
type: Decision
title: "ADR 0017: White River Online and Its Action-Combat Path"
description: "White River Online is an original sandbox ORPG built as a game folder; action combat arrives as a new CombatModule, prototyped in Lua, and the tick rate stays at 20 TPS."
tags: [adr, white-river, combat, roadmap, content]
code: ["games/white-river/game.json", "games/white-river/ROADMAP.md", "core-domain/src/main/kotlin/com/morgana/domain/combat/CombatModule.kt", "server-app/src/test/kotlin/com/morgana/server/WhiteRiverGameTest.kt"]
symbols: ["CombatModule", "LuaCombatModule", "WhiteRiverGameTest"]
generated: { by: claude-code/claude-fable-5-2, at: 2026-11-15T20:00:00Z }
stale_after: 2027-05-14T00:00:00Z
---

# ADR 0017: White River Online and Its Action-Combat Path

**Status**: Accepted 2026-11-15 (owner request: "start building out the Black Desert Online inspired game called White River Online", built off the ORPG game rather than the Emberdeep dungeon crawler). Phase A0 is implemented: see [White River Online](/content/white_river_game.md). Phase A1 (skills and combo graphs as validated content) followed the same day: see [action-combat content](/domain/action_combat_content.md). Phase A2 (the Lua prototype that steps them) followed: see [action-combat content](/domain/action_combat_content.md). Phases A3 to A10 are in `games/white-river/ROADMAP.md`.

**Context**: the owner's plan asks for a 2D online action RPG with the *structure* of Black Desert Online (skill-chain combat, deep life skills, risky gear enhancement, a player economy, territory wars) on Morgana's deterministic, data-driven core. Morgana already has the MMO shell (the ORPG module set, markets, mail, guilds, gathering, crafting, loot rarities, seamless map borders); the gap is action combat. The plan left three questions open: where the game lives, the tick rate, and which classes come first.

**Decision**:
1. **An original game, in a game folder.** `games/white-river` (id `white-river`), built like the [Halcyon Reach](/content/halcyon_reach_game.md): ORPG modules in `server.json`, open-world maps, a build script. Every name, place, creature, class and skill is its own; Black Desert is inspiration for systems only. No engine code is specific to the game.
2. **Playable first, on `realtime` combat.** The world, classes, economy and life-skill content ship now on the existing `CombatModule`, so every later phase is tested against a real game instead of a demo map.
3. **Action combat is a new `CombatModule`, not a fork.** Skills and combo graphs become JSON content (A1). The rules are prototyped in Lua behind `lua_combat` and `c_action` (A2 to A4), then ported to a Kotlin `ActionCombatModule` and differential-tested against the Lua version on shared input logs (A5), the way the engine/game split was done ([ADR 0013](/decisions/0013_engine_game_split_and_lua_scripting.md)).
4. **The tick rate stays at 20 TPS.** Skills are timed in ticks (50 ms). Raising the rate is a global change: it alters every duration written in ticks in every game (cooldowns, regeneration, respawns, the clock) and invalidates the golden replay. Typical action windows (startup 2 to 6 ticks, cancel windows 4 to 8) fit a 50 ms grid, and the feel of a hit is carried by client prediction, the 6-tick input buffer and hit-stop rather than by server granularity. This is revisited with measurements at the end of A3; if 50 ms proves too coarse, the fallback is sub-tick hitbox offsets inside the module, still not a global rate change.
5. **Three archetypes first**: Bladewarden (melee chains, guard), Windrunner (ranged, evasion), Duskweaver (short casts, area denial). Each already has an opener, a defensive skill, an area skill, a crowd-control skill and a finisher, which is the shape a combo graph needs.
6. **The world is walked, not warped.** Maps are 64 x 48 and join through `neighbors` at road gaps; the build script and the test both check that a seam is open at the same cells on both sides and that every NPC, node and station can be reached on foot from the class start.

**Consequences**: the game is content plus a test today and costs the engine nothing. Until their phases land it uses stand-ins, listed in the roadmap: the `SAFE_ZONE` ruleset for PvE zones (the client labels them "Safe Zone"), `OPEN_PVP` full-bag drops in contested zones where the plan wants a small karma penalty, no life-skill levels, and an enhancement material with no use. The art is the owner's Time Fantasy set, shared with the Conquer demake and recoloured, so the two games look related until White River gets its own sheets. The tick-rate decision (4) is the assistant's recommendation recorded as accepted so work can proceed; the owner has not separately confirmed it.
