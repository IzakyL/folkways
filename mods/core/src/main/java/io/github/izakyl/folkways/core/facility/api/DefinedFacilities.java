package io.github.izakyl.folkways.core.facility.api;

import static io.github.izakyl.folkways.core.facility.api.FacilityScriptRuns.compile;
import static io.github.izakyl.folkways.core.facility.api.FacilityScriptRuns.script;

import io.github.izakyl.folkways.core.card.api.Card;
import io.github.izakyl.folkways.core.card.api.notice.Notice;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.colony.api.ColonyContext;
import io.github.izakyl.folkways.core.facility.api.FacilityConditions.ConditionState;
import io.github.izakyl.folkways.core.facility.api.FacilityConditions.Evaluation;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.control.Motion;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.script.api.ScriptFault;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Outcome;
import io.github.izakyl.folkways.core.work.api.Stall;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;

/** Server-thread service for one colony's player-defined facilities. */
public final class DefinedFacilities {

    public static final ResourceLocation ID = ResourceLocation.parse("folkways_core:defined_facilities");
    public record Problem(String path, String reason, String detail, List<Notice.Arg> arguments) {
        public Problem {
            arguments = List.copyOf(arguments);
        }

        public Problem(String path, String reason, String detail) {
            this(path, reason, detail, List.of());
        }

        public Problem(String path, Notice refusal) {
            this(path, "refused", refusal.key(), refusal.args());
        }

        public Component describe() {
            Object explanation = reason.equals("refused") && detail.startsWith("folkways.refusal.")
                ? new Notice(detail, arguments).component() : detail;
            return Component.translatable("folkways.facility.problem",
                Component.literal(path), Component.translatable("folkways.facility.problem." + reason, explanation));
        }
    }

    public record Candidate(ControlRef position, Rig.Kind kind, Optional<Value> reading, Optional<UUID> entity) {
    }

    public record Overlap(UUID colony, String name, FacilityExtent extent) {
    }

    public record Discovery(FacilityExtent extent, List<Candidate> controls, List<Facilities.Source> prefabs, List<Overlap> overlaps) {
    }

    public record Result(String routine, long time, String ending, Optional<String> reason) {
    }

    public record View(Map<String, Object> data, Map<String, Object> readings, Map<String, Boolean> triggers,
                       Optional<Result> recent) {
    }

    public enum Change {
        CREATE, REPLACE, DELETE
    }

    public record Preview(Change change, String name, Optional<Map<String, Object>> before,
                          Optional<Map<String, Object>> after, List<Facilities.Source> covered,
                          List<Facilities.Source> restored, List<WorldPos> usedStores, List<Problem> problems) {
        public Component describe() {
            var text = Component.translatable("folkways.facility.preview.action." + change.name().toLowerCase(java.util.Locale.ROOT),
                Component.literal(name));
            if (!covered.isEmpty()) {
                text.append(" ").append(Component.translatable("folkways.facility.preview.covered", covered.size()));
            }
            if (!restored.isEmpty()) {
                text.append(" ").append(Component.translatable("folkways.facility.preview.restored", restored.size()));
            }
            if (!usedStores.isEmpty()) {
                text.append(" ").append(Component.translatable("folkways.facility.preview.used_stores", usedStores.size()));
            }
            return text;
        }
    }

    public static final class CoverageChanged extends net.neoforged.bus.api.Event {

        private final MinecraftServer server;

        private CoverageChanged(MinecraftServer server) {
            this.server = server;
        }

        public MinecraftServer server() {
            return server;
        }
    }

    private final Colony colony;
    private final MinecraftServer server;
    private final Map<String, Entry> facilities = new LinkedHashMap<>();

    private Map<String, RuntimeFacilities.RuntimeFacility> runtime = Map.of();
    private final Map<String, Entry> runtimeEntries = new LinkedHashMap<>();

    private final FacilityConditions conditions;
    private final FacilityScriptRuns scripts;
    private final Map<Preview, Map<FacilityScripts.Source, FacilityScripts.Compiled>> prepared = new WeakHashMap<>();

    interface ConditionProfile {

        void tick(int tick, long nanos);

        void evaluated(int tick, long steps, long nanos);
    }

    void profileConditions(ConditionProfile profile) {
        conditions.conditionProfile = profile;
    }

    static final class Entry {
        FacilityDefinition facility;
        final Map<FacilityScripts.Source, FacilityScripts.Compiled> programs;
        final FacilityRides rides = new FacilityRides();
        final Map<UUID, ScriptRun> scripts = new LinkedHashMap<>();
        final Map<String, ConditionState> conditions = new LinkedHashMap<>();
        final Map<String, Long> days = new LinkedHashMap<>();
        final Map<UUID, String> runs = new LinkedHashMap<>();
        final Map<String, TriggerRun> requests = new LinkedHashMap<>();
        final Set<String> pausedSamples = new HashSet<>();
        final Set<String> pausedAdmissions = new HashSet<>();
        final FacilitySamples samples = new FacilitySamples();
        Result recent;
        CompoundTag workshopRun;
        private Runnable unregister = () -> { };

        private void unpublish() {
            Runnable remove = unregister;
            unregister = () -> { };
            remove.run();
        }

        Entry(FacilityDefinition facility, Map<FacilityScripts.Source, FacilityScripts.Compiled> programs) {
            this.facility = facility;
            this.programs = Map.copyOf(programs);
        }
    }

    private final Posts posts;
    private final PostRuns postRuns;

    public Posts posts() {
        return posts;
    }

    PostRuns postRuns() {
        return postRuns;
    }

    void withdraw(UUID id) {
        if (!postRuns.cancel(id)) {
            colony.withdraw(ID, id);
        }
    }

