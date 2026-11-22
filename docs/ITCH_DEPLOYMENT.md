# Distributing the Browser Client to itch.io

This guide covers building, testing, and publishing the `:client-web` TeaVM browser client to itch.io (`https://<your-user>.itch.io/morgana-realms-client`), and exposing a local Morgana server over HTTPS/WSS so browser players can connect.

---

## 1. One-Time Setup

### Install & Authenticate `butler`
1. Download `butler` and add it to your `PATH` (or invoke it from its install folder).
2. Log in once:
   ```powershell
   butler login
   ```
3. Verify access to the project:
   ```powershell
   butler status <your-user>/morgana-realms-client
   ```

### itch.io Project Page Settings
In the itch.io **Edit game** page for `morgana-realms-client`:
- **Kind of project**: `HTML`
- **Embed options**: `"Click to launch in fullscreen"` (or `1280 × 768`), and tick **Mobile friendly** and **Fullscreen button**.
- On the uploaded `html` channel file, ensure **This file will be played in the browser** is checked.

---

## 2. Build & Verify Locally

1. Run the verification gate and package the release zip:
   ```powershell
   .\gradlew.bat check :client-web:dist
   ```
   This builds the minified TeaVM webapp (`client-web/build/dist/js/release/webapp`) and packages it into:
   ```text
   client-web/build/dist/morgana-realms-client-html.zip
   ```
   - `index.html`, `app.js`, `assets/`, and `scripts/` sit at the root of the zip.
   - `WEB-INF/` is excluded; `assets/.keep` is kept because the TeaVM asset preloader requests it.
2. *(Optional)* Test the zip locally before uploading: unzip it to a temporary folder and serve it from any local static HTTP server (opening `index.html` directly from `file://` will not work).

---

## 3. Publish to itch.io

Commit and push your changes first so the `--userversion` tag matches a real commit:

```powershell
$sha = git rev-parse --short HEAD
butler push client-web/build/dist/morgana-realms-client-html.zip <your-user>/morgana-realms-client:html --userversion "0.1.0-$sha"
```

Check the upload/build status:

```powershell
butler status <your-user>/morgana-realms-client:html
```

---

## 4. Connecting to a Local Server (Tailscale Funnel)

Because itch.io serves the client over `https://`, browsers require `wss://` WebSocket connections and `https://` asset fetches (mixed-content `http://` requests are blocked). The browser client automatically resolves `{host}` asset templates to the WebSocket's own scheme and port (`RealmAssetSyncPipeline.resolveClientAssetBaseUrl`), and the server's `/assets/` endpoint sends `Access-Control-Allow-Origin: *`.

1. **Expose port `7002` over HTTPS/WSS with Tailscale Funnel** (runs in the background):
   ```powershell
   tailscale funnel --bg 7002
   ```
   *(If Funnel is not yet enabled on your tailnet, `tailscale funnel` prints an admin-console approval link.)*

2. **Start the server with WebSocket enabled on port `7002`** (building with `:server-app:installDist` avoids holding a Gradle daemon `BUSY`):
   ```powershell
   .\gradlew.bat :server-app:installDist
   $env:MORGANA_WS_PORT="7002"
   .\server-app\build\install\server-app\bin\server-app.bat --game games/roguelike
   ```

3. **Connect from itch.io**:
   Open `https://<your-user>.itch.io/morgana-realms-client` and use **Direct Join** with:
   - **Host**: `<your-node>.<your-tailnet>.ts.net`
   - **Port**: `443`
   - **Encrypted TLS**: checked

4. **Turn Funnel off when done**:
   ```powershell
   tailscale funnel --https=443 off
   ```
