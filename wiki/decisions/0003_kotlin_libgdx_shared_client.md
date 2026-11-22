---
type: Decision
title: "ADR 0003: Kotlin + LibGDX with a Shared Client Core"
description: "One Kotlin codebase for server and clients, with LibGDX giving desktop and Android from a single client-core."
tags: [adr, platform]
status: draft
code: ["client-core/build.gradle.kts"]
symbols: ["gdx", "vis-ui"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# ADR 0003: Kotlin + LibGDX with a Shared Client Core

**Status**: Accepted.

**Decision**: Kotlin on the JVM for everything; the client is LibGDX (+ KTX, Scene2D, VisUI) split into platform-independent `:client-core` and thin launchers ([desktop](/architecture/client_desktop.md), [android](/architecture/client_android.md)).

**Consequences**: protocol and domain classes are shared verbatim between client and server (no codegen); Android is nearly free; the JVM is required for hosts. Compared with Intersect (C#/MonoGame) this trades .NET ecosystem tooling for cross-platform reach ([competitive landscape](/reference/competitive_landscape.md)).

> Rationale source: reconstructed from the code, design docs, and roadmap; confirm with the original author and then add a `verified` entry.
