package io.github.izakyl.folkways.core.front.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.izakyl.folkways.core.resident.api.Locomotions;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug.Edge;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug.EdgeKind;
import io.github.izakyl.folkways.core.travel.internal.graph.WayGraphDebug.Snapshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class ColonyDebugWaypoints {
    // Ivory: ordinary waypoints. Hot pink: residents' feet. Indigo: crossing endpoints and passage edges.
    private static final float[] WAYPOINT = {1.0F, 0.96F, 0.86F};
    private static final float[] FOOT = {1.0F, 0.28F, 0.56F};
    private static final float[] CROSSING = {0.44F, 0.32F, 1.0F};
    // Ice white: walking links. Deep teal: other locomotion. Electric lime: probation (dashed).
    // Raspberry: a retained failed/struck link; dashed while also on probation, solid when retryable.
    private static final float[] WALKING = {0.91F, 0.96F, 1.0F};
    private static final float[] OTHER = {0.0F, 0.65F, 0.61F};
    private static final float[] PROBATION = {0.77F, 1.0F, 0.0F};
    private static final float[] STRUCK = {0.94F, 0.0F, 0.39F};

    private ColonyDebugWaypoints() {
    }

    static void render(ClientLevel level, PoseStack stack, VertexConsumer lines, Snapshot graph, float partial) {
        Vec3[] positions = new Vec3[graph.points().size()];
        for (int index = 0; index < positions.length; index++) {
            var point = graph.points().get(index);
            Vec3 at = Placed.at(level, point.pos(), partial).centre();
            positions[index] = at;
            float[] rgb = switch (point.kind()) {
                case WAYPOINT -> WAYPOINT;
                case FOOT -> FOOT;
                case CROSSING -> CROSSING;
            };
            double size = switch (point.kind()) {
                case WAYPOINT -> 0.08D;
                case FOOT -> 0.13D;
                case CROSSING -> 0.18D;
            };
            LevelRenderer.renderLineBox(stack, lines,
                new AABB(at.x - size, at.y - size, at.z - size, at.x + size, at.y + size, at.z + size),
                rgb[0], rgb[1], rgb[2], 0.95F);
        }
        for (Edge edge : graph.edges()) {
            Vec3 from = positions[edge.from()];
            Vec3 to = positions[edge.to()];
            float[] rgb = switch (edge.kind()) {
                case NORMAL -> edge.sort().equals(Locomotions.WALKING) ? WALKING : OTHER;
                case PROBATION -> PROBATION;
                case STRUCK, STRUCK_PROBATION -> STRUCK;
                case CROSSING -> CROSSING;
            };
            if (edge.kind() == EdgeKind.PROBATION || edge.kind() == EdgeKind.STRUCK_PROBATION) {
                for (int part = 0; part < 8; part += 2) {
                    PathSelection.segment(stack, lines, from.lerp(to, part / 8.0D),
                        from.lerp(to, (part + 1) / 8.0D), rgb, 0.8F);
                }
            } else {
                PathSelection.segment(stack, lines, from, to, rgb, 0.7F);
            }
            // One small arrow per directed edge makes asymmetric failures and one-way crossings visible.
            Vec3 direction = to.subtract(from).normalize();
            Vec3 side = direction.cross(new Vec3(0, 1, 0));
            if (side.lengthSqr() < 0.01D) {
                side = direction.cross(new Vec3(1, 0, 0));
            }
            side = side.normalize().scale(0.13D);
            Vec3 tip = from.lerp(to, 0.7D);
            Vec3 back = tip.subtract(direction.scale(0.3D));
            PathSelection.segment(stack, lines, back.add(side), tip, rgb, 0.9F);
            PathSelection.segment(stack, lines, back.subtract(side), tip, rgb, 0.9F);
        }
    }
}
