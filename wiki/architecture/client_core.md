---
type: Module
title: "Module: client-core"
description: "The platform-independent LibGDX client: state, renderer, HUD, F1 editor, networking, assets, audio."
tags: [module, client]
code: ["client-core/src/main/kotlin/com/morgana/client"]
symbols: ["MorganaGame", "ClientTransport", "ClientWorldState", "MorganaWorldRenderer", "F1LiveAdminPanel", "ProceduralRetroAssets", "RetroSoundEngine", "MorganaRealmsHubWindow", "AssetCache", "PerRealmStore", "RealmDirectoryClient"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-08T18:55:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Module: `:client-core`

| Package / class | Role |
| :--- | :--- |
| `MorganaGame` | LibGDX application adapter: realms hub, input, main loop ([input and HUD](/client/input_and_hud.md), [ADR 0014](/decisions/0014_morgana_realms_universal_client_architecture.md)) |
| `net/ClientTransport` | Platform-neutral, non-blocking connection interface (`connectAsync`, `send`, `drainPackets`); the Netty TCP implementation `MorganaNetClient` lives in `:client-net-netty` ([wire format](/protocol/wire_format.md)) |
| `state/ClientWorldState` | Applies server packets, prediction, editor painting ([state and prediction](/client/client_state_and_prediction.md)) |
| `render/MorganaWorldRenderer` | Map layers, entities, lighting, weather ([rendering](/client/rendering.md)) |
| `ui/MorganaRealmsHubWindow` | Universal client hub: community directory browser ([realm browser](/client/realm_browser.md)), direct invite/host connect, local worlds |
| `ui/PlayerHudAndWindows`, `ui/F1LiveAdminPanel` | Scene2D/VisUI windows ([F1 editor](/client/f1_editor.md)) |
| `assets/ProceduralRetroAssets`, `audio/RetroSoundEngine` | Generated art and sound; sound loads through a platform `SoundLoader` ([assets and audio](/client/assets_and_audio.md)) |
| `assets/ContentAddressedAssetCache` | Content-addressed SHA-256 asset cache (`AssetCache`, `FileAssetCache`, `InMemoryAssetCache`) |
| `state/PerRealmClientStorage` | Per-realm isolated player hotbar preferences, history, and favorites (`PerRealmStore`, `FilePerRealmStore`) |
| `realms/RealmDirectoryClient` | Realm discovery port (`RealmDirectoryClient`, `InMemoryRealmDirectoryClient`, `HttpJsonRealmDirectoryClient`) |

The browser build of this module is [client-web](/architecture/client_web.md).

Test: `Phase5And6ClientStateTest` (no GL context needed; logic is kept in `ClientWorldState`). Why LibGDX/Kotlin: [ADR 0003](/decisions/0003_kotlin_libgdx_shared_client.md).
