'use strict';

// State pushed by /events: server id -> server (online, info, running, recent).
const state = { servers: new Map(), live: false };
let source = null;
let rerender = () => {};

const $app = document.getElementById('app');
const $crumbs = document.getElementById('crumbs');

// ---------- helpers ----------

function esc(v) {
  return String(v ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

async function api(path, options = {}) {
  const res = await fetch(path, {
    ...options,
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    credentials: 'same-origin',
  });
  if (res.status === 401) { showLogin(); throw new Error('log in first'); }
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(body && body.error ? body.error : res.status + ' ' + res.statusText);
  return body;
}

function ago(ms) {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000));
  if (s < 60) return s + 's ago';
  if (s < 3600) return Math.round(s / 60) + 'm ago';
  if (s < 86400) return Math.round(s / 3600) + 'h ago';
  return new Date(ms).toLocaleDateString();
}

function duration(ms) {
  if (ms == null) return '';
  if (ms < 1000) return ms + ' ms';
  const s = ms / 1000;
  return s < 60 ? s.toFixed(1) + ' s' : Math.floor(s / 60) + 'm ' + Math.round(s % 60) + 's';
}

function num(n) { return Number(n || 0).toLocaleString(); }

function boxText(b) {
  if (!b) return '';
  if (Array.isArray(b)) return `${b[0]} ${b[1]} ${b[2]} → ${b[3]} ${b[4]} ${b[5]}`;
  return `${b.min.x} ${b.min.y} ${b.min.z} → ${b.max.x} ${b.max.y} ${b.max.z}`;
}

function crumbs(parts) {
  $crumbs.innerHTML = parts.map(([label, href]) => `<span>${href ? `<a href="${href}">${esc(label)}</a>` : esc(label)}</span>`).join('');
}

function stats(e) {
  if (!e.tracked) return '<span class="muted stat">no diff</span>';
  const part = (n, sign, cls) => `<span class="${n ? cls : 'zero'}">${sign}${num(n)}</span>`;
  return `<span class="stat">${part(e.added, '+', 'add')} ${part(e.removed, '−', 'rem')} ${part(e.changed, '~', 'chg')}</span>`;
}

// ---------- live updates ----------

function connectEvents() {
  if (source) source.close();
  source = new EventSource('/events');
  source.addEventListener('snapshot', ev => {
    state.servers.clear();
    for (const s of JSON.parse(ev.data).servers) state.servers.set(s.id, s);
    setLive(true);
    rerender();
  });
  source.addEventListener('server', ev => {
    const s = JSON.parse(ev.data);
    state.servers.set(s.id, s);
    rerender();
  });
  source.addEventListener('job', ev => {
    const { server, event, job } = JSON.parse(ev.data);
    const s = state.servers.get(server);
    if (!s) return;
    s.running = s.running.filter(j => j.id !== job.id);
    if (event === 'job.end') {
      s.recent = [job, ...s.recent.filter(j => j.id !== job.id)].slice(0, 50);
    } else {
      s.running.push(job);
    }
    rerender(server);
  });
  source.addEventListener('draft', ev => {
    const { server, draft } = JSON.parse(ev.data);
    const s = state.servers.get(server);
    if (!s) return;
    s.drafts = (s.drafts || []).filter(x => x.id !== draft.id);
    if (draft.status === 'open') s.drafts.push(draft);
    rerender(server);
  });
  source.onerror = () => {
    setLive(false);
    // The browser retries by itself; check whether the session expired.
    fetch('/api/state', { credentials: 'same-origin' }).then(r => { if (r.status === 401) showLogin(); });
  };
}

function setLive(on) {
  state.live = on;
  document.getElementById('live').classList.toggle('on', on);
}

// ---------- login ----------

function showLogin() {
  if (source) { source.close(); source = null; }
  setLive(false);
  document.getElementById('logout').hidden = true;
  crumbs([]);
  $app.innerHTML = '';
  $app.appendChild(document.getElementById('login-tpl').content.cloneNode(true));
  document.getElementById('login-form').addEventListener('submit', async e => {
    e.preventDefault();
    const err = document.getElementById('login-error');
    err.textContent = '';
    try {
      await api('/api/login', { method: 'POST', body: JSON.stringify({ password: e.target.password.value }) });
      start();
    } catch (ex) {
      err.textContent = ex.message;
    }
  });
}

document.getElementById('logout').addEventListener('click', async () => {
  await fetch('/api/logout', { method: 'POST', credentials: 'same-origin' });
  showLogin();
});

// ---------- jobs ----------

