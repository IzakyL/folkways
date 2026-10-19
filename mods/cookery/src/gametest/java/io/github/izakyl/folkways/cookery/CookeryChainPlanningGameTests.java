package io.github.izakyl.folkways.cookery;

import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.MillstoneBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModBlocks;
import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemFilter;
import io.github.izakyl.folkways.core.api.terms.Stash;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.vocation.Vocations;
import io.github.izakyl.folkways.core.api.work.Grown;
import io.github.izakyl.folkways.core.api.work.Need;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.NodeSpec;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.WorkSite;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.core.api.work.Workload;
import io.github.izakyl.folkways.core.engine.colony.ColonyWorks;
import io.github.izakyl.folkways.core.engine.plan.Crew;
import io.github.izakyl.folkways.core.engine.plan.Known;
import io.github.izakyl.folkways.core.engine.plan.Message;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.plan.Solving;
import io.github.izakyl.folkways.core.engine.plan.Stock;
import io.github.izakyl.folkways.front.engine.colony.MemberToggle;
import io.github.izakyl.folkways.plugins.supply.SupplyBox;
import io.github.izakyl.folkways.plugins.supply.SupplyContent;
import io.github.izakyl.folkways.plugins.wares.WaresContent;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_cookery", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CookeryChainPlanningGameTests {
    private static final String K = "kaleidoscope_cookery:";
    private static final List<String> BASE = List.of("minecraft:wheat_seeds", K + "green_chili", K + "rice_panicle",
        "minecraft:brown_mushroom", "minecraft:porkchop", "minecraft:water_bucket", "minecraft:oak_planks", "minecraft:iron_ingot", "minecraft:brick");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) { event.register(CookeryChainPlanningGameTests.class); }

    @GameTestGenerator
    public static List<TestFunction> theWholePublishedCataloguePlansEveryStageOfTheSixIngredientFinalOrder() {
        return List.of("oil", "rice", "cooked_rice", "fish_flavored_shredded_pork", "fish_flavored_shredded_pork_rice_bowl")
            .stream().map(product -> new TestFunction("cookery_chain_planning", "folkways:cookery_chain_planning_" + product,
                "folkways:empty", 100, 0L, true, helper -> plan(helper, product))).toList();
    }

    private static void plan(GameTestHelper helper, String product) {
        ServerLevel level = helper.getLevel();
        Colony colony = Colonies.mint(level.getServer());
        PlanningFixture fixture = new PlanningFixture(helper, 36);
        Solving solver = new Solving();
        try {
            for (BlockPos cell : BlockPos.betweenClosed(helper.absolutePos(BlockPos.ZERO), helper.absolutePos(new BlockPos(6, 0, 6))))
                level.setBlockAndUpdate(cell, Blocks.STONE.defaultBlockState());
            var source = helper.absolutePos(new BlockPos(0, 1, 0));
            var table = helper.absolutePos(new BlockPos(5, 1, 1));
            var board = helper.absolutePos(new BlockPos(2, 1, 1));
            var mill = helper.absolutePos(new BlockPos(2, 1, 4));
            var pot = helper.absolutePos(new BlockPos(5, 2, 3));
            var stockpot = helper.absolutePos(new BlockPos(5, 2, 5));
            var store = helper.absolutePos(new BlockPos(0, 1, 6));
            level.setBlockAndUpdate(source, SupplyContent.BLOCK.get().defaultBlockState());
            ((SupplyBox) level.getBlockEntity(source)).configure(false,
                BASE.stream().map(id -> ItemFilter.item(ResourceLocation.parse(id))).toList());
            level.setBlockAndUpdate(table, Blocks.CRAFTING_TABLE.defaultBlockState());
            level.setBlockAndUpdate(board, ModBlocks.CHOPPING_BOARD.get().defaultBlockState());
            for (var part : MillstoneBlock.PART.getPossibleValues())
                level.setBlockAndUpdate(mill.offset(part.getPosX(), 0, part.getPosY()),
                    ModBlocks.MILLSTONE.get().defaultBlockState().setValue(MillstoneBlock.PART, part));
            for (var at : List.of(pot, stockpot)) level.setBlockAndUpdate(at.below(), Blocks.MAGMA_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(pot, ModBlocks.POT.get().defaultBlockState());
            level.setBlockAndUpdate(stockpot, ModBlocks.STOCKPOT.get().defaultBlockState());
            level.setBlockAndUpdate(store, Blocks.CHEST.defaultBlockState());
            var player = FakePlayerFactory.getMinecraft(level);
            for (var at : List.of(source, table, board, mill, pot, stockpot, store))
                helper.assertTrue(MemberToggle.toggleAt(level, colony, at, player).refusal().isEmpty(),
                    "real book membership accepts the source, workstations and store: " + at);
            refresh(colony, SupplyContent.ID, "io.github.izakyl.folkways.plugins.supply.SupplyPresence", "tick", level.getServer());
            refresh(colony, WaresContent.ID, "io.github.izakyl.folkways.plugins.wares.WaresPresence", "refresh", level.getServer());
            colony.service(CookeryContent.ID, CookeryPresence.class).orElseThrow().refresh(level.getServer());
            var catalogue = ((ColonyWorks) colony).workshopsIn(level.dimension());
            helper.assertValueEqual(catalogue.size(), 6, "all six actual published workshops remain in the cost catalogue");
            var body = (io.github.izakyl.folkways.plugins.person.ResidentEntity) fixture.worker.body();
            var crafting = body.licences().of(WaresContent.trade()).orElseThrow();
            var hauling = body.licences().of(Vocations.required(Vocations.HAULING)).orElseThrow();
            var hand = new Crew.Hand(body.resident(), fixture.source, 36, 36, 1,
                List.of(crafting, hauling), List.of(), Optional.empty());
            var stock = new Stock(List.of(), Map.of(new Stash(fixture.source), 27, new Stash(fixture.target), 27));
            var wanted = Goods.specOf(new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(K + product))));
            NodeSpec spec = NodeSpec.of(UUID.randomUUID(), CookeryContent.ID, new WorkSite.AtBlock(fixture.target),
                Stances.at(fixture.target), Workload.Once.of(1)).needs(new Need(wanted, 1)).done();
            Node consumer = new Node() {
                public NodeSpec spec() { return spec; }
                public Outcome commit(ServerLevel nativeLevel, Worker who) { return Outcome.done(); }
            };
            var result = solver.handle(new Known(fixture.situation(stock, catalogue), List.of(hand)),
                List.of(new Message.Submitted(CookeryContent.ID, Grown.of(consumer))), 0);
            var needs = result.weave().vertices().values().stream().map(vertex -> vertex.node().spec().needs()).toList();
            helper.assertTrue(result.diagnosis().shortfalls().isEmpty(),
                "the full real catalogue can make " + product + " using only six raw foods and plank/iron/brick materials, without any intermediate order: "
                    + result.diagnosis() + "; actual planned needs=" + needs);
            helper.assertTrue(result.weave().vertices().size() > 1, "the planner expands actual executable intermediate production");
        } finally {
            solver.closed();
            colony.raze();
            fixture.worker.body().discard();
        }
        helper.succeed();
    }

    private static void refresh(Colony colony, ResourceLocation owner, String name, String method, MinecraftServer server) {
        try {
            Class<?> type = Class.forName(name);
            Object presence = colony.service(owner, type).orElseThrow();
            var refresh = type.getDeclaredMethod(method, MinecraftServer.class);
            refresh.setAccessible(true);
            refresh.invoke(presence, server);
        } catch (ReflectiveOperationException broken) {
            if (broken instanceof InvocationTargetException invocation && invocation.getCause() instanceof RuntimeException cause) throw cause;
            throw new AssertionError("the real colony service could not publish its native catalogue", broken);
        }
    }
}
