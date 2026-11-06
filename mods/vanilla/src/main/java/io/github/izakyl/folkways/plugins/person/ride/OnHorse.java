package io.github.izakyl.folkways.plugins.person.ride;

import io.github.izakyl.folkways.core.api.resident.Conveyance;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.resident.body.Bodies;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.RefusalKind;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.terms.WorldSpaces;
import io.github.izakyl.folkways.plugins.combat.weapon.ResidentMounts;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.plugins.person.walk.OnFoot;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

final class OnHorse implements Conveyance {
    private enum Stage { FETCH, RIDE, PARK, WALK }
    private final double speed;
    private final RefusalKind lost;
    private Stage stage = Stage.WALK;
    private OnFoot walking;
    private AbstractHorse horse;
    private int ticks;
    private Vec3 last;
    private Path parkingPath;
    private int parkingRetries;
    private Going going = Going.UNDERWAY;

    OnHorse(double speed, RefusalKind lost) {
        this.speed = speed;
        this.lost = lost;
    }

    @Override
    public void setOut(ServerLevel level, Mob body, Set<WorldPos> to) {
        last = null;
        ticks = 0;
        parkingPath = null;
        parkingRetries = 0;
        horse = ResidentMounts.horse(body).filter(AbstractHorse::isSaddled).orElse(null);
        if (horse != null && body.getVehicle() == horse) {
            HorseNavigation.fit(horse);
            stage = Stage.RIDE;
            return;
        }
        boolean patrolling = Bodies.of(body).flatMap(who -> who.doing())
            .map(doing -> doing.toward().orElse(doing.what()).equals(PatrolContent.PATROLLING)).orElse(false);
        Set<BlockPos> goals = destinations(level, to);
        if (horse == null || goals.isEmpty()
                || !patrolling && goals.stream().anyMatch(cell -> cell.distToCenterSqr(body.position()) < 36)) {
            walk(level, body, to);
            return;
        }
        HorseNavigation.fit(horse);
        stage = Stage.FETCH;
        walking = new OnFoot(speed, lost);
        walking.setOut(level, body, around(level));
    }

    @Override
    public Going step(ServerLevel level, Mob body, Set<WorldPos> to) {
        going = advance(level, body, to);
        return going;
    }

    private Going advance(ServerLevel level, Mob body, Set<WorldPos> to) {
        if (stage != Stage.WALK && (horse == null || !horse.isAlive() || horse.isRemoved()
                || ResidentMounts.horse(body).filter(horse::equals).isEmpty())) {
            walk(level, body, to);
        }
        if (stage == Stage.WALK) {
            return walking.step(level, body, to);
        }
        if (stage == Stage.FETCH) {
            // Reaching a cell beside the horse is enough to get on; no second check of the distance.
            Going fetching = walking.step(level, body, around(level));
            if (fetching instanceof Going.Arrived) {
                // Once boarded, Mob.getNavigation forwards to the horse. Release the rider's actual FETCH
                // path first, or its native tick keeps sending the horse back toward the old boarding cell.
                walking.release(level, body);
                walking = null;
                if (ResidentMounts.board(body)) {
                    stage = Stage.RIDE;
                    last = null;
                    ticks = 0;
                } else {
                    walk(level, body, to);
                }
            } else if (!(fetching instanceof Going.Underway)) {
                walk(level, body, to);
            }
            return Going.UNDERWAY;
        }
        if (stage == Stage.PARK) {
            // A stopped navigation may be blocked. Only the path cursor completing means arrival.
            if (body.getVehicle() != horse || parkingPath.isDone()) {
                walk(level, body, to);
                return Going.UNDERWAY;
            }
            if (horse.getNavigation().isDone() || ++ticks > 100 && horse.position().distanceToSqr(last) < .25) {
                Path retry = parkingRetries++ < 2
                    ? HorseParking.reserve(level, horse, parkingPath, destinations(level, to)) : null;
                if (retry == null || !horse.getNavigation().moveTo(retry, speed)) {
                    walk(level, body, to);
                } else {
                    parkingPath = retry;
                    last = horse.position();
                    ticks = 0;
                }
                return Going.UNDERWAY;
            }
            if (ticks > 100) {
                last = horse.position();
                ticks = 0;
            }
            return Going.UNDERWAY;
        }
        if (body.getVehicle() == horse && destinations(level, to).stream()
                .anyMatch(cell -> Reach.standingAt(horse.position(), cell))) {
            return Going.ARRIVED;
        }
        if (stage == Stage.RIDE && last == null && body.getVehicle() == horse) {
            if (!horse.onGround()) {
                if (++ticks >= 40) {
                    walk(level, body, to);
                }
                return Going.UNDERWAY;
            }
            Path path = path(level, to);
            if (!useful(path, level, to) || !horse.getNavigation().moveTo(path, speed)) {
                walk(level, body, to);
                return Going.UNDERWAY;
            }
            last = horse.position();
            ticks = 0;
        }
        if (body.getVehicle() == horse && horse.getNavigation().isDone()) {
            Path onward = path(level, to);
            if (useful(onward, level, to) && horse.getNavigation().moveTo(onward, speed)) {
                last = horse.position();
                ticks = 0;
                return Going.UNDERWAY;
            }
            walk(level, body, to);
            return Going.UNDERWAY;
        }
        if (body.getVehicle() != horse || ++ticks > 100 && horse.position().distanceToSqr(last) < .25) {
            walk(level, body, to);
            return Going.UNDERWAY;
        }
        if (ticks > 100) {
            last = horse.position();
            ticks = 0;
        }
        return Going.UNDERWAY;
    }

