package io.github.izakyl.folkways.core.facility.api.control;

import java.util.List;
import java.util.Optional;

/**
 * What a signal accepts. A closed set: routines, the command card and validation are written against these five and
 * nothing else.
 */
public sealed interface SignalType {

    Pulse PULSE = new Pulse();
    Bool BOOL = new Bool();
    Data DATA = new Data();
    Real BLOCK_AXIS = new Real(-30_000_000, 30_000_000, 1);
    Vector BLOCK_POSITION = new Vector(List.of(BLOCK_AXIS, BLOCK_AXIS, BLOCK_AXIS));

    /** A button or trigger: pressed, never set. */
    record Pulse() implements SignalType {
    }

    /** A lever or clutch. */
    record Bool() implements SignalType {
    }

    /**
     * An analogue lever, speed controller or hand wheel: a number in [min, max] on a grid of `step` from `min`. A step
     * of zero takes any number in range.
     */
    record Real(double min, double max, double step) implements SignalType {

        public Real {
            if (!Double.isFinite(min) || !Double.isFinite(max) || min > max) {
                throw new IllegalArgumentException("a range runs from a finite min to a max at least as large");
            }
            if (!Double.isFinite(step) || step < 0) {
                throw new IllegalArgumentException("a step is finite and not negative, not " + step);
            }
        }

        // Grid checks forgive what doubles cannot hold exactly.
        private double slack() {
            return Math.max(1e-9, (max - min) * 1e-9);
        }

        Optional<String> check(double value) {
            if (value < min - slack() || value > max + slack()) {
                return Optional.of(value + " is outside [" + min + ", " + max + "]");
            }
            if (step > 0) {
                double steps = (value - min) / step;
                if (Math.abs(steps - Math.rint(steps)) * step > slack()) {
                    return Optional.of(value + " is not on the grid of " + step + " from " + min);
                }
            }
            return Optional.empty();
        }

        boolean agrees(double target, double observed) {
            return Math.abs(target - observed) <= Math.max(step / 2, slack());
        }
    }

    /** A fixed number of axes written together, each with its own range. */
    record Vector(List<Real> axes) implements SignalType {

        public Vector {
            axes = List.copyOf(axes);
            if (axes.isEmpty()) {
                throw new IllegalArgumentException("a vector has at least one axis");
            }
        }
    }

    /** A structured value the adapter checks itself. */
    record Data() implements SignalType {
    }

    /** Why the value cannot be written to a signal of this type; empty when it can. */
    default Optional<String> check(Value value) {
        return switch (this) {
            case Pulse ignored -> value instanceof Value.Pulse ? Optional.empty() : Optional.of("a pulse is pressed");
            case Bool ignored -> value instanceof Value.Bool ? Optional.empty() : Optional.of("expected on or off");
            case Real real -> value instanceof Value.Real(double number) ? real.check(number)
                : Optional.of("expected a number");
            case Vector(List<Real> axes) -> {
                if (!(value instanceof Value.Vector(List<Double> values))) {
                    yield Optional.of("expected " + axes.size() + " numbers");
                }
                if (values.size() != axes.size()) {
                    yield Optional.of("expected " + axes.size() + " numbers, not " + values.size());
                }
                for (int i = 0; i < axes.size(); i++) {
                    Optional<String> wrong = axes.get(i).check(values.get(i));
                    if (wrong.isPresent()) {
                        yield Optional.of("axis " + i + ": " + wrong.get());
                    }
                }
                yield Optional.empty();
            }
            case Data ignored -> value instanceof Value.Data ? Optional.empty() : Optional.of("expected data");
        };
    }

    /**
     * Whether the observed reading shows the target as written. A pulse leaves nothing to read, so it always agrees;
     * a number agrees within half a step.
     */
    default boolean agrees(Value target, Value observed) {
        return switch (this) {
            case Pulse ignored -> true;
            case Real real -> target instanceof Value.Real(double want) && observed instanceof Value.Real(double got)
                && real.agrees(want, got);
            case Vector(List<Real> axes) -> {
                if (!(target instanceof Value.Vector(List<Double> want))
                    || !(observed instanceof Value.Vector(List<Double> got))
                    || want.size() != axes.size() || got.size() != axes.size()) {
                    yield false;
                }
                for (int i = 0; i < axes.size(); i++) {
                    if (!axes.get(i).agrees(want.get(i), got.get(i))) {
                        yield false;
                    }
                }
                yield true;
            }
            case Bool ignored -> target.equals(observed);
            case Data ignored -> target.equals(observed);
        };
    }
}
