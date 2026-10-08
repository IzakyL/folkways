package io.github.izakyl.folkways.plugins.rail.domain;

import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.display.GlobalTrainDisplayData;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.graph.DiscoveredPath;
import com.simibubi.create.content.trains.graph.EdgePointType;
import com.simibubi.create.content.trains.schedule.Schedule;
import com.simibubi.create.content.trains.schedule.ScheduleEntry;
import com.simibubi.create.content.trains.schedule.ScheduleRuntime;
import com.simibubi.create.content.trains.schedule.condition.ScheduleWaitCondition;
import com.simibubi.create.content.trains.schedule.condition.ScheduledDelay;
import com.simibubi.create.content.trains.schedule.destination.ChangeThrottleInstruction;
import com.simibubi.create.content.trains.schedule.destination.ChangeTitleInstruction;
import com.simibubi.create.content.trains.schedule.destination.DestinationInstruction;
import com.simibubi.create.content.trains.schedule.destination.ScheduleInstruction;
import com.simibubi.create.content.trains.station.GlobalStation;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.CreateMod;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.Glob;
import net.createmod.catnip.math.VecHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class CreateTransitNetwork implements TransitNetwork {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("folkways-labor");

    // Widest and longest each train has been seen, so the platform does not shrink while its carriages are unloaded.
    private final Map<UUID, Double> widths = new ConcurrentHashMap<>();

    private final Map<UUID, Double> lengths = new ConcurrentHashMap<>();

    // Where along the track, from the stop, each train's blocks lie when it stands at a station it ran into forwards.
    private final Map<UUID, double[]> stood = new ConcurrentHashMap<>();

    // Whether each train last came into each station backwards, by train and station.
    private final Map<String, Boolean> cameIn = new ConcurrentHashMap<>();

    // What makes a stop's name a pattern to Create (Glob) rather than one station's name.
    private static final String PATTERN = "*?[]{}\\";

    private static final double MIN_SPEED = 0.05D;

    // Slack either end of a train's reckoned span before it has stood anywhere to be measured.
    private static final double OVERHANG = 1.0D;

    private static final double UNSEEN_WIDTH = 3.0D;

    @Override
    public boolean isAvailable() {
        return CreateMod.loaded();
    }

    @Override
    public List<UUID> trains(MinecraftServer server) {
        return List.copyOf(railways(server).trains.keySet());
    }

    @Override
    public boolean exists(MinecraftServer server, UUID train) {
        return railways(server).trains.containsKey(train);
    }

    @Override
    public boolean isServiceable(MinecraftServer server, UUID train) {
        Train t = railways(server).trains.get(train);
        return t != null && !t.runtime.paused && t.runtime.getSchedule() != null && !t.derailed
            && t.graph != null;
    }

    @Override
    public List<SeatRef> conductorSeats(MinecraftServer server, UUID train, boolean forward) {
        Train t = railways(server).trains.get(train);
        if (t == null) {
            return List.of();
        }
        List<SeatRef> seats = new ArrayList<>();
        for (Carriage carriage : t.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null || !(entity.getContraption() instanceof CarriageContraption contraption)) {
                continue;
            }
            List<BlockPos> allSeats = contraption.getSeats();
            for (var conductorSeat : contraption.conductorSeats.entrySet()) {
                Couple<Boolean> facing = conductorSeat.getValue();
                if (!(forward ? facing.getFirst() : facing.getSecond())) {
                    continue;
                }
                int index = allSeats.indexOf(conductorSeat.getKey());
                if (index >= 0) {
                    seats.add(new SeatRef(entity.getUUID(), index));
                }
            }
        }
        return seats;
    }

    @Override
    public boolean hasConductor(MinecraftServer server, UUID train, boolean forward) {
        Train t = railways(server).trains.get(train);
        if (t == null) {
            return false;
        }
        return forward ? t.hasForwardConductor() : t.hasBackwardConductor();
    }

    @Override
    public List<SeatRef> passengerSeats(MinecraftServer server, UUID train) {
        Train t = railways(server).trains.get(train);
        if (t == null) {
            return List.of();
        }
        List<SeatRef> seats = new ArrayList<>();
        for (Carriage carriage : t.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null || !(entity.getContraption() instanceof CarriageContraption contraption)) {
                continue;
            }
            List<BlockPos> allSeats = contraption.getSeats();
            for (int index = 0; index < allSeats.size(); index++) {
                if (contraption.conductorSeats.containsKey(allSeats.get(index))) {
                    continue;
                }
                seats.add(new SeatRef(entity.getUUID(), index));
            }
        }
        return seats;
    }

    @Override
    public Optional<WorldPos> stationPosition(MinecraftServer server, UUID train, String station) {
        Train t = railways(server).trains.get(train);
        if (t == null || t.graph == null) {
            return Optional.empty();
        }
        return stopping(t, station).map(candidate ->
            WorldPos.of(candidate.getBlockEntityDimension(), candidate.getBlockEntityPos()));
    }

    @Override
    public Optional<UUID> occupant(MinecraftServer server, SeatRef seat) {
        return carriageEntity(server, seat.vehicleId()).flatMap(entity -> {
            Contraption contraption = entity.getContraption();
            if (contraption == null || seat.seatIndex() < 0 || seat.seatIndex() >= contraption.getSeats().size()) {
                return Optional.empty();
            }
            return contraption.getSeatMapping().entrySet().stream()
                .filter(entry -> entry.getValue() == seat.seatIndex())
                .map(Map.Entry::getKey)
                .findFirst();
        });
    }

    @Override
    public Optional<WorldPos> seatPosition(MinecraftServer server, SeatRef seat) {
        return carriageEntity(server, seat.vehicleId()).flatMap(entity -> {
            Contraption contraption = entity.getContraption();
            if (contraption == null || seat.seatIndex() < 0 || seat.seatIndex() >= contraption.getSeats().size()) {
                return Optional.empty();
            }
            BlockPos local = contraption.getSeats().get(seat.seatIndex());
            return Optional.of(WorldPos.of(entity.level(), BlockPos.containing(
                entity.toGlobalVector(VecHelper.getCenterOf(local), 1.0F))));
        });
    }

    @Override
    public boolean board(MinecraftServer server, SeatRef seat, Entity rider) {
        Optional<CarriageContraptionEntity> entity = carriageEntity(server, seat.vehicleId());
        if (entity.isEmpty()) {
            return false;
        }
        Contraption contraption = entity.get().getContraption();
        if (contraption == null || seat.seatIndex() < 0 || seat.seatIndex() >= contraption.getSeats().size()
                || rider.level() != entity.get().level() || occupant(server, seat).isPresent()) {
            return false;
        }
        Vec3 previous = rider.position();
        Vec3 position = entity.get().toGlobalVector(
            Vec3.atCenterOf(contraption.getSeats().get(seat.seatIndex())), 1.0F);
        rider.teleportTo(position.x, position.y, position.z);
        entity.get().addSittingPassenger(rider, seat.seatIndex());
        if (!contraption.getSeatMapping().containsKey(rider.getUUID())) {
            rider.teleportTo(previous.x, previous.y, previous.z);
        }
        return contraption.getSeatMapping().containsKey(rider.getUUID());
    }

    @Override
    public void disembark(MinecraftServer server, SeatRef seat, Entity rider) {
        if (rider.isPassenger()) {
            rider.stopRiding();
        }
    }

    // The stop on the schedule the train is standing at, named as the schedule names it: the entry it is carrying out
    // first, since one station may answer several entries.
    @Override
    public Optional<String> currentStation(MinecraftServer server, UUID train) {
        Train t = railways(server).trains.get(train);
        if (t == null) {
            return Optional.empty();
        }
        GlobalStation here = t.getCurrentStation();
        if (here == null) {
            return Optional.empty();
        }
        Schedule schedule = t.runtime.getSchedule();
        if (schedule != null) {
            int count = schedule.entries.size();
            for (int step = 0; step < count; step++) {
                ScheduleEntry entry = schedule.entries.get((Math.max(0, t.runtime.currentEntry) + step) % count);
                if (entry.instruction instanceof DestinationInstruction destination
                        && here.name.matches(destination.getFilterForRegex())) {
                    return Optional.of(stationOf(destination));
                }
            }
        }
        return Optional.of(here.name);
    }

    @Override
    public List<Stop> timetable(MinecraftServer server, UUID train) {
        Train t = railways(server).trains.get(train);
        if (t == null || !isServiceable(server, train) || fault(server, train).isPresent()) {
            return List.of();
        }
        ScheduleRuntime runtime = t.runtime;
        Schedule schedule = runtime.getSchedule();
        List<Itinerary.Call> calls = new ArrayList<>();
        int next = -1;
        for (int index = 0; index < schedule.entries.size(); index++) {
            ScheduleEntry entry = schedule.entries.get(index);
            if (!(entry.instruction instanceof DestinationInstruction destination)) {
                continue;
            }
            if (next < 0 && index >= runtime.currentEntry) {
                next = calls.size();
            }
            calls.add(new Itinerary.Call(stationOf(destination), ridden(runtime, index),
                holds(entry, null)));
        }
        if (calls.isEmpty()) {
            return List.of();
        }
        if (next < 0) {
            if (!schedule.cyclic) {
                return List.of();
            }
            next = 0;
        }
        GlobalStation here = t.getCurrentStation();
        Stop first;
        if (runtime.state == ScheduleRuntime.State.POST_TRANSIT && here != null) {
            OptionalInt left = runtime.currentEntry < schedule.entries.size()
                ? holds(schedule.entries.get(runtime.currentEntry), runtime) : OptionalInt.empty();
            first = new Stop(calls.get(next).station(), 0, left.orElse(0));
            if (left.isEmpty()) {
                return List.of(first);
            }
            // Standing at a station, the train can be asked the way to its next one even before it has run it.
            int onward = next + 1 < calls.size() ? next + 1 : schedule.cyclic ? 0 : -1;
            if (onward >= 0 && calls.get(onward).ride().isEmpty()) {
                Itinerary.Call call = calls.get(onward);
                OptionalInt pathed = pathed(t, call.station()).map(route -> ticks(t, Math.abs(route.distance)))
                    .map(OptionalInt::of).orElse(OptionalInt.empty());
                calls.set(onward, new Itinerary.Call(call.station(), pathed, call.stay()));
            }
        } else {
            Optional<Stop> heading = heading(t, runtime, calls.get(next));
            if (heading.isEmpty()) {
                return List.of();
            }
            first = heading.get();
            if (calls.get(next).stay().isEmpty()) {
                return List.of(first);
            }
        }
        // A run the train has not made yet has no time of Create's; reckon it by the way between its stops, so the line
        // does not end at the first stop not yet run to (under way to Beta, the way back to Alpha is still a way).
        for (int call = 0; call < calls.size(); call++) {
            Itinerary.Call unrun = calls.get(call);
            int before = call > 0 ? call - 1 : schedule.cyclic ? calls.size() - 1 : -1;
            if (unrun.ride().isPresent() || before < 0) {
                continue;
            }
            OptionalInt reckoned = between(t, calls.get(before).station(), unrun.station());
            calls.set(call, new Itinerary.Call(unrun.station(), reckoned, unrun.stay()));
        }
        List<Stop> stops = new ArrayList<>();
        stops.add(first);
        stops.addAll(Itinerary.after(calls, schedule.cyclic, next + 1, first.leavesIn()));
        return List.copyOf(stops);
    }

    // Under way, Create's own estimate for the stop it is running to, the one its departure boards show. Before it
    // has set out, the time the run took last time, else the way Create would find to the stop.
    private static Optional<Stop> heading(Train t, ScheduleRuntime runtime, Itinerary.Call call) {
        int arrives;
        OptionalInt ridden = ridden(runtime, runtime.currentEntry);
        if (t.navigation.destination != null) {
            Optional<GlobalTrainDisplayData.TrainDeparturePrediction> told =
                runtime.submitPredictions().stream().findFirst();
            // Create has no word between some ticks of a run (a prediction of -1 while it recomputes); the run is still on,
            // so it is reckoned by the way left rather than dropped from the timetable.
            arrives = told.isPresent() && told.get().ticks >= 0 ? told.get().ticks
                : ticks(t, Math.abs(t.navigation.distanceToDestination));
        } else if (ridden.isPresent()) {
            arrives = ridden.getAsInt();
        } else {
            Optional<DiscoveredPath> route = pathed(t, call.station());
            if (route.isEmpty()) {
                return Optional.empty();
            }
            arrives = ticks(t, Math.abs(route.get().distance));
        }
        arrives = Math.max(1, arrives);
        return Optional.of(new Stop(call.station(), arrives, arrives + call.stay().orElse(0)));
    }

    // A span measured from the front wheels, as seen from the wheels at the other end running the other way.
    private static double[] mirrored(double[] span, double wheelbase) {
        return new double[] {-span[1] - wheelbase, -span[0] - wheelbase};
    }

    private static Optional<double[]> stands(Train t, Vec3 stop, Vec3 along) {
        double low = Double.MAX_VALUE;
        double high = -Double.MAX_VALUE;
        for (Carriage carriage : t.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity == null) {
                return Optional.empty();
            }
            if (entity.getContraption() == null) {
                return Optional.empty();
            }
            for (BlockPos local : entity.getContraption().getBlocks().keySet()) {
                Vec3 block = entity.toGlobalVector(Vec3.atCenterOf(local), 1.0F);
                double at = (block.x - stop.x) * along.x + (block.z - stop.z) * along.z;
                low = Math.min(low, at - 0.5D);
                high = Math.max(high, at + 0.5D);
            }
        }
        return low > high ? Optional.empty() : Optional.of(new double[] {low, high});
    }

    // The carriage's own blocks, across and along the way it was assembled: its bounding box is widened so it can
    // turn, and says nothing about how wide it is.
    static double[] extent(CarriageContraptionEntity entity) {
        if (!(entity.getContraption() instanceof CarriageContraption contraption)
                || contraption.getBlocks().isEmpty()) {
            AABB box = entity.getBoundingBox();
            return new double[] {UNSEEN_WIDTH, Math.max(box.getXsize(), box.getZsize())};
        }
        Direction.Axis runs = contraption.getAssemblyDirection().getAxis();
        int acrossLow = Integer.MAX_VALUE;
        int acrossHigh = Integer.MIN_VALUE;
        int alongLow = Integer.MAX_VALUE;
        int alongHigh = Integer.MIN_VALUE;
        for (BlockPos local : contraption.getBlocks().keySet()) {
            int across = runs == Direction.Axis.X ? local.getZ() : local.getX();
            int along = runs == Direction.Axis.X ? local.getX() : local.getZ();
            acrossLow = Math.min(acrossLow, across);
            acrossHigh = Math.max(acrossHigh, across);
            alongLow = Math.min(alongLow, along);
            alongHigh = Math.max(alongHigh, along);
        }
        return new double[] {acrossHigh - acrossLow + 1, alongHigh - alongLow + 1};
    }

    // The one station a schedule stop names. A train is only timetabled when every stop names exactly one station on
    // its network (see fault), so the stop is known before the train sets out for it.
    private static Optional<GlobalStation> stopping(Train t, String station) {
        GlobalStation found = null;
        for (GlobalStation candidate : t.graph.getPoints(EdgePointType.STATION)) {
            if (candidate.name.equals(station)) {
                if (found != null) {
                    return Optional.empty();
                }
                found = candidate;
            }
        }
        return Optional.ofNullable(found);
    }

    // Only a schedule that says ahead of time where the train stops and for how long can be timetabled: every entry a
    // stop (or a change of title or throttle, which does neither), each stop naming one station of the train's
    // network outright, and each held only by delays.
    @Override
    public Optional<TimetableFault> fault(MinecraftServer server, UUID train) {
        Train t = railways(server).trains.get(train);
        if (t == null || t.graph == null || t.runtime.getSchedule() == null) {
            return Optional.empty();
        }
        List<ScheduleEntry> entries = t.runtime.getSchedule().entries;
        int stops = 0;
        for (int index = 0; index < entries.size(); index++) {
            ScheduleEntry entry = entries.get(index);
            if (entry.instruction instanceof ChangeTitleInstruction
                    || entry.instruction instanceof ChangeThrottleInstruction) {
                continue;
            }
            if (entry.instruction.getClass() != DestinationInstruction.class) {
                return TimetableFault.of(TimetableFault.NOT_A_STOP, String.valueOf(index + 1));
            }
            String station = stationOf((DestinationInstruction) entry.instruction);
            if (station.isBlank()) {
                return TimetableFault.of(TimetableFault.UNNAMED, String.valueOf(index + 1));
            }
            if (station.chars().anyMatch(c -> PATTERN.indexOf(c) >= 0)) {
                return TimetableFault.of(TimetableFault.PATTERN, station);
            }
            int named = 0;
            for (GlobalStation candidate : t.graph.getPoints(EdgePointType.STATION)) {
                named += candidate.name.equals(station) ? 1 : 0;
            }
            if (named != 1) {
                return TimetableFault.of(named == 0 ? TimetableFault.NO_STATION : TimetableFault.SHARED_NAME, station);
            }
            for (List<ScheduleWaitCondition> group : entry.conditions) {
                for (ScheduleWaitCondition condition : group) {
                    if (!(condition instanceof ScheduledDelay)) {
                        return TimetableFault.of(TimetableFault.NOT_TIMED, station);
                    }
                }
            }
            stops++;
        }
        return stops < 2 ? TimetableFault.of(TimetableFault.NO_STOPS, "") : Optional.empty();
    }

    // The count of departures the first stop of the timetable leaves at: standing at a stop it is still to leave, that
    // one; under way, the next. Standing at a station it is done with and bound elsewhere, it leaves that one first;
    // bound for the very station it stands at, Create counts it arrived without leaving.
    @Override
    public long nextDeparture(MinecraftServer server, UUID train) {
        Train t = railways(server).trains.get(train);
        long left = Departures.of(train);
        if (t == null) {
            return left + 1;
        }
        GlobalStation here = t.getCurrentStation();
        ScheduleRuntime runtime = t.runtime;
        Schedule schedule = runtime.getSchedule();
        boolean leaving = here != null && runtime.state != ScheduleRuntime.State.POST_TRANSIT && schedule != null
            && !(runtime.currentEntry < schedule.entries.size()
                && schedule.entries.get(runtime.currentEntry).instruction instanceof DestinationInstruction bound
                && here.name.equals(stationOf(bound)));
        return left + (leaving ? 2 : 1);
    }

    // As DestinationInstruction.start finds its way, without setting the train off.
    private static Optional<DiscoveredPath> pathed(Train t, String station) {
        String matching = Glob.toRegexPattern(station, "");
        ArrayList<GlobalStation> destinations = new ArrayList<>();
        for (GlobalStation candidate : t.graph.getPoints(EdgePointType.STATION)) {
            if (candidate.name.matches(matching)) {
                destinations.add(candidate);
            }
        }
        return destinations.isEmpty() ? Optional.empty()
            : Optional.ofNullable(t.navigation.findPathTo(destinations, Double.MAX_VALUE));
    }

    private OptionalInt between(Train t, String from, String to) {
        Optional<Vec3> leaving = stopping(t, from).flatMap(CreateTransitNetwork::stopOf);
        Optional<Vec3> arriving = stopping(t, to).flatMap(CreateTransitNetwork::stopOf);
        return leaving.isEmpty() || arriving.isEmpty() ? OptionalInt.empty()
            : OptionalInt.of(ticks(t, leaving.get().distanceTo(arriving.get())));
    }

    private static Optional<Vec3> stopOf(GlobalStation station) {
        if (station.edgeLocation == null) {
            return Optional.empty();
        }
        Vec3 from = station.edgeLocation.getFirst().getLocation();
        Vec3 run = station.edgeLocation.getSecond().getLocation().subtract(from);
        return run.lengthSqr() < 1.0E-6D ? Optional.empty() : Optional.of(from.add(run.normalize().scale(station.position)));
    }

    // The speed ScheduleRuntime.submitPredictions reckons a run at before it has one to go by.
    private static int ticks(Train t, double distance) {
        double speed = Math.max(MIN_SPEED,
            Math.min(t.throttle * t.maxSpeed(), (t.maxSpeed() + t.maxTurnSpeed()) / 2.0D));
        return Math.max(1, (int) (distance / speed) * 2);
    }

    private static String stationOf(DestinationInstruction destination) {
        return destination.getFilter();
    }

    private static OptionalInt ridden(ScheduleRuntime runtime, int entry) {
        if (entry < 0 || entry >= runtime.predictionTicks.size()) {
            return OptionalInt.empty();
        }
        int ticks = runtime.predictionTicks.get(entry);
        return ticks > 0 ? OptionalInt.of(ticks) : OptionalInt.empty();
    }

    // The train leaves once any one group of its conditions is met. Only waits for time, and our own conditions
    // that the crew settles at once, say when that is; a group that waits on anything else says nothing.
    private static OptionalInt holds(ScheduleEntry entry, ScheduleRuntime waiting) {
        int soonest = Integer.MAX_VALUE;
        for (int group = 0; group < entry.conditions.size(); group++) {
            List<ScheduleWaitCondition> conditions = entry.conditions.get(group);
            int from = waiting != null && group < waiting.conditionProgress.size()
                ? waiting.conditionProgress.get(group) : 0;
            int total = 0;
            boolean told = true;
            for (int at = from; at < conditions.size() && told; at++) {
                ScheduleWaitCondition condition = conditions.get(at);
                if (condition instanceof ScheduledDelay delay) {
                    int waited = at == from && waiting != null && group < waiting.conditionContext.size()
                        ? waiting.conditionContext.get(group).getInt("Time") : 0;
                    total += Math.max(0, delay.totalWaitTicks() - waited);
                } else {
                    told = false;
                }
            }
            if (told) {
                soonest = Math.min(soonest, total);
            }
        }
        return soonest == Integer.MAX_VALUE ? OptionalInt.empty() : OptionalInt.of(soonest);
    }

    @Override
    public Optional<Berth> berth(MinecraftServer server, UUID train, String station) {
        Train t = railways(server).trains.get(train);
        if (t == null || t.graph == null || t.carriages.isEmpty()) {
            return Optional.empty();
        }
        GlobalStation candidate = stopping(t, station).orElse(null);
        if (candidate == null || candidate.edgeLocation == null) {
            return Optional.empty();
        }
        Vec3 from = candidate.edgeLocation.getFirst().getLocation();
        Vec3 run = candidate.edgeLocation.getSecond().getLocation().subtract(from);
        Vec3 flat = new Vec3(run.x, 0.0D, run.z);
        if (flat.lengthSqr() < 1.0E-6D) {
            return Optional.empty();
        }
        Vec3 stop = from.add(run.normalize().scale(candidate.position));
        Vec3 along = flat.normalize();
        double width = 0.0D;
        double length = 0.0D;
        for (Carriage carriage : t.carriages) {
            CarriageContraptionEntity entity = carriage.anyAvailableEntity();
            if (entity != null) {
                double[] extent = extent(entity);
                width = Math.max(width, extent[0]);
                length += extent[1];
            }
        }
        width = widths.merge(train, width, Math::max);
        if (width <= 0.0D) {
            width = UNSEEN_WIDTH;
        }
        length = lengths.merge(train, length, Math::max);
        // Create only lets a train stop here running towards the station's second node, and stops it with the wheels
        // at its front, as it runs, on the stop. So a train stands the same way at every station it runs into forwards,
        // and the mirror of that at every one it backs into (its wheelbase further along). Its span running forwards
        // is measured the first time it stands anywhere and kept, until then reckoned from its carriages' length; each
        // station keeps which way the train last came in. The platform is then the same whether the train stands
        // there, is loaded, or is miles away.
        double wheelbase = t.getTotalLength();
        boolean arriving = candidate == t.getCurrentStation() || candidate == t.navigation.destination;
        String berthed = train + "/" + candidate.id;
        if (arriving) {
            cameIn.put(berthed, t.currentlyBackwards);
        }
        if (candidate == t.getCurrentStation() && !stood.containsKey(train)) {
            stands(t, stop, along).ifPresent(span -> {
                double[] forwards = t.currentlyBackwards ? mirrored(span, wheelbase) : span;
                stood.put(train, forwards);
                LOGGER.info("train {} stands over {}..{} of its stop at {} running {}", train, span[0], span[1],
                    station, t.currentlyBackwards ? "backwards" : "forwards");
            });
        }
        double[] forwards = stood.getOrDefault(train, new double[] {-length - OVERHANG, OVERHANG});
        Boolean backwards = t.doubleEnded ? cameIn.get(berthed) : Boolean.FALSE;
        double[] span = backwards == null
            ? new double[] {Math.min(forwards[0], -forwards[1] - wheelbase), Math.max(forwards[1], -forwards[0] - wheelbase)}
            : backwards ? mirrored(forwards, wheelbase) : forwards;
        return Optional.of(new Berth(WorldPos.of(candidate.getBlockEntityDimension(), BlockPos.containing(stop)),
            stop.x, stop.z, along.x, along.z, width / 2.0D, span[0], span[1]));
    }

    @Override
    public List<String> scheduledStations(MinecraftServer server, UUID train) {
        Train t = railways(server).trains.get(train);
        if (t == null) {
            return List.of();
        }
        Schedule schedule = t.runtime.getSchedule();
        if (schedule == null) {
            return List.of();
        }
        List<String> stations = new ArrayList<>();
        for (ScheduleEntry entry : schedule.entries) {
            ScheduleInstruction instruction = entry.instruction;
            if (instruction instanceof DestinationInstruction destination) {
                stations.add(stationOf(destination));
            }
        }
        return stations;
    }

    private static GlobalRailwayManager railways(MinecraftServer server) {
        return Create.RAILWAYS.sided(server.overworld());
    }

    private static Optional<CarriageContraptionEntity> carriageEntity(MinecraftServer server,
            UUID vehicleId) {
        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(vehicleId) instanceof CarriageContraptionEntity carriage) {
                return Optional.of(carriage);
            }
        }
        return Optional.empty();
    }
}
