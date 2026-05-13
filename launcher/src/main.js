// Shadow Client launcher — front-end glue.
//
// Design goal: a fresh user opens the app, sees ONE input (username), ONE
// button (PLAY), and a small version picker. Click PLAY → game launches.
// Everything else (RAM, GC, mods list, logs, shortcuts) lives behind the
// gear icon in the top bar.

// Defensive Tauri IPC binding. In Tauri 2 the `window.__TAURI__` global is
// only exposed when `app.withGlobalTauri: true` is set in tauri.conf.json
// (it defaults to false in v2, unlike v1). If we don't guard this, accessing
// `.core` on an undefined global crashes the entire script at line 1 and the
// whole UI fails to wire up — empty dropdown, dead PLAY button, dead settings
// gear, every event listener never attached. Catching the missing global
// here lets the UI at least render so we can show a clear "this build is
// broken" message instead of a silent black screen.
const tauriGlobal = window.__TAURI__;
if (!tauriGlobal) {
  console.error('[shadow] window.__TAURI__ is undefined — IPC will fail. '
    + 'Check that tauri.conf.json has app.withGlobalTauri: true and that '
    + 'this page is actually loaded inside the Tauri webview, not a browser.');
}
const invoke = (tauriGlobal && tauriGlobal.core && tauriGlobal.core.invoke)
  || (() => Promise.reject(new Error(
      'Tauri IPC unavailable — launcher needs to be reinstalled. '
      + 'Download the latest .exe from the Shadow Client website.')));
const listen = (tauriGlobal && tauriGlobal.event && tauriGlobal.event.listen)
  || (() => Promise.resolve(() => {}));   // no-op unlisten

// ───── Supported Minecraft versions ─────────────────────────────
// We fetch Mojang's version manifest at boot and filter to every release
// >= 1.21 (covers both the 1.21.x line AND the year-based 26.x line they
// switched to after 1.21.11). The list refreshes every 24h via localStorage,
// so new Mojang releases appear in the picker automatically — no launcher
// update needed.
//
// FALLBACK_VERSIONS is the offline / first-launch backup: if the manifest
// fetch fails (no internet, Mojang's CDN down, CSP blocked), we render this
// hardcoded list instead. Keep it newest-first so latest is on top.
const MANIFEST_URLS = [
  'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json',
  'https://launchermeta.mojang.com/mc/game/version_manifest.json',
];
const FALLBACK_VERSIONS = [
  '26.1',
  '1.21.11', '1.21.10', '1.21.9', '1.21.8', '1.21.7', '1.21.6',
  '1.21.5', '1.21.4', '1.21.3', '1.21.2', '1.21.1', '1.21',
];
const SAVED_VERSION_KEY    = 'shadowclient.version';
const MANIFEST_CACHE_KEY   = 'shadowclient.mojangManifest';
const MANIFEST_CACHE_TTL_MS = 24 * 60 * 60 * 1000;   // 24 hours

// Runtime list populated by populateVersionPicker(). DEFAULT_VERSION is
// always the first entry (i.e. the newest version per Mojang's chronological
// ordering after filtering to releases).
let SUPPORTED_VERSIONS = [...FALLBACK_VERSIONS];
let DEFAULT_VERSION    = FALLBACK_VERSIONS[0];

/** True if the version id is 1.21 or newer.
 *
 *  Mojang historically used semver-ish "1.X.Y", then jumped to a year-based
 *  scheme ("26.1") after 1.21.11. Both lines should appear in the picker; we
 *  treat any major >= 22 as "year-based and therefore newer than 1.21",
 *  which leaves safe headroom if Mojang releases 1.22 somehow.
 */
function isVersionAtLeast121(id) {
  const m = String(id).match(/^(\d+)\.(\d+)(?:\.\d+)?$/);
  if (!m) return false;
  const major = parseInt(m[1], 10);
  const minor = parseInt(m[2], 10);
  if (major >= 22) return true;                  // year-based: 26.1, 27.x, …
  if (major === 1 && minor >= 21) return true;   // 1.21, 1.21.1, …, 1.21.11
  return false;
}

