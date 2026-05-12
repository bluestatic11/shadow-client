// Shadow Client launcher — front-end glue.
//
// Design goal: a fresh user opens the app, sees ONE input (username), ONE
// button (PLAY), and a small version picker. Click PLAY → game launches.
// Everything else (RAM, GC, mods list, logs, shortcuts) lives behind the
// gear icon in the top bar.

const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

// ───── Supported Minecraft versions ─────────────────────────────
// Hardcoded list of 1.21+ releases. Newest first so the dropdown defaults
// to latest. client.py asks Mojang's version manifest at setup time and
// will error gracefully if any of these isn't (yet) on Mojang's CDN.
const SUPPORTED_VERSIONS = [
  // 1.26+ (latest major)
  '1.26',
  '1.25',
  '1.24',
  '1.23',
  '1.22',
  // 1.21.x — older but commonly requested for mod compatibility
  '1.21.11',
  '1.21.10',
  '1.21.9',
  '1.21.8',
  '1.21.7',
  '1.21.6',
  '1.21.5',
  '1.21.4',
  '1.21.3',
  '1.21.2',
  '1.21.1',
  '1.21',
];
const DEFAULT_VERSION = '1.26';
const SAVED_VERSION_KEY = 'shadowclient.version';

// ───── DOM refs (resolved on DOMContentLoaded) ──────────────────
const $ = (id) => document.getElementById(id);

const playBtn        = $('play-btn');
const playText       = playBtn.querySelector('.play-text');
const playIcon       = playBtn.querySelector('.play-icon');
const statusLine     = $('status-line');
const progress       = $('progress');
const progressFill   = $('progress-fill');
const usernameInput  = $('username-input');
const msBtn          = $('ms-btn');
const openSettings   = $('open-settings');
const closeSettings  = $('close-settings');
const settingsDialog = $('settings-dialog');

// ───── State ────────────────────────────────────────────────────
let installed = null;
let busy = false;
let signedIn = false;

function getPickedVersion() {
  const sel = $('version-select');
  if (sel && sel.value) return sel.value;
  return localStorage.getItem(SAVED_VERSION_KEY) || DEFAULT_VERSION;
}

function populateVersionPicker(installedSet) {
  const sel = $('version-select');
  if (!sel) return;
  sel.innerHTML = '';
  for (const v of SUPPORTED_VERSIONS) {
    const opt = document.createElement('option');
    opt.value = v;
    // ✓ marker if the user has already set up this version, so the picker
    // doubles as a "what's installed" indicator.
    const installed = installedSet && installedSet.has(v);
    const installedMark = installed ? ' ✓' : '';
    opt.textContent = v === DEFAULT_VERSION
      ? `${v} (latest)${installedMark}`
      : `${v}${installedMark}`;
    sel.appendChild(opt);
  }
  const saved = localStorage.getItem(SAVED_VERSION_KEY) || DEFAULT_VERSION;
  sel.value = SUPPORTED_VERSIONS.includes(saved) ? saved : DEFAULT_VERSION;
  sel.addEventListener('change', () => {
    localStorage.setItem(SAVED_VERSION_KEY, sel.value);
    loadState();   // re-resolve "is this version installed?" for the new pick
  });
}

// ───── Status helpers ───────────────────────────────────────────
function setStatus(text, kind) {
  statusLine.textContent = text || '';
  statusLine.classList.remove('ok', 'error', 'working');
  if (kind) statusLine.classList.add(kind);
}

