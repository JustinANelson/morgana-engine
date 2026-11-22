'use strict';
// The Morgana admin panel. Plain JavaScript, no libraries. Everything that comes from the server is put on the page with
// textContent (never as HTML), and the page's Content-Security-Policy forbids inline script and style.

const TOKEN_KEY = 'morgana-admin-token';
const REFRESH_MS = 5000;

const $ = (selector, root) => (root || document).querySelector(selector);

function el(tag, props, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value === undefined || value === null || value === false) continue;
    if (key === 'class') node.className = value;
    else if (key === 'text') node.textContent = value;
    else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
    else node.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

const state = { token: '', tab: 'overview', timer: null, editing: false };

function stored() {
  try { return sessionStorage.getItem(TOKEN_KEY) || ''; } catch (e) { return ''; }
}
function remember(token) {
  try { if (token) sessionStorage.setItem(TOKEN_KEY, token); else sessionStorage.removeItem(TOKEN_KEY); } catch (e) { /* storage may be blocked */ }
}

async function api(path, method, body) {
  const response = await fetch(path, {
    method: method || 'GET',
    headers: Object.assign({ Authorization: 'Bearer ' + state.token }, body ? { 'Content-Type': 'application/json' } : {}),
    body: body ? JSON.stringify(body) : undefined,
    cache: 'no-store',
  });
  let data = null;
  try { data = await response.json(); } catch (e) { /* an empty or non-JSON body */ }
  if (response.status === 401) {
    signOut('That token was not accepted.');
    throw new Error('unauthorized');
  }
  if (!response.ok) throw new Error((data && data.error) || ('Request failed (' + response.status + ')'));
  return data;
}

function banner(text, kind) {
  const node = $('#banner');
  node.textContent = text;
  node.className = 'banner' + (kind ? ' ' + kind : '');
  node.hidden = !text;
}

function when(ms) {
  return ms ? new Date(ms).toLocaleString() : '';
}

function duration(seconds) {
  const d = Math.floor(seconds / 86400), h = Math.floor((seconds % 86400) / 3600), m = Math.floor((seconds % 3600) / 60);
  return d ? d + ' d ' + h + ' h' : h ? h + ' h ' + m + ' min' : m + ' min';
}

function until(untilMillis) {
  return untilMillis ? 'until ' + when(untilMillis) : 'permanent';
}

// ----- sign in and out

function showLogin(show) {
  $('#login').hidden = !show;
  $('#view').hidden = show;
  $('#tabs').hidden = show;
  $('#signout').hidden = show;
}

function signOut(message) {
  state.token = '';
  remember('');
  clearInterval(state.timer);
  state.timer = null;
  showLogin(true);
  banner(message || '', message ? 'error' : '');
  $('#token').value = '';
}

async function signIn(token) {
  state.token = token.trim();
  try {
    await api('/api/status');
  } catch (e) {
    if (e.message !== 'unauthorized') { banner(e.message, 'error'); showLogin(true); }
    return;
  }
  remember(state.token);
  banner('');
  showLogin(false);
  show(state.tab);
  clearInterval(state.timer);
  state.timer = setInterval(() => { if (!document.hidden && !state.editing) refresh(); }, REFRESH_MS);
}

// ----- tabs

const views = { overview, players, reports, sanctions, audit, tools };

function show(tab) {
  state.tab = tab;
  state.editing = false;
  for (const button of document.querySelectorAll('.tab')) button.classList.toggle('active', button.dataset.tab === tab);
  refresh();
}

async function refresh() {
  try {
    await views[state.tab]();
    updateBadges();
  } catch (e) {
    if (e.message !== 'unauthorized') banner(e.message, 'error');
  }
}

async function updateBadges() {
  try {
    const s = await api('/api/status');
    const players = $('#count-players'), reports = $('#count-reports');
    players.textContent = s.playersOnline; players.hidden = false;
    reports.textContent = s.openReports; reports.hidden = s.openReports === 0;
  } catch (e) { /* the view already reported it */ }
}

function render(...nodes) {
  const view = $('#view');
  view.replaceChildren(...nodes.flat().filter(Boolean));
}

function table(headers, rows, emptyText) {
  if (!rows.length) return el('p', { class: 'empty', text: emptyText });
  return el('div', { class: 'table-wrap' }, el('table', {},
    el('thead', {}, el('tr', {}, headers.map((h) => el('th', { text: h })))),
    el('tbody', {}, rows.map((cells) => el('tr', {}, cells.map((c) => el('td', {}, c)))))
  ));
}

function stat(label, value, warn) {
  return el('div', { class: 'stat' + (warn ? ' warn' : '') }, el('div', { class: 'value', text: String(value) }), el('div', { class: 'label', text: label }));
}

// ----- overview

