package app.shadowclient.chat.minigame;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * Bottom-left HUD overlay showing current ground-plane speed, session
 * peak, and post-landing jump distance + height. When the player is
 * in a boat or minecart, picks up the vehicle's position so racers
 * see actual boat speed rather than the player's lerped local
 * position (which lags the boat by a few ticks on ice).
 *
 * <p>Toggle: {@code /speedhud on|off|reset|status}.
 */
public final class SpeedHud {

    private static double lastX = Double.NaN, lastZ = Double.NaN;
    private static double currentSpeed = 0.0;
    private static double peakSpeed = 0.0;

    /** Identity of the level the last sample came from — see the
     *  world-change reset in the tick handler. Weak so we never pin a
     *  left world in memory. */
    private static java.lang.ref.WeakReference<Object> lastLevel = new java.lang.ref.WeakReference<>(null);

    // Jump tracking
    private static boolean inJump = false;
    private static double jumpStartX, jumpStartZ, jumpStartY, jumpPeakY;
    private static double lastJumpDistance = 0.0;
    private static double lastJumpHeight = 0.0;
    private static double bestJumpDistance = 0.0;
    private static long lastJumpEndedMs = 0L;

    private SpeedHud() {}

    /** Wipe session peak + jump PB. Called by {@code /speedhud reset}. */
    public static void resetSession() {
        peakSpeed = 0.0;
        bestJumpDistance = 0.0;
        lastJumpDistance = 0.0;
        lastJumpHeight = 0.0;
        lastJumpEndedMs = 0L;
    }

    public static double currentSpeed() { return currentSpeed; }
    public static double peakSpeed() { return peakSpeed; }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!Minigames.speedHudEnabled) {
                // Reset bookkeeping so a fresh enable doesn't show a
                // huge bogus delta from the position when toggled off.
                lastX = Double.NaN;
                lastZ = Double.NaN;
                inJump = false;
                currentSpeed = 0.0;
                return;
            }
            if (client.player == null) return;
            LocalPlayer p = client.player;

            // World/dimension change → old sample coordinates belong to a
            // different coordinate space. Re-seed instead of recording a
            // multi-thousand-block "move".
            if (lastLevel.get() != client.level) {
                lastLevel = new java.lang.ref.WeakReference<>(client.level);
                lastX = Double.NaN;
                lastZ = Double.NaN;
                inJump = false;
                currentSpeed = 0.0;
                return;
            }

            // Sample the vehicle when the player is riding one —
            // gives the actual boat speed instead of the player's
            // lerped client position which can lag behind on ice.
            Entity ref = p.getVehicle() != null ? p.getVehicle() : p;
            double x = ref.getX(), y = ref.getY(), z = ref.getZ();

            if (Double.isNaN(lastX)) { lastX = x; lastZ = z; }
            double dx = x - lastX, dz = z - lastZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            // Teleport guard: nothing legitimate moves >30 blocks in one
            // tick (600 b/s — elytra+firework peaks ~6 b/t). /tp, pearls
            // across the map, or server-side warps would otherwise spike
            // the session peak to absurd values and poison the jump PB.
            if (dist > 30.0) {
                lastX = x; lastZ = z;
                inJump = false;
                currentSpeed = 0.0;
                return;
            }
            currentSpeed = dist * 20.0; // ticks → seconds
            if (currentSpeed > peakSpeed) peakSpeed = currentSpeed;
            lastX = x; lastZ = z;

            // Jump tracking — only meaningful when the player is on
            // foot. In a vehicle, "jump" data would describe the boat
            // catching air, which isn't what parkour players want.
            if (p.getVehicle() == null) {
                boolean onGround = p.onGround();
                if (!inJump && !onGround) {
                    inJump = true;
                    jumpStartX = p.getX(); jumpStartZ = p.getZ();
                    jumpStartY = p.getY(); jumpPeakY = p.getY();
                } else if (inJump) {
                    double py = p.getY();
                    if (py > jumpPeakY) jumpPeakY = py;
                    if (onGround) {
                        inJump = false;
                        double jdx = p.getX() - jumpStartX, jdz = p.getZ() - jumpStartZ;
                        lastJumpDistance = Math.sqrt(jdx * jdx + jdz * jdz);
                        lastJumpHeight = jumpPeakY - jumpStartY;
                        lastJumpEndedMs = System.currentTimeMillis();
                        if (lastJumpDistance > bestJumpDistance) bestJumpDistance = lastJumpDistance;
                    }
                }
            }
        });

        HudRenderCallback.EVENT.register((GuiGraphics gfx, DeltaTracker dt) -> {
            if (!Minigames.speedHudEnabled) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            // Hide the HUD while a screen (chat, inventory, etc.) is open
            // so we don't draw on top of the user's interface.
            if (mc.screen != null) return;

            int x = 4;
            int y = gfx.guiHeight() - 64;
            int lineH = 10;
            String l1 = String.format("§eSpeed: §f%.1f §7b/s", currentSpeed);
            String l2 = String.format("§7Peak: %.1f", peakSpeed);

            long sinceJump = System.currentTimeMillis() - lastJumpEndedMs;
            boolean showLast = lastJumpEndedMs > 0 && sinceJump < 3000;
            boolean showPB = bestJumpDistance > 0;
            int rows = 2 + (showLast ? 1 : 0) + (showPB ? 1 : 0);
            int w = 96;

            gfx.fill(x - 2, y - 2, x + w, y + rows * lineH + 1, 0x80000000);
            int sy = y;
            gfx.drawString(mc.font, Component.literal(l1), x, sy, 0xFFFFFF); sy += lineH;
            gfx.drawString(mc.font, Component.literal(l2), x, sy, 0xFFFFFF); sy += lineH;
            if (showLast) {
                String jt = String.format("§bJump: %.1fb §7↑%.1f",
                        lastJumpDistance, lastJumpHeight);
                gfx.drawString(mc.font, Component.literal(jt), x, sy, 0xFFFFFF); sy += lineH;
            }
            if (showPB) {
                String pb = String.format("§6PB: %.1fb", bestJumpDistance);
                gfx.drawString(mc.font, Component.literal(pb), x, sy, 0xFFFFFF);
            }
        });
    }
}
