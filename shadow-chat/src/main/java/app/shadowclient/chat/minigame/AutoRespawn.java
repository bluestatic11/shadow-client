package app.shadowclient.chat.minigame;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;

/**
 * Instantly fires the respawn packet whenever the death screen
 * appears, then closes it. Saves clicking through "Respawn" /
 * "Title Screen" between every parkour fall and every spleef KO.
 *
 * <p>20-tick cooldown after each respawn so a bug-loop (e.g. spawning
 * back into the same lava pit) doesn't packet-spam the server.
 *
 * <p>Toggle: {@code /autorespawn on|off|status}.
 */
public final class AutoRespawn {

    private static int cooldownTicks = 0;

    private AutoRespawn() {}

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (cooldownTicks > 0) cooldownTicks--;
            if (!Minigames.autoRespawnEnabled) return;
            if (client.player == null || client.player.connection == null) return;

            LocalPlayer p = client.player;
            boolean dead = p.isDeadOrDying() || client.screen instanceof DeathScreen;
            if (!dead) return;
            if (cooldownTicks > 0) return;

            p.connection.send(new ServerboundClientCommandPacket(
                    ServerboundClientCommandPacket.Action.PERFORM_RESPAWN));
            if (client.screen instanceof DeathScreen) {
                client.setScreen(null);
            }
            cooldownTicks = 20;
        });
    }
}
