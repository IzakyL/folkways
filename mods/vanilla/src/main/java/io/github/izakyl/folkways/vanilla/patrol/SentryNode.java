package io.github.izakyl.folkways.vanilla.patrol;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.combat.FireSafety;
import io.github.izakyl.folkways.vanilla.combat.SpottedTargets;
import io.github.izakyl.folkways.vanilla.combat.WeaponStations;
import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.vanilla.combat.weapon.Weapons;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

/** One runner owns the post, its actual weapon and any fixed-weapon lease. */
final class SentryNode implements Node {
    private final SentryPosts posts;
    private final Colony colony;
    private final UUID resident;
    private final UUID zone;
    private final Optional<UUID> slot;
    private final WorldPos at;
    private final Optional<WorldPos> stationAnchor;
    private final ResourceKey<Level> dimension;
    private final NodeSpec spec;
    private WeaponAttack weapon;
    private WeaponStations.Lease station;
    private boolean closed;
    private String status = "returning";
    private Mob target;
    private SpottedTargets.Spot firingSpot;
    private Worker operator;
    private UUID weaponTarget;
    private SpottedTargets.Spot weaponSpot;

    SentryNode(SentryPosts posts, Colony colony, UUID resident, UUID zone, WorldPos at, ResourceKey<Level> dimension,
        Optional<WorldPos> stationAnchor, Stances standing) {
        this.posts = posts;
        this.colony = colony;
        this.resident = resident;
        this.zone = zone;
        slot = posts.slot(zone, resident);
        this.at = at;
        this.stationAnchor = stationAnchor;
        this.dimension = dimension;
        Workload.Continuous watching = this::advance;
        spec = NodeSpec.of(UUID.randomUUID(), SentryPosts.ID, new WorkSite.AtBlock(at), standing, watching)
            .vocation(PatrolContent.trade()).doing(PatrolContent.PATROLLING).done();
    }

