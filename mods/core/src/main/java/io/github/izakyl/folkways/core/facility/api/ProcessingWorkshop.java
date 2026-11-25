package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.plan.api.Produce;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import io.github.izakyl.folkways.core.plan.api.WorkshopRecipes;
import io.github.izakyl.folkways.core.resident.api.RefusalKind;
import io.github.izakyl.folkways.core.script.api.ColonyScripts;
import io.github.izakyl.folkways.core.script.api.ScriptFault;
import io.github.izakyl.folkways.core.script.api.ScriptRuntime;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeManager;

/** Server-thread observation and planner-side offers for the workshop's implicit Demand trigger. */
final class ProcessingWorkshop {

    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    private final FacilityDefinition facility;
    private final FacilityWorkshop definition;
    private final Colony colony;
    private final DefinedFacilities facilities;
    private final FacilityScripts.Compiled program;
    private final ResourceLocation id;
    private final AtomicBoolean booked = new AtomicBoolean();
    private volatile boolean active = true;
    private RecipeManager manager;
    private WorkshopRecipeSnapshot recipes;
    private long nextSample;
    private List<FacilityWorkshop.Batch> batches = List.of();
    private Map<String, Value> observed;
    private Map<String, String> libraries;
    private CompletableFuture<List<FacilityWorkshop.Batch>> pending;
    private final boolean scripted;
    private int dispatched;
    private Offered offered;
    private WorkshopScriptWork run;
    private volatile CompoundTag checkpoint;

    private ProcessingWorkshop(Colony colony, DefinedFacilities facilities, FacilityDefinition facility,
                               FacilityWorkshop definition,
                               FacilityScripts.Compiled program) {
        this.colony = colony;
        this.facilities = facilities;
        this.facility = facility;
        this.definition = definition;
        this.program = program;
        scripted = program.program().definesFunction("recipes");
        id = DefinedFacilities.ID.withSuffix("/" + facility.id());
        if (!scripted) {
            batches = FacilityWorkshop.batches(definition.explicit(), id);
        }
    }

    static Optional<ProcessingWorkshop> of(Colony colony, DefinedFacilities facilities, FacilityDefinition facility) {
        return facility.workshop().flatMap(definition -> facilities.workshopProgram(facility)
            .map(program -> new ProcessingWorkshop(colony, facilities, facility, definition, program)));
    }

    Optional<Contribution.Workshop> project(ServerLevel level) {
        if (!facility.extent().loaded(level) || facility.rig(level).isEmpty()) {
            return Optional.empty();
        }
        if (run != null) {
            run.tick();
        }
        if (pending != null && dispatched != level.getServer().getTickCount() && pending.isDone()) {
            try {
                batches = ScriptRuntime.result(pending);
            } catch (ScriptFault fault) {
                LOGGER.info("Workshop recipe script {} paused: {} ({})", facility.name(),
                    FacilityScripts.code(fault), FacilityScripts.detail(fault));
                batches = List.of();
                facilities.pauseWorkshop(facility, "workshop.recipes", definition.trigger().routine(), fault);
            }
            pending = null;
        }
        long now = level.getGameTime();
        if (pending == null && scripted && now >= nextSample
                && !facilities.workshopPaused(facility, "workshop.recipes")) {
            nextSample = now + 10;
            if (manager != level.getRecipeManager()) {
                manager = level.getRecipeManager();
                recipes = WorkshopRecipeSnapshot.of(level);
                observed = null;
            }
            var catalog = recipes;
            var input = DefinedFacilities.scriptInput(level, facility);
            var sources = ColonyScripts.snapshot(colony);
            if (!input.equals(observed) || !sources.equals(libraries)) {
                pending = FacilityScripts.submit(false, () -> program.recipes(input, sources, catalog, id));
                if (pending != null) {
                    observed = input;
                    libraries = sources;
                    dispatched = level.getServer().getTickCount();
                }
            }
        }
        var saved = facilities.workshopRun(facility);
        checkpoint = saved;
        var available = facilities.workshopPaused(facility, "workshop.admission")
            ? List.<FacilityWorkshop.Batch>of() : batches;
        if (offered == null || offered.batches != available || !booked.get() && offered.checkpoint != saved) {
            offered = new Offered(available, saved, level.registryAccess());
        }
        return Optional.of(new Contribution.Workshop(offered));
    }

