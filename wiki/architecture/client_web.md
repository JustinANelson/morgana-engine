---
type: Module
title: "Module: client-web"
description: "The browser client: client-core compiled to JavaScript by gdx-teavm, talking to the server over WebSocket."
tags: [module, client, web]
code: ["client-web/build.gradle.kts", "client-core/src/main/kotlin/com/morgana/client/assets/PixmapPixels.kt", "client-web/src/main/kotlin/com/morgana/client/web", "client-web/src/main/java/com/morgana/client/web/compiler/MorganaReflectionPolicy.java", "client-web/pwa", "client-web/src/main/kotlin/com/morgana/client/web/BrowserDevice.kt"]
symbols: ["BrowserWebSocketTransport", "BrowserSoundLoader", "BrowserAssetDownloader", "WebLauncher", "MorganaReflectionPolicy"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T19:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Module: `:client-web`

The shared [client-core](/architecture/client_core.md) compiled to JavaScript by the [gdx-teavm](https://github.com/xpenatan/gdx-teavm) plugin (1.6.2, TeaVM 0.15, libGDX 1.14.2). The Kotlin game code is unchanged; only the launcher and the network transport are browser-specific.

| File | Role |
| :--- | :--- |
| `web/WebLauncher.kt` | `main()`: creates `WebApplication` with `MorganaGame`; the canvas fills the window; the login host defaults to the page's host, port **7002** |
| `web/BrowserWebSocketTransport.kt` | `ClientTransport` over the browser `WebSocket`: one JSON packet per text frame; the browser does TLS (`wss://`), so there is no fingerprint pinning or trust-on-first-use |
| `web/BrowserAssetDownloader.kt` | `AssetDownloader` over `fetch` (async only; a browser cannot block): downloads a game's own art ([game-owned art](/server/game_assets.md)); the pipeline checks every SHA-256, and the art is kept in memory per page load |
| `web/BrowserDevice.kt` | `BrowserDeviceFeatures` (vibration, screen wake lock, the fatal-error panel), `installBrowserLifecycle` (visibility, `online`/`offline`, `pagehide`) and `installPwaSupport` (manifest and meta tags, service worker registration, rotation re-size, a one-time "turn your phone sideways" hint) |
| `pwa/` | `manifest.webmanifest`, `sw.js` and `icons/` (192, 512 and a maskable 512), copied beside the built page by the `copyPwaFiles` / `copyPwaFilesRelease` tasks (run after each web build and before `dist` and the SDK) |
| `web/BrowserSoundLoader.kt` | `SoundLoader` that plays the synthesized WAV bytes through Howler (the desktop default writes temp files) |
| `compiler/MorganaReflectionPolicy.java` | Reflection grants for VisUI/Scene2D skin loading (registered through `META-INF/services/org.teavm.extension.spi.reflection.ReflectionPolicy`) |
| `build.gradle.kts` | Plugin config (dev and `release` JS targets), the 1x skin as classpath assets, and `skinClasses` (every class the skin JSON names, kept from dead-code removal) |

## Run it

**Simplest (and for phones):** build the release client once, then start the server with `--web`; it serves the page on its WebSocket port and the page connects back to it. See [play on a phone](/playbooks/play_on_a_phone.md) (Tailscale, the phone keyboard bar, the flood-limit change).

Separately, for development:

1. Start the server with the WebSocket listener on: `MORGANA_WS_PORT=7002` then `.\gradlew.bat :server-app:run` (see [wire format](/protocol/wire_format.md)). Set `MORGANA_BIND_HOST=<ip>` (e.g. a Tailscale address) to listen only on that address; unset listens on all interfaces.
2. `.\gradlew.bat :client-web:gdx_teavm_web_js_run` builds and serves on `http://localhost:8080`. `:client-web:gdx_teavm_web_js_build` only builds (`client-web/build/dist/js/webapp`).

## Installable app and phone behaviour

- **Install** (2026-11-17): the page adds a manifest link, `theme-color` and the Apple web-app tags at start (the generated `index.html` is not ours to edit), so Chrome/Android offers Install and iOS Add to Home Screen opens it **fullscreen** with no browser chrome. The server serves `.webmanifest` as `application/manifest+json`.
- **Service worker** (`pwa/sw.js`): network-first for the page's own files (`index.html`, `app.js`, `scripts/`, `assets/com/`, icons), with the saved copy used when the network fails or takes over 4 s, so a phone with a poor signal still gets the page. The build is rebuilt under the same names, so it never prefers a stale copy when the server answers. It does not touch the game's art (`/assets/<manifest path>`, kept in IndexedDB), `/wiki/`, queried URLs, non-GET requests or the WebSocket. Registered only on HTTPS or localhost; on other pages (plain `http://` over a LAN or Tailscale IP) it is skipped and everything else still works.
- **Safe areas**: the viewport no longer asks for `viewport-fit=cover`, so phones keep the page clear of notches and rounded corners (black strips beside it). Rotation is followed by two delayed `resize` events because browsers report the new size late.
- **Vibration** (`HapticModel`): a short tick when the local character loses health (at most one per 200 ms), a long buzz on death, a medium one on a level up; off when **Reduce motion** is on and on non-touch devices. Android Chrome only: iOS Safari has no vibration API.
- **Screen wake lock**: held while in the world and taken again when the page returns to the foreground (browsers release it when hidden), so the phone does not lock mid-fight and force a reconnect.
- Reconnect, backgrounding and flaky-network behaviour: [Reconnect and Session Resume](/client/reconnect.md).

## Release build & itch.io packaging

`.\gradlew.bat :client-web:gdx_teavm_web_js_release_build` writes a minified (obfuscated) build to `client-web/build/dist/js/release/webapp`: `app.js` is 1.8 MB (565 KB gzipped) against 6.6 MB (926 KB) for the dev build, and only the 1x VisUI skin is shipped (the game calls `VisUI.load()` with the default scale). Verified in Chrome: the skin still loads by class name and a WebSocket login renders the world. Serve it from any static web server.

`.\gradlew.bat :client-web:dist` packages that release webapp (excluding `WEB-INF/`) into `client-web/build/dist/morgana-realms-client-html.zip` with `index.html` at the zip root for itch.io / static hosting (`assets/.keep` is kept because the TeaVM asset preloader fetches it). See `docs/ITCH_DEPLOYMENT.md` for the full itch.io and Tailscale Funnel guide. To publish to itch.io:

```powershell
butler push client-web/build/dist/morgana-realms-client-html.zip <your-user>/morgana-realms-client:html --userversion <version>-<git-hash>
```

## Status and known limits

* Verified in Chrome: the login window renders, a WebSocket login succeeds, and the world renders with entities and minimap.
* **Never fill a pixmap pixel by pixel in code that runs in the browser.** gdx-teavm keeps a pixmap's pixels in the wasm heap and, after *every* drawing call (`drawPixel`, `fillRectangle`, `drawLine`, ...), copies the whole pixmap back into the JS buffer that textures upload from (`Gdx2DPixmapNative.copyHeapToBuffer`), so a `drawPixel` loop costs O(pixels²). Until 2026-11-14 `ProceduralRetroAssets` filled its atlas pages that way, and the page sat on a black screen for about 85 s between the loading bar and the realm browser (CPU profile: 84 of 85 s in `copyHeapToBuffer` under `uploadRgbaTexture`). Pixmaps going straight to a texture now use `rgba8888UploadPixmap` / `Pixmap.writeRgba8888` (`PixmapPixels.kt`), one bulk write into `Pixmap.getPixels()`; on the browser that write does not reach the wasm copy, so do not `getPixel` or draw on such a pixmap afterwards. `create` now takes about 0.5 s and the realm browser shows about 3 s after navigation (headless Chrome, dev build). `getPixel` does not copy and is fine in loops.
* **Browser throttling also slows startup**: in the automated Chrome tab a trivial page with no game code ran 60 fps for about 4 s after navigation and then about 1 fps. gdx-teavm drives its startup (asset preload, `create`, and a 60-frame warm-up before the first `render`) from `requestAnimationFrame`, so a throttled tab needs about 45 s to reach the first frame. Verified in a separate, fresh Chrome driven by puppeteer-core with default flags: the first game frame (not the loading splash) appears about 3.5 s after page load for both the dev and release builds, at 58 fps; the release build is smaller but no faster to start on localhost. The Chrome switches `--disable-background-timer-throttling`, `--disable-backgrounding-occluded-windows` and `--disable-renderer-backgrounding` made no measurable difference. When testing through an automated or backgrounded tab, expect throttling and use a dedicated browser window.
* Verified in a fresh Chrome driven by puppeteer-core: **keyboard movement** (arrows and WASD send `c_move`; a new character walked right, down, left and up as expected), **hotkeys** (`I` opens the inventory), and **chat** (typing works; the Send button sends and the message appears in the console and as an overhead bubble). Enter does not send chat in any client: the game only binds the Send button.
* **Audio** works after a fix: `RetroSoundEngine` used temp files, which do not exist in a browser, and silently loaded no sounds (Howler held 0). It now takes a `SoundLoader`; `BrowserSoundLoader` hands the synthesized WAV bytes to Howler. In the browser all 9 effects load, the audio context runs, and Space and G trigger `Howl.play`. Not verified by ear.
* Verified: the [F1 editor](/client/f1_editor.md) (all tabs render, item and map saves reach the server's data files). Not yet verified: a production (`wss://`) deployment. (A character that could not move after a server restart turned out to be a server bug, now fixed: see [movement](/systems/movement.md).)
* Verified in Chrome: a realm whose `assets/tilesets/1.png` had a recolored grass tile showed the recolored grass after login (fetched from the server's `/assets/` endpoint on the WebSocket port, hash-checked, applied). Same-origin and cross-origin both work because the endpoint allows any origin.
* Adding a new VisUI widget style to the skin needs its class added to `skinClasses`; otherwise the browser fails with `ClassNotFoundException` at start.
* The released gdx-teavm 1.6.2 does not contain `GdxReflectionHelper` that the master docs describe; the policy lists its grants by hand.
* VisUI 1.5.9 prints a harmless "needs libGDX 1.14.1" warning.
