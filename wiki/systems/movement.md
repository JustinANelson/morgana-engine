---
type: Simulation System
title: "Movement"
description: "Rules for player movement: modes, validation order, border crossing, and tile attribute effects."
tags: [movement]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/MovementSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/MovementRules.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/EventSystem.kt", "server-app/src/main/kotlin/com/morgana/server/MoveInputQueue.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/WorldSimulation.kt"]
symbols: ["movePlayerImpl", "preventsMovement", "SUB_TILE", "PLAYER_TOUCH", "MovementRules", "GRID_MIN_STEP_TICKS", "fireMapEntryEvents", "MoveInputQueue", "moveAllowedNow"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Movement

Entry: `WorldSimulation.movePlayer(characterId, direction, mode, deltaPixelsX, deltaPixelsY)`.

Rejected outright if the player is dead or has a status effect that `preventsMovement` (stun/root).

**Modes**
- `GRID_LOCKED`: target is the adjacent tile in `direction` (8 directions exist).
- `SUB_TILE`: pixel deltas, each clamped to +/-16 px per call; the tile is derived from the new pixel position. Diagonal steps (both deltas non-zero) are allowed; if the diagonal lands in a tile that cannot be entered, the server tries the horizontal part, then the vertical part (wall sliding). The client mirrors this in `predictAndCreateMovePacket`. The mode is realm-wide: `subTileMovement` on means everyone uses `SUB_TILE`.

**Validation order**
1. Seamless N/S/E/W map-border crossing (moves the player into the neighbor map; the client keeps sprites and camera continuous across the swap and shows entities across the border, see [rendering](/client/rendering.md)).
2. Traversal invariants on the current map (bounds, blocked, directional blocks, key doors, resource nodes); a blocked move still updates facing.
3. Live NPC collision.
4. Destination attributes: `warp`, `slide`, `shop` (opens shop UI), `bank` (opens bank UI).
5. `PLAYER_TOUCH` events on the destination tile ([events and quests](/systems/events_quests.md)).

**Jump**: Jump (`jump` in `game.json`, `WorldSimulation.jumpTo`): a tile leap to any open tile within `maxRangeTiles` (Chebyshev), costing `staminaCost` stamina (0, as in Trevana Realms, makes it free and needs no stamina vital), with a cooldown; walls and water in between do not matter. `disabledMapIds` lists maps where jumping is refused (a walled arena, a jail). The map edge is never crossed by a jump (out of bounds is refused).

**Sprint**: when `sprint` (`SprintDefinition`) is set and free pixel movement is on, a step longer than a normal one (8 px, `MovementRules.NORMAL_STEP_PIXELS`) by more than 5% is a sprint step. `MovementRules.limitStep` keeps it up to `speedPercent` of a normal step while the character has stamina and cuts it back to a normal step otherwise; one stamina point is spent every `stepsPerStamina` ticks of sprinting. The client sends longer steps while the Sprint key (`Shift`, hold) is down and `ClientWorldState.canSprint()` is true. Without a `sprint` section the old 16 px per-axis cap stands. Tests: `SprintTest`.

**Mounts**: an item with `mountSpeedPercent` of 101 to 200 worn in any slot (Trevana Realms has a `MOUNT` slot) makes a normal free-movement step that percent of 8 px (`EquipmentLoadout.mountSpeedPercent`, the fastest worn mount counts). Sprint stacks on top of it and still costs stamina. The client scales its steps the same way (`ClientWorldState.predictAndCreateMovePacket`). A mount has no sprite yet. Tests: `SprintTest`.

**Town Portal**: an item with `townGate = true` takes the user to the nearest town of the game's `townGate.towns` (`TownGates.nearest`: fewest map hops over seamless borders and warp tiles; ties go to the town listed first; an unreachable map falls back to the first town). `townGate.disabledMapIds` lists maps where it fizzles and keeps the scroll. Tests: `TownGateTest`.
6. After a **border crossing or warp tile**, `AUTORUN_ON_ENTER` pages of the new map run (also after login, event warps, and warp spells; not after admin teleports or death respawns).

## Rate limit (`MovementRules`)
Before anything else the server checks `currentTick - lastMoveTick` against the mode's minimum: `GRID_MIN_STEP_TICKS = 2` (max 10 tiles/s; a normal client sends about 7.7/s) or `SUB_TILE_MIN_STEP_TICKS = 1` (one step per tick, each clamped to `SUB_TILE_MAX_STEP_PIXELS = 16`). In the simulation a move that is too soon is dropped and answered with an authoritative `CharacterUpdated`. The coordinator keeps that from happening to honest clients: steps that the network delivers bunched wait in a per-session `MoveInputQueue` (up to 6, in order) and are applied on later ticks as `WorldSimulation.moveAllowedNow` permits, one per tick at most, so a jittery connection is not snapped back and speed is still capped. A step beyond the queue is dropped but acknowledged, and the client's [reconciliation](/client/client_state_and_prediction.md) corrects for it. Plugin `beforeAction` hooks judge a step when it arrives, not when a queued step is applied. Tests: `MoveInputQueueTest`.
Successful moves set `lastMoveTick`; blocked moves (which only turn the player) do not. `SLOW`/`HASTE` do not change the limit ([security model](/reference/security_model.md)). Test: `HardeningRulesTest`.
The client predicts the same rules locally ([prediction](/client/client_state_and_prediction.md)). Attribute definitions: [maps and tiles](/domain/maps_and_tiles.md).

`currentTick` restarts at 0 on every server launch, so a character entering the simulation has its saved `lastMoveTick`, `lastAttackTick` and `spellCooldowns` filtered by `WorldSimulation.spawnOrUpdatePlayer`: values ahead of this run's clock are dropped (otherwise they would block moves, attacks and spells until the clock caught up); values this run could have produced are kept, so logging out and in does not shed a real cooldown. Test: `TickClockRestartTest`.
