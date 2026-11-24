package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.facility.api.control.Motion;
import io.github.izakyl.folkways.core.facility.api.control.Control;
import io.github.izakyl.folkways.core.facility.api.control.ControlRef;
import io.github.izakyl.folkways.core.facility.api.control.Signal;
import io.github.izakyl.folkways.core.facility.api.control.Port;
import io.github.izakyl.folkways.core.facility.api.control.Value;
import io.github.izakyl.folkways.core.facility.api.control.SignalType;
import io.github.izakyl.folkways.core.facility.api.control.ResourceKind;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * A facility's named controls as they stood at one instant, taken on the server thread so that a routine can plan from
 * it on the planner's thread. A routine only sees a rig when its facility has a body; a part names its control, and
 * the work the routine plans resolves the control afresh when it is done.
 */
public record Rig(Map<String, Part> parts, Map<String, FacilityDefinition.Carrier> carriers) {

    public Rig(Map<String, Part> parts) {
        this(parts, Map.of());
    }

    public Rig {
        parts = Map.copyOf(parts);
        carriers = Map.copyOf(carriers);
    }

    public Optional<Part> part(String name) {
        return Optional.ofNullable(parts.get(name));
    }

    /** Updates feedback and translates each carried control by the change in its carrier chain. */
    public Rig predict(Map<String, Value> writes) {
        Map<String, Part> predicted = new LinkedHashMap<>();
        parts.forEach((name, part) -> {
            var shift = displacement(name, writes);
            var ref = part.ref();
            var stances = part.stances();
            var site = part.site();
            if (!shift.equals(BlockPos.ZERO)) {
                ref = new ControlRef(ref.at().at(ref.at().cell().offset(shift)), ref.part());
                site = new WorkSite.AtBlock(ref.at());
                if (stances instanceof Stances.Cells cells) {
                    stances = Stances.of(cells.cells().stream()
                        .map(at -> at.at(at.cell().offset(shift))).toList()).orElseThrow();
                }
            }
            predicted.put(name, new Part(name, ref, site, stances, part.cost(), part.kind(),
                Optional.ofNullable(writes.get(name)).or(part::feedback)));
        });
        return new Rig(predicted, carriers);
    }

    private BlockPos displacement(String name, Map<String, Value> writes) {
        var carrier = carriers.get(name);
        if (carrier == null) {
            return BlockPos.ZERO;
        }
        var shift = displacement(carrier.part(), writes);
        if (writes.get(carrier.part()) instanceof Value.Real next) {
            double previous = number(part(carrier.part()).orElseThrow().feedback().orElseThrow(
                () -> new IllegalArgumentException("carrier needs settled feedback")));
            return shift.offset(carrier.shift(next.value()).subtract(carrier.shift(previous)));
        }
        return shift;
    }

    /**
     * One control: where its work is told to be, where the writer stands (whether seated), what a write costs, what it
     * takes, and what its feedback read when the rig was taken.
     */
    private static double number(Value value) {
        if (!(value instanceof Value.Real real)) {
            throw new IllegalArgumentException("expected a number");
        }
        return real.value();
    }

    public record Part(String name, ControlRef ref, WorkSite site, Stances stances,
                       Control.Cost cost, Kind kind, Optional<Value> feedback) {

        public Part {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(site, "site");
            Objects.requireNonNull(stances, "stances");
            Objects.requireNonNull(cost, "cost");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(feedback, "feedback");
        }

        /** Whether the writer sits in a seat to write it. */
        public boolean seated() {
            return stances instanceof Stances.Seat;
        }

        /** Whether the feedback already shows the target, so that writing it again would change nothing. */
        public boolean shows(Value target) {
            return kind instanceof Signalled(SignalType type) && !(type instanceof SignalType.Pulse)
                && feedback.filter(read -> type.agrees(target, read)).isPresent();
        }
    }

    public sealed interface Kind {
    }

    public record Signalled(SignalType type) implements Kind {
    }

    public record Sensed(double maximum) implements Kind {
    }

    public record Ported(ResourceKind<?> resource) implements Kind {
    }

    /** Takes the rig of these controls now. Controls that are gone or unusable are left out. */
    public static Rig of(ServerLevel level, Map<String, Control> controls) {
        Map<String, Part> parts = new LinkedHashMap<>();
        controls.forEach((name, control) -> {
            if (control.valid(level)) {
                part(level, name, control).ifPresent(part -> parts.put(name, part));
            }
        });
        return new Rig(parts);
    }

    private static Optional<Part> part(ServerLevel level, String name, Control control) {
        Kind kind = switch (control) {
            case Signal signal -> new Signalled(signal.type());
            case Port<?> port -> new Ported(port.resource());
            case Motion motion -> new Sensed(motion.maximum());
        };
        Optional<Value> feedback = control instanceof Signal signal
            ? signal.commandFeedback().flatMap(reading -> reading.read(level))
            : control instanceof Motion motion ? Optional.of(Value.of(motion.position())) : Optional.empty();
        Optional<Stances> stance = control instanceof Motion ? Optional.of(Stances.WHEREVER) : control.stance(level);
        if (control.cost() instanceof Control.Cost.Remote && stance.isEmpty()) {
            stance = Optional.of(Stances.WHEREVER);
        }
        return stance.map(stances -> new Part(name, control.ref(),
            stances instanceof Stances.Seat(UUID entity, int seat)
                ? new WorkSite.AtSeat(entity, seat, control.anchor()) : new WorkSite.AtBlock(control.anchor()),
            stances, control.cost(), kind, feedback));
    }
}
