package shadowhud.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.class_1297;
import net.minecraft.class_4587;
import net.minecraft.class_4597;

import shadowhud.ShadowHud;

/**
 * Per-entity cosmetic render hook. Injects at the TAIL of
 * {@code class_898.method_3954} (EntityRenderDispatcher.render) so we draw
 * 3D mesh cosmetics in the same MatrixStack + VertexConsumerProvider that
 * vanilla just used to render the entity.
 *
 * <p>Earlier iteration used {@code Object} parameter types hoping Mixin
 * would accept them via reference erasure. It does NOT — the HotbarFade
 * launch error confirmed Mixin requires exact descriptor match. Now we
 * use real intermediary types via compile-time stubs (the stubs are not
 * shipped in the jar; fabric loader binds the real MC classes at runtime
 * by intermediary name).</p>
 *
 * <p>Signature reference (1.21.x):
 * {@code render(class_1297, double, double, double, float, float,
 * class_4587, class_4597, int)}.</p>
 *
 * <p>The hook is wrapped in try/catch so any failure in cosmetic
 * rendering can never crash vanilla entity rendering — it just throttles
 * a log line.</p>
 */
@Mixin(targets = "net.minecraft.class_898")
public abstract class EntityRenderDispatcherCosmeticMixin {

    static {
        System.out.println("[ShadowHud][Mixin] EntityRenderDispatcherCosmeticMixin class loaded");
    }

    private static int  shadowhud$injectFireCount;
    private static long shadowhud$lastErrLog;

    @Inject(method = "method_3954", at = @At("TAIL"), require = 0)
    private void shadowhud$cosmeticTail(
        class_1297 entity,
        double x, double y, double z,
        float yaw, float tickDelta,
        class_4587 matrices,
        class_4597 vertexConsumers,
        int light,
        CallbackInfo ci
    ) {
        if (shadowhud$injectFireCount++ == 0) {
            System.out.println("[ShadowHud][Mixin] EntityRenderDispatcher.method_3954 inject FIRED — entity="
                + (entity != null ? entity.getClass().getSimpleName() : "null"));
        }

        try {
            ShadowHud.cosmeticMeshHook(entity, x, y, z, yaw, tickDelta, matrices, vertexConsumers, light);
        } catch (Throwable t) {
            // Throttle to one log per second so we don't spam at 60 fps
            long now = System.currentTimeMillis();
            if (now - shadowhud$lastErrLog > 1000L) {
                shadowhud$lastErrLog = now;
                System.out.println("[ShadowHud][Mixin] cosmetic hook threw: " + t);
            }
        }
    }
}
