package app.shadowclient.chat.qol;

import app.shadowclient.chat.ui.InputState;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Slash-command dispatcher for the QoL pack. Same shape as
 * {@link app.shadowclient.chat.minigame.MinigameCommands}.
 */
public final class QolCommands {

    private QolCommands() {}

    public static boolean dispatch(String[] parts, Consumer<InputState.DisplayLine> echo) {
        if (parts == null || parts.length == 0) return false;
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "/recipe", "/rp":      handleRecipe(parts, echo);     return true;
            case "/coordshud", "/coordshud-toggle":
                                        handleCoords(parts, echo);     return true;
            case "/helditemhud":        handleHeld(parts, echo);       return true;
            case "/cooldowns", "/cdhud", "/cd":
                                        handleCooldown(parts, echo);   return true;
            case "/qol":                listAll(echo);                 return true;
            default: return false;
        }
    }

    public static String[] helpLines() {
        return new String[]{
                "/recipe on|off            — recipe tooltip on hover ("
                        + RecipePreview.knownRecipeCount() + " items known) — currently "
                        + onOff(Qol.recipePreviewEnabled),
                "/coordshud on|off         — small XYZ + facing in top-right — currently "
                        + onOff(Qol.coordsHudEnabled),
                "/helditemhud on|off       — show selected item above hotbar on slot switch — currently "
                        + onOff(Qol.heldItemHudEnabled),
                "/cooldowns on|off         — left-edge list of your items on cooldown + time left — currently "
                        + onOff(Qol.cooldownHudEnabled),
                "/qol                      — list all QoL helpers + state",
        };
    }

    private static void handleRecipe(String[] parts, Consumer<InputState.DisplayLine> echo) {
        String sub = sub(parts);
        if (sub == null || "status".equals(sub)) {
            echo.accept(InputState.DisplayLine.system(
                    "Recipe preview: " + onOff(Qol.recipePreviewEnabled)
                            + " · " + RecipePreview.knownRecipeCount() + " items in the table"));
            return;
        }
        switch (sub) {
            case "on", "true", "enable" -> {
                Qol.recipePreviewEnabled = true;
                persist("recipe_preview", true);
                echo.accept(InputState.DisplayLine.system(
                        "Recipe preview enabled. Hover an item in any inventory to see its recipe."));
            }
            case "off", "false", "disable" -> {
                Qol.recipePreviewEnabled = false;
                persist("recipe_preview", false);
                echo.accept(InputState.DisplayLine.system("Recipe preview disabled."));
            }
            default -> echo.accept(InputState.DisplayLine.error("Unknown subcommand: " + sub));
        }
    }

    private static void handleCoords(String[] parts, Consumer<InputState.DisplayLine> echo) {
        String sub = sub(parts);
        if (sub == null || "status".equals(sub)) {
            echo.accept(InputState.DisplayLine.system("Coords HUD: " + onOff(Qol.coordsHudEnabled)));
            return;
        }
        switch (sub) {
            case "on", "true", "enable" -> {
                Qol.coordsHudEnabled = true;
                persist("coords_hud", true);
                echo.accept(InputState.DisplayLine.system("Coords HUD enabled (top-right)."));
            }
            case "off", "false", "disable" -> {
                Qol.coordsHudEnabled = false;
                persist("coords_hud", false);
                echo.accept(InputState.DisplayLine.system("Coords HUD disabled."));
            }
            default -> echo.accept(InputState.DisplayLine.error("Unknown subcommand: " + sub));
        }
    }

    private static void handleHeld(String[] parts, Consumer<InputState.DisplayLine> echo) {
        String sub = sub(parts);
        if (sub == null || "status".equals(sub)) {
            echo.accept(InputState.DisplayLine.system("Held-item HUD: " + onOff(Qol.heldItemHudEnabled)));
            return;
        }
        switch (sub) {
            case "on", "true", "enable" -> {
                Qol.heldItemHudEnabled = true;
                persist("held_item_hud", true);
                echo.accept(InputState.DisplayLine.system(
                        "Held-item HUD enabled. Switch hotbar slots to see the item name pop up."));
            }
            case "off", "false", "disable" -> {
                Qol.heldItemHudEnabled = false;
                persist("held_item_hud", false);
                echo.accept(InputState.DisplayLine.system("Held-item HUD disabled."));
            }
            default -> echo.accept(InputState.DisplayLine.error("Unknown subcommand: " + sub));
        }
    }

    private static void handleCooldown(String[] parts, Consumer<InputState.DisplayLine> echo) {
        String sub = sub(parts);
        if (sub == null || "status".equals(sub)) {
            echo.accept(InputState.DisplayLine.system("Cooldown HUD: " + onOff(Qol.cooldownHudEnabled)));
            return;
        }
        switch (sub) {
            case "on", "true", "enable" -> {
                Qol.cooldownHudEnabled = true;
                persist("cooldown_hud", true);
                echo.accept(InputState.DisplayLine.system(
                        "Cooldown HUD enabled (left edge). Items on cooldown show with time remaining; "
                                + "hides itself when nothing's cooling down."));
            }
            case "off", "false", "disable" -> {
                Qol.cooldownHudEnabled = false;
                persist("cooldown_hud", false);
                echo.accept(InputState.DisplayLine.system("Cooldown HUD disabled."));
            }
            default -> echo.accept(InputState.DisplayLine.error("Unknown subcommand: " + sub));
        }
    }

    private static void listAll(Consumer<InputState.DisplayLine> echo) {
        echo.accept(InputState.DisplayLine.system("QoL helpers:"));
        echo.accept(InputState.DisplayLine.system("  Recipe preview:  " + onOff(Qol.recipePreviewEnabled)
                + " · " + RecipePreview.knownRecipeCount() + " items in table"));
        echo.accept(InputState.DisplayLine.system("  Coords HUD:      " + onOff(Qol.coordsHudEnabled)));
        echo.accept(InputState.DisplayLine.system("  Held-item HUD:   " + onOff(Qol.heldItemHudEnabled)));
        echo.accept(InputState.DisplayLine.system("  Cooldown HUD:    " + onOff(Qol.cooldownHudEnabled)));
        echo.accept(InputState.DisplayLine.system("Toggle with /<name> on|off."));
    }


    /** Mirror a toggle flip into the persisted config (survives restarts). */
    private static void persist(String key, boolean v) {
        try {
            app.shadowclient.chat.ShadowChatClient.get().modConfig().setHelperToggle(key, v);
        } catch (IllegalStateException ignored) {
            // Mod still initializing — startup restore covers this window.
        }
    }

    private static String sub(String[] parts) {
        if (parts.length < 2) return null;
        return parts[1].toLowerCase(Locale.ROOT);
    }

    private static String onOff(boolean b) { return b ? "ON" : "off"; }
}
