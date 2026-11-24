package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import java.util.List;
import java.util.UUID;

public record PlanTask(UUID id, String doing, String vocation, WorldPos site, String owner,
                       List<PlanInputs.Ware> needs, List<PlanInputs.Ware> gives, UUID worker) {

    public PlanTask {
        needs = List.copyOf(needs);
        gives = List.copyOf(gives);
    }

    public static PlanTask of(NodeSpec spec, UUID worker) {
        return new PlanTask(spec.id(), spec.doing().toString(),
            spec.vocation().map(vocation -> vocation.id().toString()).orElse(null), spec.site().where(),
            spec.owner().toString(), spec.needs().stream()
                .map(need -> new PlanInputs.Ware(need.spec().describe(), need.count())).toList(),
            spec.gives().stream()
                .map(amount -> new PlanInputs.Ware(amount.spec().describe(), amount.count())).toList(), worker);
    }
}