    private DefinedFacilities(ColonyContext scope) {
        colony = scope.colony();
        server = scope.server();
        posts = new Posts(colony);
        postRuns = new PostRuns(colony, posts);
        conditions = new FacilityConditions(this, colony, server);
        scripts = new FacilityScriptRuns(this, colony, server);
        DefinedFacilityStorage.load(colony, facilities);
    }

    private record Observation(FacilityObserver observer, int period) {
    }

    private static final List<Observation> OBSERVERS = new CopyOnWriteArrayList<>();

    /** Registers an observer during mod setup, with its refresh cadence in server ticks. */
    public static void observe(FacilityObserver observer, int period) {
        if (period < 1) {
            throw new IllegalArgumentException("facility refresh period must be positive");
        }
        OBSERVERS.add(new Observation(observer, period));
    }

    public static void install(ColonyContext scope) {
        DefinedFacilities service = new DefinedFacilities(scope);
        scope.share(service);
        service.facilities.values().forEach(entry -> service.ownershipChanged(entry.facility));
        scope.tick(ignored -> service.tick());
        scope.closed(why -> {
            service.postRuns.close();
            service.posts.close();
            service.facilities.values().forEach(entry -> entry.unpublish());
            service.facilities.clear();
            service.runtimeEntries.clear();
            if (why != io.github.izakyl.folkways.core.colony.api.Closing.SHUTDOWN) {
                service.coverageChanged();
            }
        });
    }

    public static Optional<DefinedFacilities> of(Colony colony) {
        return colony.service(ID, DefinedFacilities.class);
    }

    private static Stream<DefinedFacilities> all(MinecraftServer server) {
        return Colonies.all(server).stream().map(DefinedFacilities::of).flatMap(Optional::stream);
    }

    static Optional<DefinedFacilities> forRide(ServerLevel level, FacilityDefinition facility) {
        return all(level.getServer()).filter(service -> service.ownsRide(facility, level)).findFirst();
    }

    boolean ownsRide(FacilityDefinition facility, ServerLevel level) {
        Entry entry = facilities.get(facility.name());
        return entry != null && entry.facility == facility && Facilities.registered(level, colony.id(), facility.id());
    }

    FacilityRides.Ticket queueRide(FacilityRidePassage.RideFare fare,
            io.github.izakyl.folkways.core.travel.api.Hop hop, net.minecraft.world.entity.Mob body, ServerLevel level) {
        return facilities.get(fare.facility().name()).rides.queue(fare, hop, body, level.getGameTime());
    }

    static Stream<FacilityDefinition> forOwnership(ServerLevel level) {
        return all(level.getServer()).flatMap(service ->
            Stream.concat(service.facilities.values().stream(), service.runtimeEntries.values().stream()))
            .map(entry -> entry.facility).filter(facility -> facility.site().in(level))
            .sorted(Comparator.comparing(FacilityDefinition::id));
    }

    public static boolean covers(ServerLevel level, WorldPos at) {
        return forOwnership(level).anyMatch(facility -> facility.extent().contains(at));
    }

    public static boolean covers(ServerLevel level, UUID entity) {
        return forOwnership(level).anyMatch(facility -> facility.extent().contains(entity));
    }

    public List<String> list() {
        return Stream.concat(facilities.keySet().stream(), runtimeEntries.keySet().stream()).distinct().sorted().toList();
    }

    public Optional<View> read(String name) {
        return Optional.ofNullable(entry(name)).map(entry -> {
            FacilityDefinition facility = entry.facility;
            Map<String, Object> readings = new LinkedHashMap<>();
            facility.site().level(server).ifPresent(level -> Readings.snapshot(level, facility.readings(level))
                .forEach((key, value) -> readings.put(key, DefinitionCodec.value(value))));
            Map<String, Object> data = new LinkedHashMap<>(facility.data());
            var metadata = runtime.get(name);
            if (metadata != null) {
                data.put("seats", metadata.seats().stream().map(seat ->
                    Map.of("entity", seat.entity().toString(), "index", seat.seat())).toList());
            }
            return new View(Map.copyOf(data), Map.copyOf(readings), triggerStates(name),
                Optional.ofNullable(entry.recent));
        });
    }

    public Map<UUID, Map<String, Object>> activeScripts(String name) {
        Entry entry = entry(name);
        if (entry == null) {
            return Map.of();
        }
        Map<UUID, Map<String, Object>> active = new LinkedHashMap<>();
        entry.scripts.forEach((id, run) -> active.put(id, run.view()));
        return active;
    }

    public boolean cancelScript(String name, UUID id) {
        Entry entry = entry(name);
        ScriptRun run = entry == null ? null : entry.scripts.get(id);
        if (run == null) {
            return false;
        }
        scripts.endScript(entry, run, "folkways.facility.script.cancelled_by_player", false);
        return true;
    }

    /** The effective switches, including any condition paused by a script fault. */
    public Map<String, Boolean> triggerStates(String name) {
        Entry entry = entry(name);
        if (entry == null) {
            return Map.of();
        }
        Map<String, Boolean> triggers = new LinkedHashMap<>();
        entry.facility.switches().forEach((key, value) -> {
            ConditionState state = entry.conditions.get(key);
            triggers.put(key, value.enabled() && (state == null || !state.paused));
        });
        return Map.copyOf(triggers);
    }

    /** Every facility of the colony, by name. */
    public List<FacilityDefinition> facilities() {
        return list().stream().map(name -> entry(name).facility).toList();
    }

    /** The facility with this internal id, if this colony has it. */
    public Optional<FacilityDefinition> facility(UUID id) {
        return facilities().stream().filter(facility -> facility.id().equals(id))
            .findFirst();
    }

