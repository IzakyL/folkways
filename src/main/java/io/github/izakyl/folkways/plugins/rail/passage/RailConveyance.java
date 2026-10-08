package io.github.izakyl.folkways.plugins.rail.passage;

import io.github.izakyl.folkways.core.api.resident.Conveyance;
import io.github.izakyl.folkways.core.api.resident.Going;
import io.github.izakyl.folkways.core.api.resident.Headway;
import io.github.izakyl.folkways.core.api.terms.Footing;
import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.Structures;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Doing;
import io.github.izakyl.folkways.core.api.work.Doings;
import io.github.izakyl.folkways.plugins.rail.domain.Boarding;
import io.github.izakyl.folkways.plugins.rail.domain.RailContent;
import io.github.izakyl.folkways.plugins.rail.domain.RailRefusal;
import io.github.izakyl.folkways.plugins.rail.domain.SeatRef;
import io.github.izakyl.folkways.plugins.rail.domain.TransitNetwork;
import io.github.izakyl.folkways.plugins.rail.domain.TransitNetworks;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

final class RailConveyance implements Conveyance {

    private final RailLegs.Leg leg;

    private Set<BlockPos> onward = Set.of();

    private final Headway ridden = new Headway();

    private Doing doing = Doing.open(Doings.WAITING);

    private int seating;
    private boolean boarded;
    private boolean alighted;
    private SeatRef taking;

    RailConveyance(RailLegs.Leg leg) {
        this.leg = leg;
    }

    @Override
    public Going step(ServerLevel level, Mob body, Set<WorldPos> to) {
        if (alighted) {
            return Going.ARRIVED;
        }
        onward = cellsIn(level, to);
        MinecraftServer server = level.getServer();
        TransitNetwork network = TransitNetworks.get();
        if (!network.isAvailable() || !network.isServiceable(server, leg.train())) {
            return new Going.Failed(RailRefusal.SEAT_GONE);
        }
        if (body.getVehicle() != null) {
            return riding(level, network, body);
        }
        if (boarded) {
            return new Going.Failed(RailRefusal.SEAT_GONE);
        }
        return boarding(level, server, network, body);
    }

    private Going boarding(ServerLevel level, MinecraftServer server, TransitNetwork network, Mob body) {
        doing = Doing.open(Doings.WAITING);
        if (withdrawn(server, network)) {
            return new Going.Failed(RailRefusal.LINE_WITHDRAWN);
        }
        Set<BlockPos> boarding = cellsIn(level, leg.boarding().cells());
        boolean standing = Reach.stanceHolding(body.position(), boarding).isPresent();
        if (!standing) {
            stepBackOn(body, boarding);
        }
        if (network.currentStation(server, leg.train()).filter(leg.board()::equals).isEmpty()) {
            taking = null;
            seating = 0;
            return missed(server, network) ? new Going.Failed(RailRefusal.RUN_MISSED) : Going.UNDERWAY;
        }
        if (!standing) {
            return Going.UNDERWAY;
        }
        if (taking == null || network.occupant(server, taking).isPresent()) {
            taking = freeSeat(server, network);
            seating = 0;
        }
        if (taking == null) {
            return Going.UNDERWAY;
        }

        doing = Doing.along(RailContent.BOARDING, seating / (float) Math.max(1, Boarding.seatTicks()));
        if (++seating < Boarding.seatTicks()) {
            return Going.UNDERWAY;
        }
        if (network.board(server, taking, body)) {
            Ridership.setDestination(body, leg.alight());
            boarded = true;
        } else {
            taking = null;
            seating = 0;
        }
        return Going.UNDERWAY;
    }

    // The walk here ended standing on the boarding cells, but a crowded platform jostles a rider off them while it
    // waits, and nothing else brings it back: it steps back on to the nearest itself, or it would wait for good.
    private static void stepBackOn(Mob body, Set<BlockPos> boarding) {
        if (boarding.isEmpty() || !body.getNavigation().isDone()) {
            return;
        }
        Path path = body.getNavigation().createPath(boarding, 0);
        if (path != null) {
            body.getNavigation().moveTo(path, 1.0D);
        }
    }

    // The run the plan caught has pulled out without us: counted off the train's departures as they happen, so the
    // rider gives up the moment it goes, however late or early it ran against the timetable. A leg laid without a plan
    // behind it waits for the last run the timetable offers.
    private boolean missed(MinecraftServer server, TransitNetwork network) {
        OptionalLong run = leg.run().isPresent() ? leg.run() : leg.runs().lastDeparture(leg.board(), leg.alight());
        return run.isEmpty() || network.departures(server, leg.train()) >= run.getAsLong();
    }

