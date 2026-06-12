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
 *       we open a {@link DiscordChatScreen} which paints on top of the
 *       HUD and grabs keyboard input for the message field.</li>
 * </ul>
 *
 * <p>The visibility flag in {@link InputState} controls whether the
 * HUD mode renders at all. The hotkey toggles that flag; opening the
 * focused screen is a separate, transient action that closes itself
 * via Esc (Minecraft's default Screen behavior) without affecting
 * the persistent visibility flag.
 */
public final class ChatOverlay {

    /** Max log lines shown in the passive toast. */
    private static final int TOAST_LINES = 4;

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
     * Bounding box of the coords button. The fullscreen DiscordChatScreen
     * draws its own coords button now; this is kept only because the
     * HUD-mode overlay still renders {@code showInputField=false} via
     * {@link #render} (no button shown then).
     * x2/y2 = 0 means the button isn't currently rendered.
     */
    private int coordsBtnX1 = 0, coordsBtnY1 = 0, coordsBtnX2 = 0, coordsBtnY2 = 0;

    public ChatOverlay(InputState state, ModConfig config) {
        this.state = state;
        this.config = config;
    }

    /**
     * Hit-test the coords button — return true iff the given mouse
     * coordinates (in GUI-scaled pixels) fall inside the button's
     * current bounds. Retained for the HUD-mode passive overlay; the
     * fullscreen chat screen does its own hit testing.
     */
    public boolean isCoordsButtonHit(double mouseX, double mouseY) {
        if (coordsBtnX2 == 0) return false;
        return mouseX >= coordsBtnX1 && mouseX <= coordsBtnX2
            && mouseY >= coordsBtnY1 && mouseY <= coordsBtnY2;
    }

    public void register() {
        HudRenderCallback.EVENT.register(this::onRender);
    }

    /**
     * How long the passive HUD panel stays up after the last new line
     * on the active channel. Past this it disappears entirely — the
     * old behavior (visible for the whole session once the user opened
     * chat once) parked a 40%-width translucent panel over the bottom-
     * left of the game forever, which is exactly the screen-blockage
     * players hate. Press ; any time for the full chat screen.
     */
    private static final long ACTIVITY_WINDOW_MS = 8_000;

    private void onRender(GuiGraphics gfx, DeltaTracker dt) {
        // Suppress rendering when any Screen is open — the fullscreen
        // DiscordChatScreen paints its own message log and the HUD
        // overlay would just stack on top with the same data. We also
        // don't want our overlay painting over the pause menu, an
        // inventory, etc.
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return;

        // Activity-gated: show only while something is actually
        // happening — a fresh line on the active channel, or live voice
        // (so you can glance at who's talking). Otherwise stay out of
        // the player's view entirely.
        boolean recentActivity = System.currentTimeMillis()
                - state.lastActiveAppendAtMs() < ACTIVITY_WINDOW_MS;
        boolean speaking = false;
        VoiceController vc = ShadowChatClient.get().voice();
        if (vc != null && !vc.playback().currentSpeakers().isEmpty()) {
            speaking = true;
        }
        if (!recentActivity && !speaking) return;

        render(gfx, mc, /* showInputField= */ false, "");
    }

    /**
     * Internal render. Called by the HUD callback (no input field).
     * The fullscreen DiscordChatScreen no longer reuses this path.
     */
    /**
     * Compact toast renderer for the passive (activity-gated) panel.
     *
     * <p>Pre-0.1.43 this painted the FULL overlay — channel chips,
     * status banner, presence summary, a half-screen message log, and
     * the PTT hint — every time it showed. With activity gating that
     * meant one incoming message flashed a 40%-width wall over the
     * game. Passive mode now draws just the tail of the conversation
     * (last {@link #TOAST_LINES} lines) plus the speaking indicator
     * when voice is live; everything else lives in the fullscreen
     * chat screen (;).
     *
     * <p>{@code showInputField}/{@code inputText} are legacy params —
     * the fullscreen screen stopped reusing this path long ago; the
     * HUD callback always passes {@code false, ""}.
     */
    void render(GuiGraphics gfx, Minecraft mc, boolean showInputField, String inputText) {
        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();
        Font font = mc.font;

        String active = state.activeChannel();
        List<InputState.DisplayLine> all = state.linesFor(active);
        int show = Math.min(TOAST_LINES, all.size());

        // Speaking indicator — may be the ONLY content (voice live,
        // no recent text), in which case the toast is one line tall.
        String speakingLine = null;
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
                    if (shown >= 3 && speakers.size() > 3) {
                        sb.append(" +").append(speakers.size() - shown).append(" more");
                        break;
                    }
                }
                speakingLine = sb.toString();
            }
        }
        if (show == 0 && speakingLine == null) return;

        int panelWidth = Math.max(220, Math.min(340, screenWidth / 3));
        int lineH = font.lineHeight + LINE_GAP;
        int contentLines = show + (speakingLine != null ? 1 : 0);
        int panelHeight = PAD * 2 + contentLines * lineH - LINE_GAP;

        // Bottom-left, lifted clear of the hotbar/health rows.
        int x = 4;
        int y = screenHeight - panelHeight - 44;

        gfx.fill(x, y, x + panelWidth, y + panelHeight, BG_COLOR);

        int textMaxWidth = panelWidth - PAD * 2;
        int drawY = y + PAD;
        for (int i = all.size() - show; i < all.size(); i++) {
            InputState.DisplayLine line = all.get(i);
            String rendered = renderLine(line, font, textMaxWidth);
            int color = line.error() ? 0xFFFF6060
                    : line.system() ? 0xFFAAAAAA
                    : 0xFFFFFFFF;
            gfx.drawString(font, Component.literal(rendered), x + PAD, drawY, color, false);
            drawY += lineH;
        }
        if (speakingLine != null) {
            gfx.drawString(font, Component.literal(speakingLine),
                    x + PAD, drawY, SPEAKER_COLOR, false);
        }
        // Passive mode has no coords button — clear so hit-tests reject.
        coordsBtnX2 = 0;
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