/** Pull Mojang's version manifest, filter to releases >= 1.21, and cache for
 *  24h. Falls back to FALLBACK_VERSIONS on any failure (offline, CSP, JSON
 *  parse error, etc.) so the launcher always boots into a usable picker.
 */
async function fetchSupportedVersions() {
  // Cached?
  try {
    const c = JSON.parse(localStorage.getItem(MANIFEST_CACHE_KEY) || 'null');
    if (c && c.ts && Date.now() - c.ts < MANIFEST_CACHE_TTL_MS
        && Array.isArray(c.versions) && c.versions.length > 0) {
      return c.versions;
    }
  } catch (_) { /* corrupt cache — fall through and refetch */ }

  for (const url of MANIFEST_URLS) {
    try {
      const controller = new AbortController();
      const t = setTimeout(() => controller.abort(), 8000);
      const r = await fetch(url, { signal: controller.signal });
      clearTimeout(t);
      if (!r.ok) continue;
      const data = await r.json();
      // Mojang lists newest first; keeping that order means the dropdown
      // shows latest at the top with no further sorting.
      const versions = (data.versions || [])
        .filter(v => v.type === 'release')
        .map(v => v.id)
        .filter(isVersionAtLeast121);
      if (versions.length > 0) {
        localStorage.setItem(MANIFEST_CACHE_KEY,
          JSON.stringify({ ts: Date.now(), versions }));
        return versions;
      }
    } catch (e) {
      console.warn(`[shadow] manifest fetch failed at ${url}:`, e);
    }
  }
  console.warn('[shadow] all manifest fetches failed — using offline fallback list');
  return FALLBACK_VERSIONS;
}

// ───── DOM refs (resolved on DOMContentLoaded) ──────────────────
const $ = (id) => document.getElementById(id);

const playBtn        = $('play-btn');
const playText       = playBtn.querySelector('.play-text');
const playIcon       = playBtn.querySelector('.play-icon');
const statusLine     = $('status-line');
const progress       = $('progress');
const progressFill   = $('progress-fill');
const usernameInput  = $('username-input');
const openSettings   = $('open-settings');
const closeSettings  = $('close-settings');
const settingsDialog = $('settings-dialog');
// Account widget (top-right corner)
const accountBtn     = $('account-btn');
const accountAvatar  = $('account-avatar');
const accountLabel   = $('account-label');
const accountMenu    = $('account-menu');
const accountAction  = $('account-action');
const accountMenuName= $('account-menu-name');
const accountMenuMode= $('account-menu-mode');

// ───── State ────────────────────────────────────────────────────
let installed = null;
let busy = false;
let account = null;   // { username, uuid, user_type } | null
let signedIn = false;

function getPickedVersion() {
  const sel = $('version-select');
  if (sel && sel.value) return sel.value;
  return localStorage.getItem(SAVED_VERSION_KEY) || DEFAULT_VERSION;
}

// We attach the `change` listener exactly once on the select element. Calling
// populateVersionPicker multiple times (e.g. sync from fallback, then async
// after Mojang manifest arrives) used to add duplicate listeners.
let versionPickerChangeWired = false;

function populateVersionPicker(installedSet) {
  const sel = $('version-select');
  if (!sel) return;
  sel.innerHTML = '';
  for (const v of SUPPORTED_VERSIONS) {
    const opt = document.createElement('option');
    opt.value = v;
    const installed = installedSet && installedSet.has(v);
    const installedMark = installed ? ' ✓' : '';
    opt.textContent = v === DEFAULT_VERSION
      ? `${v} (latest)${installedMark}`
      : `${v}${installedMark}`;
    sel.appendChild(opt);
  }
  // Restore the user's last pick if it's still on the list.
  const saved = localStorage.getItem(SAVED_VERSION_KEY) || DEFAULT_VERSION;
  sel.value = SUPPORTED_VERSIONS.includes(saved) ? saved : DEFAULT_VERSION;
  if (!versionPickerChangeWired) {
    versionPickerChangeWired = true;
    sel.addEventListener('change', () => {
      localStorage.setItem(SAVED_VERSION_KEY, sel.value);
      loadState();
      refreshStats();   // version determines profile → render distance → FPS estimate
    });
  }
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
    // Fresh ETA baseline — each PLAY click starts from zero.
    resetEta();
  } else {
    playText.textContent = 'PLAY';
    playIcon.textContent = '▶';
    // After a long-running task finishes (setup downloaded mods, launch
    // unpacked a JDK, etc.) re-pull the stats so the tiles + footer
    // reflect the new on-disk reality.
    refreshStats();
  }
  updateFooter();
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

