package io.github.izakyl.folkways.core.api.terms;

import io.github.izakyl.folkways.core.api.work.Stances;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class Reach {

    public static final double PLAYER_BLOCK_REACH = 4.5;

    public static final double EYE_HEIGHT = 1.62;

    private static final int SPAN = Mth.ceil(PLAYER_BLOCK_REACH);

    private static final double IN_THE_CELL = 0.9D;

    private static final double CELL_HEIGHT = 1.0D;

    private Reach() {
    }

    public static boolean standingIn(LivingEntity body, Stances stances) {
        return stanceHolding(body, stances).isPresent();
    }

    public static Optional<BlockPos> stanceHolding(LivingEntity body, Stances stances) {
        if (!(stances instanceof Stances.Cells(Set<WorldPos> footings))) {
            return Optional.of(body.blockPosition());
        }
        for (WorldPos footing : footings) {
            Optional<Vec3> feet = WorldSpaces.relative(body.level(), footing.realm(), body.position());
            if (feet.isPresent() && standingAt(feet.get(), footing.cell())) {
                return Optional.of(footing.cell());
            }
        }
        return Optional.empty();
    }

    public static Optional<BlockPos> stanceHolding(Vec3 feet, Set<BlockPos> stances) {
        for (BlockPos stance : stances) {
            if (standingAt(feet, stance)) {
                return Optional.of(stance);
            }
        }
        return Optional.empty();
    }

    public static boolean standingAt(Vec3 feet, BlockPos stance) {
        double dx = feet.x - (stance.getX() + 0.5D);
        double dz = feet.z - (stance.getZ() + 0.5D);
        return dx * dx + dz * dz <= IN_THE_CELL * IN_THE_CELL
            && Math.abs(feet.y - stance.getY()) <= CELL_HEIGHT;
    }

    public static Set<BlockPos> footingsAround(ServerLevel level, BlockPos target, int span, int rise) {
        if (!level.isLoaded(target)) {
            return Set.of();
        }
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (int dx = -span; dx <= span; dx++) {
            for (int dy = -rise; dy <= rise; dy++) {
                for (int dz = -span; dz <= span; dz++) {
                    BlockPos feet = target.offset(dx, dy, dz);
                    if (level.isLoaded(feet) && Footing.withFeetAt(level, feet)) {
                        cells.add(feet.immutable());
                    }
                }
            }
        }
        return Set.copyOf(cells);
    }

    public static Set<BlockPos> workableCells(ServerLevel level, BlockPos target) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (BlockPos feet : footingsAround(level, target, SPAN, SPAN)) {
            if (withinReach(feet, target) && inSight(level, feet, target)) {
                cells.add(feet);
            }
        }
        return Set.copyOf(cells);
    }

    public static boolean withinReach(BlockPos feet, BlockPos target) {
        return Vec3.atBottomCenterOf(feet).distanceToSqr(Vec3.atCenterOf(target))
            <= PLAYER_BLOCK_REACH * PLAYER_BLOCK_REACH;
    }

    public static boolean inSight(ServerLevel level, BlockPos feet, BlockPos target) {
        if (touching(feet, target)) {
            return true;
        }
        BlockHitResult hit = level.clip(new ClipContext(eyeAt(feet), Vec3.atCenterOf(target),
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    private static Vec3 eyeAt(BlockPos feet) {
        return Vec3.atBottomCenterOf(feet).add(0, EYE_HEIGHT, 0);
    }

    private static boolean touching(BlockPos feet, BlockPos target) {
        return Math.abs(feet.getX() - target.getX()) <= 1
            && Math.abs(feet.getY() - target.getY()) <= 1
            && Math.abs(feet.getZ() - target.getZ()) <= 1;
    }
}
