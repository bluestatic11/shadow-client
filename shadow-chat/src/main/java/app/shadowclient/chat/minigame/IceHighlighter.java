package app.shadowclient.chat.minigame;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShapeRenderer;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Color-coded outline overlay on ice blocks within ~12 blocks of the
 * player. Helps ice-boat racers pick the fastest line through a track
 * that mixes ice types, and helps parkour players spot single "trap"
 * ice blocks vs. deliberate ice runs.
 *
 * <ul>
 *   <li>Blue ice (slipperiness 0.989, top boat speed) → §agreen§r outline</li>
 *   <li>Packed ice (slipperiness 0.98)                → §eyellow§r outline</li>
 *   <li>Regular ice (slipperiness 0.98)               → §bcyan§r outline</li>
 * </ul>
 *
 * <p>Toggle: {@code /icehighlight on|off|status}.
 */
public final class IceHighlighter {

    private static final class IcePos {
        final BlockPos pos;
        final int color;
        IcePos(BlockPos pos, int color) { this.pos = pos; this.color = color; }
    }

    private static final List<IcePos> positions = new CopyOnWriteArrayList<>();
    private static int scanCooldown = 0;

    private static final int COLOR_BLUE_ICE    = 0xFF55FF55; // green
    private static final int COLOR_PACKED_ICE  = 0xFFFFFF55; // yellow
    private static final int COLOR_REGULAR_ICE = 0xFF55FFFF; // cyan
    private static final float LINE_WIDTH = 1.5f;

    private IceHighlighter() {}

    static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!Minigames.iceHighlightEnabled
                    || client.player == null
                    || client.level == null) {
                if (!positions.isEmpty()) positions.clear();
                return;
            }
            if (--scanCooldown > 0) return;
            scanCooldown = 20; // re-scan once per second

            BlockPos pp = client.player.blockPosition();
            int hRange = 12;
            int vRange = 3;
            List<IcePos> found = new ArrayList<>();
            for (int dx = -hRange; dx <= hRange; dx++) {
                for (int dz = -hRange; dz <= hRange; dz++) {
                    for (int dy = -vRange; dy <= vRange; dy++) {
                        BlockPos bp = pp.offset(dx, dy, dz);
                        BlockState st = client.level.getBlockState(bp);
                        Block b = st.getBlock();
                        int color;
                        if (b == Blocks.BLUE_ICE)        color = COLOR_BLUE_ICE;
                        else if (b == Blocks.PACKED_ICE) color = COLOR_PACKED_ICE;
                        else if (b == Blocks.ICE)        color = COLOR_REGULAR_ICE;
                        else continue;
                        found.add(new IcePos(bp, color));
                    }
                }
            }
            positions.clear();
            positions.addAll(found);
        });

        WorldRenderEvents.BEFORE_TRANSLUCENT.register(context -> {
            if (!Minigames.iceHighlightEnabled) return;
            if (positions.isEmpty()) return;
            MultiBufferSource consumers = context.consumers();
            if (consumers == null) return;
            PoseStack matrices = context.matrices();
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().position();
            VertexConsumer buffer = consumers.getBuffer(RenderTypes.lines());
            for (IcePos p : positions) {
                ShapeRenderer.renderShape(
                        matrices, buffer, Shapes.block(),
                        p.pos.getX() - cam.x,
                        p.pos.getY() - cam.y,
                        p.pos.getZ() - cam.z,
                        p.color, LINE_WIDTH);
            }
        });
    }
}
