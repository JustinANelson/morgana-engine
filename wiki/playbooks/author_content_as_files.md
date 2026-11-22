---
type: Playbook
title: "Author Content as Files (Humans and Agents)"
description: "The file-first content authoring loop with validation and hot reload."
tags: [playbook, content, agents]
code: ["docs/AGENT_GUIDE.md", "docs/schemas", "server-app/build.gradle.kts"]
symbols: ["validateContent", "exportContentSchemas"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# Author Content as Files

Canonical, longer guide: `docs/AGENT_GUIDE.md` section 4. Summary:
1. Work in a game folder (`games/blank`, or a new one from `newGame`); `.\gradlew.bat :server-app:seedWorld` seeds `server-data/world` if you use the legacy layout.
2. Read the JSON Schema for your type (`docs/schemas/<collection>.schema.json`) and copy a seeded file as a template ([content collections](/content/content_collections.md)).
3. Create `server-data/world/<dir>/<prefix>_<id:3>.json` with matching `id`. Minimal valid item: `{ "id": 50, "name": "Test Sword", "type": "WEAPON", "attackDamage": 10 }` in `items/item_050.json`.
4. `.\gradlew.bat :server-app:validateContent` (add `-PcontentArgs="--json"` for machine output); fix every ERROR ([validation rules](/content/validation_rules.md)).
5. A running server reloads on save; on error nothing applies ([hot reload](/content/hot_reload.md)). Create referenced non-map documents first (items before shops, NPCs before the map that spawns them); links between maps may dangle until the target exists ([ADR 0007](/decisions/0007_validate_before_apply.md)).
6. Commit files and note the change; for lasting gameplay knowledge update the wiki ([update the wiki](/playbooks/update_wiki.md)).
