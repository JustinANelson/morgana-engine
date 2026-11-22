---
type: Simulation System
title: "Player Market"
description: "Fixed-price player listings for the world currency: MarketRules, escrow, the sale tax, payment by mail, expiry and the Market window."
tags: [market, economy, social]
code: ["core-domain/src/main/kotlin/com/morgana/domain/market/MarketRules.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "client-core/src/main/kotlin/com/morgana/client/ui/PlayerHudAndWindows.kt"]
symbols: ["MarketRules", "MarketListingDocument", "handleMarketAction", "expireMarketListings", "postMarketMail", "MarketAction", "MarketListings", "toggleMarketWindow"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-14T18:00:00Z }
stale_after: 2027-05-13T00:00:00Z
---

# Player Market

The `market` [optional module](/server/optional_modules.md) (on by default): fixed-price listings players buy from whether or not the seller is online. Not a bidding auction.

**Rules** (`MarketRules`, pure, `MarketRulesTest`): the price is in the world currency (item 1 when it is a currency item, else the lowest-numbered `CURRENCY` item; with none, nothing can be listed), `1..1,000,000,000`; currency and bound-on-pickup items cannot be listed; a seller may have 10 listings up; the market keeps 5% (`TAX_PERCENT`, rounded down) of the price; a listing lasts seven days of server time (`LISTING_TICKS`, counted in `effectiveWorldTick`). `buy` is all or nothing: price removed, item added, refused (nothing changes) when the buyer is short of currency, the bag has no room, or the listing is their own.

**Flow** (`MorganaWorldCoordinator`, `MarketServerTest`): `c_market` (`MarketAction(BROWSE | LIST | BUY | CANCEL, ...)`) always answers with `s_market` (the newest 200 listings; the client marks its own).
- `LIST` takes the stack out of the bag into the `MarketListingDocument` (escrow) and says what the tax is. It needs the `mail` module on, because proceeds are paid by mail.
- `BUY` removes the listing, gives the buyer the item, and sends the seller a **mail letter from "Market"** carrying the proceeds (price minus tax) as a currency attachment, so an offline seller is paid and a full bag never loses money (the [mail](/systems/mail.md) claim is all or nothing).
- `CANCEL` (seller only) returns the item to the bag when it fits.
- Every 1200 ticks `expireMarketListings` returns listings past their time to the seller by mail. Market letters ignore the mailbox cap.
- All of it marks a replay gap and is saved through `setPlayerState`.

**Client**: `ClientWorldState.marketListings`; the **Market** entry in the Menu opens a window with the listings (Buy, or Cancel on your own) and a sell form over the bag (item, amount, price).

**Not built yet**: search and filters, price history, bidding and buy-it-now, a listing fee, and a per-realm tax setting in `server.json`.
