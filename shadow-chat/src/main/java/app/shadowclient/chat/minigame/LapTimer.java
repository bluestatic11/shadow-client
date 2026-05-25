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
 * Lap timer for ice-boat / parkour racing courses.
 *
 * <p>When the user types {@code /laptimer on} the toggle handler
 * calls {@link #anchorStartLineHere()} to record the current
 * position as the start line. The lap state machine is then:
 * <ul>
 *   <li><b>Anchored, not in lap</b> — waiting for the player to leave
 *       the start zone (3 blocks) and then cross back across it.</li>
 *   <li><b>In lap</b> — start timer is running. Closing the lap
 *       requires re-entering the 3-block radius ≥5 s after the lap
 *       began (filters out the racer rocking back and forth at the
 *       line right after the start).</li>
 *   <li><b>Lap closed</b> — last/best stats updated, new lap starts
 *       immediately for circuit-style courses.</li>
 * </ul>
 *
 * <p>{@code /laptimer off} discards the start line; {@code reset}
 * clears the lap stats while keeping the line anchored.
 */
public final class LapTimer {

    private static double startX = Double.NaN, startZ = Double.NaN, startY = Double.NaN;
    private static long armedAtMs = 0L;
    private static boolean inLap = false;
    private static long lapStartMs = 0L;
    private static long lastLapMs = 0L;
    private static long bestLapMs = 0L;
    private static int lapCount = 0;

    private LapTimer() {}

    public static boolean hasStartLine() { return !Double.isNaN(startX); }
    public static int lapCount() { return lapCount; }
    public static long lastLapMs() { return lastLapMs; }
    public static long bestLapMs() { return bestLapMs; }

    public static void anchorStartLineHere() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Entity ref = mc.player.getVehicle() != null ? mc.player.getVehicle() : mc.player;
        startX = ref.getX();
        startZ = ref.getZ();
        startY = ref.getY();
        armedAtMs = System.currentTimeMillis();
        inLap = false;
        lapStartMs = 0L;
    }

    public static String startLineDescription() {
        if (!hasStartLine()) return "unset";
        return String.format("(%.1f, %.1f, %.1f)", startX, startY, startZ);
    }

    /** Clear lap stats but keep the start line anchored. */
    public static void resetStats() {
        inLap = false;
        lapStartMs = 0L;
        lastLapMs = 0L;
        bestLapMs = 0L;
        lapCount = 0;
        armedAtMs = System.currentTimeMillis(); // re-arm so we don't trigger off the player still on the line
    }

    /** Full clear including start line. Called by {@code /laptimer off}. */
    public static void clearAll() {
        startX = Double.NaN; startZ = Double.NaN; startY = Double.NaN;
        resetStats();
    }

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!Minigames.lapTimerEnabled) return;
            if (client.player == null) return;
            if (Double.isNaN(startX)) return;

            LocalPlayer p = client.player;
            Entity ref = p.getVehicle() != null ? p.getVehicle() : p;
            double dx = ref.getX() - startX;
            double dz = ref.getZ() - startZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            long now = System.currentTimeMillis();

            if (!inLap && dist < 3.0 && (now - armedAtMs) > 1500) {
                // Crossing the start line after arming → begin lap.
                inLap = true;
                lapStartMs = now;
                p.displayClientMessage(Component.literal("§b[Lap] §aStart!"), true);
            } else if (inLap && dist < 3.0 && (now - lapStartMs) > 5000) {
                // Crossing the line again after ≥5 s → close lap.
                lastLapMs = now - lapStartMs;
                lapCount++;
                boolean pb = (bestLapMs == 0L || lastLapMs < bestLapMs);
                if (pb) bestLapMs = lastLapMs;
                p.displayClientMessage(Component.literal(
                        String.format("§b[Lap %d] §f%s%s",
                                lapCount, formatLap(lastLapMs),
                                pb ? " §a§l[PB!]" : "")), false);
                // Circuit mode: the next lap starts immediately so
                // continuous timing works without re-arming.
                lapStartMs = now;
            }
        });

        HudRenderCallback.EVENT.register((GuiGraphics gfx, DeltaTracker dt) -> {
            if (!Minigames.lapTimerEnabled) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (mc.screen != null) return;

            int x = 4;
            int y = 60;
            int lineH = 10;
            int w = 118;

            int rows = 2;
            if (inLap) rows++;
            if (lapCount > 0) rows += 2;

            gfx.fill(x - 2, y - 2, x + w, y + rows * lineH + 1, 0x80000000);
            int sy = y;

            gfx.drawString(mc.font, Component.literal("§b§l[Lap Timer]"), x, sy, 0xFFFFFF); sy += lineH;
            if (!hasStartLine()) {
                gfx.drawString(mc.font, Component.literal("§7No start line"), x, sy, 0xFFFFFF); sy += lineH;
            } else if (!inLap) {
                gfx.drawString(mc.font, Component.literal("§7Cross start to begin"), x, sy, 0xFFFFFF); sy += lineH;
            } else {
                long elapsed = System.currentTimeMillis() - lapStartMs;
                gfx.drawString(mc.font, Component.literal("§eLap: §f" + formatLap(elapsed)),
                        x, sy, 0xFFFFFF); sy += lineH;
            }
            if (lapCount > 0) {
                gfx.drawString(mc.font, Component.literal("§7Last: §f" + formatLap(lastLapMs)),
                        x, sy, 0xFFFFFF); sy += lineH;
                gfx.drawString(mc.font, Component.literal("§6Best: §f" + formatLap(bestLapMs)),
                        x, sy, 0xFFFFFF);
            }
        });
    }

    public static String formatLap(long ms) {
        long s = ms / 1000;
        long sub = (ms % 1000) / 10;
        return String.format("%d.%02ds", s, sub);
    }
}
