// Shadow Client launcher — front-end glue.
//
// Design goal: a fresh user opens the app, clicks PLAY once, and the
// game launches. We do not require them to find a "Run first-time
// setup" button in some other tab — the PLAY button itself notices
// nothing is installed yet and runs setup → launch back-to-back.

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

// ───── DOM refs ─────────────────────────────────────────────────
const playBtn        = document.getElementById('play-btn');
const playText       = playBtn.querySelector('.play-text');
const playIcon       = playBtn.querySelector('.play-icon');
const statusLine     = document.getElementById('status-line');
const progress       = document.getElementById('progress');
const progressFill   = document.getElementById('progress-fill');
const detailsToggle  = document.getElementById('details-toggle');
const logView        = document.getElementById('log-view');
const accountRow     = document.getElementById('account-row');
const accountName    = document.getElementById('account-name');
const accountMode    = document.getElementById('account-mode');
const avatar         = document.getElementById('avatar');
const msBtn          = document.getElementById('ms-btn');
const usernameInput  = document.getElementById('username-input');

// ───── Tab switching ────────────────────────────────────────────
document.querySelectorAll('.tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.page').forEach(p => p.classList.remove('active'));
    tab.classList.add('active');
    document.getElementById('page-' + tab.dataset.tab).classList.add('active');
    if (tab.dataset.tab === 'mods') refreshMods();
  });
});

// ───── State + status helpers ───────────────────────────────────
let installed = null;
let busy = false;

function setStatus(text, kind) {
  statusLine.textContent = text;
  statusLine.classList.remove('ok', 'error', 'working');
  if (kind) statusLine.classList.add(kind);
}

function setProgress(pct /* null = indeterminate, 0..100 = bar, -1 = hidden */) {
  if (pct === -1) {
    progress.hidden = true;
    return;
  }
  progress.hidden = false;
  if (pct === null) {
    progressFill.classList.add('indeterminate');
    progressFill.style.width = '';
  } else {
    progressFill.classList.remove('indeterminate');
    progressFill.style.width = Math.min(100, Math.max(0, pct)) + '%';
  }
}

function setBusy(on, label) {
  busy = on;
  playBtn.disabled = on;
  playBtn.classList.toggle('busy', on);
  if (on) {
    playText.textContent = label || 'WORKING…';
    playIcon.textContent = '⏳';
  } else {
    playText.textContent = 'PLAY';
    playIcon.textContent = '▶';
  }
}

// ───── Initial state load ───────────────────────────────────────
async function loadState() {
  try {
    installed = await invoke('read_state');
  } catch (_) { installed = null; }
  renderState();
  try {
    const path = await invoke('project_path');
    document.getElementById('proj-path').textContent = path;
  } catch (_) { /* ignore */ }
}

function renderState() {
  if (installed && installed.mc_version) {
    setStatus(
      `Ready to play — Minecraft ${installed.mc_version}, ${(installed.installed_mods || []).length} mods`,
      'ok'
    );
    setProgress(-1);
  } else {
    setStatus('First launch — click PLAY to download Minecraft (~200 MB, takes 2–3 min)', null);
    setProgress(-1);
  }
}

// ───── The smart PLAY button ────────────────────────────────────
// One click flow:
//   1. If installed.json missing → run `client.py setup` → wait → run `launch`
//   2. If installed.json present → run `launch` directly
playBtn.addEventListener('click', async () => {
  if (busy) return;

  const username = (usernameInput.value || '').trim() || 'Player';
  const heap = parseInt(document.getElementById('opt-heap').value || '4096', 10);
  const gc = document.getElementById('opt-gc').value;

  clearLog();

  // Phase 1: setup if needed
  if (!installed || !installed.mc_version) {
    setBusy(true, 'SETTING UP…');
    setStatus('Downloading Minecraft + perf mods — please wait', 'working');
    setProgress(null); // indeterminate
    try {
      const code = await invoke('setup_client', { username, version: '1.21.11' });
      if (code !== 0) {
        setStatus(`Setup failed (exit ${code}) — click Show details for the log`, 'error');
        setBusy(false);
        setProgress(-1);
        return;
      }
    } catch (e) {
      setStatus('Setup failed: ' + e, 'error');
      setBusy(false);
      setProgress(-1);
      return;
    }
    // Refresh state so the launch phase knows we're installed now
    try { installed = await invoke('read_state'); } catch (_) {}
  }

  // Phase 2: launch
  setBusy(true, 'LAUNCHING…');
  setStatus('Starting Minecraft…', 'working');
  setProgress(null);
  try {
    const code = await invoke('launch_game', { heapMb: heap, gc, username });
    setProgress(-1);
    if (code === 0) {
      setStatus('Game closed cleanly.', 'ok');
    } else {
      setStatus(`Game exited with code ${code} — click Show details`, 'error');
    }
  } catch (e) {
    setStatus('Launch failed: ' + e, 'error');
    setProgress(-1);
  }
  setBusy(false);
});

