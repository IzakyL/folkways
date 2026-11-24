package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.work.api.Node;
import io.github.izakyl.folkways.core.work.api.Outcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

/**
 * Sends `target` to the named drive signal, defaulting to the `destination` control. A continuous drive reports arrival
 * through its feedback. A remote signal can be written immediately.
 */
public final class MoveTo implements Routine {

    private static final int PATIENCE = 200;

    @Override
    public com.google.gson.JsonObject argumentSchema() {
        return Routine.argumentsSchema(Map.of("drive", "string", "target", "object"), "target");
    }

    @Override
    public String description() {
        return "Send a vehicle or carrier to a destination.";
    }

    @Override
    public java.util.List<String> writeTargets() {
        return java.util.List.of("drive");
    }

    @Override
    public ResourceLocation id() {
        return Routines.MOVE_TO;
    }

    @Override
    public Optional<Refinement.Change> expand(Call call) {
        if (check(call).isPresent()) {
            return Optional.empty();
        }
        Rig.Part drive = drive(call).orElseThrow();
        Value target = call.arguments().get("target");
        if (!(drive.cost() instanceof Control.Cost.UntilRead)) {
            return Optional.of(Refinement.Change.of(Grown.of(Routines.write(call.id(), call.owner(), drive, target))));
        }
        List<Node> nodes = new ArrayList<>();
        if (!drive.shows(target)) {
            nodes.add(Routines.write(call.owner(), drive, target));
        }
        SignalType type = ((Rig.Signalled) drive.kind()).type();
        Node done = Routines.await(call.id(), call.owner(), call.site(),
            List.of(new Routines.Expect(drive.ref(), type, target)), PATIENCE);
        List<Before> links = nodes.stream().map(write -> new Before(write.id(), done.id())).toList();
        nodes.add(done);
        return Optional.of(Refinement.Change.of(new Grown(nodes, links)));
    }

    @Override
    public Optional<String> check(Call call) {
        if (call.rig().isEmpty()) {
            return Optional.of("move_to needs a facility with controls");
        }
        Value name = call.arguments().get("drive");
        if (name != null && (!(name instanceof Value.Text text) || text.value().isBlank())) {
            return Optional.of("drive must name a control");
        }
        String part = driveName(call.arguments());
        Optional<Rig.Part> drive = drive(call);
        if (drive.isEmpty() || !(drive.get().kind() instanceof Rig.Signalled(SignalType type))) {
            return Optional.of("no usable drive signal " + part);
        }
        Value target = call.arguments().get("target");
        if (target == null) {
            return Optional.of("move_to needs a target");
        }
        if (drive.get().cost() instanceof Control.Cost.Remote) {
            return Optional.empty(); // The native signal validated the target in snapshot.
        }
        return type.check(target).map(wrong -> "target: " + wrong);
    }

    @Override
    public Set<String> writes(Map<String, Value> arguments) {
        return Set.of(driveName(arguments));
    }

    @Override
    public Call snapshot(ServerLevel level, Call call) {
        Value target = call.arguments().get("target");
        if (target != null) {
            remote(level, call).flatMap(signal -> signal.check(target)).ifPresent(reason -> {
                throw new IllegalArgumentException("target: " + reason);
            });
        }
        return call;
    }

    @Override
    public Optional<Outcome> direct(ServerLevel level, FacilityDefinition facility, Call call) {
        if (drive(call).filter(part -> part.cost() instanceof Control.Cost.Remote).isEmpty()) {
            return Optional.empty();
        }
        boolean written = remote(level, call).filter(signal -> signal.valid(level)
            && signal.write(level, null, call.arguments().get("target")) == Signal.Write.WRITTEN).isPresent();
        return Optional.of(written ? Outcome.done() : Outcome.failed(FacilityRefusal.INVALID));
    }

    private static Optional<Signal> remote(ServerLevel level, Call call) {
        return drive(call).filter(part -> part.cost() instanceof Control.Cost.Remote)
            .flatMap(part -> Controls.resolve(level, part.ref()))
            .filter(Signal.class::isInstance).map(Signal.class::cast);
    }

    private static Optional<Rig.Part> drive(Call call) {
        return call.rig().flatMap(rig -> rig.part(driveName(call.arguments())));
    }

    private static String driveName(Map<String, Value> arguments) {
        return arguments.get("drive") instanceof Value.Text name ? name.value() : "destination";
    }
}
