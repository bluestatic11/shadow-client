"""Mojang API: version manifest, library/asset downloads, classpath, launch args.

Everything needed to obtain and launch a vanilla Minecraft client, talking only
to Mojang's official CDN. No third-party launcher code is used.
"""
from __future__ import annotations

import hashlib
import json
import os
import platform
import re
import shutil
import sys
import urllib.request
import urllib.error
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any

VERSION_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
RESOURCE_BASE = "https://resources.download.minecraft.net"
UA = "ShadowClient/1.0 (custom python launcher)"

# Shown in Minecraft's F3 debug overlay as the client brand — this IS the
# in-game "watermark" that Mojang provides without needing a Java mod.
LAUNCHER_BRAND = "Shadow Client"
LAUNCHER_VERSION = "1.0"


def _http_get(url: str, *, binary: bool = False, retries: int = 3) -> bytes | str:
    last_err: Exception | None = None
    for attempt in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=60) as r:
                data = r.read()
                return data if binary else data.decode("utf-8")
        except (urllib.error.URLError, TimeoutError) as e:
            last_err = e
    raise RuntimeError(f"GET {url} failed: {last_err}")


def _sha1(path: Path) -> str:
    h = hashlib.sha1()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _download(url: str, dest: Path, expected_sha1: str | None = None) -> None:
    if dest.exists() and expected_sha1 and _sha1(dest) == expected_sha1:
        return
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    data = _http_get(url, binary=True)
    tmp.write_bytes(data)
    if expected_sha1 and hashlib.sha1(data).hexdigest() != expected_sha1:
        tmp.unlink(missing_ok=True)
        raise RuntimeError(f"sha1 mismatch for {url}")
    tmp.replace(dest)


def detect_os() -> tuple[str, str]:
    """Return (os_name, arch) in Mojang's naming convention."""
    sysname = platform.system().lower()
    if sysname == "windows":
        os_name = "windows"
    elif sysname == "darwin":
        os_name = "osx"
    else:
        os_name = "linux"
    m = platform.machine().lower()
    if m in ("amd64", "x86_64"):
        arch = "x64"
    elif m in ("arm64", "aarch64"):
        arch = "arm64"
    else:
        arch = "x86"
    return os_name, arch


def _rule_allows(rules: list[dict[str, Any]], os_name: str, arch: str,
                 features: dict[str, bool] | None = None) -> bool:
    """Apply Mojang's allow/disallow rule evaluation.

    Rules can gate on OS (used for libraries + JVM args) and on feature flags
    (used for things like --demo, --quickPlay*). Feature rules only match if
    we've explicitly enabled that feature — and we never do, so any rule that
    references a feature we don't have evaluates to 'not matched'.
    """
    features = features or {}
    allowed = False
    for rule in rules:
        action = rule.get("action", "allow")
        match = True
        if "os" in rule:
            osr = rule["os"]
            if osr.get("name") and osr["name"] != os_name:
                match = False
            if osr.get("arch") and osr["arch"] != arch:
                match = False
            # osr.get("version") is a Windows version regex — ignore, not gating.
        if "features" in rule:
            for feat, want in rule["features"].items():
                if bool(features.get(feat, False)) != bool(want):
                    match = False
                    break
        if match:
            allowed = action == "allow"
    return allowed


def fetch_manifest() -> dict[str, Any]:
    return json.loads(_http_get(VERSION_MANIFEST))


def resolve_version(manifest: dict[str, Any], version_id: str | None) -> dict[str, Any]:
    """Return the manifest entry for the requested version (or latest release)."""
    if version_id is None or version_id == "latest":
        version_id = manifest["latest"]["release"]
    for v in manifest["versions"]:
        if v["id"] == version_id:
            return v
    raise ValueError(f"Version {version_id} not in manifest")


