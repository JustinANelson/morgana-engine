---
type: Concept
title: "Persistence Layout on Disk"
description: "The server-data directory tree, file naming, JSON settings, and atomic write behavior."
tags: [persistence, files]
code: ["server-persistence/src/main/kotlin/com/morgana/persistence/DocumentStore.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/MorganaRepositories.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/SqliteStorage.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/StorageMigration.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ServerConfig.kt"]
symbols: ["JsonFileDocumentCollection", "InMemoryDocumentCollection", "WorldContentRepository", "StandardCopyOption.REPLACE_EXISTING", "classDiscriminator", "SqliteDocumentCollection", "SqliteDatabase", "IndexedDocumentCollection", "inTransaction", "PlayerDataBackend", "StorageMigration", "PLAYER_DB_FILE"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Persistence Layout on Disk

```
server-data/                      (gitignored; created on first start; repo root when run via Gradle)
  accounts/<id>.json
  characters/<id>.json
  world/<collection>/<prefix>_<id:3>.json     e.g. items/item_007.json, maps/map_001.json
  social/guilds/  social/mail/  social/market/  social/exploration/
  world-objects/map_<id>.json     things placed on maps (walls, chests, claims), one file per map; always JSON ([world state](/systems/world_state.md))
  server-state.json               world epoch, world switches and variables, the scheduler's cursor, last-fired record and one-offs
  admin-token.txt                 (only when the admin panel is on; owner-only where supported; see [admin panel](/server/admin_panel.md))
```
- `JsonFileDocumentCollection` writes `<id>.json` via temp file + atomic move, pretty-printed, `encodeDefaults = true`, polymorphic discriminator `_type`, `ignoreUnknownKeys = true`. Unsafe filename characters become `_`.
- `DocumentCollection` is the storage port (`findById/findAll/save/deleteById/count`); `InMemoryDocumentCollection` is the test adapter. A different backend (SQLite, MongoDB) implements the same port ([ADR 0002](/decisions/0002_json_document_persistence.md)).
- Content file stems: `map_`, `class_`, `item_`, `npc_`, `spell_`, `shop_`, `resource_`, `recipe_`, `quest_`, `event_`, `anim_` + zero-padded id ([content collections](/content/content_collections.md)).
- Accounts/characters are saved by the [coordinator](/server/coordinator.md); world content by F1 saves and the seeder.

## Player-data backends (Phase 16)
Only **player data** (accounts, characters, guilds, mail, market) can live in SQLite; **world content is always JSON files** so it stays diffable, hand-editable and shareable as [world packs](/content/world_packs.md) ([ADR 0005](/decisions/0005_data_first_content.md)).

`server.json` selects the backend (default `json`, so nothing changes unless you opt in):
```json
{ "storage": { "playerData": "sqlite" } }
```
```
server-data/
  player-data.db        (SQLite, WAL mode; replaces accounts/, characters/, social/ when selected)
  world/...             (unchanged JSON)
```
- `SqliteDocumentCollection` is one table per collection: `id TEXT PRIMARY KEY, doc TEXT` (the same JSON the file backend writes) plus indexed columns. It implements `IndexedDocumentCollection`, so `findByUsername`, `findByName` and `findByAccountId` are index lookups instead of reading every file. Indexes are created and back-filled automatically.
- `SqliteDatabase` keeps one connection behind a lock; WAL with `synchronous=FULL`, so committed data survives a crash and uncommitted data is never visible. `MorganaDataStore.inTransaction { }` groups writes: registration saves account + character together, and the 10-second autosave sweep is one commit. With JSON files it just runs the block (no transactions).
- `MorganaDataStore` is `AutoCloseable`; `MorganaServerRuntime.close()` closes it. Close stores you open yourself (Windows cannot delete an open `.db`).
- Migration: `.\gradlew.bat :server-app:storage -PcontentArgs="--migrate sqlite"` (or `json`) with the server stopped. `StorageMigration` refuses if the target already holds data, copies every collection in one transaction, reads each document back and compares it, then rewrites `server.json`. The old data is left in place. A failed or refused migration changes nothing.
- Tests: `Phase16SqliteTest` runs the same contract against both backends (plus injection, crash, concurrency, back-fill and migration), `Phase16SqliteServerTest` covers a real restart.
