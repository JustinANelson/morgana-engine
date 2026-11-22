---
type: Simulation System
title: "Shops, Bank, and Crafting"
description: "Buy/sell rate rules, bank storage, and recipe crafting including crafting-station enforcement."
tags: [economy, shops, crafting]
code: ["core-domain/src/main/kotlin/com/morgana/domain/sim/EconomySystem.kt", "client-core/src/main/kotlin/com/morgana/client/ui/bag/BagModels.kt", "client-core/src/main/kotlin/com/morgana/client/ui/bag/ShopWindow.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/EventSystem.kt", "core-domain/src/main/kotlin/com/morgana/domain/sim/WorldSimulation.kt", "core-domain/src/main/kotlin/com/morgana/domain/content/WorldContentModels.kt"]
symbols: ["shopBuyImpl", "shopSellImpl", "openShopFor", "ShopSession", "SHOP_RANGE_TILES", "TradeMath", "ShopWindow", "craftRecipeImpl", "craftingAnchors", "CraftingAnchor", "buyRatePercent", "CraftingStationType", "OpenCrafting"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Shops, Bank, and Crafting

- **A shop must be open and near** (2026-11-14): opening a shop (shopkeeper NPC, shop tile, stepping on one, or the `open_shop` event command) records a `ShopSession` (shop id, map, tile) through `openShopFor`; `shopBuy` and `shopSell` refuse with a system message ("Open the shop first." / "You are too far from the shop.") unless that shop is the one open and the player is within `SHOP_RANGE_TILES` (6) of where they opened it. Before this a modified client could trade with any shop from anywhere. The session is transient (like crafting anchors) and cleared at logout.
- **Whole purchases and sales only**: a purchase that does not entirely fit in the bag takes no payment ("Your bag is too full..."); before, it paid and delivered only what fitted. A sale whose currency does not fit is refused too (before, the overflow was lost). Both sides now tell the player: "Bought 5 x Healing Draught for 40 Gold Coin.", "Sold ...", "You need 100 Gold Coin for that.", "X is bound to you and cannot be sold.". A request moves at most 999 of something.
- `shopBuy` / `shopSell`: buy unit price = `priceOverride` if > 0, else `max(1, price * buyRatePercent / 100)`; sell unit value = `max(1, price * sellRatePercent / 100)` (defaults 100% / 50%), where a rolled item's `price` is first multiplied by its rarity's `priceMultiplierPercent` ([loot rarities and affixes](/systems/loot_and_affixes.md)). `shopSell` also accepts `slotIndices: List<Int>` (`ClientPacket.ShopSell.slotIndices` / `SimInput.ShopSell.slotIndices`) to bulk-sell multiple bag slots atomically in a single transaction. Bank deposits and withdrawals move the stack itself (`Inventory.addStack`), so rolls survive the vault. Currency is an item (`currencyItemId`, starter: Gold Coin id 1).
- `bankDeposit` / `bankWithdraw`: move stacks between inventory and the 48-slot bank.
- `craftRecipe`: checks `levelReq`, the **station requirement**, and ingredients; consumes ingredients, adds the output, grants XP, and advances `CRAFT_RECIPE` quest objectives.

## Crafting stations
A recipe whose `stationType` is not `ANYWHERE` (`ANVIL`, `ALCHEMY_TABLE`, `COOKING_FIRE`, `WORKBENCH`) is craftable only while the player stands on the tile where they last *opened that station*:
- An event's `open_crafting` command records a transient `CraftingAnchor` (station type, map, tile) in `WorldSimulation.craftingAnchors`; it is never persisted.
- Crafting from a different tile, map, or station type is refused with a system chat message ("You need to be at a anvil to craft ...").
- Logging out (`removePlayer`) clears the anchor.
- No shipped content has a crafting station any more (Starter Town and its Blacksmith Anvil were deleted on 2026-11-13). To add a station, author an event with an `open_crafting` command ([events and quests](/systems/events_quests.md)).

## Windows
- **Buy-back** (2026-11-20): everything a player sells to a shop (single or Quick sell) is remembered per player in `WorldSimulation.shopBuybacks` (`ShopBuybackList`, newest last, at most `SHOP_BUYBACK_LIMIT` = 10) with the exact stack (rolls, plus level, sockets, durability) and the price the shop paid. `shopBuyback` (`c_shop_buyback`, `SimInput.ShopBuyback`) returns one for exactly that price; it is refused whole if the player cannot pay or the bag cannot hold it. The server sends the list as `s_shop_buyback` when the shop opens and after each change. Opening a different shop, or logging out, forgets the list; it is never persisted. Tests: `ShopBuybackTest`, `InventoryServerTest`.

The client's shop window is `ui/bag/ShopWindow` ([input and HUD](/client/input_and_hud.md)): **Buy** (icons, price, quantity 1/5/10/Max, total, comparison with what you wear) and **Buy back** (a third tab listing what you sold here, at the price the shop paid) and **Sell** (your bag, what the shop pays, quantity 1/5/10/All, and **Quick sell** buttons for each gear rarity tier present in your bag — e.g. *Sell all Normal*, *Sell all Refined*, *Sell all Magic*, ... — plus *Sell all materials*, while protecting `+1` or higher enhanced gear), live after every trade. Its arithmetic is `TradeMath` (`bulkSellGroups`, `gearRarityId`, pure, tested), the same as the server's.

Shops/recipes are content ([content collections](/content/content_collections.md)); references are validated ([validation rules](/content/validation_rules.md)).

**Modelling an economy**: `TrevanaEconomyModelTest` (game-tests) is a worked example of checking a game's economy from its data: expected Marks, Starshards and Pearls per kill from monster drops, the expected cost of the quality ladder with pity, potion and repair upkeep from item prices and the `durability` rules, and the XP curve, with the play assumptions in one object and the design targets as assertions. A game can copy it. See `games/trevana-realms/RETHEME.md`, section 9.
