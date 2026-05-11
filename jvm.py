"""Optimized JVM flags for max-FPS Minecraft clients.

Two tuned profiles for Java 21+:
  - g1  (default): battle-tested G1GC tuning used by Prism / Fabulously Optimized
  - zgc:           Java 21+ generational ZGC — near-zero pause times, smoother
                   frametimes, but needs a bit more RAM headroom
  - safe:          minimal flags, use this to troubleshoot JVM startup errors

Heap sizing: Xms == Xmx so the JVM never wastes time resizing the heap mid-game
(the #1 cause of stutter spikes in Minecraft).
"""
from __future__ import annotations


def flags(heap_mb: int, *, gc: str = "g1") -> list[str]:
    base = [
        f"-Xms{heap_mb}M",
        f"-Xmx{heap_mb}M",
        "-XX:+AlwaysPreTouch",           # pre-touch heap pages → no allocation stutter
        "-XX:+DisableExplicitGC",         # ignore System.gc() from mods
        "-XX:+ParallelRefProcEnabled",    # parallel weak-reference processing
        "-XX:+PerfDisableSharedMem",      # skip hsperfdata file (Windows IO win)
        # Java 17+ stricter module access. MC's NVIDIA-driver workaround
        # reflects into ProcessEnvironment.theEnvironment to setenv()
        # __GL_THREADED_OPTIMIZATIONS=0; on Java 25 this throws
        # InaccessibleObjectException unless java.lang is opened to
        # unnamed modules. Symptom: silent JVM exit at the log line
        # "Modifying process environment to apply workarounds…".
        "--add-opens", "java.base/java.lang=ALL-UNNAMED",
        "--add-opens", "java.base/java.util=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens", "java.base/java.lang.invoke=ALL-UNNAMED",
        "--add-opens", "java.base/java.io=ALL-UNNAMED",
        "--add-opens", "java.base/java.nio=ALL-UNNAMED",
        "--add-opens", "java.base/sun.nio.ch=ALL-UNNAMED",
        # Allow native lookups (LWJGL, JNA, c2me-natives-math, etc.) to call
        # restricted JNI without triggering Java 25's "blocked by default"
        # path. Without this Sodium's GPU driver detection silently dies
        # mid-init on certain NVIDIA setups.
        "--enable-native-access=ALL-UNNAMED",
        # Mod-loader compatibility switches several launchers set unconditionally
        "-Dfml.ignoreInvalidMinecraftCertificates=true",
        "-Dfml.ignorePatchDiscrepancies=true",
        "-Dlog4j2.formatMsgNoLookups=true",
        "-Dfile.encoding=UTF-8",
    ]
    if gc == "safe":
        return [f"-Xms{heap_mb}M", f"-Xmx{heap_mb}M"]
    if gc == "zgc":
        # Generational ZGC is experimental in 21, non-experimental in 24+.
        base += [
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseZGC",
            "-XX:+ZGenerational",
        ]
    else:
        base += [
            "-XX:+UnlockExperimentalVMOptions",
            "-XX:+UseG1GC",
            "-XX:MaxGCPauseMillis=50",
            "-XX:G1NewSizePercent=20",
            "-XX:G1MaxNewSizePercent=40",
            "-XX:G1ReservePercent=20",
            "-XX:G1HeapRegionSize=32M",
            "-XX:G1HeapWastePercent=5",
            "-XX:G1MixedGCCountTarget=4",
            "-XX:InitiatingHeapOccupancyPercent=15",
            "-XX:G1MixedGCLiveThresholdPercent=90",
            "-XX:G1RSetUpdatingPauseTimePercent=5",
            "-XX:SurvivorRatio=32",
            "-XX:MaxTenuringThreshold=1",
        ]
    return base


# A sane, GPU-friendly options.txt seeded at install.
# Users override any of this in-game; it's only the initial file.
OPTIONS_TXT = """\
version:3955
renderDistance:10
simulationDistance:10
maxFps:260
enableVsync:false
graphicsMode:0
ao:0
biomeBlendRadius:0
entityShadows:false
entityDistanceScaling:0.75
particles:2
fancyGraphics:false
cloudHeight:0
renderClouds:"false"
fullscreen:false
smoothLighting:0
mipmapLevels:0
useNativeTransport:true
guiScale:2
chatVisibility:0
resourcePacks:[]
gamma:1.0
"""
