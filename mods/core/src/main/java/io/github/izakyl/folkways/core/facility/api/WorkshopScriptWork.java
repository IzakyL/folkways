package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.work.api.ReservationPool;
import io.github.izakyl.folkways.core.plan.api.RunBoundary;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.script.api.ColonyScripts;
import io.github.izakyl.folkways.core.script.api.ScriptFault;
import io.github.izakyl.folkways.core.script.api.ScriptRuntime;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.work.api.Workload;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.world.api.Stances;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

/** The booked recipe boundary. The script submits ordinary work for its actions. */
final class WorkshopScriptWork implements RunBoundary {

    private final FacilityDefinition facility;
    private final FacilityScripts.Compiled program;
    private final Colony colony;
    private final NodeSpec spec;
    private final String routine;
    private final Map<String, Object> context;
    private final DefinedFacilities facilities;
    private final CompoundTag checkpoint;
    private final FacilityWorkshop.Batch recipe;
    private final int runs;
    private final ProcessingWorkshop workshop;
    private final ReservationPool reservations;
    private long savedRevision;
    private ScriptRun run;
    private boolean paused;
    private FacilityScripts.Terminal terminal;
    private ServerLevel level;

    WorkshopScriptWork(Produce demand, FacilityDefinition facility, FacilityWorkshop definition,
                       FacilityWorkshop.Batch batch, int runs, FacilityScripts.Compiled program, Colony colony,
                       DefinedFacilities facilities, CompoundTag checkpoint, ProcessingWorkshop workshop,
                       HolderLookup.Provider registries) {
        this.facility = facility;
        this.program = program;
        this.colony = colony;
        this.facilities = facilities;
        this.workshop = workshop;
        recipe = batch;
        this.runs = runs;
        this.checkpoint = checkpoint;
        reservations = new ReservationPool(batch.inputs(), batch.outputs());
        if (checkpoint != null) {
            reservations.restore(checkpoint.getCompound("reservations"), registries);
        }
        routine = definition.trigger().routine();
        spec = NodeSpec.of(demand.id(), DefinedFacilities.ID, new WorkSite.AtBlock(facility.site()), Stances.WHEREVER,
            Workload.Once.of(0)).gives(batch.outputs()).estimate(batch.ticks()).done();
        context = Map.of("recipe", Map.of("id", batch.id().toString(), "ticks", batch.ticks(),
            "inputs", batch.inputs().stream().map(need -> item(need.spec(), need.count())).toList(),
            "outputs", batch.outputs().stream().map(amount -> item(amount.spec(), amount.count())).toList()),
            "demand", item(demand.wanted(), demand.count()));
    }

