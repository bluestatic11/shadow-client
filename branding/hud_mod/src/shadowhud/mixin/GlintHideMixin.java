package shadowhud.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import shadowhud.ShadowHud;

/**
 * Hides the enchant glint on items when GlintTune is enabled and strength=0.
 * Targets {@code ItemStack.method_7958} (hasGlint) and short-circuits to
 * {@code false} when the user has configured glint to be invisible.
 *
 * <p>Color tinting and partial-strength rendering require deeper pipeline
 * work (shader hooks or runtime resource-pack injection) — this mixin only
 * gives the user an "off" switch for now. The config UI exposes the full
 * range so the user can see the controls; partial strength is a no-op for
 * the moment.</p>
 */
@Mixin(targets = "net.minecraft.class_1799")
public abstract class GlintHideMixin {

    static {
        System.out.println("[ShadowHud][Mixin] GlintHideMixin class loaded");
    }

    @Inject(method = "method_7958", at = @At("HEAD"), cancellable = true, require = 0)
    private void shadowhud$hideGlint(CallbackInfoReturnable<Boolean> cir) {
        try {
            if (ShadowHud.shouldHideGlint()) {
                cir.setReturnValue(false);
            }
        } catch (Throwable ignored) {}
    }
}
