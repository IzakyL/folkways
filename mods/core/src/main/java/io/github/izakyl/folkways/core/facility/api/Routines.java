package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.colony.api.Colony;
import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.control.Readings;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * The routine library, and how a run of a facility's routine is asked of the plan.
 *
 * <p>A run is an {@link Run intent} carrying a snapshot taken on the server thread: the facility's readings and rig. Core's
 * routine refinement asks the routine to expand it when the plan grows it.
 */
public final class Routines {

    /** Sets a group of signals to their targets, in parallel, and is done once every reading agrees. */
    public static final ResourceLocation WRITE_STAGES = ResourceLocation.parse("folkways_core:write_stages");
    public static final ResourceLocation WRITE_SEQUENCE = ResourceLocation.parse("folkways_core:write_sequence");
    public static final ResourceLocation WRITE_ALL = ResourceLocation.fromNamespaceAndPath("folkways_core",
        "write_all");

    /** Sends a destination through a drive control. Continuous drives finish on arrival. */
    public static final ResourceLocation MOVE_TO = ResourceLocation.fromNamespaceAndPath("folkways_core", "move_to");

    public static final ResourceLocation DEPOSIT = ResourceLocation.parse("folkways_core:deposit");


    public static final ResourceLocation SCRIPT = ResourceLocation.parse("folkways_core:script");

    private static final Map<ResourceLocation, Routine> LIBRARY = new ConcurrentHashMap<>();

    private Routines() {
    }

    public static void register(Routine routine) {
        if (LIBRARY.putIfAbsent(routine.id(), routine) != null) {
            throw new IllegalStateException(routine.id() + " is already a routine");
        }
    }

    public static List<Routine> library() {
        return LIBRARY.values().stream().sorted(java.util.Comparator.comparing(Routine::id)).toList();
    }

    public static Optional<Routine> of(ResourceLocation id) {
        return Optional.ofNullable(LIBRARY.get(id));
    }

    /**
     * One run of the facility's routine called `name`, as work to ask of the plan, on the server thread. Throws when
     * the facility has no such routine, the library lacks it, or the routine turns the call down.
     */
    public static Run run(ServerLevel level, Facility facility, String name, Map<String, Value> arguments,
            ResourceLocation owner) {
        Routine.Use use = facility.routines().get(name);
        if (use == null) {
            throw new IllegalArgumentException(facility.id() + " has no routine " + name);
        }
        var defaults = use.resolve(facility.parameters());
        return libraryRun(level, facility, name, defaults.routine(), defaults.arguments(arguments), owner);
    }

    static Run libraryRun(ServerLevel level, Facility facility, String name, ResourceLocation library,
            Map<String, Value> arguments, ResourceLocation owner) {
        return libraryRun(level, facility, name, library, arguments, owner, false);
    }

    static Run libraryRun(ServerLevel level, Facility facility, String name, ResourceLocation library,
            Map<String, Value> arguments, ResourceLocation owner, boolean pinned) {
        Routine routine = of(library).orElseThrow(() ->
            new IllegalArgumentException("no routine " + library + " in the library"));
        Routine.Call call = new Routine.Call(UUID.randomUUID(), owner, facility.id(), facility.site(),
            facility.parameters(), arguments, Readings.snapshot(level, facility.readings(level)),
            facility.rig(level));
        call = pinned ? routine.resume(level, call) : routine.snapshot(level, call);
        routine.check(call).ifPresent(wrong -> {
            throw new IllegalArgumentException(name + ": " + wrong);
        });
        return new Run(library, call, pinned);
    }

    /** Asks the colony for one run. Its requester owns any resubmission. */
    public static void submit(Colony colony, ServerLevel level, Facility facility, String name,
            Map<String, Value> arguments, ResourceLocation owner, Consumer<Ending> ended) {
        submitTracked(colony, level, facility, name, arguments, owner, (id, ending) -> ended.accept(ending));
    }

    static UUID submitTracked(Colony colony, ServerLevel level, Facility facility, String name,
            Map<String, Value> arguments, ResourceLocation owner,
            java.util.function.BiConsumer<UUID, Ending> ended) {
        Objects.requireNonNull(ended, "ended");
        Run run = run(level, facility, name, arguments, owner);
        return submitTracked(colony, level, facility, run, ended);
    }

    static UUID submitTracked(Colony colony, ServerLevel level, Facility facility, Run run,
            java.util.function.BiConsumer<UUID, Ending> ended) {
        var service = DefinedFacilities.of(colony);
        if (facility instanceof FacilityDefinition definition && service.isPresent()
                && (run.routine().equals(WRITE_ALL) || run.routine().equals(WRITE_STAGES)
                    || run.routine().equals(WRITE_SEQUENCE) || of(run.routine()).orElseThrow().runsAtPosts())) {
            var graph = of(run.routine()).orElseThrow().expand(run.call()).map(Refinement.Change::replacement);
            if (graph.isPresent() && service.get().postRuns()
                    .start(level, definition, run.id(), run.call().owner(), graph.get(), run.resumed(), ended)) {
                return run.id();
            }
            if (run.routine().equals(WRITE_SEQUENCE)) {
                colony.submit(run.call().owner(), level.dimension(), graph.orElseThrow(), ended);
                return run.id();
            }
        }
        colony.submit(run.call().owner(), level.dimension(), Grown.of(run), ended);
        return run.id();
    }

    /** A node that sets the rig's signal to `target`, with the writer at its stance and holding its lease. */
    public static Node write(ResourceLocation owner, Rig.Part part, Value target) {
        return write(UUID.randomUUID(), owner, part, target);
    }

    public static Node write(UUID id, ResourceLocation owner, Rig.Part part, Value target) {
        if (!(part.kind() instanceof Rig.Signalled(SignalType type))) {
            throw new IllegalArgumentException(part.name() + " is not a signal");
        }
        type.check(target).ifPresent(wrong -> {
            throw new IllegalArgumentException(part.name() + ": " + wrong);
        });
        return new SignalWrite(id, owner, part, type, target);
    }

    public static Node deposit(ResourceLocation owner, Rig.Part part, Need need) {
        return new ItemDeposit(owner, part, need);
    }

    /** A pulse its writer holds until the control settles the action it started, such as a queued native shot. */
    public static Node press(ResourceLocation owner, Rig.Part part) {
        if (!(part.kind() instanceof Rig.Signalled(SignalType.Pulse type))) {
            throw new IllegalArgumentException(part.name() + " requires a pulse signal");
        }
        return new SignalWrite(UUID.randomUUID(), owner, part, type, Value.PULSE, List.of(), true);
    }

    public static Node pulse(UUID id, ResourceLocation owner, Rig.Part part, List<Expect> expected) {
        if (!(part.kind() instanceof Rig.Signalled(SignalType.Pulse type)) || expected.isEmpty()) {
            throw new IllegalArgumentException(part.name() + " requires a pulse signal and expected readings");
        }
        return new SignalWrite(id, owner, part, type, Value.PULSE, expected);
    }

    /** What a signal is expected to show once written. */
    public record Expect(ControlRef control, SignalType type, Value target) {
    }

    /**
     * A node, with the run's id, that is done once every expected signal shows its target; it waits on them, and
     * fails as {@link FacilityRefusal#UNSETTLED} once it has waited `patience` ticks.
     */
    public static Node await(UUID id, ResourceLocation owner, WorldPos site, List<Expect> expected, int patience) {
        return new Agreement(id, owner, site, expected, patience);
    }
}
