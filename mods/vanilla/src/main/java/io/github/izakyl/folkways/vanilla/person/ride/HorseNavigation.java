package io.github.izakyl.folkways.vanilla.person.ride;

import io.github.izakyl.folkways.core.resident.api.MobControls;
import io.github.izakyl.folkways.core.world.api.ClimbableBlocks;
import io.github.izakyl.folkways.vanilla.person.walk.ResidentNodeEvaluator;
import io.github.izakyl.folkways.vanilla.person.walk.ResidentPathNavigation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class HorseNavigation extends ResidentPathNavigation {
    private double cruise = 1;

    HorseNavigation(Mob horse, Level level) {
        super(horse, level);
        setCanOpenDoors(false);
        setCanFloat(false);
        setMaxVisitedNodesMultiplier(4);
    }

    static void fit(Mob horse) {
        if (!(horse.getNavigation() instanceof HorseNavigation)) {
            MobControls.navigation(horse, new HorseNavigation(horse, horse.level()));
        }
    }

    @Override
    public boolean moveTo(Path path, double speed) {
        cruise = speed;
        return super.moveTo(path, speed);
    }

    @Override
    public void tick() {
        speedModifier = cruise;
        if (!isDone() && path.getNodeCount() - path.getNextNodeIndex() <= 4) {
            Vec3 end = path.getEntityPosAtNode(mob, path.getNodeCount() - 1);
            double distance = end.subtract(mob.position()).horizontalDistance();
            speedModifier *= Math.clamp(distance / 2.5, .15, 1);
        }
        super.tick();
    }

    @Override
    protected void followThePath() {
        // These paths use exact cell centres. Vanilla instead adds an integer half-width (1 for a horse),
        // so its arrival square is shifted by half a block from the positions we actually steer toward.
        while (!path.isDone() && mob.position().distanceToSqr(path.getNextEntityPos(mob)) < .65 * .65) {
            path.advance();
        }
        if (path.isDone()) {
            stop();
            return;
        }
        doStuckDetection(getTempMobPos());
    }

    @Override
    public void stop() {
        super.stop();
        // Stop issuing thrust and let ordinary ground friction settle the remaining momentum.
        mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
        mob.setSpeed(0);
        mob.setZza(0);
    }

    @Override
    protected ResidentNodeEvaluator evaluator() {
        return new HorseEvaluator();
    }

    @Override
    protected List<AABB> obstacles(double range) {
        // Route around parked horses, while allowing an overlapping team to move apart at departure.
        return level.getEntitiesOfClass(AbstractHorse.class, mob.getBoundingBox().inflate(range),
                other -> other != mob && !other.isVehicle() && other.getNavigation().isDone()
                    && !other.getBoundingBox().intersects(mob.getBoundingBox()))
            .stream().map(Entity::getBoundingBox).toList();
    }

    @Override
    protected Path createPath(Set<BlockPos> targets, int margin, boolean above, int accuracy, float range) {
        Path found = super.createPath(targets, margin, above, accuracy, Math.max(range, 64));
        if (found == null) {
            return null;
        }
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < found.getNodeCount(); i++) {
            nodes.add(found.getNode(i));
        }
        return new Path(nodes, found.getTarget(), found.canReach()) {
            @Override
            public Vec3 getEntityPosAtNode(Entity entity, int index) {
                Node node = getNode(index);
                return new Vec3(node.x + .5, node.y, node.z + .5);
            }
        };
    }

    private static final class HorseEvaluator extends ResidentNodeEvaluator {
        private HorseEvaluator() {
            super(null);
        }

        @Override
        public void prepare(PathNavigationRegion region, Mob horse) {
            super.prepare(region, horse);
            // The swept box below owns sizing; vanilla's integer footprint shifts wide mobs off cell centres.
            entityWidth = 1;
            entityDepth = 1;
            entityHeight = 1;
            setCanOpenDoors(false);
            setCanPassDoors(false);
        }

        @Override
        public Node getStart() {
            return getStartNode(BlockPos.containing(mob.getX(),
                mob.getY() + (mob.onGround() ? .5 : 0), mob.getZ()));
        }

        @Override
        public PathType getPathType(PathfindingContext context, int x, int y, int z) {
            PathType type = super.getPathType(context, x, y, z);
            return clear(context, x + .5, y, z + .5) ? type : PathType.BLOCKED;
        }

        private boolean clear(PathfindingContext context, double x, double y, double z) {
            AABB bounds = new AABB(x - .71, y + .01, z - .71, x + .71, y + 3.6, z + .71);
            for (BlockPos at : BlockPos.betweenClosed(Mth.floor(bounds.minX), Mth.floor(bounds.minY),
                    Mth.floor(bounds.minZ), Mth.floor(bounds.maxX), Mth.floor(bounds.maxY),
                    Mth.floor(bounds.maxZ))) {
                var state = context.getBlockState(at);
                if (ClimbableBlocks.isClimbable(state) || !state.getFluidState().isEmpty()) {
                    return false;
                }
                for (AABB box : state.getCollisionShape(context.level(), at).toAabbs()) {
                    AABB world = box.move(at);
                    boolean step = (at.getX() != Mth.floor(x) || at.getZ() != Mth.floor(z))
                        && world.maxY <= y + mob.maxUpStep();
                    if (!step && bounds.intersects(world)) {
                        return false;
                    }
                }
            }
            return true;
        }

        @Override
        public int getNeighbors(Node[] neighbors, Node node) {
            int count = super.getNeighbors(neighbors, node);
            int kept = 0;
            for (int i = 0; i < count; i++) {
                Node next = neighbors[i];
                if (Math.abs(next.y - node.y) <= 1 && clear(currentContext, (node.x + next.x) / 2.0 + .5,
                        Math.max(node.y, next.y), (node.z + next.z) / 2.0 + .5)) {
                    neighbors[kept++] = next;
                }
            }
            return kept;
        }
    }
}
