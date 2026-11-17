package io.github.izakyl.folkways.airline;

import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.contraptions.actors.seat.SeatEntity;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkGesture;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

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

    static boolean staffed(ServerLevel level, BlockPos tower) {
        if (!Airports.validTower(level, tower)) {
            return false;
        }
        var colony = colony(level, tower);
        BlockPos seat = seat(level, tower);
        if (colony.isEmpty() || !level.hasChunkAt(seat)
                || !(level.getBlockState(seat).getBlock() instanceof SeatBlock)) {
            return false;
        }
        return level.getEntitiesOfClass(SeatEntity.class, new AABB(seat)).stream()
            .flatMap(entity -> entity.getPassengers().stream())
            .anyMatch(body -> !(body instanceof Player) && body.isAlive()
                && colony.get().residents().stream().anyMatch(resident -> resident.id().equals(body.getUUID())));
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
            if (tower.dutyColony != null && tower.dutyWork != null) {
                Colonies.of(level.getServer(), tower.dutyColony)
                    .ifPresent(old -> old.withdraw(AirportColony.ID, tower.dutyWork));
            }
            tower.dutyColony = colony.map(Colony::id).orElse(null);
            tower.dutyWork = null;
            AirportRegistry.of(level).changed(pos);
            Airports.request(level, pos);
        }
        if (colony.isEmpty() || tower.dutyWork != null || !level.hasChunkAt(seat(level, pos))
                || !(level.getBlockState(seat(level, pos)).getBlock() instanceof SeatBlock)) {
            return;
        }
        UUID work = UUID.randomUUID();
        tower.dutyWork = work;
        colony.get().submit(AirportColony.ID, level.dimension(), Grown.of(new Watch(work, level, pos)),
            (id, ending) -> {
                if (work.equals(tower.dutyWork)) {
                    tower.dutyWork = null;
                }
            });
    }

    static void withdraw(AirportTower tower, ServerLevel level) {
        UUID work = tower.dutyWork;
        UUID colony = tower.dutyColony;
        tower.dutyWork = null;
        tower.dutyColony = null;
        if (work != null && colony != null) {
            Colonies.of(level.getServer(), colony).ifPresent(old -> old.withdraw(AirportColony.ID, work));
        }
    }

    private static final class Watch implements Node, Workload.Continuous {
        private final BlockPos tower;
        private final BlockPos seat;
        private final NodeSpec spec;

        private Watch(UUID id, ServerLevel level, BlockPos tower) {
            this.tower = tower;
            seat = seat(level, tower);
            Stances stances = Stances.of(level, List.of(seat.north(), seat.east(), seat.south(), seat.west()))
                .orElseThrow();
            spec = NodeSpec.of(id, AirportColony.ID, WorkSite.at(level, seat), stances, this)
                .gesture(new WorkGesture() {
                    @Override
                    public void perform(LivingEntity body, int tick, BlockPos at) {
                        if (!body.isPassenger() && ready(level) && !SeatBlock.isSeatOccupied(level, seat)) {
                            SeatBlock.sitDown(level, seat, body);
                        }
                    }

                    @Override
                    public void release(LivingEntity body) {
                        WorkGesture.SIT.release(body);
                    }
                }).done();
        }

        @Override
        public NodeSpec spec() {
            return spec;
        }

        @Override
        public boolean ready(ServerLevel level) {
            return Airports.validTower(level, tower) && level.hasChunkAt(seat) && seat.equals(seat(level, tower))
                && level.getBlockState(seat).getBlock() instanceof SeatBlock;
        }

        @Override
        public boolean holds(ServerLevel level, Worker who) {
            return ready(level) && colony(level, tower).map(colony -> colony.residents().stream()
                .anyMatch(resident -> resident.id().equals(who.resident().id()))).orElse(false)
                && who.body().getVehicle() instanceof SeatEntity entity && entity.blockPosition().equals(seat);
        }

        @Override
        public Outcome commit(ServerLevel level, Worker who) {
            return Outcome.done();
        }

        @Override
        public void released(ServerLevel level, Worker who, Ending how) {
            WorkGesture.SIT.release(who.body());
        }
    }
}
