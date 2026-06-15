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

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Left-edge HUD that lists the player's own items currently on
 * cooldown, with the exact time remaining next to each — for servers
 * (e.g. Hoplite) where legendaries / golden heads / ability items use
 * the vanilla item-cooldown system (the white hotbar "sweep").
 *
 * <p>Everything here is read straight off the LOCAL player's own
 * {@link ItemCooldowns} — the same data that draws the hotbar overlay.
 * It surfaces nothing the client wasn't already told and nothing about
 * other players, so it's a readout convenience (like Lunar/Badlion's
 * cooldown display), not an information advantage.
 *
 * <p><b>How the time is read:</b> {@code ItemCooldowns} only exposes a
 * 0..1 <i>percent</i> publicly, so the first cut estimated the length
 * from the per-tick decay — which float-quantizes into jittery, wrong
 * numbers on long cooldowns. Instead we read the manager's own state
 * directly: the {@code tickCount} counter and each active cooldown's
 * {@code start}/{@code end} ticks, giving an exact integer
 * {@code remaining = end - tickCount}. The reflection resolves fields
 * by <i>type</i> (the single int counter; the Map; the two ints inside
 * each entry) rather than by name, so a mapping/rename across MC
 * versions doesn't silently break it; if reflection ever fails we fall
 * back to a name-only list (no invented numbers).
 *
 * <p>Reading the map directly also means an item whose cooldown is
 * keyed to a group the per-item percent API doesn't resolve still shows
 * up (labelled from the cooldown's group id when no held item matches).
 *
 * <p>Toggle: {@code /cooldowns on|off|status} (aliases {@code /cdhud},
 * {@code /cd}). Hidden automatically when nothing is on cooldown.
 */
public final class CooldownHud {

    /** Immutable render row handed from the tick thread to render. */
    private record Row(String name, int remainingTicks, boolean exact) {}

    /** Published from the client tick, read on the render thread. */
    private static volatile List<Row> snapshot = List.of();

    // ── Cached reflection handles into ItemCooldowns internals ──────────
    private static boolean reflectInit = false;
    private static boolean reflectOk = false;
    private static Field fTickCount;     // int counter on ItemCooldowns
    private static Field fCooldownsMap;  // Map<group, entry> on ItemCooldowns
    private static Field fEntryA;        // first  int on the entry record
    private static Field fEntryB;        // second int on the entry record

    private CooldownHud() {}

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!Qol.cooldownHudEnabled) { snapshot = List.of(); return; }
            LocalPlayer p = client.player;
            if (p == null) { snapshot = List.of(); return; }

            List<Row> rows = build(p.getCooldowns(), p.getInventory());
            rows.sort(Comparator.comparingInt(Row::remainingTicks));
            snapshot = rows;
        });

        HudRenderCallback.EVENT.register((GuiGraphics gfx, DeltaTracker dt) -> {
            if (!Qol.cooldownHudEnabled) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) return;
            List<Row> rows = snapshot;
            if (rows.isEmpty()) return;

            final int lineH = 10;
            String title = "§bCooldowns";

            List<String> lines = new ArrayList<>(rows.size());
            int w = mc.font.width(title);
            for (Row r : rows) {
                String text = r.exact()
                        ? "§e" + r.name() + " §f" + fmt(r.remainingTicks())
                        : "§e" + r.name() + " §7…";
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

    /**
     * Build the current cooldown rows. Primary path reads exact ticks
     * out of {@link ItemCooldowns}; on any reflection failure it degrades
     * to a name-only list off the public percent API.
     */
    private static List<Row> build(ItemCooldowns cd, Inventory inv) {
        ensureReflect();
        if (reflectOk) {
            try {
                return buildExact(cd, inv);
            } catch (Throwable t) {
                reflectOk = false;   // stop trying; fall through this + future ticks
            }
        }
        return buildNameOnly(cd, inv);
    }

    /** Exact path: read tickCount + each entry's start/end ticks. */
    private static List<Row> buildExact(ItemCooldowns cd, Inventory inv) throws IllegalAccessException {
        int tick = fTickCount.getInt(cd);
        Map<?, ?> map = (Map<?, ?>) fCooldownsMap.get(cd);
        if (map == null || map.isEmpty()) return new ArrayList<>();

        // Exact item→group labels via the public getCooldownGroup API, so a
        // group's countdown shows the actual held item's name.
        Map<Object, String> nameByGroup = groupLabels(cd, inv);

        // Dedupe by display label, keeping the longest remaining.
        Map<String, Row> byLabel = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object entry = e.getValue();
            if (entry == null) continue;
            if (fEntryA == null) resolveEntryFields(entry.getClass());
            if (fEntryA == null) continue;

            int a = fEntryA.getInt(entry);
            int b = fEntryB.getInt(entry);
            int start = Math.min(a, b);
            int end = Math.max(a, b);
            int remaining = end - tick;
            if (remaining <= 0) continue;

            Object key = e.getKey();
            String label = nameByGroup.get(key);
            if (label == null) {
                // No held item resolves to this group — match by percent, else
                // fall back to the group's own id.
                int span = end - start;
                float pct = span > 0 ? (float) (end - tick) / span : 1.0f;
                label = labelFor(inv, cd, pct, key);
            }

            Row prev = byLabel.get(label);
            if (prev == null || remaining > prev.remainingTicks()) {
                byLabel.put(label, new Row(label, remaining, true));
            }
        }
        return new ArrayList<>(byLabel.values());
    }

    /**
     * Map each inventory item's cooldown group → its display name via the
     * public {@code getCooldownGroup(ItemStack)} API. First slot to claim a
     * group wins (hotbar order). Returns empty on any failure.
     */
    private static Map<Object, String> groupLabels(ItemCooldowns cd, Inventory inv) {
        Map<Object, String> m = new java.util.HashMap<>();
        int size = inv.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.getItem(i);
            if (st == null || st.isEmpty()) continue;
            try {
                Object g = cd.getCooldownGroup(st);
                if (g != null) m.putIfAbsent(g, st.getHoverName().getString());
            } catch (Throwable t) {
                return m;   // API shape changed — skip; labelFor() covers it
            }
        }
        return m;
    }

    /** Fallback: list inventory items on cooldown by name, no number. */
    private static List<Row> buildNameOnly(ItemCooldowns cd, Inventory inv) {
        Map<String, Row> byLabel = new LinkedHashMap<>();
        int size = inv.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.getItem(i);
            if (st == null || st.isEmpty()) continue;
            if (cd.getCooldownPercent(st, 0.0f) <= 0.001f) continue;
            String name = st.getHoverName().getString();
            byLabel.putIfAbsent(name, new Row(name, Integer.MAX_VALUE, false));
        }
        return new ArrayList<>(byLabel.values());
    }

    /**
     * Label a cooldown group: prefer a held item whose public percent
     * matches this group's percent (so the player sees the real item
     * name), else prettify the group's own id (e.g. {@code
     * hoplite:golden_head} → {@code Golden Head}).
     */
    private static String labelFor(Inventory inv, ItemCooldowns cd, float pct, Object groupKey) {
        String best = null;
        float bestDiff = 0.04f;   // tolerance on the 0..1 percent
        int size = inv.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack st = inv.getItem(i);
            if (st == null || st.isEmpty()) continue;
            float p = cd.getCooldownPercent(st, 0.0f);
            if (p <= 0.0f) continue;
            float d = Math.abs(p - pct);
            if (d < bestDiff) {
                bestDiff = d;
                best = st.getHoverName().getString();
            }
        }
        return best != null ? best : prettify(String.valueOf(groupKey));
    }

    /** "namespace:some_item" → "Some Item". */
    private static String prettify(String id) {
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        path = path.replace('_', ' ').trim();
        if (path.isEmpty()) return id;
        StringBuilder b = new StringBuilder(path.length());
        boolean cap = true;
        for (char c : path.toCharArray()) {
            if (c == ' ') { cap = true; b.append(c); }
            else if (cap) { b.append(Character.toUpperCase(c)); cap = false; }
            else b.append(c);
        }
        return b.toString();
    }

    /** Remaining time: one decimal under 10 s, whole seconds above. */
    private static String fmt(int ticks) {
        double s = Math.max(0.0, ticks / 20.0);
        return s < 10.0 ? String.format("%.1fs", s) : String.format("%ds", Math.round(s));
    }

    // ── Reflection resolution (by type, cached) ─────────────────────────

    private static void ensureReflect() {
        if (reflectInit) return;
        reflectInit = true;
        try {
            Class<?> c = ItemCooldowns.class;
            fTickCount = findField(c, int.class, "tickCount");
            fCooldownsMap = findMapField(c, "cooldowns");
            if (fTickCount != null && fCooldownsMap != null) {
                fTickCount.setAccessible(true);
                fCooldownsMap.setAccessible(true);
                reflectOk = true;
            }
        } catch (Throwable t) {
            reflectOk = false;
        }
    }

    /** First the named field if it has the wanted type, else the sole field of that type. */
    private static Field findField(Class<?> c, Class<?> type, String preferredName) {
        try {
            Field f = c.getDeclaredField(preferredName);
            if (f.getType() == type) return f;
        } catch (NoSuchFieldException ignored) { /* fall through to scan */ }
        Field only = null;
        int count = 0;
        for (Field f : c.getDeclaredFields()) {
            if (f.getType() == type) { only = f; count++; }
        }
        return count == 1 ? only : null;
    }

    private static Field findMapField(Class<?> c, String preferredName) {
        try {
            Field f = c.getDeclaredField(preferredName);
            if (Map.class.isAssignableFrom(f.getType())) return f;
        } catch (NoSuchFieldException ignored) { /* fall through to scan */ }
        for (Field f : c.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(f.getType())) return f;
        }
        return null;
    }

    /** Resolve the two int fields (start/end ticks) on a cooldown entry. */
    private static void resolveEntryFields(Class<?> entryClass) {
        List<Field> ints = new ArrayList<>(2);
        for (Field f : entryClass.getDeclaredFields()) {
            if (f.getType() == int.class) {
                f.setAccessible(true);
                ints.add(f);
            }
        }
        if (ints.size() >= 2) {
            fEntryA = ints.get(0);
            fEntryB = ints.get(1);
        }
    }
}
