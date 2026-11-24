package io.github.izakyl.folkways.vanilla.create.rail.domain;

import io.github.izakyl.folkways.core.facility.api.Controls;
import io.github.izakyl.folkways.core.facility.api.Post;
import io.github.izakyl.folkways.core.facility.api.control.PostKey;
import io.github.izakyl.folkways.core.resident.api.Bodies;
import io.github.izakyl.folkways.core.shell.create.rail.domain.Boarding;
import io.github.izakyl.folkways.core.shell.create.rail.domain.SeatRef;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitNetwork;
import io.github.izakyl.folkways.core.shell.create.rail.domain.TransitNetworks;
import io.github.izakyl.folkways.core.transport.api.TrainControl;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.WorkEffort;
import io.github.izakyl.folkways.core.work.api.Worker;
import io.github.izakyl.folkways.core.work.api.Xp;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

final class ConductorSeats {

    static Collection<Cab> occupiedCabs(MinecraftServer server, TransitNetwork network, UUID train, UUID colony) {
        Map<SeatRef, Cab> occupied = new LinkedHashMap<>();
        for (boolean forward : new boolean[] {true, false}) {
            for (SeatRef seat : network.conductorSeats(server, train, forward)) {
                if (occupied.containsKey(seat)) {
                    continue;
                }
                Optional<UUID> occupant = network.occupant(server, seat);
                if (occupant.isEmpty()) {
                    continue;
                }
                network.seatPosition(server, seat).ifPresent(at -> at.level(server)
                    .map(level -> level.getEntity(occupant.get()))
                    .filter(Entity::isAlive)
                    .filter(entity -> !(entity instanceof Player))
                    .flatMap(Bodies::of)
                    .filter(body -> body.colonyId().filter(colony::equals).isPresent())
                    .ifPresent(body -> occupied.put(seat,
                        new Cab(train, seat, forward, at, Stances.WHEREVER, Optional.of(body.id())))));
            }
        }
        return occupied.values();
    }

    static Post post(ServerLevel level, Cab cab) {
        SeatRef seat = cab.seat();
        return new Post(new PostKey(RailContent.ID, cab.train(), seat.toString()), seat.site(cab.at()),
            new Stances.Seat(seat.vehicleId(), seat.seatIndex()), ref -> Controls.resolve(level, ref)
                .filter(control -> control instanceof TrainControl train && train.belongsToTrain(cab.train()))
                .isPresent(),
            new Post.Demand(Post.Staffing.STANDING), new Conductor(cab, Boarding.seatTicks()));
    }

    private record Conductor(Cab cab, int seatTicks) implements Post.Station {

        @Override
        public void describe(NodeSpec.Builder spec) {
            spec.vocation(RailContent.trade()).effort(new WorkEffort.PerTick(1.0D / 20.0D)).doing(RailContent.DRIVING);
        }

        @Override
        public Stances approach(Post post) {
            return cab.stances();
        }

        @Override
        public Optional<UUID> worker() {
            return cab.driver();
        }

        @Override
        public double boardingTicks(Worker worker) {
            return cab.driver().isPresent() ? 0 : seatTicks / RailContent.haste(worker);
        }

        @Override
        public Set<WorldPos> reachFrom(ServerLevel level, Post post) {
            return TransitNetworks.get().seatPosition(level.getServer(), cab.seat()).map(Set::of).orElse(Set.of());
        }

        @Override
        public boolean available(ServerLevel level, Post post) {
            var network = TransitNetworks.get();
            return network.seatPosition(level.getServer(), cab.seat()).isPresent()
                && network.occupant(level.getServer(), cab.seat()).equals(cab.driver());
        }

        @Override
        public boolean admits(Mob body) {
            return cab.driver().isEmpty() || cab.driver().filter(body.getUUID()::equals).isPresent();
        }

        @Override
        public boolean take(ServerLevel level, Post post, Mob body) {
            return present(level, post, body) || TransitNetworks.get().board(level.getServer(), cab.seat(), body);
        }

        @Override
        public boolean present(ServerLevel level, Post post, Mob body) {
            return TransitNetworks.get().occupant(level.getServer(), cab.seat()).filter(body.getUUID()::equals).isPresent();
        }

        @Override
        public void leave(Mob body) {
            if (body.level() instanceof ServerLevel level && present(level, null, body)) {
                TransitNetworks.get().disembark(level.getServer(), cab.seat(), body);
            }
        }

        @Override
        public Outcome finish(Worker worker, boolean staffed, boolean demanded) {
            if (staffed) {
                return new Outcome.Done(List.of(), List.of(), Optional.of(new Xp(RailContent.trade(), 1)));
            }
            return demanded ? Outcome.failed(ConductorRefusal.SEAT_TAKEN) : Outcome.done();
        }
    }
}
