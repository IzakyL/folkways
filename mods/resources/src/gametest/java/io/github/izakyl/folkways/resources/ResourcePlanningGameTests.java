package io.github.izakyl.folkways.resources;

import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.Claims;
import io.github.izakyl.folkways.core.engine.plan.Cooldowns;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Shown;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.core.engine.plan.Weaver;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysResourcesMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class ResourcePlanningGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(ResourcePlanningGameTests.class); }

    @GameTest(template = "empty_large")
    public static void canceledHaulReturnsActualWoodResourceToStorage(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var level = helper.getLevel();
        level.setBlock(f.source.cell(), Blocks.BARREL.defaultBlockState(), 3);
        var store = Containers.at(level, f.source.cell()).orElseThrow();
        ItemStack bundle = new ItemStack(ResourceItems.WOOD.get(), 4);
        bundle.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
            net.minecraft.network.chat.Component.literal("resource bundle"));
        store.setItem(0, bundle.copy());
        Stash source = new Stash(f.source);
        var planner = new Weaver(new Claims(), new Cooldowns());
        NodeSpec spec = NodeSpec.of(UUID.randomUUID(), FolkwaysResourcesMod.CONTENT, new WorkSite.AtBlock(f.target),
            Stances.at(f.target), Workload.Once.of(1)).needs(new Need(ItemSpec.of(Items.BIRCH_PLANKS), 2, true).replacing()).done();
        Node node = new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel world, Worker who) { throw new AssertionError("canceled work never commits"); }
        };
        try {
            var situation = f.situation(new Stock(List.of(new Stock.Holding(source, store.getItem(0))), Map.of(source, 26)), List.of());
            planner.know(situation, f.crew);
            planner.submit(FolkwaysResourcesMod.CONTENT, Grown.of(node));
            var view = Shown.view(planner.grow(false));
            var fetch = view.vertices().values().stream().filter(vertex -> vertex.node() instanceof TransferNode).findFirst().orElseThrow();
            var worker = PlanningFixture.placed(f.worker, fetch.plan(), List.of(new Worker.Store(f.source, store)));
            helper.assertTrue(Commit.run(level, fetch.node(), worker) instanceof Outcome.Done, "hauling carries the actual wood resource");
            helper.assertValueEqual(Goods.countIn(f.worker.pack(), ItemSpec.of(ResourceItems.WOOD.get())), 2L, "the carrier holds two resources");
            planner.finished(fetch.id());
            planner.withdraw(FolkwaysResourcesMod.CONTENT, node.id());
            var hand = f.crew.hands().getFirst();
            Crew carrying = new Crew(List.of(new Crew.Hand(hand.who(), f.target, 5, 6, 1, hand.licences(), List.of(),
                Optional.empty(), List.of(f.worker.pack().getItem(0).copy()))));
            planner.know(f.situation(new Stock(List.of(new Stock.Holding(source, store.getItem(0))), Map.of(source, 26)), List.of()), carrying);
            planner.stow(hand.id());
            var recovery = Shown.view(planner.grow(false));
            var put = recovery.vertices().values().stream().filter(vertex -> vertex.node() instanceof TransferNode).findFirst().orElseThrow();
            worker = PlanningFixture.placed(f.worker, put.plan(), List.of(new Worker.Store(f.source, store)));
            helper.assertTrue(Commit.run(level, put.node(), worker) instanceof Outcome.Done, "canceled cargo is stored again");
            helper.assertTrue(ItemStack.isSameItemSameComponents(store.getItem(0), bundle) && store.getItem(0).getCount() == 4 && f.worker.pack().isEmpty(),
                "all four resources return with their original components, without creating birch planks");
        } finally { planner.closed(); }
        helper.succeed();
    }
}
