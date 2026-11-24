---
type: Simulation System
title: "Scheduler"
description: "Real-world-time events (a weekly reset, a daily bonus, a booked window) with advance warnings, announced in chat and passed to Lua and other systems, deterministic through a recorded wall-clock input."
tags: [systems, scheduler, time, replay, lua]
code: ["core-domain/src/main/kotlin/com/morgana/domain/systems/SchedulerSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/game/Schedule.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/SystemSeams.kt", "core-domain/src/main/kotlin/com/morgana/domain/replay/ReplayModels.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ServerState.kt", "core-domain/src/test/kotlin/com/morgana/domain/SchedulerTest.kt", "script-luak/src/test/kotlin/com/morgana/script/luak/LuaSchedulerTest.kt", "server-app/src/test/kotlin/com/morgana/server/SchedulerPersistenceTest.kt"]
symbols: ["SchedulerSystem", "ScheduleDefinition", "ScheduleRules", "ScheduledOneShot", "ScheduleTrigger", "WallClockListener", "ScheduleHandler", "SimInput", "scheduleOnce", "advanceWallClock"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-24T18:00:00Z }
stale_after: 2027-05-23T00:00:00Z
---

# Scheduler

The `scheduler` system ([optional modules](/server/optional_modules.md), tags: survival, rpg, role-play) runs things at real-world times: a weekly reset on Saturday at 10:00 New York time, a daily bonus, a launch, or a window booked while the game runs. For each occurrence it raises **advance warnings** and a **start**, announces them in chat, and tells every [system](/systems/system_seams.md) and Lua plugin that listens. A game uses it with no engine code.

## Declaring schedules (`game.json` `schedules`)
```json
"schedules": [
  { "id": "cleansing", "name": "Weekly Cleansing", "kind": "WEEKLY", "timezone": "America/New_York",
    "dayOfWeek": "SATURDAY", "time": "10:00", "warningsSeconds": [3600, 900, 60],
    "warningMessage": "{name} begins in {remaining}.", "fireMessage": "{name} begins now.",
    "lateGraceSeconds": 120 }
]
```
| Field | Meaning |
| :--- | :--- |
| `id` | `a-z 0-9 _ -`, unique. What Lua hooks and `/schedule fire` call it. |
| `kind` | `DAILY` (`time`), `WEEKLY` (`dayOfWeek` + `time`), `INTERVAL` (`intervalSeconds`, optional `at` anchor, else the Unix epoch) or `ONCE` (`at`). |
| `timezone` | An IANA id (`America/New_York`, `UTC`). Times are local wall-clock times, so 10:00 stays 10:00 across daylight saving; a time that does not exist that day moves on, and one that happens twice happens once. |
| `at` | ISO date-time, local to `timezone` (`2026-12-05T10:00`) or with an offset. |
| `warningsSeconds` | Up to 8 warnings, each 1 to 2,592,000 seconds (30 days) before the start. |
| `warningMessage`, `fireMessage` | Chat text; `{name}` and (warnings) `{remaining}` ("1 hour", "15 minutes", "1 minute 30 seconds"). Blank sends nothing. |
| `messageColorHex` | Announcement colour. |
| `lateGraceSeconds` | How late a start may still happen (default 120). Later than that, it is skipped. |
| `enabled` | Per-schedule kill switch. |

Validation reports a pointer such as `/schedules/0/timezone` for each bad field (`GameDefinitionValidator`, up to 64 schedules).

## Time zones: a port, because the shared domain stays pure
Working out "Saturday 10:00 in New York" needs a time-zone database, and `:core-domain` may not use JVM-only APIs (the browser client is compiled from it; `EngineBoundaryTest` enforces it). So the scheduler asks a `ScheduleCalendar` port (`WorldSimulation.scheduleCalendar`): the default `UtcScheduleCalendar` is pure Kotlin and knows only UTC (`UTC`, `Z`, `GMT`, `Etc/UTC`), and the server installs `JavaTimeScheduleCalendar` (`:server-persistence`, any IANA zone, daylight saving) when the coordinator starts. A schedule in a zone the installed calendar does not know has no occurrences (it never fires at a wrong time) and the server logs a warning at boot. The validator checks a schedule's shape and that its zone name is well formed; whether the zone exists is the calendar's to say. `JavaTimeScheduleCalendarTest` checks the two calendars agree exactly for UTC and tests daylight saving. A time-zone database update in a newer JVM can move future occurrences; recorded replays store the wall-clock times they were fed, not these results.

## How it stays deterministic
The simulation never reads a clock. The server tells it the wall-clock time about once a second (`WALL_CLOCK_INTERVAL_TICKS`, 20 ticks) through the **recorded input `SimInput.WallClock(millis)`**, so a replay feeds the same times back and raises the same events. Everything else is a pure function of that time, the definitions and four pieces of state the simulation keeps (hashed by `StateHasher`, written only when non-empty so older replays are unchanged): `wallClockMillis`, `scheduleCursorMillis` (the time up to which things have been raised), `scheduleLastFired` (id to the last occurrence started) and `scheduleOneShots`.

Rules (see `SchedulerTest`):
- **Never retroactive.** The first reading only sets the cursor. A start fires only if it is due within its `lateGraceSeconds`; if the server was down longer, it is skipped rather than fired hours late.
- **Warnings are for the future.** A warning is raised only while its occurrence is still ahead. If several come due in one reading (after a pause), only the one closest to the start is raised, not a burst.
- **No repeats.** `scheduleLastFired` stops a restart starting an occurrence twice, even if the saved cursor is old (a crash). The server saves its state **immediately** when something fires.
- **Persistence.** `ServerState` (`server-state.json`) holds the cursor, last-fired record and one-offs; the coordinator restores them at boot.

## One-offs booked at runtime
`WorldSimulation.scheduleOnce(id, atMillis, name, warningsSeconds, data, warningMessage, fireMessage)` books a window while the game runs (a guild chooses when its base can be attacked). It raises the same triggers, carries `data` on each, and is removed once it has started or its time has passed. It refuses when the scheduler is off, the clock has not started, the time is not in the future, or `id` is a configured schedule's; booking an id again replaces it. `cancelSchedule(id)` removes it. Lua does the same with `world.schedule_once` / `world.cancel_schedule`.

## Reacting
| Who | How |
| :--- | :--- |
| Kotlin systems | implement `ScheduleHandler.onSchedule(sim, trigger, out)` and `install` it ([system seams](/systems/system_seams.md)); `WallClockListener` is the lower-level seam for anything that follows real time |
| Lua plugins | `register.schedule{ id = ..., on_warning = fn(info, world), on_fire = fn(info, world) }` ([API reference](/../docs/API_REFERENCE.md)); `world.now_ms` is the reported time |
| Other code | the `ScheduleTriggered` simulation event (server-side only; players see the chat announcement) |

A `ScheduleTrigger` carries `scheduleId`, `name`, `phase` (`WARNING` or `FIRE`), `secondsBefore`, `occurrenceMillis`, `recurring` and `data`. Booking and cancelling from Lua use the `state` script capability.

## Commands
`/schedule` lists what is coming and when (in each schedule's own time zone); `/schedule fire <id>` (server maintainers) announces and starts one for testing without touching the cursor or the last-fired record, so the real occurrence still happens.

## Not built yet
A client-side countdown banner (the warnings arrive as chat and notices today), a map or region filter on announcements (they go to everyone), and an F1 editor form for `schedules` (JSON only).
