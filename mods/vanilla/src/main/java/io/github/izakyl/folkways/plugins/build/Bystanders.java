package io.github.izakyl.folkways.plugins.build;

import io.github.izakyl.folkways.core.api.terms.ClimbableBlocks;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

// The bodies a block about to be laid would close round. Vanilla never lets a player place a block into anyone, but
// the work did: a wall laid into the edge of a body that stood half over its cell, or at the head of one standing
// just under it (its own worker's, as often as not, since a worker counts as at its stance up to most of a block off
// it), left that body in the wall, and one that stayed there - a tower's top course laid round the worker's head,
// say - suffocated. Mobs are pushed the shortest clear distance out of the new block, or onto a nearby work stance.
// A block may also lift a mob up onto its top when the space above is clear.
final class Bystanders {

    private static final double STEP_UP = 1.0D;
    private static final double CLEAR = 1.0E-3D;
    // A nearby work stance is also somewhere for a bystander to step aside to.
    private static final double MOST_STANCE_STEP = 1.5D;

    private Bystanders() {
    }

    // Steps every mob out of the way of what is laid, and answers true; or moves nobody and answers false when
    // someone cannot be stepped clear.
    static boolean clear(ServerLevel level, Map<BlockPos, BlockState> laid, Collection<BlockPos> stances) {
        List<AABB> solid = new ArrayList<>();
        Map<BlockPos, VoxelShape> shapes = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> cell : laid.entrySet()) {
            BlockState state = cell.getValue();
            VoxelShape shape = state.getCollisionShape(level, cell.getKey());
            if (ClimbableBlocks.isClimbable(state) || shape.isEmpty()) {
                continue;
            }
            BlockPos at = cell.getKey();
            shapes.put(at, shape);
            solid.addAll(shape.move(at.getX(), at.getY(), at.getZ()).toAabbs());
        }
        Map<LivingEntity, Vec3> moves = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, VoxelShape> cell : shapes.entrySet()) {
            VoxelShape shape = cell.getValue();
            BlockPos at = cell.getKey();
            List<AABB> boxes = shape.move(at.getX(), at.getY(), at.getZ()).toAabbs();
            AABB bounds = shape.bounds().move(at);
            for (LivingEntity body : level.getEntitiesOfClass(LivingEntity.class, bounds,
                    body -> body.isAlive() && !body.isSpectator())) {
                Vec3 from = moves.getOrDefault(body, body.position());
                AABB box = body.getBoundingBox().move(from.subtract(body.position()));
                if (boxes.stream().noneMatch(box::intersects)) {
                    continue;
                }
                if (!(body instanceof Mob)) {
                    return false;
                }
                Vec3 to = stepAside(level, body, box, bounds, solid);
                if (to == null) {
                    to = intoStance(level, body, box, stances, laid.keySet(), solid);
                }
                if (to == null) {
                    return false;
                }
                moves.put(body, from.add(to));
            }
        }
        moves.forEach((body, to) -> body.setPos(to.x, to.y, to.z));
        return true;
    }

    // Clear the block by the least horizontal displacement, including a body standing fully inside its cell.
    private static Vec3 stepAside(ServerLevel level, LivingEntity body, AABB box, AABB block, List<AABB> solid) {
        Vec3[] steps = {
            new Vec3(block.minX - box.maxX - CLEAR, 0.0D, 0.0D),
            new Vec3(block.maxX - box.minX + CLEAR, 0.0D, 0.0D),
            new Vec3(0.0D, 0.0D, block.minZ - box.maxZ - CLEAR),
            new Vec3(0.0D, 0.0D, block.maxZ - box.minZ + CLEAR),
        };
        Vec3 best = null;
        for (Vec3 step : steps) {
            double length = step.length();
            if (best != null && length >= best.length()) {
                continue;
            }
            if (free(level, body, box, step, solid)) {
                best = step;
            }
        }
        if (best == null && block.maxY - box.minY <= STEP_UP) {
            Vec3 up = new Vec3(0.0D, block.maxY - box.minY + CLEAR, 0.0D);
            if (free(level, body, box, up, solid)) {
                return up;
            }
        }
        return best;
    }

    private static Vec3 intoStance(ServerLevel level, LivingEntity body, AABB box, Collection<BlockPos> stances,
            Collection<BlockPos> laid, List<AABB> solid) {
        Vec3 feet = box.getBottomCenter();
        Vec3 best = null;
        for (BlockPos stance : stances) {
            if (laid.contains(stance) || laid.contains(stance.above())) {
                continue;
            }
            double y = Mth.floor(feet.y + 1.0E-3D) == stance.getY() ? feet.y : stance.getY();
            Vec3 step = new Vec3(stance.getX() + 0.5D, y, stance.getZ() + 0.5D).subtract(feet);
            if (Math.abs(step.y) > 1.0D || step.horizontalDistance() > MOST_STANCE_STEP
                    || (best != null && step.lengthSqr() >= best.lengthSqr())) {
                continue;
            }
            if (free(level, body, box, step, solid)) {
                best = step;
            }
        }
        return best;
    }

    private static boolean free(ServerLevel level, LivingEntity body, AABB box, Vec3 step, List<AABB> solid) {
        AABB moved = box.move(step);
        return level.noCollision(body, box.expandTowards(step)) && solid.stream().noneMatch(moved::intersects);
    }
}
