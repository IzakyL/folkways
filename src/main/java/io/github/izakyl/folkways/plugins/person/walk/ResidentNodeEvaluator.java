package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.resident.Searches;
import io.github.izakyl.folkways.core.api.terms.ClimbableBlocks;
import io.github.izakyl.folkways.core.api.terms.Keepouts;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

public final class ResidentNodeEvaluator extends WalkNodeEvaluator {
    private final BlockPos.MutableBlockPos probePos = new BlockPos.MutableBlockPos();
    @Nullable
    private BlockPos start;
    private boolean outOfBounds;

    public ResidentNodeEvaluator(@Nullable BlockPos start) {
        this.start = start;
    }

    void startingAt(@Nullable BlockPos cell) {
        start = cell;
    }

    // Someone already standing where nobody should may still walk out of it.
    @Override
    public void prepare(PathNavigationRegion region, Mob mob) {
        super.prepare(region, mob);
        outOfBounds = Keepouts.forbidden(mob.level(), start != null ? start : mob.blockPosition());
    }

    // Off the ground, vanilla starts a path on the floor under the mob, through anything it could walk through: for
    // a resident on a ladder, or just over its top, that is the foot of the shaft, and it would walk away from there
    // while it hung at the top. One in a climbable, or over one, starts where its feet are.
    @Override
    public Node getStart() {
        if (start != null) {
            return getStartNode(start);
        }
        BlockPos feet = mob.blockPosition();
        if (!mob.onGround() && (isClimbable(currentContext, feet.getX(), feet.getY(), feet.getZ())
                || isClimbable(currentContext, feet.getX(), feet.getY() - 1, feet.getZ()))) {
            return getStartNode(feet);
        }
        return super.getStart();
    }

    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
        if (context.getBlockState(probePos.set(x, y, z)).is(Blocks.BARRIER)
                || context.getBlockState(probePos.set(x, y - 1, z)).is(Blocks.BARRIER)) {
            return PathType.BLOCKED;
        }
        if (!outOfBounds && mob != null && Keepouts.forbidden(mob.level(), x, y, z)) {
            return PathType.BLOCKED;
        }
        PathType type = super.getPathType(context, x, y, z);
        if (type == PathType.FENCE && isShutGate(context.getBlockState(probePos.set(x, y, z)))) {
            return PathType.DOOR_WOOD_CLOSED;
        }
        if (type == PathType.TRAPDOOR && isShutOverhead(context.getBlockState(probePos.set(x, y, z)))) {
            return PathType.BLOCKED;
        }
        if ((type == PathType.WALKABLE || type == PathType.OPEN) && isPortalMouth(context, x, y, z)) {
            return PathType.DANGER_OTHER;
        }
        if (type == PathType.OPEN && (isClimbable(context, x, y, z) || restsOnAScaffold(context, x, y, z))) {
            return PathType.WALKABLE;
        }
        return type;
    }

    // A body holding a climbable block stands on it as on a ladder's rung. Vanilla reads the floor off collision
    // shapes, so a ladder's thin plate is a floor but a vine is none, and stepping off the top of a vine onto the
    // ledge it hangs from looked like a jump of two.
    @Override
    protected double getFloorLevel(BlockPos pos) {
        double floor = super.getFloorLevel(pos);
        boolean held = isClimbable(currentContext, pos.getX(), pos.getY() - 1, pos.getZ());
        return held ? Math.max(floor, pos.getY()) : floor;
    }

    private boolean isPortalMouth(PathfindingContext context, int x, int y, int z) {
        return context.getBlockState(probePos.set(x, y, z)).is(Blocks.NETHER_PORTAL);
    }

    // Vanilla walks through any trapdoor's cell, as it would over a shut one laid low; one shut in the top half of
    // the cell is a lid at head height that nobody walks under, and a resident pathing through it stood against it
    // for good. The ways (Footing) never counted the cell passable.
    private static boolean isShutOverhead(BlockState state) {
        return state.getBlock() instanceof TrapDoorBlock && !state.getValue(TrapDoorBlock.OPEN)
            && state.getValue(TrapDoorBlock.HALF) == Half.TOP;
    }

    private static boolean isShutGate(BlockState state) {
        return state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN);
    }

    @Override
    public int getNeighbors(Node[] neighbors, Node node) {
        Searches.expanded();
        int count = super.getNeighbors(neighbors, node);
        if (!inColumn(node)) {
            return count;
        }
        count = append(neighbors, count, climbNode(node.x, node.y + 1, node.z), node);
        count = append(neighbors, count, climbNode(node.x, node.y - 1, node.z), node);
        return count;
    }

    private int append(Node[] neighbors, int count, @Nullable Node candidate, Node from) {
        if (count >= neighbors.length || !isNeighborValid(candidate, from)) {
            return count;
        }
        neighbors[count] = candidate;
        return count + 1;
    }

    @Nullable
    private Node climbNode(int x, int y, int z) {
        PathType type = getCachedPathType(x, y, z);
        if (type != PathType.WALKABLE || mob.getPathfindingMalus(type) < 0.0F) {
            return null;
        }
        Node node = getNode(x, y, z);
        node.type = type;
        node.costMalus = Math.max(node.costMalus, mob.getPathfindingMalus(type));
        return node;
    }

    private boolean inColumn(Node node) {
        return isClimbable(currentContext, node.x, node.y, node.z)
            || isClimbable(currentContext, node.x, node.y - 1, node.z)
            || isClimbable(currentContext, node.x, node.y + 1, node.z);
    }

    private boolean isClimbable(PathfindingContext context, int x, int y, int z) {
        return ClimbableBlocks.isClimbable(context.getBlockState(probePos.set(x, y, z)));
    }

    private boolean restsOnAScaffold(PathfindingContext context, int x, int y, int z) {
        return ClimbableBlocks.offersFloor(context.getBlockState(probePos.set(x, y - 1, z)));
    }
}