    // Waiting only makes sense while the train still runs between our stops by a timetable, with someone to drive it.
    private boolean withdrawn(MinecraftServer server, TransitNetwork network) {
        UUID train = leg.train();
        List<String> stops = network.scheduledStations(server, train);
        return network.fault(server, train).isPresent()
            || !network.hasConductor(server, train, true) && !network.hasConductor(server, train, false)
            || !stops.contains(leg.board()) || !stops.contains(leg.alight());
    }

    private SeatRef freeSeat(MinecraftServer server, TransitNetwork network) {
        for (SeatRef seat : network.passengerSeats(server, leg.train())) {
            if (network.occupant(server, seat).isEmpty()) {
                return seat;
            }
        }
        return null;
    }

    private Going riding(ServerLevel level, TransitNetwork network, Mob body) {
        boarded = true;
        MinecraftServer server = level.getServer();
        carried(level, body);
        Optional<String> stopped = network.currentStation(server, leg.train());
        if (stopped.filter(leg.alight()::equals).isEmpty()) {
            return Going.UNDERWAY;
        }
        alight(level, network, body);
        return Going.UNDERWAY;
    }

    private void carried(ServerLevel level, Mob body) {
        Set<BlockPos> platform = cellsIn(level, leg.landing().cells());
        if (platform.isEmpty()) {
            doing = Doing.open(RailContent.RIDING);
            return;
        }
        double apart = Math.sqrt(towards(platform, body.blockPosition()));
        if (ridden.fraction().isEmpty()) {
            ridden.setOut(apart);
        } else {
            ridden.closer(apart);
        }
        doing = ridden.fraction()
            .map(done -> Doing.along(RailContent.RIDING, done))
            .orElseGet(() -> Doing.open(RailContent.RIDING));
    }

    @Override
    public Optional<Doing> doing() {
        return alighted ? Optional.empty() : Optional.of(doing);
    }

    private void alight(ServerLevel level, TransitNetwork network, Mob body) {
        MinecraftServer server = level.getServer();
        BlockPos landing = platformLanding(level);
        if (landing == null) {
            return;
        }
        seatOf(server, network, body).ifPresent(seat -> {
            network.disembark(server, seat, body);
            body.teleportTo(landing.getX() + 0.5D, landing.getY(), landing.getZ() + 0.5D);
            alighted = true;
            Ridership.clearDestination(body);
        });
    }

    private BlockPos platformLanding(ServerLevel level) {
        Set<BlockPos> landing = cellsIn(level, leg.landing().cells());
        if (landing.isEmpty()) {
            return null;
        }
        Set<BlockPos> goals = onward.isEmpty() ? landing : onward;
        List<AABB> obstacles = Structures.obstacles(level, alongThePlatform(landing));
        return landing.stream().filter(cell -> stillStandingRoom(level, obstacles, cell))
            .min(java.util.Comparator.<BlockPos>comparingDouble(cell -> towards(goals, cell))
                .thenComparingLong(BlockPos::asLong)).orElse(null);
    }

    private static double towards(Set<BlockPos> goals, BlockPos cell) {
        double nearest = Double.MAX_VALUE;
        for (BlockPos goal : goals) {
            nearest = Math.min(nearest, cell.distSqr(goal));
        }
        return nearest;
    }

    private static boolean stillStandingRoom(ServerLevel level, List<AABB> carriages, BlockPos cell) {
        return level.isLoaded(cell) && Footing.withFeetAt(level, cell)
            && !Boarding.underAStructure(carriages, cell)
            && !Boarding.underAStructure(carriages, cell.above());
    }

    private static AABB alongThePlatform(Set<BlockPos> landing) {
        AABB about = null;
        for (BlockPos cell : landing) {
            AABB one = new AABB(cell).inflate(1.0D);
            about = about == null ? one : about.minmax(one);
        }
        return about;
    }

    private static Set<BlockPos> cellsIn(ServerLevel level, Set<WorldPos> cells) {
        Set<BlockPos> here = new LinkedHashSet<>(cells.size());
        for (WorldPos cell : cells) {
            if (cell.in(level)) {
                here.add(cell.block(level));
            }
        }
        return here;
    }

    private Optional<SeatRef> seatOf(MinecraftServer server, TransitNetwork network, Mob body) {
        UUID id = body.getUUID();
        for (SeatRef seat : Ridership.allSeats(server, network, leg.train())) {
            if (network.occupant(server, seat).filter(id::equals).isPresent()) {
                return Optional.of(seat);
            }
        }
        return Optional.empty();
    }

    @Override
    public void release(ServerLevel level, Mob body) {
        if (body.getVehicle() == null) {
            return;
        }
        TransitNetwork network = TransitNetworks.get();
        if (network.isAvailable()) {
            alight(level, network, body);
        }
    }
}
