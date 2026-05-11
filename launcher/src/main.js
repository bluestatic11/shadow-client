// Shadow Client launcher — front-end glue.
// Calls Tauri commands defined in src-tauri/src/lib.rs and renders
// process output / state into the UI.

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

// ───── Tab switching ────────────────────────────────────────────
document.querySelectorAll('.tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    tab.classList.add('active');
    document.getElementById('page-' + tab.dataset.tab).classList.add('active');
    // Refresh content for tabs that need it
    if (tab.dataset.tab === 'mods') refreshMods();
  });
});

// ───── State loading ────────────────────────────────────────────
async function loadState() {
  try {
    const state = await invoke('read_state');
    const heroStatus = document.getElementById('hero-status');
    const playBtn = document.getElementById('play-btn');
    if (state) {
      heroStatus.textContent = `${state.mc_version} · Fabric ${state.fabric_loader} · ${state.installed_mods.length} mods`;
      playBtn.disabled = false;
    } else {
      heroStatus.textContent = 'First-time setup required — Settings → Run first-time setup';
      playBtn.disabled = true;
    }
  } catch (e) {
    document.getElementById('hero-status').textContent = 'Error reading state: ' + e;
  }
  // Project path display
  try {
    const path = await invoke('project_path');
    document.getElementById('proj-path').textContent = path;
  } catch (e) { /* ignore */ }
}

// ───── Play button ──────────────────────────────────────────────
document.getElementById('play-btn').addEventListener('click', async () => {
  const heap = parseInt(document.getElementById('opt-heap').value || '4096', 10);
  const gc = document.getElementById('opt-gc').value;
  const username = document.getElementById('opt-username').value.trim() || null;
  switchToLogs();
  try {
    const code = await invoke('launch_game', { heapMb: heap, gc, username });
    appendLog(`Launch exited with code ${code}`, 'exit');
  } catch (e) {
    appendLog('Launch failed: ' + e, 'stderr');
  }
});

// ───── Mods tab ─────────────────────────────────────────────────
async function refreshMods() {
  const list = document.getElementById('mod-list');
  list.innerHTML = '<div class="empty">Scanning…</div>';
  try {
    const mods = await invoke('list_mods');
    if (!mods.length) {
      list.innerHTML = '<div class="empty">No mods found in game_dir/mods/</div>';
      return;
    }
    list.innerHTML = '';
    for (const name of mods) {
      const row = document.createElement('div');
      const disabled = name.endsWith('.disabled');
      row.className = 'mod-row' + (disabled ? ' disabled' : '');
      row.innerHTML = `
        <span class="mod-status"></span>
        <span class="mod-name">${name}</span>
      `;
      list.appendChild(row);
    }
  } catch (e) {
    list.innerHTML = '<div class="empty">Error: ' + e + '</div>';
  }
}

document.getElementById('refresh-mods').addEventListener('click', refreshMods);
document.getElementById('update-mods').addEventListener('click', async () => {
  switchToLogs();
  try {
    await invoke('update_mods');
  } catch (e) {
    appendLog('update-mods failed: ' + e, 'stderr');
  }
});

// ───── Settings tab ─────────────────────────────────────────────
document.getElementById('setup-btn').addEventListener('click', async () => {
  const username = document.getElementById('opt-username').value.trim() || 'Player';
  switchToLogs();
  try {
    await invoke('setup_client', { username, version: '1.21.11' });
    await loadState();
  } catch (e) {
    appendLog('Setup failed: ' + e, 'stderr');
  }
});

document.getElementById('login-btn').addEventListener('click', async () => {
  switchToLogs();
  try {
    await invoke('microsoft_login');
  } catch (e) {
    appendLog('Login failed: ' + e, 'stderr');
  }
});

// ───── Logs ─────────────────────────────────────────────────────
const logView = document.getElementById('log-view');

function appendLog(line, kind) {
  const div = document.createElement('div');
  div.className = 'log-line ' + (kind || 'stdout');
  div.textContent = line;
  logView.appendChild(div);
  logView.scrollTop = logView.scrollHeight;
}

document.getElementById('clear-logs').addEventListener('click', () => {
  logView.innerHTML = '';
});

function switchToLogs() {
  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
  document.querySelector('.tab[data-tab="logs"]').classList.add('active');
  document.getElementById('page-logs').classList.add('active');
}

// Receive streamed Python output from the Rust backend
listen('python-output', (event) => {
  const { kind, line } = event.payload;
  appendLog(line, kind);
});

// ───── Account placeholder ──────────────────────────────────────
document.getElementById('account').addEventListener('click', async () => {
  switchToLogs();
  try {
    await invoke('microsoft_login');
  } catch (e) {
    appendLog('Login failed: ' + e, 'stderr');
  }
});

// ───── Boot ─────────────────────────────────────────────────────
loadState();
