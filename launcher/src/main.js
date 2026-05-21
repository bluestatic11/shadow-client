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
  // Re-render so the "You" row in the friends panel reflects the new
  // busy/idle state.
  renderFriends(cachedFriends);
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
  // v0.3.29: lock username only in offline mode. Offline players all share
  // "Player" (their offline UUID is generated from the name, so a fixed
  // name avoids accidental world-save splits between sessions). When the
  // user is signed in with Microsoft we open the field back up so they
  // can rename freely — useful since the MC username change UI is buried
  // and a launcher-side override is faster.
  const hint = document.getElementById('username-hint');
  if (isMsa) {
    accountBtn.classList.add('signed-in');
    accountAvatar.textContent = account.username.charAt(0).toUpperCase();
    accountLabel.textContent  = account.username;
    accountMenuName.textContent = account.username;
    accountMenuMode.textContent = 'Microsoft account · online play enabled';
    accountAction.textContent   = 'Sign out';
    accountAction.classList.add('danger');
    if (usernameInput) {
      usernameInput.removeAttribute('readonly');
      usernameInput.removeAttribute('tabindex');
      // Seed with the Microsoft account name the FIRST time we see it.
      // After that, leave whatever the user typed alone — they're free
      // to rename per their preference.
      if (!usernameInput.dataset.userEdited) {
        usernameInput.value = account.username;
      }
    }
    if (hint) hint.textContent =
      'Editable while signed in — change to whatever you want.';
  } else {
    // Offline mode is always the literal name "Player". No choice, no
    // editing. Keeps every offline-mode user's identity consistent so
    // saves and screenshots don't fragment.
    accountBtn.classList.remove('signed-in');
    accountAvatar.textContent = 'P';
    accountLabel.textContent  = 'Player';
    accountMenuName.textContent = 'Player';
    accountMenuMode.textContent = 'Offline mode';
    accountAction.textContent   = 'Sign in with Microsoft';
    accountAction.classList.remove('danger');
    if (usernameInput) {
      usernameInput.value = 'Player';
      usernameInput.setAttribute('readonly', '');
      usernameInput.setAttribute('tabindex', '-1');
      delete usernameInput.dataset.userEdited;
    }
    if (hint) hint.textContent =
      'Locked in offline mode — sign in with Microsoft to rename.';
  }
  // Shadow Chat status pill — tells the user in-game chat is ready
  // (and reminds them of the keybinds). Same auth signal as everything
  // else, hence updated from here. When offline the pill is CLICKABLE
  // and triggers Microsoft sign-in directly — users were missing the
  // tiny "?" avatar in the corner and getting stuck. The pill in the
  // middle of the home screen is much more discoverable.
  const chatHint = document.getElementById('chat-hint');
  if (chatHint) {
    const text = chatHint.querySelector('.chat-hint-text');
    chatHint.classList.remove('checking');
    if (isMsa) {
      chatHint.classList.add('ready');
      chatHint.classList.remove('disabled', 'actionable');
      chatHint.removeAttribute('role');
      chatHint.removeAttribute('tabindex');
      if (text) text.textContent = 'Shadow Chat: ready · ; chat · V talk';
    } else {
      chatHint.classList.add('disabled', 'actionable');
      chatHint.classList.remove('ready');
      chatHint.setAttribute('role', 'button');
      chatHint.setAttribute('tabindex', '0');
      if (text) text.textContent = '▶ Click to sign in with Microsoft (required for chat)';
    }
  }

  renderGreeting();  // greeting tracks the same name shown in the corner
  renderFriends(cachedFriends);  // "You" row tracks the same name too
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

// v0.3.37: the home-screen chat-hint pill is ALSO a sign-in entry point
// when in offline mode. Users were missing the tiny "?" avatar in the
// corner — this CTA in the middle of the page makes the path obvious.
const chatHintPill = document.getElementById('chat-hint');
if (chatHintPill) {
  const trigger = async () => {
    if (busy) return;
    if (!signedIn) {
      await doSignIn();
      return;
    }
    // Signed-in path: pill launches MC and signals the mod to pop
    // the chat screen open as soon as the world loads — the
    // shadow-chat-command.json IPC file gets written into the
    // profile dir before launch, the mod polls + executes + deletes.
    setStatus('Launching Minecraft — chat opens automatically', 'working');
    try { await invoke('signal_mod', { action: 'open-chat', target: null, version: getPickedVersion() }); }
    catch (e) { console.warn('[shadow] signal_mod open-chat failed:', e); }
    await playFlow();
  };
  chatHintPill.addEventListener('click', trigger);
  chatHintPill.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault();
      trigger();
    }
  });
  // Make it clickable-feeling even when signed in (the prior
  // implementation only added the .actionable class in the offline
  // path). Cursor + role are set per-state in renderAccount(), but
  // we re-mark the signed-in path here so the hover state lands.
  chatHintPill.style.cursor = 'pointer';
}

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

// ───── Latest updates / release-notes card ─────────────────────
const UPDATES_LIST_URL =
  'https://api.github.com/repos/bluestatic11/shadow-client/releases?per_page=5';
const updatesListEl = $('updates-list');

async function fetchLatestUpdates() {
  if (!updatesListEl) return;
  try {
    const r = await fetch(UPDATES_LIST_URL);
    if (!r.ok) throw new Error('HTTP ' + r.status);
    const releases = await r.json();
    renderUpdates(releases || []);
  } catch (e) {
    console.warn('[shadow] release fetch failed:', e);
    const empty = $('updates-empty') || document.createElement('li');
    empty.className = 'updates-empty';
    empty.textContent = 'Updates unavailable (offline?).';
    if (!updatesListEl.contains(empty)) updatesListEl.appendChild(empty);
  }
}

function renderUpdates(releases) {
  if (!updatesListEl) return;
  updatesListEl.innerHTML = '';
  if (!releases.length) {
    const empty = document.createElement('li');
    empty.className = 'updates-empty';
    empty.textContent = 'No releases published yet.';
    updatesListEl.appendChild(empty);
    return;
  }
  for (const rel of releases.slice(0, 5)) {
    updatesListEl.appendChild(buildUpdateCard(rel));
  }
}

/**
 * Build a single "Latest updates" card. We deliberately show ONLY the
 * release headline (the commit subject line) — the full body is mostly
 * dev jargon that's noisier than useful for end users. If they want the
 * deep dive there's a "View on GitHub →" link that opens the release
 * page in their default browser.
 */
function buildUpdateCard(rel) {
  const card = document.createElement('li');
  card.className = 'update-card';

  const head = document.createElement('div');
  head.className = 'update-card-head';
  const ver = document.createElement('span');
  ver.className = 'update-version';
  ver.textContent = rel.tag_name || rel.name || '?';
  const date = document.createElement('span');
  date.className = 'update-date';
  date.textContent = relativeReleaseTime(rel.published_at);
  head.appendChild(ver);
  head.appendChild(date);
  card.appendChild(head);

  // Extract just the first meaningful line of the release body — that's
  // the human-readable summary written at the top of every commit
  // message. Skips empty lines + the auto-generated "## Downloads:"
  // section from the release template.
  const body = (rel.body || '').trim();
  const summaryText = firstReadableLine(body);
  const summary = document.createElement('div');
  summary.className = 'update-summary';
  summary.textContent = summaryText || '(no summary)';
  card.appendChild(summary);

  // "View on GitHub →" link for users who want the full technical
  // changelog. Opens in their default browser via the same helper the
  // auto-update path uses, so no extra wiring.
  if (rel.html_url) {
    const link = document.createElement('a');
    link.className = 'update-link';
    link.textContent = 'View on GitHub →';
    link.href = '#';
    link.addEventListener('click', (e) => {
      e.preventDefault();
      openExternal(rel.html_url);
    });
    card.appendChild(link);
  }
  return card;
}

/**
 * Pull a short, user-readable summary out of a release body.
 *
 * Convention (v0.3.31+): commit messages lead with one or more bullet
 * points describing what changed in user-facing terms ("Added in-game
 * chat", "Minor QoL fixes"). We surface the FIRST such bullet so the
 * update card reads like a release-note snippet instead of a sentence
 * out of context.
 *
 * Falls back through:
 *   1. First markdown bullet ("- ..." or "* ...") whose text isn't
 *      template boilerplate (Windows: / macOS: / Linux: download lines).
 *   2. First plain prose line that isn't a heading / version marker.
 *   3. Whatever the first line of the body happens to be.
 */
function firstReadableLine(body) {
  if (!body) return '';
  const lines = body.split(/\r?\n/);

  // Pass 1: prefer the first user-facing bullet. Most descriptive.
  for (const raw of lines) {
    const line = raw.trim();
    const m = line.match(/^[-*]\s+(.+)$/);
    if (!m) continue;
    const text = m[1].trim();
    // Skip workflow asset list ("- Windows: .exe", etc).
    if (/^(Windows|macOS|Linux):/i.test(text)) continue;
    // Skip ultra-short bullets that read like fragments.
    if (text.length < 4) continue;
    return text;
  }

  // Pass 2: first non-templated prose line.
  for (const raw of lines) {
    const line = raw.trim();
    if (!line) continue;
    if (line.startsWith('#')) continue;          // markdown heading
    if (line.startsWith('**Downloads')) continue; // workflow template
    if (line.startsWith('- ') || line.startsWith('* ')) continue;
    if (/^v\d/i.test(line)) continue;            // duplicate version header
    if (line.startsWith('---')) continue;         // hr separator
    return line;
  }

  return lines[0].trim();
}

