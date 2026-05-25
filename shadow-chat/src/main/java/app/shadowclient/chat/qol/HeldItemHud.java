package app.shadowclient.chat.qol;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * "Selected item" toast above the hotbar — when the player switches
 * to a different hotbar slot the item's name (and count, if &gt;1)
 * appears centered above the hotbar for ~2 s and fades out. Same
 * idea as vanilla's "Show held item tooltip" but more responsive
 * and includes count.
 *
 * <p>Toggle: {@code /helditemhud on|off|status}.
 */
public final class HeldItemHud {

    private static int lastSlot = -1;
    private static int lastItemHash = 0;
    private static long lastChangeMs = 0L;

    /** Fade-in fully visible duration, then linear fade. */
    private static final long HOLD_MS = 1200;
    private static final long FADE_MS = 700;

    private HeldItemHud() {}

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!Qol.heldItemHudEnabled) return;
            LocalPlayer p = client.player;
            if (p == null) return;
            int slot = p.getInventory().getSelectedSlot();
            ItemStack held = p.getInventory().getSelectedItem();
            int h = held == null || held.isEmpty()
                    ? 0
                    : held.getItem().hashCode() * 31 + held.getCount();
            if (slot != lastSlot || h != lastItemHash) {
                lastSlot = slot;
                lastItemHash = h;
                lastChangeMs = System.currentTimeMillis();
            }
        });

        HudRenderCallback.EVENT.register((GuiGraphics gfx, DeltaTracker dt) -> {
            if (!Qol.heldItemHudEnabled) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (mc.screen != null) return;
            if (lastChangeMs == 0L) return;

            long age = System.currentTimeMillis() - lastChangeMs;
            long total = HOLD_MS + FADE_MS;
            if (age >= total) return;

            ItemStack held = mc.player.getInventory().getSelectedItem();
            if (held == null || held.isEmpty()) return;

            Component name = held.getHoverName();
            String text = held.getCount() > 1
                    ? String.format("%s §7× §f%d", name.getString(), held.getCount())
                    : name.getString();

            // Linear fade across the FADE_MS tail. Compute the alpha
            // first so the background + text dim together.
            int alpha;
            if (age < HOLD_MS) {
                alpha = 255;
            } else {
                long inFade = age - HOLD_MS;
                alpha = (int) (255 * (1.0 - (double) inFade / FADE_MS));
                alpha = Math.max(0, Math.min(255, alpha));
            }
            if (alpha == 0) return;

            int textW = mc.font.width(text);
            int screenW = gfx.guiWidth();
            int screenH = gfx.guiHeight();
            int x = (screenW - textW) / 2;
            // 59 px above the bottom — clears the hotbar (22 px) +
            // hunger/health rows (~30 px) with a small gap.
            int y = screenH - 59;

            int bgAlpha = (alpha * 0xA0) / 255;
            int textColor = (alpha << 24) | 0xFFFFFF;
            gfx.fill(x - 4, y - 2, x + textW + 4, y + 10, bgAlpha << 24);
            gfx.drawString(mc.font, Component.literal(text), x, y, textColor);
        });
    }
}
