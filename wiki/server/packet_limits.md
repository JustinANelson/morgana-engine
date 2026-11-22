---
type: Reference
title: "Packet Flood Limits"
description: "Per-connection rate budgets, field-length caps, kick rules, and the raw message ceiling that protect the server from floods."
tags: [security, rate-limits, server]
code: ["server-app/src/main/kotlin/com/morgana/server/PacketLimits.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaNettyServer.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ServerConfig.kt"]
symbols: ["PacketLimiter", "PacketClass", "PacketLimitSettings", "LimitDecision", "InputLimits", "RawFloodGuard", "LimitsConfig", "admit", "classify"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Packet Flood Limits

Three layers stop one connection from overwhelming the server or other players. They sit in front of game rules, so a dropped packet never reaches the simulation, the database, other players, or a [replay recording](/systems/deterministic_replay.md).

## 1. Raw ceiling (network thread, before JSON decoding)
`RawFloodGuard` in `MorganaNettyServer`: more than **400 messages in one second** closes the connection (a real client sends at most about 22 per second), and a connection that has **not logged in** may not send a message over **8,192 characters** (a login is under 1 KB). The frame decoder itself still allows 2 MiB frames for authenticated F1 map saves.

## 2. Per-class token buckets (`PacketLimiter`)
Every client packet is classified (`PacketLimiter.classify`, an exhaustive `when`, so a new packet type does not compile until it has a budget). Each connection has a bucket per class: up to *burst* packets at once, refilled per minute.

| Class | Packets | Burst | Refill/min | Kick after N drops |
| :--- | :--- | :--- | :--- | :--- |
| `AUTH` | login | 6 | 12 | 20 |
| `PRE_AUTH` | anything but login/ping before login | 5 | 6 | 10 |
| `PING` | ping | 20 | 120 | 100 |
| `CHAT` | chat | 6 | 40 | 100 |
| `MOVE` | move, face | 40 | 1800 | 100 |
| `ACTION` | attack, cast, items, equipment, hotbar, dialogue, map resync, world map request | 20 | 600 | 100 |
| `ECONOMY` | shop, bank, craft, trade, party, guild, mail, friends, market, ignore | 10 | 180 | 100 |
| `COMMAND` | slash commands | 10 | 120 | 100 |
| `EDIT` | `F1` saves, catalog request | 10 | 60 | 100 |

The budgets leave room for the busiest legitimate client (free pixel movement, about 22 moves per second) with 30 percent headroom, and the 40-packet movement burst absorbs a lag spike delivering buffered moves.

**Policy**: an over-budget packet is dropped. The player gets "Slow down: too many X requests." at most every 5 s (never for connections that have not logged in). If a connection accumulates the class's *kick after* drops within one 10 s window it is told why (if logged in), logged at WARN, and closed; later packets from it are ignored without re-kicking. `AUTH` is checked **before password hashing** (`handleClientPacketOffLock`), so a login flood cannot burn CPU, and its burst of 6 is above the existing 5-failed-login cap so that cap still applies first.

**Time** is game ticks x 50 ms (`simulation.currentTick`, now `@Volatile`), not the wall clock: the limiter is deterministic, tests need no sleeping, and the numbers above are per second of game time. If the tick loop stalls, no refill happens, which only makes limits stricter.

## 3. Field length caps (`InputLimits`)
Refused with a system message before anything is stored, hashed or broadcast: password 128 characters, chat 300, slash command 300, player-name arguments 32, guild name/MOTD 200, guild tag 8. A password over the cap is rejected before PBKDF2 runs. A connection that is already logged in cannot send another login (one account per connection), so a single socket cannot register accounts in bulk.

## Configuration
`limits` in `server.json` (on by default; scale 0.1 to 100, validated):
```json
{ "limits": { "enabled": true, "scale": 2.0 } }
```
`scale` multiplies every burst and refill (for example 2.0 for a load test or trusted bots); `enabled: false` switches layer 2 off (the raw ceiling and field caps always apply). Changing it needs a restart. `PacketLimiter.droppedCounts()` and `kickCount()` expose totals per class for monitoring.

## Not covered
Per-address limits and temporary bans are a separate layer ([connection limits](/server/connection_limits.md)): a connection kicked here counts as a strike against its address. Drops are not persisted, and a distributed flood of connections is still a job for the operating system or a firewall. `SLOW`/`HASTE` status effects do not change the movement rules ([movement](/systems/movement.md)).

Tests: `Phase18PacketLimiterTest` (buckets, kick window, notices, scale), `Phase18FloodProtectionTest` (coordinator behavior, normal play never limited, replay unaffected), `Phase18FloodEndToEndTest` (real sockets), `Phase18LimitsConfigTest`.
