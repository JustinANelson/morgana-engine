---
type: Known Issue
title: "ServerNoticeAndCooldownTest Fails Intermittently Under Load"
description: "client-core's ServerNoticeAndCooldownTest ('the server's exact cooldown replaces the estimate') failed twice in a full gradlew check run alongside heavy tasks and passed on every rerun, so it looks timing-sensitive."
tags: [testing, client, flaky]
code: ["client-core/src/test"]
symbols: ["ServerNoticeAndCooldownTest"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-21T12:00:00Z }
stale_after: 2027-05-20T00:00:00Z
---

# ServerNoticeAndCooldownTest Fails Intermittently Under Load

Seen twice on 2026-11-21 during `.\gradlew.bat check` (once right after a long art export, once with the Gradle daemon busy): `ServerNoticeAndCooldownTest > the server's exact cooldown replaces the estimate and stops further estimating()` failed in `:client-core:test`. Running that test alone, and the whole `check` again straight afterwards, passed both times. No change in either run touched `client-core`.

## Suspicion
The test depends on elapsed time between the estimate and the server's exact cooldown, so a slow machine can land on the wrong side of it. Not yet investigated.

## Suggested fix
Drive the test with a fake clock instead of wall time, then run it in a loop (`--rerun-tasks`) under CPU load to confirm it is stable.
