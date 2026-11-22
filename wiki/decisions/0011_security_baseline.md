---
type: Decision
title: "ADR 0011: Security Baseline"
description: "Salted password hashing, explicit owner bootstrap, protocol versioning, server-side rate limits, and login throttling."
tags: [adr, security]
code: ["server-persistence/src/main/kotlin/com/morgana/persistence/PasswordHasher.kt", "server-app/src/main/kotlin/com/morgana/server/OwnerPolicy.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/MovementRules.kt"]
symbols: ["PasswordHasher", "OwnerPolicy", "MovementRules"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# ADR 0011: Security Baseline

**Status**: Accepted (implemented in the known-issue burn-down after Phase 11).

**Context**: an audit of the code found unsalted SHA-256 passwords, an `admin` username that granted Owner to whoever registered it first, no client/server version check, unvalidated movement speed, and unenforced crafting stations.

**Decisions**
- **PBKDF2-HMAC-SHA256** with per-account salt via the JDK only (also works on Android), with transparent upgrade of legacy hashes at login ([security model](/reference/security_model.md)). Argon2/bcrypt were not chosen because they need a native/library dependency; revisit if one is adopted.
- **Owner policy** replaces username rules: configured names, else the first loopback account ([owner policy](/server/owner_policy.md)).
- **`PROTOCOL_VERSION`** in the auth handshake; mismatches fail early with a clear message ([wire format](/protocol/wire_format.md)).
- **Server-side movement limits** (`MovementRules`) with authoritative resync, and a **5-failure login cap** per connection.
- **Crafting stations** enforced through transient per-player anchors ([economy](/systems/economy.md)).
- Plugin exceptions are isolated so a bad hook cannot stop the tick.

**Not decided here**: transport encryption (later decided in [ADR 0012](/decisions/0012_tls_fingerprint_pinning.md)). Hashing was later moved off the coordinator lock (`handleClientPacketOffLock`).