    public Optional<RuntimeFacilities.RuntimeFacility> runtime(UUID id) {
        return runtime.values().stream().filter(definition -> definition.facility().id().equals(id)).findFirst();
    }

    public boolean runtime(String name) {
        return runtimeEntries.containsKey(name);
    }

    Entry entry(String name) {
        return runtimeEntries.getOrDefault(name, facilities.get(name));
    }

    public List<Stall> stalls(ResourceKey<Level> dimension) {
        List<Stall> stalls = new ArrayList<>();
        for (Entry entry : facilities.values()) {
            WorldPos site = entry.facility.site();
            if (site.level(server).filter(level -> level.dimension().equals(dimension)).isEmpty()) {
                continue;
            }
            entry.requests.forEach((key, request) -> {
                if (request.stalled != null) {
                    String routine = entry.facility.switches().get(key).trigger().routine();
                    stalls.add(new Stall(site, new Routine.Failure(request.stalled, false), Optional.empty(),
                        ID, Optional.of(entry.facility.name() + " / " + routine)));
                }
            });
        }
        return List.copyOf(stalls);
    }

    /** Run identifiers and routines for the work still in the plan. */
    public Map<UUID, String> activeRuns(String name) {
        return Optional.ofNullable(entry(name)).map(entry -> Map.copyOf(entry.runs)).orElse(Map.of());
    }

    /** The routines of the facility's runs still in the plan, oldest first. */
    public List<String> running(String name) {
        return Optional.ofNullable(entry(name)).map(entry -> List.copyOf(entry.runs.values()))
            .orElse(List.of());
    }

    /**
     * Why the facility's card button for this routine cannot be pressed now, if it cannot: {@code "running"} while a
     * run of it is still in the plan, {@code "state"} while its {@code enabled_when} disagrees with the readings,
     * {@code "unknown"} while they cannot be read.
     */
    public Optional<String> disabled(String name, Card.Element.Button button) {
        return disabled(name, button.routine(), button.enabledWhen());
    }

    public Optional<String> disabled(String name, String routine, Map<String, Value> enabledWhen) {
        Entry entry = entry(name);
        if (entry == null) {
            return Optional.of("unknown");
        }
        if (entry.pausedAdmissions.contains(routine)) {
            return Optional.of("unknown");
        }
        if (busy(entry, routine, Map.of(), null)) {
            return Optional.of("running");
        }
        if (enabledWhen.isEmpty()) {
            return Optional.empty();
        }
        ServerLevel level = loaded(entry.facility);
        return level == null ? Optional.of("unknown") : entry.facility.unmet(level, enabledWhen);
    }

    public Optional<Result> recent(String name) {
        return Optional.ofNullable(entry(name)).map(entry -> entry.recent);
    }

    /**
     * Turns a background trigger on or off. The same write as the state path
     * {@code facilities/<name>/triggers/<trigger>/enabled}: scheduled and conditional triggers have switches, and the changed
     * definition goes through the same preview and apply as any other definition change.
     */
    public List<Problem> toggle(String name, String trigger, boolean enabled) {
        if (runtime(name)) {
            return List.of(new Problem("name", "native", name));
        }
        Entry entry = facilities.get(name);
        if (entry == null) {
            return List.of(new Problem("name", "missing", name));
        }
        FacilityDefinition.Switch current = entry.facility.switches().get(trigger);
        if (current == null || !current.trigger().switchable()) {
            return List.of(new Problem("triggers." + trigger, "background", trigger));
        }
        if (current.enabled() == enabled) {
            ConditionState state = entry.conditions.get(trigger);
            if (enabled && state != null && state.paused) {
                entry.conditions.remove(trigger);
                save();
            }
            return List.of();
        }
        Map<String, Object> data = new LinkedHashMap<>(entry.facility.data());
        @SuppressWarnings("unchecked")
        Map<String, Object> triggers = new LinkedHashMap<>((Map<String, Object>) data.get("triggers"));
        @SuppressWarnings("unchecked")
        Map<String, Object> changed = new LinkedHashMap<>((Map<String, Object>) triggers.get(trigger));
        changed.put("enabled", enabled);
        triggers.put(trigger, changed);
        data.put("triggers", triggers);
        Preview preview = preview(name, Optional.of(data));
        return apply(preview);
    }

    public List<Problem> setParameter(String name, String parameter, Value value) {
        if (runtime(name)) {
            return List.of(new Problem("name", "native", name));
        }
        Entry entry = facilities.get(name);
        if (entry == null) {
            return List.of(new Problem("name", "missing", name));
        }
        Map<String, Object> data = new LinkedHashMap<>(entry.facility.data());
        Map<String, Object> parameters = new LinkedHashMap<>();
        entry.facility.parameters().forEach((key, current) -> parameters.put(key, DefinitionCodec.value(current)));
        parameters.put(parameter, DefinitionCodec.value(value));
        data.put("parameters", parameters);
        return apply(preview(name, Optional.of(data)));
    }

    /** Validates the size and addresses of an extent before enumerating its cells. */
    public static Optional<Problem> checkExtentBounds(ServerLevel level, FacilityExtent extent) {
        return DefinitionValidation.checkBounds(level, extent);
    }

    public Optional<Discovery> discover(ServerLevel level, UUID structure) {
        return FacilityExtent.structure(level, structure).map(extent -> discover(level, extent));
    }

    public Discovery discover(ServerLevel level, FacilityExtent extent) {
        return discover(level, extent, at -> true);
    }

