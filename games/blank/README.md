# Blank Game

Created with the Morgana ORPG Engine (API Version 1).

## Running This Game

With the Morgana SDK (from the SDK folder; on Windows use the `.bat` launchers), this is the game the server serves when
you give it no `--game`:

```
bin/morgana-server
bin/morgana-client
```

Check the content with `bin/morgana validate --game games/blank`.

From the engine source instead:

```powershell
.\gradlew.bat :server-app:run --args="--game games/blank"
.\gradlew.bat :client-desktop:run
```

## Project Structure

- `game.json` - Defines engine API compatibility, attributes, vitals, leveling progression, and combat formulas.
- `data/` - World content (classes, items, maps, npcs, spells, shops, resources, recipes, quests, events).
- `scripts/` - Lua gameplay scripts, custom slash commands, custom actions, and combat routines.

## Editing Content

You can edit world content directly as JSON files in `data/`, or log in as an administrator in the client and press **F1** to open the live in-game GUI editor.