function jobHtml(job, serverId) {
  const pct = job.total > 0 ? Math.min(100, Math.round(100 * job.processed / job.total)) : 0;
  const running = job.status === 'running';
  const phase = job.phase === 'snapshot' ? 'reading before' : job.phase === 'diff' ? 'reading result' : null;
  // A preview runs like a build (with a percentage) but places nothing.
  const elapsed = Date.now() - job.started;
  let eta = '';
  if (running && !phase && pct > 3 && pct < 100) eta = ' · ~' + duration(Math.round(elapsed * (100 - pct) / pct)) + ' left';
  const who = [job.tool, job.caller && job.caller !== 'console' ? job.caller : null, job.source].filter(Boolean).map(esc).join(' · ');
  const link = job.draft ? `<a href="#/s/${encodeURIComponent(serverId)}/d/${encodeURIComponent(job.draft)}">draft</a>`
    : job.history ? `<a href="#/s/${encodeURIComponent(serverId)}/h/${encodeURIComponent(job.history)}">diff</a>` : '';
  const status = job.draft && job.status === 'done' ? 'in draft' : job.status;
  return `<div class="job">
    <div class="job-line">
      <span class="what"><span class="mono">${esc(job.id)}</span> ${who}</span>
      <span class="muted">${running ? (phase || pct + '%') + eta : `<span class="badge ${job.draft ? 'running' : esc(job.status)}">${esc(status)}</span> ${num(job.changed)} blocks · ${duration(job.ms)} ${link}`}</span>
    </div>
    ${running ? `<div class="bar ${phase ? 'phase' : ''}"><i style="width:${phase ? 100 : pct}%"></i></div>` : ''}
    ${job.error ? `<div class="error">${esc(job.error)}</div>` : ''}
  </div>`;
}

function serverMeta(s) {
  const i = s.info || {};
  const parts = [];
  if (i.mc) parts.push('Minecraft ' + esc(i.mc));
  if (i.bridge) parts.push(esc(i.bridge));
  if (i.plugin) parts.push('plugin ' + esc(i.plugin));
  if (i.review) parts.push('<span class="badge running">review on</span>');
  parts.push((s.online ? 'connected ' : 'offline since ') + (s.since ? ago(s.since) : 'start'));
  return parts.join(' · ');
}

// ---------- dashboard ----------

function dashboard() {
  crumbs([['Servers']]);
  const servers = [...state.servers.values()].sort((a, b) => a.id.localeCompare(b.id));
  if (!servers.length) {
    $app.innerHTML = `<div class="card"><h2>No servers yet</h2>
      <p>On the panel host run <code>java -jar AIBuilderPanel.jar add-server &lt;id&gt;</code> and put the printed
      <code>relay</code> settings in that server's <code>plugins/AIBuilder/config.yml</code>.</p></div>`;
    return;
  }
  const online = servers.filter(s => s.online).length;
  const busy = servers.reduce((n, s) => n + s.running.length, 0);
  $app.innerHTML = `<div class="page-head"><div><h1>Servers</h1>
      <div class="muted">${online} of ${servers.length} online · ${busy} job${busy === 1 ? '' : 's'} running</div></div></div>
    <div class="grid">${servers.map(s => `
      <section class="card" data-server="${esc(s.id)}">
        <div class="server-head"><span class="dot ${s.online ? 'on' : ''}"></span><h2>${esc(s.name || s.id)}</h2>
          <a class="button ghost small" href="#/s/${encodeURIComponent(s.id)}">History</a></div>
        <div class="meta"><span class="mono">${esc(s.id)}</span> · ${serverMeta(s)}</div>
        ${draftsBlock(s)}
        <div class="jobs">${jobsBlock(s, 4)}</div>
      </section>`).join('')}</div>`;
}

function draftsBlock(s) {
  return (s.drafts || []).map(d => `<a class="draft-banner" href="#/s/${encodeURIComponent(s.id)}/d/${encodeURIComponent(d.id)}">
    <b>Draft waiting for approval</b> <span class="muted">${esc(d.world)} · ${num(d.edits)} edit${d.edits === 1 ? '' : 's'}</span>
    <span class="spacer"></span>${draftStats(d)}</a>`).join('');
}

function jobsBlock(s, recentCount) {
  const running = s.running.map(j => jobHtml(j, s.id)).join('');
  const recent = s.recent.slice(0, recentCount).map(j => jobHtml(j, s.id)).join('');
  if (!running && !recent) return '<div class="empty">No jobs since the panel started.</div>';
  return running + recent;
}

// ---------- server page ----------

