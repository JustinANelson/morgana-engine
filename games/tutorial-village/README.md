# Tutorial Village

Created with the Morgana ORPG Engine (API Version 1).

## Running This Game

With the Morgana SDK (from the SDK folder; on Windows use the `.bat` launchers):

```
bin/morgana-server --game games/tutorial-village --data server-data/tutorial-village
bin/morgana-client
```

Check the content with `bin/morgana validate --game games/tutorial-village`.

From the engine source instead:

```powershell
.\gradlew.bat :server-app:run --args="--game games/tutorial-village"
.\gradlew.bat :client-desktop:run
```

## Project Structure

- `game.json` - Defines engine API compatibility, attributes, vitals, leveling progression, and combat formulas.
- `data/` - World content (classes, items, maps, npcs, spells, shops, resources, recipes, quests, events).
- `scripts/` - Lua gameplay scripts, custom slash commands, custom actions, and combat routines.

## Editing Content

You can edit world content directly as JSON files in `data/`, or log in as an administrator in the client and press **F1** to open the live in-game GUI editor.
