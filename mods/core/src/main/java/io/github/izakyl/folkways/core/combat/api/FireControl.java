package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyContext;
import io.github.izakyl.folkways.core.combat.api.SpottedTargets.Spot;
import io.github.izakyl.folkways.solve.Engagements;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Colony fire control. Sources and live unit snapshots are used on the server thread. */
public final class FireControl {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("folkways_core", "fire_control");
    public static final int PERIOD = 10;
    public static final int FOCUS_TICKS = 100;
    private static final double CLUSTER_RADIUS_SQUARED = 16;

    @FunctionalInterface
    public interface Source {

        Collection<Contact> contacts(Colony colony, MinecraftServer server);
    }

    public record Contact(Spot spot, double health, double threat, int priority) {
    }

    public record Reach(double range, boolean indirect) {
    }

    /** Reach may depend on the target, for example when a resident can choose between several weapons. */
    public record Unit(ResourceKey<Level> dimension, Vec3 origin, Vec3 facing, int cycleTicks, double damage,
                       boolean loaded, Function<Spot, Optional<Reach>> reach) {
    }

    public record Assignment(Spot target, long until) {
    }

    private record Key(ResourceKey<Level> dimension, UUID target) {

        Key(Spot spot) {
            this(spot.dimension(), spot.target());
        }
    }

    private record Mark(Contact contact, long until) {
    }

    private record Registration(Unit unit, long until) {
    }

    private static final Map<ResourceLocation, Source> SOURCES = new LinkedHashMap<>();
    private final Colony colony;
    private final Map<UUID, Registration> units = new LinkedHashMap<>();
    private final Map<Key, Mark> marks = new LinkedHashMap<>();
    private final Map<UUID, Assignment> assignments = new HashMap<>();
    private long next;

    private FireControl(Colony colony) {
        this.colony = colony;
    }

    public static void install(ColonyContext scope) {
        FireControl service = new FireControl(scope.colony());
        scope.share(service);
        scope.tick(service::tick);
    }

    public static FireControl of(Colony colony) {
        return colony.service(ID, FireControl.class).orElseThrow();
    }

    public static Runnable registerSource(ResourceLocation id, Source source) {
        if (SOURCES.putIfAbsent(id, source) != null) {
            throw new IllegalArgumentException("fire control source already registered: " + id);
        }
        return () -> SOURCES.remove(id, source);
    }

    /** A mark uses the same time base as unit leases (overworld game time). */
    public void mark(Contact contact, long until) {
        marks.put(new Key(contact.spot()), new Mark(contact, until));
    }

    public static Contact contact(LivingEntity entity, long now) {
        return new Contact(new Spot(entity.getUUID(), entity.getId(), entity.level().dimension(),
            entity.position(), now), entity.getHealth(), 1, 0);
    }

    public void unmark(Spot spot) {
        marks.remove(new Key(spot));
    }

    /** Each active unit refreshes its snapshot at least once per period. */
    public void register(UUID id, Unit unit, long now) {
        units.put(id, new Registration(unit, now + 2 * PERIOD));
    }

    public void remove(UUID id) {
        units.remove(id);
        assignments.remove(id);
        if (units.isEmpty()) {
            assignments.clear();
            next = 0;
        }
    }

    public Optional<Assignment> assigned(UUID id, MinecraftServer server) {
        tick(server);
        long now = server.overworld().getGameTime();
        return Optional.ofNullable(assignments.get(id))
            .filter(assignment -> units.containsKey(id) && now < assignment.until());
    }

    private void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        if (now < next) {
            return;
        }
        units.values().removeIf(entry -> now >= entry.until());
        marks.values().removeIf(mark -> now >= mark.until());
        if (units.isEmpty()) {
            assignments.clear();
            next = 0;
            return;
        }
        next = now + PERIOD;
        Map<Key, Contact> situation = new LinkedHashMap<>();
        for (Source source : SOURCES.values()) {
            for (Contact contact : source.contacts(colony, server)) {
                situation.merge(new Key(contact.spot()), contact, FireControl::newer);
            }
        }
        marks.forEach((key, mark) -> situation.merge(key, mark.contact(), FireControl::newer));
        Map<Key, Integer> neighbors = new HashMap<>();
        situation.forEach((key, contact) -> neighbors.put(key, (int) situation.values().stream()
            .filter(other -> other != contact && other.spot().dimension().equals(key.dimension()))
            .filter(other -> other.spot().position().distanceToSqr(contact.spot().position()) <= CLUSTER_RADIUS_SQUARED)
            .count()));
        List<Engagements.Unit<UUID, Key>> snapshots = units.entrySet().stream()
            .map(entry -> snapshot(entry.getKey(), entry.getValue().unit(), situation, neighbors)).toList();
        assignments.clear();
        Engagements.allocate(snapshots).forEach((id, key) ->
            assignments.put(id, new Assignment(situation.get(key).spot(), next)));
    }

    private static Contact newer(Contact first, Contact second) {
        return second.spot().tick() > first.spot().tick() ? second : first;
    }

    private Engagements.Unit<UUID, Key> snapshot(UUID id, Unit unit, Map<Key, Contact> situation,
                                                Map<Key, Integer> neighbors) {
        List<Engagements.Candidate<Key>> candidates = situation.entrySet().stream()
            .filter(entry -> entry.getKey().dimension().equals(unit.dimension()))
            .map(entry -> candidate(unit, entry.getKey(), entry.getValue(), neighbors.get(entry.getKey())))
            .flatMap(Optional::stream).toList();
        return new Engagements.Unit<>(id, unit.loaded(), unit.cycleTicks(), unit.damage(), candidates);
    }

    private Optional<Engagements.Candidate<Key>> candidate(Unit unit, Key key, Contact contact, int neighbors) {
        Vec3 offset = contact.spot().position().subtract(unit.origin());
        double distance = offset.lengthSqr();
        return unit.reach().apply(contact.spot())
            .filter(reach -> reach.range() > 0 && distance <= reach.range() * reach.range())
            .map(reach -> new Engagements.Candidate<>(
                new Engagements.Target<>(key, contact.health(), contact.threat(), contact.priority(),
                    marks.containsKey(key)),
                distance, unit.facing().dot(offset.normalize()), neighbors, reach.indirect()));
    }
}
