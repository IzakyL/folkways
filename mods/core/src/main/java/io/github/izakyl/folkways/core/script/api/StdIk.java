package io.github.izakyl.folkways.core.script.api;

import io.github.izakyl.folkways.solve.Chains;
import io.github.izakyl.folkways.solve.Kinematics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.Dict;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Sequence;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkFloat;
import net.starlark.java.eval.StarlarkInt;
import net.starlark.java.eval.StarlarkList;
import net.starlark.java.eval.StarlarkThread;
import net.starlark.java.eval.StarlarkValue;

@StarlarkBuiltin(name = "ik", doc = "Calibrate and solve serial chains in a shared reference pose.")
public final class StdIk implements StarlarkValue {

    static final StdIk INSTANCE = new StdIk();
    private static final int MAX_JOINTS = 16;
    private static final int MAX_SAMPLES = 128;

    private StdIk() {
    }

    @StarlarkMethod(name = "chain", doc = "Make a chain with an optional reference tool axis (default +X).",
        parameters = {@Param(name = "joints", named = true), @Param(name = "tip", named = true),
            @Param(name = "tip_axis", named = true, defaultValue = "None")}, useStarlarkThread = true)
    public Dict<String, Object> chain(Object joints, Object tip, Object tipAxis, StarlarkThread thread)
            throws EvalException {
        var chain = Dict.copyOf(thread.mutability(), Map.of("joints", joints, "tip", tip, "tip_axis", tipAxis));
        try {
            readChain(chain, thread);
            return chain;
        } catch (IllegalArgumentException invalid) {
            throw Starlark.errorf("%s", invalid.getMessage());
        }
    }

    @StarlarkMethod(name = "forward", doc = "Compute tip XYZ from controls.",
        parameters = {@Param(name = "chain", named = true), @Param(name = "controls", named = true)},
        useStarlarkThread = true)
    public StarlarkList<StarlarkFloat> forward(Object chain, Object controls, StarlarkThread thread)
            throws EvalException {
        try {
            Calibrated calibrated = readChain(chain, thread);
            return vector(Chains.forward(calibrated.chain, calibrated.values(controls, thread)).tip(), thread);
        } catch (IllegalArgumentException invalid) {
            throw Starlark.errorf("%s", invalid.getMessage());
        }
    }

    @StarlarkMethod(name = "solve", doc = "Find controls for a target XYZ, an optional tool direction and tolerance.",
        parameters = {@Param(name = "chain", named = true), @Param(name = "target", named = true),
            @Param(name = "start", named = true, defaultValue = "None"),
            @Param(name = "direction", named = true, defaultValue = "None"),
            @Param(name = "tolerance", named = true, defaultValue = "None")}, useStarlarkThread = true)
    public Dict<String, Object> solve(Object chain, Object target, Object start, Object direction, Object tolerance,
            StarlarkThread thread) throws EvalException {
        try {
            Calibrated calibrated = readChain(chain, thread);
            Chains.Options defaults = Chains.Options.DEFAULT;
            // How close counts as reached, in blocks: a coarse tolerance saves refining what a tool would not notice.
            double close = tolerance == Starlark.NONE ? defaults.tolerance() : number(tolerance, thread);
            Chains.Options options = new Chains.Options(defaults.iterations(), close,
                direction == Starlark.NONE ? null : vector(direction, thread), defaults.directionWeight());
            Chains.Vec point = vector(target, thread);
            List<Double> values = start == Starlark.NONE ? null : calibrated.values(start, thread);
            int count = calibrated.controls.size();
            Chains.Solution solution = Chains.solve(calibrated.chain, point, values, options);
            // Charged by the refinement the target took: a target on a straight chain is met in a step or two.
            ScriptSteps.charge(thread, 1 + (long) solution.iterations() * (1 + count + count * count / 4));
            return Dict.copyOf(thread.mutability(), Map.of("controls", controls(calibrated.aim(solution.values()), thread),
                "reached", solution.reached(), "error", real(solution.residual())));
        } catch (IllegalArgumentException invalid) {
            throw Starlark.errorf("%s", invalid.getMessage());
        }
    }

    @StarlarkMethod(name = "fit_revolute", doc = "Fit a revolute joint from [control, XYZ] samples.",
        parameters = @Param(name = "samples", named = true), useStarlarkThread = true)
    public Dict<String, Object> fitRevolute(Object samples, StarlarkThread thread) throws EvalException {
        return fit(samples, true, thread);
    }

    @StarlarkMethod(name = "fit_prismatic", doc = "Fit a prismatic joint from [control, XYZ] samples.",
        parameters = @Param(name = "samples", named = true), useStarlarkThread = true)
    public Dict<String, Object> fitPrismatic(Object samples, StarlarkThread thread) throws EvalException {
        return fit(samples, false, thread);
    }