// ───── ETA tracker ─────────────────────────────────────────────
// Records when a long task starts + the most recent progress %, computes
// "time remaining" so we can append it to status lines. Resets when the
// user clicks PLAY (in setBusy(true)) so the ETA always reflects the
// current setup run, not some stale baseline from earlier.
let etaStartedAt = 0;          // ms epoch
let etaLastProgress = -1;      // 0-100
function resetEta() { etaStartedAt = Date.now(); etaLastProgress = -1; }
function formatEta(pct) {
  if (pct <= etaLastProgress || pct < 1 || pct >= 100) return '';
  etaLastProgress = pct;
  const elapsedMs = Date.now() - etaStartedAt;
  if (elapsedMs < 4000) return '';     // first 4s — too early to extrapolate
  const totalMs = elapsedMs / (pct / 100);
  const remainingMs = totalMs - elapsedMs;
  if (remainingMs < 1000) return '';
  return formatDuration(remainingMs);
}
function formatDuration(ms) {
  const s = Math.round(ms / 1000);
  if (s < 60)  return `~${s}s remaining`;
  const m = Math.round(s / 60);
  if (m < 60)  return `~${m} min remaining`;
  const h = Math.floor(m / 60);
  const mm = m % 60;
  return `~${h}h ${mm}m remaining`;
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
  const pct = tryParseProgress(line);
  if (pct !== null) setProgress(pct);
  if (nice) {
    // Append ETA when we have a meaningful one. Don't overwrite the
    // friendly status with raw progress numbers — those still go to
    // the detail log via appendLog above.
    const eta = pct !== null ? formatEta(pct) : '';
    setStatus(eta ? `${nice}  (${eta})` : nice, 'working');
  }
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

// ───── Time-of-day greeting (uses the user's local timezone) ─
// Date.getHours() returns the hour in the user's local timezone, so this
// works automatically — no opt-in required, no IP geolocation API call.
function getTimeGreeting() {
  const h = new Date().getHours();
  if (h >= 5  && h < 12) return 'Good morning,';
  if (h >= 12 && h < 17) return 'Good afternoon,';
  if (h >= 17 && h < 22) return 'Good evening,';
  return 'Up late,';   // 22:00 – 04:59
}

function renderGreeting() {
  const timeEl = document.getElementById('greeting-time');
  const nameEl = document.getElementById('greeting-name');
  if (timeEl) timeEl.textContent = getTimeGreeting();
  if (nameEl) {
    // Prefer the signed-in MS username; fall back to whatever's in the
    // offline username input; finally fall back to a generic "Player".
    const name =
      (account && account.user_type === 'msa' && account.username) ||
      (usernameInput && usernameInput.value && usernameInput.value.trim()) ||
      'Player';
    nameEl.textContent = name;
  }
}
// Re-render greeting once per minute so it transitions correctly if the
// user has the launcher open across an hour boundary (e.g. they opened
// at 4:59 PM and it should flip to "Good evening" at 5:00).
setInterval(renderGreeting, 60_000);

// ───── Account widget (top-right corner) ───────────────────────
// Feather-style: small pill that defaults to "Sign in". Clicking it
// opens a dropdown menu with the sign-in action and a sign-out option
// once authenticated. We never push MS sign-in onto users — they have
// to deliberately click the corner widget for the prompt to appear.
// This dodges the "looks like a phishing popup on first launch"
// problem without taking online-mode multiplayer away from users who
// actually want it.

async function loadAccount() {
  try {
    account = await invoke('read_account');
  } catch (_) { account = null; }
  renderAccount();
}

function renderAccount() {
  const isMsa = !!(account && account.user_type === 'msa' && account.username);
  signedIn = isMsa;
  if (isMsa) {
    accountBtn.classList.add('signed-in');
    accountAvatar.textContent = account.username.charAt(0).toUpperCase();
    accountLabel.textContent  = account.username;
    accountMenuName.textContent = account.username;
    accountMenuMode.textContent = 'Microsoft account · online play enabled';
    accountAction.textContent   = 'Sign out';
    accountAction.classList.add('danger');
    if (usernameInput && !usernameInput.dataset.userEdited) {
      usernameInput.value = account.username;
    }
  } else {
    // Offline-mode display shows the user's chosen offline name instead
    // of a generic "Sign in" so the corner widget feels personalised.
    const offlineName = (usernameInput?.value || 'Player').trim() || 'Player';
    accountBtn.classList.remove('signed-in');
    accountAvatar.textContent = offlineName.charAt(0).toUpperCase();
    accountLabel.textContent  = offlineName;
    accountMenuName.textContent = offlineName;
    accountMenuMode.textContent = 'Offline mode';
    accountAction.textContent   = 'Sign in with Microsoft';
    accountAction.classList.remove('danger');
  }
  renderGreeting();  // greeting tracks the same name shown in the corner
}

// Menu toggle
function setAccountMenuOpen(open) {
  accountBtn.setAttribute('aria-expanded', open ? 'true' : 'false');
  accountMenu.hidden = !open;
}

accountBtn.addEventListener('click', (e) => {
  e.stopPropagation();
  const open = accountBtn.getAttribute('aria-expanded') !== 'true';
  setAccountMenuOpen(open);
});

// Click-outside dismiss + Esc dismiss for keyboard users
document.addEventListener('click', (e) => {
  if (!accountMenu.hidden && !accountMenu.contains(e.target) && e.target !== accountBtn) {
    setAccountMenuOpen(false);
  }
});
document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && !accountMenu.hidden) setAccountMenuOpen(false);
});

