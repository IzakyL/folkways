package io.github.izakyl.folkways.plugins.rail.domain;

import io.github.izakyl.folkways.core.api.terms.Reach;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

public final class TransitSnapshot {

    private static final TransitSnapshot EMPTY = new TransitSnapshot(List.of(), 0L);

    public record Vacancy(UUID train, SeatRef seat, WorldPos at, boolean forward) {
    }

    // firstDeparture is the count of departures the timetable's first stop leaves at; each stop after it one more.
    public record Line(UUID train, List<String> stations, Map<String, WorldPos> platforms,
                      Map<String, Stances.Cells> footings, List<TransitNetwork.Stop> timetable,
                      Optional<String> currentStation, List<SeatRef> freeSeats,
                      List<Vacancy> vacancies, boolean conducted, TrainCrew crew, long firstDeparture) {

        public Line(UUID train, List<String> stations, Map<String, WorldPos> platforms,
                    Map<String, Stances.Cells> footings, List<TransitNetwork.Stop> timetable,
                    Optional<String> currentStation, List<SeatRef> freeSeats,
                    List<Vacancy> vacancies, boolean conducted, TrainCrew crew) {
            this(train, stations, platforms, footings, timetable, currentStation, freeSeats, vacancies, conducted,
                crew, 1L);
        }

        public Line {
            stations = List.copyOf(stations);
            platforms = Map.copyOf(platforms);
            footings = Map.copyOf(footings);
            timetable = List.copyOf(timetable);
            freeSeats = List.copyOf(freeSeats);
            vacancies = List.copyOf(vacancies);
        }
    }

    private final List<Line> lines;

    private final long capturedAt;

    private TransitSnapshot(List<Line> lines, long capturedAt) {
        this.lines = lines;
        this.capturedAt = capturedAt;
    }

    public static TransitSnapshot empty() {
        return EMPTY;
    }

    public long capturedAt() {
        return capturedAt;
    }

    // The platforms last read at each berth, kept by whoever captures so a platform whose ground is unloaded for now
    // stays as it was instead of dropping out of the network.
    public static TransitSnapshot capture(MinecraftServer server, Set<UUID> ours, Map<Berth, Stances.Cells> read) {
        TransitNetwork network = TransitNetworks.get();
        if (!network.isAvailable()) {
            return EMPTY;
        }
        long now = server.overworld().getGameTime();
        List<Line> lines = new ArrayList<>();
        Map<Berth, Optional<Stances.Cells>> footings = new LinkedHashMap<>();
        for (UUID train : network.trains(server)) {
            if (!ours.contains(train)) {
                continue;
            }
            if (!network.isServiceable(server, train)) {
                continue;
            }
            List<String> stations = network.scheduledStations(server, train);
            if (stations.isEmpty()) {
                continue;
            }
            lines.add(read(network, server, train, stations, footings, read));
        }
        return lines.isEmpty() ? EMPTY : new TransitSnapshot(List.copyOf(lines), now);
    }

