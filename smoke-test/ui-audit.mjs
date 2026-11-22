// Self-contained UI & text-fit audit: starts a throwaway server + web client, screenshots every screen,
// the New Realm Wizard (all 3 steps), in-game HUD/Inventory, and all 19 F1 editor panels into build/ui-audit/,
// and checks `window.__morganaProbe().overflows` on every view.
//
// Run from root via Gradle: .\gradlew.bat :client-web:uiAudit
import { chromium } from 'playwright-core';
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
const outDir = path.join(root, 'build', 'ui-audit');
const webDir = path.join(root, 'client-web', 'build', 'dist', 'js', 'webapp');
const serverBin = path.join(root, 'server-app', 'build', 'install', 'server-app', 'bin', isWindows ? 'server-app.bat' : 'server-app');
const LOAD_TIMEOUT = Number(process.env.SMOKE_LOAD_TIMEOUT_MS || 360_000);
const STEP_TIMEOUT = Number(process.env.SMOKE_STEP_TIMEOUT_MS || 60_000);
const viewport = { width: 1280, height: 720 };

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const log = (...a) => console.log('[ui-audit]', ...a);

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

const cleanups = [];
let page;

async function probe() {
  const text = await page.evaluate(() => (window.__morganaProbe ? window.__morganaProbe() : '{"ready":false}'));
  return JSON.parse(text);
}

