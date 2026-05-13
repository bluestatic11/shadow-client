//! JVM flag composition + default options.txt. Rust port of `jvm.py`.

/// Tuned JVM args for max-FPS Minecraft.
pub fn flags(heap_mb: u32, gc: &str) -> Vec<String> {
    if gc == "safe" {
        return vec![format!("-Xms{heap_mb}M"), format!("-Xmx{heap_mb}M")];
    }

    let mut base: Vec<String> = vec![
        format!("-Xms{heap_mb}M"),
        format!("-Xmx{heap_mb}M"),
        "-XX:+AlwaysPreTouch".into(),
        "-XX:+DisableExplicitGC".into(),
        "-XX:+ParallelRefProcEnabled".into(),
        "-XX:+PerfDisableSharedMem".into(),
        // Module access — needed for MC's NVIDIA driver-workaround reflection
        // and several mods that touch java.lang internals on Java 17+.
        "--add-opens".into(), "java.base/java.lang=ALL-UNNAMED".into(),
        "--add-opens".into(), "java.base/java.util=ALL-UNNAMED".into(),
        "--add-opens".into(), "java.base/java.lang.reflect=ALL-UNNAMED".into(),
        "--add-opens".into(), "java.base/java.lang.invoke=ALL-UNNAMED".into(),
        "--add-opens".into(), "java.base/java.io=ALL-UNNAMED".into(),
        "--add-opens".into(), "java.base/java.nio=ALL-UNNAMED".into(),
        "--add-opens".into(), "java.base/sun.nio.ch=ALL-UNNAMED".into(),
        "--enable-native-access=ALL-UNNAMED".into(),
        // Standard mod-loader compatibility switches
        "-Dfml.ignoreInvalidMinecraftCertificates=true".into(),
        "-Dfml.ignorePatchDiscrepancies=true".into(),
        "-Dlog4j2.formatMsgNoLookups=true".into(),
        "-Dfile.encoding=UTF-8".into(),
    ];

    if gc == "zgc" {
        base.extend([
            "-XX:+UnlockExperimentalVMOptions".into(),
            "-XX:+UseZGC".into(),
            "-XX:+ZGenerational".into(),
        ]);
    } else {
        // Default to G1GC.
        base.extend([
            "-XX:+UnlockExperimentalVMOptions".into(),
            "-XX:+UseG1GC".into(),
            "-XX:MaxGCPauseMillis=50".into(),
            "-XX:G1NewSizePercent=20".into(),
            "-XX:G1MaxNewSizePercent=40".into(),
            "-XX:G1ReservePercent=20".into(),
            "-XX:G1HeapRegionSize=32M".into(),
            "-XX:G1HeapWastePercent=5".into(),
            "-XX:G1MixedGCCountTarget=4".into(),
            "-XX:InitiatingHeapOccupancyPercent=15".into(),
            "-XX:G1MixedGCLiveThresholdPercent=90".into(),
            "-XX:G1RSetUpdatingPauseTimePercent=5".into(),
            "-XX:SurvivorRatio=32".into(),
            "-XX:MaxTenuringThreshold=1".into(),
        ]);
    }
    base
}

/// Default options.txt seeded into each new profile.
pub const OPTIONS_TXT: &str = r#"version:3955
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
"#;

/// Strip JVM args that the user's Java is too old to understand. Mirrors
/// the Python version's behaviour for `--sun-misc-unsafe-memory-access`
/// (Java 23+) and `--enable-native-access` (Java 17+).
pub fn filter_args_for_java(args: Vec<String>, java_major: u32) -> Vec<String> {
    args.into_iter()
        .filter(|a| {
            let key = a.split('=').next().unwrap_or(a);
            match key {
                "--sun-misc-unsafe-memory-access" => java_major >= 23,
                "--enable-native-access" => java_major >= 17,
                _ => true,
            }
        })
        .collect()
}
