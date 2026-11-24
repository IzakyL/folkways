package io.github.izakyl.folkways.vanilla.patrol;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.FireControl;
import io.github.izakyl.folkways.core.combat.api.SpottedTargets.Spot;
import io.github.izakyl.folkways.core.combat.api.WeaponAttack;
import io.github.izakyl.folkways.core.facility.api.Lease;
import io.github.izakyl.folkways.core.facility.api.Post;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Seating;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.combat.SpottedTargets;
import io.github.izakyl.folkways.vanilla.combat.WeaponStations;
import io.github.izakyl.folkways.vanilla.combat.weapon.ResidentWeapons;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** One runner owns the post, its actual weapon and any manned fixed weapon. */
final class SentryWatch implements Post.Station {
    private final SentryPosts posts;
    private final Colony colony;
    private final FireControl fireControl;
    private final UUID resident;
    private final UUID zone;
    private final Optional<UUID> slot;
    private final Optional<WorldPos> stationAnchor;
    private final ResourceKey<Level> dimension;
    private final Stances standing;
    private final UUID fireUnit = UUID.randomUUID();
    private Post post;
    private WeaponAttack weapon;
    private WeaponStations.Manning station;
    private String status = "returning";
    private Mob target;
    private Spot firingSpot;
    private Worker operator;
    private UUID weaponTarget;
    private Spot weaponSpot;

    SentryWatch(SentryPosts posts, Colony colony, UUID resident, UUID zone, ResourceKey<Level> dimension,
        Optional<WorldPos> stationAnchor, Stances standing) {
        this.posts = posts;
        this.colony = colony;
        fireControl = FireControl.of(colony);
        this.resident = resident;
        this.zone = zone;
        slot = posts.slot(zone, resident);
        this.stationAnchor = stationAnchor;
        this.dimension = dimension;
        this.standing = standing;
    }

    void post(Post post) {
        this.post = post;
    }

    Post post() {
        return post;
    }

