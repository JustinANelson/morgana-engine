---
type: Decision
title: "ADR 0014: Morgana Realms Universal Client Architecture"
description: "Decoupled PlayerWorlds-style multi-realm discovery, content-addressed asset caching, per-realm isolated storage, and universal client hub."
tags: [adr, realms, architecture, client, protocol]
code: ["shared-protocol/src/main/kotlin/com/morgana/protocol/realms", "client-core/src/main/kotlin/com/morgana/client/assets/ContentAddressedAssetCache.kt", "client-core/src/main/kotlin/com/morgana/client/state/PerRealmClientStorage.kt", "client-core/src/main/kotlin/com/morgana/client/realms/RealmDirectoryClient.kt", "client-core/src/main/kotlin/com/morgana/client/ui/MorganaRealmsHubWindow.kt", "client-core/src/main/kotlin/com/morgana/client/assets/RealmAssetSyncPipeline.kt", "client-core/src/main/kotlin/com/morgana/client/ui/AssetDownloadDialog.kt"]
symbols: ["RealmSummary", "AssetManifest", "AssetCache", "FileAssetCache", "PerRealmStore", "FilePerRealmStore", "RealmDirectoryClient", "MorganaRealmsHubWindow", "RealmAssetSyncPipeline", "AssetDownloader", "AssetDownloadDialog"]
generated: { by: gemini-2.5-pro, at: 2026-11-08T18:40:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# ADR 0014: Morgana Realms Universal Client Architecture

**Status**: Accepted and implemented on 2026-11-08.

**Context**: In the spirit of PlayerWorlds and classic ORPG engines, Morgana allows community creators to publish custom realms and worlds. Players need a unified client to browse community realms, enter direct invite codes, or host local worlds without downloading separate launcher binaries for each game. Simultaneously, mobile app store policies (Google Play, Apple App Store) strictly forbid executing untrusted dynamic remote code, while network efficiency requires avoiding redundant asset downloads across games.

**Decisions**:
1. **Engine-Game Decoupling**: The engine contains no hardcoded server IPs, proprietary network backends, or closed game logic. Everything is surfaced via clean ports: `RealmDirectoryClient`, `AssetCache`, and `PerRealmStore`.
2. **Server-Side Lua Execution Only**: Game logic and Lua scripts execute 100% server-side on authoritative server nodes. Clients render state snapshots, manifests, and data-driven Scene2D / VisUI forms, remaining 100% compliant with mobile app store policies.
3. **Content-Addressed Asset Caching**: Visual and audio assets are addressed strictly by SHA-256 hash (`<cacheDir>/<sha256>.<ext>`). Assets shared across multiple realms (standard tilesets, retro sound effects) are downloaded only once and verified against checksums to prevent corruption or tampering.
4. **Per-Realm Client Storage Isolation**: User preferences, hotbar configurations, and character bindings are stored per `(realmKey, characterName)`. Binds in one realm do not overwrite or collide with another realm.
5. **Universal Realms Hub UI**: Replaces the bare login dialog with a 3-tab hub:
   - **Realm Directory**: Searchable list of live community servers with player counts, tags, descriptions, and favorites.
   - **Direct Join**: Paste `morgana://` invite codes or host:port addresses with automatic parsing.
   - **Local Worlds**: Embedded single-player and LAN world hosting (desktop).
6. **Streaming Asset Pipeline**: When a realm serves custom assets via `AssetManifest`, `RealmAssetSyncPipeline` queries `AssetCache` for missing delta, downloads files asynchronously via `AssetDownloader` (`HttpAssetDownloader`), validates cryptographic checksums, and updates `AssetDownloadDialog` with live percent and byte progress before seamlessly entering the world.

**Consequences**:
- Any game creator can run an authoritative Morgana server and list it in public or private directories.
- Commercial or closed games built on Morgana can provide custom directory implementations and branding while sharing the open engine foundation.
- Full offline and local development continues to work without internet connectivity.

