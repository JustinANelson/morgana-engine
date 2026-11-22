# Make your first game in 15 minutes

*Building a dungeon crawler? After this guide, read [Build a co-op roguelike in the F1 editor](BUILD_A_ROGUELIKE_IN_F1.md).*

You will build a tiny online game without writing any code: a village, a gardener called Mara who asks for help, a few pests to chase out of her garden, and a charm as the reward. When you finish you will have a game you can play, edit and share with friends.

**What you need**: the Morgana SDK (or this repository) and Java 17 or newer. That is all. Everything below happens inside the game client's own editors.

**Stuck, or want to see the result first?** The finished game is in `games/tutorial-village`. In **New Realm Wizard...** pick *Tutorial Village* instead of *Blank Game* and you can look at, play and take apart exactly what this guide builds.

How to read this guide: **bold** is a button, panel or field you can see on the screen; *italic* is something you choose or type; `code` is a key or a command.

| Step | What you do | Time |
| :--- | :--- | :--- |
| 1 | Create your realm and walk into it | 2 min |
| 2 | Paint the village | 3 min |
| 3 | Make an item | 2 min |
| 4 | Make the pests and Mara, and place them | 3 min |
| 5 | Write the quest | 1 min |
| 6 | Give Mara something to say | 3 min |
| 7 | Play it | 1 min |

## Step 1: Create your realm and walk into it (2 min)

1. Start the desktop client: `bin\morgana-client.bat` in the SDK (macOS and Linux: `bin/morgana-client`), or `.\gradlew.bat :client-desktop:run` from the repository.
2. Open the **Local Worlds** tab.
3. Press **New Realm Wizard...**.
4. Type a name, *Greenhollow*, and pick *Blank Game* as the starting point. Press **Next**.
5. Leave the features as they are for now (you can switch any of them off later) and press **Next**, then **Create Realm**.
6. Select your new realm in the list and press **Play**. The client starts a server for it in the background and signs you in. Make an account when asked.

The first account made on your computer becomes the realm's **Owner**, which is what lets you edit the world while you are standing in it.

Walk around with `W` `A` `S` `D` or the arrow keys. You are in a plain green square with a Town Guide. Now make it yours.

## Step 2: Paint the village (3 min)

Press `F1`. The map editors open: **Tileset** and **Layers** on the left, **Attributes**, **Maps** and **Map properties** on the right. Press `F1` again (or **Close editor**) whenever you want the game back.

1. In **Tileset**, click a picture to pick it as your brush. The first rows hold grass, a crypt floor, a *cobble road*, *deep water*, an *oak tree* and many more.
2. Press `B` for the brush and paint on the map. In **Layers**, choose where the tile goes: floors such as the road and water go on *Ground*; things that stand on the ground, such as trees, go on *Mask 1* (trees have see-through corners, so they need the grass under them).
3. `R` is a rectangle (drag a box), `G` fills an area, `E` erases and `I` copies the tile under your pointer. `Ctrl+Z` undoes, `Ctrl+Y` redoes.
4. Trees and water look solid but are not yet. In **Attributes**, set **What this tile does** to *Blocked* and paint the trees and the pond again. While it says *Blocked*, every tile you paint becomes solid (and erasing makes tiles walkable), so set it back to *None* before painting anything players should walk on.
5. Press `Ctrl+S` (or the save button in the toolbar) to save the map to the server. Close the editor and walk around your village.

The editors show other changes live: everything you save is already in the game for anyone who is playing.

## Step 3: Make an item (2 min)

1. Press `F1`, then **Editors**, then **Items**.
2. Press **New item**.
3. Fill in **Name**: *Gardener's Charm*. Set **Type** to *Amulet*. Pick an **Icon** from the picture grid.
4. Under **Bonuses when worn**, give **+vit** the value *2*.
5. Press **Save**.

## Step 4: Make the pests and Mara, and place them (3 min)

1. In **Editors**, open **NPCs** and press **New NPC**.
2. First the pest. **Name**: *Garden Pest*. **Title above the head**: *Nibbler*. Pick the green slime for **Sprite**. Set **Behaviour** to *Aggressive*, **Max hp** to *20*, **Attack damage** to *2* and **XP reward** to *15*. Press **Save**.
3. Press **New NPC** again for Mara. **Name**: *Mara*. **Title above the head**: *Gardener*. Pick a person for **Sprite**. Set **Behaviour** to *Quest giver*. Press **Save**. (Her **Dialogue event** stays empty until Step 6.)
4. Put them in the world. In **Attributes**, under **NPC spawn**, choose *Garden Pest* in the drop-down. That switches to the spawn tool (`N`): left-click a tile to place a pest, right-click to remove one. Place four or five pests in one corner of the map, which will be the garden.
5. Choose *Mara* in the same drop-down and place her once, near the road.
6. Press `Ctrl+S` to save the map.

The list in **NPCs** shows each creature as a number and a name, such as *2: Garden Pest*. You will need that number in the next step.

