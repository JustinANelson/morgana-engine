import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { TOOLS, makeApi, resolveConfig, runTool, inputSchema } from '../tools.mjs';
import { handle } from '../mcp.mjs';
import { parseArgs } from '../cli.mjs';

/** A fake admin API that records requests and answers from a table. */
function fakeApi(state = {}) {
  const calls = [];
  return {
    calls,
    get: async (r) => { calls.push(['GET', r]); if (r === '/api/accounts') return state.accounts || []; if (r.startsWith('/api/characters')) return state.characters || []; return {}; },
    post: async (r, b) => { calls.push(['POST', r, b]); if (r === '/api/tickets') return { ticket: 'dev_abc', username: b.username, expiresInSeconds: b.ttlSeconds }; return { ok: true, message: 'done ' + r }; },
    put: async (r, b) => { calls.push(['PUT', r, b]); return { ok: true, message: 'saved' }; },
  };
}

test('config comes from arguments, then the environment, then the data folder', () => {
  assert.equal(resolveConfig({ url: 'http://h:1/', token: 't' }, {}).url, 'http://h:1');
  assert.equal(resolveConfig({}, { MORGANA_ADMIN_TOKEN: 'envtok', MORGANA_ADMIN_URL: 'http://x:2' }).token, 'envtok');
  assert.equal(resolveConfig({}, {}).url, 'http://127.0.0.1:7003');
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'ctl-'));
  fs.writeFileSync(path.join(dir, 'admin-token.txt'), 'filetoken\n');
  assert.equal(resolveConfig({ dataDir: dir }, {}).token, 'filetoken');
});

test('the api client sends the token, parses errors and explains a missing token or server', async () => {
  const seen = [];
  const ok = makeApi({ url: 'http://s', token: 'T' }, async (u, o) => { seen.push([u, o]); return { ok: true, text: async () => '{"a":1}' }; });
  assert.deepEqual(await ok.post('/api/x', { b: 2 }), { a: 1 });
  assert.equal(seen[0][1].headers.Authorization, 'Bearer T');
  assert.equal(seen[0][1].body, '{"b":2}');
  const refused = makeApi({ url: 'http://s', token: 'T' }, async () => ({ ok: false, status: 400, text: async () => '{"error":"nope"}' }));
  await assert.rejects(refused.get('/api/x'), /nope/);
  await assert.rejects(makeApi({ url: 'http://s' }, async () => ({})).get('/x'), /No admin token/);
  const down = makeApi({ url: 'http://s', token: 'T' }, async () => { throw Object.assign(new Error('x'), { cause: { code: 'ECONNREFUSED' } }); });
  await assert.rejects(down.get('/x'), /Cannot reach the admin API.*--agent/);
});

test('every tool has a description and a valid schema, and names are unique', () => {
  const names = new Set();
  for (const t of TOOLS) {
    assert.ok(t.description.length > 10, t.name);
    assert.ok(!names.has(t.name));
    names.add(t.name);
    assert.equal(inputSchema(t).type, 'object');
    for (const r of t.required) assert.ok(r in t.props, `${t.name} requires unknown ${r}`);
  }
});

test('arguments are validated before any request', async () => {
  const api = fakeApi();
  await assert.rejects(runTool('give_item', { name: 'A' }, api), /Missing required argument: itemId/);
  await assert.rejects(runTool('give_item', { name: 'A', itemId: '3' }, api), /must be an integer/);
  await assert.rejects(runTool('give_item', { name: 'A', itemId: 3, bogus: 1 }, api), /Unknown argument: bogus/);
  await assert.rejects(runTool('nope', {}, api), /Unknown tool/);
  assert.equal(api.calls.length, 0);
});

