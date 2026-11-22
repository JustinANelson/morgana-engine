// Shared helpers for the Halcyon Reach trailer: a throwaway realm with a made-up mid/end-game hero, a web client and a recording browser.
import { chromium } from 'playwright-core';
import { spawn, spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs'; import http from 'node:http'; import net from 'node:net'; import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const here = path.dirname(fileURLToPath(import.meta.url));
export const root = path.resolve(here, '..');
export const outDir = path.join(root, 'build', 'trailer');
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const webDir = path.join(root, 'client-web', 'build', 'dist', 'js', 'webapp');
const serverBin = path.join(root, 'server-app', 'build', 'install', 'server-app', 'bin', 'server-app.bat');
const MIME = { '.html': 'text/html', '.js': 'text/javascript', '.json': 'application/json', '.png': 'image/png', '.wasm': 'application/wasm', '.css': 'text/css', '.txt': 'text/plain', '.ttf': 'font/ttf' };
export const HERO = { user: 'Aurelion', pass: 'admin', name: 'Aurelion' };

const freePort = () => new Promise((res) => { const s = net.createServer(); s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); }); });
async function waitPort(port, ms = 120000) { const end = Date.now() + ms; for (;;) { const ok = await new Promise((r) => { const c = net.connect(port, '127.0.0.1'); c.once('connect', () => { c.destroy(); r(true); }); c.once('error', () => r(false)); }); if (ok) return; if (Date.now() > end) throw new Error('port ' + port); await sleep(300); } }
function pbkdf2(pw) { const salt = crypto.randomBytes(16); const key = crypto.pbkdf2Sync(pw, salt, 120000, 32, 'sha256'); const b = (x) => x.toString('base64url'); return `pbkdf2-sha256$120000$${b(salt)}$${b(key)}`; }

const gear = (itemId, extra = {}) => ({ itemId, amount: 1, durability: 100, rolls: { rarity: 'super', itemLevel: 66, affixes: [] }, plus: 5, sockets: [], ...extra });
const stack = (itemId, amount) => ({ itemId, amount, durability: 100, rolls: null, plus: 0, sockets: [] });
const HOTBAR = [['SPELL', 10], ['SPELL', 11], ['SPELL', 12], ['SPELL', 13], ['SPELL', 29], ['SPELL', 30], ['SPELL', 31], ['ITEM', 4], ['ITEM', 5], ['NONE', 0]];

/** Copies the game into build/trailer/realm with an empty data folder. */
export function seedRealm() {
  const realm = path.join(outDir, 'realm');
  fs.rmSync(realm, { recursive: true, force: true });
  fs.cpSync(path.join(root, 'games', 'halcyon-reach'), path.join(realm, 'game'), { recursive: true });
  fs.mkdirSync(path.join(realm, 'data'), { recursive: true });
  // The client pings every 2 s, exactly the server's 30 per minute ping budget, so a hitching frame trips the limiter: loosen it for the recording.
  const sj = path.join(realm, 'game', 'server.json'); const cfg = JSON.parse(fs.readFileSync(sj, 'utf8')); cfg.limits = { enabled: true, scale: 5.0 };
  fs.writeFileSync(sj, JSON.stringify(cfg, null, 2));
  fs.writeFileSync(path.join(realm, 'data', 'server.json'), JSON.stringify(cfg, null, 2)); // the operator file the server reads `limits` from
  return realm;
}

