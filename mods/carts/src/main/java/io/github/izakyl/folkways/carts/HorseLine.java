package io.github.izakyl.folkways.carts;

import com.jusipat.astikorcartsredux.entity.AbstractCargoCart;
import io.github.izakyl.folkways.core.resident.api.MobControls;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.phys.Vec3;

// The line owns the horse's movement, including approaching its cart and waiting for station handlers.
// Intermediate marks are steering targets, never work nodes or places to stop.
final class HorseLine extends Goal {
    private static final double SPEED = 1.0;
    private static final double PASS_MARK = 2.0;
    private final ServerLevel level;
    private final LineState state;
    private final AbstractHorse horse;
    private final PathNavigation previous;
    private final boolean noAi;
    private Line line;
    private BlockPos target;
    private int standing;
    private boolean dwelling;
    private long retry;
    private List<Vec3> turn;
    private boolean turning;

    HorseLine(ServerLevel level, Line line, LineState state, AbstractHorse horse) {
        this.level = level;
        this.line = line;
        this.state = state;
        this.horse = horse;
        previous = horse.getNavigation();
        noAi = horse.isNoAi();
        previous.stop();
        MobControls.navigation(horse, new HorseCartNavigation(horse, level, state));
        horse.setNoAi(false);
        if (horse.isLeashed()) {
            horse.dropLeash(true, false);
        }
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
        horse.goalSelector.addGoal(-1, this);
        state.lapNumber = Math.max(0, state.lapNumber);
    }

    AbstractHorse horse() {
        return horse;
    }

    void follow(Line updated) {
        if (!line.course().equals(updated.course())) {
            halt();
            target = null;
            dwelling = false;
            turn = null;
            turning = false;
            state.nextMark = 0;
            state.direction = 1;
        }
        line = updated;
    }