async function serverPage(id) {
  const s = state.servers.get(id);
  crumbs([['Servers', '#/'], [s ? s.name || id : id]]);
  const mcp = `${location.origin}/s/${id}`;
  $app.innerHTML = `<div class="page-head"><div><h1>${esc(s ? s.name || id : id)}</h1>
      <div class="muted" id="server-meta">${s ? serverMeta(s) : ''}</div></div>
      <div class="toolbar" style="margin:0"><button class="ghost small" id="copy-region">Copy region to…</button>
      <button class="ghost small" id="show-connect">Connect Claude Code</button></div></div>
    <div class="card connect" id="connect" hidden>
      <h2>Connect</h2>
      <p class="muted">Make a client token on the panel host with <code>java -jar AIBuilderPanel.jar add-client</code>, then in the repo:</p>
      <pre>export AIBUILDER_URL=${esc(mcp)} AIBUILDER_TOKEN=&lt;client token&gt;</pre>
      <p class="muted">The repo's <code>mcp.json</code> and the Python SDK use these as they are. For several servers in one
      Claude Code session, add one MCP entry per server with url <code>${esc(mcp)}/mcp</code>.</p>
    </div>
    <div class="stack">
      <section class="card"><h2>Drafts</h2><div id="server-drafts"><p class="muted">Loading…</p></div></section>
      <section class="card"><h2>Jobs</h2><div id="server-jobs"></div></section>
      <section class="card"><h2>History</h2><div class="table-wrap"><table>
        <thead><tr><th>When</th><th>Tool</th><th class="hide-sm">Who</th><th>Changes</th><th class="hide-sm">Where</th><th>Status</th></tr></thead>
        <tbody id="history"></tbody></table></div>
        <p id="history-more"></p></section>
    </div>`;
  document.getElementById('show-connect').onclick = () => { const c = document.getElementById('connect'); c.hidden = !c.hidden; };
  document.getElementById('copy-region').onclick = () => promoteDialog(id, (s && s.info && s.info.worlds && s.info.worlds[0]) || 'world', null);
  const paintJobs = () => {
    const cur = state.servers.get(id);
    const el = document.getElementById('server-jobs');
    if (cur && el) {
      el.innerHTML = jobsBlock(cur, 10);
      document.getElementById('server-meta').innerHTML = serverMeta(cur);
    }
  };
  paintJobs();
  let lastEnd = null;
  rerender = changed => {
    if (changed && changed !== id) return;
    paintJobs();
    // A finished job adds a history entry: refresh the first page.
    const cur = state.servers.get(id);
    const newest = cur && cur.recent[0] ? cur.recent[0].id + cur.recent[0].status : null;
    if (newest && newest !== lastEnd) { lastEnd = newest; loadHistory(id, null, true); }
  };
  const cur = state.servers.get(id);
  lastEnd = cur && cur.recent[0] ? cur.recent[0].id + cur.recent[0].status : null;
  let draftKey = JSON.stringify((cur && cur.drafts) || []);
  const prev = rerender;
  rerender = changed => {
    prev(changed);
    const now = state.servers.get(id);
    const key = JSON.stringify((now && now.drafts) || []);
    if (key !== draftKey) { draftKey = key; loadDrafts(id); }
  };
  loadDrafts(id);
  await loadHistory(id, null, true);
}

async function loadDrafts(id) {
  const el = document.getElementById('server-drafts');
  if (!el) return;
  try {
    const r = await api(`/api/servers/${encodeURIComponent(id)}/drafts`);
    const rows = r.drafts.slice(0, 10);
    el.innerHTML = (r.review ? '<p class="muted">Review is on: Claude\'s edits wait here until someone approves them.</p>' : '')
      + (rows.length ? `<div class="table-wrap"><table><thead><tr><th>Started</th><th>World</th><th>Edits</th><th>Changes</th><th>Status</th></tr></thead><tbody>
      ${rows.map(d => `<tr class="link" onclick="location.hash='#/s/${encodeURIComponent(id)}/d/${encodeURIComponent(d.id)}'">
        <td>${ago(d.created)}</td><td class="mono">${esc(d.world)}</td><td>${num(d.edits)}</td><td>${draftStats(d)}</td>
        <td><span class="badge ${d.status === 'open' ? 'running' : esc(d.status)}">${d.status === 'open' ? 'waiting' : esc(d.status)}</span></td></tr>`).join('')}
      </tbody></table></div>` : '<p class="empty">No drafts. They appear when review is on, or when a region is promoted here.</p>');
  } catch (ex) {
    el.innerHTML = `<p class="error">${esc(ex.message)}</p>`;
  }
}

