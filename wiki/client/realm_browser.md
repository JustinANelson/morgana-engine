---
type: Reference
title: "Realm Browser (Server Select)"
description: "The server select screen: a two-pane realm browser with search, tag filters, favorites and recents pinned on top, a details pane, and what it still lacks."
tags: [client, ui, realms, directory]
code: ["client-core/src/main/kotlin/com/morgana/client/realms/ServerAddress.kt", "client-core/src/main/kotlin/com/morgana/client/ui/MorganaRealmsHubWindow.kt", "client-core/src/main/kotlin/com/morgana/client/realms/RealmBrowserModel.kt", "client-core/src/main/kotlin/com/morgana/client/preview/DemoRealmDirectory.kt", "client-core/src/main/kotlin/com/morgana/client/realms/DirectoryRefresher.kt", "smoke-test/realms-look.mjs"]
symbols: ["ServerAddress", "MorganaRealmsHubWindow", "RealmBrowserModel", "RealmFilter", "RealmSort", "RealmRow", "RealmSection", "demoRealmDirectory", "fakeRealmsFromQuery", "DirectoryRefresher"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-12T22:00:00Z }
stale_after: 2027-05-11T00:00:00Z
---

# Realm Browser (Server Select)

The **Realm Directory** tab of the [realms hub](/architecture/client_core.md) ([ADR 0014](/decisions/0014_morgana_realms_universal_client_architecture.md)). It was a fixed 470 x 180 pixel scroll box of tall cards (under two realms visible at once) with a text search; it is now built for directories of hundreds of realms.

## What the player sees
- **Two panes** when the screen is at least 760 pixels wide: the list on the left, and on the right the selected realm's details with the sign-in form (name, password, class, **Enter Realm**) underneath, so the list gets the full window height. Narrower screens stack the list, a small details pane and the sign-in form in one column.
- **Search** across name, game title, description, tags and host; every word must match. The text, scroll position and selection survive filtering and favouriting (the screen updates in place; it used to rebuild itself on every click).
- **Filters**: Favorites only, Hide full, a **sort** (Recommended, Most players, Name), and up to eight **tag chips** with counts, which narrow the list (all chosen tags must match). A chosen chip stays visible even if it matches nothing, so it can be switched off. The line under the chips reads "26 of 153 realms match".
- **Grouped default view**: with no search or filter, the list is grouped as **Favorites**, **Recently played** (five), **Featured**, then **All realms**. Within the groups, a full realm sorts below ones you can join. Any search, filter or other sort switches to one flat "Results" list.
- **Rows** are two lines: a star (favourite toggle, drawn from the editor's pixel art because the UI font has no star glyph), the name, the tags (and "unencrypted"), the population as `online/max` with a small bar, and **FULL**. A realm that needs a newer engine API than the client knows is shown in red as "needs newer client" and cannot be entered by double-click.
- **Selecting**: one click selects; a double click selects and enters. In the search box the **Up and Down arrows** move the selection through the rows in the order shown (across the groups, scrolling the row into view) and **Enter** enters the selected realm (or the first row when none is selected). **Enter in the name, password, host or port box signs in**. The most recently played realm is selected when the hub opens, so returning players are one click from playing.
- **Tab** moves between the connection and sign-in boxes (host, port, username, password; Shift+Tab goes back, and it wraps round), and Enter in any of them signs in. `Ctrl+Tab` still switches the hub's tabs. The hub handles Tab itself (`tabBetweenFields`) rather than relying on libGDX's focus traversal, which did not move from the username to the password.
- **Details pane**: name, game title, players, tags, description, the classes on offer, address, whether the connection is encrypted (a clear warning when it is not), an engine-compatibility warning, when you last played, the website, **Add to favorites** and **Copy invite**.
- A directory of more than 200 matches shows the first 200 and says how many more there are ("type in the search box or pick a tag to narrow the list").

## Refreshing the directory
A **Refresh** button beside the sort box fetches the directory again; the typed search, filters and selection are kept. A directory that comes over the network (`RealmDirectoryClient.slow`, today `HttpJsonRealmDirectoryClient`) is fetched on a background thread, on opening the hub and on Refresh, so a slow or dead server never freezes the screen: the count line reads "Loading realms..." or "Refreshing...", and a failure leaves the last list with a note. `DirectoryRefresher` holds the one-fetch-at-a-time rule and is unit-tested. The bundled directory answers instantly and is read inline.

## Direct Join
The invite box (**Use This**, or Enter) accepts a `morgana://` invite, `host`, `host:port`, `[ipv6]:port`, a bare IPv6 address or a web-style address such as `https://play.example.com:7002/`; `ServerAddress` reads them and says so plainly when it cannot ("Enter a server address like play.example.com:7001..."). Pressing **Enter Realm** now checks the host and port boxes and explains a bad port instead of silently falling back to 7001. Every connection is remembered, including ones typed in by hand, and the tab lists the last five as one-click **Recent servers**. All three tabs (**Realm Directory**, **Direct Join**, **Local Worlds**) share a single fixed-size tabbed pane (two columns on desktop with the sign-in form on the right; single column with the sign-in form below on narrow screens), so switching tabs swaps the pane's content without resizing or moving the window.

## How it is built
`RealmBrowserModel` is pure logic (no widgets): given the directory, the favourites and the visit history, `view(RealmFilter)` returns the sections, match counts, hidden count and tag chips, and `step` picks the next row for the arrow keys. `ServerAddress` parses what is typed into Direct Join. `MorganaRealmsHubWindow` draws it. History and favourites come from the existing `PerRealmStore`. Tests: `RealmBrowserModelTest` (grouping, search, tag filters and chips, the row cap, ordering, arrow-key stepping, fullness, compatibility, time wording, the demo directory) and `ServerAddressTest`.

## Previewing with many realms
No public directory exists yet (the clients use the bundled list of three), so `demoRealmDirectory(count)` makes a deterministic made-up one. In the web client open the page with `?fakeRealms=200` (1 to 2000). `smoke-test/realms-look.mjs` takes screenshots of it at a wide and a narrow size into `build/realm-look/` (needs the built web client). The [smoke test](/playbooks/browser_smoke_test.md) checks that typing narrows the list and keeps the typed text, that the Down arrow selects a realm, and that Enter in the sign-in form logs in.

## Not done yet
Latency or online/offline status per realm (needs a probe the web client can make); periodic background refresh while the hub stays open; region and language fields and filters; more than one directory ([federated registries](/reference/roadmap_status.md)); moving favourites between devices; a banner or icon per realm (`bannerAssetHash` exists but is unused).
