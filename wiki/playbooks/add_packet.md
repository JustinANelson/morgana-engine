---
type: Playbook
title: "Add a Network Packet"
description: "Checklist for a new client or server packet."
tags: [playbook, protocol]
code: ["shared-protocol/src/main/kotlin/com/morgana/protocol/Packets.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "client-core/src/main/kotlin/com/morgana/client/state/ClientWorldState.kt"]
symbols: ["ClientPacket", "ServerPacket", "handleAuthenticatedPacket", "applyServerPacket"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Add a Network Packet

1. In `Packets.kt`, add a `@Serializable @SerialName("c_...")` subtype of `ClientPacket` or `@SerialName("s_...")` of `ServerPacket` ([wire format](/protocol/wire_format.md)).
2. Client -> server: handle in the coordinator's `handleAuthenticatedPacket` and call a simulation method ([coordinator](/server/coordinator.md)).
3. Server -> client: produce it from `dispatchEvents` (add a simulation event if needed, [simulation events](/domain/simulation_events.md)) and consume it in `ClientWorldState.applyServerPacket` ([state](/client/client_state_and_prediction.md)).
4. Extend `Phase1ProtocolCodecTest` (JSON round trip).
5. If the change is breaking (renamed/removed/required fields), bump `PROTOCOL_VERSION` in `Packets.kt` ([wire format](/protocol/wire_format.md)).
6. Update the [client](/protocol/client_packets.md) or [server](/protocol/server_packets.md) packet catalog.
