package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.resident.Searches;
import io.github.izakyl.folkways.core.api.terms.ClimbableBlocks;
import io.github.izakyl.folkways.core.api.terms.Keepouts;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class ResidentNodeEvaluator extends WalkNodeEvaluator {
    // How high a body steps without a jump.
    private static final double STEP = 0.6D;

    private final BlockPos.MutableBlockPos probePos = new BlockPos.MutableBlockPos();
    @Nullable
    private BlockPos start;
    private boolean outOfBounds;
    private Keepouts.Keepout escapeWalls;

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
        BlockPos feet = start != null ? start : mob.blockPosition();
        outOfBounds = Keepouts.forbidden(mob.level(), region, feet.getX(), feet.getY(), feet.getZ());
        escapeWalls = outOfBounds ? Keepouts.blockingEscapeFrom(mob.level(), region, feet) : null;
    }

    // Off the ground, vanilla starts a path on the floor under the mob, through anything it could walk through: for
    // a resident on a ladder, or just over its top, that is the foot of the shaft, and it would walk away from there
    // while it hung at the top. One in a climbable, or over one, starts where its feet are.
    @Override
    public Node getStart() {
        if (start != null) {
            return getStartNode(floatingStart(start));
        }
        BlockPos feet = mob.blockPosition();
        if (!mob.onGround() && (isClimbable(currentContext, feet.getX(), feet.getY(), feet.getZ())
                || isClimbable(currentContext, feet.getX(), feet.getY() - 1, feet.getZ()))) {
            return getStartNode(feet);
        }
        return super.getStart();
    }

    // A graph search can start anywhere, including deep water away from the sizing mob. Like vanilla's live
    // navigation, a floating walker begins in the top water cell, where its float goal can bring it to the bank.
    private BlockPos floatingStart(BlockPos feet) {
        if (!canFloat()) {
            return feet;
        }
        BlockPos.MutableBlockPos cell = feet.mutable();
        BlockState state = currentContext.getBlockState(cell);
        while (cell.getY() < currentContext.level().getMaxBuildHeight()
                && (state.is(Blocks.WATER) || state.getFluidState() == Fluids.WATER.getSource(false))) {
            cell.move(Direction.UP);
            state = currentContext.getBlockState(cell);
        }
        return cell.getY() == feet.getY() ? feet : cell.below();
    }

    // Vanilla only weighs a burning cell (a lit campfire, fire, the top of magma): with no other way, or a way
    // round some thirty blocks longer, a resident walked through the campfire and burned. One that fire does not
    // hurt still may.
    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
        if (context.getBlockState(probePos.set(x, y, z)).is(Blocks.BARRIER)
                || context.getBlockState(probePos.set(x, y - 1, z)).is(Blocks.BARRIER)) {
            return PathType.BLOCKED;
        }
        if (mob != null && (outOfBounds ? escapeWalls.forbids(mob.level(), x, y, z)
                : Keepouts.forbidden(mob.level(), context.level(), x, y, z))) {
            return PathType.BLOCKED;
        }
        PathType type = super.getPathType(context, x, y, z);
        if (type == PathType.DAMAGE_FIRE && mob != null && !mob.fireImmune()) {
            return PathType.BLOCKED;
        }
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
        if ((type == PathType.OPEN || type == PathType.WALKABLE) && isSolidInside(context, x, y, z)) {
            return PathType.BLOCKED;
        }
        if (type == PathType.OPEN && (isSolidInside(context, x, y - 1, z) || topsAClimb(context, x, y - 1, z))) {
            return PathType.WALKABLE;
        }
        return type;
    }

    // A climb is walked in, so it never counts as solid inside; one with a floor of its own still holds up whoever
    // climbs out on top of it, as the ways (Footing) have it. A stepladder against a barrel is climbed to its top
    // step, and the barrel's lid is a step up from there.
    private boolean topsAClimb(PathfindingContext context, int x, int y, int z) {
        BlockState state = context.getBlockState(probePos.set(x, y, z));
        if (!ClimbableBlocks.isClimbable(state) || ClimbableBlocks.offersFloor(state)) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(context.level(), probePos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) > STEP;
    }

    // Vanilla walks through any block that is not a full cube unless the block says otherwise, and every vanilla
    // block that matters says so. A modded machine a pixel short of the cube on each side does not: a resident
    // pathed into it and stood against it for good, and nobody pathed onto it. A cell too tall to step into is
    // shut, and stands as a floor under the cell above, as the ways (Footing) always had it.
    private boolean isSolidInside(PathfindingContext context, int x, int y, int z) {
        BlockState state = context.getBlockState(probePos.set(x, y, z));
        if (state.isAir() || ClimbableBlocks.isClimbable(state) || ClimbableBlocks.offersFloor(state)) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(context.level(), probePos);
        return !shape.isEmpty() && shape.max(Direction.Axis.Y) > STEP;
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
        count = keepOpenSides(neighbors, count, node);
        if (inColumn(node)) {
            count = append(neighbors, count, climbNode(node.x, node.y + 1, node.z), node);
            count = append(neighbors, count, climbNode(node.x, node.y - 1, node.z), node);
        }
        // A misplaced resident may leave the forbidden area it starts in. Once outside,
        // its route cannot enter that area again or use another keepout as a shortcut.
        if (outOfBounds && !forbidden(node)) {
            int kept = 0;
            for (int i = 0; i < count; i++) {
                Node next = neighbors[i];
                if (forbidden(next)) continue;
                if (node.x != next.x && node.z != next.z
                        && (Keepouts.forbidden(mob.level(), currentContext.level(), next.x, node.y, node.z)
                            || Keepouts.forbidden(mob.level(), currentContext.level(), node.x, node.y, next.z))) continue;
                neighbors[kept++] = next;
            }
            count = kept;
        }
        return count;
    }

    // A climb is walked into from beside it, but only through a side that is open at the feet. A ladder's plate
    // leaves three, and scaffolding is walked through on every side; a stepladder is stepped only on its low side and stands a block high on the others, where a body
    // sent to its middle pushed against the step above for good.
    private int keepOpenSides(Node[] neighbors, int count, Node from) {
        int kept = 0;
        for (int i = 0; i < count; i++) {
            Node next = neighbors[i];
            if (sidesOpen(next, from) && sidesOpen(from, next)) {
                neighbors[kept++] = next;
            }
        }
        return kept;
    }

    private boolean sidesOpen(Node cell, Node toward) {
        if ((cell.x == toward.x && cell.z == toward.z) || !isClimbable(currentContext, cell.x, cell.y, cell.z)) {
            return true;
        }
        int dx = Integer.signum(toward.x - cell.x);
        int dz = Integer.signum(toward.z - cell.z);
        return (dx == 0 || openAtFeet(cell, dx > 0 ? Direction.EAST : Direction.WEST))
            && (dz == 0 || openAtFeet(cell, dz > 0 ? Direction.SOUTH : Direction.NORTH));
    }

    // The face of the cell a body steps through: a sliver of the cell along that face, the body's width across it.
    private boolean openAtFeet(Node cell, Direction side) {
        BlockState state = currentContext.getBlockState(probePos.set(cell.x, cell.y, cell.z));
        if (ClimbableBlocks.offersFloor(state)) {
            return true;
        }
        VoxelShape shape = state.getCollisionShape(currentContext.level(), probePos);
        if (shape.isEmpty()) {
            return true;
        }
        double half = mob.getBbWidth() / 2.0D;
        double lo = side.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 15.0D / 16.0D : 0.0D;
        VoxelShape face = side.getAxis() == Direction.Axis.X
            ? Shapes.box(lo, 0.0D, 0.5D - half, lo + 1.0D / 16.0D, 1.0D, 0.5D + half)
            : Shapes.box(0.5D - half, 0.0D, lo, 0.5D + half, 1.0D, lo + 1.0D / 16.0D);
        VoxelShape met = Shapes.join(shape, face, BooleanOp.AND);
        return met.isEmpty() || met.max(Direction.Axis.Y) <= STEP;
    }

    private boolean forbidden(Node node) {
        return Keepouts.forbidden(mob.level(), currentContext.level(), node.x, node.y, node.z);
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
