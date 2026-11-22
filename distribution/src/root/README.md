# Morgana ORPG SDK @VERSION@

Everything you need to run a Morgana server, play and build in the desktop client, and make your own game, without the
engine's source code or a build tool.

## What you need

- **Java 17 or newer** (a JDK or JRE, for example [Eclipse Temurin 17](https://adoptium.net/)). Set `JAVA_HOME` or put
  `java` on your `PATH`. The launchers stop with a clear message if Java is missing.
- Windows, macOS or Linux. On Windows use the `.bat` launchers; on macOS and Linux use the ones without an extension
  (run `chmod +x bin/*` once if your unzip tool dropped the execute bit).

All launchers switch to this folder before they start, so relative paths such as `games/my-game` or `worlds/my-realm`
always mean folders next to `bin/`, wherever you run them from.

**New here?** [`docs/GETTING_STARTED.md`](docs/GETTING_STARTED.md) walks you through making a small game in about
15 minutes, using only the in-game editors.

## What is inside

| Path | What it is |
| :--- | :--- |
| `bin/morgana-client` | The desktop client: the realm browser, Local Worlds (host a world with one click) and the in-game `F1` editors |
| `bin/morgana-server` | A dedicated server (default port 7001) |
| `bin/morgana` | Command-line tools: create games and realms, validate content, owners, modules, TLS, world packs |
| `bin/morgana-art`, `bin/morgana-tileset` | Export the built-in art as editable PNGs; generate tilesets |
| `games/blank/` | A minimal game (one map, one class, one item, one NPC, one Lua script) to copy or edit |
| `web/` | The browser client, served by `morgana-server --web` (see *Play in a browser or on your phone*) |
| `games/roguelike/` | **Ashen Keep**, the reference co-op dungeon crawler & roguelike: a multiplayer fortress hub and instanced procedural Sunken Crypt floor with action combat, paperdoll gear, and boss encounter |
| `games/tutorial-village/` | The finished game from the getting-started guide: a village, a gardener, a quest and its reward |
| `docs/` | The getting-started guide, the creator guide, the Lua API reference, the `game.json` reference, and JSON Schemas for every content file |
| `lib/` | The engine and its libraries |
| `licenses/` | Licenses of bundled third-party code |

## Quick start: play and build in one window

```
bin\morgana-client.bat          (macOS / Linux: bin/morgana-client)
```

Open **Local Worlds**, press **New Realm Wizard...**, pick **Blank Game**, choose which features to switch off, and
create it. Press **Play**: the client starts a server for that world in the background and logs you in. The first
account made on this machine becomes the realm's **Owner**, so `F1` opens the editors straight away.

## Run a dedicated server

```
bin\morgana-server.bat
```

This serves `games/blank` on port 7001 and keeps accounts and characters in `server-data/`. Connect from the client
with **Direct Join** to `127.0.0.1:7001`. Options: `--port <n>`, `--game <game folder>`, `--data <data folder>`.
Press `Ctrl+C` to stop; everything is saved.

## Create a new server (realm)

A realm is a folder holding its own copy of a game plus its players. Create one, then serve it:

```
bin\morgana.bat world-new --name "My Realm"
bin\morgana-server.bat --data worlds/my-realm
```

- `--template <id>` picks the starting point: `blank` (default) or the id of any game folder under `games/`.
- `--disable trading,guilds` switches optional systems off (written to the realm's `server.json`); see the list with
  `bin\morgana modules --dir worlds/my-realm`.
- `bin\morgana worlds` lists the realms; the client's Local Worlds tab shows the same ones.

On a machine others connect to, name the owner first, since the "first local account" rule only applies to accounts
made on the server's own machine:

```
bin\morgana.bat owner --user Alice --dir worlds/my-realm
```

To let friends join over the internet, forward the port on your router and share your address. For encrypted
connections set `"tls": { "mode": "required" }` in the realm's `server.json` and share the invite code from
`bin\morgana tls --host <your address> --dir worlds/my-realm`.

## Play in a browser or on your phone (for example over Tailscale)

Add `--web` and the server also hands out the browser version of the game:

```
bin\morgana-server.bat --data worlds/my-realm --web
```

The console lists the addresses to open, such as `http://100.101.102.103:7002/` (marked *Tailscale*). With
[Tailscale](https://tailscale.com) on both this computer and your phone, open that address (or
`http://<this computer's Tailscale name>:7002/`) in the phone's browser, tap **Enter Realm**, and play with the
on-screen controls. Tapping a text box (name, password, chat) opens a typing bar at the top of the screen with your
phone's keyboard; on an iPhone, tap the bar's box if the keyboard does not come up by itself. Landscape fits the menus
best.

- The browser uses port 7002 (change it with `--ws-port <n>`); the desktop client keeps using 7001.
- Plain `http://` is accepted for Tailscale addresses and names, home networks and this computer, because the traffic
  is private (Tailscale encrypts it). For anything else use TLS.
- The first time, Windows may ask whether Java may use the network: allow it on private networks.

## Make your own game

```
bin\morgana.bat new-game --name "Shadows of Eldoria" --dir games/eldoria
```

Edit the JSON files in `games/eldoria/data/` (the schemas in `docs/schemas/` describe every field) or log in as Owner
and use the `F1` editors; both go through the same validation. Lua scripts live in `games/eldoria/scripts/`. Check your
work and play it:

```
bin\morgana.bat validate --game games/eldoria
bin\morgana.bat validate-scripts --game games/eldoria
bin\morgana-server.bat --game games/eldoria --data server-data/eldoria
```

Games in `games/` also appear as templates in the New Realm Wizard and in `world-new --template`. Edits to content files
are hot-reloaded while the server runs.

## All tool commands

Run `bin\morgana` with no arguments for the full list. The most used:

| Command | Does |
| :--- | :--- |
| `new-game --name <n> [--dir <dir>]` | Create a game project |
| `world-new --name <n> [--template <id>] [--disable a,b] [--pack <zip>]` | Create a realm under `worlds/` |
| `worlds` | List realms |
| `validate [--game <dir>] [--json]` | Validate a game's content |
| `validate-scripts [--game <dir>]`, `validate-art [--game <dir>]` | Check Lua scripts and art |
| `owner --user <name> [--dir <data dir>]` | Make an account an Owner |
| `modules [--dir <data dir>] [--enable a] [--disable b]` | Show or switch optional systems |
| `pack-export --out <zip> --name <n> [--dir <world dir>]`, `pack-import --file <zip>` | Share content as a world pack |
| `tls [--host <address>] [--dir <data dir>]` | Show the TLS setup and an invite code |

## Troubleshooting

- **"Address already in use"**: another server is on that port; pass `--port 7002`.
- **Nothing happens on macOS**: run `bin/morgana-client` from a terminal to see the log.
- **Logs**: the server logs to the console. Set `MORGANA_JAVA_OPTS` for JVM options such as `-Xmx2g`.
