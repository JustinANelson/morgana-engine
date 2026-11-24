---
type: Simulation System
title: "Territory (claims)"
description: "Claiming land: only owners build inside a claim, claims keep apart and hold limited pieces, claims can be safe zones opened to attack by timed windows. Built on world state and the scheduler."
tags: [systems, territory, building, pvp, scheduler]
code: ["core-domain/src/main/kotlin/com/morgana/domain/systems/TerritorySystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/Territory.kt", "core-domain/src/test/kotlin/com/morgana/domain/TerritoryTest.kt"]
symbols: ["TerritorySystem", "TerritoryDefinition", "ClaimKindDefinition", "ClaimLevel", "bookWindow", "claimAt", "mayBuildIn", "upgradeClaim"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-24T23:00:00Z }
stale_after: 2027-05-23T00:00:00Z
---

# Territory (claims)

The `territory` system ([optional modules](/server/optional_modules.md), needs [world state](/systems/world_state.md), tags: survival, rpg) lets a game have land that belongs to someone. A **claim** is a world object of a *claim kind* (a totem, a banner, a deed post); the ground within its radius is its owner's. Territory keeps **no state of its own**: the claim and everything built are world objects (so they are saved and replayed), and attack windows are scheduler one-offs.

## Configuration (`game.json` `territory`)
```json
"territory": {
  "noBuildRadiusTiles": 6, "minClaimSpacingTiles": 0,
  "claimKinds": [
    { "kind": "claim_totem", "levels": [ { "radiusTiles": 10, "maxPieces": 150 }, { "radiusTiles": 10, "maxPieces": 150 } ], "maxPerOwner": 1, "safeZone": true },
    { "kind": "guild_totem", "guild": true, "levels": [ { "radiusTiles": 14, "maxPieces": 300 } ] }
  ]
}
```
`kind` must be a `worldObjects.kinds[].id` ([game definition](/domain/game_definition.md)). Validation points at each bad field (`/territory/claimKinds/0/levels/0/radiusTiles`...).

## The rules
| Rule | How it works |
| :--- | :--- |
| **Who may build** | inside a claim only its owner, for a guild claim any member of the owning guild, and anyone the owner allowed (`/claim allow <name>`, `/claim deny <name>`). Placements the game itself makes (no owner) are never refused. |
| **Claims keep apart** | a new claim needs more than `radius + the other's radius + minClaimSpacingTiles` tiles between centres (square distance). A new claim also cannot go where someone else has already built within its reach. |
| **How many, how much** | a character (or, for a guild claim, a guild) holds at most `maxPerOwner` claims of a kind on a map; a claim holds at most `maxPieces` objects (not counting claims) at its level; the level sets the radius. |
| **Keep clear** | nothing a character places goes within `noBuildRadiusTiles` of a resource node tile or a monster spawn tile. |
| **Safe zone** | a claim whose kind is a `safeZone` stops anyone attacking anyone while either stands inside it, through the combat arbiter seam (`canAttackPlayer`), unless a window is open. Monster damage is not affected. |
| **Attack windows** | `TerritorySystem.bookWindow(sim, claimId, startMillis, durationSeconds, warningsSeconds, label)` opens a claim to attack at a wall-clock time and closes it after the duration, with chat warnings ("Siege window begins in 15 minutes."). It books two scheduler one-offs (`territory-open-<claim id>`, `territory-close-<claim id>`), so it survives a restart. |

Placing a claim is an ordinary placement: an item whose `placesObject` names the claim kind puts it on the tile in front ([world state](/systems/world_state.md)); a placement remembers the placer's guild, which is what a guild claim uses.

## Reading and changing claims
`TerritorySystem.claimAt`, `claimsOn`, `levelOf`, `radiusOf`, `piecesIn`, `mayBuildIn`, `isWindowOpen`, and `upgradeClaim` (one level up; **costs are the game's to charge**). `/claim` shows the claim you stand on, `/claim abandon` gives it up (what was built stays), and a server maintainer may abandon any. A claim's `data` holds `level`, `allow` (character ids) and `window` (`open` while an attack window is on).

## Protected structures
Everything inside a safe-zone claim is protected like its people: `territory` is a `WorldObjectDamageGate` that refuses blows on structures there ("This land is protected right now.") unless an attack window is open on the claim or the attacker may build in it ([world state](/systems/world_state.md)). Damage amounts and the siege rule come from the object kinds' `weaponDamagePercent` and `siegeDamagePercent`.

## What a game builds on top
Upkeep, decay, a weekly wipe, raids on a schedule, siege damage and "no claims in the safe ring" are rules of a particular game. They are built from these parts: `register.schedule` (a daily upkeep check), `register.object_gate` (a ring rule), `world.objects_near`/`set_object_data`/`remove_object` (decay), and `bookWindow` (the attack window). The engine ships none of those numbers.

## Not built yet
- **Combat lockout**: a character who fought in the last N minutes cannot place a claim. Needs a record of recent combat; PvP-only tracking exists, general combat does not. A Lua gate on a `register.on_kill`/`combat_observer` timestamp can do it today.
- **Clients are not told about objects**, so players cannot see claims or their borders yet ([world state](/systems/world_state.md)); `/claim` is the only view.
- What happens when a claim object is destroyed (an observer on `REMOVED` can do it in Lua; the engine just removes it), an upgrade cost, and shared storage permissions.
- Claim limits are per map; a game with several maps per region needs its own gate for a per-region limit.
