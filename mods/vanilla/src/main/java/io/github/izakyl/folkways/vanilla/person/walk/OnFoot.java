package io.github.izakyl.folkways.vanilla.person.walk;

import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.resident.api.Conveyance;
import io.github.izakyl.folkways.core.resident.api.Doing;
import io.github.izakyl.folkways.core.resident.api.Doings;
import io.github.izakyl.folkways.core.resident.api.Going;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.vocation.api.Vocations;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkExertion;
import io.github.izakyl.folkways.core.work.support.WorkStances;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.Structures;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.vanilla.build.SiteTravel;
import io.github.izakyl.folkways.vanilla.damagecontrol.DamageControlContent;
import io.github.izakyl.folkways.vanilla.patrol.PatrolContent;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
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
    private final SiteTravel siteTravel = new SiteTravel();
    private int settling;
    private int walkingTicks;
    private double closest = Double.MAX_VALUE;
    private int sinceProgress;
    private Float walked;
    private double distanceWalked;
    private int activeWalkingTicks;

    public OnFoot(double speed, RefusalKind lost) {
        this.speed = speed;
        this.lost = lost;
    }

    @Override
    public Going step(ServerLevel level, Mob body, Set<WorldPos> to) {
        measure(body);
        Set<BlockPos> cells = here(level, body, to);
        if (cells.isEmpty()) {
            return new Going.Failed(lost);
        }
        if (WorkStances.standingIn(body, new Stances.Cells(allowed(level, body, to)))) {
            return Going.ARRIVED;
        }
        // Only dimension-local work areas permit relocation; aboard coordinates keep their usual travel.
        if (to.stream().allMatch(cell -> cell.realm() instanceof Realm.Dimension)
                && permitsRelocation(body) && siteTravel.toward(level, body, cells)) {
            closest = Double.MAX_VALUE;
            sinceProgress = 0;
            settling = 0;
            repath(body, cells);
            return WorkStances.standingIn(body, new Stances.Cells(allowed(level, body, to))) ? Going.ARRIVED : Going.UNDERWAY;
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
        siteTravel.reset();
        walked = body.walkDist;
        distanceWalked = 0;
        settling = 0;
        walkingTicks = 0;
        activeWalkingTicks = 0;
        closest = Double.MAX_VALUE;
        sinceProgress = 0;
        repath(body, here(level, body, to));
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
        if (activeWalkingTicks > 0) {
            Bodies.of(body).ifPresent(resident ->
                resident.doing().map(doing -> doing.toward().orElse(doing.what()).getPath()).ifPresent(doing -> {
                    ResourceLocation trade = switch (doing) {
                        case "fetching", "stowing", "picking_up" -> Vocations.HAULING;
                        case "patrolling" -> PatrolContent.VOCATION;
                        default -> null;
                    };
                    if (trade != null) {
                        Bodies.worked(resident,
                            Vocations.required(trade), activeWalkingTicks, level.random);
                    }
                }));
        }
        activeWalkingTicks = 0;
        body.getNavigation().stop();
    }

    private static boolean permitsRelocation(Mob body) {
        // Suppression includes picking up and carrying a connected hose; both legs must be walked.
        return Bodies.of(body).flatMap(resident -> resident.doing()).map(doing ->
            !doing.what().equals(DamageControlContent.EXTINGUISHING)
                && doing.toward().filter(DamageControlContent.EXTINGUISHING::equals).isEmpty()).orElse(true);
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
            activeWalkingTicks++;
        }
    }

    private static Set<WorldPos> allowed(ServerLevel level, Mob body, Set<WorldPos> cells) {
        Set<WorldPos> safe = new LinkedHashSet<>();
        for (WorldPos cell : cells) {
            if (WorldSpaces.storage(level, cell).filter(feet -> Breathing.canStay(body, feet)).isPresent()) {
                safe.add(cell);
            }
        }
        return safe;
    }

    private static Set<BlockPos> here(ServerLevel level, Mob body, Set<WorldPos> cells) {
        Set<BlockPos> standing = new LinkedHashSet<>(cells.size());
        for (WorldPos cell : cells) {
            WorldSpaces.storage(level, cell).filter(feet -> Breathing.canStay(body, feet)).ifPresent(standing::add);
        }
        return standing;
    }

    private boolean closingIn(Mob body, Set<WorldPos> to) {
        double near = apart(body, to);
        if (body.getMoveControl() instanceof ResidentMoveControl control && control.yielding()) {
            closest = near;
            sinceProgress = 0;
            return true;
        }
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
        Path path = to.isEmpty() ? null : body.getNavigation().createPath(to, 0);
        if (path != null) {
            body.getNavigation().moveTo(path, speed);
        }
    }
}
