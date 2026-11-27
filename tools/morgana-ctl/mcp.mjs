#!/usr/bin/env node
// An MCP (Model Context Protocol) server over stdio for driving a running Morgana server: accounts, characters, login tickets,
// live player changes and content edits. No dependencies. Register it with a client, for example Claude Code:
//   claude mcp add morgana -e MORGANA_DATA_DIR=C:/path/to/server-data -- node C:/path/to/tools/morgana-ctl/mcp.mjs
// Stdout carries only protocol messages; diagnostics go to stderr.
import readline from 'node:readline';
import { TOOLS, inputSchema, makeApi, resolveConfig, runTool } from './tools.mjs';

const SUPPORTED = ['2025-06-18', '2025-03-26', '2024-11-05'];
const api = makeApi(resolveConfig());

function send(message) {
  process.stdout.write(JSON.stringify({ jsonrpc: '2.0', ...message }) + '\n');
}

export async function handle(msg, impl = { api, tools: TOOLS }) {
  const { id, method, params } = msg;
  const isRequest = id !== undefined && id !== null;
  try {
    switch (method) {
      case 'initialize': {
        const asked = params?.protocolVersion;
        return { id, result: {
          protocolVersion: SUPPORTED.includes(asked) ? asked : SUPPORTED[0],
          capabilities: { tools: { listChanged: false } },
          serverInfo: { name: 'morgana-ctl', version: '1.0.0' },
          instructions: 'Controls a running Morgana ORPG server through its admin API. Start with provision_tester to get a login URL for the browser client, then use the other tools for live changes. Tickets and tokens are secrets.',
        } };
      }
      case 'ping': return { id, result: {} };
      case 'tools/list':
        return { id, result: { tools: impl.tools.map((t) => ({ name: t.name, description: t.description, inputSchema: inputSchema(t) })) } };
      case 'tools/call': {
        try {
          const out = await runTool(params?.name, params?.arguments || {}, impl.api);
          return { id, result: { content: [{ type: 'text', text: JSON.stringify(out, null, 2) }], isError: false } };
        } catch (e) {
          // A failed tool is a result the model can read and recover from, not a protocol error.
          return { id, result: { content: [{ type: 'text', text: String(e.message || e) }], isError: true } };
        }
      }
      default:
        if (!isRequest) return null; // notifications (initialized, cancelled, ...) need no answer
        return { id, error: { code: -32601, message: `Method not found: ${method}` } };
    }
  } catch (e) {
    return isRequest ? { id, error: { code: -32603, message: String(e.message || e) } } : null;
  }
}

if (process.argv[1]?.endsWith('mcp.mjs')) {
  const rl = readline.createInterface({ input: process.stdin });
  rl.on('line', async (line) => {
    if (!line.trim()) return;
    let msg;
    try { msg = JSON.parse(line); } catch { return send({ id: null, error: { code: -32700, message: 'Parse error' } }); }
    const reply = await handle(msg);
    if (reply) send(reply);
  });
  rl.on('close', () => process.exit(0));
  process.stderr.write('morgana-ctl MCP server ready\n');
}
