// Screenshots of the character screen with made-up characters (?fakeCharacters=N, first argument, default 2) into build/character-look/.
// Needs the built web client (:client-web:gdx_teavm_web_js_build). Usage (from smoke-test/): node local-worlds-look.mjs
// After the count, optional arguments are clicks made in order, each followed by a screenshot: "x,y" (browser pixels) or a button label.
import { chromium } from 'playwright-core';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const webDir = path.join(root, 'client-web', 'build', 'dist', 'js', 'webapp');
const outDir = path.join(root, 'build', 'character-look');
fs.mkdirSync(outDir, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const types = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.png': 'image/png', '.wasm': 'application/wasm' };

const server = http.createServer((req, res) => {
  let p = decodeURIComponent(req.url.split('?')[0]);
  if (p === '/') p = '/index.html';
  const f = path.join(webDir, p);
  if (!f.startsWith(webDir) || !fs.existsSync(f) || fs.statSync(f).isDirectory()) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'Content-Type': types[path.extname(f)] || 'application/octet-stream' });
  fs.createReadStream(f).pipe(res);
}).listen(0);
const port = server.address().port;

const chrome = [process.env.PROGRAMFILES, process.env['PROGRAMFILES(X86)'], process.env.LOCALAPPDATA].filter(Boolean)
  .flatMap((b) => [path.join(b, 'Google', 'Chrome', 'Application', 'chrome.exe'), path.join(b, 'Microsoft', 'Edge', 'Application', 'msedge.exe')]).find((c) => fs.existsSync(c));
const browser = await chromium.launch({ executablePath: chrome, headless: true, args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--mute-audio'] });
let page;
let vp;
async function probe() { return JSON.parse(await page.evaluate(() => (window.__morganaProbe ? window.__morganaProbe() : '{"ready":false}'))); }
async function waitFor(pred, ms = 120000) { const t = Date.now() + ms; for (;;) { const p = await probe(); if (p.ready && pred(p)) return p; if (Date.now() > t) throw new Error('timeout ' + JSON.stringify(p).slice(0, 400)); await sleep(300); } }
// Clicks at a position given in browser pixels.
async function clickPx(x, y) { await page.mouse.move(x, y); await page.mouse.down(); await sleep(60); await page.mouse.up(); await sleep(500); }
async function clickButton(text) { const p = await probe(); const b = p.buttons.find((q) => q.text === text); if (!b) throw new Error('no button ' + text + ' in ' + p.buttons.map((q) => q.text).join('|')); await clickPx((b.x * vp.width) / p.screenWidth, (b.y * vp.height) / p.screenHeight); }
async function shot(name) { await sleep(500); await page.screenshot({ path: path.join(outDir, name + '.png') }); console.log('shot', name); }

async function session(width, height, tag, rowClicks, count) {
  vp = { width, height };
  const ctx = await browser.newContext({ viewport: vp, deviceScaleFactor: 1 });
  page = await ctx.newPage();
  const problems = [];
  page.on('pageerror', (e) => problems.push('pageerror ' + e.message));
  page.on('console', (m) => { if (/^(JavaError|%cException)/.test(m.text()) || m.type() === 'error') problems.push(m.text().slice(0, 300)); });
  await page.goto(`http://127.0.0.1:${port}/?fakeCharacters=${count}`);
  await waitFor((s) => (s.buttons || []).some((b) => b.text === 'Back to realms'));
  await sleep(1500);
  await shot(`${tag}-1-local`);
  for (const [i, c] of rowClicks.entries()) {
    if (typeof c === 'string') await clickButton(c); else await clickPx(c[0], c[1]);
    await shot(`${tag}-2-step${i}`);
  }
  const p = await probe();
  console.log(tag, 'buttons', (p.buttons || []).map((b) => b.text).join(' | '));
  console.log(tag, 'problems', problems.length ? problems : 'none');
  await ctx.close();
}

try {
  const count = Number(process.argv[2] || 2);
  const clicks = process.argv.slice(3).map((s) => (/^[0-9]+,[0-9]+$/.test(s) ? s.split(',').map(Number) : s));
  await session(1280, 720, 'wide', clicks, count);
  await session(640, 900, 'narrow', [], count);
} finally {
  await browser.close();
  server.close();
}
