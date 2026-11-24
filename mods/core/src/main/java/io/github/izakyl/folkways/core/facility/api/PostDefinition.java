package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.control.PostKey;
import io.github.izakyl.folkways.core.work.api.BlockSeats;
import io.github.izakyl.folkways.core.work.api.Seating;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.Reach;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

/** A named footing, native seat entity or native seat block in a facility definition. */
public record PostDefinition(Place stance, Set<String> controls, Post.Staffing staffing) {

    public PostDefinition {
        controls = Set.copyOf(controls);
    }

    public sealed interface Place {
    }

    public record Footing(BlockPos offset) implements Place {
        public Footing {
            offset = offset.immutable();
        }
    }

    public record SeatEntity(UUID entity, int seat) implements Place {
    }

    public record SeatBlock(BlockPos offset) implements Place {
        public SeatBlock {
            offset = offset.immutable();
        }
    }

    Optional<Post> resolve(ServerLevel level, FacilityDefinition facility, String name, Set<ControlRef> refs) {
        var key = new PostKey(DefinedFacilities.ID, facility.id(), name);
        var demand = new Post.Demand(staffing);
        if (stance instanceof SeatEntity seat) {
            var entity = level.getEntity(seat.entity());
            if (entity == null || !entity.isAlive()) {
                return Optional.empty();
            }
            return Optional.of(new Post(key, new WorkSite.AtSeat(seat.entity(), seat.seat(), WorldSpaces.at(entity)),
                new Stances.Seat(seat.entity(), seat.seat()), refs::contains, demand));
        }
        BlockPos offset = stance instanceof Footing footing ? footing.offset() : ((SeatBlock) stance).offset();
        var at = facility.anchor().at(facility.anchor().cell().offset(offset));
        if (!at.in(level) || !level.isLoaded(at.block(level))) {
            return Optional.empty();
        }
        var site = new WorkSite.AtBlock(at);
        if (stance instanceof PostDefinition.Footing) {
            return io.github.izakyl.folkways.core.world.api.Footing.withFeetAt(level, at.block(level))
                ? Optional.of(new Post(key, site, Stances.at(at), refs::contains, demand)) : Optional.empty();
        }
        return atSeatBlock(level, at, key, refs::contains, demand);
    }

    public static Optional<Post> atSeatBlock(ServerLevel level, WorldPos at, PostKey key,
            Predicate<ControlRef> covers, Post.Demand demand) {
        if (!at.in(level)) {
            return Optional.empty();
        }
        var cell = at.block(level);
        var seats = BlockSeats.at(level, cell).orElse(null);
        if (seats == null) {
            return Optional.empty();
        }
        return Stances.of(level, Reach.workableCells(level, cell)).map(footings ->
            new Post(key, new WorkSite.AtBlock(at), footings, covers, demand, new Post.Station() {
                @Override
                public Set<WorldPos> reachFrom(ServerLevel world, Post post) {
                    return Set.of(at);
                }

                @Override
                public boolean take(ServerLevel world, Post post, Mob body) {
                    if (!present(world, post, body) && !seats.sit(world, cell, body)) {
                        return false;
                    }
                    return Seating.sit(world, body, new Stances.Seat(body.getVehicle().getUUID(), 0));
                }

                @Override
                public boolean present(ServerLevel world, Post post, Mob body) {
                    return seats.sitting(world, cell).filter(body.getUUID()::equals).isPresent();
                }

                @Override
                public void leave(Mob body) {
                    if (present(level, null, body)) {
                        Seating.rise(body);
                    }
                }
            }));
    }
}
