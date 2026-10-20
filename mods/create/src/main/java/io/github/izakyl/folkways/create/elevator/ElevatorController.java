package io.github.izakyl.folkways.create.elevator;

import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.create.transport.PassengerLanding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Mob;

// One owner for the pulley target: passengers submit journeys, never individual calls to the pulley.
public final class ElevatorController {
    public enum Status { WAITING, BOARDING, RIDING, ALIGHTING, FAILED, FINISHED }

    private static final long LEASE = 200;
    private static final long STALL = 1200;
    private final ElevatorRef lift;
    private final ElevatorNetwork network;
    private final Map<UUID, Request> requests = new LinkedHashMap<>();
    private ElevatorNetwork.View observed;
    private final List<Request> batch = new ArrayList<>();
    private boolean retired;
    private long clock;
    private long boardingSince = -1;
    private long lastProgress;
    private double lastY = Double.NaN;
    private UUID vehicle;
    private long unavailableSince = -1;
    private Integer targeted;

    public ElevatorController(ElevatorRef lift, ElevatorNetwork network) {
        this.lift = lift;
        this.network = network;
    }

    public boolean request(UUID rider, int from, int to, long now) {
        Request previous = requests.get(rider);
        if (previous != null && live(previous)) {
            if (previous.from != from || previous.to != to || previous.cancelled) {
                return false;
            }
            previous.touched = now;
            return true;
        }
        if (retired || from == to || observed == null || !valid(from) || !valid(to)
                || observed.places().isEmpty() || !observed.powered()) {
            return false;
        }
        requests.put(rider, new Request(rider, from, to, now));
        return true;
    }

    public void touch(UUID rider, long now) {
        Request request = requests.get(rider);
        if (request != null) {
            request.touched = now;
        }
    }

    public Optional<ElevatorNetwork.View> view() { return Optional.ofNullable(observed); }

    public Status status(UUID rider) {
        Request request = requests.get(rider);
        return request == null ? Status.FAILED : request.status;
    }

    public Optional<Integer> place(UUID rider) {
        Request request = requests.get(rider);
        return request == null ? Optional.empty() : Optional.ofNullable(request.place);
    }

    public void exitThrough(UUID rider, Set<WorldPos> exits) {
        Request request = requests.get(rider);
        if (request != null && live(request) && !exits.isEmpty()) { request.exits = Set.copyOf(exits); }
    }

