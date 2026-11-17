package io.github.izakyl.folkways.core.combat.api;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;

/** Transient, exclusive airport bookings. Call on the server thread. */
public final class AirportTraffic {
    private static Provider provider;

    private AirportTraffic() {
    }

    public enum Priority { COMBAT }

    public enum Kind { HELIPAD, RUNWAY, STAND }

    public enum State { WAITING, HELD, REVOKED, RELEASED }

    /** Inclusive surface bounds in one realm, stable as a moving frame changes its world pose. */
    public record Resource(Kind kind, WorldPos first, WorldPos last) {
        public Resource {
            if (!first.realm().equals(last.realm())) {
                throw new IllegalArgumentException("Resource bounds must share a realm");
            }
        }

        public boolean overlaps(Resource other) {
            var a = first.cell();
            var b = last.cell();
            var c = other.first.cell();
            var d = other.last.cell();
            return first.realm().equals(other.first.realm())
                && Math.min(a.getX(), b.getX()) <= Math.max(c.getX(), d.getX())
                && Math.max(a.getX(), b.getX()) >= Math.min(c.getX(), d.getX())
                && Math.min(a.getY(), b.getY()) <= Math.max(c.getY(), d.getY())
                && Math.max(a.getY(), b.getY()) >= Math.min(c.getY(), d.getY())
                && Math.min(a.getZ(), b.getZ()) <= Math.max(c.getZ(), d.getZ())
                && Math.max(a.getZ(), b.getZ()) >= Math.min(c.getZ(), d.getZ());
        }
    }

    public interface Claim extends AutoCloseable {
        State state();

        @Override
        void close();
    }

    public record Aircraft(UUID id, double length, double width, boolean departing) {
    }

    public interface Provider {
        Claim request(ServerLevel level, Priority priority, List<Resource> resources);

        List<Resource> resources(ServerLevel level, LandingSites.Site site);

        default Optional<List<Resource>> assignment(ServerLevel level, LandingSites.Site site, Aircraft aircraft) {
            return Optional.of(resources(level, site));
        }
    }

    public static void register(Provider authority) {
        if (provider != null) {
            throw new IllegalStateException("Airport traffic authority already registered");
        }
        provider = authority;
    }

    public static boolean available() {
        return provider != null;
    }

    public static Optional<Claim> assign(ServerLevel level, LandingSites.Site site, Aircraft aircraft) {
        return provider == null ? Optional.empty() : provider.assignment(level, site, aircraft)
            .flatMap(resources -> request(level, Priority.COMBAT, resources));
    }

    public static Optional<Claim> request(ServerLevel level, Priority priority, List<Resource> resources) {
        return provider == null ? Optional.empty() : Optional.of(provider.request(level, priority, resources));
    }

    public static Optional<Claim> request(ServerLevel level, Priority priority, LandingSites.Site site) {
        return provider == null ? Optional.empty()
            : request(level, priority, provider.resources(level, site));
    }
}
