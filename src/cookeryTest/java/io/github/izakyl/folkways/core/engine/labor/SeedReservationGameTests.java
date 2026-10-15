package io.github.izakyl.folkways.core.engine.labor;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.NodeState;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.ReservedInput;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.plan.Shown;
import io.github.izakyl.folkways.core.engine.plan.Vertex;
import io.github.izakyl.folkways.core.engine.plan.WeaveView;
import io.github.izakyl.folkways.plugins.cookery.seeds.SeedBudget;
import io.github.izakyl.folkways.plugins.cookery.seeds.SeedSupport;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeedReservationGameTests {
    private static final ResourceLocation OWNER = ResourceLocation.fromNamespaceAndPath("folkways_test", "seed_reservations");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(SeedReservationGameTests.class); }

    @GameTest(template = "empty")
    public static void the129thActuallyFetchedSeedCannotBeComposted(GameTestHelper helper) {
        var real = InputReservationGameTests.fetched129(helper);
        var snapshot = new SeedBudget.Snapshot(List.of(new SeedBudget.Stock("actual fetched pack", SeedBudget.Location.pack(real.resident()), real.pack())),
            real.inputs().stream().map(input -> new SeedBudget.Claim(input.node(), SeedBudget.Location.pack(input.resident().orElseThrow()),
                input.goods(), input.count())).toList(), List.of(), Map.of());
        var budget = new SeedBudget(() -> snapshot, () -> Set.of(Items.WHEAT_SEEDS));
        var tally = budget.tally(Items.WHEAT_SEEDS);
        helper.assertValueEqual(tally.physical(), 129L, "the allowance reads the three actual completed fetches");
        helper.assertValueEqual(tally.committed(), 129L, "every seed is still promised to the one consumer");
        helper.assertValueEqual(tally.compostable(), 0L, "there is no bogus 129th surplus after max(64,64,1) plus a reserve");
        helper.assertTrue(!budget.permits(UUID.randomUUID(), ItemStack.EMPTY), "no undeclared escrow can change that allowance");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void repeatedSnapshotViewsPreserveDistinctHandoffsWithoutRepeatingThem(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.body.pack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
            rig.body.pack().setItem(1, new ItemStack(Items.WHEAT_SEEDS, 64));
            rig.body.pack().setItem(2, new ItemStack(Items.WHEAT_SEEDS, 1));
            UUID consumer = UUID.randomUUID();
            var goods = Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance());
            var first = new ReservedInput(consumer, Optional.empty(), Optional.of(rig.body.id()), goods, 64, Optional.of(UUID.randomUUID()));
            var second = new ReservedInput(consumer, Optional.empty(), Optional.of(rig.body.id()), goods, 64, Optional.of(UUID.randomUUID()));
            var last = new ReservedInput(consumer, Optional.empty(), Optional.of(rig.body.id()), goods, 1, Optional.of(UUID.randomUUID()));
            rig.inputs = List.of(first, first, second, last, second);
            var snapshot = rig.support().snapshot();
            helper.assertTrue(snapshot.complete(), "the actual held body and its real pack are loaded");
            helper.assertValueEqual(snapshot.claims().size(), 1, "repeated per-dimension views export one physical claim for this consumer");
            helper.assertValueEqual(snapshot.claims().getFirst().count(), 129L, "distinct upstream fetches sum, while repeated observations take their maximum");
            helper.assertValueEqual(rig.support().budget(rig.level).tally(Items.WHEAT_SEEDS).compostable(), 0L,
                "the native snapshot bridge never offers the 129th reserved seed to compost");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void anUnboundFutureHandoffDoesNotReserveAnUnrelatedPackAgain(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            rig.body.pack().setItem(0, new ItemStack(Items.WHEAT_SEEDS, 64));
            rig.body.pack().setItem(1, new ItemStack(Items.WHEAT_SEEDS, 64));
            rig.body.pack().setItem(2, new ItemStack(Items.WHEAT_SEEDS, 64));
            rig.body.pack().setItem(3, new ItemStack(Items.WHEAT_SEEDS, 8));
            var goods = Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance());
            UUID fetch = UUID.randomUUID();
            rig.inputs = List.of(new ReservedInput(fetch, Optional.empty(), Optional.of(rig.body.id()), goods, 64),
                new ReservedInput(UUID.randomUUID(), Optional.empty(), Optional.empty(), goods, 64, Optional.of(fetch)));
            var tally = rig.support().budget(rig.level).tally(Items.WHEAT_SEEDS);
            helper.assertValueEqual(tally.physical(), 200L, "only actual loaded inventory contributes to the seed count");
            helper.assertValueEqual(tally.committed(), 64L, "a future promise names the existing upstream claim rather than another sixty-four");
            helper.assertValueEqual(tally.compostable(), 72L, "one obligation and one reserve leave the real surplus, without speculative extra gathering");
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void aWorkingWithinProtectsNativeProductsFromItsCompletedFirstPiece(GameTestHelper helper) {
        try (var rig = new Rig(helper)) {
            var seeds = Goods.specOf(Items.PUMPKIN_SEEDS.getDefaultInstance());
            rig.body.pack().setItem(0, new ItemStack(Items.PUMPKIN, 2));
            rig.body.pack().setItem(1, new ItemStack(Items.PUMPKIN_SEEDS, 64));
            var input = CraftingInput.of(1, 1, List.of(Items.PUMPKIN.getDefaultInstance()));
            var recipe = rig.level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, rig.level).orElseThrow();
            helper.assertTrue(recipe.value().assemble(input, rig.level.registryAccess()).is(Items.PUMPKIN_SEEDS), "the child uses the real vanilla seed conversion");
            var childSpec = NodeSpec.of(UUID.randomUUID(), OWNER, WorkSite.at(rig.level, rig.body.blockPosition()), Stances.WHEREVER,
                Workload.Once.of(1)).needs(new Need(Goods.specOf(Items.PUMPKIN.getDefaultInstance()), 1, true)).done();
            Node child = new Node() {
                public NodeSpec spec() { return childSpec; }
                public Outcome commit(ServerLevel level, Worker who) {
                    var supplied = CraftingInput.of(1, 1, who.supplied());
                    if (!recipe.value().matches(supplied, level)) throw new AssertionError("native child recipe input changed");
                    return new Outcome.Done(List.of(), List.of(recipe.value().assemble(supplied, level.registryAccess())), Optional.empty());
                }
            };
            var parentSpec = NodeSpec.of(UUID.randomUUID(), OWNER, WorkSite.at(rig.level, rig.body.blockPosition()), Stances.WHEREVER,
                (Workload.Within)(level, who) -> new Workload.Within.Turn.Piece(child))
                .gives(new Amount(seeds, 8)).done();
            Node parent = new Node() {
                public NodeSpec spec() { return parentSpec; }
                public Outcome commit(ServerLevel at, Worker who) { throw new AssertionError("the outer batch has not completed"); }
            };
            var consumerSpec = NodeSpec.of(UUID.randomUUID(), OWNER, WorkSite.at(rig.level, rig.body.blockPosition()), Stances.WHEREVER,
                Workload.Once.of(1)).needs(new Need(seeds, 8, true)).done();
            Node consumer = new Node() {
                public NodeSpec spec() { return consumerSpec; }
                public Outcome commit(ServerLevel at, Worker who) { return Outcome.done(); }
            };
            var graph = new WeaveView();
            var planned = new Placement(Set.of(), List.of(), List.of(), List.of(), List.of(
                new Placement.Draw(Optional.empty(), seeds, 8, Optional.empty(), Optional.of(parent.id()))));
            graph.apply(Shown.delta(Map.of(parent.id(), new Vertex(parent.id(), parent, Placement.NOWHERE),
                consumer.id(), new Vertex(consumer.id(), consumer, planned)), List.of(), List.of()));
            Outcome outcome = Commit.run(rig.level, child, rig.worker());
            helper.assertTrue(outcome instanceof Outcome.Done, "the genuine first piece actually spends one pumpkin");
            ((Outcome.Done)outcome).made().forEach(stack -> helper.assertTrue(Containers.insert(rig.body.pack(), stack).isEmpty(), "its actual native output fits"));
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), seeds), 68L, "the first child produced four real seeds before the parent commit");
            helper.assertValueEqual(Goods.countIn(rig.body.pack(), Goods.specOf(Items.PUMPKIN.getDefaultInstance())), 1L, "the second batch input is still unspent");
            rig.inputs = InputReservations.read(graph, id -> id.equals(parent.id()) ? NodeState.WORKING : NodeState.PENDING,
                id -> id.equals(parent.id()) ? rig.body.id() : null);
            helper.assertTrue(rig.inputs.getFirst().resident().filter(rig.body.id()::equals).isPresent()
                && !rig.inputs.getFirst().pending(), "partial Within products name the actual producer pack");
            helper.assertValueEqual(rig.support().budget(rig.level).tally(Items.PUMPKIN_SEEDS).compostable(), 0L,
                "the four native intermediate products cannot be composted while their consumer waits");
        }
        helper.succeed();
    }

    private static final class Rig implements AutoCloseable {
        final ServerLevel level;
        final Colony colony;
        final ResidentEntity body;
        List<ReservedInput> inputs = List.of();
        Rig(GameTestHelper helper) {
            level = helper.getLevel(); colony = Colonies.mint(level.getServer());
            body = PersonBody.RESIDENT.get().create(level); body.setNoAi(true);
            body.moveTo(helper.absolutePos(new BlockPos(2, 1, 4)).getCenter()); level.addFreshEntity(body);
            colony.hold(PersonContent.ID, Held.Entity.of(body)).orElseThrow();
            body.joinColony(colony.id()); colony.entered(body);
        }
        SeedSupport support() {
            Colony observed = (Colony)Proxy.newProxyInstance(Colony.class.getClassLoader(), new Class<?>[]{Colony.class}, (proxy, method, args) -> {
                if (method.getName().equals("reservedInputs")) return inputs;
                try { return method.invoke(colony, args); } catch (InvocationTargetException broken) { throw broken.getCause(); }
            });
            return new SeedSupport(observed, level.getServer());
        }
        Worker worker() {
            return new Worker() {
                public io.github.izakyl.folkways.core.api.resident.Resident resident() { return body.resident(); }
                public Mob body() { return body; }
                public Container pack() { return body.pack(); }
                public List<Store> within() { return List.of(); }
                public ItemStack held(ToolNeed need) { return ItemStack.EMPTY; }
                public void spill(ItemStack stack) { throw new AssertionError("unexpected spill: " + stack); }
                public int rankOf(String perk) { return 0; }
                public List<Node> route() { return List.of(); }
                public Placement placement() { return Placement.NOWHERE; }
            };
        }
        public void close() { body.discard(); colony.raze(); }
    }
}