def fetch_version_json(entry: dict[str, Any], cache_dir: Path) -> dict[str, Any]:
    dest = cache_dir / "versions" / entry["id"] / f"{entry['id']}.json"
    _download(entry["url"], dest, entry.get("sha1"))
    return json.loads(dest.read_text("utf-8"))


def maven_to_path(coord: str) -> str:
    """Convert 'group:artifact:version[:classifier]' → maven relative path."""
    parts = coord.split(":")
    group = parts[0].replace(".", "/")
    artifact = parts[1]
    version = parts[2]
    classifier = f"-{parts[3]}" if len(parts) >= 4 else ""
    return f"{group}/{artifact}/{version}/{artifact}-{version}{classifier}.jar"


def _library_downloads(lib: dict[str, Any], os_name: str, arch: str) -> list[tuple[str, str, str | None, bool]]:
    """Return list of (url, relpath, sha1, is_native) tuples for a library."""
    out: list[tuple[str, str, str | None, bool]] = []
    if "rules" in lib and not _rule_allows(lib["rules"], os_name, arch):
        return out
    downloads = lib.get("downloads", {})
    artifact = downloads.get("artifact")
    if artifact:
        out.append((artifact["url"], artifact["path"], artifact.get("sha1"), False))
    # Legacy natives
    natives = lib.get("natives", {})
    classifier = natives.get(os_name)
    if classifier:
        classifier = classifier.replace("${arch}", "64" if arch == "x64" else "32")
        cls = downloads.get("classifiers", {}).get(classifier)
        if cls:
            out.append((cls["url"], cls["path"], cls.get("sha1"), True))
    # Modern natives (1.19+) have name like "org.lwjgl:lwjgl:3.3.1:natives-windows"
    name: str = lib.get("name", "")
    if name.endswith("-natives-" + os_name) or ":natives-" + os_name in name:
        if artifact and not any(t[3] for t in out):
            out[-1] = (out[-1][0], out[-1][1], out[-1][2], True)
    return out


def download_libraries(version: dict[str, Any], root: Path) -> tuple[list[Path], Path]:
    """Download all libraries + natives. Returns (classpath_jars, natives_dir)."""
    os_name, arch = detect_os()
    lib_root = root / "libraries"
    natives_dir = root / "versions" / version["id"] / "natives"
    natives_dir.mkdir(parents=True, exist_ok=True)

    tasks: list[tuple[str, Path, str | None, bool]] = []
    classpath: list[Path] = []
    for lib in version["libraries"]:
        for url, relpath, sha1, is_native in _library_downloads(lib, os_name, arch):
            dest = lib_root / relpath
            tasks.append((url, dest, sha1, is_native))
            if not is_native:
                classpath.append(dest)

    print(f"[mojang] downloading {len(tasks)} libraries…")
    with ThreadPoolExecutor(max_workers=16) as pool:
        futs = {pool.submit(_download, url, dest, sha1): (url, dest, native)
                for url, dest, sha1, native in tasks}
        done = 0
        for fut in as_completed(futs):
            url, dest, native = futs[fut]
            fut.result()
            done += 1
            if done % 20 == 0 or done == len(tasks):
                print(f"[mojang]   {done}/{len(tasks)}")
            if native:
                _extract_native(dest, natives_dir)
    return classpath, natives_dir


def _extract_native(jar: Path, natives_dir: Path) -> None:
    with zipfile.ZipFile(jar) as z:
        for info in z.infolist():
            name = info.filename
            if name.endswith("/") or "META-INF" in name:
                continue
            if not name.lower().endswith((".dll", ".so", ".dylib", ".jnilib")):
                continue
            out = natives_dir / Path(name).name
            with z.open(info) as src, out.open("wb") as dst:
                shutil.copyfileobj(src, dst)


def download_client_jar(version: dict[str, Any], root: Path) -> Path:
    client = version["downloads"]["client"]
    dest = root / "versions" / version["id"] / f"{version['id']}.jar"
    _download(client["url"], dest, client.get("sha1"))
    return dest


