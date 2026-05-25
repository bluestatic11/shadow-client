package app.shadowclient.chat.minigame;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.player.LocalPlayer;

/**
 * Always sprint when holding W. Saves finger fatigue in long parkour
 * / racing sessions where the Ctrl-hold gets old fast.
 *
 * <p>Guarded so it doesn't override deliberate sneak / item-use, and
 * skipped entirely while in a vehicle (boat speed isn't player-sprint
 * controlled) or with food ≤ 6 (vanilla blocks sprint there anyway,
 * but calling setSprinting still emits a packet — guard saves spam).
 *
 * <p>Toggle: {@code /autosprint on|off|status}.
 */
public final class AutoSprint {

    private AutoSprint() {}

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!Minigames.autoSprintEnabled) return;
            if (client.player == null || client.options == null) return;
            LocalPlayer p = client.player;

            // Vehicles handle their own movement.
            if (p.getVehicle() != null) return;

            // MC requires hunger > 6 to sprint — guard the no-op call.
            if (p.getFoodData().getFoodLevel() <= 6) return;

            // Respect deliberate sneak / item-use.
            if (p.isShiftKeyDown() || p.isUsingItem()) return;

            // Only sprint while actually moving forward.
            if (client.options.keyUp.isDown()) {
                p.setSprinting(true);
            }
        });
    }
}
