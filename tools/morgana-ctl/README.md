# morgana-ctl: control a running Morgana server from an LLM or script

A command line tool and an [MCP](https://modelcontextprotocol.io) server over the same set of tools. Both call the server's admin REST API ([wiki/server/agent_control.md](../../wiki/server/agent_control.md)); nothing edits game files or the database directly. No dependencies; Node 18 or newer.

## Start the server for it

```powershell
.\gradlew.bat :server-app:run --args="--game games/my-game --ws-port 7002 --agent"
```
`--agent` means `--dev-login` (one-time login tickets) plus `--admin-port 7003` (the admin API on loopback). **Never use it on a public server.** The admin token is written to `<data dir>/admin-token.txt`.

## MCP server (Claude Code, Claude Desktop, any MCP client)

```
claude mcp add morgana -e MORGANA_DATA_DIR=C:/path/to/server-data -- node C:/path/to/tools/morgana-ctl/mcp.mjs
```
The tool reads the token from `MORGANA_DATA_DIR`, so the model never sees it. Other settings: `MORGANA_ADMIN_URL` (default `http://127.0.0.1:7003`), `MORGANA_ADMIN_TOKEN`, `MORGANA_CLIENT_URL`.

The tool to start with is **`provision_tester`**: it creates an account (random password nobody knows) and a character if they are missing, mints a one-time ticket and returns a `loginUrl` for the browser client, which the model opens to be in the world with no password and no UI.

## CLI

```
node cli.mjs --list
node cli.mjs provision_tester --username LlmTester --clientUrl http://127.0.0.1:8088/ --connect 127.0.0.1:7102
node cli.mjs warp_player --name Bolt --mapId 1 --x 46 --y 9
node cli.mjs give_item --name Bolt --itemId 3 --amount 5
node cli.mjs put_content --collection items --id 3 --json @item_003.json
```
`--url`, `--token` and `--data` override the connection settings. Output is JSON on stdout; errors go to stderr with exit code 1.

## Tools

`status`, `list_players`, `list_accounts`, `list_characters`, `create_account`, `set_access` (below Owner), `create_character`, `login_ticket`, `provision_tester`, `warp_player`, `give_item`, `kick_player`, `broadcast`, `reload_content`, `list_content`, `get_content`, `put_content` (validated; a refused edit is rolled back), `audit_log`. Every action is recorded in the audit log under `web-admin`.

Tests: `npm test` (or `node --test test/ctl.test.mjs`).