async function loadHistory(id, before, reset) {
  const tbody = document.getElementById('history');
  const more = document.getElementById('history-more');
  if (!tbody) return;
  try {
    const q = new URLSearchParams({ limit: '50' });
    if (before) q.set('before', before);
    const page = await api(`/api/servers/${encodeURIComponent(id)}/history?${q}`);
    if (reset) tbody.innerHTML = '';
    if (!page.entries.length && reset) tbody.innerHTML = '<tr><td colspan="6" class="empty">No edits recorded yet.</td></tr>';
    for (const e of page.entries) {
      const tr = document.createElement('tr');
      tr.className = 'link';
      tr.onclick = () => { location.hash = `#/s/${encodeURIComponent(id)}/h/${encodeURIComponent(e.id)}`; };
      tr.innerHTML = `<td title="${esc(new Date(e.time).toLocaleString())}">${ago(e.time)}</td>
        <td>${esc(e.tool)}${e.revertOf ? ` <span class="badge">revert of ${esc(e.revertOf)}</span>` : ''}</td>
        <td class="hide-sm">${esc(e.caller)} <span class="muted">· ${esc(e.source)}</span></td>
        <td>${stats(e)}</td>
        <td class="hide-sm mono">${esc(e.world)} ${esc(boxText(e.bounds))}</td>
        <td><span class="badge ${esc(e.status)}">${esc(e.status)}</span></td>`;
      tbody.appendChild(tr);
    }
    const last = page.entries[page.entries.length - 1];
    more.innerHTML = '';
    if (last && tbody.children.length < page.total) {
      const b = document.createElement('button');
      b.className = 'ghost small';
      b.textContent = 'Load older';
      b.onclick = () => loadHistory(id, last.id, false);
      more.appendChild(b);
    }
  } catch (ex) {
    if (reset) tbody.innerHTML = `<tr><td colspan="6" class="error">${esc(ex.message)}</td></tr>`;
  }
}

// ---------- diff page ----------

async function diffPage(id, hid) {
  const s = state.servers.get(id);
  crumbs([['Servers', '#/'], [s ? s.name || id : id, `#/s/${encodeURIComponent(id)}`], [hid]]);
  rerender = () => {};
  $app.innerHTML = '<p class="muted">Loading diff…</p>';
  let e;
  try {
    e = await api(`/api/servers/${encodeURIComponent(id)}/history/${encodeURIComponent(hid)}`);
  } catch (ex) {
    $app.innerHTML = `<div class="card"><p class="error">${esc(ex.message)}</p></div>`;
    return;
  }
  $app.innerHTML = `<div class="page-head"><div>
      <h1>${esc(e.tool)} <span class="muted mono" style="font-size:15px">${esc(e.id)}</span></h1>
      <div class="summary">
        <div>When <b>${esc(new Date(e.time).toLocaleString())}</b></div>
        <div>Who <b>${esc(e.caller)}</b> via ${esc(e.source)}</div>
        <div>Took <b>${duration(e.ms)}</b></div>
        <div>Where <b class="mono">${esc(e.world)} ${esc(boxText(e.bounds))}</b></div>
        <div>Status <span class="badge ${esc(e.status)}">${esc(e.status)}</span></div>
        ${e.revertOf ? `<div>Reverts <a href="#/s/${encodeURIComponent(id)}/h/${encodeURIComponent(e.revertOf)}">${esc(e.revertOf)}</a></div>` : ''}
      </div>
      ${e.error ? `<p class="error">${esc(e.error)}</p>` : ''}
    </div>
    <div class="pills">
      <span class="pill ${e.added ? 'add' : 'zero'}">+${num(e.added)} added</span>
      <span class="pill ${e.removed ? 'rem' : 'zero'}">−${num(e.removed)} removed</span>
      <span class="pill ${e.changed ? 'chg' : 'zero'}">~${num(e.changed)} changed</span>
    </div></div>
    <div class="stack">
      ${picturesCard("this edit's")}
      <section class="card"><h2>Revert</h2>
        <p class="muted">Puts back the old blocks as a new job. Blocks changed again since this edit are left alone and listed, unless you force it.</p>
        <div class="toolbar"><button class="danger" id="revert">Revert this edit</button>
          <label><input type="checkbox" id="force"> also overwrite later changes</label>
          <span class="spacer"></span><button class="ghost" id="promote">Promote to…</button></div>
        <div id="revert-result"></div></section>
      ${typesCard(e)}
      ${changesCard(e)}
    </div>`;

  if (e.tracked && e.added + e.removed + e.changed > 0) {
    wirePictures(`/api/servers/${encodeURIComponent(id)}/history/${encodeURIComponent(hid)}/render`);
  } else {
    document.getElementById('renders').innerHTML = '<p class="muted">No pictures: this edit has no recorded block changes.</p>';
  }
  document.getElementById('promote').onclick = () => promoteDialog(id, e.world, e.bounds);
  document.getElementById('promote').disabled = !e.bounds;

  const btn = document.getElementById('revert');
  btn.disabled = !e.tracked || e.added + e.removed + e.changed === 0;
  btn.onclick = async () => {
    const force = document.getElementById('force').checked;
    if (!confirm(`Revert ${e.id} on ${id}?${force ? ' Later changes to these blocks will be overwritten.' : ''}`)) return;
    btn.disabled = true;
    const out = document.getElementById('revert-result');
    out.innerHTML = '<p class="muted">Reading the region…</p>';
    try {
      const r = await api(`/api/servers/${encodeURIComponent(id)}/history/${encodeURIComponent(hid)}/revert`, {
        method: 'POST', body: JSON.stringify({ force }),
      });
      let html = r.job
        ? `<p>Putting back ${num(r.blocks)} blocks as job <span class="mono">${esc(r.job)}</span>; follow it on <a href="#/s/${encodeURIComponent(id)}">the server page</a>.</p>`
        : '<p>Nothing to put back: every block is already as it was, or changed since.</p>';
      html += conflictsHtml(r, 'this edit', 'This edit placed');
      out.innerHTML = html;
    } catch (ex) {
      out.innerHTML = `<p class="error">${esc(ex.message)}</p>`;
      btn.disabled = false;
    }
  };
}


