---
type: Reference
title: "Connection Limits and IP Bans"
description: "Per-address connection caps, connection-rate limits, strikes and temporary bans, the pre-login timeout, and operator commands."
tags: [security, connections, bans, server]
code: ["server-app/src/main/kotlin/com/morgana/server/ConnectionGate.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaNettyServer.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaServerRuntime.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/AddressRange.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ServerConfig.kt"]
symbols: ["ConnectionGate", "ConnectionGateSettings", "GateVerdict", "AbuseReporter", "IpBan", "ConnectionsConfig", "AddressRange", "handleIpCommand", "disconnectBanned", "keyOf", "preLoginSeconds"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Connection Limits and IP Bans

[Packet limits](/server/packet_limits.md) bound what one connection can do; this page bounds how many connections one address can have and turns repeated abuse into temporary bans. `ConnectionGate` runs when a socket is accepted, **before TLS and before any bytes are read**, so a refused peer costs almost nothing.

## What is checked (in order)
1. **Exempt?** Loopback (this machine) and the configured `allow` list skip everything below (and are never struck or banned). They still count toward the server-wide total.
2. **Banned?** An active ban refuses the connection.
3. **Server full?** `maxTotal` simultaneous connections. Not penalized.
4. **Per-address cap**: `maxPerIp` simultaneous connections (default 8). Not penalized, because a household or school sharing one address legitimately hits it.
5. **Connection rate**: token bucket per address, `burst` 10 then `newPerMinute` 30. Running dry refuses the connection **and counts as a strike**.

IPv6 clients are tracked per **/64 block**, since one subscriber controls a whole /64 and could otherwise rotate addresses. IPv4-mapped IPv6 addresses count as IPv4.

## Strikes and bans
Other parts of the server report abuse through `AbuseReporter.strike`:
| Source | Strike reason |
| :--- | :--- |
| Connection rate exceeded | `connection flood` |
| Five failed logins on one connection (existing cap) | `repeated failed logins` |
| Packet limiter kicks a connection ([packet limits](/server/packet_limits.md)) | `packet flood` |
| Raw message ceiling or oversized pre-login message | `message flood` |
| No login within `preLoginSeconds` | `connection idle before login` |

`strikesToBan` strikes within 10 minutes (default 5) ban the address for `banMinutes` (default 10). Each repeat doubles the ban up to `maxBanMinutes` (default 24 h); a day without a ban resets the escalation. The practical effect on password guessing: 5 failed logins close a connection, and five such rounds (25 guesses) earn a ban.

**The browser (WebSocket) port** (since 2026-11-13): it also serves the browser client's page and files, dozens of short HTTP requests per page load, which used to count as a connection flood and ban the player's address. There, accepting a socket only checks `isBanned`; the full gate (rate, per-address cap, strikes) runs in `WebSocketUpgradeGate` when a request asks to become a WebSocket game session, and that session starts at the handshake. See [play on a phone](/playbooks/play_on_a_phone.md).

When an address becomes banned, `coordinator.disconnectBanned(key)` closes all of its live connections with a notice, so the ban takes effect immediately and not only for the next connect. Refused connections are closed silently (no protocol exists yet to explain why).

## Pre-login timeout
A connection that has not logged in after `preLoginSeconds` (default 30, so also covers a slow TLS handshake) is closed and counted as a strike, which stops silent connections from holding slots. Logged-in players are never timed out for being idle.

## Operator commands (permission `BAN_PLAYER`, so Developer and Owner)
In the command box or typed into chat:
- `/ipbans`: active bans with minutes left and reason (first 10)
- `/ipban <ip> [minutes] [reason]`: default 1440 minutes, maximum 43,200 (30 days); accepts IPv4/IPv6 literals only (never resolves names); an IPv6 address bans its whole /64. Refused for exempt addresses.
- `/ipunban <ip>`: lifts the ban and forgives the strikes

Bans are persisted in `<data dir>/ip-bans.json` (atomic writes, expired entries dropped at startup, an unreadable file is ignored with a warning), so restarting the server does not clear them. Editing the file by hand while the server runs is not supported.

## Configuration
`connections` in `server.json` (all fields optional; validated at startup, unknown fields and out-of-range values stop the server with a message):
```json
{ "connections": { "maxPerIp": 8, "maxTotal": 1000, "burst": 10, "newPerMinute": 30,
                   "strikesToBan": 5, "banMinutes": 10, "maxBanMinutes": 1440,
                   "preLoginSeconds": 30, "allow": ["10.0.0.0/8", "203.0.113.7"] } }
```
`"enabled": false` switches the gate off. Changes need a restart.

## Behind a proxy or tunnel
If a reverse proxy or TLS tunnel (stunnel, a TCP forwarder) connects from the same machine, every player appears as **loopback** and is exempt from these limits (and counts as local for the first-owner rule in [owner policy](/server/owner_policy.md)). If it runs on another host, all players share the proxy's address and its 8-connection cap. Either way enforce limits in the proxy, or add the proxy to `allow`. The PROXY protocol is not supported. Terminate [TLS](/server/tls.md) in the server itself where possible.

## Not covered
No limits on accounts registered per address across many connections beyond the login rate and connection limits above, no persistent per-account bans by address, no automatic unban notifications, and no protection against floods that saturate the network link (use a firewall or the host's DDoS protection).

Tests: `Phase19ConnectionGateTest` (caps, IPv6 blocks, exemptions, rate, escalation, persistence, memory bound), `Phase19AbuseWiringTest` (strike sources, live disconnects, commands and permissions), `Phase19ConnectionLimitsEndToEndTest` (real sockets via the machine's LAN address), `Phase19ConnectionsConfigTest`.
