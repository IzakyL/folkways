package io.github.izakyl.folkways.core.facility.api;

import static io.github.izakyl.folkways.core.facility.api.DefinedFacilities.ID;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities.Entry;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities.Problem;
import io.github.izakyl.folkways.core.facility.api.DefinedFacilities.Result;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.script.api.ColonyScripts;
import io.github.izakyl.folkways.core.script.api.ScriptFault;
import io.github.izakyl.folkways.core.script.api.ScriptRuntime;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.world.api.Realm;
import io.github.izakyl.folkways.core.world.api.WorldSpaces;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

final class FacilityScriptRuns {

    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("folkways-facility-scripts");

    private static final int MAX_TARGET_CELLS = 512;
    private static final int MAX_APPROACH_CELLS = 4096;
    private static final int APPROACH_ROOM = 2;

    private final DefinedFacilities facilities;
    private final Colony colony;
    private final MinecraftServer server;

    FacilityScriptRuns(DefinedFacilities facilities, Colony colony, MinecraftServer server) {
        this.facilities = facilities;
        this.colony = colony;
        this.server = server;
    }

    static FacilityScripts.Source source(Routine.Use use) {
        if (use != null && use.routine().equals(Routines.SCRIPT)
                && use.with().get("source") instanceof Value.Text text) {
            return new FacilityScripts.Source(text.value(), "step");
        }
        return null;
    }

    static Map<FacilityScripts.Source, FacilityScripts.Compiled> compile(FacilityDefinition facility,
            Map<FacilityScripts.Source, FacilityScripts.Compiled> cached, List<Problem> problems) {
        Map<FacilityScripts.Source, FacilityScripts.Compiled> programs = new LinkedHashMap<>();
        facility.routines().forEach((name, use) -> {
            if (use.routine().equals(Routines.SCRIPT)) {
                String path = "routines." + name + ".source";
                FacilityScripts.Source source = source(use);
                if (source == null) {
                    problems.add(new Problem(path, "script_source", ""));
                } else {
                    compile(source, path, programs, cached, problems);
                }
            }
        });
        facility.switches().forEach((name, entry) -> {
            if (entry.trigger() instanceof Trigger.Condition condition) {
                condition.script().ifPresent(text -> compile(new FacilityScripts.Source(text, "when"),
                    "triggers." + name + ".when", programs, cached, problems));
            }
        });
        FacilitySamples.sources(facility).forEach((path, source) -> compile(source, path, programs, cached, problems));
        return programs;
    }

    private static void compile(FacilityScripts.Source source, String path,
            Map<FacilityScripts.Source, FacilityScripts.Compiled> programs,
            Map<FacilityScripts.Source, FacilityScripts.Compiled> cached, List<Problem> problems) {
        try {
            programs.computeIfAbsent(source, script -> {
                var ready = cached.get(script);
                return ready == null ? FacilityScripts.compile(script) : ready;
            });
        } catch (ScriptFault fault) {
            problems.add(new Problem(path, FacilityScripts.code(fault), FacilityScripts.detail(fault)));
        }
    }

    static FacilityScripts.Compiled script(Entry entry, String routine) {
        Routine.Use use = entry.facility.routines().get(routine);
        FacilityScripts.Source source = use == null ? null : source(use);
        return source == null ? null : entry.programs.get(source);
    }

    UUID startScript(Entry entry, String routine, Map<String, Value> arguments) {
        if (entry.pausedAdmissions.contains(routine)) {
            throw new IllegalArgumentException("script paused");
        }
        if (script(entry, routine) == null) {
            throw new IllegalArgumentException("missing script source");
        }
        var args = entry.facility.routines().get(routine).resolve(entry.facility.parameters()).arguments(arguments);
        args.remove("source");
        ScriptRun run = new ScriptRun(UUID.randomUUID(), routine, args);
        entry.scripts.put(run.id, run);
        return run.id;
    }

    /** The contexts the program's evaluations need, taken now on the server thread. */
    static FacilityScriptContexts.Taken shots(ServerLevel level, Colony colony, FacilityDefinition facility,
                                              FacilityScripts.Compiled program) {
        return FacilityScriptContexts.take(level, colony, facility, program);
    }

