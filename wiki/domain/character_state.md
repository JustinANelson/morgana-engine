---
type: Concept
title: "Character State"
description: "What a persisted player character contains and which parts are derived."
tags: [characters, persistence]
code: ["core-domain/src/main/kotlin/com/morgana/domain/entity/EntityAndPlayerModels.kt"]
symbols: ["CharacterState", "AccountDocument", "PartyState", "effectiveAttributes", "lastMoveTick"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Character State

`CharacterState` is a single immutable data class (copy-on-write) stored in `server-data/characters/` and held live in `WorldSimulation.players`.

Key fields: identity (`id`, `accountId`, `name`), `classId`, `spriteId`, `accessLevel`, `level`, `xp`, `unspentStatPoints`, `attributes`, `vitals`,
`position` (`WorldPosition`: map, tile, pixel, direction), `movementMode`, `inventory` (24 slots default), `bank` (48), `equipment`, `knownSpellIds`,
`hotbar` (10 slots), `statusEffects`, `spellCooldowns` (spell id -> ready tick), `quests`, `switches`/`variables` (per-character event state),
guild/party ids, `instanceReturn` (where the character stood before entering an [instanced map](/systems/instancing.md); null outside instances), `friendNames`, PK flags (`pkPoints`, `isRoguePk`), `lastMoveTick`, `lastAttackTick`, `schemaVersion`. The tick fields and `spellCooldowns` are absolute ticks of the run that saved them; they are filtered when a character spawns ([movement](/systems/movement.md)).

- Derived: effective attributes and max vitals are recalculated from game definition, equipment, and status effects ([stats and formulas](/domain/stats_and_formulas.md)).
- Accounts (`AccountDocument`) are separate: `passwordHash`, `accessLevel`, `extraPermissions`, `banned`, `mutedUntilTick`, `characterIds`.
- Saved on disconnect and every 200 ticks ([coordinator](/server/coordinator.md)).
- `lastMoveTick` drives the server-side step rate limit ([movement](/systems/movement.md)).
