---
type: Reference
title: "Moderation Tools"
description: "Bans, mutes, kicks with reasons, a player report queue and an audit log: commands, who may use them, what is stored and what is not done yet."
tags: [moderation, admin, safety, commands]
code: ["server-app/src/main/kotlin/com/morgana/server/moderation/ModerationService.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt"]
symbols: ["ModerationService", "Sanction", "Report", "AuditEntry", "handleModerationCommand", "handleServerCommand"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-12T20:00:00Z }
stale_after: 2027-05-11T00:00:00Z
---

# Moderation Tools

Chat commands for the people who run a realm, plus a way for players to ask for help. All of it works from the game's chat box (`/command`); the same actions are also in the [web admin panel](/server/admin_panel.md) (off by default).

## Commands
| Command | Who | What it does |
| :--- | :--- | :--- |
| `/kick <player> [reason]` | Monitor and up (`KICK_MUTE`) | Ends the player's connection with the reason shown to them. Not a ban: they can log straight back in. Online players only. |
| `/mute <player> <30m\|12h\|7d\|perm> [reason]` | `KICK_MUTE` | The player can still play but their chat (every channel) is refused with the reason and time left. |
| `/unmute <player>`, `/mutes` | `KICK_MUTE` | Lift a mute; list active mutes. |
| `/ban <player> [30m\|12h\|7d\|perm] [reason]` | Developer and up (`BAN_PLAYER`) | Bans the **account**, disconnects it, and refuses login (and session resume) with the reason. No duration means permanent. |
| `/unban <player>`, `/bans` | `BAN_PLAYER` | Lift a ban; list active bans. |
| `/report <player> <what happened>` | everyone | Adds a report to the queue and tells online moderators. One report per 30 seconds per player, at most 200 open. |
| `/reports`, `/report close <id> [note]` | `KICK_MUTE` | List open reports (newest 10, oldest first in the queue); close one with a note. |
| `/modlog [player]` | `KICK_MUTE` | The last 10 audit entries, optionally only those about or by one player. |
| `/ipban`, `/ipunban`, `/ipbans` | `BAN_PLAYER` | Address bans (see [connection limits](/server/connection_limits.md)); now also audited. |

Players are found by character name, online or not. Nobody can act on themselves or on anyone of the **same or higher access level** (so a Developer cannot ban an Owner or another Developer). Durations are `m`, `h` or `d`, at most ten years; anything else prints the usage line. Reasons are cut to 200 characters.

## Where it lives
`ModerationService` (server-app) owns the state, which does not depend on game ticks, so it survives restarts:
- `<data dir>/moderation.json`: active bans and mutes (who, why, by whom, when, until when) and the report queue. Rewritten atomically after each change. An unreadable file is kept as `moderation.broken.json` instead of being dropped.
- `<data dir>/moderation-log.jsonl`: the audit log, one JSON object per line (time, actor, action, target, detail), appended on every moderator action and every report. Rotated to `.1` past 5 MB.

The older `AccountDocument.banned` flag is still honoured at login. Enforcement points: login and session resume (`banMessage`) and the chat handler (`handleChat`). The same handler serves the chat box, which sends `/` lines as an admin command packet, and chat packets (`handleServerCommand`).

## Not done yet
An in-game report window (the queue is readable with `/reports` and in the [web admin panel](/server/admin_panel.md)); report context (the last chat lines around the report); appeals; chat word filters and automatic sanctions; a moderator-side view of who is a ghost after a [combat log](/protocol/session_lifecycle.md); per-realm moderator roles other than the five access levels; IP bans have no reason history beyond the audit log. See the [roadmap](/reference/roadmap_status.md).

Tests: `ModerationServiceTest` (durations, persistence, expiry, report limits, audit), `ModerationCommandsTest` (permissions, rank rule, enforcement across a restart, login refusal, reports, both command paths).
