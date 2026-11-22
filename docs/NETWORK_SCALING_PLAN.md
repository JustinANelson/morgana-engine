# Network Scaling Plan

**Date**: 2026-11-19  
**Context**: After offloading periodic autosaves and exploration writes to `AsyncPersistenceWriter`, `:server-app:stressTest` (`--counts 250,500,1000,2000,4000 --ticks 200 --layout spread --aoi --sqlite`) showed the simulation and coordinator loop staying well under the 50 ms (20 TPS) budget up to **4,000 players** (`4.07 ms` avg, `7.45 ms` p95, `20.55 ms` max, `0` over-budget ticks). However, outbound packet fan-out scaled quadratically to **2,002,141 packets/tick** (`1,000` players per map × watchers), meaning network serialization and socket bandwidth will bottleneck before CPU simulation.

The following four optimizations are queued for future implementation, ordered from highest impact / lowest risk to largest protocol change.

---

## 1. Serialize Broadcasts Once Per Packet, Not Per Recipient
* **Problem**: `broadcastToMap` and `broadcastNear` in `MorganaWorldCoordinator` call `session.send(packet)` for each matching `ClientSession`. Each Netty channel pipeline then JSON-serializes the exact same `ServerPacket` (e.g., `PlayerUpdated`, `NpcUpdated`, `ChatBroadcast`) independently.
* **Plan**:
  * Add an optional pre-encoded broadcast path on `ClientSession` / `MorganaNettyServer` (e.g., encode `ServerPacket` once to UTF-8 JSON bytes or a shared reference-counted `ByteBuf` / WebSocket frame) and write the shared frame to all matching channels.
  * Keeps `:shared-protocol` and client code unchanged.

## 2. Coalesce Entity Updates Per Tick
* **Problem**: Every player movement or combat update emits an individual `PlayerUpdated` packet to every nearby watcher, producing $O(N^2)$ small packets per tick on crowded maps.
* **Plan**:
  * Buffer per-map `PublicPlayerSnapshot` (and optionally NPC) changes during a tick and flush a single batched delta packet per map/cell at the end of `stepServerTick`.
  * Requires a new `ServerPacket` batch variant in `:shared-protocol` and handling it in `:client-core` (`ClientWorldState`), with fallback to individual packets for older clients via capability negotiation.

## 3. Switch Wire Format from JSON to CBOR
* **Problem**: JSON text frames are verbose and slower to serialize/parse at high packet volumes.
* **Plan**:
  * `:shared-protocol` already includes an unused CBOR codec ([ADR 0009](../wiki/decisions/0009_json_wire_format.md)).
  * Negotiate CBOR framing during handshake (`clientCapabilities` / `PROTOCOL_VERSION`) for Netty TCP clients (`:client-net-netty` / `:client-desktop` / `:client-android`) and optionally WebSocket binary frames (`:client-web`), keeping JSON as a fallback or debug mode.

## 4. Cap Visible Nearby Players in `AreaOfInterest`
* **Problem**: `AreaOfInterest` culls updates by grid cell, but in dense hubs (e.g., 1,000 players standing in the same town cell), every observer still receives updates for every player in range.
* **Plan**:
  * Add a configurable `maxVisiblePlayers` cap per observer in `AreaOfInterestConfig` (`server.json`), prioritizing party/guild members and closest-distance players when a cell exceeds the cap.
  * Keeps outbound bandwidth bounded even under extreme hub crowding.
