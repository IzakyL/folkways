package io.github.izakyl.folkways.plugins.combat;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaConfig;
import io.github.izakyl.folkways.plugins.combat.weapon.ObservationGear;
import io.github.izakyl.folkways.plugins.patrol.PatrolContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Transient observations. Only a successful sight report may refresh a position or its age. */
public final class SpottedTargets {
    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways", "spotted_targets");

    public record Spot(UUID target, int entityId, ResourceKey<Level> dimension, Vec3 position, long tick) {
    }

    private record Key(ResourceKey<Level> dimension, UUID target) {
    }

    private final Colony colony;
    private final Map<Key, Spot> spots = new LinkedHashMap<>();

    public SpottedTargets(Colony colony) {
        this.colony = colony;
    }

    public static SpottedTargets of(Colony colony) {
        return colony.service(ID, SpottedTargets.class).orElseThrow();
    }

    private record Vision(double base, double magnification) {
        double maximum() {
            return limited((base + FolkwaysVanillaConfig.sightHeightPerBlock()
                * FolkwaysVanillaConfig.sightHeightLimit()) * magnification);
        }

        double radius(Mob observer, Mob target) {
            double height = Mth.clamp(observer.getEyeY() - target.getEyeY(), 0,
                FolkwaysVanillaConfig.sightHeightLimit());
            double light = 1;
            if (!observer.hasEffect(MobEffects.NIGHT_VISION) && !target.isOnFire()
                && !target.isCurrentlyGlowing() && !target.getType().is(CombatTags.LUMINOUS_TARGETS)) {
                int brightness = target.level().getMaxLocalRawBrightness(target.blockPosition());
                int full = FolkwaysVanillaConfig.fullSightLightLevel();
                int dark = Math.min(FolkwaysVanillaConfig.darkSightLightLevel(), full - 1);
                double illumination = Mth.clamp((double) (brightness - dark) / (full - dark), 0, 1);
                light = Mth.lerp(illumination, FolkwaysVanillaConfig.darkSightMultiplier(), 1);
            }
            return limited((base + height * FolkwaysVanillaConfig.sightHeightPerBlock()) * light * magnification);
        }
    }

    private static Vision vision(Mob observer) {
        double base = FolkwaysVanillaConfig.defenderSightRadius()
            + Bodies.of(observer).map(body -> body.effects().value(PatrolContent.ALERT_RANGE)).orElse(0.0);
        double magnification = observer instanceof ResidentEntity body && ObservationGear.assigned(body)
            ? FolkwaysVanillaConfig.observationSightMultiplier() : 1;
        return new Vision(base, magnification);
    }

    private static double limited(double radius) {
        double maximum = FolkwaysVanillaConfig.maximumSightRadius();
        return Mth.clamp(radius, Math.min(FolkwaysVanillaConfig.minimumSightRadius(), maximum), maximum);
    }

    /** Brightest-target, greatest-height bound for coarse scans and UI; not a guaranteed detection radius. */
    public static double maximumSight(Mob observer) {
        return vision(observer).maximum();
    }

    public static double sight(Mob observer, Mob target) {
        return comparable(observer, target) ? vision(observer).radius(observer, target) : 0;
    }

    private static boolean comparable(Mob observer, Mob target) {
        return target.level() == observer.level() && observer.level().hasChunkAt(target.blockPosition());
    }

    public static boolean sees(Mob observer, Mob target) {
        return sees(observer, target, vision(observer));
    }

    private static boolean sees(Mob observer, Mob target, Vision vision) {
        if (!comparable(observer, target)) {
            return false;
        }
        double distance = observer.distanceToSqr(target);
        double maximum = vision.maximum();
        if (distance > maximum * maximum) {
            return false;
        }
        double radius = vision.radius(observer, target);
        return distance <= radius * radius && observer.hasLineOfSight(target);
    }

    private boolean member(Mob observer) {
        return Bodies.of(observer).flatMap(body -> body.colonyId()).filter(colony.id()::equals).isPresent()
            && colony.claimOf(observer.getUUID()).isPresent();
    }

    public List<Mob> observe(Mob observer) {
        if (!(observer.level() instanceof ServerLevel level)
            || !level.getServer().isSameThread() || !observer.isAlive() || observer.isRemoved()) {
            return List.of();
        }
        Vision vision = vision(observer);
        List<Mob> seen = level.getEntitiesOfClass(Mob.class, observer.getBoundingBox().inflate(vision.maximum()),
            target -> Threats.hostile(target, observer) && sees(observer, target, vision));
        if (member(observer)) {
            for (Mob target : seen) {
                remember(level, target);
            }
        }
        return seen;
    }

    public boolean report(Mob observer, Mob target) {
        if (!(observer.level() instanceof ServerLevel level)
            || !level.getServer().isSameThread()
            || !member(observer)
            || !Threats.hostile(target, observer)
            || !sees(observer, target)) {
            return false;
        }
        remember(level, target);
        return true;
    }

    private void remember(ServerLevel level, Mob target) {
        Key key = new Key(level.dimension(), target.getUUID());
        spots.remove(key);
        spots.put(key, new Spot(target.getUUID(), target.getId(), level.dimension(), target.position(),
            level.getGameTime()));
        while (spots.size() > FolkwaysVanillaConfig.maxSpottedTargets()) {
            spots.remove(spots.keySet().iterator().next());
        }
    }

    public Optional<Mob> resolve(Spot spot, Mob defender) {
        if (!(defender.level() instanceof ServerLevel level)
            || !spot.dimension().equals(level.dimension())
            || level.getGameTime() - spot.tick() >= FolkwaysVanillaConfig.spottedTargetTtl()
            || level.getGameTime() < spot.tick()
            || !(level.getEntity(spot.target()) instanceof Mob target)
            || target.getId() != spot.entityId()
            || !Threats.hostile(target, defender)
            || target.position().distanceToSqr(spot.position()) > 4) {
            return Optional.empty();
        }
        return Optional.of(target);
    }

    public Optional<Spot> get(Mob defender, UUID target) {
        Spot spot = spots.get(new Key(defender.level().dimension(), target));
        return spot != null && resolve(spot, defender).isPresent() ? Optional.of(spot) : Optional.empty();
    }

    public List<Spot> targets(Mob defender) {
        return spots.values().stream().filter(spot -> resolve(spot, defender).isPresent())
            .sorted(Comparator.comparingDouble(spot -> defender.position().distanceToSqr(spot.position())))
            .toList();
    }

    public void tick(MinecraftServer server) {
        for (Map.Entry<Key, Spot> entry : new ArrayList<>(spots.entrySet())) {
            Spot spot = entry.getValue();
            ServerLevel level = server.getLevel(spot.dimension());
            boolean valid = level != null
                && level.getGameTime() - spot.tick() < FolkwaysVanillaConfig.spottedTargetTtl()
                && level.getEntity(spot.target()) instanceof Mob target
                && target.isAlive() && !target.isRemoved()
                && colony.view(level).residents().stream()
                .map(resident -> level.getEntity(resident.id()))
                .filter(Mob.class::isInstance).map(Mob.class::cast)
                .anyMatch(observer -> Threats.hostile(target, observer));
            if (!valid) {
                spots.remove(entry.getKey());
            }
        }
    }

    public void clear() {
        spots.clear();
    }
}