// The single action in the menu does double duty: sign in if not signed
// in, sign out if you are.
accountAction.addEventListener('click', async () => {
  if (busy) return;
  setAccountMenuOpen(false);
  if (signedIn) {
    await doSignOut();
  } else {
    await doSignIn();
  }
});

async function doSignIn() {
  setBusy(true, 'SIGNING IN…');
  setStatus('A browser tab will open — sign in with your Microsoft account there, then come back', 'working');
  setProgress(null);
  try {
    const code = await invoke('microsoft_login');
    if (code === 0) {
      await loadAccount();
      if (signedIn && account?.username) {
        setStatus(`Signed in as ${account.username}.`, 'ok');
      } else {
        setStatus('Signed in.', 'ok');
      }
    } else {
      setStatus('Sign-in cancelled or failed', 'error');
    }
  } catch (e) {
    setStatus('Sign-in failed: ' + e, 'error');
  }
  setProgress(-1);
  setBusy(false);
}

async function doSignOut() {
  setBusy(true, 'SIGNING OUT…');
  setStatus('Signing out…', 'working');
  setProgress(null);
  const fallbackName = (usernameInput.value || '').trim() || 'Player';
  try {
    const code = await invoke('logout_account', { username: fallbackName });
    if (code === 0) {
      await loadAccount();
      setStatus('Signed out.', 'ok');
    } else {
      setStatus(`Sign-out failed (exit ${code})`, 'error');
    }
  } catch (e) {
    setStatus('Sign-out failed: ' + e, 'error');
  }
  setProgress(-1);
  setBusy(false);
}

// Track whether the user has touched the username field directly so we
// don't overwrite their typed name with the MS account name on re-render.
// Also re-render the greeting + corner widget when the name changes so
// they stay in sync with what the user typed.
usernameInput?.addEventListener('input', () => {
  usernameInput.dataset.userEdited = '1';
  if (!signedIn) {
    renderAccount();   // updates the corner pill + greeting in one shot
  } else {
    renderGreeting();
  }
});