// ---------- shared detail parts (history entries and drafts) ----------

function picturesCard(whose) {
  return `<section class="card">
    <div class="toolbar"><h2 style="margin:0">Pictures</h2>
      <select id="view" aria-label="View"><option value="iso">Isometric</option><option value="top">Top-down</option>
        <option value="north">North face</option><option value="south">South face</option><option value="east">East face</option><option value="west">West face</option>
        <option value="3d">3D (interactive)</option></select>
      <select id="corner" aria-label="Camera corner"><option>se</option><option>sw</option><option>ne</option><option>nw</option></select>
      <span class="spacer"></span>
      <span class="legend"><span><i style="background:var(--added)"></i>added</span><span><i style="background:var(--removed)"></i>removed</span><span><i style="background:var(--changed)"></i>changed</span></span>
    </div>
    <div class="renders" id="renders"></div>
    <p class="muted" style="margin:10px 0 0">Drawn from the world as it is now, with ${whose} old and new blocks put in place. Changes hidden inside solid blocks do not show; see the list below.</p>
  </section>`;
}

// The 3D view on the page, if any. It holds a WebGL context, so it is disposed when the view or the page changes.
let viewer3d = null;

function closeViewer3d() {
  if (viewer3d) viewer3d.dispose();
  viewer3d = null;
}

function wirePictures(url) {
  const view = document.getElementById('view');
  const corner = document.getElementById('corner');
  const box = document.getElementById('renders');
  let painted = 0;
  const paint3d = async () => {
    const mine = painted;
    box.className = 'renders one';
    box.innerHTML = '<div class="viewer3d"><span class="note">Loading blocks…</span></div>';
    const holder = box.firstChild;
    try {
      const [viewer, data] = await Promise.all([import('/viewer3d.js'), api(url.replace(/render$/, 'blocks'))]);
      if (mine !== painted || !holder.isConnected) return;
      if (!viewer.supported()) throw new Error('This browser has no WebGL 2, which the 3D view needs.');
      viewer3d = viewer.mount(holder, data);
    } catch (ex) {
      if (mine === painted) holder.innerHTML = `<span class="note">${esc(ex.message)}</span>`;
    }
  };
  const paint = async () => {
    painted++;
    closeViewer3d();
    corner.disabled = view.value !== 'iso';
    if (view.value === '3d') return paint3d();
    box.className = 'renders';
    const slots = ['before', 'after', 'changes'];
    box.innerHTML = slots.map(k => `<figure class="render"><figcaption>${k}</figcaption><div class="img"><span class="note">Rendering…</span></div></figure>`).join('');
    try {
      const r = await api(`${url}?view=${view.value}&corner=${corner.value}`);
      box.querySelectorAll('.render').forEach((fig, i) => {
        const img = new Image();
        img.alt = slots[i];
        img.src = 'data:image/png;base64,' + r[slots[i]];
        img.onclick = () => zoom(img.src);
        const holder = fig.querySelector('.img');
        holder.innerHTML = '';
        holder.appendChild(img);
      });
    } catch (ex) {
      box.querySelectorAll('.img').forEach(el => { el.innerHTML = `<span class="note">${esc(ex.message)}</span>`; });
    }
  };
  // The 3D view needs room to drag in: on a phone the pictures are the better view.
  const small = window.matchMedia('(max-width: 700px)').matches;
  const option = view.querySelector('option[value="3d"]');
  option.disabled = small;
  if (small) option.textContent = '3D (needs a wider screen)';
  view.onchange = paint;
  corner.onchange = paint;
  paint();
}

