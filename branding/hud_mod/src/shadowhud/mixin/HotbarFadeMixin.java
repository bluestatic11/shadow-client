package shadowhud.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.class_332;
import net.minecraft.class_9779;

import shadowhud.ShadowHud;

/**
 * Lunar-style hotbar auto-hide. Cancels the vanilla hotbar render call
 * (intermediary {@code class_329.method_1759}, named {@code renderHotbar})
 * whenever {@link ShadowHud#shouldHideHotbar()} returns true — i.e. the
 * HotbarFade module is enabled and the user hasn't switched slots in the
 * last 3 seconds.
 *
 * <p>The previous attempt used {@code Object} params hoping Mixin would
 * accept them via reference-type erasure. It does NOT — Mixin verifies
 * inject signatures by exact descriptor match. The 1.21.11 launch log
 * told us the exact target signature:</p>
 *
 * <pre>Expected (Lnet/minecraft/class_332;Lnet/minecraft/class_9779;...)V</pre>
 *
 * <p>So we type the params as {@code class_332} (DrawContext) and
 * {@code class_9779} (RenderTickCounter). Both are available on the
 * compile classpath via the bundled MC jar.</p>
 */
@Mixin(targets = "net.minecraft.class_329")
public abstract class HotbarFadeMixin {

    static {
        System.out.println("[ShadowHud][Mixin] HotbarFadeMixin class loaded");
    }

    private static int shadowhud$cancelLogCount;

    @Inject(method = "method_1759", at = @At("HEAD"), cancellable = true, require = 0)
    private void shadowhud$cancelHotbar(class_332 dc, class_9779 tc, CallbackInfo ci) {
        try {
            if (ShadowHud.shouldHideHotbar()) {
                ci.cancel();
                if (shadowhud$cancelLogCount++ < 1) {
                    System.out.println("[ShadowHud][Mixin] hotbar hidden via method_1759");
                }
            }
        } catch (Throwable ignored) {}
    }
}