    private static Map<String, Object> item(ItemSpec item, long count) {
        if (!item.anyOf().isEmpty()) {
            return Map.of("items", item.anyOf().stream().map(spec -> item(spec, count).get("item"))
                .sorted(java.util.Comparator.comparing(Object::toString)).toList(), "count", count);
        }
        return Map.of("item", item.item().map(Object::toString)
            .orElseGet(() -> "#" + item.tag().orElseThrow().location()), "count", count);
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public ReservationPool reservations() {
        return reservations;
    }

    @Override
    public Optional<RefusalKind> planned(ServerLevel level, Consumer<RefusalKind> fail) {
        return workshop.admit(this, () -> fail.accept(FacilityRefusal.GONE))
            ? Optional.empty() : Optional.of(FacilityRefusal.GONE);
    }

    @Override
    public Optional<Ending> advance(ServerLevel level) {
        if (run == null) {
            this.level = level;
            var arguments = facility.routines().get(routine).resolve(facility.parameters()).arguments(Map.of());
            arguments.remove("source");
            run = checkpoint == null ? new ScriptRun(id(), routine, arguments)
                : ScriptRun.load(checkpoint.getCompound("run"));
            workshop.started(this);
            save();
        }
        if (terminal == null || terminal.failure() == null && reservations.hasCarried()) {
            return Optional.empty();
        }
        String failure = terminal.failure();
        if (failure == null && !reservations.fulfilled()) {
            failure = FacilityRefusal.REFUSED.translationKey();
        }
        facilities.workshopResult(facility, routine, failure);
        resolve();
        return Optional.of(failure == null ? Ending.DONE
            : new Ending.Failed(id(), new Routine.Failure(failure, terminal.retryable())));
    }

    void tick() {
        if (reservations.revision() != savedRevision) {
            save();
        }
        if (terminal != null || run.batch != null || level.getGameTime() < run.nextStep) {
            return;
        }
        int tick = level.getServer().getTickCount();
        if (!run.admitted && !admit(tick)) {
            return;
        }
        if (run.pending == null) {
            var input = FacilityScriptRuns.scriptInput(level, facility, run.arguments);
            var libraries = ColonyScripts.snapshot(colony);
            String state = run.state;
            String failure = run.failure;
            var handler = run.handler;
            run.pending = FacilityScripts.submit(false,
                () -> program.step(state, input, run.arguments, failure, libraries, handler, context));
            run.dispatched = tick;
        } else if (run.dispatched != tick && run.pending.isDone()) {
            var pending = run.pending;
            run.pending = null;
            try {
                apply(ScriptRuntime.result(pending));
            } catch (ScriptFault fault) {
                String code = FacilityScripts.code(fault);
                terminal = new FacilityScripts.Terminal("folkways.facility.script." + code, code.equals("script_timeout"));
            } catch (IllegalArgumentException invalid) {
                run.handler = null;
                run.failure = invalid.getMessage();
                save();
            }
        }
    }

    private boolean admit(int tick) {
        if (!FacilityScriptRuns.hasAdmission(program)) {
            run.admitted = true;
            return true;
        }
        if (run.admission != null) {
            if (run.dispatched == tick || !run.admission.isDone()) {
                return false;
            }
            var future = run.admission;
            run.admission = null;
            try {
                var result = ScriptRuntime.result(future);
                run.admitted = result.ready();
                if (result.refusal() != null) {
                    terminal = new FacilityScripts.Terminal(result.refusal(), false);
                }
            } catch (ScriptFault fault) {
                facilities.pauseWorkshop(facility, "workshop.admission", routine, fault);
                terminal = new FacilityScripts.Terminal("folkways.facility.script." + FacilityScripts.code(fault), false);
            }
            return run.admitted;
        }
        long now = level.getGameTime();
        if (now >= run.nextAdmission) {
            var readings = FacilityScriptRuns.scriptInput(level, facility, run.arguments);
            var libraries = ColonyScripts.snapshot(colony);
            var args = run.arguments;
            run.admission = FacilityScripts.submit(false,
                () -> FacilityScriptRuns.admission(program, readings, args, libraries));
            run.dispatched = tick;
            run.nextAdmission = now + 10;
        }
        return false;
    }

    private void apply(FacilityScripts.Step step) {
        var actions = step.actions();
        if (actions.size() == 1 && actions.getFirst().base() instanceof FacilityScripts.Terminal result) {
            terminal = result;
            return;
        }
        var batch = actions.isEmpty() ? null : run.plan(level, facility, actions, this);
        if (batch != null) {
            java.util.function.BiConsumer<UUID, Ending> ended = (id, ending) -> {
                if (id.equals(run.batch)) {
                    if (!run.batchEnded(ending)) {
                        terminal = new FacilityScripts.Terminal(run.failure, false);
                    }
                    save();
                }
            };
            if (!facilities.postRuns().start(level, facility, batch, run.resumed, false, ended)) {
                colony.submit(DefinedFacilities.ID, level.dimension(), Grown.of(batch), ended);
            }
            run.batch = batch.id();
        } else {
            run.nextStep = level.getGameTime() + 10;
        }
        run.handler = null;
        run.state = step.state();
        run.step++;
        run.failure = null;
        save();
    }

    private void save() {
        CompoundTag saved = new CompoundTag();
        saved.putString("recipe", recipe.id().toString());
        saved.putInt("runs", runs);
        saved.put("run", run.save());
        savedRevision = reservations.revision();
        saved.put("reservations", reservations.save(level.registryAccess()));
        facilities.workshopRun(facility, saved);
    }

    @Override
    public void paused() {
        if (run != null && workshop.active()) {
            paused = true;
            save();
            stop();
        }
    }

    @Override
    public void ended(ServerLevel level, Ending ending) {
        workshop.unadmit(this);
        if (run == null || paused) {
            return;
        }
        if (workshop.active()) {
            resolve();
        } else {
            stop();
        }
    }

    void stop() {
        workshop.stopped(this);
        if (run.admission != null) {
            run.admission.cancel(false);
            run.admission = null;
        }
        if (run.pending != null) {
            run.pending.cancel(false);
            run.pending = null;
        }
        if (run.batch != null) {
            UUID batch = run.batch;
            run.batch = null;
            facilities.withdraw(batch);
        }
    }

    private void resolve() {
        stop();
        facilities.workshopRun(facility, null);
    }
}
