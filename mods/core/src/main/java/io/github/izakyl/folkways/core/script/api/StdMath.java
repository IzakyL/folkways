package io.github.izakyl.folkways.core.script.api;

import java.math.BigDecimal;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

@StarlarkBuiltin(name = "math", doc = "Numeric and coordinate helpers.")
public final class StdMath implements StarlarkValue {

    static final StdMath INSTANCE = new StdMath();

    private StdMath() {
    }

    @StarlarkMethod(name = "pi", doc = "The ratio of circumference to diameter.", structField = true)
    public StarlarkFloat pi() {
        return StarlarkFloat.of(Math.PI);
    }

    @StarlarkMethod(name = "sqrt", doc = "Square root.", parameters = @Param(name = "x", named = true),
        useStarlarkThread = true)
    public StarlarkFloat sqrt(Object x, StarlarkThread thread) throws EvalException {
        return StarlarkFloat.of(Math.sqrt(number(x, thread)));
    }

    @StarlarkMethod(name = "sin", doc = "Sine in radians.", parameters = @Param(name = "x", named = true),
        useStarlarkThread = true)
    public StarlarkFloat sin(Object x, StarlarkThread thread) throws EvalException {
        return StarlarkFloat.of(Math.sin(number(x, thread)));
    }

    @StarlarkMethod(name = "cos", doc = "Cosine in radians.", parameters = @Param(name = "x", named = true),
        useStarlarkThread = true)
    public StarlarkFloat cos(Object x, StarlarkThread thread) throws EvalException {
        return StarlarkFloat.of(Math.cos(number(x, thread)));
    }

    @StarlarkMethod(name = "tan", doc = "Tangent in radians.", parameters = @Param(name = "x", named = true),
        useStarlarkThread = true)
    public StarlarkFloat tan(Object x, StarlarkThread thread) throws EvalException {
        return StarlarkFloat.of(Math.tan(number(x, thread)));
    }

    @StarlarkMethod(name = "floor", doc = "Round down to an integer.", parameters = @Param(name = "x", named = true),
        useStarlarkThread = true)
    public StarlarkInt floor(Object x, StarlarkThread thread) throws EvalException {
        return integer(Math.floor(number(x, thread)));
    }

    @StarlarkMethod(name = "ceil", doc = "Round up to an integer.", parameters = @Param(name = "x", named = true),
        useStarlarkThread = true)
    public StarlarkInt ceil(Object x, StarlarkThread thread) throws EvalException {
        return integer(Math.ceil(number(x, thread)));
    }

    @StarlarkMethod(name = "round", doc = "Round to the nearest integer, with ties to even.", parameters = @Param(name = "x", named = true),
        useStarlarkThread = true)
    public StarlarkInt round(Object x, StarlarkThread thread) throws EvalException {
        return integer(Math.rint(number(x, thread)));
    }

    @StarlarkMethod(name = "atan2", doc = "Angle of (x, y) in radians.",
        parameters = {@Param(name = "y", named = true), @Param(name = "x", named = true)}, useStarlarkThread = true)
    public StarlarkFloat atan2(Object y, Object x, StarlarkThread thread) throws EvalException {
        return StarlarkFloat.of(Math.atan2(number(y, thread), number(x, thread)));
    }

    @StarlarkMethod(name = "clamp", doc = "Constrain a number to an inclusive interval.",
        parameters = {@Param(name = "x", named = true), @Param(name = "lo", named = true),
            @Param(name = "hi", named = true)}, useStarlarkThread = true)
    public Object clamp(Object x, Object lo, Object hi, StarlarkThread thread) throws EvalException {
        chargeNumber(x, thread);
        chargeNumber(lo, thread);
        chargeNumber(hi, thread);
        if (Starlark.ORDERING.compare(lo, hi) > 0) {
            throw Starlark.errorf("lo must be at most hi");
        }
        if (Starlark.ORDERING.compare(x, lo) < 0) {
            return lo;
        }
        return Starlark.ORDERING.compare(x, hi) > 0 ? hi : x;
    }

    @StarlarkMethod(name = "dist", doc = "Euclidean distance between two XYZ positions.",
        parameters = {@Param(name = "a", named = true), @Param(name = "b", named = true)}, useStarlarkThread = true)
    public StarlarkFloat dist(Object a, Object b, StarlarkThread thread) throws EvalException {
        double dx = coordinate(a, 0, "x", thread) - coordinate(b, 0, "x", thread);
        double dy = coordinate(a, 1, "y", thread) - coordinate(b, 1, "y", thread);
        double dz = coordinate(a, 2, "z", thread) - coordinate(b, 2, "z", thread);
        return StarlarkFloat.of(Math.hypot(Math.hypot(dx, dy), dz));
    }

    private static double coordinate(Object point, int index, String axis, StarlarkThread thread)
            throws EvalException {
        if (point instanceof Sequence<?> values && values.size() == 3) {
            return number(values.get(index), thread);
        }
        if (point instanceof Dict<?, ?> fields && fields.containsKey(axis)) {
            return number(fields.get(axis), thread);
        }
        throw Starlark.errorf("position must be an XYZ dict or a sequence of three numbers");
    }

    private static void chargeNumber(Object value, StarlarkThread thread) throws EvalException {
        if (value instanceof StarlarkInt integer) {
            ScriptSteps.charge(thread, 1 + integer.toBigInteger().bitLength() / 32);
        } else if (value instanceof StarlarkFloat real && Double.isFinite(real.toDouble())) {
            ScriptSteps.charge(thread, 1);
        } else {
            throw Starlark.errorf("expected a finite number");
        }
    }

    private static double number(Object value, StarlarkThread thread) throws EvalException {
        chargeNumber(value, thread);
        return value instanceof StarlarkInt integer ? integer.toFiniteDouble() : ((StarlarkFloat) value).toDouble();
    }

    private static StarlarkInt integer(double value) {
        return StarlarkInt.of(new BigDecimal(value).toBigIntegerExact());
    }
}