    public void tick(MinecraftServer server, long now) {
        clock = now;
        requests.values().removeIf(request -> !live(request) && now - request.touched > STALL);
        observed = network.inspect(server, lift).orElse(null);
        for (Request request : requests.values()) {
            if ((request.status == Status.WAITING || request.status == Status.BOARDING)
                    && now - request.touched > LEASE) {
                if (request.approaching == null) { fail(request); }
                else { request.cancelled = true; }
            }
        }
        batch.removeIf(request -> !live(request));
        if (observed == null || !observed.powered()) {
            if (unavailableSince < 0) {
                unavailableSince = now;
            }
            if (now - unavailableSince > LEASE) {
                requests.values().stream().filter(request -> request.body == null && live(request))
                    .forEach(this::fail);
            }
            if (observed == null) {
                return;
            }
        } else {
            unavailableSince = -1;
        }
        if (!observed.vehicle().equals(vehicle)) {
            // Reassembly invalidates reservations, and old passengers must not be attached to the new cabin.
            for (Request request : requests.values()) {
                if (request.body != null && !network.placeOf(server, lift, request.body).isPresent()) {
                    fail(request);
                }
            }
            vehicle = observed.vehicle();
            targeted = null;
            lastY = observed.contactY();
            lastProgress = now;
        }
        if (Double.isNaN(lastY) || Math.abs(observed.contactY() - lastY) > 0.001) {
            lastY = observed.contactY();
            lastProgress = now;
        }
        for (Request request : requests.values()) {
            if (!live(request)) {
                continue;
            }
            if (request.body == null && request.approaching != null
                    && (!request.approaching.isAlive()
                        || !request.approaching.level().dimension().equals(lift.dimension())
                        || network.placeOf(server, lift, request.approaching).isEmpty()
                            && !nearFloor(request.approaching, request.from, 36D))) {
                fail(request);
                continue;
            }
            if (request.body != null) {
                if (!request.body.isAlive() || network.placeOf(server, lift, request.body).isEmpty()
                        && (request.status != Status.ALIGHTING || !nearLanding(request))) {
                    fail(request);
                    continue;
                }
                if (!valid(request.to) || now - lastProgress > STALL) {
                    request.cancelled = true;
                    request.rescue = true;
                }
                if (request.cancelled) {
                    unloadCancelled(server, request);
                }
            } else if (request.cancelled && request.approaching != null) {
                withdraw(server, request);
            } else if (!valid(request.from) || !valid(request.to) || observed.places().isEmpty()
                    || ((!observed.powered() || now - lastProgress > STALL)
                        && now - request.created > LEASE)) {
                fail(request);
            }
        }
        batch.removeIf(request -> !live(request));
        if (batch.isEmpty()) {
            boardingSince = -1;
            Request first = requests.values().stream().filter(request -> request.status == Status.WAITING)
                .findFirst().orElse(null);
            if (first == null || !observed.powered()) {
                return;
            }
            Set<Integer> free = new LinkedHashSet<>(observed.freePlaces());
            for (Request request : requests.values()) {
                if (request.status == Status.WAITING && request.from == first.from && request.to == first.to
                        && !free.isEmpty()) {
                    request.place = free.iterator().next();
                    free.remove(request.place);
                    batch.add(request);
                }
            }
            lastProgress = now;
        }
        if (batch.isEmpty()) {
            return;
        }
        if (boardingSince >= 0 && stopped(batch.getFirst().from) && now - boardingSince < boardingWindow()
                && batch.stream().noneMatch(request -> request.status == Status.ALIGHTING)) {
            Set<Integer> reserved = new LinkedHashSet<>();
            batch.stream().map(request -> request.place).filter(java.util.Objects::nonNull).forEach(reserved::add);
            for (Request request : requests.values()) {
                if (request.status == Status.WAITING && !request.cancelled && !batch.contains(request)
                        && request.from == batch.getFirst().from && request.to == batch.getFirst().to) {
                    Integer free = observed.freePlaces().stream().filter(place -> !reserved.contains(place)).findFirst().orElse(null);
                    if (free == null) { break; }
                    request.place = free;
                    reserved.add(free);
                    batch.add(request);
                }
            }
        }
        boolean riding = batch.stream().anyMatch(request -> request.body != null);
        boolean unboarded = batch.stream().anyMatch(request -> request.body == null);
        int pickup = batch.getFirst().from;
        int destination = batch.getFirst().to;
        if (boardingSince < 0 && !riding) {
            target(server, pickup, now);
            if (!stopped(pickup)) {
                return;
            }
            boardingSince = now;
        }
        if (unboarded && boardingSince >= 0) {
            Set<Integer> reserved = new LinkedHashSet<>();
            batch.stream().filter(request -> request.body != null).forEach(request -> reserved.add(request.place));
            for (Request request : batch) {
                if (request.body != null) {
                    continue;
                }
                if (request.cancelled) { continue; }
                if (request.place == null || reserved.contains(request.place)
                        || !observed.freePlaces().contains(request.place)
                            && !network.occupant(server, lift, request.place).filter(request.rider::equals).isPresent()) {
                    request.place = observed.freePlaces().stream().filter(place -> !reserved.contains(place))
                        .findFirst().orElse(null);
                }
                if (request.place != null) {
                    reserved.add(request.place);
                }
                request.status = stopped(pickup) && request.place != null ? Status.BOARDING : Status.WAITING;
            }
            if (now - boardingSince < boardingWindow()) {
                target(server, pickup, now);
                return;
            }
            // A missing passenger cannot hold a cabin forever; boarded passengers keep their reservation.
            for (Request request : batch) {
                if (request.body == null) {
                    if (request.approaching == null) { fail(request); }
                    else { request.cancelled = true; withdraw(server, request); }
                }
            }
            batch.removeIf(request -> !live(request));
            if (batch.stream().anyMatch(request -> request.body == null)) {
                target(server, pickup, now);
                return;
            }
        } else if (boardingSince >= 0 && now - boardingSince < dwell()) {
            target(server, pickup, now);
            return;
        }
        if (batch.isEmpty()) {
            return;
        }
        // Removed destination contacts must not trap cancellation forever; use the nearest surviving landing.
        if (!valid(destination)) {
            destination = observed.floors().stream().min(java.util.Comparator.comparingDouble(
                floor -> Math.abs(floor.y() - observed.contactY()))).map(ElevatorNetwork.Floor::y).orElse(destination);
        }
        if (stopped(pickup) && !network.readyToDepart(server, lift)) {
            target(server, pickup, now);
            return;
        }
        target(server, destination, now);
        if (stopped(destination)) {
            for (Request request : batch) {
                request.status = Status.ALIGHTING;
                if (request.cancelled) {
                    unloadCancelled(server, request);
                }
            }
        }
    }

