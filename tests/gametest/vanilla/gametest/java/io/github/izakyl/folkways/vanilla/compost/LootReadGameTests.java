package io.github.izakyl.folkways.vanilla.compost;

import io.github.izakyl.folkways.core.colony.api.Claimed;
import io.github.izakyl.folkways.core.colony.api.Colonies;
import io.github.izakyl.folkways.core.front.api.Fronts;
import io.github.izakyl.folkways.core.front.engine.Enrollments;
import io.github.izakyl.folkways.core.front.engine.colony.ColonyStocks;
import io.github.izakyl.folkways.core.front.engine.colony.HeldFacilities;
import io.github.izakyl.folkways.core.item.api.Containers;
import io.github.izakyl.folkways.core.item.api.Goods;
import io.github.izakyl.folkways.core.testing.Purity;
import io.github.izakyl.folkways.core.world.api.WorldPos;
import io.github.izakyl.folkways.vanilla.wares.WaresContent;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = "folkways_vanilla", bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways_vanilla")
@PrefixGameTestTemplate(false)
public final class LootReadGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(LootReadGameTests.class);
    }

    @GameTest(template = "empty", templateNamespace = "folkways_core")
    public static void unopenedStoreStaysUnknownOnInventoryReads(GameTestHelper helper) {
        var level = helper.getLevel();
        var colony = Colonies.mint(level.getServer());
        BlockPos at = helper.absolutePos(new BlockPos(1, 1, 1));
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        var chest = (ChestBlockEntity) level.getBlockEntity(at);
        chest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 42L);
        var where = WorldPos.of(level, at);
        var claim = colony.hold(WaresContent.STORE, new Claimed.Block(where)).orElseThrow();
        colony.stores(WaresContent.ID, level.dimension(), List.of(where));
        Fronts.of(colony);
        var spec = Goods.specOf(Items.WHEAT_SEEDS.getDefaultInstance());
        try {
            Purity.assertPure(helper, "unopened stock", () -> {
                helper.assertValueEqual(Goods.countIn(chest, spec), 0L, "unopened stock is uncounted");
                helper.assertTrue(!Containers.known(new CompoundContainer(new SimpleContainer(27), chest)),
                    "double chests preserve unknown contents");
                ColonyStocks.tally(colony, level.getServer());
                ColonyStocks.available(colony, level.getServer(), spec);
                helper.assertTrue(!new SeedSupport(colony, level.getServer()).snapshot().complete(),
                    "seed budgets report unknown stock");
                HeldFacilities.find(colony, claim.id()).orElseThrow().lines(level, Map.of());
                Enrollments.ghostSource(colony).get(WaresContent.ID).placards(colony.view(level));
            });
            helper.assertTrue(chest.getLootTable() != null && chest.getLootTableSeed() == 42L,
                "inventory reads retain the loot table and seed");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }
}
