---
type: Playbook
title: "Add a New Content Type"
description: "Every file to touch when adding a content collection (model to editor), in order."
tags: [playbook, content]
code: ["core-domain/src/main/kotlin/com/morgana/domain/validation/ContentValidation.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/MorganaRepositories.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ContentLoader.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ContentSchemaExporter.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt"]
symbols: ["ContentCollection", "ContentCatalog", "serializerFor", "loadIntoSimulation", "buildCatalogDto"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Add a New Content Type

1. **Model** (`core-domain/.../content/`): `@Serializable data class XDefinition(id, name, ..., schemaVersion = ContentSchema.CURRENT_VERSION)`.
2. **Validation** (`validation/ContentValidation.kt`): add a `ContentCollection` entry (directory, prefix), a `ContentCatalog` field, a `Collector.x()` method, and call it in `validate`. Add reference checks for every id field ([validation rules](/content/validation_rules.md)).
3. **Simulation**: state map in `WorldSimulation`; `applyLiveXEditImpl` in `AdminSystem.kt` + public delegate; include it in `toContentCatalog()` ([admin](/systems/admin_live_edit.md)).
4. **Persistence**: collection in `WorldContentRepository` (+ `loadIntoSimulation`), read in `ContentLoader.load`, serializer in `ContentSchemaExporter.serializerFor`, an example document in a test fixture (the shipped blank game has only a class, an item, an NPC and a map).
5. **Schemas**: `.\gradlew.bat :server-app:exportContentSchemas`; commit `docs/schemas/`.
6. **Protocol**: `Save<X>Definition` client packet and the `WorldContentCatalogDto` field ([add a packet](/playbooks/add_packet.md)).
7. **Server**: coordinator branch using `acceptEdit`, persist, `buildCatalogDto`, and a `sync(...)` line in `reloadContent` ([coordinator](/server/coordinator.md)).
8. **Client**: catalog handling in `ClientWorldState` and an F1 editor tab ([F1 editor](/client/f1_editor.md)); files work even before the editor exists.
9. **Tests**: validator cases, loader/file round trip, coordinator reject/accept, protocol codec round trip.
    A type can ship in stages: `action_skills` and `weapon_sets` did steps 1, 2, 4, 5 and 9 plus the state map and `reloadContent` line first (files only), leaving the live edit, packet and editor for later.
10. **Wiki**: [content collections](/content/content_collections.md), this playbook if steps changed, log entry.
