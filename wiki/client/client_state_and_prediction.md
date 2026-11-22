---
type: Concept
title: "Client State and Prediction"
description: "How ClientWorldState consumes packets, smooths motion, and predicts local movement."
tags: [client, prediction]
code: ["client-core/src/main/kotlin/com/morgana/client/state/ClientWorldState.kt"]
symbols: ["applyServerPacket", "predictAndCreateMovePacket", "reconcileLocalCharacter", "resetMovePrediction", "EntityVisualSmoothing", "ActiveFloatingText"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Client State and Prediction (`ClientWorldState`)

- All server packets pass through `applyServerPacket`, which updates maps, entities, catalog, chat, floating text, animation timers, and UI-window requests. It contains no GL calls, which is why it is unit-testable (`Phase5And6ClientStateTest`).
- `predictAndCreateMovePacket(direction)` applies the shared `:core-domain` movement/collision rules locally for zero-latency movement and returns the `c_move` packet to send ([movement](/systems/movement.md)).
- `EntityVisualSmoothing` interpolates remote entities; timers drive floating text, chat bubbles, and sprite animations.
- **Reconciliation**: every `c_move` carries a `seq` (1, 2, 3 ... per connection, reset on `AuthResult` by `resetMovePrediction`) and the client keeps the steps the server has not acknowledged. `s_char_sync` carries `ackMoveSeq`, the last step the server had handled when it sent it. `reconcileLocalCharacter` takes the server's character, drops the acknowledged steps and replays the rest with the same prediction rules, so a sync that is a round trip old does not pull the player back. A server correction (a refused step, a stun, a warp) is kept, and the later steps are replayed from it. The local player's own entry in `s_player_moved` and same-map snapshots keeps the predicted position, because those packets cannot say which steps they include. A sync with `ackMoveSeq = 0` (an older server) is taken as it is.
- **Why it matters**: without reconciliation every step's sync arrived a round trip late and snapped the prediction back. Over a simulated 50 to 110 ms link (each way, with bunching) the browser client made 14 to 18 backward jumps and lost about a quarter of its distance in 2.5 s of walking; with it, none (2026-11-15).
- **Send rate**: free movement sends one step per server tick (50 ms) on average. The repeat timer carries the leftover time over, so no frame rate can send faster than the server's one-step-per-tick limit; after a pause it restarts instead of sending a burst.
- The server is authoritative: it can still correct the client at any time, and the client follows.
- Editor state (working map, selected tool/layer) lives here too ([F1 editor](/client/f1_editor.md)).
- **Poor links (`SendGate`, 2026-11-16)**: a stalled or slow connection used to keep accepting steps and attacks that sat in the socket and arrived together on recovery, so the character lurched and swings landed in a burst. `SendGate` (client-core `net`) now (1) stops sending movement steps once 10 are un-acknowledged, letting one probe step through every 400 ms so a lost acknowledgement cannot freeze walking, (2) pings every 2 s while playing and treats a ping unanswered for 1.5 s as a stall, during which attacks, spells, interactions, pickups and jumps are dropped (the banner says "Poor connection: attacks and spells are paused"), and (3) spaces repeats of the same action by 120 ms. `GatedTransport` wraps the real transport so every send site is covered; movement is gated in the game loop before the step is predicted. Tests: `SendGateTest`.