function typesCard(e) {
  const maxType = Math.max(1, ...e.types.map(t => t.added + t.removed));
  return `<section class="card"><h2>Blocks by type</h2><div class="table-wrap"><table>
    <thead><tr><th>Block</th><th>Added</th><th>Removed</th><th></th></tr></thead>
    <tbody>${e.types.map(t => `<tr><td class="mono">${esc(t.type)}</td><td class="${t.added ? 'add' : 'zero'} stat">+${num(t.added)}</td>
      <td class="${t.removed ? 'rem' : 'zero'} stat">−${num(t.removed)}</td><td><div class="typebar" style="width:${Math.max(4, 100 * (t.added + t.removed) / maxType)}%">
      <span class="a" style="flex:${t.added}"></span><span class="r" style="flex:${t.removed}"></span></div></td></tr>`).join('')
      || '<tr><td colspan="4" class="empty">Nothing changed.</td></tr>'}</tbody></table></div></section>`;
}

function changesCard(e) {
  return `<section class="card"><h2>Changed blocks <span class="muted">(${num(e.listed)} of ${num(e.added + e.removed + e.changed)})</span></h2>
    <div class="table-wrap"><table><thead><tr><th>Position</th><th>Before</th><th>After</th></tr></thead>
    <tbody>${e.changes.map(c => `<tr><td class="mono">${c.pos.join(' ')}</td><td class="mono rem">${esc(c.before)}</td><td class="mono add">${esc(c.after)}</td></tr>`).join('')}</tbody>
    </table></div></section>`;
}

function conflictsHtml(r, what, placedLabel) {
  if (!r.conflicts) return '';
  return `<div class="warn"><b>${num(r.conflicts)} block${r.conflicts === 1 ? '' : 's'} changed since ${what}</b> and ${r.job ? 'were' : 'are'} left alone:
    <table><thead><tr><th>Position</th><th>${placedLabel}</th><th>Now</th></tr></thead><tbody>
    ${r.conflictList.map(c => `<tr><td class="mono">${c.pos.join(' ')}</td><td class="mono">${esc(c.expected)}</td><td class="mono">${esc(c.now)}</td></tr>`).join('')}
    </tbody></table></div>`;
}

function draftStats(d) {
  return stats({ tracked: true, added: d.added, removed: d.removed, changed: d.changed });
}

// ---------- draft page ----------