// ───── Stat tiles + status footer ──────────────────────────────
async function refreshStats() {
  // Mod count — scoped to the currently-picked version's profile.
  try {
    const mods = await invoke('list_mods', { version: getPickedVersion() });
    const el = document.getElementById('stat-mods');
    if (el) el.textContent = String(mods.length || 0);
  } catch (_) { /* leave dash */ }

  // Java major + distribution. Uses diagnostics' java_major; the
  // distribution name is best-effort guess from the path.
  try {
    const d = await invoke('diagnostics');
    const el = document.getElementById('stat-java');
    if (el) {
      if (d.java_major > 0) {
        el.textContent = `Java ${d.java_major}`;
      } else {
        el.textContent = 'none';
      }
    }
  } catch (_) {}

  // Disk usage — runs on a tokio blocking thread Rust-side so it doesn't
  // freeze the UI when game_dir is ~1 GB.
  try {
    const mb = await invoke('disk_usage_mb');
    const el = document.getElementById('stat-disk');
    if (el) {
      if (mb < 1024) {
        el.textContent = `${mb} MB`;
      } else {
        el.textContent = `${(mb / 1024).toFixed(1)} GB`;
      }
    }
  } catch (_) {}

  // FPS estimate — combines CPU core count (proxy for CPU tier), the
  // active profile's render distance from options.txt, and the launcher's
  // heap setting. Result is a single number like 420 → rendered as "420+"
  // because the real number can swing ±30% on scene complexity alone.
  try {
    const heap = parseInt(($('opt-heap')?.value) || '4096', 10);
    const fps = await invoke('estimate_fps', {
      version: getPickedVersion(),
      heapMb: heap,
    });
    const el = document.getElementById('stat-fps');
    if (el) el.textContent = `${fps}+`;
  } catch (_) { /* leave dash */ }

  updateFooter();
}

function updateFooter() {
  const footerText = document.getElementById('footer-text');
  if (!footerText) return;
  const parts = [];
  parts.push(busy ? 'Working…' : 'Ready');
  const java = document.getElementById('stat-java')?.textContent;
  if (java && java !== '—' && java !== 'none') parts.push(java);
  parts.push(getPickedVersion());
  const mods = document.getElementById('stat-mods')?.textContent;
  if (mods && mods !== '—' && mods !== '0') parts.push(`${mods} mods`);
  footerText.textContent = parts.join('  ·  ');
}

// ───── Friends list ─────────────────────────────────────────────
const friendsListEl = $('friends-list');
const friendsCountEl = $('friends-count');
const friendsAddForm = $('friends-add-form');
const friendsAddInput = $('friends-add-input');

async function refreshFriends() {
  if (!friendsListEl) return;
  try {
    const friends = await invoke('friends_list');
    renderFriends(friends);
  } catch (e) {
    console.warn('[shadow] friends_list failed:', e);
  }
}

function renderFriends(friends) {
  if (!friendsListEl) return;
  friendsListEl.innerHTML = '';
  if (friendsCountEl) friendsCountEl.textContent = String(friends.length);
  if (!friends.length) {
    const empty = document.createElement('li');
    empty.className = 'friends-empty';
    empty.textContent = 'No friends added yet';
    friendsListEl.appendChild(empty);
    return;
  }
  for (const f of friends) {
    const row = document.createElement('li');
    row.className = 'friend-row';
    const avatar = document.createElement('div');
    avatar.className = 'friend-avatar';
    avatar.setAttribute('aria-hidden', 'true');
    avatar.textContent = f.username.charAt(0).toUpperCase();
    const name = document.createElement('div');
    name.className = 'friend-name';
    name.textContent = f.username;
    name.title = f.username;
    const remove = document.createElement('button');
    remove.className = 'friend-remove';
    remove.type = 'button';
    remove.setAttribute('aria-label', `Remove ${f.username}`);
    remove.textContent = '×';
    remove.addEventListener('click', async () => {
      try {
        const updated = await invoke('friends_remove', { username: f.username });
        renderFriends(updated);
      } catch (e) {
        console.warn('[shadow] friends_remove failed:', e);
      }
    });
    row.appendChild(avatar);
    row.appendChild(name);
    row.appendChild(remove);
    friendsListEl.appendChild(row);
  }
}

