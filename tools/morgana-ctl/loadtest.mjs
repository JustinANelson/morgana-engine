#!/usr/bin/env node
// Bot load test for a Morgana server: N bots log in with dev tickets, gather on one map and fight, and the run reports what the
// server (tick time, heap) and each client (bytes and packets per second, round trip) saw.
//
//   node loadtest.mjs --spawn C:/path/to/game --levels 10,25,50,100 --seconds 40
//   node loadtest.mjs --ws 127.0.0.1:7002 --admin http://127.0.0.1:7003 --data C:/path/server-data --levels 20   (an already running --agent server)
//
// --spawn starts a throwaway server (the installed server-app, a copy of the game, a temp data folder, free ports), so a real realm is
// never touched. Against a running server the bots create accounts named LoadBot0001... that stay in its data folder.
// Options: --levels 10,25,50  --seconds 40  --mapId 3 --x 20 --y 42 (where the bots stand: a spread of +-3 tiles)  --out report.json
//          --tick-ms 50 (bot move rate)  --json (print only the JSON)
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { makeApi, resolveConfig } from './tools.mjs';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const here = path.dirname(fileURLToPath(import.meta.url));

function parse(argv) {
  const o = { levels: [10, 25, 50], seconds: 40, mapId: 3, x: 20, y: 42, tickMs: 50, version: 4 };
  for (let i = 0; i < argv.length; i++) {
    const k = argv[i]; const v = argv[i + 1];
    switch (k) {
      case '--spawn': o.spawn = v; i++; break;
      case '--ws': o.ws = v; i++; break;
      case '--admin': o.admin = v; i++; break;
      case '--data': o.data = v; i++; break;
      case '--levels': o.levels = v.split(',').map(Number); i++; break;
      case '--seconds': o.seconds = Number(v); i++; break;
      case '--mapId': o.mapId = Number(v); i++; break;
      case '--x': o.x = Number(v); i++; break;
      case '--y': o.y = Number(v); i++; break;
      case '--tick-ms': o.tickMs = Number(v); i++; break;
      case '--out': o.out = v; i++; break;
      case '--json': o.json = true; break;
      default: throw new Error('Unknown option ' + k);
    }
  }
  return o;
}

const freePort = () => new Promise((res) => { const s = net.createServer(); s.listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => res(p)); }); });
async function waitPort(p, ms) {
  const end = Date.now() + ms;
  while (Date.now() < end) {
    if (await new Promise((r) => { const c = net.connect(p, '127.0.0.1'); c.once('connect', () => { c.destroy(); r(true); }); c.once('error', () => { c.destroy(); r(false); }); })) return;
    await sleep(400);
  }
  throw new Error('port ' + p + ' never opened');
}

async function startThrowawayServer(gameDir) {
  const engine = path.resolve(here, '..', '..');
  const bin = path.join(engine, 'server-app', 'build', 'install', 'server-app', 'bin', 'server-app.bat');
  if (!fs.existsSync(bin)) throw new Error('Build the server first: gradlew :server-app:installDist');
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'morgana-load-'));
  fs.cpSync(gameDir, path.join(temp, 'game'), { recursive: true });
  fs.mkdirSync(path.join(temp, 'data'));
  const [tcp, ws, adm] = [await freePort(), await freePort(), await freePort()];
  const log = fs.openSync(path.join(temp, 'server.log'), 'w');
  const child = spawn('cmd', ['/c', bin, '--port', String(tcp), '--ws-port', String(ws), '--admin-port', String(adm), '--agent',
    '--game', path.join(temp, 'game'), '--data', path.join(temp, 'data')], { cwd: engine, stdio: ['ignore', log, log] });
  await waitPort(ws, 180000); await waitPort(adm, 60000);
  return { ws: `127.0.0.1:${ws}`, admin: `http://127.0.0.1:${adm}`, data: path.join(temp, 'data'), temp,
    stop: () => spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' }) };
}

const DIRS = ['UP', 'DOWN', 'LEFT', 'RIGHT', 'UP_LEFT', 'UP_RIGHT', 'DOWN_LEFT', 'DOWN_RIGHT'];
const STEP = { UP: [0, -8], DOWN: [0, 8], LEFT: [-8, 0], RIGHT: [8, 0], UP_LEFT: [-5.657, -5.657], UP_RIGHT: [5.657, -5.657], DOWN_LEFT: [-5.657, 5.657], DOWN_RIGHT: [5.657, 5.657] };
// What a player on the touch ring mostly does: the basic attack, with a skill now and then.
const TOKENS = ['LMB', 'LMB', 'LMB', 'LMB', 'RMB', 'Q', 'TAB', 'F', 'SPACE'];

