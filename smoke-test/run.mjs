// Headless-browser smoke test for the Morgana web client. See README.md.
//
// Starts a throwaway game server (a temp copy of games/blank, empty save data, free ports), serves the built web client,
// drives headless Chrome through the flows that broke by hand before (startup, login, walking, a window, the F1 editor and
// its default layout, the Editors finder, a saved layout, painting and saving a map), takes a screenshot of each key
// screen, and fails on any step that does not hold or any error in the browser console.
//
// The game draws on one canvas, so the client describes itself through `window.__morganaProbe()` (GameProbe.kt): buttons
// and fields by their text, windows, player position, editor state. The test clicks what the probe reports.

import { chromium } from 'playwright-core';
import { PNG } from 'pngjs';
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import http from 'node:http';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const isWindows = process.platform === 'win32';
const outDir = path.join(root, 'build', 'smoke-test');
const webDir = path.join(root, 'client-web', 'build', 'dist', 'js', 'webapp');
const serverBin = path.join(root, 'server-app', 'build', 'install', 'server-app', 'bin', isWindows ? 'server-app.bat' : 'server-app');
const LOAD_TIMEOUT = Number(process.env.SMOKE_LOAD_TIMEOUT_MS || 360_000); // a few seconds normally; generous for throttled or slow machines
const STEP_TIMEOUT = Number(process.env.SMOKE_STEP_TIMEOUT_MS || 60_000);
const headed = process.env.SMOKE_HEADED === '1';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log('[smoke]', ...a);

// ---------------------------------------------------------------------------------------------------------------- infra

function freePort() {
  return new Promise((resolve, reject) => {
    const s = net.createServer();
    s.on('error', reject);
    s.listen(0, '127.0.0.1', () => { const { port } = s.address(); s.close(() => resolve(port)); });
  });
}

function waitForPort(port, timeoutMs, label) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve, reject) => {
    const attempt = () => {
      const sock = net.connect(port, '127.0.0.1');
      sock.once('connect', () => { sock.destroy(); resolve(); });
      sock.once('error', () => {
        sock.destroy();
        if (Date.now() > deadline) reject(new Error(`${label} did not open port ${port} in ${timeoutMs} ms`));
        else setTimeout(attempt, 300);
      });
    };
    attempt();
  });
}

const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.png': 'image/png', '.wasm': 'application/wasm', '.css': 'text/css', '.glsl': 'text/plain', '.txt': 'text/plain', '.ttf': 'font/ttf' };

function serveStatic(dir, port) {
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://x');
    let file = path.join(dir, decodeURIComponent(url.pathname));
    if (!file.startsWith(dir)) { res.writeHead(403).end(); return; }
    if (fs.existsSync(file) && fs.statSync(file).isDirectory()) file = path.join(file, 'index.html');
    if (!fs.existsSync(file)) { res.writeHead(404).end('not found'); return; }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream', 'Cache-Control': 'no-store' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise((resolve) => server.listen(port, '127.0.0.1', () => resolve(server)));
}

function killTree(child) {
  if (!child || child.exitCode !== null) return;
  try {
    if (isWindows) spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
    else process.kill(-child.pid, 'SIGKILL');
  } catch { /* already gone */ }
}

function findChrome() {
  const candidates = process.env.CHROME_PATH ? [process.env.CHROME_PATH] : isWindows
    ? [process.env.PROGRAMFILES, process.env['PROGRAMFILES(X86)'], process.env.LOCALAPPDATA].filter(Boolean)
        .flatMap((b) => [path.join(b, 'Google', 'Chrome', 'Application', 'chrome.exe'), path.join(b, 'Microsoft', 'Edge', 'Application', 'msedge.exe')])
    : process.platform === 'darwin'
      ? ['/Applications/Google Chrome.app/Contents/MacOS/Google Chrome']
      : ['/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium', '/usr/bin/chromium-browser'];
  return candidates.find((c) => fs.existsSync(c));
}

// ------------------------------------------------------------------------------------------------------------- checks

function distinctColours(png, step = 7) {
  const seen = new Set();
  for (let y = 0; y < png.height; y += step) for (let x = 0; x < png.width; x += step) {
    const i = (png.width * y + x) << 2;
    seen.add((png.data[i] << 16) | (png.data[i + 1] << 8) | png.data[i + 2]);
  }
  return seen.size;
}

// ------------------------------------------------------------------------------------------------------------- run

const results = [];
const browserProblems = [];
const cleanups = [];
let page;
let lastProbe = null;
let viewport = { width: 1280, height: 720 };

async function probe() {
  const text = await page.evaluate(() => (window.__morganaProbe ? window.__morganaProbe() : '{"ready":false}'));
  lastProbe = JSON.parse(text);
  return lastProbe;
}

async function waitFor(what, predicate, timeoutMs = STEP_TIMEOUT) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const p = await probe();
    const found = predicate(p);
    if (found) return p;
    if (Date.now() > deadline) throw new Error(`timed out after ${timeoutMs} ms waiting for ${what}\n  last state: ${summary(p)}`);
    await sleep(250);
  }
}

