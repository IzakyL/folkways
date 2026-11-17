package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import io.github.izakyl.folkways.core.world.api.Realm;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;
import net.minecraft.server.level.ServerLevel;

/** Read-only landing geometry; providers retain ownership of airport and airspace policy. */
public final class LandingSites {
    private static final List<Facilities> FACILITIES = new CopyOnWriteArrayList<>();
    private static final List<Provider> PROVIDERS = new CopyOnWriteArrayList<>();

    private LandingSites() {
    }

    public enum Kind { HELIPAD, RUNWAY_END }

    public record Airport(String id, String name, Optional<UUID> colony) {
    }

    public record Site(String id, String name, Kind kind, WorldPos position, float yaw,
                       double length, double width, List<Catapult> catapults, Airport airport) {
        public Site(String id, String name, Kind kind, WorldPos position, float yaw,
                    double length, double width, List<Catapult> catapults) {
            this(id, name, kind, position, yaw, length, width, catapults,
                new Airport(id, name, Optional.empty()));
        }

        public Site(String id, String name, Kind kind, WorldPos position, float yaw, double length, double width) {
            this(id, name, kind, position, yaw, length, width, List.of());
        }

        public Site {
            catapults = List.copyOf(catapults);
        }

        public Optional<Pose> pose(Level level) {
            Vec3 forward = Vec3.directionFromRotation(0, yaw);
            if (position.realm() instanceof Realm.Frame frame) {
                return WorldSpaces.frame(level, frame.structure()).map(space -> {
                    Vec3 local = Vec3.atBottomCenterOf(position.cell());
                    Vec3 world = space.toWorld(local);
                    return new Pose(world, space.toWorld(local.add(forward)).subtract(world).normalize(),
                        space.toWorld(local.add(0, 1, 0)).subtract(world).normalize(),
                        space.velocity(local), space.angularVelocity());
                });
            }
            return WorldSpaces.world(level, position).map(world ->
                new Pose(world, forward, new Vec3(0, 1, 0), Vec3.ZERO, Vec3.ZERO));
        }
    }

    /** An accessory of one runway end. Geometry is in the same realm as that end. */
    public record Catapult(WorldPos start, WorldPos controller, float yaw, int length, int power,
                           double shuttleExitSpeed) {
    }

    @FunctionalInterface
    public interface Facilities {
        List<Catapult> catapults(ServerLevel level, Site runway);
    }

    public static void registerFacilities(Facilities facilities) {
        FACILITIES.add(facilities);
    }

    /** Current world basis, blocks/tick and radians/tick; queried afresh rather than cached with geometry. */
    public record Pose(Vec3 position, Vec3 forward, Vec3 up, Vec3 velocity, Vec3 angularVelocity) {
    }

    @FunctionalInterface
    public interface Provider {
        List<Site> sites(ServerLevel level);
    }

    private static final List<BiPredicate<ServerLevel, String>> REGISTRIES
        = new CopyOnWriteArrayList<>();

    public static void registerAirports(BiPredicate<ServerLevel, String> registry) {
        REGISTRIES.add(registry);
    }

    public static boolean registered(ServerLevel level, String id) {
        return REGISTRIES.stream().anyMatch(registry -> registry.test(level, id))
            || in(level).stream().anyMatch(site -> site.airport().id().equals(id));
    }

    public static void register(Provider provider) {
        PROVIDERS.add(provider);
    }

    public static List<Site> in(ServerLevel level) {
        return PROVIDERS.stream().flatMap(provider -> provider.sites(level).stream()).map(site -> {
            if (site.kind() != Kind.RUNWAY_END) {
                return site;
            }
            var accessories = FACILITIES.stream().flatMap(provider -> provider.catapults(level, site).stream()).toList();
            return new Site(site.id(), site.name(), site.kind(), site.position(), site.yaw(),
                site.length(), site.width(), accessories, site.airport());
        }).toList();
    }
}
