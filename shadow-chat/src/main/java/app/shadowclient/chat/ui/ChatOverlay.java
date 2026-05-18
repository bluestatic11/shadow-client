package app.shadowclient.chat.ui;

import app.shadowclient.chat.ShadowChatClient;
import app.shadowclient.chat.config.ModConfig;
import app.shadowclient.chat.relay.Messages.ServerEvent;
import app.shadowclient.chat.voice.VoiceController;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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
    /** Color of the speaking-indicator names — soft cyan, easy to spot. */
    private static final int SPEAKER_COLOR = 0xFF6FE6E6;
    /** PTT hint color when idle (greyish). */
    private static final int PTT_IDLE_COLOR = 0xFF808080;
    /** PTT hint color when transmitting (warm red). */
    private static final int PTT_HOT_COLOR = 0xFFFF6B6B;

    private final InputState state;
    private final ModConfig config;

    /**
     * Bounding box of the coords button drawn on the input row. Updated
     * every render so the hit-test in {@link ChatInputScreen} stays in
     * sync with the actual pixel position regardless of window resizes.
     * x2/y2 = 0 means the button isn't currently rendered (input field
     * not visible).
     */
    private int coordsBtnX1 = 0, coordsBtnY1 = 0, coordsBtnX2 = 0, coordsBtnY2 = 0;

    public ChatOverlay(InputState state, ModConfig config) {
        this.state = state;
        this.config = config;
    }

    /**
     * Hit-test the coords button — return true iff the given mouse
     * coordinates (in GUI-scaled pixels) fall inside the button's
     * current bounds. Used by {@link ChatInputScreen#mouseClicked} to
     * decide whether to paste coords into the input buffer.
     */
    public boolean isCoordsButtonHit(double mouseX, double mouseY) {
        if (coordsBtnX2 == 0) return false;
        return mouseX >= coordsBtnX1 && mouseX <= coordsBtnX2
            && mouseY >= coordsBtnY1 && mouseY <= coordsBtnY2;
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

        // ---- speaking indicator ----
        // Pulled from the voice subsystem each frame. Names are looked
        // up against the active channel's presence list; falls back to
        // a short uuid if the speaker isn't in our presence yet (can
        // happen briefly after a join).
        VoiceController vc = ShadowChatClient.get().voice();
        if (vc != null) {
            List<UUID> speakers = vc.playback().currentSpeakers();
            if (!speakers.isEmpty()) {
                StringBuilder sb = new StringBuilder("Speaking: ");
                int shown = 0;
                for (UUID id : speakers) {
                    if (shown > 0) sb.append(", ");
                    sb.append(ShadowChatClient.get().displayNameForUuid(id));
                    shown++;
                    if (shown >= 4 && speakers.size() > 4) {
                        sb.append(" +").append(speakers.size() - shown).append(" more");
                        break;
                    }
                }
                gfx.drawString(font, Component.literal(sb.toString()),
                        x + PAD, statusY, SPEAKER_COLOR, false);
                statusY += font.lineHeight + 2;
            }
        }

        // ---- message log ----
        int logTop = statusY + 2;
        // Reserve a line at the bottom for the PTT hint. When the
        // focused input field is also visible we stack: hint above
        // input, both above the log floor.
        int pttHintHeight = font.lineHeight + 2;
        int inputFieldHeight = showInputField ? font.lineHeight + 6 : 0;
        int logBottom = y + panelHeight - PAD - inputFieldHeight - pttHintHeight;
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

        // ---- ptt hint (just above the input field, or the panel floor if no input) ----
        // Three states:
        //   - mic unavailable → static "Voice unavailable" so the user
        //     knows why the V key is doing nothing.
        //   - PTT held → red "Talking…" to confirm the mic is hot.
        //   - otherwise → grey "Hold V to talk" reminder.
        int pttY = y + panelHeight - PAD - inputFieldHeight - pttHintHeight + 1;
        VoiceController vcHint = ShadowChatClient.get().voice();
        if (vcHint != null) {
            String hint;
            int color;
            if (!vcHint.capture().isAvailable()) {
                hint = "Voice unavailable (no microphone)";
                color = PTT_IDLE_COLOR;
            } else if (vcHint.isTransmitting()) {
                hint = "Talking...";
                color = PTT_HOT_COLOR;
            } else {
                hint = "Hold V to talk";
                color = PTT_IDLE_COLOR;
            }
            gfx.drawString(font, Component.literal(hint), x + PAD, pttY, color, false);
        }

        // ---- input field + coords button ----
        if (showInputField) {
            int inputY = y + panelHeight - PAD - inputFieldHeight + 1;
            // Coords button — square-ish chip on the right side of the
            // input row. Clicking it pastes the local player's X/Y/Z
            // into the input buffer at the cursor (handled in
            // ChatInputScreen.mouseClicked via isCoordsButtonHit).
            String btnLabel = "Coords";
            int btnWidth = font.width(btnLabel) + 10;
            int btnX1 = x + panelWidth - PAD - btnWidth;
            int btnY1 = inputY;
            int btnX2 = x + panelWidth - PAD;
            int btnY2 = inputY + inputFieldHeight - 2;
            // Disable visually if there's no player loaded (main menu).
            boolean enabled = app.shadowclient.chat.cmd.CoordsHelper.hasPlayer();
            int btnBg = enabled ? 0xFF2D5A8C : 0xFF2A2A2A;
            int btnFg = enabled ? 0xFFFFFFFF : 0xFF707070;
            gfx.fill(btnX1, btnY1, btnX2, btnY2, btnBg);
            gfx.drawString(font, Component.literal(btnLabel),
                    btnX1 + 5, btnY1 + (inputFieldHeight - 2 - font.lineHeight) / 2 + 1,
                    btnFg, false);
            // Stash bounds for hit-testing. Only when the button is
            // clickable (enabled + visible); set to 0 otherwise so the
            // hit-test fast-path rejects all clicks.
            if (enabled) {
                coordsBtnX1 = btnX1; coordsBtnY1 = btnY1;
                coordsBtnX2 = btnX2; coordsBtnY2 = btnY2;
            } else {
                coordsBtnX2 = 0; // signal "not interactive"
            }

            // Text input occupies the area to the LEFT of the button.
            int textRight = btnX1 - 4;
            gfx.fill(x + PAD, inputY, textRight, inputY + inputFieldHeight - 2, 0xFF1A1A1A);
            String prompt = "> " + inputText + (System.currentTimeMillis() % 1000 < 500 ? "_" : "");
            gfx.drawString(font, Component.literal(prompt), x + PAD + 3, inputY + 3, 0xFFFFFFFF, false);
        } else {
            // No input field → no button. Clear bounds so hit-test rejects.
            coordsBtnX2 = 0;
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
