package app.shadowclient.chat.qol;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemCooldowns;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Left-edge HUD that lists the player's own items currently on
 * cooldown, with the time remaining next to each — useful on servers
 * (e.g. Hoplite) where legendaries / golden heads / ability items
 * share the vanilla item-cooldown system (the white hotbar "sweep").
 *
 * <p>Everything here is read straight off the LOCAL player's own
 * client state via the public {@link ItemCooldowns} API — the same
 * data that draws the hotbar cooldown overlay. It surfaces nothing the
 * client wasn't already told and nothing about other players, so it's
 * a readout convenience (like Lunar/Badlion's cooldown display), not
 * an information advantage.
 *
 * <p><b>Why the rate estimate:</b> {@code ItemCooldowns} only exposes a
 * 0..1 <i>percent</i> publicly — the raw tick counts are private. So
 * instead of reflecting into internals (which churn across MC
 * versions), we sample the percent each tick: once it's ticking down,
 * the per-tick drop {@code Δpercent} gives the full length
 * ({@code total = Δticks / Δpercent}), and {@code remaining = percent ×
 * total}. It self-calibrates within a tick or two; until then the row
 * shows "…".
 *
 * <p>Toggle: {@code /cooldowns on|off|status} (aliases {@code /cdhud},
 * {@code /cd}). Hidden automatically whenever nothing is on cooldown.
 */
public final class CooldownHud {

    /** Per-item tracking state, keyed by the item's display name. */
    private static final class Track {
        float lastPct;
        long lastTick;
        double totalTicks = -1.0;   // estimated full cooldown length, ticks
        long lastSeenTick;
        String name;
    }

    /** Immutable render row handed from the tick thread to render. */
    private record Row(String name, double remainingTicks) {}

    private static final Map<String, Track> tracks = new HashMap<>();
    private static long gameTick = 0L;
    /** Published from the client tick, read on the render thread. */
    private static volatile List<Row> snapshot = List.of();

    private CooldownHud() {}

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!Qol.cooldownHudEnabled) {
                if (!tracks.isEmpty()) { tracks.clear(); snapshot = List.of(); }
                return;
            }
            LocalPlayer p = client.player;
            if (p == null) {
                if (!tracks.isEmpty()) { tracks.clear(); snapshot = List.of(); }
                return;
            }
            gameTick++;
            ItemCooldowns cd = p.getCooldowns();
            Inventory inv = p.getInventory();

            // Highest percent seen per display name this tick — two stacks
            // of the same item share one cooldown; the held one usually has
            // the freshest reading.
            Map<String, Float> peak = new HashMap<>();
            int size = inv.getContainerSize();
            for (int i = 0; i < size; i++) {
                ItemStack st = inv.getItem(i);
                if (st == null || st.isEmpty()) continue;
                float pct = cd.getCooldownPercent(st, 0.0f);
                if (pct <= 0.001f) continue;
                String name = st.getHoverName().getString();
                Float cur = peak.get(name);
                if (cur == null || pct > cur) peak.put(name, pct);
            }

            List<Row> rows = new ArrayList<>(peak.size());
            for (Map.Entry<String, Float> e : peak.entrySet()) {
                String key = e.getKey();
                float pct = e.getValue();
                Track t = tracks.get(key);
                if (t == null) { t = new Track(); t.name = key; tracks.put(key, t); }

                // Estimate total length from the per-tick decay rate.
                if (t.lastSeenTick != 0L && t.lastPct > pct + 1e-4f) {
                    double dTicks = gameTick - t.lastTick;
                    double dPct = t.lastPct - pct;
                    if (dTicks > 0 && dPct > 0) {
                        double est = dTicks / dPct;
                        // Light smoothing — float percent quantizes per tick,
                        // so blend rather than snap to kill display jitter.
                        t.totalTicks = t.totalTicks < 0 ? est : (t.totalTicks * 0.5 + est * 0.5);
                    }
                }
                t.lastPct = pct;
                t.lastTick = gameTick;
                t.lastSeenTick = gameTick;

                double remaining = t.totalTicks > 0 ? pct * t.totalTicks : -1.0;
                rows.add(new Row(t.name, remaining));
            }
            // Anything not still on cooldown this tick is ready again — drop it.
            tracks.entrySet().removeIf(en -> en.getValue().lastSeenTick != gameTick);

            rows.sort(Comparator.comparingDouble(
                    r -> r.remainingTicks() < 0 ? Double.MAX_VALUE : r.remainingTicks()));
            snapshot = rows;
        });

        HudRenderCallback.EVENT.register((GuiGraphics gfx, DeltaTracker dt) -> {
            if (!Qol.cooldownHudEnabled) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (mc.screen != null) return;
            List<Row> rows = snapshot;
            if (rows.isEmpty()) return;

            final int lineH = 10;
            String title = "§bCooldowns";

            List<String> lines = new ArrayList<>(rows.size());
            int w = mc.font.width(title);
            for (Row r : rows) {
                String text = r.remainingTicks() < 0
                        ? "§e" + r.name() + " §7…"
                        : "§e" + r.name() + " §f" + fmt(r.remainingTicks());
                lines.add(text);
                w = Math.max(w, mc.font.width(text));
            }
            w += 8;

            int panelH = (lines.size() + 1) * lineH + 6;
            int x = 4;
            // Centered on the left edge — clear of the held-item HUD (top),
            // lap timer (y≈60) and speed HUD (bottom).
            int y = Math.max(4, (gfx.guiHeight() - panelH) / 2);

            gfx.fill(x - 2, y - 2, x + w, y + panelH, 0x90000000);
            gfx.drawString(mc.font, Component.literal(title), x, y, 0x55FFFF);
            int sy = y + lineH + 2;
            for (String l : lines) {
                gfx.drawString(mc.font, Component.literal(l), x, sy, 0xFFFFFF);
                sy += lineH;
            }
        });
    }

    /** Remaining time: one decimal under 10 s, whole seconds above. */
    private static String fmt(double ticks) {
        double s = Math.max(0.0, ticks / 20.0);
        return s < 10.0 ? String.format("%.1fs", s) : String.format("%ds", Math.round(s));
    }
}
