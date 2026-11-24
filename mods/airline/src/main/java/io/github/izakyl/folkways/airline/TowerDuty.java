package io.github.izakyl.folkways.airline;

import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.Post;
import io.github.izakyl.folkways.core.facility.api.Posts;
import io.github.izakyl.folkways.core.facility.api.control.PostKey;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;

final class TowerDuty {
    private TowerDuty() {
    }

    static Optional<Colony> colony(ServerLevel level, BlockPos tower) {
        return Colonies.all(level.getServer()).stream()
            .filter(colony -> colony.claimAt(WorldSpaces.at(level, tower))
                .filter(holding -> holding.owner().equals(AirportColony.TOWER)).isPresent()).findFirst();
    }

    static BlockPos seat(ServerLevel level, BlockPos tower) {
        return tower.relative(level.getBlockState(tower).getValue(AirportTowerBlock.FACING));
    }

    static List<Post> posts(ServerLevel level, Colony colony) {
        return colony.claims(AirportColony.TOWER).stream()
            .flatMap(holding -> holding.what().singleCell().stream())
            .flatMap(at -> WorldSpaces.storage(level, at).stream())
            .flatMap(pos -> post(level, pos, colony.id()).stream())
            .toList();
    }

    private static PostKey key(ServerLevel level, BlockPos tower, UUID colony) {
        return new PostKey(AirportColony.ID, colony, WorldSpaces.at(level, tower).toString());
    }

    private static Optional<Post> post(ServerLevel level, BlockPos tower, UUID owner) {
        if (!Airports.validTower(level, tower)) {
            return Optional.empty();
        }
        BlockPos seat = seat(level, tower);
        if (!level.hasChunkAt(seat) || !(level.getBlockState(seat).getBlock() instanceof SeatBlock)) {
            return Optional.empty();
        }
        return Stances.of(level, List.of(seat.north(), seat.east(), seat.south(), seat.west()))
            .map(approach -> new Post(key(level, tower, owner), WorkSite.at(level, seat), approach,
                ref -> false, new Post.Demand(Post.Staffing.STANDING), new TowerSeat(tower, seat, owner)));
    }

    static boolean staffed(ServerLevel level, BlockPos tower) {
        return colony(level, tower).flatMap(owner -> post(level, tower, owner.id())
            .flatMap(place -> Posts.of(owner).map(posts -> posts.staffed(level, place)))).orElse(false);
    }

    static TowerCoverage.Tower describe(ServerLevel level, BlockPos tower) {
        return new TowerCoverage.Tower(tower, level.getBlockState(tower).getValue(AirportTowerBlock.FACING),
            colony(level, tower).map(Colony::id).orElse(null), staffed(level, tower));
    }

    static List<TowerCoverage.Tower> group(ServerLevel level, BlockPos tower) {
        var colony = colony(level, tower);
        return colony.map(value -> value.claims(AirportColony.TOWER).stream()
                .flatMap(holding -> holding.what().singleCell().stream())
                .filter(at -> at.sameRealm(WorldSpaces.at(level, tower)))
                .flatMap(at -> WorldSpaces.storage(level, at).stream())
                .filter(pos -> !level.hasChunkAt(pos) || Airports.validTower(level, pos)).sorted(BlockPos::compareTo)
                .map(pos -> Airports.validTower(level, pos) ? describe(level, pos)
                    : AirportRegistry.of(level).description(pos).orElseGet(() ->
                        new TowerCoverage.Tower(pos, Direction.NORTH, value.id(), false)))
                .toList())
            .orElseGet(() -> List.of(describe(level, tower)));
    }

    static void reconcile(AirportTower tower, ServerLevel level) {
        BlockPos pos = tower.getBlockPos();
        var colony = colony(level, pos);
        if (!colony.map(Colony::id).equals(Optional.ofNullable(tower.dutyColony))) {
            withdraw(tower, level);
            tower.dutyColony = colony.map(Colony::id).orElse(null);
            AirportRegistry.of(level).changed(pos);
            Airports.request(level, pos);
        }
        tower.dutyWork = colony.flatMap(owner -> post(level, pos, owner.id())
            .flatMap(place -> Posts.of(owner).map(posts -> posts.offer(level, place)))).orElse(null);
    }

    static void withdraw(AirportTower tower, ServerLevel level) {
        UUID owner = tower.dutyColony;
        tower.dutyWork = null;
        tower.dutyColony = null;
        if (owner != null) {
            Colonies.of(level.getServer(), owner).flatMap(Posts::of)
                .ifPresent(posts -> posts.withdraw(key(level, tower.getBlockPos(), owner)));
        }
    }

    private record TowerSeat(BlockPos tower, BlockPos seat, UUID owner) implements Post.Station {

        @Override
        public Set<WorldPos> reachFrom(ServerLevel level, Post post) {
            return Set.of(WorldSpaces.at(level, seat));
        }

        @Override
        public boolean available(ServerLevel level, Post post) {
            return Airports.validTower(level, tower) && level.hasChunkAt(seat)
                && !SeatBlock.isSeatOccupied(level, seat);
        }

        @Override
        public boolean take(ServerLevel level, Post post, Mob body) {
            if (!body.isPassenger() && available(level, post)) {
                SeatBlock.sitDown(level, seat, body);
            }
            return present(level, post, body);
        }

        @Override
        public boolean present(ServerLevel level, Post post, Mob body) {
            return Airports.validTower(level, tower) && seat.equals(TowerDuty.seat(level, tower))
                && colony(level, tower).filter(value -> value.id().equals(owner)).isPresent()
                && body.getVehicle() instanceof SeatEntity entity && entity.blockPosition().equals(seat);
        }

        @Override
        public void leave(Mob body) {
            if (body.getVehicle() instanceof SeatEntity entity && entity.blockPosition().equals(seat)) {
                WorkGesture.SIT.release(body);
            }
        }
    }
}
