---
type: Decision
title: "ADR 0012: TLS with Fingerprint Pinning"
description: "Why player-hosted worlds use automatic self-signed certificates verified by invite-code fingerprints instead of certificate authorities."
tags: [adr, security, tls]
code: ["server-app/src/main/kotlin/com/morgana/server/tls/ServerTls.kt", "shared-protocol/src/main/kotlin/com/morgana/protocol/tls/IdentityTrustManager.kt", "shared-protocol/src/main/kotlin/com/morgana/protocol/tls/ServerIdentity.kt"]
symbols: ["ServerTls", "IdentityTrustManager", "InviteCode"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# ADR 0012: TLS with Fingerprint Pinning

**Status**: Accepted (implemented after Phase 16).

**Context**: connections were cleartext, so passwords and traffic were readable on the network. The engine is meant to be a base for player-hosted worlds, whose hosts are non-technical, have no domain name, and often a changing IP. Certificate authorities need a domain, and asking hosts to run `keytool` or players to import certificates would block adoption.

**Decisions**
- Use standard **TLS** (Netty `SslHandler`, JDK provider, TLS 1.3/1.2) below the existing framing. No packet or protocol-version change.
- **Four modes** (`off`, `optional`, `required`, `remote`). Worlds hosted from the desktop client default to `remote`: loopback stays plain so local play needs nothing, every other connection must use TLS. Dedicated servers default to `off` until the operator opts in (a log warning flags unencrypted remote logins).
- **Automatic self-signed certificate** per world, stored with the world, generated with BouncyCastle (the JDK has no public API to create certificates; shelling out to `keytool` breaks with trimmed JREs; Netty's helper is unreliable on JDK 17).
- **Identity = SHA-256 fingerprint**, shared in an **invite code** (`morgana://host:port#fp=...`) like SSH/Mumble. Typed addresses fall back to trust-on-first-use with a clear prompt, and a later change triggers a warning. Real certificates (operator keystore or a TLS proxy) are verified through the platform trust store with host name checks.
- **Fail closed**: broken TLS configuration stops startup; clients never silently downgrade.
- Trust logic lives in JDK-only `:shared-protocol` so Android can reuse it; BouncyCastle is server-side only.

**Consequences**: players need no certificate files; hosts need no domain. A lost data folder or recreated certificate changes the fingerprint and warns every returning player. TOFU protects only subsequent connections, so invite codes are the verified path. Debugging network traffic with a sniffer is harder; server logs, the replay recorder and in-JVM tests are unaffected. Reachability (port forwarding, NAT) remains the larger obstacle for home hosts and is not addressed here.

See [TLS and invite codes](/server/tls.md) and the [security model](/reference/security_model.md).
