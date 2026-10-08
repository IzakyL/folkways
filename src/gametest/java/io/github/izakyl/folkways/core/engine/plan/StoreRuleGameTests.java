package io.github.izakyl.folkways.core.engine.plan;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.Containers;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.Stand;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.StoreRule;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture.Owed;
import io.github.izakyl.folkways.core.engine.plan.haul.Haul;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
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
        Solved starved = solve(f, stands, onlyReserved, kitchen);
        helper.assertTrue(starved.weave().vertices().isEmpty() && !starved.diagnosis().shortfalls().isEmpty(),
            "the only bread sits in another bread reserve, so the kitchen reserve waits instead of draining it");

        Stock alsoLoose = new Stock(List.of(new Stock.Holding(pantry, new ItemStack(Items.BREAD, 10)),
                new Stock.Holding(cellar, new ItemStack(Items.BREAD, 10))),
            Map.of(kitchen, 27, pantry, 26, cellar, 26), reserves);
        Solved fed = solve(f, stands, alsoLoose, kitchen);
        helper.assertTrue(drawnFrom(fed).equals(Set.of(cellar)),
            "bread outside any reserve fills the kitchen reserve");

        Stock plainKitchen = new Stock(List.of(new Stock.Holding(pantry, new ItemStack(Items.BREAD, 10))),
            Map.of(kitchen, 27, pantry, 26, cellar, 27),
            Map.of(pantry, List.of(new StoreRule.Reserve(pantry.pos(), BREAD))));
        Solved drawn = solve(f, stands, plainKitchen, kitchen);
        helper.assertTrue(drawnFrom(drawn).equals(Set.of(pantry)),
            "a reserve still feeds a need that is not itself a reserve of the same goods");

        Stock otherGoods = new Stock(List.of(new Stock.Holding(pantry, new ItemStack(Items.BREAD, 10))),
            Map.of(kitchen, 27, pantry, 26, cellar, 27),
            Map.of(kitchen, List.of(new StoreRule.Reserve(kitchen.pos(), BREAD)),
                pantry, List.of(new StoreRule.Reserve(pantry.pos(), IRON))));
        Solved unrelated = solve(f, stands, otherGoods, kitchen);
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
        Solved refused = solve(f, f.stands, ironOnly, target);
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
    public static void aStoreIsWorkedOnlyThroughItsSlotsAndAdmission(GameTestHelper helper) {
        var level = helper.getLevel();
        BlockPos cell = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(cell, Blocks.BARREL.defaultBlockState());
        var barrel = Containers.at(level, cell).orElseThrow();
        WorldPos where = WorldPos.of(level, cell);
        UUID colony = UUID.randomUUID();
        long before = Stores.revision();
        try {
            Stores.ruled(colony, Map.of(where, List.of(new StoreRule.Slots(where, 2, 5),
                new StoreRule.Admit(where, true, List.of(IRON)))));
            helper.assertTrue(Stores.revision() != before, "new rules move the revision so stores are read again");
            var kept = Stores.at(level, where).orElseThrow();
            helper.assertTrue(kept.getContainerSize() == 3, "only slots 2 to 4 are the colony's");
            helper.assertTrue(Containers.insert(kept, new ItemStack(Items.BREAD, 5)).getCount() == 5,
                "bread is not put where only iron is taken");
            ItemStack left = Containers.insert(kept, new ItemStack(Items.IRON_INGOT, 200));
            helper.assertTrue(left.getCount() == 200 - 3 * 64, "iron fills the three open slots and no more");
            helper.assertTrue(barrel.getItem(0).isEmpty() && barrel.getItem(1).isEmpty()
                    && barrel.getItem(5).isEmpty() && Goods.countIn(barrel, IRON) == 3 * 64,
                "the slots outside the rule are left alone");
            barrel.setItem(0, new ItemStack(Items.BREAD, 7));
            helper.assertTrue(Goods.countIn(kept, BREAD) == 0,
                "what sits outside the colony's slots is not the colony's to take");
            long moved = Stores.revision();
            Stores.ruled(colony, Map.of(where, List.of(new StoreRule.Slots(where, 2, 5),
                new StoreRule.Admit(where, true, List.of(IRON)))));
            helper.assertTrue(Stores.revision() == moved, "the same rules again change nothing");
        } finally {
            Stores.ruled(colony, Map.of());
        }
        helper.assertTrue(Stores.at(level, where).orElseThrow().getContainerSize() == 27,
            "without rules the whole barrel is the colony's again");
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
            Solved solved = rounds.solve(f.situation(stock, List.of()), f.crew, owed.goals(), List.of(), 0);
            helper.assertTrue(solved.weave().vertices().isEmpty() && !solved.diagnosis().shortfalls().isEmpty(),
                "nobody carries a shulker box to a shulker box only to fail putting it in");
        } finally {
            rounds.closed();
        }
        helper.succeed();
    }

    private static Solved solve(PlanningFixture f, Stands stands, Stock stock, Stash into) {
        Owed owed = new Owed(new WorkSite.AtBlock(into.pos()), Stances.at(into.pos()), BREAD, 5);
        PlanningFixture.Rounds rounds = new PlanningFixture.Rounds();
        try {
            return rounds.solve(new Situation(f.ways, stock, List.of(), stands), f.crew,
                owed.goals(), List.of(), 0);
        } finally {
            rounds.closed();
        }
    }

    private static Set<Stash> drawnFrom(Solved solved) {
        Map<UUID, Vertex> vertices = new LinkedHashMap<>(solved.weave().vertices());
        List<Stash> from = new ArrayList<>();
        vertices.values().forEach(vertex -> from.addAll(vertex.plan().from()));
        return Set.copyOf(from);
    }
}
