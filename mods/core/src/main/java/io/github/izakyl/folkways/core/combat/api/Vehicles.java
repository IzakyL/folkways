package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.resident.api.Body;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

/** Optional vehicle integrations. All mutations run on the authoritative server thread. */
public final class Vehicles {
    public static final int MAX_POINTS = 16;
    public static final String RELEASE_IDLE = "folkways_core:release_idle";
    public static final String ALTITUDE = "folkways_core:altitude";
    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private Vehicles() {
    }

    public enum Role { DRIVER, GUNNER, AUXILIARY, PASSENGER }

    /** TAKE_OFF is internal: a movement order to a parked aircraft departs on its own. */
    public enum Action { MOVE, PATROL, STOP, ATTACK, ATTACK_MOVE, DECOY, SPRINT, TAKE_OFF, LAND, LOITER }

    /** How a vehicle moves, which decides the orders its command card offers. */
    public enum Airframe { GROUND, HELICOPTER, FIXED_WING }

    /** Height held above the terrain ahead; each airframe turns the band into blocks of its own. */
    public enum Altitude {
        LOW, CRUISE, HIGH;

        public static final Altitude DEFAULT = CRUISE;
    }

    public record Seat(int index, Role role, List<String> weapons, int selected, Optional<UUID> occupant) {
        public Seat {
            weapons = List.copyOf(weapons);
        }
    }

    public record Capabilities(boolean mobile, Airframe airframe, boolean armed, List<Action> actions) {
        public Capabilities {
            actions = List.copyOf(actions);
        }

        public boolean flying() {
            return airframe != Airframe.GROUND;
        }
    }

    /** The persisted altitude band of an aircraft; Cruise until one is chosen. */
    public static Altitude altitude(Entity entity) {
        String saved = entity.getPersistentData().getString(ALTITUDE);
        for (Altitude band : Altitude.values()) {
            if (band.name().equals(saved)) {
                return band;
            }
        }
        return Altitude.DEFAULT;
    }

    public static void altitude(Entity entity, Altitude band) {
        if (band == Altitude.DEFAULT) {
            entity.getPersistentData().remove(ALTITUDE);
        } else {
            entity.getPersistentData().putString(ALTITUDE, band.name());
        }
    }

    public record Target(UUID id, int entityId, Component name) {
        public static Target of(Entity entity) {
            return new Target(entity.getUUID(), entity.getId(), entity.getDisplayName());
        }
    }

    public record Firing(int seat, Optional<Target> target, boolean firing) {
    }

    /** Full accepted route, current destination index and optional loop entry. */
    public record Route(List<WorldPos> points, int current, int cycleStart) {
        public static final Route EMPTY = new Route(List.of(), -1, -1);

        public Route {
            points = List.copyOf(points);
        }
    }

    /** Ammunition keys are seatIndex/weaponName; action is a translation key. */
    public record Status(double health, double maxHealth, long energy, long maxEnergy,
                         Map<String, Integer> ammunition, String action, Route route,
                         Optional<Target> target, List<Firing> firing, Optional<RunwayNeeds> runway) {
        public Status(double health, double maxHealth, long energy, long maxEnergy,
                      Map<String, Integer> ammunition, String action, Route route,
                      Optional<Target> target, List<Firing> firing) {
            this(health, maxHealth, energy, maxEnergy, ammunition, action, route, target, firing, Optional.empty());
        }

        public Status(double health, double maxHealth, long energy, long maxEnergy,
                      Map<String, Integer> ammunition, String action) {
            this(health, maxHealth, energy, maxEnergy, ammunition, action, Route.EMPTY, Optional.empty(), List.of());
        }

        public Status {
            ammunition = Map.copyOf(ammunition);
            firing = List.copyOf(firing);
        }
    }

