---
type: Decision
title: "ADR 0010: Rename FADE to Morgana ORPG"
description: "The product and package rename, what changed, and what was intentionally left alone."
tags: [adr, naming]
code: ["settings.gradle.kts", "core-domain/src/main/kotlin/com/morgana/domain/sim/WorldSimulation.kt"]
symbols: ["morgana-orpg", "0xFADE2026L"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# ADR 0010: Rename FADE to Morgana ORPG

**Status**: Accepted (done).

**Changes**: packages `com.fade.*` -> `com.morgana.*`; Gradle group `com.morgana`, root project `morgana-orpg`; `Fade*` and `Mirage*` classes -> `Morgana*` (`FadeOrpgGame` -> `MorganaGame`, `MirageWorldRenderer` -> `MorganaWorldRenderer`); Android id `com.morgana.client.android`; Tiled property keys `fade*` -> `morgana*` (breaks metadata of old exports, [Tiled](/content/tiled_conversion.md)); thread name `morgana-20tps-sim-loop`.

**Left alone on purpose**: the RNG seed literal `0xFADE2026L` (a hex number; changing it would change deterministic outputs, [determinism](/domain/determinism_and_rng.md)); the verb "fade" (`fadeRoof`, LibGDX `setFadeScrollBars`); the repository folder name.

**Lesson**: do renames by moving directories, then regex with word boundaries, then run the full gate ([playbook](/playbooks/refactor_rename.md)).
