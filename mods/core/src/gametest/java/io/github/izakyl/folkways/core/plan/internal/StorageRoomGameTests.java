package io.github.izakyl.folkways.core.plan.internal;

import io.github.izakyl.folkways.core.FolkwaysCoreMod;
import io.github.izakyl.folkways.core.inventory.api.Stash;
import io.github.izakyl.folkways.core.inventory.api.port.Room;
import io.github.izakyl.folkways.core.inventory.internal.Stands;
import io.github.izakyl.folkways.core.world.api.Stand;
import io.github.izakyl.folkways.core.work.internal.TransferNode;
import java.util.Set;
import io.github.izakyl.folkways.core.item.api.ItemSpec;
import io.github.izakyl.folkways.core.work.api.Stances;
import io.github.izakyl.folkways.core.work.api.WorkSite;
import java.util.List;
import java.util.Map;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysCoreMod.CORE_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysCoreMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class StorageRoomGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(StorageRoomGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void itemDependentPoolsShareBookingsAcrossAcceptedGoods(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        try {
            Stash where = Stash.at(f.target);
            ItemSpec bread = io.github.izakyl.folkways.core.item.api.Goods.specOf(Items.BREAD.getDefaultInstance());
            ItemSpec iron = io.github.izakyl.folkways.core.item.api.Goods.specOf(Items.IRON_INGOT.getDefaultInstance());
            Room room = new Room.Pool(100, 2, java.util.Optional.of(Map.of(bread, 40L, iron, 100L)));
            Stock stock = Stock.observed(List.of(), Map.of(where, room), Map.of(), Map.of());
            helper.assertTrue(stock.roomAt(where, bread) == 40 && stock.roomAt(where, iron) == 100
                    && stock.roomAt(where, ItemSpec.of(Items.DIAMOND)) == 0,
                "the captured room depends on the actual goods, with absent keys refused");
            helper.assertTrue(stock.roomAfter(where, bread, false, Map.of(iron, 30L), Map.of()) == 10
                    && stock.roomAfter(where, iron, false, Map.of(bread, 40L), Map.of()) == 60,
                "all puts consume both the shared pool and other keys' conservative ceilings");
            helper.assertTrue(stock.roomAfter(where, bread, false, Map.of(iron, 40L), Map.of(iron, 40L)) == 0,
                "a take does not invent item-dependent room in a different native cell");
        } finally {
            f.worker.body().discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void twoOrdersCannotClaimTheSameLastEmptySlot(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        Stash source = Stash.at(f.source);
        Stash into = Stash.at(f.target);
        ItemSpec bread = ItemSpec.of(Items.BREAD);
        ItemSpec iron = ItemSpec.of(Items.IRON_INGOT);
        Stock stock = new Stock(List.of(new Stock.Holding(source, new ItemStack(Items.BREAD, 8)),
            new Stock.Holding(source, new ItemStack(Items.IRON_INGOT, 8))), Map.of(into, 1));
        var first = new PlanningFixture.Owed(new WorkSite.AtBlock(f.target), Stances.at(f.target), bread, 8);
        var second = new PlanningFixture.Owed(new WorkSite.AtBlock(f.target), Stances.at(f.target), iron, 8);
        Claims claims = new Claims();
        Solving solver = new Solving(claims, new Cooldowns());
        try {
            solver.handle(new Known(f.situation(stock, List.of()), f.crew.hands()), List.of(
                new Message.Submitted(Weaver.OWNER, first.goals().getFirst()),
                new Message.Submitted(Weaver.OWNER, second.goals().getFirst())), 0);
            long kinds = claims.totals().keySet().stream().filter(resource -> resource instanceof Resource.Space space
                && space.where().equals(into)).count();
            helper.assertTrue(kinds == 1, "only one kind can reserve the last empty cell");
            helper.assertTrue(stock.roomAfter(into, iron, false, Map.of(bread, 1L), Map.of()) == 0
                && stock.roomAfter(into, bread, false, Map.of(bread, 1L), Map.of()) == 63,
                "one item occupies a shared cell while leaving same-kind top-up room");
        } finally {
            solver.closed();
            f.worker.body().discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void plannedTransfersUseOnlyTheirOperationStands(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        Stash source = Stash.at(f.source);
        Stash target = Stash.at(f.target);
        Stand taking = Stand.at(f.source);
        Stand putting = Stand.at(f.target);
        Stand wrong = Stand.at(f.source.at(f.source.cell().offset(0, 0, 10)));
        Stands stands = Stands.building().container(source, Set.of(taking), Set.of(wrong))
            .container(target, Set.of(wrong), Set.of(putting)).done();
        Stock stock = new Stock(List.of(new Stock.Holding(source, new ItemStack(Items.BREAD, 8))),
            Map.of(target, 1));
        var order = new PlanningFixture.Owed(new WorkSite.AtBlock(f.target), Stances.at(f.target),
            ItemSpec.of(Items.BREAD), 8);
        var rounds = new PlanningFixture.Rounds();
        try {
            var planned = rounds.solve(new Situation(f.ways, stock, List.of(), stands), f.crew,
                order.goals(), List.of(), 0);
            var tour = planned.schedule().tours().get(f.worker.resident().id());
            helper.assertTrue(tour != null && tour.nodes().size() == 2, "the complete transfer is scheduled");
            for (var id : tour.nodes()) {
                var vertex = planned.weave().vertices().get(id);
                boolean fetch = ((TransferNode) vertex.node()).fetching();
                helper.assertTrue(vertex.plan().stands().equals(Set.of(fetch ? taking : putting)),
                    "take and put each use only the matching access point's stance");
            }
        } finally {
            rounds.closed();
            f.worker.body().discard();
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void capacityModelsShareOnlyTheirOwnLimits(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        Stash where = Stash.at(f.target);
        ItemSpec bread = ItemSpec.of(Items.BREAD);
        ItemSpec iron = ItemSpec.of(Items.IRON_INGOT);
        try {
            Room.Slots keys = new Room.Slots(List.of(new Room.Cell(Items.BREAD.getDefaultInstance(), 100),
                new Room.Cell(Items.IRON_INGOT.getDefaultInstance(), 50)), 1, new Room.Cap(200, Long.MAX_VALUE));
            Stock drawers = Stock.observed(List.of(), Map.of(where, keys), Map.of(), Map.of());
            helper.assertTrue(drawers.roomAfter(where, iron, false, Map.of(bread, 100L), Map.of()) == 250,
                "bound cells do not consume another kind's room");
            helper.assertTrue(drawers.roomAfter(where, iron, false, Map.of(bread, 101L), Map.of()) == 50,
                "an open cell is shared across kinds");
            Stock pool = Stock.observed(List.of(new Stock.Holding(where, Items.BREAD.getDefaultInstance(),
                5_000_000_000L)), Map.of(where, new Room.Pool(1_000, 1)), Map.of(), Map.of());
            helper.assertTrue(pool.heldAt(where, bread) == 5_000_000_000L, "holdings preserve long counts");
            helper.assertTrue(pool.roomAfter(where, bread, false, Map.of(iron, 300L), Map.of()) == 700,
                "pool items are deducted across kinds");
            helper.assertTrue(pool.roomAfter(where, ItemSpec.of(Items.DIAMOND), false,
                Map.of(iron, 300L), Map.of()) == 0, "pool new-kind limits are deducted across reservations");
            helper.assertTrue(pool.roomAfter(where, iron, false, Map.of(iron, 300L), Map.of()) == 700,
                "the new kind already reserved may still use the remaining items");
            Room.Slots custom = new Room.Slots(List.of(new Room.Cell(Items.BREAD.getDefaultInstance(), 6),
                new Room.Cell(Items.BREAD.getDefaultInstance(), 7)), 1,
                new Room.Cap(64, 1, Map.of(Items.BREAD, 16L)));
            Stock slots = Stock.observed(List.of(), Map.of(where, custom), Map.of(), Map.of());
            helper.assertTrue(slots.roomAt(where, bread) == 29, "fragmented top-ups use the actual per-item slot cap");
        } finally {
            f.worker.body().discard();
        }
        helper.succeed();
    }
}
