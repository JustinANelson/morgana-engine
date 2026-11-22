---
type: Reference
title: "Role-Play Chat"
description: "The rolePlayChat module: /me actions, out-of-character talk, shouts, quiet speech and staff narration, speech range, and how older clients see it."
tags: [chat, roleplay, modules, server, client]
code: ["core-domain/src/main/kotlin/com/morgana/domain/chat/RolePlayChat.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ServerConfig.kt", "client-core/src/main/kotlin/com/morgana/client/state/ChatFormatting.kt", "shared-protocol/src/main/kotlin/com/morgana/protocol/ProtocolCapabilities.kt"]
symbols: ["ChatStyle", "RolePlayChat", "RolePlayChatSettings", "RolePlayLine", "RolePlayConfig", "ChatFormatting", "RP_CHAT", "ROLE_PLAY_CHAT", "NARRATE", "deliverSpeech"]
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-13T12:00:00Z }
stale_after: 2027-05-12T00:00:00Z
---

# Role-Play Chat

The first slice of the role-play (RP) module: local speech that reads like a shared story. It is the optional module `rolePlayChat` ([optional modules](/server/optional_modules.md)); like every module it is **on** unless `server.json` switches it off. With the default settings plain map chat behaves exactly as before, so turning it on changes nothing until a player types one of the commands below.

## Commands
| Typed | Style (`ChatStyle`) | Who hears it | Shown on a styled client |
| :--- | :--- | :--- | :--- |
| `/me`, `/em`, `/emote <action>`, or text on the `Emote` channel | `ACTION` | say radius | `* Aria draws her sword` |
| `/ooc <text>`, or `((text))` on the Map or Emote channel | `OOC` | whole map | `[OOC] Aria: brb` (no bubble) |
| `/say <text>`, or plain text on the Map channel | `NORMAL` | say radius | `[Map] Aria: Hello` |
| `/shout`, `/yell <text>` | `SHOUT` | whole map, plus the four neighbouring maps | `Aria shouts: Help!` |
| `/quiet`, `/mutter <text>` | `QUIET` | quiet radius | `Aria says quietly: ...`, bubble in brackets |
| `/narrate`, `/describe <text>` | `NARRATION` | whole map | `~ Thunder rolls ~`, sender shown as `Narrator`, no bubble |

- Parsing and earshot are pure functions in `RolePlayChat` (`parseCommand`, `parseText`, `recipients`); the coordinator only sends (`deliverSpeech`). Distance is in tiles with diagonals counted as one step (a square around the speaker). Speech never crosses maps except a shout.
- A command with no words replies with its usage line. Commands match whole words only (`/meow` is not `/me`).
- `/narrate` needs the `NARRATE` permission (Monitor and above, see [access levels](/domain/access_levels.md)). The staff member's name is not shown to players but is logged on the server (`Narration` logger).
- A mute covers every style. Plugin "before" hooks see role-play lines as `PlayerAction.Chat` (actions on the `EMOTE` channel, the rest on `MAP`), and `onPlayerChat` is notified, as for ordinary chat.
- The server checks role-play commands after IP, plugin and moderation commands and before game script commands, so a script cannot register `/me` and the like while the module is on.
- With the module off, the commands fall through to the other handlers (and so do nothing), and `Map`/`Emote` chat is sent map-wide with no style, as before this module existed.

## Settings (`server.json`)
```json
{ "roleplay": { "sayRadius": 8, "quietRadius": 2, "shoutReachesNeighbors": true } }
```
- `sayRadius` (0..64, default `0` = whole map): ordinary speech and actions.
- `quietRadius` (1..64, default `2`).
- `shoutReachesNeighbors` (default `true`).
- Validated strictly at startup (`ServerConfigException`); not hot-reloaded.

## Protocol and older clients
`s_chat` (`ChatBroadcast`) has a `style` field (default `NORMAL`). Clients that send the `rp_chat` capability (in `DEFAULT_CLIENT_CAPABILITIES`) get the style; other clients get a `NORMAL` line with the style folded into the text (`RolePlayChat.plainFallback`: `* Aria waves`, `(( brb ))`, `(shouts) ...`, `(quietly) ...`, narration from `Narrator`). The server lists `rp_chat` in its active capabilities when the module is on. No protocol version bump: older clients ignore the unknown field.

## Client
`ChatFormatting` turns each line and bubble into its styled text. `/hideooc` and `/showooc`, typed in the chat box, are handled by the client only: they hide or show OOC lines in the chat log for the session.

## Not done yet
Character profiles (`/look`), display names and status flags, dice (no built-in `/roll`), saved chat logs, a chat-range setting in the New Realm Wizard, and GM tools such as puppeting NPCs.

## Tests
`RolePlayChatTest` (parsing, earshot, fallbacks), `RolePlayConfigTest` (`server.json`), `RolePlayChatServerTest` (delivery, capability fallback, range, permission, mute, module off), `ChatFormattingTest` (client text).
