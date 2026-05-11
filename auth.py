"""Authentication: offline mode + Microsoft device-code flow.

Offline mode deterministically derives the UUID Mojang's server uses for
offline players (UUID.nameUUIDFromBytes("OfflinePlayer:<name>")).

Microsoft flow: Device Code → Xbox Live → XSTS → Minecraft Services → profile.
Tokens are cached under <game_dir>/mc-client-account.json. Auto-refresh is
implemented: on `launch`, if the Minecraft access_token is near expiry, we
silently exchange the Microsoft refresh_token for a new one. The refresh
rotates and is re-saved each time, so the cached sign-in stays valid
indefinitely as long as you launch at least once per 90 days.
"""
from __future__ import annotations

import base64
import hashlib
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Any

from mojang import UA

# Public Azure AD client_id registered as a multi-tenant public client for
# the Minecraft Services scope. The legacy MSA id "00000000402b5328" is no
# longer accepted on the /consumers/oauth2/v2.0/* endpoints (Microsoft
# returns AADSTS700016 "application was not found in the directory"), so
# we use PrismLauncher's registered client_id, which is publicly known and
# works with device-code on /consumers.
MS_CLIENT_ID = "c36a9fb6-4f2a-41ff-90bd-ae7cc92031eb"

DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
TOKEN_URL       = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
XBL_URL         = "https://user.auth.xboxlive.com/user/authenticate"
XSTS_URL        = "https://xsts.auth.xboxlive.com/xsts/authorize"
MC_LOGIN_URL    = "https://api.minecraftservices.com/authentication/login_with_xbox"
MC_PROFILE_URL  = "https://api.minecraftservices.com/minecraft/profile"


class PrismRefreshExpired(RuntimeError):
    """Raised when Prism's stored refresh_token is rejected by Microsoft.

    The caller should fall back to interactive device-code login — there's
    nothing we can do about an invalidated refresh token except ask the
    user to sign in again, and we can do that ourselves instead of asking
    them to re-open Prism.
    """


@dataclass
class Account:
    username: str
    uuid: str
    access_token: str               # Minecraft services JWT (24-hour life)
    user_type: str                  # "msa" for Microsoft, "legacy" for offline
    # --- optional: filled in on MSA sign-ins so the launcher can auto-refresh
    # the MC token without asking the user to sign in again. These survive
    # across launches because save()/load() persist them.
    refresh_token: str       = ""   # Microsoft refresh_token (~90-day life, rotates)
    msa_client_id: str       = ""   # client_id the refresh_token was issued for
    refresh_updated_at: float = 0.0 # epoch-seconds of last successful refresh

    def save(self, path: Path) -> None:
        # Atomic write — crash mid-save can't corrupt the account file.
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(path.suffix + ".tmp")
        tmp.write_text(json.dumps(asdict(self), indent=2))
        tmp.replace(path)

    @classmethod
    def load(cls, path: Path) -> "Account | None":
        """Load an account, ignoring any extra keys from older/newer schemas."""
        if not path.exists():
            return None
        data = json.loads(path.read_text())
        import dataclasses
        known = {f.name for f in dataclasses.fields(cls)}
        return cls(**{k: v for k, v in data.items() if k in known})

    def refresh_if_needed(self, save_path: Path,
                          prism_path: "Path | None" = None,
                          force: bool = False) -> bool:
        """Silently refresh the MC access_token if it's near expiry.

        Returns True if a refresh happened. Also rewrites Prism's
        accounts.json with the fresh refresh_token so Prism stays alive as a
        backup sign-in path — without this, Prism's token lapses after
        90 days and you hit AADSTS70000.
        """
        if self.user_type != "msa" or not self.refresh_token or not self.msa_client_id:
            return False
        if not force and not _jwt_expired(self.access_token):
            return False
        try:
            ms_token, new_refresh = _refresh_ms_tokens(self.msa_client_id, self.refresh_token)
        except Exception as e:
            print(f"[auth] refresh failed ({e}); leaving cached token in place")
            return False
        fresh = _ms_to_minecraft_account(ms_token)
        self.username            = fresh.username
        self.uuid                = fresh.uuid
        self.access_token        = fresh.access_token
        # Microsoft rotates refresh_tokens on each refresh — keep the new one.
        self.refresh_token       = new_refresh or self.refresh_token
        self.refresh_updated_at  = time.time()
        self.save(save_path)
        if prism_path is not None:
            _write_prism_refresh(prism_path, self.uuid, self.msa_client_id,
                                 self.access_token, self.refresh_token)
        return True


