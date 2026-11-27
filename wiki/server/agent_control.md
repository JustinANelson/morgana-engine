---
type: Concept
title: "Agent Control (admin API for LLMs and scripts)"
description: "How an LLM or script makes accounts and characters, logs in without a password, and changes the live world through the admin API, the morgana-ctl CLI and its MCP server."
tags: [security, admin, tooling, mcp]
code: ["server-app/src/main/kotlin/com/morgana/server/admin/AdminApi.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaServerMain.kt", "tools/morgana-ctl/tools.mjs", "tools/morgana-ctl/mcp.mjs", "tools/morgana-ctl/cli.mjs"]
symbols: ["adminCreateAccount", "adminSetAccess", "adminCreateCharacter", "adminMintTicket", "adminWarp", "adminGive", "adminContentPut", "MAX_CONTENT_BODY", "--agent", "--admin-port", "provision_tester", "put_content", "resolveConfig"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-25T18:00:00Z }
stale_after: 2027-05-24T00:00:00Z
---

# Agent Control
Builds on the [admin web panel and REST API](/server/admin_panel.md) and [dev login tickets](/server/dev_login.md).

An LLM or script cannot type into the canvas client and should never be handed a real password. Three layers give it everything it needs:

1. **Server flags.** `--agent` is `--dev-login` plus `--admin-port 7003` (the admin API on loopback, token in `<data dir>/admin-token.txt`). `--admin-port <n>` alone turns only the admin API on (it otherwise comes from `server.json`). Never on a public server.
2. **Admin API additions** (all need the bearer token, are audited under `web-admin`, and are validated before they touch the world): `GET/POST /api/accounts`, `POST /api/accounts/access`, `GET/POST /api/characters`, `POST /api/tickets`, `POST /api/players/warp`, `POST /api/players/give`, `GET /api/content`, `GET/PUT /api/content/<collection>/<id>`.
3. **`tools/morgana-ctl`**: a Node CLI and an MCP stdio server with one shared tool table (see its README). `provision_tester` creates the account and character if missing, mints a ticket and returns a browser login URL.

## Rules the operations enforce
* **Accounts**: 2 to 20 characters, unique ignoring case. A blank password becomes a random one nobody knows, so the account is enterable only with a ticket. A name in the owner list starts as Owner, as at registration.
* **Access**: `set_access` never grants Owner (only `owners.txt` / `MORGANA_OWNERS` does), never changes an Owner, and refuses while the account is online. The new level is copied to the account's stored characters.
* **Characters**: the character screen's rules (name check, class, per-account limit, name not taken).
* **Warp and give** act on online players only; map, bounds, item and amount (1 to 9999) are checked. They record a replay gap like a content reload.
* **Content PUT** requires a known collection, an integer id equal to the document's own `id` when it has one, and a JSON object. It writes the file, runs the normal reload (validate, hot-swap), and if the document has errors it restores the previous file (or removes the new one) and reloads again, so a bad edit never stays. Bodies are limited to 8 KB everywhere except content documents (8 MB, since a 240 x 240 map is megabytes; read those from disk rather than through `get_content`).
* **Tickets** are minted through the API (`POST /api/tickets`) as well as the `devTicket` Gradle tool; both need dev login on.

Tests: `AdminApiTest`, `AdminOpsTest`, `DevLoginTest`, and `tools/morgana-ctl/test/ctl.test.mjs`.
