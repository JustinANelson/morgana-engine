---
type: Module
title: "Module: core-domain"
description: "The pure Kotlin rules engine: models, the 20 TPS simulation, and content validation."
tags: [module, domain]
code: ["core-domain/src/main/kotlin/com/morgana/domain"]
symbols: ["WorldSimulation", "ContentValidator", "SeededRandomSource", "ContentSchema"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Module: `:core-domain`

Package root `com.morgana.domain`. Only dependency: `kotlinx-serialization-core` (annotations on data models).

| Package | Contents |
| :--- | :--- |
| `access` | `AccessLevel`, `PermissionFlag` ([access levels](/domain/access_levels.md)) |
| `content` | Definitions: `ClassDefinition`, `NpcDefinition`, `SpellDefinition`, `ShopDefinition`, `ResourceDefinition`, `RecipeDefinition`, `AnimationDefinition`, `ContentSchema` |
| `entity` | `CharacterState`, `AccountDocument`, party/guild/trade/mail/market documents, map runtime instances ([character state](/domain/character_state.md)) |
| `event` | Quests and page events ([events and quests](/systems/events_quests.md)) |
| `game` | `GameDefinition` (data-defined attributes, vitals, progression and combat formulas), the `Formula` language, `GameDefinitionValidator`, `CompiledGame`, `BlankGame` (the neutral default game); the simulation runs from it ([game definition](/domain/game_definition.md)) |
| `item` | `ItemDefinition`, `Inventory`, equipment |
| `map` | `MapDefinition`, layers, `TileAttribute`, morals ([maps and tiles](/domain/maps_and_tiles.md)) |
| `sim` | `WorldSimulation` facade, the system files, `WorldSimulationEvent`, `RandomSource` ([tick loop](/systems/tick_loop.md)) |
| `spatial` | `Direction`, `TileCoord`, `WorldPosition`, `MovementMode` |
| `stats` | `VitalState`, status effects ([stats and formulas](/domain/stats_and_formulas.md)) |
| `validation` | `ContentValidator`, `ContentCatalog`, `ContentIssue` ([validation rules](/content/validation_rules.md)) |

## Hard rules
- No `System.currentTimeMillis()`, `Math.random()`, disk, or network ([determinism](/domain/determinism_and_rng.md)).
- All time is a tick count; all randomness comes from the injected `RandomSource`.
- Behavior is exposed only through `WorldSimulation` methods, which return `List<WorldSimulationEvent>`.
- Document classes carry `schemaVersion` (default `ContentSchema.CURRENT_VERSION`).

Tests: `Phase1DomainModelTest`, `Phase2WorldSimulationTest` ([testing strategy](/reference/testing_strategy.md)).
