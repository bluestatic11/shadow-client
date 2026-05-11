package shadowhud.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import shadowhud.ShadowHud;

/**
 * Cursor-lock interceptor for MC's {@code class_312} (Mouse).
 *
 * <p>MC keeps the cursor in {@code GLFW_CURSOR_DISABLED} mode whenever no
 * {@code Screen} is currently open. Our HUD-overlay menu doesn't register
 * as a Screen — it draws via {@code HudRenderCallback} — so MC repeatedly
 * tries to lock the cursor, and clicks teleport to screen center for the
 * mouse-look pipeline. Three previous fixes from the Java side (calling
 * {@code unlockCursor()}, {@code glfwSetInputMode(NORMAL)}, writing
 * {@code cursorLocked = false}) all run during the HUD render callback —
 * which is too late: MC's per-frame logic re-locks before our hook runs.</p>
 *
 * <p>This Mixin makes {@code lockCursor()} a no-op while our menu is open,
 * so MC literally cannot put the cursor back in raw mode mid-frame. When
 * the menu closes, {@code menuOpen} is false and the inject does nothing,
 * so vanilla mouse-look behaviour returns immediately.</p>
 */
@Mixin(targets = "net.minecraft.class_312")
public abstract class MouseMixin {

    /** Class-load tracer — confirms via stdout that Mixin loaded our class.
     *  If you don't see this on launch, the mixin config didn't bind. */
    static {
        System.out.println("[ShadowHud][Mixin] MouseMixin class loaded");
    }

    /** Fires once when MC first tries to lock and we cancel — proves the
     *  inject point is reachable at runtime. */
    private static boolean shadowhud$lockCancelLogged;

    /**
     * Cancel {@code Mouse.lockCursor()} (intermediary {@code method_1612})
     * while our menu is open. Verified against yarn 1.21.11+build.1:
     *   {@code METHOD gfk ()V i method_1612 lockCursor}
     */
    @Inject(method = "method_1612", at = @At("HEAD"), cancellable = true, require = 0)
    private void shadowhud$skipLockWhileMenuOpen(CallbackInfo ci) {
        if (ShadowHud.menuOpen) {
            ci.cancel();
            if (!shadowhud$lockCancelLogged) {
                shadowhud$lockCancelLogged = true;
                System.out.println(
                    "[ShadowHud][Mixin] cancelled MC lockCursor — menu open");
            }
        }
    }
}