async function overview() {
  const [s, samples] = await Promise.all([api('/api/status'), api('/api/metrics')]);
  const canvas = el('canvas', { width: '1000', height: '280', role: 'img', 'aria-label': 'Players online and tick time over the last hours' });
  render(
    el('div', { class: 'stats' },
      stat('Players online', s.playersOnline),
      stat('Uptime', duration(s.uptimeSeconds)),
      stat('Tick (average)', s.tickAvgMs.toFixed(2) + ' ms', s.tickAvgMs > 25),
      stat('Tick (worst, last minute)', s.tickMaxMs.toFixed(1) + ' ms', s.tickMaxMs > 50),
      stat('Memory', s.heapUsedMb + ' / ' + s.heapMaxMb + ' MB', s.heapUsedMb > s.heapMaxMb * 0.9),
      stat('Open reports', s.openReports, s.openReports > 0),
      stat('Bans / mutes', s.activeBans + ' / ' + s.activeMutes),
      stat('Accounts / characters', s.accounts + ' / ' + s.characters),
      stat('Maps', s.maps),
    ),
    el('div', { class: 'card' },
      el('h2', { text: 'Players online and tick time' }), canvas,
      el('p', { class: 'legend', text: 'One sample a minute for the last three hours. Gold: players. Red: average tick time (the game runs at 20 ticks a second, so a tick must stay well under 50 ms).' })),
    s.disabledModules.length
      ? el('div', { class: 'card' }, el('h2', { text: 'Switched-off modules' }), el('p', { text: s.disabledModules.join(', ') }))
      : null
  );
  drawChart(canvas, samples);
}

function drawChart(canvas, samples) {
  const ctx = canvas.getContext('2d');
  const styles = getComputedStyle(document.documentElement);
  const w = canvas.width, h = canvas.height, pad = 24;
  ctx.clearRect(0, 0, w, h);
  ctx.strokeStyle = styles.getPropertyValue('--line').trim();
  ctx.fillStyle = styles.getPropertyValue('--muted').trim();
  ctx.font = '20px system-ui, sans-serif';
  ctx.beginPath(); ctx.moveTo(pad, h - pad); ctx.lineTo(w - pad, h - pad); ctx.stroke();
  if (samples.length < 2) { ctx.fillText('Collecting samples (one a minute)...', pad + 10, h / 2); return; }
  const line = (values, max, color) => {
    ctx.strokeStyle = color; ctx.lineWidth = 3; ctx.beginPath();
    values.forEach((v, i) => {
      const x = pad + (i / (values.length - 1)) * (w - 2 * pad);
      const y = h - pad - (v / max) * (h - 2 * pad);
      if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
    });
    ctx.stroke();
  };
  const maxPlayers = Math.max(5, ...samples.map((x) => x.players));
  const maxTick = Math.max(50, ...samples.map((x) => x.tickAvgMs));
  line(samples.map((x) => x.players), maxPlayers, styles.getPropertyValue('--accent').trim());
  line(samples.map((x) => x.tickAvgMs), maxTick, styles.getPropertyValue('--danger').trim());
  ctx.fillStyle = styles.getPropertyValue('--muted').trim();
  ctx.fillText(maxPlayers + ' players', pad + 4, 22);
  ctx.fillText(maxTick.toFixed(0) + ' ms', w - pad - 70, 22);
}

// ----- players

function actionForm(title, fields, submitLabel, onSubmit, onCancel) {
  state.editing = true;
  const inputs = {};
  const form = el('form', { class: 'card form' },
    el('h2', { text: title }),
    el('div', { class: 'row' }, fields.map((f) => {
      inputs[f.name] = el('input', { id: 'f-' + f.name, type: 'text', placeholder: f.placeholder || '', value: f.value || '', maxlength: f.max || 200, autocomplete: 'off' });
      return el('div', {}, el('label', { for: 'f-' + f.name, text: f.label }), inputs[f.name]);
    }),
      el('button', { type: 'submit', class: 'primary', text: submitLabel }),
      el('button', { type: 'button', text: 'Cancel', onclick: () => { state.editing = false; onCancel(); } })),
  );
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    const values = {};
    for (const [name, input] of Object.entries(inputs)) values[name] = input.value;
    try {
      const result = await onSubmit(values);
      banner(result.message, 'ok');
    } catch (e) {
      if (e.message !== 'unauthorized') banner(e.message, 'error');
    }
    state.editing = false;
    refresh();
  });
  return form;
}

