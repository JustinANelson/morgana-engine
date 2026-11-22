---
type: Reference
title: "Validation Rules and Issue Codes"
description: "Every check the content validator performs and the issue codes it emits."
tags: [content, validation]
code: ["core-domain/src/main/kotlin/com/morgana/domain/validation/ContentValidation.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ContentLoader.kt"]
symbols: ["missing_reference", "out_of_bounds", "non_reciprocal_neighbor", "unknown_field", "filename_mismatch", "newer_schema", "suspicious_stack", "missing_map_link"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Validation Rules and Issue Codes

Each issue has: `severity` (ERROR blocks, WARNING advises), `collection`, `id`, `pointer` (RFC 6901 JSON pointer), `code`, `message`, and optionally `file`.

| Code | Severity | Meaning |
| :--- | :--- | :--- |
| `parse_error` | ERROR | Malformed JSON / constructor invariant failed (e.g. map cell count) |
| `unknown_field` | WARNING | Field name not in the model (likely typo); loaded leniently |
| `duplicate_id` | ERROR | Two files define the same id |
| `filename_mismatch` | WARNING | File name does not match `<prefix>_<id:3>.json` |
| `invalid_id` / `blank_name` | ERROR | id <= 0 or empty name |
| `newer_schema` | ERROR | `schemaVersion` greater than `ContentSchema.CURRENT_VERSION` |
| `missing_reference` | ERROR | Referenced id does not exist |
| `missing_map_link` | WARNING | A warp tile, neighbor link, warp command, or warp spell targets a map that does not exist yet (the simulation ignores it; maps may reference each other cyclically, [ADR 0007](/decisions/0007_validate_before_apply.md)) |
| `out_of_bounds` | ERROR | Warp/spawn/start/event tile outside its map |
| `out_of_range` | ERROR | Negative costs, `maxStack < 1`, `maxHp < 1`, drop chance outside 0..10000, `ON_TIMER` common event `intervalTicks` outside 1..72000, yields, etc. |
| `missing_required` | ERROR/WARNING | `SHOPKEEPER` without `shopId` (error); `QUEST_GIVER` without event (warning) |
| `too_deep` | ERROR | Event steps nested more than eight levels (answers and `if` branches inside one another) |
| `empty_list` | ERROR/WARNING | Class without sprites, event without pages (errors); quest without objectives, recipe without ingredients (warnings) |
| `duplicate_slot` | ERROR | Repeated NPC spawn `slotIndex` on a map |
| `invalid_color` | ERROR | Not `#RRGGBB`/`#RRGGBBAA` |
| `self_reference` | ERROR | Quest requires itself |
| `non_reciprocal_neighbor` | WARNING | Map A links to B but B does not link back |
| `suspicious_stack` / `suspicious_value` | WARNING | `stackable` with `maxStack < 2`; AoE radius < 1 |

Action skills and weapon sets have their own codes (`free_cycle`, `unreachable_skill`, `ambiguous_input`, `outside_active_window` and more): see [action-combat content](/domain/action_combat_content.md).

Implementation: `ContentValidator` (pure, in `:core-domain`); file-level codes come from `ContentLoader`. To add a rule see [add a content type](/playbooks/add_content_type.md). Behavior on failure: [hot reload](/content/hot_reload.md).