def _jwt_expired(jwt: str, *, skew_seconds: int = 120) -> bool:
    """Decode a JWT's payload and check its `exp` claim. `skew_seconds` treats
    near-expiry tokens as expired so we refresh proactively."""
    try:
        payload = jwt.split('.')[1]
        payload += '=' * (-len(payload) % 4)
        data = json.loads(base64.urlsafe_b64decode(payload))
        return time.time() + skew_seconds >= float(data.get('exp', 0))
    except Exception:
        return True   # unparseable ⇒ treat as expired, force refresh


def _refresh_ms_access_token(client_id: str, refresh_token: str) -> str:
    """Back-compat alias returning only the access_token. Prefer
    `_refresh_ms_tokens` which returns the rotated refresh_token too."""
    access, _ = _refresh_ms_tokens(client_id, refresh_token)
    return access


def _refresh_ms_tokens(client_id: str, refresh_token: str) -> tuple[str, str]:
    """Exchange a Microsoft refresh_token for a fresh `(access_token, refresh_token)`.

    Microsoft rotates refresh_tokens on every use — the old one becomes
    invalid the moment the new one is issued. Capturing the rotated token
    is the whole point of "auto-refreshing" Prism: if we keep using Prism's
    original refresh_token forever, it'll eventually age out (90 days). If
    we re-save the rotated one each refresh, it stays fresh indefinitely.
    """
    resp = _post_json(
        TOKEN_URL,
        {
            "client_id":     client_id,
            "refresh_token": refresh_token,
            "grant_type":    "refresh_token",
            "scope":         "XboxLive.signin offline_access",
        },
        form=True,
    )
    return resp["access_token"], resp.get("refresh_token", refresh_token)


def _write_prism_refresh(prism_path: Path, player_uuid: str, client_id: str,
                         access_token: str, refresh_token: str) -> None:
    """Rewrite Prism's accounts.json with fresh MSA tokens for the given UUID.

    We only touch the one matching account, leave every other field alone,
    and do an atomic temp-file swap so Prism can't read a half-written file
    if it happens to be open. Failures are non-fatal — worst case Prism's
    cached state stays stale and you hit the usual re-sign-in prompt next
    time you launch Prism itself.
    """
    try:
        if not prism_path.exists():
            return
        data = json.loads(prism_path.read_text("utf-8"))
        accounts = data.get("accounts", [])
        changed = False
        pid_compact = (player_uuid or "").replace("-", "")
        for a in accounts:
            prof = a.get("profile", {}) or {}
            if prof.get("id", "").replace("-", "") != pid_compact:
                continue
            msa = a.setdefault("msa", {})
            msa["access_token"]  = access_token
            msa["refresh_token"] = refresh_token
            msa["token_type"]    = "Bearer"
            a["msa-client-id"]   = client_id
            changed = True
            break
        if not changed:
            return
        tmp = prism_path.with_suffix(prism_path.suffix + ".tmp")
        tmp.write_text(json.dumps(data, indent=4), encoding="utf-8")
        tmp.replace(prism_path)
        print("[auth] refreshed Prism's cached token in-place")
    except Exception as e:
        print(f"[auth] couldn't update Prism's accounts.json: {e}")


def _ms_to_minecraft_account(ms_token: str) -> Account:
    """Take a Microsoft access_token and walk it through Xbox Live → XSTS →
    Minecraft Services → profile. Returns a ready-to-use Account.

    Shared between the interactive device-code flow and the silent refresh."""
    xbl = _post_json(XBL_URL, {
        "Properties": {
            "AuthMethod": "RPS",
            "SiteName": "user.auth.xboxlive.com",
            "RpsTicket": f"d={ms_token}",
        },
        "RelyingParty": "http://auth.xboxlive.com",
        "TokenType": "JWT",
    })
    xbl_token = xbl["Token"]
    uhs = xbl["DisplayClaims"]["xui"][0]["uhs"]

    xsts = _post_json(XSTS_URL, {
        "Properties": {"SandboxId": "RETAIL", "UserTokens": [xbl_token]},
        "RelyingParty": "rp://api.minecraftservices.com/",
        "TokenType": "JWT",
    })
    xsts_token = xsts["Token"]

    mc = _post_json(MC_LOGIN_URL, {"identityToken": f"XBL3.0 x={uhs};{xsts_token}"})
    mc_token = mc["access_token"]

    req = urllib.request.Request(MC_PROFILE_URL, headers={
        "Authorization": f"Bearer {mc_token}", "User-Agent": UA,
    })
    with urllib.request.urlopen(req, timeout=30) as r:
        profile = json.loads(r.read().decode("utf-8"))
    if "id" not in profile:
        raise RuntimeError(f"No Minecraft profile on this account: {profile}")
    pid = profile["id"]
    dashed = f"{pid[0:8]}-{pid[8:12]}-{pid[12:16]}-{pid[16:20]}-{pid[20:]}"
    return Account(username=profile["name"], uuid=dashed, access_token=mc_token, user_type="msa")