async function waitFor(what, predicate, timeoutMs = STEP_TIMEOUT) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const p = await probe();
    if (predicate(p)) return p;
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}; buttons=${(p.buttons || []).map((b) => b.text).join('|')}`);
    await sleep(250);
  }
}

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
  let p = await waitFor(`button "${text}"`, find);
  for (let i = 0; i < 20; i++) {
    await sleep(80);
    const next = await probe();
    const was = find(p);
    const now = find(next);
    p = next;
    if (was && now && was.x === now.x && was.y === now.y) break;
  }
  p = await waitFor(`button "${text}"`, find);
  const b = find(p);
  await clickAt(p, b.x, b.y);
}

async function clickField(hint) {
  const p = await waitFor(`field "${hint}"`, (s) => (s.fields || []).some((f) => f.hint === hint));
  const f = p.fields.find((x) => x.hint === hint);
  await clickAt(p, f.x, f.y);
}

async function finder(wantOpen) {
  const p = await probe();
  if (p.panelFinderOpen !== wantOpen) await clickButton('Editors');
  await waitFor(`finder=${wantOpen}`, (s) => s.panelFinderOpen === wantOpen, 10_000);
}

const report = [];

async function capture(name) {
  await sleep(450);
  const p = await probe();
  const file = path.join(outDir, `${name}.png`);
  await page.screenshot({ path: file });
  const overflows = p.overflows || [];
  report.push({ view: name, overflowCount: overflows.length, overflows });
  log(`shot ${name} (overflows: ${overflows.length})`);
  for (const o of overflows) {
    log(`  [OVERFLOW] ${o.kind} win="${o.window || ''}" text="${o.text || ''}" w=${Math.round(o.w)}/${Math.round(o.prefW || 0)} h=${Math.round(o.h)}/${Math.round(o.prefH || 0)}`);
  }
}

async function main() {
  fs.rmSync(outDir, { recursive: true, force: true });
  fs.mkdirSync(outDir, { recursive: true });
  const chrome = findChrome();
  if (!chrome) throw new Error('No Chrome/Edge found; set CHROME_PATH');

  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'morgana-audit-'));
  cleanups.push(() => fs.rmSync(temp, { recursive: true, force: true }));
  fs.cpSync(path.join(root, 'games', 'blank'), path.join(temp, 'game'), { recursive: true });
  fs.mkdirSync(path.join(temp, 'data'));

  const [tcpPort, wsPort, webPort] = [await freePort(), await freePort(), await freePort()];
  const serverLog = fs.openSync(path.join(outDir, 'server.log'), 'w');
  const server = spawn(isWindows ? 'cmd' : serverBin, isWindows ? ['/c', serverBin, '--port', String(tcpPort), '--game', path.join(temp, 'game'), '--data', path.join(temp, 'data')]
    : ['--port', String(tcpPort), '--game', path.join(temp, 'game'), '--data', path.join(temp, 'data')],
  { cwd: root, env: { ...process.env, MORGANA_WS_PORT: String(wsPort) }, stdio: ['ignore', serverLog, serverLog], detached: !isWindows });
  cleanups.push(() => killTree(server));
  await waitForPort(wsPort, 120_000, 'the server');
  const web = await serveStatic(webDir, webPort);
  cleanups.push(() => web.close());

  const browser = await chromium.launch({
    executablePath: chrome,
    headless: true,
    args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--mute-audio']
  });
  cleanups.push(() => browser.close());
  const context = await browser.newContext({ viewport, deviceScaleFactor: 1 });
  page = await context.newPage();
  page.on('pageerror', (e) => log('PAGEERROR:', e.message));
  page.on('console', (m) => { if (m.type() === 'error' || /^(JavaError|%cException)/.test(m.text())) log('CONSOLE:', m.text().slice(0, 400)); });

  // 1. Realm Browser, Local Worlds, and New Realm Wizard (all 3 steps)
  await page.goto(`http://127.0.0.1:${webPort}/?fakeWorlds=1`);
  await waitFor('login screen', (p) => p.ready && (p.buttons || []).some((b) => b.text === 'Local Worlds'), LOAD_TIMEOUT);
  await capture('01-realms-browser');

  await clickButton('Local Worlds');
  await waitFor('New Realm Wizard button', (p) => (p.buttons || []).some((b) => b.text === 'New Realm Wizard...'));
  await capture('02-local-worlds');

  await clickButton('New Realm Wizard...');
  await waitFor('wizard step 1', (p) => (p.windows || []).includes('New Realm Wizard'));
  await capture('03-wizard-step1-basics');

  // Realm name field is focused or clickable; click Next after typing a name
  const p1 = await probe();
  const nameField = (p1.fields || []).filter((f) => !f.hint && f.text === '').sort((a, b) => a.y - b.y)[0];
  if (nameField) {
    await clickAt(p1, nameField.x, nameField.y);
    await page.keyboard.type('Audit Realm', { delay: 20 });
  }
  await clickButton('Next');
  await waitFor('wizard step 2', (p) => (p.buttons || []).some((b) => b.text === 'Back' && !b.disabled));
  await capture('04-wizard-step2-features');

  await clickButton('Next');
  await waitFor('wizard step 3', (p) => (p.buttons || []).some((b) => b.text === 'Create Realm'));
  await capture('05-wizard-step3-review');

  await clickButton('Cancel');

  // 2. Log in to real throwaway server
  await page.goto(`http://127.0.0.1:${webPort}/?port=${wsPort}`);
  await waitFor('login screen', (p) => p.ready && (p.buttons || []).some((b) => b.text === 'Enter Realm'), LOAD_TIMEOUT);
  await clickButton('Enter Realm');
  await waitFor('in world', (p) => p.inWorld);
  await sleep(1000);
  await capture('06-ingame-hud');

  // Inventory
  await clickButton('Inv (I)');
  await waitFor('inventory', (p) => (p.windows || []).some((w) => w.toLowerCase().includes('inventory')));
  await capture('07-ingame-inventory');
  await clickButton('Inv (I)');
  await sleep(250);

  // 3. F1 Editor: default, finder, every single panel, and Everything layout
  await page.keyboard.press('F1');
  await waitFor('editor open', (p) => p.editorOpen);
  await capture('08-editor-default');

  await finder(true);
  await capture('09-editor-finder');
  await clickButton('Minimal');
  await finder(false);

  const panels = [
    ['Tileset', 'tileset'],
    ['Layers', 'layers'],
    ['Attributes', 'attributes'],
    ['Maps', 'maps'],
    ['Map properties', 'mapprops'],
    ['Items', 'items'],
    ['NPCs', 'npcs'],
    ['Spells', 'spells'],
    ['Shops', 'shops'],
    ['Resources', 'resources'],
    ['Recipes', 'recipes'],
    ['Quests', 'quests'],
    ['Animations', 'animations'],
    ['Events', 'events'],
    ['Common events', 'commonevents'],
    ['Admin tools', 'studio'],
    ['Art', 'art'],
    ['Help', 'help'],
    ['Settings', 'settings']
  ];

  let idx = 10;
  for (const [label, id] of panels) {
    await finder(true);
    await page.keyboard.type(label.toLowerCase(), { delay: 20 });
    await page.keyboard.press('Enter');
    await waitFor(`panel ${id}`, (p) => (p.panels || []).some((x) => x.id === id && x.state === 'SHOWN'));
    await capture(`${String(idx++).padStart(2, '0')}-panel-${id}`);
    await finder(true);
    await clickButton('Minimal');
    await finder(false);
  }

  await finder(true);
  await clickButton('Everything');
  await finder(false);
  await capture(`${String(idx++).padStart(2, '0')}-editor-everything`);

  const totalOverflows = report.reduce((acc, r) => acc + r.overflowCount, 0);
  fs.writeFileSync(path.join(outDir, 'report.json'), JSON.stringify({ totalOverflows, views: report }, null, 2));
  log(`Done. ${report.length} views captured in ${outDir}. Total overflows: ${totalOverflows}`);
}

try {
  await main();
} finally {
  for (const c of cleanups.reverse()) {
    try { c(); } catch { /* ignore */ }
  }
}
