---
type: Module
title: "Module: shared-protocol"
description: "Client/server packet definitions and the JSON/CBOR codec."
tags: [module, protocol]
code: ["shared-protocol/src/main/kotlin/com/morgana/protocol"]
symbols: ["ClientPacket", "ServerPacket", "PacketCodec", "WorldContentCatalogDto", "PROTOCOL_VERSION", "RealmSummary", "AssetManifest"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Module: `:shared-protocol`

Defines `ClientPacket` and `ServerPacket` (sealed interfaces, `kotlinx.serialization`), supporting DTOs
(`WorldContentCatalogDto`, `PublicPlayerSnapshot`, chat/UI enums), and `PacketCodec`.

- Packet catalogs: [client packets](/protocol/client_packets.md), [server packets](/protocol/server_packets.md).
- Realm discovery and asset manifest schemas (`com.morgana.protocol.realms`): [ADR 0014](/decisions/0014_morgana_realms_universal_client_architecture.md).
- Wire details and the JSON-vs-CBOR reality: [wire format](/protocol/wire_format.md).
- Depends on `:core-domain` so packets can carry domain models (`CharacterState`, `MapDefinition`) directly.
- `PROTOCOL_VERSION` (top of `Packets.kt`) must be bumped on breaking changes; the server rejects other versions at login ([wire format](/protocol/wire_format.md)).
- Test: `Phase1ProtocolCodecTest` round-trips packets through both codecs and covers the version field.

Adding a packet: [playbook](/playbooks/add_packet.md).