async function draftPage(id, did) {
  const s = state.servers.get(id);
  crumbs([['Servers', '#/'], [s ? s.name || id : id, `#/s/${encodeURIComponent(id)}`], ['draft ' + did]]);
  rerender = () => {};
  $app.innerHTML = '<p class="muted">Loading draft…</p>';
  let d;
  try {
    d = await api(`/api/servers/${encodeURIComponent(id)}/drafts/${encodeURIComponent(did)}`);
  } catch (ex) {
    $app.innerHTML = `<div class="card"><p class="error">${esc(ex.message)}</p></div>`;
    return;
  }
  const open = d.status === 'open';
  const total = d.added + d.removed + d.changed;
  $app.innerHTML = `<div class="page-head"><div>
      <h1>Draft <span class="muted mono" style="font-size:15px">${esc(d.id)}</span></h1>
      <div class="summary">
        <div>Started <b>${esc(new Date(d.created).toLocaleString())}</b></div>
        <div>World <b class="mono">${esc(d.world)}</b></div>
        <div>Edits <b>${num(d.edits)}</b></div>
        ${d.bounds ? `<div>Where <b class="mono">${esc(boxText(d.bounds))}</b></div>` : ''}
        <div>Status <span class="badge ${open ? 'running' : esc(d.status)}">${open ? 'waiting for approval' : esc(d.status)}</span></div>
        ${d.closedBy ? `<div>By <b>${esc(d.closedBy)}</b> ${ago(d.closedAt)}</div>` : ''}
        ${d.job ? `<div>Placed by job <span class="mono">${esc(d.job)}</span></div>` : ''}
      </div>
    </div>
    <div class="pills">
      <span class="pill ${d.added ? 'add' : 'zero'}">+${num(d.added)} added</span>
      <span class="pill ${d.removed ? 'rem' : 'zero'}">−${num(d.removed)} removed</span>
      <span class="pill ${d.changed ? 'chg' : 'zero'}">~${num(d.changed)} changed</span>
    </div></div>
    <div class="stack">
      ${picturesCard("the draft's")}
      <section class="card"><h2>${open ? 'Approve' : 'Decision'}</h2>
        ${open ? `<p class="muted">Approving places exactly the blocks shown, as one job you can revert from the history. Blocks changed in the world since the draft was made are left alone and listed, unless you force it.</p>
        <div class="toolbar"><button id="approve">Approve and place</button><button class="ghost" id="reject">Reject</button>
          <label><input type="checkbox" id="force"> also overwrite blocks changed since</label>
          <span class="spacer"></span><button class="ghost" id="promote">Promote to…</button></div>`
        : `<p>This draft was ${esc(d.status)}${d.closedBy ? ' by ' + esc(d.closedBy) : ''}.</p>`}
        <div id="decision"></div></section>
      <section class="card"><h2>Edits in this draft</h2><div class="table-wrap"><table>
        <thead><tr><th>When</th><th>Tool</th><th>Who</th><th>Changes</th></tr></thead>
        <tbody>${d.entries.map(x => `<tr><td title="${esc(new Date(x.time).toLocaleString())}">${ago(x.time)}</td>
          <td>${esc(x.tool)}${x.label ? ` <span class="badge">${esc(x.label)}</span>` : ''}</td>
          <td>${esc(x.caller)} <span class="muted">· ${esc(x.source)}</span></td><td>${draftStats(x)}</td></tr>`).join('')
          || '<tr><td colspan="4" class="empty">No edits.</td></tr>'}</tbody></table></div></section>
      ${typesCard(d)}
      ${changesCard(d)}
    </div>`;
  if (total > 0) wirePictures(`/api/servers/${encodeURIComponent(id)}/drafts/${encodeURIComponent(did)}/render`);
  else document.getElementById('renders').innerHTML = '<p class="muted">The draft changes no blocks.</p>';
  if (!open) return;
  document.getElementById('promote').onclick = () => promoteDialog(id, d.world, d.bounds, true);
  document.getElementById('promote').disabled = !d.bounds;
  const out = document.getElementById('decision');
  const approve = document.getElementById('approve');
  const reject = document.getElementById('reject');
  approve.disabled = total === 0;
  approve.onclick = async () => {
    const force = document.getElementById('force').checked;
    if (!confirm(`Place draft ${d.id} on ${id}? ${num(total)} blocks change.${force ? ' Blocks changed since will be overwritten.' : ''}`)) return;
    approve.disabled = reject.disabled = true;
    out.innerHTML = '<p class="muted">Reading the region…</p>';
    try {
      const r = await api(`/api/servers/${encodeURIComponent(id)}/drafts/${encodeURIComponent(did)}/approve`, { method: 'POST', body: JSON.stringify({ force }) });
      out.innerHTML = (r.job
        ? `<p>Approved. Placing ${num(r.blocks)} blocks as job <span class="mono">${esc(r.job)}</span>; follow it on <a href="#/s/${encodeURIComponent(id)}">the server page</a>.</p>`
        : '<p>Approved. Nothing to place: every block already matches the draft or changed since.</p>')
        + conflictsHtml(r, 'the draft was made', 'Draft expected');
    } catch (ex) {
      out.innerHTML = `<p class="error">${esc(ex.message)}</p>`;
      approve.disabled = reject.disabled = false;
    }
  };
  reject.onclick = async () => {
    if (!confirm(`Reject draft ${d.id}? Its edits are dropped and nothing is placed.`)) return;
    approve.disabled = reject.disabled = true;
    try {
      await api(`/api/servers/${encodeURIComponent(id)}/drafts/${encodeURIComponent(did)}/reject`, { method: 'POST', body: '{}' });
      out.innerHTML = '<p>Rejected. Nothing was placed.</p>';
    } catch (ex) {
      out.innerHTML = `<p class="error">${esc(ex.message)}</p>`;
      approve.disabled = reject.disabled = false;
    }
  };
}

// ---------- promote ----------