    /** Distances in blocks, speeds in blocks/tick. Catapults never reduce landing distance. */
    public record RunwayNeeds(double takeoff, double landing, double width, double liftSpeed,
                              double acceleration, double margin, double towSlack) {
        public RunwayNeeds(double takeoff, double landing, double width, double liftSpeed,
                           double acceleration, double margin) {
            this(takeoff, landing, width, liftSpeed, acceleration, margin, 0);
        }

        public double launchSpeed(LandingSites.Catapult catapult) {
            double shuttle = .5;
            double shuttleSpeed = 0;
            double plane = shuttle - towSlack;
            double planeSpeed = 0;
            for (int tick = 0; tick < 10000 && catapult.power() > 0; tick++) {
                plane += planeSpeed;
                shuttleSpeed *= .8;
                shuttle += shuttleSpeed;
                if (shuttle >= catapult.length()) {
                    return Math.max(0, planeSpeed);
                }
                if (shuttleSpeed < .6 * catapult.power()) {
                    shuttleSpeed += catapult.power() / 50.0;
                }
                double gap = shuttle - plane - towSlack;
                if (gap > 0) {
                    double limit = Math.max(2, shuttleSpeed);
                    planeSpeed += Math.clamp((gap - planeSpeed + shuttleSpeed) / 2, -limit, limit);
                }
            }
            return 0;
        }

        public double takeoff(LandingSites.Site site) {
            return Math.min(takeoff, site.catapults().stream().mapToDouble(catapult -> {
                double offset = catapult.start().cell().getCenter().subtract(site.position().cell().getCenter())
                    .dot(net.minecraft.world.phys.Vec3.directionFromRotation(0, site.yaw()));
                return offset + catapult.length() + takeoff(launchSpeed(catapult));
            }).min().orElse(takeoff));
        }

        public double takeoff(double launchSpeed) {
            double speed = Math.min(liftSpeed, Math.max(0, launchSpeed));
            double distance = margin;
            while (speed < liftSpeed) {
                speed = Math.min(liftSpeed, speed + acceleration);
                distance += speed;
            }
            return Math.ceil(distance);
        }

        public Component refusal(LandingSites.Site site, boolean departing, double launchSpeed) {
            double length = departing ? (launchSpeed > 0 ? takeoff(launchSpeed) : takeoff(site)) : landing;
            double shortfall = Math.max(0, Math.ceil(length - site.length()));
            double narrow = Math.max(0, Math.ceil(width - site.width()));
            return shortfall == 0 && narrow == 0 ? Component.empty()
                : Component.translatable("combat.vehicle.runway_size", shortfall, narrow);
        }

        public boolean fits(LandingSites.Site site, boolean departing, double launchSpeed) {
            return site.kind() == LandingSites.Kind.RUNWAY_END && site.width() >= width
                && site.length() >= (departing ? (launchSpeed > 0 ? takeoff(launchSpeed) : takeoff(site)) : landing);
        }
    }

    public record Order(Action action, List<WorldPos> points, int cycleStart, Optional<UUID> target,
                        Optional<String> airport) {
        public Order {
            points = List.copyOf(points);
        }
    }

    /** A translation key is carried intact to the requesting client. */
    public record Result(boolean accepted, String key) {
        public static Result success() {
            return new Result(true, "combat.vehicle.accepted");
        }

        public static Result refused(String reason) {
            return new Result(false, "combat.vehicle." + reason);
        }
    }

    public record Readiness(UUID profile, ResourceLocation type, List<Integer> stock, List<Integer> missingSeats) {
        public Readiness {
            stock = List.copyOf(stock);
            missingSeats = List.copyOf(missingSeats);
        }
    }

    public interface Provider {
        default ResourceLocation type(Entity entity) {
            return BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        }

        default Optional<Readiness> readiness(Entity entity, Colony colony) {
            return Optional.empty();
        }

        default Map<String, WeaponRules.Rule> rules(Entity entity) {
            return WeaponRules.read(entity.getPersistentData().getCompound(WeaponRules.TAG));
        }

        boolean accepts(Entity entity);

        List<Seat> seats(Entity entity);

        default boolean ownsSeat(Body body) {
            return false;
        }

        Capabilities capabilities(Entity entity);

        Optional<UUID> owner(Entity entity);

        default boolean locked(Entity entity) {
            return false;
        }

        default Result releaseOwnership(Entity entity) {
            return Result.refused("unsupported");
        }

        Result board(Entity entity, Body body, int seat);

        default Result boardAvailable(Entity entity, Colony colony,
                                      List<Body> selected) {
            return Result.refused("unsupported");
        }

        Result leave(Entity entity, int seat);

        Result order(Entity entity, Order order);

        default Result appendPoint(Entity entity, WorldPos point, boolean attackMove) {
            return Result.refused("unsupported");
        }

        default Result cycle(Entity entity, int start) {
            return Result.refused("unsupported");
        }

        Result selectWeapon(Entity entity, int seat, int index);

        Status status(Entity entity);

        default Map<String, Component> weapons(Entity entity) {
            var names = new LinkedHashMap<String, Component>();
            seats(entity).forEach(seat -> seat.weapons().forEach(weapon ->
                names.put(seat.index() + "/" + weapon, Component.literal(weapon))));
            return Map.copyOf(names);
        }
    }

    /** Unclaimed hulls must already be sent to this client by the server entity tracker. */
    public static boolean availableTo(net.minecraft.server.level.ServerPlayer player, UUID colony, Entity entity) {
        return entity.isAlive() && entity.level() == player.level()
            && of(entity).filter(provider -> !provider.locked(entity)
                && provider.owner(entity).map(colony::equals).orElseGet(() ->
                    player.serverLevel().getChunkSource().chunkMap.getPlayersWatching(entity).contains(player)))
                .isPresent();
    }

    public static void register(Provider provider) {
        PROVIDERS.add(provider);
    }

    public static Optional<Provider> of(Entity entity) {
        return PROVIDERS.stream().filter(provider -> provider.accepts(entity)).findFirst();
    }
}