    private static Line read(TransitNetwork network, MinecraftServer server, UUID train,
            List<String> stations, Map<Berth, Optional<Stances.Cells>> known, Map<Berth, Stances.Cells> read) {
        boolean conducted = false;
        List<Vacancy> vacancies = new ArrayList<>();
        List<String> drivers = new ArrayList<>();
        for (boolean forward : new boolean[] {true, false}) {
            boolean driven = network.hasConductor(server, train, forward);
            conducted |= driven;
            for (SeatRef seat : network.conductorSeats(server, train, forward)) {
                Optional<UUID> occupant = network.occupant(server, seat);
                if (occupant.isPresent()) {
                    nameOf(network, server, seat, occupant.get())
                        .filter(name -> !drivers.contains(name))
                        .ifPresent(drivers::add);
                    continue;
                }
                if (driven) {
                    continue;
                }
                network.seatPosition(server, seat)
                    .ifPresent(at -> vacancies.add(new Vacancy(train, seat, at, forward)));
            }
        }
        List<SeatRef> freeSeats = new ArrayList<>();
        List<SeatRef> passengerSeats = network.passengerSeats(server, train);
        for (SeatRef seat : passengerSeats) {
            if (network.occupant(server, seat).isEmpty()) {
                freeSeats.add(seat);
            }
        }
        Optional<TimetableFault> fault = network.fault(server, train);
        TrainCrew crew = new TrainCrew(drivers, passengerSeats.size() - freeSeats.size(), passengerSeats.size(),
            fault);
        Optional<String> current = network.currentStation(server, train);
        Map<String, WorldPos> platforms = new LinkedHashMap<>();
        Map<String, Stances.Cells> footings = new LinkedHashMap<>();
        for (String station : stations) {
            network.stationPosition(server, train, station).ifPresent(pos -> {
                platforms.put(station, pos);
                network.berth(server, train, station)
                    .flatMap(berth -> known.computeIfAbsent(berth, where -> standingRoom(server, where, read)))
                    .ifPresent(cells -> footings.put(station, cells));
            });
        }
        List<TransitNetwork.Stop> timetable =
            conducted && fault.isEmpty() ? network.timetable(server, train) : List.of();
        return new Line(train, stations, platforms, footings, timetable, current, freeSeats, vacancies,
            conducted, crew, network.nextDeparture(server, train));
    }

    public List<Line> lines() {
        return lines;
    }

    public String describe() {
        StringBuilder said = new StringBuilder("rail snapshot lines=").append(lines.size());
        for (Line line : lines) {
            said.append(" [conducted=").append(line.conducted())
                .append(" stations=").append(line.stations().size())
                .append(" timetable=").append(spell(line.timetable()))
                .append(" from=").append(line.firstDeparture())
                .append(line.crew().fault().map(fault -> " fault=" + fault.key() + ":" + fault.stop()).orElse(""))
                .append(" footings=").append(footingCounts(line))
                .append(" freeSeats=").append(line.freeSeats().size())
                .append(" at=").append(line.currentStation().orElse("-"))
                .append(']');
        }
        return said.toString();
    }

    private static Map<String, String> footingCounts(Line line) {
        Map<String, String> counts = new LinkedHashMap<>();
        line.footings().forEach((station, cells) -> {
            int[] box = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (WorldPos cell : cells.cells()) {
                box[0] = Math.min(box[0], cell.cell().getX());
                box[1] = Math.min(box[1], cell.cell().getY());
                box[2] = Math.min(box[2], cell.cell().getZ());
                box[3] = Math.max(box[3], cell.cell().getX());
                box[4] = Math.max(box[4], cell.cell().getY());
                box[5] = Math.max(box[5], cell.cell().getZ());
            }
            counts.put(station, cells.cells().size() + "@" + box[0] + "," + box[1] + "," + box[2] + ".."
                + box[3] + "," + box[4] + "," + box[5]);
        });
        return counts;
    }

    private static String spell(List<TransitNetwork.Stop> timetable) {
        StringBuilder said = new StringBuilder("[");
        for (TransitNetwork.Stop stop : timetable) {
            if (said.length() > 1) {
                said.append(", ");
            }
            said.append(stop.station()).append('@').append(stop.arrivesIn()).append('-').append(stop.leavesIn());
        }
        return said.append(']').toString();
    }

    private static Optional<Stances.Cells> standingRoom(MinecraftServer server, Berth berth,
            Map<Berth, Stances.Cells> read) {
        Optional<ServerLevel> level = berth.at().level(server);
        if (level.isEmpty() || !level.get().isLoaded(berth.at().cell())) {
            return Optional.ofNullable(read.get(berth));
        }
        Optional<Stances.Cells> cells = Boarding.platform(level.get(), berth);
        cells.ifPresentOrElse(found -> read.put(berth, found), () -> read.remove(berth));
        return cells;
    }

    private static Optional<String> nameOf(TransitNetwork network, MinecraftServer server, SeatRef seat,
            UUID occupant) {
        return network.seatPosition(server, seat)
            .flatMap(at -> at.level(server))
            .map(level -> level.getEntity(occupant))
            .filter(rider -> !(rider instanceof Player))
            .map(rider -> rider.getName().getString());
    }
}
