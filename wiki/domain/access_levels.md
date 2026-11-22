---
type: Concept
title: "Access Levels and Permissions"
description: "The five access tiers and the permission flags each tier grants."
tags: [access, security]
code: ["core-domain/src/main/kotlin/com/morgana/domain/access/AccessLevel.kt"]
symbols: ["PermissionFlag", "SERVER_MAINTENANCE", "canOpenAdminPanel", "fromRank"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Access Levels and Permissions

| Rank | Level | Default permissions (cumulative) |
| :--- | :--- | :--- |
| 0 | Player | CHAT, PARTY, GUILD, TRADE |
| 1 | Monitor | + KICK_MUTE, WARP_TO_PLAYER, SUMMON_PLAYER, NARRATE |
| 2 | Mapper | + WARP_TO_MAP, EDIT_MAP |
| 3 | Developer | + EDIT_CONTENT, SPAWN_ITEM, BAN_PLAYER |
| 4 | Owner | all flags (adds SET_ACCESS, SERVER_MAINTENANCE) |

- `AccessLevel.hasPermission(flag, extra, revoked)`: revocations apply to everyone except Owner; accounts can carry `extraPermissions`.
- `canOpenAdminPanel`: Mapper and above, or anyone with `EDIT_MAP`/`EDIT_CONTENT`.
- Live map edits require `EDIT_MAP`; all other content edits require `EDIT_CONTENT` ([admin and live edit](/systems/admin_live_edit.md)).
- `/narrate` requires `NARRATE` ([role-play chat](/systems/roleplay_chat.md)).
- `/reload` requires `SERVER_MAINTENANCE` ([hot reload](/content/hot_reload.md)).
- Nameplate colors: Player white, Monitor sky, Mapper green, Developer purple, Owner gold.
- Who becomes Owner: the [owner policy](/server/owner_policy.md) (configured names or the first local account; no magic username). Applied by the [coordinator](/server/coordinator.md).
