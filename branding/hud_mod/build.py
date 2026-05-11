"""Compile + package the Shadow HUD Fabric mod.

Pipeline:
  1. Compile the two MC stub classes (`class_332`, `class_9779`) so javac can
     resolve the signatures Fabric API references.
  2. Compile the mod against fabric-loader.jar + extracted fabric-rendering-v1
     + fabric-api-base + the stub classes.
  3. Zip the mod classes + fabric.mod.json into a .jar.
  4. Copy the jar into game_dir/mods/ so Fabric loader picks it up on next launch.

All external paths (JDK, fabric-loader, nested fabric-api modules) are resolved
via glob so the script survives version bumps without code edits.
"""
from __future__ import annotations

import platform
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
GAME = ROOT / "game_dir"

OUT_JAR = GAME / "mods" / "shadowhud-1.0.0.jar"
BUILD   = HERE / "build"
DEPS    = HERE / "build-deps"


def run(cmd: list[str]) -> None:
    print("  $", " ".join(str(c) for c in cmd))
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr, file=sys.stderr)
        raise SystemExit(f"command failed with exit {r.returncode}")


def _jdk_major_from_folder(folder: Path) -> int:
    """Parse `jdk-<N>` → <N> for ordering. Returns 0 if unparseable."""
    name = folder.name
    if not name.startswith("jdk-"):
        return 0
    tail = name[4:]
    num = ""
    for ch in tail:
        if ch.isdigit():
            num += ch
        else:
            break
    return int(num) if num else 0


def find_javac() -> Path:
    """Pick the newest bundled JDK's javac, or fall back to system javac.

    Ordering: highest `jdk-<N>/` folder wins. `jdk-25` beats `jdk-21` beats
    `jdk-17`. Inside a jdk folder we take the first javac found (there's
    only one).
    """
    name = "javac.exe" if platform.system() == "Windows" else "javac"
    folders = [f for f in ROOT.glob("jdk-*") if f.is_dir()]
    folders.sort(key=_jdk_major_from_folder, reverse=True)
    for folder in folders:
        for p in folder.rglob(name):
            if p.is_file() and "bin" in p.parts:
                return p
    # system fallback
    for sys_path in ("javac", "/usr/bin/javac"):
        try:
            subprocess.run([sys_path, "-version"], capture_output=True, check=True, timeout=10)
            return Path(sys_path)
        except Exception:
            continue
    raise SystemExit("No javac found — install a JDK or run `client.py setup` first")


def find_fabric_loader() -> Path:
    """Find any fabric-loader-*.jar under libraries/net/fabricmc/fabric-loader/."""
    root = GAME / "libraries" / "net" / "fabricmc" / "fabric-loader"
    if not root.exists():
        raise SystemExit(f"fabric-loader library dir missing: {root} — run `setup` first")
    jars = sorted(root.rglob("fabric-loader-*.jar"))
    if not jars:
        raise SystemExit(f"no fabric-loader jar under {root}")
    # newest by filename (loader versions sort lexicographically close enough)
    return jars[-1]


def find_sponge_mixin() -> Path:
    """Locate the SpongeMixin jar that Fabric Loader downloads into the
    libraries folder. Needed on the compile classpath so we can use
    @Mixin / @Inject annotations in our source. Returns None if missing —
    caller can fall back to no-mixin builds."""
    root = GAME / "libraries" / "net" / "fabricmc" / "sponge-mixin"
    if not root.exists():
        return None
    jars = sorted(root.rglob("sponge-mixin-*.jar"))
    return jars[-1] if jars else None


def find_mc_jar() -> Path:
    """The remapped 1.21.x client jar — needed on classpath so Mixins can
    reference net.minecraft.class_xxxx types via @Mixin(targets=...) without
    NoClassDefFoundError when the annotation processor reads them."""
    candidates = list((GAME / "versions").rglob("*.jar"))
    if not candidates:
        return None
    # Prefer the newest one
    return max(candidates, key=lambda p: p.stat().st_mtime)


