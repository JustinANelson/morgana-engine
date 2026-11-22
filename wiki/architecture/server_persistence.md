---
type: Module
title: "Module: server-persistence"
description: "JSON document collections, repositories, content loader, schema exporter, Tiled converter, and the game scaffolder."
tags: [module, persistence]
code: ["server-persistence/src/main/kotlin/com/morgana/persistence"]
symbols: ["JsonFileDocumentCollection", "ContentLoader", "ContentSchemaExporter", "GameScaffolder", "TiledMapConverter", "PasswordHasher"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Module: `:server-persistence`

| Piece | Purpose |
| :--- | :--- |
| `DocumentCollection` / `JsonFileDocumentCollection` / `InMemoryDocumentCollection` | Storage port and adapters ([persistence layout](/server/persistence_layout.md)) |
| `MorganaDataStore`, `WorldContentRepository`, `AccountRepository`, `CharacterRepository`, `SocialMarketRepository` | Repositories |
| `ContentLoader` | Reads content files without throwing; returns file-level issues ([hot reload](/content/hot_reload.md)) |
| `ContentSchemaExporter` | JSON Schemas generated from the Kotlin models ([content collections](/content/content_collections.md)) |
| `PasswordHasher` | Salted PBKDF2 password hashing with legacy-hash upgrade ([security model](/reference/security_model.md)) |
| `GameScaffolder` | Writes a new game project (`newGame`) or seeds an empty data directory with the same content ([blank game](/content/blank_game.md)) |
| `TiledMapConverter` | Tiled `.tmj` import/export ([Tiled conversion](/content/tiled_conversion.md)) |

Depends only on `:core-domain` and serialization-json. Tests: `Phase3PersistenceTest`, `Phase3TiledAndInMemoryTest`, `Phase11ContentPipelineTest`.