    private Set<WorldPos> around(ServerLevel level) {
        Set<WorldPos> cells = new LinkedHashSet<>();
        Reach.footingsAround(level, horse.blockPosition(), 2, 1).stream()
            .filter(cell -> cell.distToCenterSqr(horse.position()) >= 1)
            .forEach(cell -> cells.add(WorldPos.of(level, cell)));
        return cells;
    }

    private Path path(ServerLevel level, Set<WorldPos> to) {
        Path path = horse.getNavigation().createPath(destinations(level, to), 0);
        if (path != null && !path.canReach() && path.getNodeCount() > 2) {
            // Leave space to walk around a parked horse before a low or narrow opening.
            path.truncateNodes(path.getNodeCount() - 2);
        }
        return path;
    }

    private boolean useful(Path path, ServerLevel level, Set<WorldPos> to) {
        if (path == null || path.getEndNode() == null) {
            return false;
        }
        Vec3 end = Vec3.atBottomCenterOf(path.getEndNode().asBlockPos());
        double before = destinations(level, to).stream()
            .mapToDouble(cell -> Vec3.atBottomCenterOf(cell).distanceTo(horse.position())).min().orElse(0);
        double after = destinations(level, to).stream()
            .mapToDouble(cell -> Vec3.atBottomCenterOf(cell).distanceTo(end)).min().orElse(before);
        return path.canReach() || before - after >= 4;
    }

    private static Set<BlockPos> destinations(ServerLevel level, Set<WorldPos> to) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        to.forEach(cell -> WorldSpaces.storage(level, cell).ifPresent(cells::add));
        return cells;
    }

    private void walk(ServerLevel level, Mob body, Set<WorldPos> to) {
        if (horse != null) {
            HorseParking.release(horse);
        }
        if (walking != null && stage == Stage.FETCH) {
            walking.release(level, body);
        }
        if (stage == Stage.RIDE && horse != null && body.getVehicle() == horse && horse.isAlive()
                && horse.onGround() && ResidentMounts.horse(body).filter(horse::equals).isPresent()) {
            Path parking = HorseParking.reserve(level, horse, horse.getNavigation().getPath(), destinations(level, to));
            if (parking != null && horse.getNavigation().moveTo(parking, speed)) {
                stage = Stage.PARK;
                parkingPath = parking;
                parkingRetries = 0;
                last = horse.position();
                ticks = 0;
                return;
            }
        }
        ResidentMounts.dismount(body);
        if (horse != null && horse.getPassengers().isEmpty()) {
            ResidentMounts.park(horse);
        }
        stage = Stage.WALK;
        walking = new OnFoot(speed, lost);
        walking.setOut(level, body, to);
    }

    @Override
    public void release(ServerLevel level, Mob body) {
        if (horse != null) {
            HorseParking.release(horse);
        }
        if (walking != null && (stage == Stage.FETCH || stage == Stage.WALK)) {
            walking.release(level, body);
        }
        if (horse != null && body.getVehicle() == horse) {
            horse.getNavigation().stop();
        }
    }
}
