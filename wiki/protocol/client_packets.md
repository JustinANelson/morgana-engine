---
type: Protocol
title: "Client Packets (c_*)"
description: "Every client-to-server packet and its purpose."
tags: [protocol, packets]
code: ["shared-protocol/src/main/kotlin/com/morgana/protocol/Packets.kt"]
symbols: ["Authenticate", "MoveInput", "SaveItemDefinition", "RequestAdminCatalog", "AdminSlashCommand", "SelectDialogueChoice"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Client Packets

| Wire name | Class | Purpose |
| :--- | :--- | :--- |
| `c_unknown` | `ClientPacket.Unknown` | Fallback for unrecognized client packets (forward compatibility) |
| `c_auth` | `Authenticate` | Login/register (username, password, `registerIfNew`, class, sprite, **`protocolVersion`**, **`capabilities`**) |
| `c_resume` | `ResumeSession` | Return to a character after a dropped connection using the single-use token from `s_auth_res`; see [reconnect](/client/reconnect.md) |
| `c_ping` | `Ping` | Latency probe and connection heartbeat; server replies `s_pong` |
| `c_realm_info` | `RequestRealmInfo` | Pre-login request for realm metadata and playable classes; server replies `s_realm_info` |
| `c_move` | `MoveInput` | Move (direction, mode, pixel deltas, `seq`: step number for [reconciliation](/client/client_state_and_prediction.md), 0 = unnumbered) |
| `c_face` | face direction | Turn without moving |
| `c_attack`, `c_cast` | `Attack`, cast spell | Combat |
| `c_interact`, `c_pickup` | `InteractFacing`, `PickupGroundItem` | World interaction |
| `c_use_slot`, `c_drop_slot`, `c_swap_slots`, `c_sort_inventory`, `c_unequip` | inventory ops (`c_sort_inventory` merges and packs the bag; an older server ignores it) | Items |
| `c_alloc_stat`, `c_hotbar` | stat/hotbar ops | Character |
| `c_chat` | `SendChat` | Chat on a `ChatChannel` |
| `c_shop_buy/sell/buyback`, `c_bank_deposit/withdraw`, `c_craft` | economy | [Economy](/systems/economy.md) |
| `c_guild_bank_deposit/withdraw/access` | economy | [Guilds](/systems/guilds.md) |
| `c_dialogue_choice` | `SelectDialogueChoice` | The answer picked in a dialogue, or a choice index of `-1` when a line with no answers was closed (clients with the `dialogue_flow` capability), which lets the event carry on ([events](/systems/events_quests.md)) |
| `c_text_input` | `SubmitTextInput` | Player-entered value (or `cancelled=true`) answering an `s_text_input` event prompt ([events](/systems/events_quests.md)) |
| `c_world_map` | opening the world map: asks for the terrain and enemy spawns of the explored maps (`s_world_map`) | [Exploration](/systems/exploration.md) |
| `c_party`, `c_guild`, `c_trade` | social actions; `c_guild` actions are CREATE, INVITE, ACCEPT, LEAVE, KICK, PROMOTE, DEMOTE, SET_MOTD, REFRESH | [Trading](/systems/trading.md), [Guilds](/systems/guilds.md) |
| `c_ignore` | add or remove an ignored player, or ask for the list | [Ignore list](/systems/ignore.md) |
| `c_market` | browse, list, buy or cancel at the player market | [Player market](/systems/market.md) |
| `c_friend` | add or remove a friend, or ask for the list | [Friends](/systems/friends.md) |
| `c_mail_send`, `c_mail` | send a letter (optional item attachment); read, claim or delete one, or ask for the mailbox | [Mail](/systems/mail.md) |
| `c_admin_cmd` | `AdminSlashCommand` | [Admin](/systems/admin_live_edit.md) |
| `c_choose_character`, `c_create_character`, `c_delete_character` | `ChooseCharacter`, `CreateCharacter`, `DeleteCharacter` | After `s_character_list`: play a character of the signed-in account, make a new one (name, class), or delete one (typed name as confirmation). Refused before sign-in ([characters per account](/server/characters.md)) |
| `c_plugin_msg`, `c_plugin_action`, `c_plugin_window_closed` | `PluginMessage`, `PluginAction`, `PluginWindowClosed` | A free-form message for a [plugin](/playbooks/write_a_server_plugin.md); a press of a plugin window's button (with the text of its inputs); closing a plugin window (capability `plugin_ui`) |
| `c_tips` | `TipsControl` | The player turns new-player tips off, on, or has all of them shown again (`TipsAction.OFF/ON/RESET`); needs the `tips` capability. See [new-player tips](/systems/onboarding.md). |
| `c_admin_req_catalog` | `RequestAdminCatalog` | Load content catalog for the F1 editor |
| `c_admin_save_map/item/npc/spell/shop/resource/recipe/quest/event/common_event/animation` | `Save*Definition` | F1 live saves (validated first; [content pipeline](/content/content_pipeline.md)) |
| `c_admin_test_event` | `TestEventCommands` | Run unsaved event steps once for the editing admin (`EDIT_CONTENT`, validated first; [events](/systems/events_quests.md)) |
| `c_admin_import_art` | `ImportGameArt` | Upload a PNG (`kind`, numeric `id`, Base64 bytes) to replace or add game art live (`EDIT_CONTENT`, validated by `GameAssets.importPng`) |

New packet checklist: [playbook](/playbooks/add_packet.md). Transport: [wire format](/protocol/wire_format.md).
