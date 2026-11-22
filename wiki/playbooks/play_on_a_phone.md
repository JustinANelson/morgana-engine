---
type: Playbook
title: "Play on a Phone (Browser, Tailscale)"
description: "Serve the browser client from the game server with --web and play from a phone's browser over Tailscale or a home network: what makes it work and what to check when it does not."
tags: [web, mobile, tailscale, server, playbook]
code: ["server-app/src/main/kotlin/com/morgana/server/web/WebClientHttpHandler.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaServerMain.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaNettyServer.kt", "client-core/src/main/kotlin/com/morgana/client/ui/TextEntryBridge.kt", "client-web/src/main/kotlin/com/morgana/client/web/BrowserTextEntry.kt", "client-core/src/main/kotlin/com/morgana/client/net/TransportPolicy.kt", "client-core/src/main/kotlin/com/morgana/client/net/LaunchQuery.kt"]
symbols: ["WebClientHttpHandler", "findBuilt", "browserAddresses", "WebSocketUpgradeGate", "TextEntryBridge", "TextEntrySync", "BrowserTextEntry", "webDefaultPort", "isBanned"]
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-13T12:00:00Z }
stale_after: 2027-05-12T00:00:00Z
---

# Play on a Phone (Browser, Tailscale)

Added 2026-11-13 (owner request: "play on a phone browser over Tailscale").

## Steps
1. Install [Tailscale](https://tailscale.com) on the computer running the server and on the phone, signed in to the same tailnet.
2. Start the server with the browser client: from the SDK `bin\morgana-server.bat --data worlds/<realm> --web`; from source build the client once (`.\gradlew.bat :client-web:gdx_teavm_web_js_release_build`) and run `.\gradlew.bat :server-app:run --args="--game games/roguelike --web"`.
3. The console prints the addresses to open, the Tailscale one first (`http://100.x.y.z:7002/  (Tailscale: ...)`). Open it, or `http://<machine name>:7002/` with MagicDNS, in the phone's browser.
4. Optional: install it. On Android Chrome use the menu's **Install app**; on iPhone Safari use Share, **Add to Home Screen**. It then opens fullscreen. (Installing needs an `https://` address, for example Tailscale Serve/Funnel; the plain `http://` Tailscale address still plays in the browser, just without the install prompt or offline page cache.)
5. Tap **Enter Realm**. Tapping a text box opens a typing bar at the top with the phone keyboard; Enter in the bar submits. Landscape fits the menus best.

## What makes it work
- **The server hosts the page** (`--web [dir]`, `WebClientHttpHandler`): plain GET/HEAD requests on the WebSocket port are served from the built client (`web/` in the [SDK](/playbooks/build_the_sdk.md), else `client-web/build/dist/js/release/webapp`, else the dev build; `WebClientHttpHandler.findBuilt`), so the page and the game share one address and port. `--web` turns the WebSocket port on at 7002 unless `--ws-port` or `MORGANA_WS_PORT` says otherwise. Paths cannot leave the folder; any other plain request gets a 404 (before, a plain request on the WebSocket port hung). The game's own art keeps `/assets/<manifest path>`; `AssetHttpHandler` now passes on `/assets/` paths its manifest does not list, so the client's files (`/assets/com/...`) are served too.
- **The page connects back to where it came from** (`webDefaultPort`): its own port, 443 for an https page with no port (behind `tailscale serve` or another proxy), or 7002 from the TeaVM dev server (port 8080). The bundled "Local Server" realm entry uses the page's host, port and https.
- **Unencrypted `ws://` is allowed over Tailscale** (`TransportPolicy.isLocalHost`): 100.64.0.0/10, `fd7a:115c:a1e0::/48`, `*.ts.net` and bare machine names count as private, like home-network addresses. Tailscale encrypts traffic between devices with WireGuard.
- **The phone keyboard** (`TextEntryBridge`, `TextEntrySync`, `BrowserTextEntry`): gdx-teavm's `setOnscreenKeyboardVisible` is empty, so on touch screens a bar with a real `<input>` mirrors the focused game text field (libGDX `TextField` or VisUI `VisTextField`). Edits are replayed as key presses (Backspace, then the new characters), so filters, length limits and listeners behave as with a keyboard; Enter is sent as the Enter key; Done or tapping away closes it. Android opens the keyboard at once; iOS only from a direct tap, so tap the bar's box there.
- **Page loads do not trip the flood limits** (`WebSocketUpgradeGate`): on the WebSocket port a new connection is only checked against bans (`ConnectionGate.isBanned`); the full [connection gate](/server/connection_limits.md) runs when a request asks to become a WebSocket game session, and the session starts at the handshake. Before this, the dozens of requests of one page load counted as a connection flood and banned the phone's address for 10 minutes.

- **Phone-friendly behaviour** (2026-11-17, see [web client](/architecture/client_web.md) and [reconnect](/client/reconnect.md)): the game reconnects at once when the phone's network returns or the tab comes back, resumes even if the last reply was lost, keeps the screen awake while you play, buzzes on hits (Android), shows a signal indicator at the top, and offers a Reload button if the game itself fails.

## Checked
The [browser smoke test](/playbooks/browser_smoke_test.md) step 12 logs in on an emulated phone (390 x 844, touch) using only the typing bar. By hand on Windows: the SDK server with `--web` served the roguelike to a phone-sized touch browser through this machine's Tailscale address; the player typed a name in the bar and entered Lantern Camp. Not yet tried on a physical iPhone or Android phone.

## If it does not work
- **Blank or black page**: the server log should say "Browser client served from ..."; if `--web` found no client, build it (step 2). A 404 for `/assets/...` means the page is older than the server.
- **"Refusing an unencrypted connection"**: the address is not one of the private kinds above (for example a public DNS name); use the Tailscale address or name, or set up TLS.
- **Connects on Wi-Fi at home but not over Tailscale**: Windows Firewall may block Java on the Tailscale network; allow Java on private networks, or check `tailscale ping <phone>`.
- **Locked out after many attempts**: `/ipbans` and `/ipunban <ip>` (Developer or Owner), or wait the 10 minutes.
- **Portrait**: the realm browser is wider than a phone held upright; turn it sideways.