async function players() {
  const list = await api('/api/players');
  let form = null;
  const draw = () => render(
    el('div', { class: 'card' }, el('h2', { text: 'Online now (' + list.length + ')' }), table(
      ['Name', 'Level', 'Map', 'Tile', 'Access', ''],
      list.map((p) => [
        p.name, p.level, p.mapId, p.tileX + ', ' + p.tileY, p.access,
        el('span', { class: 'actions' },
          el('button', { class: 'small', text: 'Kick', onclick: () => ask('Kick ' + p.name, [{ name: 'reason', label: 'Reason' }], 'Kick', (v) => api('/api/players/kick', 'POST', { name: p.name, reason: v.reason })) }),
          el('button', { class: 'small', text: 'Mute', onclick: () => ask('Mute ' + p.name, [{ name: 'duration', label: 'For how long (30m, 12h, 7d, perm)', value: '30m', max: 8 }, { name: 'reason', label: 'Reason' }], 'Mute', (v) => api('/api/players/mute', 'POST', { name: p.name, duration: v.duration, reason: v.reason })) }),
          el('button', { class: 'small danger', text: 'Ban', onclick: () => ask('Ban ' + p.name, [{ name: 'duration', label: 'For how long (blank = permanent)', max: 8 }, { name: 'reason', label: 'Reason' }], 'Ban', (v) => api('/api/players/ban', 'POST', { name: p.name, duration: v.duration, reason: v.reason })) })
        ),
      ]),
      'Nobody is online.'
    )),
    form
  );
  const ask = (title, fields, label, send) => {
    form = actionForm(title, fields, label, send, () => { form = null; draw(); });
    draw();
    $('input', form).focus();
  };
  draw();
}

// ----- reports

async function reports() {
  const list = await api('/api/reports');
  let form = null;
  const draw = () => render(
    el('div', { class: 'card' }, el('h2', { text: 'Open reports (' + list.length + ')' }), table(
      ['#', 'When', 'From', 'About', 'What happened', ''],
      list.map((r) => [
        r.id, when(r.atMillis), r.reporterName, r.targetName, r.reason,
        el('button', { class: 'small', text: 'Close', onclick: () => {
          form = actionForm('Close report #' + r.id, [{ name: 'note', label: 'Note (optional)' }], 'Close report', (v) => api('/api/reports/close', 'POST', { id: r.id, note: v.note }), () => { form = null; draw(); });
          draw(); $('input', form).focus();
        } }),
      ]),
      'No open reports.'
    )),
    form
  );
  draw();
}

// ----- bans and mutes

async function sanctions() {
  const [bans, mutes] = await Promise.all([api('/api/sanctions?kind=ban'), api('/api/sanctions?kind=mute')]);
  const lift = (kind, name) => async () => {
    try {
      const result = await api('/api/players/' + (kind === 'ban' ? 'unban' : 'unmute'), 'POST', { name });
      banner(result.message, 'ok');
    } catch (e) { if (e.message !== 'unauthorized') banner(e.message, 'error'); }
    refresh();
  };
  const block = (title, kind, list) => el('div', { class: 'card' }, el('h2', { text: title + ' (' + list.length + ')' }), table(
    ['Account', 'Until', 'By', 'Reason', ''],
    list.map((s) => [s.accountName, until(s.untilMillis), s.byName, s.reason, el('button', { class: 'small', text: 'Lift', onclick: lift(kind, s.accountName) })]),
    'None.'
  ));
  render(block('Bans', 'ban', bans), block('Mutes', 'mute', mutes));
}

// ----- audit log

async function audit() {
  const list = (await api('/api/audit?limit=200')).slice().reverse();
  render(el('div', { class: 'card' }, el('h2', { text: 'Moderation audit log (newest first)' }), table(
    ['When', 'Who', 'Action', 'About', 'Detail'],
    list.map((a) => [when(a.atMillis), a.actor, a.action, a.target, a.detail]),
    'Nothing logged yet.'
  )));
}

// ----- tools

async function tools() {
  state.editing = true; // keep typed text when the page refreshes
  const message = el('input', { id: 'announce', type: 'text', maxlength: '300', placeholder: 'Server restarting in five minutes', autocomplete: 'off' });
  const announce = el('form', { class: 'card form' },
    el('h2', { text: 'Announce to everyone online' }),
    el('div', { class: 'row' }, el('div', {}, el('label', { for: 'announce', text: 'Message' }), message), el('button', { type: 'submit', class: 'primary', text: 'Send' })));
  announce.addEventListener('submit', async (event) => {
    event.preventDefault();
    try {
      const result = await api('/api/broadcast', 'POST', { message: message.value });
      banner(result.message, 'ok');
      message.value = '';
    } catch (e) { if (e.message !== 'unauthorized') banner(e.message, 'error'); }
  });
  const reload = el('div', { class: 'card' },
    el('h2', { text: 'Reload world content' }),
    el('p', { class: 'muted', text: 'Reads the content files from disk and applies them if they are all valid. Players stay connected.' }),
    el('button', { class: 'primary', text: 'Reload content', onclick: async () => {
      try { banner((await api('/api/reload', 'POST')).message, 'ok'); } catch (e) { if (e.message !== 'unauthorized') banner(e.message, 'error'); }
    } }));
  render(announce, reload);
}

// ----- start

document.addEventListener('DOMContentLoaded', () => {
  for (const button of document.querySelectorAll('.tab')) button.addEventListener('click', () => show(button.dataset.tab));
  $('#signout').addEventListener('click', () => signOut(''));
  $('#login-form').addEventListener('submit', (event) => { event.preventDefault(); signIn($('#token').value); });
  const saved = stored();
  if (saved) signIn(saved); else showLogin(true);
});