    private void target(MinecraftServer server, int floor, long now) {
        if (observed.powered() && (targeted == null || targeted != floor || now % 20 == 0 || stopped(floor) || now == lastProgress)) {
            if (network.target(server, lift, floor)) { targeted = floor; }
        }
    }

    public boolean board(MinecraftServer server, UUID rider, Mob body) {
        observed = network.inspect(server, lift).orElse(null);
        Request request = requests.get(rider);
        if (request == null || request.status != Status.BOARDING || request.place == null
                || !stopped(request.from) || !observed.powered() || !body.getUUID().equals(rider)
                || request.cancelled || network.occupant(server, lift, request.place)
                    .filter(occupant -> !occupant.equals(rider)).isPresent()) {
            return false;
        }
        request.approaching = body;
        if (!network.board(server, lift, request.place, body)) {
            return false;
        }
        request.body = body;
        request.status = Status.RIDING;
        return true;
    }

    public void alight(MinecraftServer server, UUID rider, Mob body) {
        observed = network.inspect(server, lift).orElse(null);
        Request request = requests.get(rider);
        if (request == null || request.status != Status.ALIGHTING || request.body != body
                || request.place == null || !stopped(request.to)) {
            return;
        }
        if (!network.disembark(server, lift, request.place, body, request.exits)) { return; }
        request.body = null;
        request.approaching = null;
        request.place = null;
        request.status = Status.FINISHED;
        request.touched = clock;
    }

    public void cancel(UUID rider) {
        Request request = requests.get(rider);
        if (request != null && live(request)) {
            if (request.body == null && request.approaching == null) {
                fail(request);
            } else {
                request.cancelled = true;
            }
        }
    }

    public void retire() {
        retired = true;
        requests.keySet().forEach(this::cancel);
    }

    public boolean retired() { return retired; }

    public boolean idle() { return requests.values().stream().noneMatch(ElevatorController::live); }

    private void unloadCancelled(MinecraftServer server, Request request) {
        if (observed.stoppedAt().isEmpty() || request.body == null
                || (valid(request.to) && !stopped(request.to) && !request.rescue)) {
            return;
        }
        request.status = Status.ALIGHTING;
        Set<WorldPos> exits = stopped(request.to) ? request.exits : Set.of();
        if (network.disembark(server, lift, request.place, request.body, exits)) {
            request.body = null;
            request.approaching = null;
            request.place = null;
            request.status = Status.FINISHED;
            request.touched = clock;
        }
    }

