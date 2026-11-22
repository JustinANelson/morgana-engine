// Screenshots of the realm browser with a made-up directory of 150 realms (wide and narrow screens) into build/realm-look/.
// Needs the built web client (:client-web:gdx_teavm_web_js_build). Usage (from smoke-test/): node realms-look.mjs
import { chromium } from 'playwright-core';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.resolve(here, '..');
const webDir = path.join(root, 'client-web', 'build', 'dist', 'js', 'webapp');
const outDir = path.join(root, 'build', 'realm-look');
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
async function click(p, x, y) { const ax = (x * vp.width) / p.screenWidth, ay = (y * vp.height) / p.screenHeight; await page.mouse.move(ax, ay); await page.mouse.down(); await sleep(60); await page.mouse.up(); await sleep(400); }
async function shot(name) { await sleep(500); await page.screenshot({ path: path.join(outDir, name + '.png') }); console.log('shot', name); }

async function session(width, height, tag) {
  vp = { width, height };
  const ctx = await browser.newContext({ viewport: vp, deviceScaleFactor: 1 });
  page = await ctx.newPage();
  const problems = [];
  page.on('pageerror', (e) => problems.push('pageerror ' + e.message));
  page.on('console', (m) => { if (/^(JavaError|%cException)/.test(m.text()) || m.type() === 'error') problems.push(m.text().slice(0, 300)); });
  await page.goto(`http://127.0.0.1:${port}/?fakeRealms=150`);
  let p = await waitFor((s) => (s.buttons || []).some((b) => b.text === 'Enter Realm'));
  await sleep(1500);
  await shot(`${tag}-1-default`);
  // select a realm by clicking its row, then favourite it with its star
  p = await probe();
  const sx = (x) => x * p.screenWidth / vp.width, sy = (y) => y * p.screenHeight / vp.height;
  const rowAt = tag === 'wide' ? { x: 420, y: 330 } : { x: 250, y: 330 };
  await click(p, sx(rowAt.x), sy(rowAt.y));
  await shot(`${tag}-2-selected`);
  // search
  p = await probe();
  const field = p.fields.find((f) => f.hint && f.hint.startsWith('Search realms'));
  await click(p, field.x, field.y);
  await page.keyboard.type('cozy farm', { delay: 40 });
  await sleep(500);
  await shot(`${tag}-3-search`);
  p = await probe();
  // the Direct Join tab
  p = await probe();
  const direct = p.buttons.find((b) => b.text === 'Direct Join');
  await click(p, direct.x, direct.y);
  await shot(`${tag}-4-direct`);
  console.log(tag, 'fields', p.fields.map((f) => f.hint + '=' + f.text).join(' | '));
  console.log(tag, 'buttons', (p.buttons || []).map((b) => b.text).slice(0, 40).join(' | '));
  console.log(tag, 'problems', problems.length ? problems : 'none');
  await ctx.close();
}

try {
  await session(1280, 720, 'wide');
  await session(640, 900, 'narrow');
} finally {
  await browser.close();
  server.close();
}
