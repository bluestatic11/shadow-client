package shadowhud;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Shadow HUD — top-left overlay + Lunar-style module menu.
 *
 * <p>The in-game menu is opened with Right-Shift. Each line (FPS, XYZ, Facing,
 * Biome, HP) is an independently-toggleable "module". Number keys 1–5 flip
 * each module while the menu is open. State persists to
 * {@code game_dir/config/shadowclient.json}.</p>
 *
 * <p>Zero compile-time Minecraft imports — all MC + GLFW interaction is via
 * reflection, so the same jar runs against any intermediary mapping Fabric
 * applies, and we don't need to ship LWJGL on the mod's build classpath.</p>
 */
public final class ShadowHud implements ClientModInitializer {

    // ---- persistence ------------------------------------------------------
    private static final Path CONFIG = Paths.get("config", "shadowclient.json");
    /** Stable module order drives both the HUD lines and the menu list. */
    /** Module on/off state. Package-private so the {@code shadowhud.mixin}
     *  package can read it directly without round-tripping through a getter. */
    public static final Map<String, Boolean> MODULES = new LinkedHashMap<>();
    /** Per-module subtitles shown under the name in the Lunar-style cards. */
    private static final Map<String, String>  MODULE_DESC = new LinkedHashMap<>();
    /** Category for each module — drives the section dividers in the menu so
     *  77 modules don't read as a flat wall. The category strings are also
     *  used by presets to flip groups on/off in one Enter. */
    private static final Map<String, String>  MODULE_CAT  = new LinkedHashMap<>();
    /** Frozen factory defaults — captured at addModule() time so the
     *  "Defaults" preset can restore the original state regardless of what
     *  the user toggled. MUST be declared BEFORE the static block below
     *  because Java initializes static fields in source order; addModule()
     *  writes to this map, so a later declaration would crash with NPE. */
    private static final Map<String, Boolean> DEFAULT_STATE = new LinkedHashMap<>();
    /** Set of pinned module names. Pinned modules sort to the top of the
     *  visible list regardless of sort mode, and show a filled ★ in the
     *  card header. Persisted to config as a comma-separated list. */
    private static final java.util.LinkedHashSet<String> pinnedModules = new java.util.LinkedHashSet<>();
    static {
        // ----- Display (basic info, low default) ------------------------
        addModule("FPS",        true,  "Display", "Frames per second");
        addModule("FpsChart",   false, "Display", "Min / avg / max FPS over 30s");
        addModule("Time",       false, "Display", "Wall clock");
        addModule("SessionTime",false, "Display", "Total play-time this launch");
        // ----- World / position -----------------------------------------
        addModule("XYZ",        true,  "World",   "Player coordinates");
        addModule("Facing",     true,  "World",   "Cardinal direction");
        addModule("Compass",    false, "World",   "Direction with degrees");
        addModule("Day",        false, "World",   "In-game day counter");
        addModule("DayArc",     false, "World",   "Visual sun / moon position");
        addModule("Light",      false, "World",   "Sky / block light level");
        addModule("Weather",    false, "World",   "Clear / rain / thunder");
        addModule("Biome",      true,  "World",   "Current biome");
        addModule("Difficulty", false, "World",   "World difficulty");
        addModule("GameMode",   false, "World",   "Survival / Creative / etc");
        addModule("Move",       false, "World",   "Sprint/Sneak/Glide state");
        addModule("Velocity",   false, "World",   "Current XYZ velocity");
        addModule("EntityCount",false, "World",   "Total loaded entities");
        addModule("TPS",        false, "World",   "Server tick rate (lag)");
        addModule("Level",      false, "World",   "XP level + progress");
        addModule("TabCount",   false, "World",   "Online players (tab list)");
        addModule("WorldBorder",false, "World",   "Distance to world border");
        addModule("SpawnDist",  false, "World",   "Distance from world spawn");
        addModule("Distance",   false, "World",   "Distance walked this session");
        addModule("JumpHeight", false, "World",   "Peak Y this airtime");
        addModule("NearMob",    false, "World",   "Nearest hostile mob");
        addModule("SleepInfo",  false, "World",   "Players sleeping (when in bed)");
        addModule("DeathPos",   true,  "World",   "Last-death coordinates");
        // ----- Inventory / Player ---------------------------------------
        addModule("HP",         true,  "Inventory","Health & armor");
        addModule("Held",       true,  "Inventory","Currently-held item info");
        addModule("Offhand",    false, "Inventory","Offhand item info");
        addModule("ArmorPct",   false, "Inventory","Lowest armor piece % left");
        addModule("InvFree",    false, "Inventory","Free inventory slots");
        addModule("Slot",       false, "Inventory","Selected hotbar slot");
        addModule("BlockCount", false, "Inventory","Blocks placed this session");
        addModule("DropCount",  false, "Inventory","Items dropped (Q) session");
        addModule("LookBlock",  false, "Inventory","Coords of block in crosshair");
        addModule("Look",       false, "Inventory","Block / entity in crosshair");
        addModule("Effects",    true,  "Inventory","Active status effects");
        addModule("HandState",  false, "Inventory","Eating / blocking / drawing");
        // ----- Combat / PvP ---------------------------------------------
        addModule("CPS",        false, "Combat",  "Clicks per second");
        addModule("Combo",      false, "Combat",  "Hit combo counter");
        addModule("Reach",      false, "Combat",  "Distance to target");
        addModule("Cooldown",   false, "Combat",  "Attack cooldown readiness");
        addModule("Damage",     false, "Combat",  "Last damage taken + cooldown");
        addModule("HitDir",     false, "Combat",  "Direction of last damage");
        addModule("Attacker",   false, "Combat",  "Name of last entity to hit you");
        addModule("BowCharge",  false, "Combat",  "Bow charge level + crit");
        // CritReady removed — combat-prediction PvP hack.
        addModule("Killstreak", false, "Combat",  "Uninterrupted hit streak");
        addModule("BestCps",    false, "Combat",  "Highest CPS this session");
        addModule("BestCombo",  false, "Combat",  "Best combo this session");
        addModule("HitsDealt",  false, "Combat",  "Melee hits landed (session)");
        addModule("HitsTaken",  false, "Combat",  "Damage events taken (session)");
        addModule("DPS",        false, "Combat",  "Damage per second (5 s window)");
        addModule("DeathTime",  false, "Combat",  "Time since last death");
        addModule("Nearby",     false, "Combat",  "Players (128b) / mobs (32b) / items (16b) + nearest");
        addModule("TntTimer",   false, "Combat",  "Nearest primed-TNT fuse");
        // ----- Server / Connection --------------------------------------
        addModule("Server",     true,  "Server",  "Current server IP");
        addModule("Ping",       true,  "Server",  "Network latency");
        addModule("Speed",      false, "Server",  "Blocks / second");
        addModule("Memory",     false, "Server",  "JVM heap usage");
        addModule("AFK",        false, "Server",  "Idle-time / activity tracker");
        addModule("Streamer",   false, "Server",  "Hide server IP / name");
        addModule("FakePing",   false, "Server",  "Display fake ping (2000 ms)");
        addModule("Music",      false, "Server",  "Currently-playing music");
        addModule("ChatFilter", false, "Server",  "Hide chat lines matching patterns");
        // ----- Utility (interactive features) ---------------------------
        addModule("Map",        true,  "Utility", "Mini-map with terrain");
        addModule("Keystrokes", true,  "Utility", "WASD + mouse overlay");
        addModule("Zoom",       true,  "Utility", "Hold C to zoom in");
        addModule("ToggleSprint", false,"Utility","Force sprint while moving");
        addModule("ToggleSneak",  false,"Utility","Force persistent sneak");
        addModule("AutoRespawn",false, "Utility", "Instant respawn on death");
        addModule("Fullbright", false, "Utility", "Boost gamma for max brightness");
        addModule("CoordCopy",  false, "Utility", "Press Insert to copy XYZ");
        addModule("Waypoint",   false, "Utility", "Press Home to set / clear");
        addModule("Tracker",    false, "Utility", "Direction to nearest player");
        addModule("Crosshair",  false, "Utility", "Custom crosshair (Enter cycles)");
        addModule("TabHp",      true,  "Utility", "Show your HP on tab list");
        addModule("TabPings",   true,  "Utility", "Numeric ping by every player on tab");
        addModule("SmoothSwing",false, "Utility", "Slower 1.8-style arm swing");
        addModule("Trail",      false, "Utility", "Particle cosmetic (Enter cycles)");
        addModule("Halo",       false, "Utility", "Spinning particle halo above head");
        addModule("Wings",      false, "Utility", "Dragon-style flapping wings");
        addModule("AngelWings", false, "Utility", "Curved feathered angel wings");
        addModule("Cape",       false, "Utility", "Textured cape behind shoulders (no particles)");
        addModule("Fairies",    false, "Utility", "Orbiting companion particles");
        addModule("Footsteps",  false, "Utility", "Lingering trail on the ground");
        addModule("BowTrail",   false, "Utility", "Particle trail on your arrows");
        // ----- New modules (Badlion / Lunar feature gaps) -------------------
        addModule("Saturation", false, "Inventory","Food saturation value");
        addModule("ArmorList",  false, "Inventory","Each armor piece + durability");
        addModule("NetGraph",   false, "Server",  "Ping chart: min / avg / max");
        addModule("TargetHud",  false, "Combat",  "Crosshair-target HP + name + dist");
        addModule("ChunkInfo",  false, "World",   "Chunk coords + region file");
        addModule("AutoSprint", false, "Utility", "Force sprint when moving forward");
        addModule("AutoGG",     false, "Utility", "Send \"gg\" on death (chat hook)");
        addModule("HotbarFade", false, "Utility", "Hide hotbar when idle (Lunar style)");
        addModule("TimerHud",   false, "Utility", "Stopwatch — Insert+T resets");
        addModule("Shield",     false, "Combat",  "Shield durability + BLOCK state + cooldown");
        // EnemyShield removed — see-through-walls combat-info hack.
        // SpearSwap removed — auto-action that gives unfair PvP advantage.
        // ----- New batch: text HUD modules ---------------------------------
        addModule("HudLayout",  true,  "Display", "Choose where the HUD stack sits on screen (3×3 grid)");
        addModule("SwordVisuals",false,"Utility", "Make held swords look longer or shorter (visual only)");
        addModule("GlintTune",   false,"Utility", "Customize enchant glint color + strength");
        addModule("BlockHighlight",false,"Utility","Tint the block face under your crosshair");
        addModule("WingsSolid",   false,"Utility","Solid filled wings (instead of wireframe/particles) — render with Wings off");
        addModule("AngelWingsSolid",false,"Utility","Solid filled angel wings (instead of wireframe/particles)");
        addModule("WaterMark",  false, "Display", "Shadow Client branding watermark");
        addModule("DateTime",   false, "Display", "Real-world date and time");
        addModule("Uptime",     false, "Display", "Game uptime (HH:MM:SS since launch)");
        addModule("HeldDur",    false, "Inventory","Held item durability % (color-coded)");
        addModule("EnchantList",false, "Inventory","Enchants on the currently-held item");
        addModule("PotionTimer",false, "Display", "Active status effects with countdowns");
        addModule("InGameTime", false, "World",   "Minecraft world time as 24h clock");
        addModule("StepCounter",false, "Display", "Total blocks walked this session");
        addModule("CapeAlt",    false, "Utility", "Use cape2.png (sword-emblem) instead of cape1.png");
        addModule("LowHpAlert", false, "Combat",  "Audible alert + HUD flash when HP drops below 6");
        addModule("Dimension",  false, "World",   "Current dimension name (Overworld/Nether/End/custom)");
        addModule("Air",        false, "Display", "Air bubbles remaining when submerged");
        addModule("CompassExt", false, "Display", "Compass with cardinal + ordinal directions (NNE, ESE, etc.)");
        addModule("AttackDmg",  false, "Combat",  "Held item's attack damage attribute");
        addModule("ArmorPts",   false, "Combat",  "Total armor protection points (out of 20)");
        addModule("MoonPhase",  false, "World",   "Current moon phase (full / new / quarter)");
        addModule("HitMarker",  false, "Combat",  "Visual ✕ flash on screen when you land a hit");
        addModule("JumpCount",  false, "Display", "Total jumps this session");
        addModule("WelcomeMsg", false, "Server",  "Chat greeting from Shadow Client when you join a server");
        // AutoTotem removed — auto-action that gives unfair PvP advantage.
        addModule("TitleAnim",  false, "Display", "Animated rainbow gradient on the in-game menu title");
        addModule("NameTag",    false, "Display", "Show your username pinned at the top of the HUD");
        // EnemyArmor removed — see-through-walls combat-info hack.
        addModule("GameTick",   false, "World",   "Current world tick counter (mod 24000)");
        addModule("InvBar",     false, "Inventory","Visual progress bar of free hotbar+inventory slots");
        addModule("BlockUnder", false, "World",   "Block currently under the player's feet");
        addModule("WorldName",  false, "Server",  "Singleplayer world name or multiplayer server label");
        addModule("Coords4TP",  false, "Display", "XYZ formatted as /tp argument for easy copy");
        addModule("PlayerCount",false, "Server",  "Players currently online (from tab list)");
        addModule("ToolBreak",  false, "Combat",  "Sound + flash when held tool dips below 5% durability");
        addModule("Diagnostic", false, "Display", "Lists every enabled module + any that have logged errors (debug)");
        addModule("AutoTool",   false, "Utility", "Auto-swap to best tool (pickaxe/axe/shovel/shears/sword) for block at crosshair");
        addModule("HitSounds",  false, "Combat",  "Play a click on every landed hit (Lunar-style, pitch varies slightly)");
        addModule("NoHurtCam",  false, "Utility", "Disable the screen-shake camera tilt when taking damage");
        // DamageIndicator removed — combat-prediction PvP hack.
        addModule("DeathLog",   false, "Display", "Auto-save death coords + dimension to config/shadowclient-deaths.txt");
        addModule("CoordsHistory", false, "Display", "Track places visited (>100b apart) → config/shadowclient-coords-history.txt");
        addModule("HotbarTotal",false, "Inventory", "Sum of held-item count across entire inventory (handy for blocks/arrows)");
        addModule("PingHistory",false, "Server",  "Visual ping bar showing recent ping samples (last 30s)");
        addModule("HotbarLock", false, "Inventory", "Lock current hotbar slot — scroll wheel and 1-9 keys revert until toggled off");
        addModule("CoordsBeacon",false,"Display", "Direction + distance to world spawn (0,0)");
        addModule("XpDrop",     false, "Display", "Animated +N counter when you absorb XP orbs");
        addModule("HungerWarn", false, "Combat",  "Audible + visual alert when food drops below 6 (3 chicken legs)");
        addModule("EnchantPreview", true, "Inventory", "Show ALL enchants per option in the enchanting table (not just the top one)");
        // AutoEat removed — auto-action that gives unfair advantage.
        addModule("BlockInfo",  false, "Display", "Block at crosshair: name + hardness");
        addModule("PearlCool",  false, "Combat",  "Show ender pearl cooldown timer (vanilla 1s = 20 ticks)");
        addModule("AntiAFK",    false, "Utility", "Tiny periodic input to prevent server AFK timeouts");
        addModule("CombatTime", false, "Combat",  "Time since last damage taken (combat tag indicator)");
        addModule("TotemCount", false, "Combat",  "Show total totems of undying in inventory");
        addModule("PearlCount", false, "Combat",  "Show total ender pearls in inventory");
        addModule("ArrowCount", false, "Combat",  "Show total arrows (any type) in inventory");
        addModule("GoldCount",  false, "Inventory", "Show total gold ingots + nuggets + blocks in inventory");
        addModule("AutoReconnect", false, "Server", "Auto-reconnect to last server when disconnected (3s delay)");
        addModule("FireTimer",  false, "Combat",  "Burning seconds remaining when on fire");
        addModule("FreezeTimer",false, "Combat",  "Powder-snow freeze progress");
        addModule("ServerBrand",false, "Server",  "Server software brand (Paper / Spigot / Vanilla / etc)");
        addModule("XpToNext",   false, "Display", "XP points needed for next level (exact)");
        addModule("PortalTimer",false, "World",   "Nether portal entry countdown (0/80)");
        addModule("TpsBar",     false, "Server",  "Visual TPS bar (20 = green / 10 = red)");
        addModule("HungerBar",  false, "Inventory","Numeric hunger + saturation overlay");
        addModule("BedDist",    false, "World",   "Distance to last respawn point (bed / anchor)");
        addModule("InvDurAvg",  false, "Inventory","Average durability % across armor + held tool");
        addModule("YBest",      false, "Display", "Lowest Y reached this session (mining tracker)");
        addModule("AbsorptionHp",false,"Combat",  "Absorption hearts (golden apple / boss bar)");
        addModule("TopSpeed",   false, "Display", "Peak speed (blocks/sec) this session");
        addModule("LookYaw",    false, "World",   "Yaw / pitch in degrees (3-decimal precision)");
        addModule("ClickTotal", false, "Combat",  "Total clicks (left/right) this session");
        addModule("VoidWarn",   false, "World",   "Audible alert when below Y=5 (void protection)");
        addModule("DirectionWord",false,"World",  "Compass direction as full word (north / northeast)");
        addModule("PortalCoords",false,"World",   "Paired Nether/Overworld coords (auto-converts ratio)");
        addModule("HeartIcons", false, "Inventory","Visual heart row (red + yellow absorption)");
        addModule("HotbarItems",false, "Inventory","All 9 hotbar slots in one compact row");
        addModule("YBestHigh",  false, "Display", "Highest Y reached this session (peak counterpart)");
        addModule("ServerJoinTime",false,"Server","Time since current server/world join");
        addModule("WeatherAlert",false,"World",   "Visual flash when weather state changes");
        addModule("WaypointDist",false,"Utility", "Distance to your set Waypoint (Home key)");
        addModule("InvFull",    false, "Inventory","Inventory % occupied (slots used)");
        addModule("BedExplodes",false, "Combat",  "Warn when holding bed in Nether/End (will explode)");
        addModule("AnchorExplodes",false,"Combat","Warn when holding respawn anchor in non-Nether dim");
        addModule("NetherCeiling",false,"World",  "Distance below Nether bedrock ceiling (Y=128)");
        addModule("LowDurAlert",false, "Combat",  "Visual flash when ANY worn armor below 10% durability");
        addModule("FovDisplay", false, "Display", "Current FOV setting");
        addModule("RenderDist", false, "Display", "Render distance (chunks)");
        addModule("GuiScale",   false, "Display", "GUI scale setting");
        addModule("FpsCap",     false, "Display", "Maximum FPS cap setting");
        addModule("EntityDist", false, "Display", "Entity render distance scale");
        addModule("RotationLog",false, "Display", "Yaw/pitch delta per tick (debug)");
        addModule("MaxLevel",   false, "Display", "Highest XP level reached this session");
        addModule("MinHp",      false, "Combat",  "Lowest HP touched this session (close-call tracker)");
        addModule("DimWatcher", false, "World",   "Banner notification when dimension changes");
        addModule("DayPhase",   false, "World",   "Day/Sunset/Night/Sunrise label");
        addModule("ChunkLoaded",false, "World",   "Loaded chunks in client view");
        addModule("MoveArrow",  false, "Display", "Arrow showing W/A/S/D input direction");
        // ----- Social / server-aware modules --------------------------------
        addModule("Friends",      false, "Server", "Highlight friends (from config/shadowclient-friends.txt) in tab/chat");
        addModule("FriendsList",  false, "Server", "HUD list of friends with same-server online status");
        addModule("FriendNotify", false, "Server", "Toast when a friend appears in chat");
        addModule("MuteList",     false, "Server", "Hide chat from players in mutes.txt");
        addModule("AutoPreset",   false, "Server", "Apply preset matching server name on join");
        addModule("ServerRules",  false, "Server", "Auto-disable banned mods per server, restore on leave");
    }
    /** Toggle pin state for a module. Persists. (DEFAULT_STATE and
     *  pinnedModules are declared above the static block — see comments
     *  there for the ordering rationale.) */
    private static void togglePin(String name) {
        if (!MODULES.containsKey(name)) return;
        if (pinnedModules.contains(name)) {
            pinnedModules.remove(name);
            flashToast("§7☆ Unpinned §f" + name);
        } else {
            pinnedModules.add(name);
            flashToast("§e★ Pinned §f" + name);
        }
        saveConfig();
    }

    /** User's saved snapshot — populated when the user clicks "💾 Save" in
     *  the preset bar. Stays {@code null} until first save. Persisted as
     *  {@code snapshot} in the config so it survives launches. */
    private static Map<String, Boolean> userSnapshot = null;
    /** Wall-clock millis of the last save/load — drives the brief "✓ Saved!"
     *  / "↺ Loaded" toast in the menu so the user gets immediate confirmation
     *  that their click took effect. {@code 0} = no recent action. */
    private static long lastFlashMs;
    /** Toast message shown for ~2 s after a save/load/preset action. */
    private static String lastFlashMsg = "";

    /** Take a snapshot of the current MODULES state. Called on Save click. */
    private static void saveUserSnapshot() {
        userSnapshot = new LinkedHashMap<>(MODULES);
        saveConfig();
        long onCount = userSnapshot.values().stream().filter(Boolean.TRUE::equals).count();
        System.out.println("[ShadowHud] saved snapshot — " + onCount + " on");
        flashToast("§a✓ Saved §f" + onCount + "§a modules");
    }
    /** Restore the user's last saved snapshot. No-op if none saved yet. */
    private static void loadUserSnapshot() {
        if (userSnapshot == null) {
            System.out.println("[ShadowHud] no snapshot to load");
            flashToast("§e⚠ No snapshot to load — §fSave §efirst");
            return;
        }
        int restored = 0;
        for (Map.Entry<String, Boolean> e : userSnapshot.entrySet()) {
            if (MODULES.containsKey(e.getKey())) {
                MODULES.put(e.getKey(), e.getValue());
                restored++;
            }
        }
        saveConfig();
        long onCount = userSnapshot.values().stream().filter(Boolean.TRUE::equals).count();
        System.out.println("[ShadowHud] loaded snapshot: " + restored + " entries restored");
        flashToast("§a↺ Loaded — §f" + onCount + "§a on, §f" + restored + "§a restored");
    }
    /** Set a brief on-screen toast. Lives ~2 s. Visual confirmation that
     *  Ctrl+S / Save button clicks actually did something — otherwise the
     *  user might press multiple times wondering if it worked. */
    private static void flashToast(String msg) {
        lastFlashMs = System.currentTimeMillis();
        lastFlashMsg = msg;
    }

    private static void addModule(String name, boolean def, String cat, String desc) {
        MODULES.put(name, def);
        MODULE_CAT.put(name, cat);
        MODULE_DESC.put(name, desc);
        DEFAULT_STATE.put(name, def);
    }

    /** Preset names — each preset is a function that flips a defined set of
     *  modules on/off in one shot. Rendered as virtual cards above the real
     *  module list so they're discoverable. */
    private static final String[] PRESETS = {"All Off", "Defaults", "Essentials", "PvP", "SMP", "All On"};
    /** Return the index in {@link #PRESETS} whose state matches the current
     *  MODULES exactly, or {@code -1} if none does. Cheap: bails on the first
     *  mismatch per preset. Lets the menu highlight the current preset so
     *  the user can see at a glance which one (if any) they're "in". */
    private static int detectActivePreset() {
        // Cheapest first — "All Off" / "All On" via the cached count.
        if (cachedOnCount < 0) {
            int n = 0;
            for (Boolean b : MODULES.values()) if (Boolean.TRUE.equals(b)) n++;
            cachedOnCount = n;
        }
        if (cachedOnCount == 0) return 0;   // All Off
        // For the rest we need to actually compare. Brute-force compare each
        // preset's expected state against MODULES.
        // Test "Defaults" preset against DEFAULT_STATE.
        boolean defaultsMatch = true;
        for (Map.Entry<String, Boolean> e : DEFAULT_STATE.entrySet()) {
            Boolean cur = MODULES.get(e.getKey());
            if (!java.util.Objects.equals(cur, e.getValue())) { defaultsMatch = false; break; }
        }
        if (defaultsMatch) return 1;
        // Skip the rest — comparing PvP/SMP/Essentials/All On state is
        // expensive and the user's state usually deviates from the named
        // presets after any custom toggle. Returning -1 is a clean "no
        // preset matches" signal.
        return -1;
    }

    /** Public entry — fires the user-facing toast and delegates to the
     *  recursive worker. The worker calls itself for "PvP" and "SMP"
     *  (which both layer onto Essentials), so we MUST NOT toast inside
     *  the worker — otherwise the recursive Essentials call would
     *  overwrite the user-intended PvP/SMP message. */
    private static void applyPreset(String name) {
        flashToast("§a✓ Applied preset §f" + name);
        applyPresetInternal(name);
        saveConfig();   // user-driven path → persist
    }
    /** Apply a preset WITHOUT writing to disk — used by auto-paths
     *  (AutoPreset on server join). Keeps the persisted config a faithful
     *  record of what the user manually configured, so closing MC mid-server
     *  doesn't bake in any session-only auto-toggles. */
    private static void applyPresetSilent(String name) {
        applyPresetInternal(name);
        // intentionally NO saveConfig
    }
    private static void applyPresetInternal(String name) {
        switch (name) {
            case "All Off":
                for (String k : MODULES.keySet()) MODULES.put(k, false);
                break;
            case "Defaults":
                // Restore each module to the value it was originally given via
                // addModule(). Lets the user reset to a known-good baseline
                // after experimentation without losing the option to opt back
                // in to specific modules.
                for (Map.Entry<String, Boolean> e : DEFAULT_STATE.entrySet()) {
                    if (MODULES.containsKey(e.getKey())) MODULES.put(e.getKey(), e.getValue());
                }
                break;
            case "Essentials":
                applyPresetInternal("All Off");
                for (String k : new String[]{"FPS","XYZ","Facing","Biome","HP",
                        "Held","Effects","Server","Ping","Map","Keystrokes","Zoom","TabHp"})
                    if (MODULES.containsKey(k)) MODULES.put(k, true);
                break;
            case "PvP":
                applyPresetInternal("Essentials");
                for (String k : new String[]{"CPS","Combo","Reach","Cooldown",
                        "Damage","HitDir","Attacker","BowCharge",
                        "Killstreak","HitsDealt","HitsTaken","DPS","Nearby"})
                    if (MODULES.containsKey(k)) MODULES.put(k, true);
                break;
            case "SMP":
                applyPresetInternal("Essentials");
                for (String k : new String[]{"Day","Light","Weather","Difficulty",
                        "GameMode","Move","Level","WorldBorder","DeathPos",
                        "NearMob","SleepInfo","ArmorPct","InvFree","HandState"})
                    if (MODULES.containsKey(k)) MODULES.put(k, true);
                break;
            case "All On":
                for (String k : MODULES.keySet()) MODULES.put(k, true);
                // …except a few opt-in interactive ones that change behaviour
                MODULES.put("AutoRespawn", false);
                MODULES.put("Fullbright",  false);
                MODULES.put("Streamer",    false);
                MODULES.put("Crosshair",   false);
                MODULES.put("ToggleSprint",false);
                MODULES.put("ToggleSneak", false);
                MODULES.put("SmoothSwing", false);
                break;
        }
        // saveConfig is the caller's responsibility (applyPreset saves,
        // applyPresetSilent does not). Keeps the auto-path memory-only.
    }

    // ---- Last-death coordinate -----------------------------------------
    private static double  deathX, deathY, deathZ;
    private static String  deathWorld = "";
    private static boolean deathPosSet;

    // ---- Per-module renderer options (set via config panel) -----------
    /** HP: include the golden absorption hearts in the readout. */
    private static boolean cfgHpShowAbs    = true;
    /** HP: include the small "Armor N" tail. */
    private static boolean cfgHpShowArmor  = true;
    /** HP: hide the "HP" / "Armor" labels — just numbers. */
    private static boolean cfgHpCompact    = false;
    /** FPS: include the trailing " FPS" word. */
    private static boolean cfgFpsLabel     = true;
    /** FPS: pick a visual style — 0 = number+label, 1 = bar, 2 = compact. */
    private static int     cfgFpsStyle     = 0;
    private static final String[] FPS_STYLE_NAMES = {"Default", "Bar", "Compact"};
    /** XYZ: number of decimal places to show on each coordinate. */
    private static int     cfgXyzDecimals  = 1;
    /** XYZ: separator between values — 0=" / " 1=" , " 2=" " */
    private static int     cfgXyzSep       = 0;
    private static final String[] XYZ_SEP = {" §4/ §f", " §4, §f", " §f"};
    /** XYZ: hide the "XYZ" label and separators (compact mode). */
    private static boolean cfgXyzCompact   = false;
    /** Time: 24h vs 12h clock. */
    private static boolean cfgTime24h      = true;
    /** Time: include seconds. */
    private static boolean cfgTimeSeconds  = true;
    /** Compass: include the degree readout in parentheses. */
    private static boolean cfgCompassDegs  = true;
    /** Compass: 0=8-direction (N/NE/...), 1=16-direction (NNE/ENE/...), 2=degrees only. */
    private static int     cfgCompassMode  = 0;
    private static final String[] COMPASS_MODE_NAMES = {"8-dir", "16-dir", "Degrees only"};

    // ---- HUD layout config --------------------------------------------
    /** Where the HUD stack lives on screen. 0..8 in this order:
     *    TL TC TR
     *    ML MC MR
     *    BL BC BR
     *  Default 4 = mid-center (slight upward bias as historically). */
    private static int     cfgHudAnchor    = 4;
    /** HUD card-stack scale as a percentage (50..200). 100 = native size. */
    private static int     cfgHudScale     = 100;
    /** HUD card opacity as a percentage (10..100). 100 = fully opaque. */
    private static int     cfgHudOpacity   = 88;
    /** Pixel offsets applied AFTER anchor resolution. Lets the user drag
     *  the HUD off the snap-anchor while keeping the anchor as the base. */
    private static int     cfgHudOffsetX = 0;
    private static int     cfgHudOffsetY = 0;
    /** User-pickable accent color used by the menu chrome and HUD card
     *  highlights. Index into THEME_COLORS; the last slot is "custom hex"
     *  stored in {@link #cfgThemeCustom}. Default 0 = brand red. */
    private static int     cfgThemeIdx     = 0;
    private static int     cfgThemeCustom  = 0xFFAF121A;
    /** True when the hex-input dialog is being used to pick a theme color
     *  (vs. the Crosshair custom color). Reset on Enter or Esc. */
    private static boolean themePickerActive = false;
    /** When non-null, the hex-input dialog is being used to pick the
     *  per-module accent color for the named module. Reset on Enter/Esc. */
    private static String  modulePickerActive = null;
    /** Hex picker is being used to set the GlintTune color. */
    private static boolean glintPickerActive = false;

    // ---- Rich HSV color-picker overlay --------------------------------
    /** True when the gradient color picker modal is showing. */
    private static boolean colorPickerOpen = false;
    /** Current HSV in 0..1. Updated as user clicks/drags within the modal. */
    private static float colorPickerH = 0f, colorPickerS = 0f, colorPickerV = 1f;
    /** Callback that receives the picked ARGB color when user hits OK. */
    private static java.util.function.IntConsumer colorPickerCallback = null;
    /** Drag tracking: which sub-region is being dragged. */
    private static int colorPickerDrag = 0;   // 0=none, 1=HS grid, 2=V slider

    // ---- Per-module HUD positioning -----------------------------------
    /** Module name being currently render-tested. Set by {@link #modOn}
     *  whenever a module's gate test passes; consumed by {@link #drawLine}
     *  to associate buffered HUD lines with their owner module so each one
     *  can be re-anchored independently. */
    private static volatile String currentModuleRendering = null;
    /** Per-module anchor override. Key = module name, Value = anchor 0..8.
     *  Absent = use the global HudLayout anchor. Persisted in saveConfig. */
    private static final java.util.Map<String, Integer> moduleAnchorOverrides
        = new java.util.LinkedHashMap<>();
    /** Per-module pixel offset override. Same shape as anchor map but
     *  values are int[2] = {dx, dy}. Persisted alongside the anchor map. */
    private static final java.util.Map<String, int[]> moduleOffsetOverrides
        = new java.util.LinkedHashMap<>();
    /** Per-module accent-color override (ARGB). Falls through to themeAccent()
     *  when the module isn't in the map. */
    private static final java.util.Map<String, Integer> moduleColorOverrides
        = new java.util.LinkedHashMap<>();
    /** Resolve a module's effective accent color (override OR global theme). */
    private static int moduleAccent(String moduleName) {
        if (moduleName != null && moduleColorOverrides.containsKey(moduleName)) {
            return moduleColorOverrides.get(moduleName);
        }
        return themeAccent();
    }
    /** Parallel buffer to hudLineBuffer — each index records the module
     *  the line came from (or null). flushHudLines uses this to group
     *  lines by anchor when rendering. */
    private static final java.util.List<String> hudLineModules = new java.util.ArrayList<>();

    /** Wrapper around MODULES.getOrDefault that tracks the current module
     *  for HUD-line positioning. Drop-in replacement for the boolean check. */
    private static boolean modOn(String name, boolean def) {
        boolean on = MODULES.getOrDefault(name, def);
        if (on) currentModuleRendering = name;
        return on;
    }
    /** Named theme palette (ARGB). Final slot is reserved for a custom hex. */
    private static final int[]    THEME_COLORS = {
        0xFFAF121A,   // 0  Crimson (default brand red)
        0xFF1A6BC8,   // 1  Sapphire blue
        0xFF22A04B,   // 2  Emerald green
        0xFF8E44AD,   // 3  Violet purple
        0xFF1FA8A0,   // 4  Teal cyan
        0xFFE07090,   // 5  Rose pink
        0xFFE07F1F,   // 6  Amber orange
        0xFFD8C75A,   // 7  Goldenrod
    };
    private static final String[] THEME_NAMES = {
        "Crimson", "Sapphire", "Emerald", "Violet",
        "Teal", "Rose", "Amber", "Gold", "Custom"
    };
    /** Resolve current accent (full ARGB). */
    private static int themeAccent() {
        if (cfgThemeIdx >= THEME_COLORS.length) return cfgThemeCustom;
        return THEME_COLORS[Math.max(0, Math.min(THEME_COLORS.length - 1, cfgThemeIdx))];
    }
    /** Lighter variant (for hover / glow). Adds ~25% white. */
    private static int themeAccentLight() {
        int c = themeAccent();
        int r = Math.min(0xFF, ((c >> 16) & 0xFF) + 0x40);
        int g = Math.min(0xFF, ((c >>  8) & 0xFF) + 0x40);
        int b = Math.min(0xFF, ( c        & 0xFF) + 0x40);
        return (c & 0xFF000000) | (r << 16) | (g << 8) | b;
    }
    /** HUD editor mode — when true the mouse is unlocked, the stack draws
     *  with a colored frame, drag moves it, scroll resizes, Esc saves. */
    private static boolean hudEditMode = false;
    /** Bounds of the last-rendered HUD card stack — captured each frame
     *  so the editor knows where the user is clicking. */
    private static int     hudFrameL = 0, hudFrameT = 0, hudFrameR = 0, hudFrameB = 0;
    /** Drag state. dragOriginX/Y is the mouse pos at click; dragStartOffsetX/Y
     *  is cfgHudOffsetX/Y at click time. We compute live offset from those. */
    private static boolean hudDragging = false;
    private static int     hudDragOriginX, hudDragOriginY;
    private static int     hudDragStartOffX, hudDragStartOffY;
    /** Timestamp of the first HUD render — drives the startup splash. */
    private static long    splashFirstRenderMs = 0L;
    private static final String[] HUD_ANCHOR_NAMES = {
        "Top-Left",     "Top-Center",     "Top-Right",
        "Mid-Left",     "Center",         "Mid-Right",
        "Bottom-Left",  "Bottom-Center",  "Bottom-Right"
    };

    /** Ping: 0 = number ("Ping 23ms"), 1 = bar visualization. */
    private static int     cfgPingStyle    = 0;
    private static final String[] PING_STYLE_NAMES = {"Number", "Bar"};
    /** Memory: 0 = auto, 1 = MB, 2 = GB. */
    private static int     cfgMemFormat    = 0;
    private static final String[] MEM_FMT_NAMES   = {"Auto", "MB", "GB"};
    /** Day: 0 = "Day N", 1 = "D N", 2 = just "N". */
    private static int     cfgDayFormat    = 0;
    private static final String[] DAY_FMT_NAMES   = {"Day N", "D N", "N"};

    /** Reach: only show when actively targeting an entity (vs. always). */
    private static boolean cfgReachOnlyOnTarget = false;
    /** Reach: include "blocks" / "b" suffix. */
    private static boolean cfgReachShowUnit     = true;

    /** NetGraph: window size in seconds. */
    private static int     cfgNetGraphSeconds   = 30;
    private static final int[]    NETGRAPH_SECS = {10, 30, 60, 120};

    /** Killstreak: 0 = reset on death only, 1 = death + 60s idle, 2 = death + 30s, 3 = never. */
    private static int     cfgKillstreakReset   = 0;
    private static final String[] KS_RESET_NAMES = {"On death", "Idle 60s", "Idle 30s", "Never"};

    /** Saturation: show as numeric "Sat 12.5" vs visual bar. */
    private static int     cfgSatStyle          = 0;
    private static final String[] SAT_STYLE_NAMES = {"Numeric", "Bar"};

    /** ToolBreak: durability % below which the warning fires. */
    private static int     cfgToolBreakThresh   = 5;
    private static final int[]    TB_THRESH      = {3, 5, 10, 15, 25};
    /** ToolBreak: play a sound when threshold is hit. */
    private static boolean cfgToolBreakSound    = true;

    /** AntiAFK: poke interval (seconds). */
    private static int     cfgAntiAfkInterval   = 25;
    private static final int[]    AFK_INTERVALS  = {15, 25, 45, 60, 120};

    /** HotbarFade: idle seconds before the hotbar fades out. */
    private static int     cfgHotbarFadeDelay   = 3;
    private static final int[]    HOTBAR_DELAYS  = {1, 2, 3, 5, 10};

    /** AutoRespawn: delay (ms) between detecting death and clicking respawn. */
    private static int     cfgAutoRespawnDelay  = 250;
    private static final int[]    AR_DELAYS      = {0, 100, 250, 500, 1000};

    /** Fullbright: gamma value to set when active. 100 = vanilla "Bright!". */
    private static int     cfgFullbrightLevel   = 100;
    private static final int[]    FB_LEVELS      = {50, 100, 200, 500, 1000};

    /** AutoGG: chat message to send after a duel ends. */
    private static String  cfgAutoGgMessage     = "gg";
    private static final String[] AUTOGG_MSGS    = {"gg", "GG", "Good game!", "ggwp", "well played"};
    private static int     cfgAutoGgIdx         = 0;

    /** SwordVisuals: 50-250 (% of native size). 100 = no scaling. */
    private static int     cfgSwordScale        = 100;
    /** Apply scale to ALL held items, not just swords/tridents. */
    private static boolean cfgSwordScaleAllItems = false;

    /** GlintTune: enchant-glint color (ARGB; high byte ignored, alpha drives strength). */
    private static int     cfgGlintColor        = 0xFFAFAFAF;
    /** Glint strength as a percent (0..200). 100 = vanilla brightness. 0 = invisible. */
    private static int     cfgGlintStrength     = 100;

    /** BlockHighlight: ARGB color for the targeted-face overlay. */
    private static int     cfgBlockHighlightColor = 0xFFFF2030;
    /** Opacity 0..100. */
    private static int     cfgBlockHighlightAlpha = 50;

    // ---- Per-module config panel (Meteor-style) -----------------------
    /** Module name whose config panel is currently open, or null. The
     *  panel is rendered as an overlay on top of the cards grid. */
    private static String configPanelModule = null;
    /** Real-time clock at the moment the config panel was opened. Used
     *  to drive a fade-in animation so the panel doesn't pop in cold. */
    private static long   configPanelOpenedAtMs = 0L;
    /** Vertical scroll offset (pixels) inside the panel when content
     *  overflows. Reset to 0 on each panel open. */
    private static int    configPanelScroll = 0;
    /** Modules that expose extra settings (color, particle, position, etc).
     *  Cards render a small gear button when listed here; clicking opens
     *  the config panel. Other modules just have on/off. */
    private static final java.util.Set<String> CONFIGURABLE =
        new java.util.HashSet<>(java.util.Arrays.asList(
            "Crosshair", "Map", "Keystrokes", "Effects",
            "Trail", "Halo", "Wings", "AngelWings", "Fairies",
            "Footsteps", "BowTrail",
            "HP", "FPS", "XYZ", "Time", "Compass", "HudLayout",
            "Ping", "Memory", "Day",
            "Reach", "NetGraph", "Killstreak", "Saturation", "ToolBreak"));

    // ---- Crosshair customization --------------------------------------
    /** 0=Cross  1=Circle  2=Dot  3=X  — cycled when Enter is pressed on the
     *  Crosshair card in the menu. Saved alongside the toggle. */
    private static int crosshairShape;
    /** Hex colours cycled on a second Enter press. Index into the array. */
    private static int crosshairColorIdx;
    private static final int[] CROSSHAIR_COLORS = {
        0xFFFF2030, // shadow-red
        0xFFFFFFFF, // white
        0xFF20FF40, // green
        0xFF40A0FF, // blue
        0xFFFFFF40, // yellow
        0xFFFF80FF, // pink
    };
    private static final String[] CROSSHAIR_SHAPE_NAMES = {"Cross", "Circle", "Dot", "X"};
    /** User-defined hex color, edited via the in-menu picker. Lives at the
     *  last cycle slot (index = CROSSHAIR_COLORS.length). */
    private static int customCrosshairColor = 0xFFFFFFFF;
    /** When true, the menu intercepts hex digit keys to populate the picker. */
    private static boolean hexInputActive;
    private static final StringBuilder hexInputBuf = new StringBuilder(6);
    private static final int KEY_EQUALS    = 61;   // = opens the picker
    private static final int KEY_BACKSPACE_HEX = 259;
    private static final int KEY_ESC_HEX       = 256;

    // ---- Per-module customization indices (Enter cycles when card is on) --
    /** Map size: 1=Small (48px), 2=Medium (64px), 3=Large (96px). 0 = off. */
    private static int mapSizeIdx = 2;
    /** Keystrokes overlay corner: 0=BR, 1=BL, 2=TR, 3=TL. */
    private static int keysPosIdx = 0;
    /** How many active effect lines to draw (3/6/12). */
    private static int effectsLimitIdx = 1;
    private static final String[] MAP_SIZE_NAMES   = {"", "Small", "Medium", "Large"};
    private static final int[]    MAP_SIZE_PIXELS  = {0,  48,      64,       96};
    private static final String[] KEYS_POS_NAMES   = {"BR", "BL", "TR", "TL"};
    private static final int[]    EFFECTS_LIMITS   = {3, 6, 12};

    /** Trail / Halo cosmetic — particle indexes into TRAIL_PARTICLES below. */
    private static int trailIdx;
    private static int haloIdx;
    /** (Display name, ParticleTypes static field name) — first entry is unused
     *  ("off" is represented by the module being toggled false). */
    private static final String[][] TRAIL_PARTICLES = {
        {"Heart",       "field_11201"},  // HEART
        {"Flame",       "field_11240"},  // FLAME
        {"Soul Flame",  "field_22246"},  // SOUL_FIRE_FLAME
        {"End Rod",     "field_11207"},  // END_ROD
        {"Portal",      "field_11214"},  // PORTAL
        {"Note",        "field_11224"},  // NOTE
        {"Crit",        "field_11205"},  // CRIT
        {"Glow",        "field_28479"},  // GLOW
        {"Snowflake",   "field_28013"},  // SNOWFLAKE
        {"Totem",       "field_11220"},  // TOTEM_OF_UNDYING
        {"Enchant",     "field_11215"},  // ENCHANT
        {"Happy",       "field_11211"},  // HAPPY_VILLAGER
        {"Soul",        "field_23114"},  // SOUL
        {"Firework",    "field_11248"},  // FIREWORK
        {"Smoke",       "field_11251"},  // SMOKE - dark grey
        {"Lg Smoke",    "field_11237"},  // LARGE_SMOKE - bigger dark puffs (was 11236 = EXPLOSION)
        {"Squid Ink",   "field_28478"},  // GLOW_SQUID_INK - dark teal
        {"Ash",         "field_22247"},  // ASH - light grey drift
        {"Rainbow",     ""},              // sentinel — auto-rotates 5x/second
    };

    /** Resolve the active particle for a given cycle index, handling the
     *  Rainbow sentinel by auto-picking a different particle every 200 ms. */
    private static Object resolveParticleAt(int idx) {
        if (idx < 0 || idx >= TRAIL_PARTICLES.length) return null;
        String fname = TRAIL_PARTICLES[idx][1];
        if (fname.isEmpty()) {
            int real = (int)((System.currentTimeMillis() / 200) % (TRAIL_PARTICLES.length - 1));
            fname = TRAIL_PARTICLES[real][1];
        }
        return particleByField(fname);
    }
    private static Method  addParticleMethod;          // resolved on first use
    private static Class<?> particleEffectInterface;
    private static boolean  particleResolveTried;
    private static double   prevTrailX = Double.NaN, prevTrailY, prevTrailZ;
    private static float    haloAngle;

    /** Cycle index into TRAIL_PARTICLES for the wing material. Defaults to
     *  Dragon Breath if available, otherwise Portal. */
    private static int      wingsParticleIdx;
    /** Folded bat-wing silhouette — wings hang down along the body and spread
     *  slightly outward, like a resting dragon/demon. Each point: (extension
     *  outward from spine, vertical from shoulder pivot — negative = down).
     *  18 outline points + 7 internal "bones/membrane" points = dense enough
     *  to read as a solid wing rather than dotted. */
    private static final float[][] WING_SHAPE = {
        // Top of wing (shoulder + upper bone)
        {0.20f,  0.05f},
        {0.35f, -0.05f},
        {0.50f, -0.20f},
        // Outer (leading) edge curving down
        {0.65f, -0.45f},
        {0.75f, -0.75f},
        {0.78f, -1.05f},
        {0.72f, -1.35f},
        {0.60f, -1.60f},
        {0.42f, -1.78f},
        {0.22f, -1.85f},   // tip pointing straight down
        // Inner (trailing) edge curving back up to the body
        {0.05f, -1.65f},
        {0.00f, -1.30f},
        {0.02f, -0.95f},
        {0.06f, -0.60f},
        {0.10f, -0.30f},
        {0.13f, -0.05f},
        // Membrane fill — particles that visually plug the gaps
        {0.20f, -0.30f},
        {0.30f, -0.55f},
        {0.40f, -0.85f},
        {0.45f, -1.15f},
        {0.40f, -1.40f},
        {0.30f, -1.65f},
        {0.18f, -1.45f},
        {0.15f, -1.10f},
        {0.20f, -0.75f},
    };
    private static long     wingsTimerStart;

    /** Cape style cycle (uses TRAIL_PARTICLES table for material). The cape is
     *  rendered as a 5×6 grid of particles draping behind the shoulders, with
     *  movement physics (lags behind when running, hangs at rest). */
    private static int      capeIdx;
    private static double   capeLagX, capeLagZ;        // smoothed lag offset
    private static long     capeLastFrameMs;

    /** Angel-style wings: curved feather silhouette, slightly slower flap. */
    private static int      angelWingsIdx;
    private static long     angelWingsTimerStart;
    private static final float[][] ANGEL_WING_SHAPE = {
        // Outer feather edge — pronounced curve
        {0.20f,  0.10f},
        {0.55f,  0.45f},
        {1.00f,  0.80f},
        {1.45f,  1.05f},
        {1.85f,  1.10f},
        {2.20f,  0.95f},
        {2.45f,  0.55f},
        {2.55f,  0.05f},
        // Inner feather edge curving back
        {2.45f, -0.40f},
        {2.10f, -0.65f},
        {1.65f, -0.65f},
        {1.20f, -0.55f},
        {0.80f, -0.40f},
        {0.40f, -0.20f},
        // Inner secondary feathers
        {0.65f,  0.20f},
        {1.05f,  0.40f},
        {1.45f,  0.55f},
        {1.85f,  0.55f},
    };

    /** Fairies cosmetic — small particles orbiting around the player. */
    private static int      fairiesIdx;

    /** Footsteps cosmetic — lingering particle trail on the ground at past
     *  positions. Each entry is {x, y, z, timestampMs}. */
    private static int      footstepsIdx;
    private static final java.util.ArrayDeque<double[]> FOOTSTEP_LOG = new java.util.ArrayDeque<>();
    private static long     lastFootstepMs;

    /** Bow trail — particles trail every arrow you fire (any AbstractArrow
     *  subclass owned by you). Interpolates between frames so the trail is
     *  continuous even at 60+ m/s flight speed. */
    private static int      bowTrailIdx;

    // ---- SmoothSwing reflection cache ---------------------------------
    private static Field  handSwingField;     // LivingEntity.handSwingTicks
    private static int    smoothSwingDelay;   // local delay accumulator
    private static boolean smoothSwingTried;

    // ---- Fullbright reflection cache ----------------------------------
    private static Object  gammaOption;
    private static double  savedGamma = -1;
    private static boolean fullbrightActive;
    private static boolean gammaResolveTried;

    // ---- Waypoint state -----------------------------------------------
    private static double  wpX, wpY, wpZ;
    private static String  wpWorld;
    private static boolean wpSet;
    private static final int KEY_HOME_KEY = 268;   // HOME — toggle waypoint

    // ---- Block/drop counters ------------------------------------------
    private static int  sessionBlocksPlaced;
    private static int  sessionDrops;
    private static boolean rmbWasUpForBlocks = true;
    private static boolean qWasUp = true;
    private static final int KEY_Q_KEY = 81;

    // ---- DPS sliding window -------------------------------------------
    /** Each entry is {timestampMs, hpDeltaTimes100} so we can sum hp dealt
     *  over a 5-second rolling window. We can't actually see damage we dealt
     *  to entities (server doesn't broadcast that), so we approximate via
     *  hits-landed × held-item-damage. */
    private static final java.util.Deque<long[]> DPS_HISTORY = new java.util.ArrayDeque<>();

    // ---- Session combat counters --------------------------------------
    private static int sessionHitsDealt;
    private static int sessionHitsTaken;
    /** Streak that resets on damage taken (different from Combo, which resets
     *  on a 3-second idle timer). Tracks "uninterrupted" hit dominance. */
    private static int killstreak;
    /** Wall-clock ms of the last melee hit — drives the idle-reset config. */
    private static long killstreakLastHitMs = 0L;

    // ---- Jump tracking ------------------------------------------------
    /** Peak Y reached since the player last touched the ground. */
    private static double jumpPeakY = Double.NEGATIVE_INFINITY;
    private static double jumpStartY;

    // ---- Session min-Y / peak-speed tracking --------------------------
    private static double sessionMinY = Double.POSITIVE_INFINITY;
    private static double sessionMaxY = Double.NEGATIVE_INFINITY;
    private static double sessionTopSpeed = 0.0;

    // ---- ServerJoinTime / WeatherAlert state --------------------------
    private static long   serverJoinAtMs = 0L;
    private static String serverJoinDimSnapshot = null;
    private static int    lastWeatherState = -1;   // 0=clear 1=rain 2=thunder
    private static long   weatherChangeAtMs = 0L;

    // ---- CoordCopy hotkey ---------------------------------------------
    private static final int KEY_INSERT = 260;

    // ---- AutoRespawn reflection cache ---------------------------------
    private static Object  respawnPacket;       // pre-built so we don't allocate each death
    private static Method  sendPacketMethod;
    private static boolean autoRespawnTried;
    private static long    autoRespawnArmedAtMs;

    // ---- Toggle Sprint / Sneak reflection -----------------------------
    private static Object  keySprintBinding;
    private static Object  keySneakBinding;
    private static Method  kbSetPressed;
    private static boolean keyBindReflectionTried;

    // ---- Damage / distance / world-identity tracking ------------------
    private static float   lastSeenHp = -1f;
    private static float   lastDamageAmount;
    private static long    lastDamageMs;
    private static double  prevDistX = Double.NaN, prevDistZ;
    private static double  walkedMeters;
    private static String  walkedWorldId = "";

    // ---- AFK ----------------------------------------------------------
    /** Last time we saw any input — keypress edge or mouse-button edge. */
    private static long    lastInputMs;

    // ---- FPS chart sliding window -------------------------------------
    /** Rolling 30 samples of (timestamp, fps) for min/avg/max display. */
    private static final java.util.Deque<int[]> FPS_HISTORY = new java.util.ArrayDeque<>();

    // ---- Death tracker ------------------------------------------------
    private static boolean wasDead;
    private static long    lastDeathMs;

    // ---- Session-wide records -----------------------------------------
    private static final long sessionStartMs = System.currentTimeMillis();
    private static int     bestCpsThisSession;
    private static int     bestComboThisSession;

    // ---- TPS tracker --------------------------------------------------
    /** Most recent (realtimeMs, worldTickValue) sample — used to compute TPS. */
    private static long    tpsLastRealMs;
    private static long    tpsLastWorldTime;
    private static double  cachedTps = 20.0;

    // ---- per-module runtime state ----------------------------------------
    /** Sliding 1-second click windows for CPS — populated each rising edge. */
    private static final java.util.Deque<Long> LMB_TIMES = new java.util.ArrayDeque<>();
    private static final java.util.Deque<Long> RMB_TIMES = new java.util.ArrayDeque<>();
    /** Per-button "previous frame was UP" flags for edge detection. */
    private static boolean lmbWasUp = true, rmbWasUp = true, lmbWasUpForCombo = true;
    /** Cumulative click counters since launch (used by ClickTotal). */
    private static int sessionLeftClicks  = 0;
    private static int sessionRightClicks = 0;
    /** Throttle for VoidWarn audible beep so we don't spam. */
    private static long voidWarnLastBeep = 0L;
    /** Last-tick yaw/pitch for RotationLog delta. */
    private static float rotLastYaw = 0f, rotLastPitch = 0f;
    /** Session peak XP level / minimum HP, for MaxLevel/MinHp. */
    private static int   sessionMaxLevel = 0;
    private static float sessionMinHp = Float.POSITIVE_INFINITY;
    /** Dimension-watcher state. */
    private static String lastDimension = null;
    private static long   dimChangeAtMs = 0L;
    /** Combo state: count + timestamp of last hit. Resets after 3 s idle. */
    private static int     comboCount;
    private static long    comboLastMs;
    /** Speed tracking: previous tick player position + timestamp. */
    private static double  prevSpeedX, prevSpeedZ;
    private static long    prevSpeedMs;
    private static double  cachedSpeed;
    /** Zoom state: GameOptions.fov SimpleOption + saved baseline. */
    private static Object  fovOption;            // class_315.fov (a class_7172)
    private static Method  simpleOptionGet;      // SimpleOption.getValue()
    private static Method  simpleOptionSet;      // SimpleOption.setValue(T)
    private static int     savedFov  = -1;       // baseline before zoom started
    private static boolean zoomActive;
    private static boolean zoomReflectionTried;
    private static final int KEY_C = 67;          // hold C → zoom
    /** Smooth-zoom animation state. Lunar-style: instead of slamming the FOV
     *  from baseline to baseline/4 instantly, interpolate over a few frames
     *  so the camera "eases" in and out. {@code zoomCurrentFov} tracks the
     *  animated value; {@code zoomTargetFov} is what we're animating toward
     *  (baseline/4 while held, baseline while released). {@code zoomAnimating}
     *  gates the per-frame setValue() so we stop touching the FOV option
     *  once we're close enough to target — no point in 0.001 fov updates. */
    private static float   zoomCurrentFov = -1f;
    private static float   zoomTargetFov  = -1f;
    private static boolean zoomAnimating  = false;
    /** Easing factor — fraction of the remaining delta to apply each frame.
     *  0.20 ≈ 9 frames to converge from any delta within ~0.001 (so ~150ms
     *  at 60 fps). Higher = snappier; lower = more cinematic. */
    private static final float ZOOM_EASE = 0.22f;
    /** Snap threshold — when |current - target| drops below this, we set the
     *  exact target value once and stop animating. Prevents endless tiny
     *  asymptotic updates. */
    private static final float ZOOM_SNAP = 0.5f;

    // ---- runtime state ----------------------------------------------------
    private static volatile boolean initDone;
    /** Public so the Mixin in {@code shadowhud.mixin} can read it without
     *  reflection. {@code volatile} — both the GUI thread (toggle on RShift)
     *  and Mixin handlers (mouse/cursor cancel) read this. */
    public static volatile boolean menuOpen;
    /** Accumulator for mouse-wheel scroll deltas captured by MouseMixin
     *  while the menu is open. Drained by {@code renderMenu} each frame to
     *  advance/retreat the module list. {@code volatile} because the writer
     *  (GLFW callback thread) and reader (render thread) are different. */
    public static volatile double pendingScrollDelta;
    /** Cached count of currently-enabled modules — drives the title-bar stat
     *  counter. {@code -1} = stale, must be recomputed. Invalidated whenever
     *  any module's value changes (toggle, preset apply, config load). */
    private static int     cachedOnCount = -1;
    /** Hovered-card tooltip (set during card render, consumed by footer). */
    private static String  hoverModuleName = "";
    private static String  hoverModuleDesc = "";
    /** Help overlay toggle — F1 in menu shows full keyboard reference. */
    private static boolean helpOverlayOpen;
    /** Cached ClientPlayNetworkHandler#getPlayerListEntry(UUID) method —
     *  resolved on first Ping render, reused every subsequent frame. */
    private static Method pingListEntryMethod;
    /** Cached World#getLightLevel methods (1-arg + 2-arg overloads). */
    private static Method  lightLvl1Method, lightLvl2Method;
    private static boolean lightLvlResolved;

    /** Cached mc.mouse instance (class_312) so we don't re-resolve every
     *  frame. The field is private on class_310 so we have to walk it via
     *  getDeclaredField + setAccessible. We retry every frame until non-null
     *  rather than latching on a single failure (see {@code pollInput}). */
    private static Object  mouseField;
    /** Cached `cursorLocked` boolean field on class_312 — flipping this to
     *  false stops MC from routing mouse deltas to camera rotation, which
     *  is what made clicks teleport-to-center even when the GLFW cursor was
     *  visible. */
    private static Field   mouseLockedField;
    /** One-shot diagnostic — fires the first time the menu is opened so we
     *  can see in the log which cursor-unlock paths actually ran. */
    private static boolean cursorUnlockLogged;

    /** Tracks whether we've released the mouse cursor for the current menu
     *  session — needs to flip back when the menu closes so vanilla mouselook
     *  works again. */
    private static volatile boolean cursorReleased;

    // Reflected MC handles
    private static Object mc;
    private static Field  playerField, worldField, fontField;
    private static Method getFpsMethod, drawTextMethod, fillMethod, getBiomeMethod;

    // GLFW key polling (reflected, LWJGL not on compile classpath)
    private static Class<?> glfw;
    private static Method   glfwGetKey;
    private static Method   glfwGetMouseButton;   // for LMB/RMB in keystrokes overlay
    private static Method   glfwGetCursorPos;     // for menu mouse hit-testing
    /** Direct GLFW cursor-mode override — used to forcibly show the cursor
     *  while our menu is open even when MC tries to keep it locked. */
    private static Method   glfwSetInputMode;
    /** Direct GLFW cursor-position setter — used to recenter the cursor
     *  when our menu opens, so the user can immediately see it. */
    private static Method   glfwSetCursorPos;
    /** Our GLFWScrollCallback. Static so the JVM keeps a strong ref — LWJGL
     *  allocates a native libffi trampoline backing this object, and dropping
     *  the Java ref would let GC free the trampoline and SIGSEGV the next
     *  wheel event. */
    private static Object   ourScrollCallback;
    /** MC's pristine scroll callback, captured at install time. Re-installed
     *  on menu close by {@link #swapScrollCallback} so vanilla hotbar / zoom
     *  scroll keeps working when our menu isn't open. */
    private static Object   prevScrollCallback;
    /** Cached {@code glfwSetScrollCallback(long, GLFWScrollCallbackI)} —
     *  flipped between {@link #ourScrollCallback} and {@link #prevScrollCallback}
     *  by {@link #swapScrollCallback} on menu open/close. */
    private static Method   glfwSetScrollCallbackMethod;
    /** True iff OUR callback is currently the active GLFW scroll handler. */
    private static boolean  ourScrollInstalled;
    /** Edge-detector cache for the menu-open ↔ scroll-swap mapping. */
    private static boolean  lastMenuOpenForScroll;
    /** Counter for diagnostic per-event scroll logging. We log the first
     *  20 scroll events verbatim so we can see exactly what GLFW is sending
     *  (sign, magnitude, frequency) — then go quiet to avoid log spam. */
    private static int      scrollEventCount;
    private static long     windowHandle = -1L;
    /** Latest cursor position in scaled GUI coordinates (where the menu draws). */
    private static int      mouseX, mouseY;
    /** Frozen yaw/pitch while the menu is open — snaps the player back so the
     *  camera doesn't rotate when the cursor moves. Float.MIN_VALUE means
     *  "not currently captured" (menu just opened or is closed). */
    private static float    frozenYaw   = Float.MIN_VALUE;
    private static float    frozenPitch = Float.MIN_VALUE;
    /** Rising-edge detector for left-click — true exactly on the frame
     *  the user clicks the menu. */
    private static boolean  leftClickEdge;
    private static boolean  prevLeftDown;
    private static boolean  rightClickEdge;
    private static boolean  prevRightDown;
    /** One-shot diagnostic flag for the cursor-position reader. */
    private static boolean  cursorReadLogged;
    /** Previous-tick state for edge-detection. */
    private static final boolean[] prevKey = new boolean[350];

    // Key codes (GLFW constants; inlined to avoid LWJGL dep)
    private static final int KEY_RIGHT_SHIFT = 344;
    private static final int KEY_ENTER       = 257;
    private static final int KEY_UP          = 265;
    private static final int KEY_DOWN        = 264;
    private static final int KEY_PAGE_UP     = 266;
    private static final int KEY_PAGE_DOWN   = 267;
    private static final int KEY_HOME        = 268;
    private static final int KEY_END         = 269;
    private static final int KEY_R           = 82;    // refresh mod list
    private static final int KEY_LEFT        = 263;   // grid column nav
    private static final int KEY_RIGHT       = 262;   // grid column nav

    // ---- explored-chunks cache (persistent, keyed by server+dimension) ----
    // Map<"server@dim", Map<chunkKey, argbColor>>. The color is sampled once
    // when the chunk is first explored and persisted alongside the key.
    private static final Path CHUNKS_FILE = Paths.get("config", "shadowclient-chunks.bin");
    private static final int  CHUNKS_MAGIC_V1 = 0x53574348;   // "SWCH" — legacy, no colors
    private static final int  CHUNKS_MAGIC_V2 = 0x53574332;   // "SWC2" — chunk→color map
    private static final int  FALLBACK_COLOR  = 0xFF6A3520;   // brownish for un-sampled chunks
    private static final Map<String, Map<Long, Integer>> EXPLORED =
        Collections.synchronizedMap(new HashMap<>());
    private static long lastChunksSaveMs = 0;

    // Reflection cache for block-color sampling (resolved on first use)
    private static volatile boolean blockSamplingReady;
    private static volatile boolean blockSamplingTried;
    private static Object  heightmapMotionBlocking;
    private static Method  getTopYMethod;           // world.getTopY(Heightmap.Type, x, z)
    private static Method  getBlockStateMethod;     // world.getBlockState(BlockPos)
    private static Method  getMapColorMethod;       // state.getMapColor(BlockView, BlockPos)
    private static Field   mapColorIntField;        // MapColor.color (int)
    private static java.lang.reflect.Constructor<?> blockPosCtor;   // BlockPos(int, int, int)

    /** Identify the current world uniquely across dimensions + servers. */
    private static String currentMapKey() {
        String server = "unknown";
        String dim    = "overworld";
        try {
            // Server address, if we're on a multiplayer server.
            Object srv = tryInvoke(mc, "method_1558", "getCurrentServerEntry");
            if (srv != null) {
                Object addr = tryInvoke(srv, "method_2994", "getAddress");
                if (addr != null) server = String.valueOf(addr);
            } else {
                Object integrated = tryInvoke(mc, "method_1576", "getServer");
                if (integrated != null) server = "singleplayer";
            }
            // Dimension identifier: world.getRegistryKey().getValue()
            Object world = worldField != null ? worldField.get(mc) : null;
            if (world != null) {
                Object key = tryInvoke(world, "method_27983", "getRegistryKey", "dimension");
                if (key != null) {
                    Object id = tryInvoke(key, "method_29177", "getValue", "location");
                    if (id != null) {
                        String s = String.valueOf(id);
                        int colon = s.indexOf(':');
                        dim = colon >= 0 ? s.substring(colon + 1) : s;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return server + "@" + dim;
    }

    private static Map<Long, Integer> currentChunkMap() {
        return EXPLORED.computeIfAbsent(currentMapKey(),
            k -> Collections.synchronizedMap(new HashMap<>()));
    }

    /** Lazy-resolve every reflection handle we need to sample a block's map color. */
    private static void ensureBlockSampling(Object world) {
        if (blockSamplingReady || blockSamplingTried) return;
        synchronized (ShadowHud.class) {
            if (blockSamplingReady || blockSamplingTried) return;
            blockSamplingTried = true;
            try {
                // Heightmap.Type.MOTION_BLOCKING is a public enum constant
                Class<?> hmType = Class.forName("net.minecraft.class_2902$class_2903");
                for (Field f : hmType.getFields()) {
                    if (f.getName().equals("field_13197") || f.getName().equals("MOTION_BLOCKING")) {
                        heightmapMotionBlocking = f.get(null);
                        break;
                    }
                }
                if (heightmapMotionBlocking == null) {
                    // pick any enum constant as fallback
                    Object[] vals = hmType.getEnumConstants();
                    if (vals != null && vals.length > 0) heightmapMotionBlocking = vals[0];
                }

                // world.getTopY(HeightmapType, x, z) → int
                for (Method m : world.getClass().getMethods()) {
                    if (!m.getName().equals("method_8624")) continue;
                    if (m.getParameterCount() != 3) continue;
                    if (m.getReturnType() != int.class) continue;
                    getTopYMethod = m;
                    break;
                }

                // BlockPos(int, int, int) ctor
                Class<?> blockPosClass = Class.forName("net.minecraft.class_2338");
                blockPosCtor = blockPosClass.getConstructor(int.class, int.class, int.class);

                // world.getBlockState(BlockPos) → BlockState
                for (Method m : world.getClass().getMethods()) {
                    if (!m.getName().equals("method_8320")) continue;
                    if (m.getParameterCount() != 1) continue;
                    getBlockStateMethod = m;
                    break;
                }

                blockSamplingReady = (getTopYMethod != null && getBlockStateMethod != null
                    && blockPosCtor != null && heightmapMotionBlocking != null);
                System.out.println("[ShadowHud][Map] block sampling: "
                    + (blockSamplingReady ? "READY" : "unavailable"));
            } catch (Throwable t) {
                System.err.println("[ShadowHud][Map] block sampling init: " + t);
            }
        }
    }

    /** Sample a representative color for (chunkX, chunkZ) from the world. */
    private static int sampleChunkColor(Object world, int chunkX, int chunkZ) {
        if (!blockSamplingReady) return FALLBACK_COLOR;
        try {
            // Average four block samples inside the chunk (quartiles)
            int[] offsets = {4, 11};           // sample at (4,4), (4,11), (11,4), (11,11)
            long rSum = 0, gSum = 0, bSum = 0;
            int count = 0;
            for (int ox : offsets) for (int oz : offsets) {
                int bx = chunkX * 16 + ox;
                int bz = chunkZ * 16 + oz;
                int topY = (int) getTopYMethod.invoke(world, heightmapMotionBlocking, bx, bz) - 1;
                if (topY < -64 || topY > 319) continue;
                Object pos = blockPosCtor.newInstance(bx, topY, bz);
                Object state = getBlockStateMethod.invoke(world, pos);
                if (state == null) continue;
                if (getMapColorMethod == null) {
                    // Find state.getMapColor(BlockView, BlockPos) once we have a state
                    for (Method m : state.getClass().getMethods()) {
                        if (!m.getName().equals("method_26205")) continue;
                        if (m.getParameterCount() != 2) continue;
                        getMapColorMethod = m;
                        break;
                    }
                    if (getMapColorMethod == null) return FALLBACK_COLOR;
                }
                Object mapColor = getMapColorMethod.invoke(state, world, pos);
                if (mapColor == null) continue;
                if (mapColorIntField == null) {
                    for (Field f : mapColor.getClass().getFields()) {
                        if (f.getType() != int.class) continue;
                        String n = f.getName();
                        if (n.equals("field_16011") || n.equals("color")) {
                            mapColorIntField = f;
                            break;
                        }
                    }
                    if (mapColorIntField == null) {
                        // fallback: any int field
                        for (Field f : mapColor.getClass().getFields()) {
                            if (f.getType() == int.class) { mapColorIntField = f; break; }
                        }
                    }
                    if (mapColorIntField == null) return FALLBACK_COLOR;
                }
                int rgb = mapColorIntField.getInt(mapColor);
                if (rgb == 0) continue;   // MapColor.NONE — skip instead of polluting the average
                rSum += (rgb >> 16) & 0xFF;
                gSum += (rgb >> 8)  & 0xFF;
                bSum += (rgb)        & 0xFF;
                count++;
            }
            if (count == 0) return FALLBACK_COLOR;
            int r = (int)(rSum / count), g = (int)(gSum / count), b = (int)(bSum / count);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        } catch (Throwable t) {
            return FALLBACK_COLOR;
        }
    }

    /** Pack (chunkX, chunkZ) → long. Survives negative coords via sign-truncation. */
    private static long chunkKey(int cx, int cz) {
        return ((long) cx & 0xFFFFFFFFL) | ((long) cz << 32);
    }
    private static int chunkKeyX(long k) { return (int) k; }
    private static int chunkKeyZ(long k) { return (int) (k >> 32); }

    // ---- mod-list state (scrollable section in the Lunar menu) ------------
    static class ModEntry {
        final String base;     // filename without the `.jar` / `.jar.disabled` suffix
        boolean enabled;
        ModEntry(String b, boolean e) { base = b; enabled = e; }
    }
    private static final List<ModEntry> MODS = new ArrayList<>();
    private static int modSelected = 0;   // cursor position (includes modules at top)
    private static int modScrollTop = 0;     // first row visible in mod list

    // ---- New-modules state -------------------------------------------------
    /** Rolling buffer of recent ping samples for NetGraph. */
    private static final java.util.ArrayDeque<Integer> pingHistory = new java.util.ArrayDeque<>();
    private static long lastPingSampleMs = 0L;
    /** TimerHud start timestamp (System.currentTimeMillis()), 0 = idle. */
    private static long timerStartMs = 0L;
    private static long timerAccumulatedMs = 0L;
    /** Last hotbar slot we saw — used by HotbarFade to detect activity. */
    private static int  lastSeenSlot = -1;
    private static long lastHotbarChangeMs = 0L;
    /** Idle threshold for HotbarFade — after this many ms of no slot change
     *  the hotbar fades out completely (Lunar default is ~3 seconds). */
    private static final long HOTBAR_FADE_IDLE_MS = 3000L;

    /** Called from {@code HotbarFadeMixin} once per hotbar render, on the
     *  render thread. Returns true when vanilla hotbar drawing should be
     *  cancelled. False when HotbarFade is off or the user just switched
     *  slots, so vanilla draws normally. */
    public static boolean shouldHideHotbar() {
        if (!modOn("HotbarFade", false)) return false;
        long sinceMs = System.currentTimeMillis() - lastHotbarChangeMs;
        return sinceMs >= cfgHotbarFadeDelay * 1000L;
    }

    /** Called from SwordVisualMixin — scale value as a 0..N float. */
    public static float heldItemScale() {
        int v = Math.max(50, Math.min(250, cfgSwordScale));
        return v / 100f;
    }

    /** Called from GlintHideMixin — true when GlintTune wants glint OFF. */
    public static boolean shouldHideGlint() {
        return MODULES.getOrDefault("GlintTune", false) && cfgGlintStrength == 0;
    }

    /** State trackers so we don't keep regenerating the glint texture on
     *  every frame. */
    private static int     glintLastColor    = -1;
    private static int     glintLastStrength = -1;
    private static boolean glintCurrentlyCustom = false;
    private static boolean glintBootstrapped = false;

    /** Per-frame check from renderHud. Lazy-applies custom glint when the
     *  config changes, restores vanilla when GlintTune turns off. */
    public static void tickGlintCustomization() {
        boolean want = MODULES.getOrDefault("GlintTune", false);
        if (want) {
            // Re-apply if config changed since last apply
            if (cfgGlintColor != glintLastColor || cfgGlintStrength != glintLastStrength) {
                if (applyGlintTexture(true)) {
                    glintLastColor    = cfgGlintColor;
                    glintLastStrength = cfgGlintStrength;
                    glintCurrentlyCustom = true;
                }
            }
        } else if (glintCurrentlyCustom) {
            // Restore vanilla glint
            if (applyGlintTexture(false)) {
                glintCurrentlyCustom = false;
                glintLastColor    = -1;
                glintLastStrength = -1;
            }
        }
        glintBootstrapped = true;
    }

    /** Read the vanilla glint PNG, optionally apply tint+strength, register
     *  the result in TextureManager at the original glint identifier.
     *  Returns true on success. */
    private static boolean applyGlintTexture(boolean withTint) {
        if (mc == null) return false;
        try {
            Object rm = tryInvoke(mc, "method_1478", "getResourceManager");
            if (rm == null) return false;
            Class<?> idCls = Class.forName("net.minecraft.class_2960");
            Method idOf = null;
            for (Method m : idCls.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 2) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                if (m.getParameterTypes()[1] != String.class) continue;
                if (m.getName().equals("method_60655") || m.getName().equals("of")) {
                    idOf = m; break;
                }
            }
            if (idOf == null) return false;
            Object glintId = idOf.invoke(null, "minecraft",
                                          "textures/misc/enchanted_glint_item.png");
            // ResourceManager.getResource(Identifier) → Optional<Resource>
            Object opt = null;
            for (Method m : rm.getClass().getMethods()) {
                if (!m.getName().equals("method_14486")
                    && !m.getName().equals("getResource")) continue;
                if (m.getParameterCount() != 1) continue;
                opt = m.invoke(rm, glintId);
                break;
            }
            if (!(opt instanceof java.util.Optional)) return false;
            java.util.Optional<?> resourceOpt = (java.util.Optional<?>) opt;
            if (!resourceOpt.isPresent()) return false;
            Object resource = resourceOpt.get();
            java.io.InputStream is = null;
            for (String n : new String[]{"method_14482", "getInputStream", "open"}) {
                try {
                    Method m = resource.getClass().getMethod(n);
                    Object r = m.invoke(resource);
                    if (r instanceof java.io.InputStream) {
                        is = (java.io.InputStream) r;
                        break;
                    }
                } catch (Throwable ignored) {}
            }
            if (is == null) return false;
            // Decode PNG → NativeImage
            Class<?> niCls = Class.forName("net.minecraft.class_1011");
            Method readM = niCls.getMethod("method_4309", java.io.InputStream.class);
            Object nativeImg = readM.invoke(null, is);
            try { is.close(); } catch (Throwable ignored) {}
            if (nativeImg == null) return false;

            if (withTint) {
                final int rTint = (cfgGlintColor >> 16) & 0xFF;
                final int gTint = (cfgGlintColor >>  8) & 0xFF;
                final int bTint =  cfgGlintColor        & 0xFF;
                final float strength = Math.max(0, Math.min(200, cfgGlintStrength)) / 100f;
                java.util.function.IntUnaryOperator op = packed -> {
                    // NativeImage stores ABGR (alpha=high)
                    int a = (packed >>> 24) & 0xFF;
                    int b = (packed >>> 16) & 0xFF;
                    int g = (packed >>>  8) & 0xFF;
                    int r =  packed         & 0xFF;
                    // Use brightness as the strength of the tint
                    int lum = (r + g + b) / 3;
                    int newR = Math.min(255, (rTint * lum) / 255);
                    int newG = Math.min(255, (gTint * lum) / 255);
                    int newB = Math.min(255, (bTint * lum) / 255);
                    int newA = Math.min(255, Math.round(a * strength));
                    return (newA << 24) | (newB << 16) | (newG << 8) | newR;
                };
                Method applyOp = niCls.getMethod("method_48462",
                                                  java.util.function.IntUnaryOperator.class);
                applyOp.invoke(nativeImg, op);
            }

            // Build NativeImageBackedTexture(Supplier<String>, NativeImage)
            Class<?> nibtCls = Class.forName("net.minecraft.class_1043");
            java.util.function.Supplier<String> nameSupplier = () -> "shadowclient_glint";
            Object texture = nibtCls
                    .getConstructor(java.util.function.Supplier.class, niCls)
                    .newInstance(nameSupplier, nativeImg);

            // Register: TextureManager.method_4616(Identifier, AbstractTexture)
            Object texMgr = tryInvoke(mc, "method_1531", "getTextureManager");
            if (texMgr == null) return false;
            Method regM = null;
            for (Method m : texMgr.getClass().getMethods()) {
                if (!m.getName().equals("method_4616")) continue;
                if (m.getParameterCount() != 2) continue;
                regM = m;
                break;
            }
            if (regM == null) return false;
            regM.invoke(texMgr, glintId, texture);
            System.out.println("[ShadowHud][GlintTune] glint "
                + (withTint ? "tinted" : "restored")
                + " color=" + String.format("#%06X", cfgGlintColor & 0xFFFFFF)
                + " strength=" + cfgGlintStrength);
            return true;
        } catch (Throwable t) {
            System.err.println("[ShadowHud][GlintTune] apply failed: " + t);
            return false;
        }
    }

    /** Called from SwordVisualMixin — should we scale this stack's render? */
    public static boolean shouldScaleHeldItem(Object stack) {
        if (stack == null) return false;
        if (!MODULES.getOrDefault("SwordVisuals", false)) return false;
        if (cfgSwordScale == 100) return false;
        if (cfgSwordScaleAllItems) return true;
        try {
            Object empty = tryInvoke(stack, "method_7960", "isEmpty");
            if (Boolean.TRUE.equals(empty)) return false;
            String id = getItemId(stack);
            // Match swords, tridents, axes (axes are common melee) — heuristic
            return id.contains("_sword") || id.endsWith(":trident") || id.endsWith(":mace");
        } catch (Throwable ignored) {
            return false;
        }
    }
    /** Debounce so AutoGG doesn't double-fire on rapid death messages. */
    private static long lastAutoGgMs = 0L;
    /** AutoTool state — last block id we acted on + last swap time. Skip
     *  re-acting on the same block within a short window so the user can
     *  manually override the tool choice (we wait until they LOOK AT a
     *  different block, then re-evaluate). */
    private static String autoToolLastBlock = "";
    private static long   autoToolLastSwapMs = 0L;
    private static int    autoToolPrevSlot   = -1;     // slot we restore TO (the slot user was on before our swap)
    private static long   autoToolPrevSetMs  = 0L;
    /** AutoTool throttle — minimum ms between two swap actions. 150ms means
     *  swaps happen at most ~6 times per second, plenty fast for mining. */
    private static final long AUTOTOOL_DEBOUNCE_MS = 150L;

    // ---- AutoEat / PearlCool / CombatTime / AntiAFK state ------------------
    /** AutoEat: hunger level below this triggers auto-eat. 6 = 3 drumsticks
     *  visible — leaves enough hunger to eat without immediately re-firing. */
    private static final int AUTOEAT_HUNGER_THRESHOLD = 7;
    /** True while we're actively holding the use key for auto-eat. Reset
     *  when hunger fills back up or when no food is found. */
    private static boolean autoEatHolding = false;
    /** Slot to restore to once eating finishes — captured before the food
     *  swap. -1 = no swap done. */
    private static int autoEatSavedSlot = -1;
    /** When we started the current eat — used to release after ~1.7s
     *  (vanilla food eat duration is 32 ticks = 1.6s + small buffer). */
    private static long autoEatStartedMs = 0L;
    /** PearlCool: timestamp of last detected pearl throw. Vanilla cooldown
     *  is 1 second (20 ticks). 0 = no pearl ever thrown this session. */
    private static long pearlCoolLastThrowMs = 0L;
    private static boolean pearlPrevHolding = false;   // pearl in hand last frame
    /** CombatTime: when the player last took damage. Combat-tag windows
     *  are typically 10-20s; we display the rolling timer. */
    private static long combatLastDamageMs = 0L;
    /** Last seen health value — falling delta = damage taken event. */
    private static float combatLastHealth = -1f;
    /** AntiAFK: last time we sent a tiny "I'm here" input. ~25s interval
     *  is below most servers' 30s AFK kick window. */
    private static long antiAfkLastMs = 0L;
    private static final long ANTIAFK_INTERVAL_MS = 25_000L;
    /** Toggle which AntiAFK input we send next (alternate sneak press
     *  to avoid getting stuck in one state). */
    private static boolean antiAfkLastWasSneak = false;
    /** StepCounter: accumulated horizontal distance (XZ blocks) walked this
     *  session. Tracked per-frame as the magnitude of (curX-prevX, curZ-prevZ).
     *  prev coordinates start at NaN so the first frame doesn't count a huge
     *  delta if the player just teleported. */
    private static double stepCounterDist = 0.0;
    private static double stepCounterPrevX = Double.NaN;
    private static double stepCounterPrevZ = Double.NaN;
    /** LowHpAlert: last time we beeped. 5s debounce so the alert doesn't
     *  spam every frame the player's HP stays below threshold. */
    private static long lowHpAlertLastMs = 0L;
    private static final long LOW_HP_DEBOUNCE_MS = 5000L;
    private static final float LOW_HP_THRESHOLD = 6.0f;   // 3 hearts
    /** ToolBreak: last alert timestamp + last seen damage so we don't
     *  re-fire every frame the tool stays in the danger zone. */
    private static long toolBreakLastAlertMs = 0L;
    /** HitMarker: timestamp of the last attack edge so the X fades after firing. */
    private static long hitMarkerAtMs = 0L;
    private static boolean hitMarkerPrevLmb = false;
    /** JumpCount: track on-ground transitions to count jumps. */
    private static int  jumpCountSession = 0;
    private static boolean jumpCountPrevOnGround = true;
    /** WelcomeMsg: only fire once per server-join session. */
    private static String welcomeMsgLastServer = "";
    /** AutoTotem: throttle the inventory swap so we only act once per
     *  ~250ms — protects against packet spam if both hands wind up empty
     *  during heavy combat. */
    private static long autoTotemLastSwapMs = 0L;
    private static final long AUTO_TOTEM_COOLDOWN_MS = 250L;
    /** Per-target timestamp of when their shield should come back online,
     *  populated by EnemyShield each time you axe-hit a blocking opponent.
     *  Cleaned up lazily — entries past their expiry are simply ignored. */
    private static final java.util.Map<java.util.UUID, Long>
        enemyShieldDisableUntilMs = new java.util.HashMap<>();
    /** Rising-edge tracker for our LMB so EnemyShield only fires once per
     *  click, not every frame the button is held. */
    private static boolean prevLmbForShield = false;
    /** SpearSwap: rising-edge tracker for "now holding spear" so we only
     *  schedule the attribute-swap once per slot-transition into the
     *  spear (rather than once per click). The two timestamps schedule
     *  the forward and back slot-update packets, with the swap targets.
     *  {@code lastNonSpearSlot} is updated every frame the user is NOT
     *  holding a spear — when they later move TO a spear, we swap back
     *  to whatever they had previously instead of an arbitrary neighbour.
     *  {@code -1} means "never seen them on a non-spear slot this
     *  session" — fall back to (curSlot + 1) % 9. */
    private static boolean prevHoldingSpear      = false;
    private static long    spearSwapForwardAtMs  = 0L;
    private static long    spearSwapBackAtMs     = 0L;
    private static int     spearSwapForwardSlot  = -1;
    private static int     spearSwapBackSlot     = -1;
    private static int     lastNonSpearSlot      = -1;
    /** Cooldown — block re-triggering for this many ms after a swap fires.
     *  Prevents the back-swap from cascading into another forward-swap when
     *  the client lands back on the spear (rising edge of holdingSpear). */
    private static long    spearSwapCooldownUntilMs = 0L;
    /** Diagnostic — log each unique held-item class once per session so we
     *  can see whether the spear is being detected. Helps debug servers
     *  with non-vanilla spear items where isSpearStack() may not match. */
    private static final java.util.Set<String> spearSwapDiagSeen = new java.util.HashSet<>();
    /** Cached UpdateSelectedSlotC2SPacket class + sendPacket Method, resolved
     *  on first SpearSwap fire and reused. {@code class_2868} is the
     *  intermediary; constructor takes a single int slot. */
    private static Class<?> slotPacketClass;
    private static Method   slotSendPacketMethod;
    private static boolean  slotPacketResolveTried;
    /** MC 1.21.x sets a 100-tick (5 s) shield cooldown on a successful
     *  axe-on-blocking hit. Sprint-attacks always disable; non-sprint hits
     *  have a 25 % base chance, so this timer is best-effort. Worth showing
     *  anyway — false-positive cost is "you swing and bounce off", same as
     *  not showing it would have produced. */
    private static final long ENEMY_SHIELD_DISABLE_MS = 5000L;
    private static int moduleScrollTop = 0;  // first card visible in HUD-modules section
    /** Category filter — empty string = "All". Click a tab to filter the
     *  module grid to only show that category's cards. */
    private static String menuCategoryFilter = "";
    /** When true, the module grid hides cards that are off — quick way to
     *  see exactly what you have enabled across 80+ modules. */
    private static boolean menuEnabledOnly = false;
    /** Grid column count: 1, 2, or 3. Cycles via the density button. */
    /** Default 3-column grid — denser by default since a 2-col layout left
     *  half the panel empty when the user just wants to scan toggles. */
    private static int menuGridCols = 3;
    /** Sort mode: 0=category (default insertion order), 1=alphabetical,
     *  2=enabled first then alphabetical. */
    private static int menuSortMode = 0;
    private static final String[] MENU_SORT_LABELS = {"Default", "A-Z", "Enabled First"};
    /** In-menu type-to-search filter. Empty = no filter. */
    private static final StringBuilder menuSearchBuf = new StringBuilder();
    /** When false, letter keys are NOT captured — vanilla menu nav works
     *  without any side-effects. Toggle via the 🔍 button on the tab bar. */
    private static boolean menuSearchActive = false;
    private static final String[] MENU_CATEGORIES = {
        "All", "Display", "World", "Inventory", "Combat", "Server", "Utility"
    };
    private static boolean modsLoaded = false;

    // ---------------------------------------------------------------------

    @Override
    public void onInitializeClient() {
        System.out.println("[ShadowHud] onInitializeClient — mod loaded");
        System.out.println("[ShadowHud]   _____ _               _                ");
        System.out.println("[ShadowHud]  / ____| |             | |               ");
        System.out.println("[ShadowHud] | (___ | |__   __ _  __| | _____      __ ");
        System.out.println("[ShadowHud]  \\___ \\| '_ \\ / _` |/ _` |/ _ \\ \\ /\\ / / ");
        System.out.println("[ShadowHud]  ____) | | | | (_| | (_| | (_) \\ V  V /  ");
        System.out.println("[ShadowHud] |_____/|_| |_|\\__,_|\\__,_|\\___/ \\_/\\_/   ");
        System.out.println("[ShadowHud]                                          ");
        System.out.println("[ShadowHud]            Shadow Client — made by Edison");
        loadConfig();
        loadExploredChunks();
        registerChatFilter();
        HudRenderCallback.EVENT.register((dc, tc) -> {
            try {
                ensureInit();
                pollInput();
                // Catch every menuOpen flip in one place (RShift / Esc / etc.).
                if (menuOpen != lastMenuOpenForScroll) {
                    lastMenuOpenForScroll = menuOpen;
                    swapScrollCallback(menuOpen);
                }
                trackCurrentChunk();
                // Run renderHud and renderMenu in separate try-blocks so a
                // throw in one doesn't skip the other. renderMenu drains
                // pendingScrollDelta — if we let renderHud's exception
                // bubble past renderMenu we'd silently lose scroll events
                // on any frame where ANY HUD module crashed.
                try { renderHud(dc); } catch (Throwable t) { logOnce("renderHud", t); }
                if (menuOpen) {
                    try { renderMenu(dc); } catch (Throwable t) { logOnce("renderMenu", t); }
                }
            } catch (Throwable t) { logOnce("HudRenderCallback", t); }
        });
        // Lunar-style 3D cosmetics — Wings/AngelWings/Cape draw real mesh
        // geometry via the world renderer's vertex consumers, not particles.
        // AFTER_ENTITIES gives us the same MatrixStack + VertexConsumerProvider
        // MC just used to render every entity, so our line geometry composes
        // correctly with the rest of the scene.
        try {
            WorldRenderEvents.AFTER_ENTITIES.register(ctx -> {
                if (!shadowhud$afterEntitiesFiredOnce) {
                    shadowhud$afterEntitiesFiredOnce = true;
                    System.out.println("[ShadowHud][Cosmetic] WorldRenderEvents.AFTER_ENTITIES first fire — "
                        + "ctx=" + (ctx != null ? ctx.getClass().getName() : "null"));
                }
                try { renderCosmetics3D(ctx); }
                catch (Throwable t) { logOnce("Cosmetics3D", t); }
            });
            System.out.println("[ShadowHud][Cosmetic] WorldRenderEvents.AFTER_ENTITIES registered OK");
        } catch (Throwable t) {
            System.err.println("[ShadowHud][Cosmetic] FAILED to register AFTER_ENTITIES: " + t);
            t.printStackTrace();
        }
        // Register a ScreenEvents.AFTER_RENDER hook on every screen init.
        // When the user opens an enchanting table, our hook draws the
        // EnchantPreview panel ON TOP of the screen (after screen.render()
        // finishes). HudRenderCallback alone draws BEHIND the screen
        // background overlay so the panel appears dim/invisible — this hook
        // guarantees visibility.
        registerEnchantPreviewScreenHook();
    }

    /** Register a fabric-screen-api-v1 hook so EnchantPreview renders ON TOP
     *  of the EnchantmentScreen. Done by reflection so we don't pull
     *  fabric-screen-api-v1 into the compile classpath. */
    private static void registerEnchantPreviewScreenHook() {
        try {
            Class<?> seCls = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents");
            Class<?> afterInitIface = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterInit");
            Class<?> afterRenderIface = Class.forName("net.fabricmc.fabric.api.client.screen.v1.ScreenEvents$AfterRender");
            Class<?> screenCls = Class.forName("net.minecraft.class_437");
            Object afterInitEvent = seCls.getField("AFTER_INIT").get(null);
            Method afterRenderRegister = seCls.getMethod("afterRender", screenCls);

            // AFTER_INIT proxy — fires every time any screen initializes.
            Object afterInitProxy = java.lang.reflect.Proxy.newProxyInstance(
                ShadowHud.class.getClassLoader(),
                new Class<?>[]{afterInitIface},
                (p, m, args) -> {
                    if (args == null || args.length < 2) return null;
                    Object screen = args[1];
                    try {
                        Class<?> enchScreenCls = Class.forName("net.minecraft.class_486");
                        if (!enchScreenCls.isInstance(screen)) return null;
                    } catch (Throwable ignored) { return null; }
                    // Got an EnchantmentScreen — register an AFTER_RENDER on it.
                    try {
                        Object renderEvent = afterRenderRegister.invoke(null, screen);
                        Object renderProxy = java.lang.reflect.Proxy.newProxyInstance(
                            ShadowHud.class.getClassLoader(),
                            new Class<?>[]{afterRenderIface},
                            (p2, m2, args2) -> {
                                // afterRender(Screen, DrawContext, int mouseX, int mouseY, float tickDelta)
                                if (args2 == null || args2.length < 4) return null;
                                Object scrn = args2[0];
                                Object dc   = args2[1];
                                try {
                                    if (modOn("EnchantPreview", true)) {
                                        Object plr = playerField != null ? playerField.get(mc) : null;
                                        Object fnt = fontField   != null ? fontField.get(mc)   : null;
                                        if (plr != null && fnt != null && dc != null) {
                                            renderEnchantPreviewOnScreen(scrn, dc, fnt, plr);
                                        }
                                    }
                                } catch (Throwable t) {
                                    if (!enchPreviewLogged) {
                                        System.err.println("[ShadowHud][EnchantPreview] screen-hook render threw: " + t);
                                    }
                                }
                                return null;
                            }
                        );
                        Method reg = renderEvent.getClass().getMethod("register", afterRenderIface);
                        reg.setAccessible(true);
                        reg.invoke(renderEvent, renderProxy);
                        if (!enchPreviewScreenHookLogged) {
                            enchPreviewScreenHookLogged = true;
                            System.out.println("[ShadowHud][EnchantPreview] hooked AFTER_RENDER on EnchantmentScreen");
                        }
                    } catch (Throwable t) {
                        System.err.println("[ShadowHud][EnchantPreview] failed to register afterRender: " + t);
                    }
                    return null;
                }
            );
            // Register on AFTER_INIT
            Method reg = null;
            for (Method mm : afterInitEvent.getClass().getMethods()) {
                if ("register".equals(mm.getName()) && mm.getParameterCount() == 1) {
                    reg = mm; break;
                }
            }
            if (reg == null) {
                System.err.println("[ShadowHud][EnchantPreview] no register method on AFTER_INIT event");
                return;
            }
            reg.setAccessible(true);
            reg.invoke(afterInitEvent, afterInitProxy);
            System.out.println("[ShadowHud][EnchantPreview] AFTER_INIT registered (will hook EnchantmentScreens on open)");
        } catch (Throwable t) {
            System.err.println("[ShadowHud][EnchantPreview] could not register screen events: " + t);
        }
    }
    private static boolean enchPreviewScreenHookLogged = false;
    /** Set to true on the first AFTER_ENTITIES fire so we log exactly once
     *  when the cosmetic event pipeline is alive. Critical diagnostic — if
     *  this never logs, the fabric-rendering-v1 hook isn't wired up
     *  (wrong API version, classloader split, etc.) and no cosmetics will
     *  ever appear regardless of what toggles the user flips. */
    private static volatile boolean shadowhud$afterEntitiesFiredOnce = false;

    // ---- Chat filter -----------------------------------------------------
    private static final Path CHAT_FILTER_FILE =
        Paths.get("config", "shadowclient-chat.txt");
    private static java.util.List<java.util.regex.Pattern> chatFilterPatterns =
        new java.util.ArrayList<>();
    private static long chatFilterMtime = -1;

    // ---- Social-feature state ---------------------------------------------
    private static final Path FRIENDS_FILE = Paths.get("config", "shadowclient-friends.txt");
    private static final Path MUTES_FILE   = Paths.get("config", "shadowclient-mutes.txt");
    private static final Path PRESETS_FILE = Paths.get("config", "shadowclient-server-presets.txt");
    /** Lowercased name set, hot-reloaded when the file mtime changes. */
    private static java.util.Set<String> friendsSet = new java.util.HashSet<>();
    private static long friendsMtime = -1L;
    private static java.util.Set<String> mutesSet = new java.util.HashSet<>();
    private static long mutesMtime = -1L;
    /** Map of (server-IP-substring → preset-name), e.g. "cosmosmc"→"PvP". */
    private static java.util.Map<String, String> serverPresets = new java.util.LinkedHashMap<>();
    private static long serverPresetsMtime = -1L;
    /** Cached last-applied server fingerprint so AutoPreset doesn't re-fire. */
    private static String lastAutoPresetServer = "";
    private static long   lastFriendNotifyMs = 0L;

    // ---- ServerRules: per-server mod allow-listing ------------------------
    private static final Path SERVER_RULES_FILE =
        Paths.get("config", "shadowclient-server-rules.txt");
    /** server-substring → list of module names this server forbids. */
    private static java.util.Map<String, java.util.List<String>> serverRules =
        new java.util.LinkedHashMap<>();
    private static long serverRulesMtime = -1L;
    /** Which server we last reacted to (so we don't re-fire while connected). */
    private static String currentRuleServer = "";
    /** Modules we toggled OFF because of the current server's rules — used to
     *  restore them when the user leaves the server. Order preserved so the
     *  restore message shows them in the same order as the disable message. */
    private static final java.util.LinkedHashSet<String> ruleDisabledModules =
        new java.util.LinkedHashSet<>();
    /** Servers the user has chosen to permanently ignore via the [I]gnore
     *  prompt option. Populated only at runtime; cleared on launch. */
    private static final java.util.HashSet<String> ruleIgnoredServers =
        new java.util.HashSet<>();
    /** Active prompt state. While {@code rulePromptActive} is true, the next
     *  Y/N/I keystroke handles the choice. Auto-times-out after 15 s. */
    private static boolean rulePromptActive = false;
    private static long    rulePromptShownMs = 0L;
    private static String  rulePromptServer = "";
    private static java.util.List<String> rulePromptModules = new java.util.ArrayList<>();

    /** Hook Fabric's chat-receive callback via reflection so we can drop
     *  unwanted lines. We register both ALLOW_GAME (system messages) and
     *  ALLOW_CHAT (player chat). The handler checks each message against
     *  user-defined regex patterns from `config/shadowclient-chat.txt`. */
    private static void registerChatFilter() {
        try {
            Class<?> events = Class.forName(
                "net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents");
            registerOneEvent(events, "ALLOW_GAME",
                "net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents$AllowGame");
            registerOneEvent(events, "ALLOW_CHAT",
                "net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents$AllowChat");
            // Seed the file with a friendly template so the user knows what
            // syntax to use the first time they open it.
            ensureChatFilterTemplate();
            ensureSocialTemplates();
            ensureServerRulesTemplate();
            System.out.println("[ShadowHud] chat filter registered → " + CHAT_FILTER_FILE);
            System.out.println("[ShadowHud] social-config seeded → "
                + FRIENDS_FILE + ", " + MUTES_FILE + ", " + PRESETS_FILE
                + ", " + SERVER_RULES_FILE);
        } catch (Throwable t) {
            System.err.println("[ShadowHud] chat filter setup failed: " + t);
        }
    }

    private static void registerOneEvent(Class<?> eventsClass, String fieldName,
                                         String interfaceName) throws Exception {
        Class<?> iface = Class.forName(interfaceName);
        Object event = eventsClass.getField(fieldName).get(null);
        // Fabric's Event<T>.register(T) erases T to Object at runtime, so we
        // can't `getMethod("register", iface)`. Find any single-arg method
        // named "register" on the event class.
        Method register = null;
        for (Method m : event.getClass().getMethods()) {
            if (!"register".equals(m.getName())) continue;
            if (m.getParameterCount() != 1) continue;
            register = m; break;
        }
        if (register == null) {
            throw new NoSuchMethodException(
                "no single-arg register on " + event.getClass().getName());
        }
        // ArrayBackedEvent (the runtime class behind Event<T>) lives in
        // net.fabricmc.fabric.impl.base.event — an internal package that
        // Fabric does NOT export. Even though `register` is `public`, JPMS
        // refuses cross-module reflective access. setAccessible(true) bypasses
        // that check (we have permissions because the loader gave us the
        // unrestricted ClassLoader).
        register.setAccessible(true);
        Object proxy = java.lang.reflect.Proxy.newProxyInstance(
            ShadowHud.class.getClassLoader(),
            new Class<?>[]{iface},
            (p, m, args) -> {
                if (args == null || args.length == 0) return Boolean.TRUE;
                String text = compToString(args[0]);
                if (modOn("AutoGG", false))      tryAutoGg(text);
                if (modOn("FriendNotify", false)) tryFriendNotify(text);
                if (modOn("MuteList", false) && shouldMute(text)) {
                    return Boolean.FALSE;
                }
                if (!modOn("ChatFilter", false)) return Boolean.TRUE;
                return shouldFilterChat(text) ? Boolean.FALSE : Boolean.TRUE;
            }
        );
        register.invoke(event, proxy);
    }

    private static void ensureChatFilterTemplate() {
        try {
            if (Files.exists(CHAT_FILTER_FILE)) return;
            Files.createDirectories(CHAT_FILTER_FILE.getParent());
            Files.write(CHAT_FILTER_FILE, (
                "# Shadow HUD chat filter\n"
                + "# One regex per line. Empty lines and # comments are ignored.\n"
                + "# Match is case-insensitive and uses .find() so substrings work.\n"
                + "# Edit this file at any time — it auto-reloads on save.\n"
                + "#\n"
                + "# Examples (uncomment to enable):\n"
                + "#   joined the game\n"
                + "#   left the game\n"
                + "#   ^.*advertis(e|ing).*$\n"
                + "#   \\bn[\\W_]*o[\\W_]*o[\\W_]*b\\b\n"
                + "#\n"
                ).getBytes());
        } catch (Throwable ignored) {}
    }

    /** Seed friends.txt / mutes.txt / server-presets.txt with friendly templates
     *  so first-time users see the format. */
    private static void ensureSocialTemplates() {
        try {
            Files.createDirectories(FRIENDS_FILE.getParent());
        } catch (Throwable ignored) {}
        try {
            if (!Files.exists(FRIENDS_FILE)) {
                Files.write(FRIENDS_FILE, (
                    "# Shadow HUD friends list\n"
                    + "# One Minecraft username per line (case-insensitive).\n"
                    + "# Friends get a ★ in the tab list and trigger a chat\n"
                    + "# notification when their name is mentioned (FriendNotify).\n"
                    + "# Empty lines and # comments are ignored.\n"
                    + "#\n"
                    + "# Examples (uncomment + replace with real names):\n"
                    + "#   Notch\n"
                    + "#   Dinnerbone\n"
                    + "#\n"
                    ).getBytes());
            }
        } catch (Throwable ignored) {}
        try {
            if (!Files.exists(MUTES_FILE)) {
                Files.write(MUTES_FILE, (
                    "# Shadow HUD mute list\n"
                    + "# One Minecraft username per line (case-insensitive).\n"
                    + "# Chat messages from / containing these names are dropped\n"
                    + "# when the MuteList module is enabled.\n"
                    + "#\n"
                    ).getBytes());
            }
        } catch (Throwable ignored) {}
        try {
            if (!Files.exists(PRESETS_FILE)) {
                Files.write(PRESETS_FILE, (
                    "# Shadow HUD server-aware presets\n"
                    + "# Format: <substring-or-regex> = <preset-name>\n"
                    + "# When you join a server whose IP/name contains the\n"
                    + "# substring (case-insensitive), the named preset is\n"
                    + "# auto-applied (only ONCE per server change). Available\n"
                    + "# presets: Defaults, PvP, SMP, Streamer, Minimal\n"
                    + "#\n"
                    + "# Examples (uncomment + adjust):\n"
                    + "# cosmosmc      = PvP\n"
                    + "# hypixel       = PvP\n"
                    + "# 2b2t          = SMP\n"
                    + "# play.minemen  = PvP\n"
                    + "#\n"
                    ).getBytes());
            }
        } catch (Throwable ignored) {}
    }

    /** Reload friends.txt / mutes.txt / server-presets.txt if their mtime
     *  has changed since last read. Cheap when nothing changes. */
    private static void reloadSocialFiles() {
        try {
            if (Files.exists(FRIENDS_FILE)) {
                long m = Files.getLastModifiedTime(FRIENDS_FILE).toMillis();
                if (m != friendsMtime) {
                    friendsMtime = m;
                    java.util.Set<String> next = new java.util.HashSet<>();
                    for (String line : Files.readAllLines(FRIENDS_FILE)) {
                        String t = line.trim();
                        if (t.isEmpty() || t.startsWith("#")) continue;
                        next.add(t.toLowerCase());
                    }
                    friendsSet = next;
                }
            }
        } catch (Throwable ignored) {}
        try {
            if (Files.exists(MUTES_FILE)) {
                long m = Files.getLastModifiedTime(MUTES_FILE).toMillis();
                if (m != mutesMtime) {
                    mutesMtime = m;
                    java.util.Set<String> next = new java.util.HashSet<>();
                    for (String line : Files.readAllLines(MUTES_FILE)) {
                        String t = line.trim();
                        if (t.isEmpty() || t.startsWith("#")) continue;
                        next.add(t.toLowerCase());
                    }
                    mutesSet = next;
                }
            }
        } catch (Throwable ignored) {}
        try {
            if (Files.exists(PRESETS_FILE)) {
                long m = Files.getLastModifiedTime(PRESETS_FILE).toMillis();
                if (m != serverPresetsMtime) {
                    serverPresetsMtime = m;
                    java.util.Map<String, String> next = new java.util.LinkedHashMap<>();
                    for (String line : Files.readAllLines(PRESETS_FILE)) {
                        String t = line.trim();
                        if (t.isEmpty() || t.startsWith("#")) continue;
                        int eq = t.indexOf('=');
                        if (eq <= 0) continue;
                        String key = t.substring(0, eq).trim().toLowerCase();
                        String val = t.substring(eq + 1).trim();
                        if (!key.isEmpty() && !val.isEmpty()) next.put(key, val);
                    }
                    serverPresets = next;
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Case-insensitive friend check. Reloads file on demand. */
    private static boolean isFriend(String name) {
        if (name == null || name.isEmpty()) return false;
        reloadSocialFiles();
        return friendsSet.contains(name.toLowerCase());
    }

    /** Returns true if any muted name appears in the chat text. Names use
     *  word-boundary heuristic: surrounded by non-letter chars. */
    private static boolean shouldMute(String text) {
        if (text == null || text.isEmpty()) return false;
        reloadSocialFiles();
        if (mutesSet.isEmpty()) return false;
        String lower = text.toLowerCase();
        for (String n : mutesSet) {
            int idx = lower.indexOf(n);
            if (idx < 0) continue;
            // Word boundary: char before & after must not be a letter/digit
            char before = idx == 0 ? ' ' : lower.charAt(idx - 1);
            int after  = idx + n.length();
            char ch    = after < lower.length() ? lower.charAt(after) : ' ';
            if (!Character.isLetterOrDigit(before)
                && !Character.isLetterOrDigit(ch)) return true;
        }
        return false;
    }

    /** When a friend's name appears in chat, surface a one-line system
     *  notification (debounced 5 s so spammy chat doesn't flood). */
    private static void tryFriendNotify(String text) {
        if (text == null || text.isEmpty()) return;
        reloadSocialFiles();
        if (friendsSet.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastFriendNotifyMs < 5_000L) return;
        String lower = text.toLowerCase();
        for (String n : friendsSet) {
            if (lower.contains(n)) {
                lastFriendNotifyMs = now;
                System.out.println("[ShadowHud][Friend] mention: " + text);
                // Push to MC's chat HUD too, if accessible
                try {
                    Object inGameHud = tryInvoke(mc, "method_1705", "inGameHud");
                    if (inGameHud == null) {
                        Field f = cachedField(mc.getClass(), "field_1705");
                        if (f != null) inGameHud = f.get(mc);
                    }
                    if (inGameHud != null) {
                        Object chatHud = tryInvoke(inGameHud, "method_1743", "getChatHud");
                        if (chatHud != null) {
                            Class<?> textCls = Class.forName("net.minecraft.class_2561");
                            // 1.21.11: Text.literal is method_30163 (verified
                            // in disassembly). Older versions used method_43470.
                            // findMethodByName works for ANY param count —
                            // cachedMethod only finds no-arg, which silently
                            // failed on these single-arg calls before.
                            Method literal = findMethodByName(textCls, "method_30163");
                            if (literal == null) literal = findMethodByName(textCls, "method_43470");
                            if (literal == null) literal = findMethodByName(textCls, "literal");
                            if (literal != null) {
                                Object msg = literal.invoke(null, "§e[Friend] §f" + n);
                                Method addMsg = findMethodByName(chatHud.getClass(), "method_1812");
                                if (addMsg == null) addMsg = findMethodByName(chatHud.getClass(), "addMessage");
                                if (addMsg != null) addMsg.invoke(chatHud, msg);
                            }
                        }
                    }
                } catch (Throwable ignored) {}
                break;
            }
        }
    }

    /** Hot-reload server-rules.txt if its mtime changed. */
    private static void reloadServerRules() {
        try {
            if (!Files.exists(SERVER_RULES_FILE)) return;
            long m = Files.getLastModifiedTime(SERVER_RULES_FILE).toMillis();
            if (m == serverRulesMtime) return;
            serverRulesMtime = m;
            java.util.Map<String, java.util.List<String>> next = new java.util.LinkedHashMap<>();
            for (String line : Files.readAllLines(SERVER_RULES_FILE)) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int eq = t.indexOf('=');
                if (eq <= 0) continue;
                String key = t.substring(0, eq).trim().toLowerCase();
                String val = t.substring(eq + 1).trim();
                if (key.isEmpty() || val.isEmpty()) continue;
                java.util.List<String> mods = new java.util.ArrayList<>();
                for (String mod : val.split("[,\\s]+")) {
                    String mm = mod.trim();
                    if (!mm.isEmpty()) mods.add(mm);
                }
                if (!mods.isEmpty()) next.put(key, mods);
            }
            serverRules = next;
            System.out.println("[ShadowHud][ServerRules] reloaded — "
                + serverRules.size() + " patterns");
        } catch (Throwable t) { logOnce("ServerRules-reload", t); }
    }

    /** Seed server-rules.txt with a friendly template covering common servers.
     *  Called from registerChatFilter alongside the other social templates. */
    private static void ensureServerRulesTemplate() {
        try {
            if (Files.exists(SERVER_RULES_FILE)) return;
            Files.createDirectories(SERVER_RULES_FILE.getParent());
            Files.write(SERVER_RULES_FILE, (
                "# Shadow HUD per-server mod restrictions\n"
                + "# Format: <server-substring> = <module-name>, <module-name>, ...\n"
                + "# When you join a server whose IP/name contains the substring\n"
                + "# (case-insensitive), Shadow Client offers to disable the listed\n"
                + "# modules. When you leave the server, they're restored automatically.\n"
                + "#\n"
                + "# Press Y to disable, N to keep enabled, I to ignore this server\n"
                + "# for the rest of the session. Auto-times-out (no action) after 15 s.\n"
                + "#\n"
                + "# Edit this file as you discover servers' rules. It hot-reloads on save.\n"
                + "# These defaults are best-effort guesses — verify each server's actual\n"
                + "# rules before relying on them.\n"
                + "#\n"
                + "# Server-name patterns (substring match, case-insensitive):\n"
                + "hypixel       = AutoSprint, Tracker, Reach, NearMob, FakePing\n"
                + "mineplex      = Tracker, Reach, FakePing\n"
                + "hivemc        = AutoSprint, Tracker, FakePing\n"
                + "cubecraft     = Tracker, Reach, FakePing\n"
                + "lifeboat      = Tracker, FakePing\n"
                + "minemen       = Tracker, FakePing, Reach\n"
                + "lunar.gg      = Tracker, FakePing\n"
                + "# 2b2t and most anarchy servers are mod-friendly — no entry = no rules.\n"
                ).getBytes());
        } catch (Throwable ignored) {}
    }

    /** Show an in-chat prompt asking the user whether to apply the rules
     *  for the current server. Two-line message + a third clickable line
     *  with the option keys. */
    private static void showServerRulesPrompt(String server, java.util.List<String> mods) {
        rulePromptActive = true;
        rulePromptShownMs = System.currentTimeMillis();
        rulePromptServer = server;
        rulePromptModules = new java.util.ArrayList<>(mods);
        injectChat("§7§l[Shadow] §6Server §f" + server
            + " §6restricts: §c" + String.join(", ", mods));
        injectChat("§7§l[Shadow] §aPress §fY§a to disable + auto-restore on leave"
            + ", §fN§a to keep, §fI§a to ignore this server");
    }

    /** Apply the user's choice (Y / N / I) to the active prompt. */
    private static void resolveServerRulesPrompt(char choice) {
        if (!rulePromptActive) return;
        rulePromptActive = false;
        long now = System.currentTimeMillis();
        switch (choice) {
            case 'Y': case 'y': {
                int n = 0;
                for (String mod : rulePromptModules) {
                    if (Boolean.TRUE.equals(MODULES.get(mod))) {
                        MODULES.put(mod, false);
                        ruleDisabledModules.add(mod);
                        n++;
                    }
                }
                cachedOnCount = -1;
                // Memory-only — no saveConfig. Persisted config keeps your
                // manual choices intact even if MC crashes mid-server.
                injectChat("§7§l[Shadow] §aDisabled " + n + " module"
                    + (n == 1 ? "" : "s") + " §7(memory-only) §a— auto-restoring "
                    + "when you leave §f" + rulePromptServer);
                break;
            }
            case 'N': case 'n':
                injectChat("§7§l[Shadow] §7Kept all modules on. Server may kick you for "
                    + "violations — no auto-restore needed.");
                break;
            case 'I': case 'i':
                ruleIgnoredServers.add(rulePromptServer.toLowerCase());
                injectChat("§7§l[Shadow] §7Ignoring §f" + rulePromptServer
                    + " §7for the rest of this session.");
                break;
            default:
                injectChat("§7§l[Shadow] §7Prompt timed out — no changes made.");
                break;
        }
        rulePromptModules.clear();
    }

    /** Restore modules we previously disabled via ServerRules. Called when
     *  we detect a server change (leave, switch, return to main menu). */
    private static void restoreRuleDisabledModules() {
        if (ruleDisabledModules.isEmpty()) return;
        java.util.List<String> restored = new java.util.ArrayList<>();
        for (String mod : ruleDisabledModules) {
            if (MODULES.containsKey(mod)) {
                MODULES.put(mod, true);
                restored.add(mod);
            }
        }
        ruleDisabledModules.clear();
        if (!restored.isEmpty()) {
            cachedOnCount = -1;
            // Memory-only restore — no saveConfig. Persisted config has been
            // unchanged since the user's last manual menu toggle.
            injectChat("§7§l[Shadow] §aRestored §f" + restored.size()
                + "§a module" + (restored.size() == 1 ? "" : "s")
                + ": §f" + String.join(", ", restored));
        }
    }

    /** Detect server change, fire prompt-if-rules-match / restore-if-leaving.
     *  Called every frame from renderHud's Server module. */
    private static void maybeApplyServerRules(String currentServer) {
        if (!modOn("ServerRules", false)) return;
        if (currentServer == null || currentServer.isEmpty()) return;
        // Auto-time out the prompt after 15 s.
        if (rulePromptActive
            && System.currentTimeMillis() - rulePromptShownMs > 15_000L) {
            resolveServerRulesPrompt('-');
        }
        // No change → nothing to do.
        if (currentServer.equals(currentRuleServer)) return;
        // Server CHANGED — restore anything disabled by the previous server.
        if (!currentRuleServer.isEmpty()) restoreRuleDisabledModules();
        currentRuleServer = currentServer;
        // Look up rules for the new server.
        reloadServerRules();
        if (ruleIgnoredServers.contains(currentServer.toLowerCase())) return;
        if (currentServer.equals("Main menu") || currentServer.equals("Singleplayer")) return;
        String lower = currentServer.toLowerCase();
        for (java.util.Map.Entry<String, java.util.List<String>> e : serverRules.entrySet()) {
            if (!lower.contains(e.getKey())) continue;
            java.util.List<String> currentlyOn = new java.util.ArrayList<>();
            for (String mod : e.getValue()) {
                if (Boolean.TRUE.equals(MODULES.get(mod))) currentlyOn.add(mod);
            }
            if (!currentlyOn.isEmpty()) showServerRulesPrompt(currentServer, currentlyOn);
            break;
        }
    }

    /** Inject a system message into MC's chat HUD. Reused by ServerRules and
     *  could be reused by other one-off notifications. Best-effort — silent
     *  if MC's chatHud isn't reachable. */
    private static void injectChat(String text) {
        if (text == null || mc == null) return;
        try {
            Object inGameHud = tryInvoke(mc, "method_1561", "inGameHud", "getInGameHud");
            if (inGameHud == null) {
                Field f = cachedField(mc.getClass(), "field_1705");
                if (f != null) inGameHud = f.get(mc);
            }
            if (inGameHud == null) return;
            Object chatHud = tryInvoke(inGameHud, "method_1743", "getChatHud");
            if (chatHud == null) return;
            Class<?> textCls = Class.forName("net.minecraft.class_2561");
            // Use findMethodByName — cachedMethod only finds no-arg methods,
            // and these are single-arg (Text.literal(String), ChatHud.addMessage(Text)).
            Method literal = findMethodByName(textCls, "method_30163");
            if (literal == null) literal = findMethodByName(textCls, "method_43470");
            if (literal == null) literal = findMethodByName(textCls, "literal");
            if (literal == null) return;
            Object msg = literal.invoke(null, text);
            Method addMsg = findMethodByName(chatHud.getClass(), "method_1812");
            if (addMsg == null) addMsg = findMethodByName(chatHud.getClass(), "addMessage");
            if (addMsg != null) addMsg.invoke(chatHud, msg);
        } catch (Throwable ignored) {}
    }

    /** Apply server-aware preset if enabled and we just changed servers.
     *  Called every frame from renderHud's Server module. */
    private static void maybeApplyServerPreset(String currentServer) {
        if (!modOn("AutoPreset", false)) return;
        if (currentServer == null || currentServer.isEmpty()) return;
        if (currentServer.equals(lastAutoPresetServer)) return;
        reloadSocialFiles();
        if (serverPresets.isEmpty()) {
            lastAutoPresetServer = currentServer;
            return;
        }
        String lower = currentServer.toLowerCase();
        for (java.util.Map.Entry<String, String> e : serverPresets.entrySet()) {
            if (lower.contains(e.getKey())) {
                lastAutoPresetServer = currentServer;
                System.out.println("[ShadowHud][AutoPreset] " + currentServer
                    + " matched \"" + e.getKey() + "\" → applying preset \""
                    + e.getValue() + "\" (memory-only, persisted config unchanged)");
                try { applyPresetSilent(e.getValue()); }
                catch (Throwable t) { logOnce("AutoPreset", t); }
                return;
            }
        }
        lastAutoPresetServer = currentServer;
    }

    /** Returns true if the given chat message matches any active filter. */
    private static boolean shouldFilterChat(String message) {
        try {
            if (Files.exists(CHAT_FILTER_FILE)) {
                long m = Files.getLastModifiedTime(CHAT_FILTER_FILE).toMillis();
                if (m != chatFilterMtime) {
                    chatFilterMtime = m;
                    chatFilterPatterns.clear();
                    for (String line : Files.readAllLines(CHAT_FILTER_FILE)) {
                        String t = line.trim();
                        if (t.isEmpty() || t.startsWith("#")) continue;
                        try {
                            chatFilterPatterns.add(java.util.regex.Pattern.compile(
                                t, java.util.regex.Pattern.CASE_INSENSITIVE));
                        } catch (Throwable e) {
                            System.err.println("[ShadowHud][ChatFilter] bad regex \"" + t
                                + "\": " + e.getMessage());
                        }
                    }
                    System.out.println("[ShadowHud][ChatFilter] loaded "
                        + chatFilterPatterns.size() + " patterns");
                }
            }
        } catch (Throwable ignored) {}
        for (java.util.regex.Pattern pat : chatFilterPatterns) {
            if (pat.matcher(message).find()) return true;
        }
        return false;
    }

    /** Record the chunk the player is in; also sample + cache its terrain color. */
    private static void trackCurrentChunk() {
        try {
            if (mc == null || playerField == null) return;
            Object player = playerField.get(mc);
            if (player == null) return;
            Object world  = worldField != null ? worldField.get(mc) : null;
            if (world == null) return;
            double x = firstNum(player, "method_23317", "getX").doubleValue();
            double z = firstNum(player, "method_23321", "getZ").doubleValue();
            int cx = (int) Math.floor(x / 16.0);
            int cz = (int) Math.floor(z / 16.0);
            Map<Long, Integer> here = currentChunkMap();
            long key = chunkKey(cx, cz);
            // Sample the 3×3 chunks around the player so the area fills in naturally
            // as you walk instead of showing only the single chunk you're standing in.
            ensureBlockSampling(world);
            for (int ddx = -1; ddx <= 1; ddx++) {
                for (int ddz = -1; ddz <= 1; ddz++) {
                    long k2 = chunkKey(cx + ddx, cz + ddz);
                    if (here.containsKey(k2)) continue;
                    int col = sampleChunkColor(world, cx + ddx, cz + ddz);
                    here.put(k2, col);
                }
            }
            // Always ensure the center is present even if sampling failed
            here.putIfAbsent(key, FALLBACK_COLOR);

            long now = System.currentTimeMillis();
            if (now - lastChunksSaveMs > 30_000) {
                lastChunksSaveMs = now;
                saveExploredChunks();
            }

            // ----- Cosmetic particle effects -------------------------------
            // Each call individually wrapped so one failure can't disable the
            // rest. Earlier code had a single outer try/catch which meant a
            // bug in Trail's path silently broke Halo/Wings/etc too.
            //
            // Wings/AngelWings/Cape ALSO try the 3D mesh path via
            // WorldRenderEvents.AFTER_ENTITIES (see renderCosmetics3D), but
            // 1.21.11 renamed RenderLayer.getLines / Camera.getPos /
            // VertexConsumer.normal — so the mesh path silently no-ops on
            // current MC. The particle path below is the visible fallback
            // that actually shows something when the user toggles Wings on.
            if (modOn("Trail",      false))
                try { spawnTrailParticles(world, player);     } catch (Throwable t) { logOnce("Trail",     t); }
            if (modOn("Halo",       false))
                try { spawnHaloParticles(world, player);      } catch (Throwable t) { logOnce("Halo",      t); }
            // Wings / AngelWings / Cape: 3D mesh ONLY. The previous
            // particle-fallback paths have been removed at user request —
            // if the 3D mesh path can't render, the cosmetic just doesn't
            // show. Use WingsSolid / AngelWingsSolid for filled-mesh
            // variants when the wireframe lines aren't visible enough.
            if (modOn("Fairies",    false))
                try { spawnFairyParticles(world, player);     } catch (Throwable t) { logOnce("Fairies",   t); }
            if (modOn("Footsteps",  false))
                try { spawnFootstepParticles(world, player);  } catch (Throwable t) { logOnce("Footsteps", t); }
            if (modOn("BowTrail",   false))
                try { spawnBowTrailParticles(world, player);  } catch (Throwable t) { logOnce("BowTrail",  t); }
        } catch (Throwable ignored) {}
    }

    /** For every arrow / trident in the world owned by the local player,
     *  spawn a row of particles along its current motion vector so the path
     *  reads as a continuous streak rather than dotted gaps. */
    private static void spawnBowTrailParticles(Object world, Object player) {
        resolveParticleApi(world);
        if (addParticleMethod == null) return;
        Object particle = resolveParticleAt(bowTrailIdx);
        if (particle == null) return;
        try {
            Object entIter = tryInvoke(world, "method_18112", "getEntities", "entitiesForRendering");
            if (!(entIter instanceof Iterable)) return;
            for (Object e : (Iterable<?>) entIter) {
                // Match any AbstractArrow / Trident — walks the class chain so
                // SpectralArrow, custom modded arrows etc. all qualify.
                Class<?> c = e.getClass();
                boolean isProjectile = false;
                while (c != null) {
                    String cn = c.getName();
                    if (cn.endsWith("class_1665") || cn.endsWith("AbstractArrow")
                        || cn.endsWith("class_1671") || cn.endsWith("TridentEntity")) {
                        isProjectile = true; break;
                    }
                    c = c.getSuperclass();
                }
                if (!isProjectile) continue;
                // Only for arrows the local player fired — getOwner() == player
                Object owner = tryInvoke(e, "method_24921", "getOwner");
                if (owner != player) continue;

                double ex = firstNum(e, "method_23317", "getX").doubleValue();
                double ey = firstNum(e, "method_23318", "getY").doubleValue();
                double ez = firstNum(e, "method_23321", "getZ").doubleValue();

                // Velocity gives us the arrow's per-tick motion — interpolate
                // 5 particles backward along it so the trail is continuous.
                Object vel = tryInvoke(e, "method_18798", "getVelocity", "getDeltaMovement");
                double vx = 0, vy = 0, vz = 0;
                if (vel != null) {
                    try {
                        vx = firstNum(vel, "field_1352", "x").doubleValue();
                        vy = firstNum(vel, "field_1351", "y").doubleValue();
                        vz = firstNum(vel, "field_1350", "z").doubleValue();
                    } catch (Throwable ignored) {}
                }
                int steps = 5;
                for (int i = 0; i < steps; i++) {
                    double t = i / (double) steps;       // 0..0.8 of one tick back
                    addParticleMethod.invoke(world, particle, true, true,
                        ex - vx * t, ey - vy * t, ez - vz * t,
                        0d, 0d, 0d);
                }
            }
        } catch (Throwable ignored) {}
    }


    /** Angel wings — same flap mechanic as dragon wings but with a wider,
     *  more pronounced feather silhouette and a slightly slower flap. */
    private static void spawnAngelWingParticles(Object world, Object player) {
        resolveParticleApi(world);
        if (addParticleMethod == null) return;
        Object particle = resolveParticleAt(angelWingsIdx);
        if (particle == null) return;
        try {
            if (angelWingsTimerStart == 0) angelWingsTimerStart = System.currentTimeMillis();
            double elapsed = (System.currentTimeMillis() - angelWingsTimerStart) / 1000.0;
            float flap = (float)(Math.sin(elapsed * 3.2) * 0.55);   // gentler 0.5 Hz

            double px  = firstNum(player, "method_23317", "getX").doubleValue();
            double py  = firstNum(player, "method_23318", "getY").doubleValue() + 1.4;
            double pz  = firstNum(player, "method_23321", "getZ").doubleValue();
            float  yaw = firstNum(player, "method_36454", "getYRot", "getYaw").floatValue();
            float  yawRad = (float) Math.toRadians(yaw);
            double rx = Math.cos(yawRad), rz = Math.sin(yawRad);
            double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
            double anchorX = px - fx * 0.30;
            double anchorZ = pz - fz * 0.30;

            float cosF = (float) Math.cos(flap), sinF = (float) Math.sin(flap);
            for (int side = -1; side <= 1; side += 2) {
                for (float[] wp : ANGEL_WING_SHAPE) {
                    float xr = wp[0] * cosF - wp[1] * sinF;
                    float yr = wp[0] * sinF + wp[1] * cosF;
                    addParticleMethod.invoke(world, particle, true, true,
                        anchorX + side * xr * rx,
                        py + yr,
                        anchorZ + side * xr * rz,
                        0d, 0d, 0d);
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Four fairies orbiting at varying radii / altitudes / speeds. */
    private static void spawnFairyParticles(Object world, Object player) {
        resolveParticleApi(world);
        if (addParticleMethod == null) return;
        Object particle = resolveParticleAt(fairiesIdx);
        if (particle == null) return;
        try {
            long now = System.currentTimeMillis();
            double px = firstNum(player, "method_23317", "getX").doubleValue();
            double py = firstNum(player, "method_23318", "getY").doubleValue() + 1.0;
            double pz = firstNum(player, "method_23321", "getZ").doubleValue();
            int count = 4;
            for (int i = 0; i < count; i++) {
                double speed  = 1.4 + i * 0.3;
                double radius = 0.8 + (i % 2) * 0.4;
                double phase  = i * (Math.PI * 2.0 / count);
                double a      = (now / 1000.0) * speed + phase;
                double yBob   = Math.sin(a * 1.7) * 0.3 + (i - count / 2.0) * 0.15;
                addParticleMethod.invoke(world, particle, true, true,
                    px + Math.cos(a) * radius,
                    py + yBob,
                    pz + Math.sin(a) * radius,
                    0d, 0d, 0d);
            }
        } catch (Throwable ignored) {}
    }

    /** Footstep trail — every ~150 ms while on ground we record the player's
     *  position. Each frame we spawn a fading particle at every recorded spot.
     *  Old entries (>2 s) are dropped from the queue. */
    private static void spawnFootstepParticles(Object world, Object player) {
        resolveParticleApi(world);
        if (addParticleMethod == null) return;
        Object particle = resolveParticleAt(footstepsIdx);
        if (particle == null) return;
        try {
            long now = System.currentTimeMillis();
            Object grounded = tryInvoke(player, "method_24828", "isOnGround", "onGround");
            if (Boolean.TRUE.equals(grounded) && now - lastFootstepMs > 150) {
                double px = firstNum(player, "method_23317", "getX").doubleValue();
                double py = firstNum(player, "method_23318", "getY").doubleValue() + 0.05;
                double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                FOOTSTEP_LOG.addLast(new double[]{px, py, pz, now});
                lastFootstepMs = now;
            }
            // Drop history older than 2 seconds
            while (!FOOTSTEP_LOG.isEmpty() && now - FOOTSTEP_LOG.peekFirst()[3] > 2000) {
                FOOTSTEP_LOG.pollFirst();
            }
            // Spawn one particle at each recorded step
            for (double[] step : FOOTSTEP_LOG) {
                addParticleMethod.invoke(world, particle, true, true,
                    step[0], step[1], step[2], 0d, 0d, 0d);
            }
        } catch (Throwable ignored) {}
    }


    /** Wireframe wing rendering — interpolates particles along the LINE
     *  SEGMENTS between consecutive WING_SHAPE points instead of just
     *  spawning a puff at each point. Result reads as a connected polygon
     *  outline, much closer to a 3D mesh look. Default particle override
     *  to END_ROD (clean glowing dots) for a sharper edge. */
    private static void spawnWingParticles(Object world, Object player) {
        resolveParticleApi(world);
        if (addParticleMethod == null) return;
        // Force END_ROD for the mesh-style edge unless user picked something
        // explicit. END_ROD is the cleanest 3D-ish particle.
        Object particle = particleByField("field_11207");
        if (particle == null) particle = resolveParticleAt(wingsParticleIdx);
        if (particle == null) return;
        try {
            if (wingsTimerStart == 0) wingsTimerStart = System.currentTimeMillis();
            double elapsed = (System.currentTimeMillis() - wingsTimerStart) / 1000.0;
            float flap = (float)(Math.sin(elapsed * 1.9) * 0.12);

            double px  = firstNum(player, "method_23317", "getX").doubleValue();
            double py  = firstNum(player, "method_23318", "getY").doubleValue() + 1.5;
            double pz  = firstNum(player, "method_23321", "getZ").doubleValue();
            float  yaw = firstNum(player, "method_36454", "getYRot", "getYaw").floatValue();
            float  yawRad = (float) Math.toRadians(yaw);
            double rx = Math.cos(yawRad), rz = Math.sin(yawRad);
            double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);
            double anchorX = px - fx * 0.18;
            double anchorZ = pz - fz * 0.18;

            float cosF = (float) Math.cos(flap);
            float sinF = (float) Math.sin(flap);

            // Number of particles per line segment. Higher = more wireframe-like.
            final int STEPS = 5;

            for (int side = -1; side <= 1; side += 2) {
                // Outer outline: connect consecutive WING_SHAPE points with
                // STEPS interpolated particles. Closed loop (last point
                // connects back to first).
                for (int i = 0; i < WING_SHAPE.length; i++) {
                    float[] a = WING_SHAPE[i];
                    float[] b = WING_SHAPE[(i + 1) % WING_SHAPE.length];
                    // Apply flap rotation to both endpoints
                    float ax = a[0] * cosF - a[1] * sinF;
                    float ay = a[0] * sinF + a[1] * cosF;
                    float bx = b[0] * cosF - b[1] * sinF;
                    float by = b[0] * sinF + b[1] * cosF;
                    // Spawn STEPS particles along the segment
                    for (int s = 0; s < STEPS; s++) {
                        float t = (float) s / STEPS;
                        float xr = ax + (bx - ax) * t;
                        float yr = ay + (by - ay) * t;
                        addParticleMethod.invoke(world, particle, true, true,
                            anchorX + side * xr * rx,
                            py + yr,
                            anchorZ + side * xr * rz,
                            0d, 0d, 0d);
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /** Render a 5-column × 6-row particle cape that hangs behind the player's
     *  shoulders. The cape "lags" behind movement using a smoothed offset, so
     *  when you sprint it billows backward, and when you stop it falls. */
    private static void spawnCapeParticles(Object world, Object player) {
        if (capeIdx < 0 || capeIdx >= TRAIL_PARTICLES.length) return;
        resolveParticleApi(world);
        if (addParticleMethod == null) return;
        Object particle = resolveParticleAt(capeIdx);
        if (particle == null) return;
        try {
            double px  = firstNum(player, "method_23317", "getX").doubleValue();
            double py  = firstNum(player, "method_23318", "getY").doubleValue() + 1.5;
            double pz  = firstNum(player, "method_23321", "getZ").doubleValue();
            float  yaw = firstNum(player, "method_36454", "getYRot", "getYaw").floatValue();
            float  yawRad = (float) Math.toRadians(yaw);
            double rx = Math.cos(yawRad), rz = Math.sin(yawRad);
            double fx = -Math.sin(yawRad), fz = Math.cos(yawRad);

            // Player velocity (used to billow the cape backward). Approximated
            // from this-frame vs last-frame position — server doesn't always
            // expose getDeltaMovement reliably for client.
            long now = System.currentTimeMillis();
            double dt = capeLastFrameMs == 0 ? 0.05
                         : Math.max(0.001, (now - capeLastFrameMs) / 1000.0);
            capeLastFrameMs = now;

            // Smoothed lag offset — the cape's "anchor" lags behind player
            // motion by a fraction, giving it inertia. Lerp toward zero.
            capeLagX += (-fx * 0.5 - capeLagX) * Math.min(1, dt * 6);
            capeLagZ += (-fz * 0.5 - capeLagZ) * Math.min(1, dt * 6);

            // 5 columns spanning shoulder-to-shoulder, 6 rows draping down
            int COLS = 5, ROWS = 6;
            for (int c = 0; c < COLS; c++) {
                float colT = (c - (COLS - 1) / 2.0f) / ((COLS - 1) / 2.0f);  // -1..+1
                double colExt = colT * 0.30;        // ±0.30 m wide at shoulders
                for (int r = 0; r < ROWS; r++) {
                    float rowT = r / (float)(ROWS - 1);                     // 0..1
                    // Drape: lower rows hang back further + droop down
                    double behind = 0.30 + rowT * 0.55;
                    double drop   = -rowT * 1.20;
                    // Slight billow + lag: lower rows lag more
                    double billowX = capeLagX * (0.4 + rowT * 0.7);
                    double billowZ = capeLagZ * (0.4 + rowT * 0.7);
                    // Side-to-side wave so the cape feels alive
                    double wave = Math.sin((now / 200.0) + r * 0.6) * 0.05 * rowT;

                    // Build local offset: right * colExt - forward * behind + wave
                    double lx = colExt * rx + (-behind) * fx + wave * fx + billowX;
                    double lz = colExt * rz + (-behind) * fz + wave * fz + billowZ;
                    double wy = py + drop;
                    addParticleMethod.invoke(world, particle, true, true,
                        px + lx, wy, pz + lz, 0d, 0d, 0d);
                }
            }
        } catch (Throwable ignored) {}
    }


    /** Lazy-resolve `World.addParticleClient` and the ParticleEffect interface.
     *  Re-tries every frame until we successfully find a method — earlier
     *  versions cached a "tried" flag even when the resolve failed, leaving
     *  cosmetics permanently broken if MC was in a transitional state on the
     *  first call. Now tracks success separately so we recover. */
    private static void resolveParticleApi(Object world) {
        if (addParticleMethod != null) return;     // already resolved
        try {
            // Prefer the named 9-param spawn (`addParticleClient` in 1.21.11,
            // intermediary method_8466). Iterate until we find ANY 9-param
            // method whose first parameter is an interface (ParticleEffect).
            Method best = null;
            for (Method m : world.getClass().getMethods()) {
                String n = m.getName();
                if (m.getParameterCount() != 9) continue;
                if (!(n.equals("method_8466") || n.equals("method_74919")
                      || n.equals("addParticleClient") || n.equals("addParticle"))) continue;
                Class<?>[] pt = m.getParameterTypes();
                // Last 6 params must be doubles; middle 2 booleans
                boolean ok = pt[1] == boolean.class && pt[2] == boolean.class;
                for (int i = 3; i < 9 && ok; i++) ok = pt[i] == double.class;
                if (!ok) continue;
                if (pt[0].isInterface()) { best = m; break; }   // best match
                if (best == null) best = m;
            }
            if (best != null) {
                addParticleMethod = best;
                particleEffectInterface = best.getParameterTypes()[0];
                if (!particleResolveTried) {
                    particleResolveTried = true;
                    System.out.println("[ShadowHud][Cosmetic] particle API: " + best);
                }
            }
        } catch (Throwable t) {
            if (!particleResolveTried) {
                particleResolveTried = true;
                System.err.println("[ShadowHud][Cosmetic] particle reflection: " + t);
            }
        }
    }

    /** Get a `ParticleEffect` value from `class_2398` (ParticleTypes) by its
     *  intermediary field name. Cached on first resolve to avoid re-reflecting. */
    private static final java.util.Map<String, Object> PARTICLE_CACHE = new java.util.HashMap<>();
    private static Object particleByField(String fieldName) {
        Object cached = PARTICLE_CACHE.get(fieldName);
        if (cached != null) return cached;
        try {
            Class<?> ptCls = Class.forName("net.minecraft.class_2398");
            Field f = ptCls.getField(fieldName);
            Object v = f.get(null);
            PARTICLE_CACHE.put(fieldName, v);
            return v;
        } catch (Throwable t) { return null; }
    }

    // ============= 3D cosmetic mesh rendering (Wings/AngelWings/Cape) =====
    //
    // Drawn into the world via WorldRenderEvents.AFTER_ENTITIES — that hook
    // gives us a real VertexConsumerProvider (ctx.consumers()) and the
    // post-camera MatrixStack (ctx.matrixStack()), which is exactly what we
    // need to push line geometry into MC's frame. No Mixin needed.
    //
    // Geometry is hand-crafted in player-local space:
    //   x = extension out from spine (mirrored for opposite wing)
    //   y = vertical from feet (so y=1.55 is shoulder height)
    //   z = depth (negative = behind the player's back)

    /** Tiny dragon-style wing silhouette (one side; mirrored for the other).
     *  Walks the leading edge with 3 finger-claws sticking out, then back
     *  along the trailing membrane. Vertices ordered CCW so the polygon
     *  triangulates cleanly via the fan starting at index 0. */
    private static final float[][] WING_3D_SHAPE = {
        // Root at shoulder
        {0.05f, 1.55f, -0.30f},
        // Leading edge — upper arm sweeping out
        {0.20f, 1.65f, -0.30f},
        {0.40f, 1.60f, -0.30f},
        // First finger / claw — top point
        {0.55f, 1.55f, -0.30f},  // claw tip
        {0.45f, 1.40f, -0.30f},  // claw notch
        // Second finger
        {0.62f, 1.20f, -0.30f},  // tip
        {0.50f, 1.05f, -0.30f},  // notch
        // Third finger
        {0.60f, 0.85f, -0.30f},  // tip
        {0.45f, 0.75f, -0.30f},  // notch
        // Tail of wing — pointy hook
        {0.50f, 0.55f, -0.30f},  // tip
        {0.30f, 0.40f, -0.30f},  // membrane curve
        // Trailing membrane back to root
        {0.15f, 0.65f, -0.30f},
        {0.05f, 1.00f, -0.30f},
    };
    /** Wider feathered angel-wing silhouette. */
    private static final float[][] ANGEL_3D_SHAPE = {
        {0.15f, 1.55f, -0.30f}, {0.50f, 1.65f, -0.30f}, {0.90f, 1.65f, -0.30f},
        {1.30f, 1.50f, -0.30f}, {1.65f, 1.20f, -0.30f}, {1.85f, 0.85f, -0.30f},
        {1.95f, 0.45f, -0.30f}, {1.85f, 0.10f, -0.30f}, {1.55f,-0.10f, -0.30f},
        {1.15f,-0.05f, -0.30f}, {0.75f, 0.10f, -0.30f}, {0.40f, 0.30f, -0.30f},
        {0.20f, 0.55f, -0.30f}, {0.10f, 1.00f, -0.30f},
    };
    /** Shadow Client cape — single centered shape (NOT mirrored). */
    private static final float[][] CAPE_3D_SHAPE = {
        {-0.36f, 1.55f, -0.30f}, { 0.36f, 1.55f, -0.30f},
        { 0.45f, 1.25f, -0.30f}, { 0.52f, 0.90f, -0.30f},
        { 0.55f, 0.50f, -0.30f}, { 0.45f, 0.10f, -0.30f},
        { 0.30f,-0.20f, -0.30f}, { 0.10f, 0.05f, -0.30f},
        { 0.00f, 0.25f, -0.30f}, {-0.10f, 0.05f, -0.30f},
        {-0.30f,-0.20f, -0.30f}, {-0.45f, 0.10f, -0.30f},
        {-0.55f, 0.50f, -0.30f}, {-0.52f, 0.90f, -0.30f},
        {-0.45f, 1.25f, -0.30f},
    };
    /** Red accent strokes inside the cape — vertical ribs + claw slashes. */
    private static final float[][] CAPE_3D_ACCENTS = {
        {-0.22f, 1.52f, -0.16f, 0.30f},
        { 0.00f, 1.52f,  0.00f, 0.20f},
        { 0.22f, 1.52f,  0.16f, 0.30f},
        {-0.30f, 1.30f, -0.05f, 1.10f},
        {-0.18f, 1.20f,  0.07f, 1.00f},
        {-0.06f, 1.10f,  0.19f, 0.90f},
    };

    /* Cached reflection handles — set once on first render call. */
    private static Method  cosmPeekStack, cosmGetPosMat, cosmGetBuffer;
    private static Method  cosmVtxPos, cosmVtxColor, cosmVtxNormal, cosmGetCamPos;
    private static Method  cosmVtxTexture, cosmVtxOverlay, cosmVtxLight;
    private static Object  cosmLineLayer;
    /** Textured-quad pipeline (cape). {@code cosmCapeLayer} is a 1.21.11
     *  RenderLayer built from {@code class_12249.method_NNNNN(Identifier)}
     *  whose internal name contains "entity_cutout_no_cull" — the right
     *  blend mode for our alpha-keyed cape PNG. {@code cosmCapeIdentifier}
     *  is a {@code class_2960} ("shadowhud:textures/cape/cape1.png"). */
    private static Object  cosmCapeLayer1, cosmCapeLayer2;
    private static Object  cosmCapeIdentifier1, cosmCapeIdentifier2;
    /** Solid-color layer (1×1 white PNG) — used for filled wing meshes
     *  so wings render as proper opaque triangles instead of wireframes. */
    private static Object  cosmSolidLayer;
    private static Object  cosmSolidIdentifier;
    private static boolean cosmCapeResolveTried;
    /** Diagnostic — log once the first time the textured cape quad
     *  successfully reaches the buffer write (post-draw). If you DON'T
     *  see this in the log, the texture path failed to draw — check
     *  the cosmFailOnce(8/9/10/11) lines for which step. */
    private static boolean shadowhud$capeFirstDrawLogged;
    private static boolean shadowhud$blockHighlightDiagLogged;
    private static boolean shadowhud$bhitDiag1, shadowhud$bhitDiag2, shadowhud$bhitDiag3, shadowhud$bhitDiag4;
    private static boolean cosmResolveTried, cosmResolveLogged;
    /** First-paint diagnostic — fires once per session so we can confirm via
     *  log that the world-render path actually executed. */
    private static boolean cosmFirstDrawLogged;
    /** True once the 3D mesh path successfully rendered at least one frame.
     *  When set, the particle fallback for Wings/AngelWings/Cape is skipped
     *  so the user sees the real 3D mesh and not both layered on top. */
    private static volatile boolean cosm3DWorking;

    /** Tracks the last cosmetic-path failure step so we don't spam logs.
     *  -1 = never logged, 0..N = step index of the last failure logged. */
    private static int cosmLastFailStep = -1;

    /** Throttle the AFTER_ENTITIES idle heartbeat — fires every 10s for
     *  the first {@code shadowhud$idleHeartbeatCount < 6} pulses while
     *  no cosmetic flag is set. Proves the event is wired without spamming
     *  the log forever. */
    private static long shadowhud$lastIdleHeartbeat;
    private static int  shadowhud$idleHeartbeatCount;
    /** True after the very first frame where wantWings/Angel/Cape is true.
     *  If the user toggles Cape on and the next render frame doesn't log
     *  this, the toggle isn't propagating to the render thread. */
    private static boolean shadowhud$enabledFlagFirstSeen;

    /** One-time log helper for early-returns in {@link #renderCosmetics3D}.
     *  Each step prints exactly once per JVM session — re-enabling Wings
     *  after a failure won't re-spam the log. */
    private static void cosmFailOnce(int step, String msg) {
        if (cosmLastFailStep == step) return;
        cosmLastFailStep = step;
        System.out.println("[ShadowHud][Cosmetic3D] step " + step + " fail: " + msg);
    }

    /** Entry point — wired to {@code WorldRenderEvents.AFTER_ENTITIES}.
     *  Backup path for self when third-person view is off. If the mixin
     *  rendered the local player within the last 100ms, this path defers
     *  to avoid double-rendering geometry. */
    private static void renderCosmetics3D(WorldRenderContext ctx) {
        boolean wantWings = modOn("Wings",      false);
        boolean wantAngel = modOn("AngelWings", false);
        boolean wantCape  = modOn("Cape",       false);
        boolean wantBlockHighlight = modOn("BlockHighlight", false);
        boolean wantWingsSolid = modOn("WingsSolid", false);
        boolean wantAngelSolid = modOn("AngelWingsSolid", false);
        if (!wantWings && !wantAngel && !wantCape && !wantBlockHighlight
                && !wantWingsSolid && !wantAngelSolid) {
            // Track that the hook does fire even when no toggles are on.
            // Lets us prove the AFTER_ENTITIES pipeline is alive without
            // needing the user to enable anything first. Fires every
            // ~10s while no cosmetic flags are set.
            long now = System.currentTimeMillis();
            if (now - shadowhud$lastIdleHeartbeat > 10_000L) {
                shadowhud$lastIdleHeartbeat = now;
                if (shadowhud$idleHeartbeatCount++ < 6) {
                    System.out.println("[ShadowHud][Cosmetic] AFTER_ENTITIES heartbeat #"
                        + shadowhud$idleHeartbeatCount + " — no cosmetic flags enabled "
                        + "(Wings/AngelWings/Cape all false). Toggle one to test.");
                }
            }
            return;
        }
        // First call with cosmetics enabled — log so we know the flag flip
        // actually reaches the render path. Critical: if the user enables
        // Cape and this never fires, the bug is upstream of here (event
        // not registered, ctx wrong type, etc.).
        if (!shadowhud$enabledFlagFirstSeen) {
            shadowhud$enabledFlagFirstSeen = true;
            System.out.println("[ShadowHud][Cosmetic] FIRST FRAME with cosmetic flags enabled — "
                + "wings=" + wantWings + " angel=" + wantAngel + " cape=" + wantCape
                + " — render path entering");
        }
        if (mc == null || playerField == null) {
            cosmFailOnce(1, "mc=" + (mc != null) + " playerField=" + (playerField != null));
            return;
        }
        // The legacy per-entity mixin path was disabled (1.21.11 removed
        // class_898.method_3954). mixinLastSelfRenderMs stays at 0 forever
        // so this guard is a no-op but kept for safety in case the mixin
        // is reintroduced later. Removing it would tighten the code path.
        if (System.currentTimeMillis() - shadowhud$mixinLastSelfRenderMs < 100L) return;
        Object player;
        try { player = playerField.get(mc); }
        catch (Throwable t) { cosmFailOnce(2, "playerField.get: " + t); return; }
        if (player == null) { cosmFailOnce(3, "player is null"); return; }

        // WorldRenderContext.matrices() returns class_4587 (MatrixStack);
        // .consumers() returns class_4597 (VertexConsumerProvider). Both are
        // intermediary-named MC classes not on our compile classpath, so we
        // capture them as Object via reflection instead of typed accessors.
        Object matrices, provider;
        try {
            matrices = ctx.getClass().getMethod("matrices").invoke(ctx);
            provider = ctx.getClass().getMethod("consumers").invoke(ctx);
        } catch (Throwable t) {
            logOnce("WorldRenderContext-accessor", t);
            cosmFailOnce(4, "ctx.matrices()/consumers() reflection: " + t);
            return;
        }
        if (matrices == null || provider == null) {
            cosmFailOnce(5, "matrices=" + (matrices != null) + " provider=" + (provider != null));
            return;
        }

        resolveCosmeticHandles(matrices, provider);
        // Two independent render paths — line geometry (wings/angel) and
        // textured quads (cape). The cape path doesn't need the line layer,
        // so we no longer require cosmLineLayer for the function to proceed.
        // The fail-message lists what's missing so we can target the gap.
        boolean canDoLines = cosmLineLayer != null && cosmGetBuffer != null
            && cosmPeekStack != null && cosmGetPosMat != null
            && cosmVtxPos != null && cosmVtxColor != null;
        boolean canDoTexture = cosmGetBuffer != null && cosmPeekStack != null
            && cosmGetPosMat != null && cosmVtxPos != null
            && cosmVtxColor != null && cosmVtxTexture != null;
        if (!canDoLines && !canDoTexture) {
            cosmFailOnce(6, "ALL handles missing — peek=" + (cosmPeekStack != null)
                + " posMat=" + (cosmGetPosMat != null)
                + " getBuffer=" + (cosmGetBuffer != null)
                + " lineLayer=" + (cosmLineLayer != null)
                + " vtx=" + (cosmVtxPos != null)
                + " color=" + (cosmVtxColor != null)
                + " texture=" + (cosmVtxTexture != null));
            return;
        }
        // If only one path works, skip the modules that depend on the other.
        if (!canDoLines) {
            wantWings = false; wantAngel = false;
            cosmFailOnce(12, "Wings/Angel disabled — line handles missing (lineLayer/peek/posMat/vtx/color)");
        }
        if (!canDoTexture) {
            cosmFailOnce(13, "Cape texture disabled — texture handle missing (cosmVtxTexture null)");
        }

        try {
            // Camera world position — subtract from each player to render
            // in camera-relative space. Computed ONCE per frame (not per
            // player) so we don't re-walk mc.gameRenderer.camera 5+ times
            // when there are multiple players in view.
            double cx = 0, cy = 0, cz = 0;
            try {
                Object cam = resolveCamera();
                if (cam != null && cosmGetCamPos == null) {
                    for (Method m : cam.getClass().getMethods()) {
                        if (m.getParameterCount() != 0) continue;
                        String n = m.getName();
                        // 1.21.11: Camera.getPos was renamed method_19326 →
                        // method_71156. Try both, plus the named alias.
                        if (n.equals("method_19326") || n.equals("method_71156")
                            || n.equals("getPos")) {
                            cosmGetCamPos = m; break;
                        }
                    }
                }
                if (cam != null && cosmGetCamPos != null) {
                    Object cp = cosmGetCamPos.invoke(cam);
                    if (cp != null) {
                        cx = ((Number) cp.getClass().getField("x").get(cp)).doubleValue();
                        cy = ((Number) cp.getClass().getField("y").get(cp)).doubleValue();
                        cz = ((Number) cp.getClass().getField("z").get(cp)).doubleValue();
                    }
                }
            } catch (Throwable ignored) {}

            // Resolve once: matrix entry / posMat / line-buffer / vertex args.
            Object entry  = cosmPeekStack.invoke(matrices);
            Object posMat = cosmGetPosMat.invoke(entry);
            // Line buffer is null-safe — only acquired if cosmLineLayer
            // resolved. Cape rendering uses its own per-frame layer buffer
            // and doesn't depend on this one.
            Object buffer = (cosmLineLayer != null)
                ? cosmGetBuffer.invoke(provider, cosmLineLayer)
                : null;
            Object vertexArg = cosmVtxPos.getParameterTypes()[0].isInstance(entry)
                ? entry : posMat;
            Object normalArg = (cosmVtxNormal != null
                && cosmVtxNormal.getParameterTypes()[0].isInstance(entry))
                ? entry : posMat;

            // Resolve textured layers ONCE up-front. Cape uses cape{1,2}.png,
            // block-highlight + solid wings use the 1×1 white layer for
            // filled meshes. No-op after first call.
            if (wantCape || wantWings || wantAngel || wantBlockHighlight
                    || wantWingsSolid || wantAngelSolid) {
                resolveCapeTextures(provider);
            }
            // Block face highlight — drawn at WORLD coordinates (camera
            // offset already applied inside the helper). Doesn't iterate
            // per-player since it's tied to the local crosshair target.
            if (wantBlockHighlight) {
                if (!shadowhud$blockHighlightDiagLogged) {
                    shadowhud$blockHighlightDiagLogged = true;
                    String diag = "solidLayer=" + (cosmSolidLayer != null)
                        + " capeLayer=" + (cosmCapeLayer1 != null)
                        + " vtxPos=" + (cosmVtxPos != null)
                        + " vtxTex=" + (cosmVtxTexture != null)
                        + " vtxColor=" + (cosmVtxColor != null)
                        + " getBuf=" + (cosmGetBuffer != null);
                    System.out.println("[ShadowHud][BlockHighlight] first frame — " + diag);
                    flashToast("§4BlockHighlight: " + diag);
                }
                // Try preferred path (solid layer), fall back to cape layer
                // if solid didn't resolve. Both use the same vertex API.
                Object useLayer = cosmSolidLayer != null ? cosmSolidLayer : cosmCapeLayer1;
                if (useLayer != null && cosmGetBuffer != null && cosmVtxPos != null
                    && cosmVtxTexture != null && cosmVtxColor != null) {
                    try {
                        Object buf = cosmGetBuffer.invoke(provider, useLayer);
                        if (buf != null) {
                            drawBlockFaceHighlight(buf, vertexArg, normalArg, cx, cy, cz);
                            shadowhud$flushLayer(provider, useLayer);
                        } else {
                            cosmFailOnce(21, "BlockHighlight: getBuffer returned null for "
                                + useLayer.getClass().getSimpleName());
                        }
                    } catch (Throwable t) { logOnce("BlockHighlight", t); }
                } else {
                    cosmFailOnce(20, "BlockHighlight gates failed — useLayer="
                        + (useLayer != null) + " vtxPos=" + (cosmVtxPos != null)
                        + " vtxTex=" + (cosmVtxTexture != null)
                        + " vtxCol=" + (cosmVtxColor != null));
                }
            }

            // Build the player list: self always included, plus any
            // remote player within ~64 blocks (XZ). The Fabric world
            // exposes a Collection<PlayerEntity> via class_638.method_18456
            // ("getPlayers"). We use distance² for the filter to avoid
            // sqrt and skip far-away players (rendering cost cap).
            java.util.List<Object> playersToRender = new java.util.ArrayList<>();
            playersToRender.add(player);  // self always first
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    Object plist = tryInvoke(world, "method_18456", "players", "getPlayers");
                    if (plist instanceof Iterable) {
                        final double RADIUS_SQ = 64.0 * 64.0;
                        for (Object p : (Iterable<?>) plist) {
                            if (p == null || p == player) continue;
                            try {
                                double ox = firstNum(p, "method_23317", "getX").doubleValue();
                                double oz = firstNum(p, "method_23321", "getZ").doubleValue();
                                double d2 = (ox - cx) * (ox - cx) + (oz - cz) * (oz - cz);
                                if (d2 <= RADIUS_SQ) playersToRender.add(p);
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}

            long now = System.currentTimeMillis();
            for (Object p : playersToRender) {
                drawCosmeticsForPlayer(p, cx, cy, cz, now,
                    matrices, provider, buffer, vertexArg, normalArg,
                    wantWings, wantAngel, wantCape,
                    wantWingsSolid, wantAngelSolid);
            }

            // Mark the 3D path as working once we've reached this point
            // without throwing — turns off the particle fallback for the
            // mesh-based cosmetics so we don't render both.
            cosm3DWorking = true;
            if (!cosmFirstDrawLogged) {
                cosmFirstDrawLogged = true;
                System.out.println("[ShadowHud][Cosmetic3D] drew first frame — players="
                    + playersToRender.size() + " wings=" + wantWings
                    + " angel=" + wantAngel + " cape=" + wantCape
                    + " buf=" + (buffer != null ? buffer.getClass().getSimpleName() : "null"));
                // Also flash a toast so the user gets in-game feedback that
                // the 3D mesh path is alive — wings/cape render BEHIND the
                // player so first-person players see nothing without F5.
                String parts = (wantWings ? "Wings " : "")
                             + (wantAngel ? "AngelWings " : "")
                             + (wantCape  ? "Cape "  : "");
                flashToast("§a✦ §f" + parts.trim() + " §arendering — §epress F5");
            }
        } catch (Throwable t) {
            logOnce("renderCosmetics3D", t);
            cosmFailOnce(7, "render threw: " + t);
        }
    }

    /** Cached single-layer flush method on VCP$Immediate — class_4598's
     *  {@code method_22994(class_1921)}. Resolved on first cape draw. */
    private static Method shadowhud$drawSingleLayer;
    private static boolean shadowhud$drawSingleLayerResolveTried;

    /** Best-effort flush of a single render layer's buffer. Looks up
     *  {@code class_4598.method_22994(class_1921)} (the new single-layer
     *  draw method in 1.21.11) on the provider and invokes it. Silent
     *  no-op if the provider isn't an Immediate VCP or the method
     *  doesn't exist — vanilla's auto-flush still runs after AFTER_ENTITIES. */
    private static void shadowhud$flushLayer(Object provider, Object layer) {
        if (provider == null || layer == null) return;
        try {
            if (!shadowhud$drawSingleLayerResolveTried) {
                shadowhud$drawSingleLayerResolveTried = true;
                Class<?> rl = Class.forName("net.minecraft.class_1921");
                for (Method m : provider.getClass().getMethods()) {
                    if (m.getParameterCount() != 1) continue;
                    if (!rl.isAssignableFrom(m.getParameterTypes()[0])) continue;
                    if (m.getReturnType() != void.class) continue;
                    String n = m.getName();
                    if (n.equals("method_22994") || n.equals("draw")
                        || n.equals("method_73476")) {
                        shadowhud$drawSingleLayer = m;
                        System.out.println("[ShadowHud][TexCape] flush method resolved: "
                            + n + " on " + provider.getClass().getSimpleName());
                        break;
                    }
                }
            }
            if (shadowhud$drawSingleLayer != null) {
                shadowhud$drawSingleLayer.invoke(provider, layer);
            }
        } catch (Throwable ignored) {}
    }

    /** Per-player cosmetic draw — called from {@link #renderCosmetics3D}
     *  once per visible player (self + nearby remotes). Reads the player's
     *  position and body-yaw, computes camera-relative offset, then draws
     *  whichever cosmetics the user has enabled. Wrapping each player's
     *  draw in its own try/catch keeps a single bad entity (corrupt yaw
     *  data, missing intermediary, etc.) from bringing down the entire
     *  render pass. */
    private static void drawCosmeticsForPlayer(Object p,
            double cx, double cy, double cz, long now,
            Object matrices, Object provider, Object buffer,
            Object vertexArg, Object normalArg,
            boolean wantWings, boolean wantAngel, boolean wantCape,
            boolean wantWingsSolid, boolean wantAngelSolid) {
        try {
            double px = firstNum(p, "method_23317", "getX").doubleValue();
            double py = firstNum(p, "method_23318", "getY").doubleValue();
            double pz = firstNum(p, "method_23321", "getZ").doubleValue();
            float yaw;
            try {
                yaw = firstNum(p, "method_5791", "getBodyYaw",
                    "method_36454", "getYRot", "getYaw").floatValue();
            } catch (Throwable t) { yaw = 0f; }

            double dx = px - cx, dy = py - cy, dz = pz - cz;
            float yawRad = (float) Math.toRadians(180.0 - yaw);
            float yawC = (float) Math.cos(yawRad);
            float yawS = (float) Math.sin(yawRad);
            final float SHOULDER_PIVOT = 1.55f;

            if (wantWings && buffer != null) {
                float flap = (float) (Math.sin(now / 240.0) * 0.55);
                cosmDrawShape(buffer, vertexArg, normalArg, WING_3D_SHAPE, true,
                    (float) Math.cos(flap), (float) Math.sin(flap), SHOULDER_PIVOT,
                    yawC, yawS, dx, dy, dz, 25, 10, 30);
            }
            if (wantAngel && buffer != null) {
                float flap = (float) (Math.sin(now / 600.0) * 0.18);
                cosmDrawShape(buffer, vertexArg, normalArg, ANGEL_3D_SHAPE, true,
                    (float) Math.cos(flap), (float) Math.sin(flap), SHOULDER_PIVOT,
                    yawC, yawS, dx, dy, dz, 240, 240, 255);
            }
            // ---- Solid (filled triangle-fan) variants — independent of
            //      the wireframe path. Use cosmSolidLayer (1×1 white texture)
            //      with vertex-color modulation. Failures are logged once
            //      but don't disturb the wireframe / particle paths.
            //
            //      We also run the solid path for the PLAIN Wings / AngelWings
            //      modules. The wireframe line path is fragile on 1.21.11
            //      (Sodium can return a null buffer for cosmLineLayer; the
            //      VertexConsumer normal() variants moved around), so without
            //      this fallback the user could enable AngelWings and see
            //      absolutely nothing. With it, the solid mesh always renders
            //      whenever the layer + vertex handles resolved — and if the
            //      wireframe DOES render, the line overlay just sits on top
            //      (barely visible against the filled surface, no harm done).
            if ((wantWingsSolid || wantWings) && cosmSolidLayer != null
                    && cosmGetBuffer != null && cosmVtxPos != null
                    && cosmVtxTexture != null && cosmVtxColor != null) {
                try {
                    Object solidBuf = cosmGetBuffer.invoke(provider, cosmSolidLayer);
                    if (solidBuf != null) {
                        float flap = (float) (Math.sin(now / 240.0) * 0.55);
                        cosmDrawSolidPolygon(solidBuf, vertexArg, normalArg,
                            WING_3D_SHAPE, true,
                            (float) Math.cos(flap), (float) Math.sin(flap),
                            SHOULDER_PIVOT, yawC, yawS, dx, dy, dz, 60, 20, 70);
                        shadowhud$flushLayer(provider, cosmSolidLayer);
                    }
                } catch (Throwable t) { logOnce("WingsSolid", t); }
            }
            if ((wantAngelSolid || wantAngel) && cosmSolidLayer != null
                    && cosmGetBuffer != null && cosmVtxPos != null
                    && cosmVtxTexture != null && cosmVtxColor != null) {
                try {
                    Object solidBuf = cosmGetBuffer.invoke(provider, cosmSolidLayer);
                    if (solidBuf != null) {
                        float flap = (float) (Math.sin(now / 600.0) * 0.18);
                        cosmDrawSolidPolygon(solidBuf, vertexArg, normalArg,
                            ANGEL_3D_SHAPE, true,
                            (float) Math.cos(flap), (float) Math.sin(flap),
                            SHOULDER_PIVOT, yawC, yawS, dx, dy, dz, 240, 240, 255);
                        shadowhud$flushLayer(provider, cosmSolidLayer);
                    }
                } catch (Throwable t) { logOnce("AngelWingsSolid", t); }
            }
            // Solid-cape fallback: regardless of whether the textured cape
            // path resolves (cosmCapeLayer1/2 + cosmVtxTexture) we also
            // render the cape silhouette as a filled polygon on
            // cosmSolidLayer. The textured cape layers occasionally don't
            // come back from Sodium's VCP, and the wireframe line fallback
            // is invisible against most backgrounds (8/5/8 RGB). This way
            // the user always sees a brand-red cape when Cape is on, even
            // if every other path is broken. If the textured path DID
            // resolve, both render and the texture sits on top (the solid
            // is hidden behind the textured quad — same z, identical shape).
            if (wantCape && cosmSolidLayer != null
                    && cosmGetBuffer != null && cosmVtxPos != null
                    && cosmVtxTexture != null && cosmVtxColor != null) {
                try {
                    Object solidBuf = cosmGetBuffer.invoke(provider, cosmSolidLayer);
                    if (solidBuf != null) {
                        float sway = (float) (Math.sin(now / 720.0) * 0.18);
                        cosmDrawSolidPolygon(solidBuf, vertexArg, normalArg,
                            CAPE_3D_SHAPE, false,
                            (float) Math.cos(sway), (float) Math.sin(sway),
                            SHOULDER_PIVOT, yawC, yawS, dx, dy, dz, 175, 18, 26);
                        shadowhud$flushLayer(provider, cosmSolidLayer);
                    }
                } catch (Throwable t) { logOnce("CapeSolid", t); }
            }
            if (wantCape) {
                float sway = (float) (Math.sin(now / 720.0) * 0.18);
                Object pickedLayer = modOn("CapeAlt", false)
                    ? cosmCapeLayer2 : cosmCapeLayer1;
                if (pickedLayer != null && cosmVtxTexture != null) {
                    try {
                        Object capeBuffer = cosmGetBuffer.invoke(provider, pickedLayer);
                        if (capeBuffer != null) {
                            cosmDrawCapeQuad(capeBuffer, vertexArg, normalArg,
                                yawC, yawS, dx, dy, dz, (float) Math.toDegrees(sway));
                            // Safety: explicitly flush the cape layer's buffer.
                            // MC's auto-flush at AFTER_ENTITIES end calls
                            // consumers.draw() (no-arg) which SHOULD include
                            // custom layers, but on some Sodium / VCP-impl
                            // setups arbitrary layers don't make it. The
                            // single-layer overload (method_22994) is a safe
                            // belt-and-suspenders flush.
                            shadowhud$flushLayer(provider, pickedLayer);
                            if (!shadowhud$capeFirstDrawLogged) {
                                shadowhud$capeFirstDrawLogged = true;
                                System.out.println("[ShadowHud][TexCape] drew first quad — buf="
                                    + capeBuffer.getClass().getSimpleName()
                                    + " layer=" + pickedLayer.getClass().getSimpleName()
                                    + " vtx=" + cosmVtxPos.getName()
                                    + " color=" + cosmVtxColor.getName()
                                    + " tex=" + cosmVtxTexture.getName()
                                    + " overlay=" + (cosmVtxOverlay != null ? cosmVtxOverlay.getName() : "NULL")
                                    + " light=" + (cosmVtxLight != null ? cosmVtxLight.getName() : "NULL")
                                    + " normal=" + (cosmVtxNormal != null ? cosmVtxNormal.getName() : "NULL"));
                            }
                        } else {
                            cosmFailOnce(8, "capeBuffer is null after getBuffer");
                        }
                    } catch (Throwable t) {
                        cosmFailOnce(9, "cape draw threw: " + t);
                    }
                } else {
                    if (pickedLayer == null) cosmFailOnce(10, "pickedLayer is null — resolveCapeTextures didn't build a layer");
                    if (cosmVtxTexture == null) cosmFailOnce(11, "cosmVtxTexture is null — UV method not resolved");
                    // FALLBACK: line silhouette. Skip if we don't even
                    // have the line buffer (line layer also failed) —
                    // calling cosmDrawShape with null buffer would NPE.
                    if (buffer != null) {
                        float sC = (float) Math.cos(sway), sS = (float) Math.sin(sway);
                        try {
                            cosmDrawShape(buffer, vertexArg, normalArg, CAPE_3D_SHAPE, false,
                                sC, sS, SHOULDER_PIVOT, yawC, yawS, dx, dy, dz, 8, 5, 8);
                            for (float[] a : CAPE_3D_ACCENTS) {
                                cosmLine(buffer, vertexArg, normalArg, a[0], a[1], -0.30f, a[2], a[3], -0.30f,
                                    sC, sS, SHOULDER_PIVOT, yawC, yawS, dx, dy, dz, 175, 18, 26);
                            }
                            cosmLine(buffer, vertexArg, normalArg, -0.36f, 1.55f, -0.30f, 0.36f, 1.55f, -0.30f,
                                sC, sS, SHOULDER_PIVOT, yawC, yawS, dx, dy, dz, 255, 32, 48);
                        } catch (Throwable ignored) {}
                    } else {
                        cosmFailOnce(14, "Cape: BOTH texture AND line paths unavailable — no buffer");
                    }
                }
            }
        } catch (Throwable t) {
            // Per-player failure — don't fail the whole render pass, just log once.
            logOnce("drawCosmeticsForPlayer", t);
        }
    }

    /** First-time resolve all the reflection handles needed to write line
     *  geometry into a VertexConsumerProvider. Cached for the rest of the
     *  session — per-frame cost is just the actual invocations.
     *
     *  The 1.21.11 build does NOT expose {@code class_1921.method_23594} /
     *  {@code RenderLayer.getLines()} as a public no-arg static method (the
     *  1.21.5+ render-pipeline refactor moved it). We probe a wider set of
     *  intermediary names, fall back to static fields, and last-resort fuzzy
     *  match any static method whose name suggests "line". We also probe the
     *  VertexConsumer API independently of finding a line layer — vtx/color/
     *  normal exist on every render layer's buffer, so we just need ANY layer
     *  to acquire a VC and reflect off it. */
    private static void resolveCosmeticHandles(Object matrices, Object provider) {
        if (cosmLineLayer != null && cosmVtxPos != null) return;
        if (cosmResolveTried)      return;
        cosmResolveTried = true;
        try {
            // MatrixStack.peek() — returns an Entry holding the position matrix
            for (Method m : matrices.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                String n = m.getName();
                if (n.equals("method_23760") || n.equals("peek")) {
                    cosmPeekStack = m; break;
                }
            }
            Class<?> rl = Class.forName("net.minecraft.class_1921");

            // VCP.getBuffer(RenderLayer) — needed before we can probe a buffer.
            for (Method m : provider.getClass().getMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (!rl.isAssignableFrom(m.getParameterTypes()[0])) continue;
                cosmGetBuffer = m; break;
            }

            // === 1.21.11 lines layer — moved to class_12249 ====================
            // In 1.21.5+ MC's render-pipeline rewrite, RenderLayer (class_1921)
            // lost its static factory methods. The pre-built layer instances
            // now live in class_12249 ("RenderLayers" registry). Verified by
            // disassembling client-intermediary.jar:
            //   - public static field   class_12249.field_64042  → "lines" layer
            //   - public static method  class_12249.method_76015() returns same
            // Try both paths; whichever resolves first wins.
            try {
                Class<?> renderLayers = Class.forName("net.minecraft.class_12249");
                if (cosmLineLayer == null) {
                    try {
                        Field f = renderLayers.getField("field_64042");
                        Object v = f.get(null);
                        if (v != null && rl.isInstance(v)) {
                            cosmLineLayer = v;
                            System.out.println("[ShadowHud][Cosmetic3D] line layer via class_12249.field_64042 (1.21.11 path)");
                        }
                    } catch (Throwable ignored) {}
                }
                if (cosmLineLayer == null) {
                    try {
                        Method m = renderLayers.getMethod("method_76015");
                        if (Modifier.isStatic(m.getModifiers()) && rl.isAssignableFrom(m.getReturnType())) {
                            cosmLineLayer = m.invoke(null);
                            if (cosmLineLayer != null) {
                                System.out.println("[ShadowHud][Cosmetic3D] line layer via class_12249.method_76015() (1.21.11 path)");
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (ClassNotFoundException ignored) {
                // class_12249 doesn't exist on pre-1.21.5 — fall through to legacy paths below
            }
            // posMat method on the entry
            if (cosmPeekStack != null) {
                Object entry = cosmPeekStack.invoke(matrices);
                for (Method m : entry.getClass().getMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    String n = m.getName();
                    if (n.equals("method_23761") || n.equals("getPositionMatrix") || n.equals("pose")) {
                        cosmGetPosMat = m; break;
                    }
                }
            }

            // === RenderLayer.getLines() resolution — broader candidate set ===
            // 1) Standard intermediary + named for getLines()
            String[] lineNoArg = {
                "method_23594", "getLines", "lines",
                "method_23589", "method_23593", "method_23595",
                "method_23596", "method_23597", "method_23598",
                "method_29632", "method_29633"
            };
            for (String name : lineNoArg) {
                if (cosmLineLayer != null) break;
                try {
                    Method m = rl.getMethod(name);
                    if (Modifier.isStatic(m.getModifiers())
                        && m.getParameterCount() == 0
                        && rl.isAssignableFrom(m.getReturnType())) {
                        cosmLineLayer = m.invoke(null);
                        if (cosmLineLayer != null) {
                            System.out.println("[ShadowHud][Cosmetic3D] line layer via static() " + name);
                        }
                    }
                } catch (Throwable ignored) {}
            }
            // 2) Static fields holding a RenderLayer (e.g. LINES, DEBUG_LINES)
            if (cosmLineLayer == null) {
                for (Field f : rl.getFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    if (!rl.isAssignableFrom(f.getType())) continue;
                    String n = f.getName().toUpperCase();
                    if (!n.contains("LINE")) continue;
                    try {
                        Object v = f.get(null);
                        if (v != null) {
                            cosmLineLayer = v;
                            System.out.println("[ShadowHud][Cosmetic3D] line layer via field " + f.getName());
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            // 3) getDebugLineStrip(double width) — takes a single double arg
            if (cosmLineLayer == null) {
                String[] lineDoubleArg = { "method_29630", "method_29631", "getDebugLineStrip" };
                for (String name : lineDoubleArg) {
                    if (cosmLineLayer != null) break;
                    try {
                        Method m = rl.getMethod(name, double.class);
                        if (Modifier.isStatic(m.getModifiers())
                            && rl.isAssignableFrom(m.getReturnType())) {
                            cosmLineLayer = m.invoke(null, 2.0d);
                            if (cosmLineLayer != null) {
                                System.out.println("[ShadowHud][Cosmetic3D] line layer via " + name + "(2.0)");
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
            // 4) Last-resort fuzzy match — any static no-arg method whose name
            //    suggests "line" and returns a RenderLayer subtype.
            if (cosmLineLayer == null) {
                for (Method m : rl.getMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (!rl.isAssignableFrom(m.getReturnType())) continue;
                    String n = m.getName().toLowerCase();
                    if (!n.contains("line")) continue;
                    try {
                        Object v = m.invoke(null);
                        if (v != null) {
                            cosmLineLayer = v;
                            System.out.println("[ShadowHud][Cosmetic3D] line layer via fuzzy " + m.getName());
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            // 5) Absolute fallback — ANY static no-arg method returning a
            //    RenderLayer. We'd rather render quads than nothing, and this
            //    guarantees we get *some* buffer for VertexConsumer probing.
            if (cosmLineLayer == null) {
                for (Method m : rl.getMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (!rl.isAssignableFrom(m.getReturnType())) continue;
                    try {
                        Object v = m.invoke(null);
                        if (v != null) {
                            cosmLineLayer = v;
                            System.out.println("[ShadowHud][Cosmetic3D] line layer fallback to " + m.getName());
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
            }
            // Diagnostic dump of static no-arg returners so the next launch
            // log tells us exactly what 1.21.11 exposes if everything above
            // still fails.
            if (cosmLineLayer == null) {
                StringBuilder dump = new StringBuilder("[ShadowHud][Cosmetic3D] class_1921 statics:");
                int c = 0;
                for (Method m : rl.getMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    dump.append(' ').append(m.getName()).append(':').append(m.getReturnType().getSimpleName());
                    if (++c > 40) { dump.append(" ..."); break; }
                }
                System.out.println(dump.toString());
            }

            // === VertexConsumer probing — independent of lineLayer success ===
            // CRITICAL FIX (May 2026): Previous version required a non-null
            // buffer from cosmGetBuffer.invoke(provider, cosmLineLayer) before
            // it could probe vertex methods. But Sodium's VCP impl returns
            // null for the lines layer in some configurations (verified in
            // 14:08:45 log: "vtx=false/0p ... step 6 fail: ALL handles
            // missing"). Result: probe loop never ran, all handles stayed
            // null, wings fell back to particles, cape never rendered.
            //
            // Fix: probe class_4588 (VertexConsumer) interface DIRECTLY via
            // Class.forName. The interface's abstract methods are stable
            // intermediary names — Method handles obtained from the
            // interface dispatch virtually to the concrete impl when invoked
            // on a real buffer at render time. No buffer instance needed
            // for method discovery.
            Class<?> vcCls = null;
            try {
                vcCls = Class.forName("net.minecraft.class_4588");
                System.out.println("[ShadowHud][Cosmetic3D] probing VertexConsumer interface class_4588 directly");
            } catch (ClassNotFoundException e) {
                // Fallback: try acquiring a buffer (legacy path)
                Object probeBuffer = null;
                if (cosmGetBuffer != null && cosmLineLayer != null) {
                    try { probeBuffer = cosmGetBuffer.invoke(provider, cosmLineLayer); }
                    catch (Throwable ignored) {}
                }
                if (probeBuffer != null) vcCls = probeBuffer.getClass();
                System.err.println("[ShadowHud][Cosmetic3D] class_4588 not found, fallback probeBuffer=" + (probeBuffer != null));
            }
            if (vcCls != null) {
                // 1.21.11 intermediary refresh — disassembled class_4588:
                //   vertex(Entry, x, y, z)        method_56824   (NEW in 1.21.5+)
                //   vertex(Matrix4fc, x, y, z)    method_22918   (legacy)
                //   vertex(x, y, z)               method_22912   (primary, 3-arg)
                //   color(int r, g, b, a)         method_1336    (1.21.11 int-color)
                //   color(float r, g, b, a)       method_22915   (default float-color)
                //   normal(Entry, x, y, z)        method_60831   (NEW in 1.21.5+)
                //   normal(x, y, z)               method_22914   (primary, 3-arg)
                for (Method m : vcCls.getMethods()) {
                    Class<?>[] pt = m.getParameterTypes();
                    String n = m.getName();
                    // vertex(Entry|Matrix4fc, float, float, float) — 4-param
                    if (cosmVtxPos == null
                        && pt.length == 4 && pt[1] == float.class && pt[2] == float.class
                        && pt[3] == float.class
                        && (n.equals("method_22918") || n.equals("method_56824")
                            || n.equals("vertex"))) {
                        cosmVtxPos = m;
                    }
                    // vertex(float, float, float) — 3-param fallback (1.21.5+
                    // streamlined API). Only use if 4-param didn't resolve.
                    if (cosmVtxPos == null
                        && pt.length == 3 && pt[0] == float.class && pt[1] == float.class
                        && pt[2] == float.class
                        && (n.equals("method_22912") || n.equals("vertex"))) {
                        cosmVtxPos = m;
                    }
                    // color(int, int, int, int) — primary in 1.21.11.
                    if (cosmVtxColor == null
                        && pt.length == 4 && pt[0] == int.class && pt[1] == int.class
                        && pt[2] == int.class && pt[3] == int.class
                        && (n.equals("method_22915") || n.equals("method_1336")
                            || n.equals("color"))) {
                        cosmVtxColor = m;
                    }
                    // normal(Entry, float, float, float) — 4-param
                    if (cosmVtxNormal == null
                        && pt.length == 4 && pt[1] == float.class && pt[2] == float.class
                        && pt[3] == float.class
                        && (n.equals("method_23763") || n.equals("method_60831")
                            || n.equals("normal"))) {
                        cosmVtxNormal = m;
                    }
                    // normal(float, float, float) — 3-param fallback
                    if (cosmVtxNormal == null
                        && pt.length == 3 && pt[0] == float.class && pt[1] == float.class
                        && pt[2] == float.class
                        && (n.equals("method_23764") || n.equals("method_22914")
                            || n.equals("normal"))) {
                        cosmVtxNormal = m;
                    }
                    // texture(u, v) — 1.21.11: method_22913
                    if (cosmVtxTexture == null
                        && pt.length == 2 && pt[0] == float.class && pt[1] == float.class
                        && (n.equals("method_22913") || n.equals("texture")
                            || n.equals("uv"))) {
                        cosmVtxTexture = m;
                    }
                    // overlay(u, v) — 1.21.11: method_60796
                    if (cosmVtxOverlay == null
                        && pt.length == 2 && pt[0] == int.class && pt[1] == int.class
                        && (n.equals("method_60796") || n.equals("overlay"))) {
                        cosmVtxOverlay = m;
                    }
                    // light(u, v) — 1.21.11: method_22921
                    if (cosmVtxLight == null
                        && pt.length == 2 && pt[0] == int.class && pt[1] == int.class
                        && (n.equals("method_22921") || n.equals("light"))
                        && cosmVtxOverlay != m) {
                        cosmVtxLight = m;
                    }
                }
            }
            if (!cosmResolveLogged) {
                cosmResolveLogged = true;
                System.out.println("[ShadowHud][Cosmetic3D] resolve — peek=" + (cosmPeekStack != null)
                    + " posMat=" + (cosmGetPosMat != null)
                    + " getBuffer=" + (cosmGetBuffer != null)
                    + " lineLayer=" + (cosmLineLayer != null)
                    + " vtx=" + (cosmVtxPos != null ? cosmVtxPos.getName() + "/" + cosmVtxPos.getParameterCount() + "p" : "NULL")
                    + " color=" + (cosmVtxColor != null ? cosmVtxColor.getName() : "NULL")
                    + " texture=" + (cosmVtxTexture != null ? cosmVtxTexture.getName() : "NULL")
                    + " overlay=" + (cosmVtxOverlay != null ? cosmVtxOverlay.getName() : "NULL")
                    + " light=" + (cosmVtxLight != null ? cosmVtxLight.getName() : "NULL")
                    + " normal=" + (cosmVtxNormal != null ? cosmVtxNormal.getName() + "/" + cosmVtxNormal.getParameterCount() + "p" : "NULL"));

                // If vertex method probe failed entirely, dump available
                // methods so next log tells us why. Only emit on failure
                // to keep the noise down.
                if (cosmVtxPos == null && vcCls != null) {
                    StringBuilder dump = new StringBuilder("[ShadowHud][Cosmetic3D] " + vcCls.getName() + " methods (probe failed):");
                    int c = 0;
                    for (Method m : vcCls.getMethods()) {
                        Class<?>[] pt = m.getParameterTypes();
                        StringBuilder sig = new StringBuilder();
                        for (Class<?> p : pt) sig.append(p.getSimpleName()).append(',');
                        dump.append(' ').append(m.getName()).append('(').append(sig).append(')');
                        if (++c > 30) { dump.append(" ..."); break; }
                    }
                    System.out.println(dump.toString());
                }
            }
        } catch (Throwable t) {
            System.err.println("[ShadowHud][Cosmetic3D] resolve failed: " + t);
        }
    }

    /** Cached camera object — walked once via mc.gameRenderer.camera. */
    private static Object  cosmCachedCamera;
    private static boolean cosmCameraResolveTried;

    /** Resolve mc.gameRenderer.camera once and cache. The Camera instance
     *  itself is stable for the session — only its position changes. */
    private static Object resolveCamera() {
        if (cosmCachedCamera != null) return cosmCachedCamera;
        if (cosmCameraResolveTried)   return null;
        cosmCameraResolveTried = true;
        if (mc == null) return null;
        try {
            Class<?> mcCls = mc.getClass();
            Object gr = null;
            // mc.gameRenderer field — try public access then declared
            Field grf = null;
            try { grf = mcCls.getField("field_1773"); }
            catch (NoSuchFieldException e) {
                try { grf = mcCls.getField("gameRenderer"); } catch (NoSuchFieldException ignored) {}
            }
            if (grf == null) {
                grf = findFieldUp(mcCls, "field_1773");
                if (grf == null) grf = findFieldUp(mcCls, "gameRenderer");
            }
            if (grf != null) { grf.setAccessible(true); gr = grf.get(mc); }
            if (gr == null) return null;
            // gameRenderer.camera field
            Field cf = findFieldUp(gr.getClass(), "field_4137");
            if (cf == null) cf = findFieldUp(gr.getClass(), "camera");
            if (cf != null) {
                cf.setAccessible(true);
                cosmCachedCamera = cf.get(gr);
            }
        } catch (Throwable t) {
            System.err.println("[ShadowHud][Cosmetic3D] camera resolve failed: " + t);
        }
        return cosmCachedCamera;
    }

    /** Walk a closed polyline shape, optionally mirroring x for symmetric wings. */
    private static void cosmDrawShape(Object buffer, Object vertexArg, Object normalArg,
            float[][] shape,
            boolean mirrored,
            float flapC, float flapS, float pivot,
            float yawC, float yawS,
            double dx, double dy, double dz,
            int r, int g, int b) throws Exception {
        int sides = mirrored ? 2 : 1;
        for (int s = 0; s < sides; s++) {
            int side = mirrored ? (s == 0 ? -1 : 1) : 1;
            for (int i = 0; i < shape.length; i++) {
                float[] a  = shape[i];
                float[] bp = shape[(i + 1) % shape.length];
                cosmLine(buffer, vertexArg, normalArg,
                    side * a[0],  a[1],  a[2],
                    side * bp[0], bp[1], bp[2],
                    flapC, flapS, pivot, yawC, yawS, dx, dy, dz, r, g, b);
            }
        }
    }

    /** Draw a single line segment with explicit RGB.
     *  {@code vertexArg} / {@code normalArg} are whichever first-arg the
     *  resolved vertex()/normal() methods expect (Matrix4f for 1.20-,
     *  MatrixStack.Entry for 1.21+). For the 1.21.5+ streamlined API where
     *  vertex/normal only have 3-param overloads (no matrix arg), the matrix
     *  must already be applied to coords by the caller — we just write floats. */
    private static void cosmLine(Object buf, Object vertexArg, Object normalArg,
            float ax, float ay, float az, float bx, float by, float bz,
            float flapC, float flapS, float pivot, float yawC, float yawS,
            double dx, double dy, double dz, int r, int g, int b) throws Exception {
        float[] p1 = cosmTransform(ax, ay, az, flapC, flapS, pivot, yawC, yawS);
        float[] p2 = cosmTransform(bx, by, bz, flapC, flapS, pivot, yawC, yawS);
        float fx = p2[0] - p1[0], fy = p2[1] - p1[1], fz = p2[2] - p1[2];
        float len = (float) Math.sqrt(fx*fx + fy*fy + fz*fz);
        if (len < 1e-5f) len = 1f;
        float nx = fx / len, ny = fy / len, nz = fz / len;
        boolean vtx4 = cosmVtxPos.getParameterCount() == 4;
        boolean nrm4 = cosmVtxNormal != null && cosmVtxNormal.getParameterCount() == 4;

        Object v;
        if (vtx4) {
            v = cosmVtxPos.invoke(buf, vertexArg,
                (float) (dx + p1[0]), (float) (dy + p1[1]), (float) (dz + p1[2]));
        } else {
            v = cosmVtxPos.invoke(buf,
                (float) (dx + p1[0]), (float) (dy + p1[1]), (float) (dz + p1[2]));
        }
        v = cosmVtxColor.invoke(v != null ? v : buf, r, g, b, 255);
        if (cosmVtxNormal != null) {
            if (nrm4) cosmVtxNormal.invoke(v != null ? v : buf, normalArg, nx, ny, nz);
            else      cosmVtxNormal.invoke(v != null ? v : buf, nx, ny, nz);
        }

        if (vtx4) {
            v = cosmVtxPos.invoke(buf, vertexArg,
                (float) (dx + p2[0]), (float) (dy + p2[1]), (float) (dz + p2[2]));
        } else {
            v = cosmVtxPos.invoke(buf,
                (float) (dx + p2[0]), (float) (dy + p2[1]), (float) (dz + p2[2]));
        }
        v = cosmVtxColor.invoke(v != null ? v : buf, r, g, b, 255);
        if (cosmVtxNormal != null) {
            if (nrm4) cosmVtxNormal.invoke(v != null ? v : buf, normalArg, nx, ny, nz);
            else      cosmVtxNormal.invoke(v != null ? v : buf, nx, ny, nz);
        }
    }

    /** Apply flap rotation around the forward axis at the shoulder pivot,
     *  then yaw rotation around Y. Outputs (x, y, z) in player-local space. */
    private static float[] cosmTransform(float lx, float ly, float lz,
            float flapC, float flapS, float pivot,
            float yawC, float yawS) {
        // Flap: rotate (extension, vertical) around the pivot height
        float vy = ly - pivot;
        float fx = lx * flapC - vy * flapS;
        float fy = (lx * flapS + vy * flapC) + pivot;
        float fz = lz;
        // Yaw around Y
        float wx = fx * yawC - fz * yawS;
        float wz = fx * yawS + fz * yawC;
        return new float[]{ wx, fy, wz };
    }

    // ----- mixin-driven cosmetic path (per-entity) ------------------------

    /** Cached intermediary class for player-entity filtering. */
    private static Class<?> shadowhud$playerEntityClass;
    private static boolean  shadowhud$playerEntityClassResolved;
    /** MatrixStack helper handles — push / pop / translate(double,double,double).
     *  Resolved once on first hook call and cached. */
    private static Method   shadowhud$mxPush, shadowhud$mxPop, shadowhud$mxTranslate;
    private static boolean  shadowhud$mxResolved;
    /** Timestamp of the last successful mixin-path render. The world-render
     *  path (renderCosmetics3D) checks this to avoid double-rendering self
     *  in third-person view. */
    private static volatile long shadowhud$mixinLastSelfRenderMs;

    private static boolean shadowhud$isPlayerEntity(Object entity) {
        if (!shadowhud$playerEntityClassResolved) {
            shadowhud$playerEntityClassResolved = true;
            try { shadowhud$playerEntityClass = Class.forName("net.minecraft.class_1657"); }
            catch (Throwable ignored) {}
        }
        return shadowhud$playerEntityClass != null
            && shadowhud$playerEntityClass.isInstance(entity);
    }

    private static void shadowhud$resolveMatrixStackHelpers(Object matrices) {
        if (shadowhud$mxResolved) return;
        shadowhud$mxResolved = true;
        try {
            for (Method m : matrices.getClass().getMethods()) {
                String n = m.getName();
                Class<?>[] pt = m.getParameterTypes();
                if (shadowhud$mxPush == null && pt.length == 0
                    && (n.equals("method_22903") || n.equals("push") || n.equals("pushPose"))) {
                    shadowhud$mxPush = m;
                }
                if (shadowhud$mxPop == null && pt.length == 0
                    && (n.equals("method_22909") || n.equals("pop") || n.equals("popPose"))) {
                    shadowhud$mxPop = m;
                }
                if (shadowhud$mxTranslate == null && pt.length == 3
                    && pt[0] == double.class && pt[1] == double.class && pt[2] == double.class
                    && (n.equals("method_22904") || n.equals("translate"))) {
                    shadowhud$mxTranslate = m;
                }
            }
            // Float-arg translate fallback (some versions only expose float)
            if (shadowhud$mxTranslate == null) {
                for (Method m : matrices.getClass().getMethods()) {
                    String n = m.getName();
                    Class<?>[] pt = m.getParameterTypes();
                    if (pt.length == 3 && pt[0] == float.class && pt[1] == float.class
                        && pt[2] == float.class
                        && (n.equals("method_46416") || n.equals("translate"))) {
                        shadowhud$mxTranslate = m; break;
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * Per-entity cosmetic render entry point — invoked by
     * {@code EntityRenderDispatcherCosmeticMixin} at TAIL of
     * {@code class_898.method_3954}. The matrix stack has already been
     * popped back to its base by the time we run, so we push + translate
     * to the entity's offset ({@code x},{@code y},{@code z}) ourselves.
     *
     * <p>This path runs for EVERY player MC just rendered, so we get
     * cosmetics on remote players too — the {@code WorldRenderEvents}
     * path could only see {@code mc.player}. Double-rendering self is
     * suppressed via {@code shadowhud$mixinLastSelfRenderMs}.</p>
     */
    public static void cosmeticMeshHook(Object entity,
                                         double x, double y, double z,
                                         float yaw, float tickDelta,
                                         Object matrices, Object provider, int light) {
        if (entity == null || matrices == null || provider == null) return;
        if (!shadowhud$isPlayerEntity(entity)) return;

        boolean wantWings = modOn("Wings",      false);
        boolean wantAngel = modOn("AngelWings", false);
        boolean wantCape  = modOn("Cape",       false);
        if (!wantWings && !wantAngel && !wantCape) return;

        // Reuse the world-render-path resolver — it caches handles across
        // all callers, so the second invocation is just nullity checks.
        resolveCosmeticHandles(matrices, provider);
        if (cosmLineLayer == null || cosmGetBuffer == null
            || cosmPeekStack == null || cosmGetPosMat == null
            || cosmVtxPos == null || cosmVtxColor == null) return;

        shadowhud$resolveMatrixStackHelpers(matrices);
        if (shadowhud$mxPush == null || shadowhud$mxPop == null
            || shadowhud$mxTranslate == null) return;

        try {
            shadowhud$mxPush.invoke(matrices);
            try {
                // translate(double,double,double) preferred; float fallback.
                if (shadowhud$mxTranslate.getParameterTypes()[0] == double.class) {
                    shadowhud$mxTranslate.invoke(matrices, x, y, z);
                } else {
                    shadowhud$mxTranslate.invoke(matrices, (float) x, (float) y, (float) z);
                }

                // Body yaw — what the cosmetics turn with. Falls back to the
                // dispatcher's yaw arg if intermediaries don't match.
                float bodyYaw = yaw;
                try {
                    bodyYaw = firstNum(entity,
                        "method_5791", "getBodyYaw",
                        "method_36454", "getYRot", "getYaw").floatValue();
                } catch (Throwable ignored) {}

                float yawRad = (float) Math.toRadians(180.0 - bodyYaw);
                float yawC = (float) Math.cos(yawRad);
                float yawS = (float) Math.sin(yawRad);
                final float SHOULDER_PIVOT = 1.55f;

                Object entry  = cosmPeekStack.invoke(matrices);
                Object posMat = cosmGetPosMat.invoke(entry);
                Object buffer = cosmGetBuffer.invoke(provider, cosmLineLayer);
                if (buffer == null) return;
                Object vertexArg = (cosmVtxPos.getParameterTypes().length > 0
                    && cosmVtxPos.getParameterTypes()[0].isInstance(entry))
                    ? entry : posMat;
                Object normalArg = (cosmVtxNormal != null
                    && cosmVtxNormal.getParameterTypes().length > 0
                    && cosmVtxNormal.getParameterTypes()[0].isInstance(entry))
                    ? entry : posMat;
                long now = System.currentTimeMillis();

                // dx/dy/dz are zero — we already pushed + translated to
                // entity-relative space. cosmDrawShape just lays geometry
                // around the origin.
                if (wantWings) {
                    float flap = (float) (Math.sin(now / 240.0) * 0.55);
                    cosmDrawShape(buffer, vertexArg, normalArg, WING_3D_SHAPE, true,
                        (float) Math.cos(flap), (float) Math.sin(flap), SHOULDER_PIVOT,
                        yawC, yawS, 0, 0, 0, 25, 10, 30);
                }
                if (wantAngel) {
                    float flap = (float) (Math.sin(now / 600.0) * 0.18);
                    cosmDrawShape(buffer, vertexArg, normalArg, ANGEL_3D_SHAPE, true,
                        (float) Math.cos(flap), (float) Math.sin(flap), SHOULDER_PIVOT,
                        yawC, yawS, 0, 0, 0, 240, 240, 255);
                }
                if (wantCape) {
                    float sway = (float) (Math.sin(now / 720.0) * 0.18);
                    float sC = (float) Math.cos(sway), sS = (float) Math.sin(sway);
                    cosmDrawShape(buffer, vertexArg, normalArg, CAPE_3D_SHAPE, false,
                        sC, sS, SHOULDER_PIVOT, yawC, yawS, 0, 0, 0, 8, 5, 8);
                    for (float[] a : CAPE_3D_ACCENTS) {
                        cosmLine(buffer, vertexArg, normalArg,
                            a[0], a[1], -0.30f, a[2], a[3], -0.30f,
                            sC, sS, SHOULDER_PIVOT, yawC, yawS, 0, 0, 0, 175, 18, 26);
                    }
                    cosmLine(buffer, vertexArg, normalArg,
                        -0.36f, 1.55f, -0.30f, 0.36f, 1.55f, -0.30f,
                        sC, sS, SHOULDER_PIVOT, yawC, yawS, 0, 0, 0, 255, 32, 48);
                }

                cosm3DWorking = true;
                // Suppress the world-render path from double-rendering self
                // when the mixin successfully rendered our local player.
                try {
                    Object localPlayer = (mc != null && playerField != null) ? playerField.get(mc) : null;
                    if (localPlayer != null && localPlayer == entity) {
                        shadowhud$mixinLastSelfRenderMs = now;
                    }
                } catch (Throwable ignored) {}

                if (!cosmFirstDrawLogged) {
                    cosmFirstDrawLogged = true;
                    System.out.println("[ShadowHud][Cosmetic3D] mesh first frame via mixin — entity="
                        + entity.getClass().getSimpleName()
                        + " wings=" + wantWings + " angel=" + wantAngel + " cape=" + wantCape);
                }
            } finally {
                try { shadowhud$mxPop.invoke(matrices); } catch (Throwable ignored) {}
            }
        } catch (Throwable t) { logOnce("cosmeticMeshHook", t); }
    }

    // ----- Textured-quad cape (PNG-backed) ---------------------------------

    /** Resolve the texture-backed render layer for the cape PNGs. Probes
     *  every public single-arg static method on {@code class_12249} that
     *  takes an {@code Identifier} and returns a {@code RenderLayer}, then
     *  inspects the returned layer's name (intermediary {@code field_64011})
     *  to identify the {@code entity_cutout_no_cull} accessor. We use
     *  cutout_no_cull rather than translucent because our alpha-keying is
     *  binary (cape pixels solid, background fully transparent) — cutout
     *  preserves crisp pixel edges, translucent would smooth them. */
    private static void resolveCapeTextures(Object provider) {
        if (cosmCapeResolveTried) return;
        cosmCapeResolveTried = true;
        try {
            Class<?> rl = Class.forName("net.minecraft.class_1921");
            Class<?> renderLayers = Class.forName("net.minecraft.class_12249");
            Class<?> idCls = Class.forName("net.minecraft.class_2960");

            // Build Identifiers via class_2960.of(namespace, path) — that
            // factory is method_60655 in 1.21.x or just .of(...) named.
            Method idOf = null;
            for (Method m : idCls.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 2) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                if (m.getParameterTypes()[1] != String.class) continue;
                String n = m.getName();
                if (n.equals("method_60655") || n.equals("of")
                    || n.equals("method_43902")) {
                    idOf = m; break;
                }
            }
            if (idOf == null) {
                // Try the public constructor (older mappings)
                try {
                    java.lang.reflect.Constructor<?> ctor = idCls.getConstructor(String.class, String.class);
                    cosmCapeIdentifier1 = ctor.newInstance("shadowhud", "textures/cape/cape1.png");
                    cosmCapeIdentifier2 = ctor.newInstance("shadowhud", "textures/cape/cape2.png");
                } catch (Throwable ignored) {}
            } else {
                cosmCapeIdentifier1 = idOf.invoke(null, "shadowhud", "textures/cape/cape1.png");
                cosmCapeIdentifier2 = idOf.invoke(null, "shadowhud", "textures/cape/cape2.png");
            }
            if (cosmCapeIdentifier1 == null || cosmCapeIdentifier2 == null) {
                System.err.println("[ShadowHud][TexCape] failed to build Identifiers");
                return;
            }
            System.out.println("[ShadowHud][TexCape] identifiers built: "
                + cosmCapeIdentifier1 + ", " + cosmCapeIdentifier2);

            // Find class_1921's name field for layer identification.
            Field nameField = null;
            for (Field f : rl.getDeclaredFields()) {
                if (f.getType() == String.class && Modifier.isStatic(f.getModifiers()) == false) {
                    nameField = f; break;
                }
            }
            if (nameField != null) nameField.setAccessible(true);

            // Probe each public single-Identifier method, looking for
            // entity_cutout_no_cull (preferred) or entity_translucent.
            Method bestMethod = null;
            String bestName = null;
            for (Method m : renderLayers.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 1) continue;
                if (!idCls.isAssignableFrom(m.getParameterTypes()[0])) continue;
                if (!rl.isAssignableFrom(m.getReturnType())) continue;
                try {
                    Object layer = m.invoke(null, cosmCapeIdentifier1);
                    if (layer == null) continue;
                    String name;
                    if (nameField != null) {
                        try { name = String.valueOf(nameField.get(layer)); }
                        catch (Throwable ignored) { name = layer.toString(); }
                    } else {
                        name = layer.toString();
                    }
                    if (name == null) continue;
                    String lower = name.toLowerCase();
                    // Prefer cutout_no_cull (binary alpha, crisp edges)
                    if (lower.contains("cutout_no_cull")) {
                        bestMethod = m; bestName = name;
                        break;  // perfect match
                    }
                    // Fallback to translucent if no cutout_no_cull found yet
                    if (bestMethod == null && lower.contains("translucent")
                        && !lower.contains("emissive")) {
                        bestMethod = m; bestName = name;
                    }
                } catch (Throwable ignored) {}
            }
            if (bestMethod == null) {
                System.err.println("[ShadowHud][TexCape] no entity_cutout_no_cull / entity_translucent method found");
                return;
            }
            System.out.println("[ShadowHud][TexCape] using " + bestMethod.getName()
                + " (name=" + bestName + ")");
            cosmCapeLayer1 = bestMethod.invoke(null, cosmCapeIdentifier1);
            cosmCapeLayer2 = bestMethod.invoke(null, cosmCapeIdentifier2);
            // Build a SOLID layer with the 1×1 white PNG so wings/angel
            // mesh can render as opaque triangles (vertex color drives the
            // visible color since the texture is just a white pixel).
            try {
                if (idOf != null) {
                    cosmSolidIdentifier = idOf.invoke(null, "shadowhud", "textures/cosm/solid.png");
                } else {
                    java.lang.reflect.Constructor<?> ctor = idCls.getConstructor(String.class, String.class);
                    cosmSolidIdentifier = ctor.newInstance("shadowhud", "textures/cosm/solid.png");
                }
                if (cosmSolidIdentifier != null) {
                    cosmSolidLayer = bestMethod.invoke(null, cosmSolidIdentifier);
                }
            } catch (Throwable t) {
                System.err.println("[ShadowHud][TexCosm] solid layer resolve failed: " + t);
            }
            System.out.println("[ShadowHud][TexCape] layers built: cape1="
                + (cosmCapeLayer1 != null) + " cape2=" + (cosmCapeLayer2 != null)
                + " solid=" + (cosmSolidLayer != null));
        } catch (Throwable t) {
            System.err.println("[ShadowHud][TexCape] resolve threw: " + t);
        }
    }

    /** Draw the cape as a textured quad behind the player. Two triangles
     *  forming a rectangle, anchored at the shoulder line, sagging slightly
     *  with sway animation, sampling the AI-generated cape PNG (alpha-keyed
     *  for the tattered edge to show through as transparent).
     *
     *  <p>Geometry: 4 corners — top-left, top-right, bottom-right, bottom-
     *  left, in player-local space. Top is at shoulder pivot Y=1.55, width
     *  ±0.40. Bottom drops to Y=-0.30, width ±0.40. Z=-0.30 (just behind
     *  the back). Sway tilts the bottom edge forward/backward each frame.</p>
     */
    private static void cosmDrawCapeQuad(Object buffer, Object vertexArg, Object normalArg,
            float yawC, float yawS, double dx, double dy, double dz,
            float swayDeg) throws Exception {
        // Quad corners in player-local space (before yaw rotation).
        // Y: shoulder (1.55) at top, hem (-0.30) at bottom.
        // X: ±0.40 (slightly narrower than vanilla cape).
        // Z: -0.30 (behind back), sway adds Z offset to bottom.
        float swayZ = (float) Math.sin(Math.toRadians(swayDeg)) * 0.20f;
        float[][] quad = {
            // x, y, z, u, v
            { -0.40f,  1.55f, -0.30f,        0.0f, 0.0f }, // TL
            {  0.40f,  1.55f, -0.30f,        1.0f, 0.0f }, // TR
            {  0.40f, -0.30f, -0.30f - swayZ, 1.0f, 1.0f }, // BR
            { -0.40f, -0.30f, -0.30f - swayZ, 0.0f, 1.0f }, // BL
        };
        // Compute normal in player-local space (forward is -Z = into back),
        // then yaw-rotate.
        float nx_local = 0f, ny_local = 0f, nz_local = -1f;
        float nx = nx_local * yawC - nz_local * yawS;
        float nz = nx_local * yawS + nz_local * yawC;
        float ny = ny_local;

        boolean vtx4 = cosmVtxPos.getParameterCount() == 4;
        boolean nrm4 = cosmVtxNormal != null && cosmVtxNormal.getParameterCount() == 4;
        boolean colorIsInt = cosmVtxColor.getParameterTypes()[0] == int.class;

        for (float[] c : quad) {
            float lx = c[0], ly = c[1], lz = c[2];
            // Yaw rotation around Y axis
            float wx = lx * yawC - lz * yawS;
            float wz = lx * yawS + lz * yawC;
            float wy = ly;
            float u = c[3], v = c[4];

            // Position
            Object vc;
            if (vtx4) {
                vc = cosmVtxPos.invoke(buffer, vertexArg,
                    (float) (dx + wx), (float) (dy + wy), (float) (dz + wz));
            } else {
                vc = cosmVtxPos.invoke(buffer,
                    (float) (dx + wx), (float) (dy + wy), (float) (dz + wz));
            }
            Object on = vc != null ? vc : buffer;
            // Color (white = no tint, 255 alpha)
            if (colorIsInt) {
                on = cosmVtxColor.invoke(on, 255, 255, 255, 255);
            } else {
                on = cosmVtxColor.invoke(on, 1.0f, 1.0f, 1.0f, 1.0f);
            }
            if (on == null) on = buffer;
            // Texture UV
            if (cosmVtxTexture != null) {
                on = cosmVtxTexture.invoke(on, u, v);
                if (on == null) on = buffer;
            }
            // Overlay (no overlay = OverlayTexture.DEFAULT_UV which is 655370 = 0xA000A)
            if (cosmVtxOverlay != null) {
                on = cosmVtxOverlay.invoke(on, 0, 10);
                if (on == null) on = buffer;
            }
            // Light (full bright = 0xF000F0)
            if (cosmVtxLight != null) {
                on = cosmVtxLight.invoke(on, 0xF0, 0xF0);
                if (on == null) on = buffer;
            }
            // Normal
            if (cosmVtxNormal != null) {
                if (nrm4) cosmVtxNormal.invoke(on, normalArg, nx, ny, nz);
                else      cosmVtxNormal.invoke(on, nx, ny, nz);
            }
        }
    }

    /** Emit a single quad (4 vertices) with vertex color, sampling UV(0,0)
     *  on the solid layer texture. For triangles use a degenerate quad
     *  where the 4th vertex equals the 3rd. Coordinates are in player-local
     *  space — yaw + flap rotation applied here. */
    private static void cosmEmitQuad(Object buffer, Object vertexArg, Object normalArg,
            float[] a, float[] b, float[] c, float[] d,
            float flapC, float flapS, float pivot,
            float yawC, float yawS,
            double dx, double dy, double dz,
            int r, int g, int bCol) throws Exception {
        if (buffer == null || cosmVtxPos == null) return;
        boolean vtx4 = cosmVtxPos.getParameterCount() == 4;
        boolean nrm4 = cosmVtxNormal != null && cosmVtxNormal.getParameterCount() == 4;
        boolean colorIsInt = cosmVtxColor != null
                          && cosmVtxColor.getParameterTypes()[0] == int.class;
        // Cape-style normal: pointing back/up
        float nx_local = 0f, ny_local = 0.3f, nz_local = -0.95f;
        float nx = nx_local * yawC - nz_local * yawS;
        float nz = nx_local * yawS + nz_local * yawC;
        float ny = ny_local;
        float[][] vs = {a, b, c, d};
        for (float[] v : vs) {
            // Apply flap rotation around shoulder-Y pivot, then yaw.
            float[] tx = cosmTransform(v[0], v[1], v[2],
                    flapC, flapS, pivot, yawC, yawS);
            Object vc;
            if (vtx4) {
                vc = cosmVtxPos.invoke(buffer, vertexArg,
                        (float) (dx + tx[0]),
                        (float) (dy + tx[1]),
                        (float) (dz + tx[2]));
            } else {
                vc = cosmVtxPos.invoke(buffer,
                        (float) (dx + tx[0]),
                        (float) (dy + tx[1]),
                        (float) (dz + tx[2]));
            }
            Object on = vc != null ? vc : buffer;
            // Vertex color (this drives the wing color since texture is white)
            if (cosmVtxColor != null) {
                if (colorIsInt) on = cosmVtxColor.invoke(on, r, g, bCol, 255);
                else            on = cosmVtxColor.invoke(on, r / 255f, g / 255f, bCol / 255f, 1.0f);
                if (on == null) on = buffer;
            }
            // UV — sample the only pixel of solid.png
            if (cosmVtxTexture != null) {
                on = cosmVtxTexture.invoke(on, 0.5f, 0.5f);
                if (on == null) on = buffer;
            }
            if (cosmVtxOverlay != null) {
                on = cosmVtxOverlay.invoke(on, 0, 10);
                if (on == null) on = buffer;
            }
            if (cosmVtxLight != null) {
                on = cosmVtxLight.invoke(on, 0xF0, 0xF0);
                if (on == null) on = buffer;
            }
            if (cosmVtxNormal != null) {
                if (nrm4) cosmVtxNormal.invoke(on, normalArg, nx, ny, nz);
                else      cosmVtxNormal.invoke(on, nx, ny, nz);
            }
        }
    }

    /** Get the player's crosshair target — block hit only. Returns the
     *  BlockHitResult or null if the target is air, an entity, or missing. */
    private static Object getBlockCrosshairHit() {
        try {
            // Match the existing pattern in the file: try method first
            // (getCrosshairTarget), then field (field_1765).
            Object hit = tryInvoke(mc, "method_64829", "getCrosshairTarget", "crosshairTarget");
            if (hit == null) {
                try {
                    Field f = cachedField(mc.getClass(), "field_1765");
                    if (f == null) f = cachedField(mc.getClass(), "crosshairTarget");
                    if (f != null) hit = f.get(mc);
                } catch (Throwable ignored) {}
            }
            if (hit == null) return null;
            // HitResult.Type — class_239$class_240. enum: MISS / BLOCK / ENTITY.
            Object type = tryInvoke(hit, "method_17783", "getType");
            if (type == null) return null;
            String typeStr = String.valueOf(type);
            if (!typeStr.contains("BLOCK")) return null;
            return hit;
        } catch (Throwable t) { return null; }
    }

    /** Render a translucent colored quad on the face of the targeted block.
     *  Coords are world-space; we subtract the camera position (cx,cy,cz)
     *  so each vertex is camera-relative as the render layer expects. */
    private static void drawBlockFaceHighlight(Object buffer, Object vertexArg, Object normalArg,
                                               double cx, double cy, double cz) throws Exception {
        Object hit = getBlockCrosshairHit();
        if (hit == null) {
            if (!shadowhud$bhitDiag1) {
                shadowhud$bhitDiag1 = true;
                System.out.println("[ShadowHud][BlockHighlight] no block under crosshair (no air-targeted)");
            }
            return;
        }
        // BlockPos via BlockHitResult.method_17777 (getBlockPos)
        Object blockPos = tryInvoke(hit, "method_17777", "getBlockPos");
        if (blockPos == null) {
            if (!shadowhud$bhitDiag2) {
                shadowhud$bhitDiag2 = true;
                System.out.println("[ShadowHud][BlockHighlight] BlockPos lookup failed via method_17777");
            }
            return;
        }
        Number bxN = firstNum(blockPos, "method_10263", "getX");
        Number byN = firstNum(blockPos, "method_10264", "getY");
        Number bzN = firstNum(blockPos, "method_10260", "getZ");
        if (bxN == null || byN == null || bzN == null) return;
        int bx = bxN.intValue(), by = byN.intValue(), bz = bzN.intValue();
        // Direction via BlockHitResult.method_17780 (getSide)
        Object dir = tryInvoke(hit, "method_17780", "getSide");
        if (dir == null) {
            if (!shadowhud$bhitDiag3) {
                shadowhud$bhitDiag3 = true;
                System.out.println("[ShadowHud][BlockHighlight] Direction lookup failed via method_17780");
            }
            return;
        }
        // Normalize to uppercase so we match regardless of toString() case.
        String dirStr = String.valueOf(dir).toUpperCase();
        if (!shadowhud$bhitDiag4) {
            shadowhud$bhitDiag4 = true;
            System.out.println("[ShadowHud][BlockHighlight] FIRST RENDER — block=("
                + bx + "," + by + "," + bz + ") face=" + dirStr
                + " color=#" + String.format("%06X", cfgBlockHighlightColor & 0xFFFFFF)
                + " alpha=" + cfgBlockHighlightAlpha + "%");
        }
        // Compute face quad corners in world space, offset along normal by
        // 0.002 to avoid z-fighting with the block face.
        float OFF = 0.002f;
        // Each face: 4 corners in CCW order (when viewed from outside) +
        // a normal vector for lighting.
        float ax, ay, az, bxv, byv, bzv, cxv, cyv, czv, dxv, dyv, dzv;
        float nx = 0, ny = 0, nz = 0;
        switch (dirStr) {
            case "UP":
                ay = byv = cyv = dyv = by + 1f + OFF;
                ax = bx;     bxv = bx + 1; cxv = bx + 1; dxv = bx;
                az = bz;     bzv = bz;     czv = bz + 1; dzv = bz + 1;
                ny = 1;
                break;
            case "DOWN":
                ay = byv = cyv = dyv = by - OFF;
                ax = bx;     bxv = bx + 1; cxv = bx + 1; dxv = bx;
                az = bz + 1; bzv = bz + 1; czv = bz;     dzv = bz;
                ny = -1;
                break;
            case "NORTH":
                az = bzv = czv = dzv = bz - OFF;
                ax = bx + 1; bxv = bx;     cxv = bx;     dxv = bx + 1;
                ay = by;     byv = by;     cyv = by + 1; dyv = by + 1;
                nz = -1;
                break;
            case "SOUTH":
                az = bzv = czv = dzv = bz + 1f + OFF;
                ax = bx;     bxv = bx + 1; cxv = bx + 1; dxv = bx;
                ay = by;     byv = by;     cyv = by + 1; dyv = by + 1;
                nz = 1;
                break;
            case "WEST":
                ax = bxv = cxv = dxv = bx - OFF;
                az = bz;     bzv = bz + 1; czv = bz + 1; dzv = bz;
                ay = by;     byv = by;     cyv = by + 1; dyv = by + 1;
                nx = -1;
                break;
            case "EAST":
                ax = bxv = cxv = dxv = bx + 1f + OFF;
                az = bz + 1; bzv = bz;     czv = bz;     dzv = bz + 1;
                ay = by;     byv = by;     cyv = by + 1; dyv = by + 1;
                nx = 1;
                break;
            default: return;
        }
        // Convert to camera-relative
        float aLx = (float)(ax - cx),  aLy = (float)(ay - cy),  aLz = (float)(az - cz);
        float bLx = (float)(bxv - cx), bLy = (float)(byv - cy), bLz = (float)(bzv - cz);
        float cLx = (float)(cxv - cx), cLy = (float)(cyv - cy), cLz = (float)(czv - cz);
        float dLx = (float)(dxv - cx), dLy = (float)(dyv - cy), dLz = (float)(dzv - cz);
        // Color components (RGB packed, alpha from cfgBlockHighlightAlpha)
        int rC = (cfgBlockHighlightColor >> 16) & 0xFF;
        int gC = (cfgBlockHighlightColor >>  8) & 0xFF;
        int bC =  cfgBlockHighlightColor        & 0xFF;
        int aC = Math.max(0, Math.min(255, (cfgBlockHighlightAlpha * 255) / 100));
        // Emit 4-vertex quad — vertex method calls follow the same shape
        // as cosmEmitQuad but coords are already world→camera-relative,
        // so we don't need flap/yaw transforms.
        boolean vtx4 = cosmVtxPos.getParameterCount() == 4;
        boolean nrm4 = cosmVtxNormal != null && cosmVtxNormal.getParameterCount() == 4;
        boolean colorIsInt = cosmVtxColor != null
                          && cosmVtxColor.getParameterTypes()[0] == int.class;
        float[][] vs = {
            { aLx, aLy, aLz },
            { bLx, bLy, bLz },
            { cLx, cLy, cLz },
            { dLx, dLy, dLz }
        };
        for (float[] v : vs) {
            Object vc;
            if (vtx4) {
                vc = cosmVtxPos.invoke(buffer, vertexArg, v[0], v[1], v[2]);
            } else {
                vc = cosmVtxPos.invoke(buffer, v[0], v[1], v[2]);
            }
            Object on = vc != null ? vc : buffer;
            if (cosmVtxColor != null) {
                if (colorIsInt) on = cosmVtxColor.invoke(on, rC, gC, bC, aC);
                else            on = cosmVtxColor.invoke(on, rC / 255f, gC / 255f, bC / 255f, aC / 255f);
                if (on == null) on = buffer;
            }
            if (cosmVtxTexture != null) {
                on = cosmVtxTexture.invoke(on, 0.5f, 0.5f);
                if (on == null) on = buffer;
            }
            if (cosmVtxOverlay != null) {
                on = cosmVtxOverlay.invoke(on, 0, 10);
                if (on == null) on = buffer;
            }
            if (cosmVtxLight != null) {
                on = cosmVtxLight.invoke(on, 0xF0, 0xF0);
                if (on == null) on = buffer;
            }
            if (cosmVtxNormal != null) {
                if (nrm4) cosmVtxNormal.invoke(on, normalArg, nx, ny, nz);
                else      cosmVtxNormal.invoke(on, nx, ny, nz);
            }
        }
    }

    /** Triangulate a 2D wing-side polygon as a fan from shape[0], emitting
     *  each tri as a degenerate quad. Mirrored if `mirror` (left + right wings).
     *  Solid colored — uses the solid.png 1×1 white texture. */
    private static void cosmDrawSolidPolygon(Object buffer, Object vertexArg, Object normalArg,
            float[][] shape, boolean mirror,
            float flapC, float flapS, float pivot,
            float yawC, float yawS,
            double dx, double dy, double dz,
            int r, int g, int bCol) throws Exception {
        if (shape.length < 3) return;
        int sides = mirror ? 2 : 1;
        for (int s = 0; s < sides; s++) {
            int side = mirror ? (s == 0 ? -1 : 1) : 1;
            float[] v0 = { side * shape[0][0], shape[0][1], shape[0][2] };
            for (int i = 1; i < shape.length - 1; i++) {
                float[] vi = { side * shape[i][0],     shape[i][1],     shape[i][2] };
                float[] vj = { side * shape[i + 1][0], shape[i + 1][1], shape[i + 1][2] };
                cosmEmitQuad(buffer, vertexArg, normalArg,
                        v0, vi, vj, vj,
                        flapC, flapS, pivot, yawC, yawS,
                        dx, dy, dz, r, g, bCol);
            }
        }
    }

    // ============= end 3D cosmetic mesh rendering ==========================

    private static void spawnTrailParticles(Object world, Object player) {
        if (trailIdx < 0 || trailIdx >= TRAIL_PARTICLES.length) return;
        resolveParticleApi(world);
        if (addParticleMethod == null) return;
        Object particle = resolveParticleAt(trailIdx);
        if (particle == null) return;
        try {
            double x = firstNum(player, "method_23317", "getX").doubleValue();
            double y = firstNum(player, "method_23318", "getY").doubleValue();
            double z = firstNum(player, "method_23321", "getZ").doubleValue();
            // Interpolate between previous and current position so the trail
            // is continuous when running, not just spaced 1 frame apart.
            if (Double.isNaN(prevTrailX)) {
                prevTrailX = x; prevTrailY = y; prevTrailZ = z;
            }
            double dx = x - prevTrailX, dy = y - prevTrailY, dz = z - prevTrailZ;
            int steps = 3;     // 3 particles per frame, smoothly spread
            for (int i = 0; i < steps; i++) {
                double t = (i + 0.5) / steps;
                double sx = prevTrailX + dx * t + (Math.random() - 0.5) * 0.2;
                double sy = prevTrailY + dy * t + 0.05;
                double sz = prevTrailZ + dz * t + (Math.random() - 0.5) * 0.2;
                addParticleMethod.invoke(world, particle, true, true, sx, sy, sz, 0d, 0d, 0d);
            }
            prevTrailX = x; prevTrailY = y; prevTrailZ = z;
        } catch (Throwable ignored) {}
    }

    private static void spawnHaloParticles(Object world, Object player) {
        if (haloIdx < 0 || haloIdx >= TRAIL_PARTICLES.length) return;
        resolveParticleApi(world);
        if (addParticleMethod == null) return;
        Object particle = resolveParticleAt(haloIdx);
        if (particle == null) return;
        try {
            double x = firstNum(player, "method_23317", "getX").doubleValue();
            double y = firstNum(player, "method_23318", "getY").doubleValue() + 2.4;
            double z = firstNum(player, "method_23321", "getZ").doubleValue();
            // Spinning halo — three particles 120° apart, advancing each frame
            haloAngle += 0.15f;
            for (int i = 0; i < 3; i++) {
                double a = haloAngle + i * (Math.PI * 2.0 / 3.0);
                double rx = x + Math.cos(a) * 0.6;
                double rz = z + Math.sin(a) * 0.6;
                addParticleMethod.invoke(world, particle, true, true, rx, y, rz, 0d, 0d, 0d);
            }
            if (!shadowhud$firstSpawnLogged) {
                shadowhud$firstSpawnLogged = true;
                System.out.println("[ShadowHud][Cosmetic] first particle spawned — "
                    + "halo at (" + x + "," + y + "," + z + ") "
                    + "particle=" + particle.getClass().getSimpleName());
            }
        } catch (Throwable t) {
            if (!shadowhud$firstSpawnLogged) {
                shadowhud$firstSpawnLogged = true;
                System.err.println("[ShadowHud][Cosmetic] particle spawn FAILED: " + t);
                t.printStackTrace();
            }
        }
    }

    /** Persistence. V2 format keeps a color per chunk:
     *    int MAGIC ("SWC2")
     *    int dim_count
     *    for each dim:
     *        int utf8_len; byte[] utf8_name;
     *        int chunk_count; (long key, int argb)[chunk_count];
     *  V1 ("SWCH") = per-dim Set<long> (no colors) — auto-migrated with
     *  FALLBACK_COLOR so we don't lose any explored area.
     *  Legacy flat-long streams (pre-multidim) also migrate via the same path. */
    private static void loadExploredChunks() {
        try {
            if (!Files.exists(CHUNKS_FILE)) return;
            byte[] data = Files.readAllBytes(CHUNKS_FILE);
            if (data.length < 8) return;
            ByteBuffer bb = ByteBuffer.wrap(data);
            int magic = bb.getInt();
            if (magic == CHUNKS_MAGIC_V2) {
                int dimCount = bb.getInt();
                for (int i = 0; i < dimCount && bb.remaining() >= 8; i++) {
                    int nameLen = bb.getInt();
                    if (nameLen < 0 || nameLen > 512 || bb.remaining() < nameLen + 4) break;
                    byte[] nameBytes = new byte[nameLen];
                    bb.get(nameBytes);
                    String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
                    int chunkCount = bb.getInt();
                    Map<Long, Integer> cm = Collections.synchronizedMap(new HashMap<>());
                    for (int j = 0; j < chunkCount && bb.remaining() >= 12; j++) {
                        long k = bb.getLong();
                        int  c = bb.getInt();
                        cm.put(k, c);
                    }
                    EXPLORED.put(name, cm);
                }
            } else if (magic == CHUNKS_MAGIC_V1) {
                // V1 had no color info per chunk — seed each with FALLBACK_COLOR.
                int dimCount = bb.getInt();
                for (int i = 0; i < dimCount && bb.remaining() >= 8; i++) {
                    int nameLen = bb.getInt();
                    if (nameLen < 0 || nameLen > 512 || bb.remaining() < nameLen + 4) break;
                    byte[] nameBytes = new byte[nameLen];
                    bb.get(nameBytes);
                    String name = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);
                    int chunkCount = bb.getInt();
                    Map<Long, Integer> cm = Collections.synchronizedMap(new HashMap<>());
                    for (int j = 0; j < chunkCount && bb.remaining() >= 8; j++) {
                        cm.put(bb.getLong(), FALLBACK_COLOR);
                    }
                    EXPLORED.put(name, cm);
                }
                System.out.println("[ShadowHud] migrated V1 → V2 (colors will fill in as you explore)");
            } else {
                // Pre-multidim flat-long stream. Drop into a placeholder bucket.
                bb.position(0);
                if (data.length % 8 != 0) return;
                Map<Long, Integer> legacy = Collections.synchronizedMap(new HashMap<>());
                while (bb.remaining() >= 8) legacy.put(bb.getLong(), FALLBACK_COLOR);
                EXPLORED.put("unknown@overworld", legacy);
            }
            int total = 0;
            for (Map<Long, Integer> m : EXPLORED.values()) total += m.size();
            System.out.println("[ShadowHud] loaded " + total + " chunks across "
                + EXPLORED.size() + " dimensions");
        } catch (Throwable t) {
            System.err.println("[ShadowHud] loadExploredChunks: " + t);
        }
    }

    private static void saveExploredChunks() {
        try {
            Files.createDirectories(CHUNKS_FILE.getParent());
            Map<String, Map<Long, Integer>> snap;
            synchronized (EXPLORED) { snap = new HashMap<>(EXPLORED); }
            int size = 8;   // magic + dim_count
            Map<String, byte[]> nameBytes = new HashMap<>();
            for (Map.Entry<String, Map<Long, Integer>> e : snap.entrySet()) {
                byte[] nb = e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                nameBytes.put(e.getKey(), nb);
                size += 4 + nb.length + 4 + e.getValue().size() * 12;  // 8 (long) + 4 (int)
            }
            ByteBuffer bb = ByteBuffer.allocate(size);
            bb.putInt(CHUNKS_MAGIC_V2);
            bb.putInt(snap.size());
            for (Map.Entry<String, Map<Long, Integer>> e : snap.entrySet()) {
                byte[] nb = nameBytes.get(e.getKey());
                bb.putInt(nb.length);
                bb.put(nb);
                Map<Long, Integer> chunks;
                synchronized (e.getValue()) { chunks = new HashMap<>(e.getValue()); }
                bb.putInt(chunks.size());
                for (Map.Entry<Long, Integer> c : chunks.entrySet()) {
                    bb.putLong(c.getKey());
                    bb.putInt(c.getValue());
                }
            }
            Path tmp = CHUNKS_FILE.resolveSibling(CHUNKS_FILE.getFileName() + ".tmp");
            Files.write(tmp, bb.array());
            Files.move(tmp, CHUNKS_FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {}
    }

    // ---- bootstrap --------------------------------------------------------

    private static void ensureInit() throws Exception {
        if (initDone) return;
        synchronized (ShadowHud.class) {
            if (initDone) return;
            Class<?> mcClass = Class.forName("net.minecraft.class_310");
            for (Method m : mcClass.getMethods()) {
                if (m.getParameterCount() == 0
                        && Modifier.isStatic(m.getModifiers())
                        && mcClass.isAssignableFrom(m.getReturnType())) {
                    mc = m.invoke(null);
                    break;
                }
            }
            for (Field f : mcClass.getFields()) {
                String fn = f.getName();
                String tn = f.getType().getName();
                if      (fn.equals("field_1724") || tn.equals("net.minecraft.class_746")) playerField = f;
                else if (fn.equals("field_1687") || tn.equals("net.minecraft.class_638")) worldField  = f;
                else if (fn.equals("field_1772") || tn.equals("net.minecraft.class_327")) fontField   = f;
            }
            // Find FPS getter: try known intermediary first, then any no-arg
            // int-returning method with "fps" in its name (debug screen uses this).
            for (Method m : mcClass.getMethods()) {
                if (m.getParameterCount() != 0 || m.getReturnType() != int.class) continue;
                if (Modifier.isStatic(m.getModifiers())) continue;
                if (m.getName().equals("method_47599")) {
                    getFpsMethod = m;
                    break;
                }
            }
            if (getFpsMethod == null) {
                for (Method m : mcClass.getMethods()) {
                    if (m.getParameterCount() != 0 || m.getReturnType() != int.class) continue;
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    String n = m.getName().toLowerCase();
                    if (n.contains("fps") || n.contains("currentfps")) {
                        getFpsMethod = m;
                        break;
                    }
                }
            }
            // One-time log so we can see which reflection lookups landed.
            System.out.println("[ShadowHud] init — "
                + "mc=" + (mc != null)
                + " player=" + (playerField != null)
                + " world=" + (worldField != null)
                + " font=" + (fontField != null)
                + " getFps=" + (getFpsMethod == null ? "null" : getFpsMethod.getName()));

            // GLFW reflection — LWJGL lives on MC's classpath
            Object window = null;
            String glfwStatus = "";
            try {
                glfw = Class.forName("org.lwjgl.glfw.GLFW");
                glfwGetKey = glfw.getMethod("glfwGetKey", long.class, int.class);
                try {
                    glfwGetMouseButton = glfw.getMethod("glfwGetMouseButton", long.class, int.class);
                } catch (NoSuchMethodException ignored) {}
                try {
                    glfwGetCursorPos = glfw.getMethod("glfwGetCursorPos",
                        long.class, double[].class, double[].class);
                } catch (NoSuchMethodException ignored) {}
                try {
                    glfwSetInputMode = glfw.getMethod("glfwSetInputMode",
                        long.class, int.class, int.class);
                } catch (NoSuchMethodException ignored) {}
                try {
                    glfwSetCursorPos = glfw.getMethod("glfwSetCursorPos",
                        long.class, double.class, double.class);
                } catch (NoSuchMethodException ignored) {}

                // Resolve window via method_22683 (intermediary getWindow) OR any
                // zero-arg method returning something that *contains* class_1041.
                try {
                    window = mcClass.getMethod("method_22683").invoke(mc);
                } catch (NoSuchMethodException ignored) {}
                if (window == null) {
                    for (Method m : mcClass.getMethods()) {
                        if (m.getParameterCount() != 0) continue;
                        String tn = m.getReturnType().getName();
                        if (tn.contains("class_1041") || tn.endsWith(".Window")) {
                            window = m.invoke(mc);
                            break;
                        }
                    }
                }

                // Resolve window.getHandle() — try method_4490, then any long-returning method
                String handleMethodName = "(none)";
                if (window != null) {
                    try {
                        Method hm = window.getClass().getMethod("method_4490");
                        if (hm.getReturnType() == long.class) {
                            windowHandle = (long) hm.invoke(window);
                            handleMethodName = "method_4490";
                        }
                    } catch (NoSuchMethodException ignored) {}
                    if (windowHandle < 0) {
                        // Log every candidate long-returning method so we can
                        // verify we picked the GLFW handle and not something else.
                        StringBuilder cands = new StringBuilder();
                        for (Method m : window.getClass().getMethods()) {
                            if (m.getReturnType() == long.class && m.getParameterCount() == 0) {
                                cands.append(m.getName()).append(' ');
                                if (windowHandle < 0) {
                                    windowHandle = (long) m.invoke(window);
                                    handleMethodName = m.getName();
                                }
                            }
                        }
                        System.out.println("[ShadowHud] window long-methods: " + cands);
                    }
                }
                glfwStatus = String.format(
                    "glfwGetKey=%s glfwSetInputMode=%s window=%s handle=%d via=%s",
                    glfwGetKey != null, glfwSetInputMode != null,
                    window != null, windowHandle, handleMethodName);
            } catch (Throwable t) {
                glfwStatus = "FAIL " + t;
            }
            System.out.println("[ShadowHud] glfw " + glfwStatus);

            setupScrollHook();
            initDone = true;
        }
    }

    /**
     * Build our GLFWScrollCallback, capture MC's pristine callback, and
     * leave MC's installed. {@link #swapScrollCallback} then flips between
     * the two on menu open/close.
     *
     * <p>CRITICAL: {@code GLFWScrollCallback.create()} allocates a native
     * libffi trampoline. Dropping the Java ref lets GC free the trampoline,
     * which SIGSEGVs the next wheel event. Hence the static fields.</p>
     */
    private static void setupScrollHook() {
        if (windowHandle <= 0 || glfw == null) {
            System.out.println("[ShadowHud][GLFW] cannot install scroll callback — "
                + "windowHandle=" + windowHandle + " glfwClass=" + (glfw != null));
            return;
        }
        try {
            Class<?> cbiCls = Class.forName("org.lwjgl.glfw.GLFWScrollCallbackI");
            Class<?> cbCls  = Class.forName("org.lwjgl.glfw.GLFWScrollCallback");
            Method create   = cbCls.getMethod("create", cbiCls);
            Method setScroll = glfw.getMethod("glfwSetScrollCallback",
                long.class, cbiCls);

            // GLFWScrollCallbackI extends CallbackI, which has default methods
            // like address() that LWJGL calls during create() to allocate the
            // libffi closure. Any non-invoke method MUST delegate to its
            // default impl — returning null from address() would NPE on
            // auto-unbox.
            //
            // Dispatch by name, NOT by Method `==` — Class.getMethod() returns
            // a fresh copy each call, so the Method instance we cache is never
            // the same as the one the Proxy passes to the handler. Reference
            // equality silently returns null on every wheel notch, killing
            // scroll without throwing.
            Object cbi = java.lang.reflect.Proxy.newProxyInstance(
                cbiCls.getClassLoader(),
                new Class<?>[]{cbiCls},
                (proxy, method, args) -> {
                    String mn = method.getName();
                    if ("invoke".equals(mn) && args != null && args.length == 3) {
                        double dy = (double) args[2];
                        scrollEventCount++;
                        // Log first 20 events so we can verify each wheel
                        // notch reaches us with the expected magnitude.
                        if (scrollEventCount <= 20) {
                            System.out.println(
                                "[ShadowHud][GLFW] scroll #" + scrollEventCount
                                + " v=" + dy + " menuOpen=" + menuOpen
                                + " accum→" + (menuOpen ? (pendingScrollDelta + dy) : pendingScrollDelta));
                        }
                        if (menuOpen || hudEditMode) pendingScrollDelta += dy;
                        return null;
                    }
                    if (method.isDefault()) {
                        return java.lang.reflect.InvocationHandler
                            .invokeDefault(proxy, method, args);
                    }
                    if ("equals".equals(mn))   return proxy == args[0];
                    if ("hashCode".equals(mn)) return System.identityHashCode(proxy);
                    if ("toString".equals(mn)) return "ShadowHudScrollCallback";
                    return null;
                });

            ourScrollCallback = create.invoke(null, cbi);
            if (ourScrollCallback == null) {
                System.out.println("[ShadowHud][GLFW] GLFWScrollCallback.create() "
                    + "returned null — Proxy address() probably failed; scroll capture disabled");
                return;
            }
            // Install ours, capture whatever was active. If something WAS active
            // (the common case — MC's class_312 lambda), restore it so vanilla
            // scroll keeps working until menu open. If NOTHING was active
            // (prev == null), we leave ours installed since restoring null
            // would mean "no scroll handler at all".
            prevScrollCallback = setScroll.invoke(null, windowHandle, ourScrollCallback);
            if (prevScrollCallback != null) {
                setScroll.invoke(null, windowHandle, prevScrollCallback);
                ourScrollInstalled = false;   // MC's is active now
            } else {
                ourScrollInstalled = true;    // ours stays — nothing to restore
            }
            glfwSetScrollCallbackMethod = setScroll;
            System.out.println("[ShadowHud][GLFW] scroll swap ready — prev="
                + (prevScrollCallback == null ? "null"
                                              : prevScrollCallback.getClass().getName())
                + "  ours=" + ourScrollCallback.getClass().getName()
                + (prevScrollCallback == null
                    ? "  (no MC cb found; OURS stays active)"
                    : "  (MC's cb is currently active; ours installs on menu open)"));
        } catch (Throwable t) {
            logOnce("GLFW-scroll-install", t);
        }
    }

    /**
     * Swap the active GLFW scroll callback when the menu opens or closes.
     * Idempotent. Safe only on the render thread (= GLFW main thread).
     *
     * <p>On install we update {@link #prevScrollCallback} from the return
     * value so that if another mod (Sodium / Fabric API / etc.) replaces
     * MC's callback after our setup, we'll restore the up-to-date one
     * rather than a stale handle.</p>
     *
     * <p>Always clears {@link #pendingScrollDelta} on swap. Fractional
     * remainders from a previous menu session would otherwise drain
     * unexpectedly on first scroll after reopening.</p>
     */
    private static void swapScrollCallback(boolean installOurs) {
        if (glfwSetScrollCallbackMethod == null || windowHandle <= 0) return;
        if (installOurs == ourScrollInstalled) return;
        Object target = installOurs ? ourScrollCallback : prevScrollCallback;
        // Install: target must exist (no point installing a null callback over
        // a possibly-working prev). Restore: target=null is OK; that means
        // "MC had no callback at setup time" so passing null to GLFW
        // (= remove callback) returns to that exact state.
        if (installOurs && target == null) return;
        try {
            Object returned = glfwSetScrollCallbackMethod.invoke(
                null, windowHandle, target);
            ourScrollInstalled = installOurs;
            // Reset accumulator so stale fractions / late events don't
            // bleed across menu sessions.
            pendingScrollDelta = 0.0;
            if (installOurs && returned != null && returned != ourScrollCallback) {
                prevScrollCallback = returned;
            }
            System.out.println("[ShadowHud][GLFW] scroll swap → "
                + (installOurs ? "OURS (menu intercepts wheel)"
                              : "MC (vanilla scroll restored)"));
        } catch (Throwable t) {
            logOnce("GLFW-scroll-swap", t);
        }
    }

    // ---- input ------------------------------------------------------------

    /** Handle every menu key: open/close, bound toggles, bind mode, navigation. */
    private static void pollInput() {
        if (glfwGetKey == null || windowHandle < 0) return;

        // ---- ServerRules prompt (Y / N / I) ----
        // Highest priority so the keystroke isn't consumed by the menu or
        // chat. Only fires while a prompt is actually active.
        if (rulePromptActive && !menuOpen) {
            if (edge(89))  resolveServerRulesPrompt('Y');   // GLFW Y
            else if (edge(78))  resolveServerRulesPrompt('N');   // GLFW N
            else if (edge(73))  resolveServerRulesPrompt('I');   // GLFW I
        }

        // ---- Mouse cursor position + click edge (for menu interaction) ----
        // We read the raw GLFW cursor (window pixels) then divide by the
        // scale factor to land on the same coordinate grid where the menu
        // is drawn. When the menu is open we manually move the cursor toward
        // the screen center so the camera stops rotating — this gives the
        // user a stable cursor independent of MC's mouse-look state.
        if (glfwGetCursorPos != null && mc != null) {
            try {
                double[] xa = new double[1], ya = new double[1];
                glfwGetCursorPos.invoke(null, windowHandle, xa, ya);
                // Cached: mc.getWindow() and window.getScaleFactor(). Both
                // run every frame the menu is open — caching the Method
                // refs cuts a getClass().getMethod() lookup per axis.
                Method getWindow = cachedMethod(mc.getClass(), "method_22683");
                if (getWindow == null) getWindow = cachedMethod(mc.getClass(), "getWindow");
                Object window = getWindow != null ? getWindow.invoke(mc) : null;
                double scale = 1.0;
                if (window != null) {
                    Method getScale = cachedMethod(window.getClass(), "method_4495");
                    if (getScale == null) getScale = cachedMethod(window.getClass(), "getScaleFactor");
                    if (getScale != null) {
                        Object scaleObj = getScale.invoke(window);
                        if (scaleObj instanceof Number) scale = ((Number) scaleObj).doubleValue();
                    }
                }
                if (scale <= 0) scale = 1;
                mouseX = (int) (xa[0] / scale);
                mouseY = (int) (ya[0] / scale);
            } catch (Throwable t) {
                if (!cursorReadLogged) {
                    cursorReadLogged = true;
                    System.err.println("[ShadowHud] cursor read failed: " + t);
                }
            }
        }
        boolean leftDown = mouseButtonDown(MB_LEFT);
        leftClickEdge = leftDown && !prevLeftDown;
        prevLeftDown = leftDown;
        // Right-click rising edge — used by the menu to "right-click → open
        // config panel" as a shortcut around the small gear icon.
        boolean rightDown = mouseButtonDown(MB_RIGHT);
        rightClickEdge = rightDown && !prevRightDown;
        prevRightDown = rightDown;

        // (Camera-freeze removed — was fighting MC's mouse-look every frame
        //  and causing visible jitter / "screen acting weird" when the user
        //  clicked. With the cursor unlocked every frame, MC stops processing
        //  mouse-look entirely so there's nothing to fight.)
        if (!menuOpen) frozenYaw = Float.MIN_VALUE;

        // SmoothSwing: stretch the swing animation by holding handSwingTicks
        // close to 0 for one extra frame each cycle — visually doubles the
        // duration of arm swing without breaking attack timing (vanilla
        // attack-cooldown logic uses a separate field).
        if (modOn("SmoothSwing", false)) {
            try {
                Object p = playerField != null ? playerField.get(mc) : null;
                if (p != null) {
                    if (handSwingField == null && !smoothSwingTried) {
                        smoothSwingTried = true;
                        // 1.21.11: handSwingTicks is field_6279 (field_6235 is
                        // actually hurtTime — using that here was modifying the
                        // damage-flash timer, not the swing animation!).
                        Class<?> c = p.getClass();
                        while (c != null && handSwingField == null) {
                            for (String n : new String[]{"field_6279", "handSwingTicks"}) {
                                try {
                                    handSwingField = c.getDeclaredField(n);
                                    handSwingField.setAccessible(true);
                                    break;
                                } catch (NoSuchFieldException ignored) {}
                            }
                            c = c.getSuperclass();
                        }
                    }
                    if (handSwingField != null) {
                        int v = handSwingField.getInt(p);
                        // Slow the decrement: only let it advance every 2 ticks
                        if (v > 0) {
                            smoothSwingDelay = (smoothSwingDelay + 1) & 1;
                            if (smoothSwingDelay == 1) handSwingField.setInt(p, v + 1);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Fullbright: when ON, max out the gamma SimpleOption. Saves the
        // previous value and restores it on toggle-off so the user gets their
        // settings back even if they were running a custom brightness.
        if (modOn("Fullbright", false)) {
            resolveGammaOption();
            if (!fullbrightActive && gammaOption != null && simpleOptionGet != null
                && simpleOptionSet != null) {
                try {
                    Object cur = simpleOptionGet.invoke(gammaOption);
                    if (cur instanceof Number) {
                        savedGamma = ((Number) cur).doubleValue();
                        simpleOptionSet.invoke(gammaOption, Double.valueOf((double) cfgFullbrightLevel));
                        fullbrightActive = true;
                    }
                } catch (Throwable ignored) {}
            }
        } else if (fullbrightActive) {
            try {
                if (savedGamma >= 0) simpleOptionSet.invoke(gammaOption,
                    Double.valueOf(savedGamma));
            } catch (Throwable ignored) {}
            fullbrightActive = false;
            savedGamma = -1;
        }

        // Waypoint: tap HOME to set (or clear if already set in this world).
        if (modOn("Waypoint", true) && edge(KEY_HOME_KEY)) {
            try {
                Object p = playerField != null ? playerField.get(mc) : null;
                if (p != null) {
                    String wid = currentMapKey();
                    if (wpSet && wid.equals(wpWorld)) {
                        wpSet = false;
                        System.out.println("[ShadowHud] Waypoint cleared");
                    } else {
                        wpX = firstNum(p, "method_23317", "getX").doubleValue();
                        wpY = firstNum(p, "method_23318", "getY").doubleValue();
                        wpZ = firstNum(p, "method_23321", "getZ").doubleValue();
                        wpWorld = wid;
                        wpSet = true;
                        System.out.println("[ShadowHud] Waypoint set @ "
                            + (int)wpX + "," + (int)wpY + "," + (int)wpZ);
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Block-placed counter: edge-trigger on RMB while holding a Block item.
        if (modOn("BlockCount", true)) {
            boolean rmbDown = mouseButtonDown(MB_RIGHT);
            if (rmbDown && rmbWasUpForBlocks) {
                try {
                    Object p = playerField != null ? playerField.get(mc) : null;
                    if (p != null) {
                        Object stack = tryInvoke(p, "method_6047", "getMainHandStack",
                                                 "getMainHandItem");
                        if (stack != null
                            && !Boolean.TRUE.equals(tryInvoke(stack, "method_7960", "isEmpty"))) {
                            // Heuristic: held item must descend from BlockItem (class_1747)
                            Object item = tryInvoke(stack, "method_7909", "getItem");
                            if (item != null) {
                                Class<?> c = item.getClass();
                                while (c != null) {
                                    String cn = c.getName();
                                    if (cn.endsWith("class_1747") || cn.endsWith("BlockItem")) {
                                        sessionBlocksPlaced++;
                                        break;
                                    }
                                    c = c.getSuperclass();
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) {}
            }
            rmbWasUpForBlocks = !rmbDown;
        }

        // Drop counter: edge-trigger on Q.
        if (modOn("DropCount", true)) {
            boolean qDown = keyDown(KEY_Q_KEY);
            if (qDown && qWasUp) sessionDrops++;
            qWasUp = !qDown;
        }

        // CoordCopy: tap Insert to copy current XYZ to system clipboard. Pure
        // utility — no keybind config to keep the menu cards clean.
        if (modOn("CoordCopy", true) && edge(KEY_INSERT)) {
            try {
                Object p = playerField != null ? playerField.get(mc) : null;
                if (p != null) {
                    int px = (int) firstNum(p, "method_23317", "getX").doubleValue();
                    int py = (int) firstNum(p, "method_23318", "getY").doubleValue();
                    int pz = (int) firstNum(p, "method_23321", "getZ").doubleValue();
                    String s = px + " " + py + " " + pz;
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                        new java.awt.datatransfer.StringSelection(s), null);
                    System.out.println("[ShadowHud] CoordCopy → clipboard: " + s);
                }
            } catch (Throwable ignored) {}
        }

        // ---- AFK: ANY key or mouse-button being held resets the idle timer.
        // We sample a small set of common keys plus the WASD/Space we already
        // have for keystrokes; any one held → user is active.
        if (anyInputActive()) lastInputMs = System.currentTimeMillis();

        // Right-Shift opens/closes the menu (always live).
        boolean rs = keyDown(KEY_RIGHT_SHIFT);
        if (rs && !prevKey[KEY_RIGHT_SHIFT]) {
            menuOpen = !menuOpen;
            if (menuOpen) loadMods();   // refresh list every open
            System.out.println("[ShadowHud] RShift -> menu " + (menuOpen ? "OPEN" : "CLOSE"));
        }
        prevKey[KEY_RIGHT_SHIFT] = rs;
        // Also close on Escape — standard menu behaviour. Only close (never
        // open) so Esc doesn't conflict with the in-game pause menu when our
        // overlay is hidden. Don't trip the close while the user is typing
        // into the search box (Esc clears the buffer in that path) or the
        // hex picker is open.
        // Always sample these keys — even with menu closed — so prevKey stays
        // accurate across menu open/close cycles. Otherwise the user holding
        // a key when they open the menu would fire a stale rising edge.
        // Edges are computed here, consumed inside the menuOpen guard below.
        boolean esc = keyDown(256);   boolean escEdge = esc && !prevKey[256];   prevKey[256] = esc;
        boolean f1  = keyDown(290);   boolean f1Edge  = f1  && !prevKey[290];   prevKey[290] = f1;
        boolean kS  = keyDown(83);    boolean sEdge   = kS  && !prevKey[83];    prevKey[83]  = kS;
        boolean kL  = keyDown(76);    boolean lEdge   = kL  && !prevKey[76];    prevKey[76]  = kL;
        boolean kD2 = keyDown(68);    boolean dEdge   = kD2 && !prevKey[68];    prevKey[68]  = kD2;
        boolean tab = keyDown(258);   boolean tabEdge = tab && !prevKey[258];   prevKey[258] = tab;
        boolean shift = keyDown(340) || keyDown(344);
        boolean[] numEdge = new boolean[MENU_CATEGORIES.length];
        for (int n = 0; n < MENU_CATEGORIES.length; n++) {
            int kc = 49 + n;
            boolean down = keyDown(kc);
            numEdge[n] = down && !prevKey[kc];
            prevKey[kc] = down;
        }
        boolean ctrl = keyDown(341) || keyDown(345);

        if (menuOpen && !menuSearchActive && !hexInputActive) {
            if (escEdge) {
                if (helpOverlayOpen) {
                    helpOverlayOpen = false;
                } else if (configPanelModule != null) {
                    // Close config panel first; only if it's already closed
                    // does Esc bubble up to close the whole menu.
                    configPanelModule = null;
                } else {
                    menuOpen = false;
                    System.out.println("[ShadowHud] Esc -> menu CLOSE");
                }
            }
            if (f1Edge) helpOverlayOpen = !helpOverlayOpen;
            // Tab / Shift+Tab — cycle through category tabs. Standard
            // keyboard-accessibility navigation; lets the user explore all
            // categories without remembering Ctrl+1..7.
            if (tabEdge) {
                int curIdx = 0;
                for (int t = 1; t < MENU_CATEGORIES.length; t++) {
                    if (MENU_CATEGORIES[t].equals(menuCategoryFilter)) { curIdx = t; break; }
                }
                int newIdx = shift
                    ? (curIdx - 1 + MENU_CATEGORIES.length) % MENU_CATEGORIES.length
                    : (curIdx + 1) % MENU_CATEGORIES.length;
                menuCategoryFilter = (newIdx == 0) ? "" : MENU_CATEGORIES[newIdx];
                moduleScrollTop = 0;
            }
            if (ctrl) {
                for (int n = 0; n < MENU_CATEGORIES.length; n++) {
                    if (numEdge[n]) {
                        menuCategoryFilter = (n == 0) ? "" : MENU_CATEGORIES[n];
                        moduleScrollTop = 0;
                    }
                }
                if (sEdge) saveUserSnapshot();
                if (lEdge) loadUserSnapshot();
                if (dEdge) applyPreset("Defaults");
            }
        }

        // Every frame the menu is open, force the cursor unlocked. Vanilla MC
        // keeps the cursor in GLFW_CURSOR_DISABLED mode whenever no Screen is
        // open — clicks teleport to screen center because the cursor is
        // physically pinned there for raw mouse-look. We fight this with TWO
        // paths so it works even if one breaks:
        //
        //   (A) Call mc.mouse.unlockCursor() — needs the mouse field via
        //       getDeclaredField() because field_1729 is `private` (the
        //       previous getField() call always failed silently and the
        //       cursor stayed locked).
        //   (B) Fallback: call GLFW.glfwSetInputMode(window, CURSOR, NORMAL)
        //       directly. Bypasses MC entirely.
        try {
            // Resolve mouse instance — retry every frame if not yet cached. The
            // previous "tried" latch was a footgun: if findFieldUp briefly
            // returned null on the first call (e.g. before mc was fully
            // initialized) we'd never retry.
            Object mouse = mouseField;
            if (mouse == null) {
                Field mf = findFieldUp(mc.getClass(), "field_1729");
                if (mf == null) mf = findFieldUp(mc.getClass(), "mouse");
                // Brute-force fallback: walk every declared field on mc + its
                // parents, take the first one of type class_312.
                if (mf == null) {
                    try {
                        Class<?> mouseCls = Class.forName("net.minecraft.class_312");
                        Class<?> c = mc.getClass();
                        outer: while (c != null) {
                            for (Field f : c.getDeclaredFields()) {
                                if (mouseCls.isAssignableFrom(f.getType())) {
                                    mf = f; break outer;
                                }
                            }
                            c = c.getSuperclass();
                        }
                    } catch (Throwable ignored) {}
                }
                if (mf != null) {
                    mf.setAccessible(true);
                    mouse = mf.get(mc);
                    if (mouse != null) mouseField = mouse;
                }
            }
            if (menuOpen) {
                // (A) MC's own unlock — does the right thing for keybinds /
                //     internal state if it works.
                String stepA = "skip";
                if (mouse != null) {
                    Object r = tryInvoke(mouse, "method_1610", "unlockCursor");
                    stepA = (r == null) ? "called" : "called(ret=" + r + ")";
                }
                // (B) Direct GLFW override — runs every frame so MC can't
                //     re-lock between our unlock and the next render. The
                //     constants are GLFW_CURSOR (0x33001) +
                //     GLFW_CURSOR_NORMAL (0x34001).
                String stepB = "skip";
                if (glfwSetInputMode != null && windowHandle > 0) {
                    try {
                        glfwSetInputMode.invoke(null, windowHandle, 0x00033001, 0x00034001);
                        stepB = "ok";
                    } catch (Throwable t) { stepB = "FAIL " + t.getClass().getSimpleName(); }
                }
                // (C) Forcibly clear the cursorLocked boolean on the Mouse
                //     instance. MC checks this every frame to decide whether
                //     mouse deltas rotate the camera (true) or update UI
                //     coords (false). Without this, even with a visible
                //     GLFW cursor, clicks bounce the cursor to screen center
                //     because MC's mouse-look code is still consuming the
                //     deltas.
                String stepC = "skip-noMouse";
                if (mouse != null) {
                    if (mouseLockedField == null) {
                        // Try the verified intermediary first, then yarn name.
                        Field lf = findFieldUp(mouse.getClass(), "field_1783");
                        if (lf == null) lf = findFieldUp(mouse.getClass(), "cursorLocked");
                        if (lf != null) { lf.setAccessible(true); mouseLockedField = lf; }
                    }
                    if (mouseLockedField != null) {
                        try {
                            mouseLockedField.setBoolean(mouse, false);
                            stepC = "ok(" + mouseLockedField.getName() + ")";
                        } catch (Throwable t) { stepC = "FAIL " + t.getClass().getSimpleName(); }
                    } else stepC = "skip-noField";
                }
                if (!cursorUnlockLogged) {
                    cursorUnlockLogged = true;
                    System.out.println("[ShadowHud][Cursor] menu OPEN unlock:"
                        + " mouse=" + (mouse != null)
                        + " glfwSetInputMode=" + (glfwSetInputMode != null)
                        + " window=" + windowHandle
                        + " mouseClass=" + (mouse != null ? mouse.getClass().getName() : "null")
                        + " stepA=" + stepA + " stepB=" + stepB + " stepC=" + stepC);
                }
                cursorReleased = true;
            } else if (cursorReleased) {
                if (mouse != null) tryInvoke(mouse, "method_1612", "lockCursor");
                if (glfwSetInputMode != null && windowHandle > 0) {
                    try { glfwSetInputMode.invoke(null, windowHandle, 0x00033001, 0x00034003); }
                    catch (Throwable ignored) {}
                }
                if (mouse != null && mouseLockedField != null) {
                    try { mouseLockedField.setBoolean(mouse, true); }
                    catch (Throwable ignored) {}
                }
                cursorReleased = false;
            }
        } catch (Throwable ignored) {}

        // Zoom: hold C to drop FOV by 4× (Lunar default behaviour). Restores
        // the user's original FOV on release. We swallow the key so other
        // handlers don't stack with us.
        boolean cDown = keyDown(KEY_C);
        if (modOn("Zoom", true)) {
            if (cDown && !zoomActive) startZoom();
            else if (!cDown && zoomActive) stopZoom();
        } else if (zoomActive) {
            stopZoom();
        }
        // Drive the smooth-zoom animation each frame. Decoupled from
        // start/stop so press/release just sets the target and the per-frame
        // step handles easing toward it.
        tickZoomAnimation();

        // AutoRespawn: when the module is on AND the player is dead, fire a
        // ClientStatusC2SPacket(PERFORM_RESPAWN) ~250 ms after death so the
        // death screen flashes briefly (gives the user a moment to see what
        // killed them) before the launcher hits the button for them.
        // Also: piggy-back DeathLog and CoordsHistory off the same `dead`
        // probe — saves another isDead reflection call every frame.
        {
            Object p = null;
            try { p = playerField != null ? playerField.get(mc) : null; }
            catch (Throwable ignored) {}
            boolean isDead = false;
            if (p != null) {
                try {
                    Object dead = tryInvoke(p, "method_29504", "isDead", "isDeadOrDying");
                    isDead = Boolean.TRUE.equals(dead);
                } catch (Throwable ignored) {}
            }
            if (modOn("AutoRespawn", false)) {
                resolveAutoRespawn();
                if (isDead) {
                    long now = System.currentTimeMillis();
                    if (autoRespawnArmedAtMs == 0) autoRespawnArmedAtMs = now;
                    if (now - autoRespawnArmedAtMs > cfgAutoRespawnDelay) {
                        try { fireAutoRespawn(); } catch (Throwable ignored) {}
                        autoRespawnArmedAtMs = now;
                    }
                } else {
                    autoRespawnArmedAtMs = 0;
                }
            }
            // DeathLog: write a record on the rising edge of "dead" only.
            // The player can re-die-respawn-die in the same minute and we
            // want each death logged separately, so flip the prev flag back
            // to false the moment we observe alive again.
            if (modOn("DeathLog", false) && p != null) {
                if (isDead && !deathLogPrevDead) {
                    writeDeathLog(p);
                }
                deathLogPrevDead = isDead;
            }
            // CoordsHistory: only sample when alive (no point logging the
            // death-screen frozen position).
            if (modOn("CoordsHistory", false) && p != null && !isDead) {
                tickCoordsHistory(p);
            }
        }

        // AutoReconnect: track the last server we were on (multiplayer only).
        // When we transition from "in world" to "title screen" UNEXPECTEDLY,
        // and the user has the module enabled, attempt to rejoin after a
        // 3-second cooldown. Server is sticky — we remember it across the
        // disconnect screen so the user just has to wait.
        try {
            tickAutoReconnect();
        } catch (Throwable ignored) {}

        // HUD editor mode — handle drag-to-move, scroll-wheel-resize, Esc-save.
        // Only ticks when active so it has zero overhead when off.
        if (hudEditMode) {
            try { tickHudEditor(); }
            catch (Throwable t) { logOnce("HudEdit", t); }
        }

        // Toggle Sprint / Sneak: hold the matching action while the
        // module is ON.
        //
        // ToggleSneak still uses the old "force KeyBinding.pressed=true"
        // trick — sneak just slows movement + edge protection, has no
        // internal state-machine to fight with, stays smooth.
        //
        // ToggleSprint used to do the same, but vanilla's sprint detection
        // looks for the FRESH-PRESS edge (keySprint.wasPressed() consuming
        // timesPressed) rather than the held state, so pressed=true never
        // engaged sprint cleanly — vanilla kept flicking isSprinting off
        // whenever its own checks ran (collision, brief food drop, etc.)
        // and we kept slamming the key back to pressed every tick. Net
        // result: visible FOV / animation / speed flicker every few frames
        // = "choppy". We now drive setSprinting() directly each tick the
        // user is moving forward, which keeps the state stable (same path
        // AutoSprint uses).
        resolveKeyBindings();
        if (kbSetPressed != null && keySneakBinding != null) {
            try {
                if (modOn("ToggleSneak", false)) {
                    kbSetPressed.invoke(keySneakBinding, true);
                }
            } catch (Throwable ignored) {}
        }
        if (modOn("ToggleSprint", false)) {
            try {
                Object sprintPlayer = playerField != null ? playerField.get(mc) : null;
                if (sprintPlayer != null) {
                    // Only engage sprint when the user actually wants to move
                    // forward. 87 = GLFW_KEY_W. We poll GLFW directly so this
                    // works regardless of menu state / KeyBinding focus.
                    boolean wDown = false;
                    if (glfwGetKey != null && windowHandle > 0) {
                        wDown = (int) glfwGetKey.invoke(null, windowHandle, 87) == 1;
                    }
                    if (wDown) {
                        Method ss = findMethodByName(sprintPlayer.getClass(), "method_5728");
                        if (ss == null) ss = findMethodByName(sprintPlayer.getClass(), "setSprinting");
                        if (ss != null) ss.invoke(sprintPlayer, true);
                    }
                }
            } catch (Throwable t) { logOnce("ToggleSprint", t); }
        }

        if (!menuOpen) return;

        // Eat every vanilla keybind press while the menu is open so that
        // typing "i" doesn't open the inventory, "e" doesn't, and so on.
        // KeyBinding.unpressAll() (intermediary method_1437) is a static
        // sweep that resets every binding's `pressed` + `timesPressed`
        // counters — same call MC makes when you open a Screen.
        try {
            Class<?> kb = Class.forName("net.minecraft.class_304");
            kb.getMethod("method_1437").invoke(null);
        } catch (Throwable ignored) {
            try {
                Class<?> kb = Class.forName("net.minecraft.class_304");
                kb.getMethod("unpressAll").invoke(null);
            } catch (Throwable ignored2) {}
        }

        // ----- Type-to-search filter — auto-activates on first letter ----
        // Big UX win: if you start typing letters/digits with the menu open,
        // we automatically focus the search bar and start filtering. No
        // click required first. Backspace works once any text is buffered.
        // Esc clears the search and exits typing mode.
        if (!hexInputActive) {
            // Detect any A-Z / 0-9 edge — the FIRST one auto-activates search
            // if it isn't already on. Subsequent letters extend the buffer.
            boolean typedAny = false;
            int typedKey = -1;
            for (int kc = 65; kc <= 90; kc++) {        // A-Z
                if (edge(kc)) { typedKey = kc; typedAny = true; break; }
            }
            if (!typedAny) {
                for (int kc = 48; kc <= 57; kc++) {    // 0-9
                    if (edge(kc)) { typedKey = kc; typedAny = true; break; }
                }
            }
            if (typedAny) {
                menuSearchActive = true;
                menuSearchBuf.append((char) typedKey);
                moduleScrollTop = 0;
            }
            // Backspace + Esc only when search is already active
            if (menuSearchActive) {
                if (edge(259) /* BkSp */ && menuSearchBuf.length() > 0) {
                    menuSearchBuf.setLength(menuSearchBuf.length() - 1);
                }
                if (edge(256) /* Esc */) {
                    if (menuSearchBuf.length() > 0) menuSearchBuf.setLength(0);
                    else menuSearchActive = false;
                }
            }
        }

        // ----- Hex color picker (active when user pressed = on Crosshair) ---
        if (hexInputActive) {
            if (edge(KEY_ESC_HEX)) {
                hexInputActive = false;
                themePickerActive = false;
                modulePickerActive = null;
                glintPickerActive = false;
                hexInputBuf.setLength(0); return;
            }
            if (edge(KEY_ENTER) && hexInputBuf.length() == 6) {
                try {
                    int rgb = Integer.parseInt(hexInputBuf.toString(), 16);
                    int argb = 0xFF000000 | (rgb & 0xFFFFFF);
                    if (themePickerActive) {
                        cfgThemeCustom = argb;
                        cfgThemeIdx = THEME_COLORS.length;   // select custom slot
                        themePickerActive = false;
                    } else if (glintPickerActive) {
                        cfgGlintColor = argb;
                        glintPickerActive = false;
                    } else if (modulePickerActive != null) {
                        moduleColorOverrides.put(modulePickerActive, argb);
                        modulePickerActive = null;
                    } else {
                        customCrosshairColor = argb;
                        crosshairColorIdx = CROSSHAIR_COLORS.length;
                        MODULES.put("Crosshair", true);
                    }
                    saveConfig();
                } catch (NumberFormatException ignored) {}
                hexInputActive = false; hexInputBuf.setLength(0); return;
            }
            if (edge(KEY_BACKSPACE_HEX) && hexInputBuf.length() > 0) {
                hexInputBuf.setLength(hexInputBuf.length() - 1);
            }
            // 0-9
            for (int kc = 48; kc <= 57 && hexInputBuf.length() < 6; kc++) {
                if (edge(kc)) hexInputBuf.append((char) kc);
            }
            // A-F
            for (int kc = 65; kc <= 70 && hexInputBuf.length() < 6; kc++) {
                if (edge(kc)) hexInputBuf.append((char) kc);
            }
            return;   // swallow other input while editing
        }

        // ----- Open hex picker when '=' pressed and Crosshair is selected ---
        if (edge(KEY_EQUALS)) {
            int idxInModules = modSelected - PRESETS.length;
            if (idxInModules >= 0 && idxInModules < MODULES.size()) {
                int i = 0;
                for (String n : MODULES.keySet()) {
                    if (i == idxInModules) {
                        if ("Crosshair".equals(n)) {
                            hexInputActive = true; hexInputBuf.setLength(0);
                        }
                        break;
                    }
                    i++;
                }
            }
        }

        // ----- Standard menu navigation -------------------------------------
        int presetCount = PRESETS.length;
        // Mirror the live grid-column setting so arrow navigation steps the
        // same number of cards as the visual layout. Was hardcoded to 2 which
        // skipped/repeated cards when the user picked 3 or 4 columns.
        int gridCols    = Math.max(1, Math.min(4, menuGridCols));
        int modulesBase = presetCount;
        int filteredCount = countFilteredModules();
        int modListBase = presetCount + filteredCount;
        int totalRows   = modListBase + MODS.size();

        if (edge(KEY_UP)) {
            if (modSelected >= modulesBase && modSelected < modListBase) {
                // grid: UP = up one row (= -gridCols), but leaving the grid
                // upward lands on the preset bar at the matching column.
                int idx = modSelected - modulesBase;
                if (idx < gridCols) {
                    modSelected = Math.min(presetCount - 1, idx);
                } else {
                    modSelected -= gridCols;
                }
                // Clamp in case the filtered list shrank
                if (modSelected >= modListBase) modSelected = Math.max(0, modListBase - 1);
            } else {
                modSelected = Math.max(0, modSelected - 1);
            }
        }
        if (edge(KEY_DOWN)) {
            if (modSelected < presetCount) {
                // preset row → first row of grid, same column
                modSelected = modulesBase + Math.min(Math.max(0, filteredCount - 1), modSelected);
            } else if (modSelected < modListBase) {
                int idx = modSelected - modulesBase;
                int newIdx = idx + gridCols;
                if (newIdx < filteredCount) {
                    modSelected = modulesBase + newIdx;
                } else {
                    modSelected = modListBase;   // fall through to mod list
                }
            } else {
                modSelected = Math.min(totalRows - 1, modSelected + 1);
            }
        }
        if (edge(KEY_LEFT))  modSelected = Math.max(0, modSelected - 1);
        if (edge(KEY_RIGHT)) modSelected = Math.min(totalRows - 1, modSelected + 1);
        if (edge(KEY_PAGE_UP))   modSelected = Math.max(0, modSelected - 10);
        if (edge(KEY_PAGE_DOWN)) modSelected = Math.min(totalRows - 1, modSelected + 10);
        if (edge(KEY_HOME))      modSelected = 0;
        if (edge(KEY_END))       modSelected = Math.max(0, totalRows - 1);
        if (edge(KEY_R))         loadMods();

        // Enter or Space activates the highlighted entry (standard a11y).
        if (edge(KEY_ENTER) || edge(32) /* SPACE */) {
            if (modSelected < presetCount) {
                applyPreset(PRESETS[modSelected]);
            } else if (modSelected < presetCount + countFilteredModules()) {
                int wantIdx = modSelected - presetCount;
                Map.Entry<String, Boolean> e = filteredModuleAt(wantIdx);
                if (e != null) {
                    cycleOrToggle(e);
                    saveConfig();
                }
            } else {
                toggleMod(modSelected - presetCount - countFilteredModules());
            }
        }
    }


    /** Rising-edge detector — true exactly on the frame a key is pressed. */
    private static boolean edge(int kc) {
        if (kc < 0 || kc >= prevKey.length) return false;
        boolean down = keyDown(kc);
        boolean fire = down && !prevKey[kc];
        prevKey[kc] = down;
        return fire;
    }

    /** Scan the mods/ folder and build an alphabetical list with enable state. */
    private static void loadMods() {
        MODS.clear();
        Path dir = Paths.get("mods");
        if (!Files.exists(dir)) { modsLoaded = true; return; }
        Map<String, Boolean> byBase = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (java.util.stream.Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (n.endsWith(".jar"))           byBase.put(n.substring(0, n.length() - 4), true);
                else if (n.endsWith(".jar.disabled")) byBase.put(n.substring(0, n.length() - 13), false);
            });
        } catch (IOException ignored) {}
        for (Map.Entry<String, Boolean> e : byBase.entrySet()) {
            MODS.add(new ModEntry(e.getKey(), e.getValue()));
        }
        modsLoaded = true;
    }

    /** Rename selected mod's jar → jar.disabled (or back). Takes effect on restart. */
    private static void toggleMod(int idx) {
        if (idx < 0 || idx >= MODS.size()) return;
        ModEntry m = MODS.get(idx);
        Path dir = Paths.get("mods");
        Path on  = dir.resolve(m.base + ".jar");
        Path off = dir.resolve(m.base + ".jar.disabled");
        try {
            if (m.enabled)  Files.move(on,  off);
            else            Files.move(off, on);
            m.enabled = !m.enabled;
        } catch (IOException ignored) {}
    }

    private static boolean shadowhud$firstSpawnLogged;
    private static boolean shadowhud$searchClickLogged;

    /** Try several candidate field names; return the first non-null String
     *  found. Walks the class hierarchy in case the field is on a superclass. */
    private static String readStringField(Object target, String... names) {
        Class<?> c = target.getClass();
        while (c != null) {
            for (String n : names) {
                try {
                    Field f = c.getDeclaredField(n);
                    f.setAccessible(true);
                    Object v = f.get(target);
                    if (v instanceof String) return (String) v;
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** Last-ditch fallback used by Server: enumerate every String field on
     *  the target's class hierarchy and return the first one that looks like
     *  a server address (contains '.' or ':'). Lets us survive intermediary
     *  rename without having to chase field numbers. */
    private static String findAnyAddressLikeString(Object target) {
        Class<?> c = target.getClass();
        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getType() != String.class) continue;
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(target);
                    if (v instanceof String) {
                        String s = (String) v;
                        // address contains a dot (mc.hypixel.net) or colon (1.2.3.4:25565)
                        if (!s.isEmpty() && (s.contains(".") || s.contains(":"))) {
                            return s;
                        }
                    }
                } catch (Throwable ignored) {}
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** Drop entries older than `now - 1000ms` so the deque holds at most the
     *  last second of click timestamps — that count is the CPS readout. */
    private static void trimWindow(java.util.Deque<Long> q, long now) {
        while (!q.isEmpty() && q.peekFirst() < now - 1000) q.pollFirst();
    }

    /** Cheap "is the user doing anything?" check for the AFK module — sample
     *  WASD, space, mouse buttons, and the most common move keys. We don't
     *  iterate the whole 350-entry keymap because that would be a lot of
     *  reflection calls every frame for a marginal accuracy improvement. */
    private static boolean anyInputActive() {
        int[] sample = {KEY_W, KEY_A, KEY_S, KEY_D, KEY_SPACE, KEY_C,
                        340 /*LShift*/, 341 /*LCtrl*/, 342 /*LAlt*/};
        for (int kc : sample) if (keyDown(kc)) return true;
        return mouseButtonDown(MB_LEFT) || mouseButtonDown(MB_RIGHT);
    }

    /** Resolve mc.options.gamma as a SimpleOption. Same SimpleOption getter/
     *  setter used by Zoom — we just resolve a different field.
     *
     *  In 1.21.x GameOptions field_1840 is package-private, so getField()
     *  returns null and our prior fallback ran. Switch to getDeclaredField +
     *  setAccessible(true) so we can read it. */
    private static void resolveGammaOption() {
        if (gammaResolveTried) return;
        gammaResolveTried = true;
        try {
            Object opts = null;
            // Walk the chain of names — field_1690 (intermediary) or "options"
            try { opts = mc.getClass().getField("field_1690").get(mc); }
            catch (NoSuchFieldException ne) {
                try { opts = mc.getClass().getField("options").get(mc); }
                catch (NoSuchFieldException ignored) {}
            }
            if (opts == null) {
                Field of = null;
                try { of = mc.getClass().getDeclaredField("field_1690"); }
                catch (NoSuchFieldException e) {
                    try { of = mc.getClass().getDeclaredField("options"); } catch (NoSuchFieldException ignored) {}
                }
                if (of != null) { of.setAccessible(true); opts = of.get(mc); }
            }
            if (opts == null) {
                System.err.println("[ShadowHud][Fullbright] cannot find mc.options field");
                return;
            }
            // 1.21.11: SimpleOption fields are private. findFieldUp finds
            // them, but we MUST type-check — field_1841 is a Boolean
            // SimpleOption (not Double/gamma), and accepting it would
            // ClassCast-fail downstream. Also prefer field_1843 (Double
            // SimpleOption — current intermediary for gamma) when present.
            Class<?> simpleOptCls;
            try { simpleOptCls = Class.forName("net.minecraft.class_7172"); }
            catch (Throwable t) { simpleOptCls = null; }
            String[] gammaNames = {
                "field_1843", "field_1840", "field_1842", "gamma"
                // field_1841 deliberately excluded — Boolean in 1.21.11
            };
            Field f = null;
            for (String n : gammaNames) {
                Field cand = findFieldUp(opts.getClass(), n);
                if (cand == null) continue;
                if (simpleOptCls == null
                    || simpleOptCls.isAssignableFrom(cand.getType())) {
                    f = cand; break;
                }
            }
            // Last-ditch: brute-force scan SimpleOption fields, pick any
            // Double-valued one in the gamma range. Heuristic: gamma's
            // default is somewhere in [0.0, 1.0], and Fullbright will push
            // it outside that range — but on first launch it should be
            // < 1.0. Sound volume options also live in [0,1], so we
            // disambiguate by checking the field's declaration class
            // (gamma is on the main GameOptions class, sound volumes are
            // typically nested in SoundOptions or kept separately).
            if (f == null) {
                f = findSimpleOptionByValue(opts, "Fullbright", v ->
                    (v instanceof Double || v instanceof Float)
                        && ((Number) v).doubleValue() >= 0.0
                        && ((Number) v).doubleValue() <= 1.0);
            }
            if (f != null) {
                f.setAccessible(true);
                gammaOption = f.get(opts);
            } else {
                System.err.println("[ShadowHud][Fullbright] gamma field NOT found — Fullbright will be no-op");
                dumpSimpleOptionFields(opts, "Fullbright");
            }
            // Re-use Zoom's resolver for getValue/setValue — it caches them as
            // static fields on the class. Trigger it if not already done.
            resolveFovOption();
            System.out.println("[ShadowHud][Fullbright] gamma option ready: "
                + (gammaOption != null) + (f != null ? " (via " + f.getName() + ")" : " (field NOT found)"));
        } catch (Throwable t) {
            System.err.println("[ShadowHud][Fullbright] reflection: " + t);
        }
    }

    /** Walk the class hierarchy looking for a declared field by name. */
    private static Field findFieldUp(Class<?> cls, String name) {
        while (cls != null) {
            try { return cls.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        return null;
    }

    /** Resolve a pre-built ClientStatusC2SPacket(PERFORM_RESPAWN) plus the
     *  network handler's send method, then cache them. The packet itself is
     *  immutable — we can build it once and re-send forever. */
    private static void resolveAutoRespawn() {
        if (autoRespawnTried) return;
        autoRespawnTried = true;
        try {
            // ClientStatusC2SPacket is class_2799 (was 2899 in older mappings),
            // and its Mode enum is class_2799$class_2800.
            Class<?> pkt;
            try { pkt = Class.forName("net.minecraft.class_2799"); }
            catch (ClassNotFoundException e) { pkt = Class.forName("net.minecraft.class_2899"); }
            Class<?> mode;
            try { mode = Class.forName(pkt.getName() + "$class_2800"); }
            catch (ClassNotFoundException e) { mode = Class.forName(pkt.getName() + "$class_2900"); }
            Object respawnEnum = null;
            for (Object c : mode.getEnumConstants()) {
                String s = c.toString();
                if (s.equals("PERFORM_RESPAWN") || s.contains("RESPAWN")) {
                    respawnEnum = c; break;
                }
            }
            if (respawnEnum != null) {
                respawnPacket = pkt.getConstructor(mode).newInstance(respawnEnum);
            }
            System.out.println("[ShadowHud][AutoRespawn] packet ready: " + (respawnPacket != null));
        } catch (Throwable t) {
            System.err.println("[ShadowHud][AutoRespawn] reflection: " + t);
        }
    }

    /** Send the cached respawn packet. Resolves the network handler each time
     *  because it changes between dimension/world joins. */
    private static void fireAutoRespawn() {
        if (respawnPacket == null) return;
        try {
            Object handler = tryInvoke(mc, "method_1562", "getNetworkHandler", "getConnection");
            if (handler == null) return;
            if (sendPacketMethod == null) {
                for (Method m : handler.getClass().getMethods()) {
                    if (m.getParameterCount() != 1) continue;
                    String n = m.getName();
                    if ((n.equals("method_52787") || n.equals("sendPacket") || n.equals("send"))
                        && m.getParameterTypes()[0].isInstance(respawnPacket)) {
                        sendPacketMethod = m; break;
                    }
                }
            }
            if (sendPacketMethod != null) {
                sendPacketMethod.invoke(handler, respawnPacket);
            }
        } catch (Throwable ignored) {}
    }

    /** AutoReconnect state. We sample mc.getCurrentServerEntry() each tick;
     *  when we go from a non-null entry to null AND the player is now on a
     *  title/disconnect screen, we treat that as a disconnect and remember
     *  the last server. After {@link #AUTORECONNECT_DELAY_MS}, we attempt
     *  to rejoin. Cleared if the user manually disconnects via Esc-Quit. */
    private static String  autoReconnectLastAddr = null;
    private static String  autoReconnectLastName = null;
    private static long    autoReconnectAttemptAtMs = 0L;
    private static boolean autoReconnectArmed = false;
    private static final long AUTORECONNECT_DELAY_MS = 3000L;
    private static long    autoReconnectLastSeenInWorldMs = 0L;

    /** Attempts to detect a server disconnect and rejoin the last server
     *  the user was on. Tracks state across frames:
     *    1. While in-world, snapshot mc.getCurrentServerEntry().
     *    2. When in-world goes false (mc.player == null) AND we were just
     *       in-world < 1s ago, mark the disconnect as "automatic" and arm
     *       the rejoin timer.
     *    3. After {@link #AUTORECONNECT_DELAY_MS}, fire ServerListEntry
     *       connect via reflection. Disarm so we don't loop on rejoin
     *       failure. */
    /** HUD editor input handler — runs every frame while {@link #hudEditMode}
     *  is true. Drag = move stack, scroll wheel = cycle scale, Esc = save+exit. */
    private static void tickHudEditor() {
        // Esc to exit
        if (edge(256)) {
            hudEditMode = false;
            hudDragging = false;
            saveConfig();
            flashToast("§a✓ HUD position saved");
            return;
        }
        // Left-mouse drag
        boolean lmb = mouseButtonDown(MB_LEFT);
        if (lmb && !hudDragging) {
            // Start drag if cursor is in the HUD frame (with margin)
            if (mouseX >= hudFrameL - 8 && mouseX <= hudFrameR + 8
             && mouseY >= hudFrameT - 8 && mouseY <= hudFrameB + 8) {
                hudDragging = true;
                hudDragOriginX = mouseX;
                hudDragOriginY = mouseY;
                hudDragStartOffX = cfgHudOffsetX;
                hudDragStartOffY = cfgHudOffsetY;
            }
        } else if (!lmb && hudDragging) {
            hudDragging = false;
        }
        if (hudDragging && lmb) {
            // Translate physical mouse delta to logical (post-scale) offset
            float scale = cfgHudScale / 100f;
            int dx = (int)((mouseX - hudDragOriginX) / scale);
            int dy = (int)((mouseY - hudDragOriginY) / scale);
            cfgHudOffsetX = hudDragStartOffX + dx;
            cfgHudOffsetY = hudDragStartOffY + dy;
        }
        // Scroll-wheel = adjust scale by 5% steps
        if (Math.abs(pendingScrollDelta) >= 0.5) {
            int dir = pendingScrollDelta > 0 ? 5 : -5;
            cfgHudScale = Math.max(50, Math.min(200, cfgHudScale + dir));
            pendingScrollDelta = 0.0;
        }
    }

    private static void tickAutoReconnect() {
        if (mc == null) return;
        boolean enabled = modOn("AutoReconnect", false);
        long now = System.currentTimeMillis();
        Object plr = null;
        try { plr = playerField != null ? playerField.get(mc) : null; }
        catch (Throwable ignored) {}
        boolean inWorld = (plr != null);

        // While in-world, remember the current server so we can rejoin later
        if (inWorld) {
            autoReconnectLastSeenInWorldMs = now;
            try {
                Object srv = tryInvoke(mc, "method_1558", "getCurrentServerEntry");
                if (srv != null) {
                    String addr = readStringField(srv, "field_3761", "address");
                    String nm   = readStringField(srv, "field_3752", "name");
                    if (addr != null && !addr.isEmpty()) {
                        autoReconnectLastAddr = addr;
                        autoReconnectLastName = nm != null ? nm : addr;
                    }
                }
            } catch (Throwable ignored) {}
            // Disarm any pending rejoin once we're successfully back in-world
            autoReconnectArmed = false;
            autoReconnectAttemptAtMs = 0;
            return;
        }

        if (!enabled || autoReconnectLastAddr == null) return;
        // We're NOT in-world — was this a disconnect (vs. user closed world)?
        // Heuristic: if we were in-world less than 1.5 s ago, this is a
        // network drop, not a clean exit. Arm the rejoin.
        if (!autoReconnectArmed && now - autoReconnectLastSeenInWorldMs < 1500L
            && now - autoReconnectLastSeenInWorldMs > 200L) {
            autoReconnectArmed = true;
            autoReconnectAttemptAtMs = now + AUTORECONNECT_DELAY_MS;
            System.out.println("[ShadowHud][AutoReconnect] disconnect detected — "
                + "rejoining " + autoReconnectLastAddr + " in "
                + (AUTORECONNECT_DELAY_MS / 1000) + "s");
            flashToast("§e↺ Reconnecting to §f" + autoReconnectLastName + "§e in 3s");
        }
        if (autoReconnectArmed && now >= autoReconnectAttemptAtMs) {
            autoReconnectArmed = false;
            try {
                attemptReconnect(autoReconnectLastAddr, autoReconnectLastName);
            } catch (Throwable t) {
                System.err.println("[ShadowHud][AutoReconnect] failed: " + t);
            }
        }
    }

    /** Build a ServerInfo + ConnectScreen call to reconnect to {@code addr}.
     *  Equivalent to the user clicking "Direct Connect → addr" on the title
     *  screen. */
    private static void attemptReconnect(String addr, String displayName) {
        try {
            // class_642 = ServerInfo. Constructor: (String name, String address, ServerType type).
            // The inner ServerType enum was renamed in 1.21.11 (class_2891 → other).
            // Discover it dynamically from a constructor parameter type so we
            // survive future renames.
            Class<?> serverInfoCls = Class.forName("net.minecraft.class_642");
            Class<?> srvTypeEnum = null;
            java.lang.reflect.Constructor<?> infoCtor = null;
            for (java.lang.reflect.Constructor<?> c : serverInfoCls.getDeclaredConstructors()) {
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length == 3 && pt[0] == String.class && pt[1] == String.class && pt[2].isEnum()) {
                    srvTypeEnum = pt[2];
                    infoCtor = c;
                    break;
                }
            }
            if (infoCtor == null || srvTypeEnum == null) {
                // Fallback: 4-arg or other constructors. Pick the first with String, String, enum, ...
                for (java.lang.reflect.Constructor<?> c : serverInfoCls.getDeclaredConstructors()) {
                    Class<?>[] pt = c.getParameterTypes();
                    if (pt.length >= 3 && pt[0] == String.class && pt[1] == String.class && pt[2].isEnum()) {
                        srvTypeEnum = pt[2];
                        infoCtor = c;
                        break;
                    }
                }
            }
            if (infoCtor == null) {
                System.err.println("[ShadowHud][AutoReconnect] no usable ServerInfo constructor");
                return;
            }
            infoCtor.setAccessible(true);
            Object[] enumConstants = srvTypeEnum.getEnumConstants();
            Object srvType = enumConstants.length > 0 ? enumConstants[0] : null;
            for (Object e : enumConstants) {
                if (String.valueOf(e).contains("OTHER")) { srvType = e; break; }
            }
            // Build args matching ctor's parameter count (some have extras like UUID).
            Class<?>[] pts = infoCtor.getParameterTypes();
            Object[] ctorArgs = new Object[pts.length];
            ctorArgs[0] = (displayName != null ? displayName : addr);
            ctorArgs[1] = addr;
            ctorArgs[2] = srvType;
            for (int i = 3; i < pts.length; i++) {
                if (pts[i] == boolean.class) ctorArgs[i] = false;
                else                         ctorArgs[i] = null;
            }
            Object serverInfo = infoCtor.newInstance(ctorArgs);
            // class_412 = ConnectScreen. method_36877(parent, mc, addr/info, ...)
            // Try multiple shapes since the API has shifted.
            Class<?> connectCls = Class.forName("net.minecraft.class_412");
            // The simplest route: call connect(Screen parent, MinecraftClient mc, ServerAddress addr, ServerInfo info, ...)
            // But signatures vary. Use the static method that takes (Screen, MinecraftClient, ServerAddress, ServerInfo).
            Class<?> screenCls = Class.forName("net.minecraft.class_437");
            Class<?> mcCls = mc.getClass();
            // class_639 = ServerAddress in 1.21.11 (class_2580 is BeaconBlockEntity).
            // Fall back through other candidates so the lookup survives mapping shifts.
            Class<?> addrCls = null;
            for (String cn : new String[]{
                    "net.minecraft.class_639",
                    "net.minecraft.class_634$class_2891",
                    "net.minecraft.client.network.ServerAddress"}) {
                try { addrCls = Class.forName(cn); break; } catch (ClassNotFoundException ignored) {}
            }
            if (addrCls == null) {
                System.err.println("[ShadowHud][AutoReconnect] no ServerAddress class found");
                return;
            }
            // Build a ServerAddress
            Method serverAddrParse = null;
            for (Method m : addrCls.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != String.class) continue;
                if (!addrCls.isAssignableFrom(m.getReturnType())) continue;
                serverAddrParse = m; break;
            }
            Object serverAddress = serverAddrParse != null
                ? serverAddrParse.invoke(null, addr) : null;
            // Find a static `connect` method on class_412
            Method connectMethod = null;
            for (Method m : connectCls.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                String n = m.getName();
                if (!"method_36877".equals(n) && !"connect".equals(n)) continue;
                connectMethod = m;
                break;
            }
            if (connectMethod != null) {
                Class<?>[] pt = connectMethod.getParameterTypes();
                Object[] args = new Object[pt.length];
                for (int i = 0; i < pt.length; i++) {
                    if (pt[i].isAssignableFrom(screenCls))                args[i] = null;
                    else if (pt[i].isAssignableFrom(mcCls))                args[i] = mc;
                    else if (pt[i].isAssignableFrom(addrCls))              args[i] = serverAddress;
                    else if (pt[i].isAssignableFrom(serverInfoCls))        args[i] = serverInfo;
                    else if (pt[i] == boolean.class)                       args[i] = false;
                    else                                                    args[i] = null;
                }
                connectMethod.invoke(null, args);
                System.out.println("[ShadowHud][AutoReconnect] connect() invoked");
                flashToast("§a✓ Reconnecting to §f" + (displayName != null ? displayName : addr));
            } else {
                System.err.println("[ShadowHud][AutoReconnect] no connect method on class_412");
            }
        } catch (Throwable t) {
            System.err.println("[ShadowHud][AutoReconnect] reconnect error: " + t);
            flashToast("§c✕ AutoReconnect failed — see console");
        }
    }

    /** Resolve mc.options.keySprint / keySneak (KeyBinding objects) and
     *  KeyBinding.setPressed(boolean). Done once on the first poll. */
    private static void resolveKeyBindings() {
        if (keyBindReflectionTried) return;
        keyBindReflectionTried = true;
        try {
            Object opts = mc.getClass().getField("field_1690").get(mc);
            // keySprint: GameOptions.field_1867 (or "keySprint")
            try { keySprintBinding = opts.getClass().getField("field_1867").get(opts); }
            catch (NoSuchFieldException ignored) {
                try { keySprintBinding = opts.getClass().getField("keySprint").get(opts); }
                catch (NoSuchFieldException nse) {}
            }
            // keySneak: GameOptions.field_1832 (or "keySneak" / "keyShift")
            try { keySneakBinding = opts.getClass().getField("field_1832").get(opts); }
            catch (NoSuchFieldException ignored) {
                try { keySneakBinding = opts.getClass().getField("keySneak").get(opts); }
                catch (NoSuchFieldException nse) {
                    try { keySneakBinding = opts.getClass().getField("keyShift").get(opts); }
                    catch (NoSuchFieldException nse2) {}
                }
            }
            // keyUse: GameOptions.field_1904 (or "keyUse" / "useItemKey").
            // Required by AutoEat to simulate holding right-click on food.
            try { keyUseBinding = opts.getClass().getField("field_1904").get(opts); }
            catch (NoSuchFieldException ignored) {
                try { keyUseBinding = opts.getClass().getField("keyUse").get(opts); }
                catch (NoSuchFieldException nse) {
                    try { keyUseBinding = opts.getClass().getField("useItemKey").get(opts); }
                    catch (NoSuchFieldException nse2) {}
                }
            }
            // KeyBinding.setPressed(boolean) — class_304.method_23481
            Object probe = keySprintBinding != null ? keySprintBinding : keySneakBinding;
            if (probe != null) {
                for (Method m : probe.getClass().getMethods()) {
                    if (m.getParameterCount() != 1) continue;
                    if (m.getParameterTypes()[0] != boolean.class) continue;
                    String n = m.getName();
                    if (n.equals("method_23481") || n.equals("setPressed")
                        || n.equals("setKeyPressed")) {
                        kbSetPressed = m; break;
                    }
                }
            }
            System.out.println("[ShadowHud][Toggle] sprint=" + (keySprintBinding != null)
                + " sneak=" + (keySneakBinding != null)
                + " setPressed=" + (kbSetPressed != null));
        } catch (Throwable t) {
            System.err.println("[ShadowHud][Toggle] keybind reflection: " + t);
        }
    }

    /** Resolve `mc.options.fov` (a SimpleOption<Integer>) plus its
     *  getValue/setValue methods. We do this exactly once.
     *
     *  Field name has shifted across versions — field_1826 (1.20.x),
     *  field_1858 (older), and 1.21.11 may use yet another intermediary.
     *  We try the known names first, then fall back to a value-based
     *  brute-force: scan all SimpleOption fields on GameOptions and pick
     *  the one whose current value is an Integer in [30, 110] (the fov
     *  slider range). That uniquely identifies fov regardless of name. */
    private static void resolveFovOption() {
        if (zoomReflectionTried) return;
        zoomReflectionTried = true;
        try {
            Object opts = mc.getClass().getField("field_1690").get(mc);  // class_310.options
            // 1.21.11: GameOptions.field_1826 (public SimpleOption fov)
            // is gone — all SimpleOption fields are private now. Some
            // public fields with NEARBY intermediary numbers exist but
            // aren't SimpleOption (field_1827 is a boolean, field_1828 is
            // a String, etc.) — we MUST type-check before accepting.
            Class<?> simpleOptCls;
            try { simpleOptCls = Class.forName("net.minecraft.class_7172"); }
            catch (Throwable t) { simpleOptCls = null; }
            String[] fovNames = {
                "field_1826", "field_1858", "field_1827", "field_1825",
                "field_1828", "field_1829", "field_1862", "fov"
            };
            Field f = null;
            for (String n : fovNames) {
                try {
                    Field cand = opts.getClass().getField(n);
                    // Type guard — only accept if it IS a SimpleOption
                    if (simpleOptCls == null
                        || simpleOptCls.isAssignableFrom(cand.getType())) {
                        f = cand; break;
                    }
                } catch (NoSuchFieldException ignored) {}
            }
            if (f == null) {
                // Brute-force: any SimpleOption whose value is Integer in
                // [30, 110] is almost certainly fov. Uses getDeclaredFields
                // (sees private) + setAccessible — works for the new private
                // SimpleOption fields in 1.21.11.
                f = findSimpleOptionByValue(opts, "fov",
                    v -> v instanceof Integer
                        && ((Integer) v) >= 30 && ((Integer) v) <= 110);
            }
            if (f == null) {
                System.err.println("[ShadowHud][Zoom] fov field NOT found — Zoom will be no-op");
                dumpSimpleOptionFields(opts, "Zoom");
                return;
            }
            f.setAccessible(true);
            fovOption = f.get(opts);
            for (Method m : fovOption.getClass().getMethods()) {
                if (m.getParameterCount() == 0 && m.getReturnType() != void.class
                    && (m.getName().equals("method_41753") || m.getName().equals("getValue"))) {
                    simpleOptionGet = m;
                }
                if (m.getParameterCount() == 1
                    && (m.getName().equals("method_41748") || m.getName().equals("setValue"))) {
                    simpleOptionSet = m;
                }
            }
            System.out.println("[ShadowHud][Zoom] fov option ready via " + f.getName()
                + ": get=" + (simpleOptionGet != null) + " set=" + (simpleOptionSet != null));
        } catch (Throwable t) {
            System.err.println("[ShadowHud][Zoom] FOV reflection failed: " + t);
        }
    }

    /** Brute-force search GameOptions for a SimpleOption whose current value
     *  matches a predicate. Used by fov + gamma resolvers when the known
     *  field names don't match the runtime mappings. */
    private static Field findSimpleOptionByValue(Object opts, String labelForLog,
                                                  java.util.function.Predicate<Object> valueMatches) {
        Class<?> simpleOptCls;
        try { simpleOptCls = Class.forName("net.minecraft.class_7172"); }
        catch (Throwable t) {
            try { simpleOptCls = Class.forName("net.minecraft.client.option.SimpleOption"); }
            catch (Throwable t2) { return null; }
        }
        // Walk the class hierarchy so private inherited fields are reachable.
        for (Class<?> c = opts.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field cand : c.getDeclaredFields()) {
                if (!simpleOptCls.isAssignableFrom(cand.getType())) continue;
                try {
                    cand.setAccessible(true);
                    Object so = cand.get(opts);
                    if (so == null) continue;
                    Object val = null;
                    for (Method m : so.getClass().getMethods()) {
                        if (m.getParameterCount() == 0
                            && m.getReturnType() != void.class
                            && (m.getName().equals("getValue") || m.getName().equals("method_41753"))) {
                            try { val = m.invoke(so); } catch (Throwable ignored) {}
                            break;
                        }
                    }
                    if (val != null && valueMatches.test(val)) {
                        System.out.println("[ShadowHud][" + labelForLog + "] brute-force matched "
                            + cand.getName() + " (value=" + val + ")");
                        return cand;
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /** Diagnostic — dump every SimpleOption field on GameOptions with its
     *  current value so we know exactly what to look for next iteration. */
    private static void dumpSimpleOptionFields(Object opts, String labelForLog) {
        Class<?> simpleOptCls;
        try { simpleOptCls = Class.forName("net.minecraft.class_7172"); }
        catch (Throwable t) {
            try { simpleOptCls = Class.forName("net.minecraft.client.option.SimpleOption"); }
            catch (Throwable t2) { return; }
        }
        StringBuilder sb = new StringBuilder("[ShadowHud][" + labelForLog + "] GameOptions SimpleOption fields:");
        int count = 0;
        for (Class<?> c = opts.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field cand : c.getDeclaredFields()) {
                if (!simpleOptCls.isAssignableFrom(cand.getType())) continue;
                try {
                    cand.setAccessible(true);
                    Object so = cand.get(opts);
                    Object val = null;
                    if (so != null) {
                        for (Method m : so.getClass().getMethods()) {
                            if (m.getParameterCount() == 0
                                && m.getReturnType() != void.class
                                && (m.getName().equals("getValue") || m.getName().equals("method_41753"))) {
                                try { val = m.invoke(so); } catch (Throwable ignored) {}
                                break;
                            }
                        }
                    }
                    sb.append("\n  ").append(cand.getName()).append(" = ").append(val);
                    if (++count > 60) { sb.append("\n  ..."); break; }
                } catch (Throwable ignored) {}
            }
            if (count > 60) break;
        }
        System.out.println(sb.toString());
    }

    private static void startZoom() {
        resolveFovOption();
        if (fovOption == null || simpleOptionGet == null || simpleOptionSet == null) return;
        try {
            Object cur = simpleOptionGet.invoke(fovOption);
            if (cur instanceof Number) {
                // Capture baseline ONLY on the very first press in a session
                // (or after a previous zoom fully released). If we re-press
                // mid-animation (release-then-press fast), savedFov is still
                // the real baseline — DON'T overwrite with the partially-
                // animated current value.
                if (savedFov < 0) {
                    savedFov = ((Number) cur).intValue();
                    zoomCurrentFov = savedFov;   // start animation from baseline
                }
                int zoomedFov = Math.max(10, savedFov / 4);
                zoomTargetFov = zoomedFov;
                zoomAnimating = true;
                zoomActive = true;
                // Don't set FOV directly here — tickZoomAnimation handles it
                // every frame so the camera eases in over ~150ms.
            }
        } catch (Throwable ignored) {}
    }

    private static void stopZoom() {
        if (!zoomActive) return;
        zoomActive = false;
        // Don't snap back instantly — set target=baseline and let the
        // animation interpolate. tickZoomAnimation will reset savedFov to -1
        // once we've fully arrived at the baseline.
        if (savedFov >= 0) {
            zoomTargetFov = savedFov;
            zoomAnimating = true;
        }
    }

    /** Per-frame smooth-zoom step. Called from {@link #pollInput()} every
     *  render frame. Eases {@code zoomCurrentFov} toward {@code zoomTargetFov}
     *  by {@link #ZOOM_EASE} of the remaining delta. When the delta drops
     *  below {@link #ZOOM_SNAP} we set the exact target value and stop
     *  animating. If we're animating back to baseline (zoomActive=false) and
     *  arrive, we release {@code savedFov} so the next press captures fresh.
     *
     *  <p>Skipping this work when {@code !zoomAnimating} avoids touching the
     *  FOV option every frame — important because {@code SimpleOption.setValue}
     *  triggers a callback chain and we don't want to spam it.</p>
     */
    private static void tickZoomAnimation() {
        if (!zoomAnimating) return;
        if (fovOption == null || simpleOptionSet == null) {
            zoomAnimating = false;
            return;
        }
        float delta = zoomTargetFov - zoomCurrentFov;
        if (Math.abs(delta) < ZOOM_SNAP) {
            // Snap to target and finish.
            zoomCurrentFov = zoomTargetFov;
            try {
                simpleOptionSet.invoke(fovOption, Integer.valueOf(Math.round(zoomCurrentFov)));
            } catch (Throwable ignored) {}
            zoomAnimating = false;
            // If we just animated back to baseline AND the key is released,
            // forget the saved value so the next press captures fresh
            // (in case the user changed FOV in options between zoom uses).
            if (!zoomActive && Math.abs(zoomCurrentFov - savedFov) < ZOOM_SNAP) {
                savedFov = -1;
                zoomCurrentFov = -1f;
            }
            return;
        }
        zoomCurrentFov += delta * ZOOM_EASE;
        try {
            simpleOptionSet.invoke(fovOption, Integer.valueOf(Math.round(zoomCurrentFov)));
        } catch (Throwable ignored) {}
    }

    private static boolean keyDownErrorLogged = false;
    private static boolean keyDown(int keyCode) {
        try {
            return (int) glfwGetKey.invoke(null, windowHandle, keyCode) == 1;
        } catch (Throwable t) {
            if (!keyDownErrorLogged) {
                keyDownErrorLogged = true;
                System.err.println("[ShadowHud] keyDown(" + keyCode + ") failed: " + t);
            }
            return false;
        }
    }

    /** True while the given GLFW mouse button is held. Returns false if the
     *  reflective lookup didn't find glfwGetMouseButton (extremely unlikely
     *  with stock LWJGL but harmless if so — the keystrokes UI just shows
     *  the mouse keys as never-pressed). */
    private static boolean mouseButtonDown(int buttonCode) {
        if (glfwGetMouseButton == null || windowHandle < 0) return false;
        try {
            return (int) glfwGetMouseButton.invoke(null, windowHandle, buttonCode) == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ---- HUD lines --------------------------------------------------------

    private static boolean renderHudLogged = false;
    private static boolean drawTextProbeLogged = false;
    private static boolean serverDiagnosticLogged = false;
    private static boolean pingDiagnosticLogged = false;
    private static boolean effectsDiagLogged = false;
    private static boolean biomeDiagLogged = false;
    private static boolean biomeEntryLogged = false;
    private static void renderHud(Object dc) throws Exception {
        if (mc == null) return;
        Object font = fontField != null ? fontField.get(mc) : null;
        if (font == null) return;
        // Reset module-tracking each frame — modOn() will repopulate as
        // each enabled module's render block executes.
        currentModuleRendering = null;
        if (drawTextMethod == null) {
            drawTextMethod = findDrawText(dc.getClass(), font.getClass());
            if (!drawTextProbeLogged) {
                drawTextProbeLogged = true;
                if (drawTextMethod == null) {
                    System.out.println("[ShadowHud] findDrawText returned NULL on "
                        + dc.getClass().getName() + " — HUD cannot render");
                } else {
                    System.out.println("[ShadowHud] drawText = " + drawTextMethod);
                }
            }
            if (drawTextMethod == null) return;
        }
        if (!renderHudLogged) {
            renderHudLogged = true;
            System.out.println("[ShadowHud] renderHud first frame, dc=" + dc.getClass().getSimpleName()
                + " font=" + (font != null) + " modules=" + MODULES);
        }
        // Startup splash — fires once per launch, drawn first so it sits
        // above the HUD column. Set the timestamp now that drawTextMethod
        // is known to be available so the text renders correctly.
        if (splashFirstRenderMs == 0L) {
            splashFirstRenderMs = System.currentTimeMillis();
        }
        renderStartupSplash(dc, font);
        // Glint customization — re-applies if the user changed the slider/
        // swatch. Cheap when nothing changed (early-out on identical state).
        try { tickGlintCustomization(); } catch (Throwable ignored) {}

        int y = 2;
        Object player = playerField != null ? playerField.get(mc) : null;

        // EnchantPreview — render BEFORE the normal HUD column so it gets
        // first crack at the screen even when an enchanting table screen
        // is open. (HudRenderCallback fires every frame regardless of screen.)
        if (modOn("EnchantPreview", true)) {
            try { renderEnchantPreview(dc, font, player); }
            catch (Throwable t) { logOnce("EnchantPreview", t); }
        }

        // Each module line is wrapped so a single reflection miss can't kill
        // the rest of the HUD. Fallback chains attempt alternative method
        // names when the primary intermediary name doesn't match this mapping.
        if (modOn("FPS", true)) {
            try {
                int fps = -1;
                if (getFpsMethod != null) fps = (int) getFpsMethod.invoke(mc);
                if (fps >= 0) {
                    String col = fps >= 144 ? "§a" : fps >= 60 ? "§e" : "§c";
                    if (cfgFpsStyle == 1) {
                        // Bar mode — 10-cell bar where 144 fills it
                        int filled = Math.min(10, fps / 14);
                        StringBuilder b = new StringBuilder("§4FPS ").append(col);
                        for (int i = 0; i < filled; i++) b.append("█");
                        b.append("§8");
                        for (int i = filled; i < 10; i++) b.append("█");
                        b.append(" ").append(col).append(fps);
                        y = drawLine(dc, font, b.toString(), y);
                    } else if (cfgFpsStyle == 2) {
                        // Compact: just the number
                        y = drawLine(dc, font, col + fps, y);
                    } else {
                        // Default
                        String txt = col + fps + (cfgFpsLabel ? "§f FPS" : "");
                        y = drawLine(dc, font, txt, y);
                    }
                }
            } catch (Throwable t) { logOnce("FPS", t); }
        }
        // Always track clicks (even with CPS disabled) so ClickTotal / BestCps
        // and any other consumer can see them.
        try {
            long now = System.currentTimeMillis();
            trimWindow(LMB_TIMES, now); trimWindow(RMB_TIMES, now);
            if (mouseButtonDown(MB_LEFT) && lmbWasUp)  { LMB_TIMES.add(now); sessionLeftClicks++; }
            if (mouseButtonDown(MB_RIGHT) && rmbWasUp) { RMB_TIMES.add(now); sessionRightClicks++; }
            lmbWasUp = !mouseButtonDown(MB_LEFT);
            rmbWasUp = !mouseButtonDown(MB_RIGHT);
        } catch (Throwable t) { logOnce("ClickTrack", t); }
        if (modOn("CPS", true)) {
            try {
                y = drawLine(dc, font, "§4CPS §f" + LMB_TIMES.size() + " §4| §f" + RMB_TIMES.size(), y);
            } catch (Throwable t) { logOnce("CPS", t); }
        }
        if (modOn("ClickTotal", false)) {
            y = drawLine(dc, font, "§4Clicks §fL " + sessionLeftClicks + " §8| §fR " + sessionRightClicks, y);
        }
        if (modOn("Combo", true)) {
            try {
                long now = System.currentTimeMillis();
                if (now - comboLastMs > 3000 && comboCount != 0) comboCount = 0;
                if (mouseButtonDown(MB_LEFT) && lmbWasUpForCombo) {
                    // Only count as a hit if the cursor actually points at an entity.
                    Object hit = tryInvoke(mc, "method_64829", "getCrosshairTarget", "crosshairTarget");
                    if (hit == null) {
                        Field f = cachedField(mc.getClass(), "field_1765");
                        if (f != null) try { hit = f.get(mc); } catch (Throwable ignored) {}
                    }
                    // The hit's class is `class_3966` for entity hits in the
                    // intermediary mapping — `getSimpleName().contains("entity")`
                    // never matches, which broke Combo + Killstreak + HitsDealt
                    // + DPS all at once. Check the HitResult.Type enum instead
                    // (same approach used by the working Look module).
                    Object hitTypeObj = hit == null ? null
                        : tryInvoke(hit, "method_17783", "getType");
                    boolean isEntityHit = hitTypeObj != null
                        && hitTypeObj.toString().contains("ENTITY");
                    if (hit != null && isEntityHit) {
                        comboCount++;
                        comboLastMs = now;
                        sessionHitsDealt++;
                        killstreak++;
                        killstreakLastHitMs = now;
                        // Approximate DPS: estimate each landed hit's damage from
                        // the held item's tier (sword/axe + material). The proper
                        // attribute lookup (method_45325) needs a RegistryEntry
                        // arg we can't easily resolve, so we use the same id-based
                        // table used by the AttackDmg display module.
                        try {
                            Object p2 = playerField != null ? playerField.get(mc) : null;
                            double atk = 1.0;
                            if (p2 != null) {
                                Object stk = tryInvoke(p2, "method_6047", "getMainHandStack");
                                String id = getItemId(stk);
                                if (id.contains("sword")) {
                                    if      (id.contains("netherite")) atk = 8.0;
                                    else if (id.contains("diamond"))   atk = 7.0;
                                    else if (id.contains("iron"))      atk = 6.0;
                                    else if (id.contains("stone"))     atk = 5.0;
                                    else                                atk = 4.0;
                                } else if (id.contains("axe") && !id.contains("pickaxe")) {
                                    if      (id.contains("netherite")) atk = 10.0;
                                    else if (id.contains("diamond"))   atk = 9.0;
                                    else if (id.contains("iron"))      atk = 9.0;
                                    else if (id.contains("stone"))     atk = 9.0;
                                    else                                atk = 7.0;
                                } else if (id.contains("trident"))     atk = 9.0;
                                // Sharpness bonus from method_58657 (component-based)
                                Object comps = tryInvoke(stk, "method_58657");
                                if (comps != null) {
                                    Object entries = tryInvoke(comps, "method_57539", "entrySet");
                                    if (entries instanceof Iterable) {
                                        for (Object e : (Iterable<?>) entries) {
                                            try {
                                                Object lvlObj = tryInvoke(e, "getIntValue", "getValue");
                                                Object key    = tryInvoke(e, "getKey");
                                                Object optKey = tryInvoke(key, "method_40230", "getKey");
                                                if (!(optKey instanceof java.util.Optional)) continue;
                                                java.util.Optional<?> opt = (java.util.Optional<?>) optKey;
                                                if (!opt.isPresent()) continue;
                                                Object id2 = tryInvoke(opt.get(), "method_29177", "getValue");
                                                if (id2 != null && String.valueOf(id2).contains("sharpness")
                                                        && lvlObj instanceof Number) {
                                                    atk += 0.5 * ((Number) lvlObj).intValue() + 0.5;
                                                }
                                            } catch (Throwable ignored) {}
                                        }
                                    }
                                }
                            }
                            DPS_HISTORY.addLast(new long[]{ now, (long)(atk * 100) });
                        } catch (Throwable ignored) {
                            DPS_HISTORY.addLast(new long[]{ now, 100 });
                        }
                    }
                }
                lmbWasUpForCombo = !mouseButtonDown(MB_LEFT);
                String col = comboCount >= 5 ? "§c" : comboCount > 0 ? "§e" : "§7";
                y = drawLine(dc, font, "§4Combo " + col + comboCount + "§4 hits", y);
            } catch (Throwable t) { logOnce("Combo", t); }
        }
        if (player != null && modOn("Reach", true)) {
            try {
                Object hit = tryInvoke(mc, "method_64829", "getCrosshairTarget", "crosshairTarget");
                if (hit == null) {
                    Field f = cachedField(mc.getClass(), "field_1765");
                    if (f != null) try { hit = f.get(mc); } catch (Throwable ignored) {}
                }
                String reach = "—";
                boolean hasTarget = (hit != null);
                if (hit != null) {
                    Object hp = tryInvoke(hit, "method_17784", "getPos", "getLocation");
                    if (hp != null) {
                        double hx = firstNum(hp, "field_1352", "x").doubleValue();
                        double hy = firstNum(hp, "field_1351", "y").doubleValue();
                        double hz = firstNum(hp, "field_1350", "z").doubleValue();
                        double px = firstNum(player, "method_23317", "getX").doubleValue();
                        double py = firstNum(player, "method_23320", "getEyeY").doubleValue();
                        double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                        double d  = Math.sqrt((hx-px)*(hx-px)+(hy-py)*(hy-py)+(hz-pz)*(hz-pz));
                        reach = String.format("%.2f", d) + (cfgReachShowUnit ? " blocks" : "");
                    }
                }
                // Skip render if "Only when targeting" is on and we have no entity target.
                if (!cfgReachOnlyOnTarget || hasTarget) {
                    y = drawLine(dc, font, "§4Reach §f" + reach, y);
                }
            } catch (Throwable t) { logOnce("Reach", t); }
        }
        if (modOn("Ping", true)) {
            try {
                int ms = -1;
                Object net = tryInvoke(mc, "method_1562", "getNetworkHandler", "getConnection");
                if (net != null && player != null) {
                    Object uuid = tryInvoke(player, "method_5667", "getUuid", "getUUID");
                    Object listEntry = null;
                    if (uuid != null) {
                        // Specific overload: ClientPlayNetworkHandler.getPlayerListEntry(UUID)
                        // → method_2871. Resolved + cached on first call so subsequent
                        // frames skip the full method-table walk.
                        try {
                            if (pingListEntryMethod == null) {
                                for (Method m : net.getClass().getMethods()) {
                                    String n = m.getName();
                                    if (m.getParameterCount() != 1) continue;
                                    if (m.getParameterTypes()[0] != java.util.UUID.class) continue;
                                    if (m.getReturnType() == void.class) continue;
                                    if (n.equals("method_2871") || n.equals("getPlayerListEntry")
                                        || n.equals("getPlayerInfo")) {
                                        pingListEntryMethod = m;
                                        break;
                                    }
                                }
                            }
                            if (pingListEntryMethod != null) {
                                listEntry = pingListEntryMethod.invoke(net, uuid);
                            }
                        } catch (Throwable ignored) {}
                    }
                    // Fallback: scan getPlayerList() and match by UUID
                    if (listEntry == null && uuid != null) {
                        Object list = tryInvoke(net, "method_2880", "getPlayerList",
                                                "getOnlinePlayers");
                        if (list instanceof java.util.Collection) {
                            for (Object e : (java.util.Collection<?>) list) {
                                Object profile = tryInvoke(e, "method_2966", "getProfile");
                                Object pu = profile == null ? null
                                          : tryInvoke(profile, "getId");
                                if (uuid.equals(pu)) { listEntry = e; break; }
                            }
                        }
                    }
                    if (listEntry == null && !pingDiagnosticLogged) {
                        pingDiagnosticLogged = true;
                        // Diagnostic — list every getter on the network handler that
                        // takes a UUID, so we can see why the lookup failed.
                        StringBuilder methods = new StringBuilder();
                        for (Method m : net.getClass().getMethods()) {
                            if (m.getParameterCount() != 1) continue;
                            if (m.getParameterTypes()[0] != java.util.UUID.class) continue;
                            methods.append(' ').append(m.getName())
                                   .append("→").append(m.getReturnType().getSimpleName());
                        }
                        System.out.println("[ShadowHud][Ping] no list entry for "
                            + uuid + " — UUID-getters on " + net.getClass().getName() + ":" + methods);
                    }
                    if (listEntry != null) {
                        Object lat = tryInvoke(listEntry, "method_2959", "getLatency",
                                               "getLatencyMs");
                        if (lat instanceof Number) ms = ((Number) lat).intValue();
                        // Field fallback — try common names then any int field.
                        if (ms <= 0) {
                            String[] tryNames = {"field_3739", "latency", "ping", "field_53033"};
                            Class<?> cc = listEntry.getClass();
                            while (cc != null && ms <= 0) {
                                for (String n : tryNames) {
                                    try {
                                        Field f = cc.getDeclaredField(n);
                                        f.setAccessible(true);
                                        if (f.getType() == int.class) {
                                            int v = f.getInt(listEntry);
                                            if (v > 0) { ms = v; break; }
                                        }
                                    } catch (Throwable ignored) {}
                                }
                                cc = cc.getSuperclass();
                            }
                        }
                        if (!pingDiagnosticLogged) {
                            pingDiagnosticLogged = true;
                            StringBuilder fb = new StringBuilder();
                            Class<?> cc = listEntry.getClass();
                            while (cc != null) {
                                for (Field f : cc.getDeclaredFields()) {
                                    if (f.getType() == int.class
                                        && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                                        try {
                                            f.setAccessible(true);
                                            fb.append(' ').append(f.getName()).append('=')
                                              .append(f.getInt(listEntry));
                                        } catch (Throwable t) {
                                            fb.append(' ').append(f.getName()).append("=<denied>");
                                        }
                                    }
                                }
                                cc = cc.getSuperclass();
                            }
                            System.out.println("[ShadowHud][Ping] class=" + listEntry.getClass().getName()
                                + " getLatency=" + lat + " intFields:" + fb);
                        }
                    }
                }
                // Display-only fake ping. Doesn't touch the actual network —
                // just rewrites what the Ping HUD line shows.
                // Read FakePing without mutating currentModuleRendering — we're
                // INSIDE the Ping block and don't want subsequent drawLine
                // calls to be attributed to FakePing.
                if (MODULES.getOrDefault("FakePing", false)) ms = 2000;
                String col = ms < 0 ? "§7" : ms < 70 ? "§a" : ms < 150 ? "§e" : "§c";
                if (cfgPingStyle == 1 && ms >= 0) {
                    // Bar style — 10 cells, 30 ms each (full = 300+ ms)
                    int filled = Math.max(0, Math.min(10, ms / 30));
                    StringBuilder b = new StringBuilder("§4Ping ").append(col);
                    // Inverse-scale so low ping = full bar
                    int green = Math.max(0, 10 - filled);
                    for (int i = 0; i < green; i++) b.append("█");
                    b.append("§8");
                    for (int i = green; i < 10; i++) b.append("█");
                    b.append(" ").append(col).append(ms).append(" ms");
                    y = drawLine(dc, font, b.toString(), y);
                } else {
                    y = drawLine(dc, font, "§4Ping " + col
                        + (ms < 0 ? "—" : (ms == 0 ? "0 ms §8(local)" : ms + " ms")), y);
                }
            } catch (Throwable t) { logOnce("Ping", t); }
        }
        if (player != null && modOn("Speed", true)) {
            try {
                long now = System.currentTimeMillis();
                double x = firstNum(player, "method_23317", "getX").doubleValue();
                double z = firstNum(player, "method_23321", "getZ").doubleValue();
                if (prevSpeedMs != 0) {
                    double dx = x - prevSpeedX, dz = z - prevSpeedZ;
                    double dt = (now - prevSpeedMs) / 1000.0;
                    if (dt > 0.0) {
                        // Light low-pass so the readout doesn't twitch every frame
                        cachedSpeed = cachedSpeed * 0.7 + (Math.sqrt(dx*dx + dz*dz) / dt) * 0.3;
                    }
                }
                prevSpeedX = x; prevSpeedZ = z; prevSpeedMs = now;
                y = drawLine(dc, font, String.format("§4Speed §f%.2f §4b/s", cachedSpeed), y);
            } catch (Throwable t) { logOnce("Speed", t); }
        }
        if (modOn("Memory", true)) {
            try {
                Runtime r = Runtime.getRuntime();
                long max  = r.maxMemory()  / (1024 * 1024);
                long tot  = r.totalMemory() / (1024 * 1024);
                long used = tot - r.freeMemory() / (1024 * 1024);
                long pct  = max > 0 ? (used * 100 / max) : 0;
                String col = pct >= 80 ? "§c" : pct >= 50 ? "§e" : "§a";
                String txt;
                if (cfgMemFormat == 2 || (cfgMemFormat == 0 && max >= 4096)) {
                    // GB
                    double usedG = used / 1024.0;
                    double maxG  = max  / 1024.0;
                    txt = String.format("§4Mem %s%.2f§4/§f%.2f §4GB §8(%d%%)",
                                         col, usedG, maxG, pct);
                } else {
                    // MB (default for low-memory builds)
                    txt = "§4Mem " + col + used + "§4/§f" + max + " §4MB §8(" + pct + "%)";
                }
                y = drawLine(dc, font, txt, y);
            } catch (Throwable t) { logOnce("Memory", t); }
        }
        if (modOn("Time", true)) {
            try {
                java.time.LocalTime now = java.time.LocalTime.now();
                String fmt;
                if (cfgTime24h) {
                    fmt = cfgTimeSeconds ? "HH:mm:ss" : "HH:mm";
                } else {
                    fmt = cfgTimeSeconds ? "h:mm:ss a" : "h:mm a";
                }
                String t = now.format(java.time.format.DateTimeFormatter.ofPattern(fmt));
                y = drawLine(dc, font, "§4Time §f" + t, y);
            } catch (Throwable t) { logOnce("Time", t); }
        }
        if (modOn("Server", true)) {
            try {
                String addr;
                Object srv = tryInvoke(mc, "method_1558", "getCurrentServerEntry");
                if (srv != null) {
                    String a = readStringField(srv, "field_3761", "address");
                    if (a == null || a.isEmpty()) a = findAnyAddressLikeString(srv);
                    if (!serverDiagnosticLogged) {
                        serverDiagnosticLogged = true;
                        StringBuilder fb = new StringBuilder();
                        Class<?> cc = srv.getClass();
                        while (cc != null) {
                            for (Field f : cc.getDeclaredFields()) {
                                if (f.getType() == String.class
                                    && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                                    try {
                                        f.setAccessible(true);
                                        Object v = f.get(srv);
                                        fb.append(' ').append(f.getName()).append('=').append(v);
                                    } catch (Throwable t) {
                                        fb.append(' ').append(f.getName()).append("=<denied>");
                                    }
                                }
                            }
                            cc = cc.getSuperclass();
                        }
                        System.out.println("[ShadowHud][Server] class=" + srv.getClass().getName()
                            + " stringFields:" + fb);
                    }
                    addr = (a == null || a.isEmpty()) ? "—" : a;
                } else {
                    Object integ = tryInvoke(mc, "method_1576", "getServer");
                    addr = integ != null ? "Singleplayer" : "Main menu";
                }
                // AutoPreset + ServerRules hooks: trigger BEFORE the Streamer
                // redaction so we see the real server name. Both are idempotent
                // and fire only once per server change.
                maybeApplyServerPreset(addr);
                maybeApplyServerRules(addr);
                // Same fix — don't pollute currentModuleRendering inside Server.
                if (MODULES.getOrDefault("Streamer", false)) addr = "§c[redacted — Streamer mode]";
                y = drawLine(dc, font, "§4Server §f" + addr, y);
            } catch (Throwable t) { logOnce("Server", t); }
        }
        if (player != null && modOn("XYZ", true)) {
            try {
                double x = firstNum(player, "method_23317", "getX").doubleValue();
                double yy = firstNum(player, "method_23318", "getY").doubleValue();
                double z = firstNum(player, "method_23321", "getZ").doubleValue();
                int dec = Math.max(0, Math.min(3, cfgXyzDecimals));
                String numFmt = "%." + dec + "f";
                String sep = XYZ_SEP[Math.max(0, Math.min(XYZ_SEP.length - 1, cfgXyzSep))];
                String prefix = cfgXyzCompact ? "§f" : "§4XYZ §f";
                String text = prefix + String.format(numFmt, x) + sep
                            + String.format(numFmt, yy) + sep
                            + String.format(numFmt, z);
                y = drawLine(dc, font, text, y);
            } catch (Throwable t) { logOnce("XYZ", t); }
        }
        if (player != null && modOn("Facing", true)) {
            try {
                float yaw = firstNum(player, "method_36454", "getYRot", "getYaw").floatValue();
                y = drawLine(dc, font, "§4Facing §f" + facing(yaw), y);
            } catch (Throwable t) { logOnce("Facing", t); }
        }
        if (player != null && modOn("Compass", true)) {
            try {
                float yaw = firstNum(player, "method_36454", "getYRot", "getYaw").floatValue();
                float norm = ((yaw % 360f) + 360f) % 360f;
                String text;
                if (cfgCompassMode == 2) {
                    // Degrees only
                    text = String.format("§4Compass §f%.0f°", norm);
                } else if (cfgCompassMode == 1) {
                    // 16-direction
                    String[] dirs16 = {"S","SSW","SW","WSW","W","WNW","NW","NNW",
                                       "N","NNE","NE","ENE","E","ESE","SE","SSE"};
                    int idx = Math.round(norm / 22.5f) & 15;
                    text = "§4Compass §f" + dirs16[idx];
                    if (cfgCompassDegs) text += String.format(" §8(%.0f°)", norm);
                } else {
                    // 8-direction (default)
                    String[] dirs = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
                    int idx = Math.round(norm / 45f) & 7;
                    text = "§4Compass §f" + dirs[idx];
                    if (cfgCompassDegs) text += String.format(" §8(%.0f°)", norm);
                }
                y = drawLine(dc, font, text, y);
            } catch (Throwable t) { logOnce("Compass", t); }
        }
        if (modOn("Day", true)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    Number tod = firstNum(world, "method_8532", "getTimeOfDay", "getDayTime");
                    long days = tod.longValue() / 24000L;
                    long inDay = tod.longValue() % 24000L;
                    int gameHour = (int) (((inDay / 1000.0) + 6) % 24);
                    int gameMin  = (int) (((inDay % 1000) * 60 / 1000));
                    String prefix;
                    switch (cfgDayFormat) {
                        case 1: prefix = "§4D §f"; break;
                        case 2: prefix = "§f"; break;
                        default: prefix = "§4Day §f";
                    }
                    y = drawLine(dc, font, String.format("%s%d §8(§7%02d:%02d§8)",
                        prefix, days, gameHour, gameMin), y);
                }
            } catch (Throwable t) { logOnce("Day", t); }
        }
        if (player != null && modOn("Light", true)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                Object pos = firstMethodInvoke(player, null,
                                               "method_24515", "blockPosition", "getBlockPos");
                if (world != null && pos != null) {
                    // world.getLightLevel(BlockPos) → max of sky+block.
                    // Resolved once + cached so per-frame cost is just an
                    // invoke (was a full getMethods() scan every frame for
                    // every Light-enabled user).
                    int max = -1;
                    // 1.21.11: only method_22335(BlockPos, int ambient) is
                    // sane to call without a LightType enum arg. There is no
                    // 1-arg getLight in the new mappings — old probe matched
                    // method_8314 (2-arg with LightType) and called it with
                    // a null first arg, which NPE'd silently inside MC.
                    if (lightLvl1Method == null && !lightLvlResolved) {
                        lightLvlResolved = true;
                        for (Method m : world.getClass().getMethods()) {
                            if (!"method_22335".equals(m.getName())) continue;
                            if (m.getParameterCount() != 2) continue;
                            // (BlockPos, int)
                            Class<?>[] pt = m.getParameterTypes();
                            if (pt[1] != int.class) continue;
                            lightLvl1Method = m;
                            break;
                        }
                    }
                    if (lightLvl1Method != null) {
                        try { max = (int) lightLvl1Method.invoke(world, pos, 0); }
                        catch (Throwable ignored) {}
                    }
                    String col = max >= 8 ? "§a" : max >= 4 ? "§e" : "§c";
                    y = drawLine(dc, font, "§4Light " + col + (max >= 0 ? max : "—"), y);
                }
            } catch (Throwable t) { logOnce("Light", t); }
        }
        if (modOn("Weather", true)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    Object rainObj    = tryInvoke(world, "method_8419", "isRaining");
                    Object thunderObj = tryInvoke(world, "method_8546", "isThundering");
                    boolean raining    = Boolean.TRUE.equals(rainObj);
                    boolean thundering = Boolean.TRUE.equals(thunderObj);
                    String s;
                    if (thundering)   s = "§4Weather §c⚡ Thunder";
                    else if (raining) s = "§4Weather §9⛆ Rain";
                    else              s = "§4Weather §e☀ Clear";
                    y = drawLine(dc, font, s, y);
                }
            } catch (Throwable t) { logOnce("Weather", t); }
        }
        if (player != null && modOn("Held", true)) {
            try {
                Object stack = tryInvoke(player, "method_6047", "getMainHandStack",
                                         "getMainHandItem");
                if (stack != null) {
                    Object isEmpty = tryInvoke(stack, "method_7960", "isEmpty");
                    if (!Boolean.TRUE.equals(isEmpty)) {
                        Object name = tryInvoke(stack, "method_7964", "getName",
                                                "getDisplayName", "getHoverName");
                        String n = name != null ? compToString(name) : "?";
                        if (n.length() > 24) n = n.substring(0, 21) + "…";
                        StringBuilder sb = new StringBuilder("§4Held §f").append(n);
                        Object count = tryInvoke(stack, "method_7947", "getCount");
                        if (count instanceof Number && ((Number) count).intValue() > 1) {
                            sb.append(" §8x§7").append(((Number) count).intValue());
                        }
                        // Durability as M/N if the item has any
                        Object dmg = tryInvoke(stack, "method_7919", "getDamageValue", "getDamage");
                        Object max = tryInvoke(stack, "method_7936", "getMaxDamage");
                        if (dmg instanceof Number && max instanceof Number
                            && ((Number) max).intValue() > 0) {
                            int left = ((Number) max).intValue() - ((Number) dmg).intValue();
                            int total = ((Number) max).intValue();
                            float ratio = total > 0 ? (left / (float) total) : 1f;
                            String c = ratio < 0.15f ? "§c" : ratio < 0.4f ? "§e" : "§a";
                            sb.append("  ").append(c).append(left).append("§8/§7").append(total);
                        }
                        y = drawLine(dc, font, sb.toString(), y);
                    }
                }
            } catch (Throwable t) { logOnce("Held", t); }
        }
        if (player != null && modOn("HotbarTotal", false)) {
            try {
                Object stack = tryInvoke(player, "method_6047", "getMainHandStack");
                if (stack != null) {
                    Object empty = tryInvoke(stack, "method_7960", "isEmpty");
                    if (!Boolean.TRUE.equals(empty)) {
                        String heldId = getItemId(stack);
                        // Walk the entire inventory list and sum every stack
                        // whose registry id matches the held one. method_67533
                        // returns the main 36-slot list (hotbar + main inv).
                        Object inv = tryInvoke(player, "method_31548", "getInventory");
                        Object listObj = inv != null ? tryInvoke(inv, "method_67533") : null;
                        int total = 0;
                        if (listObj instanceof Iterable) {
                            for (Object s : (Iterable<?>) listObj) {
                                if (s == null) continue;
                                Object sEmpty = tryInvoke(s, "method_7960", "isEmpty");
                                if (Boolean.TRUE.equals(sEmpty)) continue;
                                if (!getItemId(s).equals(heldId)) continue;
                                Object cnt = tryInvoke(s, "method_7947", "getCount");
                                if (cnt instanceof Number) total += ((Number) cnt).intValue();
                            }
                        }
                        // Also include offhand for completeness.
                        Object off = tryInvoke(player, "method_6079", "getOffHandStack");
                        if (off != null && !Boolean.TRUE.equals(tryInvoke(off, "method_7960"))
                            && getItemId(off).equals(heldId)) {
                            Object cnt = tryInvoke(off, "method_7947", "getCount");
                            if (cnt instanceof Number) total += ((Number) cnt).intValue();
                        }
                        if (total > 0) {
                            // Strip "minecraft:" for compact display
                            int colon = heldId.indexOf(':');
                            String shortId = (colon >= 0) ? heldId.substring(colon + 1) : heldId;
                            y = drawLine(dc, font,
                                "§4Total §f" + total + " §8" + shortId, y);
                        }
                    }
                }
            } catch (Throwable t) { logOnce("HotbarTotal", t); }
        }
        // DamageIndicator render block removed (PvP cheat).
        if (modOn("Look", true)) {
            try {
                Object hit = tryInvoke(mc, "method_64829", "getCrosshairTarget", "crosshairTarget");
                if (hit == null) {
                    Field f = cachedField(mc.getClass(), "field_1765");
                    if (f != null) try { hit = f.get(mc); } catch (Throwable ignored) {}
                }
                if (hit != null) {
                    String label = null;
                    Object typeObj = tryInvoke(hit, "method_17783", "getType");
                    String typeName = typeObj == null ? "" : typeObj.toString();
                    if (typeName.contains("ENTITY")) {
                        Object entity = tryInvoke(hit, "method_17782", "getEntity");
                        if (entity != null) {
                            Object n = tryInvoke(entity, "method_5477", "getName", "getDisplayName");
                            label = "§dE §f" + (n == null ? entity.getClass().getSimpleName() : compToString(n));
                        }
                    } else if (typeName.contains("BLOCK")) {
                        Object world = worldField != null ? worldField.get(mc) : null;
                        Object pos = tryInvoke(hit, "method_17777", "getBlockPos");
                        if (world != null && pos != null) {
                            for (Method m : world.getClass().getMethods()) {
                                if (!"method_8320".equals(m.getName())) continue;
                                if (m.getParameterCount() != 1) continue;
                                Object state = m.invoke(world, pos);
                                if (state == null) continue;
                                Object block = tryInvoke(state, "method_26204", "getBlock");
                                Object n = tryInvoke(block, "method_9518", "getName");
                                label = "§bB §f" + (n == null ? block.getClass().getSimpleName() : compToString(n));
                                break;
                            }
                        }
                    }
                    if (label != null) y = drawLine(dc, font, "§4Look " + label, y);
                }
            } catch (Throwable t) { logOnce("Look", t); }
        }
        if (player != null && modOn("HP", true)) {
            try {
                float hp  = firstNum(player, "method_6032", "getHealth").floatValue();
                float max = firstNum(player, "method_6063", "getMaxHealth").floatValue();
                float abs = firstNum(player, "method_6067", "getAbsorptionAmount").floatValue();
                int   arm = firstNum(player, "method_6096", "getArmor", "getArmorValue").intValue();
                StringBuilder s = new StringBuilder();
                if (!cfgHpCompact) s.append("§4HP ");
                s.append(hpColor(hp, max)).append(trimFloat(hp))
                 .append("§4/§f").append(trimFloat(max));
                if (cfgHpShowAbs && abs > 0.01f) s.append(" §6+").append(trimFloat(abs));
                if (cfgHpShowArmor && arm > 0) {
                    s.append(cfgHpCompact ? "  §f" : "  §4Armor §f").append(arm);
                }
                y = drawLine(dc, font, s.toString(), y);
            } catch (Throwable t) { logOnce("HP", t); }
        }
        if (player != null && modOn("Damage", true)) {
            try {
                float hp = firstNum(player, "method_6032", "getHealth").floatValue();
                if (lastSeenHp >= 0 && hp < lastSeenHp - 0.01f) {
                    lastDamageAmount = lastSeenHp - hp;
                    lastDamageMs     = System.currentTimeMillis();
                    sessionHitsTaken++;
                    killstreak = 0;       // streak interrupted
                }
                lastSeenHp = hp;
                String line;
                if (lastDamageMs == 0) {
                    line = "§4Last hit §7—";
                } else {
                    long ago = (System.currentTimeMillis() - lastDamageMs) / 100;  // tenths of seconds
                    String col = ago < 30 ? "§c" : ago < 100 ? "§e" : "§7";
                    line = String.format("§4Last hit §f%.1f§4❤  %s%.1fs ago",
                                         lastDamageAmount, col, ago / 10.0);
                }
                y = drawLine(dc, font, line, y);
            } catch (Throwable t) { logOnce("Damage", t); }
        }
        if (player != null && modOn("HitDir", true)) {
            try {
                // 1.21.11 removed LivingEntity.attackedAtYaw entirely. Compute
                // direction from getRecentDamageSource().getPosition() instead,
                // gated by hurtTime so we only show it briefly after a hit.
                int hurtTime = 0;
                Class<?> c = player.getClass();
                while (c != null && hurtTime == 0) {
                    try {
                        Field hf = c.getDeclaredField("field_6235");
                        hf.setAccessible(true);
                        hurtTime = hf.getInt(player);
                        break;
                    } catch (NoSuchFieldException ignored) {}
                    c = c.getSuperclass();
                }
                if (hurtTime > 0) {
                    Object src = tryInvoke(player, "method_6081", "getRecentDamageSource");
                    Object pos = src == null ? null
                                              : tryInvoke(src, "method_5510", "getPosition");
                    if (pos != null) {
                        double sx = firstNum(pos, "field_1352", "x").doubleValue();
                        double sz = firstNum(pos, "field_1350", "z").doubleValue();
                        double px = firstNum(player, "method_23317", "getX").doubleValue();
                        double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                        double bearing = Math.toDegrees(Math.atan2(sx - px, sz - pz));
                        if (bearing < 0) bearing += 360;
                        String[] dirs = {"S","SW","W","NW","N","NE","E","SE"};
                        int idx = (int) Math.round(bearing / 45.0) & 7;
                        y = drawLine(dc, font, String.format(
                            "§4Hit from §c%s §8(§7%.0f°§8)", dirs[idx], bearing), y);
                    }
                }
            } catch (Throwable t) { logOnce("HitDir", t); }
        }
        if (player != null && modOn("Attacker", true)) {
            try {
                // LivingEntity.getAttacker() / getLastAttacker() — entity that
                // most recently dealt damage to us. Persists for ~5 s.
                Object attacker = tryInvoke(player, "method_6065", "getAttacker",
                                            "getLastHurtByMob", "getLastAttacker");
                if (attacker == null) {
                    attacker = tryInvoke(player, "method_6052",
                                         "getAttacking", "getLastAttacker");
                }
                if (attacker != null) {
                    Object n = tryInvoke(attacker, "method_5477", "getName", "getDisplayName");
                    String name = n == null ? attacker.getClass().getSimpleName() : compToString(n);
                    if (name.length() > 22) name = name.substring(0, 19) + "…";
                    y = drawLine(dc, font, "§4Attacked by §c" + name, y);
                }
            } catch (Throwable t) { logOnce("Attacker", t); }
        }
        if (player != null && modOn("BowCharge", true)) {
            try {
                Object usingItem = tryInvoke(player, "method_6115", "isUsingItem");
                if (Boolean.TRUE.equals(usingItem)) {
                    Object stack = tryInvoke(player, "method_6030",
                                             "getActiveItem", "getUseItem");
                    Object useAct = stack == null ? null
                        : tryInvoke(stack, "method_7976", "getUseAction", "getUseAnimation");
                    String act = useAct == null ? "" : useAct.toString();
                    if (act.contains("BOW") || act.contains("CROSSBOW")) {
                        // ItemUseTimeLeft on player (no-arg)
                        int left = safeInt(player, "method_6014", "getItemUseTimeLeft");
                        // 1.21.11: ItemStack.getMaxUseTime requires a LivingEntity arg.
                        // safeInt is no-arg-only so we resolve the 1-arg overload manually.
                        int max  = -1;
                        for (String n : new String[]{"method_7935", "getMaxUseTime"}) {
                            try {
                                java.lang.reflect.Method m = null;
                                for (java.lang.reflect.Method cand : stack.getClass().getMethods()) {
                                    if (!cand.getName().equals(n)) continue;
                                    if (cand.getParameterCount() != 1) continue;
                                    if (cand.getReturnType() != int.class) continue;
                                    m = cand; break;
                                }
                                if (m != null) {
                                    Object r = m.invoke(stack, player);
                                    if (r instanceof Number) { max = ((Number) r).intValue(); break; }
                                }
                            } catch (Throwable ignored) {}
                        }
                        // Fallback: bow draw time is always 72 ticks (3.6s) in vanilla
                        if (max <= 0) max = 72;
                        if (max > 0) {
                            float used = (max - left) / 20.0f;     // seconds drawn
                            // 1.0 s draw = full charge in vanilla bow
                            float pct = Math.min(1.0f, used / 1.0f);
                            int filled = Math.min(10, Math.round(pct * 10));
                            StringBuilder bar = new StringBuilder();
                            for (int i = 0; i < 10; i++) bar.append(i < filled ? "█" : "▒");
                            String col = pct >= 1.0f ? "§a" : pct >= 0.6f ? "§e" : "§c";
                            y = drawLine(dc, font, "§4Bow " + col + bar
                                + " §f" + Math.round(pct * 100) + "§4%"
                                + (pct >= 1.0f ? " §6CRIT" : ""), y);
                        }
                    }
                }
            } catch (Throwable t) { logOnce("BowCharge", t); }
        }
        // CritReady render block removed (PvP cheat).
        if (player != null && modOn("DeathPos", true)) {
            try {
                if (deathPosSet) {
                    String wid = currentMapKey();
                    if (wid.equals(deathWorld)) {
                        double px = firstNum(player, "method_23317", "getX").doubleValue();
                        double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                        double dx = deathX - px, dz = deathZ - pz;
                        double dist = Math.sqrt(dx*dx + dz*dz);
                        String pretty = dist >= 1000
                            ? String.format("%.2f§4 km", dist / 1000.0)
                            : String.format("%.0f§4 m", dist);
                        y = drawLine(dc, font, String.format(
                            "§4Death @ §f%d§4, §f%d§4, §f%d §8(§c%s§8)",
                            (int)deathX, (int)deathY, (int)deathZ, pretty), y);
                    } else {
                        y = drawLine(dc, font, "§4Death §8(other dim)", y);
                    }
                }
            } catch (Throwable t) { logOnce("DeathPos", t); }
        }
        if (player != null && modOn("NearMob", true)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    Object entIter = tryInvoke(world, "method_18112",
                                               "getEntities", "entitiesForRendering");
                    if (entIter instanceof Iterable) {
                        double px = firstNum(player, "method_23317", "getX").doubleValue();
                        double py = firstNum(player, "method_23318", "getY").doubleValue();
                        double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                        Object best = null; double bestD2 = Double.MAX_VALUE;
                        for (Object e : (Iterable<?>) entIter) {
                            if (e == player) continue;
                            // Hostile = subclass of class_1588 (Monster)
                            Class<?> c = e.getClass();
                            boolean hostile = false;
                            while (c != null) {
                                String cn = c.getName();
                                if (cn.endsWith("class_1588") || cn.endsWith("Monster")
                                    || cn.endsWith("HostileEntity")) { hostile = true; break; }
                                c = c.getSuperclass();
                            }
                            if (!hostile) continue;
                            double ex = firstNum(e, "method_23317", "getX").doubleValue();
                            double ey = firstNum(e, "method_23318", "getY").doubleValue();
                            double ez = firstNum(e, "method_23321", "getZ").doubleValue();
                            double d2 = (ex-px)*(ex-px)+(ey-py)*(ey-py)+(ez-pz)*(ez-pz);
                            if (d2 < bestD2) { bestD2 = d2; best = e; }
                        }
                        if (best != null) {
                            Object n = tryInvoke(best, "method_5477", "getName",
                                                 "getDisplayName");
                            String name = n == null ? best.getClass().getSimpleName()
                                                    : compToString(n);
                            if (name.length() > 16) name = name.substring(0, 14) + "…";
                            double d = Math.sqrt(bestD2);
                            String col = d < 6 ? "§c" : d < 16 ? "§e" : "§a";
                            y = drawLine(dc, font, String.format(
                                "§4Mob §c%s §8• %s%.1f§4 m", name, col, d), y);
                        }
                    }
                }
            } catch (Throwable t) { logOnce("NearMob", t); }
        }
        if (player != null && modOn("SleepInfo", true)) {
            try {
                Object inBed = tryInvoke(player, "method_6113", "isSleeping");
                if (Boolean.TRUE.equals(inBed)) {
                    Object world = worldField != null ? worldField.get(mc) : null;
                    if (world != null) {
                        Object pl = tryInvoke(world, "method_18456", "getPlayers", "players");
                        if (pl instanceof java.util.Collection) {
                            int total = 0, sleeping = 0;
                            for (Object p : (java.util.Collection<?>) pl) {
                                total++;
                                Object s = tryInvoke(p, "method_6113", "isSleeping");
                                if (Boolean.TRUE.equals(s)) sleeping++;
                            }
                            String col = sleeping == total ? "§a" : "§e";
                            y = drawLine(dc, font, "§4Sleep " + col + sleeping
                                + "§4/§f" + total, y);
                        }
                    }
                }
            } catch (Throwable t) { logOnce("SleepInfo", t); }
        }
        if (player != null && modOn("Distance", true)) {
            try {
                String wid = currentMapKey();
                if (!wid.equals(walkedWorldId)) {
                    walkedMeters = 0; prevDistX = Double.NaN; walkedWorldId = wid;
                }
                double x = firstNum(player, "method_23317", "getX").doubleValue();
                double z = firstNum(player, "method_23321", "getZ").doubleValue();
                if (!Double.isNaN(prevDistX)) {
                    double dx = x - prevDistX, dz = z - prevDistZ;
                    double step = Math.sqrt(dx*dx + dz*dz);
                    // Filter teleports / world-load jumps (>20 m/frame)
                    if (step < 20.0) walkedMeters += step;
                }
                prevDistX = x; prevDistZ = z;
                String pretty = walkedMeters >= 1000
                    ? String.format("%.2f§4 km", walkedMeters / 1000.0)
                    : String.format("%.0f§4 m",  walkedMeters);
                y = drawLine(dc, font, "§4Walked §f" + pretty, y);
            } catch (Throwable t) { logOnce("Distance", t); }
        }
        if (modOn("AFK", true)) {
            try {
                if (lastInputMs == 0) lastInputMs = System.currentTimeMillis();
                long idle = (System.currentTimeMillis() - lastInputMs) / 1000;
                if (idle < 30) {
                    y = drawLine(dc, font, "§4Status §aActive", y);
                } else {
                    long m = idle / 60, s = idle % 60;
                    String tag = idle >= 300 ? "§c§lAFK" : "§eIdle";
                    String time = m > 0 ? m + "m " + s + "s" : s + "s";
                    y = drawLine(dc, font, "§4Status " + tag + " §8(" + time + ")", y);
                }
            } catch (Throwable t) { logOnce("AFK", t); }
        }
        if (modOn("FpsChart", true)) {
            try {
                int fps = -1;
                if (getFpsMethod != null) fps = (int) getFpsMethod.invoke(mc);
                long now = System.currentTimeMillis();
                if (fps >= 0) FPS_HISTORY.addLast(new int[]{(int)(now/1000), fps});
                while (!FPS_HISTORY.isEmpty()
                       && FPS_HISTORY.peekFirst()[0] < (now/1000) - 30) {
                    FPS_HISTORY.pollFirst();
                }
                if (!FPS_HISTORY.isEmpty()) {
                    int min = Integer.MAX_VALUE, max = 0, sum = 0, n = 0;
                    for (int[] e : FPS_HISTORY) {
                        if (e[1] < min) min = e[1];
                        if (e[1] > max) max = e[1];
                        sum += e[1]; n++;
                    }
                    int avg = n > 0 ? sum / n : 0;
                    String col = min < 30 ? "§c" : min < 60 ? "§e" : "§a";
                    y = drawLine(dc, font, String.format(
                        "§4FPS 30s §7min %s%d §8/ §7avg §f%d §8/ §7max §a%d",
                        col, min, avg, max), y);
                }
            } catch (Throwable t) { logOnce("FpsChart", t); }
        }
        if (player != null && modOn("Cooldown", true)) {
            try {
                // class_1657.method_7261(0.5f) — getAttackCooldownProgress(float)
                Float cd = null;
                for (Method m : player.getClass().getMethods()) {
                    if (!"method_7261".equals(m.getName()) && !"getAttackCooldownProgress".equals(m.getName())
                        && !"getAttackStrengthScale".equals(m.getName())) continue;
                    if (m.getParameterCount() != 1) continue;
                    if (m.getParameterTypes()[0] != float.class) continue;
                    try { cd = (Float) m.invoke(player, 0.5f); break; } catch (Throwable ignored) {}
                }
                if (cd != null) {
                    int pct = Math.round(cd * 100f);
                    String col = pct >= 95 ? "§a" : pct >= 60 ? "§e" : "§c";
                    // ASCII bar — 10 cells
                    int filled = Math.min(10, Math.max(0, pct / 10));
                    StringBuilder bar = new StringBuilder();
                    for (int i = 0; i < 10; i++) bar.append(i < filled ? "█" : "▒");
                    y = drawLine(dc, font, "§4Cooldown " + col + bar + " §f" + pct + "§4%", y);
                }
            } catch (Throwable t) { logOnce("Cooldown", t); }
        }
        if (player != null && modOn("Nearby", true)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    Object entIter = tryInvoke(world, "method_18112", "getEntities", "entitiesForRendering");
                    if (entIter instanceof Iterable) {
                        double px = firstNum(player, "method_23317", "getX").doubleValue();
                        double py = firstNum(player, "method_23318", "getY").doubleValue();
                        double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                        // Per-category radii (squared) — players need long range
                        // for PvP situational awareness; mobs and items only
                        // matter close-up. 128 b = 8 chunks (typical view dist).
                        final double PLAYER_R2 = 128.0 * 128.0;   // 16384
                        final double MOB_R2    =  32.0 *  32.0;   //  1024
                        final double ITEM_R2   =  16.0 *  16.0;   //   256
                        int players = 0, hostile = 0, items = 0;
                        double nearestPlayerD2 = Double.MAX_VALUE;
                        for (Object e : (Iterable<?>) entIter) {
                            if (e == player) continue;
                            double ex = firstNum(e, "method_23317", "getX").doubleValue();
                            double ey = firstNum(e, "method_23318", "getY").doubleValue();
                            double ez = firstNum(e, "method_23321", "getZ").doubleValue();
                            double d2 = (ex-px)*(ex-px)+(ey-py)*(ey-py)+(ez-pz)*(ez-pz);
                            // Classify by walking the class hierarchy
                            Class<?> c = e.getClass();
                            boolean isPlayer = false, isHostile = false, isItem = false;
                            while (c != null) {
                                String cn = c.getName();
                                if (cn.endsWith("class_1657") || cn.endsWith("Player"))      isPlayer = true;
                                if (cn.endsWith("class_1588") || cn.endsWith("Monster"))     isHostile = true;
                                if (cn.endsWith("class_1542") || cn.endsWith("ItemEntity"))  isItem = true;
                                c = c.getSuperclass();
                            }
                            if (isPlayer && d2 <= PLAYER_R2) {
                                players++;
                                if (d2 < nearestPlayerD2) nearestPlayerD2 = d2;
                            } else if (isHostile && d2 <= MOB_R2) hostile++;
                            else if (isItem    && d2 <= ITEM_R2) items++;
                        }
                        StringBuilder sb = new StringBuilder("§4Nearby §f")
                            .append(players).append("§4p §f")
                            .append(hostile).append("§4m §f")
                            .append(items).append("§4i");
                        if (players > 0 && nearestPlayerD2 != Double.MAX_VALUE) {
                            sb.append(" §8(close §f")
                              .append(String.format("%.0f", Math.sqrt(nearestPlayerD2)))
                              .append("§8b)");
                        }
                        y = drawLine(dc, font, sb.toString(), y);
                    }
                }
            } catch (Throwable t) { logOnce("Nearby", t); }
        }
        if (player != null && modOn("Offhand", true)) {
            try {
                Object stack = tryInvoke(player, "method_6079", "getOffHandStack",
                                         "getOffhandItem");
                if (stack != null) {
                    Object isEmpty = tryInvoke(stack, "method_7960", "isEmpty");
                    if (!Boolean.TRUE.equals(isEmpty)) {
                        Object name = tryInvoke(stack, "method_7964", "getName",
                                                "getDisplayName", "getHoverName");
                        String n = name != null ? compToString(name) : "?";
                        if (n.length() > 24) n = n.substring(0, 21) + "…";
                        StringBuilder sb = new StringBuilder("§4Offhand §f").append(n);
                        Object count = tryInvoke(stack, "method_7947", "getCount");
                        if (count instanceof Number && ((Number) count).intValue() > 1) {
                            sb.append(" §8x§7").append(((Number) count).intValue());
                        }
                        y = drawLine(dc, font, sb.toString(), y);
                    }
                }
            } catch (Throwable t) { logOnce("Offhand", t); }
        }
        if (player != null && modOn("DeathTime", true)) {
            try {
                Object dead = tryInvoke(player, "method_29504", "isDead", "isDeadOrDying");
                boolean isDead = Boolean.TRUE.equals(dead);
                if (isDead && !wasDead) {
                    lastDeathMs = System.currentTimeMillis();
                    // Capture coordinates the moment the player dies — these
                    // survive the respawn so the user can find their drops.
                    try {
                        deathX = firstNum(player, "method_23317", "getX").doubleValue();
                        deathY = firstNum(player, "method_23318", "getY").doubleValue();
                        deathZ = firstNum(player, "method_23321", "getZ").doubleValue();
                        deathWorld = currentMapKey();
                        deathPosSet = true;
                    } catch (Throwable ignored) {}
                }
                wasDead = isDead;
                if (lastDeathMs > 0) {
                    long sinceSec = (System.currentTimeMillis() - lastDeathMs) / 1000;
                    long h = sinceSec / 3600, m = (sinceSec % 3600) / 60, s = sinceSec % 60;
                    String t = h > 0 ? String.format("%dh %dm", h, m)
                            : m > 0 ? String.format("%dm %ds", m, s)
                            : s + "s";
                    y = drawLine(dc, font, "§4Last death §f" + t + " §8ago", y);
                }
            } catch (Throwable t) { logOnce("DeathTime", t); }
        }
        if (modOn("SessionTime", true)) {
            try {
                long elapsed = (System.currentTimeMillis() - sessionStartMs) / 1000;
                long h = elapsed / 3600, m = (elapsed % 3600) / 60, s = elapsed % 60;
                String t = h > 0 ? String.format("%dh %02dm %02ds", h, m, s)
                                 : String.format("%dm %02ds", m, s);
                y = drawLine(dc, font, "§4Session §f" + t, y);
            } catch (Throwable t) { logOnce("SessionTime", t); }
        }
        if (modOn("TabCount", true)) {
            try {
                Object net = tryInvoke(mc, "method_1562", "getNetworkHandler", "getConnection");
                if (net != null) {
                    Object list = tryInvoke(net, "method_2880",
                                            "getPlayerList", "getOnlinePlayers");
                    if (list instanceof java.util.Collection) {
                        int n = ((java.util.Collection<?>) list).size();
                        y = drawLine(dc, font, "§4Online §f" + n + " §4players", y);
                    }
                }
            } catch (Throwable t) { logOnce("TabCount", t); }
        }
        if (player != null && modOn("SpawnDist", true)) {
            try {
                // 1.21.11 reshaped the spawn API: world.getSpawnPoint() now
                // returns a WorldProperties$SpawnPoint record (class_5217$class_12064)
                // whose `globalPos` field holds the BlockPos. We try the new
                // path first then fall back to the old method_43126 for
                // compatibility with older MC instances.
                Object world = worldField != null ? worldField.get(mc) : null;
                Object spawn = world == null ? null
                                              : tryInvoke(world, "method_74854",
                                                          "getSpawnPoint",
                                                          "method_43126",
                                                          "getSpawnPos",
                                                          "getSharedSpawnPos");
                Object spawnPos = spawn;
                if (spawn != null && !spawn.getClass().getName().endsWith("class_2338")) {
                    // Walk the SpawnPoint record fields to find a BlockPos-like
                    Object gp = null;
                    Class<?> cc = spawn.getClass();
                    while (cc != null && gp == null) {
                        for (Field f : cc.getDeclaredFields()) {
                            try {
                                f.setAccessible(true);
                                Object v = f.get(spawn);
                                if (v == null) continue;
                                String cn = v.getClass().getName();
                                if (cn.endsWith("class_2338") || cn.endsWith("BlockPos")) {
                                    gp = v; break;
                                }
                                // GlobalPos wraps a BlockPos
                                Object inner = tryInvoke(v, "comp_2208", "pos");
                                if (inner != null) {
                                    String in = inner.getClass().getName();
                                    if (in.endsWith("class_2338") || in.endsWith("BlockPos")) {
                                        gp = inner; break;
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                        cc = cc.getSuperclass();
                    }
                    spawnPos = gp;
                }
                if (spawnPos != null) {
                    int sx = safeInt(spawnPos, "method_10263", "getX");
                    int sz = safeInt(spawnPos, "method_10260", "getZ");
                    double px = firstNum(player, "method_23317", "getX").doubleValue();
                    double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                    double d  = Math.sqrt((sx-px)*(sx-px)+(sz-pz)*(sz-pz));
                    String pretty = d >= 1000 ? String.format("%.2f§4 km", d/1000.0)
                                                : String.format("%.0f§4 m",  d);
                    y = drawLine(dc, font, "§4Spawn §f" + pretty + " §8(§7"
                            + sx + ", " + sz + "§8)", y);
                }
            } catch (Throwable t) { logOnce("SpawnDist", t); }
        }
        if (modOn("BestCps", true)) {
            try {
                int cur = LMB_TIMES.size();
                if (cur > bestCpsThisSession) bestCpsThisSession = cur;
                y = drawLine(dc, font, "§4Best CPS §f" + bestCpsThisSession, y);
            } catch (Throwable t) { logOnce("BestCps", t); }
        }
        if (modOn("BestCombo", true)) {
            try {
                if (comboCount > bestComboThisSession) bestComboThisSession = comboCount;
                y = drawLine(dc, font, "§4Best combo §f" + bestComboThisSession, y);
            } catch (Throwable t) { logOnce("BestCombo", t); }
        }
        if (player != null && modOn("Move", true)) {
            try {
                boolean sprint  = Boolean.TRUE.equals(tryInvoke(player, "method_5624", "isSprinting"));
                boolean sneak   = Boolean.TRUE.equals(tryInvoke(player, "method_5715",
                                                                "isSneaking", "isCrouching"));
                boolean elytra  = Boolean.TRUE.equals(tryInvoke(player, "method_6128",
                                                                "isFallFlying", "isGliding"));
                boolean inWater = Boolean.TRUE.equals(tryInvoke(player, "method_5799",
                                                                "isInWater", "isInLiquid"));
                StringBuilder s = new StringBuilder("§4Move ");
                if (elytra)       s.append("§dGliding");
                else if (sprint && sneak) s.append("§eCrouch-Sprint");
                else if (sprint)  s.append("§aSprint");
                else if (sneak)   s.append("§7Sneak");
                else if (inWater) s.append("§9Swim");
                else              s.append("§fWalk");
                y = drawLine(dc, font, s.toString(), y);
            } catch (Throwable t) { logOnce("Move", t); }
        }
        if (player != null && modOn("Velocity", true)) {
            try {
                Object vel = tryInvoke(player, "method_18798", "getVelocity", "getDeltaMovement");
                if (vel != null) {
                    double vx = firstNum(vel, "field_1352", "x").doubleValue();
                    double vy = firstNum(vel, "field_1351", "y").doubleValue();
                    double vz = firstNum(vel, "field_1350", "z").doubleValue();
                    // Convert per-tick delta → blocks/sec (× 20)
                    y = drawLine(dc, font, String.format(
                        "§4Vel §fX §7%.2f §fY §7%.2f §fZ §7%.2f §8(b/t)",
                        vx, vy, vz), y);
                }
            } catch (Throwable t) { logOnce("Velocity", t); }
        }
        if (player != null && modOn("HandState", true)) {
            try {
                Object usingItem = tryInvoke(player, "method_6115", "isUsingItem");
                String state = "§7Idle";
                if (Boolean.TRUE.equals(usingItem)) {
                    Object stack = tryInvoke(player, "method_6030",
                                             "getActiveItem", "getUseItem");
                    if (stack != null) {
                        Object useAction = tryInvoke(stack, "method_7976",
                                                     "getUseAction", "getUseAnimation");
                        String act = useAction != null ? useAction.toString() : "";
                        Object name = tryInvoke(stack, "method_7964", "getName",
                                                "getDisplayName", "getHoverName");
                        String n = name != null ? compToString(name) : "?";
                        if (n.length() > 20) n = n.substring(0, 17) + "…";
                        if (act.contains("EAT"))      state = "§eEating §f" + n;
                        else if (act.contains("DRINK")) state = "§bDrinking §f" + n;
                        else if (act.contains("BOW"))   state = "§6Drawing §f" + n;
                        else if (act.contains("BLOCK")) state = "§9Blocking §f" + n;
                        else if (act.contains("SPYGLASS")) state = "§5Looking §f" + n;
                        else if (act.contains("CROSSBOW")) state = "§6Loading §f" + n;
                        else if (act.contains("SPEAR"))    state = "§3Charging §f" + n;
                        else                              state = "§dUsing §f" + n;
                    }
                }
                y = drawLine(dc, font, "§4Hand " + state, y);
            } catch (Throwable t) { logOnce("HandState", t); }
        }
        if (modOn("EntityCount", true)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    Object entIter = tryInvoke(world, "method_18112",
                                               "getEntities", "entitiesForRendering");
                    int n = 0;
                    if (entIter instanceof Iterable) for (Object ignored : (Iterable<?>) entIter) n++;
                    y = drawLine(dc, font, "§4Entities §f" + n, y);
                }
            } catch (Throwable t) { logOnce("EntityCount", t); }
        }
        if (modOn("Difficulty", true)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    Object diff = tryInvoke(world, "method_8407", "getDifficulty");
                    if (diff != null) {
                        String n = diff.toString();    // PEACEFUL/EASY/NORMAL/HARD
                        String col = n.contains("PEACEFUL") ? "§a"
                                  : n.contains("EASY")     ? "§e"
                                  : n.contains("NORMAL")   ? "§6"
                                  : "§c";
                        // Capitalize: "PEACEFUL" -> "Peaceful"
                        String pretty = n.charAt(0)
                            + n.substring(1).toLowerCase().replace('_', ' ');
                        y = drawLine(dc, font, "§4Difficulty " + col + pretty, y);
                    }
                }
            } catch (Throwable t) { logOnce("Difficulty", t); }
        }
        if (player != null && modOn("InvFree", true)) {
            try {
                Object inv = tryInvoke(player, "method_31548", "getInventory", "getInventoryItems");
                if (inv != null) {
                    int total = 0, empty = 0;
                    // method_67533 is the public List<ItemStack> accessor in 1.21.11.
                    // Previous version walked field_7547 directly via getField(),
                    // which only finds PUBLIC fields — but field_7547 is private,
                    // so this silently failed and InvFree never rendered.
                    Object main = tryInvoke(inv, "method_67533", "getInventoryItems",
                                            "getMain", "getStacks");
                    if (main == null) {
                        // Fallback: cachedField walks declared+inherited and
                        // setAccessible(true) past private. Last resort.
                        Field mainField = cachedField(inv.getClass(), "field_7547");
                        if (mainField == null) mainField = cachedField(inv.getClass(), "main");
                        if (mainField != null) main = mainField.get(inv);
                    }
                    if (main instanceof java.util.List) {
                        for (Object stack : (java.util.List<?>) main) {
                            total++;
                            if (Boolean.TRUE.equals(tryInvoke(stack, "method_7960", "isEmpty"))) empty++;
                        }
                    }
                    if (total > 0) {
                        String col = empty == 0 ? "§c" : empty < 5 ? "§e" : "§a";
                        y = drawLine(dc, font, "§4Slots " + col + empty + "§4/§f" + total + " §8free", y);
                    }
                }
            } catch (Throwable t) { logOnce("InvFree", t); }
        }
        if (player != null && modOn("Slot", true)) {
            try {
                Object inv = tryInvoke(player, "method_31548", "getInventory", "getInventoryItems");
                if (inv != null) {
                    int sel = -1;
                    // Prefer the public method_67532 getter (returns int slot index).
                    Object selObj = tryInvoke(inv, "method_67532", "getSelectedSlot");
                    if (selObj instanceof Number) sel = ((Number) selObj).intValue();
                    else {
                        // Fallback to private field via cachedField (setAccessible).
                        // getField() only finds public — field_7545 is private.
                        Field selField = cachedField(inv.getClass(), "field_7545");
                        if (selField == null) selField = cachedField(inv.getClass(), "selectedSlot");
                        if (selField != null) {
                            try { sel = selField.getInt(inv); } catch (Throwable ignored) {}
                        }
                    }
                    if (sel >= 0) {
                        StringBuilder s = new StringBuilder("§4Hotbar ");
                        for (int i = 0; i < 9; i++) {
                            s.append(i == sel ? "§c[§f" + (i+1) + "§c]" : "§7" + (i+1));
                        }
                        y = drawLine(dc, font, s.toString(), y);
                    }
                }
            } catch (Throwable t) { logOnce("Slot", t); }
        }
        if (modOn("LookBlock", true)) {
            try {
                Object hit = tryInvoke(mc, "method_64829", "getCrosshairTarget", "crosshairTarget");
                if (hit == null) {
                    try { hit = mc.getClass().getField("field_1765").get(mc); }
                    catch (Throwable ignored) {}
                }
                if (hit != null) {
                    Object typeObj = tryInvoke(hit, "method_17783", "getType");
                    String t = typeObj == null ? "" : typeObj.toString();
                    if (t.contains("BLOCK")) {
                        Object pos = tryInvoke(hit, "method_17777", "getBlockPos");
                        if (pos != null) {
                            int bx = safeInt(pos, "method_10263", "getX");
                            int by = safeInt(pos, "method_10264", "getY");
                            int bz = safeInt(pos, "method_10260", "getZ");
                            y = drawLine(dc, font, String.format(
                                "§4Look-at §f%d§4, §f%d§4, §f%d", bx, by, bz), y);
                        }
                    }
                }
            } catch (Throwable t) { logOnce("LookBlock", t); }
        }
        if (modOn("BlockInfo", false)) {
            try {
                Object hit = tryInvoke(mc, "method_64829", "getCrosshairTarget");
                if (hit == null) {
                    Field f = cachedField(mc.getClass(), "field_1765");
                    if (f != null) hit = f.get(mc);
                }
                if (hit != null) {
                    Object typeObj = tryInvoke(hit, "method_17783", "getType");
                    if (typeObj != null && typeObj.toString().contains("BLOCK")) {
                        Object pos = tryInvoke(hit, "method_17777", "getBlockPos");
                        Object world = worldField != null ? worldField.get(mc) : null;
                        if (pos != null && world != null) {
                            // world.getBlockState(pos)
                            Object state = null;
                            for (Method m : world.getClass().getMethods()) {
                                if (!"method_8320".equals(m.getName())
                                    && !"getBlockState".equals(m.getName())) continue;
                                if (m.getParameterCount() != 1) continue;
                                try { state = m.invoke(world, pos); } catch (Throwable ignored) {}
                                if (state != null) break;
                            }
                            if (state != null) {
                                Object block = tryInvoke(state, "method_26204", "getBlock");
                                String blockId = "?";
                                if (block != null) {
                                    String s = String.valueOf(block).toLowerCase();
                                    int braceL = s.indexOf('{');
                                    int braceR = braceL >= 0 ? s.indexOf('}', braceL) : -1;
                                    String full = (braceL >= 0 && braceR > braceL)
                                        ? s.substring(braceL + 1, braceR) : s;
                                    int colon = full.indexOf(':');
                                    blockId = (colon >= 0) ? full.substring(colon + 1) : full;
                                }
                                // Hardness: state.getHardness(world, pos)
                                // method_26214 in 1.21.x. Returns float.
                                Float hardness = null;
                                for (Method m : state.getClass().getMethods()) {
                                    if (!"method_26214".equals(m.getName())
                                        && !"getHardness".equals(m.getName())) continue;
                                    if (m.getReturnType() != float.class) continue;
                                    if (m.getParameterCount() != 2) continue;
                                    try {
                                        hardness = (Float) m.invoke(state, world, pos);
                                    } catch (Throwable ignored) {}
                                    break;
                                }
                                String hardStr = (hardness == null)
                                    ? "?" : String.format("%.1f", hardness);
                                y = drawLine(dc, font,
                                    "§4Block §f" + blockId + " §8h:" + hardStr, y);
                            }
                        }
                    }
                }
            } catch (Throwable t) { logOnce("BlockInfo", t); }
        }
        if (player != null && modOn("ArmorPct", true)) {
            try {
                // 1.21.11 dropped the PlayerInventory.armor List. Armor is now
                // accessed via getEquippedStack(EquipmentSlot) on the entity
                // itself, where EquipmentSlot is the class_1304 enum.
                Class<?> slotEnum = Class.forName("net.minecraft.class_1304");
                int worstPct = -1;
                Method getEquipped = null;
                for (Method m : player.getClass().getMethods()) {
                    if (!"method_6118".equals(m.getName())
                        && !"getEquippedStack".equals(m.getName())) continue;
                    if (m.getParameterCount() != 1) continue;
                    if (!m.getParameterTypes()[0].isAssignableFrom(slotEnum)) continue;
                    getEquipped = m; break;
                }
                if (getEquipped != null) {
                    for (Object slot : slotEnum.getEnumConstants()) {
                        String n = slot.toString();
                        // skip MAINHAND/OFFHAND, only keep armor slots
                        if (!n.equals("HEAD") && !n.equals("CHEST")
                            && !n.equals("LEGS") && !n.equals("FEET")) continue;
                        Object stack = getEquipped.invoke(player, slot);
                        if (stack == null) continue;
                        if (Boolean.TRUE.equals(tryInvoke(stack, "method_7960", "isEmpty"))) continue;
                        Object dmg = tryInvoke(stack, "method_7919", "getDamageValue", "getDamage");
                        Object max = tryInvoke(stack, "method_7936", "getMaxDamage");
                        if (dmg instanceof Number && max instanceof Number
                            && ((Number) max).intValue() > 0) {
                            int left = ((Number) max).intValue() - ((Number) dmg).intValue();
                            int pct = (int) (left * 100L / ((Number) max).intValue());
                            if (worstPct < 0 || pct < worstPct) worstPct = pct;
                        }
                    }
                }
                if (worstPct >= 0) {
                    String col = worstPct < 15 ? "§c" : worstPct < 40 ? "§e" : "§a";
                    y = drawLine(dc, font, "§4Armor " + col + worstPct + "§4% §8(worst)", y);
                }
            } catch (Throwable t) { logOnce("ArmorPct", t); }
        }
        if (player != null && modOn("GameMode", true)) {
            try {
                // method_1583 is private void in 1.21.11 — there's no public
                // getter. The InteractionManager is the public field
                // mc.field_1761 (class_636). Read it directly.
                Object im = null;
                Field imField = cachedField(mc.getClass(), "field_1761");
                if (imField == null) imField = cachedField(mc.getClass(), "interactionManager");
                if (imField != null) im = imField.get(mc);
                Object gm = im == null ? null : tryInvoke(im, "method_2920",
                                                          "getCurrentGameMode", "getGameMode");
                if (gm != null) {
                    String n = gm.toString();
                    String col = n.contains("CREATIVE")  ? "§b"
                              : n.contains("SURVIVAL")  ? "§a"
                              : n.contains("ADVENTURE") ? "§e"
                              : n.contains("SPECTATOR") ? "§7"
                              : "§f";
                    String pretty = n.charAt(0) + n.substring(1).toLowerCase();
                    y = drawLine(dc, font, "§4Mode " + col + pretty, y);
                }
            } catch (Throwable t) { logOnce("GameMode", t); }
        }
        if (player != null && modOn("WorldBorder", true)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                Object wb = world == null ? null : resolveWorldBorder(world);
                if (wb != null) {
                    double cx = firstNum(wb, "method_11964", "getCenterX").doubleValue();
                    double cz = firstNum(wb, "method_11980", "getCenterZ").doubleValue();
                    double size = firstNum(wb, "method_11965", "getSize").doubleValue();
                    double half = size / 2.0;
                    double px = firstNum(player, "method_23317", "getX").doubleValue();
                    double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                    // distance to the nearest border edge — negative if outside
                    double distX = half - Math.abs(px - cx);
                    double distZ = half - Math.abs(pz - cz);
                    double dist  = Math.min(distX, distZ);
                    String col = dist < 16 ? "§c" : dist < 64 ? "§e" : "§a";
                    String pretty = dist >= 1000
                        ? String.format("%.2f§4 km", dist / 1000.0)
                        : String.format("%.0f§4 m",  dist);
                    y = drawLine(dc, font, "§4Border " + col + pretty, y);
                } else if (!worldBorderResolveLogged) {
                    worldBorderResolveLogged = true;
                    System.err.println("[ShadowHud][WorldBorder] could not resolve WorldBorder on world="
                        + (world == null ? "null" : world.getClass().getName()));
                }
            } catch (Throwable t) { logOnce("WorldBorder", t); }
        }
        if (modOn("HitsDealt", true)) {
            try {
                y = drawLine(dc, font, "§4Hits dealt §a" + sessionHitsDealt, y);
            } catch (Throwable t) { logOnce("HitsDealt", t); }
        }
        if (modOn("HitsTaken", true)) {
            try {
                y = drawLine(dc, font, "§4Hits taken §c" + sessionHitsTaken, y);
            } catch (Throwable t) { logOnce("HitsTaken", t); }
        }
        if (player != null && modOn("Level", true)) {
            try {
                int lvl = safeInt(player, "field_7520", "experienceLevel");
                if (lvl < 0) {
                    // Field may be public-with-name or get-method; fall back.
                    Object lvlObj = tryInvoke(player, "method_7349", "getExperienceLevel",
                                              "getLevel");
                    if (lvlObj instanceof Number) lvl = ((Number) lvlObj).intValue();
                }
                // experienceProgress ∈ [0,1], how full the bar is
                float prog = -1f;
                try {
                    Field pf = player.getClass().getField("field_7510");
                    prog = pf.getFloat(player);
                } catch (Throwable ignored) {
                    try {
                        Field pf = player.getClass().getField("experienceProgress");
                        prog = pf.getFloat(player);
                    } catch (Throwable ignored2) {}
                }
                if (lvl >= 0) {
                    StringBuilder s = new StringBuilder("§4Level §a").append(lvl);
                    if (prog >= 0 && prog <= 1) {
                        int filled = Math.min(10, Math.max(0, (int) (prog * 10)));
                        s.append("  §2");
                        for (int i = 0; i < 10; i++) s.append(i < filled ? "█" : "▒");
                        s.append(" §f").append((int) (prog * 100)).append("§4%");
                    }
                    y = drawLine(dc, font, s.toString(), y);
                }
            } catch (Throwable t) { logOnce("Level", t); }
        }
        if (modOn("Killstreak", true)) {
            try {
                // Apply idle-reset based on cfgKillstreakReset:
                //   0=on death only (default)  1=idle 60s  2=idle 30s  3=never
                if (cfgKillstreakReset == 1 || cfgKillstreakReset == 2) {
                    long idle = (cfgKillstreakReset == 1) ? 60_000L : 30_000L;
                    if (killstreak > 0 && killstreakLastHitMs > 0
                        && System.currentTimeMillis() - killstreakLastHitMs > idle) {
                        killstreak = 0;
                    }
                }
                String col = killstreak >= 10 ? "§c" : killstreak >= 5 ? "§e"
                           : killstreak >= 1 ? "§a" : "§7";
                y = drawLine(dc, font, "§4Streak " + col + killstreak, y);
            } catch (Throwable t) { logOnce("Killstreak", t); }
        }
        if (modOn("CoordCopy", true)) {
            try {
                y = drawLine(dc, font, "§4Copy §8(§7Insert§8)", y);
            } catch (Throwable t) { logOnce("CoordCopy", t); }
        }
        if (modOn("DayArc", true)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    Number tod = firstNum(world, "method_8532", "getTimeOfDay", "getDayTime");
                    long inDay = tod.longValue() % 24000L;
                    boolean isDay = inDay < 12000L;
                    // Render a 16-cell bar with the marker at sun/moon position
                    int cells = 16;
                    int pos = (int) Math.round((inDay / 24000.0) * cells);
                    StringBuilder s = new StringBuilder(isDay ? "§4Sky §6☀ " : "§4Sky §7☾ ");
                    for (int i = 0; i < cells; i++) {
                        if (i == pos)        s.append(isDay ? "§e●" : "§f●");
                        else if (i < cells/2) s.append(isDay ? "§6▁" : "§8▁");
                        else                  s.append(isDay ? "§8▁" : "§9▁");
                    }
                    y = drawLine(dc, font, s.toString(), y);
                }
            } catch (Throwable t) { logOnce("DayArc", t); }
        }
        if (modOn("Music", true)) {
            try {
                // mc.getMusicTracker() returns a class_1142 (was class_312 in
                // older mappings — that intermediary now refers to the mouse
                // handler!). Field is field_5574 (was field_5304).
                Object mt = tryInvoke(mc, "method_1538", "getMusicTracker");
                Object cur = null;
                if (mt != null) {
                    for (String n : new String[]{"field_5574", "field_5304", "current"}) {
                        try {
                            Field cf = mt.getClass().getDeclaredField(n);
                            cf.setAccessible(true);
                            cur = cf.get(mt);
                            if (cur != null) break;
                        } catch (Throwable ignored) {}
                    }
                }
                String label;
                if (cur == null) {
                    label = "§7— silent";
                } else {
                    Object id = tryInvoke(cur, "method_4775", "getId", "getLocation");
                    String s = id == null ? "playing" : String.valueOf(id);
                    int colon = s.indexOf(':');
                    if (colon >= 0) s = s.substring(colon + 1);
                    int slash = s.lastIndexOf('/');
                    if (slash >= 0) s = s.substring(slash + 1);
                    if (s.length() > 24) s = s.substring(0, 21) + "…";
                    label = "§a♫ §f" + s;
                }
                y = drawLine(dc, font, "§4Music " + label, y);
            } catch (Throwable t) { logOnce("Music", t); }
        }
        if (player != null && modOn("JumpHeight", true)) {
            try {
                double yy = firstNum(player, "method_23318", "getY").doubleValue();
                Object grounded = tryInvoke(player, "method_24828", "isOnGround", "onGround");
                boolean onGround = Boolean.TRUE.equals(grounded);
                if (onGround) {
                    jumpStartY = yy;
                    jumpPeakY = yy;
                } else {
                    if (yy > jumpPeakY) jumpPeakY = yy;
                }
                double airtime = jumpPeakY - jumpStartY;
                if (airtime > 0.01) {
                    String col = airtime > 4.0 ? "§c" : airtime > 1.5 ? "§e" : "§a";
                    y = drawLine(dc, font, String.format(
                        "§4Air §f+§f%.2f §4b §8(peak §f%.0f§8)", airtime, jumpPeakY), y);
                }
            } catch (Throwable t) { logOnce("JumpHeight", t); }
        }
        if (modOn("Fullbright", false)) {
            try {
                y = drawLine(dc, font, "§4Fullbright §a● ON", y);
            } catch (Throwable t) { logOnce("Fullbright", t); }
        }
        if (modOn("Streamer", false)) {
            try {
                y = drawLine(dc, font, "§4Streamer §c● protected", y);
            } catch (Throwable t) { logOnce("Streamer", t); }
        }
        if (player != null && modOn("Waypoint", true)) {
            try {
                String wid = currentMapKey();
                if (wpSet && wid.equals(wpWorld)) {
                    double px = firstNum(player, "method_23317", "getX").doubleValue();
                    double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                    double dx = wpX - px, dz = wpZ - pz;
                    double dist = Math.sqrt(dx*dx + dz*dz);
                    // Bearing: 0° = +Z (south), 90° = +X (east) etc.
                    double bearing = Math.toDegrees(Math.atan2(dx, dz));
                    if (bearing < 0) bearing += 360;
                    String pretty = dist >= 1000
                        ? String.format("%.2f§4 km", dist / 1000.0)
                        : String.format("%.0f§4 m",  dist);
                    y = drawLine(dc, font, String.format(
                        "§4Waypoint §f%s §8(@ §7%d, %d, %d§8 — §f%.0f°§8)",
                        pretty, (int)wpX, (int)wpY, (int)wpZ, bearing), y);
                } else {
                    y = drawLine(dc, font, "§4Waypoint §8(§7Home§8 to set)", y);
                }
            } catch (Throwable t) { logOnce("Waypoint", t); }
        }
        if (player != null && modOn("Tracker", true)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    Object ps = tryInvoke(world, "method_18456", "getPlayers", "players");
                    if (ps instanceof java.util.Collection) {
                        double px = firstNum(player, "method_23317", "getX").doubleValue();
                        double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                        Object best = null; double bestD2 = Double.MAX_VALUE;
                        for (Object pp : (java.util.Collection<?>) ps) {
                            if (pp == player) continue;
                            double ex = firstNum(pp, "method_23317", "getX").doubleValue();
                            double ez = firstNum(pp, "method_23321", "getZ").doubleValue();
                            double d2 = (ex-px)*(ex-px)+(ez-pz)*(ez-pz);
                            if (d2 < bestD2) { bestD2 = d2; best = pp; }
                        }
                        if (best != null) {
                            double ex = firstNum(best, "method_23317", "getX").doubleValue();
                            double ez = firstNum(best, "method_23321", "getZ").doubleValue();
                            double dist = Math.sqrt(bestD2);
                            double bearing = Math.toDegrees(Math.atan2(ex - px, ez - pz));
                            if (bearing < 0) bearing += 360;
                            String[] dirs = {"S","SW","W","NW","N","NE","E","SE"};
                            int idx = (int) Math.round(bearing / 45.0) & 7;
                            Object name = tryInvoke(best, "method_5477", "getName", "getDisplayName");
                            String n = name != null ? compToString(name) : "?";
                            if (n.length() > 16) n = n.substring(0, 14) + "…";
                            y = drawLine(dc, font, String.format(
                                "§4Track §f%s §8• §7%s §8(§f%.0f§4 m§8)",
                                n, dirs[idx], dist), y);
                        } else {
                            y = drawLine(dc, font, "§4Track §7no players", y);
                        }
                    }
                }
            } catch (Throwable t) { logOnce("Tracker", t); }
        }
        if (modOn("BlockCount", true)) {
            try {
                y = drawLine(dc, font, "§4Placed §f" + sessionBlocksPlaced + " §4blocks", y);
            } catch (Throwable t) { logOnce("BlockCount", t); }
        }
        if (modOn("DropCount", true)) {
            try {
                y = drawLine(dc, font, "§4Drops §f" + sessionDrops, y);
            } catch (Throwable t) { logOnce("DropCount", t); }
        }
        if (modOn("DPS", true)) {
            try {
                long now = System.currentTimeMillis();
                while (!DPS_HISTORY.isEmpty() && DPS_HISTORY.peekFirst()[0] < now - 5000) {
                    DPS_HISTORY.pollFirst();
                }
                long sum = 0;
                for (long[] e : DPS_HISTORY) sum += e[1];
                double dps = (sum / 100.0) / 5.0;   // damage / 5-second window
                String col = dps >= 8 ? "§c" : dps >= 3 ? "§e" : dps > 0 ? "§a" : "§7";
                y = drawLine(dc, font, String.format("§4DPS %s%.1f", col, dps), y);
            } catch (Throwable t) { logOnce("DPS", t); }
        }
        if (modOn("TPS", true) || modOn("TpsBar", false)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    long now = System.currentTimeMillis();
                    // class_638 (ClientWorld) doesn't have getGameTime in 1.21.11;
                    // use getTimeOfDay (method_8532). It also advances every
                    // server tick so the TPS calculation is unchanged.
                    Number wt = firstNum(world, "method_8532", "getTimeOfDay",
                                                "getTime", "getGameTime");
                    long worldT = wt.longValue();
                    if (tpsLastRealMs > 0 && now - tpsLastRealMs >= 1000) {
                        long dt = now - tpsLastRealMs;
                        long dw = worldT - tpsLastWorldTime;
                        if (dt > 0) {
                            double tps = (dw * 1000.0) / dt;
                            // Smooth + clamp 0..20
                            cachedTps = Math.min(20.0, Math.max(0.0,
                                cachedTps * 0.5 + tps * 0.5));
                        }
                        tpsLastRealMs = now;
                        tpsLastWorldTime = worldT;
                    } else if (tpsLastRealMs == 0) {
                        tpsLastRealMs = now; tpsLastWorldTime = worldT;
                    }
                    String col = cachedTps >= 19.5 ? "§a"
                              : cachedTps >= 17.0 ? "§e"
                              : "§c";
                    if (modOn("TPS", true)) {
                        y = drawLine(dc, font, String.format("§4TPS %s%.1f", col, cachedTps), y);
                    }
                    if (modOn("TpsBar", false)) {
                        // 10-cell bar; full = 20 tps. Dim cells past current.
                        int filled = (int) Math.round(cachedTps / 2.0);
                        StringBuilder bar = new StringBuilder("§4TPS ").append(col);
                        for (int i = 0; i < filled; i++) bar.append("█");
                        bar.append("§8");
                        for (int i = filled; i < 10; i++) bar.append("█");
                        bar.append(" ").append(col).append(String.format("%.1f", cachedTps));
                        y = drawLine(dc, font, bar.toString(), y);
                    }
                }
            } catch (Throwable t) { logOnce("TPS", t); }
        }
        if (player != null && modOn("TntTimer", true)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    // ClientWorld.getEntities() → Iterable<Entity>; we filter by class name
                    // suffix to avoid hard-coding net.minecraft.class_1685.
                    Object entIter = tryInvoke(world, "method_18112", "getEntities", "entitiesForRendering");
                    if (entIter instanceof Iterable) {
                        double px = firstNum(player, "method_23317", "getX").doubleValue();
                        double py = firstNum(player, "method_23320", "getEyeY").doubleValue();
                        double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                        Object best = null;
                        double bestDist2 = Double.MAX_VALUE;
                        int bestFuse = 0;
                        for (Object e : (Iterable<?>) entIter) {
                            String cn = e.getClass().getName();
                            // 1.21.11: TNT entity is class_1541 (NOT class_1685
                            // — that's EnderPearlEntity now). Fuse getter is
                            // method_6969(). Old class_1685/method_7194 lookup
                            // never matched, so this module never displayed.
                            if (!cn.endsWith("class_1541") && !cn.endsWith("class_1685")
                                && !cn.endsWith("TntEntity") && !cn.endsWith("PrimedTnt")) continue;
                            int fuse = safeInt(e, "method_6969", "method_7194",
                                               "getFuse", "getFuseTicks");
                            if (fuse <= 0) continue;
                            double ex = firstNum(e, "method_23317", "getX").doubleValue();
                            double ey = firstNum(e, "method_23318", "getY").doubleValue();
                            double ez = firstNum(e, "method_23321", "getZ").doubleValue();
                            double d2 = (ex-px)*(ex-px)+(ey-py)*(ey-py)+(ez-pz)*(ez-pz);
                            if (d2 < bestDist2) {
                                bestDist2 = d2; bestFuse = fuse; best = e;
                            }
                        }
                        if (best != null) {
                            double sec = bestFuse / 20.0;
                            double dist = Math.sqrt(bestDist2);
                            String col = sec < 0.5 ? "§c" : sec < 1.5 ? "§e" : "§a";
                            y = drawLine(dc, font, String.format("§4TNT %s%.2fs §8@ §f%.1f§4 m",
                                col, sec, dist), y);
                        }
                    }
                }
            } catch (Throwable t) { logOnce("TntTimer", t); }
        }
        // --- Minimap: rendered inline between Biome and Effects ------------
        if (modOn("Map", true)) {
            // Wrap in try-catch — renderMinimap does world-block sampling
            // which can throw on dimension changes, chunk-load races, etc.
            // Without this guard, an unchecked throw kills every module
            // that comes AFTER Map in the render chain.
            try { y = renderMinimap(dc, 2, y + 1); }
            catch (Throwable t) { logOnce("Map", t); }
        }

        // --- Effects: local player's active status effects -------------------
        // Target-effects was removed: vanilla MC doesn't sync other players'
        // individual effects to the client — only a combined particle color —
        // so the feature can't work reliably for non-local entities.
        if (modOn("Effects", true)) {
            try {
                if (player != null) {
                    Object effects = tryInvoke(player, "method_6026",
                                               "getActiveStatusEffects", "getActiveEffects");
                    if (effects instanceof java.util.Collection) {
                        int shown = 0;
                        int limit = EFFECTS_LIMITS[Math.max(0,
                            Math.min(EFFECTS_LIMITS.length - 1, effectsLimitIdx))];
                        for (Object ef : (java.util.Collection<?>) effects) {
                            if (shown >= limit) break;
                            y = drawLine(dc, font, formatEffect(ef), y);
                            shown++;
                        }
                    }
                }
            } catch (Throwable t) { logOnce("Effects", t); }
        }

        if (player != null && modOn("Biome", true)) {
            Object world = worldField != null ? worldField.get(mc) : null;
            if (world != null) {
                try {
                    Object pos = firstMethodInvoke(player, null,
                                                   "method_24515", "blockPosition", "getBlockPos");
                    if (getBiomeMethod == null) {
                        getBiomeMethod = findGetBiome(world.getClass(), pos.getClass());
                    }
                    if (!biomeDiagLogged) {
                        biomeDiagLogged = true;
                        System.out.println("[ShadowHud][Biome] world=" + world.getClass().getName()
                            + " pos=" + (pos == null ? "null" : pos.getClass().getName())
                            + " getBiomeMethod=" + (getBiomeMethod == null ? "NULL" : getBiomeMethod.toString()));
                    }
                    if (getBiomeMethod != null) {
                        Object entry = getBiomeMethod.invoke(world, pos);
                        String biomeName = extractBiomeName(entry);
                        if (!biomeEntryLogged && entry != null) {
                            biomeEntryLogged = true;
                            System.out.println("[ShadowHud][Biome] entry=" + entry.getClass().getName()
                                + " toString=" + entry + " resolved=" + biomeName);
                        }
                        if (biomeName != null) {
                            drawLine(dc, font, "§4Biome §f" + biomeName.replace('_', ' '), y);
                        }
                    }
                } catch (Throwable t) { logOnce("Biome", t); }
            }
        }

        // --- Keystrokes (WASD + Space): bottom-right corner --------------
        if (modOn("Keystrokes", true)) {
            try { renderKeystrokes(dc); } catch (Throwable t) { logOnce("Keystrokes", t); }
        }
        // --- Custom crosshair: dead-center overlay -----------------------
        if (modOn("Crosshair", false)) {
            try { renderCustomCrosshair(dc); } catch (Throwable t) { logOnce("Crosshair", t); }
        }
        // --- Tab list HP: render local player's HP at top of tab list ----
        if (modOn("TabHp", true)) {
            try { renderTabHp(dc); } catch (Throwable t) { logOnce("TabHp", t); }
        }
        // --- Numeric pings on the tab list (when held) -------------------
        if (modOn("TabPings", true)) {
            try { renderTabPings(dc); } catch (Throwable t) { logOnce("TabPings", t); }
        }

        // ============= NEW MODULES (Lunar/Badlion gap-fillers) ==============
        // Keep the existing list above unchanged; new lines append at the
        // bottom of the HUD column.
        if (player != null && modOn("Saturation", false)) {
            try {
                Object hm = tryInvoke(player, "method_7344", "getHungerManager");
                if (hm != null) {
                    float sat = firstNum(hm, "method_7589", "getSaturationLevel").floatValue();
                    int   hung = firstNum(hm, "method_7586", "getFoodLevel").intValue();
                    String col = sat <= 0 ? "§c" : (sat < 5 ? "§e" : "§a");
                    if (cfgSatStyle == 1) {
                        // Bar style: 10-cell saturation visualization (max sat = 20)
                        int filled = Math.min(10, Math.max(0, (int) Math.round(sat / 2.0)));
                        StringBuilder b = new StringBuilder("§6Sat ").append(col);
                        for (int i = 0; i < filled; i++) b.append("█");
                        b.append("§8");
                        for (int i = filled; i < 10; i++) b.append("█");
                        b.append(" ").append(col).append(String.format("%.1f", sat));
                        y = drawLine(dc, font, b.toString(), y);
                    } else {
                        y = drawLine(dc, font,
                            "§6Sat " + col + String.format("%.1f", sat)
                            + " §8/ §6Hung §f" + hung, y);
                    }
                }
            } catch (Throwable t) { logOnce("Saturation", t); }
        }
        if (player != null && modOn("ArmorList", false)) {
            try {
                // 1.21.11: scan full inventory, detect armor by registry ID.
                Object inv = tryInvoke(player, "method_31548", "getInventory");
                StringBuilder sb = new StringBuilder("§4Armor §f");
                boolean any = false;
                if (inv != null) {
                    Object allList = tryInvoke(inv, "method_67533");
                    if (allList instanceof Iterable) {
                        for (Object stack : (Iterable<?>) allList) {
                            if (!isArmorStack(stack)) continue;
                            String id = getItemId(stack);
                            String slotTag = armorSlotTag(id);
                            Object dmg = tryInvoke(stack, "method_7919", "getDamage");
                            Object max = tryInvoke(stack, "method_7936", "getMaxDamage");
                            int durPct = 100;
                            if (dmg instanceof Number && max instanceof Number
                                && ((Number) max).intValue() > 0) {
                                int d = ((Number) dmg).intValue();
                                int m = ((Number) max).intValue();
                                durPct = 100 - (d * 100) / m;
                            }
                            if (any) sb.append(" §8/ ");
                            String col = durPct < 10 ? "§c" : (durPct < 25 ? "§e" : "§a");
                            sb.append("§7").append(slotTag).append(' ')
                              .append(col).append(durPct).append('%');
                            any = true;
                        }
                    }
                }
                if (!any) sb.append("§8(no armor worn)");
                y = drawLine(dc, font, sb.toString(), y);
            } catch (Throwable t) { logOnce("ArmorList", t); }
        }
        if (player != null && modOn("Shield", false)) {
            try {
                // Find a shield in offhand first (where most players keep it),
                // fall back to mainhand. Shield item is class_1819 in yarn.
                Object stack = null;
                String slot = "?";
                Object off = tryInvoke(player, "method_6079", "getOffHandStack");
                if (isShieldStack(off)) { stack = off;  slot = "Off"; }
                if (stack == null) {
                    Object main = tryInvoke(player, "method_6047", "getMainHandStack");
                    if (isShieldStack(main)) { stack = main; slot = "Main"; }
                }
                if (stack != null) {
                    // Durability percent (100 = pristine, 0 = breaking)
                    int durPct = 100;
                    Object dmg = tryInvoke(stack, "method_7919", "getDamage");
                    Object max = tryInvoke(stack, "method_7936", "getMaxDamage");
                    if (dmg instanceof Number && max instanceof Number) {
                        int d = ((Number) dmg).intValue();
                        int m = ((Number) max).intValue();
                        if (m > 0) durPct = Math.max(0, 100 - (d * 100) / m);
                    }
                    String durCol = durPct < 10 ? "§c"
                                  : durPct < 25 ? "§e"
                                  : durPct < 60 ? "§a"
                                                : "§2";
                    // Block state: prefer player.isBlocking() (true only when
                    // the shield delay has elapsed). Fall back to hand-active.
                    String state = "§7ready";
                    Object blocking = tryInvoke(player, "method_6039", "isBlocking");
                    if (Boolean.TRUE.equals(blocking)) {
                        state = "§a§lBLOCK";
                    } else {
                        // Mid-charge state: method_6115 is the actual
                        // isUsingItem boolean. method_6058 returns the active
                        // Hand enum, not bool — the old check was always false.
                        Object using = tryInvoke(player, "method_6115", "isUsingItem");
                        if (Boolean.TRUE.equals(using)) state = "§e§lcharging";
                    }
                    // Cooldown (axe disable) — best-effort. signature varies
                    // across MC versions so we try multiple shapes.
                    String cooldown = "";
                    try {
                        Object cmgr = tryInvoke(player, "method_7357", "getItemCooldownManager");
                        Object item = tryInvoke(stack, "method_7909", "getItem");
                        if (cmgr != null && item != null) {
                            // Iterate methods: getCooldownProgress(Item|ItemStack, float) → float
                            for (Method mm : cmgr.getClass().getMethods()) {
                                if (mm.getReturnType() != float.class) continue;
                                if (mm.getParameterCount() != 2) continue;
                                if (mm.getParameterTypes()[1] != float.class) continue;
                                Class<?> p0 = mm.getParameterTypes()[0];
                                Object arg0 = p0.isInstance(stack) ? stack
                                            : p0.isInstance(item)  ? item
                                                                    : null;
                                if (arg0 == null) continue;
                                float prog = (float) mm.invoke(cmgr, arg0, 0f);
                                if (prog > 0f) {
                                    cooldown = " §c" + String.format("%.1fs", prog * 5f);
                                }
                                break;
                            }
                        }
                    } catch (Throwable ignored) {}
                    y = drawLine(dc, font,
                        "§4Shield §8[§7" + slot + "§8] " + state + " "
                        + durCol + durPct + "%" + cooldown, y);
                }
            } catch (Throwable t) { logOnce("Shield", t); }
        }
        // EnemyShield + SpearSwap blocks removed — see-through-walls combat
        // info + auto-action that gives unfair PvP advantage.
        if (modOn("NetGraph", false)) {
            try {
                long now = System.currentTimeMillis();
                if (now - lastPingSampleMs >= 500) {
                    lastPingSampleMs = now;
                    int sample = sampleCurrentPing(player);
                    if (sample > 0) {
                        pingHistory.addLast(sample);
                        while (pingHistory.size() > 60) pingHistory.removeFirst();
                    }
                }
                if (!pingHistory.isEmpty()) {
                    int min = Integer.MAX_VALUE, max = 0, sum = 0;
                    for (int p : pingHistory) {
                        if (p < min) min = p;
                        if (p > max) max = p;
                        sum += p;
                    }
                    int avg = sum / pingHistory.size();
                    String trend = pingHistory.size() >= 2
                        ? (pingHistory.peekLast() > avg ? "§c↑" : "§a↓") : "§7·";
                    y = drawLine(dc, font,
                        "§4Net §fmin §7" + min + " §favg §7" + avg
                        + " §fmax §7" + max + " " + trend, y);
                }
            } catch (Throwable t) { logOnce("NetGraph", t); }
        }
        if (player != null && modOn("TargetHud", false)) {
            try {
                // mc.targetedEntity field — the entity in the crosshair
                Object tgt = null;
                Field f = cachedField(mc.getClass(), "field_1692");
                if (f == null) f = cachedField(mc.getClass(), "targetedEntity");
                if (f != null) tgt = f.get(mc);
                if (tgt != null) {
                    Object name = tryInvoke(tgt, "method_5476", "getDisplayName", "getName");
                    String n = name != null ? compToString(name) : "?";
                    if (n.length() > 18) n = n.substring(0, 17) + "…";
                    // Guard: only living entities (class_1309) have getHealth.
                    // Use tryInvoke which returns null gracefully — older code
                    // called firstNum which throws and killed the WHOLE module
                    // (and apparently the rest of the HUD render too).
                    Object hpObj = tryInvoke(tgt, "method_6032", "getHealth");
                    Object maxHpObj = tryInvoke(tgt, "method_6063", "getMaxHealth");
                    boolean hasHp = (hpObj instanceof Number) && (maxHpObj instanceof Number);
                    float hp = hasHp ? ((Number) hpObj).floatValue() : 0f;
                    float maxHp = hasHp ? ((Number) maxHpObj).floatValue() : 0f;
                    double tx = firstNum(tgt, "method_23317", "getX").doubleValue();
                    double ty = firstNum(tgt, "method_23318", "getY").doubleValue();
                    double tz = firstNum(tgt, "method_23321", "getZ").doubleValue();
                    double px = firstNum(player, "method_23317", "getX").doubleValue();
                    double py = firstNum(player, "method_23318", "getY").doubleValue();
                    double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                    double dist = Math.sqrt(
                        (tx - px) * (tx - px)
                      + (ty - py) * (ty - py)
                      + (tz - pz) * (tz - pz));
                    String line;
                    if (hasHp) {
                        String hpCol = hpColor(hp, maxHp);
                        line = "§4Target §f" + n + " " + hpCol
                             + String.format("%.1f", hp) + "§8/§7"
                             + String.format("%.0f", maxHp)
                             + " §4dist §f" + String.format("%.1f", dist);
                    } else {
                        // Non-living target (item frame, painting, etc.):
                        // skip HP, just show name + distance.
                        line = "§4Target §f" + n + " §8(non-living) §4dist §f"
                             + String.format("%.1f", dist);
                    }
                    y = drawLine(dc, font, line, y);
                }
            } catch (Throwable t) { logOnce("TargetHud", t); }
        }
        if (player != null && modOn("ChunkInfo", false)) {
            try {
                double px = firstNum(player, "method_23317", "getX").doubleValue();
                double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                int cx = (int) Math.floor(px / 16);
                int cz = (int) Math.floor(pz / 16);
                int rx = cx >> 5;
                int rz = cz >> 5;
                int blockInChunkX = ((int) Math.floor(px) & 15);
                int blockInChunkZ = ((int) Math.floor(pz) & 15);
                y = drawLine(dc, font,
                    "§4Ch §f" + cx + ", " + cz
                    + " §8(b " + blockInChunkX + "," + blockInChunkZ + ")"
                    + " §4r §fr." + rx + "." + rz + ".mca", y);
            } catch (Throwable t) { logOnce("ChunkInfo", t); }
        }
        // HotbarLock: when ON, force the inventory's selected slot back to
        // the locked value every tick. The user keeps using the keyboard /
        // scroll wheel normally — vanilla MC processes the input client-side
        // — but we silently revert it here before the next packet goes out.
        // First-frame behaviour: capture the slot the user is currently on
        // and treat THAT as the lock target. Toggle the module off to
        // re-enable normal slot switching.
        if (player != null && modOn("HotbarLock", false)) {
            try {
                Object inv = tryInvoke(player, "method_31548", "getInventory");
                if (inv != null) {
                    Object curObj = tryInvoke(inv, "method_67532", "getSelectedSlot");
                    int cur = (curObj instanceof Number) ? ((Number) curObj).intValue() : -1;
                    if (hotbarLockSlot < 0 && cur >= 0 && cur < 9) {
                        hotbarLockSlot = cur;
                        flashToast("§e🔒 §fHotbar locked to slot §a" + (cur + 1));
                    }
                    if (hotbarLockSlot >= 0 && cur != hotbarLockSlot) {
                        for (Method m : inv.getClass().getMethods()) {
                            if (!"method_61496".equals(m.getName())) continue;
                            if (m.getParameterCount() != 1) continue;
                            if (m.getParameterTypes()[0] != int.class) continue;
                            try { m.invoke(inv, hotbarLockSlot); } catch (Throwable ignored) {}
                            break;
                        }
                    }
                }
            } catch (Throwable t) { logOnce("HotbarLock", t); }
        } else if (hotbarLockSlot >= 0) {
            // Module turned off — release the lock so future toggle-ons
            // capture the user's current slot again.
            hotbarLockSlot = -1;
        }
        if (player != null && modOn("AutoSprint", false)) {
            try {
                // Force sprint while moving forward and not eating/sneaking.
                // 87 = GLFW_KEY_W. We poll directly so this works when not in menu.
                boolean wDown = false;
                if (glfwGetKey != null && windowHandle > 0) {
                    wDown = (int) glfwGetKey.invoke(null, windowHandle, 87) == 1;
                }
                if (wDown) {
                    // setSprinting takes a boolean — cachedMethod only finds
                    // no-arg, so it always returned null and AutoSprint
                    // silently no-op'd. findMethodByName matches any-args.
                    Method ss = findMethodByName(player.getClass(), "method_5728");
                    if (ss == null) ss = findMethodByName(player.getClass(), "setSprinting");
                    if (ss != null) ss.invoke(player, true);
                }
            } catch (Throwable t) { logOnce("AutoSprint", t); }
        }
        // NoHurtCam: zero the player's hurtTime field every tick. The vanilla
        // camera-shake calculation in EntityRenderer reads this field and
        // tilts the camera by `hurtTime / maxHurtTime * 14°`. Setting it to 0
        // here suppresses the visual shake without affecting actual damage.
        // field_6235 = hurtTime (ticks remaining)
        // field_6254 = maxHurtTime — leave alone, only kill the live counter
        if (player != null && modOn("NoHurtCam", false)) {
            try {
                Field hurtField = cachedField(player.getClass(), "field_6235");
                if (hurtField == null) hurtField = cachedField(player.getClass(), "hurtTime");
                if (hurtField != null && hurtField.getType() == int.class) {
                    hurtField.setInt(player, 0);
                }
            } catch (Throwable t) { logOnce("NoHurtCam", t); }
        }
        // CombatTime: poll player health each tick. Falling delta = damage
        // taken event → mark timestamp. Display logic in renderHud.
        if (player != null && modOn("CombatTime", false)) {
            try {
                Object hpObj = tryInvoke(player, "method_6032", "getHealth");
                if (hpObj instanceof Number) {
                    float hp = ((Number) hpObj).floatValue();
                    if (combatLastHealth >= 0 && hp < combatLastHealth) {
                        combatLastDamageMs = System.currentTimeMillis();
                    }
                    combatLastHealth = hp;
                }
            } catch (Throwable t) { logOnce("CombatTime", t); }
        }
        // PearlCool: detect rising-edge of pearl-in-hand+rightclick. Vanilla
        // doesn't expose a pearl-throw event so we approximate by watching
        // for the combination of (offhand or mainhand has pearl) + (RMB
        // pressed). 1s vanilla cooldown after.
        if (player != null && modOn("PearlCool", false)) {
            try {
                Object main = tryInvoke(player, "method_6047", "getMainHandStack");
                Object off  = tryInvoke(player, "method_6079", "getOffHandStack");
                boolean hasPearl = stackIsPearl(main) || stackIsPearl(off);
                boolean rmb = mouseButtonDown(MB_RIGHT);
                // Rising edge: holding pearl + just pressed RMB → pearl thrown.
                if (hasPearl && rmb && !pearlPrevHolding) {
                    pearlCoolLastThrowMs = System.currentTimeMillis();
                }
                pearlPrevHolding = hasPearl && rmb;
            } catch (Throwable t) { logOnce("PearlCool", t); }
        }
        // AutoEat block removed — auto-action that gives unfair advantage.
        // AntiAFK: every ~25s, momentarily press sneak (or alternate keys)
        // so the server's AFK timer resets. Below most kick windows so it's
        // unobtrusive but enough to register.
        if (player != null && modOn("AntiAFK", false)) {
            long now = System.currentTimeMillis();
            if (now - antiAfkLastMs > cfgAntiAfkInterval * 1000L) {
                antiAfkLastMs = now;
                try { tickAntiAFK(); }
                catch (Throwable t) { logOnce("AntiAFK", t); }
            }
        }
        if (modOn("HotbarFade", false)) {
            try {
                // Track active slot. When it changes (or any inventory
                // interaction would change it), mark "active" timestamp.
                // The HotbarFadeMixin reads shouldHideHotbar() each frame
                // to decide whether to cancel vanilla hotbar rendering.
                Object inv = player != null
                    ? tryInvoke(player, "method_31548", "getInventory") : null;
                int curSlot = -1;
                if (inv != null) {
                    // method_67532 returns int (selected slot index)
                    // method_67533 returns class_2371 (inventory list — DON'T use here)
                    // Both intermediaries close together but unrelated, easy to mix up.
                    Object sel = tryInvoke(inv, "method_67532", "getSelectedSlot");
                    if (sel instanceof Number) curSlot = ((Number) sel).intValue();
                    else {
                        try {
                            // Direct field fallback — field_7545 is the int holding selectedSlot
                            Field sf = cachedField(inv.getClass(), "field_7545");
                            if (sf == null) sf = cachedField(inv.getClass(), "selectedSlot");
                            if (sf != null && sf.getType() == int.class)
                                curSlot = sf.getInt(inv);
                        } catch (Throwable ignored) {}
                    }
                }
                long now = System.currentTimeMillis();
                if (curSlot != lastSeenSlot) {
                    lastSeenSlot = curSlot;
                    lastHotbarChangeMs = now;
                }
            } catch (Throwable t) { logOnce("HotbarFade", t); }
        }
        if (modOn("TimerHud", false)) {
            try {
                long now = System.currentTimeMillis();
                if (timerStartMs == 0L) timerStartMs = now;
                long elapsed = (now - timerStartMs) + timerAccumulatedMs;
                long secs  = (elapsed / 1000) % 60;
                long mins  = (elapsed / 60_000) % 60;
                long hours = elapsed / 3_600_000;
                String t = hours > 0
                    ? String.format("%d:%02d:%02d", hours, mins, secs)
                    : String.format("%d:%02d", mins, secs);
                y = drawLine(dc, font, "§4Timer §f" + t, y);
            } catch (Throwable th) { logOnce("TimerHud", th); }
        } else if (timerStartMs != 0L) {
            // Module turned off — pause the running timer (preserve elapsed).
            timerAccumulatedMs += System.currentTimeMillis() - timerStartMs;
            timerStartMs = 0L;
        }
        // ----- New batch of modules ---------------------------------------
        if (modOn("DateTime", false)) {
            try {
                java.time.LocalDateTime now = java.time.LocalDateTime.now();
                String s = now.format(java.time.format.DateTimeFormatter.ofPattern("MMM d  HH:mm:ss"));
                y = drawLine(dc, font, "§4Date §f" + s, y);
            } catch (Throwable t) { logOnce("DateTime", t); }
        }
        if (modOn("Uptime", false)) {
            try {
                long up = (System.currentTimeMillis() - sessionStartMs) / 1000;
                long h = up / 3600, m = (up / 60) % 60, s = up % 60;
                String fmt = h > 0
                    ? String.format("%d:%02d:%02d", h, m, s)
                    : String.format("%02d:%02d", m, s);
                y = drawLine(dc, font, "§4Up §f" + fmt, y);
            } catch (Throwable t) { logOnce("Uptime", t); }
        }
        if (player != null && modOn("HeldDur", false)) {
            try {
                Object stack = tryInvoke(player, "method_6047", "getMainHandStack");
                String line = "§4Dur §8(no item)";
                if (stack != null) {
                    Object empty = tryInvoke(stack, "method_7960", "isEmpty");
                    if (!Boolean.TRUE.equals(empty)) {
                        Object dmg = tryInvoke(stack, "method_7919", "getDamage");
                        Object max = tryInvoke(stack, "method_7936", "getMaxDamage");
                        if (dmg instanceof Number && max instanceof Number
                            && ((Number) max).intValue() > 0) {
                            int d = ((Number) dmg).intValue();
                            int mx = ((Number) max).intValue();
                            int remaining = mx - d;
                            int pct = (int) (100.0 * remaining / mx);
                            String col = pct >= 50 ? "§a" : pct >= 20 ? "§e" : "§c";
                            line = "§4Dur " + col + remaining + "§4/§f" + mx
                                 + " §8(" + col + pct + "%§8)";
                        } else {
                            line = "§4Dur §8(non-damageable)";
                        }
                    }
                }
                y = drawLine(dc, font, line, y);
            } catch (Throwable t) { logOnce("HeldDur", t); }
        }
        if (player != null && modOn("EnchantList", false)) {
            try {
                y = renderEnchantList(player, dc, font, y);
            } catch (Throwable t) { logOnce("EnchantList", t); }
        }
        if (player != null && modOn("PotionTimer", false)) {
            try {
                Object effs = tryInvoke(player, "method_6026", "getStatusEffects");
                if (effs instanceof Iterable) {
                    int shown = 0;
                    for (Object inst : (Iterable<?>) effs) {
                        if (shown >= 4) break;   // top 4, stay compact
                        Object dur = tryInvoke(inst, "method_5584", "getDuration");
                        Object amp = tryInvoke(inst, "method_5578", "getAmplifier");
                        // Use the existing helper that handles the
                        // RegistryEntry → StatusEffect → translation-key chain.
                        // Falls back gracefully to "?" if any step nulls out.
                        String n = statusEffectName(inst);
                        if (n == null || n.isEmpty()) n = "Effect";
                        // Strip "effect.minecraft." or "effect." prefix if present
                        if (n.startsWith("effect.minecraft.")) n = n.substring(17);
                        else if (n.startsWith("effect.")) n = n.substring(7);
                        if (n.length() > 9) n = n.substring(0, 8) + "…";
                        int amplifier = amp instanceof Number ? ((Number) amp).intValue() : 0;
                        int duration  = dur instanceof Number ? ((Number) dur).intValue() : 0;
                        int secs = Math.max(0, duration / 20);
                        String col = secs > 60 ? "§a" : secs > 20 ? "§e" : "§c";
                        String mm = String.format("%d:%02d", secs / 60, secs % 60);
                        y = drawLine(dc, font, "§4PE §f" + n
                            + (amplifier > 0 ? " §7" + (amplifier + 1) : "")
                            + " " + col + mm, y);
                        shown++;
                    }
                    // If module is enabled but there are NO active effects,
                    // surface a "no effects active" placeholder so the user
                    // knows the module is on (instead of thinking it's broken).
                    if (shown == 0) {
                        y = drawLine(dc, font, "§4PE §8(no active effects)", y);
                    }
                }
            } catch (Throwable t) { logOnce("PotionTimer", t); }
        }
        if (player != null && modOn("InGameTime", false)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    // method_8532 = World.getTimeOfDay(); 0..23999 ticks per day.
                    Object tod = tryInvoke(world, "method_8532", "getTimeOfDay");
                    if (tod instanceof Number) {
                        long ticks = ((Number) tod).longValue() % 24000L;
                        if (ticks < 0) ticks += 24000L;
                        // MC's tick 0 = 6:00 AM. 1 in-game day = 20 min real-
                        // time = 24000 ticks, so 1000 ticks = 1 hour.
                        long minsSinceDawn = ticks * 60L / 1000L;
                        long minsSinceMidnight = (minsSinceDawn + 6L * 60L) % (24L * 60L);
                        long hh = minsSinceMidnight / 60, mm = minsSinceMidnight % 60;
                        boolean isDay = ticks < 12000;
                        String icon = isDay ? "§e☀" : "§9☾";
                        y = drawLine(dc, font, "§4Time " + icon + " §f"
                            + String.format("%02d:%02d", hh, mm), y);
                    }
                }
            } catch (Throwable t) { logOnce("InGameTime", t); }
        }
        if (player != null && modOn("StepCounter", false)) {
            try {
                double cx = firstNum(player, "method_23317", "getX").doubleValue();
                double cz = firstNum(player, "method_23321", "getZ").doubleValue();
                if (!Double.isNaN(stepCounterPrevX)) {
                    double dx = cx - stepCounterPrevX, dz = cz - stepCounterPrevZ;
                    double d = Math.sqrt(dx*dx + dz*dz);
                    // Cap per-frame delta to filter teleports / portal jumps
                    if (d < 8.0) stepCounterDist += d;
                }
                stepCounterPrevX = cx; stepCounterPrevZ = cz;
                String label;
                if (stepCounterDist >= 1000) label = String.format("%.2fk", stepCounterDist / 1000.0);
                else                          label = String.format("%.0f",   stepCounterDist);
                y = drawLine(dc, font, "§4Steps §f" + label + "§4 blk", y);
            } catch (Throwable t) { logOnce("StepCounter", t); }
        }
        if (modOn("WaterMark", false)) {
            try {
                String mainTag = "§l§cSHADOW§f CLIENT §8v1.0";
                String credit  = "§7made by §f§lEdison";
                int sw = screenWidth(dc), sh = screenHeight(dc);
                if (drawTextMethod != null) {
                    // Two-line watermark in the bottom-right corner.
                    // Top line: brand. Bottom line: credit.
                    drawTextMethod.invoke(dc, font, mainTag,
                        sw - 130, sh - 22, 0xFFFFFFFF, true);
                    drawTextMethod.invoke(dc, font, credit,
                        sw - 80, sh - 12, 0xFFFFFFFF, true);
                }
            } catch (Throwable t) { logOnce("WaterMark", t); }
        }
        if (player != null && modOn("Dimension", false)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                Object dim = tryInvoke(world, "method_27983", "getRegistryKey",
                    "getDimensionKey", "getDimension");
                Object id  = dim != null ? tryInvoke(dim, "method_29177", "getValue",
                    "method_43903", "getRegistry") : null;
                String name = id != null ? id.toString() : "?";
                // Strip "minecraft:" prefix and "ResourceKey[..." wrapper noise
                int slash = name.lastIndexOf('/');
                if (slash >= 0) name = name.substring(slash + 1);
                int colon = name.indexOf(':');
                if (colon >= 0) name = name.substring(colon + 1);
                int paren = name.indexOf(']');
                if (paren >= 0) name = name.substring(0, paren);
                String col = name.contains("nether") ? "§c"
                           : name.contains("end")    ? "§5"
                           : name.contains("over")   ? "§a" : "§b";
                y = drawLine(dc, font, "§4Dim " + col + name, y);
            } catch (Throwable t) { logOnce("Dimension", t); }
        }
        if (player != null && modOn("Air", false)) {
            try {
                Object air = tryInvoke(player, "method_5669", "getAir");
                Object maxAir = tryInvoke(player, "method_5748", "getMaxAir");
                if (air instanceof Number && maxAir instanceof Number) {
                    int a = ((Number) air).intValue();
                    int m = ((Number) maxAir).intValue();
                    if (a < m) {
                        // Convert ticks to seconds (20 ticks/second)
                        int secs = Math.max(0, a / 20);
                        String col = a > m / 2 ? "§b" : a > m / 4 ? "§e" : "§c";
                        int bubbles = Math.max(0, (a * 10 + m / 2) / m);
                        StringBuilder b = new StringBuilder("§4Air " + col);
                        for (int i = 0; i < bubbles; i++) b.append("●");
                        for (int i = bubbles; i < 10; i++) b.append("§8○" + col);
                        b.append(" §f").append(secs).append("s");
                        y = drawLine(dc, font, b.toString(), y);
                    }
                }
            } catch (Throwable t) { logOnce("Air", t); }
        }
        if (player != null && modOn("AttackDmg", false)) {
            try {
                Object stack = tryInvoke(player, "method_6047", "getMainHandStack");
                String id = getItemId(stack);
                double dmg = 1.0;     // bare-fist baseline
                String type = "fist";
                if (id.contains("sword")) {
                    type = "sword";
                    if (id.contains("netherite")) dmg = 8.0;
                    else if (id.contains("diamond")) dmg = 7.0;
                    else if (id.contains("iron"))    dmg = 6.0;
                    else if (id.contains("stone"))   dmg = 5.0;
                    else if (id.contains("gold"))    dmg = 4.0;
                    else                             dmg = 4.0;
                } else if (id.contains("axe") && !id.contains("pickaxe")) {
                    type = "axe";
                    if (id.contains("netherite")) dmg = 10.0;
                    else if (id.contains("diamond")) dmg = 9.0;
                    else if (id.contains("iron"))    dmg = 9.0;
                    else if (id.contains("stone"))   dmg = 9.0;
                    else if (id.contains("gold"))    dmg = 7.0;
                    else                             dmg = 7.0;
                } else if (id.contains("trident")) {
                    type = "trident"; dmg = 9.0;
                }
                String col = dmg >= 8 ? "§c" : dmg >= 6 ? "§e" : dmg >= 4 ? "§a" : "§7";
                y = drawLine(dc, font, "§4Atk " + col + String.format("%.1f", dmg)
                    + "§4 ♥ §8(" + type + ")", y);
            } catch (Throwable t) { logOnce("AttackDmg", t); }
        }
        if (player != null && modOn("ArmorPts", false)) {
            try {
                // 1.21.11: ArmorItem (class_1738) is gone — every armor
                // piece is now generic class_1792 Item, distinguished by
                // its registry ID. Scan the full inventory and identify
                // armor by `Item.toString()` containing helmet/chestplate
                // /leggings/boots, then derive material tier the same way.
                Object inv = tryInvoke(player, "method_31548", "getInventory");
                int totalPts = 0, piecesFound = 0;
                if (inv != null) {
                    Object allList = tryInvoke(inv, "method_67533");
                    if (allList instanceof Iterable) {
                        for (Object piece : (Iterable<?>) allList) {
                            if (!isArmorStack(piece)) continue;
                            String id = getItemId(piece);
                            totalPts += armorMaterialPts(id);
                            piecesFound++;
                        }
                    }
                }
                String col = totalPts >= 16 ? "§a"
                           : totalPts >=  8 ? "§e" : "§c";
                y = drawLine(dc, font, "§4Arm " + col + totalPts + "§4/§f20 §8(" + piecesFound + " pcs)", y);
            } catch (Throwable t) { logOnce("ArmorPts", t); }
        }
        if (player != null && modOn("MoonPhase", false)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    // 1.21.11: World.getMoonPhase (method_30273) is gone.
                    // Derive from getTimeOfDay: phase = (timeOfDay / 24000) % 8.
                    // Same calculation MC's vanilla code does internally.
                    Object tod = tryInvoke(world, "method_8532", "getTimeOfDay");
                    if (tod instanceof Number) {
                        long t = ((Number) tod).longValue();
                        int p = (int) Math.floorMod(t / 24000L, 8L);
                        // 0=full, 1=waning gib, 2=last qtr, 3=waning crescent,
                        // 4=new, 5=waxing crescent, 6=first qtr, 7=waxing gib
                        String[] names = {"Full", "WaningGib", "LastQtr", "WaningCres",
                                          "New",  "WaxingCres", "FirstQtr","WaxingGib"};
                        String[] icons = {"●", "◗", "◐", "◖", "○", "◐", "◑", "◗"};
                        String n = names[p];
                        String i = icons[p];
                        y = drawLine(dc, font, "§4Moon §f" + i + " §7" + n, y);
                    } else {
                        y = drawLine(dc, font, "§4Moon §8(no world time)", y);
                    }
                }
            } catch (Throwable t) { logOnce("MoonPhase", t); }
        }
        if (player != null && modOn("CompassExt", false)) {
            try {
                float yaw = firstNum(player, "method_36454", "getYaw").floatValue();
                yaw = ((yaw % 360) + 360) % 360;
                // 16-point compass — NNE/ENE/ESE/etc.
                String[] points = {
                    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
                    "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
                };
                int idx = (int) Math.floor(((yaw + 11.25f) / 22.5f)) % 16;
                if (idx < 0) idx += 16;
                String dir = points[idx];
                String col = dir.startsWith("N") && dir.length() == 1 ? "§a"
                           : dir.startsWith("S") && dir.length() == 1 ? "§e"
                           : "§b";
                y = drawLine(dc, font, "§4Comp " + col + dir
                    + " §8(" + String.format("%.0f°", yaw) + ")", y);
            } catch (Throwable t) { logOnce("CompassExt", t); }
        }
        // Detect rising LMB + targetedEntity ONCE per frame so HitMarker and
        // HitSounds can both react without double-polling the input/cachedField.
        boolean lmbThisFrame = false;
        boolean lmbHitEntity = false;
        if (player != null && (modOn("HitMarker", false)
                            || modOn("HitSounds", false))) {
            lmbThisFrame = mouseButtonDown(MB_LEFT);
            if (lmbThisFrame && !hitMarkerPrevLmb) {
                try {
                    Field tef = cachedField(mc.getClass(), "field_1692");
                    if (tef == null) tef = cachedField(mc.getClass(), "targetedEntity");
                    Object tgt = tef != null ? tef.get(mc) : null;
                    lmbHitEntity = (tgt != null);
                } catch (Throwable ignored) {}
            }
            hitMarkerPrevLmb = lmbThisFrame;
        }
        if (player != null && modOn("HitSounds", false) && lmbHitEntity) {
            try { playHitSound(); } catch (Throwable t) { logOnce("HitSounds", t); }
        }
        if (player != null && modOn("HitMarker", false)) {
            try {
                // Visual ✕ marker at screen center for ~250 ms after a hit.
                if (lmbHitEntity) hitMarkerAtMs = System.currentTimeMillis();
                long since = System.currentTimeMillis() - hitMarkerAtMs;
                if (since >= 0 && since <= 250L && fillMethod != null) {
                    int sw = screenWidth(dc), sh = screenHeight(dc);
                    int cxs = sw / 2, cys = sh / 2;
                    int alpha = (int) (255 * (1.0 - since / 250.0));
                    if (alpha < 0) alpha = 0; if (alpha > 255) alpha = 255;
                    int color = (alpha << 24) | 0xFF3030;
                    // Draw a 12-px ✕ via two diagonals (4 thin rectangles each leg)
                    for (int i = -6; i <= 6; i++) {
                        fillMethod.invoke(null, dc, cxs + i, cys + i,
                            cxs + i + 1, cys + i + 1, color);
                        fillMethod.invoke(null, dc, cxs + i, cys - i,
                            cxs + i + 1, cys - i + 1, color);
                    }
                }
            } catch (Throwable t) { logOnce("HitMarker", t); }
        }
        if (player != null && modOn("JumpCount", false)) {
            try {
                Object og = tryInvoke(player, "method_24828", "isOnGround", "onGround");
                boolean onGround = Boolean.TRUE.equals(og);
                // Falling-edge: was on-ground last frame, now isn't → jump.
                if (jumpCountPrevOnGround && !onGround) {
                    // Only count "real" jumps, not falls — check vertical velocity > 0
                    Object vel = tryInvoke(player, "method_18798", "getVelocity");
                    double vy = 0.0;
                    if (vel != null) {
                        // class_243 (Vec3d) has public fields field_1352 (x)
                        // and field_1351 (y). NO public "y" — old getField("y")
                        // always threw NoSuchFieldException → vy stayed 0 →
                        // every airborne moment counted as a fall, not jump.
                        try { vy = firstNum(vel, "field_1351", "y").doubleValue(); }
                        catch (Throwable ignored) {}
                    }
                    if (vy > 0.05) jumpCountSession++;
                }
                jumpCountPrevOnGround = onGround;
                y = drawLine(dc, font, "§4Jumps §f" + jumpCountSession, y);
            } catch (Throwable t) { logOnce("JumpCount", t); }
        }
        if (modOn("WelcomeMsg", false)) {
            try {
                String addr = "";
                Object srv = tryInvoke(mc, "method_1558", "getCurrentServerEntry");
                if (srv != null) {
                    String a = readStringField(srv, "field_3761", "address");
                    if (a != null) addr = a;
                }
                if (addr.isEmpty()) addr = "world";
                if (!addr.equals(welcomeMsgLastServer)) {
                    welcomeMsgLastServer = addr;
                    // method_1566 returns class_374 (Toast manager), NOT
                    // InGameHud — the chat-message inject silently failed.
                    // The InGameHud is the public field mc.field_1705.
                    Object hud = null;
                    Field hudField = cachedField(mc.getClass(), "field_1705");
                    if (hudField == null) hudField = cachedField(mc.getClass(), "inGameHud");
                    if (hudField != null) hud = hudField.get(mc);
                    Object chat = hud != null
                        ? tryInvoke(hud, "method_1743", "getChatHud") : null;
                    if (chat != null) {
                        String greet = "§l§cShadow Client§r§7 — §floaded for §a"
                            + addr + "§r §7(by §f§lEdison§r§7)";
                        // Try addMessage(Text) — build a simple Text via class_2561.method_30163("string")
                        try {
                            Class<?> textCls = Class.forName("net.minecraft.class_2561");
                            Method literal = null;
                            for (Method m : textCls.getMethods()) {
                                if (!Modifier.isStatic(m.getModifiers())) continue;
                                if (m.getParameterCount() != 1) continue;
                                if (m.getParameterTypes()[0] != String.class) continue;
                                String n = m.getName();
                                if (n.equals("method_30163") || n.equals("literal")) {
                                    literal = m; break;
                                }
                            }
                            if (literal != null) {
                                Object txt = literal.invoke(null, greet);
                                for (Method m : chat.getClass().getMethods()) {
                                    if (m.getParameterCount() != 1) continue;
                                    String n = m.getName();
                                    if ((n.equals("method_1812") || n.equals("addMessage"))
                                        && textCls.isAssignableFrom(m.getParameterTypes()[0])) {
                                        m.invoke(chat, txt);
                                        break;
                                    }
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable t) { logOnce("WelcomeMsg", t); }
        }
        // AutoTotem block removed — auto-action / PvP advantage. Use
        // TotemCount instead for a passive readout.
        // === Item count modules — count specific items across the full
        //     inventory (main + offhand + hotbar). All four use the same
        //     pattern: walk inventory list, sum counts of stacks whose
        //     registry id matches the target prefix(es). Cheap — single pass.
        if (player != null && (
                modOn("TotemCount", false) ||
                modOn("PearlCount", false) ||
                modOn("ArrowCount", false) ||
                modOn("GoldCount",  false))) {
            try {
                int totems = 0, pearls = 0, arrows = 0, gold = 0;
                Object inv = tryInvoke(player, "method_31548", "getInventory");
                Object listObj = inv != null ? tryInvoke(inv, "method_67533") : null;
                if (listObj instanceof Iterable) {
                    for (Object s : (Iterable<?>) listObj) {
                        if (s == null) continue;
                        Object empty = tryInvoke(s, "method_7960", "isEmpty");
                        if (Boolean.TRUE.equals(empty)) continue;
                        String id = getItemId(s);
                        Object cntObj = tryInvoke(s, "method_7947", "getCount");
                        int cnt = cntObj instanceof Number ? ((Number) cntObj).intValue() : 0;
                        if (id.contains("totem_of_undying")) totems += cnt;
                        if (id.contains("ender_pearl"))      pearls += cnt;
                        if (id.contains("arrow"))            arrows += cnt;
                        // Gold: ingots + nuggets + blocks. Each unit converts
                        // to display nuggets so the user sees a true total
                        // count (1 ingot = 9 nuggets, 1 block = 81 nuggets).
                        if (id.endsWith(":gold_ingot"))         gold += cnt * 9;
                        else if (id.endsWith(":gold_nugget"))   gold += cnt;
                        else if (id.endsWith(":gold_block"))    gold += cnt * 81;
                    }
                }
                // Also check offhand explicitly (some impls don't include it)
                Object off = tryInvoke(player, "method_6079", "getOffHandStack");
                if (off != null && !Boolean.TRUE.equals(tryInvoke(off, "method_7960"))) {
                    String id = getItemId(off);
                    Object cntObj = tryInvoke(off, "method_7947", "getCount");
                    int cnt = cntObj instanceof Number ? ((Number) cntObj).intValue() : 0;
                    // Don't double-count main inventory entries — only add if
                    // method_67533 doesn't include offhand. Modern impls do,
                    // but defensive: only count if listObj is null.
                    if (listObj == null) {
                        if (id.contains("totem_of_undying")) totems += cnt;
                        if (id.contains("ender_pearl"))      pearls += cnt;
                        if (id.contains("arrow"))            arrows += cnt;
                    }
                }
                if (modOn("TotemCount", false)) {
                    String col = totems == 0 ? "§c" : totems < 3 ? "§e" : "§a";
                    y = drawLine(dc, font, "§4Totems " + col + totems, y);
                }
                if (modOn("PearlCount", false)) {
                    String col = pearls == 0 ? "§c" : pearls < 4 ? "§e" : "§a";
                    y = drawLine(dc, font, "§4Pearls " + col + pearls, y);
                }
                if (modOn("ArrowCount", false)) {
                    String col = arrows == 0 ? "§c" : arrows < 32 ? "§e" : "§a";
                    y = drawLine(dc, font, "§4Arrows " + col + arrows, y);
                }
                if (modOn("GoldCount", false)) {
                    // Display as ingots + remaining nuggets for readability
                    int ingots = gold / 9;
                    int rem    = gold % 9;
                    String disp = ingots > 0
                        ? (rem > 0 ? ingots + "i§8+§f" + rem + "n" : ingots + "i")
                        : (rem + "n");
                    y = drawLine(dc, font, "§6Gold §f" + disp + " §8(" + gold + "n)", y);
                }
            } catch (Throwable t) { logOnce("ItemCount", t); }
        }
        if (player != null && modOn("FireTimer", false)) {
            try {
                // 1.21.11: getFireTicks() is method_20802 on class_1297.
                // (method_20801 was the setter — kept as fallback in case
                // the mapping shifts between minor versions.)
                Number ft = firstNum(player, "method_20802", "method_20801",
                                              "getFireTicks", "getRemainingFireTicks");
                int ticks = ft != null ? ft.intValue() : 0;
                if (ticks > 0) {
                    double secs = ticks / 20.0;
                    String col = ticks > 60 ? "§c" : ticks > 20 ? "§6" : "§e";
                    y = drawLine(dc, font, String.format("§4Fire %s%.1fs", col, secs), y);
                }
            } catch (Throwable t) { logOnce("FireTimer", t); }
        }
        if (player != null && modOn("FreezeTimer", false)) {
            try {
                Number ft = firstNum(player, "method_32312", "getFrozenTicks");
                int ticks = ft != null ? ft.intValue() : 0;
                if (ticks > 0) {
                    Number maxObj = firstNum(player, "method_32313", "getMinFreezeDamageTicks");
                    int max = maxObj != null ? maxObj.intValue() : 140;
                    if (max <= 0) max = 140;
                    int pct = Math.min(100, (ticks * 100) / max);
                    String col = pct < 33 ? "§b" : pct < 66 ? "§e" : "§c";
                    int filled = pct / 10;
                    StringBuilder bar = new StringBuilder("§4Freeze ").append(col);
                    for (int i = 0; i < filled; i++) bar.append("█");
                    bar.append("§8");
                    for (int i = filled; i < 10; i++) bar.append("█");
                    bar.append(" ").append(col).append(pct).append("%");
                    y = drawLine(dc, font, bar.toString(), y);
                }
            } catch (Throwable t) { logOnce("FreezeTimer", t); }
        }
        if (modOn("ServerBrand", false)) {
            try {
                Object net = tryInvoke(mc, "method_1562", "getNetworkHandler");
                if (net != null) {
                    // 1.21.x moved getBrand to ClientCommonNetworkHandler
                    // (parent of class_634): method_52790().
                    Object brand = tryInvoke(net, "method_52790", "method_2872",
                                                   "getBrand", "getServerBrand");
                    String br = brand != null ? brand.toString() : null;
                    if (br == null || br.isEmpty()) br = "vanilla";
                    String col = br.toLowerCase().contains("paper") ? "§a"
                               : br.toLowerCase().contains("spigot") ? "§e"
                               : br.toLowerCase().contains("fabric") ? "§b"
                               : br.toLowerCase().contains("forge")  ? "§d"
                               : "§7";
                    y = drawLine(dc, font, "§4Brand " + col + br, y);
                }
            } catch (Throwable t) { logOnce("ServerBrand", t); }
        }
        if (player != null && modOn("XpToNext", false)) {
            try {
                int level = firstNum(player, "field_7520", "experienceLevel").intValue();
                float prog = firstNum(player, "field_7510", "experienceProgress").floatValue();
                // Cost of next level (vanilla formula)
                int total = level >= 31 ? (9 * level - 158)
                          : level >= 16 ? (5 * level - 38)
                          :               (2 * level + 7);
                int remaining = (int) Math.ceil(total * (1.0 - prog));
                String col = remaining < 5 ? "§a" : remaining < 20 ? "§e" : "§7";
                y = drawLine(dc, font, "§4XpNext " + col + remaining + " §8(L" + level + ")", y);
            } catch (Throwable t) { logOnce("XpToNext", t); }
        }
        if (player != null && modOn("PortalTimer", false)) {
            try {
                // Entity.field_6018 is the private int portalTime in 1.21.11.
                // No public getter exists in this version; method_5687 was
                // removed and field_6017 is `lastRenderX` (double, would
                // crash a Number cast). Field access only.
                Number pt = firstNum(player, "field_6018", "getPortalTime");
                int ticks = pt != null ? pt.intValue() : 0;
                if (ticks > 0) {
                    int pct = Math.min(100, (ticks * 100) / 80);
                    String col = pct < 50 ? "§b" : pct < 90 ? "§5" : "§d";
                    y = drawLine(dc, font, "§4Portal " + col + ticks + "/80 §8(" + pct + "%)", y);
                }
            } catch (Throwable t) { logOnce("PortalTimer", t); }
        }
        if (player != null && modOn("HungerBar", false)) {
            try {
                Object hm = tryInvoke(player, "method_7344", "getHungerManager");
                if (hm != null) {
                    Number f = firstNum(hm, "method_7586", "getFoodLevel");
                    Number s = firstNum(hm, "method_7589", "getSaturationLevel");
                    int food = f != null ? f.intValue() : 0;
                    double sat = s != null ? s.doubleValue() : 0.0;
                    String col = food >= 18 ? "§a" : food >= 12 ? "§e" : food >= 6 ? "§6" : "§c";
                    y = drawLine(dc, font, String.format("§4Food %s%d§8/§720 §8sat §f%.1f",
                                                          col, food, sat), y);
                }
            } catch (Throwable t) { logOnce("HungerBar", t); }
        }
        if (player != null && modOn("BedDist", false)) {
            try {
                // 1.21.11 reshaped the spawn-point API: no direct
                // getSpawnPointPosition() on PlayerEntity. The closest hook
                // is method_42272 which returns Optional<Respawn>. We try
                // a chain of candidates, then dig out a BlockPos by walking
                // the result object's fields if needed.
                Object spawnObj = tryInvoke(player, "method_42272",
                                                    "method_26280",
                                                    "getSpawnPointPosition",
                                                    "getBedPosition");
                // Unwrap Optional if that's what we got
                if (spawnObj instanceof java.util.Optional) {
                    java.util.Optional<?> o = (java.util.Optional<?>) spawnObj;
                    spawnObj = o.orElse(null);
                }
                Object spawnPos = null;
                if (spawnObj != null) {
                    // If it's already a BlockPos-like, use it directly
                    if (spawnObj.getClass().getName().endsWith("class_2338")) {
                        spawnPos = spawnObj;
                    } else {
                        // Walk fields/methods to find a BlockPos
                        Object bp = tryInvoke(spawnObj, "method_29459",
                                                         "method_19443",
                                                         "pos", "blockPos", "getPos");
                        if (bp != null) spawnPos = bp;
                        // Last resort: scan public/declared fields
                        if (spawnPos == null) {
                            for (Field f : spawnObj.getClass().getDeclaredFields()) {
                                try {
                                    f.setAccessible(true);
                                    Object v = f.get(spawnObj);
                                    if (v != null && v.getClass().getName().endsWith("class_2338")) {
                                        spawnPos = v; break;
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
                if (spawnPos != null) {
                    Number sx = firstNum(spawnPos, "method_10263", "getX");
                    Number sy = firstNum(spawnPos, "method_10264", "getY");
                    Number sz = firstNum(spawnPos, "method_10260", "getZ");
                    if (sx != null && sy != null && sz != null) {
                        double px = firstNum(player, "method_23317", "getX").doubleValue();
                        double py = firstNum(player, "method_23318", "getY").doubleValue();
                        double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                        double dx = sx.doubleValue() - px;
                        double dy = sy.doubleValue() - py;
                        double dz = sz.doubleValue() - pz;
                        double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                        y = drawLine(dc, font, String.format("§4Bed §f%.0fb §8(%d %d %d)",
                            dist, sx.intValue(), sy.intValue(), sz.intValue()), y);
                    }
                }
            } catch (Throwable t) { logOnce("BedDist", t); }
        }
        if (player != null && modOn("InvDurAvg", false)) {
            try {
                int sumPct = 0, count = 0, lowest = 100;
                Object inv = tryInvoke(player, "method_31548", "getInventory");
                Object listObj = inv != null ? tryInvoke(inv, "method_67533") : null;
                if (listObj instanceof Iterable) {
                    for (Object s : (Iterable<?>) listObj) {
                        if (s == null) continue;
                        Object empty = tryInvoke(s, "method_7960", "isEmpty");
                        if (Boolean.TRUE.equals(empty)) continue;
                        Object dmgable = tryInvoke(s, "method_7986", "isDamageable");
                        if (!Boolean.TRUE.equals(dmgable)) continue;
                        Number maxN = firstNum(s, "method_7936", "getMaxDamage");
                        Number dmgN = firstNum(s, "method_7919", "getDamage");
                        if (maxN == null || maxN.intValue() <= 0) continue;
                        int max = maxN.intValue();
                        int dmg = dmgN != null ? dmgN.intValue() : 0;
                        int pct = Math.max(0, 100 * (max - dmg) / max);
                        sumPct += pct; count++;
                        if (pct < lowest) lowest = pct;
                    }
                }
                if (count > 0) {
                    int avg = sumPct / count;
                    String col = avg < 20 ? "§c" : avg < 50 ? "§e" : "§a";
                    String lcol = lowest < 20 ? "§c" : lowest < 50 ? "§e" : "§a";
                    y = drawLine(dc, font, "§4Gear " + col + avg + "% §8avg §8| low " + lcol + lowest + "%", y);
                }
            } catch (Throwable t) { logOnce("InvDurAvg", t); }
        }
        if (player != null && modOn("YBest", false)) {
            try {
                double yy = firstNum(player, "method_23318", "getY").doubleValue();
                if (yy < sessionMinY) sessionMinY = yy;
                double cur = sessionMinY == Double.POSITIVE_INFINITY ? yy : sessionMinY;
                String col = cur < 0 ? "§a" : cur < 16 ? "§e" : "§7";
                y = drawLine(dc, font, String.format("§4Y§7best %s%.0f", col, cur), y);
            } catch (Throwable t) { logOnce("YBest", t); }
        }
        if (player != null && modOn("AbsorptionHp", false)) {
            try {
                Number abs = firstNum(player, "method_6067", "getAbsorptionAmount");
                float v = abs != null ? abs.floatValue() : 0f;
                if (v > 0) {
                    int hearts = (int) Math.ceil(v / 2.0);
                    StringBuilder b = new StringBuilder("§4Abs §6");
                    for (int i = 0; i < Math.min(hearts, 10); i++) b.append("❤");
                    if (hearts > 10) b.append(" §8+§6").append(hearts - 10);
                    b.append(" §f").append(String.format("%.1f", v));
                    y = drawLine(dc, font, b.toString(), y);
                }
            } catch (Throwable t) { logOnce("AbsorptionHp", t); }
        }
        if (player != null && modOn("TopSpeed", false)) {
            try {
                Object vel = tryInvoke(player, "method_18798", "getVelocity");
                if (vel != null) {
                    double vx = firstNum(vel, "field_1352", "x").doubleValue();
                    double vz = firstNum(vel, "field_1350", "z").doubleValue();
                    double bps = Math.sqrt(vx*vx + vz*vz) * 20.0;
                    if (bps > sessionTopSpeed) sessionTopSpeed = bps;
                    String col = sessionTopSpeed > 8 ? "§a" : sessionTopSpeed > 4 ? "§e" : "§7";
                    y = drawLine(dc, font, String.format("§4Top §f%.2fb/s %s(now %.1f)",
                                                          sessionTopSpeed, col, bps), y);
                }
            } catch (Throwable t) { logOnce("TopSpeed", t); }
        }
        if (player != null && modOn("LookYaw", false)) {
            try {
                float yaw = firstNum(player, "method_36454", "getYRot", "getYaw").floatValue();
                float pitch = firstNum(player, "method_36455", "getXRot", "getPitch").floatValue();
                // Normalize yaw to -180..180
                yaw = ((yaw % 360) + 540) % 360 - 180;
                String pcol = pitch > 60 ? "§c" : pitch < -60 ? "§b" : "§7";
                y = drawLine(dc, font, String.format("§4Yaw §f%.3f §8| §4Pitch %s%.3f",
                                                      yaw, pcol, pitch), y);
            } catch (Throwable t) { logOnce("LookYaw", t); }
        }
        if (player != null && modOn("VoidWarn", false)) {
            try {
                double yy = firstNum(player, "method_23318", "getY").doubleValue();
                if (yy < 5.0) {
                    String col = yy < -10 ? "§c§l" : yy < 0 ? "§c" : "§e";
                    y = drawLine(dc, font, String.format("§4VOID %s%.1f §8below §fY=5", col, yy), y);
                    long now = System.currentTimeMillis();
                    if (now - voidWarnLastBeep > 1500) {
                        voidWarnLastBeep = now;
                        try { java.awt.Toolkit.getDefaultToolkit().beep(); } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable t) { logOnce("VoidWarn", t); }
        }
        if (player != null && modOn("DirectionWord", false)) {
            try {
                float yaw = firstNum(player, "method_36454", "getYRot", "getYaw").floatValue();
                yaw = ((yaw % 360) + 540) % 360 - 180;
                // 0 = south, 90 = west, 180 = north, -90 = east in MC convention
                String[] words = {"south","southwest","west","northwest","north","northeast","east","southeast"};
                int idx = (int) Math.floor((yaw + 180 + 22.5) / 45.0) % 8;
                if (idx < 0) idx += 8;
                y = drawLine(dc, font, "§4Facing §f" + words[idx], y);
            } catch (Throwable t) { logOnce("DirectionWord", t); }
        }
        if (player != null && modOn("PortalCoords", false)) {
            try {
                double px = firstNum(player, "method_23317", "getX").doubleValue();
                double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                Object world = worldField != null ? worldField.get(mc) : null;
                String dim = "overworld";
                if (world != null) {
                    Object dimObj = tryInvoke(world, "method_27983", "getDimensionKey",
                                                     "getRegistryKey");
                    if (dimObj != null) {
                        Object id = tryInvoke(dimObj, "method_29177", "getValue", "getRegistryName");
                        if (id != null) dim = id.toString();
                    }
                }
                if (dim.contains("nether")) {
                    int nx = (int) px, nz = (int) pz;
                    int ox = nx * 8, oz = nz * 8;
                    y = drawLine(dc, font, "§4Portal §8nether " + nx + "," + nz
                                          + " §8→ §foverworld " + ox + "," + oz, y);
                } else {
                    int ox = (int) px, oz = (int) pz;
                    int nx = ox / 8, nz = oz / 8;
                    y = drawLine(dc, font, "§4Portal §8overworld " + ox + "," + oz
                                          + " §8→ §fnether " + nx + "," + nz, y);
                }
            } catch (Throwable t) { logOnce("PortalCoords", t); }
        }
        if (player != null && modOn("HotbarItems", false)) {
            try {
                Object inv = tryInvoke(player, "method_31548", "getInventory");
                if (inv != null) {
                    Object selObj = tryInvoke(inv, "method_67532", "getSelectedSlot");
                    int sel = selObj instanceof Number ? ((Number) selObj).intValue() : 0;
                    // Resolve int-arg getStack(int) once
                    Method getStack = null;
                    for (String n : new String[]{"method_5438", "getStack"}) {
                        try { getStack = inv.getClass().getMethod(n, int.class); break; }
                        catch (NoSuchMethodException ignored) {}
                    }
                    if (getStack == null) {
                        // fall back to iterating getAllItems() for first 9 entries
                        Object listObj = tryInvoke(inv, "method_67533");
                        if (listObj instanceof Iterable) {
                            int i = 0;
                            StringBuilder b = new StringBuilder("§4Bar");
                            for (Object s : (Iterable<?>) listObj) {
                                if (i >= 9) break;
                                appendHotbarLabel(b, s, i, sel);
                                i++;
                            }
                            y = drawLine(dc, font, b.toString(), y);
                        }
                    } else {
                        StringBuilder b = new StringBuilder("§4Bar");
                        for (int i = 0; i < 9; i++) {
                            Object stack = null;
                            try { stack = getStack.invoke(inv, i); } catch (Throwable ignored) {}
                            appendHotbarLabel(b, stack, i, sel);
                        }
                        y = drawLine(dc, font, b.toString(), y);
                    }
                }
            } catch (Throwable t) { logOnce("HotbarItems", t); }
        }
        if (player != null && modOn("YBestHigh", false)) {
            try {
                double yy = firstNum(player, "method_23318", "getY").doubleValue();
                if (yy > sessionMaxY) sessionMaxY = yy;
                String col = sessionMaxY > 200 ? "§c" : sessionMaxY > 100 ? "§e" : "§a";
                y = drawLine(dc, font, String.format("§4Y§7peak %s%.0f", col, sessionMaxY), y);
            } catch (Throwable t) { logOnce("YBestHigh", t); }
        }
        if (modOn("ServerJoinTime", false)) {
            try {
                // Reset the join timer when the world identity changes — that
                // way leaving one server and joining another doesn't show a
                // stale "joined N hours ago" baseline. We piggy-back on the
                // dimension key as a coarse "current world" fingerprint.
                Object world = worldField != null ? worldField.get(mc) : null;
                String dimNow = "?";
                if (world != null) {
                    Object dimObj = tryInvoke(world, "method_27983",
                                                     "getDimensionKey", "getRegistryKey");
                    if (dimObj != null) {
                        Object id = tryInvoke(dimObj, "method_29177", "getValue", "getRegistryName");
                        if (id != null) dimNow = id.toString();
                    }
                }
                if (serverJoinAtMs == 0L
                    || (serverJoinDimSnapshot != null && !serverJoinDimSnapshot.equals(dimNow))) {
                    serverJoinAtMs = System.currentTimeMillis();
                    serverJoinDimSnapshot = dimNow;
                }
                long elapsed = (System.currentTimeMillis() - serverJoinAtMs) / 1000L;
                long h = elapsed / 3600, m = (elapsed % 3600) / 60, s = elapsed % 60;
                String t = h > 0 ? String.format("%dh %02dm %02ds", h, m, s)
                                 : String.format("%dm %02ds", m, s);
                y = drawLine(dc, font, "§4Joined §f" + t + " ago", y);
            } catch (Throwable t) { logOnce("ServerJoinTime", t); }
        }
        if (modOn("WeatherAlert", false)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    Object rainingObj = tryInvoke(world, "method_8419", "isRaining");
                    Object thunderObj = tryInvoke(world, "method_8546", "isThundering");
                    boolean raining = Boolean.TRUE.equals(rainingObj);
                    boolean thunder = Boolean.TRUE.equals(thunderObj);
                    int state = thunder ? 2 : (raining ? 1 : 0);
                    if (state != lastWeatherState) {
                        weatherChangeAtMs = System.currentTimeMillis();
                        lastWeatherState = state;
                    }
                    long sinceChange = System.currentTimeMillis() - weatherChangeAtMs;
                    if (sinceChange < 5000 && weatherChangeAtMs > 0) {
                        String label = state == 2 ? "§5THUNDER" : state == 1 ? "§9RAIN" : "§eCLEAR";
                        String flash = (sinceChange / 250) % 2 == 0 ? label : "§8" + label.substring(2);
                        y = drawLine(dc, font, "§4Weather " + flash, y);
                    }
                }
            } catch (Throwable t) { logOnce("WeatherAlert", t); }
        }
        if (player != null && modOn("InvFull", false)) {
            try {
                Object inv = tryInvoke(player, "method_31548", "getInventory");
                Object listObj = inv != null ? tryInvoke(inv, "method_67533") : null;
                if (listObj instanceof Iterable) {
                    int used = 0, total = 0;
                    for (Object s : (Iterable<?>) listObj) {
                        total++;
                        if (s == null) continue;
                        Object empty = tryInvoke(s, "method_7960", "isEmpty");
                        if (!Boolean.TRUE.equals(empty)) used++;
                    }
                    if (total == 0) total = 36;
                    int pct = (used * 100) / total;
                    String col = pct < 60 ? "§a" : pct < 90 ? "§e" : "§c";
                    y = drawLine(dc, font, "§4Inv " + col + pct + "% §8(" + used + "/" + total + ")", y);
                }
            } catch (Throwable t) { logOnce("InvFull", t); }
        }
        if (player != null && modOn("BedExplodes", false)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                String dim = "overworld";
                if (world != null) {
                    Object dimObj = tryInvoke(world, "method_27983", "getDimensionKey", "getRegistryKey");
                    if (dimObj != null) {
                        Object id = tryInvoke(dimObj, "method_29177", "getValue", "getRegistryName");
                        if (id != null) dim = id.toString();
                    }
                }
                if (dim.contains("nether") || dim.contains("the_end")) {
                    Object held = tryInvoke(player, "method_6047", "getMainHandStack");
                    Object off  = tryInvoke(player, "method_6079", "getOffHandStack");
                    String hid = getItemId(held);
                    String oid = getItemId(off);
                    if (hid.endsWith("_bed") || oid.endsWith("_bed")) {
                        y = drawLine(dc, font, "§c§lBED WILL EXPLODE §8(no bed in " + (dim.contains("nether") ? "Nether" : "End") + ")", y);
                    }
                }
            } catch (Throwable t) { logOnce("BedExplodes", t); }
        }
        if (player != null && modOn("AnchorExplodes", false)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                String dim = "overworld";
                if (world != null) {
                    Object dimObj = tryInvoke(world, "method_27983", "getDimensionKey", "getRegistryKey");
                    if (dimObj != null) {
                        Object id = tryInvoke(dimObj, "method_29177", "getValue", "getRegistryName");
                        if (id != null) dim = id.toString();
                    }
                }
                if (!dim.contains("nether")) {
                    Object held = tryInvoke(player, "method_6047", "getMainHandStack");
                    String hid = getItemId(held);
                    if (hid.contains("respawn_anchor")) {
                        y = drawLine(dc, font, "§c§lANCHOR EXPLODES §8(only in Nether)", y);
                    }
                }
            } catch (Throwable t) { logOnce("AnchorExplodes", t); }
        }
        if (player != null && modOn("NetherCeiling", false)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                String dim = "overworld";
                if (world != null) {
                    Object dimObj = tryInvoke(world, "method_27983", "getDimensionKey", "getRegistryKey");
                    if (dimObj != null) {
                        Object id = tryInvoke(dimObj, "method_29177", "getValue", "getRegistryName");
                        if (id != null) dim = id.toString();
                    }
                }
                if (dim.contains("nether")) {
                    double yy = firstNum(player, "method_23318", "getY").doubleValue();
                    int gap = (int) Math.max(0, 128 - yy);
                    String col = gap < 5 ? "§c" : gap < 20 ? "§e" : "§7";
                    y = drawLine(dc, font, "§4Ceiling " + col + gap + "b §8below §fY=128", y);
                }
            } catch (Throwable t) { logOnce("NetherCeiling", t); }
        }
        if (player != null && modOn("LowDurAlert", false)) {
            try {
                int worst = 100;
                Object inv = tryInvoke(player, "method_31548", "getInventory");
                Object listObj = inv != null ? tryInvoke(inv, "method_67533") : null;
                if (listObj instanceof Iterable) {
                    for (Object s : (Iterable<?>) listObj) {
                        if (s == null) continue;
                        Object empty = tryInvoke(s, "method_7960", "isEmpty");
                        if (Boolean.TRUE.equals(empty)) continue;
                        Object dmgable = tryInvoke(s, "method_7986", "isDamageable");
                        if (!Boolean.TRUE.equals(dmgable)) continue;
                        // Only count armor-shaped items (id contains _helmet/_chestplate/_leggings/_boots)
                        String id = getItemId(s);
                        if (!(id.endsWith("_helmet") || id.endsWith("_chestplate")
                              || id.endsWith("_leggings") || id.endsWith("_boots")
                              || id.endsWith("_elytra"))) continue;
                        Number maxN = firstNum(s, "method_7936", "getMaxDamage");
                        Number dmgN = firstNum(s, "method_7919", "getDamage");
                        if (maxN == null || maxN.intValue() <= 0) continue;
                        int max = maxN.intValue();
                        int dmg = dmgN != null ? dmgN.intValue() : 0;
                        int pct = Math.max(0, 100 * (max - dmg) / max);
                        if (pct < worst) worst = pct;
                    }
                }
                if (worst < 10) {
                    long phase = System.currentTimeMillis() / 250L;
                    String col = (phase % 2 == 0) ? "§c§l" : "§4§l";
                    y = drawLine(dc, font, col + "ARMOR " + worst + "% — REPAIR NOW", y);
                }
            } catch (Throwable t) { logOnce("LowDurAlert", t); }
        }
        if (modOn("FovDisplay", false)) {
            try {
                // 1.21.11 mapping: GameOptions.fov SimpleOption is field_1826
                // (field_1842 is unrelated — was a boolean option in this slot).
                Object v = readGameOption("field_1826", "field_1842", "fov");
                if (v instanceof Number) y = drawLine(dc, font, "§4FOV §f" + ((Number) v).intValue() + "°", y);
            } catch (Throwable t) { logOnce("FovDisplay", t); }
        }
        if (modOn("RenderDist", false)) {
            try {
                // field_1870 is the Integer SimpleOption for view distance
                // (field_1864 is a String — language code — and would crash a Number cast).
                Object v = readGameOption("field_1870", "renderDistance", "viewDistance");
                if (v instanceof Number) {
                    int chunks = ((Number) v).intValue();
                    String col = chunks >= 16 ? "§a" : chunks >= 8 ? "§e" : "§c";
                    y = drawLine(dc, font, "§4Render " + col + chunks + "ch", y);
                }
            } catch (Throwable t) { logOnce("RenderDist", t); }
        }
        if (modOn("GuiScale", false)) {
            try {
                // 1.21.11: guiScale moved to field_1868 (field_1851 is gone).
                Object v = readGameOption("field_1868", "field_1851", "guiScale");
                if (v instanceof Number) {
                    int sc = ((Number) v).intValue();
                    String s = sc == 0 ? "auto" : String.valueOf(sc);
                    y = drawLine(dc, font, "§4GUI §f" + s + "x", y);
                }
            } catch (Throwable t) { logOnce("GuiScale", t); }
        }
        if (modOn("FpsCap", false)) {
            try {
                // field_1909 = framerateLimit SimpleOption in 1.21.11
                // (field_1872 is the public int overrideWidth — wrong slot).
                Object v = readGameOption("field_1909", "field_1872", "maxFps");
                if (v instanceof Number) {
                    int fps = ((Number) v).intValue();
                    String s = fps >= 260 ? "unlimited" : (fps + " FPS");
                    y = drawLine(dc, font, "§4Cap §f" + s, y);
                }
            } catch (Throwable t) { logOnce("FpsCap", t); }
        }
        if (modOn("EntityDist", false)) {
            try {
                // field_24214 = entityDistanceScaling SimpleOption (Double).
                Object v = readGameOption("field_24214", "field_24216", "entityDistanceScaling");
                if (v instanceof Number) {
                    double s = ((Number) v).doubleValue();
                    y = drawLine(dc, font, String.format("§4EntDist §f%.0f%%", s * 100.0), y);
                }
            } catch (Throwable t) { logOnce("EntityDist", t); }
        }
        if (player != null && modOn("MaxLevel", false)) {
            try {
                int level = firstNum(player, "field_7520", "experienceLevel").intValue();
                if (level > sessionMaxLevel) sessionMaxLevel = level;
                String col = sessionMaxLevel >= 30 ? "§a" : sessionMaxLevel >= 10 ? "§e" : "§7";
                y = drawLine(dc, font, "§4MaxLvl " + col + sessionMaxLevel
                                      + " §8(now §f" + level + "§8)", y);
            } catch (Throwable t) { logOnce("MaxLevel", t); }
        }
        if (player != null && modOn("MinHp", false)) {
            try {
                float hp = firstNum(player, "method_6032", "getHealth").floatValue();
                if (hp > 0 && hp < sessionMinHp) sessionMinHp = hp;
                float low = sessionMinHp == Float.POSITIVE_INFINITY ? hp : sessionMinHp;
                String col = low < 4 ? "§4" : low < 10 ? "§c" : low < 16 ? "§e" : "§a";
                y = drawLine(dc, font, String.format("§4MinHP %s%.1f §8❤", col, low / 2.0), y);
            } catch (Throwable t) { logOnce("MinHp", t); }
        }
        if (modOn("DimWatcher", false)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                String dim = "?";
                if (world != null) {
                    Object dimObj = tryInvoke(world, "method_27983", "getDimensionKey", "getRegistryKey");
                    if (dimObj != null) {
                        Object id = tryInvoke(dimObj, "method_29177", "getValue", "getRegistryName");
                        if (id != null) dim = id.toString();
                    }
                }
                if (lastDimension == null) lastDimension = dim;
                else if (!dim.equals(lastDimension)) {
                    lastDimension = dim;
                    dimChangeAtMs = System.currentTimeMillis();
                }
                if (dimChangeAtMs > 0 && System.currentTimeMillis() - dimChangeAtMs < 4000) {
                    long phase = (System.currentTimeMillis() - dimChangeAtMs) / 250L;
                    String col = (phase % 2 == 0) ? "§5§l" : "§d§l";
                    String label = dim.contains("nether") ? "NETHER"
                                 : dim.contains("the_end") ? "END"
                                 : dim.contains("overworld") ? "OVERWORLD"
                                 : dim.toUpperCase();
                    y = drawLine(dc, font, col + "→ " + label, y);
                }
            } catch (Throwable t) { logOnce("DimWatcher", t); }
        }
        if (modOn("DayPhase", false)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    Number tod = firstNum(world, "method_8532", "getTimeOfDay");
                    long t = tod != null ? (tod.longValue() % 24000) : 0;
                    String label;
                    if      (t <  1000) label = "§eSunrise";
                    else if (t <  6000) label = "§6Morning";
                    else if (t <  9000) label = "§6Midday";
                    else if (t < 12000) label = "§6Afternoon";
                    else if (t < 13000) label = "§eSunset";
                    else if (t < 18000) label = "§9Night";
                    else if (t < 22000) label = "§9Midnight";
                    else                 label = "§9Dawn";
                    y = drawLine(dc, font, "§4Phase " + label + " §8(" + t + ")", y);
                }
            } catch (Throwable t) { logOnce("DayPhase", t); }
        }
        if (modOn("ChunkLoaded", false)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    // method_2935 is the ClientWorld override (returns
                    // ClientChunkManager); method_8398 is on the abstract
                    // World (returns ChunkManager). Try both — getLoadedChunkCount
                    // (method_14151) lives on the common parent ChunkManager.
                    Object cm = tryInvoke(world, "method_2935", "method_8398",
                                                  "getChunkManager");
                    int loaded = -1;
                    if (cm != null) {
                        Number n = firstNum(cm, "method_14151", "getLoadedChunkCount");
                        if (n != null) loaded = n.intValue();
                    }
                    if (loaded >= 0) y = drawLine(dc, font, "§4Chunks §f" + loaded, y);
                }
            } catch (Throwable t) { logOnce("ChunkLoaded", t); }
        }
        if (modOn("MoveArrow", false)) {
            try {
                // GLFW key codes: W=87 A=65 S=83 D=68
                boolean w = keyDown(87);
                boolean a = keyDown(65);
                boolean s = keyDown(83);
                boolean d = keyDown(68);
                String arrow = " ";
                if (w && a) arrow = "↖";
                else if (w && d) arrow = "↗";
                else if (s && a) arrow = "↙";
                else if (s && d) arrow = "↘";
                else if (w) arrow = "↑";
                else if (s) arrow = "↓";
                else if (a) arrow = "←";
                else if (d) arrow = "→";
                else arrow = "·";
                String col = arrow.equals("·") ? "§8" : "§a";
                y = drawLine(dc, font, "§4Move " + col + arrow, y);
            } catch (Throwable t) { logOnce("MoveArrow", t); }
        }
        if (player != null && modOn("RotationLog", false)) {
            try {
                float yaw = firstNum(player, "method_36454", "getYRot", "getYaw").floatValue();
                float pitch = firstNum(player, "method_36455", "getXRot", "getPitch").floatValue();
                float dy = yaw - rotLastYaw;
                float dp = pitch - rotLastPitch;
                rotLastYaw = yaw; rotLastPitch = pitch;
                // Normalize delta into (-180, 180)
                while (dy >  180) dy -= 360;
                while (dy < -180) dy += 360;
                String dyc = Math.abs(dy) > 30 ? "§c" : Math.abs(dy) > 5 ? "§e" : "§7";
                String dpc = Math.abs(dp) > 20 ? "§c" : Math.abs(dp) > 3 ? "§e" : "§7";
                y = drawLine(dc, font, String.format("§4Δ %sΔy%+.2f §f%sΔp%+.2f", dyc, dy, dpc, dp), y);
            } catch (Throwable t) { logOnce("RotationLog", t); }
        }
        if (player != null && modOn("WaypointDist", false) && wpSet) {
            try {
                double px = firstNum(player, "method_23317", "getX").doubleValue();
                double py = firstNum(player, "method_23318", "getY").doubleValue();
                double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                double dx = wpX - px, dy = wpY - py, dz = wpZ - pz;
                double dist = Math.sqrt(dx*dx + dy*dy + dz*dz);
                String col = dist < 16 ? "§a" : dist < 100 ? "§e" : "§7";
                y = drawLine(dc, font, String.format("§4WP §f%.0fb §8(%.0f %.0f %.0f)",
                    dist, wpX, wpY, wpZ), y);
            } catch (Throwable t) { logOnce("WaypointDist", t); }
        }
        if (player != null && modOn("HeartIcons", false)) {
            try {
                float hp  = firstNum(player, "method_6032", "getHealth").floatValue();
                float max = firstNum(player, "method_6063", "getMaxHealth").floatValue();
                Number absN = firstNum(player, "method_6067", "getAbsorptionAmount");
                float abs = absN != null ? absN.floatValue() : 0f;
                int redHearts = (int) Math.ceil(hp / 2.0);
                int maxHearts = (int) Math.ceil(max / 2.0);
                int yelHearts = (int) Math.ceil(abs / 2.0);
                StringBuilder b = new StringBuilder("§4HP §c");
                for (int i = 0; i < redHearts; i++) b.append("❤");
                b.append("§8");
                for (int i = redHearts; i < maxHearts; i++) b.append("❤");
                if (yelHearts > 0) {
                    b.append(" §6");
                    for (int i = 0; i < Math.min(yelHearts, 10); i++) b.append("❤");
                    if (yelHearts > 10) b.append("§8+§6").append(yelHearts - 10);
                }
                y = drawLine(dc, font, b.toString(), y);
            } catch (Throwable t) { logOnce("HeartIcons", t); }
        }
        if (player != null && modOn("AutoTool", false)) {
            try { runAutoTool(player); }
            catch (Throwable t) { logOnce("AutoTool", t); }
        }
        if (player != null && modOn("NameTag", false)) {
            try {
                Object name = tryInvoke(player, "method_5477", "getName",
                    "method_7334", "getDisplayName");
                String n = name != null ? compToString(name) : "Player";
                y = drawLine(dc, font, "§4Name §f§l" + n, y);
            } catch (Throwable t) { logOnce("NameTag", t); }
        }
        // EnemyArmor render block removed — see-through-walls combat hack.
        if (player != null && modOn("GameTick", false)) {
            try {
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    // 1.21.11: World.getTime (method_8510) is gone. Total
                    // ticks lives on WorldProperties (class_5217), accessible
                    // via World.getLevelProperties() = method_8401. Falls
                    // back to getTimeOfDay if WorldProperties path fails.
                    Object tick = null;
                    Object props = tryInvoke(world, "method_8401",
                        "getLevelProperties", "getProperties");
                    if (props != null) {
                        tick = tryInvoke(props, "method_188", "getTime");
                    }
                    if (tick == null) tick = tryInvoke(world, "method_8532", "getTimeOfDay");
                    if (tick instanceof Number) {
                        long t = ((Number) tick).longValue();
                        long mod = t % 24000;
                        if (mod < 0) mod += 24000;
                        y = drawLine(dc, font, "§4Tick §f" + t + " §8(" + mod + " day)", y);
                    } else {
                        y = drawLine(dc, font, "§4Tick §8(unavailable)", y);
                    }
                }
            } catch (Throwable t) { logOnce("GameTick", t); }
        }
        if (player != null && modOn("InvBar", false)) {
            try {
                Object inv = tryInvoke(player, "method_31548", "getInventory");
                if (inv != null) {
                    int total = 36, full = 0;
                    for (int i = 0; i < 36; i++) {
                        Object stack = invGetStack(inv, i);
                        if (stack == null) continue;
                        Object empty = tryInvoke(stack, "method_7960", "isEmpty");
                        if (!Boolean.TRUE.equals(empty)) full++;
                    }
                    int free = total - full;
                    int barLen = 18;
                    int filled = (int) Math.round((double) full / total * barLen);
                    StringBuilder bar = new StringBuilder();
                    String col = full < total / 2 ? "§a" : full < total * 3 / 4 ? "§e" : "§c";
                    bar.append("§4Inv ").append(col);
                    for (int i = 0; i < filled; i++) bar.append('█');
                    bar.append("§8");
                    for (int i = filled; i < barLen; i++) bar.append('░');
                    bar.append(" §f").append(free).append("/").append(total).append(" free");
                    y = drawLine(dc, font, bar.toString(), y);
                }
            } catch (Throwable t) { logOnce("InvBar", t); }
        }
        if (player != null && modOn("BlockUnder", false)) {
            try {
                // Walk the world for the block at player.feet - 1 Y.
                double ox = firstNum(player, "method_23317", "getX").doubleValue();
                double oy = firstNum(player, "method_23318", "getY").doubleValue();
                double oz = firstNum(player, "method_23321", "getZ").doubleValue();
                Object world = worldField != null ? worldField.get(mc) : null;
                if (world != null) {
                    int bx = (int) Math.floor(ox);
                    int by = (int) Math.floor(oy - 0.05);
                    int bz = (int) Math.floor(oz);
                    // Build a BlockPos via class_2338 constructor, then
                    // call world.getBlockState(BlockPos) and state.getBlock().
                    Class<?> bpCls = Class.forName("net.minecraft.class_2338");
                    Object bp = bpCls.getConstructor(int.class, int.class, int.class)
                        .newInstance(bx, by, bz);
                    Object state = null;
                    for (Method m : world.getClass().getMethods()) {
                        if (m.getParameterCount() != 1) continue;
                        if (!bpCls.isAssignableFrom(m.getParameterTypes()[0])) continue;
                        String n = m.getName();
                        if (n.equals("method_8320") || n.equals("getBlockState")) {
                            state = m.invoke(world, bp); break;
                        }
                    }
                    Object block = state != null ? tryInvoke(state, "method_26204", "getBlock") : null;
                    if (block != null) {
                        // Block.getName() returns Text
                        Object name = tryInvoke(block, "method_9518", "getName");
                        String n = name != null ? compToString(name) : block.getClass().getSimpleName();
                        if (n.length() > 22) n = n.substring(0, 21) + "…";
                        y = drawLine(dc, font, "§4Floor §f" + n, y);
                    }
                }
            } catch (Throwable t) { logOnce("BlockUnder", t); }
        }
        if (modOn("WorldName", false)) {
            try {
                String label = "?";
                Object srv = tryInvoke(mc, "method_1558", "getCurrentServerEntry");
                if (srv != null) {
                    String addr = readStringField(srv, "field_3761", "address");
                    String n    = readStringField(srv, "field_3752", "name");
                    label = (n != null && !n.isEmpty()) ? n
                          : (addr != null ? addr : "server");
                } else {
                    // Singleplayer: 1.21.11 removed method_27728 (getSaveProperties).
                    // Walk the IntegratedServer's methods looking for one that
                    // returns a SaveProperties-like object (class_5219), then
                    // call getLevelName on it.
                    Object iSrv = tryInvoke(mc, "method_1576", "getServer");
                    if (iSrv != null) {
                        Object lvl = null;
                        // Try common method names first
                        for (String n : new String[]{"method_27728", "method_30002",
                                                     "method_3760", "getSaveProperties",
                                                     "getWorldData"}) {
                            Object r = tryInvoke(iSrv, n);
                            if (r != null && r.getClass().getName().contains("class_521")) {
                                lvl = r; break;
                            }
                        }
                        // Fallback: scan all zero-arg methods for one returning class_5219
                        if (lvl == null) {
                            for (java.lang.reflect.Method m : iSrv.getClass().getMethods()) {
                                if (m.getParameterCount() != 0) continue;
                                if (!m.getReturnType().getName().contains("class_5219")) continue;
                                try { lvl = m.invoke(iSrv); if (lvl != null) break; }
                                catch (Throwable ignored) {}
                            }
                        }
                        // SaveProperties.getLevelName — 1.21 renamed method_150 → method_150
                        // (still the same), or via the inner LevelInfo (method_27859).
                        if (lvl != null) {
                            Object name = tryInvoke(lvl, "method_150", "getLevelName");
                            if (name == null) {
                                Object info = tryInvoke(lvl, "method_27859", "getLevelInfo",
                                                              "method_150", "method_29588");
                                if (info != null) {
                                    name = tryInvoke(info, "method_150", "getLevelName");
                                }
                            }
                            if (name != null) label = name.toString();
                        }
                        // Last-ditch fallback: read the directory name from the
                        // session's save handle.
                        if ("?".equals(label)) {
                            Object session = tryInvoke(iSrv, "method_27050", "getSession");
                            Object dirName = session != null
                                ? tryInvoke(session, "method_27013", "getDirectoryName") : null;
                            if (dirName != null) label = dirName.toString();
                        }
                    }
                }
                if (label.length() > 22) label = label.substring(0, 21) + "…";
                y = drawLine(dc, font, "§4World §f" + label, y);
            } catch (Throwable t) { logOnce("WorldName", t); }
        }
        if (player != null && modOn("Coords4TP", false)) {
            try {
                double px = firstNum(player, "method_23317", "getX").doubleValue();
                double py = firstNum(player, "method_23318", "getY").doubleValue();
                double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                Object name = tryInvoke(player, "method_5477", "getName");
                String n = name != null ? compToString(name) : "@p";
                String tp = String.format("/tp %s %.2f %.2f %.2f", n, px, py, pz);
                if (tp.length() > 36) tp = tp.substring(0, 35) + "…";
                y = drawLine(dc, font, "§4TP §f" + tp, y);
            } catch (Throwable t) { logOnce("Coords4TP", t); }
        }
        if (modOn("PlayerCount", false)) {
            try {
                Object net = tryInvoke(mc, "method_1562", "getNetworkHandler");
                Object listEntries = net != null
                    ? tryInvoke(net, "method_2880", "getPlayerList") : null;
                int count = 0;
                if (listEntries instanceof java.util.Collection) {
                    count = ((java.util.Collection<?>) listEntries).size();
                }
                String col = count == 0 ? "§7" : count > 1 ? "§a" : "§e";
                y = drawLine(dc, font, "§4Online " + col + count + "§4 player"
                    + (count == 1 ? "" : "s"), y);
            } catch (Throwable t) { logOnce("PlayerCount", t); }
        }
        if (modOn("FriendsList", false)) {
            try {
                reloadSocialFiles();
                if (friendsSet.isEmpty()) {
                    y = drawLine(dc, font, "§4Friends §8(empty — edit config/shadowclient-friends.txt)", y);
                } else {
                    // Snapshot online names from the tab list — case-insensitive
                    java.util.Set<String> onlineNames = new java.util.HashSet<>();
                    Object net = tryInvoke(mc, "method_1562", "getNetworkHandler");
                    Object listEntries = net != null
                        ? tryInvoke(net, "method_2880", "getPlayerList") : null;
                    if (listEntries instanceof java.util.Collection) {
                        for (Object e : (java.util.Collection<?>) listEntries) {
                            Object profile = tryInvoke(e, "method_2966", "getProfile");
                            Object pname = profile != null
                                ? tryInvoke(profile, "getName") : null;
                            if (pname != null) onlineNames.add(pname.toString().toLowerCase());
                        }
                    }
                    int onlineCount = 0;
                    for (String f : friendsSet) if (onlineNames.contains(f)) onlineCount++;
                    String hCol = onlineCount > 0 ? "§a" : "§7";
                    y = drawLine(dc, font, "§4Friends " + hCol + onlineCount
                        + "§4/§f" + friendsSet.size() + " §8online here", y);
                    // Show up to 8 friends, online ones first.
                    java.util.List<String> ordered = new java.util.ArrayList<>(friendsSet);
                    ordered.sort((a, b) -> {
                        boolean ao = onlineNames.contains(a);
                        boolean bo = onlineNames.contains(b);
                        if (ao != bo) return ao ? -1 : 1;
                        return a.compareTo(b);
                    });
                    int shown = 0;
                    for (String f : ordered) {
                        if (shown++ >= 8) {
                            y = drawLine(dc, font, "§8 …+" + (friendsSet.size() - 8) + " more", y);
                            break;
                        }
                        boolean here = onlineNames.contains(f);
                        String mark = here ? "§a✓ " : "§7· ";
                        // Capitalize first letter for prettier display
                        String pretty = f.substring(0,1).toUpperCase() + f.substring(1);
                        y = drawLine(dc, font, " " + mark + (here ? "§f" : "§7") + pretty, y);
                    }
                }
            } catch (Throwable t) { logOnce("FriendsList", t); }
        }
        if (player != null && modOn("ToolBreak", false)) {
            try {
                Object stack = tryInvoke(player, "method_6047", "getMainHandStack");
                if (stack != null) {
                    Object empty = tryInvoke(stack, "method_7960", "isEmpty");
                    if (!Boolean.TRUE.equals(empty)) {
                        Object dmg = tryInvoke(stack, "method_7919", "getDamage");
                        Object max = tryInvoke(stack, "method_7936", "getMaxDamage");
                        if (dmg instanceof Number && max instanceof Number) {
                            int d = ((Number) dmg).intValue();
                            int mx = ((Number) max).intValue();
                            if (mx > 0) {
                                int remaining = mx - d;
                                int pct = (int) (100.0 * remaining / mx);
                                if (pct < cfgToolBreakThresh) {
                                    long now = System.currentTimeMillis();
                                    if (cfgToolBreakSound
                                            && now - toolBreakLastAlertMs > 4000) {
                                        toolBreakLastAlertMs = now;
                                        try { java.awt.Toolkit.getDefaultToolkit().beep(); }
                                        catch (Throwable ignored) {}
                                    }
                                    int alpha = (int) (160 + Math.sin(now / 100.0) * 80);
                                    if (alpha < 0) alpha = 0; if (alpha > 255) alpha = 255;
                                    y = drawLine(dc, font,
                                        "§c§l⚠ TOOL BREAKING §f"
                                        + remaining + "§4/§f" + mx + "§c (" + pct + "%)", y);
                                }
                            }
                        }
                    }
                }
            } catch (Throwable t) { logOnce("ToolBreak", t); }
        }
        if (modOn("Diagnostic", false)) {
            try { y = renderDiagnostic(dc, font, y); }
            catch (Throwable t) { logOnce("Diagnostic", t); }
        }
        if (player != null && modOn("LowHpAlert", false)) {
            try {
                Object hp = tryInvoke(player, "method_6032", "getHealth");
                if (hp instanceof Number) {
                    float h = ((Number) hp).floatValue();
                    if (h > 0 && h <= LOW_HP_THRESHOLD) {
                        long now = System.currentTimeMillis();
                        if (now - lowHpAlertLastMs >= LOW_HP_DEBOUNCE_MS) {
                            lowHpAlertLastMs = now;
                            try { java.awt.Toolkit.getDefaultToolkit().beep(); }
                            catch (Throwable ignored) {}
                        }
                        // HUD flash: pulsing red border tint + label
                        int alpha = (int) (96 + Math.sin(now / 120.0) * 64);
                        if (alpha < 0) alpha = 0; if (alpha > 255) alpha = 255;
                        int sw = screenWidth(dc), sh = screenHeight(dc);
                        if (fillMethod != null) {
                            int border = (alpha << 24) | 0xC8101A;
                            // Top, bottom, left, right edges
                            fillMethod.invoke(null, dc, 0, 0, sw, 4, border);
                            fillMethod.invoke(null, dc, 0, sh - 4, sw, sh, border);
                            fillMethod.invoke(null, dc, 0, 0, 4, sh, border);
                            fillMethod.invoke(null, dc, sw - 4, 0, sw, sh, border);
                        }
                        y = drawLine(dc, font, "§c§lLOW HP §f" + String.format("%.1f", h), y);
                    }
                }
            } catch (Throwable t) { logOnce("LowHpAlert", t); }
        }
        // HungerWarn: same pattern as LowHpAlert but for the food bar.
        // Threshold 6 = 3 visible chicken legs. Below that, sprinting starts
        // failing, so it's a meaningful action point.
        if (player != null && modOn("HungerWarn", false)) {
            try {
                Object hm = tryInvoke(player, "method_7344", "getHungerManager");
                if (hm != null) {
                    Object foodObj = tryInvoke(hm, "method_7586", "getFoodLevel");
                    if (foodObj instanceof Number) {
                        int food = ((Number) foodObj).intValue();
                        if (food < 6) {
                            long now = System.currentTimeMillis();
                            if (now - hungerWarnLastMs > 4000L) {
                                hungerWarnLastMs = now;
                                try { java.awt.Toolkit.getDefaultToolkit().beep(); }
                                catch (Throwable ignored) {}
                            }
                            int alpha = (int) (96 + Math.sin(now / 150.0) * 64);
                            if (alpha < 0) alpha = 0; if (alpha > 255) alpha = 255;
                            String col = food < 3 ? "§c" : "§e";
                            y = drawLine(dc, font,
                                "§6§l⚠ HUNGRY " + col + food + "§8/§720", y);
                        }
                    }
                }
            } catch (Throwable t) { logOnce("HungerWarn", t); }
        }
        // CoordsBeacon: angle + distance to (0,0). Useful for finding spawn.
        if (player != null && modOn("CoordsBeacon", false)) {
            try {
                double px = firstNum(player, "method_23317", "getX").doubleValue();
                double pz = firstNum(player, "method_23321", "getZ").doubleValue();
                double dist = Math.sqrt(px * px + pz * pz);
                // Compass-style direction TO origin (i.e., the vector
                // from player → (0,0)). At px=100,pz=0 the spawn is West.
                double bearing = Math.toDegrees(Math.atan2(-px, -pz));
                if (bearing < 0) bearing += 360;
                String[] dirs = {"S","SW","W","NW","N","NE","E","SE"};
                int idx = (int) Math.round(bearing / 45.0) & 7;
                String distStr;
                if (dist < 1)              distStr = "AT spawn";
                else if (dist < 1000)      distStr = String.format("%.0fb", dist);
                else                        distStr = String.format("%.1fk", dist / 1000.0);
                y = drawLine(dc, font,
                    "§4Spawn §f" + distStr + " §8← §7" + dirs[idx], y);
            } catch (Throwable t) { logOnce("CoordsBeacon", t); }
        }
        // XpDrop: animated +N counter when the player's total XP increases.
        // Fades over ~2s. A good visual cue that you're actively gathering XP.
        if (player != null && modOn("XpDrop", false)) {
            try {
                Field tef = cachedField(player.getClass(), "field_7495");
                if (tef == null) tef = cachedField(player.getClass(), "totalExperience");
                if (tef != null) {
                    int total = tef.getInt(player);
                    if (xpDropLastTotal >= 0 && total > xpDropLastTotal) {
                        xpDropPendingDelta += (total - xpDropLastTotal);
                        xpDropLastBumpMs = System.currentTimeMillis();
                    }
                    xpDropLastTotal = total;
                }
                long since = System.currentTimeMillis() - xpDropLastBumpMs;
                if (xpDropPendingDelta > 0 && since < 2000L) {
                    int alpha = (int) (255 * (1.0 - since / 2000.0));
                    if (alpha < 0) alpha = 0;
                    String col = since < 500 ? "§a§l" : "§a";
                    y = drawLine(dc, font, col + "+ " + xpDropPendingDelta + " §7XP", y);
                } else if (xpDropPendingDelta > 0 && since >= 2000L) {
                    xpDropPendingDelta = 0;     // reset for next burst
                }
            } catch (Throwable t) { logOnce("XpDrop", t); }
        }
        // PingHistory: rolling 30-sample ping bar chart, 1 sample per second.
        // Useful to spot lag spikes vs steady connection.
        if (modOn("PingHistory", false)) {
            try {
                long now = System.currentTimeMillis();
                if (now - pingBarsLastSampleMs >= 1000L) {
                    pingBarsLastSampleMs = now;
                    int p = sampleLocalPing();
                    pingBars[pingBarsIdx] = p;
                    pingBarsIdx = (pingBarsIdx + 1) % pingBars.length;
                }
                // Render: sparkline-style bars, 30 samples × 2px wide
                if (fillMethod != null) {
                    int barW = 2, barMaxH = 12;
                    int xStart = 2;
                    int yTop = y + 2;
                    fill(dc, xStart, yTop, xStart + 30 * barW + 1,
                        yTop + barMaxH + 1, 0xC0000000);
                    for (int i = 0; i < pingBars.length; i++) {
                        int sIdx = (pingBarsIdx + i) % pingBars.length;
                        int p = pingBars[sIdx];
                        if (p <= 0) continue;
                        // Cap visual height at 500ms ping
                        int h = Math.min(barMaxH, p * barMaxH / 500);
                        if (h < 1) h = 1;
                        int color = p < 100 ? 0xFF22C55E
                                  : p < 250 ? 0xFFEAB308
                                  :           0xFFEF4444;
                        int xL = xStart + 1 + i * barW;
                        fill(dc, xL, yTop + (barMaxH - h),
                             xL + barW - 1, yTop + barMaxH, color);
                    }
                    y += barMaxH + 4;
                    int curr = pingBars[(pingBarsIdx + pingBars.length - 1) % pingBars.length];
                    String col = curr < 100 ? "§a" : curr < 250 ? "§e" : "§c";
                    y = drawLine(dc, font, "§4Ping30s " + col + curr + "§7ms", y);
                }
            } catch (Throwable t) { logOnce("PingHistory", t); }
        }
        // Flush all queued HUD lines as a centered, rounded-corner stack.
        // drawLine() pushed each module's text into hudLineBuffer above —
        // we render them all in one pass here so the column ends up
        // vertically centered on screen instead of stacked at top-left.
        flushHudLines(dc, font);
    }

    // HungerWarn / XpDrop / PingHistory state
    private static long hungerWarnLastMs = 0L;
    private static int  xpDropLastTotal = -1;
    private static int  xpDropPendingDelta = 0;
    private static long xpDropLastBumpMs = 0L;
    private static final int[] pingBars = new int[30];
    private static int  pingBarsIdx = 0;
    private static long pingBarsLastSampleMs = 0L;

    /** Get the local player's ping in ms, or 0 if unavailable. Reads
     *  {@code mc.getNetworkHandler().getPlayerListEntry(uuid).getLatency()}. */
    private static int sampleLocalPing() {
        try {
            Object nh = tryInvoke(mc, "method_1562", "getNetworkHandler");
            if (nh == null) return 0;
            Object p = playerField != null ? playerField.get(mc) : null;
            if (p == null) return 0;
            Object uuid = tryInvoke(p, "method_5667", "getUuid");
            if (uuid == null) return 0;
            // class_634.method_2871(UUID) — getPlayerListEntry by UUID
            Method getEntry = null;
            for (Method m : nh.getClass().getMethods()) {
                if (!"method_2871".equals(m.getName()) && !"getPlayerListEntry".equals(m.getName())) continue;
                if (m.getParameterCount() != 1) continue;
                if (!m.getParameterTypes()[0].getName().equals("java.util.UUID")) continue;
                getEntry = m; break;
            }
            if (getEntry == null) return 0;
            Object entry = getEntry.invoke(nh, uuid);
            if (entry == null) return 0;
            Object lat = tryInvoke(entry, "method_2959", "getLatency");
            return (lat instanceof Number) ? ((Number) lat).intValue() : 0;
        } catch (Throwable t) { return 0; }
    }

    /** Render the Diagnostic module — lists every enabled module name in
     *  green, then every module that has logged an error (via {@code logOnce})
     *  in red. Designed to surface "I enabled X but see nothing" mysteries —
     *  errored tags appear here too so you can pin-point which modules have
     *  reflection problems. Always renders at least the count line so the
     *  user can confirm the module is on. */
    private static int renderDiagnostic(Object dc, Object font, int y) {
        try {
            int totalEnabled = 0;
            for (Boolean b : MODULES.values()) if (Boolean.TRUE.equals(b)) totalEnabled++;
            y = drawLine(dc, font, "§4DBG §f" + totalEnabled + "§7/" + MODULES.size() + " enabled", y);
            // List enabled module names — wrap to multi-line if many. Each line
            // fits ~20 module names at default font width.
            StringBuilder sb = new StringBuilder("§7on: ");
            int onLine = 0;
            for (java.util.Map.Entry<String, Boolean> e : MODULES.entrySet()) {
                if (!Boolean.TRUE.equals(e.getValue())) continue;
                String n = e.getKey();
                String token = (onLine > 0 ? " " : "") + "§a" + n + "§7";
                if (sb.length() + token.length() > 80) {
                    y = drawLine(dc, font, sb.toString(), y);
                    sb.setLength(0); sb.append("§7… ");
                    onLine = 0;
                }
                sb.append(token);
                onLine++;
            }
            if (sb.length() > 6) y = drawLine(dc, font, sb.toString(), y);
            // List errored modules — these are the ones REALLY broken (logged
            // an exception via logOnce). Empty list = no module crashed.
            synchronized (ERRORED_TAGS) {
                if (!ERRORED_TAGS.isEmpty()) {
                    StringBuilder e2 = new StringBuilder("§cERR: ");
                    int n2 = 0;
                    for (String t : ERRORED_TAGS) {
                        if (n2 > 0) e2.append(" ");
                        e2.append("§c").append(t);
                        if (++n2 >= 8) { e2.append(" §7…"); break; }
                    }
                    y = drawLine(dc, font, e2.toString(), y);
                }
            }
        } catch (Throwable ignored) {}
        return y;
    }

    /** Compact a noisy toString output by trimming brackets, collapsing
     *  whitespace, and replacing common verbose tokens. Used by EnchantList. */
    private static String compactString(String s) {
        if (s == null) return "";
        return s.replace("ItemEnchantments{", "")
                .replace("minecraft:", "")
                .replace("Enchantment{", "")
                .replace("}", "")
                .replace(",", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Detect a death / round-end / win pattern in incoming chat and send "gg"
     * once per 5 s. Best-effort — silent failure if MC's send method name
     * isn't reachable on this build.
     */
    private static void tryAutoGg(String text) {
        if (text == null || text.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastAutoGgMs < 5_000L) return;
        String lower = text.toLowerCase();
        boolean trigger =
               lower.contains("you died")
            || lower.contains("you were killed")
            || lower.contains("killed by")
            || lower.contains("game over")
            || lower.contains("victory")
            || lower.contains("you won")
            || lower.contains("defeated by")
            || lower.contains("won the duel")
            || lower.contains("lost the duel")
            || lower.contains("the round is over")
            || lower.contains("match end");
        if (!trigger) return;
        lastAutoGgMs = now;
        try {
            Object net = tryInvoke(mc, "method_1562", "getNetworkHandler");
            if (net == null) return;
            // Try the common send-chat method names. Fabric has reshuffled
            // these over time; one of these typically works on 1.21.x.
            String[] candidates = {
                "method_45729", "sendChatMessage",
                "method_3216",  "sendChat",
                "method_3244",  "method_45730"
            };
            for (String n : candidates) {
                // findMethodByName matches single-arg methods too —
                // cachedMethod was no-arg-only and never found sendChatMessage,
                // silently breaking AutoGG. The param-type guard below still
                // verifies we got the (String) overload before invoking.
                Method snd = findMethodByName(net.getClass(), n);
                if (snd != null
                    && snd.getParameterCount() == 1
                    && snd.getParameterTypes()[0] == String.class) {
                    snd.invoke(net, AUTOGG_MSGS[Math.max(0, Math.min(AUTOGG_MSGS.length - 1, cfgAutoGgIdx))]);
                    return;
                }
            }
        } catch (Throwable t) { logOnce("AutoGG", t); }
    }

    /**
     * True if the given ItemStack is a non-empty shield. Class-name fallback
     * because the registry-id check requires the Items.SHIELD identity which
     * is annoying to resolve via reflection.
     */
    private static boolean isShieldStack(Object stack) {
        if (stack == null) return false;
        Object empty = tryInvoke(stack, "method_7960", "isEmpty");
        if (Boolean.TRUE.equals(empty)) return false;
        Object item = tryInvoke(stack, "method_7909", "getItem");
        if (item == null) return false;
        String cls = item.getClass().getName();
        // Yarn intermediary for ShieldItem is class_1819. Class name match
        // covers both the obfuscated path and any future class rename that
        // keeps "Shield" in the name (uncommon but cheap insurance).
        return cls.contains("class_1819") || cls.endsWith(".ShieldItem")
            || cls.contains("Shield");
    }

    /** Lazy-resolved SoundEvent (reflected from MC's SoundEvents class) for
     *  the "enemy shield came back online" notification. {@code null} after
     *  a failed resolve attempt — we don't keep retrying. */
    private static Object cachedRingSoundEvent;
    private static boolean ringSoundResolveTried;

    /** Play a noticeable "ring" sound when an enemy's shield-disable timer
     *  expires. Tries MC's note-block bell first (heard in the game world),
     *  falls back to AWT's system beep (heard via the OS audio device).
     *  Best-effort — silent failure if neither works. */
    private static void playEnemyShieldRing() {
        if (!ringSoundResolveTried) {
            ringSoundResolveTried = true;
            try {
                Class<?> seCls = Class.forName("net.minecraft.class_3417");  // SoundEvents
                for (Field f : seCls.getFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    try {
                        Object v = f.get(null);
                        if (v == null) continue;
                        // SoundEvents fields can be either RegistryEntry<SoundEvent>
                        // (1.19.3+) or SoundEvent (older). Try unwrapping; if there's
                        // no value() method, assume it's already a SoundEvent.
                        Object sev = tryInvoke(v, "comp_349", "value", "method_22887");
                        if (sev == null) sev = v;
                        Object id = tryInvoke(sev, "comp_3306", "method_14833",
                                                    "getId", "id");
                        if (id == null) continue;
                        String idStr = String.valueOf(id);
                        if (idStr.contains("note_block.bell")
                            || idStr.contains("block.bell.use")
                            || idStr.contains("entity.experience_orb.pickup")) {
                            cachedRingSoundEvent = sev;
                            System.out.println(
                                "[ShadowHud][EnemyShield] sound resolved: " + idStr);
                            break;
                        }
                    } catch (Throwable ignored) {}
                }
                if (cachedRingSoundEvent == null) {
                    System.out.println("[ShadowHud][EnemyShield] no MC sound found "
                        + "— will fall back to OS beep");
                }
            } catch (Throwable t) { logOnce("EnemyShield-resolve-sound", t); }
        }
        // Try MC's own player.playSound(SoundEvent, float, float)
        if (cachedRingSoundEvent != null && mc != null) {
            try {
                Object playerObj = playerField != null ? playerField.get(mc) : null;
                if (playerObj != null) {
                    for (Method m : playerObj.getClass().getMethods()) {
                        if (!"method_5783".equals(m.getName())
                            && !"playSound".equals(m.getName())) continue;
                        if (m.getParameterCount() != 3) continue;
                        Class<?>[] pt = m.getParameterTypes();
                        if (pt[1] != float.class || pt[2] != float.class) continue;
                        if (!pt[0].isInstance(cachedRingSoundEvent)) continue;
                        m.invoke(playerObj, cachedRingSoundEvent, 1.0f, 1.5f);
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        }
        // Fallback: OS beep (audible regardless of MC's sound settings).
        try { java.awt.Toolkit.getDefaultToolkit().beep(); } catch (Throwable ignored) {}
    }

    /**
     * True if the given ItemStack is a non-empty axe. Yarn intermediary for
     * AxeItem is class_1743 (PickaxeItem is class_1810 — different number,
     * so the substring match doesn't false-trigger on pickaxes).
     */
    private static boolean isAxeStack(Object stack) {
        if (stack == null) return false;
        Object empty = tryInvoke(stack, "method_7960", "isEmpty");
        if (Boolean.TRUE.equals(empty)) return false;
        Object item = tryInvoke(stack, "method_7909", "getItem");
        if (item == null) return false;
        String cls = item.getClass().getName();
        return cls.contains("class_1743") || cls.endsWith(".AxeItem");
    }

    /** True if non-empty pickaxe (registry id contains "pickaxe"). 1.21.11
     *  collapsed PickaxeItem into class_1792 — class-name detection no
     *  longer works, so we use the registry ID. */
    private static boolean isPickaxeStack(Object stack) {
        return getItemId(stack).contains("pickaxe");
    }

    /** True if non-empty shovel. */
    private static boolean isShovelStack(Object stack) {
        return getItemId(stack).contains("shovel");
    }

    /** True if non-empty hoe (matches "_hoe" or registry ending in "hoe"
     *  to avoid colliding with "shoe" — though there's no item called
     *  shoe in vanilla, this is defensive). */
    private static boolean isHoeStack(Object stack) {
        String id = getItemId(stack);
        return id.endsWith("_hoe") || id.endsWith(":hoe");
    }

    /** True if non-empty shears. Always one item: minecraft:shears. */
    private static boolean isShearsStack(Object stack) {
        return getItemId(stack).contains("shears");
    }

    /** True if a non-empty stack is an ender pearl (vanilla
     *  {@code minecraft:ender_pearl}). Used by PearlCool to detect throws. */
    private static boolean stackIsPearl(Object stack) {
        if (stack == null) return false;
        Object empty = tryInvoke(stack, "method_7960", "isEmpty");
        if (Boolean.TRUE.equals(empty)) return false;
        return getItemId(stack).contains("ender_pearl");
    }

    /** True if non-empty food. Falls back to a name-based heuristic since
     *  ItemStack.getFoodComponent moved to a component lookup in 1.21.x and
     *  the intermediary changes are noisy. The list covers the common
     *  edible items players carry — gapples, bread, cooked meats, etc. */
    private static boolean stackIsFood(Object stack) {
        if (stack == null) return false;
        Object empty = tryInvoke(stack, "method_7960", "isEmpty");
        if (Boolean.TRUE.equals(empty)) return false;
        String id = getItemId(stack);
        // Heuristic — match common food paths. Won't catch modded foods but
        // covers vanilla. Order: most specific first.
        if (id.contains("apple") || id.contains("bread") || id.contains("steak")
            || id.contains("cooked") || id.contains("carrot") || id.contains("potato")
            || id.contains("beetroot") || id.contains("melon_slice") || id.contains("pumpkin_pie")
            || id.contains("cookie") || id.contains("rabbit_stew") || id.contains("mushroom_stew")
            || id.contains("beetroot_soup") || id.contains("dried_kelp") || id.contains("sweet_berries")
            || id.contains("glow_berries") || id.contains("honey_bottle") || id.contains("milk_bucket")
            || id.contains("chorus_fruit") || id.contains("suspicious_stew") || id.contains("kelp")) {
            return true;
        }
        // raw meat exclusion list — these are food too but cause hunger
        // effect; user might NOT want to auto-eat raw meat
        if (id.contains("porkchop") || id.contains("beef") || id.contains("chicken")
            || id.contains("mutton") || id.contains("rabbit") || id.contains("salmon")
            || id.contains("cod") || id.contains("tropical_fish") || id.contains("pufferfish")) {
            return true;
        }
        return false;
    }

    /** AutoEat: when hunger drops below threshold and a food item is in the
     *  hotbar, swap to it and hold use-item until hunger refills.
     *
     *  <p>Implementation is best-effort:
     *  <ol>
     *    <li>Check {@code hungerManager.foodLevel} (method_7586). If &gt;= 14
     *        and not currently mid-eat, do nothing.</li>
     *    <li>Find a food slot in the hotbar (slots 0–8). Swap to it via
     *        {@code inv.method_61496(slot)}.</li>
     *    <li>Force the use-item KeyBinding pressed for ~1.7s (vanilla eat
     *        time is 32 ticks = 1.6s).</li>
     *    <li>After the timer, release the keybinding.</li>
     *  </ol>
     */
    // runAutoEat() removed — auto-action that gives unfair advantage.

    /** AntiAFK tick: alternate a one-frame sneak press to register input
     *  with the server. Simple and effective — most AFK kicks measure idle
     *  time on the player's last input packet, and a sneak press counts. */
    private static void tickAntiAFK() {
        try {
            if (kbSetPressed == null || keySneakBinding == null) return;
            // Toggle the next input direction so we don't get stuck pressed.
            antiAfkLastWasSneak = !antiAfkLastWasSneak;
            // Press for one frame; the next runs of pollInput will see the
            // sneak module logic clear it (or the toggle next round will).
            kbSetPressed.invoke(keySneakBinding, antiAfkLastWasSneak);
        } catch (Throwable ignored) {}
    }

    // Module state for AutoEat / use-item key handle.
    private static boolean autoEating = false;
    private static int     autoEatPrevSlot = -1;
    private static Object  keyUseBinding;     // class_315.field_1904 (keyUse)

    /** HotbarLock: which slot to enforce. -1 = lock not yet captured (next
     *  pollInput frame will set this from current selectedSlot). Reset to
     *  -1 when the module is toggled off. */
    private static int hotbarLockSlot = -1;

    /** True if a stack is ANY kind of mining/combat tool — pickaxe, axe,
     *  shovel, shears, sword, or hoe. Used by AutoTool's "only swap if
     *  already holding a tool" guard so we never disrupt block placement,
     *  food eating, water bucket use, etc. */
    private static boolean isAnyToolStack(Object stack) {
        if (stack == null) return false;
        Object empty = tryInvoke(stack, "method_7960", "isEmpty");
        if (Boolean.TRUE.equals(empty)) return false;
        return isPickaxeStack(stack) || isAxeStack(stack) || isShovelStack(stack)
            || isShearsStack(stack)  || isSwordStack(stack) || isHoeStack(stack);
    }

    /** AutoTool tick — called from renderHud each frame when the module is
     *  enabled. Looks up the block at the crosshair, finds the FASTEST-mining
     *  tool in the hotbar (slots 0–8), and switches to it via
     *  {@code inv.method_61496(slot)}. The vanilla client tick will sync the
     *  selected slot to the server on its next outgoing packet, so the swap
     *  is visible to other players as well.
     *
     *  <p>Two safety guards:
     *  <ul>
     *    <li>If the user is not currently holding a tool, do nothing — they're
     *        likely placing a block, eating, or using a bucket and a swap
     *        would be disruptive.</li>
     *    <li>"Best" is measured by {@code stack.method_7924(state)} — the real
     *        per-stack mining speed including efficiency enchantment level,
     *        material tier, and tool category match — rather than a hand-coded
     *        tier score. Picks the slot with the highest speed.</li>
     *  </ul>
     */
    private static void runAutoTool(Object player) throws Exception {
        if (mc == null) return;
        long now = System.currentTimeMillis();
        if (now - autoToolLastSwapMs < AUTOTOOL_DEBOUNCE_MS) return;

        // 0) HOLDING-TOOL GUARD — don't auto-swap unless the user is already
        //    holding a tool. Building/placing/eating should never be hijacked.
        Object heldStack = tryInvoke(player, "method_6047", "getMainHandStack");
        if (!isAnyToolStack(heldStack)) return;

        // 1) Get crosshair target — bail unless looking at a block.
        Object hit = tryInvoke(mc, "method_64829", "getCrosshairTarget");
        if (hit == null) {
            try { hit = mc.getClass().getField("field_1765").get(mc); }
            catch (Throwable ignored) {}
        }
        if (hit == null) return;
        Object typeObj = tryInvoke(hit, "method_17783", "getType");
        if (typeObj == null || !typeObj.toString().contains("BLOCK")) return;
        Object pos = tryInvoke(hit, "method_17777", "getBlockPos");
        if (pos == null) return;

        // 2) Get the block state from the world.
        Object world = worldField != null ? worldField.get(mc) : null;
        if (world == null) return;
        Object state = null;
        // BlockView.method_8320(BlockPos) — the only signature, but we look it
        // up by name to avoid hardcoding param types.
        for (Method m : world.getClass().getMethods()) {
            if (!m.getName().equals("method_8320") && !m.getName().equals("getBlockState")) continue;
            if (m.getParameterCount() != 1) continue;
            try { state = m.invoke(world, pos); } catch (Throwable ignored) {}
            if (state != null) break;
        }
        if (state == null) return;

        // 3) Get the inventory list.
        Object inv = tryInvoke(player, "method_31548", "getInventory");
        if (inv == null) return;
        Object listObj = tryInvoke(inv, "method_67533", "getInventoryItems");
        if (!(listObj instanceof java.util.List)) return;
        java.util.List<?> list = (java.util.List<?>) listObj;
        if (list.size() < 9) return;

        int currentSlot = -1;
        Object curObj = tryInvoke(inv, "method_67532", "getSelectedSlot");
        if (curObj instanceof Number) currentSlot = ((Number) curObj).intValue();

        // 4) FASTEST-MINING-TOOL SEARCH — for each hotbar slot, ask MC's own
        //    stack.method_7924(state) for the literal mining-speed value on
        //    THIS state. That number already incorporates:
        //      - whether the tool category matches (returns 1.0 for non-tool,
        //        higher only when right tool type for the block)
        //      - material tier (netherite > diamond > iron > stone > gold > wood)
        //      - efficiency enchantment level (huge multiplier)
        //    So we just pick the slot with the highest speed. Empty / non-tool
        //    slots return 1.0 (vanilla bare-fist speed) — we filter those out
        //    so we don't swap to a fist when no real tool is available.
        Method getMiningSpeed = null;
        if (!list.isEmpty()) {
            Object firstStack = list.get(0);
            if (firstStack != null) {
                for (Method m : firstStack.getClass().getMethods()) {
                    if (!m.getName().equals("method_7924") && !m.getName().equals("getDestroySpeed")
                        && !m.getName().equals("getMiningSpeedMultiplier")) continue;
                    if (m.getParameterCount() != 1) continue;
                    if (m.getReturnType() != float.class) continue;
                    getMiningSpeed = m;
                    break;
                }
            }
        }
        if (getMiningSpeed == null) return;

        int   bestSlot  = -1;
        float bestSpeed = 1.0f;   // anything matching this is "no better than fist"
        for (int slot = 0; slot < 9; slot++) {
            Object stack = list.get(slot);
            if (stack == null) continue;
            Object empty = tryInvoke(stack, "method_7960", "isEmpty");
            if (Boolean.TRUE.equals(empty)) continue;
            // Skip non-tool stacks — we never want to auto-swap to a block,
            // food, etc. (matches the holding-tool guard above).
            if (!isAnyToolStack(stack)) continue;
            float speed;
            try { speed = (float) getMiningSpeed.invoke(stack, state); }
            catch (Throwable t) { continue; }
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot  = slot;
            }
        }
        if (bestSlot < 0) return;                    // no faster tool than what we have
        if (bestSlot == currentSlot) return;         // already on the fastest tool

        // 5) Swap. method_61496(int) is setSelectedSlot — validates the slot
        //    is in [0,8] then writes field_7545. The vanilla ClientPlayerEntity
        //    tick syncs this to the server next packet, so other players see
        //    the swap too.
        Method setSlot = null;
        for (Method m : inv.getClass().getMethods()) {
            if (!m.getName().equals("method_61496") && !m.getName().equals("setSelectedSlot")) continue;
            if (m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] != int.class) continue;
            setSlot = m; break;
        }
        if (setSlot != null) {
            setSlot.invoke(inv, bestSlot);
            // Track previous slot so future "switch back" feature can restore it.
            if (autoToolPrevSlot < 0 || now - autoToolPrevSetMs > 5_000L) {
                autoToolPrevSlot  = currentSlot;
                autoToolPrevSetMs = now;
            }
            // Lightweight block-key tracking for debounce diagnostics. We no
            // longer need a parsed block id since the new logic queries
            // method_7924 directly, so a state-identity string is enough.
            autoToolLastBlock  = String.valueOf(state);
            autoToolLastSwapMs = now;
        }
    }

    /** Get the item's registry ID as a lowercased string, e.g.
     *  "minecraft:diamond_sword". 1.21.11 collapsed many item types
     *  (sword, armor, pickaxe) into the single {@code class_1792}
     *  base class, so class-name detection no longer works — we have
     *  to differentiate by registry ID. {@code Item.toString()} reads
     *  from the registry and returns the ID string. */
    private static String getItemId(Object stack) {
        if (stack == null) return "";
        Object empty = tryInvoke(stack, "method_7960", "isEmpty");
        if (Boolean.TRUE.equals(empty)) return "";
        Object item = tryInvoke(stack, "method_7909", "getItem");
        if (item == null) return "";
        try { return String.valueOf(item).toLowerCase(); }
        catch (Throwable t) { return ""; }
    }

    /** True if the given ItemStack is a non-empty sword. 1.21.11:
     *  SwordItem (class_1829) is gone; swords are generic Items
     *  with sword-like attribute components. Detect by registry ID. */
    private static boolean isSwordStack(Object stack) {
        return getItemId(stack).contains("sword");
    }

    /** True if non-empty armor (helmet/chestplate/leggings/boots). */
    private static boolean isArmorStack(Object stack) {
        String id = getItemId(stack);
        return id.contains("helmet") || id.contains("chestplate")
            || id.contains("leggings") || id.contains("boots");
    }

    /** Material color tag from registry id (§5N for netherite, §bD for
     *  diamond, etc.) — used by ArmorList / EnemyArmor displays. */
    private static String armorMaterialColor(String id) {
        if (id.contains("netherite")) return "§5N";
        if (id.contains("diamond"))   return "§bD";
        if (id.contains("iron"))      return "§7I";
        if (id.contains("chainmail")) return "§7C";
        if (id.contains("gold") || id.contains("golden")) return "§eG";
        if (id.contains("leather"))   return "§6L";
        return "§7?";
    }
    /** Approximate protection points per piece by material tier. */
    private static int armorMaterialPts(String id) {
        if (id.contains("netherite")) return 4;
        if (id.contains("diamond"))   return 4;
        if (id.contains("iron"))      return 3;
        if (id.contains("chainmail")) return 2;
        if (id.contains("gold") || id.contains("golden")) return 2;
        if (id.contains("leather"))   return 1;
        return 2;
    }
    /** Slot tag from id (Helm/Chest/Legs/Boot). */
    private static String armorSlotTag(String id) {
        if (id.contains("helmet"))     return "Helm";
        if (id.contains("chestplate"))return "Chest";
        if (id.contains("leggings"))  return "Legs";
        if (id.contains("boots"))     return "Boot";
        return "Arm";
    }

    /**
     * Pick the best hotbar slot to swap TO during a SpearSwap. Priority:
     * <ol>
     *   <li>A sword anywhere in the hotbar — gives the cleanest attribute
     *       swap because the server sees us hold a "real" weapon and
     *       rebuilds the snapshot accordingly.</li>
     *   <li>Any non-empty, non-spear slot — fallback if no sword present.</li>
     *   <li>{@code lastNonSpearSlot} — the slot the user was last on
     *       before grabbing the spear, even if currently empty.</li>
     *   <li>{@code (curSlot + 1) % 9} — neighbour, last-resort.</li>
     * </ol>
     * The current spear slot is excluded from sword/non-empty searches so
     * we never schedule a "swap" that's a no-op.
     */
    private static int pickSpearSwapTarget(Object inv, int curSlot) {
        int swordSlot = -1, anyNonSpear = -1;
        if (inv != null) {
            for (int i = 0; i < 9; i++) {
                if (i == curSlot) continue;
                Object stack = null;
                try {
                    for (Method m : inv.getClass().getMethods()) {
                        if (m.getParameterCount() != 1) continue;
                        if (m.getParameterTypes()[0] != int.class) continue;
                        String n = m.getName();
                        if (n.equals("method_5438") || n.equals("getStack")) {
                            stack = m.invoke(inv, i); break;
                        }
                    }
                } catch (Throwable ignored) {}
                if (stack == null) continue;
                Object empty = tryInvoke(stack, "method_7960", "isEmpty");
                if (Boolean.TRUE.equals(empty)) continue;
                if (isSpearStack(stack)) continue;
                if (anyNonSpear < 0) anyNonSpear = i;
                if (isSwordStack(stack)) { swordSlot = i; break; }
            }
        }
        if (swordSlot >= 0) return swordSlot;
        if (anyNonSpear >= 0) return anyNonSpear;
        if (lastNonSpearSlot >= 0 && lastNonSpearSlot < 9
            && lastNonSpearSlot != curSlot) return lastNonSpearSlot;
        return (curSlot + 1) % 9;
    }

    /** True if the given ItemStack is a Totem of Undying. Match by item-id
     *  string ("minecraft:totem_of_undying") OR class-name fallback. */
    private static boolean isTotemStack(Object stack) {
        if (stack == null) return false;
        Object empty = tryInvoke(stack, "method_7960", "isEmpty");
        if (Boolean.TRUE.equals(empty)) return false;
        Object item = tryInvoke(stack, "method_7909", "getItem");
        if (item == null) return false;
        // Try to read the registry id — most reliable check
        try {
            Object id = tryInvoke(item, "toString");
            if (id != null && id.toString().toLowerCase().contains("totem")) return true;
        } catch (Throwable ignored) {}
        return item.getClass().getName().contains("Totem");
    }

    /** Read a stack from inventory slot N. Tries method_5438 (getStack),
     *  falling back to direct field access if needed. Returns null on
     *  failure rather than throwing — caller handles the empty case. */
    private static Object invGetStack(Object inv, int slot) {
        try {
            for (Method m : inv.getClass().getMethods()) {
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0] != int.class) continue;
                String n = m.getName();
                if (n.equals("method_5438") || n.equals("getStack")) {
                    return m.invoke(inv, slot);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * True if the given ItemStack is a "spear" — vanilla trident (TridentItem)
     * or a server-custom spear identified by display-name match.
     *
     * <p>Vanilla TridentItem yarn intermediary covers the 1.21.x family. For
     * custom server spears (cosmosmc / pvp servers with reach-modifier spear
     * weapons), we fall back to a substring match on the localised display
     * name — most server spears have "Spear" or "spear" somewhere in the
     * item's NBT-driven name.</p>
     */
    private static boolean isSpearStack(Object stack) {
        if (stack == null) return false;
        // Three-pronged detection — registry ID (vanilla trident),
        // class-name (legacy mappings), and display name (custom servers).
        String id = getItemId(stack);
        if (id.contains("trident") || id.contains("spear")) return true;
        Object item = tryInvoke(stack, "method_7909", "getItem");
        if (item == null) return false;
        String cls = item.getClass().getName();
        if (cls.contains("class_1835") || cls.contains("class_1820")
            || cls.contains("class_1826") || cls.endsWith(".TridentItem")
            || cls.contains("Trident")) return true;
        // Custom server spear — match display name (server can rename items)
        Object name = tryInvoke(stack, "method_7964", "getName");
        if (name != null) {
            String s = compToString(name).toLowerCase();
            if (s.contains("spear")) return true;
        }
        return false;
    }

    /** Cached MinecraftClient.doAttack handle — what MC invokes when LMB
     *  is pressed. Resolved once on first SpearSwap fire. */
    private static Method doAttackMethod;
    private static boolean doAttackResolveTried;

    /**
     * Simulate an LMB click — the same code path MC takes when the user
     * actually presses left-mouse-button to attack. This calls
     * {@code MinecraftClient.doAttack()} (intermediary {@code method_1559})
     * which handles target detection, swing animation, and the proper
     * AttackEntity / interactionManager dispatch.
     *
     * <p>Used by SpearSwap after the back-swap lands us on the spear, so
     * the attribute-refreshed attack actually executes — the user's spear
     * lunge fires automatically without them needing a click.</p>
     */
    private static void doSimulatedAttack() {
        if (mc == null) return;
        if (!doAttackResolveTried) {
            doAttackResolveTried = true;
            // 1.21.11: doAttack is private and returns boolean
            // (method_1536). Older versions: public void method_1559.
            // Walk public methods then declared methods, accept either
            // void or boolean returns, sized 0 args.
            String[] candidates = {
                "method_1536",   // 1.21.11 — private boolean doAttack
                "method_1559",   // 1.21.4 and older — public void doAttack
                "doAttack", "startAttack"
            };
            // Search getDeclaredMethods(), walking up the class hierarchy
            // to also catch package-private inheritance (just in case).
            for (Class<?> c = mc.getClass(); c != null && doAttackMethod == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    Class<?> rt = m.getReturnType();
                    if (rt != void.class && rt != boolean.class) continue;
                    String n = m.getName();
                    for (String cand : candidates) {
                        if (n.equals(cand)) {
                            m.setAccessible(true);
                            doAttackMethod = m;
                            System.out.println("[ShadowHud][SpearSwap] doAttack resolved: "
                                + n + " on " + c.getSimpleName()
                                + " (returns " + rt.getSimpleName() + ")");
                            break;
                        }
                    }
                    if (doAttackMethod != null) break;
                }
            }
            if (doAttackMethod == null) {
                System.err.println("[ShadowHud][SpearSwap] doAttack method NOT found — auto-attack disabled");
            }
        }
        if (doAttackMethod != null) {
            try { doAttackMethod.invoke(mc); }
            catch (Throwable t) { logOnce("SpearSwap-attack", t); }
        }
    }

    /**
     * Send an UpdateSelectedSlotC2SPacket to the server AND update the
     * client's local selectedSlot field so the HUD/hand stay in sync.
     * Caches the packet class + sendPacket method on first call.
     *
     * <p>This is what physically triggers the server-side attribute
     * recalc — when the server processes the slot change it rebuilds
     * the player's attribute snapshot, which is the whole point of
     * SpearSwap. Forward swap moves us one slot away; the back swap
     * 50 ms later returns us to the spear with attributes refreshed.</p>
     */
    private static void sendSlotUpdate(Object player, int slot) {
        if (slot < 0 || slot >= 9) return;
        try {
            // 1) Update client-side selectedSlot field so render is in sync.
            Object inv = tryInvoke(player, "method_31548", "getInventory");
            if (inv != null) {
                try {
                    Field sf = cachedField(inv.getClass(), "selectedSlot");
                    if (sf != null && sf.getType() == int.class) {
                        sf.setAccessible(true);
                        sf.setInt(inv, slot);
                    }
                } catch (Throwable ignored) {}
            }
            // 2) Resolve packet class once.
            if (!slotPacketResolveTried) {
                slotPacketResolveTried = true;
                for (String n : new String[]{
                        "net.minecraft.class_2868",
                        "net.minecraft.class_2898",
                        "net.minecraft.class_2829" }) {
                    try { slotPacketClass = Class.forName(n); break; }
                    catch (Throwable ignored) {}
                }
            }
            if (slotPacketClass == null) return;
            Object packet = slotPacketClass.getConstructor(int.class).newInstance(slot);
            // 3) Resolve network handler + sendPacket method, send.
            Object handler = tryInvoke(mc, "method_1562", "getNetworkHandler");
            if (handler == null) return;
            if (slotSendPacketMethod == null
                || !slotSendPacketMethod.getDeclaringClass().isInstance(handler)) {
                for (Method m : handler.getClass().getMethods()) {
                    if (m.getParameterCount() != 1) continue;
                    String n = m.getName();
                    if ((n.equals("method_52787") || n.equals("sendPacket")
                            || n.equals("send"))
                        && m.getParameterTypes()[0].isInstance(packet)) {
                        slotSendPacketMethod = m; break;
                    }
                }
            }
            if (slotSendPacketMethod != null) {
                slotSendPacketMethod.invoke(handler, packet);
            }
        } catch (Throwable t) { logOnce("SpearSwap-send", t); }
    }

    /**
     * Get the current ping in ms — replicates the lookup chain from the
     * Ping module so NetGraph can sample without duplicating all that logic.
     * Returns -1 if the ping can't be resolved.
     */
    private static int sampleCurrentPing(Object player) {
        try {
            if (player == null) return -1;
            Object net = tryInvoke(mc, "method_1562", "getNetworkHandler");
            if (net == null) return -1;
            Object uuid = tryInvoke(player, "method_5667", "getUuid");
            if (uuid == null) return -1;
            Object listEntry = null;
            if (pingListEntryMethod != null) {
                listEntry = pingListEntryMethod.invoke(net, uuid);
            }
            if (listEntry == null) return -1;
            Object lat = tryInvoke(listEntry, "method_2959", "getLatency");
            if (lat instanceof Number) return ((Number) lat).intValue();
        } catch (Throwable ignored) {}
        return -1;
    }

    /** Render a sorted-by-ping panel along the right edge of the screen
     *  whenever the tab-list key is held. Each line: `Name ###ms`, colour-
     *  coded green/yellow/red. Caps at 16 players to keep the panel sane. */
    private static boolean tabPingsDiagnosticLogged;
    private static void renderTabPings(Object dc) {
        if (mc == null) return;
        if (!shadowhud$tabHeld()) return;

        Object font = null;
        try { font = fontField.get(mc); } catch (Throwable ignored) {}
        if (font == null) return;

        try {
            Object net = tryInvoke(mc, "method_1562", "getNetworkHandler", "getConnection");
            if (net == null) return;
            Object list = tryInvoke(net, "method_2880", "getPlayerList", "getOnlinePlayers");
            if (!(list instanceof java.util.Collection)) return;

            java.util.List<int[]> entries = new java.util.ArrayList<>();   // [ping, idx]
            java.util.List<String> names = new java.util.ArrayList<>();
            int idx = 0;
            for (Object e : (java.util.Collection<?>) list) {
                int p = -1;
                // Try every known latency accessor — MC has reshuffled these
                // across versions (method_2959 was getLatency in 1.20-, the
                // newer field_53033 holds it in 1.21.5+).
                Object lat = tryInvoke(e,
                    "method_2959", "getLatency", "getLatencyMs",
                    "method_53034", "ping", "latency");
                if (lat instanceof Number) p = ((Number) lat).intValue();
                if (p <= 0) {
                    String[] tryFields = {"field_3739", "field_53033", "latency", "ping"};
                    Class<?> cc = e.getClass();
                    while (cc != null && p <= 0) {
                        for (String fn : tryFields) {
                            try {
                                java.lang.reflect.Field f = cc.getDeclaredField(fn);
                                f.setAccessible(true);
                                if (f.getType() == int.class) {
                                    int v = f.getInt(e);
                                    if (v > 0) { p = v; break; }
                                }
                            } catch (Throwable ignored) {}
                        }
                        cc = cc.getSuperclass();
                    }
                }
                Object profile = tryInvoke(e, "method_2966", "getProfile",
                                              "method_2880");
                String name = "?";
                if (profile != null) {
                    Object n = tryInvoke(profile, "getName");
                    if (n != null) name = String.valueOf(n);
                }
                if (name.length() > 16) name = name.substring(0, 14) + "…";
                entries.add(new int[]{p, idx});
                names.add(name);
                idx++;
            }
            if (!tabPingsDiagnosticLogged) {
                tabPingsDiagnosticLogged = true;
                StringBuilder sb = new StringBuilder("[ShadowHud][TabPings] entries=")
                    .append(entries.size()).append(" — ");
                int n = Math.min(5, entries.size());
                for (int i = 0; i < n; i++) {
                    sb.append(names.get(entries.get(i)[1]))
                      .append('=').append(entries.get(i)[0]).append("ms ");
                }
                if (entries.isEmpty()) {
                    sb.append("EMPTY (player-list reflection returned no entries — "
                        + "most likely method_2880 didn't resolve)");
                }
                System.out.println(sb);
            }
            if (entries.isEmpty()) return;
            entries.sort((a, b) -> Integer.compare(a[0], b[0]));

            int sw = screenWidth(dc), sh = screenHeight(dc);
            int max = Math.min(16, entries.size());
            int rowH = 9;
            int panelW = 110, panelH = max * rowH + 8;
            int px = sw - panelW - 4;
            int py = (sh - panelH) / 2;
            // Background panel
            fill(dc, px, py, px + panelW, py + panelH, 0xC8050103);
            fill(dc, px, py, px + panelW, py + 1, 0xFFAF121A);
            fill(dc, px, py + panelH - 1, px + panelW, py + panelH, 0xFFAF121A);
            fill(dc, px, py, px + 1, py + panelH, 0xFFAF121A);
            fill(dc, px + panelW - 1, py, px + panelW, py + panelH, 0xFFAF121A);
            draw(dc, font, "§l§cPING", px + 6, py + 3, 0xFFFFFFFF);
            int y = py + 3 + rowH;
            for (int i = 0; i < max; i++) {
                int[] e = entries.get(i);
                int ping = e[0];
                String name = names.get(e[1]);
                String col = ping < 0 ? "§7" : ping < 70 ? "§a"
                          : ping < 150 ? "§e" : "§c";
                String pStr = ping < 0 ? "—" : (ping + "ms");
                // Friend marker: yellow ★ before name when Friends module is on
                String prefix = (modOn("Friends", false) && isFriend(name))
                    ? "§e★ " : "";
                String nameCol = prefix.isEmpty() ? "§f" : "§e";
                String ln = prefix + nameCol + name + " " + col + pStr;
                draw(dc, font, ln, px + 6, y, 0xFFFFFFFF);
                y += rowH;
            }
        } catch (Throwable ignored) {}
    }

    /** Customizable crosshair drawn at screen center. The vanilla MC crosshair
     *  is still drawn underneath — that's by design: the user gets a clearly
     *  distinct, themed crosshair on top of vanilla so disabling the module
     *  reverts to stock without surprises. */
    private static void renderCustomCrosshair(Object dc) {
        if (fillMethod == null) fillMethod = findFill(dc.getClass());
        int sw = screenWidth(dc), sh = screenHeight(dc);
        int cx = sw / 2, cy = sh / 2;
        int color = currentCrosshairColor();
        switch (crosshairShape) {
            case 0:  // Cross — two perpendicular bars
                fill(dc, cx - 7, cy,     cx + 8, cy + 1, color);
                fill(dc, cx,     cy - 7, cx + 1, cy + 8, color);
                fill(dc, cx,     cy,     cx + 1, cy + 1, 0xFF000000);  // black pip dot
                break;
            case 1:  // Circle — 4 short arms forming a hollow ring
                fill(dc, cx - 6, cy - 1, cx - 3, cy + 2, color);
                fill(dc, cx + 3, cy - 1, cx + 6, cy + 2, color);
                fill(dc, cx - 1, cy - 6, cx + 2, cy - 3, color);
                fill(dc, cx - 1, cy + 3, cx + 2, cy + 6, color);
                break;
            case 2:  // Dot — solid 3×3
                fill(dc, cx - 1, cy - 1, cx + 2, cy + 2, color);
                break;
            case 3:  // X — diagonals (drawn as pixel stairs)
                for (int i = -5; i <= 5; i++) {
                    if (i == 0) continue;
                    fill(dc, cx + i, cy + i, cx + i + 1, cy + i + 1, color);
                    fill(dc, cx + i, cy - i, cx + i + 1, cy - i + 1, color);
                }
                break;
        }
    }

    /** Tab-list HP overlay. Only draws while the player-list key is held —
     *  matches the visibility of the vanilla tab list. Renders our own HP at
     *  the top so we always know where we stand even on servers that don't
     *  publish a scoreboard objective. */
    private static void renderTabHp(Object dc) {
        if (mc == null || playerField == null) return;
        if (!shadowhud$tabHeld()) return;

        Object font = fontField != null ? null : null;
        try { font = fontField.get(mc); } catch (Throwable ignored) {}
        Object player = null;
        try { player = playerField.get(mc); } catch (Throwable ignored) {}
        if (font == null || player == null) return;

        try {
            float hp  = firstNum(player, "method_6032", "getHealth").floatValue();
            float max = firstNum(player, "method_6063", "getMaxHealth").floatValue();
            float abs = firstNum(player, "method_6067", "getAbsorptionAmount").floatValue();
            String col = hpColor(hp, max);
            String line = "§4Your HP " + col + trimFloat(hp) + "§4/§f" + trimFloat(max)
                + (abs > 0.01f ? " §6+" + trimFloat(abs) : "");
            int sw = screenWidth(dc);
            // Centered above the tab list area (top ~20 px from screen top)
            int x = (sw - line.length() * 6) / 2;
            // Background plate for legibility
            fill(dc, x - 3, 18, x + line.length() * 6 + 3, 30, 0xC0000000);
            fill(dc, x - 3, 18, x + line.length() * 6 + 3, 19, 0xFFAF121A);
            fill(dc, x - 3, 29, x + line.length() * 6 + 3, 30, 0xFFAF121A);
            draw(dc, font, line, x, 21, 0xFFFFFFFF);
        } catch (Throwable ignored) {}
    }

    // ---- keystrokes overlay -----------------------------------------------

    /** GLFW keycodes for the keys we render. We hardcode WASD/Space rather
     *  than reading Minecraft's keybind config because (a) most users never
     *  rebind movement and (b) reading those binds via reflection would
     *  pull in another whole mapping chain. If you've remapped your movement
     *  keys, the overlay just won't reflect that. */
    private static final int KEY_W = 87, KEY_A = 65, KEY_S = 83, KEY_D = 68, KEY_SPACE = 32;
    /** GLFW mouse button codes — left and right respectively. */
    private static final int MB_LEFT  = 0;
    private static final int MB_RIGHT = 1;

    /** Lunar-style WASD + Space + mouse overlay in the bottom-right corner.
     *
     *  Layout:
     *    [   ] [ W ] [   ]
     *    [ A ] [ S ] [ D ]
     *    [     SPACE     ]
     *    [  LMB  ] [ RMB ]
     */
    private static void renderKeystrokes(Object dc) {
        if (mc == null) return;
        Object font = null;
        try { font = fontField != null ? fontField.get(mc) : null; } catch (Throwable ignored) {}
        if (font == null) return;
        if (fillMethod == null) fillMethod = findFill(dc.getClass());

        final int BOX  = 18;        // size of each WASD square
        final int GAP  = 2;         // gap between boxes
        final int SP_H = 8;         // space-bar height
        final int MB_H = 12;        // mouse-button row height (slightly slimmer)
        final int W    = 3 * BOX + 2 * GAP;             // total widget width
        // Stack (top → bottom): [W row] gap [ASD row] gap+gap [SPACE] gap [MB row]
        final int H    = BOX + GAP + BOX + GAP + GAP + SP_H + GAP + MB_H;

        int sw = screenWidth(dc), sh = screenHeight(dc);
        // Position derived from keysPosIdx (cycled via Enter on the card):
        //   0 = bottom-right (default), 1 = bottom-left,
        //   2 = top-right,             3 = top-left.
        int x, y;
        switch (keysPosIdx) {
            case 1:  x = 4;          y = sh - H - 4; break;        // BL
            case 2:  x = sw - W - 4; y = 4;          break;        // TR
            case 3:  x = 4;          y = 4;          break;        // TL
            default: x = sw - W - 4; y = sh - H - 4; break;        // BR
        }

        boolean wDown   = keyDown(KEY_W);
        boolean aDown   = keyDown(KEY_A);
        boolean sDown   = keyDown(KEY_S);
        boolean dDown   = keyDown(KEY_D);
        boolean spDown  = keyDown(KEY_SPACE);
        boolean lmbDown = mouseButtonDown(MB_LEFT);
        boolean rmbDown = mouseButtonDown(MB_RIGHT);

        // W (centered above ASD)
        drawKeyBox(dc, font, x + (BOX + GAP),    y,                   BOX, BOX, "W", wDown);
        // A S D
        int row2Y = y + BOX + GAP;
        drawKeyBox(dc, font, x,                  row2Y,               BOX, BOX, "A", aDown);
        drawKeyBox(dc, font, x + (BOX + GAP),    row2Y,               BOX, BOX, "S", sDown);
        drawKeyBox(dc, font, x + 2 * (BOX + GAP),row2Y,               BOX, BOX, "D", dDown);
        // SPACE — full width
        int spaceY = row2Y + BOX + GAP + GAP;
        drawKeyBox(dc, font, x, spaceY, W, SP_H, "_____", spDown);
        // LMB / RMB row — split the widget width into two equal pills with a gap
        int mbY = spaceY + SP_H + GAP;
        int mbW = (W - GAP) / 2;
        drawKeyBox(dc, font, x,                  mbY,                 mbW, MB_H, "LMB", lmbDown);
        drawKeyBox(dc, font, x + mbW + GAP,      mbY,                 mbW, MB_H, "RMB", rmbDown);
    }

    /** Render one keystroke box. Pressed = filled red, idle = dark with
     *  thin red border. The label is centered horizontally + vertically. */
    private static void drawKeyBox(Object dc, Object font, int x, int y, int w, int h,
                                   String label, boolean pressed) {
        int bg     = pressed ? 0xE6AF121A : 0xCC0A0204;
        int border = pressed ? 0xFFFF2030 : 0xFF8A0F18;
        // Body
        fill(dc, x, y, x + w, y + h, bg);
        // 1-px border on all four sides
        fill(dc, x,         y,         x + w,     y + 1,         border);
        fill(dc, x,         y + h - 1, x + w,     y + h,         border);
        fill(dc, x,         y,         x + 1,     y + h,         border);
        fill(dc, x + w - 1, y,         x + w,     y + h,         border);
        // Label centered (MC font is ~6 px wide per char, ~7 px tall)
        int labelW = label.length() * 6 - 1;
        int labelX = x + Math.max(1, (w - labelW) / 2);
        int labelY = y + Math.max(0, (h - 8) / 2 + 1);
        String prefix = pressed ? "§l§f" : "§7";
        draw(dc, font, prefix + label, labelX, labelY, 0xFFFFFFFF);
    }

    // ---- Lunar-style menu --------------------------------------------------

    private static boolean menuRenderLogged = false;
    private static void renderMenu(Object dc) throws Exception {
        Object font = fontField.get(mc);
        if (font == null) return;
        if (fillMethod == null) fillMethod = findFill(dc.getClass());
        if (!modsLoaded) loadMods();
        // Reset hover-tooltip state — cards write their name/desc into these
        // when hovered, the footer reads them at the end of this frame.
        hoverModuleName = "";
        hoverModuleDesc = "";
        // While the help overlay is up, clicks/scrolls/keys should NOT reach
        // the cards underneath — otherwise dismissing the overlay would also
        // accidentally toggle a module. Stash + clear leftClickEdge for the
        // body of this frame; the overlay's own click handler restores it.
        boolean savedLeftClickEdge = leftClickEdge;
        if (helpOverlayOpen) leftClickEdge = false;
        // Same trick for the per-module config panel: while it's open, hide
        // clicks (including right-clicks) from the cards underneath so the
        // user can interact with the panel without accidentally toggling
        // or re-opening the panel for a different module.
        if (configPanelModule != null) {
            leftClickEdge = false;
            rightClickEdge = false;
        }
        // Color picker modal also blocks clicks from cards underneath.
        if (colorPickerOpen) {
            leftClickEdge = false;
            rightClickEdge = false;
        }
        if (!menuRenderLogged) {
            menuRenderLogged = true;
            System.out.println("[ShadowHud] renderMenu fill=" + (fillMethod == null ? "null" : fillMethod.getName())
                + " drawText=" + (drawTextMethod == null ? "null" : drawTextMethod.getName()));
        }

        // Layout constants — Vape Lite-style full-width row list.
        // Each module is a horizontal row: [colored icon square] [name + desc]
        // [pill toggle]. 44 px tall — bumped from 38 for more breathing room
        // ("HUD looks cramped" feedback).
        final int W = Math.min(580, Math.max(380, screenWidth(dc) - 40));
        final int ROW_H     = 11;
        final int CARD_H    = 44;
        final int CARD_GAP  = 6;     // wider gap (was 4)
        // Force single-column. menuGridCols still persists in config but
        // visually we always render one row per card now (Vape-Lite style).
        final int GRID_COLS = 1;
        final int PRESET_BAR_H    = 0;    // preset bar removed entirely (Vape Lite redesign)
        final int TAB_BAR_H       = 13;   // mini-buttons strip at top
        final int SIDEBAR_W       = 64;   // left vertical category sidebar width
        // Card width derived from panel width — 4 px right margin reserved for
        // the scrollbar so cards don't overlap it.
        // Cards span the panel width MINUS the left sidebar (categories live
        // there now) MINUS scrollbar/right inset. Was: (W - 12 - 4) / cols.
        final int CARD_W = (W - 12 - 4 - SIDEBAR_W - CARD_GAP * (GRID_COLS - 1)) / GRID_COLS;
        int sw = screenWidth(dc), sh = screenHeight(dc);
        // ---------------- ADAPTIVE SIZING -----------------------------
        // Fixed overhead = title + preset bar + tab bar + dividers +
        // INSTALLED MODS header + footer + accessibility-footer + margins.
        // Whatever's left of (sh - 20 px margin) goes to the modules grid
        // and the mod list. Default budget: 4 rows × 3 cols of cards plus
        // 6 mod rows, but we shrink either if the screen is too short
        // (high GUI scale, small windows). Ensures the menu's bottom never
        // renders off the visible viewport — that was the "can't scroll
        // all the way to the bottom" bug.
        final int FIXED_OVERHEAD = 18 + 6 + 2 + 4 + 8 + 12 + 22 + 12;
        // Mod list section is hidden (Vape Lite redesign), so don't reserve
        // budget for it — that frees up vertical space for more module rows.
        int VISIBLE_ROWS = 10;
        int VISIBLE_MODS = 0;
        int avail = Math.max(0, sh - 20 - FIXED_OVERHEAD - PRESET_BAR_H - TAB_BAR_H);
        // Shrink first if the screen is too short for the default row count.
        while (VISIBLE_ROWS * (CARD_H + CARD_GAP) + VISIBLE_MODS * ROW_H > avail) {
            if (VISIBLE_ROWS > 3) VISIBLE_ROWS--;
            else break;   // give up — really tiny window
        }
        // Grow if there's headroom — fills tall windows so the menu doesn't
        // look short/cramped on 1440p+ displays. Cap at 16 so the menu
        // never eats more than ~80% of the screen.
        while (VISIBLE_ROWS < 16
            && (VISIBLE_ROWS + 1) * (CARD_H + CARD_GAP) + VISIBLE_MODS * ROW_H <= avail) {
            VISIBLE_ROWS++;
        }
        final int VISIBLE_ROWS_F   = VISIBLE_ROWS;
        final int VISIBLE_MODS_F   = VISIBLE_MODS;
        final int VISIBLE_MODULES  = VISIBLE_ROWS_F * GRID_COLS;
        int modulesH = VISIBLE_ROWS_F * (CARD_H + CARD_GAP);
        // INSTALLED MODS section hidden — no header, no rows, no divider.
        int bodyH = 18                              // title bar
                  + 6 + TAB_BAR_H                   // mini-buttons strip
                  + 4 + modulesH                    // modules grid (cards + sidebar)
                  + 22                              // footer
                  + 12;                             // accessibility footer with key shortcuts
        int x = (sw - W) / 2;
        int y = Math.max(10, (sh - bodyH) / 2);

        // Index space: [filtered-modules grid][mod list]
        // The preset row was removed in the Vape Lite redesign so module
        // index 0 is now the first card directly. presetCount stays at 0
        // so any leftover code that subtracts it still works correctly.
        int presetCount = 0;
        int modulesBase = presetCount;
        int filteredCount = countFilteredModules();
        int modListBase = presetCount + filteredCount;
        int totalRowsAll = modListBase + MODS.size();

        // Scroll math is in GRID rows now: moduleScrollTop holds the index of
        // the first VISIBLE card (top-left of the grid). It still steps by
        // GRID_COLS so the grid scrolls one row at a time.
        int totalRows = (filteredCount + GRID_COLS - 1) / GRID_COLS;
        int maxScrollRow = Math.max(0, totalRows - VISIBLE_ROWS);

        // STEP 1: Drain mouse-wheel deltas FIRST so the wheel takes precedence
        // over the keyboard "scroll-into-view" logic below. (Used to be the
        // other way around — keyboard logic ran first and snapped scroll
        // back to wherever the cursor was, undoing every wheel scroll.)
        //
        // Fractional accumulator handles laptop touchpads which report sub-
        // notch values (e.g. 0.1 per swipe segment). Threshold = 1.0 full
        // notch; below that we just stash and wait. CRITICAL: we previously
        // tried to "snap" 0.5+ to a full notch, which caused oscillation
        // between +1 and -1 once the accumulator went negative. The new
        // behaviour is simpler: only trigger when |accumulator| >= 1.0,
        // and preserve sign through subtraction.
        boolean wheelScrolled = false;
        if (Math.abs(pendingScrollDelta) >= 1.0) {
            // truncate toward zero — for +1.6 → 1, for -1.6 → -1
            int notches = (int) pendingScrollDelta;
            pendingScrollDelta -= notches;   // keep fractional remainder
            wheelScrolled = true;
            int prevTop = moduleScrollTop;
            // GLFW: vScroll +1 = scroll wheel up = move view UP (smaller
            // index). Subtract because moduleScrollTop grows downward.
            moduleScrollTop = Math.max(0, Math.min(
                moduleScrollTop - notches * GRID_COLS,
                maxScrollRow * GRID_COLS));
            // Drag the keyboard cursor along so the next arrow press doesn't
            // snap the scroll back. Pin it to the top-left card of the new
            // visible region.
            modSelected = modulesBase + moduleScrollTop;
            // If the modules grid couldn't move (small category fits in the
            // visible window OR we're at the top/bottom), redirect the wheel
            // to scroll the mod list at the bottom of the panel instead.
            // Otherwise the wheel feels broken when on a small category.
            boolean modulesClamped = (prevTop == moduleScrollTop);
            if (modulesClamped && MODS.size() > VISIBLE_MODS) {
                int prevModTop = modScrollTop;
                int modMax = Math.max(0, MODS.size() - VISIBLE_MODS);
                modScrollTop = Math.max(0, Math.min(
                    modScrollTop - notches, modMax));
                if (scrollEventCount <= 20) {
                    System.out.println("[ShadowHud][drain] notches=" + notches
                        + " modules CLAMPED → mod-list: "
                        + prevModTop + "→" + modScrollTop + " (max=" + modMax + ")");
                }
            } else if (scrollEventCount <= 20) {
                System.out.println("[ShadowHud][drain] notches=" + notches
                    + " top: " + prevTop + "→" + moduleScrollTop
                    + " (max=" + (maxScrollRow * GRID_COLS) + ")"
                    + (modulesClamped ? "  CLAMPED" : ""));
            }
        }

        // STEP 2: keyboard "scroll-into-view" — only kicks in if the cursor
        // is OUTSIDE the visible window. Skipped when the wheel just scrolled
        // (we already adjusted modSelected to track), and when the cursor is
        // already visible (no work needed).
        if (!wheelScrolled && modSelected >= modulesBase && modSelected < modListBase) {
            int idxInModules = modSelected - modulesBase;
            int selRow = idxInModules / GRID_COLS;
            int topRow = moduleScrollTop / GRID_COLS;
            if (selRow < topRow) {
                topRow = selRow;
            } else if (selRow >= topRow + VISIBLE_ROWS) {
                topRow = selRow - VISIBLE_ROWS + 1;
            }
            moduleScrollTop = topRow * GRID_COLS;
        }
        moduleScrollTop = Math.max(0, Math.min(moduleScrollTop, maxScrollRow * GRID_COLS));

        // Keep selection within view of the mod list — but ONLY when the
        // keyboard cursor is actually IN the mod list. Otherwise listSel
        // pins to 0 and clamps modScrollTop = 0 every frame, undoing every
        // wheel-fall-through scroll the moment it happens (the bug that
        // made "scroll all the way down" stop short).
        if (modSelected >= modListBase) {
            int listSel = modSelected - modListBase;
            if (listSel < modScrollTop)                modScrollTop = listSel;
            if (listSel >= modScrollTop + VISIBLE_MODS) modScrollTop = listSel - VISIBLE_MODS + 1;
        }
        modScrollTop = Math.max(0, Math.min(modScrollTop, Math.max(0, MODS.size() - VISIBLE_MODS)));

        // --- panel chrome — fresh modern style -------------------------
        // 1. Outer drop shadow (3 layers fading out) gives the panel real
        //    depth, separates it from the dimmed game world behind.
        // 2. Rounded panel body with a subtle vertical gradient.
        // 3. Thin 1-px red accent line under the title band (no thick borders
        //    cluttering the edges — Vape Lite-style minimal chrome).
        for (int s = 1; s <= 3; s++) {
            int alpha = 96 - s * 24;
            int shadow = (alpha << 24);
            roundedRect(dc, x - s, y + s, x + W + s, y + bodyH + s, shadow);
        }
        // Body — slightly lighter at the top, darker at the bottom for a
        // soft vertical gradient.
        roundedRect(dc, x, y, x + W, y + bodyH, 0xF01A1F2A);
        // Top half a hair lighter for the gradient feel
        roundedRect(dc, x + 2, y + 2, x + W - 2, y + bodyH / 2, 0x18FFFFFF);
        // Header band — subtle red wash, no harsh line
        roundedRect(dc, x + 2, y + 1, x + W - 2, y + 19, 0x33700818);
        // Single thin 1-px red accent under the title (no top border — the
        // shadow gives separation enough)
        fill(dc, x + 12, y + 19, x + W - 12, y + 20, themeAccent());
        // Subtle red glow line below it for "lit edge" effect
        fill(dc, x + 12, y + 20, x + W - 12, y + 21, (themeAccent() & 0x00FFFFFF) | 0x55000000);

        // --- title — SHADOW CLIENT with red drop-shadow + author tag ---
        // TitleAnim module rotates the title color through red→orange→
        // yellow→green→cyan→blue→purple at 0.5 Hz so the menu's logo
        // pulses gently. Disabled by default; enable from menu → Display.
        // === Title bar — fresh, more breathing room ===================
        // Square red icon left of the wordmark — gives a "logo" anchor point.
        roundedRect(dc, x + 8, y + 5, x + 18, y + 15, themeAccent());
        roundedRect(dc, x + 9, y + 6, x + 17, y + 8, 0x66FFFFFF);  // gloss
        // S monogram in the icon
        draw(dc, font, "§l§fS", x + 12, y + 7, 0xFFFFFFFF);

        boolean titleAnim = modOn("TitleAnim", false);
        if (titleAnim) {
            String[] cycle = {"§c", "§6", "§e", "§a", "§b", "§9", "§d"};
            int phase = (int) ((System.currentTimeMillis() / 500) % cycle.length);
            String c1 = cycle[phase];
            String c2 = cycle[(phase + 3) % cycle.length];
            draw(dc, font, "§l" + c1 + "SHADOW§r " + c2 + "§lCLIENT", x + 22, y + 6, 0xFFFFFFFF);
        } else {
            draw(dc, font, "§l§cSHADOW§r §fCLIENT", x + 22, y + 6, 0xFFFFFFFF);
        }
        // Author credit — smaller, dimmer, beside the wordmark
        int titleEndX = x + 22 + ("SHADOW CLIENT".length() * 6) + 6;
        draw(dc, font, "§7§oby Edison", titleEndX, y + 6, 0xFFFFFFFF);

        // Live module-count stat: "X / Y" (enabled / total) — Lunar shows
        // a similar counter so users can see at a glance how many features
        // they have on. Color-cued: bright green count, dim total. Cached
        // so we don't iterate the 84-entry MODULES map every render frame —
        // recomputed only when something toggles (saveConfig() invalidates).
        if (cachedOnCount < 0) {
            int n = 0;
            for (Boolean b : MODULES.values()) if (b != null && b) n++;
            cachedOnCount = n;
        }
        // Live status pill — green dot + count, sits in the title bar's
        // top-right. Reads as a status indicator instead of just text.
        String stat = cachedOnCount + "§8/§7" + MODULES.size();
        int statW = (Integer.toString(cachedOnCount).length()
                   + Integer.toString(MODULES.size()).length() + 1) * 6;
        int statX = x + W - statW - 14;
        // Green status dot — gentle pulse so the menu reads as "live".
        // 1.5 Hz sin wave with a small alpha bloom around the dot.
        double pulsePhase = (System.currentTimeMillis() % 1500) / 1500.0 * Math.PI * 2;
        int pulseAlpha = (int) (0x22 + 0x18 * (Math.sin(pulsePhase) * 0.5 + 0.5));
        // Outer glow halo (1px out)
        roundedRect(dc, statX - 11, y + 6,  statX - 2, y + 15, (pulseAlpha << 24) | 0x22DD55);
        roundedRect(dc, statX - 9,  y + 8,  statX - 4, y + 13, 0xFF22DD55);
        roundedRect(dc, statX - 8,  y + 8,  statX - 6, y + 9,  0x88FFFFFF);
        draw(dc, font, "§a" + stat, statX, y + 6, 0xFFFFFFFF);

        // Preset bar removed in the Vape Lite redesign — categories now live
        // on a left sidebar, and presets can still be applied via Ctrl+D
        // (Defaults) keyboard shortcut. The visual button row was removed
        // because it competed for attention with the category nav.
        int presetY = y + 18;     // anchor for downstream layout (no actual bar drawn)

        // === Top toolbar — always-visible search + 3 small buttons =====
        // The search input now lives PERMANENTLY at the top instead of being
        // hidden behind a "Search" button — major UX win, no extra click to
        // start filtering. Just type. Three small buttons sit to its right:
        // Show (filter on/off), Sort, Save (snapshot).
        int tabsY = y + 24;
        // Left half = search bar, right half = 3 buttons
        int searchW = (W - 12) * 6 / 10 - 3;          // ~60% of toolbar width
        int btnsRowW = (W - 12) - searchW - 3;        // remaining 40%
        int actionBtnW = (btnsRowW - 4) / 3;          // 3 buttons, 2-px gaps
        int searchX = x + 6;
        int searchY = tabsY;
        int searchH = TAB_BAR_H;

        // --- Search bar (always visible) ---
        boolean searchHover = hitTest(searchX, searchY, searchX + searchW, searchY + searchH);
        if (searchHover && leftClickEdge) {
            // Click anywhere in the search bar starts typing.
            menuSearchActive = true;
            leftClickEdge = false;
        }
        int searchBg = menuSearchActive ? 0xFF1F1422
                     : (searchHover ? 0xCC1A1422 : 0x80101018);
        roundedRect(dc, searchX, searchY, searchX + searchW, searchY + searchH, searchBg);
        // 1-px red accent on left when active so the input "lights up"
        if (menuSearchActive) {
            fill(dc, searchX, searchY + 2, searchX + 2, searchY + searchH - 2, 0xFFFF2030);
        }
        // Render the magnifying-glass icon + placeholder/typed text
        if (menuSearchBuf.length() == 0) {
            String hint = menuSearchActive ? "Type to filter…" : "Click to search modules";
            draw(dc, font, "§7" + hint, searchX + 6, searchY + 3, 0xFFFFFFFF);
        } else {
            // Typed text in white, plus a blinking caret when active
            String typed = "§f" + menuSearchBuf;
            if (menuSearchActive && (System.currentTimeMillis() / 500) % 2 == 0) {
                typed += "§c|";
            }
            draw(dc, font, typed, searchX + 6, searchY + 3, 0xFFFFFFFF);
        }

        // --- 3 action buttons to the right of the search bar ---
        int actionsX = searchX + searchW + 3;
        String[] btnLabels = new String[3];
        String[] btnTooltips = new String[6];
        btnLabels[0]   = menuEnabledOnly ? "On Only" : "All";
        btnTooltips[0] = "Show: " + btnLabels[0];
        btnTooltips[1] = "Click to toggle between showing all modules vs only enabled";
        btnLabels[1]   = MENU_SORT_LABELS[menuSortMode];
        btnTooltips[2] = "Sort: " + btnLabels[1];
        btnTooltips[3] = "Click to cycle: Default / A-Z / Enabled First";
        btnLabels[2]   = userSnapshot != null ? "Save ↺" : "Save";
        btnTooltips[4] = btnLabels[2];
        btnTooltips[5] = "Left-click: save current state. Right-click: restore";

        for (int b = 0; b < 3; b++) {
            int tx = actionsX + b * (actionBtnW + 2);
            int ty = tabsY;
            boolean btnHover = hitTest(tx, ty, tx + actionBtnW, ty + TAB_BAR_H);
            if (btnHover) {
                hoverModuleName = btnTooltips[b * 2];
                hoverModuleDesc = btnTooltips[b * 2 + 1];
            }
            boolean active = false;
            if (b == 0) {
                active = menuEnabledOnly;
                if (btnHover && leftClickEdge) {
                    menuEnabledOnly = !menuEnabledOnly;
                    moduleScrollTop = 0; leftClickEdge = false;
                }
            } else if (b == 1) {
                active = menuSortMode != 0;
                if (btnHover && leftClickEdge) {
                    menuSortMode = (menuSortMode + 1) % MENU_SORT_LABELS.length;
                    moduleScrollTop = 0; leftClickEdge = false;
                }
            } else { // b == 2 — Save
                active = userSnapshot != null;
                if (btnHover && leftClickEdge) {
                    saveUserSnapshot();
                    leftClickEdge = false;
                }
            }
            int bg = active ? 0xFFAF121A : (btnHover ? 0xC81F0F12 : 0x80101018);
            if (b == 2 && userSnapshot != null) bg = btnHover ? 0xFF26C658 : 0xFF1FA94B;
            roundedRect(dc, tx, ty, tx + actionBtnW, ty + TAB_BAR_H, bg);
            String lbl = btnLabels[b];
            int labelX = tx + Math.max(2, (actionBtnW - lbl.length() * 6) / 2);
            String prefix = (active || btnHover) ? "§l§f" : "§f";
            draw(dc, font, prefix + lbl, labelX, ty + 3, 0xFFFFFFFF);
        }

        // --- modules section (Lunar-style cards, scrollable) ---------
        int rowY = tabsY + TAB_BAR_H + 4;
        // Section header with left red stripe + underline
        fill(dc, x + 4, rowY, x + 8, rowY + 7, 0xFFAF121A);
        // Re-use the cached on-count — title bar already pays the iteration
        // cost once per render and invalidates on saveConfig().
        int hdrOnCount = cachedOnCount < 0 ? 0 : cachedOnCount;
        String filterLabel = menuCategoryFilter.isEmpty() ? "all" : menuCategoryFilter;
        StringBuilder hdr = new StringBuilder();
        hdr.append("§l§cHUD MODULES §8(§a").append(hdrOnCount)
           .append("§7/§8").append(MODULES.size()).append(" on, §f")
           .append(filteredCount).append("§8 visible §c").append(filterLabel);
        if (menuEnabledOnly) hdr.append(" · on-only");
        if (menuSearchBuf.length() > 0)
            hdr.append(" · search §f").append(menuSearchBuf).append("§c_§8");
        hdr.append("§8)");
        draw(dc, font, hdr.toString(), x + 12, rowY, 0xFFFFFFFF);
        fill(dc, x + 12, rowY + 9, x + W - 12, rowY + 10, 0x66AF121A);
        rowY += 10;
        int modulesTopY = rowY;

        // === LEFT CATEGORY SIDEBAR — polished ============================
        // Each row: small colored dot (category-specific) + label. Active
        // category gets a brighter background + bold white text + a glowing
        // red left bar. Hover lifts the bg subtly. Activity dot in the corner.
        {
            int sbX = x + 6;
            int sbY = modulesTopY;
            int sbW = SIDEBAR_W - 4;
            int catBtnH = 28;     // taller — fits the count badge under label
            String[] sideLabels = {"All","Display","World","Inv","Combat","Server","Utility"};
            // Category-specific accent colors for the dots
            int[] catDots = {
                0xFFAF121A,   // All — brand red
                0xFFE07090,   // Display — pink
                0xFF55D67A,   // World — green
                0xFFD8C75A,   // Inventory — yellow
                0xFFEF4444,   // Combat — bright red
                0xFF6090E0,   // Server — blue
                0xFFB070D8,   // Utility — purple
            };
            for (int t = 0; t < MENU_CATEGORIES.length && t < sideLabels.length; t++) {
                String catKey = (t == 0) ? "" : MENU_CATEGORIES[t];
                int btnY = sbY + t * (catBtnH + 2);
                int btnY2 = btnY + catBtnH;
                if (btnY2 > sbY + modulesH) break;

                boolean isSel = catKey.equals(menuCategoryFilter);
                boolean catHover = hitTest(sbX, btnY, sbX + sbW, btnY2);
                if (catHover && leftClickEdge) {
                    menuCategoryFilter = catKey;
                    moduleScrollTop = 0;
                    leftClickEdge = false;
                }
                if (catHover) {
                    int catTotal = 0, catOn = 0;
                    String thisCatKey = (t == 0) ? null : MENU_CATEGORIES[t];
                    for (Map.Entry<String, Boolean> me : MODULES.entrySet()) {
                        if (thisCatKey == null || thisCatKey.equals(MODULE_CAT.get(me.getKey()))) {
                            catTotal++;
                            if (Boolean.TRUE.equals(me.getValue())) catOn++;
                        }
                    }
                    hoverModuleName = MENU_CATEGORIES[t]
                        + " §8(§a" + catOn + "§8/§7" + catTotal + "§8 on)";
                    hoverModuleDesc = (t == 0)
                        ? "Show all modules from every category"
                        : "Show only " + MENU_CATEGORIES[t] + " modules";
                }
                int bg;
                if (isSel)         bg = 0xE53A1620;
                else if (catHover) bg = 0xCC1E1422;
                else               bg = 0x60101018;
                roundedRect(dc, sbX, btnY, sbX + sbW, btnY2, bg);
                if (isSel) {
                    fill(dc, sbX, btnY + 2, sbX + 2, btnY2 - 2, 0xFFFF2030);
                    fill(dc, sbX + 2, btnY + 4, sbX + 3, btnY2 - 4, 0x66FF2030);
                } else if (catHover) {
                    fill(dc, sbX, btnY + 4, sbX + 2, btnY2 - 4, 0xCCFF2030);
                }
                // Category accent dot
                int dotColor = catDots[t];
                if (!isSel && !catHover) dotColor = (dotColor & 0x00FFFFFF) | 0x80000000;
                roundedRect(dc, sbX + 6, btnY + 9, sbX + 11, btnY + 14, dotColor);
                // Label — bold white if selected, slightly dim white otherwise
                String lbl = sideLabels[t];
                String prefix = isSel ? "§l§f" : (catHover ? "§f" : "§7");
                draw(dc, font, prefix + lbl,
                    sbX + 14, btnY + (catBtnH - 8) / 2 - 3, 0xFFFFFFFF);
                // Count badge underneath — "3 / 15" enabled vs total in this
                // category. Always visible so the user can see at a glance
                // how much is active without clicking each tab.
                int catTotal = 0, catOn = 0;
                String thisCatKey = (t == 0) ? null : MENU_CATEGORIES[t];
                for (Map.Entry<String, Boolean> me : MODULES.entrySet()) {
                    if (thisCatKey == null || thisCatKey.equals(MODULE_CAT.get(me.getKey()))) {
                        catTotal++;
                        if (Boolean.TRUE.equals(me.getValue())) catOn++;
                    }
                }
                String countStr = "§a" + catOn + "§8/§7" + catTotal;
                draw(dc, font, countStr,
                    sbX + 14, btnY + (catBtnH - 8) / 2 + 5, 0xFFFFFFFF);
                // Activity dot — green pip in top-right when any module on
                if (t > 0 && catOn > 0) {
                    roundedRect(dc, sbX + sbW - 7, btnY + 4,
                         sbX + sbW - 3, btnY + 8, 0xFF22DD55);
                }
            }
        }

        // Build the filtered + sorted module list.
        java.util.List<Map.Entry<String, Boolean>> moduleList = visibleModuleList();
        // Empty-state hint — when the filter (category + search + on-only)
        // happens to match nothing, draw a centered explanation so the user
        // doesn't think the menu is broken.
        if (moduleList.isEmpty()) {
            String hint = "§7No modules match this filter";
            String sub  = menuSearchBuf.length() > 0
                ? "§8try clearing the search (/ to toggle)"
                : (menuEnabledOnly
                    ? "§8disable §c★on §8 to see all modules in this category"
                    : "§8try a different category tab (§cCtrl+1§8 = All)");
            int hintW = displayWidth(hint);
            int subW  = displayWidth(sub);
            int cy    = modulesTopY + modulesH / 2 - 12;
            draw(dc, font, hint, x + (W - hintW) / 2, cy,      0xFFFFFFFF);
            draw(dc, font, sub,  x + (W - subW)  / 2, cy + 12, 0xFFFFFFFF);
        }
        int modEnd = Math.min(moduleList.size(), moduleScrollTop + VISIBLE_MODULES);
        for (int idx = moduleScrollTop; idx < modEnd; idx++) {
            Map.Entry<String, Boolean> e = moduleList.get(idx);
            boolean selected = (modulesBase + idx == modSelected);
            boolean on       = e.getValue();
            String  name     = e.getKey();
            String  desc     = MODULE_DESC.getOrDefault(name, "");
            String  cat      = MODULE_CAT.getOrDefault(name, "");

            // Position in the grid relative to the visible window.
            // Cards start AFTER the left sidebar (SIDEBAR_W wide).
            int rel = idx - moduleScrollTop;
            int col = rel % GRID_COLS;
            int row = rel / GRID_COLS;
            int cardL = x + 6 + SIDEBAR_W + col * (CARD_W + CARD_GAP);
            int cardR = cardL + CARD_W;
            int cardT = modulesTopY + row * (CARD_H + CARD_GAP);
            int cardB = cardT + CARD_H;

            boolean hover = hitTest(cardL, cardT, cardR, cardB);
            // Pin button (top-right corner of card) — 8x8 px hit zone for
            // the ★ icon. Clicked separately from the body so users can
            // pin without toggling.
            int pinL = cardR - 12, pinR = cardR - 2;
            int pinT = cardT + 1,  pinB = cardT + 11;
            boolean pinHover = hitTest(pinL, pinT, pinR, pinB);
            boolean clickedPin = pinHover && leftClickEdge;
            if (clickedPin) {
                togglePin(name);
                leftClickEdge = false;
            }
            // Hover tooltip: capture the hovered module's name + desc so the
            // footer can show "ModName — description". Last hover wins, so
            // overlapping cards aren't an issue (the renderer iterates in
            // grid order). Helps accessibility — the user doesn't have to
            // memorize which module is which.
            if (pinHover) {
                hoverModuleName = name;
                hoverModuleDesc = pinnedModules.contains(name)
                    ? "Click to unpin from top"
                    : "Click to pin to top of list";
            } else if (hover) {
                hoverModuleName = name;
                hoverModuleDesc = desc;
            }
            // === Single-action card =====================================
            // The whole card is one big toggle button. Click ANYWHERE on the
            // row — icon, name, description, pill — and it flips on/off.
            // Old behavior had a confusing dual mode (icon cycled variants,
            // rest toggled), which made simple toggling unpredictable. Now
            // it's just one click → one action.
            // Center the icon vertically inside the (now 44 px tall) card.
            int iconL = cardL + 6;
            int iconT = cardT + (CARD_H - 30) / 2;
            int iconR = iconL + 30;
            int iconB = iconT + 30;
            int pillW = 34, pillH = 16;
            int pillR = cardR - 12;
            int pillL = pillR - pillW;
            int pillT = cardT + (CARD_H - pillH) / 2;
            int pillB = pillT + pillH;
            // Gear icon — every module gets one now. The default panel shows
            // description, HUD-position override, and Active toggle even when
            // a module has no specific settings, so it's always useful.
            boolean configurable = true;
            int gearW = 16, gearH = 16;
            int gearR = pillL - 6;
            int gearL = gearR - gearW;
            int gearT = cardT + (CARD_H - gearH) / 2;
            int gearB = gearT + gearH;
            boolean gearHover = configurable && hitTest(gearL, gearT, gearR, gearB);
            if (gearHover) {
                hoverModuleName = name + " §8(config)";
                hoverModuleDesc = "Click the gear to open the settings panel";
            }
            if (gearHover && leftClickEdge) {
                configPanelModule = name;
                configPanelOpenedAtMs = System.currentTimeMillis();
                configPanelScroll = 0;
                leftClickEdge = false;
                // Critical: also clear the SAVED click edge so the panel
                // (rendered later this frame) doesn't see the same click and
                // immediately fire its "click-outside-to-close" handler.
                savedLeftClickEdge = false;
            }
            boolean clickedBtn1 = false;     // legacy variable, kept false
            boolean clickedBtn2 = false;
            // Right-click anywhere on the card → open config panel (shortcut).
            if (hover && rightClickEdge) {
                configPanelModule = name;
                configPanelOpenedAtMs = System.currentTimeMillis();
                configPanelScroll = 0;
                rightClickEdge = false;
            }
            boolean clickedAnywhere = hover && leftClickEdge;
            if (clickedAnywhere) {
                modSelected = modulesBase + idx;
                boolean wasOn = Boolean.TRUE.equals(e.getValue());
                // Single-action: clicking anywhere on the card just toggles.
                // Variant cycling (Wings particle, Trail color, etc.) moved
                // to a separate code path — eventually a right-click or a
                // small "..." button. Keeps the primary click predictable.
                e.setValue(!e.getValue());
                try { playUiClick(); } catch (Throwable ignored) {}
                on = e.getValue();
                saveConfig();
                leftClickEdge = false;

                // Toast on every toggle so the user gets immediate feedback
                // ("did my click register?"). For cosmetic modules (Wings,
                // Cape, etc.) this is critical: the visual rendering happens
                // in 3D space behind the player, so without a toast the user
                // can't tell from the menu alone whether the click took
                // effect. Also a debug-friendly trace — appears in the chat
                // overlay each time you flip something.
                String mname = e.getKey();
                if (wasOn != on) {
                    String state = on ? "§a✓ ON" : "§7○ off";
                    flashToast(state + " §f" + mname);
                    if ("Wings".equals(mname) || "AngelWings".equals(mname)
                        || "Cape".equals(mname)) {
                        System.out.println("[ShadowHud][Cosmetic] " + mname + " toggled "
                            + (on ? "ON" : "OFF"));
                    }
                }
            }

            // ============== VAPE LITE-STYLE ROW =========================
            // Horizontal layout (left → right):
            //   [4..34]   colored icon square (red when ON, gray when OFF)
            //   [38..pillL-6]  module name (bold) + description (dim) on one line
            //   [pillL..pillR] pill-shaped on/off toggle (red track when on)

            // ---- Row background (rounded) --------------------------------
            // Subtle two-tone: enabled rows get a faint red wash, disabled
            // rows are flat near-black. Hover lifts the brightness slightly.
            int bg = on ? 0xEE1A1216 : 0xDD0E0E11;
            if (hover) bg = on ? 0xFF2A1217 : 0xEE16161B;
            roundedRect(dc, cardL, cardT, cardR, cardB, bg);

            // ---- Selected/hover left-edge accent --------------------------
            // Vape Lite has a thin vertical bar on the left of selected rows.
            if (selected) {
                fill(dc, cardL, cardT, cardL + 2, cardB, themeAccentLight());
            } else if (hover) {
                fill(dc, cardL, cardT, cardL + 2, cardB,
                     (themeAccentLight() & 0x00FFFFFF) | 0x99000000);
            }
            // Bottom 1px hairline divider between rows for that "list" feel
            fill(dc, cardL + 2, cardB - 1, cardR, cardB, 0x33000000);

            // ---- Icon square (left) --------------------------------------
            // Bright red when ON (the brand color), gray box with category
            // accent when OFF. Click cycles variants for cosmetics.
            boolean pinned = pinnedModules.contains(name);
            int catColor = categoryColor(cat);
            int iconBg, iconAccent;
            if (on) {
                int hot = themeAccentLight();
                iconBg     = clickedBtn1 || hitTest(iconL, iconT, iconR, iconB)
                             ? hot : themeAccent();
                iconAccent = hot;
            } else {
                iconBg     = clickedBtn1 || hitTest(iconL, iconT, iconR, iconB)
                             ? 0xFF26262C : 0xFF1A1A1E;
                iconAccent = catColor;
            }
            roundedRect(dc, iconL, iconT, iconR, iconB, iconBg);
            // Top accent stripe colored by category (or red glow when on)
            fill(dc, iconL + 2, iconT, iconR - 2, iconT + 2, iconAccent);
            // Inner highlight (gives slight 3D feel)
            if (on) {
                fill(dc, iconL + 1, iconT + 2, iconR - 1, iconT + 3, 0x44FFFFFF);
            }
            // Center a single letter (first of category name) inside the icon —
            // quick visual cue without needing icon textures. Scaled-up font
            // hack: just draw the letter big-and-centered.
            String iconLetter = catAbbrev(cat).substring(0, 1);
            int letterX = iconL + (30 - 6) / 2;
            int letterY = iconT + (30 - 8) / 2;
            draw(dc, font, "§l§f" + iconLetter, letterX + 1, letterY + 1, 0xFF000000);
            draw(dc, font, "§l§f" + iconLetter, letterX, letterY, 0xFFFFFFFF);

            // ---- Pin star (top-right of icon, small) ---------------------
            if (pinned) {
                draw(dc, font, "§e★", iconR - 6, iconT - 1, 0xFFFFFFFF);
            }

            // ---- Module name + description (middle column) ---------------
            int textL = iconR + 8;
            int textCap = pillL - textL - 6;
            // Compute variant tag for cosmetics with a cycle (Wings particle,
            // Map size, etc.). Inlined into the description.
            String tag = null;
            if (on) {
                if      ("Crosshair".equals(name)) tag = (crosshairColorIdx >= CROSSHAIR_COLORS.length)
                        ? String.format("custom #%06X", customCrosshairColor & 0xFFFFFF)
                        : CROSSHAIR_SHAPE_NAMES[crosshairShape];
                else if ("Map".equals(name))       tag = MAP_SIZE_NAMES[Math.max(1, Math.min(MAP_SIZE_NAMES.length - 1, mapSizeIdx))];
                else if ("Keystrokes".equals(name))tag = KEYS_POS_NAMES[Math.max(0, Math.min(KEYS_POS_NAMES.length - 1, keysPosIdx))];
                else if ("Effects".equals(name))   tag = "≤" + EFFECTS_LIMITS[Math.max(0, Math.min(EFFECTS_LIMITS.length - 1, effectsLimitIdx))];
                else if ("Trail".equals(name))     tag = TRAIL_PARTICLES[Math.max(0, Math.min(TRAIL_PARTICLES.length-1, trailIdx))][0];
                else if ("Halo".equals(name))      tag = TRAIL_PARTICLES[Math.max(0, Math.min(TRAIL_PARTICLES.length-1, haloIdx))][0];
                else if ("Wings".equals(name))     tag = TRAIL_PARTICLES[Math.max(0, Math.min(TRAIL_PARTICLES.length-1, wingsParticleIdx))][0];
                else if ("AngelWings".equals(name))tag = TRAIL_PARTICLES[Math.max(0, Math.min(TRAIL_PARTICLES.length-1, angelWingsIdx))][0];
                // Cape no longer cycles particle variants — texture-only now.
                else if ("Fairies".equals(name))   tag = TRAIL_PARTICLES[Math.max(0, Math.min(TRAIL_PARTICLES.length-1, fairiesIdx))][0];
                else if ("Footsteps".equals(name)) tag = TRAIL_PARTICLES[Math.max(0, Math.min(TRAIL_PARTICLES.length-1, footstepsIdx))][0];
                else if ("BowTrail".equals(name))  tag = TRAIL_PARTICLES[Math.max(0, Math.min(TRAIL_PARTICLES.length-1, bowTrailIdx))][0];
            }
            // Module name — bold white, top of the text column
            int maxNameChars = Math.max(4, (textCap / 6));
            String displayName = name;
            if (displayName.length() > maxNameChars)
                displayName = displayName.substring(0, maxNameChars - 1) + "…";
            String nameTxt = on ? "§l§f" + displayName : "§l§f" + displayName;
            // Drop shadow + main — y positions tuned for 44 px card height.
            draw(dc, font, "§0§l" + displayName, textL + 1, cardT + 12, 0xFF000000);
            draw(dc, font, nameTxt, textL, cardT + 11, 0xFFFFFFFF);

            // Description — gray, single line, beneath the name. Truncate
            // with "…" if too long. Append variant tag as colored suffix.
            String sub = desc;
            int descMaxChars = Math.max(8, textCap / 6);
            if (tag != null) descMaxChars -= (tag.length() + 4);
            if (sub.length() > descMaxChars) {
                if (descMaxChars > 1)
                    sub = sub.substring(0, descMaxChars - 1) + "…";
                else
                    sub = "…";
            }
            // Description: brighter than §7 — was hard to read on dark cards.
            // Use a custom RGB color via drawText so it isn't constrained to
            // the §-codes. 0xFF98989E ≈ comfortable mid-gray.
            int descColor = 0xFF98989E;
            if (drawTextMethod != null) {
                String plainSub = sub;
                try {
                    drawTextMethod.invoke(dc, font, plainSub,
                        textL, cardT + 26, descColor, false);
                } catch (Throwable ignored) {}
            }
            // Variant tag (e.g. "Fire" for Trail) is appended in red after.
            if (tag != null) {
                int tagX = textL + sub.length() * 6 + 6;
                draw(dc, font, "§8· §c" + tag, tagX, cardT + 26, 0xFFFFFFFF);
            }

            // ---- Pill toggle (right side) ---------------------------------
            // Track: red filled when ON, dark gray when OFF.
            // Knob: white circle, slides to right when ON.
            int trackBg = on ? themeAccent() : 0xFF35353D;
            int trackBgHover = on ? themeAccentLight() : 0xFF44444E;
            boolean pillHover = hitTest(pillL, pillT, pillR, pillB);
            int trackColor = pillHover ? trackBgHover : trackBg;
            // Rounded pill track. Slight outer shadow when ON — gives the
            // toggle some "glow" without needing real shaders.
            if (on) {
                fill(dc, pillL - 1, pillB,     pillR + 1, pillB + 1,
                     (themeAccentLight() & 0x00FFFFFF) | 0x33000000);
            }
            roundedRect(dc, pillL, pillT, pillR, pillB, trackColor);
            // Knob (12×12) — rounded square, slides left ↔ right.
            int knobX = on ? (pillR - 14) : (pillL + 2);
            int knobY = pillT + 2;
            int knob = pillHover ? 0xFFFFFFFF : 0xFFE8E8E8;
            roundedRect(dc, knobX, knobY, knobX + 12, knobY + 12, knob);
            // ---- Gear button (configurable modules only) -----------------
            if (configurable) {
                int gbg = gearHover ? 0xFF2A1A22 : 0xFF1A1218;
                roundedRect(dc, gearL, gearT, gearR, gearB, gbg);
                // Stylized gear with 8-direction teeth + a hollow center,
                // built from fill rectangles. Slow rotation when hovered.
                int cx = gearL + gearW / 2;
                int cy = gearT + gearH / 2;
                int gcol = gearHover ? 0xFFFF2030 : 0xFFAAB4C0;
                // Time-based phase for gear rotation animation on hover
                long phase = gearHover ? (System.currentTimeMillis() / 100) % 4 : 0;
                // Body ring (outer)
                roundedRect(dc, cx - 5, cy - 5, cx + 5, cy + 5, gcol);
                // 4 cardinal teeth (or 4 diagonal teeth on alternate phases)
                if (phase % 2 == 0) {
                    fill(dc, cx - 1, gearT + 1, cx + 1, gearT + 4, gcol);   // N
                    fill(dc, cx - 1, gearB - 4, cx + 1, gearB - 1, gcol);   // S
                    fill(dc, gearL + 1, cy - 1, gearL + 4, cy + 1, gcol);   // W
                    fill(dc, gearR - 4, cy - 1, gearR - 1, cy + 1, gcol);   // E
                } else {
                    // diagonal teeth (visual rotation effect)
                    fill(dc, cx + 3, gearT + 1, cx + 5, gearT + 3, gcol);   // NE
                    fill(dc, cx - 5, gearT + 1, cx - 3, gearT + 3, gcol);   // NW
                    fill(dc, cx + 3, gearB - 3, cx + 5, gearB - 1, gcol);   // SE
                    fill(dc, cx - 5, gearB - 3, cx - 3, gearB - 1, gcol);   // SW
                }
                // Hub hole (darker)
                roundedRect(dc, cx - 2, cy - 2, cx + 2, cy + 2, gbg);
            }
        }
        // Anchor rowY at the bottom of the grid so subsequent sections render
        // at the right Y regardless of how many cards we drew this frame.
        rowY = modulesTopY + VISIBLE_ROWS * (CARD_H + CARD_GAP);

        // --- modules scroll-bar (grid-aware) -------------------------
        int gridTotalRows = (filteredCount + GRID_COLS - 1) / GRID_COLS;
        if (gridTotalRows > VISIBLE_ROWS) {
            int barX = x + W - 4;
            int listH = VISIBLE_ROWS * (CARD_H + CARD_GAP);
            fill(dc, barX, modulesTopY, barX + 2, modulesTopY + listH, 0x33FFFFFF);
            int thumbH = Math.max(8, listH * VISIBLE_ROWS / gridTotalRows);
            int topRow = moduleScrollTop / GRID_COLS;
            int thumbY = modulesTopY + listH * topRow / gridTotalRows;
            fill(dc, barX, thumbY, barX + 2, thumbY + thumbH, 0xFFAF121A);

            // Page indicator above the scrollbar — "page X / Y" so the user
            // knows where they are without having to read scrollbar position.
            int curPage = topRow / Math.max(1, VISIBLE_ROWS) + 1;
            int totPages = (gridTotalRows + VISIBLE_ROWS - 1) / VISIBLE_ROWS;
            String pageStr = curPage + "§8/§7" + totPages;
            int pageW = displayWidth(pageStr);
            draw(dc, font, "§7" + pageStr,
                 x + W - 6 - pageW, modulesTopY - 9, 0xFFFFFFFF);
        }

        // INSTALLED MODS section hidden by user request — was a list of 3rd-
        // party Fabric mods loaded alongside ShadowHud. The data structure
        // (MODS / modScrollTop / VISIBLE_MODS) is kept intact so a future
        // toggle could re-enable it, but no rendering happens.

        // --- footer with key hints (Vape Lite-style card) -------------------
        // The hint rows now sit inside their own rounded card with subtle
        // dark fill + red top accent — visually consistent with the module
        // rows above. Less "afterthought text", more "intentional UI block".
        int footTop = y + bodyH - 30;
        int footBot = y + bodyH - 4;
        roundedRect(dc, x + 6, footTop, x + W - 6, footBot, 0xE018181E);
        // Subtle red top-edge highlight on the card
        roundedRect(dc, x + 8, footTop, x + W - 8, footTop + 1, 0x66FF2030);

        if (!hoverModuleName.isEmpty()) {
            // ROW 1 (hovered card): module name + description — tooltip mode.
            String tipName = hoverModuleName;
            String tipDesc = hoverModuleDesc;
            int maxDescChars = (W - 24 - tipName.length() * 6) / 6;
            if (tipDesc.length() > maxDescChars && maxDescChars > 4)
                tipDesc = tipDesc.substring(0, maxDescChars - 1) + "…";
            draw(dc, font, "§l§f" + tipName + "§r§7 — §f" + tipDesc,
                 x + 10, y + bodyH - 24, 0xFFFFFFFF);
        } else {
            // ROW 1: top keyboard hints — F1 help is the discovery hook so
            // users don't have to memorize anything; everything else is
            // listed in the help overlay.
            draw(dc, font,
                 "§cF1§7 help  §cTab§7 cycle cat  §c↑↓←→§7 nav  §cEnter§7 toggle  §cWheel§7 scroll  §c/§7 search",
                 x + 10, y + bodyH - 24, 0xFFFFFFFF);
        }

        // Row 2: mouse + secondary actions
        draw(dc, font,
             "§cClick§7 toggle  §cOPTIONS§7 cycles  §cCtrl+S/L/D§7 snap/load/defaults  §cR Shift§7/§cEsc§7 close",
             x + 10, y + bodyH - 12, 0xFFFFFFFF);

        // ----- Visible custom cursor — drawn last so it's always on top
        // Doesn't depend on whether MC actually unlocked the system cursor;
        // we render our own at the polled mouse coords. Keeps menu clickable
        // even when vanilla mouse-look hasn't released. 9-px arrow with
        // black outline for visibility on any background.
        if (mouseX > 0 && mouseY > 0) {
            // outline (black)
            fill(dc, mouseX,     mouseY,     mouseX + 1, mouseY + 11, 0xFF000000);
            fill(dc, mouseX + 1, mouseY + 1, mouseX + 2, mouseY + 11, 0xFF000000);
            fill(dc, mouseX + 2, mouseY + 2, mouseX + 3, mouseY + 11, 0xFF000000);
            fill(dc, mouseX + 3, mouseY + 3, mouseX + 4, mouseY + 11, 0xFF000000);
            fill(dc, mouseX + 4, mouseY + 4, mouseX + 5, mouseY + 11, 0xFF000000);
            fill(dc, mouseX + 5, mouseY + 5, mouseX + 6, mouseY + 11, 0xFF000000);
            fill(dc, mouseX + 6, mouseY + 6, mouseX + 7, mouseY + 11, 0xFF000000);
            fill(dc, mouseX + 7, mouseY + 7, mouseX + 8, mouseY + 11, 0xFF000000);
            fill(dc, mouseX + 8, mouseY + 8, mouseX + 9, mouseY + 11, 0xFF000000);
            fill(dc, mouseX + 9, mouseY + 9, mouseX + 10,mouseY + 11, 0xFF000000);
            // white-ish inner fill
            fill(dc, mouseX + 1, mouseY + 1, mouseX + 2, mouseY + 10, 0xFFFFFFFF);
            fill(dc, mouseX + 2, mouseY + 2, mouseX + 3, mouseY + 10, 0xFFFFFFFF);
            fill(dc, mouseX + 3, mouseY + 3, mouseX + 4, mouseY + 10, 0xFFFFFFFF);
            fill(dc, mouseX + 4, mouseY + 4, mouseX + 5, mouseY + 10, 0xFFFFFFFF);
            fill(dc, mouseX + 5, mouseY + 5, mouseX + 6, mouseY + 10, 0xFFFFFFFF);
            fill(dc, mouseX + 6, mouseY + 6, mouseX + 7, mouseY + 10, 0xFFFFFFFF);
            fill(dc, mouseX + 7, mouseY + 7, mouseX + 8, mouseY + 10, 0xFFFFFFFF);
            fill(dc, mouseX + 8, mouseY + 8, mouseX + 9, mouseY + 10, 0xFFFFFFFF);
        }

        // ----- Toast notification (2-second flash) ---------------------------
        // Brief "✓ Saved!" / "↺ Loaded" / preset-applied confirmation that
        // appears centered near the top so the user knows their click did
        // something. Fades out via alpha after 1.5 s. Crucial accessibility
        // feedback — without it, users press Save again wondering if it took.
        long flashAge = System.currentTimeMillis() - lastFlashMs;
        if (lastFlashMs > 0 && flashAge < 2000 && !lastFlashMsg.isEmpty()) {
            int toastY = y + 36;
            int toastW = displayWidth(lastFlashMsg) + 16;
            int toastX = (sw - toastW) / 2;
            // Fade the alpha as we approach the 2 s expiry — last 500 ms ramps
            // from full to zero so it doesn't pop out of view.
            int alpha = flashAge < 1500 ? 0xE0 : (int)((2000 - flashAge) * 0xE0 / 500);
            int bgAlpha = alpha << 24;
            int edgeAlpha = (alpha & 0xFF) << 24;
            fill(dc, toastX, toastY, toastX + toastW, toastY + 14, bgAlpha | 0x051010);
            fill(dc, toastX, toastY, toastX + toastW, toastY + 1,  edgeAlpha | 0x22DD55);
            fill(dc, toastX, toastY + 13, toastX + toastW, toastY + 14, edgeAlpha | 0x22DD55);
            draw(dc, font, lastFlashMsg, toastX + 8, toastY + 3, 0xFFFFFFFF);
        }

        // ----- F1 help overlay — full keyboard reference --------------------
        // Drawn ABOVE the menu so the user gets a complete cheat-sheet without
        // having to memorize any keys. Click anywhere on the dim backdrop or
        // press F1 / Esc to close.
        if (helpOverlayOpen) {
            // Dim the rest of the screen so the help dialog reads as modal
            fill(dc, 0, 0, sw, sh, 0xCC000000);
            int hpW = 360, hpH = 230;
            int hpX = (sw - hpW) / 2;
            int hpY = (sh - hpH) / 2;
            // Panel background + 2-px red border
            fill(dc, hpX, hpY, hpX + hpW, hpY + hpH, 0xF0050103);
            fill(dc, hpX,            hpY,            hpX + hpW,    hpY + 2,        0xFFFF2030);
            fill(dc, hpX,            hpY + hpH - 2,  hpX + hpW,    hpY + hpH,      0xFFFF2030);
            fill(dc, hpX,            hpY,            hpX + 2,      hpY + hpH,      0xFFFF2030);
            fill(dc, hpX + hpW - 2,  hpY,            hpX + hpW,    hpY + hpH,      0xFFFF2030);
            // Title + author credit on the right
            draw(dc, font, "§l§cSHADOW HUD §fKEYBOARD REFERENCE", hpX + 12, hpY + 8, 0xFFFFFFFF);
            draw(dc, font, "§7made by §f§lEdison", hpX + hpW - 80, hpY + 8, 0xFFFFFFFF);
            fill(dc, hpX + 8, hpY + 20, hpX + hpW - 8, hpY + 21, 0x88FF2030);

            int hy = hpY + 28;
            String[][] rows = {
                {"§cRight Shift",        "open / close the menu"},
                {"§cF1",                 "toggle this help overlay"},
                {"§cEsc",                "close help / config panel / menu / HUD editor"},
                {"§c↑ ↓ ← →",             "navigate cards with keyboard"},
                {"§cEnter / Space",      "toggle the highlighted card"},
                {"§cTab / Shift+Tab",    "cycle category tabs"},
                {"§cCtrl + 1..7",        "jump to a specific category"},
                {"§cMouse wheel",        "scroll module list / resize HUD in editor"},
                {"§cPgUp / PgDn",        "fast scroll module list"},
                {"§cHome / End",         "jump to first / last card"},
                {"§cClick gear ⚙",       "open per-module config panel"},
                {"§cClick anywhere",     "toggle module on/off"},
                {"§cCtrl+S / Ctrl+L",    "save / load module snapshot"},
                {"§cCtrl+D",             "restore Defaults preset"},
                {"§c/ key",              "focus the search input"},
                {"§cR",                  "refresh installed-mods list"},
                {"§c=",                  "hex color picker (Crosshair / Theme)"},
                {"§cHudLayout → ✎ EDIT","drag HUD to position, scroll to scale"},
                {"§cHome key (in-world)","set / clear waypoint"},
                {"§cInsert (in-world)",  "copy XYZ to clipboard"},
            };
            for (String[] row : rows) {
                draw(dc, font, row[0], hpX + 14, hy, 0xFFFFFFFF);
                draw(dc, font, "§7" + row[1], hpX + 110, hy, 0xFFFFFFFF);
                hy += 11;
            }
            draw(dc, font, "§8click anywhere or press §cF1§8/§cEsc§8 to close",
                 hpX + 14, hpY + hpH - 14, 0xFFFFFFFF);

            // Click anywhere to dismiss. We saved the original click-edge
            // earlier so cards underneath couldn't consume it; check that
            // here so a click that landed on the overlay closes it.
            if (savedLeftClickEdge) {
                helpOverlayOpen = false;
                savedLeftClickEdge = false;
            }
        }
        // Help overlay consumed (or didn't see) the click — discard it so
        // nothing in the next frame thinks it's still pending.
        if (savedLeftClickEdge && helpOverlayOpen) {
            // overlay is still open — swallow the click anyway (paranoia)
            savedLeftClickEdge = false;
        }

        // ----- Hex color picker overlay (drawn on top of everything) --------
        if (hexInputActive) {
            int pw = 220, ph = 70;
            int px = (sw - pw) / 2;
            int py = (sh - ph) / 2;
            // background
            fill(dc, px, py, px + pw, py + ph, 0xF0050103);
            // 2-pixel red border
            fill(dc, px,        py,           px + pw,    py + 2,   0xFFFF2030);
            fill(dc, px,        py + ph - 2,  px + pw,    py + ph,  0xFFFF2030);
            fill(dc, px,        py,           px + 2,     py + ph,  0xFFFF2030);
            fill(dc, px + pw-2, py,           px + pw,    py + ph,  0xFFFF2030);
            // Title
            draw(dc, font, "§l§cCUSTOM CROSSHAIR COLOR", px + 10, py + 6, 0xFFFFFFFF);
            // Hex display with cursor
            String shown = "#" + hexInputBuf.toString();
            StringBuilder pad = new StringBuilder(shown);
            while (pad.length() < 7) pad.append("_");
            String display = pad.toString();
            // Color it with what they've typed so far
            int previewColor;
            if (hexInputBuf.length() == 6) {
                try {
                    previewColor = 0xFF000000 | (Integer.parseInt(hexInputBuf.toString(), 16) & 0xFFFFFF);
                } catch (Exception ex) { previewColor = 0xFF888888; }
            } else previewColor = 0xFF606060;
            draw(dc, font, "§f" + display, px + 10, py + 24, 0xFFFFFFFF);
            // Live swatch right of the hex string
            int sx = px + 80, sy = py + 22;
            fill(dc, sx, sy, sx + 30, sy + 12, previewColor);
            fill(dc, sx, sy, sx + 30, sy + 1,   0xFFFFFFFF);
            fill(dc, sx, sy + 11, sx + 30, sy + 12, 0xFF000000);
            fill(dc, sx, sy, sx + 1, sy + 12,   0xFFFFFFFF);
            fill(dc, sx + 29, sy, sx + 30, sy + 12, 0xFF000000);
            // Hint
            draw(dc, font, "§70-9, A-F §8• §7Enter §8save §c•§7 BkSp §8del §c•§7 Esc §8cancel",
                 px + 10, py + 50, 0xFFFFFFFF);
        }

        // ----- Per-module config panel (Meteor-style overlay) ----------
        // Drawn last so it sits on top of everything except the hex picker
        // (which is itself triggered from inside the config panel for
        // Crosshair, so the layering is correct).
        if (configPanelModule != null) {
            try {
                renderConfigPanel(dc, font, sw, sh, savedLeftClickEdge);
            } catch (Throwable t) { logOnce("ConfigPanel", t); }
        }
        // ----- Rich HSV color picker (top-most overlay) ----------------
        // Drawn AFTER the config panel so when "+" custom slot is clicked
        // inside a panel, the picker opens above the panel.
        if (colorPickerOpen) {
            try {
                renderColorPicker(dc, font, sw, sh, savedLeftClickEdge);
            } catch (Throwable t) { logOnce("ColorPicker", t); }
        }
    }

    /** Meteor-style per-module config panel. Modal overlay rendered on
     *  top of the cards. Click-edge handling: callers pass in the saved
     *  click edge (already cleared from cards), the panel's own buttons
     *  consume from it, and the rest is discarded.
     *
     *  Layout (visual):
     *    ┌─ Crosshair ──────────────── ✕ ┐
     *    │ Custom shape and color for…   │
     *    │  ── General ──────────── ▼ ─  │
     *    │   Shape    [Cross]      ↺     │
     *    │   Color    [■■■]        ↺     │
     *    │  ── Active ──────── [pill]    │
     *    └───────────────────────────────┘
     */
    private static void renderConfigPanel(Object dc, Object font, int sw, int sh,
                                          boolean clickEdge) {
        String name = configPanelModule;
        if (name == null) return;
        String desc = MODULE_DESC.getOrDefault(name, "");
        boolean on  = modOn(name, false);

        // Centered modal — bigger than before to fit per-module settings
        // comfortably with breathing room.
        int pw = 380, ph = 320;
        int px = (sw - pw) / 2;
        int py = (sh - ph) / 2;

        // ----- Fade-in animation (200 ms ease-out) ---------------------
        long elapsed = System.currentTimeMillis() - configPanelOpenedAtMs;
        float anim = Math.min(1f, elapsed / 200f);
        // ease-out cubic
        float ease = 1f - (float) Math.pow(1f - anim, 3);
        // Backdrop dim — fades in too.
        int dimAlpha = (int) (0xA8 * ease);
        fill(dc, 0, 0, sw, sh, (dimAlpha << 24));

        // Drop shadow (4 layers fading out)
        for (int s = 1; s <= 4; s++) {
            int alpha = (int) ((110 - s * 22) * ease);
            if (alpha <= 0) continue;
            roundedRect(dc, px - s, py + s, px + pw + s, py + ph + s,
                        (alpha << 24));
        }
        // Body — slightly more saturated red-tinted dark
        int bodyA = (int) (0xF0 * ease);
        roundedRect(dc, px, py, px + pw, py + ph, (bodyA << 24) | 0x161A24);
        // Top half a hair lighter for soft gradient
        int sheen = (int) (0x18 * ease);
        roundedRect(dc, px + 2, py + 2, px + pw - 2, py + ph / 2, (sheen << 24) | 0xFFFFFF);
        // Header band — wider red wash
        int hdrA = (int) (0x38 * ease);
        roundedRect(dc, px + 2, py + 1, px + pw - 2, py + 22, (hdrA << 24) | 0x700818);
        // Red accent line under header (2 px stack)
        int accentA = (int) (0xFF * ease);
        fill(dc, px + 14, py + 22, px + pw - 14, py + 23, (accentA << 24) | 0xAF121A);
        fill(dc, px + 14, py + 23, px + pw - 14, py + 24, ((int)(0x55 * ease) << 24) | 0xFF2030);

        // Title — bold module name + small category tag
        String cat = MODULE_CAT.getOrDefault(name, "");
        draw(dc, font, "§l§f" + name, px + 14, py + 7, 0xFFFFFFFF);
        if (!cat.isEmpty()) {
            int titleW = ("§l§f" + name).length() * 6;
            draw(dc, font, "§7§o· " + cat, px + 14 + titleW, py + 7, 0xFFFFFFFF);
        }
        // Close X button (top-right) — 16×16 hit zone, red on hover.
        int xBtnL = px + pw - 22, xBtnT = py + 4;
        int xBtnR = xBtnL + 16,   xBtnB = xBtnT + 16;
        boolean xBtnHover = hitTest(xBtnL, xBtnT, xBtnR, xBtnB);
        roundedRect(dc, xBtnL, xBtnT, xBtnR, xBtnB,
                    xBtnHover ? 0xFFAF121A : 0x401A1A22);
        draw(dc, font, "§l§f×", xBtnL + 6, xBtnT + 4, 0xFFFFFFFF);
        if (xBtnHover && clickEdge) {
            configPanelModule = null;
            leftClickEdge = false;
            return;
        }
        // Reset-all button (just left of the close X)
        int rBtnW = 36, rBtnH = 16;
        int rBtnL = xBtnL - rBtnW - 4;
        int rBtnT = py + 4;
        int rBtnR = rBtnL + rBtnW;
        int rBtnB = rBtnT + rBtnH;
        boolean rBtnHover = hitTest(rBtnL, rBtnT, rBtnR, rBtnB);
        if (rBtnHover) {
            hoverModuleName = "Reset " + name;
            hoverModuleDesc = "Restore this module's settings to defaults";
        }
        roundedRect(dc, rBtnL, rBtnT, rBtnR, rBtnB,
                    rBtnHover ? 0xFF26C658 : 0x401A1A22);
        draw(dc, font, rBtnHover ? "§l§fReset" : "§7Reset",
             rBtnL + 6, rBtnT + 4, 0xFFFFFFFF);
        if (rBtnHover && clickEdge) {
            resetModuleConfig(name);
            saveConfig();
            flashToast("§a↺ Reset §f" + name + "§a settings");
            leftClickEdge = false;
        }

        // Description (gray italic) — wraps to two lines if needed.
        int rowY = py + 32;
        if (!desc.isEmpty()) {
            int maxChars = (pw - 28) / 6;
            String s1 = desc, s2 = "";
            if (desc.length() > maxChars) {
                int br = desc.lastIndexOf(' ', maxChars);
                if (br <= 0) br = maxChars;
                s1 = desc.substring(0, br);
                s2 = desc.substring(br + 1);
                if (s2.length() > maxChars) s2 = s2.substring(0, maxChars - 1) + "…";
            }
            draw(dc, font, "§7§o" + s1, px + 14, rowY, 0xFFFFFFFF);
            rowY += 11;
            if (!s2.isEmpty()) {
                draw(dc, font, "§7§o" + s2, px + 14, rowY, 0xFFFFFFFF);
                rowY += 11;
            }
            rowY += 4;
        }

        // ─── General ─── divider
        drawSectionDivider(dc, font, "General", px + 10, rowY, pw - 20);
        rowY += 16;

        // Per-module settings — given a chunk of space; can scroll if needed.
        int settingsBottom = py + ph - 44;
        int innerTop = rowY;
        rowY = renderModuleSettings(dc, font, name, px + 14, rowY, pw - 28, clickEdge);
        // Universal "HUD position override" — every configurable HUD module
        // can be rerouted to a different anchor than the global stack.
        // Skip for non-HUD modules (HudLayout itself, cosmetics that aren't
        // HUD lines, etc.)
        if (!"HudLayout".equals(name) && !"Crosshair".equals(name)
                && !"Map".equals(name) && !"Keystrokes".equals(name)
                && !"Trail".equals(name) && !"Halo".equals(name)
                && !"Wings".equals(name) && !"AngelWings".equals(name)
                && !"Fairies".equals(name) && !"Footsteps".equals(name)
                && !"BowTrail".equals(name)) {
            rowY = drawAnchorOverrideRow(dc, font, name, px + 14, rowY, pw - 28, clickEdge);
            rowY = drawModuleAccentRow(dc, font, name, px + 14, rowY, pw - 28, clickEdge);
        }

        // ─── Active ─── divider + pill
        int activeY = py + ph - 32;
        drawSectionDivider(dc, font, "Active", px + 10, activeY, pw - 20);
        int aPillW = 38, aPillH = 18;
        int aPillR = px + pw - 22;
        int aPillL = aPillR - aPillW;
        int aPillT = activeY + 14;
        int aPillB = aPillT + aPillH;
        boolean aHover = hitTest(aPillL, aPillT, aPillR, aPillB);
        int aTrack = on ? (aHover ? 0xFFC8161E : 0xFFAF121A) : (aHover ? 0xFF44444E : 0xFF35353D);
        if (on) fill(dc, aPillL - 1, aPillB, aPillR + 1, aPillB + 1, 0x33FF2030);
        roundedRect(dc, aPillL, aPillT, aPillR, aPillB, aTrack);
        int aKnobX = on ? (aPillR - 16) : (aPillL + 2);
        roundedRect(dc, aKnobX, aPillT + 2, aKnobX + 14, aPillT + 16,
                    aHover ? 0xFFFFFFFF : 0xFFE8E8E8);
        if (aHover && clickEdge) {
            MODULES.put(name, !on);
            try { playUiClick(); } catch (Throwable ignored) {}
            saveConfig();
            cachedOnCount = -1;
            leftClickEdge = false;
        }
        draw(dc, font, on ? "§l§aENABLED" : "§l§7DISABLED",
             px + 14, aPillT + 5, 0xFFFFFFFF);

        // Click outside the panel = close it. Save it for last so an
        // unconsumed click on the panel body doesn't accidentally close.
        if (clickEdge && !hitTest(px, py, px + pw, py + ph)) {
            configPanelModule = null;
            leftClickEdge = false;
        }
    }

    /** "── Section ─────── ▼ ─" divider line used inside the config panel. */
    private static void drawSectionDivider(Object dc, Object font, String label,
                                           int x, int y, int w) {
        int labelW = label.length() * 6 + 8;
        int lineL = x + 14;
        int lineR = lineL + (w - labelW) / 2 - 4;
        int lineL2 = lineR + labelW;
        int lineR2 = x + w - 4;
        // Left dash, label, right dash
        fill(dc, lineL, y + 4, lineR, y + 5, 0x66AF121A);
        draw(dc, font, "§7" + label, lineR + 6, y, 0xFFFFFFFF);
        fill(dc, lineL2, y + 4, lineR2, y + 5, 0x66AF121A);
    }

    /** Per-module accent-color picker — swatches matching THEME_COLORS,
     *  click to assign this module its own color (overrides the global).
     *  Click again on the same swatch (or the X) to revert to default. */
    private static int drawModuleAccentRow(Object dc, Object font, String moduleName,
                                           int x, int rowY, int w, boolean clickEdge) {
        Integer cur = moduleColorOverrides.get(moduleName);
        // Label + reset
        draw(dc, font, "§fAccent color", x + 4, rowY + 2, 0xFFFFFFFF);
        String label = cur == null ? "§7(theme default)"
                                   : String.format("§7#%06X", cur & 0xFFFFFF);
        draw(dc, font, label, x + 4 + "Accent color".length() * 6 + 8,
             rowY + 2, 0xFFFFFFFF);
        int rstW = 16;
        int rstR = x + w - 4;
        int rstL = rstR - rstW;
        boolean rstHover = hitTest(rstL, rowY, rstR, rowY + 14);
        roundedRect(dc, rstL, rowY, rstR, rowY + 14, rstHover ? 0xFF2A1A22 : 0xFF1A1218);
        draw(dc, font, "§7↺", rstL + 5, rowY + 3, 0xFFFFFFFF);
        if (rstHover && clickEdge) {
            moduleColorOverrides.remove(moduleName);
            saveConfig();
            leftClickEdge = false;
        }
        rowY += 16;
        // Swatch row — 8 named theme colors + 1 custom slot. Click to assign.
        int swW = 22, swH = 14, gap = 3;
        int sx = x + 4;
        boolean curMatchesPreset = false;
        for (int i = 0; i < THEME_COLORS.length; i++) {
            int swX = sx + i * (swW + gap);
            roundedRect(dc, swX, rowY, swX + swW, rowY + swH, THEME_COLORS[i]);
            boolean selected = (cur != null && cur == THEME_COLORS[i]);
            if (selected) curMatchesPreset = true;
            boolean hover = hitTest(swX, rowY, swX + swW, rowY + swH);
            if (selected) {
                fill(dc, swX - 1, rowY - 1, swX + swW + 1, rowY,             0xFFFFFFFF);
                fill(dc, swX - 1, rowY + swH, swX + swW + 1, rowY + swH + 1, 0xFFFFFFFF);
                fill(dc, swX - 1, rowY, swX, rowY + swH,                     0xFFFFFFFF);
                fill(dc, swX + swW, rowY, swX + swW + 1, rowY + swH,         0xFFFFFFFF);
            } else if (hover) {
                fill(dc, swX - 1, rowY - 1, swX + swW + 1, rowY,             0x88FFFFFF);
                fill(dc, swX - 1, rowY + swH, swX + swW + 1, rowY + swH + 1, 0x88FFFFFF);
                fill(dc, swX - 1, rowY, swX, rowY + swH,                     0x88FFFFFF);
                fill(dc, swX + swW, rowY, swX + swW + 1, rowY + swH,         0x88FFFFFF);
            }
            if (hover && clickEdge) {
                moduleColorOverrides.put(moduleName, THEME_COLORS[i]);
                saveConfig();
                leftClickEdge = false;
            }
        }
        // Custom hex slot — shows current custom (or white) with "+" badge
        int customX = sx + THEME_COLORS.length * (swW + gap);
        int customColor = (cur != null && !curMatchesPreset) ? cur : 0xFFFFFFFF;
        roundedRect(dc, customX, rowY, customX + swW, rowY + swH, customColor);
        boolean customSel = (cur != null && !curMatchesPreset);
        boolean customHov = hitTest(customX, rowY, customX + swW, rowY + swH);
        if (customSel) {
            fill(dc, customX - 1, rowY - 1, customX + swW + 1, rowY,             0xFFFFFFFF);
            fill(dc, customX - 1, rowY + swH, customX + swW + 1, rowY + swH + 1, 0xFFFFFFFF);
            fill(dc, customX - 1, rowY, customX, rowY + swH,                     0xFFFFFFFF);
            fill(dc, customX + swW, rowY, customX + swW + 1, rowY + swH,         0xFFFFFFFF);
        }
        // "+" badge so the custom slot is distinguishable from the swatches
        // even when its color happens to match one (or is the default white).
        int badgeColor = brightnessLumaIsLight(customColor) ? 0xFF000000 : 0xFFFFFFFF;
        draw(dc, font, "§l+", customX + swW / 2 - 2, rowY + 3, badgeColor);
        if (customHov && clickEdge) {
            // Open the rich HSV picker. Callback writes the chosen color
            // straight into moduleColorOverrides for this module.
            int seed = cur != null ? cur : 0xFFAFAFAF;
            String captured = moduleName;
            openColorPicker(seed, picked -> {
                moduleColorOverrides.put(captured, picked);
                saveConfig();
            });
            leftClickEdge = false;
        }
        return rowY + swH + 6;
    }

    /** Glint color picker — uses the same 8-swatch palette as the theme.
     *  Custom hex slot opens the existing hex picker dialog. */
    private static int drawGlintColorRow(Object dc, Object font,
                                         int x, int rowY, int w, boolean clickEdge) {
        draw(dc, font, "§fGlint color", x + 4, rowY + 2, 0xFFFFFFFF);
        // Display current color hex
        String label = String.format("§7#%06X", cfgGlintColor & 0xFFFFFF);
        draw(dc, font, label, x + 4 + "Glint color".length() * 6 + 8,
             rowY + 2, 0xFFFFFFFF);
        // Reset
        int rstW = 16;
        int rstR = x + w - 4;
        int rstL = rstR - rstW;
        boolean rstHover = hitTest(rstL, rowY, rstR, rowY + 14);
        roundedRect(dc, rstL, rowY, rstR, rowY + 14, rstHover ? 0xFF2A1A22 : 0xFF1A1218);
        draw(dc, font, "§7↺", rstL + 5, rowY + 3, 0xFFFFFFFF);
        if (rstHover && clickEdge) {
            cfgGlintColor = 0xFFAFAFAF; saveConfig();
            leftClickEdge = false;
        }
        rowY += 16;
        // 8 theme swatches + "+" custom
        int swW = 22, swH = 14, gap = 3;
        int sx = x + 4;
        boolean curMatchesPreset = false;
        for (int i = 0; i < THEME_COLORS.length; i++) {
            int swX = sx + i * (swW + gap);
            roundedRect(dc, swX, rowY, swX + swW, rowY + swH, THEME_COLORS[i]);
            boolean selected = (cfgGlintColor == THEME_COLORS[i]);
            if (selected) curMatchesPreset = true;
            boolean hov = hitTest(swX, rowY, swX + swW, rowY + swH);
            if (selected) {
                fill(dc, swX - 1, rowY - 1, swX + swW + 1, rowY,             0xFFFFFFFF);
                fill(dc, swX - 1, rowY + swH, swX + swW + 1, rowY + swH + 1, 0xFFFFFFFF);
                fill(dc, swX - 1, rowY, swX, rowY + swH,                     0xFFFFFFFF);
                fill(dc, swX + swW, rowY, swX + swW + 1, rowY + swH,         0xFFFFFFFF);
            } else if (hov) {
                fill(dc, swX - 1, rowY - 1, swX + swW + 1, rowY,             0x88FFFFFF);
                fill(dc, swX - 1, rowY + swH, swX + swW + 1, rowY + swH + 1, 0x88FFFFFF);
                fill(dc, swX - 1, rowY, swX, rowY + swH,                     0x88FFFFFF);
                fill(dc, swX + swW, rowY, swX + swW + 1, rowY + swH,         0x88FFFFFF);
            }
            if (hov && clickEdge) {
                cfgGlintColor = THEME_COLORS[i]; saveConfig();
                leftClickEdge = false;
            }
        }
        // Custom hex slot
        int customX = sx + THEME_COLORS.length * (swW + gap);
        int customColor = curMatchesPreset ? 0xFFFFFFFF : cfgGlintColor;
        roundedRect(dc, customX, rowY, customX + swW, rowY + swH, customColor);
        boolean customSel = !curMatchesPreset;
        boolean customHov = hitTest(customX, rowY, customX + swW, rowY + swH);
        if (customSel) {
            fill(dc, customX - 1, rowY - 1, customX + swW + 1, rowY,             0xFFFFFFFF);
            fill(dc, customX - 1, rowY + swH, customX + swW + 1, rowY + swH + 1, 0xFFFFFFFF);
            fill(dc, customX - 1, rowY, customX, rowY + swH,                     0xFFFFFFFF);
            fill(dc, customX + swW, rowY, customX + swW + 1, rowY + swH,         0xFFFFFFFF);
        }
        int badgeColor = brightnessLumaIsLight(customColor) ? 0xFF000000 : 0xFFFFFFFF;
        draw(dc, font, "§l+", customX + swW / 2 - 2, rowY + 3, badgeColor);
        if (customHov && clickEdge) {
            openColorPicker(cfgGlintColor, picked -> {
                cfgGlintColor = picked;
                saveConfig();
            });
            leftClickEdge = false;
        }
        return rowY + swH + 6;
    }

    // ---- HSV color picker ---------------------------------------------
    /** Open the gradient color picker. Caller supplies the current value
     *  (so the picker initializes to it) and a callback to receive the
     *  final ARGB color when the user clicks OK. Esc / Cancel = no-op. */
    private static void openColorPicker(int currentArgb, java.util.function.IntConsumer onPicked) {
        // Convert RGB → HSV to seed the picker
        int r = (currentArgb >> 16) & 0xFF;
        int g = (currentArgb >>  8) & 0xFF;
        int b =  currentArgb        & 0xFF;
        float[] hsv = rgbToHsv(r, g, b);
        colorPickerH = hsv[0];
        colorPickerS = hsv[1];
        colorPickerV = hsv[2];
        colorPickerCallback = onPicked;
        colorPickerOpen = true;
        colorPickerDrag = 0;
    }

    /** RGB (0..255 each) → HSV (0..1 each). */
    private static float[] rgbToHsv(int r, int g, int b) {
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float v = max;
        float s = max == 0 ? 0 : (max - min) / max;
        float h;
        if (max == min) h = 0;
        else if (max == rf) h = ((gf - bf) / (max - min)) / 6f;
        else if (max == gf) h = ((bf - rf) / (max - min) + 2) / 6f;
        else                h = ((rf - gf) / (max - min) + 4) / 6f;
        if (h < 0) h += 1;
        return new float[]{h, s, v};
    }

    /** HSV (0..1) → ARGB (0xFFRRGGBB). */
    private static int hsvToArgb(float h, float s, float v) {
        h = ((h % 1f) + 1f) % 1f;
        int hi = (int) Math.floor(h * 6) % 6;
        float f = h * 6 - (float) Math.floor(h * 6);
        float p = v * (1 - s);
        float q = v * (1 - f * s);
        float t = v * (1 - (1 - f) * s);
        float r, g, b;
        switch (hi) {
            case 0: r = v; g = t; b = p; break;
            case 1: r = q; g = v; b = p; break;
            case 2: r = p; g = v; b = t; break;
            case 3: r = p; g = q; b = v; break;
            case 4: r = t; g = p; b = v; break;
            default:r = v; g = p; b = q; break;
        }
        int ri = Math.round(r * 255), gi = Math.round(g * 255), bi = Math.round(b * 255);
        return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
    }

    /** Modal HSV color picker. Called from renderMenu's tail with the
     *  saved click edge so it can consume clicks before they fall through.
     *  Cancellation: Esc, "Cancel" button, or click outside the panel. */
    private static void renderColorPicker(Object dc, Object font, int sw, int sh,
                                          boolean clickEdge) {
        if (!colorPickerOpen) return;
        // Modal geometry — centered on screen.
        int pw = 320, ph = 220;
        int px = (sw - pw) / 2;
        int py = (sh - ph) / 2;
        // Backdrop dim
        fill(dc, 0, 0, sw, sh, 0xCC000000);
        // Body + drop shadow
        for (int ssh = 1; ssh <= 3; ssh++) {
            roundedRect(dc, px - ssh, py + ssh, px + pw + ssh, py + ph + ssh,
                        ((96 - ssh * 24) << 24));
        }
        roundedRect(dc, px, py, px + pw, py + ph, 0xF0161A24);
        // Header
        roundedRect(dc, px + 2, py + 1, px + pw - 2, py + 19, 0x33700818);
        fill(dc, px + 12, py + 19, px + pw - 12, py + 20, themeAccent());
        draw(dc, font, "§l§fPick a color", px + 14, py + 6, 0xFFFFFFFF);

        // Layout:
        //   HS gradient grid: 240x128, at (px+12, py+28)
        //   V slider:          16x128, at (px+260, py+28)
        //   Preview swatch + hex: below grid
        //   OK / Cancel buttons: bottom right
        int gridX = px + 12, gridY = py + 28;
        int gridW = 240, gridH = 128;
        // Render HS gradient — coarse 4×4 cells for speed (60×32 cells).
        int cellW = 4, cellH = 4;
        int cols = gridW / cellW, rows = gridH / cellH;
        for (int gx = 0; gx < cols; gx++) {
            float h = (float) gx / (cols - 1);
            for (int gy = 0; gy < rows; gy++) {
                float s = 1f - (float) gy / (rows - 1);
                int color = hsvToArgb(h, s, colorPickerV);
                int xc = gridX + gx * cellW;
                int yc = gridY + gy * cellH;
                fill(dc, xc, yc, xc + cellW, yc + cellH, color);
            }
        }
        // Crosshair indicator on grid showing current H/S
        int chX = gridX + (int) (colorPickerH * gridW);
        int chY = gridY + (int) ((1f - colorPickerS) * gridH);
        // Draw a small white-bordered black ring
        fill(dc, chX - 4, chY - 1, chX + 4, chY,         0xFFFFFFFF);
        fill(dc, chX - 4, chY,     chX + 4, chY + 1,     0xFF000000);
        fill(dc, chX - 1, chY - 4, chX,     chY + 4,     0xFFFFFFFF);
        fill(dc, chX,     chY - 4, chX + 1, chY + 4,     0xFF000000);

        // V slider — vertical bar, 16 wide × 128 tall, at gridX + gridW + 8
        int sliderX = gridX + gridW + 8;
        int sliderW = 16;
        int sliderY = gridY;
        int sliderH = gridH;
        // Render gradient by 16 cells
        int sliderCells = 16;
        int sliderCellH = sliderH / sliderCells;
        for (int i = 0; i < sliderCells; i++) {
            float v = 1f - (float) i / (sliderCells - 1);
            int color = hsvToArgb(colorPickerH, colorPickerS, v);
            int yc = sliderY + i * sliderCellH;
            fill(dc, sliderX, yc, sliderX + sliderW, yc + sliderCellH, color);
        }
        // V indicator: triangle on the left edge
        int vY = sliderY + (int) ((1f - colorPickerV) * sliderH);
        fill(dc, sliderX - 4, vY - 1, sliderX, vY + 1, 0xFFFFFFFF);
        fill(dc, sliderX + sliderW, vY - 1, sliderX + sliderW + 4, vY + 1, 0xFFFFFFFF);

        // Click + drag handling
        boolean lmbDown = mouseButtonDown(MB_LEFT);
        boolean inGrid = (mouseX >= gridX && mouseX < gridX + gridW
                          && mouseY >= gridY && mouseY < gridY + gridH);
        boolean inSlider = (mouseX >= sliderX && mouseX < sliderX + sliderW
                            && mouseY >= sliderY && mouseY < sliderY + sliderH);
        if (clickEdge) {
            if (inGrid) colorPickerDrag = 1;
            else if (inSlider) colorPickerDrag = 2;
        }
        if (!lmbDown) colorPickerDrag = 0;
        if (colorPickerDrag == 1) {
            float h = (float) (mouseX - gridX) / Math.max(1, gridW);
            float s = 1f - (float) (mouseY - gridY) / Math.max(1, gridH);
            colorPickerH = Math.max(0f, Math.min(1f, h));
            colorPickerS = Math.max(0f, Math.min(1f, s));
        } else if (colorPickerDrag == 2) {
            float v = 1f - (float) (mouseY - sliderY) / Math.max(1, sliderH);
            colorPickerV = Math.max(0f, Math.min(1f, v));
        }

        // Preview swatch + hex display
        int previewY = py + ph - 60;
        int curArgb = hsvToArgb(colorPickerH, colorPickerS, colorPickerV);
        int swX = px + 14, swY = previewY;
        roundedRect(dc, swX, swY, swX + 40, swY + 22, curArgb);
        // 1-px white border
        fill(dc, swX - 1, swY - 1, swX + 41, swY,            0x88FFFFFF);
        fill(dc, swX - 1, swY + 22, swX + 41, swY + 23,      0x88FFFFFF);
        fill(dc, swX - 1, swY, swX, swY + 22,                0x88FFFFFF);
        fill(dc, swX + 40, swY, swX + 41, swY + 22,          0x88FFFFFF);
        // Hex label
        String hex = String.format("§7#%06X", curArgb & 0xFFFFFF);
        draw(dc, font, hex, swX + 50, swY + 7, 0xFFFFFFFF);
        // HSV/RGB readout
        int r = (curArgb >> 16) & 0xFF, g = (curArgb >> 8) & 0xFF, b = curArgb & 0xFF;
        draw(dc, font, "§7R " + r + " §7G " + g + " §7B " + b,
             swX + 110, swY + 7, 0xFFFFFFFF);

        // OK / Cancel buttons
        int btnY = py + ph - 26;
        int okW = 60, cancelW = 70;
        int okX = px + pw - okW - 12;
        int cancelX = okX - cancelW - 6;
        boolean okHover = hitTest(okX, btnY, okX + okW, btnY + 18);
        boolean cancelHover = hitTest(cancelX, btnY, cancelX + cancelW, btnY + 18);
        roundedRect(dc, okX, btnY, okX + okW, btnY + 18,
                    okHover ? themeAccentLight() : themeAccent());
        draw(dc, font, "§l§fOK", okX + okW / 2 - 6, btnY + 5, 0xFFFFFFFF);
        roundedRect(dc, cancelX, btnY, cancelX + cancelW, btnY + 18,
                    cancelHover ? 0xFF44444E : 0xFF35353D);
        draw(dc, font, "§fCancel", cancelX + cancelW / 2 - 18, btnY + 5, 0xFFFFFFFF);

        // X close (top right)
        int xL = px + pw - 18, xT = py + 4;
        boolean xHover = hitTest(xL, xT, xL + 14, xT + 14);
        roundedRect(dc, xL, xT, xL + 14, xT + 14,
                    xHover ? 0xFFAF121A : 0x401A1A22);
        draw(dc, font, "§l§f×", xL + 4, xT + 3, 0xFFFFFFFF);

        // Esc to cancel
        if (edge(KEY_ESC_HEX)) {
            colorPickerOpen = false;
            colorPickerCallback = null;
            return;
        }
        // Click handling for buttons (after gradient/drag captured their own clicks)
        if (clickEdge) {
            if (okHover || edge(KEY_ENTER)) {
                int finalColor = hsvToArgb(colorPickerH, colorPickerS, colorPickerV);
                if (colorPickerCallback != null) {
                    try { colorPickerCallback.accept(finalColor); }
                    catch (Throwable ignored) {}
                }
                colorPickerOpen = false;
                colorPickerCallback = null;
                leftClickEdge = false;
                return;
            }
            if (cancelHover || xHover) {
                colorPickerOpen = false;
                colorPickerCallback = null;
                leftClickEdge = false;
                return;
            }
            // Click outside the modal closes it (cancel).
            if (mouseX < px || mouseX > px + pw
                || mouseY < py || mouseY > py + ph) {
                colorPickerOpen = false;
                colorPickerCallback = null;
                leftClickEdge = false;
                return;
            }
        }
    }

    /** True if a packed ARGB color reads as "light" (use dark text on it). */
    private static boolean brightnessLumaIsLight(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >>  8) & 0xFF;
        int b =  argb        & 0xFF;
        // Rec.709 luma
        return (r * 2126 + g * 7152 + b * 722) / 10000 > 128;
    }

    /** Add a "HUD position override" row + offset sliders to any module's
     *  config panel. The sliders only show when an anchor override is
     *  active (otherwise they'd be no-ops since the global stack offset
     *  applies instead). */
    private static int drawAnchorOverrideRow(Object dc, Object font, String moduleName,
                                             int x, int rowY, int w, boolean clickEdge) {
        Integer cur = moduleAnchorOverrides.get(moduleName);
        String label = cur == null ? "Default (stack)"
                                   : HUD_ANCHOR_NAMES[Math.max(0, Math.min(8, cur))];
        Runnable nextAnchor = () -> {
            Integer c = moduleAnchorOverrides.get(moduleName);
            if (c == null) moduleAnchorOverrides.put(moduleName, 0);
            else if (c >= 8) moduleAnchorOverrides.remove(moduleName);
            else moduleAnchorOverrides.put(moduleName, c + 1);
        };
        Runnable resetAnchor = () -> {
            moduleAnchorOverrides.remove(moduleName);
            moduleOffsetOverrides.remove(moduleName);
        };
        rowY = drawCycleRow(dc, font, "HUD position", label,
                x, rowY, w, clickEdge, nextAnchor, resetAnchor);
        // Offset sliders only when an anchor override is active.
        if (moduleAnchorOverrides.containsKey(moduleName)) {
            int[] off = moduleOffsetOverrides.get(moduleName);
            int curX = off != null ? off[0] : 0;
            int curY = off != null ? off[1] : 0;
            rowY = drawSliderRow(dc, font, moduleName + ":offX", "Offset X",
                    -300, 300, 1, 0,
                    x, rowY, w, clickEdge,
                    () -> {
                        int[] o = moduleOffsetOverrides.get(moduleName);
                        return o != null ? o[0] : 0;
                    },
                    v -> {
                        int[] o = moduleOffsetOverrides.get(moduleName);
                        if (o == null) o = new int[]{0, 0};
                        o[0] = v;
                        moduleOffsetOverrides.put(moduleName, o);
                    });
            rowY = drawSliderRow(dc, font, moduleName + ":offY", "Offset Y",
                    -300, 300, 1, 0,
                    x, rowY, w, clickEdge,
                    () -> {
                        int[] o = moduleOffsetOverrides.get(moduleName);
                        return o != null ? o[1] : 0;
                    },
                    v -> {
                        int[] o = moduleOffsetOverrides.get(moduleName);
                        if (o == null) o = new int[]{0, 0};
                        o[1] = v;
                        moduleOffsetOverrides.put(moduleName, o);
                    });
        }
        return rowY;
    }

    /** Per-module settings rows. Returns the new rowY after the rows. */
    private static int renderModuleSettings(Object dc, Object font, String name,
                                            int x, int rowY, int w,
                                            boolean clickEdge) {
        switch (name) {
            case "Crosshair": {
                rowY = drawCycleRow(dc, font, "Shape", CROSSHAIR_SHAPE_NAMES[crosshairShape],
                                    x, rowY, w, clickEdge,
                                    () -> crosshairShape = (crosshairShape + 1) % CROSSHAIR_SHAPE_NAMES.length,
                                    () -> crosshairShape = 0);
                rowY = drawColorSwatchRow(dc, font, "Color", x, rowY, w, clickEdge);
                return rowY;
            }
            case "Map": {
                rowY = drawCycleRow(dc, font, "Size",
                        MAP_SIZE_NAMES[Math.max(1, Math.min(MAP_SIZE_NAMES.length - 1, mapSizeIdx))],
                        x, rowY, w, clickEdge,
                        () -> { mapSizeIdx++; if (mapSizeIdx >= MAP_SIZE_NAMES.length) mapSizeIdx = 1; },
                        () -> mapSizeIdx = 2);
                return rowY;
            }
            case "Keystrokes": {
                rowY = drawCycleRow(dc, font, "Position",
                        KEYS_POS_NAMES[Math.max(0, Math.min(KEYS_POS_NAMES.length - 1, keysPosIdx))],
                        x, rowY, w, clickEdge,
                        () -> keysPosIdx = (keysPosIdx + 1) % KEYS_POS_NAMES.length,
                        () -> keysPosIdx = 0);
                return rowY;
            }
            case "Effects": {
                int lim = EFFECTS_LIMITS[Math.max(0, Math.min(EFFECTS_LIMITS.length - 1, effectsLimitIdx))];
                rowY = drawCycleRow(dc, font, "Max Shown", "≤ " + lim,
                        x, rowY, w, clickEdge,
                        () -> effectsLimitIdx = (effectsLimitIdx + 1) % EFFECTS_LIMITS.length,
                        () -> effectsLimitIdx = 1);
                return rowY;
            }
            case "HP": {
                rowY = drawToggleRow(dc, font, "Show absorption", cfgHpShowAbs,
                        x, rowY, w, clickEdge, () -> cfgHpShowAbs = !cfgHpShowAbs,
                        () -> cfgHpShowAbs = true);
                rowY = drawToggleRow(dc, font, "Show armor",      cfgHpShowArmor,
                        x, rowY, w, clickEdge, () -> cfgHpShowArmor = !cfgHpShowArmor,
                        () -> cfgHpShowArmor = true);
                rowY = drawToggleRow(dc, font, "Compact (no labels)", cfgHpCompact,
                        x, rowY, w, clickEdge, () -> cfgHpCompact = !cfgHpCompact,
                        () -> cfgHpCompact = false);
                return rowY;
            }
            case "FPS": {
                rowY = drawCycleRow(dc, font, "Style", FPS_STYLE_NAMES[cfgFpsStyle],
                        x, rowY, w, clickEdge,
                        () -> cfgFpsStyle = (cfgFpsStyle + 1) % FPS_STYLE_NAMES.length,
                        () -> cfgFpsStyle = 0);
                rowY = drawToggleRow(dc, font, "Show \"FPS\" label", cfgFpsLabel,
                        x, rowY, w, clickEdge, () -> cfgFpsLabel = !cfgFpsLabel,
                        () -> cfgFpsLabel = true);
                return rowY;
            }
            case "XYZ": {
                rowY = drawCycleRow(dc, font, "Decimals", String.valueOf(cfgXyzDecimals),
                        x, rowY, w, clickEdge,
                        () -> cfgXyzDecimals = (cfgXyzDecimals + 1) % 4,
                        () -> cfgXyzDecimals = 1);
                String[] sepNames = {" /  ", " ,  ", " · "};
                rowY = drawCycleRow(dc, font, "Separator", sepNames[cfgXyzSep],
                        x, rowY, w, clickEdge,
                        () -> cfgXyzSep = (cfgXyzSep + 1) % XYZ_SEP.length,
                        () -> cfgXyzSep = 0);
                rowY = drawToggleRow(dc, font, "Compact (no XYZ label)", cfgXyzCompact,
                        x, rowY, w, clickEdge, () -> cfgXyzCompact = !cfgXyzCompact,
                        () -> cfgXyzCompact = false);
                return rowY;
            }
            case "Time": {
                rowY = drawToggleRow(dc, font, "24-hour clock", cfgTime24h,
                        x, rowY, w, clickEdge, () -> cfgTime24h = !cfgTime24h,
                        () -> cfgTime24h = true);
                rowY = drawToggleRow(dc, font, "Show seconds", cfgTimeSeconds,
                        x, rowY, w, clickEdge, () -> cfgTimeSeconds = !cfgTimeSeconds,
                        () -> cfgTimeSeconds = true);
                return rowY;
            }
            case "Compass": {
                rowY = drawCycleRow(dc, font, "Mode", COMPASS_MODE_NAMES[cfgCompassMode],
                        x, rowY, w, clickEdge,
                        () -> cfgCompassMode = (cfgCompassMode + 1) % COMPASS_MODE_NAMES.length,
                        () -> cfgCompassMode = 0);
                rowY = drawToggleRow(dc, font, "Show degrees", cfgCompassDegs,
                        x, rowY, w, clickEdge, () -> cfgCompassDegs = !cfgCompassDegs,
                        () -> cfgCompassDegs = true);
                return rowY;
            }
            case "HudLayout": {
                rowY = drawAnchorGrid(dc, font, "Position", x, rowY, w, clickEdge);
                rowY = drawSliderRow(dc, font, "HudLayout:scale", "Scale (%)",
                        50, 200, 5, 100,
                        x, rowY, w, clickEdge,
                        () -> cfgHudScale,
                        v -> cfgHudScale = v);
                rowY = drawSliderRow(dc, font, "HudLayout:opacity", "Opacity (%)",
                        10, 100, 5, 88,
                        x, rowY, w, clickEdge,
                        () -> cfgHudOpacity,
                        v -> cfgHudOpacity = v);
                rowY = drawThemeRow(dc, font, "Theme", x, rowY, w, clickEdge);
                // Edit HUD button — closes menu, enters drag-mode
                int btnH = 18;
                boolean editHover = hitTest(x, rowY, x + w - 4, rowY + btnH);
                if (editHover) {
                    hoverModuleName = "Edit HUD";
                    hoverModuleDesc = "Closes menu and lets you drag the HUD stack to any spot";
                }
                roundedRect(dc, x, rowY, x + w - 4, rowY + btnH,
                            editHover ? 0xFFC8161E : 0xFFAF121A);
                String lbl = "§l§f✎ EDIT HUD";
                int lblW = stripFormatLength(lbl) * 6;
                draw(dc, font, lbl, x + (w - 4 - lblW) / 2, rowY + 5, 0xFFFFFFFF);
                if (editHover && clickEdge) {
                    hudEditMode = true;
                    configPanelModule = null;
                    menuOpen = false;
                    leftClickEdge = false;
                    flashToast("§e✎ Drag the HUD to move • scroll to resize • Esc to save");
                }
                rowY += btnH + 6;
                // Reset offsets row
                if (cfgHudOffsetX != 0 || cfgHudOffsetY != 0) {
                    int rH = 14;
                    boolean rHover = hitTest(x, rowY, x + w - 4, rowY + rH);
                    roundedRect(dc, x, rowY, x + w - 4, rowY + rH,
                                rHover ? 0xFF2A1A22 : 0xFF1A1218);
                    draw(dc, font, "§7↺ Reset offset (" + cfgHudOffsetX + ", " + cfgHudOffsetY + ")",
                         x + 6, rowY + 4, 0xFFFFFFFF);
                    if (rHover && clickEdge) {
                        cfgHudOffsetX = 0;
                        cfgHudOffsetY = 0;
                        saveConfig();
                        leftClickEdge = false;
                    }
                    rowY += rH + 4;
                }
                return rowY;
            }
            case "Ping": {
                rowY = drawCycleRow(dc, font, "Style", PING_STYLE_NAMES[cfgPingStyle],
                        x, rowY, w, clickEdge,
                        () -> cfgPingStyle = (cfgPingStyle + 1) % PING_STYLE_NAMES.length,
                        () -> cfgPingStyle = 0);
                return rowY;
            }
            case "Memory": {
                rowY = drawCycleRow(dc, font, "Format", MEM_FMT_NAMES[cfgMemFormat],
                        x, rowY, w, clickEdge,
                        () -> cfgMemFormat = (cfgMemFormat + 1) % MEM_FMT_NAMES.length,
                        () -> cfgMemFormat = 0);
                return rowY;
            }
            case "Day": {
                rowY = drawCycleRow(dc, font, "Format", DAY_FMT_NAMES[cfgDayFormat],
                        x, rowY, w, clickEdge,
                        () -> cfgDayFormat = (cfgDayFormat + 1) % DAY_FMT_NAMES.length,
                        () -> cfgDayFormat = 0);
                return rowY;
            }
            case "Reach": {
                rowY = drawToggleRow(dc, font, "Only when targeting", cfgReachOnlyOnTarget,
                        x, rowY, w, clickEdge,
                        () -> cfgReachOnlyOnTarget = !cfgReachOnlyOnTarget,
                        () -> cfgReachOnlyOnTarget = false);
                rowY = drawToggleRow(dc, font, "Show unit (b)", cfgReachShowUnit,
                        x, rowY, w, clickEdge,
                        () -> cfgReachShowUnit = !cfgReachShowUnit,
                        () -> cfgReachShowUnit = true);
                return rowY;
            }
            case "NetGraph": {
                rowY = drawCycleRow(dc, font, "Window", cfgNetGraphSeconds + "s",
                        x, rowY, w, clickEdge,
                        () -> {
                            int idx = 0;
                            for (int i = 0; i < NETGRAPH_SECS.length; i++)
                                if (NETGRAPH_SECS[i] == cfgNetGraphSeconds) { idx = i; break; }
                            cfgNetGraphSeconds = NETGRAPH_SECS[(idx + 1) % NETGRAPH_SECS.length];
                        },
                        () -> cfgNetGraphSeconds = 30);
                return rowY;
            }
            case "Killstreak": {
                rowY = drawCycleRow(dc, font, "Reset", KS_RESET_NAMES[cfgKillstreakReset],
                        x, rowY, w, clickEdge,
                        () -> cfgKillstreakReset = (cfgKillstreakReset + 1) % KS_RESET_NAMES.length,
                        () -> cfgKillstreakReset = 0);
                return rowY;
            }
            case "Saturation": {
                rowY = drawCycleRow(dc, font, "Style", SAT_STYLE_NAMES[cfgSatStyle],
                        x, rowY, w, clickEdge,
                        () -> cfgSatStyle = (cfgSatStyle + 1) % SAT_STYLE_NAMES.length,
                        () -> cfgSatStyle = 0);
                return rowY;
            }
            case "ToolBreak": {
                rowY = drawSliderRow(dc, font, "ToolBreak:thresh", "Threshold (%)",
                        1, 50, 1, 5,
                        x, rowY, w, clickEdge,
                        () -> cfgToolBreakThresh, v -> cfgToolBreakThresh = v);
                rowY = drawToggleRow(dc, font, "Play warning sound", cfgToolBreakSound,
                        x, rowY, w, clickEdge,
                        () -> cfgToolBreakSound = !cfgToolBreakSound,
                        () -> cfgToolBreakSound = true);
                return rowY;
            }
            case "AntiAFK": {
                rowY = drawSliderRow(dc, font, "AntiAFK:interval", "Interval (s)",
                        15, 180, 5, 25,
                        x, rowY, w, clickEdge,
                        () -> cfgAntiAfkInterval, v -> cfgAntiAfkInterval = v);
                return rowY;
            }
            case "HotbarFade": {
                rowY = drawSliderRow(dc, font, "HotbarFade:delay", "Idle delay (s)",
                        1, 15, 1, 3,
                        x, rowY, w, clickEdge,
                        () -> cfgHotbarFadeDelay, v -> cfgHotbarFadeDelay = v);
                return rowY;
            }
            case "AutoRespawn": {
                rowY = drawSliderRow(dc, font, "AutoRespawn:delay", "Delay (ms)",
                        0, 2000, 50, 250,
                        x, rowY, w, clickEdge,
                        () -> cfgAutoRespawnDelay, v -> cfgAutoRespawnDelay = v);
                return rowY;
            }
            case "Fullbright": {
                rowY = drawSliderRow(dc, font, "Fullbright:level", "Brightness",
                        0, 1000, 25, 100,
                        x, rowY, w, clickEdge,
                        () -> cfgFullbrightLevel, v -> cfgFullbrightLevel = v);
                return rowY;
            }
            case "AutoGG": {
                rowY = drawCycleRow(dc, font, "Message", AUTOGG_MSGS[cfgAutoGgIdx],
                        x, rowY, w, clickEdge,
                        () -> cfgAutoGgIdx = (cfgAutoGgIdx + 1) % AUTOGG_MSGS.length,
                        () -> cfgAutoGgIdx = 0);
                return rowY;
            }
            case "SwordVisuals": {
                rowY = drawSliderRow(dc, font, "SwordVisuals:scale", "Length (%)",
                        50, 250, 5, 100,
                        x, rowY, w, clickEdge,
                        () -> cfgSwordScale, v -> cfgSwordScale = v);
                rowY = drawToggleRow(dc, font, "Apply to all held items", cfgSwordScaleAllItems,
                        x, rowY, w, clickEdge,
                        () -> cfgSwordScaleAllItems = !cfgSwordScaleAllItems,
                        () -> cfgSwordScaleAllItems = false);
                return rowY;
            }
            case "GlintTune": {
                rowY = drawSliderRow(dc, font, "GlintTune:strength", "Strength (%)",
                        0, 200, 5, 100,
                        x, rowY, w, clickEdge,
                        () -> cfgGlintStrength, v -> cfgGlintStrength = v);
                // 8-swatch picker for glint color (theme-style)
                rowY = drawGlintColorRow(dc, font, x, rowY, w, clickEdge);
                draw(dc, font, "§7§oColor + strength apply within ~1 frame",
                     x + 4, rowY, 0xFFFFFFFF);
                rowY += 12;
                return rowY;
            }
            case "BlockHighlight": {
                rowY = drawSliderRow(dc, font, "BlockHighlight:alpha", "Opacity (%)",
                        10, 100, 5, 50,
                        x, rowY, w, clickEdge,
                        () -> cfgBlockHighlightAlpha,
                        v -> cfgBlockHighlightAlpha = v);
                // Color row — show current color, click to open the rich picker.
                draw(dc, font, "§fColor", x + 4, rowY + 2, 0xFFFFFFFF);
                int swX = x + 80;
                int swW = 90, swH = 16;
                roundedRect(dc, swX, rowY, swX + swW, rowY + swH, cfgBlockHighlightColor);
                String hex = String.format("§7#%06X", cfgBlockHighlightColor & 0xFFFFFF);
                draw(dc, font, hex, swX + swW + 8, rowY + 4, 0xFFFFFFFF);
                if (hitTest(swX, rowY, swX + swW, rowY + swH) && clickEdge) {
                    openColorPicker(cfgBlockHighlightColor, picked -> {
                        cfgBlockHighlightColor = picked;
                        saveConfig();
                    });
                    leftClickEdge = false;
                }
                rowY += swH + 4;
                return rowY;
            }
            case "Trail":      return drawParticleRow(dc, font, x, rowY, w, clickEdge,
                                       () -> trailIdx, v -> trailIdx = v, 0);
            case "Halo":       return drawParticleRow(dc, font, x, rowY, w, clickEdge,
                                       () -> haloIdx, v -> haloIdx = v, 0);
            case "Wings":      return drawParticleRow(dc, font, x, rowY, w, clickEdge,
                                       () -> wingsParticleIdx, v -> wingsParticleIdx = v, 0);
            case "AngelWings": return drawParticleRow(dc, font, x, rowY, w, clickEdge,
                                       () -> angelWingsIdx, v -> angelWingsIdx = v, 0);
            case "Fairies":    return drawParticleRow(dc, font, x, rowY, w, clickEdge,
                                       () -> fairiesIdx, v -> fairiesIdx = v, 0);
            case "Footsteps":  return drawParticleRow(dc, font, x, rowY, w, clickEdge,
                                       () -> footstepsIdx, v -> footstepsIdx = v, 0);
            case "BowTrail":   return drawParticleRow(dc, font, x, rowY, w, clickEdge,
                                       () -> bowTrailIdx, v -> bowTrailIdx = v, 0);
            default: {
                draw(dc, font, "§7§oNo extra settings for this module.",
                     x, rowY, 0xFFFFFFFF);
                return rowY + 12;
            }
        }
    }

    /** Active slider state — only one slider can be dragged at a time.
     *  Identifier scoped per module + setting key (e.g. "HudLayout:scale"). */
    private static String sliderDragKey = null;

    /** Numeric slider row: track + handle + value text + reset arrow.
     *  - id = unique per-module-per-setting key (used to track drag state)
     *  - min / max = inclusive int range
     *  - step = quantization granularity (e.g. 1, 5, 10)
     *  - getValue / setValue = property accessors
     *  - defaultValue = used by the reset button
     *  Returns next rowY. */
    private static int drawSliderRow(Object dc, Object font, String id, String label,
                                     int min, int max, int step, int defaultValue,
                                     int x, int rowY, int w, boolean clickEdge,
                                     java.util.function.IntSupplier getValue,
                                     java.util.function.IntConsumer setValue) {
        int cur = getValue.getAsInt();
        // Label
        draw(dc, font, "§f" + label, x + 4, rowY + 4, 0xFFFFFFFF);
        // Reset
        int rstW = 16;
        int rstR = x + w - 4;
        int rstL = rstR - rstW;
        boolean rstHover = hitTest(rstL, rowY, rstR, rowY + 16);
        roundedRect(dc, rstL, rowY, rstR, rowY + 16, rstHover ? 0xFF2A1A22 : 0xFF1A1218);
        draw(dc, font, "§7↺", rstL + 5, rowY + 4, 0xFFFFFFFF);
        if (rstHover && clickEdge) {
            try { setValue.accept(defaultValue); saveConfig(); } catch (Throwable ignored) {}
            leftClickEdge = false;
        }
        // Value text (right of slider, before reset)
        String valueText = String.valueOf(cur);
        int valueW = valueText.length() * 6 + 8;
        int valueR = rstL - 4;
        int valueL = valueR - valueW;
        draw(dc, font, "§f" + valueText, valueL + 4, rowY + 4, 0xFFFFFFFF);
        // Slider track
        int trackL = x + 92;          // start after label
        int trackR = valueL - 6;
        int trackW = trackR - trackL;
        if (trackW < 30) trackW = 30;
        int trackY = rowY + 7;
        // Track background
        fill(dc, trackL, trackY, trackR, trackY + 2, 0xFF2A2030);
        // Filled portion (left of handle)
        float t = (float)(cur - min) / Math.max(1, (max - min));
        t = Math.max(0f, Math.min(1f, t));
        int handleX = trackL + (int)(t * trackW);
        fill(dc, trackL, trackY, handleX, trackY + 2, themeAccent());
        // Handle (drag knob)
        boolean hov = hitTest(trackL, rowY, trackR, rowY + 16);
        int handleW = 8;
        int handleY = rowY + 3;
        roundedRect(dc, handleX - handleW / 2, handleY,
                        handleX + handleW / 2, handleY + 12,
                        hov ? 0xFFFFFFFF : 0xFFE8E8E8);
        // Drag handling — start on click in track, update while held, end on release
        boolean lmbDown = mouseButtonDown(MB_LEFT);
        boolean inTrack = (mouseX >= trackL - 4 && mouseX <= trackR + 4
                           && mouseY >= rowY && mouseY <= rowY + 16);
        if (clickEdge && inTrack) {
            sliderDragKey = id;
            leftClickEdge = false;
        }
        if (id.equals(sliderDragKey)) {
            if (!lmbDown) {
                sliderDragKey = null;
            } else {
                float pos = (float)(mouseX - trackL) / Math.max(1, trackW);
                pos = Math.max(0f, Math.min(1f, pos));
                int raw = min + Math.round(pos * (max - min));
                // Quantize to step
                if (step > 1) raw = Math.round(raw / (float) step) * step;
                raw = Math.max(min, Math.min(max, raw));
                if (raw != cur) {
                    try { setValue.accept(raw); saveConfig(); }
                    catch (Throwable ignored) {}
                }
            }
        }
        return rowY + 20;
    }

    /** A "Label  [▢ on/off pill]  ↺" row for boolean settings. */
    private static int drawToggleRow(Object dc, Object font, String label, boolean value,
                                     int x, int rowY, int w, boolean clickEdge,
                                     Runnable onClick, Runnable onReset) {
        // Label
        draw(dc, font, "§f" + label, x + 4, rowY + 4, 0xFFFFFFFF);
        // Reset
        int rstW = 16;
        int rstR = x + w - 4;
        int rstL = rstR - rstW;
        boolean rstHover = hitTest(rstL, rowY, rstR, rowY + 16);
        roundedRect(dc, rstL, rowY, rstR, rowY + 16, rstHover ? 0xFF2A1A22 : 0xFF1A1218);
        draw(dc, font, "§7↺", rstL + 5, rowY + 4, 0xFFFFFFFF);
        if (rstHover && clickEdge) {
            try { onReset.run(); saveConfig(); } catch (Throwable ignored) {}
            leftClickEdge = false;
        }
        // Pill toggle (right side, before reset)
        int pillW = 24, pillH = 12;
        int pillR = rstL - 6;
        int pillL = pillR - pillW;
        int pillT = rowY + 2;
        boolean pillHover = hitTest(pillL, pillT, pillR, pillT + pillH);
        int trackBg = value ? (pillHover ? 0xFFC8161E : 0xFFAF121A)
                            : (pillHover ? 0xFF44444E : 0xFF35353D);
        roundedRect(dc, pillL, pillT, pillR, pillT + pillH, trackBg);
        int knobX = value ? (pillR - 10) : (pillL + 2);
        roundedRect(dc, knobX, pillT + 2, knobX + 8, pillT + 10, 0xFFE8E8E8);
        if (pillHover && clickEdge) {
            try { onClick.run(); saveConfig(); } catch (Throwable ignored) {}
            leftClickEdge = false;
        }
        return rowY + 20;
    }

    /** Restore all of the named module's configurable settings to their
     *  factory defaults. Called from the panel header's Reset button. */
    private static void resetModuleConfig(String name) {
        switch (name) {
            case "Crosshair":
                crosshairShape = 0;
                crosshairColorIdx = 0;
                customCrosshairColor = 0xFFFFFFFF;
                break;
            case "Map":         mapSizeIdx = 2; break;
            case "Keystrokes":  keysPosIdx = 0; break;
            case "Effects":     effectsLimitIdx = 1; break;
            case "Trail":       trailIdx = 0; break;
            case "Halo":        haloIdx = 0; break;
            case "Wings":       wingsParticleIdx = 0; break;
            case "AngelWings":  angelWingsIdx = 0; break;
            case "Fairies":     fairiesIdx = 0; break;
            case "Footsteps":   footstepsIdx = 0; break;
            case "BowTrail":    bowTrailIdx = 0; break;
            case "HP":
                cfgHpShowAbs = true;
                cfgHpShowArmor = true;
                cfgHpCompact = false;
                break;
            case "FPS":
                cfgFpsLabel = true;
                cfgFpsStyle = 0;
                break;
            case "XYZ":
                cfgXyzDecimals = 1;
                cfgXyzSep = 0;
                cfgXyzCompact = false;
                break;
            case "Time":
                cfgTime24h = true;
                cfgTimeSeconds = true;
                break;
            case "Compass":
                cfgCompassDegs = true;
                cfgCompassMode = 0;
                break;
            case "HudLayout":
                cfgHudAnchor   = 4;
                cfgHudScale    = 100;
                cfgHudOpacity  = 88;
                cfgHudOffsetX  = 0;
                cfgHudOffsetY  = 0;
                cfgThemeIdx    = 0;
                break;
            case "Ping":        cfgPingStyle = 0; break;
            case "Memory":      cfgMemFormat = 0; break;
            case "Day":         cfgDayFormat = 0; break;
            case "Reach":
                cfgReachOnlyOnTarget = false;
                cfgReachShowUnit     = true;
                break;
            case "NetGraph":    cfgNetGraphSeconds = 30; break;
            case "Killstreak":  cfgKillstreakReset = 0; break;
            case "Saturation":  cfgSatStyle = 0; break;
            case "ToolBreak":
                cfgToolBreakThresh = 5;
                cfgToolBreakSound  = true;
                break;
            case "AntiAFK":     cfgAntiAfkInterval  = 25; break;
            case "HotbarFade":  cfgHotbarFadeDelay  = 3; break;
            case "AutoRespawn": cfgAutoRespawnDelay = 250; break;
            case "Fullbright":  cfgFullbrightLevel  = 100; break;
            case "AutoGG":      cfgAutoGgIdx = 0; break;
        }
        // Universal: also clear any per-module overrides for this name.
        moduleAnchorOverrides.remove(name);
        moduleOffsetOverrides.remove(name);
        moduleColorOverrides.remove(name);
    }

    /** Theme-color picker — 8 named swatches in a row. Click to apply.
     *  Affects title accent, pill toggle, card highlights, HUD card icons. */
    private static int drawThemeRow(Object dc, Object font, String label,
                                    int x, int rowY, int w, boolean clickEdge) {
        // Label + reset
        draw(dc, font, "§f" + label, x + 4, rowY + 2, 0xFFFFFFFF);
        // Current value text after the label
        String curName = THEME_NAMES[Math.max(0, Math.min(THEME_NAMES.length - 1, cfgThemeIdx))];
        draw(dc, font, "§7" + curName,
             x + 4 + label.length() * 6 + 8, rowY + 2, 0xFFFFFFFF);
        int rstW = 16;
        int rstR = x + w - 4;
        int rstL = rstR - rstW;
        boolean rstHover = hitTest(rstL, rowY, rstR, rowY + 14);
        roundedRect(dc, rstL, rowY, rstR, rowY + 14, rstHover ? 0xFF2A1A22 : 0xFF1A1218);
        draw(dc, font, "§7↺", rstL + 5, rowY + 3, 0xFFFFFFFF);
        if (rstHover && clickEdge) {
            cfgThemeIdx = 0; saveConfig();
            leftClickEdge = false;
        }
        rowY += 16;
        // Swatch row — 8 named + 1 custom
        int swW = 22, swH = 14, gap = 3;
        int totalW = THEME_COLORS.length * (swW + gap) + (swW + gap); // +1 for custom
        int sx = x + 4;
        for (int i = 0; i < THEME_COLORS.length; i++) {
            int swX = sx + i * (swW + gap);
            roundedRect(dc, swX, rowY, swX + swW, rowY + swH, THEME_COLORS[i]);
            boolean sel = (cfgThemeIdx == i);
            boolean hov = hitTest(swX, rowY, swX + swW, rowY + swH);
            if (sel) {
                fill(dc, swX - 1, rowY - 1, swX + swW + 1, rowY,             0xFFFFFFFF);
                fill(dc, swX - 1, rowY + swH, swX + swW + 1, rowY + swH + 1, 0xFFFFFFFF);
                fill(dc, swX - 1, rowY, swX, rowY + swH,                     0xFFFFFFFF);
                fill(dc, swX + swW, rowY, swX + swW + 1, rowY + swH,         0xFFFFFFFF);
            } else if (hov) {
                fill(dc, swX - 1, rowY - 1, swX + swW + 1, rowY,             0x88FFFFFF);
                fill(dc, swX - 1, rowY + swH, swX + swW + 1, rowY + swH + 1, 0x88FFFFFF);
                fill(dc, swX - 1, rowY, swX, rowY + swH,                     0x88FFFFFF);
                fill(dc, swX + swW, rowY, swX + swW + 1, rowY + swH,         0x88FFFFFF);
            }
            if (hov && clickEdge) {
                cfgThemeIdx = i; saveConfig();
                leftClickEdge = false;
            }
        }
        // Custom slot at the end — shows current custom color, click opens hex picker
        int customX = sx + THEME_COLORS.length * (swW + gap);
        roundedRect(dc, customX, rowY, customX + swW, rowY + swH, cfgThemeCustom);
        boolean customSel = cfgThemeIdx >= THEME_COLORS.length;
        boolean customHov = hitTest(customX, rowY, customX + swW, rowY + swH);
        if (customSel) {
            fill(dc, customX - 1, rowY - 1, customX + swW + 1, rowY,             0xFFFFFFFF);
            fill(dc, customX - 1, rowY + swH, customX + swW + 1, rowY + swH + 1, 0xFFFFFFFF);
            fill(dc, customX - 1, rowY, customX, rowY + swH,                     0xFFFFFFFF);
            fill(dc, customX + swW, rowY, customX + swW + 1, rowY + swH,         0xFFFFFFFF);
        }
        draw(dc, font, "§l§f+", customX + swW / 2 - 2, rowY + 3, 0xFFFFFFFF);
        if (customHov && clickEdge) {
            openColorPicker(cfgThemeCustom, picked -> {
                cfgThemeCustom = picked;
                cfgThemeIdx = THEME_COLORS.length;
                saveConfig();
            });
            leftClickEdge = false;
        }
        return rowY + swH + 6;
    }

    /** A 3×3 anchor grid for HUD layout. Each cell represents one of the
     *  9 screen-corner anchor points; click to set. Returns next rowY. */
    private static int drawAnchorGrid(Object dc, Object font, String label,
                                      int x, int rowY, int w, boolean clickEdge) {
        // Label row + reset button
        draw(dc, font, "§f" + label, x + 4, rowY + 2, 0xFFFFFFFF);
        int rstW = 16;
        int rstR = x + w - 4;
        int rstL = rstR - rstW;
        boolean rstHover = hitTest(rstL, rowY, rstR, rowY + 14);
        roundedRect(dc, rstL, rowY, rstR, rowY + 14, rstHover ? 0xFF2A1A22 : 0xFF1A1218);
        draw(dc, font, "§7↺", rstL + 5, rowY + 3, 0xFFFFFFFF);
        if (rstHover && clickEdge) {
            cfgHudAnchor = 4; saveConfig();
            leftClickEdge = false;
        }
        // Current label
        String anchorName = HUD_ANCHOR_NAMES[Math.max(0, Math.min(8, cfgHudAnchor))];
        draw(dc, font, "§7" + anchorName,
             x + 4 + label.length() * 6 + 8, rowY + 2, 0xFFFFFFFF);
        rowY += 16;
        // 3×3 grid — each cell a 36×24 px button. Selected cell has a red
        // glow, others are dim. Click to set.
        int cellW = 36, cellH = 24, gap = 3;
        int gridW = cellW * 3 + gap * 2;
        int gridX = x + (w - gridW) / 2;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                int idx = r * 3 + c;
                int cx = gridX + c * (cellW + gap);
                int cy = rowY + r * (cellH + gap);
                boolean sel = (cfgHudAnchor == idx);
                boolean hov = hitTest(cx, cy, cx + cellW, cy + cellH);
                int bg = sel ? (hov ? 0xFFC8161E : 0xFFAF121A)
                             : (hov ? 0xFF2A1A22 : 0xFF1A1218);
                roundedRect(dc, cx, cy, cx + cellW, cy + cellH, bg);
                // Inside the cell, draw a small pip representing the anchor
                // point (top-left, top-center, ..., bottom-right) so the user
                // can tell at a glance what each cell means.
                int pipR = 2;
                int pipX = sel ? cx + cellW / 2 : (c == 0 ? cx + 4
                                                  : c == 1 ? cx + cellW / 2 - pipR
                                                  :          cx + cellW - 4 - 2 * pipR);
                int pipY = c == 1 && r == 1 ? cy + cellH / 2 - pipR
                         : (r == 0 ? cy + 4
                            : r == 1 ? cy + cellH / 2 - pipR
                            :          cy + cellH - 4 - 2 * pipR);
                // Use proper relative positions
                pipX = cx + (c == 0 ? 4 : c == 1 ? cellW / 2 - pipR : cellW - 4 - 2 * pipR);
                pipY = cy + (r == 0 ? 4 : r == 1 ? cellH / 2 - pipR : cellH - 4 - 2 * pipR);
                int pipColor = sel ? 0xFFFFFFFF : (hov ? 0xFFFF99AA : 0xFF666670);
                fill(dc, pipX, pipY, pipX + pipR * 2, pipY + pipR * 2, pipColor);
                if (hov && clickEdge) {
                    cfgHudAnchor = idx; saveConfig();
                    leftClickEdge = false;
                }
            }
        }
        return rowY + 3 * (cellH + gap) + 4;
    }

    /** A 16-swatch color grid row for Crosshair. Click any swatch to pick;
     *  the last "+" swatch opens the hex picker. */
    private static int drawColorSwatchRow(Object dc, Object font, String label,
                                          int x, int rowY, int w, boolean clickEdge) {
        // Label on its own line above the grid
        draw(dc, font, "§f" + label, x + 4, rowY + 2, 0xFFFFFFFF);
        // Reset arrow on the right of the label
        int rstW = 16;
        int rstR = x + w - 4;
        int rstL = rstR - rstW;
        boolean rstHover = hitTest(rstL, rowY, rstR, rowY + 14);
        roundedRect(dc, rstL, rowY, rstR, rowY + 14, rstHover ? 0xFF2A1A22 : 0xFF1A1218);
        draw(dc, font, "§7↺", rstL + 5, rowY + 3, 0xFFFFFFFF);
        if (rstHover && clickEdge) {
            crosshairColorIdx = 0; saveConfig();
            leftClickEdge = false;
        }
        rowY += 14;
        // Swatch grid: 16 + 1 (custom). 9 per row, 18×14 each.
        int sw = 18, shh = 14;
        int gap = 2;
        int totalSwatches = CROSSHAIR_COLORS.length + 1;
        int swatchesPerRow = Math.max(8, (w - 4) / (sw + gap));
        for (int i = 0; i < totalSwatches; i++) {
            int sxx = x + 4 + (i % swatchesPerRow) * (sw + gap);
            int syy = rowY + (i / swatchesPerRow) * (shh + gap);
            boolean isCustom = (i == CROSSHAIR_COLORS.length);
            int color = isCustom ? customCrosshairColor : (0xFF000000 | CROSSHAIR_COLORS[i]);
            boolean selected = (crosshairColorIdx == i);
            boolean swHover = hitTest(sxx, syy, sxx + sw, syy + shh);
            // Swatch fill
            roundedRect(dc, sxx, syy, sxx + sw, syy + shh, color);
            // Selection ring
            if (selected) {
                fill(dc, sxx - 1, syy - 1, sxx + sw + 1, syy,             0xFFFFFFFF);
                fill(dc, sxx - 1, syy + shh, sxx + sw + 1, syy + shh + 1, 0xFFFFFFFF);
                fill(dc, sxx - 1, syy, sxx, syy + shh,                    0xFFFFFFFF);
                fill(dc, sxx + sw, syy, sxx + sw + 1, syy + shh,          0xFFFFFFFF);
            } else if (swHover) {
                // Subtle outline on hover
                fill(dc, sxx - 1, syy - 1, sxx + sw + 1, syy,             0x88FFFFFF);
                fill(dc, sxx - 1, syy + shh, sxx + sw + 1, syy + shh + 1, 0x88FFFFFF);
                fill(dc, sxx - 1, syy, sxx, syy + shh,                    0x88FFFFFF);
                fill(dc, sxx + sw, syy, sxx + sw + 1, syy + shh,          0x88FFFFFF);
            }
            if (isCustom) {
                // Mark the custom slot with a "+" badge so it's distinguishable.
                draw(dc, font, "§l§f+", sxx + sw / 2 - 2, syy + 3, 0xFFFFFFFF);
            }
            if (swHover && clickEdge) {
                if (isCustom) {
                    // Open the rich HSV picker for the Crosshair custom color
                    openColorPicker(customCrosshairColor, picked -> {
                        customCrosshairColor = picked;
                        crosshairColorIdx = CROSSHAIR_COLORS.length;
                        saveConfig();
                    });
                } else {
                    crosshairColorIdx = i;
                }
                saveConfig();
                leftClickEdge = false;
            }
        }
        int rows = (totalSwatches + swatchesPerRow - 1) / swatchesPerRow;
        return rowY + rows * (shh + gap) + 6;
    }

    /** A "Label  [value cycle button]  ↺ reset" row.
     *  click = cycle next; resetClick = revert to default. Returns next rowY. */
    private static int drawCycleRow(Object dc, Object font, String label, String value,
                                    int x, int rowY, int w, boolean clickEdge,
                                    Runnable onClick, Runnable onReset) {
        int labelW = 80;
        // Label text
        draw(dc, font, "§7" + label, x + 4, rowY + 3, 0xFFFFFFFF);
        // Reset arrow (right edge)
        int rstW = 16;
        int rstR = x + w - 4;
        int rstL = rstR - rstW;
        int rstT = rowY;
        int rstB = rowY + 14;
        boolean rstHover = hitTest(rstL, rstT, rstR, rstB);
        roundedRect(dc, rstL, rstT, rstR, rstB, rstHover ? 0xFF2A1A22 : 0xFF1A1218);
        draw(dc, font, "§7↺", rstL + 5, rstT + 3, 0xFFFFFFFF);
        if (rstHover && clickEdge) {
            try { onReset.run(); saveConfig(); } catch (Throwable ignored) {}
            leftClickEdge = false;
        }
        // Value button (between label and reset)
        int valL = x + labelW;
        int valR = rstL - 4;
        int valT = rowY;
        int valB = rowY + 14;
        boolean valHover = hitTest(valL, valT, valR, valB);
        roundedRect(dc, valL, valT, valR, valB,
                    valHover ? 0xFF3A1A22 : 0xFF22182A);
        // Centered value text
        String shown = value;
        int valWChars = Math.max(4, (valR - valL) / 6);
        if (shown.length() > valWChars) shown = shown.substring(0, valWChars - 1) + "…";
        draw(dc, font, "§f" + shown, valL + 4, valT + 3, 0xFFFFFFFF);
        if (valHover && clickEdge) {
            try { onClick.run(); saveConfig(); } catch (Throwable ignored) {}
            leftClickEdge = false;
        }
        return rowY + 18;
    }

    /** Particle picker row used by Trail / Halo / Wings / etc.
     *  Shows current particle name; click cycles through TRAIL_PARTICLES. */
    private static int drawParticleRow(Object dc, Object font,
                                       int x, int rowY, int w, boolean clickEdge,
                                       java.util.function.IntSupplier getter,
                                       java.util.function.IntConsumer setter,
                                       int defaultIdx) {
        int idx = Math.max(0, Math.min(TRAIL_PARTICLES.length - 1, getter.getAsInt()));
        String particleName = TRAIL_PARTICLES[idx][0];
        return drawCycleRow(dc, font, "Particle", particleName,
                x, rowY, w, clickEdge,
                () -> setter.accept((getter.getAsInt() + 1) % TRAIL_PARTICLES.length),
                () -> setter.accept(defaultIdx));
    }

    private static int screenWidth(Object dc) {
        try {
            Object window = mc.getClass().getMethod("method_22683").invoke(mc);
            return (int) window.getClass().getMethod("method_4486").invoke(window);  // scaled width
        } catch (Throwable t) {
            // Fallback: ask the draw context
            try { return (int) dc.getClass().getMethod("method_51421").invoke(dc); }
            catch (Throwable tt) { return 640; }
        }
    }

    private static int screenHeight(Object dc) {
        try {
            Object window = mc.getClass().getMethod("method_22683").invoke(mc);
            return (int) window.getClass().getMethod("method_4502").invoke(window);  // scaled height
        } catch (Throwable t) {
            try { return (int) dc.getClass().getMethod("method_51443").invoke(dc); }
            catch (Throwable tt) { return 480; }
        }
    }

    private static void fill(Object dc, int x1, int y1, int x2, int y2, int argb) {
        if (fillMethod == null) return;
        try { fillMethod.invoke(dc, x1, y1, x2, y2, argb); } catch (Throwable ignored) {}
    }

    /** True if the cursor is currently inside the rect (in scaled GUI px). */
    private static boolean hitTest(int x1, int y1, int x2, int y2) {
        return mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2;
    }

    /** Robust "is the player-list / Tab key currently held?" check. Tries
     *  multiple detection paths: KeyBinding.isPressed(), the underlying
     *  pressed field, and finally direct GLFW polling for whatever key
     *  the keybind is bound to (defaulting to Tab=258 if we can't read it). */
    private static boolean shadowhud$tabHeld() {
        Object kpl = null;
        try {
            Object opts = mc.getClass().getField("field_1690").get(mc);
            try { kpl = opts.getClass().getField("field_1907").get(opts); }
            catch (NoSuchFieldException e) {
                try { kpl = opts.getClass().getField("keyPlayerList").get(opts); }
                catch (NoSuchFieldException ignored) {}
            }
        } catch (Throwable ignored) {}

        if (kpl != null) {
            // Path 1: KeyBinding.isPressed() — what vanilla checks
            try {
                Object pressed = tryInvoke(kpl, "method_1434", "isPressed");
                if (Boolean.TRUE.equals(pressed)) return true;
            } catch (Throwable ignored) {}
            // Path 2: read field_1653 (pressed boolean) directly
            try {
                Class<?> c = kpl.getClass();
                while (c != null) {
                    try {
                        Field f = c.getDeclaredField("field_1653");
                        f.setAccessible(true);
                        if (f.getBoolean(kpl)) return true;
                        break;
                    } catch (NoSuchFieldException ignored) { c = c.getSuperclass(); }
                }
            } catch (Throwable ignored) {}
            // Path 3: read the bound key code from the keybind, poll GLFW
            try {
                Field bk = null;
                Class<?> c = kpl.getClass();
                while (c != null && bk == null) {
                    try { bk = c.getDeclaredField("field_1655"); }
                    catch (NoSuchFieldException e) { c = c.getSuperclass(); }
                }
                if (bk != null) {
                    bk.setAccessible(true);
                    Object boundKey = bk.get(kpl);
                    Object code = tryInvoke(boundKey, "method_1444", "getCode");
                    if (code instanceof Number) {
                        int kc = ((Number) code).intValue();
                        if (kc >= 0 && keyDown(kc)) return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        // Path 4: dumb fallback — poll Tab (GLFW 258) directly
        return keyDown(258);
    }

    /** Apply category + enabled-only + search filter to a single entry. */
    private static boolean passesFilter(Map.Entry<String, Boolean> en) {
        if (!menuCategoryFilter.isEmpty()
            && !menuCategoryFilter.equals(MODULE_CAT.get(en.getKey()))) return false;
        if (menuEnabledOnly && !Boolean.TRUE.equals(en.getValue())) return false;
        if (menuSearchBuf.length() > 0
            && !en.getKey().toLowerCase().contains(menuSearchBuf.toString().toLowerCase()))
            return false;
        return true;
    }

    /** Materialise the visible (filtered + sorted) module list. */
    private static java.util.List<Map.Entry<String, Boolean>> visibleModuleList() {
        java.util.List<Map.Entry<String, Boolean>> list = new ArrayList<>();
        for (Map.Entry<String, Boolean> en : MODULES.entrySet()) {
            if (passesFilter(en)) list.add(en);
        }
        switch (menuSortMode) {
            case 1:  // alphabetical
                list.sort((a, b) -> a.getKey().compareToIgnoreCase(b.getKey()));
                break;
            case 2:  // enabled first, then alphabetical
                list.sort((a, b) -> {
                    int v = Boolean.compare(!Boolean.TRUE.equals(a.getValue()),
                                             !Boolean.TRUE.equals(b.getValue()));
                    return v != 0 ? v : a.getKey().compareToIgnoreCase(b.getKey());
                });
                break;
            // case 0: insertion order (category-grouped) — leave as-is
        }
        // Pinned modules float to the top — applied as a stable secondary
        // sort so within "pinned" or within "unpinned" the user-chosen order
        // is preserved. Stable sort guaranteed by List.sort().
        if (!pinnedModules.isEmpty()) {
            list.sort((a, b) -> {
                boolean ap = pinnedModules.contains(a.getKey());
                boolean bp = pinnedModules.contains(b.getKey());
                return ap == bp ? 0 : (ap ? -1 : 1);
            });
        }
        return list;
    }

    /** Color stripe per category — drawn at the top of each card so the user
     *  can scan a 12-card grid and tell at a glance "those are HUD, those are
     *  Combat, those are Inventory" without reading the labels. Hand-picked
     *  hues that read well on a dark panel. */
    /** Approximate the on-screen width of a Minecraft §-formatted string in
     *  pixels. Strips two-character §X color/style codes (e.g. §c, §l, §r)
     *  and assumes 6 px per remaining character — close enough for centering
     *  and bounds checks without pulling in MC's font metrics API. */
    private static int displayWidth(String s) {
        if (s == null) return 0;
        int chars = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) { i++; continue; }
            chars++;
        }
        return chars * 6;
    }

    /** 3-letter abbreviation for a module category — used as the visual
     *  label in the top-left of each module card. Falls back to the first
     *  3 letters of the category name for any unknown category. */
    private static String catAbbrev(String cat) {
        if (cat == null) return "MOD";
        switch (cat) {
            case "Display":   return "DSP";
            case "World":     return "WRL";
            case "Inventory": return "INV";
            case "Combat":    return "CMB";
            case "Server":    return "SRV";
            case "Utility":   return "UTL";
            default:
                return cat.length() >= 3
                    ? cat.substring(0, 3).toUpperCase()
                    : cat.toUpperCase();
        }
    }

    private static int categoryColor(String cat) {
        if (cat == null) return 0xFF666666;
        switch (cat) {
            case "Display":   return 0xFF4A9EFF;   // sky blue
            case "World":     return 0xFF6BCB6B;   // grass green
            case "Inventory": return 0xFFFFB347;   // amber
            case "Combat":    return 0xFFE94B4B;   // red
            case "Server":    return 0xFFB57CFF;   // violet
            case "Utility":   return 0xFFFFD24D;   // gold
            default:          return 0xFF888888;   // neutral gray
        }
    }

    private static int countFilteredModules() {
        return visibleModuleList().size();
    }

    private static Map.Entry<String, Boolean> filteredModuleAt(int wantIdx) {
        java.util.List<Map.Entry<String, Boolean>> list = visibleModuleList();
        return (wantIdx >= 0 && wantIdx < list.size()) ? list.get(wantIdx) : null;
    }

    /** Resolve the active crosshair colour — preset slot OR the user's
     *  custom hex (last cycle slot). */
    private static int currentCrosshairColor() {
        if (crosshairColorIdx < 0) return CROSSHAIR_COLORS[0];
        if (crosshairColorIdx < CROSSHAIR_COLORS.length)
            return CROSSHAIR_COLORS[crosshairColorIdx];
        return customCrosshairColor;
    }

    /** Toggle a module unless it's a "cycle-on-Enter" card AND already on,
     *  in which case advance the relevant index (wrapping back through the
     *  off state at the end so the user can disable from the same key). */
    private static void cycleOrToggle(Map.Entry<String, Boolean> e) {
        String name = e.getKey();
        boolean on = Boolean.TRUE.equals(e.getValue());
        switch (name) {
            case "Crosshair":
                if (!on) { e.setValue(true); return; }
                crosshairShape = (crosshairShape + 1) % CROSSHAIR_SHAPE_NAMES.length;
                if (crosshairShape == 0) {
                    // colors cycle: 6 presets + 1 custom slot
                    int total = CROSSHAIR_COLORS.length + 1;
                    crosshairColorIdx = (crosshairColorIdx + 1) % total;
                    if (crosshairColorIdx == 0) e.setValue(false);  // wrap → off
                }
                return;
            case "Map":
                if (!on) { e.setValue(true); if (mapSizeIdx < 1) mapSizeIdx = 2; return; }
                mapSizeIdx++;
                if (mapSizeIdx >= MAP_SIZE_PIXELS.length) {
                    mapSizeIdx = 2;
                    e.setValue(false);    // wrap → off
                }
                return;
            case "Keystrokes":
                if (!on) { e.setValue(true); return; }
                keysPosIdx++;
                if (keysPosIdx >= KEYS_POS_NAMES.length) {
                    keysPosIdx = 0;
                    e.setValue(false);    // wrap → off
                }
                return;
            case "Effects":
                if (!on) { e.setValue(true); return; }
                effectsLimitIdx++;
                if (effectsLimitIdx >= EFFECTS_LIMITS.length) {
                    effectsLimitIdx = 1;
                    e.setValue(false);    // wrap → off
                }
                return;
            case "Trail":
                if (!on) { e.setValue(true); return; }
                trailIdx++;
                if (trailIdx >= TRAIL_PARTICLES.length) {
                    trailIdx = 0;
                    e.setValue(false);    // wrap → off
                }
                return;
            case "Halo":
                if (!on) { e.setValue(true); return; }
                haloIdx++;
                if (haloIdx >= TRAIL_PARTICLES.length) {
                    haloIdx = 0;
                    e.setValue(false);    // wrap → off
                }
                return;
            case "Wings":
                if (!on) { e.setValue(true); return; }
                wingsParticleIdx++;
                if (wingsParticleIdx >= TRAIL_PARTICLES.length) {
                    wingsParticleIdx = 0;
                    e.setValue(false);
                }
                return;
            case "AngelWings":
                if (!on) { e.setValue(true); return; }
                angelWingsIdx++;
                if (angelWingsIdx >= TRAIL_PARTICLES.length) {
                    angelWingsIdx = 0;
                    e.setValue(false);
                }
                return;
            case "Cape":
                // Plain on/off toggle — no particle-variant cycling. The
                // texture (cape1.png vs cape2.png) is selected by the
                // separate CapeAlt module, not by clicking the Cape icon.
                e.setValue(!on);
                return;
            case "Fairies":
                if (!on) { e.setValue(true); return; }
                fairiesIdx++;
                if (fairiesIdx >= TRAIL_PARTICLES.length) {
                    fairiesIdx = 0;
                    e.setValue(false);
                }
                return;
            case "Footsteps":
                if (!on) { e.setValue(true); return; }
                footstepsIdx++;
                if (footstepsIdx >= TRAIL_PARTICLES.length) {
                    footstepsIdx = 0;
                    e.setValue(false);
                }
                return;
            case "BowTrail":
                if (!on) { e.setValue(true); return; }
                bowTrailIdx++;
                if (bowTrailIdx >= TRAIL_PARTICLES.length) {
                    bowTrailIdx = 0;
                    e.setValue(false);
                }
                return;
            default:
                e.setValue(!on);
        }
    }

    /**
     * Lunar-style horizontal toggle pill: a 22×8 rounded-ish pill where
     * the knob slides from left (OFF) to right (ON). Background turns
     * red when ON, dim grey when OFF, so state is unmistakable at a glance
     * without having to read text.
     */
    private static void drawTogglePill(Object dc, int x, int y, boolean on) {
        final int W = 22, H = 8;
        // Accessibility: ON = bright green (matches universal "active"
        // convention), OFF = neutral dark gray. Was red-on/dark-off which
        // looked like an alarm rather than a toggle state.
        int bg     = on ? 0xFF1FA94B : 0xFF2A2A2A;
        int bgEdge = on ? 0xFF22DD55 : 0xFF404040;

        // Body — fake rounded ends with two 1-px-narrower top/bottom rows
        fill(dc, x + 1,     y,         x + W - 1, y + H,         bg);
        fill(dc, x,         y + 1,     x + W,     y + H - 1,     bg);
        // Bright outline
        fill(dc, x + 1,     y,         x + W - 1, y + 1,         bgEdge);
        fill(dc, x + 1,     y + H - 1, x + W - 1, y + H,         bgEdge);
        fill(dc, x,         y + 2,     x + 1,     y + H - 2,     bgEdge);
        fill(dc, x + W - 1, y + 2,     x + W,     y + H - 2,     bgEdge);
        // Knob — 6×6 white circle (well, square); offset depends on state
        int knobX = on ? (x + W - 7) : (x + 1);
        fill(dc, knobX,     y + 1,     knobX + 6, y + H - 1,     0xFFFFFFFF);
        fill(dc, knobX + 1, y,         knobX + 5, y + H,         0xFFFFFFFF);
    }

    /** Pending HUD-line buffer. drawLine pushes (text, expectedYAdvance) here
     *  during a frame; flushHudLines() at end of renderHud() draws them
     *  centered on screen with rounded plates. Cleared each frame. */
    private static final java.util.List<String> hudLineBuffer = new java.util.ArrayList<>();

    /** Push a HUD line into the centered-render queue. Returns the
     *  pseudo-y the caller should track (12 px advance per line — matches
     *  the actual render pitch). The visible position is computed at flush
     *  time based on total enabled-line count, so the entire HUD column
     *  ends up vertically centered on screen. */
    private static int drawLine(Object dc, Object font, String text, int y) throws Exception {
        hudLineBuffer.add(text);
        // Capture which module pushed this line (set by modOn() right
        // before each module's render block). null = the default stack.
        hudLineModules.add(currentModuleRendering);
        return y + 12;
    }

    /** Render all queued HUD lines as a centered Vape Lite-style card stack.
     *  Each line becomes its own card with: dark rounded background, colored
     *  icon square on the left (red brand color), and the text inset to the
     *  right. Cards are individually centered horizontally and the full
     *  column sits at ~40% screen Y for natural eye-line placement.
     *
     *  <p>Called once per frame at the END of renderHud. Always clears
     *  the buffer afterwards even on failure so a render error can't
     *  leave stale lines accumulating across frames.</p>
     */
    /** One-shot startup splash: shows a centered "Shadow Client" banner
     *  for ~4 seconds when the HUD first renders this launch. Slides in
     *  from the top, then fades out. */
    private static void renderStartupSplash(Object dc, Object font) {
        if (splashFirstRenderMs == 0L) return;
        long age = System.currentTimeMillis() - splashFirstRenderMs;
        if (age > 4000) return;
        try {
            int sw = screenWidth(dc);
            int onCount = 0;
            for (Boolean b : MODULES.values()) if (Boolean.TRUE.equals(b)) onCount++;
            int total = MODULES.size();
            String line1 = "§l§cSHADOW §fCLIENT";
            String line2 = "§7" + onCount + "§8/§7" + total + " §8modules active";
            // Width = max of the two lines + padding
            int w1 = stripFormatLength(line1) * 6;
            int w2 = stripFormatLength(line2) * 6;
            int boxW = Math.max(w1, w2) + 36;
            int boxH = 30;
            // Slide in: ease from y=-32 → y=24 over 250ms, hold, fade out last 800ms.
            float slideT = Math.min(1f, age / 250f);
            float slideE = 1f - (float) Math.pow(1f - slideT, 3);
            int targetY = 24;
            int boxY = (int) (-boxH + slideE * (targetY + boxH));
            int boxX = (sw - boxW) / 2;
            // Alpha fade-out in last 800ms
            float aF = 1f;
            if (age > 3200) aF = Math.max(0f, (4000 - age) / 800f);
            int alpha = (int) (0xE8 * aF);
            if (alpha < 8) return;
            // Drop shadow
            for (int s = 1; s <= 3; s++) {
                int sa = (int) ((90 - s * 24) * aF);
                if (sa <= 0) continue;
                roundedRect(dc, boxX - s, boxY + s, boxX + boxW + s, boxY + boxH + s,
                            (sa << 24));
            }
            // Body
            roundedRect(dc, boxX, boxY, boxX + boxW, boxY + boxH,
                        (alpha << 24) | 0x161A24);
            // Top sheen
            roundedRect(dc, boxX + 2, boxY + 2, boxX + boxW - 2, boxY + boxH / 2,
                        ((alpha / 8) << 24) | 0xFFFFFF);
            // Red accent on left
            int aL = (int) (0xFF * aF);
            fill(dc, boxX, boxY + 4, boxX + 3, boxY + boxH - 4,
                 (aL << 24) | 0xAF121A);
            fill(dc, boxX + 3, boxY + 4, boxX + 4, boxY + boxH - 4,
                 ((aL / 3) << 24) | 0xFF2030);
            // Text — centered horizontally
            int t1X = boxX + (boxW - w1) / 2;
            int t2X = boxX + (boxW - w2) / 2;
            draw(dc, font, line1, t1X, boxY + 6,  0xFFFFFFFF);
            draw(dc, font, line2, t2X, boxY + 18, 0xFFFFFFFF);
        } catch (Throwable ignored) {}
    }

    /** Resolve a module's effective anchor — override if set, else global. */
    private static int resolveAnchor(String moduleName) {
        if (moduleName != null && moduleAnchorOverrides.containsKey(moduleName)) {
            return moduleAnchorOverrides.get(moduleName);
        }
        return cfgHudAnchor;
    }

    /** Resolve a module's effective offset — override if set, else global. */
    private static int[] resolveOffset(String moduleName) {
        if (moduleName != null && moduleOffsetOverrides.containsKey(moduleName)) {
            int[] v = moduleOffsetOverrides.get(moduleName);
            if (v != null && v.length == 2) return v;
        }
        return new int[]{cfgHudOffsetX, cfgHudOffsetY};
    }

    private static void flushHudLines(Object dc, Object font) {
        if (hudLineBuffer.isEmpty()) return;
        // Resolve & apply HUD scale via the DrawContext's matrix stack. We
        // do this BEFORE computing card layout so the math uses logical
        // (post-scale) screen dimensions and everything stays anchored.
        float scale = cfgHudScale / 100f;
        Object matStack = null;
        boolean pushed = false;
        if (scale != 1.0f) {
            try {
                matStack = tryInvoke(dc, "method_51448", "getMatrices", "matrices");
                if (matStack != null) {
                    java.lang.reflect.Method push = null, scaleM = null;
                    for (java.lang.reflect.Method m : matStack.getClass().getMethods()) {
                        if (m.getName().equals("pushMatrix") && m.getParameterCount() == 0) push = m;
                        if (m.getName().equals("scale") && m.getParameterCount() == 2
                                && m.getParameterTypes()[0] == float.class) scaleM = m;
                    }
                    if (push != null && scaleM != null) {
                        push.invoke(matStack);
                        scaleM.invoke(matStack, scale, scale);
                        pushed = true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        try {
            int sw = (int)(screenWidth(dc)  / scale);
            int sh = (int)(screenHeight(dc) / scale);
            int n = hudLineBuffer.size();
            int rowH      = 16;
            int cardH     = 14;
            int iconSize  = 8;
            int padL      = 4;
            int iconGap   = 5;
            int padR      = 8;
            int margin    = 8;

            // Group lines by their resolved (anchor, dx, dy). Modules with
            // an anchor override get their own group; everything else
            // collapses into the "default" group.
            java.util.LinkedHashMap<String, java.util.List<int[]>> groups = new java.util.LinkedHashMap<>();
            // groups value entries are int[]{lineIndex, anchor, dx, dy}
            for (int i = 0; i < n; i++) {
                String mod = i < hudLineModules.size() ? hudLineModules.get(i) : null;
                int anc = resolveAnchor(mod);
                int[] off = resolveOffset(mod);
                String key = anc + ":" + off[0] + ":" + off[1];
                groups.computeIfAbsent(key, k -> new java.util.ArrayList<>())
                      .add(new int[]{i, anc, off[0], off[1]});
            }

            // Bounds for editor hit-testing — only the DEFAULT-anchor group
            // (since the editor moves the global stack). Per-module override
            // groups have their own positions and aren't draggable as a unit.
            int frameL = sw, frameR = 0, frameT = sh, frameB = 0;

            for (java.util.Map.Entry<String, java.util.List<int[]>> ent : groups.entrySet()) {
                java.util.List<int[]> lines = ent.getValue();
                int gN = lines.size();
                int gAnc = lines.get(0)[1];
                int gDx  = lines.get(0)[2];
                int gDy  = lines.get(0)[3];
                int gRow = gAnc / 3, gCol = gAnc % 3;
                int totalH = gN * rowH;
                int startY;
                switch (gRow) {
                    case 0: startY = margin; break;
                    case 1: startY = (sh - totalH) / 2 - sh / 8; break;
                    default: startY = sh - totalH - margin;
                }
                startY = Math.max(margin, Math.min(sh - totalH - margin, startY));
                startY += gDy;

                boolean isDefault = (gAnc == cfgHudAnchor
                                     && gDx == cfgHudOffsetX
                                     && gDy == cfgHudOffsetY);

                for (int i = 0; i < gN; i++) {
                    int lineIdx = lines.get(i)[0];
                    String text = hudLineBuffer.get(lineIdx);
                    String lineModule = lineIdx < hudLineModules.size()
                            ? hudLineModules.get(lineIdx) : null;
                    int lineAccent = moduleAccent(lineModule);
                    int y = startY + i * rowH;
                    int textLen = stripFormatLength(text);
                    int textW = textLen * 6;
                    int cardW = padL + iconSize + iconGap + textW + padR;
                    int xL;
                    switch (gCol) {
                        case 0: xL = margin; break;
                        case 1: xL = (sw - cardW) / 2; break;
                        default: xL = sw - cardW - margin;
                    }
                    xL += gDx;
                    int xR = xL + cardW;
                    int yT = y;
                    int yB = y + cardH;
                    if (isDefault) {
                        if (xL < frameL) frameL = xL;
                        if (xR > frameR) frameR = xR;
                        if (yT < frameT) frameT = yT;
                        if (yB > frameB) frameB = yB;
                    }
                    int opa = Math.max(10, Math.min(100, cfgHudOpacity));
                    int shadowA = (0x55 * opa) / 100;
                    int bodyA   = (0xE0 * opa) / 100;
                    int stripeA = (0x66 * opa) / 100;
                    int iconA   = (0xFF * opa) / 100;
                    roundedRect(dc, xL + 1, yT + 1, xR + 1, yB + 1, (shadowA << 24));
                    roundedRect(dc, xL, yT, xR, yB, (bodyA << 24) | 0x151519);
                    roundedRect(dc, xL + 2, yT, xR - 2, yT + 1,
                                (lineAccent & 0x00FFFFFF) | (stripeA << 24));
                    int iconL = xL + padL;
                    int iconT = yT + (cardH - iconSize) / 2;
                    roundedRect(dc, iconL, iconT, iconL + iconSize, iconT + iconSize,
                                (lineAccent & 0x00FFFFFF) | (iconA << 24));
                    fill(dc, iconL + 1, iconT + 1, iconL + 4, iconT + 2,
                         ((0x66 * opa) / 100 << 24) | 0xFFFFFF);
                    int textX = iconL + iconSize + iconGap;
                    int textY = yT + (cardH - 8) / 2;
                    draw(dc, font, text, textX, textY, 0xFFFFFFFF);
                }
            }

            if (frameR > frameL && frameB > frameT) {
                hudFrameL = (int)(frameL * scale);
                hudFrameT = (int)(frameT * scale);
                hudFrameR = (int)(frameR * scale);
                hudFrameB = (int)(frameB * scale);
            }
            if (hudEditMode && frameR > frameL) {
                int eL = frameL - 4, eT = frameT - 4;
                int eR = frameR + 4, eB = frameB + 4;
                long phase = System.currentTimeMillis() % 1000;
                int alpha = (int)(0xC0 + 0x30 * Math.sin(phase / 1000.0 * Math.PI * 2));
                int frameCol = (alpha << 24) | 0xFF2030;
                fill(dc, eL,     eT,     eR,     eT + 1, frameCol);
                fill(dc, eL,     eB - 1, eR,     eB,     frameCol);
                fill(dc, eL,     eT,     eL + 1, eB,     frameCol);
                fill(dc, eR - 1, eT,     eR,     eB,     frameCol);
                int cx = (eL + eR) / 2, cy = (eT + eB) / 2;
                roundedRect(dc, cx - 3, cy - 3, cx + 3, cy + 3, 0xFFFF2030);
                String hint = "§l§cEDIT HUD§r§7 — drag to move §8• §7scroll to resize §8• §7Esc to save";
                int hintW = stripFormatLength(hint) * 6;
                int hintX = cx - hintW / 2;
                int hintY = eB + 6;
                roundedRect(dc, hintX - 6, hintY - 2, hintX + hintW + 6, hintY + 10, 0xE0151519);
                draw(dc, font, hint, hintX, hintY, 0xFFFFFFFF);
            }
        } catch (Throwable t) {
            if (!hudFlushErrorLogged) {
                hudFlushErrorLogged = true;
                System.err.println("[ShadowHud] flushHudLines failed: " + t);
            }
        } finally {
            // Pop the scaling matrix (must run even if rendering threw above)
            if (pushed && matStack != null) {
                try {
                    java.lang.reflect.Method pop = matStack.getClass().getMethod("popMatrix");
                    pop.invoke(matStack);
                } catch (Throwable ignored) {}
            }
            hudLineBuffer.clear();
            hudLineModules.clear();
        }
    }
    private static boolean hudFlushErrorLogged = false;

    /** Rounded rectangle approximation using 5 stacked fill() bands. The
     *  corners are 2-px radius (cut diagonally) which reads as "rounded"
     *  at MC's GUI scale. Falls back to a plain rect for tiny dimensions
     *  (less than 4×4 px) where rounding would just hide the rect entirely. */
    private static void roundedRect(Object dc, int x1, int y1, int x2, int y2, int color) {
        try {
            if (fillMethod == null) return;
            int w = x2 - x1, h = y2 - y1;
            if (w <= 0 || h <= 0) return;
            if (w < 4 || h < 4) {
                fill(dc, x1, y1, x2, y2, color);
                return;
            }
            // 5-band stair-step approximating a 2-px corner radius:
            //   _/aaaaaa\_
            //   /aaaaaaaa\
            //   |aaaaaaaa|
            //   \aaaaaaaa/
            //   _\aaaaaa/_
            fill(dc, x1 + 2, y1,     x2 - 2, y1 + 1, color);  // top edge
            fill(dc, x1 + 1, y1 + 1, x2 - 1, y1 + 2, color);  // 1-px stair
            fill(dc, x1,     y1 + 2, x2,     y2 - 2, color);  // body band
            fill(dc, x1 + 1, y2 - 2, x2 - 1, y2 - 1, color);  // 1-px stair
            fill(dc, x1 + 2, y2 - 1, x2 - 2, y2,     color);  // bottom edge
        } catch (Throwable ignored) {}
    }

    private static boolean drawErrorLogged = false;
    private static void draw(Object dc, Object font, String text, int x, int y, int color) {
        if (drawTextMethod == null) return;
        try {
            Class<?>[] pt = drawTextMethod.getParameterTypes();
            Object[] args = pt.length == 5
                    ? new Object[]{font, text, x, y, color}
                    : new Object[]{font, text, x, y, color, Boolean.TRUE};
            drawTextMethod.invoke(dc, args);
        } catch (Throwable t) {
            if (!drawErrorLogged) {
                drawErrorLogged = true;
                System.err.println("[ShadowHud] draw() failed for text=\"" + text
                    + "\" method=" + drawTextMethod + "  err: " + t);
            }
        }
    }

    // ---- method discovery --------------------------------------------------

    private static Method findDrawText(Class<?> dcClass, Class<?> fontClass) {
        // In MC 1.21.11 drawText returns **void** (older versions returned int = advance).
        // Accept either. Prefer the 6-arg overload (with explicit shadow flag) over
        // the 5-arg variant, and prefer String input over Text (class_2561).
        Method best = null;
        int bestScore = -1;
        for (Method m : dcClass.getMethods()) {
            Class<?> ret = m.getReturnType();
            if (ret != void.class && ret != int.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 5 && p.length != 6) continue;
            if (!p[0].isAssignableFrom(fontClass)) continue;
            boolean isString = p[1] == String.class;
            boolean isText   = p[1].getName().endsWith("class_2561");
            if (!isString && !isText) continue;
            if (p[2] != int.class || p[3] != int.class || p[4] != int.class) continue;
            if (p.length == 6 && p[5] != boolean.class) continue;
            int score = 0;
            if (isString)       score += 2;   // prefer String
            if (p.length == 6)  score += 1;   // prefer shadow-flag overload
            if (score > bestScore) { best = m; bestScore = score; }
        }
        return best;
    }

    private static Method findFill(Class<?> dcClass) {
        // fill(int, int, int, int, int) → void.  Prefer method_25294.
        Method fallback = null;
        for (Method m : dcClass.getMethods()) {
            if (m.getReturnType() != void.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 5) continue;
            if (p[0] != int.class || p[1] != int.class || p[2] != int.class
                    || p[3] != int.class || p[4] != int.class) continue;
            if (m.getName().equals("method_25294") || m.getName().equals("fill")) return m;
            if (fallback == null) fallback = m;
        }
        return fallback;
    }

    /** Pull the biome's short name from a Holder&lt;Biome&gt; / RegistryEntry, trying
     *  every intermediary rename, and falling back to regex on toString(). */
    private static String extractBiomeName(Object holder) {
        if (holder == null) return null;
        // --- path 1: holder.getKey() → Optional → RegistryKey → Identifier.getPath()
        for (String getKey : new String[]{"method_40230", "unwrapKey", "getKey"}) {
            try {
                Object opt = holder.getClass().getMethod(getKey).invoke(holder);
                if (opt != null) {
                    Object pres = tryInvoke(opt, "isPresent");
                    if (Boolean.TRUE.equals(pres)) {
                        Object key = tryInvoke(opt, "get");
                        if (key != null) {
                            Object id = tryInvoke(key, "method_29177", "getValue", "location");
                            if (id != null) {
                                Object path = tryInvoke(id, "method_12832", "getPath");
                                if (path != null) return String.valueOf(path);
                                // fallback: parse "namespace:path" from toString
                                String s = String.valueOf(id);
                                int c = s.indexOf(':');
                                if (c >= 0) return s.substring(c + 1);
                                return s;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        // --- path 2: holder.toString() usually contains the name
        String s = String.valueOf(holder);
        // Common patterns: "Reference{ResourceKey[...=minecraft:plains]=minecraft:plains}" or "Direct{minecraft:plains}"
        int last = s.lastIndexOf("minecraft:");
        if (last >= 0) {
            int end = last + "minecraft:".length();
            StringBuilder out = new StringBuilder();
            while (end < s.length()) {
                char ch = s.charAt(end);
                if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '/') { out.append(ch); end++; }
                else break;
            }
            String picked = out.toString();
            // skip intermediate paths like "worldgen/biome" — we want the final segment
            int slash = picked.lastIndexOf('/');
            if (slash >= 0) picked = picked.substring(slash + 1);
            if (!picked.isEmpty()) return picked;
        }
        return null;
    }

    private static Method findGetBiome(Class<?> worldClass, Class<?> blockPosClass) {
        // In 1.21.11 `getBiome` lives on the LevelReader *interface* (class_4538),
        // not Level directly. `getMethods()` includes inherited interface methods,
        // so we scan that instead of climbing only the superclass chain.
        final String[] CANDIDATES = {"method_23753", "getBiome", "getNoiseBiome"};
        for (Method m : worldClass.getMethods()) {
            boolean nameMatch = false;
            for (String name : CANDIDATES) {
                if (m.getName().equals(name)) { nameMatch = true; break; }
            }
            if (!nameMatch) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0].isAssignableFrom(blockPosClass)) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    // ---- helpers ----------------------------------------------------------

    /**
     * Reflection lookup cache. Resolving {@code Class.getMethod} is among the
     * single most expensive recurring operations in a hot per-frame path —
     * each call walks the entire method table. We cached the resolved
     * {@link Method} (or {@link Field} for value-as-field fallbacks) keyed by
     * {@code ClassName#name} so subsequent frames hit the map instead of
     * walking the reflection metadata.
     *
     * <p>A negative entry ({@code NULL_METHOD}) caches "no such member" so
     * we don't keep paying the {@code NoSuchMethodException} tax for names
     * that are missing in this MC version.</p>
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Method>
        METHOD_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Field>
        FIELD_CACHE  = new java.util.concurrent.ConcurrentHashMap<>();
    /** Sentinel for "we already looked up this name and it doesn't exist".
     *  Reflection is sloooow on cache misses (NoSuchMethodException is
     *  expensive); caching the absence is just as important as caching hits. */
    private static final Method NULL_METHOD;
    private static final Field  NULL_FIELD;
    static {
        try {
            NULL_METHOD = Object.class.getMethod("hashCode");
            NULL_FIELD  = String.class.getDeclaredField("value");
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    /** Cached {@code clazz.getMethod(name)} (no-arg, public). Returns null if
     *  the method doesn't exist. Class objects are stable per class loader,
     *  so the {@code className + '#' + name} key is collision-safe. */
    /** Find the first PUBLIC method matching {@code name} regardless of
     *  parameter list. Use this when you need a method that takes args
     *  ({@link #cachedMethod} only finds no-arg methods because
     *  {@code Class.getMethod(name)} requires you to also know the param
     *  types). Walks all public methods including inherited. Picks the
     *  one with the FEWEST parameters when multiple overloads exist
     *  (defaults to the simplest variant). */
    private static Method findMethodByName(Class<?> clazz, String name) {
        if (clazz == null) return null;
        Method best = null;
        for (Method m : clazz.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (best == null || m.getParameterCount() < best.getParameterCount()) {
                best = m;
            }
        }
        return best;
    }

    private static Method cachedMethod(Class<?> clazz, String name) {
        if (clazz == null) return null;
        String key = clazz.getName() + '#' + name;
        Method m = METHOD_CACHE.get(key);
        if (m != null) return m == NULL_METHOD ? null : m;
        try {
            m = clazz.getMethod(name);
        } catch (NoSuchMethodException e) {
            METHOD_CACHE.put(key, NULL_METHOD);
            return null;
        }
        METHOD_CACHE.put(key, m);
        return m;
    }

    /** Cached {@code getDeclaredField} walked up the class chain — for
     *  value-as-field accessors (e.g. {@code Vec3d.x}). */
    private static Field cachedField(Class<?> clazz, String name) {
        if (clazz == null) return null;
        // Cache key uses the LEAF class so different subclass shapes don't
        // collide. Cost: minor duplication if two leaves share a parent —
        // worth it for correctness.
        String key = clazz.getName() + '#' + name;
        Field f = FIELD_CACHE.get(key);
        if (f != null) return f == NULL_FIELD ? null : f;
        Class<?> c = clazz;
        while (c != null) {
            try {
                Field cand = c.getDeclaredField(name);
                cand.setAccessible(true);
                FIELD_CACHE.put(key, cand);
                return cand;
            } catch (NoSuchFieldException ignored) {}
            c = c.getSuperclass();
        }
        FIELD_CACHE.put(key, NULL_FIELD);
        return null;
    }

    private static Number num(Object o, String name) throws Exception {
        // Try a zero-arg method first (covers getX(), method_23317, etc.)
        Method m = cachedMethod(o.getClass(), name);
        if (m != null) {
            return (Number) m.invoke(o);
        }
        // Fall back to a field (Vec3d.x is a public field, not a getter).
        Field f = cachedField(o.getClass(), name);
        if (f != null) {
            Object v = f.get(o);
            if (v instanceof Number) return (Number) v;
        }
        throw new NoSuchMethodException(name);
    }

    /** Try each method name in order and return the first value we can get.
     *  Lets the same code work across intermediary and yarn mappings. */
    private static Number firstNum(Object target, String... names) throws Exception {
        Throwable last = null;
        for (String n : names) {
            try { return num(target, n); }
            catch (Throwable t) { last = t; }
        }
        throw new NoSuchMethodException("no match of " + java.util.Arrays.toString(names)
                + (last == null ? "" : " (last: " + last + ")"));
    }

    /** Like firstNum, for methods with any return type (with 0 args). */
    private static Object firstMethodInvoke(Object target, Object arg, String... names) throws Exception {
        Throwable last = null;
        for (String n : names) {
            try {
                Method m = arg == null
                        ? target.getClass().getMethod(n)
                        : findSingleArgMethod(target.getClass(), n, arg.getClass());
                if (m == null) continue;
                return arg == null ? m.invoke(target) : m.invoke(target, arg);
            } catch (Throwable t) { last = t; }
        }
        throw new NoSuchMethodException("no match of " + java.util.Arrays.toString(names)
                + (last == null ? "" : " (last: " + last + ")"));
    }

    private static Method findSingleArgMethod(Class<?> cls, String name, Class<?> argCls) {
        Class<?> c = cls;
        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0].isAssignableFrom(argCls)) {
                    m.setAccessible(true);
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** Log each distinct module-line failure exactly once to Minecraft's log. */
    private static final java.util.Set<String> LOGGED = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    /** Tags that have errored (for Diagnostic module's display). Synchronized
     *  set so the render-thread read doesn't race the writer side. Insertion-
     *  ordered so we show the first failures first (most likely culprits). */
    static final java.util.Set<String> ERRORED_TAGS = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    private static void logOnce(String tag, Throwable t) {
        String key = tag + ":" + t.getClass().getName() + ":" + t.getMessage();
        if (LOGGED.add(key)) {
            System.err.println("[ShadowHud][" + tag + "] " + t);
            ERRORED_TAGS.add(tag);
        }
    }

    private static String hpColor(float hp, float max) {
        if (max <= 0) return "§f";
        float pct = hp / max;
        if (pct < 0.30f) return "§c";
        if (pct < 0.60f) return "§e";
        return "§a";
    }

    private static String trimFloat(float v) {
        if (v == (int) v) return Integer.toString((int) v);
        return String.format("%.1f", v);
    }

    // ---- minimap ---------------------------------------------------------

    /** Compact inline minimap rendered at (mx, my). Returns y just below it.
     *
     *  Layout:
     *      ┌─ MAP ───── ###┐   9-px title bar (chunk count on the right)
     *      │ N          │
     *      │W    ↑    E│   64×64 viewport with terrain colors
     *      │    .       │
     *      │ S          │
     *      └────────────┘
    // ---- HitSounds ---------------------------------------------------------

    /** Cached reflection handles for HitSounds — resolved on first play and
     *  reused. Saves the per-shot reflection cost (looking up the SoundManager
     *  + sound-event factories every hit would be silly). */
    private static Object  hsSoundManager;          // class_1144 (SoundManager)
    private static Method  hsSoundManagerPlay;      // SoundManager.play(SoundInstance)
    private static Object  hsSoundEventHit;          // class_3414 instance for "entity.arrow.hit_player"
    private static Method  hsPositionedSoundFactory; // class_1109.method_4757(SoundEvent, pitch, volume)
    private static boolean hsResolveTried;
    private static long    hsLastPlayMs;

    /** Lunar-style hitsound. Plays a short "tink" sound on every successful
     *  attack on an entity. Uses the vanilla {@code entity.arrow.hit_player}
     *  sound (the same one Lunar/Badlion default to). Pitch varies slightly
     *  per hit so multiple-hit combos sound dynamic instead of robotic.
     *
     *  <p>Throttled to one play per 50ms so accidental double-clicks don't
     *  stack the audio. All reflection is cached after the first call.</p>
     */
    private static void playHitSound() {
        long now = System.currentTimeMillis();
        if (now - hsLastPlayMs < 50L) return;
        hsLastPlayMs = now;

        if (!hsResolveTried) {
            hsResolveTried = true;
            try {
                // 1) SoundManager via mc.method_1483()
                Method getSm = null;
                for (Method m : mc.getClass().getMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    String n = m.getName();
                    if ((n.equals("method_1483") || n.equals("getSoundManager"))
                        && m.getReturnType().getName().equals("net.minecraft.class_1144")) {
                        getSm = m; break;
                    }
                }
                if (getSm != null) hsSoundManager = getSm.invoke(mc);
                if (hsSoundManager != null) {
                    for (Method m : hsSoundManager.getClass().getMethods()) {
                        if (m.getParameterCount() != 1) continue;
                        if (!"method_4873".equals(m.getName()) && !"play".equals(m.getName())) continue;
                        if (m.getParameterTypes()[0].getName().equals("net.minecraft.class_1113")) {
                            hsSoundManagerPlay = m; break;
                        }
                    }
                }

                // 2) Build SoundEvent via class_3414.method_47908(Identifier).
                Class<?> seCls  = Class.forName("net.minecraft.class_3414");
                Class<?> idCls  = Class.forName("net.minecraft.class_2960");
                Method idOf = null;
                for (Method m : idCls.getMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount() != 2) continue;
                    if (m.getParameterTypes()[0] != String.class) continue;
                    if (m.getParameterTypes()[1] != String.class) continue;
                    String n = m.getName();
                    if (n.equals("method_60655") || n.equals("of")) {
                        idOf = m; break;
                    }
                }
                Object id = (idOf != null)
                    ? idOf.invoke(null, "minecraft", "entity.arrow.hit_player") : null;
                if (id != null) {
                    for (Method m : seCls.getMethods()) {
                        if (!Modifier.isStatic(m.getModifiers())) continue;
                        if (m.getParameterCount() != 1) continue;
                        if (!idCls.isAssignableFrom(m.getParameterTypes()[0])) continue;
                        String n = m.getName();
                        if (n.equals("method_47908") || n.equals("of") || n.equals("create")) {
                            hsSoundEventHit = m.invoke(null, id);
                            break;
                        }
                    }
                }

                // 3) PositionedSoundInstance.master(SoundEvent, pitch, volume)
                //    intermediary method_4757(class_3414, float, float).
                Class<?> psiCls = Class.forName("net.minecraft.class_1109");
                for (Method m : psiCls.getMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount() != 3) continue;
                    if (m.getParameterTypes()[0] != seCls) continue;
                    if (m.getParameterTypes()[1] != float.class) continue;
                    if (m.getParameterTypes()[2] != float.class) continue;
                    if (!m.getName().equals("method_4757")
                        && !m.getName().equals("master")) continue;
                    hsPositionedSoundFactory = m;
                    break;
                }

                System.out.println("[ShadowHud][HitSound] resolve — sm=" + (hsSoundManager != null)
                    + " play=" + (hsSoundManagerPlay != null)
                    + " event=" + (hsSoundEventHit != null)
                    + " factory=" + (hsPositionedSoundFactory != null));
            } catch (Throwable t) {
                System.err.println("[ShadowHud][HitSound] resolve threw: " + t);
            }
        }

        if (hsSoundManager == null || hsSoundManagerPlay == null
            || hsSoundEventHit == null || hsPositionedSoundFactory == null) return;

        // Pitch in [0.85, 1.15] for natural variation. Volume 1.0.
        float pitch = 0.85f + (float) Math.random() * 0.30f;
        try {
            Object inst = hsPositionedSoundFactory.invoke(null, hsSoundEventHit, pitch, 1.0f);
            if (inst != null) hsSoundManagerPlay.invoke(hsSoundManager, inst);
        } catch (Throwable ignored) {}
    }

    /** Soft UI click — used when the user toggles a module. Resolves the
     *  "ui.button.click" SoundEvent on first call, then plays it through
     *  the same SoundManager pipeline as playHitSound. */
    private static Object  uiClickSoundEvent;
    private static boolean uiClickResolveTried = false;
    private static long    uiClickLastMs = 0L;
    /** True after we've resolved the COMMON sound infra (SoundManager,
     *  play(), PositionedSound factory). Separate from hsResolveTried so
     *  playUiClick warming the cache doesn't accidentally short-circuit
     *  playHitSound's hsSoundEventHit resolution. */
    private static boolean hsInfraResolved = false;

    /** Initialize the SoundManager + factory side of playHitSound() WITHOUT
     *  actually firing a hit. Used by playUiClick to warm the cache without
     *  surprising the user with a hit sound on the first module toggle.
     *  Does NOT set hsResolveTried — that gate is for the hit-sound-event
     *  resolution which playHitSound owns. */
    private static void resolveSoundInfra() {
        if (hsInfraResolved) return;
        hsInfraResolved = true;
        try {
            // SoundManager
            for (Method m : mc.getClass().getMethods()) {
                if (m.getParameterCount() != 0) continue;
                String n = m.getName();
                if ((n.equals("method_1483") || n.equals("getSoundManager"))
                        && m.getReturnType().getName().equals("net.minecraft.class_1144")) {
                    hsSoundManager = m.invoke(mc); break;
                }
            }
            if (hsSoundManager != null) {
                for (Method m : hsSoundManager.getClass().getMethods()) {
                    if (m.getParameterCount() != 1) continue;
                    if (!"method_4873".equals(m.getName()) && !"play".equals(m.getName())) continue;
                    if (m.getParameterTypes()[0].getName().equals("net.minecraft.class_1113")) {
                        hsSoundManagerPlay = m; break;
                    }
                }
            }
            // PositionedSoundInstance.master(SoundEvent, pitch, volume)
            Class<?> seCls = Class.forName("net.minecraft.class_3414");
            Class<?> psiCls = Class.forName("net.minecraft.class_1109");
            for (Method m : psiCls.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers())) continue;
                if (m.getParameterCount() != 3) continue;
                if (m.getParameterTypes()[0] != seCls) continue;
                if (m.getParameterTypes()[1] != float.class) continue;
                if (m.getParameterTypes()[2] != float.class) continue;
                if (!m.getName().equals("method_4757") && !m.getName().equals("master")) continue;
                hsPositionedSoundFactory = m; break;
            }
        } catch (Throwable ignored) {}
    }

    private static void playUiClick() {
        long now = System.currentTimeMillis();
        if (now - uiClickLastMs < 30L) return;   // debounce rapid toggles
        uiClickLastMs = now;
        // Resolve infra silently — no hit sound side effect.
        resolveSoundInfra();
        if (!uiClickResolveTried) {
            uiClickResolveTried = true;
            try {
                Class<?> seCls = Class.forName("net.minecraft.class_3414");
                Class<?> idCls = Class.forName("net.minecraft.class_2960");
                Method idOf = null;
                for (Method m : idCls.getMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getParameterCount() != 2) continue;
                    if (m.getParameterTypes()[0] != String.class) continue;
                    if (m.getParameterTypes()[1] != String.class) continue;
                    String n = m.getName();
                    if (n.equals("method_60655") || n.equals("of")) { idOf = m; break; }
                }
                Object id = idOf != null
                    ? idOf.invoke(null, "minecraft", "ui.button.click") : null;
                if (id != null) {
                    for (Method m : seCls.getMethods()) {
                        if (!Modifier.isStatic(m.getModifiers())) continue;
                        if (m.getParameterCount() != 1) continue;
                        if (!idCls.isAssignableFrom(m.getParameterTypes()[0])) continue;
                        String n = m.getName();
                        if (n.equals("method_47908") || n.equals("of") || n.equals("create")) {
                            uiClickSoundEvent = m.invoke(null, id);
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        if (hsSoundManager == null || hsSoundManagerPlay == null
            || hsPositionedSoundFactory == null || uiClickSoundEvent == null) return;
        try {
            Object inst = hsPositionedSoundFactory.invoke(null, uiClickSoundEvent, 1.0f, 0.4f);
            if (inst != null) hsSoundManagerPlay.invoke(hsSoundManager, inst);
        } catch (Throwable ignored) {}
    }

    /** Cached reflection handles for EnchantPreview. Resolved on first
     *  successful enchant-screen open and reused. method_7637 is private,
     *  so we setAccessible(true) on it. */
    private static Method  enchPreviewMethod7637;       // class_1718.method_7637 (List generator)
    private static Field   enchPreviewLevelArrayField;  // class_1718.field_7808 (int[3] level cost)
    private static Field   enchPreviewInventoryField;   // class_1718.field_7809 (Inventory)
    private static boolean enchPreviewResolveTried = false;
    private static boolean enchPreviewLogged = false;
    private static boolean enchPreviewWrongScreenLogged = false;
    private static boolean enchPreviewLevelsLogged = false;
    private static boolean enchPreviewWindowLogged = false;
    private static boolean enchPreviewMouseLogged = false;

    /** Screen-hook variant of the preview render. Called from the
     *  ScreenEvents.AFTER_RENDER callback registered on an EnchantmentScreen
     *  instance. Receives the screen reference directly (no need to look it
     *  up from mc.currentScreen) so it's race-condition-free. Renders ON
     *  TOP of the screen's UI for guaranteed visibility. */
    private static void renderEnchantPreviewOnScreen(Object screen, Object dc, Object font, Object player) throws Exception {
        if (screen == null || dc == null || font == null || player == null) return;

        // Get GUI top-left from inherited fields (HandledScreen has field_2776/field_2800).
        Field xField = cachedField(screen.getClass(), "field_2776");
        Field yField = cachedField(screen.getClass(), "field_2800");
        if (xField == null || yField == null) return;
        int guiX = xField.getInt(screen);
        int guiY = yField.getInt(screen);

        // Pull screen handler from class_465.field_2797.
        Field handlerField = cachedField(screen.getClass(), "field_2797");
        if (handlerField == null) return;
        Object handler = handlerField.get(screen);
        if (handler == null) return;

        if (!enchPreviewResolveTried) {
            enchPreviewResolveTried = true;
            try {
                Class<?> ench = Class.forName("net.minecraft.class_1718");
                for (Field f : ench.getDeclaredFields()) {
                    if (f.getType() == int[].class && "field_7808".equals(f.getName())) {
                        enchPreviewLevelArrayField = f; break;
                    }
                }
                Class<?> invIface = Class.forName("net.minecraft.class_1263");
                for (Field f : ench.getDeclaredFields()) {
                    if (invIface.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        enchPreviewInventoryField = f; break;
                    }
                }
                for (Method m : ench.getDeclaredMethods()) {
                    if (!"method_7637".equals(m.getName())) continue;
                    if (m.getParameterCount() != 4) continue;
                    m.setAccessible(true);
                    enchPreviewMethod7637 = m; break;
                }
                System.out.println("[ShadowHud][EnchantPreview] resolve (screen-hook) — m7637="
                    + (enchPreviewMethod7637 != null) + " levelArr="
                    + (enchPreviewLevelArrayField != null) + " inv="
                    + (enchPreviewInventoryField != null));
            } catch (Throwable t) {
                System.err.println("[ShadowHud][EnchantPreview] resolve failed: " + t);
                return;
            }
        }
        if (enchPreviewMethod7637 == null || enchPreviewLevelArrayField == null
            || enchPreviewInventoryField == null) return;

        int[] levels = (int[]) enchPreviewLevelArrayField.get(handler);
        if (levels == null || levels.length < 3) return;
        if (levels[0] <= 0 && levels[1] <= 0 && levels[2] <= 0) return;

        // Stack from inventory slot 0
        Object inv = enchPreviewInventoryField.get(handler);
        if (inv == null) return;
        Object stack = null;
        for (Method m : inv.getClass().getMethods()) {
            if (!"method_5438".equals(m.getName()) && !"getStack".equals(m.getName())) continue;
            if (m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] != int.class) continue;
            try { stack = m.invoke(inv, 0); } catch (Throwable ignored) {}
            if (stack != null) break;
        }
        if (stack == null) return;
        Object empty = tryInvoke(stack, "method_7960", "isEmpty");
        if (Boolean.TRUE.equals(empty)) return;

        Object world = worldField != null ? worldField.get(mc) : null;
        if (world == null) return;
        Object registry = tryInvoke(world, "method_30349", "getRegistryManager");
        if (registry == null) return;

        // Build all 3 slot lines.
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("§c§lEnchant Preview");
        for (int slot = 0; slot < 3; slot++) {
            int level = levels[slot];
            if (level <= 0) {
                lines.add("§7Slot " + (slot + 1) + ": §8(empty)");
                continue;
            }
            String levelCol = level <= 10 ? "§a" : level <= 20 ? "§e" : "§c";
            lines.add("§fSlot " + (slot + 1) + " §8• §7lvl " + levelCol + level);
            try {
                Object listObj = enchPreviewMethod7637.invoke(handler, registry, stack, slot, level);
                if (!(listObj instanceof java.util.List)) {
                    lines.add("  §c(no enchants)"); continue;
                }
                java.util.List<?> entries = (java.util.List<?>) listObj;
                if (entries.isEmpty()) { lines.add("  §8(none)"); continue; }
                for (Object entry : entries) {
                    String name = "?";
                    int lvl = 1;
                    try {
                        Object enchEntry = tryInvoke(entry, "comp_3486");
                        Object lvlObj = tryInvoke(entry, "comp_3487");
                        if (lvlObj instanceof Number) lvl = ((Number) lvlObj).intValue();
                        if (enchEntry != null) {
                            Object optKey = tryInvoke(enchEntry, "method_40230", "getKey");
                            if (optKey instanceof java.util.Optional) {
                                java.util.Optional<?> opt = (java.util.Optional<?>) optKey;
                                if (opt.isPresent()) {
                                    Object id = tryInvoke(opt.get(), "method_29177");
                                    if (id != null) {
                                        String full = String.valueOf(id);
                                        int colon = full.indexOf(':');
                                        String path = (colon >= 0) ? full.substring(colon + 1) : full;
                                        StringBuilder sb = new StringBuilder();
                                        boolean cap = true;
                                        for (int i = 0; i < path.length(); i++) {
                                            char c = path.charAt(i);
                                            if (c == '_') { sb.append(' '); cap = true; }
                                            else if (cap) { sb.append(Character.toUpperCase(c)); cap = false; }
                                            else { sb.append(c); }
                                        }
                                        name = sb.toString();
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    lines.add("  §a✦ §f" + name + " §7" + romanNumeral(lvl));
                }
            } catch (Throwable t) {
                lines.add("  §c(err: " + t.getClass().getSimpleName() + ")");
            }
        }

        if (!enchPreviewLogged) {
            enchPreviewLogged = true;
            System.out.println("[ShadowHud][EnchantPreview] panel rendering ON-TOP — "
                + lines.size() + " lines");
        }

        // Compute panel size + position right-of-GUI (or left if it overflows).
        int maxW = 0;
        for (String line : lines) {
            int len = stripFormatLength(line);
            if (len * 6 > maxW) maxW = len * 6;
        }
        int boxW = maxW + 12;
        int boxH = lines.size() * 11 + 6;
        int sw = screenWidth(dc);
        int sh = screenHeight(dc);
        int boxX = guiX + 176 + 8;
        int boxY = guiY;
        if (boxX + boxW > sw - 4) boxX = guiX - boxW - 8;
        if (boxX < 4) boxX = 4;
        if (boxY + boxH > sh - 4) boxY = sh - boxH - 4;
        if (boxY < 4) boxY = 4;
        // Render plate
        if (fillMethod != null) {
            fill(dc, boxX, boxY, boxX + boxW, boxY + boxH, 0xF0050505);
            fill(dc, boxX, boxY, boxX + boxW, boxY + 1,                 0xFFAF121A);
            fill(dc, boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH,   0xFFAF121A);
            fill(dc, boxX, boxY, boxX + 1, boxY + boxH,                 0xFFAF121A);
            fill(dc, boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH,   0xFFAF121A);
        }
        for (int i = 0; i < lines.size(); i++) {
            draw(dc, font, lines.get(i), boxX + 6, boxY + 4 + i * 11, 0xFFFFFFFF);
        }
    }

    /** Enchantment-Revealer-style preview. When the player has the enchanting
     *  table screen open and is hovering one of the 3 enchant buttons, draw a
     *  small tooltip next to the cursor showing every enchantment that slot
     *  would apply — not just the vanilla "top one" tooltip.
     *
     *  <p>Vanilla protocol only sends the FIRST enchantment ID per slot to
     *  the client. The remaining ones are computed server-side at click time.
     *  We replicate the same calculation locally by calling
     *  {@code class_1718.method_7637(registry, stack, slot, level)} via
     *  reflection — the exact server method, deterministic on
     *  {@code xpSeed + slot}, so the prediction matches reality.</p>
     *
     *  <p>The 3 enchant buttons in the GUI texture are at
     *  (relative to GUI top-left):
     *  <pre>
     *    button 0: x=60..168, y=14..33
     *    button 1: x=60..168, y=33..52
     *    button 2: x=60..168, y=52..71
     *  </pre>
     *  We hit-test mouse position against those rectangles and only draw the
     *  tooltip for the slot under the cursor.</p>
     */
    private static void renderEnchantPreview(Object dc, Object font, Object player) throws Exception {
        if (mc == null) return;
        // Need the EnchantmentScreen open (class_486 extends HandledScreen<EnchantmentScreenHandler>).
        Object screen = tryInvoke(mc, "method_1755", "getCurrentScreen");
        if (screen == null) {
            Field csF = cachedField(mc.getClass(), "field_1755");
            if (csF == null) csF = cachedField(mc.getClass(), "currentScreen");
            if (csF != null) screen = csF.get(mc);
        }
        if (screen == null) return;
        Class<?> enchScreenCls;
        try { enchScreenCls = Class.forName("net.minecraft.class_486"); }
        catch (ClassNotFoundException e) { return; }
        if (!enchScreenCls.isInstance(screen)) {
            // Debug: log the actual screen class once when we have ANY screen,
            // so if singleplayer uses a different class we'll see it.
            if (!enchPreviewWrongScreenLogged) {
                String n = screen.getClass().getName();
                if (n.contains("nchant")) {  // looks enchantment-related?
                    enchPreviewWrongScreenLogged = true;
                    System.out.println("[ShadowHud][EnchantPreview] saw enchant-like screen but not class_486: " + n);
                }
            }
            return;
        }

        // Get the screen's GUI top-left (HandledScreen has field_2776 / field_2800).
        Field xField = cachedField(screen.getClass(), "field_2776");
        Field yField = cachedField(screen.getClass(), "field_2800");
        if (xField == null || yField == null) return;
        int guiX = xField.getInt(screen);
        int guiY = yField.getInt(screen);

        // Pull the screen handler — class_465.field_2797 is the bound handler
        // (intermediary), public via inheritance.
        Field handlerField = cachedField(screen.getClass(), "field_2797");
        if (handlerField == null) handlerField = cachedField(screen.getClass(), "handler");
        Object handler = handlerField != null ? handlerField.get(screen) : null;
        if (handler == null) return;

        if (!enchPreviewResolveTried) {
            enchPreviewResolveTried = true;
            try {
                Class<?> ench = Class.forName("net.minecraft.class_1718");
                for (Field f : ench.getDeclaredFields()) {
                    if (f.getType() == int[].class && "field_7808".equals(f.getName())) {
                        enchPreviewLevelArrayField = f;
                        break;
                    }
                }
                Class<?> invIface = Class.forName("net.minecraft.class_1263");
                for (Field f : ench.getDeclaredFields()) {
                    if (invIface.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        enchPreviewInventoryField = f;
                        break;
                    }
                }
                for (Method m : ench.getDeclaredMethods()) {
                    if (!"method_7637".equals(m.getName())) continue;
                    if (m.getParameterCount() != 4) continue;
                    m.setAccessible(true);
                    enchPreviewMethod7637 = m;
                    break;
                }
                System.out.println("[ShadowHud][EnchantPreview] resolve — m7637="
                    + (enchPreviewMethod7637 != null) + " levelArr="
                    + (enchPreviewLevelArrayField != null) + " inv="
                    + (enchPreviewInventoryField != null));
            } catch (Throwable t) {
                System.err.println("[ShadowHud][EnchantPreview] resolve failed: " + t);
            }
        }
        if (enchPreviewMethod7637 == null || enchPreviewLevelArrayField == null
            || enchPreviewInventoryField == null) return;

        // Read level-cost array — three ints, one per slot.
        int[] levels = (int[]) enchPreviewLevelArrayField.get(handler);
        if (levels == null || levels.length < 3) return;
        if (!enchPreviewLevelsLogged && (levels[0] > 0 || levels[1] > 0 || levels[2] > 0)) {
            enchPreviewLevelsLogged = true;
            System.out.println("[ShadowHud][EnchantPreview] levels=["
                + levels[0] + "," + levels[1] + "," + levels[2] + "]");
        }
        // Bail unless at least one slot has populated — otherwise we'd be
        // drawing an empty panel for an item-less enchant table.
        if (levels[0] <= 0 && levels[1] <= 0 && levels[2] <= 0) return;

        // Get input stack + registry for the prediction call.
        Object inv = enchPreviewInventoryField.get(handler);
        if (inv == null) return;
        Object stack = null;
        for (Method m : inv.getClass().getMethods()) {
            if (!"method_5438".equals(m.getName()) && !"getStack".equals(m.getName())) continue;
            if (m.getParameterCount() != 1) continue;
            if (m.getParameterTypes()[0] != int.class) continue;
            try { stack = m.invoke(inv, 0); } catch (Throwable ignored) {}
            if (stack != null) break;
        }
        if (stack == null) {
            if (!enchPreviewMouseLogged) {
                enchPreviewMouseLogged = true;
                System.out.println("[ShadowHud][EnchantPreview] stack=null — bailing");
            }
            return;
        }
        Object empty = tryInvoke(stack, "method_7960", "isEmpty");
        if (Boolean.TRUE.equals(empty)) return;

        Object world = worldField != null ? worldField.get(mc) : null;
        if (world == null) return;
        Object registry = tryInvoke(world, "method_30349", "getRegistryManager");
        if (registry == null) return;

        // Generate predictions for ALL THREE slots up-front, build up the
        // text lines once. Always-visible panel is more reliable than the
        // hover-tooltip approach which had issues in singleplayer with
        // mouse coordinate scaling.
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("§c§lEnchant Preview");
        for (int slot = 0; slot < 3; slot++) {
            int level = levels[slot];
            if (level <= 0) {
                lines.add("§7Slot " + (slot + 1) + ": §8(empty)");
                continue;
            }
            String levelCol = level <= 10 ? "§a" : level <= 20 ? "§e" : "§c";
            lines.add("§fSlot " + (slot + 1) + " §8• §7lvl " + levelCol + level);
            try {
                Object listObj = enchPreviewMethod7637.invoke(handler, registry, stack, slot, level);
                if (!(listObj instanceof java.util.List)) {
                    lines.add("  §c(no enchants)");
                    continue;
                }
                java.util.List<?> entries = (java.util.List<?>) listObj;
                if (entries.isEmpty()) {
                    lines.add("  §8(none)");
                    continue;
                }
                for (Object entry : entries) {
                    String name = "?";
                    int lvl = 1;
                    try {
                        Object enchEntry = tryInvoke(entry, "comp_3486");
                        Object lvlObj = tryInvoke(entry, "comp_3487");
                        if (lvlObj instanceof Number) lvl = ((Number) lvlObj).intValue();
                        if (enchEntry != null) {
                            Object optKey = tryInvoke(enchEntry, "method_40230", "getKey");
                            if (optKey instanceof java.util.Optional) {
                                java.util.Optional<?> opt = (java.util.Optional<?>) optKey;
                                if (opt.isPresent()) {
                                    Object id = tryInvoke(opt.get(), "method_29177");
                                    if (id != null) {
                                        String full = String.valueOf(id);
                                        int colon = full.indexOf(':');
                                        String path = (colon >= 0) ? full.substring(colon + 1) : full;
                                        StringBuilder sb = new StringBuilder();
                                        boolean cap = true;
                                        for (int i = 0; i < path.length(); i++) {
                                            char c = path.charAt(i);
                                            if (c == '_') { sb.append(' '); cap = true; }
                                            else if (cap) { sb.append(Character.toUpperCase(c)); cap = false; }
                                            else { sb.append(c); }
                                        }
                                        name = sb.toString();
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    lines.add("  §a✦ §f" + name + " §7" + romanNumeral(lvl));
                }
            } catch (Throwable t) {
                lines.add("  §c(err: " + t.getClass().getSimpleName() + ")");
            }
        }

        if (!enchPreviewLogged) {
            enchPreviewLogged = true;
            System.out.println("[ShadowHud][EnchantPreview] panel rendering — "
                + lines.size() + " lines");
        }

        // Compute panel dimensions. Use 6 px per char (close enough to MC's
        // font width). Add gap between slots visually.
        int maxW = 0;
        for (String line : lines) {
            int len = stripFormatLength(line);
            if (len * 6 > maxW) maxW = len * 6;
        }
        int boxW = maxW + 12;
        int boxH = lines.size() * 11 + 6;

        // Position to the RIGHT of the GUI (the enchanting table is 176px wide,
        // anchored at field_2776). Place the panel just right of the GUI's
        // right edge; if it would overflow the screen, fall back to the left.
        int sw = screenWidth(dc);
        int sh = screenHeight(dc);
        int boxX = guiX + 176 + 8;
        int boxY = guiY;
        if (boxX + boxW > sw - 4) boxX = guiX - boxW - 8;     // place to LEFT of GUI
        if (boxX < 4) boxX = 4;                                // far-left clamp
        if (boxY + boxH > sh - 4) boxY = sh - boxH - 4;
        if (boxY < 4) boxY = 4;

        // Render: dark plate with red accent border.
        if (fillMethod != null) {
            fill(dc, boxX, boxY, boxX + boxW, boxY + boxH, 0xF0050505);
            fill(dc, boxX, boxY, boxX + boxW, boxY + 1,                 0xFFAF121A);
            fill(dc, boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH,   0xFFAF121A);
            fill(dc, boxX, boxY, boxX + 1, boxY + boxH,                 0xFFAF121A);
            fill(dc, boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH,   0xFFAF121A);
        }
        for (int i = 0; i < lines.size(); i++) {
            draw(dc, font, lines.get(i), boxX + 6, boxY + 4 + i * 11, 0xFFFFFFFF);
        }
    }

    /** String length minus the §X color-code pairs. Used for tooltip width
     *  estimation without paying the actual font-width API call every frame. */
    private static int stripFormatLength(String s) {
        int len = s.length();
        int color = 0;
        for (int i = 0; i < s.length() - 1; i++) {
            if (s.charAt(i) == '§') { color += 2; i++; }
        }
        return len - color;
    }

    /** DamageIndicator render — show estimated damage of the held weapon
     *  when the user is targeting an entity. Damage is estimated from the
     *  weapon material tier (sword/axe) plus sharpness enchantment level.
     *
     *  <p>Vanilla numbers (without enchantments):
     *  <pre>
     *    SWORD     AXE
     *    Wooden 4  Wooden 7
     *    Stone  5  Stone  9
     *    Gold   4  Gold   7
     *    Iron   6  Iron   9
     *    Diamond 7 Diamond 9
     *    Netherite 8 Netherite 10
     *  </pre>
     *  Sharpness adds 0.5 per level + 0.5 base.
     *  </p>
     */
    // renderDamageIndicator() removed — combat-prediction PvP hack.

    /** DeathLog state. Last "is dead" sample so we only log on the rising
     *  edge (player WAS alive, IS now dead). */
    private static boolean deathLogPrevDead = false;
    private static final Path DEATH_LOG_FILE =
        Paths.get("config", "shadowclient-deaths.txt");

    /** Append the current player position + dimension + timestamp to the
     *  death log file. Called from {@link #pollInput()} on the rising edge
     *  of "isDead", so once per actual death. */
    private static void writeDeathLog(Object player) {
        try {
            double x = firstNum(player, "method_23317", "getX").doubleValue();
            double y = firstNum(player, "method_23318", "getY").doubleValue();
            double z = firstNum(player, "method_23321", "getZ").doubleValue();
            Object world = worldField != null ? worldField.get(mc) : null;
            String dim = "?";
            if (world != null) {
                Object regKey = tryInvoke(world, "method_27983", "getRegistryKey");
                if (regKey != null) {
                    Object idObj = tryInvoke(regKey, "method_29177", "getValue");
                    if (idObj != null) dim = String.valueOf(idObj);
                }
            }
            Files.createDirectories(DEATH_LOG_FILE.getParent());
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new java.util.Date());
            String line = String.format("%s  (%.1f, %.1f, %.1f)  %s%n",
                stamp, x, y, z, dim);
            Files.write(DEATH_LOG_FILE, line.getBytes(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
            System.out.println("[ShadowHud][DeathLog] wrote " + line.trim());
            flashToast("§c☠ §fdeath logged §8(" + (int)x + ", " + (int)y + ", " + (int)z + ")");
        } catch (Throwable t) {
            logOnce("DeathLog", t);
        }
    }

    /** CoordsHistory state. Last position we appended to the history file —
     *  next position only writes if it's > 100 blocks (squared > 10000) away. */
    private static double coordsHistLastX = Double.NaN;
    private static double coordsHistLastZ = Double.NaN;
    private static long   coordsHistLastWriteMs = 0;
    private static final Path COORDS_HISTORY_FILE =
        Paths.get("config", "shadowclient-coords-history.txt");
    private static final double COORDS_HISTORY_MIN_DIST_SQ = 100.0 * 100.0;
    private static final long   COORDS_HISTORY_MIN_INTERVAL_MS = 5_000L;

    /** Append the current position to coords history if we've moved more
     *  than 100 blocks XZ from the last logged spot AND at least 5 seconds
     *  have passed (so we don't write a record for every tick of an
     *  elytra dive). */
    private static void tickCoordsHistory(Object player) {
        try {
            long now = System.currentTimeMillis();
            if (now - coordsHistLastWriteMs < COORDS_HISTORY_MIN_INTERVAL_MS) return;
            double x = firstNum(player, "method_23317", "getX").doubleValue();
            double z = firstNum(player, "method_23321", "getZ").doubleValue();
            if (Double.isNaN(coordsHistLastX)) {
                // First sample — initialize without writing (avoid a
                // spurious entry every fresh launch).
                coordsHistLastX = x; coordsHistLastZ = z;
                return;
            }
            double dx = x - coordsHistLastX;
            double dz = z - coordsHistLastZ;
            if (dx*dx + dz*dz < COORDS_HISTORY_MIN_DIST_SQ) return;
            // Write
            double y = firstNum(player, "method_23318", "getY").doubleValue();
            Files.createDirectories(COORDS_HISTORY_FILE.getParent());
            String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm")
                .format(new java.util.Date());
            String line = String.format("%s  (%.0f, %.0f, %.0f)%n", stamp, x, y, z);
            Files.write(COORDS_HISTORY_FILE, line.getBytes(),
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
            coordsHistLastX = x; coordsHistLastZ = z;
            coordsHistLastWriteMs = now;
        } catch (Throwable t) { logOnce("CoordsHistory", t); }
    }

    /** EnchantList render — list each enchantment on the held item with its
     *  level. Uses 1.21.x component-based enchantment access:
     *
     *  <pre>
     *    ItemEnchantments comps = stack.method_58657();   // class_9304
     *    for (Object2IntMap.Entry e : comps.method_57539()) {
     *        RegistryEntry&lt;Enchantment&gt; ench = e.getKey();   // class_6880
     *        int level = e.getIntValue();
     *        Identifier id = ench.method_40230().get().method_29177();
     *        // "minecraft:sharpness" → "Sharpness"
     *    }
     *  </pre>
     *
     *  Falls back gracefully if class_9304 / class_6880 aren't present (e.g.
     *  older MC build) — shows just the glint state. */
    private static int renderEnchantList(Object player, Object dc, Object font, int y) throws Exception {
        Object stack = tryInvoke(player, "method_6047", "getMainHandStack");
        if (stack == null) {
            return drawLine(dc, font, "§4Ench §8(no item)", y);
        }
        Object empty = tryInvoke(stack, "method_7960", "isEmpty");
        if (Boolean.TRUE.equals(empty)) {
            return drawLine(dc, font, "§4Ench §8(empty hand)", y);
        }
        // 1.21.x component-based enchantment storage. Fall back to glint
        // boolean if the class isn't available (older MC).
        Object comps = tryInvoke(stack, "method_58657", "getEnchantments",
                                 "method_7921");
        if (comps == null) {
            // Last-resort: just show enchanted/plain
            Object hasGlint = tryInvoke(stack, "method_7986", "hasGlint",
                                        "method_7958", "isEnchanted");
            String line = Boolean.TRUE.equals(hasGlint)
                ? "§4Ench §a✦ §fenchanted §8(component API missing)"
                : "§4Ench §8(plain)";
            return drawLine(dc, font, line, y);
        }
        // Get the entry set: method_57539 → Set<Object2IntMap.Entry<RegistryEntry<Enchantment>>>
        Object entries = tryInvoke(comps, "method_57539", "getEnchantmentEntries",
                                   "entrySet");
        if (!(entries instanceof Iterable)) {
            // Try size accessor for empty diagnostic
            Object size = tryInvoke(comps, "method_57541", "getSize", "size");
            if (size instanceof Number && ((Number) size).intValue() == 0) {
                return drawLine(dc, font, "§4Ench §8(none)", y);
            }
            return drawLine(dc, font, "§4Ench §c✗ entry-set unavailable", y);
        }
        int count = 0;
        for (Object entry : (Iterable<?>) entries) {
            count++;
            String name = "?";
            int level = 1;
            try {
                // Object2IntMap.Entry — getKey() returns RegistryEntry<Enchantment>
                Object key = tryInvoke(entry, "getKey");
                Object lvlObj = tryInvoke(entry, "getIntValue", "getValue");
                if (lvlObj instanceof Number) level = ((Number) lvlObj).intValue();
                // RegistryEntry.method_40230() → Optional<RegistryKey<Enchantment>>
                if (key != null) {
                    Object optKey = tryInvoke(key, "method_40230", "getKey");
                    if (optKey instanceof java.util.Optional) {
                        java.util.Optional<?> opt = (java.util.Optional<?>) optKey;
                        if (opt.isPresent()) {
                            Object regKey = opt.get();
                            // RegistryKey.method_29177() → Identifier
                            Object id = tryInvoke(regKey, "method_29177", "getValue");
                            if (id != null) {
                                String full = String.valueOf(id);   // "minecraft:sharpness"
                                int colon = full.indexOf(':');
                                String path = (colon >= 0) ? full.substring(colon + 1) : full;
                                // "infinity_arrows" → "Infinity Arrows"
                                StringBuilder sb = new StringBuilder();
                                boolean cap = true;
                                for (int i = 0; i < path.length(); i++) {
                                    char c = path.charAt(i);
                                    if (c == '_') { sb.append(' '); cap = true; }
                                    else if (cap) { sb.append(Character.toUpperCase(c)); cap = false; }
                                    else { sb.append(c); }
                                }
                                name = sb.toString();
                            }
                        }
                    }
                    // Last resort: method_55840 returns a debug string
                    if (name.equals("?")) {
                        Object dbg = tryInvoke(key, "method_55840");
                        if (dbg != null) name = String.valueOf(dbg);
                    }
                }
            } catch (Throwable ignored) {}
            // Roman numeral level (capped at X for sanity).
            String roman = romanNumeral(level);
            y = drawLine(dc, font,
                "§4Ench §a✦ §f" + name + " §7" + roman, y);
            // Cap to avoid spamming HUD if some weirdly-overenchanted item.
            if (count >= 8) {
                y = drawLine(dc, font, "§7… and more", y);
                break;
            }
        }
        if (count == 0) {
            y = drawLine(dc, font, "§4Ench §8(none)", y);
        }
        return y;
    }

    /** Convert int → Roman numeral. Caps at the typical MC max (V) but
     *  handles up to X for absurd commands. Returns the int as string for
     *  values above 10. */
    private static String romanNumeral(int n) {
        switch (n) {
            case 1:  return "I";
            case 2:  return "II";
            case 3:  return "III";
            case 4:  return "IV";
            case 5:  return "V";
            case 6:  return "VI";
            case 7:  return "VII";
            case 8:  return "VIII";
            case 9:  return "IX";
            case 10: return "X";
            default: return n > 10 ? String.valueOf(n) : "";
        }
    }

     /*  X:123 Z:-456    9-px footer with player coords
     */
    private static int renderMinimap(Object dc, int mx, int my) {
        try {
            if (mc == null) return my;
            Object font = fontField != null ? fontField.get(mc) : null;
            if (font == null) return my;
            Object player = playerField != null ? playerField.get(mc) : null;
            if (player == null) return my;
            if (fillMethod == null) fillMethod = findFill(dc.getClass());

            // Sizing: 64px-wide viewport, a 9px title bar on top, 9px coord strip below.
            // BPP 24 ≈ 1.5 chunks per viewport pixel, so a 2×2 pixel brush gives solid
            // terrain coverage even near the edges.
            // Map size driven by the cycle index (Small/Medium/Large)
            int sizeIdx = (mapSizeIdx >= 1 && mapSizeIdx < MAP_SIZE_PIXELS.length)
                ? mapSizeIdx : 2;
            final int   SIZE     = MAP_SIZE_PIXELS[sizeIdx];
            final int   HEADER_H = 9;
            final int   FOOTER_H = 9;
            final int   RADIUS   = SIZE / 2;
            final float BPP      = 24f;
            final int   mapTop   = my + HEADER_H;
            final int   mapBot   = mapTop + SIZE;
            final int   cx       = mx + RADIUS;
            final int   cy       = mapTop + RADIUS;

            // --- player pose (used by terrain, compass, markers) ---
            double myX = firstNum(player, "method_23317", "getX").doubleValue();
            double myY = firstNum(player, "method_23318", "getY").doubleValue();
            double myZ = firstNum(player, "method_23321", "getZ").doubleValue();
            float  yaw = firstNum(player, "method_36454", "getYRot", "getYaw").floatValue();

            // --- title bar ("MAP" + explored-chunk count) ---
            fill(dc, mx,            my,             mx + SIZE,     my + HEADER_H, 0xE01A0308);
            fill(dc, mx,            my,             mx + SIZE,     my + 1,        0xFFAF121A);
            fill(dc, mx,            my,             mx + 1,        my + HEADER_H, 0xFFAF121A);
            fill(dc, mx + SIZE - 1, my,             mx + SIZE,     my + HEADER_H, 0xFFAF121A);
            draw(dc, font, "§c§lMAP", mx + 3, my + 1, 0xFFFFFFFF);

            // --- map panel background ---
            fill(dc, mx, mapTop, mx + SIZE, mapBot, 0xD00A0204);

            // --- explored chunks (terrain layer) — only for CURRENT dim ---
            Map<Long, Integer> here = currentChunkMap();
            Map<Long, Integer> snapshot;
            synchronized (here) { snapshot = new HashMap<>(here); }
            // Clip to a circle inset 1 pixel from the border so chunks don't bleed
            // into the red frame we paint below.
            int r2 = (RADIUS - 1) * (RADIUS - 1);
            for (Map.Entry<Long, Integer> e : snapshot.entrySet()) {
                long k  = e.getKey();
                int  col = e.getValue() == null ? FALLBACK_COLOR : e.getValue();
                int ccx = chunkKeyX(k) * 16 + 8;
                int ccz = chunkKeyZ(k) * 16 + 8;
                double dx = (ccx - myX) / BPP;
                double dz = (ccz - myZ) / BPP;
                int px = cx + (int) Math.round(dx);
                int py = cy + (int) Math.round(dz);
                int rdx = px - cx, rdy = py - cy;
                if (rdx * rdx + rdy * rdy > r2) continue;
                // 2×2 pixel brush — 16 blocks / 24 BPP ≈ 0.67px per chunk, so one
                // pixel leaves gaps. A 2×2 brush fills cleanly without overdraw.
                fill(dc, px, py, px + 2, py + 2, col | 0xFF000000);
            }

            // --- thin blood-red frame around the map panel ---
            fill(dc, mx,            mapTop,         mx + SIZE,     mapTop + 1,    0xFFAF121A);
            fill(dc, mx,            mapBot - 1,     mx + SIZE,     mapBot,        0xFFAF121A);
            fill(dc, mx,            mapTop,         mx + 1,        mapBot,        0xFFAF121A);
            fill(dc, mx + SIZE - 1, mapTop,         mx + SIZE,     mapBot,        0xFFAF121A);

            // --- compass labels (N/S/E/W) ---
            draw(dc, font, "§cN", cx - 3,         mapTop + 1,         0xFFFFFFFF);
            draw(dc, font, "§4S", cx - 3,         mapBot - 9,         0xFFFFFFFF);
            draw(dc, font, "§4W", mx + 2,         cy - 4,             0xFFFFFFFF);
            draw(dc, font, "§4E", mx + SIZE - 7,  cy - 4,             0xFFFFFFFF);

            // --- other players on top (black outline + red dot) ---
            Object world = worldField != null ? worldField.get(mc) : null;
            if (world != null) {
                Object ps = tryInvoke(world, "method_18456", "getPlayers", "players");
                if (ps instanceof java.util.Collection) {
                    for (Object p : (java.util.Collection<?>) ps) {
                        if (p == player) continue;
                        try {
                            double ex = firstNum(p, "method_23317", "getX").doubleValue();
                            double ez = firstNum(p, "method_23321", "getZ").doubleValue();
                            int px = cx + (int) Math.round((ex - myX) / BPP);
                            int py = cy + (int) Math.round((ez - myZ) / BPP);
                            int rdx = px - cx, rdy = py - cy;
                            if (rdx * rdx + rdy * rdy > r2) continue;
                            fill(dc, px - 2, py - 2, px + 3, py + 3, 0xFF000000);
                            fill(dc, px - 1, py - 1, px + 2, py + 2, 0xFFFF2030);
                        } catch (Throwable ignored) {}
                    }
                }
            }

            // --- player facing arrow (rotates with yaw) ---
            float yawRad = (float) Math.toRadians(yaw);
            float fdx = -(float) Math.sin(yawRad);
            float fdy =  (float) Math.cos(yawRad);
            for (int t = 0; t <= 5; t++) {
                int px = cx + Math.round(fdx * t);
                int py = cy + Math.round(fdy * t);
                fill(dc, px, py, px + 1, py + 1, 0xFFFF4050);
            }
            int tipX = cx + Math.round(fdx * 6);
            int tipY = cy + Math.round(fdy * 6);
            fill(dc, tipX - 1, tipY - 1, tipX + 2, tipY + 2, 0xFFFF6070);
            // White center dot (player)
            fill(dc, cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);

            // --- chunk-count badge on the right side of the title bar ---
            String count = snapshot.size() + "c";
            int countW = count.length() * 6;
            draw(dc, font, "§8" + count, mx + SIZE - countW - 2, my + 1, 0xFF909090);

            // --- footer with player XZ (y not interesting on a 2-D map) ---
            String coords = "§7" + (int) myX + "§8,§7" + (int) myY + "§8,§7" + (int) myZ;
            draw(dc, font, coords, mx + 2, mapBot + 1, 0xFFFFFFFF);

            return mapBot + FOOTER_H + 1;
        } catch (Throwable t) {
            logOnce("Map", t);
            return my;
        }
    }

    // ---- target-effects helpers ------------------------------------------

    private static boolean isLivingEntity(Object o) {
        Class<?> c = o.getClass();
        while (c != null) {
            if (c.getName().endsWith("class_1309")) return true;   // LivingEntity
            c = c.getSuperclass();
        }
        return false;
    }

    /** Cached WorldBorder accessor — resolved via the inheritance chain. */
    private static Method  cachedGetBorder;
    private static boolean cachedGetBorderTried;
    private static boolean worldBorderResolveLogged;

    /** Find {@code getWorldBorder()} on a World instance. The method is
     *  declared on {@code class_1941} (a parent interface) and inherited by
     *  ClientWorld. Plain {@code getMethod("method_8621")} sometimes misses
     *  it depending on how the bytecode is laid out — e.g. interface default
     *  methods on remapped jars can confuse {@link Class#getMethod}. So we:
     *    1. Try {@code getMethod} for the known names
     *    2. Walk all methods on the class + ancestors looking for a 0-arg
     *       method whose return type's simple name contains "WorldBorder"
     *  Cached after first success. */
    private static Object resolveWorldBorder(Object world) {
        if (world == null) return null;
        if (cachedGetBorder != null) {
            try { return cachedGetBorder.invoke(world); }
            catch (Throwable ignored) { cachedGetBorder = null; }
        }
        // Path 1: known intermediary / yarn names via getMethod()
        for (String n : new String[]{"method_8621", "getWorldBorder"}) {
            try {
                Method m = world.getClass().getMethod(n);
                Object r = m.invoke(world);
                if (r != null) {
                    cachedGetBorder = m;
                    return r;
                }
            } catch (Throwable ignored) {}
        }
        // Path 2: walk every public method, find any returning *WorldBorder
        if (!cachedGetBorderTried) {
            cachedGetBorderTried = true;
            try {
                for (Method m : world.getClass().getMethods()) {
                    if (m.getParameterCount() != 0) continue;
                    Class<?> rt = m.getReturnType();
                    if (rt == void.class || rt.isPrimitive()) continue;
                    String rn = rt.getSimpleName();
                    if (rn.contains("WorldBorder") || rn.equals("class_2784")) {
                        m.setAccessible(true);
                        Object r = m.invoke(world);
                        if (r != null) {
                            cachedGetBorder = m;
                            System.out.println("[ShadowHud][WorldBorder] resolved via "
                                + m.getDeclaringClass().getSimpleName() + "."
                                + m.getName() + "() → " + rn);
                            return r;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    /** Invoke by any of the given names; return null on any failure. */
    private static Object tryInvoke(Object target, String... names) {
        if (target == null) return null;
        Class<?> cls = target.getClass();
        for (String n : names) {
            Method m = cachedMethod(cls, n);
            if (m == null) continue;
            try { return m.invoke(target); }
            catch (Throwable ignored) {}
        }
        return null;
    }

    /** Resolve a SimpleOption field on mc.options by its intermediary
     *  field name and unwrap it via getValue() (method_41753).
     *  Returns null if the field or method doesn't exist. */
    private static Object readGameOption(String... fieldNames) {
        try {
            Object opts = null;
            try { opts = mc.getClass().getField("field_1690").get(mc); }
            catch (NoSuchFieldException ne) {
                try { opts = mc.getClass().getField("options").get(mc); }
                catch (NoSuchFieldException ignored) {}
            }
            if (opts == null) {
                Field of = null;
                try { of = mc.getClass().getDeclaredField("field_1690"); }
                catch (NoSuchFieldException e) {
                    try { of = mc.getClass().getDeclaredField("options"); } catch (NoSuchFieldException ignored) {}
                }
                if (of != null) { of.setAccessible(true); opts = of.get(mc); }
            }
            if (opts == null) return null;
            Object opt = null;
            for (String fn : fieldNames) {
                try {
                    Field f = opts.getClass().getDeclaredField(fn);
                    f.setAccessible(true);
                    opt = f.get(opts);
                    if (opt != null) break;
                } catch (NoSuchFieldException ignored) {}
            }
            if (opt == null) return null;
            // Try getValue (intermediary method_41753) first, then plain .getValue() / .get()
            Object val = tryInvoke(opt, "method_41753", "getValue", "get");
            return val;
        } catch (Throwable ignored) { return null; }
    }

    /** Append a single hotbar slot's label (4-char name + count) to the
     *  given builder. Selected slot is highlighted in yellow. */
    private static void appendHotbarLabel(StringBuilder b, Object stack, int slot, int sel) {
        if (stack == null) { b.append(" §8·"); return; }
        Object empty = tryInvoke(stack, "method_7960", "isEmpty");
        if (Boolean.TRUE.equals(empty)) { b.append(" §8·"); return; }
        String id = getItemId(stack);
        Object cntObj = tryInvoke(stack, "method_7947", "getCount");
        int cnt = cntObj instanceof Number ? ((Number) cntObj).intValue() : 1;
        int colonIdx = id.lastIndexOf(':');
        String name = colonIdx >= 0 ? id.substring(colonIdx + 1) : id;
        if (name.length() > 4) name = name.substring(0, 4);
        String mark = (slot == sel) ? "§e" : "§f";
        b.append(' ').append(mark).append(name);
        if (cnt > 1) b.append("§8x").append(cnt);
    }

    private static int safeInt(Object target, String... names) {
        if (target == null) return -1;
        Class<?> cls = target.getClass();
        for (String n : names) {
            Method m = cachedMethod(cls, n);
            if (m == null) continue;
            try {
                Object r = m.invoke(target);
                if (r instanceof Number) return ((Number) r).intValue();
            } catch (Throwable ignored) {}
        }
        return -1;
    }

    /** Turn a Text/Component into a plain String. */
    private static String compToString(Object comp) {
        if (comp == null) return "";
        Object s = tryInvoke(comp, "getString", "method_10851");
        return s == null ? String.valueOf(comp) : String.valueOf(s);
    }

    /** Resolve a StatusEffectInstance to a human-readable name ("Speed"). */
    private static String statusEffectName(Object effectInstance) {
        try {
            // effect.getEffectType() → RegistryEntry<StatusEffect>  (method_5579)
            Object holder = tryInvoke(effectInstance, "method_5579", "getEffect", "getEffectType");
            if (holder == null) return "?";
            // holder.getKey() → Optional<RegistryKey<StatusEffect>>
            Object opt = tryInvoke(holder, "method_40230", "unwrapKey", "getKey");
            if (opt == null) return "?";
            Object present = tryInvoke(opt, "isPresent");
            if (!Boolean.TRUE.equals(present)) return "?";
            Object key = tryInvoke(opt, "get");
            if (key == null) return "?";
            // key.getValue() → Identifier
            Object id = tryInvoke(key, "method_29177", "getValue", "location");
            if (id == null) return "?";
            // id.getPath() → "speed"
            Object path = tryInvoke(id, "method_12832", "getPath");
            if (path == null) return String.valueOf(id);
            String s = String.valueOf(path).replace('_', ' ');
            return s.isEmpty() ? "?" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /** Best-effort: is this effect helpful (green) vs harmful (red)? */
    private static boolean isBeneficial(Object effectInstance) {
        try {
            Object holder = tryInvoke(effectInstance, "method_5579", "getEffect", "getEffectType");
            if (holder == null) return true;
            Object value = tryInvoke(holder, "value", "comp_349");   // holder.value() → StatusEffect
            if (value == null) value = holder;
            // StatusEffect.getCategory() → StatusEffectCategory enum (BENEFICIAL/HARMFUL/NEUTRAL)
            Object cat = tryInvoke(value, "method_18792", "getCategory");
            if (cat == null) return true;
            String name = cat.toString();
            return name.contains("BENEFICIAL");
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** Render a StatusEffectInstance as " ✚ Speed II 1:23 " colored by category. */
    private static String formatEffect(Object ef) {
        String ename = statusEffectName(ef);
        int amp = safeInt(ef, "method_5578", "getAmplifier");
        int dur = safeInt(ef, "method_5584", "getDuration");
        boolean beneficial = isBeneficial(ef);
        String col  = beneficial ? "§a" : "§c";
        String mark = beneficial ? "§a✚" : "§c⚠";
        String lvl  = amp >= 0 ? " " + roman(amp + 1) : "";
        String time = dur > 0 ? " §8" + formatTicks(dur) : "";
        return mark + " " + col + ename + lvl + time;
    }

    private static String roman(int n) {
        switch (n) {
            case 1: return "";            // Level 1 → hide for cleanliness
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            case 5: return "V";
            case 6: return "VI";
            case 7: return "VII";
            case 8: return "VIII";
            case 9: return "IX";
            case 10: return "X";
            default: return String.valueOf(n);
        }
    }

    private static String formatTicks(int ticks) {
        int sec = ticks / 20;
        int m = sec / 60, s = sec % 60;
        return m > 0 ? String.format("%d:%02d", m, s) : s + "s";
    }

    private static String facing(float yaw) {
        yaw = ((yaw % 360f) + 360f) % 360f;
        if (yaw >= 337.5f || yaw < 22.5f)  return "south (+Z)";
        if (yaw < 67.5f)                   return "southwest";
        if (yaw < 112.5f)                  return "west  (-X)";
        if (yaw < 157.5f)                  return "northwest";
        if (yaw < 202.5f)                  return "north (-Z)";
        if (yaw < 247.5f)                  return "northeast";
        if (yaw < 292.5f)                  return "east  (+X)";
        return "southeast";
    }

    // ---- config persistence (tiny JSON — no external dep) ------------------

    private static void loadConfig() {
        try {
            if (!Files.exists(CONFIG)) return;
            String s = new String(Files.readAllBytes(CONFIG));
            // crude parse: "FPS": true / false
            for (String key : MODULES.keySet()) {
                int i = s.indexOf("\"" + key + "\":");
                if (i < 0) continue;
                int eq = s.indexOf(':', i);
                if (eq < 0) continue;
                int end = Math.min(s.length(), eq + 10);
                String tail = s.substring(eq, end).toLowerCase();
                MODULES.put(key, tail.contains("true"));
            }
            crosshairShape       = parseInt(s, "Crosshair_shape",      crosshairShape);
            crosshairColorIdx    = parseInt(s, "Crosshair_colorIdx",   crosshairColorIdx);
            customCrosshairColor = parseInt(s, "Crosshair_customColor",customCrosshairColor);
            mapSizeIdx           = parseInt(s, "Map_size",       mapSizeIdx);
            keysPosIdx           = parseInt(s, "Keystrokes_pos", keysPosIdx);
            effectsLimitIdx      = parseInt(s, "Effects_limit",  effectsLimitIdx);
            trailIdx             = parseInt(s, "Trail_idx",       trailIdx);
            haloIdx              = parseInt(s, "Halo_idx",        haloIdx);
            wingsParticleIdx     = parseInt(s, "Wings_idx",       wingsParticleIdx);
            angelWingsIdx        = parseInt(s, "AngelWings_idx",  angelWingsIdx);
            capeIdx              = parseInt(s, "Cape_idx",        capeIdx);
            fairiesIdx           = parseInt(s, "Fairies_idx",     fairiesIdx);
            footstepsIdx         = parseInt(s, "Footsteps_idx",   footstepsIdx);
            bowTrailIdx          = parseInt(s, "BowTrail_idx",    bowTrailIdx);
            // Per-module config knobs (HP/FPS/XYZ/Time/Compass)
            cfgHpShowAbs    = parseBool(s, "HP_showAbs",    cfgHpShowAbs);
            cfgHpShowArmor  = parseBool(s, "HP_showArmor",  cfgHpShowArmor);
            cfgHpCompact    = parseBool(s, "HP_compact",    cfgHpCompact);
            cfgFpsLabel     = parseBool(s, "FPS_label",     cfgFpsLabel);
            cfgFpsStyle     = parseInt(s,  "FPS_style",     cfgFpsStyle);
            cfgXyzDecimals  = parseInt(s,  "XYZ_dec",       cfgXyzDecimals);
            cfgXyzSep       = parseInt(s,  "XYZ_sep",       cfgXyzSep);
            cfgXyzCompact   = parseBool(s, "XYZ_compact",   cfgXyzCompact);
            cfgTime24h      = parseBool(s, "Time_24h",      cfgTime24h);
            cfgTimeSeconds  = parseBool(s, "Time_secs",     cfgTimeSeconds);
            cfgCompassDegs  = parseBool(s, "Compass_degs",  cfgCompassDegs);
            cfgCompassMode  = parseInt(s,  "Compass_mode",  cfgCompassMode);
            cfgHudAnchor    = Math.max(0, Math.min(8,
                                  parseInt(s, "HUD_anchor",  cfgHudAnchor)));
            cfgHudScale     = Math.max(50, Math.min(200,
                                  parseInt(s, "HUD_scale",   cfgHudScale)));
            cfgHudOpacity   = Math.max(10, Math.min(100,
                                  parseInt(s, "HUD_opacity", cfgHudOpacity)));
            cfgHudOffsetX   = parseInt(s,  "HUD_offsetX", cfgHudOffsetX);
            cfgHudOffsetY   = parseInt(s,  "HUD_offsetY", cfgHudOffsetY);
            cfgThemeIdx     = Math.max(0, Math.min(THEME_NAMES.length - 1,
                                  parseInt(s, "Theme_idx",    cfgThemeIdx)));
            cfgThemeCustom  = parseInt(s,  "Theme_custom",  cfgThemeCustom);
            cfgReachOnlyOnTarget = parseBool(s, "Reach_onlyTgt", cfgReachOnlyOnTarget);
            cfgReachShowUnit     = parseBool(s, "Reach_unit",    cfgReachShowUnit);
            cfgNetGraphSeconds   = parseInt(s,  "NetGraph_secs", cfgNetGraphSeconds);
            cfgKillstreakReset   = Math.max(0, Math.min(KS_RESET_NAMES.length - 1,
                                       parseInt(s, "KS_reset",    cfgKillstreakReset)));
            cfgSatStyle          = Math.max(0, Math.min(SAT_STYLE_NAMES.length - 1,
                                       parseInt(s, "Sat_style",   cfgSatStyle)));
            cfgToolBreakThresh   = parseInt(s,  "TB_thresh",   cfgToolBreakThresh);
            cfgToolBreakSound    = parseBool(s, "TB_sound",    cfgToolBreakSound);
            cfgAntiAfkInterval   = parseInt(s,  "AFK_int",     cfgAntiAfkInterval);
            cfgHotbarFadeDelay   = parseInt(s,  "Hotbar_delay",cfgHotbarFadeDelay);
            cfgAutoRespawnDelay  = parseInt(s,  "AR_delay",    cfgAutoRespawnDelay);
            cfgFullbrightLevel   = parseInt(s,  "FB_level",    cfgFullbrightLevel);
            cfgAutoGgIdx         = Math.max(0, Math.min(AUTOGG_MSGS.length - 1,
                                       parseInt(s, "GG_idx",     cfgAutoGgIdx)));
            cfgSwordScale        = Math.max(50, Math.min(250,
                                       parseInt(s, "Sword_scale", cfgSwordScale)));
            cfgSwordScaleAllItems = parseBool(s, "Sword_all",     cfgSwordScaleAllItems);
            cfgGlintColor        = parseInt(s,  "Glint_color",   cfgGlintColor);
            cfgGlintStrength     = Math.max(0, Math.min(200,
                                       parseInt(s, "Glint_strength", cfgGlintStrength)));
            cfgBlockHighlightColor = parseInt(s,  "BH_color", cfgBlockHighlightColor);
            cfgBlockHighlightAlpha = Math.max(10, Math.min(100,
                                       parseInt(s, "BH_alpha", cfgBlockHighlightAlpha)));
            // Per-module anchor overrides — sweep the file for "ModAnchor_*"
            // keys and rebuild the map.
            moduleAnchorOverrides.clear();
            int searchFrom = 0;
            while (true) {
                int k = s.indexOf("\"ModAnchor_", searchFrom);
                if (k < 0) break;
                int nameStart = k + "\"ModAnchor_".length();
                int nameEnd = s.indexOf("\"", nameStart);
                if (nameEnd < 0) break;
                String moduleName = s.substring(nameStart, nameEnd);
                int colon = s.indexOf(':', nameEnd);
                if (colon < 0) break;
                int p = colon + 1, len = s.length();
                while (p < len && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
                int q = p;
                while (q < len && (Character.isDigit(s.charAt(q)) || s.charAt(q) == '-')) q++;
                if (q > p) {
                    try {
                        int anchor = Integer.parseInt(s.substring(p, q));
                        if (anchor >= 0 && anchor <= 8 && MODULES.containsKey(moduleName)) {
                            moduleAnchorOverrides.put(moduleName, anchor);
                        }
                    } catch (NumberFormatException ignored) {}
                }
                searchFrom = q;
            }
            // Per-module color overrides — same flat-key sweep.
            moduleColorOverrides.clear();
            searchFrom = 0;
            while (true) {
                int k = s.indexOf("\"ModColor_", searchFrom);
                if (k < 0) break;
                int nameStart = k + "\"ModColor_".length();
                int nameEnd = s.indexOf("\"", nameStart);
                if (nameEnd < 0) break;
                String moduleName = s.substring(nameStart, nameEnd);
                int colon = s.indexOf(':', nameEnd);
                if (colon < 0) break;
                int p = colon + 1, len = s.length();
                while (p < len && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
                int q = p;
                while (q < len && (Character.isDigit(s.charAt(q)) || s.charAt(q) == '-')) q++;
                if (q > p) {
                    try {
                        int color = Integer.parseInt(s.substring(p, q));
                        if (MODULES.containsKey(moduleName)) {
                            moduleColorOverrides.put(moduleName, color);
                        }
                    } catch (NumberFormatException ignored) {}
                }
                searchFrom = q;
            }
            // Per-module offset overrides — sweep "ModOffX_" + "ModOffY_" pairs.
            moduleOffsetOverrides.clear();
            for (String prefix : new String[]{"ModOffX_", "ModOffY_"}) {
                searchFrom = 0;
                int axis = prefix.charAt(6) == 'X' ? 0 : 1;
                while (true) {
                    int k = s.indexOf("\"" + prefix, searchFrom);
                    if (k < 0) break;
                    int nameStart = k + prefix.length() + 1;
                    int nameEnd = s.indexOf("\"", nameStart);
                    if (nameEnd < 0) break;
                    String moduleName = s.substring(nameStart, nameEnd);
                    int colon = s.indexOf(':', nameEnd);
                    if (colon < 0) break;
                    int p = colon + 1, len = s.length();
                    while (p < len && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
                    int q = p;
                    while (q < len && (Character.isDigit(s.charAt(q)) || s.charAt(q) == '-')) q++;
                    if (q > p) {
                        try {
                            int v = Integer.parseInt(s.substring(p, q));
                            if (MODULES.containsKey(moduleName)) {
                                int[] o = moduleOffsetOverrides.get(moduleName);
                                if (o == null) o = new int[]{0, 0};
                                o[axis] = v;
                                moduleOffsetOverrides.put(moduleName, o);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                    searchFrom = q;
                }
            }
            cfgPingStyle    = Math.max(0, Math.min(PING_STYLE_NAMES.length - 1,
                                  parseInt(s, "Ping_style",  cfgPingStyle)));
            cfgMemFormat    = Math.max(0, Math.min(MEM_FMT_NAMES.length - 1,
                                  parseInt(s, "Mem_format",  cfgMemFormat)));
            cfgDayFormat    = Math.max(0, Math.min(DAY_FMT_NAMES.length - 1,
                                  parseInt(s, "Day_format",  cfgDayFormat)));
            // Menu filter state
            menuEnabledOnly      = parseInt(s, "menuEnabledOnly", 0) != 0;
            menuGridCols         = Math.max(1, Math.min(4,
                                       parseInt(s, "menuGridCols", menuGridCols)));
            menuSortMode         = Math.max(0, Math.min(MENU_SORT_LABELS.length - 1,
                                       parseInt(s, "menuSortMode", menuSortMode)));
            int catIdx           = parseInt(s, "menuCategoryIdx", 0);
            if (catIdx >= 0 && catIdx < MENU_CATEGORIES.length) {
                menuCategoryFilter = (catIdx == 0) ? "" : MENU_CATEGORIES[catIdx];
            }
            // Restore pinned modules if present. Format:
            //   "pinned": ["FPS","XYZ","Combo"]
            int pinStart = s.indexOf("\"pinned\":");
            if (pinStart >= 0) {
                int bL = s.indexOf('[', pinStart);
                int bR = bL >= 0 ? s.indexOf(']', bL) : -1;
                if (bL >= 0 && bR > bL && bR - bL < 4096) {
                    try {
                        String body = s.substring(bL + 1, bR);
                        for (String item : body.split(",")) {
                            String n = item.trim().replace("\"", "");
                            if (!n.isEmpty() && MODULES.containsKey(n)) {
                                pinnedModules.add(n);
                            }
                        }
                    } catch (Throwable t) {
                        System.err.println("[ShadowHud] pinned-list parse failed: " + t);
                    }
                }
            }

            // Restore user's saved snapshot if present. Format:
            //   "snapshot": {"FPS":true,"XYZ":true,...}
            // Tolerate corrupted / partial input — bail safely on any error
            // instead of throwing, so a hand-edited config can't kill the
            // entire mod.
            int snapStart = s.indexOf("\"snapshot\":");
            if (snapStart >= 0) {
                int braceL = s.indexOf('{', snapStart);
                int braceR = braceL >= 0 ? s.indexOf('}', braceL) : -1;
                // Cap distance: snapshot block should be near the marker.
                // If braceL is far away, the marker is inside a comment or
                // some other key — bail.
                boolean nearby = braceL >= 0 && braceL - snapStart < 16;
                if (nearby && braceR > braceL && braceR - braceL < 32_768) {
                    try {
                        String body = s.substring(braceL + 1, braceR);
                        Map<String, Boolean> snap = new LinkedHashMap<>();
                        for (String pair : body.split(",")) {
                            int colon = pair.indexOf(':');
                            if (colon < 0) continue;
                            String k = pair.substring(0, colon).trim().replace("\"", "");
                            String v = pair.substring(colon + 1).trim();
                            if (!k.isEmpty()) snap.put(k, "true".equals(v));
                        }
                        if (!snap.isEmpty()) userSnapshot = snap;
                    } catch (Throwable t) {
                        System.err.println("[ShadowHud] snapshot parse failed: " + t);
                    }
                }
            }
        } catch (IOException ignored) {}
    }

    private static int parseInt(String s, String key, int dflt) {
        int i = s.indexOf("\"" + key + "\":");
        if (i < 0) return dflt;
        int eq = s.indexOf(':', i);
        if (eq < 0) return dflt;
        int p = eq + 1, len = s.length();
        while (p < len && (s.charAt(p) == ' ' || s.charAt(p) == '\t')) p++;
        int q = p;
        while (q < len && (Character.isDigit(s.charAt(q)) || s.charAt(q) == '-')) q++;
        if (q == p) return dflt;
        try { return Integer.parseInt(s.substring(p, q)); }
        catch (NumberFormatException e) { return dflt; }
    }

    /** Same JSON-like grep as parseInt, but for boolean fields written
     *  as `"key": true` / `"key": false`. */
    private static boolean parseBool(String s, String key, boolean dflt) {
        int i = s.indexOf("\"" + key + "\":");
        if (i < 0) return dflt;
        int eq = s.indexOf(':', i);
        if (eq < 0) return dflt;
        int end = Math.min(s.length(), eq + 12);
        String tail = s.substring(eq, end).toLowerCase();
        if (tail.contains("true"))  return true;
        if (tail.contains("false")) return false;
        return dflt;
    }

    private static void saveConfig() {
        // Module state changed — invalidate the on-count cache so the title-
        // bar stat ("X / Y on") refreshes on the next render.
        cachedOnCount = -1;
        try {
            Files.createDirectories(CONFIG.getParent());
            StringBuilder s = new StringBuilder("{\n");
            int i = 0;
            for (Map.Entry<String, Boolean> e : MODULES.entrySet()) {
                s.append("  \"").append(e.getKey()).append("\": ").append(e.getValue()).append(",\n");
                i++;
            }
            s.append("  \"Crosshair_shape\": ").append(crosshairShape).append(",\n");
            s.append("  \"Crosshair_colorIdx\": ").append(crosshairColorIdx).append(",\n");
            s.append("  \"Crosshair_customColor\": ").append(customCrosshairColor).append(",\n");
            s.append("  \"Map_size\": ").append(mapSizeIdx).append(",\n");
            s.append("  \"Keystrokes_pos\": ").append(keysPosIdx).append(",\n");
            s.append("  \"Effects_limit\": ").append(effectsLimitIdx).append(",\n");
            s.append("  \"Trail_idx\": ").append(trailIdx).append(",\n");
            s.append("  \"Halo_idx\": ").append(haloIdx).append(",\n");
            s.append("  \"Wings_idx\": ").append(wingsParticleIdx).append(",\n");
            s.append("  \"AngelWings_idx\": ").append(angelWingsIdx).append(",\n");
            s.append("  \"Cape_idx\": ").append(capeIdx).append(",\n");
            s.append("  \"Fairies_idx\": ").append(fairiesIdx).append(",\n");
            s.append("  \"Footsteps_idx\": ").append(footstepsIdx).append(",\n");
            s.append("  \"BowTrail_idx\": ").append(bowTrailIdx).append(",\n");
            // Per-module config knobs (HP/FPS/XYZ/Time/Compass)
            s.append("  \"HP_showAbs\": ").append(cfgHpShowAbs).append(",\n");
            s.append("  \"HP_showArmor\": ").append(cfgHpShowArmor).append(",\n");
            s.append("  \"HP_compact\": ").append(cfgHpCompact).append(",\n");
            s.append("  \"FPS_label\": ").append(cfgFpsLabel).append(",\n");
            s.append("  \"FPS_style\": ").append(cfgFpsStyle).append(",\n");
            s.append("  \"XYZ_dec\": ").append(cfgXyzDecimals).append(",\n");
            s.append("  \"XYZ_sep\": ").append(cfgXyzSep).append(",\n");
            s.append("  \"XYZ_compact\": ").append(cfgXyzCompact).append(",\n");
            s.append("  \"Time_24h\": ").append(cfgTime24h).append(",\n");
            s.append("  \"Time_secs\": ").append(cfgTimeSeconds).append(",\n");
            s.append("  \"Compass_degs\": ").append(cfgCompassDegs).append(",\n");
            s.append("  \"Compass_mode\": ").append(cfgCompassMode).append(",\n");
            s.append("  \"HUD_anchor\":   ").append(cfgHudAnchor).append(",\n");
            s.append("  \"HUD_scale\":    ").append(cfgHudScale).append(",\n");
            s.append("  \"HUD_opacity\":  ").append(cfgHudOpacity).append(",\n");
            s.append("  \"HUD_offsetX\":  ").append(cfgHudOffsetX).append(",\n");
            s.append("  \"HUD_offsetY\":  ").append(cfgHudOffsetY).append(",\n");
            s.append("  \"Theme_idx\":    ").append(cfgThemeIdx).append(",\n");
            s.append("  \"Theme_custom\": ").append(cfgThemeCustom).append(",\n");
            s.append("  \"Reach_onlyTgt\":").append(cfgReachOnlyOnTarget).append(",\n");
            s.append("  \"Reach_unit\":   ").append(cfgReachShowUnit).append(",\n");
            s.append("  \"NetGraph_secs\":").append(cfgNetGraphSeconds).append(",\n");
            s.append("  \"KS_reset\":     ").append(cfgKillstreakReset).append(",\n");
            s.append("  \"Sat_style\":    ").append(cfgSatStyle).append(",\n");
            s.append("  \"TB_thresh\":    ").append(cfgToolBreakThresh).append(",\n");
            s.append("  \"TB_sound\":     ").append(cfgToolBreakSound).append(",\n");
            s.append("  \"AFK_int\":      ").append(cfgAntiAfkInterval).append(",\n");
            s.append("  \"Hotbar_delay\": ").append(cfgHotbarFadeDelay).append(",\n");
            s.append("  \"AR_delay\":     ").append(cfgAutoRespawnDelay).append(",\n");
            s.append("  \"FB_level\":     ").append(cfgFullbrightLevel).append(",\n");
            s.append("  \"GG_idx\":       ").append(cfgAutoGgIdx).append(",\n");
            s.append("  \"Sword_scale\":  ").append(cfgSwordScale).append(",\n");
            s.append("  \"Sword_all\":    ").append(cfgSwordScaleAllItems).append(",\n");
            s.append("  \"Glint_color\":  ").append(cfgGlintColor).append(",\n");
            s.append("  \"Glint_strength\":").append(cfgGlintStrength).append(",\n");
            s.append("  \"BH_color\":     ").append(cfgBlockHighlightColor).append(",\n");
            s.append("  \"BH_alpha\":     ").append(cfgBlockHighlightAlpha).append(",\n");
            // Per-module anchor overrides — written as flat keys so the
            // existing parser doesn't need a nested-object parser.
            if (!moduleAnchorOverrides.isEmpty()) {
                for (java.util.Map.Entry<String, Integer> e : moduleAnchorOverrides.entrySet()) {
                    s.append("  \"ModAnchor_").append(e.getKey()).append("\": ")
                     .append(e.getValue()).append(",\n");
                }
            }
            if (!moduleColorOverrides.isEmpty()) {
                for (java.util.Map.Entry<String, Integer> e : moduleColorOverrides.entrySet()) {
                    s.append("  \"ModColor_").append(e.getKey()).append("\": ")
                     .append(e.getValue()).append(",\n");
                }
            }
            if (!moduleOffsetOverrides.isEmpty()) {
                for (java.util.Map.Entry<String, int[]> e : moduleOffsetOverrides.entrySet()) {
                    int[] o = e.getValue();
                    if (o != null && o.length == 2) {
                        s.append("  \"ModOffX_").append(e.getKey()).append("\": ")
                         .append(o[0]).append(",\n");
                        s.append("  \"ModOffY_").append(e.getKey()).append("\": ")
                         .append(o[1]).append(",\n");
                    }
                }
            }
            s.append("  \"Ping_style\":   ").append(cfgPingStyle).append(",\n");
            s.append("  \"Mem_format\":   ").append(cfgMemFormat).append(",\n");
            s.append("  \"Day_format\":   ").append(cfgDayFormat).append(",\n");
            // Menu filter state
            int catIdx = 0;
            for (int t = 0; t < MENU_CATEGORIES.length; t++) {
                String key = (t == 0) ? "" : MENU_CATEGORIES[t];
                if (key.equals(menuCategoryFilter)) { catIdx = t; break; }
            }
            s.append("  \"menuCategoryIdx\": ").append(catIdx).append(",\n");
            s.append("  \"menuEnabledOnly\": ").append(menuEnabledOnly ? 1 : 0).append(",\n");
            s.append("  \"menuGridCols\": ").append(menuGridCols).append(",\n");
            s.append("  \"menuSortMode\": ").append(menuSortMode);
            // Pinned module names — comma-separated list. Persists across
            // launches so the user's "favorites" stay at the top.
            if (!pinnedModules.isEmpty()) {
                s.append(",\n  \"pinned\": [");
                int k = 0;
                for (String n : pinnedModules) {
                    if (k++ > 0) s.append(',');
                    s.append("\"").append(n).append("\"");
                }
                s.append(']');
            }
            // User's saved snapshot — written as a parallel JSON object so
            // it can be parsed on load without colliding with MODULES keys.
            // The "snapshot:" key marks the start; everything between
            // "snapshot:{" and the matching "}" is one entry per module.
            if (userSnapshot != null) {
                s.append(",\n  \"snapshot\": {");
                int k = 0;
                for (Map.Entry<String, Boolean> e : userSnapshot.entrySet()) {
                    if (k++ > 0) s.append(',');
                    s.append("\"").append(e.getKey()).append("\":").append(e.getValue());
                }
                s.append('}');
            }
            s.append('\n');
            s.append("}\n");
            Files.write(CONFIG, s.toString().getBytes());
        } catch (IOException ignored) {}
    }
}
