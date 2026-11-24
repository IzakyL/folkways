package io.github.izakyl.folkways.vanilla.combat;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.combat.api.FireControl;
import io.github.izakyl.folkways.core.combat.api.Gunnery;
import io.github.izakyl.folkways.core.combat.api.ShotPlanning;
import io.github.izakyl.folkways.core.combat.api.Trajectories;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities;
import io.github.izakyl.folkways.core.facility.api.DefinitionCodec;
import io.github.izakyl.folkways.core.facility.api.FacilityDefinition;
import io.github.izakyl.folkways.core.facility.api.FacilityObserver;
import io.github.izakyl.folkways.core.facility.api.TriggerSources;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.solve.Ballistics;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Enrolls defined facilities in colony fire control. A routine takes part by publishing an {@code engagement} reading
 * (as {@code @std//ballistics} does): the aim control whose {@code ballistics} reading describes the gun, and the flight
 * model, arc, range, damage and blast the routine fires with. Fire control assigns a contact only when that flight,
 * traced through the loaded world, ends at it and keeps clear of the colony.
 */
public final class FacilityEngagements implements FacilityObserver {

    private static final double MARGIN = .35;

    private FacilityEngagements() {
    }

    public static void install() {
        DefinedFacilities.observe(new FacilityEngagements(), FireControl.PERIOD);
        TriggerSources.register(FireControl.ID, (level, colony, facility, routine) ->
            FireControl.of(colony).assigned(facility.id(), level.getServer())
                .map(FireControl.Assignment::target)
                .filter(spot -> spot.dimension().equals(level.dimension()))
                .map(spot -> {
                    var position = spot.position();
                    return Map.of("target", spot.entity() ? new Value.Text(spot.target().toString())
                        : Value.of(position.x, position.y, position.z));
                }));
        FacilityScans.install();
    }

    @Override
    public void refresh(ServerLevel level, Colony colony, FacilityDefinition facility, long tick) {
        engagement(level, colony, facility).ifPresentOrElse(
            unit -> FireControl.of(colony).register(facility.id(), unit, tick), () -> removed(level, colony, facility));
    }

    private static Optional<FireControl.Unit> engagement(ServerLevel level, Colony colony,
                                                         FacilityDefinition facility) {
        Map<String, Reading> readings = facility.readings(level);
        for (String routine : facility.routines().keySet()) {
            var published = read(level, readings, routine + ".engagement");
            if (published.isEmpty()) {
                continue;
            }
            try {
                var engagement = published.get();
                String aim = (String) engagement.get("aim");
                var gun = read(level, readings, aim + ".ballistics").orElse(null);
                if (gun == null) {
                    continue;
                }
                var weapon = Gunnery.Weapon.of(gun);
                var model = Gunnery.model((Map<?, ?>) engagement.get("model"));
                boolean indirect = Boolean.TRUE.equals(engagement.get("indirect"));
                double range = Gunnery.number(engagement.get("range"), "range");
                double damage = Gunnery.number(engagement.get("damage"), "damage");
                double blast = Gunnery.number(engagement.get("blast"), "blast");
                if (range <= 0 || damage <= 0 || blast < 0) {
                    continue;
                }
                Vec3 origin = weapon.pivot();
                Vec3 facing = gun.containsKey("facing") ? Gunnery.vector(gun.get("facing"), "facing")
                    : new Vec3(0, 0, 1);
                int cycle = (int) Math.max(1, gun.containsKey("cycle") ? Gunnery.number(gun.get("cycle"), "cycle") : 20);
                boolean loaded = Boolean.TRUE.equals(gun.get("loaded"));
                Entity shooter = Optional.ofNullable(facility.resolve(level).get(aim))
                    .flatMap(control -> control.entityId()).map(level::getEntity).orElse(null);
                return Optional.of(new FireControl.Unit(level.dimension(), origin, facing, cycle, damage, loaded,
                    spot -> {
                        if (spot.position().distanceToSqr(origin) > range * range) {
                            return Optional.empty();
                        }
                        var entity = spot.entity() ? level.getEntity(spot.target()) : null;
                        Vec3 at = entity == null ? spot.position() : entity.getBoundingBox().getCenter();
                        return planned(level, colony, shooter, weapon, model, at, indirect, blast)
                            ? Optional.of(new FireControl.Reach(range, indirect)) : Optional.empty();
                    }));
            } catch (IllegalArgumentException | ClassCastException | NullPointerException malformed) {
                // A routine that publishes no usable engagement takes no part in fire control.
            }
        }
        return Optional.empty();
    }

    private static Optional<Map<?, ?>> read(ServerLevel level, Map<String, Reading> readings, String name) {
        return Optional.ofNullable(readings.get(name)).flatMap(reading -> reading.read(level))
            .map(DefinitionCodec::value).filter(Map.class::isInstance).map(value -> (Map<?, ?>) value);
    }

    /** Whether the shot lands on the target through the loaded world and keeps clear of the colony. */
    static boolean planned(ServerLevel level, Colony colony, Entity shooter, Gunnery.Weapon weapon,
                           Ballistics.Model model, Vec3 target, boolean indirect, double blast) {
        return Gunnery.aim(weapon, model, target, indirect)
            .map(angles -> Trajectories.trace(level, weapon.launch(model, angles[0], angles[1]), target))
            .filter(path -> path.endsAtMark())
            .filter(path -> ShotPlanning.avoidsColony(level, colony, shooter, path.points(), MARGIN, blast))
            .isPresent();
    }

    @Override
    public void removed(ServerLevel level, Colony colony, FacilityDefinition facility) {
        FireControl.of(colony).remove(facility.id());
    }
}