function promoteDialog(from, world, bounds, fromDraft) {
  // The source itself is listed last: copying a region elsewhere on the same server is a promote too.
  const others = [...state.servers.values()].sort((a, b) => (a.id === from) - (b.id === from) || a.id.localeCompare(b.id));
  const b = bounds || [0, 0, 0, 0, 0, 0];
  const d = document.createElement('dialog');
  d.className = 'card form-dialog';
  d.innerHTML = `<form method="dialog" id="promote-form">
    <h2>Promote region</h2>
    <p class="muted">Copies the blocks in this region${fromDraft ? ' as they are in the world now (approve this draft first if it should be included)' : ' as they are now'}
      into a draft on the target server. Nothing is placed there until someone approves that draft.</p>
    <label>Target server <select name="to" required>${others.map(s => `<option value="${esc(s.id)}" ${s.online ? '' : 'disabled'}>${esc(s.name || s.id)}${s.id === from ? ' (same server)' : ''}${s.online ? '' : ' (offline)'}</option>`).join('')}</select></label>
    <div class="row3">
      <label>From world <input type="text" name="world" value="${esc(world)}" required></label>
      <label>Target world <input type="text" name="toWorld" value="${esc(world)}" required></label>
    </div>
    <label>Region <input type="text" name="box" value="${b.join(' ')}" class="mono" required></label>
    <label>Target corner (min x y z) <input type="text" name="origin" value="${Math.min(b[0], b[3])} ${Math.min(b[1], b[4])} ${Math.min(b[2], b[5])}" class="mono" required></label>
    <label class="check"><input type="checkbox" name="air" checked> copy air too (clears what is in the way)</label>
    <label class="check"><input type="checkbox" name="skipUnknown"> leave out blocks the target server does not have</label>
    <p class="error" id="promote-error"></p>
    <div class="toolbar"><button type="submit" value="ok" id="promote-go">Create draft</button><button type="button" class="ghost" id="promote-cancel">Cancel</button></div>
  </form>`;
  document.body.appendChild(d);
  d.querySelector('#promote-cancel').onclick = () => { d.close(); d.remove(); };
  d.querySelector('#promote-form').onsubmit = async ev => {
    ev.preventDefault();
    const f = ev.target;
    const nums = v => v.trim().split(/[\s,]+/).map(Number);
    const box = nums(f.box.value), origin = nums(f.origin.value);
    const err = d.querySelector('#promote-error');
    if (box.length !== 6 || origin.length !== 3 || [...box, ...origin].some(Number.isNaN)) {
      err.textContent = 'Region needs 6 numbers and the corner 3.';
      return;
    }
    const go = d.querySelector('#promote-go');
    go.disabled = true;
    err.textContent = '';
    go.textContent = 'Copying…';
    try {
      const r = await api('/api/promote', { method: 'POST', body: JSON.stringify({
        from, to: f.to.value, world: f.world.value, toWorld: f.toWorld.value, box, origin,
        air: f.air.checked, skipUnknown: f.skipUnknown.checked }) });
      if (r.error) throw new Error(r.error);
      d.close();
      d.remove();
      if (r.draft) location.hash = `#/s/${encodeURIComponent(f.to.value)}/d/${encodeURIComponent(r.draft)}`;
      else alert('Nothing changed on the target: it already has these blocks.');
    } catch (ex) {
      err.textContent = ex.message;
      go.disabled = false;
      go.textContent = 'Create draft';
    }
  };
  d.showModal();
}

function zoom(src) {
  const d = document.createElement('dialog');
  d.className = 'zoom';
  d.innerHTML = `<img alt="" src="${src}">`;
  d.onclick = () => { d.close(); d.remove(); };
  document.body.appendChild(d);
  d.showModal();
}

// ---------- routing ----------

function route() {
  closeViewer3d();
  const parts = location.hash.replace(/^#\/?/, '').split('/').map(decodeURIComponent);
  if (parts[0] === 's' && parts[1] && parts[2] === 'h' && parts[3]) return diffPage(parts[1], parts[3]);
  if (parts[0] === 's' && parts[1] && parts[2] === 'd' && parts[3]) return draftPage(parts[1], parts[3]);
  if (parts[0] === 's' && parts[1]) return serverPage(parts[1]);
  rerender = () => dashboard();
  dashboard();
}

async function start() {
  try {
    const s = await api('/api/state');
    for (const srv of s.servers) state.servers.set(srv.id, srv);
  } catch {
    return; // showLogin already ran
  }
  document.getElementById('logout').hidden = false;
  connectEvents();
  route();
}

window.addEventListener('hashchange', () => { if (source) route(); });
// Keep "connected 3m ago" and ETAs fresh.
setInterval(() => { if (source && state.live) rerender(); }, 5000);
start();