    public Discovery discover(ServerLevel level, FacilityExtent extent, Predicate<WorldPos> permitted) {
        checkExtentBounds(level, extent).ifPresent(problem -> {
            throw new IllegalArgumentException(problem.path());
        });
        List<Candidate> candidates = new ArrayList<>();
        Set<FacilityExtent.Element> suggested = new LinkedHashSet<>();
        Set<WorldPos> cells = new LinkedHashSet<>(extent.cells());
        Set<BlockPos> selected = new LinkedHashSet<>();
        for (WorldPos pos : cells) {
            if (pos.realm() instanceof io.github.izakyl.folkways.core.world.api.Realm.Dimension) {
                selected.add(pos.cell());
            }
        }
        if (!selected.isEmpty()) {
            for (var frame : WorldSpaces.frames(level)) {
                FacilityExtent.structure(level, frame.id()).ifPresent(body -> {
                    if (DefinitionValidation.checkBounds(level, body).isEmpty()) {
                        for (WorldPos pos : body.cells()) {
                            if (WorldSpaces.world(level, pos).map(BlockPos::containing)
                                    .filter(selected::contains).isPresent()) {
                                cells.add(pos);
                            }
                        }
                    }
                });
            }
        }
        for (WorldPos pos : cells) {
            if (!permitted.test(pos) || !pos.in(level) || !level.isLoaded(pos.block(level))) {
                continue;
            }
            Map<String, Control> controls = new LinkedHashMap<>();
            Controls.at(level, pos).forEach(control -> controls.putIfAbsent(control.part(), control));
            if (!controls.isEmpty() || !level.getBlockState(pos.block(level)).isAir()) {
                suggested.add(new FacilityExtent.Block(WorldPos.of(level, pos.block(level))));
            }
            for (Control control : controls.values()) {
                Candidate candidate = switch (control) {
                    case Signal signal -> new Candidate(signal.ref(), new Rig.Signalled(signal.type()),
                        signal.feedback().flatMap(reading -> reading.read(level)), signal.entityId());
                    case Port<?> port -> new Candidate(port.ref(), new Rig.Ported(port.resource()),
                        Optional.empty(), port.entityId());
                    case Motion motion -> new Candidate(motion.ref(), new Rig.Sensed(motion.maximum()),
                        Optional.of(Value.of(motion.position())), motion.entityId());
                };
                candidate.entity().ifPresent(id -> suggested.add(new FacilityExtent.Entity(id)));
                candidates.add(candidate);
            }
        }
        for (var entity : level.getAllEntities()) {
            if (!(entity instanceof ServerPlayer) && entity.isAlive()
                    && permitted.test(WorldSpaces.at(entity))
                    && (extent.contains(entity.getUUID()) || extent.contains(WorldSpaces.at(entity))
                        || selected.contains(entity.blockPosition()))) {
                suggested.add(new FacilityExtent.Entity(entity.getUUID()));
            }
        }
        FacilityExtent refined = new FacilityExtent(suggested);
        return new Discovery(refined, List.copyOf(candidates), prefabs(level, refined), overlaps(refined, null));
    }

    private List<Overlap> overlaps(FacilityExtent extent, Entry except) {
        return all(server).flatMap(service -> service.facilities.values().stream()
            .filter(entry -> entry != except && extent.overlaps(entry.facility.extent()))
            .map(entry -> new Overlap(service.colony.id(), entry.facility.name(), entry.facility.extent())))
            .sorted(Comparator.comparing(Overlap::name).thenComparing(Overlap::colony)).toList();
    }

    private static List<Facilities.Source> prefabs(ServerLevel level, FacilityExtent extent) {
        return Stream.concat(Facilities.installed(level), extent.cells().stream().filter(cell -> cell.in(level))
                .flatMap(cell -> Prefabs.nativeCovering(level, cell.block(level)).stream()))
            .filter(facility -> !(facility instanceof FacilityDefinition) && extent.overlaps(facility.extent(level)))
            .map(facility -> new Facilities.Source(facility.id(), facility.site())).distinct().toList();
    }

    /** An empty definition previews deletion. The path name is the colony-local key. */
    public Preview preview(String name, Optional<? extends Map<String, ?>> definition) {
        Prepared result = prepare(name, definition, Map.of());
        if (!result.programs().isEmpty() && result.preview().problems().isEmpty()) {
            prepared.put(result.preview(), result.programs());
        }
        return result.preview();
    }

    private record Prepared(Preview preview, FacilityDefinition facility, Map<FacilityScripts.Source, FacilityScripts.Compiled> programs) {
    }