function relativeReleaseTime(iso) {
  if (!iso) return '';
  const then = new Date(iso).getTime();
  if (!Number.isFinite(then)) return '';
  const diff = Math.max(0, Math.floor((Date.now() - then) / 1000));
  if (diff < 60)        return 'just now';
  if (diff < 3600)      return `${Math.floor(diff / 60)} min ago`;
  if (diff < 86400)     return `${Math.floor(diff / 3600)} h ago`;
  if (diff < 86400 * 7) return `${Math.floor(diff / 86400)} d ago`;
  return new Date(iso).toLocaleDateString();
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

// Tracks whether the user's MC process is currently running. Set true on
// the "mc-started" Tauri event, false on "mc-exited". Drives the "You"
// row in the friends list.
let mcRunning = false;
let cachedFriends = [];

function renderFriends(friends) {
  if (!friendsListEl) return;
  cachedFriends = friends || [];
  friendsListEl.innerHTML = '';
  if (friendsCountEl) friendsCountEl.textContent = String(cachedFriends.length);

  // Always show the "You" row at the top so the user can see their own
  // live status side-by-side with their friends.
  friendsListEl.appendChild(buildYouRow());

  if (!cachedFriends.length) {
    const empty = document.createElement('li');
    empty.className = 'friends-empty';
    empty.textContent = 'No friends added yet';
    friendsListEl.appendChild(empty);
    return;
  }
  for (const f of cachedFriends) {
    friendsListEl.appendChild(buildFriendRow(f));
  }
}

function buildFriendRow(f) {
  const row = document.createElement('li');
  row.className = 'friend-row friend-row-clickable';
  row.title = `Open chat with ${f.username} (launches Minecraft)`;
  row.appendChild(makeAvatar(f.username));

  const info = document.createElement('div');
  info.className = 'friend-info';
  const name = document.createElement('div');
  name.className = 'friend-name';
  name.textContent = f.username;
  name.title = f.username;
  info.appendChild(name);
  info.appendChild(makeStatusLine(f));
  row.appendChild(info);

  const remove = document.createElement('button');
  remove.className = 'friend-remove';
  remove.type = 'button';
  remove.setAttribute('aria-label', `Remove ${f.username}`);
  remove.textContent = '×';
  remove.addEventListener('click', async (e) => {
    // Don't bubble up to the row's launch-on-click handler.
    e.stopPropagation();
    try {
      const updated = await invoke('friends_remove', { username: f.username });
      renderFriends(updated);
    } catch (err) {
      console.warn('[shadow] friends_remove failed:', err);
    }
  });
  row.appendChild(remove);

  // Click anywhere else on the row → write an IPC signal naming
  // this friend, then launch MC. The mod opens the chat screen on
  // world load and shows a system line pointing at the target so
  // the user knows who they came in to chat with.
  row.addEventListener('click', async () => {
    if (busy) return;
    setStatus(`Launching Minecraft — chat will open targeting ${f.username}`, 'working');
    try {
      await invoke('signal_mod', {
        action: 'open-chat-with',
        target: f.username,
        version: getPickedVersion(),
      });
    } catch (e) { console.warn('[shadow] signal_mod open-chat-with failed:', e); }
    await playFlow();
  });
  return row;
}

function buildYouRow() {
  const row = document.createElement('li');
  row.className = 'friend-row you-row';
  const username = (signedIn && account?.username) ||
    (usernameInput?.value || '').trim() || 'Player';

  row.appendChild(makeAvatar(username));

  const info = document.createElement('div');
  info.className = 'friend-info';
  const name = document.createElement('div');
  name.className = 'friend-name';
  name.innerHTML = `${username} <span class="friend-self-tag">you</span>`;
  info.appendChild(name);

  // Live status from the launcher's own state.
  let status, text;
  if (mcRunning) {
    status = 'playing';
    text = `Playing ${getPickedVersion()}`;
  } else if (busy) {
    status = 'online';
    text = 'In launcher · working';
  } else {
    status = 'online';
    text = 'In launcher';
  }
  info.appendChild(makeStatusLineRaw(status, text));
  row.appendChild(info);
  return row;
}

function makeAvatar(username) {
  const a = document.createElement('div');
  a.className = 'friend-avatar';
  a.setAttribute('aria-hidden', 'true');
  // mc-heads.net returns a 24x24 PNG of the face + hat layer. Errors
  // (network down, username not registered) fall back to the letter
  // placeholder via the <img>'s onerror handler.
  const img = document.createElement('img');
  img.src = `https://mc-heads.net/avatar/${encodeURIComponent(username)}/24`;
  img.alt = '';
  img.loading = 'lazy';
  img.addEventListener('error', () => {
    a.textContent = username.charAt(0).toUpperCase();
    img.remove();
  });
  a.appendChild(img);
  return a;
}

/**
 * Format a launcher identifier from the relay's presence response
 * into a user-facing label. We currently only know about Shadow
 * Client; other launchers might show up if/when they start
 * publishing to the same endpoint.
 */
function formatLauncher(id) {
  if (!id) return '';
  switch (id.toLowerCase()) {
    case 'shadow-client': return 'Shadow Client';
    case 'vanilla':       return 'Vanilla launcher';
    case 'lunar':         return 'Lunar';
    case 'feather':       return 'Feather';
    case 'prism':         return 'Prism';
    case 'modrinth':      return 'Modrinth app';
    case 'atlauncher':    return 'ATLauncher';
    case 'curseforge':    return 'CurseForge';
    case 'multimc':       return 'MultiMC';
    // Sentinel emitted by server-ping detection — we can prove the
    // friend is on that server but the ping protocol doesn't reveal
    // what launcher they used to get there.
    case 'unknown':       return 'launcher unknown';
    default:              return id;
  }
}

function makeStatusLine(f) {
  if (f.status === 'playing') {
    const where = f.server ? ` · ${f.server}` : '';
    const via = f.launcher ? ` · ${formatLauncher(f.launcher)}` : '';
    return makeStatusLineRaw('playing', `Playing Minecraft${where}${via}`);
  }
  if (f.status === 'idle' || f.status === 'online' || f.status === 'in_menu') {
    const via = f.launcher ? ` · ${formatLauncher(f.launcher)} open` : 'Online';
    return makeStatusLineRaw('online', via.trimStart().replace(/^· /, ''));
  }
  // Offline / unknown.
  const text = f.last_seen
    ? `Offline · ${formatRelativeTime(f.last_seen)}`
    : 'Offline · add a server below to detect them on it';
  return makeStatusLineRaw('offline', text);
}

function makeStatusLineRaw(klass, text) {
  const line = document.createElement('div');
  line.className = 'friend-status ' + klass;
  const dot = document.createElement('span');
  dot.className = 'status-dot';
  dot.setAttribute('aria-hidden', 'true');
  const t = document.createElement('span');
  t.className = 'friend-status-text';
  t.textContent = text;
  line.appendChild(dot);
  line.appendChild(t);
  return line;
}

function formatRelativeTime(epochSecs) {
  const diff = Math.max(0, Math.floor(Date.now() / 1000) - epochSecs);
  if (diff < 60)       return 'just now';
  if (diff < 3600)     return `${Math.floor(diff / 60)} min ago`;
  if (diff < 86400)    return `${Math.floor(diff / 3600)} h ago`;
  if (diff < 86400 * 7) return `${Math.floor(diff / 86400)} d ago`;
  return new Date(epochSecs * 1000).toLocaleDateString();
}

// ───── Friend presence sync ────────────────────────────────────
// Two loops:
//   - publishPresence(): tells the relay we're here. Runs every 60 s
//     while either MC is open OR the launcher window is focused. The
//     5-min relay TTL means we can skip ticks during long idle and
//     still re-establish quickly.
//   - pollFriendPresence(): asks the relay which of our friends'
//     UUIDs are currently online. Runs every 30 s; merges results
//     into cachedFriends and re-renders.
//
// Heartbeat needs a Microsoft token so it's a no-op for users in
// offline mode — the Rust side bails silently in that case.

async function publishPresence(playing) {
  try {
    // Server detection — best-effort. We don't know which MC server
    // the user joined inside the game (the launcher only knows what
    // version was launched). Future: have the chat-mod write a
    // current-server.txt next to shadow-chat-auth.json on every join.
    await invoke('presence_heartbeat', {
      playing: !!playing,
      server: null,
    });
  } catch (e) {
    // Don't spam the console on every tick — relay down, offline
    // mode, etc. are all expected states.
    if (window.__presenceLastError !== String(e)) {
      console.warn('[shadow] presence heartbeat failed:', e);
      window.__presenceLastError = String(e);
    }
  }
}

async function pollFriendPresence() {
  if (!cachedFriends.length) return;
  const uuids = cachedFriends.map(f => f.uuid).filter(Boolean);
  if (!uuids.length) return;
  let entries = [];
  try {
    entries = await invoke('presence_query', { uuids });
  } catch (e) {
    console.warn('[shadow] presence_query failed:', e);
    return;
  }
  const byUuid = new Map(entries.map(e => [e.uuid.toLowerCase(), e]));
  let changed = false;
  for (const f of cachedFriends) {
    const e = f.uuid ? byUuid.get(f.uuid.toLowerCase()) : null;
    if (e) {
      const beforeKey = `${f.status}|${f.server}|${f.launcher}|${f.version}`;
      f.status   = e.status;
      f.server   = e.server || null;
      f.version  = e.version || null;
      f.launcher = e.launcher || null;
      f.last_seen = Math.floor((e.lastSeenAt || Date.now()) / 1000);
      const afterKey = `${f.status}|${f.server}|${f.launcher}|${f.version}`;
      if (beforeKey !== afterKey) changed = true;
    } else if (f.status && f.status !== 'offline') {
      // Friend dropped out of the TTL window — flip to offline.
      f.status = null;
      f.server = null;
      f.version = null;
      f.launcher = null;
      changed = true;
    }
  }
  if (changed) renderFriends(cachedFriends);
}

setInterval(() => { publishPresence(mcRunning); }, 60_000);
setInterval(pollFriendPresence, 30_000);
// Kick once on startup so the user doesn't wait 30 s for friends to
// fill in.
setTimeout(() => {
  publishPresence(mcRunning);
  pollFriendPresence();
  pollTrackedServers();
}, 2_000);

// ───── Server-ping friend detection (cross-launcher) ──────────────
// The presence relay only sees Shadow Client users. For friends on
// other launchers (Lunar, Feather, Vanilla, etc.) we fall back to
// Minecraft's standard Server List Ping protocol: for each server in
// the user's tracked-servers list, we ping it every 90 s and check
// if any friend usernames appear in the `players.sample` list. If so,
// we mark them as playing on that server even though we have no
// presence-relay data for them. Launcher field stays "unknown"
// because the Server List Ping protocol doesn't reveal it (and no
// non-Shadow-Client launcher publishes that information to us
// anywhere we can reach).
//
// Tracked-servers list lives in localStorage so the user controls
// what we ping. Default is empty; add via the input under the
// friends panel.

function loadTrackedServers() {
  try {
    const raw = localStorage.getItem('shadow.trackedServers');
    if (!raw) return [];
    const arr = JSON.parse(raw);
    if (!Array.isArray(arr)) return [];
    return arr.filter(s => typeof s === 'string' && s.trim());
  } catch (_) { return []; }
}

function saveTrackedServers(servers) {
  try {
    localStorage.setItem('shadow.trackedServers',
      JSON.stringify(servers.slice(0, 16)));  // hard cap so we don't ping forever
  } catch (_) {}
}

/**
 * Parse "hypixel.net" or "play.example.com:25566" into {host, port}.
 * Lowercases the host so duplicate adds collapse.
 */
function parseServerAddr(input) {
  let s = (input || '').trim().toLowerCase();
  if (!s) return null;
  // strip any scheme/path the user might paste
  s = s.replace(/^[a-z]+:\/\//, '').replace(/[/?].*$/, '');
  let port = 0;
  const colon = s.lastIndexOf(':');
  if (colon > 0) {
    const p = parseInt(s.slice(colon + 1), 10);
    if (Number.isFinite(p) && p > 0 && p < 65536) {
      port = p;
      s = s.slice(0, colon);
    }
  }
  if (!s) return null;
  return { host: s, port };
}

/** Tracked-server poll state (per-server cached sample-name list). */
const trackedServerSamples = new Map();  // host:port → { names: Set<string>, lastPingMs, label }

/**
 * Curated list of well-known public MC servers we always ping. Tiny
 * by design — these are the ones friends are most likely to be on.
 * The user's own servers.dat is the bigger source.
 */
const POPULAR_SERVERS = [
  { host: 'mc.hypixel.net',     port: 25565, label: 'Hypixel' },
  { host: 'play.cubecraft.net', port: 25565, label: 'CubeCraft' },
  { host: 'play.hivemc.com',    port: 25565, label: 'The Hive' },
  { host: 'play.wynncraft.com', port: 25565, label: 'Wynncraft' },
  { host: 'mineplex.com',       port: 25565, label: 'Mineplex' },
  { host: '2b2t.org',           port: 25565, label: '2b2t' },
  { host: 'play.mc-central.net', port: 25565, label: 'MC Central' },
];

/** Cached results of list_local_mc_servers — refreshed every 10 min. */
let cachedLocalServers = [];
let cachedLocalServersAt = 0;

async function getServerPingTargets() {
  // 1. Refresh local servers.dat scan every 10 min (cheap I/O, but no
  //    point doing it on every poll cycle).
  if (Date.now() - cachedLocalServersAt > 10 * 60_000) {
    try {
      cachedLocalServers = await invoke('list_local_mc_servers') || [];
      cachedLocalServersAt = Date.now();
    } catch (_) { cachedLocalServers = []; }
  }
  // 2. Merge: popular + local + user-added. Keyed by host:port so a
  //    server in two sources only gets pinged once.
  const merged = new Map();
  const add = (host, port, label) => {
    const h = (host || '').trim().toLowerCase();
    if (!h) return;
    const p = port && port > 0 ? port : 25565;
    const key = `${h}:${p}`;
    if (!merged.has(key)) merged.set(key, { host: h, port: p, label: label || h });
  };
  for (const s of POPULAR_SERVERS) add(s.host, s.port, s.label);
  for (const s of cachedLocalServers) add(s.host, s.port, s.name);
  for (const raw of loadTrackedServers()) {
    const addr = parseServerAddr(raw);
    if (addr) add(addr.host, addr.port, addr.host);
  }
  return Array.from(merged.values());
}

/** Ping `n` servers in parallel. Returns nothing — populates the
 *  trackedServerSamples map. */
async function pingBatch(targets, concurrency = 8) {
  let i = 0;
  const workers = Array.from({ length: Math.min(concurrency, targets.length) }, async () => {
    while (i < targets.length) {
      const t = targets[i++];
      const key = `${t.host}:${t.port}`;
      try {
        const status = await invoke('ping_minecraft_server', {
          host: t.host, port: t.port,
        });
        const names = new Set(((status && status.sample_names) || []).map(n => n.toLowerCase()));
        trackedServerSamples.set(key, {
          names,
          lastPingMs: Date.now(),
          label: t.label,
        });
      } catch (_) {
        trackedServerSamples.set(key, {
          names: new Set(), lastPingMs: Date.now(), label: t.label,
        });
      }
    }
  });
  await Promise.all(workers);
}

async function pollTrackedServers() {
  if (!cachedFriends.length) return;
  const targets = await getServerPingTargets();
  if (!targets.length) return;
  await pingBatch(targets);
  // Merge into friends — only override status if we don't already have
  // a more-trustworthy presence-relay entry for them.
  let anyChanged = false;
  for (const f of cachedFriends) {
    const isRelayKnown = f.launcher === 'shadow-client';
    if (isRelayKnown) continue;  // relay data wins
    let foundOn = null;
    for (const [key, { names, label }] of trackedServerSamples) {
      if (names.has(f.username.toLowerCase())) {
        foundOn = label || key.replace(/:25565$/, '');
        break;
      }
    }
    const prev = `${f.status}|${f.server}|${f.launcher}`;
    if (foundOn) {
      f.status = 'playing';
      f.server = foundOn;
      f.launcher = 'unknown';  // can't tell from server ping
      f.last_seen = Math.floor(Date.now() / 1000);
    } else if (f.launcher === 'unknown') {
      // Was found via ping previously, now isn't — flip back to offline.
      f.status = null;
      f.server = null;
      f.launcher = null;
    }
    if (prev !== `${f.status}|${f.server}|${f.launcher}`) anyChanged = true;
  }
  if (anyChanged) renderFriends(cachedFriends);
}

setInterval(pollTrackedServers, 90_000);

// ───── Resource-pack drag-drop ─────────────────────────────────
// Tauri 2 routes file drops on the webview through its own event
// system (HTML5 native drop hides the absolute file path for
// security). We subscribe via the webview API and install any .zip
// dropped on the launcher window into the active profile's
// resourcepacks/ dir.

async function registerDragDropHandler() {
  // Probe for the API — Tauri 2 exposes window.__TAURI__.webview when
  // withGlobalTauri:true (which we do, per tauri.conf.json).
  const webviewApi = window.__TAURI__ && window.__TAURI__.webview;
  if (!webviewApi || typeof webviewApi.getCurrentWebview !== 'function') {
    console.warn('[shadow] webview drag-drop API not available; resource-pack drop disabled');
    return;
  }
  const wv = webviewApi.getCurrentWebview();
  // The handler returns an unlisten() which we ignore — we want this
  // to run for the lifetime of the window.
  wv.onDragDropEvent(async (e) => {
    if (!e || !e.payload) return;
    if (e.payload.type !== 'drop' && e.payload.type !== 'over') return;
    if (e.payload.type === 'over') {
      document.body.classList.add('drag-active');
      return;
    }
    document.body.classList.remove('drag-active');
    const paths = e.payload.paths || [];
    const zips = paths.filter(p => /\.zip$/i.test(p));
    if (!zips.length) {
      if (paths.length) {
        setStatus('Drop a .zip resource pack to install it.', 'error');
      }
      return;
    }
    for (const p of zips) {
      try {
        const result = await invoke('install_resource_pack', {
          path: p, version: getPickedVersion(),
        });
        setStatus(
          `Installed pack: ${result.filename} (${formatBytes(result.bytes)}). \
Enable it in MC's Options → Resource Packs.`,
          'ok',
        );
      } catch (err) {
        setStatus(`Pack install failed: ${err}`, 'error');
      }
    }
  });
}

function formatBytes(n) {
  if (!Number.isFinite(n) || n < 0) return `${n} B`;
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(0)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`;
}

// Fire once on startup. Don't await — if the API is missing we still
// want the rest of the launcher to keep loading.
registerDragDropHandler().catch(e =>
  console.warn('[shadow] registerDragDropHandler:', e));

// ───── Resource-pack list dialog ────────────────────────────────
// Opened from the "Resource packs" tile. Drag-drop continues to
// install while the dialog is open; we re-fetch the list on every
// open so newly-installed packs show up immediately.
const packsDialog       = $('packs-dialog');
const packsDialogClose  = $('packs-dialog-close');
const packsDialogDone   = $('packs-dialog-done');
const packsListEl       = $('packs-list');
const actionResourcePacks = $('action-resource-packs');

async function refreshPacksList() {
  if (!packsListEl) return;
  packsListEl.innerHTML = '';
  let packs = [];
  try {
    packs = await invoke('list_resource_packs', { version: getPickedVersion() });
  } catch (e) {
    const err = document.createElement('li');
    err.className = 'packs-empty';
    err.textContent = `Couldn't read resourcepacks folder: ${e}`;
    packsListEl.appendChild(err);
    return;
  }
  if (!packs.length) {
    const empty = document.createElement('li');
    empty.className = 'packs-empty';
    empty.textContent = 'No packs installed for this profile. Drag a .zip onto the launcher window to install one.';
    packsListEl.appendChild(empty);
    return;
  }
  for (const p of packs) {
    const row = document.createElement('li');
    row.className = 'pack-row';

    const info = document.createElement('div');
    info.className = 'pack-info';
    const name = document.createElement('div');
    name.className = 'pack-name';
    name.textContent = p.filename;
    name.title = p.dest_path;
    info.appendChild(name);
    const meta = document.createElement('div');
    meta.className = 'pack-meta';
    meta.textContent = formatBytes(p.bytes);
    info.appendChild(meta);
    row.appendChild(info);

    const remove = document.createElement('button');
    remove.className = 'pack-remove';
    remove.type = 'button';
    remove.setAttribute('aria-label', `Delete ${p.filename}`);
    remove.textContent = '× Delete';
    remove.addEventListener('click', async () => {
      // Cheap inline confirm — packs are large and accidentally
      // deleted ones are 100 MB the user has to re-download.
      remove.disabled = true;
      remove.textContent = 'Sure?';
      remove.classList.add('danger');
      let confirmed = false;
      const onConfirm = async () => {
        confirmed = true;
        try {
          await invoke('remove_resource_pack', {
            filename: p.filename,
            version: getPickedVersion(),
          });
          await refreshPacksList();
        } catch (err) {
          remove.disabled = false;
          remove.textContent = '× Delete';
          remove.classList.remove('danger');
          setStatus(`Couldn't delete ${p.filename}: ${err}`, 'error');
        }
      };
      remove.addEventListener('click', onConfirm, { once: true });
      // Auto-revert after 2.5s if they don't click again.
      setTimeout(() => {
        if (confirmed) return;
        remove.disabled = false;
        remove.textContent = '× Delete';
        remove.classList.remove('danger');
        remove.removeEventListener('click', onConfirm);
      }, 2500);
    });
    row.appendChild(remove);

    packsListEl.appendChild(row);
  }
}

actionResourcePacks?.addEventListener('click', () => {
  if (!packsDialog) return;
  packsDialog.showModal();
  refreshPacksList();
});
packsDialogClose?.addEventListener('click', () => packsDialog?.close());
packsDialogDone?.addEventListener('click', () => packsDialog?.close());
// Click backdrop to dismiss — matches the existing settings dialog UX.
packsDialog?.addEventListener('click', (e) => {
  if (e.target === packsDialog) packsDialog.close();
});

// ───── Server browser dialog ────────────────────────────────────
// Shows curated popular servers + the user's own servers.dat
// entries. Add buttons funnel through shadowAddTrackedServer /
// shadowRemoveTrackedServer so the friend-detection ping list is
// the single source of truth — same backend the friends-panel
// trackers UI uses.
const serversDialog       = $('servers-dialog');
const serversDialogClose  = $('servers-dialog-close');
const serversDialogDone   = $('servers-dialog-done');
const serverListPopular   = $('server-list-popular');
const serverListSaved     = $('server-list-saved');
const actionServers       = $('action-servers');

function serverKeyMatches(tracked, host, port) {
  // shadowAddTrackedServer stores "host" or "host:port" (default
  // port elided). Match either form.
  const h = (host || '').toLowerCase();
  if (!h) return false;
  if (port && port !== 25565) {
    return tracked === `${h}:${port}` || tracked === `${h}:${port}/`;
  }
  return tracked === h || tracked === `${h}:25565`;
}

function buildServerRow(displayName, host, port, isTracked) {
  const row = document.createElement('li');
  row.className = 'server-row';

  const info = document.createElement('div');
  info.className = 'server-info';
  const name = document.createElement('div');
  name.className = 'server-name';
  name.textContent = displayName;
  const hostLine = document.createElement('div');
  hostLine.className = 'server-host';
  hostLine.textContent = port && port !== 25565 ? `${host}:${port}` : host;
  info.appendChild(name);
  info.appendChild(hostLine);

  // Live status pulled from the existing pollTrackedServers cache.
  const cacheKey = `${(host || '').toLowerCase()}:${port || 25565}`;
  const sample = trackedServerSamples.get(cacheKey);
  if (sample) {
    const status = document.createElement('div');
    status.className = 'server-status';
    if (sample.names.size > 0) {
      status.textContent = `${sample.names.size} player${sample.names.size === 1 ? '' : 's'} in sample`;
    } else {
      status.textContent = 'No sample yet';
    }
    info.appendChild(status);
  }
  row.appendChild(info);

  const btn = document.createElement('button');
  btn.className = 'server-add-btn';
  btn.type = 'button';
  if (isTracked) {
    btn.textContent = '✓ Tracking';
    btn.classList.add('tracking');
    btn.title = 'Click to stop tracking this server';
  } else {
    btn.textContent = '+ Track';
    btn.title = 'Add to friend-detection ping list';
  }
  btn.addEventListener('click', () => {
    if (isTracked) {
      // Remove via the same key form we stored under.
      const tracked = loadTrackedServers();
      const norm = (port && port !== 25565) ? `${host}:${port}` : host;
      const found = tracked.find(t => serverKeyMatches(t, host, port)) || norm;
      window.shadowRemoveTrackedServer(found);
    } else {
      const norm = (port && port !== 25565) ? `${host}:${port}` : host;
      window.shadowAddTrackedServer(norm);
    }
    refreshServersDialog();
    renderTrackedServers();  // keep the friends-panel list in sync
  });
  row.appendChild(btn);

  return row;
}

async function refreshServersDialog() {
  if (!serverListPopular || !serverListSaved) return;
  const tracked = loadTrackedServers().map(t => t.toLowerCase());

  // Popular section.
  serverListPopular.innerHTML = '';
  for (const s of POPULAR_SERVERS) {
    const isTracked = tracked.some(t => serverKeyMatches(t, s.host, s.port));
    serverListPopular.appendChild(buildServerRow(s.label, s.host, s.port, isTracked));
  }

  // Saved section.
  serverListSaved.innerHTML = '';
  let local = [];
  try {
    local = await invoke('list_local_mc_servers') || [];
  } catch (_) {}
  if (!local.length) {
    const empty = document.createElement('li');
    empty.className = 'server-empty';
    empty.textContent = "Nothing in this launcher's servers.dat yet. Add a server inside Minecraft's Multiplayer menu and it'll show up here.";
    serverListSaved.appendChild(empty);
  } else {
    for (const s of local) {
      const isTracked = tracked.some(t => serverKeyMatches(t, s.host, s.port));
      const label = s.name && s.name !== s.host ? s.name : s.host;
      serverListSaved.appendChild(buildServerRow(label, s.host, s.port, isTracked));
    }
  }
}

actionServers?.addEventListener('click', () => {
  if (!serversDialog) return;
  serversDialog.showModal();
  refreshServersDialog();
});
serversDialogClose?.addEventListener('click', () => serversDialog?.close());
serversDialogDone?.addEventListener('click', () => serversDialog?.close());
serversDialog?.addEventListener('click', (e) => {
  if (e.target === serversDialog) serversDialog.close();
});

// Also clear the drag-active class on leave events the webview routes
// through the same channel — covers the user dragging back out.
(async () => {
  const webviewApi = window.__TAURI__ && window.__TAURI__.webview;
  if (!webviewApi || typeof webviewApi.getCurrentWebview !== 'function') return;
  const wv = webviewApi.getCurrentWebview();
  wv.onDragDropEvent((e) => {
    if (e && e.payload && (e.payload.type === 'leave' || e.payload.type === 'cancel')) {
      document.body.classList.remove('drag-active');
    }
  });
})().catch(() => {});

// Expose for UI binding.
window.shadowAddTrackedServer = (input) => {
  const addr = parseServerAddr(input);
  if (!addr) return false;
  const servers = loadTrackedServers();
  const norm = `${addr.host}${addr.port ? ':' + addr.port : ''}`;
  if (servers.includes(norm)) return false;
  servers.unshift(norm);
  saveTrackedServers(servers);
  pollTrackedServers();
  return true;
};
window.shadowRemoveTrackedServer = (host) => {
  const servers = loadTrackedServers().filter(s => s !== host);
  saveTrackedServers(servers);
  pollTrackedServers();
};
window.shadowListTrackedServers = () => loadTrackedServers();

// Listen for MC start/exit events so the "You" row re-renders to reflect
// the actual running state without polling. Also kicks the presence
// publisher so friends see us as "Playing" within seconds, not on the
// next 60s tick.
listen('mc-started', () => {
  mcRunning = true;
  renderFriends(cachedFriends);
  publishPresence(true);
});
listen('mc-exited',  (event) => {
  mcRunning = false;
  renderFriends(cachedFriends);
  publishPresence(false);
  // v0.3.39: on non-zero exit, surface the most-recent crash report so
  // the user doesn't have to dig into game_dir/profiles/X/crash-reports/
  // to figure out what blew up. Mod-loader crashes write nicely
  // formatted reports there; uncaught exceptions in core MC do too.
  const code = event?.payload?.code;
  if (typeof code === 'number' && code !== 0) {
    setTimeout(showLatestCrashReport, 200);
  }
});

async function showLatestCrashReport() {
  let report;
  try {
    report = await invoke('read_latest_crash_report', { version: getPickedVersion() });
  } catch (e) {
    console.warn('[shadow] read_latest_crash_report failed:', e);
    return;
  }
  if (!report) return;
  // Only show the report if it's from THIS launch (mtime within last
  // 60 s). Otherwise we'd surface stale crashes on every clean exit.
  const ageSeconds = (Date.now() / 1000) - (report.mtime_unix || 0);
  if (ageSeconds > 120) return;

  // Lightweight modal — overlay + dismissable panel. Reusing the
  // <dialog> element gets us focus trapping + Esc-to-close for free.
  let modal = document.getElementById('crash-report-modal');
  if (!modal) {
    modal = document.createElement('dialog');
    modal.id = 'crash-report-modal';
    modal.className = 'crash-modal';
    modal.innerHTML = `
      <div class="dialog-head">
        <h2>Minecraft crashed</h2>
        <button class="icon-btn" id="crash-close" type="button" aria-label="Close">
          <span aria-hidden="true">×</span>
        </button>
      </div>
      <div class="dialog-body">
        <p class="page-blurb" id="crash-filename"></p>
        <pre class="crash-content" id="crash-content"></pre>
      </div>
      <div class="dialog-foot">
        <button class="btn-sm" id="crash-copy" type="button">Copy</button>
        <button class="btn-sm" id="crash-open-folder" type="button">Open folder</button>
        <button class="btn-done" id="crash-done" type="button">Close</button>
      </div>
    `;
    document.body.appendChild(modal);
    modal.querySelector('#crash-close')?.addEventListener('click', () => modal.close());
    modal.querySelector('#crash-done')?.addEventListener('click', () => modal.close());
    modal.querySelector('#crash-copy')?.addEventListener('click', async () => {
      const pre = modal.querySelector('#crash-content');
      try { await navigator.clipboard.writeText(pre?.textContent || ''); } catch (_) {}
      const btn = modal.querySelector('#crash-copy');
      if (btn) { const t = btn.textContent; btn.textContent = 'Copied!'; setTimeout(() => btn.textContent = t, 1500); }
    });
    modal.querySelector('#crash-open-folder')?.addEventListener('click', async () => {
      try { await invoke('open_folder'); } catch (_) {}
    });
  }
  modal.querySelector('#crash-filename').textContent =
    `${report.filename} · ${Math.round(report.size_bytes / 1024)} KB`;
  modal.querySelector('#crash-content').textContent = report.head || '(empty)';
  modal.showModal();
}

// v0.3.37: Microsoft sign-in prompt banner. The Rust microsoft_login
// command emits `ms-prompt` with the device code + verification URL the
// moment Microsoft hands it to us. We render a big sticky banner with
// both — clickable URL (opens in browser), copyable code — so the user
// can complete sign-in EVEN IF the browser didn't auto-open.
// `ms-prompt-done` fires when sign-in completes (success or failure)
// and tears the banner down.
let msPromptBanner = null;
listen('ms-prompt', (event) => {
  if (msPromptBanner) msPromptBanner.remove();
  const p = event.payload || {};
  const code = String(p.user_code || '').toUpperCase();
  const url  = String(p.verification_uri_complete || p.verification_uri || '');
  const banner = document.createElement('div');
  banner.className = 'startup-banner ms-prompt';
  banner.setAttribute('role', 'status');
  banner.innerHTML = `
    <div class="startup-banner-title">▲ Microsoft sign-in</div>
    <div class="startup-banner-body">
      A browser tab should have opened. If it didn't, click
      <a id="ms-prompt-link" href="#">${url}</a>
      and enter this code:
      <code id="ms-prompt-code" class="ms-prompt-code">${code}</code>
      <button class="banner-action-secondary" id="ms-prompt-copy" type="button">Copy code</button>
    </div>
  `;
  document.body.appendChild(banner);
  msPromptBanner = banner;
  banner.querySelector('#ms-prompt-link')?.addEventListener('click', (e) => {
    e.preventDefault();
    openExternal(url);
  });
  banner.querySelector('#ms-prompt-copy')?.addEventListener('click', async () => {
    try {
      await navigator.clipboard.writeText(code);
      const btn = banner.querySelector('#ms-prompt-copy');
      if (btn) {
        const orig = btn.textContent;
        btn.textContent = 'Copied!';
        setTimeout(() => { btn.textContent = orig; }, 1500);
      }
    } catch (_) {}
  });
});
listen('ms-prompt-done', () => {
  if (msPromptBanner) {
    msPromptBanner.remove();
    msPromptBanner = null;
  }
});

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

// ───── Tracked-servers form (cross-launcher friend detection) ──────
const trackedAddForm = $('tracked-add-form');
const trackedAddInput = $('tracked-add-input');
const trackedListEl = $('tracked-servers-list');

function renderTrackedServers() {
  if (!trackedListEl) return;
  trackedListEl.innerHTML = '';
  const servers = loadTrackedServers();
  if (!servers.length) {
    const empty = document.createElement('li');
    empty.className = 'tracked-empty';
    empty.textContent = 'No servers tracked.';
    trackedListEl.appendChild(empty);
    return;
  }
  for (const host of servers) {
    const row = document.createElement('li');
    row.className = 'tracked-server-row';
    const name = document.createElement('span');
    name.className = 'tracked-server-host';
    name.textContent = host;
    const remove = document.createElement('button');
    remove.className = 'tracked-server-remove';
    remove.type = 'button';
    remove.textContent = '×';
    remove.setAttribute('aria-label', `Stop tracking ${host}`);
    remove.addEventListener('click', () => {
      window.shadowRemoveTrackedServer(host);
      renderTrackedServers();
    });
    row.appendChild(name);
    row.appendChild(remove);
    trackedListEl.appendChild(row);
  }
}

trackedAddForm?.addEventListener('submit', (e) => {
  e.preventDefault();
  if (!trackedAddInput) return;
  const raw = trackedAddInput.value.trim();
  if (!raw) return;
  if (window.shadowAddTrackedServer(raw)) {
    trackedAddInput.value = '';
    trackedAddInput.focus();
    renderTrackedServers();
  } else {
    // Either duplicate or unparseable — flash the input.
    trackedAddInput.classList.add('error');
    setTimeout(() => trackedAddInput.classList.remove('error'), 1500);
  }
});

// Initial render so the list reflects whatever's stored.
renderTrackedServers();

// ───── Quick action tiles ───────────────────────────────────────
document.getElementById('action-mods')?.addEventListener('click', () => {
  // Open Settings dialog and switch to the Mods tab.
  settingsDialog.showModal();
  const tab = document.querySelector('.dlg-tab[data-tab="mods"]');
  if (tab) activateDialogTab(tab);
});

// Cosmetics quick action now opens the dedicated Cosmetics tab in the
// settings dialog so the user can actually pick things.
document.getElementById('action-cosmetics')?.addEventListener('click', () => {
  settingsDialog.showModal();
  const tab = document.querySelector('.dlg-tab[data-tab="cosm"]');
  if (tab) activateDialogTab(tab);
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

// v0.3.36: prominent "Done" button in the sticky dialog footer.
// Belt-and-suspenders with the corner × — users were getting stuck in
// settings because the × was hard to spot after the cosmetics tab's
// character preview took up the upper area.
document.getElementById('dialog-done')?.addEventListener('click', () => {
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
    if (tab.dataset.tab === 'cosm') loadCosmetics();
    if (tab.dataset.tab === 'general') refreshProfiles();
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

// ───── Profiles manager (Settings → General) ────────────────────
// v0.3.40: lets the user see + switch + delete installed MC version
// profiles. Each profile owns its own mods/worlds/screenshots; the
// shared libraries/assets dir is untouched by deletion.
async function refreshProfiles() {
  const list = $('profile-list');
  if (!list) return;
  list.innerHTML = '<li class="empty">Loading…</li>';
  try {
    const profiles = await invoke('list_profiles');
    if (!profiles.length) {
      list.innerHTML = '<li class="empty">No profiles installed yet. Press PLAY to set one up.</li>';
      return;
    }
    list.innerHTML = '';
    for (const p of profiles) {
      list.appendChild(buildProfileRow(p));
    }
  } catch (e) {
    list.innerHTML = `<li class="empty">Error: ${e.message || e}</li>`;
  }
}

function buildProfileRow(p) {
  const row = document.createElement('li');
  row.className = 'profile-row' + (p.is_last_used ? ' active' : '');
  row.dataset.profile = p.name;

  const meta = document.createElement('div');
  meta.className = 'profile-meta';
  const activeDot = p.is_last_used
    ? '<span class="profile-active-dot" aria-label="Active"></span>'
    : '';
  meta.innerHTML = `
    <div class="profile-name">${activeDot}${escapeHtml(p.name)}${p.is_last_used ? ' <span class="profile-active-tag">ACTIVE</span>' : ''}</div>
    <div class="profile-sub">MC ${escapeHtml(p.mc_version || '?')} · Fabric ${escapeHtml(p.fabric_loader || '?')} · ${p.mods_installed} mods</div>
  `;

  const actions = document.createElement('div');
  actions.className = 'profile-actions';
  if (!p.is_last_used) {
    const switchBtn = document.createElement('button');
    switchBtn.type = 'button';
    switchBtn.className = 'btn-sm';
    switchBtn.textContent = 'Switch to';
    switchBtn.addEventListener('click', async () => {
      switchBtn.disabled = true;
      try {
        await invoke('switch_profile', { name: p.name });
        // Reflect the switch on the home screen's version picker.
        const sel = $('version-select');
        if (sel && p.mc_version) {
          sel.value = p.mc_version;
          localStorage.setItem(SAVED_VERSION_KEY, p.mc_version);
          loadState();
        }
        await refreshProfiles();
        setStatus(`Switched to profile "${p.name}"`, 'ok');
      } catch (e) {
        setStatus(`Switch failed: ${e}`, 'error');
        switchBtn.disabled = false;
      }
    });
    actions.appendChild(switchBtn);
  }
  const delBtn = document.createElement('button');
  delBtn.type = 'button';
  delBtn.className = 'btn-sm';
  delBtn.textContent = 'Delete';
  delBtn.addEventListener('click', async () => {
    const ok = confirm(
      `Delete profile "${p.name}"?\n\n` +
      `This removes the per-profile mods, worlds, and screenshots. ` +
      `Shared libraries + assets stay (they're version-scoped, not profile-scoped).`
    );
    if (!ok) return;
    delBtn.disabled = true;
    delBtn.textContent = 'Deleting…';
    try {
      await invoke('delete_profile', { name: p.name, force: p.is_last_used });
      await refreshProfiles();
      setStatus(`Deleted profile "${p.name}"`, 'ok');
    } catch (e) {
      setStatus(`Delete failed: ${e}`, 'error');
      delBtn.disabled = false;
      delBtn.textContent = 'Delete';
    }
  });
  actions.appendChild(delBtn);

  row.appendChild(meta);
  row.appendChild(actions);
  return row;
}

// ───── Add mod (.jar) ───────────────────────────────────────────
// "Add mod" button proxies its click to the hidden <input type="file">
// so the file picker opens. The picker fires `change` when files are
// chosen; we then read each one as bytes and ship it to the Rust
// command `add_mod_jar`, which drops the file into the per-version
// mods folder. Skips non-.jar files silently (the picker already
// filters via accept=".jar" but the browser doesn't strictly enforce).
const addModBtn = $('add-mod-btn');
const addModInput = $('add-mod-input');
if (addModBtn && addModInput) {
  addModBtn.addEventListener('click', () => addModInput.click());
  addModInput.addEventListener('change', async (e) => {
    const files = Array.from(e.target.files || []);
    e.target.value = '';  // reset so re-picking the same file fires change
    if (!files.length) return;
    const version = getPickedVersion();
    const list = $('mod-list');
    if (list) list.innerHTML = '<li class="empty">Installing…</li>';

    let added = 0, failed = 0;
    for (const file of files) {
      if (!file.name.toLowerCase().endsWith('.jar')) { failed++; continue; }
      try {
        const buf = await file.arrayBuffer();
        // Tauri serializes Vec<u8> as an array of numbers in the JSON
        // payload. For typical mods (50 KB – 8 MB) this is fine; we'd
        // need a streaming approach if mods got significantly larger.
        await invoke('add_mod_jar', {
          version,
          name: file.name,
          bytes: Array.from(new Uint8Array(buf)),
        });
        added++;
      } catch (err) {
        console.warn('[shadow] add_mod_jar failed for', file.name, err);
        failed++;
      }
    }
    await refreshMods();
    setStatus(
      failed
        ? `Added ${added} mod${added === 1 ? '' : 's'}, ${failed} failed`
        : `Added ${added} mod${added === 1 ? '' : 's'} to ${version}`,
      failed ? 'error' : 'ok'
    );
  });
}

// ───── Modrinth search ─────────────────────────────────────────
// v0.3.39: real mod browser. Type any query, hit Search → fetches
// api.modrinth.com/v2/search filtered to Fabric mods on the current
// MC version. Each result has a one-click Install button that
// resolves the matching version and downloads via the Rust
// install_mod_from_url command (streams to disk, doesn't go through
// IPC bytes).
const MODRINTH_API = 'https://api.modrinth.com/v2';
const modrinthForm    = $('modrinth-search-form');
const modrinthQuery   = $('modrinth-query');
const modrinthResults = $('modrinth-results');

if (modrinthForm) {
  modrinthForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    await runModrinthSearch();
  });
}

async function runModrinthSearch() {
  const q = (modrinthQuery?.value || '').trim();
  if (!modrinthResults) return;
  if (!q) {
    modrinthResults.hidden = true;
    modrinthResults.innerHTML = '';
    return;
  }
  modrinthResults.hidden = false;
  modrinthResults.innerHTML = '<li class="modrinth-empty">Searching Modrinth…</li>';

  const mcVer = getPickedVersion();
  // Facets restrict to fabric mods that target the current MC version.
  const facets = JSON.stringify([
    ['project_type:mod'],
    [`versions:${mcVer}`],
    ['categories:fabric'],
  ]);
  const url = `${MODRINTH_API}/search?query=${encodeURIComponent(q)}&limit=15&facets=${encodeURIComponent(facets)}`;
  try {
    const resp = await fetch(url, { headers: { 'User-Agent': 'shadow-client (github.com/bluestatic11/shadow-client)' } });
    if (!resp.ok) {
      modrinthResults.innerHTML = `<li class="modrinth-empty">Modrinth returned ${resp.status}</li>`;
      return;
    }
    const data = await resp.json();
    const hits = data.hits || [];
    if (!hits.length) {
      modrinthResults.innerHTML = `<li class="modrinth-empty">No fabric mods for MC ${mcVer} match "${q}".</li>`;
      return;
    }
    modrinthResults.innerHTML = '';
    for (const hit of hits) {
      modrinthResults.appendChild(buildModrinthResult(hit, mcVer));
    }
  } catch (err) {
    modrinthResults.innerHTML = `<li class="modrinth-empty">Search failed: ${err.message || err}</li>`;
  }
}

function buildModrinthResult(hit, mcVer) {
  const li = document.createElement('li');
  li.className = 'modrinth-result';

  const icon = document.createElement('img');
  icon.className = 'modrinth-icon';
  icon.src = hit.icon_url || '';
  icon.alt = '';
  icon.referrerPolicy = 'no-referrer';
  icon.onerror = () => { icon.style.visibility = 'hidden'; };

  const meta = document.createElement('div');
  meta.className = 'modrinth-meta';
  meta.innerHTML = `
    <div class="modrinth-title">${escapeHtml(hit.title)} <span class="modrinth-author">by ${escapeHtml(hit.author)}</span></div>
    <div class="modrinth-desc">${escapeHtml(hit.description || '')}</div>
    <div class="modrinth-stats">↓ ${formatCount(hit.downloads)} · ${escapeHtml(hit.client_side || '')}</div>
  `;

  const installBtn = document.createElement('button');
  installBtn.type = 'button';
  installBtn.className = 'btn-sm primary modrinth-install';
  installBtn.textContent = 'Install';
  installBtn.addEventListener('click', async () => {
    await installModrinthProject(hit.slug, hit.title, mcVer, installBtn);
  });

  li.appendChild(icon);
  li.appendChild(meta);
  li.appendChild(installBtn);
  return li;
}

async function installModrinthProject(slug, displayName, mcVer, btn) {
  const orig = btn.textContent;
  btn.disabled = true;
  btn.textContent = 'Fetching…';
  try {
    // Get the most recent version that targets this MC version + Fabric.
    const verResp = await fetch(
      `${MODRINTH_API}/project/${encodeURIComponent(slug)}/version` +
      `?loaders=${encodeURIComponent('["fabric"]')}` +
      `&game_versions=${encodeURIComponent('["' + mcVer + '"]')}`,
      { headers: { 'User-Agent': 'shadow-client' } }
    );
    if (!verResp.ok) throw new Error(`Modrinth /version returned ${verResp.status}`);
    const versions = await verResp.json();
    if (!versions.length) throw new Error(`No Fabric version of ${displayName} for MC ${mcVer}`);
    // First version is the most recent.
    const v = versions[0];
    const file = (v.files || []).find(f => f.primary) || (v.files || [])[0];
    if (!file) throw new Error('No downloadable file in latest version');

    btn.textContent = 'Installing…';
    await invoke('install_mod_from_url', {
      version: mcVer,
      name: file.filename,
      url: file.url,
    });
    btn.textContent = '✓ Installed';
    setStatus(`Installed ${displayName} (${file.filename})`, 'ok');
    // Refresh the installed-mods list below so the user sees it land.
    await refreshMods();
    setTimeout(() => { btn.textContent = orig; btn.disabled = false; }, 2500);
  } catch (e) {
    btn.textContent = 'Failed';
    setStatus(`Install failed: ${e.message || e}`, 'error');
    setTimeout(() => { btn.textContent = orig; btn.disabled = false; }, 3000);
  }
}

function escapeHtml(s) {
  return String(s ?? '')
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
}

function formatCount(n) {
  n = Number(n) || 0;
  if (n >= 1000000) return (n / 1000000).toFixed(1).replace(/\.0$/, '') + 'M';
  if (n >= 1000)    return (n / 1000).toFixed(1).replace(/\.0$/, '') + 'k';
  return String(n);
}

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

// ───── Cosmetics catalog ────────────────────────────────────────
// Single source of truth for every cosmetic the launcher offers. Each
// item has:
//   id     — globally unique string ID (used as the saved value)
//   name   — display label
//   icon   — single character or short string shown in the tile
//   color  — optional hex tint applied to the icon (visually distinguishes
//            items in the same slot — e.g. red dragon wings vs white angel)
//
// IDs are prefixed by slot (cape_*, wings_*, head_*, trail_*, aura_*) so a
// stale value from one slot can never collide with another.
//
// Bug-check pass #1 (code review): every item across every slot has a
// unique ID; "none" is reserved by every slot so deselection is consistent.
// Bug-check pass #2 (walkthrough): saved cosmCache only ever stores values
// from this catalog; an attacker injecting a foreign value into
// cosmetics.json would just render as "no selection" rather than crashing.
// v0.3.22: every cosmetic now uses a unique emoji glyph (Segoe UI Emoji is
// always installed on Win10+, so these render reliably). Previously many
// items shared a generic symbol — every cape was '▽', every aura was '◌',
// several wing types used '⫷⫸' which doesn't even render in most fonts and
// fell back to a missing-glyph box. Result: capes/auras were
// indistinguishable. Now each item has a thematic emoji + still keeps its
// color hint for the cosmetic background swatch.
const COSMETICS_CATALOG = {
  back: [
    { id: 'none',            name: 'None',         icon: '🚫' },
    // ─── Capes (color/theme variants — each gets a thematic emoji) ──
    { id: 'cape_shadow',     name: 'Shadow',       icon: '🌑', color: '#ff2030' },
    { id: 'cape_storm',      name: 'Storm',        icon: '⛈️', color: '#3a9eda' },
    { id: 'cape_embers',     name: 'Embers',       icon: '🔥', color: '#ff8a40' },
    { id: 'cape_frost',      name: 'Frost',        icon: '❄️', color: '#9ad8ea' },
    { id: 'cape_royal',      name: 'Royal',        icon: '👑', color: '#a050ff' },
    { id: 'cape_forest',     name: 'Forest',       icon: '🌲', color: '#22dd55' },
    { id: 'cape_noir',       name: 'Noir',         icon: '🌚', color: '#444444' },
    { id: 'cape_solar',      name: 'Solar',        icon: '☀️', color: '#ffd24a' },
    { id: 'cape_galaxy',     name: 'Galaxy',       icon: '🌌', color: '#7050cc' },
    { id: 'cape_aurora',     name: 'Aurora',       icon: '🌠', color: '#0ad6a8' },
    { id: 'cape_sunset',     name: 'Sunset',       icon: '🌅', color: '#ff70a0' },
    { id: 'cape_eclipse',    name: 'Eclipse',      icon: '🌘', color: '#202028' },
    { id: 'cape_inferno',    name: 'Inferno',      icon: '🌋', color: '#dc2020' },
    { id: 'cape_tide',       name: 'Tide',         icon: '🌊', color: '#4090d8' },
    { id: 'cape_mist',       name: 'Mist',         icon: '🌫️', color: '#b8b8c0' },
    { id: 'cape_volt',       name: 'Volt',         icon: '⚡', color: '#fff45a' },
    { id: 'cape_steel',      name: 'Steel',        icon: '🔩', color: '#909098' },
    { id: 'cape_rose',       name: 'Rose',         icon: '🌹', color: '#ff8888' },
    { id: 'cape_crimson',    name: 'Crimson',      icon: '🩸', color: '#8b0020' },
    // ─── Wings (one distinct emoji per kind) ────────────────────────
    { id: 'wings_dragon',    name: 'Dragon',       icon: '🐉', color: '#ff2030' },
    { id: 'wings_angel',     name: 'Angel',        icon: '👼', color: '#e8e8ec' },
    { id: 'wings_demon',     name: 'Demon',        icon: '😈', color: '#660020' },
    { id: 'wings_fairy',     name: 'Fairy',        icon: '🧚', color: '#ffb0e0' },
    { id: 'wings_butterfly', name: 'Butterfly',    icon: '🦋', color: '#3a9eda' },
    { id: 'wings_phoenix',   name: 'Phoenix',      icon: '🦅', color: '#ff8a40' },
    { id: 'wings_bat',       name: 'Bat',          icon: '🦇', color: '#2a2030' },
    { id: 'wings_crystal',   name: 'Crystal',      icon: '💎', color: '#9ad8ea' },
    { id: 'wings_mech',      name: 'Mech',         icon: '🤖', color: '#909098' },
    { id: 'wings_astral',    name: 'Astral',       icon: '🌟', color: '#a050ff' },
    { id: 'wings_toxic',     name: 'Toxic',        icon: '☣️', color: '#88dd22' },
    { id: 'wings_void',      name: 'Void',         icon: '🕳️', color: '#3a2050' },
    { id: 'wings_origami',   name: 'Origami',      icon: '📜', color: '#fff0e0' },
    { id: 'wings_shark',     name: 'Shark Fin',    icon: '🦈', color: '#6088a0' },
  ],
  head: [
    { id: 'none',            name: 'None',         icon: '🚫' },
    { id: 'head_halo',       name: 'Halo',         icon: '😇', color: '#ffd24a' },
    { id: 'head_crown',      name: 'Crown',        icon: '👑', color: '#ffd24a' },
    { id: 'head_antlers',    name: 'Antlers',      icon: '🦌', color: '#bb8855' },
    { id: 'head_tophat',     name: 'Top Hat',      icon: '🎩', color: '#1a1a1a' },
    { id: 'head_tiara',      name: 'Tiara',        icon: '👸', color: '#9ad8ea' },
    { id: 'head_helmet',     name: 'Helmet',       icon: '⛑️', color: '#888888' },
    { id: 'head_beanie',     name: 'Beanie',       icon: '🧢', color: '#3a9eda' },
    { id: 'head_headband',   name: 'Headband',     icon: '🥋', color: '#ff2030' },
    { id: 'head_wizard',     name: 'Wizard Hat',   icon: '🧙', color: '#a050ff' },
    { id: 'head_cowboy',     name: 'Cowboy Hat',   icon: '🤠', color: '#bb8855' },
    { id: 'head_cap',        name: 'Cap',          icon: '⛑️', color: '#ff2030' },
    { id: 'head_phones',     name: 'Headphones',   icon: '🎧', color: '#202020' },
    { id: 'head_mask',       name: 'Mask',         icon: '🎭', color: '#e8e8ec' },
    { id: 'head_mohawk',     name: 'Mohawk',       icon: '🦔', color: '#ff2030' },
    { id: 'head_cat_ears',   name: 'Cat Ears',     icon: '🐱', color: '#ffb0e0' },
    { id: 'head_glasses',    name: 'Glasses',      icon: '👓', color: '#202020' },
    { id: 'head_pirate',     name: 'Pirate Hat',   icon: '🏴‍☠️', color: '#202020' },
    { id: 'head_visor',      name: 'Cyber Visor',  icon: '🥽', color: '#0adada' },
  ],
  trail: [
    { id: 'none',            name: 'None',         icon: '🚫' },
    { id: 'trail_fairies',   name: 'Fairies',      icon: '🧚', color: '#ffb0e0' },
    { id: 'trail_footsteps', name: 'Footsteps',    icon: '👣', color: '#a8a8a8' },
    { id: 'trail_stars',     name: 'Stars',        icon: '⭐', color: '#ffd24a' },
    { id: 'trail_bow',       name: 'Bow Trail',    icon: '🏹', color: '#ff2030' },
    { id: 'trail_fire',      name: 'Fire',         icon: '🔥', color: '#ff8a40' },
    { id: 'trail_ice',       name: 'Ice',          icon: '❄️', color: '#9ad8ea' },
    { id: 'trail_lightning', name: 'Lightning',    icon: '⚡', color: '#fff45a' },
    { id: 'trail_hearts',    name: 'Hearts',       icon: '❤️', color: '#ff60a0' },
    { id: 'trail_petals',    name: 'Petals',       icon: '🌸', color: '#ffb0e0' },
    { id: 'trail_bubbles',   name: 'Bubbles',      icon: '🫧', color: '#9ad8ea' },
    { id: 'trail_smoke',     name: 'Smoke',        icon: '💨', color: '#888888' },
    { id: 'trail_leaves',    name: 'Leaves',       icon: '🍃', color: '#22dd55' },
    { id: 'trail_magic',     name: 'Magic',        icon: '✨', color: '#a050ff' },
    { id: 'trail_cherry',    name: 'Cherry Bloss', icon: '🌸', color: '#ff90c0' },
    { id: 'trail_confetti',  name: 'Confetti',     icon: '🎊', color: '#e040c0' },
    { id: 'trail_skulls',    name: 'Skulls',       icon: '💀', color: '#e8e8ec' },
    { id: 'trail_comet',     name: 'Comet',        icon: '☄️', color: '#3a9eda' },
  ],
  aura: [
    { id: 'none',            name: 'None',         icon: '🚫' },
    { id: 'aura_soft',       name: 'Soft Glow',    icon: '⚪', color: '#ffffff' },
    { id: 'aura_brand',      name: 'Brand',        icon: '🔴', color: '#ff2030' },
    { id: 'aura_holy',       name: 'Holy',         icon: '🟡', color: '#ffd24a' },
    { id: 'aura_shadow',     name: 'Shadow',       icon: '⚫', color: '#3a2a3a' },
    { id: 'aura_energy',     name: 'Energy',       icon: '🔵', color: '#3a9eda' },
    { id: 'aura_nature',     name: 'Nature',       icon: '🟢', color: '#22dd55' },
    { id: 'aura_arcane',     name: 'Arcane',       icon: '🟣', color: '#a050ff' },
    { id: 'aura_crimson',    name: 'Crimson',      icon: '🩸', color: '#8b0020' },
    { id: 'aura_verdant',    name: 'Verdant',      icon: '🌿', color: '#88dd22' },
    { id: 'aura_mystic',     name: 'Mystic',       icon: '💎', color: '#0ad6d6' },
    { id: 'aura_spectral',   name: 'Spectral',     icon: '👻', color: '#c0e0ff' },
    { id: 'aura_void',       name: 'Void',         icon: '🕳️', color: '#2a1040' },
    { id: 'aura_neon',       name: 'Neon',         icon: '💜', color: '#e040c0' },
  ],
  accent: [
    // 24-color accent palette. Stored as the hex string itself.
    { id: '#ff2030', name: 'Brand Red' },
    { id: '#dc2020', name: 'Crimson'   },
    { id: '#ff60a0', name: 'Pink'      },
    { id: '#ff7088', name: 'Coral'     },
    { id: '#ff8a40', name: 'Orange'    },
    { id: '#ffb840', name: 'Amber'     },
    { id: '#ffd24a', name: 'Gold'      },
    { id: '#fff45a', name: 'Yellow'    },
    { id: '#a8ff40', name: 'Lime'      },
    { id: '#22dd55', name: 'Green'     },
    { id: '#1e6038', name: 'Forest'    },
    { id: '#0ad6a8', name: 'Mint'      },
    { id: '#0ad6d6', name: 'Teal'      },
    { id: '#9ad8ea', name: 'Cyan'      },
    { id: '#3a9eda', name: 'Blue'      },
    { id: '#7ec8ff', name: 'Sky'       },
    { id: '#4060d0', name: 'Navy'      },
    { id: '#3030c0', name: 'Indigo'    },
    { id: '#a050ff', name: 'Purple'    },
    { id: '#c898ff', name: 'Lavender'  },
    { id: '#e040c0', name: 'Magenta'   },
    { id: '#e8e8ec', name: 'White'     },
    { id: '#888888', name: 'Grey'      },
    { id: '#1a1a1a', name: 'Black'     },
  ],
};

/**
 * Catalog self-test. Runs at boot + the first time the cosmetics tab
 * opens. Logs "Catalog OK" if everything checks out, or a list of
 * specific errors if anything is wrong. This is bug-check pass 2 for
 * every cosmetic — pass 1 is the manual code review at edit time.
 *
 * Checks performed against EVERY catalog entry:
 *   1. ID is a non-empty string
 *   2. IDs are unique within each slot
 *   3. ID matches the pattern: 'none' / lowercase_with_underscores /
 *      '#rrggbb' (the accent-slot hex form)
 *   4. Name is a non-empty string
 *   5. Tile-slot entries have an icon set
 *   6. Color (if present) is a valid 6-digit hex
 *   7. Tile slots (everything except 'accent') include a 'none' entry
 *   8. No ID appears in more than one slot (except 'none' which is
 *      slot-scoped and intentionally shared)
 */
function verifyCatalog() {
  const errors = [];
  const allIds = new Map();
  let total = 0;

  for (const [slot, items] of Object.entries(COSMETICS_CATALOG)) {
    const slotIds = new Set();
    for (const item of items) {
      total++;
      // 1. ID validity
      if (typeof item.id !== 'string' || item.id.length === 0) {
        errors.push(`${slot}: item missing id → ${JSON.stringify(item)}`);
        continue;
      }
      // 2. Slot-local uniqueness
      if (slotIds.has(item.id)) {
        errors.push(`${slot}: duplicate id '${item.id}'`);
      }
      slotIds.add(item.id);
      // 3. ID format
      const idOk = item.id === 'none'
        || /^[a-z][a-z0-9_]*$/.test(item.id)
        || /^#[0-9a-f]{6}$/i.test(item.id);
      if (!idOk) {
        errors.push(`${slot}: id '${item.id}' fails format check`);
      }
      // 4. Name
      if (typeof item.name !== 'string' || !item.name.trim()) {
        errors.push(`${slot}: '${item.id}' has empty name`);
      }
      // 5. Icon for tile slots
      if (slot !== 'accent' && (typeof item.icon !== 'string' || !item.icon)) {
        errors.push(`${slot}: '${item.id}' has no icon`);
      }
      // 6. Color hex (if present)
      if (item.color !== undefined && !/^#[0-9a-f]{6}$/i.test(item.color)) {
        errors.push(`${slot}: '${item.id}' has invalid color '${item.color}'`);
      }
      // 8. Cross-slot collision (skip 'none' — it's slot-scoped)
      if (item.id !== 'none') {
        const prev = allIds.get(item.id);
        if (prev && prev !== slot) {
          errors.push(`cross-slot collision: '${item.id}' in '${prev}' and '${slot}'`);
        }
        allIds.set(item.id, slot);
      }
    }
    // 7. Tile slots need 'none'
    if (slot !== 'accent' && !slotIds.has('none')) {
      errors.push(`${slot}: missing 'none' entry`);
    }
  }

  if (errors.length) {
    console.error('[shadow] Catalog FAILED validation (' + errors.length
      + ' errors):\n  ' + errors.join('\n  '));
    return { ok: false, errors, total };
  }
  console.log(`[shadow] Catalog OK — ${total} cosmetics validated`);
  return { ok: true, errors: [], total };
}

// Run the self-test at module-load time so any catalog typo surfaces
// immediately in the console + can be caught before the user sees it.
const __CATALOG_CHECK = verifyCatalog();

// Pretty slot titles. Order matters — this is the render order.
const COSM_SLOTS = [
  { key: 'back',   title: 'Back'        },
  { key: 'head',   title: 'Head'        },
  { key: 'trail',  title: 'Trail'       },
  { key: 'aura',   title: 'Aura'        },
  { key: 'accent', title: 'Accent color'},
];

// Default selections per slot. "none" for tile slots, brand red for accent.
const COSM_DEFAULTS = {
  back: 'none', head: 'none', trail: 'none', aura: 'none', accent: '#ff2030',
};

let cosmCache = { back: null, head: null, trail: null, aura: null, accent: null };
let cosmRendered = false;

async function loadCosmetics() {
  try {
    cosmCache = (await invoke('read_cosmetics')) || cosmCache;
  } catch (_) {}
  // Normalise + validate.
  //   1. Fill in missing slot keys (older cosmetics.json files may not
  //      have the 'aura' field yet — Rust serde defaults already do this,
  //      but the JS side belt-and-suspenders).
  //   2. Reject any value that doesn't exist in the current catalog —
  //      this happens if the user downgrades, or a renamed/deleted item
  //      lingers in their save. Replace with null so the UI shows
  //      "None" (or the default) instead of "nothing selected".
  for (const { key } of COSM_SLOTS) {
    if (!(key in cosmCache)) cosmCache[key] = null;
    const v = cosmCache[key];
    if (v != null) {
      const known = COSMETICS_CATALOG[key].some(item => item.id === v);
      if (!known) cosmCache[key] = null;
    }
  }
  if (!cosmRendered) renderCosmeticsTab();
  applyCosmeticsToUI();
}

function renderCosmeticsTab() {
  const host = document.getElementById('cosm-sections-host');
  if (!host) return;
  host.innerHTML = '';

  // Character mesh preview — Lunar-style mannequin showing the currently
  // equipped cape/wings/head on a blocky Minecraft-shaped silhouette.
  // The user asked for this in v0.3.24: "show the mesh." Each cosmetic
  // slot maps to a visual layer on the character. Updated reactively
  // by applyCosmeticsToUI() whenever a tile is clicked.
  host.appendChild(buildCharacterPreview());

  // Validation status badge — surfaces the catalog self-test result in
  // the UI so the user can see at a glance that every cosmetic passed
  // the runtime checks. Green if ok, red if any check failed.
  const badge = document.createElement('div');
  badge.className = __CATALOG_CHECK.ok ? 'cosm-validation ok' : 'cosm-validation fail';
  badge.textContent = __CATALOG_CHECK.ok
    ? `✓ ${__CATALOG_CHECK.total} cosmetics — all validated`
    : `✗ Catalog failed validation (${__CATALOG_CHECK.errors.length} errors — see console)`;
  host.appendChild(badge);

  for (const { key, title } of COSM_SLOTS) {
    const section = document.createElement('div');
    section.className = 'cosm-section';
    section.dataset.slot = key;

    const h = document.createElement('h4');
    h.className = 'cosm-slot-title';
    h.textContent = title;
    section.appendChild(h);

    const grid = document.createElement('div');
    grid.className = key === 'accent' ? 'cosm-swatches' : 'cosm-grid';

    for (const item of COSMETICS_CATALOG[key]) {
      grid.appendChild(buildCosmEntry(key, item));
    }
    section.appendChild(grid);
    host.appendChild(section);
  }
  // Single delegated click handler — survives re-renders, no risk of
  // double-attached listeners.
  host.addEventListener('click', onCosmClick);
  cosmRendered = true;
}

function buildCosmEntry(slot, item) {
  if (slot === 'accent') {
    const b = document.createElement('button');
    b.type = 'button';
    b.className = 'cosm-swatch';
    b.dataset.value = item.id;
    b.style.background = item.id;
    b.setAttribute('aria-label', item.name);
    b.title = item.name;
    return b;
  }
  const b = document.createElement('button');
  b.type = 'button';
  b.className = 'cosm-tile';
  b.dataset.value = item.id;

  // v0.3.24: shape-based previews instead of color strips + emojis. Each
  // cosmetic category renders a stylized SVG/CSS shape so the tile
  // visually resembles the item — a cape looks like a cape, wings look
  // like wings, an aura looks like a glowing disc. Previously every cape
  // rendered as the same triangle + a 6px color strip and they all
  // looked indistinguishable. This is what the Lunar-style preview the
  // user shared screenshot of actually does.
  const preview = buildCosmPreview(slot, item);
  b.appendChild(preview);

  const name = document.createElement('span');
  name.className = 'cosm-name';
  name.textContent = item.name;
  b.appendChild(name);
  return b;
}

/**
 * Per-item SVG shape library. Every head, trail, and wings entry gets a
 * hand-drawn SVG so the tile shows the actual item rather than a generic
 * emoji. v0.3.25 — replaces the v0.3.24 emoji-on-disk approach the user
 * called out as "not showing the actual model".
 *
 * Conventions:
 *  - viewBox is "0 0 64 64" for heads and trails (square), "0 0 80 50"
 *    for wings (wide).
 *  - Trail SVGs use `currentColor` for fills so the parent .trail-preview
 *    can tint them with the item's color via `color: var(--trail-color)`.
 *  - Wing SVGs use `var(--wings-color)` so each instance tints the wings
 *    independently from a CSS variable set inline.
 *  - Head SVGs use hardcoded colors per item identity (gold crown, black
 *    top hat) because a halo isn't just "any color" — its identity is
 *    "gold ring".
 */
const COSM_SHAPES = {
  // ─── HEADS ───────────────────────────────────────────────────────
  head_halo:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<ellipse cx="32" cy="34" rx="26" ry="7" fill="none" stroke="#ffd24a" stroke-width="5"/>'
    + '<ellipse cx="32" cy="32" rx="26" ry="5" fill="none" stroke="#fff7c0" stroke-width="1.5" opacity="0.8"/>'
    + '</svg>',
  head_crown:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 6 44 L 14 22 L 22 36 L 32 14 L 42 36 L 50 22 L 58 44 Z" fill="#ffd24a" stroke="#9c7b00" stroke-width="2" stroke-linejoin="round"/>'
    + '<rect x="6" y="42" width="52" height="14" fill="#ffd24a" stroke="#9c7b00" stroke-width="2"/>'
    + '<circle cx="32" cy="50" r="3.5" fill="#dc2030"/>'
    + '<circle cx="18" cy="50" r="2.5" fill="#3a9eda"/>'
    + '<circle cx="46" cy="50" r="2.5" fill="#22dd55"/>'
    + '</svg>',
  head_antlers:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<g stroke="#bb8855" stroke-width="3" fill="none" stroke-linecap="round">'
    + '<path d="M 20 54 Q 18 30, 14 22"/>'
    + '<path d="M 14 22 L 8 14 M 16 30 L 9 26 M 18 38 L 10 36"/>'
    + '<path d="M 44 54 Q 46 30, 50 22"/>'
    + '<path d="M 50 22 L 56 14 M 48 30 L 55 26 M 46 38 L 54 36"/>'
    + '</g>'
    + '</svg>',
  head_tophat:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<rect x="18" y="8" width="28" height="36" fill="#1a1a1a" rx="1"/>'
    + '<rect x="18" y="38" width="28" height="5" fill="#dc2030"/>'
    + '<ellipse cx="32" cy="46" rx="26" ry="5" fill="#1a1a1a"/>'
    + '<rect x="18" y="10" width="3" height="32" fill="rgba(255,255,255,0.12)"/>'
    + '</svg>',
  head_tiara:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 8 40 Q 32 12, 56 40 L 56 44 Q 32 22, 8 44 Z" fill="#9ad8ea" stroke="#3a9eda" stroke-width="1.5"/>'
    + '<circle cx="32" cy="18" r="4.5" fill="#3a9eda" stroke="white" stroke-width="1"/>'
    + '<circle cx="32" cy="18" r="1.5" fill="white"/>'
    + '<circle cx="18" cy="28" r="2.5" fill="#9ad8ea" stroke="white" stroke-width="0.6"/>'
    + '<circle cx="46" cy="28" r="2.5" fill="#9ad8ea" stroke="white" stroke-width="0.6"/>'
    + '</svg>',
  head_helmet:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 12 46 Q 12 18, 32 14 Q 52 18, 52 46 Z" fill="#888888" stroke="#444" stroke-width="1.5"/>'
    + '<rect x="12" y="42" width="40" height="10" fill="#666"/>'
    + '<rect x="20" y="32" width="24" height="6" fill="#1a1a1a"/>'
    + '<rect x="20" y="32" width="24" height="2" fill="#3a9eda" opacity="0.6"/>'
    + '</svg>',
  head_beanie:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 14 42 Q 14 16, 32 14 Q 50 16, 50 42 Z" fill="#3a9eda" stroke="#1c6090" stroke-width="1.5"/>'
    + '<rect x="11" y="40" width="42" height="10" fill="#2a7eb8" rx="2"/>'
    + '<circle cx="32" cy="12" r="5" fill="#dc2030" stroke="#7a0c1c" stroke-width="1"/>'
    + '<path d="M 22 22 Q 32 18, 42 22" stroke="rgba(255,255,255,0.25)" stroke-width="1.5" fill="none"/>'
    + '</svg>',
  head_headband:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<rect x="6" y="26" width="52" height="12" fill="#dc2030" rx="2" stroke="#7a0c1c" stroke-width="1"/>'
    + '<line x1="6" y1="32" x2="58" y2="32" stroke="white" stroke-width="2"/>'
    + '<path d="M 50 38 L 56 50 L 54 42 L 60 46 L 56 38" fill="#dc2030" stroke="#7a0c1c" stroke-width="1"/>'
    + '</svg>',
  head_wizard:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 32 4 L 50 42 L 14 42 Z" fill="#a050ff" stroke="#5a2cc4" stroke-width="1.5"/>'
    + '<ellipse cx="32" cy="46" rx="22" ry="5" fill="#a050ff" stroke="#5a2cc4" stroke-width="1.5"/>'
    + '<rect x="10" y="42" width="44" height="5" fill="#5a2cc4"/>'
    + '<circle cx="36" cy="22" r="2" fill="#ffd24a"/>'
    + '<circle cx="22" cy="32" r="1.5" fill="#ffd24a"/>'
    + '<circle cx="40" cy="36" r="1.2" fill="#ffd24a"/>'
    + '</svg>',
  head_cowboy:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 4 36 Q 32 30, 60 36 L 60 44 Q 32 50, 4 44 Z" fill="#bb8855" stroke="#553311" stroke-width="1.5"/>'
    + '<path d="M 18 36 Q 22 14, 32 14 Q 42 14, 46 36 Z" fill="#bb8855" stroke="#553311" stroke-width="1.5"/>'
    + '<path d="M 26 24 Q 32 22, 38 24" stroke="#553311" stroke-width="2.5" fill="none"/>'
    + '<rect x="18" y="32" width="28" height="3" fill="#553311"/>'
    + '<circle cx="32" cy="33" r="1.5" fill="#ffd24a"/>'
    + '</svg>',
  head_cap:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 12 36 Q 12 18, 30 16 Q 50 18, 50 36 Z" fill="#dc2030" stroke="#7a0c1c" stroke-width="1.5"/>'
    + '<path d="M 12 34 L 4 40 L 14 38 Z" fill="#dc2030" stroke="#7a0c1c" stroke-width="1.5"/>'
    + '<circle cx="30" cy="24" r="4" fill="white" stroke="#7a0c1c" stroke-width="1.2"/>'
    + '<path d="M 28 22 L 32 26 M 32 22 L 28 26" stroke="#7a0c1c" stroke-width="1"/>'
    + '</svg>',
  head_phones:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 10 38 Q 10 14, 32 14 Q 54 14, 54 38" stroke="#222" stroke-width="5" fill="none"/>'
    + '<rect x="4" y="32" width="14" height="22" fill="#444" rx="3" stroke="#222" stroke-width="1.5"/>'
    + '<rect x="46" y="32" width="14" height="22" fill="#444" rx="3" stroke="#222" stroke-width="1.5"/>'
    + '<circle cx="11" cy="43" r="4" fill="#222"/>'
    + '<circle cx="53" cy="43" r="4" fill="#222"/>'
    + '<circle cx="11" cy="43" r="1.5" fill="#3a9eda"/>'
    + '<circle cx="53" cy="43" r="1.5" fill="#3a9eda"/>'
    + '</svg>',
  head_mask:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 10 24 Q 32 16, 54 24 Q 54 38, 32 42 Q 10 38, 10 24 Z" fill="#e8e8ec" stroke="#888" stroke-width="1.5"/>'
    + '<ellipse cx="22" cy="28" rx="3.5" ry="4.5" fill="#1a1a1a"/>'
    + '<ellipse cx="42" cy="28" rx="3.5" ry="4.5" fill="#1a1a1a"/>'
    + '<path d="M 24 36 Q 32 34, 40 36" stroke="#1a1a1a" stroke-width="1.5" fill="none"/>'
    + '</svg>',
  head_mohawk:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 32 6 L 28 30 L 24 26 L 22 34 L 18 30 L 14 38 L 30 40 L 34 40 L 50 38 L 46 30 L 42 34 L 40 26 L 36 30 Z" fill="#dc2030" stroke="#7a0c1c" stroke-width="1.5" stroke-linejoin="round"/>'
    + '<path d="M 28 14 L 30 28 M 34 14 L 32 28 M 38 18 L 36 28" stroke="#ff5060" stroke-width="1.2" fill="none"/>'
    + '</svg>',
  head_cat_ears:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 10 38 L 18 12 L 28 32 Z" fill="#ffb0e0" stroke="#cc6090" stroke-width="1.5" stroke-linejoin="round"/>'
    + '<path d="M 36 32 L 46 12 L 54 38 Z" fill="#ffb0e0" stroke="#cc6090" stroke-width="1.5" stroke-linejoin="round"/>'
    + '<path d="M 14 32 L 18 18 L 24 30 Z" fill="#ff70b0"/>'
    + '<path d="M 40 30 L 46 18 L 50 32 Z" fill="#ff70b0"/>'
    + '</svg>',
  head_glasses:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<circle cx="20" cy="32" r="11" fill="rgba(20,20,20,0.6)" stroke="#1a1a1a" stroke-width="2.5"/>'
    + '<circle cx="44" cy="32" r="11" fill="rgba(20,20,20,0.6)" stroke="#1a1a1a" stroke-width="2.5"/>'
    + '<line x1="31" y1="32" x2="33" y2="32" stroke="#1a1a1a" stroke-width="2.5"/>'
    + '<line x1="8" y1="28" x2="3" y2="26" stroke="#1a1a1a" stroke-width="2.5"/>'
    + '<line x1="56" y1="28" x2="61" y2="26" stroke="#1a1a1a" stroke-width="2.5"/>'
    + '<path d="M 16 26 Q 22 24, 24 28" stroke="rgba(255,255,255,0.4)" stroke-width="1.5" fill="none"/>'
    + '<path d="M 40 26 Q 46 24, 48 28" stroke="rgba(255,255,255,0.4)" stroke-width="1.5" fill="none"/>'
    + '</svg>',
  head_pirate:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 4 32 L 32 12 L 60 32 L 50 40 L 32 28 L 14 40 Z" fill="#202020" stroke="#000" stroke-width="1.5" stroke-linejoin="round"/>'
    + '<circle cx="32" cy="24" r="5" fill="white"/>'
    + '<circle cx="30" cy="23" r="1.2" fill="#000"/>'
    + '<circle cx="34" cy="23" r="1.2" fill="#000"/>'
    + '<path d="M 28 27 L 29 30 M 32 27 L 32 30 M 36 27 L 35 30" stroke="#000" stroke-width="1.2"/>'
    + '</svg>',
  head_visor:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<rect x="6" y="24" width="52" height="12" fill="#0adada" stroke="#0a7878" stroke-width="1.5" rx="3"/>'
    + '<rect x="9" y="27" width="46" height="3" fill="white" opacity="0.7"/>'
    + '<rect x="6" y="22" width="52" height="2" fill="#0adada" opacity="0.5"/>'
    + '<rect x="6" y="36" width="52" height="2" fill="#0adada" opacity="0.5"/>'
    + '</svg>',

  // ─── TRAILS (use currentColor — tinted by parent) ───────────────
  trail_fairies:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<g fill="currentColor">'
    + '<path d="M 32 8 L 34 16 L 42 18 L 34 20 L 32 28 L 30 20 L 22 18 L 30 16 Z"/>'
    + '<path d="M 14 36 L 15 40 L 19 41 L 15 42 L 14 46 L 13 42 L 9 41 L 13 40 Z"/>'
    + '<path d="M 50 36 L 51 40 L 55 41 L 51 42 L 50 46 L 49 42 L 45 41 L 49 40 Z"/>'
    + '<circle cx="20" cy="52" r="2" opacity="0.6"/>'
    + '<circle cx="44" cy="52" r="1.5" opacity="0.5"/>'
    + '</g>'
    + '</svg>',
  trail_footsteps:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<g fill="currentColor">'
    + '<ellipse cx="18" cy="24" rx="6" ry="9"/>'
    + '<circle cx="13" cy="14" r="2.2"/>'
    + '<circle cx="19" cy="12" r="2.2"/>'
    + '<circle cx="24" cy="14" r="2"/>'
    + '<ellipse cx="46" cy="44" rx="6" ry="9"/>'
    + '<circle cx="41" cy="34" r="2.2"/>'
    + '<circle cx="47" cy="32" r="2.2"/>'
    + '<circle cx="52" cy="34" r="2"/>'
    + '</g>'
    + '</svg>',
  trail_stars:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<g fill="currentColor">'
    + '<path d="M 32 6 L 36 22 L 52 22 L 39 31 L 44 47 L 32 37 L 20 47 L 25 31 L 12 22 L 28 22 Z"/>'
    + '<path d="M 12 50 L 13 54 L 17 54 L 14 57 L 15 61 L 12 58 L 9 61 L 10 57 L 7 54 L 11 54 Z" opacity="0.7"/>'
    + '<path d="M 52 50 L 53 54 L 57 54 L 54 57 L 55 61 L 52 58 L 49 61 L 50 57 L 47 54 L 51 54 Z" opacity="0.7"/>'
    + '</g>'
    + '</svg>',
  trail_bow:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<g stroke="currentColor" fill="currentColor" stroke-linecap="round" stroke-linejoin="round">'
    + '<line x1="8" y1="56" x2="52" y2="12" stroke-width="3.5"/>'
    + '<polygon points="52,12 38,12 44,22" stroke-width="1"/>'
    + '<line x1="6" y1="58" x2="14" y2="50" stroke-width="2.5"/>'
    + '<line x1="6" y1="58" x2="14" y2="58" stroke-width="2.5"/>'
    + '</g>'
    + '</svg>',
  trail_fire:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 32 8 Q 22 22, 24 36 Q 18 32, 18 46 Q 18 58, 32 58 Q 46 58, 46 46 Q 46 32, 40 36 Q 42 22, 32 8 Z" fill="currentColor"/>'
    + '<path d="M 32 22 Q 28 32, 30 42 Q 30 50, 32 52 Q 34 50, 34 42 Q 36 32, 32 22 Z" fill="white" opacity="0.55"/>'
    + '</svg>',
  trail_ice:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<g stroke="currentColor" stroke-width="3" stroke-linecap="round" fill="none">'
    + '<line x1="32" y1="6" x2="32" y2="58"/>'
    + '<line x1="6" y1="32" x2="58" y2="32"/>'
    + '<line x1="13" y1="13" x2="51" y2="51"/>'
    + '<line x1="51" y1="13" x2="13" y2="51"/>'
    + '<path d="M 32 14 L 28 10 M 32 14 L 36 10 M 32 50 L 28 54 M 32 50 L 36 54" stroke-width="2"/>'
    + '<path d="M 14 32 L 10 28 M 14 32 L 10 36 M 50 32 L 54 28 M 50 32 L 54 36" stroke-width="2"/>'
    + '</g>'
    + '</svg>',
  trail_lightning:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<polygon points="36,4 12,32 26,32 20,60 52,28 38,28 44,4" fill="currentColor" stroke="rgba(0,0,0,0.35)" stroke-width="1.5" stroke-linejoin="round"/>'
    + '<polygon points="36,8 18,30 26,30 22,52 46,28 36,28 40,8" fill="white" opacity="0.25"/>'
    + '</svg>',
  trail_hearts:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 32 54 L 10 30 Q 4 20, 12 12 Q 22 6, 32 18 Q 42 6, 52 12 Q 60 20, 54 30 Z" fill="currentColor"/>'
    + '<path d="M 22 18 Q 18 22, 22 30" stroke="white" stroke-width="2.5" fill="none" opacity="0.45"/>'
    + '</svg>',
  trail_petals:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<g fill="currentColor">'
    + '<ellipse cx="32" cy="14" rx="6" ry="11"/>'
    + '<ellipse cx="50" cy="32" rx="11" ry="6"/>'
    + '<ellipse cx="32" cy="50" rx="6" ry="11"/>'
    + '<ellipse cx="14" cy="32" rx="11" ry="6"/>'
    + '<ellipse cx="20" cy="20" rx="7" ry="9" transform="rotate(-45 20 20)" opacity="0.82"/>'
    + '<ellipse cx="44" cy="20" rx="7" ry="9" transform="rotate(45 44 20)" opacity="0.82"/>'
    + '</g>'
    + '<circle cx="32" cy="32" r="5" fill="#ffd24a" stroke="#9c7b00" stroke-width="1"/>'
    + '</svg>',
  trail_bubbles:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<g fill="currentColor">'
    + '<circle cx="20" cy="20" r="11" opacity="0.5"/>'
    + '<circle cx="44" cy="34" r="9" opacity="0.6"/>'
    + '<circle cx="22" cy="46" r="6" opacity="0.7"/>'
    + '<circle cx="48" cy="14" r="5" opacity="0.5"/>'
    + '</g>'
    + '<circle cx="16" cy="16" r="3" fill="white" opacity="0.7"/>'
    + '<circle cx="40" cy="30" r="2.5" fill="white" opacity="0.65"/>'
    + '<circle cx="19" cy="44" r="1.5" fill="white" opacity="0.6"/>'
    + '</svg>',
  trail_smoke:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<g fill="currentColor">'
    + '<ellipse cx="18" cy="50" rx="15" ry="9" opacity="0.6"/>'
    + '<ellipse cx="34" cy="36" rx="13" ry="8" opacity="0.55"/>'
    + '<ellipse cx="44" cy="22" rx="11" ry="7" opacity="0.5"/>'
    + '<ellipse cx="52" cy="10" rx="7" ry="5" opacity="0.4"/>'
    + '</g>'
    + '</svg>',
  trail_leaves:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 32 6 Q 14 14, 14 32 Q 14 50, 32 58 Q 50 50, 50 32 Q 50 14, 32 6 Z" fill="currentColor"/>'
    + '<line x1="32" y1="6" x2="32" y2="58" stroke="rgba(0,0,0,0.35)" stroke-width="1.8"/>'
    + '<g stroke="rgba(0,0,0,0.25)" stroke-width="1.5" fill="none">'
    + '<path d="M 32 16 L 22 24 M 32 26 L 18 34 M 32 36 L 22 44 M 32 46 L 26 50"/>'
    + '<path d="M 32 16 L 42 24 M 32 26 L 46 34 M 32 36 L 42 44 M 32 46 L 38 50"/>'
    + '</g>'
    + '</svg>',
  trail_magic:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<g fill="currentColor">'
    + '<path d="M 32 2 L 34 28 L 32 32 L 30 28 Z"/>'
    + '<path d="M 32 32 L 32 62 L 30 36 Z"/>'
    + '<path d="M 2 32 L 28 30 L 32 32 L 28 34 Z"/>'
    + '<path d="M 62 32 L 36 30 L 32 32 L 36 34 Z"/>'
    + '<path d="M 12 12 L 28 28 L 30 30 L 14 14 Z"/>'
    + '<path d="M 52 12 L 36 28 L 34 30 L 50 14 Z"/>'
    + '<path d="M 12 52 L 28 36 L 30 34 L 14 50 Z"/>'
    + '<path d="M 52 52 L 36 36 L 34 34 L 50 50 Z"/>'
    + '</g>'
    + '<circle cx="32" cy="32" r="5" fill="white"/>'
    + '</svg>',
  trail_cherry:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<g fill="currentColor">'
    + '<path d="M 32 8 Q 22 14, 26 28 Q 32 28, 32 20 Z" opacity="0.9"/>'
    + '<path d="M 52 22 Q 46 30, 36 28 Q 36 22, 44 20 Z" opacity="0.9"/>'
    + '<path d="M 44 48 Q 32 44, 34 32 Q 40 34, 44 42 Z" opacity="0.9"/>'
    + '<path d="M 12 22 Q 18 30, 28 28 Q 28 22, 20 20 Z" opacity="0.9"/>'
    + '<path d="M 20 48 Q 32 44, 30 32 Q 24 34, 20 42 Z" opacity="0.9"/>'
    + '</g>'
    + '<circle cx="32" cy="32" r="4" fill="#ffd24a"/>'
    + '<circle cx="32" cy="32" r="2" fill="white"/>'
    + '</svg>',
  trail_confetti:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<g stroke="rgba(0,0,0,0.2)" stroke-width="0.5">'
    + '<rect x="12" y="14" width="6" height="3" fill="currentColor" transform="rotate(-30 12 14)"/>'
    + '<rect x="40" y="20" width="7" height="3" fill="#ffd24a" transform="rotate(45 40 20)"/>'
    + '<rect x="22" y="40" width="6" height="3" fill="#3a9eda" transform="rotate(-60 22 40)"/>'
    + '<rect x="48" y="46" width="7" height="3" fill="#22dd55" transform="rotate(30 48 46)"/>'
    + '<rect x="14" y="34" width="5" height="3" fill="#ff8a40"/>'
    + '<rect x="32" y="10" width="6" height="3" fill="#a050ff" transform="rotate(60 32 10)"/>'
    + '<rect x="36" y="50" width="6" height="3" fill="currentColor" transform="rotate(-45 36 50)"/>'
    + '</g>'
    + '</svg>',
  trail_skulls:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<path d="M 14 18 Q 14 6, 32 6 Q 50 6, 50 18 L 50 38 L 44 38 L 44 48 L 36 48 L 36 44 L 32 44 L 28 44 L 28 48 L 20 48 L 20 38 L 14 38 Z" fill="currentColor" stroke="rgba(0,0,0,0.4)" stroke-width="1.5" stroke-linejoin="round"/>'
    + '<circle cx="24" cy="24" r="4" fill="#000"/>'
    + '<circle cx="40" cy="24" r="4" fill="#000"/>'
    + '<path d="M 28 34 L 32 30 L 36 34" stroke="#000" stroke-width="2" fill="none"/>'
    + '</svg>',
  trail_comet:
    '<svg viewBox="0 0 64 64" class="cosm-svg">'
    + '<g fill="currentColor">'
    + '<path d="M 44 16 L 6 56 L 18 50 L 50 22 Z" opacity="0.45"/>'
    + '<path d="M 46 18 L 14 54 L 24 50 L 48 22 Z" opacity="0.7"/>'
    + '<circle cx="46" cy="18" r="7"/>'
    + '</g>'
    + '<circle cx="46" cy="18" r="3.5" fill="white"/>'
    + '</svg>',

  // ─── WINGS (use --wings-color CSS var) ──────────────────────────
  wings_dragon:
    '<svg viewBox="0 0 80 50" class="cosm-svg">'
    + '<g fill="var(--wings-color)" stroke="rgba(0,0,0,0.35)" stroke-width="1">'
    + '<path d="M 40 24 Q 24 4, 4 8 Q 4 14, 10 22 L 4 18 Q 12 30, 20 32 L 14 34 Q 22 38, 32 32 Z"/>'
    + '<path d="M 40 24 Q 56 4, 76 8 Q 76 14, 70 22 L 76 18 Q 68 30, 60 32 L 66 34 Q 58 38, 48 32 Z"/>'
    + '</g>'
    + '<g stroke="rgba(0,0,0,0.5)" stroke-width="1.2" fill="none">'
    + '<path d="M 22 32 L 14 16 M 28 32 L 26 12 M 34 30 L 34 10"/>'
    + '<path d="M 58 32 L 66 16 M 52 32 L 54 12 M 46 30 L 46 10"/>'
    + '</g>'
    + '</svg>',
  wings_angel:
    '<svg viewBox="0 0 80 50" class="cosm-svg">'
    + '<g fill="var(--wings-color)" stroke="rgba(0,0,0,0.18)" stroke-width="0.8">'
    + '<path d="M 40 24 Q 24 6, 4 14 Q 4 22, 14 28 Q 26 32, 40 30 Z"/>'
    + '<path d="M 40 26 Q 28 14, 16 20 Q 22 26, 28 28 Q 36 30, 40 28 Z" opacity="0.78"/>'
    + '<path d="M 40 24 Q 56 6, 76 14 Q 76 22, 66 28 Q 54 32, 40 30 Z"/>'
    + '<path d="M 40 26 Q 52 14, 64 20 Q 58 26, 52 28 Q 44 30, 40 28 Z" opacity="0.78"/>'
    + '</g>'
    + '<g stroke="rgba(255,255,255,0.35)" stroke-width="1" fill="none">'
    + '<path d="M 10 18 L 18 26 M 18 14 L 26 24 M 26 12 L 32 22"/>'
    + '<path d="M 70 18 L 62 26 M 62 14 L 54 24 M 54 12 L 48 22"/>'
    + '</g>'
    + '</svg>',
  wings_demon:
    '<svg viewBox="0 0 80 50" class="cosm-svg">'
    + '<g fill="var(--wings-color)" stroke="rgba(0,0,0,0.45)" stroke-width="1" stroke-linejoin="round">'
    + '<path d="M 40 26 L 6 10 L 10 20 L 2 18 L 14 30 L 8 30 L 22 38 L 18 32 L 28 38 L 26 30 L 36 32 Z"/>'
    + '<path d="M 40 26 L 74 10 L 70 20 L 78 18 L 66 30 L 72 30 L 58 38 L 62 32 L 52 38 L 54 30 L 44 32 Z"/>'
    + '</g>'
    + '</svg>',
  wings_fairy:
    '<svg viewBox="0 0 80 50" class="cosm-svg">'
    + '<g fill="var(--wings-color)" stroke="rgba(0,0,0,0.25)" stroke-width="0.8" opacity="0.75">'
    + '<ellipse cx="20" cy="18" rx="14" ry="10"/>'
    + '<ellipse cx="22" cy="32" rx="11" ry="8"/>'
    + '<ellipse cx="60" cy="18" rx="14" ry="10"/>'
    + '<ellipse cx="58" cy="32" rx="11" ry="8"/>'
    + '</g>'
    + '<g fill="rgba(255,255,255,0.6)">'
    + '<circle cx="16" cy="14" r="1.5"/>'
    + '<circle cx="22" cy="20" r="1"/>'
    + '<circle cx="62" cy="14" r="1.5"/>'
    + '<circle cx="58" cy="20" r="1"/>'
    + '</g>'
    + '</svg>',
  wings_butterfly:
    '<svg viewBox="0 0 80 50" class="cosm-svg">'
    + '<g stroke="rgba(0,0,0,0.45)" stroke-width="1">'
    + '<path d="M 40 22 Q 20 4, 6 14 Q 4 22, 22 26 Q 32 26, 40 22 Z" fill="var(--wings-color)"/>'
    + '<path d="M 40 22 Q 60 4, 74 14 Q 76 22, 58 26 Q 48 26, 40 22 Z" fill="var(--wings-color)"/>'
    + '<path d="M 40 24 Q 28 34, 14 40 Q 16 46, 32 44 Q 38 38, 40 32 Z" fill="var(--wings-color)" opacity="0.85"/>'
    + '<path d="M 40 24 Q 52 34, 66 40 Q 64 46, 48 44 Q 42 38, 40 32 Z" fill="var(--wings-color)" opacity="0.85"/>'
    + '</g>'
    + '<g>'
    + '<circle cx="20" cy="18" r="3.5" fill="rgba(255,255,255,0.55)"/>'
    + '<circle cx="60" cy="18" r="3.5" fill="rgba(255,255,255,0.55)"/>'
    + '<circle cx="20" cy="18" r="1.5" fill="#1a1a1a"/>'
    + '<circle cx="60" cy="18" r="1.5" fill="#1a1a1a"/>'
    + '</g>'
    + '</svg>',
  wings_phoenix:
    '<svg viewBox="0 0 80 50" class="cosm-svg">'
    + '<g fill="var(--wings-color)" stroke="rgba(0,0,0,0.25)" stroke-width="0.8">'
    + '<path d="M 40 24 Q 22 6, 2 14 Q 4 24, 16 30 Q 28 32, 40 28 Z"/>'
    + '<path d="M 40 24 Q 58 6, 78 14 Q 76 24, 64 30 Q 52 32, 40 28 Z"/>'
    + '</g>'
    + '<g fill="#ffd24a" opacity="0.7">'
    + '<path d="M 14 24 L 10 18 L 16 22 L 12 14 L 18 20 Z"/>'
    + '<path d="M 66 24 L 70 18 L 64 22 L 68 14 L 62 20 Z"/>'
    + '</g>'
    + '</svg>',
  wings_bat:
    '<svg viewBox="0 0 80 50" class="cosm-svg">'
    + '<g fill="var(--wings-color)" stroke="rgba(0,0,0,0.5)" stroke-width="1">'
    + '<path d="M 40 26 Q 26 12, 4 16 L 12 22 L 6 26 L 16 30 L 10 34 L 22 36 Q 32 38, 40 30 Z"/>'
    + '<path d="M 40 26 Q 54 12, 76 16 L 68 22 L 74 26 L 64 30 L 70 34 L 58 36 Q 48 38, 40 30 Z"/>'
    + '</g>'
    + '<g stroke="rgba(0,0,0,0.5)" stroke-width="1" fill="none">'
    + '<path d="M 14 22 L 26 28 M 18 30 L 30 30 M 22 34 L 34 32"/>'
    + '<path d="M 66 22 L 54 28 M 62 30 L 50 30 M 58 34 L 46 32"/>'
    + '</g>'
    + '</svg>',
  wings_crystal:
    '<svg viewBox="0 0 80 50" class="cosm-svg">'
    + '<g fill="var(--wings-color)" stroke="rgba(255,255,255,0.5)" stroke-width="1">'
    + '<polygon points="40,26 6,8 4,18 12,22 2,30 16,32 8,42 24,36 22,46 36,30"/>'
    + '<polygon points="40,26 74,8 76,18 68,22 78,30 64,32 72,42 56,36 58,46 44,30"/>'
    + '</g>'
    + '<g fill="rgba(255,255,255,0.45)">'
    + '<polygon points="20,16 22,22 14,22"/>'
    + '<polygon points="60,16 58,22 66,22"/>'
    + '</g>'
    + '</svg>',
  wings_mech:
    '<svg viewBox="0 0 80 50" class="cosm-svg">'
    + '<g fill="var(--wings-color)" stroke="rgba(0,0,0,0.4)" stroke-width="1">'
    + '<rect x="4" y="14" width="34" height="6" rx="2"/>'
    + '<rect x="6" y="22" width="30" height="5" rx="2"/>'
    + '<rect x="10" y="29" width="24" height="4" rx="2"/>'
    + '<rect x="42" y="14" width="34" height="6" rx="2"/>'
    + '<rect x="44" y="22" width="30" height="5" rx="2"/>'
    + '<rect x="46" y="29" width="24" height="4" rx="2"/>'
    + '</g>'
    + '<g fill="rgba(255,255,255,0.3)">'
    + '<rect x="6" y="15" width="30" height="1.5"/>'
    + '<rect x="44" y="15" width="30" height="1.5"/>'
    + '</g>'
    + '</svg>',
  wings_astral:
    '<svg viewBox="0 0 80 50" class="cosm-svg">'
    + '<g fill="var(--wings-color)" stroke="rgba(0,0,0,0.25)" stroke-width="0.8">'
    + '<path d="M 40 24 Q 22 8, 4 14 Q 6 22, 18 28 Q 30 30, 40 28 Z"/>'
    + '<path d="M 40 24 Q 58 8, 76 14 Q 74 22, 62 28 Q 50 30, 40 28 Z"/>'
    + '</g>'
    + '<g fill="#fff7c0">'
    + '<circle cx="12" cy="20" r="1.5"/>'
    + '<circle cx="22" cy="14" r="1.2"/>'
    + '<circle cx="30" cy="22" r="1"/>'
    + '<circle cx="68" cy="20" r="1.5"/>'
    + '<circle cx="58" cy="14" r="1.2"/>'
    + '<circle cx="50" cy="22" r="1"/>'
    + '</g>'
    + '</svg>',
  wings_toxic:
    '<svg viewBox="0 0 80 50" class="cosm-svg">'
    + '<g fill="var(--wings-color)" stroke="rgba(0,0,0,0.3)" stroke-width="0.8">'
    + '<path d="M 40 24 Q 22 8, 4 16 Q 8 26, 22 30 Q 32 30, 40 26 Z"/>'
    + '<path d="M 40 24 Q 58 8, 76 16 Q 72 26, 58 30 Q 48 30, 40 26 Z"/>'
    + '</g>'
    + '<g fill="var(--wings-color)" opacity="0.75">'
    + '<ellipse cx="12" cy="38" rx="2" ry="3"/>'
    + '<ellipse cx="22" cy="42" rx="1.5" ry="2.5"/>'
    + '<ellipse cx="30" cy="40" rx="1.2" ry="2"/>'
    + '<ellipse cx="68" cy="38" rx="2" ry="3"/>'
    + '<ellipse cx="58" cy="42" rx="1.5" ry="2.5"/>'
    + '<ellipse cx="50" cy="40" rx="1.2" ry="2"/>'
    + '</g>'
    + '</svg>',
  wings_void:
    '<svg viewBox="0 0 80 50" class="cosm-svg">'
    + '<g fill="var(--wings-color)" stroke="rgba(0,0,0,0.2)" stroke-width="0.8" style="filter: blur(0.6px)">'
    + '<path d="M 40 24 Q 22 6, 4 14 Q 4 24, 16 30 Q 30 30, 40 28 Z"/>'
    + '<path d="M 40 24 Q 58 6, 76 14 Q 76 24, 64 30 Q 50 30, 40 28 Z"/>'
    + '</g>'
    + '<g fill="white" opacity="0.3">'
    + '<circle cx="14" cy="20" r="1"/>'
    + '<circle cx="24" cy="24" r="0.8"/>'
    + '<circle cx="66" cy="20" r="1"/>'
    + '<circle cx="56" cy="24" r="0.8"/>'
    + '</g>'
    + '</svg>',
  wings_origami:
    '<svg viewBox="0 0 80 50" class="cosm-svg">'
    + '<g fill="var(--wings-color)" stroke="rgba(0,0,0,0.4)" stroke-width="1" stroke-linejoin="round">'
    + '<polygon points="40,24 4,8 14,24 4,32 22,30 14,40 30,28"/>'
    + '<polygon points="40,24 76,8 66,24 76,32 58,30 66,40 50,28"/>'
    + '</g>'
    + '<g stroke="rgba(0,0,0,0.3)" stroke-width="0.8" fill="none">'
    + '<line x1="4" y1="8" x2="22" y2="30"/>'
    + '<line x1="14" y1="24" x2="30" y2="28"/>'
    + '<line x1="76" y1="8" x2="58" y2="30"/>'
    + '<line x1="66" y1="24" x2="50" y2="28"/>'
    + '</g>'
    + '</svg>',
  wings_shark:
    '<svg viewBox="0 0 80 50" class="cosm-svg">'
    + '<g fill="var(--wings-color)" stroke="rgba(0,0,0,0.4)" stroke-width="1.2" stroke-linejoin="round">'
    + '<path d="M 40 44 Q 36 12, 28 6 Q 24 8, 22 16 Q 28 28, 32 44 Z"/>'
    + '<path d="M 40 44 Q 44 12, 52 6 Q 56 8, 58 16 Q 52 28, 48 44 Z"/>'
    + '</g>'
    + '<g stroke="rgba(255,255,255,0.3)" stroke-width="1" fill="none">'
    + '<path d="M 30 12 Q 32 22, 36 36"/>'
    + '<path d="M 50 12 Q 48 22, 44 36"/>'
    + '</g>'
    + '</svg>',
};

/**
 * Build the visual preview block for a cosmetic tile. Routed by slot +
 * id-prefix so capes get cape-shapes, wings get wing-shapes, etc. Falls
 * back to the emoji icon for anything that doesn't have a dedicated
 * preview (e.g. the 'None' tile or future slots).
 */
function buildCosmPreview(slot, item) {
  // 'None' tile in any slot — show a muted crossed-out circle so it
  // doesn't compete visually with the colorful real cosmetics.
  if (item.id === 'none') {
    const none = document.createElement('div');
    none.className = 'cosm-preview cosm-preview-none';
    none.setAttribute('aria-hidden', 'true');
    none.innerHTML =
      '<svg viewBox="0 0 32 32" width="32" height="32">' +
        '<circle cx="16" cy="16" r="13" fill="none" stroke="currentColor" stroke-width="2"/>' +
        '<line x1="6" y1="26" x2="26" y2="6" stroke="currentColor" stroke-width="2"/>' +
      '</svg>';
    return none;
  }

  // Cape — rectangular cloth with mesh weave. Looks like Lunar's cape
  // thumbnails: a piece of dyed fabric you'd hang on a character's back.
  if (slot === 'back' && item.id.startsWith('cape_') && item.color) {
    const cape = document.createElement('div');
    cape.className = 'cosm-preview cape-preview';
    cape.style.setProperty('--cape-color', item.color);
    cape.setAttribute('aria-hidden', 'true');
    // Collar notch as a child div so we don't fight CSS pseudo-element
    // limitations across themes.
    cape.innerHTML = '<div class="cape-collar"></div>';
    return cape;
  }

  // Wings — pull the per-type SVG from the shape library so dragon wings
  // look like dragon wings, butterfly wings have butterfly markings, etc.
  // The SVG fills use var(--wings-color) so the inline color still tints
  // the wing membranes.
  if (slot === 'back' && item.id.startsWith('wings_') && item.color) {
    const wings = document.createElement('div');
    wings.className = 'cosm-preview wings-preview';
    wings.style.setProperty('--wings-color', item.color);
    wings.setAttribute('aria-hidden', 'true');
    wings.innerHTML = COSM_SHAPES[item.id] || '';
    return wings;
  }

  // Head — hand-drawn SVG of the actual item (crown, top hat, halo,
  // glasses, etc). Each item's SVG carries its own identity colors;
  // the tinted disk behind it just adds a soft accent.
  if (slot === 'head' && COSM_SHAPES[item.id]) {
    const head = document.createElement('div');
    head.className = 'cosm-preview head-preview';
    if (item.color) head.style.setProperty('--head-color', item.color);
    head.setAttribute('aria-hidden', 'true');
    head.innerHTML = COSM_SHAPES[item.id];
    return head;
  }

  // Trail — SVG drawing of the particle (fire flame, snowflake, lightning
  // bolt, etc) with `color: var(--trail-color)` so the SVG's currentColor
  // fills pick up the item's tint.
  if (slot === 'trail' && COSM_SHAPES[item.id]) {
    const trail = document.createElement('div');
    trail.className = 'cosm-preview trail-preview';
    trail.style.color = item.color;
    trail.setAttribute('aria-hidden', 'true');
    trail.innerHTML = COSM_SHAPES[item.id];
    return trail;
  }

  // Aura — glowing colored disc, radial gradient. The aura's nature is
  // "a colored glow around the character" so showing it as a soft circle
  // is the most faithful representation.
  if (slot === 'aura' && item.color) {
    const aura = document.createElement('div');
    aura.className = 'cosm-preview aura-preview';
    aura.style.setProperty('--aura-color', item.color);
    aura.setAttribute('aria-hidden', 'true');
    return aura;
  }

  // Fallback — emoji on tinted backdrop. Covers any slot we didn't
  // special-case above.
  const fallback = document.createElement('div');
  fallback.className = 'cosm-preview cosm-preview-fallback';
  if (item.color) fallback.style.setProperty('--fallback-color', item.color);
  fallback.setAttribute('aria-hidden', 'true');
  fallback.textContent = item.icon || '';
  return fallback;
}

/**
 * Build the character mannequin shown at the top of the cosmetics tab.
 *
 * v0.3.26: real 3D Minecraft mesh via skinview3d + three.js. Renders the
 * default Steve skin and wraps the equipped cape around the model. Other
 * cosmetic slots (head accessory, wings, aura, trail) don't have a
 * native Minecraft mesh slot — they're shown as SVG overlays positioned
 * around the 3D canvas via CSS so the user still sees what's equipped.
 *
 * skinview3d.bundle.js is loaded as a global in index.html before
 * main.js runs, so `skinview3d.SkinViewer` is guaranteed defined here.
 */
function buildCharacterPreview() {
  const mount = document.createElement('div');
  mount.className = 'cosm-character-mount';
  mount.id = 'cosm-character-mount';
  mount.innerHTML = `
    <div class="char-stage">
      <!-- Aura sits behind the canvas, set as a glow background -->
      <div class="char-aura" id="char-aura" aria-hidden="true"></div>
      <!-- Real 3D Steve mesh (skinview3d → three.js). 180×240 in v0.3.36
           (was 240×320) — sized to fit the sticky right-side sidebar
           of the cosmetics tab so it doesn't dominate the screen. -->
      <canvas id="char-canvas" class="char-canvas"
              width="180" height="240" aria-label="Character preview"></canvas>
      <!-- Wings overlay — SVG positioned behind the canvas. Three.js
           ignores DOM overlap so we use z-index + position:absolute. -->
      <div class="char-wings-overlay" id="char-wings-overlay"
           aria-hidden="true"></div>
      <!-- Head accessory overlay — SVG floating above the head. -->
      <div class="char-head-overlay" id="char-head-overlay"
           aria-hidden="true"></div>
      <!-- Trail particles at the feet. -->
      <div class="char-trail-overlay" id="char-trail-overlay"
           aria-hidden="true"></div>
    </div>
    <div class="char-info">
      <div class="char-info-label">PREVIEW</div>
      <ul class="char-equipped" id="char-equipped">
        <li data-slot="back">  <span class="char-slot-name">Back</span>
                               <span class="char-slot-val">None</span></li>
        <li data-slot="head">  <span class="char-slot-name">Head</span>
                               <span class="char-slot-val">None</span></li>
        <li data-slot="trail"> <span class="char-slot-name">Trail</span>
                               <span class="char-slot-val">None</span></li>
        <li data-slot="aura">  <span class="char-slot-name">Aura</span>
                               <span class="char-slot-val">None</span></li>
        <li data-slot="accent"><span class="char-slot-name">Accent</span>
                               <span class="char-slot-val">—</span></li>
      </ul>
      <div class="char-info-hint">
        Drag to rotate. Click any item below to equip — selections save
        instantly.
      </div>
    </div>
  `;
  // Defer viewer init to the next tick so the canvas is fully in the DOM
  // (and has its real layout dimensions) by the time three.js measures.
  setTimeout(initSkinViewer, 0);
  return mount;
}

/** Live SkinViewer instance — null until the cosmetics tab opens. */
let skinViewer = null;

/**
 * Initialize the skinview3d viewer on the cosmetics tab's canvas. Safe
 * to call multiple times — the second call just re-binds to the new
 * canvas if the tab was re-rendered.
 */
function initSkinViewer() {
  const canvas = document.getElementById('char-canvas');
  if (!canvas) return;
  if (typeof skinview3d === 'undefined') {
    console.warn('[shadow] skinview3d not loaded — character preview disabled');
    return;
  }
  // Dispose any previous viewer so we don't leak WebGL contexts when the
  // settings dialog is reopened.
  if (skinViewer) {
    try { skinViewer.dispose(); } catch (_) {}
    skinViewer = null;
  }
  try {
    skinViewer = new skinview3d.SkinViewer({
      canvas,
      width: 180,
      height: 240,
      skin: 'textures/steve.png',
    });
    // Subtle idle pose — Lunar-style stationary stance with a slow rock.
    skinViewer.fov = 50;
    skinViewer.zoom = 0.85;
    // Disable orbital pan/zoom but keep rotation so the user can spin to
    // see the cape on the back. Drag = rotate, scroll = no-op.
    if (skinViewer.controls) {
      skinViewer.controls.enableRotate = true;
      skinViewer.controls.enableZoom   = false;
      skinViewer.controls.enablePan    = false;
    }
    // Walking animation makes the cape sway naturally so it doesn't look
    // pasted on. Fallback to no animation if the API differs.
    try {
      skinViewer.animation = new skinview3d.WalkingAnimation();
      if (skinViewer.animation) skinViewer.animation.speed = 0.5;
    } catch (_) { /* older skinview3d — leave static */ }
  } catch (e) {
    console.error('[shadow] failed to init SkinViewer:', e);
    return;
  }
  // Apply whatever's currently selected.
  updateCharacterPreview();
}

/**
 * Build a 64×32 cape texture filled with `color` plus a faint horizontal
 * weave so the cape doesn't look like a flat sticker on the model. The
 * Minecraft cape texture format uses the top-left 22×17 region for the
 * visible cape pixels — filling the whole canvas is overkill but
 * harmless. Returns a data: URL suitable for `viewer.loadCape()`.
 */
function buildCapeTexture(color) {
  const c = document.createElement('canvas');
  c.width  = 64;
  c.height = 32;
  const ctx = c.getContext('2d');
  // Base fill.
  ctx.fillStyle = color;
  ctx.fillRect(0, 0, 64, 32);
  // Horizontal weave hint — every other row gets a 12 %-darken band.
  ctx.fillStyle = 'rgba(0,0,0,0.12)';
  for (let y = 1; y < 32; y += 2) ctx.fillRect(0, y, 64, 1);
  // Vertical weave — much subtler.
  ctx.fillStyle = 'rgba(0,0,0,0.06)';
  for (let x = 1; x < 64; x += 3) ctx.fillRect(x, 0, 1, 32);
  // Top-edge highlight on the cape's visible front face — gives the
  // appearance of light hitting the upper shoulder of the cloth.
  ctx.fillStyle = 'rgba(255,255,255,0.10)';
  ctx.fillRect(1, 1, 10, 1);
  return c.toDataURL();
}

async function onCosmClick(e) {
  const target = e.target.closest('.cosm-tile, .cosm-swatch');
  if (!target) return;
  const section = target.closest('.cosm-section');
  if (!section) return;
  const slot = section.dataset.slot;
  const value = target.dataset.value;

  // "none" tiles persist as null so the HUD reads "no cosmetic in slot".
  // For the accent slot every value is a real selection.
  cosmCache[slot] = (value === 'none') ? null : value;
  applyCosmeticsToUI();
  try {
    await invoke('save_cosmetics', { cosm: cosmCache });
  } catch (err) {
    console.warn('[shadow] save_cosmetics failed:', err);
  }
}

function applyCosmeticsToUI() {
  for (const { key } of COSM_SLOTS) {
    const wanted = cosmCache[key] ?? COSM_DEFAULTS[key];
    const section = document.querySelector(`.cosm-section[data-slot="${key}"]`);
    if (!section) continue;
    section.querySelectorAll('.cosm-tile, .cosm-swatch').forEach(el => {
      const isSelected = el.dataset.value === wanted;
      el.classList.toggle('selected', isSelected);
      el.setAttribute('aria-pressed', isSelected ? 'true' : 'false');
    });
  }
  updateCharacterPreview();
}

/**
 * Reflect the current cosmCache onto the 3D character mesh at the top
 * of the cosmetics tab. Capes drive the real Minecraft cape slot via
 * skinview3d.loadCape(); other slots are rendered as SVG overlays
 * positioned around the canvas.
 */
function updateCharacterPreview() {
  const mount = document.getElementById('cosm-character-mount');
  if (!mount) return;  // tab not rendered yet
  const lookup = id => {
    for (const slot of Object.keys(COSMETICS_CATALOG)) {
      const hit = COSMETICS_CATALOG[slot].find(i => i.id === id);
      if (hit) return hit;
    }
    return null;
  };

  // Back slot:
  //  - cape_*  → drive the 3D mesh's cape texture
  //  - wings_* → render as SVG behind the canvas (no native MC slot)
  const backId = cosmCache.back;
  const back = backId && backId !== 'none' ? lookup(backId) : null;
  const wingsOverlay = document.getElementById('char-wings-overlay');
  if (wingsOverlay) wingsOverlay.innerHTML = '';
  if (skinViewer && back && back.id.startsWith('cape_')) {
    try {
      skinViewer.loadCape(buildCapeTexture(back.color));
    } catch (e) { console.warn('[shadow] loadCape failed:', e); }
  } else if (skinViewer) {
    try { skinViewer.resetCape(); } catch (_) {}
  }
  if (wingsOverlay && back && back.id.startsWith('wings_')) {
    wingsOverlay.style.setProperty('--wings-color', back.color);
    wingsOverlay.innerHTML = COSM_SHAPES[back.id] || '';
    wingsOverlay.style.display = 'block';
  } else if (wingsOverlay) {
    wingsOverlay.style.display = 'none';
  }

  // Head — SVG overlay floating above the canvas. The 3D model's head
  // is rendered at a known on-canvas position; CSS pins the overlay
  // there.
  const headId = cosmCache.head;
  const head = headId && headId !== 'none' ? lookup(headId) : null;
  const headOverlay = document.getElementById('char-head-overlay');
  if (headOverlay) {
    headOverlay.innerHTML = head && COSM_SHAPES[head.id] ? COSM_SHAPES[head.id] : '';
    headOverlay.style.display = head ? 'flex' : 'none';
  }

  // Trail — SVG overlay at the bottom of the canvas.
  const trailId = cosmCache.trail;
  const trail = trailId && trailId !== 'none' ? lookup(trailId) : null;
  const trailOverlay = document.getElementById('char-trail-overlay');
  if (trailOverlay) {
    if (trail) {
      trailOverlay.style.color = trail.color;
      trailOverlay.innerHTML = COSM_SHAPES[trail.id] || '';
      trailOverlay.style.display = 'flex';
    } else {
      trailOverlay.innerHTML = '';
      trailOverlay.style.display = 'none';
    }
  }

  // Aura — radial glow behind the whole canvas.
  const auraId = cosmCache.aura;
  const aura = auraId && auraId !== 'none' ? lookup(auraId) : null;
  const auraEl = document.getElementById('char-aura');
  if (auraEl) {
    if (aura) {
      auraEl.style.setProperty('--aura-color', aura.color);
      auraEl.style.display = 'block';
    } else {
      auraEl.style.display = 'none';
    }
  }

  // Equipment summary list on the right side of the preview.
  const list = document.getElementById('char-equipped');
  if (list) {
    const setRow = (slot, name) => {
      const row = list.querySelector(`li[data-slot="${slot}"] .char-slot-val`);
      if (row) row.textContent = name;
    };
    setRow('back',   back   ? back.name   : 'None');
    setRow('head',   head   ? head.name   : 'None');
    setRow('trail',  trail  ? trail.name  : 'None');
    setRow('aura',   aura   ? aura.name   : 'None');
    const accent = lookup(cosmCache.accent || COSM_DEFAULTS.accent);
    setRow('accent', accent ? accent.name : '—');
  }
}

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
  // v0.3.37: dropped Python-era fields (resource_files_present, candidates_*,
  // python probe) which the post-v0.3.0 Rust port no longer emits. The
  // renderer was showing `undefined` for those and the user couldn't tell
  // what was real vs broken. New shape matches the actual Rust struct.
  const javaLine = d.java_detected
    ? `${d.java_major || '?'} at ${d.java_detected}`
    : '✗ no JDK installed (launcher will download one on first PLAY)';
  return [
    `Launcher version:        ${d.launcher_version}`,
    `Executable:              ${d.exe_path}`,
    `Executable directory:    ${d.exe_dir}`,
    `Resolved project root:   ${d.project_root}`,
    `Has game_dir?            ${d.project_root_has_game_dir ? 'YES' : 'NO'}`,
    `Java:                    ${javaLine}`,
    `Working directory:       ${d.cwd}`,
  ].join('\n');
}

$('diag-refresh')?.addEventListener('click', refreshDiagnostics);

// v0.3.37: focused Shadow Chat self-diagnosis. Runs every check that
// matters for chat to actually work — sign-in state, mod jar present,
// auth file present, relay reachable — and dumps the result as a
// pasteable text block.
$('diag-chat')?.addEventListener('click', async () => {
  const btn = $('diag-chat');
  const out = $('diag-output');
  if (!btn || !out) return;
  const orig = btn.textContent;
  btn.disabled = true;
  btn.textContent = 'Checking…';
  try {
    out.textContent = await invoke('chat_diagnostics');
  } catch (e) {
    out.textContent = 'chat_diagnostics command failed: ' + e;
  }
  btn.textContent = orig;
  btn.disabled = false;
});

// End-to-end chat round-trip test. Opens a WebSocket using the user's
// real MS token, joins a "test:diag" channel, sends a message, waits
// for the relay to echo it back. Validates the whole pipeline (token
// verification, channel routing, message broadcast) WITHOUT needing
// Minecraft to launch. If this passes, in-game chat will work; if it
// fails, the error message tells the user exactly where it broke.
$('diag-test-chat')?.addEventListener('click', async () => {
  const btn = $('diag-test-chat');
  const out = $('diag-output');
  if (!btn || !out) return;
  const orig = btn.textContent;
  btn.disabled = true;
  btn.textContent = 'Testing…';
  const append = (line) => { out.textContent = (out.textContent + '\n' + line).trim(); };

  let token;
  try {
    token = await invoke('chat_test_token');
  } catch (e) {
    append(`[test-chat] ✗ ${e}`);
    btn.textContent = orig; btn.disabled = false;
    return;
  }

  const ts = Date.now();
  const channel = `dm:diagtest-${ts}-${Math.random().toString(36).slice(2, 8)}`;
  const url = `wss://shadow-chat-relay.edisongushf.workers.dev/ws?token=${encodeURIComponent(token)}&channel=${encodeURIComponent(channel)}`;
  append(`[test-chat] opening WebSocket to channel ${channel}…`);

  const echoMarker = `diagnostic-${ts}`;
  // 15s overall timeout so the button doesn't hang forever if the
  // server never responds.
  const deadline = setTimeout(() => {
    append('[test-chat] ✗ timeout — no echo received in 15s');
    try { ws.close(); } catch (_) {}
    btn.textContent = orig; btn.disabled = false;
  }, 15000);

  const ws = new WebSocket(url);
  const t0 = performance.now();
  ws.onopen = () => {
    append(`[test-chat] ✓ connected (${Math.round(performance.now() - t0)}ms)`);
    ws.send(JSON.stringify({ op: 'msg', text: echoMarker }));
    append('[test-chat] sent diagnostic message, waiting for echo…');
  };
  ws.onmessage = (e) => {
    let m;
    try { m = JSON.parse(e.data); } catch { return; }
    if (m.op === 'msg' && m.text === echoMarker) {
      clearTimeout(deadline);
      append(`[test-chat] ✓ echo received (round-trip ${Math.round(performance.now() - t0)}ms total)`);
      append('[test-chat] ✓ Shadow Chat is working end-to-end.');
      try { ws.close(1000, 'test complete'); } catch (_) {}
      btn.textContent = orig; btn.disabled = false;
    } else if (m.op === 'error') {
      append(`[test-chat] relay error: ${m.msg}`);
    } else if (m.op === 'presence') {
      append(`[test-chat] presence event (${(m.users || []).length} member${(m.users || []).length === 1 ? '' : 's'} in test channel)`);
    }
  };
  ws.onerror = () => {
    // The browser hides WebSocket error details for security.
    // Real error info comes via onclose's code/reason.
    append('[test-chat] ✗ WebSocket error');
  };
  ws.onclose = (e) => {
    clearTimeout(deadline);
    if (e.code !== 1000) {
      // 1006 abnormal closure usually means auth failure (the relay
      // rejected the upgrade with 401 before the upgrade completed).
      const why = e.code === 1006
        ? '✗ relay rejected the connection. Most likely: Microsoft token expired or account doesn\'t own Minecraft.'
        : `✗ socket closed unexpectedly (code ${e.code}${e.reason ? ' ' + e.reason : ''})`;
      append(`[test-chat] ${why}`);
      btn.textContent = orig; btn.disabled = false;
    }
  };
});

// Ping the relay. Just a fetch from JS — no Rust trip required.
// Appends result to the diag-output rather than replacing so the user
// can see both the full diagnose-chat output AND the ping result.
$('diag-ping-relay')?.addEventListener('click', async () => {
  const btn = $('diag-ping-relay');
  const out = $('diag-output');
  if (!btn || !out) return;
  const orig = btn.textContent;
  btn.disabled = true;
  btn.textContent = 'Pinging…';
  const url = 'https://shadow-chat-relay.edisongushf.workers.dev/health';
  const t0 = performance.now();
  let resultLine;
  try {
    const r = await fetch(url, { method: 'GET' });
    const body = (await r.text()).trim();
    const ms = Math.round(performance.now() - t0);
    resultLine = r.ok
      ? `[ping ${ms}ms] ${url} → ${r.status} ${body || '(empty body)'}`
      : `[ping] ${url} → ${r.status} ${r.statusText} (chat will not work)`;
  } catch (e) {
    resultLine = `[ping] ${url} → network error: ${e.message || e}`;
  }
  out.textContent = (out.textContent + '\n\n' + resultLine).trim();
  btn.textContent = orig;
  btn.disabled = false;
});

// Manual update-check button. Belt-and-suspenders fallback for users
// whose auto-update silently fails (network hiccup at boot, CSP
// regression, etc.) — clicking this re-runs the same check that
// fires on launcher boot, with a clear status message either way.
$('diag-check-update')?.addEventListener('click', async () => {
  const btn = $('diag-check-update');
  if (!btn) return;
  const orig = btn.textContent;
  btn.disabled = true;
  btn.textContent = 'Checking…';
  try {
    const r = await fetch(LAUNCHER_UPDATE_URL);
    if (!r.ok) throw new Error('GitHub returned ' + r.status);
    const data = await r.json();
    const latest = (data.tag_name || '').replace(/^v/, '').trim();
    if (!latest) throw new Error('No release found');
    if (compareSemver(latest, CURRENT_LAUNCHER_VERSION) <= 0) {
      btn.textContent = `Already on latest (v${CURRENT_LAUNCHER_VERSION})`;
    } else {
      btn.textContent = `Update found — v${latest}, installing…`;
      // Fall through to the normal auto-install path.
      checkForLauncherUpdate();
    }
  } catch (e) {
    btn.textContent = 'Check failed: ' + String(e).slice(0, 50);
  }
  setTimeout(() => { btn.disabled = false; btn.textContent = orig; }, 4500);
});

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
loadCosmetics();

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

  // Pull recent release notes for the home-screen "Latest updates" list.
  fetchLatestUpdates();
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
const HARDCODED_VERSION = '0.3.20';
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
      Replacing the launcher silently — no installer wizard, no setup
      questions. Shadow Client will reopen on its own in a few seconds.
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
