---
type: Protocol
title: "Server Packets (s_*)"
description: "Every server-to-client packet and when it is sent."
tags: [protocol, packets]
code: ["shared-protocol/src/main/kotlin/com/morgana/protocol/Packets.kt"]
symbols: ["AuthResult", "MapStateSnapshot", "ChatBroadcast", "ContentCatalogUpdated", "MapDefinitionUpdated", "FloatingText"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Server Packets

| Wire name | Class | Meaning |
| :--- | :--- | :--- |
| `s_unknown` | `ServerPacket.Unknown` | Fallback for unrecognized server packets; ignored by older clients (forward compatibility) |
| `s_auth_res` | `AuthResult` | Login or resume outcome (+ character, `capabilities`, and a single-use `resumeToken` on success) |
| `s_session_end` | `SessionEnded` | Server is ending this session; `resumable=false` (signed in elsewhere) tells the client not to auto-reconnect; see [reconnect](/client/reconnect.md) |
| `s_pong` | `Pong` | Echo timestamp + server tick (the client re-syncs its game clock from it; `AuthResult` and `MapStateSnapshot` also carry `serverTick`) |
| `s_realm_info` | `RealmInfo` | Pre-login realm metadata (`gameId`, `gameName`, `classes: List<RealmClassSummary>`) for the login screen's class selector |
| `s_map_snapshot` | `MapStateSnapshot` | Active map (null when the connection already holds it), neighbor maps not yet held, `mapRefs` for the send-once cache (see [wire format](/protocol/wire_format.md)), entities on enter/warp |
| `s_seam_view` | `SeamView` | Entities of neighbouring maps within 16 tiles of the shared border (`NeighborMapEntities`: NPCs, players, ground items in that map's own coordinates). Sent to players near a border right after each snapshot and then every 5 ticks only when changed; an empty list clears it. Visual only |
| `s_char_sync` | `CharacterSync` | Full own-character update; `ackMoveSeq` is the last `c_move` step handled when it was sent |
| `s_player_moved`, `s_player_left` | `PlayerUpdated`, `PlayerLeftMap` | Other players (`PublicPlayerSnapshot`, carrying `paperdollSpriteIds` plus `paperdollLayers` when `sprites.paperdollingV2` is enabled; [public snapshot](/protocol/session_lifecycle.md)) |
| `s_npc_sync`, `s_ground_items`, `s_projectiles`, `s_resource_sync` | map entity updates | |
| `s_npc_pos`, `s_player_pos`, `s_moves` | `NpcMoved`, `PlayerMoved`, `EntityMoves` | A monster or player that only **moved** since the last full `s_npc_sync` / `s_player_moved`. The server sends the compact form (short field names, no unchanged data) only to viewers who could already see both the old and new place; others get the whole entity. Moves are queued per viewer and sent as one `s_moves` every second tick (10 Hz, the latest place of each entity wins); the client copies the fields onto the entity it has and ignores one it does not know. A change only the server cares about (attack and spell clocks, threat, wind-up) is not sent at all, nor are other players' mana or stamina. |
| `s_proj_delta` | `ProjectilesDelta` | The projectiles that were added or changed (`upserted`, by id) and the ones that ended (`removedIds`) since the last `s_projectiles` or delta. A free-flight shot that just flew on is not listed: the client flies it on from where it last heard of it (`vx`, `vy` per 50 ms tick, until `remainingPixels` runs out). |
| `s_chat` | `ChatBroadcast` | Chat; system messages use `ChatChannel.SYSTEM`. `style` (`ChatStyle`) marks [role-play](/systems/roleplay_chat.md) actions, OOC, shouts, quiet speech and narration, sent only to clients with `rp_chat`. `notice` (`NoticeKind`: INFO, GOOD, WARNING, LEVEL) is set on system messages that deserve an on-screen toast (refusals, purchases, level-ups); null on everything else and on older servers, where the client falls back to guessing from the words |
| `s_world_objects` | `WorldObjectsUpdated` | World objects placed or changed (`upserted`, whole objects) and removed (`removedIds`) on a map; sent to players on the map whose client has the `world_objects` capability (in `DEFAULT_CLIENT_CAPABILITIES`). The map's objects arrive in `MapStateSnapshot.worldObjects`. See [world state](/systems/world_state.md). |
| `s_float_text`, `s_play_anim` | `FloatingText`, animation | Combat feedback; `s_play_anim` also carries `casterEntityId` and `spellId` (both optional, empty/0 when not a spell's) so clients can draw the shot |
| `s_play_sound` | `PlaySound` | Plays a sound effect by id on the client (`RetroSoundEngine.playById`) |
| `s_spell_cooldown` | `SpellCooldown` | The receiving player's `spellId` is cooling down for `durationTicks` (20 per second) from now; sent when a cast starts its cooldown (instant casts at once, timed casts when the cast finishes). New type, so older clients decode it as `Unknown` and ignore it (no version bump); once a client has seen one it stops estimating cooldowns itself |
| `s_show_picture` | `ShowPicture` | Opens a picture popup window by id (`pictures/<name>.png`, `icon:<id>`, `sprite:<id>`, `anim:<id>`) with optional title and `durationTicks` auto-close |
| `s_text_input` | `TextInputPrompt` | Opens a text or number input modal (`prompt`, `isNumeric`, `defaultValue`, `maxLength`); client replies with `c_text_input` |
| `s_open_ui` | `OpenUiWindow` | Opens shop/bank/crafting/dialogue windows |
| `s_character_list` | `CharacterList` | Sent instead of `s_auth_res` to clients that advertised `character_select`: the account's characters, the limit, the classes for a new one, and a message explaining a refused request. Additive, no version bump ([characters per account](/server/characters.md)) |
| `s_plugin_msg`, `s_plugin_window`, `s_plugin_window_close` | `PluginMessage`, `PluginWindow`, `PluginWindowClose` | A plugin's free-form message; a data-described window (title, text lines, a label/value table, buttons) the client draws; closing it. Sent only to clients that advertised `plugin_ui`; additive, no version bump ([plugin UI](/playbooks/write_a_server_plugin.md)) |
| `s_tip` | `Tip` | A new-player tip (id, title, text with `{key:ACTION}` and `{abilities}` placeholders), sent once per account and only to clients that advertised the `tips` capability. See [new-player tips](/systems/onboarding.md). |
| `s_trade_sync`, `s_party_sync`, `s_guild_sync` | social state | |
| `s_stall_view` | `StallViewed` | The goods of a stall the player looked at or of their own (`StallView`: owner, title, open, numbered goods with item, `+` level, units and unit price) | [Trading](/systems/trading.md) |
| `s_explored`, `s_explored_map` | every explored map at login; one map's exploration after it changes | [Exploration](/systems/exploration.md) |
| `s_world_map` | the terrain thumbnail and NPC spawn points of each explored map, answering `c_world_map` | [Exploration](/systems/exploration.md) |
| `s_guild_roster` | the guild's members with ranks and online state | [Guilds](/systems/guilds.md) |
| `s_ignore` | the names the player ignores | [Ignore list](/systems/ignore.md) |
| `s_shop_buyback` | what the player sold to the open shop and can buy back (sent on open and after each sale or buy-back) | [Economy](/systems/economy.md) |
| `s_market` | the market's open listings, newest first | [Player market](/systems/market.md) |
| `s_friends` | the friends list, online friends first (with level and map) | [Friends](/systems/friends.md) |
| `s_mail_inbox` | the player's whole mailbox, newest first | [Mail](/systems/mail.md) |
| `s_admin_map_updated` | `MapDefinitionUpdated` | Hot-swapped map |
| `s_admin_catalog_updated` | `ContentCatalogUpdated` | Hot-swapped content catalog + summary message |
| `s_admin_art_updated` | `GameArtUpdated` | Live-imported PNG asset (`AssetEntry` + Base64 bytes) broadcast after `c_admin_import_art` so connected clients swap the texture immediately |

Produced by `dispatchEvents` ([simulation events](/domain/simulation_events.md)); consumed by `ClientWorldState.applyServerPacket` ([state](/client/client_state_and_prediction.md)).
