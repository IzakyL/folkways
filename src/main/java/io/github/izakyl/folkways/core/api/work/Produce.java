package io.github.izakyl.folkways.core.api.work;

import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.vocation.Vocation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.resources.ResourceLocation;

public record Produce(NodeSpec spec, ItemSpec wanted, long count, Batching batching) implements Intent {

    @FunctionalInterface
    public interface Batching {

        long runs(WorkSite at, List<Need> perRun, long perRunYield, Optional<Vocation> by, long limit);
    }

    public static final Batching UNBOUNDED = (at, perRun, perRunYield, by, limit) -> limit;

    public Produce {
        if (count < 1) {
            throw new IllegalArgumentException("nothing wanted is nothing to make");
        }
    }

    public static Produce of(ResourceLocation owner, WorkSite near, ItemSpec wanted, long count,
                             Batching batching) {
        return new Produce(NodeSpec.of(UUID.randomUUID(), owner, near, Stances.WHEREVER,
            Workload.Once.of(1, who -> 1.0)).done(), wanted, count, batching);
    }

    public long runs(WorkSite at, List<Need> perRun, long perRunYield, Optional<Vocation> by, long ceiling) {
        long yields = Math.max(1, perRunYield);
        long enough = (count + yields - 1) / yields;
        return Math.max(1, Math.min(ceiling, batching.runs(at, perRun, yields, by, Math.min(ceiling, enough))));
    }
}
