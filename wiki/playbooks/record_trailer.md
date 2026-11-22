---
type: Playbook
title: "Record the Trevana Realms Trailer"
description: "Regenerate the gameplay trailer: a throwaway realm, a made-up level 66 Emberweaver, a scripted tour of the maps recorded from the web client, and an ffmpeg edit."
tags: [playbook, demo, web, video]
code: ["smoke-test/trailer.mjs", "smoke-test/trailer-lib.mjs", "smoke-test/trailer-edit.mjs"]
symbols: ["seedRealm", "patchHero", "startRealm"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-17T12:00:00Z }
stale_after: 2027-05-16T00:00:00Z
---

# Record the Trevana Realms Trailer

```powershell
.\gradlew.bat :server-app:installDist :client-web:gdx_teavm_web_js_build   # fresh server and web client
cd smoke-test
node trailer.mjs          # optional argument: scene names, e.g. hub,gear,glass
node trailer-edit.mjs     # build/trailer/trevana-realms-trailer.mp4
```

- **Realm**: `seedRealm` copies `games/trevana-realms` into `build/trailer/realm` (nothing under `games/` or `server-data/` is touched). The script logs in once so the client creates the account (`Aurelion`, password `admin`, the login box's default) and character, stops the server, `patchHero` rewrites the saved character into a level 66 Expert Emberweaver with Skyrender/Arcanist gear, a full bag and Marks, marks every map explored, and the server restarts.
- **Direction**: the hero is Owner, so scenes jump with `/warpto <map> <x> <y>` typed into the chat box (the **Send** button; Enter does not submit) and fight by pressing quickbar keys 1 to 4 while wandering; monsters aggro and the server picks targets. Scenes are the `scenes` table in `trailer.mjs`; the timeline is written to `build/trailer/timeline.json` and used for the captions.
- **Needs a GPU window**: the recording is headed Chrome on the real GPU. Software WebGL (`TRAILER_SOFTWARE=1`) is too slow, the client falls behind its pings and the server pauses the hero ("Poor connection").
- **Touch PCs**: a PC with a touch screen reports `maxTouchPoints > 0`, which switches the web client to its phone layout; `trailer.mjs` overrides it with an init script.
- **Playwright video** needs its own ffmpeg binary; a copy of the system `ffmpeg.exe` at `%LOCALAPPDATA%\ms-playwright\ffmpeg-1013\ffmpeg-win64.exe` stands in for `npx playwright install ffmpeg`.
- Only quickbar slots 1 to 4 are filled: the quickbar is client-side per-realm preference and auto-fills with the first four known spells ([input and HUD](/client/input_and_hud.md)).
- The server runs with `limits.scale` 5 as a safeguard against frame-hitching bursts during recording (the underlying ping budget issue was fixed on 2026-11-17 in `PacketLimits` and `SendGatePolicy`).
