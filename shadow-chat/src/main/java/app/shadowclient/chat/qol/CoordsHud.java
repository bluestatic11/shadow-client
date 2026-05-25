package app.shadowclient.chat.qol;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * Top-right HUD overlay with player XYZ and facing direction. Less
 * noisy than F3 — just the bits you actually want to read mid-play.
 *
 * <p>Toggle: {@code /coordshud on|off|status}.
 */
public final class CoordsHud {

    private CoordsHud() {}

    static void register() {
        HudRenderCallback.EVENT.register((GuiGraphics gfx, DeltaTracker dt) -> {
            if (!Qol.coordsHudEnabled) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            // Don't draw on top of opened screens — let the inventory /
            // chat / pause menu have the screen.
            if (mc.screen != null) return;

            LocalPlayer p = mc.player;
            String coords = String.format("§eX §f%.1f  §eY §f%.1f  §eZ §f%.1f",
                    p.getX(), p.getY(), p.getZ());
            String facing = "§7" + cardinalFor(p.getYRot());

            int w = Math.max(mc.font.width(coords), mc.font.width(facing)) + 8;
            int x = gfx.guiWidth() - w - 4;
            int y = 4;

            gfx.fill(x - 2, y - 2, x + w, y + 22, 0x80000000);
            gfx.drawString(mc.font, Component.literal(coords), x, y, 0xFFFFFF);
            gfx.drawString(mc.font, Component.literal(facing),  x, y + 10, 0xFFFFFF);
        });
    }

    /**
     * Map yaw degrees → cardinal label. MC's yaw 0 points south, so
     * the table starts there and wraps every 45° around the compass.
     * Returns the same labels the F3 screen uses for consistency.
     */
    private static String cardinalFor(float yaw) {
        // Normalize to 0..360 then snap to 22.5° octants
        float y = ((yaw % 360.0f) + 360.0f) % 360.0f;
        int oct = Math.round(y / 45.0f) & 7;
        return switch (oct) {
            case 0 -> "south (+Z)";
            case 1 -> "south-west";
            case 2 -> "west (-X)";
            case 3 -> "north-west";
            case 4 -> "north (-Z)";
            case 5 -> "north-east";
            case 6 -> "east (+X)";
            case 7 -> "south-east";
            default -> "?";
        };
    }
}
