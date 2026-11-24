---
type: Simulation System
title: "World State (placed objects)"
description: "Things placed on a map that stay there (walls, chests, totems): deterministic, saved per map, and open to Kotlin systems and Lua through a placement gate and an observer."
tags: [systems, world-state, building, replay, lua, persistence]
code: ["core-domain/src/main/kotlin/com/morgana/domain/systems/WorldStateSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/entity/WorldObject.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/WorldObjects.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/SystemSeams.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/WorldObjectRepository.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "core-domain/src/test/kotlin/com/morgana/domain/WorldStateTest.kt", "script-luak/src/test/kotlin/com/morgana/script/luak/LuaWorldStateTest.kt", "server-app/src/test/kotlin/com/morgana/server/WorldStatePersistenceTest.kt"]
symbols: ["WorldStateSystem", "WorldObject", "WorldObjectKind", "WorldObjectsDefinition", "WorldObjectGate", "WorldObjectObserver", "WorldObjectInteraction", "WorldObjectDamageGate", "WorldObjectResult", "placeWorldObject", "WorldObjectRepository", "WorldObjectsChanged"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-24T22:00:00Z }
stale_after: 2027-05-23T00:00:00Z
---

# World State (placed objects)

The `worldState` system ([optional modules](/server/optional_modules.md), tags: survival, farming, rpg, role-play) lets a game leave things in the world: a wall, a chest, a sign, a claim totem. It is the base that base building, land claims and farm plots are built on, and it works on its own for any game that wants placeable objects.

## What an object is
A `WorldObject` occupies **one tile**, has a **kind**, **hit points**, an optional owner (character and guild), the tick it was placed, and a **`data`** map of strings that a system or script keeps on it (an upkeep deadline, a tier). Kinds are declared in `game.json` `worldObjects` ([game definition](/domain/game_definition.md)): name, whether it blocks walking, max HP, tags.

## Placing, hurting, removing
Everything goes through the simulation so it is an event and is recorded:
- `placeWorldObject(kind, mapId, x, y, ownerCharacterId, ownerGuildId, data, out)` returns `Placed` or `Refused(reason)`. Refused when the system is off, the kind is unknown, the tile is outside the map or has a blocking attribute (a light source is fine), something is already built there, a character or monster stands there (blocking kinds only), the map is at `maxPerMap`, or a gate says no.
- `damageWorldObject` (removed as `destroyed` at 0 HP), `setWorldObjectData`, `removeWorldObject(id, reason)`.
- **From an item:** an item with `placesObject` set puts that kind on the tile the character faces and is used up; a refused placement keeps the item. `/object` shows what you face; `/object remove` takes down your own (or anything, for a server maintainer). There is no refund yet.
- Recorded inputs: `SimInput.PlaceWorldObject` and `RemoveWorldObject` (an item use is recorded as `UseInventorySlot`).

## Rules by others
| Who | How |
| :--- | :--- |
| Kotlin systems | implement `WorldObjectGate.refusal(...)` to veto a placement, and `WorldObjectObserver.onWorldObject(...)` to hear every `PLACED`, `CHANGED` and `REMOVED` (with a reason such as `placed`, `damaged`, `data`, `decayed`, `destroyed`) ([system seams](/systems/system_seams.md)) |
| Lua | `register.object_gate{ kinds, check }` for rules; `world.get_object`, `world.objects_near` to read; `world.place_object`, `remove_object`, `damage_object`, `set_object_data` as effects (the `state` capability) ([API reference](/../docs/API_REFERENCE.md)) |

## Clients
Clients with the `world_objects` capability (in the default set) get the map's objects in `MapStateSnapshot.worldObjects` and every change as `WorldObjectsUpdated` ([server packets](/protocol/server_packets.md)); `ClientWorldState.worldObjectsOnMap` holds them. The renderer draws a kind's tileset sprite (`spriteTilesetId` above 0) or, without one, a coloured block, with a damage bar when hurt, and shows a claim's reach as a tinted square (red while its attack window is open). `MorganaWorldCoordinator.placeWorldObject` and `removeWorldObject` place and remove objects the way a game would (recorded, announced), for tools and tests.

## Using an object
Facing a placed object and interacting (the same input as opening a door or harvesting a node, `InteractFacing`) asks the `WorldObjectInteraction` seam before the tile's own interaction. `interactionRefusal` can veto (the reason is told to the player, nothing else runs: a damaged structure refuses storage or crafting); then `interact` lets a system carry the use out and settle it. An object nobody has a rule for does nothing and the interaction carries on as before. Lua: `register.object_use{ kinds, gate, use }`. The engine ships no object that does anything when used; chests, stations and doors are rules of a game or a later system.

## Hurting objects
A kind with `"attackable": true` can be hurt by characters: a melee swing (realtime), an action-combat hitbox and a player's projectile all land on the object on the tile they reach, through `WorldStateSystem.strike`. The blow is the attacker's ordinary weapon damage against a default defender, scaled by the kind's `weaponDamagePercent` (the GDD's 5% rule is `5`) or, when the equipped weapon's type is listed in `worldObjects.siegeWeaponTypes`, by `siegeDamagePercent` (at least 1 damage; both 1 to 1000, default 100). Objects at 0 HP are removed (reason `destroyed`) and reported to observers. A `WorldObjectDamageGate` (Kotlin) can refuse a blow: `territory` refuses it on anything inside a safe-zone claim unless an attack window is open or the attacker may build there. Spells, monsters and area effects do not hurt objects yet, and a projectile only stops on an object it can hurt.

## Walking
`isTileBlockedByObject` is checked where players step (including the slide along a wall in free movement) and where monsters choose a tile (`canNpcStepTo`). Non-blocking kinds (a sign) are walked over. Projectiles and line of sight do not look at objects yet.

## Determinism and replay
Objects are state the simulation owns: `StateHasher` includes them (only when there are any, so older replays are unchanged), `nextId("obj")` comes from the shared entity counter, and `loadWorldObjects` keeps that counter ahead of every loaded id. A replay log carries `startingWorldObjects`, captured when recording begins, so a recording of a server that already had builds replays exactly.

## Persistence
`WorldObjectRepository` writes one file per map, `<data dir>/world-objects/map_<id>.json` (atomic: a temporary file moved into place; a map with nothing left has no file). The coordinator loads them at boot, marks changed maps (`dirtyWorldObjectMaps`), saves those off the tick thread every 200 ticks (`AsyncPersistenceWriter.enqueueWorldObjects`; a newer snapshot of a map replaces an older unwritten one) and saves everything on shutdown. A file that cannot be read is reported and skipped, and left as it is. The objects belong to the realm, not to the game folder, so they live under the data directory.

## Not built yet
- A **placement preview** (a ghost of the held item's object on the tile in front), a build/claim window, a sprite picker in `F1`, and **area-of-interest filtering** of object changes (they go to everyone on the map today).
- Objects are one tile. Larger pieces, rotation, and a per-kind build cost or tool are not modelled.
- Damage by spells, monsters and area effects; siege engines as placed machines (a siege weapon is a weapon type for now); decay and upkeep (a game's Lua plugin).
- Refunding an item when a player takes an object down; an F1 editor form for kinds.
