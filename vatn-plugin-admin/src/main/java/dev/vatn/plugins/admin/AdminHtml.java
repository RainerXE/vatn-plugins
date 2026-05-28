package dev.vatn.plugins.admin;

/** Self-contained HTML dashboard. No build step — pure HTML + vanilla JS + Tailwind CDN. */
final class AdminHtml {

    private AdminHtml() {}

    static String render(String basePath) {
        return HTML.replace("__BASE__", basePath);
    }

    // language=HTML
    private static final String HTML = """
<!DOCTYPE html>
<html lang="en" class="bg-gray-950 text-gray-100">
<head>
  <meta charset="UTF-8"/>
  <meta name="viewport" content="width=device-width, initial-scale=1"/>
  <title>VATN Admin</title>
  <script src="https://cdn.tailwindcss.com"></script>
  <style>
    .badge-primary  { @apply bg-green-500/20 text-green-400 border border-green-500/30; }
    .badge-standby  { @apply bg-yellow-500/20 text-yellow-400 border border-yellow-500/30; }
    .badge-twin     { @apply bg-blue-500/20 text-blue-400 border border-blue-500/30; }
    .badge-success  { @apply bg-green-500/20 text-green-400; }
    .badge-failed   { @apply bg-red-500/20 text-red-400; }
    .badge-running  { @apply bg-blue-500/20 text-blue-400; }
    .badge-queued   { @apply bg-gray-500/20 text-gray-400; }
    .badge-canceled { @apply bg-gray-500/20 text-gray-500; }
    @keyframes spin { to { transform: rotate(360deg); } }
    .spinning { animation: spin 1s linear infinite; display: inline-block; }
  </style>
</head>
<body class="min-h-screen font-mono text-sm">

<!-- ── Auth overlay ───────────────────────────────────────────────────── -->
<div id="auth-overlay" class="fixed inset-0 z-50 flex items-center justify-center bg-gray-950/95 hidden">
  <div class="bg-gray-900 border border-gray-700 rounded-xl p-8 w-96 shadow-2xl">
    <h2 class="text-lg font-semibold text-white mb-1">VATN Admin</h2>
    <p class="text-gray-400 text-xs mb-6">Enter your bearer token to continue.</p>
    <input id="token-input" type="password" placeholder="Bearer token…"
           class="w-full bg-gray-800 border border-gray-600 rounded-lg px-3 py-2 text-white placeholder-gray-500 focus:outline-none focus:border-blue-500 mb-4"/>
    <button onclick="submitToken()"
            class="w-full bg-blue-600 hover:bg-blue-500 text-white rounded-lg py-2 font-medium transition">
      Sign in
    </button>
    <p id="auth-error" class="text-red-400 text-xs mt-3 hidden">Invalid token.</p>
  </div>
</div>

<!-- ── Top bar ──────────────────────────────────────────────────────────── -->
<header class="sticky top-0 z-40 bg-gray-900/80 backdrop-blur border-b border-gray-800 px-6 py-3 flex items-center gap-4">
  <span class="text-blue-400 font-semibold tracking-tight">⬡ VATN Admin</span>
  <span id="hdr-node"   class="text-gray-500 text-xs">—</span>
  <span id="hdr-flavor" class="text-gray-600 text-xs">—</span>
  <span id="hdr-uptime" class="text-gray-600 text-xs ml-auto">—</span>
  <button onclick="refreshAll()" title="Refresh"
          class="text-gray-400 hover:text-white transition text-base leading-none" id="refresh-btn">↺</button>
</header>

<!-- ── Main grid ────────────────────────────────────────────────────────── -->
<main class="max-w-7xl mx-auto px-4 py-6 space-y-6">

  <!-- Row 1: Overview · Health · Plugins -->
  <div class="grid grid-cols-1 md:grid-cols-3 gap-4">

    <!-- Overview -->
    <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
      <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Node</h2>
      <div id="overview-body" class="space-y-1">
        <div class="h-4 bg-gray-800 rounded animate-pulse w-3/4"></div>
        <div class="h-4 bg-gray-800 rounded animate-pulse w-1/2"></div>
      </div>
    </div>

    <!-- Health -->
    <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
      <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Health</h2>
      <div id="health-body" class="space-y-1 text-xs">
        <div class="h-4 bg-gray-800 rounded animate-pulse w-full"></div>
      </div>
    </div>

    <!-- Plugins -->
    <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
      <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Plugins</h2>
      <div id="plugins-body" class="space-y-1 text-xs">
        <div class="h-4 bg-gray-800 rounded animate-pulse w-full"></div>
      </div>
    </div>

  </div>

  <!-- Row 2: Agents -->
  <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
    <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Agents</h2>
    <div id="agents-body" class="text-xs">
      <div class="h-4 bg-gray-800 rounded animate-pulse w-1/3"></div>
    </div>
  </div>

  <!-- Row 3: Workflows -->
  <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
    <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Workflow Runs</h2>
    <div id="workflows-body" class="text-xs">
      <div class="h-4 bg-gray-800 rounded animate-pulse w-2/3"></div>
    </div>
  </div>

  <!-- Row 4: Routes -->
  <div class="bg-gray-900 border border-gray-800 rounded-xl p-4">
    <h2 class="text-xs text-gray-500 uppercase tracking-wider mb-3">Registered Routes</h2>
    <div id="routes-body" class="text-xs">
      <div class="h-4 bg-gray-800 rounded animate-pulse w-1/2"></div>
    </div>
  </div>

</main>

<script>
const BASE = '__BASE__';
let token = sessionStorage.getItem('vatn_admin_token') || '';

// ── auth ──────────────────────────────────────────────────────────────────
function checkAuth() {
  if (!token) { showAuthOverlay(); return false; }
  return true;
}
function showAuthOverlay() {
  document.getElementById('auth-overlay').classList.remove('hidden');
}
function hideAuthOverlay() {
  document.getElementById('auth-overlay').classList.add('hidden');
}
function submitToken() {
  const t = document.getElementById('token-input').value.trim();
  if (!t) return;
  token = t;
  sessionStorage.setItem('vatn_admin_token', token);
  document.getElementById('auth-error').classList.add('hidden');
  hideAuthOverlay();
  refreshAll();
}
document.getElementById('token-input').addEventListener('keydown', e => {
  if (e.key === 'Enter') submitToken();
});

// ── fetch helper ──────────────────────────────────────────────────────────
async function api(path) {
  const headers = token ? { Authorization: 'Bearer ' + token } : {};
  const r = await fetch(BASE + '/api/' + path, { headers });
  if (r.status === 401) { showAuthOverlay(); return null; }
  if (!r.ok) throw new Error(r.status);
  return r.json();
}

// ── rendering helpers ─────────────────────────────────────────────────────
function kv(label, value) {
  return `<div class="flex justify-between gap-2">
    <span class="text-gray-500">${label}</span>
    <span class="text-gray-200 font-medium truncate">${value}</span>
  </div>`;
}
function badge(text, cls) {
  return `<span class="px-1.5 py-0.5 rounded text-xs font-medium ${cls}">${text}</span>`;
}
function roleBadge(role) {
  if (role === 'PRIMARY') return badge('PRIMARY', 'bg-green-500/20 text-green-400 border border-green-500/30');
  if (role === 'TWIN')    return badge('TWIN',    'bg-blue-500/20  text-blue-400  border border-blue-500/30');
  return                         badge('STANDBY', 'bg-yellow-500/20 text-yellow-300 border border-yellow-500/30');
}
function stateBadge(state) {
  const map = {
    SUCCESS:  'bg-green-500/20 text-green-400',
    FAILED:   'bg-red-500/20 text-red-400',
    RUNNING:  'bg-blue-500/20 text-blue-300',
    QUEUED:   'bg-gray-700 text-gray-400',
    CANCELED: 'bg-gray-700 text-gray-500',
  };
  return badge(state, map[state] || 'bg-gray-700 text-gray-400');
}

// ── section loaders ───────────────────────────────────────────────────────
async function loadOverview() {
  const d = await api('overview');
  if (!d) return;
  document.getElementById('hdr-node').textContent   = 'node: ' + d.nodeId;
  document.getElementById('hdr-flavor').textContent = d.flavor;
  document.getElementById('hdr-uptime').textContent = 'up ' + d.uptimeHuman;
  document.getElementById('overview-body').innerHTML = [
    kv('Node ID',   `<span class="font-mono text-blue-300">${d.nodeId}</span>`),
    kv('Flavor',    d.flavor),
    kv('Uptime',    d.uptimeHuman),
    kv('VATN',      d.vatnVersion),
    kv('Plugins',   d.pluginCount),
    kv('Agents',    d.agentCount),
  ].join('');
}

async function loadHealth() {
  const d = await api('health');
  if (!d) return;
  const el = document.getElementById('health-body');
  if (!d.checks || d.checks.length === 0) {
    el.innerHTML = '<span class="text-gray-600">No health checks registered</span>';
    return;
  }
  el.innerHTML = d.checks.map(c => {
    const dot = c.status === 'UP'
      ? '<span class="text-green-400">●</span>'
      : c.status === 'STANDBY'
        ? '<span class="text-yellow-400">◐</span>'
        : '<span class="text-red-400">●</span>';
    return `<div class="flex items-center gap-2">${dot}<span class="text-gray-300">${c.name}</span>
      <span class="ml-auto text-gray-500">${c.detail || c.status}</span></div>`;
  }).join('');
}

async function loadPlugins() {
  const d = await api('plugins');
  if (!d) return;
  const el = document.getElementById('plugins-body');
  if (d.length === 0) {
    el.innerHTML = '<span class="text-gray-600">No plugins registered</span>';
    return;
  }
  el.innerHTML = d.map(p =>
    `<div class="flex items-center gap-2">
      <span class="text-gray-400 truncate">${p.id.replace('dev.vatn.plugins.','')}</span>
      <span class="ml-auto text-gray-600 shrink-0">${p.version}</span>
    </div>`
  ).join('');
}

async function loadAgents() {
  const d = await api('agents');
  if (!d) return;
  const el = document.getElementById('agents-body');
  if (d.length === 0) {
    el.innerHTML = '<span class="text-gray-600">No agents registered</span>';
    return;
  }
  el.innerHTML = `
    <table class="w-full">
      <thead><tr class="text-gray-600 text-left border-b border-gray-800">
        <th class="pb-2 pr-4 font-normal">ID</th>
        <th class="pb-2 pr-4 font-normal">Channel</th>
        <th class="pb-2 pr-4 font-normal">Role</th>
        <th class="pb-2 font-normal">Strategy</th>
      </tr></thead>
      <tbody>
        ${d.map(a => `<tr class="border-b border-gray-800/50">
          <td class="py-1.5 pr-4 text-gray-300">${a.id}</td>
          <td class="py-1.5 pr-4 text-gray-500">${a.channelType}</td>
          <td class="py-1.5 pr-4">${roleBadge(a.role)}</td>
          <td class="py-1.5 text-gray-500">${a.strategy.replace('_', ' ')}</td>
        </tr>`).join('')}
      </tbody>
    </table>`;
}

async function loadWorkflows() {
  const d = await api('workflows');
  if (!d) return;
  const el = document.getElementById('workflows-body');
  if (d.length === 0) {
    el.innerHTML = '<span class="text-gray-600">No workflow runs found</span>';
    return;
  }
  el.innerHTML = `
    <table class="w-full">
      <thead><tr class="text-gray-600 text-left border-b border-gray-800">
        <th class="pb-2 pr-4 font-normal">Run</th>
        <th class="pb-2 pr-4 font-normal">DAG</th>
        <th class="pb-2 pr-4 font-normal">State</th>
        <th class="pb-2 pr-4 font-normal">Duration</th>
        <th class="pb-2 pr-4 font-normal">Trigger</th>
        <th class="pb-2 font-normal">Started</th>
      </tr></thead>
      <tbody>
        ${d.map(r => `<tr class="border-b border-gray-800/50">
          <td class="py-1.5 pr-4 text-gray-500 font-mono text-xs">${r.runId.substring(0, 8)}…</td>
          <td class="py-1.5 pr-4 text-gray-300">${r.dagId}</td>
          <td class="py-1.5 pr-4">${stateBadge(r.state)}</td>
          <td class="py-1.5 pr-4 text-gray-500">${r.durationMs != null ? (r.durationMs / 1000).toFixed(1) + 's' : '—'}</td>
          <td class="py-1.5 pr-4 text-gray-600">${r.triggered}</td>
          <td class="py-1.5 text-gray-600">${r.started ? new Date(r.started).toLocaleString() : '—'}</td>
        </tr>`).join('')}
      </tbody>
    </table>`;
}

async function loadRoutes() {
  const d = await api('routes');
  if (!d) return;
  const el = document.getElementById('routes-body');
  if (d.length === 0) {
    el.innerHTML = '<span class="text-gray-600">No routes registered</span>';
    return;
  }
  // Show as a compact pill grid
  el.innerHTML = `<div class="flex flex-wrap gap-2">
    ${d.map(r => `<span class="bg-gray-800 border border-gray-700 rounded px-2 py-0.5 text-gray-400">${r}</span>`).join('')}
  </div>`;
}

// ── refresh ───────────────────────────────────────────────────────────────
async function refreshAll() {
  if (!checkAuth()) return;
  const btn = document.getElementById('refresh-btn');
  btn.classList.add('spinning');
  try {
    await Promise.all([
      loadOverview(), loadHealth(), loadPlugins(),
      loadAgents(),   loadWorkflows(), loadRoutes()
    ]);
  } finally {
    btn.classList.remove('spinning');
  }
}

// ── boot ──────────────────────────────────────────────────────────────────
if (token) {
  refreshAll();
} else {
  showAuthOverlay();
}
// Auto-refresh every 10 seconds
setInterval(() => { if (token && !document.hidden) refreshAll(); }, 10_000);
</script>
</body>
</html>
""";
}
