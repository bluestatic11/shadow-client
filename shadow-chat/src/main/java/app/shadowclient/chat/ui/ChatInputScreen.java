package app.shadowclient.chat.ui;

import app.shadowclient.chat.ShadowChatClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Focused screen variant of the chat overlay. Opened when the player
 * presses the toggle hotkey; closed by Esc (or by submitting a
 * message).
 *
 * <p>We deliberately don't use Minecraft's {@code EditBox} widget —
 * the overlay does its own rendering for the input line so it looks
 * cohesive with the rest of the panel. The buffer is just a
 * {@link StringBuilder} mutated by {@link #keyPressed} / {@link #charTyped}.
 *
 * <p>Pausing: we return {@link #isPauseScreen()} as {@code false} so
 * the game continues running while the player types — same UX as
 * vanilla chat.
 *
 * <h2>1.21.11 API note</h2>
 * In 1.21.11, {@code Screen.keyPressed} takes a {@link KeyEvent}
 * record and {@code Screen.charTyped} takes a {@link CharacterEvent}
 * record — both replaced the old {@code (int,int,int)} /
 * {@code (char,int)} signatures from earlier versions.
 */
public final class ChatInputScreen extends Screen {

    private final ChatOverlay overlay;
    private final StringBuilder buffer = new StringBuilder();

    public ChatInputScreen(ChatOverlay overlay) {
        super(Component.literal("Shadow Chat"));
        this.overlay = overlay;
    }

    @Override
    public boolean isPauseScreen() {
        return false; // World keeps ticking while we type.
    }

    @Override
    public void render(GuiGraphics gfx, int mouseX, int mouseY, float partialTick) {
        // Don't call super.render — we want a transparent background
        // so the game world remains visible behind us.
        if (this.minecraft == null) return;
        overlay.render(gfx, this.minecraft, /* showInputField= */ true, buffer.toString());
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int keyCode = event.key();
        int modifiers = event.modifiers();
        // Enter → submit
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            String text = buffer.toString().trim();
            buffer.setLength(0);
            if (!text.isEmpty()) {
                ShadowChatClient.get().submitInput(text);
            }
            this.onClose();
            return true;
        }
        // Backspace
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            if (buffer.length() > 0) buffer.setLength(buffer.length() - 1);
            return true;
        }
        // Esc → close without submitting (Screen default also handles this)
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            buffer.setLength(0);
            this.onClose();
            return true;
        }
        // Paste — Ctrl/Cmd+V
        if (keyCode == GLFW.GLFW_KEY_V && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0) {
            if (this.minecraft != null) {
                String clip = this.minecraft.keyboardHandler.getClipboard();
                if (clip != null) {
                    // Strip newlines so a multi-line paste doesn't break the message.
                    appendSafe(clip.replace('\n', ' ').replace('\r', ' '));
                }
            }
            return true;
        }
        return super.keyPressed(event);
    }

    /**
     * 1.21.11 signature: {@code mouseClicked(MouseButtonEvent, boolean)}.
     * Detects clicks on the overlay's Coords button — when hit, pastes
     * the player's current X/Y/Z into the input buffer at the cursor
     * (cursor = end of buffer; no cursor-position tracking yet).
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && overlay.isCoordsButtonHit(event.x(), event.y())) {
            var coords = app.shadowclient.chat.cmd.CoordsHelper.currentCoords();
            if (coords.isPresent()) {
                // Add a separator before the coords if the buffer isn't empty.
                if (buffer.length() > 0
                        && buffer.charAt(buffer.length() - 1) != ' ') {
                    appendSafe(" ");
                }
                appendSafe(coords.get());
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        int cp = event.codepoint();
        // Filter to printable characters; Minecraft does the same for
        // its native chat to avoid e.g. NUL bytes corrupting payloads.
        if (cp >= 32 && cp != 127) {
            appendSafe(new String(Character.toChars(cp)));
            return true;
        }
        return super.charTyped(event);
    }

    /** Hard cap on input length — server trims to 500 anyway, give a little headroom for slash commands. */
    private static final int MAX_INPUT = 512;

    private void appendSafe(String s) {
        int room = MAX_INPUT - buffer.length();
        if (room <= 0) return;
        if (s.length() > room) s = s.substring(0, room);
        buffer.append(s);
    }
}
