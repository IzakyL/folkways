package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.work.api.Ending;
import io.github.izakyl.folkways.core.work.api.Hold;
import io.github.izakyl.folkways.core.work.api.Need;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;

// A checkpoint belongs to the facility. Futures and plan requests belong to this server session.
final class ScriptRun {

    final UUID id;
    final String routine;
    final Map<String, Value> arguments;
    String state;
    String failure;
    FacilityScripts.Failure handler;
    int handled;
    Map<UUID, FacilityScripts.Failure> handlers = Map.of();
    UUID batch;
    Set<String> writing = Set.of();
    CompletableFuture<FacilityScripts.Step> pending;
    int dispatched;
    CompletableFuture<FacilityScriptRuns.Admission> admission;
    boolean admitted;
    long nextAdmission;
    int step;
    boolean resumed;
    long nextStep;

    ScriptRun(UUID id, String routine, Map<String, Value> arguments) {
        this.id = id;
        this.routine = routine;
        this.arguments = Map.copyOf(arguments);
    }

    Map<String, Object> view() {
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> args = new LinkedHashMap<>();
        arguments.forEach((key, value) -> args.put(key, DefinitionCodec.value(value)));
        data.put("routine", routine);
        data.put("args", args);
        data.put("state", state == null ? com.google.gson.JsonNull.INSTANCE
            : com.google.gson.JsonParser.parseString(state));
        data.put("step", step);
        data.put("failure", failure == null ? com.google.gson.JsonNull.INSTANCE : failure);
        return data;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putString("routine", routine);
        tag.putInt("step", step);
        tag.put("arguments", Value.saveAll(arguments));
        if (state != null) {
            tag.putByteArray("state", state.getBytes(StandardCharsets.UTF_8));
        }
        if (failure != null) {
            tag.putString("failure", failure);
        }
        tag.putInt("handled", handled);
        if (handler != null) {
            tag.putString("handler", handler.function());
            tag.putInt("action_index", handler.index());
            tag.putString("kind", handler.kind());
            tag.putString("reason", handler.reason());
            tag.putBoolean("retryable", handler.retryable());
        }
        tag.putBoolean("acting", batch != null);
        return tag;
    }

    static ScriptRun load(CompoundTag tag) {
        ScriptRun run = new ScriptRun(tag.getUUID("id"), tag.getString("routine"),
            Value.loadAll(tag.getCompound("arguments")));
        run.step = tag.getInt("step");
        run.resumed = run.step > 0;
        run.handled = tag.getInt("handled");
        if (tag.contains("handler")) {
            run.handler = new FacilityScripts.Failure(tag.getString("handler"), tag.getInt("action_index"),
                tag.getString("kind"), tag.getString("reason"), tag.getBoolean("retryable"));
        }
        run.state = tag.contains("state") ? new String(tag.getByteArray("state"), StandardCharsets.UTF_8) : null;
        run.failure = tag.getBoolean("acting") ? "folkways.facility.script.script_interrupted"
            : tag.contains("failure") ? tag.getString("failure") : null;
        return run;
    }

    static Set<String> writes(List<FacilityScripts.Action> actions) {
        Set<String> controls = new java.util.LinkedHashSet<>();
        for (var action : actions) {
            switch (action.base()) {
                case FacilityScripts.Write write -> controls.add(write.control());
                case FacilityScripts.Library library -> Routines.of(library.routine())
                    .ifPresent(routine -> controls.addAll(routine.writes(library.arguments())));
                default -> { }
            }
        }
        return Set.copyOf(controls);
    }

    record ActionNode(Node node, FacilityScripts.Action action, int index) {
        FacilityScripts.Failure failure(String reason) {
            return action instanceof FacilityScripts.WithHandler handled
                ? new FacilityScripts.Failure(handled.function(), index, FacilityScripts.kind(action), reason, true)
                : null;
        }
    }