    @Override
    public boolean mountedWork() {
        return stationAnchor.isEmpty();
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    UUID zoneId() {
        return zone;
    }

    ResourceKey<Level> dimension() {
        return dimension;
    }

    String status() {
        return status;
    }

    Optional<Component> targetName() {
        return Optional.ofNullable(target).filter(Mob::isAlive).map(Mob::getDisplayName);
    }

    @Override
    public boolean directTravel() {
        return true;
    }

    @Override
    public boolean staysAtWorkSite() {
        return operator == null || !WeaponStations.ownsSeat(operator.body());
    }

    @Override
    public boolean ready(ServerLevel level) {
        return !closed && posts.slot(zone, resident).equals(slot) && posts.valid(level, resident, zone)
            && stationAnchor.equals(posts.zone(level, zone).filter(StationPost.class::isInstance)
                .map(StationPost.class::cast).map(StationPost::anchor));
    }

    private boolean inside(ServerLevel level, Worker worker) {
        return posts.zone(level, zone)
            .filter(
            post ->
                station != null
                    && WeaponStations.ownsSeat(worker.body())
                    && post.cells().stream()
                    .anyMatch(cell -> post.at(cell).equals(station.station().at()))
                    || posts.stances(level, post, worker.body()).stream()
                    .anyMatch(cell -> Reach.standingAt(Bodies.of(worker.body())
                        .map(body -> body.locomotion().feet(body.mob())).orElse(worker.body().position()), cell)))
            .isPresent();
    }

    private boolean advance(ServerLevel level, Worker worker) {
        if (!ready(level) || !inside(level, worker)) {
            close();
            return false;
        }
        operator = worker;
        maintain(level);
        status = "watching";
        SpottedTargets spots = SpottedTargets.of(colony);
        List<Mob> seen = spots.observe(worker.body());
        if (station == null) {
            for (WeaponStations.Station candidate : posts.stations(level, posts.zone(level, zone).orElseThrow())) {
                if (weapon != null) {
                    weapon.stop();
                    Weapons.holster(worker);
                    weapon = null;
                }
                station = WeaponStations.acquire(candidate, worker).orElse(null);
                if (station != null) {
                    break;
                }
                status = "station_busy";
            }
        }
        target = null;
        if (stationAnchor.isPresent() && station == null) {
            status = status.equals("station_busy") ? status : "station_unavailable";
            stopWeapon();
            return true;
        }
        for (SpottedTargets.Spot spot : spots.targets(worker.body())) {
            Mob foe = spots.resolve(spot, worker.body()).orElseThrow();
            double range = station == null ? Weapons.rangedRange(worker, foe) : station.station().range();
            boolean indirect = station == null ? Weapons.rangedIndirect(worker, foe) : station.station().indirect();
            boolean withinRange = station == null
                ? worker.body().position().distanceToSqr(spot.position()) <= range * range
                : station.station().reaches(level, spot.position());
            if (!withinRange || !indirect && !worker.body().hasLineOfSight(foe)) {
                continue;
            }
            target = foe;
            firingSpot = spot;
            break;
        }
        for (Mob foe : seen) {
            double reach = station == null ? Weapons.rangedRange(worker, foe) : station.station().range();
            boolean withinRange = station == null ? worker.body().distanceToSqr(foe) <= reach * reach
                : station.station().reaches(level, foe.position());
            if (!withinRange || worker.body().distanceToSqr(foe) <= 4.5 * 4.5) {
                colony.service(PatrolContent.ID, PatrolPresence.class)
                    .ifPresent(patrol -> patrol.call(worker.body(), foe));
            }
        }
        if (target == null) {
            stopWeapon();
            if (!status.equals("station_busy") && !status.equals("station_unavailable")) {
                status = "watching";
            }
            return true;
        }
        status = "fighting";
        if (station != null) {
            status = switch (station.fire(spots, firingSpot, () -> ready(level) && inside(level, worker))) {
                case FIRED, COOLDOWN -> "fighting";
                case NO_AMMO -> "no_ammo";
                case BLOCKED -> "blocked";
                case STALE -> "watching";
                case UNAVAILABLE, REFUSED -> "station_unavailable";
            };
            return true;
        }
        if (weaponTarget != null && !weaponTarget.equals(target.getUUID())) {
            stopWeapon();
        }
        weapon = Weapons.selectRanged(worker, target, weapon);
        if (weapon == null) {
            stopWeapon();
            status = "no_weapon";
            return true;
        }
        boolean indirect = Weapons.rangedIndirect(worker, target);
        if (!indirect && (!worker.body().hasLineOfSight(target) || !FireSafety.clear(worker.body(), target))) {
            stopWeapon();
            status = "blocked";
            return true;
        }
        if (weapon.state() == WeaponAttack.State.READY) {
            SpottedTargets.Spot observation = firingSpot;
            Mob shotTarget = target;
            weaponTarget = shotTarget.getUUID();
            weaponSpot = observation;
            if (!weapon.spottedTarget(shotTarget, observation.position(),
                () -> ready(level) && inside(level, worker)
                    && (!indirect && SpottedTargets.sees(worker.body(), shotTarget)
                    || spots.resolve(observation, worker.body()).isPresent()))) {
                stopWeapon();
                status = "no_weapon";
                return true;
            }
            if (!weapon.fire(target)) {
                stopWeapon();
                status = "no_ammo";
                return true;
            }
        }
        if (weaponSpot == null || spots.resolve(weaponSpot, worker.body()).isEmpty()
            && (indirect || !SpottedTargets.sees(worker.body(), target))) {
            stopWeapon();
        } else {
            weapon.tick();
        }
        return true;
    }

    void maintain(ServerLevel level) {
        if (operator == null) {
            return;
        }
        if (!inside(level, operator)) {
            releaseStation();
            stopWeapon();
            status = "returning";
        } else if (station != null && (!station.station().valid(level)
            || posts.zone(level, zone).map(post -> posts.stations(level, post).stream()
                .noneMatch(candidate -> candidate == station.station())).orElse(true))) {
            releaseStation();
        }
    }

    private void releaseStation() {
        if (station != null) {
            station.close();
            station = null;
        }
    }

    private void stopWeapon() {
        if (weapon != null) {
            weapon.stop();
            Weapons.holster(operator);
            weapon = null;
        }
        weaponTarget = null;
        weaponSpot = null;
    }

    @Override
    public Outcome commit(ServerLevel level, Worker worker) {
        return Outcome.done();
    }

    void close() {
        closed = true;
        stopWeapon();
        releaseStation();
    }

    @Override
    public void released(ServerLevel level, Worker worker, Ending ending) {
        close();
        posts.ended(resident, this);
    }
}
