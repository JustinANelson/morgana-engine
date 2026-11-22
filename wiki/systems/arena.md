---
type: Simulation System
title: "Arena"
description: "Instanced team fights: the /arena queue, rating-based pairing, countdown, no loss on death, Elo rating changes and rewards."
tags: [pvp, arena, instancing, matchmaking]
code: ["core-domain/src/main/kotlin/com/morgana/domain/game/Arena.kt", "core-domain/src/main/kotlin/com/morgana/domain/systems/ArenaSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/CombatSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/WorldSimulation.kt", "core-domain/src/test/kotlin/com/morgana/domain/ArenaTest.kt"]
symbols: ["ArenaDefinition", "ArenaSpawn", "ArenaTicket", "ArenaMatch", "arenaQueue", "arenaMatches", "tickArena", "arenaCommand", "eliminateFromArena", "canAttackPlayer", "arenaRating"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-19T18:00:00Z }
stale_after: 2027-05-18T00:00:00Z
---

# Arena

Optional `arena` section of `game.json` (`ArenaDefinition`); without it there is no arena queue. It is a pure, deterministic part of the simulation, installed as `ArenaSystem` (`systems/ArenaSystem.kt`; a tick participant, combat arbiter, chat command and event command handler, see [system seams](/systems/system_seams.md)): no clocks or randomness, so a [replay](/systems/deterministic_replay.md) of queue commands and fights agrees.

**Queue**: `/arena join [size]`, `/arena leave`, `/arena rating` and `/arena top` (the best five ratings among players online who have fought; chat commands, owned by `ArenaSystem` as a `ChatCommandHandler` and reached from `executeAdminSlashCommand` through the system seams). `size` must be one of `teamSizes` (a size of 2 means two against two); a player cannot queue while dead, in an instance, already queued or already in a fight. Tickets (`ArenaTicket`) live in `WorldSimulation.arenaQueue` and are dropped when the player leaves the world or dies.

The same actions are available without typing through the `arena_queue` event step (`EventCommand.ArenaQueue`, action JOIN, LEAVE, RATING or STANDINGS, plus a team size); an NPC menu can offer them. There is no stored leaderboard: standings cover players who are online.

**Pairing** (`tickArena`, once a second): for each size, queued players sorted by rating are scanned in windows of `2 * size`; the first window whose best and worst rating differ by no more than `maxRatingGap` (growing by `gapGrowth` for every `gapGrowthTicks` the oldest ticket has waited) becomes a match. The window is split A B B A A B B A so the two teams are close in strength. Players with no rating yet count as `startRating`.

**Match** (`ArenaMatch`, `WorldSimulation.arenaMatches`): the server copies `mapId` (it must be instanced; content validation says so) into a new instance (`ownerKey` `arena:<id>`), puts each team on its spawn tiles with full life and no effects, and remembers where everyone came from. For `countdownTicks` nobody can hurt anybody; then `canAttackPlayer` lets only opposite teams fight, whatever their PK mode (`ArenaMatch.canFight`). The fight lasts at most `matchTicks`.

**Dying costs nothing**: `handlePlayerDeath` hands an arena player to `eliminateFromArena`, which leaves them out of the fight and sends them home at once with full life (no drops, no durability wear, no ghost). A player who leaves the world forfeits. The match ends when a team has nobody left (the other wins), or at the time limit, when the team with more total life wins and an equal one draws.

**Result** (`finishMatch`): winners and losers get `arenaWins` / `arenaLosses`, an Elo rating change by `ratingK` (a draw changes nothing) stored in `CharacterState.arenaRating`, and the `winnerRewards` / `loserRewards` items. Everyone still inside is sent back to where they queued from; the empty instance closes with the usual [instance](/systems/instancing.md) timer.

**Tests**: `ArenaTest` (pairing, countdown, team rules, no loss on death, rating, time limit, forfeit, rating gap, commands).
