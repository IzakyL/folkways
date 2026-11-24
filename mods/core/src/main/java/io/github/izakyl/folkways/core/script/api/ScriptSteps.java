package io.github.izakyl.folkways.core.script.api;

import net.starlark.java.eval.AllocationBudget;
import net.starlark.java.eval.EvalException;
import net.starlark.java.eval.Starlark;
import net.starlark.java.eval.StarlarkThread;

/** Native work lowers the interpreter ceiling by the steps it consumes. */
public final class ScriptSteps {

    private long ceiling;

    ScriptSteps(long ceiling) {
        this.ceiling = ceiling;
    }

    long remaining(StarlarkThread thread) {
        return ceiling - thread.getExecutedSteps();
    }

    public static void charge(StarlarkThread thread, long steps) throws EvalException {
        ScriptRuntime.checkTime();
        AllocationBudget.checkpoint();
        ScriptSteps budget = thread.getThreadLocal(ScriptSteps.class);
        if (budget == null) {
            return;
        }
        long remaining = budget.remaining(thread);
        if (steps >= remaining) {
            budget.ceiling = thread.getExecutedSteps();
            throw Starlark.errorf("Starlark computation cancelled: too many steps");
        }
        budget.ceiling -= steps;
        thread.setMaxExecutionSteps(budget.ceiling);
    }
}
