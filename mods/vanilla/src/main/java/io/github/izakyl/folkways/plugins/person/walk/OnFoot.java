package io.github.izakyl.folkways.plugins.person.walk;

import io.github.izakyl.folkways.core.api.resident.Conveyance;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.Realm;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.Structures;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.core.api.work.Doing;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkExertion;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

public final class OnFoot implements Conveyance {

    private static final int SETTLE_TICKS = 20;

    private static final int REPATH_TICKS = 5;

    private static final int NO_PROGRESS_TICKS = 200;

    private static final double PROGRESS_STEP = 0.5D;

    private final double speed;
    private final RefusalKind lost;
    private int settling;
    private int walkingTicks;
    private double closest = Double.MAX_VALUE;
    private int sinceProgress;
    private Float walked;
    private double distanceWalked;

    public OnFoot(double speed, RefusalKind lost) {
        this.speed = speed;
        this.lost = lost;
    }

    @Override
    public Going step(ServerLevel level, Mob body, Set<WorldPos> to) {
        measure(body);
        Set<BlockPos> cells = here(level, to);
        if (cells.isEmpty()) {
            return new Going.Failed(lost);
        }
        if (Reach.standingIn(body, new Stances.Cells(to))) {
            return Going.ARRIVED;
        }
        if (!closingIn(body, to)) {
            return new Going.Failed(lost);
        }
        if (++walkingTicks % 10 == 0 && to.stream().anyMatch(cell -> cell.realm() instanceof Realm.Dimension)
                && !Structures.obstacles(level,
                    body.getBoundingBox().inflate(4.0D)).isEmpty()) {
            repath(body, cells);
        }
        if (!body.getNavigation().isDone()) {
            settling = 0;
            return Going.UNDERWAY;
        }
        settling++;
        if ((settling - 1) % REPATH_TICKS == 0) {
            repath(body, cells);
            return Going.UNDERWAY;
        }
        if (settling >= SETTLE_TICKS) {
            return new Going.Failed(lost);
        }
        return Going.UNDERWAY;
    }

    @Override
    public void setOut(ServerLevel level, Mob body, Set<WorldPos> to) {
        walked = body.walkDist;
        distanceWalked = 0;
        settling = 0;
        walkingTicks = 0;
        closest = Double.MAX_VALUE;
        sinceProgress = 0;
        repath(body, here(level, to));
    }

    @Override
    public Optional<Doing> doing() {
        return Optional.of(Doing.open(Doings.WALKING));
    }

    @Override
    public void release(ServerLevel level, Mob body) {
        measure(body);
        walked = null;
        double distance = distanceWalked;
        distanceWalked = 0;
        WorkExertion.performed(body, 0, 0, distance);
        body.getNavigation().stop();
    }

    private void measure(Mob body) {
        if (walked == null) {
            return;
        }
        // Entity.move accumulates horizontal travel scaled by 0.6; teleport/setPos does not.
        double distance = (body.walkDist - walked) / 0.6D;
        walked = body.walkDist;
        if (distance > 0 && !body.isPassenger() && !body.isSleeping()) {
            distanceWalked += distance;
        }
    }

    private static Set<BlockPos> here(ServerLevel level, Set<WorldPos> cells) {
        Set<BlockPos> standing = new LinkedHashSet<>(cells.size());
        for (WorldPos cell : cells) {
            WorldSpaces.storage(level, cell).ifPresent(standing::add);
        }
        return standing;
    }

    private boolean closingIn(Mob body, Set<WorldPos> to) {
        double near = apart(body, to);
        if (near < closest - PROGRESS_STEP) {
            closest = near;
            sinceProgress = 0;
            return true;
        }
        return ++sinceProgress < NO_PROGRESS_TICKS;
    }

    private static double apart(Mob body, Set<WorldPos> to) {
        double near = Double.MAX_VALUE;
        for (WorldPos cell : to) {
            var feet = WorldSpaces.relative(body.level(), cell.realm(), body.position());
            if (feet.isPresent()) {
                near = Math.min(near, Vec3.atBottomCenterOf(cell.cell()).distanceTo(feet.get()));
            }
        }
        return near;
    }

    private void repath(Mob body, Set<BlockPos> to) {
        body.getNavigation().stop();
        Path path = body.getNavigation().createPath(to, 0);
        if (path != null) {
            body.getNavigation().moveTo(path, speed);
        }
    }
}
