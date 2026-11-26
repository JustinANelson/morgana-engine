# Load testing with bots

`tools/morgana-ctl/loadtest.mjs` logs N bots into a server with one-time dev tickets, puts them all on one map and has them walk and fight at the rate of a real client (a move every 50 ms, a skill every 350-500 ms, a ping every 2 s). It reports what the server saw (tick time, heap) and what each client received (kilobytes and packets per second by packet type, round trip), so you can see where a crowded fight spends its bandwidth.

```
node tools/morgana-ctl/loadtest.mjs --spawn games/trevena --levels 10,30,60 --seconds 25
node tools/morgana-ctl/loadtest.mjs --ws 127.0.0.1:7002 --admin http://127.0.0.1:7003 --data <server-data> --levels 20
```

* `--spawn <game folder>` starts a **throwaway** server (the installed `server-app`, so run `gradlew :server-app:installDist` first; a copy of the game; a temp data folder; free ports) and removes it afterwards. A real realm is never touched.
* Against a running `--agent` server the bots create accounts named `LoadBot0001`... that stay in its data folder.
* Options: `--levels`, `--seconds`, `--mapId/--x/--y` (where they stand, default Trevena's hunting ground, spread over 7 x 7 tiles: the worst case for broadcast), `--out report.json`, `--json`, `DUMP=1` (print one sample of every packet type).

Numbers from a laptop (60 bots in one 7 x 7 patch, half sword and shield, half archers, all fighting wolves):

| Change | Per client | Server out | Tick avg |
| --- | --- | --- | --- |
| First run | 437 KB/s, 1000 packets/s | 215 Mbit/s | 10 ms |
| + compact monster/player moves, projectile deltas, one move batch per tick at 10 Hz, no resend of server-only changes | 90 KB/s, 175 packets/s | 44 Mbit/s | 5 ms |

A tick has a 50 ms budget, so the simulation is far from the limit; **bandwidth** (players in view of each other squared) is what bounds a crowd. See [protocol: server packets](/protocol/server_packets.md) for `s_npc_pos`, `s_player_pos`, `s_moves` and `s_proj_delta`.

The first run found a real bug: an `aimX` of `NaN` (or a huge number) put a NaN projectile in the world and every later tick failed to encode it. Aim values that are not finite or are absurdly large are now ignored (`FreeAimCombatTest`).
