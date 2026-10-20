package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Workshop;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OutputBudgetGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "output_budget");

    private record Action(NodeSpec spec) implements Node {
        public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
    }

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(OutputBudgetGameTests.class); }

    @GameTest(template = "empty")
    public static void additiveSameItemRemainderStillCannotFitInOneCell(GameTestHelper helper) {
        check(helper, 1); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void additiveSameItemRemainderStillFitsExactlyTwoCells(GameTestHelper helper) {
        check(helper, 2); helper.succeed();
    }

    private static void check(GameTestHelper helper, int cells) {
        var fixture = new PlanningFixture(helper, cells);
        var water = Goods.specOf(Items.WATER_BUCKET.getDefaultInstance());
        var maker = new Action(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(fixture.source),
            Stances.at(fixture.source), Workload.Once.of(1))
            .gives(new Amount(water, 1)).leaves(List.of(new Amount(water, 1))).done());
        // This provider declares an extra same-item remainder, rather than an entire-output estimate.
        // The default API must reserve both physical unstackable buckets even when only one was requested.
        Workshop workshop = new Workshop() {
            public ResourceLocation id() { return OWNER; }
            public UUID key() { return maker.id(); }
            public WorkSite site() { return maker.spec().site(); }
            public List<Refinement.Change> options(Intent intent, Grown graph) {
                return intent instanceof Produce produce && produce.wanted().admits(water)
                    ? List.of(Refinement.Change.of(Grown.of(maker))) : List.of();
            }
        };
        var consume = new Action(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(fixture.target),
            Stances.at(fixture.target), Workload.Once.of(1)).needs(new Need(water, 1, true)).done());
        var solver = new Solving();
        try {
            var stock = new Stock(List.of(), Map.of(new Stash(fixture.target), 27));
            var planned = solver.handle(new Known(fixture.situation(stock, List.of(workshop)), fixture.crew.hands()),
                List.of(new Message.Submitted(OWNER, Grown.of(consume))), 0);
            boolean scheduled = planned.schedule().tours().values().stream().anyMatch(tour -> tour.nodes().contains(maker.id()));
            helper.assertValueEqual(scheduled, cells == 2,
                "an additive same-item remainder needs two cells and is never subtracted as an output promise: " + planned.diagnosis());
        } finally {
            solver.closed(); fixture.worker.body().discard();
        }
    }
}
