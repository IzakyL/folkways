package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.resident.Searches;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Keepouts;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Finds a nearby breathable place when the body's own air supply is actually falling. */
public final class Breathing {
    private static final int AIR_PERCENT = 80;
    private static final int STILL_AIR_PERCENT = 95;
    private static final int SEARCH_RADIUS = 12;
    private static final int SEARCH_LIMIT = 2048;
    private static final int RETRY_TICKS = 10;
    private static final int NO_EXIT_RETRY_TICKS = 40;
    // Emergencies share a bounded number of searches; a body that waits retries on the next tick.
    private static final int SEARCHES_PER_TICK = 2;

    private static long searchTick = Long.MIN_VALUE;
    private static int searchesThisTick;

    private int previousAir = Integer.MAX_VALUE;
    private boolean escaping;
    private long nextSearch;
    private long retryAfter;
    private BlockPos next;

    boolean escaping() {
        return escaping;
    }

    boolean needed(Mob body) {
        int air = body.getAirSupply();
        if (!body.isAlive() || body.isRemoved()) {
            escaping = false;
        } else if (escaping) {
            if (air >= body.getMaxAirSupply() || body.isUnderWater() && canBreatheHere(body)) {
                escaping = false;
                next = null;
                body.getNavigation().stop();
            }
        } else if (body.isUnderWater() && air < body.getMaxAirSupply()
                * (body.getNavigation().isDone() ? STILL_AIR_PERCENT : AIR_PERCENT) / 100
                && previousAir != Integer.MAX_VALUE && air < previousAir
                && body.level().getGameTime() >= retryAfter) {
            escaping = true;
            nextSearch = 0;
        }
        previousAir = air;
        return escaping;
    }

    void tick(Mob body) {
        body.getNavigation().stop();
        if (!body.isUnderWater()) {
            return;
        }
        long now = body.level().getGameTime();
        if (now >= nextSearch && takeSearch(now)) {
            nextSearch = now + RETRY_TICKS;
            next = nearestAir(body);
            if (next == null) {
                escaping = false;
                retryAfter = now + NO_EXIT_RETRY_TICKS;
                return;
            }
        }
        if (next != null) {
            openDoors(body, next);
            if (next.getX() == body.getBlockX() && next.getZ() == body.getBlockZ()
                    && next.getY() > body.getY()) {
                double dx = next.getX() + 0.5D - body.getX();
                double dz = next.getZ() + 0.5D - body.getZ();
                body.setDeltaMovement(body.getDeltaMovement().add(
                    Mth.clamp(dx * 0.08D, -0.04D, 0.04D), 0.0D, Mth.clamp(dz * 0.08D, -0.04D, 0.04D)));
                body.getJumpControl().jump();
                body.getMoveControl().setWantedPosition(body.getX(), body.getY(), body.getZ(), 0.0D);
            } else {
                body.getMoveControl().setWantedPosition(next.getX() + 0.5D, next.getY(), next.getZ() + 0.5D, 1.2D);
            }
        }
    }

    private static boolean takeSearch(long now) {
        if (searchTick != now) {
            searchTick = now;
            searchesThisTick = 0;
        }
        if (searchesThisTick >= SEARCHES_PER_TICK) {
            return false;
        }
        searchesThisTick++;
        return true;
    }

    /** A place to remain must let the body breathe, using the same fluid and effect rules as living entities. */
    public static boolean canStay(Mob body, BlockPos feet) {
        return canStay(body, body.level(), feet);
    }

    static boolean canStay(Mob body, BlockGetter blocks, BlockPos feet) {
        BlockPos eyes = BlockPos.containing(feet.getX() + 0.5D,
            feet.getY() + body.getEyeHeight(), feet.getZ() + 0.5D);
        var fluid = blocks.getFluidState(eyes);
        return fluid.isEmpty() || blocks.getBlockState(eyes).is(Blocks.BUBBLE_COLUMN)
            || MobEffectUtil.hasWaterBreathing(body) || !body.canDrownInFluidType(fluid.getFluidType());
    }