    static Map<String, Value> scriptInput(ServerLevel level, FacilityDefinition facility) {
        return scriptInput(level, facility, Map.of());
    }

    static Map<String, Value> scriptInput(ServerLevel level, FacilityDefinition facility, Map<String, Value> args) {
        return scriptInput(level, facility, args, box -> true);
    }

    static Map<String, Value> scriptInput(ServerLevel level, FacilityDefinition facility, Map<String, Value> args,
            Predicate<BoundingBox> observed) {
        Map<String, Value> snapshot = new LinkedHashMap<>(facility.parameters());
        snapshot.putAll(Readings.snapshot(level, facility.readings(level)));
        frame(level, facility).ifPresent(frame -> snapshot.put("_frame", frame));
        snapshot.remove("_target_point");
        snapshot.remove("_target_center");
        if (args.get("target") instanceof Value.Text target) {
            try {
                var entity = level.getEntity(UUID.fromString(target.value()));
                if (entity != null && entity.isAlive()) {
                    var center = entity.getBoundingBox().getCenter();
                    snapshot.put("_target_center", Value.of(center.x, center.y, center.z));
                    var vehicle = entity.getVehicle();
                    var address = WorldSpaces.at(level, (vehicle == null ? entity : vehicle).blockPosition());
                    WorldSpaces.world(level, address).ifPresent(point ->
                        snapshot.put("_target_point", Value.of(point.x, point.y, point.z)));
                }
            } catch (IllegalArgumentException invalid) {
                // A textual argument need not be an entity target.
            }
        }
        snapshot.remove("_target");
        snapshot.remove("_target_approach");
        snapshot.remove("_sweep_drive");
        targetBounds(level, args.get("target")).filter(observed).ifPresent(box -> {
            snapshot.put("_target", target(level, box));
            facility.resolve(level).forEach((name, control) -> {
                if (control instanceof Signal signal && signal.valid(level)) {
                    signal.travelPosition(level).ifPresent(at -> {
                        if (name.equals("destination") || !snapshot.containsKey("_sweep_drive")) {
                            var drive = new CompoundTag();
                            drive.putString("control", name);
                            drive.put("position", vector(at));
                            snapshot.put("_sweep_drive", new Value.Data(drive));
                        }
                    });
                }
            });
            approachBounds(level, box, snapshot, args).filter(observed).ifPresent(approach ->
                snapshot.put("_target_approach", target(level, approach)));
        });
        return Map.copyOf(snapshot);
    }

    static Optional<BoundingBox> targetBounds(ServerLevel level, Value target) {
        List<Double> axes;
        if (target instanceof Value.Vector vector) {
            axes = vector.values();
        } else if (target instanceof Value.Sequence sequence && sequence.values().size() == 6
                && sequence.values().stream().allMatch(Value.Real.class::isInstance)) {
            axes = sequence.values().stream().map(axis -> ((Value.Real) axis).value()).toList();
        } else {
            return Optional.empty();
        }
        if (axes.size() != 6 || axes.stream().anyMatch(axis -> axis != axis.intValue()
                || axis == Integer.MIN_VALUE || axis == Integer.MAX_VALUE)) {
            return Optional.empty();
        }
        int[] around = new int[6];
        long cells = 1;
        for (int i = 0; i < 3; i++) {
            int low = axes.get(i).intValue();
            int high = axes.get(i + 3).intValue();
            long width = (long) high - low + 1;
            if (width < 1 || width > MAX_TARGET_CELLS || cells * width > MAX_TARGET_CELLS) {
                return Optional.empty();
            }
            cells *= width;
            around[i] = low - 1;
            around[i + 3] = high + 1;
        }
        var box = new BoundingBox(around[0], around[1], around[2], around[3], around[4], around[5]);
        return loaded(level, box) ? Optional.of(box) : Optional.empty();
    }

