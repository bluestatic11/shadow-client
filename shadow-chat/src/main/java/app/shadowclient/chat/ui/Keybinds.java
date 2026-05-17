package app.shadowclient.chat.ui;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Hotkeys owned by Shadow Chat. Currently just the overlay toggle.
 *
 * <p>Default bind: semicolon ({@code ;}) — chosen because it's near
 * the slash key for "type a chat command" muscle memory and isn't
 * used by vanilla Minecraft.
 */
public final class Keybinds {

    /** Toggles the chat overlay open/closed and grabs keyboard focus. */
    public static KeyMapping TOGGLE_CHAT;

    private Keybinds() {}

    public static void register() {
        TOGGLE_CHAT = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "Shadow Chat: Toggle Overlay",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_SEMICOLON,
                KeyMapping.Category.MISC));
    }
}
