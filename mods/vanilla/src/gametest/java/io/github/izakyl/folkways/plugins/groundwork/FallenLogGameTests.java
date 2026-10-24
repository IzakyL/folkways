package io.github.izakyl.folkways.plugins.groundwork;

import io.github.izakyl.folkways.FolkwaysMod;
import io.github.izakyl.folkways.core.api.colony.Colonies;
import io.github.izakyl.folkways.core.api.colony.Colony;
import io.github.izakyl.folkways.core.api.colony.Held;
import io.github.izakyl.folkways.core.api.colony.PointSites;
import io.github.izakyl.folkways.core.api.terms.WorldPos;
import io.github.izakyl.folkways.core.api.work.Node;
import io.github.izakyl.folkways.core.api.work.Outcome;
import io.github.izakyl.folkways.core.engine.colony.ColonyData;
import io.github.izakyl.folkways.core.engine.labor.ColonyLabor;
import io.github.izakyl.folkways.core.engine.plan.PlanningFixture;
import io.github.izakyl.folkways.core.engine.colony.ColonyWays;
import io.github.izakyl.folkways.front.engine.colony.ColonyFront;
import io.github.izakyl.folkways.front.engine.colony.ColonySettings;
import io.github.izakyl.folkways.plugins.FolkwaysVanillaMod;
import io.github.izakyl.folkways.plugins.person.PersonBody;
import io.github.izakyl.folkways.plugins.person.PersonContent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@EventBusSubscriber(modid = FolkwaysVanillaMod.ID, bus = EventBusSubscriber.Bus.MOD)
@GameTestHolder(FolkwaysMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class FallenLogGameTests {

    private static final ResourceLocation POINTS = ResourceLocation.fromNamespaceAndPath("folkways", "fallen_log_test");

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        PointSites.register(POINTS, (level, cell) -> FallenLogs.decoration(level.getBlockState(cell)));
        event.register(FallenLogGameTests.class);
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void fallenOakAndMossReachColonyStorage(GameTestHelper helper) throws ReflectiveOperationException {
        stored(helper, Blocks.OAK_LOG, false, 3);
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void taggedPillarNeedsNoMinecraftLogTag(GameTestHelper helper) throws ReflectiveOperationException {
        helper.assertFalse(Blocks.HAY_BLOCK.defaultBlockState().is(BlockTags.LOGS), "the hollow-log stand-in is tag-only");
        stored(helper, Blocks.HAY_BLOCK, false, 1);
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void clearingAlsoCollectsDecorationsBeforeTheirSupport(GameTestHelper helper)
            throws ReflectiveOperationException {
        stored(helper, Blocks.OAK_LOG, true, 1);
    }

    // GameTestServer suppresses Labor.work. Drive its usual colony pipeline with inline planning lanes.
    private static void stored(GameTestHelper helper, Block block, boolean clearing, int length)
            throws ReflectiveOperationException {
        ServerLevel level = helper.getLevel();
        BlockPos log = ground(helper);
        Set<BlockPos> marked = new HashSet<>();
        for (int x = 0; x < length; x++) {
            BlockPos cell = log.east(x);
            marked.add(cell);
            place(level, cell, horizontal(block));
            place(level, cell.above(), Blocks.MOSS_CARPET.defaultBlockState());
        }
        BlockPos chest = log.offset(3, 0, 0);
        place(level, chest, Blocks.BARREL.defaultBlockState());
        Container stock = (Container) level.getBlockEntity(chest);
        Colony colony = Colonies.mint(level.getServer());
        ColonyFront.of(colony).addZone(level, GroundworkContent.ZONE, marked, ColonySettings.empty()
            .with(GroundworkContent.CLEAR, new ColonySettings.Value.Flag(clearing))
            .with(GroundworkContent.FELL, new ColonySettings.Value.Flag(!clearing))).orElseThrow();
        colony.stores(GroundworkContent.ID, level.dimension(), List.of(WorldPos.of(level, chest)));
        var person = PersonBody.RESIDENT.get().create(level);
        person.moveTo(log.getX() + 2.5, log.getY(), log.getZ() + 1.5);
        colony.hold(PersonContent.ID, Held.Entity.of(person)).orElseThrow();
        person.joinColony(colony.id());
        level.addFreshEntity(person);
        colony.entered(person);
        var data = ColonyData.find(level.getServer(), colony.id()).orElseThrow();
        var labor = new ColonyLabor(data, level.dimension());
        Class<?> lanes = Class.forName("io.github.izakyl.folkways.core.engine.labor.Lanes");
        var field = lanes.getDeclaredField("INLINE");
        field.setAccessible(true);
        Object inline = field.get(null);
        var run = ColonyLabor.class.getMethod("run", ServerLevel.class, lanes);
        var stop = ColonyLabor.class.getDeclaredMethod("close");
        stop.setAccessible(true);
        Set<Long> forced = new HashSet<>();
        for (BlockPos corner : List.of(log.offset(-3, 0, -3), log.offset(4, 0, -3),
                log.offset(-3, 0, 3), log.offset(4, 0, 3))) {
            ChunkPos chunk = new ChunkPos(corner);
            if (!level.getForcedChunks().contains(chunk.toLong()) && level.setChunkForced(chunk.x, chunk.z, true)) {
                forced.add(chunk.toLong());
            }
        }
        boolean[] closed = {false};
        Runnable close = () -> {
            if (closed[0]) return;
            closed[0] = true;
            try { stop.invoke(labor); }
            catch (ReflectiveOperationException broken) { throw new IllegalStateException(broken); }
            person.discard();
            colony.raze();
            forced.forEach(chunk -> level.setChunkForced(ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false));
        };
        helper.onEachTick(() -> {
            if (closed[0]) return;
            colony.service(GroundworkContent.ID, GroundPresence.class).orElseThrow().tick(level.getServer());
            ColonyWays.advance(data, level);
            try { run.invoke(labor, level, inline); }
            catch (ReflectiveOperationException broken) { throw new IllegalStateException(broken); }
        });
        helper.runAtTickTime(790, close);
        helper.startSequence().thenWaitUntil(() -> {
            for (BlockPos cell : marked) {
                helper.assertTrue(level.getBlockState(cell).isAir(), "the marked log was cleared");
                helper.assertTrue(level.getBlockState(cell.above()).isAir(), "its moss was cleared");
            }
            helper.assertTrue(stock.countItem(block.asItem()) == length, "the logs reached colony storage");
            helper.assertTrue(stock.countItem(Items.MOSS_CARPET) == length, "moss loot survived the support update");
        }).thenExecute(close).thenSucceed();
    }

    @GameTest(template = "empty")
    public static void standingTreesAndTheirHorizontalBranchesAreNotDebris(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos trunk = ground(helper);
        for (int y = 0; y < 4; y++) place(level, trunk.above(y), Blocks.OAK_LOG.defaultBlockState());
        place(level, trunk.above(3).east(), Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(LeavesBlock.PERSISTENT, false).setValue(LeavesBlock.DISTANCE, 1));
        BlockPos branch = trunk.east();
        place(level, branch, horizontal(Blocks.OAK_LOG));
        helper.assertTrue(new Survey(level, Set.of(branch)).fallen(cell -> false, state -> false, Set.of()).isEmpty(),
            "a trunk outside the mark still protects its branch");
        List<Job> trees = new Survey(level, Set.of(trunk)).fell();
        helper.assertTrue(trees.size() == 1 && trees.getFirst().cells().contains(branch), "standing tree felling is unchanged");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void leavesUnsupportedBeamsAndProtectedWoodStay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos log = ground(helper);
        place(level, log, horizontal(Blocks.OAK_LOG));
        helper.assertTrue(new FallenLogs(level, cell -> false).at(log), "a supported horizontal log is debris");
        helper.assertTrue(new Survey(level, Set.of(log)).fallen(cell -> false, state -> state.is(Blocks.DIRT), Set.of()).size() == 1,
            "choosing dirt as fill does not protect natural ground's fallen wood");
        helper.assertTrue(new Survey(level, Set.of(log)).fallen(cell -> false, state -> state.is(Blocks.OAK_LOG), Set.of()).isEmpty(),
            "the selected fill material itself stays");
        helper.assertFalse(new FallenLogs(level, log::equals).at(log), "a colony member log stays");
        helper.assertFalse(new FallenLogs(level, log.below()::equals).at(log), "a beam on a colony building stays");
        place(level, log.east().above(), Blocks.OAK_LEAVES.defaultBlockState());
        helper.assertFalse(new FallenLogs(level, cell -> false).at(log), "diagonally joined leaves protect the log");
        place(level, log.east().above(), Blocks.AIR.defaultBlockState());
        place(level, log.below(), Blocks.AIR.defaultBlockState());
        helper.assertFalse(new FallenLogs(level, cell -> false).at(log), "an elevated beam is not fallen wood");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void fallenWorkRechecksNewTrunksAndProtectedDecorations(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos log = ground(helper);
        place(level, log, horizontal(Blocks.OAK_LOG));
        place(level, log.above(), Blocks.MOSS_CARPET.defaultBlockState());
        Colony colony = Colonies.mint(level.getServer());
        var fixture = new PlanningFixture(helper);
        try {
            ColonyFront.of(colony).addZone(level, GroundworkContent.ZONE, Set.of(log), ColonySettings.empty()
                .with(GroundworkContent.FELL, new ColonySettings.Value.Flag(true))).orElseThrow();
            GroundPresence presence = new GroundPresence(colony);
            presence.refresh(level.getServer());
            Node node = presence.goals(colony.view(level)).getFirst().nodes().getFirst();
            place(level, log.east(), Blocks.OAK_LOG.defaultBlockState());
            helper.assertTrue(node.commit(level, fixture.worker) instanceof Outcome.Failed,
                "a trunk joined after discovery prevents cutting");
            place(level, log.east(), Blocks.AIR.defaultBlockState());
            colony.hold(POINTS, new Held.Point(WorldPos.of(level, log.above()))).orElseThrow();
            helper.assertTrue(node.commit(level, fixture.worker) instanceof Outcome.Failed,
                "newly registered decoration prevents removing its support");
            helper.assertTrue(level.getBlockState(log).is(Blocks.OAK_LOG), "the log stayed through both refusals");
        } finally {
            fixture.worker.body().discard();
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void mushroomsAndVinesAreCollectedAndLogsStayWithinTheMark(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos log = ground(helper);
        place(level, log, horizontal(Blocks.OAK_LOG));
        place(level, log.east(), horizontal(Blocks.OAK_LOG));
        place(level, log.above(), Blocks.BROWN_MUSHROOM.defaultBlockState());
        place(level, log.north(), Blocks.VINE.defaultBlockState());
        place(level, log.south(), Blocks.FERN.defaultBlockState());
        List<Job> jobs = new Survey(level, Set.of(log)).fallen(cell -> false, state -> false, Set.of());
        helper.assertTrue(jobs.size() == 1 && jobs.getFirst().cells().equals(List.of(log)), "only the marked log is taken");
        helper.assertTrue(Set.copyOf(jobs.getFirst().canopy()).equals(Set.of(log.above(), log.north(), log.south())),
            "top mushrooms and side vines come away with the log");
        helper.assertTrue(new Survey(level, Set.of(log)).fell().isEmpty(), "vines alone do not turn fallen wood into a tree");
        Colony colony = Colonies.mint(level.getServer());
        try {
            ColonyFront.of(colony).addZone(level, GroundworkContent.ZONE, Set.of(log, log.south()), ColonySettings.empty()
                .with(GroundworkContent.FELL, new ColonySettings.Value.Flag(true))
                .with(GroundworkContent.WEED, new ColonySettings.Value.Flag(true))).orElseThrow();
            GroundPresence presence = new GroundPresence(colony);
            presence.refresh(level.getServer());
            helper.assertTrue(presence.goals(colony.view(level)).size() == 1,
                "weeding leaves the fallen log's fern to the same clearing job");
        } finally {
            colony.raze();
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void connectivityStopsAtItsBudget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = ground(helper);
        Set<BlockPos> logs = new HashSet<>();
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 8; z++) {
                BlockPos cell = origin.offset(x, 0, z);
                place(level, cell.below(), Blocks.DIRT.defaultBlockState());
                place(level, cell, horizontal(Blocks.OAK_LOG));
                logs.add(cell);
            }
        }
        helper.assertTrue(new Survey(level, logs).fallen(cell -> false, state -> false, Set.of()).isEmpty(),
            "an oversized connected body is left standing");
        helper.succeed();
    }

    private static BlockPos ground(GameTestHelper helper) {
        BlockPos log = helper.absolutePos(new BlockPos(4, 1, 4));
        for (BlockPos cell : BlockPos.betweenClosed(log.offset(-3, -1, -3), log.offset(4, -1, 3))) {
            place(helper.getLevel(), cell, Blocks.DIRT.defaultBlockState());
            for (int y = 1; y <= 5; y++) place(helper.getLevel(), cell.above(y), Blocks.AIR.defaultBlockState());
        }
        return log;
    }

    private static BlockState horizontal(Block block) {
        return block.defaultBlockState().setValue(BlockStateProperties.AXIS, Direction.Axis.X);
    }

    private static void place(ServerLevel level, BlockPos at, BlockState state) {
        level.setBlock(at, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }
}