    private Prepared prepare(String name, Optional<? extends Map<String, ?>> definition,
                             Map<FacilityScripts.Source, FacilityScripts.Compiled> reused) {
        if (runtime(name)) {
            var before = read(name).map(View::data);
            return new Prepared(new Preview(definition.isPresent() ? Change.REPLACE : Change.DELETE, name,
                before, Optional.empty(), List.of(), List.of(), List.of(), List.of(new Problem("name", "native", name))),
                null, Map.of());
        }
        Entry old = facilities.get(name);
        Optional<Map<String, Object>> before = Optional.ofNullable(old).map(entry -> entry.facility.data());
        Change change = definition.isEmpty() ? Change.DELETE : old == null ? Change.CREATE : Change.REPLACE;
        List<Problem> problems = new ArrayList<>();
        FacilityDefinition candidate = null;
        Map<FacilityScripts.Source, FacilityScripts.Compiled> programs = Map.of();
        if (definition.isPresent()) {
            try {
                candidate = FacilityDefinition.fromData(old == null ? UUID.randomUUID() : old.facility.id(), definition.get());
            } catch (IllegalArgumentException invalid) {
                problems.add(new Problem(invalid.getMessage(), "invalid", ""));
            }
            if (candidate != null) {
                if (name.isBlank() || !name.equals(candidate.name())) {
                    problems.add(new Problem("name", "name", name));
                }
                ServerLevel level = candidate.site().level(server).orElse(null);
                Map<FacilityScripts.Source, FacilityScripts.Compiled> cached = new LinkedHashMap<>(reused);
                if (old != null) {
                    cached.putAll(old.programs);
                }
                programs = compile(candidate, cached, problems);
                problems.addAll(DefinitionValidation.check(level, candidate, posts, programs));
                overlaps(candidate.extent(), old).forEach(overlap ->
                    problems.add(new Problem("extent", "overlap", overlap.name())));
            }
        } else if (old == null) {
            problems.add(new Problem("name", "missing", name));
        }
        Optional<Map<String, Object>> after = Optional.ofNullable(candidate).map(FacilityDefinition::data);
        List<Facilities.Source> was = List.of();
        List<Facilities.Source> next = List.of();
        if (problems.isEmpty()) {
            if (old != null) {
                FacilityDefinition previous = old.facility;
                was = previous.site().level(server).map(level -> prefabs(level, previous.extent())).orElse(List.of());
            }
            if (candidate != null) {
                next = prefabs(candidate.site().level(server).orElseThrow(), candidate.extent());
            }
        }
        List<Facilities.Source> covered = new ArrayList<>(next);
        covered.removeAll(was);
        List<Facilities.Source> restored = new ArrayList<>(was);
        restored.removeAll(next);
        List<WorldPos> usedStores = candidate == null || !problems.isEmpty() ? List.of()
            : usedStores(candidate.site().level(server).orElseThrow(), candidate);
        Preview preview = new Preview(change, name, before, after, List.copyOf(covered), List.copyOf(restored),
            usedStores, List.copyOf(problems));
        return new Prepared(preview, candidate, programs);
    }

    private List<WorldPos> usedStores(ServerLevel level, FacilityDefinition facility) {
        return colony.storesIn(level.dimension()).stream()
            .filter(at -> ContainerFacility.chestCells(level, at).stream()
                .anyMatch(cell -> facility.extent().contains(cell) && facility.ownsStoreCell(cell))).toList();
    }

    /** Applies a valid preview if its definition and coverage still agree with the world. */
    public List<Problem> apply(Preview preview) {
        if (!preview.problems().isEmpty()) {
            return preview.problems();
        }
        Prepared now = prepare(preview.name(), preview.after(), prepared.getOrDefault(preview, Map.of()));
        if (!now.preview().problems().isEmpty()) {
            return now.preview().problems();
        }
        if (!now.preview().equals(preview)) {
            return List.of(new Problem("name", "changed", preview.name()));
        }
        Entry previous = facilities.get(preview.name());
        Entry next = now.facility() == null ? null : new Entry(now.facility(), now.programs());
        if (previous != null) {
            previous.unpublish();
        }
        try {
            if (next == null) {
                facilities.remove(preview.name());
                if (previous != null) {
                    ownershipChanged(previous.facility);
                }
            } else {
                if (previous != null) {
                    next.recent = previous.recent;
                    next.days.putAll(previous.days);
                    next.days.keySet().retainAll(next.facility.switches().keySet());
                    next.facility.switches().forEach((name, current) -> {
                        FacilityDefinition.Switch prior = previous.facility.switches().get(name);
                        TriggerRun request = previous.requests.get(name);
                        if (current.equals(prior) && request != null && request.active == null
                                && previous.facility.routines().equals(next.facility.routines())
                                && previous.facility.parameters().equals(next.facility.parameters())
                                && previous.facility.extent().equals(next.facility.extent())
                                && previous.facility.controls().equals(next.facility.controls())) {
                            next.requests.put(name, request);
                        }
                        ConditionState state = previous.conditions.get(name);
                        if (state != null && prior != null && prior.trigger().equals(current.trigger())) {
                            ConditionState retained = new ConditionState();
                            retained.nextEvaluation = state.nextEvaluation;
                            retained.nextRun = state.nextRun;
                            if (prior.enabled() == current.enabled()) {
                                retained.matches = state.matches;
                                retained.paused = state.paused;
                            }
                            next.conditions.put(name, retained);
                        }
                    });
                }
                facilities.put(preview.name(), next);
                ownershipChanged(next.facility);
                if (previous != null) {
                    ownershipChanged(previous.facility);
                }
                sync(next);
            }
        } catch (RuntimeException failure) {
            if (next != null) {
                next.unpublish();
                ownershipChanged(next.facility);
            }
            if (previous == null) {
                facilities.remove(preview.name());
            } else {
                facilities.put(preview.name(), previous);
                ownershipChanged(previous.facility);
                sync(previous);
            }
            throw failure;
        }
        if (previous != null) {
            previous.scripts.values().forEach(run -> {
                if (run.pending != null && previous.rides.owns(run.id)) {
                    run.pending.cancel(false);
                }
                if (run.batch != null) {
                    withdraw(run.batch);
                }
            });
            List.copyOf(previous.runs.keySet()).forEach(this::withdraw);
        }
        save();
        coverageChanged();
        return List.of();
    }

    public List<Problem> run(String name, String routine, Map<String, Value> arguments) {
        Entry entry = entry(name);
        if (entry == null) {
            return List.of(new Problem("name", "missing", name));
        }
        var trigger = entry.facility.switches().entrySet().stream()
            .filter(item -> item.getValue().trigger() instanceof Trigger.Manual manual
                && manual.routine().equals(routine)).findFirst();
        if (trigger.isEmpty()) {
            return List.of(new Problem("routine", "manual", routine));
        }
        if (busy(entry, routine, arguments, null)) {
            return List.of(new Problem("routine", "script_running", routine));
        }
        if (loaded(entry.facility) == null) {
            return List.of(new Problem("extent", "unloaded", ""));
        }
        entry.requests.keySet().removeIf(key -> entry.facility.switches().get(key).trigger().routine().equals(routine));
        return begin(entry, trigger.get().getKey(), arguments);
    }

