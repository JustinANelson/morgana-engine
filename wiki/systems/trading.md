---
type: Simulation System
title: "Player Trading"
description: "The two-stage lock-and-confirm trade protocol and its safeguards."
tags: [trading, social]
code: ["core-domain/src/main/kotlin/com/morgana/domain/systems/TradeSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/WorldSimulation.kt"]
symbols: ["initiateTradeImpl", "addTradeOfferItemImpl", "lockAndConfirmTradeImpl", "TradeSessionState", "boundOnPickup"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Player Trading

1. `initiateTrade(initiator, partner)`: both must be online and on the same map; creates a `TradeSessionState`.
2. `addTradeOfferItem`: moves the stack out of the offerer's inventory into the session; **bound-on-pickup items are refused**; no changes once both sides are locked.
3. `lockAndConfirmTrade(confirmStage = false)` locks your offer; when both are locked, `confirmStage = true` confirms; when both confirm, offers are exchanged atomically.
4. `removePlayer` (logout) cancels any session and emits `TradeUpdated(null)`.

## Player stalls

With the `stall` section (`StallDefinition`) a player lists bag items with `/stall add <bag slot> <price each> [units]` (bound items are refused, one listing per slot, at most `maxListings`), then `/stall open [title]` (only on `mapIds`, empty = any map). While open the owner cannot move, attack, cast, use items or trade, and `PublicPlayerSnapshot.stallTitle` shows the title by their name. A buyer within `rangeTiles` on the same map uses `/stall view <player>` and `/stall buy <player> <n> [units]`: the sale checks the goods are still in the listed bag slot (otherwise the listing is dropped), charges `currencyItemId`, and moves the stack (with its rolls and sockets) and the payment in one step, refusing if either bag is full. Stalls live in `WorldSimulation.stalls` only and close on `/stall close`, death and logout. The sim emits `StallViewed` (a `StallView` of `StallGood` lines) to the viewer on `/stall view`, `list`, `add`, `remove`, `open` and `close` and to buyer and seller after a sale; the server sends it as `s_stall_view` and the Stall window lists the goods. Not built yet: clicking a stall in the world to browse it.

Packets: `c_trade` (`REQUEST`, `ADD_ITEM`, `LOCK`, `CONFIRM`, `CANCEL`) and `s_trade_sync` ([client packets](/protocol/client_packets.md)).
UI: Interactive two-stage modal Trade Window in `PlayerHudAndWindows` displays both offers, individual status badges (`[OFFERING]`, `[LOCKED]`, `[CONFIRMED]`), inventory offer buttons, and synchronized Lock/Confirm/Cancel actions ([input and HUD](/client/input_and_hud.md)).
