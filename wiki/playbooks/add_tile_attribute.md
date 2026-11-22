---
type: Playbook
title: "Add a Tile Attribute"
description: "Steps to introduce a new TileAttribute subtype end to end."
tags: [playbook, tiles]
code: ["core-domain/src/main/kotlin/com/morgana/domain/map/MapModels.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/MovementSystem.kt"]
symbols: ["TileAttribute", "movePlayerImpl", "SerialName"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Add a Tile Attribute

1. Add a subtype of `TileAttribute` with a unique `@SerialName("snake_case")` ([maps and tiles](/domain/maps_and_tiles.md)). Objects with no data use `data object`.
2. Gameplay: handle it in `MovementSystem` (destination attributes) or in the relevant tick ([movement](/systems/movement.md)).
3. Validation: add references/ranges to `Collector.map` ([validation rules](/content/validation_rules.md)).
4. Client: render badge/effect in `MorganaWorldRenderer` and add it to the F1 map-editor palette ([rendering](/client/rendering.md), [F1 editor](/client/f1_editor.md)).
5. Tiled: extend the attribute-layer codes if it should round-trip ([Tiled](/content/tiled_conversion.md)).
6. Run `exportContentSchemas`; tests for movement + validation; update the wiki.