def from_prism_launcher() -> Account:
    """Import the currently-active account from PrismLauncher's accounts.json.

    If Prism's stored Minecraft token is already expired (common if Prism
    hasn't been opened in >24h), we silently refresh it via the Microsoft
    refresh_token that Prism stored. The refresh_token lives ~90 days so
    this works transparently as long as you signed in via Prism within
    the last three months.
    """
    prism = Path.home() / "AppData/Roaming/PrismLauncher/accounts.json"
    if not prism.exists():
        raise RuntimeError(f"PrismLauncher accounts file not found at {prism}")
    data = json.loads(prism.read_text("utf-8"))
    accounts = data.get("accounts", [])
    if not accounts:
        raise RuntimeError("No accounts in PrismLauncher — sign in there first")
    acct = next((a for a in accounts if a.get("active")), accounts[0])
    profile = acct.get("profile", {})
    ygg     = acct.get("ygg", {})
    if not profile.get("id") or not ygg.get("token"):
        raise RuntimeError("PrismLauncher account has no MC profile/token — re-login there")

    token       = ygg["token"]
    msa         = acct.get("msa", {}) or {}
    refresh_tok = msa.get("refresh_token") or ""
    client_id   = acct.get("msa-client-id") or ""

    if _jwt_expired(token):
        # Prism's token is stale; refresh via the stored Microsoft refresh_token.
        if not refresh_tok or not client_id:
            raise PrismRefreshExpired(
                "Prism's token is expired and has no refresh info."
            )
        print("[login] Prism token expired — refreshing via Microsoft…")
        try:
            ms_token, new_refresh = _refresh_ms_tokens(client_id, refresh_tok)
        except RuntimeError as e:
            msg = str(e)
            if "AADSTS70000" in msg or "invalid_grant" in msg:
                # Let the caller decide to fall back to device-code login.
                raise PrismRefreshExpired(
                    "Microsoft rejected Prism's stored refresh token."
                ) from e
            raise
        # Write the rotated refresh_token back to Prism straight away — if
        # we skip this and keep using the old token, Microsoft will reject
        # it next time because it's been superseded.
        mc_account = _ms_to_minecraft_account(ms_token)
        _write_prism_refresh(prism, mc_account.uuid, client_id,
                             mc_account.access_token, new_refresh)
        mc_account.refresh_token      = new_refresh
        mc_account.msa_client_id      = client_id
        mc_account.refresh_updated_at = time.time()
        return mc_account

    # Still valid — use it as-is, but carry refresh info forward so future
    # launches can auto-refresh without touching Prism again.
    pid = profile["id"]
    dashed = f"{pid[0:8]}-{pid[8:12]}-{pid[12:16]}-{pid[16:20]}-{pid[20:]}"
    return Account(
        username=profile["name"], uuid=dashed,
        access_token=token, user_type="msa",
        refresh_token=refresh_tok, msa_client_id=client_id,
        refresh_updated_at=time.time() if refresh_tok else 0.0,
    )


def offline(username: str) -> Account:
    """Build a deterministic offline account. Good for singleplayer + LAN."""
    h = hashlib.md5(f"OfflinePlayer:{username}".encode("utf-8")).digest()
    # UUID v3 encoding
    b = bytearray(h)
    b[6] = (b[6] & 0x0F) | 0x30
    b[8] = (b[8] & 0x3F) | 0x80
    u = str(uuid.UUID(bytes=bytes(b)))
    return Account(username=username, uuid=u, access_token="0", user_type="legacy")


