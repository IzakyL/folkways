package io.github.izakyl.folkways.vanilla.build;

import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.person.walk.ResidentPathNavigation;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

final class ScaffoldAction extends BuildAction {

    private final TemporaryScaffolds temporary;

    ScaffoldAction(BuildJob job, TemporaryScaffolds temporary) {
        super(job);
        this.temporary = temporary;
    }

    static BlockState state() {
        return Blocks.SCAFFOLDING.defaultBlockState().setValue(ScaffoldingBlock.DISTANCE, 0);
    }

    @Override
    boolean finished(ServerLevel level) {
        if (job().kind() == BuildJob.Kind.LOWER) {
            // World edits can remove the structure before the cleanup job commits. It must still
            // release its ownership record, even when every requested world cell is already air.
            return job().after().keySet().stream().noneMatch(temporary::contains);
        }
        // Distance and bottom are computed by scaffold physics, rather than blueprint targets.
        return job().after().keySet().stream().allMatch(cell -> temporary.contains(cell)
            && level.getBlockState(cell.block(level)).is(Blocks.SCAFFOLDING));
    }

    @Override
    Outcome work(ServerLevel level, Worker who) {
        List<WorldPos> column = List.copyOf(job().after().keySet());
        List<BlockPos> cells = new ArrayList<>(column.size());
        for (WorldPos cell : column) {
            cells.add(cell.block(level));
        }
        return job().kind() == BuildJob.Kind.RAISE ? raise(level, who, column, cells) : lower(level, who, column,
            cells);
    }

