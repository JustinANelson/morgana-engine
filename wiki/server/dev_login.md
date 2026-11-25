---
type: Concept
title: "Dev Login Tickets"
description: "One-time login tickets so automation (an LLM driving a client, a test script) can enter the world without typing a password; opt-in per server."
tags: [security, access, tooling]
code: ["server-app/src/main/kotlin/com/morgana/server/DevTickets.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaWorldCoordinator.kt", "server-app/src/main/kotlin/com/morgana/server/tools/ContentTool.kt", "client-core/src/main/kotlin/com/morgana/client/net/DirectConnect.kt", "client-core/src/main/kotlin/com/morgana/client/MorganaGame.kt"]
symbols: ["DevTickets", "devTickets", "handleResume", "DirectConnect", "directLogin", "--dev-login", "MORGANA_DEV_LOGIN", "dev-ticket"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-25T12:00:00Z }
stale_after: 2027-05-24T00:00:00Z
---

# Dev Login Tickets (`DevTickets`)
Related: [owner policy](/server/owner_policy.md), [reconnect and session resume](/client/reconnect.md), [coordinator](/server/coordinator.md).

The client is a canvas, so a tool that cannot type into it (an LLM, a script) cannot use the password screen, and it should never be handed a real password. A **dev ticket** stands in for the password once.

**Server.** Off by default. Start the server with `--dev-login` (or `MORGANA_DEV_LOGIN=1`) to accept tickets; the startup log then warns that dev login is on. A ticket is redeemed through the normal `c_resume` packet (`handleResume`): a token starting `dev_` is looked up in `<data dir>/dev-tickets/` instead of the resume tokens, the account's character is entered as for any login, and the client then holds a normal resume token. Without `--dev-login` the packet is treated as an ordinary (failing) resume token and tickets stay untouched.

**Minting.** `.\gradlew.bat :server-app:devTicket -PcontentArgs="--user Alice [--ttl 600] [--dir server-data]"` prints the ticket on stdout (the explanation goes to stderr). The account must already exist (register once in the client). Whoever can write the server's data folder can mint, the same trust as editing `owners.txt`; a remote client cannot. A ticket is 192 random bits, lasts 10 minutes by default (max 24 h), works once (deleting its file is the claim), and only its SHA-256 is stored. It grants exactly its account's access level, Owner included. Because a Tailscale Funnel or reverse proxy reaches the server from 127.0.0.1, no loopback check is used: the ticket secrecy is the protection, so leave dev login off on a server that is not being tested.

**Client.** `DirectConnect` reads a launch address or command line and hands the client a saved session to resume at start (`MorganaGame.directLogin`), skipping the realm browser:
* Browser: `?connect=host:port&tls=1&ticket=dev_...` (`connect` defaults to the page's own server, `tls` to the page scheme). The query string is removed from the address bar once read.
* Desktop: `--connect host:port [--tls] [--ticket dev_...]`; the ticket may come from `MORGANA_TICKET` so it stays out of the process list.
* `connect` without a ticket only points the login window at that server.

Characters: an account with no character still lands on the character screen, so create the tester's character once through the UI or the first login. Tests: `DevTicketsTest`, `DevLoginTest`, `DirectConnectTest`.
