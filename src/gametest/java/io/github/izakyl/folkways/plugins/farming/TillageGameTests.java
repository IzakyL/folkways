package io.github.izakyl.folkways.plugins.farming;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.api.work.Stances;
import io.github.izakyl.folkways.core.api.work.Worker;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class TillageGameTests {
    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(TillageGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void grassAndFernsAreClearedBeforeTilling(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(2, 1, 2));
        for (Block soil : List.of(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.DIRT_PATH)) {
            for (Block grass : List.of(Blocks.SHORT_GRASS, Blocks.TALL_GRASS, Blocks.FERN, Blocks.LARGE_FERN)) {
                place(level, ground, soil.defaultBlockState());
                place(level, ground.above(), grass.defaultBlockState());
                if (grass instanceof DoublePlantBlock) {
                    place(level, ground.above(2), grass.defaultBlockState()
                        .setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
                }
                Outcome outcome = till(level, ground, new ItemStack(Items.WOODEN_HOE));
                helper.assertTrue(outcome instanceof Outcome.Done done && done.xp().isPresent()
                    && done.noises().size() == 2, "clearing and tilling finish as one piece of work");
                helper.assertTrue(level.getBlockState(ground).is(Blocks.FARMLAND), "the soil is tilled");
                helper.assertTrue(level.getBlockState(ground.above()).isAir(), "the grass is removed");
                helper.assertTrue(level.getBlockState(ground.above(2)).isAir(), "no upper half is left behind");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void otherBlocksAndPlantsAreLeftAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(2, 1, 2));
        for (Block obstacle : List.of(Blocks.STONE, Blocks.WHEAT,
                Blocks.OAK_SAPLING, Blocks.WATER)) {
            place(level, ground, Blocks.DIRT.defaultBlockState());
            place(level, ground.above(), obstacle.defaultBlockState());
            helper.assertFalse(Tillage.canTill(level, ground, level.getBlockState(ground), Tillage.PLAIN_HOE,
                Tillage.Clearing.ALL), "a field must not plan to clear " + obstacle);
            till(level, ground, new ItemStack(Items.WOODEN_HOE));
            helper.assertTrue(level.getBlockState(ground).is(Blocks.DIRT), "blocked soil is left alone");
            helper.assertTrue(level.getBlockState(ground.above()).is(obstacle), "the obstacle is left alone");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void flowersAreClearedOnlyWhenThePlotAllowsIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(2, 1, 2));
        Tillage.Clearing grassOnly = new Tillage.Clearing(true, false);
        for (Block flower : List.of(Blocks.DANDELION, Blocks.OXEYE_DAISY, Blocks.SUNFLOWER)) {
            place(level, ground, Blocks.DIRT.defaultBlockState());
            placePlant(level, ground.above(), flower);
            helper.assertFalse(Tillage.canTill(level, ground, level.getBlockState(ground), Tillage.PLAIN_HOE,
                grassOnly), "a plot that keeps its flowers must not plan to clear " + flower);
            till(level, ground, new ItemStack(Items.WOODEN_HOE), grassOnly);
            helper.assertTrue(level.getBlockState(ground).is(Blocks.DIRT), "soil under a kept flower is left alone");
            helper.assertTrue(level.getBlockState(ground.above()).is(flower), "the kept flower stays");

            helper.assertTrue(Tillage.canTill(level, ground, level.getBlockState(ground), Tillage.PLAIN_HOE,
                Tillage.Clearing.ALL), "a plot that clears flowers plans to clear " + flower);
            till(level, ground, new ItemStack(Items.WOODEN_HOE), Tillage.Clearing.ALL);
            helper.assertTrue(level.getBlockState(ground).is(Blocks.FARMLAND), "the soil is tilled");
            helper.assertTrue(level.getBlockState(ground.above()).isAir(), "the flower is pulled up");
            helper.assertTrue(level.getBlockState(ground.above(2)).isAir(), "no upper half is left behind");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void grassIsKeptWhenThePlotSaysSo(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(2, 1, 2));
        Tillage.Clearing flowersOnly = new Tillage.Clearing(false, true);
        place(level, ground, Blocks.DIRT.defaultBlockState());
        place(level, ground.above(), Blocks.SHORT_GRASS.defaultBlockState());
        helper.assertFalse(Tillage.canTill(level, ground, level.getBlockState(ground), Tillage.PLAIN_HOE,
            flowersOnly), "a plot that keeps its grass must not plan to clear it");
        till(level, ground, new ItemStack(Items.WOODEN_HOE), flowersOnly);
        helper.assertTrue(level.getBlockState(ground).is(Blocks.DIRT), "soil under kept grass is left alone");
        helper.assertTrue(level.getBlockState(ground.above()).is(Blocks.SHORT_GRASS), "the kept grass stays");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void grassOverUntillableSoilOrWithoutAHoeIsLeftAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(2, 1, 2));
        for (Block soil : List.of(Blocks.STONE, Blocks.FARMLAND, Blocks.COARSE_DIRT)) {
            place(level, ground, soil.defaultBlockState());
            place(level, ground.above(), Blocks.SHORT_GRASS.defaultBlockState());
            till(level, ground, new ItemStack(Items.WOODEN_HOE));
            helper.assertTrue(level.getBlockState(ground).is(soil), "only soil that becomes farmland is worked");
            helper.assertTrue(level.getBlockState(ground.above()).is(Blocks.SHORT_GRASS),
                "grass over untillable soil is left alone");
        }
        place(level, ground, Blocks.DIRT.defaultBlockState());
        till(level, ground, ItemStack.EMPTY);
        helper.assertTrue(level.getBlockState(ground).is(Blocks.DIRT), "tilling still requires a hoe");
        helper.assertTrue(level.getBlockState(ground.above()).is(Blocks.SHORT_GRASS),
            "a worker without a hoe does not clear the grass");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void anObstructionAddedAfterPlanningIsLeftAlone(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(2, 1, 2));
        place(level, ground, Blocks.DIRT.defaultBlockState());
        place(level, ground.above(), Blocks.SHORT_GRASS.defaultBlockState());
        helper.assertTrue(Tillage.canTill(level, ground, level.getBlockState(ground), Tillage.PLAIN_HOE,
            Tillage.Clearing.ALL), "the grass-covered cell starts out tillable");
        place(level, ground.above(), Blocks.STONE.defaultBlockState());
        till(level, ground, new ItemStack(Items.WOODEN_HOE));
        helper.assertTrue(level.getBlockState(ground).is(Blocks.DIRT), "work checks the soil again");
        helper.assertTrue(level.getBlockState(ground.above()).is(Blocks.STONE), "the new obstruction survives");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20)
    public static void bareSoilIsStillTilled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(2, 1, 2));
        place(level, ground, Blocks.DIRT.defaultBlockState());
        place(level, ground.above(), Blocks.AIR.defaultBlockState());
        Outcome outcome = till(level, ground, new ItemStack(Items.WOODEN_HOE));
        helper.assertTrue(level.getBlockState(ground).is(Blocks.FARMLAND), "bare soil is still tilled");
        helper.assertTrue(outcome instanceof Outcome.Done done && done.noises().size() == 1,
            "bare soil only makes the tilling noise");
        helper.succeed();
    }

    private static Outcome till(ServerLevel level, BlockPos ground, ItemStack hoe) {
        return till(level, ground, hoe, Tillage.Clearing.ALL);
    }

    private static Outcome till(ServerLevel level, BlockPos ground, ItemStack hoe, Tillage.Clearing clearing) {
        var body = PersonBody.RESIDENT.get().create(level);
        Worker worker = (Worker) Proxy.newProxyInstance(Worker.class.getClassLoader(),
            new Class<?>[] {Worker.class}, (proxy, method, args) -> switch (method.getName()) {
                case "held" -> hoe;
                case "body" -> body.mob();
                default -> throw new AssertionError(method.getName());
            });
        Crop crop = Crop.of(level, ground, ResourceLocation.withDefaultNamespace("wheat")).orElseThrow();
        Job job = new Job(WorldPos.of(level, ground), List.of(ground), Stances.WHEREVER, ground);
        TillNode node = new TillNode(new FarmNode.Chore(UUID.randomUUID(), crop, job, null), clearing);
        return node.work(level, worker, job);
    }

    private static void placePlant(ServerLevel level, BlockPos at, Block plant) {
        place(level, at, plant.defaultBlockState());
        if (plant instanceof DoublePlantBlock) {
            place(level, at.above(), plant.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
        }
    }

    private static void place(ServerLevel level, BlockPos at, BlockState state) {
        level.setBlock(at, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }
}