function summary(p) {
  if (!p.ready) return 'client not ready';
  const buttons = (p.buttons || []).map((b) => b.text).filter(Boolean).slice(0, 12).join(' | ');
  return `inWorld=${p.inWorld} map=${p.mapId} editorOpen=${p.editorOpen} finder=${p.panelFinderOpen} windows=[${(p.windows || []).join(', ')}] buttons=[${buttons}]`;
}

/** Page coordinates for a point the probe reports in the game's own screen pixels. */
function toPage(p, x, y) {
  return { x: (x * viewport.width) / p.screenWidth, y: (y * viewport.height) / p.screenHeight };
}

async function clickAt(p, x, y) {
  const at = toPage(p, x, y);
  await page.mouse.move(at.x, at.y);
  await page.mouse.down();
  await sleep(60);
  await page.mouse.up();
}

async function clickButton(text) {
  const find = (s) => (s.buttons || []).find((b) => b.text === text && !b.disabled);
  let p = await waitFor(`a button "${text}"`, find, STEP_TIMEOUT);
  // A button built this frame reports where it was before the layout ran. Wait until it stops moving, or the click lands elsewhere.
  for (let i = 0; i < 20; i++) {
    await sleep(80);
    const next = await probe();
    const was = find(p);
    const now = find(next);
    p = next;
    if (was && now && was.x === now.x && was.y === now.y) break;
  }
  p = await waitFor(`a button "${text}"`, find, STEP_TIMEOUT);
  const b = find(p);
  await clickAt(p, b.x, b.y);
}

async function clickField(hint) {
  const p = await waitFor(`a field "${hint}"`, (s) => (s.fields || []).some((f) => f.hint === hint), STEP_TIMEOUT);
  const f = p.fields.find((x) => x.hint === hint);
  await clickAt(p, f.x, f.y);
}

async function screenshot(name) {
  const file = path.join(outDir, `${name}.png`);
  const buffer = await page.screenshot({ path: file });
  return { file, png: PNG.sync.read(buffer) };
}

/** What the page's WebGL context is, for a failure message when the canvas stays black. */
async function glInfo() {
  return page.evaluate(() => {
    const c = document.querySelector('canvas');
    if (!c) return 'no canvas';
    const gl = c.getContext('webgl2') || c.getContext('webgl');
    if (!gl) return 'no WebGL context';
    const ext = gl.getExtension('WEBGL_debug_renderer_info');
    return (ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : 'WebGL') + (gl.isContextLost() ? ' (context lost)' : '');
  });
}

/** Screenshots until the canvas has real content (the first frames take a moment), then returns the picture; fails with WebGL details. */
async function screenshotDrawn(name, minColours, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const shot = await screenshot(name);
    const colours = distinctColours(shot.png);
    if (colours >= minColours) return shot;
    if (Date.now() > deadline) throw new Error(`the screen stayed (nearly) blank: ${colours} colours; WebGL: ${await glInfo()}`);
    await sleep(1000);
  }
}

async function step(name, body) {
  const started = Date.now();
  try {
    await body();
    results.push({ name, ok: true, ms: Date.now() - started });
    log(`ok    ${name} (${((Date.now() - started) / 1000).toFixed(1)} s)`);
    return true;
  } catch (e) {
    results.push({ name, ok: false, ms: Date.now() - started, error: String(e.message || e) });
    log(`FAIL  ${name}\n      ${String(e.message || e).replace(/\n/g, '\n      ')}`);
    try { await screenshot(`FAILED-${name.replace(/[^a-z0-9]+/gi, '-')}`); } catch { /* the page may be gone */ }
    return false;
  }
}