    public List<Problem> previewRun(String name, String routine, Map<String, Value> arguments) {
        Entry entry = entry(name);
        return entry == null ? List.of(new Problem("name", "missing", name))
            : previewRun(entry.facility, routine, arguments);
    }

    /** Checks a manual run against a definition, including one staged for the same confirmation. */
    public List<Problem> previewRun(String routine, Map<String, Value> arguments, Map<String, ?> definition) {
        FacilityDefinition facility;
        try {
            facility = FacilityDefinition.fromData(UUID.randomUUID(), definition);
        } catch (IllegalArgumentException invalid) {
            return List.of(new Problem(invalid.getMessage(), "invalid", ""));
        }
        return previewRun(facility, routine, arguments);
    }

    private List<Problem> previewRun(FacilityDefinition facility, String routine, Map<String, Value> arguments) {
        var problem = manual(facility, routine);
        if (problem.isPresent()) {
            return List.of(problem.get());
        }
        ServerLevel level = loaded(facility);
        if (level == null) {
            return List.of(new Problem("extent", "unloaded", ""));
        }
        Routine.Use use = facility.routines().get(routine);
        if (use != null && use.routine().equals(Routines.SCRIPT)) {
            return FacilityScriptRuns.source(use) == null
                ? List.of(new Problem("routines." + routine + ".source", "script_source", "")) : List.of();
        }
        try {
            Run run = Routines.run(level, facility, routine, arguments, ID);
            if (run.routine().equals(Routines.WRITE_SEQUENCE)) {
                new WriteSequence.Sequence(run.call()).validate(level, posts.resolve(level, facility));
            }
            return List.of();
        } catch (IllegalArgumentException refused) {
            return List.of(new Problem("routine", "refused", refused.getMessage()));
        }
    }

    private static Optional<Problem> manual(FacilityDefinition facility, String routine) {
        return facility.triggers().stream().anyMatch(trigger ->
            trigger instanceof Trigger.Manual && trigger.routine().equals(routine))
                ? Optional.empty() : Optional.of(new Problem("routine", "manual", routine));
    }

    ServerLevel loaded(FacilityDefinition facility) {
        return facility.site().level(server).filter(facility.extent()::loaded).orElse(null);
    }

    static boolean busy(Entry entry, String name, Map<String, Value> arguments, TriggerRun self) {
        if (entry.runs.containsValue(name) || entry.pausedAdmissions.contains(name)) {
            return true;
        }
        var wanted = writes(entry, name, arguments, self != null && self.pinnedArguments != null);
        return conflicts(entry, wanted, self, null);
    }

    static boolean conflicts(Entry entry, Set<String> wanted, TriggerRun self, ScriptRun script) {
        return entry.scripts.values().stream().anyMatch(run ->
            run != script && !java.util.Collections.disjoint(wanted, run.writing))
            || entry.requests.entrySet().stream().anyMatch(item -> {
                var other = item.getValue();
                if (other == self || other.stalled != null || other.pinnedArguments == null) {
                    return false;
                }
                String routine = entry.facility.switches().get(item.getKey()).trigger().routine();
                return !java.util.Collections.disjoint(wanted, writes(entry, routine, other.pinnedArguments, true));
            });
    }

    private static Set<String> writes(Entry entry, String name, Map<String, Value> arguments, boolean pinned) {
        var use = entry.facility.routines().get(name);
        if (use == null) {
            return Set.of();
        }
        try {
            return Routines.of(use.routine()).map(routine -> routine.writes(pinned ? arguments
                : use.resolve(entry.facility.parameters()).arguments(arguments))).orElse(Set.of());
        } catch (IllegalArgumentException invalid) {
            return Set.of();
        }
    }

    List<Problem> begin(Entry entry, String key, Map<String, Value> arguments) {
        String routine = entry.facility.switches().get(key).trigger().routine();
        if (busy(entry, routine, arguments, null)) {
            return List.of(new Problem("routine", "script_running", routine));
        }
        TriggerRun request = new TriggerRun(arguments);
        entry.requests.put(key, request);
        return submit(entry, key, request);
    }

    private List<Problem> submit(Entry entry, String key, TriggerRun request) {
        FacilityDefinition facility = entry.facility;
        String routine = facility.switches().get(key).trigger().routine();
        ServerLevel level = loaded(facility);
        if (level == null) {
            return List.of(new Problem("extent", "unloaded", ""));
        }
        var kind = facility.routines().get(routine).routine();
        try {
            UUID id;
            if (kind.equals(Routines.SCRIPT)) {
                id = scripts.startScript(entry, routine, request.arguments);
            } else {
                Run run = request.pinnedArguments == null
                    ? Routines.run(level, facility, routine, request.arguments, ID)
                    : Routines.libraryRun(level, facility, routine, kind, request.pinnedArguments, ID, true);
                if (request.pinnedArguments == null) {
                    request.pinnedArguments = run.call().arguments();
                }
                Optional<Outcome> direct = direct(facility, level, run);
                if (direct.isPresent()) {
                    if (direct.get() instanceof Outcome.Failed failure) {
                        failed(entry, key, request, new Routine.Failure(failure.why().translationKey(), false));
                        return List.of(new Problem("routine", "refused", routine));
                    }
                    finished(entry, key, "done");
                    return List.of();
                }
                id = Routines.submitTracked(colony, level, facility, run,
                    (batch, ending) -> server.execute(() -> ended(entry, key, batch, ending)));
            }
            entry.runs.put(id, routine);
            request.active = id;
            save();
            return List.of();
        } catch (IllegalArgumentException refused) {
            String reason = refused.getMessage() != null && refused.getMessage().startsWith("folkways.refusal.")
                ? refused.getMessage() : FacilityRefusal.INVALID.translationKey();
            failed(entry, key, request, new Routine.Failure(reason, false));
            return List.of(new Problem("routine", "refused", refused.getMessage()));
        }
    }

