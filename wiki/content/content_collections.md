---
type: Reference
title: "Content Collections"
description: "All fourteen content types: directory, file name, model class, JSON Schema, and reference rules."
tags: [content, schema]
code: ["docs/schemas", "core-domain/src/main/kotlin/com/morgana/domain/validation/ContentValidation.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ContentSchemaExporter.kt"]
symbols: ["ContentCollection", "fileStem", "schemaFor", "MapDefinition", "AnimationDefinition", "ACTION_SKILLS", "WEAPON_SETS"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Content Collections

Files: `server-data/world/<directory>/<prefix>_<id:3>.json`. The file name number must equal the document `id` (`filename_mismatch` warning otherwise). `0` means "none" for optional references.
JSON Schemas (generated, drift-tested): `docs/schemas/<directory>.schema.json`.

| Directory | Prefix | Model | Notable references |
| :--- | :--- | :--- | :--- |
| `maps` | `map_` | `MapDefinition` | warps -> maps; spawns -> npcs; attributes -> items/resources/shops; `eventIds` -> events; neighbors -> maps |
| `classes` | `class_` | `ClassDefinition` | start map/tile; starting items and spells |
| `items` | `item_` | `ItemDefinition` | `teachSpellId`, `requiredAmmoItemId`, `animationId`, `classReqIds` |
| `npcs` | `npc_` | `NpcDefinition` | `shopId`, `eventId`, `spellIds`, drops -> items |
| `spells` | `spell_` | `SpellDefinition` | warp map for WARP; `animationId`; `classReqIds` (a cast, scroll or quest reward for another class is refused with a notice, and a character who knows a spell their class cannot use forgets it at login) |
| `shops` | `shop_` | `ShopDefinition` | currency and entry items |
| `resources` | `resource_` | `ResourceDefinition` | `rewardItemId` |
| `recipes` | `recipe_` | `RecipeDefinition` | ingredient and output items |
| `quests` | `quest_` | `QuestDefinition` | objective targets by type; reward items/spell; prerequisites |
| `events` | `event_` | `EventDefinition` | commands -> items/quests/shops/maps/animations/commonEvents |
| `common_events` | `common_event_` | `CommonEventDefinition` | reusable event step lists -> items/quests/shops/maps/animations/commonEvents |
| `animations` | `anim_` | `AnimationDefinition` | none |
| `action_skills` | `action_skill_` | `ActionSkillDefinition` | `animationId`; cost vitals ([action-combat content](/domain/action_combat_content.md)) |
| `weapon_sets` | `weapon_set_` | `WeaponSetDefinition` | `skillIds`, entries and edges -> action_skills; `classIds` |

Polymorphic values carry `"_type"`: tile attributes (`warp`, `blocked`, ...) and event commands (`dialogue`, `give_item`, ...). Unknown fields are reported as warnings (typos).
The last two are files only for now (no F1 editor tab, not sent to clients) and are read by no combat module yet.
Ordering tip: create referenced documents first. Rules: [validation rules](/content/validation_rules.md). Adding a type: [playbook](/playbooks/add_content_type.md).