class Bot {
  static seen = new Set();
  constructor(name, cfg) {
    this.name = name; this.cfg = cfg; this.rxBytes = 0; this.rxPackets = 0; this.byType = new Map(); this.rtts = []; this.sent = 0;
    this.seq = 1; this.pos = null; this.mode = 'GRID_LOCKED'; this.ready = false; this.closed = false; this.dir = 'UP'; this.walkUntil = 0; this.rxWindow = [];
  }
  connect(ticket) {
    return new Promise((resolve, reject) => {
      const ws = new WebSocket(`ws://${this.cfg.ws}/`);
      this.ws = ws;
      const timer = setTimeout(() => reject(new Error(this.name + ' login timed out')), 30000);
      ws.onopen = () => this.send({ pkt: 'c_resume', token: ticket, protocolVersion: this.cfg.version });
      ws.onerror = () => { clearTimeout(timer); reject(new Error(this.name + ' socket error')); };
      ws.onclose = () => { this.closed = true; };
      ws.onmessage = (ev) => {
        const text = typeof ev.data === 'string' ? ev.data : Buffer.from(ev.data).toString('utf8');
        const bytes = Buffer.byteLength(text);
        let msg; try { msg = JSON.parse(text); } catch { return; }
        const type = msg.pkt;
        if (process.env.DUMP && this.counting && !Bot.seen.has(type)) { Bot.seen.add(type); console.log(type, text.slice(0, 400)); }
        if (this.counting) {
          this.rxBytes += bytes; this.rxPackets++;
          const e = this.byType.get(type) || { n: 0, bytes: 0 }; e.n++; e.bytes += bytes; this.byType.set(type, e);
        }
        if (type === 's_auth_res') {
          clearTimeout(timer);
          if (!msg.success) { reject(new Error(this.name + ': ' + msg.message)); return; }
          this.pos = this.fix(msg.character?.position); this.mode = msg.character?.movementMode || 'GRID_LOCKED'; this.ready = true; resolve();
        } else if (type === 's_pong') {
          this.rtts.push(Date.now() - msg.clientTimestampMs);
        } else if (type === 's_char_sync') {
          if (msg.character?.position) this.pos = this.fix(msg.character.position);
        }
      };
    });
  }
  // The server leaves out pixelX/pixelY when they equal the tile corner (32 px tiles), so fill them in.
  fix(p) { return p && { ...p, pixelX: p.pixelX ?? p.tileX * 32, pixelY: p.pixelY ?? p.tileY * 32 }; }
  send(o) { if (this.ws.readyState === 1) { this.ws.send(JSON.stringify(o)); this.sent++; } }
  start() {
    this.counting = true;
    const tick = this.cfg.tickMs;
    this.moveTimer = setInterval(() => {
      const now = Date.now();
      if (now > this.walkUntil) { // pause or pick a new heading
        if (Math.random() < 0.35) { this.walkUntil = now + 300 + Math.random() * 600; return; }
        this.dir = DIRS[(Math.random() * DIRS.length) | 0]; this.walkUntil = now + 500 + Math.random() * 1500;
      }
      const [dx, dy] = STEP[this.dir];
      const sub = this.mode === 'SUB_TILE';
      this.send({ pkt: 'c_move', direction: this.dir, mode: this.mode, deltaPixelsX: sub ? dx : 0, deltaPixelsY: sub ? dy : 0, seq: this.seq++ });
    }, tick);
    this.fightTimer = setInterval(() => {
      const p = this.pos; if (!p) return;
      const token = TOKENS[(Math.random() * TOKENS.length) | 0];
      this.send({ pkt: 'c_action', actionId: 'input', params: { token, aimX: String(p.pixelX + (Math.random() - 0.5) * 300), aimY: String(p.pixelY + (Math.random() - 0.5) * 300) } });
    }, 350 + Math.random() * 150);
    this.pingTimer = setInterval(() => this.send({ pkt: 'c_ping', clientTimestampMs: Date.now() }), 2000);
  }
  stop() { clearInterval(this.moveTimer); clearInterval(this.fightTimer); clearInterval(this.pingTimer); this.counting = false; try { this.ws.close(); } catch { /* gone */ } }
}

const pct = (arr, p) => { if (!arr.length) return 0; const s = [...arr].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p * s.length))]; };