    private Outcome raise(ServerLevel level, Worker who, List<WorldPos> column, List<BlockPos> cells) {
        // Validate the entire ordered structure before consuming materials or changing the world.
        Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
        for (BlockPos cell : cells) {
            if (!level.getBlockState(cell).isAir()) {
                return refuse(BuildRefusal.CONTESTED);
            }
            BlockState below = placed.getOrDefault(cell.below(), level.getBlockState(cell.below()));
            int distance = below.is(Blocks.SCAFFOLDING) ? below.getValue(ScaffoldingBlock.DISTANCE)
                : below.isFaceSturdy(level, cell.below(), Direction.UP) ? 0 : 7;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighbor = cell.relative(direction);
                BlockState side = placed.getOrDefault(neighbor, level.getBlockState(neighbor));
                if (side.is(Blocks.SCAFFOLDING)) {
                    distance = Math.min(distance, side.getValue(ScaffoldingBlock.DISTANCE) + 1);
                }
            }
            if (distance > 6) {
                return refuse(BuildRefusal.CONTESTED);
            }
            placed.put(cell, state().setValue(ScaffoldingBlock.DISTANCE, distance)
                .setValue(ScaffoldingBlock.BOTTOM, distance > 0 && !below.is(Blocks.SCAFFOLDING)));
        }
        spend(who);
        placed.forEach((cell, state) -> level.setBlock(cell, state, 3));
        temporary.added(column);
        return Outcome.done();
    }

    // A mob on the bottom rung may settle the last step when access is removed. The quarter block allows for
    // the small vertical oscillation of scaffold climbing. Players keep control of their own movement.
    private static boolean droppedSafely(LivingEntity body, int foot) {
        return body instanceof Mob && body.getY() <= foot + 1.25D;
    }

    private boolean descend(Mob mob, BlockPos base, List<BlockPos> cells, Path entryPath) {
        for (var wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof Descent descent) {
                return descent.request(base, cells, temporary, spec().pace(), entryPath);
            }
        }
        Descent descent = new Descent(mob);
        boolean requested = descent.request(base, cells, temporary, spec().pace(), entryPath);
        mob.goalSelector.addGoal(0, descent);
        return requested;
    }

    private boolean climbOut(Mob mob, BlockPos base, List<BlockPos> cells, Path path) {
        for (var wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof NativeReturn returning) {
                return returning.request(base, temporary, spec().pace(), path);
            }
        }
        NativeReturn returning = new NativeReturn(mob);
        boolean requested = returning.request(base, temporary, spec().pace(), path);
        mob.goalSelector.addGoal(0, returning);
        return requested;
    }

    private boolean returning(Mob mob, BlockPos base) {
        for (var wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof Descent descent && descent.belongs(base, temporary) && descent.canUse()) {
                return true;
            }
            if (wrapped.getGoal() instanceof NativeReturn returning && returning.belongs(base, temporary)
                    && returning.canUse()) {
                return true;
            }
        }
        return false;
    }

    // Site approach yields during the physical return and while the resident safely waits on permanent
    // ground for the cleanup operation to commit. It must not send that resident down the same column again.
    static boolean returning(Mob mob, TemporaryScaffolds temporary) {
        for (var wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof Descent descent && descent.temporary == temporary && descent.canUse()) {
                return true;
            }
            if (wrapped.getGoal() instanceof NativeReturn returning) {
                if (returning.temporary == temporary && returning.canUse()) {
                    return true;
                }
                if (returning.returnedBase != null
                        && temporary.contains(WorldPos.of(mob.level(), returning.returnedBase))
                        && returning.completed(returning.returnedBase, temporary)) {
                    return true;
                }
            }
        }
        return false;
    }

    static void releaseReturn(Mob body, TemporaryScaffolds temporary) {
        for (var wrapped : body.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof Descent descent && descent.temporary == temporary) {
                wrapped.stop();
                descent.stop();
            }
            if (wrapped.getGoal() instanceof NativeReturn returning && returning.temporary == temporary) {
                wrapped.stop();
                returning.stop();
            }
        }
    }

    private boolean returnedToPermanentGround(Mob mob, BlockPos base) {
        for (var wrapped : mob.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof NativeReturn returning && returning.completed(base, temporary)) {
                return true;
            }
        }
        return false;
    }

    /** One reusable, normally dormant request per mob; it never claims movement after reaching safe ground. */
    private static final class Descent extends Goal {
        private final Mob mob;
        private BlockPos base;
        private List<BlockPos> column = List.of();
        private long until;
        private TemporaryScaffolds temporary;
        private double pace;
        private Path entryPath;
        private boolean entered;

        private Descent(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
        }

        private boolean request(BlockPos base, List<BlockPos> column, TemporaryScaffolds temporary,
                double pace, Path entryPath) {
            if (belongs(base, temporary)) {
                // Repeated cleanup attempts share an active return's deadline. Once it ends, a later attempt
                // may ask again: another resident or a temporary interruption may have kept this one from moving.
                return canUse();
            }
            if (this.base != null && canUse()) {
                return false;
            }
            this.base = base;
            this.temporary = temporary;
            this.pace = pace;
            this.column = List.copyOf(column);
            this.entryPath = entryPath;
            entered = overColumn();
            until = mob.level().getGameTime() + 400;
            return true;
        }

        private boolean belongs(BlockPos base, TemporaryScaffolds temporary) {
            return base.equals(this.base) && this.temporary == temporary;
        }

        @Override
        public boolean canUse() {
            if (base == null) {
                return false;
            }
            if (!mob.isAlive() || mob.level().getGameTime() >= until || temporary == null
                || !temporary.contains(WorldPos.of(mob.level(), base))
                || !mob.level().getBlockState(base).is(Blocks.SCAFFOLDING)) {
                clearRequest();
                return false;
            }
            boolean needed = mob.getY() > base.getY() + 1.0D || column.stream()
                .anyMatch(cell -> new AABB(cell.above()).intersects(mob.getBoundingBox()));
            if (!needed) {
                clearRequest();
            }
            return needed;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            // Cleanup can finish this request between the goal selector's maintenance ticks.
            if (base == null) {
                return;
            }
            if (!entered) {
                entered = overColumn();
                if (!entered) {
                    // Approach on the existing floor first. Steering straight toward the base from a roof
                    // would send the resident off its edge instead of into the access column.
                    if (mob.getNavigation().isDone() && entryPath != null) {
                        if (entryPath.isDone()) {
                            int top = column.stream().filter(cell -> cell.getX() == base.getX()
                                && cell.getZ() == base.getZ())
                                .mapToInt(BlockPos::getY).max().orElse(base.getY()) + 1;
                            mob.getMoveControl().setWantedPosition(base.getX() + 0.5D, top,
                                base.getZ() + 0.5D, pace);
                        } else {
                            mob.getNavigation().moveTo(entryPath, pace);
                        }
                    }
                    return;
                }
            }
            if (mob.getY() > base.getY() + 0.5D) {
                // Target the exact column below first. A route to adjacent ground may otherwise start with a
                // sideways step off the top. ResidentMoveControl turns this into a controlled scaffold descent.
                mob.getNavigation().stop();
                mob.getMoveControl().setWantedPosition(base.getX() + 0.5D, base.getY(), base.getZ() + 0.5D,
                    pace);
            } else if (mob.level() instanceof ServerLevel level && mob.getNavigation().isDone()) {
                for (BlockPos exit : SiteApproach.stances(level, base, Set.copyOf(column), List.of())) {
                    if (mob.getNavigation().moveTo(exit.getX() + 0.5D, exit.getY(), exit.getZ() + 0.5D,
                            pace)) {
                        break;
                    }
                }
            }
        }

        @Override
        public void stop() {
            clearRequest();
            mob.getNavigation().stop();
        }

        private void clearRequest() {
            base = null;
            column = List.of();
            temporary = null;
            entryPath = null;
        }

        private boolean overColumn() {
            if (base == null) {
                return false;
            }
            double dx = mob.getX() - (base.getX() + 0.5D);
            double dz = mob.getZ() - (base.getZ() + 0.5D);
            return dx * dx + dz * dz <= 0.45D * 0.45D;
        }
    }

    /** A pit's lower floor is not safe ground until the resident has climbed onto a permanent surface. */
    private static final class NativeReturn extends Goal {
        private final Mob mob;
        private BlockPos base;
        private TemporaryScaffolds temporary;
        private Path path;
        private BlockPos exit;
        private BlockPos returnedBase;
        private TemporaryScaffolds returnedSite;
        private int returnedHeight;
        private double pace;
        private long until;

        private NativeReturn(Mob mob) {
            this.mob = mob;
            setFlags(EnumSet.of(Flag.MOVE, Flag.JUMP));
        }

        private boolean belongs(BlockPos base, TemporaryScaffolds temporary) {
            return base.equals(this.base) && temporary == this.temporary;
        }

        private boolean completed(BlockPos base, TemporaryScaffolds temporary) {
            if (!base.equals(returnedBase) || temporary != returnedSite) {
                return false;
            }
            BlockPos support = mob.blockPosition().below();
            if (mob.getY() < returnedHeight - 0.25D || mob.level().getBlockState(support).is(Blocks.SCAFFOLDING)
                    || !mob.level().getBlockState(support).isFaceSturdy(mob.level(), support, Direction.UP)) {
                returnedBase = null;
                returnedSite = null;
                return false;
            }
            return true;
        }

        private boolean request(BlockPos base, TemporaryScaffolds temporary, double pace, Path path) {
            if (belongs(base, temporary) && canUse()) {
                return true;
            }
            if (this.base != null && canUse()) {
                return false;
            }
            this.base = base;
            this.temporary = temporary;
            this.path = path;
            this.exit = path.getTarget();
            returnedBase = null;
            returnedSite = null;
            this.pace = pace;
            until = mob.level().getGameTime() + 400;
            return true;
        }

        @Override
        public boolean canUse() {
            if (base == null) {
                if (returnedSite != null && !returnedSite.contains(WorldPos.of(mob.level(), returnedBase))) {
                    returnedBase = null;
                    returnedSite = null;
                }
                return false;
            }
            boolean arrived = mob.distanceToSqr(exit.getX() + 0.5D, exit.getY(), exit.getZ() + 0.5D) < 0.64D
                && mob.level().getBlockState(mob.blockPosition().below()).isFaceSturdy(mob.level(),
                    mob.blockPosition().below(), Direction.UP)
                && !mob.level().getBlockState(mob.blockPosition().below()).is(Blocks.SCAFFOLDING);
            if (arrived) {
                returnedBase = base;
                returnedSite = temporary;
                returnedHeight = exit.getY();
            }
            if (!mob.isAlive() || arrived || mob.level().getGameTime() >= until || temporary == null
                    || !temporary.contains(WorldPos.of(mob.level(), base))) {
                base = null;
                temporary = null;
                path = null;
                exit = null;
                return false;
            }
            return true;
        }

        @Override
        public boolean canContinueToUse() {
            return canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            // A cleanup retry may have observed arrival and cleared the request before this goal is stopped.
            if (base == null || exit == null) {
                return;
            }
            if (path == null || path.isDone()) {
                path = mob.getNavigation().createPath(exit, 0);
            }
            if (path != null && (mob.getNavigation().isDone() || mob.getNavigation().getPath() != path)) {
                mob.getNavigation().moveTo(path, pace);
            }
        }

        @Override
        public void stop() {
            base = null;
            temporary = null;
            path = null;
            exit = null;
            mob.getNavigation().stop();
        }
    }

    private Outcome lower(ServerLevel level, Worker who, List<WorldPos> column, List<BlockPos> cells) {
        int foot = cells.stream().mapToInt(BlockPos::getY).min().orElse(0);
        // The tallest horizontal endpoint is not necessarily the access column. Descend at a
        // grounded vertical run, approaching its actual top along the existing deck first.
        BlockPos base = cells.stream().filter(cell -> !cells.contains(cell.below()))
            .filter(cell -> level.getBlockState(cell.below()).isFaceSturdy(level, cell.below(), Direction.UP))
            .min(java.util.Comparator.comparingInt(BlockPos::getY)).orElse(cells.getFirst());
        BlockPos top = base;
        while (cells.contains(top.above())) {
            top = top.above();
        }
        BlockPos entry = top.above();
        AABB bounds = new AABB(cells.getFirst());
        for (BlockPos cell : cells) {
            bounds = bounds.minmax(new AABB(cell.above()));
        }
        for (WorldPos cell : column) {
            if (!temporary.contains(cell) && level.getBlockState(cell.block(level)).is(Blocks.SCAFFOLDING)) {
                return refuse(BuildRefusal.CONTESTED);
            }
        }
        boolean waiting = false;
        for (Mob mob : level.getEntitiesOfClass(Mob.class,
                bounds.inflate(6.0D, 0.0D, 6.0D),
                Mob::isAlive)) {
            if (droppedSafely(mob, foot) && hasGroundWayOut(level, mob, base, cells)) {
                continue;
            }
            if (returning(mob, base)) {
                waiting = true;
                continue;
            }
            if (returnedToPermanentGround(mob, base)) {
                continue;
            }
            if (droppedSafely(mob, foot)) {
                Path exit = upperExit(level, mob, entry, cells);
                if (exit != null) {
                    waiting |= climbOut(mob, base, cells, exit);
                }
                continue;
            }
            if (mob != who.body() && !mob.getNavigation().isDone()) {
                continue;
            }
            Path path = mob.getNavigation().createPath(entry, 0);
            if (path != null && path.canReach() && !hasOtherExit(level, mob, base, cells)) {
                waiting |= descend(mob, base, cells, path);
            }
        }
        for (int at = 0; at < cells.size(); at++) {
            List<LivingEntity> occupants = level.getEntitiesOfClass(LivingEntity.class,
                new AABB(cells.get(at).above()), body -> !droppedSafely(body, foot)
                    && !(body instanceof Mob mob && returnedToPermanentGround(mob, base)));
            if (!occupants.isEmpty()) {
                // An idle resident can still be on the access column after another hand finishes the last block.
                // Ask it to climb down before retrying; refusing alone would leave both bodies waiting forever.
                for (LivingEntity occupant : occupants) {
                    if (occupant instanceof Mob mob && !returning(mob, base)
                            && (mob == who.body() || mob.getNavigation().isDone())) {
                        Path path = mob.getNavigation().createPath(entry, 0);
                        descend(mob, base, cells, path);
                    }
                }
                return refuse(BuildRefusal.CONTESTED);
            }
        }
        if (waiting) {
            return refuse(BuildRefusal.CONTESTED);
        }
        int recovered = 0;
        for (int at = cells.size() - 1; at >= 0; at--) {
            if (level.getBlockState(cells.get(at)).is(Blocks.SCAFFOLDING)) {
                level.setBlock(cells.get(at), Blocks.AIR.defaultBlockState(), 3);
                recovered++;
            }
        }
        temporary.removed(column);
        List<ItemStack> returned = new ArrayList<>();
        for (int left = recovered; left > 0; left -= Items.SCAFFOLDING.getDefaultMaxStackSize()) {
            returned.add(new ItemStack(Items.SCAFFOLDING, Math.min(left, Items.SCAFFOLDING.getDefaultMaxStackSize())));
        }
        return new Outcome.Done(List.of(), List.copyOf(returned), Optional.empty());
    }

    // A low resident beside an ordinary tower can already walk away on its own floor. A pit resident cannot:
    // the only routes to distant ground rise through this column. Probe actual feet and native paths, without
    // assigning a site boundary or treating a partial path as an exit.
    private static boolean hasGroundWayOut(ServerLevel level, Mob mob, BlockPos base, List<BlockPos> column) {
        List<BlockPos> exits = new ArrayList<>();
        for (BlockPos feet : BlockPos.betweenClosed(base.offset(-6, -1, -6).atY(mob.blockPosition().getY() - 1),
                base.offset(6, 1, 6).atY(mob.blockPosition().getY() + 1))) {
            if ((Math.abs(feet.getX() - base.getX()) == 6 || Math.abs(feet.getZ() - base.getZ()) == 6)
                    && SiteApproach.stands(level, feet, Set.copyOf(column))) {
                exits.add(feet.immutable());
            }
        }
        exits.sort(java.util.Comparator.comparingDouble(feet -> feet.distSqr(mob.blockPosition())));
        for (BlockPos exit : exits.stream().limit(12).toList()) {
            Path path = new ResidentPathNavigation(mob, level).createPath(exit, 0);
            if (path != null && path.canReach() && !usesColumn(path, base, column)) {
                return true;
            }
        }
        return false;
    }

    // Also usable before a requested column has been built: an enclosed lower resident still needs its
    // materials delivered and the planned access raised after the last target block is finished.
    static boolean neededForReturn(ServerLevel level, Mob mob, List<BlockPos> column) {
        if (column.isEmpty() || !mob.isAlive()) {
            return false;
        }
        BlockPos base = column.stream().min(java.util.Comparator.comparingInt(BlockPos::getY)).orElseThrow();
        if (!droppedSafely(mob, base.getY()) || Math.abs(mob.getX() - base.getX() - 0.5D) > 6.0D
                || Math.abs(mob.getZ() - base.getZ() - 0.5D) > 6.0D
                || hasGroundWayOut(level, mob, base, column)) {
            return false;
        }
        BlockPos top = base;
        while (column.contains(top.above())) {
            top = top.above();
        }
        return !upperFootings(level, top.above(), column).isEmpty();
    }

    private static List<BlockPos> upperFootings(ServerLevel level, BlockPos entry, List<BlockPos> column) {
        return SiteApproach.stances(level, entry, Set.copyOf(column), List.of()).stream()
            .filter(exit -> exit.getY() >= entry.getY() - 1
                && !level.getBlockState(exit).is(Blocks.SCAFFOLDING)
                && !level.getBlockState(exit.below()).is(Blocks.SCAFFOLDING)).toList();
    }

    private static Path upperExit(ServerLevel level, Mob mob, BlockPos entry, List<BlockPos> column) {
        for (BlockPos exit : upperFootings(level, entry, column)) {
            Path path = new ResidentPathNavigation(mob, level).createPath(exit, 0);
            if (path != null && path.canReach()) {
                return path;
            }
        }
        return null;
    }

    private static boolean usesColumn(Path path, BlockPos base, List<BlockPos> column) {
        for (int at = 0; at < path.getNodeCount(); at++) {
            var node = path.getNode(at);
            if (node.y > base.getY() + 1 && column.stream().anyMatch(cell ->
                    cell.getX() == node.x && cell.getZ() == node.z)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasOtherExit(ServerLevel level, Mob mob, BlockPos base, List<BlockPos> column) {
        int tried = 0;
        for (BlockPos exit : SiteApproach.stances(level, base, Set.copyOf(column), List.of())) {
            if (exit.getY() > base.getY() + 1 || tried++ >= 6) {
                continue;
            }
            Path path = mob.getNavigation().createPath(exit, 0);
            if (path == null || !path.canReach()) {
                continue;
            }
            boolean usesColumn = false;
            for (int at = 0; at < path.getNodeCount(); at++) {
                var node = path.getNode(at);
                if (node.x == base.getX() && node.z == base.getZ() && node.y > base.getY() + 1) {
                    usesColumn = true;
                    break;
                }
            }
            if (!usesColumn) {
                return true;
            }
        }
        return false;
    }
}
