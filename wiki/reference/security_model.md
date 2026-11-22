---
type: Reference
title: "Security Model"
description: "Authentication, owner bootstrap, password storage, protocol versioning, rate limits, and the remaining risks."
tags: [security, reference]
code: ["server-persistence/src/main/kotlin/com/morgana/persistence/PasswordHasher.kt", "server-app/src/main/kotlin/com/morgana/server/OwnerPolicy.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/MovementRules.kt"]
symbols: ["PBKDF2WithHmacSHA256", "MAX_AUTH_FAILURES", "grantsOwnerOnRegistration", "GRID_MIN_STEP_TICKS", "PROTOCOL_VERSION"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Security Model

| Area | Behavior | Where |
| :--- | :--- | :--- |
| Password storage | Salted PBKDF2-HMAC-SHA256, 120,000 iterations, 16-byte random salt, constant-time compare. Format `pbkdf2-sha256$iterations$salt$hash`. Legacy unsalted SHA-256 hashes still verify and are re-hashed on the next successful login | `PasswordHasher` |
| Connection limits | Per-address (IPv6 per /64) caps, connection-rate bucket, strikes leading to escalating temporary bans persisted in `ip-bans.json`, pre-login timeout, `/ipban` `/ipunban` `/ipbans` ([connection limits](/server/connection_limits.md)) | `ConnectionGate` |
| Flood limits | Token buckets per packet class, field-length caps, kick on sustained flooding, raw per-second ceiling ([packet limits](/server/packet_limits.md)) | `PacketLimiter`, `InputLimits`, `RawFloodGuard` |
| Login throttling | A connection is closed after 5 consecutive failed logins | `MAX_AUTH_FAILURES` in the coordinator |
| Protocol version | `Authenticate.protocolVersion` must equal `PROTOCOL_VERSION` (currently 1); a missing version decodes as 0 and is rejected with an "update your client" message before any account work | `handleAuthentication` |
| Owner access | See [owner policy](/server/owner_policy.md): configured owner names, or the first account over a loopback connection. No username is special | `OwnerPolicy` |
| Movement | Server-side step rate limit ([movement](/systems/movement.md)); rejected moves resync the client | `MovementRules` |
| Crafting stations | Station recipes need an opened station on the player's tile ([economy](/systems/economy.md)) | `CraftingAnchor` |
| Transport | Optional TLS (`off`/`optional`/`required`/`remote`), automatic self-signed certificates, invite-code fingerprint pinning, fail-closed setup ([TLS](/server/tls.md)) | `ServerTls`, `IdentityTrustManager` |
| Plugins | Exceptions inside hooks are logged and isolated ([hook API](/server/hook_api.md)) | `EventHookRegistry` |
| Content edits | Validated before applying ([ADR 0007](/decisions/0007_validate_before_apply.md)); permission flags per [access levels](/domain/access_levels.md) | `acceptEdit` |

## Known remaining risks
- Transport encryption is **opt-in** for dedicated servers (`tls.mode` defaults to `off`); with it off, credentials cross the network in cleartext and the server logs a warning for remote unencrypted logins. See [TLS and invite codes](/server/tls.md).
- Per-connection flood limits cover every client packet ([packet limits](/server/packet_limits.md)); per-address connection caps, connection-rate limits and temporary IP bans cover the rest ([connection limits](/server/connection_limits.md)). Gaps: floods that saturate the network link need a firewall, and behind a same-machine proxy every player counts as loopback and is exempt. `SLOW`/`HASTE` status effects do not change the movement limit.
- Ban state is an account flag checked at login; there is no IP-level blocking.

Decision record: [ADR 0011](/decisions/0011_security_baseline.md).
