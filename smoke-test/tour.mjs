// A screenshot tour of every editor panel, for design reviews (not a pass/fail test). Usage:
//   node tour.mjs [baseUrl] [width] [height]     e.g. node tour.mjs "http://127.0.0.1:8090/?port=7022" 1280 720
// It needs a running server and web client (the same ones a person would use) and writes build/tour/*.png.
import { chromium } from 'playwright-core';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const out = path.join(here, '..', 'build', 'tour');
const url = process.argv[2] || 'http://127.0.0.1:8090/?port=7022';
const width = Number(process.argv[3] || 1280);
const height = Number(process.argv[4] || 720);
const chrome = ['C:/Program Files/Google/Chrome/Application/chrome.exe', '/usr/bin/google-chrome'].find((p) => fs.existsSync(p));
fs.rmSync(out, { recursive: true, force: true });
fs.mkdirSync(out, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch({ executablePath: chrome, headless: true, args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--mute-audio'] });
const page = await (await browser.newContext({ viewport: { width, height } })).newPage();
const probe = async () => JSON.parse(await page.evaluate(() => (window.__morganaProbe ? window.__morganaProbe() : '{"ready":false}')));
async function waitFor(pred, ms = 120000) { const end = Date.now() + ms; for (;;) { const p = await probe(); if (pred(p)) return p; if (Date.now() > end) throw new Error('timeout; windows=' + (p.windows||[]).join(',') + ' finder=' + p.panelFinderOpen + ' buttons=' + (p.buttons||[]).map((b) => b.text).join('|')); await sleep(250); } }
async function click(text) {
  const p = await waitFor((s) => (s.buttons || []).some((b) => b.text === text));
  const b = p.buttons.find((x) => x.text === text);
  const x = (b.x * width) / p.screenWidth, y = (b.y * height) / p.screenHeight;
  await page.mouse.move(x, y); await page.mouse.down(); await sleep(60); await page.mouse.up(); await sleep(400);
}
async function finder(wantOpen) { const p = await probe(); if (p.panelFinderOpen !== wantOpen) await click('Editors'); await waitFor((s) => s.panelFinderOpen === wantOpen, 10000); }
const shot = async (name) => { await sleep(500); await page.screenshot({ path: path.join(out, `${name}.png`) }); console.log('shot', name); };

await page.goto(url);
await waitFor((p) => p.ready && (p.buttons || []).some((b) => b.text === 'Enter Realm'));
await click('Enter Realm');
await waitFor((p) => p.inWorld);
await sleep(1500);
await shot('00-game-before-editor');
await page.keyboard.press('F1');
await waitFor((p) => p.editorOpen);
await shot('01-editor-default');

const panels = [['Tileset', 'tileset'], ['Layers', 'layers'], ['Attributes', 'attributes'], ['Maps', 'maps'], ['Map properties', 'mapprops'],
  ['Items', 'items'], ['NPCs', 'npcs'], ['Spells', 'spells'], ['Shops', 'shops'], ['Resources', 'resources'], ['Recipes', 'recipes'], ['Quests', 'quests'], ['Animations', 'animations'], ['Art', 'art'], ['Help', 'help'], ['Events', 'events'], ['Admin tools', 'studio']];
let n = 2;
for (const [label, id] of panels) {
  await finder(true);
  await shot(`${String(n).padStart(2, '0')}-finder-before-${id}`);
  await page.keyboard.type(label.toLowerCase(), { delay: 30 }); await page.keyboard.press('Enter'); await sleep(400);
  await shot(`${String(n + 1).padStart(2, '0')}-panel-${id}`);
  n += 2;
  await finder(true);
  await click('Minimal');
  await finder(false);
}
// everything at once, the worst case
await finder(true); await click('Everything'); await finder(false);
await shot(`${String(n++).padStart(2, '0')}-everything`);
await browser.close();
