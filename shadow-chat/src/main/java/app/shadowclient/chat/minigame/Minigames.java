package app.shadowclient.chat.minigame;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * Minigame QoL pack — a small suite of in-game helpers aimed at the
 * minigames Shadow Client users actually play (ice-boat racing,
 * parkour, spleef, hub-game grinding). Each feature is opt-in via a
 * slash command typed in the Shadow Chat overlay so the mod stays
 * silent for users who don't want anything extra.
 *
 * <p>Features in this pack:
 * <ul>
 *   <li><b>Speed HUD</b> — current ground-plane speed in blocks/sec,
 *       session peak, plus jump distance + height while in air. Picks
 *       up boat / minecart speed automatically when the player is in
 *       a vehicle.</li>
 *   <li><b>Lap Timer</b> — anchor a start line on toggle; cross it
 *       again to begin a lap; close laps for lap time, best lap, and
 *       PB highlight. Aimed at ice-boat racing courses.</li>
 *   <li><b>Ice Highlighter</b> — color-coded outlines on ice blocks
 *       within 12 blocks: blue ice = green (top speed), packed ice =
 *       yellow, regular ice = cyan. Lets racers spot the fastest
 *       line through a mixed-ice course at a glance.</li>
 *   <li><b>Auto Sprint</b> — always sprint when holding W; guarded so
 *       it backs off during sneaking, item use, or low hunger.</li>
 *   <li><b>Auto Respawn</b> — instantly fires the respawn packet on
 *       the death screen so deaths don't break flow in repetitive
 *       parkour / minigame loops.</li>
 * </ul>
 *
 * <p>Toggles are session-scoped (not persisted) — minigame helpers
 * are typically wanted for one play session, not as a permanent
 * config. Slash commands live in {@link MinigameCommands}.
 */
public final class Minigames {

    // Session toggles. All default OFF — the mod is invisible until
    // a user types /speedhud on (etc.) in the chat overlay.
    public static volatile boolean speedHudEnabled = false;
    public static volatile boolean lapTimerEnabled = false;
    public static volatile boolean iceHighlightEnabled = false;
    public static volatile boolean autoSprintEnabled = false;
    public static volatile boolean autoRespawnEnabled = false;

    private Minigames() {}

    /**
     * Register every helper's tick + HUD callbacks once at startup.
     * Each helper short-circuits its own work when its toggle is off,
     * so registration is cheap regardless of which ones the user
     * ends up enabling.
     */
    public static void register() {
        SpeedHud.register();
        LapTimer.register();
        IceHighlighter.register();
        AutoSprint.register();
        AutoRespawn.register();

        // Speed-HUD bookkeeping needs to tick even when the HUD is
        // hidden so the moment the user enables it the readout
        // isn't blank — register a no-op pre-warm here. The actual
        // SpeedHud.register() already does its own tick handler,
        // this is just the convention point if we add more later.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // (room for future global minigame state — server detect, etc.)
        });
    }
}
