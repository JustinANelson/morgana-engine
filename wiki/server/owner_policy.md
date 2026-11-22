---
type: Concept
title: "Owner Policy"
description: "Who becomes Owner (access rank 4): configured owner names or the first local account, never a magic username."
tags: [security, access]
code: ["server-app/src/main/kotlin/com/morgana/server/OwnerPolicy.kt", "server-app/src/main/kotlin/com/morgana/server/tools/ContentTool.kt"]
symbols: ["grantsOwnerOnRegistration", "MORGANA_OWNERS", "owners.txt", "isConfiguredOwner", "grantOwner"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Owner Policy (`OwnerPolicy`)

Owner is the only level that can set access, reload content, and edit everything ([access levels](/domain/access_levels.md)).

**Rules**
1. **Configured owners**: usernames in env `MORGANA_OWNERS` (comma separated) or in `server-data/owners.txt` (one per line, `#` comments). Matching is case-insensitive and the file is re-read on every check, so adding a name needs no restart.
   - Existing accounts are **promoted at their next login**; new registrations of a listed name start as Owner.
   - Add a name with `.\gradlew.bat :server-app:grantOwner -PcontentArgs="--user Alice"` (or edit the file).
2. **Local bootstrap**: only when *no* owners are configured, the very first account on a server with no accounts becomes Owner **if it connects over loopback** (`ClientSession.isLoopback`: the PC running the server, including the desktop client's embedded server).
3. Anything else registers as Player. The name `admin` has no special meaning.

**Dedicated remote server**: nobody is Owner until you list a name; do that before opening the port. Tests: `SecurityHardeningTest`.
History and rationale: [ADR 0011](/decisions/0011_security_baseline.md); wider picture: [security model](/reference/security_model.md); where it is applied: [coordinator](/server/coordinator.md).
