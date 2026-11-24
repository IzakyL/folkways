package io.github.izakyl.folkways.core.facility.internal;

import io.github.izakyl.folkways.core.facility.api.Rig;
import io.github.izakyl.folkways.core.facility.api.Routine;
import io.github.izakyl.folkways.core.facility.api.Routines;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.plan.api.Before;
import io.github.izakyl.folkways.core.plan.api.Grown;
import io.github.izakyl.folkways.core.plan.api.Refinement;
import io.github.izakyl.folkways.core.work.api.Node;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

/**
 * `write_all`: every argument names a signal of the rig and the value to set it to. Each signal that does not already
 * show its value is written once, the writes independent of each other so the plan may share them out, and the run is
 * done once every signal shows its value. Run again after a failure, it plans only the writes still needed.
 */
public final class WriteAll implements Routine {

    // How long the end of a run waits for what the signals show to catch up with what was written.
    static final int PATIENCE = 200;

    @Override
    public java.util.Set<String> writes(Map<String, Value> arguments) {
        return arguments.keySet();
    }

    @Override
    public String description() {
        return "Set each named signal to its requested value.";
    }

    @Override
    public java.util.List<String> writeTargets() {
        return java.util.List.of("*");
    }

    @Override
    public ResourceLocation id() {
        return Routines.WRITE_ALL;
    }

    @Override
    public Optional<Refinement.Change> expand(Call call) {
        if (call.rig().isEmpty() || check(call).isPresent()) {
            return Optional.empty();
        }
        Rig rig = call.rig().get();
        List<Node> nodes = new ArrayList<>();
        List<Routines.Expect> expected = new ArrayList<>();
        for (Map.Entry<String, Value> wanted : call.arguments().entrySet()) {
            Rig.Part part = rig.part(wanted.getKey()).orElseThrow();
            SignalType type = ((Rig.Signalled) part.kind()).type();
            if (!(type instanceof SignalType.Pulse)) {
                expected.add(new Routines.Expect(part.ref(), type, wanted.getValue()));
            }
            if (!part.shows(wanted.getValue())) {
                nodes.add(Routines.write(call.owner(), part, wanted.getValue()));
            }
        }
        Node done = Routines.await(call.id(), call.owner(), call.site(), expected, PATIENCE);
        List<Before> links = nodes.stream().map(write -> new Before(write.id(), done.id())).toList();
        nodes.add(done);
        return Optional.of(Refinement.Change.of(new Grown(nodes, links)));
    }

    @Override
    public Optional<String> check(Call call) {
        if (call.rig().isEmpty()) {
            return Optional.of("write_all needs a facility with controls");
        }
        if (call.arguments().isEmpty()) {
            return Optional.of("write_all needs at least one signal to write");
        }
        for (Map.Entry<String, Value> wanted : call.arguments().entrySet()) {
            Optional<Rig.Part> part = call.rig().get().part(wanted.getKey());
            if (part.isEmpty()) {
                return Optional.of("no usable control " + wanted.getKey());
            }
            if (!(part.get().kind() instanceof Rig.Signalled(SignalType type))) {
                return Optional.of(wanted.getKey() + " is not a signal");
            }
            Optional<String> wrong = type.check(wanted.getValue());
            if (wrong.isPresent()) {
                return Optional.of(wanted.getKey() + ": " + wrong.get());
            }
        }
        return Optional.empty();
    }
}
