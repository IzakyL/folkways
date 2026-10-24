package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Leaves;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class BoundCargoRecoveryGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "bound_cargo");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(BoundCargoRecoveryGameTests.class); }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aPendingBoundInputDoesNotPreventUnloadingUnclaimedCargo(GameTestHelper helper) {
        checkRecovery(helper, false);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aTaskAlreadyInHandStillPreventsCargoRecovery(GameTestHelper helper) {
        checkRecovery(helper, true);
    }

    private static void checkRecovery(GameTestHelper helper, boolean started) {
        var fixture = new PlanningFixture(helper, 2);
        var hand = fixture.crew.hands().getFirst();
        ItemSpec seeds = Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance());
        ItemSpec bone = Goods.specOf(Items.BONE_MEAL.getDefaultInstance());
        var task = new ConsumerTask(NodeSpec.of(UUID.randomUUID(), OWNER, new WorkSite.AtBlock(fixture.target),
            Stances.at(fixture.target), Workload.Once.of(10))
            .needs(new Need(seeds, 21, true)).leaves(Leaves.room(1)).done());
        var cargo = List.of(new ItemStack(Items.WHEAT_SEEDS, 1), new ItemStack(Items.BONE_MEAL, 64));
        var full = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(), 0, 2, 1, hand.licences(),
            hand.tools(), Optional.empty(), cargo)));
        var stock = new Stock(List.of(new Stock.Holding(new Stash(fixture.source), new ItemStack(Items.WHEAT_SEEDS, 20))),
            Map.of(new Stash(fixture.source), 0, new Stash(fixture.target), 2));
        var claims = new Claims();
        var weaver = new Weaver(claims, new Cooldowns());
        weaver.know(fixture.situation(stock, List.of()), full);
        weaver.submit(OWNER, Grown.of(task));
        weaver.grow(false);
        helper.assertTrue(claims.taken(new Resource.Cargo(hand.id(), seeds)) == 1,
            "the one carried seed binds the input to its physical owner");
        var next = new Crew(List.of(new Crew.Hand(hand.who(), hand.at(), 0, 2, 1, hand.licences(),
            hand.tools(), started ? Optional.of(task.id()) : Optional.empty(), cargo)));
        weaver.know(fixture.situation(stock, List.of()), next);
        weaver.stow(hand.id());
        var delta = Shown.view(weaver.grow(true));
        var unloads = delta.vertices().values().stream().map(Vertex::node)
            .filter(TransferNode.class::isInstance).map(TransferNode.class::cast)
            .filter(node -> node.goods().stream().anyMatch(stack -> stack.is(Items.BONE_MEAL))).toList();
        helper.assertTrue(started ? unloads.isEmpty() : unloads.size() == 1,
            started ? "active work keeps its pack until settlement" : "a pending bound task must allow unclaimed bone meal to be unloaded first");
        if (!started) {
            helper.assertTrue(unloads.getFirst().goods().stream().noneMatch(stack -> stack.is(Items.WHEAT_SEEDS)),
                "recovery must not unload the seed already claimed by the pending consumer");
            helper.assertTrue(delta.pins().get(unloads.getFirst().id()).equals(hand.id()),
                "the real carrier performs the unloading");
        }
        helper.assertTrue(claims.taken(new Resource.Cargo(hand.id(), seeds)) == 1,
            "recovering other cargo preserves the consumer's physical seed reservation");
        weaver.closed();
        helper.succeed();
    }

    private record ConsumerTask(NodeSpec spec) implements Node {
        public Outcome commit(ServerLevel level, Worker who) {
            return new Outcome.Done(List.of(), List.of(), Optional.empty());
        }
    }
}
