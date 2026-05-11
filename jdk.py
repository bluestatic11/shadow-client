"""Download a bundled JDK (Adoptium Temurin) when the system Java is too old.

Reads the required major version from the Mojang version JSON
(`javaVersion.majorVersion`) and grabs the matching Temurin build from
Adoptium's public API. Extracted into <project>/jdk-<N>/.
"""
from __future__ import annotations

import platform
import shutil
import sys
import tarfile
import urllib.request
import zipfile
from pathlib import Path

from mojang import UA, detect_os


def _adoptium_url(major: int) -> str:
    os_name, arch = detect_os()
    os_map = {"windows": "windows", "osx": "mac", "linux": "linux"}
    arch_map = {"x64": "x64", "arm64": "aarch64", "x86": "x86"}
    return (
        "https://api.adoptium.net/v3/binary/latest/"
        f"{major}/ga/{os_map[os_name]}/{arch_map[arch]}/jdk/hotspot/normal/eclipse"
    )


def download_jdk(major: int, into: Path) -> Path:
    """Download + extract JDK <major>. Returns path to the java[w] executable."""
    into.mkdir(parents=True, exist_ok=True)
    marker = into / f".jdk{major}-ok"
    # Use java.exe (console) not javaw.exe (windowed) — javaw silently swallows
    # stderr, so crashes look like "nothing happened".
    bin_name = "java.exe" if platform.system() == "Windows" else "java"

    if marker.exists():
        # Already downloaded earlier — find the binary.
        for p in into.rglob(bin_name):
            if p.is_file() and "bin" in p.parts:
                return p

    url = _adoptium_url(major)
    archive_suffix = ".zip" if platform.system() == "Windows" else ".tar.gz"
    archive = into / f"jdk-{major}{archive_suffix}"
    part    = archive.with_suffix(archive.suffix + ".part")

    # Clean up any leftover .part from a previous interrupted download.
    if part.exists():
        part.unlink(missing_ok=True)

    print(f"[jdk] downloading JDK {major} from Adoptium…")
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    try:
        with urllib.request.urlopen(req, timeout=120) as r, part.open("wb") as f:
            total = int(r.headers.get("content-length", 0))
            read = 0
            while chunk := r.read(1 << 20):
                f.write(chunk)
                read += len(chunk)
                if total:
                    pct = 100 * read / total
                    sys.stdout.write(f"\r[jdk]   {read >> 20} / {total >> 20} MB  ({pct:.1f}%)")
                    sys.stdout.flush()
        print()
        # Sanity-check size before accepting
        if total and read < total:
            raise RuntimeError(f"short download: got {read}/{total} bytes")
        part.replace(archive)
    except BaseException:
        # KeyboardInterrupt, OSError, URLError — nuke the partial so the next
        # run starts fresh instead of trying to extract corrupt bytes.
        part.unlink(missing_ok=True)
        raise

    print(f"[jdk] extracting…")
    try:
        if archive_suffix == ".zip":
            with zipfile.ZipFile(archive) as z:
                z.extractall(into)
        else:
            with tarfile.open(archive) as t:
                t.extractall(into)
    finally:
        archive.unlink(missing_ok=True)

    for p in into.rglob(bin_name):
        if p.is_file() and "bin" in p.parts:
            marker.touch()
            return p
    raise RuntimeError(f"Extracted JDK {major} but couldn't find {bin_name}")
