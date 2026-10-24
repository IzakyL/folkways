package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.core.api.terms.Goods;
import io.github.izakyl.folkways.core.api.terms.ItemSpec;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.engine.labor.Commit;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.front.api.PastDay;
import io.github.izakyl.folkways.resources.ResourceItems;
import java.util.List;
import java.util.Optional;
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

@EventBusSubscriber(modid = io.github.izakyl.folkways.plugins.FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder("folkways")
@PrefixGameTestTemplate(false)
public final class ResourcePlantingGameTests {
    @SubscribeEvent public static void register(RegisterGameTestsEvent event) { event.register(ResourcePlantingGameTests.class); }

    @GameTest(template = "empty_large")
    public static void resourcesPlantTheChosenSeedsAndSaplings(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var level = helper.getLevel();
        var crops = List.of(Blocks.WHEAT, Blocks.BEETROOTS, Blocks.BIRCH_SAPLING, Blocks.SPRUCE_SAPLING);
        f.worker.pack().setItem(0, new ItemStack(ResourceItems.SEED.get(), 2));
        f.worker.pack().setItem(1, new ItemStack(ResourceItems.SAPLING.get(), 2));
        int index = 0;
        for (var block : crops) {
            BlockPos ground = helper.absolutePos(new BlockPos(8 + index++ * 3, 2, 8));
            Crop crop = Crop.of(level, ground, net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block)).orElseThrow();
            level.setBlock(ground, (crop.needsFarmland() ? Blocks.FARMLAND : Blocks.DIRT).defaultBlockState(), 3);
            level.setBlock(ground.above(), Blocks.AIR.defaultBlockState(), 3);
            Job job = new Job(WorldPos.of(level, ground), List.of(ground), Stances.WHEREVER, ground);
            FieldChores tending = new FieldChores("plant", PlantNode::new, () -> { }, new PastDay());
            Node node = new PlantNode(new FarmNode.Chore(UUID.randomUUID(), crop, job, tending));
            var done = Commit.run(level, node, f.worker);
            helper.assertTrue(done instanceof Outcome.Done && level.getBlockState(ground.above()).is(block), "the selected crop is planted: " + block);
            var receipt = ((Outcome.Done) done).inputs().getFirst();
            helper.assertTrue(receipt.paid().is(crop.needsFarmland() ? ResourceItems.SEED.get() : ResourceItems.SAPLING.get()),
                "planting records actual resource payment");
            level.setBlock(ground.above(), Blocks.AIR.defaultBlockState(), 3);
        }
        helper.assertTrue(f.worker.pack().isEmpty(), "each planting consumes one resource");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void blockItemCropBehaviorReceivesThePlannedSeed(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(8, 2, 8));
        level.setBlock(at, Blocks.FARMLAND.defaultBlockState(), 3);
        level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), 3);
        Crop original = Crop.of(level, at, ResourceLocation.withDefaultNamespace("wheat")).orElseThrow();
        CropBehavior behavior = new CropBehavior() {
            public net.minecraft.world.level.block.Block block() { return Blocks.WHEAT; }
            public List<net.minecraft.world.item.Item> seeds() { return List.of(Items.WHEAT_SEEDS); }
            public boolean needsFarmland() { return true; }
            public boolean ripe(net.minecraft.world.level.LevelReader world, BlockPos base) { return false; }
        };
        Crop crop = new Crop(original.id(), original.block(), original.standing(), original.seed(), original.seedSpec(),
            original.substrate(), original.habit(), Optional.of(behavior), Optional.empty());
        Job job = new Job(WorldPos.of(level, at), List.of(at), Stances.WHEREVER, at);
        FieldChores tending = new FieldChores("plant", PlantNode::new, () -> { }, new PastDay());
        f.worker.pack().setItem(0, new ItemStack(ResourceItems.SEED.get()));
        Node node = new PlantNode(new FarmNode.Chore(UUID.randomUUID(), crop, job, tending));
        helper.assertTrue(Commit.run(level, node, f.worker) instanceof Outcome.Done && level.getBlockState(at.above()).is(Blocks.WHEAT),
            "BlockItem placement uses wheat seed while payment is a seed resource");
        helper.assertTrue(f.worker.pack().isEmpty(), "the seed resource was consumed directly");
        helper.succeed();
    }

    @GameTest(template = "empty_large")
    public static void fourSaplingsConsumeFourResourcesAndCanceledFieldsKeepThem(GameTestHelper helper) {
        var f = new PlanningFixture(helper);
        var level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(8, 2, 8));
        Crop crop = Crop.of(level, at, ResourceLocation.withDefaultNamespace("dark_oak_sapling")).orElseThrow();
        List<BlockPos> cells = List.of(at, at.east(), at.south(), at.south().east());
        for (BlockPos cell : cells) {
            level.setBlock(cell, Blocks.DIRT.defaultBlockState(), 3);
            level.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), 3);
        }
        FieldChores tending = new FieldChores("plant", PlantNode::new, () -> { }, new PastDay());
        Job job = new Job(WorldPos.of(level, at), cells, Stances.WHEREVER, at);
        f.worker.pack().setItem(0, new ItemStack(ResourceItems.SAPLING.get(), 4));
        Node node = new PlantNode(new FarmNode.Chore(UUID.randomUUID(), crop, job, tending));
        helper.assertTrue(Commit.run(level, node, f.worker) instanceof Outcome.Done, "a four-sapling stand can be sown");
        helper.assertTrue(f.worker.pack().isEmpty() && cells.stream().allMatch(cell -> level.getBlockState(cell.above()).is(Blocks.DARK_OAK_SAPLING)),
            "all four planned saplings cost four resources");
        cells.forEach(cell -> level.setBlock(cell.above(), Blocks.AIR.defaultBlockState(), 3));
        f.worker.pack().setItem(0, new ItemStack(ResourceItems.SAPLING.get(), 4));
        tending.close();
        helper.assertTrue(Commit.run(level, node, f.worker) instanceof Outcome.Failed, "a canceled field refuses the commit");
        helper.assertValueEqual(Goods.countIn(f.worker.pack(), ItemSpec.of(ResourceItems.SAPLING.get())), 4L, "cancel returns the actual sapling resources");
        helper.succeed();
    }
}
