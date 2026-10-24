package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture.Owed;
import io.github.izakyl.folkways.core.engine.plan.haul.Haul;
import io.github.izakyl.folkways.core.engine.plan.haul.TransferNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StoreRuleGameTests {

    private static final ItemSpec BREAD = ItemSpec.of(ResourceLocation.withDefaultNamespace("bread"));
    private static final ItemSpec IRON = ItemSpec.of(ResourceLocation.withDefaultNamespace("iron_ingot"));

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(StoreRuleGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aReserveIsNeverFilledFromAnotherReserveOfTheSameGoods(GameTestHelper helper) {
        PlanningFixture f = new PlanningFixture(helper);
        Stash kitchen = new Stash(f.target);
        Stash pantry = new Stash(f.source);
        Stash cellar = new Stash(f.source.at(f.source.cell().offset(0, 0, 3)));
        Stands stands = Stands.building()
            .container(kitchen, Set.of(new Stand(kitchen.pos())))
            .container(pantry, Set.of(new Stand(pantry.pos())))
            .container(cellar, Set.of(new Stand(cellar.pos()))).done();
        Map<Stash, List<StoreRule>> reserves = Map.of(
            kitchen, List.of(new StoreRule.Reserve(kitchen.pos(), BREAD)),
            pantry, List.of(new StoreRule.Reserve(pantry.pos(), BREAD)));

        Stock onlyReserved = new Stock(List.of(new Stock.Holding(pantry, new ItemStack(Items.BREAD, 10))),
            Map.of(kitchen, 27, pantry, 26, cellar, 27), reserves);
        Planned starved = solve(f, stands, onlyReserved, kitchen);
        helper.assertTrue(starved.weave().vertices().isEmpty() && !starved.diagnosis().shortfalls().isEmpty(),
            "the only bread sits in another bread reserve, so the kitchen reserve waits instead of draining it");

        Stock alsoLoose = new Stock(List.of(new Stock.Holding(pantry, new ItemStack(Items.BREAD, 10)),
                new Stock.Holding(cellar, new ItemStack(Items.BREAD, 10))),
            Map.of(kitchen, 27, pantry, 26, cellar, 26), reserves);
        Planned fed = solve(f, stands, alsoLoose, kitchen);
        helper.assertTrue(drawnFrom(fed).equals(Set.of(cellar)),
            "bread outside any reserve fills the kitchen reserve");

        Stock plainKitchen = new Stock(List.of(new Stock.Holding(pantry, new ItemStack(Items.BREAD, 10))),
            Map.of(kitchen, 27, pantry, 26, cellar, 27),
            Map.of(pantry, List.of(new StoreRule.Reserve(pantry.pos(), BREAD))));
        Planned drawn = solve(f, stands, plainKitchen, kitchen);
        helper.assertTrue(drawnFrom(drawn).equals(Set.of(pantry)),
            "a reserve still feeds a need that is not itself a reserve of the same goods");

        Stock otherGoods = new Stock(List.of(new Stock.Holding(pantry, new ItemStack(Items.BREAD, 10))),
            Map.of(kitchen, 27, pantry, 26, cellar, 27),
            Map.of(kitchen, List.of(new StoreRule.Reserve(kitchen.pos(), BREAD)),
                pantry, List.of(new StoreRule.Reserve(pantry.pos(), IRON))));
        Planned unrelated = solve(f, stands, otherGoods, kitchen);
        helper.assertTrue(drawnFrom(unrelated).equals(Set.of(pantry)),
            "a reserve of other goods is an ordinary source for bread");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aStoreTakesOnlyWhatItsRulesAdmit(GameTestHelper helper) {
        PlanningFixture f = new PlanningFixture(helper);
        Stash target = new Stash(f.target);
        Stash source = new Stash(f.source);
        List<Stock.Holding> held = List.of(new Stock.Holding(source, new ItemStack(Items.BREAD, 10)));
        Map<Stash, Integer> empty = Map.of(source, 26, target, 27);

        Stock ironOnly = new Stock(held, empty,
            Map.of(target, List.of(new StoreRule.Admit(target.pos(), true, List.of(IRON)))));
        helper.assertTrue(ironOnly.roomAt(target, BREAD) == 0 && ironOnly.roomAt(target, IRON) > 0,
            "a store that takes only iron has no room for bread");
        Planned refused = solve(f, f.stands, ironOnly, target);
        helper.assertTrue(refused.weave().vertices().isEmpty() && !refused.diagnosis().shortfalls().isEmpty(),
            "bread is not carried into a store that does not take it");

        Stock noBread = new Stock(held, empty,
            Map.of(target, List.of(new StoreRule.Admit(target.pos(), false, List.of(BREAD)))));
        helper.assertTrue(noBread.roomAt(target, BREAD) == 0 && noBread.roomAt(target, IRON) > 0,
            "a store that takes anything but bread still has room for iron");

        Stock open = new Stock(held, empty,
            Map.of(target, List.of(new StoreRule.Admit(target.pos(), true, List.of(BREAD)))));
        helper.assertTrue(drawnFrom(solve(f, f.stands, open, target)).equals(Set.of(source)),
            "bread goes into a store that takes it");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aStoreUsesAllSlotsAndHonorsAdmission(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, Blocks.BARREL.defaultBlockState());
        var barrel = Containers.at(level, cell).orElseThrow();
        WorldPos where = WorldPos.of(level, cell);
        UUID colony = UUID.randomUUID();
        long before = Stores.revision();
        try {
            Stores.ruled(colony, Map.of(where, List.of(new StoreRule.Admit(where, true, List.of(IRON)))));
            helper.assertTrue(Stores.revision() != before, "new rules move the revision so stores are read again");
            var kept = Stores.at(level, where).orElseThrow();
            helper.assertTrue(kept.getContainerSize() == 27, "all slots are accessible");
            helper.assertTrue(Containers.insert(kept, new ItemStack(Items.BREAD, 5)).getCount() == 5,
                "bread is not put where only iron is taken");
            ItemStack left = Containers.insert(kept, new ItemStack(Items.IRON_INGOT, 200));
            helper.assertTrue(left.isEmpty() && Goods.countIn(barrel, IRON) == 200,
                "admitted goods can use the full container");
            barrel.setItem(0, new ItemStack(Items.BREAD, 7));
            helper.assertTrue(Goods.countIn(kept, BREAD) == 7,
                "admission filters do not hide existing stock");
            long moved = Stores.revision();
            Stores.ruled(colony, Map.of(where, List.of(new StoreRule.Admit(where, true, List.of(IRON)))));
            helper.assertTrue(Stores.revision() == moved, "the same rules again change nothing");
        } finally {
            Stores.ruled(colony, Map.of());
        }
        helper.assertTrue(Stores.at(level, where).orElseThrow().getContainerSize() == 27,
            "without rules the whole barrel is the colony's again");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void slotRangesAndGeneralAccessRulesRemainIndependent(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, Blocks.BARREL.defaultBlockState());
        var barrel = Containers.at(level, cell).orElseThrow();
        barrel.setItem(0, new ItemStack(Items.BREAD, 7));
        WorldPos where = WorldPos.of(level, cell);
        UUID colony = UUID.randomUUID();
        List<StoreRule> rules = List.of(new StoreRule.Slots(where, 2, 4),
            new StoreRule.Access(where, false, false), new StoreRule.Prefer(where, IRON, 100),
            new StoreRule.Admit(where, true, List.of(IRON)));
        try {
            Stores.ruled(colony, Map.of(where, rules));
            var kept = Stores.at(level, where).orElseThrow();
            helper.assertTrue(kept.getContainerSize() == 2 && Goods.countIn(kept, BREAD) == 0,
                "general access and destination preferences do not discard slot restrictions");
            helper.assertTrue(!StoreRule.takes(Stores.rulesAt(where)) && !StoreRule.stores(Stores.rulesAt(where)),
                "slot restrictions and preferences do not discard general access permissions");
            helper.assertTrue(Containers.insert(kept, new ItemStack(Items.IRON_INGOT, 130)).getCount() == 2
                    && barrel.getItem(2).getCount() == 64 && barrel.getItem(3).getCount() == 64,
                "a directed write through the container view still uses only admitted, selected slots");
            helper.assertTrue(barrel.getItem(0).getCount() == 7 && barrel.getItem(4).isEmpty(),
                "the inaccessible slots remain untouched");
        } finally {
            Stores.ruled(colony, Map.of());
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aStoreHasNoRoomForWhatItsContainerRefuses(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos boxAt = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos barrelAt = helper.absolutePos(new BlockPos(3, 1, 1));
        level.setBlockAndUpdate(boxAt, Blocks.SHULKER_BOX.defaultBlockState());
        level.setBlockAndUpdate(barrelAt, Blocks.BARREL.defaultBlockState());
        var refused = Containers.refused(Containers.at(level, boxAt).orElseThrow());
        helper.assertTrue(refused.contains(Items.SHULKER_BOX) && refused.contains(Items.RED_SHULKER_BOX)
                && !refused.contains(Items.BREAD),
            "a shulker box refuses shulker boxes and nothing else");
        helper.assertTrue(Containers.refused(Containers.at(level, barrelAt).orElseThrow()).isEmpty(),
            "a barrel takes anything");
        var shulker = Containers.at(level, boxAt).orElseThrow();
        helper.assertTrue(Containers.insert(shulker, new ItemStack(Items.SHULKER_BOX)).getCount() == 1
                && shulker.isEmpty(),
            "a resident cannot put a shulker box inside another one");
        helper.assertTrue(Containers.insert(shulker, new ItemStack(Items.BREAD, 3)).isEmpty(),
            "but bread goes in");

        PlanningFixture f = new PlanningFixture(helper);
        Stash target = new Stash(f.target);
        Stash source = new Stash(f.source);
        ItemSpec box = ItemSpec.of(ResourceLocation.withDefaultNamespace("shulker_box"));
        Stock stock = new Stock(List.of(new Stock.Holding(source, new ItemStack(Items.SHULKER_BOX, 1))),
            Map.of(source, 26, target, 27), Map.of(), Map.of(target, refused));
        helper.assertTrue(stock.roomAt(target, box) == 0 && stock.roomAt(target, BREAD) > 0,
            "the plan sees no room for goods the container would not take");
        Owed owed = new Owed(new WorkSite.AtBlock(target.pos()), Stances.at(target.pos()), box, 1);
        PlanningFixture.Rounds rounds = new PlanningFixture.Rounds();
        try {
            Planned solved = rounds.solve(f.situation(stock, List.of()), f.crew, owed.goals(), List.of(), 0);
            helper.assertTrue(solved.weave().vertices().isEmpty() && !solved.diagnosis().shortfalls().isEmpty(),
                "nobody carries a shulker box to a shulker box only to fail putting it in");
        } finally {
            rounds.closed();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void takingAndGeneralStorageAreIndependentOfOrders(GameTestHelper helper) {
        PlanningFixture f = new PlanningFixture(helper);
        Stash source = new Stash(f.source);
        Stash target = new Stash(f.target);
        List<Stock.Holding> held = List.of(new Stock.Holding(source, new ItemStack(Items.BREAD, 10)));
        Map<Stash, Integer> empty = Map.of(source, 26, target, 27);
        Stock closedSource = new Stock(held, empty, Map.of(source,
            List.of(new StoreRule.Access(source.pos(), false, true))));
        helper.assertTrue(drawnFrom(solve(f, f.stands, closedSource, target)).isEmpty(),
            "a closed source cannot supply other work");
        helper.assertTrue(closedSource.heldAt(source, BREAD) == 10,
            "closed source inventory is still counted for its own orders");
        Stock orderedOnly = new Stock(held, empty, Map.of(target,
            List.of(new StoreRule.Access(target.pos(), false, false))));
        helper.assertTrue(drawnFrom(solve(f, f.stands, orderedOnly, target)).equals(Set.of(source)),
            "directed delivery works even when general storage is disabled");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aRelayMustAllowItsConsumerToTakeTheGoods(GameTestHelper helper) {
        PlanningFixture f = new PlanningFixture(helper);
        Stash source = new Stash(f.source);
        Stash nearby = new Stash(f.target);
        Node consumer = new Node() {
            private final NodeSpec spec = NodeSpec.of(UUID.randomUUID(), Haul.DOMAIN,
                new WorkSite.AtBlock(f.target), Stances.at(f.target), Workload.Once.of(1))
                .needs(new Need(BREAD, 4)).done();
            public NodeSpec spec() { return spec; }
            public Outcome commit(ServerLevel level, Worker who) { return Outcome.done(); }
        };
        for (boolean take : new boolean[] {true, false}) {
            Stock stock = new Stock(List.of(new Stock.Holding(source, new ItemStack(Items.BREAD, 4))),
                Map.of(source, 26, nearby, 27),
                Map.of(nearby, List.of(new StoreRule.Access(nearby.pos(), take, true))));
            PlanningFixture.Rounds rounds = new PlanningFixture.Rounds();
            try {
                Planned solved = rounds.solve(f.situation(stock, List.of()), f.crew,
                    List.of(Grown.of(consumer)), List.of(), 0);
                var vertices = solved.weave().vertices();
                helper.assertTrue(solved.diagnosis().shortfalls().isEmpty() && vertices.containsKey(consumer.id()),
                    "the consumer is fully supplied whether the nearby store permits taking or not");
                if (take) {
                    helper.assertTrue(vertices.size() == 3
                            && vertices.values().stream().anyMatch(vertex -> vertex.plan().into().contains(nearby))
                            && vertices.get(consumer.id()).plan().from().contains(nearby),
                        "an open nearby store relays the bread through a take and a put before consumption");
                } else {
                    helper.assertTrue(vertices.size() == 2 && vertices.values().stream().noneMatch(vertex ->
                            vertex.plan().into().contains(nearby) || vertex.plan().from().contains(nearby)),
                        "a store that forbids taking is never used as a relay; bread is fetched into the pack");
                    helper.assertTrue(vertices.get(consumer.id()).plan().draws().stream().anyMatch(draw ->
                            draw.from().isEmpty() && draw.count() == 4 && draw.spec().equals(Goods.specOf(new ItemStack(Items.BREAD)))),
                        "the consumer draws its bread from the carrier's pack instead of the closed relay");
                }
            } finally {
                rounds.closed();
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void inFlightTakingRechecksPermission(GameTestHelper helper) {
        PlanningFixture f = new PlanningFixture(helper);
        var level = helper.getLevel();
        level.setBlockAndUpdate(f.source.cell(), Blocks.BARREL.defaultBlockState());
        var barrel = Containers.at(level, f.source.cell()).orElseThrow();
        barrel.setItem(0, new ItemStack(Items.BREAD, 10));
        UUID colony = UUID.randomUUID();
        TransferNode take = TransferNode.at(TransferNode.Endpoint.store(new Stash(f.source)),
            TransferNode.Endpoint.pack(), new Manifest(List.of(new ItemStack(Items.BREAD, 4)), List.of()),
            Set.of(new Stand(f.source))).orElseThrow();
        try {
            Stores.ruled(colony, Map.of(f.source, List.of(new StoreRule.Access(f.source, false, true))));
            helper.assertTrue(take.draw(level, f.worker) instanceof Staged.Short,
                "taking permission is checked again when work executes");
            helper.assertTrue(Goods.countIn(barrel, BREAD) == 10, "refused taking leaves all stock in place");
            Stores.ruled(colony, Map.of(f.source, List.of(new StoreRule.Access(f.source, true, true))));
            helper.assertTrue(take.draw(level, f.worker) instanceof Staged.Taken,
                "taking can proceed after permission is restored");
            helper.assertTrue(Goods.countIn(barrel, BREAD) == 6, "the requested stock leaves");
        } finally {
            Stores.ruled(colony, Map.of());
        }
        helper.succeed();
    }

    private static Planned solve(PlanningFixture f, Stands stands, Stock stock, Stash into) {
        Owed owed = new Owed(new WorkSite.AtBlock(into.pos()), Stances.at(into.pos()), BREAD, 5);
        PlanningFixture.Rounds rounds = new PlanningFixture.Rounds();
        try {
            return rounds.solve(new Situation(f.ways, stock, List.of(), stands), f.crew,
                owed.goals(), List.of(), 0);
        } finally {
            rounds.closed();
        }
    }

    private static Set<Stash> drawnFrom(Planned solved) {
        Map<UUID, Vertex> vertices = new LinkedHashMap<>(solved.weave().vertices());
        List<Stash> from = new ArrayList<>();
        vertices.values().forEach(vertex -> from.addAll(vertex.plan().from()));
        return Set.copyOf(from);
    }
}
