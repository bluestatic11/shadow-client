package shadowhud.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Minimum-risk probe — injects nothing meaningful into
 * {@code class_310} (MinecraftClient), just logs ONCE that the Mixin
 * pipeline binds successfully on this MC build. No rendering, no MC type
 * imports, no entity classes touched. If this load crashes, the issue
 * is broader than the entity-render mixin attempted previously.
 *
 * <p>Once this is proven stable in the launch log, the next iteration
 * will move to a render-adjacent target with confidence the basic mixin
 * processing isn't itself the problem.</p>
 */
@Mixin(targets = "net.minecraft.class_310")
public abstract class MinecraftClientProbeMixin {

    /** Class-load tracer — proves the Mixin processor accepted this class
     *  without erroring out at startup. If you DON'T see this line in
     *  launch.log, mixin transformation failed at load time. */
    static {
        System.out.println("[ShadowHud][Mixin] MinecraftClientProbeMixin class loaded");
    }

    /** One-shot diagnostic — confirms the inject point is reachable at
     *  runtime. Tries common tick-method intermediary names with
     *  {@code require = 0} so each line silently no-ops if the mapping
     *  doesn't match this MC build. Whichever matches will fire. */
    private static boolean shadowhud$tickProbeLogged;

    @Inject(method = "method_1574", at = @At("HEAD"), require = 0)
    private void shadowhud$tickProbe1574(CallbackInfo ci) { logFirstFire("method_1574"); }

    @Inject(method = "tick",         at = @At("HEAD"), require = 0)
    private void shadowhud$tickProbeNamed(CallbackInfo ci) { logFirstFire("tick"); }

    private static void logFirstFire(String which) {
        if (shadowhud$tickProbeLogged) return;
        shadowhud$tickProbeLogged = true;
        System.out.println("[ShadowHud][Mixin] tick probe FIRED via " + which
            + " — mixin pipeline is healthy on this MC build");
    }
}
