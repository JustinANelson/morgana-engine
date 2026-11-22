---
type: Decision
title: "ADR 0009: JSON Text Frames on the Wire (CBOR Deferred)"
description: "The current wire format is JSON over length-prefixed TCP; CBOR exists but is unused."
tags: [adr, protocol]
status: draft
code: ["shared-protocol/src/main/kotlin/com/morgana/protocol/PacketCodec.kt"]
symbols: ["encodeClientJson", "encodeClientCbor"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# ADR 0009: JSON Text Frames on the Wire

**Status**: Provisional.

**Observed decision**: client and server both use `PacketCodec` JSON with a 4-byte length prefix ([wire format](/protocol/wire_format.md)). CBOR support was added to the codec but never wired in.

**Why it is acceptable now**: debuggable by eye, trivial for headless bots/agents, adequate at hobby scale.
**Revisit when**: bandwidth or CPU for large map snapshots matters. Switching is localized to the Netty pipeline plus the two `PacketCodec` call sites; bump `PROTOCOL_VERSION` when you switch ([wire format](/protocol/wire_format.md)).

> Rationale source: reconstructed from the code, design docs, and roadmap; confirm with the original author and then add a `verified` entry.