friendsAddForm?.addEventListener('submit', async (e) => {
  e.preventDefault();
  if (!friendsAddInput) return;
  const username = friendsAddInput.value.trim();
  if (!username) return;
  try {
    const updated = await invoke('friends_add', { username });
    renderFriends(updated);
    friendsAddInput.value = '';
    friendsAddInput.focus();
  } catch (err) {
    // Show the error inline as a status briefly so the user knows why
    // their add failed (duplicate / invalid char / too long).
    const old = friendsAddInput.placeholder;
    friendsAddInput.placeholder = String(err).slice(0, 40);
    friendsAddInput.classList.add('error');
    setTimeout(() => {
      friendsAddInput.placeholder = old;
      friendsAddInput.classList.remove('error');
    }, 3000);
  }
});

// ───── Quick action tiles ───────────────────────────────────────
document.getElementById('action-mods')?.addEventListener('click', () => {
  // Open Settings dialog and switch to the Mods tab.
  settingsDialog.showModal();
  const tab = document.querySelector('.dlg-tab[data-tab="mods"]');
  if (tab) activateDialogTab(tab);
});

document.getElementById('action-cosmetics')?.addEventListener('click', () => {
  setStatus(
    'Cosmetics live inside the Shadow HUD overlay — those features ' +
    'land in a follow-up release.', 'working'
  );
  setTimeout(() => setStatus('', null), 5000);
});

document.getElementById('action-hud-editor')?.addEventListener('click', () => {
  setStatus(
    'After launching, press Right Shift in-game to open the HUD editor.',
    'working'
  );
  setTimeout(() => setStatus('', null), 6000);
});

document.getElementById('action-folder')?.addEventListener('click', async () => {
  try {
    await invoke('open_folder');
  } catch (e) {
    setStatus('Couldn\'t open folder: ' + e, 'error');
  }
});

