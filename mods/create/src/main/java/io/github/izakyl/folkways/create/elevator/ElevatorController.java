package io.github.izakyl.folkways.create.elevator;

import io.github.izakyl.folkways.create.FolkwaysCreateConfig;
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
import net.minecraft.server.level.ServerLevel;
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
                || observed.seats().isEmpty() || !observed.powered()) {
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

    public Optional<Integer> seat(UUID rider) {
        Request request = requests.get(rider);
        return request == null ? Optional.empty() : Optional.ofNullable(request.seat);
    }

    public void tick(MinecraftServer server, long now) {
        clock = now;
        requests.values().removeIf(request -> !live(request) && now - request.touched > STALL);
        observed = network.inspect(server, lift).orElse(null);
        for (Request request : requests.values()) {
            if ((request.status == Status.WAITING || request.status == Status.BOARDING)
                    && now - request.touched > LEASE) {
                fail(request);
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
                if (request.body != null && !network.seatOf(server, lift, request.body).isPresent()) {
                    fail(request);
                }
            }
            vehicle = observed.vehicle();
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
            if (request.body != null) {
                if (!request.body.isAlive() || network.seatOf(server, lift, request.body).isEmpty()) {
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
            } else if (!valid(request.from) || !valid(request.to) || observed.seats().isEmpty()
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
            Set<Integer> free = new LinkedHashSet<>(observed.freeSeats());
            for (Request request : requests.values()) {
                if (request.status == Status.WAITING && request.from == first.from && request.to == first.to
                        && !free.isEmpty()) {
                    request.seat = free.iterator().next();
                    free.remove(request.seat);
                    batch.add(request);
                }
            }
            lastProgress = now;
        }
        if (batch.isEmpty()) {
            return;
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
            batch.stream().filter(request -> request.body != null).forEach(request -> reserved.add(request.seat));
            for (Request request : batch) {
                if (request.body != null) {
                    continue;
                }
                if (request.seat == null || reserved.contains(request.seat)
                        || !observed.freeSeats().contains(request.seat)) {
                    request.seat = observed.freeSeats().stream().filter(seat -> !reserved.contains(seat))
                        .findFirst().orElse(null);
                }
                if (request.seat != null) {
                    reserved.add(request.seat);
                }
                request.status = stopped(pickup) && request.seat != null ? Status.BOARDING : Status.WAITING;
            }
            long dwell = dwell();
            if (now - boardingSince < dwell) {
                target(server, pickup, now);
                return;
            }
            // A missing passenger cannot hold a cabin forever; boarded passengers keep their reservation.
            for (Request request : batch) {
                if (request.body == null) {
                    fail(request);
                }
            }
            batch.removeIf(request -> !live(request));
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
        if (observed.powered() && (now % 20 == 0 || stopped(floor) || now == lastProgress)) {
            network.target(server, lift, floor);
        }
    }

    public boolean board(MinecraftServer server, UUID rider, Mob body) {
        observed = network.inspect(server, lift).orElse(null);
        Request request = requests.get(rider);
        if (request == null || request.status != Status.BOARDING || request.seat == null
                || !stopped(request.from) || !observed.powered() || !body.getUUID().equals(rider)
                || network.occupant(server, lift, request.seat).isPresent()) {
            return false;
        }
        if (!network.board(server, lift, request.seat, body)) {
            request.seat = null;
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
                || request.seat == null || !stopped(request.to)) {
            return;
        }
        network.disembark(server, lift, request.seat, body);
        request.body = null;
        request.seat = null;
        request.status = Status.FINISHED;
        request.touched = clock;
    }

    public void cancel(UUID rider) {
        Request request = requests.get(rider);
        if (request != null && live(request)) {
            if (request.body == null) {
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
        var floor = observed.floor(observed.stoppedAt().getAsInt());
        ServerLevel level = server.getLevel(lift.dimension());
        if (floor.isEmpty() || level == null) {
            return;
        }
        PassengerLanding.at(level, floor.get().platform(), Set.of()).ifPresent(landing -> {
            network.disembark(server, lift, request.seat, request.body);
            request.body.teleportTo(landing.getX() + 0.5D, landing.getY(), landing.getZ() + 0.5D);
            request.body = null;
            request.seat = null;
            request.status = Status.FINISHED;
            request.touched = clock;
        });
    }

    // This estimate deliberately does not reserve seats or submit a request. A later caller cannot obtain an
    // earlier arrival by waiting: all work already accepted is conservatively served before the new journey.
    public long estimate(int from, int to, long when) {
        if (observed == null || !observed.powered() || !valid(from) || !valid(to)
                || observed.seats().isEmpty() || retired) {
            return Long.MAX_VALUE;
        }
        double position = observed.contactY();
        long duration = 0;
        Set<Request> counted = new LinkedHashSet<>();
        List<Request> queue = requests.values().stream().filter(ElevatorController::live).toList();
        int capacity = Math.max(1, observed.freeSeats().size()
            + (int) queue.stream().filter(request -> request.body != null).count());
        for (Request request : queue) {
            if (!counted.add(request)) {
                continue;
            }
            int loaded = 1;
            for (Request other : queue) {
                if (loaded < capacity && !counted.contains(other) && request.from == other.from
                        && request.to == other.to && batch.contains(request) == batch.contains(other)) {
                    counted.add(other);
                    loaded++;
                }
            }
            if (request.body == null) {
                duration += travel(position, request.from) + dwell();
            }
            duration += travel(request.body == null ? request.from : position, request.to) + 20;
            position = request.to;
        }
        duration += travel(position, from) + dwell() + travel(from, to) + 20;
        long start = Math.max(clock, when);
        return duration >= Long.MAX_VALUE - start ? Long.MAX_VALUE : start + duration;
    }

    private long travel(double from, int to) {
        double speed = Math.max(0.01D, observed.blocksPerTick());
        return (long) Math.ceil(Math.abs(to - from) / speed) + 20;
    }

    private long dwell() { return FolkwaysCreateConfig.seatTicks() + 20L; }
    private boolean valid(int y) { return observed != null && observed.floor(y).isPresent(); }
    private boolean stopped(int y) {
        return observed != null && observed.stoppedAt().isPresent() && observed.stoppedAt().getAsInt() == y;
    }
    private static boolean live(Request request) {
        return request.status != Status.FAILED && request.status != Status.FINISHED;
    }
    private void fail(Request request) {
        request.status = Status.FAILED;
        request.seat = null;
        request.body = null;
        request.touched = clock;
    }

    private static final class Request {
        final UUID rider;
        final int from;
        final int to;
        final long created;
        long touched;
        Status status = Status.WAITING;
        Integer seat;
        Mob body;
        boolean cancelled;
        boolean rescue;

        Request(UUID rider, int from, int to, long now) {
            this.rider = rider;
            this.from = from;
            this.to = to;
            created = now;
            touched = now;
        }
    }
}