def ensure_fabric_nested_jars() -> tuple[Path, Path]:
    """Extract fabric-rendering-v1 and fabric-api-base from whichever fabric-api
    jar is currently installed. Re-extract every build so version bumps work."""
    DEPS.mkdir(parents=True, exist_ok=True)
    mods = GAME / "mods"
    api_jars = sorted(mods.glob("fabric-api-*.jar"))
    if not api_jars:
        raise SystemExit(f"no fabric-api jar in {mods} — run `setup` first")
    api = api_jars[-1]

    rendering_out = DEPS / "fabric-rendering-v1.jar"
    base_out      = DEPS / "fabric-api-base.jar"

    with zipfile.ZipFile(api) as z:
        nested = [n for n in z.namelist() if n.startswith("META-INF/jars/")]
        rendering_src = next((n for n in nested if "rendering-v1" in n), None)
        base_src      = next((n for n in nested if "api-base"     in n), None)
        if not rendering_src or not base_src:
            raise SystemExit(f"fabric-api {api.name} missing expected nested jars")
        rendering_out.write_bytes(z.read(rendering_src))
        base_out.write_bytes(z.read(base_src))

    return rendering_out, base_out


def main() -> None:
    javac = find_javac()
    loader = find_fabric_loader()
    rendering, api_base = ensure_fabric_nested_jars()
    sponge_mixin = find_sponge_mixin()
    mc_jar = find_mc_jar()

    print(f"[deps] javac         = {javac}")
    print(f"[deps] fabric-loader = {loader.name}")
    print(f"[deps] rendering-v1  = {rendering.name}  (re-extracted)")
    print(f"[deps] api-base      = {api_base.name}  (re-extracted)")
    print(f"[deps] sponge-mixin  = {sponge_mixin.name if sponge_mixin else 'MISSING (mixin features disabled)'}")
    print(f"[deps] minecraft     = {mc_jar.name if mc_jar else 'MISSING'}")

    if BUILD.exists():
        shutil.rmtree(BUILD)
    (BUILD / "stubs").mkdir(parents=True)
    (BUILD / "classes").mkdir(parents=True)

    print("[1/3] compile stubs")
    stubs = sorted(str(p) for p in (HERE / "stubs").rglob("*.java"))
    run([str(javac), "-d", str(BUILD / "stubs"), *stubs])

    print("[2/3] compile mod")
    cp_entries = [rendering, api_base, loader, BUILD / "stubs"]
    if sponge_mixin: cp_entries.append(sponge_mixin)
    if mc_jar:       cp_entries.append(mc_jar)
    cp = ";".join(str(p) for p in cp_entries)
    srcs = sorted(str(p) for p in (HERE / "src").rglob("*.java"))
    run([str(javac),
         "-cp", cp,
         "-d", str(BUILD / "classes"),
         "--release", "17",
         *srcs])

    print("[3/3] package jar")
    # Build to a sibling .jar.new first, then atomically replace — so if MC is
    # still running and holds a handle on the old jar, we stage the new one and
    # cmd_launch's _promote_staged_mods swaps it in before the next start.
    staged = OUT_JAR.with_suffix(".jar.new")
    if staged.exists():
        staged.unlink()
    with zipfile.ZipFile(staged, "w", zipfile.ZIP_DEFLATED) as z:
        z.write(HERE / "fabric.mod.json", "fabric.mod.json")
        # Include any *.json from the resources/ folder so the Mixin config
        # ships in the jar — Fabric Loader looks them up by name from the
        # mixins[] entry in fabric.mod.json.
        res = HERE / "resources"
        if res.exists():
            for r in res.rglob("*"):
                if r.is_file():
                    z.write(r, r.relative_to(res).as_posix())
        for cls in (BUILD / "classes").rglob("*.class"):
            arcname = cls.relative_to(BUILD / "classes").as_posix()
            z.write(cls, arcname)

    try:
        if OUT_JAR.exists():
            OUT_JAR.unlink()
        staged.replace(OUT_JAR)
        size_kb = OUT_JAR.stat().st_size // 1024
        print(f"\n[ok] built {OUT_JAR.name}  ({size_kb} KB)")
    except PermissionError:
        size_kb = staged.stat().st_size // 1024
        print(f"\n[!] {OUT_JAR.name} is locked (Minecraft running?)")
        print(f"[!] staged at {staged.name} ({size_kb} KB) — close MC and re-run to swap in.")


if __name__ == "__main__":
    main()
