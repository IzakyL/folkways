package io.github.izakyl.folkways.cookery.millstone;

import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.BambooTrayBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.MillstoneBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.ShawarmaSpitBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.SteamerBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.TeapotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.effect.Effects;
import io.github.izakyl.folkways.core.api.resident.Resident;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Amount;
import io.github.izakyl.folkways.core.api.work.Ending;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Intent;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Placement;
import io.github.izakyl.folkways.core.api.work.Produce;
import io.github.izakyl.folkways.core.api.work.Refinement;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.ToolNeed;
import io.github.izakyl.folkways.core.api.work.Tools;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.api.work.Workshop;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Situation;
import io.github.izakyl.folkways.core.engine.plan.Solving;
import io.github.izakyl.folkways.core.engine.plan.Stands;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import io.github.izakyl.folkways.cookery.CookeryContent;
import io.github.izakyl.folkways.cookery.KitchenBookings;
import io.github.izakyl.folkways.cookery.KitchenStation;
import io.github.izakyl.folkways.cookery.spit.SpitStation;
import io.github.izakyl.folkways.cookery.steamer.SteamerStation;
import io.github.izakyl.folkways.cookery.teapot.TeapotStation;
import io.github.izakyl.folkways.cookery.tray.TrayStation;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.ResidentEntity;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ApplianceCarryGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(ApplianceCarryGameTests.class); }

    @GameTest(template = "empty")
    public static void theMillFetchesNearbyWheatBeforeItsNativeLoad(GameTestHelper helper) {
        run(helper, new MillstoneStation(), ModBlocks.MILLSTONE.get(), List.of(new ItemStack(Items.WHEAT, 3)),
            new ItemStack(ModItems.FLOUR.get(), 3), 1);
    }
    @GameTest(template = "empty")
    public static void theSpitFetchesNearbyMeatBeforeItsNativeLoad(GameTestHelper helper) {
        run(helper, new SpitStation(), ModBlocks.SHAWARMA_SPIT.get(), List.of(new ItemStack(Items.BEEF, 2)),
            new ItemStack(Items.COOKED_BEEF, 2), 1);
    }
    @GameTest(template = "empty")
    public static void theSteamerFetchesNearbyDoughBeforeItsNativeLoad(GameTestHelper helper) {
        run(helper, new SteamerStation(), ModBlocks.STEAMER.get(), List.of(new ItemStack(ModItems.RAW_DOUGH.get(), 2)),
            new ItemStack(ModItems.MANTOU.get(), 2), 1);
    }
    @GameTest(template = "empty")
    public static void theTrayFetchesNearbyLeavesBeforeItsNativeLoad(GameTestHelper helper) {
        run(helper, new TrayStation(), ModBlocks.BAMBOO_TRAY.get(), List.of(new ItemStack(ModItems.FRESH_TEA_LEAVES.get(), 2)),
            new ItemStack(ModItems.DRIED_TEA_LEAVES.get(), 2), 1);
    }
    @GameTest(template = "empty")
    public static void theTeapotFetchesItsFluidIngredientAndTwelveCupsBeforeNativeLoading(GameTestHelper helper) {
        run(helper, new TeapotStation(), ModBlocks.TEAPOT.get(), List.of(new ItemStack(Items.WATER_BUCKET),
            new ItemStack(ModItems.TIEGUANYIN_TEA_BAG.get()), new ItemStack(ModItems.EMPTY_CUP.get(), 12)),
            new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "tieguanyin")), 12), 2);
    }

    @GameTest(template = "empty")
    public static void aMillKeepsUnpromisedAndChanceOutputsWithoutDuplicatingItsPromisedMain(GameTestHelper helper) {
        var fixture = new PlanningFixture(helper, 3);
        WorldPos at = fixture.source;
        var flour = Goods.specOf(new ItemStack(ModItems.FLOUR.get()));
        var bone = Goods.specOf(new ItemStack(Items.BONE));
        var diamond = Goods.specOf(new ItemStack(Items.DIAMOND));
        // The native recipe API allows four outputs. This declared job isolates both an unrequested guaranteed
        // side product and a possible bonus of the same item as the requested main product.
        var job = new MillstoneStation.Job(ResourceLocation.fromNamespaceAndPath("folkways_test", "mill_capacity"),
            Goods.specOf(new ItemStack(Items.WHEAT)), List.of(new ItemStack(ModItems.FLOUR.get(), 63), new ItemStack(Items.BONE)),
            List.of(new ItemStack(ModItems.FLOUR.get(), 63), new ItemStack(Items.BONE),
                new ItemStack(ModItems.FLOUR.get()), new ItemStack(Items.DIAMOND)));
        var work = new MillstoneWork(UUID.randomUUID(), at, Stances.at(at), job, 1, new KitchenBookings(), () -> {}, null);
        Workshop workshop = new Workshop() {
            public ResourceLocation id() { return CookeryContent.ID; }
            public UUID key() { return work.id(); }
            public WorkSite site() { return new WorkSite.AtBlock(at); }
            public List<Refinement.Change> options(Intent intent, Grown graph) {
                return intent instanceof Produce produce && Goods.members(produce.wanted()).contains(ModItems.FLOUR.get())
                    ? List.of(Refinement.Change.of(Grown.of(work))) : List.of();
            }
        };
        var stock = new Stock(List.of(new Stock.Holding(new Stash(at), new ItemStack(Items.WHEAT))),
            Map.of(new Stash(at), 27, new Stash(fixture.target), 27));
        var known = new Known(fixture.situation(stock, List.of(workshop)), fixture.crew.hands());
        // The fixture normally has hauling only. The actual native MillstoneWork also requires crafting.
        var original = fixture.crew.hands().getFirst();
        var actor = (ResidentEntity) fixture.worker.body();
        var hand = new Crew.Hand(original.who(), original.at(), 3, 3, 1,
            List.of(actor.licences().of(WaresContent.trade()).orElseThrow(),
                actor.licences().of(Vocations.required(Vocations.HAULING)).orElseThrow()),
            List.of(), Optional.empty());
        known = new Known(known.world(), List.of(hand));
        NodeSpec requested = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(fixture.target),
            Stances.at(fixture.target), Workload.Once.of(1)).needs(new Need(flour, 63, true)).done();
        Node consumer = new Node() {
            public NodeSpec spec() { return requested; }
            public Outcome commit(ServerLevel level, Worker who) { throw new AssertionError("only the real plan is read"); }
        };
        var solver = new Solving();
        try {
            var planned = solver.handle(known, List.of(new Message.Submitted(CookeryContent.ID, Grown.of(consumer))), 0);
            helper.assertTrue(planned.diagnosis().shortfalls().isEmpty() && planned.schedule().unassigned().isEmpty(),
                "64 possible flour plus bone and diamond fit exactly three cells without duplicating 63 promised flour: " + planned.diagnosis());
            var placed = planned.weave().vertices().get(work.id());
            helper.assertTrue(placed != null && planned.schedule().tours().values().stream()
                    .anyMatch(tour -> tour.nodes().contains(work.id()) && tour.nodes().contains(consumer.id())),
                "the complete native-work and consumer chain must be scheduled at the unchanged three-cell boundary");
            helper.assertValueEqual(carried(placed.plan(), flour), 64L,
                "the promised 63 plus one same-item chance bonus are budgeted exactly once");
            helper.assertValueEqual(carried(placed.plan(), bone), 1L,
                "an unrequested guaranteed side product still occupies its own real cell");
            helper.assertValueEqual(carried(placed.plan(), diamond), 1L,
                "a possible different-item bonus still occupies its own real cell");
        } finally { solver.closed(); fixture.worker.body().discard(); }
        helper.succeed();
    }

    private static long carried(Placement placement, io.github.izakyl.folkways.core.api.terms.ItemSpec spec) {
        return placement.carrying().stream().filter(amount -> amount.spec().equals(spec)).mapToLong(Amount::count).sum();
    }

    private static void run(GameTestHelper helper, KitchenStation station, Block block, List<ItemStack> ingredients, ItemStack result, int loads) {
        var fixture = new PlanningFixture(helper, 36); ServerLevel level = helper.getLevel(); UUID colony = UUID.randomUUID();
        BlockPos cell = helper.absolutePos(new BlockPos(3, 1, 3)); WorldPos at = WorldPos.of(level, cell);
        WorldPos footing = WorldPos.of(level, station instanceof SpitStation || station instanceof SteamerStation || station instanceof TeapotStation ? cell.south() : cell.west(2)); KitchenBookings bookings = new KitchenBookings();
        for (int x = -4; x <= 4; x++) { for (int z = -4; z <= 4; z++) {
            level.setBlockAndUpdate(cell.offset(x, -1, z), Blocks.STONE.defaultBlockState());
        } }
        level.setBlockAndUpdate(cell.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
        var state = block.defaultBlockState();
        if (station instanceof SpitStation || station instanceof SteamerStation) {
            state = state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING,
                station instanceof SteamerStation ? net.minecraft.core.Direction.EAST : net.minecraft.core.Direction.SOUTH);
        }
        level.setBlockAndUpdate(cell, state);
        block.setPlacedBy(level, cell, level.getBlockState(cell), null, new ItemStack(block));
        if (station instanceof SpitStation) {
            level.setBlockAndUpdate(cell.east(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(cell.east().above(), Blocks.REDSTONE_BLOCK.defaultBlockState());
        }
        level.setBlockAndUpdate(fixture.source.block(level), Blocks.BARREL.defaultBlockState());
        level.setBlockAndUpdate(fixture.target.block(level), Blocks.BARREL.defaultBlockState());
        Container store = Containers.at(level, fixture.source.block(level)).orElseThrow();
        List<Stock.Holding> holdings = new ArrayList<>();
        for (int index = 0; index < ingredients.size(); index++) {
            store.setItem(index, ingredients.get(index).copy()); holdings.add(new Stock.Holding(new Stash(fixture.source), ingredients.get(index).copy()));
        }
        ResidentEntity actor = PersonBody.RESIDENT.get().create(level); actor.setPos(Vec3.atBottomCenterOf(footing.block(level)));
        actor.setOnGround(true); actor.setNoAi(!(station instanceof MillstoneStation)); level.addFreshEntity(actor);
        SimpleContainer pack = new SimpleContainer(36); pack.setItem(35, new ItemStack(ModItems.IRON_KITCHEN_KNIFE.get()));
        var workshop = station.freeze(UUID.randomUUID(), colony, level, at, Stances.at(footing), bookings, () -> {}).orElseThrow();
        var source = new Stash(fixture.source); var target = new Stash(fixture.target);
        var stands = Stands.building().container(source, Set.of(new Stand(footing)))
            .container(target, Set.of(new Stand(fixture.target))).done();
        var hand = new Crew.Hand(actor.resident(), footing, 35, 36, 1,
            List.of(actor.licences().of(WaresContent.trade()).orElseThrow(), actor.licences().of(Vocations.required(Vocations.HAULING)).orElseThrow()),
            List.of(pack.getItem(35)), Optional.empty());
        var known = new Known(new Situation(fixture.ways, new Stock(holdings, Map.of(source, 27, target, 27)), List.of(workshop), stands), List.of(hand));
        NodeSpec wanted = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(fixture.target),
            Stances.at(fixture.target), Workload.Once.of(1)).needs(new Need(Goods.specOf(result), result.getCount(), true)).done();
        Node consumer = new Node() {
            public NodeSpec spec() { return wanted; }
            public Outcome commit(ServerLevel ignored, Worker who) { return Outcome.done(); }
        };
        var solver = new Solving();
        try {
            var planned = solver.handle(known, List.of(new Message.Submitted(CookeryContent.ID, Grown.of(consumer))), 0);
            helper.assertTrue(planned.diagnosis().shortfalls().isEmpty() && planned.schedule().unassigned().isEmpty(),
                block + ": real production and all its fetching can be scheduled: " + planned.diagnosis());
            var tour = planned.schedule().tours().values().iterator().next(); Node work = null; Placement placement = Placement.NOWHERE; int fetches = 0;
            for (UUID id : tour.nodes()) {
                var vertex = planned.weave().vertices().get(id); Node node = vertex.node();
                if (node.spec().workload() instanceof Workload.Within) { work = node; placement = vertex.plan(); break; }
                helper.assertTrue(node instanceof TransferNode, block + ": work starts only after real source transfers");
                Worker who = worker(actor, pack, fixture.source, store, vertex.plan());
                helper.assertTrue(Commit.run(level, node, who) instanceof Outcome.Done, block + ": the actual planned transfer lifts raw materials into the real pack");
                node.ended(level, Ending.DONE); fetches++;
            }
            helper.assertTrue(work != null && fetches > 0, block + ": a nearby source still needs a physical fetch before entering the native workload");
            if (station instanceof MillstoneStation || station instanceof SpitStation || station instanceof SteamerStation) {
                long promised = placement.carrying().stream().filter(amount -> amount.spec().equals(Goods.specOf(result)))
                    .mapToLong(Amount::count).sum();
                helper.assertValueEqual(promised, (long) result.getCount(),
                    block + ": the real planner carries the native main product once, without a second leaves copy");
            }
            helper.assertTrue(placement.draws().stream().allMatch(draw -> draw.from().isEmpty()), block + ": native child inputs are reserved in the pack");
            helper.assertTrue(store.isEmpty(), block + ": the actual source no longer retains an unspent duplicate of any batch ingredient");
            for (ItemStack input : ingredients) { helper.assertValueEqual(Goods.countIn(pack, Goods.specOf(input)), (long) input.getCount(), block + ": every actual ingredient was fetched once"); }
            Worker who = worker(actor, pack, fixture.source, store, placement); Workload.Within within = (Workload.Within) work.spec().workload();
            for (int index = 0; index < loads; index++) {
                var turn = within.next(level, who); helper.assertTrue(turn instanceof Workload.Within.Turn.Piece, block + ": actual native loading is available after fetching");
                Node piece = ((Workload.Within.Turn.Piece) turn).node(); Outcome outcome = Commit.run(level, piece, who);
                helper.assertTrue(outcome instanceof Outcome.Done, block + ": the original native API accepts the fetched ingredients: " + outcome);
                ((Outcome.Done) outcome).made().forEach(stack -> helper.assertTrue(Containers.insert(pack, stack).isEmpty(), "actual returned containers fit"));
                within.ended(level, piece, Ending.DONE);
            }
            var nativeBlock = level.getBlockEntity(cell);
            if (nativeBlock instanceof MillstoneBlockEntity mill) { helper.assertValueEqual(mill.getInput().getCount(), 3, "three fetched wheat enter the actual mill"); }
            else if (nativeBlock instanceof ShawarmaSpitBlockEntity spit) { helper.assertValueEqual(spit.cookingItem.getCount(), 2, "two fetched meats enter the actual spit"); }
            else if (nativeBlock instanceof SteamerBlockEntity steamer) { helper.assertValueEqual(steamer.getItems().stream().filter(stack -> !stack.isEmpty()).count(), 2L, "two fetched doughs enter real steamer slots"); }
            else if (nativeBlock instanceof BambooTrayBlockEntity tray) { helper.assertValueEqual(tray.getItem(0).getCount(), 2, "two fetched leaves enter the actual tray slot"); }
            else if (nativeBlock instanceof TeapotBlockEntity tea) {
                helper.assertTrue(tea.getInput().is(ModItems.TIEGUANYIN_TEA_BAG.get()), "the actual fetched tea bag enters the native teapot");
                helper.assertValueEqual(Goods.countIn(pack, Goods.specOf(new ItemStack(ModItems.EMPTY_CUP.get()))), 12L, "the twelve real fetched serving cups remain reserved for later serving");
                helper.assertValueEqual(Goods.countIn(pack, Goods.specOf(new ItemStack(Items.BUCKET))), 1L, "native fluid loading returns exactly one empty bucket");
            } else { helper.fail("native appliance is missing after real loading"); }
            helper.assertTrue(level.getEntitiesOfClass(ItemEntity.class, new AABB(cell).inflate(5)).isEmpty(), "loading has no duplicate physical drops");
        } finally { solver.closed(); station.release(colony, level, at); station.stop(colony, io.github.izakyl.folkways.core.api.colony.Closing.RAZED); actor.discard(); fixture.worker.body().discard(); }
        helper.succeed();
    }

    private static Worker worker(ResidentEntity actor, SimpleContainer pack, WorldPos source, Container store, Placement placement) {
        return new Worker() {
            public Resident resident() { return actor.resident(); }
            public Mob body() { return actor; }
            public Container pack() { return pack; }
            public List<Store> within() { return List.of(new Store(source, store)); }
            public ItemStack held(ToolNeed need) { for (ItemStack stack : pack.getItems()) { if (Tools.answers(stack, need)) { return stack; } } return ItemStack.EMPTY; }
            public void spill(ItemStack stack) { if (!stack.isEmpty()) { throw new IllegalStateException("unexpected spill: " + stack); } }
            public Effects effects() { return Effects.NONE; }
            public List<Node> route() { return List.of(); }
            public Placement placement() { return placement; }
        };
    }
}