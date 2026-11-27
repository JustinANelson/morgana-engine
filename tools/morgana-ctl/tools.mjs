// The tool table shared by the CLI (cli.mjs) and the MCP server (mcp.mjs). Every tool is a thin call to the server's admin
// REST API (see wiki/server/admin_panel.md); nothing here touches game files or the database directly.
import fs from 'node:fs';
import path from 'node:path';

/** Where the admin API is and how to authenticate: flags/arguments first, then environment, then the data folder's token file. */
export function resolveConfig({ url, token, dataDir } = {}, env = process.env) {
  const dir = dataDir || env.MORGANA_DATA_DIR;
  let secret = token || env.MORGANA_ADMIN_TOKEN;
  if (!secret && dir) {
    const file = path.join(dir, 'admin-token.txt');
    if (fs.existsSync(file)) secret = fs.readFileSync(file, 'utf8').trim();
  }
  return { url: (url || env.MORGANA_ADMIN_URL || 'http://127.0.0.1:7003').replace(/\/+$/, ''), token: secret, dataDir: dir };
}

/** A minimal client for the admin API. Errors carry the server's own message. */
export function makeApi(config, fetchImpl = globalThis.fetch) {
  async function call(method, route, body) {
    if (!config.token) {
      throw new Error('No admin token. Set MORGANA_ADMIN_TOKEN, or MORGANA_DATA_DIR to the server data folder (it holds admin-token.txt), and start the server with --agent or --admin-port.');
    }
    let res;
    try {
      res = await fetchImpl(config.url + route, {
        method,
        headers: { Authorization: 'Bearer ' + config.token, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
        body: body === undefined ? undefined : (typeof body === 'string' ? body : JSON.stringify(body)),
      });
    } catch (e) {
      throw new Error(`Cannot reach the admin API at ${config.url} (${e.cause?.code || e.message}). Start the server with --agent (or --admin-port <n>).`);
    }
    const text = await res.text();
    let data;
    try { data = text ? JSON.parse(text) : null; } catch { data = text; }
    if (!res.ok) throw new Error((data && data.error) || `HTTP ${res.status}`);
    return data;
  }
  return { get: (r) => call('GET', r), post: (r, b) => call('POST', r, b ?? {}), put: (r, b) => call('PUT', r, b) };
}

const str = (description) => ({ type: 'string', description });
const int = (description) => ({ type: 'integer', description });
const q = (s) => encodeURIComponent(s);

export const TOOLS = [
  {
    name: 'status',
    description: 'Server at a glance: tick, uptime, players online, accounts, characters, maps, tick time, open reports.',
    props: {}, required: [],
    run: (a, api) => api.get('/api/status'),
  },
  {
    name: 'list_players',
    description: 'Who is online right now (character id, name, level, map, tile, access).',
    props: {}, required: [],
    run: (a, api) => api.get('/api/players'),
  },
  {
    name: 'list_accounts',
    description: 'Every account (no passwords): id, username, access level, character count, banned.',
    props: {}, required: [],
    run: (a, api) => api.get('/api/accounts'),
  },
  {
    name: 'list_characters',
    description: 'Stored characters, optionally only one account\'s: id, name, class, level, position, whether online.',
    props: { account: str('Account username or id; omit for all characters.') }, required: [],
    run: (a, api) => api.get('/api/characters' + (a.account ? '?account=' + q(a.account) : '')),
  },
  {
    name: 'create_account',
    description: 'Create an account. Leave password empty to make one with a random password nobody knows; enter it with login_ticket or provision_tester instead of typing a password.',
    props: { username: str('2 to 20 characters.'), password: str('Optional. Omit for a ticket-only account.') }, required: ['username'],
    run: (a, api) => api.post('/api/accounts', { username: a.username, password: a.password || '' }),
  },
  {
    name: 'set_access',
    description: 'Set an account\'s access level (PLAYER, MONITOR, MAPPER, DEVELOPER). Owner can only be granted through the server\'s owner list, and an online account must be kicked first.',
    props: { username: str('Account username.'), access: str('PLAYER, MONITOR, MAPPER or DEVELOPER.') }, required: ['username', 'access'],
    run: (a, api) => api.post('/api/accounts/access', { username: a.username, access: a.access }),
  },
  {
    name: 'create_character',
    description: 'Create a character on an existing account, by the same rules as the character screen (name rules, class, per-account limit).',
    props: { account: str('Account username.'), name: str('Character name.'), classId: int('Class id; omit for the first class.'), spriteId: int('Optional sprite id.') }, required: ['account', 'name'],
    run: (a, api) => api.post('/api/characters', { account: a.account, name: a.name, classId: a.classId || 0, spriteId: a.spriteId || 0 }),
  },
  {
    name: 'login_ticket',
    description: 'Mint a one-time login ticket for an existing account (server must run with --agent or --dev-login). The ticket is a secret: single use, short-lived. Use it with the client\'s ?connect=host:port&ticket=... or --ticket.',
    props: { username: str('Account username.'), ttlSeconds: int('Lifetime in seconds, default 600, max 86400.') }, required: ['username'],
    run: (a, api) => api.post('/api/tickets', { username: a.username, ttlSeconds: a.ttlSeconds || 600 }),
  },
  {
    name: 'provision_tester',
    description: 'One call to get a logged-in-able test player: creates the account and a character if they do not exist, mints a ticket, and returns a login URL for the browser client. Safe to repeat.',
    props: {
      username: str('Account username (default LlmTester).'),
      characterName: str('Character name (default: the username).'),
      classId: int('Class id for a new character; omit for the first class.'),
      clientUrl: str('Where the browser client is served, e.g. http://127.0.0.1:7002/ (default MORGANA_CLIENT_URL or that).'),
      connect: str('host:port of the server\'s WebSocket port as the browser reaches it (default 127.0.0.1:7002).'),
      tls: { type: 'boolean', description: 'True if the browser should use wss:// to that host.' },
      ttlSeconds: int('Ticket lifetime in seconds, default 900.'),
    }, required: [],
    run: async (a, api, env = process.env) => {
      const username = a.username || 'LlmTester';
      const steps = [];
      const accounts = await api.get('/api/accounts');
      if (!accounts.some((x) => x.username.toLowerCase() === username.toLowerCase())) {
        steps.push((await api.post('/api/accounts', { username, password: '' })).message);
      }
      const chars = await api.get('/api/characters?account=' + q(username));
      if (chars.length === 0) {
        steps.push((await api.post('/api/characters', { account: username, name: a.characterName || username, classId: a.classId || 0, spriteId: 0 })).message);
      }
      const t = await api.post('/api/tickets', { username, ttlSeconds: a.ttlSeconds || 900 });
      const client = (a.clientUrl || env.MORGANA_CLIENT_URL || 'http://127.0.0.1:7002/');
      const connect = a.connect || '127.0.0.1:7002';
      const loginUrl = `${client}${client.includes('?') ? '&' : '?'}connect=${connect}${a.tls ? '&tls=1' : ''}&ticket=${t.ticket}`;
      return { username: t.username, steps, ticket: t.ticket, expiresInSeconds: t.expiresInSeconds, loginUrl };
    },
  },
  {
    name: 'warp_player',
    description: 'Move an online player to a map tile.',
    props: { name: str('Character name.'), mapId: int('Map id.'), x: int('Tile x.'), y: int('Tile y.') }, required: ['name', 'mapId', 'x', 'y'],
    run: (a, api) => api.post('/api/players/warp', { name: a.name, mapId: a.mapId, x: a.x, y: a.y }),
  },
  {
    name: 'give_item',
    description: 'Put items in an online player\'s bag.',
    props: { name: str('Character name.'), itemId: int('Item id.'), amount: int('1 to 9999, default 1.') }, required: ['name', 'itemId'],
    run: (a, api) => api.post('/api/players/give', { name: a.name, itemId: a.itemId, amount: a.amount || 1 }),
  },
  {
    name: 'kick_player',
    description: 'Disconnect an online player.',
    props: { name: str('Character name.'), reason: str('Optional reason shown to them.') }, required: ['name'],
    run: (a, api) => api.post('/api/players/kick', { name: a.name, reason: a.reason || '' }),
  },
  {
    name: 'broadcast',
    description: 'Send a server announcement to everyone online.',
    props: { message: str('Up to 300 characters.') }, required: ['message'],
    run: (a, api) => api.post('/api/broadcast', { message: a.message }),
  },
  {
    name: 'reload_content',
    description: 'Re-read all content files from disk and hot-swap what changed (the same as saving a file with the watcher on).',
    props: {}, required: [],
    run: (a, api) => api.post('/api/reload'),
  },
  {
    name: 'list_content',
    description: 'List content documents (maps, items, npcs, classes, spells, shops, resources, recipes, quests, events, ...).',
    props: { collection: str('Collection folder name such as items or maps; omit for all.') }, required: [],
    run: (a, api) => api.get('/api/content' + (a.collection ? '?collection=' + q(a.collection) : '')),
  },
  {
    name: 'get_content',
    description: 'Read one content document as JSON. Maps are large (a 240 x 240 map is megabytes): prefer reading the file on disk for those.',
    props: { collection: str('e.g. items'), id: int('Document id.') }, required: ['collection', 'id'],
    run: (a, api) => api.get(`/api/content/${q(a.collection)}/${a.id}`),
  },
  {
    name: 'put_content',
    description: 'Create or replace one content document. It is validated and hot-loaded like a hand edit; if the server refuses it, the old file is restored and the errors are returned, so a bad edit never sticks.',
    props: { collection: str('e.g. items'), id: int('Document id (must equal the document\'s own id).'), document: { type: ['object', 'string'], description: 'The document, as a JSON object or JSON text.' } }, required: ['collection', 'id', 'document'],
    run: (a, api) => api.put(`/api/content/${q(a.collection)}/${a.id}`, typeof a.document === 'string' ? a.document : JSON.stringify(a.document)),
  },
  {
    name: 'audit_log',
    description: 'Recent moderation and admin actions (including everything done through this tool).',
    props: { name: str('Only entries about this name.'), limit: int('Default 50, max 500.') }, required: [],
    run: (a, api) => api.get('/api/audit?limit=' + (a.limit || 50) + (a.name ? '&name=' + q(a.name) : '')),
  },
];

export function inputSchema(tool) {
  return { type: 'object', properties: tool.props, required: tool.required, additionalProperties: false };
}

/** Checks required fields and integer types before any request is made, so mistakes come back as clear messages. */
export function validateArgs(tool, args) {
  for (const k of tool.required) if (args[k] === undefined || args[k] === '') throw new Error(`Missing required argument: ${k}`);
  for (const k of Object.keys(args)) {
    const spec = tool.props[k];
    if (!spec) throw new Error(`Unknown argument: ${k}. Known: ${Object.keys(tool.props).join(', ') || '(none)'}`);
    if (spec.type === 'integer' && !Number.isInteger(args[k])) throw new Error(`${k} must be an integer`);
    if (spec.type === 'string' && typeof args[k] !== 'string') throw new Error(`${k} must be a string`);
  }
}

export async function runTool(name, args, api, env = process.env) {
  const tool = TOOLS.find((t) => t.name === name);
  if (!tool) throw new Error(`Unknown tool: ${name}. Known: ${TOOLS.map((t) => t.name).join(', ')}`);
  validateArgs(tool, args || {});
  return tool.run(args || {}, api, env);
}
