package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.control.PostKey;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Seating;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Urge;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/** A resolved duty place. Its source decides what the occupant can operate and when the post is wanted. */
public record Post(PostKey key, WorkSite site, Stances stance, Predicate<ControlRef> covers, Demand demand,
                   Station station) {

    public Post(PostKey key, WorkSite site, Stances stance, Predicate<ControlRef> covers, Demand demand) {
        this(key, site, stance, covers, demand, Station.NATIVE);
    }

    public boolean covers(ServerLevel level, ControlRef ref) {
        if (covers.test(ref)) {
            return true;
        }
        var control = Controls.resolve(level, ref);
        if (control.isEmpty() || !ref.at().in(level) || !level.isLoaded(ref.at().block(level))) {
            return false;
        }
        if (control.get().stance(level).orElse(null) instanceof Stances.Seat seat) {
            return stance.equals(seat);
        }
        var target = ref.at().block(level);
        return station.reachFrom(level, this).stream().filter(at -> at.in(level))
            .map(at -> at.block(level))
            .anyMatch(feet -> Reach.withinReach(feet, target) && Reach.inSight(level, feet, target));
    }

    public enum Staffing {
        STANDING,
        ON_DEMAND
    }

    public record Demand(Staffing staffing, Urge.Priority priority, boolean urgent) {
        public Demand(Staffing staffing) {
            this(staffing, Urge.Priority.ORDINARY, false);
        }
    }

    public enum Boarding {
        SEATED,
        WAITING,
        REFUSED
    }

    /** Hooks for stations with native boarding or additional controls to retain. */
    public interface Station {

        Station NATIVE = new Station() { };

        default Set<WorldPos> reachFrom(ServerLevel level, Post post) {
            if (post.stance() instanceof Stances.Seat seat) {
                return Seating.position(level, seat).map(Set::of).orElse(Set.of());
            }
            return post.stance().cells();
        }

        default Object leaseKey(Post post) {
            return post.stance() instanceof Stances.Seat ? post.stance() : post.site();
        }

        default void describe(NodeSpec.Builder spec) {
        }

        default Stances approach(Post post) {
            return post.stance();
        }

        default Optional<UUID> worker() {
            return Optional.empty();
        }

        default double boardingTicks(Worker worker) {
            return 0;
        }

        default boolean directTravel() {
            return false;
        }

        default Optional<Lease> additionalLease(ServerLevel level, Worker worker) {
            return Optional.empty();
        }

        default void onDuty(ServerLevel level, Worker worker, List<Lease> leases) {
        }

        default boolean available(ServerLevel level, Post post) {
            return true;
        }

        default Outcome finish(Worker worker, boolean staffed, boolean demanded) {
            return demanded ? Outcome.failed(FacilityRefusal.POST_UNAVAILABLE) : Outcome.done();
        }

        default boolean admits(Mob body) {
            return true;
        }

        default boolean take(ServerLevel level, Post post, Mob body) {
            return present(level, post, body);
        }

        default Boarding board(ServerLevel level, Post post, Mob body) {
            return take(level, post, body) ? Boarding.SEATED : Boarding.REFUSED;
        }

        default boolean dismountOnRelease() {
            return true;
        }

        default boolean present(ServerLevel level, Post post, Mob body) {
            if (post.stance() instanceof Stances.Seat seat) {
                return Seating.in(body, seat);
            }
            return body.level() == level && body.isAlive() && !body.isRemoved();
        }

        default void leave(Mob body) {
        }

        default Set<ControlRef> retainedControls() {
            return Set.of();
        }
    }
}
