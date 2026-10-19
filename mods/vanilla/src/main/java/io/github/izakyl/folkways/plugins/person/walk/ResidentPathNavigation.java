package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.terms.ClimbableBlocks;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;

public final class ResidentPathNavigation extends GroundPathNavigation {

    public ResidentPathNavigation(Mob mob, Level level) {
        super(mob, level);
    }

    @Override
    protected PathFinder createPathFinder(int maxVisitedNodes) {
        this.nodeEvaluator = new ResidentNodeEvaluator(null);
        this.nodeEvaluator.setCanPassDoors(true);
        this.nodeEvaluator.setCanOpenDoors(true);
        return new PathFinder(this.nodeEvaluator, maxVisitedNodes);
    }

    @Override
    protected net.minecraft.world.level.pathfinder.Path createPath(Set<BlockPos> targets,
            int margin, boolean above, int accuracy, float range) {
        BlockPos start = null;
        var source = WorldSpaces.at(mob);
        var realm = source.realm();
        if (!(realm instanceof Realm.Frame)) {
            for (BlockPos target : targets) {
                var address = WorldPos.of(level, target);
                if (address.realm() instanceof Realm.Frame) {
                    realm = address.realm();
                    break;
                }
            }
        }
        if (realm instanceof Realm.Frame frame) {
            var space = WorldSpaces.frame(level, frame.structure());
            if (space.isPresent()) {
                var local = space.get().toLocal(mob.position());
                start = space.get().storage(BlockPos.containing(local.x, local.y + (mob.onGround() ? 0.5D : 0.0D), local.z));
            }
        }
        var evaluator = (ResidentNodeEvaluator) nodeEvaluator;
        evaluator.startingAt(start);
        try {
            return super.createPath(targets, margin, above, accuracy, range);
        } finally {
            evaluator.startingAt(null);
        }
    }

    // Where the body is sent for a node: a climbable block under it holds the body at the node's height, as a
    // ladder's rung does. Vanilla reads that height off collision shapes, so on a vine it sent the body to the foot
    // of the column, and it hung there never climbing.
    @Override
    protected double getGroundY(Vec3 node) {
        double ground = super.getGroundY(node);
        BlockPos at = BlockPos.containing(node);
        return ClimbableBlocks.isClimbable(level.getBlockState(at.below())) ? Math.max(ground, at.getY()) : ground;
    }

    @Override
    protected boolean canUpdatePath() {
        return super.canUpdatePath()
            || ClimbableBlocks.isClimbable(mob.getInBlockState())
            || ClimbableBlocks.isClimbable(level.getBlockState(mob.blockPosition().above()));
    }

    @Override
    protected void followThePath() {
        if (isBelowColumnNode(path.getNextNodePos())) {
            doStuckDetection(getTempMobPos());
            return;
        }
        super.followThePath();
    }

    private boolean isBelowColumnNode(Vec3i node) {
        return mob.getY() < node.getY() - ARRIVED
            && ClimbableBlocks.isClimbable(
                level.getBlockState(new BlockPos(node.getX(), node.getY() - 1, node.getZ())));
    }

    private static final double ARRIVED = 0.01;
}