    private static Dict<String, Object> fit(Object samples, boolean revolute, StarlarkThread thread)
            throws EvalException {
        var input = sequence(samples, "samples");
        if (input.size() > MAX_SAMPLES) {
            throw Starlark.errorf("expected at most %d calibration samples", MAX_SAMPLES);
        }
        ScriptSteps.charge(thread, 12L * input.size() + 1);
        try {
            var measured = new ArrayList<Chains.Sample>();
            for (Object item : input) {
                var sample = sequence(item, "sample");
                if (sample.size() != 2) {
                    throw Starlark.errorf("expected a [control, XYZ] sample");
                }
                measured.add(new Chains.Sample(number(sample.get(0), thread), vector(sample.get(1), thread)));
            }
            Chains.Fit fit = revolute ? Chains.fitRevolute(measured) : Chains.fitPrismatic(measured);
            ScriptSteps.charge(thread, 1);
            var result = new LinkedHashMap<String, Object>();
            if (fit.joint() instanceof Chains.Revolute rotation) {
                result.put("kind", "revolute");
                result.put("pivot", vector(rotation.pivot(), thread));
                result.put("axis", vector(rotation.axis(), thread));
            } else {
                result.put("kind", "prismatic");
                result.put("direction", vector(fit.joint().axis(), thread));
            }
            result.put("per_unit", real(fit.perUnit()));
            result.put("zero", real(fit.zero()));
            result.put("reference", vector(fit.reference(), thread));
            result.put("residual", real(fit.residual()));
            return Dict.copyOf(thread.mutability(), result);
        } catch (IllegalArgumentException invalid) {
            throw Starlark.errorf("%s", invalid.getMessage());
        }
    }

    static Calibrated readChain(Object chain, StarlarkThread thread) throws EvalException {
        if (!(chain instanceof Dict<?, ?> fields)) {
            throw Starlark.errorf("expected a chain dict");
        }
        var joints = sequence(fields.get("joints"), "joints");
        if (joints.size() > MAX_JOINTS) {
            throw Starlark.errorf("expected at most %d joints", MAX_JOINTS);
        }
        var geometry = new ArrayList<Chains.Joint>();
        var controls = new ArrayList<Control>();
        for (Object item : joints) {
            if (!(item instanceof Dict<?, ?> joint)) {
                throw Starlark.errorf("expected a joint dict");
            }
            Object min = joint.get("min");
            Object max = joint.get("max");
            Kinematics.Range limits = absent(min) && absent(max) ? null
                : new Kinematics.Range(number(min, thread), number(max, thread));
            Object kind = joint.get("kind");
            if ("revolute".equals(kind)) {
                geometry.add(new Chains.Revolute(vector(joint.get("pivot"), thread),
                    vector(joint.get("axis"), thread), limits));
            } else if ("prismatic".equals(kind)) {
                geometry.add(new Chains.Prismatic(vector(joint.get("direction"), thread), limits));
            } else {
                throw Starlark.errorf("joint kind must be revolute or prismatic");
            }
            double perUnit = number(joint.get("per_unit"), thread);
            if (perUnit == 0) {
                throw Starlark.errorf("per_unit must be non-zero");
            }
            controls.add(new Control(perUnit, number(joint.get("zero"), thread)));
        }
        Chains.Vec tip = vector(fields.get("tip"), thread);
        Object axis = fields.get("tip_axis");
        return new Calibrated(absent(axis) ? new Chains.Chain(geometry, tip)
            : new Chains.Chain(geometry, tip, vector(axis, thread)), controls);
    }

    private record Control(double perUnit, double zero) {
    }

    record Calibrated(Chains.Chain chain, List<Control> controls) {
        List<Double> aim(List<Double> values) {
            var aim = new ArrayList<Double>(controls.size());
            for (int i = 0; i < controls.size(); i++) {
                Control control = controls.get(i);
                aim.add(control.zero + values.get(i) / control.perUnit);
            }
            return aim;
        }

        List<Double> values(Object input, StarlarkThread thread) throws EvalException {
            var supplied = sequence(input, "controls");
            if (supplied.size() != controls.size()) {
                throw Starlark.errorf("expected one control value per joint");
            }
            var values = new ArrayList<Double>();
            for (int i = 0; i < controls.size(); i++) {
                Control control = controls.get(i);
                values.add((number(supplied.get(i), thread) - control.zero) * control.perUnit);
            }
            return values;
        }
    }

    static StarlarkList<StarlarkFloat> controls(List<Double> aim, StarlarkThread thread) {
        return StarlarkList.copyOf(thread.mutability(), aim.stream().map(StdIk::real).toList());
    }

    private static boolean absent(Object value) {
        return value == null || value == Starlark.NONE;
    }

    static Sequence<?> sequence(Object value, String name) throws EvalException {
        if (!(value instanceof Sequence<?> sequence)) {
            throw Starlark.errorf("expected a list or tuple for %s", name);
        }
        return sequence;
    }

    private static Chains.Vec vector(Object value, StarlarkThread thread) throws EvalException {
        var point = sequence(value, "XYZ");
        if (point.size() != 3) {
            throw Starlark.errorf("expected three XYZ coordinates");
        }
        return new Chains.Vec(number(point.get(0), thread), number(point.get(1), thread), number(point.get(2), thread));
    }

    private static StarlarkList<StarlarkFloat> vector(Chains.Vec point, StarlarkThread thread) {
        return StarlarkList.copyOf(thread.mutability(), List.of(real(point.x()), real(point.y()), real(point.z())));
    }

    private static StarlarkFloat real(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Expected a finite number");
        }
        return StarlarkFloat.of(value);
    }

    static double number(Object value, StarlarkThread thread) throws EvalException {
        if (value instanceof StarlarkInt integer) {
            ScriptSteps.charge(thread, 1 + integer.toBigInteger().bitLength() / 32);
            return integer.toFiniteDouble();
        }
        if (value instanceof StarlarkFloat real && Double.isFinite(real.toDouble())) {
            ScriptSteps.charge(thread, 1);
            return real.toDouble();
        }
        throw Starlark.errorf("expected a finite number");
    }
}
