"""Fabric mod loader integration.

Uses Fabric's official meta API. The Fabric 'profile' JSON lists the additional
libraries (fabric-loader, intermediary mappings, ASM, etc.) and the alternative
main class (KnotClient). We merge these on top of the vanilla version JSON so
all the vanilla launch logic keeps working.
"""
from __future__ import annotations

import json
import urllib.request
from pathlib import Path
from typing import Any

from mojang import _http_get, _download, maven_to_path, UA

FABRIC_META = "https://meta.fabricmc.net/v2"
FABRIC_MAVEN = "https://maven.fabricmc.net"


def latest_loader(mc_version: str) -> str:
    data = json.loads(_http_get(f"{FABRIC_META}/versions/loader/{mc_version}"))
    if not data:
        raise RuntimeError(f"No Fabric loader for MC {mc_version}")
    return data[0]["loader"]["version"]


def fetch_profile(mc_version: str, loader_version: str) -> dict[str, Any]:
    url = f"{FABRIC_META}/versions/loader/{mc_version}/{loader_version}/profile/json"
    return json.loads(_http_get(url))


def merge_into_vanilla(vanilla: dict[str, Any], fabric: dict[str, Any]) -> dict[str, Any]:
    """Return a new version dict with Fabric loader merged on top of vanilla."""
    merged: dict[str, Any] = json.loads(json.dumps(vanilla))  # deep copy via json
    merged["id"] = fabric["id"]
    merged["mainClass"] = fabric["mainClass"]

    # Merge libraries: Fabric libs first so they win over matching vanilla ones
    fabric_libs = _normalize_libraries(fabric.get("libraries", []))
    existing_names = {lib["name"] for lib in fabric_libs}
    for lib in merged.get("libraries", []):
        if lib["name"] not in existing_names:
            fabric_libs.append(lib)
    merged["libraries"] = fabric_libs

    # Merge arguments
    if "arguments" in fabric:
        va = merged.setdefault("arguments", {"jvm": [], "game": []})
        for key in ("jvm", "game"):
            va[key] = [*va.get(key, []), *fabric["arguments"].get(key, [])]

    return merged


def _normalize_libraries(libs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Fabric's profile lists libraries with just `name` + `url`; build a full
    Mojang-style `downloads.artifact` block so the vanilla downloader handles it."""
    out: list[dict[str, Any]] = []
    for lib in libs:
        if "downloads" in lib and "artifact" in lib.get("downloads", {}):
            out.append(lib)
            continue
        name: str = lib["name"]
        base = lib.get("url") or FABRIC_MAVEN
        if not base.endswith("/"):
            base += "/"
        path = maven_to_path(name)
        url = base + path
        sha1 = _fetch_sha1(url)
        entry = {
            "name": name,
            "downloads": {
                "artifact": {
                    "path": path,
                    "url": url,
                    "sha1": sha1,
                }
            },
        }
        out.append(entry)
    return out


def _fetch_sha1(url: str) -> str | None:
    """Fabric maven publishes `.sha1` sidecar files; best-effort fetch."""
    try:
        req = urllib.request.Request(url + ".sha1", headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.read().decode("utf-8").strip().split()[0]
    except Exception:
        return None
