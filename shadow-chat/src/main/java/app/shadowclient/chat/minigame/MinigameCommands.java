package app.shadowclient.chat.minigame;

import app.shadowclient.chat.ui.InputState;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Slash-command parser for the minigame helpers. Each command is a
 * simple {@code /<cmd> on|off|status} toggle, with a couple of extras
 * (lap reset, speed reset) where they help.
 *
 * <p>Pass {@code echo} the system-line sink the Shadow Chat overlay
 * uses for command output — the dispatcher writes one short response
 * line per command. Callers should also append the help block when
 * the user types {@code /help}.
 */
public final class MinigameCommands {

    private MinigameCommands() {}

    /** True if {@code cmd} is one of ours and was handled. */
    public static boolean dispatch(String[] parts, Consumer<InputState.DisplayLine> echo) {
        if (parts == null || parts.length == 0) return false;
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "/speedhud":    handleSpeed(parts, echo);    return true;
            case "/laptimer":    handleLap(parts, echo);      return true;
            case "/icehighlight":handleIce(parts, echo);      return true;
            case "/autosprint":  handleSprint(parts, echo);   return true;
            case "/autorespawn": handleRespawn(parts, echo);  return true;
            case "/minigames":   listAll(echo);               return true;
            default: return false;
        }
    }

    /** Lines added to {@code /help} so the user can find these. */
    public static String[] helpLines() {
        return new String[]{
                "/speedhud on|off|reset    — current speed + jump stats HUD"
                        + " (currently " + onOff(Minigames.speedHudEnabled) + ")",
                "/laptimer on|off|reset    — lap timer for ice-boat racing"
                        + " (currently " + onOff(Minigames.lapTimerEnabled) + ")",
                "/icehighlight on|off      — color-code nearby ice blocks"
                        + " (currently " + onOff(Minigames.iceHighlightEnabled) + ")",
                "/autosprint on|off        — always sprint when holding W"
                        + " (currently " + onOff(Minigames.autoSprintEnabled) + ")",
                "/autorespawn on|off       — instant respawn on death"
                        + " (currently " + onOff(Minigames.autoRespawnEnabled) + ")",
                "/minigames                — list all minigame helpers + state",
        };
    }

    // ─── individual handlers ─────────────────────────────────────────

    private static void handleSpeed(String[] parts, Consumer<InputState.DisplayLine> echo) {
        String sub = sub(parts);
        if (sub == null || "status".equals(sub)) {
            echo.accept(InputState.DisplayLine.system("Speed HUD: " + onOff(Minigames.speedHudEnabled)
                    + (Minigames.speedHudEnabled
                        ? String.format(" · now %.1f b/s · peak %.1f", SpeedHud.currentSpeed(), SpeedHud.peakSpeed())
                        : "")));
            return;
        }
        switch (sub) {
            case "on", "true", "enable" -> {
                Minigames.speedHudEnabled = true;
                persist("speed_hud", true);
                SpeedHud.resetSession();
                echo.accept(InputState.DisplayLine.system("Speed HUD enabled."));
            }
            case "off", "false", "disable" -> {
                Minigames.speedHudEnabled = false;
                persist("speed_hud", false);
                echo.accept(InputState.DisplayLine.system("Speed HUD disabled."));
            }
            case "reset" -> {
                SpeedHud.resetSession();
                echo.accept(InputState.DisplayLine.system("Speed HUD peak + jump PB reset."));
            }
            default -> echo.accept(InputState.DisplayLine.error("Unknown subcommand: " + sub));
        }
    }

    private static void handleLap(String[] parts, Consumer<InputState.DisplayLine> echo) {
        String sub = sub(parts);
        if (sub == null || "status".equals(sub)) {
            String extra = "";
            if (Minigames.lapTimerEnabled) {
                extra = " · start " + LapTimer.startLineDescription()
                        + " · laps " + LapTimer.lapCount()
                        + (LapTimer.bestLapMs() > 0 ? " · best " + LapTimer.formatLap(LapTimer.bestLapMs()) : "");
            }
            echo.accept(InputState.DisplayLine.system("Lap timer: " + onOff(Minigames.lapTimerEnabled) + extra));
            return;
        }
        switch (sub) {
            case "on", "true", "enable" -> {
                Minigames.lapTimerEnabled = true;
                LapTimer.anchorStartLineHere();
                echo.accept(InputState.DisplayLine.system(
                        "Lap timer armed. Start line anchored at " + LapTimer.startLineDescription()
                                + " — cross it to begin lap 1."));
            }
            case "off", "false", "disable" -> {
                Minigames.lapTimerEnabled = false;
                LapTimer.clearAll();
                echo.accept(InputState.DisplayLine.system("Lap timer disabled. Stats cleared."));
            }
            case "reset" -> {
                LapTimer.resetStats();
                echo.accept(InputState.DisplayLine.system("Lap stats cleared. Start line stays at "
                        + LapTimer.startLineDescription() + "."));
            }
            case "here", "anchor" -> {
                LapTimer.anchorStartLineHere();
                echo.accept(InputState.DisplayLine.system(
                        "Start line re-anchored at " + LapTimer.startLineDescription() + "."));
            }
            default -> echo.accept(InputState.DisplayLine.error("Unknown subcommand: " + sub));
        }
    }

    private static void handleIce(String[] parts, Consumer<InputState.DisplayLine> echo) {
        String sub = sub(parts);
        if (sub == null || "status".equals(sub)) {
            echo.accept(InputState.DisplayLine.system("Ice highlight: " + onOff(Minigames.iceHighlightEnabled)
                    + " · green=blue ice, yellow=packed, cyan=regular"));
            return;
        }
        switch (sub) {
            case "on", "true", "enable" -> {
                Minigames.iceHighlightEnabled = true;
                persist("ice_highlight", true);
                echo.accept(InputState.DisplayLine.system(
                        "Ice highlight enabled — green outline = blue ice (fastest), yellow = packed, cyan = regular."));
            }
            case "off", "false", "disable" -> {
                Minigames.iceHighlightEnabled = false;
                persist("ice_highlight", false);
                echo.accept(InputState.DisplayLine.system("Ice highlight disabled."));
            }
            default -> echo.accept(InputState.DisplayLine.error("Unknown subcommand: " + sub));
        }
    }

    private static void handleSprint(String[] parts, Consumer<InputState.DisplayLine> echo) {
        String sub = sub(parts);
        if (sub == null || "status".equals(sub)) {
            echo.accept(InputState.DisplayLine.system("Auto sprint: " + onOff(Minigames.autoSprintEnabled)));
            return;
        }
        switch (sub) {
            case "on", "true", "enable" -> {
                Minigames.autoSprintEnabled = true;
                persist("auto_sprint", true);
                echo.accept(InputState.DisplayLine.system("Auto sprint enabled."));
            }
            case "off", "false", "disable" -> {
                Minigames.autoSprintEnabled = false;
                persist("auto_sprint", false);
                echo.accept(InputState.DisplayLine.system("Auto sprint disabled."));
            }
            default -> echo.accept(InputState.DisplayLine.error("Unknown subcommand: " + sub));
        }
    }

    private static void handleRespawn(String[] parts, Consumer<InputState.DisplayLine> echo) {
        String sub = sub(parts);
        if (sub == null || "status".equals(sub)) {
            echo.accept(InputState.DisplayLine.system("Auto respawn: " + onOff(Minigames.autoRespawnEnabled)));
            return;
        }
        switch (sub) {
            case "on", "true", "enable" -> {
                Minigames.autoRespawnEnabled = true;
                persist("auto_respawn", true);
                echo.accept(InputState.DisplayLine.system("Auto respawn enabled."));
            }
            case "off", "false", "disable" -> {
                Minigames.autoRespawnEnabled = false;
                persist("auto_respawn", false);
                echo.accept(InputState.DisplayLine.system("Auto respawn disabled."));
            }
            default -> echo.accept(InputState.DisplayLine.error("Unknown subcommand: " + sub));
        }
    }

    private static void listAll(Consumer<InputState.DisplayLine> echo) {
        echo.accept(InputState.DisplayLine.system("Minigame helpers:"));
        echo.accept(InputState.DisplayLine.system("  Speed HUD:      " + onOff(Minigames.speedHudEnabled)));
        echo.accept(InputState.DisplayLine.system("  Lap timer:      " + onOff(Minigames.lapTimerEnabled)
                + (LapTimer.hasStartLine() ? " · start " + LapTimer.startLineDescription() : "")
                + (LapTimer.lapCount() > 0 ? " · " + LapTimer.lapCount() + " laps, best "
                        + LapTimer.formatLap(LapTimer.bestLapMs()) : "")));
        echo.accept(InputState.DisplayLine.system("  Ice highlight:  " + onOff(Minigames.iceHighlightEnabled)));
        echo.accept(InputState.DisplayLine.system("  Auto sprint:    " + onOff(Minigames.autoSprintEnabled)));
        echo.accept(InputState.DisplayLine.system("  Auto respawn:   " + onOff(Minigames.autoRespawnEnabled)));
        echo.accept(InputState.DisplayLine.system("Toggle with /<name> on|off, /<name> status, or /<name> reset."));
    }


    /** Mirror a toggle flip into the persisted config (survives restarts). */
    private static void persist(String key, boolean v) {
        try {
            app.shadowclient.chat.ShadowChatClient.get().modConfig().setHelperToggle(key, v);
        } catch (IllegalStateException ignored) {
            // Mod still initializing — startup restore covers this window.
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────

    private static String sub(String[] parts) {
        if (parts.length < 2) return null;
        return parts[1].toLowerCase(Locale.ROOT);
    }

    private static String onOff(boolean b) { return b ? "ON" : "off"; }
}
