package io.github.izakyl.folkways.plugins.wares;

import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Situation;
import io.github.izakyl.folkways.core.engine.plan.Solving;
import io.github.izakyl.folkways.core.engine.plan.Stands;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CraftRemainderPlanningGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CraftRemainderPlanningGameTests.class); }

    @GameTest(template = "empty")
    public static void oneFreeCellCannotScheduleCakeAndItsActualNativeBucketRemainders(GameTestHelper helper) {
        check(helper, 1); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void twoFreeCellsCanScheduleCakeAndAllThreeNativeBuckets(GameTestHelper helper) {
        check(helper, 2); helper.succeed();
    }
    @GameTest(template = "empty")
    public static void batchSizingIncludesRealNativeOutputsWithoutInventingInputNeeds(GameTestHelper helper) {
        var level = helper.getLevel(); var fixture = new PlanningFixture(helper, 2);
        var at = fixture.source; level.setBlockAndUpdate(at.block(level), Blocks.CRAFTING_TABLE.defaultBlockState());
        var workshop = new PackCraftHost(at, Blocks.CRAFTING_TABLE, Stances.at(at.above()))
            .frozen(RecipeIndex.over(level, level.getRecipeManager()), level, Optional.empty());
        var cake = Goods.specOf(new ItemStack(Items.CAKE)); var bucket = Goods.specOf(new ItemStack(Items.BUCKET));
        boolean[] sizedVanilla = { false };
        Produce wanted = Produce.of(WaresContent.ID, new WorkSite.AtBlock(at), cake, 2,
            (site, perRun, perRunYield, by, limit) -> {
                helper.assertTrue(perRun.stream().anyMatch(need -> need.carried() && need.spec().equals(cake) && need.count() == 1), "batch capacity includes the actual primary output");
                long milk = perRun.stream().filter(need -> !need.carried()
                    && Goods.matches(new ItemStack(Items.MILK_BUCKET), need.spec())).mapToLong(Need::count).sum();
                helper.assertTrue(milk > 0 && perRun.stream().anyMatch(need -> need.carried() && need.spec().equals(bucket) && need.count() == milk), "each real cake candidate budgets its own actual native milk containers: " + perRun);
                if (milk == 3) { sizedVanilla[0] = true; }
                return 1;
            });
        try {
            var options = workshop.options(wanted, Grown.of(wanted));
            helper.assertTrue(sizedVanilla[0], "the real vanilla three-milk recipe is among the capacity-sized candidates");
            for (var option : options) {
                Node node = option.replacement().nodes().getFirst();
                helper.assertTrue(node.spec().needs().stream().noneMatch(need -> need.spec().equals(cake) || need.spec().equals(bucket)), "capacity hints are not fabricated cake or empty bucket inputs");
                helper.assertTrue(node.spec().gives().size() == 1 && node.spec().gives().getFirst().spec().equals(cake), "the main output is promised exactly once");
                helper.assertTrue(node.spec().leaves().size() == 1 && node.spec().leaves().getFirst().spec().equals(bucket), "only each candidate's native extra containers are leftover room");
            }
        } finally { fixture.worker.body().discard(); }
        helper.succeed();
    }

    private static void check(GameTestHelper helper, int cells) {
        var fixture = new PlanningFixture(helper, cells); var level = helper.getLevel(); var body = (ResidentEntity) fixture.worker.body();
        var source = new Stash(fixture.source); var target = new Stash(fixture.target); var footing = fixture.source.above();
        var station = fixture.source.at(fixture.source.cell().offset(0, 0, 2));
        level.setBlockAndUpdate(station.block(level), Blocks.CRAFTING_TABLE.defaultBlockState());
        var workshop = new PackCraftHost(station, Blocks.CRAFTING_TABLE, Stances.at(footing))
            .frozen(RecipeIndex.over(level, level.getRecipeManager()), level, Optional.empty());
        List<ItemStack> inputs = List.of(new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.MILK_BUCKET),
            new ItemStack(Items.MILK_BUCKET), new ItemStack(Items.SUGAR, 2), new ItemStack(Items.EGG), new ItemStack(Items.WHEAT, 3));
        var stock = new Stock(inputs.stream().map(stack -> new Stock.Holding(source, stack)).toList(), Map.of(source, 27, target, 27));
        var stands = Stands.building().container(source, Set.of(new Stand(footing)))
            .container(target, Set.of(new Stand(fixture.target))).done();
        var hand = new Crew.Hand(body.resident(), footing, cells, cells, 1,
            List.of(body.licences().of(WaresContent.trade()).orElseThrow(), body.licences().of(Vocations.required(Vocations.HAULING)).orElseThrow()),
            List.of(), Optional.empty());
        var spec = NodeSpec.of(UUID.randomUUID(), WaresContent.ID, new WorkSite.AtBlock(fixture.target),
            Stances.at(fixture.target), Workload.Once.of(1)).needs(new Need(Goods.specOf(new ItemStack(Items.CAKE)), 1, true)).done();
        Node consume = new Node() {
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel ignored, Worker who) { return Outcome.done(); }
        };
        var solver = new Solving();
        try {
            var planned = solver.handle(new Known(new Situation(fixture.ways, stock, List.of(workshop), stands), List.of(hand)),
                List.of(new Message.Submitted(WaresContent.ID, Grown.of(consume))), 0);
            if (cells == 1) {
                helper.assertTrue(!planned.schedule().unassigned().isEmpty() || !planned.diagnosis().shortfalls().isEmpty(),
                    "the real planner refuses a one-cell pack that cannot hold cake and the three native buckets: " + planned.diagnosis());
            } else {
                helper.assertTrue(planned.diagnosis().shortfalls().isEmpty() && planned.schedule().unassigned().isEmpty(),
                    "exactly two free cells are sufficient for the actual native cake and merged bucket stack: " + planned.diagnosis());
                var craft = planned.weave().vertices().values().stream().filter(vertex -> vertex.node() instanceof PackCraftNode).findFirst().orElseThrow();
                helper.assertTrue(craft.plan().carrying().stream().anyMatch(amount -> amount.count() == 3
                    && Goods.matches(new ItemStack(Items.BUCKET), amount.spec())), "the scheduled placement actually reserves the native bucket remainder");
                helper.assertTrue(craft.node().spec().leaves().size() == 1, "only unpromised containers are leaves; the promised cake is not counted twice");
                SimpleContainer actualSource = new SimpleContainer(27); inputs.forEach(stack -> Containers.insert(actualSource, stack.copy()));
                Container actualPack = fixture.worker.pack();
                Worker actual = new Worker() {
                    public io.github.izakyl.folkways.core.api.resident.Resident resident() { return body.resident(); }
                    public Mob body() { return body; }
                    public Container pack() { return actualPack; }
                    public List<Store> within() { return List.of(new Store(fixture.source, actualSource)); }
                    public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
                    public void spill(ItemStack stack) { if (!stack.isEmpty()) { throw new AssertionError("unexpected spill: " + stack); } }
                    public Effects effects() { return body.effects(); }
                    public List<Node> route() { return List.of(craft.node()); }
                    public Placement placement() { return craft.plan(); }
                };
                Outcome outcome = Commit.run(level, craft.node(), actual);
                helper.assertTrue(outcome instanceof Outcome.Done, "the real planned cake consumes its exact claimed ingredients from the nearby store: " + outcome);
                ((Outcome.Done) outcome).made().forEach(stack -> helper.assertTrue(Containers.insert(actualPack, stack).isEmpty(), "the actual output and all three native containers fit the reserved two cells"));
                helper.assertValueEqual(Goods.countIn(actualPack, Goods.specOf(new ItemStack(Items.CAKE))), 1L, "one actual vanilla cake occupies the first reserved cell");
                helper.assertValueEqual(Goods.countIn(actualPack, Goods.specOf(new ItemStack(Items.BUCKET))), 3L, "all three native milk remainders stack in the second reserved cell");
                helper.assertTrue(actualSource.isEmpty(), "all actual ingredient stacks are consumed from the nearby store, not replaced by fake input needs");
            }
        } finally { solver.closed(); body.discard(); }
    }
}