async function main() {
  fs.rmSync(outDir, { recursive: true, force: true });
  fs.mkdirSync(outDir, { recursive: true });
  for (const [what, p] of [['the web client (run :client-web:gdx_teavm_web_js_build)', path.join(webDir, 'index.html')], ['the server (run :server-app:installDist)', serverBin]]) {
    if (!fs.existsSync(p)) throw new Error(`Missing ${what}: ${p}`);
  }
  const chrome = findChrome();
  if (!chrome) throw new Error('No Chrome, Edge or Chromium found; set CHROME_PATH');

  // --- a throwaway realm: games/blank copied to a temp folder, empty save data, free ports
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'morgana-smoke-'));
  cleanups.push(() => fs.rmSync(temp, { recursive: true, force: true }));
  fs.cpSync(path.join(root, 'games', 'blank'), path.join(temp, 'game'), { recursive: true });
  fs.mkdirSync(path.join(temp, 'data'));
  // Give the first event an If step, so the browser client has to load and draw one (the Events panel step looks for it).
  const eventFile = path.join(temp, 'game', 'data', 'events', 'event_001.json');
  fs.mkdirSync(path.dirname(eventFile), { recursive: true });
  // The blank game has no events, so the smoke test adds one on map 1 for the Events panel to show.
  const firstEvent = fs.existsSync(eventFile)
    ? JSON.parse(fs.readFileSync(eventFile, 'utf8'))
    : { id: 1, name: 'Smoke Sign', mapId: 1, tileX: 7, tileY: 7, pages: [{ trigger: 'ACTION_INTERACT', commands: [] }] };
  const itemsDir = path.join(temp, 'game', 'data', 'items');
  const anItem = JSON.parse(fs.readFileSync(path.join(itemsDir, fs.readdirSync(itemsDir).sort()[0]), 'utf8'));
  firstEvent.pages[0].commands.unshift({
    _type: 'if', condition: { type: 'HAS_ITEM', targetId: anItem.id, value: 2 },
    then: [{ _type: 'message', text: 'Your bag is not empty.' }], otherwise: [{ _type: 'set_variable', variableId: 'smoke', value: 1 }]
  });
  fs.writeFileSync(eventFile, JSON.stringify(firstEvent, null, 2));
  // The example plugin shows a window on login (plugin UI end to end); built by :plugin-example:jar.
  const pluginJar = path.join(root, 'plugin-example', 'build', 'libs', 'hello-plugin.jar');
  if (!fs.existsSync(pluginJar)) throw new Error(`Missing the example plugin (run :plugin-example:jar): ${pluginJar}`);
  fs.mkdirSync(path.join(temp, 'data', 'plugins'));
  fs.copyFileSync(pluginJar, path.join(temp, 'data', 'plugins', 'hello-plugin.jar'));
  const [tcpPort, wsPort, webPort] = [await freePort(), await freePort(), await freePort()];

  const serverLog = fs.openSync(path.join(outDir, 'server.log'), 'w');
  const server = spawn(isWindows ? 'cmd' : serverBin, isWindows ? ['/c', serverBin, '--port', String(tcpPort), '--game', path.join(temp, 'game'), '--data', path.join(temp, 'data')]
    : ['--port', String(tcpPort), '--game', path.join(temp, 'game'), '--data', path.join(temp, 'data')],
  { cwd: root, env: { ...process.env, MORGANA_WS_PORT: String(wsPort) }, stdio: ['ignore', serverLog, serverLog], detached: !isWindows });
  cleanups.push(() => killTree(server));
  server.on('exit', (code) => log(`server exited with code ${code}`));
  log(`server on tcp ${tcpPort}, websocket ${wsPort}; client on http ${webPort}`);
  await waitForPort(wsPort, 120_000, 'the server');
  const web = await serveStatic(webDir, webPort);
  cleanups.push(() => web.close());

  // --- the browser
  const browser = await chromium.launch({
    executablePath: chrome,
    headless: !headed,
    args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--autoplay-policy=no-user-gesture-required', '--mute-audio']
  });
  cleanups.push(() => browser.close());
  const context = await browser.newContext({ viewport, deviceScaleFactor: 1 });
  page = await context.newPage();
  page.on('pageerror', (e) => browserProblems.push(`page error: ${e.message}`));
  if (process.env.SMOKE_CONSOLE === '1') page.on('console', (m) => log('console', m.type(), m.text().slice(0, 300)));
  // TeaVM logs a Java exception that nothing caught as an ordinary log line, so look for those too.
  page.on('console', (m) => { if (m.type() !== 'error' && /^(JavaError|%cException)/.test(m.text())) browserProblems.push(`uncaught Java exception: ${m.text().slice(0, 200)}`); });
  page.on('console', (m) => { if (m.type() === 'error' && !m.location().url.includes('favicon')) browserProblems.push(`console error: ${m.text()} ${m.location().url}`); });
  page.on('requestfailed', (r) => { if (!r.url().includes('favicon')) browserProblems.push(`request failed: ${r.url()} (${r.failure()?.errorText})`); });
  page.on('response', (r) => { if (r.status() >= 400 && !r.url().includes('favicon')) browserProblems.push(`HTTP ${r.status()}: ${r.url()}`); });

  let start = null;
  let ok = await step('the client loads and shows the login screen', async () => {
    await page.goto(`http://127.0.0.1:${webPort}/?port=${wsPort}`);
    await waitFor('the login screen', (p) => p.ready && (p.buttons || []).some((b) => b.text === 'Enter Realm'), LOAD_TIMEOUT);
    await screenshotDrawn('01-login', 8);
  });

  ok = ok && await step('the realm browser narrows its list as you type and keeps your text', async () => {
    const hint = 'Search realms by name, genre, or tag...';
    const chips = (p) => (p.buttons || []).map((b) => b.text);
    // The bundled directory has one realm, "Local Server", with no tags. A search that matches nothing empties the list
    // (and offers "Clear filters"); deleting the text brings the realm back.
    await waitFor('the realm list', (p) => chips(p).includes('Enter Realm') && chips(p).includes('Hide full'));
    await clickField(hint);
    await page.keyboard.type('zzqx', { delay: 50 });
    const narrowed = await waitFor('the list to follow the search', (p) => chips(p).includes('Clear filters'));
    const field = narrowed.fields.find((f) => f.hint === hint);
    if (!field || field.text !== 'zzqx') throw new Error('the search text was lost: ' + JSON.stringify(field));
    await screenshot('01b-realm-search');
    for (let i = 0; i < 4; i++) await page.keyboard.press('Backspace');
    await waitFor('the full list to come back', (p) => !chips(p).includes('Clear filters'));
    // The arrow keys select a realm (its details pane, with Add to favorites, appears); selecting one points the host at it,
    // so start over from a fresh page afterwards.
    await page.keyboard.type('local', { delay: 50 });
    await waitFor('the search to apply', (p) => !chips(p).includes('Clear filters'));
    await page.keyboard.press('ArrowDown');
    await waitFor('the details of the arrow-selected realm', (p) => chips(p).includes('Add to favorites'));
    await page.goto(`http://127.0.0.1:${webPort}/?port=${wsPort}`);
    await waitFor('the login screen again', (p) => p.ready && chips(p).includes('Enter Realm') && !chips(p).includes('Add to favorites'), LOAD_TIMEOUT);
  });

  ok = ok && await step('pressing Enter in the sign-in form logs in and puts the player in the world', async () => {
    await clickField('');
    await page.keyboard.press('Enter');
    // A new account lands on the character screen ("Make your first character"): name one and play it.
    const first = await waitFor('the world or the first-character screen', (p) => p.inWorld || (p.buttons || []).some((b) => b.text === 'Back to realms'), 60_000);
    if (!first.inWorld) {
      await clickField('Character name');
      await page.keyboard.type('Hero', { delay: 40 });
      await clickButton('Create');
    }
    start = await waitFor('the player in the world', (p) => p.inWorld, 90_000);
    await screenshotDrawn('02-in-world', 60);
  });

  ok = ok && await step('a server plugin shows its own window, a button works, and the server can close it', async () => {
    const p = await waitFor('the plugin window', (s) => (s.windows || []).some((w) => w.startsWith('Welcome,')), 30_000);
    await screenshot('02b-plugin-window');
    await clickField('Leave a note');
    await page.keyboard.type('hello there', { delay: 40 });
    await clickButton('Wave');
    await waitFor('the plugin to answer with the typed note', (s) => (s.chat || []).some((c) => c.includes('You wave and say: hello there')), 15_000);
    const after = await probe();
    if (!(after.windows || []).some((w) => w.startsWith('Welcome,'))) throw new Error('the plugin window vanished after Wave; it should stay and update');
    await clickButton('Goodbye');
    await waitFor('the plugin window to close', (s) => !(s.windows || []).some((w) => w.startsWith('Welcome,')), 15_000);
  });

  ok = ok && await step('the player can walk', async () => {
    const before = await probe();
    let moved = false;
    for (const key of ['d', 's', 'a', 'w']) {
      await page.keyboard.down(key);
      await sleep(900);
      await page.keyboard.up(key);
      await sleep(300);
      const after = await probe();
      if (Math.abs(after.playerPixelX - before.playerPixelX) >= 8 || Math.abs(after.playerPixelY - before.playerPixelY) >= 8) { moved = true; break; }
    }
    if (!moved) throw new Error(`the player did not move from (${before.playerPixelX}, ${before.playerPixelY}) with any direction key`);
  });

  ok = ok && await step('a game window opens and closes', async () => {
    const before = (await probe()).windows.length;
    await page.keyboard.press('i');
    const opened = await waitFor('the inventory window', (p) => p.windows.length > before, 15_000);
    await screenshot('03-inventory');
    await page.keyboard.press('i');
    await waitFor('the inventory window to close', (p) => p.windows.length <= before, 15_000);
    if (opened.windows.length <= before) throw new Error('no window appeared');
  });

  ok = ok && await step('the character menu opens on Inventory, shows the bag, sorts it and switches tabs in place', async () => {
    await clickButton('Inv (I)');
    const p = await waitFor('the character menu', (s) => s.windows.includes('Character'), 15_000);
    for (const label of ['Stats', 'Equipment', 'Inventory', 'All', 'Gear', 'Use', 'Materials', 'Other', 'Sort']) {
      if (!p.buttons.some((b) => b.text === label)) throw new Error(`the character menu has no "${label}" button on its Inventory tab; buttons: ` + p.buttons.map((b) => b.text).join(' | '));
    }
    await screenshot('03b-inventory');
    await clickButton('Sort');
    await sleep(500);
    await clickButton('Equipment'); // a tab of the same window, not another window
    await waitFor('the Equipment tab', (s) => s.windows.includes('Character') && !s.buttons.some((b) => b.text === 'Sort'), 15_000);
    await sleep(500);
    await screenshot('03c-character-tab');
    await clickButton('Inventory'); // and back
    await waitFor('the Inventory tab', (s) => s.buttons.some((b) => b.text === 'Sort'), 15_000);
    await page.keyboard.press('i'); // the I key closes the menu while it is on Inventory
    await waitFor('the character menu to close', (s) => !s.windows.includes('Character'), 15_000);
  });

  ok = ok && await step('H opens the Controls window, which lists every key, rebinds actions and quickbar slots, and resets them', async () => {
    await page.keyboard.press('h');
    await waitFor('the Controls window', (s) => s.windows.includes('Controls'), 15_000);
    await screenshot('03e-controls');
    // Pick up defaults to G: click its key button, press B, and the button now reads B.
    let p = await waitFor('the key buttons', (s) => s.buttons.some((b) => b.text === 'G'), 10_000);
    await clickButton('G');
    p = await waitFor('the capture prompt', (s) => s.buttons.some((b) => b.text === 'Press a key...'), 10_000);
    await page.keyboard.press('b');
    p = await waitFor('the new key', (s) => s.buttons.some((b) => b.text === 'B') && !s.buttons.some((b) => b.text === 'Press a key...'), 10_000);
    if (p.buttons.some((b) => b.text === 'G')) throw new Error('G is still shown after rebinding Pick up to B');
    // Quickbar slot 1 defaults to the 1 key: move it to Z. (This character's quickbar is empty, so its window is hidden.)
    await clickButton('1');
    await waitFor('the capture prompt for quickbar 1', (s) => s.buttons.some((b) => b.text === 'Press a key...'), 10_000);
    await page.keyboard.press('z');
    p = await waitFor('quickbar 1 on Z', (s) => s.buttons.some((b) => b.text === 'Z') && !s.buttons.some((b) => b.text === 'Press a key...'), 10_000);
    if (p.buttons.some((b) => b.text === '1')) throw new Error('quickbar 1 still shows the 1 key after moving it to Z');
    await screenshot('03g-rebound');
    await clickButton('Reset all keys');
    await waitFor('the standard keys', (s) => s.buttons.some((b) => b.text === 'G') && s.buttons.some((b) => b.text === '1') && !s.buttons.some((b) => b.text === 'Z'), 10_000);
    await page.keyboard.press('h');
    await waitFor('the Controls window to close', (s) => !s.windows.includes('Controls'), 10_000);
  });

  ok = ok && await step('the Accessibility window opens and a colour set can be chosen', async () => {
    await clickButton('Settings');
    await waitFor('the Settings window', (s) => s.windows.includes('Settings'), 15_000);
    await clickButton('Accessibility...');
    await waitFor('the Accessibility window', (s) => s.windows.includes('Accessibility'), 15_000);
    await screenshot('03f-accessibility');
    // The colour set button shows the current set, a click moves to the next.
    await clickButton('Standard');
    await waitFor('the red-green set', (s) => s.buttons.some((b) => b.text === 'Red-green safe'), 10_000);
    await screenshot('03h-red-green');
    await clickButton('Red-green safe');
    await clickButton('Blue-yellow safe');
    await clickButton('High contrast');
    await waitFor('the standard set again', (s) => s.buttons.some((b) => b.text === 'Standard'), 10_000);
    // Close both windows again through the buttons that opened them.
    await clickButton('Close');
    await waitFor('the Accessibility window to close', (s) => !s.windows.includes('Accessibility'), 10_000);
    await clickButton('Settings');
    await waitFor('the Settings window to close', (s) => !s.windows.includes('Settings'), 10_000);
  });

  ok = ok && await step('the world map opens with N, shows what the player has explored, and closes again', async () => {
    await sleep(1500); // exploration is sent a moment after the player stands somewhere
    await page.keyboard.press('n');
    await waitFor('the World Map window', (s) => s.windows.includes('World Map'), 15_000);
    await sleep(500);
    await screenshot('03d-world-map');
    await page.keyboard.press('n');
    await waitFor('the World Map window to close', (s) => !s.windows.includes('World Map'), 15_000);
  });

  ok = ok && await step('F1 opens the editor with the map editors', async () => {
    await page.keyboard.press('F1');
    const p = await waitFor('the editor', (s) => s.editorOpen && s.panels.length > 0, 20_000);
    const state = Object.fromEntries(p.panels.map((x) => [x.id, x.state]));
    const shown = Object.entries(state).filter(([, s]) => s === 'SHOWN').map(([id]) => id).sort();
    if (shown.join(',') !== 'attributes,layers,mapprops,maps,tileset') throw new Error(`expected the five map editors at first, saw: ${shown.join(', ')}`);
    if (p.windows.includes('Chat & Command Console')) throw new Error('the game chat window should step aside while editing');
    if (!p.buttons.some((b) => b.text === 'Close editor')) throw new Error('there is no Close editor button');
    await screenshot('04-editor');
  });

  ok = ok && await step('the Editors finder opens an editor by name', async () => {
    await clickButton('Editors');
    await waitFor('the finder', (p) => p.panelFinderOpen, 15_000);
    await sleep(300);
    await page.keyboard.type('quest', { delay: 60 });
    await sleep(300);
    await screenshot('05-finder');
    await page.keyboard.press('Enter');
    const p = await waitFor('the Quests panel', (s) => s.panels.some((x) => x.id === 'quests' && x.state === 'SHOWN'), 15_000);
    if (p.panelFinderOpen) throw new Error('the finder stayed open');
    if (p.windows.some((w) => /party|minimap/i.test(w))) throw new Error('typing in the finder reached the game: ' + p.windows.join(', '));
    await screenshot('06-quests-panel');
  });

  ok = ok && await step('typing in a text field does not trigger editor shortcuts', async () => {
    // E selects the eraser and P the Panels finder; typed into a field they must only be text.
    const before = await probe();
    if (before.editorTool !== 'BRUSH') throw new Error(`expected the brush at first, found ${before.editorTool}`);
    await clickField('Search');
    await page.keyboard.type('epbr', { delay: 60 });
    const after = await waitFor('the typed text', (s) => (s.fields || []).some((f) => f.hint === 'Search' && f.text === 'epbr'), 10_000);
    if (after.editorTool !== 'BRUSH') throw new Error(`typing changed the tool to ${after.editorTool}`);
    if (after.panelFinderOpen || after.windows.some((w) => /party|minimap/i.test(w))) throw new Error('typing opened something: ' + after.windows.join(', '));
  });

  ok = ok && await step('a layout can be saved and is remembered', async () => {
    await clickButton('Editors');
    await waitFor('the finder', (p) => p.panelFinderOpen, 15_000);
    await clickField('Name this layout');
    await page.keyboard.type('Smoke layout', { delay: 40 });
    await clickButton('Save current layout');
    await sleep(500);
    const stored = await page.evaluate(() => Object.keys(localStorage).some((k) => k.includes('Smoke layout') || (localStorage.getItem(k) || '').includes('Smoke layout')));
    if (!stored) throw new Error('the saved layout was not written to the browser storage');
    await screenshot('07-layout-saved');
    await page.keyboard.press('Escape');
    await waitFor('the finder to close', (p) => !p.panelFinderOpen, 10_000);
  });

  ok = ok && await step('the Map editing preset shows the map editors, and the finder can be dragged', async () => {
    await clickButton('Editors');
    const before = await waitFor('the finder', (p) => p.panelFinderOpen, 15_000);
    await clickButton('Map editing');
    const p = await waitFor('the map editors', (s) => ['tileset', 'layers', 'attributes', 'maps', 'mapprops'].every((id) => s.panels.some((x) => x.id === id && x.state === 'SHOWN')), 10_000);
    await screenshot('06b-map-editing-preset');
    // Drag the finder by its title: the Save current layout button moves with it.
    const save = (s) => s.buttons.find((b) => b.text === 'Save current layout');
    const start = save(p) || save(before);
    // The finder opens at the top right (8 px from the right edge, 48 px from the top); its title row is the first 30 px.
    const grab = toPage(p, p.screenWidth - 488 + 120, 48 + 18);
    await page.mouse.move(grab.x, grab.y);
    await page.mouse.down();
    for (let i = 1; i <= 10; i++) { await page.mouse.move(grab.x - 30 * i, grab.y + 10 * i); await sleep(30); }
    await page.mouse.up();
    const moved = await waitFor('the finder to move', (s) => { const b = save(s); return b && b.x < start.x - 150; }, 10_000);
    if (!moved.panelFinderOpen) throw new Error('dragging closed the finder');
    await screenshot('06c-finder-moved');
    await page.keyboard.press('Escape');
    await waitFor('the finder to close', (s) => !s.panelFinderOpen, 10_000);
  });

  ok = ok && await step('editing the map marks it changed and Ctrl+S saves it', async () => {
    // The eraser always changes a map that has ground (the brush's first tile may already be what is painted there).
    await page.keyboard.press('e');
    await waitFor('the eraser', (s) => s.editorTool === 'ERASER', 10_000);
    const p = await probe();
    const cx = p.screenWidth / 2;
    const cy = p.screenHeight / 2;
    // Just right of centre: the left is covered by the Quests editor opened earlier, the far right by the docked map editors.
    const from = toPage(p, cx + 70, cy);
    const to = toPage(p, cx + 220, cy);
    await page.mouse.move(from.x, from.y);
    await page.mouse.down();
    for (let i = 1; i <= 12; i++) { await page.mouse.move(from.x + ((to.x - from.x) * i) / 12, from.y); await sleep(40); }
    await page.mouse.up();
    const dirty = await waitFor('the map to be marked changed', (s) => s.editorDirty && s.editorUndoDepth >= 1, 15_000);
    await screenshot('08-painted');
    await page.keyboard.down('Control');
    await page.keyboard.press('s');
    await page.keyboard.up('Control');
    await waitFor('the map to save', (s) => !s.editorDirty, 20_000);
    await page.keyboard.press('b');
    await waitFor('the brush again', (s) => s.editorTool === 'BRUSH', 10_000);
    if (!dirty.editorDirty) throw new Error('unreachable');
    await screenshot('09-saved');
  });

  ok = ok && await step('the Events panel builds an event with a spoken line and an answer, and saves it', async () => {
    await clickButton('Editors');
    await waitFor('the finder', (p) => p.panelFinderOpen, 15_000);
    await sleep(300);
    await page.keyboard.type('events', { delay: 60 });
    await page.keyboard.press('Enter');
    await waitFor('the Events panel', (s) => s.panels.some((x) => x.id === 'events' && x.state === 'SHOWN'), 15_000);
    const shown = await waitFor('the If step of the first event', (s) => s.buttons.some((b) => b.text.startsWith('1. If the player carries 2 x ')), 10_000);
    for (const row of ['Then:', 'Otherwise:', '1. Chat message: "Your bag is not empty."', '1. Set smoke to 1']) {
      if (!shown.buttons.some((b) => b.text === row)) throw new Error(`the If step is missing the row "${row}"; rows: ` + shown.buttons.map((b) => b.text).filter((t) => /^\d+\. |:$/.test(t)).join(' | '));
    }
    await sleep(400);
    await screenshot('09a-events-shipped');
    await clickButton('New event');
    const line = '1. Say: "Hello!"';
    await waitFor('the starter step', (s) => s.buttons.some((b) => b.text === line), 10_000);
    await clickButton(line);
    await clickButton('Add answer');
    await waitFor('the answer row', (s) => s.buttons.some((b) => b.text === 'If the player answers "Yes":'), 10_000);
    await clickButton('Add step'); // goes under the selected answer
    await waitFor('the nested step', (s) => s.buttons.filter((b) => b.text === line).length === 2, 10_000);
    await screenshot('09b-events-panel');
    await clickButton('Save event');
    const dir = path.join(temp, 'game', 'data', 'events');
    const deadline = Date.now() + 15_000;
    for (;;) {
      const saved = fs.readdirSync(dir).map((f) => fs.readFileSync(path.join(dir, f), 'utf8')).find((t) => t.includes('New event'));
      if (saved) {
        if (!saved.includes('"Yes"') || (saved.match(/Hello!/g) || []).length !== 2) throw new Error('the saved event is not what was built: ' + saved.slice(0, 400));
        break;
      }
      if (Date.now() > deadline) throw new Error('the event was not written to the game folder; files: ' + fs.readdirSync(dir).join(', '));
      await sleep(300);
    }
    await waitFor('Save to go quiet once the server confirms', (s) => s.buttons.some((b) => b.text === 'Save event' && b.disabled), 10_000);
  });

  ok = ok && await step('Settings switches the editor to another colour scheme and remembers it', async () => {
    await clickButton('Editors');
    await waitFor('the finder', (p) => p.panelFinderOpen, 15_000);
    await sleep(300);
    await page.keyboard.type('settings', { delay: 60 });
    await page.keyboard.press('Enter');
    await waitFor('the Settings editor', (s) => s.panels.some((x) => x.id === 'settings' && x.state === 'SHOWN'), 15_000);
    // The schemes are listed in order with Ember (the default) in use, so the first "Use" button is Parchment's.
    await clickButton('Use');
    await waitFor('the editor rebuilt with Settings open again', (s) => s.editorOpen && s.panels.some((x) => x.id === 'settings' && x.state === 'SHOWN'), 15_000);
    await sleep(500);
    const stored = await page.evaluate(() => Object.keys(localStorage).some((k) => (localStorage.getItem(k) || '').includes('parchment') || k.includes('parchment')));
    if (!stored) throw new Error('the chosen colour scheme was not written to the browser storage');
    await screenshot('09c-parchment-scheme');
  });

  ok = ok && await step('Close editor returns to the game', async () => {
    await clickButton('Close editor');
    const p = await waitFor('the editor to close', (s) => !s.editorOpen, 15_000);
    await waitFor('the game chat box back', (s) => s.windows.includes('Chat & Command Console') || (s.buttons || []).some((b) => b.text === 'All'), 10_000);
    if (!p.inWorld) throw new Error('left the world');
    await screenshotDrawn('10-back-in-game', 60);
  });

  ok = ok && await step('signing in again shows the character list; a second character can be made and played', async () => {
    await clickButton('Settings');
    await clickButton('Log out');
    await waitFor('the login screen', (p) => (p.buttons || []).some((b) => b.text === 'Enter Realm'), 20_000);
    await clickField('');
    await page.keyboard.press('Enter');
    const list = await waitFor('the character list', (p) => (p.buttons || []).some((b) => b.text === 'Back to realms'), 30_000);
    if (!list.buttons.some((b) => /^Play /.test(b.text))) throw new Error('the list has no Play button: ' + list.buttons.map((b) => b.text).join(' | '));
    if (list.inWorld) throw new Error('the player was put in the world before choosing');
    await screenshot('12-character-list');
    await clickField('Character name');
    await page.keyboard.type('Second', { delay: 40 });
    await clickButton('Create');
    await waitFor('the second character in the world', (p) => p.inWorld, 60_000);
    await screenshotDrawn('13-second-character', 60);
  });

  ok = ok && await step('the page is installable (manifest, icons, service worker) and the browser going offline makes the client reconnect', async () => {
    const pwa = await page.evaluate(async () => {
      let registrations = -1;
      if ('serviceWorker' in navigator) {
        for (let i = 0; i < 20 && registrations < 1; i++) {
          registrations = (await navigator.serviceWorker.getRegistrations()).length;
          if (registrations < 1) await new Promise((r) => setTimeout(r, 250));
        }
      }
      return {
        manifestLink: !!document.querySelector('link[rel=manifest]'),
        themeColour: !!document.querySelector('meta[name=theme-color]'),
        manifestFile: (await fetch('manifest.webmanifest')).ok,
        icon: (await fetch('icons/icon-192.png')).ok,
        workerFile: (await fetch('sw.js')).ok,
        registrations
      };
    });
    for (const [key, value] of Object.entries(pwa)) {
      if (key === 'registrations' ? value < 1 : !value) throw new Error(`installable-app check failed: ${JSON.stringify(pwa)}`);
    }
    // Count new sockets, then tell the page the network went away: the client must drop and re-open its connection at once.
    await page.evaluate(() => {
      window.__sockets = 0;
      const Original = window.WebSocket;
      window.WebSocket = function (...args) { window.__sockets++; return new Original(...args); };
      window.WebSocket.prototype = Original.prototype;
      Object.assign(window.WebSocket, { CONNECTING: 0, OPEN: 1, CLOSING: 2, CLOSED: 3 });
      window.dispatchEvent(new Event('offline'));
    });
    const deadline = Date.now() + 20_000;
    while (Date.now() < deadline && (await page.evaluate(() => window.__sockets)) < 1) await sleep(250);
    if ((await page.evaluate(() => window.__sockets)) < 1) throw new Error('the client did not open a new connection after the offline event');
    await sleep(3000);
    const after = await waitFor('the player still in the world after reconnecting', (p) => p.inWorld, 20_000);
    if ((after.buttons || []).some((b) => b.text === 'Enter Realm')) throw new Error('the client fell back to the login screen instead of resuming');
  });

  ok = ok && await step('on a phone, tapping a text field opens a typing bar with the phone keyboard, and logs in', async () => {
    // A separate touch-screen page the size of a phone (Chrome's mobile emulation: touch events, no mouse).
    const phoneViewport = { width: 390, height: 844 };
    const phone = await browser.newContext({ viewport: phoneViewport, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
    const desktopPage = page;
    page = await phone.newPage();
    viewport = phoneViewport;
    page.on('pageerror', (e) => browserProblems.push(`phone page error: ${e.message} ${(e.stack || '').split('\n').slice(0, 6).join(' | ')}`));
    try {
      await page.goto(`http://127.0.0.1:${webPort}/?port=${wsPort}`);
      const p = await waitFor('the login screen on the phone', (s) => s.ready && (s.buttons || []).some((b) => b.text === 'Enter Realm'), LOAD_TIMEOUT);
      const field = (p.fields || []).find((f) => f.hint === '');
      if (!field) throw new Error('no name field on the login screen: ' + JSON.stringify(p.fields));
      const at = toPage(p, field.x, field.y);
      await page.touchscreen.tap(at.x, at.y);
      await sleep(1000);
      await screenshot('14a-phone-after-tap');
      await page.waitForSelector('#morgana-text-input', { state: 'visible', timeout: 10_000 });
      await screenshot('14-phone-typing-bar');
      // Replace whatever the field held with a new name, as a phone keyboard would.
      await page.fill('#morgana-text-input', '');
      await page.type('#morgana-text-input', 'PhoneDelver', { delay: 30 });
      await waitFor('the game field to follow the typing bar', (s) => (s.fields || []).some((f) => f.text === 'PhoneDelver'), 10_000);
      await page.press('#morgana-text-input', 'Enter');
      const inside = await waitFor('the phone player in the world (or at the character list)', (s) => s.inWorld || (s.buttons || []).some((b) => b.text === 'Back to realms'), 30_000);
      if (!inside.inWorld) {
        const play = inside.buttons.find((b) => /^Play /.test(b.text));
        if (play) {
          const t = toPage(inside, play.x, play.y);
          await page.touchscreen.tap(t.x, t.y);
        } else {
          // A new account has no character yet: name one with the typing bar, then tap Create.
          const nameField = (inside.fields || []).find((f) => f.hint === 'Character name');
          if (!nameField) throw new Error('no character name field: ' + JSON.stringify(inside.fields));
          const n = toPage(inside, nameField.x, nameField.y);
          await page.touchscreen.tap(n.x, n.y);
          await page.waitForSelector('#morgana-text-input', { state: 'visible', timeout: 10_000 });
          await page.fill('#morgana-text-input', 'PhoneHero');
          await page.click('#morgana-text-done');
          const after = await waitFor('the character screen after the typing bar closed', (s) => (s.buttons || []).some((b) => b.text === 'Create'), 10_000);
          const create = after.buttons.find((b) => b.text === 'Create');
          const c = toPage(after, create.x, create.y);
          await page.touchscreen.tap(c.x, c.y);
        }
        await waitFor('the phone player in the world', (s) => s.inWorld, 30_000);
      }
      await page.waitForSelector('#morgana-text-input', { state: 'detached', timeout: 10_000 });
      await sleep(800);
      await screenshotDrawn('15-phone-in-world', 30);
    } finally {
      // gdx-teavm's own visibilitychange handler throws when a page is torn down; that is not the game's doing.
      page.removeAllListeners('pageerror');
      await phone.close();
      page = desktopPage;
      viewport = { width: 1280, height: 720 };
    }
  });

  await step('the browser console stayed clean', async () => {
    if (browserProblems.length) throw new Error(`${browserProblems.length} problem(s):\n  ${[...new Set(browserProblems)].slice(0, 10).join('\n  ')}`);
  });
}

let fatal = null;
try { await main(); } catch (e) { fatal = e; log('could not run:', e.message); }
for (const c of cleanups.reverse()) { try { await c(); } catch { /* best effort */ } }

const failed = results.filter((r) => !r.ok);
fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(path.join(outDir, 'report.json'), JSON.stringify({ results, browserProblems, fatal: fatal && String(fatal.message), lastProbe }, null, 2));
console.log('\nSmoke test: ' + (fatal ? 'could not run' : failed.length ? `${failed.length} of ${results.length} steps FAILED` : `all ${results.length} steps passed`));
console.log(`Screenshots and report: ${outDir}`);
process.exit(fatal || failed.length ? 1 : 0);