    private void withdraw(MinecraftServer server, Request request) {
        Set<WorldPos> exits = Set.of();
        var level = server.getLevel(lift.dimension());
        if (level != null) {
            var floor = observed.floor(request.from);
            if (floor.isPresent()) {
                exits = PassengerLanding.at(level, floor.get().platform(), floor.get().waiting().cells())
                    .map(cell -> Set.of(WorldPos.of(level, cell))).orElse(Set.of());
            }
        }
        if (request.approaching != null && stopped(request.from)
                && network.disembark(server, lift, request.place == null ? -1 : request.place, request.approaching, exits)) {
            request.approaching = null;
            fail(request);
        }
    }

    private boolean nearLanding(Request request) {
        // Walking through a doorway temporarily loses cabin contact. A body moved to another
        // floor (or falling away from it) must not retain a reservation and hold the lift forever.
        return observed.stoppedAt().isPresent() && nearFloor(request.body, observed.stoppedAt().getAsInt(), 16D);
    }

    private boolean nearFloor(Mob body, int y, double rangeSqr) {
        return observed.floor(y).map(floor -> floor.platform().cells().stream().anyMatch(at ->
                Math.abs(body.getY() - at.cell().getY()) < 1.5D
                    && net.minecraft.world.phys.Vec3.atBottomCenterOf(at.cell()).distanceToSqr(body.position()) < rangeSqr))
            .orElse(false);
    }

    public long estimate(int from, int to, long when) {
        if (!valid(from) || !valid(to) || retired || observed == null || !observed.powered()) {
            return Long.MAX_VALUE;
        }
        return forecast().arrive(from, to, when);
    }

    ElevatorForecast forecast() {
        if (observed == null || !observed.powered() || retired) {
            return new ElevatorForecast(clock, 0, 0.01, 100, 0, List.of());
        }
        List<Request> queue = requests.values().stream().filter(ElevatorController::live).toList();
        int capacity = Math.min(observed.places().size(), observed.freePlaces().size()
            + (int) queue.stream().filter(request -> request.body != null).count());
        List<ElevatorForecast.Trip> trips = new ArrayList<>();
        Set<Request> counted = new LinkedHashSet<>();
        for (Request request : queue) {
            if (!counted.add(request)) { continue; }
            List<Request> grouped = new ArrayList<>();
            grouped.add(request);
            for (Request other : queue) {
                if (grouped.size() < capacity && !counted.contains(other) && request.from == other.from
                        && request.to == other.to && batch.contains(request) == batch.contains(other)) {
                    counted.add(other);
                    grouped.add(other);
                }
            }
            boolean aboard = grouped.stream().anyMatch(one -> one.body != null);
            boolean accepting = !aboard || stopped(request.from)
                && grouped.stream().noneMatch(one -> one.status == Status.ALIGHTING);
            trips.add(new ElevatorForecast.Trip(request.from, request.to, grouped.size(), aboard, accepting));
        }
        return new ElevatorForecast(clock, observed.contactY(), observed.blocksPerTick(), boardingWindow(), capacity, trips);
    }

    private long dwell() { return 20L; }
    private long boardingWindow() { return Math.max(100L, Math.min(400L, observed.places().size() * 20L)); }
    private boolean valid(int y) { return observed != null && observed.floor(y).isPresent(); }
    private boolean stopped(int y) {
        return observed != null && observed.stoppedAt().isPresent() && observed.stoppedAt().getAsInt() == y;
    }
    private static boolean live(Request request) {
        return request.status != Status.FAILED && request.status != Status.FINISHED;
    }
    private void fail(Request request) {
        if (request.approaching != null) { request.approaching.getNavigation().stop(); }
        request.status = Status.FAILED;
        request.place = null;
        request.body = null;
        request.approaching = null;
        request.touched = clock;
    }

    private static final class Request {
        final UUID rider;
        final int from;
        final int to;
        final long created;
        long touched;
        Status status = Status.WAITING;
        Integer place;
        Mob body;
        Mob approaching;
        boolean cancelled;
        boolean rescue;
        Set<WorldPos> exits = Set.of();

        Request(UUID rider, int from, int to, long now) {
            this.rider = rider;
            this.from = from;
            this.to = to;
            created = now;
            touched = now;
        }
    }
}
