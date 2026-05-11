package shadowhud.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.class_1309;
import net.minecraft.class_1799;
import net.minecraft.class_4587;
import net.minecraft.class_811;
import net.minecraft.class_11659;

import shadowhud.ShadowHud;

/**
 * Lengthens or shortens the visible sword (or any held item, configurable)
 * by pushing a scale onto the MatrixStack BEFORE
 * {@code class_759.method_3233} ({@code HeldItemRenderer.renderItem}) does
 * its real work. Vanilla animation is preserved — we just nest within the
 * existing transform.
 *
 * <p>Wired to {@link ShadowHud#cfgSwordScale} (50–250 percent) and the
 * {@code SwordVisuals} module toggle. When the toggle is off or scale=100,
 * we no-op; otherwise scale is applied uniformly on all three axes.</p>
 *
 * <p>Heuristic for "is a sword": held-item registry id contains the substring
 * "_sword" or equals "trident". Falls back to "any held item" if the user
 * sets {@link ShadowHud#cfgSwordScaleAllItems} so e.g. axes can be sized too.</p>
 */
@Mixin(targets = "net.minecraft.class_759")
public abstract class SwordVisualMixin {

    static {
        System.out.println("[ShadowHud][Mixin] SwordVisualMixin class loaded");
    }

    @Inject(method = "method_3233", at = @At("HEAD"), require = 0)
    private void shadowhud$preRenderItem(class_1309 entity, class_1799 stack,
                                         class_811 transform, class_4587 matrices,
                                         class_11659 vc, int light,
                                         CallbackInfo ci) {
        try {
            if (!ShadowHud.shouldScaleHeldItem(stack)) return;
            float s = ShadowHud.heldItemScale();
            if (s == 1.0f) return;
            matrices.method_22905(s, s, s);
        } catch (Throwable ignored) {}
    }
}