## Step 5: Write the quest (1 min)

1. In **Editors**, open **Quests** and press **New quest**.
2. **Name**: *Weed the Garden*. **Summary**: *Chase away three Garden Pests.*
3. In **Objectives**, write one line: `KILL_NPC 2 3 Chase away Garden Pests`. That means: kill, creature number 2 (the Garden Pest), three of them, and this text.
4. Under **Reward**, set **XP** to *100*, **Currency** to *0* and write `2 1` in **Items**: item number 2, one of it. (Item 2 is your Gardener's Charm.)
5. Press **Save**.

## Step 6: Give Mara something to say (3 min)

A conversation is an *event*. It has pages; the last page whose conditions are true is the one the player sees. You will make three pages: one that offers the quest, one for while it is under way, and one for afterwards.

1. In **Editors**, open **Events** and press **New event**. Give it the name *Mara: the garden quest*.
2. **Page 1** (the offer). Press **Add step** and choose *Say something*. Speaker *Mara*, text *Oh, thank goodness you are here! Garden Pests keep nibbling my vegetables. Could you chase three of them away?* Add two answers: *I'll help* and *Not right now*. Under *I'll help*, add the steps *Start a quest* (choose Weed the Garden) and *Say something* (*Wonderful! They are in the garden.*). Under *Not right now*, add *Say something* (*That is all right. Come back if you change your mind.*).
3. Press **Add page**. In **Use this page only when**, set **Quest (0 = no check)** to the quest's number and **The quest must be** to *In progress*. Add the step *If ... otherwise*: check that the quest is *Ready to complete*. Under *Then*, add *Complete a quest* (Weed the Garden) and *Say something* (*You did it! Please take this charm.*). Under *Otherwise*, add *Say something* (*Still some pests left, I am afraid.*).
4. Press **Add page** once more. Set the quest as before and **The quest must be** to *Completed*. Add *Say something*: *The garden has never looked better. Thank you again!*
5. Press **Save event**, then **Test from here** to try the steps right now, as yourself, without touching anything saved.
6. One more link: in **NPCs**, open *Mara*, put the event's number in **Dialogue event**, and press **Save**.

## Step 7: Play it (1 min)

Close the editor and walk up to Mara. Press `E` to talk: she asks for help, and you answer *I'll help*. Open the quest log with `J`: your quest is there, and the tracker on the screen points at the nearest pest. Equip your Bronze Sword (open the bag with `I` and use it), then chase three pests out with `Space`. Go back to Mara, talk again, and the charm is yours.

That is a whole game loop made of nothing but data: a place, creatures, an item, a quest and a conversation.

## Share it

- **Friends on your network or the internet**: open your realm in **Local Worlds** and press **Copy Invite Code**. Friends paste it into **Direct Join**. For friends outside your home network, forward the port on your router (see the SDK `README.md`).
- **In a browser or on a phone**: run `bin\morgana-server.bat --data worlds/greenhollow --web` and open the address it prints.
- **As a package**: `bin\morgana.bat pack-export --out greenhollow.zip --name Greenhollow --dir worlds/greenhollow` makes a file others can import. **Open folder** in **Local Worlds** shows where the realm lives on disk: the world is plain JSON files you can read, edit and keep in version control.

## Where next

- Change the rules of the game itself (stats, health, how damage works) without code: [`GAME_DEFINITION.md`](GAME_DEFINITION.md).
- Add scripted behaviour in Lua, or plugins for anything bigger: [`API_REFERENCE.md`](API_REFERENCE.md) and [`PLUGIN_ARCHITECTURE.md`](PLUGIN_ARCHITECTURE.md).
- Switch whole systems on or off (parties, guilds, mail, a player market), or pick a different kind of game: [`GENRES_AND_MODULES.md`](GENRES_AND_MODULES.md).
- Work from the command line and with the JSON files directly: [`CREATOR_GUIDE.md`](CREATOR_GUIDE.md).
- Make your own art: the **Art** editor imports pictures, and the engine repository's wiki has a playbook for it (`wiki/playbooks/make_game_art.md`).
- See a bigger finished game: the roguelike `games/roguelike` (*Lanternfall*).

## If something goes wrong

- **The editors do not open.** Only Mappers and above can edit. The first account made on the machine that runs the realm is the Owner; on a server others join, name the owner first with `bin\morgana.bat owner --user <name> --dir <data folder>`.
- **A Save is refused.** Every save is checked before it is applied, and the message says exactly what is wrong (a quest that points at a creature that does not exist, for example). Fix it and save again; nothing half-saved is ever applied.
- **Mara does nothing when you talk to her.** Her **Dialogue event** must hold the event's number, and the event needs at least one step on its first page.
- **Fighting a pest is very slow.** Equip your sword first: open the bag with `I` and use the sword.
- **Check everything at once**: `bin\morgana.bat validate --game games/<your game>` lists every problem in a game folder.