function setProgress(pct /* null = indeterminate, 0..100 = bar, -1 = hidden */) {
  if (pct === -1) {
    progress.hidden = true;
    progress.setAttribute('aria-valuenow', '0');
    return;
  }
  progress.hidden = false;
  if (pct === null) {
    progressFill.classList.add('indeterminate');
    progressFill.style.width = '';
    progress.removeAttribute('aria-valuenow');
  } else {
    progressFill.classList.remove('indeterminate');
    const n = Math.min(100, Math.max(0, Math.round(pct)));
    progressFill.style.width = n + '%';
    progress.setAttribute('aria-valuenow', String(n));
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
  const picked = getPickedVersion();
  try {
    installed = await invoke('read_state', { version: picked });
  } catch (_) { installed = null; }

  // Refresh picker labels with ✓ markers for installed versions.
  const installedSet = new Set(installed?.installed_profiles || []);
  const sel = $('version-select');
  if (sel) {
    Array.from(sel.options).forEach(opt => {
      const v = opt.value;
      const mark = installedSet.has(v) ? ' ✓' : '';
      opt.textContent = v === DEFAULT_VERSION
        ? `${v} (latest)${mark}`
        : `${v}${mark}`;
    });
  }

  renderState();
  try {
    const path = await invoke('project_path');
    const el = $('proj-path');
    if (el) el.textContent = path;
  } catch (_) { /* ignore */ }
}

function renderState() {
  const picked = getPickedVersion();
  const isInstalled = installed && installed.mc_version === picked;
  if (isInstalled) {
    setStatus('', null);
  } else {
    setStatus(`First launch of ${picked} — click PLAY (~200 MB, takes 2–3 min)`, null);
  }
  setProgress(-1);
}

// ───── The smart PLAY button ────────────────────────────────────
// One click flow:
//   1. If installed.mc_version !== pickedVersion → run setup → wait
//   2. Then run launch
playBtn.addEventListener('click', async () => {
  if (busy) return;
  await playFlow();
});

async function playFlow() {
  const username = (usernameInput.value || '').trim() || 'Player';
  const version = getPickedVersion();
  const heap = parseInt(($('opt-heap')?.value) || '4096', 10);
  const gc = ($('opt-gc')?.value) || 'g1';

  clearLog();

  // Phase 1: setup if this version's profile isn't installed yet. Each
  // version has its own isolated profile dir, so switching versions just
  // means "set up that version once". Re-picking a previously installed
  // version is free.
  const needsSetup = !(installed?.installed_profiles || []).includes(version);
  if (needsSetup) {
    setBusy(true, 'SETTING UP…');
    setStatus(`Downloading Minecraft ${version} into its own profile…`, 'working');
    setProgress(null);
    try {
      const code = await invoke('setup_client', { username, version });
      if (code !== 0) {
        setStatus(`Setup failed (exit ${code}) — open Settings → Logs`, 'error');
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
    try { installed = await invoke('read_state', { version }); } catch (_) {}
  }

  // Phase 2: launch — pass version through so client.py routes to the right
  // per-version profile (mods/saves/options come from that profile).
  setBusy(true, 'LAUNCHING…');
  setStatus(`Starting Minecraft ${version}…`, 'working');
  setProgress(null);
  try {
    const code = await invoke('launch_game', { heapMb: heap, gc, username, version });
    setProgress(-1);
    if (code === 0) {
      setStatus('Game closed cleanly.', 'ok');
    } else {
      setStatus(`Game exited with code ${code} — Settings → Logs`, 'error');
    }
  } catch (e) {
    setStatus('Launch failed: ' + e, 'error');
    setProgress(-1);
  }
  setBusy(false);
}

// ───── Friendly status parsing ──────────────────────────────────
function friendlyStatusFor(line) {
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

listen('python-output', (event) => {
  const { kind, line } = event.payload;
  appendLog(line, kind);
  if (!busy) return;
  const nice = friendlyStatusFor(line);
  if (nice) setStatus(nice, 'working');
  const pct = tryParseProgress(line);
  if (pct !== null) setProgress(pct);
});

// ───── Logs (inside Settings → Logs tab) ────────────────────────
const logView = $('log-view');

function appendLog(line, kind) {
  if (!logView) return;
  const div = document.createElement('div');
  div.className = 'log-line ' + (kind || 'stdout');
  div.textContent = line;
  logView.appendChild(div);
  logView.scrollTop = logView.scrollHeight;
}

function clearLog() {
  if (logView) logView.innerHTML = '';
}

// ───── Microsoft sign-in ────────────────────────────────────────
msBtn.addEventListener('click', async () => {
  if (busy) return;
  if (signedIn) {
    // Click again to sign out — minimal flow: clear UI state. Real token
    // revocation would need a backend command; for now just reset the UI.
    signedIn = false;
    msBtn.classList.remove('signed-in');
    msBtn.textContent = 'Sign in with Microsoft';
    usernameInput.disabled = false;
    return;
  }
  setBusy(true, 'SIGNING IN…');
  setStatus('A browser tab will open — sign in there, then come back', 'working');
  setProgress(null);
  try {
    const code = await invoke('microsoft_login');
    if (code === 0) {
      signedIn = true;
      msBtn.classList.add('signed-in');
      msBtn.textContent = '✓ Signed in — click to sign out';
      usernameInput.disabled = true;
      setStatus('Signed in. Click PLAY to launch.', 'ok');
    } else {
      setStatus('Sign-in cancelled or failed', 'error');
    }
  } catch (e) {
    setStatus('Sign-in failed: ' + e, 'error');
  }
  setProgress(-1);
  setBusy(false);
});

// ───── Settings dialog open/close ───────────────────────────────
openSettings.addEventListener('click', () => {
  settingsDialog.showModal();
  // When opening the Mods tab, refresh the list
  if (document.querySelector('.dlg-tab[aria-selected="true"]')?.dataset.tab === 'mods') {
    refreshMods();
  }
});

closeSettings.addEventListener('click', () => {
  settingsDialog.close();
});

// Close when clicking the backdrop (outside the dialog content)
settingsDialog.addEventListener('click', (e) => {
  if (e.target === settingsDialog) settingsDialog.close();
});

// ───── Dialog tabs (ARIA tabs pattern) ──────────────────────────
const dialogTabs = Array.from(document.querySelectorAll('.dlg-tab'));

function activateDialogTab(tab) {
  dialogTabs.forEach(t => {
    const active = (t === tab);
    t.classList.toggle('active', active);
    t.setAttribute('aria-selected', active ? 'true' : 'false');
    t.tabIndex = active ? 0 : -1;
  });
  document.querySelectorAll('.dlg-page').forEach(p => {
    const active = (p.id === 'dpage-' + tab.dataset.tab);
    p.classList.toggle('active', active);
    p.hidden = !active;
  });
  if (tab.dataset.tab === 'mods') refreshMods();
}

dialogTabs.forEach(tab => {
  tab.addEventListener('click', () => activateDialogTab(tab));
  tab.addEventListener('keydown', (e) => {
    const i = dialogTabs.indexOf(tab);
    let target = null;
    if (e.key === 'ArrowRight') target = dialogTabs[(i + 1) % dialogTabs.length];
    else if (e.key === 'ArrowLeft') target = dialogTabs[(i - 1 + dialogTabs.length) % dialogTabs.length];
    else if (e.key === 'Home') target = dialogTabs[0];
    else if (e.key === 'End') target = dialogTabs[dialogTabs.length - 1];
    if (target) { e.preventDefault(); target.focus(); activateDialogTab(target); }
  });
});

// ───── Mods list ────────────────────────────────────────────────
async function refreshMods() {
  const list = $('mod-list');
  if (!list) return;
  list.innerHTML = '<li class="empty">Scanning…</li>';
  try {
    // Always scope to the currently picked version's profile so the user
    // sees the mods for whatever they're about to play.
    const mods = await invoke('list_mods', { version: getPickedVersion() });
    if (!mods.length) {
      list.innerHTML = '<li class="empty">No mods installed yet for ' + getPickedVersion() + '. Press PLAY on the home screen.</li>';
      return;
    }
    list.innerHTML = '';
    for (const name of mods) {
      const row = document.createElement('li');
      const disabled = name.endsWith('.disabled');
      row.className = 'mod-row' + (disabled ? ' disabled' : '');
      row.innerHTML = `
        <span class="mod-status" aria-hidden="true"></span>
        <span class="mod-name">${name}</span>
      `;
      list.appendChild(row);
    }
  } catch (e) {
    list.innerHTML = '<li class="empty">Error: ' + e + '</li>';
  }
}

$('refresh-mods')?.addEventListener('click', refreshMods);
$('update-mods')?.addEventListener('click', async () => {
  if (busy) return;
  const version = getPickedVersion();
  setBusy(true, 'UPDATING…');
  setStatus(`Refreshing mod stack for ${version}…`, 'working');
  setProgress(null);
  try {
    const code = await invoke('update_mods', { version });
    setStatus(code === 0 ? 'Mods updated.' : `Update failed (exit ${code})`, code === 0 ? 'ok' : 'error');
    await refreshMods();
  } catch (e) {
    setStatus('Update failed: ' + e, 'error');
  }
  setProgress(-1);
  setBusy(false);
});

// ───── Global keyboard shortcuts ────────────────────────────────
document.addEventListener('keydown', (e) => {
  // Enter on the home screen (not inside the dialog, not in the username
  // input — the user might still be typing) fires PLAY.
  if (e.key === 'Enter' && !settingsDialog.open) {
    const tag = document.activeElement?.tagName;
    if (tag !== 'INPUT' && tag !== 'SELECT' && tag !== 'TEXTAREA') {
      e.preventDefault();
      if (!busy) playFlow();
    }
  }
  // Ctrl+, opens settings (matches macOS / VS Code convention)
  if (e.key === ',' && (e.ctrlKey || e.metaKey)) {
    e.preventDefault();
    if (settingsDialog.open) settingsDialog.close();
    else settingsDialog.showModal();
  }
  // Esc closes settings (native <dialog> already does this, but explicit
  // for symmetry with the kbd shortcut list)
});

// Username input: Enter also launches
usernameInput.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !busy && !settingsDialog.open) {
    e.preventDefault();
    playBtn.focus();
    playFlow();
  }
});

// ───── Boot ─────────────────────────────────────────────────────
populateVersionPicker();
loadState();
