// Records the Halcyon Reach trailer: a throwaway realm, a made-up level 66 Emberweaver, and a scripted tour of the world.
// Usage (from smoke-test/): node trailer.mjs [scene,scene,...]   (no argument = every scene). Output: build/trailer/raw/*.webm and build/trailer/shots/*.png
// Needs the built web client and server (:client-web:gdx_teavm_web_js_build, :server-app:installDist).
import fs from 'node:fs'; import path from 'node:path';
import { chromium, chromePath, seedRealm, patchHero, startRealm, outDir, sleep, HERO } from './trailer-lib.mjs';

const W = 1280, H = 720;
const shotDir = path.join(outDir, 'shots'), rawDir = path.join(outDir, 'raw');
fs.rmSync(shotDir, { recursive: true, force: true }); fs.rmSync(rawDir, { recursive: true, force: true });
fs.mkdirSync(shotDir, { recursive: true }); fs.mkdirSync(rawDir, { recursive: true });
const only = process.argv[2] ? process.argv[2].split(',') : null;
// Headed with the real GPU by default: software WebGL is too slow, the client falls behind its pings and the server pauses the hero.
const SOFT = process.env.TRAILER_SOFTWARE === '1';
const launchOpts = SOFT ? { executablePath: chromePath(), headless: true, args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--mute-audio'] }
  : { executablePath: chromePath(), headless: false, args: ['--mute-audio', '--ignore-gpu-blocklist', '--enable-gpu-rasterization', '--disable-renderer-backgrounding', '--disable-background-timer-throttling', '--window-size=1296,820', '--window-position=20,20'] };

// This PC reports a touch screen, which would switch the client to its phone layout.
const NO_TOUCH = () => Object.defineProperty(navigator, 'maxTouchPoints', { get: () => 0 });
let page;
let t0 = 0; const timeline = [];
const probe = async () => JSON.parse(await page.evaluate(() => (window.__morganaProbe ? window.__morganaProbe() : '{"ready":false}')));
async function waitFor(pred, ms = 120000, what = '') { const end = Date.now() + ms; for (;;) { const p = await probe(); if (p.ready && pred(p)) return p; if (Date.now() > end) throw new Error('timeout ' + what + ' ' + JSON.stringify(p).slice(0, 500)); await sleep(250); } }
async function click(x, y) { await page.mouse.move(x, y); await page.mouse.down(); await sleep(60); await page.mouse.up(); await sleep(250); }
async function clickButton(text) { const p = await waitFor((s) => (s.buttons || []).some((b) => b.text === text), 60000, 'button ' + text); await sleep(300); const q = await probe(); const b = q.buttons.find((x) => x.text === text) || p.buttons.find((x) => x.text === text); await click((b.x * W) / q.screenWidth, (b.y * H) / q.screenHeight); }
const shot = async (name) => { await page.screenshot({ path: path.join(shotDir, name + '.png') }); console.log('shot', name); };

async function login(url) {
  await page.goto(url);
  await waitFor((p) => (p.buttons || []).some((b) => b.text === 'Enter Realm'), 120000, 'login screen');
  await click(908, 539); await page.keyboard.press('Control+A'); await page.keyboard.type(HERO.user, { delay: 20 });
  await page.keyboard.press('Enter');
}

async function provision() {
  const realm = seedRealm();
  let rt = await startRealm(realm);
  const browser = await chromium.launch(launchOpts);
  const pctx = await browser.newContext({ viewport: { width: W, height: H } }); await pctx.addInitScript(NO_TOUCH); page = await pctx.newPage();
  await login(rt.url);
  await waitFor((p) => (p.buttons || []).some((b) => b.text === 'Create'), 60000, 'character creation');
  await click(535, 342); await page.keyboard.type(HERO.name, { delay: 20 });
  await clickButton('Create');
  await waitFor((p) => p.inWorld, 90000, 'first entry');
  await sleep(3000);
  await browser.close(); await sleep(3000); rt.stop(); await sleep(3000);
  patchHero(realm);
  return realm;
}

// ---- director helpers
let chatOpen = true;
const chatToggle = async () => {
  const p = await probe(); const b = (p.buttons || []).find((q) => (q.text === '+' || q.text === '-') && q.x < 480 && q.y > 480 && q.y < 540);
  if (!b) { console.log('no chat toggle', JSON.stringify(p.buttons)); return; }
  await click((b.x * W) / p.screenWidth, (b.y * H) / p.screenHeight); chatOpen = b.text === '+'; await sleep(250);
};
const chat = async (line) => {
  if (!chatOpen) await chatToggle();
  await sleep(400);
  const p = await waitFor((q) => (q.fields || []).some((x) => x.y > 600), 8000, 'chat field'); const f = p.fields.filter((q) => q.y > 600).pop();
  await click((f.x * W) / p.screenWidth, (f.y * H) / p.screenHeight);
  await page.keyboard.type(line, { delay: 12 }); await clickButton('Send'); await sleep(300);
  await page.keyboard.press('Escape'); await sleep(100);
  await chatToggle();
};
const warp = async (map, x, y) => { await chat(`/warpto ${map} ${x} ${y}`); await sleep(2500); };
const heal = async () => chat('/heal');
const hold = async (key, ms) => { await page.keyboard.down(key); await sleep(ms); await page.keyboard.up(key); };
const press = async (key, ms = 200) => { await page.keyboard.press(key); await sleep(ms); };
const walk = async (path_) => { for (const [k, ms] of path_) await hold(k, ms); };
/** Casts the quickbar in a rotation for `ms` milliseconds, healing the hero now and then, optionally walking between volleys. */
async function fight(ms, { rotation = ['1', '2', '4', '3', '1', '2', '4', '1'], drift = ['s', 'a', 's', 'd', 'w', 'd'] } = {}) {
  const end = Date.now() + ms; let i = 0;
  while (Date.now() < end) {
    await press(rotation[i % rotation.length], 550); i++;
    if (i % 2 === 0) await hold(drift[(i / 2) % drift.length], 550); // wander so the monsters close in
    if (i % 12 === 0) await heal();
  }
}

const scenes = {
  test: async () => {
    await warp(7, 83, 49); await sleep(1500);
    await shot('t-0'); await hold('a', 600); await shot('t-1');
    for (let i = 0; i < 4; i++) { await press('1', 300); await shot('t-c' + i); }
    await press('2', 300); await shot('t-s'); await press('3', 800); await shot('t-f');
  },
  hub: async () => {
    await warp(1, 70, 74); await sleep(1500); await shot('hub-0');
    await walk([['d', 1300], ['w', 1000], ['a', 1800]]); await shot('hub-1'); await walk([['s', 1500]]);
  },
  gear: async () => {
    await press('i', 1800); await shot('gear-inv'); await press('i', 600);
    await press('v', 1200); await shot('gear-char'); await press('v', 600);
    await press('k', 1500); await shot('gear-spells'); await press('k', 500);
  },
  plain: async () => { await warp(7, 83, 49); await sleep(1500); await shot('plain-0'); await fight(7000); await shot('plain-1'); },
  crags: async () => { await warp(10, 61, 71); await sleep(1500); await shot('crags-0'); await fight(7000); await shot('crags-1'); },
  glass: async () => { await warp(16, 45, 65); await sleep(1500); await shot('glass-0'); await fight(8000, { drift: ['a', 's', 'a', 'w'] }); await shot('glass-1'); },
  starfall: async () => { await warp(17, 47, 79); await sleep(1500); await shot('starfall-0'); await fight(8000); await shot('starfall-1'); },
  under: async () => { await warp(23, 32, 24); await sleep(1500); await shot('under-0'); await fight(7000); await shot('under-1'); },
  grave: async () => { await warp(25, 30, 30); await sleep(1500); await shot('grave-0'); await fight(9000, { rotation: ['2', '4', '3', '1', '2', '4', '3', '1'] }); await shot('grave-1'); },
  market: async () => { await warp(2, 30, 36); await sleep(1500); await shot('market-0'); await walk([['d', 1200], ['w', 1500], ['a', 2000]]); await shot('market-1'); }
};
const order = ['hub', 'gear', 'crags', 'glass', 'starfall', 'under', 'grave', 'market'];

const realm = await provision();
const rt = await startRealm(realm);
const browser = await chromium.launch(launchOpts);
try {
  const ctx = await browser.newContext({ viewport: { width: W, height: H }, recordVideo: { dir: rawDir, size: { width: W, height: H } } });
  await ctx.addInitScript(NO_TOUCH); page = await ctx.newPage(); t0 = Date.now();
  page.on('console', (m) => { if (/^(JavaError|%cException)/.test(m.text())) console.log('JAVA', m.text().slice(0, 200)); });
  await login(rt.url);
  const sel = await waitFor((p) => p.inWorld || (p.buttons || []).some((b) => b.text !== 'Enter Realm' && /play|enter|select|aurelion/i.test(b.text)), 60000, 'character select');
  if (!sel.inWorld) { console.log('char select buttons:', sel.buttons.map((b) => b.text).join('|')); await shot('charselect'); const b = sel.buttons.find((x) => /play|enter|aurelion/i.test(x.text)); await click((b.x * W) / sel.screenWidth, (b.y * H) / sel.screenHeight); }
  await waitFor((p) => p.inWorld, 90000, 'in world'); await sleep(2500); await shot('enter');
  await click(982, 22); await sleep(400); chatOpen = true; await shot('enter2'); await chatToggle(); // minimize the top bar and the chat
  for (const name of order) {
    if (only && !only.includes(name)) continue;
    console.log('scene', name); const a = (Date.now() - t0) / 1000; await scenes[name](); timeline.push({ name, start: a, end: (Date.now() - t0) / 1000 });
  }
  await ctx.close();
  fs.writeFileSync(path.join(outDir, 'timeline.json'), JSON.stringify(timeline, null, 2));
  console.log('video in', rawDir, fs.readdirSync(rawDir));
} finally { await browser.close(); rt.stop(); }