    @Override
    public void describe(NodeSpec.Builder spec) {
        spec.vocation(PatrolContent.trade()).doing(PatrolContent.PATROLLING)
            .effort(new WorkEffort.PerTick(1.0D / 20.0D));
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
    public boolean admits(Mob body) {
        return body.getUUID().equals(resident) && (stationAnchor.isEmpty()
            || body.level() instanceof ServerLevel level && posts.zone(level, zone)
                .map(post -> posts.stations(level, post).stream().anyMatch(candidate -> candidate.admits(body)))
                .orElse(false));
    }

    boolean ready(ServerLevel level) {
        return posts.slot(zone, resident).equals(slot) && posts.valid(level, resident, zone)
            && stationAnchor.equals(posts.zone(level, zone).filter(StationPost.class::isInstance)
                .map(StationPost.class::cast).map(StationPost::anchor));
    }

    @Override
    public boolean present(ServerLevel level, Post place, Mob body) {
        if (standing instanceof Stances.Seat seat) {
            return posts.zone(level, zone).isPresent() && Seating.in(body, seat);
        }
        return posts.zone(level, zone).isPresent();
    }

    @Override
    public Optional<Lease> additionalLease(ServerLevel level, Worker worker) {
        return posts.zone(level, zone).stream().flatMap(post -> posts.stations(level, post).stream())
            .flatMap(candidate -> WeaponStations.lease(candidate, worker).stream()).findFirst();
    }

    @Override
    public void onDuty(ServerLevel level, Worker worker, List<Lease> leases) {
        operator = worker;
        posts.staffed(resident, this);
        if (station == null && !leases.isEmpty()) {
            stopWeapon();
            station = WeaponStations.manning(leases.getFirst());
        }
        status = "watching";
        SpottedTargets spots = SpottedTargets.of(colony);
        List<Mob> seen = spots.observe(worker.body());
        target = null;
        if (stationAnchor.isPresent() && station == null) {
            fireControl.remove(fireUnit);
            status = posts.zone(level, zone).filter(post -> !posts.stations(level, post).isEmpty()).isPresent()
                ? "station_busy" : "station_unavailable";
            stopWeapon();
            return;
        }
        assignTarget(level, worker, spots);
        for (Mob foe : seen) {
            double reach = station == null ? ResidentWeapons.rangedRange(worker, foe) : station.station().range();
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
            return;
        }
        status = "fighting";
        if (station != null) {
            status = switch (station.fire(spots, firingSpot, () -> ready(level) && present(level, post, worker.body()))) {
                case FIRED, COOLDOWN -> "fighting";
                case NO_AMMO -> "no_ammo";
                case BLOCKED -> "blocked";
                case STALE -> "watching";
                case UNAVAILABLE -> "station_unavailable";
            };
            return;
        }
        if (weaponTarget != null && !weaponTarget.equals(target.getUUID())) {
            stopWeapon();
        }
        weapon = ResidentWeapons.selectRanged(worker, target, weapon);
        if (weapon == null) {
            stopWeapon();
            status = "no_weapon";
            return;
        }
        boolean indirect = ResidentWeapons.rangedIndirect(worker, target);
        if (weapon.state() == WeaponAttack.State.READY) {
            Spot observation = firingSpot;
            Mob shotTarget = target;
            weaponTarget = shotTarget.getUUID();
            weaponSpot = observation;
            if (!weapon.spottedTarget(shotTarget, observation.position(),
                () -> ready(level) && present(level, post, worker.body())
                    && (!indirect && SpottedTargets.sees(worker.body(), shotTarget)
                    || spots.resolve(observation, worker.body()).isPresent()))) {
                stopWeapon();
                status = "no_weapon";
                return;
            }
            if (!weapon.fire(target)) {
                stopWeapon();
                status = "no_ammo";
                return;
            }
        }
        if (weaponSpot == null || spots.resolve(weaponSpot, worker.body()).isEmpty()
            && (indirect || !SpottedTargets.sees(worker.body(), target))) {
            stopWeapon();
        } else {
            weapon.tick();
        }
    }

    private void assignTarget(ServerLevel level, Worker worker, SpottedTargets spots) {
        Mob body = worker.body();
        WeaponStations.Station fixed = station == null ? null : station.station();
        Optional<Vec3> origin = fixed == null ? Optional.of(body.position()) : fixed.origin(level);
        if (origin.isEmpty()) {
            fireControl.remove(fireUnit);
            return;
        }
        FireControl.Unit unit = new FireControl.Unit(level.dimension(), origin.get(), body.getLookAngle(),
            fixed == null ? 20 : fixed.cycleTicks(), fixed == null ? 4 : fixed.damage(),
            fixed == null || fixed.loaded(level, worker), spot -> reach(worker, spots, fixed, spot));
        fireControl.register(fireUnit, unit, level.getServer().overworld().getGameTime());
        fireControl.assigned(fireUnit, level.getServer()).map(FireControl.Assignment::target)
            .flatMap(spot -> spots.get(body, spot.target()))
            .filter(spot -> reach(worker, spots, fixed, spot)
                .filter(reach -> origin.get().distanceToSqr(spot.position()) <= reach.range() * reach.range())
                .isPresent())
            .ifPresent(spot -> {
                firingSpot = spot;
                target = spots.resolve(spot, body).orElseThrow();
            });
    }

    private Optional<FireControl.Reach> reach(Worker worker, SpottedTargets spots,
                                                     WeaponStations.Station fixed, Spot spot) {
        return spots.resolve(spot, worker.body()).flatMap(foe -> {
            double range = fixed == null ? ResidentWeapons.rangedRange(worker, foe) : fixed.range();
            boolean indirect = fixed == null ? ResidentWeapons.rangedIndirect(worker, foe) : fixed.indirect();
            boolean planned = fixed == null ? ResidentWeapons.planned(colony, worker, foe, true)
                : fixed.planned((ServerLevel) worker.body().level(), colony, worker.body(), foe);
            return planned && range > 0 && (indirect || worker.body().hasLineOfSight(foe))
                ? Optional.of(new FireControl.Reach(range, indirect)) : Optional.empty();
        });
    }

    boolean stillStaffed(ServerLevel level) {
        return operator != null && ready(level) && present(level, post, operator.body())
            && (station == null || station.lease().open() && station.station().valid(level)
                && posts.zone(level, zone).map(post -> posts.stations(level, post).contains(station.station()))
                    .orElse(false));
    }

    private void stopWeapon() {
        if (weapon != null) {
            weapon.stop();
            ResidentWeapons.holster(operator);
            weapon = null;
        }
        weaponTarget = null;
        weaponSpot = null;
    }

    @Override
    public void leave(Mob body) {
        fireControl.remove(fireUnit);
        stopWeapon();
        station = null;
        operator = null;
        target = null;
        status = "returning";
        posts.ended(resident, this);
    }
}