// When the user changes the RAM allocation in Settings, the FPS estimate
// shifts (low heap = stutter penalty). Re-pull stats. Debounced so we
// don't fire IPC on every keystroke while they're typing.
let heapInputTimer = null;
$('opt-heap')?.addEventListener('input', () => {
  clearTimeout(heapInputTimer);
  heapInputTimer = setTimeout(refreshStats, 400);
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
  tab.addEventListener('click', () => {
    activateDialogTab(tab);
    if (tab.dataset.tab === 'diag') refreshDiagnostics();
  });
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

// ───── Diagnostics view ────────────────────────────────────────
async function refreshDiagnostics() {
  const out = $('diag-output');
  if (!out) return;
  out.textContent = 'Loading…';
  try {
    const d = await invoke('diagnostics');
    out.textContent = renderDiagnostics(d);
  } catch (e) {
    out.textContent = 'diagnostics command unavailable in this build: ' + e;
  }
}

function renderDiagnostics(d) {
  // Plain-text format — fits in a chat message and is easy to grep.
  const py = d.python || {};
  const lines = [
    `Launcher version:        ${d.launcher_version}`,
    `Executable:              ${d.exe_path}`,
    `Executable directory:    ${d.exe_dir}`,
    `Resolved project root:   ${d.project_root}`,
    `Has client.py?           ${d.project_root_has_client_py ? 'YES' : 'NO'}`,
    `Working directory:       ${d.cwd}`,
    `Python:                  ${py.ok ? '✓' : '✗'} ${py.detail || ''}`,
    '',
    'Files found in project root:',
    ...(d.resource_files_present.length
        ? d.resource_files_present.map(f => `  ✓ ${f}`)
        : ['  (none — install is incomplete)']),
    '',
    'Candidate paths searched:',
    ...d.candidates_checked.map(p => `  ${d.candidates_with_client_py.includes(p) ? '✓' : '·'} ${p}`),
  ];
  return lines.join('\n');
}

$('diag-refresh')?.addEventListener('click', refreshDiagnostics);
$('diag-copy')?.addEventListener('click', async () => {
  const text = $('diag-output')?.textContent || '';
  try {
    await navigator.clipboard.writeText(text);
    const btn = $('diag-copy');
    if (btn) {
      const orig = btn.textContent;
      btn.textContent = 'Copied!';
      setTimeout(() => { btn.textContent = orig; }, 1500);
    }
  } catch (e) {
    console.warn('clipboard write failed:', e);
  }
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
// Order matters here. The first paint should be instant — no awaiting any
// network or IPC before the dropdown and PLAY button are visible. After
// the UI is up, we run the slower checks one at a time so a slow machine
// isn't hammered with five concurrent async tasks during startup.

// 1. Synchronous paint — UI is alive immediately.
populateVersionPicker();
renderGreeting();
updateFooter();

// 2. Backend reads (instant, only Tauri IPC, no network).
loadState();
loadAccount();
refreshStats();
refreshFriends();

// 3. Background tasks — kicked off but not awaited, and deliberately
//    delayed by 200ms so they happen AFTER the first paint settles.
//    Each one is wrapped so a failure can't cascade into the others.
setTimeout(() => {
  // Mojang manifest enrichment — fetches the live version list.
  (async () => {
    try {
      const versions = await fetchSupportedVersions();
      if (versions.length > 0 && JSON.stringify(versions) !== JSON.stringify(SUPPORTED_VERSIONS)) {
        SUPPORTED_VERSIONS = versions;
        DEFAULT_VERSION    = versions[0];
        populateVersionPicker();
        loadState();
      }
    } catch (e) { console.warn('[shadow] manifest enrich failed:', e); }
  })();

  // Python availability probe. The v0.3.0+ Rust port doesn't actually
  // need Python — check_python always returns ok=true, so this banner
  // never fires. Kept around so the JS keeps working when running
  // against older Rust builds that DID need Python.
  (async () => {
    try {
      const out = await invoke('check_python');
      if (!out || !out.ok) showPythonBanner(out);
    } catch (_) { /* old Rust build, silent */ }
  })();

  // Housekeeping: nuke stale auto-update installers from %TEMP%.
  invoke('sweep_update_temp').catch(() => {});

  // Self-update check.
  checkForLauncherUpdate();
}, 200);

function showPythonBanner(probeResult) {
  const banner = document.createElement('div');
  banner.className = 'startup-banner error';
  banner.setAttribute('role', 'alert');
  banner.innerHTML = `
    <div class="startup-banner-title">⚠ Python 3.11+ not found</div>
    <div class="startup-banner-body">
      Shadow Client uses Python under the hood to download Minecraft. The
      first PLAY click will fail until you install it.<br>
      <a href="https://www.python.org/downloads/" target="_blank" rel="noopener">
        Download Python (python.org)
      </a> — pick "Add to PATH" during install, then reopen this launcher.
    </div>
  `;
  document.body.appendChild(banner);
}

// ───── Launcher self-update check ──────────────────────────────
// Reads our own version from the brand label. We look in TWO ways so a
// future HTML edit that drops the id can't silently break auto-update:
//   1. `#version-label` (the canonical id)
//   2. `.brand-sub` (the css class on the same element)
// Hit on either is fine. If both miss (someone gutted the topbar), we
// fall back to a hardcoded version string so update can still recover.
const HARDCODED_VERSION = '0.3.10';
function resolveCurrentVersion() {
  const el = document.getElementById('version-label')
          || document.querySelector('.brand-sub');
  const t = (el?.textContent || '').replace(/^v/, '').trim();
  return t || HARDCODED_VERSION;
}
const CURRENT_LAUNCHER_VERSION = resolveCurrentVersion();
const LAUNCHER_UPDATE_URL = 'https://api.github.com/repos/bluestatic11/shadow-client/releases/latest';

async function checkForLauncherUpdate() {
  if (!CURRENT_LAUNCHER_VERSION) return;
  try {
    const r = await fetch(LAUNCHER_UPDATE_URL);
    if (!r.ok) return;
    const data = await r.json();
    const latest = (data.tag_name || '').replace(/^v/, '').trim();
    if (!latest) return;
    if (compareSemver(latest, CURRENT_LAUNCHER_VERSION) <= 0) return;

    // Find the right platform installer in the release assets.
    const ua = navigator.userAgent.toLowerCase();
    const platform = ua.includes('mac') ? 'mac'
                  : ua.includes('linux') ? 'linux'
                  : 'windows';
    const patterns = {
      windows: /x64-setup\.exe$/i,
      mac:     /universal\.dmg$/i,
      linux:   /amd64\.AppImage$/i,
    };
    const asset = (data.assets || []).find(a => patterns[platform].test(a.name));
    if (!asset) return;

    // FIRE-AND-FORGET auto-update. No button, no prompt — just fetch the
    // installer and run it. The user sees a status banner so they know what's
    // happening, but they don't need to click anything.
    autoInstallUpdate(latest, asset.browser_download_url);
  } catch (e) {
    console.warn('[shadow] update check failed:', e);
  }
}

async function autoInstallUpdate(latest, downloadUrl) {
  // Status-only banner — no buttons. The user sees the launcher updating,
  // doesn't get asked to do anything, doesn't even need to click "OK".
  const banner = document.createElement('div');
  banner.className = 'startup-banner update auto';
  banner.setAttribute('role', 'status');
  banner.setAttribute('aria-live', 'polite');
  banner.innerHTML = `
    <div class="startup-banner-title" id="auto-update-title">▲ Updating to v${latest}…</div>
    <div class="startup-banner-body" id="auto-update-body">
      Downloading the new launcher. The window will close in a few seconds —
      Shadow Client will reopen automatically when the installer finishes.
    </div>
  `;
  document.body.appendChild(banner);

  try {
    await invoke('install_update', { url: downloadUrl });
    // If install_update returns success the app is about to exit and we
    // won't reach this line. If we do, the spawn went through but exit
    // hasn't fired yet — just hold the banner.
  } catch (e) {
    // Update failed — silently drop the banner after 6 seconds so the user
    // can keep playing with the current version. Update will retry on the
    // next boot.
    const body = document.getElementById('auto-update-body');
    if (body) {
      body.textContent =
        'Auto-update couldn\'t finish (' + String(e).slice(0, 120) +
        '). Continuing on v' + CURRENT_LAUNCHER_VERSION + '.';
    }
    setTimeout(() => banner.remove(), 6000);
  }
}

/** Compare "1.2.3" vs "1.2.4"-style version strings. Returns >0 if a>b,
 *  <0 if a<b, 0 if equal. Tolerates missing parts ("1.2" == "1.2.0"). */
function compareSemver(a, b) {
  const parse = s => s.split('.').map(p => parseInt(p, 10) || 0);
  const ap = parse(a), bp = parse(b);
  const len = Math.max(ap.length, bp.length);
  for (let i = 0; i < len; i++) {
    const av = ap[i] || 0;
    const bv = bp[i] || 0;
    if (av !== bv) return av - bv;
  }
  return 0;
}

// (showUpdateBanner with manual "Update now" button was removed in v0.3.2 —
//  auto-install replaced the click-to-update flow. The Rust install_update
//  command stays available for callers that want to gate the upgrade.)

async function openExternal(url) {
  try {
    // Tauri 2 shell.open opens the URL in the OS default browser.
    if (tauriGlobal?.shell?.open) {
      await tauriGlobal.shell.open(url);
      return;
    }
    // Fallback: open the URL — Tauri's webview should intercept and route
    // to the system browser. If that fails too, the navigation just stays
    // inside the launcher window (ugly but not broken).
    window.open(url, '_blank');
  } catch (e) {
    console.warn('[shadow] openExternal failed:', e);
  }
}
