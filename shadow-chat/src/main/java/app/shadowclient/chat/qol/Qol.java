package app.shadowclient.chat.qol;

/**
 * General client-side QoL helpers (not minigame-specific). Each
 * feature is a toggle in the static state below, default OFF, opted
 * into via slash commands typed in the chat overlay. Mirrors the
 * shape of {@link app.shadowclient.chat.minigame.Minigames} so
 * registration / dispatch / help all look identical.
 *
 * <p>Features:
 * <ul>
 *   <li><b>Recipe preview</b> — hovering an item in inventory adds a
 *       short text recipe summary to the tooltip. Backed by a curated
 *       hand-written table (1.21.11's client-side recipe data is
 *       limited to display summaries that aren't trivial to render
 *       inline, so we trade completeness for shippability).</li>
 *   <li><b>Coords HUD</b> — small XYZ + facing overlay in the top
 *       right of the screen. Less noisy than F3 when you just want
 *       to know where you are.</li>
 *   <li><b>Held-item HUD</b> — when you scroll to a new hotbar slot,
 *       the item name + count float above the hotbar for a couple
 *       seconds and fade out.</li>
 * </ul>
 */
public final class Qol {

    public static volatile boolean recipePreviewEnabled = false;
    public static volatile boolean coordsHudEnabled = false;
    public static volatile boolean heldItemHudEnabled = false;

    private Qol() {}

    public static void register() {
        RecipePreview.register();
        CoordsHud.register();
        HeldItemHud.register();
    }
}