def _post_json(url: str, body: Any, *, form: bool = False, headers: dict[str, str] | None = None) -> dict[str, Any]:
    if form:
        data = urllib.parse.urlencode(body).encode()
        content_type = "application/x-www-form-urlencoded"
    else:
        data = json.dumps(body).encode()
        content_type = "application/json"
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": content_type, "Accept": "application/json", "User-Agent": UA, **(headers or {})},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"POST {url} → HTTP {e.code}: {e.read().decode('utf-8', 'replace')}")


def _copy_to_clipboard(text: str) -> bool:
    """Best-effort copy to clipboard. Returns True if it likely succeeded."""
    import subprocess
    try:
        # Windows: pipe through clip.exe (ships with Windows)
        p = subprocess.Popen(["clip"], stdin=subprocess.PIPE, shell=False)
        p.communicate(input=text.encode("utf-16le"))
        return p.returncode == 0
    except Exception:
        pass
    try:
        # macOS
        p = subprocess.Popen(["pbcopy"], stdin=subprocess.PIPE)
        p.communicate(input=text.encode("utf-8"))
        return p.returncode == 0
    except Exception:
        pass
    try:
        # Linux (xclip)
        p = subprocess.Popen(["xclip", "-selection", "clipboard"], stdin=subprocess.PIPE)
        p.communicate(input=text.encode("utf-8"))
        return p.returncode == 0
    except Exception:
        pass
    return False


def microsoft_login() -> Account:
    """Interactive device-code login.

    We try three levels of help so the user doesn't have to do anything
    manual beyond signing in:
      1. auto-open their default browser at the pre-filled verification URL
         (Microsoft's response includes `verification_uri_complete` which
         embeds the code — no typing needed)
      2. copy the user code to their clipboard as a backup
      3. print the URL + code to stdout as the final fallback
    """
    import webbrowser
    dc = _post_json(
        DEVICE_CODE_URL,
        {"client_id": MS_CLIENT_ID, "scope": "XboxLive.signin offline_access"},
        form=True,
    )
    url_plain    = dc["verification_uri"]
    user_code    = dc["user_code"]
    # Microsoft returns `verification_uri_complete` with the code embedded —
    # just opening it signs the user in without having to type.
    url_complete = dc.get("verification_uri_complete") or f"{url_plain}?otc={user_code}"

    opened = False
    try:
        opened = webbrowser.open(url_complete, new=2)
    except Exception:
        opened = False
    clipped = _copy_to_clipboard(user_code)

    print("\n" + "=" * 60)
    if opened:
        print("  Your browser should have opened automatically.")
        print(f"  If it didn't, open this URL manually:")
        print(f"    {url_plain}")
        print(f"  and enter this code:")
        print(f"    {user_code}" + ("   (already copied to clipboard — Ctrl+V)" if clipped else ""))
    else:
        print(f"  Open this URL in any browser:")
        print(f"    {url_plain}")
        print(f"  Enter this code:")
        print(f"    {user_code}" + ("   (copied to clipboard — Ctrl+V)" if clipped else ""))
    print("=" * 60 + "\n")
    print("Waiting for you to sign in… (this page will close itself when done)")

    interval = dc.get("interval", 5)
    deadline = time.time() + dc.get("expires_in", 900)
    ms_token: str | None = None
    ms_refresh: str = ""
    while time.time() < deadline:
        time.sleep(interval)
        try:
            tok = _post_json(
                TOKEN_URL,
                {
                    "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
                    "client_id": MS_CLIENT_ID,
                    "device_code": dc["device_code"],
                },
                form=True,
            )
            ms_token   = tok["access_token"]
            # Capturing the refresh_token here is what enables silent
            # auto-refresh across future launches — without this we'd be
            # back to asking the user to sign in every 24h.
            ms_refresh = tok.get("refresh_token", "")
            break
        except RuntimeError as e:
            msg = str(e)
            if "authorization_pending" in msg or "slow_down" in msg:
                if "slow_down" in msg:
                    interval += 5
                continue
            raise
    if not ms_token:
        raise RuntimeError("Device-code login timed out")
    acct = _ms_to_minecraft_account(ms_token)
    acct.refresh_token      = ms_refresh
    acct.msa_client_id      = MS_CLIENT_ID
    acct.refresh_updated_at = time.time() if ms_refresh else 0.0
    return acct
