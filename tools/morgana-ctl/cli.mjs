#!/usr/bin/env node
// Command line front end for the same tools the MCP server offers.
//   node cli.mjs --list
//   node cli.mjs provision_tester --username LlmTester
//   node cli.mjs give_item --name Bolt --itemId 3 --amount 5
//   node cli.mjs put_content --collection items --id 3 --json @item_003.json
// Connection: --url, --token, --data (or MORGANA_ADMIN_URL, MORGANA_ADMIN_TOKEN, MORGANA_DATA_DIR). Output is JSON on stdout; errors go to stderr with exit code 1.
import fs from 'node:fs';
import { TOOLS, makeApi, resolveConfig, runTool } from './tools.mjs';

/** `--key value` pairs into an object, using each tool's argument types (integers and booleans are converted). */
export function parseArgs(tool, argv) {
  const out = {}; const conn = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) throw new Error(`Unexpected argument: ${a}`);
    const key = a.slice(2);
    const next = argv[i + 1];
    const hasValue = next !== undefined && !(next.startsWith('--') && next.length > 2);
    const value = hasValue ? next : undefined;
    if (hasValue) i++;
    if (['url', 'token', 'data'].includes(key)) { conn[key === 'data' ? 'dataDir' : key] = value; continue; }
    if (key === 'json') {
      if (value === undefined) throw new Error('--json needs a value (JSON text or @file)');
      out.document = value.startsWith('@') ? fs.readFileSync(value.slice(1), 'utf8') : value;
      continue;
    }
    const spec = tool.props[key];
    if (spec?.type === 'integer') out[key] = Number(value);
    else if (spec?.type === 'boolean') out[key] = value === undefined || !['false', '0', 'no'].includes(value);
    else out[key] = value ?? '';
  }
  return { args: out, conn };
}

async function main(argv) {
  if (argv.length === 0 || argv[0] === '--help' || argv[0] === '-h' || argv[0] === '--list') {
    const lines = TOOLS.map((t) => {
      const params = Object.keys(t.props).map((k) => (t.required.includes(k) ? `--${k}` : `[--${k}]`)).join(' ');
      return `  ${t.name.padEnd(18)} ${params}\n      ${t.description}`;
    });
    console.log('Usage: node cli.mjs <tool> [--arg value ...] [--url U] [--token T] [--data DIR]\n\nTools:\n' + lines.join('\n'));
    return 0;
  }
  const tool = TOOLS.find((t) => t.name === argv[0]);
  if (!tool) { console.error(`Unknown tool: ${argv[0]}. Run with --list.`); return 2; }
  const { args, conn } = parseArgs(tool, argv.slice(1));
  const api = makeApi(resolveConfig(conn));
  try {
    console.log(JSON.stringify(await runTool(tool.name, args, api), null, 2));
    return 0;
  } catch (e) {
    console.error(e.message || String(e));
    return 1;
  }
}

// Set the exit code and let the event loop drain: process.exit() while fetch connections close trips a libuv assertion on Windows.
if (process.argv[1]?.endsWith('cli.mjs')) process.exitCode = await main(process.argv.slice(2));
