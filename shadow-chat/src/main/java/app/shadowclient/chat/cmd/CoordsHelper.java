package app.shadowclient.chat.cmd;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.Optional;

/**
 * Helper for the "paste coordinates" button in the chat input.
 *
 * <p>Reads the local player's position and formats it for chat pasting.
 * Returns {@link Optional#empty()} when there's no player (main menu,
 * world loading, etc.) — the caller should disable the button in that
 * case rather than panic.
 *
 * <p>Default format mirrors what Lunar / Feather use:
 * {@code "X: 123 Y: 64 Z: -456"} — block coordinates (integer floor of
 * the entity's float pos), space-separated, capital axis letters.
 * Easy to scan in chat and parses cleanly into any client-side
 * "go to coords" feature.
 */
public final class CoordsHelper {

    private CoordsHelper() {}

    /**
     * @return the current local player's coordinates formatted for chat,
     *         or {@link Optional#empty()} when no player is available.
     */
    public static Optional<String> currentCoords() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return Optional.empty();
        return Optional.of(format(player));
    }

    /**
     * @return whether {@link #currentCoords()} would return a value.
     *         Cheaper than calling currentCoords just to check
     *         presence; used by the UI to gate the button's enabled
     *         state per frame.
     */
    public static boolean hasPlayer() {
        return Minecraft.getInstance().player != null;
    }

    private static String format(Entity entity) {
        BlockPos pos = entity.blockPosition();
        return "X: " + pos.getX() + " Y: " + pos.getY() + " Z: " + pos.getZ();
    }
}