    private static boolean canBreatheHere(Mob body) {
        var fluid = body.getEyeInFluidType();
        return fluid.isAir() || MobEffectUtil.hasWaterBreathing(body) || !body.canDrownInFluidType(fluid)
            || body.level().getBlockState(BlockPos.containing(body.getX(), body.getEyeY(), body.getZ()))
                .is(Blocks.BUBBLE_COLUMN);
    }

    private static BlockPos nearestAir(Mob body) {
        Searches.starting(body.level());
        BlockPos start = body.blockPosition();
        ArrayDeque<BlockPos> open = new ArrayDeque<>();
        Map<BlockPos, BlockPos> previous = new HashMap<>();
        Keepouts.Keepout blocked = Keepouts.blockingEscapeFrom(body.level(), body.level(), start);
        open.add(start);
        previous.put(start, start);
        while (!open.isEmpty() && previous.size() <= SEARCH_LIMIT) {
            BlockPos at = open.removeFirst();
            Searches.expanded();
            if (!at.equals(start) && breathable(body, at)) {
                BlockPos step = at;
                while (!previous.get(step).equals(start)) {
                    step = previous.get(step);
                }
                return step;
            }
            for (Direction direction : Direction.values()) {
                BlockPos to = at.relative(direction);
                if (previous.containsKey(to) || Math.abs(to.getX() - start.getX()) > SEARCH_RADIUS
                        || Math.abs(to.getZ() - start.getZ()) > SEARCH_RADIUS
                        || Math.abs(to.getY() - start.getY()) > SEARCH_RADIUS || !body.level().isLoaded(to)
                        || blocked.forbids(body.level(), to.getX(), to.getY(), to.getZ()) || !open(body, to)) {
                    continue;
                }
                if (direction == Direction.UP && body.level().getFluidState(at).isEmpty()) {
                    continue;
                }
                previous.put(to, at);
                open.addLast(to);
            }
        }
        return null;
    }

    private static boolean open(Mob body, BlockPos feet) {
        AABB box = body.getDimensions(body.getPose()).makeBoundingBox(Vec3.atBottomCenterOf(feet));
        if (!body.level().getWorldBorder().isWithinBounds(feet)
                || !body.level().getEntityCollisions(body, box).isEmpty()) {
            return false;
        }
        CollisionContext collision = CollisionContext.of(body);
        for (BlockPos cell : BlockPos.betweenClosed(Mth.floor(box.minX), Mth.floor(box.minY), Mth.floor(box.minZ),
                Mth.floor(box.maxX - 1.0E-7D), Mth.floor(box.maxY - 1.0E-7D), Mth.floor(box.maxZ - 1.0E-7D))) {
            BlockState state = body.level().getBlockState(cell);
            if (openable(state)) {
                state = state.setValue(DoorBlock.OPEN, true);
            }
            for (AABB part : state.getCollisionShape(body.level(), cell, collision).toAabbs()) {
                if (part.move(cell).intersects(box)) {
                    return false;
                }
            }
        }
        return !body.level().getFluidState(feet).isEmpty() || !body.level().getFluidState(feet.below()).isEmpty()
            || Footing.withFeetAt(body.level(), feet)
            || openable(body.level().getBlockState(feet)) && Footing.holdsAbove(body.level(), feet.below());
    }

    private static boolean openable(BlockState state) {
        return state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand()
            || state.getBlock() instanceof TrapDoorBlock && state.is(BlockTags.WOODEN_TRAPDOORS);
    }

    private static void openDoors(Mob body, BlockPos feet) {
        for (int y = 0; y <= 2; y++) {
            BlockPos at = feet.above(y);
            BlockState state = body.level().getBlockState(at);
            if (!openable(state) || state.getValue(DoorBlock.OPEN)) {
                continue;
            }
            if (state.getBlock() instanceof DoorBlock door) {
                door.setOpen(body, body.level(), state, at, true);
            } else {
                body.level().setBlock(at, state.setValue(TrapDoorBlock.OPEN, true), 3);
            }
        }
    }

    private static boolean breathable(Mob body, BlockPos feet) {
        return canStay(body, feet);
    }
}
