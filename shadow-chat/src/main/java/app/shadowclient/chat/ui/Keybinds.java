package app.shadowclient.chat.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Hotkeys owned by Shadow Chat.
 *
 * <p>Default binds:
 * <ul>
 *   <li>{@code ;} (semicolon) — toggle overlay open/closed. Chosen
 *       because it's near the slash key for "type a chat command"
 *       muscle memory and isn't used by vanilla Minecraft.</li>
 *   <li>{@code V} — push-to-talk. Mirrors Discord's default so the
 *       muscle memory is already there for most users.</li>
 * </ul>
 */
public final class Keybinds {

    /** Toggles the chat overlay open/closed and grabs keyboard focus. */
    public static KeyMapping TOGGLE_CHAT;
    /** Held to transmit voice. */
    public static KeyMapping PUSH_TO_TALK;

    private Keybinds() {}

    public static void register() {
        TOGGLE_CHAT = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "Shadow Chat: Toggle Overlay",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_SEMICOLON,
                KeyMapping.Category.MISC));
        PUSH_TO_TALK = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "Shadow Chat: Push to Talk",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                KeyMapping.Category.MISC));
    }
}
