---
type: Simulation System
title: "Deterministic Replay"
description: "Input-log recorder, replay player, state hashing, JSON codec, and the golden-master test workflow."
tags: [replay, determinism, testing]
code: ["core-domain/src/main/kotlin/com/morgana/domain/replay/ReplayModels.kt", "core-domain/src/main/kotlin/com/morgana/domain/replay/ReplayEngine.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ReplayLogCodec.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaServerRuntime.kt", "server-app/src/main/kotlin/com/morgana/server/tools/ContentTool.kt"]
symbols: ["SimInput", "ReplayLog", "InputRecorder", "ReplayPlayer", "StateHasher", "ReplayLogCodec", "startRecording", "RECORD_REPLAY_ENV", "replayLog", "SetPlayerState", "markGap"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Deterministic Replay

Because the simulation is deterministic ([determinism](/domain/determinism_and_rng.md)), a seed plus the ordered list of inputs reproduces a run exactly. Phase 12 adds the tooling.

- **`SimInput`** (sealed, `@Serializable`): one case per externally driven `WorldSimulation` call, including `Tick(deltaMs)`, because time is an input. Each case applies itself with `applyTo(sim)`.
- **`InputRecorder`** wraps a simulation, applies and records inputs, stores a `ReplayCheckpoint` hash every N ticks, and `finish()` returns a `ReplayLog` (seed, content fingerprint, inputs, checkpoints, final tick and hash).
- **`ReplayPlayer.replay(freshSim, log)`** re-applies the inputs and reports the first diverging checkpoint plus a final-state check. `ReplayResult.ok` requires matching content and no divergences.
- **`StateHasher`**: 64-bit FNV-1a over the data-class rendering of players, NPCs, ground items, projectiles, resources, doors, parties, guilds, trades, tick, entity id counter and the RNG state (`RandomSource.stateFingerprint`). `contentFingerprint` hashes the loaded definitions so a replay against different content is flagged.
- **`ReplayLogCodec`** (`:server-persistence`) reads/writes the log as pretty JSON (`_type` discriminator).

## Rules
1. The replay must start from a fresh simulation built with the log's seed and identical content, no players yet.
2. Any new externally triggered state change must be expressible as a `SimInput`; otherwise it cannot be replayed. Coordinator-side player edits (hotbar, inventory swap, facing) are recorded as `SetPlayerState`. Actions that still bypass the recorder call `recorder?.markGap(reason)`: party and guild actions (random ids, side maps), live `F1` content edits and content hot reload. A log with `gaps` is flagged incomplete.
3. A new field in simulation state that is not part of `StateHasher.canonicalState` is invisible to replay checks; add it there.
4. State that exists before recording starts (the world epoch, world switches and variables, the scheduler's cursor, last-fired record and one-offs) is captured **when the recorder is made** and stored as `starting*` fields of the log; `ReplayPlayer` restores them. A new kind of persisted state that survives restarts needs a `starting*` field too, or a recording of a live server will not replay.

## Golden master
`Phase12GoldenReplayTest` replays `server-persistence/src/test/resources/golden/blank_world_session.replay.json` against the seeded blank world ([blank game](/content/blank_game.md)). When a deliberate gameplay or starter-content change breaks it, re-record with `MORGANA_REGEN_GOLDEN=1 .\gradlew.bat :server-persistence:test --tests "*Phase12GoldenReplayTest*"`, review the diff, and commit it with the change. An unexpected failure means determinism broke: see [debug determinism](/playbooks/debug_determinism.md).

## Recording a live server
Set `MORGANA_RECORD_REPLAY=<file>` before starting the server (`MorganaServerRuntime.start` calls `MorganaWorldCoordinator.startRecording()` before the first tick or login; it refuses to start later because a replay needs the freshly loaded world). The log is written on shutdown and the server logs whether it is complete. Replay it against the same `server-data/` with `.\gradlew.bat :server-app:replayLog -PcontentArgs="--file <log>"`; it exits 1 and prints the first diverging tick on mismatch. Players' full state is stored in `SpawnPlayer` inputs, so character files may change afterwards without breaking the replay, but world content and persisted guilds must be unchanged.