// ───── Friendly status parsing ──────────────────────────────────
// Map common client.py log lines to short user-facing status strings.
// Anything we don't recognize, we leave the previous status in place.
function friendlyStatusFor(line) {
  const s = line.toLowerCase();
  if (/download(ing)?\s+(mc|minecraft|vanilla|version)/i.test(line)) return 'Downloading Minecraft…';
  if (/download(ing)?\s+asset/i.test(line))                          return 'Downloading game assets…';
  if (/download(ing)?\s+librar/i.test(line))                          return 'Downloading libraries…';
  if (/fabric/i.test(line) && /install/i.test(line))                  return 'Installing Fabric…';
  if (/fetch(ing)?\s+mod/i.test(line) || /modrinth/i.test(line))      return 'Downloading performance mods…';
  if (/compil(ing|e)\s+.*hud/i.test(line))                            return 'Compiling Shadow HUD…';
  if (/build(ing)?/i.test(line) && /hud/i.test(line))                 return 'Compiling Shadow HUD…';
  if (/launching/i.test(line) || /starting java/i.test(line))         return 'Starting Minecraft…';
  if (/microsoft/i.test(line) && /sign|auth|login/i.test(line))       return 'Waiting for Microsoft sign-in…';
  if (/open(ing)?\s+browser/i.test(line))                             return 'Opening sign-in in your browser…';
  return null;
}

function tryParseProgress(line) {
  // Match "37/126" style fraction or "37%" style percent
  let m = line.match(/(\d+)\s*\/\s*(\d+)/);
  if (m) {
    const cur = parseInt(m[1], 10), tot = parseInt(m[2], 10);
    if (tot > 0 && cur <= tot) return (cur / tot) * 100;
  }
  m = line.match(/(\d{1,3})\s*%/);
  if (m) {
    const v = parseInt(m[1], 10);
    if (v >= 0 && v <= 100) return v;
  }
  return null;
}

// Stream Python output → log + friendly status
listen('python-output', (event) => {
  const { kind, line } = event.payload;
  appendLog(line, kind);
  if (!busy) return;
  const nice = friendlyStatusFor(line);
  if (nice) setStatus(nice, 'working');
  const pct = tryParseProgress(line);
  if (pct !== null) setProgress(pct);
});

// ───── Logs ─────────────────────────────────────────────────────
function appendLog(line, kind) {
  const div = document.createElement('div');
  div.className = 'log-line ' + (kind || 'stdout');
  div.textContent = line;
  logView.appendChild(div);
  logView.scrollTop = logView.scrollHeight;
}

function clearLog() {
  logView.innerHTML = '';
}

detailsToggle.addEventListener('click', () => {
  const open = logView.classList.toggle('collapsed') === false;
  detailsToggle.classList.toggle('open', open);
  detailsToggle.querySelector('.caret').textContent = open ? '▾' : '▸';
  detailsToggle.lastChild.textContent = ' ' + (open ? 'Hide details' : 'Show details');
});

// ───── Microsoft sign-in ────────────────────────────────────────
msBtn.addEventListener('click', async () => {
  if (busy) return;
  setBusy(true, 'SIGNING IN…');
  setStatus('A browser tab will open — sign in there, then come back', 'working');
  setProgress(null);
  try {
    const code = await invoke('microsoft_login');
    if (code === 0) {
      // The Python side writes mc-client-account.json with the resolved
      // profile. We don't have a dedicated read-account command yet, so
      // we just flip the UI to a generic "signed in" state.
      accountRow.classList.add('signed-in');
      accountName.textContent = 'Signed in';
      accountMode.textContent = 'Microsoft account (online)';
      avatar.textContent = '✓';
      setStatus('Signed in. Click PLAY to launch.', 'ok');
    } else {
      setStatus('Sign-in cancelled or failed (exit ' + code + ')', 'error');
    }
  } catch (e) {
    setStatus('Sign-in failed: ' + e, 'error');
  }
  setProgress(-1);
  setBusy(false);
});

// ───── Mods tab ─────────────────────────────────────────────────
async function refreshMods() {
  const list = document.getElementById('mod-list');
  list.innerHTML = '<div class="empty">Scanning…</div>';
  try {
    const mods = await invoke('list_mods');
    if (!mods.length) {
      list.innerHTML = '<div class="empty">No mods installed yet. Click PLAY on the home tab to set up.</div>';
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
  if (busy) return;
  setBusy(true, 'UPDATING MODS…');
  setStatus('Refreshing performance mod stack…', 'working');
  setProgress(null);
  try {
    const code = await invoke('update_mods');
    setStatus(code === 0 ? 'Mods updated.' : `Update failed (exit ${code})`, code === 0 ? 'ok' : 'error');
    await refreshMods();
  } catch (e) {
    setStatus('Update failed: ' + e, 'error');
  }
  setProgress(-1);
  setBusy(false);
});

// ───── Boot ─────────────────────────────────────────────────────
loadState();