    void startRide(Entry entry, ServerLevel level, String routine, Map<String, Value> arguments, FacilityRides rides) {
        try {
            UUID id;
            if (entry.facility.routines().get(routine).routine().equals(Routines.SCRIPT)) {
                id = scripts.startScript(entry, routine, arguments);
            } else {
                Run run = Routines.run(level, entry.facility, routine, arguments, ID);
                Optional<Outcome> result = direct(entry.facility, level, run);
                if (result.isPresent()) {
                    String failure = result.get() instanceof Outcome.Failed failed ? failed.why().translationKey() : null;
                    rides.completed(failure == null);
                    rideResult(entry, routine, failure);
                    return;
                }
                id = Routines.submitTracked(colony, level, entry.facility, run, (batch, ending) ->
                    server.tell(new net.minecraft.server.TickTask(server.getTickCount(),
                        () -> rideEnded(entry, routine, batch, ending))));
            }
            rides.started(id);
            entry.runs.put(id, routine);
        } catch (IllegalArgumentException refused) {
            rides.completed(false);
            rideResult(entry, routine, FacilityRefusal.INVALID.translationKey());
        }
    }

    private Optional<Outcome> direct(FacilityDefinition facility, ServerLevel level, Run run) {
        Routine operation = Routines.of(run.routine()).orElseThrow();
        return operation instanceof Deposit deposit
            ? deposit.direct(level, facility, run.call(), posts) : operation.direct(level, facility, run.call());
    }

    private void rideEnded(Entry entry, String routine, UUID id, Ending ending) {
        if (entry(entry.facility.name()) != entry || !entry.rides.owns(id)) {
            return;
        }
        entry.runs.remove(id);
        entry.rides.ended(id, ending instanceof Ending.Done);
        rideResult(entry, routine, switch (ending) {
            case Ending.Done ignored -> null;
            case Ending.Failed failed -> failed.why().translationKey();
            default -> FacilityRefusal.GONE.translationKey();
        });
    }

    void cancelRide(Entry entry, UUID id) {
        ScriptRun script = entry.scripts.get(id);
        if (script != null) {
            scripts.endScript(entry, script, FacilityRefusal.GONE.translationKey(), false);
        } else {
            String routine = entry.runs.get(id);
            rideEnded(entry, routine, id, new Ending.Failed(id, FacilityRefusal.GONE));
            withdraw(id);
        }
    }

    private void rideResult(Entry entry, String routine, String failure) {
        entry.recent = new Result(routine, server.overworld().getGameTime(),
            failure == null ? "done" : "failed", Optional.ofNullable(failure));
        save();
    }

    private void ended(Entry entry, String key, UUID run, Ending ending) {
        entry.runs.remove(run);
        TriggerRun request = entry.requests.get(key);
        if (entry(entry.facility.name()) != entry || request == null || !run.equals(request.active)) {
            return;
        }
        if (ending instanceof Ending.Failed failure) {
            var why = failure.why();
            boolean retryable = why instanceof Routine.Failure reported ? reported.retryable()
                : why != FacilityRefusal.GONE && why != FacilityRefusal.INVALID;
            failed(entry, key, request, new Routine.Failure(why.translationKey(), retryable));
            return;
        }
        finished(entry, key, switch (ending) {
            case Ending.Done ignored -> "done";
            case Ending.Dropped ignored -> "dropped";
            case Ending.Revoked ignored -> "revoked";
            case Ending.Replaced ignored -> throw new IllegalStateException();
            case Ending.Failed ignored -> throw new IllegalStateException();
        });
    }

    void finished(Entry entry, String key, String ending) {
        entry.requests.remove(key);
        String routine = entry.facility.switches().get(key).trigger().routine();
        entry.recent = new Result(routine, server.overworld().getGameTime(), ending, Optional.empty());
        if (!runtime(entry.facility.name())) {
            save();
        }
    }

    void failed(Entry entry, String key, TriggerRun request, Routine.Failure failure) {
        Trigger trigger = entry.facility.switches().get(key).trigger();
        long now = server.overworld().getGameTime();
        request.failed(trigger.retry(), now, failure);
        entry.recent = new Result(trigger.routine(), now, request.stalled == null ? "failed" : "stalled",
            Optional.of(failure.translationKey()));
        if (!runtime(entry.facility.name())) {
            save();
        }
    }

    private void tick() {
        ConditionProfile profile = conditions.conditionProfile;
        long started = profile == null ? 0 : System.nanoTime();
        runtime = RuntimeFacilities.loaded(server, colony);
        for (String name : List.copyOf(runtimeEntries.keySet())) {
            if (!runtime.containsKey(name)) {
                Entry removed = runtimeEntries.remove(name);
                ownershipChanged(removed.facility);
                List.copyOf(removed.runs.keySet()).forEach(this::withdraw);
            }
        }
        runtime.forEach((name, definition) -> runtimeEntries.compute(name, (key, previous) -> {
            var facility = definition.facility();
            if (previous == null) {
                ownershipChanged(facility);
                return new Entry(facility, Map.of());
            }
            if (!previous.facility.equals(facility)) {
                ownershipChanged(previous.facility);
                previous.facility = facility;
                ownershipChanged(facility);
            }
            return previous;
        }));
        Map<ServerLevel, List<Post>> declared = new LinkedHashMap<>();
        Stream.concat(facilities.values().stream(), runtimeEntries.values().stream()).forEach(entry -> {
            if (!entry.facility.posts().isEmpty()) {
                ServerLevel level = loaded(entry.facility);
                if (level != null) {
                    declared.computeIfAbsent(level, ignored -> new ArrayList<>())
                        .addAll(Posts.declared(level, entry.facility));
                }
            }
        });
        List<ServerLevel> worlds = new ArrayList<>();
        server.getAllLevels().forEach(worlds::add);
        posts.refresh(worlds, declared);
        postRuns.tick();
        tickFacilities();
        if (profile != null) {
            profile.tick(server.getTickCount(), System.nanoTime() - started);
        }
    }

