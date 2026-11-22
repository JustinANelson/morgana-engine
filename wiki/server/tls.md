---
type: Reference
title: "TLS and Invite Codes"
description: "Encrypted connections: modes, automatic certificates, fingerprint pinning via invite codes, and how clients decide whom to trust."
tags: [tls, security, invites]
code: ["server-app/src/main/kotlin/com/morgana/server/tls/ServerTls.kt", "server-app/src/main/kotlin/com/morgana/server/MorganaNettyServer.kt", "server-persistence/src/main/kotlin/com/morgana/persistence/ServerConfig.kt", "shared-protocol/src/main/kotlin/com/morgana/protocol/tls/ServerIdentity.kt", "shared-protocol/src/main/kotlin/com/morgana/protocol/tls/IdentityTrustManager.kt", "client-net-netty/src/main/kotlin/com/morgana/client/net/MorganaNetClient.kt", "client-core/src/main/kotlin/com/morgana/client/net/ClientTransport.kt", "client-core/src/main/kotlin/com/morgana/client/net/ConnectionPlanner.kt", "client-desktop/src/main/kotlin/com/morgana/client/desktop/DesktopLauncher.kt", "server-app/src/main/kotlin/com/morgana/server/tools/ContentTool.kt"]
symbols: ["TlsMode", "TlsConfig", "ServerTls", "SelfSignedCertificates", "TlsSetupException", "InviteCode", "Fingerprint", "IdentityTrustManager", "KnownHostStore", "FileKnownHosts", "ConnectResult", "ClientSecurity", "ConnectionPlanner", "OptionalSslHandler", "tlsCommand", "inviteCode"]
generated: { by: claude-code/claude-sonnet-5-5, at: 2026-11-07T12:00:00Z }
stale_after: 2027-05-06T00:00:00Z
---

# TLS and Invite Codes

Login passwords and all gameplay traffic can be encrypted with TLS (1.3, falling back to 1.2). TLS wraps the connection *below* the framing and JSON, so the packet formats and `PROTOCOL_VERSION` are unchanged ([wire format](/protocol/wire_format.md)). Decision record: [ADR 0012](/decisions/0012_tls_fingerprint_pinning.md).

## Server modes (`tls.mode` in `server.json`)
| Mode | Behavior | Default for |
| :--- | :--- | :--- |
| `off` | Plain TCP only | dedicated servers (no `tls` section) |
| `optional` | TLS and plain on the same port, detected per connection (Netty `OptionalSslHandler`) | migrating existing clients |
| `required` | TLS only; plain clients are dropped | public servers |
| `remote` | TLS required for everyone except connections from this machine (loopback) | worlds hosted from the desktop client |

The TLS handler is the first handler in the pipeline. The effective mode is `tls.mode` if present, otherwise the caller's default (`MorganaServerRuntime.start(defaultTlsMode = ...)`). The login result adds `[encrypted]` to the welcome message on TLS connections, and the server logs a warning when a non-loopback player logs in unencrypted.

**Fail closed**: a missing/unreadable keystore, wrong password, or unset password variable throws `TlsSetupException` before the port is opened. The server never falls back to plain text.

## Certificates
- **Automatic** (no `keystore` set): the first start creates a self-signed RSA-2048 certificate (10 years, SAN `localhost`, `127.0.0.1`, `::1`, this host name) in `<data dir>/tls/server.p12` (BouncyCastle, `SelfSignedCertificates`). It is reused on every start, so the fingerprint is stable. Deleting the file creates a new identity, and clients will be warned.
- **Operator certificate**: `"tls": { "mode": "required", "keystore": "certs/server.p12", "passwordEnv": "MORGANA_TLS_PASSWORD" }`. The PKCS12 file is relative to the data directory; the password comes from the named environment variable and never lives in a file (a `keystorePassword` field is rejected). Use this for certificates from a certificate authority (for example Let's Encrypt converted to PKCS12), or put a TLS-terminating reverse proxy in front instead.
- `.\gradlew.bat :server-app:tlsInfo -PcontentArgs="--host play.example.com"` prints the mode, fingerprint, expiry and an invite code (creating the certificate if needed).

## Deciding whom to trust (client)
`IdentityTrustManager` (JDK-only, shared with Android) checks, in order:
1. **Invite fingerprint**: if the player used an invite code, the certificate must match exactly, otherwise `InviteMismatchException` (hard refusal).
2. **Remembered fingerprint** for that `host:port` (`KnownHostStore`; the desktop client persists to `client-data/known_hosts.txt`): a different certificate raises `ServerIdentityChangedException`.
3. **Certificate authority**: a certificate the platform trusts, with host name check, is accepted.
4. Otherwise `UnknownServerException` carries the fingerprint so the UI can ask once ("trust this server?").

`MorganaNetClient.connectSecure(host, port, ClientSecurity)` returns a `ConnectResult`: `Connected`, `NeedsTrust`, `IdentityChanged`, `InviteMismatch` or `Failed`. Nothing is sent until the handshake succeeds, and a TLS attempt is **never downgraded** to plain text. Against a server without TLS the client reports "no secure answer" after the 6 s handshake timeout.

## Browser client and cleartext
The browser client refuses `ws://` to any host that is not loopback, a private-network address or `.local` (`TransportPolicy`), because passwords would cross the network in cleartext. Tick the secure (TLS) option to use `wss://`.

## Invite codes
`morgana://host:port#fp=<sha-256 hex>`: the address plus the certificate fingerprint (`InviteCode.format/parse`; bare `host`, `host:port` and `[v6]:port` also parse). The login window's host field accepts a pasted code and fills in everything, forcing TLS with the pinned fingerprint. The desktop host's **Copy Invite Code for Friends** button calls `LocalWorldHost.inviteCode(publicHost)`; with a blank or loopback host it uses the machine's LAN address, so friends outside the local network need the public IP or domain substituted. The code protects against interception only if it reaches the player through a trusted channel.

TLS handshakes start only after the [connection gate](/server/connection_limits.md) admits the peer, and a handshake that takes longer than the pre-login timeout is dropped.

## Login window behavior
TLS is on by default for non-loopback hosts and off for `localhost`/`127.x` (the embedded `remote` mode accepts plain loopback). The first connection to an unknown self-signed server shows the fingerprint with **Trust & Connect** / **Cancel**; an identity change shows a warning window whose default is Cancel.

## Testing
`Phase17TlsEndToEndTest` (real server and client, including a non-loopback connection through the machine's LAN address) covers pinned login, trust-on-first-use, wrong invite, changed identity, `required`/`optional`/`remote` behavior, TLS against a plain server, certificate persistence, operator keystores and fail-closed errors. Unit tests: `Phase17ServerIdentityTest`, `Phase17ConnectionPlannerTest`, `Phase17TlsConfigTest`, `Phase17TlsToolTest`. Manual check: start a server with `"tls": {"mode": "required"}`, run `openssl s_client -connect localhost:7001` (the handshake succeeds and shows the certificate), connect the desktop client with the invite from `tlsInfo`.

## Not done
Android has the shared trust code but no UI for entering invite codes or confirming fingerprints yet. There is no certificate rotation flow or revocation; recreating a certificate warns every returning player. The client does not offer "trust the system store only" or custom roots.