def download_assets(version: dict[str, Any], root: Path) -> Path:
    """Download the asset index + all objects."""
    assets_root = root / "assets"
    ai = version["assetIndex"]
    index_path = assets_root / "indexes" / f"{ai['id']}.json"
    _download(ai["url"], index_path, ai.get("sha1"))
    index = json.loads(index_path.read_text("utf-8"))
    objects = index.get("objects", {})
    tasks = []
    for name, obj in objects.items():
        h = obj["hash"]
        rel = f"{h[:2]}/{h}"
        url = f"{RESOURCE_BASE}/{rel}"
        dest = assets_root / "objects" / rel
        tasks.append((url, dest, h))
    print(f"[mojang] downloading {len(tasks)} assets…")
    with ThreadPoolExecutor(max_workers=32) as pool:
        futs = [pool.submit(_download, u, d, s) for u, d, s in tasks]
        done = 0
        for fut in as_completed(futs):
            fut.result()
            done += 1
            if done % 200 == 0 or done == len(tasks):
                print(f"[mojang]   {done}/{len(tasks)}")
    return assets_root


def build_args(
    version: dict[str, Any],
    *,
    username: str,
    uuid: str,
    access_token: str,
    user_type: str,
    game_dir: Path,
    assets_dir: Path,
    natives_dir: Path,
    classpath: list[Path],
    client_jar: Path,
    jvm_extra: list[str],
) -> tuple[list[str], str]:
    """Build (jvm_args + main_class + game_args, main_class).

    Handles both legacy `minecraftArguments` string and modern `arguments` dict.
    """
    os_name, arch = detect_os()
    cp_sep = ";" if os_name == "windows" else ":"
    cp = cp_sep.join(str(p) for p in [*classpath, client_jar])

    tokens = {
        "auth_player_name": username,
        "version_name": version["id"],
        "game_directory": str(game_dir),
        "assets_root": str(assets_dir),
        "assets_index_name": version["assetIndex"]["id"],
        "auth_uuid": uuid,
        "auth_access_token": access_token,
        "clientid": LAUNCHER_BRAND,
        "auth_xuid": "0",
        "user_type": user_type,
        "version_type": version.get("type", "release"),
        "user_properties": "{}",
        "natives_directory": str(natives_dir),
        "launcher_name": LAUNCHER_BRAND,
        "launcher_version": LAUNCHER_VERSION,
        "classpath": cp,
        "game_assets": str(assets_dir / "virtual" / "legacy"),
        "auth_session": access_token,
    }

    def subst(s: str) -> str:
        return re.sub(r"\$\{([^}]+)\}", lambda m: str(tokens.get(m.group(1), m.group(0))), s)

    def flatten(seq: Any) -> list[str]:
        out: list[str] = []
        for item in seq:
            if isinstance(item, str):
                out.append(subst(item))
            elif isinstance(item, dict):
                rules = item.get("rules", [])
                if rules and not _rule_allows(rules, os_name, arch):
                    continue
                v = item.get("value")
                if isinstance(v, list):
                    out.extend(subst(x) for x in v)
                elif isinstance(v, str):
                    out.append(subst(v))
        return out

    jvm_args: list[str] = []
    game_args: list[str] = []
    main_class: str = version["mainClass"]

    if "arguments" in version:
        jvm_args = flatten(version["arguments"].get("jvm", []))
        game_args = flatten(version["arguments"].get("game", []))
    else:
        # Legacy: build minimal JVM args ourselves
        jvm_args = [
            f"-Djava.library.path={natives_dir}",
            f"-Dminecraft.launcher.brand={LAUNCHER_BRAND}",
            f"-Dminecraft.launcher.version={LAUNCHER_VERSION}",
            "-cp", cp,
        ]
        game_args = [subst(tok) for tok in version.get("minecraftArguments", "").split()]

    jvm_args = [*jvm_args, *jvm_extra]
    return [*jvm_args, main_class, *game_args], main_class
