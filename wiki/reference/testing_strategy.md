---
type: Reference
title: "Testing Strategy"
description: "Where tests live, what each suite covers, and how to run them reliably."
tags: [testing]
code: ["core-domain/src/test", "server-persistence/src/test", "server-app/src/test", "client-core/src/test", "dev-tools/src/test", "tilegen/src/test"]
symbols: ["Phase2WorldSimulationTest", "Phase11ContentReloadTest", "Phase11ContentPipelineTest", "WikiToolingTest", "SecurityHardeningTest", "HardeningRulesTest"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Testing Strategy

| Suite | Module | Covers |
| :--- | :--- | :--- |
| `Phase1DomainModelTest` | core-domain | Inventory, maps, characters, access levels |
| `Phase2WorldSimulationTest` | core-domain | Combat, loot, party XP, movement/warps, crafting, quests, trading, live edits (seeded) |
| `Phase1ProtocolCodecTest` | shared-protocol | Packet JSON/CBOR round trips |
| `Phase3PersistenceTest`, `Phase3TiledAndInMemoryTest` | server-persistence | Seeder, collections, Tiled round trip |
| `Phase11ContentPipelineTest` | server-persistence | Loader, validator, schemas (and schema drift against `docs/schemas`) |
| `FormulaTest`, `GameDefinitionValidatorTest`, `MorganaClassicOracleTest`, `OracleMatchesOriginalCodeTest` | core-domain | Expression language, game-definition validation, and exact equivalence of Morgana Classic with the original hard-coded formulas ([game definition](/domain/game_definition.md)) |
| `GameDefinitionLoaderTest` | server-persistence | Strict `game.json` loading, a second hand-written game, schema drift |
| `GameCommandTest` | server-app | The `gameDefinition` tool |
| `Phase4ServerCoordinatorTest` | server-app | Auth, Owner rule, snapshots, F1 broadcast, real TCP socket |
| `Phase11ContentReloadTest` | server-app | Reject bad F1 saves, hot reload, `/reload`, watcher debounce |
| `Phase5And6ClientStateTest` | client-core | Client state/audio without GL |
| `HardeningRulesTest` | core-domain | Movement rate limit, crafting stations, autorun events, hook events, map-link warnings |
| `HardeningPersistenceTest` | server-persistence | Password hashing/upgrade, full-fidelity Tiled round trip |
| `SecurityHardeningTest` | server-app | Protocol version, owner policy, login upgrade/throttle, hook dispatch, shared runtime hot reload |
| `TileImageTest`, `ProceduralTilesetGeneratorTest`, `ThemedTilesetGeneratorTest` | tilegen | Tile image blending, seamless noise, per-theme tile sets (no Gdx natives needed) |
| `WikiToolingTest`, `RepoHygieneTest` | dev-tools | Wiki lint/index/impact; guard against the stale `com/fade` tree |
| `AndroidClientConfigTest` | client-android | Android defaults |

- Tests use `@TempDir` and fixed seeds; they must not touch `server-data`.
- Gradle build cache can make `test` print nothing: use `cleanTest test --no-build-cache` ([gate](/playbooks/verification_gate.md)).
- Gaps worth filling: replay/golden-master tests, Android TLS UI.

Known flaky test: [ServerNoticeAndCooldownTest](/issues/flaky_cooldown_notice_test.md) has failed under heavy load and passed on rerun.