test('tools call the documented routes', async () => {
  const api = fakeApi();
  await runTool('create_account', { username: 'Bot' }, api);
  await runTool('create_character', { account: 'Bot', name: 'Hero', classId: 2 }, api);
  await runTool('warp_player', { name: 'Hero', mapId: 1, x: 2, y: 3 }, api);
  await runTool('put_content', { collection: 'items', id: 3, document: { id: 3, name: 'X' } }, api);
  await runTool('list_characters', { account: 'a b' }, api);
  assert.deepEqual(api.calls.map((c) => c[0] + ' ' + c[1]), [
    'POST /api/accounts', 'POST /api/characters', 'POST /api/players/warp', 'PUT /api/content/items/3', 'GET /api/characters?account=a%20b',
  ]);
  assert.equal(api.calls[3][2], '{"id":3,"name":"X"}');
});

test('provision_tester creates what is missing, once, and returns a login url', async () => {
  const fresh = fakeApi();
  const out = await runTool('provision_tester', { username: 'Bot', clientUrl: 'http://c/', connect: 'h:7002', tls: true }, fresh, {});
  assert.deepEqual(fresh.calls.map((c) => c[0] + ' ' + c[1]), [
    'GET /api/accounts', 'POST /api/accounts', 'GET /api/characters?account=Bot', 'POST /api/characters', 'POST /api/tickets',
  ]);
  assert.equal(out.loginUrl, 'http://c/?connect=h:7002&tls=1&ticket=dev_abc');
  assert.equal(out.steps.length, 2);
  const again = fakeApi({ accounts: [{ username: 'bot' }], characters: [{ name: 'Bot' }] });
  const second = await runTool('provision_tester', { username: 'Bot' }, again, {});
  assert.deepEqual(again.calls.map((c) => c[0] + ' ' + c[1]), ['GET /api/accounts', 'GET /api/characters?account=Bot', 'POST /api/tickets']);
  assert.equal(second.steps.length, 0);
  assert.equal(second.loginUrl, 'http://127.0.0.1:7002/?connect=127.0.0.1:7002&ticket=dev_abc');
});

test('the MCP server speaks the protocol and reports tool failures as results', async () => {
  const impl = { api: fakeApi(), tools: TOOLS };
  const init = await handle({ id: 1, method: 'initialize', params: { protocolVersion: '2025-03-26' } }, impl);
  assert.equal(init.result.protocolVersion, '2025-03-26');
  assert.ok(init.result.capabilities.tools);
  assert.equal((await handle({ id: 2, method: 'initialize', params: { protocolVersion: '1999' } }, impl)).result.protocolVersion, '2025-06-18');
  assert.equal(await handle({ method: 'notifications/initialized' }, impl), null);
  const list = await handle({ id: 3, method: 'tools/list' }, impl);
  assert.equal(list.result.tools.length, TOOLS.length);
  assert.ok(list.result.tools.every((t) => t.inputSchema.type === 'object'));
  const good = await handle({ id: 4, method: 'tools/call', params: { name: 'status', arguments: {} } }, impl);
  assert.equal(good.result.isError, false);
  const bad = await handle({ id: 5, method: 'tools/call', params: { name: 'give_item', arguments: { name: 'x' } } }, impl);
  assert.equal(bad.result.isError, true);
  assert.match(bad.result.content[0].text, /itemId/);
  assert.equal((await handle({ id: 6, method: 'nope' }, impl)).error.code, -32601);
});

test('the CLI turns flags into typed arguments', () => {
  const tool = TOOLS.find((t) => t.name === 'give_item');
  const { args, conn } = parseArgs(tool, ['--name', 'Bolt', '--itemId', '3', '--amount', '5', '--url', 'http://u', '--data', 'd']);
  assert.deepEqual(args, { name: 'Bolt', itemId: 3, amount: 5 });
  assert.deepEqual(conn, { url: 'http://u', dataDir: 'd' });
  assert.throws(() => parseArgs(tool, ['stray']), /Unexpected argument/);
  const prov = TOOLS.find((t) => t.name === 'provision_tester');
  assert.equal(parseArgs(prov, ['--tls']).args.tls, true);
});
