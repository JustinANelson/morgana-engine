# Morgana Realms: One Universal Client

Background for [ADR 0014](../wiki/decisions/0014_morgana_realms_universal_client_architecture.md). The product name was settled as *Morgana Realms*.

## Reusing a Single Universal Desktop & Android Client

In Intersect, every game creator has to distribute their own custom client executable because game rules and assets are baked into the client folder. In a **PlayerWorlds-style Universal Client**, a player installs **one** Desktop or Android app (or opens one Web URL) and switches between completely different games from the server browser or via `morgana://` invite links.

Because you already built `WorldLibrary`, `WorldPack`, `GameDefinition`, and `ProceduralRetroAssets`, you only need to solve four architectural boundaries to make a single `:client-desktop` and `:client-android` binary work for every game:

### 1. Handshake & Dynamic `GameDefinition` Sync

Right now, you are moving stats, vitals, and combat formulas into `GameDefinition` (`game.schema.json`). For one client to play two worlds with completely different stats (e.g., World A has *STR/INT/DEF*, World B is a sci-fi RPG with *Shields/Energy/Tech*):

* **How to wire it:** During the `PROTOCOL_VERSION` handshake, the server sends its `GameDefinition` (or its FNV-1a hash) to the client before character login.
* **Client adaptation:** Your Scene2D `PlayerHudAndWindows` reads vitals and stat rows dynamically from the active `GameDefinition` rather than hardcoding `HP/MP/SP` or `STR/DEF/INT/AGI/VIT` labels.

### 2. Per-Server Asset Sync & Caching (Beyond Procedural Assets)

If every world only uses your built-in `ProceduralRetroAssets` (or a bundled "Morgana Standard Asset Pack" like *PlayerWorlds* used to do with its numbered sprite sheets), **your client already works across all worlds today with zero asset downloads.**

* **When creators want custom pixel art/audio:** Extend your `WorldPack` concept into an **Asset Manifest** (`assets-manifest.json` mapping `<assetId> -> <sha256, byteSize>`).
* **Content-Addressed Local Cache:** On both Desktop and Android, store downloaded tilesets, paperdolls, and `.ogg` files in a local cache folder keyed by SHA-256 (`<user-data>/cache/assets/<sha256>.png`):
* On Desktop: `~/.morgana/cache/`
* On Android: `context.filesDir.resolve("asset_cache")`


* If two different servers use the same community sprite sheet, the client only downloads it once. You can serve these over the existing WebSocket/HTTP port or inline chunked packets before entering the map.

### 3. Per-World Client Storage Isolation

When a single client connects to multiple worlds, local client state must be namespaced by `worldId` or server fingerprint (`#fp=<sha-256>`) so settings don't collide:

* **Quickbar & Keybinds:** Store quickbar slot assignments (`1..0`) per `(worldId, characterName)` so switching from a fantasy server to a PvP arena server doesn't overwrite the player's hotkeys.
* **Known Hosts / TOFU Pinning:** Your `morgana://host:port#fp=<sha-256>` design already namespaces TLS certificate fingerprints per host, which fits a multi-server client naturally.

### 4. Client-Side Scripting Rule: Keep Lua 100% Server-Side

One reason other engines fail at universal clients (especially on **Android** and **iOS**) is that they send executable client code over the network, which violates mobile app store policies and creates security nightmares.

* **Your advantage:** Because Morgana’s `:core-domain` is authoritative and your Event Pages + HUD windows are purely data-driven, **Luak (Lua) never needs to run on the client**.
* All custom Lua hooks, combat overrides, and event triggers execute on the server, while the client simply renders the resulting state deltas, animations, and dialogues. This keeps the Android APK 100% compliant with Google Play policies and completely safe from malicious server owners.

---

## How the Universal Client User Flow Looks

1. **Launch:** The player opens the single **Morgana** app on Desktop, Android, or Chrome.
2. **Hub Screen:** Instead of a hardcoded IP login, they see three tabs:
* **Public Realms:** A lightweight JSON directory of active community worlds.
* **Join via Invite:** Paste a `morgana://host:port#fp=...` link (or click it directly from Discord—Android deep links can register the `morgana://` scheme in `AndroidManifest.xml`!).
* **My Worlds (Desktop):** Your existing Phase 13 `Local World` picker + `New World & Play` (`EmbeddedWorldHost`).


3. **Connect & Sync:** The client verifies the TLS fingerprint, checks the server's `PROTOCOL_VERSION`, receives the `GameDefinition` + any missing custom sprites into the SHA-256 cache, and drops the player straight into that creator's world.