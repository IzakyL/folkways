package io.github.izakyl.folkways.carts;

import io.github.izakyl.folkways.plugins.person.walk.ResidentNodeEvaluator;
import io.github.izakyl.folkways.plugins.person.walk.ResidentPathNavigation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Cart-specific horse clearance and centred paths, independent of resident riding.
final class HorseCartNavigation extends ResidentPathNavigation {
    private final LineState state;
    private Path turnPath;

    HorseCartNavigation(Mob horse, Level level, LineState state) {
        super(horse, level);
        this.state = state;
        setCanOpenDoors(false);
        setCanFloat(false);
        setMaxVisitedNodesMultiplier(4);
    }

    @Override
    protected ResidentNodeEvaluator evaluator() {
        return new CartNodeEvaluator(null) {
            @Override
            protected double radius() {
                // A loose horse can leave its stall before it has to reserve the cart's trailing envelope.
                return state.cart((net.minecraft.server.level.ServerLevel) level)
                    .filter(cart -> cart.getPulling() == mob).isPresent() ? super.radius() : mob.getBbWidth() / 2.0;
            }

            @Override
            public void prepare(PathNavigationRegion region, Mob horse) {
                super.prepare(region, horse);
                entityWidth = 1;
                entityDepth = 1;
                entityHeight = 1;
                setCanOpenDoors(false);
                setCanPassDoors(false);
            }

            @Override
            public Node getStart() {
                return getStartNode(mob.blockPosition());
            }
        };
    }

    @Override
    protected List<AABB> obstacles(double range) {
        var own = state.cart((net.minecraft.server.level.ServerLevel) level).map(Entity::getBoundingBox);
        return super.obstacles(range).stream().filter(box -> own.filter(box::equals).isEmpty()).toList();
    }

    @Override
    public void tick() {
        super.tick();
        var control = mob.getMoveControl();
        var cart = state.cart((net.minecraft.server.level.ServerLevel) level)
            .filter(found -> found.getPulling() == mob);
        if (isDone() || !control.hasWanted() || cart.isEmpty()) {
            return;
        }
        Vec3 shaft = mob.position().subtract(cart.get().position());
        Vec3 wanted = new Vec3(control.getWantedX() - mob.getX(), 0, control.getWantedZ() - mob.getZ());
        if (shaft.horizontalDistanceSqr() < .25 || wanted.horizontalDistanceSqr() < 1.0E-6) {
            return;
        }
        double angle = Math.atan2(shaft.x * wanted.z - shaft.z * wanted.x, shaft.x * wanted.x + shaft.z * wanted.z);
        double limit = Math.toRadians(55);
        if (Math.abs(angle) > limit) {
            // A collision or a newly selected node can ask for a sharper turn than the trailing shaft allows.
            // Keep drawing forward on a wider arc until the cart catches up, instead of facing across it.
            double heading = Math.atan2(shaft.z, shaft.x) + Math.clamp(angle, -limit, limit);
            double distance = Math.max(1, wanted.horizontalDistance());
            control.setWantedPosition(mob.getX() + Math.cos(heading) * distance, control.getWantedY(),
                mob.getZ() + Math.sin(heading) * distance, speedModifier);
        }
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

    boolean turn(List<Vec3> points, double speed) {
        // Check the entire swept envelope before starting. A blocked turning bay leaves the team waiting,
        // rather than letting a shortest-path search cut across the loop and reverse through the cart.
        for (Vec3 point : points) {
            // On these smooth arcs the cart cuts inside the horse's circle. No right-angle chord allowance
            // is needed; reserve both bodies' half widths plus a small margin on the outside of the circle.
            double radius = Math.max(.75, mob.getBbWidth() / 2.0) + .1;
            AABB envelope = new AABB(point.x - radius, point.y + .01, point.z - radius,
                point.x + radius, point.y + Math.max(1.4, mob.getBbHeight()), point.z + radius);
            if (!level.noCollision(mob, envelope)
                    || level.getBlockState(BlockPos.containing(point).below()).getCollisionShape(level,
                        BlockPos.containing(point).below()).isEmpty()) {
                return false;
            }
        }
        List<Node> nodes = points.stream().map(p -> new Node((int) Math.floor(p.x),
            (int) Math.floor(p.y), (int) Math.floor(p.z))).toList();
        turnPath = new Path(nodes, BlockPos.containing(points.getLast()), true) {
            @Override
            public Vec3 getEntityPosAtNode(Entity entity, int index) {
                return points.get(index);
            }
        };
        return moveTo(turnPath, speed);
    }

    @Override
    protected void followThePath() {
        // Vanilla tests arrival against integer nodes plus the mob's width. Several five-degree arc samples
        // share a block; those tests steer past the actual samples and can turn the horse in place. The same
        // exact-coordinate test also keeps the first ordinary node after a turn from sending it backwards.
        while (!path.isDone() && mob.position().distanceToSqr(path.getNextEntityPos(mob)) < .7 * .7) {
            path.advance();
        }
        if (path != turnPath) {
            doStuckDetection(getTempMobPos());
            return;
        }
        // moveTo does not reset vanilla's per-block timeout. After a station dwell, the first arc points
        // can share its old cached block and immediately time out. Curved paths use displacement instead.
        if (tick - lastStuckCheck > 100) {
            if (mob.position().distanceToSqr(lastStuckCheckPos) < .25) {
                stop();
            }
            lastStuckCheck = tick;
            lastStuckCheckPos = mob.position();
        }
    }

    boolean completedTurn() {
        return turnPath != null && turnPath.isDone();
    }

    @Override
    public void recomputePath() {
        if (path == turnPath && turnPath != null) {
            // Nearby block notifications must not replace a loop by a shortest path to its final block.
            hasDelayedRecomputation = false;
            return;
        }
        super.recomputePath();
    }
}
