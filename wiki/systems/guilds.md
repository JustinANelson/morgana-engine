---
type: Simulation System
title: "Guilds"
description: "Guild ranks, invitations, kicks and succession: GuildRules, the coordinator flow, the roster packet and the Guild window."
tags: [guilds, social]
code: ["core-domain/src/main/kotlin/com/morgana/domain/social/GuildRules.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "client-core/src/main/kotlin/com/morgana/client/ui/PlayerHudAndWindows.kt"]
symbols: ["GuildRules", "GuildDocument", "handleGuildAction", "pushGuild", "setGuildMembership", "announceGuildOnLogin", "GuildAction", "GuildRoster", "GuildMemberEntry", "toggleGuildWindow"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-14T21:00:00Z }
stale_after: 2027-05-13T00:00:00Z
---

# Guilds

The `guilds` [optional module](/server/optional_modules.md). A `GuildDocument` (id, name, tag, founder, `memberRanks` character id to rank, message of the day) is stored in `social/guilds`, loaded into `WorldSimulation.guilds` at start, and mirrored onto each member as `CharacterState.guildId` and `guildRank`.

**Rules** (`GuildRules`, pure, `GuildAndIgnoreRulesTest`): name 2..24 characters and unique (ignoring case), tag 1..5 (stored uppercase), at most 50 members. Ranks are **Founder** (exactly one), **Officer** and **Member**. The founder and officers invite and set the message of the day; officers kick members; the founder kicks anyone but themselves and promotes members to officer or demotes them; nobody changes the founder's rank. A **leaving founder hands the guild to an officer first, otherwise to the first remaining member**, and the last member leaving disbands it.

**Flow** (`MorganaWorldCoordinator`, `GuildAndIgnoreServerTest`): `c_guild` (`GuildAction(action, primaryArg, secondaryArg)`): `CREATE` (name, tag), `INVITE` (name of an online, unguilded player; they are told in chat and the invite is kept in `pendingGuildInvites` until they `ACCEPT`; an ignoring player is not told, the inviter cannot tell), `LEAVE`, `KICK`, `PROMOTE`, `DEMOTE` (name), `SET_MOTD`, `REFRESH`. Every change saves the guild, updates the members (a kicked offline member's stored character is edited) and pushes `s_guild_sync` plus `s_guild_roster` (`GuildMemberEntry(name, rank, online, level)`, officers first) to the online members. At login `announceGuildOnLogin` repairs a stale membership (kicked or disbanded while offline), sends the guild and the message of the day. All of it marks a replay gap.

**Client**: `ClientWorldState.activeGuild` and `guildRoster`; the **Guild** entry in the Menu opens a window: found a guild or accept an invitation when you have none; otherwise the message (editable for officers), the roster with promote, demote and remove buttons that appear only where your rank allows, an invite box, and Leave.

**Guild War** (`guildWar` in `game.json`, `GuildWarSystem.kt`, `GuildWarTest`): the sim holds a `GuildWarState` (active, start tick, pole damage per guild id). `tickGuildWar` starts a war when `currentTick % intervalTicks == 0` and ends it after `durationTicks`; staff with `SERVER_MAINTENANCE` can type `/guildwar start` or `/guildwar end`. Every hit on an NPC passes `interceptGuildWarHit`: a `POLE` or `GATE` is refused outside a war, to players without a guild and to the holding guild; a pole then only counts the damage, a gate takes it normally (a dead gate does not respawn until a war starts or ends). Ending a war gives the area to the guild with the most pole damage (a tie or less than `minDamage` changes nothing), clears the old holder's `holdsGuildArea`, emits `GuildWarResult` (the coordinator saves and pushes both guilds), gives `rewardItems` to the winner's online members and announces the result on the broadcast channel.

**Not built yet**: custom rank names and permissions, guild invitations by clicking a player, a guild log, Guild Silver donations, ally and enemy guilds, a guild-leader halo and an on-screen war score.

**Guild halls**: a map with `instanceOwner: GUILD` (and `instanced: true`) is a hall template. `warpedPlayer` keys its copy by `MapInstances.guildKey(guildId)`: members of one guild share a copy whatever their party, another guild gets its own, and someone with no guild stays put with a "guild members only" notice. `positionAfterLogin` puts a player back outside if they are in a hall of a guild they have left. Needs the `instancing` module. Test: `GuildHallInstanceTest`.

**Guild bank** (`guildBank` in `game.json`, `GuildBankSystem.kt`, `GuildBankTest`): `GuildDocument.bank` is a 48 slot vault with `bankWithdrawRank` (blank means `guildBank.defaultWithdrawRank`) and a capped `bankLog`. At a `GUILD_BANKER` NPC (within `reachTiles`, default 4) any member can `guildBankDeposit` (bound items refused) and a member of the allowed rank or higher can `guildBankWithdraw`; the founder `setGuildBankAccess`. Each change emits `GuildBankChanged`, so the coordinator saves the guild and resends `GuildUpdated` (which carries the vault) to online members. Packets `c_guild_bank_deposit`, `c_guild_bank_withdraw`, `c_guild_bank_access`; window type `GUILD_BANK`, shown by `BankWindow` in guild mode.

**Guild bank extras** (`GuildBankTest`): the vault is shown in tabs of `guildBank.tabSlots` slots (a deposit can name a tab, `GuildBankDeposit.tab`); `withdrawLimits` caps what a rank takes out per `limitDayTicks` (tracked per member in `GuildDocument.bankWithdrawn`; the founder has no limit); log lines carry their world tick (`GuildBankLog`, `Day 3 14:05`) and `logEntries` may reach 500; the vault's `currencyItemId` total is the guild's Marks balance; and the founder buys `upgrades` in order with those Marks (`GuildBankUpgrade` packet, `GuildDocument.bankLevel`), each adding slots up to 200.

**Guild hall extras** (`GuildDocument.warWins`, `ejectFromGuildHall`, `HalcyonReachGameTest`): a guild's wins are counted when it takes or defends the guild area; event text can show `{guild}`, `{guildtag}`, `{guildmotd}` and `{guildwins}`, and an `if` step can test `GUILD_HOLDS_AREA`. When a player stops belonging to the guild whose hall they stand in (kicked, left, disbanded), `MorganaWorldCoordinator.setGuildMembership` runs `SimInput.EjectFromGuildHall`, which puts them back where they entered from.