/** Rewrites the freshly created character into a level 66 Expert Emberweaver with mythic gear, a full bag and a quickbar. */
export function patchHero(realm) {
  const dir = path.join(realm, 'data', 'characters');
  const file = path.join(dir, fs.readdirSync(dir).find((f) => f.endsWith('.json')));
  const old = JSON.parse(fs.readFileSync(file, 'utf8'));
  const slots = [stack(2, 30), stack(3, 15), stack(5, 6), stack(4, 40), stack(6, 3), stack(63, 2), stack(64, 2), stack(7, 24), stack(8, 4), stack(15, 37), stack(11, 3), stack(12, 3), stack(13, 2), stack(17, 2), stack(79, 40), stack(80, 12), stack(10, 5), stack(77, 1), stack(1, 184250)];
  const c = {
    ...old, classId: 5, spriteId: 5, level: 66, xp: 0, unspentStatPoints: 0,
    attributes: { str: 12, agi: 44, vit: 96, spi: 172 },
    vitals: { hp: { cur: 1900, max: 1900 }, mp: { cur: 1800, max: 1800 }, stamina: { cur: 100, max: 100 } },
    inventory: { maxSlots: 40, slots: [...slots, ...Array(40 - slots.length).fill(null)] },
    equipment: { equipped: { WEAPON: gear(41, { plus: 7 }), ARMOR: gear(44), HELMET: gear(65), BOOTS: gear(67), RING: gear(68), AMULET: gear(70, { rolls: null, plus: 0 }) } },
    knownSpellIds: [10, 11, 12, 13, 29, 30, 31],
    spellLevels: { 10: 9, 11: 8, 12: 6, 13: 9, 29: 7, 30: 8, 31: 5 },
    variables: { ...(old.variables || {}), promotion: 2 },
    hotbar: HOTBAR.map(([type, targetId], index) => ({ index, type, targetId })),
    quests: Object.fromEntries([1, 2, 3, 4, 5, 6, 7, 8].map((q) => [q, { questId: q, status: 'COMPLETED', objectiveCounts: [9, 9] }])),
    position: { ...old.position, mapId: 1 }
  };
  fs.writeFileSync(file, JSON.stringify(c, null, 2));
  // The hero has seen the whole world: every chunk of every map marked explored.
  const expDir = path.join(realm, 'data', 'social', 'exploration'); fs.mkdirSync(expDir, { recursive: true });
  const maps = Object.fromEntries(Array.from({ length: 28 }, (_, i) => [String(i + 1), { chunkBits: Array(8).fill(-1) }]));
  fs.writeFileSync(path.join(expDir, c.id + '.json'), JSON.stringify({ id: c.id, maps, schemaVersion: 1 }, null, 2));
}

export async function startRealm(realm) {
  const [tcp, ws, web] = [await freePort(), await freePort(), await freePort()];
  const log = fs.openSync(path.join(outDir, 'server.log'), 'w');
  const q = (x) => `"${x}"`;
  const server = spawn('cmd', ['/s', '/c', `"${[q(serverBin), '--port', tcp, '--game', q(path.join(realm, 'game')), '--data', q(path.join(realm, 'data'))].join(' ')}"`], { windowsVerbatimArguments: true, cwd: root, env: { ...process.env, MORGANA_WS_PORT: String(ws) }, stdio: ['ignore', log, log] });
  await waitPort(ws);
  const httpd = http.createServer((req, res) => {
    const u = new URL(req.url, 'http://x'); let f = path.join(webDir, decodeURIComponent(u.pathname));
    if (!f.startsWith(webDir)) { res.writeHead(403).end(); return; }
    if (fs.existsSync(f) && fs.statSync(f).isDirectory()) f = path.join(f, 'index.html');
    if (!fs.existsSync(f)) { res.writeHead(404).end(); return; }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(f)] || 'application/octet-stream', 'Cache-Control': 'no-store' }); fs.createReadStream(f).pipe(res);
  }).listen(web, '127.0.0.1');
  return { url: `http://127.0.0.1:${web}/?port=${ws}`, stop() { try { spawnSync('taskkill', ['/pid', String(server.pid), '/T', '/F'], { stdio: 'ignore' }); } catch { /* gone */ } httpd.close(); } };
}

export function chromePath() {
  return ['C:/Program Files/Google/Chrome/Application/chrome.exe', 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'].find(fs.existsSync);
}
export { chromium };