    private static List<ActionNode> actions(ServerLevel level, FacilityDefinition facility,
            List<FacilityScripts.Action> actions) {
        List<ActionNode> nodes = new ArrayList<>();
        Rig rig = facility.rig(level).orElseThrow();
        for (int index = 0; index < actions.size(); index++) {
            FacilityScripts.Action action = actions.get(index);
            List<Node> next = switch (action.base()) {
                case FacilityScripts.Write write -> {
                    Rig.Part part = rig.part(write.control()).orElseThrow(() ->
                        new IllegalArgumentException("no usable control " + write.control()));
                    // A pulse is held until the control settles it, so a native action it starts is seen through.
                    boolean press = write.value() instanceof Value.Pulse
                        && part.kind() instanceof Rig.Signalled(SignalType.Pulse ignored)
                        && part.cost() instanceof Control.Cost.Instant;
                    yield List.of(press ? Routines.press(DefinedFacilities.ID, part)
                        : Routines.write(DefinedFacilities.ID, part, write.value()));
                }
                case FacilityScripts.Await await -> {
                    ScriptAwait.validate(level, facility, await);
                    yield List.of(new ScriptAwait(facility, await));
                }
                case FacilityScripts.Library library -> List.of(Routines.libraryRun(level, facility,
                    library.routine().toString(), library.routine(), library.arguments(), DefinedFacilities.ID));
                case FacilityScripts.Items items -> {
                    Rig.Part part = rig.part(items.port()).orElseThrow(() ->
                        new IllegalArgumentException("no usable port " + items.port()));
                    if (!(part.kind() instanceof Rig.Ported port)
                            || !port.resource().equals(ResourceKind.ITEM)) {
                        throw new IllegalArgumentException("not an item port " + items.port());
                    }
                    yield items.collect() ? List.of(new ItemCollection(part, items.items(), items.all()))
                        : items.items().stream().<Node>map(amount -> new ItemDeposit(DefinedFacilities.ID, part,
                            new Need(amount.spec(), amount.count(), true))).toList();
                }
                default -> throw new IllegalArgumentException("invalid action in batch");
            };
            for (Node node : next) {
                nodes.add(new ActionNode(node, action, index));
            }
        }
        return nodes;
    }

    ScriptBatch plan(ServerLevel level, FacilityDefinition facility, List<FacilityScripts.Action> actions) {
        return plan(level, facility, actions, null);
    }

    ScriptBatch plan(ServerLevel level, FacilityDefinition facility, List<FacilityScripts.Action> actions,
                     WorkshopScriptWork workshop) {
        List<Node> nodes = new ArrayList<>();
        List<Before> links = new ArrayList<>();
        List<Set<UUID>> groups = new ArrayList<>();
        List<Hold> holds = new ArrayList<>();
        Map<UUID, FacilityScripts.Failure> nextHandlers = new LinkedHashMap<>();
        Set<UUID> previous = Set.of();
        Set<UUID> current = new LinkedHashSet<>();
        Set<WorldPos> ports = new LinkedHashSet<>();
        for (ActionNode planned : actions(level, facility, actions)) {
            Node next = planned.node();
            var port = next instanceof ItemDeposit deposit && workshop != null ? deposit.part().ref().at() : null;
            // Distinct input ports can be loaded together. Other actions preserve their script order.
            if (!current.isEmpty() && (port == null || ports.isEmpty() || ports.contains(port))) {
                previous = Set.copyOf(current);
                current.clear();
                ports.clear();
            }
            if (port != null) {
                ports.add(port);
            }
            var handler = planned.failure("");
            if (next instanceof Run library) {
                var change = Routines.of(library.routine()).flatMap(rule -> rule.expand(library.call()))
                    .orElseThrow(() -> new IllegalArgumentException("cannot plan routine " + library.routine()));
                var graph = change.replacement();
                nodes.addAll(graph.nodes());
                links.addAll(graph.links());
                links.addAll(change.links());
                groups.addAll(graph.workerGroups());
                groups.addAll(change.workerGroups());
                holds.addAll(change.holds());
                Set<UUID> entries = new LinkedHashSet<>(graph.completions());
                graph.links().forEach(link -> entries.remove(link.to()));
                change.links().forEach(link -> entries.remove(link.to()));
                for (UUID entry : entries) {
                    previous.forEach(before -> links.add(new Before(before, entry)));
                }
                if (handler != null) {
                    graph.nodes().forEach(node -> nextHandlers.put(node.id(), handler));
                }
            } else {
                nodes.add(next);
                previous.forEach(before -> links.add(new Before(before, next.id())));
                if (handler != null) {
                    nextHandlers.put(next.id(), handler);
                }
            }
            current.add(next.id());
        }
        Node sink = Routines.await(UUID.randomUUID(), DefinedFacilities.ID, facility.site(), List.of(), 0);
        current.forEach(before -> links.add(new Before(before, sink.id())));
        nodes.add(sink);
        handlers = Map.copyOf(nextHandlers);
        return new ScriptBatch(sink.spec(), new Grown(nodes, links, groups), holds,
            workshop == null ? null : workshop.reservations());
    }

    // A handled action reports to its handler instead of exposing the failure to step().
    boolean batchEnded(Ending ending) {
        batch = null;
        writing = Set.of();
        failure = switch (ending) {
            case Ending.Done ignored -> null;
            case Ending.Failed failed -> failed.why().translationKey();
            case Ending.Dropped ignored -> "folkways.facility.script.script_interrupted";
            case Ending.Revoked ignored -> "folkways.facility.script.script_interrupted";
        };
        var next = ending instanceof Ending.Failed failed ? handlers.get(failed.node()) : null;
        handlers = Map.of();
        if (next == null) {
            return true;
        }
        if (handled >= 16) {
            return false;
        }
        handler = new FacilityScripts.Failure(next.function(), next.index(), next.kind(), failure, next.retryable());
        handled++;
        return true;
    }
}
