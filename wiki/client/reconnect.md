---
type: Concept
title: "Reconnect and Session Resume"
description: "How a client whose connection dropped (backgrounded app, locked phone, network loss) returns to its character: heartbeat, resume tokens, backoff, takeover."
tags: [client, server, network]
code: ["client-core/src/main/kotlin/com/morgana/client/net/ReconnectController.kt", "client-core/src/main/kotlin/com/morgana/client/net/ResumeStore.kt", "client-core/src/main/kotlin/com/morgana/client/MorganaGame.kt", "client-core/src/main/kotlin/com/morgana/client/ui/ConnectionBanner.kt", "server-app/src/main/kotlin/com/morgana/server/ResumeTokens.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "client-net-netty/src/main/kotlin/com/morgana/client/net/FileResumeStore.kt", "client-web/src/main/kotlin/com/morgana/client/web/BrowserResumeStore.kt", "client-web/src/main/kotlin/com/morgana/client/web/BrowserWebSocketTransport.kt", "client-web/src/main/kotlin/com/morgana/client/web/BrowserDevice.kt", "client-core/src/main/kotlin/com/morgana/client/net/LinkQuality.kt", "client-core/src/main/kotlin/com/morgana/client/ui/LinkQualityIndicator.kt"]
symbols: ["ReconnectController", "ReconnectPolicy", "ResumeStore", "ResumeInfo", "ResumeTokens", "ResumeSession", "SessionEnded", "LinkQuality", "installBrowserLifecycle", "networkLost", "networkReturned", "evictExistingSession", "completeLogin"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-08T12:00:00Z }
stale_after: 2027-05-07T00:00:00Z
---

# Reconnect and Session Resume

Phones and browsers kill sockets and freeze timers when the app is backgrounded. Instead of leaving a frozen game on screen, the client notices, reconnects, and puts the player back on their character without retyping the password.

## Protocol
- `AuthResult.resumeToken`: a random single-use token issued at every successful login or resume.
- `c_resume` (`ClientPacket.ResumeSession(token, protocolVersion)`): redeems a token. The server answers with a normal `s_auth_res` (success carries a **fresh** token; failure says the session expired). It uses the `AUTH` flood budget.
- `s_session_end` (`ServerPacket.SessionEnded(reason, resumable)`): the server is ending this session. `resumable = false` (signed in on another device) tells the client **not** to reconnect, so two devices never fight over one character.
- `c_ping` / `s_pong` (already existed) are the heartbeat.

## Server (`ResumeTokens`, `MorganaWorldCoordinator`)
- A token is valid while its session is connected and for **30 minutes after** the session ends; redeeming **retires** it. One live token per account. Tokens are memory-only, so a server restart makes everyone log in again.
- **Lost-reply overlap** (2026-11-17): the fresh token travels only in the `AuthResult`, and on a phone that reply is often lost with the connection, which used to send the player back to the password screen. A retired token therefore works **once more for 60 s** (`ResumeTokens.overlapMs`), but only while the session that redeemed it has already ended, so a captured token cannot be replayed to take over a live session; it also dies as soon as the fresh token is used. Tests in `ReconnectTest`.
- `completeLogin` is shared by password login and resume. `evictExistingSession` handles the "old socket not yet noticed dead" race: the old session gets `SessionEnded(resumable=false)`, is saved and removed exactly like a disconnect, and is detached (`characterId = null`). `onSessionDisconnected` ignores a session that no longer owns its character, so a stale socket timing out later cannot remove the resumed player.
- Netty `IdleStateHandler` closes any connection silent for 75 s (clients ping every 10 s), so dead sockets free their character and start the token grace period.

## Client (`ReconnectController`, wired in `MorganaGame.render`)
`ReconnectController` is a pure state machine (`OFFLINE`, `ONLINE`, `WAITING`, `ATTEMPTING`, `GAVE_UP`) driven by a caller-supplied clock, covered by `ReconnectControllerTest`.
- **Heartbeat**: ping every 10 s; no packet for 30 s means the connection is dead (half-open socket) and it is dropped.
- **Browser lifecycle** (`installBrowserLifecycle`, `BrowserDevice.kt`): `visibilitychange` (visible) and `pageshow` (also after a back/forward-cache restore) call `MorganaGame.onAppForeground`; `online` calls `onNetworkOnline` (`ReconnectController.networkReturned`: probe an open socket, or skip the back-off and retry now, also reviving `GAVE_UP`); `offline` calls `onNetworkOffline` (`networkLost`: drop the socket and reconnect at once instead of waiting out the 30 s silence); `pagehide` calls `onPageHidden`, which closes an online socket so the server saves and removes the character immediately (the token is kept, so the next visit resumes).
- **App resume**: `ApplicationListener.resume()` or a frame gap over 3 s calls `appResumed`: the next frame pings at once and drops the connection if nothing answers within 4 s. An absence longer than 30 s is treated as dead immediately.
- **Attempts**: attempt 1 is immediate, then back off 1, 2, 4, 8, 15 s (repeating); each attempt (connect plus resume handshake) times out after 10 s; after 12 failures it stops (`GAVE_UP`) until the player presses Retry.
- **Outcomes**: resume success returns to `ONLINE`. A failed `AuthResult` (token expired, banned), a non-resumable `SessionEnded`, or a changed server identity ends in `returnToLogin(message)`, which clears the token and `ClientWorldState.resetSession()`.
- **UI**: `ConnectionBanner` (top centre, never blocks the map) shows "Connection lost. Retrying in 4s (attempt 3)" with **Retry now** and **Log out**, plus a quiet "Waiting for the server..." when the link goes quiet. Settings has **Log out**.
- **Transport hardening** (`BrowserWebSocketTransport`): every socket event first checks its socket is still the current one, so a late `close` from a replaced socket cannot mark the new connection dead; `connectAsync` supersedes (does not fail) a connect in flight; a frame that cannot be decoded is counted (`ClientTransport.takeUndecodableCount`) and skipped instead of throwing into the browser's event loop, and after three the banner says to reload (the client is older than the server); a send on a closing socket is swallowed; and a queue over 20 000 packets (a tab left in the background) is dropped and the socket closed, so the client reconnects and the login sends the world afresh. A smaller backlog is caught up over a few frames (`MorganaGame.drainBudget`, up to 2048 packets a frame).
- **Send backpressure**: the browser's `WebSocket.bufferedAmount` is exposed as `ClientTransport.bufferedBytes`; more than `SendGatePolicy.maxBufferedBytes` (32 KB) unsent makes `SendGate.isStalled` true, which pauses attacks and spells and lets only probe steps through, exactly as an unanswered ping does.
- **Frame guard**: `MorganaGame.render` catches a throwing frame, skips it and logs it; five in a row call `DeviceFeatures.showFatalError`, which in the browser draws a plain-HTML "Something went wrong" panel with a Reload button (reloading resumes the character).
- **Link indicator** (`LinkQuality`, `LinkQualityIndicator`): four bars and the round trip at the top centre while in the world (good under 150 ms, fair under 350 ms, then poor; "slow" when the send gate is stalled; "offline" while reconnecting). The bar count carries the meaning and the colours follow the colour-blind palettes. The reconnect banner now adds "Your character is safe." while retrying.
- **Surviving a restart**: `ResumeStore` keeps `ResumeInfo(host, port, tls, realmId, token)` (never the password): `BrowserResumeStore` uses `localStorage`, `FileResumeStore` writes `client-data/resume.json` on desktop and `resume.json` in the app files dir on Android, and the default is in-memory. On launch `resumeSavedSession` tries to resume immediately; if the token expired the login screen shows the server's message.

## Tests
`ReconnectTest` (server: tokens, rotation, grace expiry, the lost-reply overlap and its refusal while the resumed session lives, takeover, stale-disconnect safety), `ReconnectControllerTest` (client policy, including `networkLost`/`networkReturned`), `SendGateTest` (buffered-bytes stall), `LinkQualityTest`, `HapticModelTest`, the browser [smoke test](/playbooks/browser_smoke_test.md) (an `offline` event makes the page open a new socket and stay in the world), `ReconnectEndToEndTest` (real sockets: abrupt drop then resume; second device eviction).

Related: [wire format](/protocol/wire_format.md), [client packets](/protocol/client_packets.md), [server packets](/protocol/server_packets.md), [web client](/architecture/client_web.md), [input and HUD](/client/input_and_hud.md).
