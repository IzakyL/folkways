package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SurveyGameTests {

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(SurveyGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void completedChoresStaySpentAcrossSweeps(GameTestHelper helper) {
        java.util.UUID zone = java.util.UUID.randomUUID();
        BlockPos cell = helper.absolutePos(new BlockPos(2, 1, 2));
        Job job = Job.at(cell, new io.github.izakyl.folkways.core.api.work.WorkSite.AtBlock(
            WorldPos.of(helper.getLevel(), cell)), io.github.izakyl.folkways.core.api.work.Stances.WHEREVER);
        Chores chores = new Chores(zone, "weed", (level, chore) -> {
            throw new AssertionError("completed work must never be offered again");
        }, () -> {}, new io.github.izakyl.folkways.front.api.PastDay());
        chores.settle(List.of(job));
        Chores.Chore chore = new Chores.Chore(java.util.UUID.randomUUID(), job, chores);
        chores.planned(chore);
        helper.assertTrue(chores.busy(), "planned work keeps the site busy");
        chores.ended(chore, io.github.izakyl.folkways.core.api.work.Ending.DONE);
        chores.settle(List.of(job));
        chores.goals(helper.getLevel(), new java.util.ArrayList<>());
        helper.assertTrue(!chores.busy() && chores.spent().contains(job.key()),
            "a later sweep preserves completion and releases busy work");
        helper.succeed();
    }

    // A weed two tall is pulled once, by its lower half.
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void aTallWeedIsPulledOnce(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 1, 2));
        floor(level, origin.offset(-2, -1, -2), origin.offset(4, -1, 4), Blocks.GRASS_BLOCK);
        BlockPos tall = origin;
        place(level, tall, Blocks.TALL_GRASS.defaultBlockState());
        place(level, tall.above(), Blocks.TALL_GRASS.defaultBlockState()
            .setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
        BlockPos flower = origin.offset(1, 0, 0);
        place(level, flower, Blocks.POPPY.defaultBlockState());
        Set<BlockPos> box = box(origin, origin.offset(1, 1, 0));

        Set<BlockPos> asked = cellsOf(new Survey(level, box).weed(Survey.weeds(true, false)));
        helper.assertTrue(asked.equals(Set.of(tall)), "the grass by its foot, and not the flower, got " + asked);
        helper.succeed();
    }

    // Flowers are pulled only when the zone says so, a tall one once by its lower half; then the grass may stay.
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void flowersArePulledOnlyWhenAsked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 1, 2));
        floor(level, origin.offset(-2, -1, -2), origin.offset(4, -1, 4), Blocks.GRASS_BLOCK);
        BlockPos grass = origin;
        place(level, grass, Blocks.SHORT_GRASS.defaultBlockState());
        BlockPos poppy = origin.offset(1, 0, 0);
        place(level, poppy, Blocks.POPPY.defaultBlockState());
        BlockPos sunflower = origin.offset(2, 0, 0);
        place(level, sunflower, Blocks.SUNFLOWER.defaultBlockState());
        place(level, sunflower.above(), Blocks.SUNFLOWER.defaultBlockState()
            .setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));
        Set<BlockPos> box = box(origin, origin.offset(2, 1, 0));

        Set<BlockPos> asked = cellsOf(new Survey(level, box).weed(Survey.weeds(false, true)));
        helper.assertTrue(asked.equals(Set.of(poppy, sunflower)), "the flowers by their foot and not the grass, got "
            + asked);
        asked = cellsOf(new Survey(level, box).weed(Survey.weeds(true, true)));
        helper.assertTrue(asked.equals(Set.of(grass, poppy, sunflower)), "the grass and the flowers, got " + asked);
        helper.succeed();
    }

    // Snow is no weed or flower: it stays when weeding.
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void snowIsLeftByWeeding(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 1, 2));
        floor(level, origin.offset(-2, -1, -2), origin.offset(4, -1, 4), Blocks.STONE);
        for (int layers = 1; layers <= 8; layers++) {
            BlockState snow = Blocks.SNOW.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers);
            place(level, origin, snow);
            helper.assertFalse(Survey.weeds(true, true).test(snow), "snow is not selected as a weed");
            helper.assertTrue(new Survey(level, Set.of(origin)).weed(Survey.weeds(true, true)).isEmpty(),
                "weeding leaves the snow, " + layers + " layers");
        }
        helper.succeed();
    }

    // A tree is felled whole from the foot of its trunk; a log with no leaves of its own is a beam and stays.
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void treesAreFelledAndBeamsAreNot(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(3, 1, 3));
        floor(level, origin.offset(-3, -1, -3), origin.offset(5, -1, 3), Blocks.DIRT);
        BlockPos foot = origin;
        for (int up = 0; up < 4; up++) {
            place(level, foot.above(up), Blocks.OAK_LOG.defaultBlockState());
        }
        place(level, foot.offset(1, 3, 0), Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(LeavesBlock.DISTANCE, 1).setValue(LeavesBlock.PERSISTENT, false));
        BlockPos beam = origin.offset(4, 0, 0);
        place(level, beam, Blocks.OAK_LOG.defaultBlockState());
        place(level, beam.above(), Blocks.OAK_LOG.defaultBlockState());

        Set<BlockPos> box = box(origin.offset(-1, 0, -1), origin.offset(5, 0, 1));
        List<Job> jobs = new Survey(level, box).fell();
        helper.assertTrue(jobs.size() == 1 && jobs.getFirst().cell().equals(foot),
            "one tree, by its foot, got " + cellsOf(jobs));
        helper.assertTrue(jobs.getFirst().cells().size() == 4, "the whole trunk: " + jobs.getFirst().cells());
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void whatLiesLooseInTheBoxIsHauled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 1, 2));
        floor(level, origin.offset(-2, -1, -2), origin.offset(4, -1, 4), Blocks.STONE);
        ItemEntity inside = new ItemEntity(level, origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5,
            new ItemStack(Items.COBBLESTONE, 3));
        ItemEntity outside = new ItemEntity(level, origin.getX() + 3.5, origin.getY(), origin.getZ() + 0.5,
            new ItemStack(Items.COBBLESTONE));
        inside.setDeltaMovement(0, 0, 0);
        outside.setDeltaMovement(0, 0, 0);
        level.addFreshEntity(inside);
        level.addFreshEntity(outside);
        try {
            Set<BlockPos> box = box(origin, origin.offset(1, 1, 1));
            List<Job> jobs = new Survey(level, box).haul(cell -> WorldPos.of(level, cell));
            helper.assertTrue(jobs.size() == 1 && jobs.getFirst().key().equals("item:" + inside.getUUID()),
                "the stack in the box and not the one beside it, got " + jobs);
        } finally {
            inside.discard();
            outside.discard();
        }
        helper.succeed();
    }

    private static Set<BlockPos> box(BlockPos min, BlockPos max) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        BlockPos.betweenClosed(min, max).forEach(cell -> cells.add(cell.immutable()));
        return cells;
    }

    private static void floor(ServerLevel level, BlockPos min, BlockPos max, Block block) {
        for (BlockPos cell : BlockPos.betweenClosed(min, max)) {
            place(level, cell, block.defaultBlockState());
            for (int up = 1; up <= 6; up++) {
                place(level, cell.above(up), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static void place(ServerLevel level, BlockPos at, BlockState state) {
        level.setBlock(at, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    private static Set<BlockPos> cellsOf(List<Job> jobs) {
        Set<BlockPos> cells = new LinkedHashSet<>();
        jobs.forEach(job -> cells.add(job.cell()));
        return cells;
    }
}
