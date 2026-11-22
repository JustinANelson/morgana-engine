---
type: Playbook
title: "Browser Smoke Test"
description: "Run and extend the headless-Chrome smoke test of the web client: what it covers, how the client describes itself to it, and how to add a step."
tags: [playbook, quality, web, testing]
code: ["smoke-test/run.mjs", "smoke-test/package.json", "client-core/src/main/kotlin/com/morgana/client/probe/GameProbe.kt", "client-web/src/main/kotlin/com/morgana/client/web/WebLauncher.kt", "client-web/build.gradle.kts"]
symbols: ["GameProbe", "probeSnapshot", "exposeProbe", "smokeTest", "smokeTestInstall"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-11T12:00:00Z }
stale_after: 2027-05-10T00:00:00Z
---

# Browser Smoke Test

Unit tests missed bugs that only the real browser client shows: the Menu overlap, the slow first screen, stale dialogs, tile seams, typing in a field firing shortcuts. The smoke test drives the real web client in headless Chrome so the main flows are checked by a command instead of by hand.

**Run it**: `.\gradlew.bat :client-web:smokeTest` (builds the web client and the server, installs the Node dependencies, runs the test). Needs **Node** and **Chrome, Edge or Chromium** installed (`CHROME_PATH` overrides the search). It is *not* part of `check`. Screenshots of each key screen, the server log and `report.json` land in `build/smoke-test/`; a failed step adds `FAILED-<step>.png`. Set `SMOKE_HEADED=1` to watch it, `SMOKE_CONSOLE=1` to print every browser console message.

## What it does

It starts a throwaway realm (a temp copy of `games/blank`, empty save data, free ports, so the owner's servers are never touched), serves `client-web/build/dist/js/webapp`, and runs these steps in order, stopping at the first failure:

1. the client loads and the login screen draws (not blank);
2. the realm browser narrows its list as you type (a search matching nothing empties the bundled one-realm list and offers Clear filters; deleting the text brings it back) and keeps the typed text, and the Down arrow selects a realm ([realm browser](/client/realm_browser.md));
2b. pressing Enter in the sign-in form logs in and puts the player in the world (the map draws); a new account first lands on the character screen ("Make your first character"), so the step names one (`Hero`) and presses Create (the test had expected to go straight into the world since the character screen was added, and failed here until 2026-11-17);
3. a server plugin (the example in `plugin-example/`, installed into the throwaway realm) shows its own window, its Wave button works and its Goodbye button makes the server close the window;
4. the player can walk (arrow or WASD keys move the pixel position);
5. a game window opens and closes (`I`);
6. F1 opens the editor showing the five map editors: Tileset, Layers, Attributes, Maps and Map properties ([default layout](/client/f1_editor.md));
7. the Editors finder opens an editor by name and typed keys do not reach the game;
8. typing in a text field does not trigger editor shortcuts (regression for `VisTextField` not being a `TextField`);
9. a named layout can be saved and lands in the browser storage;
9b. the *Map editing* preset shows the five map editors, and the Editors finder moves when its title is dragged;
10. erasing with the eraser (`E`) marks the map changed and Ctrl+S saves it (erasing, because the brush's tile may already be what is painted there);
10b. the Events panel builds an event (a spoken line, an answer, a step under the answer) and Save writes it to the throwaway game folder ([F1 editor](/client/f1_editor.md));
10c. the Settings editor switches the colour scheme (the editor is rebuilt with Settings open again) and the choice lands in the browser storage;
11b. the page is installable (manifest link, theme colour, the manifest, icon and `sw.js` fetch, and a registered service worker) and a browser `offline` event makes the client open a new WebSocket and stay in the world ([reconnect](/client/reconnect.md));
12. on an emulated phone (390 x 844, touch), tapping the name field opens the typing bar (`#morgana-text-input`), typing there fills the game field, and Enter logs in; the new account's character is named through the same bar and created ([play on a phone](/playbooks/play_on_a_phone.md));
11. the browser console stayed clean (no page errors, failed requests or HTTP errors besides the favicon).

## How the test sees a canvas

The game draws everything on one canvas, so the client describes itself: `GameProbe` (client-core, no platform code) builds a JSON snapshot, `MorganaGame.probeSnapshot()` exposes it, and `WebLauncher` publishes it as `window.__morganaProbe()`. It is read-only and reports: whether the client is ready, whether the player is in the world, map, player position, editor open/dirty/undo depth/tool, each panel's state, the Editors finder, open windows, recent chat lines, and the **position of every visible text button and text field** (by its text or hint; passwords are never reported). Coordinates are in the game's own screen pixels; the test scales them to the page. To click something the test calls `clickButton('Editors')` or `clickField('Search')`.

## Adding a step

1. If the test needs a fact the probe does not report, add it to `GameProbe.snapshot` (and rebuild the web client).
2. Add a `step('what should hold', async () => { ... })` to `smoke-test/run.mjs`: use `waitFor(description, predicate)` rather than fixed sleeps, `clickButton(text)` to press a button (it waits until the button has stopped moving, because a button built in the current frame reports its position from before the layout ran, which once made a step fail two runs in five), `screenshotDrawn(name, minColours)` for a screen worth keeping, and keep each step small. Later steps assume the state earlier ones leave (the editor is open from step 5).
3. Prove it can fail: break the behaviour on purpose, rebuild, and see the step fail ([debug determinism](/playbooks/debug_determinism.md) has the same habit for replays).

## Known limits

- Single browser, 1280 x 720, no touch layout; headless Chrome renders WebGL with SwiftShader, so it checks that things draw and respond, not pixel-exact art.
- Screenshots are saved for people, not compared. A pixel-diff layer is possible later (it needs a stable renderer and an approval flow).
- The first load takes a few seconds (it took about 90 s until the pixmap fix of 2026-11-14, see [client-web](/architecture/client_web.md)) but can be much slower in a throttled desktop Chrome tab; the load timeout is `SMOKE_LOAD_TIMEOUT_MS` (default 6 minutes).
- No CI runs it yet (the repository has no CI configuration); add `:client-web:smokeTest` to the pipeline when one exists.

## Related scripts
- `smoke-test/ui-audit.mjs` (`.\gradlew.bat :client-web:uiAudit`) screenshots the Realm Browser, Local Worlds, all three steps of the New Realm Wizard, in-game HUD/Inventory, and all 19 F1 editor panels into `build/ui-audit/`, checking `GameProbe.overflows` on every view.
- `smoke-test/tour.mjs` screenshots every editor panel against a manually started server for design reviews.
- `smoke-test/trailer.mjs` records the Halcyon Reach gameplay trailer (see [Record the Halcyon Reach Trailer](/playbooks/record_trailer.md)).
- The Hearthvale showcase page script (`smoke-test/showcase.mjs`) was deleted with Hearthvale on 2026-11-13.