async function level(api, cfg, count, seconds) {
  const names = Array.from({ length: count }, (_, i) => 'LoadBot' + String(i + 1).padStart(4, '0'));
  // Accounts and characters (half sword and shield, half archer), then one ticket each.
  const accounts = new Set((await api.get('/api/accounts')).map((a) => a.username.toLowerCase()));
  for (let i = 0; i < names.length; i += 10) {
    await Promise.all(names.slice(i, i + 10).map(async (n, j) => {
      if (!accounts.has(n.toLowerCase())) {
        await api.post('/api/accounts', { username: n, password: '' });
        await api.post('/api/characters', { account: n, name: n, classId: (i + j) % 2 === 0 ? 1 : 5, spriteId: 0 });
      }
    }));
  }
  const bots = []; const failures = [];
  for (let i = 0; i < names.length; i += 10) {
    await Promise.all(names.slice(i, i + 10).map(async (n) => {
      try {
        const t = await api.post('/api/tickets', { username: n, ttlSeconds: 300 });
        const bot = new Bot(n, cfg); await bot.connect(t.ticket); bots.push(bot);
      } catch (e) { failures.push(e.message); }
    }));
  }
  // Put them all in one spot: the worst case for broadcast.
  for (let i = 0; i < bots.length; i += 10) {
    await Promise.all(bots.slice(i, i + 10).map((b, j) => api.post('/api/players/warp', { name: b.name, mapId: cfg.mapId, x: cfg.x + ((i + j) % 7) - 3, y: cfg.y + (((i + j) / 7) | 0) % 7 - 3 }).catch(() => {})));
  }
  await sleep(1500);
  const before = await api.get('/api/status');
  for (const b of bots) { b.rxBytes = 0; b.rxPackets = 0; b.byType.clear(); b.rtts.length = 0; b.sent = 0; b.start(); }
  const status = []; const t0 = Date.now();
  while (Date.now() - t0 < seconds * 1000) { await sleep(2000); status.push(await api.get('/api/status')); }
  const elapsed = (Date.now() - t0) / 1000;
  const players = (await api.get('/api/players')).length;
  const alive = bots.filter((b) => !b.closed).length;
  for (const b of bots) b.stop();
  const types = new Map();
  for (const b of bots) for (const [t, e] of b.byType) { const x = types.get(t) || { n: 0, bytes: 0 }; x.n += e.n; x.bytes += e.bytes; types.set(t, x); }
  const rx = bots.map((b) => b.rxBytes / elapsed);
  const rtts = bots.flatMap((b) => b.rtts);
  const tickAvg = status.map((s) => s.tickAvgMs); const tickMax = status.map((s) => s.tickMaxMs);
  const totalBytes = bots.reduce((a, b) => a + b.rxBytes, 0);
  const result = {
    bots: count, connected: bots.length, stillConnected: alive, onlineAtEnd: players, loginFailures: failures.slice(0, 3), seconds: Math.round(elapsed),
    server: { tickAvgMs: +(pct(tickAvg, 0.5)).toFixed(2), tickAvgMaxMs: +Math.max(0, ...tickAvg).toFixed(2), tickWorstMs: +Math.max(0, ...tickMax).toFixed(1),
      heapUsedMb: status.at(-1)?.heapUsedMb, heapMaxMb: status.at(-1)?.heapMaxMb, heapBeforeMb: before.heapUsedMb },
    perClient: { kbPerSecAvg: +(rx.reduce((a, b) => a + b, 0) / Math.max(1, rx.length) / 1024).toFixed(1), kbPerSecMax: +(Math.max(0, ...rx) / 1024).toFixed(1),
      packetsPerSecAvg: +(bots.reduce((a, b) => a + b.rxPackets, 0) / Math.max(1, bots.length) / elapsed).toFixed(1), rttP50: pct(rtts, 0.5), rttP95: pct(rtts, 0.95) },
    serverEgressMbit: +(totalBytes * 8 / elapsed / 1e6).toFixed(2),
    topPackets: [...types.entries()].sort((a, b) => b[1].bytes - a[1].bytes).slice(0, 8)
      .map(([t, e]) => ({ type: t, perClientPerSec: +(e.n / bots.length / elapsed).toFixed(1), kbPerClientPerSec: +(e.bytes / bots.length / elapsed / 1024).toFixed(2) })),
  };
  return result;
}

async function main() {
  const cfg = parse(process.argv.slice(2));
  let spawned = null;
  try {
    if (cfg.spawn) { spawned = await startThrowawayServer(cfg.spawn); cfg.ws = spawned.ws; cfg.admin = spawned.admin; cfg.data = spawned.data; }
    if (!cfg.ws) throw new Error('Give --spawn <game folder>, or --ws host:port with --admin and --data of a running --agent server');
    const api = makeApi(resolveConfig({ url: cfg.admin, dataDir: cfg.data }));
    const report = [];
    for (const n of cfg.levels) {
      if (!cfg.json) console.log(`--- ${n} bots for ${cfg.seconds}s ...`);
      const r = await level(api, cfg, n, cfg.seconds); report.push(r);
      if (!cfg.json) console.log(JSON.stringify(r, null, 1));
      await sleep(3000);
    }
    if (cfg.out) fs.writeFileSync(cfg.out, JSON.stringify(report, null, 2));
    if (cfg.json) console.log(JSON.stringify(report));
    else {
      console.log('\nbots  conn  tick avg/worst ms  KB/s per client  Mbit out  rtt p50/p95');
      for (const r of report) console.log(`${String(r.bots).padStart(4)}  ${String(r.stillConnected).padStart(4)}  ${String(r.server.tickAvgMs).padStart(6)}/${String(r.server.tickWorstMs).padEnd(8)}  ${String(r.perClient.kbPerSecAvg).padStart(14)}  ${String(r.serverEgressMbit).padStart(8)}  ${r.perClient.rttP50}/${r.perClient.rttP95}`);
    }
  } finally { spawned?.stop(); }
}
main().then(() => process.exit(0), (e) => { console.error(e.message); process.exit(1); });