    private final Map<WorkshopScriptWork, Runnable> placed = new java.util.IdentityHashMap<>();

    boolean admit(WorkshopScriptWork work, Runnable fail) {
        if (!active) {
            return false;
        }
        placed.put(work, fail);
        return true;
    }

    void unadmit(WorkshopScriptWork work) {
        placed.remove(work);
    }

    boolean active() {
        return active;
    }

    void started(WorkshopScriptWork work) {
        run = work;
    }

    void stopped(WorkshopScriptWork work) {
        if (run == work) {
            run = null;
        }
    }

    void close() {
        active = false;
        for (Runnable fail : List.copyOf(placed.values())) {
            fail.run();
        }
        if (run != null) {
            run.stop();
        }
        if (pending != null) {
            pending.cancel(false);
        }
    }

    private final class Offered implements Workshop {
        private final List<FacilityWorkshop.Batch> batches;
        private final CompoundTag checkpoint;

        private final HolderLookup.Provider registries;

        Offered(List<FacilityWorkshop.Batch> batches, CompoundTag checkpoint,
                HolderLookup.Provider registries) {
            this.registries = registries;
            this.batches = batches;
            this.checkpoint = checkpoint;
        }

        @Override
        public UUID key() {
            return facility.id();
        }

        @Override
        public ResourceLocation id() {
            return id;
        }

        @Override
        public WorkSite site() {
            return new WorkSite.AtBlock(facility.site());
        }

        @Override
        public Optional<FacilityDetails> facilityDetails() {
            return Optional.of(new FacilityDetails(facility.id(), Component.literal(facility.name()), Optional.empty(),
                recipes(Set.of())));
        }

        @Override
        public List<WorkshopRecipes.Recipe> recipes(Set<ItemSpec> products) {
            return batches.stream().map(batch -> new WorkshopRecipes.Recipe(batch.inputs(), batch.outputs())).toList();
        }

        @Override
        public Optional<Change> rewrite(Intent intent, Grown graph) {
            return options(intent, graph).stream().findFirst();
        }

        @Override
        public List<Change> options(Intent intent, Grown graph) {
            if (!(intent instanceof Produce demand) || !active) {
                return List.of();
            }
            return batches.stream()
                .filter(batch -> checkpoint == null || checkpoint.getString("recipe").equals(batch.id().toString()))
                .filter(batch -> batch.outputs().stream()
                    .anyMatch(amount -> Goods.includes(demand.wanted(), amount.spec())))
                .map(batch -> {
                    // A resumed run keeps the size it was booked at; a new one takes what the demand wants, up to scale.
                    long runs = checkpoint != null ? Math.max(1, checkpoint.getInt("runs"))
                        : demand.runs(site(), batch.inputs(), perRun(batch, demand), Optional.empty(), batch.scale());
                    return Change.of(Grown.of(new WorkshopScriptWork(demand, facility, definition, batch.times(runs),
                        (int) runs, program, colony, facilities, checkpoint, ProcessingWorkshop.this, registries)))
                        .holding(new Booking(checkpoint));
                }).toList();
        }
    }

    private static long perRun(FacilityWorkshop.Batch batch, Produce demand) {
        return batch.outputs().stream().filter(amount -> Goods.includes(demand.wanted(), amount.spec()))
            .mapToLong(io.github.izakyl.folkways.core.work.api.Amount::count).sum();
    }

    private final class Booking implements Hold {
        private final CompoundTag expected;
        private boolean taken;

        Booking(CompoundTag expected) {
            this.expected = expected;
        }

        @Override
        public boolean mayTake() {
            return active && checkpoint == expected && !booked.get();
        }

        @Override
        public Optional<RefusalKind> take() {
            if (!active || checkpoint != expected || !booked.compareAndSet(false, true)) {
                return Optional.of(FacilityRefusal.BUSY);
            }
            taken = true;
            return Optional.empty();
        }

        @Override
        public void release(Ending how) {
            if (taken) {
                taken = false;
                booked.set(false);
            }
        }
    }
}
