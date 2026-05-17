package app.shadowclient.chat.ui;

import app.shadowclient.chat.ShadowChatClient;
import app.shadowclient.chat.config.ModConfig;
import app.shadowclient.chat.relay.Messages.ServerEvent;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Bottom-anchored chat overlay rendered via {@link HudRenderCallback}.
 *
 * <p>The overlay has two modes:
 * <ul>
 *   <li><b>HUD mode</b>: passive read-only display under any active
 *       {@link net.minecraft.client.gui.screens.Screen}, plus when
 *       the player has no screen open. The overlay still renders so
 *       the player can glance at chat without opening a focused UI.</li>
 *   <li><b>Focused mode</b>: when the player presses the toggle key,
 *       we open a {@link ChatScreen} which paints on top of the HUD
 *       and grabs keyboard input for the message field.</li>
 * </ul>
 *
 * <p>The visibility flag in {@link InputState} controls whether the
 * HUD mode renders at all. The hotkey toggles that flag; opening the
 * focused screen is a separate, transient action that closes itself
 * via Esc (Minecraft's default Screen behavior) without affecting
 * the persistent visibility flag.
 */
public final class ChatOverlay {

    /** Overlay width as fraction of screen width. */
    private static final double WIDTH_FRACTION = 0.40;
    /** Maximum overlay height in pixels (regardless of how many messages). */
    private static final int MAX_HEIGHT = 300;
    /** Padding inside the overlay panel. */
    private static final int PAD = 4;
    /** Pixels between consecutive lines. */
    private static final int LINE_GAP = 1;

    /** Argb color of the panel background — translucent black. */
    private static final int BG_COLOR = 0xB0000000;
    /** Bright chip when channel is selected. */
    private static final int CHIP_ACTIVE = 0xFF3D7B3D;
    /** Dim chip otherwise. */
    private static final int CHIP_INACTIVE = 0xFF2A2A2A;
    private static final int CHIP_TEXT = 0xFFFFFFFF;

    private final InputState state;
    private final ModConfig config;

    public ChatOverlay(InputState state, ModConfig config) {
        this.state = state;
        this.config = config;
    }

    public void register() {
        HudRenderCallback.EVENT.register(this::onRender);
    }

    private void onRender(GuiGraphics gfx, DeltaTracker dt) {
        // Suppress rendering while a non-ChatInputScreen UI is open — we
        // don't want our overlay painting over the pause menu, an
        // inventory, etc. The focused mode (ChatInputScreen) renders
        // us itself, so that case is also handled.
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null && !(mc.screen instanceof ChatInputScreen)) return;

        if (!state.isOverlayVisible()) return;

        render(gfx, mc, /* showInputField= */ false, "");
    }

    /**
     * Internal render. Called by the HUD callback (no input field)
     * and by {@link ChatScreen} (with the current input buffer drawn).
     */
    void render(GuiGraphics gfx, Minecraft mc, boolean showInputField, String inputText) {
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        Font font = mc.font;

        int panelWidth = Math.max(220, (int) (screenWidth * WIDTH_FRACTION));
        int panelHeight = Math.min(MAX_HEIGHT, screenHeight / 2);

        // Anchor: bottom-left, lifted ~30px so we don't clash with the
        // vanilla hotbar / health rendering.
        int x = 4;
        int y = screenHeight - panelHeight - 32;

        // ---- background ----
        gfx.fill(x, y, x + panelWidth, y + panelHeight, BG_COLOR);

        // ---- channel chips (top row) ----
        int chipY = y + PAD;
        int chipHeight = font.lineHeight + 4;
        int chipX = x + PAD;
        String active = state.activeChannel();

        chipX = drawChip(gfx, font, chipX, chipY, chipHeight, "Server",
                ModConfig.CHANNEL_SERVER.equals(active));
        for (ModConfig.Group g : new ArrayList<>(config.joinedGroups())) {
            String channelKey = "group:" + g.id;
            String label = "G: " + (g.name == null || g.name.isBlank() ? shortId(g.id) : g.name);
            chipX = drawChip(gfx, font, chipX, chipY, chipHeight, label, channelKey.equals(active));
            // Wrap chips that overflow — overflow is silently clipped.
            if (chipX > x + panelWidth - 30) break;
        }

        // ---- status banner (auth / connection) ----
        int statusY = chipY + chipHeight + 2;
        String status = ShadowChatClient.get().statusLine();
        if (status != null && !status.isBlank()) {
            gfx.drawString(font, Component.literal(status), x + PAD, statusY, 0xFFAAAAAA, false);
            statusY += font.lineHeight + 2;
        }

        // ---- presence summary ----
        List<ServerEvent.User> presence = state.presenceFor(active);
        if (!presence.isEmpty()) {
            String summary = presence.size() + " online: " + summarizeNames(presence);
            gfx.drawString(font, Component.literal(summary), x + PAD, statusY, 0xFF7AA8E0, false);
            statusY += font.lineHeight + 2;
        }

        // ---- message log ----
        int logTop = statusY + 2;
        int inputFieldHeight = showInputField ? font.lineHeight + 6 : 0;
        int logBottom = y + panelHeight - PAD - inputFieldHeight;
        int maxLines = Math.max(0, (logBottom - logTop) / (font.lineHeight + LINE_GAP));
        if (maxLines > 0) {
            List<InputState.DisplayLine> lines = state.linesFor(active);
            // Take the last N lines so the most recent messages are visible.
            int start = Math.max(0, lines.size() - maxLines);
            int drawY = logTop;
            int textMaxWidth = panelWidth - PAD * 2;
            for (int i = start; i < lines.size(); i++) {
                InputState.DisplayLine line = lines.get(i);
                String rendered = renderLine(line, font, textMaxWidth);
                int color = line.error() ? 0xFFFF6060
                        : line.system() ? 0xFFAAAAAA
                        : 0xFFFFFFFF;
                gfx.drawString(font, Component.literal(rendered), x + PAD, drawY, color, false);
                drawY += font.lineHeight + LINE_GAP;
            }
        }

        // ---- input field ----
        if (showInputField) {
            int inputY = y + panelHeight - PAD - inputFieldHeight + 1;
            gfx.fill(x + PAD, inputY, x + panelWidth - PAD, inputY + inputFieldHeight - 2, 0xFF1A1A1A);
            // Trim to fit; rough char clamp avoids width measurement on every keystroke.
            String prompt = "> " + inputText + (System.currentTimeMillis() % 1000 < 500 ? "_" : "");
            gfx.drawString(font, Component.literal(prompt), x + PAD + 3, inputY + 3, 0xFFFFFFFF, false);
        }
    }

    /** Draw a chip and return the next x position to draw at. */
    private int drawChip(GuiGraphics gfx, Font font, int x, int y, int height,
                         String label, boolean active) {
        int width = font.width(label) + 8;
        int bg = active ? CHIP_ACTIVE : CHIP_INACTIVE;
        gfx.fill(x, y, x + width, y + height, bg);
        gfx.drawString(font, Component.literal(label),
                x + 4, y + (height - font.lineHeight) / 2 + 1, CHIP_TEXT, false);
        return x + width + 4;
    }

    /** Format a line for display: [HH:mm] Name: text  (or system/error variants). */
    private String renderLine(InputState.DisplayLine line, Font font, int maxWidth) {
        String stamp = "[" + InputState.formatTimestamp(line.ts()) + "] ";
        String body;
        if (line.system() || line.error()) {
            body = line.text();
        } else {
            body = (line.name() == null ? "?" : line.name()) + ": " + line.text();
        }
        String full = stamp + body;
        // If the line is too wide, truncate with an ellipsis. Multi-line
        // wrapping would mean per-frame layout math that's not worth it
        // for the MVP — server caps each msg at 500 chars so this is OK.
        if (font.width(full) <= maxWidth) return full;
        while (full.length() > 4 && font.width(full + "...") > maxWidth) {
            full = full.substring(0, full.length() - 1);
        }
        return full + "...";
    }

    private static String summarizeNames(List<ServerEvent.User> users) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (ServerEvent.User u : users) {
            if (shown > 0) sb.append(", ");
            sb.append(u.name());
            shown++;
            if (shown >= 5 && users.size() > 5) {
                sb.append(" +").append(users.size() - shown).append(" more");
                break;
            }
        }
        return sb.toString();
    }

    private static String shortId(String id) {
        if (id == null) return "?";
        return id.length() > 6 ? id.substring(0, 6) : id;
    }
}
