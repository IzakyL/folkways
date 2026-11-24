package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Motion;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.control.Reading;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * A facility with a body: named controls placed relative to an anchor, which may lie in a moving structure's realm,
 * plus its routines, triggers, parameters and readings. Pure data; what it means is in its routines.
 */
public record FacilityDefinition(UUID id, WorldPos anchor, Map<String, Placed> controls, Map<String, Routine.Use> routines,
                     Map<String, Value> parameters, Map<String, Probed> probes,
                     String name, FacilityExtent extent, Card card, List<Gate> gates,
                     Map<String, Switch> switches, Optional<FacilityWorkshop> workshop, List<Lane> lanes,
                     List<MovingKeepout> keepouts, Map<String, PostDefinition> posts, List<Ride> rides) implements Facility {

    public FacilityDefinition(UUID id, WorldPos anchor, Map<String, Placed> controls, Map<String, Routine.Use> routines,
                  Map<String, Value> parameters, Map<String, Probed> probes, String name,
                  FacilityExtent extent, Card card, List<Gate> gates, Map<String, Switch> switches,
                  Optional<FacilityWorkshop> workshop, List<Lane> lanes) {
        this(id, anchor, controls, routines, parameters, probes, name, extent, card, gates, switches,
            workshop, lanes, List.of());
    }

    public FacilityDefinition(UUID id, WorldPos anchor, Map<String, Placed> controls, Map<String, Routine.Use> routines,
                  Map<String, Value> parameters, Map<String, Probed> probes, String name,
                  FacilityExtent extent, Card card, List<Gate> gates, Map<String, Switch> switches,
                  Optional<FacilityWorkshop> workshop, List<Lane> lanes, List<MovingKeepout> keepouts) {
        this(id, anchor, controls, routines, parameters, probes, name, extent, card, gates, switches,
            workshop, lanes, keepouts, Map.of());
    }

    public FacilityDefinition(UUID id, WorldPos anchor, Map<String, Placed> controls, Map<String, Routine.Use> routines,
                  Map<String, Value> parameters, Map<String, Probed> probes, String name,
                  FacilityExtent extent, Card card, List<Gate> gates, Map<String, Switch> switches,
                  Optional<FacilityWorkshop> workshop, List<Lane> lanes, List<MovingKeepout> keepouts,
                  Map<String, PostDefinition> posts) {
        this(id, anchor, controls, routines, parameters, probes, name, extent, card, gates, switches,
            workshop, lanes, keepouts, posts, List.of());
    }

    public record Carrier(String part, Vec3 perUnit) {
        public BlockPos shift(double extension) {
            return new BlockPos((int) Math.round(extension * perUnit.x),
                (int) Math.round(extension * perUnit.y), (int) Math.round(extension * perUnit.z));
        }
    }

    /** A control of the facility: the block at `offset` from the anchor, and which part of it. */
    public record Placed(BlockPos offset, String part, Optional<Carrier> carrier, Optional<UUID> entity) {

        public Placed(BlockPos offset, String part, Optional<Carrier> carrier) {
            this(offset, part, carrier, Optional.empty());
        }

        public Placed(BlockPos offset, String part) {
            this(offset, part, Optional.empty());
        }

        public Placed {
            offset = offset.immutable();
            Objects.requireNonNull(part, "part");
        }
    }

    /** A named reading: a registered probe read at `offset` from the anchor. */
    public record Probed(ResourceLocation probe, BlockPos offset, Map<String, Value> config, Optional<String> source) {

        public Probed {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(probe, "probe");
            offset = offset.immutable();
            config = Map.copyOf(config);
        }

        public Probed(ResourceLocation probe, BlockPos offset, Map<String, Value> config) {
            this(Objects.requireNonNull(probe), offset, config, Optional.empty());
        }

        public Probed(ResourceLocation probe, BlockPos offset) {
            this(probe, offset, Map.of());
        }
    }

    public FacilityDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(anchor, "anchor");
        controls = Map.copyOf(controls);
        routines = Map.copyOf(routines);
        parameters = Map.copyOf(parameters);
        probes = Map.copyOf(probes);
        Objects.requireNonNull(name);
        Objects.requireNonNull(extent);
        Objects.requireNonNull(card);
        parameters = card.withFallbacks(parameters);
        gates = List.copyOf(gates);
        switches = Map.copyOf(switches);
        lanes = List.copyOf(lanes);
        keepouts = List.copyOf(keepouts);
        posts = Map.copyOf(posts);
        rides = List.copyOf(rides);
    }

    Card declaredCard() {
        return card;
    }

    public record Lane(String from, String to, boolean automatic, List<ResourceLocation> items,
                       int latency, Optional<Contribution.Lane.Rate> rate) {
        public Lane {
            items = List.copyOf(items);
        }
    }

    public record Switch(Trigger trigger, boolean enabled) {
        public Switch {
            Objects.requireNonNull(trigger);
        }
    }

    public record Gate(List<BlockPos> cells, Map<String, Value> openWhen,
                       Optional<Set<String>> axes) {

        public Gate {
            cells = cells.stream().map(BlockPos::immutable).toList();
            openWhen = Map.copyOf(openWhen);
            axes = axes.map(Set::copyOf);
        }
    }

    public record Ride(Map<String, List<BlockPos>> stops, String routine, int ticks, int capacity,
                       Map<String, Value> availableWhen) {

        public Ride {
            Map<String, List<BlockPos>> cells = new LinkedHashMap<>();
            stops.forEach((name, positions) -> cells.put(name, positions.stream().map(BlockPos::immutable).toList()));
            stops = Map.copyOf(cells);
            availableWhen = Map.copyOf(availableWhen);
        }

        static String conditionKey(int index) {
            return "ride:" + index;
        }
    }

    @FunctionalInterface
    public interface GateProjection {
        List<Contribution> project(ServerLevel level, FacilityDefinition facility, Map<String, Value> readings);
    }

    private static GateProjection gateProjection = (level, facility, readings) -> List.of();

    public static void installGateProjection(GateProjection projection) {
        gateProjection = Objects.requireNonNull(projection);
    }

    @Override
    public List<Contribution> projections(ServerLevel level, Map<String, Value> readings) {
        List<Contribution> projected = new ArrayList<>(gateProjection.project(level, this, readings));
        FacilityRidePassage.project(this, readings, projected);
        if (!keepouts.isEmpty()) {
            var resolved = resolve(level);
            for (int i = 0; i < keepouts.size(); i++) {
                var keepout = keepouts.get(i);
                var contribution = keepout.source().isPresent()
                    ? MovingKeepout.projection(FacilitySamples.keepout(this, i))
                    : keepout.project(level, this, resolved);
                contribution.ifPresent(projected::add);
            }
        }
        lanes.forEach(lane -> projected.add(new Contribution.Lane(ref(lane.from()), ref(lane.to()),
            lane.automatic(), lane.items().stream().map(ItemSpec::of).toList(),
            lane.latency(), lane.rate())));
        Map<String, ControlRef> parts = new LinkedHashMap<>();
        controls.keySet().forEach(name -> parts.put(name, ref(name)));
        projected.add(new Contribution.Parts(parts));
        projected.add(new Contribution.Protection(Set.copyOf(extent.cells())));
        return List.copyOf(projected);
    }

    @Override
    public WorldPos site() {
        return anchor;
    }

    public ControlRef ref(String control) {
        Placed placed = Objects.requireNonNull(controls.get(control), control);
        return new ControlRef(anchor.at(anchor.cell().offset(placed.offset())), placed.part());
    }

    boolean ownsStoreCell(WorldPos cell) {
        // A hull extent includes its cargo. Fixed controls and the anchor still reserve their cells.
        if (!(anchor.realm() instanceof Realm.Frame) || !anchor.sameRealm(cell) || workshop.isPresent()
                || controls.values().stream().anyMatch(part -> part.carrier().isPresent() || part.entity().isPresent())) {
            return true;
        }
        return anchor.equals(cell) || controls.keySet().stream().map(this::ref).anyMatch(ref -> ref.at().equals(cell));
    }

    public Map<String, Control> resolve(ServerLevel level) {
        Map<String, Control> found = new LinkedHashMap<>();
        for (String name : controls.keySet()) {
            resolve(level, name, found, new LinkedHashSet<>());
        }
        return found;
    }

    private void resolve(ServerLevel level, String name, Map<String, Control> found, Set<String> visiting) {
        if (found.containsKey(name) || !controls.containsKey(name) || !visiting.add(name)) {
            return;
        }
        var placed = controls.get(name);
        if (placed.entity().isPresent()) {
            UUID id = placed.entity().orElseThrow();
            Optional.ofNullable(level.getEntity(id)).filter(entity -> entity.isAlive())
                .map(io.github.izakyl.folkways.core.world.api.WorldSpaces::at)
                .flatMap(at -> Controls.at(level, at).stream().filter(control ->
                    placed.part().equals(control.part()) && control.entityId().filter(id::equals).isPresent())
                    .findFirst()).ifPresent(control -> found.put(name, control));
            return;
        }
        var ref = ref(name);
        var carrier = placed.carrier();
        if (carrier.isPresent()) {
            var placement = carrier.get();
            resolve(level, placement.part(), found, visiting);
            var carrierControl = found.get(placement.part());
            var extension = carrierControl instanceof Signal signal
                ? signal.feedback().flatMap(reading -> reading.read(level))
                : carrierControl instanceof Motion motion ? Optional.of(Value.of(motion.position())) : Optional.empty();
            if (!(extension.orElse(null) instanceof Value.Real real)) {
                return;
            }
            var shift = carrierControl.anchor().cell().subtract(ref(placement.part()).at().cell())
                .offset(placement.shift(real.value()));
            ref = new ControlRef(ref.at().at(ref.at().cell().offset(shift)), ref.part());
        }
        Controls.resolve(level, ref).ifPresent(control -> found.put(name, control));
    }

    /** The named probes, and each signal's feedback under the signal's name unless a probe takes that name. */
    @Override
    public Map<String, Reading> readings(ServerLevel level) {
        Map<String, Reading> all = new LinkedHashMap<>();
        resolve(level).forEach((name, control) -> {
            if (control instanceof Signal signal) {
                signal.feedback().ifPresent(reading -> all.put(name, reading));
            }
            if (control instanceof Motion motion) {
                all.put(name, motion.readings().get("position"));
            }
            control.readings().forEach((suffix, reading) -> all.put(name + "." + suffix, reading));
        });
        routines.forEach((name, use) -> Routines.of(use.routine()).ifPresent(routine ->
            routine.readings(level, this, use).forEach((suffix, value) ->
                all.put(name + "." + suffix, ignored -> Optional.of(value)))));
        probes.forEach((name, probe) -> {
            if (probe.source().isPresent()) {
                return;
            }
            WorldPos at = anchor.at(anchor.cell().offset(probe.offset()));
            Readings.of(probe.probe(), at, probe.config()).ifPresent(reading -> all.put(name, reading));
        });
        FacilitySamples.readings(this).forEach((name, value) -> all.put(name, ignored -> Optional.of(value)));
        return all;
    }

    /**
     * Whether the readings now agree with every target, as a gate's {@code open_when} does: empty when they do,
     * else {@code "state"} when one disagrees or {@code "unknown"} when one cannot be read.
     */
    public Optional<String> unmet(ServerLevel level, Map<String, Value> when) {
        var source = FacilitySamples.predicate(when);
        if (source.isPresent()) {
            return FacilitySamples.predicate(this, source.get());
        }
        if (when.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Reading> readings = readings(level);
        Map<String, Control> resolved = resolve(level);
        for (var target : when.entrySet()) {
            Optional<Value> read = Optional.ofNullable(readings.get(target.getKey()))
                .flatMap(reading -> reading.read(level));
            if (read.isEmpty()) {
                return Optional.of("unknown");
            }
            boolean agrees = resolved.get(target.getKey()) instanceof Signal signal
                ? signal.type().agrees(target.getValue(), read.get()) : target.getValue().equals(read.get());
            if (!agrees) {
                return Optional.of("state");
            }
        }
        return Optional.empty();
    }

    /** Snapshot of projection conditions, independent of probes that shadow signal feedback. */
    Map<String, Value> projectionReadings(ServerLevel level) {
        if (gates.isEmpty() && rides.isEmpty() && keepouts.isEmpty()) {
            return Map.of();
        }
        Map<String, Value> snapshot = new LinkedHashMap<>();
        if (!keepouts.isEmpty()) {
            snapshot.put("projection_tick", Value.of(level.getGameTime()));
        }
        Map<String, Control> resolved = gates.isEmpty() && rides.stream().allMatch(ride -> ride.availableWhen().isEmpty())
            ? Map.of() : resolve(level);
        for (int index = 0; index < gates.size(); index++) {
            String key = Integer.toString(index);
            condition(level, resolved, gates.get(index).openWhen()).ifPresent(value -> snapshot.put(key, value));
        }
        for (int index = 0; index < rides.size(); index++) {
            String key = Ride.conditionKey(index);
            condition(level, resolved, rides.get(index).availableWhen()).ifPresent(value -> snapshot.put(key, value));
        }
        return Map.copyOf(snapshot);
    }

    // A script predicate answers from its cached sample, and only a passing one is published, as for gates.
    private Optional<Value> condition(ServerLevel level, Map<String, Control> resolved, Map<String, Value> when) {
        var script = FacilitySamples.predicate(when);
        if (script.isPresent()) {
            return FacilitySamples.predicate(this, script.get()).isEmpty() ? Optional.of(Value.of(true)) : Optional.empty();
        }
        return matches(level, resolved, when);
    }

    private static Optional<Value> matches(ServerLevel level, Map<String, Control> resolved, Map<String, Value> when) {
        boolean agrees = true;
        for (var target : when.entrySet()) {
            if (!(resolved.get(target.getKey()) instanceof Signal signal) || !signal.valid(level)
                    || signal.type().check(target.getValue()).isPresent()) {
                return Optional.empty();
            }
            var feedback = signal.feedback().flatMap(reading -> reading.read(level));
            if (feedback.isEmpty()) {
                return Optional.empty();
            }
            agrees &= signal.type().agrees(target.getValue(), feedback.get());
        }
        return Optional.of(Value.of(agrees));
    }

    @Override
    public FacilityExtent extent(ServerLevel level) {
        return extent;
    }

    @Override
    public Optional<Rig> rig(ServerLevel level) {
        return Optional.of(new Rig(Rig.of(level, resolve(level)).parts(), controls.entrySet().stream()
            .filter(entry -> entry.getValue().carrier().isPresent())
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                entry -> entry.getValue().carrier().orElseThrow()))));
    }

    @Override
    public List<Trigger> triggers() {
        return java.util.stream.Stream.concat(switches.values().stream().map(Switch::trigger),
            workshop.stream().map(FacilityWorkshop::trigger)).toList();
    }

    public Map<String, Object> data() {
        return DefinitionCodec.write(this);
    }

    public static FacilityDefinition fromData(UUID id, Map<String, ?> data) {
        return DefinitionCodec.read(id, data);
    }

    public CompoundTag save() {
        return DefinitionCodec.save(this);
    }

    public static Optional<FacilityDefinition> load(CompoundTag tag) {
        return Optional.ofNullable(DefinitionCodec.load(tag));
    }
}