    private static boolean loaded(ServerLevel level, BoundingBox box) {
        for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++) {
            for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) {
                if (!level.hasChunk(x, z)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Observe a bounded entry route when a tool starts outside the target's border: the space from the box out to
     * where the facility's positioned controls stand now, nearest first while it stays bounded. A routine may name
     * the tool's control and offset in its arguments; otherwise every control reading with a position counts, since
     * the routine may pass them to a library only inside its own source.
     */
    static Optional<BoundingBox> approachBounds(ServerLevel level, BoundingBox box, Map<String, Value> readings,
            Map<String, Value> args) {
        Vec3 offset = args.get("offset") instanceof Value.Vector shift && shift.values().size() == 3
            ? new Vec3(shift.values().get(0), shift.values().get(1), shift.values().get(2)) : Vec3.ZERO;
        List<Vec3> points = new ArrayList<>();
        if (args.get("control") instanceof Value.Text text) {
            position(readings.get(text.value())).ifPresent(points::add);
        } else {
            readings.forEach((name, value) -> {
                if (!name.startsWith("_")) {
                    position(value).ifPresent(points::add);
                }
            });
        }
        var middle = Vec3.atCenterOf(box.getCenter());
        points.sort(java.util.Comparator.comparingDouble(point -> point.distanceToSqr(middle)));
        var min = new BlockPos(box.minX(), box.minY(), box.minZ());
        var max = new BlockPos(box.maxX(), box.maxY(), box.maxZ());
        BlockPos below = min;
        BlockPos above = max;
        for (Vec3 point : points) {
            var cell = BlockPos.containing(point.add(offset));
            // Room for the footprint around the working point and for the first waypoint beside it.
            var low = new BlockPos(Math.min(below.getX(), cell.getX() - APPROACH_ROOM),
                Math.min(below.getY(), cell.getY() - APPROACH_ROOM), Math.min(below.getZ(), cell.getZ() - APPROACH_ROOM));
            var high = new BlockPos(Math.max(above.getX(), cell.getX() + APPROACH_ROOM),
                Math.max(above.getY(), cell.getY() + APPROACH_ROOM), Math.max(above.getZ(), cell.getZ() + APPROACH_ROOM));
            long volume = (long) (high.getX() - low.getX() + 1) * (high.getY() - low.getY() + 1)
                * (high.getZ() - low.getZ() + 1);
            if (volume <= MAX_APPROACH_CELLS) {
                below = low;
                above = high;
            }
        }
        if (below.equals(min) && above.equals(max)) {
            return Optional.empty();
        }
        var approach = BoundingBox.fromCorners(below, above);
        return loaded(level, approach) ? Optional.of(approach) : Optional.empty();
    }

    private static Optional<Vec3> position(Value value) {
        if (!(value instanceof Value.Data data)) {
            return Optional.empty();
        }
        var end = data.tag();
        if (!end.contains("x") || !end.contains("y") || !end.contains("z")) {
            return Optional.empty();
        }
        return Optional.of(new Vec3(end.getDouble("x"), end.getDouble("y"), end.getDouble("z")));
    }

    private static Value target(ServerLevel level, BoundingBox box) {
        var blocked = new ListTag();
        for (BlockPos cell : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(),
                box.maxX(), box.maxY(), box.maxZ())) {
            if (!level.getBlockState(cell).getCollisionShape(level, cell).isEmpty()) {
                blocked.add(new IntArrayTag(new int[] {cell.getX(), cell.getY(), cell.getZ()}));
            }
        }
        var bounds = new ListTag();
        for (int axis : new int[] {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()}) {
            bounds.add(IntTag.valueOf(axis));
        }
        var result = new CompoundTag();
        result.put("around", bounds);
        result.put("blocked", blocked);
        return new Value.Data(result);
    }

    /**
     * Where a facility on a moving structure stands now: the world position of its space's origin and the world
     * direction of each of its axes, so a script can carry points between the structure's space and the world.
     */
    static Optional<Value> frame(ServerLevel level, FacilityDefinition facility) {
        if (!(facility.anchor().realm() instanceof Realm.Frame realm)) {
            return Optional.empty();
        }
        return WorldSpaces.frame(level, realm.structure()).map(frame -> {
            var origin = frame.toWorld(Vec3.ZERO);
            var tag = new CompoundTag();
            tag.put("origin", vector(origin));
            tag.put("x", vector(frame.toWorld(new Vec3(1, 0, 0)).subtract(origin)));
            tag.put("y", vector(frame.toWorld(new Vec3(0, 1, 0)).subtract(origin)));
            tag.put("z", vector(frame.toWorld(new Vec3(0, 0, 1)).subtract(origin)));
            return new Value.Data(tag);
        });
    }

    private static ListTag vector(Vec3 v) {
        var list = new ListTag();
        list.add(DoubleTag.valueOf(v.x));
        list.add(DoubleTag.valueOf(v.y));
        list.add(DoubleTag.valueOf(v.z));
        return list;
    }

    record Admission(boolean ready, String refusal) {
    }

    static Admission admission(FacilityScripts.Compiled program, Map<String, Value> readings,
            Map<String, Value> args, Map<String, String> libraries) {
        return admission(program, readings, args, libraries, null);
    }

    static Admission admission(FacilityScripts.Compiled program, Map<String, Value> readings,
            Map<String, Value> args, Map<String, String> libraries, FacilityScriptContexts.Taken shots) {
        String refusal = program.program().definesFunction("check")
            ? program.hook("check", Map.of(), args, libraries, data ->
                data == null ? null : new DefinitionCodec.Field("check", data).string())
            : null;
        boolean ready = refusal == null && (!program.program().definesFunction("ready")
            || program.hook("ready", readings, args, libraries, FacilitySamples::bool, shots));
        return new Admission(ready, refusal);
    }

    static boolean hasAdmission(FacilityScripts.Compiled program) {
        return program.program().definesFunction("check") || program.program().definesFunction("ready");
    }

    private boolean admit(Entry entry, ServerLevel level, ScriptRun run, FacilityScripts.Compiled program) {
        if (!hasAdmission(program)) {
            run.admitted = true;
            return true;
        }
        int tick = server.getTickCount();
        if (run.admission != null) {
            if (run.dispatched == tick || !run.admission.isDone()) {
                return false;
            }
            var future = run.admission;
            run.admission = null;
            try {
                Admission result = ScriptRuntime.result(future);
                if (result.refusal() != null) {
                    endScript(entry, run, result.refusal(), false);
                } else {
                    run.admitted = result.ready();
                }
            } catch (ScriptFault fault) {
                entry.pausedAdmissions.add(run.routine);
                endScript(entry, run, "folkways.facility.script." + FacilityScripts.code(fault), false);
            }
            return run.admitted;
        }
        long now = level.getGameTime();
        if (now >= run.nextAdmission) {
            var readings = scriptInput(level, entry.facility, run.arguments);
            var libraries = ColonyScripts.snapshot(colony);
            var args = Map.copyOf(run.arguments);
            var shots = shots(level, colony, entry.facility, program);
            run.admission = FacilityScripts.submit(false, () -> admission(program, readings, args, libraries, shots));
            run.dispatched = tick;
            run.nextAdmission = now + 10;
        }
        return false;
    }

    void tickScript(Entry entry, ServerLevel level, ScriptRun run) {
        if (run.batch != null || level.getGameTime() < run.nextStep) {
            return;
        }
        if (run.pending == null) {
            var program = script(entry, run.routine);
            if (!run.admitted && !admit(entry, level, run, program)) {
                return;
            }
            var readings = scriptInput(level, entry.facility, run.arguments);
            String state = run.state;
            String failure = run.failure;
            Map<String, Value> args = run.arguments;
            var libraries = ColonyScripts.snapshot(colony);
            var handler = run.handler;
            var shots = shots(level, colony, entry.facility, program);
            run.pending = FacilityScripts.submit(false, () -> program.step(state, readings, args, failure, libraries,
                handler, Map.of(), shots));
            run.dispatched = server.getTickCount();
        } else if (run.dispatched != server.getTickCount() && run.pending.isDone()) {
            var pending = run.pending;
            run.pending = null;
            try {
                applyStep(entry, level, run, ScriptRuntime.result(pending));
            } catch (ScriptFault fault) {
                // The run's result keeps only the fault's kind; its detail goes to the log for whoever debugs it.
                LOGGER.info("Facility script {} ended: {} ({})", run.routine, FacilityScripts.code(fault),
                    FacilityScripts.detail(fault));
                endScript(entry, run, "folkways.facility.script." + FacilityScripts.code(fault),
                    FacilityScripts.code(fault).equals("script_timeout"));
            }
        }
    }

    private void applyStep(Entry entry, ServerLevel level, ScriptRun run, FacilityScripts.Step step) {
        var actions = step.actions();
        if (actions.size() == 1 && actions.getFirst().base() instanceof FacilityScripts.Terminal terminal) {
            endScript(entry, run, terminal.failure(), terminal.retryable());
            return;
        }
        String previous = run.state;
        int previousStep = run.step;
        run.handler = null;
        try {
            var writing = ScriptRun.writes(actions);
            if (DefinedFacilities.conflicts(entry, writing, null, run)) {
                throw new IllegalArgumentException("script_running");
            }
            var batch = actions.isEmpty() ? null : run.plan(level, entry.facility, actions);
            run.state = step.state();
            run.step++;
            run.failure = null;
            if (batch == null) {
                run.nextStep = level.getGameTime() + 10;
            } else {
                run.batch = batch.id();
                run.writing = writing;
                java.util.function.BiConsumer<UUID, Ending> ended = (id, ending) ->
                    server.execute(() -> batchEnded(entry, run, id, ending));
                if (batch.graph().nodes().stream().anyMatch(ItemCollection.class::isInstance)) {
                    if (!facilities.postRuns().startStaffedCollection(level, entry.facility, batch, run.resumed,
                            entry.rides.owns(run.id), ended)) {
                        var stowing = new StowingScriptBatch(colony, facilities, entry.facility, batch);
                        run.batch = stowing.id();
                        colony.submit(ID, level.dimension(), Grown.of(stowing),
                            (id, ending) -> ended.accept(id, stowing.result(ending)));
                    }
                } else if (!facilities.postRuns().start(level, entry.facility, batch, run.resumed,
                        entry.rides.owns(run.id), ended)) {
                    colony.submit(ID, level.dimension(), Grown.of(batch), ended);
                }
            }
        } catch (IllegalArgumentException invalid) {
            run.batch = null;
            run.writing = Set.of();
            run.state = previous;
            run.step = previousStep;
            run.failure = invalid.getMessage();
        }
        facilities.save();
    }

    private void batchEnded(Entry entry, ScriptRun run, UUID id, Ending ending) {
        if (facilities.entry(entry.facility.name()) != entry || !id.equals(run.batch)) {
            return;
        }
        if (!run.batchEnded(ending)) {
            endScript(entry, run, run.failure, false);
            return;
        }
        facilities.save();
    }

    void endScript(Entry entry, ScriptRun run, String failure, boolean retryable) {
        entry.scripts.remove(run.id);
        if (run.pending != null) {
            run.pending.cancel(false);
        }
        if (run.admission != null) {
            run.admission.cancel(false);
        }
        if (run.batch != null) {
            UUID batch = run.batch;
            run.batch = null;
            facilities.withdraw(batch);
        }
        run.writing = Set.of();
        entry.runs.remove(run.id);
        entry.rides.ended(run.id, failure == null);
        for (var item : entry.requests.entrySet()) {
            if (run.id.equals(item.getValue().active)) {
                if (failure == null) {
                    facilities.finished(entry, item.getKey(), "done");
                } else {
                    facilities.failed(entry, item.getKey(), item.getValue(), new Routine.Failure(failure, retryable));
                }
                return;
            }
        }
        entry.recent = new Result(run.routine, server.overworld().getGameTime(),
            failure == null ? "done" : "failed", Optional.ofNullable(failure));
        facilities.save();
    }
}
