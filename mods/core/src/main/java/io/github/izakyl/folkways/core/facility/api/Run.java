package io.github.izakyl.folkways.core.facility.api;

import io.github.izakyl.folkways.core.plan.api.Intent;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.world.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import io.github.izakyl.folkways.core.work.api.Workload;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/** One run of a library routine, asked of the plan; Core's routine refinement grows it into the routine's work. */
public final class Run implements Intent {

    private final ResourceLocation routine;
    private final Routine.Call call;
    private final NodeSpec spec;
    private final boolean resumed;

    Run(ResourceLocation routine, Routine.Call call, boolean resumed) {
        this.routine = Objects.requireNonNull(routine, "routine");
        this.call = Objects.requireNonNull(call, "call");
        this.resumed = resumed;
        spec = NodeSpec.of(call.id(), call.owner(), new WorkSite.AtBlock(call.site()), Stances.WHEREVER,
            Workload.Once.of(0)).done();
    }

    public ResourceLocation routine() {
        return routine;
    }

    public Routine.Call call() {
        return call;
    }

    boolean resumed() {
        return resumed;
    }

    @Override
    public NodeSpec spec() {
        return spec;
    }

    @Override
    public String toString() {
        return "Run[" + routine + " " + call.id() + "]";
    }
}
