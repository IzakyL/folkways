package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.core.work.api.NodeSpec;
import io.github.izakyl.folkways.core.plan.api.Workshop;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;

public record PlanInputs(List<Stocked> stock, List<Shop> workshops, int stashes, int stands,
                         int hands, String ways, List<Process> processes) {

    public static final PlanInputs EMPTY = new PlanInputs(List.of(), List.of(), 0, 0, 0, "unknown", List.of());

    public PlanInputs {
        stock = List.copyOf(stock);
        workshops = List.copyOf(workshops);
        processes = List.copyOf(processes);
    }

    public record Ware(String item, long count) {
    }

    public record Stocked(String item, long count, int locations) {
    }

    public record Shop(UUID id, String owner, WorldPos site) {
    }

    public record Process(UUID workshop, List<Ware> inputs, List<Ware> outputs) {

        public Process {
            inputs = List.copyOf(inputs);
            outputs = List.copyOf(outputs);
        }

        static Process of(UUID workshop, NodeSpec spec) {
            return new Process(workshop, spec.needs().stream()
                .map(need -> new Ware(need.spec().describe(), need.count())).toList(),
                spec.gives().stream().map(amount -> new Ware(amount.spec().describe(), amount.count())).toList());
        }
    }

    static final class Capture {

        private Stock stock;
        private List<Stocked> stocked = List.of();
        private List<Workshop> workshops = List.of();
        private List<Shop> shops = List.of();
        private io.github.izakyl.folkways.core.inventory.internal.Stands around;
        private int stands;

        PlanInputs of(Known known, List<Process> processes) {
            Situation world = known.world();
            if (stock != world.stock()) {
                stock = world.stock();
                stocked = stockOf(stock);
                around = null;
            }
            if (!workshops.equals(world.workshops())) {
                workshops = world.workshops();
                shops = workshops.stream()
                    .map(shop -> new Shop(shop.key(), shop.id().toString(), shop.site().where())).toList();
            }
            if (around != world.stands()) {
                around = world.stands();
                Set<Stand> cells = new LinkedHashSet<>();
                stock.stashes().forEach(stash -> cells.addAll(around.around(stash)));
                stands = cells.size();
            }
            return new PlanInputs(stocked, shops, stock.stashes().size(), stands, known.hands().size(),
                world.ways().getClass().getSimpleName(), processes);
        }
    }

    private static List<Stocked> stockOf(Stock stock) {
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Set<Stash>> places = new LinkedHashMap<>();
        for (Stock.Holding holding : stock.holdings()) {
            String item = BuiltInRegistries.ITEM.getKey(holding.stack().getItem()).toString();
            counts.merge(item, holding.count(), Long::sum);
            places.computeIfAbsent(item, ignored -> new LinkedHashSet<>()).add(holding.where());
        }
        return counts.entrySet().stream()
            .map(entry -> new Stocked(entry.getKey(), entry.getValue(), places.get(entry.getKey()).size())).toList();
    }
}