    @Override
    public boolean canUse() {
        return horse.isAlive();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public boolean isInterruptable() {
        return false;
    }

    @Override
    public void tick() {
        var found = state.cart(level);
        if (found.isEmpty()) {
            halt();
            return;
        }
        AbstractCargoCart cart = found.get();
        if (horse.isLeashed()) {
            horse.dropLeash(true, false);
        }
        if (cart.getPulling() != horse) {
            // Approach the parked cart under our own navigation, then hitch once. No handler or lead is needed.
            Vec3 ahead = line.course().get(1).at().cell().getBottomCenter()
                .subtract(line.course().getFirst().at().cell().getBottomCenter()).normalize();
            BlockPos hitch = BlockPos.containing(cart.position().add(ahead.scale(2.5)));
            if (horse.position().distanceToSqr(hitch.getBottomCenter()) > 1) {
                navigate(hitch);
                return;
            }
            halt();
            float yaw = (float) Math.toDegrees(Math.atan2(-ahead.x, ahead.z));
            horse.setYRot(yaw);
            horse.setYHeadRot(yaw);
            horse.setYBodyRot(yaw);
            cart.setPulling(horse);
            target = null;
            dwelling = false;
        }
        state.nextMark = Math.floorMod(state.nextMark, line.course().size());
        Line.Mark mark = line.course().get(state.nextMark);
        if (dwelling) {
            halt();
            standing = state.stopped(level) ? standing + 1 : 0;
            int station = mark.station();
            boolean handling = state.aboard.values().stream().anyMatch(sent ->
                !sent.loaded && sent.from == station || sent.loaded && sent.to == station);
            if (standing < Line.DWELL_TICKS || handling || !state.stray(cart).isEmpty()) {
                return;
            }
            dwelling = false;
            standing = 0;
            advance();
            mark = line.course().get(state.nextMark);
        }
        if (turn != null) {
            if (!turning) {
                turning = ((HorseCartNavigation) horse.getNavigation()).turn(turn, SPEED);
                return;
            }
            if (!horse.getNavigation().isDone()) {
                return;
            }
            if (!((HorseCartNavigation) horse.getNavigation()).completedTurn()) {
                // A blocked/aborted turn never authorizes reversing through the cart.
                halt();
                return;
            }
            turn = null;
            turning = false;
        }
        if (mark.isStation() && state.near(level, mark, LineState.AT_STATION - 0.5)) {
            halt();
            dwelling = true;
            standing = 0;
            return;
        }
        if (!mark.isStation() && horse.position().distanceToSqr(mark.at().cell().getBottomCenter())
                < PASS_MARK * PASS_MARK) {
            advance();
            mark = line.course().get(state.nextMark);
        }
        Vec3 destination = mark.at().cell().getBottomCenter();
        if (mark.isStation()) {
            int behind = Math.floorMod(state.nextMark - state.direction, line.course().size());
            Vec3 approach = destination.subtract(line.course().get(behind).at().cell().getBottomCenter()).normalize();
            // The horse finishes ahead of the station; Redux keeps the cart about 2.6 blocks behind it.
            destination = destination.add(approach.scale(1.5));
        }
        navigate(BlockPos.containing(destination));
    }

    private void advance() {
        int next = state.nextMark + state.direction;
        if (line.loop()) {
            if (next == line.course().size()) {
                state.lapNumber++;
            }
            state.nextMark = Math.floorMod(next, line.course().size());
        } else {
            if (next < 0 || next >= line.course().size()) {
                int before = state.nextMark - state.direction;
                Vec3 forward = line.course().get(state.nextMark).at().cell().getBottomCenter()
                    .subtract(line.course().get(before).at().cell().getBottomCenter()).normalize();
                turn = turnaround(horse.position(), forward);
                state.direction = -state.direction;
                state.lapNumber++;
                next = state.nextMark + state.direction;
            }
            state.nextMark = next;
        }
        target = null;
    }

    // Redux follows the puller's position, not its heading. Reversing the navigation target in place leaves the
    // cart ahead of the horse. Three tangent arcs (60/300/60 degrees) return to the same point facing back,
    // with a 3.5-block radius throughout: the horse walks around, never through, its trailing cart. First
    // draw clear of the station's handlers and roadside furniture, then return along the same short runway.
    private static List<Vec3> turnaround(Vec3 start, Vec3 forward) {
        List<Vec3> points = new ArrayList<>();
        Vec3 heading = new Vec3(forward.x, 0, forward.z).normalize();
        for (int step = 1; step <= 8; step++) {
            points.add(start.add(heading.scale(step * .5)));
        }
        Vec3 at = points.getLast();
        double radius = 3.5;
        for (int degrees : new int[] {60, -300, 60}) {
            double sign = Math.signum(degrees);
            Vec3 normal = new Vec3(-heading.z, 0, heading.x);
            Vec3 centre = at.add(normal.scale(radius * sign));
            Vec3 radial = at.subtract(centre);
            int steps = Math.abs(degrees) / 5;
            for (int step = 1; step <= steps; step++) {
                double angle = Math.toRadians(degrees * (double) step / steps);
                points.add(centre.add(rotate(radial, angle)));
            }
            at = points.getLast();
            heading = rotate(heading, Math.toRadians(degrees));
        }
        for (int step = 7; step >= 0; step--) {
            points.add(start.add(forward.scale(step * .5)));
        }
        return points;
    }

    private static Vec3 rotate(Vec3 v, double angle) {
        return new Vec3(v.x * Math.cos(angle) - v.z * Math.sin(angle), 0,
            v.x * Math.sin(angle) + v.z * Math.cos(angle));
    }

    private void navigate(BlockPos to) {
        if (to.equals(target) && !horse.getNavigation().isDone()) {
            return;
        }
        if (to.equals(target) && level.getGameTime() < retry) {
            return;
        }
        target = to;
        retry = level.getGameTime() + 20;
        var path = horse.getNavigation().createPath(to, 0);
        if (path != null && path.canReach()) {
            horse.getNavigation().moveTo(path, SPEED);
        }
    }

    private void halt() {
        horse.getNavigation().stop();
        horse.getMoveControl().setWantedPosition(horse.getX(), horse.getY(), horse.getZ(), 0);
        horse.setSpeed(0);
        horse.setZza(0);
        horse.setDeltaMovement(Vec3.ZERO);
    }

    void release() {
        halt();
        state.cart(level).filter(cart -> cart.getPulling() == horse).ifPresent(cart -> cart.setPulling(null));
        horse.goalSelector.removeGoal(this);
        MobControls.navigation(horse, previous);
        horse.setNoAi(noAi);
    }
}