    private void tickFacilities() {
        long now = server.overworld().getGameTime();
        conditions.finishConditions(now);
        List<Evaluation> evaluations = new ArrayList<>();
        for (Entry entry : facilities.values()) {
            ServerLevel level = sync(entry);
            boolean loaded = level != null && entry.facility.extent().loaded(level);
            if (!entry.facility.rides().isEmpty()) {
                entry.rides.tick(this, entry, level, loaded && ownsRide(entry.facility, level));
            }
            if (!loaded) {
                entry.conditions.values().forEach(state -> state.matches = 0);
                entry.scripts.values().forEach(run -> {
                    if (run.pending != null) {
                        run.pending.cancel(false);
                        run.pending = null;
                    }
                    if (run.admission != null) {
                        run.admission.cancel(false);
                        run.admission = null;
                    }
                });
            }
            if (level == null) {
                continue;
            }
            if (loaded) {
                if (entry.samples.tick(entry, level, colony, now)) {
                    save();
                }
                for (Observation observation : OBSERVERS) {
                    if (now % observation.period() == 0) {
                        observation.observer().refresh(level, colony, entry.facility, now);
                    }
                }
            }
            if (loaded && !entry.scripts.isEmpty()) {
                for (ScriptRun run : List.copyOf(entry.scripts.values())) {
                    scripts.tickScript(entry, level, run);
                }
            }
            if (loaded) {
                for (var item : List.copyOf(entry.requests.entrySet())) {
                    TriggerRun request = item.getValue();
                    String routine = entry.facility.switches().get(item.getKey()).trigger().routine();
                    if (request.active == null && request.stalled == null && now >= request.due
                            && !busy(entry, routine, request.pinnedArguments == null
                                ? request.arguments : request.pinnedArguments, request)
                            && (request.pinnedArguments == null || Routines.of(entry.facility.routines().get(routine)
                                .routine()).orElseThrow().ready(level, entry.facility, request.pinnedArguments))) {
                        submit(entry, item.getKey(), request);
                    }
                }
            }
            conditions.collect(entry, level, loaded, evaluations);
        }
        conditions.evaluate(evaluations, now);
    }

    Optional<FacilityScripts.Compiled> workshopProgram(FacilityDefinition facility) {
        Entry entry = entry(facility.name());
        return entry == null ? Optional.empty() : facility.workshop()
            .map(workshop -> script(entry, workshop.trigger().routine()));
    }

    CompoundTag workshopRun(FacilityDefinition facility) {
        Entry entry = entry(facility.name());
        return entry == null || entry.facility != facility ? null : entry.workshopRun;
    }

    void workshopRun(FacilityDefinition facility, CompoundTag checkpoint) {
        Entry entry = entry(facility.name());
        if (entry != null && entry.facility == facility) {
            entry.workshopRun = checkpoint;
            save();
        }
    }

    boolean workshopPaused(FacilityDefinition facility, String sample) {
        Entry entry = entry(facility.name());
        return entry != null && entry.facility == facility && entry.pausedSamples.contains(sample);
    }

    void pauseWorkshop(FacilityDefinition facility, String sample, String routine, ScriptFault fault) {
        Entry entry = entry(facility.name());
        if (entry != null && entry.facility == facility) {
            entry.pausedSamples.add(sample);
            workshopResult(facility, routine, "folkways.facility.script." + FacilityScripts.code(fault));
        }
    }

    void workshopResult(FacilityDefinition facility, String routine, String failure) {
        Entry entry = entry(facility.name());
        if (entry != null && entry.facility == facility) {
            entry.recent = new Result(routine, server.overworld().getGameTime(),
                failure == null ? "done" : "failed", Optional.ofNullable(failure));
            save();
        }
    }

    private ServerLevel sync(Entry entry) {
        FacilityDefinition facility = entry.facility;
        ServerLevel level = facility.site().level(server).orElse(null);
        if (level != null && facility.extent().loaded(level)) {
            if (!Facilities.registered(level, colony.id(), facility.id())) {
                entry.unpublish();
                Runnable remove = Facilities.register(level, colony, facility, ProcessingWorkshop.of(colony, this, facility));
                var observers = OBSERVERS.stream().map(Observation::observer).toList();
                entry.unregister = () -> {
                    entry.samples.unload(facility);
                    remove.run();
                    observers.forEach(observer -> observer.removed(level, colony, facility));
                };
                observers.forEach(observer -> observer.published(level, colony, facility));
            }
        } else {
            entry.unpublish();
        }
        return level;
    }

    private void ownershipChanged(FacilityDefinition facility) {
        facility.site().level(server).ifPresent(Facilities::ownershipChanged);
    }

    private void coverageChanged() {
        for (ServerLevel level : server.getAllLevels()) {
            Facilities.ownershipChanged(level);
            Facilities.refreshPrefabs(level);
        }
        NeoForge.EVENT_BUS.post(new CoverageChanged(server));
    }

    static Map<String, Value> scriptInput(ServerLevel level, FacilityDefinition facility) {
        return FacilityScriptRuns.scriptInput(level, facility);
    }

    void save() {
        DefinedFacilityStorage.save(colony, facilities);
    }
}
